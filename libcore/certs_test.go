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

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "test root"},
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

// Off Android the store finds no Android certificates, as on a device that returns none: Go's
// own roots stay.
func TestUseAndroidTrustStoreKeepsGoRootsWithoutAndroidCertificates(t *testing.T) {
	before, err := x509.SystemCertPool()
	if err != nil {
		t.Skip("no system roots on this host:", err)
	}
	t.Cleanup(func() { setSystemRoots(before) })
	useAndroidTrustStore()
	after, err := x509.SystemCertPool()
	if err != nil {
		t.Fatal(err)
	}
	if !after.Equal(before) {
		t.Fatal("Go's system roots changed")
	}
}
