package main

import (
	"crypto/tls"
	"testing"
)

func TestEnrolmentTLSUsesAPIHostAndKeepsVerification(t *testing.T) {
	config := enrolmentTLSConfig()
	if config.ServerName != apiHost || config.InsecureSkipVerify {
		t.Fatal("enrolment must authenticate the actual Cloudflare API hostname")
	}
	if config.MinVersion < tls.VersionTLS12 || len(config.NextProtos) != 0 {
		t.Fatal("enrolment must use TLS 1.2+ and let the selected HTTP transport negotiate ALPN")
	}
}
