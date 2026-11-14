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
	if config.MinVersion < tls.VersionTLS12 || len(config.NextProtos) != 1 || config.NextProtos[0] != "http/1.1" {
		t.Fatal("enrolment must negotiate TLS 1.2+ and HTTP/1.1")
	}
}
