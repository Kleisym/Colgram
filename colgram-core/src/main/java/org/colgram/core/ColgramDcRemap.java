package org.colgram.core;

import android.util.Log;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
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
 *   1. the address tgnet asked for;
 *   2. only documented aliases for that exact DC.
 *
 * Do not probe neighboring /24 or /16 addresses: Telegram assigns different DCs inside those
 * ranges, and a TCP-open web front or another DC is not a valid substitute for the requested DC.
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
    private static final long PROBE_BUDGET_MS = 1800L;

    /** Prefixes of Telegram's own address space. Anything else is passed through untouched. */
    private static final String[] TELEGRAM_PREFIXES = {
            "149.154.", "91.108.", "185.76.151.", "185.76.150.", "5.142.", "95.161.76.",
            "139.45.", "109.239.140.", "67.198.55.", "31.13.",
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
    /** A TCP-open address that gave no MTProto reply cools down briefly, then can be retried. */
    private static final Map<String, Long> temporarilyParkedUntil = new ConcurrentHashMap<>();
    private static final long SILENT_ADDRESS_COOLDOWN_MS = 60 * 1000L;

    /** requested host:port -> chosen address, so a burst of connections probes once. */
    private static final Map<String, String> targetCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> targetCacheAt = new ConcurrentHashMap<>();
    private static final long TARGET_TTL_MS = 60 * 1000L;
    /** Repeated requests for the same DC subnet share one scan and briefly cool down on misses. */
    private static final long TARGET_MISS_COOLDOWN_MS = 30 * 1000L;
    private static final ColgramProbeMissCache probeMisses =
            new ColgramProbeMissCache(TARGET_MISS_COOLDOWN_MS);
    private static volatile long lastSuppressedLogAt;

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

    private static volatile ServerSocket listener;
    private static volatile int boundPort = -1;
    private static final AtomicInteger remapped = new AtomicInteger();
    private static final AtomicInteger refused = new AtomicInteger();

    /**
     * How long a UDP association may sit idle before it is assumed abandoned.
     *
     * A Telegram client that vanishes without closing its control connection would otherwise hold
     * a socket for the life of the process, and this listener is long-lived by design.
     */
    private static final int UDP_IDLE_MS = 30000;
    /** A MTProto datagram plus its SOCKS5 UDP header; anything larger is not Telegram traffic. */
    private static final int UDP_BUFFER_BYTES = 2048;
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

    /**
     * The port tgnet is pointed at, or -1 when the listener is not up.
     *
     * Exposed because "is the local SOCKS endpoint actually there" is otherwise unanswerable from
     * outside, and a check that can only inspect a flag is a check that passes while the listener
     * is dead. The device test dials this port for real.
     */
    public static int localPort() {
        return boundPort;
    }

    /** Counters and the last decision, for the settings screen and the proxy doctor. */
    public static String describe() {
        if (boundPort <= 0) return "не запущен";
        return "внутренний маршрут DC; перенаправлено " + remapped.get()
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
     * Speak enough SOCKS5 to make tgnet happy: no-auth method selection, then CONNECT or
     * UDP ASSOCIATE.
     *
     * UDP ASSOCIATE used to be refused outright, on the reasoning that tgnet had no use for it
     * here. That reasoning was wrong in a way that mattered: a refused ASSOCIATE is answered with
     * "host unreachable", and a client that asked for UDP then has no path at all rather than a
     * degraded one. Telegram's own transports ask for it, so the reply the user sees is a
     * connection that cannot be established - with nothing in the log pointing at the reason.
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
            if (rver != 0x05) {
                refuse(out);
                close(client);
                return;
            }
            if (cmd == 0x03) {                            // UDP ASSOCIATE
                serveUdpAssociate(client, in, out);
                return;
            }
            if (cmd != 0x01) {                            // CONNECT is the only other one
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
                if (probeMisses.isSuppressed(probeMissKey(host, port), SystemClock.elapsedRealtime())) {
                    lastDecision = host + ":" + port + " — повторная проверка адресов отложена";
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastSuppressedLogAt >= 10000L) {
                        lastSuppressedLogAt = now;
                        Log.w(TAG, lastDecision);
                    }
                } else {
                    lastDecision = host + ":" + port + " — адреса Telegram не ответили";
                    Log.w(TAG, lastDecision + " (" + parkedCount() + " припарковано)");
                }
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

    // ------------------------------------------------------------------ UDP ASSOCIATE

    /**
     * Serve one UDP ASSOCIATE, per RFC 1928.
     *
     * The client opens a UDP socket, asks the proxy to associate with it, and then sends
     * datagrams to whatever BND.ADDR/BND.PORT the success reply names. Those datagrams carry a
     * SOCKS5 UDP request header - RSV(2) FRAG(1) ATYP(1) ADDR DST.ADDR DST.PORT - followed by the
     * payload, and the reply comes back the same way. That framing is the whole reason this is not
     * "just open a DatagramSocket": a client that gets the header wrong silently sends to the
     * wrong place.
     *
     * The BND address in the reply has to be the address the client can actually reach, not
     * 0.0.0.0. A client that dials 0.0.0.0 gets nothing on a real network, and the failure looks
     * like the proxy dropped the association rather than like a bad reply - so loopback is named
     * explicitly and the client's own request address is honoured when it supplies one.
     */
    private static void serveUdpAssociate(Socket control, InputStream in, OutputStream out) {
        // Drain the DST.ADDR/DST.PORT the client sent. They are advisory for ASSOCIATE - the
        // client names where it will send from - but they have to be consumed or they would be
        // read as the head of the first encapsulated datagram.
        DatagramSocket relay = null;
        try {
            String requested = readAddress(in, in.read());
            readPort(in);

            relay = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            relay.setSoTimeout(UDP_IDLE_MS);
            int boundPort = relay.getLocalPort();

            // BND.ADDR is the loopback the client can reach us on, never 0.0.0.0.
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1,
                    (byte) (boundPort >>> 8), (byte) boundPort});
            out.flush();
            Log.i(TAG, "UDP associate on 127.0.0.1:" + boundPort
                    + (requested == null ? "" : " for client " + requested));

            // The control connection is the client's lifetime signal: when it closes, the
            // association is over and the relay socket has to go with it. Holding the socket open
            // after that leaks one port per association for the life of the process.
            control.setSoTimeout(0);
            final DatagramSocket bound = relay;
            Thread controlWatch = new Thread(() -> {
                try {
                    byte[] sink = new byte[64];
                    while (control.getInputStream().read(sink) >= 0) {
                        // Clients do not send anything meaningful here; the read only detects close.
                    }
                } catch (Throwable ignored) {
                    // Closed or reset: both mean the association is over.
                } finally {
                    bound.close();
                }
            }, "colgram-socks-udp-control");
            controlWatch.setDaemon(true);
            controlWatch.start();

            pumpUdp(relay, control);
        } catch (Throwable t) {
            Log.w(TAG, "UDP associate failed: " + t.getMessage());
            try {
                refuse(out);
                out.flush();
            } catch (Throwable ignored) {
            }
            relay.close();
        } finally {
            close(control);
        }
    }

    /**
     * Move datagrams between the client's UDP socket and Telegram, in both directions.
     *
     * Each datagram is parsed for its SOCKS5 UDP header and re-sent to the destination it names,
     * so the destination is the client's choice and not something this bridge second-guesses -
     * except that a Telegram address gets the same remap treatment CONNECT gets, because that is
     * the entire point of this listener on a network where tgnet's hardcoded IPs are dropped.
     */
    private static void pumpUdp(DatagramSocket relay, Socket control) {
        pumpUdp(relay, control, new ConcurrentHashMap<String, DatagramSocket>());
    }

    private static void pumpUdp(DatagramSocket relay, Socket control,
                                ConcurrentHashMap<String, DatagramSocket> upstreams) {
        byte[] buffer = new byte[UDP_BUFFER_BYTES];
        long lastTraffic = System.currentTimeMillis();
        while (!relay.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                relay.receive(packet);
            } catch (SocketTimeoutException timeout) {
                // An association with no traffic in either direction for a while is abandoned;
                // without this a client that vanished without closing its control connection would
                // hold the socket open for the life of the process.
                if (System.currentTimeMillis() - lastTraffic > UDP_IDLE_MS * 4L) break;
                continue;
            } catch (Throwable closed) {
                break;
            }
            lastTraffic = System.currentTimeMillis();

            byte[] data = packet.getData();
            int length = packet.getLength();
            if (length < 10 || (data[0] & 0xFF) != 0 || (data[1] & 0xFF) != 0) {
                continue;                                   // RSV must be zero, and a header is at least 10 bytes
            }
            if ((data[2] & 0xFF) != 0) continue;            // FRAG != 0 is reassembly, which is not served
            int atyp = data[3] & 0xFF;
            int hostStart = 4;
            String host = udpAddress(data, atyp, hostStart);
            if (host == null) continue;
            // The header length depends on the FORM, and for a domain name on the name's own
            // length byte - guessing it wrong shifts the port and the payload by a byte or two,
            // which produces datagrams that go nowhere with nothing to explain why.
            int addressBytes = udpAddressLength(data, atyp, hostStart);
            if (addressBytes < 0) continue;
            int headerLength = addressBytes + 2;
            if (length < headerLength) continue;
            int port = ((data[headerLength] & 0xFF) << 8) | (data[headerLength + 1] & 0xFF);
            int payloadStart = headerLength + 2;
            if (port <= 0) continue;

            String target = chooseTarget(host, port);
            if (target == null) {
                refused.incrementAndGet();
                lastDecision = host + ":" + port + " (udp) — нет доступного адреса";
                continue;
            }
            // One upstream socket per destination, and its reader is what puts the reply back on
            // the client's socket. Sending and never receiving is the half that looks like it
            // works: the datagram leaves, nothing comes back, and there is nothing to point at.
            final String destination = target;
            final int destinationPort = port;
            final InetSocketAddress clientAddress = new InetSocketAddress(
                    packet.getAddress(), packet.getPort());
            final byte[] original = new byte[length];
            System.arraycopy(data, 0, original, 0, length);
            final int originalHeader = headerLength;
            try {
                DatagramSocket upstream = upstreams.get(target + ":" + port);
                if (upstream == null || upstream.isClosed()) {
                    upstream = new DatagramSocket(new InetSocketAddress("0.0.0.0", 0));
                    upstream.setSoTimeout(UDP_IDLE_MS);
                    upstreams.put(target + ":" + port, upstream);
                    startUpstreamReader(upstream, relay, clientAddress, original,
                            originalHeader, upstreams, target + ":" + port);
                }
                byte[] out = new byte[length - payloadStart];
                System.arraycopy(data, payloadStart, out, 0, out.length);
                upstream.send(new DatagramPacket(out, out.length,
                        InetAddress.getByName(target), port));
            } catch (Throwable t) {
                Log.i(TAG, "udp send to " + target + ":" + port + " failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    /**
     * Read replies from one upstream socket and hand them back, encapsulated, to the client.
     *
     * The reply keeps the header the client used, so the address it named is the address the reply
     * appears to come from - which is what lets a client correlate the two. Re-encoding the
     * destination instead would be more accurate and would break any client that matches on it.
     */
    private static void startUpstreamReader(final DatagramSocket upstream,
                                            final DatagramSocket client,
                                            final InetSocketAddress clientAddress,
                                            final byte[] header,
                                            final int headerLength,
                                            final ConcurrentHashMap<String, DatagramSocket> upstreams,
                                            final String key) {
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[UDP_BUFFER_BYTES];
            try {
                while (!upstream.isClosed() && !client.isClosed()) {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    upstream.receive(reply);
                    byte[] encapsulated = new byte[headerLength + reply.getLength()];
                    System.arraycopy(header, 0, encapsulated, 0, headerLength);
                    System.arraycopy(reply.getData(), reply.getOffset(), encapsulated,
                            headerLength, reply.getLength());
                    client.send(new DatagramPacket(encapsulated, encapsulated.length,
                            clientAddress.getAddress(), clientAddress.getPort()));
                }
            } catch (Throwable gone) {
                // The association ended, or the socket idled out. Either way this reader's job
                // is over, and leaving the socket open would leak one port per destination.
            } finally {
                upstream.close();
                upstreams.remove(key, upstream);
            }
        }, "colgram-socks-udp-upstream");
        reader.setDaemon(true);
        reader.start();
    }

    /** The address in a SOCKS5 UDP header, or null when the form is one we do not serve. */
    private static String udpAddress(byte[] data, int atyp, int start) {
        if (atyp == 0x01) {
            if (start + 4 > data.length) return null;
            return (data[start] & 0xFF) + "." + (data[start + 1] & 0xFF) + "."
                    + (data[start + 2] & 0xFF) + "." + (data[start + 3] & 0xFF);
        }
        if (atyp == 0x03) {
            int nameLength = data[start] & 0xFF;
            if (start + 1 + nameLength > data.length) return null;
            return new String(data, start + 1, nameLength,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        if (atyp == 0x04) {
            if (start + 16 > data.length) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) sb.append(':');
                sb.append(Integer.toHexString(((data[start + i * 2] & 0xFF) << 8)
                        | (data[start + i * 2 + 1] & 0xFF)));
            }
            return sb.toString();
        }
        return null;                                        // 0x04 is the only other defined form
    }

    /** Bytes the destination address occupies in a SOCKS5 UDP header, or -1 when malformed. */
    private static int udpAddressLength(byte[] data, int atyp, int start) {
        switch (atyp) {
            case 0x01:
                return start + 4 <= data.length ? 4 : -1;
            case 0x03: {
                if (start >= data.length) return -1;
                int nameLength = data[start] & 0xFF;
                return start + 1 + nameLength <= data.length ? 1 + nameLength : -1;
            }
            case 0x04:
                return start + 16 <= data.length ? 16 : -1;
            default:
                return -1;
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
        return chooseTarget(host, port, true);
    }

    /** Fallback lookup used after the local listener already failed the requested destination. */
    static String liveAlternativeFor(String host, int port) {
        if (!isTelegramAddress(host)) return null;
        return chooseTarget(host, port, false);
    }

    /** Skip a TCP-open destination for a short period after its MTProto handshake stayed silent. */
    public static boolean shouldSkipDirectAddress(String host) {
        return isTelegramAddress(host) && isParked(host);
    }

    /**
     * The address to dial for a requested one: itself first, then its own /16, then the rest of
     * Telegram's published addresses. Non-Telegram destinations are returned unchanged - this
     * component has no business rewriting traffic that is not Telegram's.
     */
    private static String chooseTarget(String host, int port) {
        return chooseTarget(host, port, true);
    }

    private static String chooseTarget(String host, int port, boolean includeRequestedAddress) {
        if (!isTelegramAddress(host)) return host;
        final String key = host + ":" + port;
        Long cachedAt = targetCacheAt.get(key);
        String cached = targetCache.get(key);
        if (cached != null && cachedAt != null
                && System.currentTimeMillis() - cachedAt < TARGET_TTL_MS
                && (includeRequestedAddress || !cached.equals(host))
                && !isParked(cached) && probe(port, cached)) {
            return cached;
        }
        if (cached != null) targetCache.remove(key, cached);
        if (cachedAt != null) targetCacheAt.remove(key, cachedAt);

        final String missKey = probeMissKey(host, port);
        if (!probeMisses.tryBegin(missKey, SystemClock.elapsedRealtime())) {
            lastDecision = host + ":" + port + " — проверка адресов уже выполняется или отложена";
            return null;
        }

        String chosen = null;
        try {
        // Probe only the small official alias set for this DC, in parallel. TCP-open is only a
        // dial hint: the first MTProto reply is bounded and a silent endpoint is cooled down.
        final List<String> candidates = candidatesFor(host, port);
        if (!includeRequestedAddress) candidates.remove(host);
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
        chosen = winner.get();
        if (chosen != null) {
            targetCache.put(key, chosen);
            targetCacheAt.put(key, System.currentTimeMillis());
        }
        return chosen;
        } finally {
            probeMisses.finish(missKey, chosen != null, SystemClock.elapsedRealtime());
        }
    }

    /** Clear stale misses after a route change or an explicit user-triggered retry. */
    public static void invalidateFailedProbes() {
        probeMisses.clearFailures();
    }

    private static String probeMissKey(String host, int port) {
        String subnet = subnetPrefix(host);
        return (subnet == null ? host : subnet + "*") + ":" + port;
    }

    /** How many addresses this process has already given up on. */
    static int parkedCount() {
        long now = System.currentTimeMillis();
        java.util.Set<String> active = new java.util.HashSet<>();
        for (Map.Entry<String, Long> entry : parked.entrySet()) {
            if (now - entry.getValue() < PARK_MS) active.add(entry.getKey());
        }
        for (Map.Entry<String, Long> entry : temporarilyParkedUntil.entrySet()) {
            if (now < entry.getValue()) active.add(entry.getKey());
        }
        return active.size();
    }

    private static boolean isParked(String address) {
        Long temporaryUntil = temporarilyParkedUntil.get(address);
        if (temporaryUntil != null) {
            if (System.currentTimeMillis() < temporaryUntil) return true;
            temporarilyParkedUntil.remove(address, temporaryUntil);
        }
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
     * The local DPI tunnel accepted TCP but the selected address did not answer MTProto.
     * Drop every cached mapping to it so the next tgnet retry can try a different candidate.
     */
    public static void reportSilentAddress(String address, int port) {
        if (!isTelegramAddress(address) || port <= 0) return;
        long until = System.currentTimeMillis() + SILENT_ADDRESS_COOLDOWN_MS;
        temporarilyParkedUntil.put(address, until);
        String directKey = address + ":" + port;
        targetCache.remove(directKey);
        targetCacheAt.remove(directKey);
        for (Map.Entry<String, String> entry : targetCache.entrySet()) {
            if (address.equals(entry.getValue()) && targetCache.remove(entry.getKey(), address)) {
                targetCacheAt.remove(entry.getKey());
            }
        }
        probeMisses.clearFailures();
        Log.w(TAG, "parked silent MTProto address " + address + ":" + port
                + " for " + SILENT_ADDRESS_COOLDOWN_MS + "ms; next retry will select another candidate");
    }

    /** Requested address followed only by the stock-configured aliases for its own DC. */
    static List<String> candidatesFor(String host, int port) {
        List<String> out = new ArrayList<>();
        java.util.Collections.addAll(out, ColgramTelegramDcAddresses.candidatesFor(host));
        return out;
    }

    /** The "a.b.c." part of an IPv4 literal for grouping a short-lived negative cache. */
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
        if (ColgramTelegramDcAddresses.isKnownAddress(host)) return true;
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

    /**
     * Quick "can this network even route to Telegram" check, for startup ordering.
     *
     * Probes two production DC addresses on 443 with a short budget. Any answer means the
     * network still routes Telegram (possibly behind signature filtering, which is what the
     * desync listener is for); both refused or dropped means an IP-level block, where only a
     * real proxy carries traffic. Returns the winning RTT in ms, or -1.
     */
    public static int telegramDcProbe() {
        long startedAt = System.currentTimeMillis();
        String[][] candidates = {
                {"149.154.167.51", "443"},
                {"149.154.175.50", "443"},
        };
        for (String[] c : candidates) {
            try {
                int port = Integer.parseInt(c[1]);
                if (probe(1500, c[0], port)) {
                    return (int) Math.max(1L, System.currentTimeMillis() - startedAt);
                }
            } catch (Throwable ignored) {}
        }
        return -1;
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
