package libcore

import (
	"net"
	"strconv"
	"testing"
)

func TestAPortListenerIsFoundAndFollowed(t *testing.T) {
	for _, address := range []string{"127.0.0.1:0", "0.0.0.0:0"} {
		listener, err := net.Listen("tcp4", address)
		if err != nil {
			t.Fatal(err)
		}
		port := listener.Addr().(*net.TCPAddr).Port
		found := FindPortListener(int32(port))
		if found == nil || !found.Alive() {
			t.Fatalf("the listener at %s was not found alive: %v", address, found)
		}
		// A connection has the port too, at either end, and is no listener: one the listener took
		// stays open after it.
		dialed, err := net.Dial("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
		if err != nil {
			t.Fatal(err)
		}
		defer dialed.Close()
		taken, err := listener.Accept()
		if err != nil {
			t.Fatal(err)
		}
		defer taken.Close()
		if FindPortListener(int32(dialed.LocalAddr().(*net.TCPAddr).Port)) != nil {
			t.Error("the port a connection left from was taken for a listener's")
		}
		listener.Close()
		if found.Alive() || FindPortListener(int32(port)) != nil {
			t.Errorf("the closed listener at %s was still found", address)
		}
	}
	// A listener at another address of this device leaves 127.0.0.1 on that port to anyone.
	if listener, err := net.Listen("tcp4", "127.0.0.2:0"); err == nil {
		defer listener.Close()
		if FindPortListener(int32(listener.Addr().(*net.TCPAddr).Port)) != nil {
			t.Error("a listener at 127.0.0.2 was taken for one at 127.0.0.1")
		}
	}
}

// A start's listener is gone, and the next listener on the port gets the descriptor number it
// had: that one belongs to another start, and does not make the first one's alive again.
func TestAPortListenerIsNotTakenForTheNextOneWithItsDescriptor(t *testing.T) {
	for attempt := 0; attempt < 50; attempt++ {
		first, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		port := first.Addr().(*net.TCPAddr).Port
		found := FindPortListener(int32(port))
		first.Close()
		next, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
		if err != nil {
			continue
		}
		again := FindPortListener(int32(port))
		alive := found != nil && found.Alive()
		next.Close()
		if found == nil || again == nil {
			t.Fatalf("a listener on port %d was not found: %v, %v", port, found, again)
		}
		if alive {
			t.Fatal("the closed listener was taken for the next one on its port")
		}
		// Where the kernel names its sockets, no two have one name.
		if found.cookie == again.cookie && (found.cookie != 0 || found.inode == again.inode) {
			t.Fatalf("two listeners are told by the same: cookie %d, inodes %d and %d", found.cookie, found.inode, again.inode)
		}
		if again.fd == found.fd {
			return
		}
	}
	t.Fatal("no listener was given the descriptor number of the one before it")
}
