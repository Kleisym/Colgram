// edgescan asks one question per address: does a real QUIC handshake complete to it from this
// machine?
//
// The hypothesis is narrow and worth testing. Every failure so far has been explained by egress:
// "this edge answers from that source address and drops the same handshake from another". That
// explanation predicts a different address behaves the same as the dropped one - which is exactly
// what a per-IP block would NOT do. Nothing has distinguished the two.
//
// The first version of this file assembled a QUIC Initial by hand and reported "no answer" for
// every address - including on the host, where quic-go completes the same handshake in milliseconds.
// That was the scanner being wrong, not the network. "Nothing answers" looks exactly like a block,
// so this uses the stack the tunnel itself uses, which makes a result a fact about the path.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"os"
	"strconv"
	"time"

	"github.com/quic-go/quic-go"
)

func main() {
	bind := os.Getenv("SCAN_BIND")
	port := os.Getenv("SCAN_PORT")
	if port == "" {
		port = "443"
	}
	addrs := os.Args[1:]

	local := &net.UDPAddr{}
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			fmt.Println("SCAN_BIND is not IPv4:", bind)
			os.Exit(1)
		}
		local.IP = ip
	}

	for _, addr := range addrs {
		ip := net.ParseIP(addr).To4()
		if ip == nil {
			fmt.Printf("%-16s  not an IPv4 address\n", addr)
			continue
		}
		conn, err := net.ListenUDP("udp4", local)
		if err != nil {
			fmt.Println("socket:", err)
			return
		}
		p, perr := strconv.Atoi(port)
		if perr != nil {
			p = 443
		}

		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		start := time.Now()
		_, err = quic.Dial(ctx, conn, &net.UDPAddr{IP: ip, Port: p},
			&tls.Config{
				InsecureSkipVerify: true,
				ServerName:         "consumer-masque.cloudflareclient.com",
				NextProtos:         []string{"h3"},
				MinVersion:         tls.VersionTLS13,
			},
			&quic.Config{
				// 1200 because this edge answers that size and drops anything larger.
				InitialPacketSize: 1200,
			})
		elapsed := time.Since(start)
		conn.Close()
		cancel()

		if err != nil {
			fmt.Printf("%-16s  handshake failed after %4dms  %v\n", addr, elapsed.Milliseconds(), err)
			continue
		}
		fmt.Printf("%-16s  HANDSHAKE OK in %4dms\n", addr, elapsed.Milliseconds())
	}
}
