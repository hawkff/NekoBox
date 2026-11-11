package libcore

import (
	"net"
	"strconv"
	"testing"

	"golang.org/x/sys/unix"
)

// The core gives a listener up after an accept error it does not retry, and runs on without it.
// The port stays this process's all the same while the listening socket is held, so nothing else
// can take it and the app's own requests with it.
func TestAHeldListenerKeepsItsPortFromEveryoneElse(t *testing.T) {
	for _, address := range []string{"127.0.0.1:0", "0.0.0.0:0"} {
		listener, err := net.Listen("tcp4", address)
		if err != nil {
			t.Fatal(err)
		}
		port := listener.Addr().(*net.TCPAddr).Port
		at := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
		held := holdListener(int32(port))
		if held < 0 {
			t.Fatalf("the listener at %s was not found", address)
		}
		// A connection has the port too, at either end, and is no listener.
		dialed, err := net.Dial("tcp4", at)
		if err != nil {
			t.Fatal(err)
		}
		defer dialed.Close()
		if other := holdListener(int32(dialed.LocalAddr().(*net.TCPAddr).Port)); other >= 0 {
			unix.Close(other)
			t.Error("the port a connection left from was taken for a listener's")
		}
		listener.Close()
		if taken, err := net.Listen("tcp4", at); err == nil {
			taken.Close()
			t.Errorf("the port of the held listener at %s was there to take", address)
		}
		// A connection made meanwhile waits at the held socket and has reached nothing else.
		waiting, err := net.Dial("tcp4", at)
		if err != nil {
			t.Errorf("the held listener at %s takes no connections: %v", address, err)
		} else {
			waiting.Close()
		}
		unix.Close(held)
		free, err := net.Listen("tcp4", at)
		if err != nil {
			t.Fatalf("the port stayed taken after the listener at %s was let go: %v", address, err)
		}
		free.Close()
		if again := holdListener(int32(port)); again >= 0 {
			unix.Close(again)
			t.Errorf("a listener at %s was found after all were closed", address)
		}
	}
	// A listener at another address of this device leaves 127.0.0.1 on that port to anyone.
	if listener, err := net.Listen("tcp4", "127.0.0.2:0"); err == nil {
		defer listener.Close()
		if held := holdListener(int32(listener.Addr().(*net.TCPAddr).Port)); held >= 0 {
			unix.Close(held)
			t.Error("a listener at 127.0.0.2 was taken for one at 127.0.0.1")
		}
	}
}
