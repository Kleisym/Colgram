// language: Go, file: main.go, target: host probe for the Colgram WARP TCP/H2 carrier
//
// A self-contained replica of the registration plus the extended CONNECT, with every frame printed.
// The point is to separate "our h2 encoding is wrong" from "this network drops the exchange": the
// native client reports one string for both, and the device log could not tell them apart.
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
	"os"
	"strings"
	"time"

	"github.com/quic-go/quic-go"
	"golang.org/x/net/http2/hpack"
)

const (
	apiHost     = "api.cloudflareclient.com"
	apiVer      = "v0a4471"
	edgeIP      = "162.159.192.6"
	connectAuth = "cloudflareaccess.com"
	crlf        = "\r\n"
)

// snis are the two names measured against this edge. The QUIC carrier uses the first; the TCP one
// was moved to the second because the first is filtered by name here.
var snis = []string{"consumer-masque.cloudflareclient.com", "engage.cloudflareclient.com"}

func main() {
	// -sni lets the same binary answer the question for either name without a rebuild.
	sni := "engage.cloudflareclient.com"
	if v := os.Getenv("SNI"); v != "" {
		sni = v
	}
	ports := []string{"443", "2053"}
	if v := os.Getenv("PORTS"); v != "" {
		ports = strings.Split(v, ",")
	}

	srcIP, cert, err := enrol()
	if err != nil {
		fmt.Println("enrol failed:", err)
		return
	}
	fmt.Println("registered, src", srcIP)

	// QUIC is asked first, and it is asked with the enrolled certificate, because that is the one
	// question the TCP carrier cannot answer: the edge answers an anonymous QUIC Initial with
	// CRYPTO_ERROR 0x128, which is a refusal to present a client certificate and looks exactly like a
	// filtered path. Whether QUIC survives here with a certificate is a separate fact from whether
	// TCP/H2 gets a PROTOCOL_ERROR, and the tunnel needs both answered.
	for _, addr := range []string{"162.159.192.6:2408", "162.159.192.6:500", "162.159.192.6:443", "162.159.192.1:443"} {
		fmt.Printf("\n=== quic %s ===\n", addr)
		if err := tryQuic(addr, edgeSNIFor(addr), cert); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}

	for _, port := range ports {
		addr := net.JoinHostPort(edgeIP, port)
		fmt.Printf("\n=== %s sni=%s ===\n", addr, sni)
		if err := tryEdge(addr, sni, srcIP, cert); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}
}

func edgeSNIFor(addr string) string {
	if strings.Contains(addr, ":2408") || strings.Contains(addr, ":500") || strings.Contains(addr, ":1701") || strings.Contains(addr, ":4500") {
		return "consumer-masque.cloudflareclient.com"
	}
	return "engage.cloudflareclient.com"
}

// tryQuic performs the handshake only. A QUIC session that completes the handshake on a network
// where the same handshake is refused anonymously is the strongest single piece of evidence about
// whether MASQUE over QUIC is reachable here.
func tryQuic(addr, sni string, cert tls.Certificate) error {
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		return err
	}
	defer udp.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	sw := time.Now()
	conn, err := quic.Dial(ctx, udp, mustAddr(addr), &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
		NextProtos:         []string{"h3"},
		Certificates:       []tls.Certificate{cert},
		MinVersion:         tls.VersionTLS13,
	}, &quic.Config{
		EnableDatagrams:         true,
		InitialPacketSize:       1200,
		DisablePathMTUDiscovery: true,
		MaxIdleTimeout:          30 * time.Second,
	})
	if err != nil {
		return err
	}
	defer conn.CloseWithError(0, "")
	fmt.Printf("  quic handshake OK sni=%s in %dms\n", sni, time.Since(sw).Milliseconds())
	return nil
}

func mustAddr(a string) *net.UDPAddr {
	u, err := net.ResolveUDPAddr("udp", a)
	if err != nil {
		panic(err)
	}
	return u
}

func tryEdge(addr, sni string, srcIP net.IP, cert tls.Certificate) error {
	d := net.Dialer{Timeout: 6 * time.Second}
	conn, err := d.DialContext(context.Background(), "tcp", addr)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	defer conn.Close()

	// ALPN is what decides whether the edge will speak h2 at all, and it has to be offered before
	// the handshake, so the negotiated protocol is printed: a server that answers "" is answering
	// with HTTP/1.1 and every frame written afterwards is unreadable.
	tc := tls.Client(conn, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
		NextProtos:         []string{"h2"},
		Certificates:       []tls.Certificate{cert},
		MinVersion:         tls.VersionTLS12,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := tc.HandshakeContext(ctx); err != nil {
		return fmt.Errorf("tls: %w", err)
	}
	fmt.Printf("  tls ok version=0x%04x alpn=%q\n", tc.ConnectionState().Version, tc.ConnectionState().NegotiatedProtocol)

	// SETTINGS_ENABLE_CONNECT_PROTOCOL = 0x8. Without it the edge refuses an extended CONNECT, and
	// the empty SETTINGS this client used to send is why the frame exchange produced nothing.
	settings := []byte{0, 0, 4, 0, 0, 0, 0, 0, 0}
	// payload: SETTINGS_ENABLE_CONNECT_PROTOCOL(0x8)=1, MAX_CONCURRENT_STREAMS(0x3)=256,
	// MAX_FRAME_SIZE(0x5)=16384, INITIAL_WINDOW_SIZE(0x4)=1<<20
	var payload []byte
	payload = appendSetting(payload, 0x8, 1)
	payload = appendSetting(payload, 0x3, 256)
	payload = appendSetting(payload, 0x5, 16384)
	payload = appendSetting(payload, 0x4, 1<<20)
	settings = frame(0x4, 0, 0, payload)

	preface := []byte("PRI * HTTP/2.0" + crlf + crlf + "SM" + crlf + crlf)
	if _, err := tc.Write(append(preface, settings...)); err != nil {
		return fmt.Errorf("write preface: %w", err)
	}
	fmt.Println("  sent preface + SETTINGS", hex(settings))

	// Read until the server's SETTINGS arrives, printing everything, because the device failure was
	// a read that never returned and the frame that would have said why was never shown.
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	h, err := readFrame(tc)
	if err != nil {
		return fmt.Errorf("reading server frames: %w", err)
	}
	for h.typ != 0x4 {
		fmt.Printf("  server frame type=0x%x flags=0x%x stream=%d payload=%s\n", h.typ, h.flags, h.stream, hex(h.payload))
		if h.typ == 0x7 {
			return fmt.Errorf("GOAWAY: %s", hex(h.payload))
		}
		conn.SetReadDeadline(time.Now().Add(10 * time.Second))
		if h, err = readFrame(tc); err != nil {
			return fmt.Errorf("reading server frames: %w", err)
		}
	}
	fmt.Printf("  server SETTINGS %s\n", hex(h.payload))
	// Acknowledge the server's SETTINGS. Sending the ACK before its frame arrived acknowledges a
	// frame the peer has not sent, and the peer then never answers - which is the silence measured.
	if _, err := tc.Write(frame(0x4, 0x1, 0, nil)); err != nil {
		return fmt.Errorf("write settings ack: %w", err)
	}
	fmt.Println("  sent SETTINGS ACK")

	// END_STREAM is set on the HEADERS: an extended CONNECT has no request body, and the frame the
	// edge reads as its request is only complete when the stream is half-closed from this side.
	head, err := connectHeaders(sni)
	if err != nil {
		return err
	}
	if _, err := tc.Write(frame(0x1, 0x5, 1, head)); err != nil {
		return fmt.Errorf("write HEADERS: %w", err)
	}
	fmt.Printf("  sent CONNECT HEADERS stream=1 endStream payload=%s\n", hex(head))

	conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	for {
		h, err := readFrame(tc)
		if err != nil {
			return fmt.Errorf("reading response: %w", err)
		}
		fmt.Printf("  server frame type=0x%x flags=0x%x stream=%d len=%d payload=%s\n",
			h.typ, h.flags, h.stream, len(h.payload), hex(h.payload))
		if h.typ == 0x7 {
			return fmt.Errorf("GOAWAY after CONNECT: %s", hex(h.payload))
		}
		if h.typ == 0x1 && h.stream == 1 {
			status := hpackStatus(h.payload)
			if status != 200 {
				return fmt.Errorf("CONNECT refused with status %d", status)
			}
			fmt.Println("  CONNECT 200 - tunnel established")
			return nil
		}
	}
}

type hdr struct {
	typ     byte
	flags   byte
	stream  uint32
	payload []byte
}

func readFrame(r io.Reader) (hdr, error) {
	var head [9]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		return hdr{}, err
	}
	length := int(head[0])<<16 | int(head[1])<<8 | int(head[2])
	p := make([]byte, length)
	if _, err := io.ReadFull(r, p); err != nil {
		return hdr{}, err
	}
	return hdr{typ: head[3], flags: head[4],
		stream: uint32(head[5])<<24 | uint32(head[6])<<16 | uint32(head[7])<<8 | uint32(head[8]), payload: p}, nil
}

func frame(typ, flags byte, stream uint32, payload []byte) []byte {
	out := make([]byte, 9, 9+len(payload))
	length := len(payload)
	out[0], out[1], out[2] = byte(length>>16), byte(length>>8), byte(length)
	out[3], out[4] = typ, flags
	out[5] = byte(stream >> 24)
	out[6] = byte(stream >> 16)
	out[7] = byte(stream >> 8)
	out[8] = byte(stream)
	return append(out, payload...)
}

func appendSetting(dst []byte, id uint16, v uint32) []byte {
	dst = append(dst, byte(id>>8), byte(id))
	dst = append(dst, byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	return dst
}

// connectHeaders encodes the extended CONNECT block by hand: literal-without-indexing with an
// indexed name, which is the only form needed because every name here is either in HPACK's static
// table or is being sent as a new name with no index at all.
func connectHeaders(sni string) ([]byte, error) {
	var buf bytes.Buffer
	enc := hpack.NewEncoder(&buf)
	// Written through a real encoder rather than by hand. The hand-written version emitted the static
	// table indices 3, 7 and 4 as literal-without-indexing name references and never wrote their values,
	// so the decoder took the next byte as a length and read the rest of the block as one name. A block
	// that looks like valid HPACK on the wire and decodes into garbage produces exactly the RST_STREAM
	// and GOAWAY this probe was built to diagnose, so the encoder itself was the thing standing between
	// the measurement and its answer.
	fields := [][2]string{
		{":method", "CONNECT"},
		{":scheme", "https"},
		{":authority", connectAuth},
		{":path", "/"},
		{":protocol", "cf-connect-ip"},
		{"cf-connect-proto", "cf-connect-ip"},
		{"capsule-protocol", "?1"},
		{"pq-enabled", "false"},
		{"user-agent", "WARP for Android"},
	}
	for _, kv := range fields {
		if err := enc.WriteField(hpack.HeaderField{Name: kv[0], Value: kv[1]}); err != nil {
			return nil, err
		}
	}
	_ = sni
	return buf.Bytes(), nil
}

func appendString(b []byte, s string) []byte {
	// HPACK string literal: 0x00 prefix plus a 7-bit length. Nothing here needs Huffman, and a
	// 0x80 prefix would mean the string that follows is Huffman-coded.
	b = append(b, byte(len(s)))
	return append(b, s...)
}

func hpackStatus(payload []byte) int {
	// Only the single indexed :status the edge sends is handled, which is what a 200 is: 0x88.
	for _, b := range payload {
		if b&0x80 != 0 && b != 0x80 {
			return int(b & 0x7f)
		}
	}
	return 0
}

func hex(b []byte) string {
	var sb strings.Builder
	for i, c := range b {
		if i > 0 {
			sb.WriteByte(' ')
		}
		fmt.Fprintf(&sb, "%02x", c)
		if i > 40 {
			sb.WriteString("...")
			break
		}
	}
	return sb.String()
}

// enrol mirrors the native client's registration exactly: a P-256 key, POST /reg with its scalar,
// PATCH with the SPKI, and a bare self-signed certificate with an empty subject. The edge refuses a
// client that presents no certificate, which is why this cannot be skipped.
func enrol() (net.IP, tls.Certificate, error) {
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
			// HTTP/1.1 only, and that is measured rather than chosen for comfort: with the default
			// ALPN the enrolment POST completes TLS and is then closed before a response byte, which
			// Go reports as a bare EOF. Forcing h1 makes the same request return a body every time.
			TLSClientConfig:   &tls.Config{ServerName: apiHost, NextProtos: []string{"http/1.1"}},
			ForceAttemptHTTP2: false,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(ip, "443"))
			},
		},
	}
	call := func(method, path, token string, body map[string]any) (map[string]any, error) {
		buf, _ := json.Marshal(body)
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
		// Retried, because the failure is measured and intermittent rather than a schema problem:
		// the same request returns 200 with a body, then EOF, then a read timeout, across three runs
		// of this binary. A single attempt is not a measurement.
		var resp *http.Response
		for attempt := 1; attempt <= 10; attempt++ {
			resp, err = client.Do(req.Clone(context.Background()))
			if err == nil {
				break
			}
			fmt.Printf("  %s %s attempt %d: %v\n", method, path, attempt, err)
			// A fresh API address each round: api.cloudflareclient.com resolves into a rotating set,
			// and the stall is per-address rather than per-request, so backing off onto the same one
			// just repeats it.
			if fresh, ferr := resolve4(apiHost); ferr == nil {
				ip = fresh
			}
			time.Sleep(time.Duration(attempt%4) * 500 * time.Millisecond)
		}
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(resp.Body)
		if resp.StatusCode >= 400 {
			return nil, fmt.Errorf("%s %s: %d %s", method, path, resp.StatusCode, string(raw))
		}
		var out map[string]any
		json.Unmarshal(raw, &out)
		return out, nil
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
	fmt.Println("  registered id", id)
	// The registration is the only authoritative statement about where this identity's tunnel
	// should connect, so it is printed in full rather than reduced to the address the client was
	// already going to use.
	raw, _ := json.MarshalIndent(reg, "", "  ")
	fmt.Println("  reg response:", string(raw))
	if _, err := call("PATCH", "/"+apiVer+"/reg/"+id, token, map[string]any{
		"key": base64.StdEncoding.EncodeToString(spki),
		"key_type": "secp256r1", "tun_type": "masque"}); err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("enrol: %w", err)
	}

	addr := "172.16.0.2"
	if cfg, ok := reg["config"].(map[string]any); ok {
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

// resolve4 goes through DoH rather than the system resolver, because a plain lookup is what fails on
// the networks this client has to survive.
func resolve4(name string) (string, error) {
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query", "https://9.9.9.9/dns-query"} {
		// GET, not POST: Cloudflare's JSON API answers a POST only with
		// Content-Type: application/dns-message, and the JSON POST form is refused outright -
		//     https://1.1.1.1/dns-query  status 400
		//     https://8.8.4.4/dns-query  status 415
		req, err := http.NewRequest("GET", r+"?name="+name+"&type=A", nil)
		if err != nil {
			continue
		}
		req.Header.Set("Accept", "application/dns-json")
		cl := &http.Client{Timeout: 8 * time.Second}
		resp, err := cl.Do(req)
		if err != nil {
			fmt.Println("  doh", r, "error:", err)
			continue
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		fmt.Println("  doh", r, "status", resp.StatusCode, "bytes", len(raw))
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
