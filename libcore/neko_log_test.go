package libcore

import (
	"os"
	"path/filepath"
	"testing"

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
