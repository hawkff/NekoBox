package libcore

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	_ "unsafe"

	"log"

	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	"golang.org/x/sys/unix"
)

//go:linkname resourcePaths github.com/sagernet/sing-box/constant.resourcePaths
var resourcePaths []string

func NekoLogPrintln(s string) {
	log.Println(s)
}

func NekoLogClear() {
	if nekoLog != nil {
		defer lockLog(nekoLog)()
		nekoLog.Truncate(0)
	}
}

func ForceGc() {
	go debug.FreeOSMemory()
}

// deferPanicToError converts a panic in the calling function into an error
// passed to onError. Use with defer.
func deferPanicToError(name string, onError func(error)) {
	if r := recover(); r != nil {
		onError(fmt.Errorf("%s panic: %s\n%s", name, r, debug.Stack()))
	}
}

func InitCore(process, cachePath, internalAssets, externalAssets string,
	maxLogSizeKb int32, logEnable bool,
	if1 AppPlatformInterface, if2 BoxPlatformInterface, if3 LocalDNSTransport,
) {
	defer deferPanicToError("InitCore", func(err error) { log.Println(err) })
	isBgProcess = strings.HasSuffix(process, ":bg")

	appPlatform = if1
	intfBox = if2
	useProcfs = intfBox.UseProcFS()
	gLocalDNSTransport = newPlatformTransport(if3, "", option.LocalDNSServerOptions{})

	// Working dir
	tmp := filepath.Join(cachePath, "../no_backup")
	os.MkdirAll(tmp, 0755)
	os.Chdir(tmp)

	// sing-box fs
	resourcePaths = append(resourcePaths, externalAssets)
	externalAssetsPath = externalAssets
	internalAssetsPath = internalAssets

	// Set up log
	if maxLogSizeKb < 50 {
		maxLogSizeKb = 50
	}
	nekoLogDisable = !logEnable
	if err := setupLog(int(maxLogSizeKb)*1024, filepath.Join(cachePath, "neko.log"), isBgProcess); err != nil {
		log.Println("open log:", err)
	}
	// A fatal panic of the core also goes to core-crash.txt, which the app reports on the next start.
	// SetCrashOutput keeps its own descriptor, so the file can be closed here.
	if isBgProcess {
		if f, err := os.OpenFile(filepath.Join(cachePath, "core-crash.txt"), os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600); err == nil {
			if err := debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
				log.Println("crash output:", err)
			}
			f.Close()
		}
	}

	// nekoutils
	nekoutils.Selector_OnProxySelected = appPlatform.Selector_OnProxySelected

	// Set up some component
	go func() {
		defer deferPanicToError("InitCore-go", func(err error) { log.Println(err) })

		// certs
		pem, err := os.ReadFile(externalAssetsPath + "ca.pem")
		if err == nil {
			updateRootCACerts(pem)
		}

		// bg
		if isBgProcess {
			extractAssets()
		}
	}()
}

// ProtectSocket asks the service process to protect a socket owned by the UI process.
func ProtectSocket(fd int32) error {
	return sendFdToProtect(int(fd), "protect_path")
}

func sendFdToProtect(fd int, path string) error {
	socketFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return fmt.Errorf("failed to create unix socket: %w", err)
	}
	defer unix.Close(socketFd)

	var timeout unix.Timeval
	timeout.Usec = 100 * 1000

	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &timeout)
	_ = unix.SetsockoptTimeval(socketFd, unix.SOL_SOCKET, unix.SO_SNDTIMEO, &timeout)

	err = unix.Connect(socketFd, &unix.SockaddrUnix{Name: path})
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}

	err = unix.Sendmsg(socketFd, nil, unix.UnixRights(fd), nil, 0)
	if err != nil {
		return fmt.Errorf("failed to send: %w", err)
	}

	dummy := []byte{1}
	n, err := unix.Read(socketFd, dummy)
	if err != nil {
		return fmt.Errorf("failed to receive: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("socket closed unexpectedly")
	}
	if dummy[0] != 1 {
		return fmt.Errorf("VPN socket protection failed")
	}
	return nil
}
