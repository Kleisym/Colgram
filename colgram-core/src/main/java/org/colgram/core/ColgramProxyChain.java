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

    private static final class Forwarder {
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

        private void relayOne(Socket client) {
            Socket upstream = null;
            try {
                client.setTcpNoDelay(true);
                upstream = connectThrough(relay, targetHost, targetPort, CONNECT_TIMEOUT_MS);
                if (upstream == null) {
                    client.close();
                    relay.dead = true;
                    Log.w(TAG, "relay " + relay + " could not reach " + targetHost + ":" + targetPort);
                    return;
                }
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
                if (e.getValue().served < lowest) {
                    lowest = e.getValue().served;
                    victim = e.getKey();
                }
            }
            if (victim != null) {
                Forwarder f = forwarders.remove(victim);
                if (f != null) f.close();
            }
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
        long startedAt = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = connectThrough(relay, targetHost, targetPort, timeoutMs);
            if (socket == null) {
                relay.dead = true;
                return -1;
            }
            int rtt = (int) Math.max(1L, System.currentTimeMillis() - startedAt);
            relay.rttMs = rtt;
            relay.dead = false;
            return rtt;
        } catch (Throwable t) {
            relay.dead = true;
            return -1;
        } finally {
            closeQuietly(socket);
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
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (line.length() < 512 && c != '\r') line.append((char) c);
            }
            String status = line.toString();
            // "HTTP/1.1 200 Connection established" is the common form; anything 2xx is a tunnel.
            int space = status.indexOf(' ');
            if (space < 0 || space + 4 > status.length()) return false;
            String code = status.substring(space + 1, space + 4);
            if (!code.startsWith("2")) return false;
            // Drain the remaining headers so the first byte the caller writes is tunnel payload.
            int prev = -1;
            while ((c = in.read()) != -1) {
                if (prev == '\n' && c == '\r') {
                    int last = in.read();
                    return last == '\n';
                }
                if (prev == '\n' && c == '\n') return true;
                prev = c;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
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
}
