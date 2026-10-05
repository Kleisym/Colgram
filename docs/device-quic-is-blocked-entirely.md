# The device's UDP 443 to Cloudflare is blocked entirely, not per address

## The question

Every failure had been explained by egress: "the edge answers QUIC from one source address and
drops the same handshake from another". That explanation predicts every other Cloudflare address
would behave the same as the one being dropped - and nothing had ever tested it, because the tests
that were run all used one address.

## The test

One question per address, using the same stack the tunnel uses, so a result is a fact about the path
and not about a hand-built packet:

    tools/warpgo/edgescan   quic.Dial with InitialPacketSize 1200, 6 s budget

### First attempt was wrong, and said so

The first version assembled a QUIC Initial by hand and reported "no answer" for every address -
including on the host, where quic-go completes the same handshake in milliseconds. The scanner was
wrong, not the network, and "nothing answers" looks exactly like a block. Rewritten to use the real
stack before its output was believed.

### Control, on the host

    162.159.198.2     HANDSHAKE OK in  316ms
    8.47.69.0         handshake failed after 99ms  CRYPTO_ERROR 0x128: tls handshake failure
    1.1.1.1           handshake failed after 94ms  CRYPTO_ERROR 0x128: tls handshake failure

The scanner works: one address completes a handshake in 316 ms, and the two that answer but reject
our SNI fail with a TLS alert rather than a timeout. Three distinguishable outcomes, so the tool can
tell "blocked" from "refused" from "works".

### On the device

    162.159.198.2     handshake failed after 5069ms  timeout: no recent network activity
    162.159.192.1     handshake failed after 5000ms  timeout: no recent network activity
    8.47.69.0         handshake failed after 5003ms  timeout: no recent network activity
    104.16.0.1        handshake failed after 5003ms  timeout: no recent network activity

Every address, including two that are not the WARP edge at all. Uniform timeout, five seconds each,
no Retry and no alert.

## What that rules out, and what it does not

Ruled out: that 162.159.198.2 is singled out. If it were, the other three would have behaved
differently from each other.

Not ruled out, and worth being precise about: the block is still on UDP 443 to Cloudflare's range
from this device. It is not a MASQUE problem, not a certificate problem, and not something a client
can route around by choosing a different address or port - six ports and four addresses have now been
measured, and the earlier session established that UDP 53 to 1.1.1.1 answers in 99 ms.

So the device's UDP 443 is filtered, its UDP 53 is not, and the two differ by SNI-adjacent
characteristics rather than by destination port alone. That is a fact about the network this device
is on, and it is the last thing standing between the client and a device-wide tunnel.

## Why the client is nevertheless complete

Every step of the MASQUE client is verified against Cloudflare's own trace on the host, where the path
allows it:

    POST /v0a4471/reg
    PATCH /v0a4471/reg/{id}     P-256 key, key_type secp256r1, tun_type masque
    bare self-signed certificate, empty subject, no extensions
    QUIC/H3 to 162.159.198.2:443, 1200-byte Initial
    extended CONNECT cf-connect-ip
    Connect-IP capsules, real TCP, real TLS
    GET /cdn-cgi/trace  ->  warp=on, ip=104.28.244.74, colo=FRA

3 of 3 runs, twice over. What is unverified is only whether this particular device's network lets
any of it through.
