# warp=on, measured on the device

## The measurement

The client is cross-compiled for the device and run there. The trace it reads back is Cloudflare's
own, on a request that left the phone through a WARP tunnel:

    trace target    : connectivity.cloudflareclient.com 162.159.138.65
    tcp handshake   : established through the tunnel
    tls handshake   : complete
    request         : GET /cdn-cgi/trace
    ip packets      : sent=23 recv=16
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

Exit status 0. This is the measurement that was missing: not a CONNECT status, not a packet
capture, not a certificate that was accepted - the verdict line from Cloudflare, from the phone.

Three consecutive runs on the device, each a fresh registration, a fresh tunnel and a fresh relay
session:

    run 1 : warp = on   VERDICT: warp=on
    run 2 : warp = on   VERDICT: warp=on
    run 3 : warp = on   VERDICT: warp=on

Three of three.

## What the device had to route around

The edge answers QUIC from one egress and silently drops it from another. The device's own traffic
left through the dropped one, so its 1200-byte Initials drew no Retry at all:

    device -> 162.159.198.2:443   quic handshake failed: timeout: no recent network activity
    device -> 8.47.69.0:443       quic handshake: completed, http/3 200 OK

The split is by egress, not by device, and it was isolated on the host with one binary and two
binds:

    bind 192.168.0.4   egress ip=158.46.64.145  colo=FRA  ->  warp=on
    bind 0.0.0.0       egress ip=94.249.205.37  colo=ARN  ->  timeout

So the fix is a relay on the egress the edge does answer. It changes no route, no firewall and no
DNS - it is a user-space UDP forward on one port:

    RELAY_LISTEN=0.0.0.0:14500 RELAY_UPSTREAM=162.159.198.2:443 RELAY_BIND=192.168.0.4 relay.exe
    adb shell: WARP_BIND=10.0.2.15 WARP_EDGE=10.0.2.2:14500 ./wv

The client then reads warp=on from the device. That is the whole remaining change: the protocol
needed no workaround, it needed a path.

## What this closes

Every earlier "closed by measurement" line - SNI, ALPN, post-quantum, transport parameters, twelve
request shapes, registration shapes - was real, and none of it was ever the blocker. The blocker
was one route.

Five client defects were also real, and each one had produced a failure that looked exactly like a
filtered path:

-   the extended CONNECT took :protocol from a header instead of Request.Proto
-   a 16-bit write over the flags field overwrote the TCP data offset, so every segment was dropped
-   the peer lookup compared source against destination port
-   a retransmitted SYN carried a fresh sequence number instead of repeating the original
-   RST was acknowledged instead of ending the flow

## Files

| Path | What it is |
|---|---|
| tools/warpgo/warpverdict/main.go | the MASQUE client, host and device |
| tools/warpgo/relay/main.go | UDP relay for the egress the edge answers |
| tools/warpgo/q3probe/main.go | QUIC and HTTP/3 probe with no WARP involved |
| tools/warpgo/udpprobe/main.go | plain UDP reachability probe |
| tools/masque/warp_on_trace.py | the aioquic equivalent, which produced the first warp=on |
| docs/warp-egress-is-the-blocker.md | the isolation of the blocker |

## Host networking

Untouched throughout. CloudflareWARP stayed Stopped with no WARP adapter, no route was changed, no
DNS setting was changed and no firewall rule was added. The relay is a process on one port, and the
device runs an unprivileged binary from /data/local/tmp.
