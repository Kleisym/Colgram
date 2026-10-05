# Session state — 2026-10-02

## Where the goal stands

Two things are now proven, and they were separate problems.

**1. warp=on from inside the app, on the phone.** Read over the tunnel, on a network whose own
UDP to the edge is filtered:

```
I ColgramMasqueVpn(12925): verdict through the tunnel: warp=on | HTTP/1.1 200 OK
fl=931f3 h=connectivity.cloudflareclient.com ip=104.28.244.74 colo=FRA loc=RU
    tls=TLSv1.3 sni=plaintext warp=on gateway=off kex=X25519MLKEM768
tun0: TX 23, RX 9
```

**2. warp=on with no external relay at all.** The brief asked for the relay dependency to go, and
until now it had not: the verdict above came through a SOCKS5 front on the host at
`10.0.2.2:15150`, which proves the host's path and says nothing about the phone. With the relay
environment explicitly empty:

```
measureWith: env WARP_SOCKS="" WARP_RELAY=""
edge candidate 19/30  162.159.198.2:443 via 192.168.0.4
trace target    : connectivity.cloudflareclient.com 162.159.137.65
tls handshake   : complete, alpn=
ip packets      : sent=23 recv=16
CONNECT status  : 200 OK
  warp=on  ip=104.28.244.74  colo=FRA  loc=RU  tls=TLSv1.3  kex=X25519MLKEM768
VERDICT: warp=on
```

One line in the whole run mentions a relay, and it is the empty `WARP_RELAY=""`.

What made it work: direct QUIC is filtered from the host as well, so the client walks 19 edge
candidates across the bind addresses the machine actually has -- 26.226.94.158, 100.127.255.2,
172.31.208.1 -- before one answers on 192.168.0.4, and reports each failure with its real reason
(`wsasendto: A socket operation was attempted to an unreachable network`, the host's spelling of the
device's `context deadline exceeded`). Both mean the same thing and both mean try the next address.

## Nine checks, all green

```
[ok] reflection in colgram-core
[ok] reflection in the UI files
[ok] auto-reply send path
[ok] packaged masque library
[ok] brand strings
[ok] manifest components exist in dex                 defects 1, 2
[ok] native methods have JNI entry points             defect 3
[ok] foreground services call startForeground         defect 14
[ok] no external relay in the WARP path               the relay dependency
```

`check_no_external_relay.py` is the new one. It is structural: the in-process front must own both
halves -- a loopback ServerSocket and its own DatagramSocket -- and no file on the WARP path may name
a non-loopback address with a port. No runtime measurement can tell the two arrangements apart,
because both produce a tunnel with packets in both directions and a trace saying warp=on; only the
address of the front distinguishes them.

## Fourteen defects, each named by its measurement

1. `ColgramSettingsActivity` declared as an activity, wrong package, and not an Activity
2. `android.permission.BIND_VPN` -- a permission that does not exist
3. no JNI entry points at all
4. the libraries were Windows PE files that existed and were large
5. the pump reported nothing, so a load failure and a dead tunnel looked alike
6. `open_session()` ignored the SOCKS5 front `measureWith()` honoured
7. `last_error` read `bridgeErr` instead of `lastExchangeErr`
8. `quic.Dial` had no deadline
9. the SOCKS5 front was inside the tunnel
10. inbound capsules bypassed flow matching
11. the pump wrote TCP payload into the tun instead of an IP packet
12. `openTrace` ran per probe and raced its own request
13. the trace request went out in plaintext to an HTTPS port
14. the VPN service never called `startForeground`, and Android raised five ANRs

## What is owed

The emulator's adbd has been offline since the previous session. Every port answers -- 16384, 7555,
5555 -- and every connection sits at `offline`, across four restarts, a driver reinstall, and two adb
server restarts, while `mumu-cli info` reports `is_android_started: true`. So:

- the APK carrying the `startForeground` fix is built (2026-10-01 23:23) and the nine checks pass
  on it, but it was never installed or re-measured on the device
- the WARP toggle has still not been clicked. It lives in Colgram settings, which sit behind the login
  screen, and no account is authenticated in `org.colgram.messenger`. The tunnel was brought up with an
  explicit intent rather than through the switch, so the switch's own state handling after a click
  remains unexercised on device

Everything else from the original list is verified in source and was not touched this session: dark
theme (`colgramGuard` on both colour paths in `Theme.java`), call proxy on by default
(`proxy_enabled_calls` defaults true and is honoured at `VoIPService.java:3454`), subscriber counts,
search tab counters, return-from-chat with query and scroll position (`SearchViewPager.onResume`
line 877), search history with deletion, `.plugin` import, built-in versus user split, branding.

## Files

- `docs/warp-on-from-the-app-2026-10-01.md` -- the verdict, on the phone
- `docs/warp-on-no-relay-2026-10-02.md` -- the verdict, with no relay
- `docs/warp-on-and-the-anr-next-to-it-2026-10-01.md` -- defects 11 to 14
- `tools/warpgo/native/build-all.sh` -- the three-ABI build, NDK pinned
- `tools/warpgo/native/jni_bridge.c` -- the bridge the VM can call
- `tools/warpgo/native/probe.exe` -- the standalone verdict, `WARP_SOCKS="" WARP_RELAY=""`
