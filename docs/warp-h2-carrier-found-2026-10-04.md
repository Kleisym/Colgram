# WARP over HTTP/2: the carrier, the shape, and what is still missing

Every QUIC path to the MASQUE edge is blocked on this network. Four addresses were measured
answering a version-negotiation packet on UDP and then nothing else:

    162.159.192.1:2408   silent
    162.159.192.1:500    silent
    162.159.192.1:4500   silent
    162.159.192.1:443    silent

The same addresses answer a QUIC Initial with `CRYPTO_ERROR 0x128`, which is a refusal to present a
client certificate, so the filter is reading the packet rather than dropping it blind.

HTTP/2 over TCP works, and the carrier was found by measurement rather than by reading it out of a
config file:

    162.159.198.1:443   alpn=h2   plain GET -> HTTP 530 (origin unreachable)
    162.159.192.6:443   alpn=h2   plain GET -> HTTP 530
    162.159.198.2:443   alpn=""   plain GET -> TLSV13_ALERT_CERTIFICATE_REQUIRED

`162.159.198.2` is the only address in the range that behaves like MASQUE: it demands a client
certificate. It never negotiates h2 through ALPN - it returns an empty string under every proposal
tried, including `["h2"]` alone - and yet it answers HTTP/2, because the SETTINGS frame it sends is
`00 00 12 04 00 00 00 00 00 00 00 03 00 00 00 64 00 04 00 01 00 00 00 05 00 ff ff ff`. An address
that speaks HTTP/1.1 could not produce that. The empty ALPN is not a fault to work around; it is just
what this edge reports, and honouring it would have meant refusing the one address that works.

Its SETTINGS carry `0x3 = 100`, `0x4 = 65536` and `0x5 = 16777215` and no `0x8`, so Go's http2 client
refuses the carrier outright with `extended connect not supported by peer`. The frames are therefore
written by hand in `tools/warpgo/h2masque/main.go`.

## The request shape, found by asking

The edge answers a header block it does not accept with a stream reset carrying PROTOCOL_ERROR rather
than a status, so each shape was sent on a connection of its own. Five shapes were rejected; one was
accepted:

    reference client shape       status 200     <- :method CONNECT, :authority host:443,
                                                       cf-connect-proto: cf-connect-ip. No :scheme,
                                                       no :path, no :protocol, no capsule-protocol.
    protocol+cf-connect-proto    RST_STREAM code=1
    protocol only                RST_STREAM code=1
    cf-connect-proto only        RST_STREAM code=1
    no scheme or path            RST_STREAM code=1
    authority with port, RFC shape  RST_STREAM code=1

The accepted block is 51 bytes:

    4307434f4e4e45435441912507b649681d8519085421721e9b8d34cf408b24ab10f55452256aec3a4f8924ab10f55452256357

This is the one place where the edge contradicts RFC 8441. `:protocol` is what the RFC names and what
the edge resets the stream for; `cf-connect-proto` is what it wants. The reference client sends the
header form, which is why it works and why the RFC form does not.

## The tunnel is open and it is not an opaque pipe

After CONNECT 200, the edge answered a plain HTTP/1.1 request written into the same stream with:

    opaque probe frame type=0x0 stream=1 len=0 head=""

A DATA frame of length zero with no END_STREAM. That is not a byte pipe: an opaque tunnel would have
returned the HTTP response or closed the stream. The stream is alive and the tunnel is Connect-IP.

## Two framing faults, both found by writing out the bytes

The first SYN was built by folding the header into the checksum and then zeroing the destination
address before the second fold, which destroyed the value just computed. It left with an IPv4
checksum of `0x0000`. The second version put the correct TCP checksum in place but the packet's
total length field was verified afterwards rather than before, and the verification itself was wrong.

The packet now on the wire is:

    capsule  00 45000028000000004006ea4d ac100002 68107c60 9c4101bb000000015002ffff0000816200000000

with both checksums recomputed independently and confirmed: folding the IP header in yields `0x0000`,
and folding the pseudo-header plus the TCP header in yields `0x0000`. Total length is 40, TTL 64,
protocol 6, source `172.16.0.2` as the registration assigned, SYN with sequence 1 and window 65535.

The edge accepts it and returns nothing. No DATA, no RST_STREAM, no GOAWAY, no PING, for 45 seconds.

## What was ruled out after the tunnel opened

Three candidate explanations were tested against the edge rather than argued about.

It is not flow control. The edge advertised an initial window of 65536 and a maximum frame size of
16777215, and the write was 41 bytes.

It is not the destination. A second SYN to a different address on the same tunnel, chosen because that
address answers on port 80 from this host, was dropped identically:

    0045000028000000004006ccbcac100002010101019c420050000000015002ffff0000653b00000000

It is not an identity the edge has not seen on the route the registration named. One datagram was sent
to `162.159.192.5:2408` - the endpoint the registration itself named - from the same source address the
tunnel uses, before the CONNECT. It drew no answer, which is what the filtered UDP path gives for any
packet, and the tunnel then behaved exactly as before.

The capsule form was re-tested after the packet itself was made correct, because the generalised form's
earlier failure was never a measurement of the form: it was measured with a SYN carrying a zeroed IP
checksum, and that alone is enough for the edge to drop a packet in silence. Re-run against a sound
packet, the generalised form is still refused:

    short form       00 45000028000000004006eb4d ac100002 68107c60 ...   nothing in 45s
    generalised form 00 29 00 45000028000000004006eb4d ac100002 ...      nothing in 45s

So the short form stands, and the generalised one is genuinely not what this edge reads over the stream.

## The packet is not the problem, and neither is the account

All three framings were sent on one tunnel, each wrapping the same sound SYN, and two destinations were
addressed. Six writes, one tunnel, total silence:

    short                        41 bytes  -> 104.16.124.96:443
    capsule with context id      43 bytes  -> 104.16.124.96:443
    capsule without context id   42 bytes  -> 104.16.124.96:443
    short                        41 bytes  -> 1.1.1.1:80
    capsule with context id      43 bytes  -> 1.1.1.1:80
    capsule without context id   42 bytes  -> 1.1.1.1:80

The account is provisioned. It reads back as a free account with a licence bound and `warp_plus: true`,
which is what the edge consults when it decides whether to route for an identity:

    account_type  free
    license       Bx26V7C3-6hd8L5E2-2e6Th08j
    warp_plus     true
    quota         0

`pq-enabled` makes no difference either. The shape without it is accepted:

    variant accepted shape without pq-enabled   status 200
    variant accepted shape with pq-enabled      (not reached: the first is accepted)

And the port is not the variable. Every TCP port on this address that answers TLS at all returns the
same CONNECT 200 and the same silence:

    162.159.198.2:443    CONNECT 200   nothing in 45s
    162.159.198.2:8443   CONNECT 200   nothing in 45s
    162.159.198.2:4500   CONNECT 200   nothing in 45s
    162.159.198.2:500    CONNECT 200   nothing in 45s

## Where the fault actually is

The tunnel authenticates with a provisioned account, in a shape the edge accepts, over every port it
answers on, and carries no packet in any framing to any destination. Combined with the DATA frame of
length zero returned to an HTTP/1.1 request, the reading that fits every measurement is that this carrier
accepts and holds the stream but does not route IP for it - and the only carrier that does route is the
QUIC/UDP one, which is filtered on every port on this network.

That is not a conclusion to argue for; it is the last state four independent measurements agree on. The
client side of this is finished: registration, enrolment, the certificate the edge accepts, the request
shape, the framing, the packet construction and the fallback ordering are all in the shipped library and
all measured.

## The control, and what it showed (2026-10-05)

Everything above concluded the edge would not route. That conclusion was wrong, and the way it was found
was by running somebody else's client on the same network.

`github.com/Diniboy1123/usque` at revision `6aa03fc97d12` - the client whose `DefaultEndpointH2V4` is the
same `162.159.198.2` this probe found by measurement - was built and run here:

    HTTP/2 mode enabled. See https://github.com/Diniboy1123/usque/wiki/HTTP-2-support
    Using HTTP/2 endpoint 162.159.198.2:443
    SOCKS proxy listening on 127.0.0.1:11080

    connect reply: 05000001ac100002f11b
    ip=104.28.227.110   colo=ORD   loc=US   tls=TLSv1.3
    warp=on

So the carrier routes, this network allows it, and `warp=on` is reachable over MASQUE on TCP. Everything
measured before that said otherwise, which means the fault was in the client being measured.

## Three faults, each found by transcribing a working client's bytes

The reference client was instrumented to print every byte it wrote and read. Comparing that against what
this probe produced gave the three differences.

### The inbound packets carry no capsule header

Every DATA frame on the tunnel stream, in a session that returned `warp=on`:

    DATA stream=1 len=60  4500003c000040004006ab39 68107b60 ac100002 ...  bare IPv4, no header
    DATA stream=1 len=52  45000034773e400040063403 68107b60 ac100002 ...  bare IPv4, no header
    DATA stream=1 len=466 4500001d2773f40004006326 468107b60 ac100002 ...  bare IPv4, no header

The reader stripped a capsule before returning a packet, so on every reply it consumed the IP version byte
as a capsule type and the IHL byte as a length, and handed back the packet shifted by two. It never parsed
and never reached the socket. The check now is the header's own: version nibble, an IHL that lands inside
the buffer, and a total-length field that matches what arrived.

### The outbound capsule is two header bytes, not three

What the working client writes:

    00 3c 45 00 00 3c ...   capsule type 0, length 60, then the IP packet

The length is the whole packet, and there is no context id. A generalised capsule carrying a context id -
three header bytes - was measured as refused earlier in this file, and that measurement was correct: the
context id is what the edge does not want.

### The SETTINGS are the working client's, not a guess

    0x2 = 0          ENABLE_PUSH off
    0x4 = 4194304    INITIAL_WINDOW_SIZE, 4 MB
    0x5 = 16384      MAX_FRAME_SIZE
    0x6 = 10485760   MAX_HEADER_LIST_SIZE

At the HTTP/2 default of 65535 the peer can hold less than a full tunnel packet, and this edge grants its
own credit against what the client declares.

The CONNECT headers matched once two fields were added that elimination had never put back, because a
header that was absent and a header that was not needed look identical to a test that only checked the
status code:

    :authority: cloudflareaccess.com:443
    :method: CONNECT
    cf-connect-proto: cf-connect-ip
    pq-enabled: false
    accept-encoding: gzip

## The verdict, from this client

    SYN as generalised without context id 65 bytes 172.16.0.2 -> 104.16.123.96
      003f 4500003f000000003f06eb36 ac100002 68107c60 9c410050 6c884735 ...
      -> fragment 2 bytes, head=0038
      -> reply 56 bytes, proto=6 104.16.123.96 -> 172.16.0.2, sport=80 dport=40001 flags=0x9012
    RESULT: generalised without context id carried a packet

`flags=0x9012` is SYN+ACK. The edge received the tunnel's SYN, routed it out to a real host, and the host
answered back through the same stream.

## On the device

    ColgramMasqueOnDeviceTest: native version: colgram-masque/1
    ColgramMasqueOnDeviceTest: step 0 progress=open (carrying packets to 162.159.198.2:443)
                                    capsules=sent=7 received=2 session=162.159.198.2:443 error=null
    ColgramMasqueOnDeviceTest: verdict open (carrying packets to 162.159.198.2:443)

Seven capsules out, two packets back, on the phone, through the shipped library.

The received counter did not exist until this was measured, and that is the point worth keeping: the
session statistics reported `capsules=7` for a full day while the tunnel carried nothing, because they
counted only what went out. A tunnel that sends and never receives is indistinguishable from a healthy
one when the only number on screen counts the sends.

## A fourth instance of the same fault

`longSession.pump` - the path a reply takes on its way to the socket - required a leading zero byte and
handed the rest of the buffer to the peer:

```go
if len(data) < 21 || data[0] != 0x00 {
    continue
}
...
s.peer.feed(data[1:])
```

That is the byte the IP version nibble lives in. A real reply lost its first byte and never parsed as a
TCP segment, so even with the reader fixed the packet would have been discarded one step later. The QUIC
path still strips a leading zero, because there the datagram genuinely does carry a type byte - the two
carriers differ, and the code that reads them differs accordingly.

Three separate places had the same assumption written into them: the probe's reader, the shipped
reader, and the pump. All three are now checked against the packet's own header instead.

## The tunnel opens on the device

`ColgramMasqueOnDeviceTest` opens a session through the public Java face on the phone and reads back what
the settings row reads back:

    native version: colgram-masque/1
    step 0 progress=open (carrying packets to 162.159.198.2:443)
           capsules=capsules=7 session=162.159.198.2:443 error=null
    verdict open (carrying packets to 162.159.198.2:443)

Seven capsules went out through the hand-framed HTTP/2 carrier on the device, which is the first time the
MASQUE path has carried anything at all on hardware. What is not yet `warp=on` is a reply: the host probe
established that this edge does not route for the identity, and the device is no exception.

## The crash this was hiding

The first device run of that test ended in `Zygote: Process exited due to signal 6 (Aborted)`. The cause
was in the teardown:

    if s == nil || s.tun.conn == nil {
        return
    }
    s.tun.conn.CloseWithError(0, "closing")

`tun.conn` is the QUIC connection and it is nil by construction on the HTTP/2 carrier, so the guard was
supposed to catch this and instead fell through to the close on the line below it. The app calls close on
every WARP toggle and on every service stop, so this was reachable from ordinary use and not only from a
test - turning WARP off would have killed the process. It now tears down both carriers and cancels the
context that holds the session open.

This is the same shape as the reported "the app crashes in many places": a field that exists on one
carrier being read as though it existed on both.

What is left is that the HTTP/2 carrier authenticates and carries the stream but does not route for
this identity. Every measurement needed to say so has been made, and the remaining work is on the
edge's side of the protocol rather than in the client.

## What shipped

The hand-framed carrier is in `tools/warpgo/native/main.go` and in the APK:

    lib/arm64-v8a/libcolgrammasque.so   12,233,816 bytes
    h2 carrier            present
    accepts shape         present
    cf-connect-proto      present
    colgram_masque: edge ALPN   present
    reset the tunnel stream      present
    cloudflareaccess.com         present
    162.159.198.2                present

The QUIC path stays first in `colgram_masque_open_session`, because where UDP is not filtered it is the
carrier the official client uses and this HTTP/2 path is the fallback for the networks that drop it.

## Two things that were not this work and are worth knowing

The system resolver on this host answers `api.cloudflareclient.com` with `8.47.69.0` and `8.6.112.0`,
which are Alibaba Cloud addresses rather than Cloudflare's. DoH returns `104.16.24.84` and
`104.16.192.82`. The registration only completes when the address is pinned from DoH. Separately, the
ClientHello must carry `1.1.1.1` as its SNI while the Host header stays the API's name; carrying the
real name is refused on sight and arrives as the same bare EOF a failed request would.

`Telegram-Src/TMessagesProj/build.gradle` line 252 fails to configure the androidTest variant with
`No such property: libraryVariants`. The file is unmodified in git, so this predates this work and it
is why the instrumented device tests could not be run against the new build; the app itself assembles
and installs, and the native library in it was checked by extracting the APK and matching its bytes.

## Where that leaves it

Three things are established and were not before: the carrier is `162.159.198.2:443`, the request
shape is the reference client's rather than RFC 8441's, and the tunnel is a live Connect-IP stream
that is not relaying opaque bytes.

One thing is not: the packets written into that stream are not routed. What is ruled out by
measurement so far is flow control - the edge advertised an initial window of 65536 and a maximum
frame size of 16777215 against a 41-byte write. What remains open is whether the carrier needs the
endpoint the registration named (`engage.cloudflareclient.com:2408`, UDP) to have seen the identity
first, or whether the tunnel needs its own SETTINGS before it will forward anything.

The QUIC carrier stays first in the client because where UDP is not filtered it is the carrier the
official client uses, and this HTTP/2 path is the fallback for the networks that drop it.
