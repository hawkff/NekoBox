package libcore

import (
	"strings"
	"testing"
)

func TestHysteria1IsNotRegistered(t *testing.T) {
	instance, err := NewSingBoxInstance(`{
		"outbounds": [{"type":"hysteria","server":"192.0.2.1","server_port":443,
		"up_mbps":10,"down_mbps":50,"tls":{"enabled":true,"server_name":"example.com"}}]
	}`, nil)
	if instance != nil {
		instance.Close()
	}
	if err == nil || !strings.Contains(err.Error(), "unknown outbound type: hysteria") {
		t.Fatalf("expected an unregistered Hysteria 1 outbound, got %v", err)
	}
}
