package libcore

import (
	"errors"
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
