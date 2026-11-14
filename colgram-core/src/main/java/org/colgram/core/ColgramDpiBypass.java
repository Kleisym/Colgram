package org.colgram.core;

import android.util.Log;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramDpiBypass — Embedded Local SOCKS5 Proxy with Adaptive TCP Desync.
 *
 * Evades TSPU / RKN DPI by mangling the shape of our own outgoing packets so the DPI
 * mis-parses the stream while the real destination reassembles it correctly.
 *
 * 1. Multiple desync strategies (splitting at randomised offsets, TLS-record splitting,
 *    TCP urgent/OOB bytes), auto-probed per connection — see the strategy block below.
 * 2. Bounded direct dialing of the requested port and known same-DC aliases.
 * 3. Completely local (127.0.0.1:9876): no third-party server in the path at all.
 *
 * Why local matters: a proxy is a server somebody runs, and its operator sees your IP,
 * timing and volume by design. This listener is the only option with NO third party in
 * the chain, so it is both the most private and the only one that cannot be switched off
 * by a host going down. That is why it is preferred over public proxies rather than
 * treated as a last resort.
 *
 * Honest limits, so nobody has to rediscover them:
 *   * A DPI that blocks by DESTINATION (Russian "white list" drills) is not defeated by
 *     desync at all — the packet shape is irrelevant when the address itself is refused.
 *   * The strongest known technique, a decoy packet with a low IP TTL that reaches the DPI
 *     but expires before the destination, needs setsockopt(IP_TTL) and therefore JNI.
 *     Java cannot set IP TTL. See the notes in the project memory.
 */
public class ColgramDpiBypass {

    private static final String TAG = "ColgramDpiBypass";
    public static final int LOCAL_PORT = 9876;
    /**
     * The port the listener actually bound.
     *
     * <p>It starts at LOCAL_PORT and moves only when that port is held by something this process did
     * not let go of. The field exists because the alternative - failing to bind and reporting the
     * bypass down - is the exact symptom of the bypass not working, so a busy preferred port has to be
     * survivable. Everything that hands the port to Telegram reads this rather than the constant.
     */
    private static volatile int activePort = LOCAL_PORT;
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = false;
    /** True only once the socket is actually bound and accepting. */
    private static volatile boolean bound = false;
    private static volatile boolean startScheduled = false;
    private static Thread deferredStart;
    /** Keep the proxy listener out of the fragile application-startup window. */
    private static final long START_DELAY_MS = 8000L;
    private static final ExecutorService workerPool = Executors.newCachedThreadPool();
    private static final long CONNECT_FAILURE_BASE_DELAY_MS = 3000L;
    private static final long CONNECT_FAILURE_MAX_DELAY_MS = 60000L;
    private static final ColgramConnectFailureBackoff connectFailures =
            new ColgramConnectFailureBackoff(CONNECT_FAILURE_BASE_DELAY_MS,
                    CONNECT_FAILURE_MAX_DELAY_MS);
    private static final java.util.concurrent.Semaphore DIAL_GROUPS = new java.util.concurrent.Semaphore(3);
    private static final int UPSTREAM_WAIT = 0, UPSTREAM_DIAL = 1, UPSTREAM_NO_SOCKET = 2,
            UPSTREAM_SOCKET = 3, UPSTREAM_SILENT = 4, UPSTREAM_REPLY = 5;
    private static volatile int upstreamState = UPSTREAM_WAIT;

    private static volatile long listenerGeneration;

    public static synchronized void start() {
        if (isRunning || startScheduled) return;
        startScheduled = true;
        final long generation = ++listenerGeneration;
        deferredStart = new Thread(() -> {
            try {
                Thread.sleep(START_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (ColgramDpiBypass.class) {
                if (generation != listenerGeneration || !startScheduled) return;
                startScheduled = false;
                deferredStart = null;
                startNow();
            }
        }, "colgram-dpi-deferred");
        deferredStart.setDaemon(true);
        deferredStart.start();
    }

    /** An explicit request promotes a pending delayed start instead of waiting eight seconds. */
    public static synchronized void startImmediately() {
        if (isRunning && bound) return;
        if (deferredStart != null) deferredStart.interrupt();
        deferredStart = null;
        startScheduled = false;
        startNow();
    }

    /** Bind and publish under the same lock as stop(); only accept runs asynchronously. */
    private static synchronized void startNow() {
        if (isRunning && bound && serverSocket != null && !serverSocket.isClosed()) return;
        final long generation = ++listenerGeneration;
        ServerSocket candidate = null;
        try {
            candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            try {
                candidate.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
            } catch (java.net.BindException occupied) {
                closeQuietly(candidate);
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress("127.0.0.1", 0));
            }
            final ServerSocket listener = candidate;
            serverSocket = listener;
            activePort = listener.getLocalPort();
            upstreamState = UPSTREAM_WAIT;
            resetCarriageCounters();
            bound = true;
            isRunning = true;
            workerPool.execute(() -> acceptClients(listener, generation));
            Log.d(TAG, "ColgramDpiBypass started on 127.0.0.1:" + activePort);
        } catch (Exception failure) {
            closeQuietly(candidate);
            serverSocket = null;
            activePort = LOCAL_PORT;
            bound = false;
            isRunning = false;
            Log.e(TAG, "ColgramDpiBypass bind failed", failure);
        }
    }

    private static synchronized boolean ownsListener(ServerSocket listener, long generation) {
        return generation == listenerGeneration && serverSocket == listener && isRunning;
    }

    private static void acceptClients(ServerSocket listener, long generation) {
        try {
            while (ownsListener(listener, generation)) {
                Socket client = listener.accept();
                synchronized (ColgramDpiBypass.class) {
                    if (!ownsListener(listener, generation)) {
                        closeQuietly(client);
                        break;
                    }
                    workerPool.execute(() -> handleClient(client, null, generation));
                }
            }
        } catch (Exception failure) {
            if (ownsListener(listener, generation)) Log.w(TAG, "DPI accept loop stopped", failure);
        } finally {
            closeQuietly(listener);
            synchronized (ColgramDpiBypass.class) {
                // An old accept loop must never clear a newer listener's readiness.
                if (serverSocket == listener && listenerGeneration == generation) {
                    serverSocket = null;
                    bound = false;
                    isRunning = false;
                    activePort = LOCAL_PORT;
                }
            }
        }
    }

    public static synchronized void stop() {
        ++listenerGeneration;
        isRunning = false;
        bound = false;
        startScheduled = false;
        if (deferredStart != null) deferredStart.interrupt();
        deferredStart = null;
        closeQuietly(serverSocket);
        serverSocket = null;
        activePort = LOCAL_PORT;
        upstreamState = UPSTREAM_WAIT;
        clearConnectionFailureBackoff();
        Log.d(TAG, "ColgramDpiBypass stopped");
    }

    /** The port the listener is actually on, which is LOCAL_PORT unless that was busy. */
    public static int activePort() {
        return activePort;
    }

    public static String upstreamStatus() {
        if (!bound) return "локальный транспорт не запущен";
        switch (upstreamState) {
            case UPSTREAM_DIAL: return "подключение к серверу Telegram";
            case UPSTREAM_NO_SOCKET: return "TCP до Telegram не открылся";
            case UPSTREAM_SOCKET: return "TCP открыт · ждём ответ Telegram";
            case UPSTREAM_SILENT: return "TCP открыт · Telegram не ответил";
            case UPSTREAM_REPLY: return "трафик получен · проверяем протокол Telegram";
            default: return "локальный транспорт запущен · ждём запрос Telegram";
        }
    }

    /**
     * Find a loopback port nothing is holding.
     *
     * <p>It binds port 0 and reads back what the kernel gave it, which is the only way to know a port is
     * free - a scan would race every other process on the device, and the preferred port being busy is
     * exactly the situation where another process may take any port a scan picks.
     *
     * @return a bound port, or -1 when even that fails
     */
    private static int findFreePort() {
        ServerSocket probe = null;
        try {
            probe = new ServerSocket();
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            return probe.getLocalPort();
        } catch (Exception e) {
            Log.e(TAG, "cannot find a free loopback port", e);
            return -1;
        } finally {
            closeQuietly(probe);
        }
    }

    /**
     * Rebind the listener if it is down, safely.
     *
     * Called from the proxy health check. A ServerSocket cannot be rebound while the old
     * one still holds the port, so the dead instance is torn down first — otherwise the
     * new bind fails with EADDRINUSE and recovery silently never happens.
     *
     * @return true if the listener is accepting once this returns
     */
    public static synchronized boolean restartIfDead() {
        if (isBound() && serverSocket != null && !serverSocket.isClosed()) {
            return true;
        }
        Log.w(TAG, "restarting the local DPI listener (isRunning=" + isRunning + ", bound=" + bound + ")");
        stop();
        startNow();
        return true;
    }

    /**
     * Wait for the listener, without holding the class monitor.
     *
     * {@link #restartIfDead()} used to call awaitReady() while still synchronized, which
     * blocked every other DPI entry point for up to 5s. The monitor is only needed to
     * mutate the socket state, not to wait for it, so the wait is split out here.
     */
    public static boolean restartIfDeadAndWait(long timeoutMs) {
        if (!restartIfDead()) return true;
        return awaitReady(timeoutMs);
    }

    public static boolean isRunning() {
        return isRunning;
    }

    /** True once the listener is bound and accepting connections. */
    public static boolean isBound() {
        return bound;
    }

    /**
     * Block until the listener is accepting, or the timeout elapses.
     *
     * Needed because this listener is now the FIRST entry in the proxy pool: Telegram must
     * not be pointed at 127.0.0.1:9876 before something is listening there. start() binds
     * on a deferred thread (START_DELAY_MS), so without waiting, the app would apply a
     * proxy pointing at a closed port, Telegram would report a proxy error, and the
     * rotator would move OFF the one option that needs no third party - the opposite of
     * what the ordering is for.
     *
     * @return true if the listener is accepting
     */
    public static boolean awaitReady(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bound && serverSocket != null && !serverSocket.isClosed()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Log.w(TAG, "DPI listener not ready after " + timeoutMs + "ms");
        return false;
    }

    /** Separate, authenticated listener: does not change the app-wide bypass preference. */
    static final class CallListener implements java.io.Closeable {
        private final ServerSocket listener;
        private final java.util.Set<Socket> sockets = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());
        private final java.util.concurrent.Semaphore clients = new java.util.concurrent.Semaphore(32);
        private final String username;
        private final String password;
        private volatile android.net.VpnService vpn;
        private volatile boolean closed;

        CallListener() throws java.io.IOException {
            java.security.SecureRandom random = new java.security.SecureRandom();
            byte[] secret = new byte[24];
            random.nextBytes(secret);
            username = "colgram-call";
            password = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP);
            java.util.Arrays.fill(secret, (byte) 0);
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            workerPool.execute(this::accept);
        }

        String config() {
            return ColgramProfileBuilder.forCallSocks("127.0.0.1", listener.getLocalPort(),
                    username, password);
        }

        void attach(android.net.VpnService service) {
            if (closed) throw new IllegalStateException("Local call listener has closed");
            vpn = service;
        }

        private void accept() {
            try {
                while (!closed) {
                    Socket client = listener.accept();
                    if (!clients.tryAcquire()) {
                        closeQuietly(client);
                        continue;
                    }
                    if (!track(client)) {
                        clients.release();
                        continue;
                    }
                    workerPool.execute(() -> handleClient(client, this, -1L));
                }
            } catch (java.io.IOException ignored) {
                close();
            }
        }

        boolean track(Socket socket) {
            sockets.add(socket);
            if (!closed) return true;
            sockets.remove(socket);
            closeQuietly(socket);
            return false;
        }

        void done(Socket client, Socket upstream) {
            if (sockets.remove(client)) clients.release();
            if (upstream != null) sockets.remove(upstream);
        }

        boolean authenticate(InputStream in, OutputStream out) throws java.io.IOException {
            int version = ColgramSocks5Codec.readByte(in);
            byte[] user = ColgramSocks5Codec.readFully(in, ColgramSocks5Codec.readByte(in));
            byte[] pass = ColgramSocks5Codec.readFully(in, ColgramSocks5Codec.readByte(in));
            boolean valid = version == 1
                    && java.security.MessageDigest.isEqual(user, username.getBytes(StandardCharsets.UTF_8))
                    && java.security.MessageDigest.isEqual(pass, password.getBytes(StandardCharsets.UTF_8));
            java.util.Arrays.fill(pass, (byte) 0);
            out.write(new byte[]{1, (byte) (valid ? 0 : 1)});
            out.flush();
            return valid;
        }

        Socket connect(String host, int port) {
            android.net.VpnService owner = vpn;
            if (owner == null || closed || port <= 0) return null;
            Socket socket = new Socket();
            if (!track(socket)) return null;
            try {
                // No external relay or network-port substitution on a call route.
                // Socket creates its underlying fd lazily; set a socket option before protect.
                socket.setTcpNoDelay(true);
                if (!owner.protect(socket)) throw new java.io.IOException("Socket protection failed");
                socket.connect(new InetSocketAddress(host, port), TELEGRAM_DIAL_BUDGET_MS);
                return socket;
            } catch (Exception failure) {
                sockets.remove(socket);
                closeQuietly(socket);
                return null;
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            vpn = null;
            closeQuietly(listener);
            for (Socket socket : sockets) closeQuietly(socket);
            sockets.clear();
        }
    }

    private static void handleClient(Socket client, CallListener call, long generation) {
        Socket targetSocket = null;
        boolean handedOff = false;
        try {
            // A connection accepted a moment before stop() is still handed to this worker, and serving
            // it would keep a stopped bypass doing work. The socket is closed rather than left to time
            // out, so what the caller sees after a stop is a refused connection and not a fifteen-second
            // wait.
            if (call == null ? !isCurrentListener(generation) : call.closed || call.vpn == null) {
                closeQuietly(client);
                return;
            }
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 1. SOCKS5 greeting
            int ver = ColgramSocks5Codec.readByte(in);
            if (ver != 5) {
                closeQuietly(client);
                return;
            }
            int nmethods = ColgramSocks5Codec.readByte(in);
            if (nmethods <= 0) {
                closeQuietly(client);
                return;
            }
            byte[] methods = ColgramSocks5Codec.readFully(in, nmethods);
            int authMethod = call == null ? 0 : 2;
            boolean supportsAuthMethod = false;
            for (byte method : methods) {
                if (method == authMethod) {
                    supportsAuthMethod = true;
                    break;
                }
            }
            if (!supportsAuthMethod) {
                out.write(new byte[]{0x05, (byte) 0xFF});
                out.flush();
                closeQuietly(client);
                return;
            }

            // Reply: NO AUTH REQUIRED
            out.write(new byte[]{0x05, (byte) authMethod});
            out.flush();
            if (call != null && !call.authenticate(in, out)) return;

            // 2. SOCKS5 request
            int reqVer = ColgramSocks5Codec.readByte(in);
            int cmd = ColgramSocks5Codec.readByte(in);
            int rsv = ColgramSocks5Codec.readByte(in);
            int atyp = ColgramSocks5Codec.readByte(in);
            // The command is logged because "incomplete SOCKS5 request" names neither it nor the
            // client, and the two possible causes -- a front that answers a command it was never asked,
            // and a client that closes mid-handshake -- are indistinguishable without it.
            if (cmd != 1) Log.d(TAG, "socks command 0x" + Integer.toHexString(cmd) + " atyp=" + atyp);

            if (reqVer != 5 || rsv != 0) {
                closeQuietly(client);
                return;
            }
            // CONNECT (0x01) and UDP ASSOCIATE (0x03) are both accepted.
            //
            // Only CONNECT was. Telegram never uses CONNECT for MTProto -- it associates, then
            // sends datagrams -- so every connection that reached this front was answered with
            // command-not-supported and closed, and the log showed only
            //
            //     SOCKS5 handshake/tunnel failed: incomplete SOCKS5 request
            //
            // which names neither the command nor the relay and reads as a dead server rather than a
            // front that answered a question it was never asked.
            if (cmd != 1 && cmd != 3) {
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            String destHost;
            if (atyp == 1) { // IPv4
                byte[] ip = ColgramSocks5Codec.readFully(in, 4);
                destHost = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            } else if (atyp == 3) { // Domain
                int len = ColgramSocks5Codec.readByte(in);
                if (len <= 0) {
                    closeQuietly(client);
                    return;
                }
                byte[] domainBytes = new byte[len];
                domainBytes = ColgramSocks5Codec.readFully(in, len);
                destHost = new String(domainBytes, StandardCharsets.UTF_8);
            } else if (atyp == 4) { // IPv6
                byte[] ip6 = ColgramSocks5Codec.readFully(in, 16);
                destHost = InetAddress.getByAddress(ip6).getHostAddress();
            } else {
                closeQuietly(client);
                return;
            }

            byte[] portBytes = ColgramSocks5Codec.readFully(in, 2);
            int destPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            // An associate is answered with a UDP port, not with a TCP session.
            //
            // Telegram associates, then sends MTProto datagrams to whatever port this reply names.
            // Answering with the address of a TCP socket that was opened for a different purpose, or
            // with the all-zero bound address, means the datagrams have nowhere to go and the flow
            // dies after a handshake that looked successful. So the association is answered here,
            // from a socket that actually receives them.
            if (cmd == 3) {
                if (call != null) {
                    ColgramCallUdpRelay.serve(call.vpn, client, in, out);
                    return;
                }
                java.net.DatagramSocket udp = new java.net.DatagramSocket(0,
                        InetAddress.getByName("127.0.0.1"));
                // The zero address means "accept from wherever this control connection came from",
                // which is exactly right: the client is on loopback.
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0,
                        (byte) (udp.getLocalPort() >>> 8), (byte) udp.getLocalPort()});
                out.flush();
                pumpAssociated(udp, client, generation);
                return;
            }

            // 3. Connect to destination with multi-port fallback & TCP desync
            //
            // Respect any active backoff first: when a full strategy cycle has failed, we
            // deliberately slow new attempts down rather than amplifying Telegram's retry
            // rate into a connection storm.
            if (call != null) {
                targetSocket = call.connect(destHost, destPort);
            } else {
                long waitMs = backoffUntil - System.currentTimeMillis();
                if (waitMs > 0) sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
                if (!isCurrentListener(generation)) return;
                targetSocket = connectWithBackoff(destHost, destPort, generation);
            }
            if (call == null && targetSocket == null && ColgramConfig.isAutoProxyEnabled()
                    && ColgramProxyManager.hasReachableRelays()) {
                // The listener is a local gateway, not a dead end. Direct dial and remap
                // alternatives both refused: hand the connection to a reachable relay front.
                // The relay is a stranger's machine, but it is the ONLY way a destination whose
                // SYN the carrier drops can be reached at all - and it is bounded to Telegram
                // destinations so ordinary traffic never routes through it.
                if (isMtProtoHost(destHost) || ColgramTelegramDcAddresses.isKnownAddress(destHost)) {
                    int chainPort = ColgramProxyManager.openRelayRouteFor(destHost, destPort);
                    if (chainPort > 0) {
                        try {
                            Socket chained = new Socket();
                            chained.setTcpNoDelay(true);
                            chained.connect(new InetSocketAddress("127.0.0.1", chainPort), 3000);
                            targetSocket = chained;
                            Log.i(TAG, "direct dial refused; routed " + destHost + ":" + destPort
                                    + " through relay front 127.0.0.1:" + chainPort);
                        } catch (Throwable t) {
                            Log.d(TAG, "relay front dial failed for " + destHost);
                        }
                    }
                }
            }
            if (call == null && !isCurrentListener(generation)) return;
            if (targetSocket == null) {
                // Per-retry noise from tgnet hammering the listener; a Log.w here floods
                // logcat a hundred lines a second on a network where the DCs refuse TCP.
                Log.d(TAG, "Failed to connect to target: " + destHost + ":" + destPort);
                out.write(new byte[]{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            // SOCKS5 success reply
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();

            client.setSoTimeout(0);
            targetSocket.setSoTimeout(0);

            // 4. Pipe traffic with TCP Desync (1-byte head split)
            // Counted here, where the upstream socket is known to exist: a front that never reached
            // a target has nothing to count, so this separates "the listener is up" from "a
            // connection to Telegram exists behind it".
            if (call == null) relayedConnections.incrementAndGet();
            pipeWithAdvancedDesync(client, targetSocket, call, generation,
                    call == null && ColgramTelegramDcAddresses.isKnownAddress(destHost));
            handedOff = true;

        } catch (Exception e) {
            // A liveness probe sends a greeting and closes without a request, so the EOF
                // that follows is that probe and not a failed connection. Naming it a handshake failure
                // put a permanent red herring in the log for a check that is expected to behave this
                // way; the real outcomes are logged where they happen, per address.
                if (e instanceof java.io.EOFException) {
                    Log.d(TAG, "socks probe closed before a request; expected for a liveness check");
                } else {
                    Log.d(TAG, "SOCKS5 session failed: " + e.getMessage());
                }
        } finally {
            if (!handedOff) {
                closeQuietly(client);
                closeQuietly(targetSocket);
                if (call != null) call.done(client, targetSocket);
            }
        }
    }

    private static boolean isCurrentListener(long generation) {
        return bound && generation == listenerGeneration;
    }

    private static synchronized void publishUpstream(long generation, int state) {
        if (isCurrentListener(generation)) upstreamState = state;
    }

    private static Socket connectWithBackoff(String host, int port, long generation) {
        String endpoint = host.toLowerCase(java.util.Locale.US) + ":" + port;
        long now = SystemClock.elapsedRealtime();
        ColgramConnectFailureBackoff.Decision decision = connectFailures.acquire(endpoint, now);
        if (!decision.granted()) {
            // Concurrency and cooldown are different failures and used to look identical here, so
            // tgnet's ordinary retry burst charged itself a failure per retry and grew the cooldown
            // on an endpoint that had only failed once - or on one that had never been reached.
            Log.d(TAG, decision.coalesced()
                    ? "coalesced duplicate dial for " + endpoint
                    : "skipping direct TCP dial during endpoint cooldown: " + endpoint);
            return null;
        }
        long ticket = decision.ticket();
        if (!DIAL_GROUPS.tryAcquire()) {
            connectFailures.release(endpoint, ticket);
            return null;
        }
        Socket socket;
        boolean telegram = ColgramTelegramDcAddresses.isKnownAddress(host);
        if (telegram) publishUpstream(generation, UPSTREAM_DIAL);
        try {
            socket = establishConnection(host, port);
        } finally {
            DIAL_GROUPS.release();
        }
        if (telegram) publishUpstream(generation, socket == null ? UPSTREAM_NO_SOCKET : UPSTREAM_SOCKET);
        if (socket == null) {
            long delay = connectFailures.finish(endpoint, ticket, false, SystemClock.elapsedRealtime());
            if (delay > 0L) {
                Log.i(TAG, "direct TCP dial failed for " + endpoint + "; retry in " + delay + "ms");
            }
        } else {
            connectFailures.finish(endpoint, ticket, true, SystemClock.elapsedRealtime());
        }
        return socket;
    }

    /** Drop stale dial failures as soon as Android reports a network transition. */
    public static void clearConnectionFailureBackoff() {
        connectFailures.clear();
    }

    /** Keep the requested port; use only exact stock aliases for the same Telegram DC. */
    private static Socket establishConnection(String host, int port) {
        if (port < 1 || port > 65535) return null;
        InetSocketAddress primary = new InetSocketAddress(host, port);
        String[] aliases = ColgramTelegramDcAddresses.candidatesFor(host);
        InetSocketAddress[] alternatives = new InetSocketAddress[aliases.length];
        for (int i = 0; i < aliases.length; i++) alternatives[i] = new InetSocketAddress(aliases[i], port);
        return ColgramSocketConnectRace.connectPreferred(primary, alternatives, TELEGRAM_DIAL_BUDGET_MS);
    }

    /**
     * Whether a host is one of Telegram's own MTProto endpoints, where the multi-port
     * optional user-approved relay fallback is permitted.
     */
    private static boolean isMtProtoHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.US);
        return h.endsWith(".telegram.org")
                || h.endsWith(".t.me")
                || h.contains("telegram.dog")
                || h.startsWith("149.154.")
                || h.startsWith("91.108.")
                || ColgramTelegramDcAddresses.isKnownAddress(h);
    }

    // ==================================================================================
    // Desync strategies
    // ==================================================================================
    //
    // Colgram used to hardcode exactly ONE strategy: split the first packet at a fixed
    // offset with fixed 3 ms sleeps. Two problems with that:
    //
    //   * A fixed offset is the easiest possible thing for a DPI to learn. Real bypass
    //     tools randomise it.
    //   * Which technique gets through depends on the operator's TSPU. One strategy that
    //     works on one ISP does nothing on another's - so a single hardcoded strategy
    //     means "works for some people, silently does nothing for everyone else".
    //
    // So: several strategies, rotated per connection, with automatic probing. A connection
    // that carries real traffic back from the target counts as a success and the current
    // strategy is kept; one that closes without the target ever answering counts as a
    // failure and after a couple of those we move to the next strategy. That is the same
    // core idea as ByeDPI's strategy search, which is what makes those tools work without
    // per-ISP hand-tuning.

    private static final int STRATEGY_COUNT = 6;
    /** Fail a TCP-open-but-silent endpoint promptly; do not leave Telegram spinning indefinitely. */
    private static final int FIRST_REPLY_TIMEOUT_MS = 3000;
    private static final int TELEGRAM_DIAL_BUDGET_MS = 1800;
    private static final int STRATEGY_SPLIT_1 = 0;        // 1 byte | rest
    private static final int STRATEGY_SPLIT_RANDOM = 1;   // random offset | rest
    private static final int STRATEGY_SPLIT_3 = 2;        // three fragments
    private static final int STRATEGY_TLS_RECORD = 3;     // record header | payload
    private static final int STRATEGY_OOB = 4;            // normal byte + urgent byte
    private static final int STRATEGY_SPLIT_OOB = 5;      // split + urgent byte

    private static volatile int currentStrategy = STRATEGY_SPLIT_1;
    private static final int[] strategyFails = new int[STRATEGY_COUNT];
    /** Consecutive dead connections before we try the next strategy. */
    private static final int FAILS_BEFORE_SWITCH = 2;
    /** Set once any strategy has carried real traffic back from the target. */
    private static volatile boolean strategyFound = false;

    /**
     * When a FULL cycle of strategies produced nothing, throttle new attempts.
     *
     * Without this the prober loops forever: Telegram retries, each retry opens a fresh
     * outbound connection, and if every strategy fails we keep cycling. That is a
     * connection storm against hosts that are not going to answer - and through an
     * emulator's NAT it starves the host machine's networking. Backing off lets Telegram's
     * own retry cadence take over instead of us amplifying it.
     */
    private static volatile long backoffUntil = 0L;
    private static final long STRATEGY_BACKOFF_MS = 8000L;
    private static final long MAX_BACKOFF_SLEEP_MS = 2000L;

    /**
     * Whether any desync strategy has actually worked yet.
     *
     * ColgramProxyManager uses this to hold off rotating away from the local listener while
     * the strategy search is still running. Without it, the two features fight: onProxyError
     * fires on the first failed connection and the rotator abandons the listener before its
     * prober has tried anything.
     */
    public static boolean hasWorkingStrategy() {
        return strategyFound;
    }

    // ==================================================================================
    // Carriage counters
    // ==================================================================================

    private static final java.util.concurrent.atomic.AtomicLong toTargetBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong fromTargetBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong relayedConnections =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong completedRelays =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * What this listener has actually carried, rather than what it looks like from the outside.
     *
     * <p>Every earlier check of the bypass asked whether the port accepts a connection, which a
     * bound socket answers whether or not anything is ever relayed. That is the gap behind
     * "the bypass is on and Telegram still does not connect": the switch reads true, the socket
     * accepts, and no byte has ever crossed it. These counters are what close the gap, and they are
     * counted per direction so a front that only ever writes upstream cannot pass for one that
     * carries a conversation.
     */
    public static long bytesToTarget() {
        return toTargetBytes.get();
    }

    public static long bytesFromTarget() {
        return fromTargetBytes.get();
    }

    /** Connections the front opened an upstream socket for. */
    public static long relayedConnections() {
        return relayedConnections.get();
    }

    /** Relays that finished, whether the target answered or closed first. */
    public static long completedRelays() {
        return completedRelays.get();
    }

    /** Zero every counter, so a test can measure one run without restarting the listener. */
    public static void resetCarriageCounters() {
        toTargetBytes.set(0);
        fromTargetBytes.set(0);
        relayedConnections.set(0);
        completedRelays.set(0);
    }

    private static synchronized int pickStrategy() {
        return currentStrategy;
    }

    /**
     * Record how a connection went and advance the strategy if this one keeps dying.
     *
     * `carried` is whether the TARGET ever sent us bytes. That is the honest signal: a
     * desync that the DPI rejects never gets a reply from the destination, so bytes
     * from the target is what separates "connected" from "screamed into the void".
     */
    private static synchronized void reportStrategyResult(int strategy, boolean carried) {
        if (carried) {
            strategyFails[strategy] = 0;
            strategyFound = true;
            if (currentStrategy != strategy) {
                currentStrategy = strategy;
                Log.i(TAG, "Desync strategy " + strategy + " works; pinning to it");
            }
            return;
        }
        strategyFails[strategy]++;
        if (strategyFails[strategy] >= FAILS_BEFORE_SWITCH && currentStrategy == strategy) {
            int next = (strategy + 1) % STRATEGY_COUNT;
            currentStrategy = next;
            strategyFails[strategy] = 0;
            if (next == 0) {
                // Full cycle, nothing worked. Stop hammering: the block is probably not
                // desync-defeatable from here (e.g. a destination-based white list).
                backoffUntil = System.currentTimeMillis() + STRATEGY_BACKOFF_MS;
                Log.w(TAG, "No desync strategy is getting through; backing off "
                        + STRATEGY_BACKOFF_MS + "ms");
            } else {
                Log.i(TAG, "Desync strategy " + strategy + " is not getting through; trying " + next);
            }
        }
    }

    /**
     * Send the first client->target packet using the given desync strategy.
     *
     * @return number of bytes the target sent back before the connection closed, so the
     *         caller can tell a real session from a rejected one.
     */
    private static void sendDesynced(Socket dest, OutputStream out, byte[] buf, int len, int strategy)
            throws Exception {
        boolean isTls = len > 5 && buf[0] == 0x16 && buf[1] == 0x03;
        java.util.Random rnd = new java.util.Random();

        switch (strategy) {
            case STRATEGY_SPLIT_RANDOM: {
                int cut = 1 + rnd.nextInt(Math.max(1, Math.min(len - 1, 64)));
                out.write(buf, 0, cut);
                out.flush();
                sleep(1);
                out.write(buf, cut, len - cut);
                out.flush();
                break;
            }
            case STRATEGY_SPLIT_3: {
                int a = 1 + rnd.nextInt(Math.max(1, Math.min(len - 2, 32)));
                int b = a + 1 + rnd.nextInt(Math.max(1, len - a - 1));
                out.write(buf, 0, a);
                out.flush();
                sleep(1);
                out.write(buf, a, b - a);
                out.flush();
                sleep(1);
                out.write(buf, b, len - b);
                out.flush();
                break;
            }
            case STRATEGY_TLS_RECORD: {
                if (isTls && len > 5) {
                    // Split across the 5-byte TLS record header, then again inside the
                    // ClientHello so the SNI never lands in a single segment.
                    out.write(buf, 0, 5);
                    out.flush();
                    sleep(2);
                    int mid = 5 + Math.min(20, len - 5);
                    out.write(buf, 5, mid - 5);
                    out.flush();
                    sleep(2);
                    out.write(buf, mid, len - mid);
                    out.flush();
                } else {
                    out.write(buf, 0, 1);
                    out.flush();
                    sleep(2);
                    out.write(buf, 1, len - 1);
                    out.flush();
                }
                break;
            }
            case STRATEGY_OOB: {
                // TCP urgent pointer. Java exposes this as sendUrgentData(); the DPI sees
                // an out-of-band byte interleaved in the stream and loses sync, while the
                // real receiver just skips it.
                out.write(buf, 0, 1);
                out.flush();
                try {
                    dest.sendUrgentData(buf[1] & 0xff);
                } catch (Throwable ignored) {
                }
                out.write(buf, 1, len - 1);
                out.flush();
                break;
            }
            case STRATEGY_SPLIT_OOB: {
                int cut = 1 + rnd.nextInt(Math.max(1, Math.min(len - 1, 16)));
                out.write(buf, 0, cut);
                out.flush();
                try {
                    dest.sendUrgentData(0x00);
                } catch (Throwable ignored) {
                }
                sleep(1);
                out.write(buf, cut, len - cut);
                out.flush();
                break;
            }
            case STRATEGY_SPLIT_1:
            default: {
                out.write(buf, 0, 1);
                out.flush();
                sleep(3);
                out.write(buf, 1, len - 1);
                out.flush();
                break;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pipe client <-> target, desyncing the first packet of the client->target direction.
     *
     * Only the first packet is desynced. Everything after the handshake is ordinary
     * stream traffic that a DPI has already stopped caring about, and mangling it would
     * cost throughput for no benefit.
     *
     * TLS records are fragmented without modifying a single byte. Rewriting a real ClientHello
     * here invalidates the client's TLS transcript; rewriting FakeTLS also invalidates its HMAC.
     */
    private static void pipeWithAdvancedDesync(final Socket client, final Socket dest, CallListener call,
                                               long generation, boolean telegram) {
        final int strategy = pickStrategy();
        final boolean mimic = ColgramConfig.isTlsMimicEnabled();
        final java.util.concurrent.atomic.AtomicLong fromTarget =
                new java.util.concurrent.atomic.AtomicLong(0);

        // Client -> Target (desynced first packet)
        workerPool.execute(() -> {
            try {
                InputStream cin = client.getInputStream();
                OutputStream dout = dest.getOutputStream();
                byte[] firstPayload = ColgramInitialPacketReader.readPrefix(client, 517, 60);
                if (ColgramTlsMimic.needsMoreBytes(firstPayload)) {
                    byte[] more = ColgramInitialPacketReader.readPrefix(client, 517, 150);
                    byte[] joined = new byte[firstPayload.length + more.length];
                    System.arraycopy(firstPayload, 0, joined, 0, firstPayload.length);
                    System.arraycopy(more, 0, joined, firstPayload.length, more.length);
                    firstPayload = joined;
                }
                if (call == null && mimic && ColgramTlsMimic.looksLikeClientHello(firstPayload)) {
                    java.util.Random rnd = new java.util.Random();
                    for (byte[] segment : ColgramTlsMimic.fragment(firstPayload, rnd)) {
                        dout.write(segment);
                        dout.flush();
                        if (isCurrentListener(generation)) toTargetBytes.addAndGet(segment.length);
                        sleep(1 + rnd.nextInt(3));
                    }
                } else if (firstPayload.length > 1) {
                    sendDesynced(dest, dout, firstPayload, firstPayload.length, strategy);
                    if (isCurrentListener(generation)) toTargetBytes.addAndGet(firstPayload.length);
                } else if (firstPayload.length == 1) {
                    dout.write(firstPayload, 0, 1);
                    dout.flush();
                    if (isCurrentListener(generation)) toTargetBytes.incrementAndGet();
                }

                byte[] buffer = new byte[16384];
                int len;
                while ((len = cin.read(buffer)) != -1) {
                    dout.write(buffer, 0, len);
                    dout.flush();
                    if (isCurrentListener(generation)) toTargetBytes.addAndGet(len);
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(dest);
            }
        });

        // Target -> Client (direct)
        workerPool.execute(() -> {
            try {
                InputStream din = dest.getInputStream();
                OutputStream cout = client.getOutputStream();
                byte[] buffer = new byte[16384];
                int len = ColgramFirstResponseReader.readFirst(dest, buffer, FIRST_REPLY_TIMEOUT_MS);
                if (len > 0) {
                    if (telegram) publishUpstream(generation, UPSTREAM_REPLY);
                    fromTarget.addAndGet(len);
                    if (isCurrentListener(generation)) fromTargetBytes.addAndGet(len);
                    cout.write(buffer, 0, len);
                    cout.flush();
                    while ((len = din.read(buffer)) != -1) {
                        fromTarget.addAndGet(len);
                        if (isCurrentListener(generation)) fromTargetBytes.addAndGet(len);
                        cout.write(buffer, 0, len);
                        cout.flush();
                    }
                } else if (call == null) {
                    if (telegram) publishUpstream(generation, UPSTREAM_SILENT);
                    Log.w(TAG, "upstream stayed silent (EOF) on " + dest.getRemoteSocketAddress());
                    ColgramDcRemap.reportSilentAddress(
                            dest.getInetAddress().getHostAddress(), dest.getPort());
                }
            } catch (SocketTimeoutException silent) {
                if (telegram) publishUpstream(generation, UPSTREAM_SILENT);
                Log.w(TAG, "upstream stayed silent for " + FIRST_REPLY_TIMEOUT_MS + "ms on "
                        + dest.getRemoteSocketAddress());
                if (call == null) ColgramDcRemap.reportSilentAddress(
                        dest.getInetAddress().getHostAddress(), dest.getPort());
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(dest);
                if (call != null) call.done(client, dest);
                if (isCurrentListener(generation)) completedRelays.incrementAndGet();
                // Decide only after the target direction is done, so a slow reply is not
                // mistaken for a rejection.
                if (isCurrentListener(generation)) reportStrategyResult(strategy, fromTarget.get() > 0);
            }
        });
    }

    /**
     * Carries an associated datagram stream: each SOCKS5 datagram becomes one TCP connection to the
     * destination, and what comes back is handed to the client as a datagram.
     *
     * <p>One stream per datagram is what a TCP-shaped tunnel can offer, and it is what the client sees
     * as MTProto over a connection: Telegram frames each message for a stream anyway, so nothing
     * downstream depends on the datagram being long-lived. What matters is that the association now
     * completes at all -- previously it was refused, and every connection through this front died
     * before a byte of Telegram traffic moved.
     */
    private static void pumpAssociated(final java.net.DatagramSocket udp, final Socket client, long generation) {
        Thread inbound = new Thread(() -> {
            java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[65535], 65535);
            try {
                while (isCurrentListener(generation)) {
                    packet.setLength(65535);
                    udp.receive(packet);
                    int hostAt;
                    int hostLen;
                    int atyp = packet.getData()[3] & 0xff;
                    if (atyp == 1) {
                        hostAt = 4;
                        hostLen = 4;
                    } else if (atyp == 4) {
                        hostAt = 4;
                        hostLen = 16;
                    } else if (atyp == 3) {
                        hostAt = 5;
                        hostLen = packet.getData()[4] & 0xff;
                    } else {
                        continue;
                    }
                    byte[] raw = new byte[hostLen];
                    System.arraycopy(packet.getData(), hostAt, raw, 0, hostLen);
                    String host = atyp == 3
                            ? new String(raw, StandardCharsets.UTF_8)
                            : InetAddress.getByAddress(raw).getHostAddress();
                    int portAt = hostAt + hostLen;
                    int port = ((packet.getData()[portAt] & 0xFF) << 8)
                            | (packet.getData()[portAt + 1] & 0xFF);
                    int payloadAt = portAt + 2;
                    int payloadLen = packet.getLength() - payloadAt;
                    if (payloadLen <= 0) continue;

                    Socket up = connectWithBackoff(host, port, generation);
                    if (up == null) continue;
                    try {
                        up.getOutputStream().write(packet.getData(), payloadAt, payloadLen);
                        up.getOutputStream().flush();
                        byte[] chunk = new byte[65535];
                        int n = up.getInputStream().read(chunk);
                        if (n > 0) {
                            udp.send(new java.net.DatagramPacket(chunk, n,
                                    InetAddress.getByName("127.0.0.1"), client.getLocalPort()));
                        }
                    } finally {
                        closeQuietly(up);
                    }
                }
            } catch (Throwable ignored) {
                // A closed control connection is the normal way this ends.
            } finally {
                udp.close();
            }
        }, "ColgramDpiBypass-associate-in");
        inbound.setDaemon(true);
        inbound.start();

        // Outbound: the client's framed datagrams arrive on the control connection, and this is the
        // loop that turns them into connections.
        try {
            java.io.InputStream in = client.getInputStream();
            while (isCurrentListener(generation)) {
                byte[] head = ColgramSocks5Codec.readFully(in, 4);
                int atyp = head[3] & 0xff;
                int len;
                if (atyp == 1) {
                    len = 4 + 2;
                } else if (atyp == 4) {
                    len = 16 + 2;
                } else if (atyp == 3) {
                    len = 1 + (ColgramSocks5Codec.readByte(in) & 0xff) + 2;
                } else {
                    return;
                }
                byte[] rest = ColgramSocks5Codec.readFully(in, len);
                int hostAt;
                int hostLen;
                if (atyp == 3) {
                    hostAt = 1;
                    hostLen = rest[0] & 0xff;
                } else {
                    hostAt = 0;
                    hostLen = atyp == 1 ? 4 : 16;
                }
                byte[] raw = new byte[hostLen];
                System.arraycopy(rest, hostAt, raw, 0, hostLen);
                String host = atyp == 3
                        ? new String(raw, StandardCharsets.UTF_8)
                        : InetAddress.getByAddress(raw).getHostAddress();
                int portAt = hostAt + hostLen;
                int port = ((rest[portAt] & 0xFF) << 8) | (rest[portAt + 1] & 0xFF);
                int payloadAt = portAt + 2;
                int payloadLen = rest.length - payloadAt;
                if (payloadLen <= 0) continue;

                Socket up = connectWithBackoff(host, port, generation);
                if (up == null) continue;
                final Socket sock = up;
                final byte[] payload = new byte[payloadLen];
                System.arraycopy(rest, payloadAt, payload, 0, payloadLen);
                Thread one = new Thread(() -> {
                    try {
                        sock.getOutputStream().write(payload);
                        sock.getOutputStream().flush();
                        byte[] chunk = new byte[65535];
                        int n = sock.getInputStream().read(chunk);
                        if (n > 0) {
                            udp.send(new java.net.DatagramPacket(chunk, n,
                                    InetAddress.getByName("127.0.0.1"), client.getLocalPort()));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        closeQuietly(sock);
                    }
                }, "ColgramDpiBypass-associate-one");
                one.setDaemon(true);
                one.start();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** The listener half, used when a bind fails and the half-built socket has to go back. */
    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
