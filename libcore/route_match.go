package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// MatchRouteDestination reports whether host, a domain or an IP address, meets the destination
// conditions of a route rule given as JSON: domain, domain_suffix, domain_keyword, domain_regex,
// ip_cidr, ip_is_private and the bundled geosite: and geoip: lists in rule_set. One is enough, as
// in the core. An error names a condition the core would refuse: a site list the database lacks, an
// address list that isn't a country code, a malformed address or expression.
func MatchRouteDestination(ruleJSON string, host string) (bool, error) {
	var conditions struct {
		Domain        []string `json:"domain"`
		DomainSuffix  []string `json:"domain_suffix"`
		DomainKeyword []string `json:"domain_keyword"`
		DomainRegex   []string `json:"domain_regex"`
		IPCIDR        []string `json:"ip_cidr"`
		IPIsPrivate   bool     `json:"ip_is_private"`
		RuleSet       []string `json:"rule_set"`
	}
	if err := json.Unmarshal([]byte(ruleJSON), &conditions); err != nil {
		return false, err
	}
	var metadata adapter.InboundContext
	address, err := netip.ParseAddr(host)
	if err == nil {
		address = address.Unmap()
		metadata.Destination = M.SocksaddrFrom(address, 0)
	} else {
		metadata.Domain = strings.ToLower(host)
		metadata.Destination = M.Socksaddr{Fqdn: metadata.Domain}
	}
	matched := conditions.IPIsPrivate && address.IsValid() && !N.IsPublicAddr(address)
	headless := []option.DefaultHeadlessRule{{
		Domain:        conditions.Domain,
		DomainSuffix:  conditions.DomainSuffix,
		DomainKeyword: conditions.DomainKeyword,
		DomainRegex:   conditions.DomainRegex,
		IPCIDR:        conditions.IPCIDR,
	}}
	var countries []string
	var sites *geosite
	defer func() {
		if sites != nil {
			sites.geositeReader.Upstream().(io.Closer).Close()
		}
	}()
	for _, name := range conditions.RuleSet {
		if code, isSite := strings.CutPrefix(name, "geosite:"); isSite {
			if sites == nil {
				opened := new(geosite)
				if err := opened.Open(filepath.Join(externalAssetsPath, geositeDat)); err != nil {
					return false, err
				}
				sites = opened
			}
			rules, err := sites.Rules(code)
			if err != nil {
				return false, err
			}
			for _, siteRule := range rules {
				headless = append(headless, siteRule.DefaultOptions)
			}
		} else if code, isAddress := strings.CutPrefix(name, "geoip:"); isAddress {
			// The address lists are named by two-letter country codes; checked whatever the host is.
			if len(code) != 2 || strings.Trim(strings.ToLower(code), "abcdefghijklmnopqrstuvwxyz") != "" {
				return false, fmt.Errorf("no address list named %s", name)
			}
			countries = append(countries, strings.ToLower(code))
		}
	}
	// Every condition is built, so a broken one is reported even when another matches.
	for _, options := range headless {
		if !options.IsValid() {
			continue
		}
		item, err := rule.NewDefaultHeadlessRule(context.Background(), options)
		if err != nil {
			return false, err
		}
		attempt := metadata
		matched = matched || item.Match(&attempt)
	}
	if len(countries) > 0 && !matched && address.IsValid() {
		// One lookup, where the core would expand each country into all of its networks.
		reader, err := maxminddb.Open(filepath.Join(externalAssetsPath, geoipDat))
		if err != nil {
			return false, err
		}
		defer reader.Close()
		var country string
		if err := reader.Lookup(net.IP(address.AsSlice()), &country); err != nil {
			return false, err
		}
		for _, code := range countries {
			matched = matched || code == strings.ToLower(country)
		}
	}
	return matched, nil
}
