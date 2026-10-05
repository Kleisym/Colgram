// h2probe asks the WARP edge for MASQUE over HTTP/2 and reports what comes back, over TCP only.
//
// Why this exists. UDP to the edge is filtered on the device - measured, on every port the edge
// serves QUIC on, with the real QUIC stack: four to five seconds, no Retry, no alert. TCP to the
// same address and port connects in under a second. Cloudflare ships the transport officially as
// "h2-only", so the same tunnel that works over the relay can run over plain TCP with no UDP hop
// and no helper process at all.
//
// The frame it needs is DATAGRAM (type 0x33), and x/net/http2 has no API for it: no FrameDATAGRAM
// constant exists in v0.56.0. So the exchange is written by hand over a raw connection, which is
// also the only way to see exactly where it stops - a wrong answer here would otherwise be blamed
// on the network again.
package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"fmt"
	"math/big"
	"net"
	"os"
	"strings"
	"time"
)

const (
	edgeIP  = "162.159.198.2"
	edgeSNI = "consumer-masque.cloudflareclient.com"
)

func main() {
	port := "443"
	if len(os.Args) > 1 {
		port = os.Args[1]
	}
	bind := ""
	if len(os.Args) > 2 {
		bind = os.Args[2]
	}
	// Which ALPN to offer, and what to do after the preface. Both default to what the tunnel needs,
	// and both exist because a wrong guess here is indistinguishable from a block: an ALPN the server
	// does not pick still completes TLS, so nothing fails until the first frame - and then the
	// failure names the protocol layer instead of the thing that was actually wrong.
	alpn := "h2"
	if v := os.Getenv("H2_ALPN"); v != "" {
		alpn = v
	}
	mode := "settings"
	if v := os.Getenv("H2_MODE"); v != "" {
		mode = v
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		die("key: %v", err)
	}
	cert := bareCert(key)

	d := net.Dialer{Timeout: 10 * time.Second}
	// Bind the source address, because it decides the route and the egress.
	//
	// Left on the default, this host's traffic to 162.159.198.2 leaves through a VPN tunnel that
	// holds 128.0.0.0/1 at metric 0, and the edge refuses the TLS handshake from there. The same
	// handshake from the LAN address is accepted. That is not a theory - it is the same egress
	// difference this project has measured on QUIC, and it is why the bridge binds explicitly.
	//
	// A dialer with a LocalAddr is what actually applies it; building the address and then dialing
	// without it does nothing at all, which is what this did until it was caught.
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			die("bind is not an IPv4 address: %s", bind)
		}
		// A TCPAddr, not a UDPAddr. The dialer's network is "tcp", and a UDPAddr here is rejected
		// outright with "mismatched local address type" - which is at least an honest error, but it
		// means the binding has to be spelled for the network actually in use.
		d.LocalAddr = &net.TCPAddr{IP: ip}
	}
	raw, err := d.Dial("tcp", net.JoinHostPort(edgeIP, port))
	if err != nil {
		die("tcp: %v", err)
	}
	raw.SetDeadline(time.Now().Add(20 * time.Second))
	defer raw.Close()
	fmt.Printf("tcp %s:443 connected from %s\n", edgeIP, raw.LocalAddr())

	// ALPN h2 only. The edge will not negotiate anything else on this port, and saying so is the
	// point: if the server picked a different protocol, the frames below would be meaningless.
	cfg := &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         edgeSNI,
		NextProtos:         strings.Split(alpn, ","),
		Certificates:       []tls.Certificate{cert},
		MinVersion:         tls.VersionTLS13,
	}
	// Whether to present the client certificate at all.
	//
	// This is the one variable that separates "the edge refuses our identity" from "the edge
	// refuses everything on this port", and nothing else does. Both look identical from outside:
	// TLS completes, then the connection dies with access denied on the first byte after the
	// preface - before any frame is read, so there is no status and no frame type to point at.
	if os.Getenv("H2_NO_CERT") != "" {
		cfg.Certificates = nil
		fmt.Println("no client certificate will be presented")
	}
	conn := tls.Client(raw, cfg)
	if err := conn.Handshake(); err != nil {
		die("tls handshake: %v", err)
	}
	fmt.Printf("tls ok: version=0x%04x alpn=%q\n", conn.ConnectionState().Version,
		conn.ConnectionState().NegotiatedProtocol)
	if conn.ConnectionState().NegotiatedProtocol != "h2" {
		fmt.Printf("the server did not select h2 from %q - anything sent now is guesswork\n", alpn)
	}

	// The HTTP/2 connection preface, then the client's SETTINGS.
	if _, err := conn.Write([]byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")); err != nil {
		die("preface: %v", err)
	}
	settings := settingsFrame(1, 0x3, 0x100)
	if _, err := conn.Write(settings); err != nil {
		die("settings: %v", err)
	}
	// SETTINGS_ENABLE_CONNECT_PROTOCOL is 0x8. Without it the edge answers an extended CONNECT
	// with PROTOCOL_VIOLATION, which reads like a protocol bug rather than a missing setting.
	if _, err := conn.Write(settingsFrame(2, 0x8, 1)); err != nil {
		die("settings connect-protocol: %v", err)
	}
	if _, err := conn.Write(pingFrame(1, 0x5c5c5c5c)); err != nil {
		die("ping: %v", err)
	}
	fmt.Println("sent preface, settings (MAX_CONCURRENT=256, MAX_FRAME=4096), " +
		"ENABLE_CONNECT_PROTOCOL, ping")

	// Read whatever the server says before asking for the tunnel: its SETTINGS tell us whether it
	// accepted a DATAGRAM capability at all, and that single fact decides whether this path is real.
	if mode == "get" {
		// A plain GET, to separate "the connection is unusable" from "my CONNECT is wrong".
		block := hpackGET()
		if _, err := conn.Write(headFrame(3, 0x4|0x1, block)); err != nil {
			die("get headers: %v", err)
		}
		fmt.Println("sent GET / with :method GET, :scheme https, :path /")
	}
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		raw, err := readFrame(conn)
		if err != nil {
			fmt.Println("server went quiet:", err)
			break
		}
		if raw[0] == 0x4 {
			reportSettings(raw)
			if raw[6] == 0x6 {
				fmt.Println("server ACKED our ping - the connection is alive")
			}
		}
		if raw[0] == 0x7 {
			if len(raw) < 17 {
				fmt.Printf("GOAWAY with a short payload: %x\n", raw[9:])
			} else {
				last := be32(raw, 9)
				code := be32(raw, 13)
				fmt.Printf("GOAWAY last-stream-id=%d error=%d debug=%q\n",
					last, code, string(raw[17:]))
			}
			break
		}
		if raw[0] == 0x1 {
			fmt.Printf("HEADERS response: %x\n", raw[9:])
		}
		if raw[0] == 0x3 {
			fmt.Printf("RST_STREAM error=%d\n", be32(raw, 13))
		}
	}
}

func be32(b []byte, i int) uint32 {
	return uint32(b[i])<<24 | uint32(b[i+1])<<16 | uint32(b[i+2])<<8 | uint32(b[i+3])
}

// headFrame builds one HEADERS frame carrying a stream id.
//
// The stream id is 31 bits inside a 32-bit field, so the first byte is written as zero rather than
// as the top of the value. Getting that byte wrong puts the stream id somewhere else entirely and the
// server has no idea which stream the request belongs to - which is what a first attempt here did.
func headFrame(stream uint32, flags byte, block []byte) []byte {
	out := make([]byte, 9, 9+len(block))
	out[0] = byte(len(block) >> 16)
	out[1] = byte(len(block) >> 8)
	out[2] = byte(len(block))
	out[3] = 0x1
	out[4] = flags
	putU32(out[5:9], stream)
	return append(out, block...)
}

func putU32(b []byte, v uint32) {
	b[0] = byte(v >> 24)
	b[1] = byte(v >> 16)
	b[2] = byte(v >> 8)
	b[3] = byte(v)
}

// hpackGET builds a GET request as literals, the same shape h2tunnel uses for CONNECT, so a
// failure here cannot be blamed on the encoder being different between the two.
func hpackGET() []byte {
	fields := [][2]string{
		{":method", "GET"},
		{":scheme", "https"},
		{":authority", edgeSNI},
		{":path", "/"},
	}
	var b bytes.Buffer
	for _, f := range fields {
		b.WriteByte(0x00)
		b.WriteByte(byte(len(f[0])))
		b.WriteString(f[0])
		b.WriteByte(byte(len(f[1])))
		b.WriteString(f[1])
	}
	return b.Bytes()
}

// bareCert is the certificate the edge expects: empty subject, no extensions, self-signed.
// Anything else is refused, and the refusal looks like an unexplained handshake reset.
func bareCert(key *ecdsa.PrivateKey) tls.Certificate {
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		die("certificate: %v", err)
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	certPEM := pemEncode("CERTIFICATE", der)
	keyPEM := pemEncode("PRIVATE KEY", keyDER)
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		die("keypair: %v", err)
	}
	return pair
}

func settingsFrame(id uint32, key, value uint16) []byte {
	b := make([]byte, 0, 15)
	b = append(b, 0x0, 0x4)
	b = appendU32(b, 0) // length, patched below
	b = appendU32(b, id)
	b = append(b, byte(key>>8), byte(key))
	b = append(b, byte(value>>8), byte(value))
	binary.BigEndian.PutUint32(b[0:4], uint32(len(b)-9))
	return b
}

func pingFrame(id uint32, opaque uint32) []byte {
	b := make([]byte, 0, 17)
	b = append(b, 0x0, 0x6)
	b = appendU32(b, 8)
	b = appendU32(b, id)
	b = appendU32(b, opaque)
	return b
}

func appendU32(b []byte, v uint32) []byte {
	return append(b, byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
}

// readFrame reads one HTTP/2 frame off the wire.
func readFrame(conn net.Conn) ([]byte, error) {
	head := make([]byte, 9)
	if _, err := readFull(conn, head); err != nil {
		return nil, err
	}
	length := int(head[0])<<16 | int(head[1])<<8 | int(head[2])
	if length > 1<<20 {
		return nil, fmt.Errorf("frame length %d is implausible", length)
	}
	body := make([]byte, length)
	if length > 0 {
		if _, err := readFull(conn, body); err != nil {
			return nil, err
		}
	}
	return append(head, body...), nil
}

func readFull(conn net.Conn, b []byte) (int, error) {
	read := 0
	for read < len(b) {
		n, err := conn.Read(b[read:])
		if err != nil {
			return read, err
		}
		read += n
	}
	return read, nil
}

// reportSettings prints the server's settings, naming each one, because the datagram capability is
// the whole question and a raw number would not say which setting it is.
var settingNames = map[uint16]string{
	0x1: "HEADER_TABLE_SIZE",
	0x2: "ENABLE_PUSH",
	0x3: "MAX_CONCURRENT_STREAMS",
	0x4: "INITIAL_WINDOW_SIZE",
	0x5: "MAX_FRAME_SIZE",
	0x6: "MAX_HEADER_LIST_SIZE",
	0x8: "ENABLE_CONNECT_PROTOCOL",
	0x33: "H2_DATAGRAM (RFC 9297)",
}

func reportSettings(frame []byte) {
	payload := frame[9:]
	if len(payload) < 6 {
		fmt.Println("SETTINGS ack with no settings in it")
		return
	}
	for i := 6; i+6 <= len(payload); i += 6 {
		key := binary.BigEndian.Uint16(payload[i : i+2])
		value := binary.BigEndian.Uint32(payload[i+2 : i+6])
		name, ok := settingNames[key]
		if !ok {
			name = "unknown"
		}
		fmt.Printf("server setting 0x%02x %-28s = %d\n", key, name, value)
	}
}

func die(format string, args ...any) {
	fmt.Printf(format+"\n", args...)
	os.Exit(1)
}

// pemEncode wraps DER in PEM without pulling in encoding/pem for one call site.
func pemEncode(kind string, der []byte) []byte {
	var out bytes.Buffer
	out.WriteString("-----BEGIN " + kind + "-----\n")
	enc := []byte("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
	for i := 0; i < len(der); i += 3 {
		var chunk [3]byte
		n := copy(chunk[:], der[i:])
		out.WriteByte(enc[chunk[0]>>2])
		out.WriteByte(enc[(chunk[0]&0x03)<<4|chunk[1]>>4])
		if n > 1 {
			out.WriteByte(enc[(chunk[1]&0x0f)<<2|chunk[2]>>6])
		} else {
			out.WriteByte('=')
		}
		if n > 2 {
			out.WriteByte(enc[chunk[2]&0x3f])
		} else {
			out.WriteByte('=')
		}
		out.WriteByte('\n')
	}
	out.WriteString("-----END " + kind + "-----\n")
	return out.Bytes()
}
