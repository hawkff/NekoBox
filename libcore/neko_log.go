package libcore

import (
	"log"
	"os"
	"sync"

	"golang.org/x/sys/unix"
)

// nekoLog is cache/neko.log. The UI and :bg processes of the app append to it concurrently,
// so the trims, every write of the logger and NekoLogClear run under lockLog. What is written
// straight to stderr, which setupLog points at the same file, takes no lock and starts no trim:
// the next write of the logger looks at the size of the whole file. Until setupLog runs, and
// when opening the file fails, messages go to stderr instead.
var (
	nekoLog        *os.File
	nekoLogMax     int
	nekoLogAccess  sync.Mutex
	nekoLogDisable bool
)

// lockLog serializes this process's log file operations and takes the cross-process flock
// around them; call the result to release both. flock does not exclude goroutines that share
// one open file description, so the mutex is what keeps them apart.
func lockLog(f *os.File) func() {
	nekoLogAccess.Lock()
	fd := int(f.Fd())
	unix.Flock(fd, unix.LOCK_EX)
	return func() {
		unix.Flock(fd, unix.LOCK_UN)
		nekoLogAccess.Unlock()
	}
}

// setupLog opens path for append, keeps only its last maxSize bytes when trim is set, and
// routes the standard logger and stderr (Go runtime panics) into it. Later writes of the
// logger cut the file back to maxSize once it is longer than twice that.
func setupLog(maxSize int, path string, trim bool) error {
	if nekoLog != nil {
		return nil
	}
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		return err
	}
	if trim {
		keepLogTail(f, maxSize)
	}
	unix.Dup2(int(f.Fd()), int(os.Stderr.Fd()))
	nekoLog = f
	nekoLogMax = maxSize
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetOutput(nekoLogWriter{})
	return nil
}

// keepLogTail drops everything but the last maxSize bytes of f.
func keepLogTail(f *os.File, maxSize int) {
	defer lockLog(f)()
	cutLog(f, maxSize, maxSize)
}

// cutLog drops everything but the last keep bytes of f once f is longer than limit. The
// caller holds lockLog. The tail is read at its place and not from the file's offset: stderr
// shares that offset once setupLog has run, and every line printed there moves it to the end.
// shortcut: what stderr prints between the read of the tail and the truncation is lost, and
// the Logs screen, the problem report and the API read the file without the lock, so a read
// between the truncation and the rewrite comes back short; give stderr a file of its own and
// read through the core under lockLog if either is ever seen.
func cutLog(f *os.File, limit, keep int) {
	info, err := f.Stat()
	if err != nil || info.Size() <= int64(limit) {
		return
	}
	tail := make([]byte, keep)
	if _, err := f.ReadAt(tail, info.Size()-int64(keep)); err != nil {
		return
	}
	if f.Truncate(0) != nil {
		return
	}
	f.Write(tail)
}

type nekoLogWriter struct{}

func (nekoLogWriter) Write(p []byte) (int, error) {
	if nekoLogDisable {
		return len(p), nil
	}
	if nekoLog == nil {
		return os.Stderr.Write(p)
	}
	defer lockLog(nekoLog)()
	n, err := nekoLog.Write(p)
	// A service that runs for weeks would grow the file without end. It is cut back to the
	// limit once it is longer than twice that, so a full log is not rewritten on every line.
	cutLog(nekoLog, 2*nekoLogMax, nekoLogMax)
	return n, err
}
