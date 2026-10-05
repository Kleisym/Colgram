// quicprobe sends a minimal QUIC Initial and reports whether the edge answers with a Retry.
//
// It exists to separate two questions that keep getting confused: is the path to the MASQUE edge
// usable from this machine, and does the full client work. No TLS, no registration, no capsules -
// just one datagram and whatever comes back.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"strconv"
	"time"
)

func main() {
	host := "162.159.198.2"
	port := "443"
	if len(os.Args) > 1 {
		host = os.Args[1]
	}
	if len(os.Args) > 2 {
		port = os.Args[2]
	}
	size := 1200
	if len(os.Args) > 3 {
		size, _ = strconv.Atoi(os.Args[3])
	}

	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, port))
	if err != nil {
		fmt.Println("resolve:", err)
	os.Exit(1)
	}
	local := &net.UDPAddr{}
	if bind := os.Getenv("PROBE_BIND"); bind != "" {
		local.IP = net.ParseIP(bind).To4()
	}
	conn, err := net.DialUDP("udp4", local, addr)
	if err != nil {
		fmt.Println("dial:", err)
		os.Exit(1)
	}
	defer conn.Close()

	pkt := buildInitial(size)
	start := time.Now()
	if _, err := conn.Write(pkt); err != nil {
		fmt.Println("write:", err)
		os.Exit(1)
	}
	fmt.Printf("sent %d bytes to %s:%s\n", len(pkt), host, port)

	conn.SetReadDeadline(time.Now().Add(6 * time.Second))
	buf := make([]byte, 2048)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			fmt.Println("no answer within 6s:", err)
			os.Exit(2)
		}
		elapsed := time.Since(start)
		if n < 1 {
			continue
		}
		first := buf[0]
		kind := "unknown"
		switch {
		case first&0x80 != 0:
			t := (first & 0x30) >> 4
			switch t {
			case 0:
				kind = "Initial"
			case 1:
				kind = "0-RTT"
			case 2:
				kind = "Handshake"
			case 3:
				kind = "Retry"
			}
		default:
			kind = "Short"
		}
		fmt.Printf("answer: %d bytes, type=%s, first=0x%02x, rtt=%dms\n",
			n, kind, first, elapsed.Milliseconds())
		return
	}
}

// buildInitial assembles a long-header Initial large enough to be valid. The payload is random:
// only the first byte is inspected on the way back, and random bytes are enough to carry the packet.
func buildInitial(size int) []byte {
	pkt := make([]byte, size)
	if _, err := rand.Read(pkt); err != nil {
		panic(err)
	}
	// Long header, Initial, version 1.
	pkt[0] = 0xc3
	copy(pkt[1:5], []byte{0x00, 0x00, 0x00, 0x01})
	// Destination connection ID length and value.
	pkt[5] = 8
	copy(pkt[6:14], []byte{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88})
	// Packet number length 4 bits (0) in the high nibble, reserved bits zero.
	pkt[14] = 0x00
	// The length field covers everything after it: packet number, token length, token, and the
	// CRYPTO frame. Getting this wrong makes the packet unparseable, and the edge simply does not
	// answer - which looks exactly like being filtered.
	payloadLen := size - 17 - 4 - 4 - 1
	pkt[15] = byte(payloadLen >> 8)
	pkt[16] = byte(payloadLen)
	// Token length 0, then the packet number.
	pkt[17] = 0x00
	// A CRYPTO frame header so the frame parser has something to walk.
	off := 18 + 4
	if off+8 <= size {
		pkt[off] = 0x06
		pkt[off+1] = byte((size - off - 8) >> 8)
		pkt[off+2] = byte(size - off - 8)
	}
	if size >= 16 {
		fmt.Fprintf(os.Stderr, "initial head: %s\n", hex.EncodeToString(pkt[:20]))
	}
	return pkt
}
