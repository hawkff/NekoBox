package libcore

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/slackhq/nebula"
	nebulacert "github.com/slackhq/nebula/cert"
	nebulaconfig "github.com/slackhq/nebula/config"
	"github.com/slackhq/nebula/overlay"
	"github.com/slackhq/nebula/routing"
	"golang.org/x/sys/unix"
)

// NebulaConfig describes a Nebula config for the settings screen.
type NebulaConfig struct {
	// The device's name and its addresses in the network, from its certificate.
	Name      string
	Addresses string
	// When the certificate expires, in Unix seconds.
	Expires int64
	// The networks reached through Nebula, one per line: the overlay's own and its unsafe routes.
	Networks string
}

// ReadNebulaConfig checks a Nebula config the way the endpoint reads it and describes it.
func ReadNebulaConfig(config string) (*NebulaConfig, error) {
	parsed, err := parseNebulaConfig(config, slog.New(slog.DiscardHandler))
	if err != nil {
		return nil, err
	}
	var addresses []string
	for _, network := range parsed.cert.Networks() {
		addresses = append(addresses, network.String())
	}
	var networks []string
	for _, network := range parsed.networks() {
		networks = append(networks, network.String())
	}
	return &NebulaConfig{
		Name:      parsed.cert.Name(),
		Addresses: strings.Join(addresses, "\n"),
		Expires:   parsed.cert.NotAfter().Unix(),
		Networks:  strings.Join(networks, "\n"),
	}, nil
}

type nebulaParsed struct {
	config *nebulaconfig.C
	cert   nebulacert.Certificate
	routes []nebulaRoute
}

// nebulaRoute is an unsafe route: a network outside the overlay reached through one of its hosts.
type nebulaRoute struct {
	network netip.Prefix
	via     netip.Addr
}

func parseNebulaConfig(raw string, l *slog.Logger) (*nebulaParsed, error) {
	config := nebulaconfig.NewC(l)
	if err := config.LoadString(raw); err != nil {
		return nil, E.Cause(err, "read the Nebula config")
	}
	// The app keeps one config, so the CA, certificate and key must be in it rather than in files.
	for _, key := range []string{"pki.ca", "pki.cert", "pki.key"} {
		if !strings.Contains(config.GetString(key, ""), "-----BEGIN") {
			return nil, E.New(key, " must hold the PEM itself, not a file path")
		}
	}
	cert, _, err := nebulacert.UnmarshalCertificateFromPEM([]byte(config.GetString("pki.cert", "")))
	if err != nil {
		return nil, E.Cause(err, "read pki.cert")
	}
	// The pieces must fit together, so a broken file never replaces a working config. Expiry is
	// left to the settings screen, which shows it.
	cas, err := nebulacert.NewCAPoolFromPEM([]byte(config.GetString("pki.ca", "")))
	if err != nil && !errors.Is(err, nebulacert.ErrExpired) {
		return nil, E.Cause(err, "read pki.ca")
	}
	if signer, err := cas.GetCAForCert(cert); err != nil || !cert.CheckSignature(signer.Certificate.PublicKey()) {
		return nil, E.New("pki.cert is not signed by a CA in pki.ca")
	}
	key, _, curve, err := nebulacert.UnmarshalPrivateKeyFromPEM([]byte(config.GetString("pki.key", "")))
	if err != nil {
		return nil, E.Cause(err, "read pki.key")
	}
	if err = cert.VerifyPrivateKey(curve, key); err != nil {
		return nil, E.Cause(err, "pki.key does not belong to pki.cert")
	}
	rawRoutes, ok := config.Get("tun.unsafe_routes").([]any)
	if !ok && config.IsSet("tun.unsafe_routes") {
		return nil, E.New("tun.unsafe_routes must be a list")
	}
	var routes []nebulaRoute
	for i, rawRoute := range rawRoutes {
		route, _ := rawRoute.(map[string]any)
		network, err := netip.ParsePrefix(stringValue(route["route"]))
		if err != nil {
			return nil, E.Cause(err, "tun.unsafe_routes[", i, "].route")
		}
		// Nebula also takes a list of weighted gateways; that is left out here.
		via, err := netip.ParseAddr(stringValue(route["via"]))
		if err != nil {
			return nil, E.Cause(err, "tun.unsafe_routes[", i, "].via must be one overlay address")
		}
		routes = append(routes, nebulaRoute{network.Masked(), via})
	}
	// The most specific route wins.
	slices.SortFunc(routes, func(a, b nebulaRoute) int { return b.network.Bits() - a.network.Bits() })
	return &nebulaParsed{config, cert, routes}, nil
}

func stringValue(value any) string {
	text, _ := value.(string)
	return text
}

// networks are the overlay's networks and the unsafe routes' ones.
func (p *nebulaParsed) networks() []netip.Prefix {
	var networks []netip.Prefix
	for _, network := range p.cert.Networks() {
		networks = append(networks, network.Masked())
	}
	for _, route := range p.routes {
		networks = append(networks, route.network)
	}
	return networks
}

// nebulaEndpointOptions is the "nebula" endpoint's: a Nebula config with its PKI inline.
type nebulaEndpointOptions struct {
	Config string `json:"config"`
}

func registerNebulaEndpoint(registry *endpoint.Registry) {
	endpoint.Register[nebulaEndpointOptions](registry, "nebula", newNebulaEndpoint)
}

var (
	_ adapter.OutboundWithPreferredRoutes = (*nebulaEndpoint)(nil)
	_ adapter.InterfaceUpdateListener     = (*nebulaEndpoint)(nil)
)

// nebulaEndpoint joins a Nebula network inside the core. Nebula keeps its own UDP socket for the
// overlay, and a userspace network stack turns the core's connections into overlay packets, so the
// network needs no tun device of its own. Overlay destinations reach it through preferred routes.
type nebulaEndpoint struct {
	endpoint.Adapter
	ctx       context.Context
	logger    log.ContextLogger
	dnsRouter adapter.DNSRouter
	parsed    *nebulaParsed
	networks  []netip.Prefix
	control   *nebula.Control
	stack     wireguard.Device
	started   atomic.Bool
}

func newNebulaEndpoint(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options nebulaEndpointOptions) (adapter.Endpoint, error) {
	parsed, err := parseNebulaConfig(options.Config, slog.New(nebulaLog{logger: logger}))
	if err != nil {
		return nil, err
	}
	// Nothing on the phone should listen for others: no SSH, metrics or lighthouse DNS server.
	delete(parsed.config.Settings, "sshd")
	delete(parsed.config.Settings, "stats")
	setNebulaValue(parsed.config.Settings, false, "lighthouse", "serve_dns")
	return &nebulaEndpoint{
		Adapter:   endpoint.NewAdapter("nebula", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		ctx:       ctx,
		logger:    logger,
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
		parsed:    parsed,
		networks:  parsed.networks(),
	}, nil
}

func (e *nebulaEndpoint) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStatePostStart {
		return nil
	}
	config := e.parsed.config
	port := config.GetInt("listen.port", 0)
	if port == 0 {
		// A port of our choosing, so the socket Nebula opens on it can be found and protected.
		var err error
		if port, err = freeUDPPort(); err != nil {
			return err
		}
		setNebulaValue(config.Settings, port, "listen", "port")
	}
	var device *nebulaDevice
	control, err := nebula.Main(config, false, "NekoBox", slog.New(nebulaLog{logger: e.logger}), func(_ *nebulaconfig.C, _ *slog.Logger, networks []netip.Prefix, _ int) (overlay.Device, error) {
		user, err := overlay.NewUserDevice(networks)
		if err != nil {
			return nil, err
		}
		device = &nebulaDevice{user.(*overlay.UserDevice), e.parsed.routes}
		return device, nil
	})
	if err != nil {
		return E.Cause(err, "start Nebula")
	}
	scope.Add(func() error {
		control.Stop()
		return nil
	})
	// The app stays inside its own VPN: Nebula's socket must leave through the physical network.
	if platform := service.FromContext[adapter.PlatformInterface](e.ctx); platform != nil && platform.UsePlatformAutoDetectInterfaceControl() {
		if err = protectUDPSockets(port, platform.AutoDetectInterfaceControl); err != nil {
			return E.Cause(err, "protect Nebula's socket")
		}
	}
	stack, err := wireguard.NewDevice(wireguard.DeviceOptions{
		Context: e.ctx,
		Logger:  e.logger,
		MTU:     uint32(config.GetInt("tun.mtu", 1300)),
		Address: device.Networks(),
	})
	if err != nil {
		return err
	}
	scope.Add(stack.Close)
	if err = stack.Start(); err != nil {
		return err
	}
	if err = control.Start(); err != nil {
		return E.Cause(err, "start Nebula")
	}
	reader, writer := device.Pipe()
	go func() {
		packet := make([]byte, 65535)
		for {
			n, err := reader.Read(packet)
			if err != nil {
				return
			}
			_, _ = stack.Write([][]byte{packet[:n]}, 0)
		}
	}()
	go func() {
		packets, sizes := [][]byte{make([]byte, 65535)}, []int{0}
		for {
			if _, err := stack.Read(packets, sizes, 0); err != nil {
				return
			}
			if _, err := writer.Write(packets[0][:sizes[0]]); err != nil {
				return
			}
		}
	}()
	e.control, e.stack = control, stack
	e.started.Store(true)
	return nil
}

func (e *nebulaEndpoint) InterfaceUpdated(context.Context) {
	if e.started.Load() {
		// Tells the lighthouses where the device is now.
		go e.control.RebindUDPServer()
	}
}

func (e *nebulaEndpoint) PreferredDomain(*adapter.InboundContext, string) bool {
	return false
}

func (e *nebulaEndpoint) PreferredAddress(_ *adapter.InboundContext, address netip.Addr) bool {
	return slices.ContainsFunc(e.networks, func(network netip.Prefix) bool { return network.Contains(address) })
}

func (e *nebulaEndpoint) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if !e.started.Load() {
		return nil, E.New("Nebula is not ready yet")
	}
	if destination.IsFqdn() {
		addresses, err := e.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
		if err != nil {
			return nil, err
		}
		return N.DialSerial(ctx, e.stack, network, destination, addresses)
	}
	return e.stack.DialContext(ctx, network, destination)
}

func (e *nebulaEndpoint) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if !e.started.Load() {
		return nil, E.New("Nebula is not ready yet")
	}
	if !destination.IsFqdn() {
		return e.stack.ListenPacket(ctx, destination)
	}
	addresses, err := e.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
	if err != nil {
		return nil, err
	}
	packetConn, address, err := N.ListenSerial(ctx, e.stack, destination, addresses)
	if err != nil {
		return nil, err
	}
	return bufio.NewNATPacketConn(bufio.NewPacketConn(packetConn), M.SocksaddrFrom(address, destination.Port), destination), nil
}

// nebulaDevice is Nebula's userspace device with the unsafe routes, which the plain one ignores.
type nebulaDevice struct {
	*overlay.UserDevice
	routes []nebulaRoute
}

func (d *nebulaDevice) RoutesFor(address netip.Addr) routing.Gateways {
	for _, route := range d.routes {
		if route.network.Contains(address) {
			return routing.Gateways{routing.NewGateway(route.via, 1)}
		}
	}
	return d.UserDevice.RoutesFor(address)
}

// setNebulaValue sets a value in a parsed Nebula config, creating the sections on its path.
func setNebulaValue(settings map[string]any, value any, path ...string) {
	for _, key := range path[:len(path)-1] {
		section, ok := settings[key].(map[string]any)
		if !ok {
			section = map[string]any{}
			settings[key] = section
		}
		settings = section
	}
	settings[path[len(path)-1]] = value
}

func freeUDPPort() (int, error) {
	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).Port, nil
}

// protectUDPSockets protects this process's UDP sockets bound to port. Nebula opens its socket in
// Main and sends nothing before Start, so protecting in between covers all of its traffic.
func protectUDPSockets(port int, protect func(fd int) error) error {
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return err
	}
	found := false
	for _, entry := range entries {
		fd, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		if kind, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE); err != nil || kind != unix.SOCK_DGRAM {
			continue
		}
		address, err := unix.Getsockname(fd)
		if err != nil {
			continue
		}
		switch address := address.(type) {
		case *unix.SockaddrInet4:
			if address.Port != port {
				continue
			}
		case *unix.SockaddrInet6:
			if address.Port != port {
				continue
			}
		default:
			continue
		}
		if err = protect(fd); err != nil {
			return err
		}
		found = true
	}
	if !found {
		return E.New("no UDP socket on port ", port)
	}
	return nil
}

// nebulaLog passes Nebula's log records on to the core's log at the same level. Debug records are
// dropped before Nebula formats them: it logs per packet at that level.
type nebulaLog struct {
	logger log.ContextLogger
	attrs  string
}

func (h nebulaLog) Enabled(_ context.Context, level slog.Level) bool {
	return level >= slog.LevelInfo
}

func (h nebulaLog) Handle(_ context.Context, record slog.Record) error {
	var text strings.Builder
	text.WriteString(record.Message)
	text.WriteString(h.attrs)
	record.Attrs(func(attr slog.Attr) bool {
		text.WriteString(" " + attr.String())
		return true
	})
	switch {
	case record.Level >= slog.LevelError:
		h.logger.Error(text.String())
	case record.Level >= slog.LevelWarn:
		h.logger.Warn(text.String())
	default:
		h.logger.Info(text.String())
	}
	return nil
}

func (h nebulaLog) WithAttrs(attrs []slog.Attr) slog.Handler {
	for _, attr := range attrs {
		h.attrs += " " + attr.String()
	}
	return h
}

func (h nebulaLog) WithGroup(string) slog.Handler {
	return h
}
