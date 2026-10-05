// language: Go, file: main.go, target: host-side WARP identity registrar
//
// The device cannot complete the enrolment POST on this network and the host cannot reach the
// edge, so the two halves are split: this enrols an identity where the API answers and serves the
// key over loopback, and the device runs the tunnel with it.
package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

const (
	apiHost = "api.cloudflareclient.com"
	apiVer  = "v0a4471"
)

func main() {
	listen := os.Args[1]

	http.HandleFunc("/key", func(w http.ResponseWriter, r *http.Request) {
		k, tok, src, err := enrol()
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		json.NewEncoder(w).Encode(map[string]string{"key": k, "token": tok, "src": src})
	})
	srv := &http.Server{Addr: listen, ReadHeaderTimeout: 5 * time.Second}
	fmt.Println("registrar on", listen)
	if err := srv.ListenAndServe(); err != nil {
		fmt.Println("registrar stopped:", err)
	}
}

func enrol() (string, string, string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", "", err
	}
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return "", "", "", err
	}
	scalar := make([]byte, 32)
	key.D.FillBytes(scalar)

	ip, err := resolve4(apiHost)
	if err != nil {
		return "", "", "", err
	}
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			// HTTP/1.1 only. With the default ALPN the enrolment POST completes TLS and is then closed
			// before a single response byte, which Go reports as a bare EOF; forcing h1 returns a body.
			TLSClientConfig:   &tls.Config{ServerName: apiHost, NextProtos: []string{"http/1.1"}},
			ForceAttemptHTTP2: false,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "tcp", net.JoinHostPort(ip, "443"))
			},
		},
	}
	call := func(method, path, token string, body map[string]any) (map[string]any, error) {
		buf, _ := json.Marshal(body)
		var out map[string]any
		var lastErr error
		for attempt := 1; attempt <= 12; attempt++ {
			req, err := http.NewRequest(method, "https://"+apiHost+path, bytes.NewReader(buf))
			if err != nil {
				return nil, err
			}
			req.Host = apiHost
			req.Header.Set("User-Agent", "WARP for Android")
			req.Header.Set("CF-Client-Version", "a-6.35-4471")
			req.Header.Set("Content-Type", "application/json; charset=UTF-8")
			if token != "" {
				req.Header.Set("Authorization", "Bearer "+token)
			}
			resp, err := client.Do(req)
			if err != nil {
				lastErr = err
				fmt.Printf("%s %s attempt %d: %v\n", method, path, attempt, err)
				if fresh, ferr := resolve4(apiHost); ferr == nil {
					ip = fresh
				}
				time.Sleep(time.Duration(attempt%4) * 700 * time.Millisecond)
				continue
			}
			raw, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode >= 400 {
				return nil, fmt.Errorf("%s %s: %d %s", method, path, resp.StatusCode, string(raw))
			}
			json.Unmarshal(raw, &out)
			return out, nil
		}
		return nil, lastErr
	}

	serial := make([]byte, 16)
	rand.Read(serial)
	reg, err := call("POST", "/"+apiVer+"/reg", "", map[string]any{
		"fcm_token": "", "install_id": "", "tos": "2024-06-01T00:00:00.000Z",
		"model": "PC", "type": "Android", "serial_number": fmt.Sprintf("%x", serial),
		"locale": "en_US", "region": "US", "warp_enabled": true,
		"key": base64.StdEncoding.EncodeToString(scalar)})
	if err != nil {
		return "", "", "", fmt.Errorf("register: %w", err)
	}
	id, _ := reg["id"].(string)
	token, _ := reg["token"].(string)
	if id == "" || token == "" {
		return "", "", "", fmt.Errorf("register: no id/token in %v", reg)
	}
	if _, err := call("PATCH", "/"+apiVer+"/reg/"+id, token, map[string]any{
		"key": base64.StdEncoding.EncodeToString(spki),
		"key_type": "secp256r1", "tun_type": "masque"}); err != nil {
		return "", "", "", fmt.Errorf("enrol: %w", err)
	}

	src := "172.16.0.2"
	if cfg, ok := reg["config"].(map[string]any); ok {
		if iface, ok := cfg["interface"].(map[string]any); ok {
			if addrs, ok := iface["addresses"].(map[string]any); ok {
				if v4, ok := addrs["v4"].(string); ok && v4 != "" {
					src = v4
				}
			}
		}
	}
	keyDER, _ := x509.MarshalPKCS8PrivateKey(key)
	fmt.Println("enrolled id", id, "src", src)
	return base64.StdEncoding.EncodeToString(keyDER), token, src, nil
}

func resolve4(name string) (string, error) {
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query", "https://9.9.9.9/dns-query"} {
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
		for _, a := range out.Answer {
			if a.Type == 1 {
				return a.Data, nil
			}
		}
	}
	return "", fmt.Errorf("no A record for %s over DoH", name)
}
