package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

/**
 * ColgramProxyManager — Manages built-in MTProto and Fake-TLS proxy pools.
 * Automatically enables censorship-resistant proxies out-of-the-box so
 * registration, login, and messaging work without system VPN.
 */
public class ColgramProxyManager {

    public static class ProxyItem {
        public final String address;
        public final int port;
        public final String secret;
        public int pingMs = -1;
        public boolean isAvailable = false;

        public ProxyItem(String address, int port, String secret) {
            this.address = address;
            this.port = port;
            this.secret = secret;
        }
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Curated high-availability default Fake-TLS MTProto proxies masquerading as AWS, Cloudflare, etc.
    private static final List<ProxyItem> DEFAULT_PROXIES = Collections.synchronizedList(new ArrayList<>());

    static {
        DEFAULT_PROXIES.add(new ProxyItem("t.meow-meow-fast.site", 443, "eeaea279c83d92a4c4fa8a780775d0458b73332e616d617a6f6e6177732e636f6d"));
        DEFAULT_PROXIES.add(new ProxyItem("yostavpn.casacam.net", 443, "eec17adfc3591215500ff524021295b2fa636c6f7564666c6172652e636f6d"));
        DEFAULT_PROXIES.add(new ProxyItem("sioms.co.uk", 25565, "ee104462821249bd7ac519130220c25d0963646e2e79656b74616e65742e636f6d"));
        DEFAULT_PROXIES.add(new ProxyItem("media.experthost.shop", 443, "ee92ccb7af38638802ad9afb21d587fa9f7777772e6d6963726f736f66742e636f6d"));
        DEFAULT_PROXIES.add(new ProxyItem("194.59.221.90", 8443, "eef4b79908a669cfe8f29394142828b8e07777772e676f6f676c652e636f6d"));
    }

    private static ProxyItem activeProxy = null;

    public interface ProxyCallback {
        void onProxySelected(ProxyItem proxy);
    }

    /**
     * Activates the default built-in proxy immediately on app startup
     * so that login and initial connection succeed without infinite loading.
     */
    public static void activateBuiltinProxy(Context context) {
        if (context == null || DEFAULT_PROXIES.isEmpty()) return;
        ProxyItem proxy = DEFAULT_PROXIES.get(0);
        applyProxy(context, proxy);
    }

    /**
     * Applies a proxy item directly into Telegram's SharedPreferences and ConnectionsManager.
     */
    public static void applyProxy(Context context, ProxyItem proxy) {
        if (context == null || proxy == null) return;
        activeProxy = proxy;

        try {
            SharedPreferences preferences = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
            preferences.edit()
                    .putBoolean("proxy_enabled", true)
                    .putString("proxy_ip", proxy.address)
                    .putInt("proxy_port", proxy.port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", proxy.secret)
                    .putInt("proxy_type", 2) // MTProto Fake-TLS
                    .apply();

            // Set in native ConnectionsManager for all accounts
            try {
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Method nativeSetProxy = cmClass.getMethod("native_setProxySettings",
                        int.class, String.class, int.class, String.class, String.class, String.class);
                for (int i = 0; i < 4; i++) {
                    nativeSetProxy.invoke(null, i, proxy.address, proxy.port, "", "", proxy.secret);
                }
            } catch (Throwable ignored) {}

            // Notify SharedConfig to reload proxy list
            try {
                Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
                Method loadList = scClass.getMethod("loadProxyList");
                loadList.invoke(null);
            } catch (Throwable ignored) {}

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Automatically tests proxy latency and switches to the fastest reachable one.
     */
    public static void autoSelectFastestProxy(final ProxyCallback callback) {
        executor.execute(() -> {
            // First fetch fresh proxies from online repository
            fetchOnlineProxies();

            ProxyItem bestProxy = null;
            int lowestPing = Integer.MAX_VALUE;

            List<ProxyItem> copyList = new ArrayList<>(DEFAULT_PROXIES);
            for (ProxyItem item : copyList) {
                int ping = testProxyLatency(item.address, item.port, 2500);
                if (ping >= 0 && ping < lowestPing) {
                    lowestPing = ping;
                    item.pingMs = ping;
                    item.isAvailable = true;
                    bestProxy = item;
                }
            }

            final ProxyItem selected = bestProxy != null ? bestProxy : (copyList.isEmpty() ? null : copyList.get(0));
            if (selected != null) {
                activeProxy = selected;
            }

            if (callback != null && selected != null) {
                mainHandler.post(() -> callback.onProxySelected(selected));
            }
        });
    }

    /**
     * Fetches fresh verified MTProto proxies from online public directory.
     */
    private static void fetchOnlineProxies() {
        try {
            URL url = new URL("https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/proxies.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONArray arr = new JSONArray(sb.toString());
                int count = Math.min(arr.length(), 10);
                for (int i = 0; i < count; i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String server = obj.optString("server");
                    int port = obj.optInt("port");
                    String secret = obj.optString("secret");
                    if (!server.isEmpty() && port > 0 && secret.startsWith("ee")) {
                        DEFAULT_PROXIES.add(new ProxyItem(server, port, secret));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Measures TCP connection latency to verify connectivity through DPI.
     */
    public static int testProxyLatency(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return (int) (System.currentTimeMillis() - start);
        } catch (Exception e) {
            return -1;
        }
    }

    public static ProxyItem getActiveProxy() {
        return activeProxy;
    }

    public static List<ProxyItem> getAllProxies() {
        return new ArrayList<>(DEFAULT_PROXIES);
    }
}
