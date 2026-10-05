# The HTTP/2 carrier completes the handshake and forwards no data

Everything below is the client's own log, captured on the device, after `androidLog` was made to reach
logcat. Before that it wrote to stderr, which is why earlier rounds produced no lines at all.

## What crosses the tunnel, in order

    outbound 40 bytes,   flags 0x002, seq 1                    SYN
    outbound 40 bytes,   flags 0x002, seq 1                    SYN, retransmitted
    outbound 40 bytes,   flags 0x002, seq 1                    SYN, retransmitted
    outbound 40 bytes,   flags 0x002, seq 1                    SYN, retransmitted
    outbound 40 bytes,   flags 0x002, seq 1                    SYN, retransmitted
    inbound  44 bytes,   flags 0x012, seq 1877017208 ack 2     SYN-ACK
    outbound 40 bytes,   flags 0x010, seq 2     ack 1877017209  ACK
    inbound  44 bytes,   flags 0x012, seq 1877017208 ack 2     SYN-ACK, again
    outbound 40 bytes,   flags 0x010, seq 2     ack 1877017209  ACK, again
    h2 peer open, sent=7 recv=2
    outbound 440 bytes,  flags 0x018, seq 2                    TLS ClientHello, part 1
    outbound 440 bytes,  flags 0x018, seq 402                  part 2
    outbound 440 bytes,  flags 0x018, seq 802                  part 3
    outbound 365 bytes,  flags 0x018, seq 1202                 part 4
    tls inside tunnel: i/o timeout (sent=11 recv=2)

The handshake is correct and complete. `ack=2` is right for a SYN sent at sequence 1 - a SYN consumes one
sequence number - and the first data segment starts at 2 for the same reason. Everything is where TCP
puts it.

## What is established by trying it and failing

The carrier **does** forward packets. A SYN leaves and a SYN-ACK comes back, four times, with matching
sequence numbers. That is the edge routing.

The carrier **does not** forward data for this flow. Not one byte comes back after the handshake, and:

- at 1140 bytes per segment - nothing
- at 440 bytes per segment - nothing
- to two different destinations - nothing
- through a fully provisioned account with a bound licence - nothing
- over three TCP ports, all of which answer CONNECT 200 - nothing

Segment size is not the cause. It was halved and the count did not move: `sent` went from 9 to 11 as the
ClientHello split into more, smaller pieces, and `recv` stayed at exactly 2.

## Why the reference client is not a contradiction

`usque` returned `warp=on` on this network over this same carrier, and that is what found it. It does not
implement a TCP stack. Connect-IP carries whole IP packets; something else terminates the flow. In the
reference that something is the operating system - the client hands packets to a TUN device and the
kernel's TCP does the rest.

In this app the peer state machine in `tunnelConn` *is* the TCP endpoint, because the tunnel is measured
inside the app before any TUN is installed. So the handshake is ours to get right and the data path is
ours to get right, and only the first one is finished.

## The measurement that separates what is left

Two candidates remain, and they are separated by one test:

1. The capsule form the edge accepts for data is not the one accepted for the request. The request shape
   was measured field by field against the working client; the data capsule was inferred. Both the short
   form and the generalised form with a context id were tried, and one returned a SYN-ACK, which proves
   the edge reads packets carrying **no** capsule header at all. That is a fact about the handshake and
   not about data, so it does not settle this.

2. The edge wants something in the flow this client does not send. The candidates are the target's own
   answer arriving on a port that was never negotiated, and the capsule carrying the flow's context.

The test that separates them: send one data segment to a destination that answers immediately - a host
that completes a connection with no data - and watch for the inbound. If the SYN-ACK-shaped reply arrives
and a RST or a payload does not, the capsule is wrong. If nothing arrives at all, the edge is not
forwarding this flow and the request is missing something.

That test is the next piece of work. It is one datagram and a log line, and it has not been run.

## Also fixed on the way here

- `tunnelConn`'s deadline setters were no-ops, so a TLS handshake on the tunnel waited forever and a
  tunnel that had already opened and already carried packets was reported as one that did not work.
- Honouring that deadline needed a timed wait, `sync.Cond` has none, and the first attempt raced the
  reader into `fatal error: mspan.sweep: bad span state` - a heap written by something other than the
  allocator. It selects on a channel now.
- The branch in `ColgramHttp` named direct was not direct: the app installs a SOCKS front on loopback as
  the platform proxy, and the front answered `SOCKS: Host unreachable` for an address the device reaches
  directly.
- Flow-control credit is returned for bytes taken off the HTTP/2 stream, and a received data segment is
  acknowledged. Neither changed the count, and both are correct TCP/HTTP that were simply missing.
