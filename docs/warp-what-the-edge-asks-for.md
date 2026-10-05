# The edge's CertificateRequest, read verbatim

## What the edge requires, in its own words

`aioquic` records the peer's CertificateRequest in `tls._certificate_request` after
`_client_handle_certificate_request`. Read out of the live connection to 162.159.198.2:

```
signature_algorithms (7):
    0x0403  ecdsa_secp256r1_sha256
    0x0503  ecdsa_secp384r1_sha384
    0x0603  ecdsa_secp521r1_sha512
    0x0804  rsa_pss_rsae_sha256
    0x0805  rsa_pss_rsae_sha384
    0x0401  rsa_pkcs1_sha256
    0x0501  rsa_pkcs1_sha384
other_extensions: []
request_context: empty
```

The identical list arrives whether or not a certificate is presented. The edge asks for a client
certificate signed with one of seven algorithms, and carries no certificate-authorities constraint and
no extensions.

## This explains a result that looked like noise

Ed25519 gave alert 116, identical to sending nothing, and was written off as "the certificate was
never sent". The list says why: `ed25519` is 0x0807 and it is **not in the list**. A certificate
signed with an algorithm outside signature_algorithms cannot satisfy the request, so it is the same
outcome as offering none. That reading was right, and now it has a cause rather than a guess.

P-256 is 0x0403 and **is** in the list, which is why presenting a P-256 certificate changes the answer
from 116 to 49: the edge accepted the algorithm, received the certificate, and then refused it on
grounds other than the signature.

## What this narrows the problem to

The requirement is now fully known and small:

```
the edge wants a client certificate
signed with one of: ecdsa_secp256r1_sha256, ecdsa_secp384r1_sha384, ecdsa_secp521r1_sha512,
                     rsa_pss_rsae_sha256, rsa_pss_rsae_sha384,
                     rsa_pkcs1_sha256, rsa_pkcs1_sha384
with no certificate-authorities constraint and no other extensions
```

And this project has presented a certificate under the most permissive member of that list, from a
key the server had just stored, with and without SAN, with CA:true, with clientAuth EKU, with key
usage, and as a plain self-signed leaf. All of them return 49.

Since the edge does not constrain the issuer - `other_extensions` is empty and there is no
certificate_authorities field in the request at all - the refusal cannot be a chain-trust failure. A
self-signed leaf satisfies a request that names no authority. So what remains is not the certificate's
shape, its issuer, its key, or its signature: it is that the edge wants to know which device is talking,
and the answer has to be something the daemon holds.

That is consistent with everything else in this project. `MtlsCertificate.certificate` is the response
field, `certificate_id` is what the client reads back out of what it was given, the service logs
`FailedToCreateSelfSignedCertificate` and `FailedToBuildConnectRequest`, and the material lives under
DPAPI with a SYSTEM-only ACL or a TPM.

## Also settled, and worth recording

`aioquic` handles the request correctly - `_client_handle_certificate_request` records it and moves
to `CLIENT_EXPECT_CERTIFICATE` without raising, so a client that has no certificate continues the
handshake rather than failing it. Alert 116 is therefore a deliberate refusal from the edge after a
clean handshake, not a limitation of this client, and not a timer.

**No `warp=on` measurement exists. The tunnel does not work.**

## The certificate demonstrably reaches the edge

The remaining doubt was whether aioquic sends the client certificate at all. Comparing the size of
the client's final handshake datagram with and without one:

```
no certificate       client datagrams = [1200, 1200, 1200, 191]   3791 B   alert 116
self-signed P-256    client datagrams = [1200, 1200, 1200, 659]   4259 B   alert 49
registered P-256     client datagrams = [1200, 1200, 1200, 607]   4207 B   alert 49
```

468 bytes of certificate and CertificateVerify go on the wire, in the flight immediately after the
CertificateRequest. The edge receives a certificate, verifies the signature against the algorithm it
asked for, and answers `access_denied`.

So the whole sequence is now observed end to end:

```
client -> ClientHello (h3, no client cert)
edge   -> ServerHello, EncryptedExtensions, CertificateRequest, Certificate, CertificateVerify, Finished
client -> (no Certificate: the client has none)
edge   -> alert 116 certificate_required

client -> ClientHello, then Certificate + CertificateVerify after the request
edge   -> alert 49 access_denied
```

The refusal is not a transport problem, not a timeout, not a filter, not a missing message, and not
an algorithm the certificate failed to satisfy. The edge asks for a client certificate, this client
sends one signed with an algorithm the edge named, and the edge declines it.

## What that leaves, exactly

The edge names seven signature algorithms and no certificate authority. A self-signed P-256 leaf is
therefore a structurally valid answer, and it is the one that has been sent. It is still refused, which
means the decision is made on something other than the certificate's structure - and the only thing
left in the protocol that the daemon holds and this project does not is the identity material itself.

There is no network-side route to that. Twenty-odd measurements in this project have closed every
other avenue, including several that turned out to be my own errors rather than the network's.

**No `warp=on` measurement exists. The tunnel does not work.**

## Every field of the certificate, exhausted

The CertificateRequest names seven signature algorithms, no certificate authority, no extensions and
an empty request context. Every dimension of the certificate that a client can control has now been
varied, and the answer is 49 in all of them.

```
identity fields (fresh registration each run)
    CN=cloudflare-client, no SAN                     alert 49
    CN empty, no SAN                                 alert 49
    SAN=registration token (URI)                     alert 49
    SAN=registration id (URI)                        alert 49
    SAN=account id (URI)                             alert 49
    SAN=edge IP + clientAuth EKU                    alert 49
    CN=client_id, OU=Cloudflare, O=WARP              alert 49

issuer
    issuer=cloudflare-client                         alert 49
    issuer=DigiCert Global Root G2                   alert 49
    issuer=ISRG Root X1                              alert 49
    issuer=GlobalSign Root CA                        alert 49
    issuer=Amazon RSA 2048 M01                       alert 49
    issuer=Baltimore CyberTrust Root                 alert 49

earlier, same result
    CA:TRUE, clientAuth EKU, no basicConstraints, keyUsage digitalSignature,
    CN=registration_id, CN=account_id, CN=license, certificate built from the key the
    server had just stored, Ed25519 (0x0807, absent from the request - alert 116)
```

The issuer result is the one that closes ordinary authentication as an explanation. If the edge were
verifying a chain, a leaf naming DigiCert or ISRG would fail differently from a self-issued one. All
six are identical, and the request named no authority to begin with, so chain validation is not the
gate.

## The final position

What is known, in one place:

```
network   the WARP MASQUE edge is reachable from the host and the device, speaks QUIC with
          ALPN h3, and its transport parameters are the most generous of three Cloudflare
          edges tested on the same path with the same code

protocol  the handshake completes, the edge sends a CertificateRequest naming seven signature
          algorithms with no authority constraint, and refuses:
              no certificate offered   -> alert 116 certificate_required
              any certificate offered  -> alert  49 access_denied

client    the certificate is verifiably on the wire - the final handshake datagram grows by
          468 bytes when one is presented - so the edge reads it and declines it

gap       every field a client controls has been varied and the answer never changes. The
          certificate has to carry something the daemon holds: an identifier Cloudflare
          issued, produced at registration and sealed under DPAPI or a TPM.
```

There is no network-side route past that, and there is no client-side certificate that this machine
can produce which the edge will take. Both remaining routes need the official daemon running, which
is the one thing that has stayed off limits throughout.

**No `warp=on` measurement exists. The tunnel does not work.**

## The one result that differed, and why

Sweeping the key types the request names turned up a single row that did not return 49:

```
p256          alert 49
p384          alert 49
p521          alert 116      <- different
rsa-pkcs1     alert 49
rsa-pss       alert 49
```

P-521 gives 116, which is the alert for having sent no certificate. Reading the client explains it
exactly. When a CertificateRequest arrives, aioquic negotiates a signature algorithm:

```
if self._certificate_request is not None:
    if certificate is not None and certificate_private_key is not None:
        signature_algorithm = negotiate(
            self._signature_algorithms_for_private_key(),
            self._certificate_request.signature_algorithms)
    else:
        signature_algorithm = None

    push_certificate(Certificate(
        request_context = ...,
        certificates = [(x.public_bytes(DER), b"") for x in [self.certificate] + chain]
                      if signature_algorithm else []))
```

and `_signature_algorithms_for_private_key` returns a list per key type:

```
RSA          -> PSS_RSAE_SHA256, PKCS1_SHA256, PSS_RSAE_SHA384, PKCS1_SHA384, PKCS1_SHA1
SECP256R1    -> ECDSA_SECP256R1_SHA256
SECP384R1    -> ECDSA_SECP384R1_SHA384
Ed25519      -> ED25519
Ed448        -> ED448
```

There is no SECP521R1 branch, so a P-521 key yields an empty list, `negotiate` returns None, and
aioquic sends a Certificate message with an empty certificate list. The edge sees no certificate and
answers 116 - the same alert as sending none. The edge does accept 0x0603; this client simply cannot
use it.

That is the last shape variation available, and it changes nothing about the conclusion: every key
type this client can actually present, under every identity and issuer, is refused with 49.

It is also the clearest illustration of how much of this investigation was fought against the
instrument rather than the network. P-521 looked like a lead - a different answer - and it turned out
to be a gap in aioquic, visible only by reading the client's own code after the measurement.

**No `warp=on` measurement exists. The tunnel does not work.**

## The TCP listener is not a MASQUE path at all

The official client's own log raced `primary="masque" secondary="H2"` and the H2 attempt failed every
time with `TLS handshake failed unexpected EOF` and `AllChecksFailed`. That has been read here as a
certificate problem, and it is worth settling because it decides whether an entire transport is even
available.

Offering every ALPN in turn against 162.159.198.2:443 over TCP, with a client certificate presented:

```
offers=None                               -> selected=None  TLSv1.3
offers=['h2']                             -> selected=None  TLSv1.3
offers=['h3']                             -> selected=None  TLSv1.3
offers=['h3-29','h3-32','h3-34']          -> selected=None  TLSv1.3
offers=['h2','h3']                        -> selected=None  TLSv1.3
offers=['http/1.1']                       -> selected=None  TLSv1.3
offers=['h2','http/1.1']                  -> selected=None  TLSv1.3
offers=['h3','h2']                        -> selected=None  TLSv1.3
```

The server never selects an application protocol. Not h2, not h3, not http/1.1. So the earlier rows
that read `OK, 0 bytes` were not a handshake that got further - they were a TLS 1.3 session with no
negotiated protocol that was then closed, which is what a listener with nothing to offer looks like.

That closes the `h2-only` route outright. `warp-cli tunnel masque-options set h2-only` points at a
listener that does not exist on this network; the TCP socket there completes TLS and then serves
nothing. The `"unexpected EOF"` in the client's log was this, not a refusal.

## So the transports available are

```
QUIC / HTTP3   yes - handshake completes, ALPN h3 negotiated, edge sends SETTINGS,
               then alert 116 without a certificate and 49 with any of them
TCP / HTTP2    no  - the listener negotiates no application protocol at all
WireGuard      no  - twelve destinations, silent, including a valid initiation
```

One transport, one requirement, one artefact that cannot be produced without the daemon.

**No `warp=on` measurement exists. The tunnel does not work.**
