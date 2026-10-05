// udp6 asks whether an IPv6 path to the edge answers, from one specific source address.
//
// Why IPv6 gets its own probe. Every measurement in this project so far was udp4, and the
// conclusion drawn from them was "the edge is filtered from this device". That is only established
// for IPv4. The guest has a global IPv6 address and a default route, so the filter may well be
// keyed on the address family - and if it is, the whole in-process tunnel opens over v6 and no relay
// is needed at all.
//
// The payload is a real DNS query, not a bare string: a resolver is entitled to ignore something
// that is not a DNS message, and a timeout would then mean nothing.
package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strings"
	"time"
)

// dnsQuery builds a well-formed A question for name, so "no answer" means the packet did not get
// through rather than that the receiver had no idea what it was.
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
	bind := os.Getenv("PROBE6_BIND")
	targets := os.Getenv("PROBE6_TARGETS")
	if targets == "" {
		// The edge's own v6 from the registration, and the Cloudflare resolvers as controls: if the
		// resolver answers over v6 then v6 works at all and a silent edge is a fact about the edge.
		targets = "[2606:4700:d0::a29f:c002]:443,[2606:4700:102::4]:443," +
			"[2606:4700:4700::1111]:443,[2606:4700:4700::1001]:443," +
			"[2606:4700:102::3]:443"
	}
	payload := dnsQuery("cloudflare.com")

	local := &net.UDPAddr{}
	if bind != "" {
		ip := net.ParseIP(bind)
		if ip == nil {
			fmt.Println("PROBE6_BIND is not an address:", bind)
			os.Exit(1)
		}
		local.IP = ip
	}

	for _, target := range strings.Split(targets, ",") {
		target = strings.TrimSpace(target)
		if target == "" {
			continue
		}
		probe(local, target, payload)
	}
}

func probe(local *net.UDPAddr, target string, payload []byte) {
	addr, err := net.ResolveUDPAddr("udp6", target)
	if err != nil {
		fmt.Printf("  %-32s resolve: %v\n", target, err)
		return
	}
	conn, err := net.DialUDP("udp6", local, addr)
	if err != nil {
		fmt.Printf("  %-32s dial: %v\n", target, err)
		return
	}
	defer conn.Close()
	if _, err := conn.Write(payload); err != nil {
		fmt.Printf("  %-32s write: %v\n", target, err)
		return
	}
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 2048)
	n, err := conn.Read(buf)
	if err != nil {
		fmt.Printf("  %-32s sent %d bytes, no answer in 3s\n", target, len(payload))
		return
	}
	kind := "bytes"
	if len(buf) >= 4 && buf[2]&0x80 != 0 {
		kind = "DNS response"
	}
	fmt.Printf("  %-32s ANSWERED %d %s from %s\n", target, n, kind, conn.RemoteAddr())
}


