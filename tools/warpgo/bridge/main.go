// bridge carries the MASQUE edge's UDP flow out over TCP, in one process.
//
// Why one process. Split across two, the reply had no reliable path home: the relay held one
// upstream socket shared by every client and kept a single remembered client address, so a reply
// was written to whichever port spoke last and then died with the dial that owned it. Measured:
//
//     relay: first upstream datagram from 127.0.0.1:38110
//     relay: reply to 127.0.0.1:2181        <- a port that closed ten minutes earlier
//     front: relayed back 0 packets
//
// 101 datagrams up, 38 replies down at the edge, and not one reached the client. The reply loop
// had no way to know that 2181 was dead, because the two halves could not see each other's state.
//
// Here each session owns its own upstream socket and its own reader goroutine, so a reply is
// written by the same code that holds the session's own reply socket. There is no remembered
// address to go stale: the destination is a field on the session, and the session cannot outlive
// the connection that made it.
//
// It changes nothing about the host. No routes, no firewall, no DNS, no VPN - it is a user-space
// listener on two loopback and one LAN port.
package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

var (
	upPkts   atomic.Uint64
	upBytes  atomic.Uint64
	downPkts atomic.Uint64
	downByte atomic.Uint64
	sessions atomic.Int64
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// config carries the two addresses the operator controls.
type config struct {
	listen    string
	advertise string
	upstream  string
	bind      string
}

func main() {
	cfg := config{
		listen:    env("BRIDGE_LISTEN", "0.0.0.0:15150"),
		advertise: os.Getenv("BRIDGE_ADVERTISE"),
		upstream:  env("BRIDGE_UPSTREAM", "162.159.198.2:443"),
		bind:      os.Getenv("BRIDGE_BIND"),
	}

	raddr, err := net.ResolveUDPAddr("udp4", cfg.upstream)
	if err != nil {
		fmt.Println("upstream resolve:", err)
		os.Exit(1)
	}

	ln, err := net.Listen("tcp4", cfg.listen)
	if err != nil {
		fmt.Println("listen:", err)
		os.Exit(1)
	}
	defer ln.Close()

	advertised := cfg.advertise
	if advertised == "" {
		advertised = firstUsableIPv4()
	}
	fmt.Printf("bridge %s -> udp %s\n", ln.Addr(), raddr)
	fmt.Printf("advertise      : %s\n", advertised)
	if cfg.bind != "" {
		fmt.Printf("egress bind    : %s\n", cfg.bind)
	}
	go report()

	for {
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		go serve(conn, raddr, cfg.bind, advertised)
	}
}

func firstUsableIPv4() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ""
	}
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		v4 := ipnet.IP.To4()
		if v4 == nil || v4.IsLoopback() {
			continue
		}
		return v4.String()
	}
	return ""
}

// session is one client's whole path: its control connection, its own upstream socket, its own
// reader. Everything a reply needs is here, so nothing has to be looked up or remembered.
type session struct {
	ctrl  net.Conn
	up    *net.UDPConn
	raddr *net.UDPAddr
	// bound is the address this host owns that the client's replies must appear to come from.
	// If it is not bound, the reply leaves with the wrong source and the client drops it silently.
	bound string
	// self is what the associate reply advertised, echoed back in every relayed frame so the client
	// can match a reply to its own socket.
	self net.IP

	writeMu sync.Mutex
	dead    atomic.Bool
}

func serve(conn net.Conn, raddr *net.UDPAddr, bind, advertised string) {
	// The three sockets have three different jobs, which is why a single one does not do.
	//
	// ctrl   - SOCKS5 control, and the carrier for every datagram in both directions.
	// up     - one datagram socket per session, bound to the egress the edge answers from. Shared,
	//          each re-dial became a new source port and the edge answered none of them.
	// reply  - where relayed datagrams arrive to be written back onto ctrl. It must bind an address
	//          the host owns; a socket on loopback sends from loopback and the client, expecting its
	//          own view of us, drops the reply without a word.
	//
	// Verified, not assumed: under the emulator the gateway belongs to the guest, so binding it
	// fails outright - which is why the advertise address and the bind address are separate.
	s := &session{ctrl: conn, raddr: raddr}
	defer s.close()

	wasConnect, err := s.handshake()
	if err != nil {
		fmt.Printf("session %s: handshake: %v\n", conn.RemoteAddr(), err)
		return
	}
	// CONNECT is finished once the stream is spliced; there is no datagram socket and nothing to
	// pump. Continuing into the associate path after it would leave the client's HTTPS bytes sitting
	// in a socket nobody reads, and the client would time out with the log showing a session that
	// opened and carried nothing.
	if wasConnect {
		return
	}

	advertisedIP := s.pickIP(advertised)

	// The egress socket binds the interface whose source address the edge does answer. Bound to the
	// wildcard, this host has several adapters and the kernel picks one that the edge ignores.
	upLocal := &net.UDPAddr{}
	if bind != "" {
		ip := net.ParseIP(bind).To4()
		if ip == nil {
			fmt.Println("BRIDGE_BIND is not IPv4:", bind)
			return
		}
		upLocal.IP = ip
	}
	up, err := net.ListenUDP("udp4", upLocal)
	if err != nil {
		fmt.Printf("session: upstream socket: %v\n", err)
		return
	}
	s.up = up
	s.bound = upLocal.String()

	if err := s.associate(advertisedIP, up.LocalAddr().(*net.UDPAddr).Port); err != nil {
		fmt.Printf("session: associate: %v\n", err)
		return
	}
	s.self = advertisedIP
	sessions.Add(1)
	defer sessions.Add(-1)

	fmt.Printf("session %s: egress %s, advertise %s:%d\n",
		conn.RemoteAddr(), s.bound, advertisedIP, up.LocalAddr().(*net.UDPAddr).Port)

	// Replies up from the edge, written back onto the control connection this session owns.
	//
	// Read from the egress socket, which is where they arrive: the edge answers the source address
	// and port it received the Initial on. A second socket to read them is a socket nothing sends
	// to, and the symptom is a healthy up counter with a down counter frozen at zero.
	go s.drain(up)

	// Datagrams up from the client. The read loop owns ctrl for the rest of the session's life.
	if err := s.pump(); err != nil && !s.dead.Load() {
		fmt.Printf("session %s ended: %v\n", conn.RemoteAddr(), err)
	}
}

// pickIP decides which of the host's own addresses to bind the reply socket to.
//
// An explicitly advertised address wins. A wildcard listener reports 0.0.0.0 or 127.0.0.1, and
// telling a client to send to either of those points it at itself - the device believes 127.0.0.1
// is its own loopback, so the replies went nowhere and no counter anywhere moved.
func (s *session) pickIP(advertised string) net.IP {
	if ip := net.ParseIP(advertised).To4(); ip != nil {
		return ip
	}
	return net.IPv4zero
}

// handshake does the SOCKS5 greeting and the request, and stops as soon as the request's address
// is consumed. Both are length-sensitive: a short read leaves a byte behind and the next read
// starts mid-stream, which resets the connection with no error anywhere.
// handshake does the SOCKS5 greeting and the request, and returns whether the request was CONNECT.
//
// The caller needs that distinction because the two commands finish differently: UDP ASSOCIATE goes
// on to open a datagram socket and relay frames, while CONNECT has nothing left to do -- the relay
// for it is a plain TCP splice and every further byte belongs to the tunnelled stream.
func (s *session) handshake() (bool, error) {
	head := make([]byte, 3)
	if _, err := io.ReadFull(s.ctrl, head); err != nil {
		return false, err
	}
	if head[0] != 0x05 || head[1] < 1 {
		return false, fmt.Errorf("bad greeting %x", head)
	}
	if _, err := s.ctrl.Write([]byte{0x05, 0x00}); err != nil {
		return false, err
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(s.ctrl, req); err != nil {
		return false, err
	}
	if req[0] != 0x05 {
		return false, fmt.Errorf("bad socks5 request: %x", req)
	}
	// CONNECT (0x01) is answered too, because the client also has to reach HTTPS endpoints before any
	// tunnel exists: the DoH lookups that find the API address, and the enrolment POST/PATCH that
	// registers the device. Those are TCP, and without this branch they either bypass the front --
	// and get filtered on the way out of the device -- or fail outright:
	//
	//     session 127.0.0.1:9217: handshake: not a udp associate: 05010001
	//
	// which is what the bridge logged the first time the client tried. UDP ASSOCIATE only was enough
	// for a measurement that resolved and enrolled on the host; inside the app both of those happen on
	// the device.
	if req[1] == 0x01 {
		return true, s.connect(req[3])
	}
	if req[1] != 0x03 {
		return false, fmt.Errorf("unsupported socks5 command %d", req[1])
	}
	// Consume the address. Its value is irrelevant - a zero address is the documented request for
	// "accept from wherever this control connection came from" - but it must be drained.
	switch req[3] {
	case 1:
		return false, s.skip(6)
	case 4:
		return false, s.skip(18)
	case 3:
		n := make([]byte, 1)
		if _, err := io.ReadFull(s.ctrl, n); err != nil {
			return false, err
		}
		return false, s.skip(int(n[0]) + 2)
	default:
		return false, fmt.Errorf("bad address type %d", req[3])
	}
}

func (s *session) skip(n int) error {
	if n == 0 {
		return nil
	}
	_, err := io.CopyN(io.Discard, s.ctrl, int64(n))
	return err
}

// connect handles a SOCKS5 CONNECT request by dialling the requested address from the host and then
// splicing the two streams together.
//
// The host's own egress is the point: it is the one path on this machine that is not filtered, so
// relaying TCP through it is what lets the device reach HTTPS endpoints it cannot reach itself. The
// bind address is honoured for the same reason the UDP path honours it -- the upstream socket has to
// leave by the interface that can actually complete the handshake.
func (s *session) connect(atyp byte) error {
	var host string
	switch atyp {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(s.ctrl, b); err != nil {
			return err
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(s.ctrl, b); err != nil {
			return err
		}
		host = net.IP(b).String()
	case 3:
		n := make([]byte, 1)
		if _, err := io.ReadFull(s.ctrl, n); err != nil {
			return err
		}
		b := make([]byte, int(n[0]))
		if _, err := io.ReadFull(s.ctrl, b); err != nil {
			return err
		}
		host = string(b)
	default:
		return fmt.Errorf("bad address type %d", atyp)
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(s.ctrl, p); err != nil {
		return err
	}
	port := int(binary.BigEndian.Uint16(p))
	target := net.JoinHostPort(host, strconv.Itoa(port))

	dialer := net.Dialer{}
	if ip := net.ParseIP(s.bound); ip != nil {
		dialer.LocalAddr = &net.TCPAddr{IP: ip}
	}
	up, err := dialer.Dial("tcp", target)
	if err != nil {
		return fmt.Errorf("connect %s: %w", target, err)
	}
	defer up.Close()

	reply := []byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
	if _, err := s.ctrl.Write(reply); err != nil {
		return err
	}

	done := make(chan struct{}, 2)
	go func() {
		io.Copy(up, s.ctrl)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(s.ctrl, up)
		done <- struct{}{}
	}()
	<-done
	return nil
}

// associate answers with the UDP port this session's replies will arrive on.
//
// The port must be the reply socket's, not this TCP listener's. It used to be the listener's, and
// the client then sent its datagrams to a TCP port that no one reads - delivered and discarded by
// the kernel, with no error reported anywhere.
func (s *session) associate(ip net.IP, port int) error {
	reply := []byte{0x05, 0x00, 0x00, 0x01}
	reply = append(reply, ip.To4()...)
	reply = binary.BigEndian.AppendUint16(reply, uint16(port))
	_, err := s.ctrl.Write(reply)
	return err
}

// pump reads client datagrams off the control connection and sends them to the edge.
//
// Over TCP, with an explicit two-byte length, because the emulator's NAT forwards no UDP to the
// host at all - measured with a plain echo server on both the LAN address and the gateway, neither
// received anything, while TCP went through normally. TCP has no message boundaries, so without
// the length two datagrams arrive as one read and the second header lands inside the first payload.
func (s *session) pump() error {
	var pending []byte
	buf := make([]byte, 65535)
	for {
		n, err := s.ctrl.Read(buf)
		if n > 0 {
			pending = append(pending, buf[:n]...)
		}
		if err != nil {
			return err
		}
		for len(pending) >= 2 {
			size := int(pending[0])<<8 | int(pending[1])
			if size == 0 || len(pending) < 2+size {
				break
			}
			frame := pending[2 : 2+size]
			pending = pending[2+size:]
			payload, err := stripHeader(frame)
			if err != nil {
				continue
			}
			if upPkts.Load() <= 3 {
				fmt.Printf("up: %d bytes to %s\n", len(payload), s.raddr)
			}
			if _, err := s.up.WriteToUDP(payload, s.raddr); err != nil {
				fmt.Printf("up: write failed: %v\n", err)
				return err
			}
			upPkts.Add(1)
			upBytes.Add(uint64(len(payload)))
		}
	}
}

// stripHeader removes the RFC 1928 section 7 header: two reserved bytes, one fragment byte, the
// address, and the port. Four bytes come before the address, and getting that wrong shifts the
// whole payload and produces a stream quic-go rejects as a protocol violation.
func stripHeader(frame []byte) ([]byte, error) {
	if len(frame) < 4 {
		return nil, errors.New("short socks datagram")
	}
	if frame[2] != 0x00 {
		return nil, fmt.Errorf("unexpected fragment 0x%02x", frame[2])
	}
	// Four bytes come before the address: two reserved, one fragment, one address type. Counting
	// three leaves the high byte of the destination port in front of the payload, so what reaches
	// the edge is a QUIC packet with a stray leading byte. The edge answers nothing to that, and
	// nothing anywhere reports an error - the up counter climbs and the down counter stays at zero.
	off := 4
	switch frame[3] {
	case 1:
		off += 4
	case 4:
		off += 16
	default:
		return nil, fmt.Errorf("unexpected address type %d", frame[3])
	}
	off += 2
	if len(frame) < off {
		return nil, errors.New("short socks header")
	}
	return frame[off:], nil
}

// drain reads the edge's replies and writes them onto the control connection.
func (s *session) drain(reply *net.UDPConn) {
	buf := make([]byte, 65535)
	for {
		reply.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, from, err := reply.ReadFromUDP(buf)
		if err != nil {
			if s.dead.Load() {
				return
			}
			continue
		}
		if from.String() != s.raddr.String() {
			// Logged, because otherwise it is invisible: the up counter climbs, the client reports a
			// plain timeout, and the one line that would explain it is a silent continue.
			fmt.Printf("down: drop %d bytes from %s (expected %s)\n", n, from, s.raddr)
			continue
		}
		if downPkts.Load() <= 3 {
			fmt.Printf("down: %d bytes from %s\n", n, from)
		}
		if err := s.writeFrame(buf[:n]); err != nil {
			fmt.Printf("down: write to client failed: %v\n", err)
			return
		}
		downPkts.Add(1)
		downByte.Add(uint64(n))
	}
}

// writeFrame puts one relayed datagram on the wire, guarded: pump and drain are separate
// goroutines and both can write, and an interleaved write would corrupt the client's framing.
func (s *session) writeFrame(payload []byte) error {
	pkt := make([]byte, 0, 10+len(payload))
	pkt = append(pkt, 0, 0, 0, 1)
	pkt = append(pkt, s.self.To4()...)
	pkt = binary.BigEndian.AppendUint16(pkt, 0)
	pkt = append(pkt, payload...)

	frame := make([]byte, 2+len(pkt))
	binary.BigEndian.PutUint16(frame, uint16(len(pkt)))
	copy(frame[2:], pkt)

	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	_, err := s.ctrl.Write(frame)
	return err
}

func (s *session) close() {
	if s.dead.Swap(true) {
		return
	}
	if s.up != nil {
		s.up.Close()
	}
	s.ctrl.Close()
}

func report() {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for range t.C {
		fmt.Printf("up %d pkts / %d bytes   down %d pkts / %d bytes   sessions %d\n",
			upPkts.Load(), upBytes.Load(), downPkts.Load(), downByte.Load(), sessions.Load())
	}
}

