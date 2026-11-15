package libcore

import (
	"encoding/binary"
	"errors"
	"net"
	"testing"

	"libcore/stun"
)

func TestDescribeBehaviorNamesWhatTheTestsFound(t *testing.T) {
	refused := errors.New("read: connection refused")
	for _, c := range []struct {
		name     string
		nat      stun.NATType
		behavior *stun.NATBehavior
		err      error
		want     StunResult
	}{
		{
			"finished", stun.NATPortRestricted, &stun.NATBehavior{MappingType: stun.BehaviorTypeEndpoint, FilteringType: stun.BehaviorTypeAddrAndPort}, nil,
			StunResult{Mapping: BehaviorEndpointIndependent, Filtering: BehaviorAddressAndPortDependent},
		},
		{
			"stopped after the mapping", stun.NATSymmetric, &stun.NATBehavior{MappingType: stun.BehaviorTypeAddr}, refused,
			StunResult{Mapping: BehaviorAddressDependent, Problem: StunFailed, Error: refused.Error()},
		},
		{"no answer", stun.NATBlocked, nil, stun.ErrNoAnswer, StunResult{Problem: StunUnreachable, Error: stun.ErrNoAnswer.Error()}},
		{"no alternate address", stun.NATError, nil, stun.ErrNoOtherAddress, StunResult{Problem: StunUnsupported, Error: stun.ErrNoOtherAddress.Error()}},
		{"public address", stun.NATNone, nil, stun.ErrNotBehindNAT, StunResult{NoNat: true}},
		{"public address behind a firewall", stun.SymmetricUDPFirewall, nil, stun.ErrNotBehindNAT, StunResult{NoNat: true}},
	} {
		var got StunResult
		describeBehavior(&got, c.nat, nil, c.behavior, c.err)
		if got != c.want {
			t.Errorf("%s: got %+v, want %+v", c.name, got, c.want)
		}
	}
}

// A reply with an empty XOR-MAPPED-ADDRESS used to panic, which kills the app across JNI.
func TestStunTestSurvivesAnEmptyMappedAddress(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		request := make([]byte, 1500)
		for {
			n, addr, err := server.ReadFrom(request)
			if err != nil {
				return
			}
			if n < 20 {
				continue
			}
			reply := binary.BigEndian.AppendUint16(nil, 0x0101) // binding response
			reply = binary.BigEndian.AppendUint16(reply, 4)
			reply = append(reply, request[4:20]...)              // magic cookie and transaction ID
			reply = binary.BigEndian.AppendUint16(reply, 0x0020) // XOR-MAPPED-ADDRESS, empty
			reply = binary.BigEndian.AppendUint16(reply, 0)
			_, _ = server.WriteTo(reply, addr)
		}
	}()
	result := StunTest(server.LocalAddr().String())
	if result.Problem != StunUnsupported || result.PublicAddress != "" {
		t.Fatalf("got %+v", result)
	}
}
