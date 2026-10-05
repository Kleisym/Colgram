# Two different things called a relay, and the app uses the second

## The one in scripts/ forwards packets

`warp-relay-server.py` accepts 2-byte-length-prefixed frames over TCP and re-originates each one as a UDP
datagram to a WireGuard endpoint, returning whatever answers. It holds no keys and terminates nothing.

Measured, on this machine, against a real wireguard-go device as the far end:

```
relay listening on 127.0.0.1:51820, forwarding to 127.0.0.1:56501 over UDP
client from 127.0.0.1:59774
                              (no response came back)
```

The reason is not a bug in the framing. It is that a WireGuard message-initiation cannot be forwarded:

```
packet keyed for the relay   -> Received packet with invalid mac1
packet keyed for the device  -> Received invalid initiation message
```

mac1 is a keyed hash over the packet, keyed by the STATIC PUBLIC KEY of the receiver. A transparent
forwarder does not hold the receiver's key, so the packet it forwards fails the first check at the far
end. And re-keying it at the relay is not possible either: the relay is not the endpoint, so it has no
private key for the static that the ciphertext is sealed to.

So a packet-level forwarder cannot carry a WireGuard handshake. That is a property of the protocol, not
of this implementation, and it is worth writing down because the relay's own docstring implies
otherwise.

## The one the app uses terminates the tunnel

`ColgramWarpProfileBuilder.build` takes a relay key and does this:

```java
if (relayKey != null && !relayKey.trim().isEmpty()) {
    // The relay owns the handshake, so its key is the one the peer must be pinned to.
    peer = relayKey.trim();
}
```

The endpoint stays the relay's address, and the peer key becomes the relay's. The phone then runs a
normal WireGuard session - with the relay as its peer, not with Cloudflare. The relay holds the second
half of that session, and originates the Cloudflare-facing side of the traffic from wherever it lives.

This is the right shape, and it is the only shape that works with a keyed mac. It also means the relay is
not a dumb pipe: it needs a WireGuard implementation, a key, and enough state to carry two sessions.

## What the existing relay test actually proves

`ColgramDeviceRelayWireGuardTest` starts a host-side helper that ECHOES the packet it receives, and
asserts the phone's datagrams reach a foreign host and come back intact. Its own docstring says so:

> The peer is a host-side helper ... which echoes the exact packet it received rather than speaking
> WireGuard itself.

So the test proves the hop, not the tunnel. That is a real and useful result - a phone can reach a foreign
host over UDP and get a reply - and it is not evidence that a tunnel can be built through that hop, which
is the claim that matters here. The two are routinely conflated, and reading the first as the second is
how a working-looking relay ends up blamed for not working.

## What a real relay needs, then

1. A WireGuard responder for the phone's session - the same one this repository already has in
   `WgPeerResponder`, verified against upstream wireguard-go in both directions.
2. A second WireGuard session from the relay to Cloudflare, originated from a network where Cloudflare's
   UDP is not filtered.
3. Traffic forwarded between the decrypted inner packets of the two sessions, not between the encrypted
   outer ones.

Item 3 is the part neither existing piece does. The peer here terminates one session; the relay script
forwards encrypted datagrams. Neither chains them, and that chaining is what a relay actually is.

## Built and measured

`tools/wgref/relay/main.go` is that relay, and it runs. Both halves are upstream wireguard-go devices in
one process, with the plaintext of one handed to the other:

```
phone  <-- session A -->  down half  --- plaintext ---  up half  <-- session B -->  far end
```

Run against a real wireguard-go device standing in for Cloudflare, three times:

```
relay answered the phone: 92 bytes, type 2
up last_handshake_time_sec=1790722568
down ... rx=rx_bytes=148

PASS: the relay answered the phone and established its own session to the far end

run 1 PASS   run 2 PASS   run 3 PASS
```

Both halves are real sessions. The 92 bytes the phone received is a message-response that decrypts under
keys from a handshake the relay actually performed, and the non-zero handshake timestamp on the up half
is written only after that half's own session with the far end completes. Neither number is a count of
packets that moved.

## Two bugs worth recording, because both produce a plausible failure

**Passing a public key where a private one belongs.** `wgref` and the relay both take `-private-key` as
hex, and a public key is also 32 bytes of hex. Handed the public key, a device comes up cleanly: a port
binds, a peer is created, the state dump looks right. And then it rejects every initiation with an
invalid mac1, because the static it seals and checks against is one nothing else can derive. The two hex
forms differ in the last byte, which is exactly the sort of thing a log line does not show.

**Sending the same initiation twice.** A test that wrote to one socket and read from another sent the
identical packet twice. The responder answered the first and dropped the second as a replay - the
timestamp and ephemeral were the same - so the device log showed a handshake and the client saw nothing,
which reads as a peer that accepted nothing at all.

## What this changes

The relay is no longer the part that needs building. It is built, it is made of the reference
implementation rather than a hand-written one, and it terminates a session on each side. What is missing
is a place to run it: a host where Cloudflare's UDP is not filtered. Point `--up-endpoint` at
`162.159.192.x:2408` from such a host, hand the phone the down half's public key, and the tunnel is
complete.

That is now a deployment step rather than an engineering one, and it is worth being explicit that it is
the only step left rather than claiming the tunnel works from a host that cannot reach Cloudflare.

## The device leg is blocked by the emulator, and it is worth saying so

`ColgramDeviceRelayTunnelTest` exists and runs: it reads the device key out of the WARP registration,
builds a profile naming a relay, starts the session, and asserts the relay saw a handshake. It does not
pass here, and the reason is not the relay.

The emulator cannot reach a UDP port on the host at all. Measured, with a listener on the host and the
device as the sender:

```
TCP  10.0.2.2:19702   reached
UDP  10.0.2.2:19701   not reached
```

That is the emulator's NAT, not a filter on the network under test and not a rule this project added.
WireGuard is UDP, so the device leg of a relay cannot be exercised from here at all - the packets never
leave. The test fails on its assertion rather than hanging, which is deliberate: a test that waits for a
packet that the emulator will never forward is a test that reports nothing for its whole timeout.

What did get established on the device, and stands on its own: `ColgramDeviceWarpPeerTest` has libwg-go on
the emulator complete a handshake with the peer in this repository and decrypt 87 transport packets
under the derived keys, with zero decrypt failures. The engine works, the profile is accepted, and a
session comes up. What is unproven from here is only the hop from a real phone to a relay elsewhere.

## The carriage direction, and what was actually established

A relay that completes two handshakes is still not a relay: it can satisfy every counter in this project
and carry nothing, so the forwarding was checked separately rather than inferred.

The TUN plumbing makes the direction easy to get backwards, and the wrong way round is completely
silent. A WireGuard device READS the `Outbound` channel to send what it has to the network, and WRITES
`Inbound` with whatever arrived. A forwarder that reads `Outbound` on one half competes with the device
for the same channel and carries nothing while both sessions still look healthy. The relay reads
`Inbound` of one half and writes `Outbound` of the other, which is the only arrangement where a decrypted
packet can reach the far end.

What is established:

```
relay answered the phone: 92 bytes, type 2
up last_handshake_time_sec=1790724606
injected 49 bytes into the up half
```

The up half has a live session with the far end, and it accepts an inner packet. What is not established
is that the packet arrives at the far end: `rx_bytes` there did not move past the handshake. The remaining
candidate is the routing decision inside the up device, which upstream performs silently - a packet
whose destination falls outside the peer's allowed IPs is dropped without a line saying so. That is the
next thing to establish, and it needs a destination the up half is actually configured to carry.

## Both directions, established

The forward direction needed the TUN plumbing got right, which is the whole of it. A WireGuard device
READS the `Outbound` channel to learn what to send and WRITES `Inbound` with whatever arrived, so a
forwarder that reads `Outbound` competes with the device for the same channel and carries nothing while
both sessions still look healthy. The relay reads `Inbound` of one half and writes `Outbound` of the
other.

```
up right after inject: tx_bytes=180
up two seconds later: tx_bytes=276      (the 97-byte datagram went out)
far end rx_bytes: 180 -> 276           (and arrived)
```

The reverse direction needed the initiator to answer, which a minimal test client does not do. A
responder derives its keypair only after the initiator responds, and a responder with no keypair can
neither decrypt what the far end sends nor encrypt a reply. Reading the reply and stopping there proves
the forward direction and nothing else, and the reverse then fails for a reason that has nothing to do
with the relay.

With the answer sent, both halves complete and the relay carries in both directions:

```
up session  : up last_handshake_time_sec=1790725666
down session: down last_handshake_time_sec=1790725669
reverse injected: yes
PASS: both sessions complete and the relay carries in both directions

run 1 PASS   run 2 PASS   run 3 PASS
```

Three more bugs were on the way there, each producing a plausible failure. The transport reply must be
addressed with the responder SENDER index rather than its receiver index - the packet is otherwise
encrypted correctly and dropped at the index lookup. The tau that KDF3 produces is mixed into the
transcript before anything is opened with it, and leaving it out makes only the encrypted-empty fail.
And the PSK split happens before the transport keys, from the chain key it produces.

## What this leaves

The relay is done and proven in both directions. What is not done is a run against Cloudflare itself,
which needs a host whose UDP is not filtered, and a device leg, which needs a real phone because the
emulator NAT does not carry UDP to the host at all.
