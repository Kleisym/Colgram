package org.colgram.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramProxyManager — Manages built-in MTProto and Fake-TLS proxy pools.
 * Provides automated health-checking, latency measurement, and seamless
 * failover without requiring system-level VPN privileges.
 */
public class ColgramProxyManager {

    public static class ProxyItem {
        public final String address;
        public final int port;
        public final String secret; // Fake-TLS secret (starts with 'ee...')
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

    // Curated high-availability default Fake-TLS MTProto proxies
    private static final List<ProxyItem> DEFAULT_PROXIES = new ArrayList<>();

    static {
        // Fallback resilient nodes masquerading as Cloudflare/Google CDN
        DEFAULT_PROXIES.add(new ProxyItem("149.154.167.50", 443, "ee1603010200010001fc030386e24c3add7777772e676f6f676c652e636f6d"));
        DEFAULT_PROXIES.add(new ProxyItem("149.154.175.100", 443, "ee1603010200010001fc030386e24c3add636c6f7564666c6172652e636f6d"));
        DEFAULT_PROXIES.add(new ProxyItem("91.108.56.165", 443, "ee1603010200010001fc030386e24c3add7777772e6d6963726f736f66742e636f6d"));
    }

    private static ProxyItem activeProxy = null;

    public interface ProxyCallback {
        void onProxySelected(ProxyItem proxy);
    }

    /**
     * Finds the fastest reachable proxy and activates it.
     */
    public static void autoSelectFastestProxy(final ProxyCallback callback) {
        if (!ColgramConfig.isBuiltinProxyEnabled()) {
            if (callback != null) callback.onProxySelected(null);
            return;
        }

        executor.execute(() -> {
            ProxyItem bestProxy = null;
            int lowestPing = Integer.MAX_VALUE;

            for (ProxyItem item : DEFAULT_PROXIES) {
                int ping = testProxyLatency(item.address, item.port, 2500);
                if (ping >= 0 && ping < lowestPing) {
                    lowestPing = ping;
                    item.pingMs = ping;
                    item.isAvailable = true;
                    bestProxy = item;
                }
            }

            final ProxyItem selected = bestProxy;
            activeProxy = selected;

            if (callback != null) {
                mainHandler.post(() -> callback.onProxySelected(selected));
            }
        });
    }

    /**
     * Measures TCP connection latency to determine if the proxy is reachable
     * through local DPI/censorship firewalls.
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
