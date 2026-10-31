package libcore

import (
	"context"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

func TestServerAddresses(t *testing.T) {
	t.Chdir(t.TempDir())
	// Hosts servers answer without a network: the route's default and an outbound's own resolver
	// give the same name different addresses, so the answer shows which resolver was asked.
	config := `{
		"dns": {"servers": [
			{"type": "hosts", "tag": "dns-direct", "predefined": {"node.example": "192.0.2.5"}},
			{"type": "hosts", "tag": "dns-sub-1", "predefined": {"node.example": "198.51.100.7"}}
		]},
		"route": {"default_domain_resolver": "dns-direct"},
		"outbounds": [
			{"type": "vless", "tag": "proxy", "server": "node.example", "server_port": 443, "uuid": "00000000-0000-4000-8000-000000000001"},
			{"type": "vless", "tag": "subscription", "server": "node.example", "server_port": 443, "uuid": "00000000-0000-4000-8000-000000000001", "domain_resolver": "dns-sub-1"},
			{"type": "direct", "tag": "direct"}
		]
	}`
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	if err := instance.PreStart(); err != nil {
		t.Fatal(err)
	}
	core := &BoxInstance{Box: instance, ctx: ctx, outbounds: options.Outbounds}
	for _, query := range []struct{ tag, name, want string }{
		{"proxy", "node.example", "192.0.2.5"},
		{"subscription", "node.example", "198.51.100.7"},
		// A sidecar's outbound has no resolver of its own: the route's default answers.
		{"direct", "node.example", "192.0.2.5"},
		{"proxy", "::ffff:192.0.2.9", "192.0.2.9"},
	} {
		got, err := ResolveServer(core, query.tag, query.name)
		if err != nil {
			t.Fatalf("%s %s: %v", query.tag, query.name, err)
		}
		if got != query.want {
			t.Fatalf("%s %s: ResolveServer = %q, want %q", query.tag, query.name, got, query.want)
		}
	}
	if _, err := ResolveServer(core, "missing", "node.example"); err == nil {
		t.Fatal("missing: want an error")
	}
}
