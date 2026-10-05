// language: Go, file: main.go, target: android/arm64 - can a working SOCKS proxy carry UDP to the WARP edge
//
// The edge is silent to UDP from this network, and a pool of public SOCKS proxies has just been shown
// to complete a real MTProto handshake through tgnet. If one of those also relays UDP ASSOCIATE, the
// MASQUE carrier has a path: the datagrams leave through the proxy egress rather than through this
// network, which drops them. That is the one remaining lever, and this measures it.
//
// A QUIC Initial is the probe because the edge answer to it is unambiguous.
package main

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

func main() {
	proxies := os.Getenv("PROXIES")
	if proxies == "" {
		proxies = "t.meow-network.com:443,mtp.webvirt.cloud:443,edge.ehtemal.info:443"
	}
	edge := os.Getenv("EDGE")
	if edge == "" {
		edge = "162.159.192.6:2408"
	}
	relay := os.Getenv("RELAY")
	if relay == "" {
		relay = "127.0.0.1:1"
	}
	for _, p := range strings.Split(proxies, ",") {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		fmt.Printf("=== proxy %s ===\n", p)
		if err := probe(p, edge, relay); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}
}

func probe(proxy, edge, relay string) error {
	phost, _, _ := net.SplitHostPort(proxy)
	ip := phost
	if net.ParseIP(phost) == nil {
		ip = resolveProxy(proxy)
	}
	if ip == "" {
		return fmt.Errorf("no A record over DoH")
	}
	ctrl, err := net.DialTimeout("tcp", net.JoinHostPort(ip, proxyPort(proxy)), 8*time.Second)
	if err != nil {
		return fmt.Errorf("dial proxy: %w", err)
	}
	defer ctrl.Close()
	if _, err := ctrl.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return err
	}
	h := []byte{0, 0}
	if _, err := io.ReadFull(ctrl, h); err != nil {
		return fmt.Errorf("greeting: %w", err)
	}
	if h[1] != 0x00 {
		return fmt.Errorf("proxy wants auth, method 0x%02x", h[1])
	}
	rhost, rportStr, _ := net.SplitHostPort(relay)
	rport, _ := strconv.Atoi(rportStr)
	relayIP := net.ParseIP(rhost).To4()
	if relayIP == nil {
		relayIP = []byte{0, 0, 0, 0}
	}
	assoc := []byte{0x05, 0x03, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3],
		byte(rport >> 8), byte(rport)}
	if _, err := ctrl.Write(assoc); err != nil {
		return err
	}
	rh := make([]byte, 10)
	ctrl.SetReadDeadline(time.Now().Add(8 * time.Second))
	if _, err := io.ReadFull(ctrl, rh); err != nil {
		return fmt.Errorf("associate: %w", err)
	}
	if rh[1] != 0x00 {
		return fmt.Errorf("associate refused, code %d", rh[1])
	}
	bind := net.JoinHostPort(fmt.Sprintf("%d.%d.%d.%d", rh[4], rh[5], rh[6], rh[7]),
		strconv.Itoa(int(rh[8])<<8|int(rh[9])))
	fmt.Println("  associate ok, BND:", bind)
	bhost, bportStr, _ := net.SplitHostPort(bind)
	bport, _ := strconv.Atoi(bportStr)
	if bport == 0 {
		return fmt.Errorf("proxy bound port 0: unreachable")
	}
	uaddr := net.UDPAddr{IP: net.ParseIP(bhost), Port: bport}
	ehost, eportStr, _ := net.SplitHostPort(edge)
	eport, _ := strconv.Atoi(eportStr)
	eip := net.ParseIP(ehost).To4()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		return err
	}
	defer conn.Close()
	var initial [1200]byte
	binary.LittleEndian.PutUint32(initial[0:4], 0x00000001)
	binary.LittleEndian.PutUint32(initial[4:8], 0x00000002)
	rand.Read(initial[8:20])
	binary.LittleEndian.PutUint64(initial[20:28], 0x0700000000000001)
	pkt := []byte{0, 0, 0, 0x01, eip[0], eip[1], eip[2], eip[3],
		byte(eport >> 8), byte(eport)}
	pkt = append(pkt, initial[:]...)
	if _, err := conn.WriteToUDP(pkt, &uaddr); err != nil {
		return fmt.Errorf("write to relay: %w", err)
	}
	fmt.Printf("  sent %d-byte datagram via %s\n", len(pkt), uaddr)
	buf := make([]byte, 2048)
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	n, from, err := conn.ReadFromUDP(buf)
	if err != nil {
		return fmt.Errorf("no answer from the edge: %w", err)
	}
	fmt.Printf("  ANSWERED: %d bytes from %s\n", n, from)
	return nil
}

// proxyPort is the port in a host:port pair.
func proxyPort(p string) string {
	_, port, _ := net.SplitHostPort(p)
	return port
}

// resolveProxy asks DoH for an A record. The system resolver on this device answers nothing at all -
// measured: "lookup X on [::1]:53: read udp [::1]:37268->[::1]:53: read: connection refused" - and a
// Go binary that cannot resolve its own proxy cannot test it. This is the same masking the app does.
func resolveProxy(name string) string {
	host, _, _ := net.SplitHostPort(name)
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query"} {
		req, err := http.NewRequest("GET", r+"?name="+host+"&type=A", nil)
		if err != nil {
			continue
		}
		req.Header.Set("Accept", "application/dns-json")
		cl := &http.Client{Timeout: 10 * time.Second}
		resp, err := cl.Do(req)
		if err != nil {
			continue
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var out struct {
			Answer []struct {
				Type int    `json:"type"`
				Data string `json:"data"`
			} `json:"Answer"`
		}
		json.Unmarshal(raw, &out)
		for _, a := range out.Answer {
			if a.Type == 1 {
				return a.Data
			}
		}
	}
	return ""
}
