package libcore

import (
	"errors"
	"fmt"
	"strings"

	"libcore/stun"
)

// How a NAT maps or filters UDP, per RFC 5780.
const (
	BehaviorEndpointIndependent     = "endpoint_independent"
	BehaviorAddressDependent        = "address_dependent"
	BehaviorAddressAndPortDependent = "address_and_port_dependent"
)

// Why the behavior test stopped.
const (
	// StunUnreachable: no answer to a plain request; UDP is blocked or the server is down.
	StunUnreachable = "unreachable"
	// StunUnsupported: the server names no alternate address, which the test needs.
	StunUnsupported = "unsupported"
	// StunFailed: anything else; Error has the reason.
	StunFailed = "failed"
)

// StunResult is the NAT test against one STUN server: the RFC 3489 test and the RFC 5780 behavior
// test as text, and the behavior test's findings as fields.
type StunResult struct {
	Text    string
	Success bool
	// A Behavior constant, empty when the test did not get that far.
	Mapping   string
	Filtering string
	// The public address the server saw, empty without an answer.
	PublicAddress string
	// The server saw the device's own address: no NAT in the way.
	NoNat bool
	// A Stun constant when the behavior test stopped early, and its reason.
	Problem string
	Error   string
}

func StunTest(server string) *StunResult {
	//note: this library doesn't support stun1.l.google.com:19302
	ret := &StunResult{}
	var text string

	// Old NAT Type Test
	client := stun.NewClient()
	client.SetServerAddr(server)
	nat, host, err, fakeFullCone := client.Discover()
	if err != nil {
		text += fmt.Sprintln("Discover Error:", err.Error())
	}

	if fakeFullCone {
		text += fmt.Sprintln("Fake fullcone (no endpoint IP change) detected!!")
	}

	if host != nil {
		text += fmt.Sprintln("NAT Type:", nat)
		text += fmt.Sprintln("External IP Family:", host.Family())
		text += fmt.Sprintln("External IP:", host.IP())
		text += fmt.Sprintln("External Port:", host.Port())
	}

	// New NAT Test

	natBehavior, err := client.BehaviorTest()
	if err != nil {
		text += fmt.Sprintln("BehaviorTest Error:", err.Error())
	}

	if natBehavior != nil {
		text += fmt.Sprintln("Mapping Behavior:", natBehavior.MappingType)
		text += fmt.Sprintln("Filtering Behavior:", natBehavior.FilteringType)
		text += fmt.Sprintln("Normal NAT Type:", natBehavior.NormalType())
	}

	describeBehavior(ret, nat, host, natBehavior, err)
	ret.Success = true
	ret.Text = strings.TrimRight(text, "\n")
	return ret
}

// describeBehavior fills the result's fields from both tests: the address from the first one, the
// rest from the behavior test.
func describeBehavior(ret *StunResult, nat stun.NATType, host *stun.Host, behavior *stun.NATBehavior, err error) {
	if host != nil {
		ret.PublicAddress = host.IP()
	}
	if behavior != nil {
		ret.Mapping = behaviorName(behavior.MappingType)
		ret.Filtering = behaviorName(behavior.FilteringType)
	}
	ret.NoNat = errors.Is(err, stun.ErrNotBehindNAT) || nat == stun.NATNone || nat == stun.SymmetricUDPFirewall
	switch {
	case err == nil || ret.NoNat:
	case errors.Is(err, stun.ErrNoAnswer):
		ret.Problem = StunUnreachable
	case errors.Is(err, stun.ErrNoOtherAddress):
		ret.Problem = StunUnsupported
	default:
		ret.Problem = StunFailed
	}
	if ret.Problem != "" {
		ret.Error = err.Error()
	}
}

func behaviorName(behavior stun.BehaviorType) string {
	switch behavior {
	case stun.BehaviorTypeEndpoint:
		return BehaviorEndpointIndependent
	case stun.BehaviorTypeAddr:
		return BehaviorAddressDependent
	case stun.BehaviorTypeAddrAndPort:
		return BehaviorAddressAndPortDependent
	}
	return ""
}
