package main

import (
	"encoding/binary"
	"net"
	"testing"
)

func receiveFixture() *tunnelConn {
	return &tunnelConn{tun: &tunnel{}, srcIP: net.IPv4(10, 0, 0, 1), dstIP: net.IPv4(1, 1, 1, 1),
		srcPort: 31999, dstPort: 443, synned: true, seq: 700, ack: 1000}
}
func receivePacket(seq uint32, flags uint16, data []byte) []byte {
	packet := make([]byte, 40+len(data))
	packet[0] = 0x45
	packet[9] = 6
	binary.BigEndian.PutUint16(packet[2:4], uint16(len(packet)))
	binary.BigEndian.PutUint32(packet[24:28], seq)
	binary.BigEndian.PutUint16(packet[32:34], (5<<12)|flags)
	copy(packet[40:], data)
	return packet
}
func TestTCPReceiveDoesNotDeliverOrAcknowledgeAGap(t *testing.T) {
	c := receiveFixture()
	c.feed(receivePacket(1010, 0x18, []byte("tail")))
	if c.ack != 1000 || len(c.inbuf) != 0 {
		t.Fatalf("out-of-order TLS ciphertext escaped: ack=%d bytes=%q", c.ack, c.inbuf)
	}
	c.feed(receivePacket(1000, 0x18, []byte("0123456789")))
	if c.ack != 1014 || string(c.inbuf) != "0123456789tail" {
		t.Fatalf("gap was not reassembled: ack=%d bytes=%q", c.ack, c.inbuf)
	}
	c.feed(receivePacket(1010, 0x18, []byte("tail")))
	if c.ack != 1014 || string(c.inbuf) != "0123456789tail" {
		t.Fatal("duplicate TLS bytes delivered")
	}
}
func TestTCPReceivePureAckCannotSkipMissingBytes(t *testing.T) {
	c := receiveFixture()
	c.feed(receivePacket(5000, 0x10, nil))
	if c.ack != 1000 {
		t.Fatalf("a bare ACK skipped unread bytes: %d", c.ack)
	}
}
func TestTCPReceiveFinWaitsForTheGap(t *testing.T) {
	c := receiveFixture()
	c.feed(receivePacket(1004, 0x11, nil))
	if c.eof || c.ack != 1000 {
		t.Fatal("out-of-order FIN terminated TLS early")
	}
	c.feed(receivePacket(1000, 0x18, []byte("data")))
	if !c.eof || c.ack != 1005 || string(c.inbuf) != "data" {
		t.Fatal("contiguous FIN was not consumed")
	}
}
func TestTCPReceiveOverlapsAndSequenceWrap(t *testing.T) {
	c := receiveFixture()
	c.feed(receivePacket(1000, 0x18, []byte("abcdef")))
	c.feed(receivePacket(1003, 0x18, []byte("defghi")))
	if string(c.inbuf) != "abcdefghi" || c.ack != 1009 {
		t.Fatalf("overlap duplicated bytes: %q", c.inbuf)
	}
	c = receiveFixture()
	c.ack = ^uint32(0) - 2
	c.feed(receivePacket(1, 0x18, []byte("ef")))
	c.feed(receivePacket(^uint32(0)-2, 0x18, []byte("abcd")))
	if string(c.inbuf) != "abcdef" || c.ack != 3 {
		t.Fatalf("sequence wrap failed: ack=%d bytes=%q", c.ack, c.inbuf)
	}
}
