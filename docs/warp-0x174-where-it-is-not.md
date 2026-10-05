# Where 0x174 is not: four things ruled out by measurement

After establishing that `0x174` follows the edge address rather than the SNI, and that it is not a
certificate error, four remaining explanations were tested against the live edge. All four are dead.

## 1. It is not the SNI

One client, one ALPN, no client certificate anywhere:

```
8.47.69.0      cloudflare-quic.com                  ALIVE
8.47.69.0      one.one.one.one                      ALIVE
8.47.69.0      consumer-masque.cloudflareclient.com no handshake
162.159.198.2  cloudflare-quic.com                  0x174
162.159.198.2  consumer-masque.cloudflareclient.com 0x174
162.159.198.2  one.one.one.one                      0x174
162.159.197.4  connectivity.cloudflareclient.com    no handshake
162.159.197.3  engage.cloudflareclient.com          no handshake
```

The same SNI is accepted on one edge and refused on another; the same SNI is refused on 162.159.198.2
alongside two others. The variable is the edge, not the name.

## 2. It is not the ALPN

On 162.159.198.2:

```
h3                            -> 0x174, negotiated h3
h2                            -> no handshake
http/1.1                      -> no handshake
h3-29,h3-32,h3-34,h3          -> 0x174, negotiated h3
cloudflare-warp               -> no handshake
warp                          -> no handshake
masque                        -> no handshake
hq-interop                    -> no handshake
h3,h2,http/1.1,cloudflare-warp,masque -> 0x174, negotiated h3
(nothing offered)             -> 0x174, negotiated h3
```

The edge answers only h3 and always terminates. Offering nothing is not treated as a fallback - the
server still selects h3 and still fails.

## 3. It is not a QUIC transport parameter

Ten variations, all identical:

```
defaults                        0x174   alpn=h3
max_datagram_size 1200           0x174   alpn=h3
max_datagram_size 1350           0x174   alpn=h3
max_datagram_size 1252           0x174   alpn=h3
disable_active_migration         0x174   alpn=h3
keep_alive                       0x174   alpn=h3
initial_source_connection_id off 0x174   alpn=h3
peer_certificate empty          0x174   alpn=h3
stateless_reset off              0x174   alpn=h3
active_connection_id_limit 2     0x174   alpn=h3
```

Not the MTU, not migration, not keepalive, not connection IDs, not the stateless reset token.

One transport option cannot be varied with aioquic 1.3: post-quantum key exchange. The registration
says `post_quantum: enabled_with_downgrades` and the official client logs `pq=true` and `pq=false` as
separate attempts, so it tries both. A classical-only handshake rejected after completing is exactly
the shape observed here, and it is the one live hypothesis that this tooling cannot test.

## 4. It is not a missing request

Every earlier test waited. The edge issues a connection ID and sends H3 SETTINGS, then terminates,
which reads like a server waiting for a stream. So the request was sent in the same event handler that
reports `HandshakeCompleted`, 100 ms later, and after the server's SETTINGS:

```
immediate        sent=True  status=None  terminated=0x174
delay 100ms      sent=True  status=None  terminated=0x174
after SETTINGS   sent=True  status=None  terminated=0x174
```

The request goes out in all three. No response arrives, and the termination still comes. So the edge
is not waiting for a stream to be opened - it is failing the session regardless of what is sent on it.

## What is left

The failure is above the QUIC transport, above the ALPN choice, and independent of the request. Two
things remain that this project cannot test without the desktop daemon:

1. Post-quantum key exchange. aioquic 1.3 has no hybrid key share, and the edge may require the
   ML-KEM hybrid the official client sends when `pq=true`.
2. The identity exchange in the stage the official client names `EnsuringMtlsIdentity`, which runs
   after the handshake and is where registration-derived material is presented.

Both of those are performed by `warp-svc.exe`, and neither is reachable by a third-party client that
has not been issued the material. That is the same wall as before, stated more precisely: it is not
a certificate, it is the post-handshake identity the daemon builds.

**Still no `warp=on` measurement. The tunnel does not work.**
