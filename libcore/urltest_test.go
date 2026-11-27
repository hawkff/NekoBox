package libcore

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

func TestUrlTestRequestsTwiceWithoutFollowingRedirects(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		// Following this would fail: nothing listens on port 1.
		http.Redirect(w, r, "http://127.0.0.1:1/", http.StatusFound)
	}))
	defer server.Close()

	latency, err := urlTest(context.Background(), &http.Client{Transport: &http.Transport{}}, server.URL, 5000)
	if err != nil {
		t.Fatal(err)
	}
	if hits.Load() != 2 {
		t.Fatalf("expected 2 requests, got %d", hits.Load())
	}
	if latency < 0 {
		t.Fatalf("negative latency %d", latency)
	}
	if _, err := urlTest(context.Background(), &http.Client{Transport: &http.Transport{}}, "http://127.0.0.1:1/", 500); err == nil {
		t.Fatal("expected an error for a closed port")
	}
}

func TestFetchTextReadsAShortAnswerOnlyFromA200(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/trace":
			_, _ = w.Write([]byte("ip=203.0.113.7\nloc=US\n"))
		case "/big":
			_, _ = w.Write([]byte(strings.Repeat("a", fetchTextLimit+100)))
		case "/moved":
			http.Redirect(w, r, "http://127.0.0.1:1/", http.StatusFound)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	client := func() *http.Client { return &http.Client{Transport: &http.Transport{}} }

	text, err := fetchText(context.Background(), client(), server.URL+"/trace", 5000)
	if err != nil || text != "ip=203.0.113.7\nloc=US\n" {
		t.Fatalf("got %q, %v", text, err)
	}
	if text, err := fetchText(context.Background(), client(), server.URL+"/big", 5000); err != nil || len(text) != fetchTextLimit {
		t.Fatalf("expected the answer cut at %d bytes, got %d, %v", fetchTextLimit, len(text), err)
	}
	if _, err := fetchText(context.Background(), client(), server.URL+"/moved", 5000); err == nil {
		t.Fatal("a redirect is no answer")
	}
	if _, err := fetchText(context.Background(), client(), server.URL+"/missing", 5000); err == nil {
		t.Fatal("a 404 is no answer")
	}
	if _, err := fetchText(context.Background(), client(), "http://127.0.0.1:1/", 500); err == nil {
		t.Fatal("expected an error for a closed port")
	}
}

// Closing an instance ends a test through it that is still connecting, and Close returns only after that test, so
// no request holds an outbound of a closed core.
func TestUrlTestsEndWhenTheInstanceCloses(t *testing.T) {
	previous := intfBox
	intfBox = socketProtectionPlatform{}
	t.Cleanup(func() { intfBox = previous })
	tests := map[string]func(*BoxInstance, string) error{
		"outbound": func(i *BoxInstance, link string) error { _, err := UrlTestOutbound(i, "direct", link, 60000); return err },
		"route":    func(i *BoxInstance, link string) error { _, err := UrlTest(i, link, 60000); return err },
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			address := unansweredTCPAddress(t)
			instance, err := NewSingBoxInstance(`{"log": {"disabled": true}, "outbounds": [{"type": "direct", "tag": "direct"}]}`, nil)
			if err != nil {
				t.Fatal(err)
			}
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- test(instance, "http://"+address+"/") }()
			time.Sleep(500 * time.Millisecond)
			select {
			case err := <-done:
				t.Fatalf("the test ended before the close: %v", err)
			default:
			}
			closed := make(chan struct{})
			go func() {
				instance.Close()
				close(closed)
			}()
			select {
			case <-closed:
			case <-time.After(5 * time.Second):
				t.Fatal("Close did not return")
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("a test of a closed instance passed")
				}
			default:
				t.Fatal("Close returned while a test still ran through the instance")
			}
		})
	}
}

// unansweredTCPAddress listens with a full accept queue, so a connect to it waits for its handshake until it gives up.
func unansweredTCPAddress(t *testing.T) string {
	t.Helper()
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { syscall.Close(fd) })
	if err = syscall.Bind(fd, &syscall.SockaddrInet4{Addr: [4]byte{127, 0, 0, 1}}); err != nil {
		t.Fatal(err)
	}
	if err = syscall.Listen(fd, 0); err != nil {
		t.Fatal(err)
	}
	name, err := syscall.Getsockname(fd)
	if err != nil {
		t.Fatal(err)
	}
	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(name.(*syscall.SockaddrInet4).Port))
	// The queue holds one connection more than its backlog; once it is full, the listener drops new handshakes.
	for {
		conn, err := net.DialTimeout("tcp", address, 300*time.Millisecond)
		if err != nil {
			return address
		}
		t.Cleanup(func() { conn.Close() })
	}
}
