// q3probe completes a QUIC handshake and an HTTP/3 GET against any host, with no MASQUE and no
// registration involved.
//
// It exists to answer one question cleanly: does QUIC work from this machine at all. The WARP
// client mixes transport with protocol, so when it stalls there are two suspects - the path and the
// tunnel - and this removes the tunnel from the picture.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

func main() {
	url := "https://cloudflare-quic.com/"
	if len(os.Args) > 1 {
		url = os.Args[1]
	}

	local := &net.UDPAddr{}
	if bind := os.Getenv("Q3_BIND"); bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			fmt.Println("Q3_BIND is not an IPv4 address:", bind)
			os.Exit(1)
		}
		local.IP = ip
	}

	addr, resolveErr := net.ResolveUDPAddr("udp4", hostPort(url))
	if ip := os.Getenv("Q3_IP"); ip != "" {
		addr = &net.UDPAddr{IP: net.ParseIP(ip).To4(), Port: 443}
		fmt.Println("using address literal:", addr.String())
	}
	if addr == nil {
		fmt.Println("resolve:", resolveErr)
		os.Exit(1)
	}
	conn, err := net.ListenUDP("udp4", local)
	if err != nil {
		fmt.Println("listen:", err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Println("local          :", conn.LocalAddr())
	fmt.Println("target         :", addr.String())

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	qconn, err := quic.Dial(ctx, conn, addr, &tls.Config{
		ServerName:         hostOnly(url),
		NextProtos:         []string{"h3"},
		InsecureSkipVerify: true,
		MinVersion:         tls.VersionTLS13,
	}, &quic.Config{
		InitialPacketSize: 1200,
		MaxIdleTimeout:    10 * time.Second,
		// Datagrams stay off unless asked for: a server that does not advertise
		// max_datagram_frame_size rejects the connection outright, which looks like a transport
		// failure and is not one.
		EnableDatagrams: os.Getenv("Q3_DATAGRAMS") != "",
	})
	if err != nil {
		fmt.Println("quic handshake failed:", err)
		os.Exit(2)
	}
	defer qconn.CloseWithError(0, "done")
	fmt.Println("quic handshake  : completed")

	// The URL host is used for SNI and for the Host header, while the connection is already
	// dialled at a fixed address. On the device the system resolver does not run, and the point is
	// to measure the transport, not the resolver.
	tr := &http3.Transport{EnableDatagrams: false}
	// Reuse the connection just established, so the request needs no name resolution at all.
	tr.Dial = func(ctx context.Context, _ string, _ *tls.Config, _ *quic.Config) (*quic.Conn, error) {
		return qconn, nil
	}
	defer tr.Close()
	client := &http.Client{
		Transport: tr,
		Timeout:   15 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		fmt.Println("request:", err)
		os.Exit(3)
	}
	// The request keeps its own host for SNI and the Host header even though the connection was
	// dialled at a fixed address literal.
	resp, err := client.Do(req)
	if err != nil {
		fmt.Println("http/3 get failed:", err)
		os.Exit(3)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
	fmt.Printf("http/3          : %s, %d bytes\n", resp.Status, len(body))
	fmt.Println("alpn            :", resp.Proto)
	if len(body) > 0 {
		fmt.Println("body head       :", string(body[:min(120, len(body))]))
	}
	// The trace endpoint reports the source address the edge saw. That is the egress interface,
	// which matters here: two machines on the same network can leave by different adapters.
	for _, line := range strings.Split(string(body), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "ip=") || strings.HasPrefix(line, "loc=") ||
			strings.HasPrefix(line, "colo=") || strings.HasPrefix(line, "warp=") {
			fmt.Println("trace           :", line)
		}
	}
	fmt.Println()
	fmt.Println("VERDICT: QUIC and HTTP/3 work from this machine")
}

func hostPort(raw string) string {
	u, _ := url.Parse(raw)
	port := "443"
	if u.Port() != "" {
		port = u.Port()
	}
	return net.JoinHostPort(u.Hostname(), port)
}

func hostOnly(raw string) string {
	u, _ := url.Parse(raw)
	return u.Hostname()
}
