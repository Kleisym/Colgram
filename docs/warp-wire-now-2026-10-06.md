# What the wire looks like now, and what is left

Every segment this client writes, from the device's own log at 03:24 on 2026-10-06, with the option count
it reports:

    outbound 60 bytes (opts 20) flags 0x002  SYN
    outbound 52 bytes (opts 12) flags 0x010  ACK
    outbound 52 bytes (opts 12) flags 0x010  ACK

against the reference client's own log for a session that returned warp=on on the same edge and the same
network:

    reference SYN   win=24704  doff=40  ttl=63  opts 02 04 04 d8 04 02 08 0a ... 01 03 03 07
    this client SYN  win=193    doff=40  ttl=63  opts 02 04 04 d8 04 02 08 0a ... 01 03 03 07
    reference ACK             doff=32  win=193   opts 01 01 08 0a
    this client ACK           doff=32  win=1900  opts 01 01 08 0a

Two differences remain and both are named rather than hidden: the data-segment window is 1900 units where
the reference offers 4096, and the reference's data window is unscaled because it does not scale at all.
Neither has moved the verdict.

The verdict still fails at the same point:

    h2 peer open, sent=7 recv=2
    h2 carrier failed: tls inside tunnel: i/o timeout (sent=8 recv=3)

The edge reads the ClientHello, acknowledges the segment it arrived in, and forwards nothing.

## The faults found by putting this client next to the reference

Seven differences in the segments carrying the tunnel, each visible only by reading fields rather than
outcomes:

| what | this client was | reference |
|---|---|---|
| options on a data segment | 20 bytes, the peer's own block echoed | 12, timestamps only |
| the first ACK after the handshake | bare, doff=20 | doff=32 |
| the SYN window | 200 units, about 25 KB | 24704 |
| the data window scale | divided by its own 128 | the peer's 13 |
| the initial sequence number | 1 | random |
| the source port | 51500 on every flow | ephemeral |
| IP id, TTL, DF | 0x4321, 64, no DF | 0, 63, DF set |

and the certificate, which was signed against itself rather than against an empty parent, which is what
makes Go add the basic-constraints extension marking it a CA.

## Why the shape of the fault is now known

The edge was measured forwarding this client's own traffic for a whole week while a reader discarded the
packet behind each capsule header - so a tunnel which cannot route and a tunnel whose output is thrown away
are the same thing from outside, and every conclusion drawn from the outside was the second one.

Four role swaps, each moving the fault from the edge to this file:

    reference client, its own enrolment                          warp=on
    reference client, this client's enrolment                    warp=on
    this client, the reference client's enrolment                stalls
    reference client doing the TLS, a real client finishing it   warp=on, kex=X25519

## What has not been compared

The reference client never sends a TLS handshake over this carrier. Its log holds a 76-byte plaintext GET
and a 400 Bad Request; the 414 bytes that came back on the one carrying run were that exchange. The TLS it
carries is curl's, from a library whose ClientHello has never appeared in this client's own transcript - so
there is no capture of a ClientHello that this edge forwarded, and this one has never been compared against
it.

That capture is one run: the reference client's SOCKS front terminates a TCP flow that curl opened through
this same carrier, so pointing this client at that front and asking for the same handshake puts the edge's
answer to a ClientHello and this client's ClientHello in one transcript, in both directions.
