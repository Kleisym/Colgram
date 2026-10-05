# The MASQUE path, and why it needs a host too

## What the registration says

```
policy: {
  "tunnel_protocol": "masque",
  "post_quantum": "enabled_with_downgrades",
  "always_include": [{"ip": "162.159.197.4"}, {"ip": "2606:4700:102::4"}],
  "always_exclude":  [{"ip": "162.159.197.3"}, {"ip": "2606:4700:102::3"}]
}
```

This is worth taking seriously rather than dismissing, because it is Cloudflare naming its own
preferred transport for this tunnel, and the always-include address is a different one from the
WireGuard endpoint - `162.159.197.4` rather than anything under `162.159.192.0/24`. Everything measured
so far was aimed at the second range.

## What was measured about it

MASQUE runs over HTTP/3, and HTTP/3 runs over QUIC, and QUIC is UDP. Two facts close it here:

```
cloudflare.com              -> TLS 1.3  ALPN h2
engage.cloudflareclient.com -> TLS 1.3  ALPN h2
one.one.one.one            -> TLS 1.3  ALPN h2

1.1.1.1:443        replies 0 of 2
104.16.132.229:443 replies 0 of 2
```

The server will not negotiate h3, and UDP 443 carries nothing. There is no HTTP/3 to run MASQUE over.

The always-include address is worth one honest caveat: it has not been dialled, because a probe for it
would be exactly the kind of packet a MASQUE server ignores, and silence would again be ambiguous. A
first CONNECT packet to `162.159.197.4:443` over TCP is the packet that would settle it, and that is not
something to write by hand from a guess at the protocol framing.

## What would settle it

Two things, in order of cost:

1. A WARP client that speaks MASQUE - warp-go, or the official client behind a relay - pointed at
   `162.159.197.4:443` over TCP. If the server accepts CONNECT there, MASQUE-over-TCP-443 is available and
   the whole problem reduces to the open TCP 443 this network does have.
2. Failing that, the same relay as before, carrying WireGuard rather than MASQUE.

Both need a host that can see Cloudflare's UDP. Neither can be settled from here, because the question
is what a Cloudflare ingress answers, and every ingress reachable from this machine answers nothing.

## What this changes

It changes the priority, not the conclusion. Before this, the only route worth pursuing was WireGuard
over a relay. Now there is a second candidate that Cloudflare itself nominates, and it is the better one
where it works, because it needs no WireGuard peer key of our own and rides the one port this network
leaves open. It still needs a host to be tested from, so the blocker is unchanged even though the thing
to test once it is available has.
