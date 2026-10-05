# The front only spoke half of SOCKS5 — 2026-10-02

Found by taking the relay away and watching what the app did on its own.

## What the measurement said

With the host bridge stopped and nothing but the app running:

```
I ColgramUdpTunnel(8339): UDP-over-TCP front on 127.0.0.1:42787
I ColgramMasqueVpn(8339): socks front=127.0.0.1:42787
```

The front was up, on loopback, entirely inside the app. Then:

```
lastError=Get "https://1.1.1.1/dns-query?name=api.cloudflareclient.com&type=A": read tcp
         127.0.0.1:52938->127.0.0.1:42787: read: connection reset by peer
stage=failed
```

A reset, not a SOCKS5 refusal, because the front returned from its serve method mid-handshake rather
than replying. That distinction is the whole bug: a refusal names itself, a reset reads as the
network dropping a connection that was never accepted.

## The cause

```java
// Request: version, UDP ASSOCIATE, reserved, then an address that only has to be read.
if (in.read() != 0x05 || in.read() != 0x03) return;
```

0x03 is UDP ASSOCIATE. Everything else, including 0x01 CONNECT, was closed unread.

That is half a SOCKS5 server, and the half that was missing is the half the client needs before any
tunnel exists. DoH is HTTPS. The enrolment call is HTTPS. Both arrive as CONNECT, both were being
reset, and both happen before a single packet of tunnel traffic is sent -- which is why the tunnel
came up carrying nothing and the stage never left `failed`.

## The fix

`serve()` reads the command and branches:

```java
if (cmd == 0x01) {
    serveConnect(in, out, client, atyp);
    return;
}
if (cmd != 0x03) return;
```

`serveConnect` dials the target from the device itself, replies, and splices both streams. Nothing
is proxied off the device -- the socket belongs to the app, which is the point the whole exercise was
about.

One detail that would have bitten next: the client socket arrives with a read timeout set for the UDP
path, which is correct there and fatal for HTTPS, since a response can idle longer than that between
packets. The CONNECT path clears it:

```java
// Zero means block.
client.setSoTimeout(0);
```

## The second defect, in the service

```
I ColgramMasqueVpn: socks front=none
```

`ColgramWarpMasqueTunnel.bringUp()` starts the in-process front, but the service is reachable on its
own -- from the switch, from restored state, from an explicit start -- and it took the front from the
intent and passed it straight through. No front in the intent meant no front at all, and the native
client sent its QUIC direct into a path this network filters. The service now starts the front itself
when nobody named one, because the front belongs to the tunnel rather than to whoever asked for it.

## And in the trace probe

```
verdict through the tunnel: warp=off | no trace: trace read: tls inside tunnel: use of closed
network connection
```

`traceThroughTunnel` ended with `defer inner.Close()`. `tls.Client` wraps the tunnelConn itself, so
closing the TLS session closed the flow the whole device-wide tunnel runs on, and the next probe had
nothing to talk to. The read deadline already bounds the call, so the close is simply gone -- with a
comment saying why, because it looks like a leak and is not one.

## New check

`scripts/check_front_connect.py` -- the front must branch on CONNECT, must have a handler, must clear
the read timeout before splicing, and must still reject anything else. All four, or it fails.

## Ten checks now

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

## What is not measured yet

The APK carrying all three fixes is built (01:09). It has not been installed: the emulator adbd went
offline again mid-install and has not come back, across a driver reinstall and two restarts. The
CONNECT handler is compiled and checked structurally; it has not carried a real DoH request.
