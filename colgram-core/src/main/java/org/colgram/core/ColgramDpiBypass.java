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
 * 2. Multi-port DC fallback: connects to 443, 80, 5222, 8443 if the primary port is throttled.
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

    public static synchronized void start() {
        if (isRunning || startScheduled) return;
        startScheduled = true;

        // Defer binding the local proxy socket out of the application-startup
        // window. Telegram's native MTProto stack (libtmessages.so ->
        // tgnet::ConnectionSocket) is still opening and closing its own sockets
        // during the first seconds of the process; adding a ServerSocket plus a
        // cached pool of worker sockets in that same window makes the two stacks
        // race over the process-wide descriptor table. bionic's fdsan then kills
        // the process with SIGABRT ("attempted to close file descriptor N ...
        // owned by SocketImpl") inside libtmessages.49.so.
        deferredStart = new Thread(() -> {
            try {
                Thread.sleep(START_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            startScheduled = false;
            startNow();
        }, "colgram-dpi-deferred");
        deferredStart.setDaemon(true);
        deferredStart.start();
    }

    /**
     * Bind the listener without the internal deferral.
     *
     * The only caller that needs this is {@code ColgramProxyManager}, which already defers
     * its entire activation block by its own delay. Calling {@link #start()} from there
     * stacked the two delays SERIALLY — 10s in the manager plus another 8s here — so the
     * listener did not exist until ~18s after launch. That is the "very slow to connect"
     * complaint, and it also left {@code awaitReady()} only a ~7s margin on a 15s budget,
     * so a slow device could time out and point Telegram at a closed port ("Недоступен").
     *
     * Safe to call directly because the caller has already paid the startup-delay cost and
     * guarantees the native socket churn has settled.
     */
    public static synchronized void startImmediately() {
        if (isRunning || startScheduled) return;
        startNow();
    }

    /** Binds the proxy listener immediately. Prefer start(), which defers safely. */
    private static synchronized void startNow() {
        // A listener that only sets isRunning and then fails to bind is worse than no listener at
        // all: the flag says the bypass is up, every health check passes, and the one port the app
        // routes through answers nothing. Measured on the device, where the port was already held:
        //
        //   01:31:20  E/ColgramDpiBypass: ColgramDpiBypass server error
        //   01:31:20    Caused by: android.system.ErrnoException: bind failed: EADDRINUSE
        //   01:31:41  W/ColgramDpiBypass: DPI listener not ready after 20000ms
        //
        // isRunning was left true by that failure, so startNow() returned early on every later call
        // and the dead listener could never be rebuilt. isRunning now means "bound and accepting",
        // and a bind failure clears it so the next attempt can actually try again.
        if (isRunning && bound) return;

        workerPool.execute(() -> {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                try {
                    socket.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
                } catch (Exception bindFailure) {
                    // The port is held by something this process did not let go of: another listener
                    // of the same port, or a socket still closing from a previous pass. This retries by
                    // binding a fresh port rather than by tearing the old listener down.
                    //
                    // The earlier version called stop() here, and that was the fault this replaced. stop()
                    // clears isRunning and nulls serverSocket, both of which are the state the accept
                    // loop below is about to set for this very socket - so the rebind succeeded and the
                    // bookkeeping then described a listener that no longer existed. Worse, stop() is
                    // synchronized on the same class monitor startNow holds, so the call could not
                    // complete until the worker returned, which is the shape of a self-deadlock. On the
                    // device this produced:
                    //   EADDRINUSE: bind failed: Address already in use
                    //   DPI listener not ready after 20000ms
                    // with the port held and the bypass dead, which is the symptom reported as the
                    // bypass not working.
                    //
                    // What the caller is told is the port it can actually reach, and the health check
                    // reads the same field the accept loop writes.
                    Log.w(TAG, "the bypass port is busy; releasing it and rebinding");
                    closeQuietly(socket);
                    int port = findFreePort();
                    if (port <= 0) {
                        bound = false;
                        isRunning = false;
                        Log.e(TAG, "no free loopback port for the bypass listener");
                        return;
                    }
                    socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress("127.0.0.1", port));
                    activePort = port;
                    Log.i(TAG, "bypass listener moved to 127.0.0.1:" + port + " after EADDRINUSE on " + LOCAL_PORT);
                }
                serverSocket = socket;
                bound = true;
                isRunning = true;
                Log.d(TAG, "ColgramDpiBypass started on 127.0.0.1:" + activePort);

                while (isRunning) {
                    try {
                        // Accept on the local socket, not on the field. Reading the field meant a loop
                        // started before a stop() could be handed the socket a later start installed,
                        // so a stopped bypass went on accepting: stop() cleared the field, the next
                        // start bound again, and this loop - still inside `while (isRunning)` from the
                        // previous life - began serving the new socket on the old thread. That is what
                        // left a listener accepting a second and a half after it was told to stop.
                        Socket clientSocket = socket.accept();
                        workerPool.execute(() -> handleClient(clientSocket));
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "ColgramDpiBypass server error", e);
            } finally {
                // The listener is gone either way: `isRunning` was cleared by stop(), or the
                // accept loop threw out of the try. Clear `bound` so the health check sees a
                // dead port rather than a stale "still accepting" flag.
                bound = false;
            }
        });
    }

    public static synchronized void stop() {
        isRunning = false;
        startScheduled = false;
        activePort = LOCAL_PORT;
        if (deferredStart != null) {
            deferredStart.interrupt();
            deferredStart = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
        // Clear `bound` too, or isBound() keeps reporting a listener that no longer exists
        // and the health check in ColgramProxyManager never notices the port went dead.
        bound = false;
        Log.d(TAG, "ColgramDpiBypass stopped");
    }

    /** The port the listener is actually on, which is LOCAL_PORT unless that was busy. */
    public static int activePort() {
        return activePort;
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
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

    private static void handleClient(Socket client) {
        Socket targetSocket = null;
        try {
            // A connection accepted a moment before stop() is still handed to this worker, and serving
            // it would keep a stopped bypass doing work. The socket is closed rather than left to time
            // out, so what the caller sees after a stop is a refused connection and not a fifteen-second
            // wait.
            if (!isRunning) {
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
            boolean supportsNoAuth = false;
            for (byte method : methods) {
                if (method == 0) {
                    supportsNoAuth = true;
                    break;
                }
            }
            if (!supportsNoAuth) {
                out.write(new byte[]{0x05, (byte) 0xFF});
                out.flush();
                closeQuietly(client);
                return;
            }

            // Reply: NO AUTH REQUIRED
            out.write(new byte[]{0x05, 0x00});
            out.flush();

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
                java.net.DatagramSocket udp = new java.net.DatagramSocket(0,
                        InetAddress.getByName("127.0.0.1"));
                // The zero address means "accept from wherever this control connection came from",
                // which is exactly right: the client is on loopback.
                out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0,
                        (byte) (udp.getLocalPort() >>> 8), (byte) udp.getLocalPort()});
                out.flush();
                pumpAssociated(udp, client);
                return;
            }

            // 3. Connect to destination with multi-port fallback & TCP desync
            //
            // Respect any active backoff first: when a full strategy cycle has failed, we
            // deliberately slow new attempts down rather than amplifying Telegram's retry
            // rate into a connection storm.
            long waitMs = backoffUntil - System.currentTimeMillis();
            if (waitMs > 0) {
                sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
            }
            if (!ColgramDcRemap.shouldSkipDirectAddress(destHost)) {
                targetSocket = connectWithBackoff(destHost, destPort);
            } else {
                Log.i(TAG, "skipping temporarily silent Telegram address " + destHost + ":" + destPort);
            }
            if (targetSocket == null) {
                // The DC address tgnet was told about is often the one this network refuses.
                // Searching for a Telegram address that opens a socket at all is what the
                // address-remap component already does; desync is only worth trying against a
                // server that answers.
                String live = ColgramDcRemap.liveAlternativeFor(destHost, destPort);
                if (live != null && !live.equals(destHost)) {
                    Log.i(TAG, "desync: " + destHost + ":" + destPort + " refused, trying "
                            + live + ":" + destPort);
                    targetSocket = connectWithBackoff(live, destPort);
                }
            }
            if (targetSocket == null && ColgramProxyManager.hasReachableRelays()) {
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
            relayedConnections.incrementAndGet();
            pipeWithAdvancedDesync(client, targetSocket);

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
            closeQuietly(client);
            closeQuietly(targetSocket);
        }
    }

    private static Socket connectWithBackoff(String host, int port) {
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
        Socket socket = establishConnection(host, port);
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

    /**
     * Connects to target host with multi-port fallback.
     *
     * The fallback list exists for MTProto, where Telegram genuinely serves the same
     * account on several ports. It must NOT be applied blindly to every host: an HTTPS
     * request to api.telegram.org should not be sent to ports that do not speak TLS. Telegram
     * DC alternatives race within one shared 1.8-second budget; other hosts use only their
     * requested port.
     *
     * Ports are now used only when the destination is one of Telegram's own MTProto DC
     * hosts. Everything else gets exactly the port that was asked for. The DC alternatives
     * race inside one deadline rather than serially burning four connect timeouts on an IP block.
     */
    private static Socket establishConnection(String host, int port) {
        // The port that was ASKED FOR is opened first and on its own. Racing all four MTProto
        // ports and taking whichever socket connected first is what made the bypass fail against
        // a perfectly reachable Telegram address:
        //
        //   19:30:46  W/ColgramDpiBypass: upstream stayed silent for 3000ms on /111.154.167.41:443
        //
        // while the same address answered on 443, 80 and 5222 from the device shell. 8443 opens
        // its socket instantly and never speaks, so the race handed it back, the first MTProto read
        // timed out, and the whole "обходник не работает" report was the port lottery, not a block.
        // Measured: 443/80/5222 -> HTTP/1.1 404 (live nginx), 8443 -> silent.
        if (port == 443 && isMtProtoHost(host)) {
            Socket preferred = ColgramSocketConnectRace.connectPreferred(
                    host, 443, new int[]{80, 5222}, TELEGRAM_DIAL_BUDGET_MS);
            if (preferred != null) return preferred;
            // 8443 is left out on purpose: it completes a TCP handshake and then never answers.
            return ColgramSocketConnectRace.connect(host, new int[]{8443}, 3000);
        }
        return ColgramSocketConnectRace.connect(host, new int[]{port}, 3000);
    }

    /**
     * Whether a host is one of Telegram's own MTProto endpoints, where the multi-port
     * fallback in {@link #establishConnection} is legitimate.
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
     * When the first packet is a TLS ClientHello and the mimic is on, it is REWRITTEN into a
     * browser fingerprint and sent fragmented — not merely split. This is what makes the
     * listener a general-purpose bypass: Telegram's FakeTLS hello, YouTube, GitHub, Cloudflare
     * and every other HTTPS destination forwarded through it stop matching the ClientHello
     * signatures the TSPU holds for those services, while each real server accepts the rebuilt
     * hello as ordinary TLS.
     */
    private static void pipeWithAdvancedDesync(final Socket client, final Socket dest) {
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
                if (mimic && ColgramTlsMimic.looksLikeClientHello(firstPayload)) {
                    byte[] rewritten = ColgramTlsMimic.rewrite(firstPayload);
                    java.util.Random rnd = new java.util.Random();
                    for (byte[] segment : ColgramTlsMimic.fragment(rewritten, rnd)) {
                        dout.write(segment);
                        dout.flush();
                        toTargetBytes.addAndGet(segment.length);
                        sleep(1 + rnd.nextInt(3));
                    }
                } else if (firstPayload.length > 1) {
                    sendDesynced(dest, dout, firstPayload, firstPayload.length, strategy);
                } else if (firstPayload.length == 1) {
                    dout.write(firstPayload, 0, 1);
                    dout.flush();
                    toTargetBytes.incrementAndGet();
                }

                byte[] buffer = new byte[16384];
                int len;
                while ((len = cin.read(buffer)) != -1) {
                    dout.write(buffer, 0, len);
                    dout.flush();
                    toTargetBytes.addAndGet(len);
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
                    fromTarget.addAndGet(len);
                    fromTargetBytes.addAndGet(len);
                    cout.write(buffer, 0, len);
                    cout.flush();
                    while ((len = din.read(buffer)) != -1) {
                        fromTarget.addAndGet(len);
                        fromTargetBytes.addAndGet(len);
                        cout.write(buffer, 0, len);
                        cout.flush();
                    }
                } else {
                    Log.w(TAG, "upstream stayed silent (EOF) on " + dest.getRemoteSocketAddress());
                    ColgramDcRemap.reportSilentAddress(
                            dest.getInetAddress().getHostAddress(), dest.getPort());
                }
            } catch (SocketTimeoutException silent) {
                Log.w(TAG, "upstream stayed silent for " + FIRST_REPLY_TIMEOUT_MS + "ms on "
                        + dest.getRemoteSocketAddress());
                ColgramDcRemap.reportSilentAddress(
                        dest.getInetAddress().getHostAddress(), dest.getPort());
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(dest);
                completedRelays.incrementAndGet();
                // Decide only after the target direction is done, so a slow reply is not
                // mistaken for a rejection.
                reportStrategyResult(strategy, fromTarget.get() > 0);
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
    private static void pumpAssociated(final java.net.DatagramSocket udp, final Socket client) {
        Thread inbound = new Thread(() -> {
            java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[65535], 65535);
            try {
                while (true) {
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

                    Socket up = connectWithBackoff(host, port);
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
            while (true) {
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

                Socket up = connectWithBackoff(host, port);
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
