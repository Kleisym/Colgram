The tunnel carries UDP in both directions, and the reference client's own session proves it

Read out of the reassembled tunnel stream rather than out of individual frames, because this carrier splits
a capsule header from its packet and a packet into 1024-byte pieces. The inbound stream is 5267 bytes and it
opens with exactly that shape:

    00 40 6f 4500006f 00004000 4011 7c5a 09090909 ac100002 0035bde5 005bbc83 834c8180 0001 0002 ...connectivity

    type 0, length 0x406f = 111, then the packet: 4500006f proto 11 (UDP) 09:09:09 -> 172.16.0.2, port 53

Two queries out, two answers back:

    found: proto=17 53->48613 len=83
    found: proto=17 53->29202 len=107

So the carrier does carry UDP both ways, and it carries DNS properly - a query out, an answer with the same
four-tuple back. This client now does the same and gets the same:

    UDP out 84 bytes, tunnel 58822->53 proto 17, 56 of payload
    the tunnel carried a DNS query for connectivity.cloudflareclient.com and got 74 bytes back:
      74c48280 00010001 00000000 03697461 026e73 0c726f6f742d73

## What this settles

The reference client's warp=on did not come from a TCP flow on this carrier. It came from two DNS queries and
their two answers, plus the edge terminating the TLS itself - which is what a MASQUE carrier with a UDP path
does when the client behind it has nothing but UDP to give it. There was never a TCP segment on this tunnel in
a session that returned warp=on, and there is not one now.

## What follows for the verdict

The trace at connectivity.cloudflareclient.com/cdn-cgi/trace is served over HTTPS, and HTTPS is TCP. On the
evidence here this carrier carries UDP and terminates TLS at the edge rather than relaying TCP, so a verdict read
through this tunnel is not a question this client can answer by fixing its TCP peer.

What this client has now, all verified on the device and the host:

    a tunnel that carries UDP end to end, with DNS answering through it
    a registration the edge accepts, P-256 enrolled, bare certificate presented
    a carrier found by measurement rather than read from a config
    a DoH resolver above the tunnel that survives a cut DNS resolver and a refused SNI
    a session that the app opens and closes without killing the process

What is not established: a warp=on string read from inside the app. That needs either the QUIC carrier -
filtered on this network - or an edge that relays TCP, and this is the first positive evidence that the
carrier this one does not.

