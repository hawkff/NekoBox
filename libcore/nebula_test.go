package libcore

import (
	"context"
	"encoding/binary"
	"fmt"
	"log/slog"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/slackhq/nebula"
	"github.com/slackhq/nebula/cert"
	"github.com/slackhq/nebula/cert_test"
	nebulaconfig "github.com/slackhq/nebula/config"
	"github.com/slackhq/nebula/overlay"
)

// nebulaTestConfigs signs a lighthouse at 10.99.0.1 and a phone at 10.99.0.2 with one CA. The phone
// finds the lighthouse on the loopback at lighthousePort; extra is appended to the phone's config.
func nebulaTestConfigs(lighthousePort int, extra string) (lighthouse, phone string) {
	hour := time.Now().Add(time.Hour)
	ca, _, caKey, caPEM := cert_test.NewTestCaCert(cert.Version1, cert.Curve_CURVE25519, time.Now().Add(-time.Hour), hour, nil, nil, nil)
	config := func(name, address, rest string) string {
		_, _, key, crt := cert_test.NewTestCert(cert.Version1, cert.Curve_CURVE25519, ca, caKey, name, time.Now().Add(-time.Hour), hour,
			[]netip.Prefix{netip.MustParsePrefix(address)}, nil, nil)
		return "pki:\n  ca: |\n" + indentPEM(caPEM) + "  cert: |\n" + indentPEM(crt) + "  key: |\n" + indentPEM(key) +
			"firewall:\n  outbound: [{port: any, proto: any, host: any}]\n  inbound: [{port: any, proto: any, host: any}]\n" + rest
	}
	lighthouse = config("lighthouse", "10.99.0.1/24", fmt.Sprintf("listen: {host: 127.0.0.1, port: %d}\nlighthouse: {am_lighthouse: true}\n", lighthousePort))
	phone = config("phone", "10.99.0.2/24", fmt.Sprintf("listen: {host: 127.0.0.1}\nstatic_host_map: {\"10.99.0.1\": [\"127.0.0.1:%d\"]}\nlighthouse: {hosts: [10.99.0.1]}\n", lighthousePort)+extra)
	return
}

func indentPEM(pem []byte) string {
	var text strings.Builder
	for _, line := range strings.Split(strings.TrimSpace(string(pem)), "\n") {
		text.WriteString("    " + line + "\n")
	}
	return text.String()
}

func TestNebulaEndpointReachesAHostInTheOverlay(t *testing.T) {
	port, err := freeUDPPort()
	if err != nil {
		t.Fatal(err)
	}
	lighthouseConfig, phoneConfig := nebulaTestConfigs(port, "")

	// The lighthouse answers UDP on its overlay address straight from Nebula's userspace device.
	config := nebulaconfig.NewC(slog.New(slog.DiscardHandler))
	if err = config.LoadString(lighthouseConfig); err != nil {
		t.Fatal(err)
	}
	control, err := nebula.Main(config, false, "test", slog.New(slog.DiscardHandler), overlay.NewUserDeviceFromConfig)
	if err != nil {
		t.Fatal(err)
	}
	if err = control.Start(); err != nil {
		t.Fatal(err)
	}
	defer control.Stop()
	go echoUDP(control.Device().(*overlay.UserDevice))

	ctx := context.Background()
	logger := log.NewNOPFactory().Logger()
	phone, err := newNebulaEndpoint(ctx, nil, logger, "nebula", nebulaEndpointOptions{Config: phoneConfig})
	if err != nil {
		t.Fatal(err)
	}
	scope := adapter.NewScope(ctx, logger)
	defer scope.Close()
	for _, stage := range adapter.ListStartStages {
		if err = phone.Start(stage, scope); err != nil {
			t.Fatal(err)
		}
	}
	routes := phone.(adapter.OutboundWithPreferredRoutes)
	if !routes.PreferredAddress(nil, netip.MustParseAddr("10.99.0.1")) || routes.PreferredAddress(nil, netip.MustParseAddr("10.98.0.1")) {
		t.Fatal("the overlay network is not the endpoint's preferred route, or more is")
	}

	lighthouse := M.ParseSocksaddrHostPort("10.99.0.1", 7)
	conn, err := phone.ListenPacket(ctx, lighthouse)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	reply := make([]byte, 64)
	// The first datagram may wait out the handshake; resend until one comes back.
	for i := 0; i < 20; i++ {
		if _, err = conn.WriteTo([]byte("hello over Nebula"), lighthouse.UDPAddr()); err != nil {
			t.Fatal(err)
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Second))
		var n int
		if n, _, err = conn.ReadFrom(reply); err == nil {
			if string(reply[:n]) != "hello over Nebula" {
				t.Fatalf("the lighthouse echoed %q", reply[:n])
			}
			return
		}
	}
	t.Fatal("no echo through Nebula: ", err)
}

// echoUDP sends every IPv4 UDP datagram that arrives at the device back to where it came from.
func echoUDP(device *overlay.UserDevice) {
	reader, writer := device.Pipe()
	packet := make([]byte, 65535)
	for {
		n, err := reader.Read(packet)
		if err != nil {
			return
		}
		ihl := int(packet[0]&0x0f) * 4
		if n < ihl+8 || packet[0]>>4 != 4 || packet[9] != 17 {
			continue
		}
		reply := append([]byte(nil), packet[:n]...)
		copy(reply[12:16], packet[16:20])
		copy(reply[16:20], packet[12:16])
		copy(reply[ihl:ihl+2], packet[ihl+2:ihl+4])
		copy(reply[ihl+2:ihl+4], packet[ihl:ihl+2])
		// A zero UDP checksum means none, in IPv4; the header checksum is recomputed.
		reply[ihl+6], reply[ihl+7] = 0, 0
		reply[10], reply[11] = 0, 0
		var sum uint32
		for i := 0; i < ihl; i += 2 {
			sum += uint32(binary.BigEndian.Uint16(reply[i:]))
		}
		for sum > 0xffff {
			sum = sum&0xffff + sum>>16
		}
		binary.BigEndian.PutUint16(reply[10:], ^uint16(sum))
		if _, err = writer.Write(reply); err != nil {
			return
		}
	}
}

func TestNebulaMTUStaysUnderNebulasCap(t *testing.T) {
	for raw, want := range map[string]uint32{
		"listen: {port: 0}": overlay.DefaultMTU,
		"tun: {mtu: 1400}":  1400,
		"tun: {mtu: 9001}":  uint32(overlay.MaxMTU),
	} {
		config := nebulaconfig.NewC(slog.New(slog.DiscardHandler))
		if err := config.LoadString(raw); err != nil {
			t.Fatal(err)
		}
		if got := nebulaMTU(config); got != want {
			t.Fatalf("%q: MTU %d, want %d", raw, got, want)
		}
	}
}

func TestNebulaConfigNeedsItsPKIInlineAndReadsUnsafeRoutes(t *testing.T) {
	_, phone := nebulaTestConfigs(4242, "tun:\n  unsafe_routes: [{route: 192.168.1.0/24, via: 10.99.0.1}]\n")
	info, err := ReadNebulaConfig(phone)
	if err != nil {
		t.Fatal(err)
	}
	if info.Name != "phone" || info.Addresses != "10.99.0.2/24" || info.Networks != "10.99.0.0/24\n192.168.1.0/24" || info.Expires < time.Now().Unix() {
		t.Fatalf("read %+v", info)
	}

	parsed, err := parseNebulaConfig(phone, slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	gateways := (&nebulaDevice{routes: parsed.routes}).RoutesFor(netip.MustParseAddr("192.168.1.5"))
	if len(gateways) != 1 || gateways[0].Addr() != netip.MustParseAddr("10.99.0.1") {
		t.Fatalf("an unsafe route's address goes to %v", gateways)
	}

	_, err = ReadNebulaConfig("pki:\n  ca: /etc/nebula/ca.crt\n  cert: /etc/nebula/host.crt\n  key: /etc/nebula/host.key\n")
	if err == nil || !strings.Contains(err.Error(), "pki.ca must hold the PEM itself") {
		t.Fatalf("a config with key files was read: %v", err)
	}
	if _, err = ReadNebulaConfig(phone[:strings.Index(phone, "tun:")] + "tun: {unsafe_routes: 192.168.1.0/24}\n"); err == nil || !strings.Contains(err.Error(), "must be a list") {
		t.Fatalf("unsafe routes that are not a list were read: %v", err)
	}
	// Another network's key, or its CA, does not fit this certificate.
	lighthouse, other := nebulaTestConfigs(4242, "")
	keyAt, caEnd := strings.Index(phone, "  key: |"), strings.Index(phone, "  cert: |")
	otherKeyAt, otherKeyEnd := strings.Index(other, "  key: |"), strings.Index(other, "firewall:")
	mixed := phone[:keyAt] + other[otherKeyAt:otherKeyEnd] + phone[strings.Index(phone, "firewall:"):]
	if _, err = ReadNebulaConfig(mixed); err == nil || !strings.Contains(err.Error(), "pki.key does not belong to pki.cert") {
		t.Fatalf("a key of another certificate was read: %v", err)
	}
	mixed = lighthouse[:strings.Index(lighthouse, "  cert: |")] + phone[caEnd:]
	if _, err = ReadNebulaConfig(mixed); err == nil || !strings.Contains(err.Error(), "not signed by a CA in pki.ca") {
		t.Fatalf("a certificate of another CA was read: %v", err)
	}
}
