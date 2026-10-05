# warp=on, measured twice from the host

## The measurement

```
python -B warp_on_trace.py
```

Two consecutive runs, identical output:

```
session address : 172.16.0.2
trace target    : connectivity.cloudflareclient.com 162.159.137.65
CONNECT status  : 200
tcp handshake   : established through the tunnel
client hello    : 517 bytes
tls handshake   : complete, alpn=None
request         : GET /cdn-cgi/trace (133 bytes)
ip packets      : sent=14 recv=11
status          : HTTP/1.1 200 OK
  warp   = on
  ip     = 104.28.244.74
  loc    = RU
  colo   = FRA
  kex    = X25519
  tls    = TLSv1.3
  http   = http/1.1
  sni    = plaintext

VERDICT: warp=on -- the request travelled through the WARP tunnel
```

Exit status 0 both times. `warp=on` is Cloudflare's own line in its own trace, on a request that left
the host through the MASQUE tunnel. It is not inferred from a CONNECT code or from a packet capture.

## What this closes, and what it does not

Earlier work in this project proved forwarding but never produced a verdict: UDP to 1.1.1.1 came
back, an ICMP echo reply came back carrying our own identifier, and CONNECT returned 200. All three
are consistent with a tunnel that carries packets. None of them is what WARP is.

This is. `warp=on` is emitted by the same edge that serves every Cloudflare property, and it is
reported per request by the client identity on the connection. So:

-   the MASQUE path works on this network, over QUIC to 162.159.198.2:443
-   the registration with `v0a4471`, the PATCH enrolment and the bare self-signed certificate are
    all correct, because the edge accepted them and attributed the session to a WARP client
-   Connect-IP capsules carry real TCP, real TLS and a real HTTPS request end to end

The request arrives at Cloudflare's Frankfurt colo from a WARP-attributed source IP
(104.28.244.74) while the host itself is on RU infrastructure. That difference is the tunnel.

## The remaining leg

This is a host measurement. The device leg - the reference client registered on the phone,
which never completed its own tunnel - is still open, and the goal is not met until the same
trace reads `warp=on` from the device.

The protocol is now proven independently of the client, which changes what the device failure
can be. It is no longer a question of whether this network permits the protocol: it does. What
remains is why the Go client on the phone does not complete a handshake that this one completes
here, against the same edge, with the same registration flow.

## What was wrong in the client code that got this working

Four defects, all in the measuring client, none in the protocol:

-   the peer lookup key was reversed, so inbound TCP was delivered to nothing
-   SYN/ACK was answered with another SYN/ACK instead of an ACK
-   a bare ACK was emitted after every data packet, which the far side read as end of stream
-   the handshake-complete flag doubled as the loop-exit flag, so the response was never awaited

The last one is why earlier runs reported `CONNECT 200` and no body: the client exited as soon as
the handshake finished, one step before the reply arrived.
