package org.colgram.core;

import android.util.Log;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramDpiBypass — Embedded Local SOCKS5 Proxy with Adaptive TCP Desync.
 *
 * Evades TSPU / RKN DPI by mangling the shape of our own outgoing packets so the DPI
 * mis-parses the stream while the real destination reassembles it correctly.
 *
 * 1. Multiple desync strategies (splitting at randomised offsets, TLS-record splitting,
 *    TCP urgent/OOB bytes), auto-probed per connection — see the strategy block below.
 * 2. Bounded direct dialing of the requested port and known same-DC aliases.
 * 3. Completely local (127.0.0.1:9876): no third-party server in the path at all.
 *
 * Why local matters: a proxy is a server somebody runs, and its operator sees your IP,
 * timing and volume by design. This listener is the only option with NO third party in
 * the chain, so it is both the most private and the only one that cannot be switched off
 * by a host going down. That is why it is preferred over public proxies rather than
 * treated as a last resort.
 *
 * Honest limits, so nobody has to rediscover them:
 *   * A DPI that blocks by DESTINATION (Russian "white list" drills) is not defeated by
 *     desync at all — the packet shape is irrelevant when the address itself is refused.
 *   * The strongest known technique, a decoy packet with a low IP TTL that reaches the DPI
 *     but expires before the destination, needs setsockopt(IP_TTL) and therefore JNI.
 *     Java cannot set IP TTL. See the notes in the project memory.
 */
public class ColgramDpiBypass {

    private static final String TAG = "ColgramDpiBypass";
    public static final int LOCAL_PORT = 9876;
    /**
     * The port the listener actually bound.
     *
     * <p>It starts at LOCAL_PORT and moves only when that port is held by something this process did
     * not let go of. The field exists because the alternative - failing to bind and reporting the
     * bypass down - is the exact symptom of the bypass not working, so a busy preferred port has to be
     * survivable. Everything that hands the port to Telegram reads this rather than the constant.
     */
    private static volatile int activePort = LOCAL_PORT;
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = false;
    /** True only once the socket is actually bound and accepting. */
    private static volatile boolean bound = false;
    private static volatile boolean startScheduled = false;
    private static Thread deferredStart;
    private static final Object READY_LOCK = new Object();
    /** Near-instant local proxy bind delay while keeping deferred async contract. */
    private static final long START_DELAY_MS = 50L;
    private static final String HINT_PREFS_NAME = "colgram_bypass_hints";
    private static volatile boolean hintsLoaded = false;
    private static volatile int cellularWssFragHint = -1;
    private static volatile int cellularWssSniHint = 0;
    private static volatile int wifiWssFragHint = -1;
    private static volatile int wifiWssSniHint = 0;
    private static final int MAX_WORKER_POOL_THREADS = 384;
    private static final ExecutorService workerPool = new java.util.concurrent.ThreadPoolExecutor(
            0, MAX_WORKER_POOL_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "colgram-dpi-worker-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((th, ex) -> Log.w(TAG, "dpi worker error: " + ex));
                    return t;
                }
            });

    static boolean executeWorker(Runnable task) {
        if (task == null) return false;
        try {
            workerPool.execute(task);
            return true;
        } catch (Throwable rejectedOrOom) {
            Log.w(TAG, "worker submission rejected: " + rejectedOrOom.getClass().getSimpleName());
            return false;
        }
    }

    static int maxWorkerPoolThreadsForTest() {
        return MAX_WORKER_POOL_THREADS;
    }

    static int systemWideClientCapForTest() {
        return SYSTEM_WIDE_CLIENT_CAP;
    }

    private static final int SYSTEM_WIDE_CLIENT_CAP = 256;
    private static final int MAX_TRACKED_MAP_ENTRIES = 512;
    // Admission backpressure prevents failed native SOCKS retries from spinning the CPU.
    private static final java.util.concurrent.Semaphore HANDSHAKE_SLOTS = new java.util.concurrent.Semaphore(96);
    private static final long REJECT_RETRY_DELAY_MS = 120L;
    private static final long CONNECT_FAILURE_BASE_DELAY_MS = 1200L;
    private static final long CONNECT_FAILURE_MAX_DELAY_MS = 15000L;
    private static final ColgramConnectFailureBackoff connectFailures =
            new ColgramConnectFailureBackoff(CONNECT_FAILURE_BASE_DELAY_MS,
                    CONNECT_FAILURE_MAX_DELAY_MS, 4);
    private static final java.util.concurrent.Semaphore DIAL_GROUPS = new java.util.concurrent.Semaphore(28);
    private static final int UPSTREAM_WAIT = 0, UPSTREAM_DIAL = 1, UPSTREAM_NO_SOCKET = 2,
            UPSTREAM_SOCKET = 3, UPSTREAM_SILENT = 4, UPSTREAM_REPLY = 5;
    private static volatile int upstreamState = UPSTREAM_WAIT;
    private static final java.util.Set<String> silentEndpoints =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final java.util.Map<String, Integer> silentEndpointCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_DC_BUCKETS = 12; // 0 = other, 1..5 = generic DC 1..5, 6..10 = media DC 1..5
    private static final java.util.concurrent.atomic.AtomicIntegerArray dcRawReplied =
            new java.util.concurrent.atomic.AtomicIntegerArray(MAX_DC_BUCKETS);
    private static final java.util.concurrent.atomic.AtomicIntegerArray dcRawFailed =
            new java.util.concurrent.atomic.AtomicIntegerArray(MAX_DC_BUCKETS);
    private static final java.util.concurrent.atomic.AtomicIntegerArray dcWsPreferred =
            new java.util.concurrent.atomic.AtomicIntegerArray(MAX_DC_BUCKETS);
    private static volatile long lastTunCheckAt = 0L;
    private static volatile boolean cachedTunUp = false;
    private static volatile android.content.Context appContext;

    private static final String[] RU_WHITELIST_PROBE_HOSTS = {"vk.com", "ya.ru"};
    private static final long L3_WHITELIST_CACHE_MS = 20_000L;
    private static final long L3_WHITELIST_DETECTED_CACHE_MS = 30_000L;
    private static final long L3_RELAY_ACTIVE_WINDOW_MS = 90_000L;
    private static final long L3_RELAY_PENALTY_MS = 180_000L;
    private static final int L3_RELAY_CONNECT_TIMEOUT_MS = 2500;
    private static final int L3_CELLULAR_RELAY_CONNECT_TIMEOUT_MS = 3200;
    private static final int L3_RELAY_MAX_CANDIDATES = 4;
    private static final int L3_DIRECT_RECOVERY_PROBE_MS = 900;
    private static final long L3_ZERO_RELAY_WARP_TRIGGER_MS = 8_000L;
    private static final int SOCKET_BUFFER_BYTES = 262144;
    private static final int STREAM_COPY_BUFFER_BYTES = 65536;
    static final int MAX_ACTIVE_SESSIONS = 192;
    private static volatile long l3WhitelistCheckedAt = 0L;
    private static volatile boolean strictL3WhitelistDetected = false;
    private static volatile long strictL3ZeroRelaysSinceMs = 0L;
    private static volatile long lastL3RelaySuccessAt = 0L;
    private static volatile String activeL3RelayLabel = "";
    /**
     * Single sticky Layer-2 L3 relay pinned for Telegram DC traffic so concurrent MTProto DC
     * connections always share ONE exit IP and never trigger AUTH_KEY_DUPLICATED.
     */
    private static volatile ColgramProxyChain.Relay pinnedTelegramL3Relay = null;
    private static final java.util.concurrent.CopyOnWriteArrayList<ActiveSession> ACTIVE_SESSIONS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Set<Socket> ACTIVE_DIAL_SOCKETS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.concurrent.atomic.AtomicLong networkEpoch =
            new java.util.concurrent.atomic.AtomicLong(0L);

    static long currentNetworkEpoch() {
        return networkEpoch.get();
    }

    static long bumpNetworkEpoch() {
        return networkEpoch.incrementAndGet();
    }

    static void trackDialSocket(Socket socket) {
        if (socket != null) {
            ACTIVE_DIAL_SOCKETS.add(socket);
        }
    }

    static void untrackDialSocket(Socket socket) {
        if (socket != null) {
            ACTIVE_DIAL_SOCKETS.remove(socket);
        }
    }

    /** Track a dialing socket so closeAllActiveSessions() aborts hung old-network dials immediately. */
    static void connectTracked(Socket socket, java.net.SocketAddress endpoint, int timeoutMs) throws java.io.IOException {
        if (socket == null) throw new java.io.IOException("null socket");
        trackDialSocket(socket);
        try {
            socket.connect(endpoint, timeoutMs);
        } finally {
            untrackDialSocket(socket);
        }
    }

    static int activeDialSocketCountForTest() {
        return ACTIVE_DIAL_SOCKETS.size();
    }

    static final class ActiveSession {
        final Socket client;
        final java.io.Closeable upstream;
        final boolean telegram;
        final long createdAtMs;
        volatile long lastRxAtMs;
        volatile long pendingReplySinceMs;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        ActiveSession(Socket client, java.io.Closeable upstream, boolean telegram, long nowMs) {
            this.client = client;
            this.upstream = upstream;
            this.telegram = telegram;
            this.createdAtMs = nowMs;
            this.lastRxAtMs = nowMs;
            this.pendingReplySinceMs = 0L;
        }

        void onClientTx(long nowMs) {
            if (pendingReplySinceMs == 0L) {
                pendingReplySinceMs = nowMs;
            }
        }

        void onUpstreamRx(long nowMs) {
            lastRxAtMs = nowMs;
            pendingReplySinceMs = 0L;
        }

        boolean closeNow() {
            if (!closed.compareAndSet(false, true)) return false;
            ACTIVE_SESSIONS.remove(this);
            closeQuietly(upstream);
            closeQuietly(client);
            return true;
        }
    }

    static ActiveSession registerActiveSession(Socket client, java.io.Closeable upstream, boolean telegram) {
        return registerActiveSessionAt(client, upstream, telegram, safeElapsedRealtime());
    }

    static ActiveSession registerActiveSessionAt(Socket client, java.io.Closeable upstream,
                                                 boolean telegram, long nowMs) {
        ActiveSession session = new ActiveSession(client, upstream, telegram, nowMs);
        ACTIVE_SESSIONS.add(session);
        evictOverflowSessionsLocked();
        return session;
    }

    private static void evictOverflowSessionsLocked() {
        while (ACTIVE_SESSIONS.size() > MAX_ACTIVE_SESSIONS) {
            ActiveSession victim = null;
            for (ActiveSession s : ACTIVE_SESSIONS) {
                if (s == null) continue;
                if (victim == null) {
                    victim = s;
                    continue;
                }
                boolean sPending = s.pendingReplySinceMs > 0L;
                boolean vPending = victim.pendingReplySinceMs > 0L;
                if (sPending && !vPending) {
                    victim = s;
                } else if (sPending == vPending) {
                    long sAge = sPending ? s.pendingReplySinceMs : s.lastRxAtMs;
                    long vAge = vPending ? victim.pendingReplySinceMs : victim.lastRxAtMs;
                    if (sAge < vAge) victim = s;
                }
            }
            if (victim == null) break;
            if (!victim.closeNow()) {
                ACTIVE_SESSIONS.remove(victim);
            }
        }
    }

    static void unregisterActiveSession(ActiveSession session) {
        if (session != null) {
            ACTIVE_SESSIONS.remove(session);
        }
    }

    /**
     * Reaps dead/half-open proxy sessions whose upstream stopped replying after client writes,
     * and (when {@code maxIdleMs > 0}) recycles pre-sleep NAT-expired idle sockets on wake.
     * Never sends client WebSocket PING frames (Telegram /apiws drops on client PING).
     */
    public static int reapStaleSessions(long maxPendingReplyMs, long maxIdleMs) {
        return reapStaleSessionsAt(safeElapsedRealtime(), maxPendingReplyMs, maxIdleMs);
    }

    static int reapStaleSessionsAt(long nowMs, long maxPendingReplyMs, long maxIdleMs) {
        int reaped = 0;
        for (ActiveSession s : ACTIVE_SESSIONS) {
            if (s == null) continue;
            boolean deadClient = s.client != null && s.client.isClosed();
            boolean pendingStall = maxPendingReplyMs > 0L
                    && s.pendingReplySinceMs > 0L
                    && (nowMs - s.pendingReplySinceMs) >= maxPendingReplyMs;
            boolean idleExpired = maxIdleMs > 0L
                    && (nowMs - s.lastRxAtMs) >= maxIdleMs;
            if (deadClient || pendingStall || idleExpired) {
                if (s.closeNow()) {
                    reaped++;
                }
            }
        }
        if (reaped > 0) {
            safeLogI("REAPED_STALE_SESSIONS count=" + reaped + " remaining=" + ACTIVE_SESSIONS.size()
                    + " maxPendingMs=" + maxPendingReplyMs + " maxIdleMs=" + maxIdleMs);
        }
        return reaped;
    }

    public static int closeAllActiveSessions() {
        networkEpoch.incrementAndGet();
        int closed = 0;
        for (ActiveSession s : ACTIVE_SESSIONS) {
            if (s != null && s.closeNow()) {
                closed++;
            }
        }
        for (Socket dial : new java.util.ArrayList<>(ACTIVE_DIAL_SOCKETS)) {
            if (dial != null && ACTIVE_DIAL_SOCKETS.remove(dial)) {
                closeQuietly(dial);
                closed++;
            }
        }
        return closed;
    }

    static int activeSessionCountForTest() {
        return ACTIVE_SESSIONS.size();
    }

    public static void resetL3WhitelistCache() {
        l3WhitelistCheckedAt = 0L;
        strictL3WhitelistDetected = false;
        strictL3ZeroRelaysSinceMs = 0L;
    }

    static void clearPinnedTelegramL3Relay() {
        pinnedTelegramL3Relay = null;
    }

    static String pinnedTelegramL3RelayKeyForTest() {
        ColgramProxyChain.Relay r = pinnedTelegramL3Relay;
        return r == null ? "" : normalizeRelayKey(r.host, r.port);
    }
    private static final java.util.concurrent.atomic.AtomicInteger l3RawMtprotoSilentStreak =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicBoolean l3ProbeInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.Map<String, Long> l3RelayPenaltyUntil =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Socket, String> l3RelayLabelsBySocket =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void setAppContext(android.content.Context context) {
        if (context != null) {
            android.content.Context app = context.getApplicationContext();
            appContext = app != null ? app : context;
            try {
                ColgramTelegramDcAddresses.init(appContext);
            } catch (Throwable ignored) {}
            loadPersistedHintsIfNeeded(appContext);
        }
    }

    private static synchronized void loadPersistedHintsIfNeeded(android.content.Context ctx) {
        if (ctx == null || hintsLoaded) return;
        hintsLoaded = true;
        try {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                    HINT_PREFS_NAME, android.content.Context.MODE_PRIVATE);
            if (prefs == null) return;
            int cellStrat = prefs.getInt("cell_strat", -1);
            int wifiStrat = prefs.getInt("wifi_strat", -1);
            if (cellStrat >= 0 && cellStrat < STRATEGY_COUNT) {
                cellularPreferredStrategy = cellStrat;
                if (cellularNetwork) currentStrategy = cellStrat;
            }
            if (wifiStrat >= 0 && wifiStrat < STRATEGY_COUNT) {
                wifiPreferredStrategy = wifiStrat;
                if (!cellularNetwork) currentStrategy = wifiStrat;
            }
            int cFrag = prefs.getInt("cell_ws_frag", -1);
            int cSni = prefs.getInt("cell_ws_sni", 0);
            int wFrag = prefs.getInt("wifi_ws_frag", -1);
            int wSni = prefs.getInt("wifi_ws_sni", 0);
            if (cFrag >= 0) {
                cellularWssFragHint = cFrag;
                cellularWssSniHint = cSni;
                ColgramTelegramWsBridge.restoreBypassHint(true, cFrag, cSni);
            }
            if (wFrag >= 0) {
                wifiWssFragHint = wFrag;
                wifiWssSniHint = wSni;
                ColgramTelegramWsBridge.restoreBypassHint(false, wFrag, wSni);
            }
        } catch (Throwable ignored) {}
    }

    static void persistDesyncStrategyHint(boolean cellular, int strategy) {
        if (strategy < 0 || strategy >= STRATEGY_COUNT) return;
        synchronized (ColgramDpiBypass.class) {
            if (cellular) {
                cellularPreferredStrategy = strategy;
            } else {
                wifiPreferredStrategy = strategy;
            }
        }
        android.content.Context ctx = resolveAppContext();
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                        HINT_PREFS_NAME, android.content.Context.MODE_PRIVATE);
                if (prefs != null) {
                    prefs.edit().putInt(cellular ? "cell_strat" : "wifi_strat", strategy).apply();
                }
            } catch (Throwable ignored) {}
        }
    }

    static void persistWssHint(boolean cellular, int fragMode, int sniCursor) {
        if (cellular) {
            cellularWssFragHint = fragMode;
            cellularWssSniHint = sniCursor;
        } else {
            wifiWssFragHint = fragMode;
            wifiWssSniHint = sniCursor;
        }
        android.content.Context ctx = resolveAppContext();
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                        HINT_PREFS_NAME, android.content.Context.MODE_PRIVATE);
                if (prefs != null) {
                    prefs.edit()
                            .putInt(cellular ? "cell_ws_frag" : "wifi_ws_frag", fragMode)
                            .putInt(cellular ? "cell_ws_sni" : "wifi_ws_sni", sniCursor)
                            .apply();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static synchronized void clearPersistedHints() {
        cellularWssFragHint = -1;
        cellularWssSniHint = 0;
        wifiWssFragHint = -1;
        wifiWssSniHint = 0;
        hintsLoaded = false;
        android.content.Context ctx = resolveAppContext();
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                        HINT_PREFS_NAME, android.content.Context.MODE_PRIVATE);
                if (prefs != null) prefs.edit().clear().apply();
            } catch (Throwable ignored) {}
        }
    }

    static void resetPersistedHintsForTest() {
        clearPersistedHints();
    }

    static int preferredStrategyForTest(boolean cellular) {
        return cellular ? cellularPreferredStrategy : wifiPreferredStrategy;
    }

    static int wssFragHintForTest(boolean cellular) {
        return cellular ? cellularWssFragHint : wifiWssFragHint;
    }

    private static android.content.Context resolveAppContext() {
        android.content.Context ctx = appContext;
        if (ctx != null) return ctx;
        try {
            ctx = ColgramProxyManager.getAppContext();
            if (ctx != null) {
                appContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {}
        try {
            ctx = ColgramPythonEngine.appContext();
            if (ctx != null) {
                appContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** True when the last L3 diagnostic proved RU-whitelist TCP SYN works while ALL Telegram WSS edges are dropped. */
    public static boolean isStrictL3WhitelistDetected() {
        return strictL3WhitelistDetected;
    }

    /** True when automatic Layer-2 L3-relay cascade is actively carrying traffic on an L3-blocked network. */
    public static boolean isL3RelayCascadeActive() {
        long last = lastL3RelaySuccessAt;
        return strictL3WhitelistDetected && last > 0L
                && (safeElapsedRealtime() - last) <= L3_RELAY_ACTIVE_WINDOW_MS;
    }

    public static String getActiveL3RelayLabel() {
        return activeL3RelayLabel;
    }

    /** Called whenever any direct Layer-1 Telegram TCP/WSS path succeeds; snaps back to 100% pure local bypass. */
    static void onDirectLayer1Success() {
        strictL3WhitelistDetected = false;
        strictL3ZeroRelaysSinceMs = 0L;
        activeL3RelayLabel = "";
        lastL3RelaySuccessAt = 0L;
        pinnedTelegramL3Relay = null;
        l3RawMtprotoSilentStreak.set(0);
        l3RelayPenaltyUntil.clear();
    }

    static void setStrictL3WhitelistForTest(boolean detected) {
        strictL3WhitelistDetected = detected;
        l3WhitelistCheckedAt = detected ? safeElapsedRealtime() : 0L;
        l3RelayPenaltyUntil.clear();
        if (!detected) {
            activeL3RelayLabel = "";
            lastL3RelaySuccessAt = 0L;
        }
    }

    private static String normalizeRelayKey(String host, int port) {
        if (host == null || port <= 0) return "";
        return host.trim().toLowerCase(java.util.Locale.US) + ":" + port;
    }

    static boolean isL3RelayPenalized(String host, int port) {
        String key = normalizeRelayKey(host, port);
        if (key.isEmpty()) return false;
        Long until = l3RelayPenaltyUntil.get(key);
        if (until == null) return false;
        if (safeElapsedRealtime() >= until) {
            l3RelayPenaltyUntil.remove(key, until);
            return false;
        }
        return true;
    }

    private static void penalizeL3Relay(String relayLabel) {
        if (relayLabel == null || relayLabel.isEmpty()) return;
        int colon = relayLabel.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= relayLabel.length()) return;
        String host = relayLabel.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(relayLabel.substring(colon + 1));
        } catch (NumberFormatException ignored) {
            return;
        }
        markRelayFailed(host, port);
    }

    static void markRelayFailed(String host, int port) {
        String key = normalizeRelayKey(host, port);
        if (key.isEmpty()) return;
        long now = safeElapsedRealtime();
        if (l3RelayPenaltyUntil.size() >= MAX_TRACKED_MAP_ENTRIES) {
            l3RelayPenaltyUntil.entrySet().removeIf(e -> now >= e.getValue());
            if (l3RelayPenaltyUntil.size() >= MAX_TRACKED_MAP_ENTRIES) l3RelayPenaltyUntil.clear();
        }
        l3RelayPenaltyUntil.put(key, now + L3_RELAY_PENALTY_MS);
        ColgramProxyChain.Relay pinned = pinnedTelegramL3Relay;
        if (pinned != null && key.equalsIgnoreCase(normalizeRelayKey(pinned.host, pinned.port))) {
            pinnedTelegramL3Relay = null;
        }
        if (key.equalsIgnoreCase(activeL3RelayLabel)) {
            lastL3RelaySuccessAt = 0L;
            activeL3RelayLabel = "";
        }
        try {
            ColgramProxyManager.markRelayDead(host, port);
        } catch (Throwable ignored) {}
    }

    static boolean evaluateStrictL3Whitelist(boolean ruWhitelistReachable, boolean anyTelegramEdgeReachable) {
        return ruWhitelistReachable && !anyTelegramEdgeReachable;
    }

    private static boolean hasGlobalIpv6Safe() {
        boolean v6 = false;
        try {
            v6 = ColgramDohResolver.hasGlobalRoutableIpv6();
        } catch (Throwable ignored) {}
        if (v6) return true;
        try {
            return ColgramConfig.isIpv6BypassEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Collects verified Telegram WSS edge IPs across DC 2/1/5 for L3 SYN reachability probing.
     * When {@code includeIpv6} is true, includes verified IPv6 WSS edges alongside IPv4 so
     * IPv6-routed mobile carriers stay on Layer 1 without firing a false L3-block.
     */
    static java.util.List<String> buildL3ProbeEdgeIps(boolean includeIpv6) {
        java.util.LinkedHashSet<String> tgEdgeIps = new java.util.LinkedHashSet<>();
        boolean hasV6 = false;
        for (int dc : new int[]{2, 1, 5}) {
            try {
                String[] ips = ColgramTelegramWsBridge.getVerifiedWssEdgeIpsForDc(dc);
                if (ips != null) {
                    boolean pickedV4 = false;
                    boolean pickedV6 = false;
                    for (String ip : ips) {
                        if (ip == null || ip.isEmpty()) continue;
                        boolean isV6 = ip.indexOf(':') >= 0;
                        if (!isV6 && !pickedV4) {
                            tgEdgeIps.add(ip);
                            pickedV4 = true;
                        } else if (isV6 && includeIpv6 && !pickedV6) {
                            tgEdgeIps.add(ip);
                            pickedV6 = true;
                            hasV6 = true;
                        }
                        if (pickedV4 && (!includeIpv6 || pickedV6)) break;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (tgEdgeIps.isEmpty()) {
            tgEdgeIps.add("149.154.167.220");
            tgEdgeIps.add("149.154.174.100");
            tgEdgeIps.add("149.154.170.100");
        }
        if (includeIpv6 && !hasV6) {
            tgEdgeIps.add("2001:67c:4e8:f004::9");
        }
        return new java.util.ArrayList<>(tgEdgeIps);
    }

    /**
     * Lightweight L3-whitelist diagnostic: compares TCP SYN reachability of Russian-whitelisted
     * endpoints (vk.com:443 / ya.ru:443) against verified Telegram WSS edge IPs (IPv4 + IPv6 when
     * global IPv6 is present). If RU whitelist accepts SYN while ALL Telegram WSS edges fail SYN,
     * records an explicit strict L3-whitelist state and warms Layer-2 SOCKS5/relay candidates.
     */
    public static boolean probeStrictL3WhitelistStatus() {
        long now = safeElapsedRealtime();
        long cacheWindow = strictL3WhitelistDetected ? L3_WHITELIST_DETECTED_CACHE_MS : L3_WHITELIST_CACHE_MS;
        if (l3WhitelistCheckedAt > 0L && now - l3WhitelistCheckedAt < cacheWindow) {
            return strictL3WhitelistDetected;
        }
        boolean includeIpv6 = hasGlobalIpv6Safe();
        final java.util.List<String> tgEdgeIps = buildL3ProbeEdgeIps(includeIpv6);
        final java.util.List<String> ruTargets = new java.util.ArrayList<>(RU_WHITELIST_PROBE_HOSTS.length);
        for (String h : RU_WHITELIST_PROBE_HOSTS) ruTargets.add(h);

        final java.util.concurrent.atomic.AtomicBoolean ruResult = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch ruDone = new java.util.concurrent.CountDownLatch(1);
        final boolean ruStarted = executeWorker(() -> {
            try {
                ruResult.set(probeAnyTcpSyn(ruTargets, 443, 1400));
            } finally {
                ruDone.countDown();
            }
        });

        boolean anyTgSyn = probeAnyTcpSyn(tgEdgeIps, 443, 1400);
        if (anyTgSyn) {
            onDirectLayer1Success();
            l3WhitelistCheckedAt = safeElapsedRealtime();
            return false;
        }

        boolean ruSyn;
        if (ruStarted) {
            try {
                ruDone.await(1650L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            ruSyn = ruResult.get();
        } else {
            ruSyn = probeAnyTcpSyn(ruTargets, 443, 1400);
        }

        boolean detected = evaluateStrictL3Whitelist(ruSyn, false);
        strictL3WhitelistDetected = detected;
        l3WhitelistCheckedAt = safeElapsedRealtime();
        if (detected) {
            safeLogW("STRICT_L3_WHITELIST_DETECTED: vk.com/ya.ru:443 SYN ok, all Telegram WSS edges ("
                    + tgEdgeIps + ") dropped at L3");
            try {
                if (ColgramProxyManager.getReachableRelays().isEmpty()) {
                    ColgramProxyManager.triggerImmediateL3RelayRefresh();
                }
            } catch (Throwable ignored) {}
        }
        return detected;
    }

    private static void triggerL3WhitelistProbeAsync() {
        long now = safeElapsedRealtime();
        long cacheWindow = strictL3WhitelistDetected ? L3_WHITELIST_DETECTED_CACHE_MS : L3_WHITELIST_CACHE_MS;
        if (l3WhitelistCheckedAt > 0L && now - l3WhitelistCheckedAt < cacheWindow) return;
        if (!l3ProbeInFlight.compareAndSet(false, true)) return;
        if (!executeWorker(() -> {
            try {
                probeStrictL3WhitelistStatus();
            } catch (Throwable ignored) {
            } finally {
                l3ProbeInFlight.set(false);
            }
        })) {
            l3ProbeInFlight.set(false);
        }
    }

    private static boolean probeAnyTcpSyn(final java.util.List<String> hosts, final int port, final int timeoutMs) {
        if (hosts == null || hosts.isEmpty()) return false;
        final java.util.concurrent.atomic.AtomicBoolean anyOk = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(hosts.size());
        final android.net.VpnService sysVpn = activeSystemDpiVpn;
        for (final String host : hosts) {
            if (!executeWorker(() -> {
                Socket sock = null;
                try {
                    if (anyOk.get()) return;
                    InetAddress addr = null;
                    if (ColgramDohResolver.isIpLiteral(host)) {
                        addr = InetAddress.getByName(host);
                    } else {
                        InetAddress[] resolved = ColgramDohResolver.resolveOrSystem(host);
                        if (resolved != null && resolved.length > 0) addr = resolved[0];
                        if (addr == null) addr = InetAddress.getByName(host);
                    }
                    sock = new Socket(java.net.Proxy.NO_PROXY);
                    if (sysVpn != null && !sysVpn.protect(sock)) return;
                    connectTracked(sock, new InetSocketAddress(addr, port), timeoutMs);
                    if (anyOk.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(sock);
                    if (remaining.decrementAndGet() == 0) {
                        latch.countDown();
                    }
                }
            })) {
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown();
                }
            }
        }
        try {
            latch.await(timeoutMs + 250L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return anyOk.get();
    }

    private static long safeElapsedRealtime() {
        try {
            return SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static int dcBucketFor(String host) {
        return dcBucketForSignedDc(ColgramTelegramDcAddresses.signedDcIdFor(host));
    }

    static int dcBucketForSignedDc(int signedDc) {
        int norm = ColgramTelegramWsBridge.Obfuscated2FrameSplitter.normalizeDcId(signedDc);
        if (norm < 1 || norm > 5) return 0;
        return ColgramTelegramWsBridge.Obfuscated2FrameSplitter.isMediaDcId(signedDc) ? (norm + 5) : norm;
    }

    private static int genericBucketForSignedDc(int signedDc) {
        int norm = ColgramTelegramWsBridge.Obfuscated2FrameSplitter.normalizeDcId(signedDc);
        return (norm >= 1 && norm <= 5) ? norm : 0;
    }

    private static void resetDcTrackers() {
        for (int i = 0; i < MAX_DC_BUCKETS; i++) {
            dcRawReplied.set(i, 0);
            dcRawFailed.set(i, 0);
            dcWsPreferred.set(i, 0);
        }
    }

    private static volatile long listenerGeneration;
    private static volatile boolean stoppedExplicitly;

    static boolean wasExplicitlyStopped() {
        return stoppedExplicitly;
    }

    public static synchronized void start() {
        if (isRunning || startScheduled) return;
        stoppedExplicitly = false;
        startScheduled = true;
        final long generation = ++listenerGeneration;
        deferredStart = new Thread(() -> {
            try {
                Thread.sleep(START_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (ColgramDpiBypass.class) {
                if (generation != listenerGeneration || !startScheduled) return;
                startScheduled = false;
                deferredStart = null;
                startNow();
            }
        }, "colgram-dpi-deferred");
        deferredStart.setDaemon(true);
        deferredStart.start();
    }

    /** An explicit request promotes a pending delayed start instead of waiting eight seconds. */
    public static synchronized void startImmediately() {
        stoppedExplicitly = false;
        if (isRunning && bound) return;
        if (deferredStart != null) deferredStart.interrupt();
        deferredStart = null;
        startScheduled = false;
        startNow();
    }

    /** Bind and publish under the same lock as stop(); only accept runs asynchronously. */
    private static synchronized void startNow() {
        if (isRunning && bound && serverSocket != null && !serverSocket.isClosed()) return;
        final long generation = ++listenerGeneration;
        ServerSocket candidate = null;
        try {
            // Pin ONE permanent fixed loopback port (127.0.0.1:9876) with SO_REUSEADDR and brief
            // retries so transient TIME_WAIT never causes ephemeral port rotation or tgnet resets.
            java.net.BindException lastBindError = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                try {
                    candidate.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
                    lastBindError = null;
                    break;
                } catch (java.net.BindException occupied) {
                    lastBindError = occupied;
                    closeQuietly(candidate);
                    candidate = null;
                    if (attempt < 3) {
                        try { Thread.sleep(80L); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (candidate == null) {
                Log.w(TAG, "Fixed port " + LOCAL_PORT + " held externally ("
                        + (lastBindError != null ? lastBindError.getMessage() : "busy")
                        + "); falling back to ephemeral loopback once");
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress("127.0.0.1", 0));
            }
            final ServerSocket listener = candidate;
            serverSocket = listener;
            activePort = listener.getLocalPort();
            upstreamState = UPSTREAM_WAIT;
            resetCarriageCounters();
            bound = true;
            isRunning = true;
            synchronized (READY_LOCK) {
                READY_LOCK.notifyAll();
            }
            ColgramNativeDesync.runSelfTest();
            ColgramTelegramWsBridge.warmUp();
            workerPool.execute(() -> acceptClients(listener, generation));
            safeLogD("ColgramDpiBypass started on 127.0.0.1:" + activePort
                    + " nativeDesync=" + ColgramNativeDesync.isAvailable());
        } catch (Exception failure) {
            closeQuietly(candidate);
            serverSocket = null;
            activePort = LOCAL_PORT;
            bound = false;
            isRunning = false;
            synchronized (READY_LOCK) {
                READY_LOCK.notifyAll();
            }
            safeLogE("ColgramDpiBypass bind failed", failure);
        }
    }

    private static synchronized boolean ownsListener(ServerSocket listener, long generation) {
        return generation == listenerGeneration && serverSocket == listener && isRunning;
    }

    private static void acceptClients(ServerSocket listener, long generation) {
        try {
            while (ownsListener(listener, generation)) {
                if (!HANDSHAKE_SLOTS.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) continue;
                boolean submitted = false;
                Socket client = null;
                try {
                    client = listener.accept();
                    synchronized (ColgramDpiBypass.class) {
                        if (!ownsListener(listener, generation)) break;
                        final Socket accepted = client;
                        submitted = executeWorker(() -> {
                            try { handleClient(accepted, null, generation); }
                            finally { HANDSHAKE_SLOTS.release(); }
                        });
                    }
                } finally {
                    if (!submitted) { closeQuietly(client); HANDSHAKE_SLOTS.release(); }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (ownsListener(listener, generation)) safeLogW("DPI accept loop stopped: " + failure);
        } finally {
            closeQuietly(listener);
            synchronized (ColgramDpiBypass.class) {
                if (serverSocket == listener && listenerGeneration == generation) {
                    serverSocket = null;
                    bound = false;
                    isRunning = false;
                    activePort = LOCAL_PORT;
                    synchronized (READY_LOCK) {
                        READY_LOCK.notifyAll();
                    }
                }
            }
        }
    }

    public static synchronized void stop() {
        ++listenerGeneration;
        stoppedExplicitly = true;
        isRunning = false;
        bound = false;
        synchronized (READY_LOCK) {
            READY_LOCK.notifyAll();
        }
        startScheduled = false;
        if (deferredStart != null) deferredStart.interrupt();
        deferredStart = null;
        closeQuietly(serverSocket);
        serverSocket = null;
        activePort = LOCAL_PORT;
        upstreamState = UPSTREAM_WAIT;
        closeAllActiveSessions();
        clearConnectionFailureBackoff();
        safeLogD("ColgramDpiBypass stopped");
    }

    /** The port the listener is actually on, which is LOCAL_PORT unless that was busy. */
    public static int activePort() {
        return activePort;
    }

    public static String upstreamStatus() {
        return upstreamStatusForBound(bound);
    }

    static String upstreamStatusForBound(boolean listenerBound) {
        if (!listenerBound) return "локальный транспорт не запущен";
        if (isL3RelayCascadeActive()) {
            String label = activeL3RelayLabel;
            return (label != null && !label.isEmpty())
                    ? "Авто-обход (L3-мост " + label + ") · подключён"
                    : "Авто-обход (L3-мост) · подключён";
        }
        if (strictL3WhitelistDetected) {
            return "TCP SYN блокируется вышкой · ищу L3-мост…";
        }
        if (bytesFromTarget() > 0 && ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork()) {
            return "WSS-мост активен · трафик идёт";
        }
        switch (upstreamState) {
            case UPSTREAM_DIAL: return "подключение к серверу Telegram";
            case UPSTREAM_NO_SOCKET:
                return cellularNetwork
                        ? "TCP SYN блокируется вышкой · жёсткий IP-фильтр"
                        : "TCP до Telegram не открылся";
            case UPSTREAM_SOCKET: return "TCP открыт · ждём ответ Telegram";
            case UPSTREAM_SILENT: return "TCP открыт · Telegram не ответил";
            case UPSTREAM_REPLY: return "трафик получен · проверяем протокол Telegram";
            default: return "локальный транспорт запущен · ждём запрос Telegram";
        }
    }

    /**
     * Find a loopback port nothing is holding.
     *
     * <p>It binds port 0 and reads back what the kernel gave it, which is the only way to know a port is
     * free - a scan would race every other process on the device, and the preferred port being busy is
     * exactly the situation where another process may take any port a scan picks.
     *
     * @return a bound port, or -1 when even that fails
     */
    private static int findFreePort() {
        ServerSocket probe = null;
        try {
            probe = new ServerSocket();
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            return probe.getLocalPort();
        } catch (Exception e) {
            Log.e(TAG, "cannot find a free loopback port", e);
            return -1;
        } finally {
            closeQuietly(probe);
        }
    }

    /**
     * Rebind the listener if it is down, safely.
     *
     * Called from the proxy health check. A ServerSocket cannot be rebound while the old
     * one still holds the port, so the dead instance is torn down first — otherwise the
     * new bind fails with EADDRINUSE and recovery silently never happens.
     *
     * @return true if the listener is accepting once this returns
     */
    public static synchronized boolean restartIfDead() {
        if (isBound() && serverSocket != null && !serverSocket.isClosed()) {
            return true;
        }
        Log.w(TAG, "restarting the local DPI listener (isRunning=" + isRunning + ", bound=" + bound + ")");
        stop();
        startNow();
        return true;
    }

    /**
     * Wait for the listener, without holding the class monitor.
     *
     * {@link #restartIfDead()} used to call awaitReady() while still synchronized, which
     * blocked every other DPI entry point for up to 5s. The monitor is only needed to
     * mutate the socket state, not to wait for it, so the wait is split out here.
     */
    public static boolean restartIfDeadAndWait(long timeoutMs) {
        if (!restartIfDead()) return true;
        return awaitReady(timeoutMs);
    }

    public static boolean isRunning() {
        return isRunning;
    }

    /** True once the listener is bound and accepting connections. */
    public static boolean isBound() {
        return bound;
    }

    /**
     * Block until the listener is accepting, or the timeout elapses.
     *
     * Needed because this listener is now the FIRST entry in the proxy pool: Telegram must
     * not be pointed at 127.0.0.1:9876 before something is listening there. start() binds
     * on a deferred thread (START_DELAY_MS), so without waiting, the app would apply a
     * proxy pointing at a closed port, Telegram would report a proxy error, and the
     * rotator would move OFF the one option that needs no third party - the opposite of
     * what the ordering is for.
     *
     * @return true if the listener is accepting
     */
    public static boolean awaitReady(long timeoutMs) {
        if (bound && serverSocket != null && !serverSocket.isClosed()) {
            return true;
        }
        if (timeoutMs <= 0L) {
            return bound && serverSocket != null && !serverSocket.isClosed();
        }
        long deadline = safeElapsedRealtime() + timeoutMs;
        synchronized (READY_LOCK) {
            while (!(bound && serverSocket != null && !serverSocket.isClosed())) {
                long remaining = deadline - safeElapsedRealtime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    READY_LOCK.wait(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (bound && serverSocket != null && !serverSocket.isClosed()) {
                return true;
            }
        }
        safeLogW("DPI listener not ready after " + timeoutMs + "ms");
        return false;
    }

    /** Active whole-device local DPI VpnService (non-null ONLY while our own local DPI TUN is up). */
    private static volatile android.net.VpnService activeSystemDpiVpn;

    static android.net.VpnService activeSystemDpiVpn() {
        return activeSystemDpiVpn;
    }

    /** Separate, authenticated listener: does not change the app-wide bypass preference. */
    static final class CallListener implements java.io.Closeable {
        private final ServerSocket listener;
        private final ColgramLocalDnsProxy localDns;
        private final java.util.Set<Socket> sockets = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());
        private final java.util.concurrent.Semaphore clients;
        private final boolean systemWide;
        private final boolean dualStack;
        private final String username;
        private final String password;
        private volatile android.net.VpnService vpn;
        private volatile boolean closed;

        CallListener() throws java.io.IOException {
            this(false);
        }

        CallListener(boolean systemWide) throws java.io.IOException {
            this.systemWide = systemWide;
            this.dualStack = systemWide && ColgramDohResolver.hasGlobalRoutableIpv6();
            this.clients = new java.util.concurrent.Semaphore(systemWide ? SYSTEM_WIDE_CLIENT_CAP : 64);
            java.security.SecureRandom random = new java.security.SecureRandom();
            byte[] secret = new byte[24];
            random.nextBytes(secret);
            username = systemWide ? "colgram-sysdpi" : "colgram-call";
            password = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP);
            java.util.Arrays.fill(secret, (byte) 0);
            listener = new ServerSocket(0, systemWide ? 128 : 32, InetAddress.getByName("127.0.0.1"));
            ColgramLocalDnsProxy dns = null;
            if (systemWide) {
                try {
                    dns = new ColgramLocalDnsProxy(!dualStack);
                } catch (Throwable t) {
                    Log.w(TAG, "local DNS proxy bind failed; using sing-box DoH fallback", t);
                }
            }
            this.localDns = dns;
            Log.i(TAG, "CallListener ready systemWide=" + systemWide + " dualStackIpv6=" + dualStack
                    + " localDnsPort=" + (dns != null ? dns.port() : 0));
            if (!executeWorker(this::accept)) {
                close();
                throw new java.io.IOException("Worker pool rejected CallListener accept loop");
            }
        }

        String config() {
            if (systemWide) {
                return ColgramProfileBuilder.forSystemDpiSocks("127.0.0.1", listener.getLocalPort(),
                        username, password, localDns != null ? localDns.port() : 0, dualStack);
            }
            return ColgramProfileBuilder.forCallSocks("127.0.0.1", listener.getLocalPort(),
                    username, password);
        }

        void attach(android.net.VpnService service) {
            if (closed) throw new IllegalStateException("Local call listener has closed");
            vpn = service;
            if (systemWide) {
                activeSystemDpiVpn = service;
                lastTunCheckAt = 0L;
            }
            if (localDns != null) localDns.attach(service);
            ColgramNativeDesync.runSelfTest();
        }

        private void accept() {
            try {
                while (!closed) {
                    Socket client = listener.accept();
                    if (!clients.tryAcquire()) {
                        closeQuietly(client);
                        continue;
                    }
                    if (!track(client)) {
                        clients.release();
                        continue;
                    }
                    final Socket accepted = client;
                    if (!executeWorker(() -> handleClient(accepted, this, -1L))) {
                        sockets.remove(accepted);
                        closeQuietly(accepted);
                        clients.release();
                    }
                }
            } catch (java.io.IOException ignored) {
                close();
            }
        }

        boolean track(Socket socket) {
            sockets.add(socket);
            if (!closed) return true;
            sockets.remove(socket);
            closeQuietly(socket);
            return false;
        }

        void done(Socket client, Socket upstream) {
            if (sockets.remove(client)) clients.release();
            if (upstream != null) sockets.remove(upstream);
        }

        void untrackUpstream(Socket upstream) {
            if (upstream != null) sockets.remove(upstream);
        }

        boolean authenticate(InputStream in, OutputStream out) throws java.io.IOException {
            int version = ColgramSocks5Codec.readByte(in);
            byte[] user = ColgramSocks5Codec.readFully(in, ColgramSocks5Codec.readByte(in));
            byte[] pass = ColgramSocks5Codec.readFully(in, ColgramSocks5Codec.readByte(in));
            boolean valid = version == 1
                    && java.security.MessageDigest.isEqual(user, username.getBytes(StandardCharsets.UTF_8))
                    && java.security.MessageDigest.isEqual(pass, password.getBytes(StandardCharsets.UTF_8));
            java.util.Arrays.fill(pass, (byte) 0);
            out.write(new byte[]{1, (byte) (valid ? 0 : 1)});
            out.flush();
            return valid;
        }

        Socket connect(String host, int port) {
            String key = baseDomainKey(host);
            boolean noMssClamp = isStreamingOrVoiceDomain(key);
            boolean confirmed = isHostStrategyConfirmed(key, cellularNetwork);
            int initStrat = pickStrategyForHost(key);
            // NEVER force tiny SYN(MSS=88) or WCLAMP=2048 on initial connect for unconfirmed hosts
            // or streaming/voice domains: Russian 4G LTE PGWs drop MSS=88 SYNs and streams need full MSS!
            int mss = (confirmed && !noMssClamp) ? mssClampForStrategy(initStrat) : 0;
            int wclamp = (confirmed && !noMssClamp) ? windowClampForStrategy(initStrat) : 0;
            return connect(host, port, mss, wclamp);
        }

        Socket connect(String host, int port, int mssClamp) {
            return connect(host, port, mssClamp, 0);
        }

        Socket connect(String host, int port, int mssClamp, int windowClamp) {
            android.net.VpnService owner = vpn;
            if (owner == null || closed || port <= 0) return null;
            int budgetMs = systemWide
                    ? ((strictL3WhitelistDetected && isEligibleForL3RelayCascade(host, port))
                            ? 1500
                            : (cellularNetwork ? 6500 : 5000))
                    : telegramDialBudgetMs();
            if (ColgramDohResolver.isIpLiteral(host)) {
                return connectDirectIp(host, port, mssClamp, windowClamp, budgetMs);
            }
            InetAddress[] resolved = ColgramDohResolver.resolveProtectedAll(
                    host, owner::protect, owner::protect, dualStack);
            if (resolved != null && resolved.length > 0) {
                String ipv4 = null;
                String ipv6 = null;
                for (InetAddress a : resolved) {
                    if (a == null || a.getAddress() == null) continue;
                    if (a.getAddress().length == 4 && ipv4 == null) ipv4 = a.getHostAddress();
                    else if (a.getAddress().length == 16 && ipv6 == null) ipv6 = a.getHostAddress();
                }
                if (dualStack && ipv4 != null && ipv6 != null) {
                    // Happy Eyeballs IPv4 + IPv6 race: beats IPv4-only carrier whitelists automatically!
                    Socket happy = raceDualStackConnect(ipv4, ipv6, port, mssClamp, windowClamp, budgetMs);
                    if (happy != null) return happy;
                }
                String primaryIp = ipv4 != null ? ipv4 : ipv6;
                if (primaryIp != null) {
                    Socket direct = connectDirectIp(primaryIp, port, mssClamp, windowClamp, budgetMs);
                    if (direct != null) return direct;
                }
                if (ipv4 != null && ipv6 != null) {
                    return connectDirectIp(ipv6, port, mssClamp, windowClamp, budgetMs);
                }
                return null;
            }
            try {
                InetAddress sys = InetAddress.getByName(host);
                if (sys != null) {
                    Socket direct = connectDirectIp(sys.getHostAddress(), port, mssClamp, windowClamp, budgetMs);
                    if (direct != null) return direct;
                }
            } catch (Throwable ignored) {}
            // Fallback direct hostname connect when resolver returned null
            Socket socket = new Socket();
            if (!track(socket)) return null;
            try {
                tuneSocketBuffers(socket);
                if (!owner.protect(socket)) throw new java.io.IOException("Socket protection failed");
                connectTracked(socket, new InetSocketAddress(host, port), budgetMs);
                return socket;
            } catch (Exception failure) {
                sockets.remove(socket);
                closeQuietly(socket);
            }
            return null;
        }

        private Socket raceDualStackConnect(final String ipv4, final String ipv6, final int port,
                                            final int mssClamp, final int windowClamp, final int budgetMs) {
            final java.util.concurrent.atomic.AtomicReference<Socket> winner =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(2);
            final String[] ips = {ipv4, ipv6};
            final long[] delays = {0L, 120L};
            for (int i = 0; i < 2; i++) {
                final String ip = ips[i];
                final long delay = delays[i];
                if (!executeWorker(() -> {
                    Socket s = null;
                    try {
                        if (delay > 0L) Thread.sleep(delay);
                        if (done.get() || closed) return;
                        s = connectDirectIp(ip, port, mssClamp, windowClamp, budgetMs);
                        if (s != null && winner.compareAndSet(null, s)) {
                            done.set(true);
                            latch.countDown();
                            s = null;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (s != null) {
                            untrackUpstream(s);
                            closeQuietly(s);
                        }
                        if (left.decrementAndGet() == 0) {
                            done.set(true);
                            latch.countDown();
                        }
                    }
                })) {
                    if (left.decrementAndGet() == 0) {
                        done.set(true);
                        latch.countDown();
                    }
                }
            }
            try {
                latch.await(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            done.set(true);
            return winner.get();
        }

        Socket connectDirectIp(String ipLiteral, int port, int mssClamp) {
            return connectDirectIp(ipLiteral, port, mssClamp, 0);
        }

        Socket connectDirectIp(String ipLiteral, int port, int mssClamp, int windowClamp) {
            int budgetMs = systemWide ? (cellularNetwork ? 5000 : 3500) : telegramDialBudgetMs();
            return connectDirectIp(ipLiteral, port, mssClamp, windowClamp, budgetMs);
        }

        Socket connectDirectIp(String ipLiteral, int port, int mssClamp, int windowClamp, int budgetMs) {
            android.net.VpnService owner = vpn;
            if (owner == null || closed || ipLiteral == null || ipLiteral.isEmpty() || port <= 0) return null;
            if (ColgramNativeDesync.isAvailable()) {
                try {
                    ColgramNativeDesync.NativeSocket nativeSock = ColgramNativeDesync.connect(
                            ipLiteral, port, budgetMs, mssClamp, windowClamp, owner::protect);
                    if (!track(nativeSock)) return null;
                    return nativeSock;
                } catch (Throwable ignored) {
                    // Fall back cleanly to protected Java socket
                }
            }
            Socket socket = new Socket();
            if (!track(socket)) return null;
            try {
                tuneSocketBuffers(socket);
                if (!owner.protect(socket)) throw new java.io.IOException("Socket protection failed");
                connectTracked(socket, new InetSocketAddress(ipLiteral, port), budgetMs);
                return socket;
            } catch (Exception failure) {
                sockets.remove(socket);
                closeQuietly(socket);
                return null;
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            android.net.VpnService prevVpn = vpn;
            vpn = null;
            if (systemWide && activeSystemDpiVpn == prevVpn) {
                activeSystemDpiVpn = null;
                lastTunCheckAt = 0L;
            }
            closeQuietly(localDns);
            closeQuietly(listener);
            for (Socket socket : sockets) closeQuietly(socket);
            sockets.clear();
        }
    }

    private static void handleClient(Socket client, CallListener call, long generation) {
        Socket targetSocket = null;
        boolean handedOff = false;
        try {
            // A connection accepted a moment before stop() is still handed to this worker, and serving
            // it would keep a stopped bypass doing work. The socket is closed rather than left to time
            // out, so what the caller sees after a stop is a refused connection and not a fifteen-second
            // wait.
            if (call == null ? !isCurrentListener(generation) : call.closed || call.vpn == null) {
                closeQuietly(client);
                return;
            }
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 1. SOCKS5 greeting
            int ver = ColgramSocks5Codec.readByte(in);
            if (ver != 5) {
                closeQuietly(client);
                return;
            }
            int nmethods = ColgramSocks5Codec.readByte(in);
            if (nmethods <= 0) {
                closeQuietly(client);
                return;
            }
            byte[] methods = ColgramSocks5Codec.readFully(in, nmethods);
            int authMethod = call == null ? 0 : 2;
            boolean supportsAuthMethod = false;
            for (byte method : methods) {
                if (method == authMethod) {
                    supportsAuthMethod = true;
                    break;
                }
            }
            if (!supportsAuthMethod) {
                out.write(new byte[]{0x05, (byte) 0xFF});
                out.flush();
                closeQuietly(client);
                return;
            }

            // Reply: NO AUTH REQUIRED
            out.write(new byte[]{0x05, (byte) authMethod});
            out.flush();
            if (call != null && !call.authenticate(in, out)) return;

            // 2. SOCKS5 request
            int reqVer = ColgramSocks5Codec.readByte(in);
            int cmd = ColgramSocks5Codec.readByte(in);
            int rsv = ColgramSocks5Codec.readByte(in);
            int atyp = ColgramSocks5Codec.readByte(in);
            // The command is logged because "incomplete SOCKS5 request" names neither it nor the
            // client, and the two possible causes -- a front that answers a command it was never asked,
            // and a client that closes mid-handshake -- are indistinguishable without it.
            if (cmd != 1) Log.d(TAG, "socks command 0x" + Integer.toHexString(cmd) + " atyp=" + atyp);

            if (reqVer != 5 || rsv != 0) {
                closeQuietly(client);
                return;
            }
            // CONNECT (0x01) and UDP ASSOCIATE (0x03) are both accepted.
            //
            // Only CONNECT was. Telegram never uses CONNECT for MTProto -- it associates, then
            // sends datagrams -- so every connection that reached this front was answered with
            // command-not-supported and closed, and the log showed only
            //
            //     SOCKS5 handshake/tunnel failed: incomplete SOCKS5 request
            //
            // which names neither the command nor the relay and reads as a dead server rather than a
            // front that answered a question it was never asked.
            if (cmd != 1 && cmd != 3) {
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            String destHost;
            if (atyp == 1) { // IPv4
                byte[] ip = ColgramSocks5Codec.readFully(in, 4);
                destHost = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            } else if (atyp == 3) { // Domain
                int len = ColgramSocks5Codec.readByte(in);
                if (len <= 0) {
                    closeQuietly(client);
                    return;
                }
                byte[] domainBytes = new byte[len];
                domainBytes = ColgramSocks5Codec.readFully(in, len);
                destHost = new String(domainBytes, StandardCharsets.UTF_8);
            } else if (atyp == 4) { // IPv6
                byte[] ip6 = ColgramSocks5Codec.readFully(in, 16);
                destHost = InetAddress.getByAddress(ip6).getHostAddress();
            } else {
                closeQuietly(client);
                return;
            }

            byte[] portBytes = ColgramSocks5Codec.readFully(in, 2);
            int destPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            // An associate is answered with a UDP port, not with a TCP session.
            //
            // Telegram associates, then sends MTProto datagrams to whatever port this reply names.
            // Answering with the address of a TCP socket that was opened for a different purpose, or
            // with the all-zero bound address, means the datagrams have nowhere to go and the flow
            // dies after a handshake that looked successful. So the association is answered here,
            // from a socket that actually receives them.
            if (cmd == 3) {
                if (call != null) {
                    ColgramCallUdpRelay.serve(call.vpn, client, in, out);
                    return;
                }
                java.net.DatagramSocket udp = new java.net.DatagramSocket(0,
                        InetAddress.getByName("127.0.0.1"));
                // The zero address means "accept from wherever this control connection came from",
                // which is exactly right: the client is on loopback.
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0,
                        (byte) (udp.getLocalPort() >>> 8), (byte) udp.getLocalPort()});
                out.flush();
                pumpAssociated(udp, client, generation);
                return;
            }

            // 3. Connect to destination with multi-port fallback & TCP desync
            //
            // When call != null (CallListener inside ColgramVpnService), every outbound socket is
            // explicitly protected via VpnService.protect() onto the raw carrier/ISP network, so
            // overlayActive MUST be false — otherwise whole-device local DPI bypass skips all desync!
            boolean overlayActive = (call == null) && isOverlayVpnActive();
            int signedDc = ColgramTelegramDcAddresses.signedDcIdFor(destHost);
            boolean isMedia = signedDc < 0;
            int dcBucket = dcBucketForSignedDc(signedDc);
            int genericBucket = genericBucketForSignedDc(signedDc);
            boolean inheritedGenericWs = isMedia && genericBucket > 0 && dcWsPreferred.get(genericBucket) != 0
                    && (cellularNetwork || (dcRawReplied.get(dcBucket) == 0 && dcRawFailed.get(dcBucket) > 0));
            boolean wsEdgeHost = "149.154.167.220".equals(destHost);
            boolean wsCapableDest = isWsCapableForSession(call != null, call != null && call.systemWide, wsEdgeHost)
                    && ColgramTelegramWsBridge.supports(destHost, destPort);
            boolean skipDcBackoffSleep = wsCapableDest
                    && (ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork() || dcWsPreferred.get(dcBucket) != 0);
            // For CallListener (whole-device TUN), NEVER pre-emptively bypass raw TCP before inspecting
            // the client's first packet — HTTPS/TLS to t.me, api.telegram.org, or web.telegram.org also
            // lives on 149.154.*:443 and MUST be SNI-desynced as TLS, never wrapped inside MTProto WSS!
            // When an overlay VPN (e.g. WARP) is active, try raw TCP once first, then fail over to WSS.
            boolean skipRawForWs = (call == null) && wsCapableDest && (
                    dcWsPreferred.get(dcBucket) != 0
                            || inheritedGenericWs
                            || ((!isMedia || cellularNetwork)
                                    && !overlayActive
                                    && ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork()
                                    && dcRawReplied.get(dcBucket) == 0)
                            || (dcRawReplied.get(dcBucket) == 0
                            && dcRawFailed.get(dcBucket) > 0
                            && (overlayActive
                                    || ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork()
                                    || cellularNetwork)));
            boolean viaL3Relay = false;
            boolean wsViaL3Relay = false;
            boolean triedL3Race = false;
            android.net.VpnService activeProtVpn = (call != null) ? call.vpn : activeSystemDpiVpn;
            ColgramDohResolver.SocketProtector l3Protector = activeProtVpn != null ? activeProtVpn::protect : null;
            boolean l3Eligible = !overlayActive && isEligibleForL3RelayCascade(destHost, destPort);
            ColgramTelegramWsBridge.WsSession wsSession = null;

            final long startEpoch = currentNetworkEpoch();

            // When strict L3-whitelist blocking is already detected on this mobile tower, immediately
            // race Layer-2 L3-relay cascade (segmented SOCKS5/HTTP CONNECT + WSS-over-SOCKS5 failover)
            // alongside a 900ms direct SYN recovery probe so Telegram connects in <2s AND auto-recovers
            // back to Layer 1 the instant direct SYN works again.
            if (strictL3WhitelistDetected && l3Eligible
                    && (call == null || (call.systemWide && isTelegramDcIp(destHost)))) {
                triedL3Race = true;
                L3RaceResult raced = raceLayer2WithFastDirectProbe(destHost, destPort,
                        call == null && wsCapableDest, isMedia, l3Protector);
                if (raced.wsSession != null) {
                    wsSession = raced.wsSession;
                    wsViaL3Relay = raced.viaL3Relay;
                } else if (raced.socket != null) {
                    if (call == null || call.track(raced.socket)) {
                        targetSocket = raced.socket;
                        viaL3Relay = raced.viaL3Relay;
                    } else {
                        closeQuietly(raced.socket);
                    }
                }
            }

            boolean coldWsAlreadyTried = false;
            if (targetSocket == null && wsSession == null && (!triedL3Race || !strictL3WhitelistDetected)) {
                if (call != null) {
                    targetSocket = call.connect(destHost, destPort);
                } else {
                    int normSignedDc = Math.abs(signedDc);
                    boolean hasPrewarm = wsCapableDest && !overlayActive && !strictL3WhitelistDetected
                            && normSignedDc > 0
                            && (skipRawForWs || dcRawReplied.get(dcBucket) == 0)
                            && ColgramTelegramWsBridge.hasPrewarmed(
                                    normSignedDc, isMedia, activeSystemDpiVpn != null);
                    if (hasPrewarm) {
                        android.net.VpnService sysVpn = activeSystemDpiVpn;
                        coldWsAlreadyTried = true;
                        wsSession = (sysVpn != null)
                                ? ColgramTelegramWsBridge.dialProtected(destHost, destPort, sysVpn::protect, sysVpn::protect, isMedia)
                                : ColgramTelegramWsBridge.dial(destHost, destPort, isMedia);
                        if (wsSession != null && startEpoch == currentNetworkEpoch()) {
                            onDirectLayer1Success();
                            int wsBucket = dcBucketForSignedDc(wsSession.rawDcId != 0 ? wsSession.rawDcId : wsSession.dcId);
                            if (wsBucket == 0) wsBucket = dcBucket;
                            dcWsPreferred.set(wsBucket, 1);
                            dcBackoffUntil.set(wsBucket, 0L);
                            if (wsBucket != dcBucket) dcBackoffUntil.set(dcBucket, 0L);
                        }
                    }
                    if (wsSession == null && !skipRawForWs) {
                        boolean coldRawWsRace = !coldWsAlreadyTried && wsCapableDest
                                && !overlayActive && !strictL3WhitelistDetected
                                && dcRawReplied.get(dcBucket) == 0;
                        if (coldRawWsRace) {
                            coldWsAlreadyTried = true;
                            long wsHeadStartMs = cellularNetwork ? 0L : 50L;
                            ColdRaceResult raced = raceColdRawAndWs(
                                    destHost, destPort, generation, dcBucket, isMedia,
                                    skipDcBackoffSleep, wsHeadStartMs);
                            targetSocket = raced.socket;
                            wsSession = raced.wsSession;
                            if (wsSession != null && startEpoch == currentNetworkEpoch()) {
                                onDirectLayer1Success();
                                int wsBucket = dcBucketForSignedDc(wsSession.rawDcId != 0 ? wsSession.rawDcId : wsSession.dcId);
                                if (wsBucket == 0) wsBucket = dcBucket;
                                dcWsPreferred.set(wsBucket, 1);
                                dcBackoffUntil.set(wsBucket, 0L);
                                if (wsBucket != dcBucket) dcBackoffUntil.set(dcBucket, 0L);
                            }
                        } else {
                            if (!skipDcBackoffSleep) {
                                long waitMs = dcBackoffUntil.get(dcBucket) - System.currentTimeMillis();
                                if (waitMs > 0) sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
                            }
                            if (!isCurrentListener(generation)) return;
                            targetSocket = connectWithBackoff(destHost, destPort, generation);
                        }
                    }
                }
            }
            if (call == null && !coldWsAlreadyTried && targetSocket == null && wsSession == null && wsCapableDest
                    && !(triedL3Race && strictL3WhitelistDetected && !activeL3RelayLabel.isEmpty())) {
                android.net.VpnService sysVpn = activeSystemDpiVpn;
                wsSession = (sysVpn != null)
                        ? ColgramTelegramWsBridge.dialProtected(destHost, destPort, sysVpn::protect, sysVpn::protect, isMedia)
                        : ColgramTelegramWsBridge.dial(destHost, destPort, isMedia);
                if (wsSession != null && startEpoch == currentNetworkEpoch()) {
                    onDirectLayer1Success();
                    int wsBucket = dcBucketForSignedDc(wsSession.rawDcId != 0 ? wsSession.rawDcId : wsSession.dcId);
                    if (wsBucket == 0) wsBucket = dcBucket;
                    dcWsPreferred.set(wsBucket, 1);
                    dcBackoffUntil.set(wsBucket, 0L);
                    if (wsBucket != dcBucket) dcBackoffUntil.set(dcBucket, 0L);
                }
            }
            if (call == null && skipRawForWs && wsSession == null && targetSocket == null
                    && !strictL3WhitelistDetected && isCurrentListener(generation)) {
                if (!skipDcBackoffSleep) {
                    long waitMs = dcBackoffUntil.get(dcBucket) - System.currentTimeMillis();
                    if (waitMs > 0) sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
                }
                if (!isCurrentListener(generation)) return;
                targetSocket = connectWithBackoff(destHost, destPort, generation);
            }
            if (call != null && call.systemWide && targetSocket == null && wsCapableDest && call.vpn != null) {
                // Carrier dropped raw TCP SYN to a Telegram DC IP while whole-device DPI is ON.
                // Reply SOCKS5 OK, peek firstPayload: only upgrade to protected WSS if it's real
                // non-TLS MTProto Obfuscated2 (>= 64B), never TLS/HTTPS!
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                client.setSoTimeout(0);
                byte[] peek = ColgramInitialPacketReader.readInitialPacket(client, 512, 1024, 150, 150);
                if (peek.length >= 64 && !ColgramTlsMimic.looksLikeClientHello(peek) && !strictL3WhitelistDetected) {
                    final android.net.VpnService owner = call.vpn;
                    int rawDc = ColgramTelegramWsBridge.extractRawDcFromObfuscated2(peek, peek.length);
                    boolean validRawDc = ColgramTelegramWsBridge.Obfuscated2FrameSplitter.normalizeDcId(rawDc) > 0;
                    boolean mediaDc = isMedia || (validRawDc && ColgramTelegramWsBridge.isMediaDcId(rawDc));
                    wsSession = ColgramTelegramWsBridge.dialProtected(
                            destHost, destPort, owner::protect, owner::protect, mediaDc);
                    if (wsSession != null) {
                        int hdrDc = validRawDc ? rawDc
                                : (wsSession.rawDcId != 0 ? wsSession.rawDcId : (mediaDc ? -wsSession.dcId : wsSession.dcId));
                        ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter =
                                ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(peek, peek.length, hdrDc);
                        if (splitter != null) {
                            onDirectLayer1Success();
                            splitter.feedInitAndTailToSession(peek, peek.length, wsSession);
                            pipeOverWebSocketAfterInit(client, wsSession, splitter, call, generation);
                            handedOff = true;
                            return;
                        }
                        closeQuietly(wsSession);
                        wsSession = null;
                    }
                }
                // WSS failed or HTTPS/TLS on a Telegram IP had its SYN dropped -> Layer-2 L3 relay cascade!
                if (l3Eligible) {
                    Socket l3Sock = connectViaL3RelayCascade(destHost, destPort, l3RelayTimeoutMs(), l3Protector);
                    if (l3Sock != null) {
                        if (call.track(l3Sock)) {
                            pipeViaL3Relay(client, l3Sock, call, generation, peek, peek.length, false);
                            handedOff = true;
                            return;
                        }
                        closeQuietly(l3Sock);
                    }
                }
                closeQuietly(client);
                return;
            }
            // Layer 1 direct TCP + WSS both failed to TCP-connect -> automatic Layer-2 L3-relay cascade
            // for encrypted targets (Telegram DC IPs, ports 443/8443/5222; never plaintext port 80).
            if (targetSocket == null && wsSession == null && l3Eligible && !triedL3Race) {
                if (call == null && wsCapableDest) triggerL3WhitelistProbeAsync();
                if (call == null && wsCapableDest && l3RawMtprotoSilentStreak.get() > 0) {
                    wsSession = dialWssViaL3RelayCascade(destHost, destPort, isMedia, l3Protector);
                    if (wsSession != null) wsViaL3Relay = true;
                }
                if (targetSocket == null && wsSession == null) {
                    Socket l3Sock = connectViaL3RelayCascade(destHost, destPort, l3RelayTimeoutMs(), l3Protector);
                    if (l3Sock != null) {
                        if (call == null || call.track(l3Sock)) {
                            targetSocket = l3Sock;
                            viaL3Relay = true;
                        } else {
                            closeQuietly(l3Sock);
                        }
                    }
                }
                if (call == null && targetSocket == null && wsSession == null && wsCapableDest) {
                    wsSession = dialWssViaL3RelayCascade(destHost, destPort, isMedia, l3Protector);
                    if (wsSession != null) wsViaL3Relay = true;
                }
            }
            if (call == null && targetSocket == null && wsSession == null && ColgramConfig.isAutoProxyEnabled()
                    && ColgramProxyManager.hasReachableRelays()) {
                // The listener is a local gateway, not a dead end. Direct dial and remap
                // alternatives both refused: hand the connection to a reachable relay front.
                // The relay is a stranger's machine, but it is the ONLY way a destination whose
                // SYN the carrier drops can be reached at all - and it is bounded to Telegram
                // destinations so ordinary traffic never routes through it.
                if (isMtProtoHost(destHost) || ColgramTelegramDcAddresses.isKnownAddress(destHost)) {
                    int chainPort = ColgramProxyManager.openRelayRouteFor(destHost, destPort);
                    if (chainPort > 0) {
                        try {
                            Socket chained = new Socket();
                            tuneSocketBuffers(chained);
                            connectTracked(chained, new InetSocketAddress("127.0.0.1", chainPort), 3000);
                            targetSocket = chained;
                            Log.i(TAG, "direct dial refused; routed " + destHost + ":" + destPort
                                    + " through relay front 127.0.0.1:" + chainPort);
                        } catch (Throwable t) {
                            Log.d(TAG, "relay front dial failed for " + destHost);
                        }
                    }
                }
            }
            if (call == null && !isCurrentListener(generation)) {
                closeQuietly(wsSession);
                return;
            }
            if (wsSession != null) {
                if (call == null) publishUpstream(generation, UPSTREAM_SOCKET);
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                client.setSoTimeout(0);
                if (call == null) relayedConnections.incrementAndGet();
                pipeOverWebSocket(client, wsSession, call, generation, wsViaL3Relay);
                handedOff = true;
                return;
            }
            if (targetSocket == null) {
                if (call == null) {
                    if (wsCapableDest) triggerL3WhitelistProbeAsync();
                    // Pace dead-endpoint rejects under L3 whitelists or thermal/background pressure so
                    // background tgnet probes don't spin radio/CPU while working WSS/L3 relays stay fast.
                    long paceMs = REJECT_RETRY_DELAY_MS;
                    if (strictL3WhitelistDetected && wsCapableDest) {
                        boolean eco = !ColgramUiBridge.isAppInForeground()
                                || ColgramPowerGuard.isThermalThrottled(appContext);
                        paceMs = Math.max(REJECT_RETRY_DELAY_MS, eco ? 600L : 250L);
                    }
                    sleep(paceMs);
                }
                // Per-retry noise from tgnet hammering the listener; a Log.w here floods
                // logcat a hundred lines a second on a network where the DCs refuse TCP.
                Log.d(TAG, "Failed to connect to target: " + destHost + ":" + destPort);
                out.write(new byte[]{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            // SOCKS5 success reply
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();

            client.setSoTimeout(0);
            targetSocket.setSoTimeout(0);

            // 4. Pipe traffic: transparent end-to-end stream for Layer-2 L3 relay, or adaptive desync for Layer 1
            if (call == null) relayedConnections.incrementAndGet();
            if (viaL3Relay) {
                pipeViaL3Relay(client, targetSocket, call, generation, null, 0,
                        call == null && isTelegramDcIp(destHost));
            } else {
                pipeWithAdvancedDesync(client, targetSocket, call, generation,
                        (call == null || call.systemWide) && ColgramTelegramDcAddresses.isKnownAddress(destHost),
                        destHost, destPort, isMedia, startEpoch);
            }
            handedOff = true;

        } catch (Throwable e) {
            // A liveness probe sends a greeting and closes without a request, so the EOF
                // that follows is that probe and not a failed connection. Naming it a handshake failure
                // put a permanent red herring in the log for a check that is expected to behave this
                // way; the real outcomes are logged where they happen, per address.
                if (e instanceof java.io.EOFException) {
                    Log.d(TAG, "socks probe closed before a request; expected for a liveness check");
                } else {
                    Log.d(TAG, "SOCKS5 session failed: " + e.getMessage());
                }
        } finally {
            if (!handedOff) {
                closeQuietly(client);
                closeQuietly(targetSocket);
                if (call != null) call.done(client, targetSocket);
            }
        }
    }

    private static final class ColdRaceResult {
        final Socket socket;
        final ColgramTelegramWsBridge.WsSession wsSession;

        ColdRaceResult(Socket socket, ColgramTelegramWsBridge.WsSession wsSession) {
            this.socket = socket;
            this.wsSession = wsSession;
        }
    }

    /**
     * Races Layer-1 direct TCP desync against official Telegram WSS edges on a cold DC bucket so
     * SYN-dropping networks resolve in ~90-150ms instead of blocking for 1.1-1.4s on TCP timeout.
     */
    private static ColdRaceResult raceColdRawAndWs(final String destHost, final int destPort,
                                                   final long generation, final int dcBucket,
                                                   final boolean isMedia, final boolean skipBackoff,
                                                   final long wsHeadStartMs) {
        final Object gate = new Object();
        final boolean[] decided = new boolean[1];
        final Socket[] rawWinner = new Socket[1];
        final ColgramTelegramWsBridge.WsSession[] wsWinner = new ColgramTelegramWsBridge.WsSession[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(2);

        boolean rawSubmitted = executeWorker(() -> {
            Socket candidate = null;
            try {
                if (!skipBackoff) {
                    long waitMs = dcBackoffUntil.get(dcBucket) - System.currentTimeMillis();
                    if (waitMs > 0) sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
                }
                if (!isCurrentListener(generation)) return;
                synchronized (gate) {
                    if (decided[0]) return;
                }
                candidate = connectWithBackoff(destHost, destPort, generation);
                synchronized (gate) {
                    if (!decided[0] && candidate != null && isCurrentListener(generation)) {
                        decided[0] = true;
                        rawWinner[0] = candidate;
                        candidate = null;
                        gate.notifyAll();
                        latch.countDown();
                    }
                }
            } finally {
                closeQuietly(candidate);
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown();
                }
            }
        });
        if (!rawSubmitted && remaining.decrementAndGet() == 0) {
            latch.countDown();
        }

        boolean wsSubmitted = executeWorker(() -> {
            ColgramTelegramWsBridge.WsSession candidateWs = null;
            try {
                if (wsHeadStartMs > 0L) {
                    synchronized (gate) {
                        if (!decided[0]) {
                            try {
                                gate.wait(wsHeadStartMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (decided[0]) return;
                    }
                }
                if (!isCurrentListener(generation)) return;
                synchronized (gate) {
                    if (decided[0]) return;
                }
                android.net.VpnService sysVpn = activeSystemDpiVpn;
                candidateWs = (sysVpn != null)
                        ? ColgramTelegramWsBridge.dialProtected(destHost, destPort, sysVpn::protect, sysVpn::protect, isMedia)
                        : ColgramTelegramWsBridge.dial(destHost, destPort, isMedia);
                synchronized (gate) {
                    if (!decided[0] && candidateWs != null && isCurrentListener(generation)) {
                        decided[0] = true;
                        wsWinner[0] = candidateWs;
                        candidateWs = null;
                        gate.notifyAll();
                        latch.countDown();
                    }
                }
            } finally {
                closeQuietly(candidateWs);
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown();
                }
            }
        });
        if (!wsSubmitted && remaining.decrementAndGet() == 0) {
            latch.countDown();
        }

        if (!rawSubmitted && !wsSubmitted) {
            if (!skipBackoff) {
                long waitMs = dcBackoffUntil.get(dcBucket) - System.currentTimeMillis();
                if (waitMs > 0) sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
            }
            if (!isCurrentListener(generation)) return new ColdRaceResult(null, null);
            return new ColdRaceResult(connectWithBackoff(destHost, destPort, generation), null);
        }

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (gate) {
            decided[0] = true;
            gate.notifyAll();
            return new ColdRaceResult(rawWinner[0], wsWinner[0]);
        }
    }

    private static boolean isCurrentListener(long generation) {
        return bound && generation == listenerGeneration;
    }

    private static synchronized void publishUpstream(long generation, int state) {
        if (isCurrentListener(generation)) upstreamState = state;
    }

    private static Socket connectWithBackoff(String host, int port, long generation) {
        if (port < 1 || port > 65535) return null;
        String endpoint = host.toLowerCase(java.util.Locale.US) + ":" + port;
        if (!isOverlayVpnActive() && silentEndpoints.contains(endpoint)
                && ColgramTelegramWsBridge.supports(host, port)) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        ColgramConnectFailureBackoff.Decision decision = connectFailures.acquire(endpoint, now);
        if (!decision.granted()) {
            // Concurrency and cooldown are different failures and used to look identical here, so
            // tgnet's ordinary retry burst charged itself a failure per retry and grew the cooldown
            // on an endpoint that had only failed once - or on one that had never been reached.
            Log.d(TAG, decision.coalesced()
                    ? "coalesced duplicate dial for " + endpoint
                    : "skipping direct TCP dial during endpoint cooldown: " + endpoint);
            return null;
        }
        long ticket = decision.ticket();
        final long startEpoch = currentNetworkEpoch();
        InetSocketAddress primary = resolveBypassEndpoint(host, port);
        if (primary == null) {
            connectFailures.release(endpoint, ticket);
            return null;
        }
        try {
            if (!DIAL_GROUPS.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                connectFailures.release(endpoint, ticket);
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            connectFailures.release(endpoint, ticket);
            return null;
        }
        Socket socket;
        boolean telegram = ColgramTelegramDcAddresses.isKnownAddress(host);
        if (telegram) publishUpstream(generation, UPSTREAM_DIAL);
        try {
            socket = establishConnection(host, port, primary);
        } finally {
            DIAL_GROUPS.release();
        }
        if (startEpoch != currentNetworkEpoch()) {
            closeQuietly(socket);
            connectFailures.release(endpoint, ticket);
            return null;
        }
        if (telegram) {
            publishUpstream(generation, socket == null ? UPSTREAM_NO_SOCKET : UPSTREAM_SOCKET);
            if (socket == null && cellularNetwork) {
                triggerL3WhitelistProbeAsync();
            } else if (socket != null) {
                onDirectLayer1Success();
            }
        }
        if (socket == null) {
            if (telegram) dcRawFailed.incrementAndGet(dcBucketFor(host));
            long delay = connectFailures.finish(endpoint, ticket, false, SystemClock.elapsedRealtime());
            if (delay > 0L) {
                Log.i(TAG, "direct TCP dial failed for " + endpoint + "; retry in " + delay + "ms");
            }
        } else {
            connectFailures.finish(endpoint, ticket, true, SystemClock.elapsedRealtime());
        }
        return socket;
    }

    /** Clears per-DC backoff/cooldowns without resetting working WS preferences or DNS cache. */
    public static void clearTransientCooldowns() {
        connectFailures.clear();
        for (int i = 0; i < MAX_DC_BUCKETS; i++) {
            dcBackoffUntil.set(i, 0L);
        }
        l3RelayPenaltyUntil.clear();
        ColgramTelegramWsBridge.clearCooldowns();
    }

    /** Drop stale dial failures as soon as Android reports a network or tunnel transition. */
    public static void clearConnectionFailureBackoff() {
        networkEpoch.incrementAndGet();
        connectFailures.clear();
        silentEndpoints.clear();
        silentEndpointCounts.clear();
        resetDcTrackers();
        for (int i = 0; i < MAX_DC_BUCKETS; i++) {
            dcBackoffUntil.set(i, 0L);
        }
        l3RelayPenaltyUntil.clear();
        l3RawMtprotoSilentStreak.set(0);
        lastTunCheckAt = 0L;
        onDirectLayer1Success();
        l3WhitelistCheckedAt = 0L;
        synchronized (ColgramDpiBypass.class) {
            java.util.Arrays.fill(strategyFails, 0);
            strategyCycleSteps = 0;
            cellularPreferredStrategy = defaultStrategyForNetwork(true);
            wifiPreferredStrategy = STRATEGY_SPLIT_1;
            currentStrategy = defaultStrategyForNetwork(cellularNetwork);
            strategyFound = false;
        }
        clearPersistedHints();
        hostStrategies.clear();
        ColgramTelegramWsBridge.resetNetworkState();
    }

    static boolean isOverlayVpnActive(boolean warpConnected, boolean ownSystemDpiActive,
                                      boolean activeNetworkHasTransportVpn) {
        if (warpConnected) return true;
        if (ownSystemDpiActive) return false;
        return activeNetworkHasTransportVpn;
    }

    static boolean isWsCapableForSession(boolean isCallTunnel, boolean systemWide, boolean wsEdgeHost) {
        return !isCallTunnel || (systemWide && !wsEdgeHost);
    }

    static boolean isOverlayVpnActive() {
        // Bypass DPI/WSS handling ONLY when WARP or an EXTERNAL Android VPN is active on the
        // default network (NetworkCapabilities.TRANSPORT_VPN), excluding our own whole-device
        // local DPI VPN. NEVER scan NetworkInterface.getNetworkInterfaces() for tun*/ppp*/wg*
        // because real phones expose VoLTE/IMS/ePDG tun*/ppp* interfaces that would false-positive
        // and silently kill both desync and WSS!
        if (ColgramWarpTunnel.isConnected()) return true;
        android.content.Context ctx = resolveAppContext();
        boolean ownSystemDpi = activeSystemDpiVpn != null
                || (ctx != null && ColgramCallSocksTunnel.isSystemDpiRunning(ctx));
        if (ownSystemDpi) return false;
        long now = safeElapsedRealtime();
        if (now - lastTunCheckAt < 1000L) return cachedTunUp;
        boolean hasTransportVpn = false;
        if (ctx != null) {
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network activeNet = cm.getActiveNetwork();
                    android.net.NetworkCapabilities caps = activeNet != null
                            ? cm.getNetworkCapabilities(activeNet) : null;
                    hasTransportVpn = caps != null
                            && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
                }
            } catch (Throwable ignored) {
                hasTransportVpn = false;
            }
        }
        boolean up = isOverlayVpnActive(false, false, hasTransportVpn);
        cachedTunUp = up;
        lastTunCheckAt = now;
        return up;
    }

    private static InetSocketAddress resolveBypassEndpoint(String host, int port) {
        if (ColgramConfig.isDohEnabled() && !ColgramDohResolver.isIpLiteral(host)) {
            android.net.VpnService sysVpn = activeSystemDpiVpn;
            InetAddress resolved = null;
            if (sysVpn != null) {
                InetAddress[] all = ColgramDohResolver.resolveProtectedAll(
                        host, sysVpn::protect, sysVpn::protect, ColgramDohResolver.hasGlobalRoutableIpv6());
                if (all != null && all.length > 0) {
                    resolved = all[0];
                }
            } else {
                resolved = ColgramDohResolver.resolveOrNull(host);
            }
            if (resolved != null) {
                return new InetSocketAddress(resolved, port);
            }
            if (ColgramConfig.isStrictProxySecurityEnabled()) {
                return null;
            }
        }
        return new InetSocketAddress(host, port);
    }

    /**
     * Connects to the destination using native ByeDPI sockets. For official Telegram DC IPs,
     * races same-DC IPv4+IPv6 aliases across official Telegram MTProto ports (443, 5222, 80) when
     * port 443 is blocked/throttled — 100% official Telegram servers, zero third-party proxy.
     */
    private static Socket establishConnection(String host, int port) {
        if (port < 1 || port > 65535) return null;
        InetSocketAddress primary = resolveBypassEndpoint(host, port);
        return primary != null ? establishConnection(host, port, primary) : null;
    }

    private static Socket establishConnection(String host, int port, InetSocketAddress primary) {
        if (port < 1 || port > 65535 || primary == null) return null;
        String key = baseDomainKey(host);
        boolean noMssClamp = isStreamingOrVoiceDomain(key);
        boolean confirmed = isHostStrategyConfirmed(key, cellularNetwork);
        int initStrat = pickStrategyForHost(key);
        // Always use standard RFC TCP SYN (mss=0, wclamp=0) on unconfirmed or streaming/voice hosts.
        int mss = (confirmed && !noMssClamp) ? mssClampForStrategy(initStrat) : 0;
        int wclamp = (confirmed && !noMssClamp) ? windowClampForStrategy(initStrat) : 0;
        android.net.VpnService sysVpn = activeSystemDpiVpn;
        ColgramDohResolver.SocketProtector sockProt = sysVpn != null ? sysVpn::protect : null;
        ColgramNativeDesync.FdProtector fdProt = sysVpn != null ? sysVpn::protect : null;
        boolean isProtected = (fdProt != null || sockProt != null);
        boolean allowIpv6 = ColgramDohResolver.hasRoutableIpv6ForCurrentSocket(isProtected)
                || (ColgramConfig.isIpv6BypassEnabled() && (isProtected || !isOverlayVpnActive()));
        boolean isTelegramDc = ColgramTelegramDcAddresses.isKnownAddress(host);
        String[] aliases = ColgramTelegramDcAddresses.candidatesFor(host);

        java.util.List<InetSocketAddress> alts = new java.util.ArrayList<>();
        if (!allowIpv6 && host.indexOf(':') >= 0) {
            for (String alias : aliases) {
                if (alias != null && alias.indexOf(':') < 0) {
                    alts.add(new InetSocketAddress(alias, port));
                }
            }
            if (!alts.isEmpty()) {
                InetSocketAddress v4Primary = alts.remove(0);
                if (isTelegramDc && port == 443) {
                    addTelegramAltPorts(alts, aliases, false);
                }
                return ColgramSocketConnectRace.connectPreferredNative(
                        v4Primary, alts.toArray(new InetSocketAddress[0]), telegramDialBudgetMs(),
                        mss, wclamp, sockProt, fdProt);
            }
        }

        for (String alias : aliases) {
            if (alias == null || alias.isEmpty()) continue;
            if (!allowIpv6 && alias.indexOf(':') >= 0) continue;
            alts.add(new InetSocketAddress(alias, port));
        }
        if (isTelegramDc && port == 443) {
            addTelegramAltPorts(alts, aliases, allowIpv6);
        }
        return ColgramSocketConnectRace.connectPreferredNative(
                primary, alts.toArray(new InetSocketAddress[0]), telegramDialBudgetMs(),
                mss, wclamp, sockProt, fdProt);
    }

    private static void addTelegramAltPorts(java.util.List<InetSocketAddress> out,
                                            String[] aliases, boolean allowIpv6) {
        // Official Telegram DCs accept identical MTProto Obfuscated2 on ports 5222 and 80.
        int addedV4 = 0;
        int addedV6 = 0;
        for (String alias : aliases) {
            if (alias == null || alias.isEmpty()) continue;
            boolean isV6 = alias.indexOf(':') >= 0;
            if (isV6) {
                if (!allowIpv6 || addedV6 >= 1) continue;
                out.add(new InetSocketAddress(alias, 5222));
                out.add(new InetSocketAddress(alias, 80));
                addedV6++;
            } else {
                if (addedV4 >= 2) continue;
                out.add(new InetSocketAddress(alias, 5222));
                out.add(new InetSocketAddress(alias, 80));
                addedV4++;
            }
        }
    }

    private static Socket connectDirectIp(String ipLiteral, int port, int mssClamp) {
        return connectDirectIp(ipLiteral, port, mssClamp, 0);
    }

    private static Socket connectDirectIp(String ipLiteral, int port, int mssClamp, int windowClamp) {
        if (ipLiteral == null || ipLiteral.isEmpty() || port <= 0) return null;
        int timeoutMs = cellularNetwork ? 4500 : 3000;
        android.net.VpnService sysVpn = activeSystemDpiVpn;
        if (ColgramNativeDesync.isAvailable()) {
            try {
                ColgramNativeDesync.FdProtector fdProt = sysVpn != null ? sysVpn::protect : null;
                return ColgramNativeDesync.connect(ipLiteral, port, timeoutMs, mssClamp, windowClamp, fdProt);
            } catch (Throwable ignored) {}
        }
        Socket s = new Socket(java.net.Proxy.NO_PROXY);
        try {
            tuneSocketBuffers(s);
            if (sysVpn != null && !sysVpn.protect(s)) {
                closeQuietly(s);
                return null;
            }
            connectTracked(s, new InetSocketAddress(ipLiteral, port), timeoutMs);
            return s;
        } catch (Throwable ignored) {
            closeQuietly(s);
            return null;
        }
    }

    /**
     * Whether a host is one of Telegram's own MTProto endpoints, where the multi-port
     * optional user-approved relay fallback is permitted.
     */
    private static boolean isMtProtoHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.US);
        return h.endsWith(".telegram.org")
                || h.endsWith(".t.me")
                || h.contains("telegram.dog")
                || h.startsWith("149.154.")
                || h.startsWith("91.108.")
                || ColgramTelegramDcAddresses.isKnownAddress(h);
    }

    // ==================================================================================
    // Layer-2 Automatic L3-Relay Cascade (encrypted targets only; never plaintext port 80)
    // ==================================================================================

    static boolean isTelegramDcIp(String host) {
        return host != null && !host.isEmpty() && ColgramTelegramDcAddresses.isKnownAddress(host);
    }

    private static boolean isLoopbackOrPrivateTarget(String host) {
        if (host == null || host.isEmpty()) return true;
        String h = host.trim().toLowerCase(java.util.Locale.US);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if ("localhost".equals(h) || "::1".equals(h) || "::".equals(h) || "0.0.0.0".equals(h)) {
            return true;
        }
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.startsWith("169.254.") || h.startsWith("0.")
                || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) {
            return true;
        }
        if (h.startsWith("172.")) {
            int dot = h.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(h.substring(4, dot));
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    /**
     * Strict encryption security guard for automatic Layer-2 L3-relay cascade:
     * ONLY allows encrypted targets (Telegram DC MTProto IPs or TLS/encrypted ports 443/8443/5222)
     * and NEVER allows plaintext port 80 or loopback/private LAN targets.
     */
    static boolean isEligibleForL3RelayCascade(String targetHost, int targetPort) {
        if (targetHost == null) return false;
        String trimmed = targetHost.trim();
        if (trimmed.isEmpty() || targetPort <= 0 || targetPort > 65535) return false;
        if (targetPort == 80) return false;
        if (isLoopbackOrPrivateTarget(trimmed)) return false;
        return isTelegramDcIp(trimmed) || targetPort == 443 || targetPort == 8443 || targetPort == 5222;
    }

    /**
     * Automatic Layer-2 L3-relay cascade: pulls verified reachable SOCKS5/HTTP-CONNECT relays from
     * {@link ColgramProxyManager#getReachableRelays()}, races up to 3 fastest relays with bounded
     * per-relay timeout, protects outbound sockets when TUN is active, and tunnels encrypted
     * traffic transparently end-to-end.
     */
    static Socket connectViaL3RelayCascade(String targetHost, int targetPort, int timeoutMs,
                                           ColgramDohResolver.SocketProtector protector) {
        java.util.List<ColgramProxyChain.Relay> relays;
        try {
            relays = ColgramProxyManager.getReachableRelays();
        } catch (Throwable ignored) {
            return null;
        }
        return connectViaL3RelayCascade(targetHost, targetPort, timeoutMs, protector, relays);
    }

    private static java.util.List<ColgramProxyChain.Relay> selectL3RelayTopCandidates(
            java.util.List<ColgramProxyChain.Relay> relays) {
        if (relays == null || relays.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<ColgramProxyChain.Relay> fresh = new java.util.ArrayList<>(relays.size());
        java.util.List<ColgramProxyChain.Relay> penalized = new java.util.ArrayList<>();
        for (ColgramProxyChain.Relay r : relays) {
            if (r == null || r.dead || r.host == null || r.host.isEmpty() || r.port <= 0) continue;
            if (isL3RelayPenalized(r.host, r.port)) {
                penalized.add(r);
            } else {
                fresh.add(r);
            }
        }
        java.util.List<ColgramProxyChain.Relay> pool = !fresh.isEmpty() ? fresh : penalized;
        if (pool.isEmpty()) return java.util.Collections.emptyList();
        pool.sort((a, b) -> Integer.compare(
                a.rttMs >= 0 ? a.rttMs : Integer.MAX_VALUE,
                b.rttMs >= 0 ? b.rttMs : Integer.MAX_VALUE));
        int count = Math.min(L3_RELAY_MAX_CANDIDATES, pool.size());
        return new java.util.ArrayList<>(pool.subList(0, count));
    }

    private static ColgramDohResolver.SocketProtector resolveRelayProtector(
            ColgramDohResolver.SocketProtector protector) {
        if (protector != null) return protector;
        android.net.VpnService sysVpn = activeSystemDpiVpn;
        return sysVpn != null ? sysVpn::protect : null;
    }

    private static ColgramProxyChain.Relay findPinnedRelayIn(java.util.List<ColgramProxyChain.Relay> relays) {
        ColgramProxyChain.Relay pinned = pinnedTelegramL3Relay;
        if (pinned == null || relays == null || relays.isEmpty()) return null;
        if (pinned.dead || isL3RelayPenalized(pinned.host, pinned.port)) {
            pinnedTelegramL3Relay = null;
            return null;
        }
        String pinnedKey = normalizeRelayKey(pinned.host, pinned.port);
        for (ColgramProxyChain.Relay r : relays) {
            if (r != null && !r.dead && pinnedKey.equals(normalizeRelayKey(r.host, r.port))) {
                return r;
            }
        }
        return null;
    }

    static Socket connectViaL3RelayCascade(String targetHost, int targetPort, int timeoutMs,
                                           ColgramDohResolver.SocketProtector protector,
                                           java.util.List<ColgramProxyChain.Relay> relays) {
        if (!isEligibleForL3RelayCascade(targetHost, targetPort)) return null;
        final boolean telegramTarget = isTelegramDcIp(targetHost);
        final ColgramDohResolver.SocketProtector finalProtector = resolveRelayProtector(protector);
        final int maxPerRelay = l3RelayTimeoutMs();
        final int perRelayTimeout = Math.max(800,
                Math.min(timeoutMs > 0 ? timeoutMs : maxPerRelay, maxPerRelay));

        // Pin all concurrent Telegram MTProto sockets to ONE sticky L3 relay exit IP so Telegram
        // never sees the same auth_key from multiple public IPs simultaneously (AUTH_KEY_DUPLICATED).
        if (telegramTarget) {
            ColgramProxyChain.Relay pinned = findPinnedRelayIn(relays);
            if (pinned != null) {
                Socket pinnedSock = dialThroughSingleRelay(pinned, targetHost, targetPort, perRelayTimeout, finalProtector);
                if (pinnedSock != null) {
                    l3RelayLabelsBySocket.put(pinnedSock, pinned.host + ":" + pinned.port);
                    recordL3RelaySuccess(pinned, targetHost, targetPort);
                    return pinnedSock;
                }
                markRelayFailed(pinned.host, pinned.port);
            }
        }

        java.util.List<ColgramProxyChain.Relay> top = selectL3RelayTopCandidates(relays);
        if (top.isEmpty()) return null;

        if (top.size() == 1) {
            ColgramProxyChain.Relay only = top.get(0);
            Socket s = dialThroughSingleRelay(only, targetHost, targetPort, perRelayTimeout, finalProtector);
            if (s != null) {
                l3RelayLabelsBySocket.put(s, only.host + ":" + only.port);
                if (telegramTarget) pinnedTelegramL3Relay = only;
                recordL3RelaySuccess(only, targetHost, targetPort);
            } else {
                markRelayFailed(only.host, only.port);
            }
            return s;
        }

        final Object winnerLock = new Object();
        final Socket[] winnerSock = new Socket[1];
        final ColgramProxyChain.Relay[] winnerRelay = new ColgramProxyChain.Relay[1];
        final boolean[] finished = new boolean[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(top.size());
        final long[] delaysMs = {0L, 60L, 120L, 180L};

        for (int i = 0; i < top.size(); i++) {
            final ColgramProxyChain.Relay relay = top.get(i);
            final long delay = i < delaysMs.length ? delaysMs[i] : 240L;
            if (!executeWorker(() -> {
                Socket sock = null;
                boolean selected = false;
                try {
                    if (delay > 0L) Thread.sleep(delay);
                    synchronized (winnerLock) {
                        if (finished[0] || winnerSock[0] != null) return;
                    }
                    sock = dialThroughSingleRelay(relay, targetHost, targetPort, perRelayTimeout, finalProtector);
                    if (sock != null) {
                        synchronized (winnerLock) {
                            if (!finished[0] && winnerSock[0] == null) {
                                winnerSock[0] = sock;
                                winnerRelay[0] = relay;
                                selected = true;
                                finished[0] = true;
                                latch.countDown();
                            }
                        }
                    } else {
                        markRelayFailed(relay.host, relay.port);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!selected && sock != null) closeQuietly(sock);
                    if (remaining.decrementAndGet() == 0) {
                        synchronized (winnerLock) {
                            finished[0] = true;
                        }
                        latch.countDown();
                    }
                }
            })) {
                if (remaining.decrementAndGet() == 0) {
                    synchronized (winnerLock) { finished[0] = true; }
                    latch.countDown();
                }
            }
        }

        long raceBudgetMs = Math.max(perRelayTimeout + 400L,
                timeoutMs > 0 ? timeoutMs : (L3_RELAY_CONNECT_TIMEOUT_MS + 400L));
        try {
            latch.await(raceBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        Socket result;
        ColgramProxyChain.Relay chosen;
        synchronized (winnerLock) {
            finished[0] = true;
            result = winnerSock[0];
            chosen = winnerRelay[0];
        }
        if (result != null && chosen != null) {
            l3RelayLabelsBySocket.put(result, chosen.host + ":" + chosen.port);
            if (telegramTarget) pinnedTelegramL3Relay = chosen;
            recordL3RelaySuccess(chosen, targetHost, targetPort);
        }
        return result;
    }

    private static void recordL3RelaySuccess(ColgramProxyChain.Relay relay, String targetHost, int targetPort) {
        lastL3RelaySuccessAt = safeElapsedRealtime();
        activeL3RelayLabel = relay.host + ":" + relay.port;
        safeLogI("L3_RELAY_CASCADE_OK relay=" + activeL3RelayLabel
                + " mode=" + (relay.socks ? "socks5" : "http-connect")
                + " target=" + targetHost + ":" + targetPort);
    }

    private static Socket dialThroughSingleRelay(ColgramProxyChain.Relay relay, String targetHost,
                                                 int targetPort, int perRelayTimeoutMs,
                                                 ColgramDohResolver.SocketProtector protector) {
        Socket sock = null;
        try {
            InetAddress relayAddr = null;
            if (ColgramDohResolver.isIpLiteral(relay.host)) {
                relayAddr = InetAddress.getByName(relay.host);
            } else {
                try {
                    InetAddress[] resolved = ColgramDohResolver.resolveOrSystem(relay.host);
                    if (resolved != null && resolved.length > 0) relayAddr = resolved[0];
                } catch (Throwable ignored) {}
                if (relayAddr == null) relayAddr = InetAddress.getByName(relay.host);
            }
            if (relayAddr == null) return null;
            sock = new Socket(java.net.Proxy.NO_PROXY);
            tuneSocketBuffers(sock);
            if (protector != null && !protector.protect(sock)) {
                closeQuietly(sock);
                return null;
            }
            trackDialSocket(sock);
            try {
                sock.connect(new InetSocketAddress(relayAddr, relay.port), perRelayTimeoutMs);
                sock.setSoTimeout(perRelayTimeoutMs);
                boolean ok = relay.socks
                        ? performSocks5Connect(sock.getOutputStream(), sock.getInputStream(),
                                targetHost, targetPort, relay.username, relay.password)
                        : performHttpConnect(sock.getOutputStream(), sock.getInputStream(),
                                targetHost, targetPort, relay.username, relay.password);
                if (!ok) {
                    closeQuietly(sock);
                    return null;
                }
            } finally {
                untrackDialSocket(sock);
            }
            sock.setSoTimeout(0);
            return sock;
        } catch (Throwable ignored) {
            closeQuietly(sock);
            return null;
        }
    }

    private static int l3RelayTimeoutMs() {
        return cellularNetwork ? L3_CELLULAR_RELAY_CONNECT_TIMEOUT_MS : L3_RELAY_CONNECT_TIMEOUT_MS;
    }

    /**
     * Clean RFC 1928 SOCKS5 CONNECT handshake (IPv4 / IPv6 / domain, no-auth or RFC 1929 user/pass)
     * with 2-byte TCP segmentation across the target address bytes via {@link ColgramSocks5Codec}
     * so carrier TSPU DPI (e.g. MegaFon/Yota LTE) never drops {@code 05 01 00 01 <149.154.*>}.
     */
    private static boolean performSocks5Connect(OutputStream out, InputStream in,
                                                String targetHost, int targetPort,
                                                String username, String password) throws java.io.IOException {
        return ColgramSocks5Codec.requestConnectHost(out, in, targetHost, targetPort, username, password);
    }

    /** Clean HTTP/1.1 CONNECT tunnel handshake segmented across the target host/IP for TSPU evasion. */
    private static boolean performHttpConnect(OutputStream out, InputStream in,
                                              String targetHost, int targetPort,
                                              String username, String password) throws java.io.IOException {
        if (targetHost == null || targetHost.isEmpty() || targetPort < 1 || targetPort > 65535) return false;
        ColgramSocks5Codec.writeHttpConnectSegmented(out, targetHost, targetPort, username, password);

        String status = readHttpLineBounded(in);
        if (status == null) return false;
        int sp = status.indexOf(' ');
        if (sp < 0 || sp + 4 > status.length()) return false;
        if (status.charAt(sp + 1) != '2') return false;
        for (int i = 0; i < 100; i++) {
            String line = readHttpLineBounded(in);
            if (line == null) return false;
            if (line.isEmpty()) return true;
        }
        return false;
    }

    private static String readHttpLineBounded(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            if (sb.length() < 4096) sb.append((char) c);
        }
        return null;
    }

    /**
     * Layer-2 WSS-over-segmented-SOCKS5/HTTP-CONNECT cascade for Telegram DCs: opens a segmented
     * {@code ATYP=0x03 kws<dc>.web.telegram.org:443} tunnel through the fastest reachable L3 relay
     * and upgrades TLS 1.3 + {@code /apiws} with spoofed RU-whitelist SNI + fragmented ClientHello
     * (evading MegaFon/Yota TSPU in-tunnel SNI DPI while preserving CA + Telegram SAN verification).
     */
    private static String firstIpv4EdgeForDc(int dcId) {
        int norm = ColgramTelegramWsBridge.Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm == 2 || norm == 4) return "149.154.167.220";
        String[] ips = ColgramTelegramWsBridge.getVerifiedWssEdgeIpsForDc(norm);
        if (ips != null) {
            for (String ip : ips) {
                if (ip != null && ip.indexOf(':') < 0) return ip;
            }
        }
        return null;
    }

    private static ColgramTelegramWsBridge.WsSession dialSingleWssOverRelay(
            ColgramProxyChain.Relay relay, int signedDcId, String wssHost,
            int perRelayTimeout, ColgramDohResolver.SocketProtector finalProtector) {
        String label = relay.host + ":" + relay.port;
        Socket hostSock = dialThroughSingleRelay(relay, wssHost, 443, perRelayTimeout, finalProtector);
        if (hostSock != null) {
            ColgramTelegramWsBridge.WsSession sess =
                    ColgramTelegramWsBridge.dialViaRelaySocket(hostSock, signedDcId, wssHost, label);
            if (sess != null) return sess;
        }
        String fallbackIp = firstIpv4EdgeForDc(signedDcId);
        if (fallbackIp != null && !fallbackIp.equals(wssHost)) {
            Socket ipSock = dialThroughSingleRelay(relay, fallbackIp, 443, perRelayTimeout, finalProtector);
            if (ipSock != null) {
                ColgramTelegramWsBridge.WsSession sess =
                        ColgramTelegramWsBridge.dialViaRelaySocket(ipSock, signedDcId, wssHost, label);
                if (sess != null) return sess;
            }
        }
        return null;
    }

    static ColgramTelegramWsBridge.WsSession dialWssViaL3RelayCascade(
            String destHost, int destPort, ColgramDohResolver.SocketProtector protector) {
        return dialWssViaL3RelayCascade(destHost, destPort,
                ColgramTelegramDcAddresses.signedDcIdFor(destHost) < 0, protector);
    }

    static ColgramTelegramWsBridge.WsSession dialWssViaL3RelayCascade(
            String destHost, int destPort, boolean preferMediaEdge,
            ColgramDohResolver.SocketProtector protector) {
        java.util.List<ColgramProxyChain.Relay> relays;
        try {
            relays = ColgramProxyManager.getReachableRelays();
        } catch (Throwable ignored) {
            return null;
        }
        return dialWssViaL3RelayCascade(destHost, destPort, preferMediaEdge, protector, relays);
    }

    static ColgramTelegramWsBridge.WsSession dialWssViaL3RelayCascade(
            String destHost, int destPort, ColgramDohResolver.SocketProtector protector,
            java.util.List<ColgramProxyChain.Relay> relays) {
        return dialWssViaL3RelayCascade(destHost, destPort,
                ColgramTelegramDcAddresses.signedDcIdFor(destHost) < 0, protector, relays);
    }

    static ColgramTelegramWsBridge.WsSession dialWssViaL3RelayCascade(
            String destHost, int destPort, boolean preferMediaEdge,
            ColgramDohResolver.SocketProtector protector,
            java.util.List<ColgramProxyChain.Relay> relays) {
        if (!isEligibleForL3RelayCascade(destHost, destPort)) return null;
        final int baseSigned = ColgramTelegramDcAddresses.signedDcIdFor(destHost);
        final int normDc = ColgramTelegramWsBridge.Obfuscated2FrameSplitter.normalizeDcId(baseSigned);
        if (normDc < 1 || normDc > 5) return null;
        final boolean media = preferMediaEdge || baseSigned < 0;
        final int signedDc = media ? -normDc : normDc;
        final String wssHost = ColgramTelegramWsBridge.primaryWssHostForSignedDc(signedDc);
        final ColgramDohResolver.SocketProtector finalProtector = resolveRelayProtector(protector);
        final int perRelayTimeout = l3RelayTimeoutMs();

        ColgramProxyChain.Relay pinned = findPinnedRelayIn(relays);
        if (pinned != null) {
            String label = pinned.host + ":" + pinned.port;
            ColgramTelegramWsBridge.WsSession pinnedSess =
                    dialSingleWssOverRelay(pinned, signedDc, wssHost, perRelayTimeout, finalProtector);
            if (pinnedSess != null) {
                lastL3RelaySuccessAt = safeElapsedRealtime();
                activeL3RelayLabel = label;
                safeLogI("L3_WSS_RELAY_OK relay=" + label + " host=" + wssHost + " dc=" + signedDc + " (pinned)");
                return pinnedSess;
            }
            markRelayFailed(pinned.host, pinned.port);
        }

        java.util.List<ColgramProxyChain.Relay> top = selectL3RelayTopCandidates(relays);
        if (top.isEmpty()) return null;

        if (top.size() == 1) {
            ColgramProxyChain.Relay only = top.get(0);
            String label = only.host + ":" + only.port;
            ColgramTelegramWsBridge.WsSession sess =
                    dialSingleWssOverRelay(only, signedDc, wssHost, perRelayTimeout, finalProtector);
            if (sess != null) {
                pinnedTelegramL3Relay = only;
                lastL3RelaySuccessAt = safeElapsedRealtime();
                activeL3RelayLabel = label;
                safeLogI("L3_WSS_RELAY_OK relay=" + label + " host=" + wssHost + " dc=" + signedDc);
            } else {
                markRelayFailed(only.host, only.port);
            }
            return sess;
        }

        final Object winnerLock = new Object();
        final ColgramTelegramWsBridge.WsSession[] winner = new ColgramTelegramWsBridge.WsSession[1];
        final ColgramProxyChain.Relay[] winnerRelay = new ColgramProxyChain.Relay[1];
        final boolean[] finished = new boolean[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(top.size());
        final long[] delaysMs = {0L, 90L, 180L, 270L, 360L, 450L};

        for (int i = 0; i < top.size(); i++) {
            final ColgramProxyChain.Relay relay = top.get(i);
            final long delay = i < delaysMs.length ? delaysMs[i] : 450L;
            if (!executeWorker(() -> {
                ColgramTelegramWsBridge.WsSession sess = null;
                boolean selected = false;
                try {
                    if (delay > 0L) Thread.sleep(delay);
                    synchronized (winnerLock) {
                        if (finished[0] || winner[0] != null) return;
                    }
                    sess = dialSingleWssOverRelay(relay, signedDc, wssHost, perRelayTimeout, finalProtector);
                    if (sess == null) {
                        markRelayFailed(relay.host, relay.port);
                        return;
                    }
                    synchronized (winnerLock) {
                        if (!finished[0] && winner[0] == null) {
                            winner[0] = sess;
                            winnerRelay[0] = relay;
                            selected = true;
                            finished[0] = true;
                            latch.countDown();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!selected && sess != null) closeQuietly(sess);
                    if (remaining.decrementAndGet() == 0) {
                        synchronized (winnerLock) {
                            finished[0] = true;
                        }
                        latch.countDown();
                    }
                }
            })) {
                if (remaining.decrementAndGet() == 0) {
                    synchronized (winnerLock) { finished[0] = true; }
                    latch.countDown();
                }
            }
        }

        try {
            latch.await(Math.max(perRelayTimeout + 2500L, 5200L), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        ColgramTelegramWsBridge.WsSession chosenSess;
        ColgramProxyChain.Relay chosenRelay;
        synchronized (winnerLock) {
            finished[0] = true;
            chosenSess = winner[0];
            chosenRelay = winnerRelay[0];
        }
        if (chosenSess != null && chosenRelay != null) {
            String label = chosenRelay.host + ":" + chosenRelay.port;
            pinnedTelegramL3Relay = chosenRelay;
            lastL3RelaySuccessAt = safeElapsedRealtime();
            activeL3RelayLabel = label;
            safeLogI("L3_WSS_RELAY_OK relay=" + label + " host=" + wssHost + " dc=" + signedDc);
        }
        return chosenSess;
    }

    /**
     * Writes the very first client-&gt;target payload over a plaintext L3 SOCKS5/HTTP-CONNECT relay.
     * If the payload is a TLS ClientHello ({@code 0x16 0x03 ...}), splits it into 2 RFC-valid TLS
     * records across SNI + 2-byte TCP segments (defeating carrier TSPU SNI DPI inside plaintext
     * SOCKS5 tunnels, e.g. MegaFon/Yota LTE). Non-TLS encrypted traffic (MTProto obfuscated2) is
     * forwarded verbatim.
     */
    static void writeFirstPayloadForL3Relay(OutputStream out, byte[] buf, int len) throws java.io.IOException {
        if (buf == null || len <= 0) return;
        byte[] slice = (len == buf.length) ? buf : java.util.Arrays.copyOf(buf, len);
        if (ColgramTlsMimic.looksLikeClientHello(slice)) {
            byte[][] recs = ColgramTlsMimic.splitTlsRecordAtSni(slice, 2);
            if (recs != null && recs.length >= 2) {
                byte[] r0 = recs[0];
                int headCut = Math.min(r0.length, 6);
                for (int pos = 0; pos < headCut; pos += 2) {
                    out.write(r0, pos, Math.min(2, headCut - pos));
                    out.flush();
                }
                if (headCut < r0.length) {
                    out.write(r0, headCut, r0.length - headCut);
                    out.flush();
                }
                for (int r = 1; r < recs.length; r++) {
                    out.write(recs[r]);
                    out.flush();
                }
                return;
            }
            int[] sni = ColgramTlsMimic.findTlsSniSpan(slice, slice.length);
            if (sni != null) {
                int segStart = Math.max(0, sni[0] - 2);
                int segEnd = Math.min(slice.length, sni[1]);
                if (segStart > 0) {
                    out.write(slice, 0, segStart);
                    out.flush();
                }
                for (int pos = segStart; pos < segEnd; pos += 2) {
                    out.write(slice, pos, Math.min(2, segEnd - pos));
                    out.flush();
                }
                if (segEnd < slice.length) {
                    out.write(slice, segEnd, slice.length - segEnd);
                    out.flush();
                }
                return;
            }
        }
        out.write(slice, 0, slice.length);
        out.flush();
    }

    private static final class L3RaceResult {
        final Socket socket;
        final ColgramTelegramWsBridge.WsSession wsSession;
        final boolean viaL3Relay;
        final boolean directRecovered;

        L3RaceResult(Socket socket, ColgramTelegramWsBridge.WsSession wsSession,
                     boolean viaL3Relay, boolean directRecovered) {
            this.socket = socket;
            this.wsSession = wsSession;
            this.viaL3Relay = viaL3Relay;
            this.directRecovered = directRecovered;
        }
    }

    /**
     * Races Layer-2 L3-relay cascade immediately against a fast 900ms direct TCP SYN recovery
     * probe (to {@code destHost:destPort} and primary verified WSS edge) when strict L3-whitelist
     * blocking is active. On L3-blocked towers, Layer 2 connects in &lt;2s; the instant direct SYN
     * starts succeeding again (e.g. returning to Wi-Fi), clears {@code strictL3WhitelistDetected}
     * and snaps right back to 100% pure local Layer-1 bypass.
     */
    private static L3RaceResult raceLayer2WithFastDirectProbe(final String destHost, final int destPort,
                                                              final boolean wsCapableDest,
                                                              final boolean isMedia,
                                                              final ColgramDohResolver.SocketProtector protector) {
        java.util.List<ColgramProxyChain.Relay> relays = java.util.Collections.emptyList();
        try {
            relays = ColgramProxyManager.getReachableRelays();
            if (relays.isEmpty()) {
                long nowMs = safeElapsedRealtime();
                if (strictL3ZeroRelaysSinceMs == 0L) {
                    strictL3ZeroRelaysSinceMs = nowMs;
                }
                ColgramProxyManager.triggerImmediateL3RelayRefresh();
                if (nowMs - strictL3ZeroRelaysSinceMs >= L3_ZERO_RELAY_WARP_TRIGGER_MS) {
                    strictL3ZeroRelaysSinceMs = nowMs;
                    ColgramProxyManager.triggerEmergencyWarpProbeIfAvailable();
                }
            } else {
                strictL3ZeroRelaysSinceMs = 0L;
            }
        } catch (Throwable ignored) {}

        final java.util.List<ColgramProxyChain.Relay> relaySnapshot = relays;
        final Object gate = new Object();
        final L3RaceResult[] winner = new L3RaceResult[1];
        final boolean[] finished = new boolean[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int laneCount = relaySnapshot.isEmpty() ? 1 : 2;
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(laneCount);

        // Lane A: fast 900ms direct SYN recovery probe
        if (!executeWorker(() -> {
            Socket directSock = null;
            boolean handedToWinner = false;
            try {
                // Probe primary verified WSS edge (fast path for Telegram recovery) or direct target
                String edgeIp = null;
                if (wsCapableDest) {
                    int dc = ColgramTelegramDcAddresses.dcIdFor(destHost);
                    String[] verified = ColgramTelegramWsBridge.getVerifiedWssEdgeIpsForDc(dc > 0 ? dc : 2);
                    if (verified != null && verified.length > 0) {
                        boolean v6Ok = hasGlobalIpv6Safe();
                        for (String ip : verified) {
                            if (ip != null && !ip.isEmpty() && (v6Ok || ip.indexOf(':') < 0)) {
                                edgeIp = ip;
                                break;
                            }
                        }
                    }
                }
                if (edgeIp != null) {
                    Socket probe = new Socket(java.net.Proxy.NO_PROXY);
                    boolean probeHandedOff = false;
                    try {
                        probe.setTcpNoDelay(true);
                        tuneSocketBuffers(probe);
                        if (protector == null || protector.protect(probe)) {
                            connectTracked(probe, new InetSocketAddress(edgeIp, 443), L3_DIRECT_RECOVERY_PROBE_MS);
                            if (wsCapableDest) {
                                ColgramTelegramWsBridge.WsSession wsSess =
                                        ColgramTelegramWsBridge.dialWithConnectedSocket(probe, destHost, isMedia);
                                if (wsSess != null) {
                                    probeHandedOff = true;
                                    onDirectLayer1Success();
                                    safeLogI("L3_RECOVERY_WS_OK: direct WSS edge probe (" + edgeIp + ":443)");
                                    synchronized (gate) {
                                        if (!finished[0] && winner[0] == null) {
                                            winner[0] = new L3RaceResult(null, wsSess, false, true);
                                            finished[0] = true;
                                            latch.countDown();
                                        } else {
                                            closeQuietly(wsSess);
                                        }
                                    }
                                    return;
                                }
                                // dialWithConnectedSocket closes probe on failure, mark handed off
                                probeHandedOff = true;
                            } else {
                                onDirectLayer1Success();
                                safeLogI("L3_RECOVERY_OK: direct WSS edge SYN restored (" + edgeIp + ":443)");
                                synchronized (gate) {
                                    if (!finished[0] && winner[0] == null) {
                                        winner[0] = new L3RaceResult(null, null, false, true);
                                        finished[0] = true;
                                        latch.countDown();
                                    }
                                }
                                return;
                            }
                        }
                    } finally {
                        if (!probeHandedOff) closeQuietly(probe);
                    }
                } else {
                    InetAddress addr = ColgramDohResolver.isIpLiteral(destHost)
                            ? InetAddress.getByName(destHost)
                            : ColgramDohResolver.resolveOrNull(destHost);
                    if (addr != null) {
                        Socket s = new Socket(java.net.Proxy.NO_PROXY);
                        s.setTcpNoDelay(true);
                        tuneSocketBuffers(s);
                        if (protector == null || protector.protect(s)) {
                            connectTracked(s, new InetSocketAddress(addr, destPort), L3_DIRECT_RECOVERY_PROBE_MS);
                            directSock = s;
                            if (isTelegramDcIp(destHost)) onDirectLayer1Success();
                            synchronized (gate) {
                                if (!finished[0] && winner[0] == null) {
                                    winner[0] = new L3RaceResult(directSock, null, false, true);
                                    handedToWinner = true;
                                    finished[0] = true;
                                    latch.countDown();
                                }
                            }
                        } else {
                            closeQuietly(s);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (!handedToWinner && directSock != null) closeQuietly(directSock);
                if (remaining.decrementAndGet() == 0) {
                    synchronized (gate) { finished[0] = true; }
                    latch.countDown();
                }
            }
        })) {
            if (remaining.decrementAndGet() == 0) {
                synchronized (gate) { finished[0] = true; }
                latch.countDown();
            }
        }

        // Lane B: immediate Layer-2 L3-relay cascade (segmented SOCKS5/HTTP CONNECT + WSS-over-SOCKS5 failover)
        if (!relaySnapshot.isEmpty()) {
            if (!executeWorker(() -> {
                Socket relaySock = null;
                ColgramTelegramWsBridge.WsSession relayWs = null;
                boolean handedToWinner = false;
                try {
                    boolean preferWssOverRelay = wsCapableDest && l3RawMtprotoSilentStreak.get() > 0;
                    if (preferWssOverRelay) {
                        relayWs = dialWssViaL3RelayCascade(destHost, destPort, isMedia, protector, relaySnapshot);
                    }
                    if (relayWs == null) {
                        relaySock = connectViaL3RelayCascade(destHost, destPort,
                                l3RelayTimeoutMs(), protector, relaySnapshot);
                    }
                    if (relayWs == null && relaySock == null && wsCapableDest && !preferWssOverRelay) {
                        relayWs = dialWssViaL3RelayCascade(destHost, destPort, isMedia, protector, relaySnapshot);
                    }
                    if (relayWs != null || relaySock != null) {
                        synchronized (gate) {
                            if (!finished[0] && winner[0] == null) {
                                winner[0] = new L3RaceResult(relaySock, relayWs, true, false);
                                handedToWinner = true;
                                finished[0] = true;
                                latch.countDown();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!handedToWinner) {
                        if (relaySock != null) closeQuietly(relaySock);
                        if (relayWs != null) closeQuietly(relayWs);
                    }
                    if (remaining.decrementAndGet() == 0) {
                        synchronized (gate) { finished[0] = true; }
                        latch.countDown();
                    }
                }
            })) {
                if (remaining.decrementAndGet() == 0) {
                    synchronized (gate) { finished[0] = true; }
                    latch.countDown();
                }
            }
        }

        try {
            latch.await(l3RelayTimeoutMs() + 900L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (gate) {
            finished[0] = true;
            return winner[0] != null ? winner[0] : new L3RaceResult(null, null, false, false);
        }
    }

    /**
     * Bidirectional stream pipe over a Layer-2 L3 relay socket. Fragments any initial TLS
     * ClientHello across SNI (defeating carrier TSPU SNI DPI inside plaintext SOCKS5 tunnels)
     * while forwarding MTProto obfuscated2 verbatim, never falsely clears
     * {@code strictL3WhitelistDetected}, and refreshes {@code lastL3RelaySuccessAt} on live bytes.
     */
    private static void pipeViaL3Relay(final Socket client, final Socket relayDest,
                                       final CallListener call, final long generation,
                                       final byte[] initialPayload, final int initialLen,
                                       final boolean telegramTarget) {
        final long startEpoch = currentNetworkEpoch();
        tuneSocketBuffers(client);
        tuneSocketBuffers(relayDest);
        try { client.setSoTimeout(0); } catch (Throwable ignored) {}
        try { relayDest.setSoTimeout(0); } catch (Throwable ignored) {}
        final String relayLabel = l3RelayLabelsBySocket.remove(relayDest);
        final java.util.concurrent.atomic.AtomicBoolean firstReplySeen =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final ActiveSession tracked = registerActiveSession(client, relayDest, telegramTarget);

        if (!executeWorker(() -> {
            try {
                OutputStream dout = relayDest.getOutputStream();
                if (initialPayload != null && initialLen > 0) {
                    writeFirstPayloadForL3Relay(dout, initialPayload, initialLen);
                    tracked.onClientTx(safeElapsedRealtime());
                    if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(initialLen);
                } else {
                    byte[] first = ColgramInitialPacketReader.readInitialPacket(client, 2048, 8192, 60, 180);
                    if (first.length > 0) {
                        writeFirstPayloadForL3Relay(dout, first, first.length);
                        tracked.onClientTx(safeElapsedRealtime());
                        if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(first.length);
                    }
                }
                InputStream cin = client.getInputStream();
                byte[] buf = new byte[STREAM_COPY_BUFFER_BYTES];
                int n;
                while ((n = cin.read(buf)) != -1) {
                    dout.write(buf, 0, n);
                    flushIfDrained(cin, dout);
                    tracked.onClientTx(safeElapsedRealtime());
                    if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(n);
                }
            } catch (Throwable ignored) {
            } finally {
                try { relayDest.shutdownOutput(); } catch (Throwable ignored) {}
            }
        })) {
            unregisterActiveSession(tracked);
            closeQuietly(relayDest);
            closeQuietly(client);
            if (call != null) call.done(client, relayDest);
            return;
        }

        if (!executeWorker(() -> {
            try {
                InputStream din = relayDest.getInputStream();
                OutputStream cout = client.getOutputStream();
                byte[] buf = new byte[STREAM_COPY_BUFFER_BYTES];
                int n;
                if (telegramTarget) {
                    // Fail fast if a relay accepted CONNECT then dropped/stalled raw MTProto so the
                    // next tgnet retry immediately evicts the dead relay or upgrades to WSS-over-SOCKS5.
                    n = ColgramFirstResponseReader.readFirst(relayDest, buf, 2500);
                } else {
                    n = din.read(buf);
                }
                if (n > 0) {
                    firstReplySeen.set(true);
                    if (telegramTarget) l3RawMtprotoSilentStreak.set(0);
                    long rxNow = safeElapsedRealtime();
                    tracked.onUpstreamRx(rxNow);
                    lastL3RelaySuccessAt = rxNow;
                    if (call == null) publishUpstream(generation, UPSTREAM_REPLY);
                    if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(n);
                    cout.write(buf, 0, n);
                    cout.flush();
                    while ((n = din.read(buf)) != -1) {
                        long chunkNow = safeElapsedRealtime();
                        tracked.onUpstreamRx(chunkNow);
                        lastL3RelaySuccessAt = chunkNow;
                        if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(n);
                        cout.write(buf, 0, n);
                        flushIfDrained(din, cout);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                unregisterActiveSession(tracked);
                if (telegramTarget && !firstReplySeen.get() && startEpoch == currentNetworkEpoch()) {
                    l3RawMtprotoSilentStreak.incrementAndGet();
                    penalizeL3Relay(relayLabel);
                }
                closeQuietly(relayDest);
                closeQuietly(client);
                if (call != null) call.done(client, relayDest);
                if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
            }
        })) {
            unregisterActiveSession(tracked);
            closeQuietly(relayDest);
            closeQuietly(client);
            if (call != null) call.done(client, relayDest);
        }
    }

    // ==================================================================================
    // Desync strategies
    // ==================================================================================
    //
    // Colgram used to hardcode exactly ONE strategy: split the first packet at a fixed
    // offset with fixed 3 ms sleeps. Two problems with that:
    //
    //   * A fixed offset is the easiest possible thing for a DPI to learn. Real bypass
    //     tools randomise it.
    //   * Which technique gets through depends on the operator's TSPU. One strategy that
    //     works on one ISP does nothing on another's - so a single hardcoded strategy
    //     means "works for some people, silently does nothing for everyone else".
    //
    // So: several strategies, rotated per connection, with automatic probing. A connection
    // that carries real traffic back from the target counts as a success and the current
    // strategy is kept; one that closes without the target ever answering counts as a
    // failure and after a couple of those we move to the next strategy. That is the same
    // core idea as ByeDPI's strategy search, which is what makes those tools work without
    // per-ISP hand-tuning.

    private static final int STRATEGY_COUNT = 14;
    /** Fail a TCP-open-but-silent endpoint promptly; leave headroom inside tgnet's 8s timeout. */
    private static final int FIRST_REPLY_TIMEOUT_MS = 750;
    private static final int TELEGRAM_DIAL_BUDGET_MS = 1100;
    private static final int CELLULAR_FIRST_REPLY_TIMEOUT_MS = 950;
    private static final int CELLULAR_TELEGRAM_DIAL_BUDGET_MS = 1400;
    private static volatile boolean cellularNetwork = false;

    public static void setCellularNetwork(boolean cellular) {
        boolean changed = (cellularNetwork != cellular);
        cellularNetwork = cellular;
        ColgramTelegramWsBridge.setCellularNetwork(cellular);
        ColgramDohResolver.setCellularNetwork(cellular);
        if (changed) {
            ColgramDohResolver.clearConnectionPool();
            synchronized (ColgramDpiBypass.class) {
                currentStrategy = cellular ? cellularPreferredStrategy : wifiPreferredStrategy;
                strategyCycleSteps = 0;
            }
            int savedFrag = cellular ? cellularWssFragHint : wifiWssFragHint;
            if (savedFrag >= 0) {
                int savedSni = cellular ? cellularWssSniHint : wifiWssSniHint;
                ColgramTelegramWsBridge.restoreBypassHint(cellular, savedFrag, savedSni);
            }
        }
    }

    public static boolean isCellularNetwork() {
        return cellularNetwork;
    }

    private static int firstReplyTimeoutMs() {
        return cellularNetwork ? CELLULAR_FIRST_REPLY_TIMEOUT_MS : FIRST_REPLY_TIMEOUT_MS;
    }

    private static int telegramDialBudgetMs() {
        return cellularNetwork ? CELLULAR_TELEGRAM_DIAL_BUDGET_MS : TELEGRAM_DIAL_BUDGET_MS;
    }

    static final int STRATEGY_SPLIT_1                    = 0;  // Fast 1B + SNI mid TCP split (Wi-Fi default, 0ms RTO)
    static final int STRATEGY_NATIVE_DISORDER_TLSREC     = 1;  // Exact ByeDPI -d1 -r1+s
    static final int STRATEGY_NATIVE_FAKE_TTL5_TLSREC    = 2;  // Native sendfile fake RU SNI (max.ru) TTL=5 + 2-rec
    static final int STRATEGY_NATIVE_OOB_DISORDER_TLSREC = 3;  // Fallback mobile -o1 -d1 -r1+s (byte-1 OOB + TTL=1)
    static final int STRATEGY_NATIVE_FAKE_TTL3_TLSREC    = 4;  // Native sendfile fake (vk.com) TTL=3 + 2-rec
    static final int STRATEGY_NATIVE_FAKE_TTL8_TLSREC    = 5;  // Native sendfile fake (gosuslugi.ru) TTL=8 + 2-rec
    static final int STRATEGY_NATIVE_MSS_CLAMP_TLSREC    = 6;  // Dedicated fallback: TCP_MAXSEG=88 + windowClamp=2048 + 2-rec TLS SNI split
    static final int STRATEGY_NATIVE_DISORDER_SNI        = 7;  // Native byte-preserving IP_TTL=1 disorder at SNI mid (-d1+s)
    static final int STRATEGY_TLS_2REC_SNI               = 8;  // Pure RFC-valid 2-record TLS SNI split (carrier-safe)
    static final int STRATEGY_TLS_3REC_SNI               = 9;  // Pure RFC-valid 3-record TLS SNI split (carrier-safe)
    static final int STRATEGY_NATIVE_FAKE_TTL10_DISORDER = 10; // Fallback: fake (yandex.ru) TTL=10 + mid IP_TTL=1 disorder
    static final int STRATEGY_NATIVE_MSS64_OOB1_TLSREC   = 11; // Dedicated fallback: TCP_MAXSEG=88 (clamped >=88) + OOB + -r1+s + wclamp
    static final int STRATEGY_NATIVE_OOB1_ONLY_TLSREC    = 12; // Demoted parallel lane: -o1 -r1+s (never sole cellular default!)
    static final int STRATEGY_NATIVE_MULTI_SPLIT_TLSREC  = 13; // Carrier-safe cellular default: MODE_MULTI_SPLIT_SNI (0 OOB, 0 TTL=1, 0 MSS, 0 wclamp)

    private static final int[] WIFI_LADDER = {
            // Wave 1: carrier-safe zero-OOB lanes (fast 1B split, multi-split+TLSrec, 2-rec TLS SNI)
            STRATEGY_SPLIT_1,
            STRATEGY_NATIVE_MULTI_SPLIT_TLSREC,
            STRATEGY_TLS_2REC_SNI,
            // Wave 2: OOB1, disorder, multi-hop fake TTL=8 (gosuslugi.ru)
            STRATEGY_NATIVE_OOB1_ONLY_TLSREC,
            STRATEGY_NATIVE_DISORDER_TLSREC,
            STRATEGY_NATIVE_FAKE_TTL8_TLSREC,
            // Wave 3: fake TTL=5 (max.ru), MSS=88 fallback, OOB+disorder
            STRATEGY_NATIVE_FAKE_TTL5_TLSREC,
            STRATEGY_NATIVE_MSS_CLAMP_TLSREC,
            STRATEGY_NATIVE_OOB_DISORDER_TLSREC,
            STRATEGY_NATIVE_FAKE_TTL10_DISORDER,
            STRATEGY_TLS_3REC_SNI,
            STRATEGY_NATIVE_DISORDER_SNI,
            STRATEGY_NATIVE_FAKE_TTL3_TLSREC,
            STRATEGY_NATIVE_MSS64_OOB1_TLSREC
    };

    private static final int[] CELLULAR_LADDER = {
            // Wave 1: carrier-safe multi-split+TLSrec FIRST (normal SYN, 0 OOB, 0 TTL=1, 0 wclamp),
            // raced with pure 2-record TLS SNI split and OOB1 only as an opt-in 3rd parallel lane!
            STRATEGY_NATIVE_MULTI_SPLIT_TLSREC,
            STRATEGY_TLS_2REC_SNI,
            STRATEGY_NATIVE_OOB1_ONLY_TLSREC,
            // Wave 2: -d1 -r1+s disorder, multi-hop fake TTL=8 (gosuslugi.ru), fake TTL=5 (max.ru)
            STRATEGY_NATIVE_DISORDER_TLSREC,
            STRATEGY_NATIVE_FAKE_TTL8_TLSREC,
            STRATEGY_NATIVE_FAKE_TTL5_TLSREC,
            // Wave 3: -o1 -d1 -r1+s, fake TTL=10 + disorder (yandex.ru), MSS=88+wclamp fallback
            STRATEGY_NATIVE_OOB_DISORDER_TLSREC,
            STRATEGY_NATIVE_FAKE_TTL10_DISORDER,
            STRATEGY_NATIVE_MSS_CLAMP_TLSREC,
            STRATEGY_TLS_3REC_SNI,
            STRATEGY_NATIVE_FAKE_TTL3_TLSREC,
            STRATEGY_SPLIT_1,
            STRATEGY_NATIVE_DISORDER_SNI,
            STRATEGY_NATIVE_MSS64_OOB1_TLSREC
    };

    private static final int[] JAVA_ONLY_LADDER = {
            STRATEGY_SPLIT_1,
            STRATEGY_NATIVE_MULTI_SPLIT_TLSREC,
            STRATEGY_TLS_2REC_SNI,
            STRATEGY_TLS_3REC_SNI,
            STRATEGY_NATIVE_DISORDER_TLSREC,
            STRATEGY_NATIVE_OOB1_ONLY_TLSREC,
            STRATEGY_NATIVE_OOB_DISORDER_TLSREC
    };

    private static volatile int wifiPreferredStrategy = STRATEGY_SPLIT_1;
    private static volatile int cellularPreferredStrategy = STRATEGY_NATIVE_MULTI_SPLIT_TLSREC;

    private static int defaultStrategyForNetwork(boolean cellular) {
        if (cellular && ColgramNativeDesync.isAvailable()) {
            return STRATEGY_NATIVE_MULTI_SPLIT_TLSREC;
        }
        return STRATEGY_SPLIT_1;
    }

    static int[] activeLadderFor(boolean cellular, boolean nativeAvail) {
        if (!nativeAvail) return JAVA_ONLY_LADDER;
        return cellular ? CELLULAR_LADDER : WIFI_LADDER;
    }

    static int mssClampForStrategy(int strategy) {
        // Linux TCP_MIN_MSS is 88; 64 always fails setsockopt(TCP_MAXSEG) with EINVAL.
        if (strategy == STRATEGY_NATIVE_MSS_CLAMP_TLSREC
                || strategy == STRATEGY_NATIVE_MSS64_OOB1_TLSREC) {
            return 88;
        }
        return 0;
    }

    static int windowClampForStrategy(int strategy) {
        // Pre-SYN TCP_WINDOW_CLAMP forces tiny/zero Window Scale permanently for the TCP session.
        // Keep it strictly on dedicated fallback lanes (MSS-clamped), never on primary chat/media lanes!
        switch (strategy) {
            case STRATEGY_NATIVE_MSS_CLAMP_TLSREC:
            case STRATEGY_NATIVE_MSS64_OOB1_TLSREC:
                return 2048;
            default:
                return 0;
        }
    }

    private static int initialMssForHost(String host) {
        int strat = pickStrategyForHost(baseDomainKey(host));
        return mssClampForStrategy(strat);
    }

    static boolean isHostStrategyConfirmed(String hostKey, boolean cellular) {
        if (hostKey == null || hostKey.isEmpty()) return false;
        HostStrategyState state = hostStrategies.get(scopedHostKey(hostKey, cellular));
        return state != null && state.confirmed;
    }

    private static String scopedHostKey(String hostKey, boolean cellular) {
        if (hostKey == null || hostKey.isEmpty()) return "";
        return (cellular ? "C:" : "W:") + hostKey;
    }

    private static final class HostStrategyState {
        volatile int strategy;
        volatile int consecutiveFails = 0;
        volatile boolean confirmed = false;

        HostStrategyState(int initial) {
            this.strategy = initial;
        }
    }

    private static final int MAX_HOST_STRATEGIES = 256;
    private static final java.util.Map<String, HostStrategyState> hostStrategies =
            new java.util.concurrent.ConcurrentHashMap<>();

    static String baseDomainKey(String sniOrHost) {
        if (sniOrHost == null || sniOrHost.isEmpty()) return "";
        String h = sniOrHost.trim().toLowerCase(java.util.Locale.US);
        if (ColgramDohResolver.isIpLiteral(h)) return h;
        int lastDot = h.lastIndexOf('.');
        if (lastDot <= 0) return h;
        int prevDot = h.lastIndexOf('.', lastDot - 1);
        return prevDot > 0 ? h.substring(prevDot + 1) : h;
    }

    private static final String[] STREAMING_OR_VOICE_DOMAINS = {
            "googlevideo.com", "youtube.com", "ytimg.com", "ggpht.com", "youtu.be",
            "discord.com", "discordapp.com", "discord.media", "discordapp.net",
            "twitch.tv", "ttvnw.net", "instagram.com", "cdninstagram.com", "fbcdn.net"
    };

    private static final String[] DOMESTIC_DIRECT_DOMAINS = {
            "vk.com", "userapi.com", "vk.me",
            "ya.ru", "yandex.net", "yastatic.net",
            "mail.ru", "gosuslugi.ru", "sberbank.ru",
            "ozon.ru", "wildberries.ru", "avito.ru",
            "tbank.ru", "alfabank.ru", "rutube.ru",
            "kinopoisk.ru", "dzen.ru", "2gis.ru", "dns-shop.ru"
    };

    private static String normalizeDomainArg(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(java.util.Locale.US);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static boolean matchesDomainList(String normalizedHost, String[] domains) {
        if (normalizedHost.isEmpty()) return false;
        for (String d : domains) {
            if (normalizedHost.equals(d) || normalizedHost.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    static boolean isStreamingOrVoiceDomain(String hostKey) {
        return matchesDomainList(normalizeDomainArg(hostKey), STREAMING_OR_VOICE_DOMAINS);
    }

    static boolean isDomesticDirectHost(String host) {
        String h = normalizeDomainArg(host);
        if (h.isEmpty() || ColgramDohResolver.isIpLiteral(h)) return false;
        if (h.endsWith(".ru") || h.endsWith(".xn--p1ai") || h.endsWith(".su")) {
            return true;
        }
        return matchesDomainList(h, DOMESTIC_DIRECT_DOMAINS);
    }

    /**
     * Builds an ordered, deduplicated strategy plan (up to {@code maxAttempts}) for in-session
     * transparent replay: starts with the host's pinned strategy for this network type, then the
     * network-wide confirmed winner, then the cellular or Wi-Fi ladder.
     */
    static int[] buildReplayPlan(String hostKey, boolean cellular, boolean nativeAvail, int maxAttempts) {
        int cap = Math.max(1, Math.min(maxAttempts, STRATEGY_COUNT));
        int[] out = new int[cap];
        int count = 0;
        boolean skipMssClamp = isStreamingOrVoiceDomain(hostKey);
        int[] ladder = !nativeAvail ? JAVA_ONLY_LADDER : (cellular ? CELLULAR_LADDER : WIFI_LADDER);

        HostStrategyState hostState = (hostKey != null && !hostKey.isEmpty())
                ? hostStrategies.get(scopedHostKey(hostKey, cellular))
                : null;
        if (hostState != null
                && isStrategyAllowed(hostState.strategy, nativeAvail)
                && (!skipMssClamp || mssClampForStrategy(hostState.strategy) == 0)) {
            out[count++] = hostState.strategy;
        }

        int netPref = cellular ? cellularPreferredStrategy : wifiPreferredStrategy;
        if (count < cap
                && isStrategyAllowed(netPref, nativeAvail)
                && (!skipMssClamp || mssClampForStrategy(netPref) == 0)
                && !containsInt(out, count, netPref)) {
            out[count++] = netPref;
        }

        for (int candidate : ladder) {
            if (count >= cap) break;
            if ((!skipMssClamp || mssClampForStrategy(candidate) == 0)
                    && !containsInt(out, count, candidate)) {
                out[count++] = candidate;
            }
        }
        return count == cap ? out : java.util.Arrays.copyOf(out, count);
    }

    private static boolean isStrategyAllowed(int strat, boolean nativeAvail) {
        if (strat < 0 || strat >= STRATEGY_COUNT) return false;
        if (nativeAvail) return true;
        return strat == STRATEGY_SPLIT_1 || strat == STRATEGY_NATIVE_MULTI_SPLIT_TLSREC
                || strat == STRATEGY_TLS_2REC_SNI || strat == STRATEGY_TLS_3REC_SNI
                || strat == STRATEGY_NATIVE_DISORDER_TLSREC
                || strat == STRATEGY_NATIVE_OOB1_ONLY_TLSREC
                || strat == STRATEGY_NATIVE_OOB_DISORDER_TLSREC;
    }

    private static boolean containsInt(int[] arr, int len, int val) {
        for (int i = 0; i < len; i++) {
            if (arr[i] == val) return true;
        }
        return false;
    }

    private static int pickStrategyForHost(String hostKey) {
        boolean cell = cellularNetwork;
        if (hostKey != null && !hostKey.isEmpty()) {
            HostStrategyState state = hostStrategies.get(scopedHostKey(hostKey, cell));
            if (state != null) return state.strategy;
        }
        return cell && ColgramNativeDesync.isAvailable() ? cellularPreferredStrategy : pickStrategy();
    }

    static void reportHostStrategyResult(String hostKey, int strategy, boolean carried) {
        reportHostStrategyResult(hostKey, strategy, carried, false, -1L);
    }

    static void reportHostStrategyResult(String hostKey, int strategy, boolean carried, boolean promoteGlobal) {
        reportHostStrategyResult(hostKey, strategy, carried, promoteGlobal, -1L);
    }

    static void reportHostStrategyResult(String hostKey, int strategy, boolean carried,
                                         boolean promoteGlobal, long expectedEpoch) {
        if (expectedEpoch >= 0L && expectedEpoch != networkEpoch.get()) {
            return;
        }
        boolean cell = cellularNetwork;
        if (carried && mssClampForStrategy(strategy) == 0
                && (promoteGlobal || (cell && strategy != STRATEGY_SPLIT_1))) {
            persistDesyncStrategyHint(cell, strategy);
        }
        if (hostKey == null || hostKey.isEmpty()) return;
        if (carried && mssClampForStrategy(strategy) > 0 && isStreamingOrVoiceDomain(hostKey)) {
            return;
        }
        String scoped = scopedHostKey(hostKey, cell);
        HostStrategyState state = hostStrategies.get(scoped);
        if (state == null) {
            if (hostStrategies.size() >= MAX_HOST_STRATEGIES) hostStrategies.clear();
            state = hostStrategies.computeIfAbsent(scoped, k -> new HostStrategyState(strategy));
        }
        synchronized (state) {
            if (carried) {
                state.strategy = strategy;
                state.consecutiveFails = 0;
                state.confirmed = true;
            } else if (state.strategy == strategy) {
                state.consecutiveFails++;
                int limit = state.confirmed ? 2 : 1;
                if (state.consecutiveFails >= limit) {
                    int[] ladder = ColgramNativeDesync.isAvailable()
                            ? (cell ? CELLULAR_LADDER : WIFI_LADDER)
                            : JAVA_ONLY_LADDER;
                    int next = nextInLadder(ladder, strategy);
                    state.strategy = next;
                    state.consecutiveFails = 0;
                    state.confirmed = false;
                    safeLogI("host=" + scoped + " strategy " + strategy + " failed -> next=" + next);
                }
            }
        }
    }

    private static int nextInLadder(int[] ladder, int current) {
        for (int i = 0; i < ladder.length; i++) {
            if (ladder[i] == current) return ladder[(i + 1) % ladder.length];
        }
        return ladder[0];
    }

    private static boolean isDesyncReplyTimeoutPort(int port) {
        return port == 443 || port == 80 || port == 853 || port == 8443
                || port == 2053 || port == 2083 || port == 2087 || port == 2096;
    }

    private static volatile int currentStrategy = STRATEGY_SPLIT_1;
    private static final int[] strategyFails = new int[STRATEGY_COUNT];
    private static int strategyCycleSteps = 0;
    /** Consecutive dead connections before we try the next strategy. */
    private static final int FAILS_BEFORE_SWITCH = 1;
    /** Set once any strategy has carried real traffic back from the target. */
    private static volatile boolean strategyFound = false;

    /**
     * When a FULL cycle of strategies produced nothing, throttle new attempts.
     *
     * Without this the prober loops forever: Telegram retries, each retry opens a fresh
     * outbound connection, and if every strategy fails we keep cycling. That is a
     * connection storm against hosts that are not going to answer - and through an
     * emulator's NAT it starves the host machine's networking. Backing off lets Telegram's
     * own retry cadence take over instead of us amplifying it.
     */
    private static final java.util.concurrent.atomic.AtomicLongArray dcBackoffUntil =
            new java.util.concurrent.atomic.AtomicLongArray(MAX_DC_BUCKETS);
    private static final long STRATEGY_BACKOFF_MS = 3000L;
    private static final long MAX_BACKOFF_SLEEP_MS = 300L;

    /**
     * Whether any desync strategy has actually worked yet.
     *
     * ColgramProxyManager uses this to hold off rotating away from the local listener while
     * the strategy search is still running. Without it, the two features fight: onProxyError
     * fires on the first failed connection and the rotator abandons the listener before its
     * prober has tried anything.
     */
    public static boolean hasWorkingStrategy() {
        return strategyFound;
    }

    // ==================================================================================
    // Carriage counters
    // ==================================================================================

    private static final java.util.concurrent.atomic.AtomicLong toTargetBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong fromTargetBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong relayedConnections =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong completedRelays =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * What this listener has actually carried, rather than what it looks like from the outside.
     *
     * <p>Every earlier check of the bypass asked whether the port accepts a connection, which a
     * bound socket answers whether or not anything is ever relayed. That is the gap behind
     * "the bypass is on and Telegram still does not connect": the switch reads true, the socket
     * accepts, and no byte has ever crossed it. These counters are what close the gap, and they are
     * counted per direction so a front that only ever writes upstream cannot pass for one that
     * carries a conversation.
     */
    public static long bytesToTarget() {
        return toTargetBytes.get();
    }

    public static long bytesFromTarget() {
        return fromTargetBytes.get();
    }

    /** Connections the front opened an upstream socket for. */
    public static long relayedConnections() {
        return relayedConnections.get();
    }

    /** Relays that finished, whether the target answered or closed first. */
    public static long completedRelays() {
        return completedRelays.get();
    }

    /** Zero every counter, so a test can measure one run without restarting the listener. */
    public static void resetCarriageCounters() {
        toTargetBytes.set(0);
        fromTargetBytes.set(0);
        relayedConnections.set(0);
        completedRelays.set(0);
    }

    public static void resetRawFailureState() {
        resetDcTrackers();
        silentEndpoints.clear();
        silentEndpointCounts.clear();
        clearConnectionFailureBackoff();
    }

    private static synchronized int pickStrategy() {
        return currentStrategy;
    }

    /**
     * Record how a connection went and advance the strategy if this one keeps dying.
     *
     * `carried` is whether the TARGET ever sent us bytes. That is the honest signal: a
     * desync that the DPI rejects never gets a reply from the destination, so bytes
     * from the target is what separates "connected" from "screamed into the void".
     */
    private static synchronized void reportStrategyResult(int strategy, boolean carried, int dcBucket) {
        reportStrategyResult(strategy, carried, dcBucket, -1L);
    }

    private static synchronized void reportStrategyResult(int strategy, boolean carried,
                                                          int dcBucket, long expectedEpoch) {
        reportStrategyResultInternal(strategy, carried, dcBucket,
                activeLadderFor(cellularNetwork, ColgramNativeDesync.isAvailable()), expectedEpoch);
    }

    static synchronized void reportStrategyResultWithLadderForTest(int strategy, boolean carried,
                                                                   int dcBucket, int[] ladder) {
        reportStrategyResultInternal(strategy, carried, dcBucket, ladder, -1L);
    }

    static synchronized void reportStrategyResultForEpochForTest(int strategy, boolean carried,
                                                                 int dcBucket, int[] ladder, long expectedEpoch) {
        reportStrategyResultInternal(strategy, carried, dcBucket, ladder, expectedEpoch);
    }

    static synchronized int strategyFailCountForTest(int strategy) {
        return (strategy >= 0 && strategy < STRATEGY_COUNT) ? strategyFails[strategy] : -1;
    }

    static int hostStrategyCountForTest() {
        return hostStrategies.size();
    }

    static int l3RawMtprotoSilentStreakForTest() {
        return l3RawMtprotoSilentStreak.get();
    }

    static void setL3RawMtprotoSilentStreakForTest(int streak) {
        l3RawMtprotoSilentStreak.set(Math.max(0, streak));
    }

    static synchronized void resetStrategyStateForTest(boolean cellular, int initialStrategy) {
        cellularNetwork = cellular;
        java.util.Arrays.fill(strategyFails, 0);
        strategyCycleSteps = 0;
        strategyFound = false;
        if (cellular) cellularPreferredStrategy = initialStrategy;
        else wifiPreferredStrategy = initialStrategy;
        currentStrategy = initialStrategy;
        for (int i = 0; i < MAX_DC_BUCKETS; i++) {
            dcBackoffUntil.set(i, 0L);
        }
        hostStrategies.clear();
    }

    static int cellularPreferredStrategyForTest() {
        return cellularPreferredStrategy;
    }

    static long dcBackoffUntilForTest(int dcBucket) {
        int bucket = (dcBucket >= 0 && dcBucket < MAX_DC_BUCKETS) ? dcBucket : 0;
        return dcBackoffUntil.get(bucket);
    }

    private static void safeLogD(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (LinkageError | RuntimeException ignored) {}
    }

    private static void safeLogI(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (LinkageError | RuntimeException ignored) {}
    }

    private static void safeLogW(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (LinkageError | RuntimeException ignored) {}
    }

    private static void safeLogE(String msg, Throwable err) {
        try {
            Log.e(TAG, msg, err);
        } catch (LinkageError | RuntimeException ignored) {}
    }

    private static synchronized void reportStrategyResultInternal(int strategy, boolean carried,
                                                                  int dcBucket, int[] ladder,
                                                                  long expectedEpoch) {
        if (expectedEpoch >= 0L && expectedEpoch != networkEpoch.get()) return;
        if (strategy < 0 || strategy >= STRATEGY_COUNT || ladder == null || ladder.length == 0) return;
        int bucket = (dcBucket >= 0 && dcBucket < MAX_DC_BUCKETS) ? dcBucket : 0;
        if (carried) {
            strategyFails[strategy] = 0;
            strategyCycleSteps = 0;
            strategyFound = true;
            if (bucket > 0) onDirectLayer1Success();
            dcBackoffUntil.set(bucket, 0L);
            persistDesyncStrategyHint(cellularNetwork, strategy);
            if (currentStrategy != strategy) {
                currentStrategy = strategy;
                safeLogI("Desync strategy " + strategy + " works; pinning to it");
            }
            return;
        }
        strategyFails[strategy]++;
        int activePreferred = cellularNetwork ? cellularPreferredStrategy : wifiPreferredStrategy;
        if (strategyFails[strategy] >= FAILS_BEFORE_SWITCH
                && (currentStrategy == strategy || activePreferred == strategy)) {
            int next = nextInLadder(ladder, strategy);
            currentStrategy = next;
            if (cellularNetwork) cellularPreferredStrategy = next;
            else wifiPreferredStrategy = next;
            strategyFails[strategy] = 0;
            strategyCycleSteps++;
            if (strategyCycleSteps >= ladder.length) {
                strategyCycleSteps = 0;
                // Full ladder cycle exhausted, nothing worked for this DC. Back off only this DC bucket
                // so a blocked DC (e.g. DC5) never stalls healthy DC2/DC4 traffic.
                dcBackoffUntil.set(bucket, System.currentTimeMillis() + STRATEGY_BACKOFF_MS);
                safeLogW("Full desync ladder (" + ladder.length + " strategies) exhausted for dcBucket="
                        + bucket + "; backing off " + STRATEGY_BACKOFF_MS + "ms");
            } else {
                safeLogI("Desync strategy " + strategy + " is not getting through; trying " + next
                        + " (" + strategyCycleSteps + "/" + ladder.length + ")");
            }
        }
    }

    /**
     * Parses a TLS 1.2/1.3 ClientHello (RFC 5246/8446) to find the exact byte span [start, end)
     * of the SNI hostname string (extension 0x0000). Works with BoringSSL/Chrome/Android GREASE
     * and post-quantum X25519MLKEM768 keyshares. Returns null if not a TLS ClientHello with SNI.
     */
    static int[] findTlsSniSpan(byte[] buf, int len) {
        return ColgramTlsMimic.findTlsSniSpan(buf, len);
    }

    /** Finds a safe split offset inside an HTTP/1.x Host: header line, or -1 if not HTTP. */
    private static int findHttpHostSplit(byte[] buf, int len) {
        if (buf == null || len < 16) return -1;
        // Quick check for GET / POST / HEAD / PUT / CONNECT / OPTIONS
        byte b0 = buf[0];
        if (b0 != 'G' && b0 != 'P' && b0 != 'H' && b0 != 'C' && b0 != 'O' && b0 != 'D') return -1;
        for (int i = 0; i < len - 8; i++) {
            if (buf[i] == '\n'
                    && (buf[i + 1] == 'H' || buf[i + 1] == 'h')
                    && (buf[i + 2] == 'o' || buf[i + 2] == 'O')
                    && (buf[i + 3] == 's' || buf[i + 3] == 'S')
                    && (buf[i + 4] == 't' || buf[i + 4] == 'T')
                    && buf[i + 5] == ':') {
                return Math.min(len - 1, i + 8);
            }
        }
        return -1;
    }

    /**
     * Writes a TLS ClientHello record split into TWO RFC-valid 0x16 TLS records right across
     * splitIdx (inside the SNI hostname), preserving any trailing pipelined bytes after the record.
     * Optional {@code splitFirstHeaderByte} also splits the very first byte of Record 1 on TCP.
     */
    static boolean writeTlsRecordSplitAt(OutputStream out, byte[] buf, int len, int splitIdx,
                                         boolean splitFirstHeaderByte) throws Exception {
        if (len < 12 || splitIdx <= 6) return false;
        int recLen = ((buf[3] & 0xFF) << 8) | (buf[4] & 0xFF);
        int recEnd = recLen + 5;
        if (recLen < 40 || recEnd > len || splitIdx >= recEnd - 1) return false;
        int firstPayload = splitIdx - 5;
        int secondPayload = recEnd - splitIdx;
        if (firstPayload <= 0 || secondPayload <= 0) return false;

        byte[] hdr1 = new byte[]{buf[0], buf[1], buf[2], (byte) ((firstPayload >>> 8) & 0xFF), (byte) (firstPayload & 0xFF)};
        byte[] hdr2 = new byte[]{buf[0], buf[1], buf[2], (byte) ((secondPayload >>> 8) & 0xFF), (byte) (secondPayload & 0xFF)};

        if (splitFirstHeaderByte) {
            out.write(hdr1, 0, 1);
            out.flush();
            out.write(hdr1, 1, 4);
        } else {
            out.write(hdr1);
        }
        out.write(buf, 5, firstPayload);
        out.flush();
        out.write(hdr2);
        out.write(buf, splitIdx, secondPayload);
        if (recEnd < len) {
            out.write(buf, recEnd, len - recEnd);
        }
        out.flush();
        return true;
    }

    /**
     * Writes a TLS ClientHello record split into THREE RFC-valid 0x16 TLS records across
     * {@code cut1} and {@code cut2} (both inside the SNI hostname).
     */
    static boolean writeTlsRecordSplit3(OutputStream out, byte[] buf, int len, int cut1, int cut2)
            throws Exception {
        if (len < 16 || cut1 <= 6 || cut2 <= cut1) return false;
        int recLen = ((buf[3] & 0xFF) << 8) | (buf[4] & 0xFF);
        int recEnd = recLen + 5;
        if (recLen < 40 || recEnd > len || cut2 >= recEnd - 1) return false;
        int p1 = cut1 - 5;
        int p2 = cut2 - cut1;
        int p3 = recEnd - cut2;
        if (p1 <= 0 || p2 <= 0 || p3 <= 0) return false;

        byte[] h1 = new byte[]{buf[0], buf[1], buf[2], (byte) ((p1 >>> 8) & 0xFF), (byte) (p1 & 0xFF)};
        byte[] h2 = new byte[]{buf[0], buf[1], buf[2], (byte) ((p2 >>> 8) & 0xFF), (byte) (p2 & 0xFF)};
        byte[] h3 = new byte[]{buf[0], buf[1], buf[2], (byte) ((p3 >>> 8) & 0xFF), (byte) (p3 & 0xFF)};

        out.write(h1);
        out.write(buf, 5, p1);
        out.flush();
        out.write(h2);
        out.write(buf, cut1, p2);
        out.flush();
        out.write(h3);
        out.write(buf, cut2, p3);
        if (recEnd < len) {
            out.write(buf, recEnd, len - recEnd);
        }
        out.flush();
        return true;
    }

    /**
     * Send the first client->target packet using the given desync strategy.
     *
     * When {@code dest} is a native ByeDPI socket ({@link ColgramNativeDesync.NativeSocket}),
     * strategies 1..7 and 9/13 invoke real Linux kernel desync inside libcolgramdesync.so:
     *   - IP_TTL=1 / IPV6_UNICAST_HOPS=1 out-of-order TCP disorder (`--disorder`)
     *   - sendfile()+mmap() low-TTL fake whitelisted RU ClientHello decoy (`--fake --ttl 3/5/8`)
     *   - TCP MSG_OOB + disorder + 2-record TLS SNI split (`-o1 -d1 -r1+s`)
     *   - TCP_MAXSEG=88 kernel MSS clamping + TLS record split
     *   - Multi-record SNI split + hybrid 7-segment SNI fragmentation (`MODE_HYBRID_MULTI_RECORD`)
     * When running on desktop JVM or without native library, falls back cleanly to RFC-valid
     * 2/3-record TLS SNI splits and TCP SNI segmentation with 0ms inter-segment stall.
     */
    static void sendDesynced(Socket dest, OutputStream out, byte[] buf, int len, int strategy)
            throws Exception {
        if (dest instanceof ColgramNativeDesync.NativeSocket && len > 2) {
            ColgramNativeDesync.NativeSocket ns = (ColgramNativeDesync.NativeSocket) dest;
            boolean cell = cellularNetwork;
            switch (strategy) {
                case STRATEGY_NATIVE_DISORDER_TLSREC:
                    // Exact ByeDPI -d1 -r1+s (IP_TTL=1 on byte 0 + SNI+1 2-record split)
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_DISORDER_BYTE1, 1, true, true, cell, 0);
                    return;
                case STRATEGY_NATIVE_FAKE_TTL5_TLSREC:
                    // LTE-safe sendfile fake max.ru TTL=5 + 2-record SNI split
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_FAKE_TTL, 5, true, false, cell, 0);
                    return;
                case STRATEGY_NATIVE_OOB_DISORDER_TLSREC:
                    // Exact #1 RU mobile ByeDPI -o1 -d1 -r1+s (byte-1 MSG_OOB + IP_TTL=1 + SNI+1 2-record split)
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_OOB_BYTE1_DISORDER, 1, true, true, cell, 1);
                    return;
                case STRATEGY_NATIVE_FAKE_TTL3_TLSREC:
                    // LTE-safe sendfile fake vk.com TTL=3 + 2-record SNI split
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_FAKE_TTL, 3, true, false, cell, 1);
                    return;
                case STRATEGY_NATIVE_FAKE_TTL8_TLSREC:
                    // LTE-safe sendfile fake gosuslugi.ru (TTL=8 for multi-hop TSPU) + 2-record SNI split
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_FAKE_TTL, 8, true, false, cell, 3);
                    return;
                case STRATEGY_NATIVE_MSS_CLAMP_TLSREC:
                    // TCP_MAXSEG=88 + 2-record SNI split
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_MSS_SPLIT, 0, true, false, cell, 0);
                    return;
                case STRATEGY_NATIVE_DISORDER_SNI:
                    // Byte-preserving IP_TTL=1 disorder at SNI midpoint
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_DISORDER, 1, false, false, cell, 0);
                    return;
                case STRATEGY_TLS_3REC_SNI:
                    // Hybrid 3-record TLS + 7-segment SNI fragmentation (zero OOB, zero TTL=1)
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_HYBRID_MULTI_RECORD, 0, true, true, cell, 0);
                    return;
                case STRATEGY_NATIVE_FAKE_TTL10_DISORDER:
                    // Fallback: fake yandex.ru (TTL=10 for deep carrier TSPU) + middle-slice IP_TTL=1 disorder
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_FAKE_TTL_DISORDER, 10, true, false, cell, 2);
                    return;
                case STRATEGY_NATIVE_MSS64_OOB1_TLSREC:
                    // TCP_MAXSEG=88 (clamped >=88) + byte-1 MSG_OOB + SNI+1 2-record split
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_MSS_OOB_BYTE1, 0, true, true, cell, 4);
                    return;
                case STRATEGY_NATIVE_OOB1_ONLY_TLSREC:
                    // Demoted parallel lane: ByeDPI -o1 -r1+s (byte-1 MSG_OOB + SNI+1 2-record split, normal SYN)
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_OOB_BYTE1_ONLY, 0, true, true, cell, 0);
                    return;
                case STRATEGY_NATIVE_MULTI_SPLIT_TLSREC:
                    // Carrier-safe cellular default: multi-split across SNI + 2-rec TLS split (zero OOB, zero TTL=1, normal SYN)
                    ns.sendDesynced(buf, 0, len, ColgramNativeDesync.MODE_MULTI_SPLIT_SNI, 0, true, true, cell, 0);
                    return;
                default:
                    break;
            }
        }

        boolean isTls = len > 5 && buf[0] == 0x16 && buf[1] == 0x03;
        int[] sni = isTls ? findTlsSniSpan(buf, len) : null;
        int httpSplit = (!isTls) ? findHttpHostSplit(buf, len) : -1;
        java.util.Random rnd = new java.util.Random();

        if (sni != null) {
            int sniStart = sni[0];
            int sniEnd = sni[1];
            int sniMid = ColgramTlsMimic.findSldMidpoint(buf, sniStart, sniEnd);
            switch (strategy) {
                case STRATEGY_SPLIT_1: {
                    // 1-byte TCP split + SNI middle split (100% byte-preserving; instant on Wi-Fi)
                    out.write(buf, 0, 1);
                    out.flush();
                    out.write(buf, 1, sniMid - 1);
                    out.flush();
                    out.write(buf, sniMid, len - sniMid);
                    out.flush();
                    return;
                }
                case STRATEGY_NATIVE_MULTI_SPLIT_TLSREC: {
                    // Carrier-safe 1B + 2-record TLS SNI split (zero MSG_OOB, zero TTL=1)
                    if (writeTlsRecordSplitAt(out, buf, len, sniMid, true)) return;
                    int cut1 = Math.min(sniStart + 2, sniMid - 1);
                    out.write(buf, 0, 1);
                    out.flush();
                    if (cut1 > 1) {
                        out.write(buf, 1, cut1 - 1);
                        out.flush();
                        out.write(buf, cut1, sniMid - cut1);
                    } else {
                        out.write(buf, 1, sniMid - 1);
                    }
                    out.flush();
                    out.write(buf, sniMid, len - sniMid);
                    out.flush();
                    return;
                }
                case STRATEGY_NATIVE_DISORDER_TLSREC:
                case STRATEGY_TLS_2REC_SNI:
                case STRATEGY_NATIVE_MSS_CLAMP_TLSREC: {
                    // RFC-valid 2-record TLS fragmentation directly across SNI midpoint
                    if (writeTlsRecordSplitAt(out, buf, len, sniMid, false)) return;
                    out.write(buf, 0, sniMid);
                    out.flush();
                    out.write(buf, sniMid, len - sniMid);
                    out.flush();
                    return;
                }
                case STRATEGY_NATIVE_FAKE_TTL5_TLSREC:
                case STRATEGY_NATIVE_FAKE_TTL3_TLSREC:
                case STRATEGY_NATIVE_FAKE_TTL10_DISORDER: {
                    // 1-byte TCP split of Record 1 + RFC-valid 2-record TLS split across SNI
                    if (writeTlsRecordSplitAt(out, buf, len, sniMid, true)) return;
                    out.write(buf, 0, 1);
                    out.flush();
                    out.write(buf, 1, sniMid - 1);
                    out.flush();
                    out.write(buf, sniMid, len - sniMid);
                    out.flush();
                    return;
                }
                case STRATEGY_TLS_3REC_SNI:
                case STRATEGY_NATIVE_FAKE_TTL8_TLSREC: {
                    // RFC-valid 3-record TLS fragmentation across SNI+2 and SNI midpoint
                    int cut1 = Math.min(sniStart + 2, sniMid - 1);
                    if (writeTlsRecordSplit3(out, buf, len, cut1, sniMid)) return;
                    if (writeTlsRecordSplitAt(out, buf, len, sniMid, false)) return;
                    out.write(buf, 0, sniMid);
                    out.flush();
                    out.write(buf, sniMid, len - sniMid);
                    out.flush();
                    return;
                }
                case STRATEGY_NATIVE_DISORDER_SNI: {
                    // 4-segment byte-preserving TCP SNI shatter
                    int cut1 = Math.min(sniStart + 1, sniMid - 1);
                    out.write(buf, 0, 1);
                    out.flush();
                    if (cut1 > 1) {
                        out.write(buf, 1, cut1 - 1);
                        out.flush();
                        out.write(buf, cut1, sniMid - cut1);
                    } else {
                        out.write(buf, 1, sniMid - 1);
                    }
                    out.flush();
                    out.write(buf, sniMid, len - sniMid);
                    out.flush();
                    return;
                }
                case STRATEGY_NATIVE_OOB_DISORDER_TLSREC:
                case STRATEGY_NATIVE_OOB1_ONLY_TLSREC:
                case STRATEGY_NATIVE_MSS64_OOB1_TLSREC:
                default: {
                    // Java MSG_OOB (sendUrgentData) inside SNI + SNI split fallback
                    int oobAt = Math.min(sniStart + 1, sniMid);
                    out.write(buf, 0, oobAt);
                    out.flush();
                    if (dest != null) {
                        try {
                            dest.sendUrgentData('a');
                        } catch (Throwable ignored) {}
                    }
                    out.write(buf, oobAt, len - oobAt);
                    out.flush();
                    return;
                }
            }
        }

        if (httpSplit > 1 && httpSplit < len) {
            byte[] mangled = ColgramTlsMimic.mangleHttpHostHeader(buf, len);
            out.write(mangled, 0, httpSplit);
            out.flush();
            out.write(mangled, httpSplit, len - httpSplit);
            out.flush();
            return;
        }

        switch (strategy) {
            case STRATEGY_NATIVE_MULTI_SPLIT_TLSREC: {
                int cut2 = Math.min(len - 1, Math.max(2, 16));
                out.write(buf, 0, 1);
                out.flush();
                if (cut2 > 1 && cut2 < len) {
                    out.write(buf, 1, cut2 - 1);
                    out.flush();
                    out.write(buf, cut2, len - cut2);
                } else {
                    out.write(buf, 1, len - 1);
                }
                out.flush();
                break;
            }
            case STRATEGY_NATIVE_DISORDER_TLSREC:
            case STRATEGY_TLS_2REC_SNI: {
                int cut = 1 + rnd.nextInt(Math.max(1, Math.min(len - 1, 64)));
                out.write(buf, 0, cut);
                out.flush();
                out.write(buf, cut, len - cut);
                out.flush();
                break;
            }
            case STRATEGY_NATIVE_FAKE_TTL5_TLSREC:
            case STRATEGY_NATIVE_FAKE_TTL3_TLSREC: {
                int a = 1 + rnd.nextInt(Math.max(1, Math.min(len - 2, 32)));
                int b = a + 1 + rnd.nextInt(Math.max(1, len - a - 1));
                out.write(buf, 0, a);
                out.flush();
                out.write(buf, a, b - a);
                out.flush();
                out.write(buf, b, len - b);
                out.flush();
                break;
            }
            case STRATEGY_NATIVE_OOB_DISORDER_TLSREC:
            case STRATEGY_NATIVE_OOB1_ONLY_TLSREC:
            case STRATEGY_TLS_3REC_SNI: {
                if (isTls && len > 5) {
                    out.write(buf, 0, 5);
                    out.flush();
                    int mid = 5 + Math.min(20, len - 5);
                    out.write(buf, 5, mid - 5);
                    out.flush();
                    out.write(buf, mid, len - mid);
                    out.flush();
                } else {
                    out.write(buf, 0, 1);
                    out.flush();
                    out.write(buf, 1, len - 1);
                    out.flush();
                }
                break;
            }
            case STRATEGY_NATIVE_DISORDER_SNI: {
                out.write(buf, 0, 1);
                out.flush();
                if (len > 1) {
                    out.write(buf, 1, 1);
                    out.flush();
                }
                if (len > 2) {
                    out.write(buf, 2, len - 2);
                    out.flush();
                }
                break;
            }
            case STRATEGY_NATIVE_FAKE_TTL8_TLSREC:
            case STRATEGY_NATIVE_MSS_CLAMP_TLSREC: {
                int cut = 1 + rnd.nextInt(Math.max(1, Math.min(len - 1, 16)));
                out.write(buf, 0, cut);
                out.flush();
                out.write(buf, cut, len - cut);
                out.flush();
                break;
            }
            case STRATEGY_SPLIT_1:
            default: {
                out.write(buf, 0, 1);
                out.flush();
                out.write(buf, 1, len - 1);
                out.flush();
                break;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pipe client <-> target, desyncing the first packet of the client->target direction.
     *
     * Only the first packet is desynced. Everything after the handshake is ordinary
     * stream traffic that a DPI has already stopped caring about, and mangling it would
     * cost throughput for no benefit.
     *
     * TLS records are fragmented without modifying a single byte. Rewriting a real ClientHello
     * here invalidates the client's TLS transcript; rewriting FakeTLS also invalidates its HMAC.
     */
    private static void pipeOverWebSocketAfterInit(final Socket client,
                                                   final ColgramTelegramWsBridge.WsSession session,
                                                   final ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter,
                                                   final CallListener call, final long generation) {
        tuneSocketBuffers(client);
        final java.util.concurrent.atomic.AtomicLong fromTarget =
                new java.util.concurrent.atomic.AtomicLong(0);
        final ActiveSession tracked = registerActiveSession(client, session, true);
        tracked.onClientTx(safeElapsedRealtime());
        if (!executeWorker(() -> {
            try {
                InputStream cin = client.getInputStream();
                byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
                int len;
                while ((len = cin.read(buffer)) != -1) {
                    splitter.feedToSession(buffer, 0, len, session);
                    tracked.onClientTx(safeElapsedRealtime());
                    if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                }
            } catch (Throwable ignored) {
            } finally {
                unregisterActiveSession(tracked);
                closeQuietly(client);
                closeQuietly(session);
            }
        })) {
            unregisterActiveSession(tracked);
            closeQuietly(client);
            closeQuietly(session);
            if (call != null) call.done(client, null);
            return;
        }
        if (!executeWorker(() -> {
            try {
                OutputStream cout = client.getOutputStream();
                ColgramTelegramWsBridge.pumpUpstreamFrames(session, cout, count -> {
                    tracked.onUpstreamRx(safeElapsedRealtime());
                    boolean first = fromTarget.getAndAdd(count) == 0L;
                    if (first) {
                        onDirectLayer1Success();
                        int bucket = dcBucketForSignedDc(session.rawDcId != 0 ? session.rawDcId : session.dcId);
                        dcWsPreferred.set(bucket, 1);
                        dcBackoffUntil.set(bucket, 0L);
                        Log.i(TAG, "WS_REPLY_OK dc=" + session.dcId + " rawDc=" + session.rawDcId
                                + " edge=" + session.edgeIp
                                + " sni=" + session.sniHost + " bytes=" + count + " (sysdpi-peek)");
                    }
                });
            } catch (Throwable ignored) {
            } finally {
                unregisterActiveSession(tracked);
                closeQuietly(client);
                closeQuietly(session);
                if (call != null) call.done(client, null);
            }
        })) {
            unregisterActiveSession(tracked);
            closeQuietly(client);
            closeQuietly(session);
            if (call != null) call.done(client, null);
        }
    }

    private static void pipeOverWebSocket(final Socket client, final ColgramTelegramWsBridge.WsSession session,
                                          final CallListener call, final long generation) {
        pipeOverWebSocket(client, session, call, generation, false);
    }

    private static void pipeOverWebSocket(final Socket client, final ColgramTelegramWsBridge.WsSession session,
                                          final CallListener call, final long generation,
                                          final boolean viaL3Relay) {
        tuneSocketBuffers(client);
        final int strategy = pickStrategy();
        final java.util.concurrent.atomic.AtomicLong fromTarget =
                new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicBoolean clientSentInit =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean clientClosedFirst =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final ActiveSession tracked = registerActiveSession(client, session, true);

        if (!executeWorker(() -> {
            try {
                client.setSoTimeout(30_000);
                InputStream cin = client.getInputStream();
                byte[] initBuf = new byte[4096];
                int initLen = 0;
                while (initLen < 64) {
                    int n = cin.read(initBuf, initLen, initBuf.length - initLen);
                    if (n < 0) break;
                    initLen += n;
                }
                client.setSoTimeout(0);
                if (initLen < 64) {
                    clientClosedFirst.set(true);
                }
                ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter =
                        ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(
                                initBuf, initLen, session.rawDcId != 0 ? session.rawDcId : session.dcId);
                if (splitter == null) {
                    clientClosedFirst.set(true);
                    Log.w(TAG, "WS_C2S_BAD_HDR dc=" + session.dcId + " initLen=" + initLen);
                    return;
                }
                Log.d(TAG, "WS_C2S_INIT dc=" + session.dcId + " initLen=" + initLen);
                splitter.feedInitAndTailToSession(initBuf, initLen, session);
                tracked.onClientTx(safeElapsedRealtime());
                clientSentInit.set(true);
                if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(initLen);

                byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
                int len;
                while ((len = cin.read(buffer)) != -1) {
                    splitter.feedToSession(buffer, 0, len, session);
                    tracked.onClientTx(safeElapsedRealtime());
                    if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                }
                clientClosedFirst.set(true);
                Log.d(TAG, "WS_C2S_EOF dc=" + session.dcId);
            } catch (Throwable err) {
                Log.d(TAG, "WS_C2S_END dc=" + session.dcId + " err=" + err.getClass().getSimpleName() + ": " + err.getMessage());
            } finally {
                unregisterActiveSession(tracked);
                closeQuietly(client);
                closeQuietly(session);
            }
        })) {
            unregisterActiveSession(tracked);
            closeQuietly(client);
            closeQuietly(session);
            if (call != null) call.done(client, null);
            return;
        }

        if (!executeWorker(() -> {
            try {
                OutputStream cout = client.getOutputStream();
                long totalS2C = ColgramTelegramWsBridge.pumpUpstreamFrames(session, cout, count -> {
                    long rxNow = safeElapsedRealtime();
                    tracked.onUpstreamRx(rxNow);
                    boolean first = fromTarget.getAndAdd(count) == 0L;
                    if (viaL3Relay) {
                        lastL3RelaySuccessAt = rxNow;
                        if (first) {
                            if (call == null) publishUpstream(generation, UPSTREAM_REPLY);
                            Log.i(TAG, "L3_WSS_REPLY_OK dc=" + session.dcId + " relay=" + session.edgeIp
                                    + " host=" + session.sniHost + " bytes=" + count);
                        }
                    } else if (first) {
                        onDirectLayer1Success();
                        if (call == null) publishUpstream(generation, UPSTREAM_REPLY);
                        int bucket = dcBucketForSignedDc(session.rawDcId != 0 ? session.rawDcId : session.dcId);
                        dcWsPreferred.set(bucket, 1);
                        dcBackoffUntil.set(bucket, 0L);
                        reportStrategyResult(strategy, true, bucket);
                        Log.i(TAG, "WS_REPLY_OK dc=" + session.dcId + " rawDc=" + session.rawDcId
                                + " edge=" + session.edgeIp
                                + " sni=" + session.sniHost + " bytes=" + count
                                + (call != null ? " (sysdpi)" : ""));
                    }
                    if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(count);
                });
                if (!viaL3Relay && totalS2C == 0L && clientSentInit.get() && !clientClosedFirst.get()) {
                    ColgramTelegramWsBridge.deprioritizeEndpoint(session);
                }
                Log.d(TAG, "WS_S2C_EOF dc=" + session.dcId + " total=" + totalS2C);
            } catch (Throwable err) {
                Log.d(TAG, "WS_S2C_END dc=" + session.dcId + " err=" + err.getClass().getSimpleName() + ": " + err.getMessage());
            } finally {
                unregisterActiveSession(tracked);
                closeQuietly(client);
                closeQuietly(session);
                if (call != null) call.done(client, null);
                if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
                int bucket = dcBucketForSignedDc(session.rawDcId != 0 ? session.rawDcId : session.dcId);
                if (!viaL3Relay && call == null && isCurrentListener(generation) && fromTarget.get() > 0) {
                    reportStrategyResult(strategy, true, bucket);
                }
            }
        })) {
            unregisterActiveSession(tracked);
            closeQuietly(client);
            closeQuietly(session);
            if (call != null) call.done(client, null);
        }
    }

    interface ReplayDialer {
        Socket dial(int mssClamp);
    }

    interface ParallelReplayDialer {
        Socket dial(int mssClamp, int windowClamp);
    }

    static final class ReplayOutcome {
        final Socket winningUpstream;
        final int winningStrategy;
        final int attempts;
        final byte[] firstReply;
        final int firstReplyLen;

        ReplayOutcome(Socket winningUpstream, int winningStrategy, int attempts,
                      byte[] firstReply, int firstReplyLen) {
            this.winningUpstream = winningUpstream;
            this.winningStrategy = winningStrategy;
            this.attempts = attempts;
            this.firstReply = firstReply;
            this.firstReplyLen = firstReplyLen;
        }
    }

    /**
     * Staggered 3-Way Parallel Wave Racing for ByeDPI strategy search:
     *
     *   - When {@code hostConfirmed == true}: fast-paths ONLY {@code plan[0]} on a single socket
     *     (zero wasted connections for known-good domains). If that pinned strategy fails, drops
     *     straight into staggered parallel waves across {@code plan[1..end]}.
     *   - Cold / hostile mobile networks: launches up to 3 orthogonal kernel strategies per wave
     *     with {@code 0ms / 100ms / 220ms} stagger. Fast Wi-Fi answers before Lane 1 even dials;
     *     hostile 4G/LTE TSPU blackholes get hit by 9 distinct kernel strategies across 3 waves
     *     in ~7 seconds, winning as soon as ANY lane receives a valid ServerHello/HTTP reply.
     *   - Immediately unclamps {@code TCP_WINDOW_CLAMP} on the winning {@link ColgramNativeDesync.NativeSocket}
     *     so post-handshake video/media streaming runs at full 4G/Wi-Fi speed.
     */
    static ReplayOutcome executeParallelReplayPlan(Socket initialDest, int initialMss, int initialWclamp,
                                                   byte[] firstPayload, boolean looksTls,
                                                   int[] plan, ParallelReplayDialer dialer,
                                                   CallListener call, int waveTimeoutMs,
                                                   long totalBudgetMs, boolean hostConfirmed) {
        if (plan == null || plan.length == 0) {
            if (initialDest != null) {
                if (call != null) call.untrackUpstream(initialDest);
                closeQuietly(initialDest);
            }
            return new ReplayOutcome(null, STRATEGY_SPLIT_1, 0, null, 0);
        }

        final long deadline = System.currentTimeMillis() + totalBudgetMs;
        Socket reusableInitial = initialDest;
        int startIdx = 0;
        final java.util.concurrent.atomic.AtomicInteger totalAttempts =
                new java.util.concurrent.atomic.AtomicInteger(0);

        // 1. Confirmed host fast-path: single socket, zero parallel fan-out when pinned strategy still works
        if (hostConfirmed) {
            int pinnedStrat = plan[0];
            int wantMss = mssClampForStrategy(pinnedStrat);
            int wantWc = windowClampForStrategy(pinnedStrat);
            Socket candidate = null;
            if (reusableInitial != null && initialMss == wantMss && initialWclamp == wantWc) {
                candidate = reusableInitial;
                reusableInitial = null;
            } else {
                if (reusableInitial != null) {
                    if (call != null) call.untrackUpstream(reusableInitial);
                    closeQuietly(reusableInitial);
                    reusableInitial = null;
                }
                if (dialer != null) candidate = dialer.dial(wantMss, wantWc);
            }
            startIdx = 1;
            if (candidate != null) {
                totalAttempts.incrementAndGet();
                byte[] firstBuf = new byte[16384];
                try {
                    sendDesynced(candidate, candidate.getOutputStream(), firstPayload, firstPayload.length, pinnedStrat);
                    long left = Math.max(500L, deadline - System.currentTimeMillis());
                    int readMs = (int) Math.min(left, waveTimeoutMs);
                    int n = (candidate instanceof ColgramNativeDesync.NativeSocket)
                            ? ((ColgramNativeDesync.NativeSocket) candidate).readWithTimeout(firstBuf, 0, firstBuf.length, readMs)
                            : ColgramFirstResponseReader.readFirst(candidate, firstBuf, readMs);
                    boolean rejected = (n <= 0)
                            || (looksTls && ColgramTlsMimic.looksLikeFatalTlsAlert(firstBuf, n))
                            || (!looksTls && ColgramTlsMimic.looksLikeIspBlockRedirect(firstBuf, n));
                    if (!rejected) {
                        if (candidate instanceof ColgramNativeDesync.NativeSocket) {
                            ((ColgramNativeDesync.NativeSocket) candidate).unclampWindow();
                        }
                        return new ReplayOutcome(candidate, pinnedStrat, 1, firstBuf, n);
                    }
                } catch (Throwable ignored) {
                }
                if (call != null) call.untrackUpstream(candidate);
                closeQuietly(candidate);
            }
        }

        // 2. Staggered 3-way parallel waves across remaining strategies
        final long[] staggerMs = {0L, 80L, 180L};
        for (int waveStart = startIdx; waveStart < plan.length; waveStart += 3) {
            long now = System.currentTimeMillis();
            if (now + 700L >= deadline) break;
            final int waveSize = Math.min(3, plan.length - waveStart);
            final long waveBudget = Math.min(waveTimeoutMs, Math.max(700L, deadline - now));
            final java.util.concurrent.atomic.AtomicReference<ReplayOutcome> winner =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicBoolean waveDone =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger pending =
                    new java.util.concurrent.atomic.AtomicInteger(waveSize);
            final java.util.List<Socket> activeSockets =
                    java.util.Collections.synchronizedList(new java.util.ArrayList<Socket>(waveSize));

            // Hand off reusableInitial to lane 0 of the first wave ONLY if its socket opts match
            Socket lane0Initial = null;
            if (reusableInitial != null) {
                int s0 = plan[waveStart];
                if (initialMss == mssClampForStrategy(s0) && initialWclamp == windowClampForStrategy(s0)) {
                    lane0Initial = reusableInitial;
                    reusableInitial = null;
                } else {
                    if (call != null) call.untrackUpstream(reusableInitial);
                    closeQuietly(reusableInitial);
                    reusableInitial = null;
                }
            }

            for (int lane = 0; lane < waveSize; lane++) {
                final int strat = plan[waveStart + lane];
                final long delay = staggerMs[lane];
                final Socket preConnected = (lane == 0) ? lane0Initial : null;
                if (!executeWorker(() -> {
                    Socket sock = preConnected;
                    boolean won = false;
                    try {
                        if (delay > 0L) Thread.sleep(delay);
                        if (waveDone.get()) return;
                        if (sock == null && dialer != null) {
                            sock = dialer.dial(mssClampForStrategy(strat), windowClampForStrategy(strat));
                        }
                        if (sock == null || waveDone.get()) return;
                        activeSockets.add(sock);
                        int attemptNum = totalAttempts.incrementAndGet();
                        byte[] buf = new byte[16384];
                        sendDesynced(sock, sock.getOutputStream(), firstPayload, firstPayload.length, strat);
                        if (waveDone.get()) return;
                        long left = Math.max(400L, deadline - System.currentTimeMillis());
                        int readMs = (int) Math.min(left, waveBudget);
                        int n = (sock instanceof ColgramNativeDesync.NativeSocket)
                                ? ((ColgramNativeDesync.NativeSocket) sock).readWithTimeout(buf, 0, buf.length, readMs)
                                : ColgramFirstResponseReader.readFirst(sock, buf, readMs);
                        boolean rejected = (n <= 0)
                                || (looksTls && ColgramTlsMimic.looksLikeFatalTlsAlert(buf, n))
                                || (!looksTls && ColgramTlsMimic.looksLikeIspBlockRedirect(buf, n));
                        if (!rejected && !waveDone.get()) {
                            ReplayOutcome res = new ReplayOutcome(sock, strat, attemptNum, buf, n);
                            if (winner.compareAndSet(null, res)) {
                                won = true;
                                waveDone.set(true);
                                if (sock instanceof ColgramNativeDesync.NativeSocket) {
                                    ((ColgramNativeDesync.NativeSocket) sock).unclampWindow();
                                }
                                latch.countDown();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (!won && sock != null) {
                            activeSockets.remove(sock);
                            if (call != null) call.untrackUpstream(sock);
                            closeQuietly(sock);
                        }
                        if (pending.decrementAndGet() == 0) {
                            waveDone.set(true);
                            latch.countDown();
                        }
                    }
                })) {
                    if (preConnected != null) {
                        if (call != null) call.untrackUpstream(preConnected);
                        closeQuietly(preConnected);
                    }
                    if (pending.decrementAndGet() == 0) {
                        waveDone.set(true);
                        latch.countDown();
                    }
                }
            }

            try {
                latch.await(waveBudget + 150L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            waveDone.set(true);

            ReplayOutcome win = winner.get();
            Socket winSock = win != null ? win.winningUpstream : null;
            synchronized (activeSockets) {
                for (Socket s : activeSockets) {
                    if (s != null && s != winSock) {
                        if (call != null) call.untrackUpstream(s);
                        closeQuietly(s);
                    }
                }
                activeSockets.clear();
            }
            if (win != null) {
                if (reusableInitial != null) {
                    if (call != null) call.untrackUpstream(reusableInitial);
                    closeQuietly(reusableInitial);
                }
                return win;
            }
        }

        if (reusableInitial != null) {
            if (call != null) call.untrackUpstream(reusableInitial);
            closeQuietly(reusableInitial);
        }
        return new ReplayOutcome(null, plan[0], totalAttempts.get(), null, 0);
    }

    /**
     * In-session transparent ByeDPI strategy replay: tries each strategy in {@code plan} against
     * fresh upstream sockets using the exact buffered {@code firstPayload} (ClientHello / HTTP
     * request) while keeping the client app's socket open, failing over automatically on TCP RST,
     * EOF, silent timeout, fatal TLS alert, or Russian ISP block-page HTTP redirect.
     */
    static ReplayOutcome executeReplayPlan(Socket initialDest, boolean initialHasMss,
                                           byte[] firstPayload, boolean looksTls,
                                           int[] plan, ReplayDialer dialer,
                                           CallListener call, int retryReadTimeoutMs,
                                           int finalReadTimeoutMs, long totalBudgetMs) {
        final long deadline = System.currentTimeMillis() + totalBudgetMs;
        Socket candidate = initialDest;
        byte[] firstBuf = new byte[16384];
        int attempts = 0;

        for (int i = 0; i < plan.length; i++) {
            int strat = plan[i];
            int wantMss = mssClampForStrategy(strat);
            boolean last = (i == plan.length - 1);

            if (i > 0) {
                if (System.currentTimeMillis() + 900L >= deadline) break;
                candidate = dialer != null ? dialer.dial(wantMss) : null;
                if (candidate == null) continue;
            } else if (wantMss > 0 && !initialHasMss && dialer != null) {
                Socket clamped = dialer.dial(wantMss);
                if (clamped != null) {
                    if (call != null) call.untrackUpstream(candidate);
                    closeQuietly(candidate);
                    candidate = clamped;
                }
            }
            if (candidate == null) continue;
            attempts++;

            try {
                sendDesynced(candidate, candidate.getOutputStream(), firstPayload, firstPayload.length, strat);
                long leftMs = Math.max(500L, deadline - System.currentTimeMillis());
                int readTimeout = (int) Math.min(leftMs, last ? finalReadTimeoutMs : retryReadTimeoutMs);
                int n;
                if (candidate instanceof ColgramNativeDesync.NativeSocket) {
                    n = ((ColgramNativeDesync.NativeSocket) candidate).readWithTimeout(firstBuf, 0, firstBuf.length, readTimeout);
                } else {
                    n = ColgramFirstResponseReader.readFirst(candidate, firstBuf, readTimeout);
                }
                boolean rejected = (n <= 0)
                        || (looksTls && ColgramTlsMimic.looksLikeFatalTlsAlert(firstBuf, n))
                        || (!looksTls && ColgramTlsMimic.looksLikeIspBlockRedirect(firstBuf, n));
                if (!rejected) {
                    if (candidate instanceof ColgramNativeDesync.NativeSocket) {
                        ((ColgramNativeDesync.NativeSocket) candidate).unclampWindow();
                    }
                    return new ReplayOutcome(candidate, strat, attempts, firstBuf, n);
                }
            } catch (Throwable ignored) {
                // RST / timeout / EOF -> immediately replay on fresh upstream with next strategy
            }

            if (call != null) call.untrackUpstream(candidate);
            closeQuietly(candidate);
            candidate = null;
        }
        if (candidate != null) {
            if (call != null) call.untrackUpstream(candidate);
            closeQuietly(candidate);
        }
        return new ReplayOutcome(null, plan.length > 0 ? plan[0] : STRATEGY_SPLIT_1, attempts, null, 0);
    }

    private static void pipeWithAdvancedDesync(final Socket client, final Socket dest, final CallListener call,
                                               final long generation, final boolean telegram,
                                               final String destHost, final int destPort,
                                               final boolean isMedia, final long startEpoch) {
        // CRITICAL: when call != null, CallListener explicitly called VpnService.protect(dest),
        // bypassing our own TUN straight onto the raw carrier/ISP network where TSPU sits.
        // overlayActive MUST be false whenever call != null, otherwise whole-device local DPI
        // bypass skips ALL TLS SNI / HTTP desync!
        final boolean overlayActive = (call == null) && isOverlayVpnActive();

        if (!executeWorker(() -> {
            byte[] firstPayload;
            try {
                // Read full TLS ClientHello (including ~1800-2100B Chrome/YouTube/Discord Post-Quantum X25519MLKEM768!)
                firstPayload = ColgramInitialPacketReader.readInitialPacket(client, 2048, 8192, 80, 220);
            } catch (Throwable err) {
                closeQuietly(dest);
                closeQuietly(client);
                if (call != null) call.done(client, dest);
                return;
            }
            boolean looksTls = ColgramTlsMimic.looksLikeClientHello(firstPayload);
            boolean looksHttp = !looksTls && findHttpHostSplit(firstPayload, firstPayload.length) > 0;
            String sniHost = looksTls ? ColgramTlsMimic.extractSniHostname(firstPayload, firstPayload.length) : null;
            String hostKey = (sniHost != null && !sniHost.isEmpty()) ? baseDomainKey(sniHost) : baseDomainKey(destHost);

            if (call != null && call.systemWide
                    && isDomesticDirectHost(sniHost != null ? sniHost : destHost)) {
                pipeDomesticDirectSession(client, dest, call, generation, firstPayload);
                return;
            }

            if (!overlayActive && firstPayload.length > 1 && (looksTls || looksHttp)) {
                runReplayableTlsOrHttpSession(client, dest, call, generation, destHost, destPort,
                        firstPayload, looksTls, sniHost, hostKey, startEpoch);
                return;
            }
            pipeStandardOrWsSession(client, dest, call, generation, telegram, destHost, destPort,
                    overlayActive, firstPayload, looksTls, hostKey, isMedia, startEpoch);
        })) {
            closeQuietly(dest);
            closeQuietly(client);
            if (call != null) call.done(client, dest);
        }
    }

    private static final int POST_HALF_CLOSE_TIMEOUT_MS = 15_000;

    private static void awaitHalfCloseDrain(Socket upstream,
                                            java.util.concurrent.atomic.AtomicBoolean t2cDone,
                                            java.util.concurrent.CountDownLatch t2cLatch,
                                            java.util.concurrent.atomic.AtomicLong lastRxAt) {
        try { upstream.setSoTimeout(POST_HALF_CLOSE_TIMEOUT_MS); } catch (Throwable ignored) {}
        try { upstream.shutdownOutput(); } catch (Throwable ignored) {}
        long waitAnchor = Math.max(System.currentTimeMillis(), lastRxAt.get());
        while (!t2cDone.get()) {
            long activity = Math.max(waitAnchor, lastRxAt.get());
            long remaining = POST_HALF_CLOSE_TIMEOUT_MS - (System.currentTimeMillis() - activity);
            if (remaining <= 0L) {
                closeQuietly(upstream);
                break;
            }
            try {
                if (t2cLatch.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                closeQuietly(upstream);
                break;
            }
        }
    }

    private static void relayBidirectional(final Socket client, final Socket upstream,
                                           final CallListener call, final long generation) {
        final java.util.concurrent.atomic.AtomicBoolean t2cDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch t2cLatch =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicLong lastRxAt =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        final ActiveSession tracked = registerActiveSession(client, upstream, false);

        if (!executeWorker(() -> {
            boolean cleanEof = false;
            try {
                InputStream cin = client.getInputStream();
                OutputStream dout = upstream.getOutputStream();
                byte[] buf = new byte[STREAM_COPY_BUFFER_BYTES];
                int n;
                while ((n = cin.read(buf)) != -1) {
                    dout.write(buf, 0, n);
                    flushIfDrained(cin, dout);
                    tracked.onClientTx(safeElapsedRealtime());
                    if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(n);
                }
                cleanEof = true;
            } catch (Throwable err) {
                closeQuietly(upstream);
                closeQuietly(client);
            } finally {
                if (cleanEof) {
                    awaitHalfCloseDrain(upstream, t2cDone, t2cLatch, lastRxAt);
                } else {
                    closeQuietly(upstream);
                    closeQuietly(client);
                }
            }
        })) {
            unregisterActiveSession(tracked);
            t2cDone.set(true);
            t2cLatch.countDown();
            closeQuietly(upstream);
            closeQuietly(client);
            if (call != null) call.done(client, upstream);
            return;
        }

        try {
            InputStream din = upstream.getInputStream();
            OutputStream cout = client.getOutputStream();
            byte[] buf = new byte[STREAM_COPY_BUFFER_BYTES];
            int n;
            while ((n = din.read(buf)) != -1) {
                lastRxAt.set(System.currentTimeMillis());
                tracked.onUpstreamRx(safeElapsedRealtime());
                cout.write(buf, 0, n);
                flushIfDrained(din, cout);
                if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(n);
            }
        } catch (Throwable ignored) {
        } finally {
            unregisterActiveSession(tracked);
            t2cDone.set(true);
            t2cLatch.countDown();
            closeQuietly(upstream);
            closeQuietly(client);
            if (call != null) call.done(client, upstream);
            if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
        }
    }

    private static void pipeDomesticDirectSession(final Socket client, final Socket dest,
                                                  final CallListener call, final long generation,
                                                  final byte[] firstPayload) {
        tuneSocketBuffers(client);
        tuneSocketBuffers(dest);
        try {
            if (firstPayload != null && firstPayload.length > 0) {
                OutputStream dout = dest.getOutputStream();
                dout.write(firstPayload, 0, firstPayload.length);
                dout.flush();
                if (call == null && isCurrentListener(generation)) {
                    toTargetBytes.addAndGet(firstPayload.length);
                }
            }
        } catch (Throwable err) {
            closeQuietly(dest);
            closeQuietly(client);
            if (call != null) call.done(client, dest);
            if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
            return;
        }
        relayBidirectional(client, dest, call, generation);
    }

    private static void runReplayableTlsOrHttpSession(final Socket client, final Socket initialDest,
                                                      final CallListener call, final long generation,
                                                      final String destHost, final int destPort,
                                                      final byte[] firstPayload, final boolean looksTls,
                                                      final String sniHost, final String hostKey,
                                                      final long startEpoch) {
        final String resolvedIp = (initialDest instanceof ColgramNativeDesync.NativeSocket)
                ? ((ColgramNativeDesync.NativeSocket) initialDest).getRemoteIp()
                : (initialDest.getInetAddress() != null ? initialDest.getInetAddress().getHostAddress() : destHost);
        final boolean cellular = cellularNetwork;
        final boolean nativeAvail = ColgramNativeDesync.isAvailable();
        final int maxAttempts = cellular ? 9 : 6;
        final int[] plan = buildReplayPlan(hostKey, cellular, nativeAvail, maxAttempts);
        final String destKey = baseDomainKey(destHost);
        final boolean noMssClamp = isStreamingOrVoiceDomain(hostKey) || isStreamingOrVoiceDomain(destKey);
        final boolean destConfirmed = isHostStrategyConfirmed(destKey, cellular);
        final int initStrat = pickStrategyForHost(destKey);
        final int initMss = (destConfirmed && !noMssClamp) ? mssClampForStrategy(initStrat) : 0;
        final int initWc = (destConfirmed && !noMssClamp) ? windowClampForStrategy(initStrat) : 0;
        final boolean hostConfirmed = isHostStrategyConfirmed(hostKey, cellular);
        final ParallelReplayDialer dialer = (mssClamp, wclamp) -> (call != null)
                ? call.connectDirectIp(resolvedIp, destPort, mssClamp, wclamp)
                : connectDirectIp(resolvedIp, destPort, mssClamp, wclamp);

        int waveTimeout = cellular ? 2200 : 1500;
        long totalBudget = cellular ? 9500L : 6500L;

        ReplayOutcome outcome = executeParallelReplayPlan(initialDest, initMss, initWc,
                firstPayload, looksTls, plan, dialer, call, waveTimeout, totalBudget, hostConfirmed);

        if (outcome.winningUpstream == null) {
            for (int i = 0; i < Math.min(outcome.attempts, plan.length); i++) {
                reportHostStrategyResult(hostKey, plan[i], false, false, startEpoch);
            }
            if (looksTls && isEligibleForL3RelayCascade(destHost, destPort)) {
                android.net.VpnService protVpn = (call != null) ? call.vpn : activeSystemDpiVpn;
                ColgramDohResolver.SocketProtector l3Prot = protVpn != null ? protVpn::protect : null;
                String cascadeTarget = (sniHost != null && !sniHost.isEmpty()) ? sniHost : destHost;
                Socket l3Sock = connectViaL3RelayCascade(cascadeTarget, destPort, l3RelayTimeoutMs(), l3Prot);
                if (l3Sock != null) {
                    if (call == null || call.track(l3Sock)) {
                        pipeViaL3Relay(client, l3Sock, call, generation, firstPayload, firstPayload.length, false);
                        return;
                    }
                    closeQuietly(l3Sock);
                }
            }
            Log.w(TAG, "DESYNC_ALL_FAILED host=" + hostKey + " sni=" + sniHost
                    + " ip=" + resolvedIp + " attempts=" + outcome.attempts
                    + " net=" + (cellular ? "4G" : "WiFi") + " native=" + nativeAvail);
            closeQuietly(client);
            if (call != null) call.done(client, null);
            if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
            return;
        }

        final Socket winner = outcome.winningUpstream;
        if (isTelegramDcIp(destHost) && startEpoch == currentNetworkEpoch()) {
            onDirectLayer1Success();
        }
        reportHostStrategyResult(hostKey, outcome.winningStrategy, true, outcome.attempts > 1, startEpoch);
        if (call == null && isCurrentListener(generation)) {
            toTargetBytes.addAndGet(firstPayload.length);
            fromTargetBytes.addAndGet(outcome.firstReplyLen);
            reportStrategyResult(outcome.winningStrategy, true, dcBucketFor(destHost), startEpoch);
        }
        if (outcome.attempts > 1 || (call != null && call.systemWide)) {
            Log.i(TAG, "DESYNC_OK host=" + hostKey + " strat=" + outcome.winningStrategy
                    + " attempt=" + outcome.attempts + " net=" + (cellular ? "4G" : "WiFi")
                    + " native=" + (winner instanceof ColgramNativeDesync.NativeSocket));
        }

        tuneSocketBuffers(client);
        tuneSocketBuffers(winner);
        try {
            OutputStream cout = client.getOutputStream();
            cout.write(outcome.firstReply, 0, outcome.firstReplyLen);
            cout.flush();
        } catch (Throwable writeErr) {
            closeQuietly(winner);
            closeQuietly(client);
            if (call != null) call.done(client, winner);
            if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
            return;
        }

        // Bidirectional full-speed relay on the winning upstream socket
        relayBidirectional(client, winner, call, generation);
    }

    private static void pipeStandardOrWsSession(final Socket client, final Socket dest, final CallListener call,
                                                final long generation, final boolean telegram,
                                                final String destHost, final int destPort,
                                                final boolean overlayActive, final byte[] firstPayload,
                                                final boolean looksTls, final String hostKey,
                                                final boolean isMedia, final long startEpoch) {
        tuneSocketBuffers(client);
        tuneSocketBuffers(dest);
        final boolean wsCapable = isWsCapableForSession(call != null, call != null && call.systemWide, "149.154.167.220".equals(destHost))
                && telegram && ColgramTelegramWsBridge.supports(destHost, destPort);
        final long wsAwaitMs = ColgramTelegramWsBridge.maxDialWallMs() + 800L;
        final java.util.concurrent.atomic.AtomicLong fromTarget =
                new java.util.concurrent.atomic.AtomicLong(0);
        final Object handoffLock = new Object();
        final java.util.concurrent.atomic.AtomicBoolean firstReplySeen =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean wsHandoffStarted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean clientSideClosed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch wsDialDone =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<ColgramTelegramWsBridge.WsSession> wsSessionRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Socket> l3RelaySocketRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<ColgramTelegramWsBridge.Obfuscated2FrameSplitter> wsSplitterRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Never replay an already-sent Telegram MTProto auth_key handshake onto a third-party L3
        // relay exit IP in the same session: if the direct carrier packet arrives late, Telegram sees
        // the identical auth_key from two IPs simultaneously and revokes the session (AUTH_KEY_DUPLICATED).
        // Direct raw -> direct official WSS (/apiws) stays enabled because both share the device's IP.
        final boolean l3HandoffCapable = !overlayActive && !telegram && isEligibleForL3RelayCascade(destHost, destPort);
        final int chosenStrategyVal = pickStrategyForHost(hostKey);
        final java.util.concurrent.atomic.AtomicReference<ActiveSession> trackedRef =
                new java.util.concurrent.atomic.AtomicReference<>(registerActiveSession(client, dest, telegram));
        final java.util.concurrent.atomic.AtomicBoolean t2cDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch t2cLatch =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicLong lastRxAt =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        final java.io.ByteArrayOutputStream wsReplayBuf =
                (wsCapable || l3HandoffCapable) ? new java.io.ByteArrayOutputStream(512) : null;
        final boolean[] replayActive = new boolean[]{(wsCapable && !looksTls && firstPayload.length >= 64)
                || (l3HandoffCapable && firstPayload.length > 0)};
        if (replayActive[0]) {
            wsReplayBuf.write(firstPayload, 0, firstPayload.length);
        }

        // Client -> Target
        if (!executeWorker(() -> {
            boolean clientClosed = false;
            boolean cleanClientEof = false;
            boolean c2sAttachedHandoff = false;
            try {
                InputStream cin = client.getInputStream();
                OutputStream dout = dest.getOutputStream();
                try {
                    if (overlayActive && firstPayload.length > 0) {
                        dout.write(firstPayload, 0, firstPayload.length);
                        dout.flush();
                        ActiveSession t = trackedRef.get();
                        if (t != null) t.onClientTx(safeElapsedRealtime());
                        if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(firstPayload.length);
                    } else if (firstPayload.length > 1) {
                        sendDesynced(dest, dout, firstPayload, firstPayload.length, chosenStrategyVal);
                        ActiveSession t = trackedRef.get();
                        if (t != null) t.onClientTx(safeElapsedRealtime());
                        if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(firstPayload.length);
                    } else if (firstPayload.length == 1) {
                        dout.write(firstPayload, 0, 1);
                        dout.flush();
                        ActiveSession t = trackedRef.get();
                        if (t != null) t.onClientTx(safeElapsedRealtime());
                        if (call == null && isCurrentListener(generation)) toTargetBytes.incrementAndGet();
                    }
                } catch (java.io.IOException writeErr) {
                    if ((!wsCapable && !l3HandoffCapable) || firstReplySeen.get()) throw writeErr;
                    try {
                        wsDialDone.await(wsAwaitMs + L3_RELAY_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (wsSessionRef.get() == null && l3RelaySocketRef.get() == null) throw writeErr;
                    c2sAttachedHandoff = true;
                }

                byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
                int len;
                while (true) {
                    try {
                        len = cin.read(buffer);
                    } catch (java.io.IOException clientReadErr) {
                        clientClosed = true;
                        clientSideClosed.set(true);
                        throw clientReadErr;
                    }
                    if (len == -1) {
                        clientClosed = true;
                        cleanClientEof = true;
                        clientSideClosed.set(true);
                        break;
                    }
                    boolean appendedToReplay = false;
                    ColgramTelegramWsBridge.WsSession activeWs;
                    Socket activeL3;
                    synchronized (handoffLock) {
                        activeWs = wsSessionRef.get();
                        activeL3 = l3RelaySocketRef.get();
                        if (activeWs == null && activeL3 == null && !wsHandoffStarted.get() && replayActive[0]) {
                            if (wsReplayBuf.size() + len <= 65536) {
                                wsReplayBuf.write(buffer, 0, len);
                                appendedToReplay = true;
                            } else {
                                replayActive[0] = false;
                                wsReplayBuf.reset();
                            }
                        }
                    }
                    if (activeWs == null && activeL3 == null && wsHandoffStarted.get()) {
                        try {
                            wsDialDone.await(wsAwaitMs + L3_RELAY_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        activeWs = wsSessionRef.get();
                        activeL3 = l3RelaySocketRef.get();
                        if (activeWs == null && activeL3 == null) break;
                    }
                    if (activeWs != null) {
                        c2sAttachedHandoff = true;
                        if (!appendedToReplay) {
                            ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter = wsSplitterRef.get();
                            if (splitter != null) {
                                splitter.feedToSession(buffer, 0, len, activeWs);
                            } else {
                                activeWs.sendBinary(buffer, 0, len);
                            }
                            ActiveSession t = trackedRef.get();
                            if (t != null) t.onClientTx(safeElapsedRealtime());
                            if (isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                        }
                    } else if (activeL3 != null) {
                        c2sAttachedHandoff = true;
                        if (!appendedToReplay) {
                            OutputStream l3Out = activeL3.getOutputStream();
                            l3Out.write(buffer, 0, len);
                            flushIfDrained(cin, l3Out);
                            ActiveSession t = trackedRef.get();
                            if (t != null) t.onClientTx(safeElapsedRealtime());
                            if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                        }
                    } else {
                        try {
                            dout.write(buffer, 0, len);
                            flushIfDrained(cin, dout);
                            ActiveSession t = trackedRef.get();
                            if (t != null) t.onClientTx(safeElapsedRealtime());
                            if (isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                        } catch (java.io.IOException writeErr) {
                            if ((!wsCapable && !l3HandoffCapable) || firstReplySeen.get()) throw writeErr;
                            try {
                                wsDialDone.await(wsAwaitMs + L3_RELAY_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            activeWs = wsSessionRef.get();
                            activeL3 = l3RelaySocketRef.get();
                            if (activeWs == null && activeL3 == null) throw writeErr;
                            c2sAttachedHandoff = true;
                            if (!appendedToReplay) {
                                if (activeWs != null) {
                                    ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter = wsSplitterRef.get();
                                    if (splitter != null) {
                                        splitter.feedToSession(buffer, 0, len, activeWs);
                                    } else {
                                        activeWs.sendBinary(buffer, 0, len);
                                    }
                                    if (isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                                } else {
                                    OutputStream l3Out = activeL3.getOutputStream();
                                    l3Out.write(buffer, 0, len);
                                    l3Out.flush();
                                    if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                // Guard wsSessionRef / l3RelaySocketRef ownership: NEVER let a C2S timeout or
                // raw-dest write error close an active T2C-owned handoff session or client socket!
                boolean closeClientAndHandoff;
                ColgramTelegramWsBridge.WsSession ownedWs = null;
                Socket ownedL3 = null;
                synchronized (handoffLock) {
                    if (clientClosed || (!wsCapable && !l3HandoffCapable) || firstReplySeen.get()) {
                        closeClientAndHandoff = true;
                        ownedWs = wsSessionRef.get();
                        ownedL3 = l3RelaySocketRef.get();
                    } else if (c2sAttachedHandoff && (wsSessionRef.get() != null || l3RelaySocketRef.get() != null)) {
                        closeClientAndHandoff = true;
                        ownedWs = wsSessionRef.get();
                        ownedL3 = l3RelaySocketRef.get();
                    } else {
                        closeClientAndHandoff = false;
                    }
                }
                if (cleanClientEof && firstReplySeen.get() && ownedWs == null && ownedL3 == null) {
                    awaitHalfCloseDrain(dest, t2cDone, t2cLatch, lastRxAt);
                    closeQuietly(client);
                    closeQuietly(dest);
                } else {
                    if (closeClientAndHandoff) {
                        closeQuietly(client);
                        closeQuietly(ownedWs);
                        closeQuietly(ownedL3);
                    }
                    closeQuietly(dest);
                }
            }
        })) {
            unregisterActiveSession(trackedRef.getAndSet(null));
            closeQuietly(dest);
            closeQuietly(client);
            if (call != null) call.done(client, dest);
            return;
        }

        // Target -> Client (direct with automatic in-session WebSocket -> Layer-2 L3-relay failover when silent)
        ColgramTelegramWsBridge.WsSession ws = null;
        Socket l3RelaySock = null;
        try {
            InputStream din = dest.getInputStream();
            OutputStream cout = client.getOutputStream();
            byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
            final int replyTimeout;
            if (call != null) {
                if (call.systemWide && telegram) {
                    replyTimeout = firstReplyTimeoutMs();
                } else {
                    replyTimeout = 0; // Never kill SSH / games / VoIP signaling / server-speaks-first TCP
                }
            } else {
                replyTimeout = overlayActive ? 0 : firstReplyTimeoutMs();
            }
            int len = replyTimeout > 0
                    ? (dest instanceof ColgramNativeDesync.NativeSocket
                            ? ((ColgramNativeDesync.NativeSocket) dest).readWithTimeout(buffer, 0, buffer.length, replyTimeout)
                            : ColgramFirstResponseReader.readFirst(dest, buffer, replyTimeout))
                    : din.read(buffer);
            if (len > 0) {
                if (dest instanceof ColgramNativeDesync.NativeSocket) {
                    ((ColgramNativeDesync.NativeSocket) dest).unclampWindow();
                }
                lastRxAt.set(System.currentTimeMillis());
                ActiveSession tFirst = trackedRef.get();
                if (tFirst != null) tFirst.onUpstreamRx(safeElapsedRealtime());
                firstReplySeen.set(true);
                if (telegram) onDirectLayer1Success();
                synchronized (handoffLock) {
                    replayActive[0] = false;
                    if (wsReplayBuf != null) wsReplayBuf.reset();
                }
                if (telegram && call == null) {
                    publishUpstream(generation, UPSTREAM_REPLY);
                    if (!overlayActive) {
                        int dcBucket = dcBucketFor(destHost);
                        dcRawReplied.set(dcBucket, 1);
                        dcRawFailed.set(dcBucket, 0);
                    }
                }
                fromTarget.addAndGet(len);
                if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(len);
                cout.write(buffer, 0, len);
                cout.flush();
                while ((len = din.read(buffer)) != -1) {
                    lastRxAt.set(System.currentTimeMillis());
                    ActiveSession tLoop = trackedRef.get();
                    if (tLoop != null) tLoop.onUpstreamRx(safeElapsedRealtime());
                    fromTarget.addAndGet(len);
                    if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(len);
                    cout.write(buffer, 0, len);
                    flushIfDrained(din, cout);
                }
            } else if (call == null) {
                if (telegram) publishUpstream(generation, UPSTREAM_SILENT);
                Log.w(TAG, "upstream stayed silent (EOF) on " + dest.getRemoteSocketAddress());
                recordSilentEndpoint(dest, telegram, overlayActive, wsCapable, startEpoch);
            }
        } catch (SocketTimeoutException silent) {
            if (telegram && call == null) publishUpstream(generation, UPSTREAM_SILENT);
            Log.w(TAG, "upstream stayed silent on " + dest.getRemoteSocketAddress()
                    + " host=" + hostKey + " strat=" + chosenStrategyVal);
            if (call == null) recordSilentEndpoint(dest, telegram, overlayActive, wsCapable, startEpoch);
        } catch (Throwable failed) {
            if (call == null && fromTarget.get() == 0L) {
                if (telegram) publishUpstream(generation, UPSTREAM_SILENT);
                recordSilentEndpoint(dest, telegram, overlayActive, wsCapable, startEpoch);
            }
        } finally {
            t2cDone.set(true);
            t2cLatch.countDown();
            closeQuietly(dest);
            boolean l3Carried = false;
            if ((wsCapable || l3HandoffCapable) && fromTarget.get() == 0L && !firstReplySeen.get() && !clientSideClosed.get()) {
                byte[] replayBytes = null;
                synchronized (handoffLock) {
                    if (!firstReplySeen.get() && !clientSideClosed.get() && replayActive[0]
                            && wsReplayBuf != null && wsReplayBuf.size() > 0) {
                        wsHandoffStarted.set(true);
                        replayBytes = wsReplayBuf.toByteArray();
                        replayActive[0] = false;
                        wsReplayBuf.reset();
                    }
                }
                boolean wsPublished = false;
                if (wsCapable && replayBytes != null && replayBytes.length >= 64 && !looksTls) {
                    try {
                        int rawDc = ColgramTelegramWsBridge.extractRawDcFromObfuscated2(
                                replayBytes, replayBytes.length);
                        boolean validRawDc = ColgramTelegramWsBridge.Obfuscated2FrameSplitter.normalizeDcId(rawDc) > 0;
                        boolean mediaDc = isMedia || (validRawDc && ColgramTelegramWsBridge.isMediaDcId(rawDc));
                        if (call != null && call.systemWide && call.vpn != null) {
                            final android.net.VpnService owner = call.vpn;
                            ws = ColgramTelegramWsBridge.dialProtected(
                                    destHost, destPort, owner::protect, owner::protect, mediaDc);
                        } else if (call == null) {
                            android.net.VpnService sysVpn = activeSystemDpiVpn;
                            ws = (sysVpn != null)
                                    ? ColgramTelegramWsBridge.dialProtected(
                                            destHost, destPort, sysVpn::protect, sysVpn::protect, mediaDc)
                                    : ColgramTelegramWsBridge.dial(destHost, destPort, mediaDc);
                        }
                        int hdrDc = ws == null ? 0
                                : (validRawDc ? rawDc : (ws.rawDcId != 0 ? ws.rawDcId : (mediaDc ? -ws.dcId : ws.dcId)));
                        ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter =
                                ws == null ? null : ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(
                                        replayBytes, replayBytes.length, hdrDc);
                        if (ws != null && splitter != null && !clientSideClosed.get()) {
                            if (startEpoch == currentNetworkEpoch()) {
                                int wsBucket = dcBucketForSignedDc(ws.rawDcId != 0 ? ws.rawDcId : ws.dcId);
                                if (wsBucket == 0) wsBucket = dcBucketFor(destHost);
                                dcWsPreferred.set(wsBucket, 1);
                                dcBackoffUntil.set(wsBucket, 0L);
                                onDirectLayer1Success();
                            }
                            wsSplitterRef.set(splitter);
                            splitter.feedInitAndTailToSession(replayBytes, replayBytes.length, ws);
                            if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(replayBytes.length);
                            synchronized (handoffLock) {
                                wsPublished = !clientSideClosed.get();
                                if (wsPublished) wsSessionRef.set(ws);
                            }
                            wsDialDone.countDown();
                            if (wsPublished) {
                                ActiveSession wsTracked = registerActiveSession(client, ws, telegram);
                                wsTracked.onClientTx(safeElapsedRealtime());
                                unregisterActiveSession(trackedRef.getAndSet(wsTracked));
                                OutputStream cout = client.getOutputStream();
                                final ColgramTelegramWsBridge.WsSession activeSession = ws;
                                ColgramTelegramWsBridge.pumpUpstreamFrames(ws, cout, count -> {
                                    wsTracked.onUpstreamRx(safeElapsedRealtime());
                                    boolean first = fromTarget.getAndAdd(count) == 0L;
                                    if (first && telegram && call == null) {
                                        onDirectLayer1Success();
                                        publishUpstream(generation, UPSTREAM_REPLY);
                                        Log.i(TAG, "WS_HANDOFF_OK dc=" + activeSession.dcId
                                                + " edge=" + activeSession.edgeIp
                                                + " sni=" + activeSession.sniHost + " bytes=" + count);
                                    }
                                    if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(count);
                                });
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // Layer 1 direct + WSS both failed -> automatic in-session Layer-2 L3-relay cascade!
                if (!wsPublished && l3HandoffCapable && replayBytes != null && !clientSideClosed.get()) {
                    try {
                        android.net.VpnService protVpn = (call != null) ? call.vpn : activeSystemDpiVpn;
                        ColgramDohResolver.SocketProtector l3Prot = protVpn != null ? protVpn::protect : null;
                        l3RelaySock = connectViaL3RelayCascade(destHost, destPort, l3RelayTimeoutMs(), l3Prot);
                        if (l3RelaySock != null && (call == null || call.track(l3RelaySock)) && !clientSideClosed.get()) {
                            String l3Label = l3RelayLabelsBySocket.remove(l3RelaySock);
                            tuneSocketBuffers(l3RelaySock);
                            OutputStream l3Out = l3RelaySock.getOutputStream();
                            writeFirstPayloadForL3Relay(l3Out, replayBytes, replayBytes.length);
                            if (call == null && isCurrentListener(generation)) toTargetBytes.addAndGet(replayBytes.length);
                            boolean l3Published;
                            synchronized (handoffLock) {
                                l3Published = !clientSideClosed.get();
                                if (l3Published) l3RelaySocketRef.set(l3RelaySock);
                            }
                            wsDialDone.countDown();
                            if (l3Published) {
                                ActiveSession l3Tracked = registerActiveSession(client, l3RelaySock, telegram);
                                l3Tracked.onClientTx(safeElapsedRealtime());
                                unregisterActiveSession(trackedRef.getAndSet(l3Tracked));
                                InputStream l3In = l3RelaySock.getInputStream();
                                OutputStream cout = client.getOutputStream();
                                byte[] buf = new byte[STREAM_COPY_BUFFER_BYTES];
                                int n;
                                while ((n = l3In.read(buf)) != -1) {
                                    long l3Now = safeElapsedRealtime();
                                    l3Tracked.onUpstreamRx(l3Now);
                                    boolean first = fromTarget.getAndAdd(n) == 0L;
                                    l3Carried = true;
                                    lastL3RelaySuccessAt = l3Now;
                                    if (first && call == null) {
                                        publishUpstream(generation, UPSTREAM_REPLY);
                                    }
                                    if (call == null && isCurrentListener(generation)) fromTargetBytes.addAndGet(n);
                                    cout.write(buf, 0, n);
                                    flushIfDrained(l3In, cout);
                                }
                                if (!l3Carried) {
                                    penalizeL3Relay(l3Label);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            unregisterActiveSession(trackedRef.getAndSet(null));
            wsDialDone.countDown();
            closeQuietly(ws);
            closeQuietly(l3RelaySock);
            closeQuietly(client);
            if (call != null) {
                call.done(client, dest);
                if (l3RelaySock != null) call.untrackUpstream(l3RelaySock);
            }
            if (call == null && isCurrentListener(generation)) completedRelays.incrementAndGet();
            boolean rawCarried = firstReplySeen.get();
            boolean carried = fromTarget.get() > 0L;
            if (call == null && isCurrentListener(generation) && startEpoch == currentNetworkEpoch()) {
                int bucket = dcBucketFor(destHost);
                if (!overlayActive && !l3Carried) {
                    reportHostStrategyResult(hostKey, chosenStrategyVal, rawCarried, false, startEpoch);
                    reportStrategyResult(chosenStrategyVal, rawCarried, bucket, startEpoch);
                }
                if (carried && !rawCarried && !l3Carried) {
                    // WSS handoff succeeded even though raw MTProto stayed silent
                    strategyFound = true;
                    dcBackoffUntil.set(bucket, 0L);
                } else if (!carried && telegram) {
                    triggerL3WhitelistProbeAsync();
                }
            }
        }
    }

    private static void recordSilentEndpoint(Socket dest, boolean telegram, boolean overlayActive,
                                             boolean wsCapable, long expectedEpoch) {
        if (expectedEpoch >= 0L && expectedEpoch != networkEpoch.get()) return;
        if (dest == null || dest.getInetAddress() == null) return;
        String host = dest.getInetAddress().getHostAddress();
        int port = dest.getPort();
        ColgramDcRemap.reportSilentAddress(host, port);
        if (telegram && !overlayActive) {
            dcRawFailed.incrementAndGet(dcBucketFor(host));
            String key = host.toLowerCase(java.util.Locale.US) + ":" + port;
            if (silentEndpointCounts.size() >= MAX_TRACKED_MAP_ENTRIES) {
                silentEndpointCounts.clear();
            }
            if (silentEndpoints.size() >= MAX_TRACKED_MAP_ENTRIES) {
                silentEndpoints.clear();
            }
            int count = silentEndpointCounts.merge(key, 1, Integer::sum);
            if (count >= 2 || (wsCapable && ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork())) {
                silentEndpoints.add(key);
            }
        }
    }

    private static final java.util.concurrent.Semaphore ASSOCIATE_DATAGRAM_SLOTS =
            new java.util.concurrent.Semaphore(16);

    private static void pumpAssociated(final java.net.DatagramSocket udp, final Socket client, long generation) {
        if (!executeWorker(() -> {
            java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[65535], 65535);
            try {
                while (isCurrentListener(generation)) {
                    packet.setLength(65535);
                    udp.receive(packet);
                    int pktLen = packet.getLength();
                    if (pktLen < 10) continue;
                    int hostAt;
                    int hostLen;
                    int atyp = packet.getData()[3] & 0xff;
                    if (atyp == 1) {
                        hostAt = 4;
                        hostLen = 4;
                    } else if (atyp == 4) {
                        hostAt = 4;
                        hostLen = 16;
                    } else if (atyp == 3) {
                        hostAt = 5;
                        hostLen = packet.getData()[4] & 0xff;
                    } else {
                        continue;
                    }
                    int portAt = hostAt + hostLen;
                    if (portAt + 2 > pktLen) continue;
                    byte[] raw = new byte[hostLen];
                    System.arraycopy(packet.getData(), hostAt, raw, 0, hostLen);
                    String host = atyp == 3
                            ? new String(raw, StandardCharsets.UTF_8)
                            : InetAddress.getByAddress(raw).getHostAddress();
                    int port = ((packet.getData()[portAt] & 0xFF) << 8)
                            | (packet.getData()[portAt + 1] & 0xFF);
                    int payloadAt = portAt + 2;
                    int payloadLen = pktLen - payloadAt;
                    if (payloadLen <= 0) continue;
                    final InetAddress clientAddr = packet.getAddress();
                    final int clientUdpPort = packet.getPort();

                    Socket up = connectWithBackoff(host, port, generation);
                    if (up == null) continue;
                    try {
                        up.getOutputStream().write(packet.getData(), payloadAt, payloadLen);
                        up.getOutputStream().flush();
                        byte[] chunk = new byte[65500];
                        int n = up.getInputStream().read(chunk);
                        if (n > 0 && clientAddr != null && clientUdpPort > 0) {
                            byte[] hdr = new byte[10 + n];
                            hdr[3] = 1;
                            if (atyp == 1 && raw.length == 4) {
                                System.arraycopy(raw, 0, hdr, 4, 4);
                            }
                            hdr[8] = (byte) ((port >> 8) & 0xff);
                            hdr[9] = (byte) (port & 0xff);
                            System.arraycopy(chunk, 0, hdr, 10, n);
                            udp.send(new java.net.DatagramPacket(hdr, hdr.length, clientAddr, clientUdpPort));
                        }
                    } finally {
                        closeQuietly(up);
                    }
                }
            } catch (Throwable ignored) {
                // A closed control connection is the normal way this ends.
            } finally {
                udp.close();
            }
        })) {
            udp.close();
            closeQuietly(client);
            return;
        }

        // Outbound: the client's framed datagrams arrive on the control connection, and this is the
        // loop that turns them into connections.
        try {
            java.io.InputStream in = client.getInputStream();
            while (isCurrentListener(generation)) {
                byte[] head = ColgramSocks5Codec.readFully(in, 4);
                int atyp = head[3] & 0xff;
                int len;
                if (atyp == 1) {
                    len = 4 + 2;
                } else if (atyp == 4) {
                    len = 16 + 2;
                } else if (atyp == 3) {
                    len = 1 + (ColgramSocks5Codec.readByte(in) & 0xff) + 2;
                } else {
                    return;
                }
                byte[] rest = ColgramSocks5Codec.readFully(in, len);
                int hostAt;
                int hostLen;
                if (atyp == 3) {
                    hostAt = 1;
                    hostLen = rest[0] & 0xff;
                } else {
                    hostAt = 0;
                    hostLen = atyp == 1 ? 4 : 16;
                }
                byte[] raw = new byte[hostLen];
                System.arraycopy(rest, hostAt, raw, 0, hostLen);
                String host = atyp == 3
                        ? new String(raw, StandardCharsets.UTF_8)
                        : InetAddress.getByAddress(raw).getHostAddress();
                int portAt = hostAt + hostLen;
                int port = ((rest[portAt] & 0xFF) << 8) | (rest[portAt + 1] & 0xFF);
                int payloadAt = portAt + 2;
                int payloadLen = rest.length - payloadAt;
                if (payloadLen <= 0) continue;

                if (!ASSOCIATE_DATAGRAM_SLOTS.tryAcquire()) continue;
                Socket up = connectWithBackoff(host, port, generation);
                if (up == null) {
                    ASSOCIATE_DATAGRAM_SLOTS.release();
                    continue;
                }
                final Socket sock = up;
                final byte[] payload = new byte[payloadLen];
                System.arraycopy(rest, payloadAt, payload, 0, payloadLen);
                if (!executeWorker(() -> {
                    try {
                        sock.setSoTimeout(5000);
                        sock.getOutputStream().write(payload);
                        sock.getOutputStream().flush();
                        byte[] chunk = new byte[65535];
                        int n = sock.getInputStream().read(chunk);
                        if (n > 0) {
                            udp.send(new java.net.DatagramPacket(chunk, n,
                                    InetAddress.getByName("127.0.0.1"), client.getLocalPort()));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        closeQuietly(sock);
                        ASSOCIATE_DATAGRAM_SLOTS.release();
                    }
                })) {
                    closeQuietly(sock);
                    ASSOCIATE_DATAGRAM_SLOTS.release();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tuneSocketBuffers(Socket s) {
        if (s == null) return;
        try { s.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { s.setKeepAlive(true); } catch (Throwable ignored) {}
        try { s.setReceiveBufferSize(SOCKET_BUFFER_BYTES); } catch (Throwable ignored) {}
        try { s.setSendBufferSize(SOCKET_BUFFER_BYTES); } catch (Throwable ignored) {}
    }

    private static void flushIfDrained(InputStream in, OutputStream out) throws java.io.IOException {
        if (in.available() <= 0) {
            out.flush();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** The listener half, used when a bind fails and the half-built socket has to go back. */
    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
