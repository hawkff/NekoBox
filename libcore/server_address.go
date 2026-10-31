package libcore

import (
	"context"
	"net/netip"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
)

// ResolveServer looks up name with the resolver the outbound or endpoint tagged tag dials through
// (its domain_resolver, else the route's default) and the same query options, so the answer is
// the one that connection uses, mostly from the core's DNS cache. An address comes back as it is.
// Addresses are joined by newlines; a nil instance means the running core.
func ResolveServer(i *BoxInstance, tag string, name string) (addresses string, err error) {
	defer deferPanicToError("ResolveServer", func(err_ error) { err = err_ })
	if address, parseErr := netip.ParseAddr(name); parseErr == nil {
		return address.Unmap().String(), nil
	}
	if i == nil {
		mainAccess.Lock()
		i = mainInstance
		mainAccess.Unlock()
	}
	if i == nil {
		return "", E.New("no core")
	}
	dialOptions, found := i.dialOptions(tag)
	if !found {
		return "", E.New("no outbound or endpoint ", tag)
	}
	var resolver *option.DomainResolveOptions
	if dialOptions != nil {
		resolver = dialOptions.TakeDialerOptions().DomainResolver
	}
	queryOptions, err := dialer.NewDNSQueryOptions(i.ctx, resolver, false)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(i.ctx, C.DNSTimeout)
	defer cancel()
	answers, err := service.FromContext[adapter.DNSRouter](i.ctx).Lookup(ctx, name, queryOptions)
	if err != nil {
		return "", err
	}
	lines := make([]string, len(answers))
	for index, address := range answers {
		lines[index] = address.Unmap().String()
	}
	return strings.Join(lines, "\n"), nil
}

// dialOptions finds the outbound or endpoint tagged tag; its dial options are nil when it has none,
// as a group has not, and the route's default resolver then applies.
func (i *BoxInstance) dialOptions(tag string) (option.DialerOptionsWrapper, bool) {
	for _, outbound := range i.outbounds {
		if outbound.Tag == tag {
			wrapper, _ := outbound.Options.(option.DialerOptionsWrapper)
			return wrapper, true
		}
	}
	for _, endpoint := range i.endpoints {
		if endpoint.Tag == tag {
			wrapper, _ := endpoint.Options.(option.DialerOptionsWrapper)
			return wrapper, true
		}
	}
	return nil, false
}
