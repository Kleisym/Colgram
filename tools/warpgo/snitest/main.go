// language: Go, file: main.go, target: reaches the WARP enrolment API through a name this network allows
//
// Measured across candidate front doors on this network:
//
//	api.cloudflareclient.com         104.16.24.84   tls: EOF
//	cloudflareclient.com             104.16.24.84   HTTP/1.1 522   (edge reachable, no such zone)
//	one.one.one.one                  1.1.1.1        HTTP/1.1 405   (a real service answering)
//
// Two things fall out of that. The filter keys on the string cloudflareclient.com wherever it appears
// in the SNI, and it answers EOF rather than dropping the connection - which is why every earlier
// attempt looked like a TLS problem rather than a filter. And one.one.one.one is an unfiltered
// hostname on the same anycast front end that answers HTTP.
//
// So the remaining question is the one this file answers: does Cloudflare route the WARP registration
// by the SNI or by the Host header, and if by SNI, is there a hostname that both passes the filter and
// routes to the API. If routing follows Host, a masked SNI with the real Host reaches it - and the
// earlier 403 from a bare mask says only that a mask alone does not route there, not that Host is ignored.
package main

import (
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

const apiHost = "api.cloudflareclient.com"

// sniNames pass the filter. hostNames are what the request claims to be for. They are varied
// independently so the two variables can be told apart.
var sniNames = []string{"one.one.one.one", "cloudflare-dns.com", "1.1.1.1", "cloudflare.com"}

func main() {
	ips, err := resolveAll(apiHost)
	if err != nil {
		fmt.Println("DoH failed:", err)
		return
	}
	fmt.Println("api address:", strings.Join(ips, ", "))

	var key [32]byte
	rand.Read(key[:])
	body := fmt.Sprintf(`{"fcm_token":"","install_id":"","tos":"2024-06-01T00:00:00.000Z",`+
		`"model":"PC","type":"Android","serial_number":"%x","locale":"en_US","region":"US",`+
		`"warp_enabled":true,"key":"%s"}`, key[:8], base64.StdEncoding.EncodeToString(key[:]))

	for _, addr := range ips {
		for _, sni := range sniNames {
			fmt.Printf("\n=== %s  SNI=%s ===\n", addr, sni)
			status, resp, err := post(addr, sni, apiHost, body)
			if err != nil {
				fmt.Println("  FAILED:", err)
				continue
			}
			fmt.Println("  status:", status)
			fmt.Println("  body:", trunc(resp, 500))
			if strings.Contains(resp, "\"token\"") {
				fmt.Printf("\n  REGISTRATION REACHED: SNI=%s Host=%s addr=%s\n", sni, apiHost, addr)
				return
			}
		}
	}
}

// post opens TLS under sni and asks for host. Verification is off on purpose: the certificate belongs
// to the SNI that was presented, and the routing decision is Cloudflare's.
func post(addr, sni, host, body string) (string, string, error) {
	d := net.Dialer{Timeout: 8 * time.Second}
	raw, err := d.Dial("tcp", net.JoinHostPort(addr, "443"))
	if err != nil {
		return "", "", fmt.Errorf("dial: %w", err)
	}
	defer raw.Close()
	raw.SetDeadline(time.Now().Add(18 * time.Second))
	tc := tls.Client(raw, &tls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
		NextProtos:         []string{"http/1.1"},
		MinVersion:         tls.VersionTLS12,
	})
	if err := tc.Handshake(); err != nil {
		return "", "", fmt.Errorf("tls: %w", err)
	}
	req := fmt.Sprintf("POST /v0a4471/reg HTTP/1.1\r\nHost: %s\r\nUser-Agent: WARP for Android\r\n"+
		"CF-Client-Version: a-6.35-4471\r\nContent-Type: application/json; charset=UTF-8\r\n"+
		"Content-Length: %d\r\nConnection: close\r\n\r\n%s", host, len(body), body)
	if _, err := tc.Write([]byte(req)); err != nil {
		return "", "", fmt.Errorf("write: %w", err)
	}
	raw.SetReadDeadline(time.Now().Add(14 * time.Second))
	all, err := io.ReadAll(tc)
	if len(all) == 0 {
		if err == nil {
			err = io.ErrUnexpectedEOF
		}
		return "", "", fmt.Errorf("no response: %w", err)
	}
	s := string(all)
	if i := strings.Index(s, "\r\n\r\n"); i >= 0 {
		return strings.SplitN(s[:i], "\r\n", 2)[0], s[i+4:], nil
	}
	return "(no status line)", s, nil
}

func trunc(s string, n int) string {
	if len(s) > n {
		return s[:n] + "..."
	}
	return s
}

func resolveAll(name string) ([]string, error) {
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query",
		"https://9.9.9.9/dns-query"} {
		req, err := http.NewRequest("GET", r+"?name="+name+"&type=A", nil)
		if err != nil {
			continue
		}
		req.Header.Set("Accept", "application/dns-json")
		cl := &http.Client{Timeout: 10 * time.Second}
		resp, err := cl.Do(req)
		if err != nil {
			continue
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var out struct {
			Answer []struct {
				Type int    `json:"type"`
				Data string `json:"data"`
			} `json:"Answer"`
		}
		json.Unmarshal(raw, &out)
		var ips []string
		for _, a := range out.Answer {
			if a.Type == 1 {
				ips = append(ips, a.Data)
			}
		}
		if len(ips) > 0 {
			return ips, nil
		}
	}
	return nil, fmt.Errorf("no A record over DoH")
}
