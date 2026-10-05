# The blocker is egress filtering, not the device and not the client

## The isolation

One binary, one edge, two binds. Nothing else changes: same registration, same certificate, same
extended CONNECT, same capsules.

    === A) wildcard bind ===
    error           : quic dial: timeout: no recent network activity

    === B) bind to the LAN address ===
      warp   = on

And the egress each bind produces, read back from Cloudflare's own trace:

    wildcard   ip=94.249.205.37   colo=ARN   loc=SE
    LAN        ip=158.46.64.145   colo=FRA   loc=RU

The MASQUE edge answers QUIC from one egress and silently drops it from the other. The path with
the RU egress works; the other does not.

## The same split on the device, and on any QUIC destination

QUIC itself is not blocked on the device. Same stack, same emulator, same socket:

    device -> 8.47.69.0:443 (cloudflare-quic.com)
      quic handshake  : completed
      http/3          : 200 OK
      trace ip=94.249.205.37  colo=ARN

    device -> 162.159.198.2:443 (MASQUE edge)
      quic handshake failed: timeout: no recent network activity

On the host, the same split appears for a non-WARP destination:

    host, LAN bind   -> 8.47.69.0    ip=158.46.64.145  colo=FRA
    host, wildcard   -> 8.47.69.0    ip=94.249.205.37  colo=ARN

The RU egress completes a handshake to both. The other completes one and is dropped by the other.

## Why every earlier device run failed

The device captures from earlier in this project show 1200-byte Initials leaving wlan0 with no
inbound packet at all - ten packets sent, zero received. That is the filter, not a MTU problem, not
a certificate problem and not a client problem.

The device's UDP is fine: DNS to 1.1.1.1 answers in 99 ms, ICMP to the edge answers in 1.8 ms, and
QUIC with HTTP/3 to a different Cloudflare address returns 200.

It also explains the device's own reference client reporting "timeout: no recent network activity"
while a 1200-byte probe drew a Retry in 100 ms. Both were measured from the egress the edge drops.

## What this means for the goal

The protocol question is closed. A complete, self-contained MASQUE client gets warp=on from this
machine, reproducibly, on the RU egress. There is no missing capability, no unsupported SNI, no
unsupported ALPN and no unavailable transport - all of those were closed by measurement earlier and
none of them was ever the blocker.

What remains is routing. The device's traffic leaves through 94.249.205.37, and that egress does not
reach the MASQUE edge. The host reaches it through 158.46.64.145. The fix is to make the device's
WARP traffic leave through the RU path - an explicit proxy for that one destination, or a VPN exit
on that route - and then the same client that reports warp=on on the host will report it on the
device.

Until that routing exists, no amount of client work will make the device's own egress reach the
edge, and claiming victory would be claiming something not measured.
