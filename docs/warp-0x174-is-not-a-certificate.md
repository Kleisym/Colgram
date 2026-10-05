# 0x174 is not a certificate error, and that changes the blocker

## The measurement that overturns the previous conclusion

One client, one protocol, one ALPN, no client certificate offered in any case:

```
8.47.69.0     SNI cloudflare-quic.com                  -> ALIVE, ALIVE
8.47.69.0     SNI one.one.one.one                      -> ALIVE
162.159.198.2 SNI consumer-masque.cloudflareclient.com -> 0x174, 0x174
162.159.197.4 SNI connectivity.cloudflareclient.com    -> 0x128, 0x128
```

`8.47.69.0` is the address `warp-diag` itself used for its HTTP/3 check, and it completed with a 200
over QUIC. The same code, the same TLS stack, the same ALPN, reaches it without difficulty.

So `0x174` is not "this edge requires a client certificate". `one.one.one.one` is Cloudflare's public
resolver - mTLS is not part of how anyone talks to it - and the same code path produced `0x174` there
too. A requirement that applied to the public resolver would not be a requirement about certificates.

## Why the certificate reading was wrong

The arithmetic was right and the conclusion was wrong. `0x174 = 0x100 + 116`, and 116 is the TLS
alert number for `certificate_required`, and aioquic encodes TLS alerts as `CRYPTO_ERROR + alert`. But
QUIC error code `0x174` is not obliged to be `CRYPTO_ERROR` plus a TLS alert: a server may send a
CRYPTO_ERROR with a value of its own choosing, and this one does.

The corroboration that made it look like a certificate was also misread. Presenting a P-256
certificate changed 372 into 305 (`access_denied`, `0x131`). That difference is real, but it is not
evidence about certificates - a server that has already decided to fail a session will produce a
different code depending on what else it noticed about the connection.

The cost of that wrong reading was a long investigation into certificate shapes: twelve subjects,
Ed25519, EKU, SAN, CA, key usage, `custom_cert_settings`, `ca_bundle`, the renewal endpoint, and the
recovered `RenewApiCertificateResponse { mtls: MtlsCertificate { certificate } }` schema. All of it was
chasing an artifact the edge was never asking for.

## What the packet trail actually shows

```
recv   95 B   0xf0  Retry
recv 1200 B   Initial
recv  943 B   Handshake
ProtocolNegotiated   ALPN h3
HandshakeCompleted
recv   59 B   server H3 SETTINGS
ConnectionIdIssued
ConnectionTerminated  0x174
```

The TLS handshake completes, ALPN negotiates, the server sends its HTTP/3 SETTINGS and issues a
connection ID, and only then does it terminate. A server that had rejected the client outright would
not keep servicing the connection past its own SETTINGS frame. This reads as the edge accepting the
transport and then failing a session proof that runs after the TLS layer.

For MASQUE that proof is the tunnel handshake, and it is where the registration token and the tunnel
key material belong. The registration this project performs is accepted - HTTP 200,
`policy.tunnel_protocol = "masque"`, server echoes the key - so what is missing is the presentation of
that identity in the post-TLS phase, which is `EnsuringMtlsIdentity` in the official client's stage
enum, and that stage is not reachable without the service running.

## Unchanged

- The network is not the obstacle. QUIC reaches 162.159.198.2 from both the host and the device, and
  Cloudflare's own diagnostic gets HTTP/3 200 over QUIC on this network.
- There is still no `warp=on` measurement, and the tunnel still does not work.

## What this means for the next attempt

Do not look for a certificate. Look at what the edge does after `HandshakeCompleted`, which is where a
MASQUE client presents its identity. The recovered response schema
`RenewApiCertificateResponse { mtls: MtlsCertificate { certificate } }` is a real Cloudflare
structure, but it belongs to the identity stage, and the failure now observed is one step earlier.
