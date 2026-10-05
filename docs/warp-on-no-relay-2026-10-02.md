# warp=on with no external relay — 2026-10-02

The brief said to remove the dependency on an external relay. Until now `warp=on` had been true,
but through a SOCKS5 front on the host at `10.0.2.2:15150`, which proves the host's path and says
nothing about the phone. That gap is now closed on the client side.

## Measured, with the relay environment explicitly empty

```
measureWith: env WARP_SOCKS="" WARP_RELAY=""
...
edge candidate 19/30  162.159.198.2:443 via 192.168.0.4
trace target    : connectivity.cloudflareclient.com 162.159.137.65
tls handshake   : complete, alpn=
ip packets      : sent=23 recv=16
CONNECT status  : 200 OK

  warp   = on
  ip     = 104.28.244.74
  colo   = FRA
  loc    = RU
  tls    = TLSv1.3
  kex    = X25519MLKEM768
  http   = http/1.1
  sni    = plaintext

VERDICT: warp=on -- the request travelled through the WARP tunnel
```

Exactly one line in the whole run mentions a relay, and it is the empty `WARP_RELAY=""`. The path
was: own UDP socket -> QUIC to the edge -> MASQUE extended CONNECT -> Connect-IP capsules carrying
whole IP packets -> TLS -> the trace. `sent=23 recv=16` is the capsule traffic both ways.

## What made it work without a relay

Direct QUIC is filtered on this network from the host too, so the client walks 19 edge candidates on
the bind addresses the machine actually has -- 26.226.94.158 (Radmin VPN), 100.127.255.2 (VPNUS),
172.31.208.1 (Hyper-V switch) -- before reaching one that answers on 192.168.0.4 (Ethernet). Each
failure is reported with the real reason:

    write udp4 172.31.208.1:22952->162.159.198.2:8443: wsasendto: A socket operation was
    attempted to an unreachable network.

which is the same class of answer the device gave (`context deadline exceeded`), and both mean the
same thing: this source address does not reach the edge, try the next one. Without that walk the
client would have concluded the edge is unreachable, which is what every direct attempt before it
did.

## New check

`scripts/check_no_external_relay.py` -- the in-process front must own both halves (a loopback
ServerSocket and its own DatagramSocket), and no file on the WARP path may name a non-loopback
address with a port. That is the difference between "the app bypasses the block itself" and "the app
bypasses it by asking a machine that is not blocked", which no runtime measurement here can tell
apart: both produce a tunnel with packets both ways and a trace that says warp=on.

```
[i] problems found:   0
[ok] the WARP path owns its UDP; no external relay is named
```

## Still owed on the device

The emulator's adbd has been offline since the previous session -- every port (16384, 7555, 5555)
answers and every connection sits at `offline`, across three restarts, while `is_android_started` is
true. So the APK carrying the `startForeground` fix and this in-process measurement is built and
passes all nine checks, but has not been installed and re-measured on the phone.
