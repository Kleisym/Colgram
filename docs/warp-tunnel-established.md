# WARP MASQUE tunnel: established

## The three changes that made it work

All three came from reading `Diniboy1123/usque` - an MIT-licensed Go reimplementation of the
Cloudflare WARP MASQUE client with no daemon, no DPAPI and no TPM - and all three are things this
project had wrong from the first session.

**1. The API version.** `internal/consts.go`:

```
ApiVersion = "v0a4471"          this project used v0a2158 throughout
```

`v0a2158` answers HTTP 400 now. `v0a4471` answers a complete registration. Every registration in this
project was talking to a retired generation and reading the 400 as a malformed request.

**2. The enrolment call that was never made.** `api/cloudflare.go`, `func EnrollKey`:

```
deviceUpdate := models.DeviceUpdate{
    Key:     base64.StdEncoding.EncodeToString(pubKey),   // x509.MarshalPKIXPublicKey
    KeyType: internal.KeyTypeMasque,                       // "secp256r1"
    TunType: internal.TunTypeMasque,                       // "masque"
}
req, _ := http.NewRequest("PATCH", ApiUrl+"/"+ApiVersion+"/reg/"+deviceId, body)
req.Header.Set("Authorization", "Bearer "+deviceToken)
```

PATCH `/v0a4471/reg/{device_id}` with a **marshalled PKIX public key** - not the 32-byte scalar that
`ColgramWarp.register()` sends. The server accepts it and echoes it back with `key_type: secp256r1`.

**3. The certificate shape.** `internal.GenerateCert`:

```
x509.CreateCertificate(rand.Reader,
    &x509.Certificate{SerialNumber: big.NewInt(0), NotBefore: now, NotAfter: now+24h},
    &x509.Certificate{},              // <- the template is EMPTY
    &privKey.PublicKey, privKey)
```

The certificate has an **empty subject and no extensions at all** - no CN, no SAN, no
BasicConstraints, no EKU, no keyUsage. Every certificate this project built had a subject and
extensions. That is a different object, and it is why every one of them returned `access_denied`.

## The proof

```
1) register on v0a4471                          HTTP 200, tunnel_protocol=masque
2) PATCH the PKIX public key                    HTTP 200, key echoed=True, key_type=secp256r1
3) bare certificate: subject='' extensions=0
4) QUIC handshake to 162.159.198.2              0.187 s, ALPN h3
5) CONNECT "/" :protocol cf-connect-ip           :status = 200
   headers: cf-connect-proto: cf-connect-ip, pq-enabled: false, user-agent: ""
6) HTTP Datagram capsule queued                  0 -> 1
7) QUIC datagrams emitted                       1200, 1200, 1200, 543, 126, 47, 45, 41, 45, ...
8) session held                                  10.02 s, no termination
```

The 45-byte datagrams are the capsules on the wire. For comparison, every shape tried before this
terminated at 1.03-1.22 s with alert 116 or 49, and none of them ever got a response.

## What the earlier `access_denied` meant

The reference client's own error string answers it:

```
if strings.Contains(err.Error(), "tls: access denied") {
    return errors.New("login failed! Please double-check if your tls key and cert is enrolled in ...")
}
```

`access_denied` is "your key and certificate are not enrolled". The enrolment is the PATCH call, and
it had never been made - not a network fact, not a certificate shape, not an authority constraint,
and not the mysterious 781 ms deadline. Twenty sessions of investigation were spent on a step that
is one HTTP call away from the registration.

## The path, complete

```
1. POST   /v0a4471/reg                        32-byte P-256 scalar in `key`
2. PATCH  /v0a4471/reg/{device_id}            PKIX public key, key_type=secp256r1, tun_type=masque
                                           Authorization: Bearer {device token}
3. build  a certificate with an empty subject and no extensions, from the same P-256 key
4. QUIC to 162.159.198.2:443, ALPN h3, SNI consumer-masque.cloudflareclient.com
5. CONNECT / with :protocol cf-connect-ip, cf-connect-proto, pq-enabled: false
6. carry UDP in RFC 9298 DATAGRAM capsules: 00 <ctx 0> <len> <payload>
```

## What is not yet shown

The session opens and capsules leave. A response capsule has not come back, so end-to-end traffic
through the tunnel has not been demonstrated end to end on this network, and no `warp=on` trace exists
from a device through this tunnel. The likely remaining detail is the target encoding - a MASQUE
datagram carries a destination, and the reference client goes through the `connectip` package, which
may frame the destination itself rather than expecting a bare IP payload.

**The tunnel is established. `warp=on` through it is not yet measured.**
