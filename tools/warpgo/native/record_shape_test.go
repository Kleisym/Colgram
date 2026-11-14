package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
 "net/http"
 "os"
 "context"
 "github.com/quic-go/quic-go/http3"
	"testing"
	"time"
)

func TestTLSRecordShapePreservesHandshake(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	payload := bytes.Repeat([]byte("abcdef"), 150)
	copy(payload[151:], []byte("api.cloudflareclient.com"))
	payload[0] = 1
	original := append([]byte{22, 3, 1, byte(len(payload) >> 8), byte(len(payload))}, payload...)
	suffix := []byte{23, 3, 3, 0, 2, 91, 92}
	original = append(original, suffix...)
	shaped := &helloRecordConn{Conn: a, host: "api.cloudflareclient.com"}
	done := make(chan error, 1)
	go func() {
		n, e := shaped.Write(original)
		if e == nil && n != len(original) {
			e = io.ErrShortWrite
		}
		done <- e
	}()
	b.SetReadDeadline(time.Now().Add(time.Second))
	var collected []byte
	records := 0
	for len(collected) < len(payload) {
		header := make([]byte, 5)
		if _, e := io.ReadFull(b, header); e != nil {
			t.Fatal(e)
		}
		if header[0] != 22 || header[1] != 3 || header[2] != 1 {
			t.Fatal("record type/version changed")
		}
		n := int(binary.BigEndian.Uint16(header[3:]))
		if n > 128 {
			t.Fatal("record not split")
		}
		chunk := make([]byte, n)
		if _, e := io.ReadFull(b, chunk); e != nil {
			t.Fatal(e)
		}
		collected = append(collected, chunk...)
		records++
	}
	if !bytes.Equal(payload, collected) || records < 2 {
		t.Fatal("TLS handshake transcript changed")
	}
	tail := make([]byte, len(suffix))
	if _, e := io.ReadFull(b, tail); e != nil || !bytes.Equal(tail, suffix) {
		t.Fatal("following TLS record changed")
	}
	if e := <-done; e != nil {
		t.Fatal(e)
	}
}

func TestRegistrationBodyHasJSONMediaType(t *testing.T){
 req,_:=http.NewRequest(http.MethodPost,"https://example.invalid/reg",nil)
 setEnrolmentHeaders(req,"")
 if req.Header.Get("Content-Type")!="application/json; charset=UTF-8"{t.Fatal("registration JSON media type missing")}
}
func TestLiveConsumerEnrolmentOnPhysicalBridge(t *testing.T){
 if os.Getenv("COLGRAM_LIVE_WARP")!="1"{t.Skip("explicit opt-in live consumer endpoint test")}
 ctx,cancel:=context.WithTimeout(context.Background(),20*time.Second);defer cancel()
 ip,cert,e:=enrolFreshContext(ctx)
 if e!=nil{t.Fatal(e)}
 if ip==nil||len(cert.Certificate)==0{t.Fatal("missing tunnel identity")}
 t.Log("LIVE_ENROLMENT=PASS; valid_ip=true certificate=true; credentials_not_logged=true")
}

func TestLiveConsumerCarrierOnPhysicalBridge(t *testing.T) {
 if os.Getenv("COLGRAM_LIVE_WARP")!="1"{t.Skip("explicit opt-in live carrier test")}
 ctx,cancel:=context.WithTimeout(context.Background(),38*time.Second);defer cancel()
 src,cert,e:=enrolFreshContext(ctx);if e!=nil{t.Fatal(e)}
 carrier,stopCarrier:=context.WithTimeout(ctx,16*time.Second);defer stopCarrier()
 udp,e:=listenProtectedUDP("udp4",&net.UDPAddr{});if e!=nil{t.Fatal(e)};defer udp.Close()
 qctx,qcancel:=context.WithTimeout(carrier,5*time.Second)
 q,qerr:=dialQuic(qctx,udp,net.JoinHostPort(edgeIP,"443"),cert);qcancel()
 var tun *tunnel
 if qerr!=nil {
  udp.Close();t.Log("QUIC unavailable; checking pinned H2 carrier")
  stream,e:=openH2Session(carrier,cert,net.JoinHostPort(edgeIP,"443"));if e!=nil{t.Fatal("H2 carrier: ",e)}
  tun=&tunnel{srcIP:src,stream:stream}
 }else{
  h3:=(&http3.Transport{EnableDatagrams:true,AdditionalSettings:map[uint64]uint64{0x8:1}}).NewClientConn(q)
  stream,e:=openConnect(carrier,h3);if e!=nil{q.CloseWithError(0,"test ended");t.Fatal(e)}
  tun=&tunnel{srcIP:src,conn:q,h3:h3,stream:&h3Stream{s:stream}}
 }
 defer tun.close();tt:=&traceTunnel{ctx:ctx,cancel:cancel,tun:tun};go tt.pump()
 ips,e:=resolveContext(ctx,traceHost);if e!=nil{t.Fatal(e)};var dst net.IP
 for _,ip:=range ips{if ip.To4()!=nil{dst=ip.To4();break}};if dst==nil{t.Fatal("no IPv4 trace address")}
 traceCtx,traceCancel:=context.WithTimeout(ctx,12*time.Second);defer traceCancel()
 body,e:=tlsTraceThroughTunnelContext(traceCtx,tun,src,dst)
 if e!=nil{t.Fatal("encrypted trace: ",e)};if !WarpOn(body){t.Fatal("verified trace did not report warp=on")}
 t.Log("LIVE_WARP_CARRIER=PASS encrypted_trace=true warp=on external_proxy=false")
}
