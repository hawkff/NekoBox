package libcore

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"syscall"
	"time"
)

// serveProtect listens on the unix socket at path. Each client connection carries one socket
// fd over SCM_RIGHTS; the fd is passed to protect, closed, and one byte answers the client.
// Clients are this app's UI process (sendFdToProtect) and the mieru sidecar (MIERU_PROTECT_PATH).
func serveProtect(path string, protect func(fd int) error) io.Closer {
	os.Remove(path)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		log.Println("protect server listen:", err)
		return nil
	}
	go func() {
		for {
			conn, err := listener.AcceptUnix()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				// A client that connects and never sends must not pin this goroutine.
				conn.SetDeadline(time.Now().Add(5 * time.Second))
				fd, err := receiveFd(conn)
				if err != nil {
					log.Println("protect server:", err)
					return
				}
				defer syscall.Close(fd)
				if err := protect(fd); err != nil {
					log.Println("protect server:", err)
					// Older sidecars check only the reply length, so a failure must send no byte.
					return
				}
				conn.Write([]byte{1})
			}()
		}
	}()
	return listener
}

// receiveFd reads the single fd a protect client sends along with its one data byte.
func receiveFd(conn *net.UnixConn) (int, error) {
	// Room for a second fd, so a message carrying several is rejected, not silently truncated.
	oob := make([]byte, syscall.CmsgSpace(2*4))
	_, oobn, _, _, err := conn.ReadMsgUnix(make([]byte, 1), oob)
	if err != nil {
		return 0, err
	}
	messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return 0, err
	}
	if len(messages) != 1 {
		return 0, fmt.Errorf("protect: %d control messages", len(messages))
	}
	fds, err := syscall.ParseUnixRights(&messages[0])
	if err != nil {
		return 0, err
	}
	if len(fds) != 1 {
		for _, fd := range fds {
			syscall.Close(fd)
		}
		return 0, fmt.Errorf("protect: %d fds", len(fds))
	}
	return fds[0], nil
}
