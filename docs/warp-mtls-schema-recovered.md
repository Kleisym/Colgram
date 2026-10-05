# The mTLS certificate: schema recovered, issuance path not

## The response shape, read out of the binary

The struct field tables in `warp-svc.exe` give the exact shape of the certificate response, which no
amount of guessing had produced:

```
struct RenewApiCertificateResponse with 1 element  ->  field: mtls
struct MtlsCertificate with 1 element              ->  field: certificate
MtlsCertificateTunnelKeyType { curve, 25519, secp256r1 }
MtlsCertificate { certificate, WarpAuthResponse { token } }
```

And the request side, `WarpApiRegistrationPayload` in order:

```
type model key tos gateway_device_id os_version os_version_extra serial_number
warp_connector_token tunnel_key_data identifiers mtls_csr mac_address
```

`mtls_csr` is a registration field, not a separate endpoint. That is why every
`/v0a2158/accounts/{acct}/reg/client_certificates` spelling returned 404 - the path was never the
issue. The certificate endpoint in the binary is `/v1/accounts/../reg/api-certificate/renew`, and its
fragments are composed at runtime, so it cannot be recovered by reading the strings.

## mTLS is a connection stage

The stage enum of the official client's tunnel setup reads:

```
PerformingHappyEyeballs -> EstablishingConnection -> InitializingTunnelInterface ->
... -> ValidatingProxyConfiguration -> EnsuringMtlsIdentity
```

`EnsuringMtlsIdentity` sits between proxy validation and the tunnel itself, and the errors around it
are `InvalidKey`, `FailedToEnsureMtlsIdentity`, `UnableToUpdateMtlsStatus`,
`UnableToUpdateMtlsIdentitity`. So the identity is ensured per connection, from a key the client
already holds - not fetched at registration. `conf.json` agrees: a locally generated secp256r1
`own_public_key`, `install_root_ca: false`, and no certificate file anywhere on disk.

`MtlsCertificateTunnelKeyType` carries the curve, which is why the key type is part of the request
rather than an inference. Registering with `tunnel_key_data {key_type: secp256r1, tunnel_type:
masque}` is accepted (HTTP 200, server echoes the key), so that half is right.

## What was tried against the renewal path

Three path shapes x three verbs (POST, PATCH, PUT), each carrying `mtls_csr` over a registered P-256
key, with the registration token in both `CF_Authorization` and `Authorization`:

```
/v1a2158/accounts/{acct}/reg/api-certificate/renew   POST/PATCH/PUT  404
/v0a2158/accounts/{acct}/reg/api-certificate/renew   POST/PATCH/PUT  404
/v1a2158/accounts/{acct}/api-certificate/renew        POST/PATCH/PUT  404
```

Cloudflare answers 404 after a 5-second delay, so this cannot be resolved by sweeping: every wrong
guess costs 5 s and the answer is identical either way.

## One correction to an earlier claim

The TCP probe of this edge is not a reliable instrument, and an earlier reading of it was wrong. On the
same host, port, and certificate, one connection returned `TLSV13_ALERT_CERTIFICATE_REQUIRED` and the
next returned 0 bytes with no error - and the official client's own log shows the same endpoint failing
with `TLS handshake failed unexpected EOF` and `AllChecksFailed` rather than a certificate error. A
silent close and a post-handshake alert look identical from a blocking socket.

QUIC is the reliable instrument, because the alert is carried in the QUIC CONNECTION_CLOSE frame
instead of being lost with the socket. Three runs per condition:

```
no certificate      -> alert 116, alert 116, alert 116   (certificate_required)
self-signed cert    -> alert  49, alert  49, alert  49   (access_denied)
```

Stable and unambiguous. The certificate requirement is real; the TCP measurements that appeared to
contradict it were measuring the socket, not the protocol.

## Where this leaves it

The network path is proven from both the host and the device: QUIC to 162.159.198.2 answers with a
Retry in under 200 ms, confirmed against a DNS control that returns a real FORMERR. The tunnel
protocol is confirmed as masque by the registration itself. The response schema for the certificate
is now known exactly.

What is missing is the issuance call itself. The official client performs it inside `EnsuringMtlsIdentity`
using DPAPI-protected secrets; there is no public endpoint that serves it, and the desktop
registration expired on 2026-09-29, so there is no captured exchange to read.

**No `warp=on` measurement exists. The tunnel does not work.**
