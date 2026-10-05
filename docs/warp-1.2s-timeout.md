# 0x174 is a 1.2-second timeout, and the two identities are not the same key

## The timing

The termination is not immediate and it is not random. Measured with a millisecond clock on four
configurations:

```
mode=silent    wait=2.0s  -> 0x174 at 1.20s   2297 bytes received   0 requests sent
mode=silent    wait=6.0s  -> 0x174 at 1.20s   2296 bytes received   0 requests sent
mode=keepalive wait=4.0s  -> 0x174 at 1.22s   2297 bytes received   1 request sent
mode=keepalive wait=8.0s  -> 0x174 at 1.20s   2297 bytes received   1 request sent
```

Always 1.2 s, whether nothing is sent, something is sent immediately, or a keepalive is sent on a
1-second cadence. The edge is not waiting for a request, and it is not a keepalive deadline. It
starts a timer when the handshake completes, and something it needs has not arrived inside 1.2 s.

That number matches the official client's own successful run:

```
13:02:07.078  TLS handshake completed  TlsInfo { version: TLSv1.3, curve: P-256,
                                            cipher: TLS_AES_256_GCM_SHA384,
                                            post_quantum_enabled: false, sni: None }
13:02:07.091  Established QUIC connection with 192.168.0.4:61608 ---> 162.159.198.2:443
13:02:07.092  h3_tun: Establishing MASQUE tunnel on existing QUIC connection
13:02:07.093  Starting MASQUE proxy task  idle_duration: 5s, keepalive_interval=1s
13:02:08.334  Connected to 162.159.198.2:443 @ 696f29 : FRA
              Connection stage 'Establishing connection' took 1243ms
```

1243 ms there, 1.2 s here. The official client also spent about 1.2 s between the QUIC connection
being established and the tunnel being declared up - it succeeded during that window instead of
being cut off at the end of it.

## Post-quantum is settled, not open

The log says `post_quantum_enabled: false` on the connection that worked, and the racing span says
`protocol="masque" pq=false` for that attempt. Above it, the PQ attempt had already failed and the
client logged `PQ racing failed, trying non-PQ`. The connection that succeeded was the classical one.
Post-quantum was carried as a live hypothesis because aioquic 1.3 cannot send a hybrid key share and
the failure shape looked like a rejected handshake - and that hypothesis is now closed by the
client's own record rather than left standing.

SNI is closed the same way, and directly: the working connection negotiated `sni: None`, so no SNI
extension is sent. Tested anyway, and it makes no difference:

```
server_name = None   -> 0x174
server_name = masque  -> 0x174
server_name = None   -> 0x174   (repeat)
```

## Two different keys, and the shape being sent is worth stating

The log carries both identities in plain text, and they are not the same key:

```
RegistrationInfo {
    id: Consumer("254087e2-fedc-43c2-b7f0-08ad35fd05b0"),
    public_key: [178, 5, 174, 154, 242, 102, 153, 214, 239, 88, 194, 152, 237, 223, 68, 108,
                 121, 255, 2, 210, 166, 77, 209, 142, 128, 174, 166, 192, 94, 70, 97, 21] }
```

That is 32 bytes, base64 `sgWumvJmmdbvWMKY7d9EbHn/AtKmTdGOgK6mwF5GYRU=`, with the high bit of the last
byte clear - an X25519 public key.

`conf.json` holds a different one, and a different kind:

```
tunnel_key_data: { key_type: secp256r1, tunnel_type: masque }
own_public_key:  91-byte SPKI, secp256r1
public_key PEM:  91-byte SPKI, point 6fff30b3...776beb
```

The X25519 point and the P-256 point do not match. The client holds two keys: a P-256 tunnel key
declared at registration, and a separate X25519 identity in `RegistrationInfo`. Every registration
this project performs sends `key` as a 32-byte scalar, which is the X25519 shape - and the server
echoes it back unchanged, so the identity being presented is right in shape.

## What the 1.2 s window is waiting for

The packet trail on this side is complete and small: 2297 bytes in five packets, ending with the
server's 59-byte H3 SETTINGS. The official client's log for the same edge, in the same 1.2 s, shows
`creating new flow for MASQUE request` from `tokio_quiche::http3::driver::client`, and
`Establishing MASQUE tunnel on existing QUIC connection`.

Requests were sent in this project and got no response, so the request alone is not the missing piece -
what is missing is whatever the request has to carry, and it has to be produced from the registered
identity. That is `EnsuringMtlsIdentity` in the client's stage enum, which runs after the handshake
and before the tunnel, with errors `FailedToReadDERSecrets` ->
`FailedToCreateSelfSignedCertificate` -> `FailedToBuildConnectRequest`. The material is DPAPI-held:
no file, no registry blob, an empty `tpm_keys` table, no copy in any task dump.

## Where this leaves it

Closed by measurement: the certificate hypothesis (0x174 is the same with and without a certificate,
and the same code on a different edge stays alive), the SNI hypothesis, the ALPN hypothesis, ten QUIC
transport parameters, and the post-quantum hypothesis. What remains is a 1.2-second wait for identity
material that the daemon holds.

**Still no `warp=on` measurement. The tunnel does not work.**
