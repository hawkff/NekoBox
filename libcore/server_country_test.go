package libcore

import (
	"os"
	"path/filepath"
	"testing"
)

func TestServerCountries(t *testing.T) {
	archive := filepath.Join("..", "app", "src", "main", "assets", "sing-box", geoipDat+".xz")
	if _, err := os.Stat(archive); err != nil {
		t.Skip("the GeoIP database is fetched by buildScript/lib/assets.sh")
	}
	unpacked := t.TempDir()
	if err := Unxz(archive, filepath.Join(unpacked, geoipDat)); err != nil {
		t.Fatal(err)
	}
	previous := externalAssetsPath
	externalAssetsPath = unpacked
	t.Cleanup(func() { externalAssetsPath = previous })

	// Public resolvers whose countries do not move; a name, a bad value and a blank line stay empty.
	got, err := ServerCountries("8.8.8.8\n77.88.8.8\nexample.com\n\nnot an address\n::ffff:8.8.4.4")
	if err != nil {
		t.Fatal(err)
	}
	if want := "us\nru\n\n\n\nus"; got != want {
		t.Fatalf("ServerCountries = %q, want %q", got, want)
	}
}
