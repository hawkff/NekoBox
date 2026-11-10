package libcore

import (
	"os"
	"strconv"

	"golang.org/x/sys/unix"
)

// PortListener is the socket a start of the core listens with on the local port, as found right
// after that start: its descriptor, and what tells the socket from whatever the same descriptor
// number is given to later. That is the socket's cookie, a number the kernel gives no two
// sockets. Kernels before 4.12 have none: there the cookie stays 0 and the inode tells alone, a
// 32-bit number that comes back after some four billion others, to a socket that would still have
// to be a listener of this process on the same port.
type PortListener struct {
	port   int32
	fd     int
	inode  uint64
	cookie uint64
}

// FindPortListener returns this process's socket that listens on port where the app's own
// requests connect: at 127.0.0.1, which a socket at 0.0.0.0 takes too. Nil when there is none.
func FindPortListener(port int32) *PortListener {
	descriptors, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return nil
	}
	for _, descriptor := range descriptors {
		fd, err := strconv.Atoi(descriptor.Name())
		if err != nil {
			continue
		}
		if listening, inode, cookie := listeningSocket(fd, port); listening {
			return &PortListener{port: port, fd: fd, inode: inode, cookie: cookie}
		}
	}
	return nil
}

// Alive reports whether that socket is still open and listening. While it is, nothing else can
// listen on its port. The core closes a listener for good after an accept error it does not
// retry, and runs on without it: from then on the port is whoever's binds it next.
func (l *PortListener) Alive() bool {
	listening, inode, cookie := listeningSocket(l.fd, l.port)
	return listening && inode == l.inode && cookie == l.cookie
}

// listeningSocket reports whether fd is a socket that listens on port at 127.0.0.1 or 0.0.0.0,
// with its inode and its cookie, 0 where the kernel has none. It looks at a duplicate: the number
// itself can be closed and given to something else between two looks, and the answers would then
// be about two things.
func listeningSocket(fd int, port int32) (listening bool, inode, cookie uint64) {
	socket, err := unix.FcntlInt(uintptr(fd), unix.F_DUPFD_CLOEXEC, 0)
	if err != nil {
		return false, 0, 0
	}
	defer unix.Close(socket)
	if accepts, err := unix.GetsockoptInt(socket, unix.SOL_SOCKET, unix.SO_ACCEPTCONN); err != nil || accepts == 0 {
		return false, 0, 0
	}
	name, err := unix.Getsockname(socket)
	at, ok := name.(*unix.SockaddrInet4)
	if err != nil || !ok || at.Port != int(port) || (at.Addr != [4]byte{127, 0, 0, 1} && at.Addr != [4]byte{}) {
		return false, 0, 0
	}
	var stat unix.Stat_t
	if unix.Fstat(socket, &stat) != nil {
		return false, 0, 0
	}
	// Only a kernel that does not know the option leaves the inode to tell alone.
	cookie, err = unix.GetsockoptUint64(socket, unix.SOL_SOCKET, unix.SO_COOKIE)
	if err != nil && err != unix.ENOPROTOOPT {
		return false, 0, 0
	}
	return true, uint64(stat.Ino), cookie
}
