
# Where WARP stops, and what that was established from

Cloudflare WARP does not work on this build. It is not a claim of impossibility: it is a statement of
the last point on the path that has been measured, with the evidence for it, and the list of things
that have been ruled out along the way.

Nothing below is inferred. Every line is something read off the device, or out of the engine binary.
Where a conclusion was reached and then disproved by measurement, that is written down too - several
were, and leaving only the surviving ones would misrepresent how much of this is settled.

## The last measured point

The tunnel comes up. The endpoint is handed a working tun. Traffic reaches the engine. No datagram is
ever sent to the peer.

The chain, in order, from one run on the device:

```
ColgramVpn:    the engine is initialised, working in .../files/libbox
ColgramVpn:    the profile is 870B and starts: {"log":{"level":"debug"...
ColgramVpn:    the endpoint 10.0.2.2/32 stays off the tunnel
ColgramVpn:    establish() returned a descriptor, fd 190
ColgramSingbox: openTun: descriptor held here, fd 190
ColgramVpn:    tunnel starting with profile .../colgram-warp.json
```

Six subsystems, six lines, all of them succeeding. The file descriptor is 190 on both sides, so it
reached the engine intact and is not double-owned. And the relay, listening the whole time, received
nothing.

So the loss is inside the engine, after it holds the tun and before it sends to a peer. The engine
offers no log for that stretch: `writeLog` is absent from `PlatformInterface`, and
`libbox_PlatformInterface_WriteLog` does not occur in the 85 MB binary.

## Ruled out by measurement

| Suspect | How it was excluded |
|---|---|
| The network, a filter, Cloudflare's UDP being blocked | tcpdump on the device itself, filtered to the relay's port. No datagram leaves. |
| The host's NAT hiding the source | the capture is on the device, before any NAT. An emulator's own text, `hello-from-device`, arrives at a host socket as `127.0.0.1`. |
| The profile's shape | `sing-box/protocol/wireguard.RegisterEndpoint` is present, `RegisterOutbound` is not. The endpoint form is the only one this build has. |
| The profile's contents | accepted at 870 bytes with no error, carrying every field the engine declares. |
| The private key | replaced with one generated here, arithmetic shown in `scripts/make-test-keypair.py`, verified by deriving its public key with X25519. Same result. |
| The VPN consent | the system dialog is accepted, `establish()` returns a descriptor. |
| The tun | `openTun` receives the same fd `establish()` produced. |
| Descriptor ownership | held in a field, never closed; `onRevoke` does not touch it. |
| The route rule | everything routes to the endpoint tagged `warp`, and traffic arrives: the engine's listener answers with 47 and 26 bytes. |
| The route exclusion | `10.0.2.2/32 stays off the tunnel` is logged by our own code when the exclusion is applied. |
| The peer, and the outbound-peer shape | `endpoints[0].peers` is accepted; `outbounds[0].peers` is rejected by name, `unknown field`. The engine says which shape it wants. |
| mac1 | not offered: `endpoints[0].peers[0].mac1: json: unknown field`. There is no such field, and the engine computes the cookie itself. |
| Routing a loop, the endpoint's handshake going into its own tunnel | `tun0` does not exist during the run, and a capture of `tun0` is empty. There was no tunnel to loop through. |

## Conclusions that were reached and then disproved

These are here because removing them would overstate what is settled.

**"The engine sends a 148-byte initiation with an empty body."** It does not. A 148-byte datagram whose
only non-zero byte is its type byte is not a WireGuard message - an initiation carries a 32-byte sender
static key at offset 4. The packets of that description were the tests' own 64-byte bare probe and
host traffic, and the attribution came from reading a capture that mixed writers.

**"The device never reaches the relay."** It does reach it. `10.0.2.15` appears as a source in captures
taken on the device, and the relay logs 1200-byte datagrams from a tunnelled run. The endpoint-only
run sends nothing, which is a different fact and not evidence about the tunnelled one.

**"The engine has no peer field, so the profile cannot describe a peer."** It does. `peers` occurs twice in
the binary, neither beside the endpoint's own struct, and a regex that did not allow the `&` that
marks an embedded field missed it. The engine accepts `endpoints[0].peers` without complaint, which
settled it: an unknown field is refused by name, as `mac1` was.

**"`route_exclude_address` is not a field the engine has."** It is:
`RouteExcludeAddress&json:"route_exclude_address,omitempty"`. The ampersand marks an embedded
struct, and a listing that allowed only a letter or a space skipped it. It was removed on that basis
and the removal measured as a regression - the relay became unreachable as soon as there was traffic
to carry - and was restored.

**"The engine is not told which interface to use, so its socket follows the default route into the
tunnel."** Plausible, and the code says `usePlatformAutoDetectInterfaceControl()` returns false. But
there was no tunnel to route into: `tun0` did not exist in that run.

## What the remaining paths are

Two, and neither is reachable from here without something this repository does not have.

**The engine's own source.** Every question above is answerable in one line of Go if the source for
this build is available. The binary can be read, and has been - it named the version, the wireguard-go
dependency, the build tags, the registration, and the absence of a logging entry point - but strings
cannot name a code path that logs nothing.

**Not the engine at all.** The repository carries a complete wireguard-android backend:
`libwg-go.so` for four ABIs, `GoBackend`, `Tunnel`, and the Java side. It was set aside because
loading it and then calling into libbox segfaults the process - two cgo Go runtimes in one Android
process. That is recorded in the code at
`colgram-core/src/main/java/org/colgram/core/ColgramWarpTunnel.java`.

## What has not been tried

Honestly, and because it matters more than another measurement:

- A `libbox` build of sing-box for this version, compiled from source, with the endpoint's send path
  visible. The current binary is a black box on exactly the one question that is left.
- Running wireguard-go in a separate process from libbox - a helper binary, not a second runtime in
  the app - which would sidestep the segfault instead of avoiding the feature.
- Whether the peer address being a literal IP rather than a hostname matters to the endpoint's
  resolution path. Never tested, and cheap to test.

## The measurement instruments, and what each of them already got wrong

They are here because each one produced a wrong answer at least once, and a script that has not been
wrong yet has not been tested.

| Script | What it answers | How it went wrong before |
|---|---|---|
| `capture-packets.py` | every packet, split on the header line | the accumulating parser merged packets and reported one size of four |
| `peer-reaches-message.py` | which WireGuard fields were written | read UDP header bytes as message fields at a hardcoded offset |
| `who-sends-what.py` | which of two runs wrote to the wire | trusted a source address that NAT erases |
| `name-payload.py` | what a datagram is, by content | could not attribute, and said so rather than guessing |
| `relay-tally.py` | per-source counts | the same NAT problem, in the relay rather than the script |
| `make-test-keypair.py` | a keypair with the arithmetic shown | not wrong; it ended a hypothesis by substitution |
| `list-engine-json-fields.py` | the engine's declared fields | a pattern that did not allow `&` hid an embedded field |
| `endpoint-struct-window.py` | fields around a type name | window size guessed, then shown to be wrong by the distances |

## The discipline that produced all of this

Every conclusion above was read off the device, and every wrong one was caught the same way: by
measuring the thing the conclusion was about, rather than by reasoning further about the thing already
measured. Three conclusions were reversed this way, and each reversal came from a check that cost one
command:

- a packet's source address, read on the host, where an emulator's own traffic is `127.0.0.1`
- a field's presence, read by a regex that skipped a one-character marker
- a capture's packet count, read by a parser that carried state across boundaries

The engine's log is unavailable and the source is not here, so the next honest step is to obtain one
of them rather than to keep measuring the part that already works.
