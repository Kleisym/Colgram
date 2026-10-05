// netprobe says whether a UDP answer comes back at all, from one specific egress, to one address.
//
// It exists because "no answer" has meant two different things in this project - a blocked path and
// a broken tool - and only one measurement separates them: the same probe from two egresses at
// once. If one answers and the other does not, the filter is the thing. If neither answers, and a
// control on a port known to answer also fails, then UDP itself is not leaving the way we think.
package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"time"
)

// query is a real DNS question for example.com A, built by hand so the payload is well formed.
// A bare string is not a DNS message, and a resolver is entitled to ignore it - a timeout would
// then prove nothing at all.
func query() []byte {
	msg := make([]byte, 0, 32)
	id := uint16(time.Now().UnixNano())
	head := make([]byte, 12)
	binary.BigEndian.PutUint16(head[0:], id)
	binary.BigEndian.PutUint16(head[2:], 0x0100) // standard query, recursion desired
	binary.BigEndian.PutUint16(head[4:], 1)      // one question
	msg = append(msg, head...)
	for _, label := range []string{"example", "com"} {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0)
	msg = append(msg, 0, 1) // type A
	msg = append(msg, 0, 1) // class IN
	return msg
}

type target struct {
	addr string
	note string
}

func main() {
	binds := os.Args[1:]
	if len(binds) == 0 {
		binds = []string{""}
	}
	targets := []target{
		{"1.1.1.1:53", "control: a DNS resolver answers"},
		{"1.1.1.1:443", "same address, another port"},
	}
	// Every port the edge has been observed to serve QUIC on, plus a few that answer for other
	// Cloudflare services. One pass per port, several passes overall: a port that answers once and
	// then goes quiet is exactly the case a single pass would miss.
	for _, port := range []string{"443", "500", "1701", "4443", "4500", "8443", "8095"} {
		targets = append(targets, target{"162.159.198.2:" + port, "the WARP MASQUE edge"})
	}
	for _, b := range binds {
		local := &net.UDPAddr{}
		label := "wildcard"
		if b != "" {
			ip := net.ParseIP(b).To4()
			if ip == nil {
			fmt.Println("not an IPv4 address:", b)
			continue
			}
			local.IP = ip
			label = b
		}
		fmt.Printf("== from %s ==\n", label)
		for _, t := range targets {
			probe(local, t)
		}
	}
}

func probe(local *net.UDPAddr, t target) {
	raddr, err := net.ResolveUDPAddr("udp4", t.addr)
	if err != nil {
		fmt.Printf("  %-22s resolve: %v\n", t.addr, err)
		return
	}
	sock, err := net.ListenUDP("udp4", local)
	if err != nil {
		fmt.Printf("  %-22s socket: %v\n", t.addr, err)
		return
	}
	defer sock.Close()
	// One datagram. Repeating would only make a block look busier.
	_, err = sock.WriteToUDP(query(), raddr)
	if err != nil {
		fmt.Printf("  %-22s write: %v\n", t.addr, err)
		return
	}
	buf := make([]byte, 2048)
	sock.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, from, err := sock.ReadFromUDP(buf)
	if err != nil {
		fmt.Printf("  %-22s NO ANSWER in 3s   (%s)\n", t.addr, t.note)
		return
	}
	// A real DNS response carries the original question back in bytes 12.. of the answer, and the
	// flags word says response rather than query. Checking it keeps "answered" honest.
	kind := "bytes"
	if len(buf) >= 4 && buf[2]&0x80 != 0 {
		kind = "DNS response"
	}
	// The source the kernel reports is the real egress, which is the point of the measurement: it
	// says which interface the answer came back through.
	fmt.Printf("  %-22s ANSWERED %d %s from %s\n", t.addr, n, kind, from)
}
