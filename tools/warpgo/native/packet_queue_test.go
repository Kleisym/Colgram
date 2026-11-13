package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"net/http/httptest"
	"testing"
	"time"
)

func testIPv4(payload byte) []byte {
	p := make([]byte, 21)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	p[9] = 17
	p[20] = payload
	return p
}

func TestQueuePreservesCompletePacketsAndOwnership(t *testing.T) {
	q := newIPPacketQueue(2)
	p := testIPv4(42)
	if !q.offer(p) {
		t.Fatal("valid packet rejected")
	}
	p[20] = 1
	got := q.receive(context.Background(), time.Second)
	if len(got) != 21 || got[0] != 0x45 || got[20] != 42 {
		t.Fatalf("not a copied whole IP packet: %x", got)
	}
}

func TestQueueIsBoundedAndKeepsPacketBoundaries(t *testing.T) {
	q := newIPPacketQueue(2)
	if !q.offer(testIPv4(1)) || !q.offer(testIPv4(2)) || q.offer(testIPv4(3)) {
		t.Fatal("queue bound violated")
	}
	for _, want := range []byte{1, 2} {
		if got := q.receive(context.Background(), time.Second); len(got) != 21 || got[20] != want {
			t.Fatalf("packet boundary lost: %x", got)
		}
	}
}

func TestRejectsTruncatedAndNonIPPackets(t *testing.T) {
	q := newIPPacketQueue(2)
	for _, p := range [][]byte{nil, {1, 2, 3}, append([]byte{0x45, 0, 0, 40}, make([]byte, 16)...), append([]byte{0x41}, make([]byte, 20)...)} {
		if q.offer(p) {
			t.Fatalf("accepted invalid packet: %x", p)
		}
	}
}

func TestQueueCancellationAndIdleWait(t *testing.T) {
	q := newIPPacketQueue(1)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	if q.receive(ctx, time.Second) != nil || time.Since(start) > 100*time.Millisecond {
		t.Fatal("cancellation did not wake receiver")
	}
	start = time.Now()
	if q.receive(context.Background(), 20*time.Millisecond) != nil || time.Since(start) < 10*time.Millisecond {
		t.Fatal("idle queue spins")
	}
}

func TestTLSRejectsUntrustedCertificate(t *testing.T) {
	server := httptest.NewTLSServer(nil)
	defer server.Close()
	cfg := verifiedTLSConfig("localhost", tls.VersionTLS12)
	cfg.InsecureSkipVerify = false
	c, err := tls.Dial("tcp", server.Listener.Addr().String(), cfg)
	if c != nil {
		c.Close()
	}
	if err == nil {
		t.Fatal("accepted an untrusted server certificate")
	}
	if cfg.ServerName != "localhost" || cfg.InsecureSkipVerify || cfg.MinVersion < tls.VersionTLS12 {
		t.Fatal("TLS authentication disabled")
	}
}

func TestPinnedTLSAuthenticatesSelfSignedMasqueEndpoint(t *testing.T) {
	server := httptest.NewTLSServer(nil)
	defer server.Close()
	leaf, err := x509.ParseCertificate(server.TLS.Certificates[0].Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	cfg := pinnedTLSConfig("masque.test", tls.Certificate{}, leaf.RawSubjectPublicKeyInfo)
	c, err := tls.Dial("tcp", server.Listener.Addr().String(), cfg)
	if c != nil {
		c.Close()
	}
	if err != nil {
		t.Fatalf("trusted MASQUE key rejected: %v", err)
	}
	for _, pin := range [][]byte{nil, {1, 2, 3}} {
		cfg = pinnedTLSConfig("masque.test", tls.Certificate{}, pin)
		c, err = tls.Dial("tcp", server.Listener.Addr().String(), cfg)
		if c != nil {
			c.Close()
		}
		if err == nil {
			t.Fatal("empty/wrong endpoint pin accepted")
		}
	}
	if cfg.VerifyConnection(tls.ConnectionState{}) == nil {
		t.Fatal("accepted missing peer certificate")
	}
}

func TestOwnedTeardownCannotCloseReplacement(t *testing.T) {
	owner := &nativeSessionOwner{}
	closes := 0
	close := func() { closes++ }
	if !owner.claim(1, close) || !owner.isCurrent(1) {
		t.Fatal("first owner rejected")
	}
	if !owner.claim(2, close) || owner.isCurrent(1) {
		t.Fatal("replacement not owned")
	}
	before := closes
	if owner.closeIfCurrent(1, close) || closes != before {
		t.Fatal("old teardown closed replacement")
	}
	if owner.claim(1, close) || closes != before {
		t.Fatal("old open replaced new owner")
	}
	if !owner.closeIfCurrent(2, close) || owner.isCurrent(2) {
		t.Fatal("current teardown not applied")
	}
	before = closes
	if owner.closeIfCurrent(2, close) || closes != before || owner.claim(2, close) {
		t.Fatal("stopped owner resurrected or duplicate teardown ran")
	}
	if !owner.claim(3, close) || !owner.isCurrent(3) {
		t.Fatal("new start after stop failed")
	}
}

func TestLateFailureBelongsOnlyToItsOldSession(t *testing.T) {
	old := &sessionProgressRecord{}
	current := &sessionProgressRecord{}
	old.set("open", "old")
	current.set("open", "replacement")
	old.set("failed", "late trace returned after close")
	if current.text() != "open (replacement)" {
		t.Fatal("old asynchronous error changed replacement status")
	}
	if old.text() != "failed (late trace returned after close)" {
		t.Fatal("old error was lost")
	}
}
