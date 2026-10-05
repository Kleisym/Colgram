# The carrier carries UDP, and this client only ever sent TCP

Captured by instrumenting the reference client and reading what it actually writes. The reference returns
warp=on on this edge and this network; this client does not, and the reason is in two frames.

    H2 WRITE 91 bytes: 00404f 4500004f00004011 ...     proto 0x11 = UDP
    H2 WRITE 91 bytes: 00404f 4500004f00004011 ...     proto 0x11 = UDP

Decoded:

    WRITE UDP 45675->53 len=51
    WRITE UDP 58069->53 len=51

    UDP frames: 2
    TCP frames: 0

Not one TCP segment. Every packet the reference put through this carrier in the session that returned
warp=on was UDP to port 53 - a DNS query - and the answers came back the same way:

    READ  DATA 132 bytes   capsule=79
    READ  DATA 156 bytes   capsule=87

So this edge carries UDP. This client sends TCP and nothing else, because the only flow it builds is a
TCP peer:

    peer := newTunnelConn(tun, srcIP, dst, ephemeralPort(), 443)

and the SYN is answered and the flow acknowledged, which is what a tunnel terminating TCP at its edge does
with a segment it has agreed to relay - and then it forwards nothing, which is what it does with a flow it
has no session for. The ClientHello is not the fault. The transport is.

## What this explains

Every measurement this client has produced on this carrier has been a TCP flow:

    outbound 60 bytes flags 0x002   SYN            <- answered
    outbound 52 bytes flags 0x010   ACK            <- answered
    outbound 351 bytes flags 0x018  ClientHello   <- acknowledged, never forwarded

and every one of them has been read as "the edge reads the ClientHello and declines to forward on this
flow". It does not decline. There is nothing in the evidence that says it read the payload at all, and now
there is positive evidence that the only traffic it has been asked to carry in a session that worked was UDP.

The 414 bytes that came back once - an HTTP 400 from the origin - is consistent with that and not with the
other reading: a tunnel that carries UDP carries the DNS that resolves the name, and then the TCP flow that
follows is a different question, and this client has never been seen being given one that works.

## What follows from it

The UDP transport is not a second implementation to be written alongside the TCP one - it is the same
capsule, the same carrier, the same registration, and a different IP protocol number in one header byte.
This client writes 0x06 there and has never written 0x11.

The pieces that already exist for it: the capsule framing, the reassembly, the flow accounting, the
DoH resolver above it and the SOCKS front the reference exposes. What is missing is a UDP peer state
machine beside the TCP one - request id, source port, a matching rule for replies - and the session's own
DNS path to go through it, which is what the reference is doing in these two frames.
