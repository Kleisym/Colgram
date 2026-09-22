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
        public final int type; // 0 = SOCKS5, 1 = MTProto
        public int pingMs = -1;
        public boolean isAvailable = false;

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

        // 4. Background — fetch fresh proxies, test all, update pool
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
                    int ping = testProxy(active.address, active.port, 3000);
                    if (ping < 0) {
                        Log.w(TAG, "Current proxy unreachable, rotating: " + active.address);
                        switchToNextProxy();
                    }
                }
            } catch (Throwable ignored) {}
        }, 30, 30, TimeUnit.SECONDS);

        // 6. Full re-fetch every 30 minutes
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
        localDpi.isAvailable = true;
        localDpi.pingMs = 0;
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

        for (ProxyItem p : hardcoded) {
            p.isAvailable = true;
            p.pingMs = 1;
            if (!containsProxy(p)) {
                verifiedPool.add(p);
            }
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
    public static synchronized void switchToNextProxy() {
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
        int nextIndex = (currentIndex + 1) % verifiedPool.size();
        ProxyItem next = verifiedPool.get(nextIndex);
        Log.d(TAG, "Rotating proxy to: " + next.address + ":" + next.port);

        // Wrapped the whole pool without finding a live remote proxy: fall back to the local
        // DPI bypass rather than looping through dead hosts, and refresh the list in the
        // background so a later cycle has real candidates.
        if (currentIndex >= 0 && nextIndex == 0) {
            ProxyItem localDpi = findLocalDpiProxy();
            if (localDpi != null && !localDpi.equals(currentActiveProxy)) {
                Log.w(TAG, "Proxy pool exhausted; falling back to the local DPI bypass");
                next = localDpi;
                executor.execute(() -> {
                    try {
                        fetchAndVerifyAllSources();
                    } catch (Throwable ignored) {
                    }
                });
            }
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

        // Test all proxies in pool
        List<ProxyItem> snapshot = new ArrayList<>(verifiedPool);
        for (ProxyItem p : snapshot) {
            if (p.isLocalDpi()) continue;
            executor.execute(() -> {
                int ping = testProxy(p.address, p.port, 2500);
                p.pingMs = ping;
                p.isAvailable = ping >= 0;
            });
        }
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

            // 2. Set native ConnectionsManager proxy settings directly
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

            // 3. Reload SharedConfig proxy list cleanly
            try {
                Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
                try {
                    Field pllField = scClass.getDeclaredField("proxyListLoaded");
                    pllField.setAccessible(true);
                    pllField.setBoolean(null, false);
                } catch (Throwable ignored) {}

                Method loadProxyListMethod = scClass.getDeclaredMethod("loadProxyList");
                loadProxyListMethod.setAccessible(true);
                loadProxyListMethod.invoke(null);
            } catch (Throwable t) {
                Log.e(TAG, "SharedConfig loadProxyList error", t);
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
            mainHandler.post(() -> {
                try {
                    Toast.makeText(ctx, "Прокси: Отключен", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
        } else {
            if (verifiedPool.isEmpty()) {
                initVerifiedPool();
            }
            ProxyItem target = (currentActiveProxy != null && !currentActiveProxy.isLocalDpi()) ? currentActiveProxy : (verifiedPool.isEmpty() ? null : verifiedPool.get(0));
            if (target != null) {
                forceApplyProxy(target);
                mainHandler.post(() -> {
                    try {
                        Toast.makeText(ctx, "Прокси: Включен (" + target.address + ")", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                });
            }
        }
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
            if (ip == null || ip.isEmpty() || port <= 0) return null;
            for (ProxyItem p : verifiedPool) {
                if (p.address.equals(ip) && p.port == port && p.type == type) return p;
            }
            // Not in the pool (e.g. a user-entered private proxy, which has no reason to be
            // there). Honour the choice anyway rather than quietly substituting something else.
            ProxyItem custom = new ProxyItem(ip, port, secret, type);
            custom.isAvailable = true;
            custom.pingMs = 0;
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
            ColgramDpiBypass.start();
            ProxyItem local = null;
            for (ProxyItem p : verifiedPool) {
                if (p.isLocalDpi()) { local = p; break; }
            }
            if (local != null) {
                forceApplyProxy(local);
                Log.i(TAG, "desync bypass enabled and applied");
            } else {
                Log.w(TAG, "desync bypass enabled but the local node is not in the pool");
            }
        } else {
            disableProxy(context);
            ColgramDpiBypass.stop();
            Log.i(TAG, "desync bypass disabled, reverted to direct connection");
        }
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

            try {
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Method nativeSetProxy = cmClass.getDeclaredMethod("native_setProxySettings",
                        int.class, String.class, int.class, String.class, String.class, String.class);
                nativeSetProxy.setAccessible(true);
                for (int i = 0; i < colgramAccountSlots; i++) {
                    nativeSetProxy.invoke(null, i, "", 0, "", "", "");
                }
            } catch (Throwable t) {
                Log.e(TAG, "disableProxy nativeSetProxy error", t);
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

    public static void populateSharedConfigProxies() {
        try {
            Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
            Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");
            Class<?> psClass = Class.forName("org.telegram.proxy.ProxySettings");
            Class<?> pstClass = Class.forName("org.telegram.proxy.ProxySettings$Type");

            Field plField = scClass.getDeclaredField("proxyList");
            plField.setAccessible(true);
            List proxyList = (List) plField.get(null);

            if (proxyList != null && proxyList.isEmpty()) {
                if (verifiedPool.isEmpty()) {
                    initVerifiedPool();
                }
                for (ProxyItem p : verifiedPool) {
                    try {
                        Object typeObj;
                        if (p.type == 1) {
                            typeObj = Enum.valueOf((Class<Enum>) pstClass, "MTPROTO");
                        } else {
                            typeObj = Enum.valueOf((Class<Enum>) pstClass, "SOCKS5");
                        }

                        Method builderMethod = psClass.getDeclaredMethod("builder");
                        builderMethod.setAccessible(true);
                        Object builder = builderMethod.invoke(null);

                        Method setAddress = builder.getClass().getDeclaredMethod("setAddress", String.class);
                        Method setPort = builder.getClass().getDeclaredMethod("setPort", int.class);
                        Method setSecret = builder.getClass().getDeclaredMethod("setSecret", String.class);
                        Method build = builder.getClass().getDeclaredMethod("build");

                        setAddress.invoke(builder, p.address);
                        setPort.invoke(builder, p.port);
                        setSecret.invoke(builder, p.secret != null ? p.secret : "");
                        Object settings = build.invoke(builder);

                        java.lang.reflect.Constructor<?> piConstructor = piClass.getConstructor(psClass);
                        piConstructor.setAccessible(true);
                        Object proxyInfo = piConstructor.newInstance(settings);

                        proxyList.add(proxyInfo);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to reflect ProxyInfo for " + p.address, t);
                    }
                }

                if (!proxyList.isEmpty()) {
                    Field cpField = scClass.getDeclaredField("currentProxy");
                    cpField.setAccessible(true);
                    if (cpField.get(null) == null) {
                        cpField.set(null, proxyList.get(0));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "populateSharedConfigProxies error", t);
        }
    }

    private static boolean containsProxy(ProxyItem item) {
        for (ProxyItem p : verifiedPool) {
            if (p.address.equals(item.address) && p.port == item.port) return true;
        }
        return false;
    }

    public static ProxyItem getCurrentActiveProxy() { return currentActiveProxy; }
    public static List<ProxyItem> getVerifiedPool() { return new ArrayList<>(verifiedPool); }
    public static int getAliveCount() {
        int c = 0;
        for (ProxyItem p : verifiedPool) if (p.isAvailable) c++;
        return c;
    }
}
