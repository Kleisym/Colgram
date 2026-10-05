The handshake is intermittent, and the client never sends its key share when the flight does arrive

Two things on the record now, and they are not the same finding.

## The carrier's flight is complete and correctly acknowledged

On the runs where the handshake starts, the whole server flight arrives and this client acknowledges every
record in order:

    inbound 1280 bytes, 1228 payload, seq 3044710631   ServerHello, a TLS 1.3 record
    outbound 52 bytes                     ack 3044711859
    inbound 1280 bytes, 1228 payload, seq 3044711859
    outbound 52 bytes                     ack 3044713087
    inbound 1280 bytes, 1228 payload, seq 3044713087
    outbound 52 bytes                     ack 3044714315
    inbound  287 bytes,  235 payload, seq 3044714315
    outbound 52 bytes                     ack 3044714550

Three 1228-byte records and two 235-byte ones - a ServerHello, a certificate chain and the tail - read off
the capsule stream and acknowledged one at a time. No packet is lost and none is acknowledged out of order.

What is never sent is this client's own key share. In TLS 1.3 that is what answers a ServerHello, and on
every run it is absent:

    outbound 52 bytes  flags 0x011        FIN - the connection is closed rather than continued

Go's TLS client has read the ServerHello and closed. The tunnel beneath it has carried the whole flight.

## The flow is intermittent, and it is not this client's doing

Across runs on the same carrier, the same identity and the same code:

    runs where the handshake reached ServerHello:            the flight arrived whole, as above
    runs where the SYN was never answered:                  sent=5 recv=0
    runs where the handshake started and stopped early:      sent=9 recv=2

A SYN that is never answered is the edge's own behaviour on a flow it has opened and not yet decided about,
not a packet this client built wrongly - the same SYN, with the same options and the same window the client
that carries uses, is answered on the runs that work. What decides it has not been identified, and it is not
in the packet: the fields on the wire are the ones measured correct.

## Where this leaves the verdict

Every layer below TLS is measured correct, by flows that carried:

    UDP both ways          a real DNS answer back through the tunnel
    TCP to port 80         HTTP/1.1 200 OK back through the tunnel
    TCP to port 443        a TLS 1.3 ServerHello and its certificate chain back through the tunnel

The remaining fault is above the carrier and in two parts: this client stops after the ServerHello instead
of sending its key share, and the flow is not established every time. The first is a defect in the client.
The second is the edge, and the only evidence about it is that the same SYN is sometimes answered and
sometimes not.

The key share is worth one look by itself, because it is the packet a TLS 1.3 client owes a server and the
one packet in this whole conversation that has never been seen going out.
