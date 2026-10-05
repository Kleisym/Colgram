# WARP MASQUE: status on 2026-09-30

## The errors are decoded now

aioquic encodes a TLS alert as `CRYPTO_ERROR (0x100) + alert`:

| Code | Decodes to | Meaning |
|---|---|---|
| 372 = 0x174 | 0x100 + 116 | TLS `certificate_required` |
| 305 = 0x131 | 0x100 + 49 | TLS `access_denied` |

Not abstract numbers. The edge is saying, in order: a certificate is required, and the
certificate you presented is not one I accept.

## The find: C:\ProgramData\Cloudflare\conf.json

This is the official client's own state file, and it settles the key question:

```json
"tunnel_key_data": {"key_type": "secp256r1", "tunnel_type": "masque"}
"own_public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE..."   <- 91-byte SPKI, secp256r1
"endpoints": [162.159.198.2:443, :500, :1701, :4500, :4443, :8443, :8095]
```

The tunnel identity is a locally generated P-256 key, and there is no Cloudflare-issued
certificate anywhere in the picture: the service logs `pkix_config: None` and no `ca_bundle`
exists.

## Tested and ruled out

| Hypothesis | Test | Result |
|---|---|---|
| Edge does not implement extended CONNECT | QUIC handshake, ALPN h3 | disproved |
| All Cloudflare UDP is blocked | QUIC Initial to 162.159.198.2 | disproved, Retry in 90ms |
| P-256 is required, not X25519 | registration with `tunnel_key_data {secp256r1, masque}` | HTTP 200, server echoes the key |
| Certificate must carry the registered key | self-signed cert from that same key | 305, no help |
| Certificate subject matters | 8 variants: CN=client/reg_id/account/license/client_id, +O, +OU, +SAN | 305 every time |
| API will issue the certificate | 12 paths on api.cloudflareclient.com and api.devices.cloudflare.com | all 404 |

**Conclusion:** every combination of a locally generated key with a self-signed certificate is
refused with `access_denied`, independent of the subject and independent of whether the key was
registered. The certificate has to come from a CA the edge trusts, and it is not obtainable
through the public API.

## Where this stopped

The official client raises WARP on this machine, but Colgram cannot reproduce that without the
certificate. The only remaining way to observe a live CONNECT-UDP is with WARP enabled on this
host, which is off limits here.

No `warp=on` measurement exists on any device. The tunnel does not work.
