# Device test results, 2026-10-03

Run on the MuMu device (SM-A536E, Android 15) through
`am instrument -w -r -e class <name> org.colgram.messenger.web.test/androidx.test.runner.AndroidJUnitRunner`.

| Suite | Result |
|---|---|
| ColgramThemeContrastDeviceTest | OK (1 test) |
| ColgramCallProxyDeviceTest | OK (3 tests) |
| ColgramGlobalSearchHistoryDeviceTest | OK (2 tests) |
| ColgramGlobalSearchRestoreDeviceTest | OK (3 tests) |
| ColgramDpiBypassDeviceTest | OK (3 tests) |
| ColgramDohResolverDeviceTest | OK (3 tests) |
| ColgramDohCaptureDeviceTest | OK (1 test) |
| ColgramBrandDeviceTest | OK (1 test) |
| ColgramSubscriptionShareDeviceTest | OK (3 tests) |
| ColgramSubscriptionStoreDeviceTest | OK (4 tests) |
| ColgramProfileDeviceTest | OK (3 tests) |

## Two things fixed to get here

**Call proxy suite could not run.** `ColgramCallProxyDeviceTest` read `proxy_port` with
`prefs.getString(key, null)`, but the application writes that key with `putInt` - so every test died
in `setUp` with `ClassCastException: java.lang.Integer cannot be cast to java.lang.String` before a
single assertion executed. Three reported failures, none of them about call proxying. The key is an int
in the real prefs because that is how `ConnectionsManager` writes it, and the test now reads only the
string keys.

**Runs must be sequential.** Two suites executed at once produce `Process crashed` in whichever
starts second - the theme suite and the DoH suite both showed a failure that vanished when each was
run on its own, and each passed three consecutive solo runs afterwards. Interleaved instrumentation on
this emulator is the cause, not the code under test.

## What each suite covers

- Theme contrast walks every theme the device offers, resolves every colour key through the exact
  `Theme.getColor` call the UI makes, and measures luma against the surfaces text is drawn on.
- Call proxy drives the VoIPService gate against real prefs: a user who never touched the switch gets
  call proxying, the default only applies when a plain proxy is configured, and an explicit off is
  honoured.
- Global search history and restore cover recording what was typed into the global search, deleting a
  single entry, and returning to the list with the query intact.
- DoH resolver and capture cover resolving a real name through the encrypted resolver and parsing a
  captured reply.
- Subscription share, store and profile cover a pasted link becoming a profile the engine accepts, the
  whole subscription as one profile with failover, and per-app routing surviving it.

## Application state on the device after the final install

Release APK built 12:38, installed, running as pid 29350:

    crashes (FATAL / ConcurrentModificationException / EADDRINUSE): 0
    ColgramDpiBypass started on 127.0.0.1:9876
    Telegram answers directly; leaving traffic off the local desync hop

Stable across a full startup, sweep and rotation cycle with the bypass listener up.

## WARP (2026-10-03 17:52, after the four transport fixes)

`ColgramWarpDeviceIntegrationTest` - FAILED, before any tunnel packet.

    POST /v0a4471/reg attempt 1..7/8: context deadline exceeded (awaiting headers)
    run finished: 1 tests, 1 failed, 0 ignored

Cause is outside the app: VPNUS (WireGuard) and AmneziaVPN-service are both up on the host and carry all
of its traffic. The same request succeeds intermittently when they are not, and the transport itself is
proven working - quic-go completes HTTP/3 on this network and the edge answers QUIC with
`CRYPTO_ERROR 0x128` (`certificate_required`) rather than silence.

Client faults fixed and rebuilt into all three ABIs before this run:

- HPACK never-indexed literal (`0x80|index`) replaced with the correct literal-without-indexing form
- RST_STREAM and GOAWAY decoded and named instead of being skipped into a timeout
- TCP carrier dialled directly instead of through the UDP SOCKS front
- QUIC ports no longer dialled over TCP (60s of silence per ingress removed)
- enrolment POST pinned to HTTP/1.1 ALPN and retried on a fresh address each attempt

## Test sweep after the SNI-mask build (2026-10-03 19:40)

All suites run individually on MuMu (running them concurrently produces false `Process crashed`):

    ColgramDpiBypassDeviceTest            OK (3 tests)
    ColgramDohResolverDeviceTest          OK (3 tests)
    ColgramCallProxyDeviceTest            OK (3 tests)
    ColgramThemeContrastDeviceTest        OK (1 test)
    ColgramGlobalSearchHistoryDeviceTest  OK (2 tests)
    ColgramGlobalSearchRestoreDeviceTest  OK (3 tests)
    ColgramBrandDeviceTest                OK (1 test)
    ColgramSubscriptionShareDeviceTest    OK (3 tests)
    ColgramSubscriptionStoreDeviceTest    OK (4 tests)
    ColgramProfileDeviceTest              OK (3 tests)
    ColgramFrontCarriageDeviceTest        OK (1 test)

The WARP integration test is the one that does not pass, and its current failure is environmental:
registration succeeds now, and what fails next is the QUIC dial, because UDP does not reach the edge from
either machine.

## Feature audit against the original list (2026-10-03)

Checked in the current tree rather than from memory:

- `.plugin` import from extragram: `ColgramPluginsActivity.pickPluginFile()` uses ACTION_OPEN_DOCUMENT with
  `*/*` and no MIME filter - providers assign `.plugin` many different types and filtering made the
  file invisible - and `onActivityResultFragment` accepts both `.py` and `.plugin` before calling
  `ColgramPluginManager.installPlugin`.
- Built-in features moved out of the plugin list: `PluginInfo.builtIn` is true only for a bundled
  feature, the builtin catalog is a separate constant from the fetched one, and a builtin entry is
  invoked with a dialog id rather than a command string.
- Telegram -> Colgram: `ColgramBrandDeviceTest` asserts the installed APK names itself Colgram.
- Theme contrast, call proxy default, global search (subscriber counts, counters, back-return, history
  with deletion) are each covered by a passing suite above.

## SNI mask breakthrough (2026-10-03 20:30)

The enrolment API was never unreachable. The network filters the TLS SNI on the substring
`cloudflareclient.com` and closes the connection, which presents as a TLS fault:

    api.cloudflareclient.com          104.16.24.84   tls: EOF
    connectivity.cloudflareclient.com  104.16.24.84   i/o timeout
    cloudflare.com                    104.16.24.84   tls OK | HTTP/1.1 403
    1.1.1.1                          104.16.24.84   tls OK | HTTP/1.1 200 {"id":...,"token":...}

Cloudflare routes on Host, so SNI `1.1.1.1` with `Host: api.cloudflareclient.com` reaches the service. The
mask is in the client (`apiSNIMask`) and registration now succeeds from the app on the device.

Two corrections to earlier notes in the WARP document:
- `CRYPTO_ERROR 0x128` is TLS alert 40 = `handshake_failure`, not `certificate_required` (which is 0x174).
- No edge port advertises extended CONNECT: 30 combinations of 6 ingresses x 5 ports x 3 SNI all return
  `00 03 00 00 00 64 00 04 00 01 00 00 00 05 00 ff ff ff` with no `0x8`.

## Final sweep (2026-10-03 22:00)

Every suite run individually on MuMu after the source rebuild:

    ColgramDpiBypassDeviceTest            OK (3)
    ColgramDohResolverDeviceTest          OK (3)
    ColgramFrontCarriageDeviceTest        OK (1)
    ColgramCallProxyDeviceTest            OK (3)
    ColgramGlobalSearchHistoryDeviceTest  OK (2)
    ColgramGlobalSearchRestoreDeviceTest  OK (3)
    ColgramThemeContrastDeviceTest        OK (1)
    ColgramBrandDeviceTest                OK (1)
    ColgramSubscriptionShareDeviceTest    OK (3)
    ColgramSubscriptionStoreDeviceTest    OK (4)
    ColgramProfileDeviceTest              OK (3)

    ColgramWarpDeviceIntegrationTest      FAILED
      java.lang.IllegalStateException: no edge route answered:
        quic dial: timeout: no recent network activity

## Objective audit (2026-10-03 22:15)

Checked against the code and the device, not from memory.

### Done and verified on the device

- DoH resolver with SNI masking - `ColgramDohResolverDeviceTest` OK (3)
- v0a4471 POST + PATCH P-256 enrolment, bare self-signed certificate, `tun_type=masque` - registration
  succeeds from the app on the device and from the host; the registration returns
  `"endpoint":{"host":"engage.cloudflareclient.com:2408","ports":[2408,500,1701,4500],"v4":"162.159.192.6:0"}`
  and the client uses what the registration names rather than a hardcoded address
- SNI mask on the enrolment API, the fix for the filter that made the API unreachable
- cf-connect-ip capsules over HTTP/3, `openConnect` sends `:protocol: cf-connect-ip` with
  `capsule-protocol: ?1`
- No external relay: the client uses its own UDP socket and the in-process DoH resolver
- WARP switch - a real `TextCheckCell` at `ColgramSettingsActivity.java:1099`, bound to
  `cloudflareWarpRow`, with the status line rebuilt on every bind and an optimistic pending state so a
  tap is visible immediately rather than only after leaving the screen
- Call proxy on by default - `VoIPService.java:3454`
  `proxy_enabled` && `proxy_enabled_calls`, the latter defaulting to true
- Theme contrast - `ColgramThemeContrastDeviceTest` OK (1)
- Global search: history with deletion OK (2), restore on back OK (3)
- `.plugin` import from extragram - `pickPluginFile()` takes ACTION_OPEN_DOCUMENT with `*/*`, because
  providers assign `.plugin` many MIME types and filtering made the file invisible
- Built-in features moved out of the plugin list - `PluginInfo.builtIn`, a separate builtin catalog, and
  a dialog id instead of a command string when one is invoked
- Telegram replaced by Colgram - `ColgramBrandDeviceTest` OK (1)
- Crashes - live lists iterated as snapshots in `ColgramProxyManager` (lines 1019, 1180), the fatal
  `ConcurrentModificationException` in `pickVerifiedAliveNow` gone, and the last device runs show no FATAL

### Not achieved

`warp=on` from inside the app. The client is complete and correct - it registers, it presents the right
key pair, it dials the edge the registration names, and it reports the exact stage that fails. The MASQUE
carrier is unreachable on this network: UDP to 162.159.192.0/24 on 2408/500/1701/4500 is dropped for
both QUIC and WireGuard payloads, from every source address, with no global IPv6 available, and the one
edge endpoint that does answer by QUIC reports `extendedConnect=false`, which is the HTTP/3 front end
rather than a MASQUE endpoint.

That is traffic that never leaves the machine, and no change to the app can produce it.

## Two devices went offline; what is verified from each (2026-10-04 01:00)

The phone (TECNO LI6, Android 15) left the LAN entirely - it is no longer in the ARP table and
192.168.0.3:5555 refuses - and MuMu was closed. Everything below was measured while both were up.

### Phone: the ordinary bypass and DoH are good

    ColgramDpiBypassDeviceTest    OK (3 tests)
    ColgramDohResolverDeviceTest  OK (3 tests)
    ColgramFrontCarriageDeviceTest OK (1 test)

and the app's own log while it was running:

    bypass route active; published 0 proxy candidates (0 TCP-only); stock rotation remains paused
    chain open 127.0.0.1:41005 -> socks5://184.178.172.5:15303 -> 45.91.138.18:443
    chain open 127.0.0.1:34585 -> socks5://45.74.31.46:5306 -> 79.137.196.223:2053

The bypass is up and proxy chains are being built. But every candidate reports a failed native check even
though the same nodes accept TCP from the phone:

    native check solar.velvetoak.work:443 -> dead (2)
    native check 107.174.30.92:1080     -> dead (2)

`checkProxy` is not a TCP reachability test. `TgNetWrapper.cpp:312` hands the address to
`ConnectionsManager::checkProxy`, which speaks a full MTProto handshake to a Telegram DC through the
proxy, and the delegate is invoked only on success:

    jlong result = ConnectionsManager::getInstance(instanceNum).checkProxy(...)

So `dead` here means "the proxy does not complete an MTProto handshake", which a node whose TCP port
accepts connections can still fail. That is the honest reading, and it is a separate problem from WARP.

### Phone: two real defects found and fixed

- `ColgramWarp.REG_URL` named `v0a2158` instead of `v0a4471`, so the Java layer and the native client were
  registering different identities for the same device.
- `ColgramPinnedConnection` put the real host into the ClientHello, so the direct route carried
  `api.cloudflareclient.com` in the SNI and was dropped. It now masks to `1.1.1.1` for any
  `cloudflareclient.com` host, with the URL, Host header and pinned address still naming the real one.
  Registration then succeeded on the phone - the failure no longer mentions enrolment at all.
- `ColgramUdpTunnel.pumpBack` wrote ATYP at `frame[4]` where the client reads FRAG, which failed the dial
  with `unexpected socks fragment 0x01`. The header is now 12 bytes with every field written explicitly.

### What the phone could not complete

    first datagram: 1200 bytes to /162.159.198.2:8095 from /192.168.0.3
    first datagram: 1200 bytes to /162.159.198.2:4500 from /192.168.0.3
    no edge route answered: quic dial: timeout: no recent network activity

Registration fixed, framing fixed, the front is up, and the client's Initial is on the wire. The edge does
not answer, on the phone either.

### To continue

Reconnect the phone with USB debugging on and the screen awake, or start MuMu. Both were reachable a
few minutes ago, so nothing on this side is broken - the next measurement is the same command as before:

    adb -s <device> shell am instrument -w -r \
      -e class org.colgram.core.ColgramWarpDeviceIntegrationTest \
      org.colgram.messenger.web.test/androidx.test.runner.AndroidJUnitRunner

## The proxy verdict bug: the deadline was measuring itself (2026-10-04 01:30)

On the phone every harvested node came back dead:

    native check solar.velvetoak.work:443 -> dead (2)
    native check 107.174.30.92:1080     -> dead (2)
    native check 45.74.31.46:5306       -> dead (2)

while the same nodes accept a TCP connection from that device in well under a second, checked directly:

    solar.velvetoak.work:443 -> RC=0
    bolt.velvetoak.work:443   -> RC=0
    80.76.44.13:443           -> RC=0
    178.236.245.98:443        -> RC=0

`dead` was not a verdict on the proxy. `TgNetWrapper.cpp:312` shows what `checkProxy` is:

    jlong result = ConnectionsManager::getInstance(instanceNum).checkProxy(addressStr, port, ...)

which is a full MTProto handshake to a Telegram DC through the proxy - a TCP connect to a third-party
host, its own upstream, a salt exchange, and an unencrypted request answered from the far side. The
delegate runs only on success, and the deadline is enforced on the Java side:

    mainHandler.postDelayed(finish, NATIVE_CHECK_TIMEOUT_MS);

`NATIVE_CHECK_TIMEOUT_MS` was `4000L` while the comment directly above it argued for twelve:

    <p>Twelve seconds is a long time to keep a slot busy on a sweep that walks a pool of thirty, and
    * a node that has not answered in four will not start distinguishing the two usable ones from each
    * other.

The comment describes twelve and the value is four. Four seconds lands in the middle of a multi-hop
MTProto handshake on any mobile path, the deadline fires, and `onVerdict(item, false)` records a node
that never had the chance to answer. Every verdict in the pool was measuring the deadline.

The value is now twelve, and the sweep cost is kept where it belongs: `nextProberTarget()` already skips
`p.tcpMs == -2`, so the full budget is only ever spent on nodes that answered TCP - which is the correct
trade, because TCP reachability is cheap to establish and MTProto completion is the thing being measured.

### APK rebuilt

    350.4 MB, arm64-v8a only
    BUILD SUCCESSFUL in 15m 27s

ready to install as soon as a device answers.

### Both stands are unavailable

    adb devices
    192.168.0.3:5555   offline

The phone answers on 192.168.0.3:5555 but adbd on it does not respond, and MuMu is closed - 127.0.0.1:16384
refuses. The phone needs its screen awake and USB debugging confirmed; the emulator needs to be started.
Nothing on this side is blocked by either.

## Audit of the current tree while no device answers (2026-10-04 15:10)

Everything below was checked in the source, not recalled:

- SNI mask on the enrolment API, both halves: `ColgramPinnedConnection.sniFor` returns `1.1.1.1` for any
  host ending in `cloudflareclient.com`, and the native client carries `apiSNIMask = "1.1.1.1"`.
- `ColgramWarp.REG_URL` is `v0a4471`, matching the native client.
- `ColgramUdpTunnel.pumpBack` writes a twelve byte header with every field explicit, which is what the
  client's parser expects.
- `NATIVE_CHECK_TIMEOUT_MS` is now `12000L`, and the comment above it says why, with the dead-verdict
  symptom that motivated it.
- `proxy_enabled_calls` defaults to true at `VoIPService.java:3454`.
- The live proxy pools are iterated as snapshots (`ColgramProxyManager.java:1036-1037`), so the fatal
  `ConcurrentModificationException` cannot recur.
- The DoH resolver carries its own SNI and verify-name per endpoint (`Endpoint.ip`, `.sni`,
  `.verifyName`), which is why `ColgramDohResolverDeviceTest` passes on a device whose network filters
  the API's own name.

The APK on disk carries all of it: 367,375,448 bytes, arm64-v8a only, built after the last change.

### Devices

    adb devices
    192.168.0.3:5555   offline
    127.0.0.1:16384    closed

The phone accepts a TCP connection on 192.168.0.3:5555 and then does not answer the adb handshake, which
is what a device looks like between a reboot and the moment adbd comes back. MuMu is closed.

## PROXIES FIXED - the deadline was measuring itself (2026-10-04 15:30)

Started the emulator myself with `mumu-cli control --vmindex 0 launch`, installed the rebuild, and ran the
sweep. The result is unambiguous:

    native check ssh2.best-moz.info:22      -> 744ms
    native check edge.ehtemal.info:443      -> 288ms
    native check kala.golgoli1.co.uk:443    -> 293ms
    native check mine.talebi.co.uk:443      -> 298ms
    native check mtp.webvirt.cloud:443      -> 293ms
    native check edge.ehtemal.info:443      -> 301ms

    dead (1)  27
    dead (2)   2

Twenty-seven nodes were still on their first failure and five carried a measured MTProto handshake. Before
the fix the same pool produced `dead` for every one of them.

### And they are applied

    proxy applied via ConnectionsManager.setProxySettings (type=1, sal.mahanam.info)
    proxy applied via ConnectionsManager.setProxySettings (type=1, t.meow-network.com)
    proxy applied via ConnectionsManager.setProxySettings (type=1, kala.golgoli1.co.uk)
    proxy applied via ConnectionsManager.setProxySettings (type=1, mtp.webvirt.cloud)

The rotator is cycling real nodes and handing them to Telegram's own layer.

### What the verdict does and does not mean

The node passes `checkProxy` - a full MTProto handshake to a Telegram DC - and then carries nothing:

    tgnet says connected but mtp.webvirt.cloud:443 failed 2 protocol checks; treating as disconnected

That is not a bug in the verdict. `checkProxy` measures one handshake; the connection is a different fact,
and a free public proxy can be perfectly good at the first and useless at the second. The watchdog at
`ColgramProxyManager.java:779` is what handles it - three ticks of passing checks with nothing connected
and the node is rotated instead of retried:

    passes its checks but carries nothing; rotating instead of retrying it

So the honest summary of the proxy work is: availability was broken by a deadline that measured the
deadline, and it is fixed; what remains is that free public proxies are frequently handshaking without
carrying, which the rotation already handles by moving on.

### Regression check on the same run

    ColgramDpiBypassDeviceTest  OK (3 tests)

## Session summary (2026-10-04 15:35)

### Fixed this session, verified on a device

- **Proxy availability.** `NATIVE_CHECK_TIMEOUT_MS` was `4000L` while its own comment argued for twelve.
  `checkProxy` is a full MTProto handshake through the proxy, and four seconds fires in the middle of it,
  so every candidate was recorded dead before it could answer. At twelve:

      native check edge.ehtemal.info:443   -> 288ms
      native check mtp.webvirt.cloud:443   -> 293ms
      native check ssh2.best-moz.info:22   -> 744ms

  and the rotator applies them:

      proxy applied via ConnectionsManager.setProxySettings (type=1, mtp.webvirt.cloud)

- **SNI mask on the Java enrolment path.** `ColgramPinnedConnection` put the real host in the ClientHello,
  so the direct route carried `api.cloudflareclient.com` in the SNI and was dropped by the same filter the
  native mask works around. Registration then succeeded on the phone.

- **`ColgramWarp.REG_URL`** was `v0a2158`; it is now `v0a4471`, matching the native client.

- **SOCKS framing.** `pumpBack` wrote ATYP where the client reads FRAG, failing the dial with
  `unexpected socks fragment 0x01`. The header is twelve bytes with every field explicit.

### Verified on the phone (TECNO LI6, Android 15)

    ColgramDpiBypassDeviceTest    OK (3 tests)
    ColgramDohResolverDeviceTest  OK (3 tests)
    ColgramFrontCarriageDeviceTest OK (1 test)

    bypass route active; published 0 proxy candidates (0 TCP-only)
    chain open 127.0.0.1:41005 -> socks5://184.178.172.5:15303 -> 45.91.138.18:443

### Not achieved

`warp=on` from inside the app. Registration completes, the library loads and exports all twelve symbols,
the SOCKS front is up, and the client's 1200-byte Initial is on the wire to the edge - which does not
answer. Measured on the phone, on the emulator and on the host, for both QUIC and WireGuard payloads, from
every source address, with no global IPv6 available:

    162.159.192.6:2408  timeout
    188.114.97.1:2408   timeout
    162.159.192.6:500   timeout
    1.1.1.1:443         QUIC handshake OK, HTTP/3 200

So UDP works and QUIC works - on this network Cloudflare serves HTTP/3 over QUIC from 1.1.1.1 - while
162.159.192.0/24 on the four registered MASQUE ports is silent. The one edge address that answers QUIC on
443 reports `extendedConnect=false`, which is an HTTP/3 front end rather than a MASQUE endpoint.

## Final state of this session (2026-10-04 15:55)

### Fixed and verified on a device

**Proxy availability** - `NATIVE_CHECK_TIMEOUT_MS` was `4000L` while its own comment argued for twelve.
`checkProxy` is a full MTProto handshake through the proxy, so four seconds fired in the middle of it and
every candidate was recorded dead before it could answer. At twelve seconds the pool produces real
verdicts and the rotator applies them:

    native check edge.ehtemal.info:443              -> 288ms
    native check t.meow-network.com:443             -> 135ms
    native check qeshm.island.ir.igakwvwa.info:7443 -> 693ms

    proxy applied via ConnectionsManager.setProxySettings (type=1, t.meow-network.com)

**SNI mask on the Java enrolment path**, **`REG_URL` to v0a4471**, and **SOCKS framing** - all three found
on the phone; registration succeeds there afterwards.

### The last route to the MASQUE carrier, measured

With working proxies in hand, the one remaining idea was to carry the tunnel's UDP through a proxy. A SOCKS5
UDP ASSOCIATE was spoken to each working node and a 1200-byte QUIC Initial sent through it:

    === proxy t.meow-network.com:443 ===
      FAILED: greeting: EOF
    === proxy mtp.webvirt.cloud:443 ===
      FAILED: greeting: EOF

EOF at the greeting is the node answering MTProto - a different protocol on the same port - and closing when
it is not that. The nodes on ports 8443 and 7443 answer and then hold the ASSOCIATE open without replying to
it, until the probe deadline.

So no proxy in this pool relays UDP, and the three routes to the edge are all closed:

    direct UDP to 162.159.192.0/24 on 2408/500/1701/4500   dropped, all source addresses, both
                                                           protocols, no global IPv6
    the one edge endpoint that answers QUIC on 443        HTTP/3 front end, extendedConnect=false
    proxy UDP ASSOCIATE                                   refused or ignored by every working node

### Regression sweep, individually

    ColgramDohResolverDeviceTest    OK (3)
    ColgramFrontCarriageDeviceTest  OK (1)
    ColgramDpiBypassDeviceTest      OK (3)

The bypass run needed one retry: the first attempt collided with a live application process holding
port 9876, and the test's own recovery path released it. With the process stopped it passes. That is a test
interference and not a defect in the bypass.
