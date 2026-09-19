package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
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

        // 1. Start embedded DPI bypass engine immediately (runs local service on 127.0.0.1:9876)
        ColgramDpiBypass.start();

        // 2. Populate verified pool with clean verified proxies
        initVerifiedPool();

        // 3. Apply proxy ONLY if user has proxy enabled in settings (never force if disabled)
        SharedPreferences mainPrefs = appContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        boolean isProxyEnabled = mainPrefs.getBoolean("proxy_enabled", false);
        if (isProxyEnabled && ColgramConfig.isBuiltinProxyEnabled() && !verifiedPool.isEmpty()) {
            forceApplyProxy(verifiedPool.get(0));
        }

        // 4. Background — fetch fresh proxies, test all, update pool
        executor.execute(() -> {
            try {
                fetchAndVerifyAllSources();
            } catch (Throwable t) {
                Log.e(TAG, "Initial proxy fetch error", t);
            }
        });

        // 5. Periodic health check every 5 minutes
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (ColgramConfig.isBuiltinProxyEnabled() && currentActiveProxy != null && !currentActiveProxy.isLocalDpi()) {
                    int ping = testProxy(currentActiveProxy.address, currentActiveProxy.port, 3000);
                    if (ping < 0) {
                        Log.w(TAG, "Current proxy unreachable, rotating: " + currentActiveProxy.address);
                        switchToNextProxy();
                    }
                }
            } catch (Throwable ignored) {}
        }, 5, 5, TimeUnit.MINUTES);

        // 6. Full re-fetch every 30 minutes
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                fetchAndVerifyAllSources();
            } catch (Throwable ignored) {}
        }, 30, 30, TimeUnit.MINUTES);
    }

    private static void initVerifiedPool() {
        // Priority 1: Clean Fake-TLS MTProto proxies (without spam/sponsor channels)
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

        // Priority 2: Local DPI Desync Bypass (127.0.0.1:9876) — available in pool
        ProxyItem localDpi = new ProxyItem("127.0.0.1", ColgramDpiBypass.LOCAL_PORT, "", 0);
        localDpi.isAvailable = true;
        localDpi.pingMs = 0;
        if (!containsProxy(localDpi)) {
            verifiedPool.add(localDpi);
        }
    }

    /**
     * Switch to the next available proxy in the pool and apply it.
     */
    public static synchronized void switchToNextProxy() {
        if (verifiedPool.isEmpty()) return;
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

        mainHandler.post(() -> {
            forceApplyProxy(next);
            if (appContext != null) {
                try {
                    Toast.makeText(appContext, "Сеть: " + next.toString(), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        });
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
        try {
            URL url = new URL(sourceUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Fetch error from " + sourceUrl, t);
        }
    }

    private static void fetchProxiesTxt(String sourceUrl) {
        try {
            URL url = new URL(sourceUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Fetch error from " + sourceUrl, t);
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
        Log.d(TAG, "Applying proxy: " + proxy.address + ":" + proxy.port + " (type=" + proxy.type + ")");

        Context ctx = appContext;
        if (ctx == null) return;

        try {
            // 1. Persist proxy settings in SharedPreferences for all accounts (0..3)
            for (int a = 0; a < 4; a++) {
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
                for (int i = 0; i < 4; i++) {
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

    public static void disableProxy(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return;
        try {
            for (int a = 0; a < 4; a++) {
                String prefName = a == 0 ? "mainconfig" : ("mainconfig" + a);
                SharedPreferences preferences = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                preferences.edit().putBoolean("proxy_enabled", false).apply();
            }

            try {
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Method nativeSetProxy = cmClass.getDeclaredMethod("native_setProxySettings",
                        int.class, String.class, int.class, String.class, String.class, String.class);
                nativeSetProxy.setAccessible(true);
                for (int i = 0; i < 4; i++) {
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
