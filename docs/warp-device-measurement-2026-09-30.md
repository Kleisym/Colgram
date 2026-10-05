# WARP edge reachability, measured on the device

Device: MuMu emulator `127.0.0.1:16384`, Android SDK 35, x86_64, source 10.0.2.15.
Run through `dalvikvm` on a real DatagramSocket, not a shell utility.

## Why the shell method was discarded

`nc` on this device returned 0 bytes for every target, including ones that must answer:

```
nc -u 162.159.198.2 443   -> 0 bytes
nc -u 8.8.8.8 53          -> 0 bytes   (a public resolver)
nc    1.1.1.1 443         -> 0 bytes   (TCP, known reachable)
```

All three identical, and the last cannot be true, so the method was broken rather than the network.
`nc` was fed no input and gave up before any reply arrived. Every measurement below uses a real
socket with a real payload.

## QUIC Initial, 1200 bytes, from the device

```
162.159.198.2:443    Retry, 0xf0, 95 bytes, 109 ms
162.159.198.2:500    Retry, 0xf0, 95 bytes, 105 ms
162.159.198.2:8443   Retry, 0xf0, 95 bytes, 118 ms
162.159.198.2:8095   Retry, 0xf0, 95 bytes, 189 ms
162.159.192.1:443    silent   (engage / API edge)
162.159.192.3:2408   silent   (WireGuard edge)
1.1.1.1:443          silent   (control)
```

The control that decides whether this is real: DNS over UDP from the same device, same sockets.

```
8.8.8.8:53       A query -> 64 bytes, rcode 0 NOERROR    64 zero bytes -> 12 bytes, rcode 1 FORMERR
1.1.1.1:53       A query -> 64 bytes, rcode 0 NOERROR    64 zero bytes -> silent
77.88.8.8:53     A query -> 64 bytes, rcode 0 NOERROR    64 zero bytes -> 12 bytes, rcode 1 FORMERR
192.168.0.1:53   A query -> 64 bytes, rcode 0 NOERROR    64 zero bytes -> 12 bytes, rcode 4 NOTIMP
162.159.198.2:53 silent                                  silent
```

The 12-byte FORMERR in response to 64 zero bytes is the part that matters. A filtered path produces
nothing; an invented answer does not carry a correct DNS header. So the device's UDP egress works,
and the Retry packets from 162.159.198.2 are Cloudflare answering, not the emulator's NAT.

## What this changes

The claim that this network blocks all Cloudflare UDP is false, and it was false in a specific way: it
was generalised from 162.159.192.x, which is silent, to all of Cloudflare. Both the host and the
device reach 162.159.198.2 with QUIC Initials answered in under 200 ms.

The remaining wall is unchanged and is not the network. The edge requires a client certificate at the
TLS layer:

| Condition | TCP | QUIC |
|---|---|---|
| no certificate | alert 116 `certificate_required` | 372 = 0x100 + 116 |
| self-signed certificate | alert 49 `access_denied` | 305 = 0x100 + 49 |

That is reproducible on the host across six QUIC ports, six SNI values, eight certificate subjects,
and both registered and unregistered keys. The official client builds the certificate at runtime
from DPAPI-protected secrets held by its daemon; there is no file and no public endpoint that issues
it.

## No `warp=on` measurement exists

This measures the edge being reachable, which is the precondition. It is not a tunnel, and the tunnel
does not work. Device-side QUIC to the MASQUE edge succeeds; CONNECT-UDP is still unanswered because
the handshake never gets past the certificate.
