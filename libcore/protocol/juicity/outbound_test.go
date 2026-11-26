package juicity

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

// The legacy pin replaces the TLS verifier, so it must not be combined with the TLS options' checks.
func TestOutboundRefusesLegacyPinWithTLSCertificateChecks(t *testing.T) {
	for _, tls := range []option.OutboundTLSOptions{
		{Enabled: true, ServerName: "a.example", CertificateSHA256: badoption.Listable[option.CertificateHash]{make([]byte, 32)}},
		{Enabled: true, ServerName: "a.example", VerifyCertificateByName: []string{"b.example"}},
		{Enabled: true, ServerName: "a.example", CertificatePublicKeySHA256: [][]byte{make([]byte, 32)}},
	} {
		_, err := NewOutbound(context.Background(), nil, log.NewNOPFactory().NewLogger("juicity"), "juicity", JuicityOutboundOptions{
			ServerOptions:               option.ServerOptions{Server: "192.0.2.1", ServerPort: 443},
			UUID:                        "00000000-0000-4000-8000-000000000001",
			OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: &tls},
			PinCertSha256:               "AAAA",
		})
		if err == nil || err.Error() != "pin_cert_sha256 is conflict with tls certificate_sha256, verify_certificate_by_name and certificate_public_key_sha256" {
			t.Fatalf("got %v", err)
		}
	}
}
