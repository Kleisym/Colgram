package org.colgram.core;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ColgramEndpoints — dials the address that actually answers.
 *
 * Why this exists, measured on 2026-09-23 on the network Colgram is developed against:
 * `api.telegram.org` publishes several A records and the block there is per-address, so one of
 * them answers a TLS handshake in ~200 ms while the one the resolver happens to return first
 * (149.154.166.110 that day) is dropped silently. java.net dials the first answer and sits on
 * it for the full connect timeout. Every Bot API write - setMyName, setMyDescription, getUpdates
 * - therefore failed with "ошибка соединения" even though the Bot API was reachable the whole
 * time. Pinning is not optional here, it is the only way to use the path that works.
 *
 * The rule kept while doing this: the hostname never changes. TLS is still negotiated against
 * the real name, so SNI and certificate validation are exactly what they were - only the address
 * is chosen better. A wrong answer here (an IP pinned forever, or a certificate checked against
 * a literal) would be worse than the timeout it replaces, so:
 *
 *   - candidates are the host's own A records, plus the last address that worked, plus - for
 *     Telegram API names only - the A records of the sibling api hosts;
 *   - a candidate has to complete a TCP connection to be chosen;
 *   - the choice expires (TTL below) and is re-probed, so a network that stops blocking simply
 *     stops being worked around;
 *   - when nothing answers, the caller is told "no opinion" (null) and keeps the platform default.
 *
 * The loopback front (frontProxy) exists because HttpURLConnection cannot be handed an address:
 * it takes the name, resolves it, and there is no supported hook in the middle. The front speaks
 * HTTP CONNECT, so the caller still passes the real hostname and the certificate still checks
 * against it; the front is where the address is chosen. It is bound to 127.0.0.1 only.
 */
public final class ColgramEndpoints {

    private static final String TAG = "ColgramEndpoints";

    /** How long a chosen address is trusted before it is probed again. */
    private static final long TTL_MS = 5 * 60 * 1000L;
    /** After a round where nothing answered, do not re-probe on every single request. */
    private static final long NEGATIVE_TTL_MS = 15 * 1000L;
    private static final int PROBE_TIMEOUT_MS = 1500;

    /**
     * Names that resolve to Telegram's own API and web fronts. Queried only when the name the
     * caller asked for has nothing live: they are the same operator, the same certificate family
     * (`*.telegram.org`) and, for the api hosts, the same Bot API service behind a different
     * address. Nothing here is trusted - every address still has to complete a TCP connection.
     */
    private static final String[] TELEGRAM_ALTERNATIVES = {
            "api.telegram.org",
            "api1.telegram.org", "api2.telegram.org", "api3.telegram.org",
            "api4.telegram.org", "api5.telegram.org",
            "telegram.org", "t.me", "web.telegram.org", "downloads.telegram.org",
    };

    /**
     * Front addresses Telegram has published for these names over the years, used as a last
     * resort when DNS publishes only a dropped address. Kept short and probed, never trusted:
     * an entry is only ever used after it answers a TCP connect on the requested port.
     */
    private static final String[] TELEGRAM_FRONT_ADDRESSES = {
            "149.154.167.220", "149.154.167.99", "149.154.166.110", "149.154.167.51",
            "149.154.167.91", "149.154.167.40", "149.154.171.5", "149.154.175.50",
            "149.154.175.53", "149.154.175.56", "149.154.175.40", "149.154.175.100",
            "149.154.175.117", "91.108.56.100", "91.108.56.130", "95.161.76.100",
            "185.76.151.112", "185.76.151.1", "5.142.99.58",
    };

    private static final class Choice {
        final InetAddress address;
        final long at;
        final boolean live;

        Choice(InetAddress address, long at, boolean live) {
            this.address = address;
            this.at = at;
            this.live = live;
        }
    }

    /** host:port -> Choice. Keyed by port because a host can be open on 443 and dropped on 80. */
    private static final Map<String, Choice> cache = new ConcurrentHashMap<>();

    private ColgramEndpoints() {}

    /**
     * An address for {@code host} that completes a TCP connection to {@code port}, or null when
     * there is no opinion to offer - either nothing answered (caller keeps platform behaviour) or
     * the host is already a literal.
     */
    public static InetAddress select(String host, int port) {
        if (host == null || host.isEmpty() || isIpLiteral(host)) return null;
        String key = host + ":" + port;
        long now = System.currentTimeMillis();

        Choice cached = cache.get(key);
        if (cached != null && now - cached.at < (cached.live ? TTL_MS : NEGATIVE_TTL_MS)) {
            return cached.live ? cached.address : null;
        }

        synchronized (ColgramEndpoints.class) {
            cached = cache.get(key);
            now = System.currentTimeMillis();
            if (cached != null && now - cached.at < (cached.live ? TTL_MS : NEGATIVE_TTL_MS)) {
                return cached.live ? cached.address : null;
            }
            Choice result = probe(host, port);
            cache.put(key, result);
            return result.live ? result.address : null;
        }
    }

    /**
     * A {@link Proxy} that carries the request to a live address for its own hostname.
     * Null when the front could not start - the caller then connects the normal way.
     */
    public static Proxy frontProxy() {
        int p = frontPort();
        return p > 0 ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", p)) : null;
    }

    /** Port of the loopback CONNECT front, started on first use. -1 if it cannot start. */
    public static int frontPort() {
        if (frontPort > 0) return frontPort;
        synchronized (ColgramEndpoints.class) {
            if (frontPort > 0) return frontPort;
            if (frontFailed) return -1;
            try {
                java.net.ServerSocket ss = new java.net.ServerSocket(0, 64,
                        InetAddress.getByName("127.0.0.1"));
                frontPort = ss.getLocalPort();
                Thread t = new Thread(() -> acceptLoop(ss), "colgram-endpoint-front");
                t.setDaemon(true);
                t.start();
                Log.i(TAG, "CONNECT front on 127.0.0.1:" + frontPort);
            } catch (Throwable e) {
                frontFailed = true;
                Log.w(TAG, "front did not start, dialing normally: " + e);
                frontPort = -1;
            }
            return frontPort;
        }
    }

    private static volatile int frontPort = -1;
    private static volatile boolean frontFailed = false;

    /** One line describing what is currently pinned, for the diagnostics screen and logcat. */
    public static String describe() {
        if (cache.isEmpty()) return "адреса не переопределены";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Choice> e : cache.entrySet()) {
            Choice c = e.getValue();
            if (!c.live) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" -> ").append(c.address.getHostAddress());
        }
        return sb.length() == 0 ? "ни один адрес не отвечал" : sb.toString();
    }

    /** Forget a host after a transport failure so the next call re-probes instead of reusing it. */
    public static void invalidate(String host) {
        if (host == null) return;
        for (String key : new ArrayList<>(cache.keySet())) {
            if (key.startsWith(host + ":")) cache.remove(key);
        }
    }

    // ------------------------------------------------------------------ probing

    private static Choice probe(String host, int port) {
        // Wave 1: what the resolver says, plus whatever worked last time. On an unblocked network
        // this is the only wave that runs, and it costs one connection.
        List<InetAddress> first = new ArrayList<>();
        addPinned(first, host);
        collect(host, first);
        InetAddress chosen = probeAll(first, port);

        // Wave 2: Telegram's own alternatives. Needed because on the blocked network the public
        // answer for api.telegram.org is a single address and that address is dropped, while the
        // same service answers on its other long-standing fronts. Measured 2026-09-23: DNS gave
        // 149.154.166.110 (silent, 8 s timeout) and the Bot API answered correctly - TLS verified,
        // proper 401 JSON - on 149.154.167.220, which the resolver did not publish at all.
        if (chosen == null && isTelegramName(host)) {
            List<InetAddress> rest = new ArrayList<>();
            addPinned(rest, host);
            for (String sibling : TELEGRAM_ALTERNATIVES) {
                collect(sibling, rest);
            }
            addLiterals(rest, TELEGRAM_FRONT_ADDRESSES);
            removeUsed(rest, first);
            chosen = probeAll(rest, port);
        }

        long at = System.currentTimeMillis();
        if (chosen == null) {
            Log.w(TAG, "no live address for " + host + ":" + port);
            return new Choice(null, at, false);
        }
        Log.i(TAG, "using " + host + " -> " + chosen.getHostAddress() + ":" + port);
        ColgramConfig.setPinnedEndpoint(host, chosen.getHostAddress());
        return new Choice(chosen, at, true);
    }

    /** The address that answered for this host before, if any, as the first candidate. */
    private static void addPinned(List<InetAddress> into, String host) {
        String pinned = ColgramConfig.getPinnedEndpoint(host);
        if (pinned == null || pinned.isEmpty()) return;
        try {
            InetAddress p = InetAddress.getByName(pinned);
            if (p instanceof Inet4Address && !into.contains(p)) into.add(p);
        } catch (Throwable ignore) {
            // A stale literal in prefs is not a reason to skip the probe.
        }
    }

    /** Connect to every candidate in parallel; return the first that completes, or null. */
    private static InetAddress probeAll(List<InetAddress> candidates, int port) {
        if (candidates.isEmpty()) return null;
        final AtomicReference<InetAddress> winner = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(candidates.size());
        for (final InetAddress candidate : candidates) {
            Thread t = new Thread(() -> {
                try {
                    if (winner.get() != null) return;
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(candidate, port), PROBE_TIMEOUT_MS);
                        // Any live address is a good answer, so a plain set-once is enough.
                        winner.compareAndSet(null, candidate);
                    } catch (Throwable ignored) {
                    } finally {
                        try { s.close(); } catch (Throwable ignored) {}
                    }
                } finally {
                    done.countDown();
                }
            }, "colgram-endpoint-probe");
            t.setDaemon(true);
            t.start();
        }
        try {
            done.await(PROBE_TIMEOUT_MS * 2L + 2000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return winner.get();
    }

    private static void collect(String host, List<InetAddress> into) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                if (a instanceof Inet4Address && !into.contains(a)) into.add(a);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve " + host + ": " + t.getClass().getSimpleName());
        }
    }

    private static void addLiterals(List<InetAddress> into, String[] addresses) {
        for (String literal : addresses) {
            try {
                InetAddress a = InetAddress.getByName(literal);
                if (a instanceof Inet4Address && !into.contains(a)) into.add(a);
            } catch (Throwable ignore) {
                // An address that will not parse is simply not a candidate.
            }
        }
    }

    private static void removeUsed(List<InetAddress> candidates, List<InetAddress> alreadyTried) {
        candidates.removeAll(alreadyTried);
    }

    private static boolean isTelegramName(String host) {
        return host != null && host.endsWith(".telegram.org");
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    // ------------------------------------------------------------------ CONNECT front

    private static void acceptLoop(java.net.ServerSocket server) {
        while (true) {
            final Socket client;
            try {
                client = server.accept();
            } catch (Throwable t) {
                Log.w(TAG, "accept: " + t.getClass().getSimpleName());
                continue;
            }
            Thread t = new Thread(() -> serve(client), "colgram-endpoint-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Serve one client. Only CONNECT is answered: every caller that uses this front is opening
     * TLS, and the request line's hostname is what the certificate is checked against upstream,
     * so the address choice here cannot weaken either.
     */
    private static void serve(Socket client) {
        try {
            client.setSoTimeout(PROBE_TIMEOUT_MS * 4);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            String requestLine = readLine(cin);
            if (requestLine == null) { close(client); return; }
            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
                cout.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes("US-ASCII"));
                cout.flush();
                close(client);
                return;
            }
            // Drain the headers: whatever follows the blank line is the ClientHello, and leaking
            // header bytes into the tunnel upstream makes TLS fail with a parse error.
            String line;
            while ((line = readLine(cin)) != null && line.length() > 0) { /* discard */ }

            String target = parts[1];
            String host = target;
            int port = 443;
            int idx = target.lastIndexOf(':');
            if (idx > 0) {
                host = target.substring(0, idx);
                try { port = Integer.parseInt(target.substring(idx + 1)); } catch (NumberFormatException ignore) {}
            }

            InetAddress address = select(host, port);
            if (address == null) {
                // No live address. Saying so is better than hanging: the caller sees an immediate
                // failure and can fall through to its next transport.
                cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("US-ASCII"));
                cout.flush();
                close(client);
                return;
            }

            Socket upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(address, port), PROBE_TIMEOUT_MS * 4);
            } catch (Throwable t) {
                invalidate(host);
                cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("US-ASCII"));
                cout.flush();
                close(client);
                close(upstream);
                return;
            }

            cout.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"));
            cout.flush();
            // Same rule as any tunnel here: the header-phase timeout must not survive into the
            // relay, or an idle upstream leg (a long poll, a slow TLS write) is read as an EOF and
            // the connection is torn down.
            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            client.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            pump(client, upstream);
        } catch (Throwable t) {
            Log.d(TAG, "front connection: " + t);
        } finally {
            close(client);
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c, guard = 0;
        while ((c = in.read()) != -1 && guard++ < 8192) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString("US-ASCII");
    }

    /** Bidirectional relay until either side closes. */
    private static void pump(final Socket a, final Socket b) {
        Thread t = new Thread(() -> {
            try { relay(b, a); } catch (Throwable ignore) {} finally { close(a); close(b); }
        }, "colgram-endpoint-pump-ba");
        t.setDaemon(true);
        t.start();
        try {
            relay(a, b);
        } catch (Throwable ignore) {
        } finally {
            close(a);
            close(b);
        }
    }

    private static void relay(Socket from, Socket to) throws java.io.IOException {
        byte[] buf = new byte[8192];
        InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream();
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignore) {}
    }
}
