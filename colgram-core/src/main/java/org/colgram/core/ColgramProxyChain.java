package org.colgram.core;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chained proxy transport: a loopback TCP forwarder that reaches a blocked proxy through a
 * relay that is itself reachable.
 *
 * Why this exists. Measured on the network this project is developed on, every one of the 85
 * Telegram DC endpoints was unreachable while ordinary HTTPS answered in ~100 ms, i.e. the
 * filtering is per-IP, not per-name. Public MTProxy lists are full of nodes whose IPs are on
 * the same blocklists: the node is alive and would happily carry traffic, but this client
 * cannot open a socket to it. Telegram's own transports cannot chain - MTProto and SOCKS5 each
 * open exactly one outbound connection - so a blocked-but-working proxy was simply unusable.
 *
 * A forwarder closes that gap. Telegram connects to 127.0.0.1:port as if it were an ordinary
 * MTProto (or SOCKS5) proxy; this class accepts that connection, opens the real one to the
 * target *through* a relay, and copies bytes in both directions. Neither end knows. The same
 * trick is what upstream's own WebProxyTransport does for wss:// proxies, which is the proof
 * that a loopback port is a legitimate thing to hand ConnectionsManager.
 *
 * Relays come in two flavours, both plain TCP:
 *   SOCKS5 - via java.net.Proxy, so the JDK does the handshake.
 *   HTTP CONNECT - written by hand, because HttpURLConnection cannot give back a raw tunnelled
 *                  socket. A CONNECT proxy list is an order of magnitude larger than a SOCKS5
 *                  one, which matters: the relay only has to be reachable from here and able to
 *                  reach the target, it never has to speak MTProto itself.
 */
public final class ColgramProxyChain {

    private static final String TAG = "ColgramProxyChain";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int BUFFER_SIZE = 16 * 1024;
    /** Bound per-candidate route discovery so one blocked node cannot stall the full sweep. */
    private static final int MAX_RELAY_ROUTE_ATTEMPTS = 8;
    /** Hard cap: one listening socket per (target, relay) pair, and pairs are cheap to lose. */
    private static final int MAX_FORWARDERS = 8;

    /** A relay we can tunnel through. Not a Telegram proxy, so it never enters the pool UI. */
    public static final class Relay {
        public final String host;
        public final int port;
        /** true = SOCKS5, false = HTTP CONNECT. */
        public final boolean socks;
        public volatile int rttMs = -1;
        public volatile boolean dead;

        public Relay(String host, int port, boolean socks) {
            this.host = host;
            this.port = port;
            this.socks = socks;
        }

        public String key() {
            return (socks ? "socks5://" : "connect://") + host + ":" + port;
        }

        @Override
        public String toString() {
            return key();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Relay && key().equals(((Relay) o).key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }

    private static class Forwarder {
        final ServerSocket server;
        final String targetHost;
        final int targetPort;
        final Relay relay;
        final Thread acceptThread;
        volatile boolean closed;
        volatile int served;

        Forwarder(ServerSocket server, String targetHost, int targetPort, Relay relay) {
            this.server = server;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.relay = relay;
            this.acceptThread = new Thread(this::acceptLoop, "ColgramChain-" + server.getLocalPort());
            this.acceptThread.setDaemon(true);
        }

        int port() {
            return server.getLocalPort();
        }

        void start() {
            acceptThread.start();
        }

        void close() {
            closed = true;
            try {
                server.close();
            } catch (Throwable ignored) {
            }
        }

        /** Long-lived SOCKS fronts are active routes, not disposable probe sockets. */
        boolean isEvictable() {
            return true;
        }

        private void acceptLoop() {
            while (!closed) {
                Socket client;
                try {
                    client = server.accept();
                } catch (Throwable t) {
                    if (!closed) Log.w(TAG, "accept failed on " + port(), t);
                    return;
                }
                served++;
                Thread pump = new Thread(() -> relayOne(client), "ColgramChain-pump-" + port());
                pump.setDaemon(true);
                pump.start();
            }
        }

        protected void relayOne(Socket client) {
            Socket upstream = null;
            try {
                client.setTcpNoDelay(true);
                if (ColgramRelayMissCache.shouldSkip(relay.key(), targetHost, targetPort, monotonicMs())) {
                    closeQuietly(client);
                    return;
                }
                upstream = connectThrough(relay, targetHost, targetPort, CONNECT_TIMEOUT_MS);
                if (upstream == null) {
                    client.close();
                    ColgramRelayMissCache.recordMiss(relay.key(), targetHost, targetPort, monotonicMs());
                    Log.w(TAG, "relay " + relay + " could not reach " + targetHost + ":" + targetPort);
                    return;
                }
                ColgramRelayMissCache.recordSuccess(relay.key(), targetHost, targetPort);
                upstream.setTcpNoDelay(true);
                final Socket up = upstream;
                Thread toUpstream = new Thread(() -> pump(client, up), "ColgramChain-up-" + port());
                toUpstream.setDaemon(true);
                toUpstream.start();
                pump(up, client);
            } catch (Throwable t) {
                Log.w(TAG, "chain relay error", t);
            } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }
    }

    private static final Map<String, Forwarder> forwarders = new HashMap<>();

    private ColgramProxyChain() {
    }

    /**
     * Loopback port that forwards to {@code targetHost:targetPort} through {@code relay}, opening
     * the forwarder on first use. Returns 0 when no socket could be bound.
     */
    public static synchronized int open(String targetHost, int targetPort, Relay relay) {
        if (targetHost == null || targetHost.isEmpty() || targetPort <= 0 || relay == null) return 0;
        String key = targetHost + ":" + targetPort + "|" + relay.key();
        Forwarder existing = forwarders.get(key);
        if (existing != null && !existing.closed && existing.server.isBound()) {
            return existing.port();
        }
        if (forwarders.size() >= MAX_FORWARDERS) {
            // Evict the least used entry rather than refusing: a stale forwarder costs a file
            // descriptor, and the pairs that matter are re-opened on demand.
            String victim = null;
            int lowest = Integer.MAX_VALUE;
            for (Map.Entry<String, Forwarder> e : forwarders.entrySet()) {
                if (!e.getValue().isEvictable()) continue;
                if (e.getValue().served < lowest) {
                    lowest = e.getValue().served;
                    victim = e.getKey();
                }
            }
            if (victim == null) return 0;
            Forwarder f = forwarders.remove(victim);
            if (f != null) f.close();
        }
        try {
            ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Forwarder forwarder = new Forwarder(server, targetHost, targetPort, relay);
            forwarders.put(key, forwarder);
            forwarder.start();
            Log.i(TAG, "chain open 127.0.0.1:" + forwarder.port() + " -> " + relay
                    + " -> " + targetHost + ":" + targetPort);
            return forwarder.port();
        } catch (Throwable t) {
            Log.e(TAG, "cannot bind chain forwarder", t);
            return 0;
        }
    }

    public static synchronized void close(String targetHost, int targetPort) {
        List<String> gone = new ArrayList<>();
        String prefix = targetHost + ":" + targetPort + "|";
        for (Map.Entry<String, Forwarder> e : forwarders.entrySet()) {
            if (e.getKey().startsWith(prefix)) gone.add(e.getKey());
        }
        for (String key : gone) {
            Forwarder f = forwarders.remove(key);
            if (f != null) f.close();
        }
    }

    public static synchronized void closeAll() {
        for (Forwarder f : forwarders.values()) f.close();
        forwarders.clear();
    }

    public static synchronized boolean isOpen(int localPort) {
        for (Forwarder f : forwarders.values()) {
            if (f.port() == localPort && !f.closed) return true;
        }
        return false;
    }

    public static synchronized int openCount() {
        return forwarders.size();
    }

    /**
     * Can this relay reach this target, and how fast? Opens a real tunnelled connection, measures
     * it and tears it down. A relay that fails here is marked dead so the caller stops offering it.
     *
     * @return round-trip milliseconds, or -1 when the relay could not deliver the connection.
     */
    public static int probe(Relay relay, String targetHost, int targetPort, int timeoutMs) {
        if (relay == null || relay.dead) return -1;
        if (ColgramRelayMissCache.shouldSkip(relay.key(), targetHost, targetPort, monotonicMs())) return -1;
        long startedAt = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = connectThrough(relay, targetHost, targetPort, timeoutMs);
            if (socket == null) {
                ColgramRelayMissCache.recordMiss(relay.key(), targetHost, targetPort, monotonicMs());
                return -1;
            }
            int rtt = (int) Math.max(1L, System.currentTimeMillis() - startedAt);
            relay.rttMs = rtt;
            ColgramRelayMissCache.recordSuccess(relay.key(), targetHost, targetPort);
            return rtt;
        } catch (Throwable t) {
            ColgramRelayMissCache.recordMiss(relay.key(), targetHost, targetPort, monotonicMs());
            return -1;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Return the fastest measured relay that can actually open a tunnel to this target.
     * A relay's TCP port being open says nothing about its ability to reach a particular blocked
     * proxy; testing only the fastest relay made every other relay irrelevant whenever it could
     * not reach that one host. Try a small RTT-ordered set and cache target-specific misses.
     */
    public static Relay pickReachableRelay(List<Relay> candidates, String targetHost,
                                            int targetPort, int timeoutMs) {
        if (candidates == null || candidates.isEmpty() || targetHost == null || targetPort <= 0) {
            return null;
        }
        List<Relay> ordered = new ArrayList<>();
        for (Relay relay : candidates) {
            if (relay != null && !relay.dead && relay.rttMs >= 0) ordered.add(relay);
        }
        ordered.sort((a, b) -> Integer.compare(a.rttMs, b.rttMs));
        int attempted = 0;
        for (Relay relay : ordered) {
            if (attempted++ >= MAX_RELAY_ROUTE_ATTEMPTS) break;
            if (probe(relay, targetHost, targetPort, timeoutMs) >= 0) return relay;
        }
        return null;
    }

    /**
     * Loopback HTTP proxy front: it accepts the ordinary `CONNECT host:port` a Java
     * HttpURLConnection sends, answers 200, and then pumps that connection through the relay.
     *
     * This is what lets the bot API and the mail providers use a relay at all. They speak
     * `HttpsURLConnection`, which cannot be handed a pre-tunnelled socket - but it will happily
     * use an HTTP proxy, and pointing it here keeps the real hostname in the URL, so SNI and
     * certificate validation stay correct while the bytes leave through the tunnel.
     */
    public static synchronized int openProxyFront(Relay relay) {
        if (relay == null) return 0;
        String key = "front|" + relay.key();
        Forwarder existing = forwarders.get(key);
        if (existing != null && !existing.closed && existing.server.isBound()) {
            return existing.port();
        }
        try {
            ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Front front = new Front(server, relay);
            forwarders.put(key, front);
            front.start();
            Log.i(TAG, "proxy front 127.0.0.1:" + front.port() + " -> " + relay);
            return front.port();
        } catch (Throwable t) {
            Log.e(TAG, "cannot bind proxy front", t);
            return 0;
        }
    }

    /**
     * Bind a local SOCKS5 endpoint whose CONNECT requests leave through {@code relay}.
     * Telegram's native proxy checker can validate this endpoint like an ordinary SOCKS5
     * proxy, while the relay itself can be an HTTP CONNECT tunnel that reaches a blocked DC.
     */
    public static synchronized int openSocks5Front(Relay relay) {
        if (relay == null || relay.dead) return 0;
        String key = "socks-front|" + relay.key();
        Forwarder existing = forwarders.get(key);
        if (existing != null && !existing.closed && existing.server.isBound()) {
            return existing.port();
        }
        try {
            ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            SocksFront front = new SocksFront(server, relay);
            forwarders.put(key, front);
            front.start();
            Log.i(TAG, "SOCKS5 front 127.0.0.1:" + front.port() + " -> " + relay);
            return front.port();
        } catch (Throwable t) {
            Log.e(TAG, "cannot bind SOCKS5 relay front", t);
            return 0;
        }
    }

    private static final class SocksFront extends Forwarder {
        private final Relay uplink;

        SocksFront(ServerSocket server, Relay relay) {
            super(server, null, 0, relay);
            this.uplink = relay;
        }

        @Override
        boolean isEvictable() {
            return false;
        }

        @Override
        protected void relayOne(Socket client) {
            Socket upstream = null;
            try {
                client.setTcpNoDelay(true);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                int version = ColgramSocks5Codec.readByte(in);
                int methodCount = ColgramSocks5Codec.readByte(in);
                byte[] methods = ColgramSocks5Codec.readFully(in, methodCount);
                boolean noAuth = false;
                for (byte method : methods) {
                    if ((method & 0xff) == 0) noAuth = true;
                }
                out.write(new byte[]{5, (byte) (version == 5 && noAuth ? 0 : 0xff)});
                out.flush();
                if (version != 5 || !noAuth) return;

                byte[] request = ColgramSocks5Codec.readFully(in, 4);
                if ((request[0] & 0xff) != 5) {
                    writeSocksReply(out, 1);
                    return;
                }
                if ((request[1] & 0xff) != 1) {
                    writeSocksReply(out, 7);
                    return;
                }

                String host;
                int addressType = request[3] & 0xff;
                if (addressType == 1) {
                    host = InetAddress.getByAddress(ColgramSocks5Codec.readFully(in, 4)).getHostAddress();
                } else if (addressType == 3) {
                    int length = ColgramSocks5Codec.readByte(in);
                    if (length == 0) {
                        writeSocksReply(out, 8);
                        return;
                    }
                    host = new String(ColgramSocks5Codec.readFully(in, length), "US-ASCII");
                } else if (addressType == 4) {
                    host = InetAddress.getByAddress(ColgramSocks5Codec.readFully(in, 16)).getHostAddress();
                } else {
                    writeSocksReply(out, 8);
                    return;
                }
                byte[] portBytes = ColgramSocks5Codec.readFully(in, 2);
                int port = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);
                if (port <= 0) {
                    writeSocksReply(out, 1);
                    return;
                }

                if (ColgramRelayMissCache.shouldSkip(uplink.key(), host, port, monotonicMs())) {
                    writeSocksReply(out, 5);
                    return;
                }
                upstream = connectThrough(uplink, host, port, CONNECT_TIMEOUT_MS);
                if (upstream == null) {
                    ColgramRelayMissCache.recordMiss(uplink.key(), host, port, monotonicMs());
                    writeSocksReply(out, 5);
                    return;
                }
                ColgramRelayMissCache.recordSuccess(uplink.key(), host, port);
                writeSocksReply(out, 0);
                final Socket up = upstream;
                Thread toUpstream = new Thread(() -> pump(client, up), "ColgramSocksFront-up");
                toUpstream.setDaemon(true);
                toUpstream.start();
                pump(upstream, client);
            } catch (Throwable t) {
                // Plain TCP sweepers intentionally close without a SOCKS greeting; that EOF is
                // expected and should not look like a failed relay in logcat.
                if (!(t instanceof java.io.EOFException)) {
                    Log.w(TAG, "SOCKS5 relay front request failed", t);
                }
            } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }

        private static void writeSocksReply(OutputStream out, int code) throws java.io.IOException {
            out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
        }
    }

    /** A listening socket that terminates the client's CONNECT and forwards it through a relay. */
    private static final class Front extends Forwarder {
        private final Relay uplink;

        Front(ServerSocket server, Relay relay) {
            super(server, null, 0, relay);
            this.uplink = relay;
        }

        @Override
        protected void relayOne(Socket client) {
            Socket upstream = null;
            try {
                client.setTcpNoDelay(true);
                java.io.InputStream in = client.getInputStream();
                String request = readLine(in);
                if (request == null) return;
                drainHeaders(in);
                String[] parts = request.split("\\s+");
                if (parts.length < 2 || !parts[0].equalsIgnoreCase("CONNECT")) {
                    client.getOutputStream().write(
                            "HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes("UTF-8"));
                    return;
                }
                int colon = parts[1].lastIndexOf(':');
                String host = colon > 0 ? parts[1].substring(0, colon) : parts[1];
                int port = colon > 0 ? Integer.parseInt(parts[1].substring(colon + 1)) : 443;
                upstream = connectThrough(uplink, host, port, CONNECT_TIMEOUT_MS);
                if (upstream == null) {
                    client.getOutputStream().write(
                            "HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("UTF-8"));
                    return;
                }
                client.getOutputStream().write(
                        "HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8"));
                client.getOutputStream().flush();
                final Socket up = upstream;
                Thread toUpstream = new Thread(() -> pump(client, up), "ColgramFront-up");
                toUpstream.setDaemon(true);
                toUpstream.start();
                pump(up, client);
            } catch (Throwable t) {
                Log.w(TAG, "proxy front request failed", t);
            } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }

        private static String readLine(java.io.InputStream in) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static void drainHeaders(java.io.InputStream in) throws java.io.IOException {
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                // The request headers are not needed: CONNECT targets came from the first line.
            }
        }
    }

    /**
     * One tunnelled connection. SOCKS5 goes through java.net.Proxy; HTTP CONNECT is spoken by
     * hand because the platform's HTTP stack will not hand back the raw socket afterwards.
     */
    private static Socket connectThrough(Relay relay, String host, int port, int timeoutMs) {
        try {
            InetSocketAddress target = new InetSocketAddress(host, port);
            if (relay.socks) {
                Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS,
                        new InetSocketAddress(relay.host, relay.port)));
                try {
                    socket.connect(target, timeoutMs);
                    return socket;
                } catch (Throwable t) {
                    closeQuietly(socket);
                    return null;
                }
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(relay.host, relay.port), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                OutputStream out = socket.getOutputStream();
                String request = "CONNECT " + host + ":" + port + " HTTP/1.1\r\n"
                        + "Host: " + host + ":" + port + "\r\n"
                        + "User-Agent: Colgram/1.0\r\n"
                        + "\r\n";
                out.write(request.getBytes("UTF-8"));
                out.flush();
                if (!readConnectOk(socket.getInputStream())) {
                    closeQuietly(socket);
                    return null;
                }
                // Whatever the proxy answered is consumed; from here the socket is a bare tunnel
                // and must not carry a read timeout, or an idle MTProto connection would die.
                socket.setSoTimeout(0);
                return socket;
            } catch (Throwable t) {
                closeQuietly(socket);
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads the CONNECT status line plus headers and reports whether the tunnel was granted. */
    private static boolean readConnectOk(InputStream in) {
        try {
            String status = readHttpLine(in);
            if (status == null) return false;
            // "HTTP/1.1 200 Connection established" is the common form; anything 2xx is a tunnel.
            int space = status.indexOf(' ');
            if (space < 0 || space + 4 > status.length()) return false;
            String code = status.substring(space + 1, space + 4);
            if (!code.startsWith("2")) return false;
            // Drain headers through the empty line, including proxies that return no headers.
            for (int i = 0; i < 100; i++) {
                String header = readHttpLine(in);
                if (header == null) return false;
                if (header.isEmpty()) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readHttpLine(InputStream in) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') line.setLength(length - 1);
                return line.toString();
            }
            if (line.length() < 8192) line.append((char) c);
        }
        return null;
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Throwable ignored) {
            // A closed peer is the normal way a tunnel ends; there is nothing to report.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    // =====================================================================================
    // Mimic front
    // =====================================================================================
    //
    // A direct forwarder (no relay) whose only job is to reshape the first client packet: the
    // FakeTLS ClientHello is rewritten to a browser fingerprint and sent in 2-3 TCP segments.
    // Telegram connects to 127.0.0.1:port exactly as it does to a chain front, speaks its
    // ordinary MTProto-proxy protocol, and what leaves the device is a hello that TSPU cannot
    // match against its tgnet signature. Everything after the first packet is relayed as-is.

    /**
     * Bind a loopback front to {@code targetHost:targetPort} that rewrites and fragments the
     * client's first packet. Returns the local port, or 0 when no socket could be bound.
     */
    public static synchronized int openMimicFront(String targetHost, int targetPort) {
        if (targetHost == null || targetHost.isEmpty() || targetPort <= 0) return 0;
        String key = "mimic|" + targetHost + ":" + targetPort;
        Forwarder existing = forwarders.get(key);
        if (existing != null && !existing.closed && existing.server.isBound()) {
            return existing.port();
        }
        if (forwarders.size() >= MAX_FORWARDERS) {
            // The mimic front for the currently applied proxy must win an eviction slot over
            // stale chain fronts: prefer evicting the least-served non-mimic forwarder.
            String victim = null;
            int lowest = Integer.MAX_VALUE;
            for (Map.Entry<String, Forwarder> e : forwarders.entrySet()) {
                if (!e.getValue().isEvictable() || e.getKey().startsWith("mimic|")) continue;
                if (e.getValue().served < lowest) {
                    lowest = e.getValue().served;
                    victim = e.getKey();
                }
            }
            if (victim == null) return 0;
            Forwarder f = forwarders.remove(victim);
            if (f != null) f.close();
        }
        try {
            ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            MimicFront front = new MimicFront(server, targetHost, targetPort);
            forwarders.put(key, front);
            front.start();
            Log.i(TAG, "mimic front 127.0.0.1:" + front.port() + " -> " + targetHost + ":" + targetPort);
            return front.port();
        } catch (Throwable t) {
            Log.e(TAG, "cannot bind mimic front", t);
            return 0;
        }
    }

    private static final class MimicFront extends Forwarder {
        MimicFront(ServerSocket server, String host, int port) {
            super(server, host, port, null);
        }

        @Override
        boolean isEvictable() {
            return false;
        }

        @Override
        protected void relayOne(Socket client) {
            Socket upstream = null;
            try {
                client.setTcpNoDelay(true);
                upstream = new Socket();
                upstream.setTcpNoDelay(true);
                upstream.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS);

                byte[] first = ColgramInitialPacketReader.readPrefix(client, 517, 120);
                if (ColgramTlsMimic.needsMoreBytes(first)) {
                    // The hello was segmented by TCP; give it one more coalescing window rather
                    // than rewriting (and thereby corrupting) a truncated record.
                    byte[] more = ColgramInitialPacketReader.readPrefix(client, 517, 150);
                    byte[] joined = new byte[first.length + more.length];
                    System.arraycopy(first, 0, joined, 0, first.length);
                    System.arraycopy(more, 0, joined, first.length, more.length);
                    first = joined;
                }
                OutputStream out = upstream.getOutputStream();
                if (ColgramTlsMimic.looksLikeClientHello(first)) {
                    byte[] rewritten = ColgramTlsMimic.rewrite(first);
                    java.util.Random rnd = new java.util.Random();
                    for (byte[] segment : ColgramTlsMimic.fragment(rewritten, rnd)) {
                        out.write(segment);
                        out.flush();
                        Thread.sleep(1 + rnd.nextInt(3));
                    }
                } else if (first.length > 0) {
                    out.write(first);
                    out.flush();
                }
                final Socket up = upstream;
                Thread toUpstream = new Thread(() -> pump(client, up), "ColgramMimic-up");
                toUpstream.setDaemon(true);
                toUpstream.start();
                pump(up, client);
            } catch (Throwable t) {
                Log.w(TAG, "mimic front relay failed for " + targetHost + ":" + targetPort, t);
            } finally {
                closeQuietly(client);
                closeQuietly(upstream);
            }
        }
    }
}
