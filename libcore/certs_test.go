package libcore

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

// certs_android.go links to crypto/x509's unexported root lock. A renamed or retyped lock still links (the
// declaration here then defines a lock of its own), so the toolchain's x509 source is checked instead.
func TestX509StillReadsSystemRootsUnderSystemRootsMu(t *testing.T) {
	root, err := exec.Command("go", "env", "GOROOT").Output()
	if err != nil {
		t.Fatal(err)
	}
	source, err := os.ReadFile(filepath.Join(strings.TrimSpace(string(root)), "src", "crypto", "x509", "root.go"))
	if err != nil {
		t.Fatal(err)
	}
	for _, pattern := range []string{`(?m)^\s*systemRootsMu\s+sync\.RWMutex\s*$`, `systemRootsMu\.RLock\(\)`, `(?m)^\s*systemRoots\s+\*CertPool\s*$`} {
		if !regexp.MustCompile(pattern).Match(source) {
			t.Errorf("crypto/x509/root.go no longer matches %s: recheck certs_android.go", pattern)
		}
	}
}

func TestSetSystemRootsReplacesGoRoots(t *testing.T) {
	saved, err := x509.SystemCertPool()
	if err != nil {
		t.Skip("no system roots on this host:", err)
	}
	t.Cleanup(func() { setSystemRoots(saved) })

	root := testRoot(t, "test root")
	roots := x509.NewCertPool()
	roots.AddCert(root)

	setSystemRoots(roots)
	got, err := x509.SystemCertPool()
	if err != nil {
		t.Fatal(err)
	}
	if !got.Equal(roots) {
		t.Fatal("system roots were not replaced")
	}
	if _, err := root.Verify(x509.VerifyOptions{}); err != nil {
		t.Fatal("a certificate of the new roots does not verify:", err)
	}
}

func testRoot(t *testing.T, name string) *x509.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: name},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	root, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return root
}

func verifies(root *x509.Certificate, roots *x509.CertPool) bool {
	_, err := root.Verify(x509.VerifyOptions{Roots: roots})
	return err == nil
}

// A DER sequence that does not parse as a certificate, as one with a negative serial number does not.
var unparseableCertificate = []byte{0x30, 0x03, 0x02, 0x01, 0x00}

func TestTrustedRootsSkipsAnUnparseableCertificate(t *testing.T) {
	first, second := testRoot(t, "first"), testRoot(t, "second")
	der := append(append(append([]byte{}, first.Raw...), unparseableCertificate...), second.Raw...)
	roots := trustedRoots(der, false)
	if roots == nil || !verifies(first, roots) || !verifies(second, roots) {
		t.Fatal("the certificates around an unparseable one are not trusted")
	}
}

func TestTrustedRootsAddsCertificatesToTheMozillaList(t *testing.T) {
	mozilla := trustedRoots(nil, true)
	if mozilla == nil {
		t.Fatal("no Mozilla list")
	}
	if mozilla.Equal(x509.NewCertPool()) {
		t.Fatal("the Mozilla list is empty")
	}
	root := testRoot(t, "user root")
	roots := trustedRoots(root.Raw, true)
	if !verifies(root, roots) {
		t.Fatal("the certificate is not trusted")
	}
	mozilla.AddCert(root)
	if !roots.Equal(mozilla) {
		t.Fatal("the Mozilla list is not the base")
	}
}

// Android's KeyStore read failed or held nothing usable: Go's own roots stay.
func TestSetTrustedRootsKeepsGoRootsWithoutCertificates(t *testing.T) {
	before, err := x509.SystemCertPool()
	if err != nil {
		t.Skip("no system roots on this host:", err)
	}
	t.Cleanup(func() { setSystemRoots(before) })
	for _, der := range [][]byte{nil, unparseableCertificate, {0xff}} {
		SetTrustedRoots(der, false)
		after, err := x509.SystemCertPool()
		if err != nil {
			t.Fatal(err)
		}
		if !after.Equal(before) {
			t.Fatalf("Go's system roots changed for % x", der)
		}
	}
}

func TestRequestsWaitForAnnouncedRoots(t *testing.T) {
	// Nothing announced: no wait.
	start := time.Now()
	awaitTrustedRoots()
	if time.Since(start) > time.Second {
		t.Fatal("waited without an announcement")
	}

	ExpectTrustedRoots()
	waited := make(chan struct{})
	go func() {
		awaitTrustedRoots()
		close(waited)
	}()
	select {
	case <-waited:
		t.Fatal("did not wait for the roots")
	case <-time.After(100 * time.Millisecond):
	}
	SetTrustedRoots(nil, false)
	select {
	case <-waited:
	case <-time.After(time.Second):
		t.Fatal("still waiting after the roots were set")
	}

	// An announcement nobody answers ends at the bound.
	saved := trustedRootsWait
	trustedRootsWait = 50 * time.Millisecond
	t.Cleanup(func() { trustedRootsWait = saved; trustedRootsReady.Store(nil) })
	ExpectTrustedRoots()
	start = time.Now()
	awaitTrustedRoots()
	if elapsed := time.Since(start); elapsed < 50*time.Millisecond || elapsed > time.Second {
		t.Fatalf("waited %v", elapsed)
	}
}
