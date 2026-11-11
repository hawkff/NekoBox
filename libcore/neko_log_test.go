package libcore

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestKeepLogTailKeepsOnlyTheNewestBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	f.WriteString("0123456789")

	keepLogTail(f, 4)
	if content, _ := os.ReadFile(path); string(content) != "6789" {
		t.Fatalf("kept %q", content)
	}
	keepLogTail(f, 4)
	if content, _ := os.ReadFile(path); string(content) != "6789" {
		t.Fatalf("a file within the limit changed to %q", content)
	}
}

func TestALogWrittenPastTwiceItsLimitIsCutBackToTheLimit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	log, limit, disabled := nekoLog, nekoLogMax, nekoLogDisable
	nekoLog, nekoLogMax, nekoLogDisable = f, 8, false
	defer func() { nekoLog, nekoLogMax, nekoLogDisable = log, limit, disabled }()

	for i := range 5 {
		nekoLogWriter{}.Write([]byte{'a' + byte(i), '1', '2', '3'})
		if info, _ := os.Stat(path); info.Size() > 16 {
			t.Fatalf("the log grew to %d bytes", info.Size())
		}
	}
	// The fifth write made it 20 bytes long, more than twice the limit of 8.
	if content, _ := os.ReadFile(path); string(content) != "d123e123" {
		t.Fatalf("kept %q", content)
	}
}

// Whoever prints to stderr takes no lock, so stderr has a file of its own and not the log, where
// a cut could lose a line. What a run left in that file goes into the log when its process starts
// next, once, and in a size the log keeps.
func TestWhatARunLeftOnStderrGoesIntoTheLogAtTheNextStart(t *testing.T) {
	dir := t.TempDir()
	logFile, err := os.OpenFile(filepath.Join(dir, "neko.log"), os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer logFile.Close()
	log, limit, disabled := nekoLog, nekoLogMax, nekoLogDisable
	nekoLog, nekoLogMax, nekoLogDisable = logFile, 50<<10, false
	defer func() { nekoLog, nekoLogMax, nekoLogDisable = log, limit, disabled }()
	logFile.WriteString("a line of the log\n")

	path := filepath.Join(dir, "core-stderr.txt")
	// A run of the process: it takes what the run before left, and prints.
	run := func(prints string) {
		f, err := stderrFile(path)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		if info, err := f.Stat(); err != nil || info.Size() != 0 {
			t.Fatalf("the file was not emptied for the next run: %v, %v", info, err)
		}
		f.WriteString(prints)
	}
	// As the runtime prints when it dies: no newline at the end.
	const dump = "a line\npanic: boom\n\ngoroutine 1 [running]:\nmain.main()\n\t/x/main.go:1 +0x1"
	run(dump)
	run("")
	logged := string(NekoLogTail(0))
	if !strings.HasPrefix(logged, "a line of the log\n") || !strings.HasSuffix(logged, " the run before this one printed to stderr:\n"+dump+"\n") {
		t.Fatalf("the log holds %q", logged)
	}
	run("")
	if again := string(NekoLogTail(0)); again != logged {
		t.Fatalf("what a run left was put into the log again: %q", again)
	}

	// A panic that starts just before the end that is kept anyway: nothing is left out, and
	// nothing is put between the two parts.
	short := "panic: boom\n" + strings.Repeat("x", nekoLogMax/2-len("anic: boom\n")) + "\n"
	run(short)
	run("")
	if logged := string(NekoLogTail(0)); !strings.HasSuffix(logged, " the run before this one printed to stderr:\n"+short) {
		t.Fatalf("the log ends %q", logged[max(0, len(logged)-80):])
	}

	// A trace of every goroutine, longer than the log keeps: its first lines and its end go in,
	// and a cut of the log back to its limit leaves both.
	run("noise\nfatal error: all of it\n\ngoroutine 1 [running]:\n" + strings.Repeat("goroutine 2 [select]:\n", 20000) + "the end")
	run("")
	nekoLogWriter{}.Write([]byte("a line after\n"))
	keepLogTail(logFile, nekoLogMax)
	logged = string(NekoLogTail(0))
	if len(logged) != nekoLogMax {
		t.Fatalf("the log was not cut back: %d bytes", len(logged))
	}
	at := strings.Index(logged, " the run before this one printed to stderr:\nfatal error: all of it\n\ngoroutine 1 [running]:\n")
	if at < 0 || !strings.Contains(logged[at:], "\n[...]\n") || !strings.HasSuffix(logged, "the end\na line after\n") {
		t.Fatal("the log has not the start and the end of the long trace after its cut")
	}
}

// What cannot be put into the log stays in the file, for the next start to take.
func TestWhatARunLeftOnStderrStaysWhenItCannotBeTaken(t *testing.T) {
	dir := t.TempDir()
	closed, err := os.OpenFile(filepath.Join(dir, "neko.log"), os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	closed.Close()
	log := nekoLog
	nekoLog = closed
	defer func() { nekoLog = log }()
	path := filepath.Join(dir, "core-stderr.txt")
	if err := os.WriteFile(path, []byte("panic: kept\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := stderrFile(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if left, _ := os.ReadFile(path); string(left) != "panic: kept\n" {
		t.Fatalf("the file holds %q after a log that could not be written", left)
	}
}

func TestNekoLogTailReturnsTheNewestBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	log, limit, disabled := nekoLog, nekoLogMax, nekoLogDisable
	nekoLog, nekoLogMax, nekoLogDisable = f, 1<<20, false
	defer func() { nekoLog, nekoLogMax, nekoLogDisable = log, limit, disabled }()
	f.WriteString("0123456789")

	for max, want := range map[int64]string{4: "6789", 0: "0123456789", 100: "0123456789"} {
		if got := string(NekoLogTail(max)); got != want {
			t.Fatalf("the newest %d bytes read as %q", max, got)
		}
	}
}

// A reader that measured the file and then read it could fall between a cut's truncation and
// its rewrite, and come back short or empty. A read waits for the trim instead, whoever makes
// it: a goroutine of this process, held off by the mutex, or the app's other process, held off
// by the lock on the file.
func TestALogReadWaitsForATrimToFinish(t *testing.T) {
	for _, otherProcess := range []bool{false, true} {
		name := "a trim in this process"
		if otherProcess {
			name = "a trim in the other process"
		}
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "neko.log")
			f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
			if err != nil {
				t.Fatal(err)
			}
			defer f.Close()
			log, limit, disabled := nekoLog, nekoLogMax, nekoLogDisable
			nekoLog, nekoLogMax, nekoLogDisable = f, 1<<20, false
			defer func() { nekoLog, nekoLogMax, nekoLogDisable = log, limit, disabled }()
			f.WriteString("0123456789")

			// The trim under way: the lock is held and the file is empty.
			trimming := f
			var unlock func()
			if otherProcess {
				// Another open file of the same log, as the other process has one. This
				// process's mutex is free, so only the lock on the file holds a reader off.
				trimming, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND, 0o644)
				if err != nil {
					t.Fatal(err)
				}
				defer trimming.Close()
				if err := unix.Flock(int(trimming.Fd()), unix.LOCK_EX); err != nil {
					t.Fatal(err)
				}
				unlock = sync.OnceFunc(func() { unix.Flock(int(trimming.Fd()), unix.LOCK_UN) })
			} else {
				unlock = sync.OnceFunc(lockLog(f))
			}
			defer unlock()
			if err := trimming.Truncate(0); err != nil {
				t.Fatal(err)
			}
			var got []byte
			entered, returned := make(chan struct{}), make(chan struct{})
			go func() {
				defer close(returned)
				close(entered)
				got = NekoLogTail(4)
			}()
			// However the test ends, the reader is let in and is back before the log is put back.
			defer func() {
				unlock()
				<-returned
			}()
			// The reader is on its way in. With nothing to hold it, it would be back within the
			// time it is given here, with what it found: an empty file.
			<-entered
			select {
			case <-returned:
				t.Fatalf("a read during the trim returned %q", got)
			case <-time.After(100 * time.Millisecond):
			}
			trimming.WriteString("0123456789")
			unlock()
			<-returned
			if string(got) != "6789" {
				t.Fatalf("the read after the trim returned %q", got)
			}
		})
	}
}

// The same under load: reads of the newest bytes while the writer keeps cutting the file.
func TestALogReadNeverFallsIntoACut(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	log, limit, disabled := nekoLog, nekoLogMax, nekoLogDisable
	nekoLog, nekoLogMax, nekoLogDisable = f, 64, false
	defer func() { nekoLog, nekoLogMax, nekoLogDisable = log, limit, disabled }()
	line := []byte("0123456789abcdef")
	for range 4 {
		nekoLogWriter{}.Write(line)
	}

	var reads atomic.Int64
	stop, stopped := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(stopped)
		for {
			select {
			case <-stop:
				return
			default:
				if got := len(NekoLogTail(32)); got != 32 {
					t.Errorf("a read of the newest 32 bytes returned %d", got)
					return
				}
				reads.Add(1)
			}
		}
	}()
	// Every ninth line takes the file past twice its limit and cuts it back. The writer goes
	// on until the reader has read a thousand times beside it.
	for written := 0; written < 5000 || reads.Load() < 1000; written++ {
		if written > 5_000_000 || t.Failed() {
			break
		}
		nekoLogWriter{}.Write(line)
	}
	close(stop)
	<-stopped
	if reads.Load() < 1000 && !t.Failed() {
		t.Fatalf("the reader read %d times beside the writer", reads.Load())
	}
}
