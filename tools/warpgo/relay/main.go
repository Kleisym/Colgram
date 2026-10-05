// relay forwards UDP between the device and the MASQUE edge from the egress that the edge answers.
//
// Why this exists: the edge answers QUIC from one egress address and silently drops it from another.
// The device's own traffic leaves through the dropped one, so its Initials never get a Retry. This
// process does not change routes, firewall or DNS - it is a user-space relay on one local port, and
// it binds its upstream socket to the interface whose egress the edge does answer.
//
// It relays by address, not by connection, because QUIC migrates connection IDs within the flow and
// the far side may retry from a different address.
package main

import (
	"fmt"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

var (
	upstreamBytes atomic.Uint64
	downstreamBytes atomic.Uint64
	upstreamPkts atomic.Uint64
	downstreamPkts atomic.Uint64
)

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func main() {
	listen := "127.0.0.1:14500"
	if v := os.Getenv("RELAY_LISTEN"); v != "" {
		listen = v
	}
	upstream := "162.159.198.2:443"
	if v := os.Getenv("RELAY_UPSTREAM"); v != "" {
		upstream = v
	}
	bindIP := os.Getenv("RELAY_BIND")

	front, err := net.ListenPacket("udp4", listen)
	if err != nil {
		fmt.Println("listen:", err)
		os.Exit(1)
	}
	defer front.Close()
	fmt.Println("relay listening:", front.LocalAddr())
	fmt.Println("upstream       :", upstream)
	if bindIP != "" {
		fmt.Println("egress bind    :", bindIP)
	}

	stop := make(chan struct{})
	go report(stop)

	raddr, err := net.ResolveUDPAddr("udp4", upstream)
	if err != nil {
		fmt.Println("resolve upstream:", err)
		os.Exit(1)
	}

	// One upstream socket per device, held for the life of that device's flow. A fresh socket per
	// packet would change the source port every time, and the edge treats each source port as an
	// unrelated flow and answers none of them.
	sessions := map[string]*session{}
	var mu sync.Mutex

	buf := make([]byte, 2000)
	for {
		n, client, err := front.ReadFrom(buf)
		if err != nil {
			select {
			case <-stop:
				return
			default:
			}
			continue
		}
		// One upstream session, shared, because the QUIC connection ID migrates across re-dials
		// and a SOCKS5 front opens a new UDP socket for each of them.
		//
		// Keying by client address - which is what this did - made every re-dial a different
		// upstream socket with a different source port. The edge treats each source port as an
		// unrelated flow and answers none of them: measured, the packet count up climbed while down
		// stayed at zero, and a QUIC client that reconnects a few times has no flow that ever gets
		// an answer.
		key := "shared"
		mu.Lock()
		s := sessions[key]
		if s == nil {
			s = newSession(front, client, raddr, bindIP)
			if s == nil {
				mu.Unlock()
				continue
			}
			sessions[key] = s
			fmt.Println("relay: upstream session opened")
			go s.readUpstream()
			go s.expire(func() {
				mu.Lock()
				delete(sessions, key)
				mu.Unlock()
			})
		}
		// The reply goes back to whoever is talking to us now, even though the upstream socket is
		// the one from the first dial.
		s.setClient(client)
		s.last.Store(time.Now().Unix())
		mu.Unlock()
		if upstreamPkts.Load() <= 1 {
			fmt.Printf("relay: first upstream datagram from %s, %d bytes, head %x\n",
				client, n, buf[:minInt(n, 24)])
		}
		if err := s.send(buf[:n]); err != nil {
			continue
		}
		upstreamPkts.Add(1)
		upstreamBytes.Add(uint64(n))
	}
}

// session is one device's flow: its own upstream socket, its own reader, and an idle timer.
type session struct {
	front  net.PacketConn
	up     net.PacketConn
	raddr  *net.UDPAddr
	last   atomic.Int64
	// The client that spoke last.
	//
	// Guarded, because it is written by the accept loop on every datagram and read by the upstream
	// reader goroutine. Unguarded it was a data race: the reader could observe a torn interface value
	// and dereference garbage, or more often just keep sending replies to the client address from the
	// dial that created the session - which is the closed port of the first SOCKS5 front session. That
	// is why the relay showed a healthy inbound count while nothing reached the device.
	mu     sync.Mutex
	client net.Addr
}

// setClient records the most recent client address.
func (s *session) setClient(a net.Addr) {
	s.mu.Lock()
	s.client = a
	s.mu.Unlock()
}

// clientAddr reads the current client address under the lock.
func (s *session) clientAddr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.client
}

func newSession(front net.PacketConn, client net.Addr, raddr *net.UDPAddr, bindIP string) *session {
	local := &net.UDPAddr{}
	if bindIP != "" {
		local.IP = net.ParseIP(bindIP).To4()
	}
	up, err := net.ListenPacket("udp4", local.String())
	if err != nil {
		// Logged and returned nil, because the old version returned a half-built session with a nil
		// socket, and that got stored in the map. Every later datagram then hit send(), saw a nil
		// socket and returned an error - so the front forwarded packets, the edge never saw them,
		// and the counters sat still with nothing in any log to say why.
		fmt.Println("upstream socket:", err)
		return nil
	}
	s := &session{front: front, client: client, up: up, raddr: raddr}
	s.last.Store(time.Now().Unix())
	return s
}

func (s *session) send(b []byte) error {
	if s.up == nil {
		fmt.Println("relay: send with no upstream socket")
		return net.ErrClosed
	}
	_, err := s.up.WriteTo(b, s.raddr)
	if err != nil {
		fmt.Printf("relay: upstream write failed: %v\n", err)
	}
	s.last.Store(time.Now().Unix())
	return err
}

func (s *session) readUpstream() {
	buf := make([]byte, 2000)
	for {
		s.up.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, from, err := s.up.ReadFrom(buf)
		if err != nil {
			continue
		}
		if from.String() != s.raddr.String() {
			// Logged because the failure is otherwise invisible: the counter stays at zero,
			// the client reports a plain timeout, and the one line that would say why is a
			// silent continue.
			fmt.Printf("relay: drop %d bytes from %s (expected %s)\n",
				n, from, s.raddr)
			continue
		}
		downstreamPkts.Add(1)
		// Read under the lock: the address moves on every dial, and a stale one is a closed port.
		target := s.clientAddr()
		if target == nil {
			continue
		}
		if downstreamPkts.Load() <= 3 {
			fmt.Printf("relay: reply to %s (%d bytes)\n", target, n)
		}
		if _, err := s.front.WriteTo(buf[:n], target); err != nil {
			fmt.Printf("relay: reply to %s failed: %v\n", target, err)
		}
		s.last.Store(time.Now().Unix())
		downstreamBytes.Add(uint64(n))
	}
}

func (s *session) expire(remove func()) {
	for {
		time.Sleep(5 * time.Second)
		if time.Since(time.Unix(s.last.Load(), 0)) > 60*time.Second {
			if s.up != nil {
				s.up.Close()
			}
			remove()
			return
		}
	}
}

// legacyForward is the earlier per-packet implementation, kept only to document why it failed.
func legacyForward(front net.PacketConn, client net.Addr, first []byte, upstream, bindIP string) {
	local := &net.UDPAddr{}
network := "udp4"
	if bindIP != "" {
		ip := net.ParseIP(bindIP).To4()
		if ip == nil {
			fmt.Println("RELAY_BIND is not an IPv4 address:", bindIP)
			return
		}
		local.IP = ip
	}
	back, err := net.ListenPacket(network, local.String())
	if err != nil {
		fmt.Println("upstream socket:", err)
		return
	}
	defer back.Close()

	raddr, err := net.ResolveUDPAddr("udp4", upstream)
	if err != nil {
		fmt.Println("resolve upstream:", err)
		return
	}

	if _, err := back.WriteTo(first, raddr); err != nil {
		fmt.Println("write upstream:", err)
		return
	}
	upstreamPkts.Add(1)
	upstreamBytes.Add(uint64(len(first)))

	done := make(chan struct{})
	var once sync.Once
	stop := func() { once.Do(func() { close(done) }) }

	// Far side to device.
	go func() {
		buf := make([]byte, 2000)
		for {
			select {
			case <-done:
				return
			default:
			}
			// QUIC keeps an idle flow warm for seconds at a time; the deadline must be long enough
			// that a live session is never torn down between packets.
			back.SetReadDeadline(time.Now().Add(20 * time.Second))
			n, from, err := back.ReadFrom(buf)
			if err != nil {
				return
			}
			if from.String() != raddr.String() {
				continue
			}
			if _, err := front.WriteTo(buf[:n], client); err != nil {
				return
			}
			downstreamPkts.Add(1)
			downstreamBytes.Add(uint64(n))
		}
	}()

	// Device to far side. Read and forward until the flow goes quiet.
	for {
		buf := make([]byte, 2000)
		front.SetReadDeadline(time.Now().Add(20 * time.Second))
		n, from, err := front.ReadFrom(buf)
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				// No traffic for twenty seconds: the flow is done.
				return
			}
			stop()
			return
		}
		if from.String() != client.String() {
			continue
		}
		if _, err := back.WriteTo(buf[:n], raddr); err != nil {
			stop()
			return
		}
		upstreamPkts.Add(1)
		upstreamBytes.Add(uint64(n))
	}
}

func report(stop chan struct{}) {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			fmt.Printf("up %d pkts / %d bytes   down %d pkts / %d bytes\n",
				upstreamPkts.Load(), upstreamBytes.Load(),
				downstreamPkts.Load(), downstreamBytes.Load())
		}
	}
}
