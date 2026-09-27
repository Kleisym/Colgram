package device

import "testing"

func TestCloudflareReservedMessageType(t *testing.T) {
	reserved, err := EncodeClientReserved("93af18")
	if err != nil {
		t.Fatal(err)
	}
	if got, want := composeMessageType(MessageInitiationType, reserved), uint32(0x18af9301); got != want {
		t.Fatalf("init type = %08x, want %08x", got, want)
	}
	d := new(Device)
	d.net.clientReserved = reserved
	if got, want := d.messageType(MessageResponseType), uint32(0x18af9302); got != want {
		t.Fatalf("response type = %08x, want %08x", got, want)
	}
	if got, want := d.messageType(MessageCookieReplyType), uint32(0x18af9303); got != want {
		t.Fatalf("cookie reply type = %08x, want %08x", got, want)
	}
	wire := []byte{0x04, 0x93, 0xaf, 0x18}
	if got := normalizeMessageType(wire); got != MessageTransportType {
		t.Fatalf("received type = %d", got)
	}
	writeMessageType(wire, MessageTransportType, reserved)
	if got, want := binaryType(wire), uint32(0x18af9304); got != want {
		t.Fatalf("transport type = %08x, want %08x", got, want)
	}
}

func TestCloudflareReservedHexValidation(t *testing.T) {
	for _, value := range []string{"", "00000000", "00000g", "12 3456"} {
		if _, err := EncodeClientReserved(value); err == nil {
			t.Fatalf("accepted invalid reserved %q", value)
		}
	}
	if got, err := EncodeClientReserved("ABCDEF"); err != nil || got != (ClientReserved{0xab, 0xcd, 0xef}) {
		t.Fatalf("uppercase parse: %x %v", got, err)
	}
}

func binaryType(packet []byte) uint32 {
	return uint32(packet[0]) | uint32(packet[1])<<8 | uint32(packet[2])<<16 | uint32(packet[3])<<24
}

func TestUAPIReservedClientIdentifier(t *testing.T) {
	d := new(Device)
	if err := d.handleDeviceLine("reserved", "93af18"); err != nil {
		t.Fatal(err)
	}
	if got, want := d.clientReserved(), (ClientReserved{0x93, 0xaf, 0x18}); got != want {
		t.Fatalf("UAPI reserved = %x, want %x", got, want)
	}
	if err := d.handleDeviceLine("reserved", "93af1g"); err == nil {
		t.Fatal("invalid reserved ID accepted")
	}
	if got, want := d.clientReserved(), (ClientReserved{0x93, 0xaf, 0x18}); got != want {
		t.Fatalf("invalid UAPI value changed ID to %x", got)
	}
}
