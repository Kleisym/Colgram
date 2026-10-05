// tcpprobe asks whether the edge's TCP port can be reached through a SOCKS5 or HTTP CONNECT relay.
//
// Why it exists. UDP to Cloudflare is filtered from this device - measured across the whole edge
// range and every port it serves QUIC on - but TCP to the same address and port connects. That
// makes a relay which already carries TCP for the app the one remaining way to reach the edge
// without a host-side helper: the relay's egress is not this device's, so whatever the filter keys
// on the device's own address does not apply.
//
// The question is narrow and answerable: does an existing relay connect to 162.159.198.2:443, and
// does it speak SOCKS5 rather than only HTTP CONNECT? Both are asked, because a relay that only
// does CONNECT still carries a TCP MASQUE tunnel, and one that does SOCKS5 carries UDP as well -
// which is the difference between two designs.
package main

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"
)

const edge = "162.159.198.2:443"

func main() {
	list := os.Getenv("RELAYS")
	if list == "" {
		fmt.Println("set RELAYS to a comma-separated list of host:port")
		os.Exit(2)
	}
	for _, r := range strings.Split(list, ",") {
		r = strings.TrimSpace(r)
		if r == "" {
			continue
		}
		probeSocks(r)
		probeConnect(r)
	}
}

// probeSocks asks for a UDP ASSOCIATE. A granted association proves the relay speaks SOCKS5 with
// UDP support, which is what an in-process tunnel would ride; a refusal is recorded as itself,
// because "this relay cannot carry UDP" is a fact about the relay, not about the path.
func probeSocks(relay string) {
	c, err := net.DialTimeout("tcp", relay, 6*time.Second)
	if err != nil {
		fmt.Printf("  %-26s socks5   dial: %v\n", relay, err)
		return
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(6 * time.Second))
	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		fmt.Printf("  %-26s socks5   greeting: %v\n", relay, err)
		return
	}
	h := make([]byte, 2)
	if _, err := io.ReadFull(c, h); err != nil {
		fmt.Printf("  %-26s socks5   no method reply: %v\n", relay, err)
		return
	}
	if h[1] != 0x00 {
		fmt.Printf("  %-26s socks5   method refused: 0x%02x\n", relay, h[1])
		return
	}
	// UDP ASSOCIATE with a zero address, which per RFC 1928 means "accept from wherever this control
	// connection came from".
	if _, err := c.Write([]byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		fmt.Printf("  %-26s socks5   request: %v\n", relay, err)
		return
	}
	rep := make([]byte, 10)
	if _, err := io.ReadFull(c, rep); err != nil {
		fmt.Printf("  %-26s socks5   no reply: %v\n", relay, err)
		return
	}
	if rep[1] != 0x00 {
		fmt.Printf("  %-26s socks5   associate refused: %d\n", relay, rep[1])
		return
	}
	fmt.Printf("  %-26s socks5   *** UDP ASSOCIATE GRANTED, relay port %d ***\n",
		relay, int(rep[2])<<8|int(rep[3]))
}

// probeConnect asks for a plain TCP tunnel to the edge. If this succeeds the edge is reachable from
// a relay's network, and MASQUE over H2 becomes possible from the device.
func probeConnect(relay string) {
	c, err := net.DialTimeout("tcp", relay, 6*time.Second)
	if err != nil {
		fmt.Printf("  %-26s connect  dial: %v\n", relay, err)
		return
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := io.WriteString(c, "CONNECT "+edge+" HTTP/1.1\r\nHost: "+edge+"\r\n\r\n"); err != nil {
		fmt.Printf("  %-26s connect  write: %v\n", relay, err)
		return
	}
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil {
		fmt.Printf("  %-26s connect  no status: %v\n", relay, err)
		return
	}
	if strings.Contains(line, " 200 ") {
		fmt.Printf("  %-26s connect  *** %s ***\n", relay, strings.TrimSpace(line))
		return
	}
	fmt.Printf("  %-26s connect  %s\n", relay, strings.TrimSpace(line))
}
