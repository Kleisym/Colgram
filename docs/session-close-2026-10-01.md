# Session close — 2026-10-01

## The goal, met

`warp=on`, read from inside the app, on the phone, over the tunnel, on a network whose own UDP
is filtered:

```
I ColgramMasqueVpn(12925): verdict through the tunnel: warp=on | HTTP/1.1 200 OK
fl=931f3 h=connectivity.cloudflareclient.com ip=104.28.244.74 colo=FRA loc=RU
    tls=TLSv1.3 sni=plaintext warp=on gateway=off kex=X25519MLKEM768
```

`ip` is the edge's view, not the device's `10.0.2.15`. `kex=X25519MLKEM768` is the P-256
registration's certificate completing a post-quantum handshake.

```
tun0: TX 23, RX 9
up 3492 pkts / 375472 bytes   down 3523 pkts / 261036 bytes   sessions 1
```

## Fourteen defects, each with the measurement that named it

1. `ColgramSettingsActivity` declared as an activity, in the wrong package, and not an Activity
2. `android.permission.BIND_VPN` -- a permission that does not exist
3. no JNI entry points at all; cgo exports the VM cannot see
4. the libraries were Windows PE files that existed and were large
5. the pump reported nothing, so a load failure and a dead tunnel looked the same
6. `open_session()` ignored the SOCKS5 front `measureWith()` honoured
7. `last_error` read `bridgeErr` instead of `lastExchangeErr`
8. `quic.Dial` had no deadline, so a filtered path hung and blocked the pump
9. the SOCKS5 front was inside the tunnel
10. inbound capsules bypassed flow matching
11. the pump wrote TCP payload into the tun instead of an IP packet
12. `openTrace` ran on every probe and raced its own request
13. the trace request went out in plaintext to an HTTPS port
14. the VPN service never called `startForeground`, and Android raised five ANRs about it

## Checks

Eight, all green. Four are new and each fails on a defect above when it is reintroduced:

```
[ok] reflection in colgram-core
[ok] reflection in the UI files
[ok] auto-reply send path
[ok] packaged masque library
[ok] brand strings
[ok] manifest components exist in dex            defects 1, 2
[ok] native methods have JNI entry points        defect 3
[ok] foreground services call startForeground    defect 14
```

`check_masque_build.py` gained an ELF architecture comparison, which is what catches defect 4: the
Windows PE had the right exports and the wrong `e_machine`, and a byte comparison alone passed it.

## The rest of the original list, verified in source

Not touched this session, each confirmed present rather than assumed:

- dark theme: one `colgramGuard` on both colour paths in `Theme.java` (lines 9008, 9111, 9122),
  verified on screen in `dark-theme-verified-visually.md`
- call proxy on by default: `proxy_enabled_calls` defaults true in `ProxyListActivity` (three reads)
  and is honoured where it matters, `VoIPService.java:3454`
- subscriber counts on channels: `ChannelRecommendationsCell`
- search tab counters: `ViewPagerFixed`
- return from a chat with the query and the scroll position: `SearchViewPager.onResume` (line 877)
  re-runs `search(..., lastSearchString, true)`; the position guard is `lastSearchScrolledToTop`
- search history with deletion: `ColgramSearchHistory` plus `saveGlobalSearchHistory` (line 421)
- `.plugin` import: `ColgramPluginManager`
- built-in versus user split: `ColgramPluginsActivity`, `ColgramSettingsActivity`, `LaunchActivity`
- branding: 1862 occurrences across 190 files

## Where it is not finished

The emulator's `adbd` stopped coming up after a restart partway through this session -- every
connection sits at `offline` and `mumu-cli adb` reports the same, while `is_android_started` is
true. That is the emulator, not the app. Consequence: the APK carrying the `startForeground` fix
is built and the full suite passes on it, but it was not installed and re-measured on the device.
That is the one verification still owed, and it needs an emulator that answers adb.

Also not done this session, for the same reason:

- the WARP toggle has not been clicked. It lives in Colgram settings, which sit behind the login
  screen, and no account is authenticated in `org.colgram.messenger`. Everything behind that screen
  is verified by source and by the earlier on-screen pass, not by a tap in this session.
- the tunnel was brought up with an explicit intent, not through the settings switch, so the switch's
  own state handling after a click is still unexercised on device.

## Files that matter

- `docs/warp-on-from-the-app-2026-10-01.md` -- the verdict and the path it travelled
- `docs/warp-on-and-the-anr-next-to-it-2026-10-01.md` -- defects 11 to 14
- `docs/warp-tunnel-carries-2026-10-01.md` -- the IP-packet defect
- `tools/warpgo/native/build-all.sh` -- the three-ABI build, NDK pinned
- `tools/warpgo/native/jni_bridge.c` -- the bridge the VM can actually call