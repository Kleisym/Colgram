# The SYN's window is the buffer in bytes, not divided by the scale it has only just announced

The reference client's own TCP conversation through this carrier, read out of its reassembled tunnel stream:

    WRITE  80<-37479  flags=0x002  win=24704  payload=0     SYN
           80<-37479  flags=0x010  win=193    payload=0     ACK      24704 / 128
           80<-37479  flags=0x018  win=4096   payload=110   request
           80<-37479  flags=0x010  win=4092   payload=0     ACK
    READ   37479<-80  flags=0x012  win=65535  payload=0     SYN-ACK
           37479<-80  flags=0x010  win=16     payload=0     ACK
           37479<-80  flags=0x018  win=16     payload=413   response
           37479<-80  flags=0x010  win=16     payload=0     ACK

The SYN carries 24704 and its first ACK carries 193, which is that buffer divided by the 128 the SYN's own
window scale option announces. The scale applies to the segments after the one carrying the announcement,
not to that one.

This client had it backwards, and it was this work that put it there: a window fix changed the capacity to
24704 and left the division in, so the SYN offered 193 and every later segment offered 1900. Both were wrong,
in opposite directions from the client that carries.

    mine before:  SYN win=193      ACK win=1900
    reference:     SYN win=24704    ACK win=193

The SYN is now the buffer whole, and the segments after it are the buffer divided by the scale in force:

    MY segments now:
      flags=0x002 payload=0    doff=40  win=24704  pkt=60
      flags=0x010 payload=0    doff=32  win=1900   pkt=52
      flags=0x018 payload=299  doff=32  win=1900   pkt=351

## The verdict still fails, and this narrows why

    h2 peer open, sent=7 recv=2
    h2 carrier failed: tls inside tunnel: i/o timeout (sent=8 recv=2)

So the window was a real difference and not the last one. What the capture above now makes visible is the
shape of the conversation that carries, end to end: SYN, ACK, a 110-byte request, a 413-byte response, and
acknowledgements on both sides - all carried, all on one tunnel, in the same session that returned warp=on.

This client's SYN now matches that one byte for byte. The handshake completes on both. Its data segment is
a well-formed 299-byte TLS record in a 351-byte packet with a checksum that folds to zero. What has not been
compared is the ClientHello itself against a ClientHello the edge has been shown answering, because this
carrier has never been shown answering one - the client that carries sends a 110-byte request where a
ClientHello is not needed, and the capture above contains no handshake at all beyond the TCP one.

That is the next measurement, and it is cheap: ask for the trace over plain HTTP through this tunnel, which
is the 110-byte request the reference already made, and read the body back. If the 413 bytes come back on
this client's flow, the tunnel carries and the remaining fault is in the TLS layer above it.
