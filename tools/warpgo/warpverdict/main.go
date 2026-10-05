// warpverdict measures Cloudflare WARP the only way that settles it: it opens a MASQUE tunnel,
// runs a real TLS client inside it, and reads the warp= line out of Cloudflare's own trace.
//
// This is the Go port of the Python measurement that first produced warp=on. It exists because the
// device leg needs a client that runs on the phone: the device's own reference client never
// completed a handshake that this code completes against the same edge, from the same network.
//
// Nothing here touches host networking. The UDP socket is ordinary and everything it carries goes
// to the MASQUE edge on 443.
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
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

const (
	apiHost = "api.cloudflareclient.com"
	apiVer  = "v0a4471"
	edgeIP  = "162.159.198.2"
	edgeSNI = "consumer-masque.cloudflareclient.com"

	traceHost = "connectivity.cloudflareclient.com"
	tracePath = "/cdn-cgi/trace"
)

var crlf = string([]byte{13, 10})

// maxDatagramPayload leaves room for the IP and TCP headers inside one Connect-IP capsule. The
// edge's own packets come back at 1200 bytes, so a capsule is sized to fit inside that.
const maxDatagramPayload = 1100

// tunnelConn is a net.Conn whose bytes travel as Connect-IP capsules inside the MASQUE stream.
// Everything above it - the TLS client, the HTTP request - is an ordinary library.
type tunnelConn struct {
	tun     *tunnel
	srcIP   net.IP
	dstIP   net.IP
	srcPort uint16
	dstPort uint16
	seq     uint32
	ack     uint32

	mu     sync.Mutex
	cond   *sync.Cond
	inbuf  []byte
	eof    bool
	synned bool
	closed bool
	sent   int
	recv   int
	attempts int
}

func newTunnelConn(tun *tunnel, srcIP, dstIP net.IP, srcPort, dstPort uint16) *tunnelConn {
	c := &tunnelConn{tun: tun, srcIP: srcIP, dstIP: dstIP, srcPort: srcPort,
		dstPort: dstPort, seq: 1}
	c.cond = sync.NewCond(&c.mu)
	return c
}

func checksum(b []byte) uint16 {
	if len(b)%2 == 1 {
		b = append(b, 0)
	}
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(b[i])<<8 | uint32(b[i+1])
	}
	for sum>>16 != 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}

func ipv4Packet(src, dst net.IP, payload []byte) []byte {
	hdr := make([]byte, 20)
	// Version 4 in the high nibble, header length 5 words in the low one.
	hdr[0] = 0x45
	binary.BigEndian.PutUint16(hdr[2:4], uint16(20+len(payload)))
	binary.BigEndian.PutUint16(hdr[4:6], 0x4321)
	hdr[8] = 64
	hdr[9] = 6
	copy(hdr[12:16], src.To4())
	copy(hdr[16:20], dst.To4())
	binary.BigEndian.PutUint16(hdr[10:12], checksum(hdr))
	return append(hdr, payload...)
}

// emit builds a TCP segment with a real checksum. The edge forwards real TCP, so a wrong checksum
// here is a connection that is silently dropped.
func (c *tunnelConn) emit(payload []byte, flags uint16) {
	pseudo := make([]byte, 0, 12+len(payload))
	pseudo = append(pseudo, c.srcIP.To4()...)
	pseudo = append(pseudo, c.dstIP.To4()...)
	pseudo = append(pseudo, 0, 6)
	length := make([]byte, 2)
	binary.BigEndian.PutUint16(length, uint16(20+len(payload)))
	pseudo = append(pseudo, length...)

	seg := make([]byte, 20, 20+len(payload))
	binary.BigEndian.PutUint16(seg[0:2], c.srcPort)
	binary.BigEndian.PutUint16(seg[2:4], c.dstPort)
	binary.BigEndian.PutUint32(seg[4:8], c.seq)
	binary.BigEndian.PutUint32(seg[8:12], c.ack)
	// Data offset lives in the high nibble of the first byte, the flags in the next nine bits.
	seg[12] = 5<<4 | byte(flags>>8)
	seg[13] = byte(flags)
	binary.BigEndian.PutUint16(seg[14:16], 64240)
	seg = append(seg, payload...)
	binary.BigEndian.PutUint16(seg[16:18], checksum(append(pseudo, seg...)))

	c.sent++
	c.tun.sendIP(ipv4Packet(c.srcIP, c.dstIP, seg))
}

func (c *tunnelConn) feed(ip []byte) {
	if len(ip) < 40 {
		return
	}
	tcp := ip[20:]
	seq := binary.BigEndian.Uint32(tcp[4:8])
	offFlags := binary.BigEndian.Uint16(tcp[12:14])
	flags := offFlags & 0x1FF
	offset := int(offFlags>>12) * 4
	var payload []byte
	if offset >= 20 && offset <= len(tcp) {
		payload = tcp[offset:]
	}

	c.mu.Lock()
	c.recv++

	if flags&0x02 != 0 {
		if flags&0x10 != 0 {
			if !c.synned {
				// First handshake: the far side's SYN is acknowledged, and the flow becomes usable.
				c.ack = seq + 1
				c.emit(nil, 0x10)
				c.synned = true
				c.cond.Broadcast()
			} else {
				// A second SYN|ACK after the flow is up is answered with a bare ACK carrying our
				// current sequence number. Answering it with another SYN, or with an ACK whose
				// sequence is stale, is what makes the edge reply RST and tear the session down.
				c.ack = seq + 1
				c.emit(nil, 0x10)
			}
		}
		c.mu.Unlock()
		return
	}
	if len(payload) > 0 {
		c.ack = seq + uint32(len(payload))
		c.inbuf = append(c.inbuf, payload...)
	}
	// RST must not be acknowledged. Replying to it is what made the edge re-open the flow with a
	// fresh SYN, which in turn tore down the session mid-handshake.
	if flags&0x04 != 0 {
		c.eof = true
		c.cond.Broadcast()
		c.mu.Unlock()
		return
	}
	if flags&0x01 != 0 {
		c.ack = seq + uint32(len(payload)) + 1
		c.emit(nil, 0x11)
		c.eof = true
		c.cond.Broadcast()
		c.mu.Unlock()
		return
	}
	c.emit(nil, 0x10)
	c.cond.Broadcast()
	c.mu.Unlock()
}

func (c *tunnelConn) open() error {
	// A SYN retransmission must repeat the original sequence number. Incrementing between sends
	// makes the far side see two different connections and answer with RST.
	synSeq := c.seq
	c.emit(nil, 0x02)

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		ok := c.synned
		eof := c.eof
		c.mu.Unlock()
		if ok {
			return nil
		}
		if eof {
			break
		}
		// The edge acknowledges the QUIC packet carrying the SYN but does not always answer the
		// flow itself, so the SYN is retransmitted on a timer, as TCP would.
		if c.attempts < 4 {
			c.attempts++
			c.seq = synSeq
			c.emit(nil, 0x02)
			c.seq = synSeq + 1
		}
		time.Sleep(50 * time.Millisecond)
	}
	return fmt.Errorf("tcp handshake through the tunnel did not complete")
}

func (c *tunnelConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for len(c.inbuf) == 0 && !c.eof {
		c.cond.Wait()
	}
	if len(c.inbuf) == 0 && c.eof {
		return 0, io.EOF
	}
	n := copy(b, c.inbuf)
	c.inbuf = c.inbuf[n:]
	return n, nil
}

func (c *tunnelConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, net.ErrClosed
	}
	c.mu.Unlock()
	// A QUIC datagram carries one capsule and has a hard size limit. A TLS ClientHello does not fit
	// in one, so the write is split across datagrams and the far side reassembles it as a TCP
	// stream. Segmentation here is what a TUN device does.
	total := 0
	for len(b) > 0 {
		n := len(b)
		if n > maxDatagramPayload {
			n = maxDatagramPayload
		}
		c.emit(b[:n], 0x18)
		c.seq += uint32(n)
		b = b[n:]
		total += n
	}
	return total, nil
}

func (c *tunnelConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.mu.Unlock()
	c.emit(nil, 0x11)
	c.seq++
	return nil
}

func (c *tunnelConn) LocalAddr() net.Addr  { return &net.TCPAddr{IP: c.srcIP, Port: int(c.srcPort)} }
func (c *tunnelConn) RemoteAddr() net.Addr { return &net.TCPAddr{IP: c.dstIP, Port: int(c.dstPort)} }
func (c *tunnelConn) SetDeadline(t time.Time) error      { return nil }
func (c *tunnelConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *tunnelConn) SetWriteDeadline(t time.Time) error { return nil }

type tunnel struct {
	conn   *quic.Conn
	h3     *http3.ClientConn
	stream *http3.RequestStream
	srcIP  net.IP
	peers  []*tunnelConn
	sentCapsules int
}

func (t *tunnel) sendIP(raw []byte) {
	if t.h3 == nil || t.stream == nil {
		return
	}
	// Connect-IP as this edge speaks it: a single 0x00 type byte followed by the whole IP packet.
	// A generalised capsule carrying a context id and an explicit length is dropped here. That is
	// measured, not assumed: the shorter form is the one that returns traffic.
	if len(raw) < 20 || raw[0]>>4 != 4 {
		return
	}
	capsule := append([]byte{0x00}, raw...)
	if err := t.stream.SendDatagram(capsule); err != nil {
		fmt.Println("send datagram failed:", err)
		return
	}
	t.sentCapsules++
}

func appendVarint(b []byte, v uint64) []byte {
	switch {
	case v < 1<<6:
		return append(b, byte(v))
	case v < 1<<14:
		return append(b, byte(v|0x4000>>8), byte(v))
	case v < 1<<30:
		return append(b, byte(v|0x80000000>>24), byte(v>>16), byte(v>>8), byte(v))
	default:
		return append(b, byte(v|0xc000000000000000>>56), byte(v>>48), byte(v>>40),
			byte(v>>32), byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	}
}

func (t *tunnel) dispatch(ip []byte) {
	if len(ip) < 40 || ip[9] != 6 {
		return
	}
	// Inbound packets carry the far side's port as source and ours as destination.
	sport := binary.BigEndian.Uint16(ip[20:22])
	dport := binary.BigEndian.Uint16(ip[22:24])
	for _, p := range t.peers {
		if p.dstPort == sport && p.srcPort == dport {
			p.feed(ip)
		}
	}
}

func randomID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

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

	apiIP, err := resolve4(apiHost)
	if err != nil {
		return nil, tls.Certificate{}, err
	}

	// The address is pinned from DoH, so the name has to be carried by SNI and verified explicitly:
	// a certificate presented for an IP literal cannot be validated at all.
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{ServerName: apiHost},
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(apiIP, "443"))
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
		resp, err := client.Do(req)
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

	reg, err := call("POST", "/"+apiVer+"/reg", "", map[string]any{
		"fcm_token": "", "install_id": "", "tos": "2024-06-01T00:00:00.000Z",
		"model": "PC", "type": "Android", "serial_number": randomID(),
		"locale": "en_US", "region": "US", "warp_enabled": true,
		"key": base64.StdEncoding.EncodeToString(scalar)})
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("register: %w", err)
	}
	id, _ := reg["id"].(string)
	token, _ := reg["token"].(string)
	if id == "" || token == "" {
		return nil, tls.Certificate{}, fmt.Errorf("register: response carried no id/token")
	}
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

	// The edge expects a certificate with an empty subject and no extensions. Anything else is
	// refused, and the refusal looks like an unexplained handshake reset.
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("certificate: %w", err)
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	return net.ParseIP(addr).To4(), pair, nil
}

// resolve4 returns an IPv4 address for a name, resolved over DoH. The system resolver is not usable
// here: on the device it fails outright, and this measurement is meant to depend on nothing but
// the path it is measuring.
func resolve4(name string) (string, error) {
	ips, err := resolve(name)
	if err != nil {
		return "", err
	}
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			return v4.String(), nil
		}
	}
	return "", fmt.Errorf("no IPv4 for %s", name)
}

func measure() (string, error) {
	srcIP, cert, err := enrol()
	if err != nil {
		return "", err
	}
	fmt.Println("session address :", srcIP.String())

	// WARP_EDGE lets the edge address be a relay that leaves through an egress the edge answers.
	// The protocol is unchanged: the relay forwards datagrams, it does not interpret them.
	edgeHost, edgePort := edgeIP, "443"
	if v := os.Getenv("WARP_EDGE"); v != "" {
		if h, p, err := net.SplitHostPort(v); err == nil {
			edgeHost, edgePort = h, p
		} else {
			edgeHost = v
		}
		fmt.Println("edge            :", edgeHost+":"+edgePort, "(relayed)")
	}
	if p := os.Getenv("WARP_PORT"); p != "" {
		edgePort = p
	}
	edgeAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(edgeHost, edgePort))
	if err != nil {
		return "", err
	}
	// Bind the local address the same way the working client does. Left on the wildcard, this
	// host has several adapters and the kernel picks one of them for the edge.
	local := &net.UDPAddr{}
	if addr := os.Getenv("WARP_BIND"); addr != "" {
		ip := net.ParseIP(addr).To4()
		if ip == nil {
			return "", fmt.Errorf("WARP_BIND is not an IPv4 address: %s", addr)
		}
		local.IP = ip
	}
	udpConn, err := net.ListenUDP("udp4", local)
	if err != nil {
		return "", err
	}
	defer udpConn.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	tlsConf := &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         edgeSNI,
		NextProtos:         []string{"h3"},
		Certificates:       []tls.Certificate{cert},
		MinVersion:         tls.VersionTLS13,
	}
	conn, err := quic.Dial(ctx, udpConn, edgeAddr, tlsConf, &quic.Config{
		EnableDatagrams: true,
		// This edge answers a 1200-byte Initial and silently drops anything larger, so the first
		// flight is sized to exactly what it replies to. Measured on both the host and the device.
		InitialPacketSize: 1200,
		MaxIdleTimeout:    30 * time.Second,
	})
	if err != nil {
		return "", fmt.Errorf("quic dial: %w", err)
	}
	defer conn.CloseWithError(0, "done")

	tun := &tunnel{conn: conn, srcIP: srcIP}
	// Without this the client never sends SETTINGS_ENABLE_CONNECT_PROTOCOL, and the edge answers
	// an extended CONNECT with PROTOCOL_VIOLATION.
	transport := &http3.Transport{
		EnableDatagrams: true,
		// SETTINGS_ENABLE_CONNECT_PROTOCOL is 0x8. Without it the edge refuses an extended
		// CONNECT with PROTOCOL_VIOLATION.
		AdditionalSettings: map[uint64]uint64{0x8: 1},
	}
	h3 := transport.NewClientConn(conn)
	tun.h3 = h3

	datagrams := make(chan []byte, 8192)
	go func() {
		for ip := range datagrams {
			tun.dispatch(ip)
		}
	}()

	req, err := http.NewRequestWithContext(ctx, "CONNECT", "https://cloudflareaccess.com/", nil)
	if err != nil {
		return "", err
	}
	// This transport takes the :protocol value from Request.Proto - it is the extended-CONNECT
	// protocol name, not an HTTP version, and it must not be set as a header.
	req.Proto = "cf-connect-ip"
	req.Header.Set("capsule-protocol", "?1")
	req.Header.Set("cf-connect-proto", "cf-connect-ip")
	req.Header.Set("pq-enabled", "false")

	stream, err := h3.OpenRequestStream(ctx)
	if err != nil {
		return "", fmt.Errorf("open request stream: %w", err)
	}
	tun.stream = stream
	// Sending the extended CONNECT before the peer's SETTINGS have arrived is answered with
	// PROTOCOL_VIOLATION. This build of quic-go has no channel for it, so the control stream is
	// given a moment to be read.
	time.Sleep(250 * time.Millisecond)
	if err := stream.SendRequestHeader(req); err != nil {
		return "", fmt.Errorf("send request header: %w", err)
	}

	resp, err := stream.ReadResponse()
	if err != nil {
		return "", fmt.Errorf("read response: %w", err)
	}
	fmt.Println("CONNECT status  :", resp.Status)

	// Connect-IP capsules arrive as HTTP/3 datagrams on this request stream, not on the
	// connection as a whole. Reading them from the connection never yields anything.
	go func() {
		for {
			data, err := stream.ReceiveDatagram(ctx)
			if err != nil {
				fmt.Println("datagram recv stopped:", err)
				return
			}
			if len(data) < 21 || data[0] != 0x00 {
				continue
			}
			// The short form this edge uses: the type byte, then the whole IP packet.
			rest := data[1:]
			select {
			case datagrams <- rest:
			default:
			}
		}
	}()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("CONNECT refused with %d", resp.StatusCode)
	}

	ips, err := resolve(traceHost)
	if err != nil || len(ips) == 0 {
		return "", fmt.Errorf("resolve %s: %v", traceHost, err)
	}
	var dst net.IP
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			dst = v4
			break
		}
	}
	if dst == nil {
		return "", fmt.Errorf("no IPv4 for %s", traceHost)
	}
	fmt.Println("trace target    :", traceHost, dst.String())

	c := newTunnelConn(tun, srcIP, dst, 51500, 443)
	tun.peers = append(tun.peers, c)
	if err := c.open(); err != nil {
		fmt.Printf("tcp open failed: %v (sent=%d recv=%d)\n", err, c.sent, c.recv)
		return "", err
	}
	fmt.Println("tcp handshake   : established through the tunnel")

	inner := tls.Client(c, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         traceHost,
		MinVersion:         tls.VersionTLS12,
	})
	if err := inner.SetDeadline(time.Now().Add(40 * time.Second)); err != nil {
		return "", err
	}
	if err := inner.Handshake(); err != nil {
		return "", fmt.Errorf("tls inside tunnel: %w", err)
	}
	fmt.Println("tls handshake   : complete, alpn=", inner.ConnectionState().NegotiatedProtocol)

	lines := []string{
		"GET " + tracePath + " HTTP/1.1",
		"Host: " + traceHost,
		"User-Agent: colgram-warp-on",
		"Accept: */*",
		"Connection: close",
		"",
		"",
	}
	if _, err := io.WriteString(inner, strings.Join(lines, crlf)); err != nil {
		return "", fmt.Errorf("write request: %w", err)
	}
	fmt.Printf("request         : GET %s\n", tracePath)

	body := &bytes.Buffer{}
	buf := make([]byte, 4096)
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		inner.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := inner.Read(buf)
		if n > 0 {
			body.Write(buf[:n])
			if bytes.Contains(body.Bytes(), []byte("warp=")) {
				break
			}
		}
		if err != nil {
			break
		}
	}
	fmt.Printf("ip packets      : sent=%d recv=%d\n", c.sent, c.recv)
	return body.String(), nil
}

func readVarint(b []byte) (uint64, int) {
	if len(b) == 0 {
		return 0, 0
	}
	switch {
	case b[0]&0x80 == 0:
		return uint64(b[0]), 1
	case b[0]&0xc0 == 0x80:
		if len(b) < 2 {
			return 0, 0
		}
		return uint64(b[0]&0x3f)<<8 | uint64(b[1]), 2
	case b[0]&0xe0 == 0xc0:
		if len(b) < 4 {
			return 0, 0
		}
		return uint64(b[0]&0x1f)<<24 | uint64(b[1])<<16 | uint64(b[2])<<8 | uint64(b[3]), 4
	default:
		if len(b) < 8 {
			return 0, 0
		}
		v := uint64(b[0]&0x0f)
		for i := 1; i < 8; i++ {
			v = v<<8 | uint64(b[i])
		}
		return v, 8
	}
}

func main() {
	body, err := measure()
	if err != nil {
		fmt.Println("error           :", err)
		os.Exit(1)
	}
	idx := strings.Index(body, crlf+crlf)
	head, payload := body, ""
	if idx >= 0 {
		head = body[:idx]
		payload = body[idx+4:]
	}
	if i := strings.Index(head, crlf); i >= 0 {
		head = head[:i]
	}
	fmt.Println("status          :", head)
	fields := map[string]string{}
	for _, line := range strings.Split(payload, "\n") {
		if k, v, ok := strings.Cut(line, "="); ok {
			fields[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	for _, k := range []string{"warp", "ip", "loc", "colo", "kex", "tls", "http", "sni"} {
		if v, ok := fields[k]; ok {
			fmt.Printf("  %-6s = %s\n", k, v)
		}
	}
	fmt.Println()
	if fields["warp"] == "on" {
		fmt.Println("VERDICT: warp=on -- the request travelled through the WARP tunnel")
		return
	}
	fmt.Printf("VERDICT: warp=%s -- not through the tunnel\n", fields["warp"])
	os.Exit(1)
}

// resolve asks Cloudflare's DoH endpoint directly. The system resolver is not trusted here: on the
// device it fails outright, and the point of this measurement is to depend on nothing but the
// tunnel's own path.
func resolve(host string) ([]net.IP, error) {
	req, err := http.NewRequest("GET", "https://1.1.1.1/dns-query?name="+host+"&type=A", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("accept", "application/dns-json")
	client := &http.Client{
		Timeout:   15 * time.Second,
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}},
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	var out map[string]any
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
answers, _ := out["Answer"].([]any)
	var ips []net.IP
	for _, a := range answers {
		rec, ok := a.(map[string]any)
		if !ok {
			continue
		}
		if t, _ := rec["type"].(float64); int(t) != 1 {
			continue
		}
		if data, ok := rec["data"].(string); ok {
			if ip := net.ParseIP(data); ip != nil {
				ips = append(ips, ip)
			}
		}
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no A record for %s", host)
	}
	return ips, nil
}
