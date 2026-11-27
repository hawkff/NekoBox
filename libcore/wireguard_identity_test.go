package libcore

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"slices"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"golang.org/x/crypto/curve25519"
)

// The WireGuard sessions of each config in testdata/wireguard-identities.json as the core decodes it: the app reads
// the same file (WireGuardIdentityTest) and leases these sessions for a core. A config the app finds unreadable it never
// runs; for those, the sessions it could read must be among the core's.
func TestWireGuardIdentitiesAsTheCoreDecodesThem(t *testing.T) {
	raw, err := os.ReadFile("testdata/wireguard-identities.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name       string   `json:"name"`
		Config     string   `json:"config"`
		Identities []string `json:"identities"`
		Unknown    bool     `json:"unknown"`
		// The core refuses the config itself; the app reads it as above, which errs on the side of more sessions.
		CoreRejects bool `json:"core_rejects"`
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			var options option.Options
			err := options.UnmarshalJSONContext(ctx, []byte(c.Config))
			if c.CoreRejects {
				if err == nil {
					t.Fatal("the core decodes it")
				}
				return
			}
			if err != nil {
				if c.Unknown {
					return
				}
				t.Fatalf("the core does not decode it: %v", err)
			}
			sessions, unknown := coreWireGuardSessions(options)
			if c.Unknown {
				// The app refuses to run such a config; what it could read is still what the core reads.
				for _, session := range c.Identities {
					if !slices.Contains(sessions, session) {
						t.Fatalf("the app reads %s, the core %v", session, sessions)
					}
				}
				return
			}
			if unknown || !slices.Equal(sessions, c.Identities) {
				t.Fatalf("sessions %v (unknown %v), the app expects %v", sessions, unknown, c.Identities)
			}
		})
	}
}

// coreWireGuardSessions reads the client and peer keys of the decoded options as the WireGuard transport does.
func coreWireGuardSessions(options option.Options) ([]string, bool) {
	var sessions []string
	unknown := false
	add := func(privateKey string, peers []string) {
		key, err := base64.StdEncoding.DecodeString(privateKey)
		if err != nil || len(key) != 32 || len(peers) == 0 {
			unknown = true
			return
		}
		client, err := curve25519.X25519(key, curve25519.Basepoint)
		if err != nil {
			unknown = true
			return
		}
		for _, peer := range peers {
			peerKey, err := base64.StdEncoding.DecodeString(peer)
			if err != nil || len(peerKey) != 32 {
				unknown = true
				continue
			}
			sessions = append(sessions, hex.EncodeToString(client)+"-"+hex.EncodeToString(peerKey))
		}
	}
	legacy := func(peerPublicKey string, peers []option.LegacyWireGuardPeer) []string {
		var keys []string
		if peerPublicKey != "" {
			keys = append(keys, peerPublicKey)
		}
		for _, peer := range peers {
			keys = append(keys, peer.PublicKey)
		}
		return keys
	}
	for _, outbound := range options.Outbounds {
		switch o := outbound.Options.(type) {
		case *option.LegacyWireGuardOutboundOptions:
			add(o.PrivateKey, legacy(o.PeerPublicKey, o.Peers))
		case *option.AmneziaWGOutboundOptions:
			add(o.PrivateKey, legacy(o.PeerPublicKey, o.Peers))
		}
	}
	for _, endpoint := range options.Endpoints {
		if o, ok := endpoint.Options.(*option.WireGuardEndpointOptions); ok {
			var keys []string
			for _, peer := range o.Peers {
				keys = append(keys, peer.PublicKey)
			}
			add(o.PrivateKey, keys)
		}
	}
	slices.Sort(sessions)
	return slices.Compact(sessions), unknown
}
