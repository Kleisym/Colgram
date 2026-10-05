# What the edge actually accepts, and what the two alerts mean

All measurements 2026-09-30, source `192.168.0.4`, QUIC only (see the note at the end on why TCP is
not an instrument here).

## The two alerts separate two different failures

This is the finding that matters, and it came from a sweep over certificate shapes rather than from
reasoning about the protocol.

```
no certificate at all                    -> alert 116  certificate_required
Ed25519 certificate (4 shapes)          -> alert 116  certificate_required
P-256 certificate (7 shapes)            -> alert  49  access_denied
```

The Ed25519 rows are identical to the no-certificate control, and that is informative rather than
redundant. `certificate_required` is what TLS 1.3 sends when the client sent no certificate at all.
So for Ed25519 the server never considered the offered certificate a client certificate: the
CertificateRequest's acceptable-signature-algorithms list does not include Ed25519, the client
declined to send anything, and the server answered exactly as it would to a bare client.

P-256 gets a *different* answer. `access_denied` is only reachable after the certificate has been
transmitted and parsed. So the edge is receiving the P-256 certificate, reading it, and deciding it is
not one it accepts.

That is real progress, and it reframes the problem: the obstacle is not "build a certificate" - a
certificate was built and it arrived. The obstacle is that it is not the right certificate.

## Certificate shapes tried, all P-256 rejected with access_denied

- CN=cloudflare-client, plain
- CN=cloudflare-client, CA:TRUE
- CN=cloudflare-client, clientAuth EKU
- CN=cloudflare-client, no basicConstraints extension
- CN=cloudflare-client, keyUsage digitalSignature
- CN=cloudflare-client, clientAuth EKU + SAN=the edge SNI
- CN=registration_id, CA:TRUE
- CN=account_id, CA:TRUE
- CN=license, plain
- CN=client_id, plain
- CN=cloudflare-client + O=WARP
- CN=cloudflare-client + OU=WARP
- certificate built from the key that was registered for this device

Ed25519, four shapes (plain, CA:TRUE, clientAuth EKU, no basicConstraints): all `certificate_required`,
i.e. never sent.

## Why the right certificate cannot be synthesised

The binary lists the fields it reads out of a certificate it has been given:

```
certificate_id  cn  certificate  check_private_key  extended_key_usage  locations  subject_alternative_names
```

`certificate_id` is the decisive one. It is not something a client invents - it is the identifier
Cloudflare issued with the certificate, and it appears again in the posture result as
`client_certificate` / `client_certificate_v2` / `certificate_id`. A certificate without Cloudflare's id
is a certificate the edge has no record of, which is exactly the shape of `access_denied`.

The issuance path, from the binary:

```
RenewApiCertificateResponse { mtls: MtlsCertificate { certificate } }
MtlsCertificateTunnelKeyType { curve25519, secp256r1 }
stage enum: ... ValidatingProxyConfiguration -> EnsuringMtlsIdentity
errors: FailedToReadDERSecrets, FailedToCreateSelfSignedCertificate, FailedToBuildConnectRequest,
        InvalidPkixConfig, InvalidKey, UnableToEnsureMtlsIdentity
```

`EnsuringMtlsIdentity` is a connection stage, after proxy validation and before the tunnel, and it
fails at `FailedToReadDERSecrets` - the material is DPAPI-protected and held by the daemon. There is
no file for it, no registry blob, an empty `tpm_keys` table in `warp.db`, and no copy in any task dump.

The renewal endpoint was queried with a registered P-256 key and a valid CSR, three path shapes by
three verbs, with the registration token in both header spellings: all 404, each delayed 5 seconds by
Cloudflare, so the shape cannot be recovered by sweeping.

## Why QUIC and not TCP

The official client's own log shows this edge failing its H2 attempt with
`TLS handshake failed unexpected EOF` and `AllChecksFailed` - not a certificate error. Reproduced
here: the same host, port and certificate, one connection returning
`TLSV13_ALERT_CERTIFICATE_REQUIRED` and the next returning 0 bytes with no error at all.

`certificate_required` is a post-handshake alert, and on a blocking socket a silent close and a
post-handshake alert are indistinguishable. QUIC carries the alert in a CONNECTION_CLOSE frame, so it
survives. Three runs per condition, stable:

```
no certificate -> alert 116, 116, 116
self-signed    -> alert  49,  49,  49
```

## Where this leaves it

The network is proven from both the host and the device. The protocol is confirmed as masque by the
registration. The edge is at 162.159.198.2 with six answering ports. The response schema for the
certificate is known exactly, and a P-256 certificate demonstrably reaches the edge and is refused
there.

What is missing is one artifact: a certificate bearing a `certificate_id` Cloudflare issued, which
only the running daemon can obtain.

**No `warp=on` measurement exists. The tunnel does not work.**