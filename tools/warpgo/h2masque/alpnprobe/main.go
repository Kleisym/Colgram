// language: Go, file: main.go, target: host probe for what the MASQUE edge negotiates
//
// One question, asked a few ways. The edge at 162.159.198.2 refuses the handshake outright when only
// HTTP/1.1 is offered, demands a client certificate, and then - the part that matters - reports an
// empty ALPN to a client that presented one. An empty ALPN means the connection is HTTP/1.1, and every
// HTTP/2 frame written into it reads as a malformed request, which is exactly what a PROTOCOL_ERROR on
// the first HEADERS looks like from this side.
//
// So before more tunnel code, the negotiation itself has to be measured: which ALPN names, in which
// order, produce h2 back from this edge once the certificate is in the ClientHello.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"time"
)

const edgeSNI = "consumer-masque.cloudflareclient.com"

var addresses = []string{"162.159.198.2:443", "162.159.198.1:443", "162.159.192.6:443"}

// snis are tried against each address with a client certificate presented.
//
// The earlier sweep asked each address for these names without a certificate and read the failures as
// the name being filtered, which is what a timeout looks like. With the certificate presented the
// handshake is answered, and then the negotiation says which of them the edge actually recognises - and
// the registration names its own endpoint engage.cloudflareclient.com, which is the name the edge is
// most likely to route a tunnel for.
var snis = []string{
	"consumer-masque.cloudflareclient.com",
	"engage.cloudflareclient.com",
	"consumer-masque-proxy.cloudflareclient.com",
}

// proposals are the ALPN lists in the order they are tried. Ordering matters: a server picks the first
// entry of the client's list that it also supports, so putting h2 first is the only way to find out
// whether it supports h2 at all.
var proposals = [][]string{
	{"h2"},
	{"h2", "http/1.1"},
	{"http/1.1"},
	{},
}

func main() {
	cert := bareCert()
	for _, addr := range addresses {
		fmt.Printf("\n=== %s ===\n", addr)
		for _, sni := range snis {
			for _, alpn := range proposals {
				r, err := probe(addr, sni, alpn, cert)
				if err != nil {
					fmt.Printf("  sni=%-42s alpn=%-20v ERR %v\n", sni, alpn, err)
					continue
				}
				fmt.Printf("  sni=%-42s alpn=%-20v OK negotiated=%q version=%s\n", sni, alpn, r.alpn, r.version)
			}
		}
		_, err := probe(addr, snis[0], []string{"h2"}, nil)
		if err != nil {
			fmt.Printf("  no client certificate: %v\n", err)
		} else {
			fmt.Println("  no client certificate: accepted")
		}
	}
}

type result struct{ alpn, version string }

func probe(addr, sni string, alpn []string, cert *tls.Certificate) (result, error) {
	conf := &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
		NextProtos:         alpn,
		MinVersion:         tls.VersionTLS12,
	}
	if cert != nil {
		conf.Certificates = []tls.Certificate{*cert}
	}
	d := &net.Dialer{Timeout: 10 * time.Second}
	raw, err := d.Dial("tcp", addr)
	if err != nil {
		return result{}, err
	}
	defer raw.Close()
	tc := tls.Client(raw, conf)
	if err := tc.Handshake(); err != nil {
		return result{}, err
	}
	st := tc.ConnectionState()
	tc.Close()
	return result{alpn: st.NegotiatedProtocol, version: versionName(st.Version)}, nil
}

func versionName(v uint16) string {
	switch v {
	case tls.VersionTLS12:
		return "1.2"
	case tls.VersionTLS13:
		return "1.3"
	}
	return fmt.Sprintf("0x%04x", v)
}

// bareCert builds the certificate the edge is enrolled against: a P-256 self-signed certificate with an
// empty subject and no extensions. Nothing verifies it - the edge matches it against the public key in
// the registration.
func bareCert() *tls.Certificate {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		panic(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(0),
		Subject:      pkix.Name{},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(365 * 24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, &x509.Certificate{}, &key.PublicKey, key)
	if err != nil {
		panic(err)
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	pair, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		panic(err)
	}
	return &pair
}
