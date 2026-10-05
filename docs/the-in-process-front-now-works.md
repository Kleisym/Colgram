# The in-process front now works; the network is what filters

2026-10-02. Four defects fixed since the last note, each found by taking the host bridge away and
watching what the app did alone.

## What the app does now, with nothing outside it

```
I ColgramUdpTunnel(11023): associate on 127.0.0.1:50884, upstream bound to 10.0.2.15
I ColgramUdpTunnel(11023): first datagram: 1200 bytes to /162.159.198.2:443 from /10.0.2.15
I ColgramMasqueVpn(11023): pump packet 1 len=76 native=true nativeError=null stage=none
```

The front binds on loopback, associates, owns its own upstream socket on the device's real address,
and sends the tunnel QUIC Initial to the edge. No host process, no relay, no route change, no DNS
change. That is what the brief asked for, and it is now the only path the app takes.

## Four defects, in the order they surfaced

### 1. The front spoke half of SOCKS5

```java
if (in.read() != 0x05 || in.read() != 0x03) return;
```

0x03 is UDP ASSOCIATE. CONNECT was closed unread, and CONNECT is what the DoH lookups and the
enrolment call are. The symptom hid behind a reset:

    Get "https://1.1.1.1/dns-query?...": read tcp 127.0.0.1:52938->127.0.0.1:42787: read:
    connection reset by peer

which reads as a network fault and was the front refusing the half of the protocol it had not been
written to speak. serveConnect dials the target from the device and splices the streams.

### 2. The service did not start the front itself

bringUp() starts it, but the service is reachable on its own -- from the switch, from restored
state, from an explicit start -- and passed a null front straight through:

    I ColgramMasqueVpn: socks front=none

The service now starts the front when nobody named one. The front belongs to the tunnel, not to
whoever asked for it.

### 3. The trace probe closed the tunnel

traceThroughTunnel ended with `defer inner.Close()`. tls.Client wraps the tunnelConn itself, so that
close ended the flow the whole device-wide tunnel runs on:

    verdict through the tunnel: warp=off | no trace: tls inside tunnel: use of closed network
    connection

The read deadline already bounds the call, so the close is gone, with a comment saying why -- it looks
like a leak and is not one.

### 4. The front looped into the tunnel it was building

This one only shows when the tunnel is up, because before that the uid is not routed into it. With
the interface established:

    1.1.1.1 dev tun0 table tun0 src 172.16.0.2 uid 10061

The front belongs to the app, so its packets are routed by uid into the tunnel, which is not up, so
nothing returns:

    connect to 1.1.1.1:443 failed: SocketTimeoutException
    connect to 8.8.8.8:443 failed: SocketTimeoutException

A chicken-and-egg only the routing table can break. The resolvers are now excluded, and so is the
block they rotate through:

    throw 1.0.0.1 / 1.1.1.1 / 8.8.4.4 / 8.8.8.8 / 94.140.14.14 / 104.16.0.0/13 / 162.159.198.2

The first four alone were not enough -- the survivors named themselves:

    connect to 94.140.14.14:443 failed: SocketTimeoutException
    connect to 104.16.192.82:443 failed: SocketTimeoutException

## What is now true, measured

DoH works through the in-process front: enrolment no longer fails on a resolver, and the front
associates on every attempt with a real upstream bound to 10.0.2.15. That was the last Java-side
blocker.

## What the network still refuses

The device own UDP to the edge is filtered, which is the condition the front exists to work around
and which it cannot:

    nc -u -z 162.159.198.2 443   -> no answer
    ip route get 162.159.198.2 uid 0   -> via 10.0.2.2 dev wlan0

The route is right -- it leaves by wlan0, not by the tunnel -- and the datagram is well formed at
1200 bytes to the right address and port. TCP to the same address and port connects at once:

    nc -z 162.159.198.2 443   OPEN

So the front carries the tunnel UDP as far as the network permits, and the network drops it. This is
not something the app can fix from inside the app: a front that relays UDP still emits UDP, and the
filter is on UDP to that destination.

## What that means for the two verdicts

Both stand, and they are different facts:

- warp=on from inside the app on the phone, carried by a SOCKS5 front -- proven 2026-10-01,
  ip=104.28.244.74 colo=FRA loc=RU tls=TLSv1.3 kex=X25519MLKEM768
- warp=on with no relay at all, on the host, WARP_SOCKS="" WARP_RELAY="" -- proven 2026-10-02, after
  the client walks 19 edge candidates across the machine real bind addresses

What has not been proven is warp=on from inside the app with no relay, because the device network
refuses the UDP that would carry it. The host proves the client needs no relay; the device proves the
network is the obstacle. Those are separate claims and neither substitutes for the other.

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

check_front_connect.py is the new one and fails if the front stops branching on CONNECT, loses its
handler, or stops clearing the read timeout before splicing.
