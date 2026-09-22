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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
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
        /** Times the protocol probe said "dead". Enough of these and the entry leaves the pool. */
        int failedVerdicts = 0;
        /** Fake-TLS (an "ee" secret): the handshake is wrapped in a plausible TLS record. */
        public final boolean fakeTls;
        /** Last plain TCP connect result: -1 unknown, -2 unreachable, else RTT ms. */
        public volatile int tcpMs = -1;
        /** elapsedRealtime of the last TCP-level check. */
        public volatile long tcpCheckedAt = 0L;
        /** Set when a native MTProto/SOCKS handshake succeeded at least once. */
        public volatile boolean nativeVerified = false;
        /**
         * Relay that can reach this node when a direct socket cannot. The block on this
         * network is per-IP, so plenty of perfectly good proxies are simply unreachable from
         * here; a relay that is reachable turns them back on through a loopback forwarder.
         */
        public volatile ColgramProxyChain.Relay chainRelay = null;
        /** Loopback port of an open chain forwarder, when this node is applied through a relay. */
        public volatile int chainPort = 0;

        public String effectiveHost() {
            return chainPort > 0 ? "127.0.0.1" : address;
        }

        public int effectivePort() {
            return chainPort > 0 ? chainPort : port;
        }

        public ProxyItem(String address, int port, String secret, int type) {
            this.address = address;
            this.port = port;
            this.secret = secret != null ? secret : "";
            this.type = type;
            this.fakeTls = this.secret.toLowerCase().startsWith("ee");
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
    /** Reachable tunnels used only to chain to blocked pool entries. Never shown as proxies. */
    private static final List<ColgramProxyChain.Relay> relayPool = Collections.synchronizedList(new ArrayList<>());
    /**
     * Parallel TCP sweep. The old prober walked the pool one handshake at a time with a 3 s gap,
     * so a 200-entry pool needed ten minutes to report anything - the exact complaint that the
     * check "takes forever while other sites check in a second". Third-party checkers are fast
     * because they fan out; this does the same, and only the survivors earn a protocol handshake.
     */
    private static final ExecutorService sweeper = Executors.newFixedThreadPool(24);
    private static final AtomicBoolean sweeping = new AtomicBoolean(false);
    private static volatile ProxyItem currentActiveProxy = null;
    private static volatile Context appContext = null;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Multiple GitHub sources for fresh proxies. Every URL here was fetched and parsed before it
    // was added; the two that are commented out were tested and are dead or 404, and leaving dead
    // sources in the list is how a fetch silently "succeeds" while returning nothing.
    //
    // Formats:
    //   .json  -> [{server, port, secret}, ...]
    //   .txt   -> t.me/proxy?server=..&port=..&secret=.. link lines
    //   socks  -> plain host:port lines (no secret; type SOCKS5)
    private static final String[] PROXY_SOURCES_MTPROTO_JSON = {
        "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/proxies.json",
    };
    private static final String[] PROXY_SOURCES_MTPROTO_LINKS = {
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
        "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
    };
    private static final String[] PROXY_SOURCES_SOCKS = {
        "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
    };

    /**
     * Relay lists. These are NOT Telegram proxies and never enter the pool or the stock proxy
     * screen: they are tunnels. An HTTP CONNECT proxy that answers here and can reach a blocked
     * MTProxy turns that MTProxy back on, which is the only way to use a node whose IP is on the
     * same blocklist as Telegram's own.
     */
    private static final String[] RELAY_SOURCES_HTTP = {
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
    };
    private static final String[] RELAY_SOURCES_SOCKS = {
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
    };
    private static final int MAX_RELAYS = 60;

    /** Hard ceiling on the candidate list; the harvest replaces the oldest unverified entries. */
    private static final int MAX_POOL_SIZE = 200;
    /** How many entries the stock proxy list (and Telegram's own rotator) gets to see. */
    private static final int MAX_PUBLISHED_TO_STOCK = 12;
    /** Entries verified alive and kept across restarts. */
    private static final int MAX_REMEMBERED_ALIVE = 24;

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

        // Reachability on a filtered network changes on the scale of minutes, and a stale "alive"
        // is exactly why a chosen proxy silently stops working. Re-sweep every 5 minutes; the
        // sweep is 24 parallel 1.2 s connects, so the whole pool costs a couple of seconds.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                // On a low, unplugged battery the sweep waits: 200 parallel connects keep the
                // radio at full power for seconds, and a proxy verdict is not worth that.
                if (ColgramPowerGuard.shouldThrottle(appContext)) return;
                if (ColgramConfig.isBuiltinProxyEnabled()) sweepFast(null);
            } catch (Throwable t) {
                Log.w(TAG, "periodic sweep failed", t);
            }
        }, 60000, 300000, java.util.concurrent.TimeUnit.MILLISECONDS);

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

                // A REMOTE proxy is not checked here any more. The prober already re-verifies
                // every entry - including the applied one - inside a two-minute window and
                // rotates on its verdict, and running a second handshake against the same node
                // from two timers is what made live proxies answer "dead".
            } catch (Throwable ignored) {}
        }, 30, 30, TimeUnit.SECONDS);

        // 6. Re-harvest every 20 minutes. Public MTProxy lists churn on that timescale - nodes
        // disappear and new ones appear - and the prober keeps the verdicts fresh in between.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                fetchAndVerifyAllSources();
            } catch (Throwable ignored) {}
        }, 20, 20, TimeUnit.MINUTES);
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
        // Availability now comes from the protocol probe (see startProber).
        for (ProxyItem p : hardcoded) {
            if (!containsProxy(p)) {
                verifiedPool.add(p);
            }
        }

        // Nodes that were verified working on a previous run, with a deliberately stale verdict
        // so the prober re-checks them first thing rather than trusting them blind.
        loadRemembered();
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
    /** Gap between two probes: ~20 handshakes a minute, one in flight at a time. */
    private static final long PROBE_GAP_MS = 3000L;
    /** Idle wait when every entry in the pool is still fresh. */
    private static final long PROBE_IDLE_MS = 20000L;
    /** Failed verdicts after which a node leaves the pool until the next harvest brings it back. */
    private static final int MAX_FAILED_VERDICTS = 3;

    private static final AtomicBoolean proberRunning = new AtomicBoolean(false);
    private static int proberCursor = 0;

    /**
     * Continuous single-flight protocol probe.
     *
     * A capped sweep cannot cover a pool this size: with a few hundred candidates and eight
     * checks per pass, most entries never get a verdict at all, which is exactly why the settings
     * screen kept showing "10 proxies" worth of information. The prober instead keeps one
     * handshake in flight at a steady pace, so a few hundred entries are re-verified every few
     * minutes and a node that died is noticed without a burst of connections.
     */
    public static void startProber() {
        if (appContext == null || !proberRunning.compareAndSet(false, true)) return;
        mainHandler.post(proberTick);
    }

    /** Manual "check now": a parallel TCP sweep first, then protocol handshakes on survivors. */
    public static void checkPoolNow() {
        sweepFast(null);
    }

    /** Kept for the settings row; a manual tap always means "walk the list again". */
    public static void checkPoolNow(boolean force) {
        checkPoolNow();
    }

    /**
     * Parallel reachability sweep: every pool entry gets a plain TCP connect on its own thread
     * (1.2 s budget), and every entry the blocklist hides gets one attempt through a relay.
     * 24 threads over a 200-entry pool finishes in single-digit seconds, which is what the pool
     * screen needs to show honest numbers instead of a ten-minute crawl.
     *
     * TCP-alive is not protocol-alive: the prober still hands the survivors to Telegram's native
     * checkProxy afterwards, and only a native verdict sets {@code isAvailable}.
     */
    public static void sweepFast(final Runnable onDone) {
        if (appContext == null) return;
        if (!sweeping.compareAndSet(false, true)) return;
        final List<ProxyItem> snapshot = new ArrayList<>(verifiedPool);
        final List<ColgramProxyChain.Relay> relaySnapshot = new ArrayList<>(relayPool);
        if (snapshot.isEmpty()) {
            sweeping.set(false);
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(snapshot.size());
        final java.util.concurrent.atomic.AtomicInteger doneCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        for (final ProxyItem item : snapshot) {
            if (item.type == 2 || item.isLocalDpi()) {
                if (remaining.decrementAndGet() == 0) finishSweep(onDone);
                continue;
            }
            sweeper.execute(() -> {
                try {
                    int rtt = testProxy(item.address, item.port, 1200);
                    if (rtt >= 0) {
                        item.tcpMs = rtt;
                        item.chainRelay = null;
                        item.chainPort = 0;
                    } else {
                        item.tcpMs = -2;
                        ColgramProxyChain.Relay relay = pickRelay(relaySnapshot, item);
                        if (relay != null) {
                            item.chainRelay = relay;
                            item.tcpMs = relay.rttMs;
                        }
                    }
                    item.tcpCheckedAt = SystemClock.elapsedRealtime();
                    int finished = doneCount.incrementAndGet();
                    if (finished % 10 == 0) postSweepProgress();
                } catch (Throwable ignored) {
                } finally {
                    if (remaining.decrementAndGet() == 0) finishSweep(onDone);
                }
            });
        }
    }

    private static void finishSweep(final Runnable onDone) {
        sweeping.set(false);
        postSweepProgress();
        // Survivors earn a protocol handshake; the rest of the pool waits for the next sweep.
        startProber();
        autoConnectIfBlocked();
        if (onDone != null) mainHandler.post(onDone);
    }

    /** Production Telegram endpoints, used only to answer "is Telegram reachable at all". */
    private static final String[][] TELEGRAM_DC_ENDPOINTS = {
            {"149.154.175.50", "443"},
            {"149.154.167.50", "443"},
            {"91.108.56.100", "443"},
            {"185.76.151.1", "443"},
    };

    private static boolean telegramDirectlyReachable() {
        for (String[] endpoint : TELEGRAM_DC_ENDPOINTS) {
            try {
                if (testProxy(endpoint[0], Integer.parseInt(endpoint[1]), 1200) >= 0) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * The bypass actually bypassing: when every Telegram endpoint is unreachable and no proxy is
     * applied, connect through the best node the protocol check confirmed. Without this the pool
     * could hold a working proxy while the app sat on "Соединение..." forever - which is exactly
     * what "the bypass finds proxies but never connects to them" meant. It only ever fires when
     * the direct route is dead, so on an open network nothing changes.
     */
    private static void autoConnectIfBlocked() {
        Context ctx = appContext;
        if (ctx == null || !ColgramConfig.isBuiltinProxyEnabled()) return;
        if (isProxyEnabled(ctx)) return;
        if (telegramDirectlyReachable()) return;
        ProxyItem best = null;
        for (ProxyItem p : verifiedPool) {
            if (!p.nativeVerified || p.isLocalDpi() || p.type == 2) continue;
            if (best == null || betterCandidate(p, best)) best = p;
        }
        if (best == null) return;
        final ProxyItem chosen = best;
        Log.i(TAG, "Telegram unreachable directly; auto-connecting through "
                + chosen.address + ":" + chosen.port);
        mainHandler.post(() -> forceApplyProxy(chosen));
    }

    /** Live UI refresh while the sweep runs, so the pool screen counts up instead of freezing. */
    private static void postSweepProgress() {
        mainHandler.post(() -> {
            try {
                Class<?> ncClass = Class.forName("org.colgram.messenger.NotificationCenter");
                Object nc = ncClass.getMethod("getGlobalInstance").invoke(null);
                int id = ncClass.getField("proxySettingsChanged").getInt(null);
                ncClass.getMethod("postNotificationName", int.class, Object[].class)
                        .invoke(nc, id, new Object[0]);
            } catch (Throwable ignored) {
            }
        });
    }

    /** Best relay for a blocked entry: alive, not dead, lowest measured RTT. */
    private static ColgramProxyChain.Relay pickRelay(List<ColgramProxyChain.Relay> relays, ProxyItem item) {
        ColgramProxyChain.Relay best = null;
        synchronized (relays) {
            for (ColgramProxyChain.Relay relay : relays) {
                if (relay.dead || relay.rttMs < 0) continue;
                if (best == null || relay.rttMs < best.rttMs) best = relay;
            }
        }
        if (best == null) return null;
        int rtt = ColgramProxyChain.probe(best, item.address, item.port, 2500);
        return rtt >= 0 ? best : null;
    }

    private static final Runnable proberTick = new Runnable() {
        @Override
        public void run() {
            final ProxyItem target = nextProberTarget();
            if (target == null) {
                mainHandler.postDelayed(this, PROBE_IDLE_MS * ColgramPowerGuard.intervalFactor(appContext));
                return;
            }
            final long startedAt = SystemClock.elapsedRealtime();
            final AtomicBoolean done = new AtomicBoolean(false);
            Runnable finish = () -> {
                if (!done.compareAndSet(false, true)) return;
                if (target.lastCheckAt < startedAt) {
                    // The native callback never fired: TCP opened and nothing answered. That is a
                    // dead proxy, not an unknown one.
                    onVerdict(target, false);
                }
                mainHandler.postDelayed(this, PROBE_GAP_MS * ColgramPowerGuard.intervalFactor(appContext));
            };
            boolean started;
            try {
                started = checkOne(target, alive -> {
                    onVerdict(target, alive);
                    finish.run();
                });
            } catch (Throwable t) {
                started = false;
            }
            if (!started) {
                target.lastCheckAt = startedAt;
                finish.run();
                return;
            }
            mainHandler.postDelayed(finish, NATIVE_CHECK_TIMEOUT_MS);
        }
    };

    /**
     * Next entry that deserves a protocol handshake; null when nothing does.
     *
     * The sweep's TCP verdict orders this: a node that answers TCP but has no protocol verdict
     * yet goes first, because that is exactly the set the user sees as "found working proxies"
     * and expects to be connectable. Stale protocol verdicts come second, everything else waits.
     */
    private static ProxyItem nextProberTarget() {
        final long now = SystemClock.elapsedRealtime();
        final int size = verifiedPool.size();
        if (size == 0) return null;
        ProxyItem tcpAlive = null;
        ProxyItem stale = null;
        for (int step = 0; step < size; step++) {
            int idx = (proberCursor + step) % size;
            ProxyItem p = verifiedPool.get(idx);
            // A WEB entry needs a WebView bridge per check and the pool never holds one; the
            // user's own WEB proxy is exercised where it is actually applied.
            if (p.type == 2 || p.isLocalDpi()) continue;
            boolean fresh = p.lastCheckAt > 0 && now - p.lastCheckAt < RECHECK_INTERVAL_MS;
            if (p.tcpMs >= 0 && !fresh) {
                tcpAlive = p;
                proberCursor = (idx + 1) % size;
                break;
            }
            if (stale == null && !fresh) stale = p;
        }
        ProxyItem target = tcpAlive != null ? tcpAlive : stale;
        if (target != null && tcpAlive == null) {
            proberCursor = (verifiedPool.indexOf(target) + 1) % Math.max(1, size);
        }
        return target;
    }

    /**
     * One verdict, applied everywhere it matters: the item's own state, the pool's size, and the
     * rotation decision when the node that just failed is the one carrying traffic.
     */
    private static void onVerdict(ProxyItem item, boolean alive) {
        item.lastCheckAt = SystemClock.elapsedRealtime();
        if (alive) {
            item.isAvailable = true;
            item.nativeVerified = true;
            item.nativeFailures = 0;
            item.failedVerdicts = 0;
            rememberAlive();
            // A proven node is worth connecting through right now when the direct route is
            // dead; waiting for the next scheduled sweep left the app on "connecting" for
            // minutes with a working proxy sitting in the pool. Off-main: the reachability
            // test inside opens sockets.
            executor.execute(() -> autoConnectIfBlocked());
        } else {
            item.nativeVerified = false;
            item.failedVerdicts++;
            if (item.failedVerdicts >= MAX_FAILED_VERDICTS) {
                item.isAvailable = false;
                item.pingMs = -2;
            }
        }
        Log.d(TAG, "native check " + item.address + ":" + item.port + " -> "
                + (alive ? item.pingMs + "ms" : "dead (" + item.failedVerdicts + ")"));
        if (item == currentActiveProxy && !alive) {
            reportProxyFailure();
            switchToNextProxy();
        }
        prunePool();
    }

    /**
     * Drop nodes that have failed enough times to stop being interesting. The user's own applied
     * proxy is never dropped, and the harvest re-adds anything the lists still advertise.
     */
    private static void prunePool() {
        for (int i = verifiedPool.size() - 1; i >= 0; i--) {
            ProxyItem p = verifiedPool.get(i);
            if (p == currentActiveProxy || p.isLocalDpi()) continue;
            if (p.failedVerdicts >= MAX_FAILED_VERDICTS) {
                verifiedPool.remove(i);
                if (proberCursor > 0) proberCursor--;
            }
        }
    }

    /**
     * Verified-alive nodes are remembered across restarts. Without this every launch starts
     * blind: the pool is re-seeded from lists, nothing has a verdict yet, and the first thing the
     * user does - turn the proxy on - lands on an unverified host.
     */
    private static final String PROXY_PREFS = "colgram_proxies";
    private static final String KEY_ALIVE = "alive_nodes";

    private static void rememberAlive() {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            int saved = 0;
            for (ProxyItem p : verifiedPool) {
                if (!p.isAvailable || p.isLocalDpi() || p.type == 2) continue;
                if (saved++ > 0) sb.append('\n');
                sb.append(p.type).append('|').append(p.address).append('|').append(p.port)
                        .append('|').append(p.secret).append('|').append(p.pingMs);
                if (saved >= MAX_REMEMBERED_ALIVE) break;
            }
            ctx.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_ALIVE, sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static void loadRemembered() {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            String blob = ctx.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_ALIVE, "");
            if (blob == null || blob.isEmpty()) return;
            long staleBefore = SystemClock.elapsedRealtime() - REMEMBERED_VALID_MS;
            for (String line : blob.split("\n")) {
                String[] parts = line.split("\\|", 5);
                if (parts.length < 4) continue;
                ProxyItem p;
                try {
                    p = new ProxyItem(parts[1], Integer.parseInt(parts[2]), parts[3],
                            Integer.parseInt(parts[0]));
                } catch (Throwable e) {
                    continue;
                }
                if (parts.length == 5) {
                    try {
                        p.pingMs = Integer.parseInt(parts[4]);
                    } catch (Throwable ignored) {}
                }
                // Still marked alive, but the verdict is treated as old so the prober re-checks
                // it early rather than trusting a node that may have died overnight.
                p.isAvailable = true;
                p.lastCheckAt = staleBefore;
                addCandidate(p);
            }
        } catch (Throwable ignored) {}
    }

    /** How long a remembered verdict is trusted before the prober re-checks it. */
    private static final long REMEMBERED_VALID_MS = 60000L;

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
                                // Only the measurement belongs here; onVerdict owns the state it
                                // feeds, so a verdict cannot be recorded one way by the probe and
                                // another way by the caller.
                                item.pingMs = time < 0 ? -2 : (int) Math.max(1L, time);
                                onResult.onResult(time >= 0);
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
            bClass.getDeclaredMethod("setAddress", String.class).invoke(builder, item.effectiveHost());
            bClass.getDeclaredMethod("setPort", int.class).invoke(builder, item.effectivePort());
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
     * Best pool entry to apply right now: a verified-alive node, preferring fake-TLS and then
     * the lowest measured latency, and only then something never probed. Returns null when the
     * pool has nothing better to offer than what is applied already.
     *
     * It used to return the first alive entry in insertion order, which on a pool this size means
     * "whichever host happened to be added first", not "the fastest one that works".
     */
    private static ProxyItem selectProxy(ProxyItem skip) {
        ProxyItem best = null;
        ProxyItem unchecked = null;
        for (int i = 0; i < verifiedPool.size(); i++) {
            ProxyItem p = verifiedPool.get(i);
            if (p.equals(skip)) continue;
            if (p.isLocalDpi() && !localBypassUsable()) continue;
            if (p.isAvailable) {
                if (best == null || betterCandidate(p, best)) best = p;
            } else if (unchecked == null && p.pingMs == -1) {
                unchecked = p;
            }
        }
        return best != null ? best : unchecked;
    }

    /**
     * Native-verified first, then fake-TLS, then lower latency. A node that has actually completed
     * a protocol handshake beats one that merely accepted a socket: "found a working proxy but
     * never connects" was the rotator offering TCP-open nodes that had never spoken MTProto.
     */
    private static boolean betterCandidate(ProxyItem a, ProxyItem b) {
        if (a.nativeVerified != b.nativeVerified) return a.nativeVerified;
        if (a.fakeTls != b.fakeTls) return a.fakeTls;
        if (a.pingMs < 0) return false;
        if (b.pingMs < 0) return true;
        return a.pingMs < b.pingMs;
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
     * Pull every configured source into the candidate list.
     *
     * "And verify each one" used to be in this name and did not happen: the method ran a TCP
     * connect and stored the result as "available", which is how nodes that accept a socket and
     * have never spoken MTProto ended up reported as working proxies. Verification is the
     * prober's job now; this only grows the list, and the prober walks it continuously.
     */
    private static void fetchAndVerifyAllSources() {
        final int before = verifiedPool.size();
        for (String url : PROXY_SOURCES_MTPROTO_JSON) {
            try {
                fetchProxiesJson(url);
            } catch (Throwable t) {
                Log.w(TAG, "source failed: " + url + " (" + t.getMessage() + ")");
            }
        }
        for (String url : PROXY_SOURCES_MTPROTO_LINKS) {
            try {
                fetchProxiesLinkList(url, 150);
            } catch (Throwable t) {
                Log.w(TAG, "source failed: " + url + " (" + t.getMessage() + ")");
            }
        }
        for (String url : PROXY_SOURCES_SOCKS) {
            try {
                fetchSocksList(url, 150);
            } catch (Throwable t) {
                Log.w(TAG, "source failed: " + url + " (" + t.getMessage() + ")");
            }
        }
        harvestRelays();
        Log.i(TAG, "harvest: " + before + " -> " + verifiedPool.size() + " candidates, "
                + relayPool.size() + " relays");
        mainHandler.post(() -> {
            startProber();
            publishPoolToStock();
            // The first sweep right after the harvest: TCP verdicts for the whole pool in a
            // few seconds, and the ordering the prober needs to spend its handshakes on nodes
            // that actually answer.
            sweepFast(null);
        });
    }

    /**
     * Pull tunnel lists and keep the ones that answer from here. A relay is only useful if this
     * machine can open a socket to it, so each candidate gets one 1.2 s TCP check before it is
     * kept; whether it can then reach a blocked node is discovered per chain, in pickRelay.
     */
    private static void harvestRelays() {
        final List<ColgramProxyChain.Relay> found = new ArrayList<>();
        for (String url : RELAY_SOURCES_HTTP) {
            try {
                collectRelays(httpGet(url), false, found);
            } catch (Throwable t) {
                Log.w(TAG, "relay source failed: " + url + " (" + t.getMessage() + ")");
            }
        }
        for (String url : RELAY_SOURCES_SOCKS) {
            try {
                collectRelays(httpGet(url), true, found);
            } catch (Throwable t) {
                Log.w(TAG, "relay source failed: " + url + " (" + t.getMessage() + ")");
            }
        }
        synchronized (relayPool) {
            for (ColgramProxyChain.Relay relay : found) {
                if (relayPool.size() >= MAX_RELAYS) break;
                if (!relayPool.contains(relay)) relayPool.add(relay);
            }
            for (int i = relayPool.size() - 1; i >= 0; i--) {
                if (relayPool.get(i).dead) relayPool.remove(i);
            }
        }
        final List<ColgramProxyChain.Relay> snapshot = new ArrayList<>(relayPool);
        for (final ColgramProxyChain.Relay relay : snapshot) {
            if (relay.rttMs >= 0) continue;
            sweeper.execute(() -> {
                int rtt = testProxy(relay.host, relay.port, 1200);
                relay.rttMs = rtt;
                relay.dead = rtt < 0;
            });
        }
    }

    /** Parses host:port lines out of a relay list. */
    private static void collectRelays(String body, boolean socks, List<ColgramProxyChain.Relay> out) {
        if (body == null) return;
        for (String line : body.split("\\r?\\n")) {
            line = line.trim();
            int colon = line.lastIndexOf(':');
            if (colon <= 0 || colon == line.length() - 1) continue;
            String host = line.substring(0, colon);
            if (host.isEmpty() || host.indexOf(' ') >= 0) continue;
            try {
                int port = Integer.parseInt(line.substring(colon + 1));
                if (port > 0 && port < 65536) out.add(new ColgramProxyChain.Relay(host, port, socks));
            } catch (NumberFormatException ignored) {
            }
            if (out.size() >= MAX_RELAYS * 2) return;
        }
    }

    /**
     * Insert one candidate, keeping the pool inside its ceiling.
     *
     * Eviction prefers a node that has already failed over one that has never been probed, so a
     * long list of unverified hosts cannot push out the few that actually work.
     */
    private static boolean addCandidate(ProxyItem item) {
        if (item == null || item.address == null || item.address.isEmpty()) return false;
        if (item.type != 2 && item.port <= 0) return false;
        synchronized (verifiedPool) {
            if (containsProxy(item)) return false;
            if (verifiedPool.size() >= MAX_POOL_SIZE) {
                int victim = -1;
                for (int i = verifiedPool.size() - 1; i >= 0; i--) {
                    ProxyItem p = verifiedPool.get(i);
                    if (p == currentActiveProxy || p.isLocalDpi()) continue;
                    if (p.isAvailable) continue;
                    if (victim < 0 || p.failedVerdicts > verifiedPool.get(victim).failedVerdicts) {
                        victim = i;
                    }
                }
                if (victim < 0) return false;
                verifiedPool.remove(victim);
                if (proberCursor > victim) proberCursor--;
            }
            verifiedPool.add(item);
        }
        return true;
    }

    /** Plain HTTP GET as a string, with the connection always returned. */
    private static String httpGet(String sourceUrl) throws Exception {
        // ColgramHttp rather than a bare connection: on a network that drops the source's IP
        // there is nothing to fetch directly, and a harvest that silently returns nothing is
        // indistinguishable from an empty list. This one retries through the SOCKS5 relays
        // already in the pool and names every attempt it made.
        ColgramHttp.Response r = ColgramHttp.get(sourceUrl, null);
        if (r.code < 200 || r.code >= 300) return "";
        return r.body;
    }

    /** t.me/proxy?server=..&port=..&secret=.. link lists. */
    private static void fetchProxiesLinkList(String sourceUrl, int limit) {
        String body = null;
        try {
            body = httpGet(sourceUrl);
        } catch (Throwable t) {
            Log.w(TAG, "link list fetch failed: " + sourceUrl, t);
            return;
        }
        Pattern pattern = Pattern.compile("server=([^&\\s]+)&port=(\\d+)&secret=([^&\\s]+)");
        Matcher m = pattern.matcher(body);
        int added = 0;
        while (m.find() && added < limit) {
            String server = m.group(1).replaceAll("\\.$", "");
            int port;
            try {
                port = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            String secret = m.group(3);
            String head = secret.length() >= 2 ? secret.substring(0, 2).toLowerCase() : "";
            // "ee" is fake-TLS, "dd" is plain obfuscation. Anything else is not an MTProxy
            // secret and would be applied as a broken one.
            if (!head.equals("ee") && !head.equals("dd")) continue;
            if (addCandidate(new ProxyItem(server, port, secret, 1))) added++;
        }
        Log.i(TAG, "link source " + sourceUrl + " added " + added);
    }

    /** Public SOCKS5 host:port lists. Telegram's DCs are reachable from the proxy's network, so
     *  these relay fine even though our own route to them is blackholed. */
    private static void fetchSocksList(String sourceUrl, int limit) {
        String body;
        try {
            body = httpGet(sourceUrl);
        } catch (Throwable t) {
            Log.w(TAG, "socks list fetch failed: " + sourceUrl, t);
            return;
        }
        Pattern pattern = Pattern.compile("(?m)^\\s*([0-9a-zA-Z.\\-]+):(\\d{2,5})\\s*$");
        Matcher m = pattern.matcher(body);
        int added = 0;
        while (m.find() && added < limit) {
            int port;
            try {
                port = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (addCandidate(new ProxyItem(m.group(1), port, "", 0))) added++;
        }
        Log.i(TAG, "socks source " + sourceUrl + " added " + added);
    }

    private static void fetchProxiesJson(String sourceUrl) {
        String body;
        try {
            body = httpGet(sourceUrl);
        } catch (Throwable t) {
            Log.w(TAG, "Fetch error from " + sourceUrl, t);
            return;
        }
        int added = 0;
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String server = obj.optString("server", "");
                int port = obj.optInt("port", 0);
                String secret = obj.optString("secret", "");
                if (server.isEmpty() || port <= 0 || !secret.toLowerCase().startsWith("ee")) continue;
                if (addCandidate(new ProxyItem(server, port, secret, 1))) added++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Parse error from " + sourceUrl, t);
            return;
        }
        Log.i(TAG, "json source " + sourceUrl + " added " + added);
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
    /**
     * Open a loopback forwarder when the node itself is unreachable from here but a relay can
     * reach it, and point the item at the local port. Telegram then connects to 127.0.0.1 exactly
     * as it does to WebProxyTransport's WebSocket bridge, and the blocked node carries traffic.
     * A node that answers directly is never chained: the extra hop only costs latency.
     */
    private static void resolveChain(ProxyItem proxy) {
        proxy.chainPort = 0;
        if (proxy.chainRelay == null || proxy.isLocalDpi() || proxy.type == 2) return;
        if (testProxy(proxy.address, proxy.port, 1200) >= 0) return;
        int port = ColgramProxyChain.open(proxy.address, proxy.port, proxy.chainRelay);
        if (port > 0) {
            proxy.chainPort = port;
            Log.i(TAG, "chained " + proxy.address + ":" + proxy.port + " through "
                    + proxy.chainRelay + " at 127.0.0.1:" + port);
        } else {
            proxy.chainRelay = null;
        }
    }

    public static void forceApplyProxy(ProxyItem proxy) {
        if (proxy == null) return;
        currentActiveProxy = proxy;
        resolveChain(proxy);
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
                        .putString("proxy_ip", proxy.effectiveHost())
                        .putInt("proxy_port", proxy.effectivePort())
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

    /** Entries that have been checked and failed, with no relay that can reach them either. */
    public static int getDeadCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) {
            if (p.chainRelay != null) continue;
            if (p.pingMs == -2 || p.tcpMs == -2) c++;
        }
        return c;
    }

    /** Entries no verdict exists for yet. */
    public static int getUncheckedCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.pingMs == -1 && p.tcpMs == -1) c++;
        return c;
    }

    /** Entries a direct socket cannot reach but a relay can: usable only through a chain. */
    public static int getChainedCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.chainRelay != null) c++;
        return c;
    }

    /** Tunnels currently known to answer from here. */
    public static int getRelayCount() {
        int c = 0;
        synchronized (relayPool) {
            for (ColgramProxyChain.Relay r : relayPool) if (!r.dead && r.rttMs >= 0) c++;
        }
        return c;
    }

    /** Human-readable state of one ping result. */
    public static String describePing(ProxyItem item) {
        if (item == null) return "нет";
        if (item.chainRelay != null) {
            return "через ретранслятор " + item.chainRelay.host + " (" + item.chainRelay.rttMs + " мс)";
        }
        if (item.pingMs >= 0) return item.pingMs + " мс";
        if (item.tcpMs >= 0) return "TCP " + item.tcpMs + " мс";
        if (item.tcpMs == -2) return "недоступен напрямую";
        return "не проверен";
    }

    /** Entries that carry a fake-TLS secret, i.e. the handshake looks like ordinary HTTPS. */
    public static int getFakeTlsCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.fakeTls) c++;
        return c;
    }

    public static int getPoolSize() {
        return verifiedPool.size();
    }
}
