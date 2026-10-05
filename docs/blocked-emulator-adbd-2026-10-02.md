# Blocked: the emulator adbd will not come back up

Recorded 2026-10-02, after six attempts.

## What is tried, and what each attempt returned

| attempt | result |
|---|---|
| `adb devices` across 16384, 5555, 7555, 10901 | every port answers TCP, every entry reads `offline` |
| `mumu-cli info list` | `is_android_started: true`, `is_process_started: true`, `error_code: 0` |
| `mumu-cli adb --vmindex 0 --cmd 'shell echo ok'` | `device offline` |
| `adb kill-server` + `start-server` + reconnect | `offline` |
| `mumu-cli control shutdown` + `launch`, four times | guest boots, adbd stays `offline` |
| `mumu-cli driver uninstall` + `install` | both return `errcode: 0`, adbd still `offline` |
| `adb connect` on all three listening ports | `already connected`, then `offline` |

So the host side of adb is healthy and the guest side is not answering at all. `is_android_started`
is true, which means Android is up and something in it is holding the adb socket open without
completing the handshake.

## What this costs

One verification, and it is the last one owed:

- the APK carrying the `startForeground` fix (defect 14) is built and passes all nine checks, but
  was never installed or re-measured on the phone
- the WARP toggle has never been clicked, because it sits behind the login screen and no account is
  authenticated in `org.colgram.messenger`

## What is verified without the device

Everything else, and it is not a consolation prize:

- `warp=on` from inside the app on the phone, over the tunnel:

      verdict through the tunnel: warp=on | HTTP/1.1 200 OK
      ip=104.28.244.74 colo=FRA loc=RU tls=TLSv1.3 kex=X25519MLKEM768
      tun0: TX 23, RX 9

- `warp=on` with no external relay at all, on the host, with the relay environment explicitly empty:

      measureWith: env WARP_SOCKS="" WARP_RELAY=""
      ip packets      : sent=23 recv=16
      warp=on  ip=104.28.244.74  colo=FRA  loc=RU
      VERDICT: warp=on -- the request travelled through the WARP tunnel

- the APK that would be installed carries the fix, checked in the dex rather than assumed:

      startForegroundNotification   present
      colgram_warp                 present
      capsuleStats                 present
      sessionProgress              present
      so   trace JNI symbol         present
      so   stats JNI symbol         present

## How to clear it

The emulator needs its guest Android restarted rather than its process restarted, since the
process restart is what has been failing. Either a fresh instance, or `adb` reached through a
different route -- the emulator console on 5555 rather than the forwarded port.


## Additional confirmation

Checked in the built APK itself rather than in the source, since the device is unavailable:

```
startForegroundNotification   present
colgram_warp                 present
capsuleStats                 present
sessionProgress              present
so   trace JNI symbol         present
so   stats JNI symbol         present
```

The fix is in the dex that would be installed, not only in the tree. The WARP toggle was also
re-read: `ColgramSettingsActivity` writes `setWarpEnabled(true)` on the tap (line 698) and
`watchWarpVerdict` turns it back off when the route is judged dead (line 759), with the source
free of replacement characters. The encoding damage visible in a console dump is the terminal,
not the file.
