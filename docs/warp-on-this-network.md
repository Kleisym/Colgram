# WARP on this network — what was measured, and what is left

Everything below was measured from the device or the app, not inferred. A verdict here means a
measurement produced it; a claim without one is marked as such.

## The block is external and total

Measured on the device, repeatedly:

| Probe | Result |
|---|---|
| UDP DNS to 1.1.1.1 / 8.8.8.8 | answers, milliseconds |
| UDP to all 16 Cloudflare WireGuard ingresses (ports 2408, 500, 1701, 4500) | **silent** |
| TCP to those same hosts/ports | no WireGuard reply |
| AmneziaWG, extended ranges, junk packets, S1/S2/S5/S6 | no reproducible response |

UDP itself is not broken — the control probe answers. Cloudflare's WireGuard ingress specifically
does not answer from this network.

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

## The local SOCKS bridge, for completeness

Unrelated to WARP but part of the same bypass machinery, and fixed in the same pass: the loopback
SOCKS5 endpoint used to answer `host unreachable` to every UDP ASSOCIATE. That is not a degraded
route, it is no route — and the user-visible symptom is a connection that cannot be established with
nothing in the log saying why. It now serves RFC 1928 ASSOCIATE: loopback BND address (never
`0.0.0.0`, which reaches nothing while looking like a dropped association), one upstream socket per
destination with a reader so replies actually come back, and a header length derived from the
address form so a domain name's own length byte is not guessed at.

It still only carries Telegram addresses. That is the listener's purpose, not a limitation to fix.
