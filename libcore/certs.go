package libcore

import (
	"bytes"
	"context"
	"crypto/x509"
	"log"
	"sync/atomic"
	"time"
	_ "unsafe" // for go:linkname

	"github.com/sagernet/sing-box/common/certificate"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"golang.org/x/crypto/cryptobyte"
	"golang.org/x/crypto/cryptobyte/asn1"
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

// trustedRootsReady is closed by SetTrustedRoots once ExpectTrustedRoots announced it; nil when nothing is awaited.
var trustedRootsReady atomic.Pointer[chan struct{}]

// trustedRootsWait bounds how long a request waits for the roots.
var trustedRootsWait = 5 * time.Second

// ExpectTrustedRoots makes the app's own requests wait, a few seconds at most, for SetTrustedRoots, which the
// app calls off its main thread at start. Until then Go's default roots are Android's built-in ones only.
func ExpectTrustedRoots() {
	ready := make(chan struct{})
	trustedRootsReady.Store(&ready)
}

func awaitTrustedRoots() {
	if ready := trustedRootsReady.Load(); ready != nil {
		select {
		case <-*ready:
		case <-time.After(trustedRootsWait):
		}
	}
}

// SetTrustedRoots makes Go's system roots, which the app's own requests use, the DER certificates in
// der, one after another, on top of the core's copy of Mozilla's list when mozilla is set. With
// nothing to trust, Go keeps its default roots.
func SetTrustedRoots(der []byte, mozilla bool) {
	// gomobile lends the Java array only for this call, and parsed certificates point into their input.
	der = bytes.Clone(der)
	if roots := trustedRoots(der, mozilla); roots != nil {
		setSystemRoots(roots)
	}
	if ready := trustedRootsReady.Swap(nil); ready != nil {
		close(*ready)
	}
}

// trustedRoots returns nil when no certificate is read and no Mozilla list is asked for, or that
// list fails to load. A certificate that does not parse is skipped.
func trustedRoots(der []byte, mozilla bool) *x509.CertPool {
	roots := x509.NewCertPool()
	if mozilla {
		store, err := certificate.NewStore(context.Background(), logger.NOP(), option.CertificateOptions{Store: C.CertificateStoreMozilla})
		if err != nil {
			log.Printf("trusted roots: Mozilla list: %v; Go's default roots stay", err)
			return nil
		}
		// A store of its own, so its pool can take more.
		roots = store.Pool()
	}
	added := 0
	input := cryptobyte.String(der)
	for !input.Empty() {
		var element cryptobyte.String
		if !input.ReadASN1Element(&element, asn1.SEQUENCE) {
			log.Println("trusted roots: unreadable certificate data, the rest skipped")
			break
		}
		root, err := x509.ParseCertificate(element)
		if err != nil {
			log.Println("trusted roots: certificate skipped:", err)
			continue
		}
		roots.AddCert(root)
		added++
	}
	log.Printf("trusted roots: %d from Android, Mozilla list: %v", added, mozilla)
	if added == 0 && !mozilla {
		log.Println("trusted roots: none read; Go's default roots stay")
		return nil
	}
	return roots
}

func setSystemRoots(roots *x509.CertPool) {
	// Go loads its system roots once, on first use; done first, that cannot replace these later.
	x509.SystemCertPool()
	systemRootsMu.Lock()
	systemRoots = roots
	systemRootsMu.Unlock()
}
