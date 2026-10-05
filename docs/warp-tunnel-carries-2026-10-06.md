# The tunnel carries. It returned HTTP/1.1 200 OK through this client's own flow.

The measurement, on this client and this carrier, on a flow of its own and before any TLS:

    colgram_masque: the plaintext flow opened (sent=7 recv=2)
    colgram_masque: first data on the flow is 488 bytes:
      48 54 54 50 2f 31 2e 31 20 32 30 30 20 4f 4b 0d 0a 44 61 74 65 3a 20 4d ...
      H  T  T  P  /  1  .  1     2  0  0     O  K  \r \n  D  a  t  e  :   M ...

488 bytes off the wire through a tunnel this client opened, on an identity it registered, with its own
P-256 key and its own bare certificate: a SYN answered, a request sent, and the origin's response read
back off the capsule stream and parsed.

That is the request shape the client measured to carry carries, and this client now does the same thing
and gets an answer. So the carrier relays TCP, this client's TCP peer is correct through the handshake and
the request, and every conclusion in this work that said the carrier would not forward on a flow was a
conclusion about a flow the client had not built correctly.

## What is left, and it is above the carrier

    h2 peer open, sent=7 recv=2
    h2 carrier failed: tls inside the tunnel failed: i/o timeout (sent=9 recv=3)

The same flow that carried 488 bytes of plaintext does not carry a TLS handshake. The difference is one
byte of protocol number - 6 for TCP either way, but port 80 against port 443 - and one layer above: the
request on the working flow is 110 bytes of HTTP, and the one on the stalled flow is a 299-byte TLS record.

So the fault is in what the edge does with a flow that opens and then speaks TLS, and it is not in the
capsule, the reassembly, the checksums, the handshake, the window or the registration - every one of those is
now measured correct on this carrier by a flow that carried.

## The next measurement

The reference client carries TLS on this same carrier when it is proxied rather than netstacked - curl through
its SOCKS front returned warp=on with kex=X25519 - and its own instrumented carrier was asked to relay a
real TCP flow and did. So the bytes it forwards for TLS exist somewhere; this client has not been able to
observe them because the front answers its own requests. Running the front with the frame logger and
requesting HTTPS through it puts a ClientHello the edge forwarded on the record, beside this client's, which
is the one comparison that has never been made and the only one left.
