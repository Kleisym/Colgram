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

    /**
     * GET a URL, falling back through relays. Throws IOException describing every attempt when
     * nothing worked - an empty return would be the silent failure this class exists to remove.
     */
    public static Response get(String urlStr) throws IOException {
        List<String> attempts = new ArrayList<>();
        try {
            return open(urlStr, null);
        } catch (Throwable t) {
            attempts.add("напрямую: " + reason(t));
        }

        Response configured = viaConfiguredRelay(urlStr, null, null, attempts);
        if (configured != null) return configured;

        List<ColgramProxyManager.ProxyItem> relays = relayCandidates();
        if (relays.isEmpty()) {
            attempts.add("нет ни одного SOCKS-релея в пуле");
            throw new IOException(join(attempts));
        }
        int used = 0;
        for (ColgramProxyManager.ProxyItem relay : relays) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                Response r = open(urlStr, relay);
                Log.i(TAG, "fetched " + hostOf(urlStr) + " through relay " + relay.address
                        + ":" + relay.port);
                return r;
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }
        Response tunneled = viaConnectRelays(urlStr, null, null, attempts);
        if (tunneled != null) return tunneled;
        throw new IOException(join(attempts));
    }

    /**
     * Short-budget GET for public candidate feeds. A feed is retried on the next refresh, so it
     * must not hold the whole 16-source batch behind four 20-second relay attempts.
     */
    public static Response getFast(String urlStr) throws IOException {
        List<String> attempts = new ArrayList<>();
        try {
            return openFast(urlStr, null);
        } catch (Throwable t) {
            attempts.add("напрямую: " + reason(t));
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
        for (ColgramProxyChain.Relay relay : ColgramProxyManager.getReachableRelays()) {
            if (relay == null || relay.rttMs < 0) continue;
            if (connectAttempts++ >= 1) break;
            try {
                Response response = openViaConnect(urlStr, relay.host, relay.port, null, null,
                        FAST_CONNECT_TIMEOUT_MS, FAST_READ_TIMEOUT_MS);
                if (response.code >= 200 && response.code < 300) return response;
                attempts.add(relay.key() + " -> HTTP " + response.code);
            } catch (Throwable t) {
                attempts.add(relay.key() + " -> " + reason(t));
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
            conn = (HttpURLConnection) new URL(relay + sep + "url=" + target).openConnection();
            conn.setConnectTimeout(FAST_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(FAST_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line).append('\n');
                }
            }
            if (code >= 200 && code < 300) return new Response(code, body.toString());
            attempts.add("реле-адрес: HTTP " + code);
        } catch (Throwable t) {
            attempts.add("реле-адрес: " + reason(t));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static Response openFast(String urlStr, ColgramProxyManager.ProxyItem relay) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn;
        if (relay == null) {
            conn = (HttpURLConnection) url.openConnection();
        } else {
            conn = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(relay.address, relay.port)));
        }
        try {
            conn.setConnectTimeout(FAST_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(FAST_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line).append('\n');
                }
            }
            return new Response(code, body.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** POST a JSON body, with the same relay fallback as GET. */
    public static Response post(String urlStr, String jsonBody, String bearer) throws IOException {
        List<String> attempts = new ArrayList<>();
        try {
            return open(urlStr, null, jsonBody, bearer);
        } catch (Throwable t) {
            attempts.add("напрямую: " + reason(t));
        }
        Response configured = viaConfiguredRelay(urlStr, jsonBody, bearer, attempts);
        if (configured != null) return configured;

        int used = 0;
        for (ColgramProxyManager.ProxyItem relay : relayCandidates()) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                return open(urlStr, relay, jsonBody, bearer);
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }
        Response tunneled = viaConnectRelays(urlStr, jsonBody, bearer, attempts);
        if (tunneled != null) return tunneled;
        throw new IOException(join(attempts));
    }

    /** GET with an optional bearer token. */
    public static Response get(String urlStr, String bearer) throws IOException {
        List<String> attempts = new ArrayList<>();
        try {
            return open(urlStr, null, null, bearer);
        } catch (Throwable t) {
            attempts.add("напрямую: " + reason(t));
        }
        Response configured = viaConfiguredRelay(urlStr, null, bearer, attempts);
        if (configured != null) return configured;

        int used = 0;
        for (ColgramProxyManager.ProxyItem relay : relayCandidates()) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                return open(urlStr, relay, null, bearer);
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }
        Response tunneled = viaConnectRelays(urlStr, null, bearer, attempts);
        if (tunneled != null) return tunneled;
        throw new IOException(join(attempts));
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
            HttpURLConnection conn = (HttpURLConnection) new URL(via).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
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
            attempts.add("реле-адрес: HTTP " + code);
        } catch (Throwable t) {
            attempts.add("реле-адрес: " + reason(t));
        }
        return null;
    }

    /**
     * A {@link Proxy} that can carry HTTPS right now: the local desync listener when the user
     * switched it on, else a live SOCKS5 node, else a loopback front tunnelled through a
     * reachable CONNECT relay. Null means "nothing works, dial directly".
     *
     * Cached because choosing it costs a TCP probe, and callers ask on every request.
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
        if (picked == null) {
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
     * Relays worth trying: SOCKS5 entries only (MTProto and WEB cannot carry plain HTTP), the
     * applied proxy first if it qualifies, then verified-alive, then the rest.
     */
    private static List<ColgramProxyManager.ProxyItem> relayCandidates() {
        List<ColgramProxyManager.ProxyItem> out = new ArrayList<>();
        List<ColgramProxyManager.ProxyItem> pool = ColgramProxyManager.getVerifiedPool();
        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
        // The local desync listener first when the user switched it on: it is the one relay with
        // nobody else in the path, so the bot API and the mail providers should reach Telegram
        // through it before any stranger's proxy is touched.
        if (ColgramConfig.isDpiBypassEnabled() && ColgramDpiBypass.isBound()) {
            out.add(new ColgramProxyManager.ProxyItem("127.0.0.1", ColgramDpiBypass.LOCAL_PORT, "", 0));
        }
        if (active != null && active.type == 0 && !active.isLocalDpi()) out.add(active);
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
     * Last resort when the pool has no usable SOCKS5: the harvested tunnel list. An HTTP CONNECT
     * proxy is an order of magnitude more common than a SOCKS5 one, and a CONNECT tunnel carries
     * TLS untouched, so api.telegram.org and the mail providers become reachable through it even
     * when every SOCKS5 in the pool is dead. This is what "сеть недоступна" on a bot request
     * actually needed: the applied proxy was MTProto, which plain Java HTTPS cannot speak.
     */
    private static Response viaConnectRelays(String urlStr, String jsonBody, String bearer,
                                             List<String> attempts) {
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

    private static Response open(String urlStr, String socksHost, int socksPort,
                                 String jsonBody, String bearer) throws Exception {
        HttpURLConnection conn;
        URL url = new URL(urlStr);
        if (socksHost == null) {
            conn = (HttpURLConnection) url.openConnection();
        } else {
            conn = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(socksHost, socksPort)));
        }
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
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
        boolean tls = "https".equals(url.getProtocol());
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
                    + "User-Agent: Colgram/1.0\r\n\r\n").getBytes("UTF-8"));
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
            req.append("User-Agent: Mozilla/5.0\r\n");
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
