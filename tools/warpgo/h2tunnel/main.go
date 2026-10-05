// h2tunnel runs WARP's MASQUE over HTTP/2 - the whole tunnel on TCP, with no UDP anywhere.
//
// Why build this when the H3 tunnel already reaches warp=on. Because of where the H3 one has to
// run. Measured on the device: QUIC to the edge is filtered on every port the edge serves it on -
// four to five seconds, no Retry, no alert, on 443, 500, 8443 and 4500 alike. TCP to the same
// address and port connects immediately. MASQUE-over-H2 is what Cloudflare ships it as for exactly
// that case - warp-cli's own masque-options set h2-only - so the tunnel needs no helper process,
// no UDP relay, and no egress the app cannot pick for itself.
//
// Nothing here uses x/net/http2. It has no DATAGRAM frame in v0.56.0 - no FrameDATAGRAM constant
// exists - so the frames this needs are written by hand. That is also why every step is explicit:
// when something fails, the failure has to be nameable.
package main

import (
	"bufio"
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
	"strconv"
	"time"
)

const (
	apiHost = "api.cloudflareclient.com"
	apiVer  = "v0a4471"
	edgeIP  = "162.159.198.2"
	edgeSNI = "consumer-masque.cloudflareclient.com"

	traceHost = "connectivity.cloudflareclient.com"
	tracePath = "/cdn-cgi/trace"

	// frameDATAGRAM is RFC 9297 section 3. Written out rather than imported, because the Go HTTP/2
	// package does not define it and this transport cannot work without it.
	frameDATAGRAM = 0x33
)

func main() {
	bind := ""
	port := "443"
	if len(os.Args) > 1 {
		bind = os.Args[1]
	}
	if len(os.Args) > 2 {
		port = os.Args[2]
	}

	srcIP, cert, err := enrol()
	if err != nil {
		die("enrol: %v", err)
	}
	fmt.Println("session address :", srcIP)

	conn, err := dialEdge(port, bind, cert)
	if err != nil {
		die("edge: %v", err)
	}
	defer conn.Close()

	if err := conn.clientPreface(); err != nil {
		die("preface: %v", err)
	}
	fmt.Println("sent preface, SETTINGS (ENABLE_CONNECT_PROTOCOL + H2_DATAGRAM), ping")

	// The server's settings have to be read before the tunnel is asked for: it refuses an extended
	// CONNECT unless it has advertised a datagram capability, and the refusal is a bare
	// PROTOCOL_VIOLATION that says nothing about which setting was missing.
	if err := conn.awaitSettings(8 * time.Second); err != nil {
		die("server settings: %v", err)
	}

	tun, err := conn.openTunnel(srcIP)
	if err != nil {
		die("open tunnel: %v", err)
	}
	fmt.Println("CONNECT status  :", tun.status)
	if tun.status != 200 {
		die("extended CONNECT refused with %d", tun.status)
	}

	traceIP, err := resolve4(traceHost)
	if err != nil {
		die("resolve %s: %v", traceHost, err)
	}
	dst := net.ParseIP(traceIP).To4()

	tc, err := tun.dialTCP(srcIP, dst, 443)
	if err != nil {
		die("tcp inside tunnel: %v", err)
	}
	fmt.Println("tcp handshake   : established through the tunnel")

	tlsConn := tls.Client(tc, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         traceHost,
		MinVersion:         tls.VersionTLS12,
	})
	if err := tlsConn.Handshake(); err != nil {
		die("tls inside tunnel: %v", err)
	}
	fmt.Printf("tls handshake    : complete, alpn=%q\n", tlsConn.ConnectionState().NegotiatedProtocol)

	req, _ := http.NewRequest("GET", "https://"+traceHost+tracePath, nil)
	req.Header.Set("User-Agent", "colgram-warp-on")
	if err := req.Write(tlsConn); err != nil {
		die("write request: %v", err)
	}
	body, err := readBody(tlsConn)
	if err != nil {
		die("read response: %v", err)
	}
	fmt.Println(string(body))
	if field(body, "warp") != "on" {
		die("VERDICT: warp=%s -- not through the tunnel", field(body, "warp"))
	}
	fmt.Println("VERDICT: warp=on over MASQUE/H2, TCP only, no UDP hop")
}

// --- the HTTP/2 connection -------------------------------------------

// h2 is one HTTP/2 connection to the edge, carrying the tunnel as a DATAGRAM stream.
type h2 struct {
	conn     net.Conn
	nextID   uint32
	wmu      chan struct{}
	hasDatag bool
}

func dialEdge(port, bind string, cert tls.Certificate) (*h2, error) {
	d := net.Dialer{Timeout: 10 * time.Second}
	// The source address is not cosmetic: it picks the route and the egress, and this host has a
	// tunnel holding 128.0.0.0/1 at metric 0 that the edge refuses to talk to. Left unbound, the
	// handshake ends in a bare EOF that looks exactly like an SNI block.
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			return nil, fmt.Errorf("bind is not an IPv4 address: %s", bind)
		}
		// A TCPAddr, not a UDPAddr: the network here is tcp, and a UDPAddr is rejected with
		// "mismatched local address type".
		d.LocalAddr = &net.TCPAddr{IP: ip}
	}
	raw, err := d.Dial("tcp", net.JoinHostPort(edgeIP, port))
	if err != nil {
		return nil, err
	}
	tc := tls.Client(raw, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         edgeSNI,
		// h2 and nothing else. If the server picks a different protocol, every frame below is
		// meaningless - so this is checked rather than assumed.
		NextProtos:   []string{"h2"},
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS13,
	})
	if err := tc.Handshake(); err != nil {
		raw.Close()
		return nil, fmt.Errorf("tls: %w", err)
	}
	p := tc.ConnectionState().NegotiatedProtocol
	if p != "h2" {
		// Not fatal, and deliberately so. The edge closes the connection with an access-denied alert
		// rather than a protocol error, so refusing to continue on a missing ALPN would hide the one
		// thing worth seeing. What matters is what happens after the preface is written, and that is
		// reported by name below.
		fmt.Printf("note: server negotiated ALPN %q, expected h2 - continuing to observe\n", p)
	}
	// The negotiated protocol is printed as the value it actually holds.
	//
	// This line used to say "alpn=h2" as a literal while the check above had just reported the real
	// value as empty. That is worse than no output: the eye reads h2, the conclusion drawn from it is
	// that the HTTP/2 path is alive, and every frame sent afterwards is then judged against the wrong
	// idea of what the server agreed to. A diagnostic that contradicts itself one line apart cannot be
	// used to decide anything.
	fmt.Printf("tls ok: version=0x%04x alpn=%q from %s\n",
		tc.ConnectionState().Version, p, tc.LocalAddr())
	return &h2{conn: tc, nextID: 1, wmu: make(chan struct{}, 1)}, nil
}

// clientPreface writes the connection preface and the client's SETTINGS.
func (h *h2) clientPreface() error {
	if _, err := h.conn.Write([]byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")); err != nil {
		return err
	}
	var payload bytes.Buffer
	// SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8). Without it an extended CONNECT is a violation.
	binary.Write(&payload, binary.BigEndian, uint16(0x8))
	binary.Write(&payload, binary.BigEndian, uint32(1))
	// RFC 9297: the client sends H2_DATAGRAM to say it will accept datagrams. There is no separate
	// "send" capability - that asymmetry is deliberate in the RFC and is why one flag is enough.
	binary.Write(&payload, binary.BigEndian, uint16(0x33))
	binary.Write(&payload, binary.BigEndian, uint32(1))
	// SETTINGS and PING are connection-level, so stream 0. Only a request frame carries a stream id.
	if err := h.writeFrame(0, 0x4, 0, payload.Bytes()); err != nil {
		return err
	}
	// A ping, so a silent peer becomes a visible one instead of a timeout.
	return h.writeFrame(0, 0x6, 0, []byte{0x5c, 0x5c, 0x5c, 0x5c})
}

var settingNames = map[uint16]string{
	0x1:  "HEADER_TABLE_SIZE",
	0x3:  "MAX_CONCURRENT_STREAMS",
	0x4:  "INITIAL_WINDOW_SIZE",
	0x5:  "MAX_FRAME_SIZE",
	0x6:  "MAX_HEADER_LIST_SIZE",
	0x8:  "ENABLE_CONNECT_PROTOCOL",
	0x33: "H2_DATAGRAM",
}

// awaitSettings reads the server's SETTINGS and reports what it will allow.
func (h *h2) awaitSettings(budget time.Duration) error {
	deadline := time.Now().Add(budget)
	for time.Now().Before(deadline) {
		h.conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		typ, flags, payload, err := h.readFrame()
		if err != nil {
			return err
		}
		switch typ {
		case 0x4:
			if flags&0x1 != 0 {
				continue
			}
			for i := 0; i+6 <= len(payload); i += 6 {
				key := binary.BigEndian.Uint16(payload[i : i+2])
				val := binary.BigEndian.Uint32(payload[i+2 : i+6])
				name, ok := settingNames[key]
				if !ok {
					name = "unknown"
				}
				fmt.Printf("server setting 0x%02x %-26s = %d\n", key, name, val)
				if key == 0x33 {
					h.hasDatag = val == 1
				}
			}
			if err := h.writeFrame(0, 0x4, 0x1, nil); err != nil {
				return err
			}
			// Not a blocker. RFC 9297 defines SETTINGS_H2_DATAGRAM (0x33) as a client-to-server setting
			// only: it says the client will accept datagrams. A server that never sends one is
			// conforming, so treating its absence as "this cannot work" would refuse a connection that
			// is in fact fine. Checked and cleared, because it looked like a wall and was not one.
			if h.hasDatag {
				fmt.Println("server also advertises H2_DATAGRAM")
			}
			return nil
		case 0x7:
			if len(payload) < 8 {
				return fmt.Errorf("GOAWAY with a %d-byte payload", len(payload))
			}
			return fmt.Errorf("GOAWAY last-stream-id=%d error=%s debug=%q",
				be32(payload, 0), goawayReason(be32(payload, 4)), string(payload[8:]))
		}
	}
	return fmt.Errorf("no SETTINGS within %s", budget)
}

// tunnel is the CONNECT stream plus the datagrams flowing on it.
type tunnel struct {
	h      *h2
	stream uint32
	status int
	in     chan []byte
}

// openTunnel sends the extended CONNECT and waits for the response head.
func (h *h2) openTunnel(srcIP net.IP) (*tunnel, error) {
	id := h.nextID
	h.nextID += 2
	// END_STREAM as well as END_HEADERS. An extended CONNECT carries no body, so the request ends
	// with its header block; sending HEADERS without END_STREAM tells the server more data is coming
	// on a stream that will never carry any, and it answers GOAWAY PROTOCOL_ERROR before the request
	// is ever read.
	if err := h.writeFrame(id, 0x1, 0x4|0x1, hpackConnect()); err != nil {
		return nil, err
	}
	t := &tunnel{h: h, stream: id, in: make(chan []byte, 512)}
	go t.read()

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		h.conn.SetReadDeadline(time.Now().Add(4 * time.Second))
		typ, flags, payload, err := h.readFrame()
		if err != nil {
			return nil, err
		}
		switch typ {
		case 0x1:
			status, _, err := hpackStatus(payload)
			if err != nil {
				return nil, err
			}
			t.status = status
			return t, nil
		case 0x4:
			if flags&0x1 == 0 {
				if err := h.writeFrame(id, 0x4, 0x1, nil); err != nil {
					return nil, err
				}
			}
		case 0x6:
			if flags&0x1 == 0 {
				if err := h.writeFrame(id, 0x6, 0x1, payload); err != nil {
					return nil, err
				}
			}
		case 0x3:
			// RST_STREAM. The code is the only thing that says why.
			return nil, fmt.Errorf("RST_STREAM on the tunnel, error code %d", be32(payload, 4))
		case 0x7:
			// RFC 9113 section 6.8: a GOAWAY frame is a 9-byte head of last-stream-id and error code,
			// then an opaque debug block. Parsing it as one hex blob produced
			// "GOAWAY: 0000000000000001", which is a stream id and an error code and neither of them
			// readable - the one line that had to say why.
			if len(payload) < 8 {
				return nil, fmt.Errorf("GOAWAY with a %d-byte payload", len(payload))
			}
			return nil, fmt.Errorf("GOAWAY last-stream-id=%d error=%s debug=%q",
				be32(payload, 0), goawayReason(be32(payload, 4)), string(payload[8:]))
		}
	}
	return nil, fmt.Errorf("no response head within the deadline")
}

// read keeps the connection serviced, splitting datagrams from everything else.
func (t *tunnel) read() {
	for {
		typ, flags, payload, err := t.h.readFrame()
		if err != nil {
			return
		}
		switch typ {
		case frameDATAGRAM:
			t.deliver(payload)
		case 0x6:
			if flags&0x1 == 0 {
				t.h.writeFrame(t.stream, 0x6, 0x1, payload)
			}
		case 0x4:
			if flags&0x1 == 0 {
				t.h.writeFrame(0, 0x4, 0x1, nil)
			}
		}
	}
}

// deliver turns one datagram frame into IP packets for the TCP state machine.
func (t *tunnel) deliver(frame []byte) {
	// RFC 9298 Connect-IP: a half-stream ID then the context ID then the packet. This edge uses the
	// short form, which is a single type byte followed by the whole IP packet.
	if len(frame) < 1 {
		return
	}
	pkt := frame[1:]
	select {
	case t.in <- pkt:
	default:
		// Bounded on purpose: a reader that has stopped must not make the tunnel buffer without limit.
	}
}

// --- TCP inside the tunnel --------------------------------------------

const (
	stSyn = iota
	stEstablished
)

// tcpConn is a TCP stream whose packets travel as capsules. It is written out rather than mapped
// onto net.Pipe because every byte here has to go through a 1200-byte capsule, and a pipe would
// hide that entirely - which is exactly the mistake that makes a tunnel look alive and carry
// nothing.
type tcpConn struct {
	t    *tunnel
	src  net.IP
	dst  net.IP
	sport int
	dport int

	mu     chan struct{}
	in     chan []byte
	buf    []byte
	state  int
	seq    uint32
	ack    uint32
	sent   []byte
}

// dialTCP runs the three-way handshake through the tunnel.
func (t *tunnel) dialTCP(src, dst net.IP, dport int) (net.Conn, error) {
	sport := 30000 + int(time.Now().UnixNano()%20000)
	c := &tcpConn{
		t: t, src: src, dst: dst, sport: sport, dport: dport,
		mu: make(chan struct{}, 1), in: make(chan []byte, 512), state: stSyn,
	}
	// A fixed sequence number, not an advancing one: a SYN retransmit has to repeat the original
	// sequence, and a SYN with a fresh number is a second connection attempt the far end refuses.
	c.seq = 1000
	c.sendSYN()
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) && c.state != stEstablished {
		select {
		case pkt := <-c.in:
			c.onPacket(pkt)
		case <-time.After(3 * time.Second):
			if c.state == stSyn {
				// A lost capsule and a dead tunnel look identical from here, and the difference
				// decides whether retrying is right or futile.
				fmt.Println("  syn: no answer, retransmitting")
				c.sendSYN()
			}
		}
	}
	if c.state != stEstablished {
		return nil, fmt.Errorf("no handshake inside the tunnel")
	}
	return c, nil
}

func (c *tcpConn) sendSYN() {
	c.writeCapsule(buildTCP(c.src, c.dst, c.sport, c.dport, 0x02, c.seq, 0, nil))
}

func (c *tcpConn) onPacket(raw []byte) {
	if len(raw) < 20 || raw[0]>>4 != 4 {
		return
	}
	hl := int(raw[0]&0x0f) * 4
	if len(raw) < hl+20 {
		return
	}
	tcp := raw[hl:]
	if int(be16(tcp, 0)) != c.dport || int(be16(tcp, 2)) != c.sport {
		return
	}
	seq := be32(tcp, 4)
	doff := int(tcp[12]>>4) * 4
	if doff < 20 || len(tcp) < doff {
		return
	}
	flags := tcp[13]
	data := tcp[doff:]

	if flags&0x02 != 0 && flags&0x10 != 0 {
		// SYN-ACK. Answered with a plain ACK, never with an RST: an RST here would tell the far end
		// to abort, and the tunnel would drop for no reason at all.
		c.ack = seq + 1
		c.writeCapsule(buildTCP(c.src, c.dst, c.sport, c.dport, 0x10, c.seq, c.ack, nil))
		c.seq++
		c.state = stEstablished
		fmt.Println("  syn-ack: acknowledged")
		c.flush()
		return
	}
	if flags&0x10 != 0 {
		c.ack = seq + uint32(len(data))
		if len(data) > 0 {
			c.push(data)
		}
		// Every segment is acknowledged, including one that carried data, so the far end stops
		// retransmitting it.
		c.writeCapsule(buildTCP(c.src, c.dst, c.sport, c.dport, 0x10, c.seq, c.ack, nil))
	}
}

// push queues received bytes for the TLS client, dropping when full the way a real socket does.
func (c *tcpConn) push(b []byte) {
	copyOf := append([]byte(nil), b...)
	select {
	case c.in <- copyOf:
	default:
	}
}

func (c *tcpConn) flush() {
	if len(c.sent) == 0 {
		return
	}
	c.writeCapsule(buildTCP(c.src, c.dst, c.sport, c.dport, 0x18, c.seq, c.ack, c.sent))
	c.seq += uint32(len(c.sent))
	c.sent = nil
}

func (c *tcpConn) Write(b []byte) (int, error) {
	c.mu <- struct{}{}
	defer func() { <-c.mu }()
	// MSS 1360 keeps a data segment inside one 1200-byte capsule once the tunnel overhead is paid.
	const mss = 1360
	total := 0
	for len(b) > 0 {
		n := len(b)
		if n > mss {
			n = mss
		}
		c.writeCapsule(buildTCP(c.src, c.dst, c.sport, c.dport, 0x18, c.seq, c.ack, b[:n]))
		c.seq += uint32(n)
		b = b[n:]
		total += n
	}
	return total, nil
}

func (c *tcpConn) Read(b []byte) (int, error) {
	for len(c.buf) == 0 {
		select {
		case d := <-c.in:
			c.buf = append(c.buf, d...)
		case <-time.After(60 * time.Second):
			return 0, io.EOF
		}
	}
	n := copy(b, c.buf)
	c.buf = c.buf[n:]
	return n, nil
}

func (c *tcpConn) writeCapsule(pkt []byte) {
	c.t.h.writeDatagram(c.t.stream, pkt)
}

func (c *tcpConn) Close() error                       { return nil }
func (c *tcpConn) LocalAddr() net.Addr                { return &net.TCPAddr{IP: c.src, Port: c.sport} }
func (c *tcpConn) RemoteAddr() net.Addr               { return &net.TCPAddr{IP: c.dst, Port: c.dport} }
func (c *tcpConn) SetDeadline(t time.Time) error      { return nil }
func (c *tcpConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *tcpConn) SetWriteDeadline(t time.Time) error { return nil }

// --- frames ------------------------------------------------------------

// writeFrame writes one frame with its stream id.
//
// The stream id is the whole point of this fix. An HTTP/2 frame header is nine bytes - three of
// length, one type, one flags, four of stream id - and the version of this function that only
// filled the first five left every request on stream 0. Stream 0 is the connection itself, and a
// client may not open a request on it, so the extended CONNECT was refused as PROTOCOL_ERROR and a
// GOAWAY followed with last-stream-id 0: the connection looked alive and had never carried a
// request at all.
func (h *h2) writeFrame(stream uint32, typ byte, flags byte, payload []byte) error {
	h.wmu <- struct{}{}
	defer func() { <-h.wmu }()
	head := make([]byte, 9)
	head[0] = byte(len(payload) >> 16)
	head[1] = byte(len(payload) >> 8)
	head[2] = byte(len(payload))
	head[3] = typ
	head[4] = flags
	// 31 bits in a 32-bit field: the top bit is reserved and always zero.
	head[5] = byte(stream >> 24)
	head[6] = byte(stream >> 16)
	head[7] = byte(stream >> 8)
	head[8] = byte(stream)
	// A write deadline, because without one a half-open connection parks a write forever and the
	// app waits on a tunnel that stopped moving.
	h.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, err := h.conn.Write(append(head, payload...))
	return err
}

func (h *h2) Close() error { return h.conn.Close() }

func (h *h2) readFrame() (byte, byte, []byte, error) {
	head := make([]byte, 9)
	if _, err := readFull(h.conn, head); err != nil {
		return 0, 0, nil, err
	}
	length := int(head[0])<<16 | int(head[1])<<8 | int(head[2])
	if length > 1<<20 {
		return 0, 0, nil, fmt.Errorf("frame length %d is implausible", length)
	}
	payload := make([]byte, length)
	if length > 0 {
		if _, err := readFull(h.conn, payload); err != nil {
			return 0, 0, nil, err
		}
	}
	return head[3], head[4], payload, nil
}

// writeDatagram wraps one capsule in a DATAGRAM frame.
//
// The frame itself goes on stream 0, and the stream it carries data for is in the payload. RFC 9297
// section 3 says exactly that: a DATAGRAM frame is sent on stream 0 and its first four payload
// bytes are the stream id the datagram belongs to. Putting the tunnel's stream in the frame header
// instead is a different frame - an ordinary frame on the tunnel's stream - and the edge discards
// it, which is another way for the tunnel to look alive and carry nothing.
func (h *h2) writeDatagram(stream uint32, payload []byte) {
	body := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint32(body, stream)
	copy(body[4:], payload)
	if err := h.writeFrame(0, frameDATAGRAM, 0, body); err != nil {
		fmt.Println("datagram write:", err)
	}
}

func be16(b []byte, i int) uint16 { return binary.BigEndian.Uint16(b[i:]) }
func be32(b []byte, i int) uint32 { return binary.BigEndian.Uint32(b[i:]) }

// goawayReason names the HTTP/2 error codes, because "GOAWAY error 1" says nothing and the number
// alone would be looked up in a table instead of understood here.
func goawayReason(code uint32) string {
	names := map[uint32]string{
		0x0: "NO_ERROR",
		0x1: "PROTOCOL_ERROR",
		0x2: "INTERNAL_ERROR",
		0x3: "FLOW_CONTROL_ERROR",
		0x4: "SETTINGS_TIMEOUT",
		0x5: "STREAM_CLOSED",
		0x6: "FRAME_SIZE_ERROR",
		0x7: "REFUSED_STREAM",
		0x8: "CANCEL",
		0x9: "COMPRESSION_ERROR",
		0xa: "CONNECT_ERROR",
		0xb: "ENHANCE_YOUR_CALM",
		0xc: "INADEQUATE_SECURITY",
		0xd: "HTTP_1_1_REQUIRED",
	}
	if n, ok := names[code]; ok {
		return fmt.Sprintf("%s (%d)", n, code)
	}
	return fmt.Sprintf("0x%x", code)
}

// --- HPACK -------------------------------------------------------------

// hpackConnect builds the extended CONNECT request as a header block.
//
// Every field is a literal without indexing with its name written out, which is valid HPACK and
// needs neither a Huffman table nor an encoder - the right trade for a one-shot request whose only
// job is to be understood.
//
// The protocol name goes in the :protocol pseudo-header, not in a header of its own. A
// "cf-connect-proto" header is silently ignored over HTTP/2, and the edge then refuses the request
// for a reason it does not give.
//
// No :scheme and no :path. RFC 8441 section 5 requires that they be omitted for an extended CONNECT
// - it inherits the stream's context instead - and including them earns a GOAWAY. That is the whole
// difference between a request that is read and one that ends the connection on arrival.
func hpackConnect() []byte {
	fields := [][2]string{
		{":method", "CONNECT"},
		{":protocol", "cf-connect-ip"},
		{":authority", edgeSNI},
		{"capsule-protocol", "?1"},
	}
	var b bytes.Buffer
	for _, f := range fields {
		b.WriteByte(0x00) // literal header field without indexing, new name
		b.WriteByte(byte(len(f[0])))
		b.WriteString(f[0])
		b.WriteByte(byte(len(f[1])))
		b.WriteString(f[1])
	}
	return b.Bytes()
}

// staticTable names the entries hpackStatus needs to resolve, by 1-based index.
var staticTable = []string{
	":authority", ":method", ":path", ":scheme", ":status",
	":protocol", "accept", "accept-encoding", "accept-language", "accept-ranges", "age",
}

// hpackStatus pulls :status out of a response header block.
//
// Only literals and static-table references are understood, which covers what this edge sends. An
// unknown encoding is an error rather than a guess: reporting the wrong status would make a refused
// tunnel look like a working one.
func hpackStatus(block []byte) (int, string, error) {
	for i := 0; i < len(block); {
		b := block[i]
		switch {
		case b&0x80 != 0:
			idx, n, err := readInt(block[i:], 7)
			if err != nil {
				return 0, "", err
			}
			i += n
			if idx == 8 {
				return 200, ":status 200", nil
			}
		case b&0xc0 == 0x40:
			var err error
			i, err = skipLiteral(block, i)
			if err != nil {
				return 0, "", err
			}
		default:
			nameIdx, n, err := readInt(block[i:], 4)
			if err != nil {
				return 0, "", err
			}
			i += n
			var name string
			if nameIdx > 0 {
				if nameIdx >= uint64(len(staticTable)) {
					return 0, "", fmt.Errorf("static index %d out of range", nameIdx)
				}
				name = staticTable[nameIdx-1]
			} else {
				nameLen, n2, err := readInt(block[i:], 7)
				if err != nil {
					return 0, "", err
				}
				i += n2
				if i+int(nameLen) > len(block) {
					return 0, "", fmt.Errorf("truncated header name")
				}
				name = string(block[i : i+int(nameLen)])
				i += int(nameLen)
			}
			valLen, n3, err := readInt(block[i:], 7)
			if err != nil {
				return 0, "", err
			}
			i += n3
			if i+int(valLen) > len(block) {
				return 0, "", fmt.Errorf("truncated header value")
			}
			val := string(block[i : i+int(valLen)])
			i += int(valLen)
			if name == ":status" {
				code, err := strconv.Atoi(val)
				if err != nil {
					return 0, "", fmt.Errorf("status %q is not a number", val)
				}
				return code, ":status " + val, nil
			}
		}
	}
	return 0, "", fmt.Errorf("no :status in the header block")
}

func skipLiteral(block []byte, i int) (int, error) {
	_, n, err := readInt(block[i:], 6)
	if err != nil {
		return 0, err
	}
	i += n
	nameLen, n2, err := readInt(block[i:], 4)
	if err != nil {
		return 0, err
	}
	i += n2 + int(nameLen)
	valLen, n3, err := readInt(block[i:], 7)
	if err != nil {
		return 0, err
	}
	return i + n3 + int(valLen), nil
}

// readInt decodes an HPACK integer with the given prefix width.
func readInt(b []byte, prefix uint8) (uint64, int, error) {
	if len(b) == 0 {
		return 0, 0, io.ErrUnexpectedEOF
	}
	mask := uint64(1)<<prefix - 1
	v := uint64(b[0]) & mask
	if v < mask {
		return v, 1, nil
	}
	m := uint8(0)
	for i := 1; i < len(b); i++ {
		v += uint64(b[i]&0x7f) << m
		if b[i]&0x80 == 0 {
			return v, i + 1, nil
		}
		m += 7
		if m > 56 {
			return 0, 0, fmt.Errorf("integer overflow")
		}
	}
	return 0, 0, io.ErrUnexpectedEOF
}

// --- IP and TCP --------------------------------------------------------

// buildTCP assembles an IPv4 packet carrying a TCP segment.
//
// Checksums are computed because the far end is a real TCP stack and drops what does not verify.
// A zero checksum reads as corruption, and would be reported as the tunnel dropping traffic when
// in fact it never left correctly formed.
func buildTCP(src, dst net.IP, sport, dport int, flags byte, seq, ack uint32, data []byte) []byte {
	tcp := make([]byte, 20+len(data))
	binary.BigEndian.PutUint16(tcp[0:], uint16(sport))
	binary.BigEndian.PutUint16(tcp[2:], uint16(dport))
	binary.BigEndian.PutUint32(tcp[4:], seq)
	binary.BigEndian.PutUint32(tcp[8:], ack)
	tcp[12] = 5 << 4 // data offset, 20 bytes, no options
	tcp[13] = flags
	binary.BigEndian.PutUint16(tcp[14:], 65535) // window
	copy(tcp[20:], data)
	// The checksum field is zeroed first, then filled: overwriting a live field instead would fold
	// the previous value into the sum and produce a checksum that is wrong in a way that verifies.
	tcp[16], tcp[17] = 0, 0
	binary.BigEndian.PutUint16(tcp[16:], checksum(src, dst, 6, tcp))

	pkt := make([]byte, 20+len(tcp))
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:], uint16(len(pkt)))
	pkt[8] = 64 // TTL
	pkt[9] = 6  // TCP
	copy(pkt[12:16], src.To4())
	copy(pkt[16:20], dst.To4())
	binary.BigEndian.PutUint16(pkt[10:], checksum(src, dst, 0, pkt))
	copy(pkt[20:], tcp)
	return pkt
}

func checksum(src, dst net.IP, proto byte, body []byte) uint16 {
	pseudo := make([]byte, 12+len(body))
	copy(pseudo[0:4], src.To4())
	copy(pseudo[4:8], dst.To4())
	pseudo[9] = proto
	binary.BigEndian.PutUint16(pseudo[10:], uint16(len(body)))
	copy(pseudo[12:], body)
	var sum uint32
	for i := 0; i+1 < len(pseudo); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(pseudo[i:]))
	}
	if len(pseudo)%2 == 1 {
		sum += uint32(pseudo[len(pseudo)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = sum&0xffff + sum>>16
	}
	return ^uint16(sum)
}

// --- registration ------------------------------------------------------

// enrol registers a key and returns the certificate the edge expects.
//
// The PATCH matters as much as the POST: without it the edge holds the account key but not the
// public key it will check the certificate against, and refuses the tunnel later with nothing to
// say why. The certificate is bare - empty subject, no extensions - because anything else is
// refused, and that refusal arrives as an unexplained handshake reset.
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
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{ServerName: apiHost},
			// Dialled by address, because the address is pinned from DoH and a certificate for an IP
			// literal cannot be validated at all.
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
		return nil, tls.Certificate{}, fmt.Errorf("register: no id/token in the response")
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

func randomID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// --- DoH ---------------------------------------------------------------

// resolve4 asks a resolver over DoH, at an address literal and under a name that is not that
// address.
//
// The name and the address differ on purpose. Russian ISPs have been recorded cutting
// DNS-over-HTTPS with a TCP reset *after* the ClientHello, keyed on the SNI, while leaving the same
// IP reachable under a different name - net4people/bbs #81, Risky Bulletin 2026-08-26. The
// certificate is still verified against the real resolver name, so a cut resolver cannot be swapped
// for an impostor just because the name it was reached under changed.
func resolve4(host string) (string, error) {
	endpoints := []struct{ ip, sni, verify string }{
		{"1.1.1.1", "cloudflare-dns.com", "cloudflare-dns.com"},
		{"1.0.0.1", "cloudflare-dns.com", "cloudflare-dns.com"},
		{"8.8.8.8", "dns.google", "dns.google"},
		{"94.140.14.14", "adguard-dns.com", "adguard-dns.com"},
	}
	var lastErr error
	for _, e := range endpoints {
		ip, err := resolveVia(e.ip, e.sni, e.verify, host)
		if err == nil {
			return ip, nil
		}
		lastErr = err
		fmt.Printf("  doh %s: %v\n", e.ip, err)
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no endpoint produced an A record for %s", host)
	}
	return "", lastErr
}

func resolveVia(ip, sni, verify, host string) (string, error) {
	req, err := http.NewRequest("GET",
		"https://"+ip+"/dns-query?name="+host+"&type=A", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("accept", "application/dns-json")
	req.Host = verify
	client := &http.Client{
		Timeout: 12 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{ServerName: sni},
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(ip, "443"))
			},
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("http %d", resp.StatusCode)
	}
	var out map[string]any
	json.Unmarshal(raw, &out)
	answers, _ := out["Answer"].([]any)
	for _, a := range answers {
		rec, ok := a.(map[string]any)
		if !ok {
			continue
		}
		if t, _ := rec["type"].(float64); int(t) != 1 {
			continue
		}
		if data, ok := rec["data"].(string); ok {
			if v := net.ParseIP(data); v != nil {
				if v4 := v.To4(); v4 != nil {
					return v4.String(), nil
				}
			}
		}
	}
	return "", fmt.Errorf("no A record")
}

// --- helpers -----------------------------------------------------------

func readFull(c net.Conn, b []byte) (int, error) {
	read := 0
	for read < len(b) {
		n, err := c.Read(b[read:])
		if err != nil {
			return read, err
		}
		read += n
	}
	return read, nil
}

// readBody parses the response instead of scanning for the trace fields by hand.
//
// It goes through http.ReadResponse because the framing matters: this response arrives inside a
// TCP stream this file invented, and a chunked body read as raw bytes would either lose its
// terminator or cut the last field in half - and the last field is warp=, which is the one that
// decides whether this is a working tunnel.
func readBody(c net.Conn) ([]byte, error) {
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

func field(body []byte, key string) string {
	for _, line := range bytes.Split(body, []byte("\n")) {
		if bytes.HasPrefix(line, []byte(key+"=")) {
			return string(bytes.TrimPrefix(line, []byte(key+"=")))
		}
	}
	return ""
}

func die(format string, args ...any) {
	fmt.Printf(format+"\n", args...)
	os.Exit(1)
}
