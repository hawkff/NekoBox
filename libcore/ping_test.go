package libcore

import (
	"errors"
	"syscall"
	"testing"
)

type socketProtectionPlatform struct {
	BoxPlatformInterface
	err error
}

func (p socketProtectionPlatform) AutoDetectInterfaceControl(int32) error { return p.err }

func TestIcmpPingLoopback(t *testing.T) {
	previous := intfBox
	intfBox = socketProtectionPlatform{}
	t.Cleanup(func() { intfBox = previous })
	latency, err := IcmpPing("127.0.0.1", 2000)
	if err != nil {
		// Linux keeps unprivileged ICMP sockets behind net.ipv4.ping_group_range.
		if errors.Is(err, syscall.EACCES) || errors.Is(err, syscall.EPERM) {
			t.Skip("unprivileged ICMP sockets disabled:", err)
		}
		t.Fatal(err)
	}
	if latency < 0 {
		t.Fatalf("negative latency %d", latency)
	}
}

func TestIcmpPingStopsWhenSocketProtectionFails(t *testing.T) {
	previous := intfBox
	failure := errors.New("protection refused")
	intfBox = socketProtectionPlatform{err: failure}
	t.Cleanup(func() { intfBox = previous })
	if _, err := IcmpPing("127.0.0.1", 2000); !errors.Is(err, failure) {
		if errors.Is(err, syscall.EACCES) || errors.Is(err, syscall.EPERM) {
			t.Skip("unprivileged ICMP sockets disabled:", err)
		}
		t.Fatalf("expected protection error, got %v", err)
	}
}

func TestIcmpPingRejectsHostnames(t *testing.T) {
	if _, err := IcmpPing("localhost", 100); err == nil {
		t.Fatal("hostname accepted")
	}
}
