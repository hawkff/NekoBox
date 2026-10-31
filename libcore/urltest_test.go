package libcore

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestUrlTestRequestsTwiceWithoutFollowingRedirects(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		// Following this would fail: nothing listens on port 1.
		http.Redirect(w, r, "http://127.0.0.1:1/", http.StatusFound)
	}))
	defer server.Close()

	latency, err := urlTest(&http.Client{Transport: &http.Transport{}}, server.URL, 5000)
	if err != nil {
		t.Fatal(err)
	}
	if hits.Load() != 2 {
		t.Fatalf("expected 2 requests, got %d", hits.Load())
	}
	if latency < 0 {
		t.Fatalf("negative latency %d", latency)
	}
	if _, err := urlTest(&http.Client{Transport: &http.Transport{}}, "http://127.0.0.1:1/", 500); err == nil {
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

	text, err := fetchText(client(), server.URL+"/trace", 5000)
	if err != nil || text != "ip=203.0.113.7\nloc=US\n" {
		t.Fatalf("got %q, %v", text, err)
	}
	if text, err := fetchText(client(), server.URL+"/big", 5000); err != nil || len(text) != fetchTextLimit {
		t.Fatalf("expected the answer cut at %d bytes, got %d, %v", fetchTextLimit, len(text), err)
	}
	if _, err := fetchText(client(), server.URL+"/moved", 5000); err == nil {
		t.Fatal("a redirect is no answer")
	}
	if _, err := fetchText(client(), server.URL+"/missing", 5000); err == nil {
		t.Fatal("a 404 is no answer")
	}
	if _, err := fetchText(client(), "http://127.0.0.1:1/", 500); err == nil {
		t.Fatal("expected an error for a closed port")
	}
}
