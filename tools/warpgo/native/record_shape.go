package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"time"
)

// This wrapper is used only below Go's own TLS client. It changes record boundaries,
// not the handshake transcript, SNI, certificate verification, or HTTP request.
// It must not be used to rewrite Telegram's authenticated FakeTLS handshake.
type helloRecordConn struct {
	net.Conn
	host string
	done bool
}

func writeRecordBytes(c net.Conn, p []byte) error {
	for len(p) > 0 {
		n, err := c.Write(p)
		if err != nil {
			return err
		}
		if n <= 0 {
			return io.ErrShortWrite
		}
		p = p[n:]
	}
	return nil
}

func (c *helloRecordConn) Write(p []byte) (int, error) {
	if c.done {
		return c.Conn.Write(p)
	}
	c.done = true
	if len(p) < 9 || p[0] != 22 || p[1] != 3 || p[5] != 1 {
		return c.Conn.Write(p)
	}
	endRecord := 5 + int(binary.BigEndian.Uint16(p[3:5]))
	if endRecord > len(p) || endRecord <= 9 {
		return c.Conn.Write(p)
	}
	cut := -1
	if c.host != "" {
		if index := bytes.Index(p[:endRecord], []byte(c.host)); index >= 5 {
			cut = index + len(c.host)/2
		}
	}
	for start := 5; start < endRecord; {
		end := min(start+128, endRecord)
		if start < cut && cut < end {
			end = cut
		}
		header := []byte{22, p[1], p[2], byte((end - start) >> 8), byte(end - start)}
		if err := writeRecordBytes(c.Conn, header); err != nil {
			return start, err
		}
		if err := writeRecordBytes(c.Conn, p[start:end]); err != nil {
			return start, err
		}
		start = end
		// At most ~130 ms for a maximum-sized TLS record; socket/TLS deadlines still apply.
		if start < endRecord {
			time.Sleep(time.Millisecond)
		}
	}
	if err := writeRecordBytes(c.Conn, p[endRecord:]); err != nil {
		return endRecord, err
	}
	return len(p), nil
}
