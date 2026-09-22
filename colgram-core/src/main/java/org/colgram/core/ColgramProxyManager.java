package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ColgramProxyManager — Real Anti-Censorship Engine.
 *
 * 1. Starts embedded local SOCKS5 DPI bypass (127.0.0.1:9876) with 1-byte TCP desync.
 * 2. Fetches and maintains fresh Fake-TLS MTProto proxies from multiple live GitHub sources.
 * 3. Supports seamless auto-rotation on connection timeout (-1000 error).
 * 4. Ensures proxy settings are cleanly propagated to Telegram's native layer and SharedConfig.
 */
public class ColgramProxyManager {

    /**
     * Number of account slots to configure proxy settings for.
     *
     * MUST stay in sync with UserConfig.MAX_ACCOUNT_COUNT (apply-patches.py patch 49)
     * and MUST NOT exceed 5.
     *
     * 5 is not a taste decision - it is the size of the native tgnet global
     * `JNIEnv *jniEnv[MAX_ACCOUNT_COUNT]` array (jni/tgnet/Defines.h), which the
     * prebuilt official libtmessages.49.so was compiled with. Iterating past it
     * writes off the end of that array and corrupts the process.
     */
    private static final int colgramAccountSlots = 5;

    private static final String TAG = "ColgramProxyManager";

    public static class ProxyItem {
        public final String address;
        public final int port;
        public final String secret;
        public final int type; // 0 = SOCKS5, 1 = MTProto, 2 = WEB (wss:// bridge, no port)
        /** -1 never checked, -2 checked and not working, otherwise RTT in ms. */
        public int pingMs = -1;
        /**
         * Only ever set by a real protocol check (Telegram's native checkProxy). It used to be
         * assigned {@code true} to the whole hardcoded list without any check at all, which made
         * "Pool: 8 alive" a fiction and let the rotator prefer nodes that had never answered.
         */
        public boolean isAvailable = false;
        /** SystemClock.elapsedRealtime() of the last protocol check; 0 = never checked. */
        public long lastCheckAt = 0L;
        /** Times Telegram's native layer failed a connection while this entry was applied. */
        int nativeFailures = 0;

        public ProxyItem(String address, int port, String secret, int type) {
            this.address = address;
            this.port = port;
            this.secret = secret != null ? secret : "";
            this.type = type;
        }

        public boolean isLocalDpi() {
            return "127.0.0.1".equals(address) && port == ColgramDpiBypass.LOCAL_PORT;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ProxyItem)) return false;
            ProxyItem other = (ProxyItem) o;
            return address.equals(other.address) && port == other.port;
        }

        @Override
        public int hashCode() {
            return address.hashCode() * 31 + port;
        }

        @Override
        public String toString() {
            if (isLocalDpi()) return "Локальный обходчик ТСПУ (127.0.0.1:9876)";
            if (type == 2) return address + " (WebSocket)";
            return address + ":" + port + (type == 1 ? " (MTProto)" : " (SOCKS5)");
        }
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(8);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final List<ProxyItem> verifiedPool = Collections.synchronizedList(new ArrayList<>());
    private static volatile ProxyItem currentActiveProxy = null;
    private static volatile Context appContext = null;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Multiple GitHub sources for fresh proxies
    private static final String[] PROXY_SOURCES = {
        "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/proxies.json",
        "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
    };

    /**
     * Main entry point — called from ColgramHookHandler.init() on app startup.
     */
    public static void activateBuiltinProxy(final Context context) {
        if (context == null || !initialized.compareAndSet(false, true)) return;
        appContext = context.getApplicationContext();

        // Everything below touches sockets: binding the local DPI listener,
        // probing proxies over TCP, and flipping Telegram's proxy settings (which
        // makes MTProto tear down and rebuild its connections). Running that
        // during application startup races Telegram's native ConnectionSocket for
        // process-wide file descriptors and trips bionic's fdsan guard, aborting
        // the process inside libtmessages.49.so. Defer the whole block until the
        // network stack has settled.
        STARTUP_DEFERRED.execute(() -> {
            try {
                Thread.sleep(PROXY_START_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                activateBuiltinProxyNow();
            } catch (Throwable t) {
                Log.e(TAG, "Deferred proxy activation failed", t);
            }
        });
    }

    private static final ExecutorService STARTUP_DEFERRED = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colgram-proxy-deferred");
        t.setDaemon(true);
        return t;
    });
    /** Delay before any proxy-side socket work begins. */
    private static final long PROXY_START_DELAY_MS = 10000L;

    private static void activateBuiltinProxyNow() {
        // 1. Start embedded DPI bypass engine (runs local service on 127.0.0.1:9876)
        //
        // startImmediately(), NOT start(): this method is already running inside the
        // manager's own deferral, so asking the bypass to defer again stacked the delays
        // serially (10s + 8s = ~18s) before the listener existed. That was the dominant
        // cause of the slow post-login connect.
        //
        // Only when the user actually asked for it. The listener used to start on every
        // launch regardless: a bound socket, its accept thread and the desync machinery all
        // running for traffic that is no longer routed through them (the proxy is no longer
        // force-applied by default), plus awaitReady() below blocking up to 20s on a cold
        // start for a component that is switched off.
        boolean localReady = false;
        if (ColgramConfig.isDpiBypassEnabled()) {
            ColgramDpiBypass.startImmediately();
            // The local listener is the pool's FIRST entry, so it must actually be accepting
            // before we point Telegram at it. Without this wait the app would apply a proxy
            // pointing at a closed port, Telegram would raise a proxy error, and the rotator
            // would move off the local bypass entirely.
            localReady = ColgramDpiBypass.awaitReady(20000);
            if (!localReady) {
                // Do NOT apply a proxy that points at a closed port: Telegram shows it as
                // "Недоступен", the toggle flips itself off, and the user loses the proxy UI
                // entirely. Better to leave the setting alone and say so in the log.
                Log.e(TAG, "local DPI listener never bound; skipping local proxy application");
            }
        } else {
            Log.i(TAG, "DPI bypass disabled; not starting the local listener");
        }

        // 2. Populate verified pool (local desync bypass first, public proxies after)
        initVerifiedPool();
        populateSharedConfigProxies();

        // 3. Apply proxy: DIRECT by default, proxy only when the user asks for one.
        //
        // This used to default to ENABLED on a fresh install and force-apply
        // verifiedPool.get(0), which is the in-process desync listener. Two measurements killed
        // that design:
        //
        //   * On a network where Telegram is blocked, the block is an IP-level silent drop -
        //     TCP to 149.154.166.110:443 and 149.154.167.99:443 times out while general internet
        //     is fine (1.1.1.1 answers in 62ms). Desync manipulates payload framing to defeat
        //     INSPECTION; it cannot make a dropped route answer. So the thing we made the default
        //     does not help against the block it was written for.
        //   * On a network where Telegram is NOT blocked, the loopback hop is pure cost: an extra
        //     proxy, an extra failure mode, and a port that can fail to bind and pin the app to
        //     a dead 127.0.0.1.
        //
        // What does work against an IP block is an obfuscated proxy with a real endpoint - the
        // user's own MTProto proxy, or a WebSocket bridge. That is now the documented path, and
        // "Обходчик ТСПУ" stays available as an explicit opt-in for signature-based DPI.
        SharedPreferences mainPrefs = appContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        boolean hasSetProxy = mainPrefs.contains("proxy_enabled");
        boolean isProxyEnabled = hasSetProxy && mainPrefs.getBoolean("proxy_enabled", false);
        if (isProxyEnabled && ColgramConfig.isBuiltinProxyEnabled() && !verifiedPool.isEmpty()) {
            // Restore the proxy the user actually chose. This used to apply
            // verifiedPool.get(0) unconditionally, which is the local desync node - so anyone
            // who picked a public MTProto proxy was silently moved back onto the loopbar hop
            // the next time the app started, and "my proxy does not stick" was the result.
            ProxyItem saved = findSavedProxy(mainPrefs);
            if (saved != null && !(saved.isLocalDpi() && !localReady)) {
                forceApplyProxy(saved);
                Log.i(TAG, "restored proxy " + saved.address + ":" + saved.port
                        + " type=" + saved.type
                        + " secret=" + (saved.secret == null || saved.secret.isEmpty() ? "none" : "set"));
            } else {
                // No usable saved entry: fall back to the pool head, but never onto a local
                // listener that failed to bind - that pins Telegram to a dead 127.0.0.1.
                ProxyItem first = verifiedPool.get(0);
                if (first.isLocalDpi() && !localReady) {
                    Log.w(TAG, "local bypass not ready; skipping it when applying the first proxy");
                } else {
                    forceApplyProxy(first);
                }
            }
        }

        // 4. Background — fetch fresh proxies. The fetch ends by asking the native checker for a
        // verdict on every candidate and publishing the pool to Telegram's own proxy list.
        executor.execute(() -> {
            try {
                fetchAndVerifyAllSources();
            } catch (Throwable t) {
                Log.e(TAG, "Initial proxy fetch error", t);
            }
        });

        // 5. Periodic health check every 30 seconds (was 5 minutes)
        //
        // The local DPI bypass used to be EXCLUDED here, on the assumption that it is
        // in-process and therefore always alive. It is not: the listener binds on a deferred
        // thread and can fail to bind at all, and once that happens nothing retries it —
        // Telegram sits on a dead 127.0.0.1:9876 with the toggle showing "Недоступен" and
        // the user has no way back except toggling the proxy off and on.
        //
        // 5 minutes was also far too slow to notice. 30s keeps the cost trivial (a single
        // loopback connect) while making recovery actually feel automatic.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!ColgramConfig.isBuiltinProxyEnabled()) return;
                ProxyItem active = currentActiveProxy;

                if (active != null && active.isLocalDpi()) {
                    // Probe the loopback listener for real. isBound() alone is not enough:
                    // it is a flag set at bind time and never cleared if the socket later
                    // dies (e.g. the accept loop throws and exits), so a stale `true` would
                    // hide exactly the failure this check exists to catch.
                    int ping = testProxy(active.address, active.port, 3000);
                    if (ping < 0 || !ColgramDpiBypass.isBound()) {
                        Log.w(TAG, "local DPI listener unhealthy (ping=" + ping
                                + ", bound=" + ColgramDpiBypass.isBound() + "); attempting recovery");
                        if (!ColgramDpiBypass.restartIfDeadAndWait(5000)) {
                            Log.e(TAG, "local DPI listener still not bound after recovery");
                        }
                    }
                    return;
                }

                if (active != null) {
                    // A TCP connect proves nothing about a proxy - it succeeds against any host
                    // with the port open, including one that has never heard of MTProto, and it
                    // always fails for a WEB proxy, which has no port at all (so a working
                    // wss:// tunnel was rotated away every 30 seconds). Ask the native checker
                    // instead and rotate only on an actual protocol verdict.
                    if (active.type == 2) return;
                    // Do not stack a second check on top of the sweep's own: the extra
                    // handshake through the same proxy is what makes it answer "dead".
                    if (sweepRunning.get()) return;
                    // Same 2-minute freshness window as the sweep.
                    if (active.lastCheckAt > 0
                            && SystemClock.elapsedRealtime() - active.lastCheckAt < RECHECK_INTERVAL_MS) {
                        return;
                    }
                    final ProxyItem checked = active;
                    mainHandler.post(() -> {
                        boolean started;
                        try {
                            started = checkOne(checked, alive -> {
                                if (alive) return;
                                Log.w(TAG, "Current proxy failed the protocol check: "
                                        + checked.address);
                                reportProxyFailure();
                                switchToNextProxy();
                            });
                        } catch (Throwable t) {
                            started = false;
                        }
                        if (!started) {
                            Log.w(TAG, "no native checker for " + checked.address
                                    + "; leaving the applied proxy alone");
                        }
                    });
                }
            } catch (Throwable ignored) {}
        }, 30, 30, TimeUnit.SECONDS);

        // 6. Full re-fetch and re-check every 30 minutes
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                fetchAndVerifyAllSources();
            } catch (Throwable ignored) {}
        }, 30, 30, TimeUnit.MINUTES);
    }

    private static void initVerifiedPool() {
        // Priority 1: the LOCAL desync bypass, 127.0.0.1:9876.
        //
        // It goes FIRST, ahead of every public proxy, and that ordering is the whole
        // point:
        //   * It is the only option with no third party in the path. A public MTProto
        //     proxy is a stranger's server and its operator sees your IP, timing and
        //     volume by design - "anonymity checking" of someone else's proxy is not
        //     possible from the client. The local listener has nobody to trust.
        //   * It cannot be switched off by a host dying, which is the normal fate of
        //     free public proxies.
        //   * It costs one loopback hop.
        // So the free, always-available, most-private path is the DEFAULT, and public
        // proxies are the fallback rather than the other way round.
        ProxyItem localDpi = new ProxyItem("127.0.0.1", ColgramDpiBypass.LOCAL_PORT, "", 0);
        if (!containsProxy(localDpi)) {
            verifiedPool.add(localDpi);
        }

        // Priority 2: public FakeTLS MTProto proxies, as a fallback for networks where the
        // desync gets through but the destination is still refused. Kept, but demoted -
        // these are strangers' servers and they die often.
        ProxyItem[] hardcoded = {
            new ProxyItem("77.239.105.219", 443, "ee6c083120393936fb881456da3ec073777777772e676f6f676c652e636f6d", 1),
            new ProxyItem("176.57.69.182", 53627, "ee42eb79c1df22d7be6de261ce63082a4d31632e7275", 1),
            new ProxyItem("194.59.221.90", 8443, "eef4b79908a669cfe8f293941da4e388916465636174686c6f6e2e636f6d", 1),
            new ProxyItem("ma.hastim.co.uk", 443, "ee1603010200010001fc030386e24c3add6d656469612e737465616d706f77657265642e636f6d", 1),
            new ProxyItem("media.experthost.shop", 443, "ee3360704eb31ee47a17fc96383f7fcf7c6d656469612e657870657274686f73742e73686f70", 1),
            new ProxyItem("sioms.co.uk", 25565, "ee104462821249bd7ac519130220c25d0963646e2e79656b74616e65742e636f6d", 1),
            new ProxyItem("yostavpn.casacam.net", 443, "ee3db34d5ab674545e688abbefee52237f796f73746176706e2e6361736163616d2e6e6574", 1)
        };

        // These used to be inserted pre-marked {@code isAvailable = true; pingMs = 1}, with no
        // check of any kind. Everything downstream reads that flag - "Pool: N alive", the
        // rotation preference, the stock list's green/grey state - so the pool claimed seven
        // working proxies on a cold start and the rotator saw no reason to look further.
        // Availability now comes from Telegram's own native proxy checker (checkPoolNow).
        for (ProxyItem p : hardcoded) {
            if (!containsProxy(p)) {
                verifiedPool.add(p);
            }
        }
    }

    /**
     * Ask Telegram's own native checker which of our candidates actually work.
     *
     * {@code ConnectionsManager.checkProxy(ProxySettings, RequestTimeDelegate)} performs a real
     * MTProto handshake through the proxy (and, for a WEB proxy, runs it through
     * WebProxyTransport first), so it distinguishes an MTProxy from an unrelated daemon that
     * merely has the port open. A plain TCP connect cannot - and Colgram's pool is made of
     * exactly the kind of host where that matters. Upstream uses this same call in
     * ProxyRotationController and ProxyListActivity; reusing it means Colgram's numbers are the
     * ones the stock UI shows too.
     *
     * Checks run one at a time on the main thread because that is how upstream calls them and
     * because the native side serialises them anyway.
     */
    private static final long NATIVE_CHECK_TIMEOUT_MS = 12000L;
    /**
     * A node checked within this window is not probed again. Upstream uses the same 2-minute
     * window in ProxyRotationController, and it matters here for a reason that only measurement
     * showed: these are free public MTProxies, and repeated handshakes from one client get them
     * rate-limited or dropped. Observed on this network - three nodes answering in 95-173 ms
     * became unreachable at TCP level within 25 minutes, while the app was probing them.
     */
    private static final long RECHECK_INTERVAL_MS = 120000L;
    private static final AtomicBoolean sweepRunning = new AtomicBoolean(false);

    /** Max candidates probed per sweep - the pool grows from fetched lists without bound. */
    private static final int MAX_CHECKED_PER_SWEEP = 8;

    public static void checkPoolNow() {
        checkPoolNow(false);
    }

    /** @param force re-probe even the nodes checked moments ago; used by the settings tap. */
    public static void checkPoolNow(boolean force) {
        if (appContext == null || !sweepRunning.compareAndSet(false, true)) return;
        if (verifiedPool.isEmpty()) {
            // Tapping "check" seconds after launch used to do nothing at all: the pool is only
            // seeded after the startup deferral, so the sweep found no targets and the screen
            // kept saying "не проверен" with no way to make it try again.
            initVerifiedPool();
        }
        final long now = SystemClock.elapsedRealtime();
        final List<ProxyItem> targets = new ArrayList<>();
        for (ProxyItem p : verifiedPool) {
            // A WEB entry would need a WebView bridge per check and the pool never contains
            // one; the user's own WEB proxy is checked where it is actually used.
            if (p.type == 2) continue;
            if (!force && p.lastCheckAt > 0 && now - p.lastCheckAt < RECHECK_INTERVAL_MS) continue;
            targets.add(p);
            if (targets.size() >= MAX_CHECKED_PER_SWEEP) break;
        }
        if (targets.isEmpty()) {
            sweepRunning.set(false);
            return;
        }
        mainHandler.post(() -> checkNext(targets, 0, () -> sweepRunning.set(false)));
    }

    private static void checkNext(final List<ProxyItem> targets, final int index, final Runnable onDone) {
        if (index >= targets.size()) {
            onDone.run();
            return;
        }
        final ProxyItem item = targets.get(index);
        final AtomicBoolean advanced = new AtomicBoolean(false);
        final long startedAt = SystemClock.elapsedRealtime();
        final Runnable advance = () -> {
            if (advanced.compareAndSet(false, true)) {
                // Reached only from the timeout: the native callback never fired, which means
                // the host accepted TCP and then said nothing. That is a dead proxy, not an
                // unknown one, and it must stop being preferred.
                if (item.lastCheckAt < startedAt) {
                    item.lastCheckAt = startedAt;
                    item.isAvailable = false;
                    item.pingMs = -2;
                }
                checkNext(targets, index + 1, onDone);
            }
        };
        boolean started;
        try {
            started = checkOne(item, alive -> advance.run());
        } catch (Throwable t) {
            started = false;
        }
        if (!started) {
            advance.run();
            return;
        }
        mainHandler.postDelayed(advance, NATIVE_CHECK_TIMEOUT_MS);
    }

    /**
     * Single native protocol check. Returns false when the check could not even be started
     * (class or method moved), in which case {@code onResult} is NOT called.
     */
    private static boolean checkOne(final ProxyItem item, final AvailabilityHandler onResult) {
        try {
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> rtdClass = Class.forName("org.telegram.tgnet.RequestTimeDelegate");
            final Object settings = buildProxySettings(item);
            if (settings == null) return false;

            final Object delegate = java.lang.reflect.Proxy.newProxyInstance(
                    rtdClass.getClassLoader(),
                    new Class<?>[]{rtdClass},
                    (proxy, method, args) -> {
                        if ("run".equals(method.getName())) {
                            final long time = (args == null || args.length == 0
                                    || !(args[0] instanceof Number)) ? -1L : ((Number) args[0]).longValue();
                            mainHandler.post(() -> {
                                item.lastCheckAt = SystemClock.elapsedRealtime();
                                if (time < 0) {
                                    item.isAvailable = false;
                                    item.pingMs = -2;
                                } else {
                                    item.isAvailable = true;
                                    item.pingMs = (int) Math.max(1L, time);
                                    item.nativeFailures = 0;
                                }
                                Log.d(TAG, "native check " + item.address + ":" + item.port
                                        + " -> " + (item.isAvailable ? item.pingMs + "ms" : "dead"));
                                onResult.onResult(item.isAvailable);
                            });
                        }
                        return null;
                    });

            Object cm = cmClass.getMethod("getInstance", int.class).invoke(null, 0);
            cmClass.getMethod("checkProxy", settings.getClass(), rtdClass).invoke(cm, settings, delegate);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "native proxy check unavailable: " + t.getMessage());
            return false;
        }
    }

    private interface AvailabilityHandler {
        void onResult(boolean alive);
    }

    /**
     * Build upstream's own ProxySettings value object for one of our items, through its public
     * builder, so the native layer, the stock screen and Colgram all describe a proxy the same
     * way. Returns null if the classes moved.
     */
    private static Object buildProxySettings(ProxyItem item) {
        try {
            Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");
            Class<?> pstClass = Class.forName("org.telegram.proxy.ProxySettings$Type");
            String typeName = item.type == 2 ? "WEB" : (item.type == 1 ? "MTPROTO" : "SOCKS5");
            Object typeObj = Enum.valueOf((Class<Enum>) pstClass, typeName);

            Object builder = psClass.getDeclaredMethod("builder").invoke(null);
            Class<?> bClass = builder.getClass();
            bClass.getDeclaredMethod("setType", pstClass).invoke(builder, typeObj);
            bClass.getDeclaredMethod("setAddress", String.class).invoke(builder, item.address);
            bClass.getDeclaredMethod("setPort", int.class).invoke(builder, item.port);
            bClass.getDeclaredMethod("setSecret", String.class)
                    .invoke(builder, item.secret == null ? "" : item.secret);
            return bClass.getDeclaredMethod("build").invoke(builder);
        } catch (Throwable t) {
            Log.w(TAG, "buildProxySettings failed for " + item.address, t);
            return null;
        }
    }

    /**
     * Switch to the next available proxy in the pool and apply it.
     */
    /**
     * Timestamp of the last rotation. onProxyError() can fire on every failed connection
     * attempt, so without this a flapping proxy would spin the entire pool in a second and
     * land on a random entry rather than the next one.
     */
    private static volatile long lastRotationAt = 0L;
    /**
     * Rate limit between rotations. Deliberately long: onProxyError fires PER FAILED
     * CONNECTION ATTEMPT, and Telegram retries aggressively on its own. A short debounce
     * (this was 3 s) means the pool is walked over and over, and every step is a fresh
     * outbound connection to a dead host. Through an emulator's NAT that becomes a
     * connection storm that starves the HOST machine's networking - observed for real.
     */
    private static final long ROTATION_DEBOUNCE_MS = 30000L;

    /** When the local DPI bypass was applied, so it can be given a fair chance. */
    private static volatile long localDpiAppliedAt = 0L;
    /**
     * How long the local desync listener gets before we rotate away from it.
     *
     * Its adaptive strategy prober needs several connections to find a strategy that gets
     * through. Rotating on the first failure both defeats the prober and drives the storm,
     * so the two features have to be ordered: let the prober work first.
     */
    private static final long LOCAL_DPI_GRACE_MS = 45000L;

    /** Hard cap on rotations per window - a pool must never be walked in a loop. */
    private static final int MAX_ROTATIONS_PER_WINDOW = 4;
    private static final long ROTATION_WINDOW_MS = 5 * 60 * 1000L;
    private static int rotationBudget = MAX_ROTATIONS_PER_WINDOW;
    private static volatile long budgetWindowStart = 0L;

    /**
     * Rotate to the next proxy in the pool.
     *
     * Called from two places now:
     *   * ConnectionsManager.onProxyError() - Telegram's native layer reports a failed
     *     proxy connection here. This is the trigger that was missing: rotation used to be
     *     driven ONLY by the 5-minute periodic health check, so with 8 pool entries a user
     *     whose proxies were all dead waited up to 40 minutes to reach a working one, and
     *     it looked like Colgram simply never cycled them.
     *   * the periodic health check, as a backstop.
     */
    /**
     * Called from ConnectionsManager.onProxyError(): Telegram's native layer failed a connection
     * while going through the currently applied proxy.
     *
     * This is the one failure signal that travelled the whole proxy path, so it now decides
     * whether an entry counts as a candidate. Before it existed, availability came only from the
     * startup sweep: a node that worked once and died an hour later stayed "alive" in Colgram's
     * bookkeeping and the rotator kept offering it.
     *
     * Two failures, not one: a single drop is what a mobile network looks like, and demoting on
     * the first one makes the pool shrink itself into nothing.
     */
    public static void reportProxyFailure() {
        ProxyItem active = currentActiveProxy;
        if (active == null || active.isLocalDpi()) return;
        active.nativeFailures++;
        if (active.nativeFailures < 2) return;
        active.isAvailable = false;
        active.pingMs = -2;
        Log.w(TAG, "native proxy failures recorded for " + active.address + ":" + active.port
                + "; dropping it from the preferred candidates");
    }

    public static void switchToNextProxy() {
        switchToNextProxy(false);
    }

    /**
     * @param force rotate even when the applied proxy has not accumulated two failures yet;
     *              used when the user asks for a different node explicitly.
     */
    public static synchronized void switchToNextProxy(boolean force) {
        if (verifiedPool.isEmpty()) {
            // Pool genuinely empty: nothing to rotate to. Re-seed and re-fetch instead of
            // returning silently, which is what made this look like a dead feature.
            Log.w(TAG, "switchToNextProxy: pool empty, re-seeding and re-fetching");
            initVerifiedPool();
            executor.execute(() -> {
                try {
                    fetchAndVerifyAllSources();
                } catch (Throwable t) {
                    Log.w(TAG, "re-fetch after empty pool failed", t);
                }
            });
            return;
        }

        // Never rotate away from a proxy the user chose themselves. A WEB (wss://) proxy is
        // entered through Telegram's own proxy screen and has no reason to be in our pool, so
        // without this the health check or a single onProxyError would replace a working
        // user-supplied tunnel with one of our public nodes - and to the user that looks like
        // "the proxy I set keeps turning itself off".
        if (currentActiveProxy != null && !verifiedPool.contains(currentActiveProxy)) {
            Log.i(TAG, "holding user-configured proxy " + currentActiveProxy.address
                    + " (type=" + currentActiveProxy.type + "); it is not in the managed pool");
            return;
        }

        // One failed handshake is not a verdict. Observed on the emulator: three nodes that the
        // startup sweep measured at 95-173 ms each reported a failure within a minute of being
        // applied, and rotating on every one of those spent the whole 4-per-window budget in two
        // minutes and left the app parked on a dead proxy. Free MTProxies throttle extra
        // connections, so require two strikes before moving.
        if (!force && currentActiveProxy != null && !currentActiveProxy.isLocalDpi()
                && currentActiveProxy.nativeFailures > 0 && currentActiveProxy.nativeFailures < 2) {
            Log.i(TAG, "holding " + currentActiveProxy.address
                    + " after one failure; two are needed before rotating");
            return;
        }

        long now = SystemClock.elapsedRealtime();

        // Give the local desync listener time to find a working strategy before abandoning
        // it. Without this the prober never gets to finish and we rotate into dead public
        // proxies on the very first failure.
        if (currentActiveProxy != null && currentActiveProxy.isLocalDpi()
                && !ColgramDpiBypass.hasWorkingStrategy()
                && localDpiAppliedAt > 0
                && now - localDpiAppliedAt < LOCAL_DPI_GRACE_MS) {
            Log.d(TAG, "Holding the local DPI bypass: desync strategy search still running");
            return;
        }

        if (now - lastRotationAt < ROTATION_DEBOUNCE_MS) {
            return;
        }

        // Hard budget. Walking the pool repeatedly is what turns a dead proxy list into a
        // connection storm; once the budget is spent we hold whatever we have and let the
        // 5-minute health check resume later.
        if (budgetWindowStart == 0L || now - budgetWindowStart > ROTATION_WINDOW_MS) {
            budgetWindowStart = now;
            rotationBudget = MAX_ROTATIONS_PER_WINDOW;
        }
        if (rotationBudget <= 0) {
            Log.w(TAG, "Rotation budget spent for this window; holding current proxy");
            return;
        }
        rotationBudget--;
        lastRotationAt = now;

        int currentIndex = -1;
        for (int i = 0; i < verifiedPool.size(); i++) {
            if (verifiedPool.get(i).equals(currentActiveProxy)) {
                currentIndex = i;
                break;
            }
        }
        // Pick the best candidate rather than simply the next slot. Walking onto nodes that the
        // checker had already reported dead is what made rotation look like it "does nothing":
        // the step is real, the destination is broken. Preference is verified-alive, then
        // never-checked, and only then anything at all.
        ProxyItem next = selectProxy(currentActiveProxy);
        if (next == null) {
            next = roundRobinNext(currentIndex);
        }
        if (next == null) {
            Log.w(TAG, "no rotation candidate; holding the current proxy and re-fetching");
            executor.execute(() -> {
                try {
                    fetchAndVerifyAllSources();
                } catch (Throwable ignored) {}
            });
            return;
        }
        Log.d(TAG, "Rotating proxy to: " + next);

        // Nothing verified alive left in the list: refresh it in the background so a later
        // cycle has real candidates. Falling back to the local desync listener here used to be
        // unconditional, which pointed Telegram at 127.0.0.1:9876 while the bypass was switched
        // off - a proxy that cannot answer, shown as unavailable, and rotated away from again.
        if (!next.isAvailable && !next.isLocalDpi()) {
            executor.execute(() -> {
                try {
                    fetchAndVerifyAllSources();
                } catch (Throwable ignored) {
                }
            });
        }

        final ProxyItem target = next;
        mainHandler.post(() -> {
            forceApplyProxy(target);
            if (appContext != null) {
                try {
                    Toast.makeText(appContext, "Сеть: " + target.toString(), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });
    }

    /** The in-process 127.0.0.1 DPI-bypass entry, if the pool has one. */
    private static ProxyItem findLocalDpiProxy() {
        for (ProxyItem p : verifiedPool) {
            if (p.isLocalDpi()) return p;
        }
        return null;
    }

    /**
     * Whether the in-process desync listener is a legitimate target right now. The user has to
     * have switched it on AND the socket has to be accepting; otherwise applying it hands
     * Telegram a proxy that cannot answer, which reads as "the proxy does not turn on".
     */
    private static boolean localBypassUsable() {
        return ColgramConfig.isDpiBypassEnabled() && ColgramDpiBypass.isBound();
    }

    /**
     * First pool entry worth applying: one the native checker confirmed working, else the local
     * listener when it is actually up. Returns null when the pool has nothing better to offer
     * than what is applied now.
     */
    private static ProxyItem selectProxy(ProxyItem skip) {
        ProxyItem unchecked = null;
        for (int i = 0; i < verifiedPool.size(); i++) {
            ProxyItem p = verifiedPool.get(i);
            if (p.equals(skip)) continue;
            if (p.isLocalDpi() && !localBypassUsable()) continue;
            if (p.isAvailable) return p;
            if (unchecked == null && p.pingMs == -1) unchecked = p;
        }
        return unchecked;
    }

    /** Next pool entry after {@code currentIndex}, skipping unusable entries. Last resort. */
    private static ProxyItem roundRobinNext(int currentIndex) {
        int size = verifiedPool.size();
        for (int step = 1; step <= size; step++) {
            ProxyItem p = verifiedPool.get(((currentIndex + step) % size));
            if (p.equals(currentActiveProxy)) continue;
            if (p.isLocalDpi() && !localBypassUsable()) continue;
            return p;
        }
        return null;
    }

    /**
     * Fetch proxies from all configured sources and verify each one.
     */
    private static void fetchAndVerifyAllSources() {
        for (String sourceUrl : PROXY_SOURCES) {
            try {
                if (sourceUrl.endsWith(".json")) {
                    fetchProxiesJson(sourceUrl);
                } else if (sourceUrl.endsWith(".txt")) {
                    fetchProxiesTxt(sourceUrl);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Source fetch failed: " + sourceUrl, t);
            }
        }

        // Verdict on every candidate comes from the native protocol checker, not from this
        // method. It used to run a TCP connect here and store the result as "available", which
        // is how seven hardcoded nodes with unknown fate ended up reported as working proxies.
        mainHandler.post(() -> {
            checkPoolNow();
            publishPoolToStock();
        });
    }

    private static void fetchProxiesJson(String sourceUrl) {
        // Declared outside the try so the finally can reach it. A reference declared inside
        // the try block is not in scope in the finally, and the resulting compile error is
        // how this was caught - `if (conn != null)` is only meaningful for a hoisted variable
        // anyway, since a variable local to the try can never be null at that point.
        HttpURLConnection conn = null;
        try {
            URL url = new URL(sourceUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONArray arr = new JSONArray(sb.toString());
                int added = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String server = obj.optString("server", "");
                    int port = obj.optInt("port", 0);
                    String secret = obj.optString("secret", "");

                    if (!server.isEmpty() && port > 0 && secret.startsWith("ee")) {
                        ProxyItem item = new ProxyItem(server, port, secret, 1);
                        if (!containsProxy(item)) {
                            verifiedPool.add(item);
                            added++;
                        }
                    }
                    if (added >= 10) break; // Limit per source to keep pool lean
                }
                Log.d(TAG, "Fetched " + added + " new proxies from " + sourceUrl);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Fetch error from " + sourceUrl, t);
        } finally {
            // disconnect() must be in a finally: it is the only thing that returns the
            // socket to the pool. It used to sit on the success path, so every non-200
            // response (and every parse error) leaked its connection.
            if (conn != null) conn.disconnect();
        }
    }

    private static void fetchProxiesTxt(String sourceUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(sourceUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                Pattern pattern = Pattern.compile("server=([^&]+)&port=(\\d+)&secret=([^&\\s]+)");
                String line;
                int added = 0;
                while ((line = reader.readLine()) != null) {
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        String server = m.group(1).replaceAll("\\.$", "");
                        int port = Integer.parseInt(m.group(2));
                        String secret = m.group(3);

                        if (secret.startsWith("ee") || secret.startsWith("dd")) {
                            ProxyItem item = new ProxyItem(server, port, secret, 1);
                            if (!containsProxy(item)) {
                                verifiedPool.add(item);
                                added++;
                            }
                        }
                    }
                    if (added >= 10) break;
                }
                reader.close();
                Log.d(TAG, "Fetched " + added + " new proxies from " + sourceUrl);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Fetch error from " + sourceUrl, t);
        } finally {
            // disconnect() must be in a finally: it is the only thing that returns the
            // socket to the pool. It used to sit on the success path, so every non-200
            // response (and every parse error) leaked its connection.
            if (conn != null) conn.disconnect();
        }
    }

    private static int testProxy(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return (int) (System.currentTimeMillis() - start);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Apply proxy into Telegram's native layer, SharedPreferences, and SharedConfig.
     */
    public static void forceApplyProxy(ProxyItem proxy) {
        if (proxy == null) return;
        currentActiveProxy = proxy;
        if (proxy.isLocalDpi()) {
            // Start the grace window: the desync strategy prober needs several connections
            // to find a strategy that gets through, and rotating away before then defeats
            // it (see switchToNextProxy).
            localDpiAppliedAt = SystemClock.elapsedRealtime();
        }
        Log.d(TAG, "Applying proxy: " + proxy.address + ":" + proxy.port + " (type=" + proxy.type + ")");

        Context ctx = appContext;
        if (ctx == null) return;

        try {
            // 1. Persist proxy settings in SharedPreferences for every account slot.
            for (int a = 0; a < colgramAccountSlots; a++) {
                String prefName = a == 0 ? "mainconfig" : ("mainconfig" + a);
                SharedPreferences preferences = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                preferences.edit()
                        .putBoolean("proxy_enabled", true)
                        .putString("proxy_ip", proxy.address)
                        .putInt("proxy_port", proxy.port)
                        .putString("proxy_user", "")
                        .putString("proxy_pass", "")
                        .putString("proxy_secret", proxy.secret)
                        .putInt("proxy_type", proxy.type)
                        .apply();
            }

            // 2. Apply through Telegram's OWN entry point.
            //
            // This used to call native_setProxySettings directly by reflection for every
            // account. That skipped the whole Java-side method, and the Java side is where
            // two things live:
            //
            //   * ProxySettings.Type.WEB support. ConnectionsManager.setProxySettings starts
            //     WebProxyTransport, a local WebSocket-to-TCP bridge, and hands tgnet the
            //     loopback port it bound. A wss:// proxy is the one class of bypass that
            //     survives an IP-level block without a VPN and without a server of your own,
            //     because the traffic looks like ordinary HTTPS to a CDN. Bypassing the Java
            //     method made WEB unreachable from Colgram no matter what the user typed.
            //   * The per-account loop keyed off UserConfig.MAX_ACCOUNT_COUNT rather than our
            //     own constant, so the two paths could not disagree about how many slots exist.
            //
            // ProxySettings is rebuilt from the preferences written in step 1 using upstream's
            // own parser, so a proxy added through Telegram's stock screen and one added by
            // Colgram go through exactly the same code.
            boolean appliedThroughStockPath = false;
            try {
                Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");

                SharedPreferences primary = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
                Object settings = psClass.getMethod("fromSharedPreferences", SharedPreferences.class)
                        .invoke(null, primary);

                Method stockSet = cmClass.getMethod("setProxySettings", boolean.class, psClass);
                stockSet.invoke(null, true, settings);
                appliedThroughStockPath = true;
                Log.i(TAG, "proxy applied via ConnectionsManager.setProxySettings (type="
                        + proxy.type + ", " + proxy.address + ")");
            } catch (Throwable t) {
                Log.e(TAG, "stock setProxySettings failed, falling back to native", t);
            }

            if (!appliedThroughStockPath) {
                // Keep the previous behaviour as a last resort so a refactor upstream cannot
                // leave users with no way to set a proxy at all.
                try {
                    Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                    Method nativeSetProxy = cmClass.getDeclaredMethod("native_setProxySettings",
                            int.class, String.class, int.class, String.class, String.class, String.class);
                    nativeSetProxy.setAccessible(true);
                    for (int i = 0; i < colgramAccountSlots; i++) {
                        nativeSetProxy.invoke(null, i, proxy.address, proxy.port, "", "", proxy.secret);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "ConnectionsManager native_setProxySettings error", t);
                }
            }

            // 3. Make Telegram's own state agree with what was just applied.
            //
            // SharedConfig.isProxyEnabled() is "proxy_enabled && currentProxy != null", and the
            // drawer, the settings rows and the stock proxy screen all read it. loadProxyList()
            // only resolves a currentProxy when the applied node is already in the persisted
            // list, so a proxy chosen by Colgram left currentProxy null: traffic really did go
            // through the proxy while every stock surface said "Отключён". addProxy() is
            // upstream's de-duplicating, persisting insert, so go through it and point
            // currentProxy at the result.
            try {
                Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
                Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
                Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");

                Field pllField = scClass.getDeclaredField("proxyListLoaded");
                pllField.setAccessible(true);
                pllField.setBoolean(null, false);
                scClass.getDeclaredMethod("loadProxyList").invoke(null);

                Object settings = buildProxySettings(proxy);
                if (settings != null) {
                    java.lang.reflect.Constructor<?> piCtor = piClass.getConstructor(psClass);
                    Method addProxy = scClass.getDeclaredMethod("addProxy", piClass);
                    Object info = addProxy.invoke(null, piCtor.newInstance(settings));
                    if (info != null) {
                        Field cpField = scClass.getDeclaredField("currentProxy");
                        cpField.setAccessible(true);
                        cpField.set(null, info);
                    }
                }
                scClass.getDeclaredMethod("saveProxyList").invoke(null);
                enableStockRotation(appContext);
            } catch (Throwable t) {
                Log.e(TAG, "SharedConfig proxy state error", t);
            }

            // 4. Notify UI via NotificationCenter
            try {
                Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                Method getGlobalInstance = ncClass.getDeclaredMethod("getGlobalInstance");
                getGlobalInstance.setAccessible(true);
                Object globalNc = getGlobalInstance.invoke(null);

                Field proxySettingsChangedField = ncClass.getDeclaredField("proxySettingsChanged");
                proxySettingsChangedField.setAccessible(true);
                int proxySettingsChanged = proxySettingsChangedField.getInt(null);

                Method postNotificationName = ncClass.getDeclaredMethod("postNotificationName", int.class, Object[].class);
                postNotificationName.setAccessible(true);
                postNotificationName.invoke(globalNc, proxySettingsChanged, new Object[0]);
            } catch (Throwable ignored) {}

        } catch (Exception e) {
            Log.e(TAG, "forceApplyProxy error", e);
        }
    }

    public static boolean isProxyEnabled(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return false;
        SharedPreferences preferences = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        return preferences.getBoolean("proxy_enabled", false);
    }

    public static synchronized void toggleProxy(final Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        boolean currentlyEnabled = isProxyEnabled(ctx);
        if (currentlyEnabled) {
            disableProxy(ctx);
            toast("Прокси: выключен");
            return;
        }

        if (verifiedPool.isEmpty()) {
            initVerifiedPool();
        }
        SharedPreferences mainPrefs = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        boolean localOk = localBypassUsable();

        // What this used to do: apply verifiedPool.get(0). Entry zero is the in-process desync
        // listener, so tapping the proxy button pointed Telegram at 127.0.0.1:9876 even when
        // the bypass was switched off and nothing was listening. Telegram reported the proxy as
        // unreachable, the rotator moved off it, and the whole feature read as "прокси без впн
        // не подрубается". Pick something that can actually carry traffic instead.
        ProxyItem target = findSavedProxy(mainPrefs);
        if (target != null && target.isLocalDpi() && !localOk) {
            target = null;
        }
        if (target == null && currentActiveProxy != null
                && !(currentActiveProxy.isLocalDpi() && !localOk)) {
            target = currentActiveProxy;
        }
        if (target == null) {
            target = selectProxy(null);
        }

        if (target == null) {
            // Refusing to invent a proxy is better than applying a dead one. The stock screen
            // is where a proxy of his own - including a wss:// one, the only class that beats
            // an IP-level block without a VPN - gets entered.
            toast("Нет рабочих прокси. Добавьте свой: Настройки Colgram → Сеть → «Свой прокси»");
            Log.w(TAG, "toggleProxy: nothing usable to enable");
            return;
        }

        forceApplyProxy(target);
        toast("Прокси: включён — " + target);
    }

    /**
     * The proxy the user last selected, as Telegram itself persists it.
     *
     * Keys are the stock ones written by forceApplyProxy below and read by ProxySettings, so
     * a proxy added through Telegram's own proxy screen is honoured too, not just ours.
     * Returns null when nothing is stored or the stored entry is not in the pool.
     */
    private static ProxyItem findSavedProxy(SharedPreferences mainPrefs) {
        try {
            String ip = mainPrefs.getString("proxy_ip", "");
            int port = mainPrefs.getInt("proxy_port", 0);
            int type = mainPrefs.getInt("proxy_type", -1);
            String secret = mainPrefs.getString("proxy_secret", "");
            if (ip == null || ip.isEmpty()) return null;
            // A WEB proxy has no port at all - ProxySettings forces it to 0 and tgnet is given
            // the loopback port that WebProxyTransport bound instead. Rejecting port <= 0 here
            // would silently discard exactly the proxy type that beats an IP block.
            if (type != 2 && port <= 0) return null;
            for (ProxyItem p : verifiedPool) {
                if (p.address.equals(ip) && p.port == port && p.type == type) return p;
            }
            // Not in the pool (e.g. a user-entered private proxy, which has no reason to be
            // there). Honour the choice anyway rather than quietly substituting something else.
            ProxyItem custom = new ProxyItem(ip, port, secret, type);
            custom.isAvailable = false;
            custom.pingMs = -1;
            Log.i(TAG, "saved proxy is not in the pool; applying it as a user-configured entry");
            return custom;
        } catch (Throwable t) {
            Log.w(TAG, "findSavedProxy failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Single place that turns the desync bypass on or off, for real.
     *
     * The settings row used to only call ColgramDpiBypass.start()/stop(). That started or
     * killed a listener nobody was necessarily routed through, persisted nothing so the
     * choice reverted on the next launch, and left the UI showing one state while tgnet was
     * pointed at another. Persisting, running the listener and routing traffic through it are
     * three different things and all three have to agree.
     */
    public static void setDpiBypassEnabled(Context context, boolean enabled) {
        ColgramConfig.setDpiBypassEnabled(enabled);
        if (enabled) {
            // Bind first, route second. The old order force-applied 127.0.0.1:9876 the moment
            // start() was called, but start() defers the bind - so Telegram was pointed at a
            // port that did not exist yet, reported the proxy as unavailable, and the rotator
            // moved off it. That is the "я включаю обходчик и ничего не происходит" path.
            ColgramDpiBypass.startImmediately();
            executor.execute(() -> {
                boolean ready = ColgramDpiBypass.awaitReady(20000);
                ProxyItem local = findLocalDpiProxy();
                if (!ready || local == null) {
                    Log.e(TAG, "desync bypass enabled but the listener never bound; leaving traffic alone");
                    mainHandler.post(() -> toast("Обходчик не смог поднять 127.0.0.1:"
                            + ColgramDpiBypass.LOCAL_PORT));
                    return;
                }
                forceApplyProxy(local);
                checkPoolNow();
                toast("Обходчик ТСПУ включён: " + local);
            });
        } else {
            disableProxy(context);
            ColgramDpiBypass.stop();
            Log.i(TAG, "desync bypass disabled, reverted to direct connection");
        }
    }

    private static void toast(final CharSequence text) {
        // Toast needs a Looper; callers come from the pool checker's worker thread as well as
        // from the UI, so the hop belongs here rather than at every call site.
        mainHandler.post(() -> {
            Context ctx = appContext;
            if (ctx == null) return;
            try {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    public static void disableProxy(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        try {
            for (int a = 0; a < colgramAccountSlots; a++) {
                String prefName = a == 0 ? "mainconfig" : ("mainconfig" + a);
                SharedPreferences preferences = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                preferences.edit().putBoolean("proxy_enabled", false).apply();
            }

            // Through Telegram's own entry point, not just the native call: a WEB proxy is
            // served by WebProxyTransport, a WebView bridge holding a local port, and only
            // ConnectionsManager.setProxySettings stops it. Disabling by the native call alone
            // left that bridge running behind a proxy the user believes is off.
            try {
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Method stockSet = cmClass.getMethod("setProxySettings", boolean.class,
                        Class.forName("org.telegram.proxy.ProxySettings"));
                stockSet.invoke(null, false, null);
            } catch (Throwable t) {
                Log.e(TAG, "disableProxy setProxySettings error", t);
                try {
                    Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                    Method nativeSetProxy = cmClass.getDeclaredMethod("native_setProxySettings",
                            int.class, String.class, int.class, String.class, String.class, String.class);
                    nativeSetProxy.setAccessible(true);
                    for (int i = 0; i < colgramAccountSlots; i++) {
                        nativeSetProxy.invoke(null, i, "", 0, "", "", "");
                    }
                } catch (Throwable t2) {
                    Log.e(TAG, "disableProxy nativeSetProxy error", t2);
                }
            }

            try {
                Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
                try {
                    Field currentProxyField = scClass.getDeclaredField("currentProxy");
                    currentProxyField.setAccessible(true);
                    currentProxyField.set(null, null);
                } catch (Throwable ignored) {}

                Method loadProxyListMethod = scClass.getDeclaredMethod("loadProxyList");
                loadProxyListMethod.setAccessible(true);
                loadProxyListMethod.invoke(null);
            } catch (Throwable ignored) {}

            try {
                Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                Method getGlobalInstance = ncClass.getDeclaredMethod("getGlobalInstance");
                getGlobalInstance.setAccessible(true);
                Object globalNc = getGlobalInstance.invoke(null);

                Field proxySettingsChangedField = ncClass.getDeclaredField("proxySettingsChanged");
                proxySettingsChangedField.setAccessible(true);
                int proxySettingsChanged = proxySettingsChangedField.getInt(null);

                Method postNotificationName = ncClass.getDeclaredMethod("postNotificationName", int.class, Object[].class);
                postNotificationName.setAccessible(true);
                postNotificationName.invoke(globalNc, proxySettingsChanged, new Object[0]);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "disableProxy error", t);
        }
    }

    /**
     * Publish Colgram's candidate proxies into Telegram's OWN persisted list.
     *
     * This used to inject ProxyInfo objects into the in-memory SharedConfig.proxyList only.
     * Two consequences made that useless: nothing was ever written to the "proxy_list"
     * preference, so the entries disappeared on the next start; and forceApplyProxy() calls
     * SharedConfig.loadProxyList(), which CLEARS proxyList and rebuilds it from that
     * preference - so Colgram's entries were wiped the first time it ran, and the stock
     * screen, the stock rotator and the drawer all saw an empty list. Going through
     * SharedConfig.addProxy() means the entries persist, de-duplicate, and are visible to
     * everything upstream.
     *
     * It also arms upstream's ProxyRotationController. That class checks every entry with the
     * native protocol checker and switches to the lowest-ping working one the moment Telegram
     * stalls in ConnectionStateConnectingToProxy - the failover Colgram reimplemented badly in
     * Java. It is gated on SharedConfig.proxyRotationEnabled, which defaults to false and
     * nothing here ever set, so until now the real rotator never ran at all.
     */
    private static final int MAX_PUBLISHED_TO_STOCK = 8;

    private static void publishPoolToStock() {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
            Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");

            scClass.getDeclaredMethod("loadProxyList").invoke(null);

            java.lang.reflect.Constructor<?> piCtor = piClass.getConstructor(psClass);
            Method addProxy = scClass.getDeclaredMethod("addProxy", piClass);

            if (verifiedPool.isEmpty()) {
                initVerifiedPool();
            }
            int published = 0;
            for (ProxyItem p : verifiedPool) {
                if (published >= MAX_PUBLISHED_TO_STOCK) break;
                // Do not offer a listener the user switched off as if it were a proxy option.
                if (p.isLocalDpi() && !ColgramConfig.isDpiBypassEnabled()) continue;
                Object settings = buildProxySettings(p);
                if (settings == null) continue;
                addProxy.invoke(null, piCtor.newInstance(settings));
                published++;
            }

            enableStockRotation(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "publishPoolToStock error", t);
        }
    }

    private static void enableStockRotation(Context ctx) {
        try {
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Field enabled = scClass.getDeclaredField("proxyRotationEnabled");
            enabled.setAccessible(true);
            if (enabled.getBoolean(null)) return;
            enabled.setBoolean(null, true);
            // SharedConfig.loadConfig() reads this key from the "userconfing" file - that
            // spelling is upstream's, not a typo of mine - not from mainconfig.
            ctx.getSharedPreferences("userconfing", Context.MODE_PRIVATE).edit()
                    .putBoolean("proxyRotationEnabled", true).apply();
            Log.i(TAG, "Telegram's own proxy checker and rotator enabled");
        } catch (Throwable t) {
            Log.w(TAG, "could not enable stock proxy rotation: " + t.getMessage());
        }
    }

    /**
     * Entry point kept for the patched call sites (login screen, proxy list screen).
     */
    public static void populateSharedConfigProxies() {
        if (verifiedPool.isEmpty()) {
            initVerifiedPool();
        }
        mainHandler.post(() -> publishPoolToStock());
    }

    private static boolean containsProxy(ProxyItem item) {
        for (ProxyItem p : verifiedPool) {
            if (p.address.equals(item.address) && p.port == item.port) return true;
        }
        return false;
    }

    public static ProxyItem getCurrentActiveProxy() { return currentActiveProxy; }
    public static List<ProxyItem> getVerifiedPool() { return new ArrayList<>(verifiedPool); }

    /** Entries the native protocol checker actually confirmed working. */
    public static int getAliveCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.isAvailable) c++;
        return c;
    }

    /** Entries that have been checked and failed. */
    public static int getDeadCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.pingMs == -2) c++;
        return c;
    }

    /** Entries no verdict exists for yet. */
    public static int getUncheckedCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.pingMs == -1) c++;
        return c;
    }

    /** Human-readable state of one ping result. */
    public static String describePing(ProxyItem item) {
        if (item == null) return "нет";
        if (item.pingMs == -1) return "не проверен";
        if (item.pingMs == -2) return "не отвечает";
        return item.pingMs + " мс";
    }
}
