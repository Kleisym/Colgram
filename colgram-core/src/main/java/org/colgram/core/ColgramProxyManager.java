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
        /** Set when a native MTProto/SOCKS handshake succeeded at least once. */
        public volatile boolean nativeVerified = false;
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
        /**
         * FakeTLS handshake already failed once and a browser-fingerprint mimic front was tried
         * for this node. One attempt per process keeps the prober from burning handshakes on a
         * node whose real problem is a blocked IP.
         */
        public volatile boolean mimicTried = false;
        /** True while this node is applied through a live mimic front (chainPort holds its port). */
        public volatile boolean mimicActive = false;

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
                    && type == other.type && secret.equals(other.secret);
        }

        @Override
        public int hashCode() {
            int hash = address.hashCode() * 31 + port;
            return (hash * 31 + type) * 31 + secret.hashCode();
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
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
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
        "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/all_proxies.txt",
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
        "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
        "https://raw.githubusercontent.com/neobxod/mtproto-for-telegram/master/all_proxies.txt",
        "https://raw.githubusercontent.com/mmpx12/proxy-list/master/mtproto.txt",
        "https://raw.githubusercontent.com/roosterkid/openproxylist/main/MTPROTO_RAW.txt",
        "https://raw.githubusercontent.com/soroushmirzaei/telegram-configs-collector/main/protocols/mtproto",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/mtproto.txt",
        "https://raw.githubusercontent.com/MrMohebi/xray-proxy-grabber-telegram/master/collected-proxies/mtproto/actives/actives.txt",
        "https://raw.githubusercontent.com/Epodonios/proxy2http/main/mtproto.txt",
        "https://raw.githubusercontent.com/Vann-Dev/proxy-list/main/Proxy/MTProto.txt",
        "https://raw.githubusercontent.com/casals-ar/proxy-list/main/mtproto",
        "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/proxy.txt",
        "https://raw.githubusercontent.com/soroushmirzaei/telegram-configs-collector/main/security/mtproto/reality",
        "https://raw.githubusercontent.com/soroushmirzaei/telegram-configs-collector/main/countries/ru/mixed",
        "https://raw.githubusercontent.com/tgmtproxy/telegram-mtproto-proxy-list/main/censorship_resistant.txt",
    };
    private static final String[] PROXY_SOURCES_MTPROTO_JSON = {
        "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/proxies.json",
        "https://zakky8.github.io/mtproto-proxy-pro/proxies.json",
        "https://mtpro.xyz/api/?type=mtproto",
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/tg/mtproto.json",
    };
    private static final String[] PROXY_SOURCES_SOCKS = {
        "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
        "https://raw.githubusercontent.com/r00tee/Proxy-List/main/Socks5.txt",
        "https://raw.githubusercontent.com/mzyui/proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt",
        "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/socks5.txt",
        "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt",
        "https://raw.githubusercontent.com/casals-ar/proxy-list/main/socks5",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
        "https://raw.githubusercontent.com/zloi-user/hideip.me/main/socks5.txt",
        "https://raw.githubusercontent.com/mmpx12/proxy-list/master/socks5.txt",
        "https://raw.githubusercontent.com/sunny9577/proxy-scraper/master/generated/socks5_proxies.txt",
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
        "https://proxyspace.pro/socks5.txt",
        "https://mtpro.xyz/api/?type=socks5",
    };

    /** Reachable public tunnels let us test and, if needed, chain a Telegram proxy. */
    private static final String[] RELAY_SOURCES_HTTP = {
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
        "https://raw.githubusercontent.com/r00tee/Proxy-List/main/Https.txt",
        "https://raw.githubusercontent.com/mzyui/proxy-list/main/http.txt",
        "https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/http.txt",
        "https://raw.githubusercontent.com/prxchk/proxy-list/main/http.txt",
        "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/http.txt",
        "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/http.txt",
        "https://raw.githubusercontent.com/zloi-user/hideip.me/main/http.txt",
        "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
        "https://raw.githubusercontent.com/casals-ar/proxy-list/main/http",
        "https://raw.githubusercontent.com/sunny9577/proxy-scraper/master/generated/http_proxies.txt",
        "https://raw.githubusercontent.com/mmpx12/proxy-list/master/http.txt",
        "https://proxyspace.pro/http.txt",
    };
    private static final String[] RELAY_SOURCES_SOCKS = {
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
        "https://raw.githubusercontent.com/r00tee/Proxy-List/main/Socks5.txt",
        "https://raw.githubusercontent.com/mzyui/proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/socks5.txt",
        "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/socks5.txt",
        "https://raw.githubusercontent.com/roosterkid/openproxylist/main/SOCKS5_RAW.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
        "https://proxyspace.pro/socks5.txt",
    };
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
    private static final int MAX_MTPROTO_CANDIDATES = 120;
    private static final int MAX_SOCKS_CANDIDATES = 120;
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
        registerNetworkCallback(appContext);

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
        ColgramDcRemap.invalidateFailedProbes();
        ColgramDpiBypass.clearConnectionFailureBackoff();
        directRouteCheckedAt = 0L;
        localRouteUnavailableUntil = 0L;
        Log.i(TAG, "invalidated direct-route probe cache (" + reason + ")");
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

        // 2. Start with the local path; public candidates are harvested immediately in the
        // background and are applied only after a successful Telegram-native handshake.
        initVerifiedPool();
        populateSharedConfigProxies();

        // Check cached and bundled candidates immediately. Harvesting remote lists may block
        // for many seconds (or fail entirely on the filtered network); it must not gate the
        // first native verdict and the first attempt to connect.
        sweepFast(null);

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
        // The direct-address remap is an explicit choice and its port is ephemeral, so it must be
        // re-bound on every start rather than restored from the saved 127.0.0.1:<old port>, which
        // points at a socket that died with the previous process.
        if (ColgramConfig.isWarpEnabled()) {
            // WARP owns the route when its persisted switch is on. The old branch order
            // force-applied the local proxy ten seconds later and forceApplyProxy() tore WARP
            // down behind the still-blue setting.
            Log.i(TAG, "WARP is selected; disabling any stale Telegram proxy preference and keeping WARP active");
            disableProxy(appContext);
        } else if (localBypassUsable() && ColgramConfig.isBuiltinProxyEnabled()) {
            // The local desync listener is what his "анонимный обход без прокси" switch turns on,
            // and it is the only bypass with no third party in the chain. It used to be started,
            // waited for, and then never pointed at: applying anything was gated behind a proxy
            // being saved or auto-proxy being on, so the switch produced a bound socket and
            // nothing else - "обходник не работает от слова совсем", exactly as reported. It is
            // not a proxy he must consent to; he asked for this one by name.
            ProxyItem local = null;
            for (ProxyItem p : verifiedPool) {
                if (p.isLocalDpi()) { local = p; break; }
            }
            // Desync can only reshape a stream the network still ROUTES. Measured on his
            // network: every Telegram DC address is refused at TCP within milliseconds, so
            // landing on the local hop first pinned Telegram to a dead route while verified
            // pool nodes sat unused - "Подключение прокси..." forever with the bypass on.
            // Probe one DC: dead means skip the local hop and apply the best verified node;
            // the bypass still applies where TCP answers (signature-based TSPU behaviour).
            ProxyItem verifiedBest = pickVerifiedAliveNow(5);
            // The direct route is judged on its own. It used to be probed only when a verified
            // pool node already existed, so on a fresh install - nothing verified yet - the answer
            // defaulted to "the local hop is fine", Telegram was pointed at 127.0.0.1:9876, and the
            // app sat there with the bypass looking enabled and no route carrying anything. It is
            // also why the row could not be switched off: the front kept being applied on every
            // start regardless of the switch.
            //
            // Measured on the device, where the DC is directly reachable:
            //
            //   20:23:20  Applying proxy: 127.0.0.1:9876 (type=0)
            //
            // while the same address answered HTTP from the shell on every one of five tries.
            boolean directDead = ColgramDcRemap.telegramDcProbe() < 0;
            if (local != null && verifiedBest != null && directDead) {
                // Only fall back to a third-party node when the user has the stock proxy switch on.
                // This branch fires on every start while the bypass switch is enabled, so
                // forceApplyProxy() here turned "Использовать прокси" blue by itself -- the exact
                // reported bug. The bypass is his own choice; silently swapping it for a public
                // proxy is not.
                if (isProxyEnabled) {
                    Log.i(TAG, "Telegram addresses are refused at TCP; applying verified "
                            + verifiedBest + " instead of the local desync route");
                    forceApplyProxy(verifiedBest);
                } else {
                    Log.i(TAG, "Telegram addresses are refused at TCP; leaving proxy off (user has not enabled it)");
                    disableProxy(appContext);
                }
            } else if (local != null && !directDead) {
                // The DC answers directly, so the desync front has nothing to defeat and costs a
                // hop and a handshaked proxy to reach a server that was already reachable.
                Log.i(TAG, "Telegram answers directly; leaving traffic off the local desync hop");
                disableProxy(appContext);
            } else if (local != null) {
                Log.i(TAG, "applying the local DPI bypass he switched on");
                forceApplyProxy(local);
            } else {
                Log.w(TAG, "DPI listener bound but no local entry in the pool to apply");
            }
        } else if (isUserProxyDisabled()) {
            Log.i(TAG, "public proxy route was toggled off; keeping direct/local bypass and feed checks active");
        } else if (ColgramConfig.isDcRemapEnabled() && ColgramConfig.isBuiltinProxyEnabled()) {
            executor.execute(() -> applyDcRemap(true));
        } else if (isProxyEnabled && ColgramConfig.isBuiltinProxyEnabled() && !verifiedPool.isEmpty()) {
            // Restore the proxy the user actually chose. This used to apply
            // verifiedPool.get(0) unconditionally, which is the local desync node - so anyone
            // who picked a public MTProto proxy was silently moved back onto the loopbar hop
            // the next time the app started, and "my proxy does not stick" was the result.
            ProxyItem saved = findSavedProxy(mainPrefs);
            if (saved != null && mainPrefs.getBoolean("proxy_auto_applied", false)) {
                // Auto-selected endpoints are ephemeral. A previous build persisted them, so
                // restoring one after it died pinned the app to "Подключение прокси...".
                Log.i(TAG, "discarding previously auto-selected proxy "
                        + saved.address + ":" + saved.port);
                for (int a = 0; a < colgramAccountSlots; a++) {
                    appContext.getSharedPreferences(a == 0 ? "mainconfig" : ("mainconfig" + a),
                            Context.MODE_PRIVATE).edit()
                            .putBoolean("proxy_enabled", false)
                            .remove("proxy_auto_applied").apply();
                }
                saved = null;
            }
            if (saved != null && isStaleLocalHop(saved)) {
                // A build of ours wrote the remap's 127.0.0.1:<port> into Telegram's prefs, so an
                // existing install can hold a hop that died with a process from last week. Applying
                // it means "Подключение прокси..." forever; drop it and stop claiming a proxy is on.
                Log.w(TAG, "saved proxy " + saved.address + ":" + saved.port
                        + " is a local hop from an earlier process; discarding it");
                for (int a = 0; a < colgramAccountSlots; a++) {
                    appContext.getSharedPreferences(a == 0 ? "mainconfig" : ("mainconfig" + a),
                            Context.MODE_PRIVATE).edit()
                            .putBoolean("proxy_enabled", false).apply();
                }
                saved = null;
                isProxyEnabled = false;
            }
            if (saved != null && saved.type != 2 && !saved.isLocalDpi()
                    && testProxy(saved.address, saved.port, 1500) < 0) {
                // A saved endpoint can disappear between sessions. A TCP timeout is enough to
                // reject it before Telegram's native connection loop gets pinned to it; keep
                // the saved address in the proxy list, but turn the active route off.
                Log.w(TAG, "discarding unreachable saved proxy " + saved.address + ":" + saved.port);
                for (int a = 0; a < colgramAccountSlots; a++) {
                    appContext.getSharedPreferences(a == 0 ? "mainconfig" : ("mainconfig" + a),
                            Context.MODE_PRIVATE).edit()
                            .putBoolean("proxy_enabled", false)
                            .putBoolean("proxy_auto_applied", false).apply();
                }
                saved = null;
                isProxyEnabled = false;
            }
            if (saved != null && !(saved.isLocalDpi() && !localBypassUsable())) {
                forceApplyProxy(saved);
                Log.i(TAG, "restored proxy " + saved.address + ":" + saved.port
                        + " type=" + saved.type
                        + " secret=" + (saved.secret == null || saved.secret.isEmpty() ? "none" : "set"));
            } else if (isProxyEnabled) {
                // No usable saved entry: fall back to the pool head, but never onto a local
                // listener that failed to bind - that pins Telegram to a dead 127.0.0.1.
                ProxyItem first = verifiedPool.get(0);
                if (first.isLocalDpi() && !localBypassUsable()) {
                    Log.w(TAG, "local bypass not ready; skipping it when applying the first proxy");
                    for (int a = 0; a < colgramAccountSlots; a++) {
                        appContext.getSharedPreferences(a == 0 ? "mainconfig" : ("mainconfig" + a),
                                Context.MODE_PRIVATE).edit().putBoolean("proxy_enabled", false).apply();
                    }
                } else {
                    forceApplyProxy(first);
                }
            }
        }

        // 4. Background — refresh public candidate feeds and native protocol verdicts.
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
    private static int watchdogUnconnectedTicks = 0;

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
        if (ctx == null || !ColgramConfig.isBuiltinProxyEnabled() || ColgramConfig.isWarpEnabled()) return;
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
        int state = tgnetConnectionState();
        if (state < 0) return;                        // no accessor: nothing to judge
        ProxyItem applied = currentActiveProxy;
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
        boolean reachable = active.isLocalDpi()
                || probeTcp(active.effectiveHost(), active.effectivePort(), 2500) >= 0;
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
        if (active.isLocalDpi() && !ColgramConfig.isAutoProxyEnabled()) {
            Log.i(TAG, "local DPI route is stalled; automatic public-proxy fallback is disabled");
            watchdogRedials = 0;
            return;
        }
        Log.i(TAG, "applied proxy " + active.address + ":" + active.port
                + " answers but carries nothing; rotating to another node");
        watchdogRedials = 0;
        switchToNextProxy(true);
    }

    /** Telegram's own connection state for the main account, or -1 when unavailable. */
    private static int tgnetConnectionState() {
        try {
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Object inst = cm.getMethod("getInstance", int.class).invoke(null, 0);
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

    private static long lastFreshAddressesAt = 0L;

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
    private static void requestFreshDcAddresses(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastFreshAddressesAt < FRESH_ADDRESSES_INTERVAL_MS) return;
        lastFreshAddressesAt = now;
        try {
            Class<?> cm = Class.forName("org.telegram.tgnet.ConnectionsManager");
            cm.getMethod("onRequestNewServerIpAndPort", int.class, int.class)
                    .invoke(null, 0, 0);
            cm.getMethod("onRequestNewServerIpAndPort", int.class, int.class)
                    .invoke(null, 2, 0);
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
            if (item.type == 2 || item.isLocalDpi()) {
                if (remaining.decrementAndGet() == 0) finishSweep(onDone);
                continue;
            }
            sweeper.execute(() -> {
                try {
                    if ("127.0.0.1".equals(item.address)) {
                        int rtt = testProxy(item.address, item.port, 1200);
                        item.tcpMs = rtt >= 0 ? rtt : -2;
                        item.tcpCheckedAt = SystemClock.elapsedRealtime();
                        int finished = doneCount.incrementAndGet();
                        if (finished % 10 == 0) postSweepProgress();
                        return;
                    }
                    int rtt = testProxy(item.address, item.port, 1200);
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
    private static final int FAST_NATIVE_PARALLEL = 6;
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
            if (appContext == null || (tgnetConnectionState() == TG_STATE_CONNECTED
                    && (currentActiveProxy == null || currentActiveProxy.nativeFailures < 2))) {
                fastNativeRunning.set(false);
                return;
            }
            int launched = 0;
            for (ProxyItem item : new ArrayList<>(verifiedPool)) {
                if (fastNativeInFlight >= FAST_NATIVE_PARALLEL) break;
                if (item.type == 2 || item.isLocalDpi() || item.tcpMs < 0
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
                    mainHandler.postDelayed(() -> {
                        if (!done.compareAndSet(false, true)) return;
                        item.nativeProbeInFlight.set(false);
                        fastNativeInFlight--;
                        onVerdict(item, false);
                        mainHandler.post(this);
                    }, NATIVE_CHECK_TIMEOUT_MS);
                } else {
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
                ProxyItem routeInUse = currentActiveProxy;
                if (routeInUse == null || shouldFallbackFromLocalDpi(routeInUse)) {
                    forceApplyProxy(picked);
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
                    target.nativeProbeInFlight.set(false);
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
            ProxyItem p = verifiedPool.get(idx);
            // A WEB entry needs a WebView bridge per check and the pool never holds one; the
            // user's own WEB proxy is exercised where it is actually applied.
            if (p.type == 2) continue;
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
        if (alive) mainHandler.post(() -> publishPoolToStock());
        if (item == currentActiveProxy && !alive) {
            reportProxyFailure();
            switchToNextProxy();
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
            for (ProxyItem p : verifiedPool) {
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
    private static final long REMEMBERED_VALID_MS = 60000L;

    private static boolean checkOne(final ProxyItem item, final AvailabilityHandler onResult) {
        if (!item.nativeProbeInFlight.compareAndSet(false, true)) return false;
        try {
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> rtdClass = Class.forName("org.telegram.tgnet.RequestTimeDelegate");
            final Object settings = buildProxySettings(item);
            if (settings == null) {
                item.nativeProbeInFlight.set(false);
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
                                if (!item.nativeProbeInFlight.compareAndSet(true, false)) return;
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
            item.nativeProbeInFlight.set(false);
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
            Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");
            Class<?> pstClass = Class.forName("org.telegram.proxy.ProxySettings$Type");
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
            Log.w(TAG, "no rotation candidate; holding the current proxy and re-fetching");
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
            if (p.isAvailable && p.nativeVerified) {
                if (best == null || betterCandidate(p, best)) best = p;
            }
        }
        return best;
    }

    /**
     * Native-verified first, then WebSocket bridges (they ride a CDN and survive IP blocks),
     * then fake-TLS, then lower latency. A node that has actually completed a protocol handshake
     * beats one that merely accepted a socket: "found a working proxy but never connects" was the
     * rotator offering TCP-open nodes that had never spoken MTProto.
     */
    private static boolean betterCandidate(ProxyItem a, ProxyItem b) {
        if (a.nativeVerified != b.nativeVerified) return a.nativeVerified;
        if ((a.type == 2) != (b.type == 2)) return a.type == 2;
        if (a.fakeTls != b.fakeTls) return a.fakeTls;
        if (a.pingMs < 0) return false;
        if (b.pingMs < 0) return true;
        return a.pingMs < b.pingMs;
    }

    /** Next protocol-verified entry after {@code currentIndex}. */
    private static ProxyItem roundRobinNext(int currentIndex) {
        int size = verifiedPool.size();
        for (int step = 1; step <= size; step++) {
            ProxyItem p = verifiedPool.get(((currentIndex + step) % size));
            if (p.equals(currentActiveProxy)) continue;
            if (p.isLocalDpi() && !localBypassUsable()) continue;
            if (!p.isAvailable || !p.nativeVerified) continue;
            return p;
        }
        return null;
    }

    /** Harvest public routes, then let Telegram verify candidates before any auto-connect. */
    private static void fetchAndVerifyAllSources() {
        final int before = verifiedPool.size();
        fetchSourceFeedsConcurrently();
        harvestRelays();
        Log.i(TAG, "proxy harvest: " + before + " -> " + verifiedPool.size()
                + " Telegram candidates; reachable relays=" + getRelayCount());
        mainHandler.post(() -> {
            startProber();
            publishPoolToStock();
            sweepFast(null);
        });
    }

    /** Downloads and parses every feed concurrently, then returns before the reachability sweep. */
    private static void fetchSourceFeedsConcurrently() {
        final List<Runnable> tasks = new ArrayList<>();
        int mtprotoFeeds = 0;
        for (final String url : PROXY_SOURCES_MTPROTO_LINKS) {
            if (mtprotoFeeds++ >= MAX_STARTUP_MTPROTO_FEEDS) break;
            tasks.add(() -> fetchProxiesLinkList(url, 24));
        }
        int jsonFeeds = 0;
        for (final String url : PROXY_SOURCES_MTPROTO_JSON) {
            if (jsonFeeds++ >= MAX_STARTUP_JSON_FEEDS) break;
            tasks.add(() -> fetchProxiesJson(url, 30));
        }
        int socksFeeds = 0;
        for (final String url : PROXY_SOURCES_SOCKS) {
            if (socksFeeds++ >= MAX_STARTUP_SOCKS_FEEDS) break;
            tasks.add(() -> fetchSocksList(url, 24));
        }
        runSourceFetchTasks(tasks, "proxy feed");
    }

    /**
     * Independent HTTPS feed requests used to block each other in a serial loop. Run the
     * bounded feed batch together so one stalled source no longer delays every healthy one.
     */
    private static void runSourceFetchTasks(List<Runnable> tasks, String label) {
        if (tasks == null || tasks.isEmpty()) return;
        final CountDownLatch finished = new CountDownLatch(tasks.size());
        for (final Runnable task : tasks) {
            sourceFetchers.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    Log.w(TAG, label + " task failed", t);
                } finally {
                    finished.countDown();
                }
            });
        }
        try {
            finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, label + " batch interrupted");
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
        if (verifiedPool.isEmpty()) initVerifiedPool();
        executor.execute(() -> {
            try {
                fetchAndVerifyAllSources();
            } catch (Throwable t) {
                Log.w(TAG, "OS-scheduled proxy source refresh failed", t);
            } finally {
                if (onComplete != null) mainHandler.post(onComplete);
            }
        });
    }

    /**
     * Pull tunnel lists and keep the ones that answer from here. A relay is only useful if this
     * machine can open a socket to it, so each candidate gets one 1.2 s TCP check before it is
     * kept; whether it can then reach a blocked node is discovered per chain, in pickRelay.
     */
    private static void harvestRelays() {
        final List<List<ColgramProxyChain.Relay>> foundBySource = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        int httpFeedCount = 0;
        for (String url : RELAY_SOURCES_HTTP) {
            if (httpFeedCount++ >= MAX_STARTUP_RELAY_FEEDS) break;
            final String feed = url;
            final List<ColgramProxyChain.Relay> source = new ArrayList<>();
            foundBySource.add(source);
            tasks.add(() -> collectRelayFeed(feed, false, source));
        }
        int socksFeedCount = 0;
        for (String url : RELAY_SOURCES_SOCKS) {
            if (socksFeedCount++ >= MAX_STARTUP_RELAY_FEEDS) break;
            final String feed = url;
            final List<ColgramProxyChain.Relay> source = new ArrayList<>();
            foundBySource.add(source);
            tasks.add(() -> collectRelayFeed(feed, true, source));
        }
        runSourceFetchTasks(tasks, "relay feed");
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
            collectRelays(httpGet(url), socks, source);
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
        if (item.type != 2 && item.port <= 0) return false;
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
                    if (p.type == item.type && p != currentActiveProxy
                            && p.failedVerdicts >= MAX_FAILED_VERDICTS) {
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

    /** Plain HTTP GET as a string, with the connection always returned. */
    private static String httpGet(String sourceUrl) throws Exception {
        // ColgramHttp rather than a bare connection: on a network that drops the source's IP
        // there is nothing to fetch directly, and a harvest that silently returns nothing is
        // indistinguishable from an empty list. This one retries through the SOCKS5 relays
        // already in the pool and names every attempt it made.
        //
        // Mirrors matter on the same network: raw.githubusercontent.com is throttled by the
        // same infrastructure that throttles Telegram, so a feed can be perfectly alive and
        // still unreadable without a VPN. Each GitHub feed is also tried through a CDN mirror
        // and a couple of proxying gateways before the source is called dead.
        List<String> candidates = new ArrayList<>();
        candidates.add(sourceUrl);
        candidates.addAll(mirrorUrls(sourceUrl));
        IOException lastFailure = null;
        int candidateAttempts = 0;
        for (String candidate : candidates) {
            // One canonical endpoint plus one CDN mirror is enough for optional list refreshes.
            // Walking six gateways per feed multiplied 40+ feeds into hundreds of sockets at
            // every launch, despite most candidate addresses being stale before their first check.
            if (candidateAttempts++ >= 2) break;
            try {
                ColgramHttp.Response r = ColgramHttp.getFast(candidate);
                if (r.code >= 200 && r.code < 300 && r.body != null && !r.body.isEmpty()) {
                    return r.body;
                }
                Log.i(TAG, "feed " + candidate + " answered HTTP " + r.code);
            } catch (IOException failed) {
                lastFailure = failed;
            } catch (Throwable t) {
                Log.w(TAG, "feed " + candidate + " failed: " + t.getMessage());
            }
        }
        if (lastFailure != null) throw lastFailure;
        return "";
    }

    /**
     * Mirror variants for a public feed URL, in try order. jsdelivr serves the same GitHub
     * content from a CDN that Russian networks do not touch; gitmirror and the gh-proxy
     * gateways re-serve raw.githubusercontent; github.io pages map to their source repos.
     */
    private static List<String> mirrorUrls(String url) {
        List<String> out = new ArrayList<>();
        if (url == null) return out;
        String raw = "raw.githubusercontent.com/";
        int rawAt = url.indexOf(raw);
        if (rawAt >= 0) {
            String rest = url.substring(rawAt + raw.length());
            int slash1 = rest.indexOf('/');
            int slash2 = slash1 < 0 ? -1 : rest.indexOf('/', slash1 + 1);
            if (slash1 > 0 && slash2 > slash1) {
                String user = rest.substring(0, slash1);
                String repo = rest.substring(slash1 + 1, slash2);
                String branchPath = rest.substring(slash2 + 1);
                int branchEnd = branchPath.indexOf('/');
                if (branchEnd > 0) {
                    out.add("https://cdn.jsdelivr.net/gh/" + user + "/" + repo + "@"
                            + branchPath.substring(0, branchEnd) + "/" + branchPath.substring(branchEnd + 1));
                }
                out.add("https://raw.gitmirror.com/" + rest);
                out.add("https://gh-proxy.com/https://raw.githubusercontent.com/" + rest);
                out.add("https://ghproxy.net/https://raw.githubusercontent.com/" + rest);
                out.add("https://ghfast.top/https://raw.githubusercontent.com/" + rest);
            }
            return out;
        }
        // owner.github.io/repo/path is served from the repo's pages branch; jsdelivr mirrors it.
        int ioAt = url.indexOf(".github.io/");
        if (ioAt > 0) {
            int scheme = url.indexOf("://");
            String owner = url.substring(scheme > 0 ? scheme + 3 : 0, ioAt);
            String rest = url.substring(ioAt + ".github.io/".length());
            int repoEnd = rest.indexOf('/');
            if (repoEnd > 0) {
                String repo = rest.substring(0, repoEnd);
                String path = rest.substring(repoEnd + 1);
                out.add("https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@main/" + path);
                out.add("https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@gh-pages/" + path);
            }
        }
        return out;
    }

    /** t.me/proxy?server=..&port=..&secret=.. and tg://webproxy links, in any parameter order. */
    private static void fetchProxiesLinkList(String sourceUrl, int limit) {
        String body = null;
        try {
            body = httpGet(sourceUrl);
        } catch (Throwable t) {
            Log.w(TAG, "link list fetch failed: " + sourceUrl, t);
            return;
        }
        if (body == null || body.isEmpty()) return;
        int added = 0;
        java.util.regex.Matcher link = Pattern.compile(
                "(?:tg://|https?://t\\.me/|https?://telegram\\.me/)(proxy|webproxy|socks)\\?[^\\s\"'<>]+",
                Pattern.CASE_INSENSITIVE).matcher(body);
        while (link.find() && added < limit) {
            String url = link.group().replace("&#38;", "&").replace("&amp;", "&");
            if (addedProxyFromLink(url, link.group(1))) added++;
        }
        // Plain "server=...&port=...&secret=..." triples that appear without a tg link prefix
        // (bare text lists). Kept from the old parser so legacy feeds still parse.
        if (added == 0) {
            Pattern pattern = Pattern.compile("server=([^&\\s]+)&port=(\\d+)&secret=([^&\\s]+)");
            Matcher m = pattern.matcher(body);
            while (m.find() && added < limit) {
                String server = m.group(1).replaceAll("\\.$", "");
                int port;
                try {
                    port = Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) {
                    continue;
                }
                String secret = ColgramMtprotoSecrets.canonicalHex(m.group(3));
                if (secret == null) continue;
                if (addCandidate(new ProxyItem(server, port, secret, 1))) added++;
            }
        }
        Log.i(TAG, "link source " + sourceUrl + " added " + added);
    }

    /** Parse one tg://proxy, tg://webproxy or tg://socks link into a pool candidate. */
    private static boolean addedProxyFromLink(String url, String kind) {
        String server = param(url, "server");
        if (server == null || server.isEmpty()) return false;
        server = server.replaceAll("\\.$", "");
        if (kind.equalsIgnoreCase("webproxy")) {
            String secret = ColgramMtprotoSecrets.canonicalHex(param(url, "secret"));
            if (secret == null || !secret.startsWith("dd")) return false;
            return addCandidate(new ProxyItem(server, 0, secret, 2));
        }
        int port;
        try {
            port = Integer.parseInt(param(url, "port"));
        } catch (NumberFormatException e) {
            return false;
        }
        String secret = ColgramMtprotoSecrets.canonicalHex(param(url, "secret"));
        boolean socks = kind.equalsIgnoreCase("socks");
        if (!socks && secret == null) return false;
        return addCandidate(new ProxyItem(server, port, socks ? "" : secret, socks ? 0 : 1));
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
        if (body == null || body.isEmpty()) return;
        // hideip.me and friends append country/uptime columns after the port; accept and ignore
        // anything following the second colon. The line end must tolerate \r: CRLF lists
        // matched ZERO lines when the anchor was plain "$", which starved the pool of SOCKS
        // relays and cascaded into unreachable feeds for every other fetcher.
        Pattern pattern = Pattern.compile("(?m)^\\s*([0-9a-zA-Z.\\-]+):(\\d{2,5})(?::|\\r?$)");
        Matcher m = pattern.matcher(body);
        int added = 0;
        Log.d(TAG, "socks body " + body.length() + "B head="
                + body.substring(0, Math.min(80, body.length())).replace("\n", "|"));
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

    private static void fetchProxiesJson(String sourceUrl, int limit) {
        String body;
        try {
            body = httpGet(sourceUrl);
        } catch (Throwable t) {
            Log.w(TAG, "Fetch error from " + sourceUrl, t);
            return;
        }
        if (body == null || body.isEmpty()) return;
        int added = 0;
        try {
            JSONArray arr;
            try {
                arr = new JSONArray(body);
            } catch (Exception notBareArray) {
                JSONObject root = new JSONObject(body);
                arr = root.optJSONArray("proxies");
                if (arr == null) throw notBareArray;
            }
            for (int i = 0; i < arr.length() && added < limit; i++) {
                JSONObject obj = arr.getJSONObject(i);
                // mtpro.xyz and hookzof use "host"; the dubblebyte/zakky feeds use "server".
                String server = obj.optString("server", obj.optString("host", ""));
                int port = obj.optInt("port", 0);
                String secret = ColgramMtprotoSecrets.canonicalHex(obj.optString("secret", ""));
                if (server.isEmpty() || port <= 0) continue;
                if (secret != null) {
                    if (addCandidate(new ProxyItem(server, port, secret, 1))) added++;
                } else if (obj.optString("type", "socks5").contains("socks")) {
                    if (addCandidate(new ProxyItem(server, port, "", 0))) added++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Parse error from " + sourceUrl, t);
            return;
        }
        Log.i(TAG, "json source " + sourceUrl + " added " + added);
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

    private static volatile long lastApplyAt = 0L;

    public static void forceApplyProxy(ProxyItem proxy) {
        if (proxy == null) return;
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
        if (ColgramConfig.isWarpEnabled()) {
            Log.i(TAG, "WARP is selected; ignoring background proxy application");
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
                            .putString("proxy_user", "")
                            .putString("proxy_pass", "")
                            .putString("proxy_secret", proxy.secret)
                            .putInt("proxy_type", proxy.type)
                            .putBoolean("proxy_auto_applied", proxy.autoSelected)
                            .apply();
                }
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
            // Runtime-selected endpoints are deliberately not persisted in step 1. Build the
            // setting from the selected item, not from stale preferences: otherwise the stock
            // method accepts an empty/old setting and silently disables the tunnel.
            boolean appliedThroughStockPath = false;
            boolean appliedThroughNativePath = false;
            try {
                Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");
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
                                "", "", proxy.secret);
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
        Context ctx = appContext;
        if (ctx == null) return;
        // The latch belongs to the router, not to the app. A press on the stock switch is the one
        // thing that may hand Telegram's own rotator back: from here on, Telegram's checker is
        // what the user asked for. Turning the switch off re-arms the latch in disableProxy().
        if (enabled) stockRotationPausedByColgram = false;
        setUserProxyDisabled(!enabled);
        if (!enabled) {
            ProxyItem active = currentActiveProxy;
            currentActiveProxy = null;
            lastApplyAt = 0L;
            if (active != null && active.isLocalDpi()) ColgramDpiBypass.stop();
            if (dcRemapItem != null) {
                dcRemapItem = null;
                ColgramDcRemap.stop();
            }
        }
        mainHandler.post(ColgramProxyManager::notifyProxySettingsChanged);
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
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        boolean currentlyEnabled = isProxyEnabled(ctx) || hasActiveRouteForUi();
        if (currentlyEnabled) {
            setUserProxyDisabled(true);
            ColgramConfig.setAutoProxyEnabled(false);
            if (currentActiveProxy != null && currentActiveProxy.isLocalDpi()) ColgramDpiBypass.stop();
            if (dcRemapItem != null) {
                dcRemapItem = null;
                ColgramDcRemap.stop();
            }
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

        // This method is reached from the user's explicit proxy button. Disable WARP here,
        // rather than in forceApplyProxy(), which is also called by background verification.
        if (ColgramConfig.isWarpEnabled()) {
            ColgramConfig.setWarpEnabled(false);
            ColgramWarpTunnel.bringDown(ctx);
        }
        setUserProxyDisabled(false);
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
                if (p.address.equals(ip) && p.port == port && p.type == type
                        && p.secret.equals(secret == null ? "" : secret)) return p;
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
        if (!enabled) {
            ColgramDpiBypass.stop();
            ProxyItem active = currentActiveProxy;
            if (active != null && active.isLocalDpi()) {
                disableProxy(context);
                onStockProxyToggle(false);
            }
            toast("Локальный обход выключен");
            return;
        }
        // Bind first, route second. The old order pointed Telegram at the port before bind.
        ColgramDpiBypass.startImmediately();
        executor.execute(() -> {
            boolean ready = ColgramDpiBypass.awaitReady(20000);
            ProxyItem local = findLocalDpiProxy();
            if (!ready || local == null) {
                Log.e(TAG, "desync bypass enabled but the listener never bound; leaving traffic alone");
                mainHandler.post(() -> toast("Обходчик не смог поднять 127.0.0.1:"
                        + ColgramDpiBypass.activePort()));
                return;
            }
            // The listener exists either way - it is the app's permanent private route - but it does
            // not get to TAKE the route when Telegram already answers directly.
            //
            // It used to be applied unconditionally here, which is what the notice button did on a
            // perfectly open network:
            //
            //   00:32:26  Telegram answers directly; leaving traffic off the local desync hop
            //   00:32:31  Applying proxy: 127.0.0.1:9876 (type=0)
            //
            // Two things had already established the direct route was fine, and the hop still won
            // because nothing here asked again. The desync front only has something to defeat when
            // the DC is filtered, so it is applied on that evidence and not on its own existence.
            if (ColgramDcRemap.telegramDcProbe() >= 0) {
                Log.i(TAG, "desync listener is up and Telegram answers directly; not taking the route");
                checkPoolNow();
                toast("Обходчик ТСПУ работает; Telegram отвечает напрямую");
                return;
            }
            Log.i(TAG, "applying local desync route before judging Telegram reachability");
            forceApplyProxy(local);
            checkPoolNow();
            toast("Локальный обход включён; проверяю соединение");
        });
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
        try {
            // Turning the route off is a decision about the route, and Telegram's own rotator must
            // not pick a node behind it: the next pool publication would otherwise re-enable the
            // stock checker, which is what put a proxy back on screen by itself.
            stockRotationPausedByColgram = true;
            currentActiveProxy = null;
            lastApplyAt = 0L;
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
        // Bypass mode suppresses only automatic switching, never feed harvesting/publication.
        // Returning here used to hide every fetched endpoint whenever the local listener was the
        // active route, which made automatic proxy loading appear disabled. Keep the endpoints
        // visible in Telegram's proxy list, but do not let its rotator replace the bypass route.
        // WARP owns the route too, and the stock rotator is what reaches past it.
        //
        // This only ever asked about the local desync listener and the direct remap, so while WARP was
        // selected the answer was "nobody owns the route" and rotation was switched ON - which is
        // what turned the proxy on by itself:
        //
        //   Telegram's own proxy checker and rotator enabled
        //   proxy_enabled                    = true
        //   colgram_proxy_manually_disabled  = true
        //   proxy_ip                         = 47.245.165.201
        //
        // One WARP switch, one press, and the phone was on a stranger's server with a proxy screen
        // entry the user never added. The rotator is Telegram's, it is enabled from the app's own
        // preferences, and nothing about WARP stopped it.
        boolean bypassOwnsRoute = ColgramConfig.isWarpEnabled()
                || (currentActiveProxy != null
                && ((currentActiveProxy.isLocalDpi() && localBypassUsable())
                || isInternalLoopbackEndpoint(currentActiveProxy))) || isDcRemapActive();
        try {
            // The stock rotation controller mutates this list. Pause it while we remove old
            // process-local entries and add reachable public endpoints, then restore its state.
            disableStockRotation(ctx);
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
            Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");

            scClass.getDeclaredMethod("loadProxyList").invoke(null);
            removeInternalLoopbackProxies(scClass);

            java.lang.reflect.Constructor<?> piCtor = piClass.getConstructor(psClass);
            Method addProxy = scClass.getDeclaredMethod("addProxy", piClass);

            if (verifiedPool.isEmpty()) {
                initVerifiedPool();
            }
            int published = 0;
            int tcpOnlyPublished = 0;
            // Pick the top of the pool rather than the first MAX_PUBLISHED_TO_STOCK in
            // insertion order: verified endpoints and low-latency ones are what the rotator
            // (and the user's own proxy screen) should see, not whichever list fed us first.
            java.util.List<ProxyItem> publishable = new ArrayList<>();
            for (ProxyItem p : verifiedPool) {
                if (p.nativeVerified && !p.isAvailable) continue;
                if (!p.nativeVerified && p.tcpMs < 0) continue;
                if (isInternalLoopbackEndpoint(p)) continue;
                if (buildProxyListSettings(p) == null) continue;
                publishable.add(p);
            }
            publishable.sort((a, b) -> {
                if (a.nativeVerified != b.nativeVerified) return a.nativeVerified ? -1 : 1;
                if (a.isAvailable != b.isAvailable) return a.isAvailable ? -1 : 1;
                int pa = a.pingMs > 0 ? a.pingMs : (a.tcpMs > 0 ? a.tcpMs : Integer.MAX_VALUE);
                int pb = b.pingMs > 0 ? b.pingMs : (b.tcpMs > 0 ? b.tcpMs : Integer.MAX_VALUE);
                return Integer.compare(pa, pb);
            });
            for (ProxyItem p : publishable) {
                if (published >= MAX_PUBLISHED_TO_STOCK) break;
                Object settings = buildProxyListSettings(p);
                if (settings == null) continue;
                addProxy.invoke(null, piCtor.newInstance(settings));
                published++;
                if (!p.nativeVerified) tcpOnlyPublished++;
            }

            if (bypassOwnsRoute) {
                disableStockRotation(ctx);
                Log.i(TAG, "bypass route active; published " + published
                        + " proxy candidates (" + tcpOnlyPublished + " TCP-only); stock rotation remains paused");
            } else {
                enableStockRotation(ctx);
            }
        } catch (Throwable t) {
            Log.e(TAG, "publishPoolToStock error", t);
            if (!bypassOwnsRoute) enableStockRotation(ctx);
        }
    }

    private static void updateSharedConfigProxyState(ProxyItem proxy) {
        try {
            disableStockRotation(appContext);
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
            Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");

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
        java.util.List<ProxyItem> candidates = new ArrayList<>();
        // Iterate a SNAPSHOT. verifiedPool is an ArrayList that the sweep prunes and the harvest
        // grows from background threads while this runs on one of them, and walking the live list
        // throws ConcurrentModificationException the moment the sizes disagree.
        //
        // It was fatal, not a caught-and-retried hiccup - it took the whole process down from a
        // pool worker thread, with nothing on screen to explain it:
        //
        //   23:14:11  FATAL EXCEPTION: pool-8-thread-3
        //   23:14:11  java.util.ConcurrentModificationException
        //   23:14:11    at ColgramProxyManager.pickVerifiedAliveNow(ColgramProxyManager.java:3827)
        //   23:14:11    at ColgramProxyManager.connectThroughBestNode(ColgramProxyManager.java:1365)
        //   23:14:11    at ColgramProxyManager.finishSweep(ColgramProxyManager.java:1120)
        //
        // which is the "приложение часто крашится" report exactly: a crash in the background with
        // no user action behind it.
        for (ProxyItem p : new ArrayList<>(verifiedPool)) {
            if (p.isLocalDpi() || p.type == 2) continue;
            if (p.nativeVerified && p.isAvailable) candidates.add(p);
        }
        candidates.sort((a, b) -> {
            long fa = a.lastCheckAt, fb = b.lastCheckAt;
            if (fa != fb) return fa > fb ? -1 : 1;
            return Integer.compare(a.pingMs > 0 ? a.pingMs : Integer.MAX_VALUE,
                    b.pingMs > 0 ? b.pingMs : Integer.MAX_VALUE);
        });
        for (ProxyItem p : candidates) {
            if (maxTcpChecks-- <= 0) break;
            if (testProxy(p.address, p.port, 1500) >= 0) return p;
        }
        return null;
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
        ProxyItem picked = pickVerifiedAliveNow(6);
        if (picked == null) {
            // Best effort when no protocol-verified node is alive yet: dial-test TCP-alive
            // candidates and apply the fastest. The prober keeps re-checking and the rotator
            // replaces a node that lies - refusing to enable at all left the switch dead even
            // with green "Доступен" rows on screen.
            java.util.List<ProxyItem> cands = new ArrayList<>();
            for (ProxyItem p : new ArrayList<>(verifiedPool)) {
                if (p.isLocalDpi() || p.type == 2) continue;
                if (p.failedVerdicts >= MAX_FAILED_VERDICTS) continue;
                int rtt = p.tcpMs > 0 ? p.tcpMs : testProxy(p.address, p.port, 1500);
                if (rtt > 0) cands.add(p);
            }
            cands.sort((a, b) -> Integer.compare(
                    a.tcpMs > 0 ? a.tcpMs : Integer.MAX_VALUE,
                    b.tcpMs > 0 ? b.tcpMs : Integer.MAX_VALUE));
            if (!cands.isEmpty()) picked = cands.get(0);
        }
        if (picked == null) return false;
        picked.autoSelected = false;
        forceApplyProxy(picked);
        return true;
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
