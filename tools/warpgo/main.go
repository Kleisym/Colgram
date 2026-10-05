// warpverdict measures Cloudflare WARP the only way that settles it: it opens a MASQUE tunnel,
// puts a real TLS client inside it, and reads the warp= line out of Cloudflare's own trace.
//
// This is the port of the Python measurement that first produced warp=on. It exists because the
// device leg needs a client that runs on the phone, and because the device's own client never
// completed a handshake that this code completes against the same edge.
//
// Nothing here touches host networking: the UDP socket is ordinary, and everything it carries goes
// to the MASQUE edge on 443.
package main

import "strings"

var _ = strings.TrimSpace

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

type peer struct {
	tun      *tunnel
	srcIP    net.IP
	dstIP    net.IP
	srcPort  uint16
	dstPort  uint16
	seq      uint32
	ack      uint32
	inbuf    []byte
	conn     *net.TCPConn
	closed   bool
	connected chan struct{}
	done     chan struct{}
}

func checksum(b []byte) uint16 {
	var sum uint32
	if len(b)%2 == 1 {
		b = append(b, 0)
	}
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(b[i])<<8 | uint32(b[i+1])
	}
	for sum>>16 != 0 {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return ^uint16(sum)
}

func ipv4Packet(src, dst net.IP, payload []byte) []byte {
	total := 20 + len(payload)
	hdr := make([]byte, 20)
	hdr[0] = 0x45
	binary.BigEndian.PutUint16(hdr[2:4], uint16(total))
	binary.BigEndian.PutUint16(hdr[4:6], 0x4321)
	hdr[8] = 64
	hdr[9] = 17
	copy(hdr[12:16], src.To4())
	copy(hdr[16:20], dst.To4())
	binary.BigEndian.PutUint16(hdr[10:12], checksum(hdr))
	return append(hdr, payload...)
}

// emit builds a TCP segment with a real checksum. The edge forwards on real TCP, so a wrong
// checksum here is a silently dropped connection.
func (p *peer) emit(payload []byte, flags uint16) {
	pseudo := make([]byte, 0, 12+len(payload))
	pseudo = append(pseudo, p.srcIP.To4()...)
	pseudo = append(pseudo, p.dstIP.To4()...)
	pseudo = append(pseudo, 0, 6)
	length := make([]byte, 2)
	binary.BigEndian.PutUint16(length, uint16(20+len(payload)))
	pseudo = append(pseudo, length...)

	seg := make([]byte, 20)
	binary.BigEndian.PutUint16(seg[0:2], p.srcPort)
	binary.BigEndian.PutUint16(seg[2:4], p.dstPort)
	binary.BigEndian.PutUint32(seg[4:8], p.seq)
	binary.BigEndian.PutUint32(seg[8:12], p.ack)
	seg[12] = 5 << 4
	binary.BigEndian.PutUint16(seg[12:14], flags)
	binary.BigEndian.PutUint16(seg[14:16], 64240)
	seg = append(seg, payload...)

	binary.BigEndian.PutUint16(seg[16:18], checksum(append(pseudo, seg...)))
	p.tun.sendIP(ipv4Packet(p.srcIP, p.dstIP, seg))
}

func (p *peer) feed(ip []byte) {
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
	if flags&0x02 != 0 {
		if flags&0x10 != 0 {
			p.ack = seq + 1
			p.emit(nil, 0x10)
			close(p.connected)
		}
		return
	}
	if len(payload) > 0 {
		p.ack = seq + uint32(len(payload))
		p.inbuf = append(p.inbuf, payload...)
	}
	if flags&0x01 != 0 {
		p.ack = seq + uint32(len(payload)) + 1
		p.emit(nil, 0x11)
		if !p.closed {
			p.closed = true
			if p.conn != nil {
				p.conn.Write(p.inbuf)
				p.conn.Close()
				close(p.done)
			}
		}
		return
	}
	p.emit(nil, 0x10)
	if len(payload) > 0 && p.conn != nil {
		p.conn.Write(p.inbuf)
		p.inbuf = p.inbuf[:0]
	}
}

func enrol() (net.IP, *tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, nil, err
	}
	scalar := make([]byte, 32)
	key.D.FillBytes(scalar)

	client := &http.Client{Timeout: 25 * time.Second}
	post := func(path, token string, body map[string]any) (map[string]any, error) {
		buf, _ := json.Marshal(body)
		req, err := http.NewRequest("POST", "https://"+apiHost+path, bytes.NewReader(buf))
		if err != nil {
			return nil, err
		}
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
		var out map[string]any
		json.Unmarshal(raw, &out)
		if resp.StatusCode >= 400 {
			return nil, fmt.Errorf("%s %s: %d %s", "POST", path, resp.StatusCode, string(raw))
		}
		return out, nil
	}

	reg, err := post("/"+apiVer+"/reg", "", map[string]any{
		"fcm_token": "", "install_id": "", "tos": "2024-06-01T00:00:00.000Z",
		"model": "PC", "type": "Android", "serial_number": randomID(),
		"locale": "en_US", "region": "US", "warp_enabled": true,
		"key": base64.StdEncoding.EncodeToString(scalar)})
	if err != nil {
		return nil, nil, fmt.Errorf("register: %w", err)
	}
	id, _ := reg["id"].(string)
	token, _ := reg["token"].(string)
	if id == "" || token == "" {
		return nil, nil, fmt.Errorf("register: no id/token in response")
	}
	if _, err := post("/"+apiVer+"/reg/"+id, token, map[string]any{
		"key": base64.StdEncoding.EncodeToString(spki),
		"key_type": "secp256r1", "tun_type": "masque"}); err != nil {
		return nil, nil, fmt.Errorf("enrol: %w", err)
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
	// refused, and the failure looks like an unexplained handshake reset.
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, fmt.Errorf("certificate: %w", err)
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, nil, err
	}
	return net.ParseIP(addr).To4(), &pair, nil
}

func randomID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

type tunnel struct {
	conn      quic.Connection
	h3        *http3.Connection
	stream    quic.Stream
	srcIP     net.IP
	srcPort   uint16
	peers     map[string]*peer
	ready     chan struct{}
	readyOnce bool
}

func (t *tunnel) sendIP(raw []byte) {
	if t.h3 == nil || t.stream == nil {
		return
	}
	t.h3.SendDatagram(t.stream, raw)
}

func (t *tunnel) dispatch(ip []byte) {
	if len(ip) < 40 || ip[9] != 6 {
		return
	}
	sport := binary.BigEndian.Uint16(ip[20:22])
	dport := binary.BigEndian.Uint16(ip[22:24])
	key := fmt.Sprintf("%d:%d", sport, dport)
	if p, ok := t.peers[key]; ok {
		p.feed(ip)
	}
}

func (t *tunnel) connect(p *peer) error {
	_, port, err := net.SplitHostPort("127.0.0.1:0")
	if err != nil {
		return err
	}
	_ = port

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	laddr := listener.Addr().(*net.TCPAddr)
	p.srcPort = uint16(laddr.Port)
	t.peers[fmt.Sprintf("%d:%d", p.dstPort, p.srcPort)] = p

	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		tcpConn := conn.(*net.TCPConn)
		p.conn = tcpConn
		go func() {
			buf := make([]byte, 32*1024)
			for {
				n, err := tcpConn.Read(buf)
				if n > 0 {
					p.emit(buf[:n], 0x18)
				}
				if err != nil {
					if !p.closed {
						p.closed = true
						close(p.done)
					}
					return
				}
			}
		}()
	}()

	p.emit(nil, 0x02)
	p.seq++

	go func() {
		select {
		case <-p.connected:
	case <-time.After(20 * time.Second):
			t.peers[fmt.Sprintf("%d:%d", p.dstPort, p.srcPort)] = nil
		}
	}()

	select {
	case <-p.connected:
		return nil
	case <-time.After(20 * time.Second):
		return fmt.Errorf("tcp handshake through the tunnel did not complete")
	}
}

func measure() (string, error) {
	srcIP, cert, err := enrol()
	if err != nil {
		return "", err
	}
	fmt.Println("session address :", srcIP.String())

	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(edgeIP, "443"))
	if err != nil {
		return "", err
	}
	udpAddr, ok := addr.(*net.UDPAddr)
	if !ok {
		return "", fmt.Errorf("not a udp address")
	}
	udpConn, err := net.DialUDP("udp", nil, udpAddr)
	if err != nil {
		return "", err
	}
	defer udpConn.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()

	tun := &tunnel{
		srcIP:   srcIP,
		srcPort: 40000,
		peers:   make(map[string]*peer),
		ready:   make(chan struct{}),
	}

	tr := &quic.Transport{Connection: make([]byte, 0)}
	_ = tr

	conn, err := quic.DialAddr(ctx, net.JoinHostPort(edgeIP, "443"),
		&quic.Config{
			ServerName:            edgeSNI,
			NextProtos:            []string{"h3"},
			InsecureSkipVerify:    true,
			MaxIncomingStreams:    8,
			MaxIncomingUniStreams: 8,
			EnableDatagrams:       true,
			TLSConfig: &tls.Config{
				InsecureSkipVerify: true,
				NextProtos:         []string{"h3"},
				Certificates:       []tls.Certificate{*cert},
			},
		}, udpConn)
	if err != nil {
		return "", fmt.Errorf("quic dial: %w", err)
	}
	defer conn.CloseWithError(0, "done")
	tun.conn = conn

	h3 := http3.NewClientConn(tun.conn)
	tun.h3 = h3

	respCh := make(chan *http.Response, 1)
	datagramCh := make(chan []byte, 4096)
	go func() {
		for {
			data, err := h3.AcceptDatagram(context.Background())
			if err != nil {
				return
			}
			if len(data) < 21 || data[0] != 0x00 {
				continue
			}
			select {
			case datagramCh <- data[1:]:
			default:
			}
		}
	}()

	go func() {
		for {
			select {
			case ip := <-datagramCh:
				tun.dispatch(ip)
			case <-ctx.Done():
				return
			}
		}
	}()

	req, err := http.NewRequest("CONNECT", "https://cloudflareaccess.com/",
		bytes.NewReader(nil))
	if err != nil {
		return "", err
	}
	req = req.WithContext(ctx)
	req.Proto = "HTTP/3"
	req.Header.Set(":protocol", "cf-connect-ip")
	req.Header.Set(":scheme", "https")
	req.Header.Set(":path", "/")
	req.Header.Set(":authority", "cloudflareaccess.com")
	req.Header.Set("capsule-protocol", "?1")
	req.Header.Set("cf-connect-proto", "cf-connect-ip")
	req.Header.Set("pq-enabled", "false")

	stream, err := h3.OpenRequestStream(ctx, req)
	if err != nil {
		return "", fmt.Errorf("open request stream: %w", err)
	}
	tun.stream = stream
	req.Write(stream)

	resp, err := h3.ReadResponse(stream, req)
	if err != nil {
		return "", fmt.Errorf("read response: %w", err)
	}
	fmt.Println("CONNECT status  :", resp.Status)
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("CONNECT refused with %d", resp.StatusCode)
	}

	ips, err := net.LookupIP(traceHost)
	if err != nil || len(ips) == 0 {
		return "", fmt.Errorf("resolve %s: %w", traceHost, err)
	}
	var dst net.IP
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			dst = v4
			break
		}
	}
	fmt.Println("trace target    :", traceHost, dst.String())

	p := &peer{
		tun:       tun,
		srcIP:     srcIP,
		dstIP:     dst,
		dstPort:   443,
		connected: make(chan struct{}),
		done:      make(chan struct{}),
	}
	if err := tun.connect(p); err != nil {
		return "", err
	}
	fmt.Println("tcp handshake   : established through the tunnel")

	raw, err := net.DialTCP("tcp", nil, listenerAddr(p))
	if err != nil {
		return "", err
	}
	defer raw.Close()

	conn2 := tls.Client(raw, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         traceHost,
	})
	if err := conn2.SetDeadline(time.Now().Add(25 * time.Second)); err != nil {
		return "", err
	}
	if err := conn2.Handshake(); err != nil {
		return "", fmt.Errorf("tls inside tunnel: %w", err)
	}
	fmt.Println("tls handshake   : complete, alpn=", conn2.ConnectionState().NegotiatedProtocol)

	_, err = fmt.Fprintf(conn2, "GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: colgram-warp-on\r\nAccept: */*\r\nConnection: close\r\n\r\n",
		tracePath, traceHost)
	if err != nil {
		return "", fmt.Errorf("write request: %w", err)
	}
	fmt.Printf("request         : GET %s\n", tracePath)

	body := make([]byte, 0, 8192)
	buf := make([]byte, 4096)
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		conn2.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := conn2.Read(buf)
		if n > 0 {
			body = append(body, buf[:n]...)
			if bytes.Contains(body, []byte("warp=")) {
				break
			}
		}
		if err != nil && err != io.EOF {
			select {
			case <-p.done:
				if bytes.Contains(body, []byte("warp=")) {
					break
				}
			default:
			}
			if bytes.Contains(body, []byte("warp=")) {
				break
			}
		}
	}
	return string(body), nil
}

// listenerAddr returns the loopback address the bridge is listening on for this peer.
func listenerAddr(p *peer) *net.TCPAddr {
	return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: int(p.srcPort)}
}

func main() {
	body, err := measure()
	if err != nil {
		fmt.Println("error           :", err)
		os.Exit(1)
	}
	idx := bytes.Index(body, []byte("\r\n\r\n"))
	head, payload := body, []byte(nil)
	if idx >= 0 {
		head = body[:idx]
		payload = body[idx+4:]
	}
	fmt.Println("status          :", firstLine(head))
	fields := map[string]string{}
	for _, line := range bytes.Split(payload, []byte("\n")) {
		if k, v, ok := bytes.Cut(line, []byte("=")); ok {
			fields[string(bytes.TrimSpace(k))] = string(bytes.TrimSpace(v))
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

func firstLine(b []byte) string {
	if i := bytes.Index(b, []byte("\r\n")); i >= 0 {
		return string(b[:i])
	}
	return string(b)
}
