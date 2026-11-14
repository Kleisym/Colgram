package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// ipPacketQueue preserves datagram boundaries and has a fixed memory bound.
// It owns each accepted packet; a carrier may reuse its receive buffer.
type ipPacketQueue struct{ packets chan []byte }

func newIPPacketQueue(capacity int) *ipPacketQueue {
	if capacity < 1 {
		capacity = 1
	}
	return &ipPacketQueue{packets: make(chan []byte, capacity)}
}

func validIPv4(packet []byte) bool {
	if len(packet) < 20 || packet[0]>>4 != 4 {
		return false
	}
	header := int(packet[0]&15) * 4
	total := int(binary.BigEndian.Uint16(packet[2:4]))
	return header >= 20 && header <= len(packet) && total >= header && total == len(packet)
}

func (q *ipPacketQueue) offer(packet []byte) bool {
	if !validIPv4(packet) {
		return false
	}
	owned := append([]byte(nil), packet...)
	select {
	case q.packets <- owned:
		return true
	default:
		return false // Backpressure: drop rather than grow an unbounded VPN queue.
	}
}

func (q *ipPacketQueue) receive(ctx context.Context, timeout time.Duration) []byte {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return nil
	case packet := <-q.packets:
		return packet
	case <-timer.C:
		return nil
	}
}

func verifiedTLSConfig(serverName string, minimum uint16) *tls.Config {
	return &tls.Config{ServerName: serverName, MinVersion: minimum}
}

// MASQUE uses an enrolled self-signed endpoint, authenticated by the SPKI key
// returned by its HTTPS registration API. Never learn a pin from an untrusted dial.
func pinnedTLSConfig(serverName string, certificate tls.Certificate, expectedSPKI []byte) *tls.Config {
	pin := append([]byte(nil), expectedSPKI...)
	config := verifiedTLSConfig(serverName, tls.VersionTLS13)
	config.Certificates = []tls.Certificate{certificate}
	config.InsecureSkipVerify = true // Replaced by mandatory key authentication below.
	config.VerifyConnection = func(state tls.ConnectionState) error {
		if _, err := x509.ParsePKIXPublicKey(pin); err != nil {
			return fmt.Errorf("missing/invalid enrolled MASQUE endpoint key: %w", err)
		}
		if len(state.PeerCertificates) == 0 {
			return fmt.Errorf("MASQUE endpoint sent no certificate")
		}
		if subtle.ConstantTimeCompare(state.PeerCertificates[0].RawSubjectPublicKeyInfo, pin) != 1 {
			return fmt.Errorf("MASQUE endpoint public key does not match HTTPS enrollment")
		}
		return nil
	}
	return config
}

// A service instance may stop after its replacement has already started. Tokens
// increase within this process, so a delayed close/open never owns that replacement.
type nativeSessionOwner struct {
	mu     sync.Mutex
	token  atomic.Int64
	active atomic.Bool
}

// Status is part of a session, not a process-wide verdict that an old goroutine
// can overwrite after a replacement has already started.
type sessionProgressRecord struct {
	mu            sync.Mutex
	stage, detail string
}

func (p *sessionProgressRecord) set(stage, detail string) {
	p.mu.Lock()
	p.stage, p.detail = stage, detail
	p.mu.Unlock()
}
func (p *sessionProgressRecord) text() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.detail == "" {
		return p.stage
	}
	return p.stage + " (" + p.detail + ")"
}

func (p *sessionProgressRecord) failure() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.stage == "failed" {
		return p.detail
	}
	return ""
}

func (o *nativeSessionOwner) claim(token int64, closePrevious func()) bool {
	o.mu.Lock()
	defer o.mu.Unlock()
	current := o.token.Load()
	if token < current || token <= 0 || (token == current && !o.active.Load()) {
		return false
	}
	if token > current {
		o.active.Store(false)
		o.token.Store(token)
		closePrevious()
		o.active.Store(true)
	}
	return true
}

func (o *nativeSessionOwner) isCurrent(token int64) bool {
	return o.token.Load() == token && o.active.Load()
}

func (o *nativeSessionOwner) closeIfCurrent(token int64, closeCurrent func()) bool {
	o.mu.Lock()
	defer o.mu.Unlock()
	if !o.isCurrent(token) {
		return false
	}
	o.active.Store(false)
	closeCurrent()
	return true
}
