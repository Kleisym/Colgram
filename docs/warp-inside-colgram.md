# WARP inside Colgram: MASQUE, built into the app and measured on the device

## What changed

Colgram carried WARP over WireGuard, through the embedded wireguard-go backend. That path cannot
reach the edge on this network, and it was never going to:

    162.159.192.1:443                                  no initiation ever sent
    162.159.198.2 on 443, 500, 4500, 4443, 8443, 8095 no answer to a real initiation

The same 162.159.198.2 answers a QUIC Initial with a Retry in about 100 ms, so the edge is there
and only the WireGuard handshake is blocked. WARP is now spoken over MASQUE - the same edge over
HTTP/3 - by a Go client built into the APK as libcolgrammasque.so.

    tools/warpgo/native/main.go                    the client
    colgram-core/.../ColgramMasqueNative.java      JNI face
    colgram-core/.../ColgramWarpMasqueTunnel.java  policy: bind address, edge, verdict
    colgram-core/src/main/jniLibs/<abi>/           the built library, three ABIs

## Measured, from the device, through the library

The library was loaded on the device and driven over its C entry points. Three consecutive runs,
each a fresh registration, a fresh tunnel and a fresh relay session:

    ip=104.28.244.74
    colo=FRA
    loc=RU
    tls=TLSv1.3
    kex=X25519MLKEM768
    warp=on

3 of 3. warp=on is the line Cloudflare itself returned for a request that left the device through
the tunnel. The library reports its own version, so a stale .so in a build is visible rather than
guessed at:

    loaded, version=colgram-masque/1

## The two-Go-runtimes question, answered by measurement

ColgramWarpServiceBridge carries a note that two Go runtimes in one process are fatal, that
libwg-go.so followed by libbox.so killed the device with signal 11 in about a second, and that WARP
was moved to sing-box so the second runtime would be "gone".

That belief was tested rather than inherited. libbox.so is a Go c-shared library - _cgo_topofstack
and crosscall2 are both present in it - so it is exactly the case the note describes. On the device:

    dlopen libbox...        -> loaded
    dlopen libcolgrammasque -> loaded
    call version            -> colgram-masque/1
    full MASQUE session     -> warp=on
    no crash, no signal 11

So the collision was not two Go runtimes. It was libwg-go.so specifically, and the way to avoid it
was to stop loading that library rather than to remove WARP from sing-box. ColgramWarpTunnel now
tries MASQUE first and only falls back to the WireGuard path when the native client is genuinely
unavailable - kept, not deleted, because it was written against a real peer and a device on a
network where WireGuard does answer would otherwise lose WARP entirely.

## One defect found while integrating

The native client read its configuration the way the standalone binary does, from the environment.
That is wrong for a library, and it showed up immediately: an edge override of host:port had its
port overwritten by the default 443, so a relay on 14501 was dialled on 443 and the tunnel timed
out with no packets on the wire at all - a failure that looks exactly like filtering. Fixed by
taking the port from the host:port pair and by resolving a literal address directly instead of
through the system resolver, which does not run on the device.

## What is not done yet

This is a working transport with a measured verdict, not a device-wide VPN. It proves the tunnel
carries traffic and that Cloudflare attributes the session to WARP; turning that into a TUN device
that routes the whole phone needs the VpnService consent flow, and no such device is installed here.
The external relay is still in the path on this network, because the edge answers one egress and
drops another - that is a routing fact about the network, not something the client can decide.

So the goal stays open: WARP has to work inside the app on a real phone, without the lab relay, and
the remaining bugs from the original list - dark theme, the toggle, call proxy, global search, plugin
import, the Telegram rename and the crashes - are untouched.

## The client now finds its own route

The external relay was removed from the measurement path. The client enumerates the routes it has and
tries them itself:

    edge candidate  1/6  162.159.198.2:443  via 10.0.2.15
    edge candidate  2/6  162.159.198.2:500  via 10.0.2.15
    ... six ports ...
    edge candidate  relay via 10.0.2.15
      relay         : 127.0.0.1:36887 -> 162.159.198.2:443 via 10.0.2.15

The relay is now in-process rather than a program on the host: two UDP sockets, one on loopback for
the client and one bound to the egress address for the edge, forwarding datagrams both ways. It sets
no route, no firewall rule and no DNS entry, and closing it removes the path. One upstream socket
per session, because a fresh socket per packet changes the source port and the edge treats each
source port as an unrelated flow - that was measured on the host relay, not assumed.

On this emulator every route is dead, and the client says so rather than pretending otherwise:

    version: colgram-masque/1
    FAILED: no edge route answered: quic dial: timeout: no recent network activity

That is the honest result for an egress the edge does not answer. The same code on the same network
returns warp=on when the egress is one the edge does answer, so what remains is which egress a given
device has - not a client defect, and not something more client work can decide.
