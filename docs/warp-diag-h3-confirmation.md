# Official diagnostic run: H3 works, and here is the bypass path

Run 2026-09-30 01:44 UTC with `warp-diag.exe --output ... -v`. This is Cloudflare's own tool, it ran
without the WARP service (which reported `Failed to communicate with WARP service over IPC`), and it
did not touch the tunnel: `CloudflareWARP` stayed Stopped, 0 WARP adapters, default route unchanged
on Ethernet. It is a collector, not a connector.

## H3 over QUIC: successful, measured by Cloudflare's own code

```
Testing H3 QUIC connectivity to 'https://cloudflare-quic.com/cdn-cgi/l4-stats'
  result: Successful
  version = HTTP/3.0, status = 200, server = cloudflare
  server address = 8.47.69.0:443
  transport = QUIC,  http = HTTP/3
  sent = 5, recv = 9, lost = 0, retrans = 0
  sent_bytes = 3178, recv_bytes = 928
```

That is a full HTTP/3 request answered over QUIC with zero loss on this network, produced by
Cloudflare's own diagnostics module (`diagnostics::checks::h3_quic_connectivity`). It is independent
confirmation of what the raw Initial probe measured, from a source that is not this project.

IPv6 came back empty, consistent with there being no IPv6 on this host (`WinError 10051`,
NetworkUnreachable) - not with a block.

## Two usable addresses, both reachable over TCP

The trace section lists Cloudflare's policy addresses and all of them answered:

```
162.159.197.3   engage.cloudflareclient.com   trace 200,  rtt 983 ms
162.159.197.4   connectivity.cloudflareclient.com  trace 200, rtt 1103 ms
162.159.138.65  connectivity.cloudflareclient.com  trace 200,  rtt 990 ms
162.159.137.65  connectivity.cloudflareclient.com  trace 200
162.159.198.2   the MASQUE edge, from conf.json
```

The registration's `policy.always_include` names 162.159.197.4 as an address that must be reachable
for the tunnel to work, and it is. `warp=off` in every trace is expected and correct: no tunnel was
up, and that field is the measurement this whole project has been missing.

## Why this does not finish the task

H3 working and the tunnel edge answering a QUIC Initial were already established. Neither is the
last hop. The edge requires a client certificate, and that requirement is unchanged and still
measured:

```
no certificate   -> alert 116  certificate_required   (QUIC 372)
P-256 certificate -> alert  49  access_denied         (QUIC 305)
```

The certificate carries a `certificate_id` that Cloudflare issued, per the recovered schema
(`RenewApiCertificateResponse { mtls: MtlsCertificate { certificate } }`), and the official client
obtains it in the `EnsuringMtlsIdentity` connection stage from DPAPI-protected secrets
(`FailedToReadDERSecrets` -> `FailedToCreateSelfSignedCertificate` -> `FailedToBuildConnectRequest`).

`warp-diag` does not run that stage, because it does not run the service. It proved the transport and
left the credential untouched.

## What the diagnostic did confirm that the project had only assumed

- The H3 stack on this host negotiates HTTP/3 and gets a 200 over QUIC, from Cloudflare's code.
- 162.159.197.4, the address registration requires, answers.
- No IPv6, so v6 silence is not evidence of a block.
- `warp-diag` runs with the service stopped and leaves the network untouched - so it is a safe tool
  to use while WARP stays off.

**Still no `warp=on` measurement. The tunnel does not work.**
