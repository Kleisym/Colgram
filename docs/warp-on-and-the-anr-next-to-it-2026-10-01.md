# warp=on from the app, and the ANR that was hiding next to it — 2026-10-01, closing session

## The goal, met

Read on the phone, over the tunnel, on a network whose own UDP is filtered:

```
I ColgramMasqueVpn(12925): verdict through the tunnel: warp=on | HTTP/1.1 200 OK
fl=931f3 h=connectivity.cloudflareclient.com ip=104.28.244.74 colo=FRA loc=RU
    tls=TLSv1.3 sni=plaintext warp=on gateway=off kex=X25519MLKEM768
```

`ip` is the edge's view of the request, not the device's `10.0.2.15`. `kex=X25519MLKEM768` is the
P-256 registration's client certificate completing a post-quantum handshake. `sni=plaintext` because
this is Cloudflare's own endpoint and the inner request is what was sent.

```
tun0: TX 23, RX 9
up 3492 pkts / 375472 bytes   down 3523 pkts / 261036 bytes   sessions 1
```

## Defects 11 through 13, and then the ANR

### 11. The pump wrote TCP payload where an IP packet belongs

`colgram_masque_exchange` returned `inbuf`, the payload stream after `feed()` strips the headers. The
pump writes that into the tun descriptor, the kernel reads a TCP flag byte as an IP version, and
drops it. The interface stayed silent however much traffic arrived:

    in=2 dropped=0 bytes=88

`feed()` now queues whole packets, `takeIPPacket()` hands them out. rx went 0 to 9.

### 12. `openTrace` ran on every probe

It appended a peer and took a fresh port each time, so the request raced the flow about to replace
it. Opened once now, with the read buffer drained per request.

### 13. The trace request went out in plaintext

The endpoint is HTTPS. Plaintext to port 443 gets

    HTTP/1.1 400 Bad Request

a refusal, not a trace -- and reading its body produced a confident `warp=off` for a tunnel carrying
traffic perfectly. The measurement path has wrapped `tls.Client` over the tunnel since it was written.
That one is worth naming past WARP: a protocol error that returns a valid HTTP response reads as a
verdict, and without the body in the log a refusal is indistinguishable from an answer. The verdict
line prints the whole trace either way.

### 14. The VPN service never entered the foreground

Five ANR traces on the device, all of them the same subject:

    Context.startForegroundService() did not then call Service.startForeground():
    ServiceRecord{... org.colgram.messenger/org.colgram.core.ColgramMasqueVpnService}

Android allows five seconds and then raises it against the process, so bringing the tunnel up reliably
put the system's "app is not responding" dialog on screen -- indistinguishable, from the outside,
from any other freeze. On Android 14+ the same omission throws instead of warning, so on a newer
device this is a crash. It now calls `startForeground`, with the typed overload tried first and the
untyped one as the fallback, because the typed call throws when the manifest declares no matching
type and an exception there would tear down the interface the notification exists to keep alive.

Fair to say where these came from: every one of the five traces names `c:com.android.shell`, which
is my own adb invocation rather than the app's own start path. The omission was still real -- the
manifest declares a foreground service type and nothing ever entered the foreground -- but it was not
the app freezing on its own.

## State of the work

Eight checks, all green:

```
[ok] reflection in colgram-core
[ok] reflection in the UI files
[ok] auto-reply send path
[ok] packaged masque library
[ok] brand strings
[ok] manifest components exist in dex
[ok] native methods have JNI entry points
[ok] foreground services call startForeground
```

The last four are new and each fails on a defect in this series when it is reintroduced:
`check_manifest_components.py` (defect 1 and the BIND_VPN permission), `check_jni_symbols.py`
(defect 3), the architecture comparison inside `check_masque_build.py` (defect 4), and
`check_foreground_services.py` (defect 14).

## Not yet done

The remaining items from the original list are verified in code and in the earlier write-ups but have
not been re-checked by hand on this device in this session -- the login screen blocks them, since no
account is authenticated in `org.colgram.messenger` and the settings row that switches WARP lives
behind it. Dark theme, the global search work, `.plugin` import, the built-in-versus-user split and the
branding were all confirmed fixed in earlier passes (`dark-theme-verified-visually.md`,
`search-subscriber-count.md`, `search-tab-counters.md`, `search-return-from-chat.md`,
`search-input-history.md`, `rebrand-strings.md`) and none of them was touched since.

The emulator also stopped accepting adb partway through this session -- `adbd` in the guest stays
`offline` after a restart, which is its own fault and not the app's. The APK with the foreground fix
is built and the full suite passes on it.