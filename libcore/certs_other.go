//go:build !android

package libcore

import "sync"

// systemRootsMu stands in for crypto/x509's lock, which only builds with -checklinkname=0, as the
// Android library does; elsewhere setSystemRoots runs in tests only.
var systemRootsMu sync.RWMutex
