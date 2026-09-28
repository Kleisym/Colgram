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

## The join was broken until this was measured: the two sides spoke different transports

Everything above credits the relay with work that had only ever been checked on the relay's own
side of the wire. Nobody had checked whether Colgram speaks the relay's protocol at all. It does
not, and could not have:

```
Colgram profile (with a relay set):
    endpoints: [{ type: "wireguard", peers: [{ address: <relay>, port: <relay port> }] }]
    outbounds: [{ type: "direct", tag: "direct" }]

relay before this change:
    listener = socket.socket(AF_INET, SOCK_STREAM)   # TCP, 2-byte length-prefixed frames
```

A sing-box WireGuard endpoint dials its peer over **UDP**. The relay listened on **TCP** with a
framing no standard UDP sender produces. The app sent UDP datagrams at a port that was reading a
TCP handshake, and the initiation never left the phone.

The failure mode is why this went unnoticed. The profile carries the relay's key *and* the relay's
address together, which is exactly the correct identity for a relay that terminates the handshake -
so it validated, started, installed routes, and the settings switch turned blue. From the app it is
indistinguishable from a blocked network, which is what this network produces for every other
reason. A profile with the relay's key and Cloudflare's address would have failed loudly and been
found in minutes.

**The fix** is a UDP listener alongside the TCP one, so the port Colgram is told to use is a port
something actually answers:

```
python scripts/warp-relay-server.py --listen 0.0.0.0:51820 \
    --udp-listen 0.0.0.0:51820 --endpoint 162.159.192.1:2408
```

UDP is the right shape rather than the convenient one: a WireGuard peer *is* a UDP peer, so this
needs no new client code, whereas carrying the length-prefixed framing over UDP would mean inventing
a transport Colgram does not speak. The framing stays on the TCP listener for anyone driving the
relay by hand. The UDP listener is opt-in and serves one client at a time, because a WireGuard
endpoint opens no session of its own that the relay could authenticate the choice against - run one
per client behind a firewall.

    python scripts/test_warp_relay_join.py

### A detector that matched its own documentation

The test that catches this needed three attempts, and the wrong two are worth recording because the
test was *passing* while the join was broken:

  * **first**, it searched the file's text for `SOCK_DGRAM`. The startup hint prints a one-liner
    containing `socket.SOCK_DGRAM` so an operator can test the far side, so the search found that
    sentence and invented a UDP listener that did not exist. Green.
  * **second**, it walked the parse tree for every socket constructor. Better - and still wrong,
    because it asked "does this program construct a UDP socket", which is yes: the relay sends to
    Cloudflare over UDP. That is the outbound end of the bridge and says nothing about whether a
    client can reach it. Green.
  * **third**, it asks which socket is `bind()`-ed and `listen()`-ed. One qualifies, and it is
    `SOCK_STREAM`. Red, with the real gap named.

A detector that matches its own documentation is worse than no detector, because it is believed.
The lesson generalises past this file: the 403s earlier in this project were the same shape - a
client that could not tell "the server said no" from "my client did not read the answer".

### And a framing bug the tests were built to miss

The TCP listener read its length prefix with `client.recv(2)`. A TCP read returns whatever has
arrived, not the two bytes asked for, so a prefix split across two segments - one byte in each -
unpacks as a 256-byte frame, the next frame's first byte is consumed as the rest of that length,
and the framing desynchronises until the connection drops.

That is the failure a WireGuard handshake is most likely to cause: a 148-byte initiation written in
one burst is free to arrive as three segments, and the first boundary can fall inside the
two-byte prefix. The existing tests could not see it because they sent each frame with one
`sendall` and read each answer with one `recv(2)` - **the test made the same assumption as the
bug**, so a relay that only survived whole writes passed. Reverting the fix to `recv(2)` makes the
split-segment test fail with `ConnectionAbortedError`, which is what makes it worth having.

## What is proven, and what is not

| Claim | Evidence |
|---|---|
| The TCP bridge carries a frame out and an answer back | `scripts/test_warp_relay.py` |
| It carries a real 148-byte WireGuard handshake intact | `scripts/test_warp_relay_handshake.py` |
| The endpoint answers only its own peer's key | same test, second case |
| **The same handshake crosses the UDP listener** | same test, third case - the hop Colgram can actually take |
| The UDP listener carries a 148-byte initiation out and back | `scripts/test_warp_relay.py` |
| Colgram and the relay agree on a transport | `scripts/test_warp_relay_join.py` |
| Colgram sends its handshake to the relay over TCP 443 | profile carries the relay address, port and the relay's key; `ColgramWarpSingleRuntimeDeviceTest` |
| A relay started with `--udp-listen` answers a real DNS query over both hops | run below, 64 bytes, QR bit set on each |
| The far side reaches Cloudflare's UDP | **not measured** — needs a host without the filter |
| Cloudflare answers `warp=on` | **not measured** — needs the whole chain |

The last two are the ones that would make "WARP works" true rather than likely, and neither can be
established from a network where Cloudflare's WireGuard ports are silent on every protocol tried.

### The relay, running for real

Both hops were driven against a live instance rather than only in unit tests, pointed at a target
that genuinely answers on this network - `1.1.1.1:53` - so the bytes coming back are Cloudflare's
and not a stand-in's:

```
python scripts/warp-relay-server.py --listen 127.0.0.1:15120 \
    --udp-listen 127.0.0.1:15120 --endpoint 1.1.1.1:53

relay listening on 127.0.0.1:15120, forwarding to 1.1.1.1:53 over UDP
UDP listener on 127.0.0.1:15120 - this is the port to put in Colgram

UDP through relay: 64B from 127.0.0.1:15120, QR bit=1
TCP through relay: 64B, QR bit=1
```

64 bytes with the QR bit set is a real DNS response, over each hop, in both framings. It says the
relay moves bytes correctly end to end in the two shapes it offers - and it deliberately says
nothing about WARP, because the endpoint on the far side here is a resolver, not Cloudflare's
WireGuard ingress. The distinction is the whole point of the table above.

### The join assertion that had never been in a compiling build

Worth recording because it is the same failure mode one level up. The device test gained a UDP
check on the relay path, and it was added without compiling it first: the assertion called a helper
that takes Strings with an int, and the test module failed to compile 7 minutes 22 seconds into a
run that had therefore executed **no tests at all**. The green results quoted for that class before
this were green for a different reason - the new assertion was not in them.

So the check now compares the datagram's **bytes** rather than its length, which is the stronger
claim anyway: a relay answering with an error page of exactly 148 bytes would pass a length check,
and an error page is precisely what this file exists to catch.

The general form is the one this project keeps rediscovering. A test that is never executed and a
test that is executed and passes look identical in a commit message, and so does a probe whose
client could not read the answer and a probe the server refused. Compile it, run it, and read the
output - in that order, every time.
