package org.colgram.core;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

/**
 * A DNS-over-HTTPS resolver that keeps resolving names when the system one stops.
 *
 * Why this exists, measured on the device 2026-09-27: UDP/53 answers, and DoH answers on
 * Cloudflare, Google and AdGuard alike. But a resolver that works is not the same as a resolver
 * that keeps working - net4people/bbs #81 and Risky Bulletin (2026-08-26) both record Russian
 * ISPs cutting DNS-over-HTTPS and DNS-over-TLS with a TCP RST *after* the TLS ClientHello,
 * keyed on the SNI, while leaving the same IP reachable under a different SNI. When that
 * happens the system resolver is already the one component that can fail silently: a cached or
 * injected answer looks identical to a good one until it is wrong.
 *
 * So the resolver is built to survive a cut SNI rather than to assume there is none:
 *
 *   * the address is pinned and the SNI is set separately, so a resolver that is reachable but
 *     filtered by name can be dialled under a name that is not on the list;
 *   * every resolver is tried in turn, and a filtered one is skipped for the rest of the run
 *     rather than retried on every query - a cut resolver that keeps costing a full connect
 *     timeout makes everything slower;
 *   * a short TTL cache keeps the load down when queries are frequent, and a failure is never
 *     cached.
 *
 * Verification stays real: the certificate is checked against the resolver's own name even when
 * the SNI has been swapped for the bypass case, so a cut resolver cannot be silently replaced by
 * an impostor just because the name it was reached under changed.
 */
public final class ColgramDohResolver {

    private static final String TAG = "ColgramDoh";
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int CELLULAR_CONNECT_TIMEOUT_MS = 2400;
    private static final int READ_TIMEOUT_MS = 2000;
    private static final int CELLULAR_READ_TIMEOUT_MS = 2500;
    private static final long RACE_WAVE_DEADLINE_MS = 3800L;
    private static final long CACHE_TTL_MS = 180_000L;
    private static final int MAX_DNS_CACHE = 2048;
    private static final long CUT_COOLDOWN_MS = 30_000L;
    private static final int MAX_POOLED_PER_ENDPOINT = 2;
    private static final long POOL_IDLE_TTL_MS = 45_000L;
    private static final long POOL_MAX_AGE_MS = 120_000L;
    private static final String[] SPOOFED_RU_SNIS = {"vk.com", "max.ru"};

    /** Distinct exception thrown when TCP connect itself fails so we never retry TLS modes on a dead IP. */
    private static final class TcpConnectException extends java.io.IOException {
        TcpConnectException(String message, Throwable cause) {
            super(message, cause);
        }
        TcpConnectException(String message) {
            super(message);
        }
    }

    /** One DoH/DoT endpoint: where to connect, what protocol to speak, and what cert to verify. */
    public static final class Endpoint {
        public final String ip;
        public final String sni;
        /** The name the certificate is verified against. */
        public final String verifyName;
        /** 443 for RFC 8484 HTTP/1.1 DoH, 853 for RFC 7858 DNS-over-TLS. */
        public final int port;
        /** True when this endpoint speaks RFC 7858 DNS-over-TLS on port 853. */
        public final boolean dot;
        /** True when this endpoint accepts spoofed RU-whitelist SNI (vk.com/max.ru) while returning a CA-valid verifyName cert. */
        public final boolean supportsSpoofedSni;
        /** Provider diversity key so concurrent races span independent networks/ASNs. */
        public final String providerGroup;

        Endpoint(String ip, String sni, String verifyName) {
            this(ip, sni, verifyName, 443, false, false, verifyName);
        }

        Endpoint(String ip, String sni, String verifyName, int port, boolean dot,
                 boolean supportsSpoofedSni, String providerGroup) {
            this.ip = ip;
            this.sni = sni;
            this.verifyName = verifyName;
            this.port = port;
            this.dot = dot;
            this.supportsSpoofedSni = supportsSpoofedSni;
            this.providerGroup = providerGroup != null ? providerGroup : verifyName;
        }

        String key() {
            return ip + ":" + port;
        }
    }

    /**
     * Live-verified IPv4 DoH (HTTP/1.1) and DoT (RFC 7858:853) endpoints ordered in diverse triplets.
     *
     * Dead/HTTP2-only endpoints (stale Comss 83.220.169.155, Quad9 9.9.9.9/149.112.112.112 HTTP 505,
     * Mullvad 194.242.2.2 timeout, Yandex 77.88.8.8:443 HTTP/2-only) are excluded.
     */
    private static final Endpoint[] ENDPOINTS = {
            // Wave 1 triplet: Comss RU (DoH, spoofed SNI) + Yandex (DoT:853, spoofed SNI) + AdGuard (DoH)
            new Endpoint("141.133.184.24", "dns.comss.one", "dns.comss.one", 443, false, true, "comss"),
            new Endpoint("77.88.8.8", "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", 853, true, true, "yandex"),
            new Endpoint("94.140.14.14", "dns.adguard-dns.com", "dns.adguard-dns.com", 443, false, false, "adguard"),
            // Wave 2 triplet: Cloudflare + Google + Comss backup
            new Endpoint("1.1.1.1", "cloudflare-dns.com", "cloudflare-dns.com", 443, false, true, "cloudflare"),
            new Endpoint("8.8.8.8", "dns.google", "dns.google", 443, false, true, "google"),
            new Endpoint("141.133.183.77", "dns.comss.one", "dns.comss.one", 443, false, true, "comss"),
            // Wave 3 triplet: AliDNS (spoofed SNI) + ControlD + DNS.SB (spoofed SNI)
            new Endpoint("223.5.5.5", "dns.alidns.com", "dns.alidns.com", 443, false, true, "alidns"),
            new Endpoint("76.76.2.0", "freedns.controld.com", "freedns.controld.com", 443, false, false, "controld"),
            new Endpoint("45.11.45.11", "doh.dns.sb", "doh.dns.sb", 443, false, true, "dnssb"),
            // Wave 4 triplet: Comss + Yandex DoT backup + AdGuard backup
            new Endpoint("212.109.195.93", "dns.comss.one", "dns.comss.one", 443, false, true, "comss"),
            new Endpoint("77.88.8.1", "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", 853, true, true, "yandex"),
            new Endpoint("94.140.15.15", "dns.adguard-dns.com", "dns.adguard-dns.com", 443, false, false, "adguard"),
            // Wave 5 backups: Cloudflare + Google + Comss
            new Endpoint("1.0.0.1", "cloudflare-dns.com", "cloudflare-dns.com", 443, false, true, "cloudflare"),
            new Endpoint("8.8.4.4", "dns.google", "dns.google", 443, false, true, "google"),
            new Endpoint("155.212.172.101", "dns.comss.one", "dns.comss.one", 443, false, true, "comss"),
            new Endpoint("212.109.221.20", "dns.comss.one", "dns.comss.one", 443, false, true, "comss"),
    };

    /** Uncensored IPv6 DoH/DoT endpoints interleaved automatically on dual-stack 4G/LTE networks. */
    private static final Endpoint[] IPV6_ENDPOINTS = {
            new Endpoint("2a10:50c0::ad1:ff", "dns.adguard-dns.com", "dns.adguard-dns.com", 443, false, false, "adguard"),
            new Endpoint("2001:4860:4860::8888", "dns.google", "dns.google", 443, false, true, "google"),
            new Endpoint("2606:4700:4700::1111", "cloudflare-dns.com", "cloudflare-dns.com", 443, false, true, "cloudflare"),
            new Endpoint("2a02:6b8::feed:0ff", "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", 853, true, true, "yandex"),
    };

    /**
     * Encrypted Russian-whitelisted RFC 7858 DNS-over-TLS (port 853) fallback (CA-verified against
     * *.dot.dns.yandex.net, accepts both real SNI and spoofed vk.com SNI).
     */
    private static final Endpoint EMERGENCY_RU_DOT =
            new Endpoint("77.88.8.8", "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", 853, true, true, "yandex");
    private static final Endpoint EMERGENCY_RU_DOT_SECONDARY =
            new Endpoint("77.88.8.1", "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", 853, true, true, "yandex");

    private static final String[] EMERGENCY_UDP_DNS = {"141.133.184.24", "77.88.8.8", "94.140.14.14", "8.8.8.8"};

    static Endpoint[] configuredEndpoints() {
        return ENDPOINTS.clone();
    }

    static Endpoint emergencyRuDotEndpoint() {
        return EMERGENCY_RU_DOT;
    }

    private static volatile long lastIpv6CheckAt = 0L;
    private static volatile boolean cachedGlobalIpv6 = false;
    private static volatile long lastVpnIpv6CheckAt = 0L;
    private static volatile boolean cachedGlobalVpnIpv6 = false;
    private static final long IPV6_CHECK_CACHE_MS = 5_000L;

    private static boolean hasGlobalVpnIpv6() {
        long now = System.currentTimeMillis();
        if (now - lastVpnIpv6CheckAt < IPV6_CHECK_CACHE_MS) {
            return cachedGlobalVpnIpv6;
        }
        boolean found = false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(java.util.Locale.US);
                if (!lower.startsWith("tun") && !lower.startsWith("colgram")
                        && !lower.startsWith("ppp") && !lower.startsWith("wg")) {
                    continue;
                }
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof java.net.Inet6Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                            || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                            || addr.isMulticastAddress()) {
                        continue;
                    }
                    byte[] raw = addr.getAddress();
                    if (raw != null && raw.length == 16 && (raw[0] & 0xFE) != 0xFC) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
        } catch (Throwable ignored) {
        }
        cachedGlobalVpnIpv6 = found;
        lastVpnIpv6CheckAt = now;
        return found;
    }

    static boolean hasRoutableIpv6ForCurrentSocket(boolean isProtected, boolean globalPhysicalIpv6,
                                                   boolean overlayVpnActive, boolean globalVpnIpv6) {
        if (!globalPhysicalIpv6) return false;
        if (isProtected) return true;
        if (overlayVpnActive) return globalVpnIpv6;
        return true;
    }

    public static boolean hasRoutableIpv6ForCurrentSocket(boolean isProtected) {
        return hasRoutableIpv6ForCurrentSocket(
                isProtected,
                hasGlobalRoutableIpv6(),
                ColgramDpiBypass.isOverlayVpnActive(),
                hasGlobalVpnIpv6());
    }

    /**
     * Returns true when a real physical network interface (excluding loopback and VPN/TUN) holds
     * a globally routable IPv6 address (non-link-local, non-ULA). Enables Dual-Stack IPv6 TUN +
     * Happy Eyeballs + IPv6 DoH automatically on Russian dual-stack 4G/LTE towers while keeping
     * IPv4-only networks strictly IPv4-only.
     */
    public static boolean hasGlobalRoutableIpv6() {
        long now = System.currentTimeMillis();
        if (now - lastIpv6CheckAt < IPV6_CHECK_CACHE_MS) {
            return cachedGlobalIpv6;
        }
        boolean found = false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name != null) {
                    String lower = name.toLowerCase(java.util.Locale.US);
                    if (lower.startsWith("tun") || lower.startsWith("colgram")
                            || lower.startsWith("ppp") || lower.startsWith("wg")
                            || lower.startsWith("dummy")) {
                        continue;
                    }
                }
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof java.net.Inet6Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                            || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                            || addr.isMulticastAddress()) {
                        continue;
                    }
                    byte[] raw = addr.getAddress();
                    if (raw != null && raw.length == 16) {
                        int firstByte = raw[0] & 0xFF;
                        // Exclude ULA fc00::/7 (private VPN/local IPv6)
                        if ((firstByte & 0xFE) == 0xFC) continue;
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
        } catch (Throwable ignored) {
        }
        cachedGlobalIpv6 = found;
        lastIpv6CheckAt = now;
        return found;
    }

    private static List<Endpoint> activeEndpoints() {
        return activeEndpoints(hasRoutableIpv6ForCurrentSocket(false));
    }

    private static List<Endpoint> activeEndpoints(boolean allowIpv6) {
        boolean v6 = allowIpv6;
        List<Endpoint> out = new ArrayList<>(ENDPOINTS.length + (v6 ? IPV6_ENDPOINTS.length : 0));
        if (!v6) {
            for (Endpoint ep : ENDPOINTS) out.add(ep);
            return out;
        }
        // Interleave IPv6 DoH early (every 2 IPv4 endpoints) so IPv4-blackholed dual-stack 4G wins on Wave 1!
        int v6Idx = 0;
        for (int i = 0; i < ENDPOINTS.length; i++) {
            out.add(ENDPOINTS[i]);
            if ((i == 1 || i == 3 || i == 5) && v6Idx < IPV6_ENDPOINTS.length) {
                out.add(IPV6_ENDPOINTS[v6Idx++]);
            }
        }
        while (v6Idx < IPV6_ENDPOINTS.length) {
            out.add(IPV6_ENDPOINTS[v6Idx++]);
        }
        return out;
    }

    /** Optional socket protector (e.g. VpnService::protect) for protected DoH inside TUN. */
    public interface SocketProtector {
        boolean protect(java.net.Socket socket);
    }

    /** Optional datagram socket protector for emergency UDP/53 DNS fallback inside TUN. */
    public interface DatagramProtector {
        boolean protect(java.net.DatagramSocket socket);
    }

    private static volatile boolean cellularNetwork = false;

    public static void setCellularNetwork(boolean cellular) {
        boolean changed = (cellularNetwork != cellular);
        cellularNetwork = cellular;
        if (changed) {
            clearConnectionPool();
        }
    }

    private static final class PooledTlsConn {
        final String poolKey;
        final java.net.Socket raw;
        final javax.net.ssl.SSLSocket ssl;
        final java.io.BufferedInputStream in;
        final OutputStream out;
        final boolean dot;
        final long createdAt;
        volatile long lastUsedAt;

        PooledTlsConn(String poolKey, java.net.Socket raw, javax.net.ssl.SSLSocket ssl,
                      java.io.BufferedInputStream in, OutputStream out, boolean dot, long now) {
            this.poolKey = poolKey;
            this.raw = raw;
            this.ssl = ssl;
            this.in = in;
            this.out = out;
            this.dot = dot;
            this.createdAt = now;
            this.lastUsedAt = now;
        }

        boolean isUsable(long now) {
            if (raw.isClosed() || ssl.isClosed()) return false;
            if (now - lastUsedAt > POOL_IDLE_TTL_MS) return false;
            if (now - createdAt > POOL_MAX_AGE_MS) return false;
            return true;
        }

        void closeQuietly() {
            try { ssl.close(); } catch (Throwable ignored) {}
            try { raw.close(); } catch (Throwable ignored) {}
        }
    }

    private static final ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn>> tlsPool =
            new ConcurrentHashMap<>();

    public static void clearConnectionPool() {
        for (java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn> q : tlsPool.values()) {
            PooledTlsConn c;
            while ((c = q.pollFirst()) != null) {
                c.closeQuietly();
            }
        }
        tlsPool.clear();
    }

    private static PooledTlsConn pollPooledConn(String poolKey) {
        java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn> q = tlsPool.get(poolKey);
        if (q == null) return null;
        long now = System.currentTimeMillis();
        PooledTlsConn conn;
        while ((conn = q.pollFirst()) != null) {
            if (conn.isUsable(now)) {
                pruneExpiredPoolTail(q, now);
                return conn;
            }
            conn.closeQuietly();
        }
        return null;
    }

    private static void pruneExpiredPoolTail(java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn> q, long now) {
        if (q == null) return;
        PooledTlsConn tail;
        while ((tail = q.peekLast()) != null && !tail.isUsable(now)) {
            if (q.remove(tail)) {
                tail.closeQuietly();
            } else {
                break;
            }
        }
    }

    private static void pruneAllExpiredTlsPool(long now) {
        for (java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn> q : tlsPool.values()) {
            pruneExpiredPoolTail(q, now);
        }
    }

    static int tlsPoolTotalSizeForTest() {
        int total = 0;
        for (java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn> q : tlsPool.values()) {
            total += q.size();
        }
        return total;
    }

    static int dnsCacheSizeForTest() {
        return cache.size();
    }

    static void putDnsCacheEntry(String key, String[] found, long nowMs) {
        if (key == null || found == null || found.length == 0) return;
        if (cache.size() >= MAX_DNS_CACHE) {
            for (Map.Entry<String, long[]> entry : cache.entrySet()) {
                long[] ts = entry.getValue();
                if (ts == null || ts.length == 0 || (nowMs - ts[0]) >= CACHE_TTL_MS) {
                    if (cache.remove(entry.getKey(), ts)) {
                        answers.remove(entry.getKey());
                    }
                }
            }
            if (cache.size() >= MAX_DNS_CACHE) {
                cache.clear();
                answers.clear();
            }
        }
        cache.put(key, new long[]{nowMs});
        answers.put(key, found);
    }

    static void clearDnsCacheForTest() {
        cache.clear();
        answers.clear();
    }

    private static void returnPooledConn(PooledTlsConn conn) {
        if (conn == null) return;
        long now = System.currentTimeMillis();
        if (!conn.isUsable(now)) {
            conn.closeQuietly();
            return;
        }
        pruneAllExpiredTlsPool(now);
        conn.lastUsedAt = now;
        java.util.concurrent.ConcurrentLinkedDeque<PooledTlsConn> q =
                tlsPool.computeIfAbsent(conn.poolKey, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        pruneExpiredPoolTail(q, now);
        if (q.size() >= MAX_POOLED_PER_ENDPOINT) {
            conn.closeQuietly();
            return;
        }
        q.offerFirst(conn);
    }

    private static final Map<String, long[]> cache = new ConcurrentHashMap<>();
    private static final Map<String, String[]> answers = new ConcurrentHashMap<>();
    /** Endpoints temporarily skipped after timeout/reset until their cooldown expires. */
    private static final Map<String, Long> cutUntil = new ConcurrentHashMap<>();
    private static volatile String lastResolver;
    private static volatile Endpoint lastWorkingEndpoint;
    private static final java.util.concurrent.ExecutorService DOH_RACE_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    0, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "colgram-doh-race");
                        t.setDaemon(true);
                        t.setUncaughtExceptionHandler((th, ex) -> Log.w(TAG, "doh worker err: " + ex));
                        return t;
                    });

    private ColgramDohResolver() {}

    /**
     * Resolve a name, preferring the system and falling back to DoH.
     *
     * There is no supported way to replace the JVM's resolver on Android -
     * java.net.InetAddressResolver is libcore-internal and absent from android.jar - so this is
     * an explicit entry point rather than a global override. ColgramHttp calls it before every
     * request, and {@link #prefetch} warms the cache for names the app is about to need.
     */
    /**
     * Resolve a name using DoH first when enabled so ISP/system DNS never sees queried domains,
     * falling back to system DNS only when DoH fails and strict proxy security allows it.
     */
    public static InetAddress[] resolveOrSystem(String hostname) {
        InetAddress[] viaDoh = resolve(hostname);
        if (viaDoh != null && viaDoh.length > 0) return viaDoh;
        InetAddress[] system = resolveSystem(hostname);
        return system != null ? system : new InetAddress[0];
    }

    /**
     * One address for `hostname`, or null when no permitted resolver can answer.
     */
    public static InetAddress resolveOrNull(String hostname) {
        InetAddress[] viaDoh = resolve(hostname);
        InetAddress usable = pickReachable(viaDoh);
        if (usable != null) return usable;
        if (ColgramConfig.isDohEnabled() && ColgramConfig.isStrictProxySecurityEnabled()) {
            return null;
        }
        InetAddress[] system = resolveSystem(hostname);
        return pickReachable(system);
    }

    /**
     * The first address of a family that can be used, preferring v4.
     *
     * <p>An address is usable when the device has an interface that can carry it. That is not a guess about
     * the network - it is the same question the connect that follows will ask, asked first.
     */
    private static InetAddress pickReachable(InetAddress[] addrs) {
        if (addrs == null || addrs.length == 0) return null;
        InetAddress anyV6 = null;
        for (InetAddress a : addrs) {
            if (a == null) continue;
            if (a.getAddress() != null && a.getAddress().length == 4) return a;
            if (anyV6 == null) anyV6 = a;
        }
        return anyV6;
    }

    /**
     * Resolve in the background, for names that are about to be used.
     *
     * A failed prefetch costs one thread and is never retried until the next call, so a resolver
     * that has been cut cannot turn into a busy loop.
     */
    public static void prefetch(final String hostname) {
        if (hostname == null || hostname.isEmpty()) return;
        Thread thread = new Thread(() -> resolve(hostname), "colgram-doh-prefetch");
        thread.setDaemon(true);
        thread.start();
    }
    /** The platform resolver, isolated so a failure inside it cannot recurse into us. */
    private static InetAddress[] resolveSystem(String hostname) {
        try {
            return InetAddress.getAllByName(hostname);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void resetHealth() {
        cutUntil.clear();
        clearConnectionPool();
        lastResolver = null;
        lastWorkingEndpoint = null;
        lastIpv6CheckAt = 0L;
    }

    public static String activeResolver() {
        return lastResolver;
    }

    private static boolean isCut(String ip, long now) {
        Long until = cutUntil.get(ip);
        if (until == null) return false;
        if (now >= until) {
            cutUntil.remove(ip, until);
            return false;
        }
        return true;
    }

    private static void markCut(String ip, long now) {
        cutUntil.put(ip, now + CUT_COOLDOWN_MS);
    }

    /** True when the host string is a literal IP or loopback name (requires no DNS lookup). */
    public static boolean isIpLiteral(String host) {
        if (host == null) return false;
        String trimmed = host.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(trimmed)) return true;
        if (trimmed.indexOf(':') >= 0) {
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F') || c == ':' || c == '.')) {
                    return false;
                }
            }
            return true;
        }
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            int v = Integer.parseInt(p);
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    public static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(h) || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h) || h.startsWith("127.");
    }

    /**
     * Resolve one name: DoH FIRST when enabled so DNS queries stay encrypted, falling back to
     * system DNS only when DoH is disabled or all DoH endpoints failed and strict mode is off.
     */
    public static InetAddress[] resolve(String hostname) {
        if (hostname == null) return null;
        String cleaned = hostname.trim();
        if (cleaned.isEmpty()) return null;
        if (isIpLiteral(cleaned)) {
            InetAddress[] literal = resolveSystem(cleaned);
            if (literal != null && literal.length > 0) return literal;
        }

        if (ColgramConfig.isDohEnabled()) {
            String key = cleaned.toLowerCase(java.util.Locale.ROOT);
            long now = System.currentTimeMillis();
            long[] stamp = cache.get(key);
            String[] cached = answers.get(key);
            if (stamp != null && cached != null && now - stamp[0] < CACHE_TTL_MS) {
                return toAddresses(cached);
            }

            InetAddress[] result = queryEndpoints(key, cleaned, now, false);
            if (result != null) return result;
            if (!cutUntil.isEmpty()) {
                cutUntil.clear();
                result = queryEndpoints(key, cleaned, System.currentTimeMillis(), true);
                if (result != null) return result;
            }
            if (ColgramConfig.isStrictProxySecurityEnabled()) {
                return null;
            }
        }
        return resolveSystem(cleaned);
    }

    /**
     * Resolve both A (IPv4) and AAAA (IPv6) records via DoH, keeping IPv4 first and IPv6 as
     * fallback (critical on dual-stack mobile carriers that filter IPv4 Telegram edges).
     */
    public static InetAddress[] resolveWithIpv6(String hostname) {
        if (hostname == null) return null;
        String cleaned = hostname.trim();
        if (cleaned.isEmpty()) return null;
        if (isIpLiteral(cleaned)) return resolveSystem(cleaned);
        List<InetAddress> merged = new ArrayList<>();
        InetAddress[] v4 = resolve(cleaned);
        if (v4 != null) {
            for (InetAddress a : v4) {
                if (a != null && !merged.contains(a)) merged.add(a);
            }
        }
        if (ColgramConfig.isDohEnabled()) {
            InetAddress[] v6 = queryEndpointsQtype(cleaned.toLowerCase(java.util.Locale.ROOT), cleaned, 28, null);
            if (v6 != null) {
                for (InetAddress a : v6) {
                    if (a != null && !merged.contains(a)) merged.add(a);
                }
            }
        }
        if (merged.isEmpty()) {
            InetAddress[] sys = resolveSystem(cleaned);
            if (sys != null) {
                for (InetAddress a : sys) {
                    if (a != null && !merged.contains(a)) merged.add(a);
                }
            }
        }
        return merged.isEmpty() ? null : merged.toArray(new InetAddress[0]);
    }

    /** Resolve an A record using a VPN-protected socket so TUN traffic never loops on DNS. */
    public static InetAddress resolveProtected(String hostname, SocketProtector protector) {
        return resolveProtected(hostname, protector, null);
    }

    /** Resolve an A record using VPN-protected native ByeDPI / SNI-fragmented DoH sockets. */
    public static InetAddress resolveProtected(String hostname, SocketProtector protector,
                                               ColgramNativeDesync.FdProtector fdProtector) {
        if (hostname == null) return null;
        String cleaned = hostname.trim();
        if (cleaned.isEmpty()) return null;
        if (isIpLiteral(cleaned)) {
            InetAddress[] lit = resolveSystem(cleaned);
            return (lit != null && lit.length > 0) ? lit[0] : null;
        }
        String key = cleaned.toLowerCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        long[] stamp = cache.get(key);
        String[] cached = answers.get(key);
        if (stamp != null && cached != null && now - stamp[0] < CACHE_TTL_MS) {
            InetAddress picked = pickReachable(toAddresses(cached));
            if (picked != null) return picked;
        }
        InetAddress[] found = queryEndpointsInternal(key, cleaned, now, false, 1, protector, fdProtector);
        if (found == null && !cutUntil.isEmpty()) {
            cutUntil.clear();
            found = queryEndpointsInternal(key, cleaned, System.currentTimeMillis(), true, 1, protector, fdProtector);
        }
        return pickReachable(found);
    }

    /**
     * Resolves A (and optionally AAAA on dual-stack networks) via VPN-protected DoH so Happy
     * Eyeballs can connect over IPv6 when a carrier whitelists/drops IPv4.
     */
    public static InetAddress[] resolveProtectedAll(String hostname, SocketProtector protector,
                                                    ColgramNativeDesync.FdProtector fdProtector,
                                                    boolean includeIpv6) {
        if (hostname == null) return null;
        String cleaned = hostname.trim();
        if (cleaned.isEmpty()) return null;
        if (isIpLiteral(cleaned)) return resolveSystem(cleaned);
        List<InetAddress> merged = new ArrayList<>();
        InetAddress v4 = resolveProtected(cleaned, protector, fdProtector);
        if (v4 != null) merged.add(v4);
        boolean isProtected = (protector != null || fdProtector != null
                || ColgramDpiBypass.activeSystemDpiVpn() != null);
        if (includeIpv6 && hasRoutableIpv6ForCurrentSocket(isProtected)) {
            InetAddress[] v6 = queryEndpointsInternal(
                    cleaned.toLowerCase(java.util.Locale.ROOT), cleaned,
                    System.currentTimeMillis(), false, 28, protector, fdProtector);
            if (v6 != null) {
                for (InetAddress a : v6) {
                    if (a != null && !merged.contains(a)) merged.add(a);
                }
            }
        }
        return merged.isEmpty() ? null : merged.toArray(new InetAddress[0]);
    }

    static boolean prefersEcsDns(String hostname) {
        if (hostname == null || hostname.isEmpty()) return false;
        String h = hostname.trim().toLowerCase(java.util.Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h.equals("googlevideo.com") || h.endsWith(".googlevideo.com")
                || h.equals("youtube.com") || h.endsWith(".youtube.com")
                || h.equals("ytimg.com") || h.endsWith(".ytimg.com")
                || h.equals("ggpht.com") || h.endsWith(".ggpht.com")
                || h.equals("youtu.be")
                || h.equals("discord.media") || h.endsWith(".discord.media");
    }

    static String extractQueryHostname(byte[] msg) {
        if (msg == null || msg.length < 17) return null;
        int qdCount = ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        if (qdCount < 1) return null;
        int pos = 12;
        StringBuilder name = new StringBuilder(64);
        while (pos < msg.length) {
            int labelLen = msg[pos++] & 0xFF;
            if (labelLen == 0) break;
            if ((labelLen & 0xC0) != 0 || pos + labelLen > msg.length) return null;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < labelLen; i++) {
                name.append((char) (msg[pos++] & 0xFF));
            }
        }
        return name.length() > 0 ? name.toString().toLowerCase(java.util.Locale.ROOT) : null;
    }

    static List<Endpoint> orderedCandidatesForHost(String hostname) {
        return buildHealthyCandidates(null, System.currentTimeMillis(), true, prefersEcsDns(hostname));
    }

    /**
     * Forwards a raw RFC 1035 wire-format DNS query through resilient multi-provider DoH/DoT (with
     * Google ECS prioritization for YouTube/GoogleVideo/Discord Media, pinned fastest server +
     * concurrent 3-way diverse provider race under a 3.8s wave deadline + spoofed RU-whitelist SNI
     * + RFC 7858 Yandex DoT:853), falling back to protected UDP/53.
     */
    public static byte[] queryRawWireProtected(byte[] wireQuery, SocketProtector sockProtector,
                                               ColgramNativeDesync.FdProtector fdProtector,
                                               DatagramProtector udpProtector) {
        if (wireQuery == null || wireQuery.length < 12) return null;
        long now = System.currentTimeMillis();
        String queryHost = extractQueryHostname(wireQuery);
        boolean preferGoogleEcs = prefersEcsDns(queryHost);
        boolean isProtected = (sockProtector != null || fdProtector != null
                || ColgramDpiBypass.activeSystemDpiVpn() != null);
        boolean allowIpv6 = hasRoutableIpv6ForCurrentSocket(isProtected);

        // 1. Fast path: try the pinned last-working DoH/DoT provider first (<150ms typical),
        //    but never short-circuit ECS-sensitive media/video hosts onto a non-ECS resolver!
        Endpoint pinned = lastWorkingEndpoint;
        Endpoint pinnedTried = null;
        if (pinned != null && !isCut(pinned.key(), now)
                && (allowIpv6 || pinned.ip.indexOf(':') < 0)
                && (!preferGoogleEcs || "google".equals(pinned.providerGroup))) {
            pinnedTried = pinned;
            try {
                long fastDeadline = System.currentTimeMillis() + (cellularNetwork ? 2500L : 1900L);
                byte[] reply = queryWire(pinned, wireQuery, sockProtector, fdProtector, fastDeadline, null);
                if (reply != null && reply.length >= 12) {
                    cutUntil.remove(pinned.key());
                    lastResolver = pinned.key();
                    return reply;
                }
            } catch (Throwable t) {
                Log.d(TAG, "pinned DoH/DoT " + pinned.key() + " failed: " + t.getClass().getSimpleName());
            }
            markCut(pinned.key(), System.currentTimeMillis());
            if (lastWorkingEndpoint == pinned) lastWorkingEndpoint = null;
        }

        // 2. Concurrent waves across healthy providers (Google ECS wave first for YouTube/Discord media)
        List<Endpoint> remaining = buildHealthyCandidates(pinnedTried, now, false, preferGoogleEcs, allowIpv6);
        java.util.Set<String> triedKeys = new java.util.HashSet<>();
        if (pinnedTried != null) triedKeys.add(pinnedTried.key());

        if (preferGoogleEcs) {
            List<Endpoint> ecsWave = extractGoogleWave(remaining, 3);
            if (!ecsWave.isEmpty()) {
                for (Endpoint ep : ecsWave) triedKeys.add(ep.key());
                byte[] ecsReply = raceEndpointsWave(ecsWave, wireQuery, sockProtector, fdProtector,
                        cellularNetwork ? 2500L : 2000L, 0);
                if (ecsReply != null && ecsReply.length >= 12) {
                    return ecsReply;
                }
            }
        }

        for (int waveIdx = 0; waveIdx < 2 && !remaining.isEmpty(); waveIdx++) {
            List<Endpoint> wave = pickDiverseWave(remaining, 3);
            for (Endpoint ep : wave) triedKeys.add(ep.key());
            byte[] raced = raceEndpointsWave(wave, wireQuery, sockProtector, fdProtector,
                    RACE_WAVE_DEADLINE_MS, 0);
            if (raced != null && raced.length >= 12) {
                return raced;
            }
        }

        // 3. Last-resort encrypted Russian-whitelisted RFC 7858 DoT:853 if not already tried
        Endpoint[] ruFallbacks = {EMERGENCY_RU_DOT, EMERGENCY_RU_DOT_SECONDARY};
        for (Endpoint ruDot : ruFallbacks) {
            if (triedKeys.contains(ruDot.key())) continue;
            try {
                long dotDeadline = System.currentTimeMillis() + 2600L;
                byte[] ruReply = queryWire(ruDot, wireQuery, sockProtector, fdProtector, dotDeadline, null);
                if (ruReply != null && ruReply.length >= 12) {
                    lastResolver = ruDot.key();
                    lastWorkingEndpoint = ruDot;
                    return ruReply;
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. Emergency fallback: protected plain UDP/53 so DNS never suffers total blackout
        return queryEmergencyUdp(wireQuery, udpProtector);
    }

    private static List<Endpoint> extractGoogleWave(List<Endpoint> remaining, int maxCount) {
        List<Endpoint> googleWave = new ArrayList<>(maxCount);
        java.util.Iterator<Endpoint> it = remaining.iterator();
        while (it.hasNext() && googleWave.size() < maxCount) {
            Endpoint ep = it.next();
            if ("google".equals(ep.providerGroup)) {
                googleWave.add(ep);
                it.remove();
            }
        }
        return googleWave;
    }

    private static List<Endpoint> buildHealthyCandidates(Endpoint exclude, long now, boolean ignoreCut) {
        return buildHealthyCandidates(exclude, now, ignoreCut, false, hasRoutableIpv6ForCurrentSocket(false));
    }

    private static List<Endpoint> buildHealthyCandidates(Endpoint exclude, long now, boolean ignoreCut,
                                                         boolean preferGoogleEcs) {
        return buildHealthyCandidates(exclude, now, ignoreCut, preferGoogleEcs,
                hasRoutableIpv6ForCurrentSocket(false));
    }

    private static List<Endpoint> buildHealthyCandidates(Endpoint exclude, long now, boolean ignoreCut,
                                                         boolean preferGoogleEcs, boolean allowIpv6) {
        List<Endpoint> allActive = activeEndpoints(allowIpv6);
        List<Endpoint> candidates = new ArrayList<>(allActive.size());
        for (Endpoint ep : allActive) {
            if (ep == exclude) continue;
            if (!ignoreCut && isCut(ep.key(), now)) continue;
            candidates.add(ep);
        }
        if (candidates.isEmpty()) {
            cutUntil.clear();
            for (Endpoint ep : allActive) {
                if (ep != exclude) candidates.add(ep);
            }
        }
        if (preferGoogleEcs && !candidates.isEmpty()) {
            List<Endpoint> ordered = new ArrayList<>(candidates.size());
            for (Endpoint ep : candidates) {
                if ("google".equals(ep.providerGroup)) ordered.add(ep);
            }
            for (Endpoint ep : candidates) {
                if (!"google".equals(ep.providerGroup)) ordered.add(ep);
            }
            return ordered;
        }
        return candidates;
    }

    /** Picks up to {@code maxCount} endpoints from distinct providerGroups for a concurrent race wave. */
    static List<Endpoint> pickDiverseWave(List<Endpoint> remaining, int maxCount) {
        List<Endpoint> wave = new ArrayList<>(maxCount);
        java.util.Set<String> usedGroups = new java.util.HashSet<>();
        java.util.Iterator<Endpoint> it = remaining.iterator();
        while (it.hasNext() && wave.size() < maxCount) {
            Endpoint ep = it.next();
            if (usedGroups.add(ep.providerGroup)) {
                wave.add(ep);
                it.remove();
            }
        }
        it = remaining.iterator();
        while (it.hasNext() && wave.size() < maxCount) {
            wave.add(it.next());
            it.remove();
        }
        return wave;
    }

    /**
     * Races up to 3 diverse DoH/DoT endpoints concurrently under {@code deadlineMs}.
     * When {@code requireQtype > 0}, prefers an answer containing records of that qtype (1=A, 28=AAAA).
     * Cancels all losing open sockets as soon as a winner finishes.
     */
    private static byte[] raceEndpointsWave(final List<Endpoint> wave, final byte[] wireQuery,
                                            final SocketProtector sockProtector,
                                            final ColgramNativeDesync.FdProtector fdProtector,
                                            final long deadlineMs, final int requireQtype) {
        if (wave == null || wave.isEmpty()) return null;
        final long deadlineAt = System.currentTimeMillis() + deadlineMs;
        if (wave.size() == 1) {
            Endpoint ep = wave.get(0);
            try {
                byte[] reply = queryWire(ep, wireQuery, sockProtector, fdProtector, deadlineAt, null);
                if (reply != null && reply.length >= 12) {
                    cutUntil.remove(ep.key());
                    lastWorkingEndpoint = ep;
                    lastResolver = ep.key();
                    return reply;
                }
            } catch (Throwable ignored) {}
            markCut(ep.key(), System.currentTimeMillis());
            return null;
        }

        final java.util.concurrent.atomic.AtomicReference<byte[]> winner = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<byte[]> emptyValidReply = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(wave.size());
        final java.util.List<java.net.Socket> activeSockets =
                java.util.Collections.synchronizedList(new ArrayList<java.net.Socket>(wave.size()));

        for (final Endpoint ep : wave) {
            try {
                DOH_RACE_POOL.execute(() -> {
                    try {
                        if (winner.get() != null) return;
                        byte[] reply = queryWire(ep, wireQuery, sockProtector, fdProtector, deadlineAt, activeSockets);
                        if (reply != null && reply.length >= 12) {
                            cutUntil.remove(ep.key());
                            boolean acceptable = true;
                            if (requireQtype == 1) {
                                acceptable = parseARecords(reply).length > 0;
                            } else if (requireQtype == 28) {
                                acceptable = parseRecords(reply, true).length > 0;
                            }
                            if (acceptable) {
                                if (winner.compareAndSet(null, reply)) {
                                    lastWorkingEndpoint = ep;
                                    lastResolver = ep.key();
                                    latch.countDown();
                                }
                                return;
                            }
                            // Valid DNS reply with 0 records (e.g. AAAA absent) — endpoint is healthy, don't cut it
                            emptyValidReply.compareAndSet(null, reply);
                            return;
                        }
                        markCut(ep.key(), System.currentTimeMillis());
                    } catch (Throwable ignored) {
                        if (winner.get() == null) {
                            markCut(ep.key(), System.currentTimeMillis());
                        }
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            latch.countDown();
                        }
                    }
                });
            } catch (Throwable rejected) {
                if (remaining.decrementAndGet() == 0) {
                    latch.countDown();
                }
            }
        }
        try {
            latch.await(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (activeSockets) {
            for (java.net.Socket s : activeSockets) {
                try { s.close(); } catch (Throwable ignored) {}
            }
            activeSockets.clear();
        }
        byte[] win = winner.get();
        return win != null ? win : emptyValidReply.get();
    }

    static byte[] queryFastDomesticUdp(byte[] wireQuery, DatagramProtector udpProtector) {
        if (wireQuery == null || wireQuery.length < 12) return null;
        String[] fastDomesticDns = {"77.88.8.8", "77.88.8.1"};
        for (String dnsIp : fastDomesticDns) {
            java.net.DatagramSocket sock = null;
            try {
                sock = new java.net.DatagramSocket();
                if (udpProtector != null && !udpProtector.protect(sock)) continue;
                sock.setSoTimeout(650);
                InetAddress target = InetAddress.getByName(dnsIp);
                sock.send(new java.net.DatagramPacket(wireQuery, wireQuery.length, target, 53));
                byte[] buf = new byte[4096];
                java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(resp);
                int len = resp.getLength();
                if (len >= 12 && (buf[3] & 0x0F) == 0) {
                    lastResolver = dnsIp + ":53-fast";
                    return java.util.Arrays.copyOf(buf, len);
                }
            } catch (Throwable ignored) {
            } finally {
                if (sock != null) { try { sock.close(); } catch (Throwable ignored) {} }
            }
        }
        return null;
    }

    private static byte[] queryEmergencyUdp(byte[] wireQuery, DatagramProtector udpProtector) {
        for (String dnsIp : EMERGENCY_UDP_DNS) {
            java.net.DatagramSocket sock = null;
            try {
                sock = new java.net.DatagramSocket();
                if (udpProtector != null && !udpProtector.protect(sock)) continue;
                sock.setSoTimeout(cellularNetwork ? 2000 : 1400);
                InetAddress target = InetAddress.getByName(dnsIp);
                sock.send(new java.net.DatagramPacket(wireQuery, wireQuery.length, target, 53));
                byte[] buf = new byte[4096];
                java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(resp);
                if (resp.getLength() >= 12) {
                    lastResolver = dnsIp + ":53";
                    return java.util.Arrays.copyOf(buf, resp.getLength());
                }
            } catch (Throwable ignored) {
            } finally {
                if (sock != null) { try { sock.close(); } catch (Throwable ignored) {} }
            }
        }
        return null;
    }

    private static InetAddress[] queryEndpoints(String key, String hostname, long now, boolean ignoreCut) {
        return queryEndpointsInternal(key, hostname, now, ignoreCut, 1, null, null);
    }

    private static InetAddress[] queryEndpointsQtype(String key, String hostname, int qtype, SocketProtector protector) {
        return queryEndpointsInternal(key, hostname, System.currentTimeMillis(), false, qtype, protector, null);
    }

    private static InetAddress[] queryEndpointsInternal(String key, String hostname, long now,
                                                        boolean ignoreCut, int qtype,
                                                        SocketProtector protector,
                                                        ColgramNativeDesync.FdProtector fdProtector) {
        byte[] wireQuery;
        try {
            wireQuery = buildQuery(hostname, qtype);
        } catch (Throwable badName) {
            return null;
        }

        boolean preferGoogleEcs = prefersEcsDns(hostname);
        boolean isProtected = (protector != null || fdProtector != null
                || ColgramDpiBypass.activeSystemDpiVpn() != null);
        boolean allowIpv6 = hasRoutableIpv6ForCurrentSocket(isProtected);
        // 1. Fast path: pinned last-working endpoint with bounded budget (skip non-ECS pinned for YouTube/media)
        Endpoint pinned = lastWorkingEndpoint;
        Endpoint pinnedTried = null;
        if (pinned != null && (ignoreCut || !isCut(pinned.key(), now))
                && (allowIpv6 || pinned.ip.indexOf(':') < 0)
                && (!preferGoogleEcs || "google".equals(pinned.providerGroup))) {
            pinnedTried = pinned;
            try {
                long fastDeadline = System.currentTimeMillis() + (cellularNetwork ? 2400L : 1800L);
                byte[] reply = queryWire(pinned, wireQuery, protector, fdProtector, fastDeadline, null);
                if (reply != null && reply.length >= 12) {
                    cutUntil.remove(pinned.key());
                    String[] found = qtype == 28 ? parseRecords(reply, true) : parseARecords(reply);
                    if (found.length > 0) {
                        lastResolver = pinned.key();
                        if (qtype == 1) {
                            putDnsCacheEntry(key, found, System.currentTimeMillis());
                        }
                        return toAddresses(found);
                    }
                    // Valid empty AAAA answer on a healthy resolver
                    if (qtype == 28) return null;
                }
            } catch (Throwable t) {
                Log.i(TAG, pinned.key() + " cut or unreachable: " + t.getClass().getSimpleName());
                markCut(pinned.key(), System.currentTimeMillis());
                if (lastWorkingEndpoint == pinned) lastWorkingEndpoint = null;
            }
        }

        // 2. Concurrent waves (Google ECS first for YouTube/media, then 3-way diverse waves under 3.8s each)
        List<Endpoint> remaining = buildHealthyCandidates(pinnedTried, now, ignoreCut, preferGoogleEcs, allowIpv6);
        if (preferGoogleEcs) {
            List<Endpoint> ecsWave = extractGoogleWave(remaining, 3);
            if (!ecsWave.isEmpty()) {
                byte[] ecsReply = raceEndpointsWave(ecsWave, wireQuery, protector, fdProtector,
                        cellularNetwork ? 2500L : 2000L, qtype);
                if (ecsReply != null && ecsReply.length >= 12) {
                    String[] found = qtype == 28 ? parseRecords(ecsReply, true) : parseARecords(ecsReply);
                    if (found.length > 0) {
                        if (qtype == 1) {
                            putDnsCacheEntry(key, found, System.currentTimeMillis());
                        }
                        return toAddresses(found);
                    }
                    if (qtype == 28) return null;
                }
            }
        }
        for (int waveIdx = 0; waveIdx < 2 && !remaining.isEmpty(); waveIdx++) {
            List<Endpoint> wave = pickDiverseWave(remaining, 3);
            byte[] reply = raceEndpointsWave(wave, wireQuery, protector, fdProtector,
                    RACE_WAVE_DEADLINE_MS, qtype);
            if (reply != null && reply.length >= 12) {
                String[] found = qtype == 28 ? parseRecords(reply, true) : parseARecords(reply);
                if (found.length > 0) {
                    if (qtype == 1) {
                        cache.put(key, new long[]{System.currentTimeMillis()});
                        answers.put(key, found);
                    }
                    return toAddresses(found);
                }
                if (qtype == 28) return null;
            }
        }
        return null;
    }

    private static InetAddress[] toAddresses(String[] ips) {
        List<InetAddress> out = new ArrayList<>(ips.length);
        for (String ip : ips) {
            try {
                out.add(InetAddress.getByName(ip));
            } catch (Throwable ignored) {
                // A literal that will not parse is dropped; the rest of the answer still stands.
            }
        }
        return out.isEmpty() ? null : out.toArray(new InetAddress[0]);
    }

    /**
     * SSLSocketFactory that connects to an optional pinned IP (with optional VPN protection) using
     * Conscrypt engine-socket mode and carrier-safe multi-split SNI segmentation (zero MSG_OOB,
     * zero TTL=1, zero tiny MSS), defeating TSPU SNI-cuts while preserving full TLS verification.
     */
    public static javax.net.ssl.SSLSocketFactory createSniFragmentingFactory(
            final String pinnedIp, final String sniHost, final SocketProtector protector) throws Exception {
        final javax.net.ssl.SSLSocketFactory delegate = ColgramNativeDesync.createEngineSslSocketFactory();
        return new javax.net.ssl.SSLSocketFactory() {
            private java.net.Socket wrapFragmented(java.net.Socket raw, String host, int port, boolean autoClose)
                    throws java.io.IOException {
                raw.setTcpNoDelay(true);
                java.net.Socket sniFrag = (raw instanceof ColgramNativeDesync.NativeSocket)
                        ? ColgramNativeDesync.wrapForFirstTlsHello(
                                (ColgramNativeDesync.NativeSocket) raw,
                                ColgramNativeDesync.MODE_MULTI_SPLIT_SNI, 0, true, true, cellularNetwork, 0)
                        : new SniFragmentingSocket(raw);
                String targetSni = (sniHost != null && !sniHost.isEmpty()) ? sniHost : host;
                javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) delegate.createSocket(
                        sniFrag, targetSni, port, autoClose);
                hardenTlsSocket(tls);
                return tls;
            }

            private java.net.Socket openRaw(String host, int port) throws java.io.IOException {
                String targetIp = (pinnedIp != null && !pinnedIp.isEmpty()) ? pinnedIp : host;
                int timeoutMs = cellularNetwork ? CELLULAR_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS;
                android.net.VpnService sysVpn = (protector == null) ? ColgramDpiBypass.activeSystemDpiVpn() : null;
                if (ColgramNativeDesync.isAvailable() && isIpLiteral(targetIp) && (protector == null || sysVpn != null)) {
                    try {
                        ColgramNativeDesync.FdProtector fdProt = sysVpn != null ? sysVpn::protect : null;
                        return ColgramNativeDesync.connect(targetIp, port, timeoutMs, 0, 0, fdProt);
                    } catch (Throwable ignored) {
                        // Fall through to standard Java socket + SNI fragmentation
                    }
                }
                java.net.Socket raw = new java.net.Socket(java.net.Proxy.NO_PROXY);
                raw.setTcpNoDelay(true);
                if (protector != null && !protector.protect(raw)) {
                    try { raw.close(); } catch (Throwable ignored) {}
                    throw new java.io.IOException("DoH socket protection failed");
                } else if (sysVpn != null && !sysVpn.protect(raw)) {
                    try { raw.close(); } catch (Throwable ignored) {}
                    throw new java.io.IOException("System DPI socket protection failed");
                }
                raw.connect(new java.net.InetSocketAddress(targetIp, port), timeoutMs);
                return raw;
            }

            @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
            @Override public java.net.Socket createSocket() throws java.io.IOException {
                return delegate.createSocket();
            }
            @Override public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                    throws java.io.IOException {
                return wrapFragmented(s, host, port, autoClose);
            }
            @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
                return wrapFragmented(openRaw(host, port), host, port, true);
            }
            @Override public java.net.Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                    throws java.io.IOException {
                return wrapFragmented(openRaw(host, port), host, port, true);
            }
            @Override public java.net.Socket createSocket(InetAddress host, int port) throws java.io.IOException {
                return wrapFragmented(openRaw(host.getHostAddress(), port), host.getHostName(), port, true);
            }
            @Override public java.net.Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                    throws java.io.IOException {
                return wrapFragmented(openRaw(address.getHostAddress(), port), address.getHostName(), port, true);
            }
        };
    }

    /** Transparent wrapper that splits only the very first TLS ClientHello across its SNI midpoint. */
    private static final class SniFragmentingSocket extends java.net.Socket {
        private final java.net.Socket base;
        private volatile OutputStream wrappedOut;

        SniFragmentingSocket(java.net.Socket base) { this.base = base; }
        @Override public InputStream getInputStream() throws java.io.IOException { return base.getInputStream(); }
        @Override public void connect(java.net.SocketAddress ep, int timeout) throws java.io.IOException { base.connect(ep, timeout); }
        @Override public boolean isConnected() { return base.isConnected(); }
        @Override public boolean isBound() { return base.isBound(); }
        @Override public boolean isClosed() { return base.isClosed(); }
        @Override public boolean isInputShutdown() { return base.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return base.isOutputShutdown(); }
        @Override public void shutdownInput() throws java.io.IOException { base.shutdownInput(); }
        @Override public void shutdownOutput() throws java.io.IOException { base.shutdownOutput(); }
        @Override public void setSoTimeout(int timeout) throws java.net.SocketException { base.setSoTimeout(timeout); }
        @Override public int getSoTimeout() throws java.net.SocketException { return base.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean on) throws java.net.SocketException { base.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws java.net.SocketException { return base.getTcpNoDelay(); }
        @Override public void setSoLinger(boolean on, int linger) throws java.net.SocketException { base.setSoLinger(on, linger); }
        @Override public int getSoLinger() throws java.net.SocketException { return base.getSoLinger(); }
        @Override public void setReuseAddress(boolean on) throws java.net.SocketException { base.setReuseAddress(on); }
        @Override public boolean getReuseAddress() throws java.net.SocketException { return base.getReuseAddress(); }
        @Override public void setKeepAlive(boolean on) throws java.net.SocketException { base.setKeepAlive(on); }
        @Override public boolean getKeepAlive() throws java.net.SocketException { return base.getKeepAlive(); }
        @Override public void setSendBufferSize(int size) throws java.net.SocketException { base.setSendBufferSize(size); }
        @Override public int getSendBufferSize() throws java.net.SocketException { return base.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int size) throws java.net.SocketException { base.setReceiveBufferSize(size); }
        @Override public int getReceiveBufferSize() throws java.net.SocketException { return base.getReceiveBufferSize(); }
        @Override public void setOOBInline(boolean on) throws java.net.SocketException { base.setOOBInline(on); }
        @Override public boolean getOOBInline() throws java.net.SocketException { return base.getOOBInline(); }
        @Override public InetAddress getInetAddress() { return base.getInetAddress(); }
        @Override public int getPort() { return base.getPort(); }
        @Override public InetAddress getLocalAddress() { return base.getLocalAddress(); }
        @Override public int getLocalPort() { return base.getLocalPort(); }
        @Override public java.net.SocketAddress getRemoteSocketAddress() { return base.getRemoteSocketAddress(); }
        @Override public java.net.SocketAddress getLocalSocketAddress() { return base.getLocalSocketAddress(); }
        @Override public void close() throws java.io.IOException { base.close(); }

        @Override
        public OutputStream getOutputStream() throws java.io.IOException {
            OutputStream existing = wrappedOut;
            if (existing != null) return existing;
            final OutputStream raw = base.getOutputStream();
            OutputStream fresh = new OutputStream() {
                private boolean firstHelloSent;

                @Override public synchronized void write(int b) throws java.io.IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override public synchronized void write(byte[] b, int off, int len) throws java.io.IOException {
                    if (!firstHelloSent && len >= 11 && (b[off] & 0xFF) == 22 && b[off + 1] == 3 && (b[off + 5] & 0xFF) == 1) {
                        firstHelloSent = true;
                        byte[] copy = new byte[len];
                        System.arraycopy(b, off, copy, 0, len);
                        byte[][] pieces = ColgramTlsMimic.fragment(copy, new java.util.Random());
                        for (int i = 0; i < pieces.length; i++) {
                            raw.write(pieces[i]);
                            raw.flush();
                            if (i + 1 < pieces.length) {
                                try { Thread.sleep(2L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            }
                        }
                        return;
                    }
                    firstHelloSent = true;
                    raw.write(b, off, len);
                }

                @Override public void flush() throws java.io.IOException { raw.flush(); }
                @Override public void close() throws java.io.IOException { raw.close(); }
            };
            wrappedOut = fresh;
            return fresh;
        }
    }

    /**
     * SSLSocketFactory that preserves the real TLS SNI hostname when connecting via a pinned DoH IP
     * over our local SOCKS5 DPI proxy (127.0.0.1), using Conscrypt engine-socket mode.
     */
    public static javax.net.ssl.SSLSocketFactory createSniOverrideFactory(final String sniHost) throws Exception {
        final javax.net.ssl.SSLSocketFactory delegate = ColgramNativeDesync.createEngineSslSocketFactory();
        return new javax.net.ssl.SSLSocketFactory() {
            private String effectiveHost(String host) {
                return (sniHost != null && !sniHost.isEmpty()) ? sniHost : host;
            }
            @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
            @Override public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                    throws java.io.IOException {
                javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) delegate.createSocket(
                        s, effectiveHost(host), port, autoClose);
                hardenTlsSocket(tls);
                return tls;
            }
            @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
                return delegate.createSocket(host, port);
            }
            @Override public java.net.Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                    throws java.io.IOException {
                return delegate.createSocket(host, port, localHost, localPort);
            }
            @Override public java.net.Socket createSocket(InetAddress host, int port) throws java.io.IOException {
                return delegate.createSocket(host, port);
            }
            @Override public java.net.Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                    throws java.io.IOException {
                return delegate.createSocket(address, port, localAddress, localPort);
            }
        };
    }

    private static byte[] queryWire(Endpoint endpoint, byte[] request,
                                    SocketProtector protector,
                                    ColgramNativeDesync.FdProtector fdProtector,
                                    long deadlineAt,
                                    List<java.net.Socket> activeSockets) throws Exception {
        if (protector == null && fdProtector == null) {
            android.net.VpnService sysVpn = ColgramDpiBypass.activeSystemDpiVpn();
            if (sysVpn != null) {
                protector = sysVpn::protect;
                fdProtector = sysVpn::protect;
            }
        }
        String poolKey = endpoint.key() + "|" + ((protector != null || fdProtector != null) ? "P" : "D");
        byte[] pooledReply = tryPooledQuery(endpoint, poolKey, request, deadlineAt, activeSockets);
        if (pooledReply != null && pooledReply.length >= 12) {
            return pooledReply;
        }
        boolean canNative = ColgramNativeDesync.isAvailable() && (protector == null || fdProtector != null);
        // Spoofed RU-whitelist SNI (vk.com / max.ru) first on endpoints proven to accept it while
        // still verifying the real CA-signed certificate against endpoint.verifyName!
        if (endpoint.supportsSpoofedSni) {
            String primarySni = SPOOFED_RU_SNIS[0];
            try {
                byte[] res = querySingleAttempt(endpoint, request, protector, fdProtector,
                        primarySni, canNative, deadlineAt, activeSockets);
                if (res != null && res.length >= 12) return res;
            } catch (TcpConnectException tcpFail) {
                // Fail fast: never retry TLS modes against a TCP-unreachable IP!
                throw tcpFail;
            } catch (Throwable tlsOrHttpErr) {
                // Fall back once to real SNI (or secondary spoofed SNI for AliDNS) if time remains
                if (System.currentTimeMillis() + 500L >= deadlineAt) {
                    throw tlsOrHttpErr instanceof Exception ? (Exception) tlsOrHttpErr
                            : new java.io.IOException(tlsOrHttpErr);
                }
            }
            String fallbackSni = "alidns".equals(endpoint.providerGroup) ? SPOOFED_RU_SNIS[1] : endpoint.sni;
            return querySingleAttempt(endpoint, request, protector, fdProtector,
                    fallbackSni, false, deadlineAt, activeSockets);
        }

        // Real-SNI-only endpoints (AdGuard, ControlD): carrier-safe MODE_MULTI_SPLIT_SNI first, then Java SNI split
        if (canNative) {
            try {
                byte[] res = querySingleAttempt(endpoint, request, protector, fdProtector,
                        endpoint.sni, true, deadlineAt, activeSockets);
                if (res != null && res.length >= 12) return res;
            } catch (TcpConnectException tcpFail) {
                throw tcpFail;
            } catch (Throwable tlsOrHttpErr) {
                if (System.currentTimeMillis() + 500L >= deadlineAt) {
                    throw tlsOrHttpErr instanceof Exception ? (Exception) tlsOrHttpErr
                            : new java.io.IOException(tlsOrHttpErr);
                }
            }
        }
        return querySingleAttempt(endpoint, request, protector, fdProtector,
                endpoint.sni, false, deadlineAt, activeSockets);
    }

    private static byte[] tryPooledQuery(Endpoint endpoint, String poolKey, byte[] request,
                                         long deadlineAt, List<java.net.Socket> activeSockets) {
        PooledTlsConn conn = pollPooledConn(poolKey);
        if (conn == null) return null;
        long remaining = deadlineAt - System.currentTimeMillis();
        if (remaining < 200L) {
            returnPooledConn(conn);
            return null;
        }
        boolean tracked = false;
        try {
            int readTimeout = (int) Math.min(
                    cellularNetwork ? CELLULAR_READ_TIMEOUT_MS : READ_TIMEOUT_MS,
                    Math.max(300L, remaining));
            conn.raw.setSoTimeout(readTimeout);
            conn.ssl.setSoTimeout(readTimeout);
            if (activeSockets != null) {
                synchronized (activeSockets) {
                    activeSockets.add(conn.raw);
                }
                tracked = true;
            }
            sendWireRequest(endpoint, conn.out, request, true);
            boolean[] ka = new boolean[1];
            byte[] reply = endpoint.dot
                    ? readDotResponse(conn.in)
                    : readHttp11KeepAliveResponse(conn.in, ka);
            boolean keepAlive = endpoint.dot || ka[0];
            boolean openAfter = !conn.raw.isClosed() && !conn.ssl.isClosed();
            if (tracked) {
                synchronized (activeSockets) {
                    activeSockets.remove(conn.raw);
                    openAfter = !conn.raw.isClosed() && !conn.ssl.isClosed();
                }
                tracked = false;
            }
            if (reply != null && reply.length >= 12) {
                if (keepAlive && openAfter) {
                    returnPooledConn(conn);
                } else {
                    conn.closeQuietly();
                }
                return reply;
            }
            conn.closeQuietly();
            return null;
        } catch (Throwable ignored) {
            if (tracked && activeSockets != null) {
                synchronized (activeSockets) {
                    activeSockets.remove(conn.raw);
                }
            }
            conn.closeQuietly();
            return null;
        }
    }

    private static void sendWireRequest(Endpoint endpoint, OutputStream out,
                                        byte[] request, boolean keepAlive) throws java.io.IOException {
        if (endpoint.dot) {
            byte[] framed = new byte[2 + request.length];
            framed[0] = (byte) ((request.length >>> 8) & 0xFF);
            framed[1] = (byte) (request.length & 0xFF);
            System.arraycopy(request, 0, framed, 2, request.length);
            out.write(framed);
            out.flush();
            return;
        }
        String hdr = "POST /dns-query HTTP/1.1\r\n"
                + "Host: " + endpoint.sni + "\r\n"
                + "Content-Type: application/dns-message\r\n"
                + "Accept: application/dns-message\r\n"
                + "Content-Length: " + request.length + "\r\n"
                + "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n\r\n";
        out.write(hdr.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(request);
        out.flush();
    }

    private static byte[] querySingleAttempt(Endpoint endpoint, byte[] request,
                                             SocketProtector protector,
                                             ColgramNativeDesync.FdProtector fdProtector,
                                             String tlsSni, boolean useNativeMultiSplit,
                                             long deadlineAt,
                                             List<java.net.Socket> activeSockets) throws Exception {
        long remaining = deadlineAt - System.currentTimeMillis();
        if (remaining < 350L) {
            throw new TcpConnectException("DoH/DoT deadline exceeded for " + endpoint.key());
        }
        int connectTimeout = (int) Math.min(
                cellularNetwork ? CELLULAR_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS,
                Math.max(400L, remaining));
        String poolKey = endpoint.key() + "|" + ((protector != null || fdProtector != null) ? "P" : "D");
        java.net.Socket raw = null;
        javax.net.ssl.SSLSocket ssl = null;
        try {
            java.net.Socket transport;
            if (useNativeMultiSplit && ColgramNativeDesync.isAvailable() && (protector == null || fdProtector != null)) {
                ColgramNativeDesync.NativeSocket nativeSock;
                try {
                    nativeSock = ColgramNativeDesync.connect(
                            endpoint.ip, endpoint.port, connectTimeout, 0, 0, fdProtector);
                } catch (Throwable connectErr) {
                    throw new TcpConnectException("Native TCP connect failed to " + endpoint.key(), connectErr);
                }
                raw = nativeSock;
                if (activeSockets != null) {
                    synchronized (activeSockets) {
                        activeSockets.add(raw);
                    }
                }
                transport = ColgramNativeDesync.wrapForFirstTlsHello(
                        nativeSock, ColgramNativeDesync.MODE_MULTI_SPLIT_SNI, 0, true, true, cellularNetwork, 0);
            } else {
                raw = new java.net.Socket(java.net.Proxy.NO_PROXY);
                raw.setTcpNoDelay(true);
                if (protector != null && !protector.protect(raw)) {
                    throw new TcpConnectException("DoH/DoT socket protection failed for " + endpoint.key());
                }
                try {
                    raw.connect(new java.net.InetSocketAddress(endpoint.ip, endpoint.port), connectTimeout);
                } catch (Throwable connectErr) {
                    throw new TcpConnectException("TCP connect failed to " + endpoint.key(), connectErr);
                }
                if (activeSockets != null) {
                    synchronized (activeSockets) {
                        activeSockets.add(raw);
                    }
                }
                transport = new SniFragmentingSocket(raw);
            }

            long remAfterConnect = deadlineAt - System.currentTimeMillis();
            if (remAfterConnect < 250L) {
                throw new java.net.SocketTimeoutException("DoH/DoT post-connect deadline exceeded");
            }
            int readTimeout = (int) Math.min(
                    cellularNetwork ? CELLULAR_READ_TIMEOUT_MS : READ_TIMEOUT_MS,
                    Math.max(350L, remAfterConnect));
            raw.setSoTimeout(readTimeout);

            javax.net.ssl.SSLSocketFactory engineFactory = ColgramNativeDesync.createEngineSslSocketFactory();
            ssl = (javax.net.ssl.SSLSocket) engineFactory.createSocket(transport, tlsSni, endpoint.port, true);
            hardenTlsSocket(ssl);
            ssl.setSoTimeout(readTimeout);
            ssl.startHandshake();
            if (!matches(ssl.getSession(), endpoint.verifyName)) {
                throw new javax.net.ssl.SSLPeerUnverifiedException("DoH/DoT cert mismatch for " + endpoint.verifyName);
            }

            OutputStream out = ssl.getOutputStream();
            java.io.BufferedInputStream bin = new java.io.BufferedInputStream(ssl.getInputStream(), 4096);
            sendWireRequest(endpoint, out, request, true);

            boolean[] ka = new boolean[1];
            byte[] reply = endpoint.dot
                    ? readDotResponse(bin)
                    : readHttp11KeepAliveResponse(bin, ka);
            boolean keepAlive = endpoint.dot || ka[0];
            if (reply != null && reply.length >= 12 && keepAlive) {
                boolean openAfter = !raw.isClosed() && !ssl.isClosed();
                if (activeSockets != null) {
                    synchronized (activeSockets) {
                        activeSockets.remove(raw);
                        openAfter = !raw.isClosed() && !ssl.isClosed();
                    }
                }
                if (openAfter) {
                    returnPooledConn(new PooledTlsConn(
                            poolKey, raw, ssl, bin, out, endpoint.dot, System.currentTimeMillis()));
                    ssl = null;
                    raw = null;
                }
            }
            return reply;
        } finally {
            if (activeSockets != null && raw != null) {
                synchronized (activeSockets) {
                    activeSockets.remove(raw);
                }
            }
            if (ssl != null) { try { ssl.close(); } catch (Throwable ignored) {} }
            if (raw != null) { try { raw.close(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Reads one HTTP/1.1 (or HTTP/1.0) DoH response with exact Content-Length / chunked framing
     * so persistent TLS sockets can be returned cleanly to the Keep-Alive pool.
     */
    static byte[] readHttp11KeepAliveResponse(InputStream in, boolean[] outKeepAlive) throws java.io.IOException {
        if (outKeepAlive != null && outKeepAlive.length > 0) outKeepAlive[0] = false;
        ByteArrayOutputStream hdrBuf = new ByteArrayOutputStream(512);
        int match = 0;
        while (hdrBuf.size() < 16384) {
            int b = in.read();
            if (b < 0) return null;
            hdrBuf.write(b);
            if (b == '\r') {
                match = (match == 2) ? 3 : 1;
            } else if (b == '\n') {
                if (match == 1) match = 2;
                else if (match == 3) break;
                else match = 0;
            } else {
                match = 0;
            }
        }
        if (match != 3 || hdrBuf.size() < 4) return null;
        byte[] hdrBytes = hdrBuf.toByteArray();
        String headStr = new String(hdrBytes, 0, hdrBytes.length - 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        String[] lines = headStr.split("\\r?\\n");
        if (lines.length == 0) return null;
        String statusLine = lines[0];
        if (!statusLine.contains(" 200")) return null;
        boolean http10 = statusLine.startsWith("HTTP/1.0");
        boolean chunked = false;
        boolean connClose = false;
        boolean connKeepAlive = false;
        int contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String val = line.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                try {
                    int parsed = Integer.parseInt(val);
                    if (parsed >= 0) contentLength = parsed;
                } catch (NumberFormatException ignored) {}
            } else if ("transfer-encoding".equals(name)) {
                if (val.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            } else if ("connection".equals(name)) {
                String lv = val.toLowerCase(java.util.Locale.ROOT);
                if (lv.contains("close")) connClose = true;
                if (lv.contains("keep-alive")) connKeepAlive = true;
            }
        }
        boolean canKeepAlive = http10 ? connKeepAlive : !connClose;
        if (chunked) {
            byte[] body = readChunkedBodyKeepAlive(in);
            if (body == null) return null;
            if (outKeepAlive != null && outKeepAlive.length > 0) outKeepAlive[0] = canKeepAlive;
            return body;
        }
        if (contentLength >= 0) {
            if (contentLength > 65536) return null;
            byte[] body = readExactBytes(in, contentLength);
            if (body == null) return null;
            if (outKeepAlive != null && outKeepAlive.length > 0) outKeepAlive[0] = canKeepAlive;
            return body;
        }
        ByteArrayOutputStream rawBody = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            int toWrite = Math.min(n, 65536 - rawBody.size());
            if (toWrite > 0) rawBody.write(buf, 0, toWrite);
            if (rawBody.size() >= 65536) break;
        }
        return rawBody.toByteArray();
    }

    private static byte[] readExactBytes(InputStream in, int len) throws java.io.IOException {
        byte[] out = new byte[len];
        int pos = 0;
        while (pos < len) {
            int r = in.read(out, pos, len - pos);
            if (r < 0) return null;
            pos += r;
        }
        return out;
    }

    private static byte[] readChunkedBodyKeepAlive(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLineCrlf(in, 256);
            if (sizeLine == null) return null;
            String hex = sizeLine.trim();
            int semi = hex.indexOf(';');
            if (semi >= 0) hex = hex.substring(0, semi).trim();
            if (hex.isEmpty()) return null;
            int chunkLen;
            try {
                chunkLen = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                return null;
            }
            if (chunkLen < 0) return null;
            if (chunkLen == 0) {
                while (true) {
                    String trailer = readLineCrlf(in, 1024);
                    if (trailer == null) return null;
                    if (trailer.isEmpty()) break;
                }
                return out.toByteArray();
            }
            if (out.size() + chunkLen > 65536) return null;
            byte[] chunk = readExactBytes(in, chunkLen);
            if (chunk == null) return null;
            out.write(chunk);
            String crlf = readLineCrlf(in, 16);
            if (crlf == null || !crlf.isEmpty()) return null;
        }
    }

    private static String readLineCrlf(InputStream in, int maxLen) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(32);
        while (sb.length() < maxLen) {
            int b = in.read();
            if (b < 0) return null;
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) (b & 0xFF));
        }
        return null;
    }

    /** Reads a single RFC 7858 DNS-over-TLS length-prefixed response frame. */
    static byte[] readDotResponse(InputStream in) throws java.io.IOException {
        byte[] lenBuf = new byte[2];
        int got = 0;
        while (got < 2) {
            int r = in.read(lenBuf, got, 2 - got);
            if (r < 0) return null;
            got += r;
        }
        int respLen = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
        if (respLen < 12 || respLen > 65535) return null;
        byte[] msg = new byte[respLen];
        int pos = 0;
        while (pos < respLen) {
            int r = in.read(msg, pos, respLen - pos);
            if (r < 0) return null;
            pos += r;
        }
        return msg;
    }

    /**
     * RFC 6125 strict hostname / IP verification against X509 SANs (and Subject CN only when SANs are absent).
     */
    private static boolean matches(SSLSession session, String name) {
        try {
            java.security.cert.Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0) return false;
            if (!(chain[0] instanceof java.security.cert.X509Certificate)) return false;
            return matchesCertificateName((java.security.cert.X509Certificate) chain[0], name);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean matchesCertificateName(java.security.cert.X509Certificate cert, String expected) {
        if (cert == null || expected == null || expected.trim().isEmpty()) return false;
        String host = expected.trim().toLowerCase(java.util.Locale.ROOT);
        boolean expectingIp = isIpLiteral(host);
        boolean hasDnsSan = false;
        try {
            java.util.Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> entry : sans) {
                    if (entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer)) continue;
                    int type = (Integer) entry.get(0);
                    Object val = entry.get(1);
                    if (!(val instanceof String)) continue;
                    String sanVal = ((String) val).trim().toLowerCase(java.util.Locale.ROOT);
                    if (type == 2) {
                        hasDnsSan = true;
                        if (!expectingIp && matchesDnsPattern(sanVal, host)) return true;
                    } else if (type == 7 && expectingIp && host.equals(sanVal)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        if (hasDnsSan || expectingIp) return false;
        String cn = extractCommonName(cert.getSubjectX500Principal().getName(
                javax.security.auth.x500.X500Principal.RFC2253));
        return cn != null && matchesDnsPattern(cn.toLowerCase(java.util.Locale.ROOT), host);
    }

    static boolean matchesDnsPattern(String pattern, String host) {
        if (pattern == null || host == null || pattern.isEmpty() || host.isEmpty()) return false;
        if (pattern.equals(host)) return true;
        if (!pattern.startsWith("*.")) return false;
        String suffix = pattern.substring(2);
        // Reject public suffix wildcards like "*.com" or empty suffixes
        if (suffix.isEmpty() || suffix.indexOf('.') < 0 || suffix.startsWith("*")) return false;
        int dot = host.indexOf('.');
        if (dot <= 0) return false;
        String tail = host.substring(dot + 1);
        return suffix.equals(tail);
    }

    static String extractCommonName(String dn) {
        if (dn == null || dn.isEmpty()) return null;
        int i = 0;
        int len = dn.length();
        while (i < len) {
            while (i < len && (dn.charAt(i) == ',' || dn.charAt(i) == '+' || dn.charAt(i) == ' ')) i++;
            int eq = dn.indexOf('=', i);
            if (eq <= i) break;
            String attr = dn.substring(i, eq).trim();
            StringBuilder val = new StringBuilder();
            i = eq + 1;
            while (i < len) {
                char c = dn.charAt(i);
                if (c == '\\' && i + 1 < len) {
                    val.append(dn.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == ',' || c == '+') break;
                val.append(c);
                i++;
            }
            if ("CN".equalsIgnoreCase(attr)) {
                return val.toString().trim();
            }
        }
        return null;
    }

    /** Harden an SSLSocket with modern TLS protocols when supported by the runtime. */
    public static void hardenTlsSocket(javax.net.ssl.SSLSocket ssl) {
        if (ssl == null) return;
        try {
            String[] supported = ssl.getSupportedProtocols();
            if (supported == null) return;
            List<String> preferred = new ArrayList<>(2);
            for (String p : supported) {
                if ("TLSv1.3".equals(p)) preferred.add(0, p);
                else if ("TLSv1.2".equals(p)) preferred.add(p);
            }
            if (!preferred.isEmpty()) {
                ssl.setEnabledProtocols(preferred.toArray(new String[0]));
            }
        } catch (Throwable ignored) {
        }
    }

    /** A minimal wire-format DNS query (qtype 1 = A, 28 = AAAA). */
    private static byte[] buildQuery(String hostname, int qtype) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        int id = (int) (System.nanoTime() & 0xffff);
        body.write(id >> 8); body.write(id & 0xff);    // ID
        body.write(0x01); body.write(0x00);            // flags: recursion desired
        body.write(0x00); body.write(0x01);            // QDCOUNT = 1
        body.write(0x00); body.write(0x00);            // ANCOUNT = 0
        body.write(0x00); body.write(0x00);            // NSCOUNT = 0
        body.write(0x00); body.write(0x00);            // ARCOUNT = 0
        for (String label : hostname.split("\\.")) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IllegalArgumentException("bad label in " + hostname);
            }
            body.write(bytes.length);
            body.write(bytes, 0, bytes.length);
        }
        body.write(0x00);                              // root
        body.write((qtype >> 8) & 0xff); body.write(qtype & 0xff); // QTYPE
        body.write(0x00); body.write(0x01);            // QCLASS IN
        return body.toByteArray();
    }

    /** Extract A records from a wire-format reply. */
    static String[] parseARecords(byte[] message) {
        return parseRecords(message, false);
    }

    /** Extract A (or AAAA when ipv6Only=true) records from a wire-format reply. */
    static String[] parseRecords(byte[] message, boolean ipv6Only) {
        List<String> found = new ArrayList<>();
        if (message == null || message.length < 12) return new String[0];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(message);
        buffer.position(4);
        int questions = buffer.getShort() & 0xffff;
        int answers = buffer.getShort() & 0xffff;
        buffer.position(12);
        try {
            for (int i = 0; i < questions; i++) {
                skipName(buffer);
                if (buffer.remaining() < 4) break;
                buffer.position(buffer.position() + 4); // QTYPE + QCLASS
            }
            for (int i = 0; i < answers; i++) {
                if (buffer.remaining() < 11) {
                    break;
                }
                skipName(buffer);
                if (buffer.remaining() < 10) {
                    break;
                }
                int type = buffer.getShort() & 0xffff;
                int clazz = buffer.getShort() & 0xffff;
                long ttl = buffer.getInt() & 0xffffffffL;
                int length = buffer.getShort() & 0xffff;
                int start = buffer.position();
                if (start + length > buffer.limit()) {
                    break;
                }
                if (!ipv6Only && type == 1 && clazz == 1 && length == 4) {
                    String ip = (buffer.get() & 0xff) + "." + (buffer.get() & 0xff) + "."
                            + (buffer.get() & 0xff) + "." + (buffer.get() & 0xff);
                    if (!found.contains(ip)) found.add(ip);
                } else if (ipv6Only && type == 28 && clazz == 1 && length == 16) {
                    byte[] raw6 = new byte[16];
                    buffer.get(raw6);
                    String ip6 = InetAddress.getByAddress(raw6).getHostAddress();
                    if (!found.contains(ip6)) found.add(ip6);
                }
                buffer.position(start + length);
            }
        } catch (Throwable t) {
            Log.i(TAG, "reply parse stopped early: " + t.getClass().getSimpleName());
        }
        return found.toArray(new String[0]);
    }

    /** Skip a possibly compressed name. */
    private static void skipName(java.nio.ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            int len = buffer.get() & 0xff;
            if (len == 0) return;
            if ((len & 0xc0) == 0xc0) {
                if (buffer.hasRemaining()) buffer.get();
                return;
            }
            if (buffer.remaining() < len) {
                buffer.position(buffer.limit());
                return;
            }
            buffer.position(buffer.position() + len);
        }
    }
}
