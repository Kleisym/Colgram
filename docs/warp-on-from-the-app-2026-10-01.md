# warp=on, read from inside the app on the device — 2026-10-01

The goal this file closes. Read from the phone, over the tunnel, over the SOCKS5 front, on a
network whose own UDP is filtered.

```
I ColgramMasqueVpn(12925): verdict through the tunnel: warp=on | HTTP/1.1 200 OK
fl=931f3 h=connectivity.cloudflareclient.com ip=104.28.244.74 ts=1790871115.000 visit_scheme=https
    uag=colgram-warp-on colo=FRA sliver=none http=http/1.1 loc=RU tls=TLSv1.3 sni=plaintext
    warp=on gateway=off rbi=off kex=X25519MLKEM768
```

Every field is the edge describing the request it just served:

- `warp=on` -- the request egressed through Cloudflare WARP
- `ip=104.28.244.74` -- the address the edge saw, which is not the device's own `10.0.2.15`
- `colo=FRA`, `loc=RU` -- served from Frankfurt, seen as Russian traffic
- `tls=TLSv1.3 kex=X25519MLKEM768` -- post-quantum handshake, client certificate from the P-256
  registration
- `sni=plaintext` -- the inner request to Cloudflare's own endpoint, so this is not an inspection
  artefact

The device: MuMuPlayer, x86_64, `127.0.0.1:16384`, guest `10.0.2.15` behind NAT `10.0.2.2`.
Direct UDP to the edge is filtered on every port tried; TCP 443 and UDP 53 both answer.

```
tun0: TX 23 packets, RX 9 packets
up 3492 pkts / 375472 bytes   down 3523 pkts / 261036 bytes   sessions 1
```

## What the whole path is

```
app traffic -> tun0 -> Java pump -> JNI -> Go MASQUE client
           -> Connect-IP capsule -> QUIC over the SOCKS5 front (TCP)
           -> host bridge -> edge 162.159.198.2:443
           -> Connect-IP capsule back -> RX (whole IP packet) -> tun0
```

The front exists because the device's UDP is filtered. It is a SOCKS5 UDP ASSOCIATE for the tunnel's
own QUIC and a SOCKS5 CONNECT for everything else -- the DoH lookups that find the API address, the
enrolment POST/PATCH, and the TLS session that reads the verdict.

## The last three defects

### 11. The pump wrote TCP payload where an IP packet belongs

`colgram_masque_exchange` returned `inbuf` -- the TCP payload after `feed()` strips the headers. The
Java pump writes that straight into the tun descriptor, and the kernel reads a TCP flag byte as an IP
version and drops it. The interface stayed silent no matter how much traffic arrived:

    in=2 dropped=0 bytes=88

capsules arriving, none discarded, rx at 0. `feed()` now queues whole packets in a bounded `pending`
queue and `takeIPPacket()` hands the pump one of those. `rx` went from 0 to 9.

### 12. `openTrace` ran on every probe

Each call appended a peer and took a fresh source port, so the peer list grew per attempt and the
request raced the flow that was about to replace it. The answer belonged to a connection nobody was
reading. The flow is opened once now, and the read buffer is drained before each request so a stale
response cannot be returned as this one's.

### 13. The trace request was sent in plaintext

The trace endpoint is HTTPS. A plaintext GET to port 443 is answered by the edge with

    HTTP/1.1 400 Bad Request

a refusal, not a trace -- and reading its body anyway produced a confident `warp=off` for a tunnel
that was carrying traffic perfectly. The measurement path has wrapped `tls.Client` over the tunnel since
it was written, which is why it worked there and not here. The tunnel now does the same.

That one is worth naming beyond WARP: a protocol error that returns a valid-looking HTTP response
reads as a verdict, and without the body in the log there is no way to tell a refusal from an answer.
The verdict line now prints the whole trace either way.

## Where the verdict comes from

`ColgramMasqueNative.trace()` opens a second flow to `connectivity.cloudflareclient.com` inside the
same tunnel, runs TLS over it, and reads `/cdn-cgi/trace`. A second flow because `dispatch` matches
packets to flows by port pair and one flow can only reach one destination.

This is read on the device, over the device's tunnel, through the same SOCKS5 front that carries the
app's own traffic. Nothing about it is measured on the host and inferred.

## Checkers

`check_manifest_components.py`, `check_jni_symbols.py`, and the architecture comparison in
`check_masque_build.py` all fail on the defects in this series when reintroduced. Full suite green:

    [ok] reflection in colgram-core
    [ok] reflection in the UI files
    [ok] auto-reply send path
    [ok] packaged masque library
    [ok] brand strings
    [ok] manifest components exist in dex
    [ok] native methods have JNI entry points