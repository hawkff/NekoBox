package stun

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestAddrAttributesRejectShortValuesAndCapLongOnes(t *testing.T) {
	transID := make([]byte, 16)
	binary.BigEndian.PutUint32(transID, magicCookie)
	ipv6 := bytes.Repeat([]byte{0x20}, 16)
	for _, c := range []struct {
		name   string
		value  []byte
		xorIP  string
		rawIP  string
		absent bool
	}{
		{name: "empty", value: nil, absent: true},
		{name: "family only", value: []byte{0, attributeFamilyIPv4}, absent: true},
		{name: "header only", value: []byte{0, attributeFamilyIPv4, 0, 80}, absent: true},
		{name: "ipv4 cut short", value: []byte{0, attributeFamilyIPv4, 0, 80, 1, 2}, absent: true},
		{name: "ipv6 cut short", value: append([]byte{0, attributeFamilyIPV6, 0, 80}, make([]byte, 8)...), absent: true},
		{name: "unknown family", value: []byte{0, 9, 0, 80, 1, 2, 3, 4}, absent: true},
		{name: "ipv4", value: []byte{0, attributeFamilyIPv4, 0, 80, 0x21, 0x12, 0xa4, 0x43}, xorIP: "0.0.0.1", rawIP: "33.18.164.67"},
		{name: "ipv4 with extra bytes", value: append([]byte{0, attributeFamilyIPv4, 0, 80, 1, 2, 3, 4}, make([]byte, 40)...), xorIP: "32.16.167.70", rawIP: "1.2.3.4"},
		{name: "ipv6 with extra bytes", value: append(append([]byte{0, attributeFamilyIPV6, 0, 80}, ipv6...), make([]byte, 40)...), xorIP: "132:8462:2020:2020:2020:2020:2020:2020", rawIP: "2020:2020:2020:2020:2020:2020:2020:2020"},
	} {
		// Unpadded, as newAttribute would make a value of 1 to 3 bytes 4 bytes long.
		a := &attribute{types: attributeXorMappedAddress, value: c.value}
		xor, raw := a.xorAddr(transID), a.rawAddr()
		if c.absent {
			if xor != nil || raw != nil {
				t.Errorf("%s: got %v and %v, want none", c.name, xor, raw)
			}
			continue
		}
		if xor == nil || xor.IP() != c.xorIP {
			t.Errorf("%s: XOR address %v, want %s", c.name, xor, c.xorIP)
		}
		if raw == nil || raw.IP() != c.rawIP {
			t.Errorf("%s: raw address %v, want %s", c.name, raw, c.rawIP)
		}
	}
}

func TestPacketWithEmptyAddressAttributesHasNoAddresses(t *testing.T) {
	packetBytes := make([]byte, 20, 40)
	binary.BigEndian.PutUint16(packetBytes, typeBindingResponse)
	binary.BigEndian.PutUint16(packetBytes[2:], 12)
	binary.BigEndian.PutUint32(packetBytes[4:], magicCookie)
	for _, types := range []uint16{attributeXorMappedAddress, attributeMappedAddress, attributeOtherAddress} {
		packetBytes = binary.BigEndian.AppendUint16(packetBytes, types)
		packetBytes = binary.BigEndian.AppendUint16(packetBytes, 0)
	}
	pkt, err := newPacketFromBytes(packetBytes)
	if err != nil {
		t.Fatal(err)
	}
	// The last one too, though it ends the packet.
	if len(pkt.attributes) != 3 {
		t.Fatalf("parsed %d attributes, want 3", len(pkt.attributes))
	}
	if addr := pkt.getXorMappedAddr(); addr != nil {
		t.Errorf("XOR-MAPPED-ADDRESS: %v", addr)
	}
	if addr := pkt.getMappedAddr(); addr != nil {
		t.Errorf("MAPPED-ADDRESS: %v", addr)
	}
	if addr := pkt.getOtherAddr(); addr != nil {
		t.Errorf("OTHER-ADDRESS: %v", addr)
	}
}

func TestSameHostWithoutMappedAddress(t *testing.T) {
	host := &Host{attributeFamilyIPv4, "192.0.2.1", 80}
	if sameHost(nil, host) || sameHost(host, nil) || sameHost(nil, nil) {
		t.Fatal("a missing address matched")
	}
	if !sameHost(host, &Host{attributeFamilyIPv4, "192.0.2.1", 80}) {
		t.Fatal("equal addresses did not match")
	}
}
