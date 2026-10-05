# What the reference client actually puts through this carrier

Measured by instrumenting its carrier and reading every frame both ways, from a session that returned
warp=on on this edge and this network at 04:41 on 2026-10-06.

    DATA frames by protocol, both directions:
      WRITE proto=17 -> 2

    WRITE UDP 60382->53 len=51
    WRITE UDP 44335->53 len=51

Two packets. Both UDP, both DNS to port 53, both outbound. Nothing inbound at all, and no TCP in either
direction - not the SYN, not the ClientHello, not a single segment.

## What that means, and it is a correction

curl asked for https://connectivity.cloudflareclient.com/cdn-cgi/trace through the SOCKS front, the trace
came back with warp=on, and the tunnel carried two UDP packets and no TCP whatsoever.

So the TLS handshake that produced that warp=on did not cross this carrier. The edge terminated it, or the
connection did not go through this tunnel at all, or the trace came from a path this carrier was not on.
What is on the record is that this carrier was asked for DNS over UDP and carried it, and was asked for
nothing else.

## Why this matters for this client

The tunnel now carries UDP, verified:

    UDP out 84 bytes, tunnel 58822->53 proto 17, 56 of payload
    the tunnel carried a DNS query for connectivity.cloudflareclient.com and got 74 bytes back:
      74c48280 00010001 00000000 03697461 026e73 0c726f6f742d73

A real DNS response - header 0x74c4, QR set, one answer record, the name servers for net. inside it -
arriving through a tunnel this client opened, on an identity it registered itself.

And its TCP path still gets nothing:

    h2 peer open, sent=7 recv=2
    h2 carrier failed: tls inside tunnel: i/o timeout (sent=8 recv=2)

The SYN and the ACK are answered and the ClientHello is acknowledged and not forwarded, which is what a
carrier does with a TCP flow it has no session for. Whether it would ever open one for a TCP payload is
not established by anything here, because nothing here has ever asked successfully - including the client
that works.

## Where the verdict has to come from, then

connectivity.cloudflareclient.com serves its trace over HTTPS, and HTTPS is TCP. If this carrier does not
carry TCP - and the only client measured carrying anything on it carries only UDP - then a verdict read
through this tunnel is not reachable, and every hour spent on the TCP peer has been spent on a path the edge
has never shown anyone.

What would establish it, in order of cost:

1. Ask the edge directly whether it will carry TCP at all, by opening a TCP flow on the reference client and
   reading its transcript. Its own netstack path is what curl used, so one curl request through it should
   produce TCP frames - and in the run above it produced none, which is the first evidence that the SOCKS
   front bypasses this tunnel for TCP rather than failing to relay it.

2. Read the verdict over a protocol the carrier does carry. The trace is served on HTTPS only, so this means
   either an HTTP port that answers, or the QUIC carrier, which is filtered on this network.

3. Leave the tunnel carrying what it carries and read the verdict from outside it - which is what the
   reference client does, and what this client now does too: its tunnel carries DNS, its registration is
   real, and whether that identity is what makes the trace say warp=on is a question about the edge rather
   than about the tunnel.
