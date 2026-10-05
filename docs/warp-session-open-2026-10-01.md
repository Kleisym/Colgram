# WARP: the tunnel opens, the session is open, the bytes move — 2026-10-01, late

Third session on the goal. Ten real defects found and fixed, each measured on the device
(MuMuPlayer, x86_64, `127.0.0.1:16384`). The tunnel now reports its own stage, so the remaining
question is a stage rather than a silence.

## Measured state

```
I ColgramMasqueVpn: edge 162.159.198.2 stays off the tunnel, so the handshake has a way out
I ColgramMasqueVpn: socks front 10.0.2.2 stays off the tunnel, so the client can reach it
I ColgramMasqueVpn: tunnel up on 172.16.0.2/24, mtu=1280
I ColgramMasqueVpn: socks front=10.0.2.2:15150
I ColgramMasqueVpn: pump packet 2 len=48 native=true nativeError=null lastError=null stage=open (carrying packets to 162.159.198.2:443)
```

Host bridge, same moment:

```
up 1393 pkts / 148270 bytes   down 1404 pkts / 90428 bytes   sessions 1
```

`stage=open` is not a claim, it is the point past which `open_session()` cannot fail: enrolment
returned, `quic.Dial` completed, the extended CONNECT was accepted, and `openPeer()` saw its SYN
acknowledged. Before this session had a stage, `lastError=null` was the only signal, and a stalled
handshake and a carrying tunnel both produced it.

## Defects 6 through 10

### 6. `open_session()` ignored the SOCKS5 front

`measureWith()` branches on `socksAddrForAttempt()` and hands `quic.Dial` a relayed `PacketConn`,
which is why the verdict came back `warp=on`. `colgram_masque_open_session()` went straight to
`net.ListenUDP`, so the tunnel's own QUIC Initial left from the device into a filtered path. Same
source, two carriers, only one of which looked at the front.

### 7. `last_error` read the wrong variable

```go
func colgram_masque_last_error() *C.char {
    return C.CString(bridgeErr)
}
```

The tunnel records failures in `lastExchangeErr`; `bridgeErr` belongs to the measurement path. Every
session failure therefore reported as success from Java, and the pump logged `lastError=null`
straight after a session that had died.

### 8. `quic.Dial` had no deadline

`context.WithCancel` alone retries until the process ends. The pump calls this synchronously on its
first packet, so the interface was up, one packet had been read, and nothing would ever read again:
`tx_packets 1`, `rx_packets 0`, `lastError` null -- because the dial had not returned yet. A failure
cannot be reported while it is still blocked. Thirty seconds now.

### 9. The SOCKS5 front was inside the tunnel

Only the edge was excluded. Measured:

    020010AC:A76A -> 0202000A:3B2E state 02 uid 10061

uid 10061 is the app, `0202000A` is 10.0.2.2, `3B2E` is 15150: the app talking to the front, in
SYN_SENT, forever. 10.0.2.0/24 was on no excluded route, so the connection went into the tunnel,
into the pump, and from there into the very front meant to carry it. A shell on the device reached
the same port fine, because uid 0 is routed by `oif wlan0 uidrange 0-0` and never enters the tunnel.
`excludeHostRoute(builder, socks)` fixed it, and `lastError` went null and stayed null.

### 10. Inbound capsules bypassed flow matching

The measurement path calls `tun.dispatch(ip)`, which matches a packet to a flow by port pair.
The device-wide path called `s.peer.feed(data[1:])` directly, handing every inbound packet to the one
flow regardless of whose it was. Invisible with a single flow, fatal as soon as the phone opens a
second: its SYN-ACK went to the wrong peer. Both go through `dispatch` now.

## Supporting work

DoH resolvers race instead of being tried one at a time at a ten second client timeout each -- the
filtered ones here only reveal themselves by timing out, and enrolment spent its whole budget on
them. Before:

    lastError=Get "https://8.8.4.4/dns-query?...": context deadline exceeded

The host bridge spoke only UDP ASSOCIATE:

    session 127.0.0.1:9217: handshake: not a udp associate: 05010001

so the TCP the client needs for HTTPS -- resolvers and the enrolment POST/PATCH -- had nowhere to go.
CONNECT is implemented and verified end to end:

    greeting: 05-00
    CONNECT reply: 05-00-00-01-00-00-00-00-00-00 code=0
    TLS=Tls13
    DoH bytes=565

Two bugs in that path read as the network. `connect()` rejected port 443 with `target port "443" is
not a single byte` -- the check was one byte wide and the write would have truncated it anyway. And
`handshake()` did not report which command it saw, so `serve()` fell through into the UDP path after a
CONNECT and left the client's bytes in a socket nobody read.

`colgram_masque_session_progress()` and the Java `sessionProgress()` are new, and the checkers
`check_manifest_components.py`, `check_jni_symbols.py` and the architecture comparison in
`check_masque_build.py` all fail on the defects above when reintroduced. Full suite green.

## Where it stops

`tun0` rx is still 0 while `stage=open`. The inner flow's SYN was acknowledged, so the packet path
works up to that point; what is not yet established is that a capsule coming back from the edge is
read and written to the interface. The edge answers with 1200-byte QUIC packets, so the datagram is
arriving at the SOCKS front -- what happens to the IP packet inside it is the next thing to measure.

`stage=open` also arrived in about ten seconds rather than the eight minutes the previous build
needed, which is consistent with packets now reaching the flow they belong to.