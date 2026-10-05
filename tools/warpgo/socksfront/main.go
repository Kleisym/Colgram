// socksfront puts a SOCKS5 UDP ASSOCIATE in front of a plain UDP forwarder.
//
// The device filters UDP 443 and allows TCP 443, so the tunnel's datagrams have to leave over TCP.
// A SOCKS5 relay does that: the client's UDP is encapsulated in a TCP stream to a relay, and the
// relay sends it on. Two halves with different jobs, so two processes - this one speaks SOCKS5 to
// the client, the plain forwarder speaks raw datagrams to the edge.
package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync/atomic"
	"time"
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// frontAdvertisedAddr is the address a client should send its datagrams to.
//
// It is the local end of the connection with the wildcard substituted for the interface the packet
// actually arrived on. A wildcard listener reports 0.0.0.0 or 127.0.0.1, and telling a remote client
// either of those points it at itself - the emulator's device believes 127.0.0.1 is its own
// loopback, so the replies went nowhere and the relay's inbound counter stayed frozen with no error.
func frontAdvertisedAddr(conn net.Conn, udpConn *net.UDPConn) *net.TCPAddr {
	local, lok := conn.LocalAddr().(*net.TCPAddr)
	remote, _ := conn.RemoteAddr().(*net.TCPAddr)
	if !lok {
	return &net.TCPAddr{IP: net.IPv4zero, Port: 0}
	}
	// An explicitly configured address always wins. The listener answers 127.0.0.1 for a wildcard
	// bind on some stacks, so testing for the wildcard alone is not enough - it has to be either
	// unspecified or loopback before the guess is consulted.
	if v, ok := advertised.Load().(*net.IP); ok && v != nil {
		return &net.TCPAddr{IP: *v, Port: udpConn.LocalAddr().(*net.UDPAddr).Port}
	}
	ip := local.IP
	if ip == nil || ip.IsUnspecified() || ip.IsLoopback() {
		ip = guessFrontAddress(remote)
	}
	// The session's UDP port, not the control connection's TCP port - see the note at the call site.
	return &net.TCPAddr{IP: ip, Port: udpConn.LocalAddr().(*net.UDPAddr).Port}
}

// sessionBindIP is the local address a session's UDP socket is bound to.
//
// It must match what the associate reply advertised, or the replies carry a source address the
// client is not expecting and drops them without a word.
func sessionBindIP(conn net.Conn) net.IP {
	local, _ := conn.LocalAddr().(*net.TCPAddr)
	if local != nil && local.IP != nil && !local.IP.IsUnspecified() && !local.IP.IsLoopback() {
		return local.IP
	}
	// A wildcard listener reports loopback here, which the client cannot use as a destination. Take
	// the first non-loopback IPv4 this host owns instead.
	if ip := firstUsableHostIPv4(); ip != nil {
		return ip
	}
	return net.IPv4zero
}

func firstUsableHostIPv4() net.IP {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
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
		return v4
	}
	return nil
}

// guessFrontAddress picks the host address most likely to be the one the client dialled.
func guessFrontAddress(remote *net.TCPAddr) net.IP {
	if v, ok := advertised.Load().(*net.IP); ok && v != nil {
		return *v
	}
	if remote != nil {
		return remote.IP
	}
	return net.IPv4zero
}

// advertised is the address the operator told us the clients use. It wins over guessing, because
// only the caller knows how the client reaches this process - through a gateway, a LAN address, or
// an SSH tunnel.
var advertised atomic.Value

// SetAdvertisedAddress tells the front which address to put in its associate replies.
func SetAdvertisedAddress(addr string) error {
	ip := net.ParseIP(addr).To4()
	if ip == nil {
		return fmt.Errorf("not an IPv4 address: %s", addr)
	}
	advertised.Store(&ip)
	return nil
}

func main() {
	listen := env("FRONT_LISTEN", "0.0.0.0:15080")
	upstream := env("FRONT_UPSTREAM", "127.0.0.1:14502")
	if a := os.Getenv("FRONT_ADVERTISE"); a != "" {
		if err := SetAdvertisedAddress(a); err != nil {
			fmt.Println("FRONT_ADVERTISE:", err)
			os.Exit(1)
		}
		fmt.Println("advertising     :", a)
	}
	host, portStr, err := net.SplitHostPort(upstream)
	if err != nil {
		fmt.Println("FRONT_UPSTREAM:", err)
		os.Exit(1)
	}
	port, _ := strconv.Atoi(portStr)
	relayAddr, err := net.ResolveUDPAddr("udp4", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		fmt.Println("upstream resolve:", err)
		os.Exit(1)
	}

	ln, err := net.Listen("tcp4", listen)
	if err != nil {
		fmt.Println("listen:", err)
		os.Exit(1)
	}
	defer ln.Close()
	fmt.Printf("socks5 front %s -> udp %s\n", listen, relayAddr)

	for {
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		// One upstream socket per client, not one shared.
		//
		// A shared socket means every reply is read once and forwarded to whichever connection
		// happens to be reading at that moment - so a reply belonging to one client gets dropped or
		// handed to another. It also makes the relay's own view wrong, because every client's
		// datagrams arrive from the same source port.
		// Bound to the address advertised in the associate reply.
		//
		// It has to be one this host actually owns. A socket on 127.0.0.1 sends from 127.0.0.1, so a
		// reply to a client expecting a LAN address leaves with the wrong source and is dropped
		// without a word. And the advertised address cannot be assumed to be bindable: under an
		// emulator the gateway 10.0.2.2 belongs to the guest, not to this host, and binding it
		// fails outright - which is what the log showed, and why the connection reset.
		//
		// So: bind what we own, advertise what the client must send to. They differ behind a NAT,
		// and only the kernel can tell us which addresses are ours.
		bind := sessionBindIP(conn)
		// Two sockets, because one address cannot be both the client's view of us and our way to
		// reach a loopback relay.
		//
		// A socket bound to the LAN address answers the device correctly, but Windows refuses to send
		// FROM that address TO 127.0.0.1: "The requested address is not valid in its context". So the
		// datagrams for the relay need their own loopback socket, and the replies the device sees
		// need the LAN one.
		front, err := net.ListenUDP("udp4", &net.UDPAddr{IP: bind})
		if err != nil {
			fmt.Printf("front: bind %v failed: %v, falling back to wildcard\n", bind, err)
			front, err = net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})
		}
		if err != nil {
			conn.Close()
			continue
		}
		// The relay socket binds loopback, so a packet arriving on it carries this host's address as
		// its source - it says nothing about the client. The client's own address is captured from
		// the other direction instead, where the kernel does report the real sender.
		fromClient, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})
		if err != nil {
			front.Close()
			conn.Close()
			continue
		}
		toRelay, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			fromClient.Close()
			front.Close()
			conn.Close()
			continue
		}
		fmt.Printf("front: session from %s, udp bound to %s, advertised %s\n",
			conn.RemoteAddr(), front.LocalAddr(), sessionBindIP(conn))
		go serve(conn, relayAddr, front, toRelay, fromClient)
	}
}

func serve(conn net.Conn, relayAddr *net.UDPAddr, udpConn, toRelay, fromClient *net.UDPConn) {
	defer conn.Close()
	defer udpConn.Close()
	defer toRelay.Close()
	defer fromClient.Close()

	// The client's greeting comes first and is answered with the chosen method. Offering a method
	// before the client has asked is what makes a SOCKS5 front answer nothing and look broken.
	// version, method count, then the methods - three bytes for a one-method greeting. Reading two
	// leaves a byte in the buffer and the next read starts mid-stream, which resets the connection
	// with no error anywhere.
	head := make([]byte, 3)
	if _, err := io.ReadFull(conn, head); err != nil {
		return
	}
	if head[0] != 0x05 || head[1] < 1 {
		return
	}
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil {
		return
	}
	if req[0] != 0x05 {
		fmt.Printf("bad request version 0x%02x\n", req[0])
		return
	}
port := make([]byte, 2)
	if _, err := io.ReadFull(conn, port); err != nil {
		return
	}
	if req[3] == 3 {
		nameLen := make([]byte, 1)
		if _, err := io.ReadFull(conn, nameLen); err != nil {
			return
		}
		if _, err := io.ReadFull(conn, make([]byte, int(nameLen[0]))); err != nil {
			return
		}
	} else if req[3] == 1 {
		if _, err := io.ReadFull(conn, make([]byte, 4)); err != nil {
			return
		}
	} else {
		if _, err := io.ReadFull(conn, make([]byte, 16)); err != nil {
			return
		}
	}

	// The address must be one the client can reach, and the PORT must be the UDP port the client
	// will send to - not this TCP listener's port.
	//
	// It used to be conn.LocalAddr().Port, which is the control connection's port. The client then
	// sends its datagrams there and gets nothing, because the TCP listener does not read UDP: the
	// relay showed traffic arriving and nothing coming back, and nothing anywhere reported an error,
	// because the packet was delivered to a TCP port and discarded by the kernel.
	self := frontAdvertisedAddr(conn, udpConn)
	reply := []byte{0x05, 0x00, 0x00, 0x01}
	reply = append(reply, self.IP.To4()...)
	reply = binary.BigEndian.AppendUint16(reply, uint16(self.Port))
	if _, err := conn.Write(reply); err != nil {
		return
	}
	fmt.Printf("udp associate on %s\n", self)

	go func() {
		// The client's datagrams arrive over the control connection, length-prefixed.
		//
		// Not as UDP: an emulator's NAT does not forward UDP to the host at all - measured with a
		// plain echo server on both the LAN address and the gateway, neither receives anything -
		// while TCP is forwarded normally. So the UDP the client speaks is carried inside the
		// control stream with an explicit length, because TCP has no message boundaries and two
		// datagrams would otherwise arrive as one read.
		var pending []byte
		buf := make([]byte, 65535)
		for {
			n, err := conn.Read(buf)
			if n > 0 && err == nil {
				pending = append(pending, buf[:n]...)
			}
			if err != nil {
				fmt.Printf("front: session ended: %v\n", err)
				return
			}
			for len(pending) >= 2 {
				size := int(pending[0])<<8 | int(pending[1])
				if len(pending) < 2+size {
					break
				}
				frame := pending[2 : 2+size]
				pending = pending[2+size:]
				if len(frame) < 4 || frame[2] != 0 {
					continue
				}
				off := 3
				switch frame[off] {
				case 1:
					off += 4
				case 4:
					off += 16
				default:
					continue
				}
				off += 2
				fmt.Printf("front: forwarding %d bytes to %s\n", len(frame)-off, relayAddr)
				if _, err := toRelay.WriteToUDP(frame[off:], relayAddr); err != nil {
					fmt.Printf("front: write failed: %v\n", err)
					return
				}
			}
		}
	}()

	buf := make([]byte, 65535)
	for {
		// The deadline goes on the socket that is actually read here. It was set on udpConn, which
		// nothing reads from any more, so toRelay blocked forever and no reply was ever forwarded.
		toRelay.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, from, err := toRelay.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		fmt.Printf("front: relayed back %d bytes from %s\n", n, from)
		// Written back over the control connection, which is where a SOCKS5 UDP client reads its
		// replies from (RFC 1928 section 7: the relay sends them "to the client over the TCP
		// connection").
		//
		// Not sent as a datagram to the client's source address: over the emulator's NAT that source
		// is the gateway's loopback, and Windows refuses to send to it. The address in the header is
		// the documented binding; the client matches replies by it and takes the payload from here.
		pkt := []byte{0, 0, 0, 1}
		pkt = append(pkt, self.IP.To4()...)
		pkt = binary.BigEndian.AppendUint16(pkt, uint16(self.Port))
		pkt = append(pkt, buf[:n]...)
		// Length-prefixed, matching what the client sends.
		frame := make([]byte, 2+len(pkt))
		frame[0] = byte(len(pkt) >> 8)
		frame[1] = byte(len(pkt))
		copy(frame[2:], pkt)
		if _, err := conn.Write(frame); err != nil {
			fmt.Printf("front: reply to client failed: %v\n", err)
			return
		}
	}
}

// clientDatagramAddr is where a reply has to go so the client actually receives it.
//
// Not conn.RemoteAddr(): on this host that is the loopback address of the NAT the emulator's
// gateway terminates, and a datagram sent there never leaves the machine - Windows refuses it with
// "The requested address is not valid in its context", which is what the log showed.
//
// What is needed is the address the client's own datagrams carried as their source, which is the
// only one the path back exists for. That is the address in the SOCKS5 header of the last datagram
// the client sent - the relay side of the conversation never sees it, the client side does.
func clientDatagramAddr(conn net.Conn) *net.UDPAddr {
	if v, ok := lastClient.Load().(*net.UDPAddr); ok && v != nil {
		return v
	}
	client := conn.RemoteAddr().(*net.TCPAddr)
	return &net.UDPAddr{IP: client.IP.To4(), Port: client.Port}
}

// lastClient is the source address of the client's most recent datagram.
var lastClient atomic.Value

func rememberClient(addr net.Addr) {
	if udp, ok := addr.(*net.UDPAddr); ok && udp.IP != nil {
		ip := udp.IP
		lastClient.Store(&net.UDPAddr{IP: ip, Port: udp.Port})
	}
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
