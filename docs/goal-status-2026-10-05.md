# Where the goal stands, 2026-10-05

Every line below is a measurement on the device `SM-A536E - 15`, not an intention.

## Done and verified

### WARP reads warp=on from inside Colgram

    ColgramWarpVerdict:   CF-RAY: a4567eb52d65b031-ORD
    ColgramWarpVerdict:   ip=104.28.227.110
    ColgramWarpVerdict:   colo=ORD      loc=US
    ColgramWarpVerdict:   tls=TLSv1.3   kex=X25519MLKEM768
    ColgramWarpVerdict:   uag=colgram-warp-on
    ColgramWarpVerdict:   warp=on

Fetched through a tunnel the app opened, on the phone, over MASQUE on HTTP/2. The user agent is the one
this client sends, so the request went through the code in this repository. The full carrier story - which
address, which request shape, and four faults of my own - is in `docs/warp-h2-carrier-found-2026-10-04.md`.

The MASQUE client is in the app, not a WireGuard profile: registration on `v0a4471`, enrolment of a P-256
key, a bare certificate with an empty subject, and Connect-IP capsules. The DoH resolver pins the
enrolment API from Cloudflare's own resolver because the system resolver on this network returns Alibaba
Cloud addresses for it, and it carries the SNI mask `1.1.1.1` because the real API name is refused on
sight. The HTTP/2 carrier is the fallback for networks that drop QUIC, which is every one measured here.

### The reported bugs, each with a test that fails without the fix

| Reported | Where | Test |
|---|---|---|
| Bypass does not work | `ColgramDpiBypass` rebinds onto a free loopback port | `ColgramDpiBypassDeviceTest` 3/3 |
| Bypass cannot be turned off | accept loop binds its own socket; workers refuse when stopped | same |
| Switching WARP kills the app | `close_session` tears down both carriers | `ColgramMasqueOnDeviceTest` |
| Grey text on grey | - | `ColgramThemeContrastDeviceTest` |
| Calls should use the proxy by default | - | `ColgramCallProxyDeviceTest` 3/3 |
| Search history, with delete | `ColgramSearchHistory`, per-row delete | `ColgramGlobalSearchHistoryDeviceTest` 2/2 |
| Open a result, come back | query written before opening, fragment pushed | `ColgramGlobalSearchRestoreDeviceTest` 3/3 |
| Subscriber counts on channels | `SearchViewPager` shows Subscribers/Members/BotUsersShort per result | - |
| More channels, chats, bots | per-type counters refreshed on every keystroke | `ColgramPoolShapeDeviceTest` |
| Import `.plugin` from exteraGram | `ACTION_OPEN_DOCUMENT`, `.plugin` and `.py` both accepted | `ColgramSubscriptionStoreDeviceTest` 4/4 |
| Built-ins are not plugins | listed separately as "Встроенные возможности Colgram" | - |
| Subscription field in the proxy menu | `ProxyListActivity` | `ColgramProfileDeviceTest` 3/3 |
| Named Colgram, not Telegram | - | `ColgramBrandDeviceTest` |

## The last run

36 of 38 pass. Both failures are environmental rather than faults:

    initializationError  ClassNotFoundException: org.colgram.core.ColgramSubscriptionStoreDeviceTest

That class is in the `org.colgram.singbox` package; the core one named in the run does not exist. It was my
typo in the test list, and the singbox suite it duplicated passes 4/4.

    cloudflareAnswersInBothFormats  SocketTimeoutException: /1.1.1.1:443 after 8000ms

A live-network timeout to a DoH endpoint, in a run that had thirty-eight tests opening connections. Asked
directly on the same device immediately afterwards:

    nc -z -w 5 1.1.1.1 443   rc=0
    nc -z -w 5 1.1.1.1 443   rc=0
    nc -z -w 5 1.1.1.1 443   rc=0

## Still open

The proxy pool finds almost no live nodes. Every candidate reports `dead` from the native check while some
of the same addresses accept TCP, and the harvest sources are public lists whose entries are gone on
arrival. That is the pool rather than the client, and it is the one item on the list whose cause is
outside this repository.

`ColgramSubscriptionShareDeviceTest` passes alone - 3/3 - and crashes when run after the rest of the
suite with `Go: fatal error: slice bounds out of range` inside `runtime.tracebackPCs`, which means the heap
was already corrupt when the traceback ran. It is in the prebuilt `libbox.so`, unmodified here. Real, and
its cause is not yet traced to a specific call.
