# WARP: what is actually established

Written after the peer's cryptography was finally checked against upstream rather than against another
copy of itself. Supersedes `warp-what-is-true-now` as it stood, which was wrong in a way that mattered.

## Established by measurement

**A WireGuard session in both directions, against upstream wireguard-go.**

`tools/wgref/wgref.exe` is a real `wireguard-go` device (go1.23.4, commit `ecfc5a8d5446`) built from the
upstream module. The device-side peer in `colgram-core` and `TMessagesProj_AppTests` was pointed at it and
the run repeated three times:

```
PASS run 1/3: handshakes=1 decrypted=8 reject=counter=7
PASS run 2/3: handshakes=1 decrypted=8 reject=counter=7
PASS run 3/3: handshakes=1 decrypted=8 reject=counter=7
ALL RUNS PASS
```

On the wireguard-go side of the same runs: `Received handshake response`, and
`last_handshake_time_sec` non-zero. That timestamp is written by `timersHandshakeComplete`, which runs
only after the AEAD open and the replay check both pass, so it is the far end saying it decrypted
something under the keys the handshake produced. `decrypted=8` on the peer side is the mirror image.

This is a pass in both directions: the peer is a correct responder for upstream, and the Python client in
`tools/wgref/client_check.py` is a correct initiator for it. Neither is checked against a stand-in.

**And the same on a real device, in the other direction.** `ColgramDeviceWarpPeerTest` runs libwg-go on
an emulator and points it at the peer in this repository:

```
control answered=true, handshakes 0 -> 1
session state: UP
handshakes=3 transportDecrypted=104 decryptFailures=0
OK (1 test)
```

`transportDecrypted` is the number that means something: those packets were opened under keys derived
from a handshake this peer answered. `decryptFailures=0` says they opened rather than merely arrived.

That test replaced one that could not fail. The earlier `ColgramDeviceKeepaliveTest` printed a verdict
and returned, so it reported OK whether or not anything had happened, and its control probe sent an
initiation with an unencrypted body - which a correct responder is required to ignore. Reading that as
"the peer is not answering" is how a broken tunnel came to be reported as a working one, and the test
is still in the tree alongside the new one.

**The primitives, against vectors from outside this project.**

`WgNoise` passes 31 vectors: BLAKE2s from Python `hashlib`, keyed BLAKE2s from both `hashlib` and
`golang.org/x/crypto/blake2s`, and KDF1/KDF2/KDF3 verbatim from wireguard-go `device/kdf_test.go`. The
same set is a JUnit test (`WgNoiseVectorTest`) and a host run (`WgNoiseHostCheck`).

**Cloudflare UDP is unreachable from the development machine.**

`1.1.1.1:443` UDP: 0/10 replies. HTTPS to `www.cloudflare.com`: 200. Loopback UDP: works. The egress is a
WireGuard tunnel (`VPNUS`) belonging to Amnezia, AS206491, Helsinki - not the network under test. So no
statement about WARP in a filtered network can be made from this machine, and none is made here.

## Not established

**WARP itself has never been shown to work in the network it is meant for.** No measurement of
`engage` or `warp=on` exists. Everything above is about WireGuard, which is the transport WARP runs over;
a correct transport is necessary for WARP and is not sufficient for it.

No device in the network under test is reachable. `adb devices` lists only MuMu emulators. The realme is
visible over PnP (`VID_22D9&PID_2769`) but not attached, so there is no path to a real measurement until
USB debugging is on and the phone is in file-transfer mode, or a host with open Cloudflare UDP is
available as a relay.

## What was wrong before, and why it looked fine

The earlier version of the peer shared its mistakes with a Python responder written by the same hand,
so the two agreed perfectly and a test passed. The mistakes, each of which produces well-formed bytes
and no error:

| Was | Is | How it was found |
|---|---|---|
| HMAC-SHA256 for mixKey | HMAC-**BLAKE2s** | KDF vectors from wireguard-go |
| SHA-256 for the initial hash | `blake2s(construction)` | hashlib vectors |
| encrypted-empty 48 bytes (then 24) | **16** (`poly1305.TagSize`) | the device rejected the response |
| mac1 as a plain hash of a prefix | **keyed** BLAKE2s-128 | `Received packet with invalid mac1` |
| `precomputedStaticStatic` treated as a PSK | static-static **DH**, mixed unconditionally | the timestamp did not authenticate |
| transport counter read at offset 4 | offset **8** | the counter ran into the index field |
| transport sealed with 4 zero bytes of AAD | AAD is **nil** | `rx_bytes` never moved after a good handshake |
| response macs keyed by the responder own key | keyed by the **initiator** public key | `Received packet with invalid mac1` |
| initiation sender field holding the 32-byte static | a 4-byte **session index** | a 176-byte frame, rejected on length |
| mac1 computed over the 40-byte header | over the whole 116-byte **prefix** | the device rejected it as an invalid mac1 |

Two of these are worth keeping in mind for anything else in this project. A value that is the wrong
length is loud; a value that is the right length and the wrong contents is silent, and only a vector or
an independent implementation will say so. And a responder written to agree with a client proves
nothing unless the responder is upstream.

## Files

- `Telegram-Src/colgram-core/src/main/java/org/colgram/core/WgNoise.java` - BLAKE2s, HMAC, KDF1/2/3, mixHash, mixKey
- `Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core/WgPeerResponder.java` - the responder
- `Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core/Peer.java` - the test-facing wrapper
- `Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core/WgNoiseVectorTest.java` - vectors as JUnit
- `Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core/WgNoiseHostCheck.java` - the same vectors off-device
- `tools/wgref/` - the upstream device, the Python initiator, and the repeatable both-ways run
