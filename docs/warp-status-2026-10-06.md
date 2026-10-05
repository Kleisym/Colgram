# What is measured, and what is left

Status at 2026-10-06 04:00. Every line below is off a device or off this host, on this edge, on this
network.

## The verdict

    h2 peer open, sent=7 recv=2
    h2 carrier failed: tls inside tunnel: i/o timeout (sent=8 recv=2)
    ColgramWarpVerdict: trace bytes=0

`warp=on` from inside the app is not achieved.

## What the edge does

Every inbound segment has zero payload. The SYN is answered, the ClientHello is acknowledged, and no
packet carrying a byte comes back. There is no ServerHello to drop, which closes the last two candidate
explanations at once - a fault in consuming it and a fault in what this client asks the edge to forward are
one fact now, not two.

## What is ruled out by measurement

| | how |
|---|---|
| the identity | the reference client on this client's enrolment returns warp=on |
| the account | the same run |
| the carrier | the reference client on its own enrolment returns warp=on, over the same edge and network |
| TLS through this carrier | the reference client carrying the tunnel while curl does TLS returns warp=on, kex=X25519 |
| packet size | 299-byte ClientHello in one segment, and split into 64-byte and 140-byte pieces, all answered with a bare ACK |
| the request shape | byte-identical to the reference, 71-byte HPACK block, CONNECT 200 |
| the capsules | length equals IP total length equals bytes present; both checksums fold to zero, per packet |
| the handshake size | the segment is 1525 bytes across two in Go's default and 470 in one with classical curves only |
| DNS and SNI | resolved over DoH through 1.1.1.1 with the SNI mask, both falling back when cut |

## The thirteen faults found

Five were faults of the harness and are the reason several earlier conclusions were wrong:

| fault | what it hid |
|---|---|
| the test flavour shipped arm64, the device is x86_64 | every measurement ran against the previous library |
| buildh2.ps1 ignored go build's exit code | three cycles measured the previous library |
| the inbound reader discarded the packet behind each capsule header | the edge forwarded for a week and it was thrown away |
| the acknowledgement never moved off the handshake value | the edge was told it had never been heard from |
| capsule lengths in the pre-RFC varint encoding | every packet over 63 bytes was corrupt |

Eight were differences from the reference, found by reading fields rather than outcomes:

| | was | now |
|---|---|---|
| options on a data segment | 20 bytes, the peer's own block echoed | 12, timestamps only |
| the first ACK after the handshake | bare, doff=20 | doff=32 |
| the SYN window | 200 units | 24704 |
| the window scale | divided by this side's 128 | read from the peer's answer, 13 |
| the initial sequence number | 1 | random |
| the source port | 51500 on every flow | ephemeral |
| IP id, TTL, DF | 0x4321, 64, unset | 0, 63, set |
| the certificate | signed against itself, so a CA | signed against an empty parent |

Every field of every segment this client writes is now the value a client measured to carry through this
carrier writes. Two differences remain and are named rather than hidden: the data-segment window is 1900
units where the reference offers 4096, and the reference's is unscaled because it does not scale at all.

## The one comparison never taken

The reference client never sends a TLS handshake over this carrier. Its log holds a 76-byte plaintext GET and
the 400 Bad Request that answered it. The TLS it carries is curl's, through the SOCKS front it exposes, and
those bytes have never been on the record in this client's transcript.

That capture is one run - point this client at the reference client's SOCKS front, ask for the same
handshake, and the edge's answer to a ClientHello and this client's ClientHello are in one transcript in
both directions. It would say whether the edge forwards TLS at all, and if it does, what about this
handshake it declines.
