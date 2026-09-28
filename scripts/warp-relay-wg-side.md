# The relay's WireGuard side

`scripts/warp-relay-server.py` is a **transport** relay: it moves bytes between a TCP client and
the Cloudflare WireGuard endpoint. That is the whole job on the *blocked* side of the network, and
it is proven — a real 148-byte message-initiation crosses it and comes back.

What it is **not** is a WireGuard implementation. It cannot be one, and that is worth being
precise about rather than blurring:

- The peer on the relay box authenticates the client with its **own WireGuard private key**.
- The endpoint on the far side of the relay is **Cloudflare's own WireGuard ingress**, reached from
  a network that does not filter its UDP.

So the relay sits in the middle of a WireGuard session rather than terminating one. It sees
encrypted packets and forwards them; it cannot read them, and it holds no key material. That is
why the two sides can be built and tested independently, and it is also why the Colgram client
still needs the *relay's* WireGuard public key: the relay's box runs a WireGuard **interface** that
the client peers with, and that interface forwards the client's traffic out over UDP.

## What that means for a working setup

```
Colgram  --TCP 443-->  relay box  --UDP 2408-->  Cloudflare WARP
 (WireGuard client)   (WireGuard interface
                       + TCP bridge)
```

The relay box needs three things, and the script covers the third:

1. A WireGuard interface, so the client has something to peer with. `wireguard-go` is already
   vendored at `vendor/colgram-wireguard/wireguard-go` and `libwg-go.so` is already in the APK.
2. A UDP route to Cloudflare's ingress on a network that does not filter port 2408.
3. The TCP bridge, which is `scripts/warp-relay-server.py`.

Only (3) is code this repository can supply end to end, and it is done and tested. (1) and (2) are
properties of a machine, not of code.

## What is proven, and what is not

| Claim | Evidence |
|---|---|
| The TCP bridge carries a frame out and an answer back | `scripts/test_warp_relay.py` |
| It carries a real 148-byte WireGuard handshake intact | `scripts/test_warp_relay_handshake.py` |
| The endpoint answers only its own peer's key | same test, second case |
| Colgram sends its handshake to the relay over TCP 443 | profile carries the relay address, port and the relay's key; `ColgramWarpSingleRuntimeDeviceTest` |
| The far side reaches Cloudflare's UDP | **not measured** — needs a host without the filter |
| Cloudflare answers `warp=on` | **not measured** — needs the whole chain |

The last two are the ones that would make "WARP works" true rather than likely, and neither can be
established from a network where Cloudflare's WireGuard ports are silent on every protocol tried.
