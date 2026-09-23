package org.colgram.core;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = false;
    /** True only once the socket is actually bound and accepting. */
    private static volatile boolean bound = false;
    private static volatile boolean startScheduled = false;
    private static Thread deferredStart;
    /** Keep the proxy listener out of the fragile application-startup window. */
    private static final long START_DELAY_MS = 8000L;
    private static final ExecutorService workerPool = Executors.newCachedThreadPool();

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
        if (isRunning) return;
        isRunning = true;

        workerPool.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
                bound = true;
                Log.d(TAG, "ColgramDpiBypass started on 127.0.0.1:" + LOCAL_PORT);

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
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
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 1. SOCKS5 greeting
            int ver = in.read();
            if (ver != 5) {
                closeQuietly(client);
                return;
            }
            int nmethods = in.read();
            if (nmethods <= 0) {
                closeQuietly(client);
                return;
            }
            byte[] methods = new byte[nmethods];
            int read = in.read(methods);
            if (read <= 0) {
                closeQuietly(client);
                return;
            }

            // Reply: NO AUTH REQUIRED
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // 2. SOCKS5 request
            int reqVer = in.read();
            int cmd = in.read();
            int rsv = in.read();
            int atyp = in.read();

            if (reqVer != 5 || cmd != 1) { // CONNECT only
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            String destHost;
            if (atyp == 1) { // IPv4
                byte[] ip = new byte[4];
                in.read(ip);
                destHost = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            } else if (atyp == 3) { // Domain
                int len = in.read();
                byte[] domainBytes = new byte[len];
                in.read(domainBytes);
                destHost = new String(domainBytes, StandardCharsets.UTF_8);
            } else if (atyp == 4) { // IPv6
                byte[] ip6 = new byte[16];
                in.read(ip6);
                destHost = InetAddress.getByAddress(ip6).getHostAddress();
            } else {
                closeQuietly(client);
                return;
            }

            int destPort = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // 3. Connect to destination with multi-port fallback & TCP desync
            //
            // Respect any active backoff first: when a full strategy cycle has failed, we
            // deliberately slow new attempts down rather than amplifying Telegram's retry
            // rate into a connection storm.
            long waitMs = backoffUntil - System.currentTimeMillis();
            if (waitMs > 0) {
                sleep(Math.min(waitMs, MAX_BACKOFF_SLEEP_MS));
            }
            targetSocket = establishConnection(destHost, destPort);
            if (targetSocket == null) {
                // The DC address tgnet was told about is often the one this network refuses.
                // Searching for a Telegram address that opens a socket at all is what the
                // address-remap component already does; desync is only worth trying against a
                // server that answers.
                String live = ColgramDcRemap.liveAddressFor(destHost, destPort);
                if (live != null && !live.equals(destHost)) {
                    Log.i(TAG, "desync: " + destHost + ":" + destPort + " refused, trying "
                            + live + ":" + destPort);
                    targetSocket = establishConnection(live, destPort);
                }
            }
            if (targetSocket == null) {
                Log.w(TAG, "Failed to connect to target: " + destHost + ":" + destPort);
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
            pipeWithAdvancedDesync(client, targetSocket);

        } catch (Exception e) {
            closeQuietly(client);
            closeQuietly(targetSocket);
        }
    }

    /**
     * Connects to target host with multi-port fallback.
     *
     * The fallback list exists for MTProto, where Telegram genuinely serves the same
     * account on several ports. It must NOT be applied blindly to every host: the original
     * code tried {443, 80, 5222, 8443} for any target on 443, so an HTTPS request to
     * api.telegram.org that missed on 443 then burned three more connect attempts against
     * ports that do not speak TLS on that host, and returned null ~9s later. The caller
     * saw only "no response".
     *
     * Ports are now used only when the destination is one of Telegram's own MTProto DC
     * hosts. Everything else gets exactly the port that was asked for.
     */
    private static Socket establishConnection(String host, int port) {
        int[] candidatePorts;
        if (port == 443 && isMtProtoHost(host)) {
            candidatePorts = new int[]{443, 80, 5222, 8443};
        } else {
            candidatePorts = new int[]{port};
        }

        for (int p : candidatePorts) {
            try {
                Socket directSocket = new Socket();
                directSocket.setTcpNoDelay(true);
                directSocket.connect(new InetSocketAddress(host, p), 3000);
                return directSocket;
            } catch (Exception ignored) {}
        }
        return null;
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
                || h.startsWith("91.108.");
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
     */
    private static void pipeWithAdvancedDesync(final Socket client, final Socket dest) {
        final int strategy = pickStrategy();
        final java.util.concurrent.atomic.AtomicLong fromTarget =
                new java.util.concurrent.atomic.AtomicLong(0);

        // Client -> Target (desynced first packet)
        workerPool.execute(() -> {
            try {
                InputStream cin = client.getInputStream();
                OutputStream dout = dest.getOutputStream();
                byte[] buffer = new byte[16384];
                boolean firstPacket = true;
                int len;

                while ((len = cin.read(buffer)) != -1) {
                    if (firstPacket) {
                        firstPacket = false;
                        if (len > 1) {
                            sendDesynced(dest, dout, buffer, len, strategy);
                        } else {
                            dout.write(buffer, 0, len);
                            dout.flush();
                        }
                    } else {
                        dout.write(buffer, 0, len);
                        dout.flush();
                    }
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
                int len;

                while ((len = din.read(buffer)) != -1) {
                    fromTarget.addAndGet(len);
                    cout.write(buffer, 0, len);
                    cout.flush();
                }
            } catch (Throwable ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(dest);
                // Decide only after the target direction is done, so a slow reply is not
                // mistaken for a rejection.
                reportStrategyResult(strategy, fromTarget.get() > 0);
            }
        });
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
