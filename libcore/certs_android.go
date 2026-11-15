//go:build android

package libcore

import (
	"sync"
	_ "unsafe" // for go:linkname
)

// systemRootsMu is the lock crypto/x509 reads systemRoots under, so TLS handshakes on other
// goroutines see the old pool or the whole new one. Needs -ldflags=-checklinkname=0.
//
//go:linkname systemRootsMu crypto/x509.systemRootsMu
var systemRootsMu sync.RWMutex
