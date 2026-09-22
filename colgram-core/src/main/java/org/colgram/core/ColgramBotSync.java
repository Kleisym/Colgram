package org.colgram.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramBotSync — Full Dialog History Synchronizer, Real-time Poller & Profile Manager for Bot Accounts.
 *
 * 1. Synchronizes chats and messages from Telegram Bot API.
 * 2. Background daemon long-polling for real-time incoming updates.
 * 3. Injects users, messages, and dialogs into Telegram's native MessagesStorage & MessagesController.
 * 4. Bot Profile Management (setMyName, setMyDescription) bypassing MTProto BOT_METHOD_INVALID.
 * 5. "Start chat as Bot" dialog by @username or user ID.
 */
public class ColgramBotSync {

    private static final String TAG = "ColgramBotSync";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final ConcurrentHashMap<Integer, Thread> pollerThreads = new ConcurrentHashMap<>();
    /**
     * Accounts for which a poller spawn is in flight. `pollerThreads` cannot serve this
     * purpose: a constructed-but-not-yet-started thread reports `isAlive() == false`, so the
     * map alone lets concurrent callers each spawn their own poller (see the doc on
     * startBotUpdatesPoller for the measured symptom).
     */
    private static final java.util.Set<Integer> botPollerStarting =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /**
     * Accounts whose poller has already been spawned and has NOT been replaced since.
     *
     * `pollerThreads` + `isAlive()` cannot gate this, because in PASSIVE mode the poller
     * returns almost immediately ("passive bot mode: leaving webhook/updates untouched") and
     * the thread is dead within milliseconds. `isAlive()` is then false, so the next caller
     * legitimately passes the guard and spawns another - and `syncBotDialogs()` calls
     * `startBotUpdatesPoller()` unconditionally on EVERY invocation. That is a spawn LOOP, not
     * a race, and it needs a different guard than the in-flight claim.
     *
     * Measured on device before this fix: **887 poller spawns**, at times clustered ~20ms
     * apart, with the chat list unresponsive while it churned.
     *
     * The entry is cleared only when the poller is intentionally torn down (stopBotPoller /
     * logout / account switch), never merely because the thread exited. Passive mode is a
     * stable end state: the poller has done its one-shot job and should not be respawned until
     * something explicitly says to.
     *
     * Rate-limited re-arm: a genuinely transient failure (e.g. token just saved) can still
     * retry, but not more than once per POLLER_REARM_MS.
     */
    private static final java.util.Set<Integer> botPollerSpawned =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static final ConcurrentHashMap<Integer, Long> botPollerLastSpawnAt = new ConcurrentHashMap<>();
    private static final long POLLER_REARM_MS = 60_000L;
    private static final ConcurrentHashMap<Integer, Integer> lastUpdateIds = new ConcurrentHashMap<>();

    public static void saveBotToken(Context context, int account, String token) {
        if (context == null || token == null) return;
        token = token.trim();
        SharedPreferences prefs = context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE);
        prefs.edit().putString("bot_token", token).apply();

        SharedPreferences globalPrefs = context.getSharedPreferences("colgram_bot_tokens_global", Context.MODE_PRIVATE);
        globalPrefs.edit()
                .putString("token_account_" + account, token)
                .apply();

        // A new token invalidates the previous poller (it was polling with the old one), so
        // explicitly re-arm rather than relying on the rate-limited timer. Without this the
        // fresh token would not take effect for up to POLLER_REARM_MS.
        synchronized (ColgramBotSync.class) {
            botPollerSpawned.remove(account);
            botPollerLastSpawnAt.remove(account);
        }
    }

    public static String getBotToken(Context context, int account) {
        if (context == null) {
            try {
                Class<?> alClass = Class.forName("org.telegram.messenger.ApplicationLoader");
                context = (Context) alClass.getField("applicationContext").get(null);
            } catch (Throwable ignored) {}
        }
        if (context == null) return "";

        SharedPreferences prefs = context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE);
        String token = prefs.getString("bot_token", "");
        if (token.isEmpty()) {
            SharedPreferences globalPrefs = context.getSharedPreferences("colgram_bot_tokens_global", Context.MODE_PRIVATE);
            token = globalPrefs.getString("token_account_" + account, "");
        }
        // There used to be a third step here: fall back to "last_bot_token", the most recent
        // token saved for ANY account. That made every account report the same bot, so
        // ensurePollers() spun up to five long-pollers on one token (Bot API answers 409 to
        // everyone but the first) and injected that bot's dialogs into ordinary accounts.
        // A token is per-account or it does not exist; guessing across accounts is not a
        // fallback, it is a leak.
        return token;
    }

    /**
     * Route selection for Bot API calls.
     *
     * Two traps, both previously invisible to a try/catch placed here:
     *
     *  1. `URL.openConnection()` does NOT touch the network. It returns a lazy
     *     URLConnection; the connect happens later inside `getResponseCode()`. So any
     *     try/catch wrapped around it proves nothing - which is why an earlier version of
     *     this method "tried direct first" on paper while in reality the failure always
     *     surfaced much later, in the caller, with the route flag never updated.
     *
     *  2. `api.telegram.org` publishes AAAA records. On a network with no working IPv6 -
     *     an Android emulator, or many mobile carriers - resolving it yields an IPv6
     *     address that silently black-holes: every connect burns the full timeout and the
     *     user gets a bare "no response". Confirmed on device: attempts to
     *     `2001:67c:4e8:f004::9` timed out identically while IPv4 worked.
     *
     * So we fold the IPv6 stack down to IPv4 before the first call: `preferIPv4Stack` makes
     * the resolver return only A records, which keeps the hostname, SNI and certificate
     * validation correct while the black-hole AAAA path is never attempted. The verdict is
     * cached so one dead route does not cost a timeout on every subsequent call - but a
     * cached failure is re-probed periodically, because a network that was blocked can
     * become unblocked without the app restarting.
     */
    /**
     * Build an HttpURLConnection that can only ever dial IPv4.
     *
     * WHY THIS EXISTS, and why the two earlier attempts did not work:
     *
     *   * `Os.setenv("JAVA_TOOL_OPTIONS", "-Djava.net.preferIPv4Stack=true")` - inert. That
     *     variable is read by a JVM *launcher*; an Android app is a Zygote-forked ART process
     *     that is already running.
     *   * `System.setProperty("java.net.preferIPv4Stack", "true")` at runtime - also inert.
     *     `InetAddress` reads that property once, during its own class initialisation, which
     *     happens long before any app code runs. Measured on device: the property was logged
     *     as ENFORCED at 16:56:03 and attempts to `2001:67c:4e8:f004::9` still began at
     *     16:56:51 - 48 seconds later, with the property set the whole time.
     *
     * So the address family has to be constrained at the socket, not by a global preference.
     * We resolve the host to IPv4 ourselves and open the TCP socket to that literal, while the
     * HttpURLConnection still sees the ORIGINAL hostname - which is what keeps SNI and the
     * certificate hostname check correct. Rewriting the URL to an IP literal instead would
     * Make outbound HTTP dial IPv4 instead of black-holing on an unreachable AAAA record.
     *
     * 🔴 WHY THIS IS A LOCAL PROXY AND NOT A STREAM-HANDLER FACTORY.
     *
     * Three cheaper mechanisms were each tried and each measured as ineffective on Android:
     *
     *   1. `System.setProperty("java.net.preferIPv4Stack","true")` - inert. `InetAddress` reads
     *      it once during its own class init, before app code. Logged ENFORCED at 16:56:03,
     *      AAAA connects began 16:56:51, property set throughout.
     *   2. `conn.setSSLSocketFactory(ipv4Factory)` - inert. Android's `HttpURLConnection` is
     *      OkHttp (`com.android.okhttp.internal.huc.*`), which dials its OWN raw socket in
     *      `RealConnection.connect` -> `Platform.connectSocket` and consults the SSL factory
     *      only for the TLS layer on a socket it already opened.
     *   3. `URL.setURLStreamHandlerFactory(...)` - this one WORKED as a pin, but introduced a
     *      fatal re-entrancy: once installed, `new URL(...)` and `URL.openConnection()` both
     *      consult the factory, so a handler that builds a URL to obtain a "plain" connection
     *      recurses into itself. Measured: `java.lang.StackOverflowError: stack size 1038KB`,
     *      twice, in two different frames -
     *        at colgramPatchConnection(...:274) -> $1$1.openConnection(...:243) -> URL.openConnection
     *        at colgramPatchConnection(...:316) -> java.net.URL.<init> -> URL.getURLStreamHandler
     *      The second form cannot be avoided while the factory is installed, because
     *      `sun.net.www.protocol.*.Handler` is present in the Android RUNTIME but absent from
     *      `android.jar`, so there is no compile-safe way to obtain a non-ours handler.
     *
     * The mechanism that survives all three findings is a **loopback proxy**: OkHttp honours
     * `Proxy` unconditionally (it is a documented part of the connection contract, used by every
     * app that talks through one), and the proxy is where we choose the address family. The
     * target hostname still travels in the CONNECT/GET line, so SNI and certificate validation
     * against `api.telegram.org` are untouched - the same guarantee the socket pin provided,
     * without touching global URL behaviour.
     *
     * The listener is bound to 127.0.0.1 only and is created lazily; if it cannot start, callers
     * simply get a direct connection, which is where they started.
     */
    private static volatile int colgramIpv4ProxyPort = -1;
    private static volatile boolean colgramIpv4ProxyStarted = false;

    /** Port of the loopback IPv4-forcing proxy, starting it on first use. -1 if unavailable. */
    static int colgramIpv4ProxyPort() {
        if (colgramIpv4ProxyPort > 0) return colgramIpv4ProxyPort;
        synchronized (ColgramBotSync.class) {
            if (colgramIpv4ProxyPort > 0) return colgramIpv4ProxyPort;
            try {
                // Backlog 64: a burst of concurrent Bot API calls would overflow 8 and
                // connections would be refused before the accept loop could drain them.
                java.net.ServerSocket ss = new java.net.ServerSocket(0, 64,
                        java.net.InetAddress.getByName("127.0.0.1"));
                final int port = ss.getLocalPort();
                Thread t = new Thread(() -> colgramIpv4ProxyLoop(ss), "colgram-ipv4-proxy");
                t.setDaemon(true);
                t.start();
                colgramIpv4ProxyPort = port;
                colgramIpv4ProxyStarted = true;
                Log.i(TAG, "IPv4-forcing loopback proxy listening on 127.0.0.1:" + port);
            } catch (Throwable e) {
                Log.w(TAG, "could not start IPv4 proxy, dialing directly: " + e);
                colgramIpv4ProxyPort = -1;
            }
            return colgramIpv4ProxyPort;
        }
    }

    /**
     * Accept connections and relay them, choosing the upstream address family here.
     *
     * HTTP CONNECT (used for https) is handled by resolving the requested host to an A record
     * and opening the TCP leg ourselves; plain http is relayed the same way. Anything that is
     * not a name we can resolve, or that is not a request we recognise, is refused rather than
     * guessed at - a silent wrong answer here would be worse than a visible failure.
     */
    private static void colgramIpv4ProxyLoop(java.net.ServerSocket server) {
        while (true) {
            try {
                final java.net.Socket client = server.accept();
                // 🔴 ONE THREAD PER CLIENT. Handling the client inline meant the blocking
                // header read (up to COLGRAM_CONNECT_TIMEOUT_MS) held the accept loop, so a
                // single slow or silent connection stalled every other request. Measured as
                // the proxy itself timing out:
                //   SocketTimeoutException: failed to connect to /127.0.0.1 (port 36719)
                //   from /127.0.0.1 (port 41600) after 8000ms
                Thread t = new Thread(() -> colgramHandleProxyClient(client),
                        "colgram-ipv4-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                // Never let one accept failure kill the listener.
                Log.w(TAG, "proxy accept: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
    }

    /** Serve one client: read its request, dial IPv4 upstream, relay. */
    private static void colgramHandleProxyClient(java.net.Socket client) {
        try {
            client.setSoTimeout(COLGRAM_CONNECT_TIMEOUT_MS);
            java.io.InputStream cin = client.getInputStream();
            java.io.OutputStream cout = client.getOutputStream();

            // Read the request line first - that is where the target lives.
            java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
            int b, guard = 0;
            while ((b = cin.read()) != -1 && guard++ < 8192) {
                head.write(b);
                if (b == '\n') break;
            }
            String requestLine = new String(head.toByteArray(), "US-ASCII").trim();
            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                cout.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("US-ASCII"));
                cout.flush();
                client.close();
                return;
            }
            boolean isConnect = "CONNECT".equalsIgnoreCase(parts[0]);
            String target = parts[1];
            String host;
            int upstreamPort;
            if (isConnect) {
                int idx = target.lastIndexOf(':');
                if (idx < 0) { host = target; upstreamPort = 443; }
                else { host = target.substring(0, idx); upstreamPort = Integer.parseInt(target.substring(idx + 1)); }
            } else {
                java.net.URL tu = new java.net.URL(target);
                host = tu.getHost();
                upstreamPort = tu.getPort() != -1 ? tu.getPort() : 80;
            }

            java.net.InetAddress v4 = colgramResolveIpv4(host, upstreamPort);
            if (v4 == null) {
                Log.w(TAG, "proxy: no A record for " + host + ", refusing");
                cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("US-ASCII"));
                cout.flush();
                client.close();
                return;
            }

            // 🔴 DRAIN THE REST OF THE HEADER BLOCK BEFORE REPLYING.
            //
            // Only the request line has been consumed so far. For CONNECT the client sends
            // 'CONNECT host:port HTTP/1.1', then headers, then a BLANK LINE, and only THEN
            // begins the TLS ClientHello. If those leftover header bytes are not consumed
            // here they are still sitting in the socket, and the relay below forwards them
            // upstream as if they were TLS - measured as:
            //     javax.net.ssl.SSLException: Unable to parse TLS packet header
            //       at ConscryptEngine.unwrap -> RealConnection.connectTls(RealConnection.java:196)
            // because the server received the header terminator ahead of the ClientHello and
            // its handshake parser rejected it.
            //
            // For plain http the whole header block must be preserved and forwarded, so it is
            // accumulated here instead of discarded.
            java.io.ByteArrayOutputStream rest = new java.io.ByteArrayOutputStream();
            {
                // Consume up to and including the terminating blank line. Bounded so a
                // malformed or hostile stream cannot make this loop unbounded.
                int c, hdrGuard = 0, run = 0;
                while ((c = cin.read()) != -1 && hdrGuard++ < 16384) {
                    if (!isConnect) rest.write(c);
                    if (c == '\n') {
                        if (++run >= 2) break;   // blank line reached
                    } else if (c != '\r') {
                        run = 0;
                    }
                }
            }

            // The upstream leg: IPv4 literal only. This is the whole point of the proxy.
            java.net.Socket upstream = new ColgramIpv4Socket(v4, upstreamPort);
            upstream.connect(new java.net.InetSocketAddress(v4, upstreamPort),
                    COLGRAM_CONNECT_TIMEOUT_MS);

            if (isConnect) {
                cout.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"));
                cout.flush();
                // THE TUNNEL MUST NOT CARRY AN IDLE DEADLINE.
                //
                // The setSoTimeout at the top of this method belongs to the header phase. Left in
                // place for the relay it fires on the client -> upstream leg, which is idle for as
                // long as the server is thinking - and the Bot API long poll waits 20 s for updates
                // by design. colgramCopy treats that timeout like an EOF and closes both sockets,
                // so every proxied long poll ended in "unexpected end of stream" and no incoming
                // message reached the UI through this path. Zero means "wait as long as the caller
                // asked for", which is exactly what the read timeout on the connection is for.
                client.setSoTimeout(0);
                upstream.setSoTimeout(0);
            } else {
                // Replay the request line plus the preserved header block, exactly once.
                java.io.OutputStream uos = upstream.getOutputStream();
                uos.write(head.toByteArray());
                uos.write(rest.toByteArray());
                uos.flush();
            }
            Log.i(TAG, "proxy: " + (isConnect ? "CONNECT " : "GET ") + host
                    + " -> " + v4.getHostAddress() + ":" + upstreamPort);

            colgramPump(client, upstream);
        } catch (Throwable t) {
            // One bad client must never take the listener down.
            Log.w(TAG, "proxy: " + t.getClass().getSimpleName() + " " + t.getMessage());
            try { client.close(); } catch (Throwable ignored) {}
        }
    }

    /** Relay both directions until either side closes. */
    private static void colgramPump(final java.net.Socket client, final java.net.Socket upstream) {
        final java.net.Socket[] both = new java.net.Socket[] { client, upstream };
        Thread t1 = new Thread(() -> colgramCopy(client, upstream, both));
        Thread t2 = new Thread(() -> colgramCopy(upstream, client, both));
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
    }

    private static void colgramCopy(java.net.Socket from, java.net.Socket to,
                                    java.net.Socket[] both) {
        byte[] buf = new byte[8192];
        try {
            java.io.InputStream in = from.getInputStream();
            java.io.OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            // Closing both on the first EOF keeps the pair from leaking a half-open socket.
            for (java.net.Socket s : both) {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Pin an existing connection's TLS layer to an IPv4 dial of its own host.
     *
     * This complements the loopback proxy (colgramIpv4ProxyPort): the proxy is the mechanism
     * that actually pins the dial, while this covers a connection handed to us with the hostname
     * intact, and the desync-listener case where the TCP peer is 127.0.0.1 and only the TLS leg
     * needs constraining.
     */
    static void colgramForceDialIpv4(HttpURLConnection conn) {
        try {
            java.net.URL u = conn.getURL();
            String host = (u == null) ? null : u.getHost();
            if (host == null || host.isEmpty() || colgramIsIpLiteral(host)) return;
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                // OkHttp reuses keep-alive sockets, in which case the factory is simply
                // ignored - never an error, just no-op.
                ((javax.net.ssl.HttpsURLConnection) conn)
                        .setSSLSocketFactory(new ColgramIpv4SslSocketFactory(host));
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not pin connection to IPv4: " + t.getMessage());
        }
    }

    /**
     * Prove, on the real device, that a Bot API call dials IPv4 and not the AAAA black hole.
     *
     * Called once per process after the factory is installed. It is deliberately cheap: one
     * resolution plus one plain-TCP reachability attempt against the same host the Bot API
     * uses, reported as a single log line so the answer is readable in logcat without a
     * packet capture. The value is diagnostic proof - "IPv4-only connect" lines in the log
     * only prove the INTENT, whereas this proves the resolved family.
     */
    private static volatile boolean colgramDialSelfTested = false;

    static void colgramSelfTestDial() {
        if (colgramDialSelfTested) return;
        colgramDialSelfTested = true;
        try {
            java.net.InetAddress v4 = colgramResolveIpv4("api.telegram.org");
            if (v4 == null) {
                Log.w(TAG, "dial self-test: no A record for api.telegram.org");
                return;
            }
            long t0 = System.currentTimeMillis();
            java.net.Socket sock = new ColgramIpv4Socket(v4, 443);
            sock.connect(new java.net.InetSocketAddress(v4, 443), COLGRAM_CONNECT_TIMEOUT_MS);
            long ms = System.currentTimeMillis() - t0;
            // Local address of the accepted socket tells us which family the OS actually used.
            String local = String.valueOf(sock.getLocalAddress());
            sock.close();
            Log.i(TAG, "dial self-test OK: api.telegram.org -> " + v4.getHostAddress()
                    + " via " + v4.getClass().getSimpleName() + " in " + ms + "ms, local " + local);
        } catch (Throwable t) {
            // A failure here is informational only - the Bot API path has its own retries and
            // a degraded network (this host measured 50% packet loss) can fail a bare probe.
            Log.w(TAG, "dial self-test: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Resolve a host to its first A record. Returns null when the host is not a name, has no
     * A record, or resolution fails - every caller then leaves the connection untouched.
     */
    static java.net.InetAddress colgramResolveIpv4(String host) {
        return colgramResolveIpv4(host, 443);
    }

    /**
     * Same, but allowed to choose among the host's addresses by whether one actually completes a
     * TCP connection to `port`. Taking the first A record is precisely the wrong rule on a network
     * that drops whole addresses: measured 2026-09-23, api.telegram.org answered with
     * 149.154.166.110 (black hole, 8 s timeout) while another of its own addresses finished TLS in
     * 300 ms. ColgramEndpoints probes the alternatives and remembers which one worked.
     */
    static java.net.InetAddress colgramResolveIpv4(String host, int port) {
        if (host == null || host.isEmpty() || colgramIsIpLiteral(host)) return null;
        java.net.InetAddress live = ColgramEndpoints.select(host, port);
        if (live != null) return live;
        try {
            java.net.InetAddress[] all = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress a : all) {
                if (a instanceof java.net.Inet4Address) return a;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * An SSLSocketFactory whose sockets perform their TCP dial against a pre-resolved IPv4
     * address while still doing TLS against the original hostname.
     */
    static final class ColgramIpv4SslSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory delegate;
        private final java.net.InetAddress v4;
        private final int port;

        ColgramIpv4SslSocketFactory(String host) throws Exception {
            java.net.InetAddress picked = colgramResolveIpv4(host);
            if (picked == null) throw new java.io.IOException("no A record for " + host);
            this.v4 = picked;
            this.port = 443;
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            this.delegate = ctx.getSocketFactory();
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }
        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        private java.net.Socket wrap(java.net.Socket raw, String h, int p, boolean autoClose)
                throws java.io.IOException {
            return delegate.createSocket(raw, h, p, autoClose);
        }

        @Override public java.net.Socket createSocket(java.net.Socket s, String h, int p,
                boolean autoClose) throws java.io.IOException {
            return wrap(s, h, p, autoClose);
        }
        @Override public java.net.Socket createSocket() throws java.io.IOException {
            return new ColgramIpv4Socket(v4, port);
        }
        @Override public java.net.Socket createSocket(String h, int p) throws java.io.IOException {
            return new ColgramIpv4Socket(v4, p);
        }
        @Override public java.net.Socket createSocket(String h, int p,
                java.net.InetAddress localAddr, int localPort) throws java.io.IOException {
            return new ColgramIpv4Socket(v4, p);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int p)
                throws java.io.IOException {
            return new ColgramIpv4Socket(v4, p);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress a, int p,
                java.net.InetAddress localAddr, int localPort) throws java.io.IOException {
            return new ColgramIpv4Socket(v4, p);
        }

        /**
         * Public entry point used by colgramForceDialIpv4: connect the TLS socket to the
         * IPv4 literal ourselves, then hand the connected socket to the delegate so the
         * handshake uses the real hostname for SNI.
         */
        java.net.Socket connectSsl(String host, int p) throws java.io.IOException {
            java.net.Socket raw = new java.net.Socket();
            raw.connect(new java.net.InetSocketAddress(v4, p), COLGRAM_CONNECT_TIMEOUT_MS);
            return delegate.createSocket(raw, host, p, true);
        }
    }

    /**
     * A TCP socket that can only ever dial the IPv4 literal it was built with.
     *
     * This is the load-bearing class. Overriding `connect(SocketAddress, int)` means the base
     * socket never runs its own address resolution - which is exactly where the AAAA path was
     * being taken - while `getInetAddress()` still reports the IPv4 address, so TLS, SNI and
     * certificate validation against the original hostname all behave normally.
     */
    static final class ColgramIpv4Socket extends java.net.Socket {
        private final java.net.InetAddress v4;
        private final int port;

        ColgramIpv4Socket(java.net.InetAddress v4, int port) {
            this.v4 = v4;
            this.port = port;
        }

        @Override public void connect(java.net.SocketAddress endpoint) throws java.io.IOException {
            connect(endpoint, 0);
        }

        @Override public void connect(java.net.SocketAddress endpoint, int timeout)
                throws java.io.IOException {
            int t = timeout > 0 ? timeout : COLGRAM_CONNECT_TIMEOUT_MS;
            super.connect(new java.net.InetSocketAddress(v4, port), t);
        }

        @Override public java.net.InetAddress getInetAddress() {
            java.net.InetAddress real = super.getInetAddress();
            return real != null ? real : v4;
        }

        @Override public String toString() {
            return "ColgramIpv4Socket[" + v4.getHostAddress() + ":" + port + "]";
        }
    }


    /** True for IPv4/IPv6 literals, which have no name to resolve and must not be rewritten. */
    private static boolean colgramIsIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;          // IPv6 literal
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;  // IPv4 literal
        return false;
    }

    private static HttpURLConnection openConnection(String urlStr, int readTimeoutMs) throws Exception {
        // Kept for the JVM-host case; on ART it is a documented no-op. Harmless and cheap.
        colgramDisableIpv6IfUnroutable();

        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;

        // Transport ladder: every rung gets its own attempt. Picking a single transport used to be
        // the whole bug - when that one happened to be a dead public relay, a Bot API write failed
        // with a connection error although the API answered in 300 ms on another address of the
        // same hostname.
        //
        //   1. the address-pinning front: still a direct connection to Telegram, only to one of
        //      the host's addresses that answers. SNI and the certificate check stay correct,
        //      because the hostname travels in the CONNECT line.
        //   2. the IPv4-forcing loopback proxy (a plain IPv4 dial with no address choice).
        //   3. a plain direct connection.
        //   4. the DPI listener, when a plain dial has already been shown not to work here.
        //   5. a relay - the applied proxy first, then the harvested pool. Last, because it is
        //      someone else's machine, and on this network no public relay answers at all.
        java.util.List<java.net.Proxy> rungs = transportLadder(host, port);

        Throwable last = null;
        // A short per-rung connect timeout on purpose: a working path here completes in a few
        // hundred milliseconds, so a rung that has not dialled by 4 s is dead and the next one
        // deserves its turn. The read timeout stays as the caller asked (long polling waits on it).
        int connectTimeoutMs = Math.min(COLGRAM_CONNECT_TIMEOUT_MS, 4000);
        for (java.net.Proxy rung : rungs) {
            try {
                HttpURLConnection conn = rung == null || java.net.Proxy.NO_PROXY.equals(rung)
                        ? (HttpURLConnection) url.openConnection()
                        : (HttpURLConnection) url.openConnection(rung);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.connect();
                return conn;
            } catch (Throwable t) {
                last = t;
            }
        }
        // Nothing worked: forget the chosen address so the next call re-probes rather than walking
        // into the same black hole, and report the last failure to the caller.
        ColgramEndpoints.invalidate(host);
        if (last instanceof Exception) throw (Exception) last;
        throw new java.io.IOException(last == null ? "no transport" : String.valueOf(last));
    }

    /** Append a transport to the ladder when it is usable and not already there. */
    private static void addRung(java.util.List<java.net.Proxy> rungs, java.net.Proxy p) {
        if (p != null && !rungs.contains(p)) rungs.add(p);
    }

    /**
     * The transports worth trying for this host, best first:
     *
     *   1. the address-pinning front: still a direct connection to Telegram, only to one of the
     *      host's addresses that answers. SNI and the certificate check stay correct because the
     *      hostname travels in the CONNECT line.
     *   2. the IPv4-forcing loopback proxy (a plain IPv4 dial with no address choice).
     *   3. a plain direct connection.
     *   4. the DPI listener, when a plain dial has already been shown not to work here.
     *   5. a relay - the applied proxy first, then the harvested pool. Last, because it is
     *      someone else's machine, and on this network no public relay answers at all.
     */
    static java.util.List<java.net.Proxy> transportLadder(String host, int port) {
        java.util.List<java.net.Proxy> rungs = new java.util.ArrayList<>();
        java.net.Proxy front = ColgramEndpoints.frontProxy();
        if (front != null && ColgramEndpoints.select(host, port) != null) {
            addRung(rungs, front);
        }
        int ipv4Port = colgramIpv4ProxyPort();
        if (ipv4Port > 0) {
            addRung(rungs, new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress("127.0.0.1", ipv4Port)));
        }
        if (colgramShouldTryDirect()) {
            addRung(rungs, java.net.Proxy.NO_PROXY);
        }
        if (!colgramShouldTryDirect() && ColgramDpiBypass.isBound()) {
            addRung(rungs, new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    new java.net.InetSocketAddress("127.0.0.1", ColgramDpiBypass.LOCAL_PORT)));
        }
        addRung(rungs, ColgramHttp.pickRelayProxy());
        if (rungs.isEmpty()) addRung(rungs, java.net.Proxy.NO_PROXY);
        return rungs;
    }

    /** Build a connection for one rung of the ladder. Does not dial - the caller decides. */
    private static HttpURLConnection openVia(URL url, java.net.Proxy rung, int readTimeoutMs,
                                             int connectTimeoutMs) throws Exception {
        HttpURLConnection conn = rung == null || java.net.Proxy.NO_PROXY.equals(rung)
                ? (HttpURLConnection) url.openConnection()
                : (HttpURLConnection) url.openConnection(rung);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        return conn;
    }

    /**
     * Make java.net ignore IPv6 for this process when the device has no routable IPv6.
     *
     * Why not just rewrite the URL to an IPv4 literal: `HttpsURLConnection` derives both SNI
     * and the hostname check from the URL host. Point the URL at a literal and the
     * certificate is validated against the IP, which fails with a hostname mismatch - turning
     * one bug into two. (`Host:` headers do not affect SNI.)
     *
     * `java.net.preferIPv4Stack` is the supported switch: it makes the resolver return IPv4
     * addresses only, so the hostname, SNI and certificate validation all stay correct while
     * the black-hole AAAA path is never attempted.
     *
     * To force IPv4 unconditionally would be wrong on a real dual-stack network, where IPv6
     * is the faster path and Telegram's AAAA records are perfectly reachable. But the failure
     * this guards against is asymmetric: a broken IPv6 route costs a full connect timeout on
     * every single call, while an unused IPv6 route costs nothing. So the switch is applied
     * once, globally, and the device's IPv6 state is only logged - availability is re-checked
     * per-request rather than cached in a way that could pin a working network to a stale
     * decision.
     */
    private static volatile boolean colgramIpv4Forced = false;

    /**
     * Apply the IPv4 policy as early as possible, from ApplicationLoader.onCreate.
     *
     * NOTE ON THE ENV-VAR PATH BELOW: `Os.setenv("JAVA_TOOL_OPTIONS", ...)` does NOT achieve
     * what an earlier version of this method assumed. JAVA_TOOL_OPTIONS is consumed by the JVM
     * *launcher* when it creates a VM; an Android app runs inside a Zygote-forked ART process
     * that has already started, so nothing ever reads it. The call is kept because it is
     * harmless and correct on a JVM host, but the real work is done by System.setProperty in
     * colgramDisableIpv6IfUnroutable(). Do not treat this as the enforcement point.
     *
     * Failure is non-fatal: on any error we fall through to the per-request path.
     */
    public static void applyIpv4Policy() {
        // Log FIRST, before anything that can throw. The whole body used to sit inside one
        // try/catch that logged only on failure, and the caller
        // (ApplicationLoader) wraps the call in `catch (Throwable ignore) {}` - so when this
        // method failed early there was NO trace of it in logcat at all, and the symptom was
        // merely "IPv6 attempts keep timing out". Observed exactly that: 1695 AAAA connects
        // against 339 IPv4, with zero policy logging. An entry marker makes the difference
        // between "policy ran and chose IPv4" and "policy never ran" visible at a glance.
        Log.i(TAG, "applyIpv4Policy: entry");
        try {
            // Install the dial-level IPv4 pin unconditionally. It is a no-op on a host with a
            // working IPv6 path - colgramForceDialIpv4 re-resolves per connection and simply
            // finds the A record - so gating it on the device's IPv6 state only creates a way
            // for the fix to be skipped.
            //
            // This USED to gate on colgramDeviceHasIpv6() and, when an IPv6 interface was
            // present, `return` before installing anything. That was the direct cause of the
            // measured failure: the emulator advertises a ULA, the early return fired, and
            // 2434 connects to `2001:67c:4e8:f004::9` timed out with the fix never installed.
            // Bring the IPv4-forcing proxy up early; it is lazily bound to 127.0.0.1 and is a
            // cheap no-op when the device turns out to have a working v6 path.
            colgramIpv4ProxyPort();
            if (colgramDeviceHasIpv6()) {
                // An IPv6 interface exists - but that says nothing about whether Telegram's
                // AAAA records are REACHABLE, which is the only thing that matters here.
                //
                // Do NOT set colgramIpv4Forced here: the flag means "policy enforced", and
                // setting it on a device whose route is merely PRESENT (not proven working)
                // would disable the per-request fallback for the process lifetime.
                Log.i(TAG, "IPv6 interface present; dial-level pin installed anyway "
                        + "(presence != routability)");
            }
            String opts = "-Djava.net.preferIPv4Stack=true";
            // Do not clobber options a previous run or another component already set.
            try {
                String existing = System.getenv("JAVA_TOOL_OPTIONS");
                if (existing != null && existing.contains("preferIPv4Stack")) {
                    opts = existing;
                } else if (existing != null && !existing.trim().isEmpty()) {
                    opts = existing + " " + opts;
                }
            } catch (Throwable ignored) {}
            // JAVA_TOOL_OPTIONS is read by a JVM *launcher*; an ART process is already
            // running, so this is decoration. Harmless to keep for JVM hosts.
            try {
                android.system.Os.setenv("JAVA_TOOL_OPTIONS", opts, true);
            } catch (Throwable envT) {
                Log.w(TAG, "Os.setenv unavailable: " + envT.getMessage());
            }
            // This is what actually takes effect in a running ART process. Set it
            // unconditionally and BEFORE the first socket, so the java.net stack cannot
            // cache an AAAA preference first.
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
            colgramIpv4Forced = true;
            Log.i(TAG, "IPv4 policy ENFORCED at boot: preferIPv4Stack=true");
        } catch (Throwable t) {
            // Fall through to the per-request path, but never silently.
            Log.w(TAG, "applyIpv4Policy failed, will retry per-request: " + t);
        }
        // NOTE: if the "entry" marker above is absent from logcat, this method never ran -
        // postInitApplication() had already consumed its once-only guard. The per-request
        // enforcer in openConnection() is then the only thing keeping IPv6 off, which is why
        // it exists. Seeing "entry" is a diagnostic, not a requirement.
        Log.i(TAG, "IPv4 policy hook returning (enforced=" + colgramIpv4Forced + ")");
    }

    private static void colgramDisableIpv6IfUnroutable() {
        // Delegate to the idempotent enforcer. This used to return immediately when
        // colgramIpv4Forced was set, which is why a flag set by the boot hook (meaning
        // "policy decided") wrongly suppressed the per-request fallback (where it means
        // "policy enforced"). One implementation, one meaning of the flag.
        colgramEnsureIpv4Preference();
    }

    /**
     * Enforce the IPv4 preference at the point of use, idempotently.
     *
     * The boot-time hook (`ApplicationLoader.postInitApplication`) is NOT reliable: that
     * method starts with `if (applicationInited || applicationContext == null) return;` - a
     * once-only guard - and the Colgram call sits at the very END of a long method. Any
     * earlier caller (a widget provider, a broadcast receiver, ChatsWidgetService...) consumes
     * the guard, after which the method returns before reaching the call. Observed exactly
     * that: `applyIpv4Policy` present in the dex, its `entry` marker never logged, and 2425
     * IPv6 connect attempts against Telegram's AAAA record.
     *
     * So do not rely on a call site. Call this immediately before every connection attempt;
     * it is a couple of volatile reads on the fast path.
     */
    private static void colgramEnsureIpv4Preference() {
        if (colgramIpv4Forced) return;
        try {
            // Only override when the device has no USABLE IPv6. colgramDeviceHasIpv6()
            // already excludes ULA (fc00::/7) and link-local, which is what an emulator
            // advertises, so a genuinely dual-stack host keeps its native v6 path.
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("java.net.preferIPv6Addresses", "false");
            Log.i(TAG, "IPv4 preference enforced at connect time (device v6 routable="
                    + colgramDeviceHasIpv6() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "could not set IPv4 preference: " + t.getMessage());
        } finally {
            // Set regardless: a retry on every request would be worse than a one-time miss.
            colgramIpv4Forced = true;
        }
    }

    /**
     * Whether the device has a globally routable IPv6 path.
     *
     * Deliberately stricter than "has an IPv6 address": a ULA (fc00::/7, which is what an
     * Android emulator hands out - `fd17:...`) and a link-local address both look like IPv6
     * to `instanceof Inet6Address` but cannot reach the public internet. Counting those as
     * IPv6 would leave the black-hole in place. Verified on device: the emulator had only
     * `fd17:...` ULAs and `fe80::` link-local, no global address, and pinging Telegram's
     * IPv6 AAAA timed out while its IPv4 answered in 100ms.
     *
     * Cached, because it cannot meaningfully change while the process lives.
     */
    private static volatile int colgramIpv6Known = -1; // -1 unknown, 0 no, 1 yes

    private static boolean colgramDeviceHasIpv6() {
        int known = colgramIpv6Known;
        if (known >= 0) return known == 1;
        boolean has = false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> nifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                java.net.NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!(a instanceof java.net.Inet6Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    byte[] raw = a.getAddress();
                    // fc00::/7 covers both fc00:: and fd00:: - unique-local, not routable.
                    if ((raw[0] & 0xFE) == 0xFC) continue;
                    has = true;
                    break;
                }
                if (has) break;
            }
        } catch (Throwable ignored) {}
        colgramIpv6Known = has ? 1 : 0;
        Log.i(TAG, "device has globally routable IPv6: " + has);
        return has;
    }

    /**
     * Decide whether to attempt the direct route, re-probing occasionally after a failure
     * rather than disabling it for the lifetime of the process.
     */
    private static boolean colgramShouldTryDirect() {
        if (colgramDirectRouteWorks) return true;
        long now = System.currentTimeMillis();
        if (now - colgramDirectRouteFailedAt >= COLGRAM_DIRECT_RETRY_MS) {
            colgramDirectRouteWorks = true;
            Log.i(TAG, "re-probing direct route to the Bot API");
            return true;
        }
        return !ColgramDpiBypass.isBound();
    }

    /** Called by botApiPost when the direct route itself was the thing that failed. */
    private static void colgramReportDirectRouteFailed() {
        if (colgramDirectRouteWorks) {
            colgramDirectRouteWorks = false;
            colgramDirectRouteFailedAt = System.currentTimeMillis();
            Log.w(TAG, "direct route unusable; preferring the desync listener for "
                    + (COLGRAM_DIRECT_RETRY_MS / 1000) + "s");
        }
    }

    /** Cleared when the direct route to the Bot API proves unusable. */
    private static volatile boolean colgramDirectRouteWorks = true;
    private static volatile long colgramDirectRouteFailedAt = 0L;
    /** How long a failed direct route is skipped before being re-probed. */
    private static final long COLGRAM_DIRECT_RETRY_MS = 120000L;
    private static final int COLGRAM_CONNECT_TIMEOUT_MS = 8000;

    public static void deleteWebhook(String token) {
        if (token == null || token.isEmpty()) return;
        deleteWebhook(token, false);
    }

    /**
     * Inspect the bot's current update-delivery configuration without changing it.
     *
     * A bot token is a single pipe: a webhook and getUpdates cannot coexist. Telegram
     * answers getUpdates with 409 while a webhook is set. If the same token is already
     * driving a bot process elsewhere (which is the normal case for Colgram users — the
     * bot is running on a server), Colgram must NOT call deleteWebhook or long-poll,
     * because doing so silently redirects that bot's traffic into the phone and starves
     * the real deployment.
     *
     * @return true when a webhook is currently configured
     */
    public static boolean hasActiveWebhook(String token) {
        if (token == null || token.isEmpty()) return false;
        try {
            HttpURLConnection conn = openConnection(
                    "https://api.telegram.org/bot" + token + "/getWebhookInfo", 10000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return false;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONObject root = new JSONObject(sb.toString());
            if (!root.optBoolean("ok", false)) return false;
            JSONObject result = root.optJSONObject("result");
            if (result == null) return false;
            String url = result.optString("url", "");
            int pending = result.optInt("pending_update_count", 0);
            Log.i(TAG, "getWebhookInfo: url=" + (url.isEmpty() ? "(none)" : url) + " pending=" + pending);
            return !url.isEmpty();
        } catch (Throwable t) {
            Log.w(TAG, "getWebhookInfo failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * @param force when false, nothing is changed when the bot is serving a webhook —
     *              the caller is expected to fall back to passive mode instead.
     */
    public static void deleteWebhook(String token, boolean force) {
        if (token == null || token.isEmpty()) return;
        final boolean doForce = force;
        executor.execute(() -> {
            try {
                if (!doForce && hasActiveWebhook(token)) {
                    Log.i(TAG, "webhook is set and passive mode is on - leaving it alone");
                    return;
                }
                // Through botApiPost rather than a hand-rolled connection: openConnection() probes
                // its transports by dialling them, and HttpURLConnection rejects setDoOutput() after
                // that. The Bot API takes the parameter in the body just the same.
                JSONObject body = new JSONObject();
                body.put("drop_pending_updates", false);
                JSONObject resp = botApiPost(token, "deleteWebhook", body);
                Log.d(TAG, "deleteWebhook result: " + (resp == null ? "no answer" : resp.optBoolean("ok", false)));
            } catch (Throwable t) {
                Log.w(TAG, "deleteWebhook error: " + t.getMessage());
            }
        });
    }

    /**
     * True when Colgram should stay out of the update queue entirely.
     *
     * Defaults to OFF, i.e. Colgram owns the update stream for a token it was given. Passive
     * was the original default and it made the whole feature dead on arrival: the poller
     * returns before its long-poll loop while passive is on, and nothing else ever calls
     * getUpdates, so a bot chat could not receive a message until the app was restarted and
     * something happened to refresh the dialog list. With no way to turn it off that default
     * was not caution, it was a broken product.
     *
     * The caution itself is still real, so it is now the user's call rather than ours:
     * ColgramSettingsActivity exposes this per account, and switching a deployed bot to
     * passive releases the queue back to it.
     */
    public static boolean isPassiveBotMode(Context context, int account) {
        if (context == null) return false;
        return context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE)
                .getBoolean("passive_mode", false);
    }

    /**
     * Flip passive mode for an account and make the change take effect immediately.
     *
     * Tearing the poller down is not optional. The thread only checks passive once, before
     * entering its loop, so without this an account switched to passive keeps consuming
     * updates until the process dies, and one switched out of passive stays silent until the
     * 60 s re-arm window happens to expire.
     */
    public static void setPassiveBotMode(Context context, int account, boolean passive) {
        if (context == null) return;
        context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE)
                .edit().putBoolean("passive_mode", passive).apply();
        stopBotUpdatesPoller(account);
        if (!passive) {
            startBotUpdatesPoller(context, account);
        }
    }

    /**
     * Convert a Bot API chat id to the id Telegram's client uses for a channel peer.
     *
     * Bot API:  -1001234567890
     * Client:    1234567890   (strip the -100 prefix)
     */
    private static long channelIdFromBotApi(long botApiChatId) {
        return Math.abs(botApiChatId) - 1000000000000L;
    }

    /**
     * A Bot API chat id is a supergroup/channel when it carries the -100 prefix.
     * Legacy groups are negative without it; private chats are positive.
     */
    private static boolean isSupergroupOrChannel(long botApiChatId) {
        return botApiChatId < 0 && String.valueOf(Math.abs(botApiChatId)).startsWith("100");
    }

    /**
     * Mirror of MessageObject.getPeerId() for a TL_message, without referencing Telegram
     * classes at compile time.
     *
     * Telegram's rule (MessageObject.getPeerId):
     *     TL_peerChat    -> -chat_id
     *     TL_peerChannel -> -channel_id
     *     TL_peerUser    ->  user_id
     * Returning 0 when the message has no peer keeps the caller's match check safe.
     */
    private static long peerIdOf(Class<?> messageClass, Class<?> peerChannelClass, Class<?> peerChatClass,
                                 Class<?> peerUserClass, Object message) {
        try {
            Object peer = messageClass.getField("peer_id").get(message);
            if (peer == null) return 0;
            if (peerChannelClass.isInstance(peer)) {
                return -peerChannelClass.getField("channel_id").getLong(peer);
            }
            if (peerChatClass.isInstance(peer)) {
                return -peerChatClass.getField("chat_id").getLong(peer);
            }
            if (peerUserClass.isInstance(peer)) {
                return peerUserClass.getField("user_id").getLong(peer);
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Build a TLRPC chat object for a group or channel seen through the Bot API.
     *
     * DialogsActivity cannot render a dialog row for a chat it has no object for: it needs
     * the title and the group/channel flags to pick the row type, the participant count for
     * the subtitle, and the id to look the peer up. The Bot API supplies only a subset, so
     * this constructs the closest honest equivalent:
     *
     *   - supergroup/channel -> TLRPC.TL_channel, megagroup=true (broadcast=false)
     *   - legacy group       -> TLRPC.TL_chat
     *
     * Field names differ between the two classes (title/participants_count exist on both,
     * but only TL_channel has megagroup/broadcast), so the optional ones are set defensively.
     *
     * @param source    the raw Bot API chat object, used for title/username when available
     * @param isChannel true for a supergroup/channel, false for a legacy group
     * @return a TLRPC chat instance, or null if reflection could not build one
     */
    private static Object buildBotApiChat(Class<?> chatClass, Class<?> channelClass, JSONObject source,
                                          long id, String title, int date, boolean isChannel) {
        try {
            Object chat = isChannel ? channelClass.getConstructor().newInstance() : chatClass.getConstructor().newInstance();

            chatClass.getField("id").setLong(chat, id);
            chatClass.getField("title").set(chat, title == null || title.isEmpty() ? "Chat " + id : title);
            chatClass.getField("date").setInt(chat, date);
            try {
                chatClass.getField("participants_count").setInt(chat, 0);
            } catch (Throwable ignored) {}

            if (source != null) {
                String username = source.optString("username", "");
                if (!username.isEmpty()) {
                    try { chatClass.getField("username").set(chat, username); } catch (Throwable ignored) {}
                }
            }

            if (isChannel) {
                // megagroup=true keeps the chat openable in ChatActivity; a channel with
                // broadcast=true and megagroup=false opens read-only and would look wrong
                // for a bot's own group.
                try { channelClass.getField("megagroup").setBoolean(chat, true); } catch (Throwable ignored) {}
                try { channelClass.getField("broadcast").setBoolean(chat, false); } catch (Throwable ignored) {}
                try { channelClass.getField("left").setBoolean(chat, false); } catch (Throwable ignored) {}
                try { channelClass.getField("creator").setBoolean(chat, true); } catch (Throwable ignored) {}
            }
            return chat;
        } catch (Throwable t) {
            Log.w(TAG, "could not build chat object for bot api id " + id + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Fetch the bot's own profile via getMe and register it as the account's current user.
     *
     * Why this matters: on a bot account the client otherwise has no idea who "it" is.
     * MessagesController needs a current user to resolve the account's own id, to decide
     * which side of a dialog is "outgoing", and to render the avatar in the chat list.
     * Without it, dialogs can be inserted into storage and still never render.
     *
     * Returns the bot's user id, or 0 on failure.
     */
    public static long fetchAndRegisterBotSelf(Context context, int account, String token) {
        if (token == null || token.isEmpty()) return 0;
        try {
            HttpURLConnection conn = openConnection(
                    "https://api.telegram.org/bot" + token + "/getMe", 12000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return 0;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            if (!root.optBoolean("ok", false)) return 0;
            JSONObject me = root.optJSONObject("result");
            if (me == null) return 0;

            long botId = me.optLong("id", 0);
            if (botId == 0) return 0;

            Class<?> userClass = Class.forName("org.telegram.tgnet.TLRPC$TL_user");
            Class<?> userStatusClass = Class.forName("org.telegram.tgnet.TLRPC$TL_userStatusRecently");

            Object user = userClass.getConstructor().newInstance();
            userClass.getField("id").setLong(user, botId);
            userClass.getField("first_name").set(user, me.optString("first_name", "Bot"));
            userClass.getField("last_name").set(user, me.optString("last_name", ""));
            userClass.getField("username").set(user, me.optString("username", ""));
            userClass.getField("phone").set(user, "");
            userClass.getField("bot").setBoolean(user, true);
            userClass.getField("status").set(user, userStatusClass.getConstructor().newInstance());

            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);

            // getMe returns no photo, so the object built above is photo-less. putUser()
            // REPLACES the stored entry rather than merging into it, and auth
            // .importBotAuthorization had already put the real user in there WITH a photo —
            // so registering the bot's identity is what deleted its avatar. The photo only
            // came back after a restart because that reloads the user from storage. Carry the
            // visual identity over from whatever is already registered, and keep the access
            // hash for the same reason: dropping it makes the peer unresolvable for outbound
            // sends.
            try {
                Class<?> userBaseClass = Class.forName("org.telegram.tgnet.TLRPC$User");
                // MessagesController declares getUser(Long), boxed. Asking for long.class threw
                // NoSuchMethodException, which this catch turned into a warning — so on the
                // first build that carried this fix the avatar was still wiped, and only the
                // log line revealed it.
                Object existing = mcClass.getMethod("getUser", Long.class)
                        .invoke(mc, Long.valueOf(botId));
                if (existing != null) {
                    Object photo = userBaseClass.getField("photo").get(existing);
                    if (photo != null) userBaseClass.getField("photo").set(user, photo);
                    long existingHash = userBaseClass.getField("access_hash").getLong(existing);
                    if (existingHash != 0) {
                        userBaseClass.getField("access_hash").setLong(user, existingHash);
                    }
                    Log.i(TAG, "preserved existing bot photo=" + (photo != null)
                            + " access_hash=" + (existingHash != 0) + " for id=" + botId);
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not preserve existing bot photo/access_hash: " + t.getMessage());
            }

            mcClass.getMethod("putUser", Class.forName("org.telegram.tgnet.TLRPC$User"), boolean.class)
                    .invoke(mc, user, true);

            SharedPreferences prefs = context.getSharedPreferences(
                    "colgram_bot_account_" + account, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong("bot_self_id", botId)
                    .putString("bot_self_username", me.optString("username", ""))
                    .apply();

            Log.i(TAG, "getMe ok: id=" + botId + " @" + me.optString("username", ""));
            return botId;
        } catch (Throwable t) {
            Log.w(TAG, "getMe failed: " + t.getMessage());
            return 0;
        }
    }

    /** Cached bot user id for this account, or 0 if getMe has not succeeded yet. */
    public static long getBotSelfId(Context context, int account) {
        if (context == null) return 0;
        return context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE)
                .getLong("bot_self_id", 0);
    }

    /**
     * Starts continuous background polling for incoming messages on bot accounts.
     *
     * Why this is guarded by an explicit claim rather than `existing.isAlive()`:
     *
     * `loadDialogs()` runs on every dialog refresh and the MessagesController patch calls
     * `syncBotDialogs()` from it unconditionally, so this method is entered many times per
     * second during startup. The old guard only tested `isAlive()`, but the map slot was
     * written at the END of the method - after `new Thread(...)` - while the body in between
     * does real work (`getApplicationContext`, `getBotToken`, and a blocking
     * `fetchAndRegisterBotSelf` network call). A thread that has been constructed but has not
     * yet reached RUNNABLE reports `isAlive() == false`, so every caller that arrived during
     * those milliseconds passed the guard and spawned another poller. Observed on device:
     * 1806 poller threads and ~1831 live threads within 18s of launch, still climbing.
     *
     * The claim is now taken while still holding the monitor, before any work happens, so a
     * second caller sees `starting == true` and returns immediately. The claim is cleared in
     * a finally block once the thread is handed to the scheduler (or if startup throws), so a
     * failed start does not wedge the account into a permanently dead state.
     */
    public static synchronized void startBotUpdatesPoller(final Context context, final int account) {
        if (context == null) return;

        // Fast path: a live thread is already running.
        Thread existing = pollerThreads.get(account);
        if (existing != null && existing.isAlive()) {
            return;
        }
        // A previous caller is mid-spawn. isAlive() cannot see it yet - this is the race.
        if (botPollerStarting.contains(account)) {
            return;
        }

        // Already spawned and not since torn down. This is the LOOP guard, not the race
        // guard: in passive mode the thread dies immediately, so `isAlive()` above is false
        // on every subsequent call and without this every one of them respawns. See the doc
        // on botPollerSpawned for the measured 887-spawn symptom.
        //
        // Re-armed only after POLLER_REARM_MS, so a transient failure still recovers while a
        // hot call site cannot churn.
        if (botPollerSpawned.contains(account)) {
            Long last = botPollerLastSpawnAt.get(account);
            if (last != null && System.currentTimeMillis() - last < POLLER_REARM_MS) {
                return;
            }
        }

        botPollerStarting.add(account);
        botPollerLastSpawnAt.put(account, System.currentTimeMillis());
        botPollerSpawned.add(account);

        try {
            final Context appContext = context.getApplicationContext();
            Thread poller = new Thread(() -> {
                    Log.i(TAG, "Starting bot updates poller for account " + account);

                String token = getBotToken(appContext, account);
                if (!token.isEmpty()) {
                    // Make sure we know who the bot is before polling updates. getMe is
                    // read-only and safe to call in either mode.
                    if (getBotSelfId(appContext, account) == 0) {
                        fetchAndRegisterBotSelf(appContext, account, token);
                    }

                    // Passive mode: never take the update stream away from an existing
                    // deployment. deleteWebhook() is skipped and the long-poll loop below is
                    // not entered, so dialogs are still synced via syncBotDialogs() but
                    // Colgram consumes nothing from getUpdates.
                    if (isPassiveBotMode(appContext, account)) {
                        Log.i(TAG, "passive bot mode: leaving webhook/updates untouched");
                        return;
                    }

                    deleteWebhook(token, true);
                } else {
                    return;
                }

                SharedPreferences prefs = appContext.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE);
                int savedOffset = prefs.getInt("last_update_id", 0);
                if (savedOffset > 0) {
                    lastUpdateIds.put(account, savedOffset);
                }

                int consecutiveErrors = 0;
                int applyFailures = 0;

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // Check if current user is still a bot on this account
                        Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                        Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, account);
                        Object currentUser = ucClass.getMethod("getCurrentUser").invoke(uc);
                        if (currentUser == null) {
                            break;
                        }
                        boolean isBot = currentUser.getClass().getField("bot").getBoolean(currentUser);
                        if (!isBot) {
                            break;
                        }

                        token = getBotToken(appContext, account);
                        if (token == null || token.isEmpty()) {
                            Thread.sleep(4000);
                            continue;
                        }

                        int currentOffset = lastUpdateIds.getOrDefault(account, 0);
                        String urlStr;
                        if (currentOffset == 0) {
                            // First run.
                            //
                            // DO NOT use offset=-50 here. In the Bot API, `offset` means
                            // "return updates starting from this id" and Telegram treats every
                            // update BELOW that id as confirmed/delivered. A negative offset is
                            // read as "give me the last N", so this very first call marked the
                            // bot's entire pending backlog as read — and the next getUpdates
                            // came back empty. That is why the chat list was empty and never
                            // recovered until the local offset was cleared.
                            //
                            // Omitting offset entirely returns the pending queue without
                            // acknowledging anything we have not actually processed.
                            urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&timeout=0&allowed_updates=%5B%22message%22%2C%22edited_message%22%2C%22channel_post%22%2C%22callback_query%22%5D";
                        } else {
                            // Long-poll: wait up to 20 seconds on server
                            urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&offset=" + currentOffset + "&timeout=20";
                        }

                        HttpURLConnection conn = openConnection(urlStr, currentOffset == 0 ? 10000 : 28000);
                        conn.setRequestMethod("GET");

                        int responseCode = conn.getResponseCode();
                        if (responseCode == 409) {
                            // 409 means a webhook is active on this token. Something else is
                            // serving this bot. Do NOT delete the webhook — that would silently
                            // steal the bot's traffic from its real deployment. Stop polling and
                            // leave the stream alone; the user can opt in explicitly from the
                            // bot settings if they really want Colgram to take over.
                            Log.w(TAG, "getUpdates conflict (409): another consumer owns this token. "
                                    + "Stopping poller and leaving the webhook untouched.");
                            break;
                        }

                        if (responseCode != 200) {
                            consecutiveErrors++;
                            Thread.sleep(Math.min(consecutiveErrors * 2000, 15000));
                            continue;
                        }
                        consecutiveErrors = 0;

                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject root = new JSONObject(sb.toString());
                        if (root.optBoolean("ok", false)) {
                            JSONArray updates = root.optJSONArray("result");
                            if (updates != null && updates.length() > 0) {
                                int maxId = currentOffset;
                                for (int i = 0; i < updates.length(); i++) {
                                    int uid = updates.getJSONObject(i).optInt("update_id", 0);
                                    if (uid >= maxId) {
                                        maxId = uid + 1;
                                    }
                                }
                                // Ack only after the batch is in storage — same reason as in
                                // syncBotDialogs. Retrying the same offset is safe and is the
                                // point: the messages are still queued server-side.
                                int applied = processUpdatesJson(appContext, account, updates);
                                if (applied >= 0) {
                                    lastUpdateIds.put(account, maxId);
                                    prefs.edit().putInt("last_update_id", maxId).apply();
                                    applyFailures = 0;
                                } else if (++applyFailures >= 3) {
                                    // Stop rather than spin. The offset was not advanced, so the
                                    // 60 s poller re-arm picks the same batch up again later
                                    // instead of this thread burning the battery on it.
                                    Log.e(TAG, "update batch failed to apply 3 times; leaving it unacknowledged for the next poller run");
                                    break;
                                }
                            }
                        }

                        Thread.sleep(300);

                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable t) {
                        Log.w(TAG, "Poller cycle error: " + t.getMessage());
                        // A transport failure (unreachable or blocked api.telegram.org) is not a
                        // 3-seconds-and-retry-it condition. Observed on this network: the poller
                        // logged and re-attempted 20 times a minute, forever, on a host that was
                        // never going to answer. Escalate like the HTTP-error path and cap higher.
                        consecutiveErrors++;
                        try {
                            Thread.sleep(Math.min(consecutiveErrors * 5000L, 60000L));
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
                Log.i(TAG, "Bot updates poller stopped for account " + account);
        }, "ColgramBotPoller-" + account);

            poller.setDaemon(true);
            // Publish the thread BEFORE starting it. A running thread that fails to find
            // itself in the map is a worse outcome than the reverse, and `isAlive()` is not
            // reliable until the scheduler has picked the thread up.
            pollerThreads.put(account, poller);
            poller.start();
        } finally {
            botPollerStarting.remove(account);
        }
    }

    /**
     * Tear down the poller for an account and clear the respawn guard.
     *
     * This did not exist before, which is the other half of the spawn loop: `pollerThreads`
     * was only ever written, never cleared, so the map permanently held a dead thread and no
     * code path could ever legitimately restart a poller after the first one exited.
     *
     * Call on logout, on account switch, and when the bot token is replaced.
     */
    public static synchronized void stopBotUpdatesPoller(final int account) {
        Thread existing = pollerThreads.remove(account);
        if (existing != null) {
            existing.interrupt();
        }
        // Clearing this is what actually allows a future start; it must happen here and
        // nowhere else, so that "spawned" keeps meaning "spawned and not since stopped".
        botPollerSpawned.remove(account);
        botPollerLastSpawnAt.remove(account);
        botPollerStarting.remove(account);
        Log.i(TAG, "bot updates poller torn down for account " + account
                + " (respawn guard cleared)");
    }

    public static void promptBotTokenAndSync(final Activity activity, final int account) {
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("🔑 Токен бота (@BotFather)");
        builder.setMessage("Введите токен бота из @BotFather для загрузки диалогов и синхронизации сообщений:\n\n"
                + "Важно: бот видит только те чаты, которые ему писали (или где он добавлен). "
                + "Если список пуст — напишите боту любое сообщение и нажмите «Синхронизировать» снова.");

        final EditText input = new EditText(activity);
        input.setHint("123456789:ABCdef...");
        String existing = getBotToken(activity, account);
        if (!existing.isEmpty()) input.setText(existing);
        builder.setView(input);

        builder.setPositiveButton("Синхронизировать", (dialog, which) -> {
            String token = input.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(activity, "Токен пустой", Toast.LENGTH_SHORT).show();
                return;
            }
            saveBotToken(activity, account, token);
            // Verify the token before the heavier dialog sync, so a typo is reported as a
            // token problem instead of surfacing as the misleading "no chats found".
            syncBotDialogs(activity, account, true);
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    public static void syncBotDialogs(final Context context, final int account) {
        syncBotDialogs(context, account, false);
    }

    /**
     * One-shot dialog sync trigger, also kicks off the real-time poller.
     */
    /**
     * Minimum gap between AUTOMATIC dialog syncs.
     *
     * 🔴 Without this, syncBotDialogs and MessagesController.loadDialogs form an infinite cycle:
     * loadDialogs() is patched to call syncBotDialogs(), and syncBotDialogs() calls loadDialogs()
     * at the end to refresh the UI. Each iteration issues a blocking Bot API request.
     *
     * Measured before this guard: ~3300 connection attempts in 90 seconds, dozens of concurrent
     * threads inside lambda$syncBotDialogs$13, and the IPv4 proxy eventually refusing connections
     * outright (1377 x `failed to connect to /127.0.0.1`). That is the user-visible
     * "бесконечная прогрузка чатов".
     *
     * 3 s is short enough that a genuinely new message still appears promptly, and long enough
     * that a dialog-refresh storm cannot drive it. Only AUTOMATIC calls are throttled; a
     * user-initiated sync always proceeds, so the button is never a no-op.
     */
    private static final long COLGRAM_SYNC_MIN_INTERVAL_MS = 3000L;

    /** Timestamp of the last ACCEPTED automatic sync, per account. Guarded by ColgramBotSync.class. */
    private static final ConcurrentHashMap<Integer, Long> colgramLastAutoSyncAt = new ConcurrentHashMap<>();

    /** Accounts with a deferred automatic sync already queued. Guarded by ColgramBotSync.class. */
    private static final java.util.Set<Integer> colgramAutoSyncQueued =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    public static void syncBotDialogs(final Context context, final int account, final boolean userInitiated) {
        if (context == null) return;
        final String token = getBotToken(context, account);
        if (token.isEmpty()) {
            if (userInitiated && context instanceof Activity) {
                promptBotTokenAndSync((Activity) context, account);
            }
            return;
        }

        // 🔴 THE CYCLE BREAK. Automatic calls only; a user-initiated sync always proceeds.
        //
        // A skipped sync used to be a bare `return`, which silently swallowed it: the only
        // automatic caller is the loadDialogs hook, which fires on UI events, so if nothing
        // else touched the dialog list the deferred work never came back. That is why the list
        // stayed empty until a restart reset the static timestamp. The skip now queues exactly
        // one retry for the remainder of the interval, and the queue is per-account so one busy
        // bot cannot starve another.
        if (!userInitiated) {
            long remaining;
            synchronized (ColgramBotSync.class) {
                Long last = colgramLastAutoSyncAt.get(account);
                remaining = COLGRAM_SYNC_MIN_INTERVAL_MS
                        - (System.currentTimeMillis() - (last == null ? 0L : last));
                if (remaining <= 0) {
                    colgramLastAutoSyncAt.put(account, System.currentTimeMillis());
                    colgramAutoSyncQueued.remove(account);
                }
            }
            if (remaining > 0) {
                synchronized (ColgramBotSync.class) {
                    if (colgramAutoSyncQueued.contains(account)) return;
                    colgramAutoSyncQueued.add(account);
                }
                mainHandler.postDelayed(() -> {
                    synchronized (ColgramBotSync.class) {
                        colgramAutoSyncQueued.remove(account);
                    }
                    syncBotDialogs(context, account, false);
                }, remaining);
                return;
            }
        }

        // Always ensure background poller is running
        startBotUpdatesPoller(context, account);

        // The poller is the single owner of the update stream. If it is live, this method must
        // NOT issue its own getUpdates: the Bot API answers 409 to the second consumer and
        // terminates the loser's queue, which stopped the poller outright — the "chats are
        // empty until I restart" symptom surviving every other fix here.
        final Thread livePoller = pollerThreads.get(account);
        final boolean pollerOwnsStream = livePoller != null && livePoller.isAlive()
                && !isPassiveBotMode(context, account);

        if (userInitiated) {
            Toast.makeText(context, "🔄 Синхронизация чатов бота...", Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            try {
                if (pollerOwnsStream) {
                    // Nothing to fetch — the poller already consumes this stream and will
                    // inject updates as they arrive. Just make the UI re-read what storage
                    // holds so a manual press is never a visible no-op.
                    notifyDialogsChanged(account);
                    return;
                }
                // Resolve the bot's own identity FIRST. Without a current user the client
                // cannot resolve its own id, so dialogs get inserted but never render —
                // they look like "no chats" even when storage has them.
                if (getBotSelfId(context, account) == 0) {
                    fetchAndRegisterBotSelf(context, account, token);
                }

                String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&timeout=0";
                HttpURLConnection conn = openConnection(urlStr, 12000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode == 409) {
                    // A webhook is registered on this token. Do not touch it — see
                    // deleteWebhook() for why. Report it so the empty chat list is not
                    // mistaken for "the bot has no chats".
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context,
                                "У бота установлен webhook. Colgram не трогает очередь обновлений, "
                                        + "чтобы не мешать вашему боту. Чаты подтянутся только те, "
                                        + "что уже есть в Bot API.",
                                Toast.LENGTH_LONG).show());
                    }
                    Log.w(TAG, "syncBotDialogs: 409, webhook owned by another consumer");
                }
                if (responseCode != 200) {
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context, "Ошибка Telegram Bot API: HTTP " + responseCode, Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                if (!root.optBoolean("ok", false)) {
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context, "Bot API: " + root.optString("description"), Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                JSONArray updates = root.optJSONArray("result");
                if (updates == null || updates.length() == 0) {
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context, "У бота пока нет входящих сообщений", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                int maxId = 0;
                for (int i = 0; i < updates.length(); i++) {
                    int uid = updates.getJSONObject(i).optInt("update_id", 0);
                    if (uid >= maxId) {
                        maxId = uid + 1;
                    }
                }
                int count = processUpdatesJson(context, account, updates);
                // Ack only once the batch is actually in storage. The offset used to be
                // committed before this call, and because the Bot API marks everything it has
                // returned as delivered, any failure inside processUpdatesJson lost those
                // messages permanently rather than delaying them.
                if (count >= 0 && maxId > 0) {
                    lastUpdateIds.put(account, maxId);
                    context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE)
                            .edit().putInt("last_update_id", maxId).apply();
                }
                if (userInitiated) {
                    final int finalCount = count;
                    if (count < 0) {
                        mainHandler.post(() -> Toast.makeText(context,
                                "Не удалось применить полученные сообщения. Colgram не подтвердил их "
                                        + "Telegram, так что они придут со следующей попыткой.",
                                Toast.LENGTH_LONG).show());
                    } else {
                        mainHandler.post(() -> Toast.makeText(context, "✅ Синхронизировано " + finalCount + " чатов бота!", Toast.LENGTH_SHORT).show());
                    }
                }

            } catch (Throwable t) {
                Log.e(TAG, "Error syncing bot updates", t);
                if (userInitiated) {
                    mainHandler.post(() -> Toast.makeText(context, "Сбой: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    /**
     * Sync every account that holds a bot token.
     *
     * Shared by the boot receiver and the boot job so neither grows its own copy of the
     * account loop. Account range is bounded by MAX_ACCOUNT_COUNT rather than a literal, and
     * a token is only ever stored for the account that authenticated with it — the old
     * cross-account fallback made every slot look like the same bot.
     */
    public static void ensureAllAccountsSynced(Context context, String reason) {
        if (context == null) {
            return;
        }
        for (int account = 0; account < 5; account++) {
            try {
                String token = getBotToken(context, account);
                if (token != null && !token.isEmpty()) {
                    syncBotDialogs(context, account, false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "sync failed for account " + account + " (" + reason + "): " + t.getMessage());
            }
        }
    }

    /**
     * Tell the dialog list to re-read storage, without writing anything.
     *
     * Reflection because colgram-core compiles before TMessagesProj and cannot reference its
     * classes directly.
     */
    private static void notifyDialogsChanged(final int account) {
        mainHandler.post(() -> {
            try {
                Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                Object nc = ncClass.getMethod("getInstance", int.class).invoke(null, account);
                int dialogsNeedReload = ncClass.getField("dialogsNeedReload").getInt(null);
                ncClass.getMethod("postNotificationName", int.class, Object[].class)
                        .invoke(nc, dialogsNeedReload, new Object[0]);
            } catch (Throwable t) {
                Log.w(TAG, "notifyDialogsChanged failed: " + t.getMessage());
            }
        });
    }

    /**
     * Parses Bot API updates array and injects users, messages, and dialogs into MessagesStorage & MessagesController.
     *
     * @return the number of dialogs written, or -1 if the batch could not be applied. The
     * negative case must stay distinguishable from a legitimate 0 (an update set carrying no
     * message at all, e.g. only callback_query), because callers use it to decide whether to
     * acknowledge the offset to Telegram. Returning 0 on error told them to ack, which lost
     * messages outright.
     */
    private static int processUpdatesJson(Context context, int account, JSONArray updates) {
        if (updates == null || updates.length() == 0) return 0;
        try {
            Class<?> userClass = Class.forName("org.telegram.tgnet.TLRPC$TL_user");
            Class<?> userStatusClass = Class.forName("org.telegram.tgnet.TLRPC$TL_userStatusRecently");
            Class<?> messageClass = Class.forName("org.telegram.tgnet.TLRPC$TL_message");
            Class<?> peerUserClass = Class.forName("org.telegram.tgnet.TLRPC$TL_peerUser");
            Class<?> peerChatClass = Class.forName("org.telegram.tgnet.TLRPC$TL_peerChat");
            Class<?> peerChannelClass = Class.forName("org.telegram.tgnet.TLRPC$TL_peerChannel");
            Class<?> dialogClass = Class.forName("org.telegram.tgnet.TLRPC$TL_dialog");
            Class<?> chatClass = Class.forName("org.telegram.tgnet.TLRPC$TL_chat");
            Class<?> channelClass = Class.forName("org.telegram.tgnet.TLRPC$TL_channel");
            Class<?> messagesDialogsClass = Class.forName("org.telegram.tgnet.TLRPC$TL_messages_dialogs");
            Class<?> messagesDialogsBaseClass = Class.forName("org.telegram.tgnet.TLRPC$messages_Dialogs");

            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);

            Class<?> msClass = Class.forName("org.telegram.messenger.MessagesStorage");
            Object ms = msClass.getMethod("getInstance", int.class).invoke(null, account);

            ArrayList usersList = new ArrayList();
            ArrayList messagesList = new ArrayList();
            Set<Long> processedUserIds = new HashSet<>();
            java.util.LinkedHashMap<Long, Integer> topMessageMap = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<Long, Integer> lastDateMap = new java.util.LinkedHashMap<>();
            // Which updates belong to a group/channel, and what to call it. Needed later when
            // building the chat object the dialog row is rendered from — the chat fields are
            // not all present on every update, so the last non-empty value wins.
            java.util.LinkedHashMap<Long, JSONObject> chatObjCache = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<Long, String> titleCache = new java.util.LinkedHashMap<>();

            // Resolved once: which sender in this batch is "us".
            final long botSelfId = getBotSelfId(context, account);

            // Incoming messages that plugins should see. Collected during the parse loop and
            // dispatched only after the batch is actually in storage, so a plugin replying to
            // message N cannot observe a dialog state that the same batch has not written yet.
            final ArrayList<Object[]> pluginInbound = new ArrayList<>();

            for (int i = 0; i < updates.length(); i++) {
                JSONObject upd = updates.getJSONObject(i);
                JSONObject msgObj = upd.optJSONObject("message");
                if (msgObj == null) msgObj = upd.optJSONObject("edited_message");
                if (msgObj == null) msgObj = upd.optJSONObject("channel_post");
                if (msgObj == null) continue;

                JSONObject fromObj = msgObj.optJSONObject("from");
                JSONObject chatObj = msgObj.optJSONObject("chat");

                long fromId = fromObj != null ? fromObj.optLong("id", 0) : 0;
                long chatId = chatObj != null ? chatObj.optLong("id", 0) : fromId;
                if (chatId == 0) continue;

                String firstName = fromObj != null ? fromObj.optString("first_name", "User") : "Chat";
                String lastName = fromObj != null ? fromObj.optString("last_name", "") : "";
                String username = fromObj != null ? fromObj.optString("username", "") : "";

                String text = msgObj.optString("text", "");
                if (text.isEmpty()) {
                    if (msgObj.has("caption")) text = msgObj.optString("caption");
                    else if (msgObj.has("photo")) text = "🖼 Фотография";
                    else if (msgObj.has("video")) text = "📹 Видео";
                    else if (msgObj.has("document")) text = "📎 Документ";
                    else if (msgObj.has("voice")) text = "🎤 Голосовое сообщение";
                    else if (msgObj.has("sticker")) text = "🎨 Стикер";
                    else text = "[Сообщение]";
                }

                int date = msgObj.optInt("date", (int) (System.currentTimeMillis() / 1000));
                int msgId = msgObj.optInt("message_id", 1);

                topMessageMap.put(chatId, msgId);
                lastDateMap.put(chatId, date);
                if (chatObj != null && chatId < 0) {
                    chatObjCache.put(chatId, chatObj);
                    String t = chatObj.optString("title", "");
                    if (t.isEmpty()) {
                        // Fall back to @username so the row is not labelled "Chat".
                        t = chatObj.optString("username", "");
                    }
                    if (!t.isEmpty()) titleCache.put(chatId, t);
                }

                // Create TLRPC.TL_user
                if (fromId != 0 && !processedUserIds.contains(fromId)) {
                    processedUserIds.add(fromId);
                    Object user = userClass.getConstructor().newInstance();
                    userClass.getField("id").setLong(user, fromId);
                    userClass.getField("first_name").set(user, firstName);
                    userClass.getField("last_name").set(user, lastName);
                    userClass.getField("username").set(user, username);
                    userClass.getField("phone").set(user, "");
                    userClass.getField("status").set(user, userStatusClass.getConstructor().newInstance());

                    usersList.add(user);
                    try {
                        mcClass.getMethod("putUser", Class.forName("org.telegram.tgnet.TLRPC$User"), boolean.class)
                                .invoke(mc, user, false);
                    } catch (Throwable ignored) {}
                }

                // Create TLRPC.TL_message
                Object message = messageClass.getConstructor().newInstance();
                messageClass.getField("id").setInt(message, msgId);
                messageClass.getField("date").setInt(message, date);
                messageClass.getField("message").set(message, text);
                try {
                    // The bot is "self" in this session, so anything it sent is an outgoing
                    // message. Hard-coded false put every bot reply on the incoming side of
                    // the thread, which reads as the bot's own messages never arriving.
                    messageClass.getField("out").setBoolean(message, botSelfId != 0 && fromId == botSelfId);
                } catch (Throwable ignored) {}

                // Resolve the correct peer type and id.
                //
                // Telegram has THREE peer shapes and the Bot API returns them all as
                // negative chat ids, so you cannot pick the class by sign alone:
                //
                //   -100XXXXXXXXXX  supergroup or channel  -> TLRPC.TL_peerChannel,
                //                                             id = -chatId - 1000000000000
                //   -XXXXXXXXXX     legacy group           -> TLRPC.TL_peerChat,
                //                                             id = -chatId
                //   > 0             private chat           -> TLRPC.TL_peerUser
                //
                // The previous code sent EVERY negative id to TL_peerChat as -chatId, so a
                // supergroup became channel_id 1001234567890 — an id that does not exist.
                // The dialog was stored under a bogus peer and never rendered, which is why
                // group chats were missing entirely.
                if (isSupergroupOrChannel(chatId)) {
                    Object peer = peerChannelClass.getConstructor().newInstance();
                    peerChannelClass.getField("channel_id").setLong(peer, channelIdFromBotApi(chatId));
                    messageClass.getField("peer_id").set(message, peer);
                } else if (chatId < 0) {
                    Object peer = peerChatClass.getConstructor().newInstance();
                    peerChatClass.getField("chat_id").setLong(peer, -chatId);
                    messageClass.getField("peer_id").set(message, peer);
                } else {
                    Object peer = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peer, chatId);
                    messageClass.getField("peer_id").set(message, peer);
                }

                if (fromId != 0) {
                    Object peerFrom = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peerFrom, fromId);
                    messageClass.getField("from_id").set(message, peerFrom);
                }

                messagesList.add(message);

                // Only anything NOT sent by this bot is "incoming". Feeding our own replies back
                // into the plugin hook is how an auto-responder ends up answering itself.
                boolean colgramIsIncoming = botSelfId == 0 || fromId != botSelfId;
                if (colgramIsIncoming && !text.isEmpty()) {
                    pluginInbound.add(new Object[]{chatId, Integer.valueOf(msgId), text});
                }
            }

            // Persist users and messages into database.
            //
            // ⚠️ REFLECTION RULE: pass the DECLARED parameter types, not concrete ones.
            // Class.getMethod(name, paramTypes...) matches the signature exactly, and
            // interface parameters are NOT interchangeable with their implementations.
            // MessagesStorage declares
            //     public void putUsersAndChats(List<User> users, List<Chat> chats,
            //                                  boolean withTransaction, boolean useQueue)
            // so asking for ArrayList.class throws NoSuchMethodException. This one call
            // used to throw here, at the top of processUpdatesJson, which aborted the
            // whole method: no messages were persisted, no dialogs created and the live
            // chat cache was never seeded. That is why a bot account's chat list came up
            // completely empty even though getUpdates was returning data.
            //
            // Note putMessages below IS correct: MessagesStorage really does declare
            // ArrayList<TLRPC.Message> there. Verify each call against the source; do not
            // copy the parameter list from a neighbouring call.
            if (!usersList.isEmpty()) {
                msClass.getMethod("putUsersAndChats", java.util.List.class, java.util.List.class, boolean.class, boolean.class)
                        .invoke(ms, usersList, null, true, true);
            }
            if (!messagesList.isEmpty()) {
                msClass.getMethod("putMessages", ArrayList.class, boolean.class, boolean.class, boolean.class, int.class, int.class, long.class)
                        .invoke(ms, messagesList, true, true, false, 0, 0, 0L);
            }

            // Create and persist TLRPC.TL_dialog for each unique chat.
            //
            // The dialog id MUST equal MessageObject.getPeerId(peer), which Telegram defines
            // as:
            //     TL_peerChat    -> -chat_id
            //     TL_peerChannel -> -channel_id
            //     TL_peerUser    ->  user_id
            //
            // The Bot API hands back a supergroup as -1001234567890. That is NOT a Telegram
            // dialog id: the real channel_id is 1234567890 and the dialog id is
            // -1234567890. Writing the raw -100-prefixed value produced a dialog whose id
            // matched no peer, so the chat never appeared in the list even though the
            // message and user rows were stored correctly. This mirrors the peer-type fix
            // applied to messages above.
            ArrayList dialogsList = new ArrayList();
            ArrayList chatsList = new ArrayList();
            for (java.util.Map.Entry<Long, Integer> entry : topMessageMap.entrySet()) {
                long botApiChatId = entry.getKey();
                int topMid = entry.getValue();
                int lastDate = lastDateMap.containsKey(botApiChatId) ? lastDateMap.get(botApiChatId)
                        : (int) (System.currentTimeMillis() / 1000);

                long dialogId;
                Object peer;
                if (isSupergroupOrChannel(botApiChatId)) {
                    long channelId = channelIdFromBotApi(botApiChatId);
                    dialogId = -channelId;
                    peer = peerChannelClass.getConstructor().newInstance();
                    peerChannelClass.getField("channel_id").setLong(peer, channelId);
                    // DialogsActivity resolves the title and row type from a registered chat
                    // object; without it the dialog row cannot be built at all.
                    Object chat = buildBotApiChat(chatClass, channelClass, chatObjCache.get(botApiChatId),
                            channelId, titleCache.get(botApiChatId), lastDate, true);
                    if (chat != null) chatsList.add(chat);
                } else if (botApiChatId < 0) {
                    long chatId = -botApiChatId;
                    dialogId = -chatId;
                    peer = peerChatClass.getConstructor().newInstance();
                    peerChatClass.getField("chat_id").setLong(peer, chatId);
                    Object chat = buildBotApiChat(chatClass, channelClass, chatObjCache.get(botApiChatId),
                            chatId, titleCache.get(botApiChatId), lastDate, false);
                    if (chat != null) chatsList.add(chat);
                } else {
                    dialogId = botApiChatId;
                    peer = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peer, dialogId);
                }

                Object dialog = dialogClass.getConstructor().newInstance();
                dialogClass.getField("id").setLong(dialog, dialogId);
                dialogClass.getField("peer").set(dialog, peer);
                dialogClass.getField("top_message").setInt(dialog, topMid);
                dialogClass.getField("last_message_date").setInt(dialog, lastDate);
                dialogClass.getField("unread_count").setInt(dialog, 0);
                dialogsList.add(dialog);
            }

            if (!chatsList.isEmpty()) {
                try {
                    msClass.getMethod("putUsersAndChats", java.util.List.class, java.util.List.class, boolean.class, boolean.class)
                            .invoke(ms, null, chatsList, true, true);
                    // Also publish to the live chat cache so the dialog list can build rows
                    // immediately, without waiting for a full getDialogs round-trip.
                    for (Object c : chatsList) {
                        try {
                            mcClass.getMethod("putChat", Class.forName("org.telegram.tgnet.TLRPC$Chat"), boolean.class)
                                    .invoke(mc, c, false);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "putUsersAndChats(chats) reflection warning", t);
                }
            }

            if (!dialogsList.isEmpty()) {
                try {
                    Object dialogsRes = messagesDialogsClass.getConstructor().newInstance();
                    messagesDialogsClass.getField("dialogs").set(dialogsRes, dialogsList);
                    messagesDialogsClass.getField("messages").set(dialogsRes, messagesList);
                    messagesDialogsClass.getField("users").set(dialogsRes, usersList);
                    messagesDialogsClass.getField("chats").set(dialogsRes, chatsList);

                    msClass.getMethod("putDialogs", messagesDialogsBaseClass, int.class).invoke(ms, dialogsRes, 1);
                } catch (Throwable t) {
                    Log.w(TAG, "putDialogs reflection warning", t);
                }
            }

            // Seed the IN-MEMORY dialog cache, not just SQLite.
            //
            // putDialogs() above only writes rows to the database. The chat list is rendered
            // from MessagesController.dialogs_dict / dialogMessage, and those are populated by
            // loadDialogs() from a server getDialogs response. A bot account's getDialogs
            // returns almost nothing, so the cache stayed empty and the list rendered blank
            // even though every dialog was correctly persisted. Nothing downstream reloads
            // storage on its own, so the cache has to be filled here.
            //
            // The same seed is also posted to the UI thread, because DialogsActivity reads
            // these structures directly while building rows.
            final ArrayList finalDialogsList = dialogsList;
            final ArrayList finalMessagesList = messagesList;
            final ArrayList finalUsersList = usersList;
            final ArrayList finalChatsList = chatsList;
            Runnable seedCache = () -> {
                try {
                    Class<?> dialogBaseClass = Class.forName("org.telegram.tgnet.TLRPC$Dialog");
                    Class<?> msgObjCls = Class.forName("org.telegram.messenger.MessageObject");
                    Class<?> chatBaseClass = Class.forName("org.telegram.tgnet.TLRPC$Chat");
                    Class<?> userBaseClass = Class.forName("org.telegram.tgnet.TLRPC$User");

                    java.lang.reflect.Field dictField = mcClass.getField("dialogs_dict");
                    java.lang.reflect.Field msgField = mcClass.getField("dialogMessage");

                    Object dict = dictField.get(mc);
                    Object msgs = msgField.get(mc);
                    if (dict == null || msgs == null) return;

                    // Resolve `put`/`get` from the RUNTIME class of the map, not from a
                    // hardcoded `android.util.LongSparseArray`.
                    //
                    // Upstream migrated these maps to androidx.collection.LongSparseArray, whose
                    // implementation is a COPY of the platform class - same name, same signatures,
                    // different class. `Class.getMethod` matches declared types exactly, so looking
                    // the method up on the platform class and invoking it on the androidx instance
                    // throws
                    //   "Expected receiver of type android.util.LongSparseArray,
                    //    but got androidx.collection.LongSparseArray"
                    // which aborted this whole block and left the chat list empty - the exact
                    // symptom the block exists to fix.
                    Method putSparse = dict.getClass().getMethod("put", long.class, Object.class);
                    // `get` differs by version (returns Object on some, void-in/void-out on
                    // others), so it is optional: we only ever put here.
                    Method getSparse = null;
                    try {
                        getSparse = dict.getClass().getMethod("get", long.class);
                    } catch (NoSuchMethodException ignored) {}

                    // Register users/chats in the live caches so titles and avatars resolve.
                    if (finalUsersList != null && !finalUsersList.isEmpty()) {
                        Method putUser = mcClass.getMethod("putUser", userBaseClass, boolean.class);
                        for (Object u : finalUsersList) putUser.invoke(mc, u, false);
                    }
                    if (finalChatsList != null && !finalChatsList.isEmpty()) {
                        Method putChat = mcClass.getMethod("putChat", chatBaseClass, boolean.class);
                        for (Object c : finalChatsList) putChat.invoke(mc, c, false);
                    }

                    for (Object d : finalDialogsList) {
                        long did = dialogClass.getField("id").getLong(d);
                        putSparse.invoke(dict, did, d);

                        // Attach the newest message as a MessageObject so the row shows a
                        // preview line instead of an empty subtitle.
                        Object best = null;
                        int bestId = -1;
                        for (Object m : finalMessagesList) {
                            try {
                                long peerId = peerIdOf(messageClass, peerChannelClass, peerChatClass,
                                        peerUserClass, m);
                                int mid = messageClass.getField("id").getInt(m);
                                if (peerId == did && mid > bestId) {
                                    bestId = mid;
                                    best = m;
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (best != null) {
                            // The real upstream signature is
                            //     MessageObject(int, TLRPC.Message, boolean generateLayout,
                            //                   boolean checkMediaExists)
                            // (MessageObject.java:1882). The previous call passed
                            // (int, message, null, false) - a literal 4th arg where the 3rd
                            // was meant to be a reply, which matches NEITHER overload:
                            //   1882 (int, Message, boolean, boolean)          <- no reply slot
                            //   1886 (int, Message, MessageObject, boolean, boolean)  <- 5 args
                            // So getConstructor() threw NoSuchMethodException every time, the
                            // catch below swallowed it, and the in-memory dialog cache never
                            // seeded - which is what makes the bot's chat list render with no
                            // previews / glitch. Resolve by parameter count and pass real
                            // booleans.
                            //
                            // generateLayout=false: we are seeding storage, not laying out.
                            // checkMediaExists=false: avoid a synchronous media probe here.
                            Object mo = null;
                            try {
                                mo = msgObjCls.getConstructor(int.class, messageClass,
                                                boolean.class, boolean.class)
                                        .newInstance(account, best, false, false);
                            } catch (NoSuchMethodException nsme) {
                                // Fall back to the reply-taking overload for forks/newer
                                // upstreams: (int, Message, MessageObject, boolean, boolean).
                                mo = msgObjCls.getConstructor(int.class, messageClass,
                                                msgObjCls, boolean.class, boolean.class)
                                        .newInstance(account, best, null, false, false);
                            }
                            if (mo != null) {
                                java.util.ArrayList<Object> list = new java.util.ArrayList<>();
                                list.add(mo);
                                // Resolve against `msgs` itself: the two maps are different
                                // classes in general, and a Method is bound to its declaring class.
                                msgs.getClass().getMethod("put", long.class, Object.class)
                                        .invoke(msgs, did, list);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "could not seed in-memory dialog cache: " + t.getMessage());
                }
            };
            // Run the seeding on a WORKER, never on the main thread.
            //
            // This block does an O(dialogs x messages) nested scan plus reflection per row,
            // and it used to run inline whenever the caller happened to be the main thread:
            //     if (myLooper() == getMainLooper()) seedCache.run(); else mainHandler.post(...)
            // So the expensive path fired exactly when the UI was most fragile. Measured on
            // device: "Skipped 47/34/55 frames" at the same instants as the poller churn -
            // ~0.8s of frozen UI per stall.
            //
            // Storage is thread-safe here, and the UI reload below is posted to mainHandler
            // explicitly, so moving the seeding off-main is safe and is the whole fix.
            executor.execute(seedCache);

            // Reload UI dialogs & messages
            mainHandler.post(() -> {
                try {
                    Method loadDialogs = mcClass.getMethod("loadDialogs", int.class, int.class, int.class, boolean.class, Runnable.class);
                    loadDialogs.invoke(mc, 0, 0, 100, true, null);

                    Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                    Method getInst = ncClass.getMethod("getInstance", int.class);
                    Object nc = getInst.invoke(null, account);

                    int dialogsNeedReload = ncClass.getField("dialogsNeedReload").getInt(null);
                    Method postNotification = ncClass.getMethod("postNotificationName", int.class, Object[].class);
                    postNotification.invoke(nc, dialogsNeedReload, new Object[0]);

                    try {
                        int updateInterfaces = ncClass.getField("updateInterfaces").getInt(null);
                        int maskAll = mcClass.getField("UPDATE_MASK_ALL").getInt(null);
                        postNotification.invoke(nc, updateInterfaces, new Object[]{maskAll});
                    } catch (Throwable ignored) {}

                } catch (Throwable t) {
                    Log.e(TAG, "Error notifying UI after bot sync", t);
                }
            });

            // Hand the batch's incoming messages to the plugin system now that they are stored
            // and the UI has been told about them.
            for (Object[] entry : pluginInbound) {
                try {
                    ColgramHookHandler.hookOnMessageReceived(
                            ((Long) entry[0]).longValue(),
                            ((Integer) entry[1]).intValue(),
                            (String) entry[2],
                            false);
                } catch (Throwable t) {
                    Log.w(TAG, "plugin inbound dispatch failed: " + t.getMessage());
                }
            }

            return dialogsList.size();
        } catch (Throwable t) {
            Log.e(TAG, "processUpdatesJson error", t);
            return -1;
        }
    }

    /**
     * Generic Bot API POST helper that actually surfaces Telegram's error text.
     *
     * Three traps this avoids:
     *   1. Reading getResponseCode() without draining the stream leaves the connection
     *      in a state where the error body is never readable, so a rejected request just
     *      looks like "HTTP 400" with no reason. The Bot API always explains itself in the
     *      body ("description" field) and that message is what the user needs to see.
     *   2. openConnection() must be given the POST stream before the code is read.
     *   3. A transport failure and a request Telegram actually rejected are different
     *      things. Collapsing both into "no response" hid the fact that the request never
     *      reached Telegram at all - which is the difference between "your token is wrong"
     *      and "your network is blocked", and the only actionable part of the message.
     *
     * @return the parsed JSON response, or a synthetic {"ok":false,"description":...}
     *         carrying the transport failure so callers still report something honest
     */
    private static JSONObject botApiPost(String token, String method, JSONObject payload) {
        final String urlStr = "https://api.telegram.org/bot" + token + "/" + method;
        // A POST cannot be probed the way a GET can: HttpURLConnection rejects setDoOutput() once
        // the socket has been dialled, so the transport ladder has to be walked here, around the
        // write, instead of inside openConnection(). Every Bot API method used through this is
        // idempotent (a name or description set to the same value, an update offset), so retrying
        // the next transport cannot double-apply anything.
        java.util.List<java.net.Proxy> rungs;
        try {
            URL parsed = new URL(urlStr);
            rungs = transportLadder(parsed.getHost(),
                    parsed.getPort() > 0 ? parsed.getPort() : 443);
        } catch (Throwable t) {
            rungs = new java.util.ArrayList<>();
            rungs.add(java.net.Proxy.NO_PROXY);
        }

        Throwable last = null;
        for (java.net.Proxy rung : rungs) {
            HttpURLConnection conn = null;
            try {
                conn = openVia(new URL(urlStr), rung, 15000,
                        Math.min(COLGRAM_CONNECT_TIMEOUT_MS, 4000));
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] body = payload.toString().getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.close();

                int code = conn.getResponseCode();
                java.io.InputStream stream = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (stream != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                }
                if (sb.length() == 0) {
                    // Reached the server, but it said nothing. Worth distinguishing from an
                    // outright transport failure, because it usually means a proxy ate the body.
                    return transportFailure("пустой ответ (HTTP " + code + ")");
                }
                if (code < 200 || code >= 300) {
                    Log.w(TAG, method + " HTTP " + code + ": " + sb);
                }
                return new JSONObject(sb.toString());
            } catch (javax.net.ssl.SSLException se) {
                last = se;
                Log.w(TAG, method + " TLS failed via " + rung + ": " + se.getMessage());
            } catch (Throwable t) {
                last = t;
                Log.w(TAG, method + " failed via " + rung + ": " + t.getMessage());
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignore) {}
                }
            }
        }

        if (colgramShouldTryDirect()) colgramReportDirectRouteFailed();
        // Nothing carried the write: drop the chosen address so the next call re-probes instead of
        // walking into the same black hole, and hand the caller a reason rather than silence.
        ColgramEndpoints.invalidate("api.telegram.org");
        if (last instanceof javax.net.ssl.SSLException) {
            return transportFailure("ошибка TLS — соединение перехвачено или заблокировано");
        }
        return transportFailure(connectionHint(reasonOf(last)));
    }

    /**
     * A synthetic non-ok response so a transport failure travels the same path as a real
     * Bot API error and reaches the user with a reason attached.
     */
    private static JSONObject transportFailure(String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("description", reason);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Name the likely culprit instead of leaving the user with a bare timeout.
     *
     * The desync listener is the first suspect whenever it is bound, because it sits in
     * front of every request and its own upstream connection has a much shorter budget
     * than the caller's.
     */
    private static String connectionHint(String base) {
        if (ColgramDpiBypass.isBound()) {
            return base + " (проверьте обход блокировок — локальный прокси 127.0.0.1:"
                    + ColgramDpiBypass.LOCAL_PORT + " не отвечает)";
        }
        return base;
    }

    /** Human-readable reason from a Bot API error response, or null when it succeeded. */
    private static String botApiError(JSONObject resp) {
        if (resp == null) return "нет ответа";
        if (resp.optBoolean("ok", false)) return null;
        String desc = resp.optString("description", "");
        return desc.isEmpty() ? "неизвестная ошибка" : desc;
    }

    /**
     * Updates bot name via Telegram Bot API setMyName (bypassing MTProto BOT_METHOD_INVALID).
     */
    public static void updateBotName(final Context context, final int account, final String newName) {
        final String token = getBotToken(context, account);
        if (token.isEmpty()) {
            mainHandler.post(() -> Toast.makeText(context, "Токен бота не найден", Toast.LENGTH_SHORT).show());
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("name", newName);
                JSONObject resp = botApiPost(token, "setMyName", json);
                final String err = botApiError(resp);

                if (err == null) {
                    // "ok: true" is not proof the name changed. Verify it the way a user would -
                    // ask the server what the name now is - because the failure he reported is
                    // exactly this shape: the local profile shows the new name, and Telegram
                    // somewhere else still shows the old one. Either the write never landed, or
                    // another client is serving a cached user record, and those need saying out
                    // loud rather than a green toast.
                    String serverName = null;
                    try {
                        // botApiGet already unwraps ok/result and returns null on any failure.
                        JSONObject nameRes = botApiGet(context, token, "getMyName");
                        if (nameRes != null) {
                            serverName = nameRes.optString("name", "");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "getMyName verification failed: " + t.getMessage());
                    }
                    final String confirmed = serverName;
                    if (confirmed != null && !confirmed.equals(newName)) {
                        mainHandler.post(() -> Toast.makeText(context,
                                "Сервер ответил, что имя бота осталось «" + confirmed
                                        + "». В других клиентах оно обновится не сразу.",
                                Toast.LENGTH_LONG).show());
                        return;
                    }
                    mainHandler.post(() -> {
                        try {
                            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                            Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, account);
                            Object currentUser = ucClass.getMethod("getCurrentUser").invoke(uc);
                            if (currentUser != null) {
                                // Split the display name the way Telegram does, so the
                                // profile header and the dialog row agree with the server.
                                String first = newName;
                                String last = "";
                                int sp = newName.indexOf(' ');
                                if (sp > 0) {
                                    first = newName.substring(0, sp);
                                    last = newName.substring(sp + 1).trim();
                                }
                                currentUser.getClass().getField("first_name").set(currentUser, first);
                                currentUser.getClass().getField("last_name").set(currentUser, last);
                                ucClass.getMethod("saveConfig", boolean.class).invoke(uc, true);
                            }

                            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                            Object nc = ncClass.getMethod("getInstance", int.class).invoke(null, account);
                            int mainUserInfoChanged = ncClass.getField("mainUserInfoChanged").getInt(null);
                            ncClass.getMethod("postNotificationName", int.class, Object[].class).invoke(nc, mainUserInfoChanged, new Object[0]);

                            Toast.makeText(context, "Имя бота обновлено", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Log.e(TAG, "Error updating local user name", t);
                        }
                    });
                } else {
                    mainHandler.post(() -> Toast.makeText(context,
                            "Не удалось изменить имя: " + err, Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                // The failure he reported is this shape: the button does nothing and no reason
                // appears. A swallowed exception in a background task is indistinguishable from a
                // dead app, so say what broke and where.
                Log.e(TAG, "Error setMyName", t);
                final String why = connectionHint(reasonOf(t));
                mainHandler.post(() -> Toast.makeText(context,
                        "Не удалось изменить имя: " + why, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Short, human-readable reason for a transport failure. */
    private static String reasonOf(Throwable t) {
        if (t == null) return "нет ошибки";
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** Callback for the Bot API self-test; a named interface avoids java.util.function on old API. */
    public interface BotApiReport {
        void onReport(String text);
    }

    /**
     * Prove the Bot API write path on the network the phone is actually on.
     *
     * Checking it otherwise means editing the bot profile and hoping: the write and the read-back
     * are separate calls, and the failure he reported was a transport one, not an API one. The two
     * writes put back exactly what the matching read returned, so running the test cannot leave
     * the bot's public profile changed. Every step is timed, because "ошибка соединения" with no
     * number attached is what made this undiagnosable in the first place.
     */
    public static void selfTestBotApi(final Context context, final int account,
                                      final BotApiReport report) {
        final String token = getBotToken(context, account);
        if (token.isEmpty()) {
            if (report != null) {
                mainHandler.post(() -> report.onReport("Токен бота не найден для аккаунта " + account));
            }
            return;
        }
        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            step(sb, token, "getMe", null);
            String name = null;
            try {
                JSONObject res = postJson(token, "getMyName", new JSONObject());
                name = res == null ? null : res.optString("name", null);
                sb.append("getMyName: ").append(name == null ? "нет ответа" : quoted(name)).append('\n');
            } catch (Throwable t) {
                sb.append("getMyName: ").append(connectionHint(reasonOf(t))).append('\n');
            }
            if (name != null) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("name", name);
                    step(sb, token, "setMyName", body);
                } catch (Throwable ignore) {
                }
            }
            String desc = null;
            try {
                JSONObject res = postJson(token, "getMyDescription", new JSONObject());
                desc = res == null ? null : res.optString("description", null);
                sb.append("getMyDescription: ").append(desc == null ? "нет ответа"
                        : desc.length() + " симв.").append('\n');
            } catch (Throwable t) {
                sb.append("getMyDescription: ").append(connectionHint(reasonOf(t))).append('\n');
            }
            if (desc != null) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("description", desc);
                    step(sb, token, "setMyDescription", body);
                } catch (Throwable ignore) {
                }
            }
            sb.append("адрес: ").append(ColgramEndpoints.describe());
            final String text = sb.toString();
            Log.i(TAG, "Bot API self-test:\n" + text);
            if (report != null) mainHandler.post(() -> report.onReport(text));
        });
    }

    /** One call inside the self-test: timed, with the server's own reason when it fails. */
    private static void step(StringBuilder sb, String token, String method, JSONObject body) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            // The raw response is what gets judged: a write answers {"ok":true,"result":true},
            // and unwrapping that "result" (a boolean) lost the answer and reported success as
            // "no response".
            JSONObject resp = botApiPost(token, method, body == null ? new JSONObject() : body);
            long ms = android.os.SystemClock.elapsedRealtime() - t0;
            String err = botApiError(resp);
            sb.append(method).append(": ").append(err == null ? "ок" : err)
                    .append(" (").append(ms).append(" мс)\n");
        } catch (Throwable t) {
            long ms = android.os.SystemClock.elapsedRealtime() - t0;
            sb.append(method).append(": ").append(connectionHint(reasonOf(t)))
                    .append(" (").append(ms).append(" мс)\n");
        }
    }

    private static String quoted(String s) {
        return "«" + s + "»";
    }

    /**
     * Updates bot description via Bot API setMyDescription (shown on the bot profile page)
     * and setMyShortDescription (shown in the chat header / share sheet).
     *
     * Both are attempted independently: Telegram rejects a too-long description on one
     * field while accepting the other, so a single shared error path would report a
     * failure for an edit that partly succeeded. The first real error is surfaced.
     */
    public static void updateBotDescription(final Context context, final int account, final String newBio, final Runnable onDone) {
        final String token = getBotToken(context, account);
        if (token.isEmpty()) {
            mainHandler.post(() -> Toast.makeText(context, "Токен бота не найден", Toast.LENGTH_SHORT).show());
            return;
        }

        executor.execute(() -> {
            String firstError = null;
            try {
                JSONObject full = new JSONObject();
                full.put("description", newBio);
                String e1 = botApiError(botApiPost(token, "setMyDescription", full));
                if (e1 != null) firstError = e1;

                JSONObject shortDesc = new JSONObject();
                shortDesc.put("short_description", newBio);
                String e2 = botApiError(botApiPost(token, "setMyShortDescription", shortDesc));
                if (e2 != null && firstError == null) firstError = e2;
            } catch (Throwable t) {
                Log.e(TAG, "Error setMyDescription", t);
                if (firstError == null) firstError = t.getMessage();
            }

            final String finalError = firstError;
            mainHandler.post(() -> {
                if (finalError == null) {
                    Toast.makeText(context, "Описание бота обновлено", Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                } else {
                    Toast.makeText(context, "Не удалось изменить описание: " + finalError, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** POST a Bot API method and return its "result" object, or null when the call failed. */
    private static JSONObject postJson(String token, String method, JSONObject body) throws Exception {
        JSONObject resp = botApiPost(token, method, body);
        return resp == null ? null : resp.optJSONObject("result");
    }

    /**
     * Read the bot's current profile from the API so the edit screens open pre-filled.
     * Returns null when the token is missing or the call fails.
     */
    public static JSONObject fetchBotProfile(Context context, int account) {
        String token = getBotToken(context, account);
        if (token.isEmpty()) return null;
        try {
            HttpURLConnection conn = openConnection("https://api.telegram.org/bot" + token + "/getMe", 12000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            java.io.InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONObject root = new JSONObject(sb.toString());
            if (!root.optBoolean("ok", false)) return null;
            JSONObject result = root.optJSONObject("result");
            if (result == null) return null;

            // getMe does NOT return the bot's description — the Bot API only serves it from
            // getMyDescription. Callers read result.optString("description"), so without this
            // extra request the field was always empty and the profile screen blanked the bot's
            // real bio on every open. That is the "I edited the description and it only showed
            // up after visiting the profile a couple of times" report.
            try {
                JSONObject desc = botApiGet(context, token, "getMyDescription");
                if (desc != null) {
                    String botDescription = desc.optString("description", "");
                    if (!botDescription.isEmpty()) {
                        result.put("description", botDescription);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "getMyDescription failed: " + t.getMessage());
            }
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "fetchBotProfile failed: " + t.getMessage());
            return null;
        }
    }

    /** A Bot API GET that returns the `result` object, or null on any failure. */
    private static JSONObject botApiGet(Context context, String token, String method) {
        try {
            HttpURLConnection conn = openConnection(
                    "https://api.telegram.org/bot" + token + "/" + method, 12000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            java.io.InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONObject root = new JSONObject(sb.toString());
            return root.optBoolean("ok", false) ? root.optJSONObject("result") : null;
        } catch (Throwable t) {
            Log.w(TAG, method + " failed: " + t.getMessage());
            return null;
        }
    }

    private static int getThemeColor(Class<?> themeClass, String keyName, int defaultColor) {
        if (themeClass == null) return defaultColor;
        try {
            Field keyField = themeClass.getField(keyName);
            int key = keyField.getInt(null);
            Method getColorMethod = themeClass.getMethod("getColor", int.class);
            return (int) getColorMethod.invoke(null, key);
        } catch (Throwable t) {
            return defaultColor;
        }
    }

    /**
     * Opens a dialog allowing the bot to initiate a conversation with any user by username or ID.
     */
    public static void showStartChatDialog(final Activity activity, final int account) {
        showStartChatDialog(activity, account, null);
    }

    public static void showStartChatDialog(final Activity activity, final int account, final Object fragmentObj) {
        if (activity == null || activity.isFinishing()) return;

        boolean isRu = false;
        try {
            Class<?> lcClass = Class.forName("org.telegram.messenger.LocaleController");
            Object lc = lcClass.getMethod("getInstance").invoke(null);
            Field currField = lcClass.getDeclaredField("currentLocaleInfo");
            currField.setAccessible(true);
            Object li = currField.get(lc);
            if (li != null) {
                String sn = (String) li.getClass().getField("shortName").get(li);
                isRu = "ru".equalsIgnoreCase(sn);
            }
        } catch (Throwable ignored) {}

        final boolean isRussian = isRu;

        try {
            Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme");
            Class<?> alertBuilderClass = Class.forName("org.telegram.ui.ActionBar.AlertDialog$Builder");

            Method dpMethod = auClass.getMethod("dp", float.class);
            int dp8 = (int) dpMethod.invoke(null, 8f);
            int dp12 = (int) dpMethod.invoke(null, 12f);
            int dp16 = (int) dpMethod.invoke(null, 16f);
            int dp20 = (int) dpMethod.invoke(null, 20f);

            int textColor = getThemeColor(themeClass, "key_dialogTextBlack", Color.parseColor("#222222"));
            int grayColor = getThemeColor(themeClass, "key_dialogTextGray", Color.parseColor("#888888"));
            int hintColor = getThemeColor(themeClass, "key_dialogTextHint", Color.parseColor("#AAAAAA"));
            int accentColor = getThemeColor(themeClass, "key_featuredStickers_addButton", Color.parseColor("#2AABEE"));
            int fieldBgColor = getThemeColor(themeClass, "key_dialogInputField", Color.parseColor("#0F000000"));

            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp20, dp8, dp20, dp8);

            TextView descView = new TextView(activity);
            descView.setText(isRussian
                    ? "Введите @username или числовой ID пользователя, чтобы открыть чат от имени бота:"
                    : "Enter @username or user ID to start a chat as bot:");
            descView.setTextColor(grayColor);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            descView.setPadding(0, 0, 0, dp16);
            container.addView(descView);

            LinearLayout inputCard = new LinearLayout(activity);
            inputCard.setOrientation(LinearLayout.HORIZONTAL);
            inputCard.setGravity(Gravity.CENTER_VERTICAL);
            inputCard.setPadding(dp12, dp8, dp12, dp8);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp8);
            cardBg.setColor(fieldBgColor != 0 ? fieldBgColor : Color.parseColor("#15000000"));
            cardBg.setStroke((int) dpMethod.invoke(null, 1.0f), accentColor & 0x4DFFFFFF);
            inputCard.setBackground(cardBg);

            final EditText input = new EditText(activity);
            input.setHint("@username или 123456789");
            input.setHintTextColor(hintColor);
            input.setTextColor(textColor);
            input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            input.setBackground(null);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            input.setLayoutParams(inputParams);
            inputCard.addView(input);

            container.addView(inputCard);

            Object builder = alertBuilderClass.getConstructor(Context.class).newInstance(activity);
            alertBuilderClass.getMethod("setTitle", CharSequence.class).invoke(builder, isRussian ? "✉️ Написать пользователю" : "✉️ Message User");
            alertBuilderClass.getMethod("setView", View.class).invoke(builder, container);

            alertBuilderClass.getMethod("setPositiveButton", CharSequence.class, DialogInterface.OnClickListener.class)
                    .invoke(builder, isRussian ? "Открыть чат" : "Open Chat", (DialogInterface.OnClickListener) (dialog, which) -> {
                        String query = input.getText().toString().trim();
                        if (query.isEmpty()) return;
                        openChatAsBot(activity, account, fragmentObj, query);
                    });

            alertBuilderClass.getMethod("setNegativeButton", CharSequence.class, DialogInterface.OnClickListener.class)
                    .invoke(builder, isRussian ? "Отмена" : "Cancel", null);

            alertBuilderClass.getMethod("show").invoke(builder);

        } catch (Throwable t) {
            Log.e(TAG, "showStartChatDialog fallback error", t);
            fallbackShowStartChatDialog(activity, account, fragmentObj, isRussian);
        }
    }

    private static void fallbackShowStartChatDialog(final Activity activity, final int account, final Object fragmentObj, final boolean isRussian) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(isRussian ? "✉️ Написать пользователю" : "✉️ Message User");
        builder.setMessage(isRussian ? "Введите @username или числовой User ID пользователя:" : "Enter @username or numerical User ID:");

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        final EditText input = new EditText(activity);
        input.setHint("@username или 123456789");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        layout.addView(input);

        builder.setView(layout);
        builder.setPositiveButton(isRussian ? "Открыть чат" : "Open Chat", (dialog, which) -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) return;
            openChatAsBot(activity, account, fragmentObj, query);
        });
        builder.setNegativeButton(isRussian ? "Отмена" : "Cancel", null);
        builder.show();
    }

    private static void openChatAsBot(Activity activity, int account, Object fragmentObj, String query) {
        try {
            if (query.startsWith("@")) query = query.substring(1);

            Class<?> baseFragmentClass = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            Class<?> launchActivityClass = Class.forName("org.telegram.ui.LaunchActivity");
            Class<?> chatActivityClass = Class.forName("org.telegram.ui.ChatActivity");

            Object targetFragment = fragmentObj;
            if (targetFragment == null) {
                try {
                    Method getSafeLast = launchActivityClass.getMethod("getSafeLastFragment");
                    targetFragment = getSafeLast.invoke(null);
                } catch (Throwable ignored) {}
            }

            if (query.matches("^\\d+$")) {
                long userId = Long.parseLong(query);
                Bundle args = new Bundle();
                args.putLong("user_id", userId);
                Constructor<?> ctor = chatActivityClass.getConstructor(Bundle.class);
                Object chatFrag = ctor.newInstance(args);

                if (targetFragment != null) {
                    Method presentFragment = baseFragmentClass.getMethod("presentFragment", baseFragmentClass);
                    presentFragment.invoke(targetFragment, chatFrag);
                } else if (launchActivityClass.isInstance(activity)) {
                    Method presentFragment = launchActivityClass.getMethod("presentFragment", baseFragmentClass);
                    presentFragment.invoke(activity, chatFrag);
                }
            } else {
                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);

                Method openByUserName = mcClass.getMethod("openByUserName", String.class, baseFragmentClass, int.class);
                openByUserName.invoke(mc, query, targetFragment, 1);
            }
        } catch (Throwable t) {
            Log.e(TAG, "openChatAsBot error", t);
            Toast.makeText(activity, "Ошибка открытия чата: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
