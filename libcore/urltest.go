package libcore

import (
	"context"
	"io"
	"net/http"
	"net/http/httptrace"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

// urlTest fetches link twice over client and returns the second request's time from headers
// written to first response byte, so name lookup and the TCP/TLS handshakes stay out of it.
// Redirects count as a reply and are not followed.
func urlTest(client *http.Client, link string, timeout int32) (int32, error) {
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	var wroteHeaders, firstByte time.Time
	ctx = httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
		WroteHeaders:         func() { wroteHeaders = time.Now() },
		GotFirstResponseByte: func() { firstByte = time.Now() },
	})
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	for range 2 {
		response, err := client.Do(request)
		if err != nil {
			return 0, err
		}
		response.Body.Close()
	}
	return int32(firstByte.Sub(wroteHeaders).Milliseconds()), nil
}

// fetchTextLimit bounds an answer FetchText reads: what a service says about the connection, not a download.
const fetchTextLimit = 64 << 10

// fetchText reads a short text answer from link over client, as the services that report the caller's address
// give it. Redirects count as an answer, not followed, and only a 200 is read.
func fetchText(client *http.Client, link string, timeout int32) (string, error) {
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return "", err
	}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", E.New("HTTP ", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, fetchTextLimit))
	return string(body), err
}
