package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface.Store(true)
}

// mainInstance is the core the service runs. mainAccess also guards protectCloser, so a core
// that is closing cannot stop the protect server of the core that replaced it.
var (
	mainAccess    sync.Mutex
	mainInstance  *BoxInstance
	protectCloser io.Closer
)

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		conntrack.Close()
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	ctx       context.Context
	cancel    context.CancelFunc
	state     int
	running   bool
	coreWork  sync.WaitGroup
	closeDone chan struct{}

	v2api        *boxapi.SbV2rayServer
	connections  *connectionTracker
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer deferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), certificate.NewRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, newBoxPlatformInterfaceWrapper())

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		ctx:          ctx,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
		connections:  newConnectionTracker(),
	}
	b.Box.Router().AppendTracker(b.connections)

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer deferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		registerTailscaleInterfaces()
		err = b.Box.Start()
		b.running = err == nil
		return err
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	defer deferPanicToError("box.Close", func(err_ error) { err = err_ })

	b.access.Lock()
	if b.state == 2 {
		done := b.closeDone
		b.access.Unlock()
		if done != nil {
			<-done
		}
		return nil
	}
	b.state = 2
	b.running = false
	b.closeDone = make(chan struct{})
	done := b.closeDone
	b.access.Unlock()
	defer close(done)

	// Stop admission before cancellation; workers never need access to finish.
	if b.cancel != nil {
		b.cancel()
	}
	b.coreWork.Wait()

	// clear main instance
	mainAccess.Lock()
	if mainInstance == b {
		mainInstance = nil
		serveProtectLocked(false)
	}
	mainAccess.Unlock()

	// close box. A box whose Start failed has already closed itself, and closing it again
	// reports os.ErrClosed; that must not replace the start error or fail the teardown.
	if b.Box != nil {
		if err := b.Box.Close(); err != nil && !errors.Is(err, os.ErrClosed) {
			return err
		}
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainAccess.Lock()
	defer mainAccess.Unlock()
	mainInstance = b
	serveProtectLocked(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

// SelectedOutbound returns the member tag the "proxy" group (selector or urltest) currently
// routes through, or "" when the config has no such group or urltest has not picked one yet.
func (b *BoxInstance) SelectedOutbound() string {
	proxy, ok := b.Outbound().Outbound("proxy")
	if !ok {
		return ""
	}
	if outboundGroup, ok := proxy.(adapter.OutboundGroup); ok {
		return outboundGroup.Now()
	}
	return ""
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer deferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		mainAccess.Lock()
		i = mainInstance
		mainAccess.Unlock()
	}
	if i == nil {
		// no core: test the direct route
		return urlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout)
	}
	var connectionTracker adapter.ConnectionTracker
	if i.v2api != nil {
		connectionTracker = i.v2api.StatsService()
	}
	return urlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout)
}

// ServeProtect keeps socket protection available to the UI process while the kill switch holds
// the tun with no core running; SetAsMain replaces the server once a core starts again.
func ServeProtect(start bool) {
	mainAccess.Lock()
	defer mainAccess.Unlock()
	serveProtectLocked(start)
}

// serveProtectLocked replaces the protect server; the caller holds mainAccess.
func serveProtectLocked(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = serveProtect("protect_path", func(fd int) error {
			return intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
