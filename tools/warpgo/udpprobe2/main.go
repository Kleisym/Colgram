// udpprobe sends one datagram to one address and reports whether anything comes back.
//
// The question is narrow. QUIC to Cloudflare's range times out from this device, UDP 53 to 1.1.1.1
// answers in 99 ms, and DNS is UDP - so UDP as a protocol is not blocked, and neither is 1.1.1.1.
// What has not been separated is the destination address from the payload, or the port from the
// payload. One datagram at a time is enough to tell.
package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strings"
	"time"
)

// dnsQuery builds a well-formed A query. Used so "no answer" means the packet did not get through,
// not that the receiver had no idea what it was.
func dnsQuery(name string) []byte {
	msg := make([]byte, 12)
	binary.BigEndian.PutUint16(msg[0:2], 0x2b3c)
	binary.BigEndian.PutUint16(msg[2:4], 0x0100) // standard query, recursion desired
	binary.BigEndian.PutUint16(msg[4:6], 1)      // one question
	for _, part := range strings.Split(name, ".") {
		msg = append(msg, byte(len(part)))
		msg = append(msg, part...)
	}
	msg = append(msg, 0)
	msg = append(msg, 0, 1) // A
	msg = append(msg, 0, 1) // IN
	return msg
}

func main() {
	bind := os.Getenv("PROBE_BIND")
	payload := []byte("colgram-udp-probe")
	if v := os.Getenv("PROBE_PAYLOAD"); v != "" {
		payload = []byte(v)
	}
	// A real DNS query, because a bare string is not a DNS message and 1.1.1.1 is entitled to ignore
	// it. PROBE_DNS=yes sends one that is well formed, which is what separates "the resolver is
	// filtered" from "the resolver ignores this because it is not a query".
	if os.Getenv("PROBE_DNS") != "" {
		payload = dnsQuery("cloudflare.com")
	}
	// Comma-separated rather than positional: shells differ in how they pass argv, and
	// "1.1.1.1 53" arriving as one argument turns a working probe into a confusing resolve error.
	targets := os.Getenv("PROBE_TARGETS")
	if targets == "" && len(os.Args) > 1 {
		targets = strings.Join(os.Args[1:], " ")
	}
	if targets == "" {
		fmt.Println("set PROBE_TARGETS to a comma-separated list of address:port")
		os.Exit(2)
	}

	local := &net.UDPAddr{}
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			fmt.Println("PROBE_BIND is not IPv4:", bind)
			os.Exit(1)
		}
		local.IP = ip
	}

	for _, target := range strings.Split(targets, ",") {
		target = strings.TrimSpace(target)
		if target == "" {
			continue
		}
		addr, err := net.ResolveUDPAddr("udp4", target)
		if err != nil {
			fmt.Printf("%-24s  resolve: %v\n", target, err)
			continue
		}
		conn, err := net.DialUDP("udp4", local, addr)
		if err != nil {
			fmt.Printf("%-24s  dial: %v\n", target, err)
			continue
		}
		start := time.Now()
		if _, err := conn.Write(payload); err != nil {
			fmt.Printf("%-24s  write: %v\n", target, err)
			conn.Close()
			continue
		}
		conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		buf := make([]byte, 2048)
		n, err := conn.Read(buf)
		elapsed := time.Since(start)
		conn.Close()
		if err != nil {
			fmt.Printf("%-24s  sent %d bytes, no answer in %dms\n",
				target, len(payload), elapsed.Milliseconds())
			continue
		}
		head := n
		if head > 48 {
			head = 48
		}
		fmt.Printf("%-24s  ANSWERED %d bytes in %dms: %q\n",
			target, n, elapsed.Milliseconds(), string(buf[:head]))
	}
}
