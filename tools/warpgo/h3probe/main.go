// language: Go, file: main.go, target: android/arm64 - is QUIC reachable at all from this device
//
// The MASQUE edge is silent to QUIC from the device while it answers CRYPTO_ERROR 0x128 from the host,
// and a dial with a filtered SNI is indistinguishable from filtered UDP - both say "no recent network
// activity". So this separates the two by dialling addresses that are known to carry QUIC and are not
// filtered: if those answer from the device, UDP works and the edge is the problem; if they are silent
// too, UDP is the problem and no amount of edge rotation will help.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

type target struct{ label, addr, url string }

func main() {
	targets := []target{
		{"edge 188.114.97.1:443", "188.114.97.1:443", "https://188.114.97.1/"},
		{"edge 162.159.192.6:443", "162.159.192.6:443", "https://162.159.192.6/"},
		{"edge 162.159.192.6:2408", "162.159.192.6:2408", "https://162.159.192.6/"},
		{"edge 188.114.97.1:2408", "188.114.97.1:2408", "https://188.114.97.1/"},
		{"1.1.1.1 control", "1.1.1.1:443", "https://1.1.1.1/cdn-cgi/trace"},
	}
	if v := env("ONLY"); v != "" {
		var keep []target
		for _, t := range targets {
			if strings.Contains(t.label, v) {
				keep = append(keep, t)
			}
		}
		targets = keep
	}
	// SNI override exists for the same reason it does in the tunnel probe: the filter keys on the name,
	// and the edge names both contain cloudflareclient.com.
	sni := env("SNI")
	for _, t := range targets {
		fmt.Printf("=== %s (%s) ===\n", t.label, t.addr)
		if err := one(t, sni); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}
}

func one(t target, sniOverride string) error {
	laddr := &net.UDPAddr{}
	if b := env("BIND"); b != "" {
		laddr.IP = net.ParseIP(b).To4()
	}
	udp, err := net.ListenUDP("udp4", laddr)
	if err != nil {
		return err
	}
	defer udp.Close()
	fmt.Println("  local udp:", udp.LocalAddr())

	host, _, _ := net.SplitHostPort(t.addr)
	sni := sniOverride
	if sni == "" {
		sni = host
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	sw := time.Now()
	conn, err := quic.Dial(ctx, udp, mustAddr(t.addr), &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
		NextProtos:         []string{"h3"},
		MinVersion:         tls.VersionTLS13,
	}, &quic.Config{
		EnableDatagrams:         true,
		InitialPacketSize:       1200,
		DisablePathMTUDiscovery: true,
		MaxIdleTimeout:          30 * time.Second,
	})
	if err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	defer conn.CloseWithError(0, "")
	fmt.Printf("  QUIC handshake OK sni=%s in %dms\n", sni, time.Since(sw).Milliseconds())
	// The edge either supports extended CONNECT or it does not, and nothing else in the handshake says
	// which. quic-go exposes it only through the HTTP/3 layer, so a ClientConn is opened here and its
	// settings read: an edge that answers datagrams=false and extendedConnect=false cannot carry MASQUE
	// no matter how many times the dial is retried.
	tr := &http3.Transport{DisableCompression: true}
	defer tr.Close()
	hc := tr.NewClientConn(conn)
	sctx, scancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer scancel()
	select {
	case <-hc.ReceivedSettings():
		set := hc.Settings()
		fmt.Printf("  SETTINGS extendedConnect=%v datagrams=%v\n", set.EnableExtendedConnect, set.EnableDatagrams)
	case <-sctx.Done():
		fmt.Println("  SETTINGS: none within 8s")
	}

	cl := &http.Client{Transport: tr, Timeout: 20 * time.Second}
	req, err := http.NewRequestWithContext(ctx, "GET", t.url, nil)
	if err != nil {
		return err
	}
	resp, err := cl.Do(req)
	if err != nil {
		return fmt.Errorf("request: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
	fmt.Printf("  status=%d proto=%s bytes=%d\n", resp.StatusCode, resp.Proto, len(raw))
	for _, line := range strings.Split(string(raw), "\n") {
		if strings.HasPrefix(line, "warp=") || strings.HasPrefix(line, "ip=") {
			fmt.Println("   ", strings.TrimSpace(line))
		}
	}
	return nil
}

func mustAddr(a string) *net.UDPAddr {
	u, err := net.ResolveUDPAddr("udp", a)
	if err != nil {
		panic(err)
	}
	return u
}

func env(k string) string { return osGetenv(k) }

func osGetenv(k string) string { return osEnv(k) }
