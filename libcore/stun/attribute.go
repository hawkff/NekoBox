// Copyright 2016 Cong Ding
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//	http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package stun

import (
	"encoding/binary"
	"hash/crc32"
	"net"
)

type attribute struct {
	types  uint16
	length uint16
	value  []byte
}

func newAttribute(types uint16, value []byte) *attribute {
	att := new(attribute)
	att.types = types
	att.value = padding(value)
	att.length = uint16(len(att.value))
	return att
}

func newFingerprintAttribute(packet *packet) *attribute {
	crc := crc32.ChecksumIEEE(packet.bytes()) ^ fingerprint
	buf := make([]byte, 4)
	binary.BigEndian.PutUint32(buf, crc)
	return newAttribute(attributeFingerprint, buf)
}

func newSoftwareAttribute(name string) *attribute {
	return newAttribute(attributeSoftware, []byte(name))
}

func newChangeReqAttribute(changeIP bool, changePort bool) *attribute {
	value := make([]byte, 4)
	if changeIP {
		value[3] |= 0x04
	}
	if changePort {
		value[3] |= 0x02
	}
	return newAttribute(attributeChangeRequest, value)
}

// xorAddr decodes an XOR-MAPPED-ADDRESS attribute (RFC 5389, Figure 6):
//
//	 0                   1                   2                   3
//	 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//	|x x x x x x x x|    Family     |         X-Port                |
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//	|                X-Address (Variable)
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
func (v *attribute) xorAddr(transID []byte) *Host {
	family, port, address := v.addressValue()
	if address == nil {
		return nil
	}
	xorIP := make([]byte, len(address))
	for i := range address {
		xorIP[i] = address[i] ^ transID[i]
	}
	x := binary.BigEndian.Uint16(transID[:2])
	return &Host{family, net.IP(xorIP).String(), port ^ x}
}

// rawAddr decodes a MAPPED-ADDRESS attribute (RFC 5389, Figure 5):
//
//	 0                   1                   2                   3
//	 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//	|0 0 0 0 0 0 0 0|    Family     |           Port                |
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//	|                                                               |
//	|                 Address (32 bits or 128 bits)                 |
//	|                                                               |
//	+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
func (v *attribute) rawAddr() *Host {
	family, port, address := v.addressValue()
	if address == nil {
		return nil
	}
	return &Host{family, net.IP(address).String(), port}
}

// addressValue splits an address attribute's value into family, port and an address of the family's length.
// A value of an unknown family, or too short for its family, gives no address; bytes past it are ignored.
func (v *attribute) addressValue() (family, port uint16, address []byte) {
	if len(v.value) < 4 {
		return 0, 0, nil
	}
	family = uint16(v.value[1])
	size := 0
	switch family {
	case attributeFamilyIPv4:
		size = 4
	case attributeFamilyIPV6:
		size = 16
	}
	if size == 0 || len(v.value) < 4+size {
		return 0, 0, nil
	}
	return family, binary.BigEndian.Uint16(v.value[2:4]), v.value[4 : 4+size]
}
