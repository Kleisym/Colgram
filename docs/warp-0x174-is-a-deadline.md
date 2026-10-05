# 0x174 is a deadline, and the VPNUS path looks like a breakthrough and is not

## Content-blind, so it is a timer

Six runs against 162.159.198.2, varying what the client sends and when, with the clock watched rather
than the status code:

```
send=never   kind=none                terminated 0x174 at 1.219s
send=+20ms   kind=connect             terminated 0x174 at 1.218s
send=+300ms  kind=connect             terminated 0x174 at 1.203s
send=+300ms  kind=connect-nocapsule   terminated 0x174 at 1.187s
send=+300ms  kind=get                 terminated 0x174 at 1.203s
send=+600ms  kind=connect             terminated 0x174 at 1.219s
```

Nothing the client sends changes the moment. Not a plain GET, not the RFC 9298 extended CONNECT,
not the same request with `capsule-protocol` removed, not sending it before the edge's timer or well
inside it. A server reacting to content would answer one of those. This is a timer.

## The VPNUS false lead

`VPNUS` is the local Amnezia tunnel, `100.127.255.2`, routing `0.0.0.0/1` at metric 0. Binding the
client to that address sends the edge traffic through a different path. The first run of that
experiment reported `*** SURVIVED 3s ***` twice in a row, which is exactly what a working path looks
like and exactly what I wanted to see.

It was not stable. Eight bare connections per source:

```
LAN     ['0x174@1.187', '0x174@1.203', '0x174@1.187', '0x174@1.218',
         '0x174@1.219', '0x174@1.203', '0x174@1.203', '0x174@1.031']

VPNUS   ['no-hs(0B)', 'no-hs(0B)', 'no-hs(0B)', 'no-hs(0B)',
         'no-hs(0B)', 'no-hs(0B)', 'no-hs(0B)', 'no-hs(0B)']
```

`no-hs(0B)` means the handshake did not complete **and not one byte arrived from the edge**. Not a
Retry, not an Initial, nothing. The two SURVIVED results were a handful of datagrams that slipped
through a path that does not carry this traffic, not a tunnel opening. A GET from that source showed
the same thing: `hs=False, bytes=0`.

So the finding is the opposite of the one it looked like. The LAN path is not being blocked by a
middlebox that the VPNUS path bypasses - the VPNUS path does not reach the MASQUE edge at all, and the
LAN path reaches it perfectly well, answering every QUIC Initial in about 100 ms.

## The timer, precisely

Across every LAN run in this project the termination falls between 1.03 s and 1.22 s, in twenty-three
observations. The edge issues a connection ID and then waits. The official client put its MASQUE flow
up 16 ms after the handshake and declared the tunnel up 1.24 s after the connection was established,
so its own clock lands in the same place - it satisfied the wait, this project does not.

The conclusion is unchanged and now better supported: the 781 ms is a deadline for identity material
that the desktop daemon holds under DPAPI or a TPM, generated at registration, and not present in
any file on this machine.

**No `warp=on` measurement exists. The tunnel does not work.**
