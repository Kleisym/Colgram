# The WireGuard path is closed on this network, which is why MASQUE is the only route

## The lead worth chasing, and what it showed

`C:\ProgramData\Cloudflare\cfwarp_service_boring.txt.3` is not a hypothesis - it is a record of a
working WireGuard tunnel on this host:

```
2026-09-21T00:01:47.843Z  Received handshake_response local_idx=13360344 remote_idx=14159063
2026-09-21T00:01:47.843Z  New session session=13360344 index=52188
2026-09-21T00:03:48.143Z  Received handshake_response local_idx=13360345 remote_idx=14159064
```

`boringtun` is WireGuard, and a `handshake_response` is the far end accepting the handshake. Those
files cover 21-24 September, with sessions rotating every 120 s and keepalives in between. So
WireGuard worked here, four to nine days ago.

That matters because WireGuard needs none of what MASQUE needs: no client certificate, no
`EnsuringMtlsIdentity`, no DPAPI-held identity. If it still worked, the entire certificate
investigation would be irrelevant.

## It does not work now

A real 148-byte WireGuard initiation - the construction verified against upstream wireguard-go in
this project, addressed to Cloudflare's published peer static, with a correct mac1 - sent to every
Cloudflare UDP edge this project has ever identified:

```
162.159.192.3:2408    silent      162.159.198.2:2408    silent
162.159.192.4:2408    silent      162.159.198.2:500     silent
162.159.192.1:2408    silent      162.159.198.2:1701    silent
162.159.192.3:500     silent      162.159.198.2:4500    silent
162.159.192.3:1701    silent      162.159.198.2:4443    silent
162.159.192.3:4500    silent      162.159.198.2:8443    silent
```

Twelve destinations, all silent. Silence here is meaningful and not a null result: the packet carries
a valid mac1 keyed for the peer static, and a filter that drops malformed traffic would look the same
as an edge that ignores it. What closes the question is that the same construction produces
`handshake_response` against a local wireguard-go peer, which is how it was validated.

Note the asymmetry this creates, and it is the reason MASQUE had to be found at all: six of the seven
ports on 162.159.198.2 answer a QUIC Initial, and none of them answer a WireGuard initiation. The edge
speaks QUIC there and WireGuard somewhere else, and the somewhere else is not reachable.

## What the tunnel was in those logs

The service logs covering 21-24 September do not exist - the oldest is 25 September, and they contain
no `wireguard_tun` line at all, only `masque`. The 18 September snapshots are connectivity checks
only, and none of the 161 mentions a connected tunnel. So the endpoint that answered on 21-24
September is not recoverable from what is on disk.

The network itself has not changed identity in a way that explains it. The 18 September snapshot
records egress `85.118.165.181`; today it is `94.249.205.37`, both from the same local tunnel:

```
ip=94.249.205.37  colo=ARN  loc=SE  warp=off
```

## What this means

Two protocols, one network:

```
WireGuard   162.159.192.x / 162.159.198.2   silent on every port tested, 12 targets
MASQUE      162.159.198.2                   QUIC Initial answered, Retry in 105-189 ms
```

The blocking is per-destination, not per-protocol and not per-port-number. The WARP edge that carries
MASQUE is open; the edges that carry WireGuard are not. That is the whole reason the official client
was configured with `tunnel_protocol: masque` on this machine in the first place - and it is why the
certificate is unavoidable rather than a detour.

**No `warp=on` measurement exists. The tunnel does not work.**
