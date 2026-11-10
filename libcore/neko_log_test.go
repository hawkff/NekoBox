package libcore

import (
	"os"
	"path/filepath"
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

// stderr is the same open file after setupLog, so both share one offset, and every line the Go
// runtime prints there moves it to the end. A cut that read its tail from that offset kept
// nothing whenever such a line came between its seek and its read.
func TestACutKeepsItsTailWhileStderrWritesToTheSameFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	// A duplicate shares the open file and its offset, as stderr does after Dup2.
	duplicate, err := unix.Dup(int(f.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	stderr := os.NewFile(uintptr(duplicate), "stderr")
	defer stderr.Close()
	stop, stopped := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(stopped)
		for {
			select {
			case <-stop:
				return
			default:
				stderr.Write([]byte("x"))
			}
		}
	}()
	defer func() {
		close(stop)
		<-stopped
	}()

	line := []byte("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	for i := range 2000 {
		f.Write(line)
		unlock := lockLog(f)
		cutLog(f, 32, 32)
		info, _ := f.Stat()
		unlock()
		// The tail, and whatever stderr added since.
		if info.Size() < 32 {
			t.Fatalf("cut %d kept %d bytes", i, info.Size())
		}
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
