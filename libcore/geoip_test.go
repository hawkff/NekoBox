package libcore

import (
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestGeoIPCIDRsCache(t *testing.T) {
	archive := filepath.Join("..", "app", "src", "main", "assets", "sing-box", geoipDat+".xz")
	if _, err := os.Stat(archive); err != nil {
		t.Skip("the GeoIP database is fetched by buildScript/lib/assets.sh")
	}
	db := filepath.Join(t.TempDir(), geoipDat)
	if err := Unxz(archive, db); err != nil {
		t.Fatal(err)
	}
	cacheDir := filepath.Join(t.TempDir(), "geoip")
	cacheFile := filepath.Join(cacheDir, "us.txt")
	lookup := func() []string {
		t.Helper()
		cidrs, err := geoipCIDRs(db, cacheDir, "us")
		if err != nil {
			t.Fatal(err)
		}
		return cidrs
	}
	stampOf := func() string {
		t.Helper()
		content, err := os.ReadFile(cacheFile)
		if err != nil {
			t.Fatal(err)
		}
		return strings.SplitN(string(content), "\n", 2)[0]
	}

	// A scan keeps the country's networks only and writes them down.
	scanned := lookup()
	google := netip.MustParseAddr("8.8.8.8")
	if !slices.ContainsFunc(scanned, func(cidr string) bool { return netip.MustParsePrefix(cidr).Contains(google) }) {
		t.Fatal("8.8.8.8 is not in the us networks")
	}
	if slices.ContainsFunc(scanned, func(cidr string) bool {
		return netip.MustParsePrefix(cidr).Contains(netip.MustParseAddr("77.88.8.8"))
	}) {
		t.Fatal("a ru address is in the us networks")
	}
	stamp := stampOf()
	if !strings.HasPrefix(stamp, geoipCacheFormat+" ") {
		t.Fatalf("stamp %q", stamp)
	}
	if got := readGeoIPCache(cacheFile, stamp); !slices.Equal(got, scanned) {
		t.Fatal("the cache file does not hold the scanned networks")
	}

	// A file for this database is used as it is: the networks below are not the database's.
	marked := []string{"192.0.2.0/24", "2001:db8::/32"}
	if err := writeGeoIPCache(cacheFile, stamp, marked); err != nil {
		t.Fatal(err)
	}
	if got := lookup(); !slices.Equal(got, marked) {
		t.Fatalf("cache hit: got %v", got)
	}
	// Nothing is kept in memory: without the file, the database is scanned again.
	if err := os.Remove(cacheFile); err != nil {
		t.Fatal(err)
	}
	if got := lookup(); !slices.Equal(got, scanned) {
		t.Fatal("file removed: the database was not scanned again")
	}

	// Another database, as after an asset update, is scanned again.
	if err := writeGeoIPCache(cacheFile, stamp, marked); err != nil {
		t.Fatal(err)
	}
	later := time.Now().Add(time.Hour)
	if err := os.Chtimes(db, later, later); err != nil {
		t.Fatal(err)
	}
	if got := lookup(); !slices.Equal(got, scanned) {
		t.Fatal("stamp change: the database was not scanned again")
	}
	if stampOf() == stamp {
		t.Fatal("stamp change: the cache file was not rewritten")
	}

	// A broken file is replaced by a scan.
	stamp = stampOf()
	for _, broken := range []string{stamp + "\n8.8.8.0/24\nnot a network\n", stamp + "\n", "", "\x00\x01"} {
		if err := os.WriteFile(cacheFile, []byte(broken), 0o644); err != nil {
			t.Fatal(err)
		}
		if got := lookup(); !slices.Equal(got, scanned) {
			t.Fatalf("broken file %q: not scanned again", broken)
		}
		if got := readGeoIPCache(cacheFile, stamp); !slices.Equal(got, scanned) {
			t.Fatalf("broken file %q: not rewritten", broken)
		}
	}

	if _, err := geoipCIDRs(db, cacheDir, "no-such-list"); err == nil {
		t.Fatal("an unknown list has networks")
	}
	if _, err := os.Stat(filepath.Join(cacheDir, "no-such-list.txt")); !os.IsNotExist(err) {
		t.Fatal("an unknown list was written down")
	}
	// Nothing but the cache files is left behind.
	entries, err := os.ReadDir(cacheDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "us.txt" {
		t.Fatalf("cache directory holds %v", entries)
	}
}

func TestRemoveStaleGeoIPTempsKeepsFilesInUse(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"us.txt", "us.txt.1.tmp", "ru.txt.2.tmp"} {
		if err := os.WriteFile(filepath.Join(dir, name), nil, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	old := time.Now().Add(-geoipTempMaxAge - time.Minute)
	for _, name := range []string{"us.txt", "ru.txt.2.tmp"} {
		if err := os.Chtimes(filepath.Join(dir, name), old, old); err != nil {
			t.Fatal(err)
		}
	}
	removeStaleGeoIPTemps(dir)
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	// The old temporary file goes; a recent one may still be written, and the cache file stays.
	if !slices.Equal(names, []string{"us.txt", "us.txt.1.tmp"}) {
		t.Fatalf("left %v", names)
	}
}
