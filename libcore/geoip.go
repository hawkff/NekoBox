package libcore

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

// geoipCacheDir holds a country's networks per file, <code>.txt, so a core start does not walk
// the whole database for each geoip: rule set. Set by InitCore; empty means no files.
var geoipCacheDir string

// geoipCacheFormat starts the stamp line; change it when the file layout changes.
const geoipCacheFormat = "geoip-cidr-v1"

// geoipCIDRs returns the networks the database at dbPath gives the country code, from cacheDir,
// or from a scan of the database, which refills it.
func geoipCIDRs(dbPath, cacheDir, code string) ([]string, error) {
	info, err := os.Stat(dbPath)
	if err != nil {
		return nil, err
	}
	// A new database, an asset update for one, has another size or time.
	stamp := fmt.Sprintf("%s %d %d", geoipCacheFormat, info.Size(), info.ModTime().UnixNano())

	cacheFile := ""
	if cacheDir != "" && code != "" && strings.Trim(code, "abcdefghijklmnopqrstuvwxyz0123456789-_") == "" {
		cacheFile = filepath.Join(cacheDir, code+".txt")
	}
	cidrs := readGeoIPCache(cacheFile, stamp)
	if cidrs == nil {
		if cidrs, err = scanGeoIP(dbPath, code); err != nil {
			return nil, err
		}
		if cacheFile != "" {
			if err := writeGeoIPCache(cacheFile, stamp, cidrs); err != nil {
				log.Println("geoip cache:", err)
			}
		}
	}
	return cidrs, nil
}

func scanGeoIP(dbPath, code string) ([]string, error) {
	reader, err := maxminddb.Open(dbPath)
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
	var (
		cidrs   []string
		ipNet   *net.IPNet
		country string
	)
	for networks.Next() {
		ipNet, err = networks.Network(&country)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		if country == code {
			cidrs = append(cidrs, ipNet.String())
		}
	}
	if err := networks.Err(); err != nil {
		return nil, err
	}
	if len(cidrs) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", code)
	}
	return cidrs, nil
}

// readGeoIPCache returns the networks of a cache file made for stamp, or nil when the file is
// missing, made for another database or unreadable.
func readGeoIPCache(path, stamp string) []string {
	if path == "" {
		return nil
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	lines := strings.Split(strings.TrimSuffix(string(content), "\n"), "\n")
	if lines[0] != stamp || len(lines) < 2 {
		return nil
	}
	for _, line := range lines[1:] {
		if _, err := netip.ParsePrefix(line); err != nil {
			return nil
		}
	}
	return lines[1:]
}

// writeGeoIPCache writes a temporary file and renames it, so a reader, in this process or the
// other one, sees a whole file or none, whoever writes at the same time.
func writeGeoIPCache(path, stamp string, cidrs []string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	w := bufio.NewWriter(tmp)
	w.WriteString(stamp + "\n")
	for _, cidr := range cidrs {
		w.WriteString(cidr + "\n")
	}
	err = w.Flush()
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// geoipTempMaxAge is far longer than a write of a cache file takes, so an older temporary file
// is one a crash left behind.
const geoipTempMaxAge = time.Hour

// removeStaleGeoIPTemps deletes the temporary files of writeGeoIPCache that a crash left in dir.
func removeStaleGeoIPTemps(dir string) {
	temps, _ := filepath.Glob(filepath.Join(dir, "*.txt.*.tmp"))
	for _, temp := range temps {
		if info, err := os.Stat(temp); err == nil && time.Since(info.ModTime()) > geoipTempMaxAge {
			os.Remove(temp)
		}
	}
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		cidrs, err := geoipCIDRs(filepath.Join(externalAssetsPath, geoipDat), geoipCacheDir, strings.ToLower(name))
		if err != nil {
			return nil, err
		}
		return []option.HeadlessRule{
			{
				Type:           C.RuleTypeDefault,
				DefaultOptions: option.DefaultHeadlessRule{IPCIDR: cidrs},
			},
		}, nil
	}
}
