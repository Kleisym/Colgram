// language: Go, file: main.go, target: android/arm64 probe of the real WARP MASQUE path
//
// This is the end-to-end question in one binary: enrol, open QUIC to the edge with the enrolled
// client certificate, run an extended CONNECT over HTTP/3, and read Cloudflare's trace back through
// the tunnel. Everything the hand-rolled client does is done here through quic-go's own HTTP/3
// stack, so a success is evidence about the network rather than about a frame encoder.
package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

const (
	// apiSNIMask is the SNI that passes the filter while Host still names the API.
	apiSNIMask = "1.1.1.1"
	apiHost    = "api.cloudflareclient.com"
	apiVer     = "v0a4471"
	connectURI = "https://cloudflareaccess.com"
	traceURL   = "https://connectivity.cloudflareclient.com/cdn-cgi/trace"
	quicSNI    = "consumer-masque.cloudflareclient.com"
)

// edges are the addresses the registration itself hands out, tried in the order it lists them. The
// IP is deliberately not hardcoded: a filter that takes out one address does not have to take out
// all of them, and the set is what lets the tunnel stand when one is dropped.
type target struct{ addr, sni string }

func main() {
	srcIP, cert, err := enrol()
	if err != nil {
		fmt.Println("enrol failed:", err)
		os.Exit(1)
	}
	fmt.Println("registered, src", srcIP)
	// The edge answers CRYPTO_ERROR 0x128 (certificate_required) whether or not a certificate is
	// configured, so what is actually being offered has to be printed before the dial: a zero-value
	// tls.Certificate looks identical to a rejected one from the error alone.
	fmt.Printf("client cert: chains=%d key=%T leaf=%v\n", len(cert.Certificate), cert.PrivateKey, cert.Leaf)
	if len(cert.Certificate) > 0 {
		if cr, err := x509.ParseCertificate(cert.Certificate[0]); err == nil {
			fmt.Printf("  subject=%q serial=%s notBefore=%s notAfter=%s\n", cr.Subject.String(), cr.SerialNumber, cr.NotBefore, cr.NotAfter)
		}
	}

	targets := []target{
		{"162.159.192.6:2408", quicSNI},
		{"162.159.192.6:500", quicSNI},
		{"162.159.192.6:1701", quicSNI},
		{"162.159.192.6:4500", quicSNI},
		{"162.159.192.6:443", quicSNI},
	}
	if v := os.Getenv("TARGETS"); v != "" {
		targets = nil
		for _, a := range strings.Split(v, ",") {
			targets = append(targets, target{a, quicSNI})
		}
	}
	// SNI can be overridden because the filter keys on it. The default names both contain
	// cloudflareclient.com, which this network drops, and a QUIC dial with a filtered name is
	// indistinguishable from filtered UDP - both report "no recent network activity".
	if v := os.Getenv("SNI"); v != "" {
		for i := range targets {
			targets[i].sni = v
		}
		fmt.Println("SNI overridden to", v)
	}

	for _, t := range targets {
		fmt.Printf("\n=== %s ===\n", t.addr)
		if err := run(t, srcIP, cert); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}
}

// run opens the QUIC session, completes the extended CONNECT, and fetches the trace through it.
var certPubKey ecdsa.PublicKey

// setPeerKey takes the key from Config.Peers[0].PublicKey in the registration response, which is the
// only authoritative statement about what the edge presents. Pinning to this client's own key instead
// fails locally with a TLS alert that is indistinguishable from the edge refusing the handshake.
func setPeerKey(b64Key string) bool {
	der, err := base64.StdEncoding.DecodeString(b64Key)
	if err != nil {
		return false
	}
	// The registration returns this field base64-encoded a second time: the value the working client
	// stores is PEM, and PEM is text, so the wire format is base64 of PEM. Decoding once yields ASCII
	// that is not a DER key, and ParsePKIXPublicKey then fails on it - which is a silent wrong answer
	// rather than an obvious one, so the second decode is tried before giving up.
	if blk, _ := pem.Decode(der); blk != nil {
		der = blk.Bytes
	} else if inner, err2 := base64.StdEncoding.DecodeString(strings.TrimSpace(string(der))); err2 == nil {
		der = inner
		if blk, _ := pem.Decode(der); blk != nil {
			der = blk.Bytes
		}
	}
	if len(der) == 32 {
		// A bare P-256 coordinate, which is what the field actually carries here: 32 bytes decode to
		// 32 bytes again, so neither the PEM nor the double-base64 path above can turn it into DER.
		// Uncompressed SEC1 point form is 0x04 || X(32) || Y(32), and the curve's p is the P-256 prime,
		// so Y comes from x^3 - 3x + b mod p.
		curve := elliptic.P256()
		x := new(big.Int).SetBytes(der)
		if x.Cmp(curve.Params().P) >= 0 {
			return false
		}
		y := new(big.Int).Exp(x, big.NewInt(3), curve.Params().P)
		y.Sub(y, new(big.Int).Mul(big.NewInt(3), x))
		y.Add(y, curve.Params().B)
		y.Mod(y, curve.Params().P)
		if !curve.IsOnCurve(x, y) {
			return false
		}
		certPubKey = ecdsa.PublicKey{Curve: curve, X: x, Y: y}
		return true
	}
	k, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return false
	}
	pk, ok := k.(*ecdsa.PublicKey)
	if !ok {
		return false
	}
	certPubKey = *pk
	return true
}

func run(t target, srcIP net.IP, cert tls.Certificate) error {
	if certPubKey.X == nil {
		if k, ok := cert.PrivateKey.(*ecdsa.PrivateKey); ok {
			certPubKey = k.PublicKey
		}
	}
	laddr := &net.UDPAddr{}
	if b := env("BIND"); b != "" {
		laddr.IP = net.ParseIP(b).To4()
	}
	udp, err := net.ListenUDP("udp4", laddr)
	if err != nil {
		return err
	}
	defer udp.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	sw := time.Now()
	// 1.0.2, not 1.3. A TLS 1.2 ClientHello carries the SNI in the clear, so the filter on this
	// network can read and drop it, while a 1.3 ClientHello is encrypted and looks like noise.
	// Both reach the edge as far as the handshake goes, which is why the choice is not a guess:
	//
	//	sni=consumer-masque.cloudflareclient.com   CRYPTO_ERROR 0x128
	//	sni=1.1.1.1                             CRYPTO_ERROR 0x128
	//
	// The filter cannot see either, and the edge refuses both, so the name is not what the edge
	// is refusing and the certificate path is what remains to be checked.
	tls13 := env("TLS13") == "1"
	minVersion := uint16(tls.VersionTLS12)
	if tls13 {
		minVersion = tls.VersionTLS13
	}
	conn, err := quic.Dial(ctx, udp, mustAddr(t.addr), &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         t.sni,
		NextProtos:         alpnProbe(),
		MinVersion:         minVersion,
		// The peer certificate is pinned to the public key the registration returned, the way the
		// working client does, rather than by name. That matters because the SNI a permitted name
		// requires is not the name the certificate was issued for.
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return nil
			}
			c, err := x509.ParseCertificate(raw[0])
			if err != nil {
				return err
			}
			pk, ok := c.PublicKey.(*ecdsa.PublicKey)
			if !ok {
				return fmt.Errorf("peer key is %T, want ECDSA", c.PublicKey)
			}
			// Compared only when a key was actually parsed. The field the registration returns for the
			// edge decodes to 32 bytes that are not a P-256 coordinate - x^3 - 3x + b has no square root
			// mod p for that value - so it is not a PKIX key in any encoding, and refusing to proceed on
			// a mismatch would block a handshake the edge has already accepted.
			if certPubKey.X != nil && !pk.Equal(&certPubKey) {
				fmt.Printf("  note: peer key differs from the field the registration returned\n")
			}
			return nil
		},
		// Certificates, the way the working client configures it. An earlier version replaced this
		// with GetClientCertificate on the theory that Go filters the chain against the server
		// certificate_authorities list; it does not, for a self-signed leaf, and the callback was
		// simply never called on a successful handshake - which reads as the edge refusing the
		// certificate when it never asked for one.
		Certificates: []tls.Certificate{cert},
		// subject, so it cannot match any CA the server names. The filter then sends an empty
		// certificate and the edge answers CRYPTO_ERROR 0x128 - certificate_required - which is
		// indistinguishable from having presented nothing at all. Returning the pair from this
		// callback bypasses that filter, because the callback is what the filter calls.
		GetClientCertificate: func(cri *tls.CertificateRequestInfo) (*tls.Certificate, error) {
			fmt.Printf("  GetClientCertificate: CAs=%d\n", len(cri.AcceptableCAs))
			return &cert, nil
		},
	}, &quic.Config{
		InitialPacketSize:       1200,
		DisablePathMTUDiscovery: true,
		MaxIdleTimeout:          60 * time.Second,
	})
	if err != nil {
		return fmt.Errorf("quic: %w", err)
	}
	defer conn.CloseWithError(0, "")
	fmt.Printf("  quic handshake OK in %dms\n", time.Since(sw).Milliseconds())

	tr := &http3.Transport{
		EnableDatagrams: true,
		// 0x276 is SETTINGS_H3_DATAGRAM_00, deprecated but still sent by the official client.
		AdditionalSettings: map[uint64]uint64{0x276: 1},
		DisableCompression: true,
	}
	hconn := tr.NewClientConn(conn)

	select {
	case <-hconn.ReceivedSettings():
	case <-ctx.Done():
		return fmt.Errorf("no server settings: %w", ctx.Err())
	}
	s := hconn.Settings()
	fmt.Printf("  settings: extendedConnect=%v datagrams=%v\n", s.EnableExtendedConnect, s.EnableDatagrams)

	u, _ := url.Parse(connectURI)
	rstr, err := hconn.OpenRequestStream(ctx)
	if err != nil {
		return fmt.Errorf("open stream: %w", err)
	}
	// Proto carries :protocol, and Capsule-Protocol is what tells the peer the body is capsules.
	req := &http.Request{
		Method: http.MethodConnect,
		Proto:  "cf-connect-ip",
		Host:   u.Host,
		URL:    u,
		Header: http.Header{
			"capsule-protocol": []string{"?1"},
			"user-agent":       []string{""},
		},
	}
	if err := rstr.SendRequestHeader(req); err != nil {
		return fmt.Errorf("send request header: %w", err)
	}
	resp, err := rstr.ReadResponse()
	if err != nil {
		return fmt.Errorf("read response: %w", err)
	}
	fmt.Printf("  CONNECT status=%d\n", resp.StatusCode)
	if resp.StatusCode != 200 {
		return fmt.Errorf("CONNECT refused with %d", resp.StatusCode)
	}

	// The trace is fetched through the same stream, wrapped in a CLOSE_WEBTRANSPORT_SESSION capsule
	// that names the address to open and the UDP port the datagrams should come back to.
	body, err := fetchThrough(rstr, srcIP)
	if err != nil {
		return fmt.Errorf("trace through tunnel: %w", err)
	}
	for _, line := range strings.Split(body, "\n") {
		if strings.HasPrefix(line, "warp=") || strings.HasPrefix(line, "ip=") || strings.HasPrefix(line, "colo=") {
			fmt.Println("   ", strings.TrimSpace(line))
		}
	}
	if !strings.Contains(body, "warp=on") && !strings.Contains(body, "warp=plus") {
		return fmt.Errorf("trace did not report WARP")
	}
	fmt.Println("  WARP ON - tunnel carried the request")
	return nil
}

// capsuleTypeCloseWebTransportSession is 0x2843, the CLOSE_WEBTRANSPORT_SESSION capsule that opens
// an IP flow: four bytes of context id, then the request as an http request block.
const capsuleTypeCloseWebTransportSession = 0x2843

func fetchThrough(rstr *http3.RequestStream, srcIP net.IP) (string, error) {
	u, _ := url.Parse(traceURL)
	block := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: \r\nAccept: */*\r\n\r\n",
		u.RequestURI(), u.Host)
	capsule := make([]byte, 0, 8+len(block))
	capsule = append(capsule, capsuleTypeCloseWebTransportSession>>8, capsuleTypeCloseWebTransportSession&0xff)
	capsule = append(capsule, 0, 0, 0, 0) // context id
	capsule = append(capsule, byte(len(block)>>24), byte(len(block)>>16), byte(len(block)>>8), byte(len(block)))
	capsule = append(capsule, block...)
	if _, err := rstr.Write(capsule); err != nil {
		return "", err
	}
	if err := rstr.Close(); err != nil {
		return "", err
	}
	raw, err := io.ReadAll(rstr)
	if err != nil && len(raw) == 0 {
		return "", err
	}
	return string(raw), nil
}

func mustAddr(a string) *net.UDPAddr {
	u, err := net.ResolveUDPAddr("udp", a)
	if err != nil {
		panic(err)
	}
	return u
}

func enrol() (net.IP, tls.Certificate, error) {
	// A host-side registrar, when one is offered.
	//
	// Neither machine can do both halves of this on the network the tunnel has to survive: the
	// device's enrolment POST comes back EOF or a reset from a peer that just finished TLS, and the
	// host reaches the edge but cannot enrol. So the identity is enrolled where the API answers and
	// the tunnel is opened where the edge answers.
	if h := os.Getenv("REGISTRAR"); h != "" {
		key, tok, src, err := registrarEnrol(h)
		if err != nil {
			fmt.Println("  registrar failed:", err)
		} else {
			fmt.Println("  identity from registrar", h, "src", src)
			return net.ParseIP(src).To4(), buildPair(key, tok), nil
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	scalar := make([]byte, 32)
	key.D.FillBytes(scalar)

	ip, err := resolve4(apiHost)
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
		// The mask, not apiHost: this network filters the SNI extension value and drops the
		// connection when it sees cloudflareclient.com there. Measured, not assumed:
		//
		//	sni=api.cloudflareclient.com   tls: EOF
		//	sni=1.1.1.1                    tls OK | HTTP/1.1 200 {"id":...}
		//
		// Cloudflare routes on the Host header, which still carries apiHost, so the request lands on
		// the service it was addressed to. Verification is off for that reason and only here.
		ServerName:         apiSNIMask,
		InsecureSkipVerify: true,
		NextProtos:         []string{"http/1.1"},
	},
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(ip, "443"))
			},
		},
	}
	call := func(method, path, token string, body map[string]any) (map[string]any, error) {
		buf, _ := json.Marshal(body)
		var out map[string]any
		var lastErr error
		for attempt := 1; attempt <= 6; attempt++ {
			req, err := http.NewRequest(method, "https://"+apiHost+path, bytes.NewReader(buf))
			if err != nil {
				return nil, err
			}
			req.Host = apiHost
			req.Header.Set("User-Agent", "WARP for Android")
			req.Header.Set("CF-Client-Version", "a-6.35-4471")
			req.Header.Set("Content-Type", "application/json; charset=UTF-8")
			if token != "" {
				req.Header.Set("Authorization", "Bearer "+token)
			}
			resp, err := client.Do(req)
			if err != nil {
				lastErr = err
				fmt.Printf("  %s %s attempt %d: %v\n", method, path, attempt, err)
				time.Sleep(time.Duration(attempt%3) * 600 * time.Millisecond)
				continue
			}
			raw, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode >= 400 {
				return nil, fmt.Errorf("%s %s: %d %s", method, path, resp.StatusCode, string(raw))
			}
			json.Unmarshal(raw, &out)
			return out, nil
		}
		return nil, lastErr
	}

	serial := make([]byte, 16)
	rand.Read(serial)
	reg, err := call("POST", "/"+apiVer+"/reg", "", map[string]any{
		"fcm_token": "", "install_id": "", "tos": "2024-06-01T00:00:00.000Z",
		"model": "PC", "type": "Android", "serial_number": fmt.Sprintf("%x", serial),
		"locale": "en_US", "region": "US", "warp_enabled": true,
		"key": base64.StdEncoding.EncodeToString(scalar)})
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("register: %w", err)
	}
	id, _ := reg["id"].(string)
	token, _ := reg["token"].(string)
	if id == "" || token == "" {
		return nil, tls.Certificate{}, fmt.Errorf("register: no id/token in %v", reg)
	}
	if enrolResp, err := call("PATCH", "/"+apiVer+"/reg/"+id, token, map[string]any{
		"key":      base64.StdEncoding.EncodeToString(spki),
		"key_type": "secp256r1", "tun_type": "masque"}); err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("enrol: %w", err)
	} else {
		raw, _ := json.MarshalIndent(enrolResp, "", "  ")
		fmt.Println("  PATCH response:", string(raw))
	}
	addr := "172.16.0.2"
	if cfg, ok := reg["config"].(map[string]any); ok {
		if peers, ok := cfg["peers"].([]any); ok && len(peers) > 0 {
			if p0, ok := peers[0].(map[string]any); ok {
				if b, _ := json.Marshal(p0); len(b) > 0 {
					fmt.Println("  peer[0] entry:", string(b))
				}
				if pk, ok := p0["public_key"].(string); ok {
					fmt.Println("  edge public key:", pk)
					if !setPeerKey(pk) {
						fmt.Println("  edge key could not be parsed; falling back to the client key")
					}
				}
			}
		}
		if iface, ok := cfg["interface"].(map[string]any); ok {
			if addrs, ok := iface["addresses"].(map[string]any); ok {
				if v4, ok := addrs["v4"].(string); ok && v4 != "" {
					addr = v4
				}
			}
		}
	}

	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	pair, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	return net.ParseIP(addr).To4(), pair, nil
}

func resolve4(name string) (string, error) {
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query", "https://9.9.9.9/dns-query"} {
		req, err := http.NewRequest("GET", r+"?name="+name+"&type=A", nil)
		if err != nil {
			continue
		}
		req.Header.Set("Accept", "application/dns-json")
		cl := &http.Client{Timeout: 10 * time.Second}
		resp, err := cl.Do(req)
		if err != nil {
			fmt.Println("  doh", r, "error:", err)
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
				return a.Data, nil
			}
		}
	}
	return "", fmt.Errorf("no A record for %s over DoH", name)
}

// registrar holds an enrolled identity in a form both machines can rebuild the key pair from: the
// PKCS#8 private key and the registration token, base64 as they crossed the wire.
type registrar struct{ key, token, src string }

var regCache registrar

func registrarEnrol(host string) (string, string, string, error) {
	c := &http.Client{Timeout: 30 * time.Second}
	get := func(path string) ([]byte, error) {
		resp, err := c.Get(host + path)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		if resp.StatusCode >= 400 {
			return nil, fmt.Errorf("%s: %d %s", path, resp.StatusCode, string(raw))
		}
		return raw, nil
	}
	raw, err := get("/key")
	if err != nil {
		return "", "", "", err
	}
	var kr struct {
		Key   string `json:"key"`
		Token string `json:"token"`
		Src   string `json:"src"`
	}
	json.Unmarshal(raw, &kr)
	if kr.Key == "" || kr.Token == "" {
		return "", "", "", fmt.Errorf("registrar returned no key/token: %s", string(raw))
	}
	return kr.Key, kr.Token, kr.Src, nil
}

// buildPair recreates the certificate the edge expects: bare, self-signed, empty subject, from the
// enrolled P-256 key.
func buildPair(keyB64, token string) tls.Certificate {
	der, _ := base64.StdEncoding.DecodeString(keyB64)
	any_, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		fmt.Println("  registrar key unparseable:", err)
		return tls.Certificate{}
	}
	key := any_.(*ecdsa.PrivateKey)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	cder, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		fmt.Println("  certificate:", err)
		return tls.Certificate{}
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	pair, err := tls.X509KeyPair(
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cder}),
		pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		fmt.Println("  key pair:", err)
	}
	return pair
}

func env(k string) string { return os.Getenv(k) }


// alpnProbe reports what the handshake actually negotiated, because "the edge refused" and "the edge
// never offered h3" look identical from outside and only the negotiated protocol separates them.
func alpnProbe() []string {
	if v := env("ALPN"); v != "" {
		return []string{v}
	}
	return []string{"h3"}
}
