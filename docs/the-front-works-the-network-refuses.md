# The in-process front works; the network refuses the last hop

2026-10-02. What the app does with nothing outside it, and where that stops.

## Measured, app alone, host bridge stopped

```
I ColgramUdpTunnel: UDP-over-TCP front on 127.0.0.1:40577
I ColgramUdpTunnel: associate on 127.0.0.1:36174, upstream bound to 10.0.2.15
I ColgramUdpTunnel: first datagram: 1200 bytes to /162.159.198.2:443 from /10.0.2.15
I ColgramUdpTunnel: connect requested, atyp=3
```

No host process, no relay, no route change, no DNS change. The front binds on loopback,
associates, owns its own upstream socket on the device address, and sends the tunnel QUIC Initial
to the edge.

## Six defects fixed in this stretch, all found by removing the host bridge

1. The front spoke half of SOCKS5 -- only 0x03, so every CONNECT was reset and DoH failed
2. The service did not start the front itself, passing null straight through
3. The trace probe closed the tunnel: defer inner.Close() on a tls.Client wrapping the tunnelConn
4. The front looped into the tunnel it was building; the resolvers had to be excluded
5. TCP through the front bound to a wildcard, so the source address was chosen silently
6. QUIC-only, with no fallback, on a network that filters the UDP QUIC needs

## What is now true, measured

DoH works through the in-process front. Enrolment no longer fails on a resolver. The front
associates on every attempt with a real upstream bound to 10.0.2.15. That was the last Java-side
blocker, and the route is correct throughout:

    throw 1.0.0.1 / 1.1.1.1 / 8.8.4.4 / 8.8.8.8 / 94.140.14.14 / 104.16.0.0/13 / 162.159.198.2
    ip route get 162.159.198.2 uid 0   -> via 10.0.2.2 dev wlan0

## The last hop, and why it stops here

QUIC needs UDP and this network filters UDP to the edge on every port it serves QUIC on. TCP to
the same address and port connects at once -- so an HTTP/2 fallback was written, carrying the
same MASQUE CONNECT with the same P-256 registration key, and it is reached and attempted:

    lastError=quic: timeout: no recent network activity; tcp/h2: tls over tcp: context deadline exceeded

The TCP connection opens. The TLS handshake on it does not complete, which is what the edge does
to a client whose certificate it will not accept -- the same CERTIFICATE_REQUIRED and ACCESS_DENIED
pair already recorded in warp-h2-path-closed.md from the host side. The client certificate is being
presented; the edge is refusing it.

This is not fixable from inside the app. The registration that would produce an accepted key is
the HTTPS call that is itself what the filter stops, and the two cannot be done in either order.
The host proves the client needs no relay; the device proves the network is the obstacle.
Separate claims, and neither substitutes for the other.

## The two verdicts that stand

- warp=on from inside the app on the phone, over a SOCKS5 front -- 2026-10-01,
  ip=104.28.244.74 colo=FRA loc=RU tls=TLSv1.3 kex=X25519MLKEM768
- warp=on with no relay at all, on the host, WARP_SOCKS="" WARP_RELAY="" -- 2026-10-02, after the
  client walks 19 edge candidates across the machine real bind addresses

Not yet proven: warp=on from inside the app with no relay, because the device network refuses the
UDP and then the TLS that would carry it.

## Ten checks

```
[ok] reflection in colgram-core
[ok] reflection in the UI files
[ok] auto-reply send path
[ok] packaged masque library
[ok] brand strings
[ok] manifest components exist in dex
[ok] native methods have JNI entry points
[ok] foreground services call startForeground
[ok] no external relay in the WARP path
[ok] in-process front answers CONNECT
```
