# The edge accepts TCP and then says nothing: the filter is on TLS, not on the client

2026-10-02. Four probes, from the host, all against 162.159.198.2:443.

```
TCP connect              -> connected, local 100.127.255.2:38559
ClientHello sent         -> TIMEOUT, peer said nothing at all
TLS, no client cert      -> SSLEOFError, unexpected EOF
TLS, client cert (P-256) -> SSLEOFError, unexpected EOF
TLS, alpn h2 / h3 / both -> SSLEOFError in every case
```

The connection is established and then the peer never speaks. That is a filter reading the first
record of the stream, not a client being refused for a certificate it did or did not present:
an edge refusing a certificate says so in the TLS alert, which is exactly what is missing here.

It also means the HTTP/2 fallback cannot be completed from this network by any client, however
correct it is. The bytes that would carry it are stopped one layer earlier than MASQUE, one layer
earlier than the client certificate, and one layer earlier than anything this app can choose.

## What that leaves, measured

The app owns the whole path up to that point, on its own front, with nothing outside it:

    I ColgramUdpTunnel: UDP-over-TCP front on 127.0.0.1:40577
    I ColgramUdpTunnel: connected to 1.1.1.1:443 from /10.0.2.15:20913
    I ColgramUdpTunnel: connected to 8.8.4.4:443 from /10.0.2.15:47057
    I ColgramUdpTunnel: connected to 162.159.198.2:443 from /10.0.2.15:26725

DoH works. Enrolment works. The edge is reachable at TCP. What is refused is the TLS record that
would begin the tunnel, and refusing it is not something the tunnel can route around, because the
tunnel is what would have carried it.

## The verdicts that stand, and what each proves

- warp=on from inside the app on the phone -- 2026-10-01, ip=104.28.244.74 colo=FRA loc=RU
  tls=TLSv1.3 kex=X25519MLKEM768. The app MASQUE client works end to end.
- warp=on with no relay at all, on the host, WARP_SOCKS="" WARP_RELAY="" -- 2026-10-02, after
  walking 19 edge candidates across the machine own bind addresses. The client needs no relay.
- On this network the device cannot reach the edge TLS from its own address, so the app cannot
  complete a tunnel from inside itself here. That is a fact about the network, measured from four
  angles, and it is separate from the two above.

A device behind a network that does not filter TLS to the edge needs nothing from the host: the front
is in-process, the client is embedded, and the same code that reads warp=on here reads it there.
