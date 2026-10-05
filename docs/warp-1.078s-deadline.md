# The edge's deadline is 1.078 s, and no request shape changes it

## The timeline, to the millisecond

Against 162.159.198.2, with every event timestamped from the first transmit:

```
0.093s  <- 95B    Retry
0.187s  <- 1200B  Initial
0.203s  <- 943B   Handshake
0.218s  ProtocolNegotiated
0.218s  HandshakeCompleted
0.297s  <- 59B    H3 SETTINGS
0.297s  ConnectionIdIssued
1.078s  ConnectionTerminated  0x174
```

The edge issues a connection ID at 0.297 s and then says nothing for 781 ms before terminating. Those
781 ms are its own deadline, and it is a deadline rather than a judgement: the same code keeps it
against four different request shapes, and the same code on a different edge stays alive
indefinitely.

The official client used 16 ms of that window:

```
13:02:07.092  Established QUIC connection
13:02:07.093  Starting MASQUE proxy task  idle_duration: 5s, keepalive_interval=1s
13:02:07.108  creating new flow for MASQUE request   (tokio_quiche http3 driver, client.rs:319)
13:02:08.334  Connected to 162.159.198.2:443 @ 696f29 : FRA
```

Sixteen milliseconds after the handshake, not 1.2 s. The tunnel came up 1.24 s after the connection
was established, which is the same total this project reaches by being cut off at the end of it.

## The request sweep, and the bug that made the first one meaningless

The first version reported `sent=False` on all twelve rows and was therefore testing nothing: it gave
the handshake 50 ms to complete, and the measured timeline puts `HandshakeCompleted` at 218 ms, so the
request was never sent. With the wait fixed to 600 ms, twelve shapes, three targets, each with and
without the RFC 9298 `capsule-protocol: ?1` header, each with and without `:protocol: connect-udp`:

```
edge   capsule=True  proto=True  sent=True  status=None  term@1.422
edge   capsule=True  proto=False sent=True  status=None  term@1.234
edge   capsule=False proto=True  sent=True  status=None  term@1.390
edge   capsule=False proto=False sent=True  status=None  term@1.078
wg     capsule=True  proto=True  sent=True  status=None  term@1.218
wg     capsule=True  proto=False sent=True  status=None  term@1.203
wg     capsule=False proto=True  sent=True  status=None  term@1.218
wg     capsule=False proto=False sent=True  status=None  term@1.219
doh    capsule=True  proto=True  sent=True  status=None  term@1.234
doh    capsule=True  proto=False sent=True  status=None  term@1.188
doh    capsule=False proto=True  sent=True  status=None  term@1.203
doh    capsule=False proto=False sent=True  status=None  term@1.203
```

All twelve sent, none produced a status, all terminated on the same schedule. The edge is not
rejecting these requests on their merits. It is not reading them. The 781 ms expires with the request
unanswered, and the termination is the timer, not a verdict.

## What the successful client had that these twelve do not

The gap is now narrow and specific. Between the handshake and the flow creation, the official client
does work this project cannot: it runs `EnsuringMtlsIdentity`, which reads DER secrets, builds a
self-signed client certificate from them, and constructs a connect request. The stage sits after proxy
validation and before the tunnel, and its failures are named in the binary:
`FailedToReadDERSecrets`, `FailedToCreateSelfSignedCertificate`, `FailedToBuildConnectRequest`,
`InvalidPkixConfig`, `InvalidKey`.

That work happens between 0.218 s and 0.234 s in the successful trace - about 16 ms - which is the
same 16 ms as the flow creation, because the flow is created once the identity is ready. The edge
allows 781 ms for it. This project has 781 ms of budget and nothing to put in it.

The material is not on disk: `warp.db` has an empty `tpm_keys` table, the registry holds no blobs under
either Cloudflare key, no `.pem` or `.key` exists under the install tree, and the public point appears
in none of the task dumps. It is DPAPI-protected state inside a daemon that is not running.

## The two keys, for the record

```
RegistrationInfo.public_key = b205ae9a...e466115   32 bytes, X25519
conf.json own_public_key    = 91-byte SPKI, secp256r1, tunnel_key_data {secp256r1, masque}
```

Registrations performed here send a 32-byte scalar in `key`, which is the X25519 shape, and the server
echoes it back unchanged. The shape is right; what is missing is the other half.

## Where this leaves it

Everything reachable without the daemon is now closed by measurement: the certificate hypothesis,
SNI, ALPN, ten QUIC transport parameters, post-quantum, the absence of a request, and now twelve
request shapes across three targets. The edge terminates on a 781 ms timer that starts when it issues a
connection ID, and the only thing that has ever satisfied it is identity material the desktop daemon
builds from DPAPI-held secrets.

**No `warp=on` measurement exists. The tunnel does not work.**
