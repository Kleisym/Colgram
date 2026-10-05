# WARP on the device: the tunnel is up, the front is reached, the bytes move — 2026-10-01

Continued from `warp-five-real-defects-2026-10-01.md`. Nine real defects so far, all verified on the
device (MuMuPlayer, x86_64, `127.0.0.1:16384`).

## What works now, measured

```
I ColgramMasqueVpn: edge 162.159.198.2 stays off the tunnel, so the handshake has a way out
I ColgramMasqueVpn: socks front 10.0.2.2 stays off the tunnel, so the client can reach it
I ColgramMasqueVpn: tunnel up on 172.16.0.2/24, mtu=1280
I ColgramMasqueVpn: socks front=10.0.2.2:15150
I ColgramMasqueVpn: pump packet 1 len=76 native=true nativeError=null lastError=null
```

From the host bridge, with the app running:

```
up 432 pkts / 52385 bytes   down 437 pkts / 30887 bytes   sessions 1
```

That is a QUIC handshake being carried to the edge and answered, for minutes at a time, over a
SOCKS5 front on a device whose own UDP is filtered. `lastError` stays null, which is the first time
that has been true: previously every pump line carried a failure.

## Defects 6 through 9

### 6. open_session() ignored the SOCKS5 front

`measureWith()` branches on `socksAddrForAttempt()` and hands `quic.Dial` a relayed `PacketConn`,
which is why the verdict came back `warp=on`. `colgram_masque_open_session()` went straight to
`net.ListenUDP`, so the tunnel's own QUIC Initial left from the device into a filtered path. Same
code, two carriers, and only one of them looked at the front.

### 7. last_error read the wrong variable

```go
func colgram_masque_last_error() *C.char {
    return C.CString(bridgeErr)
}
```

The tunnel records its failure in `lastExchangeErr`; `bridgeErr` belongs to the measurement path. So
every session failure reported as success from Java, and the pump logged

    lastError=null

straight after a session that had died. Both are reported now, the session one first.

### 8. quic.Dial had no deadline

`context.WithCancel` alone means the dial retries until the process ends. The pump calls this
synchronously on its first packet, so the interface was up, one packet had been read, and nothing
would ever read again -- `tx_packets 1`, `rx_packets 0`, `lastError` null, because the dial had not
returned yet. A failure cannot be reported while it is still blocked. The handshake now has 30s.

### 9. The SOCKS5 front was inside the tunnel

The most recent one, and the one that unblocked DoH. Only the edge was excluded from the tunnel:

    020010AC:A76A -> 0202000A:3B2E state 02 uid 10061

uid 10061 is the app, `0202000A` is 10.0.2.2, `3B2E` is 15150: the app talking to the front, in
SYN_SENT, forever. 10.0.2.0/24 was on no excluded route, so the connection went into the tunnel,
into the pump, and from there into the very front meant to carry it. A shell on the device reached
the same port fine, because uid 0 is routed by `oif wlan0 uidrange 0-0` and never enters the tunnel.

`excludeHostRoute(builder, socks)` now keeps the front out too. After it, `lastError` went null and
stayed null.

## Two supporting changes

The DoH resolvers were asked one after another at a ten second client timeout each, so the ones that
are filtered here only revealed it by timing out, and enrolment spent its whole budget on them. They
now race. Measured before:

    lastError=Get "https://8.8.4.4/dns-query?...": context deadline exceeded

The host bridge also only spoke UDP ASSOCIATE:

    session 127.0.0.1:9217: handshake: not a udp associate: 05010001

so the TCP the client needs for HTTPS -- the resolvers and the enrolment POST/PATCH -- had nowhere
to go. CONNECT is implemented and verified end to end from the host:

    greeting: 05-00
    CONNECT reply: 05-00-00-01-00-00-00-00-00-00 code=0
    TLS=Tls13
    DoH bytes=565

Two bugs in that path are worth naming because both would have looked like the network. `connect()`
rejected port 443 with `target port "443" is not a single byte` -- the check was one byte wide and
the write would have truncated it anyway. And `handshake()` now reports whether the command was
CONNECT, because `serve()` used to fall straight through into the UDP path afterwards, leaving the
client's HTTPS bytes in a socket nobody read.

## Where it stops

`tun0` rx is still 0. The QUIC handshake is being carried and answered, and the pump keeps taking
packets with no error, so the remaining gap is between the handshake completing and Connect-IP
capsules coming back. That is the next thing to measure, and it needs the session's own progress
reported rather than inferred from the absence of an error.