package org.colgram.core;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ColgramDcRemap — dial Telegram's DCs through whichever of their addresses still answers.
 *
 * Why this is not a proxy. tgnet hardcodes the IP of every DC and dials it directly; on a blocked
 * network those specific addresses are dropped at TCP level, so the app sits on "Соединение..."
 * while other Telegram addresses are reachable. This is a loopback SOCKS5 endpoint that tgnet is
 * pointed at, and its entire job is to answer the client's CONNECT by dialing a *different
 * Telegram address for the same DC* — nothing leaves the phone except a direct connection to
 * Telegram's own infrastructure. No third party sees a byte, there is no server to rent or trust,
 * and the traffic is ordinary MTProto to Telegram, not a tunnel through somebody's VPS.
 *
 * The order of candidates is deliberate:
 *   1. the address tgnet asked for - on an unblocked network this succeeds and nothing changes;
 *   2. other addresses in the same /16, which belong to the same DC and can therefore carry the
 *      session without a migration dance;
 *   3. the rest of Telegram's published API addresses, last, because a live address of the wrong
 *      DC answers the handshake and then redirects, which is progress but not a connection.
 *
 * What it cannot do is invent a listener: if no Telegram address that speaks MTProto is reachable,
 * every candidate fails and the verdict is logged. That is the honest outcome, and it is why each
 * decision is recorded - so "the bypass does nothing" can be answered with which addresses were
 * tried and how they failed, instead of a spinner.
 */
public final class ColgramDcRemap {

    private static final String TAG = "ColgramDcRemap";

    private static final int CONNECT_TIMEOUT_MS = 2500;
    /** Wall-clock budget for the parallel probe of Telegram's addresses. */
    private static final long PROBE_BUDGET_MS = 3000L;

    /** Prefixes of Telegram's own address space. Anything else is passed through untouched. */
    private static final String[] TELEGRAM_PREFIXES = {
            "149.154.", "91.108.", "185.76.151.", "185.76.150.", "5.142.", "95.161.76.",
            "139.45.", "109.239.140.", "67.198.55.", "31.13.",
    };

    /**
     * Telegram's published API and DC addresses. Only ever used as candidates to try, never
     * trusted: each has to complete a TCP connection before anything is piped through it.
     */
    private static final String[] TELEGRAM_ADDRESSES = {
            "149.154.175.50", "149.154.175.51", "149.154.175.53", "149.154.175.56",
            "149.154.175.40", "149.154.175.100", "149.154.175.117",
            "149.154.167.51", "149.154.167.56", "149.154.167.91", "149.154.167.40",
            "149.154.167.220", "149.154.167.99", "149.154.166.110",
            "149.154.171.5", "149.154.171.20",
            "91.108.56.100", "91.108.56.130", "91.108.4.130", "91.108.8.130",
            "91.108.12.130", "91.108.16.130", "91.108.20.130", "95.161.76.100",
            "185.76.151.112", "185.76.151.1", "5.142.99.58", "5.142.134.227",
    };

    /**
     * Addresses that accepted TCP and then gave back nothing.
     *
     * That combination is the signature of a web front: it completes the handshake and answers
     * Telegram's MTProto framing with an HTTP error or a closed socket. Without parking them the
     * remap happily re-picks the same nginx host on every connection - 49 times in three minutes -
     * and never converges on anything that could carry a session.
     */
    private static final Map<String, Long> parked = new ConcurrentHashMap<>();
    private static final long PARK_MS = 10 * 60 * 1000L;

    /** requested host:port -> chosen address, so a burst of connections probes once. */
    private static final Map<String, String> targetCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> targetCacheAt = new ConcurrentHashMap<>();
    private static final long TARGET_TTL_MS = 60 * 1000L;

    /**
     * How long a server gets to answer the handshake before the address is parked. Telegram's own
     * API servers reply to req_pq_multi in a few hundred milliseconds from anywhere, so four
     * seconds is generous - and it is what lets the remap walk away from a silent address instead
     * of sitting on it until tgnet gives up.
     */
    private static final int FIRST_BYTE_MS = 4000;

    /**
     * What a connection has to look like for its address to be kept: at least one whole MTProto
     * message back (Telegram's smallest is a 20-byte pong) and enough life to carry a session.
     */
    private static final int MIN_ANSWER_BYTES = 20;
    private static final int MIN_TUNNEL_MS = 5000;

    /**
     * Addresses found by sweeping the requested DC's own /24.
     *
     * A fixed list of published addresses can only ever be as good as the last time somebody
     * wrote it down. Telegram announces its blocks as aggregates and a DC's addresses sit in one
     * or two /24s, so sweeping the /24 the client actually asked for finds what is reachable
     * today on this network - including an address that is not in any public list. Cached per
     * subnet and port, because 254 probes is not something to repeat for every connection.
     */
    private static final Map<String, List<String>> discovered = new ConcurrentHashMap<>();
    private static final Map<String, Long> discoveredAt = new ConcurrentHashMap<>();
    private static final java.util.Set<String> sweeping =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final long DISCOVERY_TTL_MS = 10 * 60 * 1000L;
    // Sized so a /24 is actually walked inside the budget: at 48 workers and 1.2s per probe the
    // 254 addresses need ~6.4s and the 4s cutoff returned "0 адресов отвечают" for a /24 that does
    // contain a reachable address - the sweep looked conclusive while it had only reached a
    // quarter of the range.
    private static final int DISCOVERY_CONCURRENCY = 96;
    private static final int DISCOVERY_TIMEOUT_MS = 900;
    private static final long DISCOVERY_BUDGET_MS = 12000L;

    private static volatile ServerSocket listener;
    private static volatile int boundPort = -1;
    private static final AtomicInteger remapped = new AtomicInteger();
    private static final AtomicInteger refused = new AtomicInteger();
    private static volatile String lastDecision = "ещё не выполнялось";

    private ColgramDcRemap() {}

    /** Bind the loopback endpoint. Idempotent; returns the port, or -1 when it cannot bind. */
    public static int start() {
        int p = boundPort;
        if (p > 0) return p;
        synchronized (ColgramDcRemap.class) {
            if (boundPort > 0) return boundPort;
            try {
                ServerSocket ss = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
                boundPort = ss.getLocalPort();
                listener = ss;
                Thread t = new Thread(ColgramDcRemap::acceptLoop, "colgram-dc-remap");
                t.setDaemon(true);
                t.start();
                Log.i(TAG, "DC remap listening on 127.0.0.1:" + boundPort);
            } catch (Throwable e) {
                Log.w(TAG, "could not bind: " + e);
                boundPort = -1;
            }
            return boundPort;
        }
    }

    /** Tear the endpoint down. Anything already piped keeps running until its peer closes. */
    public static void stop() {
        synchronized (ColgramDcRemap.class) {
            if (listener != null) {
                try { listener.close(); } catch (Throwable ignored) {}
                listener = null;
            }
            boundPort = -1;
        }
    }

    public static boolean isRunning() {
        return boundPort > 0;
    }

    /** Counters and the last decision, for the settings screen and the proxy doctor. */
    public static String describe() {
        if (boundPort <= 0) return "не запущен";
        return "127.0.0.1:" + boundPort + "; перенаправлено " + remapped.get()
                + ", отказов " + refused.get() + "; последний выбор: " + lastDecision;
    }

    // ---------------------------------------------------------------------- socket plumbing

    private static void acceptLoop() {
        while (true) {
            final Socket client;
            try {
                ServerSocket ss = listener;
                if (ss == null) return;
                client = ss.accept();
            } catch (Throwable t) {
                if (listener == null) return;            // stopped on purpose
                Log.w(TAG, "accept: " + t.getClass().getSimpleName());
                continue;
            }
            Thread t = new Thread(() -> serve(client), "colgram-dc-remap-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Speak enough SOCKS5 to make tgnet happy: no-auth method selection, then CONNECT.
     *
     * Only CONNECT is served. tgnet also uses UDP associate for nothing here, and answering a
     * method set we do not implement would be a silent wrong answer.
     */
    private static void serve(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(CONNECT_TIMEOUT_MS * 4);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            int ver = in.read();
            int nmethods = in.read();
            if (ver != 0x05 || nmethods < 0 || nmethods > 255) { close(client); return; }
            for (int i = 0; i < nmethods; i++) in.read();
            out.write(new byte[]{0x05, 0x00});           // version 5, no authentication
            out.flush();

            int rver = in.read();
            int cmd = in.read();
            in.read();                                    // reserved
            int atyp = in.read();
            if (rver != 0x05 || cmd != 0x01) {            // CONNECT only
                refuse(out);
                close(client);
                return;
            }
            String host = readAddress(in, atyp);
            int port = readPort(in);
            if (host == null || port <= 0) { refuse(out); close(client); return; }

            String target = chooseTarget(host, port);
            if (target == null) {
                refused.incrementAndGet();
                lastDecision = host + ":" + port + " — ни один адрес Telegram не ответил ("
                        + candidatesFor(host, port).size() + " проверено, "
                        + parkedCount() + " припарковано)";
                Log.w(TAG, lastDecision);
                refuse(out);
                close(client);
                return;
            }

            upstream = new Socket();
            try {
                upstream.connect(new InetSocketAddress(
                        InetAddress.getByName(target), port), CONNECT_TIMEOUT_MS);
            } catch (Throwable t) {
                refused.incrementAndGet();
                lastDecision = host + ":" + port + " -> " + target + " ("
                        + t.getClass().getSimpleName() + ")";
                refuse(out);
                close(client);
                return;
            }

            // The success reply must carry a bound address; the client only reads it, so zeros
            // are what every local SOCKS endpoint sends.
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            client.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);

            if (!target.equals(host)) {
                remapped.incrementAndGet();
                lastDecision = host + ":" + port + " -> " + target + ":" + port;
                Log.i(TAG, "remapped " + host + ":" + port + " -> " + target + ":" + port);
            }
            long openedAt = System.currentTimeMillis();
            long upstreamBytes = pipe(client, upstream, FIRST_BYTE_MS);
            long lived = System.currentTimeMillis() - openedAt;
            if (upstreamBytes < MIN_ANSWER_BYTES || lived < MIN_TUNNEL_MS) {
                // Not a server that can carry a session. Two things have to be true of a real API
                // address, and both are read off traffic tgnet generates anyway: it answers the
                // handshake with at least one MTProto message (the smallest one Telegram sends is
                // a 20-byte pong), and the connection survives long enough to be useful.
                //
                // "zero bytes" was not enough. The emulator's NAT answers SYN for the whole /24 and
                // leaks the odd byte back, so a dead address cleared the old bar, stayed in the
                // cache for its whole TTL, and was handed to every reconnect - observed as
                // "remapped -> 149.154.167.2" about twice a second with no verdict ever reached.
                targetCache.remove(host + ":" + port);
                park(target, "TCP открылся, но ответа нет: " + upstreamBytes
                        + " байт обратно за " + lived + " мс");
            }
        } catch (Throwable t) {
            Log.d(TAG, "connection: " + t);
        } finally {
            close(client);
            close(upstream);
        }
    }

    private static String readAddress(InputStream in, int atyp) throws IOException {
        if (atyp == 0x01) {
            byte[] b = new byte[4];
            if (readFully(in, b) != 4) return null;
            return InetAddress.getByAddress(b).getHostAddress();
        }
        if (atyp == 0x03) {
            int len = in.read();
            if (len <= 0 || len > 255) return null;
            byte[] b = new byte[len];
            if (readFully(in, b) != len) return null;
            return new String(b, "US-ASCII");
        }
        if (atyp == 0x04) {
            byte[] b = new byte[16];
            if (readFully(in, b) != 16) return null;
            return InetAddress.getByAddress(b).getHostAddress();
        }
        return null;
    }

    private static int readPort(InputStream in) throws IOException {
        byte[] b = new byte[2];
        if (readFully(in, b) != 2) return -1;
        return ((b[0] & 0xff) << 8) | (b[1] & 0xff);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n <= 0) break;
            off += n;
        }
        return off;
    }

    private static void refuse(OutputStream out) {
        try {
            out.write(new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); // host unreachable
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------- address choice

    /**
     * A Telegram address that is worth dialling for {@code host}:{@code port} right now - the
     * requested one if it is live, otherwise a sibling that answers. Exposed so the local DPI
     * desync bypass can search addresses too: it used to give up on the single address tgnet
     * asked for, which on a blocked network is a dead DC, so the desync strategies never got
     * applied to any of the addresses that do open a socket.
     */
    static String liveAddressFor(String host, int port) {
        if (!isTelegramAddress(host)) return null;
        return chooseTarget(host, port);
    }

    /**
     * The address to dial for a requested one: itself first, then its own /16, then the rest of
     * Telegram's published addresses. Non-Telegram destinations are returned unchanged - this
     * component has no business rewriting traffic that is not Telegram's.
     */
    private static String chooseTarget(String host, int port) {
        if (!isTelegramAddress(host)) return host;
        final String key = host + ":" + port;
        Long cachedAt = targetCacheAt.get(key);
        String cached = targetCache.get(key);
        if (cached != null && cachedAt != null
                && System.currentTimeMillis() - cachedAt < TARGET_TTL_MS
                && !isParked(cached) && probe(port, cached)) {
            return cached;
        }
        // Probed in parallel, not in sequence. Tried sequentially at 2.5 s each, the candidate
        // list cost tens of seconds and the cap that kept that tolerable silently cut off
        // 149.154.167.220 - the one Telegram address on this network that answers TCP. The
        // answer must not depend on where the list happened to be truncated.
        final List<String> candidates = candidatesFor(host, port);
        candidates.removeIf(ColgramDcRemap::isParked);
        final java.util.concurrent.atomic.AtomicReference<String> winner =
                new java.util.concurrent.atomic.AtomicReference<>();
        // First answer wins outright: waiting for the whole probe budget held every Telegram
        // connection this proxy serves for three seconds even when the first address opened
        // immediately, which made the remap look like it was stalling rather than searching.
        final java.util.concurrent.CountDownLatch answered =
                new java.util.concurrent.CountDownLatch(1);
        for (final String candidate : candidates) {
            Thread t = new Thread(() -> {
                if (winner.get() != null) return;
                if (probe(port, candidate) && winner.compareAndSet(null, candidate)) {
                    answered.countDown();
                }
            }, "colgram-dc-remap-probe");
            t.setDaemon(true);
            t.start();
        }
        try {
            answered.await(PROBE_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String chosen = winner.get();
        if (chosen != null) {
            targetCache.put(key, chosen);
            targetCacheAt.put(key, System.currentTimeMillis());
        }
        return chosen;
    }

    /** At most a handful of addresses in a log line: a whole /24 once drowned the rest. */
    private static String preview(List<String> addresses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.size() && i < 6; i++) {
            if (i > 0) sb.append(", ");
            sb.append(addresses.get(i));
        }
        if (addresses.size() > 6) sb.append(" …");
        return sb.toString();
    }

    /** How many addresses this process has already given up on. */
    static int parkedCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Long at : parked.values()) if (now - at < PARK_MS) n++;
        return n;
    }

    private static boolean isParked(String address) {
        Long at = parked.get(address);
        if (at == null) return false;
        if (System.currentTimeMillis() - at < PARK_MS) return true;
        parked.remove(address);
        return false;
    }

    /** Park an address that answered TCP but carried nothing. */
    private static void park(String address, String why) {
        if (address == null) return;
        parked.put(address, System.currentTimeMillis());
        Log.i(TAG, "parked " + address + ": " + why);
    }

    /**
     * Requested address, then whatever is live in its own /24, then same-/16 neighbours from the
     * published list, then the rest. The discovered addresses come early because they are the
     * same DC the client asked for and they were found answering right now.
     */
    static List<String> candidatesFor(String host, int port) {
        LinkedHashSet<String> sameSubnet = new LinkedHashSet<>();
        LinkedHashSet<String> other = new LinkedHashSet<>();
        String prefix = subnetPrefix(host);
        for (String address : TELEGRAM_ADDRESSES) {
            if (address.equals(host)) continue;
            if (prefix != null && address.startsWith(prefix)) sameSubnet.add(address);
            else other.add(address);
        }
        List<String> out = new ArrayList<>();
        out.add(host);
        if (prefix != null) {
            for (String live : discoverLive(prefix, port)) {
                if (!live.equals(host)) out.add(live);
            }
        }
        out.addAll(sameSubnet);
        out.addAll(other);
        return out;
    }

    /**
     * Sweep one /24 for addresses that complete a TCP connection on {@code port}.
     *
     * Bounded and best-effort on purpose: the budget returns whatever answered by the cutoff
     * rather than waiting out 254 timeouts, and a second caller asking during a sweep gets an
     * empty list instead of a duplicate sweep - the published list still covers that call.
     */
    static List<String> discoverLive(String subnet, int port) {
        final String key = subnet + port;
        Long at = discoveredAt.get(key);
        List<String> cached = discovered.get(key);
        if (cached != null && at != null && System.currentTimeMillis() - at < DISCOVERY_TTL_MS) {
            return cached;
        }
        if (!sweeping.add(key)) return java.util.Collections.emptyList();
        final List<String> found = java.util.Collections.synchronizedList(new ArrayList<String>());
        try {
            final java.util.concurrent.atomic.AtomicInteger next =
                    new java.util.concurrent.atomic.AtomicInteger(1);
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(254);
            for (int w = 0; w < DISCOVERY_CONCURRENCY; w++) {
                Thread t = new Thread(() -> {
                    while (true) {
                        int last = next.getAndIncrement();
                        if (last > 254) {
                            // This worker took no address, so it owes no countdown.
                            return;
                        }
                        try {
                            String candidate = subnet + last;
                            if (!isParked(candidate) && probe(DISCOVERY_TIMEOUT_MS, candidate, port)) {
                                found.add(candidate);
                            }
                        } finally {
                            done.countDown();
                        }
                    }
                }, "colgram-dc-sweep");
                t.setDaemon(true);
                t.start();
            }
            try {
                done.await(DISCOVERY_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            sweeping.remove(key);
        }
        List<String> snapshot = new ArrayList<>(found);
        discovered.put(key, snapshot);
        discoveredAt.put(key, System.currentTimeMillis());
        Log.i(TAG, "swept " + subnet + "1-254 on :" + port + " - " + snapshot.size()
                + " открывают TCP" + (snapshot.isEmpty() ? "" : ": " + preview(snapshot))
                + "; бесполезные отсеет парковка по ответу");
        return snapshot;
    }

    /** The "a.b.c." part of an IPv4 literal - the /24 whose neighbours are worth sweeping. */
    private static String subnetPrefix(String host) {
        int seen = 0;
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) != '.') continue;
            if (++seen == 3) return host.substring(0, i + 1);
        }
        return null;                                        // not an IPv4 literal
    }

    static boolean isTelegramAddress(String host) {
        if (host == null) return false;
        for (String prefix : TELEGRAM_PREFIXES) {
            if (host.startsWith(prefix)) return true;
        }
        // A name rather than a literal: Telegram's own hostnames are equally fine to remap.
        return host.endsWith(".telegram.org") || host.endsWith(".telegram.dog");
    }

    /**
     * True when the address completes a TCP connection on {@code port}.
     *
     * Kept only for the caller that has nothing better to ask. On an emulated network this is
     * worthless as a liveness test: the NAT answers SYN for every address in the /24, so a sweep
     * of 149.154.167.0/24 "found" 254 live hosts, all of which then carried zero bytes.
     */
    private static boolean probe(int port, String address) {
        return probe(CONNECT_TIMEOUT_MS, address, port);
    }

    private static boolean probe(int timeoutMs, String address, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName(address), port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(s);
        }
    }

    /*
     * There is deliberately no "does this address speak MTProto" test here, and the reason is
     * worth keeping in the file. Two such tests were written and both turned out to be
     * unvalidatable: through SOCKS5 nodes that complete a TCP connection to 149.154.175.50:443 -
     * the address Telegram's own clients use - neither the reserve query nor req_pq_multi got any
     * answer at all (scripts/mtproto-probe-validation.py, scripts/mtproto-liveness-test.py). A
     * filter that rejects real API servers is worse than no filter: it hides the one candidate the
     * bypass exists to find, and it reports the result as a fact about the network.
     *
     * So the cheap gate is a TCP connect, and the verdict comes from the connection itself: an
     * address has to answer tgnet's own handshake with at least a full MTProto message and keep the
     * tunnel alive long enough to be useful, or it is parked (see serve()). That reads the shape of
     * traffic tgnet generates anyway, so it separates Telegram's web fronts and a NAT that answers
     * SYN for a whole /24 from a server that can carry a session - without pretending to parse the
     * protocol.
     */

    // ---------------------------------------------------------------------- relay

    /**
     * Relay both ways, giving up on a server that never answers.
     *
     * Waiting for the tunnel to close is not enough: tgnet keeps a silent connection open, times
     * out on its own, reconnects, and the remap hands it the same dead address from the cache
     * again - observed as an endless "remapped -> 149.154.167.2" while the header spun. So the
     * first read from Telegram carries a deadline. Once a byte has arrived the deadline is cleared,
     * because an idle MTProto connection is normal and must not be cut.
     */
    private static long pipe(final Socket a, final Socket b, final int firstByteMs) {
        final java.util.concurrent.atomic.AtomicLong upstreamBytes =
                new java.util.concurrent.atomic.AtomicLong();
        Thread t = new Thread(() -> {
            try {
                b.setSoTimeout(firstByteMs);
            } catch (Throwable ignored) {
            }
            upstreamBytes.set(copy(b, a));
            close(a);
        }, "colgram-dc-remap-back");
        t.setDaemon(true);
        t.start();
        copy(a, b);
        close(b);
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return upstreamBytes.get();
    }

    private static long copy(Socket from, Socket to) {
        byte[] buf = new byte[16384];
        long total = 0;
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                if (total == 0) {
                    try { from.setSoTimeout(0); } catch (Throwable ignored) {}
                }
                out.write(buf, 0, n);
                out.flush();
                total += n;
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

}
