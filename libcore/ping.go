package libcore

import (
	"fmt"
	"net"
	"os"
	"syscall"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

// IcmpPing sends one echo request to an IP address and returns the round-trip time in
// milliseconds. It uses an unprivileged ICMP datagram socket (Android opens
// net.ipv4.ping_group_range to every group) and protects the socket like every other libcore
// socket, so the probe leaves through the underlying network while the VPN is up. The socket
// is never bound: Android 9 and older let apps create it but refuse bind(), and the kernel
// assigns the echo ID on the first send.
func IcmpPing(address string, timeout int32) (latency int32, err error) {
	defer deferPanicToError("IcmpPing", func(err_ error) { err = err_ })

	ip := net.ParseIP(address)
	if ip == nil {
		return 0, fmt.Errorf("not an IP address: %s", address)
	}
	family, protocol := syscall.AF_INET, ipv4.ICMPTypeEchoReply.Protocol()
	var echo, reply icmp.Type = ipv4.ICMPTypeEcho, ipv4.ICMPTypeEchoReply
	if ip.To4() == nil {
		family, protocol = syscall.AF_INET6, ipv6.ICMPTypeEchoReply.Protocol()
		echo, reply = ipv6.ICMPTypeEchoRequest, ipv6.ICMPTypeEchoReply
	}

	fd, err := syscall.Socket(family, syscall.SOCK_DGRAM|syscall.SOCK_CLOEXEC, protocol)
	if err != nil {
		return 0, os.NewSyscallError("socket", err)
	}
	file := os.NewFile(uintptr(fd), "icmp")
	conn, err := net.FilePacketConn(file)
	file.Close()
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	if err := protectPacketConn(conn); err != nil {
		return 0, err
	}

	request, err := (&icmp.Message{
		Type: echo,
		Code: 0,
		Body: &icmp.Echo{ID: os.Getpid() & 0xffff, Seq: echoSeq, Data: []byte("nekobox")},
	}).Marshal(nil)
	if err != nil {
		return 0, err
	}

	start := time.Now()
	if err = conn.SetDeadline(start.Add(time.Duration(timeout) * time.Millisecond)); err != nil {
		return 0, err
	}
	if _, err = conn.WriteTo(request, &net.UDPAddr{IP: ip}); err != nil {
		return 0, err
	}
	// The kernel rewrites the echo ID on datagram ICMP sockets, so the sequence is the only
	// field that ties a reply to this request; anything else on the socket is skipped until
	// the deadline, except errors about the destination, which end the probe.
	buffer := make([]byte, 1500)
	for {
		n, _, err := conn.ReadFrom(buffer)
		if err != nil {
			return 0, err
		}
		elapsed := time.Since(start)
		message, err := icmp.ParseMessage(protocol, buffer[:n])
		if err != nil {
			continue
		}
		switch body := message.Body.(type) {
		case *icmp.Echo:
			if message.Type == reply && body.Seq == echoSeq {
				return int32(elapsed.Milliseconds()), nil
			}
		case *icmp.DstUnreach, *icmp.TimeExceeded:
			return 0, fmt.Errorf("unreachable: %v", message.Type)
		}
	}
}

const echoSeq = 1

// The platform decides whether a VPN needs bypassing; a protection failure stops the probe.
func protectPacketConn(conn net.PacketConn) error {
	syscallConn, ok := conn.(syscall.Conn)
	if !ok {
		return fmt.Errorf("ICMP socket does not expose a file descriptor")
	}
	rawConn, err := syscallConn.SyscallConn()
	if err != nil {
		return err
	}
	var protectErr error
	if err := rawConn.Control(func(fd uintptr) {
		protectErr = newBoxPlatformInterfaceWrapper().AutoDetectInterfaceControl(int(fd))
	}); err != nil {
		return err
	}
	return protectErr
}
