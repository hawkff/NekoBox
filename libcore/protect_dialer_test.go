package libcore

import (
	"context"
	"net"
	"sync/atomic"
	"testing"

	M "github.com/sagernet/sing/common/metadata"
)

type recordingProtectPlatform struct {
	BoxPlatformInterface
	protected *atomic.Int32
}

func (p recordingProtectPlatform) AutoDetectInterfaceControl(int32) error {
	p.protected.Add(1)
	return nil
}

// The direct outbound hands its UDP sockets to the platform's protection like its TCP ones. Without
// that, UDP routed direct in VPN mode, such as what a sidecar sends through its mapping inbound,
// re-enters the tunnel.
func TestDirectOutboundProtectsUDPAndTCPSockets(t *testing.T) {
	previous := intfBox
	var protected atomic.Int32
	intfBox = recordingProtectPlatform{protected: &protected}
	t.Cleanup(func() { intfBox = previous })
	instance, err := NewSingBoxInstance(`{
  "log": {"disabled": true},
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {"auto_detect_interface": true}
}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close() })
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	direct, ok := instance.Outbound().Outbound("direct")
	if !ok {
		t.Fatal("no direct outbound")
	}
	packetConn, err := direct.ListenPacket(context.Background(), M.ParseSocksaddrHostPort("127.0.0.1", 9))
	if err != nil {
		t.Fatal(err)
	}
	packetConn.Close()
	if protected.Load() == 0 {
		t.Fatal("the UDP socket was not protected")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	before := protected.Load()
	conn, err := direct.DialContext(context.Background(), "tcp", M.ParseSocksaddr(listener.Addr().String()))
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if protected.Load() == before {
		t.Fatal("the TCP socket was not protected")
	}
}
