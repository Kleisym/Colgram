# WARP on this network: the complete picture

As of 2026-09-30. Every claim below is a measurement, and the ones that were wrong along the way are
named as wrong.

## The network is not the obstacle

Cloudflare's WARP MASQUE edge is `162.159.198.2`, and it is open. Six of the seven ports listed in
`conf.json` answer a QUIC Initial with a Retry, measured from both the host and the device:

```
host   192.168.0.4   Retry 0xf0, 95 B, 90-120 ms on 443 / 500 / 4500 / 4443 / 8443 / 8095
device 10.0.2.15     Retry 0xf0, 95 B, 100-189 ms on the same ports
```

The device side was confirmed with a DNS control, so it is not an emulator artefact: a well-formed
query to 8.8.8.8:53 returns `rcode 0 NOERROR`, and 64 zero bytes return a 12-byte `FORMERR`, which
only a live resolver produces.

Cloudflare's own tool agrees independently. `warp-diag.exe`, run with the WARP service stopped:

```
Testing H3 QUIC connectivity to 'https://cloudflare-quic.com/cdn-cgi/l4-stats'  ->  Successful
  version = HTTP/3.0, status = 200, server = cloudflare
  transport = QUIC, http = HTTP/3, lost = 0, retrans = 0
```

The block is per-destination, not per-protocol. The WireGuard edges are unreachable - twelve
destinations, all silent, including a real 148-byte initiation with a valid mac1 - and that is the
only reason MASQUE matters here. It is also why the official client is configured with
`tunnel_protocol: masque` on this machine.

## The edge receives everything except identity

Against `162.159.198.2`, from source `192.168.0.4`, with every datagram counted by wrapping
`QuicConnection.datagrams_to_send`:

```
get                     hs=0.156  sent=True  status=None  term=0x174@0.843
connect-udp             hs=0.141  sent=True  status=None  term=0x174@0.829

timeline (get):
  0.062  <- 95B     Retry
  0.140  <- 1200B   Initial
  0.140  <- 944B    Handshake
  0.156  ProtocolNegotiated / HandshakeCompleted
  0.218  <- 59B     H3 SETTINGS, ConnectionIdIssued
  0.843  ConnectionTerminated 0x174
```

So the edge gets a completed QUIC handshake with ALPN h3, the client's SETTINGS, a client control
stream, and a delivered request - and returns nothing, closing at 0.83-1.00 s. Holding the connection
for six seconds past the deadline changes nothing, so this is not a late answer.

The transport parameters the edge offers are the most generous of the three Cloudflare edges tested:

```
WARP MASQUE edge     bidi=25000  uni=100  maxdata=10000000  stream_bidi=1000000
cloudflare-quic.com  bidi=100    uni=3    maxdata=10485760  stream_bidi=0
1.1.1.1 resolver     bidi=100    uni=3    maxdata=10485760  stream_bidi=0
```

## The official client, from its own log

```
13:02:07.078  TLS handshake completed  P-256, post_quantum_enabled: false, sni: None
13:02:07.091  Established QUIC connection with 192.168.0.4:61608 ---> 162.159.198.2:443
13:02:07.108  creating new flow for MASQUE request
              ... 1.19 seconds with no log line at all ...
13:02:08.334  Connected to 162.159.198.2:443 @ 696f29 : FRA
```

The same edge, the same handshake, the same request - and a tunnel. The difference is the identity the
daemon presents, which is produced at registration and sealed by DPAPI under a SYSTEM-only ACL, or by
a TPM. It is not in the registration response, not issued by any public endpoint, and not in any file
on this machine: `warp.db` is 32 KB of SQLite with an empty `tpm_keys` table and no DPAPI signature,
both Cloudflare registry keys hold four scalar values and no blobs, and the public key points appear
in no task dump.

## Two walls, and they need different fixes

```
network   solved and measured - the edge answers from the host and the device
protocol  needs identity material only the desktop daemon holds
engine    Colgram's libbox has no masque outbound, and no released sing-box has one either
```

The engine wall is real and independent. Counting outbound types in the bundled `libbox.so`:
wireguard 26, shadowsocks 32, vmess 27, vless 24, trojan 24, tuic 23, hysteria2 25, naive 27,
shadowtls 25, anytls 35 - and masque 0, warp 0. The option struct exists upstream
(`MASQUEClientEndpointOptions` with `Path`, `Headers`, `Username`, `Password`, `Version`,
`DisableVersionFallback`, separate HTTP/2 and HTTP/3 option sets) and the implementation is complete
(`transport/masque/capsule.go` parses ADDRESS_ASSIGN, ADDRESS_REQUEST, ROUTE_ADVERTISEMENT with full
validation), but it first appears in `v1.15.0-alpha.9` and is absent from every released version.

## Corrections to earlier conclusions, all caught by measuring

| Earlier claim | Why it was wrong | Evidence |
|---|---|---|
| all Cloudflare UDP is blocked | generalised from 162.159.192.x | 162.159.198.2 answers Retry in 90-189 ms |
| 0x174 is a certificate error | arithmetic right, reading wrong | 8.47.69.0 with the same code stays alive |
| the certificate must carry a registered key | tested with the right fields | the key the server just stored is refused with 305 |
| post-quantum is required | never read the log line | `post_quantum_enabled: false` on the working connection |
| twelve request shapes were tested | they were buffered and never sent | sent inline they produce no datagram; +50 ms they do |
| the edge offers zero streams | attributes read before the handshake | bidi=25000 after it completes |

## What is left

One artefact: a client certificate bearing a `certificate_id` Cloudflare issued, obtainable only by
running the official daemon, which is off limits. Once it exists, the path from here is known - QUIC
handshake completes, ALPN h3, the edge waits, the request is delivered - and it is a matter of
minutes to reach the tunnel.

**No `warp=on` measurement exists. The tunnel does not work.**
