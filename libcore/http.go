package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

// errOwnPortGone ends a request of a client whose local port is no longer the one it was set up
// with: see OwnPortGuard.
var errOwnPortGone = errors.New("the proxy this request was set up to go through no longer runs")

// OwnPortGuard tells a client whether the start of the service it was set up in still runs, with
// its local port still open.
type OwnPortGuard interface {
	SameStart() bool
}

const (
	defaultHTTPTimeout     = 120 * time.Second
	defaultHTTPDialTimeout = 30 * time.Second
	// defaultHTTPRequestTimeout bounds the whole request including body reads,
	// so GetContentLimited/WriteToLimited cannot block forever on a stalled body.
	// It is generous enough for large rule-asset downloads over slow links.
	defaultHTTPRequestTimeout = 10 * time.Minute
	// defaultHTTPStringLimit caps how much of an error body is read for messages.
	defaultHTTPStringLimit = 10 * 1024 * 1024
)

type HTTPClient interface {
	SetTimeoutMs(timeoutMs int32) error
	RestrictedTLS()
	ModernTLS()
	ThroughSocks5(port int32, username string, password string, guard OwnPortGuard)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetUserAgent(userAgent string)
	SetHeader(key string, value string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) string
	GetContentLimited(limit int64) ([]byte, error)
	GetContentStringLimited(limit int64) (string, error)
	WriteToLimited(path string, limit int64) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	throughSocks5 bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	dialer := &net.Dialer{Timeout: defaultHTTPDialTimeout}
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.DialContext = dialer.DialContext
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.TLSHandshakeTimeout = defaultHTTPTimeout
	client.h1h2Transport.ResponseHeaderTimeout = defaultHTTPTimeout
	client.h1h2Transport.DisableKeepAlives = true
	// Bound the full request (including body read) so callers cannot hang forever.
	client.h1h2Client.Timeout = defaultHTTPRequestTimeout
	return client
}

// SetTimeoutMs bounds each request, body read included, on every path: direct, SOCKS and the
// direct HTTPS/HTTP3 race.
func (c *httpClient) SetTimeoutMs(timeoutMs int32) error {
	if timeoutMs < 1 || timeoutMs > 600000 {
		return errors.New("HTTP timeout must be between 1 and 600000 ms")
	}
	c.h1h2Client.Timeout = time.Duration(timeoutMs) * time.Millisecond
	return nil
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

// ThroughSocks5 sends the client's requests through the local SOCKS proxy of the running service,
// or fails them: nothing that goes wrong at the port sends a request around the proxy. Not a
// connection that cannot be made, as the start holds the port even when its core no longer
// takes connections there. And not a handshake that fails: the proxy could not connect the
// request, or closed what was open at the port for a switch to another server.
//
// guard, when there is one, is asked for every connection once it is made, and before a byte is
// sent on it. A start that still runs has held the port ever since the client was set up, so the
// connection has reached that start and nothing else. Once it no longer runs the request fails
// with errOwnPortGone, also when that shows in a handshake that fails: whatever holds the port
// by then is sent nothing.
func (c *httpClient) ThroughSocks5(port int32, username string, password string, guard OwnPortGuard) {
	dialer := &net.Dialer{Timeout: defaultHTTPDialTimeout}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
		if guard != nil && !guard.SameStart() {
			if err == nil {
				_ = socksConn.Close()
			}
			return nil, errOwnPortGone
		}
		if err != nil {
			return nil, err
		}
		stop := context.AfterFunc(ctx, func() { _ = socksConn.Close() })
		err = socksConn.SetDeadline(time.Now().Add(defaultHTTPDialTimeout))
		if err == nil {
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), username, password)
		}
		if !stop() {
			err = ctx.Err()
		}
		if err == nil {
			err = socksConn.SetDeadline(time.Time{})
		}
		if err != nil {
			_ = socksConn.Close()
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if guard != nil && !guard.SameStart() {
				return nil, errOwnPortGone
			}
			return nil, err
		}
		return socksConn, nil
	}
	c.throughSocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request       http.Request
	customHeaders []string
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
	r.customHeaders = append(r.customHeaders, key)
}

// errRedirectDowngrade stops a redirect chain that would leave HTTPS, so a request that started
// encrypted, such as a subscription link or its backup, never continues in the clear.
var errRedirectDowngrade = errors.New("redirect from HTTPS to plain HTTP refused")

// credentialHeaders are dropped, with the headers set through SetHeader, when a redirect leaves
// the original origin. Go's own policy keeps some of them for subdomains and other ports.
var credentialHeaders = []string{"Authorization", "Proxy-Authorization", "Cookie", "Cookie2"}

// checkRedirect keeps Go's ten-hop limit and refuses any hop to plain HTTP once the chain used
// HTTPS. A hop to another scheme, host or port than the first request loses the credentials and
// the headers set through SetHeader, so neither the subscription credentials nor a device
// identifier reach a third party. The Referer Go adds is the previous hop's URL, whose path or
// query may hold a token, so it goes too unless the hop stays on the origin of both the first
// request and the previous one.
func (r *httpRequest) checkRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return errors.New("stopped after 10 redirects")
	}
	if req.URL.Scheme != "https" {
		for _, previous := range via {
			if previous.URL.Scheme == "https" {
				return errRedirectDowngrade
			}
		}
	}
	sameAsFirst := sameOrigin(req.URL, via[0].URL)
	if !sameAsFirst {
		for _, key := range r.customHeaders {
			req.Header.Del(key)
		}
		for _, key := range credentialHeaders {
			req.Header.Del(key)
		}
	}
	if !sameAsFirst || !sameOrigin(req.URL, via[len(via)-1].URL) {
		req.Header.Del("Referer")
	}
	return nil
}

// sameOrigin compares scheme, host name and effective port.
func sameOrigin(a, b *url.URL) bool {
	return a.Scheme == b.Scheme && strings.EqualFold(a.Hostname(), b.Hostname()) && effectivePort(a) == effectivePort(b)
}

// effectivePort is the explicit port or the scheme default, so https://h and https://h:443 match.
func effectivePort(u *url.URL) string {
	if port := u.Port(); port != "" {
		return port
	}
	switch u.Scheme {
	case "https":
		return "443"
	case "http":
		return "80"
	}
	return ""
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer deferPanicToError("http execute", func(err error) { log.Println(err) })
	// Only a client that goes through no proxy: the direct paths would go around one.
	if r.tryH3Direct && !r.throughSocks5 {
		return r.doH3Direct()
	}
	// A per-request copy shares the transport but carries this request's redirect policy, so
	// concurrent requests on one client do not overwrite each other's.
	client := r.h1h2Client
	client.CheckRedirect = r.checkRedirect
	response, err := client.Do(&r.request)
	if err != nil {
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

type requestFunc func(context.Context) (response *http.Response, err error)

type labeledRequestFunc struct {
	label   string
	request requestFunc
}

var errEmptyHTTPResponse = errors.New("empty response")

type indexedHTTPResponse struct {
	index    int
	response *http.Response
}

type cancelOnCloseBody struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (b *cancelOnCloseBody) Close() error {
	err := b.ReadCloser.Close()
	b.cancel()
	return err
}

func raceHTTPRequests(ctx context.Context, funcs []labeledRequestFunc) (*http.Response, error) {
	successCh := make(chan indexedHTTPResponse)
	doneCh := make(chan struct{}, len(funcs))
	workerCancels := make([]context.CancelFunc, len(funcs))
	var finalErr error
	var mu sync.Mutex

	addError := func(err error) {
		mu.Lock()
		finalErr = errors.Join(finalErr, err)
		mu.Unlock()
	}
	resultError := func() error {
		mu.Lock()
		defer mu.Unlock()
		if ctxErr := ctx.Err(); ctxErr != nil {
			return errors.Join(finalErr, ctxErr)
		}
		if finalErr == nil {
			return errEmptyHTTPResponse
		}
		return finalErr
	}

	for index, f := range funcs {
		workerCtx, workerCancel := context.WithCancel(context.WithoutCancel(ctx))
		workerCancels[index] = workerCancel
		go func(index int, f labeledRequestFunc, requestCtx context.Context, cancel context.CancelFunc) {
			cancelOnExit := true
			defer func() {
				if cancelOnExit {
					cancel()
				}
				doneCh <- struct{}{}
			}()
			defer deferPanicToError("http", func(err error) {
				addError(fmt.Errorf("%s: %w", f.label, err))
				log.Println(err)
			})

			rsp, err := f.request(requestCtx)
			if rsp == nil || err != nil {
				if err == nil {
					err = errEmptyHTTPResponse
				}
				addError(fmt.Errorf("%s: %w", f.label, err))
				if rsp != nil && rsp.Body != nil {
					rsp.Body.Close()
				}
				return
			}

			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp}
				addError(fmt.Errorf("%s: %s", f.label, hr.errorString()))
				return
			}

			select {
			case successCh <- indexedHTTPResponse{index: index, response: rsp}:
				// The response body owns the winning request context until Close.
				cancelOnExit = false
			case <-requestCtx.Done():
				if rsp.Body != nil {
					rsp.Body.Close()
				}
			}
		}(index, f, workerCtx, workerCancel)
	}

	completed := 0
	cancelAndWait := func(winner int) {
		for index, cancel := range workerCancels {
			if index != winner {
				cancel()
			}
		}
		for completed < len(funcs) {
			<-doneCh
			completed++
		}
	}

	for completed < len(funcs) {
		select {
		case result := <-successCh:
			if ctx.Err() != nil {
				workerCancels[result.index]()
				if result.response.Body != nil {
					result.response.Body.Close()
				}
				cancelAndWait(-1)
				return nil, resultError()
			}
			cancelAndWait(result.index)
			if result.response.Body == nil {
				workerCancels[result.index]()
			} else {
				result.response.Body = &cancelOnCloseBody{
					ReadCloser: result.response.Body,
					cancel:     workerCancels[result.index],
				}
			}
			return result.response, nil
		case <-doneCh:
			completed++
			if completed == len(funcs) {
				return nil, resultError()
			}
		case <-ctx.Done():
			cancelAndWait(-1)
			return nil, resultError()
		}
	}
	return nil, resultError()
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	funcs := []labeledRequestFunc{
		{
			label: "http(s)",
			request: func(ctx context.Context) (response *http.Response, err error) {
				request := r.request.Clone(ctx)
				echClient := &http.Client{
					Timeout:       r.h1h2Client.Timeout,
					CheckRedirect: r.checkRedirect,
					Transport: &http.Transport{
						DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
							var d net.Dialer
							c, err := d.DialContext(ctx, network, addr)
							if err != nil {
								return c, err
							}
							domain := addr
							if host, _, _ := net.SplitHostPort(addr); host != "" {
								domain = host
							}
							echTls := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
							return echTls.Client(ctx, c)
						},
						DisableKeepAlives: true,
					},
				}
				return echClient.Do(request)
			},
		},
		{
			label: "h3",
			request: func(ctx context.Context) (response *http.Response, err error) {
				request := r.request.Clone(ctx)
				h3Client := &http.Client{
					Timeout:       r.h1h2Client.Timeout,
					CheckRedirect: r.checkRedirect,
					Transport: &http3.Transport{
						TLSClientConfig: r.tls.Clone(),
						QUICConfig: &quic.Config{
							MaxIdleTimeout: time.Second,
						},
					},
				}
				return h3Client.Do(request)
			},
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	result, err := raceHTTPRequests(ctx, funcs)
	if err != nil {
		return nil, err
	}
	return &httpResponse{Response: result}, nil
}

type httpResponse struct {
	*http.Response

	contentMu    sync.Mutex
	contentRead  bool
	content      []byte
	contentError error
}

func (h *httpResponse) errorString() string {
	content, err := h.GetContentStringLimited(defaultHTTPStringLimit)
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) string {
	return h.Header.Get(key)
}

func (h *httpResponse) GetContentLimited(limit int64) ([]byte, error) {
	h.contentMu.Lock()
	defer h.contentMu.Unlock()
	if h.contentRead {
		if h.contentError != nil {
			return nil, h.contentError
		}
		if int64(len(h.content)) > limit {
			return nil, fmt.Errorf("HTTP response body exceeds %d bytes", limit)
		}
		return h.content, nil
	}
	defer h.Body.Close()
	h.contentRead = true
	h.content, h.contentError = readAllLimited(h.Body, limit)
	return h.content, h.contentError
}

func (h *httpResponse) GetContentStringLimited(limit int64) (string, error) {
	content, err := h.GetContentLimited(limit)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteToLimited(path string, limit int64) (err error) {
	defer h.Body.Close()
	dir, base := filepath.Split(path)
	if dir == "" {
		dir = "."
	}
	file, err := os.CreateTemp(dir, base+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := file.Name()
	defer func() {
		if err != nil {
			_ = os.Remove(tmpPath)
		}
	}()
	defer func() {
		if closeErr := file.Close(); err == nil {
			err = closeErr
		}
		if err == nil {
			err = os.Rename(tmpPath, path)
		}
	}()
	_, err = copyLimited(file, h.Body, limit)
	return err
}

func readAllLimited(reader io.Reader, limit int64) ([]byte, error) {
	if limit < 0 {
		return nil, fmt.Errorf("invalid HTTP response limit %d", limit)
	}
	content, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(content)) > limit {
		return nil, fmt.Errorf("HTTP response body exceeds %d bytes", limit)
	}
	return content, nil
}

func copyLimited(dst io.Writer, src io.Reader, limit int64) (int64, error) {
	if limit < 0 {
		return 0, fmt.Errorf("invalid HTTP response limit %d", limit)
	}
	written, err := io.Copy(dst, io.LimitReader(src, limit+1))
	if err != nil {
		return written, err
	}
	if written > limit {
		return written, fmt.Errorf("stream exceeds %d bytes", limit)
	}
	return written, nil
}
