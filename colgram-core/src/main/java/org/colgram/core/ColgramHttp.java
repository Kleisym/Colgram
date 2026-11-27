package org.colgram.core;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * ColgramHttp — HTTP GET with a relay fallback.
 *
 * Why this exists: on the network Colgram is developed against, whole IP ranges are dropped
 * silently while ordinary internet still works. That is per-address blocking, so DNS-over-HTTPS
 * does not help - the name resolves fine and the connection to the answer is what dies. Measured
 * on 2026-09-22: api.mail.tm resolves to 49.12.20.211 and times out at TCP, while
 * api.guerrillamail.com on another address answers in 243 ms.
 *
 * The way through a per-IP block without a VPN is a relay, and Colgram already harvests hundreds
 * of public SOCKS5 proxies. This class uses them: direct first, then the proxy the user applied,
 * then the pool. Every attempt is reported, so a failure says which hosts were unreachable
 * instead of returning nothing.
 *
 * Only SOCKS5 can be used this way. A plain Java HttpURLConnection cannot tunnel through an
 * MTProto or a WebSocket proxy - those are understood by tgnet, not by the JDK - so when the
 * applied proxy is one of those, the pool's SOCKS5 entries are what carry the request.
 */
public final class ColgramHttp {

    private static final String TAG = "ColgramHttp";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    /** Public source feeds are expendable; one stalled mirror must not stall the entire refresh. */
    private static final int FAST_CONNECT_TIMEOUT_MS = 1800;
    private static final int FAST_READ_TIMEOUT_MS = 2800;
    private static final int MAX_FAST_RELAY_ATTEMPTS = 2;
    private static final int MAX_FEED_BYTES = 1024 * 1024;
    /** How many relay candidates one request is allowed to walk before giving up. */
    private static final int MAX_RELAY_ATTEMPTS = 4;

    private ColgramHttp() {}

    /** Response plus the HTTP status, because callers need to tell 4xx from a transport failure. */
    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static boolean preferProxyFirst() {
        String relayUrl = ColgramConfig.getRelayUrl();
        return ColgramConfig.isStrictProxySecurityEnabled()
                || ColgramConfig.isMaxAnonymityEnabled()
                || ColgramDpiBypass.isBound()
                || ColgramProxyManager.hasActiveRouteForUi()
                || (relayUrl != null && !relayUrl.trim().isEmpty());
    }

    /**
     * GET a URL, routing through active proxy/DPI tunnel first when configured or strict mode is
     * active, falling back through relays or direct DoH-pinned route only when permitted.
     */
    public static Response get(String urlStr) throws IOException {
        return executeWithRelays(urlStr, null, null);
    }

    private static Response executeWithRelays(String urlStr, String jsonBody, String bearer) throws IOException {
        List<String> attempts = new ArrayList<>();
        boolean proxyFirst = preferProxyFirst();
        if (!proxyFirst && !ColgramConfig.isMaxAnonymityEnabled()) {
            try {
                return open(urlStr, null, jsonBody, bearer);
            } catch (Throwable t) {
                attempts.add("напрямую: " + reason(t));
            }
        }

        Response configured = viaConfiguredRelay(urlStr, jsonBody, bearer, attempts);
        if (configured != null) return configured;

        List<ColgramProxyManager.ProxyItem> relays = relayCandidates();
        int used = 0;
        for (ColgramProxyManager.ProxyItem relay : relays) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                Response r = open(urlStr, relay, jsonBody, bearer);
                Log.i(TAG, "fetched " + hostOf(urlStr) + " through relay " + relay.address
                        + ":" + relay.port);
                return r;
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }
        Response tunneled = viaConnectRelays(urlStr, jsonBody, bearer, attempts);
        if (tunneled != null) return tunneled;

        if (proxyFirst && !ColgramConfig.isMaxAnonymityEnabled()) {
            try {
                return open(urlStr, null, jsonBody, bearer);
            } catch (Throwable t) {
                attempts.add("напрямую: " + reason(t));
            }
        }
        if (relays.isEmpty() && attempts.isEmpty()) {
            attempts.add("нет доступного прокси/релея");
        }
        throw new IOException(join(attempts));
    }

    /**
     * Short-budget GET for public candidate feeds. Routes via configured proxy/DPI tunnel first
     * when active or in strict mode.
     */
    public static Response getFast(String urlStr) throws IOException {
        List<String> attempts = new ArrayList<>();
        boolean proxyFirst = preferProxyFirst();
        if (!proxyFirst && !ColgramConfig.isMaxAnonymityEnabled()) {
            try {
                return openFast(urlStr, null);
            } catch (Throwable t) {
                attempts.add("напрямую: " + reason(t));
            }
        }

        Response configured = viaConfiguredRelayFast(urlStr, attempts);
        if (configured != null) return configured;

        int used = 0;
        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
        for (ColgramProxyManager.ProxyItem relay : relayCandidates()) {
            boolean userSelected = relay != null && relay.equals(active);
            if (relay == null || (!relay.isLocalDpi() && !userSelected && !relay.isAvailable)) continue;
            if (used++ >= MAX_FAST_RELAY_ATTEMPTS) break;
            try {
                return openFast(urlStr, relay);
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }

        int connectAttempts = 0;
        if (allowThirdPartyRelays()) {
            for (ColgramProxyChain.Relay relay : ColgramProxyManager.getReachableRelays()) {
                if (relay == null || relay.rttMs < 0) continue;
                if (ColgramConfig.isStrictProxySecurityEnabled()
                        && !urlStr.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                    break;
                }
                if (connectAttempts++ >= 1) break;
                try {
                    Response response = openFastConnection(urlStr, new Proxy(
                            relay.socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                            new InetSocketAddress(relay.host, relay.port)));
                    if (response.code >= 200 && response.code < 300) return response;
                    attempts.add(relay.key() + " -> HTTP " + response.code);
                } catch (Throwable t) {
                    attempts.add(relay.key() + " -> " + reason(t));
                }
            }
        }
        if (proxyFirst && !ColgramConfig.isMaxAnonymityEnabled()) {
            try {
                return openFast(urlStr, null);
            } catch (Throwable t) {
                attempts.add("напрямую: " + reason(t));
            }
        }
        throw new IOException(join(attempts));
    }

    private static Response viaConfiguredRelayFast(String urlStr, List<String> attempts) {
        String relay = ColgramConfig.getRelayUrl();
        if (relay == null || relay.trim().isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            String target = URLEncoder.encode(urlStr, "UTF-8");
            String sep = relay.contains("?") ? "&" : "?";
            conn = (HttpURLConnection) new URL(relay + sep + "url=" + target).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(FAST_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(FAST_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readBounded(stream);
            if (code >= 200 && code < 300) return new Response(code, body);
            attempts.add("реле-адрес: HTTP " + code);
        } catch (Throwable t) {
            attempts.add("реле-адрес: " + reason(t));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static Response openFast(String urlStr, ColgramProxyManager.ProxyItem relay) throws Exception {
        Proxy proxy = relay == null ? Proxy.NO_PROXY : new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(relay.address, relay.port));
        return openFastConnection(urlStr, proxy);
    }

    private static Response openFastConnection(String urlStr, Proxy proxy) throws Exception {
        // Standard HTTP framing handles chunked responses without returning chunk headers as data.
        URL url = new URL(urlStr);
        boolean externalProxy = proxy != null && proxy.type() != Proxy.Type.DIRECT;
        if (externalProxy && ColgramConfig.isStrictProxySecurityEnabled()
                && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("в строгом режиме через чужой прокси разрешён только HTTPS");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy != null ? proxy : Proxy.NO_PROXY);
        if (externalProxy && ColgramConfig.isStrictProxySecurityEnabled()) {
            conn.setInstanceFollowRedirects(false);
        }
        if (conn instanceof javax.net.ssl.HttpsURLConnection && externalProxy) {
            java.net.InetAddress viaProxy = ColgramDohResolver.resolveOrNull(url.getHost());
            conn = ColgramPinnedConnection.create(
                    (javax.net.ssl.HttpsURLConnection) conn, url, viaProxy, proxy);
        }
        try {
            conn.setConnectTimeout(FAST_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(FAST_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readBounded(stream);
            return new Response(code, body);
        } finally {
            conn.disconnect();
        }
    }

    private static String readBounded(InputStream stream) throws IOException {
        return ColgramProxyPolicy.readBounded(stream, MAX_FEED_BYTES);
    }

    /** POST a JSON body, with the same proxy-first and relay fallback as GET. */
    public static Response post(String urlStr, String jsonBody, String bearer) throws IOException {
        return executeWithRelays(urlStr, jsonBody, bearer);
    }

    /** GET with an optional bearer token. */
    public static Response get(String urlStr, String bearer) throws IOException {
        return executeWithRelays(urlStr, null, bearer);
    }

    /**
     * A user-supplied fetch relay, tried before anything else.
     *
     * On a network that drops api.telegram.org by IP and where every public SOCKS list is dead,
     * the one route that reliably works is a tiny forwarder on a host the block will not touch:
     * a Cloudflare Worker on <name>.workers.dev. It is not a proxy in the tgnet sense - it
     * forwards one HTTPS request at a time - so it cannot carry Telegram itself, but it carries
     * the Bot API, the mail providers and proxy-list fetches, which is where "сеть недоступна"
     * actually bit. The worker source ships in the repo (deploy/colgram-relay-worker.js); pasting
     * its URL into settings is the whole setup.
     */
    private static Response viaConfiguredRelay(String urlStr, String jsonBody, String bearer,
                                               List<String> attempts) {
        String relay = ColgramConfig.getRelayUrl();
        if (relay == null || relay.trim().isEmpty()) return null;
        try {
            String target = URLEncoder.encode(urlStr, "UTF-8");
            String sep = relay.contains("?") ? "&" : "?";
            String via = relay + sep + "url=" + target;
            HttpURLConnection conn = (HttpURLConnection) new URL(via).openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                conn.setRequestProperty("X-Colgram-Authorization", bearer);
            }
            if (jsonBody != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Colgram-Method", "POST");
                java.io.OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                reader.close();
            }
            conn.disconnect();
            if (code >= 200 && code < 300) {
                Log.i(TAG, "fetched " + hostOf(urlStr) + " through the configured relay");
                return new Response(code, sb.toString());
            }
            if (code >= 400 && code < 500 && "1".equals(conn.getHeaderField("X-Colgram-Relay"))) {
                return new Response(code, sb.toString());
            }
            attempts.add("реле-адрес: HTTP " + code);
        } catch (Throwable t) {
            attempts.add("реле-адрес: " + reason(t));
        }
        return null;
    }

    private static boolean isThirdPartySocks(ColgramProxyManager.ProxyItem item) {
        return item != null && item.type == 0 && !item.isLocalDpi()
                && !ColgramDohResolver.isLoopbackHost(item.address);
    }

    static boolean allowThirdPartyRelays() {
        return ColgramConfig.isAutoProxyEnabled()
                || isThirdPartySocks(ColgramProxyManager.getCurrentActiveProxy());
    }

    /**
     * A {@link Proxy} that can carry HTTPS right now: the local desync listener when the user
     * switched it on, else a live SOCKS5 node, else a loopback front tunnelled through a
     * reachable CONNECT relay. Third-party nodes are never selected while local direct bypass
     * owns the path unless auto-proxy or an explicit third-party SOCKS proxy is enabled.
     */
    public static synchronized Proxy pickRelayProxy() {
        long now = System.currentTimeMillis();
        if (now - transportPickedAt < TRANSPORT_CACHE_MS) return transportProxy;
        Proxy picked = null;
        for (ColgramProxyManager.ProxyItem relay : relayCandidates()) {
            if (ColgramProxyManager.probeTcp(relay.address, relay.port, 900) >= 0) {
                picked = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(relay.address, relay.port));
                break;
            }
        }
        if (picked == null && allowThirdPartyRelays()) {
            for (ColgramProxyChain.Relay relay : ColgramProxyManager.getReachableRelays()) {
                if (relay.socks) continue;
                int port = ColgramProxyChain.openProxyFront(relay);
                if (port > 0) {
                    picked = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
                    break;
                }
            }
        }
        transportProxy = picked;
        transportPickedAt = now;
        if (picked != null) {
            Log.i(TAG, "transport proxy for plain HTTP is " + picked.type() + " " + picked.address());
        }
        return picked;
    }

    private static Proxy transportProxy;
    private static long transportPickedAt;
    private static final long TRANSPORT_CACHE_MS = 60000L;

    /**
     * Relays worth trying: local desync listener first; explicit or auto-proxy third-party
     * SOCKS5 relays only when allowed by user settings.
     */
    private static List<ColgramProxyManager.ProxyItem> relayCandidates() {
        List<ColgramProxyManager.ProxyItem> out = new ArrayList<>();
        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
        if (ColgramDpiBypass.isBound()) {
            out.add(new ColgramProxyManager.ProxyItem("127.0.0.1", ColgramDpiBypass.activePort(), "", 0));
        }
        if (isThirdPartySocks(active)) out.add(active);
        if (!allowThirdPartyRelays()) {
            return out;
        }
        List<ColgramProxyManager.ProxyItem> pool = ColgramProxyManager.getVerifiedPool();
        for (ColgramProxyManager.ProxyItem p : pool) {
            if (p.type != 0 || p.isLocalDpi() || p.equals(active)) continue;
            if (p.isAvailable) out.add(p);
        }
        for (ColgramProxyManager.ProxyItem p : pool) {
            if (p.type != 0 || p.isLocalDpi() || p.isAvailable || p.equals(active)) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * Last resort when the pool has no usable SOCKS5: the harvested tunnel list (only when
     * third-party fallback is explicitly allowed).
     */
    private static Response viaConnectRelays(String urlStr, String jsonBody, String bearer,
                                             List<String> attempts) {
        if (!allowThirdPartyRelays()) return null;
        List<ColgramProxyChain.Relay> relays = ColgramProxyManager.getReachableRelays();
        int used = 0;
        for (ColgramProxyChain.Relay relay : relays) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                Response r = relay.socks
                        ? open(urlStr, relay.host, relay.port, jsonBody, bearer)
                        : openViaConnect(urlStr, relay.host, relay.port, jsonBody, bearer);
                Log.i(TAG, "fetched " + hostOf(urlStr) + " through tunnel " + relay);
                return r;
            } catch (Throwable t) {
                attempts.add(relay.key() + " -> " + reason(t));
            }
        }
        return null;
    }

    private static Response open(String urlStr, ColgramProxyManager.ProxyItem relay) throws Exception {
        return open(urlStr, relay, null, null);
    }

    private static Response open(String urlStr, ColgramProxyManager.ProxyItem relay,
                                 String jsonBody, String bearer) throws Exception {
        if (relay == null) {
            return open(urlStr, (String) null, 0, jsonBody, bearer);
        }
        return open(urlStr, relay.address, relay.port, jsonBody, bearer);
    }

    /** Opens a configured HTTP(S) connection for binary downloads via active Colgram SOCKS5/DPI/DoH route. */
    public static HttpURLConnection openBinaryStream(String urlStr, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return openBinaryStream(urlStr, connectTimeoutMs, readTimeoutMs, false);
    }

    /**
     * Opens a configured HTTP(S) connection for binary downloads.
     * When {@code directOnly} is true, dials directly with DoH resolution (no loopback proxy overhead);
     * otherwise uses the active SOCKS5 proxy or local DPI bypass listener when bound.
     */
    public static HttpURLConnection openBinaryStream(
            String urlStr, int connectTimeoutMs, int readTimeoutMs, boolean directOnly) throws IOException {
        String socksHost = null;
        int socksPort = 0;
        if (!directOnly) {
            ColgramProxyManager.ProxyItem relay = ColgramProxyManager.getCurrentActiveProxy();
            if (relay != null && relay.type == 0) {
                socksHost = relay.address;
                socksPort = relay.port;
            }
            if (socksHost == null && ColgramDpiBypass.isBound()) {
                socksHost = "127.0.0.1";
                socksPort = ColgramDpiBypass.activePort();
            }
        }
        HttpURLConnection conn = openConfiguredConnection(urlStr, socksHost, socksPort);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
        conn.setRequestProperty("Accept", "application/octet-stream, */*");
        return conn;
    }

    private static HttpURLConnection openConfiguredConnection(String urlStr, String socksHost, int socksPort) throws IOException {
        HttpURLConnection conn;
        URL url = new URL(urlStr);
        if (socksHost == null) {
            // Explicitly direct.
            //
            // This app installs a SOCKS front on loopback as the platform proxy, so an unnamed
            // openConnection goes through it - and the front answers for hosts it cannot reach:
            //
            //   напрямую: failed to connect to api.cloudflareclient.com/104.16.24.84 (port 443)
            //            after 8000ms
            //   127.0.0.1:37091 -> SOCKS: Host unreachable
            //
            // while the same address is open from the device:
            //
            //   nc -z -w 6 104.16.24.84 443   rc=0
            //
            // So the branch named "direct" was not direct. It asks for DIRECT explicitly, and the pinned
            // address is aimed at a socket that leaves on its own.
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            // Resolve the host ourselves first, then aim the socket at that answer. The name in
            // the URL is untouched, so SNI, Host and certificate verification are unchanged and a
            // resolver that has been cut by SNI cannot be swapped for an impostor.
            //
            // The address is sent to the socket factory rather than to a re-opened connection, so the
            // proxy the platform has installed is left in place. Dropping it - which is what copying the
            // connection without its proxy does - sends the request out on a path that is not the one the
            // device has, and on this network that path is the one that times out:
            //
            //   direct_rc=0   nc -z -w 6 104.16.24.84 443
            //   but the request timed out for eight seconds against the same address
            java.net.InetAddress resolved = ColgramDohResolver.resolveOrNull(url.getHost());
            if (resolved != null && conn instanceof javax.net.ssl.HttpsURLConnection) {
                conn = ColgramPinnedConnection.create(
                        (javax.net.ssl.HttpsURLConnection) conn, url, resolved, Proxy.NO_PROXY);
            }
        } else {
            if (ColgramConfig.isStrictProxySecurityEnabled()
                    && !ColgramDohResolver.isLoopbackHost(socksHost)
                    && !"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("в строгом режиме через чужой прокси разрешён только HTTPS");
            }
            Proxy socksProxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(socksHost, socksPort));
            conn = (HttpURLConnection) url.openConnection(socksProxy);
            if (ColgramConfig.isStrictProxySecurityEnabled()
                    && !ColgramDohResolver.isLoopbackHost(socksHost)) {
                conn.setInstanceFollowRedirects(false);
            }
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                java.net.InetAddress viaProxy = ColgramDohResolver.resolveOrNull(url.getHost());
                conn = ColgramPinnedConnection.create(
                        (javax.net.ssl.HttpsURLConnection) conn, url, viaProxy, socksProxy);
            }
        }
        return conn;
    }

    private static Response open(String urlStr, String socksHost, int socksPort,
                                 String jsonBody, String bearer) throws Exception {
        HttpURLConnection conn = openConfiguredConnection(urlStr, socksHost, socksPort);
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (jsonBody != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                java.io.OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, Charset.forName("UTF-8")));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                reader.close();
            }
            if (code < 200 || code >= 300) {
                // A 4xx/5xx is an answer. It must not be retried through six relays, so it is
                // returned rather than thrown; callers decide what to do with it.
                return new Response(code, sb.toString());
            }
            return new Response(code, sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * HTTPS through an HTTP CONNECT proxy, spoken by hand.
     *
     * HttpURLConnection cannot be given a raw tunnelled socket, so the tunnel is opened here and
     * TLS is layered over it with the real hostname (SNI and certificate verification included).
     * Connection: close keeps the response framing trivial: read until the peer hangs up.
     */
    private static Response openViaConnect(String urlStr, String proxyHost, int proxyPort,
                                           String jsonBody, String bearer) throws Exception {
        return openViaConnect(urlStr, proxyHost, proxyPort, jsonBody, bearer,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static Response openViaConnect(String urlStr, String proxyHost, int proxyPort,
                                           String jsonBody, String bearer,
                                           int connectTimeoutMs, int readTimeoutMs) throws Exception {
        URL url = new URL(urlStr);
        boolean tls = "https".equalsIgnoreCase(url.getProtocol());
        if (ColgramConfig.isStrictProxySecurityEnabled()
                && !tls && !ColgramDohResolver.isLoopbackHost(proxyHost)) {
            throw new IOException("в строгом режиме через чужой прокси разрешён только HTTPS");
        }
        int port = url.getPort() > 0 ? url.getPort() : (tls ? 443 : 80);
        String host = url.getHost();
        String path = url.getFile().isEmpty() ? "/" : url.getFile();

        java.net.Socket tunnel = new java.net.Socket();
        tunnel.connect(new InetSocketAddress(proxyHost, proxyPort), connectTimeoutMs);
        tunnel.setSoTimeout(readTimeoutMs);
        try {
            java.io.OutputStream rawOut = tunnel.getOutputStream();
            rawOut.write(("CONNECT " + host + ":" + port + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "User-Agent: " + ColgramPolymorphicStealth.pickBrowserUserAgent() + "\r\n\r\n").getBytes("UTF-8"));
            rawOut.flush();
            String status = readLine(tunnel.getInputStream());
            if (status == null || !status.contains(" 200")) {
                throw new IOException("CONNECT отклонён: " + status);
            }
            drainHeaders(tunnel.getInputStream());

            java.io.OutputStream out;
            InputStream in;
            if (tls) {
                javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                        ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                                .createSocket(tunnel, host, port, true);
                ColgramDohResolver.hardenTlsSocket(ssl);
                ssl.startHandshake();
                if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(host, ssl.getSession())) {
                    throw new IOException("сертификат не для " + host);
                }
                out = ssl.getOutputStream();
                in = ssl.getInputStream();
            } else {
                out = tunnel.getOutputStream();
                in = tunnel.getInputStream();
            }

            StringBuilder req = new StringBuilder();
            req.append(jsonBody != null ? "POST " : "GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("User-Agent: ").append(ColgramPolymorphicStealth.pickBrowserUserAgent()).append("\r\n");
            req.append("Accept: application/json\r\n");
            if (bearer != null && !bearer.isEmpty()) {
                req.append("Authorization: Bearer ").append(bearer).append("\r\n");
            }
            if (jsonBody != null) {
                byte[] body = jsonBody.getBytes("UTF-8");
                req.append("Content-Type: application/json\r\n");
                req.append("Content-Length: ").append(body.length).append("\r\n");
                req.append("Connection: close\r\n\r\n");
                out.write(req.toString().getBytes("UTF-8"));
                out.write(body);
            } else {
                req.append("Connection: close\r\n\r\n");
                out.write(req.toString().getBytes("UTF-8"));
            }
            out.flush();

            String statusLine = readLine(in);
            if (statusLine == null) throw new IOException("пустой ответ из туннеля");
            int code = parseCode(statusLine);
            drainHeaders(in);
            StringBuilder body = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) body.append(new String(buf, 0, n, "UTF-8"));
            return new Response(code, body.toString());
        } finally {
            try { tunnel.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private static void drainHeaders(InputStream in) throws IOException {
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // headers are irrelevant to the callers; they only have to be consumed
        }
    }

    private static int parseCode(String statusLine) {
        int space = statusLine.indexOf(' ');
        if (space < 0 || space + 4 > statusLine.length()) return -1;
        try {
            return Integer.parseInt(statusLine.substring(space + 1, space + 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String reason(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static String hostOf(String urlStr) {
        try {
            return new URL(urlStr).getHost();
        } catch (Throwable t) {
            return urlStr;
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
