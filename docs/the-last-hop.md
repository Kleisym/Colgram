# The last hop: TLS to the edge is stopped before it starts

2026-10-02. What follows is not an app defect. It is four measurements of the network, and
then the line between the two.

## What the edge does, measured from the host

```
TCP connect to 162.159.198.2:443     -> established
ClientHello, no certificate          -> TIMEOUT, peer silent
ClientHello + P-256 client cert      -> TLSV13_ALERT_CERTIFICATE_REQUIRED
same, ALPN h2 / h3 / none            -> TLSV1_ALERT_ACCESS_DENIED
```

The first pair says the edge speaks TLS only to a client with a certificate. The second says it
accepts the certificate and then refuses the request that follows. Both are the edge answering, in
the TLS record where an answer belongs -- which is what distinguishes them from the TCP probe, where
the peer accepted the connection and then said nothing at all.

Per source address, and this is the part that matters:

```
192.168.0.4      -> TLS OK, TLSv1.3
26.226.94.158    -> timeout
100.127.255.2    -> SSLEOFError
172.31.208.1     -> unreachable network
```

One egress of the four gets a working handshake. The edge is reachable; the path to it is not
uniform, and the one that works is not the one the device has.

## What the device does, measured

```
I ColgramUdpTunnel: UDP-over-TCP front on 127.0.0.1:23496
I ColgramUdpTunnel: connect requested, atyp=1
I ColgramUdpTunnel: connected to 1.1.1.1:443 from /10.0.2.15:20913
I ColgramUdpTunnel: connected to 8.8.4.4:443 from /10.0.2.15:47057
I ColgramUdpTunnel: connected to 104.16.192.82:443 from /10.0.2.15:51053
I ColgramUdpTunnel: connected to 162.159.198.2:443 from /10.0.2.15:10801
```

Every resolver and the edge connect through the app own front, from the device own address. DoH
works, enrolment works, and the edge accepts TCP.

The TLS handshake on that connection does not complete. Threads named colgram-connect are sitting in
it, and there is not one established socket to port 443 from the app uid. The filter takes the record
that would begin the tunnel, and the tunnel is what would have carried it, so nothing inside the app
can route around it.

## The line

Proven from inside the app, on the phone: the MASQUE client is embedded and reads

    ip=104.28.244.74 colo=FRA loc=RU tls=TLSv1.3 sni=plaintext warp=on kex=X25519MLKEM768

over a front that was inside the app as well as the client.

Proven from the host, with the relay environment empty:

    WARP_SOCKS="" WARP_RELAY=""  ->  warp=on  after 19 edge candidates

so the client needs no relay and no outside help.

Not proven, and not provable here: warp=on from inside the app with no front at all, because this
network stops TLS to the edge from the address the device has. The app has no remaining path to
invent, and the remaining work is a network, not a client.

## Nine defects fixed in this stretch

1. the front spoke half of SOCKS5
2. the service did not start the front itself
3. the trace probe closed the tunnel it measured
4. the front looped into the tunnel it was building
5. TCP through the front bound to a wildcard
6. no QUIC fallback where QUIC is filtered
7. CONNECT target sent as a domain to a relay that cannot resolve
8. the excluded resolver set was incomplete
9. HTTP/2 fallback reached and attempted, with the registered certificate
