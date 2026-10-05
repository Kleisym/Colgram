# The tunnel carries traffic

## What was sent and what came back

```
session address      172.16.0.2   (assigned by the registration)
CONNECT /            :status = 200

sent  1.1.1.1         61 bytes capsule  (context 0 + IPv4/UDP header + 21-byte DNS query)
sent  8.8.8.8         61 bytes
sent  162.159.197.3   61 bytes

received  from 1.1.1.1        93 bytes   proto 17   dst 172.16.0.2
received  from 8.8.8.8        93 bytes   proto 17   dst 172.16.0.2
```

The reply size is right: one capsule context byte, a 20-byte IPv4 header, an 8-byte UDP header, and the
DNS response - 93 bytes in total against a 61-byte query. Both resolvers were measured answering on
this network before the tunnel was involved, and both answered to the tunnel address, so the traffic
left through the tunnel and came back through it.

ICMP was the first confirmation, and it is unambiguous because the reply carries our own fields:

```
sent     ICMP echo request  id=0x1234 seq=1  to 1.1.1.1, payload "colgram-warp-icmp-probe"
received ICMP echo reply    id=0x1234 seq=1  from 1.1.1.1, TTL 64, payload "col"
```

An echo reply with a matching identifier and sequence cannot be produced without the datagram having
left the client, crossed the tunnel, reached 1.1.1.1 and come back.

## The path, complete and short

```
1. POST   /v0a4471/reg
            key       = 32-byte P-256 scalar
            -> 200, tunnel_protocol=masque, interface 172.16.0.2, device token

2. PATCH  /v0a4471/reg/{device_id}
            key       = base64(PKIX public key)   <- not the scalar
            key_type  = secp256r1
            tun_type  = masque
            Authorization: Bearer {device token}
            -> 200, key echoed back

3. certificate:  empty subject, zero extensions, 24h validity, the same P-256 key

4. QUIC to 162.159.198.2:443, ALPN h3, SNI consumer-masque.cloudflareclient.com

5. CONNECT / with :protocol cf-connect-ip
                cf-connect-proto: cf-connect-ip
                capsule-protocol: ?1
                pq-enabled: false
                -> :status = 200

6. each UDP or ICMP packet becomes a capsule:
                0x00 (DATAGRAM) | varint context 0 | full IP packet
```

## What each of the earlier wrong turns was

| Turn | What it was | The actual cause |
|---|---|---|
| every certificate refused with access_denied | read as a chain or key policy | the API version was retired and the enrolment call was never made |
| the 781 ms deadline | read as the edge refusing | the client gave up; the edge had answered nothing yet because nothing valid was sent |
| twelve request shapes | read as the edge ignoring them | the requests were buffered in the wrong turn and never left |
| capsule with a bare DNS query | read as a rejected destination | a Connect-IP datagram is a whole IP packet, headers included |
| the `ValueError` on every run | read as a protocol fault | a stream writer's `__del__` during process teardown, after the measurement |

## What is still missing

No `warp=on` trace. The measurement above runs on the host with a socket this project owns; the
device leg - Colgram's own tunnel through this path on the phone - has not been run, and that is the
remaining step before this can be called done.

The engine is the second open item: `libbox.so` in the app has no masque outbound, and the
implementation that does exist first appears in sing-box `v1.15.0-alpha.9`, absent from every released
version. So carrying this in Colgram means either the alpha line or a separate native component.

**The tunnel is up and carries traffic. The device leg and the engine are not done.**
