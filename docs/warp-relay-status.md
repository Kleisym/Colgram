# The relay: what is built, and what it still needs

## The mechanism is there

`ColgramWarp.setRelay` and `ColgramWarpProfileBuilder.build` take a relay address, a relay public key and
an optional preshared key, and substitute all three at once. That detail is deliberate and was arrived at
the hard way: a relay terminates the WireGuard handshake itself, so pointing Cloudflare's peer key at a
relay that does not own it fails in a way that looks exactly like a dead WARP. Swapping the address and
the key together is what makes a relay usable at all.

The host side exists too, in `scripts/`: `warp-relay-server.py` carries frames, `warp-relay-peer.py` is
the peer on the far side, and `warp-relay-to-cloudflare.py` runs the end-to-end question. That last one
runs and reports:

```
initiation   : 148 bytes, a real WireGuard message-initiation
cloudflare   : 162.159.192.1:2408
relay        : 127.0.0.1:51821 -> 162.159.192.1:2408

direct UDP   : silent
through relay: silent

VERDICT: neither direct UDP nor the relay reached WARP. No route from this host.
```

## Why that result says nothing about the relay

The relay in that run is on this machine, and this machine's own Cloudflare UDP is filtered. So the run
asks a relay on a filtered path to reach a destination that is filtered on that same path. It measures the
filter twice. A relay earns its name by living somewhere the filter is not, and there is no such place
involved in this run.

This is the same mistake in a different costume as the one the probe itself made: a control that shares
its author's assumptions cannot fail in a way that teaches anything. The relay has never been tested
from a host that can see Cloudflare, because there has not been one.

## What a real test needs, and nothing less

A host with open Cloudflare UDP, running the same relay, with this machine reaching it over TCP 443. Then
the path is: this machine, over TCP, to a host that is not filtered, out over UDP, to Cloudflare. A
message-response in reply to a registered initiation means WARP is reachable through a relay from a
network whose own Cloudflare UDP is blocked, which is the thing the relay exists to make true.

The relay code does not need to change for that. It needs a host.

## The blocker, stated once

Every measurement in this project agrees, and none of them can be moved past from here:

- `162.159.192.0/24` on every WireGuard port: silent to an initiation a real endpoint accepts
- `1.1.1.1:53`: silent, and stays silent at +30s and +60s while `8.8.8.8:53` answers every time
- ALPN on TCP 443 to Cloudflare: `h2` only, never `h3`, so HTTP/3 and MASQUE are out
- TCP 443 to Cloudflare: open, TLS 1.3 completes

One of those rows is usable, and it is the one a relay needs. Nothing here can substitute for a host that
has the other rows working.
