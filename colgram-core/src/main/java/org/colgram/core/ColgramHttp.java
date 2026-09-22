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
        throw new IOException(join(attempts));
    }

    /** POST a JSON body, with the same relay fallback as GET. */
    public static Response post(String urlStr, String jsonBody, String bearer) throws IOException {
        List<String> attempts = new ArrayList<>();
        try {
            return open(urlStr, null, jsonBody, bearer);
        } catch (Throwable t) {
            attempts.add("напрямую: " + reason(t));
        }
        int used = 0;
        for (ColgramProxyManager.ProxyItem relay : relayCandidates()) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                return open(urlStr, relay, jsonBody, bearer);
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }
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
        int used = 0;
        for (ColgramProxyManager.ProxyItem relay : relayCandidates()) {
            if (used++ >= MAX_RELAY_ATTEMPTS) break;
            try {
                return open(urlStr, relay, null, bearer);
            } catch (Throwable t) {
                attempts.add(relay.address + ":" + relay.port + " -> " + reason(t));
            }
        }
        throw new IOException(join(attempts));
    }

    /**
     * Relays worth trying: SOCKS5 entries only (MTProto and WEB cannot carry plain HTTP), the
     * applied proxy first if it qualifies, then verified-alive, then the rest.
     */
    private static List<ColgramProxyManager.ProxyItem> relayCandidates() {
        List<ColgramProxyManager.ProxyItem> out = new ArrayList<>();
        List<ColgramProxyManager.ProxyItem> pool = ColgramProxyManager.getVerifiedPool();
        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
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

    private static Response open(String urlStr, ColgramProxyManager.ProxyItem relay) throws Exception {
        return open(urlStr, relay, null, null);
    }

    private static Response open(String urlStr, ColgramProxyManager.ProxyItem relay,
                                 String jsonBody, String bearer) throws Exception {
        HttpURLConnection conn;
        URL url = new URL(urlStr);
        if (relay == null) {
            conn = (HttpURLConnection) url.openConnection();
        } else {
            conn = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(relay.address, relay.port)));
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
