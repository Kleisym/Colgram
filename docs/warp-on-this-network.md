# WARP on this network — what was measured, and what is left

Everything below was measured from the device or the app, not inferred. A verdict here means a
measurement produced it; a claim without one is marked as such.

## The block is external and total

Measured on the device, and re-measured from the host on the same network:

| Probe | Result |
|---|---|
| UDP DNS, 6 resolvers (1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8, 208.67.222.222, 94.140.14.14) | **all 6 answer, 64B** |
| UDP to all 16 Cloudflare WireGuard ingresses (ports 2408, 500, 1701, 4500), a real message-initiation | **0 of 16 answer** |
| AmneziaWG, extended ranges, junk packets, S1/S2/S5/S6 | no reproducible response |

UDP itself is not broken — six independent resolvers answer from the same host, immediately before
the sweep. Cloudflare's WireGuard ingress specifically does not answer.

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
