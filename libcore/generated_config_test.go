package libcore

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

// Run after :app:testOssDebugUnitTest, with NEKOBOX_CONFIG_TEST_DIR pointing to
// app/build/generated-core-configs. Inputs come from the application builder.
func TestGeneratedApplicationConfigs(t *testing.T) {
	root := os.Getenv("NEKOBOX_CONFIG_TEST_DIR")
	if root == "" {
		t.Skip("NEKOBOX_CONFIG_TEST_DIR is not set")
	}
	root, err := filepath.Abs(root)
	if err != nil {
		t.Fatal(err)
	}
	// geoip: and geosite: rule sets read the databases the app unpacks from its assets.
	assets := os.Getenv("NEKOBOX_ASSETS_DIR")
	if assets == "" {
		assets = filepath.Join("..", "app", "src", "main", "assets", "sing-box")
	}
	unpacked := t.TempDir()
	for _, name := range []string{geoipDat, geositeDat} {
		if err := Unxz(filepath.Join(assets, name+".xz"), filepath.Join(unpacked, name)); err != nil {
			t.Fatal(err)
		}
	}
	externalAssetsPath = unpacked
	count := 0
	err = filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || filepath.Ext(path) != ".json" {
			return nil
		}
		count++
		t.Run(filepath.Base(path), func(t *testing.T) {
			// Endpoints create their state directories relative to the working directory.
			t.Chdir(t.TempDir())
			content, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
			ctx = service.ContextWithDefaultRegistry(ctx)
			var options option.Options
			if err := options.UnmarshalJSONContext(ctx, content); err != nil {
				t.Fatal(err)
			}
			if options.Experimental != nil && options.Experimental.CacheFile != nil {
				options.Experimental.CacheFile.Path = filepath.Join(t.TempDir(), "cache.db")
			}
			// The Linux check cannot enable Android's VPN interface selection.
			if options.Route != nil {
				options.Route.OverrideAndroidVPN = false
			}
			instance, err := box.New(box.Options{Options: options, Context: ctx})
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { instance.Close() })
			// PreStart initializes DNS, routing and outbounds without opening the
			// Android TUN interface or any application listeners.
			if err := instance.PreStart(); err != nil {
				t.Fatal(err)
			}
			if filepath.Base(filepath.Dir(path)) == "tailscale-routing" {
				// Expected tags are supplied independently by the fixture, never copied
				// from route.final: implicit direct fallback must not pass this check.
				expected, err := os.ReadFile(path + ".expected-tag")
				if err != nil {
					t.Fatal(err)
				}
				expectedTag := strings.TrimSpace(string(expected))
				if expectedTag == "" {
					t.Fatal("empty expected default outbound tag")
				}
				defaultOutbound := instance.Outbound().Default()
				if defaultOutbound == nil {
					t.Fatal("missing default outbound")
				}
				if actual := defaultOutbound.Tag(); actual != expectedTag {
					t.Fatalf("default outbound = %q, want %q", actual, expectedTag)
				}
			}
			if filepath.Base(filepath.Dir(path)) == "routing" {
				// The rule sets come from the real databases, not empty placeholders.
				router := service.FromContext[adapter.Router](ctx)
				for tag, metadata := range map[string]*adapter.InboundContext{
					"geosite:category-ads-all": {Domain: "doubleclick.net"},
					"geoip:ru":                 {Destination: M.ParseSocksaddrHostPort("77.88.8.8", 53)},
				} {
					ruleSet, loaded := router.RuleSet(tag)
					if !loaded {
						t.Fatalf("missing rule-set %s", tag)
					}
					metadata.ResetRuleCache()
					if !ruleSet.Match(metadata) {
						t.Fatalf("rule-set %s does not match %v", tag, metadata)
					}
				}
			}
			if filepath.Base(filepath.Dir(path)) != "dns" || filepath.Base(path) == "probe.json" {
				return
			}
			var raw struct {
				DNS struct {
					Servers []struct {
						Type string `json:"type"`
					} `json:"servers"`
				} `json:"dns"`
			}
			if err := json.Unmarshal(content, &raw); err != nil {
				t.Fatal(err)
			}
			fake := false
			for _, server := range raw.DNS.Servers {
				fake = fake || server.Type == "fakeip"
			}
			router := service.FromContext[adapter.DNSRouter](ctx)
			queryCtx := adapter.WithContext(ctx, &adapter.InboundContext{Inbound: "tun-in"})
			for _, name := range []string{"mapped.example.", "blocked.example."} {
				for _, family := range []uint16{dns.TypeA, dns.TypeAAAA} {
					query := new(dns.Msg).SetQuestion(name, family)
					response, err := router.Exchange(queryCtx, query, adapter.DNSQueryOptions{})
					if err != nil {
						t.Fatal(err)
					}
					if response.Rcode != dns.RcodeSuccess {
						t.Fatalf("%s: rcode %d", name, response.Rcode)
					}
					if name == "mapped.example." && family == dns.TypeA {
						if len(response.Answer) != 1 || response.Answer[0].(*dns.A).A.String() != "192.0.2.100" {
							t.Fatalf("hosts rewrite: %v", response.Answer)
						}
					} else if len(response.Answer) != 0 {
						t.Fatalf("unexpected answer: %v", response.Answer)
					}
				}
			}
			if fake {
				for _, family := range []uint16{dns.TypeA, dns.TypeAAAA} {
					response, err := router.Exchange(queryCtx, new(dns.Msg).SetQuestion("fake.example.", family), adapter.DNSQueryOptions{})
					if err != nil {
						t.Fatal(err)
					}
					if family == dns.TypeA && len(response.Answer) != 1 {
						t.Fatal("missing fake address")
					}
					if family == dns.TypeAAAA && len(response.Answer) != 0 {
						t.Fatal("unexpected fake IPv6 address")
					}
				}
			}
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if count == 0 {
		t.Fatal("no generated configurations")
	}
	t.Logf("validated %d application configurations", count)
}
