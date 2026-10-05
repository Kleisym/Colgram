# warp=on, read from inside Colgram on the device

    ColgramWarpVerdict:   CF-RAY: a46034794ef67a9d-ORD
    ColgramWarpVerdict:   fl=1273f91
    ColgramWarpVerdict:   h=connectivity.cloudflareclient.com
    ColgramWarpVerdict:   ip=104.28.227.110
    ColgramWarpVerdict:   uag=colgram-warp-on
    ColgramWarpVerdict:   colo=ORD
    ColgramWarpVerdict:   http=http/1.1
    ColgramWarpVerdict:   loc=US
    ColgramWarpVerdict:   tls=TLSv1.3
    ColgramWarpVerdict:   sni=plaintext
    ColgramWarpVerdict:   warp=on
    ColgramWarpVerdict:   gateway=off
    ColgramWarpVerdict:   kex=X25519

    org.colgram.core.ColgramWarpVerdictDeviceTest:.
    Time: 55,844
    OK (1 test)

The user agent is the one this client sends, so the request went through the code in this repository and
through a tunnel this client opened - not through a path that happened to be working. The exit address is a
Cloudflare one, and the carrier reported ORD.

## The last fault

Read held the connection lock for its whole wait, and feed takes the same lock to append what arrived and to
acknowledge it - by writing a capsule, which can block on the stream. A reader holding that lock across its
wait was waiting for a lock the writer could not take, so Go's TLS never saw the ServerHello that was already
in the buffer:

    inbound 1280 bytes, 1228 payload, seq 3044710631   the whole flight arrives
    outbound 52 bytes                     ack 3044711859
    outbound 52 bytes  flags 0x011        FIN - Go gave up here with a read timeout

The lock is taken and released around each poll now. It reads the buffer, the end flag and the deadline, all
under the lock on every pass, so a poll sees the same state - but it does not hold the lock while waiting.

## What this client now carries, all measured

    UDP both ways      a DNS answer back through the tunnel
    TCP to port 80     HTTP/1.1 200 OK back through the tunnel
    TCP to port 443    a completed TLS 1.3 handshake
    and then          the trace body, saying warp=on

Fourteen faults, each found by measuring rather than reasoning. The first five were faults of the harness,
and they are why several earlier conclusions about this carrier were wrong:

    the test flavour shipped arm64 and the device is x86_64       every measurement ran on the old library
    buildh2.ps1 ignored go build's exit code                     three cycles measured the old library
    the reader discarded the packet behind a capsule header      the carrier forwarded for a week
    the acknowledgement never moved off the handshake value      the carrier thought it was unheard
    capsule lengths in the pre-RFC varint encoding             every packet over 63 bytes was corrupt

Then eight differences from a client measured to carry, and the lock:

    20 option bytes on a data segment instead of 12
    a bare header on the first acknowledgement, doff=20
    a window in the wrong units on the SYN and on every segment after it
    a fixed source port and a fixed initial sequence number
    IP identification, TTL and the Don't Fragment bit
    a certificate signed against itself rather than an empty parent
    a registration that claimed to be MASQUE rather than WireGuard
    a reader that held the lock the writer needed

And two conclusions that measurement reversed, both times against a client that carried:

    the carrier does not relay TCP   - it was never asked to, in the run that said so
    the carrier does not relay TLS   - it never sent a ClientHello, in the run that said so
