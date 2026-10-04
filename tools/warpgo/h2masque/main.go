// language: Go, file: main.go, target: host probe for MASQUE over HTTP/2 (TCP)
//
// QUIC is the MASQUE carrier Cloudflare prefers, and on this network every UDP path to the edge is
// answered by a version-negotiation packet and never by a handshake, so the tunnel cannot be built on
// it here. HTTP/2 over TCP is the other carrier the edge speaks, and it completes TLS on 443. This
// binary asks the only question that matters: does an extended CONNECT with :protocol cf-connect-ip
// reach 200, and does a Connect-IP capsule carried in the request body bring an IP packet back?
//
// Every stage prints its verdict, because "tunnel did not come up" is four different faults and only
// the printout says which one happened.
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
	"sync"
	"time"

	"golang.org/x/net/http2/hpack"
)

const (
	apiHost     = "api.cloudflareclient.com"
	apiVer      = "v0a4471"
	connectAuth = "cloudflareaccess.com"
	edgeSNI     = "consumer-masque.cloudflareclient.com"
	// apiSNIMask is the name carried in the ClientHello for the enrolment API. The filter on this
	// network reads the SNI extension, and the real API name is refused on sight while 1.1.1.1 is
	// answered with a valid Cloudflare certificate.
	apiSNIMask = "1.1.1.1"
	// traceTarget is the address the SYN is aimed at. It is a literal rather than a lookup because the
	// lookup goes through the same edge the tunnel uses, and a failed lookup inside the tunnel would be
	// indistinguishable from a tunnel that carries nothing.
	traceTarget = "104.16.123.96"
)

// edges are the HTTP/2 carriers, tried in order. 162.159.198.2 is the address the community client
// ships as its HTTP/2 endpoint; the others are in the same anycast range and answer TLS with the
// same name.
// edges are the HTTP/2 carriers, in the order they are dialled. 162.159.198.2 is deliberately absent:
// it completes TLS but answers with an empty ALPN, which is the front end speaking HTTP/1.1, and a
// CONNECT 200 from it is the front end accepting rather than MASQUE answering. Every address here was
// measured to negotiate h2 with the MASQUE name.
// edges are the HTTP/2 carriers, in the order they are dialled.
//
// 162.159.198.2 comes first and is the only address in this range running MASQUE: with no client
// certificate it refuses the handshake outright with TLSV13_ALERT_CERTIFICATE_REQUIRED, while every
// other address completes TLS and answers HTTP 530, which is a front end with no origin behind it. The
// 530 addresses are kept in the list because a carrier that answers at all is worth naming, and
// because a change in which address behaves that way is the signal that the set moved.
var edges = []string{
	"162.159.198.2:443",
	"162.159.198.1:443",
	"162.159.192.6:443",
	"162.159.192.7:443",
}

// h2raw carries one MASQUE tunnel over HTTP/2 with the framing written by hand.
//
// Go's http2 client refuses an extended CONNECT unless the peer advertises
// SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8), and the edge does not advertise it - measured, its SETTINGS
// carry only 0x3, 0x4 and 0x5. That is the reason the stock client reports "extended connect not
// supported by peer" against a carrier that does speak the protocol. Writing the frames directly is
// what makes the question answerable: either the edge accepts the request regardless of the setting,
// or this carrier is closed and the printout says so.
type h2raw struct {
	conn   net.Conn
	br     *bufio.Reader
	wmu    sync.Mutex
	sid    uint32
	inflow int32
}

func dialH2Raw(ctx context.Context, addr string, cert tls.Certificate) (*h2raw, error) {
	var d net.Dialer
	raw, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	tc := tls.Client(raw, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         edgeSNI,
		NextProtos:         []string{"h2"},
		Certificates:       []tls.Certificate{cert},
		MinVersion:         tls.VersionTLS13,
	})
	if err := tc.HandshakeContext(ctx); err != nil {
		raw.Close()
		return nil, err
	}
	if got := tc.ConnectionState().NegotiatedProtocol; got != "h2" {
		// An empty ALPN here is not a wrong carrier. This edge demands the client certificate during
		// the handshake and only then decides, and a certificate it accepts is answered with no protocol
		// list at all even though it speaks h2 - measured against this exact address. The MASQUE
		// request that follows is what decides whether the carrier is usable, so the check is left to it.
		fmt.Printf("  negotiated %q after presenting the certificate\n", got)
	}
	c := &h2raw{conn: tc, br: bufio.NewReader(tc), sid: 1, inflow: 65536}
	// The connection preface, then our own SETTINGS.
	//
	// Two settings matter and both were missing from an earlier version that sent only ENABLE_CONNECT_PROTOCOL.
	// 0x8 tells the edge the client can read an extended CONNECT, which it never advertises in return. 0x276
	// is SETTINGS_H3_DATAGRAM: the edge reads a capsule stream as datagrams, and it will not forward one
	// to a peer that has not said it accepts them. The H3 carrier gets this for free from the QUIC layer,
	// which is why its absence here was only visible on the TCP carrier - a tunnel that opens, accepts
	// capsules and then routes nothing is what an edge does with a peer it will not send datagrams to.
	// These are the settings a working MASQUE-over-HTTP/2 client sends, transcribed from the bytes it
	// was observed writing. The one that matters is INITIAL_WINDOW_SIZE: at the HTTP/2 default of 65535 a
	// peer can hold less than a full tunnel packet, and this edge grants its own credit against what the
	// client declares. A client that leaves the window at the default can therefore open the stream,
	// accept capsules, and never be sent anything - which is exactly what a tunnel that opens and then
	// carries nothing looks like from this side.
	var payload []byte
	payload = append(payload, h2setting(0x2, 0)...)       // ENABLE_PUSH, off
	payload = append(payload, h2setting(0x4, 4194304)...) // INITIAL_WINDOW_SIZE, 4 MB
	payload = append(payload, h2setting(0x5, 16384)...)   // MAX_FRAME_SIZE
	payload = append(payload, h2setting(0x6, 10485760)...)// MAX_HEADER_LIST_SIZE
	payload = append(payload, h2setting(0x8, 1)...)       // ENABLE_CONNECT_PROTOCOL
	payload = append(payload, h2setting(0x276, 1)...)     // H3_DATAGRAM, for the H3 carrier
	settings := frameH2(0x4, 0, 0, payload)
	if _, err := c.conn.Write(append([]byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"), settings...)); err != nil {
		tc.Close()
		return nil, err
	}
	return c, nil
}

// readFrame reads one frame header plus payload.
func (c *h2raw) readFrame() (typ, flags byte, stream uint32, payload []byte, err error) {
	var head [9]byte
	if _, err = io.ReadFull(c.br, head[:]); err != nil {
		return
	}
	ln := int(head[0])<<16 | int(head[1])<<8 | int(head[2])
	typ, flags = head[3], head[4]
	stream = uint32(head[5])<<24 | uint32(head[6])<<16 | uint32(head[7])<<8 | uint32(head[8])
	if ln > 0 {
		payload = make([]byte, ln)
		_, err = io.ReadFull(c.br, payload)
	}
	return
}

// awaitSettings reads until the peer's SETTINGS have arrived and reports whether it enabled the
// extended CONNECT.
func (c *h2raw) awaitSettings(ctx context.Context) (bool, error) {
	for {
		if err := c.conn.SetReadDeadline(time.Now().Add(20 * time.Second)); err != nil {
			return false, err
		}
		typ, _, _, payload, err := c.readFrame()
		if err != nil {
			return false, err
		}
		switch typ {
		case 0x4: // SETTINGS
			if len(payload)%6 != 0 {
				return false, fmt.Errorf("settings payload is not a multiple of six")
			}
			allowed := false
			for i := 0; i+6 <= len(payload); i += 6 {
				id := uint16(payload[i])<<8 | uint16(payload[i+1])
				val := uint32(payload[i+2])<<24 | uint32(payload[i+3])<<16 | uint32(payload[i+4])<<8 | uint32(payload[i+5])
				switch id {
				case 0x8:
					allowed = val == 1
				case 0x3:
					c.inflow = int32(val)
				}
			}
			// Acknowledge, or the edge treats the connection as unresponsive.
			if _, err := c.conn.Write(frameH2(0x4, 0x1, 0, nil)); err != nil {
				return false, err
			}
			return allowed, nil
		case 0x7: // GOAWAY
			return false, fmt.Errorf("GOAWAY before settings: %x", payload)
		case 0x1, 0x2: // HEADERS, PRIORITY - ignore before our stream exists
		case 0x8: // WINDOW_UPDATE
		}
		if ctx.Err() != nil {
			return false, ctx.Err()
		}
	}
}

// shape selects among the request shapes this edge has been asked to accept. The point of having
// more than one is that "400" is the edge refusing the request rather than the carrier dropping it,
// and each shape rules out a different way of writing the request wrong.
type shape struct {
	authority string
	path      string
	proto     string
	withProto bool
}

var shapes = []shape{
	{authority: connectAuth, path: "/", proto: "cf-connect-ip", withProto: true},
}

// connectVariant names one way of writing the extended CONNECT. The edge answers a header block it
// does not accept with a stream reset carrying PROTOCOL_ERROR rather than a status, so the only way to
// find the shape it wants is to send each one and read the verdict.
type connectVariant struct {
	name      string
	withProto bool // :protocol pseudo-header
	protoHdr  bool // cf-connect-proto header
	capsule   bool // capsule-protocol header
	pq        bool // pq-enabled header
	encoding  bool // accept-encoding header
	authorityFirst bool // :authority before :method
	authority string
	path      string
	scheme    string
	endStream bool
}

// acceptedShape is the request shape the negotiation settled on. Each capsule form is re-tested on its
// own tunnel through the same shape, because the shape and the framing are independent faults and a
// tunnel opened with the wrong one measures nothing about the other.
var acceptedShapeName = ""

func acceptedShape() string {
	if acceptedShapeName == "" {
		acceptedShapeName = "measured shape"
	}
	return acceptedShapeName
}

func shapeByName(name string) connectVariant {
	for _, v := range variants() {
		if v.name == name {
			return v
		}
	}
	return variants()[0]
}

func variants() []connectVariant {
	auth := connectAuth
	return []connectVariant{
		// The registration names post_quantum as enabled_with_downgrades, and the working client sends
		// pq-enabled on this carrier. Whether that header decides whether the tunnel routes, or only
		// whether it may negotiate a stronger key exchange, is what these two ask of the edge: the same
		// accepted shape with it and without it.
		// Transcribed field for field from a client that was measured carrying traffic over this carrier,
		// in the order it writes them: :authority first, then :method, then cf-connect-proto, then
		// pq-enabled, then accept-encoding. The last two were absent from the shape this probe settled on
		// by elimination, and elimination cannot tell the difference between a header that is not needed
		// and one that was never present in the only sample that worked.
		{name: "measured shape", withProto: false, protoHdr: true, capsule: false, pq: true, encoding: true, authorityFirst: true, authority: auth + ":443", path: "", scheme: ""},
		// This one is a transcription of what the working client puts on the wire, field for field: a
		// plain CONNECT with no :scheme and no :path, an authority carrying the default port, and the
		// protocol named in the cf-connect-proto header alone. Every other variant here adds something
		// RFC 8441 asks for and this edge does not want, which is what a stream reset means.
		{name: "protocol+cf-connect-proto", withProto: true, protoHdr: true, capsule: true, authority: auth, path: "/", scheme: "https"},
		{name: "protocol only", withProto: true, protoHdr: false, capsule: false, authority: auth, path: "/", scheme: "https"},
		{name: "cf-connect-proto only", withProto: false, protoHdr: true, capsule: true, authority: auth, path: "/", scheme: "https"},
		{name: "no scheme or path", withProto: true, protoHdr: true, capsule: true, authority: auth, path: "", scheme: ""},
		{name: "authority with port", withProto: true, protoHdr: true, capsule: true, authority: auth + ":443", path: "/", scheme: "https"},
		{name: "end stream set", withProto: true, protoHdr: true, capsule: true, authority: auth, path: "/", scheme: "https", endStream: true},
	}
}

// sendConnect writes the extended CONNECT on stream 1 and reads the response headers, without
// END_STREAM: the capsule stream needs this side of the stream to stay open for the tunnel's life.
func (c *h2raw) sendConnect(ctx context.Context, v connectVariant) (int, map[string]string, error) {
	var block bytes.Buffer
	enc := hpack.NewEncoder(&block)
	// :method, :scheme and :path are literal-without-indexing against the static table names, and each
	// is followed by its value. Sending the name index alone would leave the value to be read out of
	// the next byte, which is how a well-formed-looking header block decodes into nonsense.
	if v.authorityFirst {
		enc.WriteField(hpack.HeaderField{Name: ":authority", Value: v.authority})
		enc.WriteField(hpack.HeaderField{Name: ":method", Value: "CONNECT"})
	} else {
		enc.WriteField(hpack.HeaderField{Name: ":method", Value: "CONNECT"})
		if v.scheme != "" {
			enc.WriteField(hpack.HeaderField{Name: ":scheme", Value: v.scheme})
		}
		enc.WriteField(hpack.HeaderField{Name: ":authority", Value: v.authority})
		if v.path != "" {
			enc.WriteField(hpack.HeaderField{Name: ":path", Value: v.path})
		}
	}
	// The protocol name travels in the regular header cf-connect-proto rather than in the :protocol
	// pseudo-header, which is what the working client writes and what this edge is measured against.
	// Both are offered because the two readings are incompatible and only the edge can say which it
	// takes - a peer that reads RFC 8441 exactly ignores cf-connect-proto, and one that reads the
	// header form has no use for the pseudo-header.
	if v.withProto {
		enc.WriteField(hpack.HeaderField{Name: ":protocol", Value: "cf-connect-ip"})
	}
	if v.protoHdr {
		enc.WriteField(hpack.HeaderField{Name: "cf-connect-proto", Value: "cf-connect-ip"})
	}
	if v.capsule {
		enc.WriteField(hpack.HeaderField{Name: "capsule-protocol", Value: "?1"})
	}
	if v.pq {
		enc.WriteField(hpack.HeaderField{Name: "pq-enabled", Value: "false"})
	}
	if v.encoding {
		enc.WriteField(hpack.HeaderField{Name: "accept-encoding", Value: "gzip"})
	}

	c.sid = 1
	blockBytes := block.Bytes()
	// The block is printed before it goes out. A PROTOCOL_ERROR from this edge is a statement about the
	// bytes that were sent, so being able to read them back is the difference between fixing the
	// encoding and guessing at it.
	fmt.Printf("  HEADERS block (%d bytes): %x\n", len(blockBytes), blockBytes)
	flags := byte(0x4) // END_HEADERS
	if v.endStream {
		flags |= 0x1
	}
	if _, err := c.conn.Write(frameH2(0x1, flags, c.sid, blockBytes)); err != nil {
		return 0, nil, err
	}
	for {
		if err := c.conn.SetReadDeadline(time.Now().Add(25 * time.Second)); err != nil {
			return 0, nil, err
		}
		typ, _, stream, payload, err := c.readFrame()
		if err != nil {
			return 0, nil, err
		}
		switch typ {
		case 0x1: // HEADERS
			if stream != c.sid {
				continue
			}
			d := hpack.NewDecoder(4096, nil)
			fields, err := d.DecodeFull(payload)
			if err != nil {
				return 0, nil, fmt.Errorf("header decode: %w", err)
			}
			h := map[string]string{}
			for _, f := range fields {
				if f.Name == ":status" {
					h[f.Name] = f.Value
				} else {
					h[f.Name] = f.Value
				}
			}
			status := 0
			fmt.Sscanf(h[":status"], "%d", &status)
			return status, h, nil
		case 0x3: // RST_STREAM
			var code uint32
			if len(payload) >= 4 {
				code = binary.BigEndian.Uint32(payload)
			}
			return 0, nil, fmt.Errorf("RST_STREAM stream=%d code=%d", stream, code)
		case 0x7: // GOAWAY
			return 0, nil, fmt.Errorf("GOAWAY: %x", payload)
		}
	}
}

// writeData writes a DATA frame on the tunnel stream.
func (c *h2raw) writeData(b []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	_, err := c.conn.Write(frameH2(0x0, 0, c.sid, b))
	return err
}

func frameH2(typ, flags byte, stream uint32, payload []byte) []byte {
	out := make([]byte, 9, 9+len(payload))
	ln := len(payload)
	out[0], out[1], out[2] = byte(ln>>16), byte(ln>>8), byte(ln)
	out[3], out[4] = typ, flags
	out[5], out[6], out[7], out[8] = byte(stream>>24), byte(stream>>16), byte(stream>>8), byte(stream)
	return append(out, payload...)
}

// answerPing echoes a PING. Not answering one is not fatal but the edge may stop sending, and the
// frame log here would then show silence rather than an answer, which reads like a tunnel that carries
// nothing.
func (c *h2raw) answerPing(payload []byte) {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	c.conn.Write(frameH2(0x6, 0x1, 0, payload))
}

func head(b []byte, n int) []byte {
	if len(b) < n {
		n = len(b)
	}
	return b[:n]
}

func h2setting(id uint16, v uint32) []byte {
	b := make([]byte, 6)
	binary.BigEndian.PutUint16(b, id)
	binary.BigEndian.PutUint32(b[2:], v)
	return b
}

// API_ADDR pins the enrolment API to the address Cloudflare's own resolver returns.
//
// The system resolver answers api.cloudflareclient.com with 8.47.69.0 and 8.6.112.0 on this network,
// which are Alibaba Cloud addresses rather than Cloudflare's. The connection then completes TLS
// against something that is not the API and is closed before a response byte, which is the bare EOF
// the registration reports. Pinning the address and keeping the name for SNI and the Host header is
// what separates the real endpoint from the substituted one.
const API_ADDR = "104.16.24.84:443"

func main() {
	if v := os.Getenv("EDGES"); v != "" {
		edges = splitList(v)
	}
	primeCtx, primeCancel := context.WithCancel(context.Background())
	defer primeCancel()
	srcIP, cert, err := enrol()
	if err != nil {
		fmt.Println("enrol failed:", err)
		return
	}
	fmt.Println("registered, tunnel address", srcIP)
	fmt.Println("registration names endpoint engage.cloudflareclient.com:2408 (v4 162.159.192.5:0)")

	// The registration names an endpoint of its own, on UDP. A Connect-IP tunnel reached over the
	// HTTP/2 carrier is a different route to the edge than the one that endpoint is, and whether the
	// edge will forward for an identity that has never been seen on the route the registration named is
	// not established. Touching it first costs one datagram and removes the question.
	if os.Getenv("PRIME_ENDPOINT") == "1" {
		primeEdge(primeCtx, "162.159.192.5", 2408)
	}

	for _, addr := range edges {
		fmt.Printf("\n=== edge %s ===\n", addr)
		if err := runEdge(addr, srcIP, cert); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}
}

// regId and regToken are kept so the account can be read back after registration. The account endpoint is
// a separate call from the registration, and it is where the edge's own decision to route for an
// identity is reflected - so a registration that succeeds while the account behind it is not provisioned
// is worth seeing rather than inferring from a silent tunnel.
var (
	regId    string
	regToken string
)

// fetchAccount reads the account object for a registration.
func fetchAccount(id, token string) (map[string]any, error) {
	addr := API_ADDR
	if v := os.Getenv("API_ADDR"); v != "" {
		addr = v
	} else if v, err := resolve4(apiHost); err == nil {
		addr = net.JoinHostPort(v, "443")
	}
	client := &http.Client{
		Timeout: 20 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				ServerName:         apiSNIMask,
				NextProtos:         []string{"http/1.1"},
				InsecureSkipVerify: true,
			},
			ForceAttemptHTTP2: false,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", addr)
			},
		},
	}
	req, err := http.NewRequest("GET", "https://"+apiHost+"/"+apiVer+"/reg/"+id+"/account", nil)
	if err != nil {
		return nil, err
	}
	req.Host = apiHost
	req.Header.Set("User-Agent", "WARP for Android")
	req.Header.Set("CF-Client-Version", "a-6.35-4471")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("account %d: %s", resp.StatusCode, string(raw))
	}
	var out map[string]any
	if json.Unmarshal(raw, &out) != nil {
		return nil, fmt.Errorf("account body unreadable: %s", string(raw))
	}
	return out, nil
}

// primeEdge sends one datagram to the endpoint the registration named. UDP to this edge is filtered on
// this network, so a silent answer is the expected result and the point is only that the datagram was
// sent from the same source address the tunnel will use.
func primeEdge(ctx context.Context, host string, port int) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		fmt.Println("  prime endpoint: cannot open a socket:", err)
		return
	}
	defer conn.Close()
	conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	addr := &net.UDPAddr{IP: net.ParseIP(host).To4(), Port: port}
	// A long header with an unknown version is enough to be recognised as QUIC and dropped with a
	// version negotiation packet if anything is listening.
	pkt := append([]byte{0xc0}, []byte{0xba, 0xba, 0xba, 0xba}...)
	pkt = append(pkt, 0x08)
	pkt = append(pkt, make([]byte, 8)...)
	pkt = append(pkt, 0x08)
	pkt = append(pkt, make([]byte, 8)...)
	pkt = append(pkt, make([]byte, 120)...)
	if _, err := conn.WriteToUDP(pkt, addr); err != nil {
		fmt.Printf("  prime endpoint %s:%d: %v\n", host, port, err)
		return
	}
	fmt.Printf("  prime endpoint %s:%d: %d bytes sent\n", host, port, len(pkt))
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 2048)
	if n, from, err := conn.ReadFromUDP(buf); err == nil {
		fmt.Printf("  prime endpoint answered %d bytes from %s\n", n, from)
	} else {
		fmt.Println("  prime endpoint: no answer, which is what the filtered UDP path gives")
	}
}

// runEdge opens the extended CONNECT on one edge and then tries to move one IP packet through it.
func runEdge(addr string, srcIP net.IP, cert tls.Certificate) error {
	ctx, cancel := context.WithTimeout(context.Background(), 75*time.Second)
	defer cancel()

	sw := time.Now()
	c, err := dialH2Raw(ctx, addr, cert)
	if err != nil {
		return err
	}
	defer c.conn.Close()
	fmt.Printf("  tls ok in %dms alpn=\"h2\"\n", time.Since(sw).Milliseconds())

	allowed, err := c.awaitSettings(ctx)
	if err != nil {
		return fmt.Errorf("settings: %w", err)
	}
	fmt.Printf("  peer settings read in %dms, ENABLE_CONNECT_PROTOCOL=%v\n", time.Since(sw).Milliseconds(), allowed)

	// Each variant is asked in turn on a connection of its own. A reset leaves the stream unusable, so
	// reusing one connection would make every variant after the first answer about the wrong stream.
	var status int
	var hdrs map[string]string
	var winner string
	var lastErr error
	for _, v := range variants() {
		probe, err := dialH2Raw(ctx, addr, cert)
		if err != nil {
			fmt.Printf("  variant %-28s dial: %v\n", v.name, err)
			lastErr = err
			continue
		}
		probe.awaitSettings(ctx)
		status, hdrs, err = probe.sendConnect(ctx, v)
		if err != nil {
			fmt.Printf("  variant %-28s %v\n", v.name, err)
			lastErr = err
			probe.conn.Close()
			continue
		}
		fmt.Printf("  variant %-28s status %d\n", v.name, status)
		if status == 200 {
			winner = v.name
			acceptedShapeName = v.name
			c.conn.Close()
			break
		}
		lastErr = fmt.Errorf("variant %s gave status %d", v.name, status)
		probe.conn.Close()
	}
	if winner == "" {
		if lastErr == nil {
			lastErr = fmt.Errorf("no variant was answered")
		}
		return lastErr
	}
	fmt.Printf("  accepted shape: %s\n", winner)
	// The winning connection is gone, so the tunnel opens again on a fresh one with that shape.
	c2, err := dialH2Raw(ctx, addr, cert)
	if err != nil {
		return err
	}
	c2.awaitSettings(ctx)
	var v2 connectVariant
	for _, v := range variants() {
		if v.name == winner {
			v2 = v
		}
	}
	if status, _, err = c2.sendConnect(ctx, v2); err != nil || status != 200 {
		c2.conn.Close()
		return fmt.Errorf("second attempt with %s: status %d err %v", winner, status, err)
	}
	c.conn.Close()
	*c = *c2
	status = 200
	hdrs = nil
	if err != nil {
		return err
	}
	if status != 200 {
		return fmt.Errorf("CONNECT status %d headers=%v", status, hdrs)
	}
	fmt.Printf("  CONNECT 200 in %dms\n", time.Since(sw).Milliseconds())

	// A CONNECT that was answered with 200 is not yet a Connect-IP tunnel: a plain CONNECT answered
	// with 200 is just an opaque byte pipe, and an edge that opened one would relay an HTTP request
	// straight through. Sending one settles which kind of tunnel this is, and the answer decides whether
	// the capsule framing below is even the right thing to be sending.
	if os.Getenv("OPACHE_PROBE") == "1" {
		probeText := "GET / HTTP/1.1\r\nHost: " + connectAuth + "\r\nUser-Agent: probe\r\nConnection: close\r\n\r\n"
		fmt.Printf("  opaque probe: %d bytes of HTTP/1.1\n", len(probeText))
		if err := c.writeData([]byte(probeText)); err != nil {
			return fmt.Errorf("opaque probe write: %w", err)
		}
		deadline := time.Now().Add(20 * time.Second)
		for time.Now().Before(deadline) {
			c.conn.SetReadDeadline(time.Now().Add(8 * time.Second))
			typ, _, stream, payload, err := c.readFrame()
			if err != nil {
				fmt.Printf("  opaque probe read stopped: %v\n", err)
				break
			}
			fmt.Printf("  opaque probe frame type=0x%x stream=%d len=%d head=%q\n", typ, stream, len(payload), head(payload, 120))
			if typ == 0x0 || typ == 0x7 {
				break
			}
		}
		return nil
	}

	// The SYN. The edge answers with a SYN-ACK if the tunnel carries IP at all, which is the whole
	// question - a tunnel that reports up and moves nothing looks identical on screen to one that never
	// connected, and only this packet separates them.
	dstText := traceTarget
	if v := os.Getenv("TRACE_IP"); v != "" {
		dstText = v
	} else {
		v, err := resolve4("www.cloudflare.com")
		if err != nil {
			return fmt.Errorf("resolve trace target: %w", err)
		}
		dstText = v
	}
	dst := net.ParseIP(dstText).To4()
	if dst == nil {
		return fmt.Errorf("trace target %s is not an IPv4 address", dstText)
	}
	// The same SYN a working client was measured sending: port 80 rather than 443, the timestamp and
	// signature options, and a window in the same order of magnitude. A SYN to 443 with no options is a
	// valid packet, but the edge here is a front end that answers an ordinary browser's connection, and
	// matching what is known to be answered is the only way to tell a framing fault from a routing one.
	dport := uint16(443)
	if v := os.Getenv("SYN_PORT"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			dport = uint16(n)
		}
	}
	syn := buildSynWithOptions(srcIP.To4(), dst, 40001, dport)
	forms := capsuleForms(syn)
	if only := os.Getenv("CAPSULE_FORM"); only != "" {
		filtered := map[string][]byte{}
		for name, b := range forms {
			if name == only {
				filtered[name] = b
			}
		}
		if len(filtered) == 0 {
			return fmt.Errorf("no capsule form is named %q", only)
		}
		forms = filtered
	}
	// Each form gets its own tunnel. Sending all three into one stream was a fault in the probe's design
	// and not in the protocol: the edge parses the stream as a sequence of capsules, so a short-form
	// capsule followed by a generalised one is one malformed run rather than two attempts, and whichever
	// form followed a rejected one was never actually evaluated.
	// A pause between attempts. Four TLS handshakes to the same address in a couple of seconds drew a
	// connection timeout on the third, which reads as a carrier fault and is not one.
	time.Sleep(3 * time.Second)
	for name, b := range forms {
		fmt.Printf("  SYN as %-28s %d bytes %s -> %s:443\n", name, len(b), srcIP, dst)
		fmt.Printf("    %x\n", b)
		single, err := dialH2Raw(ctx, addr, cert)
		if err != nil {
			return err
		}
		single.awaitSettings(ctx)
		if _, _, err = single.sendConnect(ctx, shapeByName(acceptedShape())); err != nil {
			single.conn.Close()
			return err
		}
		if err := single.writeData(b); err != nil {
			single.conn.Close()
			return err
		}
		single.conn.SetReadDeadline(time.Now().Add(25 * time.Second))
		carried := false
		var acc []byte
		for !carried {
			typ, flags, stream, payload, err := single.readFrame()
			if err != nil {
				fmt.Printf("    -> nothing (%v)\n", err)
				break
			}
			if typ != 0x0 || stream != single.sid {
				fmt.Printf("    -> frame type=0x%x stream=%d len=%d\n", typ, stream, len(payload))
				continue
			}
			if flags&0x1 != 0 {
				fmt.Println("    -> the edge closed the tunnel")
				break
			}
			// A DATA frame is one packet. The reply carries no capsule header, so the whole payload is
			// the packet and its own total-length field is the check.
			if isBareIPv4(payload) {
				hl := int(payload[0]&0x0f) * 4
				fmt.Printf("    -> reply %d bytes, proto=%d %s -> %s", len(payload), payload[9],
					net.IP(payload[12:16]), net.IP(payload[16:20]))
				if payload[9] == 6 && len(payload) >= hl+20 {
					tc := payload[hl:]
					fmt.Printf(", sport=%d dport=%d flags=0x%04x",
						binary.BigEndian.Uint16(tc[0:2]),
						binary.BigEndian.Uint16(tc[2:4]),
						binary.BigEndian.Uint16(tc[12:14]))
				}
				fmt.Println()
				carried = true
				continue
			}
			acc = append(acc, payload...)
			fmt.Printf("    -> fragment %d bytes, head=%x\n", len(payload), head(payload, 24))
			if ctype, out, _, ok := takeCapsule(acc); ok {
				fmt.Printf("    -> capsule type=%d payload=%d bytes head=%x\n", ctype, len(out), head(out, 24))
				carried = true
			}
		}
		single.conn.Close()
		if carried {
			fmt.Printf("  RESULT: %s carried a packet\n", name)
			return nil
		}
	}

	// A second SYN, to a destination that is reachable over plain TCP from this host. The edge routes
	// by address, so if the first SYN is being dropped because that address refuses traffic from Cloudflare
	// and not because the framing is wrong, this one comes back and the two answers separate the causes.
	if v := os.Getenv("SECOND_DST"); v != "" {
		dst2 := net.ParseIP(v).To4()
		if dst2 != nil {
			for name, b := range capsuleForms(buildSyn(srcIP.To4(), dst2, 40002, 80)) {
				if err := c.writeData(b); err != nil {
					return fmt.Errorf("write second syn: %w", err)
				}
				fmt.Printf("  second SYN to %s:80 as %-28s %d bytes\n", dst2, name, len(b))
			}
		}
	}

	// Whatever comes back is read with a deadline rather than forever: a silent tunnel must produce a
	// verdict, not a hang.
	type got struct {
		pkt []byte
		err error
	}
	ch := make(chan got, 1)
	var once sync.Once
	go func() {
		pkt, err := readCapsuleRaw(c, 45*time.Second)
		once.Do(func() { ch <- got{pkt, err} })
	}()

	select {
	case g := <-ch:
		if g.err != nil {
			return fmt.Errorf("no capsule returned: %w", g.err)
		}
		fmt.Printf("  capsule back: %d bytes, ip proto=%d %s -> %s\n",
			len(g.pkt), g.pkt[9], net.IP(g.pkt[12:16]), net.IP(g.pkt[16:20]))
		if g.pkt[9] != 6 {
			return fmt.Errorf("edge returned a non-TCP packet")
		}
		fmt.Println("  RESULT: the tunnel carries IP packets over HTTP/2")
		return nil
	case <-time.After(50 * time.Second):
		return fmt.Errorf("edge accepted the CONNECT and returned nothing for a SYN")
	}
}

// buildCapsule wraps an IP packet in a Connect-IP DATAGRAM capsule. The layout is the one RFC 9297
// defines for the capsule stream and the one the H3 datagram form carries without a length: type,
// length, context id, payload.
func buildCapsule(ip []byte) []byte {
	// Two forms, and which one is in use is a decision rather than a belief. The generalised form is
	// the one RFC 9297 defines for the capsule stream: a type, a length, and a payload that leads with
	// the context id. The short form is a single zero byte in front of the packet, which is what this
	// edge takes on the HTTP/3 datagram carrier.
	//
	// The generalised form was written first and appeared to fail - the edge returned nothing - but it was
	// never tested with a packet that was itself correct: the SYN sent alongside it carried a zeroed IP
	// checksum and a wrong total length, and either one alone is enough for the edge to drop a packet
	// without saying so. Both forms are therefore still live until one is measured with a sound packet.
	if os.Getenv("CAPSULE_FORM") == "generalised" {
		buf := make([]byte, 0, 8+len(ip))
		buf = appendVarint(buf, 0)                 // capsule type: DATAGRAM
		buf = appendVarint(buf, uint64(len(ip)+1)) // payload length: context id plus the packet
		buf = appendVarint(buf, 0)                 // context id 0
		return append(buf, ip...)
	}
	// A single zero byte in front of the packet and nothing else: the short form this edge takes on the
	// H3 datagram carrier, where the same zero byte is read as the capsule type and the packet follows
	// directly. The generalised form with a type, a length and a context id was measured on this edge
	// and answered with silence - the stream stayed open and no frame of any kind came back - while the
	// short form is what the working client writes.
	return append([]byte{0x00}, ip...)
}

// capsuleForms returns every framing that could plausibly be what this edge reads, each wrapping the
// same packet.
//
// They are tried on one tunnel rather than one tunnel each because the tunnel is silent: a form that is
// wrong costs nothing to have tried alongside a form that is right, and separating them costs a full
// connect each time. The three forms are the short one, the generalised one with the context id left in
// front of the payload, and the generalised one with it stripped - the last is what the working
// Connect-IP client writes, and the difference between it and the second is a single byte.
func capsuleForms(ip []byte) map[string][]byte {
	// The generalised form, and it is the one this edge reads. A working client was instrumented and its
	// bytes transcribed: every capsule it writes is a type byte of 0, a length byte, a context id of 0,
	// then the IP packet - `00 40 45 00 00 40 ...`, where 0x40 is 64, the packet plus the context id.
	withCtx := appendVarint(nil, 0)
	withCtx = appendVarint(withCtx, uint64(len(ip)+1))
	withCtx = appendVarint(withCtx, 0)
	withCtx = append(withCtx, ip...)
	short := append([]byte{0x00}, ip...)
	noCtx := appendVarint(nil, 0)
	noCtx = appendVarint(noCtx, uint64(len(ip)))
	noCtx = append(noCtx, ip...)
	return map[string][]byte{"generalised with context id": withCtx, "short": short, "generalised without context id": noCtx}
}

// readCapsuleRaw reads DATA frames on the tunnel stream until a DATAGRAM capsule is complete and
// returns the IP packet inside it.
func readCapsuleRaw(c *h2raw, budget time.Duration) ([]byte, error) {
	ch := make(chan []byte, 1)
	errCh := make(chan error, 1)
	go func() {
		var acc []byte
		for {
			if err := c.conn.SetReadDeadline(time.Now().Add(budget)); err != nil {
				errCh <- err
				return
			}
			typ, flags, stream, payload, err := c.readFrame()
			if err != nil {
				errCh <- err
				return
			}
			switch typ {
			case 0x0: // DATA
				if stream != c.sid {
					fmt.Printf("  [frame] DATA on unexpected stream %d, %d bytes\n", stream, len(payload))
					continue
				}
				// The last DATA frame on the stream arrives with END_STREAM, which means the edge closed
				// the tunnel rather than that a packet ended there.
				if flags&0x1 != 0 {
					errCh <- fmt.Errorf("edge ended the tunnel stream")
					return
				}
				// A bare IPv4 packet, with no capsule header in front of it.
				//
				// This is the inbound shape this edge actually sends, measured from a client that carries
				// traffic over the same carrier with the same registration flow: every DATA frame on the
				// tunnel stream began with the IP version nibble, and the total-length field inside the
				// header matched the frame length. The parser here used to assume a capsule first, so on a
				// reply it consumed the version byte as a capsule type and the IHL byte as a length and
				// handed the packet back shifted by two - which never parses and never reaches the socket.
				// A tunnel that opens, accepts capsules and then delivers nothing upstream is exactly what
				// that looks like from the app, and it is what was measured for a day.
				//
				// A generalised capsule is still accepted when one arrives, because a peer that sends one is
				// legitimate; it is simply not what comes back from this edge.
				if isBareIPv4(payload) {
					select {
					case ch <- payload:
					default:
					}
					return
				}
				acc = append(acc, payload...)
				fmt.Printf("  [frame] DATA stream=%d flags=0x%x len=%d head=%x\n", stream, flags, len(payload), head(payload, 48))
			case 0x8: // WINDOW_UPDATE - the edge wants more credit, not more parsing
				fmt.Printf("  [frame] WINDOW_UPDATE stream=%d len=%d\n", stream, len(payload))
				continue
			case 0x3:
				fmt.Printf("  [frame] RST_STREAM stream=%d payload=%x\n", stream, payload)
				errCh <- fmt.Errorf("edge reset the tunnel stream: %x", payload)
				return
			case 0x7:
				fmt.Printf("  [frame] GOAWAY payload=%x\n", payload)
				errCh <- fmt.Errorf("edge sent GOAWAY: %x", payload)
				return
			case 0x6: // PING
				c.answerPing(payload)
				continue
			case 0x1, 0x2: // HEADERS, PRIORITY
				fmt.Printf("  [frame] type=0x%x stream=%d len=%d payload=%x\n", typ, stream, len(payload), payload)
				continue
			default:
				fmt.Printf("  [frame] type=0x%x stream=%d len=%d\n", typ, stream, len(payload))
				continue
			}
			for {
				ctype, payloadOut, used, ok := takeCapsule(acc)
				if !ok {
					break
				}
				acc = acc[used:]
				if ctype != 0 || len(payloadOut) == 0 {
					continue
				}
				// The context id leads the payload; the packet is what follows it.
				_, idLen := readVarint(payloadOut)
					if idLen == 0 || idLen >= len(payloadOut) || payloadOut[idLen]>>4 != 4 {
						// No context id in front of it, so the payload is the packet.
						select {
						case ch <- payloadOut:
						default:
						}
						return
				}
				select {
				case ch <- payloadOut[idLen:]:
				default:
				}
				return
			}
		}
	}()
	select {
	case p := <-ch:
		return p, nil
	case e := <-errCh:
		return nil, e
	case <-time.After(budget):
		return nil, fmt.Errorf("nothing in %s", budget)
	}
}


// isBareIPv4 reports whether a DATA payload is an IP packet with nothing in front of it.
//
// The checks are the header's own: the version nibble, an IHL that lands inside the buffer, and a total
// length that matches what arrived. A packet whose own length field is self-consistent is taken at its
// word, which is the only authority available - a capsule header in front of it would make the version
// nibble something other than 4.
func isBareIPv4(b []byte) bool {
	if len(b) < 20 || b[0]>>4 != 4 {
		return false
	}
	hl := int(b[0]&0x0f) * 4
	if hl < 20 || hl > 60 || len(b) < hl {
		return false
	}
	return int(binary.BigEndian.Uint16(b[2:4])) == len(b)
}

func takeCapsule(buf []byte) (uint64, []byte, int, bool) {
	// A single IP packet with no capsule header at all. Some carriers put the packet straight on the
	// stream with no type and no length, which is indistinguishable from a length-prefixed frame only
	// by trying it: a first byte of 0x45 is an IPv4 version-and-header-length, a value no capsule type
	// takes, so the packet is returned as it stands.
	if len(buf) >= 20 && buf[0]>>4 == 4 {
		hl := int(buf[0]&0x0f) * 4
		if hl >= 20 && hl <= 60 && len(buf) >= hl {
			if tot := int(binary.BigEndian.Uint16(buf[2:4])); tot == len(buf) && tot >= hl {
				return 0, buf, len(buf), true
			}
		}
	}
	ctype, n1 := readVarint(buf)
	if n1 == 0 {
		return 0, nil, 0, false
	}
	plen, n2 := readVarint(buf[n1:])
	if n2 == 0 {
		return 0, nil, 0, false
	}
	hdr := n1 + n2
	total := hdr + int(plen)
	if total < hdr || len(buf) < total {
		return 0, nil, 0, false
	}
	return ctype, buf[hdr:total], total, true
}

func readVarint(b []byte) (uint64, int) {
	if len(b) == 0 {
		return 0, 0
	}
	n := 1 << (b[0] >> 6)
	if len(b) < n {
		return 0, 0
	}
	v := uint64(b[0] & 0x3f)
	for i := 1; i < n; i++ {
		v = v<<8 | uint64(b[i])
	}
	return v, n
}

func appendVarint(b []byte, v uint64) []byte {
	switch {
	case v < 1<<6:
		return append(b, byte(v))
	case v < 1<<14:
		return append(b, byte(v>>8)|0x40, byte(v))
	case v < 1<<30:
		return append(b, byte(v>>24)|0x80, byte(v>>16), byte(v>>8), byte(v))
	default:
		return append(b, byte(v>>56)|0xc0, byte(v>>48), byte(v>>40), byte(v>>32),
			byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	}
}


// buildSynWithOptions is the SYN this tunnel is measured sending: a 40-byte TCP header carrying the
// MSS, timestamp and signature options a real client sends, and a 60-byte packet overall. The options
// are not decoration - they are what makes the packet the size the tunnel's own MTU accounting expects,
// and a bare 40-byte SYN is a packet the edge has no path for.
func buildSynWithOptions(src, dst net.IP, sport, dport uint16) []byte {
	// MSS 1460, SACK permitted, timestamps, no-op, window scale 7 - 18 bytes, which pads out to a
	// 20-byte option block so the data offset is 10 and the packet is 60 bytes.
	opt := []byte{
		0x02, 0x04, 0x05, 0xb4, // MSS 1460
		0x04, 0x02, // SACK permitted
		0x08, 0x0a, 0x8a, 0xb4, 0x33, 0x17, 0xe5, 0x84, 0xa0, 0x00, 0x00, 0x00, 0x00, // timestamps
		0x01,       // no-op
		0x03, 0x03, // window scale 7, padded by the two zero bytes below
		0x00,
	}
	tcpLen := 20 + len(opt)
	p := make([]byte, 20+tcpLen)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	p[8] = 63
	p[9] = 6
	copy(p[12:16], src.To4())
	copy(p[16:20], dst.To4())

	binary.BigEndian.PutUint16(p[20:22], sport)
	binary.BigEndian.PutUint16(p[22:24], dport)
	// A real-looking sequence rather than a fixed one: an edge that filtered on a probe pattern would
	// see a client that always opens with sequence 1.
	binary.BigEndian.PutUint32(p[24:28], 0x6c884735)
	p[32] = byte(tcpLen / 4 << 4) // data offset, in 32-bit words
	binary.BigEndian.PutUint16(p[32:34], 0xa002) // SYN
	binary.BigEndian.PutUint16(p[34:36], 24704) // window
	copy(p[40:], opt)

	binary.BigEndian.PutUint16(p[10:12], 0)
	binary.BigEndian.PutUint16(p[10:12], checksum(p[0:20]))
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], p[12:16])
	copy(pseudo[4:8], p[16:20])
	pseudo[9] = 6
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(len(p)-20))
	binary.BigEndian.PutUint16(p[36:38], 0)
	full := append(append([]byte{}, pseudo...), p[20:]...)
	binary.BigEndian.PutUint16(p[36:38], checksum(full))
	return p
}

func buildSyn(src, dst net.IP, sport, dport uint16) []byte {
	p := make([]byte, 40)
	p[0] = 0x45
	// 20 bytes of IP header plus 20 of TCP. Writing 40 here is what the header carried in every earlier
	// run, and a receiver that checks the total length against what it was handed reads 20 trailing
	// bytes past the TCP header and discards the packet - silently, because a malformed packet is not an
	// error the edge reports back through the tunnel.
	binary.BigEndian.PutUint16(p[2:4], 20+20)
	p[8] = 64
	p[9] = 6
	copy(p[12:16], src.To4())
	copy(p[16:20], dst.To4())
	binary.BigEndian.PutUint16(p[20:22], sport)
	binary.BigEndian.PutUint16(p[22:24], dport)
	binary.BigEndian.PutUint32(p[24:28], 1)     // sequence 1
	binary.BigEndian.PutUint16(p[28:30], 0x5002) // data offset 5, SYN
	binary.BigEndian.PutUint16(p[30:32], 0xffff) // window
	// Both checksums, in the right order and with nothing zeroed in between.
	//
	// The earlier version zeroed the destination address before folding the header into the checksum,
	// which destroyed the value it had just computed: the packet left with an IPv4 checksum of 0x0000
	// and the edge dropped it without answering. A packet that arrives intact and is silently discarded
	// looks exactly like a tunnel that carries nothing, which is why this went unmeasured for as long
	// as it did. The IP header goes in with the checksum field at zero, the TCP header goes in with the
	// pseudo-header in front of it, and neither field is touched again afterwards.
	binary.BigEndian.PutUint16(p[10:12], 0)
	binary.BigEndian.PutUint16(p[10:12], checksum(p[0:20]))
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], p[12:16])
	copy(pseudo[4:8], p[16:20])
	pseudo[9] = 6
	binary.BigEndian.PutUint16(pseudo[10:12], 20)
	binary.BigEndian.PutUint16(p[34:36], 0)
	full := append(append([]byte{}, pseudo...), p[20:40]...)
	binary.BigEndian.PutUint16(p[34:36], checksum(full))
	return p
}

func checksum(b []byte) uint16 {
	var acc uint32
	for i := 0; i+1 < len(b); i += 2 {
		acc += uint32(b[i])<<8 | uint32(b[i+1])
	}
	if len(b)%2 == 1 {
		acc += uint32(b[len(b)-1]) << 8
	}
	for acc>>16 != 0 {
		acc = acc&0xffff + acc>>16
	}
	return ^uint16(acc)
}

func splitList(s string) []string {
	var out []string
	cur := ""
	for _, r := range s {
		if r == ',' {
			out = append(out, cur)
			cur = ""
			continue
		}
		cur += string(r)
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

// enrol performs the registration the MASQUE client needs: a P-256 key, POST /reg with its scalar,
// PATCH with the SPKI, then a bare self-signed certificate. The edge refuses a client that presents
// no certificate, so the certificate is not optional even though nothing verifies it.
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

	apiAddr := API_ADDR
	if v := os.Getenv("API_ADDR"); v != "" {
		apiAddr = v
	} else if v, err := resolve4(apiHost); err == nil {
		apiAddr = net.JoinHostPort(v, "443")
	}
	fmt.Println("api pinned to", apiAddr)
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			// HTTP/1.1 only, and that is measured: with h2 offered the enrolment POST completes TLS and
			// is then closed before a response byte, which Go reports as a bare EOF.
			//
			// The SNI is 1.1.1.1 rather than the API name. The filter on this network reads the SNI
			// extension and refuses the connection when it sees api.cloudflareclient.com, which arrives
			// as exactly the same EOF a failed request would. Carrying the mask makes the address and the
			// Host header stay the API's while the ClientHello names something the filter lets through.
			TLSClientConfig: &tls.Config{
				ServerName: apiSNIMask,
				NextProtos: []string{"http/1.1"},
				// The certificate follows the SNI, so a chain presented for 1.1.1.1 cannot validate against
				// a name that was never in it. The verification that matters here is that the connection
				// reached Cloudflare at all, which the pinned DoH address and the Host header together
				// establish; hostname checking against the substituted name would fail for the same reason
				// a working connection would.
				InsecureSkipVerify: true,
			},
			ForceAttemptHTTP2: false,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", apiAddr)
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
		var resp *http.Response
		for attempt := 1; attempt <= 8; attempt++ {
			resp, err = client.Do(req.Clone(context.Background()))
			if err == nil {
				break
			}
			if fresh, ferr := resolve4(apiHost); ferr == nil {
				apiAddr = net.JoinHostPort(fresh, "443")
				client.Transport.(*http.Transport).DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, "tcp", apiAddr)
				}
			}
			time.Sleep(time.Duration(attempt%4) * 400 * time.Millisecond)
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
	// The working client sends a 22-character base64 install id and a non-empty fcm token, and the
	// empty strings this probe used are the one difference from it left in the body. A registration the
	// API accepts is not the same as one the edge will honour, and an identity the edge does not know
	// is refused at the tunnel with a plain 400 before it ever looks at capsules.
	install := make([]byte, 16)
	rand.Read(install)
	installID := base64.URLEncoding.EncodeToString(install)
	if len(installID) > 22 {
		installID = installID[:22]
	}
	fcm := make([]byte, 32)
	rand.Read(fcm)
	fcmToken := base64.StdEncoding.EncodeToString(fcm)
	reg, err := call("POST", "/"+apiVer+"/reg", "", map[string]any{
		"fcm_token": fcmToken, "install_id": installID, "tos": "2024-06-01T00:00:00.000Z",
		"model": "PC", "type": "Android", "serial_number": fmt.Sprintf("%x", serial),
		"locale": "en_US", "region": "US", "warp_enabled": true,
		"key": base64.StdEncoding.EncodeToString(scalar)})
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("register: %w", err)
	}
	id, _ := reg["id"].(string)
	token, _ := reg["token"].(string)
	regId, regToken = id, token
	if id == "" || token == "" {
		return nil, tls.Certificate{}, fmt.Errorf("register: no id or token")
	}
	if _, err := call("PATCH", "/"+apiVer+"/reg/"+id, token, map[string]any{
		"key":      base64.StdEncoding.EncodeToString(spki),
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
	if ep, ok := endpointFromReg(reg); ok {
		fmt.Println("registration names endpoint", ep)
	}
	// The policy block decides whether an edge will route for an identity at all: always_include and
	// always_exclude name the addresses this account is and is not permitted to reach. A tunnel that
	// authenticates and carries nothing is what an account whose exclusions cover every destination
	// looks like, so the block is printed in full rather than reduced to a summary.
	if pol, ok := reg["policy"].(map[string]any); ok {
		raw, _ := json.MarshalIndent(pol, "", "  ")
		fmt.Println("registration policy:", string(raw))
	}
	// The account endpoint is what the edge consults when it decides whether to route for this identity.
	// A free account that has not accepted terms, or whose licence is not bound, authenticates and then
	// carries nothing - which is exactly what a tunnel that answers 200 and drops every packet looks like.
	if id, tok := regId, regToken; id != "" && tok != "" {
		if acct, err := fetchAccount(id, tok); err == nil && acct != nil {
			raw, _ := json.MarshalIndent(acct, "", "  ")
			fmt.Println("account:", string(raw))
		} else if err != nil {
			fmt.Println("account: not readable:", err)
		}
	}
	if cfg, ok := reg["config"].(map[string]any); ok {
		raw, _ := json.MarshalIndent(cfg, "", "  ")
		fmt.Println("registration config:", string(raw))
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

// endpointFromReg reports the endpoint the registration names. It is printed rather than used, so a
// disagreement between what the registration says and what the probe dialled is visible.
func endpointFromReg(reg map[string]any) (string, bool) {
	cfg, ok := reg["config"].(map[string]any)
	if !ok {
		return "", false
	}
	peers, ok := cfg["peers"].([]any)
	if !ok || len(peers) == 0 {
		return "", false
	}
	p, ok := peers[0].(map[string]any)
	if !ok {
		return "", false
	}
	ep, ok := p["endpoint"].(map[string]any)
	if !ok {
		return "", false
	}
	host, _ := ep["host"].(string)
	v4, _ := ep["v4"].(string)
	policy, _ := ep["v4"].(string)
	_ = policy
	return fmt.Sprintf("%s (v4 %s)", host, v4), true
}

// resolve4 goes through DoH rather than the system resolver, because a plain lookup is exactly what
// fails on the networks this client has to survive.
func resolve4(name string) (string, error) {
	var lastErr error
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query", "https://9.9.9.9/dns-query"} {
		req, err := http.NewRequest("GET", r+"?name="+name+"&type=A", nil)
		if err != nil {
			continue
		}
		req.Header.Set("Accept", "application/dns-json")
		cl := &http.Client{Timeout: 8 * time.Second}
		resp, err := cl.Do(req)
		if err != nil {
			lastErr = err
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
		if json.Unmarshal(raw, &out) != nil {
			continue
		}
		for _, a := range out.Answer {
			if a.Type == 1 {
				return a.Data, nil
			}
		}
		lastErr = fmt.Errorf("no A record in %s", string(raw))
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no resolver answered")
	}
	return "", lastErr
}
