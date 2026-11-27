package libcore

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
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

// Close waits for a dial of a test through the instance, which the HTTP transport runs apart from the request, and
// the test ends once the instance is closed: no dial goes on through a closed core.
func TestUrlTestsEndWhenTheInstanceCloses(t *testing.T) {
	previous := intfBox
	intfBox = socketProtectionPlatform{}
	t.Cleanup(func() { intfBox = previous })
	tests := map[string]func(*BoxInstance, string) error{
		"outbound": func(i *BoxInstance, link string) error {
			_, err := UrlTestOutbound(i, "direct", link, 60000)
			return err
		},
		"route": func(i *BoxInstance, link string) error { _, err := UrlTest(i, link, 60000); return err },
		"fetch": func(i *BoxInstance, link string) error {
			_, err := FetchTextOutbound(i, "direct", link, 60000)
			return err
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			entered := make(chan struct{}, 1)
			hold := make(chan struct{})
			dialEnteredForTest = func() {
				entered <- struct{}{}
				<-hold
			}
			t.Cleanup(func() { dialEnteredForTest = nil })
			instance, err := NewSingBoxInstance(`{"log": {"disabled": true}, "outbounds": [{"type": "direct", "tag": "direct"}]}`, nil)
			if err != nil {
				t.Fatal(err)
			}
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- test(instance, "http://127.0.0.1:1/") }()
			select {
			case <-entered:
			case <-time.After(5 * time.Second):
				t.Fatal("the test did not dial")
			}
			closed := make(chan struct{})
			go func() {
				instance.Close()
				close(closed)
			}()
			// Close has begun once the instance's lifetime ends; only then may it not finish while the dial is held.
			select {
			case <-instance.ctx.Done():
			case <-time.After(5 * time.Second):
				t.Fatal("Close did not end the instance's lifetime")
			}
			select {
			case <-closed:
				t.Fatal("Close returned while a dial of the test still ran")
			case <-time.After(300 * time.Millisecond):
			}
			close(hold)
			select {
			case <-closed:
			case <-time.After(5 * time.Second):
				t.Fatal("Close did not return after the dial")
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("a test of a closed instance passed")
				}
			case <-time.After(5 * time.Second):
				t.Fatal("the test did not end with the instance")
			}
		})
	}
}

// A test that runs out of time says so, and a failure with a reason of its own does not, whatever its text holds.
func TestURLTestOutcomeTellsATimeoutFromOtherFailures(t *testing.T) {
	answer := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-answer:
		case <-r.Context().Done():
		}
	}))
	defer server.Close()
	defer close(answer)
	client := func() *http.Client { return &http.Client{Transport: &http.Transport{}} }

	outcome := urlTestOutcome(urlTest(context.Background(), client(), server.URL+"/", 300))
	if !outcome.TimedOut || outcome.Error == "" {
		t.Fatalf("an unanswered test: %+v", outcome)
	}
	// Refused at once, with "timeout" in its URL.
	outcome = urlTestOutcome(urlTest(context.Background(), client(), "http://127.0.0.1:1/timeout", 3000))
	if outcome.TimedOut || !strings.Contains(outcome.Error, "timeout") {
		t.Fatalf("a refused test: %+v", outcome)
	}
	// Ended by a closing core.
	lifetime, cancel := context.WithCancel(context.Background())
	cancel()
	if outcome = urlTestOutcome(urlTest(lifetime, client(), server.URL+"/", 3000)); outcome.TimedOut {
		t.Fatalf("a canceled test: %+v", outcome)
	}
	if outcome = urlTestOutcome(7, nil); outcome.Error != "" || outcome.Latency != 7 {
		t.Fatalf("a passed test: %+v", outcome)
	}
}
