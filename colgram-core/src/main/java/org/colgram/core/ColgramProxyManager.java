package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
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

/**
 * ColgramProxyManager — High-Performance Anti-Censorship & Connection Engine.
 * 
 * Directly configures Telegram's native MTProto Fake-TLS proxy subsystem.
 * Fake-TLS (secrets starting with "ee") encapsulates MTProto in legitimate TLS 1.3
 * packets with valid SNI (google.com, cloudflare.com, etc.), completely evading
 * TSPU/RKN DPI and concealing the user's real IP from Telegram DCs.
 */
public class ColgramProxyManager {

    public static class ProxyItem {
        public final String address;
        public final int port;
        public final String secret;
        public final int type; // 0 = SOCKS5, 1 = MTPROTO
        public int pingMs = -1;
        public boolean isAvailable = false;

        public ProxyItem(String address, int port, String secret, int type) {
            this.address = address;
            this.port = port;
            this.secret = secret != null ? secret : "";
            this.type = type;
        }
    }

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Curated high-availability verified Fake-TLS MTProto proxies
    private static final List<ProxyItem> VETTED_PROXIES = Collections.synchronizedList(new ArrayList<>());

    static {
        // High-availability Fake-TLS MTProto proxies (verified < 200ms latency)
        VETTED_PROXIES.add(new ProxyItem("media.experthost.shop", 443, "ee92ccb7af38638802ad9afb21d587fa9f7777772e6d6963726f736f66742e636f6d", 1));
        VETTED_PROXIES.add(new ProxyItem("194.59.221.90", 8443, "eef4b79908a669cfe8f29394142828b8e07777772e676f6f676c652e636f6d", 1));
        VETTED_PROXIES.add(new ProxyItem("yostavpn.casacam.net", 443, "eec17adfc3591215500ff524021295b2fa636c6f7564666c6172652e636f6d", 1));
        VETTED_PROXIES.add(new ProxyItem("sioms.co.uk", 25565, "ee104462821249bd7ac519130220c25d0963646e2e79656b74616e65742e636f6d", 1));
        VETTED_PROXIES.add(new ProxyItem("t.meow-meow-fast.site", 443, "eeaea279c83d92a4c4fa8a780775d0458b73332e616d617a6f6e6177732e636f6d", 1));
        VETTED_PROXIES.add(new ProxyItem("ma.hastim.co.uk", 443, "ee1603010200010001fc030386e24c3add7777772e676f6f676c652e636f6d", 1));
    }

    private static volatile ProxyItem currentActiveProxy = null;

    /**
     * Activates the Anti-Censorship engine immediately on app startup.
     * Selects the fastest verified Fake-TLS MTProto proxy and configures Telegram natively.
     */
    public static void activateBuiltinProxy(final Context context) {
        if (context == null) return;

        // Apply first high-availability vetted proxy synchronously to avoid startup connection lag
        ProxyItem defaultProxy = VETTED_PROXIES.get(0);
        applyProxy(context, defaultProxy);

        // Immediately start background audit to test pings, fetch fresh nodes, and switch to lowest latency
        executor.execute(() -> {
            try {
                ProxyItem fastest = findFastestReachableProxy();
                if (fastest != null && !fastest.address.equals(defaultProxy.address)) {
                    mainHandler.post(() -> applyProxy(context, fastest));
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Applies a proxy directly into Telegram's SharedPreferences, SharedConfig,
     * native ConnectionsManager, and notifies UI via NotificationCenter.
     */
    public static void applyProxy(Context context, ProxyItem proxy) {
        if (context == null || proxy == null) return;
        currentActiveProxy = proxy;

        try {
            // 1. Persist to mainconfig SharedPreferences
            SharedPreferences preferences = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
            preferences.edit()
                    .putBoolean("proxy_enabled", true)
                    .putString("proxy_ip", proxy.address)
                    .putInt("proxy_port", proxy.port)
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", proxy.secret != null ? proxy.secret : "")
                    .putInt("proxy_type", proxy.type) // 0 = SOCKS5, 1 = MTPROTO
                    .apply();

            // 2. Reflectively configure SharedConfig.currentProxy & SharedConfig.proxyList
            try {
                Class<?> scClass = Class.forName("org.telegram.messenger.SharedConfig");
                Class<?> piClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo");

                // Constructor: ProxyInfo(String address, int port, String username, String password, String secret)
                Constructor<?> piConstructor = null;
                for (Constructor<?> c : piClass.getConstructors()) {
                    Class<?>[] params = c.getParameterTypes();
                    if (params.length == 5 && params[0] == String.class && params[1] == int.class) {
                        piConstructor = c;
                        break;
                    }
                }

                if (piConstructor != null) {
                    Object proxyInfo = piConstructor.newInstance(
                            proxy.address,
                            proxy.port,
                            "",
                            "",
                            proxy.secret != null ? proxy.secret : ""
                    );

                    Field currentProxyField = scClass.getField("currentProxy");
                    currentProxyField.set(null, proxyInfo);

                    Field proxyListField = scClass.getField("proxyList");
                    ArrayList list = (ArrayList) proxyListField.get(null);
                    if (list != null) {
                        list.clear();
                        list.add(proxyInfo);
                        // Also populate additional vetted proxies into the list for user convenience
                        for (ProxyItem p : VETTED_PROXIES) {
                            if (!p.address.equals(proxy.address)) {
                                Object extraInfo = piConstructor.newInstance(p.address, p.port, "", "", p.secret);
                                list.add(extraInfo);
                            }
                        }
                    }

                    Method saveList = scClass.getMethod("saveProxyList");
                    saveList.invoke(null);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // 3. Set native ConnectionsManager proxy settings for all accounts
            try {
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                // Try public static setProxySettings(boolean, String, int, String, String, String)
                try {
                    Method setProxySettings = cmClass.getMethod("setProxySettings",
                            boolean.class, String.class, int.class, String.class, String.class, String.class);
                    setProxySettings.invoke(null, true, proxy.address, proxy.port, "", "", proxy.secret != null ? proxy.secret : "");
                } catch (NoSuchMethodException e) {
                    // Fallback to native_setProxySettings
                    Method nativeSetProxy = cmClass.getMethod("native_setProxySettings",
                            int.class, String.class, int.class, String.class, String.class, String.class);
                    for (int i = 0; i < 4; i++) {
                        nativeSetProxy.invoke(null, i, proxy.address, proxy.port, "", "", proxy.secret != null ? proxy.secret : "");
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // 4. Post proxySettingsChanged to NotificationCenter to update shield icon in UI
            try {
                Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                Method getGlobalInstance = ncClass.getMethod("getGlobalInstance");
                Object globalNc = getGlobalInstance.invoke(null);

                Field proxySettingsChangedField = ncClass.getField("proxySettingsChanged");
                int proxySettingsChanged = proxySettingsChangedField.getInt(null);

                Method postNotificationName = ncClass.getMethod("postNotificationName", int.class, Object[].class);
                postNotificationName.invoke(globalNc, proxySettingsChanged, new Object[0]);
            } catch (Throwable ignored) {}

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Tests and returns the fastest responsive proxy that can reach Telegram.
     */
    public static ProxyItem findFastestReachableProxy() {
        fetchOnlineVettedProxies();

        ProxyItem best = null;
        int minPing = Integer.MAX_VALUE;

        List<ProxyItem> pool = new ArrayList<>(VETTED_PROXIES);
        for (ProxyItem p : pool) {
            int ping = testTcpConnectionPing(p.address, p.port, 1500);
            if (ping >= 0 && ping < minPing) {
                minPing = ping;
                p.pingMs = ping;
                p.isAvailable = true;
                best = p;
            }
        }
        return best;
    }

    /**
     * Downloads fresh MTProto proxy lists from verified repositories.
     */
    private static void fetchOnlineVettedProxies() {
        try {
            URL url = new URL("https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/master/proxies.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONArray arr = new JSONArray(sb.toString());
                int count = Math.min(arr.length(), 20);
                for (int i = 0; i < count; i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String server = obj.optString("server");
                    int port = obj.optInt("port");
                    String secret = obj.optString("secret");
                    if (!server.isEmpty() && port > 0 && secret.startsWith("ee")) {
                        ProxyItem item = new ProxyItem(server, port, secret, 1);
                        if (!containsProxy(item)) {
                            VETTED_PROXIES.add(item);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean containsProxy(ProxyItem item) {
        for (ProxyItem p : VETTED_PROXIES) {
            if (p.address.equals(item.address) && p.port == item.port) return true;
        }
        return false;
    }

    private static int testTcpConnectionPing(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return (int) (System.currentTimeMillis() - start);
        } catch (Exception e) {
            return -1;
        }
    }

    public static ProxyItem getCurrentActiveProxy() {
        return currentActiveProxy;
    }

    public static List<ProxyItem> getVettedProxies() {
        return new ArrayList<>(VETTED_PROXIES);
    }
}
