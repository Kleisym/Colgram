# What the device's UDP filter actually keys on

## Before this: what had been assumed

Everything up to here was explained by "the edge answers QUIC from one egress and drops the same
handshake from another", then refined to "UDP 443 to Cloudflare's range is filtered". Both were
inferences from QUIC results. Nothing had sent a non-QUIC datagram to the same addresses to see
whether they answered.

## The measurement

One datagram per address and port, with a well-formed DNS query as the payload - a bare string is not a
DNS message and a resolver is entitled to ignore it, so a timeout would have proved nothing.

    1.1.1.1:53           ANSWERED 64 bytes in 286ms     <- real DNS response
    8.8.8.8:53           ANSWERED 64 bytes in 109ms
    94.140.14.14:53      ANSWERED 64 bytes in 101ms
    1.0.0.1:53           ANSWERED 64 bytes in 103ms

    1.1.1.1:54           no answer in 3001ms
    1.1.1.1:5353         no answer in 3001ms
    1.1.1.1:443          no answer in 3002ms
    162.159.198.2:443    no answer in 3001ms
    162.159.198.2:53     no answer in 3001ms
    104.16.0.1:53        no answer in 3002ms
    104.16.0.1:443       no answer in 3002ms
    8.47.69.0:443        no answer in 3004ms

## What that pins down

It is a **port filter with a carve-out for 53**, not an address filter and not a protocol
inspection.

-   Same address, 53 answers, 54 does not. A filter that only knew the address could not tell those
    apart.
-   Same address, a real DNS query answers, a QUIC Initial does not. So it is not looking inside
    the packet either - 54 was sent the same DNS payload and got nothing.
-   1.1.1.1 is reachable, 1.1.1.1:443 is not. Nothing about Cloudflare's addresses is blocked.

The port 53 carve-out is why the client could register at all: the API call is HTTPS to 443 and it
succeeded, so TCP is a different story - which the earlier DoH resolver work already assumed and this
confirms from the other side.

## Why this does not open a path

MASQUE needs a bidirectional UDP flow to the edge. The ports it answers on were measured - 443, 500,
4500, 4443, 8443, 8095 - and none of them is 53. A client cannot move its QUIC flow onto 53 and
expect the edge to be there: the edge does not serve QUIC on 53, and re-encapsulating QUIC inside DNS
to a resolver that is not a resolver would be talking to a different server entirely.

So the remaining gap is a network property, and it is now described precisely enough to state what
would close it:

    anything that carries the edge's UDP 443 flow over TCP, or over port 53, or from an egress the
    filter does not cover

The in-app relay covers the last one, and works - it is what produced warp=on on the host. On this
device the relay's own upstream goes out to the same filtered port.

## Verified

    tools/warpgo/udpprobe2    one datagram, address:port, real DNS payload, three seconds
    device 10.0.2.15, seven addresses, five ports
