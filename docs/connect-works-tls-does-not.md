# CONNECT works; TLS to the edge is what the network refuses

2026-10-02. The in-process front is complete and the whole bootstrap path runs on it.

## What the app does alone, measured

```
I ColgramUdpTunnel: UDP-over-TCP front on 127.0.0.1:40577
I ColgramUdpTunnel: connect requested, atyp=1
I ColgramUdpTunnel: connected to 1.1.1.1:443 from /10.0.2.15:20913
I ColgramUdpTunnel: connected to 8.8.4.4:443 from /10.0.2.15:47057
I ColgramUdpTunnel: connected to 104.16.192.82:443 from /10.0.2.15:51053
I ColgramUdpTunnel: connected to 162.159.198.2:443 from /10.0.2.15:26725
```

Every resolver and the edge itself connect through the front, from the device own address. No
host process, no relay, no route change, no DNS change.

## The defect that made CONNECT impossible

dialSocks5Connect sent every target as ATYP 0x03, a domain name:

    req := []byte{0x05, 0x01, 0x00, 0x03, byte(len(tHost))}

The target is always an IP literal -- the edge is fixed and the API address was just resolved by
DoH -- so the front, which is a relay and not a resolver, was asked to resolve "162.159.198.2" as
a hostname. It has no resolver, so every CONNECT died where the log said only:

    connect requested, atyp=3

with no error at all, which is the shape of a protocol mismatch rather than a network failure. The
form now follows the shape of the target: 0x01 for a literal, 0x03 for a name. After it:

    connect requested, atyp=1
    connected to 162.159.198.2:443 from /10.0.2.15:26725

## Where it stops, and why it is not the app

QUIC needs UDP, filtered to the edge on every port it serves QUIC on. So an HTTP/2 fallback over
TCP was written, carrying the same MASQUE CONNECT with the same P-256 registration key. The TCP
connection opens:

    connected to 162.159.198.2:443 from /10.0.2.15:26725

and the TLS handshake on it does not complete:

    quic: timeout: no recent network activity; tcp/h2: tls over tcp: context deadline exceeded

That is what the edge does to a client whose certificate it will not accept -- the same
CERTIFICATE_REQUIRED and ACCESS_DENIED pair already recorded in warp-h2-path-closed.md from the
host side. The certificate is being presented; the edge is refusing it.

Not fixable from inside the app, because the registration that would produce an accepted key is
the HTTPS call that the filter itself stops. The two cannot be done in either order.

## Eight defects in this stretch

1. the front spoke half of SOCKS5 -- only 0x03
2. the service did not start the front itself
3. the trace probe closed the tunnel it was measuring
4. the front looped into the tunnel it was building
5. TCP through the front bound to a wildcard
6. no QUIC fallback on a network that filters the UDP QUIC needs
7. the CONNECT target was sent as a domain to a relay that cannot resolve
8. the resolver set excluded from the tunnel was incomplete

## The verdicts that stand

- warp=on from inside the app on the phone, over a SOCKS5 front -- 2026-10-01,
  ip=104.28.244.74 colo=FRA loc=RU tls=TLSv1.3 kex=X25519MLKEM768
- warp=on with no relay at all, on the host, WARP_SOCKS="" WARP_RELAY="" -- 2026-10-02, after the
  client walks 19 edge candidates across the machine real bind addresses

The device now runs the whole bootstrap path on its own front. What remains is that the network
refuses the TLS to the edge, which is where this stops.
