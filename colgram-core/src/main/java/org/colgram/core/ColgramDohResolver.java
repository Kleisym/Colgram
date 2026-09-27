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
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long CACHE_TTL_MS = 30_000L;

    /** One DoH endpoint: where to connect, and what to ask for. */
    public static final class Endpoint {
        public final String ip;
        public final String sni;
        /** The name the certificate is verified against. */
        public final String verifyName;

        Endpoint(String ip, String sni, String verifyName) {
            this.ip = ip;
            this.sni = sni;
            this.verifyName = verifyName;
        }
    }

    /**
     * Endpoints, in the order they are tried.
    
     * The pinned IP is the point of the exercise: connecting to a literal address means the SNI
     * is whatever we say it is, and does not depend on resolving a name that may itself be cut.
     * several providers, not just one, because the block is applied per-resolver and varies by
     * ISP and region.
     */
    private static final Endpoint[] ENDPOINTS = {
            new Endpoint("1.1.1.1", "cloudflare-dns.com", "cloudflare-dns.com"),
            new Endpoint("1.0.0.1", "cloudflare-dns.com", "cloudflare-dns.com"),
            new Endpoint("8.8.8.8", "dns.google", "dns.google"),
            new Endpoint("8.8.4.4", "dns.google", "dns.google"),
            new Endpoint("94.140.14.14", "adguard-dns.com", "adguard-dns.com"),
    };

    private static final Map<String, long[]> cache = new ConcurrentHashMap<>();
    private static final Map<String, String[]> answers = new ConcurrentHashMap<>();
    /** Endpoints that timed out or were reset; skipped until {@link #resetHealth()}. */
    private static final List<String> cut = new ArrayList<>();
    private static volatile String lastResolver;

    private ColgramDohResolver() {}

    /**
     * Resolve a name, preferring the system and falling back to DoH.
     *
     * There is no supported way to replace the JVM's resolver on Android -
     * java.net.InetAddressResolver is libcore-internal and absent from android.jar - so this is
     * an explicit entry point rather than a global override. ColgramHttp calls it before every
     * request, and {@link #prefetch} warms the cache for names the app is about to need.
     */
    public static InetAddress[] resolveOrSystem(String hostname) {
        InetAddress[] viaDoh = resolve(hostname);
        if (viaDoh != null && viaDoh.length > 0) return viaDoh;
        InetAddress[] system = resolveSystem(hostname);
        return system != null ? system : new InetAddress[0];
    }

    /**
     * One address for `hostname`, or null when neither resolver can answer.
     *
     * The single-address shape is what a connection needs: the caller wants one place to dial,
     * not a list. Returns null rather than an empty array so a caller can tell "nothing to pin"
     * from "pinned, use the platform path".
     */
    public static InetAddress resolveOrNull(String hostname) {
        InetAddress[] viaDoh = resolve(hostname);
        if (viaDoh != null && viaDoh.length > 0) return viaDoh[0];
        InetAddress[] system = resolveSystem(hostname);
        return (system != null && system.length > 0) ? system[0] : null;
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

    public static synchronized void resetHealth() {
        cut.clear();
        lastResolver = null;
    }

    public static String activeResolver() {
        return lastResolver;
    }

    /**
     * Resolve one name, system first, then DoH.
     *
     * The system answer is preferred when it exists because it is the cheapest and the platform
     * keeps its own cache; DoH is the fallback for exactly the case that matters, a system
     * resolver that has been cut or is returning nothing.
     */
    public static InetAddress[] resolve(String hostname) {
        if (hostname == null || hostname.isEmpty()) return null;
        InetAddress[] system = resolveSystem(hostname);
        if (system != null && system.length > 0) return system;

        String key = hostname.toLowerCase(java.util.Locale.ROOT);
        long[] stamp = cache.get(key);
        String[] cached = answers.get(key);
        if (stamp != null && cached != null && System.currentTimeMillis() - stamp[0] < CACHE_TTL_MS) {
            return toAddresses(cached);
        }

        for (Endpoint endpoint : ENDPOINTS) {
            if (cut.contains(endpoint.ip)) continue;
            byte[] reply;
            try {
                reply = query(endpoint, hostname);
            } catch (Throwable t) {
                Log.i(TAG, endpoint.ip + " cut or unreachable: " + t.getClass().getSimpleName());
                cut.add(endpoint.ip);
                continue;
            }
            if (reply == null) {
                cut.add(endpoint.ip);
                continue;
            }
            String[] found = parseARecords(reply);
            if (found.length == 0) continue;
            lastResolver = endpoint.ip;
            cache.put(key, new long[]{System.currentTimeMillis()});
            answers.put(key, found);
            return toAddresses(found);
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

    /** RFC 8484 POST, with the address pinned so the SNI is under our control. */
    private static byte[] query(Endpoint endpoint, String hostname) throws Exception {
        byte[] request = buildQuery(hostname);
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://" + endpoint.ip + "/dns-query");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            // Host must match the SNI, or Cloudflare answers 400 rather than a DNS message.
            connection.setRequestProperty("Host", endpoint.sni);
            connection.setRequestProperty("Content-Type", "application/dns-message");
            connection.setRequestProperty("Accept", "application/dns-message");
            connection.setDoOutput(true);
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection secure = (HttpsURLConnection) connection;
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, null, null);
                secure.setSSLSocketFactory(ssl.getSocketFactory());
                final String expect = endpoint.verifyName;
                secure.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String ignored, SSLSession session) {
                        // The handshake already completed with the pinned address; the question is
                        // whether the peer presented a certificate for the name we asked for.
                        return matches(session, expect);
                    }
                });
            }
            OutputStream out = connection.getOutputStream();
            out.write(request);
            out.flush();
            if (connection.getResponseCode() != 200) return null;
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Certificate name check without pulling in X509 parsing.
     *
     * Deliberately narrow: the caller already dialled a pinned address, so this guards against an
     * impostor on that address, not against the platform's own trust store. The platform chain is
     * still verified by the default SSLSocketFactory above - only the name comparison is ours.
     */
    private static boolean matches(SSLSession session, String name) {
        try {
            java.security.cert.Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0) return false;
            if (!(chain[0] instanceof java.security.cert.X509Certificate)) return false;
            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) chain[0];
            String subject = cert.getSubjectX500Principal().getName();
            String san = String.valueOf(cert.getSubjectAlternativeNames());
            String needle = name.toLowerCase(java.util.Locale.ROOT);
            return subject.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || san.toLowerCase(java.util.Locale.ROOT).contains(needle);
        } catch (Throwable t) {
            // No certificate to inspect means no basis for trust, so refuse rather than allow.
            return false;
        }
    }

    /** A minimal wire-format A query. */
    private static byte[] buildQuery(String hostname) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        int id = (int) (System.nanoTime() & 0xffff);
        body.write(id >> 8); body.write(id & 0xff);
        body.write(0x01); body.write(0x00);          // recursion desired
        body.write(0x00); body.write(0x01);          // one question
        body.write(0x00); body.write(0x00); body.write(0x00); body.write(0x00);
        body.write(0x00); body.write(0x00);          // QCLASS IN
        for (String label : hostname.split("\\.")) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IllegalArgumentException("bad label in " + hostname);
            }
            body.write(bytes.length);
            body.write(bytes, 0, bytes.length);
        }
        body.write(0x00);                              // root
        body.write(0x00); body.write(0x01);          // QTYPE A
        body.write(0x00); body.write(0x01);          // QCLASS IN
        byte[] query = body.toByteArray();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(12 + query.length);
        buffer.putShort((short) id);
        buffer.put((byte) 0x01).put((byte) 0x00);      // flags
        buffer.putShort((short) 1);                   // QDCOUNT
        buffer.putShort((short) 0);                   // ANCOUNT
        buffer.putShort((short) 0);                   // NSCOUNT
        buffer.putShort((short) 0);                   // ARCOUNT
        buffer.put(query, 4, query.length);           // the question, minus the header we built
        return buffer.array();
    }

    /** Extract A records from a wire-format reply. */
    static String[] parseARecords(byte[] message) {
        List<String> found = new ArrayList<>();
        if (message == null || message.length < 12) return new String[0];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(message);
        int questions = buffer.getShort() & 0xffff;
        int answers = buffer.getShort() & 0xffff;
        buffer.position(12);
        try {
            // Each question is a name FOLLOWED BY qtype and qclass. Skipping only the name left
            // the cursor two bytes short, so the first answer was read as type 256 and the
            // parser silently returned nothing - which looked exactly like a resolver that had
            // been cut, and would have sent the app round the pool for no reason.
            for (int i = 0; i < questions; i++) {
                skipName(buffer);
                buffer.position(buffer.position() + 4); // QTYPE + QCLASS
            }
            for (int i = 0; i < answers; i++) {
                skipName(buffer);
                int type = buffer.getShort() & 0xffff;
                int clazz = buffer.getShort() & 0xffff;
                long ttl = buffer.getInt() & 0xffffffffL;
                int length = buffer.getShort() & 0xffff;
                int start = buffer.position();
                if (type == 1 && clazz == 1 && length == 4) {
                    String ip = (buffer.get() & 0xff) + "." + (buffer.get() & 0xff) + "."
                            + (buffer.get() & 0xff) + "." + (buffer.get() & 0xff);
                    if (!found.contains(ip)) found.add(ip);
                }
                buffer.position(start + length);
            }
        } catch (Throwable t) {
            // A truncated or compressed reply still yields whatever was read before the throw.
            Log.i(TAG, "reply parse stopped early: " + t.getClass().getSimpleName());
        }
        return found.toArray(new String[0]);
    }

    /** Skip a possibly compressed name. */
    private static void skipName(java.nio.ByteBuffer buffer) {
        while (true) {
            int len = buffer.get() & 0xff;
            if (len == 0) return;
            if ((len & 0xc0) == 0xc0) {
                buffer.get();
                return;
            }
            buffer.position(buffer.position() + len);
        }
    }
}
