# sing-box has a complete MASQUE client, and Colgram's build does not have it

## The engine in the app cannot speak masque

Searching the bundled `libbox.so` for outbound protocol types, counting how often each appears:

```
wireguard      26      vless        24      trojan        24      tuic          23
shadowsocks   32      vmess        27      hysteria2     25      naive         27
shadowtls     25      anytls       35
masque         0      warp          0
```

Every other outbound type appears two dozen to three dozen times. `masque` and `warp` appear zero. The
one masque-shaped string in the binary, `https://%s:%d/.well-known/masque/udp/%s/%d/`, is inside an
embedded Chromium HTTP/3 stack, not an outbound implementation.

This is not a network fact - it is an architecture fact, and it was invisible while the investigation
was about the network. `ColgramWarpServiceBridge` hands a profile to the sing-box service, and
`ColgramWarpProfileBuilder` builds a **wireguard** endpoint. MASQUE is the only WARP protocol
reachable from this network, and the engine that would carry it cannot.

## The implementation exists, and it is complete

In the sing-box repository:

```
option/masque.go              MASQUEClientEndpointOptions {
                                  System, Name, MTU, UDPMapping, UDPFiltering, UDPNATMax,
                                  Username, Password, Path, Headers,
                                  Version (enum 0,1,2,3), DisableVersionFallback,
                                  AdvertiseRoutes, UDPTimeout, OnDemand,
                                  HTTP2Options, HTTP3Options }

protocol/masque/client.go     ClientEndpoint - outbound, flow outbound, packet dialer
transport/masque/capsule.go   capsuleTypeAddressAssign      = 0x01
                              capsuleTypeAddressRequest     = 0x02
                              capsuleTypeRouteAdvertisement = 0x03
                              parseAddresses, parseVersion, parseRoutes
```

That is not a stub. `capsule.go` is 5 kB of real capsule parsing with strict validation - truncated
lengths, prefix host-bits, IP version dispatch, ordering of address ranges by version then protocol -
and `client.go` is 12 kB that builds a TUN device, tracks assigned addresses and advertised routes,
and serves as a `masque.ClientHandler`.

The option surface is exactly what a WARP client needs and exactly what this project has been trying to
speak by hand: `Path`, `Headers`, `Username`, `Password`, and an explicit `Version` with
`DisableVersionFallback`, plus separate HTTP/2 and HTTP/3 option sets. `ResolvedVersion()` defaults to
3, i.e. HTTP/3, and the fallback to HTTP/2 is a config flag.

## Which version has it

```
v1.15.0-alpha.9   option/masque.go PRESENT
v1.14.2           absent
v1.13.0           absent
v1.12.0           absent
v1.11.0           absent
```

The app bundles a 1.14.x libbox. Every released version lacks masque; it exists only on the alpha line
and the `testing` branch. So there is no drop-in upgrade either - upgrading to an alpha is the only
way to have the engine carry the protocol, and that is a build decision, not a configuration one.

## What this changes

Two independent walls now stand between here and a working tunnel, and they need different fixes:

```
network   the MASQUE edge at 162.159.198.2 is reachable - Retry in 105-189 ms from the device,
          and Cloudflare's own warp-diag gets HTTP/3 200 over QUIC on this network

protocol  the edge wants a client certificate bearing an id Cloudflare issued, produced by the
          desktop daemon at registration and sealed by DPAPI or a TPM

engine    Colgram's libbox has no masque outbound, and no released sing-box has one either
```

The engine wall is solvable without any network work: move to the alpha line, or carry a separate
MASQUE implementation. The protocol wall is not solvable here at all - it needs the daemon, or the
certificate it holds.

## What was measured, unchanged

| Layer | Result |
|---|---|
| Network, host and device | QUIC Retry to 162.159.198.2, 6 ports, 105-189 ms on device |
| Official confirmation | `warp-diag` gets HTTP/3 200 over QUIC, tunnel off |
| WireGuard edges | 12 destinations, all silent - MASQUE is the only route |
| Both official transports | H3 terminates at 1.03-1.22 s; H2 refuses before ALPN |
| All four SNI names, ALPN, post-quantum, ten transport parameters | closed by measurement |
| Twelve request shapes, three timings, seven registration shapes | closed by measurement |
| Registered-key certificate match | disproved: the key the server just stored is refused |
| Public API paths, all storage locations | audited, nothing obtainable |

**No `warp=on` measurement exists. The tunnel does not work.**
