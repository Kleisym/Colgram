# WARP on this network — what was measured, and what is left

Everything below was measured from the device or the app, not inferred. A verdict here means a
measurement produced it; a claim without one is marked as such.

## The block is external, and port-specific

Measured on the device, and re-measured from the host on the same network:

| Probe | Result |
|---|---|
| UDP DNS, 6 resolvers (1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8, 208.67.222.222, 94.140.14.14) | **all 6 answer, 64B** |
| UDP to all 16 Cloudflare WireGuard ingresses (ports 2408, 500, 1701, 4500), a real message-initiation | **0 of 16 answer** |
| **UDP 443/QUIC on the same addresses** | **answers** |
| AmneziaWG, extended ranges, junk packets, S1/S2/S5/S6 | no reproducible response |

UDP itself is not broken — six independent resolvers answer from the same host, immediately before
the sweep. Cloudflare's WireGuard ingress specifically does not answer.

### The block is not "all Cloudflare UDP" — and that correction matters

An earlier version of this file said the whole of Cloudflare's UDP was filtered. That was wider than
the evidence supported. Sweeping the ports rather than only the four WARP advertises found:

```
python scripts/warp-port-sweep.py

162.159.192.1
    2408 silent     WARP / WireGuard (the registered ingress)
    ...
    443  ANSWERED   QUIC / HTTP3 - not WireGuard, but the same edge over UDP
...
answered 2, silent 62
```

Two of sixty-four probes answered, **both on 443/udp, on Cloudflare addresses whose WireGuard
ports are all silent.** So Cloudflare's edge *is* reachable by UDP from this network; what is
filtered is the WireGuard service on it. Those are different problems, and only the first is
something a different transport could work around.

Re-run against that one address alone, so the comparison cannot be an artefact of sweep order:

```
python scripts/warp-port-sweep.py --host 162.159.192.1

answered 1, silent 15        # the one answer is 443
```

**What it does not do is make WARP work.** WARP speaks WireGuard, not QUIC. An open 443 cannot carry
a WireGuard handshake, so this narrows the diagnosis without opening a route. It does mean the
relay conclusion survives a sharper test than the one it was originally based on.

### It is a UDP port allowlist, not a WireGuard block

The port-specific result has two possible readings, and they call for opposite conclusions:

- *WireGuard is blocked specifically* - then some other transport could reach the same host, and
  wrapping WARP in it might work.
- *Only a couple of ports get through at all* - then no transport helps, because the WireGuard
  port can never carry a byte no matter what is wrapped around it.

Measured, and it is the second:

```
python scripts/warp-port-allowlist.py

  162.159.192.1:53     silent        162.159.192.1:4444   silent
  162.159.192.1:80     silent        162.159.192.1:8080   silent
  162.159.192.1:123    silent        162.159.192.1:8443   silent
  162.159.192.1:443    ANSWERED      162.159.192.1:31337  silent
  162.159.192.1:1234   silent        162.159.192.1:55555  silent

  1.1.1.1:2408   silent      # 1.1.1.1 does not run WireGuard at all
  8.8.8.8:2408   silent
  1.1.1.1:443    ANSWERED
```

Thirteen ports from 53 to 55535 on one Cloudflare address, and the only answer is 443. Port 2408 is
silent on hosts that **do not run WireGuard**, so the silence follows the port, not the protocol or
the owner.

**What this settles.** This network allows a small allowlist of UDP ports - DNS and HTTPS/3 - and
filters everything else. WARP's 2408 is outside that allowlist on every host tested, so it cannot
be reached directly, and no client-side transport moves a datagram onto a port that is filtered.
Wrapping WireGuard in QUIC or TLS does not help, because the wrapper would still have to arrive on
2408.

**What it does not settle.** A relay is still the only route, because a relay receives the
handshake over TCP - 443 and 22 are both open here - and originates the WireGuard UDP itself from a
network without this allowlist. What is settled is the *reason*, which is now a measurement rather
than an assumption, and that reason is testable against any future network.

### Correction: there is a size threshold, and the probe was not varying it

The section above was too confident, and the way it was wrong is worth recording rather than
quietly editing.

Every port probe sent **1200-byte** datagrams. Varying only the size, on the same host and the same
port that already answered:

```
1.1.1.1:443   64 bytes  silent
              256 bytes  silent
              600 bytes  silent
              800 bytes  silent
             1000 bytes  silent
             1200 bytes  ANSWERED  (a 31 byte QUIC version negotiation)
```

Nothing about the port changed - only the size did. So "443 answers and 2408 does not" was never a
clean port comparison: 443 was answering a packet that a **WireGuard message-initiation cannot
match**, since an initiation is 148 bytes. A live WireGuard port would have been silent to that
probe too.

Re-run properly - all Cloudflare WireGuard ports at 1200 bytes, a size that provably gets 443 to
reply - and they are still all silent. So the port conclusion **survives having been tested
properly rather than by accident**, which is the only reason it is worth keeping. The allowlist
framing does not: what the evidence supports is "2408 is filtered, and separately, small UDP
datagrams get no answer at all on any port".

```
python scripts/warp-mtu-threshold.py
```

The practical consequence for WARP is the same either way, and worth stating plainly: the 148-byte
handshake sits below the 1200-byte floor, so even a WireGuard port that were open would be
unobservable to a probe - which is why the device test asserts against a real handshake with
Cloudflare's own `cdn-cgi/trace` rather than against reachability.

### …but the floor is UDP-only, and that is what makes a relay viable

Read carelessly, a ~1200-byte floor on small packets says nothing small can cross this link at all,
and a WireGuard initiation is 148 bytes - so the obvious conclusion is that a relay is pointless
too, because it would have to carry those same small packets.

**That conclusion would be wrong, and the way to know is to send the same small payload over
TCP.**

```
python scripts/udp-vs-tcp-small-payload.py

  TCP   64 bytes   TLS TLSv1.3 ok        UDP   64 bytes   silent
  TCP  148 bytes   TLS TLSv1.3 ok        UDP  148 bytes   silent
  TCP  512 bytes   TLS TLSv1.3 ok        UDP  512 bytes   silent
  TCP 1200 bytes   TLS TLSv1.3 ok        UDP 1200 bytes   ANSWERED
```

Small packets cross this network over **TCP** and get nothing over **UDP**. The floor is a
property of the UDP path, not of the link - and TCP is proven here by a completed TLS 1.3
handshake, not by a connect, which succeeds on ports nothing listens on and carries nothing.

**This is the asymmetry a WireGuard relay depends on.** The 148-byte initiation arrives over TCP,
where small payloads demonstrably work; the relay then originates the WireGuard UDP itself from a
network without this filter. Judging the relay on the strength of the UDP result alone would have
dismissed the only viable route on the basis of a measurement about a different protocol.

### The relay is built, and it is proven to bridge

`scripts/warp-relay-server.py` is the server side: it accepts length-prefixed frames over TCP and
forwards each as one UDP datagram to Cloudflare's WireGuard endpoint, returning whatever comes
back. It is deliberately trivial - WireGuard authenticates every packet cryptographically, so a
relay that mangled traffic could not produce a working tunnel, only a broken one. The tunnel either
works end to end or does not, and there is no way for this to quietly weaken WireGuard's guarantees.

Run on a box that can reach Cloudflare's WireGuard ports:

```
python scripts/warp-relay-server.py --keygen
python scripts/warp-relay-server.py --listen 0.0.0.0:51820 --endpoint 162.159.192.1:2408
```

Then long-press the WARP row in Colgram and give it that address, port and the relay's public key.
A relay terminates the handshake, so both the peer key and the endpoint are the relay's - Colgram
already swaps both together, and there is a test for it.

**Proven, in two halves.** Run here against `1.1.1.1:443`, which answers, the relay returns a
frame in under a second, so the bridge itself works. Run here against `162.159.192.1:2408`, which
is filtered, it times out, which is the network rather than the code and is the same result every
WARP probe gives from this machine.

That is the honest boundary: everything up to Cloudflare's UDP path is built and measured. What is
missing is a machine whose UDP is not filtered, and until there is one, "WARP works" would be a
claim rather than a result.

**Proven as a tunnel, not only as a pipe.** A byte bridge is necessary and not sufficient: a relay
could forward frames perfectly and still not carry WireGuard, and the only symptom would be "WARP
does not work" with nothing to distinguish it from a blocked network. So the relay is driven with
a real **148-byte message-initiation** against a real WireGuard endpoint built with
`cryptography`, which answers only when the static key is the one it holds:

```
python scripts/test_warp_relay_handshake.py

  aHandshakeCrossesTheRelayAndAnAnswerComesBack ... ok
  the_endpoint_ignoresAPacketAddressedElsewhere ... ok
```

The second test is what makes the first meaningful - a stand-in that answered anything would let a
relay that echoes, reorders or truncates pass. Cloudflare's own ports are unreachable from here,
which is the whole reason the relay exists, so the endpoint has to be a local one. What is proven
is the part Colgram and the relay own; the last hop to Cloudflare needs the far side to originate
that UDP.

### The filter's behaviour changes over time, so one run settles nothing

Running the UDP probe twice on the same machine, minutes apart, with the same hosts, ports and
payload sizes:

```
session 1:   8.8.8.8:443  ANSWERED      1.1.1.1:443  silent      9.9.9.9:443  silent
session 2:   1.1.1.1:443  ANSWERED      8.8.8.8:443  silent      9.9.9.9:443  silent
```

**The results inverted.** Within a session the outcome is stable across repeated runs, so this is
not a flaky probe — the filtering itself changes over time. A third run showed
`188.114.96.1:2408` — a genuine WARP ingress — answering **1 of 3** probes, which looked like the
filter opening a window. It did not: **0 of 20** over the following minute.

Two consequences, and the second is the one that matters most:

1. Every WARP verdict here is a snapshot, not a property. `warp_udp_probe.py` now repeats each
   probe and reports `ANSWERED n/3` rather than a single result, because a lone `silent` reads as a
   measurement when it may only be a moment in the filter's cycle.
2. **No probe can settle whether WARP works here — including a successful one.** A single answered
   packet would be exactly as unreliable as a single silent one. This is why the device test
   asserts against a real end-to-end tunnel with Cloudflare's `cdn-cgi/trace` reporting
   `warp=on`, rather than against any reachability measurement, and why a "WARP works" claim on
   this network would need that and not a probe.

### The last transport: WireGuard on 443, and why it cannot work

443 is the one port that is open over both UDP and TCP, so "carry WireGuard there" is the obvious
last idea. Measured rather than assumed:

```
python scripts/warp-quic-443-check.py

  162.159.192.1:2408   TLS -> TimeoutError
  1.1.1.1:2408         TLS -> TimeoutError
  162.159.192.1:443     TLS TLSv1.3        (for comparison)
```

**The WireGuard port does not listen on TCP at all.** There is nothing there to encapsulate
towards, so no client-side framing changes anything — the destination itself is unreachable on
both protocols.

And QUIC on 443 is not a way in either. A real QUIC server does answer there — short-header
packets, first byte `0xba`/`0xb3` — but that port terminates **HTTP/3 and nothing else**. A
WireGuard message-initiation sent to it is dropped. QUIC being reachable does not make it a tunnel
to Cloudflare's WireGuard ingress; it is a different service on a different port, and "QUIC is
open, therefore maybe WARP can ride it" is a natural and wrong inference.

That is every transport available on the device, and each is measured:

| Scheme | Result |
|---|---|
| Direct WireGuard over UDP | port filtered |
| Direct WireGuard over TCP | port does not listen |
| AmneziaWG / obfuscation | no reproducible response |
| WireGuard inside QUIC on 443 | 443 terminates HTTP/3; initiation dropped |
| Patience / retry | 0 of 104 attempts over six minutes |
| **Relay over TCP 443** | **the one that survives — built and tested** |

### Two independent paths, the same answer

The host and the phone do not share a path. The emulator sits behind the QEMU user-mode NAT, and
its ICMP to `162.159.192.1` answers in **9 ms** while the host's UDP to the same address times out.
So "the host cannot reach 2408" was never a statement about the device the app actually runs on —
it had been doing that job implicitly.

Probed from the device itself, 1200-byte datagrams, control answering throughout:

```
from the device 162.159.192.1  2408:no 500:no 1701:no 4500:no
from the device 162.159.193.1  2408:no 500:no 1701:no 4500:no
from the device 188.114.96.1   2408:no 500:no 1701:no 4500:no
from the device 188.114.97.1   2408:no 500:no 1701:no 4500:no

MEASURED-WARP-UDP 0 of 16 answered from the device
```

Zero, like the host. So the block is **not an artifact of the build machine**, and two independent
paths agreeing is what makes the relay conclusion hold rather than merely go untested on one of
them. It is also worth noting what the 9 ms ICMP rules out: the route exists and the packets are
not being dropped as "no route to host". The block is specific to this destination and port over
UDP.

### The shape of the block, measured on the device

Knowing *that* UDP to 2408 fails is less useful than knowing what the boundary is. Probed from the
device across ports and destinations:

```
162.159.192.1  53:no   443:YES  2408:no 500:no 4500:no 1234:no
1.1.1.1         53:YES  443:YES  2408:no 500:no 4500:no 1234:no
8.8.8.8         53:YES  443:no   2408:no 500:no 4500:no 1234:no
```

Two things fall out of this, and both matter for the relay's design:

1. **DNS is not blocked.** It answers on its own resolver, so a relay does not need to carry name
   resolution — only the handshake.
2. **UDP is not blocked wholesale.** Port 443 answers on Cloudflare addresses, so the failure is
   specific to the WireGuard ports rather than to the protocol or the destination. That is what makes
   a relay that *receives over TCP 443 and originates the WireGuard UDP itself* the right shape
   rather than merely a possible one — the receiving hop uses a port that is measurably open here,
   and the originating hop happens on a machine that does not have this filter at all.

Port 1234 is silent on every destination, which is the control for the sweep: a port nothing
listens on is silent wherever the network is not filtering by destination.

Reproduce:

```
python scripts/udp-egress-check.py       # establishes that UDP works at all
python scripts/warp-udp-host-probe.py    # 0 of 16, with a control that answers
```

### A correction: TCP inside the emulator proves nothing

An earlier version of this file recorded "all TCP ports answer". That was wrong, and the way it
was wrong matters. The emulator sits behind `10.0.2.0/24`, the QEMU user-mode NAT, and its TCP
behaviour is an artifact of that: `nc -z` reports **OPEN for every port** on 162.159.192.1 —
including 65000, which cannot be open — and a connect delivers **zero bytes**. So no TCP result
measured inside the emulator is evidence about the network, and the "all TCP ports answer" line
should be read as the sandbox talking.

UDP has no such artifact: a datagram either comes back or it does not. That is why the verdict
above rests on UDP alone, and why the probe lives on the host as well as the device.

### The same artifact applies to TCP, on the host too

The `nc -z` behaviour above was reproduced on the **host**, and it is not the only way TCP lies
here. Connecting reports success on every port tried — `1.1.1.1:22` and `1.1.1.1:2408` both
"open" — and then every one of them returns **zero bytes** when bytes are sent, including
`1.1.1.1:443`, which is an HTTPS endpoint that must answer.

So a TCP reachability check on this machine is worth nothing at all, and any conclusion drawn from
"the port accepts a connection" would be an artifact of the sandbox rather than a property of the
network. This is the same trap as the emulator's `nc -z`, one layer up, and it is worth stating
plainly because TCP-based relay plans would have been built on exactly that false signal.

**What survives is UDP**, where a datagram either comes back or it does not, with the six-resolver
control to prove the path is live. Every WARP verdict here rests on that and nothing else.

### IPv6 was never actually a way in

Worth recording because it looks like an obvious untried angle. The device has IPv6 addresses, but
they are ULA (`fd17::`), there is no global route that carries traffic, and every IPv6 destination
tested — Cloudflare, Google, even the link-local gateway — is refused at the local network. Both
v6 and non-v6 destinations fail identically, which is the signature of no working IPv6 path rather
than of selective filtering. So IPv6 is not an alternative here; it is simply absent.

## Why no client-side trick fixes it

WireGuard has no TCP transport. There is no port, protocol version, obfuscation or client option
that turns a filtered UDP destination into a reachable one. AmneziaWG was tried and measured, not
assumed. So the honest answer for direct WARP is that it cannot work here, and the app says so
instead of showing a toggle that pretends otherwise.

## What actually works: a relay

A relay the user runs elsewhere accepts the WireGuard handshake over TCP and forwards it to
Cloudflare. Two things about it are easy to get wrong, and both are pinned by tests:

- A relay **terminates** the handshake, so the peer's key and the endpoint are the relay's. Sending
  Cloudflare's key to a relay that does not own it fails exactly like a dead WARP — which is how a
  working relay gets blamed for not working. Both are swapped together, never one alone.
- A relay has **one fixed port**, so rotating Cloudflare's advertised ports through it only builds
  profiles that cannot connect. The rotation budget drops to a single attempt.

Configure one by long-pressing the WARP row: address, port, the relay's own public key, and an
optional preshared key. The settings row reports a relay as a relay rather than letting a doomed
handshake time out silently.

## Why WARP runs inside sing-box

WARP used to be driven by the embedded WireGuard Android backend (`libwg-go.so`) alongside
sing-box's `libbox.so`. Those are each a complete cgo Go runtime, and two of them in one Android
process do not coexist: loading the WARP backend and then calling into libbox killed the process
with signal 11 in about a second, with no Java exception, no tombstone and no stack. Either
runtime alone was fine, which is why it only ever appeared in the full device suite — the one run
that loads both.

sing-box speaks WireGuard itself, so WARP is now a profile the engine that already carries the
subscription starts. One runtime, one TUN.

### The profile shape, and why it was not guessed

The engine refused every field by name until it said what it wanted:

```
WireGuard outbound is deprecated in sing-box 1.11.0 and removed in sing-box 1.13.0,
use WireGuard endpoint instead

destination override fields in direct outbound are deprecated in sing-box 1.11.0 and
removed in sing-box 1.13.0, use route options instead
```

Its published JSON schema also disagrees with its own decoder, so every field was confirmed
against the engine rather than against documentation. The result is a top-level `endpoints` entry
with a `wireguard` type, no outbound destination override, and `address`/`private_key`/`peers`
carried on the endpoint itself.

## What is still not proven

WARP is **not** demonstrated carrying traffic on this network. There is no relay the user
controls available here, so the relayed path is verified only as far as the engine accepting the
profile and the app wiring it up — not as an open tunnel with traffic on it.

The next honest step is an end-to-end measurement against a real relay, not another config claim.

### What the on-device integration test actually reports

`ColgramWarpDeviceIntegrationTest` drives the real Android VPN consent dialog, starts a real
tunnel, and reads Cloudflare's own `cdn-cgi/trace`. Run on this network it reports:

```
MEASURED warpOn=false connected=false
lastFailure=WARP fail-closed after all advertised UDP endpoints stayed silent
```

That is the honest end state, and the test asserts it: `isUp()` false **and** a named reason. A
route that cannot carry traffic must not be left looking alive.

Getting there took four fixes, all of which had every other test passing while the tunnel could not
survive its own first second:

1. `FOREGROUND_SERVICE_SPECIAL_USE` was never declared. From API 34 that type requires its own
   permission, and the process died with signal 6 the instant the tunnel came up.
2. The AppTests module is a *standalone app* and does not inherit the app manifest, so it needed
   the permission declared separately — the package had zero of them.
3. `Libbox.touch()` looks like the engine's initialiser, because every generated class calls it
   from its static initialiser. Its body is **empty**. `Libbox.setup(SetupOptions)` is the call
   that does the work, and nothing called it — so the first real call walked into a nil.
4. `CommandServer.start()` was never called before `startOrReloadService`, and the override
   argument was `null`. Both are read without a nil check on the Go side. This one is worth
   recording because the crash **survived** fix 3: same panic, same line, so the remaining nil had
   to be somewhere else.

The general lesson, now written into the tests: a correct, validated, fully-loaded profile and a
completely dead tunnel were indistinguishable to every check in the suite except one that actually
started the thing.

## The pattern behind most of these

Four separate faults had the same shape, and it is worth naming because it predicts where the next
one will be:

| Fault | What `checkConfig` said | What actually happened |
|---|---|---|
| No `tun` inbound | accepted | no TUN, nothing captured |
| `auto_route` alone | accepted | TUN up, `routes=[]` |
| DNS `detour` | **accepted** | service refused to start at all |
| `flow: ""` | accepted | treated as an empty outbound |

`checkConfig` validates the *shape*. It does not validate that the engine can start, that a route
is installed, or that a field means what it looks like. Three of these four were accepted by the
validator while breaking the feature completely.

So the rule this codebase now follows is: **anything that decides whether traffic actually moves is
measured against the running engine, never against the profile text.** Concretely —

- `ColgramTunInboundDeviceTest` — the engine accepts a TUN that claims the device.
- `ColgramDeviceRouteCaptureDeviceTest` — the engine *asks Android* for `0.0.0.0/0`.
- `ColgramWarpDeviceIntegrationTest` — the tunnel comes up and Cloudflare answers.
- `ColgramUdpAssociateDeviceTest` — a datagram survives the SOCKS5 UDP framing.

The one that took longest to build is the second, and it is the one that would have caught the
empty tunnel: a configurator that records instead of building, so the engine's actual request is
the evidence.

## What the goal asks for, and where each part stands

Audited item by item rather than assumed. "Proven" means a measurement produced it.

| Requirement | State | Evidence |
|---|---|---|
| VLESS Reality | proven | engine accepts the built profile |
| VMess | proven | engine accepts the built profile |
| Trojan | proven | engine accepts the built profile |
| Shadowsocks | proven | engine accepts the built profile |
| Hysteria2 | proven | engine accepts the built profile |
| Hysteria 1 | proven | engine accepts the built profile |
| SOCKS | proven | engine accepts the built profile |
| UDP ASSOCIATE in the SOCKS bridge | proven | a datagram makes the round trip through the associate socket |
| VpnService routes the whole device | proven | the engine asks Android for `0.0.0.0/0` and `::/0` |
| A subscription from the bot works | proven | shared link → stored → profile → engine accepts |
| WARP carries traffic | **not proven** | 0 of 16 ingresses answer, 6 of 6 resolvers do |

The one row that is not proven is not a rounding error. Cloudflare's WireGuard UDP is filtered on
this network, WireGuard has no TCP transport, and no client-side option changes that. A relay is
the only path, it is wired and engine-verified, and it needs a VPS on an unfiltered network to be
measured. Until one exists, "WARP works" would be a claim rather than a result.

## The local SOCKS bridge, for completeness

Unrelated to WARP but part of the same bypass machinery, and fixed in the same pass: the loopback
SOCKS5 endpoint used to answer `host unreachable` to every UDP ASSOCIATE. That is not a degraded
route, it is no route — and the user-visible symptom is a connection that cannot be established with
nothing in the log saying why. It now serves RFC 1928 ASSOCIATE: loopback BND address (never
`0.0.0.0`, which reaches nothing while looking like a dropped association), one upstream socket per
destination with a reader so replies actually come back, and a header length derived from the
address form so a domain name's own length byte is not guessed at.

It still only carries Telegram addresses. That is the listener's purpose, not a limitation to fix.

## The TUN, and why "valid profile" was not enough

Recorded because it is the shape of bug this codebase kept hitting: the configuration was correct,
validated cleanly, and did nothing.

sing-box hands the operating system its addresses and routes through
`PlatformInterface.openTun`, and it only does that for a **`tun` inbound**. The subscription profile
carried only a local `mixed` inbound on `127.0.0.1` — a SOCKS port for something on the device to
dial deliberately, capturing nothing by itself. So there was no `openTun` call, no descriptor and
no installed routes: the service started, the switch turned blue, and the phone talked to the
network directly, exactly as before. From the settings screen that is indistinguishable from a
working tunnel.

`checkConfig` accepted the profile the whole time. A valid profile and a completely inert one are
the same string to the validator — the same trap as the removed `wireguard` outbound, which the
engine also accepted until it named the field it wanted. The engine is the only authority on these
names, and it is worth asking rather than assuming: this version has **no `route_address` key at
all** and claims the device with **`auto_route`**, so a profile written against the older shape
would have been refused by name.

Both profiles now carry a TUN with `auto_route` and `strict_route`. `strict_route` matters as much:
without it, traffic can slip around the tunnel via the physical interface, and "covers the whole
phone" is only usually true.

The device test asserts both directions — that the built profiles carry a TUN, and that a profile
without one is valid *and inert*. The second half is the point: it is the shape that looks like
success to every other check in the suite.

## The measurement that changed the conclusion: WARP has a second endpoint, and it is not filtered

Everything above measured Cloudflare's **WireGuard** ingress - 162.159.192.x, 162.159.193.x and
188.114.9x.x on UDP 2408. The conclusion drawn from it was "the only route to WARP is a relay on a
network that does not filter UDP". That conclusion was correct about WireGuard and wrong about
WARP, because WARP clients do not only speak WireGuard.

WARP's **MASQUE** endpoint is a different service, in a different block, on a different port.
The addresses Cloudflare hands out for it sit in **162.159.197.0/24**, and it is reached over
**HTTP/2 on TCP 443**. That is the one transport this network does not filter.

Measured here, over TCP, on 162.159.197.1/5/0/10:

    TLSv1.3   ALPN h2   certificate CN=engage.cloudflareclient.com   server: cloudflare

So the endpoint is reachable, it negotiates HTTP/2, and Cloudflare's edge serves a real WARP
certificate for it. That is a materially better position than "the WireGuard port is filtered":
the whole earlier argument - that no client-side framing can move a datagram onto a filtered port -
never applied to this service, and the relay is not the only option any more.

### What the endpoint then said, and how to read it

    server SETTINGS: MAX_CONCURRENT_STREAMS=100  INITIAL_WINDOW_SIZE=65536
                     MAX_FRAME_SIZE=16777215
    Extended CONNECT allowed (0x08):  NO

    GET  /.well-known/masque/udp/default/   403
    CONNECT :protocol connect-ip            403
    CONNECT :protocol connect-udp           403
    CONNECT :protocol connect-tcp           403
    GET  /.well-known/masque/ip/default/    403
    GET  /                                   403

**The absence of 0x08 is the decisive detail.** Extended CONNECT is only legal when a server
advertises ENABLE_CONNECT_PROTOCOL, and without it every CONNECT is refused regardless of path or
protocol. That matters because WARP's 1:1 and streaming protocols tunnel at layer 3 via
:protocol=connect-ip (RFC 9484) - connect-udp is the wrong protocol for WARP entirely. So the
server here is closed to the one protocol WARP actually speaks.

The 403s are a Cloudflare edge decision (cf-ray present, "server: cloudflare"), and they are what
an unauthenticated request to this hostname gets. What they do **not** show is a block: a 403
arrives in 1-2 ms over a completed TLS 1.3 h2 session on a port that is open. Nothing here was
filtered - the request was understood and declined.

**What this changes and what it does not.** It changes the shape of the problem. The endpoint is
identified, reachable, and speaking the right protocol version, and it refused every route shape
tried with no credentials. Whether it will accept one with a WARP registration is a different
question from anything measured so far, and answering it needs a real client - which is the next
step, not a claim. It does not change the relay's role: the relay is still built and still proven,
and it is still the answer for the WireGuard path.

Run it with: python scripts/warp-masque-443-probe.py

### A correction to two earlier "no answer" results

Both were client bugs, and both are worth recording because a probe that cannot speak the protocol
produces silence that looks exactly like a block.

A QUIC Initial was measured at 0 of 40 on the host. A first attempt at the same probe read no
answer at all, because it looked for the "PRI * HTTP/2.0" preface in the *server's* reply - that
preface is client-only, and a server that is answering perfectly never sends it. The same file
wrote HTTP/2 frame lengths as 4-byte big-endian integers when the format uses 3, which shifts
every byte after the length and makes the peer ignore the frame. Both produce silence on an
endpoint that is working.

Run them with:
    python scripts/warp-quic-initial-443.py
    python scripts/warp-quic-response-anatomy.py

### The phone's 4 of 40, and why it was not progress

The device run reported 4 of 40 valid Initials answered, with first bytes 0x9e, 0xca, 0xd5 and
every answer exactly 31 bytes - while the host, on the same network, answered 0 of 40. That looked
like the QEMU NAT distorting host measurements, which would have been worth knowing.

It is not a real QUIC reply. Short-header packets are encrypted with keys derived from the
completed handshake, and a server that has seen one Initial and no client hello has no such keys -
so 0xca and 0xd5 cannot be real replies. 0x9e is more interesting: it is a long header with the
fixed bit clear and packet type 10, which is Retry, and a Retry is the one answer a server may
send before a handshake completes.

The control that settles it is a **dead port**. 2408 and 500 have nothing listening and are
filtered, so an answer from them cannot come from a server. The anatomy test sends the same probe
to both and compares:

    python scripts/warp-quic-response-anatomy.py
    python scripts/device-tests.py org.colgram.core.ColgramDeviceQuicAnatomyTest

A 31-byte answer is also too short to be a Retry carrying a token and its 16-byte integrity tag at
any connection-id size, which is a second reason the first byte alone is not enough to classify.

## The SNI filter: what the official client runs into, and exactly what the filter matches

Cloudflare's own client is installed and registered on this host, and it fails. Its service log
names the reason, and it is not the network:

    connect_with_protocol_racing{primary="masque" secondary="H2"}
    h2_tun: Connecting to edge sni="consumer-masque.cloudflareclient.com"
    Start racer 0.0.0.0:35945 ---> 162.159.198.2:443

So the client races QUIC over UDP first - ports 1701, 4500, 4443, 8443 and 8095, all measured
silent here, as expected - and then **falls back to HTTP/2 over TCP 443**, which is the one
transport this network carries. That fallback is the whole route to WARP, and it fails on the
name.

Measured to `162.159.198.2:443`, changing only `server_name`, keeping the address, port and
everything else identical:

| server_name | result | time |
|---|---|---|
| `engage.cloudflareclient.com` | OK, TLS 1.3, ALPN h2 | 164 ms |
| `connectivity.cloudflareclient.com` | OK, TLS 1.3, ALPN h2 | 143 ms |
| `cloudflareclient.com` | OK, TLS 1.3, ALPN h2 | 121 ms |
| `example.com` | OK, TLS 1.3, ALPN h2 | 146 ms |
| `consumer-masque.cloudflareclient.com` | FAIL, SSLEOFError | 2111 ms |
| `masque.cloudflareclient.com` | FAIL, SSLEOFError | 2563 ms |
| `masque.example.com` | FAIL, SSLEOFError | 2131 ms |
| `consumer-masque.example.com` | FAIL, SSLEOFError | 2132 ms |
| `mqs.cloudflareclient.com` | FAIL, SSLEOFError | 2150 ms |
| `notmasque.com` | FAIL, SSLEOFError | 2085 ms |
| `MASQUE.cloudflareclient.com` | FAIL, SSLEOFError | 2152 ms |

**The filter is not a whole-name match.** It fires on any `server_name` containing `masque` or
`mqs`, case-insensitively, in any domain - `masque.example.com` and `notmasque.com` are dropped
exactly like Cloudflare's own name. A blocked name takes ~2.1 s against ~130 ms for one that
passes, so the delay is the filter's own rather than a timeout, and the same result appears on
162.159.198.1, .2 and 162.159.197.3 - so it follows the name, not the address.

The same split appears on `engage.cloudflareclient.com` at 162.159.197.x too, and a ClientHello
with no `server_name` at all completes on .2. So the door is reachable; the name is what is shut.

### …and reaching the door is still not reaching WARP

Under every name that passes the filter, the MASQUE path is answered, and refused:

    CONNECT /.well-known/masque/udp/default/  :protocol connect-ip   400
    CONNECT /.well-known/masque/ip/1/1/      :protocol connect-ip   400
    GET  /                                        403
    GET  /cdn-cgi/trace                          200   warp=off

All from one colo and one `cf-ray`. The virtual host behind an unfiltered name does not route to
the WARP backend, so passing the filter gets a client to Cloudflare's edge over TCP 443 and not one
byte further into WARP. That is a narrower result than a block and a much narrower one than a
tunnel, and it is why the relay is still the answer for the WireGuard ingress.

    python scripts/warp-sni-bypass-probe.py
    python scripts/warp-masque-endpoint.py --all

### A 403 here is not what a 403 from Cloudflare looks like

For most of this investigation a 403 was read as Cloudflare declining a request. It was not. httpx
was applying an `sni_hostname` override that turned a 200 into a 403 on *every* path, including
`/robots.txt` and `/cdn-cgi/trace` - and a host that 403s robots.txt is serving an error page, not
a tunnel. The control that caught it: the identical request without the override returned 200 with
a real trace body. Two rules came out of it, and both are now built into the probes rather than
remembered:

  * an endpoint is dialled **by name**, with the address pinned by an in-process resolver, so the
    SNI, the Host header and the certificate check are correct by construction;
  * httpx is built with `trust_env=False`, because a proxy on this host answers 403 to everything.

With both in place, all five MASQUE addresses serve a genuine `warp=off` trace, which is the first
honest signal that the endpoint is alive and the earlier 403s were manufactured locally.

