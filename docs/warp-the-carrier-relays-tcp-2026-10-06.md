The carrier relays TCP in both directions, and the verdict can be read through this tunnel

The last claim in this work was that it does not, and it is wrong. It was measured by counting TCP packets in
a run where the client had asked the tunnel for nothing but DNS - the SOCKS front answered its own request, and
two UDP datagrams were the only thing on the tunnel. That showed what the tunnel was used for. It did not show
what the carrier will carry.

Asked properly, with the reference client in port-forward mode and a real TCP flow through it:

    WRITE  off=2733  80<-37479  flags=0x002  payload=0      SYN
           off=2795  80<-37479  flags=0x010  payload=0      ACK
           off=2850  80<-37479  flags=0x018  payload=110    request
           off=3014  80<-37479  flags=0x010  payload=0      ACK
    READ   off=4253  37479<-80  flags=0x012  payload=0      SYN-ACK
           off=4370  37479<-80  flags=0x018  payload=413    response
           off=4912  37479<-80  flags=0x010  payload=0      ACK

A full TCP conversation, both directions, carried by the same carrier this client registered on. The
handshake completes, the request goes out, 413 bytes come back, and both sides acknowledge.

So the fault was never that the carrier would not relay TCP. It is in this client's TCP peer, and the
measurement that pointed at the transport was one where the transport was never asked.

## What this settles

Every conclusion in this work that rested on "the edge reads the ClientHello and forwards nothing" was a
conclusion about a flow the client had not correctly set up, read off a carrier that demonstrably relays
TCP. The thirteen faults it did find were real - the build that measured an older library, the discarded
packet, the frozen acknowledgement, the wrong varint, the bare header, the twenty option bytes - and none of
them was the last one.

The verdict is reachable through this tunnel, over TCP, and the remaining work is the TCP peer rather than
the transport.
