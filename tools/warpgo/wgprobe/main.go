// language: Go, file: main.go, target: android/arm64 - does WireGuard UDP reach the WARP edge here
//
// MASQUE over HTTP/3 needs UDP to 2408/500/1701/4500 and this network drops it, which is measured. WARP's
// other carrier is WireGuard over the same family of ports, so the question worth asking next is whether
// the drop is specific to the QUIC payloads on those ports or to the ports themselves. A WireGuard
// handshake initiation is a 148-byte UDP datagram to a known endpoint, and the peer answers with a
// handshake response naming its own ephemeral port - so a reply is unambiguous and no key material is
// needed beyond the static one Cloudflare publishes for WARP.
package main

import (
	"encoding/base64"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strconv"
	"time"

	"golang.org/x/crypto/curve25519"
)

type target struct{ ip string; port int }

func main() {
	// The public key Cloudflare publishes for the free WARP profile.
	const peerHex = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
	peerRaw, err := base64Decode(peerHex)
	if err != nil {
		fmt.Println("peer key:", err)
		return
	}
	peerPub, err := curve25519.X25519(peerRaw, curve25519.Basepoint)
	if err != nil {
		fmt.Println("peer key is not a curve point:", err)
		return
	}
	fmt.Printf("peer public key derived, %d bytes\n", len(peerPub))
	var priv [32]byte
	rand.Read(priv[:])

	targets := []target{
		{"162.159.192.6", 2408}, {"162.159.192.1", 2408}, {"188.114.97.1", 2408},
		{"162.159.192.6", 500}, {"162.159.192.6", 443}, {"188.114.97.1", 500},
	}
	if v := os.Getenv("ONLY"); v != "" {
		var keep []target
		for _, t := range targets {
			if strconv.Itoa(t.port) == v {
				keep = append(keep, t)
			}
		}
		targets = keep
	}

	for _, t := range targets {
		fmt.Printf("=== %s:%d ===", t.ip, t.port)
		if err := probe(t, &priv, peerPub); err != nil {
			fmt.Println("  FAILED:", err)
		}
	}
}

// probe sends one handshake initiation and reports whether anything at all answers.
func probe(t target, priv *[32]byte, peerPub []byte) error {
	// BIND picks the local address. With several tunnels holding a default route, the source address the
	// kernel picks decides which one carries the datagram, and the address the tunnel owns is not the one
	// with a working path to the edge.
	laddr := &net.UDPAddr{}
	if b := os.Getenv("BIND"); b != "" {
		laddr.IP = net.ParseIP(b).To4()
	}
	conn, err := net.DialUDP("udp", laddr, &net.UDPAddr{IP: net.ParseIP(t.ip), Port: t.port})
	if err != nil {
		return err
	}
	defer conn.Close()
	fmt.Println("  local:", conn.LocalAddr())

	// Handshake initiation, type 1, with an all-zero sender index so the reply is a handshake
	// response rather than a cookie: type 2 from the peer is enough to prove the path is open.
	var msg [148]byte
	binary.LittleEndian.PutUint32(msg[0:4], 0x00000001)
	rand.Read(msg[4:8])   // sender index, ephemeral
	rand.Read(msg[8:24])  // ephemeral public key, not usable for a real session but harmless
	binary.LittleEndian.PutUint64(msg[24:32], 0) // mac1, zeroed means unverified
	binary.LittleEndian.PutUint64(msg[80:88], 0) // mac2
	copy(msg[88:120], peerPub)

	if _, err := conn.Write(msg[:]); err != nil {
		return fmt.Errorf("write: %w", err)
	}
	fmt.Println("  sent 148-byte handshake initiation")

	conn.SetReadDeadline(time.Now().Add(8 * time.Second))
	buf := make([]byte, 256)
	n, rerr := conn.Read(buf)
	if rerr != nil {
		return fmt.Errorf("read: %w", rerr)
	}
	msgType := binary.LittleEndian.Uint32(buf[0:4])
	fmt.Printf("  ANSWERED: %d bytes, message type %d\n", n, msgType)
	return nil
}

func base64Decode(s string) ([]byte, error) { return base64.StdEncoding.DecodeString(s) }
