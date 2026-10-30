package libcore

import (
	"net"
	"net/netip"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
)

// ServerCountries gives the GeoIP country code of each newline-separated address, lowercase and one
// per line. A line stays empty when its address is a name, which would need a DNS query, or when the
// database does not know it.
func ServerCountries(addresses string) (string, error) {
	reader, err := maxminddb.Open(filepath.Join(externalAssetsPath, geoipDat))
	if err != nil {
		return "", err
	}
	defer reader.Close()
	lines := strings.Split(addresses, "\n")
	for i, line := range lines {
		lines[i] = ""
		address, err := netip.ParseAddr(strings.TrimSpace(line))
		if err != nil {
			continue
		}
		var country string
		if reader.Lookup(net.IP(address.Unmap().AsSlice()), &country) == nil {
			lines[i] = strings.ToLower(country)
		}
	}
	return strings.Join(lines, "\n"), nil
}
