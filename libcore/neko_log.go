package libcore

import (
	"bytes"
	"log"
	"os"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

// nekoLog is cache/neko.log. The UI and :bg processes of the app append to it concurrently,
// so the trims, every write of the logger and NekoLogClear run under lockLog. Nothing writes to
// it without: stderr, where the runtime prints with no lock, has a file of its own (see
// stderrFile). Until setupLog runs, and when opening the file fails, messages go to stderr
// instead.
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
// routes the standard logger into it. Later writes of the logger cut the file back to maxSize
// once it is longer than twice that.
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
// caller holds lockLog, as every writer of the file does, so nothing is written between the
// read of the tail and its rewrite.
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

// stderrRead is how much of what a run left on stderr the next one reads.
const stderrRead = 16 << 20

// lastPanicAt returns where the last panic the Go runtime printed in stderr starts, at the start
// of a line, or -1.
func lastPanicAt(stderr []byte) int {
	at := -1
	for _, marker := range []string{"panic: ", "fatal error: "} {
		if bytes.HasPrefix(stderr, []byte(marker)) {
			at = max(at, 0)
		}
		if i := bytes.LastIndex(stderr, []byte("\n"+marker)); i >= 0 {
			at = max(at, i+1)
		}
	}
	return at
}

// stderrFile opens path, the file this process's stderr is to go to, for the caller to point
// stderr at. What the run before left there is taken out first: see takeLeft. When that fails it
// stays, for the next start to take, and what this run prints follows it.
func stderrFile(path string) (*os.File, error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return nil, err
	}
	if err := takeLeft(f); err != nil {
		log.Println("stderr of the run before:", err)
	}
	return f, nil
}

// takeLeft puts what the run before left in f into the log, under the log's lock like every
// other line, and empties f. Nothing else writes to f at that moment: the app has one process of
// a kind, each kind has a file of its own, and this process's stderr is not there yet.
func takeLeft(f *os.File) error {
	info, err := f.Stat()
	if err != nil || info.Size() == 0 {
		return err
	}
	left := make([]byte, min(info.Size(), stderrRead))
	if _, err := f.ReadAt(left, info.Size()-int64(len(left))); err != nil {
		return err
	}
	// Also with logging off, as a panic of the runtime has always reached the log.
	if nekoLog != nil {
		// No more than the log keeps when it is cut back, so the next cut leaves it whole: the
		// end of what was left and, when that is longer, the start of its last panic ahead of
		// the end. A trace of every goroutine runs to hundreds of kilobytes, and says what went
		// wrong in its first lines.
		text := []byte(time.Now().UTC().Format("2006/01/02 15:04:05") + " the run before this one printed to stderr:\n")
		end := left[max(0, len(left)-nekoLogMax/2):]
		if at := lastPanicAt(left); at >= 0 && at < len(left)-len(end) {
			start := left[at:min(len(left)-len(end), at+nekoLogMax/4)]
			text = append(text, start...)
			// Only where something is left out between the two.
			if at+len(start) < len(left)-len(end) {
				text = append(text, "\n[...]\n"...)
			}
		}
		text = append(text, end...)
		if text[len(text)-1] != '\n' {
			text = append(text, '\n')
		}
		unlock := lockLog(nekoLog)
		_, err := nekoLog.Write(text)
		unlock()
		if err != nil {
			return err
		}
	}
	return f.Truncate(0)
}

// NekoLogTail returns the newest max bytes of the log, or all of it when max is not positive.
// It measures and reads under lockLog, so no trim runs in between: a reader that took the
// length on its own could find the file shorter, or empty, by the time it read.
func NekoLogTail(max int64) []byte {
	if nekoLog == nil {
		return nil
	}
	defer lockLog(nekoLog)()
	info, err := nekoLog.Stat()
	if err != nil {
		return nil
	}
	size := info.Size()
	if max > 0 && size > max {
		size = max
	}
	tail := make([]byte, size)
	if _, err := nekoLog.ReadAt(tail, info.Size()-size); err != nil {
		return nil
	}
	return tail
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
