# The proxy SNI is a distinct path, and it is closed the same way

## What the binary distinguishes

The mask settings carry a struct with two different names in it:

```
MasqueProtocolSettings { masque_http_version, PkixConfig { ca_bundle, sni }, tunnel, proxy }
PkixSni { tunnel, proxy }
```

and the client logs the two as `tunnel_sni=` and `proxy_sni=`. That is a real split: the tunnel path and
the proxy path use different names, and in the binary the proxy path is its own code path,
`run_proxy_connection`.

Every probe in this project used the tunnel name. The proxy name had not been tried, and it was worth
trying because a proxy endpoint serves a different client and could plausibly carry weaker
requirements.

The complete set of names in the binary:

```
consumer-masque.cloudflareclient.com          the tunnel, the one the client used
consumer-masque-proxy.cloudflareclient.com    the proxy
zt-masque-proxy.cloudflareclient.com          Zero Trust proxy
zt-masque.cloudflareclient.com                Zero Trust tunnel
```

None of them resolve in public DNS, which is expected for names used with a pinned IP. The question
was never whether they resolve - it is whether the edge treats them differently.

## It does not

Four names against the same edge, source `192.168.0.4`, 2.5 s budget:

```
consumer-masque.cloudflareclient.com         0x174 at 1.062s   <- tunnel, the one that worked
consumer-masque-proxy.cloudflareclient.com   0x174 at 1.203s   <- proxy
zt-masque-proxy.cloudflareclient.com         0x174 at 1.219s   <- zero trust proxy
zt-masque.cloudflareclient.com               0x174 at 1.203s   <- zero trust tunnel
```

Identical, and inside the same 1.03-1.22 s band that every other LAN run occupies. The edge resolves
all four names, completes TLS for all four, and then applies the same 781 ms deadline to all four.
There is no proxy path that is more permissive, because there is no separate requirement set behind it.

## What this adds to the closed list

The identity requirement is not specific to the tunnel SNI, the transport, or the request shape. It is
the edge asking for the same thing under every name it knows, which is consistent with the conclusion
reached from the timing work: a deadline, not a judgement on what was sent.

| Layer | Result |
|---|---|
| Network, host and device | QUIC Retry to 162.159.198.2, 6 ports, 105-189 ms on device |
| Official confirmation | `warp-diag` gets HTTP/3 200 over QUIC, tunnel off |
| Protocol | `masque`, from the registration itself |
| Both official transports | H3 terminates at 1.03-1.22 s; H2 refuses before ALPN |
| All four SNI names | 0x174, same band |
| ALPN, SNI, post-quantum, transport parameters | closed by measurement |
| Request shape, timing, registration fields | closed by measurement |
| Public API paths, all storage locations | audited, nothing obtainable |

**No `warp=on` measurement exists. The tunnel does not work.**
