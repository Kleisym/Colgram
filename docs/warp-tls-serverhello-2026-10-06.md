The tunnel carries TLS. The edge answers the ClientHello, and the handshake stops after ServerHello.

Four runs on this carrier, with a plaintext flow and a TLS flow of their own before the verdict:

    the tunnel carried a DNS query for connectivity.cloudflareclient.com and got 92 bytes back
    the tunnel carried no plaintext HTTP request back
    TLS on 443 through the tunnel: handshake on 443 (sent=9 recv=2): i/o timeout
    first data on the flow is 488 bytes: 48 54 54 50 2f 31 2e 31 20 32 30 30 20 4f 4b 0d 0a ...
      H  T  T  P  /  1  .  1     2  0  0     O  K
    first data on the flow is 1228 bytes: 16 03 03 04 ba 02 00 04 b6 03 03 62 3e 28 45 50 2d ...
      |  |  |  |  |  |  `-- TLS record, type 0x16, version 1.3, length 1208

`16 03 03 04 ba` is a TLS 1.3 ServerHello, and 1228 bytes is a ServerHello with a key share and a
certificate chain. The edge forwarded this client's ClientHello, the origin answered, and the certificate
came back through the tunnel in three packets.

So the carrier relays TLS, and this client's TLS handshake gets as far as the server's answer. What it does
not do is finish: the handshake times out with sent=9 recv=3, and the exchange stops there in all four runs.

## Where that leaves the work

Every layer below TLS is now measured correct on this carrier, by flows that carried:

    UDP, both ways, a real DNS answer back through the tunnel
    TCP to port 80, HTTP/1.1 200 OK back through the tunnel
    TCP to port 443, TLS 1.3 ServerHello with a certificate chain back through the tunnel

What is missing is the rest of the handshake: this client has sent its ClientHello, read the ServerHello's
record, and then stopped, with three packets received. A TLS 1.3 client answers a ServerHello with its own
key share for the server's group, and that is a packet this client has not been observed sending on any of
the four runs.

That is the next thing to look at, and it is in the layer above the carrier rather than in it. The evidence
for it is the count rather than the bytes: recv=3 is the SYN-ACK, an acknowledgement, and one record, and
sent=9 is the handshake this client believes it completed - nine segments to three received is a conversation
that stopped after the server's first flight.
