package device

import (
	"encoding/binary"
	"errors"
)

// ClientReserved carries Cloudflare WARP's per-registration three-byte WireGuard client ID.
type ClientReserved [3]byte

// EncodeClientReserved parses the six-hex-digit private UAPI client ID.
func EncodeClientReserved(value string) (ClientReserved, error) {
	var out ClientReserved
	if len(value) != 6 {
		return out, errors.New("reserved must contain exactly six hexadecimal characters")
	}
	for i := 0; i < 3; i++ {
		var b byte
		for j := 0; j < 2; j++ {
			c := value[i*2+j]
			b <<= 4
			switch {
			case c >= '0' && c <= '9':
				b |= c - '0'
			case c >= 'a' && c <= 'f':
				b |= c - 'a' + 10
			case c >= 'A' && c <= 'F':
				b |= c - 'A' + 10
			default:
				return out, errors.New("reserved contains a non-hexadecimal character")
			}
		}
		out[i] = b
	}
	return out, nil
}

func composeMessageType(messageType uint32, reserved ClientReserved) uint32 {
	return messageType | uint32(reserved[0])<<8 | uint32(reserved[1])<<16 | uint32(reserved[2])<<24
}

func normalizeMessageType(packet []byte) uint32 {
	if len(packet) < 4 {
		return 0
	}
	return uint32(packet[0])
}

func writeMessageType(packet []byte, messageType uint32, reserved ClientReserved) {
	binary.LittleEndian.PutUint32(packet, composeMessageType(messageType, reserved))
}

func (device *Device) messageType(messageType uint32) uint32 {
	device.net.RLock()
	reserved := device.net.clientReserved
	device.net.RUnlock()
	return composeMessageType(messageType, reserved)
}

func (device *Device) writeMessageType(packet []byte, messageType uint32) {
	writeMessageType(packet, messageType, device.clientReserved())
}

func (device *Device) clientReserved() ClientReserved {
	device.net.RLock()
	defer device.net.RUnlock()
	return device.net.clientReserved
}
