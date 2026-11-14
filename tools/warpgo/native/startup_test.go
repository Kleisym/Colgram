package main

import (
	"context"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRegistrationPreflightSelectsVerifiedAlternateWithoutPostingTwice(t *testing.T) {
	var heads, posts atomic.Int32
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodHead:
			heads.Add(1)
			if r.ProtoMajor != 2 {
				t.Errorf("wanted normal ALPN to negotiate HTTP/2, got %s", r.Proto)
			}
			w.WriteHeader(http.StatusNotFound) // A valid HTTPS transport, not a registration.
		case http.MethodPost:
			posts.Add(1)
			_, _ = io.WriteString(w, "{}")
		default:
			t.Errorf("unexpected method %s", r.Method)
		}
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	tlsConfig := enrolmentTLSConfig()
	tlsConfig.RootCAs = roots
	tlsConfig.ServerName = "example.com" // Included in httptest's certificate, not the dial target.
	var stalled atomic.Int32
	dial := func(ctx context.Context, _, addr string) (net.Conn, error) {
		if strings.HasPrefix(addr, "192.0.2.1:") {
			stalled.Add(1)
			<-ctx.Done()
			return nil, ctx.Err()
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	client, err := selectEnrolmentAPI(ctx, "https://example.com/register", []net.IP{
		net.ParseIP("192.0.2.1"), net.ParseIP("2001:db8::1"),
	}, tlsConfig, dial)
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseIdleConnections()
	if _, err := callEnrolmentAPI(ctx, client, "https://example.com/register", "register", http.MethodPost, "", nil); err != nil {
		t.Fatal(err)
	}
	if stalled.Load() < 1 || heads.Load() != 1 || posts.Load() != 1 {
		t.Fatalf("expected one reachable HEAD and one registration, stalled=%d heads=%d posts=%d", stalled.Load(), heads.Load(), posts.Load())
	}
}

func TestRegistrationPreflightReportsTLSStageBeforeAnyHTTP(t *testing.T) {
	var connects atomic.Int32
	peers := make(chan net.Conn, 2)
	dial := func(_ context.Context, _, _ string) (net.Conn, error) {
		local, peer := net.Pipe()
		connects.Add(1)
		peers <- peer
		return local, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	_, err := selectEnrolmentAPI(ctx, "https://example.com/register", []net.IP{net.ParseIP("192.0.2.1")}, enrolmentTLSConfig(), dial)
	for len(peers) > 0 {
		(<-peers).Close()
	}
	if err == nil || err.Error() != "api-tls:timeout:api tls" || connects.Load() == 0 {
		t.Fatalf("expected a TLS timeout before HTTP was sent, connects=%d error=%v", connects.Load(), err)
	}
}

func TestResolverOnlyReturnsRequestedPublicAddressFamily(t *testing.T) {
	response := []byte(`{"Status":0,"Answer":[{"type":28,"data":"2606:4700::6810:1854"}, {"type":28,"data":"::1"}, {"type":1,"data":"104.16.24.84"}]}`)
	ips, err := decodeDoHAddressAnswers(response, 28)
	if err != nil || len(ips) != 2 || len(eligibleEnrolmentAddresses(append(ips, net.ParseIP("127.0.0.1")), apiHost)) != 1 || !ips[0].Equal(net.ParseIP("2606:4700::6810:1854")) {
		t.Fatalf("invalid AAAA handling: ips=%v err=%v", ips, err)
	}
}

func TestRegistrationDeadlineIsSharedAcrossAttempts(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		// Read the request body so net/http sees the disconnect.
		_, _ = io.Copy(io.Discard, req.Body)
		requests.Add(1)
		<-req.Context().Done()
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := callEnrolmentAPI(ctx, server.Client(), server.URL+"/register", "register", http.MethodPost, "", nil)
	if !errors.Is(err, context.DeadlineExceeded) || !strings.HasPrefix(err.Error(), "register:timeout:") {
		t.Fatalf("expected typed register timeout, got %v", err)
	}
	if time.Since(start) > time.Second || requests.Load() != 1 {
		t.Fatalf("a non-idempotent registration was retried or missed the deadline (requests=%d)", requests.Load())
	}
}

func TestEnrolmentFailureDoesNotLeakAPIResponseOrPath(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		http.Error(w, "private response marker", http.StatusForbidden)
	}))
	defer server.Close()
	_, err := callEnrolmentAPI(context.Background(), server.Client(), server.URL+"/private-path-marker", "enrol", http.MethodPatch, "private bearer marker", nil)
	if err == nil || err.Error() != "enrol:http:403" {
		t.Fatalf("expected only stage and HTTP status, got %v", err)
	}
}

func TestResolverCancellationStopsBeforeTryingNextEndpoint(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	var calls int
	_, err := resolveWith(ctx, apiHost, []dohEndpoint{{ip: "first"}, {ip: "second"}},
		func(ctx context.Context, e dohEndpoint, host string) ([]net.IP, error) {
			calls++
			cancel()
			return nil, ctx.Err()
		})
	if !errors.Is(err, context.Canceled) || calls != 1 {
		t.Fatalf("cancelled DNS request continued: calls=%d err=%v", calls, err)
	}
}

func TestBackoffObservesCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	if !errors.Is(waitForContext(ctx, time.Minute), context.Canceled) || time.Since(start) > time.Second {
		t.Fatal("cancellation did not interrupt the retry wait")
	}
}

func TestSessionFailureIsClearedByNextAttempt(t *testing.T) {
	var stage sessionProgressRecord
	stage.set("failed", "register:timeout:no response")
	if stage.failure() != "register:timeout:no response" {
		t.Fatalf("stage discarded the concrete failure: %q", stage.failure())
	}
	stage.set("enrolling", "")
	if got := stage.failure(); got != "" {
		t.Fatalf("old failure survived a new attempt: %q", got)
	}
}
