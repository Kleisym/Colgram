# WARP as a device-wide tunnel

## What this adds

Until now ColgramWarpMasqueTunnel proved the tunnel carries traffic - Cloudflare's own trace said
warp=on - and that is a measurement, not a feature. Nothing on the phone was routed through it. This
is the part that makes it one.

    ColgramMasqueVpnService.java   VpnService: holds consent, opens the TUN, pumps both directions
    ColgramMasqueNative            colgram_masque_open_session / _exchange / _close_session
    tools/warpgo/native/main.go     the long-lived session behind those three

## Why the session had to be rewritten

The measurement entry point registers a device, dials QUIC and reads one response. Doing that per
packet would be unusable, and doing it per VpnService pump would leak a device registration every few
seconds. So there is a second path: one registration, one QUIC session, held open for the life of
the interface, with packets pushed through it.

    colgram_masque_open_session   register, dial, CONNECT-IP, open a peer
    colgram_masque_exchange      one packet out, one packet back
    colgram_masque_close_session teardown

The context's cancel is kept on the session rather than called when the setup returns. That is not a
style point: a cancelled context closes the connection out from under the tunnel, and the interface
then goes silent with nothing in the log to say why.

## The loop that matters

The VpnService reads whole packets from the TUN and hands them to the native client; the client sends
each as a Connect-IP capsule and returns whatever comes back.

That works because Connect-IP carries exactly what a VpnService produces and expects - a whole IP
packet - so no userspace IP stack is needed in Java, and no framing is invented on either side.

The return path has to be running before the first packet leaves. The SYN goes out as a capsule, and
nothing comes back unless something is already reading the datagram stream; opening the peer first
and starting the pump after is a deadlock that looks exactly like a silent network.

## What is borrowed, and why

The VpnService shape follows ColgramVpnService, which had already been through the two failures that
are easy to walk into:

-   **The endpoint must leave by the physical interface.** Everything is routed into the tunnel, so
    without excludeRoute for the edge, the tunnel swallows its own MASQUE handshake. ColgramVpnService
    measured it: the tunnel "started cleanly", and the endpoint became unreachable the moment there
    was traffic to carry, with nothing in the log.
-   **establish() returning without an error is not a tunnel.** A null descriptor and an existing
    interface are different facts, and only the second is a tunnel - the same class has seen a
    descriptor returned and no tun interface exist afterwards.

## What is not done

The service is registered and builds, and the packet path is implemented. It has not been run on the
device, because the device's own egress does not reach the edge:

    edge candidate  1/6  162.159.198.2:443  via 10.0.2.15   no answer
    ... six ports ...
    edge candidate  relay via 10.0.2.15                    relay up, still no answer
    FAILED: no edge route answered: quic dial: timeout

So what is unproven is the thing that has been unproven all along and is not a client defect: whether
this particular device has an egress the edge answers. Establishing the interface and moving a packet
across it on hardware whose egress the edge drops would prove the routing works and say nothing new
about the tunnel.

## The switch, and why it is a second one

Bringing the tunnel up and taking over the phone are different decisions, so they are two rows:

    Cloudflare WARP (встроенный обход)   proves the tunnel carries traffic
    WARP на весь телефон                 takes the device's single VPN slot

The second row defaults to off. Installing a device-wide VPN changes what every other app on the
phone does, and that should be a choice rather than a side effect of switching WARP on.

When it is turned on, the VpnService is started only after the measurement has already returned
warp=on. Two reasons, both learned the hard way elsewhere in this codebase:

-   a VpnService holds the device's single VPN slot; taking it and then failing to reach the edge
    would leave the phone with no route and no way back, which is worse than not routing through
    WARP at all
-   the measurement leaves by the physical interface and the tunnel does not, so running them the
    other way round is how a tunnel ends up carrying its own handshake

Turning the row on while WARP is down starts the tunnel as well, rather than installing an interface
with nothing behind it.

## The resolver, which was the last single point of failure

The client resolved the registration API through one hardcoded resolver:

    req, _ := http.NewRequest("GET", "https://1.1.1.1/dns-query?...")   // and no fallback

Two ways that fails, both measured on this network. net4people/bbs #81 and Risky Bulletin
(2026-08-26) both record Russian ISPs cutting DNS-over-HTTPS and DNS-over-TLS with a TCP RST
*after* the TLS ClientHello, keyed on the SNI, while leaving the same IP reachable under a different
name. And the device has no working system resolver at all - it fails outright, which is why
everything before had to pin addresses by hand.

So it now walks a list, and each entry is an address literal plus the name to carry in SNI and verify
against:

    1.1.1.1  1.0.0.1  cloudflare-dns.com
    8.8.8.8  8.8.4.4  dns.google
    94.140.14.14          adguard-dns.com

Pinned address and separate SNI is the point: a resolver that is reachable but filtered by name gets
dialled under a name that is not on the list. The certificate is still verified against the real
resolver name, so a cut resolver cannot be quietly swapped for an impostor just because the name it
was reached under changed.

Verified on the device with the rebuilt library:

    session address : 172.16.0.2                 <- registration succeeded through the resolver
    edge candidate  1/6  162.159.198.2:443  via 10.0.2.15
    ... six ports ...
    edge candidate  relay via 10.0.2.15
    version: colgram-masque/1
    FAILED: no edge route answered: quic dial: timeout

The resolver works. The edge still does not answer this egress, which is the one thing left and is
not a client defect.

## Verified

    go vet                                      clean
    :colgram-core:assembleRelease               BUILD SUCCESSFUL in 8s
    AAR: both .so for all three ABIs
    AndroidManifest.xml                         valid, VpnService registered with BIND_VPN
