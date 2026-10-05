// sniprobe asks the MASQUE edge under each of the SNI names Cloudflare ships, and reports whether
// any of them is answered from this device.
//
// Why it exists. Every probe until now used one name: consumer-masque.cloudflareclient.com, the
// tunnel SNI. The binary logs two fields separately - tunnel_sni and proxy_sni - and the proxy path
// runs through its own code with its own names. If the filter keys on the name it sees in the
// ClientHello, a name it does not know about is a way through, and this device does reach the edge
// on TCP. Four names are tried per run so the question is answered by measurement rather than by
// picking one name and concluding.
//
// The names resolve to nothing in public DNS - expected for names only used against a pinned
// address - so each is tried against the same pinned address, with certificate verification off,
// exactly as the working client does.
package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"os"
	"time"

	"github.com/quic-go/quic-go"
)

const edgeIP = "162.159.198.2"

// The names Cloudflare's client binary refers to, grouped by which code path uses them. The
// distinction is the point: a name from another path is a name the filter may not have.
var snis = []struct {
	name string
	note string
}{
	{"consumer-masque.cloudflareclient.com", "tunnel: the one the working client uses"},
	{"consumer-masque-proxy.cloudflareclient.com", "proxy path, its own code in the binary"},
	{"zt-masque-proxy.cloudflareclient.com", "zero trust proxy"},
	{"zt-masque.cloudflareclient.com", "zero trust tunnel"},
	{"connectivity.cloudflareclient.com", "the name behind cdn-cgi/trace"},
	{"engage.cloudflareclient.com", "the engage endpoint"},
	{"api.cloudflareclient.com", "the registration API"},
}

func main() {
	bind := os.Getenv("SNI_BIND")
	port := os.Getenv("SNI_PORT")
	if port == "" {
		port = "443"
	}
	local := &net.UDPAddr{}
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			fmt.Println("SNI_BIND is not IPv4:", bind)
			os.Exit(1)
		}
		local.IP = ip
	}
	p, _ := net.LookupPort("udp", port)
	addr := &net.UDPAddr{IP: net.ParseIP(edgeIP).To4(), Port: p}

	fmt.Printf("edge %s port %s, bind %q\n\n", edgeIP, port, bind)
	for _, s := range snis {
		probe(local, addr, s.name, s.note)
	}
}

// probe asks one question and says what came back.
//
// Three outcomes, because "no answer", "answered" and "answered then refused" are different facts
// and only one of them means the path is open. A server that completes the handshake and then
// terminates has read the name and rejected the request; one that never answers has told us
// nothing got there.
func probe(local, addr *net.UDPAddr, sni, note string) {
	sock, err := net.ListenUDP("udp4", local)
	if err != nil {
		fmt.Printf("  %-44s socket: %v\n", sni, err)
		return
	}
	defer sock.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	start := time.Now()
	conn, err := quic.Dial(ctx, sock, addr, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
		NextProtos:         []string{"h3"},
		MinVersion:         tls.VersionTLS13,
	}, &quic.Config{
		// 1200 because that is the Initial size the edge answers; anything larger is dropped.
		InitialPacketSize: 1200,
	})
	if err != nil {
		fmt.Printf("  %-44s no handshake in %dms: %v\n",
			sni, time.Since(start).Milliseconds(), err)
		return
	}
	defer conn.CloseWithError(0, "")
	alpn := conn.ConnectionState().TLS.NegotiatedProtocol
	fmt.Printf("  %-44s HANDSHAKE OK in %dms alpn=%q  (%s)\n",
		sni, time.Since(start).Milliseconds(), alpn, note)
}
