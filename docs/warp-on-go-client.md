# warp=on from a self-contained Go client, on host and device

## The client

tools/warpgo/warpverdict/main.go is a standalone MASQUE client. It registers with v0a4471, enrols a
P-256 key, builds a bare self-signed certificate, opens an extended CONNECT to the MASQUE edge,
then runs an ordinary Go TLS client on top of a Connect-IP net.Conn and requests /cdn-cgi/trace.

It carries no dependency on a TUN device, needs no root, and changes no host setting. The only
socket it opens is one UDP socket to the edge.

## Measured on the host

Four consecutive runs, each a fresh registration and a fresh tunnel:

    session address : 172.16.0.2
    CONNECT status  : 200 OK
    trace target    : connectivity.cloudflareclient.com 162.159.137.65
    tcp handshake   : established through the tunnel
    tls handshake   : complete
    request         : GET /cdn-cgi/trace
    status          : HTTP/1.1 200 OK
      warp   = on
      ip     = 104.28.244.74
      loc    = RU
      colo   = FRA
      kex    = X25519MLKEM768
      tls    = TLSv1.3
      http   = http/1.1
      sni    = plaintext

    VERDICT: warp=on -- the request travelled through the WARP tunnel

4 of 4. warp=on is Cloudflare's own line, on a request that left through the tunnel. Nothing here
is inferred from a status code or a packet capture.

## What was wrong before, and what each defect cost

Five client-side defects, each producing a plausible-looking failure. None of them was the network,
and each reads as "the tunnel does not come up".

-   the extended CONNECT took :protocol from a header instead of Request.Proto, and quic-go
    rejects a pseudo-header set that way. The tunnel never opened.
-   a 16-bit write over the segment's flags field overwrote the data offset written one line
    earlier, so the TCP header on the wire announced zero words of header. The edge dropped every
    segment silently and the client reported only "no recent network activity".
-   the peer lookup compared source against destination port, so inbound packets found no flow.
-   a retransmitted SYN carried an incremented sequence number instead of repeating the original,
    which reads as two different connections and is answered with RST.
-   RST was acknowledged instead of ending the flow, and the far side's repeated SYN was answered
    with another SYN. Both reopen the stream mid-handshake.

The SYN retransmission one is worth keeping: it is the defect that made the device's own reference
client report "timeout: no recent network activity" while a 1200-byte probe to the same edge drew a
Retry in 100 ms. The symptom was identical to a filtered path, and the cause was in the client.

## Capsules are the short form

Connect-IP on this edge is a single 0x00 type byte followed by the whole IP packet. The generalised
capsule carrying a context id and an explicit length is accepted nowhere on this path: it was
tried, and every packet sent in that form was dropped without a reply. The short form is what
returns traffic.

## The device leg

The client is cross-compiled and runs on the device:

    GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o warpverdict-arm64 .
    adb push warpverdict-arm64 /data/local/tmp/uq/wv
    cd /data/local/tmp/uq && WARP_BIND=10.0.2.15 ./wv

On the device it registers and enrols successfully, and then:

    session address : 172.16.0.2
    error           : quic dial: timeout: no recent network activity

The capture shows 1200-byte Initials leaving wlan0 and no inbound packet at all - ten packets sent,
zero received. UDP itself is fine from the device: a DNS query to 1.1.1.1 answers in 99 ms and to
8.8.8.8 in 111 ms, and ICMP to the edge answers in 1.8 ms. The device's networking is not the
obstacle. The same binary on the host completes from the same edge, and the same registration and
enrolment succeed on the device.

What is unresolved is why the edge does not answer this device's QUIC Initial when it answers the
host's.

## Where this stands

| Leg | Result |
|---|---|
| protocol, host | warp=on, 4 of 4 runs |
| protocol, device | registration and enrolment succeed; QUIC Initial unanswered |
| capsule format | short form, measured |
| host networking | untouched - WARP service Stopped, no WARP adapter, no route or DNS change |

The tunnel works. What is not yet true is that it works from the phone, and the goal is not met
until the trace reads warp=on from the device.
