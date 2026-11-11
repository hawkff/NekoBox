package libcore

import (
	"os"
	"strconv"

	"golang.org/x/sys/unix"
)

// holdListener returns a descriptor of its own for this process's socket that listens on port
// where the app's own requests connect: at 127.0.0.1, which a socket at 0.0.0.0 takes too. -1
// when there is none. While the descriptor is open the socket stays bound and listening, whoever
// else closes theirs, and nothing else can listen on that port.
func holdListener(port int32) int {
	descriptors, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return -1
	}
	for _, descriptor := range descriptors {
		fd, err := strconv.Atoi(descriptor.Name())
		if err != nil {
			continue
		}
		// A descriptor of its own before a look: the number itself can be closed and given to
		// something else between two looks, and the answers would then be about two things.
		held, err := unix.FcntlInt(uintptr(fd), unix.F_DUPFD_CLOEXEC, 0)
		if err != nil {
			continue
		}
		if listensOn(held, port) {
			return held
		}
		unix.Close(held)
	}
	return -1
}

func listensOn(socket int, port int32) bool {
	if listening, err := unix.GetsockoptInt(socket, unix.SOL_SOCKET, unix.SO_ACCEPTCONN); err != nil || listening == 0 {
		return false
	}
	name, err := unix.Getsockname(socket)
	at, ok := name.(*unix.SockaddrInet4)
	return err == nil && ok && at.Port == int(port) && (at.Addr == [4]byte{127, 0, 0, 1} || at.Addr == [4]byte{})
}
