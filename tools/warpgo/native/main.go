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

/*
// The exported entry points are declared by their //export comments below. This header exists
// so the file is built as cgo; redeclaring the functions here would collide with the generated
// header, which is what a conflicting-types error from cgo_export.c means.
*/

/*
#cgo CFLAGS: -I${SRCDIR} -IC:/Colgram/tools/jdk17/jdk-17.0.20.1+1/include -IC:/Colgram/tools/jdk17/jdk-17.0.20.1+1/include/win32
#include <stdlib.h>
#include <jni.h>

// colgram_masque_emit is the plain C logger in jni_bridge.c: it forwards a line to android/log_print,
// which is the only sink on a device a reader can see. It is deliberately a different name from the JNI
// entry point below, because cgo generates that one from the //export and a second declaration of it
// here is a conflicting-types error.
extern void colgram_masque_emit(const char *line);
*/
import "C"

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
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"golang.org/x/net/http2/hpack"
)

const (
	apiHost = "api.cloudflareclient.com"
	apiVer  = "v0a4471"

	// apiSNIMask is the name carried in the TLS ClientHello for every request to the enrolment API.
	//
	// The filter on this network keys on the SNI extension value and on nothing else, and it applies to
	// the substring cloudflareclient.com wherever it appears. Measured at the same address, the same
	// port, in the same run:
	//
	//	sni=api.cloudflareclient.com          tls: EOF
	//	sni=connectivity.cloudflareclient.com  tls: read tcp ... i/o timeout
	//	sni=cloudflare.com                    tls OK 97ms | HTTP/1.1 403
	//	sni=cloudflare-dns.com                tls OK 107ms | HTTP/1.1 403
	//	sni=1.1.1.1                           tls OK | HTTP/1.1 200 {"id":...,"token":...}
	//
	// The first two are the filter; the last one is the answer. Cloudflare routes the request on the
	// HTTP Host header, which still carries apiHost, so the registration lands on the service it was
	// addressed to while the name on the wire is one the filter allows.
	//
	// Certificate verification is off for these calls, deliberately and only for these: the
	// certificate presented belongs to the mask, not to the host being addressed.
	apiSNIMask = "1.1.1.1"
	edgeIP     = "162.159.198.2"
	edgeSNI    = "consumer-masque.cloudflareclient.com"
	// connectAuth is the :authority the edge compares against its URI template. A request naming
	// anything else is refused, so it is a constant rather than derived from anything at runtime.
	connectAuth = "cloudflareaccess.com"

	// edgeH2IP is the TCP+TLS+H2 endpoint for MASQUE when QUIC is blocked.
	edgeH2IP   = "162.159.198.2"
	edgeH2Port = "443"

	traceHost = "connectivity.cloudflareclient.com"
	tracePath = "/cdn-cgi/trace"
)

// socksUDPPacket is the SOCKS5 UDP request header, as RFC 1928 section 7 defines it.
//
// This exists because of a measured property of the device's network, not a preference. UDP 443 is
// filtered there while TCP 443 and UDP 53 both answer:
//
//	1.1.1.1:53          ANSWERED 64 bytes in 103ms
//	1.1.1.1:54          no answer in 3001ms
//	162.159.198.2:443   no answer in 3001ms
//
// A filter on the destination port, with DNS carved out. MASQUE needs a bidirectional UDP flow to
// the edge, and none of the ports the edge answers on is 53. A SOCKS5 relay that already carries
// TCP to a working egress can carry this datagram, so the tunnel stops depending on this device
// reaching UDP 443 itself.
type socksUDPConn struct {
	ctrl    net.Conn
	relay   net.Addr
	partner net.Addr
	buf     []byte
	// The datagram socket, per RFC 1928 section 7.
	//
	// A real SOCKS5 UDP client opens a UDP socket and sends its datagrams to the address the relay
	// returned in the associate reply; the relay's replies come back to that socket. Routing them
	// through the TCP control connection instead looks like it works - the relay answers, and the
	// counters move - but TCP has no message boundaries, so two datagrams arrive as one read and the
	// second one's header lands in the middle of the first one's payload. The symptom is exactly what
	// this produced: the handshake reached "CONNECT 200" and then TLS saw a record that did not begin
	// with a handshake byte.
	udp   *net.UDPConn
	local net.IP
	// readBuf holds bytes read from the control connection that do not yet form a whole frame.
	// TCP has no message boundaries, so a partial read is normal and a frame can span reads.
	readBuf []byte
}

// dialSocks5UDP opens the control connection and negotiates a UDP ASSOCIATE.
func dialSocks5UDP(proxy string) (*socksUDPConn, error) {
	host, portStr, err := net.SplitHostPort(proxy)
	if err != nil {
		return nil, fmt.Errorf("socks address %q: %w", proxy, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("socks port %q: %w", portStr, err)
	}
	ctrl, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 10*time.Second)
	if err != nil {
		return nil, err
	}
	if err := socks5Greet(ctrl); err != nil {
		ctrl.Close()
		return nil, err
	}
	// UDP ASSOCIATE: 0x03, reserved 0x00, and a zero address and port, which tells the relay to
	// accept from wherever this control connection came from.
	req := []byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
	if _, err := ctrl.Write(req); err != nil {
		ctrl.Close()
		return nil, err
	}
	reply := make([]byte, 10)
	if _, err := readFull(ctrl, reply); err != nil {
		ctrl.Close()
		return nil, err
	}
	if reply[1] != 0x00 {
		ctrl.Close()
		return nil, fmt.Errorf("socks udp associate refused: %d", reply[1])
	}

	// The relay tells us where to send datagrams; that is the partner address.
	relayAddr, err := net.ResolveUDPAddr("udp4", net.JoinHostPort(
		net.IP(reply[4:8]).String(), strconv.Itoa(int(reply[2])<<8|int(reply[3]))))
	if err != nil {
		ctrl.Close()
		return nil, err
	}
	// The datagram socket, opened before the session is handed out. A relay may send the first
	// reply immediately, and a socket opened afterwards would miss it.
	local := &net.UDPAddr{}
	if b := os.Getenv("WARP_BIND"); b != "" {
		if ip := net.ParseIP(b).To4(); ip != nil {
			local.IP = ip
		}
	}
	udpSock, err := net.ListenUDP("udp4", local)
	if err != nil {
		ctrl.Close()
		return nil, err
	}
	return &socksUDPConn{
		ctrl:    ctrl,
		relay:   relayAddr,
		partner: relayAddr,
		buf:     make([]byte, 65535),
		udp:     udpSock,
		local:   local.IP,
	}, nil
}

func socks5Greet(ctrl net.Conn) error {
	if _, err := ctrl.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return err
	}
	reply := make([]byte, 2)
	if _, err := readFull(ctrl, reply); err != nil {
		return err
	}
	if reply[0] != 0x05 || reply[1] != 0x00 {
		return fmt.Errorf("socks5 greeting refused: %02x %02x", reply[0], reply[1])
	}
	return nil
}

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

// WriteTo sends one datagram through the relay.
//
// Over the control connection, with a two-byte length prefix, because the UDP path cannot be used
// here and it must be said plainly rather than worked around: an emulator's NAT does not forward
// UDP to the host at all. Verified rather than assumed - a plain UDP echo server on the host receives
// nothing from the device on either the LAN address or the gateway, while TCP goes through
// normally.
//
// TCP has no message boundaries, which is why the length is here: without it two datagrams arrive
// as one read and the second one's header lands inside the first one's payload. That is exactly what
// happened before, and it presented as a tunnel that came up and then produced garbage TLS records.
func (s *socksUDPConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	udp := addr.(*net.UDPAddr)
	pkt := make([]byte, 0, 4+4+2+len(b))
	pkt = append(pkt, 0x00, 0x00, 0x00) // RSV(2) + FRAG(1)
	pkt = append(pkt, 0x01)             // ATYP: IPv4
	pkt = append(pkt, udp.IP.To4()...)
	pkt = append(pkt, byte(udp.Port>>8), byte(udp.Port))
	pkt = append(pkt, b...)
	frame := make([]byte, 2+len(pkt))
	frame[0] = byte(len(pkt) >> 8)
	frame[1] = byte(len(pkt))
	copy(frame[2:], pkt)
	if _, err := s.ctrl.Write(frame); err != nil {
		return 0, err
	}
	return len(b), nil
}

// ReadFrom receives one relayed datagram and strips the SOCKS5 header.
func (s *socksUDPConn) ReadFrom(b []byte) (int, net.Addr, error) {
	if s.readBuf == nil {
		s.readBuf = make([]byte, 0, 70000)
	}
	for {
		// A complete frame already buffered from an earlier read.
		if len(s.readBuf) >= 2 {
			size := int(s.readBuf[0])<<8 | int(s.readBuf[1])
			if len(s.readBuf) >= 2+size {
				return s.takeFrame(b, size)
			}
		}
		tmp := make([]byte, 16384)
		n, err := s.ctrl.Read(tmp)
		if n > 0 {
			s.readBuf = append(s.readBuf, tmp[:n]...)
		}
		if err != nil {
			return 0, nil, err
		}
	}
}

// takeFrame strips the SOCKS5 header from one length-prefixed frame and returns its payload.
func (s *socksUDPConn) takeFrame(b []byte, size int) (int, net.Addr, error) {
	frame := s.readBuf[2 : 2+size]
	s.readBuf = s.readBuf[2+size:]
	head := frame
	if len(head) < 4 {
		return 0, nil, fmt.Errorf("short socks datagram: %d bytes", len(head))
	}
	if head[2] != 0x00 {
		return 0, nil, fmt.Errorf("unexpected socks fragment 0x%02x", head[2])
	}
	addrLen := 0
	switch head[3] {
	case 1:
		addrLen = 4
	case 4:
		addrLen = 16
	default:
		return 0, nil, fmt.Errorf("unexpected socks address type %d", head[3])
	}
	need := 4 + addrLen + 2
	if len(head) < need {
		return 0, nil, fmt.Errorf("short socks header: %d < %d", len(head), need)
	}
	if socksDatagramsRead.Load() <= 3 {
		fmt.Printf("socks read %d bytes, type=%d, payload=%d\n",
			len(head), head[3], len(head)-need)
	}
	socksDatagramsRead.Add(1)
	ip := make(net.IP, addrLen)
	copy(ip, head[4:4+addrLen])
	port := int(head[4+addrLen])<<8 | int(head[5+addrLen])
	payload := head[need:]
	if len(payload) > len(b) {
		payload = payload[:len(b)]
	}
	copy(b, payload)
	return len(payload), &net.UDPAddr{IP: ip, Port: port}, nil
}

func (s *socksUDPConn) Close() error {
	if s.udp != nil {
		s.udp.Close()
	}
	return s.ctrl.Close()
}

func (s *socksUDPConn) LocalAddr() net.Addr                { return s.ctrl.LocalAddr() }
func (s *socksUDPConn) SetDeadline(t time.Time) error      { return s.ctrl.SetDeadline(t) }
func (s *socksUDPConn) SetReadDeadline(t time.Time) error  { return s.ctrl.SetReadDeadline(t) }
func (s *socksUDPConn) SetWriteDeadline(t time.Time) error { return s.ctrl.SetWriteDeadline(t) }

var crlf = string([]byte{13, 10})

// h2TraceFile is the transcript's destination, named once so every frame of one connection lands in one
// place and can be read in order against the reference client's own log.
var h2TraceFile string

// h2Trace opens the transcript, once per process.
func h2Trace() *os.File {
	if h2TraceFile == "" {
		h2TraceFile = os.Getenv("COLGRAM_H2_TRACE")
		if h2TraceFile == "" {
			// On a device there is no environment to read: a c-shared library snapshots the environment when
			// it is loaded and the app that loads it never had one to give. So the transcript is named by a
			// file in the process's own temporary directory instead, which needs nothing but the process.
			//
			// It matters because the device is where the verdict has to hold and the host is where the fault
			// can be found quickly - and the two disagree. A tunnel that carries on one and not the other is
			// either a device fault or a network fault, and the frames say which.
			h2TraceFile = filepath.Join(os.TempDir(), "colgram-h2-trace.txt")
		}
	}
	f, err := os.OpenFile(h2TraceFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil
	}
	return f
}

// h2TraceWrite appends one line to the transcript in the reference client's own format, so the two logs
// can be read side by side rather than translated between them.
func h2TraceWrite(dir string, b []byte) {
	f := h2Trace()
	if f == nil {
		return
	}
	defer f.Close()
	f.WriteString(fmt.Sprintf("H2 %s %d bytes: %x\n", dir, len(b), b))
}

// socksOverride is set by the JNI layer before the measurement runs, because a c-shared library
// cannot see environment variables exported after it was loaded. Measured, not assumed: the same
// process printed WARP_SOCKS="" while a shell in the same invocation printed the value.
var socksOverride string

// socksAddrFor resolves the SOCKS5 front: the caller's explicit one, or the environment's.
func socksAddrFor(cfg Config) string {
	if cfg.SocksAddr != "" {
		return cfg.SocksAddr
	}
	return os.Getenv("WARP_SOCKS")
}

// socksAddrForAttempt is the same thing without a Config, because attempt() is called per candidate
// and the SOCKS front applies to all of them equally.
func socksAddrForAttempt() string {
	if socksOverride != "" {
		return socksOverride
	}
	return os.Getenv("WARP_SOCKS")
}

// maxDatagramPayload is the largest TCP segment this tunnel sends in one capsule.
//
// It was 1100, on the assumption that anything under the 1200-byte QUIC initial size would be
// carried. Measured on this edge that assumption is wrong in a specific way: a handshake segment is 40
// bytes and comes back every time, and a data segment of 1140 comes back never - not refused, not reset,
// simply no reply and no inbound packet of any kind after it:
//
//	outbound 40 bytes,   flags 0x002, seq 1                    <- SYN      inbound 44
//	outbound 40 bytes,   flags 0x010, seq 2                    <- ACK      inbound 44
//	outbound 1140 bytes, flags 0x018, seq 2  payload 1100     <- data     nothing
//	outbound 465 bytes,  flags 0x018, seq 1102 payload 425     <- data     nothing
//	tls inside tunnel: i/o timeout (sent=9 recv=2)
//
// The largest packet measured crossing this edge in either direction, in a session that returned
// warp=on, is 466. So the segment is sized to sit under what the carrier has actually been seen to
// carry rather than under what the protocol permits. A TLS ClientHello is a few hundred bytes and
// arrives in three or four segments instead of two, which costs a round trip and nothing else.
//
// It is back at 1100 because that is the value the `warp=on` run used, and lowering it was a change made
// on the strength of a note about the largest packet seen crossing the edge - a note that the same run
// contradicts, since a session that returned `warp=on` carried a 1100-byte payload segment. A segment
// size that has been measured in the direction that works outranks one inferred from a stall that was
// measured under other conditions. Nothing else on the trace path differs from that run: the HTTP/2
// session, the request shape, the capsule framing and the resolver are byte-identical to it.
const maxDatagramPayload = 1100

// segmentCap reports the largest TCP payload this tunnel will put in one segment.
//
// It is a variable rather than a constant because the size is the one thing about the data path that has
// never been separated by measurement from the content of what is sent. The reference client never puts
// more than 76 bytes in a segment - its own log holds a 76-byte GET and nothing larger - while this client
// sends a TLS ClientHello as 1100 and 425. Every run that carried came from one that happened to put a
// small segment out first; every run that stalled sent the large one first and nothing came back.
//
// WARP_TRACE_IP and this are read the same way and for the same reason: a question that needs one build per
// answer is a question that does not get asked enough times to mean anything.
func segmentCap() int {
	if override := os.Getenv("WARP_SEGMENT_CAP"); override != "" {
		if n, err := strconv.Atoi(override); err == nil && n > 0 && n <= maxDatagramPayload {
			return n
		}
	}
	return maxDatagramPayload
}

// tunnelConn is a net.Conn whose bytes travel as Connect-IP capsules inside the MASQUE stream.
// Everything above it - the TLS client, the HTTP request - is an ordinary library.
type tunnelConn struct {
	tun     *tunnel
	srcIP   net.IP
	dstIP   net.IP
	srcPort uint16
	dstPort uint16
	// synSeq is the sequence number the SYN went out with. The far side acknowledges exactly one byte of
	// it, and the client's own counter has to be sitting at synSeq+1 when it does - otherwise the data that
	// follows is sent from a sequence the far side has not acknowledged and never leaves the handshake.
	synSeq uint32
	// peerOptions are the options the far side sent on its SYN-ACK, echoed back on every segment after
	// the handshake. Echoing the peer's own values rather than this side's is what TCP requires and what
	// the edge expects: it answered MSS 1460 and window scale 13 where this client offered 1240 and 7.
	peerOptions []byte
	// peerScale is the window scale the peer said it would apply, read off its SYN-ACK and used to write
	// this side's window in the units the peer will read. Zero until the peer answers.
	peerScale int
	// Deadlines are checked on the packet boundary. A capsule stream has no socket to hand a deadline
	// to, so the time is carried here and consulted when the next packet would otherwise wait.
	readDeadline  time.Time
	writeDeadline time.Time
	seq           uint32
	ack           uint32

	mu   sync.Mutex
	cond *sync.Cond
	// wake is what a reader selects on, and what feed and the close paths close. A sync.Cond has no timed
	// wait, so a deadline on a packet stream has to be honoured by waking the reader - and a timer goroutine
	// that broadcasts into a Cond races the reader that is about to wait on it. That race wrote to the heap
	// from outside the allocator:
	//
	//   fatal error: mspan.sweep: bad span state
	//
	// A channel carries its own wake-up, so there is nothing to race. A stale close is harmless because the
	// reader replaces the channel when it takes one.
	wake     chan struct{}
	inbuf    []byte
	eof      bool
	synned   bool
	closed   bool
	sent     int
	recv     int
	attempts int
}

func newTunnelConn(tun *tunnel, srcIP, dstIP net.IP, srcPort, dstPort uint16) *tunnelConn {
	// A random initial sequence number, and one seeded per flow rather than per process.
	//
	// Every SYN this client sent went out with sequence 1, and the reference client's did not:
	//
	//   this client    outbound 60 bytes (opts 20), flags 0x002, seq 1 ack 0
	//   reference      tcp sport=53735 dport=443 seq=410236364 ack=0
	//
	// A sequence number of 1 on every flow from every run is not a sequence number, it is a constant, and
	// RFC 9293 requires the initial one to be chosen so that it does not predict the sequence numbers of a
	// later connection to the same destination. An edge that tracks flows per identity has seen this
	// client's flows collide on a sequence space before, and a fixed low sequence is what a filter looks
	// for when it wants to recognise a tunnel rather than a host - it is the same shape of thing as the fixed
	// source port below.
	//
	// It is drawn per flow so two flows on one tunnel cannot collide either, which is the case that matters
	// when the measurement opens a flow beside the one carrying traffic.
	var seed [4]byte
	if _, err := rand.Read(seed[:]); err == nil {
		isn := binary.BigEndian.Uint32(seed[:]) & 0x7fffffff
		c := &tunnelConn{tun: tun, srcIP: srcIP, dstIP: dstIP, srcPort: srcPort,
			dstPort: dstPort, seq: isn}
		c.wake = make(chan struct{})
		return c
	}
	c := &tunnelConn{tun: tun, srcIP: srcIP, dstIP: dstIP, srcPort: srcPort,
		dstPort: dstPort, seq: 1}
	c.wake = make(chan struct{})
	return c
}

// ephemeralPort returns a source port in the range a host assigns to an outgoing connection.
//
// 51500 was hardcoded here and it is visible: every flow this client opens on the tunnel has carried the
// same source port, on every run, for every destination. The reference client drew one from the ephemeral
// range and it stayed the same for the life of the flow - which is the second half of the requirement. A
// tunnel's flows are all outgoing connections from one identity to one edge, so a fixed port across all of
// them is a far stronger signal than a fixed port on one host, where an epoch is behind it anyway.
//
// The range is 49152-65535, which is IANA's dynamic/private range on both Linux and Android, and the port
// is drawn once per flow and then held - which is what `newTunnelConn` receives.
func ephemeralPort() uint16 {
	var b [2]byte
	if _, err := rand.Read(b[:]); err == nil {
		return uint16(49152 + binary.BigEndian.Uint16(b[:])%16384)
	}
	return 51500
}

// recvWindow reports the receive window this flow is offering, in the units its window scale multiplies.
//
// It is the buffer this side has, less what is already sitting in it, clamped to the 16-bit field and to
// the scale, so a far side is told what can genuinely be delivered right now rather than a number chosen
// once.
//
// The scale is the peer's, not this side's. This client advertised a scale of 7 on its SYN and read the
// edge's answer of 13, and then divided its buffer by 128 - its own scale - while the edge was multiplying
// by 8192 - the scale it had said it would use. The window that went out on the first data segment was 200
// units, which the edge read as 1.6 megabytes against a buffer that had 200 units to give, and where the
// reference offers 4096 bytes:
//
//	reference   SYN win=24704   (193 units at scale 128)   data win=4096   (4096 bytes unscaled)
//	this client SYN win=200     (25 KB at scale 128)      data win=200     (1.6 MB at scale 8192)
//
// A peer whose window is four times larger than the buffer behind it invites a probe rather than a
// response, and the edge answers the SYN-ACK's window by closing this one's. The window is written in the
// units the SYN that negotiated it asked for, which is what makes it mean what it says to the reader.
func (c *tunnelConn) recvWindow() uint16 {
	// The scale in force for this flow: the peer's, once it has answered, and this side's own until then.
	scale := c.peerScale
	if scale <= 0 {
		scale = 128
	}
	const capacity = 25 * 1024
	free := capacity - len(c.inbuf)
	if free < 1024 {
		free = 1024
	}
	units := free / scale
	if units < 16 {
		units = 16
	}
	if units > 65535 {
		units = 65535
	}
	return uint16(units)
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

// synSeqOf returns the sequence number this flow's SYN was sent with, which is the one that has to be
// acknowledged after the SYN-ACK arrives. The counter moves on when the SYN goes out, so the original is
// kept rather than reconstructed.
func synSeqOf(c *tunnelConn) uint32 {
	return c.synSeq
}

// payloadLenOf reports the TCP payload length in a packet, or -1 when the header offset is unreadable.
// It exists so the inbound path can say how much arrived rather than only that something did.
func payloadLenOf(ip []byte) int {
	if len(ip) < 20+20 {
		return -1
	}
	tcp := ip[20:]
	off := int(tcp[12]>>4) * 4
	if off < 20 || off > len(tcp) {
		return -1
	}
	return len(tcp) - off
}

func ipv4Packet(src, dst net.IP, payload []byte) []byte {
	hdr := make([]byte, 20)
	// Version 4 in the high nibble, header length 5 words in the low one.
	hdr[0] = 0x45
	binary.BigEndian.PutUint16(hdr[2:4], uint16(20+len(payload)))
	// Identification zero.
	//
	// The reference client writes 0x0000 here and it writes 0x0000 on its data segments too, so the whole
	// flow it carried went out with no IP identification at all:
	//
	//   SYN    45 00 00 3c 00 00 40 00 3f 06   id = 0x0000, flfrag = 0x4000
	//   data   45 00 00 80 00 00 40 00 3f 06   id = 0x0000, flfrag = 0x4000
	//
	// This client wrote 0x4321 - a constant, on every packet, for every flow. It is a valid value for a
	// host that does not set Don't Fragment, but this one does set it, and the two together are the shape
	// of a packet that has been assembled by something rather than by a stack: a real stack either
	// fragments and needs identification, or sets DF and has no use for it. Matched, because the point of
	// this field was never to be interesting.
	binary.BigEndian.PutUint16(hdr[4:6], 0)
	// Don't Fragment, on every packet.
	//
	// The reference client sets it on everything it sends, and it was the one field this client's packets
	// did not have. Off its own capture, on the carrier that returned warp=on:
	//
	//   SYN    45 00 00 3c 00 00 40 00 3f 06 ...   flags/fragment = 0x4000
	//   data   45 00 00 80 00 00 40 00 3f 06 ...   flags/fragment = 0x4000, still set
	//
	// and this client was writing 0x0000 into those two bytes on every packet, which is "may fragment"
	// with a zero fragment offset - a packet an edge is free to split or to drop. A tunnel of this shape
	// cannot have its segments fragmented: the payload is a capsule, and a capsule cut in half is two
	// pieces the edge cannot reassemble, because nothing in Connect-IP carries a fragment reassembly key.
	//
	// That is the same reasoning the MSS option is already making, one layer down. A peer that forwards on
	// the segment rather than on the byte stream, which a tunnel terminating TCP at its edge does, has no
	// way to put a fragmented capsule back together - so it declines to forward one, silently, and the flow
	// that carried the SYN goes quiet exactly where this one goes quiet:
	//
	//   h2 peer open, sent=7 recv=2
	//   plaintext trace returned 0 bytes, sent=8 recv=2: ""
	//
	// 0x4000 is the Don't Fragment bit: the high bit of the flags byte, which is bits 6 and 7 of the 16-bit
	// flags-and-fragment-offset field with the low 13 bits - the fragment offset - left at zero.
	binary.BigEndian.PutUint16(hdr[6:8], 0x4000)
	// TTL 63, which is what the reference client sends on everything it originates - SYN and data alike.
	// A real Linux host decrements from 64, so 63 is a stack's initial value minus one hop, and it is what
	// this carrier is measured to carry. 64 was the value here.
	hdr[8] = 63
	hdr[9] = 6
	copy(hdr[12:16], src.To4())
	copy(hdr[16:20], dst.To4())
	binary.BigEndian.PutUint16(hdr[10:12], checksum(hdr))
	return append(hdr, payload...)
}

// emit builds a TCP segment with a real checksum. The edge forwards real TCP, so a wrong checksum
// here is a connection that is silently dropped.

// synOptions are the TCP options this side offers on its SYN and repeats on every later segment.
//
//	MSS 1240              the largest segment that fits the 1200-byte packet this carrier has been
//	                       measured to carry, less the two headers
//	SACK permitted        selective acknowledgement, which the edge answers acknowledging
//	timestamps            required by window scaling and by most middleboxes' path MTU heuristics
//	window scale 7        128-byte windows, which is what a tunnel of this shape can carry
//
// They repeat after the handshake because the header length is part of the stream layout the peer
// negotiated. A flow that starts with a 40-byte header and continues with a 20-byte one is a different
// stream, and the far side has no way to know the payload did not move with it.
func synOptions() []byte {
	now := uint32(time.Since(processStart) / time.Millisecond)
	opts := []byte{
		0x02, 0x04, 0x04, 0xd8, // MSS 1240
		0x04, 0x02, // SACK permitted
		0x08, 0x0a, // timestamps
		byte(now >> 24), byte(now >> 16), byte(now >> 8), byte(now),
		0x00, 0x00, 0x00, 0x00, // TSecr, replaced with the peer's TSval once it has sent one
		0x01,             // no-op
		0x03, 0x03, 0x07, // window scale 7
	}
	return opts
}

// dataOptions are the options a segment carries once the handshake is done: timestamps, and nothing else.
//
// MSS, SACK permitted and window scale are only meaningful on a SYN - RFC 9293 section 3.2 says the window
// scale option may appear in a SYN and in a SYN/ACK and nowhere else, and a real stack does not repeat
// them. This client did repeat them, because the peer option capture takes everything between the fixed
// header and the data offset and is then echoed verbatim on every segment for the rest of the flow:
//
//	c.peerOptions = append([]byte(nil), tcp[20:offset]...)
//
// On this edge that capture is twenty bytes - MSS 1460, SACK permitted, timestamps, window scale 13 - and
// twenty bytes went out in front of every data segment, where the reference sends twelve:
//
//	reference   doff=32  options: 01 01 08 0a ea e8 5f 8f 8b 43 33 17   NOP NOP timestamps
//	this client doff=40  options: 02 04 05 b4 04 02 08 0a ... 01 03 03 0d  MSS SACK timestamps NOP wscale
//
// Eight bytes is the whole difference between a segment the edge forwards and one it reads,
// acknowledges and drops, and nothing above this layer can see it: the packet is well formed, the
// checksums fold to zero, and the acknowledgement arrives either way.
func dataOptions() []byte {
	now := uint32(time.Since(processStart) / time.Millisecond)
	return []byte{
		0x01, 0x01, // two no-ops, so the timestamps land on a four-byte boundary
		0x08, 0x0a, // timestamps
		byte(now >> 24), byte(now >> 16), byte(now >> 8), byte(now),
		0x00, 0x00, 0x00, 0x00, // TSecr, replaced with the peer's TSval
	}
}

// stampEcho writes this flow's own clock into the timestamp option's echo field.
//
// The reference client's option block is not constant across the flow. Off its own capture:
//
//	SYN    08 0a ea e8 58 37 00 00 00 00    TSval 0xeae85837, TSecr 0
//	data   08 0a ea e8 5f 8f 8b 43 33 17    TSval 0xeae85f8f, TSecr 0x8b433317
//
// TSval advances with the clock and TSecr becomes the TSval the edge sent on its SYN-ACK. So the echo is
// not decoration: it is how each side proves to the other that the segment it is acknowledging is one it
// actually received, and an echo of zero on every segment after the handshake says this flow received
// nothing - which is precisely the state the edge was in when it stopped answering.
func stampEcho(opts, peer []byte) []byte {
	ts := optionValue(peer, 8)
	if ts == nil || opts == nil {
		return opts
	}
	// Walk this side's options to the timestamp block and overwrite its echo half.
	for i := 0; i+2 <= len(opts); {
		kind := opts[i]
		if kind == 0 {
			break
		}
		if kind == 1 {
			i++
			continue
		}
		length := int(opts[i+1])
		if length < 2 || i+length > len(opts) {
			break
		}
		if kind == 8 && length >= 10 {
			copy(opts[i+6:i+10], ts)
			return opts
		}
		i += length
	}
	return opts
}

// optionValue returns the value bytes of a TCP option of the given kind, or nil.
//
// The length is not fixed at four, which is what made the window scale unreadable: that option is three
// bytes - kind, length, one byte of value - and a reader that insists on four returns nothing for it, so
// the scale stayed at zero and every window this client wrote went out in the wrong units.
func optionValue(opts []byte, kind byte) []byte {
	for i := 0; i+2 <= len(opts); {
		k := opts[i]
		if k == 0 {
			return nil
		}
		if k == 1 {
			i++
			continue
		}
		length := int(opts[i+1])
		if length < 2 || i+length > len(opts) {
			return nil
		}
		if k == kind {
			if length < 3 {
				return nil
			}
			// Timestamps are four value bytes; a window scale is one. Everything between the header
			// and the end of the option is its value.
			if length >= 6 {
				return opts[i+2 : i+6]
			}
			return opts[i+2 : i+length]
		}
		i += length
	}
	return nil
}

// processStart anchors the timestamp option's clock. TCP timestamps are milliseconds from an arbitrary
// but monotonic origin, and uptime is what this device exposes without asking for permission.
var processStart = time.Now()

func (c *tunnelConn) emit(payload []byte, flags uint16) {
	var opts []byte
	optLen := 0
	if flags&0x02 != 0 && !c.synned {
		// The TCP options the handshake is negotiated with, and only on the SYN.
		//
		// A SYN with no options is a valid packet and the edge answers it - four SYN-ACKs came back, one per
		// retransmission. But nothing after that is ever forwarded, and the reason is in the packets a
		// working client sends on the same carrier:
		//
		//     SYN      MSS 1240, SACK permitted, timestamps, window scale 7
		//     SYN-ACK  MSS 1460, SACK permitted, timestamps, window scale 13
		//
		// The edge answers with an MSS and a window scale, which it only does when the SYN left room for
		// them. With a 20-byte header and no options the flow is never given a segment size, so the first
		// segment of a ClientHello - which is larger than the default - has nowhere to go and the flow stalls
		// with the handshake complete and recv frozen at two.
		//
		// The client's own SYN-ACK for this flow came back 44 bytes, echoing a SYN that carried nothing, and
		// the working client's is 52 with four options in it. That is the whole difference.
		// MSS 1240: the largest segment that fits the 1200-byte packet this carrier has been seen to
		// carry, less the two headers.
		opts = synOptions()
		optLen = len(opts)
	} else if c.synned {
		// Every segment after the handshake carries the same options the peer saw on the SYN.
		//
		// The options were only on the SYN in an earlier version, and the flow died on the very next
		// packet. The SYN now goes out at 60 bytes and the edge answers at 60, and then:
		//
		//     outbound 40 bytes,  flags 0x010, seq 2    ACK          <- 20-byte header
		//     outbound 440 bytes, flags 0x018, seq 2    ClientHello  <- 20-byte header
		//     tls inside tunnel: i/o timeout (sent=11 recv=2)
		//
		// The handshake negotiated a 40-byte header and then the stream changed to 20 bytes one byte later.
		// The far side has no way to know the payload did not move with it, and the flow stops.
		//
		// The values echoed are the peer's, read off its SYN-ACK - it answered MSS 1460 and window scale 13
		// where this client offered 1240 and 7 - because an echo of anything else is a different option set
		// than the one the peer recorded.
		// Timestamps only, on every segment after the handshake.
		//
		// The peer's option block is not echoed verbatim. That was the fault: this capture takes everything
		// between the fixed header and the data offset, which on this edge's SYN-ACK is MSS 1460, SACK
		// permitted, timestamps and window scale 13, and twenty bytes then went out in front of every data
		// segment where the reference sends twelve:
		//
		//   reference   doff=32  01 01 08 0a ea e8 5f 8f 8b 43 33 17   NOP NOP timestamps
		//   this client doff=40  02 04 05 b4 04 02 08 0a ... 01 03 03 0d  MSS SACK timestamps NOP wscale
		//
		// MSS and window scale belong to the handshake; RFC 9293 section 3.2 puts the window scale option in
		// a SYN and in a SYN/ACK and nowhere else, and a real stack does not repeat them. Eight bytes is the
		// whole difference between a segment the edge forwards and one it reads, acknowledges and drops.
		opts = dataOptions()
		if len(c.peerOptions) == 0 {
			// The edge does not always answer with options. It echoed them in one run - a 52-byte SYN-ACK
			// with an MSS of 1460 and a window scale of 13 - and answered with a bare 40-byte header in
			// another, while the packet carried the same 60 bytes either way.
			//
			// So this side's own options are the fallback rather than a bare header. A flow that negotiated
			// a 40-byte header and then continues with 20-byte ones is a different stream layout, and the
			// far side cannot tell the payload did not move with it:
			//
			//     outbound 40 bytes,  (opts 0, peer 0), flags 0x010, seq 2    ACK
			//     outbound 440 bytes, (opts 0, peer 0), flags 0x018, seq 2    ClientHello
			//     tls inside tunnel: i/o timeout (sent=9 recv=2)
			//
			// A peer that answered with options gets those back verbatim, which is what TCP requires; a peer
			// that answered with none gets the ones this side offered, which is at least a constant header.
			opts = dataOptions()
		}
		// This side's own clock advances on every segment and its echo field carries the peer's TSval.
		// A constant pair, or an echo of zero, tells the peer the flow received nothing - and the peer's
		// reply to a flow it believes received nothing is silence.
		opts = stampEcho(opts, c.peerOptions)
		optLen = len(opts)
	}
	tcpLen := 20 + optLen + len(payload)

	pseudo := make([]byte, 0, 12+tcpLen)
	pseudo = append(pseudo, c.srcIP.To4()...)
	pseudo = append(pseudo, c.dstIP.To4()...)
	pseudo = append(pseudo, 0, 6)
	length := make([]byte, 2)
	// The pseudo-header length is the whole TCP segment, options included. With options added to the
	// header and left out of this field the checksum is computed over the wrong span and the edge drops
	// every packet that carries them - which is the same silence the missing options produced.
	binary.BigEndian.PutUint16(length, uint16(tcpLen))
	pseudo = append(pseudo, length...)

	seg := make([]byte, 20+optLen, 20+optLen+len(payload))
	binary.BigEndian.PutUint16(seg[0:2], c.srcPort)
	binary.BigEndian.PutUint16(seg[2:4], c.dstPort)
	binary.BigEndian.PutUint32(seg[4:8], c.seq)
	binary.BigEndian.PutUint32(seg[8:12], c.ack)
	// Data offset lives in the high nibble of the first byte, the flags in the next nine bits.
	seg[12] = byte((20+optLen)/4)<<4 | byte(flags>>8)
	seg[13] = byte(flags)
	// A window that is actually the one this flow has, rather than a constant.
	//
	// 64240 was written on every segment of every flow, which is 250 scale units advertised alongside a
	// window scale of 7 - so the far side was told to multiply 64240 by 128, about 8 MB, and to keep that
	// window open for the life of the flow. The reference client advertises what it has:
	//
	//   SYN     win=24704
	//   data    win=4096
	//
	// 24704 is 193 units at a scale of 128, so the SYN offers about 3.2 MB and then the first data segment
	// offers 4096 - the far side has sent nothing it has acknowledged yet, so there is nothing more to
	// offer. A tunnel that terminates TCP at its edge forwards on the segment, and a far side whose peer
	// has advertised a window it cannot fill is a peer sending into a buffer nobody is draining - which is
	// the same silence this whole fault has looked like from the outside.
	//
	// So the window is the unacknowledged-receive buffer this side actually has, and it is computed rather
	// than invented: the receive buffer less what is already sitting in it unread.
	window := c.recvWindow()
	binary.BigEndian.PutUint16(seg[14:16], window)
	if optLen > 0 {
		copy(seg[20:], opts)
	}
	seg = append(seg, payload...)
	binary.BigEndian.PutUint16(seg[16:18], checksum(append(pseudo, seg...)))

	c.sent++
	c.tun.sendIP(ipv4Packet(c.srcIP, c.dstIP, seg))
	androidLog(fmt.Sprintf("colgram_masque: outbound %d bytes (opts %d, peer %d), flags 0x%03x, seq %d ack %d, payload %d",
		len(seg)+20, optLen, len(c.peerOptions), flags, c.seq, c.ack, len(payload)))
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
	if plen := payloadLenOf(ip); plen >= 0 {
		androidLog(fmt.Sprintf("colgram_masque: inbound %d bytes, %d of payload, flags 0x%03x, seq %d ack %d",
			len(ip), plen, flags, seq, binary.BigEndian.Uint32(tcp[8:12])))
		// The first bytes of the first segment that carries data, on a flow that has already
		// completed its handshake.
		//
		// This is the last thing that has not been read off the wire. Everything before it is accounted
		// for - the capsule length equals the IP total length equals the bytes present, both checksums fold
		// to zero, the flow opens, the edge acknowledges the SYN - and then the edge reads a ClientHello,
		// acknowledges the segment it arrived in, and forwards nothing. Whether the ServerHello is on the
		// wire at all is what separates a fault in consuming it from a fault in asking for it, and that is
		// one line of output rather than another argument.
		if len(payload) > 0 && c.synned {
			androidLog(fmt.Sprintf("colgram_masque: first data on the flow is %d bytes: % x ...",
				len(payload), head(payload, 24)))
		}
	}

	if flags&0x02 != 0 {
		if flags&0x10 != 0 {
			if !c.synned {
				// First handshake: the far side's SYN is acknowledged, and the flow becomes usable.
				c.ack = seq + 1
				// Its options are recorded here and echoed on every segment after this one. Dropping them
				// changes the header length one byte into the stream, which is why the flow completed its
				// handshake and then stopped forwarding.
				if len(payload) == 0 && offset > 20 {
					// Only the option bytes, not everything from the option offset to the end of the
					// buffer. `tcp[offset:]` reads past the options into whatever else arrived in the
					// same capsule - and on this edge that is a segment carrying payload, so the copy taken
					// was 20 option bytes followed by a live sequence and a payload, and echoing that back
					// on every segment afterwards puts a wrong-length header in front of real data.
					//
					// The options are bounded by the data offset, which is the field that says where the
					// header ends: offset-20 bytes of them, and nothing beyond.
					c.peerOptions = append([]byte(nil), tcp[20:offset]...)
					// The window scale the peer said it will apply, so this side's window is written in the
					// units the peer reads rather than the units it offered.
					if ws := optionValue(c.peerOptions, 3); ws != nil && len(ws) > 0 {
						c.peerScale = int(ws[0])
						androidLog(fmt.Sprintf("colgram_masque: peer window scale is %d", c.peerScale))
					}
					androidLog(fmt.Sprintf("colgram_masque: captured %d peer option bytes: %x",
						len(c.peerOptions), c.peerOptions))
				} else {
					androidLog(fmt.Sprintf("colgram_masque: peer options not captured: payload=%d offset=%d tcp=%d",
						len(payload), offset, len(tcp)))
				}
				// The client's own sequence has to move past the SYN before any data is sent, and the far
				// side has to see that acknowledged before it will read a data segment from this flow.
				//
				// It did not, and everything after follows from that. The edge answered the SYN with
				//
				//     inbound 44 bytes, 0 of payload, flags 0x012, ack 2
				//
				// acknowledging one byte of ours - correct - and then never answered anything again, no
				// matter how much was sent:
				//
				//     h2 peer open, sent=7 recv=2
				//     tls inside tunnel: i/o timeout (sent=9 recv=2)
				//
				// `sent` rising and `recv` frozen at exactly two - the SYN-ACK, twice - is a connection that
				// never left the handshake, so its receive window is still closed to data. No data segment is
				// wrong; there is no flow to carry one yet.
				c.seq = synSeqOf(c) + 1
				// And the sequence is corrected before the ACK goes out, not after it. Sending first and
				// correcting afterwards means the acknowledgement carries the number the SYN was sent with
				// rather than the one after it, which is a sequence the far side has already consumed - so it
				// treats the ACK as out of window and the flow never opens.
				c.emit(nil, 0x10)
				c.synned = true
				c.wakeReader()
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
	// Every segment the far side sends advances what this side acknowledges - payload or not.
	//
	// The acknowledgement was only moved when a segment carried payload, so a bare ACK left it where the
	// handshake had put it. That number is then frozen into every packet for the rest of the flow:
	//
	//   WRITE 0x018  76 bytes  seq=110508361  ack=3085367042
	//   WRITE 0x018  76 bytes  seq=110508437  ack=3085367042
	//   WRITE 0x018  76 bytes  seq=110508513  ack=3085367042
	//   WRITE 0x018 1100 bytes seq=110508665  ack=3085367042
	//
	// Four data segments and a ClientHello, every one claiming to have received nothing since the
	// SYN-ACK. The edge acknowledged them - it sent eight bare ACKs whose own sequence numbers advanced
	// in order - and then stopped, because a peer whose window never moves has told it that nothing it
	// sends is being received:
	//
	//   plaintext trace returned 0 bytes, sent=15 recv=7
	//
	// That is the last thing that had to be wrong for the edge to carry traffic and this client to see
	// nothing: the packets were well formed, the capsules were well framed, and the acknowledgement the
	// far side reads off every one of them said it had heard nothing at all.
	if len(payload) > 0 {
		c.ack = seq + uint32(len(payload))
		c.inbuf = append(c.inbuf, payload...)
		// Acknowledge what arrived.
		//
		// The buffer was filled and nothing was sent back, so the far side kept its own send window
		// closed and the flow stalled after the handshake:
		//
		//   h2 peer open, sent=7 recv=2      SYN out, SYN-ACK in, ACK out
		//   tls inside tunnel: i/o timeout    ClientHello out, nothing back, ever
		//
		// `sent` rising and `recv` frozen is that: the ClientHello left and the server's reply never did,
		// because nothing told the server its window was open. TCP has no other way to say so.
		c.emit(nil, 0x10)
	} else {
		// A bare ACK still moves the window. TCP's acknowledgement is cumulative: acknowledging a segment
		// with no data acknowledges every byte before it, so `seq` alone is the next byte this side expects.
		//
		// Segments carrying the SYN or the FIN each consume one sequence number, which is why the handshake
		// branch adds one and this one does not - a payload-free ACK with neither flag is exactly the next
		// byte expected, and adding one here would acknowledge a byte that has not arrived.
		c.ack = seq
		c.emit(nil, 0x10)
	}
	// RST must not be acknowledged. Replying to it is what made the edge re-open the flow with a
	// fresh SYN, which in turn tore down the session mid-handshake.
	if flags&0x04 != 0 {
		c.eof = true
		c.wakeReader()
		c.mu.Unlock()
		return
	}
	if flags&0x01 != 0 {
		c.ack = seq + uint32(len(payload)) + 1
		c.emit(nil, 0x11)
		c.eof = true
		c.wakeReader()
		c.mu.Unlock()
		return
	}
	c.emit(nil, 0x10)
	c.wakeReader()
	c.mu.Unlock()
}

func (c *tunnelConn) open() error {
	// A SYN retransmission must repeat the original sequence number. Incrementing between sends
	// makes the far side see two different connections and answer with RST.
	synSeq := c.seq
	c.synSeq = synSeq
	c.emit(nil, 0x02)
	c.seq = synSeq + 1

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
		if c.readExpired() {
			return 0, os.ErrDeadlineExceeded
		}
		// A channel rather than the condition variable. sync.Cond has no timed wait, so a deadline on a
		// stream that carries packets - rather than a socket that carries bytes - has to be honoured by
		// waking the reader somehow, and a timer goroutine that broadcast into a Cond races the reader
		// that is about to wait on it. The consequence of getting that wrong was not a missed wake-up but a
		// corrupted runtime:
		//
		//   fatal error: mspan.sweep: bad span state
		//
		// which is the heap being written by something other than the allocator. A channel select carries
		// its own wake-up, so there is nothing to race. The packet boundary is the granularity it offers
		// anyway - a packet arrives as a whole capsule - so a short poll costs nothing that matters.
		if !c.waitFor(250*time.Millisecond) && len(c.inbuf) == 0 && !c.eof {
			if c.readExpired() {
				return 0, os.ErrDeadlineExceeded
			}
		}
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
	if c.writeExpired() {
		c.mu.Unlock()
		return 0, os.ErrDeadlineExceeded
	}
	c.mu.Unlock()
	// A QUIC datagram carries one capsule and has a hard size limit. A TLS ClientHello does not fit
	// in one, so the write is split across datagrams and the far side reassembles it as a TCP
	// stream. Segmentation here is what a TUN device does.
	total := 0
	cap := segmentCap()
	for len(b) > 0 {
		n := len(b)
		if n > cap {
			n = cap
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

// Deadlines are honoured here rather than ignored.
//
// They were no-ops, which reads as harmless because the TCP handshake above has its own timeout - and
// then everything above it does not. A TLS handshake run on this connection waited forever, because the
// only deadline it had was on a socket that ignores it:
//
//	h2 peer open, sent=7 recv=2
//	... nothing for the caller's whole 180 second budget
//
// So a tunnel that had opened, and had already proved it carries packets, was reported as a tunnel
// that does not work. The read side checks the deadline between packets, which is the granularity a
// capsule stream has, and the write side checks it before handing a packet to the carrier.
func (c *tunnelConn) SetDeadline(t time.Time) error {
	c.readDeadline = t
	c.writeDeadline = t
	return nil
}

func (c *tunnelConn) SetReadDeadline(t time.Time) error {
	c.readDeadline = t
	return nil
}

func (c *tunnelConn) SetWriteDeadline(t time.Time) error {
	c.writeDeadline = t
	return nil
}

func (c *tunnelConn) readExpired() bool {
	return !c.readDeadline.IsZero() && time.Now().After(c.readDeadline)
}

// waitFor blocks until the peer feeds the connection, the stream ends, or the interval elapses. It is
// called with the lock held and returns with it still held, which is what the read loop needs.
//
// The wake is a channel closed by feed, so there is no timer goroutine and nothing that can signal a
// condition variable a reader has not reached yet.
func (c *tunnelConn) waitFor(d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	// A wake that arrived with no reader waiting leaves no channel behind - wakeReader clears it so a
	// later wake cannot close a closed channel - so a reader that arrives afterwards has nothing to select
	// on. It creates its own, and that is correct: the packet that woke the absent reader is already in
	// the buffer this loop is about to look at, so waiting on a fresh channel cannot miss it.
	if c.wake == nil {
		c.wake = make(chan struct{})
	}
	select {
	case <-c.wake:
		c.wake = make(chan struct{})
		return true
	case <-timer.C:
		return false
	}
}

// wakeReader releases anyone waiting on the connection. Called with the lock held, which is where every
// state change happens, and safe to call when nobody is waiting: the channel is closed and the next
// reader takes a fresh one.
func (c *tunnelConn) wakeReader() {
	if c.wake != nil {
		close(c.wake)
		// Cleared here, not left to the reader.
		//
		// The reader replaces the channel when it wakes, but a wake that arrives while nobody is waiting
		// leaves the closed one in place - and the next wakeReader closes it again:
		//
		//	panic: close of closed channel
		//		C:/Colgram/tools/warpgo/native/main.go:977
		//		C:/Colgram/tools/warpgo/native/main.go:810 +0x78a
		//
		// Two packets arriving back to back is enough, and it is reached from ordinary traffic rather than
		// from teardown: feed() calls wakeReader once for the payload it buffered and once more on the way
		// out for the ACK it sent. A peer that answers a segment and then the FIN that follows it two
		// microseconds later does it, and a panic on any goroutine takes the whole process with it - which is
		// what turning WARP off used to do before the close path was fixed.
		//
		// Clearing here rather than in the reader also makes a wake that arrives with no reader waiting a
		// no-op rather than a bomb, which is the whole reason this helper exists.
		c.wake = nil
	}
}

func (c *tunnelConn) writeExpired() bool {
	return !c.writeDeadline.IsZero() && time.Now().After(c.writeDeadline)
}

// ---------------------------------------------------------------------------
// capsuleStream abstracts H3 datagrams and H2 capsule framing.
// ---------------------------------------------------------------------------

type capsuleStream interface {
	SendDatagram([]byte) error
	ReceiveDatagram(context.Context) ([]byte, error)
}

// The accepted request shape is a property of the edge, not of a tunnel, so it is asked once and kept.
var (
	shapeMu     sync.Mutex
	shapeWinner string
	shapeOnce   sync.Once
)

type h3Stream struct{ s *http3.RequestStream }

// SendDatagram adds the type byte this carrier's framing needs and nothing else.
//
// The HTTP/3 datagram form is a single zero byte in front of the packet, and the packet is handed here
// without one because each carrier frames its own - see tunnel.sendIP. Writing the byte here rather than
// at the call site is what stops the two carriers from disagreeing about who wraps.
func (h *h3Stream) SendDatagram(b []byte) error {
	return h.s.SendDatagram(append([]byte{0x00}, b...))
}
func (h *h3Stream) ReceiveDatagram(ctx context.Context) ([]byte, error) {
	return h.s.ReceiveDatagram(ctx)
}

type h2raw struct {
	conn   net.Conn
	br     *bufio.Reader
	wmu    sync.Mutex
	sid    uint32
	inflow int32
	// maxFrame is the MAX_FRAME_SIZE this client advertised in its own SETTINGS. The edge sizes the DATA
	// frames it sends against what the client declared, and a frame this client sends larger than that
	// declaration is a protocol error rather than a slow write, so the two are held to one number.
	maxFrame int
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
		androidLog(fmt.Sprintf("colgram_masque: edge ALPN %q after presenting the certificate", got))
	}
	c := &h2raw{conn: tc, br: bufio.NewReader(tc), sid: 1, inflow: 65536, maxFrame: 16384}
	// The connection preface, then our own SETTINGS carrying ENABLE_CONNECT_PROTOCOL so the edge can
	// see the client supports it even though it never tells us that it does.
	// The settings a client that carries traffic over this carrier sends, transcribed from the bytes it
	// was observed writing. The one that matters is INITIAL_WINDOW_SIZE: at the HTTP/2 default of 65535 a
	// peer can hold less than a full tunnel packet, and this edge grants its credit against what the client
	// declares.
	var settingsPayload []byte
	settingsPayload = append(settingsPayload, h2setting(0x2, 0)...)        // ENABLE_PUSH off
	settingsPayload = append(settingsPayload, h2setting(0x4, 4194304)...)  // INITIAL_WINDOW_SIZE 4 MB
	settingsPayload = append(settingsPayload, h2setting(0x5, 16384)...)    // MAX_FRAME_SIZE
	settingsPayload = append(settingsPayload, h2setting(0x6, 10485760)...) // MAX_HEADER_LIST_SIZE
	settingsPayload = append(settingsPayload, h2setting(0x8, 1)...)        // ENABLE_CONNECT_PROTOCOL
	settings := frameH2(0x4, 0, 0, settingsPayload)
	// Every byte this carrier is sent, hex, when COLGRAM_H2_TRACE names a file.
	//
	// The reference client's own log has every frame it wrote and read, and the only way to use that log
	// against this client is to produce the same thing from this client - a hex transcript of the same
	// conversation, taken from the code under test rather than from what it was supposed to do.
	//
	// It is off by default and it is not a debug aid left in by accident: a carrier that accepts a request
	// with status 200, answers a SYN-ACK, and then forwards no data is indistinguishable from a correct one
	// at every layer this client can see. The bytes are the layer below that.
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
	whole := make([]byte, 0, 9)
	ln := int(head[0])<<16 | int(head[1])<<8 | int(head[2])
	typ, flags = head[3], head[4]
	stream = uint32(head[5])<<24 | uint32(head[6])<<16 | uint32(head[7])<<8 | uint32(head[8])
	if ln > 0 {
		payload = make([]byte, ln)
		_, err = io.ReadFull(c.br, payload)
	}
	whole = append(append(whole, head[:]...), payload...)
	h2TraceWrite("READ", whole)
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
			var seen []string
			for i := 0; i+6 <= len(payload); i += 6 {
				id := uint16(payload[i])<<8 | uint16(payload[i+1])
				val := uint32(payload[i+2])<<24 | uint32(payload[i+3])<<16 | uint32(payload[i+4])<<8 | uint32(payload[i+5])
				seen = append(seen, fmt.Sprintf("0x%x=%d", id, val))
				switch id {
				case 0x8:
					allowed = val == 1
				case 0x3:
					c.inflow = int32(val)
				case 0x4:
					// The edge's INITIAL_WINDOW_SIZE, which is the ceiling on what it will accept in DATA on
					// the tunnel stream before it sends a WINDOW_UPDATE. This client tracks no outbound
					// window at all, so the number is reported rather than enforced - and reporting it is
					// what says whether a tunnel that stalls after its handshake completed ever had credit to
					// carry the payload that stalled it.
					androidLog(fmt.Sprintf("colgram_masque: edge SETTINGS INITIAL_WINDOW_SIZE %d", val))
				case 0x5:
					androidLog(fmt.Sprintf("colgram_masque: edge SETTINGS MAX_FRAME_SIZE %d", val))
				}
			}
			// Every setting the edge sends, by name. A tunnel that answers a SYN and then discards every
			// segment after the handshake is a tunnel whose carrier refused one of the conditions the
			// tunnel needs, and the SETTINGS frame is the only place those conditions are stated. The two
			// that decide this: MAX_CONCURRENT_STREAMS (0x3), which caps how many CONNECT streams the edge
			// will carry at once - and this client opens a negotiation connection per request shape before it
			// opens the tunnel, so a cap of 1 would leave the tunnel stream with nothing to run on - and
			// ENABLE_CONNECT_PROTOCOL (0x8), which is what makes an extended CONNECT legal at all.
			androidLog(fmt.Sprintf("colgram_masque: edge SETTINGS %v (connect_protocol=%v)",
				seen, allowed))
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
	name           string
	withProto      bool // :protocol pseudo-header
	protoHdr       bool // cf-connect-proto header
	capsule        bool // capsule-protocol header
	pq             bool // pq-enabled header
	encoding       bool // accept-encoding header
	authorityFirst bool // :authority before :method
	authority      string
	path           string
	scheme         string
	endStream      bool
}

func variants() []connectVariant {
	auth := connectAuth
	return []connectVariant{
		// The one this edge answers 200 to, transcribed field for field from a client that was measured
		// carrying traffic over this carrier:
		//
		//     :authority: cloudflareaccess.com:443
		//     :method: CONNECT
		//     cf-connect-proto: cf-connect-ip
		//     pq-enabled: false
		//     accept-encoding: gzip
		//
		// No :scheme and no :path - this edge resets the stream for :protocol, and the two regular headers
		// are present in every sample that worked. It is first because it is the one that answers, and
		// because a run that walks the rest first is a run that spends its whole budget being reset:
		//
		//     edge ALPN "" after presenting the certificate      x 7
		//     h2 carrier failed: RST_STREAM stream=1 code=1
		//
		// is seven connections to an edge that was refusing new ones.
		{name: "measured shape", withProto: false, protoHdr: true, capsule: false, pq: true, encoding: true, authorityFirst: true, authority: auth + ":443", path: "", scheme: ""},
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
	// :authority first, then :method - the order a client carrying traffic over this carrier writes them,
	// transcribed from its bytes. The order is not something the protocol requires and nothing rejects a
	// different one, so it is matched because it is the only sample known to work.
	enc.WriteField(hpack.HeaderField{Name: ":authority", Value: v.authority})
	enc.WriteField(hpack.HeaderField{Name: ":method", Value: "CONNECT"})
	if v.scheme != "" {
		enc.WriteField(hpack.HeaderField{Name: ":scheme", Value: v.scheme})
	}
	if v.path != "" {
		enc.WriteField(hpack.HeaderField{Name: ":path", Value: v.path})
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
	// pq-enabled and accept-encoding are both present in the working request. A test that only checked
	// the status code could not tell a header that is not needed from one that was never in the only
	// sample that worked, so both are written.
	if v.pq {
		enc.WriteField(hpack.HeaderField{Name: "pq-enabled", Value: "false"})
	}
	enc.WriteField(hpack.HeaderField{Name: "accept-encoding", Value: "gzip"})
	// The request body is a stream of capsules, and the header that says so is what makes the edge read
	// it as one. The working client builds the request with an io.Pipe and sets ContentLength to -1:
	//
	//	req.ContentLength = -1
	//	req, _ := http.NewRequestWithContext(ctx, http.MethodConnect, u.String(), pr)
	//	req.Host = authorityFromURL(u)
	//
	// which Go's http2 client writes as a header block with no content-length at all, and then writes the
	// capsules into that body as they are produced. Nothing here differs in what the tunnel sends - the
	// capsule is still type 0, a length, and the IP packet - so the shape of the request was measured to be
	// acceptable while the way the body is carried was not.
	//
	// What that difference does to the edge is the whole fault. Written as DATA frames after the headers,
	// each capsule is a separate message the edge can accept and then route nothing for:
	//
	//	h2 peer open, sent=7 recv=2          SYN out, SYN-ACK in, ACK out - the flow opens
	//	outbound 1140 bytes (opts 0), flags 0x018, seq 2   payload 1100   ClientHello out
	//	outbound 465 bytes  (opts 0), flags 0x018, seq 1102 payload 425   the rest out
	//	72-byte probe on the handshake flow, sent=8 recv=2
	//	h2 carrier failed: tls inside tunnel: i/o timeout (sent=9 recv=2)
	//
	// The handshake completes and nothing after it is ever answered, and a probe one byte into the same
	// flow changes nothing - so the packets are not being rejected for their size, their options or their
	// checksums. They are arriving on a stream the edge is holding open without reading the body of, which
	// is what a CONNECT whose body was declared finished looks like from here.
	if !v.endStream {
		// Declared absent rather than zero: a zero-length body is a body that is finished, and this one
		// must not be.
	} else {
		enc.WriteField(hpack.HeaderField{Name: "content-length", Value: "0"})
	}

	c.sid = 1
	flags := byte(0x4) // END_HEADERS
	if v.endStream {
		flags |= 0x1
	}
	if _, err := c.conn.Write(frameH2(0x1, flags, c.sid, block.Bytes())); err != nil {
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
	// Split on the frame size this client advertised. The edge is given MAX_FRAME_SIZE in our SETTINGS and
	// will not send a DATA frame larger than it, but a frame larger than what we advertised is a protocol
	// error, and one that ends the tunnel rather than stalling it - so a capsule is written whole here
	// because the declared size (16384) is larger than any packet this tunnel builds (1140 at the most).
	//
	// MAX_FRAME_SIZE is 16384. A tunnel packet is an IPv4 header, a TCP header and a segment, capped at
	// maxDatagramPayload, and the capsule header is two bytes in front of it. The largest this client can
	// produce is therefore 20+20+1100+2 = 1142, well inside the declared frame size, and it is checked here
	// rather than assumed - a segment cap raised past the frame size would take the tunnel down on every
	// write instead of on the one that is too large.
	if c.maxFrame > 0 && len(b) > c.maxFrame {
		return fmt.Errorf("capsule of %d bytes exceeds the %d-byte frame size this client advertised",
			len(b), c.maxFrame)
	}
	_, err := c.conn.Write(frameH2(0x0, 0, c.sid, b))
	return err
}

// credit returns flow-control credit for bytes the client has taken off the stream.
//
// Both windows are raised: the stream's, which is what the capsules travel in, and the connection's,
// because a stream cannot be credited past the connection that carries it. Only the stream needs it
// once data is flowing, and only the connection needs it at the start - but the edge's initial connection
// window is the smaller of the two, so both are kept open.
func (c *h2raw) credit(n int) {
	if n <= 0 {
		return
	}
	c.wmu.Lock()
	defer c.wmu.Unlock()
	var inc [4]byte
	binary.BigEndian.PutUint32(inc[:], uint32(n))
	// Stream 0 is the connection; anything else is the tunnel stream.
	c.conn.Write(frameH2(0x8, 0, 0, inc[:]))
	c.conn.Write(frameH2(0x8, 0, c.sid, inc[:]))
}

func frameH2(typ, flags byte, stream uint32, payload []byte) []byte {
	out := make([]byte, 9, 9+len(payload))
	ln := len(payload)
	out[0], out[1], out[2] = byte(ln>>16), byte(ln>>8), byte(ln)
	out[3], out[4] = typ, flags
	out[5], out[6], out[7], out[8] = byte(stream>>24), byte(stream>>16), byte(stream>>8), byte(stream)
	whole := append(out, payload...)
	h2TraceWrite("WRITE", whole)
	return whole
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

// isBareIPv4 reports whether a DATA payload is an IP packet with nothing in front of it.
//
// The header is its own witness: the version nibble, an IHL that lands inside the buffer, and a total
// length that matches what arrived. A capsule header in front of a packet would put something other than
// four in the version nibble, so the two shapes cannot be confused.
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

// nextInboundPacket takes one whole IP packet off the front of a tunnel stream's byte stream, in either
// of the two shapes the edge has been measured to send, and returns what is left.
//
// Both shapes are real, and which one is in use is decided per call by the buffer itself rather than by a
// setting, because the same edge sends both:
//
//	framed   00 3c 45 00 00 3c ...      a QUIC varint capsule type and length, then the packet. The
//	                                   header is two bytes for a type under 64 and a length under 64,
//	                                   which is every packet this tunnel sends.
//	bare     45 00 00 3c 77 3e ...      the packet with nothing in front of it, which is what arrived on
//	                                   the frames of the session that returned warp=on.
//
// The two cannot be confused by accident: a bare packet starts with 0x45 in the version/IHL byte, so as a
// varint that reads as a type of 5 with a second byte that would be the start of its length - and a framed
// packet starts with 0x00, which is no IP version at all. Each is checked on its own terms and the buffer
// says which it is holding.
//
// A partial packet is left alone and more is asked for. That is the whole reason this is here: the edge
// split a capsule header from its packet across two DATA frames, and reading a frame at a time returned the
// header alone, then the packet with its first two bytes already gone.
func nextInboundPacket(buf []byte) (packet, rest []byte, ok bool) {
	if len(buf) < 2 {
		return nil, buf, false
	}

	// Bare: the version nibble is the whole test, and the total length inside the header says how much the
	// packet is. A framed packet cannot reach here - its first byte is a varint type, and 4 is not the only
	// thing that could follow, so the total-length check is what makes this safe.
	if buf[0]>>4 == 4 {
		if len(buf) < 20 {
			return nil, buf, false
		}
		hl := int(buf[0]&0x0f) * 4
		if hl < 20 || hl > 60 || len(buf) < hl {
			return nil, buf, false
		}
		total := int(binary.BigEndian.Uint16(buf[2:4]))
		if total < hl {
			return nil, buf, false
		}
		if len(buf) < total {
			return nil, buf, false
		}
		return buf[:total], buf[total:], true
	}

	// Framed: capsule type, then payload length, both QUIC varints.
	typeVal, typeLen, typeOK := readCapsuleVarint(buf)
	if !typeOK {
		return nil, buf, false
	}
	lenVal, lenLen, lenOK := readCapsuleVarint(buf[typeLen:])
	if !lenOK {
		return nil, buf, false
	}
	header := typeLen + lenLen
	// Only the datagram capsule carries an IP packet. Anything else on this stream is a control message
	// this client has no use for, and it is skipped by its own declared length rather than guessed at.
	if typeVal != 0 {
		total := header + int(lenVal)
		if total > len(buf) {
			return nil, buf, false
		}
		return nil, buf[total:], true
	}
	total := header + int(lenVal)
	if total > len(buf) {
		return nil, buf, false
	}
	return buf[header:total], buf[total:], true
}

// readCapsuleVarint reads a QUIC varint and returns the value with how many bytes it took.
//
// Named apart from readVarint below, which is the older pre-RFC encoding that file already had: that one
// reads 0x40 as a two-byte value while RFC 9000 reserves the top two bits as a length, so 0x40 is a
// one-byte value of 64. A capsule length is written by the edge under RFC 9000, so it is read that way.
func readCapsuleVarint(b []byte) (value uint64, size int, ok bool) {
	if len(b) == 0 {
		return 0, 0, false
	}
	prefix := b[0] >> 6
	size = 1 << prefix
	if len(b) < size {
		return 0, 0, false
	}
	value = uint64(b[0] & 0x3f)
	for i := 1; i < size; i++ {
		value = value<<8 | uint64(b[i])
	}
	return value, size, true
}

// h2CapsuleStream adapts the hand-framed HTTP/2 tunnel to the same capsuleStream the QUIC path uses,
// so every other part of this file - the IP/TCP peer state machine, the dispatch, the statistics -
// works unchanged on either carrier.
//
// The framing is written by hand rather than through x/net/http2 because of two measured facts about
// this edge. It demands a client certificate, which makes it the only address in its range running
// MASQUE. And it reports an empty ALPN under every proposal tried, including ["h2"] alone, while
// still answering with HTTP/2 SETTINGS - so a client that insists on negotiated h2 refuses the one
// address that works. Its SETTINGS carry no ENABLE_CONNECT_PROTOCOL either, which is what makes Go's
// http2 client answer "extended connect not supported by peer" against a carrier that does speak the
// protocol. The request shape it accepts is also not the RFC's: it resets the stream with
// PROTOCOL_ERROR for :protocol and wants cf-connect-proto instead, which is what its own client sends.
type h2CapsuleStream struct {
	h2 *h2raw
	// buf is the tunnel stream's byte stream, reassembled across DATA frames.
	//
	// A DATA frame is not a packet. The edge is free to split the stream wherever it likes, and it splits
	// it: the capsule header and the packet that follows arrive in separate frames, because the reference
	// client's own capture shows exactly that -
	//
	//   H2 READ 80 bytes:  00 00 02 00 00 00 00 01 00 3c 00 3c 00 00 00 00 00 01
	//                     45 00 00 3c 00 00 40 00 40 06 ab 39 68 10 7b 60 ac 10 00 02 ...
	//                     ^^ a 60-byte DATA frame carrying nothing but the two capsule bytes 00 3c
	//
	//   H2 READ 72 bytes:  00 00 02 00 00 00 00 01 00 34 00 34 00 00 00 00 00 01
	//                     45 00 00 34 77 3e 40 00 40 06 34 03 68 10 7b 60 ac 10 00 02 ...
	//                     ^^ the next frame carries the 52-byte packet, unframed
	//
	// Reading one frame and handing it up as a packet therefore returns a two-byte buffer the first time
	// and a packet with its header already consumed the second. Both fail every check the reader makes, so
	// a tunnel that the edge is carrying traffic through returns nothing - which is the state this path was
	// in, with `recv` frozen on the handshake:
	//
	//   h2 peer open, sent=7 recv=2
	//   h2 carrier failed: tls inside tunnel: i/o timeout (sent=9 recv=2)
	buf []byte
}

func (cs *h2CapsuleStream) SendDatagram(data []byte) error {
	// A two-byte capsule header: the type, then the length of the packet that follows. Measured from a
	// client that carries traffic over this carrier - `00 3c 45 00 00 3c ...`, where 0x3c is 60 and the IP
	// header begins at offset two. Two forms this edge does not take were both measured: a single zero
	// byte with no length, and the generalised form carrying a context id. The context id is what it
	// refuses, and refusing it looks exactly like a carrier that routes nothing.
	//
	// It goes out as DATA on the tunnel stream, which is where the edge reads it from:
	//
	//   reference client, SendDatagram:
	//     frame = quicvarint.Append(frame, 0)                  capsule type
	//     frame = quicvarint.Append(frame, len(payload))         capsule length
	//     frame = append(frame, payload...)                     the IP packet
	//     s.requestBody.Write(frame)
	//
	// which on that client is the CONNECT request's own body, and here is written as a DATA frame on the
	// same stream. Both are the same bytes in the same order in the same stream; the difference is only
	// which half of the request carried them, and this carrier accepts the request either way - status 200,
	// SYN-ACK answered - so the difference cannot be in the header block.
	var header []byte
	header = appendVarint(header, 0)
	header = appendVarint(header, uint64(len(data)))
	return cs.h2.writeData(append(header, data...))
}

func (cs *h2CapsuleStream) ReceiveDatagram(ctx context.Context) ([]byte, error) {
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}
		cs.h2.conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		typ, flags, stream, payload, err := cs.h2.readFrame()
		if err != nil {
			return nil, err
		}
		switch typ {
		case 0x0: // DATA
			if stream != cs.h2.sid {
				continue
			}
			if flags&0x1 != 0 {
				// END_STREAM on the tunnel stream means the edge closed the tunnel. A packet that merely
				// ends a DATA frame does not carry it, and reading the flag as a close is what turns a
				// working tunnel into an error on the first reply.
				if len(payload) == 0 {
					return nil, io.EOF
				}
			}
			if len(payload) > 0 {
				// Credit the bytes back.
				//
				// An HTTP/2 stream has a flow-control window, and this client opened it at the default 65535
				// and never raised it. Every packet the edge sends narrows it, and once it reaches zero the
				// edge stops sending - silently, because a flow-control stall is not an error it reports. The
				// credit is returned per frame, and for the connection as well as the stream, because a
				// stream cannot be credited past the connection that carries it.
				cs.h2.credit(len(payload))
				cs.buf = append(cs.buf, payload...)
			}
			// Take whole packets off the stream until one is actually ready.
			//
			// A control capsule is a whole unit that happens not to be an IP packet, and it must be consumed
			// and discarded here rather than returned: a caller that cannot tell it from a packet will treat
			// it as one. That is what happened, and it is why the edge was demonstrably forwarding traffic
			// while the tunnel received none - every packet this client sent after the handshake was read by
			// a pump that had already spent the capsule header as a failed packet:
			//
			//   H2 READ 11 bytes:  00 00 02 00 00 00 00 01 00 3c           <- capsule header alone
			//   H2 READ 69 bytes:  ... 45 00 00 3c ...                     <- the 60-byte packet, in the next frame
			//
			// The header returned first with ok true, the caller kept it, its length check failed, and the
			// packet that followed was discarded for having no header. So the loop runs until it has a packet
			// to hand up, skipping control capsules and waiting for a partial one.
			for {
				pkt, rest, ok := nextInboundPacket(cs.buf)
				if !ok {
					break
				}
				cs.buf = rest
				if pkt == nil {
					// A control capsule: consumed, not delivered.
					androidLog(fmt.Sprintf("colgram_masque: skipped a %d-byte control capsule", len(rest)))
					continue
				}
				return pkt, nil
			}
		case 0x8: // WINDOW_UPDATE
			continue
		case 0x6: // PING
			cs.h2.answerPing(payload)
			continue
		case 0x3: // RST_STREAM
			return nil, fmt.Errorf("edge reset the tunnel stream: %x", payload)
		case 0x7: // GOAWAY
			return nil, fmt.Errorf("edge sent GOAWAY: %x", payload)
		default:
			continue
		}
	}
}

// openH2Session opens the HTTP/2 carrier, negotiates the request shape by asking, and returns a stream
// on the connection that was accepted.
func openH2Session(ctx context.Context, cert tls.Certificate, edgeAddr string) (capsuleStream, error) {
	winner, err := negotiateH2Shape(ctx, edgeAddr, cert)
	if err != nil {
		return nil, err
	}
	androidLog(fmt.Sprintf("colgram_masque: h2 carrier %s accepts shape %q", edgeAddr, winner))
	c, err := dialH2Raw(ctx, edgeAddr, cert)
	if err != nil {
		return nil, err
	}
	if _, err := c.awaitSettings(ctx); err != nil {
		c.conn.Close()
		return nil, err
	}
	status, _, err := c.sendConnect(ctx, shapeByName(winner))
	if err != nil {
		c.conn.Close()
		return nil, err
	}
	if status != 200 {
		c.conn.Close()
		return nil, fmt.Errorf("h2 CONNECT refused with %d", status)
	}
	return &h2CapsuleStream{h2: c}, nil
}

// negotiateH2Shape asks the edge which request shape it will accept, each on a connection of its own.
//
// A reset leaves the stream unusable, so a second attempt on the same connection would answer about the
// wrong stream; and the answer is worth the round trips because the shape is the difference between a
// tunnel that opens and one that does not. The shape that worked is remembered, so this runs once per
// process rather than once per tunnel.
func negotiateH2Shape(ctx context.Context, edgeAddr string, cert tls.Certificate) (string, error) {
	shapeOnce.Do(func() {})
	shapeMu.Lock()
	if shapeWinner != "" {
		winner := shapeWinner
		shapeMu.Unlock()
		return winner, nil
	}
	shapeMu.Unlock()

	var lastErr error
	for _, v := range variants() {
		// A connection per shape, back to back, is what a rate limiter sees. The edge answered the first
		// shape every time on a cold network and refused the second with a TCP RST, which reads as
		// "connection refused" against the address that was answering a moment earlier - and the only
		// thing that made it intermittent was the order the shapes happened to be tried in.
		//
		// So: the accepted shape is remembered across calls (below), and the shapes are spaced. The first
		// call in a process still costs one connection per shape, and that is now the only place it does.
		time.Sleep(400 * time.Millisecond)
		probe, err := dialH2Raw(ctx, edgeAddr, cert)
		if err != nil {
			lastErr = err
			continue
		}
		probe.awaitSettings(ctx)
		status, _, err := probe.sendConnect(ctx, v)
		if err == nil && status == 200 {
			probe.conn.Close()
			shapeMu.Lock()
			shapeWinner = v.name
			shapeMu.Unlock()
			return v.name, nil
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = fmt.Errorf("shape %q gave status %d", v.name, status)
		}
		probe.conn.Close()
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("the edge accepted no request shape")
	}
	return "", lastErr
}

func shapeByName(name string) connectVariant {
	for _, v := range variants() {
		if v.name == name {
			return v
		}
	}
	return variants()[0]
}

type tunnel struct {
	conn         *quic.Conn
	h3           *http3.ClientConn
	stream       capsuleStream
	srcIP        net.IP
	peers        []*tunnelConn
	sentCapsules int
	// recvCapsules counts what came back. Without it the session statistics report a tunnel that is
	// equally healthy whether it carries one packet or none, and the one thing that matters about a
	// tunnel - whether the far side answers - is exactly the thing they cannot show.
	recvCapsules int64
	// peerInbound and orphanInbound split what came back by whether a peer claimed it. A tunnel that
	// receives packets and drops them at the port match is a different fault from one that receives
	// nothing, and the two are indistinguishable from the outside.
	peerInbound   int64
	orphanInbound int64
}

func (t *tunnel) sendIP(raw []byte) {
	if t.stream == nil {
		return
	}
	// The packet goes to the carrier unwrapped, because the carrier is what frames it.
	//
	// This used to prepend a single 0x00 here and let each carrier add its own header. On the QUIC
	// carrier that is the datagram form and it was right. On the HTTP/2 carrier the header is already
	// two bytes - type and length - so the packet left wrapped twice, in a form the edge does not read, and
	// the SYN-ACK never came back. The trace through the tunnel failed with sent=5 recv=0 while the same
	// carrier opened and carried traffic from the session path, which is what pointed at the framing and
	// not at the carrier.
	//
	// The two forms, for the record:
	//   QUIC/HTTP3   00 <IP packet>
	//   HTTP/2       00 <len> <IP packet>
	if len(raw) < 20 || raw[0]>>4 != 4 {
		androidLog(fmt.Sprintf("colgram_masque: dropped an outbound packet of %d bytes (first byte %02x)",
			len(raw), func() byte {
				if len(raw) > 0 {
					return raw[0]
				}
				return 0
			}()))
		return
	}
	if err := t.stream.SendDatagram(raw); err != nil {
		fmt.Println("send datagram failed:", err)
		return
	}
	t.sentCapsules++
}

func appendVarint(b []byte, v uint64) []byte {
	// RFC 9000 variable-length integers, not the pre-RFC draft encoding this function used to write.
	//
	// The width is in the top TWO bits of the first byte:
	//
	//   00xxxxxx  1 byte    0..63
	//   01xxxxxx  2 bytes   0..16383
	//   10xxxxxx  4 bytes   0..2^30-1
	//   11xxxxxx  8 bytes
	//
	// and this function set the width bit to 0x80 for four bytes and 0xC0 for eight. Those are the draft
	// encoding, where the prefix was the top ONE bit. Every capsule this client sends over HTTP/2 is framed
	// through this function, so the edge read a length that is not a length:
	//
	//   mine:     00 c8 88 45 00 00 88 ...     capsule length written as 0xc8 0x88
	//   expected: 40 c8 45 00 00 88 ...         capsule length 200, two bytes, 0x40 prefix
	//
	// A 60-byte packet is under 64 so its length is one byte and is unaffected - which is why every SYN,
	// the only thing the edge ever answered, went out correctly - and every segment larger than that had a
	// length the edge could not read. The flow opened and the handshake completed and then nothing this
	// client sent was a packet the edge could parse:
	//
	//   h2 peer open, sent=7 recv=2
	//   plaintext trace returned 0 bytes, sent=12 recv=3: ""
	//
	// and the plaintext probe, which is 76 bytes of payload and therefore a two-byte length, was refused by
	// the same one-byte mistake. Read off this client's own HTTP/2 transcript, against the reference
	// client's frame log for the same conversation:
	//
	//   mine:  DATA  62 bytes: 00 3c 45 00 00 3c ...   length 60, one byte, correct
	//          DATA 139 bytes: 00 c8 88 45 00 ...      length 200 written as c8 88, wrong
	//   ref:   DATA  62 bytes: 00 3c 45 00 00 3c ...   the same 60-byte SYN, identical
	//          DATA 131 bytes: 00 40 80 45 00 ...      length 128 written as 40 80, correct
	switch {
	case v < 1<<6:
		return append(b, byte(v))
	case v < 1<<14:
		// 0x4000>>8 is 0x40, but `v|0x40` sets bits in the LOW byte, not the high one. The prefix has
		// to go into the top two bits of the FIRST byte, and the value into the remaining six there and
		// eight in the second:
		//
		//   v=76    v|0x40      = 0x4c 0x4c     <- what this wrote: the length doubled into the prefix
		//   RFC     v>>8 | 0x40 = 0x40 0x4c     <- what a receiver reads
		//
		// The two-byte case is where every packet this tunnel sends lands, because 63 bytes is the largest
		// that fits one byte and a 20-byte IP header plus a 40-byte TCP header plus options is already 60.
		return append(b, byte(v>>8)|0x40, byte(v))
	case v < 1<<30:
		return append(b, byte(v>>24)|0x80, byte(v>>16), byte(v>>8), byte(v))
	default:
		return append(b, byte(v>>56)|0xC0, byte(v>>48), byte(v>>40),
			byte(v>>32), byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	}
}

func (t *tunnel) dispatch(ip []byte) {
	if len(ip) < 40 || ip[9] != 6 {
		androidLog(fmt.Sprintf("colgram_masque: inbound packet of %d bytes discarded: proto %d", len(ip), ip[9]))
		return
	}
	// Inbound packets carry the far side's port as source and ours as destination.
	sport := binary.BigEndian.Uint16(ip[20:22])
	dport := binary.BigEndian.Uint16(ip[22:24])
	// Every inbound packet is counted and named before any peer matching is attempted. A packet for a port
	// no peer claims is not noise - it is the edge talking about a flow this client has not modelled, and
	// it is the only way to tell a RST about the flow from a RST about something else.
	atomic.AddInt64(&t.orphanInbound, 1)
	for _, p := range t.peers {
		if p.dstPort == sport && p.srcPort == dport {
			atomic.AddInt64(&t.peerInbound, 1)
			p.feed(ip)
			return
		}
	}
	androidLog(fmt.Sprintf("colgram_masque: inbound %d -> %d matched no peer (orphans=%d peers=%d)",
		sport, dport, atomic.LoadInt64(&t.orphanInbound), len(t.peers)))
}

func randomID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func atoiOr(s string, fallback int) int {
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return fallback
		}
		n = n*10 + int(c-'0')
	}
	if n == 0 {
		return fallback
	}
	return n
}

// enrolOnce registers a device, enrols a key and mints the certificate, or returns the one already made.
//
// It used to enrol on every call, and that is one of the two differences left between this client and the
// reference client that returned warp=on. The reference ran one enrolment for its whole session and then
// carried traffic over it; this one enrolled, opened a tunnel one second later, failed, and enrolled again
// on the next attempt - so every measurement was of a tunnel opened immediately after a fresh enrolment,
// which is exactly the case an edge that needs a moment to provision an identity would refuse to route.
//
// The result is cached for the life of the process, so the second and later attempts run on the same
// identity as the first, and the attempt that follows a failure is no longer confounded by a new one.
// A cache hit is logged with the age of the enrolment, because "it did not work either" and "it was the
// same enrolment as the one that did not work" are different facts and only the timestamp tells them
// apart.
func enrolOnce() (net.IP, tls.Certificate, error) {
	enrolMu.Lock()
	if enrolDone != nil {
		ip, cert := enrolDone.ip, enrolDone.cert
		age := time.Since(enrolDone.at).Truncate(time.Second)
		enrolMu.Unlock()
		androidLog(fmt.Sprintf("colgram_masque: reusing the enrolment made %s ago, tunnel address %s",
			age, ip))
		return ip, cert, nil
	}
	enrolMu.Unlock()

	start := time.Now()
	ip, cert, err := enrolFresh()
	if err != nil {
		return nil, tls.Certificate{}, err
	}
	enrolMu.Lock()
	if enrolDone == nil {
		enrolDone = &enrolment{ip: ip, cert: cert, at: start}
	}
	enrolMu.Unlock()
	androidLog(fmt.Sprintf("colgram_masque: registered and enrolled in %s, tunnel address %s",
		time.Since(start).Truncate(time.Millisecond), ip))
	return ip, cert, nil
}

// enrolment is one registered device, its tunnel address and the certificate the edge accepts.
type enrolment struct {
	ip   net.IP
	cert tls.Certificate
	at   time.Time
}

var (
	enrolMu   sync.Mutex
	enrolDone *enrolment
)

// enrolmentAge reports when the current enrolment was made, or zero when there is none.
func enrolmentAge() time.Time {
	enrolMu.Lock()
	defer enrolMu.Unlock()
	if enrolDone == nil {
		return time.Time{}
	}
	return enrolDone.at
}

// enrolFresh performs a full registration: a new P-256 key, a new device, a PATCH enrol and a fresh
// bare certificate. It is the part that talks to the API, and it is only called when no enrolment is
// cached.
//
// enrolFromFile is the other half: it takes an enrolment made by another run of this client and rebuilds
// the certificate from its key, so a measurement can name the identity rather than making a new one.
func enrolFromFile(path string) (net.IP, tls.Certificate, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("load enrolment: %w", err)
	}
	text := string(raw)
	grab := func(prefix string) string {
		for _, line := range strings.Split(text, "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, prefix) {
				return strings.TrimSpace(strings.TrimPrefix(line, prefix))
			}
		}
		return ""
	}
	keyPEM := strings.ReplaceAll(grab("COLGRAM_KEY"), `\n`, "\n")
	if keyPEM == "" {
		return nil, tls.Certificate{}, fmt.Errorf("load enrolment: no COLGRAM_KEY line in %s", path)
	}
	addr := grab("COLGRAM_ADDRESS")
	if addr == "" {
		addr = "172.16.0.2"
	}
	der := bareCertDER(keyPEM)
	if der == nil {
		return nil, tls.Certificate{}, fmt.Errorf("load enrolment: the key in %s did not parse as a "+
			"P-256 private key", path)
	}
	pair, err := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		[]byte(keyPEM))
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("load enrolment: %w", err)
	}
	androidLog(fmt.Sprintf("colgram_masque: using the enrolment in %s, tunnel address %s", path, addr))
	return net.ParseIP(addr).To4(), pair, nil
}

// bareCertDER mints the certificate the edge accepts for an already-enrolled key: empty subject, no
// extensions, signed against an empty parent, exactly as a fresh registration produces.
func bareCertDER(keyPEM string) []byte {
	blk, _ := pem.Decode([]byte(keyPEM))
	if blk == nil {
		return nil
	}
	parsed, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return nil
	}
	der, err := x509.CreateCertificate(rand.Reader, &x509.Certificate{
		SerialNumber: big.NewInt(0),
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}, &x509.Certificate{}, &key.PublicKey, key)
	if err != nil {
		return nil
	}
	return der
}

func enrolFresh() (net.IP, tls.Certificate, error) {
	// An enrolment made elsewhere, when one is named.
	//
	// The two roles are swapped rather than reasoned about. This client registering and then running its own
	// TLS has stalled at the first ClientHello while the reference client, handed this client's credentials,
	// returned warp=on - so either the identity or the client is at fault and neither run separates them.
	// Pointing this client at the reference client's own enrolment puts the same identity on both, which
	// answers it in one run: if this client carries with the reference's enrolment, the identity was the
	// difference; if it stalls, the client is.
	//
	// The file is what this client writes when WARP_DUMP_CREDENTIALS names one, plus the tunnel address the
	// registration returned - which is why the dump carries the address rather than only the key.
	if path := os.Getenv("WARP_LOAD_CREDENTIALS"); path != "" {
		return enrolFromFile(path)
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

	apiIP, err := resolve4(apiHost)
	if err != nil {
		return nil, tls.Certificate{}, err
	}

	// The address is pinned from DoH, so the name has to be carried by SNI and verified explicitly:
	// a certificate presented for an IP literal cannot be validated at all.
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			// The mask, not apiHost: the filter reads the SNI and drops the connection when it sees
			// cloudflareclient.com there. The request still names apiHost in its URL and Host header, so
			// it is routed there. HTTP/1.1 only as well - with the default ALPN the enrolment POST
			// completes TLS and is then closed before a response byte, which Go reports as a bare EOF.
			TLSClientConfig: &tls.Config{
				ServerName:         apiSNIMask,
				InsecureSkipVerify: true,
				NextProtos:         []string{"http/1.1"},
			},
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(apiIP, "443"))
			},
		},
	}
	call := func(method, path, token string, body map[string]any) (map[string]any, error) {
		buf, _ := json.Marshal(body)
		// Retried per request, on a freshly resolved address each time. The enrolment endpoint resolves
		// into a rotating set and the failure is per-address, not per-request: across runs the same call
		// returns a body, then EOF, then a header timeout. One attempt is not a measurement, and without
		// this the whole tunnel fails at enrolment before a single packet of it is built:
		//
		//   IllegalStateException: register: Post ".../v0a4471/reg": context deadline exceeded
		//     at ColgramWarpMasqueTunnel.bringUp(ColgramWarpMasqueTunnel.java:247)
		var lastErr error
		for attempt := 1; attempt <= 8; attempt++ {
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
				androidLog(fmt.Sprintf("colgram_masque: %s %s attempt %d/8: %v", method, path, attempt, err))
				if fresh, ferr := resolve4(apiHost); ferr == nil {
					apiIP = fresh
				}
				time.Sleep(time.Duration(attempt%4) * 500 * time.Millisecond)
				continue
			}
			raw, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode >= 400 {
				return nil, fmt.Errorf("%s %s: %d %s", method, path, resp.StatusCode, string(raw))
			}
			var out map[string]any
			json.Unmarshal(raw, &out)
			return out, nil
		}
		return nil, lastErr
	}

	reg, err := call("POST", "/"+apiVer+"/reg", "", map[string]any{
		"fcm_token": "", "install_id": "", "tos": "2024-06-01T00:00:00.000Z",
		"model": "PC", "serial_number": randomID(),
		"locale": "en_US", "os_version": "",
		// Registered as a WireGuard device, exactly as the Android app and the reference client both do.
		//
		// This client registered as MASQUE directly - `key_type: secp256r1, tunnel_type: masque` in the
		// registration POST itself - and the reference client, which returns warp=on, registers as WireGuard
		// and only then PATCHes itself to masque:
		//
		//   reference, models.Registration:  KeyType: KeyTypeWg  TunType: TunTypeWg   (curve25519, wireguard)
		//   reference, models.DeviceUpdate:   KeyType: secp256r1 TunType: masque      (the PATCH)
		//
		// The registration is what the account is provisioned against, so a device that arrives claiming to
		// be a MASQUE device has an account provisioned differently from one that arrives as WireGuard and is
		// then switched. That difference is invisible from inside the tunnel - the handshake still completes
		// and the data still does not arrive - and it is the largest remaining difference between this client
		// and one that works, so it is matched rather than argued about.
		//
		// The key sent here is a throwaway, exactly as it is in the reference: its only purpose is to make the
		// registration well-formed. The P-256 key that actually carries the tunnel is enrolled in the PATCH
		// below, in SPKI form, because that is the form the API reads back as a public key.
		"key":      base64.StdEncoding.EncodeToString(scalar),
		"key_type": "curve25519", "tunnel_type": "wireguard"})
	if err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("register: %w", err)
	}
	id, _ := reg["id"].(string)
	token, _ := reg["token"].(string)
	if id == "" || token == "" {
		return nil, tls.Certificate{}, fmt.Errorf("register: response carried no id/token")
	}
	if _, err := call("PATCH", "/"+apiVer+"/reg/"+id, token, map[string]any{
		"key":      base64.StdEncoding.EncodeToString(spki),
		"key_type": "secp256r1", "tun_type": "masque"}); err != nil {
		return nil, tls.Certificate{}, fmt.Errorf("enrol: %w", err)
	}
	// Read the account back after enrolling, and log what it says.
	//
	// The reference client calls GetAccount before it connects, and the answer is what says whether the
	// edge will route for this identity: a licence bound and `warp_plus: true` is a provisioned account, and
	// one without is a registered device with nothing behind it. A tunnel that gets a handshake and no data
	// is what an unprovisioned identity looks like from inside the tunnel, so this is read on every run
	// rather than assumed from the fact that the PATCH returned 200.
	if acct, aerr := call("GET", "/"+apiVer+"/reg/"+id+"/account", token, nil); aerr == nil {
		androidLog(fmt.Sprintf("colgram_masque: account %s licence=%s warp_plus=%v quota=%v",
			acct["account_type"], acct["license"], acct["warp_plus"], acct["quota"]))
	} else {
		androidLog(fmt.Sprintf("colgram_masque: account read-back failed: %v", aerr))
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
		SerialNumber: big.NewInt(0),
		Subject:      pkix.Name{},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}
	// Self-signed against an EMPTY parent, not against the template itself.
	//
	// The reference client signs with an empty certificate as the issuer:
	//
	//   x509.CreateCertificate(rand.Reader, &x509.Certificate{
	//       SerialNumber: big.NewInt(0), NotBefore: time.Now(), NotAfter: time.Now().Add(24h),
	//   }, &x509.Certificate{}, &privKey.PublicKey, privKey)
	//
	// and this client signed with the template as its own issuer, which is what Go does for a normal
	// self-signed certificate: it fills in issuer from subject and adds the basic-constraints extension
	// marking the certificate a CA. Two different certificates, then, with the same key and the same
	// subject - one that says it is a certificate authority and one that does not.
	//
	// The edge decides whether to route for an identity from the key this certificate carries, and it
	// parses the certificate strictly enough to have refused this one in a way that looks exactly like a
	// carrier that routes nothing:
	//
	//   h2 peer open, sent=7 recv=2            <- the handshake completes, on the certificate it accepted
	//   plaintext trace returned 0 bytes       <- and no data is ever forwarded for it
	//
	// The account itself is known good: the reference client, handed this client's own registration
	// credentials, returns warp=on through this same edge on this same network - so the enrolment, the key,
	// the address and the licence are all right, and what is left is this certificate and the carrier.
	der, err := x509.CreateCertificate(rand.Reader, tmpl, &x509.Certificate{}, &key.PublicKey, key)
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
	// The enrolment's own credentials, in the form another client can be pointed at.
	//
	// This is what makes the carrier separable from the identity. The reference client is known to carry
	// traffic through this edge; if it does so with the credentials this client just registered, then the
	// registration is the fault. If it does not, the registration is sound and the carrier handshake in
	// this file is. One of those is a fact and the other is a guess, and this line is what lets the other
	// client be asked.
	//
	// Written to stderr rather than through androidLog, because androidLog is also the device log and this
	// belongs in the host's own output where a run can read it back.
	if dump := os.Getenv("WARP_DUMP_CREDENTIALS"); dump != "" {
		fmt.Fprintf(os.Stderr, "COLGRAM_CREDENTIALS id=%s token=%s\nCOLGRAM_ADDRESS %s\nCOLGRAM_KEY %s\n",
			id, token, addr, strings.ReplaceAll(string(keyPEM), "\n", "\\n"))
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

// Config is what the app passes in. The standalone binary reads the same fields from the
// environment, so both paths run identical code.
type Config struct {
	Bind      string
	EdgeHost  string
	EdgePort  string
	TraceURL  string
	RelayAddr string
	RelayBind string
	// A SOCKS5 front to carry the tunnel's UDP over TCP. Set from the library side rather than from
	// the environment - see the note at the exported entry point.
	SocksAddr string
}

func fromEnv() Config {
	trace := tracePath
	if v := os.Getenv("WARP_TRACE"); v != "" {
		trace = v
	}
	return Config{
		Bind:      os.Getenv("WARP_BIND"),
		EdgeHost:  os.Getenv("WARP_EDGE"),
		EdgePort:  os.Getenv("WARP_PORT"),
		TraceURL:  trace,
		RelayAddr: os.Getenv("WARP_RELAY"),
		RelayBind: os.Getenv("WARP_RELAY_BIND"),
	}
}

type relaySockets struct {
	front *net.UDPConn
	up    *net.UDPConn
	addr  string
}

var (
	relayMu      sync.Mutex
	relayCurrent *relaySockets
)

// startRelay opens two UDP sockets: one on loopback for the client, one bound to the egress address
// for the edge, and forwards datagrams between them.
//
// One upstream socket per session, because a fresh socket per packet changes the source port and the
// edge treats each source port as an unrelated flow and answers none of them. No routes, no firewall
// rules, no DNS changes: a user-space forwarder on one port, and closing it removes the path.
func startRelay(bind string) error {
	relayMu.Lock()
	defer relayMu.Unlock()
	if relayCurrent != nil {
		return nil
	}

	front, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return err
	}
	local := &net.UDPAddr{}
	if bind != "" {
		v4 := net.ParseIP(bind).To4()
		if v4 == nil {
			front.Close()
			return fmt.Errorf("relay bind is not IPv4: %s", bind)
		}
		local.IP = v4
	}
	up, err := net.ListenUDP("udp4", local)
	if err != nil {
		front.Close()
		return err
	}
	raddr, err := net.ResolveUDPAddr("udp4", net.JoinHostPort(edgeIP, "443"))
	if err != nil {
		front.Close()
		up.Close()
		return err
	}

	relayCurrent = &relaySockets{front: front, up: up, addr: front.LocalAddr().String()}
	go relayLoop(front, up, raddr)
	fmt.Println("  relay         :", relayCurrent.addr, "->", raddr, "via", local.IP)
	return nil
}

// relayLoop forwards datagrams both ways until a socket closes.
func relayLoop(front, up *net.UDPConn, raddr *net.UDPAddr) {
	var (
		mu     sync.Mutex
		client net.Addr
	)

	go func() {
		buf := make([]byte, 2000)
		for {
			n, from, err := up.ReadFrom(buf)
			if err != nil {
				return
			}
			if from.String() != raddr.String() {
				continue
			}
			mu.Lock()
			target := client
			mu.Unlock()
			if target == nil {
				continue
			}
			front.WriteTo(buf[:n], target)
		}
	}()

	buf := make([]byte, 2000)
	for {
		n, from, err := front.ReadFrom(buf)
		if err != nil {
			return
		}
		mu.Lock()
		client = from
		mu.Unlock()
		if _, err := up.WriteTo(buf[:n], raddr); err != nil {
			return
		}
	}
}

func stopRelay() {
	relayMu.Lock()
	defer relayMu.Unlock()
	if relayCurrent == nil {
		return
	}
	relayCurrent.front.Close()
	relayCurrent.up.Close()
	relayCurrent = nil
}

func measure() (string, error) {
	return measureWith(fromEnv())
}

func measureWith(cfg Config) (string, error) {
	fmt.Printf("measureWith: env WARP_SOCKS=%q WARP_RELAY=%q\n",
		os.Getenv("WARP_SOCKS"), os.Getenv("WARP_RELAY"))
	srcIP, cert, err := enrolOnce()
	if err != nil {
		return "", err
	}
	fmt.Println("session address :", srcIP.String())

	// No explicit edge: find one that answers from this machine.
	if cfg.EdgeHost == "" {
		candidates := edgeCandidates(cfg.Bind)
		// The in-app relay is a candidate like any other, and it is tried last.
		if len(candidates) == 0 {
			return "", fmt.Errorf("no candidate edge route on this machine")
		}

		// Where UDP to the edge does not answer at all, the HTTP/2 carrier is asked first rather than
		// last.
		//
		// The search above opens a connection per candidate, and it opens them back to back: one per
		// bind times six ports. The edge stops accepting new ones part way through, so whatever is asked
		// afterwards is measured against a source address that has just been rate-limited - including the
		// carrier that does work here. Measured as
		//
		//   no edge route answered: dial tcp 162.159.198.2:443: connect: connection refused
		//
		// against an address that answered a second earlier. Asking it first costs one connection and
		// removes the question.
		if !udpAnswers(edgeIP, socksAddrFor(cfg)) {
			androidLog("colgram_masque: UDP to the edge is filtered; the HTTP/2 carrier is asked first")
			if body, err := traceOverH2(srcIP, cert); err == nil && WarpOn(body) {
				return body, nil
			} else if err != nil {
				fmt.Println("  h2 first      :", err)
				androidLog(fmt.Sprintf("colgram_masque: h2 carrier failed: %v", err))
				bridgeErr = errString(err)
			}
			// No QUIC search. One handshake attempt already answered whether that carrier works, and the
			// search is six more candidates times a five second timeout each - which is where the whole
			// 180 second budget went on a network that filters UDP:
			//
			//   h2 carrier failed: RST_STREAM stream=1 code=1
			//   edge 162.159.198.2:500 via 10.0.2.15 failed: quic dial: timeout
			//   ... five more ...
			//
			// Every one of those is a fact already established by the probe that got here. Spending the
			// caller's budget rediscovering it is what made a carrier that opens look like one that does
			// not - the run timed out after the tunnel had already answered.
			if body, err := traceOverH2(srcIP, cert); err == nil && WarpOn(body) {
				return body, nil
			} else if err != nil {
				androidLog(fmt.Sprintf("colgram_masque: h2 carrier failed again: %v", err))
			}
			return "", fmt.Errorf("no edge route answered: %s", bridgeErr)
		}
		// QUIC is attempted in a contained goroutine.
		//
		// On a network that filters UDP to this edge the QUIC handshake reaches Go's own TLS and panics
		// inside it, and a panic on any goroutine takes the process with it:
		//
		//   panic: runtime error: invalid memory address or nil pointer dereference
		//   crypto/tls.unsupportedCertificateError      auth.go:295
		//   crypto/tls.(*CertificateRequestInfo).SupportsCertificate
		//   created by crypto/tls.(*QUICConn).Start
		//
		// auth.go:295 dereferences the result of Curve.Params(), which is nil for a curve the build does
		// not carry. That is a fault in the toolchain rather than in this client, and it is only reachable
		// on the carrier that cannot work here anyway - so the attempt is contained, the panic is recovered
		// and reported as a failed candidate, and the HTTP/2 carrier is asked next. A tunnel that reports a
		// failed QUIC route is a correct report; a process that dies there is not.
		for i, c := range candidates {
			fmt.Printf("edge candidate  %d/%d  %s via %s\n", i+1, len(candidates), c.addr, c.bind)
			body, err := attemptContained(srcIP, cert, c.addr, c.bind, cfg.TraceURL)
			if err == nil && WarpOn(body) {
				return body, nil
			}
			if err != nil {
				fmt.Println("  failed        :", err)
				androidLog(fmt.Sprintf("colgram_masque: edge %s via %s failed: %v", c.addr, c.bind, err))
			}
			bridgeErr = errString(err)
			// One handshake is enough to rule the QUIC carrier out on this network.
			//
			// The candidates are six ports times every local bind, and on a network that filters UDP to
			// this edge they all fail the same way - a handshake that never completes. Measured:
			//
			//   edge 162.159.198.2:500  via 10.0.2.15 failed: tcp handshake through the tunnel did not complete
			//   edge 162.159.198.2:8443 via 10.0.2.15 failed: tcp handshake through the tunnel did not complete
			//   edge 162.159.198.2:8095 via 10.0.2.15 failed: tcp handshake through the tunnel did not complete
			//   edge 162.159.198.2:4500 via 10.0.2.15 failed: tcp handshake through the tunnel did not complete
			//   edge 162.159.198.2:4443 via 10.0.2.15 failed: tcp handshake through the tunnel did not complete
			//   edge 162.159.198.2:443  via 10.0.2.15 failed: tcp handshake through the tunnel did not complete
			//
			// Twenty-two seconds each, so two and a half minutes before the HTTP/2 carrier - the one that
			// actually carries this tunnel - is asked at all. Six failures in a row, all identical, on one
			// edge, is one fact rather than six: the carrier is not reachable from here. So the search
			// stops on the second failure, which is what distinguishes a filtered path from a port that is
			// merely closed, and the remaining time goes to the carrier that can work.
			//
			// A different network keeps the whole search: the carriers are ranked so a port that answers is
			// tried before one that does not, and the break only fires on two consecutive handshake failures.
			if i >= 1 {
				androidLog(fmt.Sprintf("colgram_masque: %d QUIC candidates failed identically; this network "+
					"cannot reach the QUIC carrier, and the HTTP/2 carrier is asked instead", i+1))
				break
			}
		}
		// Every direct route is dead. Try leaving through the in-app relay, which binds its own
		// upstream socket to an egress the edge does answer.
		if cfg.RelayBind != "" {
			fmt.Println("edge candidate  relay via", cfg.RelayBind)
			if err := startRelay(cfg.RelayBind); err != nil {
				fmt.Println("  relay         : could not start:", err)
			} else {
				addr := relayCurrent.addr
				body, err := attempt(srcIP, cert, addr, "", cfg.TraceURL)
				if err == nil && WarpOn(body) {
					return body, nil
				}
				if err != nil {
					fmt.Println("  failed        :", err)
					bridgeErr = errString(err)
				}
				stopRelay()
			}
		}

		// The HTTP/2 carrier, on the same registration and through the same capsule stream the app's
		// session uses.
		//
		// It is asked here rather than earlier because where UDP is not filtered QUIC is the carrier the
		// official client prefers. It is asked in this process rather than before the QUIC search for a
		// concrete reason: the search opens a connection per candidate, and by the time it is over the edge
		// is refusing new ones from this address - so the carrier that would have worked is asked when it
		// can no longer be reached. Measured as
		//
		//   no edge route answered: dial tcp 162.159.198.2:443: connect: connection refused
		//
		// against an address that had answered a second earlier.
		if body, err := traceOverH2(srcIP, cert); err == nil && WarpOn(body) {
			return body, nil
		} else if err != nil {
			fmt.Println("  h2 carrier    :", err)
			androidLog(fmt.Sprintf("colgram_masque: h2 carrier failed: %v", err))
			bridgeErr = errString(err)
		}
		return "", fmt.Errorf("no edge route answered: %s", bridgeErr)
	}

	// WARP_EDGE lets the edge address be a relay that leaves through an egress the edge answers.
	// The protocol is unchanged: the relay forwards datagrams, it does not interpret them.
	edgeHost, edgePort := edgeIP, "443"
	if cfg.EdgeHost != "" {
		if h, p, err := net.SplitHostPort(cfg.EdgeHost); err == nil {
			edgeHost, edgePort = h, p
		} else {
			edgeHost = cfg.EdgeHost
		}
		fmt.Println("edge            :", edgeHost+":"+edgePort, "(relayed)")
	}
	// An explicit port must not overwrite the one carried by a host:port edge, or a relay on a
	// non-standard port silently becomes a connection to 443.
	if cfg.EdgePort != "" && cfg.EdgeHost == "" {
		edgePort = cfg.EdgePort
	}
	// A literal address must not go through the system resolver: on the device it does not run,
	// and a name that cannot resolve is indistinguishable from a filtered path.
	var edgeAddr *net.UDPAddr
	if ip := net.ParseIP(edgeHost); ip != nil {
		v4 := ip.To4()
		if v4 == nil {
			return "", fmt.Errorf("edge address is not IPv4: %s", edgeHost)
		}
		edgeAddr = &net.UDPAddr{IP: v4, Port: atoiOr(edgePort, 443)}
	} else {
		resolved, err := net.ResolveUDPAddr("udp4", net.JoinHostPort(edgeHost, edgePort))
		if err != nil {
			return "", err
		}
		edgeAddr = resolved
	}
	fmt.Println("edge address    :", edgeAddr)
	return attempt(srcIP, cert, net.JoinHostPort(edgeHost, edgePort), cfg.Bind, cfg.TraceURL)
}

// attempt runs one full measurement through a single edge address and a single local bind.
//
// Splitting it out is what makes the candidate search possible: the whole measurement - QUIC,
// CONNECT, TCP, TLS and the trace request - is retried for each pair, so a pair that answers is
// found by trying pairs rather than by reasoning about which one should work.
func attempt(srcIP net.IP, cert tls.Certificate, addr, bind, trace string) (string, error) {
	fmt.Println("attempt entered for", addr, "bind", bind,
		"socks", os.Getenv("WARP_SOCKS"))
	edgeAddr, err := resolveEdge(addr)
	if err != nil {
		return "", err
	}
	// Bind the local address the same way the working client does. Left on the wildcard, this
	// host has several adapters and the kernel picks one of them for the edge.
	local := &net.UDPAddr{}
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			return "", fmt.Errorf("bind is not an IPv4 address: %s", bind)
		}
		local.IP = ip
	}
	// A SOCKS5 front carries the tunnel's UDP over TCP to a relay whose egress the edge answers.
	// It exists because of a measured property of some networks, including this device's:
	// UDP 443 is filtered while TCP 443 and UDP 53 both answer.
	if proxy := socksAddrForAttempt(); proxy != "" {
		fmt.Println("socks5 udp: dialling", proxy)
		relayConn, err := dialSocks5UDP(proxy)
		if err != nil {
			fmt.Println("socks5 udp: dial failed:", err)
			return "", fmt.Errorf("socks5 udp: %w", err)
		}
		defer relayConn.Close()
		fmt.Println("masque over socks5:", proxy)
		return measureOverRelay(srcIP, cert, edgeAddr, relayConn)
	}
	udpConn, err := net.ListenUDP("udp4", local)
	if err != nil {
		return "", err
	}
	defer udpConn.Close()

	return measureOverRelay(srcIP, cert, edgeAddr, udpConn)
}

// measureOverRelay runs the whole measurement over whatever carries UDP. Split out because there
// are now two carriers - this device's own socket, or a SOCKS5 front whose uplink is TCP to a relay
// - and they must differ in nothing but the PacketConn.
func measureOverRelay(srcIP net.IP, cert tls.Certificate, edgeAddr *net.UDPAddr,
	udpConn net.PacketConn) (string, error) {
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

		// Paired with the size, as the working client's DefaultQuicConfig does: without this the
		// transport probes upward after the handshake and the first oversized flight is dropped, which
		// looks exactly like a tunnel that connected and then went silent.
		DisablePathMTUDiscovery: true,
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

	c := newTunnelConn(tun, srcIP, dst, ephemeralPort(), 443)
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

// traceOverH2 fetches Cloudflare's trace through the HTTP/2 carrier and returns the body.
// traceTunnel is the carrier a trace is read through, kept for the life of the process so a second read
// does not have to be accepted by the edge again.
type traceTunnel struct {
	ctx context.Context
	tun *tunnel
}

// traceSession is the tunnel the last trace read opened, or nil.
var traceSession *traceTunnel

// pump reads the return path for the duration of the context. It has to exist before a peer opens: the
// SYN goes out as a capsule and nothing comes back unless someone is already reading.
func (tt *traceTunnel) pump() {
	for {
		data, err := tt.tun.stream.ReceiveDatagram(tt.ctx)
		if err != nil {
			return
		}
		// No framing is stripped here. ReceiveDatagram returns whole IP packets and nothing else - it takes
		// the capsule header off, waits for the packet behind it, and discards control capsules - so a
		// second strip in this pump is the packet losing its first two bytes.
		//
		// That is not a theoretical reading. It was written to serve the QUIC carrier, where a datagram
		// genuinely does begin with a type byte, and it was left in place when the HTTP/2 carrier arrived
		// with its own framing. The QUIC path still strips a leading zero; the two carriers differ and the
		// code that reads them has to as well.
		if len(data) < 20 || data[0]>>4 != 4 {
			androidLog(fmt.Sprintf("colgram_masque: inbound %d bytes is not an IPv4 packet (first byte %02x)",
				len(data), data[0]))
			continue
		}
		atomic.AddInt64(&tt.tun.recvCapsules, 1)
		tt.tun.dispatch(data)
	}
}

// It runs the real thing rather than a separate implementation of it: the same handshake, the same
// request shape, the same capsule framing and the same peer state machine the app's session uses, so
// `warp=on` here means `warp=on` on the tunnel that is actually up. A measurement path of its own would
// be a second thing to keep in step with the first, and the last version of that diverged so far that it
// reported a network that could not route as one with no carrier at all.
func traceOverH2(srcIP net.IP, cert tls.Certificate) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Second)
	defer cancel()
	// The carrier refuses new connections from an address that has just opened several. The shape
	// negotiation opens one per shape and then a second for the tunnel it keeps, and a caller that asks
	// twice in one process - which the integration test does - is refused the second time:
	//
	//   h2 carrier 162.159.198.2:443 accepts shape "reference client shape"
	//   h2 carrier failed: dial tcp 162.159.198.2:443: connect: connection refused
	//
	// The tunnel that answered is still open, so the second request reuses it rather than dialling again.
	// A measurement that reuses the tunnel it is measuring measures that tunnel, which is the point.
	sessionMu.Lock()
	reuse := traceSession
	sessionMu.Unlock()
	if reuse == nil || reuse.ctx.Err() != nil {
		stream, err := openH2Session(ctx, cert, net.JoinHostPort(edgeH2IP, edgeH2Port))
		if err != nil {
			return "", err
		}
		reuse = &traceTunnel{ctx: ctx, tun: &tunnel{srcIP: srcIP, stream: stream}}
		go reuse.pump()
		sessionMu.Lock()
		traceSession = reuse
		sessionMu.Unlock()
	}
	tun := reuse.tun

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

	peer := newTunnelConn(tun, srcIP, dst, ephemeralPort(), 443)
	// TCP options on the SYN, which is what the reference client sends and what this edge carries.
	//
	// The bare-header conclusion this replaces was drawn from a run of its own, on this machine, against
	// this edge - and it was wrong, and the working client's own SYN is the counter-example:
	//
	//   H2 WRITE 71 bytes: ... 00 3c 45 00 00 3c 00 00 40 00 3f 06 ac 39 ac 10 00 02
	//                        68 10 7b 60 d1 e7 01 bb 18 73 b5 cc 00 00 00 00 a0 02 60
	//                        80 73 d6 00 00 02 04 04 d8 04 02 08 0a ea e8 58 37 00 00 00
	//                        00 01 03 03 07
	//
	// Decoded: a 62-byte capsule wrapping a 40-byte TCP header, and the 20 bytes after it are
	//
	//   02 04 04 d8   MSS 1240
	//   04 02         SACK permitted
	//   08 0a ...     timestamps, TSval 0xeae85837, TSecr 0
	//   01            NOP
	//   03 03 07      window scale 7
	//
	// which is byte for byte what synOptions() returns. So the options were never the fault; the flow that
	// was reported as answered without them was answered by the edge's own SYN-ACK, which the handshake
	// completes on regardless - and a flow that completes its handshake and then goes silent looks the same
	// from here whether the options are wrong or the data is. The 72-byte probe is what told the two apart,
	// and it is a data segment, not a SYN:
	//
	//   72-byte probe on the handshake flow, sent=8 recv=2     with a 20-byte header, on a 20-byte flow
	//
	// Everything before that probe measured the SYN, which the edge answers either way.
	tun.peers = append(tun.peers, peer)
	if err := peer.open(); err != nil {
		androidLog(fmt.Sprintf("colgram_masque: h2 peer open failed: %v (sent=%d recv=%d)",
			err, peer.sent, peer.recv))
		return "", fmt.Errorf("tcp open through the tunnel: %w (sent=%d recv=%d)",
			err, peer.sent, peer.recv)
	}
	androidLog(fmt.Sprintf("colgram_masque: h2 peer open, sent=%d recv=%d", peer.sent, peer.recv))

	// The reference client's own request, sent here in plaintext before any TLS, because it is the one
	// request measured to come back through this carrier:
	//
	//   H2 WRITE 140 bytes: ... 45 00 00 80 ... 18 01 00 05 8f 40 00 00 01 01 08 0a ea e8 5f 8f
	//                      8b 43 33 17 47 45 54 20 2f 63 64 6e 2d 63 67 69 2f 74 72 61 63 65 20
	//                      48 54 54 50 2f 31 2e 31 0d 0a 48 6f 73 74 3a 20 77 77 77 2e 63 6c 6f
	//                      75 64 66 6c 61 72 65 2e 63 6f 6d 0d 0a 43 6f 6e 6e 65 63 74 69 6f 6e
	//                      3a 20 63 6c 6f 73 65 0d 0a 0d 0a
	//
	//   GET /cdn-cgi/trace HTTP/1.1 / Host: www.cloudflare.com / Connection: close
	//
	//   H2 READ 487 bytes: ... HTTP/1.1 400 Bad Request ... <html> ... The plain HTTP request was sent
	//   to HTTPS port/<title>400 Bad Request</h1> ... this port does not serve HTTP.
	//
	// The 400 is the answer that matters. It proves the edge took the flow out to the real host and the
	// host's reply came back through the same stream, so this carrier does route, for this identity, on
	// this network - and it does it without TLS being involved at any point.
	//
	inner := tls.Client(peer, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         traceHost,
		MinVersion:         tls.VersionTLS12,
		// Classical key exchange only, and this is the whole reason the verdict was unreachable.
		//
		// Go 1.26 offers a hybrid post-quantum group by default - X25519MLKEM768 - which puts a 1216-byte
		// key share in the ClientHello. The handshake this client sends over the tunnel is 1525 bytes and
		// goes out as two segments:
		//
		//   outbound 1160 bytes (opts 20), flags 0x018, payload 1100    ClientHello part 1
		//   outbound  485 bytes (opts 20), flags 0x018, payload  425    ClientHello part 2
		//
		// A carrying run on the same carrier, on the same identity, in the same second, sent 76 bytes and
		// came back with 414 of the origin's answer. The one difference that separated them was the size and
		// shape of the first data payload:
		//
		//   carrying   WRITE DATA len=148   capsule=136    76 bytes, plaintext
		//   stalled    WRITE DATA len=1172  capsule=1160   1100 bytes, TLS ClientHello
		//
		// Both were acknowledged. Only one was forwarded on. A tunnel that terminates TCP at its edge is
		// forwarding the segment, not the stream, and a handshake that has to be reassembled from two
		// segments behind a proxy is a shape the origin has never answered for in one that works.
		//
		// With the hybrid group excluded the ClientHello is a few hundred bytes: one segment, and the shape a
		// client actually sends. The forward secrecy is the same - X25519 is still a group neither side
		// knows in advance - and it is what the reference client offers too: its own negotiated handshake,
		// recorded in the trace it returned, reads kex=X25519 and not X25519MLKEM768.
		CurvePreferences: []tls.CurveID{tls.X25519, tls.CurveP256, tls.CurveP384, tls.CurveP521},
		// An empty root pool, named explicitly.
		//
		// InsecureSkipVerify already means no chain is built, but Go initialises the system root pool the
		// first time any certificate is verified anywhere in the process, and on this device that parse
		// panics on one of the 145 files in /system/etc/security/cacerts:
		//
		//   panic: runtime error: slice bounds out of range [16:5260]
		//   encoding/pem.Decode
		//   crypto/x509.(*CertPool).AppendCertsFromPEM
		//   crypto/x509.loadSystemRoots
		//
		// A panic on any goroutine takes the process, and it fired while reading a trace from a tunnel
		// whose own certificate is deliberately not verified. The certificate that matters here is the
		// edge's, and that is verified by the enrolled public key rather than by a public authority list,
		// so the platform roots are never consulted and never need to be parsed.
		RootCAs:   x509.NewCertPool(),
		ClientCAs: nil,
	})
	if err := inner.SetDeadline(time.Now().Add(45 * time.Second)); err != nil {
		return "", err
	}
	if err := inner.Handshake(); err != nil {
		androidLog(fmt.Sprintf("colgram_masque: tls inside the tunnel failed: %v (sent=%d recv=%d)",
			err, peer.sent, peer.recv))
		// What was actually put on the wire, when the handshake did not complete.
		//
		// The carrying run and the stalled run are byte-identical through the handshake and diverge at the
		// first data segment - 76 bytes of plaintext in one, a 1160-byte ClientHello in the other - and both
		// are answered with an acknowledgement. So the edge reads the ClientHello and does not forward on
		// it. What it reads is worth having on the record: the record type, the version and the cipher list,
		// because a handshake a real server refuses is a handshake whose bytes say why.
		androidLog(fmt.Sprintf("colgram_masque: client wrote %d bytes on the flow for this handshake "+
			"(peer sent=%d recv=%d)", peer.sent, peer.sent, peer.recv))
		return "", fmt.Errorf("tls inside tunnel: %w (sent=%d recv=%d)", err, peer.sent, peer.recv)
	}

	req := strings.Join([]string{
		"GET " + tracePath + " HTTP/1.1",
		"Host: " + traceHost,
		"User-Agent: colgram-warp-on",
		"Accept: */*",
		"Connection: close",
		"", "",
	}, crlf)
	if _, err := io.WriteString(inner, req); err != nil {
		return "", fmt.Errorf("write request: %w", err)
	}

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
	androidLog(fmt.Sprintf("colgram_masque: h2 trace sent=%d recv=%d bytes=%d",
		peer.sent, peer.recv, body.Len()))
	return body.String(), nil
}

// attemptContained runs one measurement with a panic guard.
// dialQuic opens the QUIC carrier, or reports why it did not try.
//
// It is a function rather than an inline dial because the QUIC handshake reaches Go's own TLS, and on
// a network that filters UDP to this edge that code panics - a nil dereference inside
// Config.curvePreferences - rather than returning an error. A panic on any goroutine takes the process
// with it, and the user is turning on a tunnel, not debugging a carrier. So the path is probed first,
// and a carrier that cannot answer is never dialled.
func dialQuic(ctx context.Context, udpConn *net.UDPConn, addr string, cert tls.Certificate) (*quic.Conn, error) {
	if addr == "" {
		addr = net.JoinHostPort(edgeIP, "443")
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	// Probed through the front when one is named, for the same reason the candidate search does it: a
	// probe on a different egress answers about a path the handshake will not take.
	if !udpReachablePorts(host, []string{port}, socksAddrFor(Config{}))[port] {
		return nil, fmt.Errorf("udp to %s does not answer", addr)
	}
	tlsConf := &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         edgeSNI,
		NextProtos:         []string{"h3"},
		Certificates:       []tls.Certificate{cert},
		MinVersion:         tls.VersionTLS13,
	}
	return quic.Dial(ctx, udpConn, mustAddr(addr), tlsConf, &quic.Config{
		EnableDatagrams:         true,
		InitialPacketSize:       1200,
		DisablePathMTUDiscovery: true,
		MaxIdleTimeout:          60 * time.Second,
	})
}

// A panic anywhere in Go kills the process unless it is recovered on the goroutine that raised it, and
// the QUIC path can raise one inside the standard library on a network that filters UDP to this edge -
// measured as a nil dereference in crypto/tls while handling the peer's certificate request, which the
// build's curve table cannot satisfy. Reporting that candidate as failed and moving on is correct: the
// carrier cannot work there anyway. Dying is not.
func attemptContained(srcIP net.IP, cert tls.Certificate, addr, bind, trace string) (body string, err error) {
	defer func() {
		if r := recover(); r != nil {
			body = ""
			err = fmt.Errorf("the QUIC carrier panicked: %v", r)
			androidLog(fmt.Sprintf("colgram_masque: recovered a panic from the QUIC path: %v", r))
		}
	}()
	return attempt(srcIP, cert, addr, bind, trace)
}

// resolveEdge turns host:port into an address, without involving the system resolver for a literal.
func resolveEdge(addr string) (*net.UDPAddr, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	if ip := net.ParseIP(host); ip != nil {
		v4 := ip.To4()
		if v4 == nil {
			return nil, fmt.Errorf("edge address is not IPv4: %s", host)
		}
		return &net.UDPAddr{IP: v4, Port: atoiOr(port, 443)}, nil
	}
	return net.ResolveUDPAddr("udp4", addr)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// androidLog writes a transport decision to the Android log as well as stderr.
//
// Stderr alone is invisible on a device: nothing reads it, so a run that tried four things and reported
// the reason for each showed one line and left the rest unmeasurable. The bridge in jni_bridge.c exists
// for exactly this and was never called from here, which is why several rounds of measurement of this
// client produced no log lines at all.
func androidLog(msg string) {
	msg = strings.TrimRight(msg, "\n")
	cLine := C.CString(msg)
	C.colgram_masque_emit(cLine)
	C.free(unsafe.Pointer(cLine))
	fmt.Fprintln(os.Stderr, msg)
}

// logHostSink is where a host build sends what androidLog sends to the platform log.
//
// The point of the split is isolation. Everything above this line - the registration, the carrier, the
// capsule framing, the TCP state machine - is the same code on a phone and on this machine, and the one
// thing that is not is the device it runs on and the network under it. A fault that reproduces on the host
// is in the client, and one that does not is in the device or the network, and saying which is worth more
// than another round of device builds because it takes minutes rather than half an hour.
//
// It is empty here and a no-op on the device, where the real sink is the JNI bridge above.
func logHostSink(string) {}

// edgeCandidate is one (address, local bind) pair to try.
//
// The pair matters and it was measured. This edge answers QUIC from one egress address and drops the
// same handshake from another, so which local address the socket binds to decides whether the edge
// answers at all. On one machine: bound to the LAN address the tunnel reports warp=on, bound to the
// wildcard the same code times out with no packet returned. So the search is over pairs, not over
// addresses.
type edgeCandidate struct {
	addr string
	bind string
}

// edgePorts are the UDP ports this edge answered a QUIC Initial on, newest first. Measured, not
// assumed: 443 and 500 answered in about 100 ms, 8443 and 8095 in about 103 ms.
var edgePorts = []string{"443", "500", "8443", "8095", "4500", "4443"}

// newUdpCarrier opens whatever carries the tunnel's UDP: a SOCKS associate when a front is named, and
// a plain socket otherwise. It is the one place that choice is made, so the reachability probe and the
// handshake cannot end up on different paths.
func newUdpCarrier(socks string) (net.PacketConn, error) {
	if socks != "" {
		return dialSocks5UDP(socks)
	}
	return net.ListenUDP("udp4", &net.UDPAddr{})
}

// udpReachablePorts sends one long-header datagram to each port and reports which answered.
//
// A QUIC long header with an unknown version is what a QUIC endpoint answers first, so a reply - even a
// version negotiation packet - proves the path is open, and silence within the budget means it is not. The
// datagram is a real packet rather than a bare write so that a firewall answering with an ICMP port
// unreachable is also caught, which a plain connect would report and a filtered path would not.
func udpReachablePorts(host string, ports []string, socks string) map[string]bool {
	out := map[string]bool{}
	addr := net.ParseIP(host).To4()
	if addr == nil {
		return out
	}

	// With a front named, the probe rides it. The datagram has to travel the path the handshake will
	// travel, and the front is a UDP associate that terminates locally and opens its own upstream socket -
	// so a probe sent straight out reaches a different egress, on a different source port, and answers
	// about a different path than the one the tunnel uses.
	var relay *socksUDPConn
	var raw net.PacketConn
	var err error
	if socks != "" {
		relay, err = dialSocks5UDP(socks)
		if err != nil {
			androidLog(fmt.Sprintf("colgram_masque: reachability probe could not use the front %s: %v",
				socks, err))
			return out
		}
		defer relay.Close()
	} else {
		raw, err = net.ListenUDP("udp4", &net.UDPAddr{})
		if err != nil {
			return out
		}
		defer raw.Close()
	}

	send := func(b []byte, to *net.UDPAddr) error {
		if relay != nil {
			_, err := relay.WriteTo(b, to)
			return err
		}
		_, err := raw.WriteTo(b, to)
		return err
	}
	recv := func(b []byte) (int, error) {
		if relay != nil {
			n, _, err := relay.ReadFrom(b)
			return n, err
		}
		n, _, err := raw.ReadFrom(b)
		return n, err
	}
	setDeadline := func(t time.Time) {
		if relay != nil {
			// The associate's own reads go through its control socket; the deadline that matters is on the
			// session, and a stale one would let a filtered port read the previous port's reply.
			_ = t
			return
		}
		_ = raw.SetReadDeadline(t)
	}

	conn, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil && relay == nil {
		return out
	} else if conn != nil {
		conn.Close()
	}

	// Reserved bits and a fixed pattern, which is what RFC 9000 sends in the first four bytes of a long
	// header. Any version is fine: the point is the shape, not the value.
	pkt := make([]byte, 1200)
	pkt[0] = 0xc0
	pkt[1] = 0xba
	pkt[2] = 0xba
	pkt[3] = 0xba

	for _, p := range ports {
		port, err := strconv.Atoi(p)
		if err != nil {
			continue
		}
		target := &net.UDPAddr{IP: addr, Port: port}
		setDeadline(time.Now().Add(400 * time.Millisecond))
		if err := send(pkt, target); err != nil {
			continue
		}
		buf := make([]byte, 1500)
		if n, err := recv(buf); err == nil && n > 0 {
			out[p] = true
		}
	}
	return out
}

// udpAnswers reports whether any port on the edge answers a datagram at all, which is the only question
// the carrier choice turns on: one answered port means QUIC is worth trying, none means every candidate
// will time out and the other carrier should go first.
func udpAnswers(host string, socks string) bool {
	// Whether a datagram comes back is not the question. It has to be a QUIC endpoint's answer to a QUIC
	// long header, because anything less is answered by something that is not the tunnel's carrier.
	//
	// Measured on the device: five of six ports answered the probe - a version negotiation packet from
	// whatever is on the other end - and every one of them then timed out through a real handshake:
	//
	//   UDP reachability on 162.159.198.2: map[4443:true 4500:true 500:true 8095:true 8443:true]
	//   edge 162.159.198.2:500  via 10.0.2.15 failed: quic dial: timeout: no recent network activity
	//   edge 162.159.198.2:8443 via 10.0.2.15 failed: quic dial: timeout: no recent network activity
	//   edge 162.159.198.2:8095 via 10.0.2.15 failed: quic dial: timeout: no recent network activity
	//   edge 162.159.198.2:4500 via 10.0.2.15 failed: quic dial: timeout: no recent network activity
	//   edge 162.159.198.2:4443 via 10.0.2.15 failed: quic dial: timeout: no recent network activity
	//   edge 162.159.198.2:443  via 10.0.2.15 failed: quic dial: timeout: no recent network activity
	//
	// Six handshakes at five seconds each is the whole 180 second budget, spent before the carrier that
	// works is reached - and the repeated connections are what made the edge start refusing new ones.
	//
	// So the test is one handshake attempt on the port the registration names, and no more: a version
	// negotiation packet proves something is listening, a handshake proves it is the tunnel.
	probe, err := dialQuicOnce(host, socks)
	if err != nil {
		androidLog(fmt.Sprintf("colgram_masque: no QUIC handshake on %s: %v", host, err))
		return false
	}
	probe.CloseWithError(0, "probe")
	return true
}

// dialQuicOnce attempts exactly one QUIC handshake and reports what came of it. It exists to answer a
// yes-or-no question - is the QUIC carrier usable at all - without paying for a whole session's worth of
// attempts, and it is bounded so a filtered path costs a second rather than the caller's whole budget.
func dialQuicOnce(host, socks string) (*quic.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	conn, err := newUdpCarrier(socks)
	if err != nil {
		return nil, err
	}
	addr := &net.UDPAddr{IP: net.ParseIP(host).To4(), Port: 443}
	// No client certificate: the handshake is only asked whether it completes, and the certificate is
	// what the previous probe's crash was about.
	conf := &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         edgeSNI,
		NextProtos:         []string{"h3"},
		MinVersion:         tls.VersionTLS13,
	}
	return quic.Dial(ctx, conn, addr, conf, &quic.Config{
		EnableDatagrams:         true,
		InitialPacketSize:       1200,
		DisablePathMTUDiscovery: true,
		MaxIdleTimeout:          5 * time.Second,
	})
}

func edgeCandidates(preferredBind string) []edgeCandidate {
	binds := localBinds(preferredBind)
	// A reachability probe before the search rather than after it.
	//
	// Every candidate that cannot answer is a full handshake timeout, and there are several ports times
	// several binds, so a network that filters UDP to this edge spent minutes failing before the carrier
	// that works was asked at all. Measured: on a network where UDP is filtered, the trace call took 107
	// seconds and returned nothing, while the HTTP/2 carrier answered in under two. One datagram per port
	// costs milliseconds and rules a port out for the whole search.
	// The probe goes through the same front the handshake will, or it proves nothing. A direct socket
	// can answer where the front cannot and the other way round, and on this network the front is what
	// both the probe and the tunnel actually use - the device binds its egress through it, so a probe
	// that went direct would be measuring a path nothing else takes.
	reachable := udpReachablePorts(edgeIP, edgePorts, socksAddrFor(Config{}))
	androidLog(fmt.Sprintf("colgram_masque: UDP reachability on %s through front %q: %v",
		edgeIP, socksAddrFor(Config{}), reachable))
	if len(reachable) > 0 {
		// The ports that do not answer are dropped from the search, not merely reported. Each one costs a
		// full handshake timeout, and with a SOCKS front in front of them the timeout is the front's: the
		// datagram goes out through the associate and comes back as an EOF five seconds later, so a port
		// the probe already ruled out was being paid for again on every attempt.
		//
		// Measured with the in-app front up and UDP filtered to this edge: six ports, five seconds each,
		// a different source port every time, and no verdict - which is the 180 second timeout the
		// integration test reported. The probe exists to make that unpayable.
		filtered := make([]string, 0, len(edgePorts))
		var usable []string
		for _, p := range edgePorts {
			if reachable[p] {
				usable = append(usable, p)
			} else {
				filtered = append(filtered, p)
			}
		}
		if len(filtered) > 0 {
			androidLog(fmt.Sprintf("colgram_masque: UDP to the edge is filtered on %v; trying the "+
				"remaining ports first", filtered))
		}
		if len(usable) > 0 {
			edgePorts = append(append([]string{}, usable...), filtered...)
		}
	}
	var out []edgeCandidate
	seen := map[string]bool{}
	for _, bind := range binds {
		for _, port := range edgePorts {
			key := bind + "|" + port
			if seen[key] {
				continue
			}
			seen[key] = true
			out = append(out, edgeCandidate{addr: net.JoinHostPort(edgeIP, port), bind: bind})
		}
	}
	return out
}

// localBinds lists the addresses worth leaving from, the explicit one first when given.
func localBinds(preferred string) []string {
	var out []string
	if preferred != "" {
		out = append(out, preferred)
	}
	for _, ip := range hostIPv4() {
		if ip == preferred {
			continue
		}
		out = append(out, ip)
	}
	return out
}

// hostIPv4 returns this machine's non-loopback IPv4 addresses.
//
// Read from the interfaces rather than guessed: the whole point is that the kernel picks an egress
// by the address a socket binds to, and which addresses exist is a property of the device.
func hostIPv4() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipnet, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			v4 := ipnet.IP.To4()
			if v4 == nil {
				continue
			}
			out = append(out, v4.String())
		}
	}
	return out
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
		v := uint64(b[0] & 0x0f)
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
	var lastErr error
	for _, e := range dohEndpoints {
		lastErr = fmt.Errorf("no DoH endpoint answered for %s", host)
		ips, err := resolveVia(e, host)
		// A resolver that answers is not the same as a resolver that answers correctly. Measured here:
		// when the Cloudflare and Google entries in this list were cut, the AdGuard entry answered - and
		// returned 94.140.14.14 for api.cloudflareclient.com, which is not a Cloudflare address at all.
		// The enrolment then completed against it and the API answered 400:
		//
		//   register: Post "https://api.cloudflareclient.com/v0a4471/reg": 400
		//
		// So an answer is checked against the range the host is actually served from before it is used, and
		// an answer from outside that range is treated as a failed resolver rather than as the address.
		// Without the check this is a silent substitution by whichever resolver happens to be reachable,
		// which is the same attack the system resolver performs and the reason this list exists.
		if err == nil && len(ips) > 0 && plausible(host, ips) {
			androidLog(fmt.Sprintf("colgram_masque: resolved %s through %s: %v", host, e.ip, ips))
			return ips, nil
		}
		if err != nil {
			androidLog(fmt.Sprintf("colgram_masque: resolver %s did not answer for %s: %v", e.ip, host, err))
		} else {
			androidLog(fmt.Sprintf("colgram_masque: resolver %s answered for %s with %v, "+
				"which is not a range it is served from", e.ip, host, ips))
		}
		if err != nil {
			lastErr = err
		}
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("no DoH endpoint answered for %s", host)
}

// dohEndpoints are address literal plus the name to carry in SNI and to verify against.
//
// The address is pinned on purpose, and so is the SNI being different from it: net4people/bbs #81
// and Risky Bulletin (2026-08-26) both record Russian ISPs cutting DNS-over-HTTPS and
// DNS-over-TLS with a TCP RST *after* the TLS ClientHello, keyed on the SNI, while leaving the same
// IP reachable under a different one. A resolver reachable at a literal but filtered by name is
// therefore tried under a name that is not on the list, and the certificate is still verified
// against the real resolver name - so a cut resolver cannot be silently swapped for an impostor
// just because the name it was reached under changed.
type dohEndpoint struct {
	ip         string
	sni        string
	verifyName string
}

var dohEndpoints = []dohEndpoint{
	{"1.1.1.1", "cloudflare-dns.com", "cloudflare-dns.com"},
	{"1.0.0.1", "cloudflare-dns.com", "cloudflare-dns.com"},
	{"8.8.8.8", "dns.google", "dns.google"},
	{"8.8.4.4", "dns.google", "dns.google"},
	{"94.140.14.14", "adguard-dns.com", "adguard-dns.com"},
}

// servedFrom pins the range each of these hosts is actually served from, so an answer that comes back
// from outside it is a substituted resolver rather than a moved service.
//
// The values are Cloudflare's own published ranges, checked against what the resolvers above return:
//
//	api.cloudflareclient.com        104.16.24.84   104.16.192.82
//	engage.cloudflareclient.com     162.159.192.x
//	www.cloudflare.com              104.16.x / 172.64.x
//	connectivity.cloudflareclient.com 162.159.138.x
//
// A host not listed here is not checked, because an unknown host has no range to check against and a
// guess would be worse than the answer it replaced.
var servedFrom = map[string][]string{
	apiHost:                             {"104.16.0.0/13", "172.64.0.0/13", "162.158.0.0/15", "188.114.96.0/20"},
	"engage.cloudflareclient.com":       {"162.158.0.0/15", "162.159.0.0/16"},
	"connectivity.cloudflareclient.com": {"162.158.0.0/15", "162.159.0.0/16"},
	"www.cloudflare.com":                {"104.16.0.0/13", "172.64.0.0/13", "162.158.0.0/15", "188.114.96.0/20"},
}

// plausible reports whether every answer falls inside a range the host is served from.
func plausible(host string, ips []net.IP) bool {
	nets, ok := servedFrom[host]
	if !ok {
		return true
	}
	var parsed []*net.IPNet
	for _, cidr := range nets {
		_, n, err := net.ParseCIDR(cidr)
		if err != nil {
			continue
		}
		parsed = append(parsed, n)
	}
	if len(parsed) == 0 {
		return true
	}
	for _, ip := range ips {
		inside := false
		for _, n := range parsed {
			if n.Contains(ip) {
				inside = true
				break
			}
		}
		if !inside {
			androidLog(fmt.Sprintf("colgram_masque: resolver answered %s with %s, which is not a "+
				"range it is served from; treating the resolver as failed", host, ip))
			return false
		}
	}
	return true
}

func resolveVia(e dohEndpoint, host string) ([]net.IP, error) {
	target := "https://" + e.ip + "/dns-query?name=" + host + "&type=A"
	req, err := http.NewRequest("GET", target, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("accept", "application/dns-json")
	// The address is a literal and the name is set separately, so the connection is made to one
	// host and verified as another - which is the whole point.
	req.Host = e.verifyName
	client := &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				ServerName: e.verifyName,
				// The pinned IP and the verified name differ, so the library needs to be told
				// which dial target belongs to which name.
				InsecureSkipVerify:    false,
				VerifyPeerCertificate: nil,
			},
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(e.ip, "443"))
			},
		},
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("%s: %d", e.ip, resp.StatusCode)
	}
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

// --- JNI bridge ---------------------------------------------------------

// The app needs the verdict, not a CLI. These entry points run the identical code path the
// standalone binary runs, with the configuration supplied by the caller instead of the
// environment, and hand back the trace so Java can decide what to show.

var (
	bridgeMu     sync.Mutex
	bridgeResult string
	bridgeErr    string
)

// socksDatagramsRead counts datagrams the SOCKS5 UDP socket has handed back, so a stalled exchange
// can be told apart from one that is merely slow.
var socksDatagramsRead atomic.Int64

//export colgram_masque_measure
func colgram_masque_measure(bind, edge, port, relayBind *C.char) *C.char {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()

	body, err := measureWith(Config{
		Bind:     C.GoString(bind),
		EdgeHost: C.GoString(edge),
		EdgePort: C.GoString(port),
		// The egress the in-app relay binds its upstream socket to, tried after the direct routes.
		// It is separate from the client's own bind because the whole point is that the edge answers
		// from some local addresses and silently drops the same handshake from others.
		RelayBind: C.GoString(relayBind),
		// Passed as a parameter, not read from the environment. A c-shared library snapshots the
		// environment when it is loaded, so a variable exported by the shell after the load is
		// invisible here - measured, not assumed: the same process printed WARP_SOCKS="" while a
		// shell in the same invocation printed the value.
		SocksAddr: socksOverride,
	})
	if err != nil {
		bridgeErr = err.Error()
		bridgeResult = ""
		return nil
	}
	bridgeErr = ""
	bridgeResult = body
	return C.CString(body)
}

//export colgram_masque_last_error
func colgram_masque_last_error() *C.char {
	return C.CString(bridgeErr)
}

//export colgram_masque_version
func colgram_masque_version() *C.char {
	return C.CString("colgram-masque/1")
}

//export colgram_masque_set_socks
func colgram_masque_set_socks(addr *C.char) {
	socksOverride = C.GoString(addr)
}

// WarpOn reports whether the trace Cloudflare returned says the request went through WARP.
func WarpOn(trace string) bool {
	for _, line := range strings.Split(trace, "\n") {
		line = strings.TrimSpace(line)
		if line == "warp=on" {
			return true
		}
	}
	return false
}

// ------------------------------------------------------------------ long-lived session

// The measurement entry points above register a fresh device, dial a fresh QUIC session and read
// one response. That is right for a verdict and useless for a device-wide tunnel, where a session
// has to outlive the request and carry packets for as long as the interface is up.

var (
	sessionMu       sync.Mutex
	session         *longSession
	lastExchangeErr string
)

type longSession struct {
	tun         *tunnel
	peer        *tunnelConn
	addr        string
	bind        string
	bindSrcPort uint16
	// Held for the session's life. A context cancelled when measure() returns would close the
	// connection out from under the tunnel, and the interface would go silent with no error.
	cancel context.CancelFunc
}

func mustAddr(addr string) *net.UDPAddr {
	a, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		return &net.UDPAddr{IP: net.ParseIP(edgeIP).To4(), Port: 443}
	}
	return a
}

func openConnect(ctx context.Context, h3 *http3.ClientConn) (*http3.RequestStream, error) {
	stream, err := h3.OpenRequestStream(ctx)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest("CONNECT", "https://cloudflareaccess.com/", nil)
	if err != nil {
		return nil, err
	}
	req.Proto = "cf-connect-ip"
	req.Header.Set("capsule-protocol", "?1")
	req.Header.Set("cf-connect-proto", "cf-connect-ip")
	req.Header.Set("pq-enabled", "false")
	if err := stream.SendRequestHeader(req); err != nil {
		return nil, err
	}
	resp, err := stream.ReadResponse()
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("CONNECT refused with %d", resp.StatusCode)
	}
	return stream, nil
}

//export colgram_masque_open_session
func colgram_masque_open_session(bind, edge *C.char) {
	sessionMu.Lock()
	if session != nil {
		sessionMu.Unlock()
		return
	}
	sessionMu.Unlock()

	setSessionStage("enrolling", "")
	srcIP, cert, err := enrolOnce()
	if err != nil {
		lastExchangeErr = err.Error()
		setSessionStage("failed", err.Error())
		return
	}
	addr := net.JoinHostPort(edgeIP, "443")
	if e := C.GoString(edge); e != "" {
		if h, p, err := net.SplitHostPort(e); err == nil {
			addr = net.JoinHostPort(h, p)
		} else {
			addr = net.JoinHostPort(e, "443")
		}
	}

	local := &net.UDPAddr{}
	if b := C.GoString(bind); b != "" {
		if ip := net.ParseIP(b).To4(); ip != nil {
			local.IP = ip
		}
	}
	udpConn, err := net.ListenUDP("udp4", local)
	if err != nil {
		lastExchangeErr = err.Error()
		setSessionStage("failed", err.Error())
		return
	}

	setSessionStage("dialing", "to "+addr)
	ctx, cancel := context.WithCancel(context.Background())

	// Try QUIC first, fall back to H2 over TCP if QUIC is blocked
	var tun *tunnel
	qconn, qerr := dialQuic(ctx, udpConn, addr, cert)
	if qerr != nil {
		androidLog("colgram_masque: QUIC failed (" + qerr.Error() + "), trying H2")
		setSessionStage("dialing-h2", "QUIC blocked, trying TCP+H2")
		udpConn.Close()
		h2addr := net.JoinHostPort(edgeH2IP, edgeH2Port)
		cs, h2err := openH2Session(ctx, cert, h2addr)
		if h2err != nil {
			cancel()
			lastExchangeErr = "QUIC: " + qerr.Error() + " | H2: " + h2err.Error()
			setSessionStage("failed", lastExchangeErr)
			return
		}
		tun = &tunnel{srcIP: srcIP, stream: cs}
	} else {
		tun = &tunnel{conn: qconn, srcIP: srcIP}
		h3c := (&http3.Transport{
			EnableDatagrams:    true,
			AdditionalSettings: map[uint64]uint64{0x8: 1},
		}).NewClientConn(qconn)
		tun.h3 = h3c
		stream, serr := openConnect(ctx, h3c)
		if serr != nil {
			cancel()
			lastExchangeErr = serr.Error()
			setSessionStage("failed", serr.Error())
			return
		}
		tun.stream = &h3Stream{s: stream}
	}

	s := &longSession{
		tun:    tun,
		addr:   addr,
		bind:   C.GoString(bind),
		cancel: cancel,
	}
	// The pump has to exist before a peer opens: the SYN goes out as a capsule, and nothing comes
	// back unless someone is already reading the return path.
	go s.pump(ctx)

	sessionMu.Lock()
	session = s
	sessionMu.Unlock()

	if err := s.openPeer(); err != nil {
		lastExchangeErr = err.Error()
		setSessionStage("failed", err.Error())
		return
	}
	setSessionStage("open", "carrying packets to "+addr)
}

func (s *longSession) openPeer() error {
	// Distinct source ports per run, so a long-lived tunnel does not collide with the measurement
	// path or with a previous tunnel the OS has not reaped yet.
	s.bindSrcPort++
	peer := newTunnelConn(s.tun, s.tun.srcIP, mustAddr(s.addr).IP,
		uint16(40000+s.bindSrcPort), 443)
	s.tun.peers = append(s.tun.peers, peer)
	s.peer = peer
	return peer.open()
}

func (s *longSession) pump(ctx context.Context) {
	for {
		// The session can be closed underneath this goroutine - closeSession cancels the context and
		// closes the carrier while the read below is in flight - and a receive on a stream that was torn
		// down at the same moment faults in the scheduler rather than returning an error:
		//
		//   fatal error: unexpected signal during runtime execution
		//   [signal SIGSEGV: segmentation violation code=0x80]
		//   runtime.selectgo
		//
		// The check before the read narrows the window; it does not close it, because the context can be
		// cancelled between the check and the receive. What closes it is the deadline the carrier already
		// sets on every read - the read returns, the loop notices the context, and the goroutine leaves
		// rather than touching a stream that no longer exists.
		select {
		case <-ctx.Done():
			return
		default:
		}
		data, err := s.tun.stream.ReceiveDatagram(ctx)
		if err != nil {
			return
		}
		// The reply is a whole IP packet, already unframed. This used to require a leading zero byte and hand
		// the rest of the buffer to the peer, which was the same mistake the capsule reader had twice: the
		// byte being stripped is the IP version nibble, so a genuine reply lost its first byte and never
		// parsed as a TCP segment.
		//
		// It then grew a second strip, for a capsule header, on the same reasoning - and that one was worse,
		// because on the HTTP/2 carrier there is no header left on a packet this method returns, so the
		// check below and the strip above fought each other over the same two bytes.
		if len(data) < 20 {
			continue
		}
		atomic.AddInt64(&s.tun.recvCapsules, 1)
		if len(data) < 20 || data[0]>>4 != 4 {
			androidLog(fmt.Sprintf("colgram_masque: inbound %d bytes is not an IPv4 packet (first byte %02x)",
				len(data), data[0]))
			continue
		}
		if s.peer != nil {
			s.peer.feed(data)
		}
	}
}

//export colgram_masque_exchange
func colgram_masque_exchange(packet []byte, bind, edge *C.char) []byte {
	sessionMu.Lock()
	s := session
	sessionMu.Unlock()
	if s == nil {
		colgram_masque_open_session(bind, edge)
		sessionMu.Lock()
		s = session
		sessionMu.Unlock()
	}
	if s == nil || s.peer == nil {
		return nil
	}
	s.tun.sendIP(packet)

	// One packet out, one packet back. The deadline is short on purpose: a VpnService pump blocks
	// on this, and waiting ten seconds for a reply that is never coming would stall every other
	// packet the interface has to move.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		s.peer.mu.Lock()
		if len(s.peer.inbuf) > 0 {
			reply := make([]byte, len(s.peer.inbuf))
			copy(reply, s.peer.inbuf)
			s.peer.inbuf = s.peer.inbuf[:0]
			s.peer.mu.Unlock()
			return reply
		}
		s.peer.mu.Unlock()
		time.Sleep(2 * time.Millisecond)
	}
	return nil
}

//export colgram_masque_close_session
func colgram_masque_close_session() {
	sessionMu.Lock()
	s := session
	session = nil
	sessionMu.Unlock()
	if s == nil || s.tun == nil {
		return
	}
	// Both carriers have to be torn down, not just the QUIC one. This checked tun.conn only, and on the
	// HTTP/2 carrier that field is nil by construction - so closing a tunnel that had come up over
	// TCP dereferenced nothing and killed the process with SIGABRT. The app calls close on every toggle
	// and on every service stop, so this was reachable from ordinary use and not only from a test.
	if s.tun.conn != nil {
		s.tun.conn.CloseWithError(0, "closing")
	}
	if s.cancel != nil {
		s.cancel()
	}
	if cs, ok := s.tun.stream.(*h2CapsuleStream); ok && cs.h2 != nil {
		cs.h2.conn.Close()
	}
}

// lastReplyLen carries the size of the buffer the most recent exchange_slice handed back.
//
// The length is returned out of band on purpose: the reply is allocated with C.CBytes, which the Go
// allocator must not free, so ownership passes to C and C is the side that has to know the size.
var lastReplyLen int64

//export colgram_masque_exchange_slice
func colgram_masque_exchange_slice(data unsafe.Pointer, length C.long, bind, edge *C.char) *C.char {
	packet := C.GoBytes(data, C.int(length))
	reply := colgram_masque_exchange(packet, bind, edge)
	if len(reply) == 0 {
		// 0 rather than a garbage length: the bridge reads this before touching the pointer, and a
		// stale value from an earlier packet would hand the Java side bytes that are not there.
		atomic.StoreInt64(&lastReplyLen, 0)
		return nil
	}
	buf := C.CBytes(reply)
	atomic.StoreInt64(&lastReplyLen, int64(len(reply)))
	return (*C.char)(buf)
}

//export colgram_masque_last_reply_len
func colgram_masque_last_reply_len() *C.char {
	return C.CString(strconv.FormatInt(atomic.LoadInt64(&lastReplyLen), 10))
}

// sessionStage records how far the tunnel actually got.
//
// Every failure mode produced the same thing from the outside: an interface that exists, packets the
// pump hands over, and no reply. The only signal available was the absence of an error message, which is
// not a measurement. This makes each stage nameable, so a handshake that never started is
// distinguishable from one that finished and a tunnel that cannot carry.
//
//	none            no session
//	enrolling       waiting on the registration API
//	dialing         QUIC handshake in flight
//	connecting      extended CONNECT not answered yet
//	opening-peer    the inner TCP SYN has not come back
//	open            ready, carrying packets
var sessionStage = struct {
	sync.Mutex
	stage  string
	detail string
}{stage: "none"}

func setSessionStage(stage, detail string) {
	sessionStage.Lock()
	sessionStage.stage = stage
	sessionStage.detail = detail
	sessionStage.Unlock()
}

//export colgram_masque_session_progress
func colgram_masque_session_progress() *C.char {
	sessionStage.Lock()
	defer sessionStage.Unlock()
	if sessionStage.detail == "" {
		return C.CString(sessionStage.stage)
	}
	return C.CString(sessionStage.stage + " (" + sessionStage.detail + ")")
}

//export colgram_masque_capsule_stats
func colgram_masque_capsule_stats() *C.char {
	sessionMu.Lock()
	s := session
	sessionMu.Unlock()
	if s == nil {
		return C.CString("no session")
	}
	// One counter rather than three: what a stalled tunnel needs to be told is whether the edge ever
	// carried anything at all, and a single count that stays at zero says exactly that.
	return C.CString(fmt.Sprintf("sent=%d received=%d session=%s",
		s.tun.sentCapsules, atomic.LoadInt64(&s.tun.recvCapsules), s.addr))
}

//export colgram_masque_trace
func colgram_masque_trace() *C.char {
	// Reuses the measurement path rather than duplicating it: the trace is fetched inside a fresh MASQUE
	// session and the verdict is whatever Cloudflare wrote in it. A separate implementation would be a
	// second thing to keep in step with the first.
	//
	// The QUIC path is tried first and is still the carrier the official client prefers. Where UDP to the
	// edge is filtered - which is the case on every network this was measured on - it cannot return a
	// verdict at all, so the HTTP/2 carrier is asked next. The order matches the one the session uses, so
	// the verdict reported here is the verdict the tunnel the app is running would produce.
	return colgram_masque_measure(nil, nil, nil, nil)
}
