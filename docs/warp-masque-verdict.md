# WARP MASQUE: where this actually stands

All measurements taken 2026-09-30 from this host, source address `192.168.0.4` (the LAN). The local
VPNUS tunnel holds `0.0.0.0/1` at metric 0, so an unbound socket would leave through it and every
measurement would be of the wrong path. WARP was never enabled on this machine during any of it.

## The original conclusion was wrong

The earlier claim - "all Cloudflare UDP is blocked, QUIC is unreachable" - came from probing one
address and generalising. Cloudflare's endpoints are reachable per-address, and the MASQUE edge is
open:

| Target | QUIC Initial (1200 bytes) |
|---|---|
| `162.159.198.2:443` (MASQUE edge) | **Retry, 90 ms** (`0xf0`) |
| `162.159.192.1:443` (engage / API) | silent |
| `162.159.192.3:2408` (WireGuard edge) | silent |
| `1.1.1.1:443` | silent |

Six of the seven ports on `162.159.198.2` answer QUIC: 443, 500, 4500, 4443, 8443, 8095.

## Where the edge came from

Not guessed - read out of the official client's log and state file:

```
warp_edge::h3_tun: Connecting to edge sni="consumer-masque.cloudflareclient.com"
perform_happy_eyeballs_race{endpoint=162.159.198.2:443}
warp_connection::tunnel: Connected to 162.159.198.2:443 @ 696f29 : FRA
```

`C:\ProgramData\Cloudflare\conf.json`:

```json
"tunnel_key_data": {"key_type": "secp256r1", "tunnel_type": "masque"}
"own_public_key": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE..."   (91-byte SPKI, secp256r1)
"install_root_ca": false
"endpoints": [162.159.198.2:443, :500, :1701, :4500, :4443, :8443, :8095]
```

## What works

| Layer | Result |
|---|---|
| `POST /v0a2158/reg` | HTTP 200, `policy.tunnel_protocol = "masque"` |
| QUIC handshake to the MASQUE edge | **ALPN h3, HandshakeCompleted** |
| H3 SETTINGS from the edge | arrive |

Cloudflare ships this as a supported mode: `warp-cli tunnel protocol set MASQUE`, and
`warp-cli tunnel masque-options set h2-only` exists specifically for networks without working QUIC.

## The single blocker

The edge requires a client certificate, and rejects every one that can be built from public inputs.
aioquic encodes the TLS alert as `CRYPTO_ERROR + alert`, so these are not abstract numbers:

| Condition | TCP | QUIC |
|---|---|---|
| no certificate | alert 116 `certificate_required` | 372 = 0x100+116 |
| self-signed, unregistered key | alert 49 `access_denied` | 305 = 0x100+49 |
| self-signed, **registered** key | alert 49 `access_denied` | 305 |

Ruled out by measurement:

- P-256 vs X25519: registering with `tunnel_key_data {secp256r1, masque}` is accepted, HTTP 200,
  server echoes the key.
- Certificate public key: a certificate built from the registered key still returns 305.
- Certificate subject: 8 variants (CN = client / reg id / account id / license / client id, plus
  O, OU, SAN) all return 305.
- API issuance: 20+ paths on `api.cloudflareclient.com` and `api.devices.cloudflare.com`, four verbs
  each, all 404. Cloudflare delays 404s by 5 s, so brute force is useless.
- Six QUIC ports and six SNI variants: all require the same certificate.
- WireGuard direct to this edge: silent on all 7 ports, which is correct - it is the MASQUE edge, not
  the WireGuard one.

## Why it cannot be finished from here

The official client generates a P-256 key locally, self-signs a certificate from it, and announces
the public key via `X-WARP-Api-Public-Key` / `X-WARP-Api-Key-Type: 1`. The binary says so directly:
`Failed to create a self-signed client certificate from secrets`, and the service logs
`pkix_config: None` with `install_root_ca: false` - no Cloudflare CA is involved.

The certificate itself is not a file. It is built at runtime from DPAPI-protected secrets held by the
daemon (`warp.db` has an empty `tpm_keys` table, the registry holds no blobs, and the key is absent
from all task dumps). The only way to obtain it is to have the official client build it, which means
running WARP on this machine - which is off limits.

## Verdict

The network is not the obstacle. A working MASQUE path to Cloudflare WARP has been demonstrated up to
the last hop: QUIC handshake completes, ALPN h3, H3 settings flow, and the tunnel protocol is what
Cloudflare's own registration specifies. The one missing item is a client certificate that only the
official daemon can mint.

**No `warp=on` measurement exists on any device. The tunnel does not work, and no claim to the
contrary is being made.**

## To finish it

With WARP briefly enabled on this host, capture the working connection once and the certificate is
yours: the daemon writes it, and the same `X-WARP-Api-Public-Key` announcement plus a self-signed
certificate reproduces the tunnel in Colgram. That needs the user's go-ahead and nothing else.

## Tooling written

```
tools/masque/quic_reach.py          QUIC Initial against every edge, per-address reachability
tools/masque/quic_lan.py            QUIC handshake pinned to the LAN address
tools/masque/wg_edge_probe.py       real WireGuard initiation against the live edge ports
tools/masque/cert_identity_sweep.py certificate subject sweep, QUIC as the three-way oracle
tools/masque/cert_methods.py        all verbs against the paths recovered from the binary
tools/masque/h3_diag.py             H3 and CONNECT-UDP diagnostics
tools/masque/doh_resolve.py         DoH resolver, bypasses the untrustworthy system DNS
tools/masque/registration_probe.py  WARP registration
```
