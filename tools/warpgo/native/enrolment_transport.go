package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptrace"
	"sync/atomic"
	"time"
)

const enrolmentPreflightBudget = 3500 * time.Millisecond

type enrolmentDial func(context.Context, string, string) (net.Conn, error)

type apiProbeResult struct {
	client *http.Client
	phase  uint32
	proto  string
	status int
	err    error
}

// selectEnrolmentAPI may race only side-effect-free HEADs. The subsequent
// registration stays on the winning verified hostname, address and protocol.
func selectEnrolmentAPI(ctx context.Context, endpoint string, addresses []net.IP, tlsConfig *tls.Config,
	dial enrolmentDial) (*http.Client, error) {
	if len(addresses) == 0 {
		return nil, newStartupError("dns", "invalid", "no usable API address", nil)
	}
	ctx, cancel := context.WithTimeout(ctx, enrolmentPreflightBudget)
	defer cancel()
	if len(addresses) > 4 {
		addresses = addresses[:4]
	}
	results := make(chan apiProbeResult, len(addresses)*2)
	for i, ip := range addresses {
		for _, http2 := range []bool{true, false} {
			delay := time.Duration(i) * 50 * time.Millisecond
			if !http2 {
				delay += 250 * time.Millisecond
			}
			go func() {
				if err := waitForContext(ctx, delay); err != nil {
					results <- apiProbeResult{err: err}
					return
				}
				results <- probeEnrolmentAPI(ctx, endpoint, net.JoinHostPort(ip.String(), "443"), tlsConfig, dial, http2)
			}()
		}
	}
	var best apiProbeResult
	for remaining := cap(results); remaining > 0; remaining-- {
		result := <-results // Each protected request is bounded by ctx.
		if result.client != nil {
			cancel()
			go func(left int) {
				for range left {
					if other := <-results; other.client != nil {
						other.client.CloseIdleConnections()
					}
				}
			}(remaining - 1)
			androidLog(fmt.Sprintf("colgram_masque: verified registration HTTPS path (%s, HEAD %d)", result.proto, result.status))
			return result.client, nil
		}
		if best.err == nil || result.phase > best.phase ||
			(result.phase == best.phase && classifyStartupError("api", result.err).kind == "tls") {
			best = result
		}
	}
	stages := [...]string{"api-connect", "api-tls", "api-http"}
	return nil, classifyStartupError(stages[best.phase], best.err)
}

func probeEnrolmentAPI(ctx context.Context, endpoint, dialTarget string, tlsConfig *tls.Config,
	dial enrolmentDial, http2 bool) apiProbeResult {
	probeContext := ctx
	protectedDial := dial
	dial = func(ctx context.Context, network, target string) (net.Conn, error) {
		conn, err := protectedDial(ctx, network, target)
		if err != nil { return nil, err }
		return &helloRecordConn{Conn: conn, host: tlsConfig.ServerName}, nil
	}
	var phase atomic.Uint32
	transport := &http.Transport{
		// Do not inherit an environment, Android or user proxy. No unpinned dial.
		ForceAttemptHTTP2:   http2,
		TLSClientConfig:     tlsConfig.Clone(),
		TLSHandshakeTimeout: enrolmentPreflightBudget,
	}
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	clientTrace := &httptrace.ClientTrace{
		TLSHandshakeStart: func() { phase.Store(1) },
		TLSHandshakeDone: func(_ tls.ConnectionState, err error) {
			if err == nil {
				phase.Store(2)
			}
		},
	}
	transport.DialContext = func(ctx context.Context, network, _ string) (net.Conn, error) {
		if httptrace.ContextClientTrace(ctx) != clientTrace {
			return dial(ctx, network, dialTarget)
		}
		// net/http deliberately detaches a pending dial from cancellation
		// to share it with a future request. Only during the read-only
		// probe, restore its bound so losing races cannot keep dialing.
		dialCtx, stop := context.WithCancel(ctx)
		unlink := context.AfterFunc(probeContext, stop)
		defer func() { unlink(); stop() }()
		return dial(dialCtx, network, dialTarget)
	}
	req, err := http.NewRequestWithContext(httptrace.WithClientTrace(ctx, clientTrace), http.MethodHead, endpoint, nil)
	if err == nil {
		setEnrolmentHeaders(req, "")
		var resp *http.Response
		resp, err = client.Do(req)
		if err == nil {
			resp.Body.Close()
			// Any HTTPS response, including 404/405, proves this route to the
			// verified host answered. It says nothing about POST registration.
			return apiProbeResult{client: client, proto: resp.Proto, status: resp.StatusCode}
		}
	}
	client.CloseIdleConnections()
	if ctx.Err() != nil {
		err = ctx.Err()
	}
	return apiProbeResult{phase: phase.Load(), err: err}
}

// At most two authenticated DNS candidates in each family. Interleave the
// families so a working IPv6 route isn't lost behind a large IPv4 answer.
func eligibleEnrolmentAddresses(addresses []net.IP, host string) []net.IP {
	var v4, v6 []net.IP
	seen := map[string]bool{}
	for _, ip := range addresses {
		if ip == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() || seen[ip.String()] || !plausible(host, []net.IP{ip}) {
			continue
		}
		seen[ip.String()] = true
		family := &v6
		if ip.To4() != nil {
			family = &v4
		}
		if len(*family) < 2 {
			*family = append(*family, ip)
		}
	}
	var chosen []net.IP
	for i := range 2 {
		if len(v4) > i {
			chosen = append(chosen, v4[i])
		}
		if len(v6) > i {
			chosen = append(chosen, v6[i])
		}
	}
	return chosen
}

func decodeDoHAddressAnswers(raw []byte, rrType int) ([]net.IP, error) {
	var response struct {
		Status int
		Answer []struct {
			Type int
			Data string
		}
	}
	if err := json.Unmarshal(raw, &response); err != nil {
		return nil, err
	}
	var addresses []net.IP
	if response.Status == 0 {
		for _, answer := range response.Answer {
			if ip := net.ParseIP(answer.Data); answer.Type == rrType && ip != nil && (ip.To4() != nil) == (rrType == 1) {
				addresses = append(addresses, ip)
			}
		}
	}
	if len(addresses) == 0 {
		return nil, errors.New("no DNS address of requested family")
	}
	return addresses, nil
}

func resolveEnrolmentAPI(ctx context.Context) ([]net.IP, error) {
	ctx, cancel := context.WithTimeout(ctx, enrolmentDNSBudget)
	defer cancel()
	type resolution struct {
		source    int
		addresses []net.IP
	}
	results := make(chan resolution, 3)
	for index, family := range []struct {
		name   string
		rrType int
	}{{"A", 1}, {"AAAA", 28}} {
		go func() {
			doh, stop := context.WithTimeout(ctx, 1800*time.Millisecond)
			defer stop()
			addresses, _ := resolveWith(doh, apiHost, dohEnrolmentEndpoints, func(c context.Context, endpoint dohEndpoint, host string) ([]net.IP, error) {
				return resolveViaFamily(c, endpoint, host, family.name, family.rrType)
			})
			results <- resolution{index, addresses}
		}()
	}
	go func() {
		dns, stop := context.WithTimeout(ctx, 1300*time.Millisecond)
		defer stop()
		addresses, _ := net.DefaultResolver.LookupIP(dns, "ip", apiHost)
		results <- resolution{2, addresses}
	}()
	// Prefer independently authenticated DoH answers over local resolver answers.
	// The latter can still help on a network that has no reachable public DNS-over-HTTPS.
	var bySource [3][]net.IP
	for range 3 {
		result := <-results
		bySource[result.source] = result.addresses
	}
	addresses := append(append(bySource[0], bySource[1]...), bySource[2]...)
	if len(addresses) == 0 && ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return eligibleEnrolmentAddresses(addresses, apiHost), nil
}
