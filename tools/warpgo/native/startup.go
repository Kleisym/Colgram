package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	enrolmentBudget     = 14 * time.Second
	enrolmentDNSBudget  = 4 * time.Second
	enrolmentCallBudget = 7 * time.Second
	carrierBudget       = 16 * time.Second
	traceBudget         = 12 * time.Second
)

type startupError struct {
	stage, kind, detail string
	cause               error
}

func (e *startupError) Error() string { return e.stage + ":" + e.kind + ":" + e.detail }
func (e *startupError) Unwrap() error { return e.cause }

func newStartupError(stage, kind, detail string, cause error) *startupError {
	return &startupError{stage: stage, kind: kind, detail: detail, cause: cause}
}

func classifyStartupError(stage string, err error) *startupError {
	// No HTTP response, URL (which can contain a device ID), or credentials
	// cross this boundary into Android logs, notifications or the UI.
	detail := strings.ReplaceAll(stage, "-", " ")
	if err == nil {
		return newStartupError(stage, "network", detail, nil)
	}
	if errors.Is(err, context.Canceled) {
		return newStartupError(stage, "cancelled", detail, err)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return newStartupError(stage, "timeout", detail, err)
	}
	var verification *tls.CertificateVerificationError
	var unknownAuthority x509.UnknownAuthorityError
	var wrongName x509.HostnameError
	var invalidCertificate x509.CertificateInvalidError
	if errors.As(err, &verification) || errors.As(err, &unknownAuthority) ||
		errors.As(err, &wrongName) || errors.As(err, &invalidCertificate) {
		return newStartupError(stage, "tls", detail, err)
	}
	var netError net.Error
	if errors.As(err, &netError) && netError.Timeout() {
		return newStartupError(stage, "timeout", detail, err)
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return newStartupError(stage, "closed", detail, err)
	}
	return newStartupError(stage, "network", detail, err)
}

func waitForContext(ctx context.Context, duration time.Duration) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// ioDeadline never gives a read or write more time than the shared stage.
func ioDeadline(ctx context.Context, duration time.Duration) time.Time {
	deadline := time.Now().Add(duration)
	if ctx.Err() != nil {
		return time.Now()
	}
	if end, ok := ctx.Deadline(); ok && end.Before(deadline) {
		return end
	}
	return deadline
}

// This timeout covers the request and body together. Only a confirmed
// pre-request TCP dial failure can be retried: repeating a registration that
// reached the API but timed out awaiting headers may create a second device.
func callEnrolmentAPI(ctx context.Context, client *http.Client, endpoint, stage, method, token string,
	body map[string]any) (map[string]any, error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, newStartupError(stage, "invalid", "request", err)
	}
	ctx, cancel := context.WithTimeout(ctx, enrolmentCallBudget)
	defer cancel()
	for attempt := 0; ; attempt++ {
		req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(payload))
		if err != nil {
			return nil, newStartupError(stage, "invalid", "request", err)
		}
		setEnrolmentHeaders(req, token)
		req.Header.Set("Content-Type", "application/json; charset=UTF-8")
		resp, err := client.Do(req)
		if err != nil {
			var op *net.OpError
			if attempt == 0 && ctx.Err() == nil && errors.As(err, &op) && op.Op == "dial" {
				if err := waitForContext(ctx, 150*time.Millisecond); err != nil {
					return nil, classifyStartupError(stage, err)
				}
				continue
			}
			var uerr *url.Error
			if errors.As(err, &uerr) {
				err = uerr.Err
			}
			return nil, classifyStartupError(stage, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return nil, newStartupError(stage, "http", strconv.Itoa(resp.StatusCode), nil)
		}
		const maxBody = 1 << 20
		raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
		if err != nil {
			return nil, classifyStartupError(stage, err)
		}
		var result map[string]any
		if len(raw) > maxBody || json.Unmarshal(raw, &result) != nil || result == nil {
			return nil, newStartupError(stage, "invalid", "response", nil)
		}
		return result, nil
	}
}

func setEnrolmentHeaders(req *http.Request, token string) {
	req.Header.Set("User-Agent", "WARP for Android")
	req.Header.Set("CF-Client-Version", "a-6.35-4471")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
}

func resolveWith(ctx context.Context, host string, endpoints []dohEndpoint,
	query func(context.Context, dohEndpoint, string) ([]net.IP, error)) ([]net.IP, error) {
	var last error
	for _, e := range endpoints {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		ips, err := query(ctx, e, host)
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if err == nil && len(ips) > 0 && plausible(host, ips) {
			androidLog(fmt.Sprintf("colgram_masque: resolved %s through %s: %v", host, e.ip, ips))
			return ips, nil
		}
		if err != nil {
			last = err
		} else {
			last = fmt.Errorf("no usable A record for %s", host)
		}
	}
	if last == nil {
		last = fmt.Errorf("no DNS endpoint for %s", host)
	}
	return nil, last
}
