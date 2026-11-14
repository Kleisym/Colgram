package main

import (
	"context"
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
