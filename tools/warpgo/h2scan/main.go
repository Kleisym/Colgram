// language: Go, file: main.go, target: host - which edge ports advertise extended CONNECT over h2
//
// MASQUE over HTTP/2 needs SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8) from the server before an extended
// CONNECT is legal, per RFC 8441. The one edge measured this session did not send it and answered the
// CONNECT with PROTOCOL_ERROR, and QUIC to the MASQUE ports is silent on this network. So this asks the
// question across the whole ingress set and the plausible ports at once, and reports the raw SETTINGS
// so the answer is a number rather than an interpretation.
package main

import (
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

type addr struct{ ip, port, sni string }

func main() {
	ips := []string{"188.114.97.1", "162.159.192.6", "162.159.192.1", "162.159.193.1", "162.159.198.2", "188.114.96.1"}
	ports := []string{"443", "2053", "8443", "4443", "500"}
	snis := []string{"engage.cloudflareclient.com", "consumer-masque.cloudflareclient.com", "cloudflare.com"}

	var all []addr
	for _, ip := range ips {
		for _, p := range ports {
			for _, s := range snis {
				all = append(all, addr{ip, p, s})
			}
		}
	}

	var wg sync.WaitGroup
	var mu sync.Mutex
	found := 0
	for _, a := range all {
		wg.Add(1)
		go func(a addr) {
			defer wg.Done()
			settings, err := probe(a)
			if err != nil {
				return
			}
			has8 := strings.Contains(settings, "08 00 00 00 01") || strings.Contains(settings, "00 08 00 00 00 01")
			if has8 {
				mu.Lock()
				found++
				fmt.Printf("*** EXTENDED CONNECT: %s sni=%s\n    settings: %s\n", a.ip+":"+a.port, a.sni, settings)
				mu.Unlock()
				return
			}
			mu.Lock()
			fmt.Printf("    %s sni=%-38s %s\n", a.ip+":"+a.port, a.sni, settings)
			mu.Unlock()
		}(a)
	}
	wg.Wait()
	fmt.Println("\nports advertising SETTINGS_ENABLE_CONNECT_PROTOCOL:", found)
}

// probe reads the server SETTINGS frame and returns its payload as hex. Nothing else: no preface, no
// CONNECT, because the answer to this question is in the first frame the peer sends.
func probe(a addr) (string, error) {
	d := net.Dialer{Timeout: 5 * time.Second}
	raw, err := d.Dial("tcp", net.JoinHostPort(a.ip, a.port))
	if err != nil {
		return "", err
	}
	defer raw.Close()
	raw.SetDeadline(time.Now().Add(9 * time.Second))

	tc := tls.Client(raw, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         a.sni,
		NextProtos:         []string{"h2"},
		MinVersion:         tls.VersionTLS12,
	})
	if err := tc.Handshake(); err != nil {
		return "", err
	}
	if _, err := tc.Write([]byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")); err != nil {
		return "", err
	}
	if _, err := tc.Write([]byte{0, 0, 0, 4, 0, 0, 0, 0, 0}); err != nil {
		return "", err
	}
	var head [9]byte
	if _, err := io.ReadFull(tc, head[:]); err != nil {
		return "", err
	}
	if head[3] != 0x4 {
		return "", fmt.Errorf("first frame type 0x%x, not SETTINGS", head[3])
	}
	n := int(head[0])<<16 | int(head[1])<<8 | int(head[2])
	p := make([]byte, n)
	if _, err := io.ReadFull(tc, p); err != nil {
		return "", err
	}
	out := make([]string, 0, n)
	for _, b := range p {
		out = append(out, fmt.Sprintf("%02x", b))
	}
	return strings.Join(out, " "), nil
}
