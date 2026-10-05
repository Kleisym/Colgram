# If the edge matched a registered key, this would have been it - and it is not

## The strongest remaining hypothesis, tested properly

The edge issues a connection ID and then waits 781 ms. The official client fills that window in 16 ms
with material it already holds. The one explanation that would make a third-party client work is that
the edge accepts a self-signed certificate whose public key it has already been told about - and the
only way a third-party client could satisfy that is to register a key and then present that exact key.

This has been tried before as `cert_key_match.py` and returned 305. It was worth doing again for a
specific reason that the first attempt got wrong: `key` is echoed back unchanged by the server, so
whatever is sent is stored as the device's identity, and the working client's `key` is a 32-byte
`secp256r1` scalar. The earlier version sent a P-256 scalar in `key` but did not also declare the
matching public point in `tunnel_key_data`, and did not carry a CSR, so the server never had a single
key it could be comparing against.

So one P-256 key, used three ways in one request, then presented immediately:

```
key             = 32-byte P-256 scalar, the shape the server echoes
public_key      = the matching SPKI
tunnel_key_data = { key_type: secp256r1, tunnel_type: masque, public_key: the raw point }
mtls_csr        = a real CSR from the same key
```

Result:

```
registration: HTTP 200  protocol=masque
  key echoed unchanged : True
  mtls in response     : False
  ca_bundle in response: False

  CN=cloudflare-client +SAN    -> alert 49 (access_denied) at 1.391s
  CN=reg_id +SAN               -> alert 49 (access_denied) at 1.078s
  CN=reg_id, no SAN            -> alert 49 (access_denied) at 1.047s
  CN=cloudflare-client, no SAN -> alert 49 (access_denied) at 1.094s
```

## What that rules out

This is the tightest version of the test available without the daemon. The key in the certificate is
the key the server stored a moment earlier, in the correct field, with the correct declared curve and
a CSR alongside it. If the edge were matching a registered key against a presented certificate, this
would match, and it does not.

So `access_denied` is not about the key, the registration, the subject, or the SAN. It is what the edge
sends when a certificate arrives that it has no record of - a `certificate_id` it never issued for.
That is consistent with the recovered field list, where `certificate_id` is something the client
*reads out of* a certificate it was given:

```
certificate_id  cn  certificate  check_private_key  extended_key_usage  locations
subject_alternative_names
```

and with `MtlsCertificate { certificate }` being a response the API never returns for a public
registration.

## The two stable behaviours, re-measured

Four runs, two conditions, from `192.168.0.4`:

```
nothing offered      alert 116 (certificate_required) at 1.063s
nothing offered      alert 116 (certificate_required) at 1.203s
P-256 self-signed    alert  49 (access_denied)         at 1.110s
P-256 self-signed    alert  49 (access_denied)         at 1.078s
```

Two distinct, repeatable outcomes, and the difference is only whether a certificate was presented.
Everything else - SNI, ALPN, transport parameters, request shape and timing, registration fields,
all four SNI names, both official transports - has been swept and is identical.

## Where this leaves it

The edge wants a certificate bearing an identifier Cloudflare issued. That identifier is produced at
registration by the daemon, sealed by DPAPI under a SYSTEM-only ACL or by a TPM, and is not returned
by any public endpoint, not written to any file, and not derivable from the registration response.

**No `warp=on` measurement exists. The tunnel does not work.**
