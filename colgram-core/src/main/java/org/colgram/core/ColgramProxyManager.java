package org.colgram.core;

import android.net.ConnectivityManager;
import android.net.Network;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ColgramProxyManager — Real Anti-Censorship Engine.
 *
 * 1. Starts embedded local SOCKS5 DPI bypass (127.0.0.1:9876) with 1-byte TCP desync.
 * 2. Harvests public MTProto/SOCKS candidates and carries only Telegram-verified nodes.
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
        public final String user;
        public final String password;
        public final int type; // 0 = SOCKS5, 1 = MTProto, 2 = WEB (wss:// bridge, no port)
        /** -1 never checked, -2 checked and not working, otherwise RTT in ms. */
        public volatile int pingMs = -1;
        /**
         * Only ever set by a real protocol check (Telegram's native checkProxy). It used to be
         * assigned {@code true} to the whole hardcoded list without any check at all, which made
         * "Pool: 8 alive" a fiction and let the rotator prefer nodes that had never answered.
         */
        public volatile boolean isAvailable = false;
        /** SystemClock.elapsedRealtime() of the last protocol check; 0 = never checked. */
        public volatile long lastCheckAt = 0L;
        /** Times Telegram's native layer failed a connection while this entry was applied. */
        int nativeFailures = 0;
        /** Times the protocol probe said "dead". Enough of these and the entry leaves the pool. */
        int failedVerdicts = 0;

        /**
         * Whether this entry has ever been applied. The failure counters are reset exactly once, here,
         * rather than on every apply -- see forceApplyProxy.
         */
        boolean appliedOnce = false;
        /** Fake-TLS (an "ee" secret): the handshake is wrapped in a plausible TLS record. */
        public final boolean fakeTls;
        /** Last plain TCP connect result: -1 unknown, -2 unreachable, else RTT ms. */
        public volatile int tcpMs = -1;
        /** elapsedRealtime of the last TCP-level check. */
        public volatile long tcpCheckedAt = 0L;
        /** Catalog measurement belongs to this network, and does not activate the candidate. */
        private long catalogNetwork = -1L;
        private long catalogTcpAt;
        private long catalogProtocolAt;
        private String catalogPinnedAddress;
        /** Set when a native MTProto/SOCKS handshake succeeded at least once. */
        public volatile boolean nativeVerified = false;
        /** A verdict belongs to the exact effective endpoint, not a later replacement front. */
        private volatile String verifiedEndpoint = "";
        /** Prevent the fast checker and the continuous checker from probing the same node. */
        final AtomicBoolean nativeProbeInFlight = new AtomicBoolean(false);
    /** One fast startup pass per process; user-configured entries remain unchecked until tested. */
            /**
             * Whether this node has been handed to the fast native checker since the last sweep.
             *
             * <p>It used to be set once and never cleared, which made every node checkable exactly
             * once in the life of the process. Free public proxies fail and come back -- a node that
             * timed out during the harvest is frequently the one that answers ten minutes later -- and
             * a node marked dead in the first sweep stayed dead in every later one, which is why the
             * pool only ever shrank while the switch kept spinning.
             *
             * <p>Reset by {@link #sweepFast} for every entry it walks, so each pass re-checks what it
             * has not seen and the flag means "already checked in this sweep" rather than "ever
             * checked".
             */
        volatile boolean fastProbeAttempted = false;
        /** When a quarantined node may be asked again. Zero while it is in the pool. */
        volatile long nextEligibleAt = 0L;
        /**
         * True when Colgram picked this node itself, false when he typed or tapped it. Persisted,
         * because without it an auto-selected node came back as "the proxy the user chose" on the
         * next start and was restored even after he switched auto-connect off - which is the
         * "it put me on a proxy again" complaint, wearing a different hat.
         */
        public volatile boolean autoSelected = false;
        /**
         * Relay that can reach this node when a direct socket cannot. The block on this
         * network is per-IP, so plenty of perfectly good proxies are simply unreachable from
         * here; a relay that is reachable turns them back on through a loopback forwarder.
         */
        public volatile ColgramProxyChain.Relay chainRelay = null;
        /** Loopback port of an open chain forwarder, when this node is applied through a relay. */
        public volatile int chainPort = 0;
        private volatile ColgramLocalTcpFront baseFront;
        /**
         * FakeTLS handshake already failed once and a browser-fingerprint mimic front was tried
         * for this node. One attempt per process keeps the prober from burning handshakes on a
         * node whose real problem is a blocked IP.
         */
        public volatile boolean mimicTried = false;
        /** True while this node is applied through a live mimic front (chainPort holds its port). */
        public volatile boolean mimicActive = false;

        public String effectiveHost() {
            return chainPort > 0 || (baseFront != null && baseFront.port() > 0) ? "127.0.0.1" : address;
        }

        public int effectivePort() {
            return chainPort > 0 ? chainPort : (baseFront != null && baseFront.port() > 0 ? baseFront.port() : port);
        }

        public ProxyItem(String address, int port, String secret, int type) {
            this(address, port, secret, type, "", "");
        }

        public ProxyItem(String address, int port, String secret, int type, String user, String password) {
            String normalized = type == 2 ? null : ColgramProxyPolicy.normalizeHost(address);
            this.address = normalized == null ? address : normalized;
            this.port = port;
            this.secret = secret != null ? secret : "";
            this.user = type == 0 && user != null ? user : "";
            this.password = type == 0 && password != null ? password : "";
            this.type = type;
            this.fakeTls = type == 1 && ColgramMtprotoSecrets.isFakeTls(this.secret);
        }

        public boolean isLocalDpi() {
            return "127.0.0.1".equals(address) && port == ColgramDpiBypass.activePort();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ProxyItem)) return false;
            ProxyItem other = (ProxyItem) o;
            return address.equals(other.address) && port == other.port
                    && type == other.type && secret.equals(other.secret)
                    && user.equals(other.user) && password.equals(other.password);
        }

        @Override
        public int hashCode() {
            int hash = address.hashCode() * 31 + port;
            hash = (hash * 31 + type) * 31 + secret.hashCode();
            return (hash * 31 + user.hashCode()) * 31 + password.hashCode();
        }

        @Override
        public String toString() {
            if (isLocalDpi()) return "Встроенный DPI-маршрут";
            if (this == dcRemapItem) return "Прямой маршрут Telegram (подбор адреса DC)";
            if (isInternalLoopbackEndpoint(this)) {
                if (chainRelay != null) {
                    return "Локальный мост через ретранслятор " + chainRelay.host + ":" + chainRelay.port;
                }
                return "Внутренний локальный маршрут";
            }
            if (type == 2) return address + " (WebSocket)";
            return address + ":" + port + (type == 1 ? " (MTProto)" : " (SOCKS5)");
        }
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(8);
    /** Feed downloads are independent; serialize only parsing into the shared proxy pool. */
    private static final ExecutorService sourceFetchers = Executors.newFixedThreadPool(4);
    // Separate from the background pool: a large relay sweep must not starve a user selection.
    private static final int SELECTION_TCP_PARALLEL = 6;
    private static final ExecutorService selectionTcpWorkers = Executors.newFixedThreadPool(SELECTION_TCP_PARALLEL);
    private static final java.util.concurrent.Semaphore selectionTcpSlots =
            new java.util.concurrent.Semaphore(SELECTION_TCP_PARALLEL);
    private static final long SOURCE_BATCH_TIMEOUT_MS = 35_000L;
    private static final int SOURCE_IO_TIMEOUT_MS = 3_000;
    private static final int SOURCE_MAX_BYTES = 512 * 1024;
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
    private static final ExecutorService sweeper = Executors.newFixedThreadPool(8);
    private static final AtomicBoolean sweeping = new AtomicBoolean(false);
    private static final AtomicBoolean sweepAgain = new AtomicBoolean(false);
    private static final AtomicBoolean autoConnectPending = new AtomicBoolean(false);
    private static final AtomicBoolean relayFallbackProbeRunning = new AtomicBoolean(false);
    /** Walk each measured relay after the previous local SOCKS route fails Telegram's handshake. */
    private static final java.util.concurrent.atomic.AtomicInteger relayFallbackCursor =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger relayFallbackFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final AtomicBoolean relayFallbackRetryPending = new AtomicBoolean(false);
    private static volatile ProxyItem currentActiveProxy = null;
    private static volatile Context appContext = null;
    private static volatile ProxyItem startupSavedProxy;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean fetchingSources = new AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicLong networkProbeEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastSourceFetchAt;
    private static volatile int sourceRound;
    private static volatile SourceBatch lastSourceBatch;
    private static final AtomicBoolean harvestingRelays = new AtomicBoolean(false);
    private static final AtomicBoolean networkCallbackRegistered = new AtomicBoolean(false);
    private static volatile ConnectivityManager.NetworkCallback networkCallback;

    // Refreshed public feeds; an entry is not called working until Telegram's native
    // checkProxy handshake succeeds. Multiple independent feeds prevent one dead mirror
    // from leaving the pool empty on a filtered network.
    private static final String[] PROXY_SOURCES_MTPROTO_LINKS = {
        "https://zakky8.github.io/mtproto-proxy-pro/censorship_resistant.txt",
        "https://raw.githubusercontent.com/tgmtproxy/telegram-mtproto-proxy-list/main/proxies.txt",
        "https://zakky8.github.io/mtproto-proxy-pro/all_proxies.txt",
        "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/main/all_proxies.txt",
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
        "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
        "https://raw.githubusercontent.com/neobxod/mtproto-for-telegram/master/all_proxies.txt",
    };
    private static final String[] PROXY_SOURCES_MTPROTO_JSON = {
        "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/proxies.json",
        "https://zakky8.github.io/mtproto-proxy-pro/proxies.json",
    };
    private static final String[] PROXY_SOURCES_SOCKS = {
        "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt",
        "https://raw.githubusercontent.com/iplocate/free-proxy-list/main/protocols/socks5.txt",
        "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/protocols/socks5/data.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/Zaeem20/FREE_PROXIES_LIST/master/socks5.txt",
        "https://api.proxyscrape.com/v2/?request=getproxies&protocol=socks5&timeout=10000&country=all",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
        "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt",
        "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt",
        "https://proxyspace.pro/socks5.txt",
        "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/main/socks5_all.txt"
    };

    /** Reachable public tunnels let us test and, if needed, chain a Telegram proxy. */
    private static final String[] RELAY_SOURCES_HTTP = {
        "https://raw.githubusercontent.com/iplocate/free-proxy-list/main/protocols/http.txt",
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt",
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
        "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/http.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
        "https://raw.githubusercontent.com/zloi-user/hideip.me/main/http.txt",
        "https://raw.githubusercontent.com/Thordata/awesome-free-proxy-list/HEAD/proxies/http.txt",
        "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/HEAD/proxies/protocols/http/data.txt",
        "https://raw.githubusercontent.com/VPSLabCloud/VPSLab-Free-Proxy-List/HEAD/http_all.txt",
        "https://raw.githubusercontent.com/sakha1370/OpenRay/HEAD/output/kind/http.txt",
        "https://raw.githubusercontent.com/Zaeem20/FREE_PROXIES_LIST/HEAD/http.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/HEAD/proxies/http.txt"
    };
    private static final String[] RELAY_SOURCES_SOCKS = PROXY_SOURCES_SOCKS;
    private static final int MAX_RELAYS = 16;
    private static final int MAX_STARTUP_MTPROTO_FEEDS = 3;
    private static final int MAX_STARTUP_JSON_FEEDS = 2;
    /**
     * Six SOCKS feeds, not three.
     *
     * <p>The first three entries in the list are raw harvested lists - "TheSpeedX/SOCKS-List",
     * "hookzof/socks5_list" and "r00tee/Proxy-List" - and they deliver host:port pairs with nothing
     * behind them. Every SOCKS entry they contributed in a measured run died on arrival:
     *
     * <p>    native check 45.95.233.88:1082      -> dead (1)
     * <p>    native check 151.243.224.12:1080    -> dead (3)
     * <p>    native check 172.81.111.156:10001  -> dead (3)
     * <p>    native check 152.32.219.123:10808  -> dead (2)
     *
     * <p>So the startup budget was spent entirely on lists that cannot produce a usable node, while
     * the sources that filter for liveness - "jetkai/proxy-list/online-proxies" among them - sit
     * further down and were never fetched. Six costs a few more requests at startup and is what it
     * takes to reach a feed whose entries answer.
     */
    private static final int MAX_STARTUP_SOCKS_FEEDS = 6;
    private static final int MAX_STARTUP_RELAY_FEEDS = 2;

    /** Separate budgets stop generic SOCKS lists from evicting every MTProto candidate. */
    // Bounded per-feed sampling keeps a much wider source set without turning one giant list
    // into a minute-long probe sweep: 20 x 75 links + 4 x 100 JSON; 19 x 65 SOCKS.
    private static final int MAX_MTPROTO_CANDIDATES = 384;
    private static final int MAX_SOCKS_CANDIDATES = 384;
    /** Reserve one separate slot for the verified local-over-relay SOCKS route. */
    private static final int MAX_LOCAL_RELAY_ROUTES = 1;
    private static final int MAX_POOL_SIZE = 1 + MAX_MTPROTO_CANDIDATES
            + MAX_SOCKS_CANDIDATES + MAX_LOCAL_RELAY_ROUTES;
    /** How many entries the stock proxy list (and Telegram's own rotator) gets to see.
     *
     * Publishing the whole pool (1700+ entries) pushed Telegram's own proxy screen controls
     * — "Использовать прокси" and "Использовать прокси для звонков" — below a mile-long list,
     * which read as "the buttons disappeared". The rotator only ever needs the best few, so
     * the stock screen gets the top of the pool, verified entries first. */
    private static final int MAX_PUBLISHED_TO_STOCK = 30;
    private static final String KEY_PROXY_MANUALLY_DISABLED = "colgram_proxy_manually_disabled";

    /**
     * True while Colgram, not the user, has taken Telegram's own rotator off.
     *
     * <p>Read by {@link #enableStockRotation(Context)} to refuse to hand the rotator back while the
     * route that displaced it is still being applied. Only an explicit stock-proxy action clears it.
     */
    private static volatile boolean stockRotationPausedByColgram = false;
    /** Entries verified alive and kept across restarts. */
    private static final int MAX_REMEMBERED_ALIVE = 24;

    /**
     * Main entry point — called from ColgramHookHandler.init() on app startup.
     */
    public static void activateBuiltinProxy(final Context context) {
        if (context == null || !initialized.compareAndSet(false, true)) return;
        appContext = context.getApplicationContext();
        // Handed to the MASQUE client so its availability probe can answer from the package without
        // loading the library into this process - see ColgramMasqueNative.isAvailableForProbe().
        ColgramMasqueNative.setProbeContext(appContext);
        ColgramBootJobService.scheduleProxyRefresh(appContext);
        scheduleBackgroundProxyCheck(PROXY_START_DELAY_MS);
        registerNetworkCallback(appContext);
        SharedPreferences startupPrefs = appContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        if (startupPrefs.getBoolean("proxy_enabled", false) && !startupPrefs.getBoolean("proxy_auto_applied", false))
            startupSavedProxy = findSavedProxy(startupPrefs);
        mainHandler.post(() -> ensureLocalBaseRoute(appContext));
        scheduler.scheduleWithFixedDelay(() -> mainHandler.post(() -> {
            if (!ColgramDpiBypass.isBound() || currentActiveProxy == null)
                ensureLocalBaseRoute(appContext);
        }), 3, 3, TimeUnit.SECONDS);

        // Everything below touches sockets: binding the local DPI listener,
        // probing proxies over TCP, and flipping Telegram's proxy settings (which
        // makes MTProto tear down and rebuild its connections). Running that
        // during application startup races Telegram's native ConnectionSocket for
        // process-wide file descriptors and trips bionic's fdsan guard, aborting
        // the process inside libtmessages.49.so. Defer the whole block until the
        // network stack has settled.
        final long startupEpoch = ColgramRouteEpoch.snapshot();
        STARTUP_DEFERRED.execute(() -> {
            try {
                Thread.sleep(PROXY_START_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                activateBuiltinProxyNow(startupEpoch);
            } catch (Throwable t) {
                Log.e(TAG, "Deferred proxy activation failed", t);
            }
        });
    }

    private static void registerNetworkCallback(Context context) {
        if (context == null || !networkCallbackRegistered.compareAndSet(false, true)) return;
        Object service = context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!(service instanceof ConnectivityManager)) {
            networkCallbackRegistered.set(false);
            return;
        }
        ConnectivityManager manager = (ConnectivityManager) service;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                invalidateNetworkProbeState("network available");
            }

            @Override
            public void onLost(Network network) {
                invalidateNetworkProbeState("network lost");
            }
        };
        try {
            manager.registerDefaultNetworkCallback(callback);
            networkCallback = callback;
        } catch (Throwable t) {
            networkCallbackRegistered.set(false);
            Log.w(TAG, "could not observe network changes: " + t);
        }
    }

    private static void invalidateNetworkProbeState(String reason) {
        networkProbeEpoch.incrementAndGet();
        for (ProxyItem proxy : new ArrayList<>(verifiedPool)) {
            proxy.isAvailable = false;
            proxy.nativeVerified = false;
            proxy.lastCheckAt = 0L;
            proxy.tcpMs = -1;
        }
        ProxyItem active = currentActiveProxy;
        if (active != null) {
            active.isAvailable = false;
            active.nativeVerified = false;
            active.lastCheckAt = 0L;
        }
        ColgramDcRemap.invalidateFailedProbes();
        ColgramDpiBypass.clearConnectionFailureBackoff();
        directRouteCheckedAt = 0L;
        localRouteUnavailableUntil = 0L;
        mainHandler.post(() -> {
            publishPoolToStock();
            if (backgroundProxySelection != null) backgroundProxyTick(backgroundProxySelection);
            else scheduleBackgroundProxyCheck(PROXY_START_DELAY_MS);
        });
        Log.i(TAG, "invalidated direct-route probe cache (" + reason + ")");
    }

    private static final ExecutorService STARTUP_DEFERRED = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colgram-proxy-deferred");
        t.setDaemon(true);
        return t;
    });
    /** Delay before any proxy-side socket work begins. */
    private static final long PROXY_START_DELAY_MS = 10000L;

    private static void activateBuiltinProxyNow(long startupEpoch) {
        mainHandler.post(() -> ColgramRouteEpoch.runIfCurrent(startupEpoch, () -> {
            ColgramDpiBypass.startImmediately();
            SharedPreferences prefs = appContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
            ProxyItem saved = startupSavedProxy;
            if (!ColgramConfig.isWarpEnabled() && !isUserProxyDisabled()
                    && saved != null && !isInternalLoopbackEndpoint(saved)) {
                selectUserProxyForEpoch(appContext, saved, startupEpoch, false);
            } else ensureLocalBaseRoute(appContext);
        }));
        if (ColgramConfig.isBuiltinProxyEnabled()) {
            initVerifiedPool();
            populateSharedConfigProxies();
            sweepFast(null);
        }

        // Optional source discovery waits until the first screen and cached route are ready.
        ColgramOptimizer.scheduleAfterStartup(() -> {
            try {
                if (ColgramConfig.isBuiltinProxyEnabled()) fetchAndVerifyAllSources();
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

        // 5b. The connection watchdog: an applied proxy that stopped carrying traffic.
        //
        // Fresh verdicts fix rotation for a node that is measurably dead. They do not fix the two
        // cases that actually keep the header reading "Соединение...": the node still answers TCP
        // but no longer tunnels, and tgnet sitting on a socket it already gave up on. So look at
        // what Telegram's own connection state says, and act on it.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                connectionWatchdogTick();
            } catch (Throwable t) {
                Log.w(TAG, "connection watchdog: " + t);
            }
        }, 15, 10, TimeUnit.SECONDS);

        // 6. Refresh public proxy feeds and their native reachability verdicts.
        // This can fan out to several remote hosts, so do not wake the radio for feed traffic
        // while the phone is both unplugged and below the low-battery threshold. The 20-minute
        // scheduler still checks the battery state and resumes after charging / recovery.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (ColgramPowerGuard.shouldThrottle(appContext)) {
                    Log.i(TAG, "proxy feed refresh skipped on low unplugged battery");
                    return;
                }
                fetchAndVerifyAllSources();
            } catch (Throwable t) {
                Log.w(TAG, "periodic proxy feed refresh failed", t);
            }
        }, 20, 20, TimeUnit.MINUTES);
    }

    /** tgnet's ConnectionStateConnected, from org.telegram.tgnet.ConnectionsManager. */
    private static final int TG_STATE_CONNECTED = 3;
    private static final int TG_STATE_WAITING_FOR_NETWORK = 2;
    private static final int TG_STATE_UPDATING = 5;
    private static int watchdogUnconnectedTicks = 0;
    private static int localBaseUnconnectedTicks = 0;

    /**
     * Ticks on which the applied node passed every protocol check and still carried nothing.
     *
     * <p>Kept apart from {@link #watchdogUnconnectedTicks} on purpose: that one is cleared by the
     * re-dial branch a few lines below, so sharing it let the two conditions reset each other and
     * neither ever reached its threshold.
     */
    private static int checksPassButUnconnectedTicks = 0;
    private static int watchdogRedials = 0;
    private static int directUnconnectedTicks = 0;
    /** Watchdog ticks spent waiting for the direct remap to produce a connection. */
    private static int dcRemapTicks = 0;
    // The direct remapper searches a bounded set of Telegram-owned addresses. Let that pass
    // finish, then fall back to the remembered/harvested proxy pool instead of pinning the UI
    // to a dead loopback route for a full minute.
    private static final int DC_REMAP_VERDICT_TICKS = 3;
    /** How often to ask Telegram for its published server addresses. */
    private static final long FRESH_ADDRESSES_INTERVAL_MS = 120000L;

    /** True once the remap has had its window and Telegram is still not connected. */
    private static boolean dcRemapGaveUp() {
        return dcRemapTicks >= DC_REMAP_VERDICT_TICKS;
    }

    /**
     * One watchdog pass: if a proxy is applied and Telegram is not connected, either re-dial or
     * move to another node. Three ticks of grace (30 s) before acting, because a normal reconnect
     * is not an outage, and a cap on re-dials so a node that answers but cannot carry traffic gets
     * replaced instead of being poked forever.
     */
    /**
     * True for an applied entry that points at this process's own loopback plumbing but is
     * neither the DPI listener nor the DC remap - i.e. a chain hop whose port died with the
     * previous process. Those are never user choices and must not block rotation.
     */
    private static boolean isStaleLocalHop(ProxyItem item) {
        if (item == null || !"127.0.0.1".equals(item.address)) return false;
        if (item.isLocalDpi()) return false;
        if (item == dcRemapItem) return false;
        if (ColgramProxyChain.isOpen(item.port)) return false;
        return true;
    }

    private static void connectionWatchdogTick() {
        Context ctx = appContext;
        if (ctx == null || ColgramConfig.isWarpEnabled()) return;
        int state = tgnetConnectionState();
        ProxyItem applied = currentActiveProxy;
        if (applied != null && applied.isLocalDpi()) {
            // The permanent listener is not an external proxy. Do not treat local TCP accept
            // as success or try to rotate it away. Recover signed DC settings independently
            // of the catalog, without changing the chosen route or using a public relay.
            if (hasSystemVpn(ctx) || !ColgramUiBridge.isAppInForeground() || state < 0
                    || state == TG_STATE_CONNECTED || state == TG_STATE_UPDATING
                    || state == TG_STATE_WAITING_FOR_NETWORK) {
                localBaseUnconnectedTicks = 0;
                return;
            }
            if (++localBaseUnconnectedTicks >= 3) {
                localBaseUnconnectedTicks = 0;
                requestFreshDcAddresses("local base stalled");
                if (ColgramConfig.isAutoProxyEnabled()) autoConnectIfBlocked();
            }
            return;
        }
        localBaseUnconnectedTicks = 0;
        if (!ColgramConfig.isBuiltinProxyEnabled()) return;
        // The remap deliberately does not write Telegram's proxy prefs (its port dies with the
        // process), so isProxyEnabled() cannot be the only test for "something is carrying
        // connections". Reading it alone meant the watchdog quit on the first tick while the remap
        // was applied: no verdict, no report, no fresh addresses - just a spinner.
        if (!isProxyEnabled(ctx) && !isDcRemapActive() && currentActiveProxy == null) {
            watchdogUnconnectedTicks = 0;
            watchdogRedials = 0;
            int directState = tgnetConnectionState();
            if (directState == TG_STATE_CONNECTED) {
                directUnconnectedTicks = 0;
                ColgramBypassNotice.clear(ctx);
                return;
            }
            if (directState < 0 || ++directUnconnectedTicks < 3) return;
            directUnconnectedTicks = 0;
            // This path previously returned forever: on a fresh install with no proxy,
            // neither fresh DC addresses nor an explanation were ever requested.
            requestFreshDcAddresses("direct connection stalled");
            tgnetCheckConnection();
            if (ColgramConfig.isDcRemapEnabled() && !isUserProxyDisabled()) {
                applyDcRemap(true);
            } else {
                autoConnectIfBlocked();
            }
            return;
        }
        directUnconnectedTicks = 0;
        if (state < 0) return;                        // no accessor: nothing to judge
        boolean endpointProvenDead = applied != null
                && (applied.nativeFailures >= 2 || applied.failedVerdicts >= 2);
        if (state == TG_STATE_CONNECTED && !endpointProvenDead) {
            watchdogUnconnectedTicks = 0;
            watchdogRedials = 0;
            dcRemapTicks = 0;
            checksPassButUnconnectedTicks = 0;
            ColgramBypassNotice.clear(ctx);
            return;
        }
        // A node whose checks keep passing while nothing connects is the one case this loop did not
        // handle, and it is the case a pool of free public proxies produces most often. The checks
        // measure the handshake; the connection is a different fact, and a node can be perfectly good
        // at the first and useless at the second. Counting those here is what stops the retry loop
        // from naming the same address five times:
        //
        // 15:33:44  native check ssh.meow0.co.uk:22 -> 145 ms
        // 15:33:44  auto-connecting through ssh.meow0.co.uk:22
        // 15:33:52  auto-connecting through ssh.meow0.co.uk:22
        // 15:33:54  auto-connecting through ssh.meow0.co.uk:22
        if (applied != null && !endpointProvenDead && ++checksPassButUnconnectedTicks >= 3) {
            checksPassButUnconnectedTicks = 0;
            watchdogUnconnectedTicks = 0;
            Log.i(TAG, applied.address + ":" + applied.port
                    + " passes its checks but carries nothing; rotating instead of retrying it");
            onTelegramNotConnected();
            return;
        }
        if (state == TG_STATE_CONNECTED && endpointProvenDead) {
            // getInstance(0).getConnectionState() is not a per-connection truth: it read
            // "connected" for minutes while the remap it was supposedly using refused every
            // single CONNECT. Trusting it alone meant no verdict, no report and no fallback -
            // an explanation-free spinner. Protocol failures on the applied endpoint outrank it.
            Log.w(TAG, "tgnet says connected but " + applied.address + ":" + applied.port
                    + " failed " + Math.max(applied.nativeFailures, applied.failedVerdicts)
                    + " protocol checks; treating as disconnected");
        }
        if (endpointProvenDead) {
            checksPassButUnconnectedTicks = 0;
        }
        if (isDcRemapActive() && currentActiveProxy == dcRemapItem) {
            // tgnet is the only honest judge of whether the remap carried a real MTProto session:
            // it does the full handshake, so "connected" means an address was found and "still
            // connecting" after a minute means this network has none. Say so, then let the
            // consented fallback run.
            if (++dcRemapTicks >= DC_REMAP_VERDICT_TICKS
                    && dcRemapTicks % DC_REMAP_VERDICT_TICKS == 0) {
                Log.i(TAG, "DC remap verdict: Telegram not connected directly. "
                        + ColgramDcRemap.describe());
                Log.i(TAG, "direct remap exhausted; checking the configured fallback");
                // Worth trying Telegram's own remedy before declaring the network closed: the
                // published addresses rotate precisely because the old ones get blocked, and the
                // native side only asks for them when a real DC connection fails - which never
                // happens while the remap's loopback socket opens happily.
                requestFreshDcAddresses("direct remap exhausted");
                mainHandler.post(() -> toast("Напрямую ни один адрес Telegram не отвечает."));
                ColgramBypassNotice.showBlocked(ctx,
                        "Напрямую ни один адрес Telegram не отвечает. " + ColgramDcRemap.describe());
                if (ColgramConfig.isAutoProxyEnabled()) autoConnectIfBlocked();
            }
            return;
        }
        if (++watchdogUnconnectedTicks < 3) return;
        watchdogUnconnectedTicks = 0;

        ProxyItem active = currentActiveProxy;
        if (active == null) {
            requestFreshDcAddresses("no proxy applied and not connected");
            autoConnectIfBlocked();
            return;
        }
        boolean reachable = probeTcp(active.effectiveHost(), active.effectivePort(), 2500) >= 0;
        if (!reachable) {
            Log.i(TAG, "applied proxy " + active.address + ":" + active.port
                    + " stopped answering while disconnected; rotating");
            onVerdict(active, false);
            return;
        }
        if (watchdogRedials++ < 2) {
            Log.i(TAG, "applied proxy answers but Telegram is not connected (state "
                    + state + "); asking tgnet to re-dial");
            tgnetCheckConnection();
            return;
        }
        Log.i(TAG, "applied proxy " + active.address + ":" + active.port
                + " answers but carries nothing; rotating to another node");
        watchdogRedials = 0;
        switchToNextProxy(true);
    }

    private static boolean hasSystemVpn(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return true; // Don't reclassify an unknown route as direct.
            Network network = manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities =
                    network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities == null
                    || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private static int selectedAccountSlot() {
        try {
            int account = Class.forName("org.telegram.messenger.UserConfig").getField("selectedAccount").getInt(null);
            return account >= 0 && account < colgramAccountSlots ? account : 0;
        } catch (ReflectiveOperationException unavailable) {
            return 0;
        }
    }

    /** Telegram's own connection state for the selected account, or -1 when unavailable. */
    private static int tgnetConnectionState() {
        try {
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Object inst = cm.getMethod("getInstance", int.class).invoke(null, selectedAccountSlot());
            Object state = cm.getMethod("getConnectionState").invoke(inst);
            return state instanceof Integer ? (Integer) state : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Public entry for the settings screen: re-read the network policy right now. */
    public static void recheckConnectionNow() {
        invalidateNetworkProbeState("manual recheck");
        executor.execute(ColgramProxyManager::tgnetCheckConnection);
    }

    /** Ask tgnet to re-evaluate the network and rebuild its connections. */
    private static void tgnetCheckConnection() {
        try {
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Object inst = cm.getMethod("getInstance", int.class).invoke(null, 0);
            cm.getMethod("checkConnection").invoke(inst);
        } catch (Throwable t) {
            Log.w(TAG, "could not ask tgnet to re-dial: " + t);
        }
    }

    private static long lastFreshAddressesAt = -FRESH_ADDRESSES_INTERVAL_MS;

    /**
     * Ask Telegram for its current server addresses, through Telegram's own mechanism.
     *
     * ConnectionsManager.onRequestNewServerIpAndPort is the callback native tgnet uses when it has
     * run out of addresses to dial: it fetches the signed DnsConfig published as TXT records on
     * apv3.stel.com (Google first, Mozilla as the alternate) and applies the fresh IP:port:secret
     * triples. That is the only censorship bypass Telegram itself ships, it needs no third-party
     * relay, and it is the one thing that can replace a hardcoded 2018 DC list that a block has
     * already learned. Measured on this link: dns.google answers in 276 ms, so the source is
     * reachable - the callback simply was not firing often enough to matter.
     *
     * Rate limited: each call kicks off a network fetch and a reconnect, and doing that every
     * watchdog tick is a storm, not a retry.
     */
    private static synchronized void requestFreshDcAddresses(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFreshAddressesAt < FRESH_ADDRESSES_INTERVAL_MS) return;
        lastFreshAddressesAt = now;
        try {
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
            // The upstream Google task owns fallback to the Mozilla task. Dispatching both
            // callbacks simultaneously only races Telegram's single currentTask slot.
            cm.getMethod("onRequestNewServerIpAndPort", int.class, int.class)
                    .invoke(null, 0, selectedAccountSlot());
            Log.i(TAG, "asked Telegram for its current server addresses (" + reason + ")");
        } catch (Throwable t) {
            Log.w(TAG, "could not ask Telegram for fresh addresses: " + t);
        }
    }

    private static void initVerifiedPool() {
        // Seed the local path first, then restore recently working public candidates. The
        // previous initializer erased the alive-node cache on every process start and never
        // called loadRemembered(), so the app lost its working pool before a new feed fetch
        // could finish (or when those feeds were unreachable without a VPN).
        ProxyItem localDpi = new ProxyItem("127.0.0.1", ColgramDpiBypass.activePort(), "", 0);
        if (!containsProxy(localDpi)) {
            verifiedPool.add(localDpi);
        }
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
    /**
     * Per-check deadline.
     *
     * <p>Twelve seconds, and this used to say twelve while the value was four. The comment was
     * written when four was measured and has not been read since, and four is too short for what
     * {@code checkProxy} actually does: it is a full MTProto handshake to a Telegram DC through the
     * proxy, which on a mobile path means a TCP connect to a third-party SOCKS host, its own upstream,
     * a salt exchange, and an unencrypted request answered by a server on the far side. Four seconds
     * lands in the middle of that on any path with more than one hop, so the deadline fires and
     * {@code onVerdict(item, false)} records a dead node that never had the chance to answer:
     *
     * <p>    native check solar.velvetoak.work:443 -> dead (2)
     * <p>    native check 107.174.30.92:1080     -> dead (2)
     *
     * while the same nodes accept a TCP connection from the device in well under a second - checked
     * directly with nc, every one of them returning RC=0. The verdict was measuring the deadline and
     * not the proxy.
     *
     * <p>The cost is real and is why this is not simply large: the sweep holds a slot per check, so a
     * twelve-second deadline on a pool of dead entries is a sweep that never finishes. That is handled
     * by {@link #NATIVE_TCP_DEAD_SKIP} and by the ordering in {@link #nextProberTarget()}, which only
     * spend the full budget on nodes that already answered TCP - not by shortening the budget for the
     * nodes that did.
     */
    private static final long NATIVE_CHECK_TIMEOUT_MS = 12000L;

    /**
     * How long the parallel sweep may run before the continuous prober joins it.
     *
     * <p>Eight nodes at two slots and four seconds each is about sixteen seconds to drain a short
     * window, and the pool is walked at roughly one per two seconds meanwhile. Fifteen seconds is
     * measured rather than guessed: it is where the fast loop stops producing verdicts on the pools
     * this app actually collects.
     */
    private static final long FAST_SWEEP_DRAIN_MS = 15000L;
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
        scheduleBackgroundProxyCheck(0L);
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
        if (isProxySelectionPending()) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        if (!sweeping.compareAndSet(false, true)) {
            // The remote harvest can finish while the cached-candidate sweep is running.
            // The new entries still need a TCP verdict, not a five-minute wait.
            sweepAgain.set(true);
            return;
        }
        final List<ProxyItem> snapshot = new ArrayList<>(verifiedPool);
        final List<ColgramProxyChain.Relay> relaySnapshot = new ArrayList<>(relayPool);

        // Each pass re-checks what this pass has not seen. The flag used to be set once for the life
        // of the process, so a node that failed during the harvest was never asked again -- which is
        // how a pool that had a working member in it still reported none.
        for (ProxyItem item : snapshot) {
            item.fastProbeAttempted = false;
        }
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
            if (item.type == 2 || item.isLocalDpi()
                    || (isBackgroundCatalogCandidate(item) && item != currentActiveProxy
                        && (!ColgramConfig.isAutoProxyEnabled() || isFreshProtocolVerified(item)))) {
                if (remaining.decrementAndGet() == 0) finishSweep(onDone);
                continue;
            }
            sweeper.execute(() -> {
                try {
                    if (isProxySelectionPending()) return;
                    if ("127.0.0.1".equals(item.address)) {
                        int rtt = testProxy(item.address, item.port, 1200);
                        item.tcpMs = rtt >= 0 ? rtt : -2;
                        item.tcpCheckedAt = SystemClock.elapsedRealtime();
                        int finished = doneCount.incrementAndGet();
                        if (finished % 10 == 0) postSweepProgress();
                        return;
                    }
                    int rtt = testProxy(item.address, item.port, 1200);
                    if (isProxySelectionPending()) return;
                    if (rtt >= 0) {
                        item.tcpMs = rtt;
                        item.chainRelay = null;
                        // A live mimic front IS this node's route; the sweep must not wipe it.
                        if (!item.mimicActive) item.chainPort = 0;
                    } else {
                        item.tcpMs = -2;
                        // A previous native success does not make a route that now fails TCP
                        // usable. Clear any old loopback chain before trying a fresh relay; if
                        // none can be opened, keep this candidate out of selection and native
                        // probing until the next reachability sweep. A mimic front dials the
                        // node directly, so it is dead the moment TCP dies with it.
                        item.chainRelay = null;
                        item.chainPort = 0;
                        item.mimicActive = false;
                        ColgramProxyChain.Relay relay = pickRelay(relaySnapshot, item);
                        if (relay != null) {
                            int chainPort = ColgramProxyChain.open(item.address, item.port, relay);
                            if (chainPort > 0) {
                                item.chainRelay = relay;
                                item.chainPort = chainPort;
                                item.tcpMs = relay.rttMs;
                            } else {
                                item.chainRelay = null;
                                item.chainPort = 0;
                            }
                        }
                        if (item.chainRelay == null) {
                            item.isAvailable = false;
                            item.nativeVerified = false;
                            item.pingMs = -2;
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
        // Publish real, TCP-reachable endpoints now; native verification still gates any auto-use.
        publishPoolToStock();
        // A listening TCP port is not proof of an MTProto proxy. Check survivors through
        // tgnet in parallel; only a successful native verdict may become the live route.
        startFastNativeChecks();
        // The continuous prober starts once the fast sweep has drained, not alongside it.
        //
        // Both were started here together, which put two loops on the same four tgnet check slots: the
        // fast loop's two requests and the prober's one, with the prober's 4 s deadline plus a 3 s gap
        // behind whatever the sweep still had queued. The visible result was the slower of the two
        // governing the pool:
        //
        //   17:15:15  197.221.240.240:80 -> dead
        //   17:15:23  45.74.31.50:5494    -> dead      7 s apart
        //   17:15:30  8.213.128.6:808    -> dead
        //
        // which is the interval of the sequential prober alone. The prober's job is the steady
        // re-check after a pass, and starting it early only took slots from the pass that had not
        // happened yet.
        mainHandler.postDelayed(ColgramProxyManager::startProber, FAST_SWEEP_DRAIN_MS);
        autoConnectIfBlocked();
        if (sweepAgain.getAndSet(false)) sweepFast(null);
        if (onDone != null) mainHandler.post(onDone);
    }

    // tgnet owns only four proxy-check connections (PROXY_CONNECTIONS_COUNT), and the other two are
    // deliberately left free for the continuous prober and Telegram's own proxy screen. This is the
    // real ceiling on parallelism here, and it is worth stating because the obvious "just raise it"
    // makes things worse: overfilling tgnet's queue starts the timeout before a queued check has even
    // reached the network, which paints live candidates "dead" and leaves the UI spinning on stale
    // verdicts.
    //
    // Two in flight with a four second deadline is the shape that measures well. The pool is walked at
    // roughly one node per two seconds instead of one per seven, and nothing is starved:
    //
    // 16:06:35  91.84.109.244:443       -> dead
    // 16:06:42  box.lavazemi5.co.uk:443  -> dead
    // 16:06:49  72.56.102.104:443       -> dead
    //
    /**
     * How many protocol checks may be queued at once.
     *
     * <p>Two was chosen because tgnet's own checker is serial: the second request waits behind the
     * first, so a sweep costs the sum of the timeouts rather than the maximum. Measured over a pool of
     * thirty with two:
     *
     * <pre>
     * native check 2.namenewok.info:2096    -> dead    14:25:04
     * native check f5fe36.proxyhub.co:443   -> dead    14:25:10
     * native check 154.86.119.143:443      -> 106ms   14:25:13
     * native check 176.57.69.182:53627    -> dead    14:25:22
     * </pre>
     *
     * Six to eight seconds between verdicts, and a node that answers in a hundred milliseconds is not
     * reached until the eighth dead one has taken its turn. That is the "proxies load for ages and
     * none of them work" report exactly: the working one was in the pool the whole time, behind the
     * queue.
     *
     * <p>Widened rather than replaced -- the checker is still serial underneath, so raising this
     * queues more requests at once instead of leaving the machine idle on one timeout.
     */
    private static final int FAST_NATIVE_PARALLEL = 2;
    private static final AtomicBoolean fastNativeRunning = new AtomicBoolean(false);
    private static int fastNativeInFlight;

    private static void startFastNativeChecks() {
        if (fastNativeRunning.compareAndSet(false, true)) {
            mainHandler.post(fastNativeTick);
        }
    }

    private static final Runnable fastNativeTick = new Runnable() {
        @Override
        public void run() {
            if (appContext == null || isProxySelectionPending() || (tgnetConnectionState() == TG_STATE_CONNECTED
                    && (currentActiveProxy == null || currentActiveProxy.nativeFailures < 2))) {
                fastNativeRunning.set(false);
                return;
            }
            int launched = 0;
            for (ProxyItem item : new ArrayList<>(verifiedPool)) {
                if (fastNativeInFlight >= FAST_NATIVE_PARALLEL) break;
                if (item.type == 2 || item.isLocalDpi() || item.tcpMs < 0
                        || (item != currentActiveProxy && isBackgroundCatalogCandidate(item))
                        || item.fastProbeAttempted || item.nativeProbeInFlight.get()) continue;
                item.fastProbeAttempted = true;
                final AtomicBoolean done = new AtomicBoolean(false);
                fastNativeInFlight++;
                boolean started = checkOne(item, alive -> {
                    if (!done.compareAndSet(false, true)) return;
                    fastNativeInFlight--;
                    onVerdict(item, alive);
                    mainHandler.post(this);
                });
                if (started) {
                    launched++;
                } else {
                    item.fastProbeAttempted = false;
                    fastNativeInFlight--;
                }
            }
            if (launched > 0) Log.i(TAG, "fast native proxy checks started: " + launched);
            if (fastNativeInFlight > 0) {
                // A harvested pool is around two hundred entries and the checker is serial
                // underneath, so this tick rate set the pace of the whole list:
                //
                //   2 in flight, one verdict every 3 s  ->  ~5 minutes for a full pass
                //
                // which is the reported "every proxy says unavailable and then, minutes later,
                // many of them turn available". Nothing was wrong with the nodes: they had simply
                // not been asked yet, and the screen was reporting the absence of an answer as if
                // it were the answer. More in flight and a shorter tick puts a verdict on every
                // node inside a couple of minutes, which is the window a person will sit through.
                mainHandler.postDelayed(this, 800L);
            } else {
                fastNativeRunning.set(false);
            }
        }
    };

    /** How often the node that is currently carrying traffic earns a fresh verdict. */
    private static final long ACTIVE_RECHECK_MS = 20000L;

    /** Production Telegram endpoints, used only to answer "is Telegram reachable at all". */
    private static final String[][] TELEGRAM_DC_ENDPOINTS = {
            {"149.154.175.50", "443"},
            {"149.154.167.50", "443"},
            {"91.108.56.100", "443"},
            {"185.76.151.1", "443"},
    };

    /**
     * What kind of interference this network actually applies, measured rather than assumed.
     *
     * The two cases need opposite fixes: an IP-level drop cannot be beaten by desync at all -
     * only a relay carries it - while an SNI/DPI filter is exactly what the local listener
     * defeats. Telling them apart is the difference between a bypass that works and a button the
     * user presses while nothing can help him.
     */
    public static String describeBlockType() {
        if (telegramDirectlyReachable()) {
            return "Telegram доступен напрямую — блокировки нет";
        }
        if (localBypassUsable()) {
            for (ProxyItem p : verifiedPool) {
                if (p.isLocalDpi() && p.nativeVerified) {
                    return "SNI/DPI-фильтр — обходчик без прокси работает";
                }
            }
            return "Серверы Telegram не отвечают напрямую";
        }
        return "Серверы Telegram не отвечают напрямую";
    }

    private static volatile boolean blockedReported;

    /**
     * Say once, in the interface, why the header reads "Соединение..." forever.
     *
     * Measured here: every client DC address is dropped (70 addresses over IPv4 and the IPv6 set,
     * one live host and it is the API, not a DC), so no amount of local desync or retrying can
     * carry the connection - a proxy or a relay has to be chosen. Without a word about that the
     * only signal is a spinner, and the reasonable conclusion is that the app is broken rather
     * than that the network is.
     */
    private static void reportBlockedOnce(Context ctx) {
        if (blockedReported) return;
        blockedReported = true;
        final String text = describeBlockType()
                + ". Включи прокси (щит вверху списка чатов) или самоподключение в Настройках Colgram.";
        mainHandler.post(() -> toast(text));
        // The toast is gone in two seconds and the header keeps spinning, so the state also gets a
        // notification with a button that turns the bypass on.
        ColgramBypassNotice.showBlocked(ctx, describeBlockType());
    }

    private static final long DIRECT_ROUTE_CACHE_MS = 20000L;
    private static volatile long directRouteCheckedAt;
    private static volatile boolean directRouteReachable;
    private static final long LOCAL_ROUTE_RETRY_MS = 60000L;
    private static volatile long localRouteUnavailableUntil;

    private static synchronized boolean telegramDirectlyReachable() {
        long now = SystemClock.elapsedRealtime();
        if (directRouteCheckedAt > 0 && now - directRouteCheckedAt < DIRECT_ROUTE_CACHE_MS) {
            return directRouteReachable;
        }
        for (String[] endpoint : TELEGRAM_DC_ENDPOINTS) {
            try {
                if (testProxy(endpoint[0], Integer.parseInt(endpoint[1]), 1200) >= 0) {
                    directRouteReachable = true;
                    directRouteCheckedAt = SystemClock.elapsedRealtime();
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        directRouteReachable = false;
        directRouteCheckedAt = SystemClock.elapsedRealtime();
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
        if (ctx == null || !ColgramConfig.isBuiltinProxyEnabled() || ColgramConfig.isWarpEnabled()) return;
        ProxyItem active = currentActiveProxy;
        boolean carriesProxy = active != null && active != dcRemapItem && !active.isLocalDpi();
        if (isProxyEnabled(ctx) && carriesProxy) return;
        if (isProxyEnabled(ctx) && active == null) {
            Log.w(TAG, "proxy_enabled is set but no proxy is active; recovering from stale preference");
        }
        if (active != null && active.autoSelected && !shouldFallbackFromLocalDpi(active)) return;
        if (!telegramDirectlyReachable()) {
            reportBlockedOnce(ctx);
        }
        // He switches the proxy off by hand; a background task that switches it back on is not a
        // bypass, it is an override. Opt-in only.
        if (!ColgramConfig.isAutoProxyEnabled()) return;
        // The direct remap is the same request taken further: "connect without sitting on a
        // proxy". Auto-connecting a public node behind it would undo that, so the remap gets its
        // own window first and only escalates if Telegram still is not connected.
        if (isDcRemapActive() && !dcRemapGaveUp()) return;
        connectThroughBestNode();
    }

    /**
     * Apply the best node without consulting the auto-proxy switch.
     *
     * The remap uses this when it has *proven* the direct route dead. That is not overriding him
     * on a guess - but it must not write his switch either, or a single exhaustion would turn
     * "обход без прокси" back into "сидеть на прокси" permanently.
     */
    private static void connectThroughBestNode() {
        final long routeEpoch = ColgramRouteEpoch.snapshot();
        if (!ColgramConfig.isAutoProxyEnabled() || isUserProxyDisabled()) return;
        if (ColgramConfig.isWarpEnabled()) {
            Log.i(TAG, "WARP was selected while candidates were being checked; skipping proxy auto-connect");
            return;
        }
        // The local desync listener wins when it has actually completed a handshake: no third
        // party sees anything, and it is the only path that works with no proxy at all, which is
        // what the "анонимный обход без прокси" switch promises.
        ProxyItem chosen = null;
        if (localBypassUsable()) {
                for (ProxyItem p : new ArrayList<>(verifiedPool)) {
                if (p.isLocalDpi() && p.nativeVerified) {
                    chosen = p;
                    break;
                }
            }
        }
        // A verified remote node beats the local front when there is one.
        //
        // The local front won unconditionally whenever it was bound, and "bound" is not the same as
        // "works": isBound() only says a socket is listening. On a network that filters egress the
        // desync strategies it tries all fail, and a measured run shows the app preferring it anyway:
        //
        // 15:33:44  Applying proxy: ssh.meow0.co.uk:22 (type=1)   <- verified, 145 ms
        // 15:33:47  Applying proxy: 127.0.0.1:9876 (type=0)      <- local front, replaced it
        // 15:33:50  native check ssh.meow0.co.uk:22 -> 196 ms     <- still working, still not used
        // 15:33:52  Telegram unreachable directly; auto-connecting through ssh.meow0.co.uk:22
        //
        // and the same node is named again three seconds later without ever having been rotated away.
        // The local front keeps priority only while nothing better exists.
        if (chosen == null || (chosen.isLocalDpi() && pickVerifiedAliveNow(1) != null)) {
            ProxyItem remote = pickVerifiedAliveNow(6);
            if (remote != null && !remote.isLocalDpi()) {
                if (chosen != null) {
                    Log.i(TAG, "preferring the verified node " + remote.address + ":" + remote.port
                            + " over the local desync front, which is only bound");
                }
                chosen = remote;
            }
        }
        if (chosen == null) {
            // Live candidates only: a stale nativeVerified flag from an hour-old check made
            // the fallback dial long-dead nodes and park the header on "Соединение...".
            chosen = pickVerifiedAliveNow(6);
        }
        if (chosen == null) {
            Log.w(TAG, "no node to fall back to: pool=" + verifiedPool.size()
                    + " candidates have no successful native verdict yet");
            return;
        }
        final ProxyItem picked = chosen;
        if (!autoConnectPending.compareAndSet(false, true)) return;
        picked.autoSelected = true;
        Log.i(TAG, "Telegram unreachable directly; auto-connecting through "
                + (picked.isLocalDpi() ? "local desync bypass" : picked.address + ":" + picked.port)
                + (picked.nativeVerified ? "" : " (не проверена нативно)"));
        mainHandler.post(() -> {
            try {
                // Multiple native verdicts can arrive before this runnable applies the first
                // endpoint. Do not tear down a fresh connection for a slightly faster second.
                if (!ColgramRouteEpoch.isCurrent(routeEpoch) || !ColgramConfig.isAutoProxyEnabled()
                        || isUserProxyDisabled() || ColgramConfig.isWarpEnabled()) return;
                ProxyItem routeInUse = currentActiveProxy;
                if (routeInUse == null || shouldFallbackFromLocalDpi(routeInUse)) {
                    ColgramRouteEpoch.runIfCurrent(routeEpoch, () -> {
                        if (ColgramConfig.isAutoProxyEnabled() && !isUserProxyDisabled() && !ColgramConfig.isWarpEnabled()) forceApplyProxy(picked);
                    });
                } else {
                    Log.i(TAG, "verified fallback held; the active route is not a failed local DPI route");
                }
            } finally {
                autoConnectPending.set(false);
            }
        });
    }

    /**
     * A listening loopback SOCKS port is not a route to a Telegram DC that the carrier drops.
     * Keep local desync as the preferred private path while it can work, then allow the existing
     * opt-in failover to replace it with a protocol-verified endpoint. Previously the fallback
     * found a real proxy but the non-null loopback route prevented it from ever being applied.
     */
    private static boolean shouldFallbackFromLocalDpi(ProxyItem active) {
        if (active == null || !active.isLocalDpi() || !ColgramConfig.isAutoProxyEnabled()) return false;
        if (tgnetConnectionState() == TG_STATE_CONNECTED) return false;
        return !localBypassUsable() || active.nativeFailures >= 3
                || active.failedVerdicts >= MAX_FAILED_VERDICTS;
    }

    private static volatile ProxyItem dcRemapItem = null;

    /**
     * Turn the direct-address remap on or off.
     *
     * On: bind the loopback endpoint and hand tgnet a SOCKS5 setting that points at it, through
     * the same stock path a real proxy uses - so the plumbing, the persistence and the reconnect
     * are the ones Telegram already trusts. The entry is deliberately kept out of verifiedPool:
     * a pool entry can be rotated away on a failed verdict, and this one is not a node somebody
     * else runs.
     */
    public static void applyDcRemap(final boolean enabled) {
        Context ctx = appContext;
        if (ctx == null) return;
        if (enabled && ColgramConfig.isWarpEnabled()) {
            Log.i(TAG, "WARP is selected; skipping automatic direct-route remap");
            return;
        }
        if (!enabled) {
            if (dcRemapItem == null) return;
            setUserProxyDisabled(true);
            dcRemapItem = null;
            ColgramDcRemap.stop();
            disableProxy(ctx);
            Log.i(TAG, "DC remap switched off");
            return;
        }
        if (isUserProxyDisabled()) {
            Log.i(TAG, "DC remap auto-apply skipped after the proxy switch was turned off");
            return;
        }
        int port = ColgramDcRemap.start();
        if (port <= 0) {
            Log.w(TAG, "DC remap could not bind; nothing applied");
            return;
        }
        ProxyItem item = new ProxyItem("127.0.0.1", port, "", 0);
        dcRemapItem = item;
        lastApplyAt = 0L;                       // an explicit switch is never a duplicate
        forceApplyProxy(item);
        Log.i(TAG, "DC remap applied through 127.0.0.1:" + port);
    }

    /** Explicit settings action; background watchdog calls must preserve the user's off toggle. */
    public static void applyDcRemapFromUser(final boolean enabled) {
        if (enabled) {
            setUserProxyDisabled(false);
            if (ColgramConfig.isWarpEnabled()) {
                ColgramConfig.setWarpEnabled(false);
                ColgramWarpTunnel.bringDown(appContext);
            }
        }
        applyDcRemap(enabled);
    }

    /**
     * True while the remap is what the user asked to carry the connection.
     *
     * Deliberately not compared against currentActiveProxy: a failed native verdict clears that
     * field, and then this reported "off" while the remap was still applied - which silenced the
     * watchdog exactly when it had something to say.
     */
    public static boolean isDcRemapActive() {
        return dcRemapItem != null;
    }

    public static String describeDcRemap() {
        return ColgramDcRemap.describe();
    }

    /**
     * The notice's "Включить обход" button enables the local desync route and clears the notice.
     * It never opts the user into switching to a public proxy when that local route fails.
     */
    public static void enableBypassFromNotification(final Context context) {
        if (context == null) return;
        if (appContext == null) appContext = context.getApplicationContext();
        ColgramBypassNotice.clear(context);
        setDpiBypassEnabled(appContext, true);
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
        if (item == null) return null;
        return ColgramProxyChain.pickReachableRelay(relays, item.address, item.port, 1000);
    }

    private static final Runnable proberTick = new Runnable() {
        @Override
        public void run() {
            if (isProxySelectionPending()) {
                mainHandler.postDelayed(this, PROBE_GAP_MS);
                return;
            }
            final ProxyItem target = nextProberTarget();
            if (target == null) {
                mainHandler.postDelayed(this, PROBE_IDLE_MS * ColgramPowerGuard.intervalFactor(appContext));
                return;
            }
            final AtomicBoolean done = new AtomicBoolean(false);
            Runnable finish = () -> {
                if (!done.compareAndSet(false, true)) return;
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
                finish.run();
                return;
            }
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
        final List<ProxyItem> snapshot = new ArrayList<>(verifiedPool);
        final int size = snapshot.size();
        if (size == 0) return null;
        // The applied proxy is checked before anything else, every tick.
        //
        // The comment by the 30-second health check claims the prober re-verifies the applied node
        // "inside a two-minute window"; it does not. The prober walks the pool one entry per tick,
        // and a harvested pool is ~200 entries at ~15 s per verdict, so a full cycle is around
        // fifty minutes. Measured on 2026-09-23: seven nodes were called alive at ~100 ms while the
        // header sat on "Соединение..." - the node actually carrying the tunnel had simply not been
        // looked at since it died. Rotation is driven by that verdict, so nothing rotated.
        ProxyItem applied = currentActiveProxy;
        if (applied != null && applied.type != 2 && !applied.isLocalDpi()
                && applied.tcpMs != -2
                && now - applied.lastCheckAt >= ACTIVE_RECHECK_MS) {
            return applied;
        }
        ProxyItem tcpAlive = null;
        ProxyItem stale = null;
        for (int step = 0; step < size; step++) {
            int idx = (proberCursor + step) % size;
            ProxyItem p = snapshot.get(idx);
            // A WEB entry needs a WebView bridge per check and the pool never holds one; the
            // user's own WEB proxy is exercised where it is actually applied.
            if (p.type == 2) continue;
            if (p != applied && isBackgroundCatalogCandidate(p)) continue;
            // A TCP-dead endpoint cannot complete an MTProto handshake. Retrying it through
            // checkProxy consumed the full 12s timeout for every stale feed entry, so a mostly
            // dead 200-node list looked like it was being checked forever.
            if (p.tcpMs == -2) continue;
            if (p.nativeProbeInFlight.get()) continue;
            // The local desync listener IS worth a protocol check: it is the only candidate with
            // no third party in the path, and excluding it from probing meant it could never earn
            // a verdict, so the "no proxy" bypass was never applied and never tested.
            if (p.isLocalDpi() && !localBypassUsable()) continue;
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
            proberCursor = (snapshot.indexOf(target) + 1) % Math.max(1, size);
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
            if (isLocalRelayRoute(item)) relayFallbackFailures.set(0);
            rememberAlive();
            ProxyItem applied = currentActiveProxy;
            boolean userSelectedRoute = applied != null && applied != dcRemapItem
                    && !applied.isLocalDpi() && !applied.autoSelected;
            if (!ColgramConfig.isWarpEnabled() && !isUserProxyDisabled()
                    && item.autoSelected && isLocalRelayRoute(item) && !containsProxy(item)
                    && !telegramDirectlyReachable() && !userSelectedRoute
                    && ColgramConfig.isAutoProxyEnabled()) {
                // A full pool must not make a real, native-verified tunnel unusable.
                forceApplyProxy(item);
            }
            if (!ColgramConfig.isWarpEnabled() && !isUserProxyDisabled()
                    && applied != null && applied != item && applied.autoSelected
                    && !applied.nativeVerified && tgnetConnectionState() != TG_STATE_CONNECTED
                    && SystemClock.elapsedRealtime() - lastApplyAt >= 5000L
                    && ColgramConfig.isAutoProxyEnabled()) {
                // The startup TCP-only route was a false positive. Replace it with the
                // first endpoint that completed Telegram's own protocol check.
                item.autoSelected = true;
                forceApplyProxy(item);
            }
            // A proven node is worth connecting through right now when the direct route is
            // dead; waiting for the next scheduled sweep left the app on "connecting" for
            // minutes with a working proxy sitting in the pool. Off-main: the reachability
            // test inside opens sockets.
            executor.execute(() -> autoConnectIfBlocked());
        } else {
            item.nativeVerified = false;
            item.isAvailable = false;
            item.pingMs = -2;
            item.failedVerdicts++;
            if (item.failedVerdicts == 1 && isLocalRelayRoute(item)
                    && ColgramConfig.isAutoProxyEnabled()) {
                scheduleRelayFallbackRetry();
            }
            if (item.isLocalDpi() && item.failedVerdicts >= 3) {
                localRouteUnavailableUntil = SystemClock.elapsedRealtime() + LOCAL_ROUTE_RETRY_MS;
            }
            if (tryMimicFront(item)) return;
            if (item.failedVerdicts >= MAX_FAILED_VERDICTS) {
                item.isAvailable = false;
                item.pingMs = -2;
            }
        }
        Log.i(TAG, "native check " + item.address + ":" + item.port + " -> "
                + (alive ? item.pingMs + "ms" : "dead (" + item.failedVerdicts + ")"));
        mainHandler.post(ColgramProxyManager::publishPoolToStock);
        if (item == currentActiveProxy && !alive) {
            reportProxyFailure();
            if (!ColgramConfig.isAutoProxyEnabled() && !isInternalLoopbackEndpoint(item)) {
                disableFailedProxyRoute(ColgramRouteEpoch.snapshot());
            } else switchToNextProxy();
        }
        prunePool();
    }

    /**
     * Give a node whose FakeTLS handshake failed a second chance through the TLS mimic front.
     *
     * The TCP socket opening but the handshake dying is the signature of a TSPU that recognised
     * tgnet's fixed ClientHello - the node itself is fine. The front rewrites that hello into a
     * browser fingerprint and sends it fragmented, which is what gets public MTProxies through
     * signature-based filtering without a VPN. The verdict still comes from Telegram's own
     * checker: only a handshake that genuinely succeeds promotes the node.
     *
     * @return true when a mimic check was started (the node's fate is decided asynchronously)
     */
    private static boolean tryMimicFront(ProxyItem item) {
        if (appContext == null || item == null || item.mimicTried) return false;
        if (item.type != 1 || !item.fakeTls) return false;
        if (!ColgramConfig.isDpiBypassEnabled() || !ColgramConfig.isTlsMimicEnabled()) return false;
        item.mimicTried = true;
        // An IP-level block defeats the front too: nothing the front rewrites changes whether
        // the socket to the node can open at all. Check that cheaply before paying for a port.
        if (probeTcp(item.address, item.port, 1500) < 0) {
            Log.i(TAG, "mimic front skipped for " + item.address + ":" + item.port
                    + " (no TCP route to the node itself)");
            return false;
        }
        executor.execute(() -> {
            int port = ColgramProxyChain.openMimicFront(item.address, item.port);
            if (port <= 0) {
                Log.w(TAG, "mimic front could not bind for " + item.address);
                return;
            }
            ProxyItem shadow = new ProxyItem("127.0.0.1", port, item.secret, 1);
            shadow.autoSelected = true;
            boolean started = checkOne(shadow, alive -> onMimicVerdict(item, port, alive));
            if (!started) Log.w(TAG, "mimic check could not start for " + item.address);
        });
        return true;
    }

    private static void onMimicVerdict(ProxyItem item, int frontPort, boolean alive) {
        if (alive) {
            item.chainPort = frontPort;
            item.mimicActive = true;
            item.isAvailable = true;
            item.nativeVerified = true;
            item.lastCheckAt = SystemClock.elapsedRealtime();
            item.verifiedEndpoint = probeEndpoint(item);
            item.nativeFailures = 0;
            item.failedVerdicts = 0;
            item.pingMs = Math.max(1, item.tcpMs > 0 ? item.tcpMs : item.pingMs > 0 ? item.pingMs : 1);
            Log.i(TAG, "mimic front carries " + item.address + ":" + item.port
                    + " through 127.0.0.1:" + frontPort + " — handshake now passes");
            rememberAlive();
            publishPoolToStock();
            executor.execute(() -> autoConnectIfBlocked());
        } else {
            item.mimicActive = false;
            item.chainPort = 0;
            if (item.failedVerdicts >= MAX_FAILED_VERDICTS) {
                item.isAvailable = false;
                item.pingMs = -2;
            }
            Log.i(TAG, "mimic front did not rescue " + item.address + ":" + item.port);
        }
    }

    /**
     * Drop nodes that have failed enough times to stop being interesting. The user's own applied
     * proxy is never dropped, and the harvest re-adds anything the lists still advertise.
     */
    private static void prunePool() {        for (int i = verifiedPool.size() - 1; i >= 0; i--) {
            ProxyItem p = verifiedPool.get(i);
            // The active entry is kept so its failure can be read; the local desync front is kept
            // because it is not a candidate to begin with.
            if (p == currentActiveProxy || p.isLocalDpi()) continue;
            // Everything else goes once it has failed enough times -- and it goes into a quarantine
            // with a deadline rather than out of memory.
            //
            // Removal was final, and that is what "all proxies are unavailable" is made of: every node
            // in a public pool is down at some moment, a node is judged on three samples, and after
            // that it was never asked again. A pool that churns that way empties itself within a few
            // minutes and stays empty, which is a fact about the network reported as a fault in the
            // checker. The endpoint is also remembered in harvestedEndpoints, so findSavedProxy no
            // longer hands it back as a user-entered proxy - the two problems are closed together.
            //
            // The old comment here described an unrelated bug it fixed at the same time - a relay front
            // on an ephemeral loopback port being re-checked forever - which is why isLocalDpi() was
            // left out of the loop. That exclusion is kept.
            if (p.failedVerdicts >= MAX_FAILED_VERDICTS) {
                p.failedVerdicts = 0;
                p.nativeFailures = 0;
                p.isAvailable = false;
                p.pingMs = -1;
                // Back in the rotation, minutes later rather than never. A node that has come back
                // once and failed again is on the same schedule as one that never did, and the counter
                // above is what stops that from being a loop.
                p.nextEligibleAt = SystemClock.elapsedRealtime() + NODE_QUARANTINE_MS;
                verifiedPool.remove(i);
                quarantined.add(p);
                if (quarantined.size() > MAX_QUARANTINED) quarantined.remove(0);
                if (proberCursor > 0) proberCursor--;
            }
        }
        releaseQuarantine();
    }

    /**
     * Nodes that failed their run, waiting out a cooldown before they are asked again.
     *
     * <p>Bounded, so a pool made entirely of dead endpoints cannot grow without limit; the oldest is
     * dropped and the harvest brings it back if the lists still carry it.
     */
    private static final java.util.List<ProxyItem> quarantined =
            java.util.Collections.synchronizedList(new java.util.ArrayList<ProxyItem>());
    private static final int MAX_QUARANTINED = 400;
    private static final long NODE_QUARANTINE_MS = 5L * 60L * 1000L;

    private static void releaseQuarantine() {
        if (quarantined.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (quarantined) {
            java.util.Iterator<ProxyItem> it = quarantined.iterator();
            while (it.hasNext()) {
                ProxyItem p = it.next();
                if (p.nextEligibleAt > now) continue;
                it.remove();
                // Back into the pool with a clean slate, so it is judged afresh rather than on the
                // three failures that put it here.
                p.appliedOnce = false;
                synchronized (verifiedPool) {
                    if (!containsProxy(p)) verifiedPool.add(p);
                }
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
            for (ProxyItem p : new ArrayList<>(verifiedPool)) {
                // Chain fronts use ephemeral in-process ports; never restore them after a reboot.
                if (!p.isAvailable || p.isLocalDpi() || p.type == 2
                        || isInternalLoopbackEndpoint(p)) continue;
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
            boolean removedStaleLoopback = false;
            StringBuilder sanitized = new StringBuilder();
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
                if (isInternalLoopbackEndpoint(p)) {
                    removedStaleLoopback = true;
                    Log.i(TAG, "discarding stale process-local proxy cache entry");
                    continue;
                }
                if (parts.length == 5) {
                    try {
                        p.pingMs = Integer.parseInt(parts[4]);
                    } catch (Throwable ignored) {}
                }
                // A previous run's verdict is a recheck hint, not current availability. Marking
                // it live made dead remembered nodes outrank newly TCP-reachable candidates.
                p.isAvailable = false;
                p.lastCheckAt = staleBefore;
                addCandidate(p);
                if (sanitized.length() > 0) sanitized.append('\n');
                sanitized.append(line);
            }
            if (removedStaleLoopback) {
                ctx.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_ALIVE, sanitized.toString()).apply();
            }
        } catch (Throwable ignored) {}
    }

    /** How long a remembered verdict is trusted before the prober re-checks it. */
    private static final long REMEMBERED_VALID_MS = 180_000L;

    private static final java.util.Map<ProxyItem, Object> nativeProbeOwners =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static boolean finishNativeProbe(ProxyItem item, Object owner) {
        synchronized (nativeProbeOwners) {
            if (nativeProbeOwners.get(item) != owner) return false;
            nativeProbeOwners.remove(item);
            item.nativeProbeInFlight.set(false);
            return true;
        }
    }

    private static boolean checkOne(final ProxyItem item, final AvailabilityHandler onResult) {
        final Object owner = new Object();
        synchronized (nativeProbeOwners) {
            // Leave native slots available for Telegram's own screen and never queue six
            // checks behind its four physical check connections.
            if (nativeProbeOwners.size() >= FAST_NATIVE_PARALLEL
                    || !item.nativeProbeInFlight.compareAndSet(false, true)) return false;
            nativeProbeOwners.put(item, owner);
        }
        final String checkedEndpoint = probeEndpoint(item);
        final long checkedNetwork = networkProbeEpoch.get();
        final Runnable timeout = () -> {
            if (!finishNativeProbe(item, owner)) return;
            item.pingMs = -2;
            onResult.onResult(false);
        };
        try {
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> rtdClass = Class.forName("org.telegram.tgnet.RequestTimeDelegate");
            final Object settings = buildProxySettings(item);
            if (settings == null) {
                finishNativeProbe(item, owner);
                return false;
            }

            final Object delegate = java.lang.reflect.Proxy.newProxyInstance(
                    rtdClass.getClassLoader(),
                    new Class<?>[]{rtdClass},
                    (proxy, method, args) -> {
                        if ("run".equals(method.getName())) {
                            final long time = (args == null || args.length == 0
                                    || !(args[0] instanceof Number)) ? -1L : ((Number) args[0]).longValue();
                            mainHandler.post(() -> {
                                if (!finishNativeProbe(item, owner)) return;
                                mainHandler.removeCallbacks(timeout);
                                // Only the measurement belongs here; onVerdict owns the state it
                                // feeds, so a verdict cannot be recorded one way by the probe and
                                // another way by the caller.
                                boolean alive = time >= 0 && checkedNetwork == networkProbeEpoch.get()
                                        && checkedEndpoint.equals(probeEndpoint(item));
                                item.pingMs = alive ? (int) Math.min(Integer.MAX_VALUE, Math.max(0L, time)) : -2;
                                if (alive) item.verifiedEndpoint = checkedEndpoint;
                                onResult.onResult(alive);
                            });
                        }
                        return null;
                    });

            Object cm = cmClass.getMethod("getInstance", int.class).invoke(null, 0);
            cmClass.getMethod("checkProxy", settings.getClass(), rtdClass).invoke(cm, settings, delegate);
            // Native checks can remain pending on a silent peer; every caller needs the
            // same bounded failure, including mimic/private-route checks outside the sweeps.
            mainHandler.postDelayed(timeout, NATIVE_CHECK_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            mainHandler.removeCallbacks(timeout);
            finishNativeProbe(item, owner);
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
        return buildProxySettings(item, true);
    }

    /**
     * Build the public proxy identity for Telegram's saved list. Runtime routes may use a
     * process-local SOCKS front at 127.0.0.1, but a saved list entry must point at the actual
     * upstream proxy so it remains meaningful after this process exits.
     */
    private static Object buildProxyListSettings(ProxyItem item) {
        if (item == null || isLoopbackHost(item.address)) return null;
        return buildProxySettings(item, false);
    }

    private static Object buildProxySettings(ProxyItem item, boolean useEffectiveEndpoint) {
        try {
            Class<?> psClass = Class.forName("org.telegram.utils.proxy.ProxySettings");
            Class<?> pstClass = Class.forName("org.telegram.utils.proxy.ProxySettings$Type");
            String typeName = item.type == 2 ? "WEB" : (item.type == 1 ? "MTPROTO" : "SOCKS5");
            Object typeObj = Enum.valueOf((Class<Enum>) pstClass, typeName);

            Object builder = psClass.getDeclaredMethod("builder").invoke(null);
            Class<?> bClass = builder.getClass();
            bClass.getDeclaredMethod("setType", pstClass).invoke(builder, typeObj);
            bClass.getDeclaredMethod("setAddress", String.class).invoke(builder,
                    useEffectiveEndpoint ? item.effectiveHost() : item.address);
            bClass.getDeclaredMethod("setPort", int.class).invoke(builder,
                    useEffectiveEndpoint ? item.effectivePort() : item.port);
            bClass.getDeclaredMethod("setSecret", String.class)
                    .invoke(builder, item.secret == null ? "" : item.secret);
            bClass.getDeclaredMethod("setUser", String.class).invoke(builder, item.user);
            bClass.getDeclaredMethod("setPassword", String.class).invoke(builder, item.password);
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
    /**
     * Minimum gap between two proxy switches.
     *
     * <p>Thirty seconds, with eight rotations allowed per five-minute window, is a contradiction that
     * reads as "it tries for a while and then gives up": eight changes at thirty seconds each consume
     * the entire budget in four minutes, the ninth is refused, and the app then holds a dead route
     * until the window rolls over. Measured on a pool of thirty with a dozen verified members, every
     * one of which had just answered a protocol check:
     *
     * <pre>
     * 14:50:37  auto-connecting through 154.86.119.143:443
     * 14:50:37  auto-connecting through ssh.meow0.co.uk:22
     * 14:50:38  auto-connecting through px.cryptocurency.wiki:443
     * 14:51:37  auto-connecting through px.cryptocurency.wiki:443   <- the same one, a minute later
     * </pre>
     *
     * Four verified proxies were available at that moment and the app was still cycling between two of
     * them, which is the "the servers do not answer" report: they did answer, and the rotation budget
     * was spent before it reached them.
     *
     * <p>Long enough to avoid a storm, short enough to finish a pass. The existing rotation budget is
     * what actually bounds the rate; this only needs to keep a failed connect from turning into a
     * tight loop.
     */
    private static final long ROTATION_DEBOUNCE_MS = 2500L;

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
    /**
     * Rotations allowed per window.
     *
     * <p>Eight, against a pool that is routinely thirty to two hundred and thirty entries, is not a
     * pass over the pool -- it is a fifth of one, spent in the first four minutes of a five minute
     * window. Raised so a sweep can actually reach the verified entries that arrive after it.
     */
    private static final int MAX_ROTATIONS_PER_WINDOW = 24;
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
    /**
     * Whether the stock "proxy unavailable" alert belongs on his screen right now.
     *
     * It does when the failing endpoint is one he put there himself - then the dialog is
     * information he needs. It does not when the endpoint was chosen by the bypass: the rotation
     * that follows is already fixing it, and a dialog on top of a self-healing transport is what
     * made the bypass feel broken ("какого хуя ошибка, я ничего не трогал").
     */
    public static boolean shouldShowProxyAlert() {
        ProxyItem active = currentActiveProxy;
        if (active == null) return true;
        if (active.autoSelected || active.isLocalDpi() || active == dcRemapItem) return false;
        return true;
    }

        /**
     * Called when Telegram reports it is not connected and a proxy is applied.
     *
     * <p>The rotation used to be driven only by a node failing its own protocol check, which meant a
     * node that answers the check but cannot actually carry a session was never replaced: the app
     * stayed on it, and every re-connect attempt named the same address. Measured:
     *
     * <pre>
     * 15:33:44  native check ssh.meow0.co.uk:22 -> 145 ms
     * 15:33:44  auto-connecting through ssh.meow0.co.uk:22
     * 15:33:50  auto-connecting through ssh.meow0.co.uk:22
     * 15:33:52  auto-connecting through ssh.meow0.co.uk:22
     * 15:33:54  auto-connecting through ssh.meow0.co.uk:22
     * </pre>
     *
     * Four attempts on one node, and the node passing its check each time. Passing a protocol check
     * and carrying a session are different facts, and only the second one was being asked about --
     * never, so the answer was always yes.
     */
    public static void onTelegramNotConnected() {
        Context ctx = appContext;
        if (ctx == null) return;
        if (tgnetConnectionState() == TG_STATE_CONNECTED) return;
        if (currentActiveProxy == null) return;
        if (isDcRemapActive() && !ColgramConfig.isAutoProxyEnabled()) return;
        final ProxyItem stuck = currentActiveProxy;
        Log.i(TAG, "not connected through " + stuck.address + ":" + stuck.port
                + "; counting it and rotating rather than retrying the same node");
        executor.execute(() -> {
            stuck.nativeFailures++;
            if (stuck.nativeFailures >= 2) {
                stuck.isAvailable = false;
                stuck.pingMs = -2;
            }
            switchToNextProxy(true);
        });
    }

public static void reportProxyFailure() {
        ProxyItem active = currentActiveProxy;
        if (active == null) return;
        if (active.isLocalDpi()) {
            // The local bypass used to be exempt from failure counting, so when it could not
            // carry traffic the app just sat on "Соединение..." with no explanation. Three
            // native failures through it is the measurement that says what kind of block this
            // is - and the user gets told instead of watching a spinner.
            active.nativeFailures++;
            if (active.nativeFailures == 3) {
                // The measurement, recorded honestly: this network refuses the destination
                // outright, so no amount of packet shaping from Java will carry it. The bypass is
                // not swapped for a relay here - that would make it the very thing it claims not
                // to be. It says so and stops.
                Log.w(TAG, "local desync bypass failed three handshakes: " + describeBlockType());
            }
            return;
        }
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
        final long routeEpoch = ColgramRouteEpoch.snapshot();
        Context rotateCtx = appContext;
        if (!force && ColgramConfig.isWarpEnabled()) {
            Log.i(TAG, "WARP is selected; background proxy rotation is suppressed");
            return;
        }
        if (!force && currentActiveProxy != null && currentActiveProxy.isLocalDpi()
                && !ColgramConfig.isAutoProxyEnabled()) {
            Log.i(TAG, "local DPI route failed; holding it without automatic public-proxy fallback");
            return;
        }
        if (isDcRemapActive() && !ColgramConfig.isAutoProxyEnabled()) {
            // The remap failing its native verdict means "no Telegram address answers here", not
            // "try somebody else's proxy". Rotating would replace the route he picked with a
            // public node - the exact behaviour that made "it switches my proxy behind my back"
            // a complaint. The watchdog reports the verdict instead.
            Log.i(TAG, "direct remap is active; not replacing it with a pool proxy");
            return;
        }
        if (rotateCtx != null && !isProxyEnabled(rotateCtx) && !ColgramConfig.isAutoProxyEnabled()) {
            // The proxy is off because the user put it off. Rotation exists to keep a running
            // tunnel alive; with nothing applied it would silently turn the proxy back on.
            Log.i(TAG, "proxy is switched off by the user; not rotating or applying anything");
            return;
        }
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
            disableFailedProxyRoute(routeEpoch);
            return;
        }

        // Hold a user-configured node unless automatic recovery is enabled and the node has
        // actually failed. A permanent hold left Telegram spinning on a dead custom proxy.
        if (currentActiveProxy != null && currentActiveProxy != dcRemapItem
                && !verifiedPool.contains(currentActiveProxy)) {
            if (isStaleLocalHop(currentActiveProxy)) {
                // A loopback endpoint from a previous process is not a choice the user made.
                // Letting rotation treat it as one parked the app on a dead 127.0.0.1 forever.
                Log.w(TAG, "applied entry " + currentActiveProxy.address + ":"
                        + currentActiveProxy.port + " is a local hop from an earlier process;"
                        + " rotating away from it");
                currentActiveProxy = null;
            } else if (!ColgramConfig.isAutoProxyEnabled()
                    || (!force && currentActiveProxy.nativeFailures < 2
                    && currentActiveProxy.failedVerdicts < 2)) {
                Log.i(TAG, "holding user-configured proxy " + currentActiveProxy.address
                        + " (type=" + currentActiveProxy.type + "); it is not in the managed pool");
                return;
            } else {
                Log.i(TAG, "failed user-configured proxy " + currentActiveProxy.address
                        + "; trying verified fallback without changing saved settings");
            }
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
                && localBypassUsable()
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
            disableFailedProxyRoute(routeEpoch);
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
        // Only protocol-verified endpoints can carry the automatic route. A TCP-open
        // host that has never completed Telegram's check is not a working proxy.
        ProxyItem next = selectProxy(currentActiveProxy);
        if (next == null) {
            next = roundRobinNext(currentIndex);
        }
        if (next == null) {
            // Every verdict-based candidate is gone: dial-test live ones before declaring
            // the pool empty and parking on "Соединение..." until the next harvest.
            next = pickVerifiedAliveNow(4);
            if (next != null) next.autoSelected = true;
        }
        if (next == null) {
            Log.w(TAG, "no verified rotation candidate; releasing failed route and re-fetching");
            disableFailedProxyRoute(routeEpoch);
            executor.execute(() -> {
                try {
                    fetchAndVerifyAllSources();
                } catch (Throwable ignored) {}
            });
            return;
        }
        Log.d(TAG, "Rotating proxy to: " + next);
        // The rotator chose this endpoint, not he did - so it is not written to his settings and
        // his own saved entry survives the rotation to be restored on the next start.
        next.autoSelected = true;

        final ProxyItem target = next;
        mainHandler.post(() -> ColgramRouteEpoch.runIfCurrent(routeEpoch, () -> {
            if (ColgramConfig.isWarpEnabled() || (!force && (isUserProxyDisabled()
                    || (!ColgramConfig.isAutoProxyEnabled() && (appContext == null || !isProxyEnabled(appContext)))))) return;
            forceApplyProxy(target);
            if (appContext != null) {
                try {
                    Toast.makeText(appContext, "Сеть: " + target.toString(), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        }));
    }

    private static void disableFailedProxyRoute(long routeEpoch) {
        mainHandler.post(() -> ColgramRouteEpoch.runIfCurrent(routeEpoch, () -> {
            ProxyItem failed = currentActiveProxy;
            if (failed == null || isInternalLoopbackEndpoint(failed)
                    || isFreshProtocolVerified(failed)) return;
            disableProxy(appContext);
            proxySelectionStatus = "Прокси недоступен; соединение через него выключено";
            toast(proxySelectionStatus);
            notifyProxySettingsChanged();
        }));
    }

    /** The in-process 127.0.0.1 DPI-bypass entry, if the pool has one. */
    private static ProxyItem findLocalDpiProxy() {
        for (ProxyItem p : new ArrayList<>(verifiedPool)) {
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
        return ColgramConfig.isDpiBypassEnabled() && ColgramDpiBypass.isBound()
                && SystemClock.elapsedRealtime() >= localRouteUnavailableUntil;
    }

    /**
     * Best verified pool entry to apply right now, preferring fake-TLS and then latency.
     *
     * It used to return the first alive entry in insertion order, which on a pool this size means
     * "whichever host happened to be added first", not "the fastest one that works".
     */
    private static ProxyItem selectProxy(ProxyItem skip) {
        ProxyItem best = null;
        // Same hazard as pickVerifiedAliveNow: index-walking a list the sweep prunes from another
        // thread skips entries and throws ConcurrentModificationException the moment the size moves
        // under the loop. A snapshot makes the walk safe without synchronising every caller.
        for (ProxyItem p : new ArrayList<>(verifiedPool)) {
            if (p.equals(skip)) continue;
            if (p.isLocalDpi() && !localBypassUsable()) continue;
            if (isFreshProtocolVerified(p)) {
                if (best == null || betterCandidate(p, best)) best = p;
            }
        }
        return best;
    }

    private static String probeEndpoint(ProxyItem proxy) {
        return proxy.type + "|" + (proxy.baseFront == null ? proxy.effectiveHost() : proxy.address)
                + "|" + (proxy.baseFront == null ? proxy.effectivePort() : proxy.port)
                + "|" + proxy.secret + "|" + proxy.user + "|" + proxy.password;
    }

    private static boolean isFreshProtocolVerified(ProxyItem proxy) {
        return proxy != null && proxy.verifiedEndpoint.equals(probeEndpoint(proxy))
                && ColgramProxyPolicy.isSelectable(rankingState(proxy), proxy.lastCheckAt,
                SystemClock.elapsedRealtime(), REMEMBERED_VALID_MS);
    }

    /** Ordinary public choices never silently introduce an additional relay or local front. */
    private static boolean isPubliclySelectable(ProxyItem proxy) {
        return proxy != null && !isInternalLoopbackEndpoint(proxy) && proxy.chainRelay == null
                && proxy.chainPort == 0 && !proxy.mimicActive && isFreshProtocolVerified(proxy);
    }

    public static List<ProxyItem> getSelectableProxies() {
        List<ProxyItem> result = new ArrayList<>();
        for (ProxyItem proxy : new ArrayList<>(verifiedPool)) {
            if (isPubliclySelectable(proxy)) result.add(proxy);
        }
        sortCandidates(result);
        return result;
    }

    /**
     * Native-verified first, then WebSocket bridges (they ride a CDN and survive IP blocks),
     * then fake-TLS, then lower latency. A node that has actually completed a protocol handshake
     * beats one that merely accepted a socket: "found a working proxy but never connects" was the
     * rotator offering TCP-open nodes that had never spoken MTProto.
     */
    private static ColgramProxyPolicy.State rankingState(ProxyItem item) {
        return new ColgramProxyPolicy.State(item.nativeVerified, item.isAvailable, item.pingMs,
                item.tcpMs, item.nextEligibleAt, item.address, item.port, item.type);
    }

    private static boolean betterCandidate(ProxyItem a, ProxyItem b) {
        int comparison = ColgramProxyPolicy.compare(rankingState(a), rankingState(b), SystemClock.elapsedRealtime());
        return comparison < 0 || (comparison == 0 && a.secret.compareTo(b.secret) < 0);
    }

    private static void sortCandidates(List<ProxyItem> candidates) {
        final long now = SystemClock.elapsedRealtime();
        final java.util.IdentityHashMap<ProxyItem, ColgramProxyPolicy.State> states = new java.util.IdentityHashMap<>();
        for (ProxyItem item : candidates) states.put(item, rankingState(item));
        candidates.sort((a, b) -> {
            int comparison = ColgramProxyPolicy.compare(states.get(a), states.get(b), now);
            return comparison != 0 ? comparison : a.secret.compareTo(b.secret);
        });
    }

    /** Next protocol-verified entry after {@code currentIndex}. */
    private static ProxyItem roundRobinNext(int currentIndex) {
        List<ProxyItem> snapshot = new ArrayList<>(verifiedPool);
        int size = snapshot.size();
        for (int step = 1; step <= size; step++) {
            ProxyItem p = snapshot.get(Math.floorMod(currentIndex + step, size));
            if (p.equals(currentActiveProxy)) continue;
            if (p.isLocalDpi() && !localBypassUsable()) continue;
            if (!isFreshProtocolVerified(p)) continue;
            return p;
        }
        return null;
    }

    /** Harvest public routes, then let Telegram verify candidates before any auto-connect. */
    private static void fetchAndVerifyAllSources() {
        fetchAndVerifyAllSources(false);
    }

    private static void fetchAndVerifyAllSources(boolean foreground) {
        if (!fetchingSources.compareAndSet(false, true)) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if (lastSourceFetchAt != 0 && now - lastSourceFetchAt < (foreground ? 15_000L : 60_000L)) return;
            lastSourceFetchAt = now;
            if (appContext != null) {
                SharedPreferences prefs = appContext.getSharedPreferences("colgram_sources", Context.MODE_PRIVATE);
                sourceRound = prefs.getInt("round", 0);
                prefs.edit().putInt("round", sourceRound == Integer.MAX_VALUE ? 0 : sourceRound + 1).apply();
            }
            final int before = verifiedPool.size();
            fetchSourceFeedsConcurrently();
            Log.i(TAG, "proxy harvest: " + before + " -> " + verifiedPool.size()
                    + " unverified candidates; HTTPS feeds=" + lastSourceBatch.available.get()
                    + "/" + lastSourceBatch.total);
            mainHandler.post(() -> {
                startProber();
                publishPoolToStock();
                if (!isProxySelectionPending()) sweepFast(null);
            });
            if (!foreground && ColgramConfig.isAutoProxyEnabled()
                    && harvestingRelays.compareAndSet(false, true)) {
                executor.execute(() -> {
                    try {
                        if (ColgramConfig.isAutoProxyEnabled()) harvestRelays();
                    } finally {
                        harvestingRelays.set(false);
                    }
                });
            }
        } finally {
            fetchingSources.set(false);
        }
    }

    private static final class SourceBatch {
        final int total;
        final java.util.concurrent.atomic.AtomicInteger available = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger parsed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.ConcurrentHashMap<String, String> outcomes = new java.util.concurrent.ConcurrentHashMap<>();

        SourceBatch(int total) { this.total = total; }
    }

    private interface SourceParser { int parse(String sourceUrl, String body, int limit); }

    private static Runnable feedTask(SourceBatch batch, String url, int limit, SourceParser parser) {
        return () -> {
            try {
                String body = httpGet(url);
                if (Thread.currentThread().isInterrupted()) return;
                batch.available.incrementAndGet();
                int count = parser.parse(url, body, limit);
                if (count > 0) batch.parsed.incrementAndGet();
                batch.outcomes.put(url, count > 0 ? "OK · кандидатов " + count : "EMPTY_OR_UNSUPPORTED_FORMAT");
                Log.i(TAG, "PROXY_SOURCE " + url + " parsed=" + count);
            } catch (IOException failure) {
                batch.outcomes.put(url, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                Log.i(TAG, "proxy feed unavailable: " + url + " (" + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage() + ")");
            } finally {
                batch.finished.incrementAndGet();
            }
        };
    }

    /** Start independent providers early and make parsed candidates available without a batch join. */
    private static void fetchSourceFeedsConcurrently() {
        final SourceBatch batch = new SourceBatch(PROXY_SOURCES_MTPROTO_LINKS.length
                + PROXY_SOURCES_MTPROTO_JSON.length + PROXY_SOURCES_SOCKS.length);
        lastSourceBatch = batch;
        final List<Runnable> tasks = new ArrayList<>();
        int count = Math.max(PROXY_SOURCES_SOCKS.length, PROXY_SOURCES_MTPROTO_LINKS.length);
        for (int i = 0; i < count; i++) {
            if (i < PROXY_SOURCES_MTPROTO_LINKS.length) tasks.add(feedTask(batch,
                    PROXY_SOURCES_MTPROTO_LINKS[i], 48, ColgramProxyManager::fetchProxiesLinkList));
            if (i < PROXY_SOURCES_SOCKS.length) tasks.add(feedTask(batch,
                    PROXY_SOURCES_SOCKS[i], 48, ColgramProxyManager::fetchSocksList));
        }
        for (String url : PROXY_SOURCES_MTPROTO_JSON)
            tasks.add(feedTask(batch, url, 48, ColgramProxyManager::fetchProxiesJson));
        runSourceFetchTasks(tasks, "proxy feed");
        for (String url : PROXY_SOURCES_MTPROTO_LINKS) batch.outcomes.putIfAbsent(url, "TIMEOUT (batch deadline)");
        for (String url : PROXY_SOURCES_MTPROTO_JSON) batch.outcomes.putIfAbsent(url, "TIMEOUT (batch deadline)");
        for (String url : PROXY_SOURCES_SOCKS) batch.outcomes.putIfAbsent(url, "TIMEOUT (batch deadline)");
    }

    public static String getSourceDiagnostics() {
        SourceBatch batch = lastSourceBatch;
        if (batch == null) return "Источники ещё не загружались";
        StringBuilder result = new StringBuilder("HTTPS: ").append(batch.available.get()).append('/')
                .append(batch.total).append(" · с кандидатами: ").append(batch.parsed.get());
        java.util.TreeMap<String, String> ordered = new java.util.TreeMap<>(batch.outcomes);
        for (java.util.Map.Entry<String, String> row : ordered.entrySet())
            result.append("\n\n").append(row.getKey()).append("\n").append(row.getValue());
        return result.toString();
    }

    private static int selectFeedWindow(List<ProxyItem> candidates, int limit) {
        for (int index : ColgramProxyPolicy.feedIndices(candidates.size(), limit, sourceRound))
            addFeedCandidate(candidates.get(index));
        return candidates.size();
    }

    /**
     * Independent HTTPS feed requests used to block each other in a serial loop. Run the
     * bounded feed batch together so one stalled source no longer delays every healthy one.
     */
    private static void runSourceFetchTasks(List<Runnable> tasks, String label) {
        if (tasks == null || tasks.isEmpty()) return;
        final CountDownLatch finished = new CountDownLatch(tasks.size());
        final List<java.util.concurrent.Future<?>> submitted = new ArrayList<>(tasks.size());
        for (final Runnable task : tasks) {
            submitted.add(sourceFetchers.submit(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    Log.w(TAG, label + " task failed", t);
                } finally {
                    finished.countDown();
                }
            }));
        }
        try {
            if (!finished.await(SOURCE_BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.i(TAG, label + " batch deadline: unfinished=" + finished.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, label + " batch interrupted");
        } finally {
            for (java.util.concurrent.Future<?> future : submitted) {
                if (!future.isDone()) future.cancel(true);
            }
        }
    }

    /**
     * Refresh feeds from a persistent OS job when Android has stopped the app process.
     * Parsing, TCP screening, and native Telegram verdicts use the same pipeline as foreground
     * refreshes, so background candidates are measured rather than trusted from a source list.
     */
    public static void refreshProxySourcesInBackground(final Context context, final Runnable onComplete) {
        final Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (appContext == null) appContext = ctx;
        ColgramConfig.init(ctx);
        if (!ColgramConfig.isBuiltinProxyEnabled()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        mainHandler.post(() -> beginScheduledCatalogCheck(onComplete));
    }

    /**
     * Pull tunnel lists and keep the ones that answer from here. A relay is only useful if this
     * machine can open a socket to it, so each candidate gets one 1.2 s TCP check before it is
     * kept; whether it can then reach a blocked node is discovered per chain, in pickRelay.
     */
    private static void harvestRelays() {
        final List<List<ColgramProxyChain.Relay>> foundBySource = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        for (int index : ColgramProxyPolicy.feedIndices(RELAY_SOURCES_HTTP.length, MAX_STARTUP_RELAY_FEEDS, sourceRound)) {
            final String feed = RELAY_SOURCES_HTTP[index];
            final List<ColgramProxyChain.Relay> source = new ArrayList<>();
            foundBySource.add(source);
            tasks.add(() -> collectRelayFeed(feed, false, source));
        }
        for (int index : ColgramProxyPolicy.feedIndices(RELAY_SOURCES_SOCKS.length, MAX_STARTUP_RELAY_FEEDS, sourceRound)) {
            final String feed = RELAY_SOURCES_SOCKS[index];
            final List<ColgramProxyChain.Relay> source = new ArrayList<>();
            foundBySource.add(source);
            tasks.add(() -> collectRelayFeed(feed, true, source));
        }
        runSourceFetchTasks(tasks, "relay feed");
        for (int i = 0; i < foundBySource.size(); i++) {
            List<ColgramProxyChain.Relay> source = foundBySource.get(i);
            synchronized (source) {
                foundBySource.set(i, new ArrayList<>(source));
            }
        }
        final List<ColgramProxyChain.Relay> found = new ArrayList<>();
        for (int offset = 0; found.size() < MAX_RELAYS; offset++) {
            boolean foundAtOffset = false;
            for (List<ColgramProxyChain.Relay> source : foundBySource) {
                if (offset >= source.size()) continue;
                foundAtOffset = true;
                ColgramProxyChain.Relay candidate = source.get(offset);
                if (!found.contains(candidate)) found.add(candidate);
                if (found.size() >= MAX_RELAYS) break;
            }
            if (!foundAtOffset) break;
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
        int pendingChecks = 0;
        for (ColgramProxyChain.Relay relay : snapshot) {
            if (relay.rttMs < 0) pendingChecks++;
        }
        if (pendingChecks == 0) {
            relayDiscoveryFinished();
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger relayChecksRemaining =
                new java.util.concurrent.atomic.AtomicInteger(pendingChecks);
        for (final ColgramProxyChain.Relay relay : snapshot) {
            if (relay.rttMs >= 0) continue;
            sweeper.execute(() -> {
                try {
                    int rtt = testProxy(relay.host, relay.port, 1200);
                    relay.rttMs = rtt;
                    relay.dead = rtt < 0;
                } finally {
                    if (relayChecksRemaining.decrementAndGet() == 0) relayDiscoveryFinished();
                }
            });
        }
    }

    private static void collectRelayFeed(String url, boolean socks, List<ColgramProxyChain.Relay> found) {
        try {
            List<ColgramProxyChain.Relay> source = new ArrayList<>();
            collectRelays(httpGet(url), socks, source, url.contains("/zloi-user/hideip.me/"));
            synchronized (found) {
                found.addAll(source);
            }
        } catch (Throwable t) {
            Log.w(TAG, "relay source failed: " + url + " (" + t.getMessage() + ")");
        }
    }

    /** Do not sweep blocked proxies against relays whose own reachability check is still pending. */
    private static void relayDiscoveryFinished() {
        // The TCP sweep above is parallel. Once it finishes, re-run candidate chaining and check
        // the relays for a real Telegram route; the earlier feed sweep may have raced these checks
        // and marked every blocked candidate dead before a single relay had a verdict.
        mainHandler.post(() -> {
            startProber();
            publishPoolToStock();
            sweepFast(null);
        });
        scheduler.schedule(ColgramProxyManager::probeRelayFallbacks, 5, TimeUnit.SECONDS);
    }

    /**
     * If public MTProto nodes are all blocked from this network, test reachable HTTP/SOCKS
     * relays against Telegram's DCs and expose one verified route as a local SOCKS5 endpoint.
     * Telegram's own native checkProxy then decides whether that route is usable before it is
     * ever selected.
     */
    private static void probeRelayFallbacks() {
        if (appContext == null || ColgramConfig.isWarpEnabled()
                || !relayFallbackProbeRunning.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                List<ColgramProxyChain.Relay> relays = getReachableRelays();
                if (relays.isEmpty()) {
                    Log.i(TAG, "no reachable public relay could open a Telegram DC tunnel");
                    return;
                }
                int start = Math.floorMod(relayFallbackCursor.get(), relays.size());
                for (int relayStep = 0; relayStep < relays.size(); relayStep++) {
                    int relayIndex = (start + relayStep) % relays.size();
                    ColgramProxyChain.Relay relay = relays.get(relayIndex);
                    if (relay.dead) continue;
                    for (String[] endpoint : TELEGRAM_DC_ENDPOINTS) {
                        int rtt = ColgramProxyChain.probe(relay, endpoint[0],
                                Integer.parseInt(endpoint[1]), 2500);
                        if (rtt < 0) continue;
                        int localPort = ColgramProxyChain.openSocks5Front(relay);
                        if (localPort <= 0) continue;

                        ProxyItem candidate = new ProxyItem("127.0.0.1", localPort, "", 0);
                        candidate.autoSelected = true;
                        candidate.chainRelay = relay;
                        candidate.tcpMs = rtt;
                        candidate.tcpCheckedAt = SystemClock.elapsedRealtime();
                        boolean added = addCandidate(candidate);
                        ProxyItem route = findPoolCandidate(candidate);
                        if (route == null && !added && removeDormantRelayRoute()) {
                            added = addCandidate(candidate);
                            route = findPoolCandidate(candidate);
                        }
                        if (route == null && added) route = candidate;
                        if (route == null) {
                            relayFallbackCursor.set((relayIndex + 1) % relays.size());
                            // Do not claim a TCP-open front is a usable route when its reserved
                            // slot belongs to a protocol-verified bridge.
                            Log.i(TAG, "Telegram DC route found, but the reserved relay-route slot is occupied");
                            return;
                        }
                        route.chainRelay = relay;
                        route.autoSelected = true;
                        route.tcpMs = rtt;
                        route.tcpCheckedAt = SystemClock.elapsedRealtime();
                        relayFallbackCursor.set((relayIndex + 1) % relays.size());
                        if (added) {
                            synchronized (verifiedPool) {
                                int index = verifiedPool.indexOf(route);
                                if (index > 1) {
                                    verifiedPool.remove(index);
                                    verifiedPool.add(1, route);
                                }
                                proberCursor = 1;
                            }
                        }
                        Log.i(TAG, "Telegram DC route found through " + relay
                                + "; native verification "
                                + (route.nativeVerified ? "already passed" : "queued for local relay route"));
                        final ProxyItem verifiedRoute = route;
                        mainHandler.post(() -> {
                            if (!verifiedRoute.nativeVerified && !verifiedRoute.nativeProbeInFlight.get()
                                    && !checkOne(verifiedRoute, alive -> onVerdict(verifiedRoute, alive))) {
                                Log.w(TAG, "could not start the immediate native check for local relay route "
                                        + verifiedRoute.port);
                            }
                            startProber();
                            publishPoolToStock();
                            sweepFast(null);
                        });
                        if (findPoolCandidate(verifiedRoute) != null) {
                            scheduleRelayRouteAutoApply(verifiedRoute, 12);
                        }
                        return;
                    }
                }
                relayFallbackCursor.set((start + 1) % relays.size());
                Log.i(TAG, "no reachable public relay could open a Telegram DC tunnel");
            } catch (Throwable t) {
                Log.w(TAG, "relay-to-Telegram route probe failed", t);
            } finally {
                relayFallbackProbeRunning.set(false);
            }
        });
    }

    /** Advance to another measured relay when Telegram rejects a local SOCKS route's handshake. */
    private static void scheduleRelayFallbackRetry() {
        int relayCount = getReachableRelays().size();
        if (relayCount <= 1) {
            relayFallbackFailures.set(0);
            return;
        }
        int attempt = relayFallbackFailures.incrementAndGet();
        if (attempt >= relayCount) {
            relayFallbackFailures.set(0);
            Log.w(TAG, "all reachable relay candidates failed Telegram's native handshake");
            return;
        }
        if (relayFallbackRetryPending.compareAndSet(false, true)) {
            scheduler.schedule(() -> {
                relayFallbackRetryPending.set(false);
                probeRelayFallbacks();
            }, 1200, TimeUnit.MILLISECONDS);
        }
    }

    /** Apply a relay only after Telegram's native protocol check proves that it carries MTProto. */
    private static void scheduleRelayRouteAutoApply(final ProxyItem candidate, final int attemptsRemaining) {
        if (candidate == null || attemptsRemaining <= 0) return;
        scheduler.schedule(() -> mainHandler.post(() -> {
            if (tgnetConnectionState() == TG_STATE_CONNECTED || currentActiveProxy == candidate
                    || candidate.failedVerdicts > 0 || !ColgramConfig.isAutoProxyEnabled()
                    || isUserProxyDisabled() || ColgramConfig.isWarpEnabled()) return;
            if (!candidate.nativeVerified) {
                if (attemptsRemaining > 1) {
                    scheduleRelayRouteAutoApply(candidate, attemptsRemaining - 1);
                } else {
                    Log.w(TAG, "not applying relay route without a successful Telegram native verdict");
                }
                return;
            }
            ProxyItem active = currentActiveProxy;
            boolean userSelectedRoute = active != null && active != dcRemapItem
                    && !active.isLocalDpi() && !active.autoSelected;
            if (userSelectedRoute) return;
            if (isDcRemapActive() && !dcRemapGaveUp()) {
                scheduleRelayRouteAutoApply(candidate, attemptsRemaining - 1);
                return;
            }
            forceApplyProxy(candidate);
            Log.i(TAG, "auto-applied the native-verified Telegram relay route after direct recovery stalled");
        }), 5, TimeUnit.SECONDS);
    }

    /** Parses host:port lines out of a relay list. */
    private static void collectRelays(String body, boolean socks, List<ColgramProxyChain.Relay> out, boolean countryColumn) {
        if (body == null) return;
        for (String line : body.split("\\r?\\n")) {
            ColgramProxyPolicy.Endpoint endpoint = ColgramProxyPolicy.parseEndpoint(line, socks ? "socks5" : "http", countryColumn);
            if (endpoint == null) continue;
            boolean duplicate = false;
            for (ColgramProxyChain.Relay existing : out) {
                if (existing.host.equals(endpoint.host) && existing.port == endpoint.port && existing.socks == socks) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(new ColgramProxyChain.Relay(endpoint.host, endpoint.port, socks));
            if (out.size() >= MAX_RELAYS * 2) return;
        }
    }

    private static boolean isLocalRelayRoute(ProxyItem item) {
        return item != null && item.type == 0 && isLoopbackHost(item.address)
                && ColgramProxyChain.isOpen(item.port);
    }

    /** Find an already pooled endpoint without conflating a coincident host/port across types. */
    private static ProxyItem findPoolCandidate(ProxyItem candidate) {
        if (candidate == null) return null;
        synchronized (verifiedPool) {
            for (ProxyItem item : verifiedPool) {
                if (item.equals(candidate)) return item;
            }
        }
        return null;
    }

    /** Replace only a dormant local bridge when its single reserved slot blocks another relay. */
    private static boolean removeDormantRelayRoute() {
        long now = SystemClock.elapsedRealtime();
        synchronized (verifiedPool) {
            for (int i = verifiedPool.size() - 1; i >= 0; i--) {
                ProxyItem old = verifiedPool.get(i);
                if (!isInternalLoopbackEndpoint(old) || old.isLocalDpi()) continue;
                boolean checkedRecently = old.nativeVerified
                        && now - old.lastCheckAt < RECHECK_INTERVAL_MS;
                if (checkedRecently || (old == currentActiveProxy
                        && tgnetConnectionState() == TG_STATE_CONNECTED)) continue;
                verifiedPool.remove(i);
                if (proberCursor > i) proberCursor--;
                return true;
            }
        }
        return false;
    }

    /**
     * Insert one candidate, keeping the pool inside its ceiling.
     *
     * Eviction prefers a node that has already failed over one that has never been probed, so a
     * long list of unverified hosts cannot push out the few that actually work.
     */
    private static boolean addCandidate(ProxyItem item) {
        if (item == null || item.address == null || item.address.isEmpty()) return false;
        if (item.type != 2 && (item.port <= 0 || item.port > 65535
                || ColgramProxyPolicy.normalizeHost(item.address) == null)) return false;
        // Remembered before the pool checks, so an endpoint rejected here is remembered too: a
        // candidate the app fetched and then dropped for capacity is not a proxy the user chose
        // either, and findSavedProxy() has to be able to tell the two apart.
        rememberHarvested(item.address, item.port);
        if (isLoopbackHost(item.address) && !item.isLocalDpi() && !isLocalRelayRoute(item)) {
            Log.i(TAG, "ignoring external loopback proxy candidate");
            return false;
        }
        synchronized (verifiedPool) {
            if (containsProxy(item)) return false;
            int limit = item.type == 1 ? MAX_MTPROTO_CANDIDATES
                    : MAX_SOCKS_CANDIDATES + (isLocalRelayRoute(item) ? 1 : 0);
            int sameType = 0;
            for (ProxyItem p : verifiedPool) {
                if (!p.isLocalDpi() && p.type == item.type) sameType++;
            }
            if (sameType >= limit || verifiedPool.size() >= MAX_POOL_SIZE) {
                // Only replace a failed entry of the same transport. The previous generic
                // eviction let two large SOCKS lists wipe out all harvested MTProxies.
                int victim = -1;
                for (int i = 0; i < verifiedPool.size(); i++) {
                    ProxyItem p = verifiedPool.get(i);
                    if (p.type == item.type && p != currentActiveProxy && !isInternalLoopbackEndpoint(p)
                            && !p.nativeProbeInFlight.get() && ColgramProxyPolicy.isReplaceable(
                                    rankingState(p), p.failedVerdicts, MAX_FAILED_VERDICTS)) {
                        victim = i;
                        break;
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

    /** Direct, TLS-verified fetch from an explicitly registered public source, never a proxy gateway. */
    private static String httpGet(String sourceUrl) throws IOException {
        java.net.URL url = new java.net.URL(sourceUrl);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
            throw new IOException("expected an HTTPS source");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        javax.net.ssl.HttpsURLConnection connection =
                (javax.net.ssl.HttpsURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(SOURCE_IO_TIMEOUT_MS);
            connection.setReadTimeout(SOURCE_IO_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            if (connection.getContentLengthLong() > SOURCE_MAX_BYTES) throw new IOException("feed exceeds byte budget");
            return ColgramProxyPolicy.readBounded(connection.getInputStream(), SOURCE_MAX_BYTES, deadline);
        } finally {
            connection.disconnect();
        }
    }

    /** t.me/proxy and tg://socks links, in any parameter order. Web endpoints remain manual. */
    private static int fetchProxiesLinkList(String sourceUrl, String body, int limit) {
        List<ProxyItem> candidates = new ArrayList<>();
        if (body == null || body.isEmpty()) return 0;
        Matcher link = Pattern.compile("(?:tg://|https?://t\\.me/|https?://telegram\\.me/)(proxy|socks)\\?[^\\s\"'<>]+",
                Pattern.CASE_INSENSITIVE).matcher(body);
        while (link.find()) {
            ProxyItem candidate = proxyFromLink(link.group().replace("&#38;", "&").replace("&amp;", "&"), link.group(1));
            if (candidate != null) candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            Matcher bare = Pattern.compile("server=([^&\\s]+)&port=(\\d+)&secret=([^&\\s]+)").matcher(body);
            while (bare.find()) {
                ProxyItem candidate = proxyFromLink("tg://proxy?" + bare.group(), "proxy");
                if (candidate != null) candidates.add(candidate);
            }
        }
        return selectFeedWindow(candidates, limit);
    }

    private static ProxyItem proxyFromLink(String url, String kind) {
        String server = param(url, "server");
        if (server == null) return null;
        server = server.replaceAll("\\.$", "");
        if (!ColgramProxyPolicy.isPublicHost(server)) return null;
        int port;
        try { port = Integer.parseInt(param(url, "port")); }
        catch (RuntimeException invalid) { return null; }
        if (port < 1 || port > 65535) return null;
        boolean socks = kind.equalsIgnoreCase("socks");
        String secret = ColgramMtprotoSecrets.canonicalHex(param(url, "secret"));
        return !socks && secret == null ? null : new ProxyItem(server, port, socks ? "" : secret, socks ? 0 : 1);
    }

    private static String param(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).equalsIgnoreCase(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    /** Public SOCKS5 host:port lists are only candidate input, not verified routes. */
    private static int fetchSocksList(String sourceUrl, String body, int limit) {
        if (body == null || body.isEmpty()) return 0;
        List<ProxyItem> candidates = new ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            ColgramProxyPolicy.Endpoint endpoint = ColgramProxyPolicy.parseEndpoint(line, "socks5",
                    sourceUrl.contains("/zloi-user/hideip.me/"));
            if (endpoint != null && ColgramProxyPolicy.isPublicHost(endpoint.host))
                candidates.add(new ProxyItem(endpoint.host, endpoint.port, "", 0));
        }
        return selectFeedWindow(candidates, limit);
    }

    private static int fetchProxiesJson(String sourceUrl, String body, int limit) {
        if (body == null || body.isEmpty()) return 0;
        List<ProxyItem> candidates = new ArrayList<>();
        try {
            JSONArray arr;
            try { arr = new JSONArray(body); }
            catch (Exception notArray) { arr = new JSONObject(body).optJSONArray("proxies"); }
            if (arr == null) return 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String server = obj.optString("server", obj.optString("host", ""));
                int port = obj.optInt("port", 0);
                String secret = ColgramMtprotoSecrets.canonicalHex(obj.optString("secret", ""));
                if (!ColgramProxyPolicy.isPublicHost(server) || port < 1 || port > 65535) continue;
                if (secret != null) candidates.add(new ProxyItem(server, port, secret, 1));
                else if (obj.optString("type", "").equalsIgnoreCase("socks5"))
                    candidates.add(new ProxyItem(server, port, "", 0));
            }
        } catch (Exception invalid) { Log.w(TAG, "Invalid proxy feed format: " + sourceUrl); }
        return selectFeedWindow(candidates, limit);
    }

    private static boolean addFeedCandidate(ProxyItem candidate) {
        return !Thread.currentThread().isInterrupted()
                && ColgramProxyPolicy.isPublicHost(candidate.address)
                && addCandidate(candidate);
    }

    /** TCP reachability of a relay, for callers that must pick a transport. */
    public static int probeTcp(String host, int port, int timeoutMs) {
        return testProxy(host, port, timeoutMs);
    }

    private static int testProxy(String host, int port, int timeoutMs) {        long start = System.currentTimeMillis();
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
        if (isInternalLoopbackEndpoint(proxy) || proxy.type == 2) return;
        if (proxy.type == 1 && proxy.mimicActive) {
            // Re-open the browser-fingerprint front: the port the verdict was measured on is
            // the only endpoint proven to pass the handshake for this node.
            int port = ColgramProxyChain.openMimicFront(proxy.address, proxy.port);
            if (port > 0) {
                proxy.chainPort = port;
                Log.i(TAG, "mimic front re-opened for " + proxy.address + ":" + proxy.port
                        + " at 127.0.0.1:" + port);
                return;
            }
            proxy.mimicActive = false;
        }
        if (proxy.chainRelay == null) return;
        // The background health check selected this relay. Never re-probe a
        // blocked host from a UI route commit while cancellation waits on it.
        int port = ColgramProxyChain.open(proxy.address, proxy.port, proxy.chainRelay);
        if (port > 0) {
            proxy.chainPort = port;
            Log.i(TAG, "chained " + proxy.address + ":" + proxy.port + " through "
                    + proxy.chainRelay + " at 127.0.0.1:" + port);
        } else {
            proxy.chainRelay = null;
        }
    }

    private static volatile long lastApplyAt = 0L;

    public static void forceApplyProxy(ProxyItem proxy) {
        if (proxy == null) return;
        if (!isInternalLoopbackEndpoint(proxy) && !isFreshProtocolVerified(proxy)) {
            Log.w(TAG, "skipping proxy application without a current Telegram protocol verdict");
            return;
        }
        // Reset the counters only on the first application of a node, never on a re-apply.
        //
        // Resetting on every application was worse than not resetting at all: the check that decides
        // whether a node works runs *after* this, and the next rotation applies it again before its
        // verdict has been recorded. So a node that never worked was measured, found dead, then had
        // its count cleared by the rotation that reacted to that verdict, and measured again from
        // one. Measured:
        //
        //   18:11:10  native check 173.212.245.154:443 -> dead (1)
        //   18:11:13  native check 217.144.187.230:443  -> dead (1)
        //   ... 100 checks, every one of them the first failure of a node, pool down to 1 entry.
        //
        // A node gets one clean slate, when it enters the pool. After that the count is the only
        // thing standing between a dead entry and a pool that never shrinks.
        if (!proxy.appliedOnce) {
            proxy.appliedOnce = true;
            proxy.failedVerdicts = 0;
            proxy.nativeFailures = 0;
        }
        // A probe/rotation can finish after the user enabled WARP. Never let a background
        // proxy result silently turn their selected VPN off; explicit proxy actions clear WARP
        // in their UI entry point before reaching this method.
        if (ColgramConfig.isWarpEnabled() && !proxy.isLocalDpi()) {
            Log.i(TAG, "WARP is selected; ignoring background remote proxy application");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (proxy == currentActiveProxy && now - lastApplyAt < 10000L) {
            // The same node asked for twice inside one grace window. Every alive verdict posts an
            // auto-connect, and re-pushing the proxy tears Telegram's connections down again for
            // no reason - the log used to read "Applying proxy" three times in a second.
            return;
        }
        Context ctx = appContext;
        if (ctx == null) return;
        resolveChain(proxy);
        if (!isInternalLoopbackEndpoint(proxy) && !isFreshProtocolVerified(proxy)) return;
        if (!isInternalLoopbackEndpoint(proxy) && proxy.type != 2 && proxy.chainPort == 0) {
            try {
                if (proxy.baseFront == null || proxy.baseFront.port() == 0)
                    proxy.baseFront = new ColgramLocalTcpFront(proxy.address, proxy.port);
            } catch (IOException failed) {
                Log.e(TAG, "Could not attach the local base to the selected proxy", failed);
                return;
            }
        }
        ProxyItem previous = currentActiveProxy;
        if (previous != null && previous != proxy && previous.baseFront != null) {
            previous.baseFront.close(); previous.baseFront = null;
        }
        if (isInternalLoopbackEndpoint(proxy) || proxy == dcRemapItem) {
            // Stop the stock rotator before touching SharedConfig's list. Its background pass
            // can otherwise mutate the same ArrayList while stale localhost entries are pruned.
            disableStockRotation(ctx);
        }
        if (proxy.isLocalDpi()) {
            // Start the grace window: the desync strategy prober needs several connections
            // to find a strategy that gets through, and rotating away before then defeats
            // it (see switchToNextProxy).
            localDpiAppliedAt = SystemClock.elapsedRealtime();
        }
        Log.d(TAG, "Applying proxy: " + proxy.address + ":" + proxy.port + " (type=" + proxy.type + ")");

        try {
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
            // Runtime-selected endpoints are deliberately not persisted in step 1. Build the
            // setting from the selected item, not from stale preferences: otherwise the stock
            // method accepts an empty/old setting and silently disables the tunnel.
            boolean appliedThroughStockPath = false;
            boolean appliedThroughNativePath = false;
            try {
                Class<?> psClass = Class.forName("org.telegram.utils.proxy.ProxySettings");
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Object settings = buildProxySettings(proxy);
                if (settings == null || !Boolean.TRUE.equals(psClass.getMethod("isValid").invoke(settings))) {
                    throw new IllegalArgumentException("Invalid selected proxy: " + proxy.address);
                }

                Method stockSet = cmClass.getMethod("setProxySettings", boolean.class, psClass);
                stockSet.invoke(null, true, settings);
                appliedThroughStockPath = true;
                Log.i(TAG, "proxy applied via ConnectionsManager.setProxySettings (type="
                        + proxy.type + ", " + proxy.address + ")");
                // A tunnel is in place, by his hand or by the notice's button: the "Telegram is
                // unreachable" notice has nothing left to say.
                ColgramBypassNotice.clear(appContext);
            } catch (Throwable t) {
                Log.e(TAG, "stock setProxySettings failed, falling back to native", t);
            }

            if (!appliedThroughStockPath) {
                // Keep the previous behaviour as a last resort so a refactor upstream cannot
                // leave users with no way to set a proxy at all.
                try {
                    if (proxy.type == 2) throw new IllegalStateException("WEB needs WebProxyTransport");
                    Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                    Method nativeSetProxy = cmClass.getDeclaredMethod("native_setProxySettings",
                            int.class, String.class, int.class, String.class, String.class, String.class);
                    nativeSetProxy.setAccessible(true);
                    for (int i = 0; i < colgramAccountSlots; i++) {
                        nativeSetProxy.invoke(null, i, proxy.effectiveHost(), proxy.effectivePort(),
                                proxy.user, proxy.password, proxy.secret);
                    }
                    appliedThroughNativePath = true;
                } catch (Throwable t) {
                    Log.e(TAG, "ConnectionsManager native_setProxySettings error", t);
                }
            }
            if (!appliedThroughStockPath && !appliedThroughNativePath) {
                Log.e(TAG, "selected proxy was not applied: " + proxy.address + ":" + proxy.port);
                return;
            }
            // 1. Persist proxy settings in SharedPreferences for every account slot.
            //
            // Skipped for the remap: its endpoint is 127.0.0.1 on a port that dies with the
            // process, so writing it into Telegram's own prefs made the next start restore a
            // socket nothing listens on, report it dead, and hold it as "the proxy the user
            // configured". The remap is restored from its own switch instead (see init).
            // Only a proxy he picked himself goes into Telegram's settings. An endpoint chosen by
            // auto-connect or by rotation is runtime state: persisting it made it come back on the
            // next start as "the proxy the user configured", so a build that had once dialled a
            // node for him kept him on that node even after he switched the behaviour off - and
            // the header said "Подключение прокси..." to a proxy he never chose. The local desync
            // listener is skipped for the same reason plus one: writing it flips Telegram's own
            // "Использовать прокси" on, which is what made "обход без прокси" read as a proxy.
            // The bypass is re-applied from its own switch on every start, so it does not need to
            // live in his settings to work.
            // Persist proxy_enabled for EVERY applied route, auto-selected included:
            // the header read "подключен" while the stock switch stayed grey, because the
            // switch reads this key. What the user disables with the switch is the route.
            if (proxy != dcRemapItem && !proxy.isLocalDpi()) {
                // Clear "the user turned this off" in the same breath that turns the route on.
                //
                // Two flags answer the same question and were written by different callers:
                // proxy_enabled is what Telegram's own switch reads, and
                // colgram_proxy_manually_disabled is Colgram's own memory of the switch being
                // tapped off. forceApplyProxy wrote the first and never the second, so a route that
                // came up by itself - the auto-connect the report also complains about - left both
                // on disk at once:
                //
                //   proxy_enabled                      = true
                //   colgram_proxy_manually_disabled    = true
                //
                // A switch that reads "on" over a route whose own manager is told "the user turned
                // this off" is the reported "I switch it off and it turns itself back on": the next
                // pass through init() reads the second flag and decides the user wanted it off, and
                // the rotator applies a node anyway. Whichever writer ran last wins, and nothing on
                // screen can tell you which.
                setUserProxyDisabled(false);
                for (int a = 0; a < colgramAccountSlots; a++) {
                    String prefName = a == 0 ? "mainconfig" : ("mainconfig" + a);
                    SharedPreferences preferences =
                            ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                    // The node itself, never the loopback hop that reaches it: a chained proxy
                    // used to be stored as 127.0.0.1:<ephemeral port>, so the next start restored
                    // a port nothing listens on.
                    preferences.edit()
                            .putBoolean("proxy_enabled", true)
                            .putString("proxy_ip", proxy.address)
                            .putInt("proxy_port", proxy.port)
                            .putString("proxy_user", proxy.user)
                            .putString("proxy_pass", proxy.password)
                            .putString("proxy_secret", proxy.secret)
                            .putInt("proxy_type", proxy.type)
                            .putBoolean("proxy_auto_applied", proxy.autoSelected)
                            .apply();
                }
            }

            currentActiveProxy = proxy;
            lastApplyAt = now;

            // SharedConfig and its ArrayList are owned by Telegram's main thread. Startup and
            // background retry callers must not mutate that list from their worker threads.
            Runnable updateStockState = () -> updateSharedConfigProxyState(proxy);
            if (Looper.myLooper() == Looper.getMainLooper()) updateStockState.run();
            else mainHandler.post(updateStockState);
            mainHandler.post(ColgramProxyManager::notifyProxySettingsChanged);

        } catch (Exception e) {
            Log.e(TAG, "forceApplyProxy error", e);
        }
    }

    private static final long PROXY_SELECTION_TIMEOUT_MS = 25_000L;
    private static final int MAX_SELECTION_TCP_CHECKS = 48;
    private static final int SELECTION_TCP_TIMEOUT_MS = 1_250;
    private static final int MAX_BACKGROUND_TCP_CHECKS = 96;
    private static final int MAX_BACKGROUND_GUEST_CHECKS = 5;
    private static final long BACKGROUND_PROXY_CHECK_WINDOW_MS = 50_000L;
    private static final long BACKGROUND_PROXY_BUSY_RETRY_MS = 4_000L;
    private static final long BACKGROUND_PROXY_IDLE_MS = 25_000L;
    private static final long BACKGROUND_PROXY_READY_MS = 15_000L;
    private static final long BACKGROUND_PROXY_FEED_INTERVAL_MS = 4 * 60_000L;
    private static final long BACKGROUND_PROXY_TCP_RECHECK_MS = 3 * 60_000L;
    private static final long BACKGROUND_PROXY_NEGATIVE_RECHECK_MS = 3 * 60_000L;
    private static final long BACKGROUND_PROXY_POSITIVE_RECHECK_MS = 35_000L;
    private static final ColgramOperationGate proxySelectionGate = new ColgramOperationGate();
    private static volatile ProxySelection proxySelection;
    private static volatile ProxySelection backgroundProxySelection;
    private static volatile String proxySelectionStatus = "";
    private static volatile String backgroundProxyStatus = "Проверка каталога запланирована";
    private static long backgroundProxyScheduledAt = Long.MAX_VALUE;
    private static final Runnable backgroundProxyRunner = ColgramProxyManager::runBackgroundProxyCheck;
    private static ScheduledCatalogCheck scheduledCatalogCheck;

    private static final class ScheduledCatalogCheck {
        final Runnable completed;
        final Runnable timeout;
        ScheduledCatalogCheck(Runnable completed) {
            this.completed = completed;
            timeout = () -> { if (scheduledCatalogCheck == this) finishScheduledCatalogCheck(true); };
        }
    }

    private static boolean catalogChecksAllowed() {
        return scheduledCatalogCheck != null || ColgramUiBridge.isAppInForeground();
    }

    private static void beginScheduledCatalogCheck(Runnable completed) {
        finishScheduledCatalogCheck(true);
        scheduledCatalogCheck = new ScheduledCatalogCheck(completed);
        mainHandler.postDelayed(scheduledCatalogCheck.timeout, BACKGROUND_PROXY_CHECK_WINDOW_MS + 15_000L);
        scheduleBackgroundProxyCheck(0L);
    }

    private static void finishScheduledCatalogCheck(boolean notify) {
        ScheduledCatalogCheck job = scheduledCatalogCheck;
        scheduledCatalogCheck = null;
        if (job == null) return;
        mainHandler.removeCallbacks(job.timeout);
        if (notify && job.completed != null) job.completed.run();
        if (!ColgramUiBridge.isAppInForeground() && backgroundProxySelection != null) {
            backgroundProxyTick(backgroundProxySelection);
        }
    }

    public static void cancelScheduledCatalogCheck() {
        mainHandler.post(() -> finishScheduledCatalogCheck(false));
    }

    private static final class ProxySelection {
        final long token;
        final long routeEpoch;
        final long deadline;
        final long network = networkProbeEpoch.get();
        final long started = SystemClock.elapsedRealtime();
        final ProxyItem requested;
        final java.util.Set<ProxyItem> nativeAttempted = new java.util.HashSet<>();
        final java.util.Set<ProxyItem> guestAttempted = new java.util.HashSet<>();
        final java.util.Set<ProxyItem> protocolTargets = new java.util.HashSet<>();
        final java.util.Set<ProxyItem> tcpAttempted = new java.util.HashSet<>();
        final java.util.Map<ProxyItem, String> tcpReachable = new java.util.HashMap<>();
        final boolean guestEligible;
        final boolean catalogOnly;
        Runnable timeout;
        Runnable tick;
        int inFlight;
        int tcpFinished;
        int tcpOpen;
        int socksAttempted;
        int socksReady;
        int socksConnected;
        int protocolAnswered;
        int guestAttempts;
        boolean guestPending;
        String guestResult = "not-started";

        ProxySelection(long token, long routeEpoch, ProxyItem requested) {
            this.token = token;
            this.routeEpoch = routeEpoch;
            this.requested = requested;
            this.deadline = started + PROXY_SELECTION_TIMEOUT_MS;
            this.guestEligible = explicitPreLogin() && (requested == null || requested.type != 2);
            this.catalogOnly = false;
        }

        ProxySelection() {
            token = -1L;
            routeEpoch = -1L;
            requested = null;
            deadline = started + BACKGROUND_PROXY_CHECK_WINDOW_MS;
            guestEligible = true;
            catalogOnly = true;
        }
    }

    public static boolean isProxySelectionPending() {
        ProxySelection selection = proxySelection;
        return selection != null && selectionIsCurrent(selection);
    }

    public static String getProxySelectionStatus() { return proxySelectionStatus; }

    public static String getBackgroundProxyStatus() {
        if (!ColgramConfig.isBuiltinProxyEnabled()) return "Фоновая проверка отключена";
        SourceBatch batch = lastSourceBatch;
        return backgroundProxyStatus + " · Доступно: " + getSelectableProxies().size()
                + (batch == null ? "" : " · Списки с кандидатами: " + batch.parsed.get() + "/" + batch.total);
    }

    public static void cancelProxySelection() {
        ProxySelection old = proxySelection;
        proxySelectionGate.cancel();
        proxySelection = null;
        if (old != null && old.timeout != null) mainHandler.removeCallbacks(old.timeout);
        if (old != null && old.tick != null) mainHandler.removeCallbacks(old.tick);
        if (old != null) {
            proxySelectionStatus = "Проверка прокси отменена";
            mainHandler.post(ColgramProxyManager::notifyProxySettingsChanged);
            scheduleBackgroundProxyCheck(BACKGROUND_PROXY_BUSY_RETRY_MS);
        }
    }

    private static boolean selectionIsCurrent(ProxySelection selection) {
        return proxySelection == selection && proxySelectionGate.isPending()
                && proxySelectionGate.isCurrent(selection.token)
                && ColgramRouteEpoch.isCurrent(selection.routeEpoch);
    }

    private static boolean preflightIsCurrent(ProxySelection selection) {
        return selection.catalogOnly
                ? backgroundProxySelection == selection && !isProxySelectionPending()
                    && ColgramConfig.isBuiltinProxyEnabled() && catalogChecksAllowed()
                : selectionIsCurrent(selection);
    }

    private static void scheduleBackgroundProxyCheck(long delayMs) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> scheduleBackgroundProxyCheck(delayMs));
            return;
        }
        long deadline = SystemClock.elapsedRealtime() + delayMs;
        if (deadline >= backgroundProxyScheduledAt) return;
        backgroundProxyScheduledAt = deadline;
        mainHandler.removeCallbacks(backgroundProxyRunner);
        mainHandler.postDelayed(backgroundProxyRunner, delayMs);
    }

    private static void runBackgroundProxyCheck() {
        backgroundProxyScheduledAt = Long.MAX_VALUE;
        Context context = appContext;
        if (context == null || !ColgramConfig.isBuiltinProxyEnabled() || !catalogChecksAllowed()
                || ColgramPowerGuard.shouldThrottle(context) || !hasCatalogNetwork(context)) {
            finishScheduledCatalogCheck(true);
            scheduleBackgroundProxyCheck(BACKGROUND_PROXY_IDLE_MS);
            return;
        }
        if (isProxySelectionPending()) {
            scheduleBackgroundProxyCheck(BACKGROUND_PROXY_BUSY_RETRY_MS);
            return;
        }
        if (backgroundProxySelection != null) return;
        if (verifiedPool.isEmpty()) initVerifiedPool();
        releaseQuarantine();
        ProxySelection selection = new ProxySelection();
        backgroundProxySelection = selection;
        selection.tick = () -> backgroundProxyTick(selection);
        Log.i(TAG, "PROXY_BACKGROUND_START network=" + selection.network
                + " proxy_enabled=" + isProxyEnabled(context));
        if (lastSourceFetchAt == 0 || selection.started - lastSourceFetchAt >= BACKGROUND_PROXY_FEED_INTERVAL_MS) {
            executor.execute(() -> fetchAndVerifyAllSources());
        }
        backgroundProxyTick(selection);
    }

    private static boolean hasCatalogNetwork(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean isBackgroundCatalogCandidate(ProxyItem item) {
        return item != null && item.type != 2 && isDirectSelectionCandidate(item)
                && ColgramProxyPolicy.isPublicHost(item.address);
    }

    private static long catalogTcpAt(ProxyItem candidate, long network) {
        return candidate.catalogNetwork == network ? candidate.catalogTcpAt : 0L;
    }

    private static long catalogProtocolAt(ProxyItem candidate, long network) {
        return candidate.catalogNetwork == network ? candidate.catalogProtocolAt : 0L;
    }

    private static void enterCatalogNetwork(ProxyItem candidate, long network) {
        if (candidate.catalogNetwork == network) return;
        candidate.catalogTcpAt = candidate.catalogProtocolAt = 0L;
        candidate.catalogPinnedAddress = null;
        candidate.catalogNetwork = network;
    }

    private static void sortCatalogProbeOrder(List<ProxyItem> candidates, long now, long network) {
        sortCandidates(candidates);
        final java.util.Set<ProxyItem> due = new java.util.HashSet<>();
        for (ProxyItem candidate : candidates) {
            if (candidate.nativeVerified && candidate.lastCheckAt > 0 && now - candidate.lastCheckAt >= BACKGROUND_PROXY_POSITIVE_RECHECK_MS) {
                due.add(candidate);
            }
        }
        candidates.sort((a, b) -> {
            boolean refreshA = due.contains(a), refreshB = due.contains(b);
            if (refreshA != refreshB) return refreshA ? -1 : 1;
            return Long.compare(catalogTcpAt(a, network), catalogTcpAt(b, network));
        });
    }

    private static void backgroundProxyTick(ProxySelection selection) {
        mainHandler.removeCallbacks(selection.tick);
        if (backgroundProxySelection != selection) return;
        if (!preflightIsCurrent(selection) || selection.network != networkProbeEpoch.get()) {
            finishBackgroundProxyCheck(selection, BACKGROUND_PROXY_BUSY_RETRY_MS);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now >= selection.deadline) {
            finishBackgroundProxyCheck(selection, BACKGROUND_PROXY_IDLE_MS);
            return;
        }
        List<ProxyItem> candidates = new ArrayList<>(verifiedPool);
        candidates.removeIf(candidate -> candidate == currentActiveProxy || !isBackgroundCatalogCandidate(candidate));
        sortCatalogProbeOrder(candidates, now, selection.network);
        scheduleSelectionTcp(selection, candidates);
        // Prefer a fresh or previously verified SOCKS route over a TCP-only MTProto candidate,
        // while alternating types so neither protocol can consume the entire guest window.
        int preferredType = selection.guestAttempts % 2;
        boolean guestAllowed = !selection.guestPending && selection.guestAttempts < MAX_BACKGROUND_GUEST_CHECKS
                && selection.deadline - now >= ColgramGuestProxyProbe.CHECK_TIMEOUT_MS;
        for (int pass = 0; guestAllowed && pass < 2; pass++) {
            int type = pass == 0 ? preferredType : 1 - preferredType;
            for (ProxyItem source : candidates) {
                if (source.type != type || source.nativeProbeInFlight.get() || source.nextEligibleAt > now) continue;
                long last = catalogProtocolAt(source, selection.network);
                long recheck = source.nativeVerified ? BACKGROUND_PROXY_POSITIVE_RECHECK_MS : BACKGROUND_PROXY_NEGATIVE_RECHECK_MS;
                if (last != 0L && now - last < recheck) continue;
                String pinnedAddress = selection.tcpReachable.get(source);
                if (pinnedAddress == null && catalogTcpAt(source, selection.network) != 0L
                        && now - source.catalogTcpAt < BACKGROUND_PROXY_TCP_RECHECK_MS) {
                    pinnedAddress = source.catalogPinnedAddress;
                }
                if (pinnedAddress == null) continue;
                ProxyItem candidate = pinSelectionCandidate(source, pinnedAddress);
                if (selection.guestAttempted.contains(candidate)) continue;
                if (startBackgroundGuestProbe(selection, source, candidate)) {
                    guestAllowed = false;
                    break;
                }
            }
        }
        backgroundProxyStatus = "Проверяю в фоне · " + selectionProgress(selection);
        if (!selection.guestPending && selection.tcpFinished == selection.tcpAttempted.size()
                && !fetchingSources.get() && now - selection.started >= 12_000L
                && (selection.guestAttempts >= MAX_BACKGROUND_GUEST_CHECKS
                    || now - selection.started >= 16_000L)) {
            finishBackgroundProxyCheck(selection,
                    getSelectableProxies().isEmpty() ? BACKGROUND_PROXY_IDLE_MS : BACKGROUND_PROXY_READY_MS);
            return;
        }
        mainHandler.postDelayed(selection.tick, 750L);
    }

    private static boolean startBackgroundGuestProbe(ProxySelection selection, ProxyItem source, ProxyItem candidate) {
        final String endpoint = probeEndpoint(candidate);
        boolean started = ColgramGuestProxyProbe.checkThroughBase(appContext, candidate.address, candidate.port,
                candidate.user, candidate.password, candidate.secret, true, (time, reason) -> {
                    if (backgroundProxySelection != selection) return;
                    selection.guestPending = false;
                    if (preflightIsCurrent(selection) && selection.network == networkProbeEpoch.get()
                            && source != currentActiveProxy && endpoint.equals(probeEndpoint(candidate))) {
                        boolean alive = time >= 0;
                        ProxyItem measured = source;
                        selection.guestResult = alive ? "verified" : reason;
                        if (alive) {
                            selection.protocolAnswered++;
                            candidate.pingMs = (int) Math.min(Integer.MAX_VALUE, time);
                            candidate.verifiedEndpoint = endpoint;
                            measured = storePinnedCandidate(source, candidate);
                        }
                        enterCatalogNetwork(measured, selection.network);
                        if (measured != source) {
                            measured.catalogTcpAt = source.catalogTcpAt;
                            measured.catalogPinnedAddress = measured.address;
                        }
                        measured.catalogProtocolAt = SystemClock.elapsedRealtime();
                        measured.lastCheckAt = measured.catalogProtocolAt;
                        measured.nativeVerified = measured.isAvailable = alive;
                        if (alive) {
                            measured.failedVerdicts = 0;
                            rememberAlive();
                        } else {
                            measured.pingMs = -2;
                            if (measured.failedVerdicts < MAX_FAILED_VERDICTS) measured.failedVerdicts++;
                        }
                        publishPoolToStock();
                        notifyProxySettingsChanged();
                    }
                    backgroundProxyTick(selection);
                });
        if (started) {
            selection.guestPending = true;
            selection.guestAttempts++;
            selection.guestAttempted.add(candidate);
            selection.protocolTargets.add(candidate);
            selection.guestResult = "pending";
            Log.i(TAG, "PROXY_BACKGROUND_CHECK telegram_attempt=" + selection.guestAttempts + " type=" + candidate.type);
        }
        return started;
    }

    private static void finishBackgroundProxyCheck(ProxySelection selection, long delayMs) {
        if (backgroundProxySelection != selection) return;
        mainHandler.removeCallbacks(selection.tick);
        backgroundProxySelection = null;
        backgroundProxyStatus = "Фоновая проверка · " + selectionProgress(selection);
        Log.i(TAG, "PROXY_BACKGROUND " + selectionDiagnostic(selection)
                + " fresh=" + getSelectableProxies().size() + " route_changed=false"
                + " proxy_enabled=" + isProxyEnabled(appContext));
        publishPoolToStock();
        notifyProxySettingsChanged();
        finishScheduledCatalogCheck(true);
        scheduleBackgroundProxyCheck(delayMs);
    }

    /** Explicit public-proxy mode: no endpoint reaches JNI until its protocol check succeeds. */
    public static void selectPublicProxy(Context context) {
        selectUserProxyForEpoch(context, null, ColgramRouteEpoch.next(), true);
    }

    /** A saved/manual entry stays saved on failure, but is not installed without a verdict. */
    public static void selectUserProxy(Context context, String address, int port, String secret,
                                       int type, String user, String password) {
        ProxyItem requested = new ProxyItem(address, port, secret, type, user, password);
        ProxyItem pooled = findPoolCandidate(requested);
        if (pooled != null) requested = pooled;
        selectUserProxyForEpoch(context, requested, ColgramRouteEpoch.next(), true);
    }

    private static void selectUserProxyForEpoch(Context context, ProxyItem requested,
                                                long routeEpoch, boolean explicit) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> selectUserProxyForEpoch(ctx, requested, routeEpoch, explicit));
            return;
        }
        if (!ColgramRouteEpoch.isCurrent(routeEpoch)) return;
        if (!explicit && (ColgramConfig.isWarpEnabled()
                || isUserProxyDisabled() || isProxySelectionPending())) return;
        appContext = ctx;
        if (backgroundProxySelection != null) {
            finishBackgroundProxyCheck(backgroundProxySelection, BACKGROUND_PROXY_BUSY_RETRY_MS);
        }
        cancelProxySelection();
        if (explicit) {
            ColgramConfig.setAutoProxyEnabled(false);
            ColgramConfig.setDcRemapEnabled(false);
            ColgramConfig.setIpv6BypassEnabled(false);
            dpiRequestGeneration.incrementAndGet();
            ColgramDpiBypass.startImmediately();
            dcRemapItem = null;
            ColgramDcRemap.stop();
            ColgramConfig.setWarpEnabled(false);
            ColgramWarpTunnel.cancelPendingStart();
            try {
                Class.forName("org.colgram.singbox.ColgramVpnService")
                        .getMethod("stopProfile", Context.class, String.class)
                        .invoke(null, ctx, ColgramSubscriptionStore.profileLocation(ctx));
            } catch (ReflectiveOperationException ignored) { }
            executor.execute(() -> {
                if (ColgramRouteEpoch.isCurrent(routeEpoch)) ColgramWarpTunnel.bringDown(ctx);
            });
        }
        // Keep the old saved identity, but release a stale active route while checking.
        disableProxy(ctx);
        setUserProxyDisabled(false);
        ProxySelection selection = new ProxySelection(proxySelectionGate.begin(), routeEpoch, requested);
        proxySelection = selection;
        proxySelectionStatus = "Проверяю прокси (до 25 с)";
        selection.timeout = () -> finishProxySelection(selection, null,
                "Рабочий прокси не найден за 25 с; прокси выключен");
        selection.tick = () -> proxySelectionTick(selection);
        mainHandler.postDelayed(selection.timeout, PROXY_SELECTION_TIMEOUT_MS);
        notifyProxySettingsChanged();
        if (requested == null) {
            if (verifiedPool.isEmpty()) initVerifiedPool();
            executor.execute(() -> fetchAndVerifyAllSources(true));
        }
        proxySelectionTick(selection);
    }

    private static boolean isDirectSelectionCandidate(ProxyItem item) {
        // Explicit public mode never introduces a secondary relay or a process-local front.
        return !isInternalLoopbackEndpoint(item) && item.chainRelay == null
                && item.chainPort == 0 && !item.mimicActive;
    }

    private static final class SelectionTcpResult {
        final String address;
        final int rtt;
        final boolean greeted;
        final boolean ready;

        SelectionTcpResult(String address, int rtt, boolean greeted, boolean ready) {
            this.address = address;
            this.rtt = rtt;
            this.greeted = greeted;
            this.ready = ready;
        }
    }

    private static int remainingSelectionTcp(long started) {
        return (int) Math.max(0, SELECTION_TCP_TIMEOUT_MS - (SystemClock.elapsedRealtime() - started));
    }

    private static SelectionTcpResult probePublicCandidateTcp(ProxyItem candidate) {
        final long started = SystemClock.elapsedRealtime();
        SelectionTcpResult reachable = null;
        try {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(candidate.address)) {
                int remaining = remainingSelectionTcp(started);
                if (remaining <= 0) break;
                if (!ColgramProxyPolicy.isPublicAddress(address)) continue;
                try (Socket socket = new Socket(java.net.Proxy.NO_PROXY)) {
                    socket.connect(new InetSocketAddress(address, candidate.port), remaining);
                    String numeric = address.getHostAddress();
                    int rtt = (int) (SystemClock.elapsedRealtime() - started);
                    if (candidate.type != 0) return new SelectionTcpResult(numeric, rtt, false, true);
                    if (reachable == null) reachable = new SelectionTcpResult(numeric, rtt, false, false);
                    // A public SOCKS5 feed supplies no credentials. Only accept a no-auth greeting.
                    // This is a transport filter, not proof that Telegram is reachable.
                    if (remainingSelectionTcp(started) <= 0) break;
                    socket.getOutputStream().write(new byte[]{5, 1, 0});
                    int version = readSelectionSocksByte(socket, started);
                    int method = readSelectionSocksByte(socket, started);
                    if (version == 5 && method == 0) {
                        reachable = new SelectionTcpResult(numeric, rtt, true, false);
                        if (ColgramSocks5Codec.requestConnect(socket.getOutputStream(),
                                () -> readSelectionSocksByte(socket, started),
                                ColgramTelegramDcAddresses.bootstrapIpv4(), 443)) {
                            return new SelectionTcpResult(numeric, rtt, true, true);
                        }
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException | SecurityException ignored) { }
        return reachable;
    }

    private static int readSelectionSocksByte(Socket socket, long started) throws IOException {
        int remaining = remainingSelectionTcp(started);
        if (remaining <= 0) throw new IOException("SOCKS5 greeting deadline");
        socket.setSoTimeout(remaining);
        return ColgramSocks5Codec.readByte(socket.getInputStream());
    }

    /** Main-thread budget. This path takes newly downloaded candidates without waiting for other feeds. */
    private static void scheduleSelectionTcp(ProxySelection selection, List<ProxyItem> candidates) {
        if (selection.requested != null) return;
        final long now = SystemClock.elapsedRealtime();
        final List<ProxyItem> direct = new ArrayList<>();
        for (ProxyItem item : candidates) {
            if (item.type == 2 || !isDirectSelectionCandidate(item)
                    || item.nextEligibleAt > now || selection.tcpAttempted.contains(item)) continue;
            if (selection.catalogOnly) {
                long checkedAt = catalogTcpAt(item, selection.network);
                long threshold = item.nativeVerified ? BACKGROUND_PROXY_POSITIVE_RECHECK_MS
                        : BACKGROUND_PROXY_TCP_RECHECK_MS;
                if (checkedAt != 0L && now - checkedAt < threshold) continue;
            }
            direct.add(item);
        }
        // Reserve slots for fresh feed rows even if old endpoints refuse immediately.
        int total = selection.catalogOnly ? MAX_BACKGROUND_TCP_CHECKS : MAX_SELECTION_TCP_CHECKS;
        int budget = now - selection.started < 4_000L ? SELECTION_TCP_PARALLEL * 2 : total;
        // Alternate transports so one large SOCKS feed cannot occupy the full window.
        while (!direct.isEmpty() && selection.tcpAttempted.size() < budget
                && (!selection.catalogOnly || selectionTcpSlots.availablePermits() > 3)
                && selectionTcpSlots.tryAcquire()) {
            int preferredType = selection.tcpAttempted.size() % 2;
            int index = 0;
            for (int i = 0; i < direct.size(); i++) {
                if (isFreshProtocolVerified(direct.get(0)) || direct.get(i).type == preferredType) {
                    index = i;
                    break;
                }
            }
            ProxyItem candidate = direct.remove(index);
            selection.tcpAttempted.add(candidate);
            selectionTcpWorkers.execute(() -> {
                SelectionTcpResult result = null;
                try {
                    if (preflightIsCurrent(selection) && selection.network == networkProbeEpoch.get()) {
                        result = probePublicCandidateTcp(candidate);
                    }
                } finally {
                    final SelectionTcpResult measured = result;
                    selectionTcpSlots.release();
                    mainHandler.post(() -> {
                        if (!preflightIsCurrent(selection) || selection.network != networkProbeEpoch.get()) return;
                        selection.tcpFinished++;
                        if (measured != null) {
                            selection.tcpOpen++;
                            if (candidate.type == 0) {
                                selection.socksAttempted++;
                                if (measured.greeted) selection.socksReady++;
                                if (measured.ready) selection.socksConnected++;
                            }
                            if (measured.ready) selection.tcpReachable.put(candidate, measured.address);
                        }
                        long finishedAt = SystemClock.elapsedRealtime();
                        if (selection.catalogOnly) {
                            enterCatalogNetwork(candidate, selection.network);
                            candidate.catalogTcpAt = finishedAt;
                            candidate.catalogPinnedAddress = measured != null && measured.ready ? measured.address : null;
                            if (measured != null && measured.ready) {
                                candidate.tcpMs = measured.rtt;
                                candidate.tcpCheckedAt = finishedAt;
                            } else if (!isFreshProtocolVerified(candidate)) {
                                candidate.tcpMs = -2;
                                candidate.tcpCheckedAt = finishedAt;
                            }
                            backgroundProxyTick(selection);
                        } else {
                            candidate.tcpMs = measured != null && measured.ready ? measured.rtt : -2;
                            candidate.tcpCheckedAt = finishedAt;
                            proxySelectionTick(selection);
                        }
                    });
                }
            });
        }
    }

    private static boolean explicitPreLogin() {
        if (tgnetConnectionState() == TG_STATE_CONNECTED) return false;
        try {
            Class<?> userConfig = Class.forName("org.telegram.messenger.UserConfig");
            Object count = userConfig.getMethod("getActivatedAccountsCount").invoke(null);
            return count instanceof Integer && ((Integer) count) == 0;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void onSelectionProtocolResult(ProxySelection selection, ProxyItem source,
                                                  ProxyItem candidate, boolean alive) {
        if (!selectionIsCurrent(selection) || selection.network != networkProbeEpoch.get()) return;
        ProxyItem proven = candidate;
        if (alive) {
            selection.protocolAnswered++;
            proven = storePinnedCandidate(source, candidate);
            onVerdict(proven, true);
        } else {
            onVerdict(source, false);
        }
        if (alive && isPubliclySelectable(proven)) {
            finishProxySelection(selection, proven, "");
        } else if (selection.requested != null && selection.inFlight == 0) {
            finishProxySelection(selection, null, "Прокси не прошёл проверку Telegram; сохранён, но выключен");
        } else proxySelectionTick(selection);
    }

    private static ProxyItem pinSelectionCandidate(ProxyItem source, String pinnedAddress) {
        if (pinnedAddress == null || pinnedAddress.equals(source.address)) return source;
        ProxyItem target = new ProxyItem(pinnedAddress, source.port, source.secret, source.type,
                source.user, source.password);
        target.tcpMs = source.tcpMs;
        target.tcpCheckedAt = source.tcpCheckedAt;
        return target;
    }

    private static ProxyItem storePinnedCandidate(ProxyItem source, ProxyItem candidate) {
        if (candidate == source) return source;
        // The proved public address is the one saved/applied, not a later DNS answer.
        rememberHarvested(candidate.address, candidate.port);
        synchronized (verifiedPool) {
            int index = verifiedPool.indexOf(source);
            ProxyItem existing = findPoolCandidate(candidate);
            if (existing != null && isDirectSelectionCandidate(existing)
                    && !existing.nativeProbeInFlight.get()) {
                existing.pingMs = candidate.pingMs;
                existing.tcpMs = candidate.tcpMs;
                existing.tcpCheckedAt = candidate.tcpCheckedAt;
                existing.verifiedEndpoint = candidate.verifiedEndpoint;
                if (index >= 0) verifiedPool.remove(index);
                return existing;
            }
            if (index >= 0 && existing == null) {
                verifiedPool.set(index, candidate);
            } else if (existing == null) {
                addCandidate(candidate);
            }
            return candidate;
        }
    }

    private static boolean startGuestSelectionProbe(ProxySelection selection, ProxyItem source, ProxyItem candidate) {
        if (!selection.guestEligible || selection.guestPending || selection.guestAttempts >= 2
                || selection.guestAttempted.contains(candidate)
                || selection.deadline - SystemClock.elapsedRealtime() < ColgramGuestProxyProbe.CHECK_TIMEOUT_MS
                || candidate.type == 2) return false;
        final String endpoint = probeEndpoint(candidate);
        boolean started = ColgramGuestProxyProbe.checkThroughBase(appContext, candidate.address, candidate.port,
                candidate.user, candidate.password, candidate.secret, selection.requested == null,
                (time, reason) -> mainHandler.post(() -> {
                    if (!selectionIsCurrent(selection) || selection.network != networkProbeEpoch.get()) return;
                    selection.inFlight--;
                    selection.guestPending = false;
                    boolean alive = time >= 0 && endpoint.equals(probeEndpoint(candidate));
                    selection.guestResult = alive ? "verified" : reason;
                    if (alive) {
                        candidate.pingMs = (int) Math.min(Integer.MAX_VALUE, time);
                        candidate.verifiedEndpoint = endpoint;
                    }
                    onSelectionProtocolResult(selection, source, candidate, alive);
                }));
        if (started) {
            selection.guestPending = true;
            selection.guestAttempts++;
            selection.guestAttempted.add(candidate);
            selection.guestResult = "pending";
        }
        return started;
    }

    private static String selectionProgress(ProxySelection selection) {
        SourceBatch batch = lastSourceBatch;
        String https = batch == null ? "—" : batch.available.get() + "/" + batch.total;
        String tcp = selection.requested != null ? "—" : selection.tcpOpen + "/" + selection.tcpFinished;
        return (selection.requested != null ? "" : "Источники: " + https + " · TCP: " + tcp
                + " · SOCKS5 CONNECT: " + selection.socksConnected + "/" + selection.socksReady + " · ")
                + "Telegram: " + selection.protocolAnswered + "/" + selection.protocolTargets.size();
    }

    private static String selectionDiagnostic(ProxySelection selection) {
        SourceBatch batch = lastSourceBatch;
        return "sources=" + (batch == null ? "0/0" : batch.available.get() + "/" + batch.total)
                + " sourceFinished=" + (batch == null ? 0 : batch.finished.get())
                + " tcp=" + selection.tcpOpen + "/" + selection.tcpFinished
                + " socks=" + selection.socksReady + "/" + selection.socksAttempted
                + " socksConnect=" + selection.socksConnected + "/" + selection.socksReady
                + " telegram=" + selection.protocolAnswered + "/" + selection.protocolTargets.size()
                + " guest=" + selection.guestResult + "/" + selection.guestAttempts;
    }

    private static void proxySelectionTick(ProxySelection selection) {
        mainHandler.removeCallbacks(selection.tick);
        if (!selectionIsCurrent(selection)) {
            if (proxySelection == selection) cancelProxySelection();
            return;
        }
        if (SystemClock.elapsedRealtime() >= selection.deadline) {
            selection.timeout.run();
            return;
        }
        if (selection.network != networkProbeEpoch.get()) {
            finishProxySelection(selection, null, "Сеть изменилась во время проверки; прокси выключен");
            return;
        }
        List<ProxyItem> candidates = selection.requested == null
                ? new ArrayList<>(verifiedPool) : new ArrayList<>(Collections.singletonList(selection.requested));
        sortCandidates(candidates);
        scheduleSelectionTcp(selection, candidates);
        for (ProxyItem candidate : candidates) {
            if (!isDirectSelectionCandidate(candidate)) continue;
            if (isPubliclySelectable(candidate)) {
                finishProxySelection(selection, candidate, "");
                return;
            }
            if (selection.inFlight >= FAST_NATIVE_PARALLEL) continue;
            if (candidate.nextEligibleAt > SystemClock.elapsedRealtime()) continue;
            String pinnedAddress = selection.tcpReachable.get(candidate);
            if (selection.requested == null && pinnedAddress == null) continue;
            final ProxyItem target = pinSelectionCandidate(candidate, pinnedAddress);
            boolean started = startGuestSelectionProbe(selection, candidate, target);
            // Fresh unauthenticated clients have no temporary DC key for native ping.
            // Running a keyless ping in parallel only delays/poisons the isolated verdict.
            if (!started && !selection.guestEligible && !selection.nativeAttempted.contains(target)
                    && !selection.guestAttempted.contains(target) && !candidate.nativeProbeInFlight.get()) {
                started = checkOne(target, alive -> {
                    selection.inFlight--;
                    onSelectionProtocolResult(selection, candidate, target, alive);
                });
                if (started) selection.nativeAttempted.add(target);
            }
            if (started) {
                selection.protocolTargets.add(target);
                selection.inFlight++;
            }
        }
        if (selectionIsCurrent(selection)) {
            proxySelectionStatus = "Проверяю прокси: " + selectionProgress(selection);
            mainHandler.postDelayed(selection.tick, 250L);
        }
    }

    private static void finishProxySelection(ProxySelection selection, ProxyItem chosen, String failure) {
        if (!selectionIsCurrent(selection)) return;
        ColgramRouteEpoch.runIfCurrent(selection.routeEpoch, () -> {
            if (!selectionIsCurrent(selection)) return;
            if (chosen != null && isPubliclySelectable(chosen)) {
                chosen.autoSelected = selection.requested == null;
                forceApplyProxy(chosen);
                if (currentActiveProxy == chosen) {
                    proxySelectionStatus = "Прокси включён: " + chosen;
                } else {
                    proxySelectionStatus = "Настройки прокси не применились; прокси выключен";
                    setUserProxyDisabled(true);
                }
            } else {
                proxySelectionStatus = failure + ". " + selectionProgress(selection);
                setUserProxyDisabled(true);
            }
            Log.i(TAG, "PROXY_DIAG outcome=" + (currentActiveProxy == chosen && chosen != null ? "enabled" : "disabled")
                    + " " + selectionDiagnostic(selection));
            proxySelectionGate.finish(selection.token);
            mainHandler.removeCallbacks(selection.timeout);
            mainHandler.removeCallbacks(selection.tick);
            proxySelection = null;
            scheduleBackgroundProxyCheck(BACKGROUND_PROXY_BUSY_RETRY_MS);
            notifyProxySettingsChanged();
            toast(proxySelectionStatus);
            startFastNativeChecks();
            if (selection.requested == null && ColgramConfig.isBuiltinProxyEnabled()) sweepFast(null);
        });
    }

    public static boolean isProxyEnabled(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return false;
        SharedPreferences preferences = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        return preferences.getBoolean("proxy_enabled", false);
    }

    /** True when Colgram has a runtime route applied, including an internal loopback route. */
    public static boolean hasActiveRouteForUi() {
        return currentActiveProxy != null || dcRemapItem != null;
    }

    /** Keep Telegram's stock proxy switch and Colgram's live-route state in sync. */
    public static void onStockProxyToggle(boolean enabled) {
        final long routeEpoch = ColgramRouteEpoch.next();
        Context ctx = appContext;
        if (ctx == null) return;
        if (enabled) {
            selectUserProxyForEpoch(ctx, null, routeEpoch, true);
            return;
        }
        cancelProxySelection();
        setUserProxyDisabled(true);
        ColgramConfig.setAutoProxyEnabled(false);
        ProxyItem active = currentActiveProxy;
        ColgramDpiBypass.startImmediately();
        if (dcRemapItem != null) {
            dcRemapItem = null;
            ColgramDcRemap.stop();
        }
        disableProxy(ctx);
        notifyProxySettingsChanged();
    }

    private static boolean isUserProxyDisabled() {
        Context ctx = appContext;
        return ctx != null && ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                .getBoolean(KEY_PROXY_MANUALLY_DISABLED, false);
    }

    private static void setUserProxyDisabled(boolean disabled) {
        Context ctx = appContext;
        if (ctx != null) {
            ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_PROXY_MANUALLY_DISABLED, disabled).apply();
        }
    }

    public static synchronized void toggleProxy(final Context context) {
        final long routeEpoch = ColgramRouteEpoch.next();
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        boolean wasPending = proxySelectionGate.isPending();
        cancelProxySelection();
        if (wasPending || (currentActiveProxy != null && !currentActiveProxy.isLocalDpi())) {
            setUserProxyDisabled(true);
            ColgramConfig.setAutoProxyEnabled(false);
            ColgramDpiBypass.startImmediately();
            if (dcRemapItem != null) {
                dcRemapItem = null;
                ColgramDcRemap.stop();
            }
            disableProxy(ctx);
            toast("Прокси: выключен");
            return;
        }
        SharedPreferences prefs = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        ProxyItem saved = prefs.getBoolean("proxy_auto_applied", false) ? null : findSavedProxy(prefs);
        if (saved != null && isInternalLoopbackEndpoint(saved)) saved = null;
        selectUserProxyForEpoch(ctx, saved, routeEpoch, true);
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
                if (p.address.equals(ip) && p.port == port && p.type == type
                        && p.secret.equals(secret == null ? "" : secret)
                        && p.user.equals(mainPrefs.getString("proxy_user", ""))
                        && p.password.equals(mainPrefs.getString("proxy_pass", ""))) return p;
            }
            // A saved endpoint that IS in the pool but has failed is not a user-entered proxy - it is
            // a dead node this app wrote itself, and handing it back is how the phone ends up on a
            // server with no connection while the row claims a proxy is on:
            //
            //   proxy_ip = 46.146.220.247   <- a harvested node, three failures and gone from the pool
            //   alive_nodes = ...             <- ten working nodes, none of them this one
            //
            // The entry is gone because prunePool() removed it, so the loop above could not match it,
            // and the fallback below treated a remembered address as a deliberate hand-typed choice.
            // Nothing distinguishes the two from here except the fact that a hand-typed proxy was never
            // in the pool to begin with - so it is checked against the ones that were, before being
            // accepted as new.
            if (everHarvested(ip, port)) {
                Log.i(TAG, "saved proxy " + ip + ":" + port + " was harvested and has since failed; "
                        + "not applying it as a user-entered proxy");
                return null;
            }
            // Not in the pool (e.g. a user-entered private proxy, which has no reason to be
            // there). Honour the choice anyway rather than quietly substituting something else.
            ProxyItem custom = new ProxyItem(ip, port, secret, type,
                    mainPrefs.getString("proxy_user", ""), mainPrefs.getString("proxy_pass", ""));
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
     * Whether this app ever harvested this endpoint itself.
     *
     * <p>The distinction that matters when a saved proxy is no longer in the pool: a node the harvest
     * brought in and that has since failed, or a server the user typed by hand. The two look identical
     * in preferences - an address and a port - and only one of them is a choice worth honouring. So the
     * addresses are remembered, and a remembered one that is not in the pool is a node that failed
     * rather than a proxy the user is entitled to have.
     *
     * <p>Bounded, because it is a set that only ever grows and a pool that rotates for months would
     * otherwise accumulate every address ever seen.
     */
    private static final java.util.Set<String> harvestedEndpoints =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    private static final int HARVEST_MEMORY_LIMIT = 4000;

    private static void rememberHarvested(String address, int port) {
        // Two feed threads reach this at once, and synchronizedSet only guards a single operation
        // - not add() beside an iterator(). The combined sequence threw
        // ConcurrentModificationException out of both, which killed the feed task that was in
        // flight; measured on the device as
        //
        //   W ColgramProxyManager: proxy feed task failed
        //   W ColgramProxyManager: java.util.ConcurrentModificationException
        //       at java.util.LinkedHashMap$LinkedHashIterator.remove(LinkedHashMap.java:1074)
        //       at org.colgram.core.ColgramProxyManager.rememberHarvested(...:3491)
        //
        // so a whole source of candidates was dropped on the floor and the pool stayed nearly
        // empty - the reported "proxies load forever and none are available". Holding the lock
        // across the add, the size check and the eviction makes the whole thing one atomic step.
        synchronized (harvestedEndpoints) {
            harvestedEndpoints.add(address + ":" + port);
            if (harvestedEndpoints.size() > HARVEST_MEMORY_LIMIT) {
                java.util.Iterator<String> it = harvestedEndpoints.iterator();
                it.next();
                it.remove();
            }
        }
    }

    private static boolean everHarvested(String address, int port) {
        return harvestedEndpoints.contains(address + ":" + port);
    }

    /** Kept for older callers. The foundation is mandatory and has no OFF transition. */
    private static final java.util.concurrent.atomic.AtomicLong dpiRequestGeneration = new java.util.concurrent.atomic.AtomicLong();

    public static void setDpiBypassEnabled(Context context, boolean enabled) {
        // Compatibility entry point. Removing a selected overlay never stops the base.
        ColgramConfig.setDpiBypassEnabled(true);
        ensureLocalBaseRoute(context);
    }

    public static void ensureLocalBaseRoute(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> ensureLocalBaseRoute(ctx));
            return;
        }
        ColgramDpiBypass.startImmediately();
        ProxyItem active = currentActiveProxy;
        if (active != null && !active.isLocalDpi()) return; // Never replace the selected overlay.
        if (!ColgramDpiBypass.isBound()) {
            Log.e(TAG, "LOCAL_BASE bind failed; not claiming a working Telegram route");
            return;
        }
        if (active != null && active.port == ColgramDpiBypass.activePort()) return;
        ProxyItem local = new ProxyItem("127.0.0.1", ColgramDpiBypass.activePort(), "", 0);
        forceApplyProxy(local);
        Log.i(TAG, "LOCAL_BASE attached; Telegram protocol not yet verified");
    }

    /**
     * The settings row now reports the bypass instead of switching it, so tapping it has to do
     * something useful: confirm the listener is bound and accepting, and rebind it if it is not.
     *
     * The listener is the app's only permanent private route, and a dead 127.0.0.1:9876 with no
     * way to notice is how it failed silently before. The check runs off the main thread because
     * it opens a socket.
     */
    public static void recheckBypassListener() {
        executor.execute(() -> {
            if (!ColgramDpiBypass.isBound()) {
                Log.w(TAG, "the desync listener is down; rebinding 127.0.0.1:"
                        + ColgramDpiBypass.activePort());
                ColgramDpiBypass.startImmediately();
                ColgramDpiBypass.awaitReady(10000);
            }
            int ping = testProxy("127.0.0.1", ColgramDpiBypass.activePort(), 3000);
            Log.i(TAG, "desync listener re-check: bound=" + ColgramDpiBypass.isBound()
                    + ", ping=" + ping + "ms");
            Context context = appContext;
            ProxyItem active = currentActiveProxy;
            if (context != null && active != null && active.isLocalDpi() && !hasSystemVpn(context)
                    && !ColgramConfig.isWarpEnabled() && tgnetConnectionState() != TG_STATE_CONNECTED) {
                requestFreshDcAddresses("local base recheck");
            }
        });
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            long requested = ColgramRouteEpoch.snapshot();
            mainHandler.post(() -> ColgramRouteEpoch.runIfCurrent(requested, () -> disableProxy(ctx)));
            return;
        }
        for (int account = 0; account < colgramAccountSlots; account++) {
            ctx.getSharedPreferences(account == 0 ? "mainconfig" : "mainconfig" + account, Context.MODE_PRIVATE)
                    .edit().putBoolean("proxy_enabled", false).apply();
        }
        ProxyItem old = currentActiveProxy;
        currentActiveProxy = null;
        lastApplyAt = 0L;
        if (old != null && old.baseFront != null) { old.baseFront.close(); old.baseFront = null; }
        ensureLocalBaseRoute(ctx);
        notifyProxySettingsChanged();
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
     * Outside bypass mode it also arms upstream's ProxyRotationController. That class checks every
     * entry with the native protocol checker and switches to the lowest-ping working one when
     * Telegram stalls in ConnectionStateConnectingToProxy. During a local bypass, candidates still
     * belong in the user's proxy list, but stock rotation must stay paused so it cannot replace the
     * active bypass route behind the user's back.
     */

    private static void publishPoolToStock() {
        Context ctx = appContext;
        if (ctx == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(ColgramProxyManager::publishPoolToStock);
            return;
        }
        try {
            disableStockRotation(ctx);
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
            Class<?> psClass = Class.forName("org.telegram.utils.proxy.ProxySettings");
            scClass.getDeclaredMethod("loadProxyList").invoke(null);
            removeInternalLoopbackProxies(scClass);
            removeUnselectableManagedProxies(scClass);
            java.lang.reflect.Constructor<?> constructor = piClass.getConstructor(psClass);
            Method addProxy = scClass.getDeclaredMethod("addProxy", piClass);
            java.util.Set<String> managed = readProxyIdentities(KEY_MANAGED_PROXIES);
            int published = 0;
            for (ProxyItem p : getSelectableProxies()) {
                if (!isPubliclySelectable(p)) continue;
                if (published >= MAX_PUBLISHED_TO_STOCK) break;
                Object settings = buildProxyListSettings(p);
                if (settings == null) continue;
                Object info = addProxy.invoke(null, constructor.newInstance(settings));
                if (info == null) continue;
                info.getClass().getField("available").setBoolean(info, true);
                info.getClass().getField("ping").setLong(info, p.pingMs);
                info.getClass().getField("availableCheckTime").setLong(info, p.lastCheckAt);
                if (!isManuallySavedProxy(p)) managed.add(proxyIdentity(p));
                published++;
            }
            ctx.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_MANAGED_PROXIES, managed).apply();
            scClass.getDeclaredMethod("saveProxyList").invoke(null);
            Log.i(TAG, "published " + published + " protocol-verified proxy choices");
            // Only Colgram's generation-owned selection/rotation may activate pool entries.
            // Stock rotation has no ownership token and must not race an explicit Direct/WARP.
        } catch (Throwable error) {
            Log.e(TAG, "publishPoolToStock error", error);
        }
    }

    private static final String KEY_MANAGED_PROXIES = "managed_proxy_identities";
    private static final String KEY_MANUAL_PROXIES = "manual_proxy_identities";

    private static java.util.Set<String> readProxyIdentities(String key) {
        Context ctx = appContext;
        return ctx == null ? new java.util.HashSet<>() : new java.util.HashSet<>(
                ctx.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE)
                        .getStringSet(key, Collections.emptySet()));
    }

    private static String proxyIdentity(ProxyItem proxy) {
        try {
            String identity = proxy.type + "|" + proxy.address + "|" + proxy.port
                    + "|" + proxy.secret + "|" + proxy.user + "|" + proxy.password;
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void rememberManualProxy(ProxyItem proxy) {
        if (appContext == null || isInternalLoopbackEndpoint(proxy)) return;
        java.util.Set<String> manual = readProxyIdentities(KEY_MANUAL_PROXIES);
        manual.add(proxyIdentity(proxy));
        appContext.getSharedPreferences(PROXY_PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_MANUAL_PROXIES, manual).apply();
    }

    /** Only the manual editor/link flow may promote a saved entry to a user-owned record. */
    public static void markManuallySavedProxy(Context context, Object proxyInfo) {
        if (context == null) return;
        ProxyItem proxy = itemFromProxyInfo(proxyInfo);
        if (proxy == null) return;
        if (appContext == null) appContext = context.getApplicationContext();
        rememberManualProxy(proxy);
    }

    private static boolean isManuallySavedProxy(ProxyItem proxy) {
        if (readProxyIdentities(KEY_MANUAL_PROXIES).contains(proxyIdentity(proxy))) return true;
        Context ctx = appContext;
        if (ctx == null || everHarvested(proxy.address, proxy.port)) return false;
        SharedPreferences prefs = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        return !prefs.getBoolean("proxy_auto_applied", false)
                && proxy.address.equals(prefs.getString("proxy_ip", ""))
                && proxy.port == prefs.getInt("proxy_port", 0)
                && proxy.type == prefs.getInt("proxy_type", -1);
    }

    private static ProxyItem itemFromProxyInfo(Object info) {
        if (info == null) return null;
        try {
            Object settings = info.getClass().getField("settings").get(info);
            Class<?> type = settings.getClass();
            String name = String.valueOf(type.getMethod("getType").invoke(settings));
            return new ProxyItem(String.valueOf(type.getMethod("getAddress").invoke(settings)),
                    ((Number) type.getMethod("getPort").invoke(settings)).intValue(),
                    String.valueOf(type.getMethod("getSecret").invoke(settings)),
                    "WEB".equals(name) ? 2 : "MTPROTO".equals(name) ? 1 : 0,
                    String.valueOf(type.getMethod("getUser").invoke(settings)),
                    String.valueOf(type.getMethod("getPassword").invoke(settings)));
        } catch (Throwable ignored) { return null; }
    }

    /** Manual entries remain editable; harvested candidates are visible only while verified. */
    public static boolean isProxyCatalogVisible(Object proxyInfo) {
        ProxyItem item = itemFromProxyInfo(proxyInfo);
        if (item == null || isInternalLoopbackEndpoint(item)) return false;
        if (readProxyIdentities(KEY_MANUAL_PROXIES).contains(proxyIdentity(item))) return true;
        // An older version persisted feed entries without provenance. Do not assume an
        // unknown row is a user-owned route, and do not remove it until proven managed.
        return isPubliclySelectable(findPoolCandidate(item));
    }

    /** Stock pings cannot independently age out managed rows that the catalog verifies. */
    public static boolean usesCatalogVerifier(Object proxyInfo) {
        ProxyItem item = itemFromProxyInfo(proxyInfo);
        return item != null && item.type != 2 && everHarvested(item.address, item.port)
                && !readProxyIdentities(KEY_MANUAL_PROXIES).contains(proxyIdentity(item));
    }

    private static void removeUnselectableManagedProxies(Class<?> sharedConfigClass) throws Exception {
        Object value = sharedConfigClass.getDeclaredField("proxyList").get(null);
        if (!(value instanceof List)) return;
        List<?> list = (List<?>) value;
        Object current = sharedConfigClass.getDeclaredField("currentProxy").get(null);
        synchronized (list) {
            for (int index = list.size() - 1; index >= 0; index--) {
                Object info = list.get(index);
                if (isProxyCatalogVisible(info)) continue;
                ProxyItem hidden = itemFromProxyInfo(info);
                if (hidden == null || !everHarvested(hidden.address, hidden.port)
                        && !readProxyIdentities(KEY_MANAGED_PROXIES).contains(proxyIdentity(hidden))) continue;
                list.remove(index);
                if (current == info) sharedConfigClass.getDeclaredField("currentProxy").set(null, null);
            }
        }
    }

    /** Feed stock protocol verdicts back into the shared candidate state, not a parallel fiction. */
    public static void onStockProxyCheckResult(Object proxyInfo, long time) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> onStockProxyCheckResult(proxyInfo, time));
            return;
        }
        ProxyItem identity = itemFromProxyInfo(proxyInfo);
        if (identity == null) return;
        ProxyItem item = findPoolCandidate(identity);
        if (item == null && identity.equals(currentActiveProxy)) item = currentActiveProxy;
        if (item == null) return;
        if (item != currentActiveProxy && usesCatalogVerifier(proxyInfo)) return;
        // The stock checker tests the public identity, never our process-local chain front.
        if (item.chainPort > 0 || item.chainRelay != null || item.mimicActive) return;
        item.pingMs = time >= 0 ? (int) Math.min(Integer.MAX_VALUE, time) : -2;
        if (time >= 0) item.verifiedEndpoint = probeEndpoint(item);
        onVerdict(item, time >= 0);
    }

    private static void updateSharedConfigProxyState(ProxyItem proxy) {
        try {
            disableStockRotation(appContext);
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
            Class<?> psClass = Class.forName("org.telegram.utils.proxy.ProxySettings");

            Field loaded = scClass.getDeclaredField("proxyListLoaded");
            loaded.setAccessible(true);
            loaded.setBoolean(null, false);
            scClass.getDeclaredMethod("loadProxyList").invoke(null);
            removeInternalLoopbackProxies(scClass);

            Object settings = buildProxyListSettings(proxy);
            if (settings != null) {
                java.lang.reflect.Constructor<?> piCtor = piClass.getConstructor(psClass);
                Method addProxy = scClass.getDeclaredMethod("addProxy", piClass);
                Object info = addProxy.invoke(null, piCtor.newInstance(settings));
                if (info != null && !isInternalLoopbackEndpoint(proxy)) {
                    Field current = scClass.getDeclaredField("currentProxy");
                    current.setAccessible(true);
                    current.set(null, info);
                }
            } else if (isInternalLoopbackEndpoint(proxy)) {
                Field current = scClass.getDeclaredField("currentProxy");
                current.setAccessible(true);
                current.set(null, null);
            }
            scClass.getDeclaredMethod("saveProxyList").invoke(null);
            if (isInternalLoopbackEndpoint(proxy) || proxy == dcRemapItem) {
                disableStockRotation(appContext);
            } else {
                enableStockRotation(appContext);
            }
        } catch (Throwable t) {
            Log.e(TAG, "SharedConfig proxy state error", t);
        }
    }

    public static void notifyProxySettingsChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(ColgramProxyManager::notifyProxySettingsChanged);
            return;
        }
        try {
            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
            Method getGlobalInstance = ncClass.getDeclaredMethod("getGlobalInstance");
            getGlobalInstance.setAccessible(true);
            Object globalNc = getGlobalInstance.invoke(null);

            Field changedField = ncClass.getDeclaredField("proxySettingsChanged");
            changedField.setAccessible(true);
            int changed = changedField.getInt(null);
            Method post = ncClass.getDeclaredMethod("postNotificationName", int.class, Object[].class);
            post.setAccessible(true);
            post.invoke(globalNc, changed, new Object[0]);
        } catch (Throwable ignored) {}
    }

    /**
     * Remove loopback listeners left in Telegram's serialized proxy list by older builds.
     * A 127.0.0.1:<ephemeral-port> entry is private to this process and cannot be an external
     * proxy; keeping it in SharedConfig makes it look like the harvested pool consists of dead
     * proxies and restores a stale port after restart.
     */
    private static int removeInternalLoopbackProxies(Class<?> sharedConfigClass) {
        if (sharedConfigClass == null) return 0;
        try {
            Field listField = sharedConfigClass.getDeclaredField("proxyList");
            listField.setAccessible(true);
            Object value = listField.get(null);
            if (!(value instanceof List)) return 0;

            List<?> proxyList = (List<?>) value;
            int removed = 0;
            synchronized (proxyList) {
                // Reverse-index removal avoids fail-fast iterators while Telegram has a
                // previously queued controller callback. Nulls also break saveProxyList's sort.
                for (int i = proxyList.size() - 1; i >= 0; i--) {
                    Object item = proxyList.get(i);
                    if (item == null || isLoopbackProxyInfo(item)) {
                        proxyList.remove(i);
                        removed++;
                    }
                }
            }

            boolean changed = removed > 0;
            Field currentField = sharedConfigClass.getDeclaredField("currentProxy");
            currentField.setAccessible(true);
            Object current = currentField.get(null);
            if (current != null && isLoopbackProxyInfo(current)) {
                currentField.set(null, null);
                changed = true;
            }
            if (changed) {
                sharedConfigClass.getDeclaredMethod("saveProxyList").invoke(null);
                Log.i(TAG, "removed " + removed + " stale loopback proxy entries from Telegram's saved list");
            }
            return removed;
        } catch (Throwable t) {
            Log.w(TAG, "could not clean stale loopback entries from Telegram's proxy list: " + t);
            return 0;
        }
    }

    private static boolean isLoopbackProxyInfo(Object proxyInfo) {
        if (proxyInfo == null) return false;
        try {
            Field settingsField = proxyInfo.getClass().getField("settings");
            Object settings = settingsField.get(proxyInfo);
            if (settings == null) return false;
            Method addressMethod = settings.getClass().getMethod("getAddress");
            return isLoopbackHost(String.valueOf(addressMethod.invoke(settings)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void enableStockRotation(Context ctx) {
        try {
            if (!ColgramConfig.isAutoProxyEnabled() || isUserProxyDisabled()
                    || currentActiveProxy == null || !isFreshProtocolVerified(currentActiveProxy)) return;
            // Refuse whenever something else owns the route. This function is the only writer of the
            // flag, so a guard here covers every call site at once - and the call sites could not
            // each be trusted to know, because the two that fire from the publication path are
            // background tasks running against state that was true when they started and is not now.
            //
            // WARP, the local desync listener and the direct remap each replace the whole route, and
            // Telegram's rotator will pick a node and hand the phone to it. That is how the proxy
            // switched itself on beside a WARP tunnel the user had just turned on.
            if (ColgramConfig.isWarpEnabled()) {
                Log.i(TAG, "WARP owns the route; leaving Telegram's rotator paused");
                return;
            }
            if (currentActiveProxy != null
                    && ((currentActiveProxy.isLocalDpi() && localBypassUsable())
                    || isInternalLoopbackEndpoint(currentActiveProxy)) || isDcRemapActive()) {
                Log.i(TAG, "a Colgram route owns traffic; leaving Telegram's rotator paused");
                return;
            }
            // The latch, not the live route. disableStockRotation() is called from the bypass
            // start path and enableStockRotation() runs from the pool-publication path a few
            // milliseconds later - before forceApplyProxy() has set currentActiveProxy. The guard
            // above reads null there and lets the rotator back on, which is the flip-flop measured
            // on the device four times in ten seconds:
            //
            //   20:09:30.175  Telegram stock rotation paused while a Colgram bypass route owns traffic
            //   20:09:30.186  Telegram's own proxy checker and rotator enabled
            //   20:09:30.357  Telegram stock rotation paused while a Colgram bypass route owns traffic
            //   20:09:30.358  Telegram's own proxy checker and rotator enabled
            //
            // Whichever won last decided the proxy behaviour. Only his hand on the proxy row or an
            // explicit teardown clears the latch.
            if (stockRotationPausedByColgram) {
                Log.i(TAG, "Colgram still owns the route; leaving Telegram's rotator paused");
                return;
            }
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

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String normalized = host.trim();
        return normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(normalized)
                || "localhost".equalsIgnoreCase(normalized);
    }

    private static boolean isInternalLoopbackEndpoint(ProxyItem proxy) {
        return proxy != null && isLoopbackHost(proxy.address);
    }

    private static void disableStockRotation(Context ctx) {
        if (ctx == null) return;
        try {
            stockRotationPausedByColgram = true;
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Field enabled = scClass.getDeclaredField("proxyRotationEnabled");
            enabled.setAccessible(true);
            enabled.setBoolean(null, false);
            ctx.getSharedPreferences("userconfing", Context.MODE_PRIVATE).edit()
                    .putBoolean("proxyRotationEnabled", false).apply();
            Log.i(TAG, "Telegram stock rotation paused while a Colgram bypass route owns traffic");
        } catch (Throwable t) {
            Log.w(TAG, "could not pause stock rotation: " + t.getMessage());
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
            if (p.equals(item)) return true;
        }
        return false;
    }

    public static ProxyItem getCurrentActiveProxy() { return currentActiveProxy; }
    public static List<ProxyItem> getVerifiedPool() {
        List<ProxyItem> result = new ArrayList<>(verifiedPool);
        sortCandidates(result);
        return result;
    }

    /** Entries the native protocol checker actually confirmed working. */
    public static int getAliveCount() {
        int c = 0;
        for (ProxyItem p : new ArrayList<>(verifiedPool)) if (isFreshProtocolVerified(p)) c++;
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

    /**
     * Reachable tunnels, fastest first, for callers that need to carry plain HTTP(S): the bot
     * API, the mail providers, the proxy lists. SOCKS relays and CONNECT relays are both
     * included; the caller picks the handshake per relay kind.
     */
    public static List<ColgramProxyChain.Relay> getReachableRelays() {
        List<ColgramProxyChain.Relay> out = new ArrayList<>();
        synchronized (relayPool) {
            for (ColgramProxyChain.Relay r : relayPool) {
                if (!r.dead && r.rttMs >= 0) out.add(r);
            }
        }
        out.sort((a, b) -> Integer.compare(a.rttMs, b.rttMs));
        return out;
    }

    /**
     * Loopback port of a relay front that can deliver {@code host:port}, for the DPI
     * listener's gateway fallback. Opens (or reuses) the chain the same way sweep does, so
     * the relay-miss cache and per-relay bookkeeping stay in one place. Returns 0 when no
     * measured relay could be engaged.
     */
    public static int openRelayRouteFor(String host, int port) {
        List<ColgramProxyChain.Relay> relays = getReachableRelays();
        if (relays.isEmpty()) return 0;
        ColgramProxyChain.Relay relay = ColgramProxyChain.pickReachableRelay(
                relays, host, port, 2500);
        if (relay == null) return 0;
        return ColgramProxyChain.open(host, port, relay);
    }

    /**
     * A node worth dialling RIGHT NOW. Stale verdicts are the "random dead proxy" bug:
     * nativeVerified survived from an hour-old check while the node died, and the auto
     * fallback grabbed exactly those. Every candidate gets a fresh TCP dial before it can
     * be applied; verdict staleness orders the attempts.
     */
    private static ProxyItem pickVerifiedAliveNow(int maxTcpChecks) {
        List<ProxyItem> candidates = getSelectableProxies();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * The stock "Использовать прокси" switch goes through here: it applies a LIVE verified
     * node instead of SharedConfig.currentProxy, which can be a process-local loopback left
     * behind by the bypass route. Applies through the regular forceApplyProxy path, so
     * persistence and WARP exclusion behave exactly like a user-picked node.
     *
     * @return false when no candidate survived a fresh TCP dial (nothing to enable)
     */
    public static boolean applyBestVerifiedNow() {
        List<ProxyItem> candidates = getSelectableProxies();
        if (candidates.isEmpty()) {
            checkPoolNow();
            return false;
        }
        ProxyItem picked = candidates.get(0);
        picked.autoSelected = true;
        forceApplyProxy(picked);
        return currentActiveProxy == picked;
    }

    /** Cheap gate for the listener's gateway fallback: is there anything to hand off to. */
    public static boolean hasReachableRelays() {
        synchronized (relayPool) {
            for (ColgramProxyChain.Relay r : relayPool) {
                if (!r.dead && r.rttMs >= 0) return true;
            }
        }
        return false;
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
