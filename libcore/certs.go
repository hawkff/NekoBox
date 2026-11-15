package libcore

import (
	"context"
	"crypto/x509"
	"log"
	_ "unsafe" // for go:linkname

	"github.com/sagernet/sing-box/common/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

// useAndroidTrustStore makes Go's system roots the ones Android trusts: its own CAs and those
// the user installed in Settings (KeyStore "AndroidCAStore"), read through the core's system
// certificate store. Without them, the store holds Go's own system roots, which stay.
func useAndroidTrustStore() {
	store, err := certificate.NewStore(context.Background(), logger.NOP(), option.CertificateOptions{})
	if err != nil {
		log.Println("system certificates:", err)
		return
	}
	setSystemRoots(store.Pool())
}

func setSystemRoots(roots *x509.CertPool) {
	// Go loads its system roots once, on first use; done first, that cannot replace these later.
	x509.SystemCertPool()
	systemRoots = roots
}
