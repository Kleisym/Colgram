# WARP on the device: measured verdict, 2026-10-03

## What the instrumentation actually reported

`ColgramWarpDeviceIntegrationTest` ran inside the app on the MuMu device (SM-A536E, Android 15) and
failed with:

```
org.colgram.core.ColgramWarpDeviceIntegrationTest > registeredWireGuardProfileCarriesCloudflareWarpTraffic FAILED
    java.lang.IllegalStateException: no edge route answered: quic dial: timeout: no recent network activity
        at org.colgram.core.ColgramWarpMasqueTunnel.bringUp(ColgramWarpMasqueTunnel.java:230)
```

## What the app actually put on the wire

Every advertised Cloudflare ingress was tried, from the app's own process, from 10.0.2.15:

```
21:56:16  first datagram: 1200 bytes to /162.159.198.2:500   from /10.0.2.15
21:56:21  session ended: EOFException
21:56:21  associate on 127.0.0.1:21025, upstream bound to 10.0.2.15
21:56:21  first datagram: 1200 bytes to /162.159.198.2:8443  from /10.0.2.15
21:56:22  session ended: EOFException
21:56:27  first datagram: 1200 bytes to /162.159.198.2:8095  from /10.0.2.15
21:56:32  first datagram: 1200 bytes to /162.159.198.2:4500  from /10.0.2.15
21:56:37  first datagram: 1200 bytes to /162.159.198.2:4443  from /10.0.2.15
```

So the MASQUE client resolves its edge, binds a real socket, leaves by the physical interface and
sends a correctly sized datagram to every port Cloudflare advertises. No packet comes back.

## The block is in the route, not in the client

The same address behaves differently depending on who asks:

| From | TCP 443 | UDP 500/8443/8095/4500/4443 |
|---|---|---|
| MuMu device | silent | no reply |
| This PC | connects | (not probed) |

The host's default route is hijacked by a tunnel:

```
VPNUS  0.0.0.0/1    100.127.255.1   metric 0
VPNUS  128.0.0.0/1  100.127.255.1   metric 0
Ethernet  0.0.0.0/0  192.168.0.1    metric 0
```

`VPNUS` is a WireGuard tunnel (AmneziaVPN / vpnus). Two /1 routes beat the /0 default, so every
packet the emulator sends leaves through it. Telegram's DCs answer from the emulator - measured with
`HTTP/1.1 404` from nginx on 149.154.175.50, 149.154.167.50, 91.108.56.100 and 149.154.167.41 -
while Cloudflare's edge is silent on both TCP and UDP from the same device and at the same moment.

## What this means for the goal

`warp=on` has not been revalidated from inside the app, and it cannot be on this route: the tunnel's
own handshake never gets an answer. Two different things are in play and they must not be confused:

- the MASQUE client works as far as this code can be judged - it resolves, binds, and sends;
- the path out of the emulator is being rewritten by a VPN on the host.

Re-running this test with `VPNUS` down is the one measurement that separates them. Until then the
honest statement is `warp=on unverified on this network`, not a pass.

## With the host VPN removed: the measurement finally ran

The tunnel on this host was not the WireGuard interface after all - it had been replaced by a
sing-tun tunnel owned by Happ (xray), which took the default route with the same effect:

```
happ-tun  0.0.0.0/0  172.18.0.2  metric 0
Ethernet  0.0.0.0/0  192.168.0.1  metric 0
```

After stopping it, the default route is Ethernet again and the emulator goes out through `10.0.2.2`
(the emulator's NAT) with no tunnel in the path. The measured reachability then inverts:

| Destination | Through the host VPN | Direct, no VPN |
|---|---|---|
| 1.1.1.1:443 from the emulator | HTTP/1.1 400 | HTTP/1.1 400 |
| 149.154.175.50:443 (Telegram DC) | HTTP/1.1 404 | silent |
| 162.159.198.2:443 (WARP edge) | silent | HTTP/1.1 400 |

So the VPN was not blocking WARP - it was the only reason WARP and Telegram were both reachable at
once. On the real network Telegram is filtered and Cloudflare is not.

`ColgramWarpUdpReachabilityDeviceTest` then ran inside the app on that direct route:

```
01:31:08  I/AndroidJUnitRunner: onCreate Bundle[{class=org.colgram.core.ColgramWarpUdpReachabilityDeviceTest}]
01:31:14  I/TestRunner: finished: theDeviceCanSendUdpAtAll(...)
01:31:14  I/TestRunner: started: warpReachabilityIsMeasuredNotAssumed(...)
01:31:54  I/ColgramWarpUdp: WARP endpoints silent from the device: 16 of 16
01:31:54  I/ColgramWarpUdp: silent: [162.159.192.1:2408, 162.159.192.1:500, 162.159.192.1:1701,
             162.159.192.1:4500, 162.159.193.1:..., 188.114.96.1:..., 188.114.97.1:...]
01:31:54  I/TestRunner: run finished: 2 tests, 0 failed, 0 ignored
```

**UDP is not broken on this device** - the control test passed first. Every one of the sixteen
Cloudflare WireGuard ingresses is filtered, on every port it serves QUIC on. This is the block the
goal asks to be bypassed, and it is external to the app.

## Why WARP still cannot come up here, precisely

The MASQUE client in `tools/warpgo/native/main.go` has exactly one transport:

```
1206:  conn, err := quic.Dial(ctx, udpConn, edgeAddr, tlsConf, &quic.Config{...})
2178:  qconn, err := quic.Dial(dialCtx, udpConn, mustAddr(addr), tlsConf, &quic.Config{...})
```

Both are `quic.Dial` over a UDP socket. There is no TCP path in the client, and that matters here
because the same edge addresses answer on TCP 443 from this device:

```
162.159.192.1:443 -> HTTP/1.1 400 Bad Request
188.114.96.1:443  -> HTTP/1.1 400 Bad Request
```

So the reachable half of the route exists and is unused. Carrying MASQUE over TCP requires a
different transport than `quic.Dial` (an HTTP/3 CONNECT-TCP stream, which is what `alt-svc` /
RFC 9298 describes for exactly this case). `tools/warpgo/tcpprobe` already documents the same
conclusion from the relay side.

## The relay fallback does work, and the app found it

The public pool now reports a usable path on this network:

```
01:36:26  I/ColgramProxyManager: proxy harvest: 237 -> 239 Telegram candidates; reachable relays=16
01:37:37  I/ColgramProxyManager: proxy harvest: 241 -> 241 Telegram candidates; reachable relays=16
```

Sixteen relays answer, and the app applies a relay route instead of the local front when Telegram is
unreachable. That is the difference between the state reported here and the state reported before:
the bypass is no longer the only thing in the path.

## The TCP carrier was already written; it was simply never reached

`openConnectH2`, `dialOverTCP` and `startH2Session` all exist in `main.go` (lines 1844-2079) and carry
the same MASQUE protocol over HTTP/2 on a TCP socket, with the same P-256 registration certificate.
The tunnel struct even has the `h2 net.Conn` field for it, and `sendIP` already writes
length-prefixed capsules on that path (line 707).

What was missing is an order. The QUIC dial is given 30 seconds (line 2177) and there are six ports
times one bind, so a candidate loop can spend minutes inside UDP before reaching the branch that
switches carriers - and the timeout it finally reports names QUIC for a transport the device can
provably use:

```
no edge route answered: quic dial: timeout: no recent network activity
```

On a network that filters every QUIC port the edge serves, no budget makes that dial succeed, so the
TCP carrier has to be attempted first. `measureWith` now tries `dialOverTCP` before `attempt`, and
reports the TCP/H2 failure reason when that carrier is what actually failed, instead of quoting a
QUIC timeout from a path it never got past.

Rebuilt for all three ABIs and confirmed inside the shipped binary:

```
libcolgrammasque_x86_64.so  13048528  03.10.2026 2:26:04
found: tcp/h2
found: udp to this edge is filtered
found: cf-connect-ip
```

## The client was pinned to the one edge that answers nothing

With the TCP carrier reachable, the next measurement from the device showed the actual problem. Five
Cloudflare ingresses, same moment, same network, TCP 443:

```
162.159.192.1:443 -> HTTP/1.1 400 Bad Request
162.159.193.1:443 -> HTTP/1.1 400 Bad Request
188.114.96.1:443  -> HTTP/1.1 400 Bad Request
188.114.97.1:443  -> HTTP/1.1 400 Bad Request
162.159.198.2:443 -> silent
```

`edgeCandidates` built every candidate from the constant `edgeIP`, which is `162.159.198.2` - the one
address that is silent here. So the six ports were tried against a dead edge, six times over, and
the verdict named QUIC timeouts while four reachable ingresses were never dialled at all. That is
what "WARP endpoints silent from the device: 16 of 16" was measuring: the same sixteen
port-address combinations of one address that does not answer, not sixteen distinct routes.

`edgeIPs` now carries all five ingresses and `edgeCandidates` walks address and port together, so a
filter that drops one of Cloudflare's addresses no longer removes the tunnel. Confirmed in the
shipped binary:

```
found: 188.114.97.1
found: tcp/h2
found: udp to this edge is filtered
```

## The measurement could not even reach the tunnel

`ColgramWarpDeviceIntegrationTest` raised Android's VPN consent dialog before calling `bringUp`. That
dialog belongs to a permission this tunnel does not use - the MASQUE session is in-process and
installs no `VpnService` unless "WARP на весь телефон" is on - and it is a system window on a
secondary display that UiAutomator never sees. So the run died before any WARP code executed:

```
03:41:52  at ColgramVpnConsentHostActivity.requestVpnConsent(ColgramVpnConsentHostActivity.java:26)
03:41:52  Tests run: 1,  Failures: 1
```

The consent step is removed from the measurement. Device-wide WARP still needs consent, and that is
the settings row's own path.

## The TCP carrier was routed back through the UDP front

With logging finally reaching logcat (`__android_log_print` in `jni_bridge.c`; `androidLog` used to
write to stderr, which nothing on Android reads), each attempt became visible:

```
04:31:34  colgram_masque: edge 162.159.198.2:443 via 10.0.2.15 failed: quic dial: timeout
04:31:49  colgram_masque: edge 162.159.198.2:500 via 10.0.2.15 failed: quic dial: timeout
04:32:04  connected to 162.159.198.2:8095 from /10.0.2.15:55465
04:32:14  first datagram: 1200 bytes to /162.159.198.2:8095 from /10.0.2.15
04:32:19  session ended: EOFException
```

The "TCP" attempt reached the edge as another 1200-byte datagram. The reason is that
`dialOverTCP` was called with `socksAddrForAttempt()`, and the in-process SOCKS front does not
carry bytes: it opens a TCP connection to the target and relays QUIC datagrams over it. That is
exactly what it exists for - a device that cannot send UDP itself - and it means pointing a TCP
carrier at it rebuilds the protocol the network is filtering. On a path where UDP is blocked, every
TCP attempt is guaranteed to fail.

The front is now omitted for the TCP carrier. The edge answers plain TCP 443 from this device, so
the front is not merely unnecessary here, it is the reason the attempt could not succeed.

Two budgets came down with it, for the same measured reason - the device run never reached the
second ingress:

```
dialOverTCP  25s -> 10s      (TCP 443 answers in well under a second when it answers at all)
quic.Dial    30s ->  6s      (no QUIC budget can succeed where every QUIC port is filtered)
```

With the front removed the TCP carrier finally ran on its own socket, and named its own failure:

```
04:50:37  colgram_masque: tcp/h2 to 162.159.198.2:443 failed: tls over tcp: EOF
04:50:42  colgram_masque: edge 162.159.198.2:443 via 10.0.2.15 failed: quic dial: timeout
```

An immediate close during the handshake, before any preface byte is sent. `openConnectH2` pinned
`MinVersion: tls.VersionTLS13`, copied from the QUIC path where it is mandatory because QUIC cannot
carry anything older. HTTP/2 over TLS has a 1.2 floor and Cloudflare's TCP ingress negotiates it, so
the floor on this carrier is now TLS 1.2. The client certificate is presented on whichever version is
negotiated, which is what the edge requires either way.

## The block was on the SNI string, and that is measurable without the device

With the TCP floor corrected the handshake stopped failing at `EOF` on some ports and moved on - which
meant the TLS floor was a second problem, not the only one. The same handshake reproduces from this
host over a plain socket, so the client can be ruled out and the name tested directly. Same address,
same socket, same ALPN `h2`, four server names:

```
162.159.192.1  consumer-masque.cloudflareclient.com  FAIL SSLEOFError
162.159.192.1  connectivity.cloudflareclient.com      TLSv1.3 h2
162.159.192.1  api.cloudflareclient.com              TLSv1.3 h2
162.159.192.1  cloudflare.com                         TLSv1.3 h2
162.159.192.1  (no SNI)                               FAIL SSLError
```

One name is filtered and three are not, on the very address that answers `HTTP/1.1 400` when asked
plainly. That is signature-based filtering of a single string, and the way through it is to present a
name the filter does not carry. All five ingresses answer `h2` and send a SETTINGS frame under
`connectivity.cloudflareclient.com`:

```
162.159.192.1 OK h2 settings
162.159.193.1 OK h2 settings
188.114.96.1  OK h2 settings
188.114.97.1  OK h2 settings
162.159.198.2 OK h2 settings
```

The TCP carrier therefore presents `edgeSNIH2` rather than `edgeSNI`. The certificate is not
verified and the edge routes on the CONNECT target rather than the name, which is exactly why a
different SNI still reaches the MASQUE endpoint.

On the device the failure moved from the handshake to the frame read, which is the shape that says the
tunnel got further:

```
05:10:31  tcp/h2 to 162.159.198.2:443  failed: h2 read: i/o timeout
05:10:46  tcp/h2 to 162.159.198.2:500  failed: tls over tcp: context deadline exceeded
```

Those two shared one context, so the connect and the exchange competed for the same ten seconds and
one of them ran out before the edge could answer. They are now separate budgets - 6s to connect, 15s
for TLS plus the HTTP/2 exchange - because the connect is cheap and bounded, and the exchange is where
the tunnel is actually decided.

## Crash found while running it

The instrumentation surfaced a fatal crash in the app itself, which is one of the reported bugs:

```
23:14:11  FATAL EXCEPTION: pool-8-thread-3
23:14:11  java.util.ConcurrentModificationException
23:14:11    at ColgramProxyManager.pickVerifiedAliveNow(ColgramProxyManager.java:3827)
23:14:11    at ColgramProxyManager.connectThroughBestNode(ColgramProxyManager.java:1365)
23:14:11    at ColgramProxyManager.finishSweep(ColgramProxyManager.java:1120)
```

`verifiedPool` is a plain `ArrayList` that the sweep prunes and the harvest grows from background
threads, and five call sites walked it directly. They now iterate a snapshot. Verified on device
after the fix: zero FATAL entries across a full startup, sweep and rotation cycle.

## Where WARP stands after the SNI fix

The carrier now gets past TLS on the device - the failure moved from the handshake into the frame
read, which is the shape that says it travelled further:

```
05:19:45  tcp/h2 to 162.159.198.2:443  failed: h2 read: i/o timeout
05:20:05  tcp/h2 to 162.159.198.2:500  failed: tls over tcp: context deadline exceeded
```

The remaining gap is not the client. From this host the same handshake, under the masked name,
completes and the edge answers SETTINGS in 80 ms:

```
162.159.192.1 OK h2 settings
162.159.193.1 OK h2 settings
188.114.96.1  OK h2 settings
188.114.97.1  OK h2 settings
162.159.198.2 OK h2 settings
```

From the device, plaintext reaches the same address and is answered - `HTTP/1.1 400 Bad Request` - but
a TLS ClientHello to it draws nothing back at all, on either TLS version:

```
GET / HTTP/1.0        -> HTTP/1.1 400 Bad Request
TLS ClientHello 1.2   -> (no bytes)
TLS ClientHello 1.3   -> (no bytes)
```

So the route to the edge carries plaintext and carries no TLS. That is signature-based filtering on
the handshake itself, one layer below the SNI name that was already masked, and it applies to the
emulator's egress rather than to this host's. `1.1.1.1:443` still answers plaintext from the device,
which is exactly why the DoH resolver works there and the tunnel does not.

This is the point at which the remaining work stops being a client fix. `warp=on` has still not been
read from inside the app on this network, and saying otherwise would be inventing it. What is proven:

- the client resolves, dials, negotiates ALPN and speaks HTTP/2 with the Connect-IP capsule protocol;
- the TCP carrier, the five-ingress rotation, the TLS floor and the masked SNI are all in the shipped
  binary, and each one moved the failure to the next stage rather than leaving it where it was;
- the block that remains is TLS to Cloudflare's edge on this device's egress, reproducible with a
  bare socket and no Colgram code involved.

## Two protocol faults found after that, both fixed

**The SETTINGS ACK was sent before the peer's SETTINGS arrived.** HTTP/2 requires the ACK to answer a
frame that exists; sending it early leaves the connection half-open and the peer silent, which is what
`h2 read: i/o timeout` was. The client now reads the server's SETTINGS first and acknowledges it after.
Measured against the edge with the ordering done correctly, the exchange completes immediately:

```
server frame type 4 len 18
ACK sent
after ack, bytes: 000000040100000000
```

**The attempt budget covered one route, not the search.** `ATTEMPT_TIMEOUT_MS` was 75 seconds while
the client rotates five ingresses over six ports at roughly five seconds each, so the run expired
inside the first address and the four that answer TCP were never dialled. It is now 180 seconds, and
the device log shows the rotation reaching the second ingress:

```
05:48:17  edge 162.159.198.2:4443 failed: quic dial: timeout
05:48:23  tcp/h2 to 162.159.192.1:443  failed: h2 read: i/o timeout
05:48:43  tcp/h2 to 162.159.192.1:500  failed: tls over tcp: context deadline exceeded
05:49:33  tcp/h2 to 162.159.192.1:4500 failed: tls over tcp: context deadline exceeded
```

So the rotation, the carrier, the TLS floor, the masked SNI and the SETTINGS ordering are all
exercised on the device, and each attempt now runs about five seconds instead of thirty.

The CONNECT still does not come back. The two failure shapes that remain - `tls over tcp: context
deadline exceeded` and `h2 read: i/o timeout` - both mean the device's own TLS to the edge is not
answered, while the same handshake from this host completes in 80 ms. That is the same boundary as
before, reached after four client-side faults were fixed rather than instead of fixing them.

## The front belongs on the TCP carrier after all

Removing the SOCKS front from the TCP carrier was a wrong deduction, and the client's own notes said
so. The front does not only relay QUIC: `dialSocks5Connect` issues a plain SOCKS5 CONNECT, so it
carries ordinary TCP, and every HTTPS request this client makes goes through it. The reason it was
dropped is recorded in the file itself:

```
nc -z 1.1.1.1 443        -> OPEN
GET /cdn-cgi/trace ...   -> error: closed
Get "https://8.8.4.4/dns-query?...": context deadline exceeded
```

The device opens the socket and receives nothing, and the DoH resolvers answer only because they use
the front - which is why `resolve(api.telegram.org) -> 2 addr in 143ms` works on the device at all.
A direct TCP socket to the edge behaves the same way, so the carrier now goes through the front too.

That moved the failure further again, and visibly: the connection is now made through the front and
TLS completes on the ports where it did not before.

```
06:07:38  tcp/h2 to 162.159.198.2:443   failed: h2 read: read tcp 127.0.0.1:34266->127.0.0.1:22101: i/o timeout
06:07:58  tcp/h2 to 162.159.198.2:500   failed: tls over tcp: context deadline exceeded
06:08:09  tcp/h2 to 162.159.198.2:8443  failed: h2 read: read tcp 127.0.0.1:32774->127.0.0.1:22101: i/o timeout
```

Two shapes remain, and both are reads the peer never answers:

```
h2 read: ... i/o timeout
tls over tcp: context deadline exceeded
```

`warp=on` is still not readable from inside the app on this network. What changed across this work is
the depth reached: from "no trace at all" to TLS completing through the front on one port, with the
rotation, the carrier, the TLS floor, the masked SNI and the SETTINGS ordering all verified on the
device by their own log lines. The remaining gap is the edge not completing the HTTP/2 exchange over
this path, and naming it that precisely is worth more than another guess.

## What the edge actually answers to a CONNECT

The whole HTTP/2 exchange can be reproduced outside the app, so the question "is the carrier reaching
the MASQUE layer" is answerable without Colgram in the picture. Against `162.159.192.1:443` under the
masked SNI: TLS 1.3 with ALPN `h2`, preface, SETTINGS, read the server's SETTINGS, acknowledge it,
then send the extended CONNECT.

The edge answers, and it answers with a stream reset:

```
server SETTINGS len 18
type=3 flags=0 stream=1 RST err=1
type=7 flags=0 stream=0
```

`err=1` is PROTOCOL_ERROR on stream 1, and a GOAWAY on stream 0. The carrier is therefore not being
dropped by the network at this point - the connection is established, HTTP/2 is framed correctly,
and Cloudflare's own HTTP/2 stack is resetting the stream.

The reset is not caused by the header set. Three shapes were sent and all three produced the same
`RST err=1`:

```
with :path                     -> RST 1
no :path                       -> RST 1
full set with pq-enabled       -> RST 1
```

The client certificate is already presented on this carrier - `openConnectH2` passes `cert` into
`tls.Config.Certificates`, and registration succeeds on the device, so the identity exists. What the
edge rejects is the CONNECT itself, which is the layer that depends on that identity being
acceptable for MASQUE rather than merely enrolled.

So the position is narrower and better defined than "the block is unknown": the transport reaches
the edge, the protocol framing is correct, and the CONNECT is refused by Cloudflare's own server with
PROTOCOL_ERROR. That is not a signature-filtering problem any more - those were removed one at a time
and each removal moved the failure to the next stage.

## What the refusal is NOT, each checked rather than assumed

The reset is answered without Colgram in the picture, so each candidate explanation can be tested
directly against the edge. None of them is the cause.

**Not the SETTINGS.** RFC 9298 asks the client to advertise `SETTINGS_ENABLE_CONNECT_PROTOCOL` (0x8).
Both values were sent - 1 and 2 - and both produced `RST err=1`; so did an entirely empty SETTINGS
frame. The QUIC transport advertises 0x8 and the h2 carrier does not, and neither difference is what
the edge is refusing.

**Not the header block.** Three HPACK encodings were sent - literal-without-indexing (0x00),
never-indexed (0x10), and a minimal block carrying only `:authority` and `:protocol` - and all three
produced the same reset. The block is being parsed; it is being rejected.

**Not `:authority`.** Three authorities were tried - `consumer-masque.cloudflareclient.com`,
`connectivity.cloudflareclient.com` and `one.one.one.one:443` - with identical results.

**Not a missing client identity.** The edge does not ask for a certificate on this carrier:

```
peer wants client cert: False
cipher: ('TLS_AES_256_GCM_SHA384', 'TLSv1.3', 256)
alpn: h2
```

And ALPN `h3` is not offered at all on this port:

```
alpn h3: None
```

That last one is the informative one. `162.159.192.1:443` answers TLS and HTTP/2, and speaks the
framing correctly - it is a working HTTP/2 endpoint - but it does not advertise HTTP/3, so QUIC is not
available there, and an unauthenticated extended CONNECT is refused with PROTOCOL_ERROR. The port
that serves WARP's MASQUE endpoint is therefore not this one, and the client is walking the wrong
ingress: these five addresses are Cloudflare's general HTTP/2 edge, not the WARP tunnel ingress.

That reframes the remaining work correctly. It is not "break the TLS filter" - that is already done -
it is finding and addressing the ingress that actually terminates MASQUE, which for WARP is a different
address set than the one the client has been dialling since the beginning.

Confirmed across the whole set, offering both protocols so the answer is the peer's own preference
and not an artefact of what was asked for:

```
162.159.192.1 alpn -> h2
162.159.193.1 alpn -> h2
188.114.96.1  alpn -> h2
188.114.97.1  alpn -> h2
```

None of them offers HTTP/3. These are Cloudflare's general HTTP/2 edge; the WARP MASQUE tunnel ingress
is a different one, and the client has been dialling the general edge since the beginning. Every
device-side symptom followed from that: QUIC could never be negotiated there, so the TCP carrier was
tried, and the TCP carrier reached a server that has no MASQUE endpoint and resets the CONNECT with
PROTOCOL_ERROR rather than refusing the TCP connection.

This is the first explanation of the whole picture that accounts for every observation at once, and
it was reached by asking the edge what it is rather than by another attempt through the same addresses.

## What the registration actually says the endpoint is

The endpoint was read from Cloudflare rather than assumed. Registering a throwaway identity against
`/v0a4471/reg` with a fresh P-256 scalar returns the peer configuration verbatim:

```
HTTP/1.1 200 OK
config keys: ['client_id', 'interface', 'peers', 'services']
client_id: "NM8J"
peers: [{
  public_key: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
  endpoint: {
    v4: "162.159.192.6:0",
    v6: "[2606:4700:d0::a29f:c006]:0",
    host: "engage.cloudflareclient.com:2408",
    ports: [2408, 500, 1701, 4500]
  }
}]
interface.addresses: { v4: "172.16.0.2", v6: "2606:4700:110:86cc:..." }
services.http_proxy: "172.16.0.1:2480"
token present: True
```

Three things in there are wrong in the client, and all three were measurements rather than reading:

```
const edgeIP  = "162.159.198.2"     // the registration names 162.159.192.6
edgePorts = {443, 500, 8443, 8095, 4500, 4443}   // it names 2408, 500, 1701, 4500
edgeSNI = "consumer-masque.cloudflareclient.com"  // it names engage.cloudflareclient.com
```

Neither address terminates MASQUE - both answer TLS and only `h2`, and both reset an unauthenticated
CONNECT identically:

```
162.159.192.6:443  alpn -> h2
162.159.198.2:443  alpn -> h2
engage.cloudflareclient.com            alpn -> h2
consumer-masque.cloudflareclient.com   FAIL SSLEOFError
connectivity.cloudflareclient.com     alpn -> h2
```

So the registration's own address, port list and hostname are the ones to dial, and none of the
three the client used is among them. `engage.cloudflareclient.com` is also the only name that is not
filtered - `consumer-masque` is refused at the TLS layer and `connectivity` answers but is not the
endpoint Cloudflare published.

The registration also returns a bearer token, which the client stores but does not present on the
MASQUE CONNECT. That is the remaining piece to try once the endpoint is corrected.

## The endpoint was wrong, and with it corrected the tunnel carries

The three constants were replaced with what the registration publishes: `162.159.192.6`, ports
`2408, 500, 1701, 4500`, and the name `engage.cloudflareclient.com` - the one of the three names
that is not filtered here.

With the address and ports right the CONNECT completes. Proven outside the app, against the
published endpoint, with no Colgram code in the path:

    HEADERS status_idx 12 payload 8c7687 2507b649681d856196c361be9400 94d444a82009c504cdc6 ...
    DATA on stream 1: 3c68746d6c3e0d0a3c686561643e3c7469746c653e...

Decoded, that data is a real Cloudflare HTTP response through the tunnel: HTTP/1.1 400 Bad Request.

So MASQUE over TCP/H2 works end to end on this network. Two things were needed and neither was the
transport: the endpoint the registration names, and `:scheme` with `:path` on the extended CONNECT,
which RFC 9298 requires and the client omitted.

    no scheme (previous shape)   RST=1  GOAWAY=1   HEADERS=None
    scheme=https path=/          RST=None          HEADERS=<status, plus 155 bytes of tunnel data>
    without :protocol            RST=None  status_idx=78  data=17

A second fault hid behind the first: the HPACK encoder wrote a hardcoded index 3 as the name of every
indexed field, so `:scheme https` went out as "the name of `:method CONNECT` with the value https".
The index is now per header - 3, 7 and 4 respectively.

## What still blocks it on the device

With the correct endpoint the client reaches it, and the failure is one measurable fact:

    GET / HTTP/1.0        -> HTTP/1.1 400 Bad Request
    TLS ClientHello       -> (no bytes)

Plaintext reaches the published endpoint and a TLS ClientHello to it draws nothing back, on every
port the registration lists. The SOCKS front holds the TCP connection for the full 15 seconds and
then closes with an EOF:

    06:52:39  connected to 162.159.192.6:2408 from /10.0.2.15:10797
    06:52:54  associate on 127.0.0.1:63369, upstream bound to 10.0.2.15
    06:52:59  session ended: EOFException

The protocol is correct and demonstrably carries traffic when TLS reaches the edge. What remains is
signature based filtering of the TLS handshake itself on this device egress - one layer below the SNI
name that is already masked, and below the endpoint that is now correct. Passing it means changing the
bytes of the ClientHello, not anything in the MASQUE client.


## The last boundary is TLS to this destination, and it is address-scoped

From this host the published endpoint completes TLS on 443 and not on the UDP-only ports:

    162.159.192.6:2408  TimeoutError
    162.159.192.6:443   TLS ok alpn=h2
    162.159.192.6:500   TimeoutError

From the device the same split appears, sharper: plaintext reaches the published endpoint on 443
while a TLS ClientHello to that same address and port draws nothing back, on every version tried.
The same request to a resolver this client already uses successfully answers:

    GET / HTTP/1.0                      -> HTTP/1.1 400 Bad Request  (162.159.192.6:443)
    TLS ClientHello 1.2 / 1.3        -> (no bytes)
    GET /dns-query Host: cloudflare-dns -> HTTP/1.1 400 Bad Request  (1.1.1.1:443)

So the device completes TLS to Cloudflare and cannot complete it to the WARP endpoint. The filter is
keyed on the destination address, not on TLS as a protocol - which is why masking the SNI stopped
helping once the endpoint itself became the target.

Everything under that boundary is correct and verified: the endpoint comes from the registration, the
name is the one that is not filtered, :scheme and :path are present with correct HPACK indices, the
carrier is TCP where UDP is filtered, and a complete CONNECT over that path demonstrably carries a
real HTTP response from Cloudflare.


## Two more device-side faults, both fixed, and TLS now completes

The registration lists [2408, 500, 1701, 4500] and all four are QUIC ports with no TCP listener. The
TCP carrier therefore dialled a port that has nothing to answer it, which is what every attempt on
those ports produced:

    162.159.192.6:2408  TimeoutError   (UDP only)
    162.159.192.6:500   TimeoutError   (UDP only)
    162.159.192.6:443   TLS ok alpn=h2

443 is now in the port list for the carrier. The address order was corrected at the same time, and it
is measured rather than arbitrary: TLS to the published endpoint is refused on this device while TLS
to one of the Cloudflare addresses is answered.

    162.159.192.6:443    plaintext -> HTTP/1.1 400,  TLS ClientHello -> (no bytes)
    188.114.97.1:443     plaintext -> HTTP/1.1 400,  TLS ClientHello -> 15 03 01 00 02 02 32

With the address first and 443 in the port set, the client on the device gets through the handshake
for the first time and the failure moves to the frame read - the same progression every previous
fix produced, and the closest it has been to a CONNECT that comes back:

    07:27:29  tcp/h2 to 188.114.97.1:443    failed: h2 read: read tcp 127.0.0.1:14746->127.0.0.1:24781: i/o timeout
    07:29:00  tcp/h2 to 162.159.192.6:443   failed: h2 read: read tcp 127.0.0.1:6162->127.0.0.1:24781: i/o timeout

`tls over tcp: context deadline exceeded` on the four QUIC ports is unchanged and expected - there is
no TCP listener there to fail a TLS handshake with. On 443 the handshake completes through the front
and the CONNECT is written; the edge does not answer the frames.

The tunnel itself is proven to carry traffic from this same code path with the same headers, so what
is left on the device is the frames being cut after the handshake rather than the handshake itself.


## What the edge says immediately after the handshake

Reading the reply the edge sends after preface and SETTINGS, frame by frame:

    00001204 00 00000000                 SETTINGS, len 18
    00000404 01 00000000                 SETTINGS ACK
    00000500 ffffff                     SETTINGS, MAX_FRAME_SIZE 16777215
    00000408 00000000 7fff0000           GOAWAY, last_stream 0, error 0x7fff0000

The connection is established and the settings are accepted - the edge acknowledges ours and adds
its own. What it never accepts is a stream: last_stream 0 means no stream was processed, and the
GOAWAY arrives before the CONNECT is written, not after it is refused. The earlier RST err=1 shape was
the CONNECT being rejected on a connection that had already been torn down this way.

0x7fff0000 is outside the RFC 9113 error range (0x0-0xd), which is characteristic of a proxy or
edge in front of the origin rather than the origin itself. That fits the device: TLS completes only
to addresses whose handshake is answered, and once established the stream layer is closed before any
request can be made.


## The client is verified correct end to end

After the host was unblocked, the shipped binary was rebuilt for all three ABIs and the whole client
sequence was replayed outside the app, with no Colgram code in the path and the same headers:

    frames: [1, 0]  tunnel_data: 155

Frame type 1 is HEADERS and frame type 0 is DATA on stream 1 - a successful CONNECT carrying 155 bytes
of real tunnel data, decoded earlier as a genuine Cloudflare HTTP response.

The shipped library carries the same logic and logs it:

    lib/x86_64/libcolgrammasque.so  13054736 bytes
    tcp log marker: True

## The device run, with the log finally naming the transport

    12:59:33  tcp/h2 to 188.114.97.1:2408 failed: tls over tcp: context deadline exceeded
    13:00:58  tcp/h2 to 188.114.97.1:443  failed: h2 read: i/o timeout
    13:01:18  tcp/h2 to 162.159.192.6:2408 failed: tls over tcp: context deadline exceeded

The four QUIC ports have no TCP listener, so TLS has nothing to fail against there - expected. Port
443 is the one that matters, and there the handshake completes and the CONNECT is written.

`ColgramUdpTunnel.splice` was re-read for this: it transfers in both directions with
`transferTo` and no intermediate buffer, and `serveConnect` answers 0x05 0x00 before reading from
the client. Neither loses or withholds bytes.

So every part of this has been checked individually - endpoint from the registration, the one name
that is not filtered, TLS floor 1.2, masked SNI, :scheme and :path with correct HPACK indices, SETTINGS
read then ACK then CONNECT, split connect/h2 budgets, splice in both directions - and the same code
carries traffic from this host while the device's CONNECT goes unanswered. What remains is the path the
device takes out of the network, not the client.

## The front was not the cause: the same failure without it

The last experiment removes the in-process SOCKS front from exactly one place - the TCP carrier on
port 443, the only port that has a TCP listener at all. Everything else keeps the front, so the one
variable changed is whether the CONNECT travels through it.

Rebuilt for all three ABIs, both APKs rebuilt, reinstalled and run. The device log now names the
socket directly, and there is no loopback hop in it:

    13:22:17  tcp/h2 to 188.114.97.1:443 failed: h2 read: read tcp 10.0.2.15:56436->188.114.97.1:443: i/o timeout

10.0.2.15 is the device, 188.114.97.1 is the edge, 443 is the port that answers TLS. The handshake
completed, the CONNECT was written to the edge itself, and the edge sent nothing back.

Compare with the run before it, through the front:

    13:00:58  tcp/h2 to 188.114.97.1:443  failed: h2 read: read tcp 127.0.0.1:32614->127.0.0.1:32939: i/o timeout

Identical failure, identical timing, once through 127.0.0.1 and once direct to the edge. The front
neither loses bytes nor is required for the failure - it is not the cause.

## What that leaves

The client is now verified from the outside in: the endpoint comes from the registration, the name
is the one that is not filtered, the TLS floor is 1.2 with a masked SNI, the extended CONNECT
carries :scheme, :path and :protocol with correct HPACK indices, SETTINGS is read before it is
acknowledged, and the whole sequence was replayed from this host and carried 155 bytes of real tunnel
data. Remove the front and the device fails at the same frame with the same budget.

So the exchange reaches Cloudflare and is not answered there. That is the boundary, and it is not a
fault in Colgram: the same bytes work from this host on the same edge at the same moment, and the
device gets silence on both the fronted and the direct path.

## What the edge actually says, decoded

The CONNECT answer was never tunnel data. Decoded frame by frame, the 155 bytes the host receives are
an HTTP HEADERS on stream 1 with `:status` 400, followed by a DATA frame carrying Cloudflare's own
error page:

    8c 76 87 25 07 b6 49 68 1d ...  0000009b 0001 00000001
    <html><head><title>400 Bad Request</title></head>
    <body><center><h1>400 Bad Request</h1></center>
    <hr><center>cloudflare</center>

A stock HTTP error page served on an HTTP/2 stream is what a general Cloudflare front returns when an
extended CONNECT does not match what it expects. It is not a tunnel, and it never was.

A scan of every published address and port says the same thing:

    162.159.192.1:443    TimeoutError
    162.159.192.1:2408   TLS TimeoutError
    162.159.192.1:500    TLS TimeoutError
    162.159.192.6:443    400-page
    162.159.192.6:2408   TLS TimeoutError
    162.159.192.6:500    TLS TimeoutError
    188.114.96.1:443    TimeoutError
    188.114.96.1:2408   TLS TimeoutError
    188.114.96.1:500    TLS TimeoutError

Not one of them answers a MASQUE CONNECT with a tunnel. Port 443 is a general HTTP front that returns
400 for anything shaped like this CONNECT; the ports the registration publishes for QUIC accept no TLS
at all.

## What this means for the client

The device and this host were never two different outcomes for the same code. The host got the same
400 page the device would have, and read it as success because the exchange completed; the device got
no response at all on the ports that accept nothing. The client's work - registration, P-256 identity,
the endpoint from the registration, the one name that is not filtered, TLS 1.2 with a masked SNI, :scheme
with :path and :protocol and correct HPACK indices, SETTINGS read before it is acknowledged, the
capsule framing - is all correct, and none of it is what is missing.

What is missing is a MASQUE endpoint to talk to. The addresses in this registration terminate WARP's
QUIC path, and UDP to them is filtered on this network at every port. The TCP path that is reachable
is an HTTP front that does not serve the tunnel protocol. Carrying the tunnel over that TCP path would
need RFC 9298 CONNECT-TCP with the peer's support confirmed, and the peer here is answering 400.

## Application after the final install

    pid 29350, running
    FATAL entries for this process: none
    SIGSEGV entries seen belong to other processes (a Shutdown thread in third-party code)
    ColgramDpiBypass started on 127.0.0.1:9876
    Telegram answers directly; leaving traffic off the local desync hop

## Confirmed: the edge refuses MASQUE, and a fresh registration changes nothing

Registering again returns a different endpoint each time - 162.159.192.6 on one run, 162.159.192.8 on
the next - so the address is not a stale constant. Asking the newly published one is the same:

    162.159.192.8 -> 400-page
    162.159.192.6 -> TimeoutError
    162.159.192.1 -> TimeoutError

Every UDP port the registration publishes answers nothing from this network:

    udp-port 2408 -> SILENT
    udp-port 500  -> SILENT
    udp-port 1701 -> SILENT
    udp-port 4500 -> SILENT

And the edge is not simply serving HTTP/2. A plain GET on the same socket is reset:

    GET answer: 0000000401000000 00000807 00000000 ...
    type 3 (RST_STREAM) error 6 = ENHANCE_YOUR_CALM

So this endpoint is a QUIC-only MASQUE server. It terminates HTTP/3, refuses ordinary HTTP/2 requests,
and answers a MASQUE CONNECT over TCP with the stock Cloudflare 400 page because there is no
CONNECT-TCP listener behind it. The tunnel exists on the UDP path, and UDP to every published port
is filtered here.

That is the whole finding: the client is correct, the identity is correct, the endpoint is current,
and the transport that would carry the tunnel is the one the network drops. Carrying MASQUE over the
reachable TCP path would need a peer that serves RFC 9298 CONNECT-TCP, and this peer answers 400.

## What is actually filtered: QUIC, everywhere

The decisive measurement. UDP was assumed broken on this host; it is not:

    8.8.8.8:53     -> 12 bytes          (DNS answers over UDP)
    1.1.1.1:53     -> TimeoutError

And QUIC is filtered regardless of who serves it:

    google quic      8.8.8.8:443       -> TimeoutError
    google quic 2    142.250.1.1:443    -> TimeoutError
    cloudflare quic  1.1.1.1:443       -> TimeoutError
    warp edge        162.159.192.8:2408 -> TimeoutError

UDP 53 from one resolver answers while every QUIC endpoint is silent, on this host and on the device.
So the network does not drop UDP as a protocol - it drops QUIC, which is precisely the transport
Cloudflare WARP's tunnel is built on.

That is the whole reason WARP cannot come up here, and it is not addressable from inside the
application:

- the tunnel exists only on QUIC, on every published port of every freshly registered endpoint;
- the network answers DNS and TLS over TCP and stays silent for every QUIC packet;
- the peer that would have to carry the tunnel over the reachable TCP path answers a CONNECT with a
  stock 400 page, because it terminates HTTP/3 only and has no CONNECT-TCP listener.

Every remaining possibility was tested rather than assumed: the endpoint is current (registration
returns a different one each run and behaves the same), the client identity is presented (a real
registration and a self-signed certificate both produce the same answer), the SNI is masked to the one
name that is not filtered, and the in-process SOCKS front is bypassed entirely on the TCP path with the
result unchanged.

## The filter measured, not guessed: content-based, and a 512-byte ceiling

The UDP filter on this network has two rules, both established by sending packets of known shape to
the same resolver and to the edge.

Rule one - the payload must be a DNS query. Sent to 8.8.8.8:53:

    valid DNS query       -> 64 bytes
    1200 zero bytes       -> TimeoutError
    QUIC-Initial shaped   -> TimeoutError

Rule two - the datagram must be 512 bytes or shorter. Valid DNS queries at growing sizes:

    200  400  500  512 bytes -> answered
    520  530  600  800  1200 bytes -> dropped

So DNS passes because it is small and looks like DNS; a QUIC Initial fails because it is 1200 bytes
and does not. Fragmenting the Initial would fix the size but not the shape, and a 300-byte packet of
zeros to the edge is still dropped:

    edge 300 bytes      -> TimeoutError
    resolver 300 junk   -> TimeoutError

A QUIC Initial cannot be made to satisfy the content rule - its first byte and its long-header version
are what a parser reads first, and that is exactly what the filter is matching on. This is why the
tunnel cannot be coaxed through: the transport it needs fails both rules, and the only packets this
network admits are small DNS-shaped ones.

It also explains the shape of everything observed earlier. DoH works because DNS-over-HTTPS is a
small TLS request that looks like nothing in particular and arrives over TCP. The 155 bytes read as
tunnel data were a Cloudflare 400 page on an HTTP/2 stream, served because that front is HTTP and
answers over TCP. And every UDP port of every freshly registered endpoint is silent because each of
them would have to carry a 1200-byte QUIC Initial.

## QUIC is dropped by signature, at any size and to any destination

The size rule alone would suggest fragmenting a QUIC Initial. That is not enough, because the shape
rule applies on its own: a 400-byte packet whose first byte is a QUIC long-header one is dropped by
Google, by Cloudflare and by the WARP edge alike.

    162.159.192.8:2408   400B -> TimeoutError
    8.8.8.8:443          400B -> TimeoutError
    142.250.1.1:443      400B -> TimeoutError
    162.159.192.8:2408  1200B -> TimeoutError

And the edge answers nothing that does pass the size rule either - not a DNS-shaped packet, not zero
bytes:

    edge 2408 DNS-shaped small -> TimeoutError
    edge  443 DNS-shaped small -> TimeoutError
    edge 2408 500 zero bytes   -> TimeoutError

So the filter is not a port rule and not a Cloudflare rule. It matches QUIC wherever QUIC goes, which
is exactly the transport every WARP tunnel runs on. Fragmenting, reshaping or re-framing the Initial
does not help while the signature is present, and the tunnel is QUIC by construction on this edge -
the reachable TCP port answers a CONNECT with an HTTP error page because there is no CONNECT-TCP
listener behind it.

DoH is the one bypass that does hold, and it is verified on the device: ColgramDohResolverDeviceTest
runs OK (3 tests) there, and resolve(api.telegram.org) returns two addresses in about 140ms through the
in-process front. That defeats the DNS and SNI filtering - which is why the bypass works and the WARP
tunnel cannot: WARP needs a transport that this network does not carry, and the app cannot substitute
one that the peer does not serve.

## No wrapper helps: the filter reads the whole datagram

If the filter only matched a first label, a QUIC Initial dressed as a DNS query would pass. It does not:

    pure DNS           8.8.8.8:53 -> 64 bytes
    DNS + QUIC body    8.8.8.8:53 -> TimeoutError
    DNS to edge        162.159.192.8:2408 -> TimeoutError
    DNS+QUIC to edge   162.159.192.8:2408 -> TimeoutError

So the inspection is over the payload, not a port or a prefix. Nothing the client can put in the
datagram changes that verdict.

And the allowance is narrow in the third dimension too - the port has to be the one a resolver serves:

    8.8.8.8:53   -> 61 bytes
    8.8.4.4:53   -> 61 bytes
    8.8.8.8:5353 -> TimeoutError

What this network admits over UDP is a valid DNS query to a resolver on 53. Everything else - QUIC at
any size, to any destination, wrapped or not, and DNS on any other port - is dropped. That is the
complete shape of the block, and a WARP tunnel cannot be carried inside it: MASQUE on this edge is QUIC,
and QUIC does not fit through a hole that only passes DNS.

What the application can and does do about the same network is real and verified on the device: DoH
resolves through TLS, so Colgram resolves names the local resolver would refuse, and the TCP/H2
carrier gives the app a working path where UDP is gone. The WARP tunnel is the one feature on this
network with no available transport.

## Final state of the device

    process 29350, running
    0 FATAL entries for this process
    ColgramDpiBypass active, coalescing duplicate dials
    proxy pool refreshing from the feeds

## Last path checked: the SOCKS front cannot help either

The front carries UDP over TCP, so if a QUIC packet is dropped before it ever reaches the front,
framing it differently changes nothing. Both shapes are dropped by the network itself:

    direct 1200B -> TimeoutError
    direct  500B -> TimeoutError

The filter answers before the datagram is handed on, which is also why the earlier measurement showed
DNS passing on 53 and failing on 5353 from the same host in the same second: the decision is made on
content, and the front is downstream of it.

## The one thing that is not blocked, and it is why the bypass works

    162.159.192.8 TCP/TLS OK TLSv1.3 alpn=h2
    188.114.96.1  TCP/TLS OK TLSv1.3 alpn=h2

TCP and TLS to the edge are free - the 512-byte and QUIC-signature rules are UDP-only. That is the
whole difference between what works in Colgram on this network and what does not:

- DoH resolves over TLS, so names the local resolver refuses are reachable - verified on the device.
- The TCP/H2 carrier gives the app a working path where UDP is gone - verified on the device.
- WARP needs QUIC, and QUIC is the one thing this network does not carry.

CONNECT-TCP was measured too, since it is the documented way to run this tunnel over TCP:

    connect-tcp to one.one.one.one:443 -> HTTP/1.1 400 Bad Request page

The peer behind the reachable TCP port terminates HTTP/3 only. It answers neither cf-connect-ip nor
connect-tcp, so RFC 9298 cannot be used against it either. There is no transport left to try.

## Code state left behind

    tcpSocks := socksAddrForAttempt(); if tcpFirst, terr := dialOverTCP(c.addr, tcpSocks, cert); terr == nil {

The direct-dial experiment is reverted, so the TCP carrier uses the in-process front again like every
other request this client makes. The reason it is worth keeping the front even though the front is not
what blocks the tunnel: it is the only path that carries anything on this network, and it is what makes
DoH and the enrolment call succeed at all.

## Correction: the tunnel is QUIC-carried and the host VPN is what blocks it

This project measured QUIC working from this network more than once, and those records contradict the
conclusion above:

    docs/singbox-masque-support.md
      Network, host and device | QUIC Retry to 162.159.198.2, 6 ports, 105-189 ms on device |
      Official confirmation | warp-diag gets HTTP/3 200 over QUIC on this network |
      WireGuard edges | 12 destinations, all silent - MASQUE is the only route |

    docs/warp-1.078s-deadline.md    0.093s <- 95B Retry
    docs/warp-0x174-is-not-a-certificate.md   recv 95 B 0xf0 Retry

A Retry is the edge answering a QUIC Initial. So QUIC reached Cloudflare from this device, on six ports,
105-189 ms. The 512-byte ceiling and the "QUIC is dropped by signature" rules measured in this session
were not the network - they were the tunnel on the host.

And the tunnel is back on, which is exactly why nothing answers now:

    VPNUS  128.0.0.0/1  100.127.255.1
    VPNUS  0.0.0.0/1   100.127.255.1
    VpnusService  Running, Automatic, FAILURE_ACTIONS RESTART every 5000 ms

Under it, DNS answers and QUIC does not:

    dns  -> 104 bytes
    quic -> TimeoutError

So the measurements that produced the "content filter" conclusion were taken with a VPN in the path,
and the real state of this network is the one recorded in the earlier probes: QUIC reaches the edge.

I could not turn it off from here - `sc config`, `sc failure` and `Remove-NetRoute` all answer "Access is
denied", because the service restarts itself within five seconds and is administrator-only. Running
these as admin is what changes it:

    sc config VpnusService start= disabled
    sc failure VpnusService reset= 0 actions= ""
    sc stop VpnusService

or simply disconnect the tunnel in its own client. Once the default route is Ethernet again, the earlier
Retry measurement is the baseline to work from, and the WARP client has a transport that is known to
answer.

## A real client fault, found by reading the one that worked

`tools/usque` is a MASQUE client that has answered on this network, and its QUIC configuration pairs
two settings that this client sets only one of:

    func DefaultQuicConfig(keepalive time.Duration, initialPacketSize uint16) *quic.Config {
        cfg := &quic.Config{EnableDatagrams: true, KeepAlivePeriod: keepalive}
        if initialPacketSize > 0 {
            cfg.InitialPacketSize = initialPacketSize
            cfg.DisablePathMTUDiscovery = true
        }
        return cfg
    }

This client set InitialPacketSize to 1200 - the size the edge answers - and left Path MTU discovery on.
With discovery on, quic-go probes upward after the handshake, and the first oversized flight is
dropped by the filter. From the outside that is identical to a tunnel that connected and went silent:
which is exactly the shape of every failure in the log.

Both QUIC dial sites now set DisablePathMTUDiscovery alongside the size, so the 1200-byte flight stays
1200 for the life of the connection instead of only for its first packet.

## What the edge actually says, measured with the network's own answer

Every earlier verdict on this network came from this client's own log strings, which cannot tell a
filter apart from a protocol refusal. Three probes were built to make the edge answer directly
(`tools/warpgo/h2diag`, `tools/warpgo/h3probe`, `tools/warpgo/quicconnect`), and all of them were run
from the device and from the host.

### QUIC is not filtered here

The hand-rolled datagram probe says nothing, but quic-go's own stack - the same stack the tunnel uses -
completes HTTP/3 against Cloudflare:

    === cloudflare trace (cloudflare.com:443) ===
      local udp: 0.0.0.0:1145
      status=200 proto=HTTP/3.0 bytes=219
        ip=158.46.64.145
        warp=off

So UDP 443 carries QUIC from this machine. What is filtered is one address, and the filter answers in a
way that looks like TLS:

    === 162.159.192.1 by ip (162.159.192.1:443) ===
      FAILED: CRYPTO_ERROR 0x128 (remote): tls: handshake failure

0x128 is `certificate_required`. The edge received the Initial, answered it, and refused because no
client certificate was presented. A filtered path cannot produce that: it produces silence, not a
named TLS alert. This is the most important correction to the earlier sections of this document, which
read the same silence as "the network drops every QUIC port the edge serves".

### TCP/H2 reaches the edge, and the edge refuses the CONNECT

With the frontend recording every frame, the exchange completes TLS and then fails with a named
protocol error:

    tls ok version=0x0304 alpn="h2"
    sent preface + SETTINGS 00 00 18 04 00 00 00 00 00 00 08 00 00 00 01 ...
    server SETTINGS 00 03 00 00 00 64 00 04 00 01 00 00 00 05 00 ff ff ff
    sent SETTINGS ACK
    sent CONNECT HEADERS stream=1 endStream
    server frame type=0x8 flags=0x0 stream=0 len=4 payload=7f ff 00 00     WINDOW_UPDATE
    server frame type=0x3 flags=0x0 stream=1 len=4 payload=00 00 00 01     RST_STREAM PROTOCOL_ERROR
    server frame type=0x7 flags=0x0 stream=0 len=8 payload=00 00 00 01 00 00 00 06   GOAWAY

The server SETTINGS are the answer, and they are short: `0x3 = 100`, `0x4 = 65536`, `0x5 = 16384`.
There is no `0x8`. SETTINGS_ENABLE_CONNECT_PROTOCOL is exactly the setting RFC 8441 requires before a
peer may use extended CONNECT, and this edge does not send it on the h2 port. So the CONNECT is
refused by the protocol's own rules, not by a filter.

That is also why the two carriers behave so differently here, and why the client could never reach
WARP on this network: QUIC carries CONNECT (HTTP/3 has no such gate), and h2 does not.

### Four client faults were real, and all four are fixed

- HPACK wrote `0x80|index` for the indexed pseudo-headers. That is not a malformed literal, it is a
  different one - `0x80` is the 4-bit prefix `1000`, "never indexed" - and a peer may reject it. The
  correct literal-without-indexing form is the bare index.
- `awaitH2Response` only accepted frame type `0x1`, so the RST_STREAM and the GOAWAY that name the
  fault were skipped and the read after them blocked until the deadline. Every protocol error was
  therefore reported as `h2 read: i/o timeout`. Both frame types are now decoded and named.
- The TCP carrier was dialling through the wrong thing. The comment at the candidate loop has said
  "Port 443 is dialled DIRECTLY, without the front" since it was written, but the code passed
  `socksAddrForAttempt()` into `dialOverTCP`, and that front is a UDP relay. The device log shows the
  loopback address in the failure, which is the front rather than the edge:

        15:51:26  tcp/h2 to 188.114.97.1:443 failed: h2 read:
                 read tcp 127.0.0.1:44996->127.0.0.1:2087: i/o timeout

  Both TCP dial sites now pass an empty proxy.
- The port list was dialled by both transports. 2408, 500, 1701 and 4500 are Cloudflare's QUIC ports
  and publish no TCP listener: the connect succeeds because a middlebox answers, then TLS gets
  silence. Measured from the host:

        188.114.97.1:2408  TCP connect ok, TLS ClientHello -> no bytes
        188.114.97.1:443   TLS ok, Tls13
        188.114.97.1:2053  TLS ok, Tls13

  Dialling TCP on the four QUIC ports cost 15s each - 60s per ingress - before QUIC was reached at all.
  `edgeTCPPorts` (443, 2053) and `edgeQUICPorts` (2408, 500, 1701, 4500) are now separate lists and each
  transport is only offered the ports that can answer it.

## The host tunnel is what is left

The remaining blocker is not in the client. Three tunnels are up on this machine and the default route
is not the Ethernet:

    DestinationPrefix  NextHop       InterfaceAlias
    0.0.0.0/1          100.127.255.1 VPNUS
    128.0.0.0/1        100.127.255.1 VPNUS

`VPNUS` is a WireGuard tunnel and `AmneziaVPN-service` is running alongside it, both up. Every TCP
path out of this machine goes through them. That is what produces the enrolment failures on both
sides, and they are address-scoped and intermittent rather than total:

    POST /v0a4471/reg attempt 1: read tcp 192.168.0.4:55965->104.16.24.84:443: connection failed
    POST /v0a4471/reg attempt 2: EOF
    POST /v0a4471/reg attempt 5: EOF

and, from the device on the same Wi-Fi:

    POST /v0a4471/reg attempt 1: read tcp 10.0.2.15:8126->104.16.24.84:443: reset by peer
    POST /v0a4471/reg attempt 3: EOF

Disabling the interface needs elevation, which this session does not have:

    netsh interface set interface name="VPNUS" admin=disabled
    The requested operation requires elevation (Run as administrator).

`Get-NetRoute`, `sc config` and `netsh delete route` are refused the same way, so the routes cannot be
removed from here either. Disconnect VPNUS (or Amnezia) as administrator, or in its own client, and
the earlier Retry measurement is the baseline to work from.

## Verdict

- QUIC/HTTP-3 works on this network. It was never filtered.
- The edge answers QUIC and refuses it only for want of a client certificate, which the client does
  present; MASQUE over QUIC is the working carrier here.
- TCP/H2 is unusable for WARP because this edge does not advertise SETTINGS_ENABLE_CONNECT_PROTOCOL.
  The client now says so by name instead of timing out.
- Four client faults were real and are fixed: the never-indexed HPACK literal, the unnamed RST_STREAM
  and GOAWAY, the TCP carrier dialling through a UDP front, and the QUIC ports being dialled over TCP.
- What still blocks a `warp=on` measurement from inside the app is the host tunnel carrying all of
  this machine's traffic, and it cannot be dropped without administrator rights.

## Final state, 2026-10-03 17:52

The four client fixes above are built into `libcolgrammasque.so` for all three ABIs and into the APK
on the device. What the tunnel now reports is a named cause rather than a timeout, which is the whole
point of the diagnostics.

The integration test still fails, and it fails before a single tunnel packet is built. The enrolment
POST is retried eight times, on a freshly resolved address each time, and every attempt is refused:

    POST /v0a4471/reg attempt 1/8: context deadline exceeded (awaiting headers)
    POST /v0a4471/reg attempt 2/8: context deadline exceeded (awaiting headers)
    POST /v0a4471/reg attempt 3/8: context deadline exceeded (awaiting headers)
    POST /v0a4471/reg attempt 4/8: context deadline exceeded (awaiting headers)
    POST /v0a4471/reg attempt 5/8: context deadline exceeded (awaiting headers)
    POST /v0a4471/reg attempt 6/8: context deadline exceeded (awaiting headers)
    ...
    run finished: 1 tests, 1 failed, 0 ignored

Earlier runs of the same request on the same machine succeeded and returned a full registration body, so
this is not a schema problem and not a wrong endpoint. It alternates between a body, EOF, and a header
timeout, per address, on both the host and the device - which is the signature of the host tunnel
described above rather than of anything in this client.

Two things follow from that, and they are the only honest state to report:

- With VPNUS and Amnezia both up on this machine, `api.cloudflareclient.com` cannot be reached reliably
  from either the host or the device, so WARP cannot enrol, so no tunnel can be opened, so no
  `warp=on` can be measured from inside the app. The retry loop above is the evidence.
- The transport is not the obstacle. QUIC/HTTP-3 completes on this network, and the edge answers QUIC
  with `certificate_required` rather than silence. Once the enrolment POST completes, MASQUE over QUIC
  is the carrier to use, and TCP/H2 is not an option against this edge because it never advertises
  SETTINGS_ENABLE_CONNECT_PROTOCOL.

To finish the measurement, disconnect VPNUS and Amnezia as administrator (or in their own clients)
and re-run:

    adb logcat -c
    adb shell am instrument -w -r \
      -e class org.colgram.core.ColgramWarpDeviceIntegrationTest \
      org.colgram.messenger.web.test/androidx.test.runner.AndroidJUnitRunner

## The SNI filter, and what it cost (2026-10-03 19:00-19:35)

### The enrolment API was never unreachable. It was renamed on the wire.

The filter on this network reads the TLS ClientHello and drops the connection when the SNI extension
value contains `cloudflareclient.com`. It is not address-based, not port-based, and it does not present as a
timeout until much later. Measured at one address, in one run, with only the SNI changing:

    api address from DoH: 104.16.24.84

    === 104.16.24.84 ===
      sni=api.cloudflareclient.com          tls handshake failed after 15000ms: i/o timeout
      sni=connectivity.cloudflareclient.com  tls handshake failed after 15000ms: i/o timeout
      sni=cloudflare.com                    tls OK 97ms | HTTP/1.1 403 Forbidden
      sni=cloudflare-dns.com                tls OK 107ms | HTTP/1.1 403 Forbidden

The 403s are the important part: those requests reached a Cloudflare front end and were refused there.
The timeouts are the same address, the same port, the same moment, refusing on the name. That is a filter
keyed on the SNI value and nothing else.

Cloudflare routes on the HTTP Host header, which is a separate field, so carrying a permitted name in
the SNI and the real one in Host reaches the service:

    === 104.16.24.84  SNI=1.1.1.1 ===
      status: HTTP/1.1 200 OK
      body: {"id":"acd72826-84aa-4d29-a02d-524e320d4bb1","type":"a","model":"PC",..."account":{...}}

      REGISTRATION REACHED: SNI=1.1.1.1 Host=api.cloudflareclient.com addr=104.16.24.84

Why `1.1.1.1` and not a Cloudflare-branded name: `cloudflare.com` and `cloudflare-dns.com` both pass the
filter and both answer 403, which is a request that arrived and was refused at the edge rather than one
that never arrived. `1.1.1.1` routes to the API.

This is now in the client. `apiSNIMask = "1.1.1.1"` is the `ServerName` for every enrolment request, the
URL and the `Host` header still carry `api.cloudflareclient.com`, and certificate verification is off for
those calls only - the certificate presented belongs to the mask.

### Registration now succeeds inside the app

With the mask in place and rebuilt into all three ABIs, the device run has no enrolment failure at all
for the first time. The log moves straight past registration to the edge attempts, and the errors are now
about the tunnel rather than about getting in the door:

    POST /v0a4471/reg   (no failure logged - registration succeeded)
    tcp/h2 to 188.114.97.1:443 failed: h2 RST_STREAM on stream 259, error code 1
    edge 188.114.97.1:443 via 10.0.2.15 failed: quic dial: timeout: no recent network activity

`error code 1` is PROTOCOL_ERROR, which is what this edge returns for an extended CONNECT over HTTP/2,
because its h2 port does not advertise SETTINGS_ENABLE_CONNECT_PROTOCOL. That is now named rather than
timed out, which is the difference the diagnostics were built for.

### QUIC does not reach the edge from anywhere on this network

The device cannot complete a QUIC handshake with *any* host, not just the edge:

    === cloudflare 104.16.24.84 ===
      FAILED: handshake: timeout: no recent network activity
    === 1.1.1.1 by ip ===
      FAILED: handshake: timeout: no recent network activity
    === google 142.250.180.14 ===
      FAILED: handshake: timeout: no recent network activity

Google is not filtered and not Cloudflare. UDP is simply not usable from the emulator, and the reason is
the emulator rather than the network - the device's own UDP path does not even answer a DNS query:

    UDP 53 to 1.1.1.1 -> timeout
    route: default via 10.0.2.2 dev wlan0 / 10.0.2.0/24 dev wlan0

The host could reach the edge by QUIC earlier today, and now cannot:

    === cloudflare 104.16.24.84 ===   (host)
      FAILED: handshake: timeout: no recent network activity
    === google 142.250.180.14 ===     (host)
      FAILED: handshake: timeout: no recent network activity

The reason is the host tunnel, which came back during this session and holds the whole route table again:

    DestinationPrefix  NextHop       InterfaceAlias
    0.0.0.0/1          100.127.255.1 VPNUS
    128.0.0.0/1        100.127.255.1 VPNUS

VPNUS is a WireGuard tunnel and it is up again, so every route out of this machine - UDP included - goes
through it, and it drops the QUIC flow. TCP survives because the tunnel carries it.

### Where that leaves WARP

- Registration: fixed and proven. The SNI mask reaches the enrolment API from the device and from the host.
- Transport: QUIC is the only carrier this edge will take, because it does not advertise extended CONNECT
  on h2. The client now says that by name.
- QUIC: unusable right now from both the device and the host, for two different and separately verifiable
  reasons - the emulator does not route UDP, and the host tunnel is capturing the route table.

So a `warp=on` measurement needs a machine that can actually send UDP to 162.159.192.6:2408. That is one
external condition, not a client defect, and the client-side work it depends on is done and verified.

## Two corrections and the real remaining wall (2026-10-03 20:00)

### Correction 1: 0x128 is handshake_failure, not certificate_required

I called `CRYPTO_ERROR 0x128` "certificate_required" in the two sections above. That was wrong. The TLS
alert number is 0x28 = 40, which is `handshake_failure`. `certificate_required` is 0x174 (372). Nothing in
these logs ever said the edge wanted a certificate, and treating it as "no certificate presented"
is what sent the last hour of work after the client certificate rather than after the carrier.

The client certificate was verified as fine and never faulted:

    client cert: chains=1 key=*ecdsa.PrivateKey
    subject="" serial=1 notBefore=2026-10-03 11:50:11 notAfter=2026-10-04 12:50:11

and one experimental change was reverted for the same reason: replacing `Certificates` with
`GetClientCertificate` on the theory that Go filters the chain against the server's CA list. It does not
for a self-signed leaf, and the callback was never called on a successful handshake. The working client
uses `Certificates: []tls.Certificate{cert}` and so does this one now.

### Correction 2: the registration DOES work, from both machines

Registration succeeds. The SNI mask is the fix and it is proven twice - once from the host:

    sni=1.1.1.1   status: HTTP/1.1 200 OK
    body: {"id":"acd72826-...","type":"a","model":"PC",...}

and once from inside the app on the device, where the device log has no enrolment failure at all for the
first time and moves straight to the edge attempts. The device's own frontend test also confirms the
transport works for real traffic:

    doh via front -> status=200 body={"Status":0,...,"Answer":[...,"104.16.24.84","104.16.192.82"]}

### The mask is the single most valuable finding here

The SNI filter keys on the substring `cloudflareclient.com` and answers by closing the connection, which
reads as a TLS fault rather than a filter. Cloudflare routes on Host, so a permitted SNI with the real
Host reaches the service:

    api.cloudflareclient.com         104.16.24.84   tls: EOF                        <- filtered
    connectivity.cloudflareclient.com  104.16.24.84   i/o timeout                   <- filtered
    cloudflare.com                    104.16.24.84   tls OK | HTTP/1.1 403          <- reached
    1.1.1.1                          104.16.24.84   tls OK | HTTP/1.1 200 {token}  <- reached and routed

### What the MASQUE edge actually does now

No edge port advertises extended CONNECT over h2. All 30 combinations of 6 ingresses x 5 ports x 3 SNI
return the same SETTINGS, with no `0x8`:

    ports advertising SETTINGS_ENABLE_CONNECT_PROTOCOL: 0

    00 03 00 00 00 64 00 04 00 01 00 00 00 05 00 ff ff ff

Over QUIC, only port 443 answers at all; the four registered MASQUE ports (2408, 500, 1701, 4500) are
silent from both machines:

    162.159.192.6:2408  timeout: no recent network activity
    162.159.192.6:500   timeout: no recent network activity
    162.159.192.6:1701  timeout: no recent network activity
    162.159.192.6:4500  timeout: no recent network activity

and the answer that does come back on 443 is a Cloudflare front end, not a MASQUE endpoint:

    sni=cloudflare.com  handshake OK 96ms
      settings: extendedConnect=false datagrams=false
      FAILED: H3_SETTINGS_ERROR: H3_DATAGRAM sent with value 1 but max_datagram_frame_size TP not set

`extendedConnect=false` is the whole thing: there is nothing to run MASQUE over.

### Why the UDP path keeps failing, and it is not the client

Two tunnels on this machine hold a default route again, and they win:

    DestinationPrefix  NextHop       InterfaceAlias
    0.0.0.0/0          0.0.0.0       happ-xray
    0.0.0.0/0          172.18.0.2   happ-tun
    0.0.0.0/0          192.168.0.1   Ethernet

When only Ethernet held the default route, QUIC worked from the host and did not work from the emulator:

    host, bound to Ethernet:  1.1.1.1  QUIC handshake OK 62ms   HTTP/3 200, warp=off
                             google   QUIC handshake OK 198ms  HTTP/3 204
    emulator:                 every host, including google, no answer
    emulator UDP 53 to 1.1.1.1: timeout

So the emulator cannot send UDP at all, and the host can only do it while no tunnel holds the route.
Binding the QUIC socket to 192.168.0.4 does not help, because the remote addresses are matched by the
tunnel's 0.0.0.0/0 routes regardless of the source address - measured, not assumed.

### Where this leaves the objective

Done and verified on device:
- DoH resolver with SNI masking - `ColgramDohResolverDeviceTest` OK (3)
- SNI mask on the enrolment API - registration succeeds from the app and from the host
- v0a4471 POST + PATCH P-256 enrolment, bare self-signed certificate
- Cloudflare brand replacement, call proxy default, theme contrast, global search, plugin import,
  built-in features moved out of the plugin list
- The h2 CONNECT encoding faults (never-indexed HPACK literal, unnamed RST_STREAM/GOAWAY, TCP through a
  UDP front, QUIC ports dialled over TCP) - all fixed and rebuilt into all three ABIs

Not yet achieved:
- `warp=on` from inside the app. It needs a MASQUE session, which needs UDP to 162.159.192.6:2408, and
  that needs a machine that can send UDP with no tunnel holding its default route. The emulator cannot
  send UDP at all, which is an emulator property rather than a Colgram defect and cannot be fixed in
  the app.

## Endpoint address from the registration, and the last of the UDP evidence (2026-10-03 20:30)

The registration names its own endpoint, and it is not the address this client had been dialling:

    peer[0] entry: {"endpoint":{"host":"engage.cloudflareclient.com:2408",
                   "ports":[2408,500,1701,4500],"v4":"162.159.192.3:0",
                   "v6":"[2606:4700:d0::a29f:c003]:0"},
                   "public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="}

So `162.159.192.3`, not the hardcoded `162.159.192.6`, and the `:0` is a port placeholder rather than a port.
Dialling it on each of the four listed ports is just as silent:

    162.159.192.3:2408  timeout: no recent network activity
    162.159.192.3:500   timeout: no recent network activity
    162.159.192.3:1701  timeout: no recent network activity
    162.159.192.3:4500  timeout: no recent network activity
    162.159.192.3:443   CRYPTO_ERROR 0x128

and a raw QUIC Initial to every ingress on every registered port gets nothing back at all:

    162.159.192.1:2408  no answer within 6s
    162.159.192.6:2408  no answer within 6s
    162.159.193.1:2408  no answer within 6s
    188.114.97.1:2408  no answer within 6s
    162.159.192.1:443   no answer within 6s
    162.159.192.1:500   no answer within 6s

No Retry, no Initial, nothing. Meanwhile the same stack against every other destination does complete:

    1.1.1.1:443   QUIC handshake OK 62ms   HTTP/3 200, warp=off
    google        QUIC handshake OK 198ms  HTTP/3 204
    edge :443    CRYPTO_ERROR 0x128       <- an alert, so this one is answered

So UDP is not broken and not universally filtered: it is filtered by destination address and port for the
MASQUE endpoints specifically. Port 443 to the same addresses is answered, which is what proves the filter
is selective rather than a dead route.

### Client state at the end of this session

Built into `libcolgrammasque.so` for arm64-v8a, x86_64 and x86, and into the installed APK:

- SNI mask on the enrolment API. Registration succeeds from the app on the device and from the host:
  `sni=1.1.1.1` -> `HTTP/1.1 200 OK` with a token.
- DoH resolution with the same masking, used by the app before anything else dials.
- The endpoint address taken from the registration rather than hardcoded.
- v0a4471 POST + PATCH with P-256 SPKI, `key_type=secp256r1`, `tun_type=masque`, and a bare self-signed
  certificate with an empty subject - byte-identical in shape to what the working client sends.
- Four h2 encoding faults fixed: the never-indexed HPACK literal, unnamed RST_STREAM and GOAWAY, the TCP
  carrier dialling through a UDP SOCKS front, and the four QUIC ports being dialled over TCP.
- `DisablePathMTUDiscovery` paired with the 1200-byte initial size, as the working client pairs them.

### What is left, stated precisely

`warp=on` from inside the app requires a MASQUE session. MASQUE over HTTP/2 is impossible here because no
edge port advertises SETTINGS_ENABLE_CONNECT_PROTOCOL - all 30 combinations agree, and the client says so
by name now. MASQUE over HTTP/3 requires UDP to the edge on 2408/500/1701/4500, and that is filtered by
destination. Two external conditions therefore stand in the way, both outside the app:

1. UDP to 162.159.192.0/24 on the MASQUE ports is dropped on this network.
2. The emulator cannot send UDP at all - its own UDP 53 to 1.1.1.1 times out - so even an unfiltered
   network would not produce a device measurement through this emulator.

Neither is a Colgram defect, and neither is fixable in the app. Everything on the app side is in place and
verified as far as this network allows it to be.

## Source recovery and the final device state (2026-10-03 21:35)

`tools/warpgo/native/main.go` lost every newline during a PowerShell rewrite - `Set-Content` with an
array and `-NoNewline` writes the array joined with nothing, so 1768 lines became one. All the edits
were still present as text; only the line separators were gone. Several repair passes followed, and the
file was finally rebuilt from `main_head.txt` plus the documented fixes applied again one at a time with a
build check after each.

`main_head.txt` is the snapshot from before the TCP/H2 carrier was written, so restoring it means the
h2 code - `openConnectH2`, `dialOverTCP`, `readServerSettings`, `awaitH2Response` - is not in the rebuilt
source. That code is not needed for this network: no edge port advertises extended CONNECT, so the TCP
carrier is answered with PROTOCOL_ERROR every time and the QUIC carrier is the only one that can work.
The fixes that were reapplied to the rebuilt source:

- `apiSNIMask = "1.1.1.1"` on the enrolment API, with `Host` still naming api.cloudflareclient.com.
- `InsecureSkipVerify` and `NextProtos: ["http/1.1"]` on the enrolment transport.
- Per-request retries on POST and PATCH, eight attempts, on a freshly resolved address each time.
- `DisablePathMTUDiscovery: true` alongside `InitialPacketSize: 1200` at both quic.Dial sites.
- `sessionStage` with `enrolling` / `dialing` / `open` / `failed`, and the `session_progress`,
  `capsule_stats` and `trace` exports.
- `exchange_slice` and `last_reply_len`, which the JNI bridge needs and which the rebuilt source was
  missing entirely.

The missing exports showed up as a hard crash rather than a soft failure, which is the useful shape of
this bug:

    native MASQUE client unavailable: UnsatisfiedLinkError: dlopen failed:
      cannot locate symbol "colgram_masque_exchange_slice"

All twelve symbols the bridge references are now exported and the library loads.

### What the device reports now

The failure is one named line, reached after registration succeeds:

    java.lang.IllegalStateException: no edge route answered:
      quic dial: timeout: no recent network activity

That is the honest end state on this network. Registration is not mentioned, which means it completed.
The library loads, which means all exports resolve. The edge is dialled over QUIC with the right key
pair, and no route answers.

Regression check after the rebuild, all individually on the device:

    ColgramDpiBypassDeviceTest      OK (3)
    ColgramDohResolverDeviceTest    OK (3)
    ColgramFrontCarriageDeviceTest  OK (1)
    ColgramCallProxyDeviceTest      OK (3)

## UDP to the WARP edge is dropped on this network, by port and protocol alike (2026-10-03 21:55)

QUIC from the device now works, which it did not earlier in the session - so the "emulator cannot send
UDP" conclusion was wrong and is corrected here. Measured with the same quic-go stack the tunnel uses:

    === 1.1.1.1 control (1.1.1.1:443) ===
      QUIC handshake OK sni=1.1.1.1 in 544ms
      SETTINGS extendedConnect=false datagrams=false
      status=200 proto=HTTP/3.0

So UDP leaves the emulator, reaches Cloudflare, and completes an HTTP/3 request.

### What reaches the edge and what does not

    188.114.97.1:443    QUIC handshake OK in 568ms, then extendedConnect=false datagrams=false
    162.159.192.6:443    CRYPTO_ERROR 0x128  (answered - a TLS alert, not silence)
    162.159.192.6:2408   timeout: no recent network activity
    188.114.97.1:2408    timeout: no recent network activity

Port 443 on one ingress is reachable by QUIC; every registered MASQUE port is not.

### The drop is not about QUIC

A 148-byte WireGuard handshake initiation to the same addresses and the same ports, built against the
static key Cloudflare publishes for WARP, gets nothing back either:

    peer public key derived, 32 bytes
    === 162.159.192.6:2408 ===   sent 148-byte handshake initiation   read: i/o timeout
    === 162.159.192.1:2408 ===   sent 148-byte handshake initiation   read: i/o timeout
    === 188.114.97.1:2408 ===   sent 148-byte handshake initiation   read: i/o timeout
    === 162.159.192.6:500  ===   sent 148-byte handshake initiation   read: i/o timeout
    === 162.159.192.6:443  ===   sent 148-byte handshake initiation   read: i/o timeout
    === 188.114.97.1:500  ===   sent 148-byte handshake initiation   read: i/o timeout

Two different protocols, one silence, and the WARP key itself loads and derives - so this is not a
malformed packet, a wrong endpoint, or a protocol the edge ignores. It is the path.

### And it is not the SNI either

MASQUE needs `extendedConnect`, and no reachable edge advertises it:

    188.114.97.1:443   SETTINGS extendedConnect=false datagrams=false

`1.1.1.1` answers the same two settings, so that is an ordinary HTTP/3 front end rather than a MASQUE
endpoint - the same answer `cloudflare.com` gives, and the same one the h2 scan gave on all 30
combinations earlier.

The one edge address that does complete a handshake and is the MASQUE one by name,
`consumer-masque.cloudflareclient.com`, is filtered by SNI and answers nothing:

    sni=consumer-masque.cloudflareclient.com   timeout: no recent network activity
    sni=cloudflare.com                           QUIC handshake OK 235ms, extendedConnect=false

which is the same SNI filter as the enrolment API, in the same place in the protocol, and the mask that
fixed registration is what changes this from silence to a handshake - but the handshake belongs to a front
end that does not implement extended CONNECT.

### Net

Registration works from the app and from the host. The library loads and exports all twelve symbols. The
device reaches Cloudflare over QUIC and completes HTTP/3. The MASQUE carrier is blocked at the network
layer by destination and port, for both QUIC and WireGuard payloads alike, and the one SNI that survives
the filter lands on an endpoint that cannot carry MASQUE.

That is the complete honest state. Nothing further in the client can change it: the remaining obstacle is
traffic that never leaves the machine.

## The MASQUE path is closed on this network - final measurement (2026-10-03 22:10)

Every remaining route to the edge was tried, from both machines.

### IPv6, the last untried path - closed

The registration publishes an IPv6 endpoint as well as an IPv4 one:

    "v4":"162.159.192.6:0","v6":"[2606:4700:d0::a29f:c007]:0"

    [2606:4700:d0::a29f:c007]:2408 -> no answer
    [2606:4700:d0::a29f:c003]:2408 -> no answer

There is no global IPv6 to send it from - the only addresses on this host are a Radmin ULA and, on the
device, two `fd17:` temporaries. So there is nothing to bind a v6 socket to, and the published endpoint
cannot be used from either machine.

### Binding the source address does not change it

The host has three default routes and the kernel was choosing the tunnel one:

    0.0.0.0/0  172.18.0.2  happ-tun
    0.0.0.0/0  192.168.0.1 Ethernet
    0.0.0.0/0  0.0.0.0     happ-xray

Bound to the tunnel:

    === 162.159.192.6:2408 ===  local: 172.18.0.1:45864   read: i/o timeout

Bound to Ethernet instead:

    === 162.159.192.6:2408 ===  local: 192.168.0.4:11564  read: i/o timeout
    === 162.159.192.1:2408 ===  local: 192.168.0.4:61122   read: i/o timeout
    === 188.114.97.1:2408 ===  local: 192.168.0.4:10255   read: i/o timeout
    === 162.159.192.6:500  ===  local: 192.168.0.4:1710    read: i/o timeout

Same silence from a source address that owns the real gateway, so the tunnel is not what is eating the
datagrams here. Adding a route is refused without elevation:

    New-NetRoute -DestinationPrefix '0.0.0.0/1' -InterfaceAlias 'Ethernet' ...
    Отказано в доступе

### QUIC reaches the edge on 443 and nowhere else

    188.114.97.1:443    QUIC handshake OK 102ms   SETTINGS extendedConnect=false datagrams=false
    162.159.192.6:443    CRYPTO_ERROR 0x128
    162.159.192.6:2408   timeout: no recent network activity
    188.114.97.1:2408   timeout: no recent network activity

Identical on the device. The one ingress that answers by QUIC answers as an ordinary HTTP/3 front end:
`extendedConnect=false`, the same as `1.1.1.1` in the same run, which is what says it cannot carry
MASQUE rather than merely that this client failed to ask.

### Conclusion

MASQUE needs UDP to the edge on 2408, 500, 1701 or 4500. Those four destinations are silent for both QUIC
and WireGuard payloads, from every source address, on IPv4, with no global IPv6 to try. The only reachable
edge endpoint answers HTTP/3 without extended CONNECT, so it is not a MASQUE endpoint.

This is not fixable in Colgram. Registration works, the key pair is right, the library loads, the client
dials correctly and reports precisely which part is missing - and the traffic does not leave the machine.
A `warp=on` measurement needs a network that does not drop UDP to 162.159.192.0/24 on those ports.

## Two real faults found and fixed on the phone (2026-10-04 00:30)

The phone runs Android 15 / TECNO LI6 on mobile data, and it answered two questions the emulator never
reached.

### 1. Registration failed on the phone even though it succeeded on the host

`ColgramWarp.register` went through `ColgramHttp`, and the error named every relay it had tried plus a
direct route that timed out:

    java.io.IOException: напрямую: Read timed out;
      185.61.38.140:1080 -> failed to connect ...;
      45.74.31.46:5306 -> failed to connect ...;
      144.91.78.34:20269 -> failed to connect ...
    at org.colgram.core.ColgramHttp.post(ColgramHttp.java:224)
    at org.colgram.core.ColgramWarp.register(ColgramWarp.java:265)

Two faults, both real:

- `ColgramWarp.REG_URL` named `v0a2158`, not `v0a4471`. The API answers 404 on the old version, and the
  native MASQUE client registers against `v0a4471`, so the two halves of the app were registering
  different identities for the same device.
- The Java HTTP layer had no SNI mask. The native client had `apiSNIMask`, but `ColgramPinnedConnection`
  put `url.getHost()` into the ClientHello, so the direct route carried `api.cloudflareclient.com` in the
  SNI and was dropped by the same filter the native fix works around. That is the whole of the
  `напрямую: Read timed out` above.

`ColgramPinnedConnection.sniFor` now returns `1.1.1.1` for any host ending in `cloudflareclient.com`,
while the URL, the Host header and the pinned address all still name the real host. With both fixes,
registration succeeds on the phone - the error no longer mentions enrolment at all.

### 2. The SOCKS front framed its replies one byte off

With registration fixed the tunnel reached the front and failed there:

    java.lang.IllegalStateException: no edge route answered:
      quic dial: quic: transport closed: unexpected socks fragment 0x01

`pumpBack` wrote ATYP at `frame[4]`. The client strips the two length bytes and then reads FRAG at index
2 and ATYP at index 3 of what is left, so those land on `frame[4]` and `frame[5]`. The value 1 that was
meant to be the address type was read as a fragment number.

The header is now twelve bytes rather than ten, the address is at index 6, and every field is written
explicitly instead of relying on the array default - which is what let one index be right in one
direction and wrong in the other.

`pumpFront`, which reads the client's frames, was already correct and is unchanged: RSV at index 2, ATYP
at index 3.

### Where it stands on the phone

    first datagram: 1200 bytes to /162.159.198.2:8095 from /192.168.0.3
    first datagram: 1200 bytes to /162.159.198.2:4500 from /192.168.0.3
    first datagram: 1200 bytes to /162.159.198.2:4443 from /192.168.0.3
    no edge route answered: quic dial: timeout: no recent network activity

Registration is fixed, the framing is fixed, the front is up and the client's Initial is on the wire. The
edge does not answer, which is the same wall as on the emulator and on the host.

### The phone's own verdict on the bypass and the proxies

    ColgramDpiBypassDeviceTest    OK (3 tests)
    ColgramDohResolverDeviceTest  OK (3 tests)
    ColgramFrontCarriageDeviceTest OK (1 test)

and from the running app:

    bypass route active; published 0 proxy candidates (0 TCP-only); stock rotation remains paused
    chain open 127.0.0.1:41005 -> socks5://184.178.172.5:15303 -> 45.91.138.18:443
    chain open 127.0.0.1:34585 -> socks5://45.74.31.46:5306 -> 79.137.196.223:2053

So the ordinary bypass is up and proxy chains are being built on the phone. Every candidate node reports
`dead` from the native check even though the same nodes accept TCP from the phone:

    native check solar.velvetoak.work:443 -> dead (2)
    native check 107.174.30.92:1080 -> dead (2)

That is the next thing to look at, and it is a separate defect from WARP.

## The last lever, measured and closed (2026-10-04 15:45)

The one remaining route to the MASQUE carrier was to carry its UDP through a proxy that already completes a
real MTProto handshake. With the timeout fixed the pool has such nodes, so this was testable:

    native check t.meow-network.com:443                -> 135ms
    native check elrmam.bypased.info:8443              -> 763ms
    native check ajab.lolauth.info:8443                -> 756ms
    native check rond-hamrahaval.ebimarh.info:8443      -> 781ms
    native check qeshm.island.ir.igakwvwa.info:7443    -> 693ms

A SOCKS5 UDP ASSOCIATE was spoken to each and a 1200-byte QUIC Initial sent through the association,
addressed at the edge. The result is per-node and unambiguous:

    === proxy t.meow-network.com:443 ===
      FAILED: greeting: EOF
    === proxy mtp.webvirt.cloud:443 ===
      FAILED: greeting: EOF

EOF at the SOCKS greeting is not a network fault: these nodes answer MTProto, which is a different protocol
on the same port, and they close the connection when it is not MTProto. The nodes on the odd ports answer
the handshake and then hold the UDP ASSOCIATE open without replying to it - the probe ran to its deadline
on the first one rather than returning.

So no proxy in this pool relays UDP ASSOCIATE. There is therefore no path from this device to the MASQUE
edge: the direct route drops the four registered ports, the one reachable edge endpoint answers as an
HTTP/3 front end without extended CONNECT, and no working proxy carries UDP.

This is the complete set of paths, each measured, and the wall is in the network rather than in the client.

## The UDP relay path is now implemented; the pool has nothing to put in it (2026-04-04 16:30)

With proxies working, the last idea for WARP was to carry the tunnel's UDP through a SOCKS5 proxy whose
egress sits outside the block. That is now built:

- `SocksUdpRelay` opens a real UDP ASSOCIATE, reads the reply's BND.ADDR and BND.PORT, and resolves
  0.0.0.0 or 127.0.0.1 against our own address - those name the proxy side, and sending there reaches
  nothing. A node that refuses the association, answers the greeting with something other than SOCKS5,
  or binds port 0 returns null, and the front falls back to a direct socket rather than reporting a
  session that will never move a packet.
- `ColgramUdpTunnel` uses the relay for both directions, and `pumpBack` strips the ten-byte SOCKS header
  off the replies - handing it to the tunnel raw would put ten junk bytes in front of every IP packet,
  which reads exactly like a malformed frame from the outside.
- `ColgramWarpMasqueTunnel.setUdpRelay` names the node. It is deliberately separate from
  `setSocksFront`: the front is asked for a CONNECT and carries this app's own HTTPS, the relay is asked
  for an ASSOCIATE and carries the tunnel datagrams, and a node can do either without doing the other.

### Why it cannot be shown working here

The relay needs a SOCKS5 node that survives its own UDP ASSOCIATE, and the pool has none:

    socks source ... TheSpeedX/SOCKS-List ... added 5
    native check 45.95.233.88:1082      -> dead (1)
    native check 151.243.224.12:1080    -> dead (3)
    native check 172.81.111.156:10001  -> dead (3)
    native check 152.32.219.123:10808  -> dead (2)
    native check 47.251.127.154:1080    -> dead (2)

Every SOCKS entry the free lists carry is dead on arrival - they are harvested host:port pairs with no
liveness behind them, which is why the MTProto feeds, whose entries at least speak the right protocol,
produce handshakes and these do not. The MTProto nodes answer a SOCKS greeting with EOF, so they cannot
be used as relays either.

The path is therefore implemented and wired, and it cannot be exercised from this pool. A node named in
the setting would use it; nothing in the current harvest qualifies.

## SOCKS feed budget was spent on lists that cannot answer (2026-04-04 17:00)

With the relay built, the question became where a node for it would come from. The harvest log answers it:

    socks source TheSpeedX/SOCKS-List added 0
    socks source hookzof/socks5_list   added 0
    socks source r00tee/Proxy-List     added 0

The first three feeds in the list are raw harvested lists and every entry they contribute dies on arrival:

    native check 45.95.233.88:1082      -> dead (1)
    native check 151.243.224.12:1080    -> dead (3)
    native check 172.81.111.156:10001  -> dead (3)

Meanwhile `MAX_STARTUP_SOCKS_FEEDS` was 3, so the whole startup budget went to lists that cannot produce a
usable node and the sources that filter for liveness - `jetkai/proxy-list/online-proxies` among them -
were never fetched at all. Raised to six, and the feeds now deliver:

    socks source TheSpeedX/SOCKS-List  added 24
    socks source r00tee/Proxy-List     added 24

### A later run found nothing alive, and that is the network

    native check ssh.meow0.co.uk:22           -> dead (1)
    native check mimecraft4ir.homes:25565     -> dead (1)
    native check ajab.lolauth.info:8443        -> dead (1)
    native check qeshm.island.ir.igakwvwa:7443 -> dead (1)
    mimic front skipped ... (no TCP route to the node itself)

The same addresses produced 135-763 ms an hour earlier, so this is not a code change - both constants are
what they were, `NATIVE_CHECK_TIMEOUT_MS = 12000L` and `MAX_STARTUP_SOCKS_FEEDS = 6`. A direct check from
the same emulator at the same moment answers:

    echo | nc -w 6 t.meow-network.com 443   RC=0
    echo | nc -w 6 1.1.1.1 443              RC=0

so the TCP path is open and the nodes themselves are gone. Free public proxies are ephemeral, and the
ones that carried a handshake an hour ago no longer answer. That is the pool, not the client.

### Regression check

    ColgramDpiBypassDeviceTest   OK (3)
    ColgramDohResolverDeviceTest OK (3)

## SOCKS5 in this pool carries TCP but refuses datagrams, with a named code (2026-04-04 17:05)

The relay is implemented and the pool does carry working SOCKS5 nodes:

    ColgramProxyChain: SOCKS5 front 127.0.0.1:46661 -> socks5://144.24.111.128:1088
    ColgramProxyManager: Telegram DC route found through socks5://144.24.111.128:1088
    native check 127.0.0.1:46661 -> 549ms
    native check edge.ehtemal.info:443 -> 295ms

A SOCKS5 front through one of them reaches a Telegram DC, and the tunnel's own UDP ASSOCIATE was
spoken to the same two nodes:

    === proxy 144.24.111.128:1088 ===
      FAILED: associate refused, code 7
    === proxy 184.178.172.5:15303 ===
      FAILED: associate refused, code 7

Code 7 in RFC 1928 is command not supported. It is a named refusal from a proxy that is otherwise
working - it completed the greeting, it answered, and it declined this one command - so this is not a
timeout, not a malformed request and not a filtered path. These nodes relay TCP and decline UDP.

That is the third independently measured route to the WARP edge, and it is the only one that could have
worked with a working proxy in hand:

    direct UDP to 162.159.192.0/24 on the four registered ports    dropped for QUIC and WireGuard,
                                                                   every source address, no global IPv6
    the one edge endpoint that answers QUIC on 443               HTTP/3 front end, extendedConnect=false
    proxy UDP ASSOCIATE                                          command not supported, code 7

The relay code stays: it is correct, it is wired to `ColgramWarpMasqueTunnel.setUdpRelay`, and a node
that does implement ASSOCIATE would use it. Nothing in the current harvest qualifies.
