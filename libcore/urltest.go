package libcore

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

// urlTest fetches link twice over client and returns the second request's time from headers
// written to first response byte, so name lookup and the TCP/TLS handshakes stay out of it.
// Redirects count as a reply and are not followed. The requests end with lifetime.
func urlTest(lifetime context.Context, client *http.Client, link string, timeout int32) (int32, error) {
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	ctx, cancel := context.WithTimeout(lifetime, time.Duration(timeout)*time.Millisecond)
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

// URLTestOutcome is a connection test as the app reads it: the latency, or the failure and whether the test ran out
// of time, which a failure with a reason of its own, such as a refused connection, did not.
type URLTestOutcome struct {
	Latency  int32
	Error    string
	TimedOut bool
}

func urlTestOutcome(latency int32, err error) *URLTestOutcome {
	if err == nil {
		return &URLTestOutcome{Latency: latency}
	}
	var netErr net.Error
	return &URLTestOutcome{Error: err.Error(), TimedOut: errors.Is(err, context.DeadlineExceeded) || errors.As(err, &netErr) && netErr.Timeout()}
}

// UrlTestOutcome is UrlTest with a timeout told apart from other failures.
func UrlTestOutcome(i *BoxInstance, link string, timeout int32) *URLTestOutcome {
	return urlTestOutcome(UrlTest(i, link, timeout))
}

// UrlTestOutboundOutcome is UrlTestOutbound with a timeout told apart from other failures.
func UrlTestOutboundOutcome(i *BoxInstance, tag string, link string, timeout int32) *URLTestOutcome {
	return urlTestOutcome(UrlTestOutbound(i, tag, link, timeout))
}

// fetchTextLimit bounds an answer FetchText reads: what a service says about the connection, not a download.
const fetchTextLimit = 64 << 10

// fetchText reads a short text answer from link over client, as the services that report the caller's address
// give it. Redirects count as an answer, not followed, and only a 200 is read. The request ends with lifetime.
func fetchText(lifetime context.Context, client *http.Client, link string, timeout int32) (string, error) {
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	ctx, cancel := context.WithTimeout(lifetime, time.Duration(timeout)*time.Millisecond)
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
