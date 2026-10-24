package libcore

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMatchRouteDestination(t *testing.T) {
	assets := filepath.Join("..", "app", "src", "main", "assets", "sing-box")
	if _, err := os.Stat(filepath.Join(assets, geositeDat+".xz")); err != nil {
		t.Skip("rule assets are not in the tree")
	}
	unpacked := t.TempDir()
	for _, name := range []string{geoipDat, geositeDat} {
		if err := Unxz(filepath.Join(assets, name+".xz"), filepath.Join(unpacked, name)); err != nil {
			t.Fatal(err)
		}
	}
	saved := externalAssetsPath
	externalAssetsPath = unpacked
	t.Cleanup(func() { externalAssetsPath = saved })

	for _, c := range []struct {
		rule, host string
		want       bool
	}{
		{`{"domain_suffix":["example.com"]}`, "www.example.com", true},
		{`{"domain_suffix":["example.com"]}`, "badexample.com", false},
		{`{"domain":["example.com"]}`, "www.example.com", false},
		{`{"domain_keyword":["tube"]}`, "youtube.com", true},
		{`{"domain_regex":["(?i)^ads?\\."]}`, "ADS.example.org", true},
		{`{"ip_cidr":["10.0.0.0/8"]}`, "10.1.2.3", true},
		{`{"ip_cidr":["10.0.0.0/8"]}`, "example.com", false},
		{`{"ip_is_private":true}`, "192.168.1.1", true},
		{`{"ip_is_private":true}`, "::ffff:192.168.1.1", true},
		{`{"ip_is_private":true}`, "8.8.8.8", false},
		{`{"rule_set":["geosite:category-ads-all"]}`, "doubleclick.net", true},
		{`{"rule_set":["geosite:category-ads-all"]}`, "example.com", false},
		// The database names this list category-ru; the core falls back to it.
		{`{"rule_set":["geosite:ru"]}`, "yandex.ru", true},
		{`{"rule_set":["geoip:ru"]}`, "77.88.8.8", true},
		{`{"rule_set":["geoip:ru"]}`, "8.8.8.8", false},
		{`{"rule_set":["geoip:ru"]}`, "yandex.ru", false},
		{`{"domain_suffix":["example.com"],"rule_set":["geoip:ru"]}`, "77.88.8.8", true},
		// Sites and addresses in one rule are alternatives, as in the core's rules.
		{`{"domain_suffix":["example.org"],"ip_cidr":["10.0.0.0/8"]}`, "www.example.org", true},
		{`{"domain_suffix":["example.org"],"ip_cidr":["10.0.0.0/8"]}`, "10.1.2.3", true},
		{`{"domain_suffix":["example.org"],"ip_cidr":["10.0.0.0/8"]}`, "example.com", false},
	} {
		got, err := MatchRouteDestination(c.rule, c.host)
		if err != nil || got != c.want {
			t.Errorf("%s for %s = %v, %v; want %v", c.rule, c.host, got, err, c.want)
		}
	}
	for _, broken := range []string{`{"rule_set":["geosite:no-such-list"]}`, `{"rule_set":["geoip:no-such-list"]}`, `{"ip_cidr":["10.0.0.0/33"]}`, `{"domain_regex":["("]}`} {
		if _, err := MatchRouteDestination(broken, "example.com"); err == nil {
			t.Errorf("%s: no error", broken)
		}
	}
}
