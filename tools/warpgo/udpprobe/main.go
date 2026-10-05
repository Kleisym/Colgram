// udpprobe asks whether UDP works at all from this machine, and whether a specific destination
// answers. A DNS query is the cleanest test: small, well-known ports, a real expected answer.
package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strconv"
	"time"
)

func dnsQuery(server, name string) []byte {
	msg := make([]byte, 12)
	binary.BigEndian.PutUint16(msg[0:2], 0x1234)
	binary.BigEndian.PutUint16(msg[2:4], 0x0100) // recursion desired
	binary.BigEndian.PutUint16(msg[4:6], 1)      // one question
	body := []byte{}
	for _, part := range splitDots(name) {
		body = append(body, byte(len(part)))
		body = append(body, part...)
	}
	body = append(body, 0)
	body = append(body, 0, 1) // A
	body = append(body, 0, 1) // IN
	return append(msg, body...)
}

func splitDots(s string) []string {
	var out []string
	cur := ""
	for _, r := range s {
		if r == '.' {
			out = append(out, cur)
			cur = ""
			continue
		}
		cur += string(r)
	}
	return append(out, cur)
}

func main() {
	server := "1.1.1.1"
	name := "cloudflare.com"
	if len(os.Args) > 1 {
		server = os.Args[1]
	}
	if len(os.Args) > 2 {
		name = os.Args[2]
	}

	local := &net.UDPAddr{}
	if bind := os.Getenv("PROBE_BIND"); bind != "" {
		local.IP = net.ParseIP(bind).To4()
	}
	conn, err := net.Dial("udp4", net.JoinHostPort(server, "53"))
	if err != nil {
		fmt.Println("dial:", err)
		os.Exit(1)
	}
	if local.IP != nil {
		fmt.Println("note: PROBE_BIND is not applied by Dial; using", conn.LocalAddr())
	}
	defer conn.Close()

	q := dnsQuery(server, name)
	// Padding isolates the variable that matters here: a QUIC Initial is 1200 bytes, a DNS query
	// is not. If a padded query is answered and an unpadded one is, the path is size-sensitive.
	if pad := os.Getenv("PROBE_PAD"); pad != "" {
		n, _ := strconv.Atoi(pad)
		for len(q) < n {
			q = append(q, 0)
		}
	}
	start := time.Now()
	if _, err := conn.Write(q); err != nil {
		fmt.Println("write:", err)
		os.Exit(1)
	}
	fmt.Printf("dns query sent to %s:53, %d bytes\n", server, len(q))

	conn.SetReadDeadline(time.Now().Add(6 * time.Second))
	buf := make([]byte, 2048)
	n, err := conn.Read(buf)
	if err != nil {
		fmt.Println("no answer within 6s:", err)
		os.Exit(2)
	}
	fmt.Printf("answer: %d bytes, rtt=%dms, answers=%d\n",
		n, time.Since(start).Milliseconds(), binary.BigEndian.Uint16(buf[6:8]))
}
