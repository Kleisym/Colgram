# The WARP edge never acknowledges this client, and that changes the conclusion again

## The measurement

Counting frames in both directions against three Cloudflare edges, same code, same network, same
source address `192.168.0.4`:

```
edge               handshake  tx  rx  ACK frames  outcome
WARP MASQUE        0.187      4   4      0        terminated 0x174 @ 1.187s
cloudflare-quic.com 0.094     10  10      1        alive
1.1.1.1 resolver   0.094      9   8      1        alive
```

The two working edges acknowledge the client. The WARP edge does not acknowledge a single packet, and
then stops sending at 281 ms.

## Why this is the finding, not another detail

A QUIC endpoint acknowledges reliably delivered packets regardless of what it thinks of them. That
is the entire point of the acknowledgement frame - it reports receipt, not consent. An edge that
received this client's handshake and then stayed silent for the rest of the connection is not
refusing a request; it has stopped talking to a flow it recognised.

This dissolves the entire line of reasoning that ran through the previous sessions. The edge was
never evaluating a certificate, never judging a request shape, never applying an 781 ms deadline.
The sequence is:

```
0.094s  <- Retry                     the edge is talking
0.187s  <- 1200B, 943B  Handshake   the edge is talking
0.281s  <- 59B    H3 SETTINGS       the edge is talking, then stops
        (no ACK ever, for any packet)
1.187s  CRYPTO_ERROR 372             the client gives up on loss detection
```

The `0x174` was never the edge's answer. It is this client reporting that it stopped hearing back.
That is also why every layer of the connection looked immaculate - ALPN h3 negotiated, SETTINGS
received, connection ID issued, transport parameters generous - and then nothing.

## The earlier errors this supersedes

Several conclusions in this project were built on reading `0x174` as the edge's verdict. With the
acknowledgement behaviour in hand, none of them hold:

| Earlier claim | What the ACK count shows |
|---|---|
| the edge demands a client certificate | it never acknowledged the client's CertificateRequest, let alone answered it |
| the edge refuses this certificate with access_denied | the 305 came from the same silence, one RTT apart |
| the edge ignores the request shape | the request was never acknowledged, so it was never read |
| the edge applies a 781 ms deadline | the client applied a loss-detection deadline of 495 ms and died at 1.2 s |

Every one of those was a symptom of the same thing, read from the wrong side of the wire.

## What is actually happening

The WARP MASQUE edge completes the QUIC and TLS handshake with this client, then drops the flow
without acknowledging anything further. The other two Cloudflare edges, reached with identical code
over identical paths, behave normally. That is a filter keyed on something about this flow - not on
the port, not on the protocol, not on the destination, since all of those are shared with the working
edges.

**Correction, from a follow-up measurement.** The strong claim above does not hold. Running the same
edge with different certificates and different SNIs, the ACK count is not stable at zero:

```
cert = none                 rx=4  ACKs=0  last_rx=0.296  term=372 @ 1.218
cert = self-signed P-256    rx=5  ACKs=0  last_rx=0.328  term=305 @ 1.094
cert = registered P-256     rx=5  ACKs=0  last_rx=0.593  term=305 @ 1.375

SNI sweep, no certificate, one edge (162.159.198.2):
    consumer-masque...   ACKs=0
    engage...            ACKs=1     <- an acknowledgement does arrive
    one.one.one.one     ACKs=0
    cloudflare-quic.com  ACKs=0
```

So acknowledgements are not categorically absent - one row out of four received one - and with a
certificate the edge keeps talking for nearly twice as long, 0.593 s against 0.296 s. That is the
opposite of a filter keying on the certificate; if anything the edge examines it and continues.

The accurate statement is therefore weaker than the one above, and it is the one to carry forward:
the edge completes the handshake, sends a handful of packets including a SETTINGS frame, and then
the connection dies of loss detection on the client side within about a second. Whether the edge
stops because it chose to, or because packets on the return path are being dropped after the
handshake, is not established by these measurements. The two working Cloudflare edges, over the same
paths with the same code, keep the connection open, which localises whatever differs to this flow -
not to the port, the protocol, or the destination address.

## The ACK accounting, which is the part that holds up

```
no certificate
    from edge   : 0.093 Retry 95B | 0.187 1200B | 0.187 943B | 0.296 59B
    from client : Initial 1200B | 1200B | 1200B | 221B
    ACKs the client sent: 0        terminated 372 at 1.218s

with a self-signed certificate
    from edge   : 0.094 Retry 95B | 0.188 1200B | 0.188 943B | 0.344 59B | 0.360 59B
    from client : Initial 1200B | 1200B | 1200B | 690B
    ACKs the client sent: 1        terminated 305 at 1.125s
```

Two things in that data are solid.

The client does acknowledge, and only when it has a certificate. Without one it completes the
handshake and then suppresses the acknowledgement, which is the same TLS-level refusal that
produces 372 and 305. One symptom, not two.

And the edge's last packet is always the 59-byte control frame at 0.28-0.36 s. It never retransmits.
An endpoint waiting on an acknowledgement must retransmit; an endpoint that has stopped does not.
So the edge is not waiting for this client. It finished what it intended to send.

Keeping the flow busy changes nothing:

```
idle, no cert          ACKs=1  last_from_edge=0.282  edge_packets=4  term=372 @ 1.204
PING every 600ms       ACKs=1  last_from_edge=0.297  edge_packets=4  term=372 @ 1.203
H3 control stream x3   ACKs=1  last_from_edge=0.297  edge_packets=4  term=372 @ 1.219
```

The edge sends four packets, ends with SETTINGS, and goes quiet within a third of a second in every
case. The client's connection then dies of loss detection about 0.9 s later, because nothing further
arrives to acknowledge.

## What that leaves

The edge is not waiting for anything this client can send, and it is not refusing on a timer. It
delivers the handshake and its HTTP/3 settings and then closes the exchange. The two working
Cloudflare edges, over the same paths, keep going. The difference is in this flow's identity, and the
only identity known to work here is the one the desktop daemon builds at registration and seals under
DPAPI or a TPM.

What it could be keyed on, in order of what has not been ruled out:

1. The certificate this client presents, if any. The 116 to 49 difference is then the edge changing
   its verdict between "no identity" and "wrong identity" - consistent with a flow-level drop
   immediately after, rather than a TLS-level exchange.
2. The ClientHello contents. The working edges negotiate h3 successfully with the same ALPN, but they
   are different hostnames with different server configurations.
3. Something in the encrypted handshake that only a Cloudflare edge inspects, such as the token from
   the Retry or a QUIC-TLS extension.

The official client gets a tunnel on this network. It presents something this client cannot. Every
shape of that something has been tested except the one produced by the daemon.

## Where this leaves it

The network is fine and the tunnel edge is fine. The flow is dropped after the handshake, and the
termination code is the client's own loss detection reporting the silence. Nothing about the
certificate question, the request shape, the ALPN, or post-quantum matters until something in the
handshake is accepted - and the only known-good value for that is material held by the desktop
daemon under DPAPI or a TPM.

**No `warp=on` measurement exists. The tunnel does not work.**
