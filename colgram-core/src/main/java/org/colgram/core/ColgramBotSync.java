package org.colgram.core;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final ExecutorService executor = createBoundedExecutor("colgram-bot-sync-", 6, 256,
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final ExecutorService avatarExecutor = createBoundedExecutor("colgram-bot-avatar-", 4, 64,
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private static ExecutorService createBoundedExecutor(String threadPrefix, int maxThreads, int queueCapacity,
                                                         java.util.concurrent.RejectedExecutionHandler rejectedHandler) {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(() -> {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        } catch (Throwable ignored) {}
                        r.run();
                    }, threadPrefix + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                rejectedHandler
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** The phantom-dialog sweep runs once per process, after the first cache seed. */
    private static final java.util.concurrent.atomic.AtomicBoolean phantomCleanupDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
    private static final java.util.Set<String> dispatchedLiveMsgKeys =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final java.util.Set<Integer> mtprotoBotLoops =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static final java.util.Set<Integer> mtprotoDialogRefreshPending =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    private static boolean isMtprotoBotAccount(int account) {
        try {
            Class<?> userConfig = Class.forName("org.telegram.messenger.UserConfig");
            Object config = userConfig.getMethod("getInstance", int.class).invoke(null, account);
            Object user = userConfig.getMethod("getCurrentUser").invoke(config);
            return user != null && user.getClass().getField("bot").getBoolean(user);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isMtprotoConnected(int account) {
        try {
            Class<?> connections = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Object manager = connections.getMethod("getInstance", int.class).invoke(null, account);
            return ((Integer) connections.getMethod("getConnectionState").invoke(manager)) == 3;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Recover missed bot updates over Telegram's authenticated MTProto session. */
    private static volatile Class<?> cachedMessagesControllerClass;
    private static volatile Method cachedMcGetInstance;
    private static volatile Method cachedMcGetDifference;
    private static volatile Field cachedStageQueueField;
    private static volatile Method cachedStageQueuePostRunnable;

    private static Object getMessagesControllerInstance(int account) throws Exception {
        Class<?> cls = cachedMessagesControllerClass;
        if (cls == null) {
            cls = Class.forName("org.telegram.messenger.MessagesController");
            cachedMcGetInstance = cls.getMethod("getInstance", int.class);
            cachedMcGetDifference = cls.getMethod("getDifference");
            try {
                Class<?> util = Class.forName("org.telegram.messenger.Utilities");
                Field sqField = util.getField("stageQueue");
                Object sq = sqField.get(null);
                if (sq != null) {
                    cachedStageQueuePostRunnable = sq.getClass().getMethod("postRunnable", Runnable.class);
                    cachedStageQueueField = sqField;
                }
            } catch (Throwable ignored) {}
            cachedMessagesControllerClass = cls;
        }
        return cachedMcGetInstance.invoke(null, account);
    }

    private static void postGetDifferenceOnStageQueue(final int account) throws Exception {
        final Object instance = getMessagesControllerInstance(account);
        if (instance == null || cachedMcGetDifference == null) return;
        Runnable task = () -> {
            try {
                cachedMcGetDifference.invoke(instance);
            } catch (Throwable t) {
                Log.w(TAG, "MTProto bot getDifference on stageQueue failed: " + t.getMessage());
            }
        };
        if (cachedStageQueueField != null && cachedStageQueuePostRunnable != null) {
            Object sq = cachedStageQueueField.get(null);
            if (sq != null) {
                cachedStageQueuePostRunnable.invoke(sq, task);
                return;
            }
        }
        task.run();
    }

    private static void ensureMtprotoBotLoop(final Context context, final int account) {
        if (!mtprotoBotLoops.add(account)) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!mtprotoBotLoops.contains(account)) return;
                if (!isMtprotoBotAccount(account) || isPassiveBotMode(context, account)) {
                    mtprotoBotLoops.remove(account);
                    return;
                }
                final boolean fg = ColgramUiBridge.isAppInForeground();
                if ((fg || !ColgramPowerGuard.shouldThrottle(context)) && isMtprotoConnected(account)) {
                    try {
                        postGetDifferenceOnStageQueue(account);
                        Log.i(TAG, "MTProto bot update recovery requested for account " + account);
                    } catch (Throwable t) {
                        Log.w(TAG, "MTProto bot update recovery failed: " + t.getMessage());
                    }
                }
                long delayMs;
                if (fg) {
                    delayMs = 30_000L;
                } else {
                    long baseBg = ColgramPowerGuard.isScreenInteractive(context) ? 90_000L : 180_000L;
                    delayMs = baseBg * Math.max(1, ColgramPowerGuard.intervalFactor(context));
                }
                mainHandler.postDelayed(this, delayMs);
            }
        });
        refreshMtprotoBotDialogs(context, account);
    }

    /** Refresh the bot account's local dialog cache for an explicit sync action. */
    private static void refreshMtprotoBotDialogs(final Context context, final int account) {
        if (context == null || !mtprotoDialogRefreshPending.add(account)) return;
        mainHandler.post(() -> {
            try {
                if (!mtprotoBotLoops.contains(account)
                        || !isMtprotoBotAccount(account)
                        || isPassiveBotMode(context, account)) {
                    return;
                }
                Object instance = getMessagesControllerInstance(account);
                Class<?> controller = cachedMessagesControllerClass;
                if (isMtprotoConnected(account)) {
                    try {
                        postGetDifferenceOnStageQueue(account);
                    } catch (Throwable t) {
                        Log.w(TAG, "MTProto bot manual difference request failed: " + t.getMessage());
                    }
                }
                // Bot dialogs are backed by local storage in the patched MessagesController.
                // Load storage directly when the folder list is empty, otherwise re-sort and notify UI.
                Object dialogsObj = controller.getMethod("getDialogs", int.class).invoke(instance, 0);
                java.util.List<?> dialogs = dialogsObj instanceof java.util.List ? (java.util.List<?>) dialogsObj : null;
                if (dialogs == null || dialogs.isEmpty()) {
                    Object ms = Class.forName("org.telegram.messenger.MessagesStorage")
                            .getMethod("getInstance", int.class).invoke(null, account);
                    if (ms != null) {
                        ms.getClass().getMethod("getDialogs", int.class, int.class, int.class, boolean.class)
                                .invoke(ms, 0, 0, 100, true);
                    }
                } else {
                    try {
                        Class<?> lsa = Class.forName("androidx.collection.LongSparseArray");
                        controller.getMethod("sortDialogs", lsa).invoke(instance, new Object[]{null});
                    } catch (Throwable ignored) {}
                    notifyDialogsChanged(account);
                }
                Log.i(TAG, "MTProto bot manual dialog refresh requested for account " + account);
            } catch (Throwable t) {
                Log.w(TAG, "MTProto bot manual dialog refresh failed: " + t.getMessage());
            } finally {
                mtprotoDialogRefreshPending.remove(account);
            }
        });
    }

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
    private static final java.util.concurrent.Semaphore IPV4_PROXY_SLOTS =
            new java.util.concurrent.Semaphore(48);
    private static final java.util.concurrent.ExecutorService IPV4_PROXY_POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    0, 96, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(() -> {
                            try {
                                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                            } catch (Throwable ignored) {}
                            r.run();
                        }, "colgram-ipv4-proxy-conn");
                        t.setDaemon(true);
                        t.setUncaughtExceptionHandler((th, ex) -> Log.w(TAG, "ipv4 proxy err: " + ex));
                        return t;
                    });

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
                Thread t = new Thread(() -> {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    } catch (Throwable ignored) {}
                    colgramIpv4ProxyLoop(ss);
                }, "colgram-ipv4-proxy");
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
        while (!server.isClosed()) {
            try {
                final java.net.Socket client = server.accept();
                if (!IPV4_PROXY_SLOTS.tryAcquire()) {
                    try { client.close(); } catch (Throwable ignored) {}
                    continue;
                }
                try {
                    final java.util.concurrent.atomic.AtomicInteger permitRefs =
                            new java.util.concurrent.atomic.AtomicInteger(1);
                    IPV4_PROXY_POOL.execute(() -> {
                        try {
                            colgramHandleProxyClient(client, permitRefs);
                        } catch (Throwable ignored) {
                        } finally {
                            if (permitRefs.decrementAndGet() == 0) {
                                IPV4_PROXY_SLOTS.release();
                            }
                        }
                    });
                } catch (Throwable rejected) {
                    IPV4_PROXY_SLOTS.release();
                    try { client.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                if (server.isClosed()) break;
                // Never let one accept failure kill the listener.
                Log.w(TAG, "proxy accept: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
    }

    /** Serve one client: read its request, dial IPv4 upstream, relay. */
    private static void colgramHandleProxyClient(java.net.Socket client,
                                                 java.util.concurrent.atomic.AtomicInteger permitRefs) {
        java.net.Socket upstream = null;
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
            upstream = new ColgramIpv4Socket(v4, upstreamPort);
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

            java.net.Socket handedUpstream = upstream;
            upstream = null;
            colgramPump(client, handedUpstream, permitRefs);
        } catch (Throwable t) {
            // One bad client must never take the listener down.
            Log.w(TAG, "proxy: " + t.getClass().getSimpleName() + " " + t.getMessage());
            try { if (upstream != null) upstream.close(); } catch (Throwable ignored) {}
            try { client.close(); } catch (Throwable ignored) {}
        }
    }

    /** Relay both directions until either side closes, releasing the slot permit only after both threads exit. */
    private static void colgramPump(final java.net.Socket client, final java.net.Socket upstream,
                                    final java.util.concurrent.atomic.AtomicInteger permitRefs) {
        final java.net.Socket[] both = new java.net.Socket[] { client, upstream };
        boolean secondaryDispatched = false;
        try {
            permitRefs.incrementAndGet();
            IPV4_PROXY_POOL.execute(() -> {
                try {
                    colgramCopy(upstream, client, both);
                } finally {
                    if (permitRefs.decrementAndGet() == 0) {
                        IPV4_PROXY_SLOTS.release();
                    }
                }
            });
            secondaryDispatched = true;
            colgramCopy(client, upstream, both);
        } catch (Throwable t) {
            if (!secondaryDispatched) {
                permitRefs.decrementAndGet();
            }
            for (java.net.Socket s : both) {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
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

    private static volatile java.net.Proxy lastWorkingBotRung = null;
    private static volatile long lastWorkingBotRungAtMs = 0L;
    private static final long BOT_RUNG_CACHE_TTL_MS = 5L * 60L * 1000L;

    private static HttpURLConnection openConnection(String urlStr, int readTimeoutMs) throws Exception {
        // Kept for the JVM-host case; on ART it is a documented no-op. Harmless and cheap.
        colgramDisableIpv6IfUnroutable();

        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;

        java.util.List<java.net.Proxy> rungs = transportLadder(host, port);

        Throwable last = null;
        boolean directFailed = false;
        java.net.Proxy front = ColgramEndpoints.frontProxy();
        int connectTimeoutMs = Math.min(COLGRAM_CONNECT_TIMEOUT_MS, 4000);
        for (java.net.Proxy rung : withBestRungRetry(rungs)) {
            try {
                HttpURLConnection conn = rung == null || java.net.Proxy.NO_PROXY.equals(rung)
                        ? (HttpURLConnection) url.openConnection()
                        : (HttpURLConnection) url.openConnection(rung);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.connect();
                lastWorkingBotRung = rung == null ? java.net.Proxy.NO_PROXY : rung;
                lastWorkingBotRungAtMs = System.currentTimeMillis();
                if (directFailed && rung != null && !java.net.Proxy.NO_PROXY.equals(rung)
                        && (front == null || !front.equals(rung))) {
                    colgramReportDirectRouteFailed();
                }
                return conn;
            } catch (Throwable t) {
                last = t;
                if (rung == null || java.net.Proxy.NO_PROXY.equals(rung)
                        || (front != null && front.equals(rung))) {
                    directFailed = true;
                }
                if (rung != null && rung.equals(lastWorkingBotRung)) {
                    lastWorkingBotRung = null;
                }
                if (t instanceof javax.net.ssl.SSLException && isUnknownCert((javax.net.ssl.SSLException) t)) {
                    ColgramEndpoints.parkCurrent(host, port);
                }
            }
        }
        if (directFailed && colgramShouldTryDirect()) {
            colgramReportDirectRouteFailed();
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
     * The ladder with its best rung tried once more at the end.
     *
     * The address the Bot API answers on here is lossy rather than down: measured, TCP completes
     * and the TLS stream is then cut on roughly one attempt in three, while the resolver's answer
     * is dropped outright. So walking straight past the front into a rung that cannot work, and
     * reporting a failure, is the wrong order of operations - one more attempt at the rung that
     * nearly worked beats every remaining rung put together.
     */
    private static java.util.List<java.net.Proxy> withBestRungRetry(
            java.util.List<java.net.Proxy> rungs) {
        if (rungs.isEmpty()) return rungs;
        java.util.List<java.net.Proxy> out = new java.util.ArrayList<>(rungs);
        out.add(rungs.get(0));
        return out;
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
        java.net.Proxy cached = lastWorkingBotRung;
        boolean strictOrMaxAnon = ColgramConfig.isStrictProxySecurityEnabled() || ColgramConfig.isMaxAnonymityEnabled();
        boolean preferProxyFirst = strictOrMaxAnon || ColgramDpiBypass.isBound()
                || ColgramProxyManager.hasActiveRouteForUi();
        boolean cachedFresh = cached != null && (System.currentTimeMillis() - lastWorkingBotRungAtMs) < BOT_RUNG_CACHE_TTL_MS;
        if (cachedFresh && (!preferProxyFirst || !java.net.Proxy.NO_PROXY.equals(cached))) {
            addRung(rungs, cached);
        }
        if (preferProxyFirst) {
            if (ColgramDpiBypass.isBound()) {
                addRung(rungs, new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", ColgramDpiBypass.activePort())));
            }
            addRung(rungs, ColgramHttp.pickRelayProxy());
            java.net.Proxy front = ColgramEndpoints.frontProxy();
            if (front != null && ColgramEndpoints.select(host, port) != null) {
                addRung(rungs, front);
            }
            if (!ColgramConfig.isMaxAnonymityEnabled() && colgramShouldTryDirect()) {
                int ipv4Port = colgramIpv4ProxyPort();
                if (ipv4Port > 0) {
                    addRung(rungs, new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress("127.0.0.1", ipv4Port)));
                }
                if (!strictOrMaxAnon || rungs.isEmpty()) {
                    addRung(rungs, java.net.Proxy.NO_PROXY);
                }
            }
        } else {
            boolean tryDirect = colgramShouldTryDirect();
            if (tryDirect) {
                java.net.Proxy front = ColgramEndpoints.frontProxy();
                if (front != null && ColgramEndpoints.select(host, port) != null) {
                    addRung(rungs, front);
                }
                int ipv4Port = colgramIpv4ProxyPort();
                if (ipv4Port > 0) {
                    addRung(rungs, new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress("127.0.0.1", ipv4Port)));
                }
                addRung(rungs, java.net.Proxy.NO_PROXY);
            }
            if (ColgramDpiBypass.isBound()) {
                addRung(rungs, new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", ColgramDpiBypass.activePort())));
            }
            addRung(rungs, ColgramHttp.pickRelayProxy());
            if (!tryDirect) {
                addRung(rungs, java.net.Proxy.NO_PROXY);
            }
        }
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
     *
     * The prefix is a RANGE, not a string shape: a supergroup id is
     * chatId <= -1000000000000. A string check on "100" also matched legacy group ids
     * like -1001234567, which routed them into the channel branch where
     * channelIdFromBotApi produced a negative channel id and a positive dialog id that
     * matches no peer — that phantom dialog row then loaded forever without ever
     * resolving, exactly the skeleton row in the chat list.
     */
    private static boolean isSupergroupOrChannel(long botApiChatId) {
        return botApiChatId <= -1000000000000L;
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
                String chatType = source != null ? source.optString("type", "") : "";
                boolean isBroadcastChannel = "channel".equalsIgnoreCase(chatType);
                try { channelClass.getField("megagroup").setBoolean(chat, !isBroadcastChannel); } catch (Throwable ignored) {}
                try { channelClass.getField("broadcast").setBoolean(chat, isBroadcastChannel); } catch (Throwable ignored) {}
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

            try {
                if (Class.forName("org.telegram.tgnet.TLRPC$User").getField("photo").get(user) == null) {
                    requestBotAvatarAsync(context, account, botId, false);
                }
            } catch (Throwable ignored) {}

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
        hydrateExistingBotPhotosOnceAsync(context.getApplicationContext(), account);
        if (isMtprotoBotAccount(account) && !isPassiveBotMode(context, account)) {
            ensureMtprotoBotLoop(context.getApplicationContext(), account);
            if (getBotToken(context, account).isEmpty()) {
                return;
            }
        }

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
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
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
                        int pollTimeoutSec = ColgramUiBridge.isAppInForeground() ? 35 : 50;
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
                            urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&timeout=0&allowed_updates=%5B%22message%22%2C%22edited_message%22%2C%22channel_post%22%2C%22edited_channel_post%22%2C%22message_reaction%22%2C%22callback_query%22%5D";
                        } else {
                            // Adaptive long-poll: 35s interactive, 50s screen-off to cut radio wakeups.
                            urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&offset=" + currentOffset + "&timeout=" + pollTimeoutSec;
                        }

                        HttpURLConnection conn = openConnection(urlStr, currentOffset == 0 ? 10000 : ((pollTimeoutSec + 8) * 1000));
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

                        Thread.sleep(ColgramUiBridge.isAppInForeground() ? 300L : 1500L);

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
        mtprotoBotLoops.remove(account);
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
        if (activity == null || activity.isFinishing()) return;
        String existing = getBotToken(activity, account);
        showThemedInputDialog(
                activity,
                "Токен бота (@BotFather)",
                "Введите токен бота из @BotFather для синхронизации диалогов и сообщений. Бот видит только чаты, в которых ему писали или куда он добавлен.",
                "123456789:ABCdef...",
                existing,
                true,
                "Синхронизировать",
                "Отмена",
                token -> {
                    if (token.isEmpty()) {
                        Toast.makeText(activity, "Токен пустой", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    saveBotToken(activity, account, token);
                    syncBotDialogs(activity, account, true);
                    return true;
                }
        );
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
        if (isMtprotoBotAccount(account) && !isPassiveBotMode(context, account)) {
            ensureMtprotoBotLoop(context.getApplicationContext(), account);
            if (userInitiated) refreshMtprotoBotDialogs(context.getApplicationContext(), account);
            if (getBotToken(context, account).isEmpty()) {
                if (userInitiated) {
                    Toast.makeText(context, "Синхронизирую чаты по MTProto", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
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
            final String botToken = getBotToken(context, account);

            // Incoming messages that plugins should see. Collected during the parse loop and
            // dispatched only after the batch is actually in storage, so a plugin replying to
            // message N cannot observe a dialog state that the same batch has not written yet.
            final ArrayList<Object[]> pluginInbound = new ArrayList<>();
            final java.util.LinkedHashSet<Long> batchCustomEmojiIds = new java.util.LinkedHashSet<>();
            final java.util.HashSet<String> editedBatchKeys = new java.util.HashSet<>();

            for (int i = 0; i < updates.length(); i++) {
                JSONObject upd = updates.getJSONObject(i);
                boolean isEditedUpdate = upd.has("edited_message") || upd.has("edited_channel_post");
                boolean isChannelPostUpdate = upd.has("channel_post") || upd.has("edited_channel_post");
                JSONObject msgObj = upd.optJSONObject("message");
                if (msgObj == null) msgObj = upd.optJSONObject("edited_message");
                if (msgObj == null) msgObj = upd.optJSONObject("channel_post");
                if (msgObj == null) msgObj = upd.optJSONObject("edited_channel_post");
                if (msgObj == null) continue;

                JSONObject fromObj = msgObj.optJSONObject("from");
                JSONObject chatObj = msgObj.optJSONObject("chat");

                long fromId = fromObj != null ? fromObj.optLong("id", 0) : 0;
                long chatId = chatObj != null ? chatObj.optLong("id", 0) : fromId;
                if (chatId == 0) continue;

                String firstName = fromObj != null ? fromObj.optString("first_name", "User") : "Chat";
                String lastName = fromObj != null ? fromObj.optString("last_name", "") : "";
                String username = fromObj != null ? fromObj.optString("username", "") : "";

                String rawCaption = msgObj.optString("caption", "");
                String text = msgObj.optString("text", "");
                if (text.isEmpty() && !rawCaption.isEmpty()) {
                    text = rawCaption;
                }
                boolean hasAttachableMedia = msgObj.has("sticker") || msgObj.has("photo")
                        || msgObj.has("video") || msgObj.has("video_note") || msgObj.has("animation")
                        || msgObj.has("document") || msgObj.has("voice") || msgObj.has("audio");
                if (text.isEmpty() && !hasAttachableMedia) {
                    text = "[Сообщение]";
                }

                int date = msgObj.optInt("date", (int) (System.currentTimeMillis() / 1000));
                int editDate = msgObj.optInt("edit_date", 0);
                int msgId = msgObj.optInt("message_id", 1);

                long resolvedDialogId;
                if (isSupergroupOrChannel(chatId)) {
                    resolvedDialogId = -channelIdFromBotApi(chatId);
                } else if (chatId < 0) {
                    resolvedDialogId = chatId;
                } else {
                    resolvedDialogId = chatId;
                }

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

                // Preserve an existing Telegram User object (avatar/access hash/self flags).
                // Bot API chat and sender snapshots are partial; replacing a cached user with
                // one of these photo-less objects made peers render as “Deleted Account” until
                // the next full MTProto refresh.
                ensureBotApiUser(mc, mcClass, userClass, userStatusClass, usersList,
                        processedUserIds, fromId, fromObj,
                        fromObj != null && fromObj.optBoolean("is_bot", false), fromId == botSelfId);

                // In a private outgoing update, `from` is the bot, while `chat` is the human
                // recipient. Creating only `from` leaves the dialog's peer_user missing, so the
                // chat row falls back to a deleted/unknown account. Cache that peer separately.
                if (chatObj != null && "private".equals(chatObj.optString("type")) && chatId != fromId) {
                    ensureBotApiUser(mc, mcClass, userClass, userStatusClass, usersList,
                            processedUserIds, chatId, chatObj, false, chatId == botSelfId);
                }

                // Create TLRPC.TL_message
                Object message = messageClass.getConstructor().newInstance();
                messageClass.getField("id").setInt(message, msgId);
                messageClass.getField("date").setInt(message, date);
                if (editDate > 0) {
                    try {
                        messageClass.getField("edit_date").setInt(message, editDate);
                        int fl = messageClass.getField("flags").getInt(message);
                        messageClass.getField("flags").setInt(message, fl | 0x00008000);
                    } catch (Throwable ignored) {}
                }
                messageClass.getField("message").set(message, text);
                JSONArray rawEntities = msgObj.optJSONArray("entities");
                if (rawEntities == null || rawEntities.length() == 0) {
                    rawEntities = msgObj.optJSONArray("caption_entities");
                }
                String newEntitiesJson = null;
                if (rawEntities != null && rawEntities.length() > 0) {
                    ArrayList<Object> parsedEntities = parseBotEntitiesToTl(rawEntities, batchCustomEmojiIds);
                    if (!parsedEntities.isEmpty()) {
                        try {
                            messageClass.getField("entities").set(message, parsedEntities);
                            int fl = messageClass.getField("flags").getInt(message);
                            messageClass.getField("flags").setInt(message, fl | 0x00000080); // MESSAGE_FLAG_HAS_ENTITIES
                        } catch (Throwable ignored) {}
                    }
                    newEntitiesJson = rawEntities.toString();
                }
                boolean mediaAttached = false;
                if (botToken != null && !botToken.isEmpty()) {
                    if (msgObj.has("sticker")) {
                        if (attachBotStickerMedia(context, account, botToken, msgObj.optJSONObject("sticker"), message, messageClass, date)) {
                            text = "";
                            mediaAttached = true;
                        }
                    } else if (msgObj.has("photo")) {
                        if (attachBotPhotoMedia(context, account, botToken, resolvedDialogId, msgId, isEditedUpdate,
                                msgObj.optJSONArray("photo"), rawCaption, message, messageClass, date)) {
                            mediaAttached = true;
                        }
                    } else if (msgObj.has("video") || msgObj.has("video_note") || msgObj.has("animation")) {
                        boolean isRoundNote = msgObj.has("video_note");
                        JSONObject vidObj = isRoundNote ? msgObj.optJSONObject("video_note")
                                : (msgObj.has("video") ? msgObj.optJSONObject("video") : msgObj.optJSONObject("animation"));
                        if (attachBotVideoMedia(context, account, botToken, resolvedDialogId, msgId, isEditedUpdate,
                                vidObj, msgObj.has("animation"), isRoundNote, rawCaption, message, messageClass, date)) {
                            mediaAttached = true;
                        }
                    } else if (msgObj.has("document") || msgObj.has("voice") || msgObj.has("audio")) {
                        boolean isVoice = msgObj.has("voice");
                        boolean isAudio = msgObj.has("audio");
                        JSONObject docObj = msgObj.has("document") ? msgObj.optJSONObject("document")
                                : (isVoice ? msgObj.optJSONObject("voice") : msgObj.optJSONObject("audio"));
                        if (attachBotDocumentMedia(context, account, botToken, resolvedDialogId, msgId, isEditedUpdate,
                                docObj, isVoice, isAudio, rawCaption, message, messageClass, date)) {
                            mediaAttached = true;
                        }
                    }
                }
                if (!mediaAttached && text.isEmpty()) {
                    if (msgObj.has("photo")) text = "🖼 Фотография";
                    else if (msgObj.has("video_note")) text = "🎥 Видеосообщение";
                    else if (msgObj.has("video") || msgObj.has("animation")) text = "📹 Видео";
                    else if (msgObj.has("document")) text = "📎 Документ";
                    else if (msgObj.has("voice")) text = "🎤 Голосовое сообщение";
                    else if (msgObj.has("audio")) text = "🎵 Аудиозапись";
                    else if (msgObj.has("sticker")) text = "🎨 Стикер";
                    messageClass.getField("message").set(message, text);
                }

                // Track live snapshots & capture rapid (<1s) text/caption + entity edits from Bot API updates
                if (isEditedUpdate) {
                    String fallbackStoredText = null;
                    String fallbackStoredEntities = null;
                    if (!ColgramHookHandler.hasLiveMessageSnapshot(resolvedDialogId, msgId)) {
                        try {
                            Class<?> msStorageCls = Class.forName("org.telegram.messenger.MessagesStorage");
                            Object msInst = msStorageCls.getMethod("getInstance", int.class).invoke(null, account);
                            if (msInst != null) {
                                Object storedMsg = msStorageCls.getMethod("getMessage", long.class, int.class)
                                        .invoke(msInst, resolvedDialogId, msgId);
                                if (storedMsg != null) {
                                    Object rawPrev = storedMsg.getClass().getField("message").get(storedMsg);
                                    if (rawPrev instanceof String) fallbackStoredText = (String) rawPrev;
                                    try {
                                        Object prevEnts = storedMsg.getClass().getField("entities").get(storedMsg);
                                        if (prevEnts instanceof java.util.List) {
                                            fallbackStoredEntities = serializeTlEntitiesToJsonString((java.util.List<?>) prevEnts);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    ColgramHookHandler.hookOnMessageEditedWithNew(
                            resolvedDialogId, msgId, fallbackStoredText, fallbackStoredEntities,
                            text, newEntitiesJson, System.currentTimeMillis());
                } else if (!text.isEmpty()) {
                    ColgramHookHandler.recordLiveMessageSnapshot(resolvedDialogId, msgId, text, newEntitiesJson);
                }
                try {
                    // The bot is "self" in this session, so anything it sent (or posted to a channel it admins) is outgoing.
                    boolean isOutMsg = (botSelfId != 0 && fromId == botSelfId) || isChannelPostUpdate;
                    messageClass.getField("out").setBoolean(message, isOutMsg);
                    if (isChannelPostUpdate) {
                        messageClass.getField("post").setBoolean(message, true);
                    }
                } catch (Throwable ignored) {}
                if (isEditedUpdate) {
                    editedBatchKeys.add(resolvedDialogId + ":" + msgId);
                }

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

                try {
                    messageClass.getField("dialog_id").setLong(message, resolvedDialogId);
                } catch (Throwable ignored) {}

                if (fromId != 0) {
                    Object peerFrom = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peerFrom, fromId);
                    messageClass.getField("from_id").set(message, peerFrom);
                }

                messagesList.add(message);

                // Only fresh, non-edited inbound messages NOT sent by this bot (and not Saved Messages)
                // enter the plugin/auto-reply hook, always keyed by resolvedDialogId (never raw -100 chatId).
                boolean colgramIsIncoming = (botSelfId == 0 || fromId != botSelfId) && !isChannelPostUpdate;
                long nowSec = System.currentTimeMillis() / 1000L;
                if (colgramIsIncoming && !isEditedUpdate && !text.isEmpty()
                        && resolvedDialogId != 0L && (botSelfId == 0L || resolvedDialogId != botSelfId)
                        && date >= nowSec - 120L) {
                    pluginInbound.add(new Object[]{
                            Long.valueOf(resolvedDialogId),
                            Integer.valueOf(msgId),
                            text,
                            Long.valueOf(fromId)
                    });
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
            if (!batchCustomEmojiIds.isEmpty()) {
                ArrayList<Long> prefetchIds = new ArrayList<>(batchCustomEmojiIds);
                fetchCustomEmojiForBot(context, account, prefetchIds, loadedDocs -> {
                    if (loadedDocs != null && !loadedDocs.isEmpty()) {
                        postDebouncedAvatarRefresh(account);
                    }
                });
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
            final java.util.List<Long> finalUserPeersToHydrate = new ArrayList<>(processedUserIds);
            final java.util.Map<Long, Long> finalGroupPeersToHydrate = new java.util.HashMap<>();
            for (Long cid : topMessageMap.keySet()) {
                if (cid != null && cid < 0) {
                    long tlDid = isSupergroupOrChannel(cid) ? -channelIdFromBotApi(cid) : cid;
                    finalGroupPeersToHydrate.put(tlDid, cid);
                }
            }
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

                    // Phantom dialog cleanup.
                    //
                    // Earlier builds wrote dialogs under peers that do not exist (a supergroup
                    // id sent to TL_peerChat, a legacy group id misread as a channel, a raw
                    // -100 value). putDialogs(..., check=1) never repairs an existing row, so
                    // every such row loaded forever as a skeleton row with no resolvable peer.
                    // On a bot account dialogs only ever come from our own Bot API sync, so a
                    // dialog whose peer is unknown AFTER the users/chats above were registered
                    // cannot render anything and is garbage. Drop it from the live cache and
                    // from the dialogs table, once per process.
                    try {
                        if (!phantomCleanupDone.compareAndSet(false, true)) {
                            // already ran in this process
                        } else {
                            Method sizeM = dict.getClass().getMethod("size");
                            Method keyAtM = dict.getClass().getMethod("keyAt", int.class);
                            Method removeM = dict.getClass().getMethod("remove", long.class);
                            Method getUserM = mcClass.getMethod("getUser", Long.class);
                            Method getChatM = mcClass.getMethod("getChat", Long.class);

                            java.util.List<Long> doomed = new java.util.ArrayList<>();
                            int n = (Integer) sizeM.invoke(dict);
                            for (int i = 0; i < n; i++) {
                                long did = (Long) keyAtM.invoke(dict, i);
                                // Encrypted and folder dialogs use high-bit ids that are not
                                // user/chat shaped; leave them alone no matter what.
                                if ((did & 0x4000000000000000L) != 0 || (did & 0x2000000000000000L) != 0) {
                                    continue;
                                }
                                boolean bogus;
                                if (did > 0) {
                                    // Private chat: the dialog id IS the user id.
                                    bogus = getUserM.invoke(mc, did) == null;
                                } else {
                                    // DialogObject.getDialogId(Chat) = -chat.id, so the chat
                                    // cache key for every negative dialog id is -did.
                                    bogus = getChatM.invoke(mc, -did) == null;
                                }
                                if (bogus) doomed.add(did);
                            }
                            if (!doomed.isEmpty()) {
                                Object dbObj = msClass.getMethod("getDatabase").invoke(ms);
                                Method execFast = dbObj.getClass().getMethod("executeFast", String.class);
                                for (long did : doomed) {
                                    removeM.invoke(dict, did);
                                    msgs.getClass().getMethod("remove", long.class).invoke(msgs, did);
                                    try {
                                        Object stmt = execFast.invoke(dbObj, "DELETE FROM dialogs WHERE uid=" + did);
                                        stmt.getClass().getMethod("stepThis").invoke(stmt);
                                        stmt.getClass().getMethod("dispose").invoke(stmt);
                                    } catch (Throwable sqlIgnore) {}
                                }
                                Log.i(TAG, "removed " + doomed.size() + " phantom dialog row(s) from the bot account");
                            }
                        }
                    } catch (Throwable cleanup) {
                        Log.w(TAG, "phantom dialog cleanup skipped: " + cleanup);
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
            // Keep the O(dialogs x messages) scan and reflection work on the worker, but do
            // not let loadDialogs race ahead and reload stale in-memory dialog maps. The UI
            // refresh is enqueued only after this cache seed has finished.
            ColgramDialogRefreshSequencer.seedThenRefresh(
                    executor,
                    command -> mainHandler.post(command),
                    seedCache,
                    () -> {
                try {
                    Class<?> colgramDialogBaseClass = Class.forName("org.telegram.tgnet.TLRPC$Dialog");
                    @SuppressWarnings("unchecked")
                    java.util.ArrayList<Object> mcDialogs = (java.util.ArrayList<Object>) mcClass.getField("dialogs").get(mc);
                    @SuppressWarnings("unchecked")
                    java.util.ArrayList<Object> mcAllDialogs = (java.util.ArrayList<Object>) mcClass.getField("allDialogs").get(mc);
                    java.util.HashSet<Long> existingIds = new java.util.HashSet<>();
                    if (mcDialogs != null) {
                        for (Object existing : mcDialogs) {
                            existingIds.add(colgramDialogBaseClass.getField("id").getLong(existing));
                        }
                    }
                    boolean listChanged = false;
                    for (Object dlg : finalDialogsList) {
                        long did = colgramDialogBaseClass.getField("id").getLong(dlg);
                        if (!existingIds.contains(did)) {
                            if (mcDialogs != null) mcDialogs.add(dlg);
                            if (mcAllDialogs != null && !mcAllDialogs.contains(dlg)) mcAllDialogs.add(dlg);
                            existingIds.add(did);
                            listChanged = true;
                        } else if (mcDialogs != null) {
                            int topMid = colgramDialogBaseClass.getField("top_message").getInt(dlg);
                            int lastDate = colgramDialogBaseClass.getField("last_message_date").getInt(dlg);
                            for (Object existing : mcDialogs) {
                                if (colgramDialogBaseClass.getField("id").getLong(existing) == did) {
                                    if (topMid > colgramDialogBaseClass.getField("top_message").getInt(existing)) {
                                        colgramDialogBaseClass.getField("top_message").setInt(existing, topMid);
                                        colgramDialogBaseClass.getField("last_message_date").setInt(existing, lastDate);
                                        listChanged = true;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    if (listChanged) {
                        try {
                            for (Method m : mcClass.getMethods()) {
                                if ("sortDialogs".equals(m.getName()) && m.getParameterTypes().length == 1) {
                                    m.invoke(mc, new Object[]{null});
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                    Method getInst = ncClass.getMethod("getInstance", int.class);
                    Object nc = getInst.invoke(null, account);
                    Method postNotification = ncClass.getMethod("postNotificationName", int.class, Object[].class);

                    // Deliver new messages via didReceiveNewMessages AND edited messages live via replaceMessagesObjects.
                    if (finalMessagesList != null && !finalMessagesList.isEmpty()) {
                        try {
                            Class<?> liveMsgObjCls = Class.forName("org.telegram.messenger.MessageObject");
                            int didReceiveNewMessages = ncClass.getField("didReceiveNewMessages").getInt(null);
                            int replaceMessagesObjects = ncClass.getField("replaceMessagesObjects").getInt(null);
                            java.util.LinkedHashMap<Long, java.util.ArrayList<Object>> perDialogLive = new java.util.LinkedHashMap<>();
                            java.util.LinkedHashMap<Long, java.util.ArrayList<Object>> perDialogEdited = new java.util.LinkedHashMap<>();
                            if (dispatchedLiveMsgKeys.size() > 4000) {
                                dispatchedLiveMsgKeys.clear();
                            }
                            for (Object msgItem : finalMessagesList) {
                                long did = peerIdOf(messageClass, peerChannelClass, peerChatClass, peerUserClass, msgItem);
                                int mid = messageClass.getField("id").getInt(msgItem);
                                if (did == 0 || mid == 0) continue;
                                boolean isEditedItem = editedBatchKeys.contains(did + ":" + mid);
                                String liveKey = account + ":" + did + ":" + mid;
                                if (!isEditedItem && !dispatchedLiveMsgKeys.add(liveKey)) continue;
                                Object liveMo = null;
                                try {
                                    liveMo = liveMsgObjCls.getConstructor(int.class, messageClass, boolean.class, boolean.class)
                                            .newInstance(account, msgItem, true, isEditedItem);
                                } catch (NoSuchMethodException nsme) {
                                    liveMo = liveMsgObjCls.getConstructor(int.class, messageClass, liveMsgObjCls, boolean.class, boolean.class)
                                            .newInstance(account, msgItem, null, true, isEditedItem);
                                }
                                if (liveMo != null) {
                                    java.util.LinkedHashMap<Long, java.util.ArrayList<Object>> targetMap = isEditedItem ? perDialogEdited : perDialogLive;
                                    java.util.ArrayList<Object> bucket = targetMap.get(did);
                                    if (bucket == null) {
                                        bucket = new java.util.ArrayList<>();
                                        targetMap.put(did, bucket);
                                    }
                                    bucket.add(liveMo);
                                }
                            }
                            for (java.util.Map.Entry<Long, java.util.ArrayList<Object>> liveEntry : perDialogLive.entrySet()) {
                                postNotification.invoke(nc, didReceiveNewMessages,
                                        new Object[]{liveEntry.getKey(), liveEntry.getValue(), Boolean.FALSE, Boolean.FALSE});
                            }
                            for (java.util.Map.Entry<Long, java.util.ArrayList<Object>> editEntry : perDialogEdited.entrySet()) {
                                postNotification.invoke(nc, replaceMessagesObjects,
                                        new Object[]{editEntry.getKey(), editEntry.getValue(), Boolean.FALSE});
                            }
                        } catch (Throwable liveErr) {
                            Log.w(TAG, "didReceiveNewMessages dispatch warning: " + liveErr.getMessage());
                        }
                    }

                    long nowUi = System.currentTimeMillis();
                    Long prevUi = lastBotUiNotifyAt.get(account);
                    if (listChanged || prevUi == null || nowUi - prevUi >= 400L) {
                        lastBotUiNotifyAt.put(account, nowUi);
                        int dialogsNeedReload = ncClass.getField("dialogsNeedReload").getInt(null);
                        postNotification.invoke(nc, dialogsNeedReload, new Object[0]);
                        if (!finalUsersList.isEmpty() || !finalChatsList.isEmpty()) {
                            try {
                                int updateInterfaces = ncClass.getField("updateInterfaces").getInt(null);
                                int mask = mcClass.getField("UPDATE_MASK_NAME").getInt(null)
                                        | mcClass.getField("UPDATE_MASK_AVATAR").getInt(null);
                                postNotification.invoke(nc, updateInterfaces, new Object[]{mask});
                            } catch (Throwable ignored) {}
                        }
                    }

                    for (Long uid : finalUserPeersToHydrate) {
                        if (uid != null && uid != 0) {
                            requestBotAvatarWithChatId(context, account, uid, uid, false);
                        }
                    }
                    for (java.util.Map.Entry<Long, Long> ge : finalGroupPeersToHydrate.entrySet()) {
                        requestBotAvatarWithChatId(context, account, ge.getKey(), ge.getValue(), true);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error notifying UI after bot sync", t);
                }
            });

            // Hand the batch's incoming messages to the plugin system now that they are stored
            // and the UI has been told about them — strictly pinned to this bot's `account`!
            for (Object[] entry : pluginInbound) {
                try {
                    ColgramHookHandler.hookOnMessageReceived(
                            account,
                            ((Long) entry[0]).longValue(),
                            ((Integer) entry[1]).intValue(),
                            (String) entry[2],
                            ((Long) entry[3]).longValue(),
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
     * Add the partial identity included in a Bot API update without erasing richer MTProto data.
     * Existing User objects carry photos, access hashes, self/deleted state, and flags that the
     * Bot API payload does not include. Reusing that instance keeps the cache and dialog rows
     * stable; a new peer is synthesized only when the update is the first source of its identity.
     */
    private static void ensureBotApiUser(Object mc, Class<?> mcClass, Class<?> userClass,
            Class<?> userStatusClass, ArrayList usersList, Set<Long> processedUserIds,
            long userId, JSONObject source, boolean isBot, boolean isSelf) throws Exception {
        if (userId == 0 || !processedUserIds.add(userId)) return;

        Object existing = mcClass.getMethod("getUser", Long.class)
                .invoke(mc, Long.valueOf(userId));
        Class<?> userBaseClass = Class.forName("org.telegram.tgnet.TLRPC$User");
        if (existing != null) {
            copyBotApiName(existing, userBaseClass, source, "first_name");
            copyBotApiName(existing, userBaseClass, source, "last_name");
            copyBotApiName(existing, userBaseClass, source, "username");
            if (isBot) setBotApiBoolean(existing, "bot", true);
            if (isSelf) setBotApiBoolean(existing, "self", true);
            setBotApiBoolean(existing, "deleted", false);
            // Keep the exact cached object: putUser() replaces entries and a Bot API snapshot
            // has no photo/access_hash with which to populate a replacement.
            usersList.add(existing);
            return;
        }

        Object user = userClass.getConstructor().newInstance();
        userBaseClass.getField("id").setLong(user, userId);
        copyBotApiName(user, userBaseClass, source, "first_name");
        copyBotApiName(user, userBaseClass, source, "last_name");
        copyBotApiName(user, userBaseClass, source, "username");
        userBaseClass.getField("phone").set(user, "");
        userBaseClass.getField("status").set(user, userStatusClass.getConstructor().newInstance());
        setBotApiBoolean(user, "bot", isBot);
        setBotApiBoolean(user, "self", isSelf);
        setBotApiBoolean(user, "deleted", false);
        usersList.add(user);
        mcClass.getMethod("putUser", userBaseClass, boolean.class).invoke(mc, user, false);
    }

    private static void copyBotApiName(Object user, Class<?> userBaseClass,
            JSONObject source, String field) throws Exception {
        if (source == null) return;
        String value = source.optString(field, "");
        if (!value.isEmpty()) userBaseClass.getField(field).set(user, value);
    }

    private static void setBotApiBoolean(Object user, String field, boolean value) {
        try {
            user.getClass().getField(field).setBoolean(user, value);
        } catch (Throwable ignored) {
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
        return botApiPost(token, method, payload, 15000);
    }

    private static JSONObject botApiPost(String token, String method, JSONObject payload, int readTimeoutMs) {
        final String urlStr = "https://api.telegram.org/bot" + token + "/" + method;
        // A POST cannot be probed the way a GET can: HttpURLConnection rejects setDoOutput() once
        // the socket has been dialled, so the transport ladder has to be walked here, around the
        // write, instead of inside openConnection(). Every Bot API method used through this is
        // idempotent (a name or description set to the same value, an update offset), so retrying
        // the next transport cannot double-apply anything.
        java.util.List<java.net.Proxy> rungs;
        String parsedHost = "api.telegram.org";
        int parsedPort = 443;
        try {
            URL parsed = new URL(urlStr);
            parsedHost = parsed.getHost();
            parsedPort = parsed.getPort() > 0 ? parsed.getPort() : 443;
            rungs = transportLadder(parsedHost, parsedPort);
        } catch (Throwable t) {
            rungs = new java.util.ArrayList<>();
            rungs.add(java.net.Proxy.NO_PROXY);
        }
        final String postHost = parsedHost;
        final int postPort = parsedPort;

        Throwable last = null;
        for (java.net.Proxy rung : withBestRungRetry(rungs)) {
            HttpURLConnection conn = null;
            try {
                conn = openVia(new URL(urlStr), rung, readTimeoutMs,
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
                // The address carried a full request/response exchange, which is the only proof a
                // TCP connect cannot give.
                ColgramEndpoints.noteSuccess(postHost, postPort);
                return new JSONObject(sb.toString());
            } catch (javax.net.ssl.SSLException se) {
                last = se;
                Log.w(TAG, method + " TLS failed via " + rung + ": " + se.getMessage());
                if (isUnknownCert(se)) {
                    // The address answered TCP but presented a certificate for something else -
                    // Telegram's web fronts do exactly this. Without parking it, every retry dials
                    // the same wrong host and the call never recovers.
                    ColgramEndpoints.parkCurrent(postHost, postPort);
                }
            } catch (Throwable t) {
                last = t;
                Log.w(TAG, method + " failed via " + rung + ": " + t.getMessage());
                ColgramEndpoints.noteFailure(postHost, postPort);
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
                    + ColgramDpiBypass.activePort() + " не отвечает)";
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

    /**
     * True when TLS reached a server whose certificate this hostname cannot use - a wrong-address
     * symptom, not a network one, and the only kind of failure worth re-picking an address for.
     */
    private static boolean isUnknownCert(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof java.security.cert.CertificateException) return true;
            String m = c.getMessage();
            if (m != null && (m.contains("Trust anchor") || m.contains("CertPathValidator"))) return true;
        }
        return false;
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

    interface ThemedInputSubmit {
        boolean onSubmit(String value);
    }

    /**
     * Renders a theme-native Colgram modal dialog (matching Telegram's "Новый контакт" sheet style:
     * dark/light/Cyber surface, rounded outlined field with focus accent, inline Paste action, and
     * full-width pill CTA). Never falls back to stock white android.app.AlertDialog.
     */
    static void showThemedInputDialog(
            final Context context,
            final String title,
            final String subtitle,
            final String hint,
            final String initialValue,
            final boolean monospace,
            final String primaryLabel,
            final String cancelLabel,
            final ThemedInputSubmit onSubmit
    ) {
        if (context == null) return;
        if (context instanceof Activity && ((Activity) context).isFinishing()) return;

        float density = context.getResources() != null ? context.getResources().getDisplayMetrics().density : 2.5f;
        int dp4 = Math.round(4f * density);
        int dp8 = Math.round(8f * density);
        int dp10 = Math.round(10f * density);
        int dp12 = Math.round(12f * density);
        int dp14 = Math.round(14f * density);
        int dp16 = Math.round(16f * density);
        int dp20 = Math.round(20f * density);
        int dp22 = Math.round(22f * density);
        int dp24 = Math.round(24f * density);
        int dp48 = Math.round(48f * density);

        Class<?> themeClass = null;
        boolean darkTheme = true;
        try {
            themeClass = Class.forName("org.telegram.ui.ActionBar.Theme");
            darkTheme = (Boolean) themeClass.getMethod("isCurrentThemeDark").invoke(null);
        } catch (Throwable ignored) {}
        boolean cyberTheme = darkTheme && ColgramConfig.isCyberThemeEnabled();

        int surfaceColor = getThemeColor(themeClass, "key_dialogBackground",
                cyberTheme ? 0xFF141519 : (darkTheme ? 0xFF1B1F27 : 0xFFFFFFFF));
        int textColor = getThemeColor(themeClass, "key_dialogTextBlack",
                darkTheme ? 0xFFF2F4F8 : 0xFF111827);
        int grayColor = getThemeColor(themeClass, "key_dialogTextGray",
                darkTheme ? 0xFF94A0B4 : 0xFF64748B);
        int hintColor = getThemeColor(themeClass, "key_dialogTextHint",
                darkTheme ? 0xFF647082 : 0xFF94A3B8);
        int accentColor = cyberTheme
                ? 0xFFE11D48
                : getThemeColor(themeClass, "key_featuredStickers_addButton", 0xFF2AABEE);
        int fieldFill = darkTheme ? (cyberTheme ? 0xFF1C1D24 : 0xFF232833) : 0xFFF3F6FA;
        int fieldBorderIdle = darkTheme ? 0x26FFFFFF : 0x220F172A;
        int fieldBorderFocus = accentColor;
        int strokePx = Math.max(1, Math.round(1.25f * density));

        Typeface boldFace = Typeface.DEFAULT_BOLD;
        try {
            Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
            Object tf = auClass.getMethod("bold").invoke(null);
            if (tf instanceof Typeface) boldFace = (Typeface) tf;
        } catch (Throwable ignored) {}

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp22, dp22, dp22, dp14);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19.5f);
        titleView.setTypeface(boldFace);
        container.addView(titleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView descView = new TextView(context);
            descView.setText(subtitle);
            descView.setTextColor(grayColor);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            descView.setLineSpacing(dp4 * 0.6f, 1.0f);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp8;
            descLp.bottomMargin = dp16;
            container.addView(descView, descLp);
        }

        final GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp12);
        cardBg.setColor(fieldFill);
        cardBg.setStroke(strokePx, fieldBorderFocus);

        LinearLayout inputCard = new LinearLayout(context);
        inputCard.setOrientation(LinearLayout.HORIZONTAL);
        inputCard.setGravity(Gravity.CENTER_VERTICAL);
        inputCard.setPadding(dp14, dp10, dp10, dp10);
        inputCard.setBackground(cardBg);

        final EditText input = new EditText(context);
        input.setHint(hint);
        input.setHintTextColor(hintColor);
        input.setTextColor(textColor);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, monospace ? 13.5f : 15f);
        if (monospace) {
            input.setTypeface(Typeface.MONOSPACE);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        input.setBackground(null);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(0, dp4, dp8, dp4);
        if (initialValue != null && !initialValue.isEmpty()) {
            input.setText(initialValue);
            input.setSelection(initialValue.length());
        }
        input.setOnFocusChangeListener((v, hasFocus) ->
                cardBg.setStroke(strokePx, hasFocus ? fieldBorderFocus : fieldBorderIdle));
        inputCard.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pasteBtn = new TextView(context);
        pasteBtn.setText("Вставить");
        pasteBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        pasteBtn.setTextColor(accentColor);
        pasteBtn.setTypeface(boldFace);
        pasteBtn.setPadding(dp10, dp8, dp10, dp8);
        GradientDrawable pasteBg = new GradientDrawable();
        pasteBg.setCornerRadius(dp8);
        pasteBg.setColor((accentColor & 0x00FFFFFF) | 0x1E000000);
        pasteBtn.setBackground(pasteBg);
        pasteBtn.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                        String pasted = clip.getItemAt(0).getText().toString().trim();
                        input.setText(pasted);
                        input.setSelection(pasted.length());
                    }
                }
            } catch (Throwable ignored) {}
        });
        inputCard.addView(pasteBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams inputCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputCardLp.bottomMargin = dp20;
        container.addView(inputCard, inputCardLp);

        final Dialog[] dialogRef = new Dialog[1];
        final Runnable triggerSubmit = () -> {
            String value = input.getText() != null ? input.getText().toString().trim() : "";
            if (onSubmit == null || onSubmit.onSubmit(value)) {
                if (dialogRef[0] != null) {
                    try {
                        dialogRef[0].dismiss();
                    } catch (Throwable ignored) {}
                }
            }
        };

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                triggerSubmit.run();
                return true;
            }
            return false;
        });

        TextView primaryBtn = new TextView(context);
        primaryBtn.setText(primaryLabel);
        primaryBtn.setTextColor(Color.WHITE);
        primaryBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        primaryBtn.setTypeface(boldFace);
        primaryBtn.setGravity(Gravity.CENTER);
        primaryBtn.setMinimumHeight(dp48);
        GradientDrawable primaryBg = new GradientDrawable();
        primaryBg.setCornerRadius(dp24);
        primaryBg.setColor(accentColor);
        primaryBtn.setBackground(primaryBg);
        primaryBtn.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).alpha(0.88f).setDuration(90).start();
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(130).start();
            }
            return false;
        });
        primaryBtn.setOnClickListener(v -> triggerSubmit.run());
        container.addView(primaryBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp48));

        TextView cancelBtn = new TextView(context);
        cancelBtn.setText(cancelLabel);
        cancelBtn.setTextColor(grayColor);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        cancelBtn.setTypeface(boldFace);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(dp16, dp12, dp16, dp8);
        cancelBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) {
                try {
                    dialogRef[0].dismiss();
                } catch (Throwable ignored) {}
            }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelLp.topMargin = dp4;
        container.addView(cancelBtn, cancelLp);

        try {
            Class<?> alertBuilderClass = Class.forName("org.telegram.ui.ActionBar.AlertDialog$Builder");
            Object builder = alertBuilderClass.getConstructor(Context.class).newInstance(context);
            alertBuilderClass.getMethod("setView", View.class).invoke(builder, container);
            Object shown = alertBuilderClass.getMethod("show").invoke(builder);
            if (shown instanceof Dialog) {
                dialogRef[0] = (Dialog) shown;
            }
            input.post(() -> {
                try {
                    input.requestFocus();
                } catch (Throwable ignored) {}
            });
            return;
        } catch (Throwable t) {
            Log.w(TAG, "Telegram AlertDialog unavailable, using themed custom Dialog", t);
        }

        // Theme-matched fallback Dialog (NEVER stock white android.app.AlertDialog!)
        Dialog fallback = new Dialog(context);
        fallback.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GradientDrawable cardSurface = new GradientDrawable();
        cardSurface.setColor(surfaceColor);
        cardSurface.setCornerRadius(dp20);
        cardSurface.setStroke(strokePx, darkTheme ? 0x1FFFFFFF : 0x140F172A);
        container.setBackground(cardSurface);
        fallback.setContentView(container);
        Window win = fallback.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int widthPx = Math.min(Math.round(380f * density),
                    Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.90f));
            win.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialogRef[0] = fallback;
        fallback.show();
    }

    public static void showStartChatDialog(final Activity activity, final int account, final Object fragmentObj) {
        if (activity == null || activity.isFinishing()) return;

        boolean isRu = true;
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
        showThemedInputDialog(
                activity,
                isRussian ? "Написать пользователю" : "Message User",
                isRussian
                        ? "Введите @username или числовой ID, чтобы открыть диалог от имени бота"
                        : "Enter @username or numerical User ID to open a chat as bot",
                isRussian ? "@username или 123456789" : "@username or 123456789",
                "",
                false,
                isRussian ? "Открыть чат" : "Open Chat",
                isRussian ? "Отмена" : "Cancel",
                query -> {
                    if (query.isEmpty()) {
                        Toast.makeText(activity,
                                isRussian ? "Введите @username или User ID" : "Enter @username or User ID",
                                Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    openChatAsBot(activity, account, fragmentObj, query);
                    return true;
                }
        );
    }

    private static final ConcurrentHashMap<Integer, Long> lastBotUiNotifyAt = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> botAvatarAttemptedAt = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> botCacheDirs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> botAvatarFileCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> botAvatarMissingAt = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> botAvatarUiPostedAt = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Boolean> botAvatarUiPending = new ConcurrentHashMap<>();
    private static final java.util.Set<String> botAvatarInFlight =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final long BOT_AVATAR_NEGATIVE_TTL_MS = 120_000L;
    private static final long BOT_AVATAR_FILE_MISS_TTL_MS = 10_000L;

    public interface BotEmojiCallback {
        void onDocumentsLoaded(ArrayList<Object> documents);
    }

    private static Context resolveAppContext(Context context) {
        if (context != null) {
            return context.getApplicationContext();
        }
        try {
            Class<?> alClass = Class.forName("org.telegram.messenger.ApplicationLoader");
            return (Context) alClass.getField("applicationContext").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File getBotCacheDir(Context context, String sub) {
        if (sub == null) sub = "";
        File cached = botCacheDirs.get(sub);
        if (cached != null) {
            return cached;
        }
        Context app = resolveAppContext(context);
        if (app == null) return null;
        File dir = new File(new File(app.getFilesDir(), "colgram_bot_cache"), sub);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        botCacheDirs.putIfAbsent(sub, dir);
        return dir;
    }

    public static File getCachedBotAvatarFile(int account, long peerId) {
        if (peerId == 0) return null;
        final String key = account + ":" + peerId;
        File cached = botAvatarFileCache.get(key);
        if (cached != null) {
            if (cached.length() > 64) {
                return cached;
            }
            botAvatarFileCache.remove(key, cached);
        }
        Long missingAt = botAvatarMissingAt.get(key);
        if (missingAt != null && System.currentTimeMillis() - missingAt < BOT_AVATAR_FILE_MISS_TTL_MS) {
            return null;
        }
        File dir = getBotCacheDir(null, "avatars");
        if (dir == null) return null;
        File scoped = new File(dir, "acc_" + account + "_peer_" + peerId + ".jpg");
        if (scoped.exists() && scoped.length() > 64) {
            botAvatarMissingAt.remove(key);
            botAvatarFileCache.put(key, scoped);
            return scoped;
        }
        File legacy = new File(dir, "peer_" + peerId + ".jpg");
        if (legacy.exists() && legacy.length() > 64) {
            botAvatarMissingAt.remove(key);
            botAvatarFileCache.put(key, legacy);
            return legacy;
        }
        botAvatarMissingAt.put(key, System.currentTimeMillis());
        return null;
    }

    public static void requestBotAvatarAsync(Context context, int account, long peerId, boolean isChat) {
        requestBotAvatarWithChatId(context, account, peerId, 0L, isChat);
    }

    private static final java.util.Set<String> botPeerMetaSynced =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void requestBotAvatarWithChatId(Context context, int account, long peerId,
                                                   long explicitBotApiChatId, boolean isChat) {
        if (peerId == 0) return;
        File existing = getCachedBotAvatarFile(account, peerId);
        final String key = account + ":" + peerId;
        final boolean needAvatar = (existing == null);
        final boolean needMeta = !botPeerMetaSynced.contains(key);
        if (!needAvatar && !needMeta) return;

        long now = System.currentTimeMillis();
        Long attempted = botAvatarAttemptedAt.get(key);
        if (attempted != null && now - attempted < BOT_AVATAR_NEGATIVE_TTL_MS) {
            return;
        }
        if (!botAvatarInFlight.add(key)) {
            return;
        }
        final Context app = resolveAppContext(context);
        if (app == null) {
            botAvatarInFlight.remove(key);
            return;
        }
        final String token = getBotToken(app, account);
        if (token.isEmpty()) {
            botAvatarAttemptedAt.put(key, now);
            botAvatarInFlight.remove(key);
            return;
        }
        avatarExecutor.execute(() -> {
            try {
                botAvatarAttemptedAt.put(key, System.currentTimeMillis());
                String fileId = null;
                boolean chatAnswered = false;
                if (needMeta || needAvatar) {
                    long[] candidateChatIds;
                    if (explicitBotApiChatId != 0) {
                        candidateChatIds = new long[]{explicitBotApiChatId};
                    } else if (peerId > 0) {
                        candidateChatIds = new long[]{peerId};
                    } else {
                        long abs = -peerId;
                        candidateChatIds = new long[]{-1000000000000L - abs, peerId};
                    }
                    for (long cid : candidateChatIds) {
                        JSONObject chatPayload = new JSONObject();
                        chatPayload.put("chat_id", cid);
                        JSONObject chatResp = botApiPost(token, "getChat", chatPayload, 6000);
                        if (chatResp != null && chatResp.optBoolean("ok", false)) {
                            JSONObject res = chatResp.optJSONObject("result");
                            if (res != null) {
                                chatAnswered = true;
                                botPeerMetaSynced.add(key);
                                applyBotChatProfileMetadata(app, account, peerId, isChat, res);
                                if (needAvatar) {
                                    JSONObject photo = res.optJSONObject("photo");
                                    if (photo != null) {
                                        fileId = photo.optString("small_file_id", photo.optString("big_file_id", ""));
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                if (needAvatar && !isChat && peerId > 0 && !chatAnswered && (fileId == null || fileId.isEmpty())) {
                    JSONObject photosPayload = new JSONObject();
                    photosPayload.put("user_id", peerId);
                    photosPayload.put("limit", 1);
                    JSONObject photosResp = botApiPost(token, "getUserProfilePhotos", photosPayload, 6000);
                    if (photosResp != null && photosResp.optBoolean("ok", false)) {
                        JSONObject res = photosResp.optJSONObject("result");
                        JSONArray photos = res != null ? res.optJSONArray("photos") : null;
                        if (photos != null && photos.length() > 0) {
                            JSONArray sizes = photos.optJSONArray(0);
                            if (sizes != null && sizes.length() > 0) {
                                int pick = sizes.length() > 1 ? Math.min(1, sizes.length() - 1) : 0;
                                JSONObject picked = sizes.optJSONObject(pick);
                                if (picked != null) {
                                    fileId = picked.optString("file_id", "");
                                }
                            }
                        }
                    }
                }
                if (needAvatar && fileId != null && !fileId.isEmpty()) {
                    File dir = getBotCacheDir(app, "avatars");
                    if (dir != null) {
                        File dest = new File(dir, "acc_" + account + "_peer_" + peerId + ".jpg");
                        if (downloadBotFileById(token, fileId, dest, 8000)) {
                            botAvatarMissingAt.remove(key);
                            botAvatarFileCache.put(key, dest);
                            postDebouncedAvatarRefresh(account);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "requestBotAvatarWithChatId failed for " + peerId + ": " + t.getMessage());
            } finally {
                botAvatarInFlight.remove(key);
            }
        });
    }

    private static void applyBotChatProfileMetadata(Context app, int account, long peerId, boolean isChat, JSONObject chatRes) {
        if (chatRes == null || peerId == 0) return;
        try {
            int accentColorId = chatRes.optInt("accent_color_id", -1);
            long bgEmojiId = parseLongSafe(chatRes.optString("background_custom_emoji_id", ""));
            int profileAccentId = chatRes.optInt("profile_accent_color_id", -1);
            long profileBgEmojiId = parseLongSafe(chatRes.optString("profile_background_custom_emoji_id", ""));
            long emojiStatusId = parseLongSafe(chatRes.optString("emoji_status_custom_emoji_id", ""));

            if (accentColorId < 0 && bgEmojiId == 0L && profileAccentId < 0 && profileBgEmojiId == 0L && emojiStatusId == 0L) {
                return;
            }

            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);
            if (mc == null) return;

            Class<?> peerColorCls = Class.forName("org.telegram.tgnet.TLRPC$TL_peerColor");
            Class<?> emojiStatusCls = Class.forName("org.telegram.tgnet.TLRPC$TL_emojiStatus");

            Object target = (!isChat && peerId > 0)
                    ? mcClass.getMethod("getUser", Long.class).invoke(mc, peerId)
                    : mcClass.getMethod("getChat", Long.class).invoke(mc, Math.abs(peerId));
            if (target == null) return;

            boolean changed = false;
            Class<?> targetCls = target.getClass();
            boolean isUserObj = (!isChat && peerId > 0);

            if (accentColorId >= 0 || bgEmojiId != 0L) {
                Object pc = peerColorCls.getConstructor().newInstance();
                int pFlags = 0;
                if (accentColorId >= 0) {
                    pFlags |= 1;
                    peerColorCls.getField("color").setInt(pc, accentColorId);
                }
                if (bgEmojiId != 0L) {
                    pFlags |= 2;
                    peerColorCls.getField("background_emoji_id").setLong(pc, bgEmojiId);
                }
                peerColorCls.getField("flags").setInt(pc, pFlags);
                targetCls.getField("color").set(target, pc);
                int f2 = targetCls.getField("flags2").getInt(target);
                targetCls.getField("flags2").setInt(target, f2 | (isUserObj ? (1 << 8) : (1 << 7)));
                changed = true;
            }
            if (profileAccentId >= 0 || profileBgEmojiId != 0L) {
                Object pc = peerColorCls.getConstructor().newInstance();
                int pFlags = 0;
                if (profileAccentId >= 0) {
                    pFlags |= 1;
                    peerColorCls.getField("color").setInt(pc, profileAccentId);
                }
                if (profileBgEmojiId != 0L) {
                    pFlags |= 2;
                    peerColorCls.getField("background_emoji_id").setLong(pc, profileBgEmojiId);
                }
                peerColorCls.getField("flags").setInt(pc, pFlags);
                targetCls.getField("profile_color").set(target, pc);
                int f2 = targetCls.getField("flags2").getInt(target);
                targetCls.getField("flags2").setInt(target, f2 | (isUserObj ? (1 << 9) : (1 << 8)));
                changed = true;
            }
            if (emojiStatusId != 0L) {
                Object es = emojiStatusCls.getConstructor().newInstance();
                emojiStatusCls.getField("flags").setInt(es, 0);
                emojiStatusCls.getField("document_id").setLong(es, emojiStatusId);
                targetCls.getField("emoji_status").set(target, es);
                if (isUserObj) {
                    int f1 = targetCls.getField("flags").getInt(target);
                    targetCls.getField("flags").setInt(target, f1 | (1 << 30));
                } else {
                    int f2 = targetCls.getField("flags2").getInt(target);
                    targetCls.getField("flags2").setInt(target, f2 | (1 << 9));
                }
                changed = true;
            }

            if (changed) {
                ArrayList<Long> emojiIdsToFetch = new ArrayList<>();
                if (bgEmojiId != 0L) emojiIdsToFetch.add(bgEmojiId);
                if (profileBgEmojiId != 0L && !emojiIdsToFetch.contains(profileBgEmojiId)) emojiIdsToFetch.add(profileBgEmojiId);
                if (emojiStatusId != 0L && !emojiIdsToFetch.contains(emojiStatusId)) emojiIdsToFetch.add(emojiStatusId);
                if (!emojiIdsToFetch.isEmpty()) {
                    fetchCustomEmojiForBot(app, account, emojiIdsToFetch, docs -> postDebouncedAvatarRefresh(account));
                } else {
                    postDebouncedAvatarRefresh(account);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyBotChatProfileMetadata error: " + t.getMessage());
        }
    }

    private static long parseLongSafe(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static void postDebouncedAvatarRefresh(final int account) {
        long now = System.currentTimeMillis();
        Long last = botAvatarUiPostedAt.get(account);
        if (last != null && now - last < 300L) {
            if (Boolean.TRUE.equals(botAvatarUiPending.putIfAbsent(account, Boolean.TRUE))) {
                return;
            }
            mainHandler.postDelayed(() -> {
                botAvatarUiPending.remove(account);
                emitAvatarUpdateNotification(account);
            }, 320L);
            return;
        }
        botAvatarUiPostedAt.put(account, now);
        mainHandler.post(() -> emitAvatarUpdateNotification(account));
    }

    private static void emitAvatarUpdateNotification(int account) {
        botAvatarUiPostedAt.put(account, System.currentTimeMillis());
        try {
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
            Object nc = ncClass.getMethod("getInstance", int.class).invoke(null, account);
            int updateInterfaces = ncClass.getField("updateInterfaces").getInt(null);
            int maskAvatar = mcClass.getField("UPDATE_MASK_AVATAR").getInt(null);
            int maskChatAvatar = mcClass.getField("UPDATE_MASK_CHAT_AVATAR").getInt(null);
            ncClass.getMethod("postNotificationName", int.class, Object[].class)
                    .invoke(nc, updateInterfaces, new Object[]{maskAvatar | maskChatAvatar});
        } catch (Throwable ignored) {}
    }

    public static File getCachedBotEmojiFile(long docId) {
        if (docId == 0) return null;
        File dir = getBotCacheDir(null, "emoji");
        if (dir == null) return null;
        for (String ext : new String[]{".tgs", ".webm", ".webp"}) {
            File f = new File(dir, docId + ext);
            if (f.exists() && f.length() > 32) {
                return f;
            }
        }
        return null;
    }

    public static void fetchCustomEmojiForBot(Context context, int account,
                                              ArrayList<Long> documentIds, BotEmojiCallback callback) {
        if (callback == null) return;
        if (documentIds == null || documentIds.isEmpty()) {
            mainHandler.post(() -> callback.onDocumentsLoaded(new ArrayList<>()));
            return;
        }
        final Context app = resolveAppContext(context);
        executor.execute(() -> {
            ArrayList<Object> docs = new ArrayList<>();
            String token = getBotToken(app, account);
            if (app != null && !token.isEmpty()) {
                File dir = getBotCacheDir(app, "emoji");
                for (int start = 0; start < documentIds.size(); start += 100) {
                    int end = Math.min(documentIds.size(), start + 100);
                    try {
                        JSONArray idArr = new JSONArray();
                        for (int i = start; i < end; i++) {
                            idArr.put(String.valueOf(documentIds.get(i)));
                        }
                        JSONObject payload = new JSONObject();
                        payload.put("custom_emoji_ids", idArr);
                        JSONObject resp = botApiPost(token, "getCustomEmojiStickers", payload);
                        if (resp != null && resp.optBoolean("ok", false)) {
                            JSONArray arr = resp.optJSONArray("result");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject st = arr.optJSONObject(i);
                                    if (st == null) continue;
                                    String cidStr = st.optString("custom_emoji_id", "0");
                                    long docId = 0L;
                                    try { docId = Long.parseLong(cidStr); } catch (NumberFormatException ignored) {}
                                    if (docId == 0L) continue;

                                    boolean animated = st.optBoolean("is_animated", false);
                                    boolean video = st.optBoolean("is_video", false);
                                    String ext = animated ? ".tgs" : (video ? ".webm" : ".webp");
                                    String mime = animated ? "application/x-tgsticker" : (video ? "video/webm" : "image/webp");
                                    String fileId = st.optString("file_id", "");
                                    JSONObject thumbObj = st.optJSONObject("thumbnail");
                                    String thumbFileId = thumbObj != null ? thumbObj.optString("file_id", "") : "";

                                    File dest = dir != null ? new File(dir, docId + ext) : null;
                                    boolean downloaded = dest != null && dest.exists() && dest.length() > 32;
                                    if (!downloaded && dest != null && !fileId.isEmpty()) {
                                        downloaded = downloadBotFileById(token, fileId, dest);
                                    }
                                    if (!downloaded && dir != null && !thumbFileId.isEmpty()) {
                                        File thumbDest = new File(dir, docId + ".webp");
                                        if (downloadBotFileById(token, thumbFileId, thumbDest)) {
                                            ext = ".webp";
                                            mime = "image/webp";
                                            dest = thumbDest;
                                            downloaded = true;
                                        }
                                    }
                                    if (downloaded && dest != null) {
                                        String setName = st.optString("set_name", "");
                                        if (!setName.isEmpty()) {
                                            rememberDiscoveredEmojiSet(app, setName);
                                        }
                                        Object tlDoc = buildSyntheticEmojiDocument(
                                                docId,
                                                mime,
                                                (int) dest.length(),
                                                st.optInt("width", 512),
                                                st.optInt("height", 512),
                                                st.optString("emoji", "😀"),
                                                setName);
                                        if (tlDoc != null) {
                                            copyToCanonicalFileLoaderPath(account, tlDoc, dest);
                                            docs.add(tlDoc);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "fetchCustomEmojiForBot batch error: " + t.getMessage());
                    }
                }
            }
            if (!docs.isEmpty()) {
                mainHandler.post(() -> callback.onDocumentsLoaded(docs));
                return;
            }
            if (!relayCustomEmojiViaHumanSlot(account, documentIds, callback)) {
                mainHandler.post(() -> callback.onDocumentsLoaded(new ArrayList<>()));
            }
        });
    }

    private static boolean relayCustomEmojiViaHumanSlot(int botAccount, ArrayList<Long> ids, BotEmojiCallback callback) {
        try {
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> reqClass = Class.forName("org.telegram.tgnet.TLRPC$TL_messages_getCustomEmojiDocuments");
            Class<?> vecClass = Class.forName("org.telegram.tgnet.TLRPC$Vector");
            Class<?> delegateClass = Class.forName("org.telegram.tgnet.RequestDelegate");
            int maxAccounts = ucClass.getField("MAX_ACCOUNT_COUNT").getInt(null);
            for (int slot = 0; slot < maxAccounts; slot++) {
                if (slot == botAccount) continue;
                Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, slot);
                boolean activated = ucClass.getMethod("isClientActivated").invoke(uc) == Boolean.TRUE;
                Object user = activated ? ucClass.getMethod("getCurrentUser").invoke(uc) : null;
                if (user == null || user.getClass().getField("bot").getBoolean(user)) continue;

                Object req = reqClass.getConstructor().newInstance();
                @SuppressWarnings("unchecked")
                ArrayList<Long> reqIds = (ArrayList<Long>) reqClass.getField("document_id").get(req);
                reqIds.addAll(ids);

                Object proxyDelegate = java.lang.reflect.Proxy.newProxyInstance(
                        delegateClass.getClassLoader(),
                        new Class<?>[]{delegateClass},
                        (p, m, args) -> {
                            ArrayList<Object> out = new ArrayList<>();
                            if (args != null && args.length > 0 && vecClass.isInstance(args[0])) {
                                @SuppressWarnings("unchecked")
                                ArrayList<Object> vecObjs = (ArrayList<Object>) vecClass.getField("objects").get(args[0]);
                                if (vecObjs != null) out.addAll(vecObjs);
                            }
                            mainHandler.post(() -> callback.onDocumentsLoaded(out));
                            return null;
                        });
                Object cm = cmClass.getMethod("getInstance", int.class).invoke(null, slot);
                Class<?> tlObjClass = Class.forName("org.telegram.tgnet.TLObject");
                cmClass.getMethod("sendRequest", tlObjClass, delegateClass).invoke(cm, req, proxyDelegate);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "relayCustomEmojiViaHumanSlot failed: " + t.getMessage());
        }
        return false;
    }

    private static boolean attachBotStickerMedia(Context context, int account, String token,
                                                 JSONObject stObj, Object message, Class<?> messageClass, int date) {
        if (stObj == null || message == null || token == null || token.isEmpty()) {
            return false;
        }
        try {
            String fileId = stObj.optString("file_id", "");
            String uniqueId = stObj.optString("file_unique_id", fileId);
            if (fileId.isEmpty()) return false;
            boolean animated = stObj.optBoolean("is_animated", false);
            boolean video = stObj.optBoolean("is_video", false);
            String ext = animated ? ".tgs" : (video ? ".webm" : ".webp");
            String mime = animated ? "application/x-tgsticker" : (video ? "video/webm" : "image/webp");
            String emoji = stObj.optString("emoji", "😀");
            int width = stObj.optInt("width", 512);
            int height = stObj.optInt("height", 512);

            long h64 = 1125899906842597L;
            for (int k = 0; k < uniqueId.length(); k++) {
                h64 = 31L * h64 + uniqueId.charAt(k);
            }
            long docId = (h64 & 0x000FFFFFFFFFFFFFL) | 0x0010000000000000L;

            File dir = getBotCacheDir(context, "stickers");
            if (dir == null) return false;
            File dest = new File(dir, docId + ext);
            boolean ok = dest.exists() && dest.length() > 32;
            if (!ok) {
                ok = downloadBotFileById(token, fileId, dest);
            }
            if (!ok) {
                JSONObject thumbObj = stObj.optJSONObject("thumbnail");
                String thumbId = thumbObj != null ? thumbObj.optString("file_id", "") : "";
                if (!thumbId.isEmpty()) {
                    File thumbDest = new File(dir, docId + ".webp");
                    if ((thumbDest.exists() && thumbDest.length() > 32) || downloadBotFileById(token, thumbId, thumbDest)) {
                        dest = thumbDest;
                        ext = ".webp";
                        mime = "image/webp";
                        animated = false;
                        video = false;
                        ok = true;
                    }
                }
            }
            if (!ok || !dest.exists() || dest.length() <= 32) {
                return false;
            }

            Class<?> docCls = Class.forName("org.telegram.tgnet.TLRPC$TL_document");
            Class<?> imgAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeImageSize");
            Class<?> stAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeSticker");
            Class<?> fnAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeFilename");
            Class<?> emptySetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_inputStickerSetEmpty");
            Class<?> mediaDocCls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageMediaDocument");

            Object doc = docCls.getConstructor().newInstance();
            docCls.getField("id").setLong(doc, docId);
            docCls.getField("dc_id").setInt(doc, 1);
            docCls.getField("access_hash").setLong(doc, 0L);
            docCls.getField("flags").setInt(doc, 0);
            docCls.getField("mime_type").set(doc, mime);
            docCls.getField("size").setLong(doc, dest.length());
            docCls.getField("date").setInt(doc, date);
            docCls.getField("file_reference").set(doc, new byte[0]);
            @SuppressWarnings("unchecked")
            ArrayList<Object> attrs = (ArrayList<Object>) docCls.getField("attributes").get(doc);
            if (attrs != null) {
                Object sz = imgAttrCls.getConstructor().newInstance();
                imgAttrCls.getField("w").setInt(sz, width > 0 ? width : 512);
                imgAttrCls.getField("h").setInt(sz, height > 0 ? height : 512);
                attrs.add(sz);

                Object sa = stAttrCls.getConstructor().newInstance();
                stAttrCls.getField("alt").set(sa, emoji != null && !emoji.isEmpty() ? emoji : "😀");
                String setName = stObj.optString("set_name", "").trim();
                Object stickerSetObj;
                if (!setName.isEmpty()) {
                    Class<?> shortNameSetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_inputStickerSetShortName");
                    stickerSetObj = shortNameSetCls.getConstructor().newInstance();
                    shortNameSetCls.getField("short_name").set(stickerSetObj, setName);
                } else {
                    stickerSetObj = emptySetCls.getConstructor().newInstance();
                }
                stAttrCls.getField("stickerset").set(sa, stickerSetObj);
                attrs.add(sa);

                Object fn = fnAttrCls.getConstructor().newInstance();
                fnAttrCls.getField("file_name").set(fn, "sticker" + ext);
                attrs.add(fn);
            }

            File finalStickerFile = dest;
            try {
                Class<?> flCls = Class.forName("org.telegram.messenger.FileLoader");
                Class<?> tlObjCls = Class.forName("org.telegram.tgnet.TLObject");
                Object fl = flCls.getMethod("getInstance", int.class).invoke(null, account);
                // Copy into Telegram's canonical FileLoader attach path so ImageLoader & ChatMessageCell find it immediately!
                try {
                    File canonical = (File) flCls.getMethod("getPathToAttach", tlObjCls, boolean.class).invoke(fl, doc, true);
                    if (canonical != null && !canonical.equals(dest)) {
                        File parent = canonical.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        if (!canonical.exists() || canonical.length() != dest.length()) {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(dest);
                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(canonical)) {
                                byte[] buf = new byte[16384];
                                int n;
                                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                            }
                        }
                        if (canonical.exists() && canonical.length() > 32) {
                            finalStickerFile = canonical;
                        }
                    }
                } catch (Throwable ignored) {}
                flCls.getMethod("setLocalPathTo", tlObjCls, String.class).invoke(fl, doc, finalStickerFile.getAbsolutePath());
            } catch (Throwable ignored) {}

            Object media = mediaDocCls.getConstructor().newInstance();
            mediaDocCls.getField("document").set(media, doc);
            mediaDocCls.getField("flags").setInt(media, 1);

            messageClass.getField("media").set(message, media);
            messageClass.getField("attachPath").set(message, finalStickerFile.getAbsolutePath());
            int curFlags = messageClass.getField("flags").getInt(message);
            messageClass.getField("flags").setInt(message, curFlags | 0x00000200);
            messageClass.getField("message").set(message, "");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attachBotStickerMedia failed: " + t.getMessage());
            return false;
        }
    }

    private static long stableBotMediaId(String uniqueId) {
        long h64 = 1125899906842597L;
        if (uniqueId != null) {
            for (int k = 0; k < uniqueId.length(); k++) {
                h64 = 31L * h64 + uniqueId.charAt(k);
            }
        }
        return (h64 & 0x000FFFFFFFFFFFFFL) | 0x0020000000000000L;
    }

    private static File copyToCanonicalFileLoaderPath(int account, Object tlAttach, File src) {
        File result = src;
        try {
            Class<?> flCls = Class.forName("org.telegram.messenger.FileLoader");
            Class<?> tlObjCls = Class.forName("org.telegram.tgnet.TLObject");
            Object fl = flCls.getMethod("getInstance", int.class).invoke(null, account);
            // Copy to BOTH non-cache media dir (used by PhotoViewer & MediaDataController) AND cache dir
            // (used by chat bubble thumbnails) so neither PhotoViewer nor chat cells ever spin trying MTProto!
            boolean[] modes = {false, true};
            for (boolean forceCache : modes) {
                try {
                    File canonical = (File) flCls.getMethod("getPathToAttach", tlObjCls, boolean.class)
                            .invoke(fl, tlAttach, forceCache);
                    if (canonical != null && !canonical.getPath().isEmpty() && !canonical.equals(src)) {
                        File parent = canonical.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        if (!canonical.exists() || canonical.length() != src.length()) {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(canonical)) {
                                byte[] buf = new byte[16384];
                                int n;
                                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                            }
                        }
                        if (!forceCache && canonical.exists() && canonical.length() > 16) {
                            result = canonical;
                        } else if (result == src && canonical.exists() && canonical.length() > 16) {
                            result = canonical;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            flCls.getMethod("setLocalPathTo", tlObjCls, String.class).invoke(fl, tlAttach, result.getAbsolutePath());
        } catch (Throwable ignored) {}
        return result;
    }

    private static final java.util.Set<Integer> hydratedBotMediaAccounts =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static void hydrateExistingBotPhotosOnceAsync(final Context appContext, final int account) {
        if (appContext == null || !hydratedBotMediaAccounts.add(account)) return;
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            hydrateExistingBotPhotos(appContext, account);
        }, "colgram-bot-media-hydrate-" + account);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Migrates any previously downloaded Bot API photos in files/colgram_bot_cache/photos/<photoId>.jpg
     * into Telegram's canonical FileLoader paths (-<photoId>_121.jpg in both MEDIA_DIR_IMAGE and MEDIA_DIR_CACHE)
     * so pre-existing bot photos immediately render in ChatActivity, SharedMediaLayout, and PhotoViewer
     * without spinning forever waiting on MTProto.
     */
    private static void hydrateExistingBotPhotos(Context context, int account) {
        if (context == null) return;
        try {
            File photosDir = new File(new File(context.getFilesDir(), "colgram_bot_cache"), "photos");
            File[] files = photosDir.listFiles();
            if (files == null || files.length == 0) return;
            Class<?> sizeCls = Class.forName("org.telegram.tgnet.TLRPC$TL_photoSize");
            Class<?> locCls = Class.forName("org.telegram.tgnet.TLRPC$TL_fileLocationToBeDeprecated");
            for (File f : files) {
                if (f == null || !f.isFile() || f.length() <= 32) continue;
                String name = f.getName();
                if (!name.endsWith(".jpg")) continue;
                String base = name.substring(0, name.length() - 4);
                long photoId;
                try {
                    photoId = Long.parseLong(base);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (photoId <= 0L) continue;
                Object ps = sizeCls.getConstructor().newInstance();
                sizeCls.getField("type").set(ps, "y");
                sizeCls.getField("size").setInt(ps, (int) f.length());
                Object loc = locCls.getConstructor().newInstance();
                locCls.getField("volume_id").setLong(loc, -photoId);
                locCls.getField("local_id").setInt(loc, (int) 'y');
                sizeCls.getField("location").set(ps, loc);
                copyToCanonicalFileLoaderPath(account, ps, f);
            }
        } catch (Throwable ignored) {}
    }

    /** Downloads a Bot API photo array and attaches a real TLRPC.TL_messageMediaPhoto (never "🖼 Фотография" text). */
    private static boolean attachBotPhotoMedia(Context context, int account, String token,
                                               long dialogId, int msgId, boolean isEdit,
                                               JSONArray photoArr, String caption,
                                               Object message, Class<?> messageClass, int date) {
        if (photoArr == null || photoArr.length() == 0 || message == null) return false;
        try {
            JSONObject best = null;
            int bestArea = -1;
            for (int k = 0; k < photoArr.length(); k++) {
                JSONObject item = photoArr.optJSONObject(k);
                if (item == null || item.optString("file_id", "").isEmpty()) continue;
                int area = item.optInt("width", 0) * item.optInt("height", 0);
                if (best == null || area >= bestArea) {
                    best = item;
                    bestArea = area;
                }
            }
            if (best == null) return false;
            String fileId = best.optString("file_id", "");
            String uniqueId = best.optString("file_unique_id", fileId);
            int w = Math.max(1, best.optInt("width", 800));
            int h = Math.max(1, best.optInt("height", 800));
            long photoId = stableBotMediaId("photo_" + uniqueId);

            File dir = getBotCacheDir(context, "photos");
            if (dir == null) return false;
            File dest = new File(dir, photoId + ".jpg");
            boolean ok = dest.exists() && dest.length() > 64;
            if (!ok) {
                ok = downloadBotFileById(token, fileId, dest);
            }
            if (!ok || !dest.exists() || dest.length() <= 64) return false;

            Class<?> mediaPhotoCls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageMediaPhoto");
            Class<?> photoCls = Class.forName("org.telegram.tgnet.TLRPC$TL_photo");
            Class<?> photoSizeCls = Class.forName("org.telegram.tgnet.TLRPC$TL_photoSize");
            Class<?> locCls = Class.forName("org.telegram.tgnet.TLRPC$TL_fileLocationToBeDeprecated");

            // EXACT match with TLRPC.PhotoSize.TLdeserialize (volume_id = -photo_id, local_id = type.charAt(0))
            // so PhotoViewer finds the downloaded JPEG immediately after SQLite reload with ZERO spinner!
            Object loc = locCls.getConstructor().newInstance();
            locCls.getField("volume_id").setLong(loc, -photoId);
            locCls.getField("local_id").setInt(loc, (int) 'y');

            Object ps = photoSizeCls.getConstructor().newInstance();
            photoSizeCls.getField("type").set(ps, "y");
            photoSizeCls.getField("w").setInt(ps, w);
            photoSizeCls.getField("h").setInt(ps, h);
            photoSizeCls.getField("size").setInt(ps, (int) Math.min(Integer.MAX_VALUE, dest.length()));
            photoSizeCls.getField("location").set(ps, loc);

            Object photo = photoCls.getConstructor().newInstance();
            photoCls.getField("id").setLong(photo, photoId);
            photoCls.getField("access_hash").setLong(photo, 0L);
            photoCls.getField("date").setInt(photo, date);
            photoCls.getField("dc_id").setInt(photo, 1);
            photoCls.getField("file_reference").set(photo, new byte[0]);
            @SuppressWarnings("unchecked")
            ArrayList<Object> sizes = (ArrayList<Object>) photoCls.getField("sizes").get(photo);
            if (sizes != null) sizes.add(ps);

            File finalFile = copyToCanonicalFileLoaderPath(account, ps, dest);
            Object media = mediaPhotoCls.getConstructor().newInstance();
            mediaPhotoCls.getField("photo").set(media, photo);
            mediaPhotoCls.getField("flags").setInt(media, 1);

            messageClass.getField("media").set(message, media);
            messageClass.getField("attachPath").set(message, finalFile.getAbsolutePath());
            int curFlags = messageClass.getField("flags").getInt(message);
            messageClass.getField("flags").setInt(message, curFlags | 0x00000200);
            messageClass.getField("message").set(message, caption != null ? caption : "");

            if (isEdit) {
                ColgramHookHandler.hookOnMediaChangedWithNew(
                        dialogId, msgId, ColgramDatabase.MediaRevision.TYPE_PHOTO,
                        finalFile.getAbsolutePath(), "photo_" + photoId + ".jpg",
                        finalFile.length(), "image/jpeg", photoId, System.currentTimeMillis());
            } else {
                ColgramHookHandler.recordLiveMediaSnapshot(
                        dialogId, msgId, ColgramDatabase.MediaRevision.TYPE_PHOTO,
                        finalFile.getAbsolutePath(), "photo_" + photoId + ".jpg",
                        finalFile.length(), "image/jpeg", photoId);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attachBotPhotoMedia failed: " + t.getMessage());
            return false;
        }
    }

    /** Downloads a Bot API video/video_note (кружок)/animation and attaches a real TLRPC.TL_messageMediaDocument. */
    private static boolean attachBotVideoMedia(Context context, int account, String token,
                                               long dialogId, int msgId, boolean isEdit,
                                               JSONObject vidObj, boolean isGif, boolean isRound, String caption,
                                               Object message, Class<?> messageClass, int date) {
        if (vidObj == null || message == null) return false;
        try {
            String fileId = vidObj.optString("file_id", "");
            String uniqueId = vidObj.optString("file_unique_id", fileId);
            if (fileId.isEmpty()) return false;
            String mime = vidObj.optString("mime_type", "video/mp4");
            String fileName = vidObj.optString("file_name", isRound ? "round_video.mp4" : (isGif ? "animation.mp4" : "video.mp4"));
            int side = vidObj.optInt("length", 384);
            int w = Math.max(1, isRound ? side : vidObj.optInt("width", 640));
            int h = Math.max(1, isRound ? side : vidObj.optInt("height", 640));
            double duration = vidObj.optDouble("duration", 1.0);
            long docId = stableBotMediaId((isRound ? "round_" : "vid_") + uniqueId);

            File dir = getBotCacheDir(context, "videos");
            if (dir == null) return false;
            File dest = new File(dir, docId + ".mp4");
            boolean ok = dest.exists() && dest.length() > 64;
            if (!ok) {
                ok = downloadBotFileById(token, fileId, dest);
            }
            if (!ok || !dest.exists() || dest.length() <= 64) return false;

            Class<?> docCls = Class.forName("org.telegram.tgnet.TLRPC$TL_document");
            Class<?> vidAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeVideo");
            Class<?> fnAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeFilename");
            Class<?> animAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeAnimated");
            Class<?> mediaDocCls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageMediaDocument");

            Object doc = docCls.getConstructor().newInstance();
            docCls.getField("id").setLong(doc, docId);
            docCls.getField("dc_id").setInt(doc, 1);
            docCls.getField("access_hash").setLong(doc, 0L);
            docCls.getField("mime_type").set(doc, mime);
            docCls.getField("size").setLong(doc, dest.length());
            docCls.getField("date").setInt(doc, date);
            docCls.getField("file_reference").set(doc, new byte[0]);
            @SuppressWarnings("unchecked")
            ArrayList<Object> attrs = (ArrayList<Object>) docCls.getField("attributes").get(doc);
            if (attrs != null) {
                Object va = vidAttrCls.getConstructor().newInstance();
                vidAttrCls.getField("w").setInt(va, w);
                vidAttrCls.getField("h").setInt(va, h);
                vidAttrCls.getField("duration").setDouble(va, duration);
                vidAttrCls.getField("supports_streaming").setBoolean(va, true);
                if (isRound) {
                    vidAttrCls.getField("round_message").setBoolean(va, true);
                }
                attrs.add(va);
                if (isGif) attrs.add(animAttrCls.getConstructor().newInstance());
                Object fn = fnAttrCls.getConstructor().newInstance();
                fnAttrCls.getField("file_name").set(fn, fileName);
                attrs.add(fn);
            }

            File finalFile = copyToCanonicalFileLoaderPath(account, doc, dest);
            try {
                docCls.getField("localPath").set(doc, finalFile.getAbsolutePath());
            } catch (Throwable ignored) {}
            Object media = mediaDocCls.getConstructor().newInstance();
            mediaDocCls.getField("document").set(media, doc);
            mediaDocCls.getField("flags").setInt(media, 1);

            messageClass.getField("media").set(message, media);
            messageClass.getField("attachPath").set(message, finalFile.getAbsolutePath());
            int curFlags = messageClass.getField("flags").getInt(message);
            messageClass.getField("flags").setInt(message, curFlags | 0x00000200);
            messageClass.getField("message").set(message, caption != null ? caption : "");

            if (isEdit) {
                ColgramHookHandler.hookOnMediaChangedWithNew(
                        dialogId, msgId, ColgramDatabase.MediaRevision.TYPE_VIDEO,
                        finalFile.getAbsolutePath(), fileName, finalFile.length(), mime, docId, System.currentTimeMillis());
            } else {
                ColgramHookHandler.recordLiveMediaSnapshot(
                        dialogId, msgId, ColgramDatabase.MediaRevision.TYPE_VIDEO,
                        finalFile.getAbsolutePath(), fileName, finalFile.length(), mime, docId);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attachBotVideoMedia failed: " + t.getMessage());
            return false;
        }
    }

    /** Downloads a Bot API document/voice/audio (музыка) and attaches a real TLRPC.TL_messageMediaDocument. */
    private static boolean attachBotDocumentMedia(Context context, int account, String token,
                                                  long dialogId, int msgId, boolean isEdit,
                                                  JSONObject docObj, boolean isVoice, boolean isAudio, String caption,
                                                  Object message, Class<?> messageClass, int date) {
        if (docObj == null || message == null) return false;
        try {
            String fileId = docObj.optString("file_id", "");
            String uniqueId = docObj.optString("file_unique_id", fileId);
            if (fileId.isEmpty()) return false;
            String defaultMime = isVoice ? "audio/ogg" : (isAudio ? "audio/mpeg" : "application/octet-stream");
            String mime = docObj.optString("mime_type", defaultMime);
            String defaultName = isVoice ? "voice.ogg" : (isAudio ? "audio.mp3" : "file.bin");
            String fileName = docObj.optString("file_name", defaultName);
            long docId = stableBotMediaId((isVoice ? "voice_" : (isAudio ? "audio_" : "doc_")) + uniqueId);

            File dir = getBotCacheDir(context, "docs");
            if (dir == null) return false;
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.'))
                    : (isVoice ? ".ogg" : (isAudio ? ".mp3" : ".bin"));
            if (ext.length() > 8) ext = ".bin";
            File dest = new File(dir, docId + ext);
            boolean ok = dest.exists() && dest.length() > 16;
            if (!ok) {
                ok = downloadBotFileById(token, fileId, dest);
            }
            if (!ok || !dest.exists() || dest.length() <= 16) return false;

            Class<?> docCls = Class.forName("org.telegram.tgnet.TLRPC$TL_document");
            Class<?> fnAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeFilename");
            Class<?> mediaDocCls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageMediaDocument");

            Object doc = docCls.getConstructor().newInstance();
            docCls.getField("id").setLong(doc, docId);
            docCls.getField("dc_id").setInt(doc, 1);
            docCls.getField("access_hash").setLong(doc, 0L);
            docCls.getField("mime_type").set(doc, mime);
            docCls.getField("size").setLong(doc, dest.length());
            docCls.getField("date").setInt(doc, date);
            docCls.getField("file_reference").set(doc, new byte[0]);
            @SuppressWarnings("unchecked")
            ArrayList<Object> attrs = (ArrayList<Object>) docCls.getField("attributes").get(doc);
            if (attrs != null) {
                if (isVoice || isAudio) {
                    try {
                        Class<?> audAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeAudio");
                        Object aa = audAttrCls.getConstructor().newInstance();
                        audAttrCls.getField("voice").setBoolean(aa, isVoice);
                        audAttrCls.getField("duration").setDouble(aa, docObj.optDouble("duration", 1.0));
                        int aFlags = isVoice ? 1024 : 0;
                        String title = docObj.optString("title", "");
                        String performer = docObj.optString("performer", "");
                        if (!title.isEmpty()) {
                            audAttrCls.getField("title").set(aa, title);
                            aFlags |= 1;
                        }
                        if (!performer.isEmpty()) {
                            audAttrCls.getField("performer").set(aa, performer);
                            aFlags |= 2;
                        }
                        audAttrCls.getField("flags").setInt(aa, aFlags);
                        attrs.add(aa);
                    } catch (Throwable ignored) {}
                }
                Object fn = fnAttrCls.getConstructor().newInstance();
                fnAttrCls.getField("file_name").set(fn, fileName);
                attrs.add(fn);
            }

            File finalFile = copyToCanonicalFileLoaderPath(account, doc, dest);
            try {
                docCls.getField("localPath").set(doc, finalFile.getAbsolutePath());
            } catch (Throwable ignored) {}
            Object media = mediaDocCls.getConstructor().newInstance();
            mediaDocCls.getField("document").set(media, doc);
            mediaDocCls.getField("flags").setInt(media, 1);

            messageClass.getField("media").set(message, media);
            messageClass.getField("attachPath").set(message, finalFile.getAbsolutePath());
            int curFlags = messageClass.getField("flags").getInt(message);
            messageClass.getField("flags").setInt(message, curFlags | 0x00000200);
            messageClass.getField("message").set(message, caption != null ? caption : "");

            int revType = mime.startsWith("image/") ? ColgramDatabase.MediaRevision.TYPE_PHOTO
                    : (mime.startsWith("video/") ? ColgramDatabase.MediaRevision.TYPE_VIDEO
                    : ColgramDatabase.MediaRevision.TYPE_DOCUMENT);
            if (isEdit) {
                ColgramHookHandler.hookOnMediaChangedWithNew(
                        dialogId, msgId, revType, finalFile.getAbsolutePath(),
                        fileName, finalFile.length(), mime, docId, System.currentTimeMillis());
            } else {
                ColgramHookHandler.recordLiveMediaSnapshot(
                        dialogId, msgId, revType, finalFile.getAbsolutePath(),
                        fileName, finalFile.length(), mime, docId);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attachBotDocumentMedia failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Bot API fallback for StickersAlert when MTProto messages.getStickerSet fails on a bot account
     * or deleted sticker: fetches full sticker pack via Bot API getStickerSet?name=<shortName>.
     */
    public static void fetchBotStickerSetAsync(final Context context, final int account, final String shortName,
                                               final java.util.function.Consumer<Object> onLoaded) {
        if (shortName == null || shortName.trim().isEmpty() || onLoaded == null) return;
        executor.execute(() -> {
            Object resultSet = null;
            try {
                String token = getBotToken(context, account);
                if (token != null && !token.isEmpty()) {
                    String url = "https://api.telegram.org/bot" + token + "/getStickerSet?name="
                            + java.net.URLEncoder.encode(shortName.trim(), "UTF-8");
                    ColgramHttp.Response resp = ColgramHttp.get(url);
                    if (resp != null && resp.code == 200 && resp.body != null) {
                        JSONObject root = new JSONObject(resp.body);
                        if (root.optBoolean("ok", false) && root.has("result")) {
                            JSONObject res = root.getJSONObject("result");
                            Class<?> resSetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_messages_stickerSet");
                            Class<?> setCls = Class.forName("org.telegram.tgnet.TLRPC$TL_stickerSet");
                            Class<?> packCls = Class.forName("org.telegram.tgnet.TLRPC$TL_stickerPack");
                            Class<?> docCls = Class.forName("org.telegram.tgnet.TLRPC$TL_document");
                            Class<?> imgAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeImageSize");
                            Class<?> stAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeSticker");
                            Class<?> emAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeCustomEmoji");
                            Class<?> fnAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeFilename");
                            Class<?> shortSetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_inputStickerSetShortName");

                            Object tlRes = resSetCls.getConstructor().newInstance();
                            Object tlSet = setCls.getConstructor().newInstance();
                            String resolvedShortName = res.optString("name", shortName);
                            long setId = stableBotMediaId("set_" + resolvedShortName);
                            setCls.getField("id").setLong(tlSet, setId);
                            setCls.getField("short_name").set(tlSet, resolvedShortName);
                            setCls.getField("title").set(tlSet, res.optString("title", resolvedShortName));
                            String stType = res.optString("sticker_type", "regular");
                            boolean isCustomEmojiSet = "custom_emoji".equals(stType);
                            setCls.getField("masks").setBoolean(tlSet, "mask".equals(stType));
                            setCls.getField("emojis").setBoolean(tlSet, isCustomEmojiSet);

                            JSONArray stickers = res.optJSONArray("stickers");
                            @SuppressWarnings("unchecked")
                            ArrayList<Object> docs = (ArrayList<Object>) resSetCls.getField("documents").get(tlRes);
                            @SuppressWarnings("unchecked")
                            ArrayList<Object> packs = (ArrayList<Object>) resSetCls.getField("packs").get(tlRes);
                            File dir = getBotCacheDir(context, isCustomEmojiSet ? "emoji" : "stickers");
                            int count = stickers != null ? Math.min(stickers.length(), isCustomEmojiSet ? 64 : 40) : 0;
                            setCls.getField("count").setInt(tlSet, count);
                            final ArrayList<Object[]> deferredDownloads = new ArrayList<>();
                            int eagerDownloads = 0;
                            final int maxEagerDownloads = isCustomEmojiSet ? 16 : 20;
                            for (int i = 0; i < count; i++) {
                                JSONObject st = stickers.optJSONObject(i);
                                if (st == null) continue;
                                String fId = st.optString("file_id", "");
                                String uId = st.optString("file_unique_id", fId);
                                if (fId.isEmpty()) continue;
                                boolean anim = st.optBoolean("is_animated", false);
                                boolean vid = st.optBoolean("is_video", false);
                                String ext = anim ? ".tgs" : (vid ? ".webm" : ".webp");
                                String mime = anim ? "application/x-tgsticker" : (vid ? "video/webm" : "image/webp");
                                long customEmojiId = isCustomEmojiSet ? parseLongSafe(st.optString("custom_emoji_id", "")) : 0L;
                                long docId = customEmojiId != 0L ? customEmojiId : stableBotMediaId(uId);
                                File dest = dir != null ? new File(dir, docId + ext) : null;
                                boolean alreadyCached = dest != null && dest.exists() && dest.length() > 32;
                                if (!alreadyCached && dest != null && eagerDownloads < maxEagerDownloads) {
                                    eagerDownloads++;
                                    alreadyCached = downloadBotFileById(token, fId, dest);
                                }
                                Object doc = docCls.getConstructor().newInstance();
                                docCls.getField("id").setLong(doc, docId);
                                docCls.getField("dc_id").setInt(doc, 1);
                                docCls.getField("mime_type").set(doc, mime);
                                docCls.getField("size").setLong(doc, dest != null && dest.exists() ? dest.length() : 1024L);
                                docCls.getField("file_reference").set(doc, new byte[0]);
                                @SuppressWarnings("unchecked")
                                ArrayList<Object> attrs = (ArrayList<Object>) docCls.getField("attributes").get(doc);
                                if (attrs != null) {
                                    Object sz = imgAttrCls.getConstructor().newInstance();
                                    imgAttrCls.getField("w").setInt(sz, st.optInt("width", 512));
                                    imgAttrCls.getField("h").setInt(sz, st.optInt("height", 512));
                                    attrs.add(sz);
                                    String em = st.optString("emoji", "😀");
                                    Object sn = shortSetCls.getConstructor().newInstance();
                                    shortSetCls.getField("short_name").set(sn, resolvedShortName);
                                    if (isCustomEmojiSet) {
                                        Object ea = emAttrCls.getConstructor().newInstance();
                                        emAttrCls.getField("alt").set(ea, em);
                                        emAttrCls.getField("stickerset").set(ea, sn);
                                        attrs.add(ea);
                                    } else {
                                        Object sa = stAttrCls.getConstructor().newInstance();
                                        stAttrCls.getField("alt").set(sa, em);
                                        stAttrCls.getField("stickerset").set(sa, sn);
                                        attrs.add(sa);
                                    }
                                    Object fn = fnAttrCls.getConstructor().newInstance();
                                    fnAttrCls.getField("file_name").set(fn, (isCustomEmojiSet ? "emoji_" + docId : "sticker") + ext);
                                    attrs.add(fn);
                                }
                                if (alreadyCached && dest != null && dest.exists() && dest.length() > 32) {
                                    copyToCanonicalFileLoaderPath(account, doc, dest);
                                } else if (dest != null) {
                                    deferredDownloads.add(new Object[]{fId, dest, doc});
                                }
                                if (docs != null) docs.add(doc);
                                if (packs != null) {
                                    Object pk = packCls.getConstructor().newInstance();
                                    packCls.getField("emoticon").set(pk, st.optString("emoji", "😀"));
                                    @SuppressWarnings("unchecked")
                                    ArrayList<Long> pkDocs = (ArrayList<Long>) packCls.getField("documents").get(pk);
                                    if (pkDocs != null) pkDocs.add(docId);
                                    packs.add(pk);
                                }
                            }
                            if (!deferredDownloads.isEmpty()) {
                                final String bgToken = token;
                                executor.execute(() -> {
                                    for (Object[] item : deferredDownloads) {
                                        try {
                                            String fId = (String) item[0];
                                            File dest = (File) item[1];
                                            Object doc = item[2];
                                            if ((!dest.exists() || dest.length() <= 32) && downloadBotFileById(bgToken, fId, dest)) {
                                                copyToCanonicalFileLoaderPath(account, doc, dest);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                });
                            }
                            resSetCls.getField("set").set(tlRes, tlSet);
                            if (docs != null && !docs.isEmpty()) {
                                resultSet = tlRes;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "fetchBotStickerSetAsync failed: " + t.getMessage());
            }
            final Object finalSet = resultSet;
            mainHandler.post(() -> onLoaded.accept(finalSet));
        });
    }

    public interface BotEmojiPacksCallback {
        void onPacksLoaded(ArrayList<Object> stickerSets);
    }

    private static final String PREF_BOT_EMOJI_SETS = "colgram_bot_emoji_sets";
    private static final String[] DEFAULT_BOT_EMOJI_PACKS = new String[]{
            "RestrictedEmoji",
            "TelegramEmoji",
            "EmojiAnimations",
            "DuckEmoji",
            "AnimatedEmojis",
            "CatEmoji"
    };
    private static final ConcurrentHashMap<Integer, ArrayList<Object>> cachedBotEmojiPacks = new ConcurrentHashMap<>();
    private static final Set<Integer> botEmojiPacksInFlight = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void rememberDiscoveredEmojiSet(Context context, String setName) {
        if (setName == null || setName.trim().isEmpty()) return;
        Context app = resolveAppContext(context);
        if (app == null) return;
        try {
            String clean = setName.trim();
            SharedPreferences sp = app.getSharedPreferences("colgram_bot_emoji_sets_prefs", Context.MODE_PRIVATE);
            String existing = sp.getString(PREF_BOT_EMOJI_SETS, "");
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            set.add(clean);
            if (!existing.isEmpty()) {
                for (String part : existing.split(",")) {
                    String p = part.trim();
                    if (!p.isEmpty() && set.size() < 20) set.add(p);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String s : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
            sp.edit().putString(PREF_BOT_EMOJI_SETS, sb.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * Fetches real animated Telegram Custom Emoji packs via Bot API getStickerSet
     * so bot accounts have full Premium Custom Emoji packs in EmojiView instead of an empty list.
     */
    public static void fetchBotCustomEmojiPacks(final Context context, final int account, final BotEmojiPacksCallback callback) {
        if (callback == null) return;
        ArrayList<Object> cached = cachedBotEmojiPacks.get(account);
        if (cached != null && !cached.isEmpty()) {
            mainHandler.post(() -> callback.onPacksLoaded(new ArrayList<>(cached)));
            return;
        }
        final Context app = resolveAppContext(context);
        final String token = getBotToken(app, account);
        if (app == null || token.isEmpty()) {
            mainHandler.post(() -> callback.onPacksLoaded(new ArrayList<>()));
            return;
        }
        if (!botEmojiPacksInFlight.add(account)) {
            mainHandler.post(() -> callback.onPacksLoaded(new ArrayList<>()));
            return;
        }
        executor.execute(() -> {
            ArrayList<Object> setsOut = new ArrayList<>();
            try {
                java.util.LinkedHashSet<String> packNames = new java.util.LinkedHashSet<>();
                try {
                    SharedPreferences sp = app.getSharedPreferences("colgram_bot_emoji_sets_prefs", Context.MODE_PRIVATE);
                    String saved = sp.getString(PREF_BOT_EMOJI_SETS, "");
                    if (!saved.isEmpty()) {
                        for (String p : saved.split(",")) {
                            if (!p.trim().isEmpty()) packNames.add(p.trim());
                        }
                    }
                } catch (Throwable ignored) {}
                for (String def : DEFAULT_BOT_EMOJI_PACKS) {
                    packNames.add(def);
                }

                Class<?> resSetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_messages_stickerSet");
                Class<?> setCls = Class.forName("org.telegram.tgnet.TLRPC$TL_stickerSet");
                Class<?> packCls = Class.forName("org.telegram.tgnet.TLRPC$TL_stickerPack");
                File emojiDir = getBotCacheDir(app, "emoji");
                int nowSec = (int) (System.currentTimeMillis() / 1000L);
                int fetchedCount = 0;

                for (String shortName : packNames) {
                    if (fetchedCount >= 8) break;
                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("name", shortName);
                        JSONObject resp = botApiPost(token, "getStickerSet", payload);
                        if (resp == null || !resp.optBoolean("ok", false)) continue;
                        JSONObject res = resp.optJSONObject("result");
                        if (res == null) continue;
                        if (!"custom_emoji".equals(res.optString("sticker_type", ""))) continue;

                        JSONArray stickers = res.optJSONArray("stickers");
                        if (stickers == null || stickers.length() == 0) continue;

                        Object tlRes = resSetCls.getConstructor().newInstance();
                        Object tlSet = setCls.getConstructor().newInstance();
                        setCls.getField("id").setLong(tlSet, stableBotMediaId("emojiset_" + shortName));
                        setCls.getField("access_hash").setLong(tlSet, 0L);
                        setCls.getField("title").set(tlSet, res.optString("title", shortName));
                        setCls.getField("short_name").set(tlSet, shortName);
                        setCls.getField("emojis").setBoolean(tlSet, true);
                        setCls.getField("installed_date").setInt(tlSet, nowSec);
                        setCls.getField("official").setBoolean(tlSet, true);

                        @SuppressWarnings("unchecked")
                        ArrayList<Object> docs = (ArrayList<Object>) resSetCls.getField("documents").get(tlRes);
                        @SuppressWarnings("unchecked")
                        ArrayList<Object> packs = (ArrayList<Object>) resSetCls.getField("packs").get(tlRes);

                        int limit = Math.min(stickers.length(), 32);
                        for (int i = 0; i < limit; i++) {
                            JSONObject st = stickers.optJSONObject(i);
                            if (st == null) continue;
                            long docId = parseLongSafe(st.optString("custom_emoji_id", ""));
                            if (docId == 0L) {
                                docId = stableBotMediaId(st.optString("file_unique_id", shortName + "_" + i));
                            }
                            boolean anim = st.optBoolean("is_animated", false);
                            boolean vid = st.optBoolean("is_video", false);
                            String ext = anim ? ".tgs" : (vid ? ".webm" : ".webp");
                            String mime = anim ? "application/x-tgsticker" : (vid ? "video/webm" : "image/webp");
                            String fId = st.optString("file_id", "");
                            File dest = emojiDir != null ? new File(emojiDir, docId + ext) : null;
                            if (dest != null && (!dest.exists() || dest.length() <= 32) && !fId.isEmpty() && i < 16) {
                                downloadBotFileById(token, fId, dest);
                            }
                            String alt = st.optString("emoji", "😀");
                            Object tlDoc = buildSyntheticEmojiDocument(
                                    docId, mime,
                                    dest != null && dest.exists() ? (int) dest.length() : 2048,
                                    st.optInt("width", 512), st.optInt("height", 512),
                                    alt, shortName);
                            if (tlDoc != null) {
                                if (dest != null && dest.exists() && dest.length() > 32) {
                                    copyToCanonicalFileLoaderPath(account, tlDoc, dest);
                                }
                                if (docs != null) docs.add(tlDoc);
                                if (packs != null) {
                                    Object pk = packCls.getConstructor().newInstance();
                                    packCls.getField("emoticon").set(pk, alt);
                                    @SuppressWarnings("unchecked")
                                    ArrayList<Long> pkDocs = (ArrayList<Long>) packCls.getField("documents").get(pk);
                                    if (pkDocs != null) pkDocs.add(docId);
                                    packs.add(pk);
                                }
                            }
                        }
                        if (docs != null && !docs.isEmpty()) {
                            setCls.getField("count").setInt(tlSet, docs.size());
                            resSetCls.getField("set").set(tlRes, tlSet);
                            setsOut.add(tlRes);
                            fetchedCount++;
                        }
                    } catch (Throwable ignored) {}
                }
                if (!setsOut.isEmpty()) {
                    cachedBotEmojiPacks.put(account, new ArrayList<>(setsOut));
                }
            } catch (Throwable t) {
                Log.w(TAG, "fetchBotCustomEmojiPacks failed: " + t.getMessage());
            } finally {
                botEmojiPacksInFlight.remove(account);
            }
            mainHandler.post(() -> callback.onPacksLoaded(setsOut));
        });
    }

    /**
     * Converts Telegram Bot API JSON entities array (entities / caption_entities) into TLRPC.MessageEntity list,
     * preserving Premium custom_emoji IDs and all rich text formatting.
     */
    public static ArrayList<Object> parseBotEntitiesToTl(JSONArray entitiesArr, java.util.Collection<Long> outCustomEmojiIds) {
        ArrayList<Object> result = new ArrayList<>();
        if (entitiesArr == null || entitiesArr.length() == 0) return result;
        for (int i = 0; i < entitiesArr.length(); i++) {
            JSONObject eo = entitiesArr.optJSONObject(i);
            if (eo == null) continue;
            String type = eo.optString("type", "");
            int offset = eo.optInt("offset", 0);
            int length = eo.optInt("length", 0);
            if (type.isEmpty() || length <= 0 || offset < 0) continue;
            try {
                Object ent = null;
                switch (type) {
                    case "custom_emoji": {
                        long docId = parseLongSafe(eo.optString("custom_emoji_id", ""));
                        if (docId != 0L) {
                            Class<?> cls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityCustomEmoji");
                            ent = cls.getConstructor().newInstance();
                            cls.getField("document_id").setLong(ent, docId);
                            if (outCustomEmojiIds != null) outCustomEmojiIds.add(docId);
                        }
                        break;
                    }
                    case "bold":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityBold").getConstructor().newInstance();
                        break;
                    case "italic":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityItalic").getConstructor().newInstance();
                        break;
                    case "underline":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityUnderline").getConstructor().newInstance();
                        break;
                    case "strikethrough":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityStrike").getConstructor().newInstance();
                        break;
                    case "spoiler":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntitySpoiler").getConstructor().newInstance();
                        break;
                    case "code":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityCode").getConstructor().newInstance();
                        break;
                    case "pre": {
                        Class<?> cls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityPre");
                        ent = cls.getConstructor().newInstance();
                        cls.getField("language").set(ent, eo.optString("language", ""));
                        break;
                    }
                    case "text_link": {
                        Class<?> cls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityTextUrl");
                        ent = cls.getConstructor().newInstance();
                        cls.getField("url").set(ent, eo.optString("url", ""));
                        break;
                    }
                    case "blockquote":
                    case "expandable_blockquote": {
                        Class<?> cls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityBlockquote");
                        ent = cls.getConstructor().newInstance();
                        if ("expandable_blockquote".equals(type)) {
                            try {
                                cls.getField("collapsed").setBoolean(ent, true);
                                cls.getField("flags").setInt(ent, 1);
                            } catch (Throwable ignored) {}
                        }
                        break;
                    }
                    case "url":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityUrl").getConstructor().newInstance();
                        break;
                    case "mention":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityMention").getConstructor().newInstance();
                        break;
                    case "hashtag":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityHashtag").getConstructor().newInstance();
                        break;
                    case "cashtag":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityCashtag").getConstructor().newInstance();
                        break;
                    case "bot_command":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityBotCommand").getConstructor().newInstance();
                        break;
                    case "email":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityEmail").getConstructor().newInstance();
                        break;
                    case "phone_number":
                        ent = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityPhone").getConstructor().newInstance();
                        break;
                    case "text_mention": {
                        long uid = eo.optLong("user_id", 0L);
                        if (uid == 0L && eo.has("user")) {
                            JSONObject uo = eo.optJSONObject("user");
                            if (uo != null) uid = uo.optLong("id", 0L);
                        }
                        if (uid != 0L) {
                            Class<?> cls = Class.forName("org.telegram.tgnet.TLRPC$TL_messageEntityMentionName");
                            ent = cls.getConstructor().newInstance();
                            cls.getField("user_id").setLong(ent, uid);
                        }
                        break;
                    }
                    default:
                        break;
                }
                if (ent != null) {
                    ent.getClass().getField("offset").setInt(ent, offset);
                    ent.getClass().getField("length").setInt(ent, length);
                    result.add(ent);
                }
            } catch (Throwable ignored) {}
        }
        return result;
    }

    /**
     * Serializes a list of TLRPC.MessageEntity objects into a Telegram Bot API JSONArray
     * (used for outgoing Bot API sendMessage/editMessage AND local edit history storage).
     */
    public static JSONArray serializeTlEntitiesToBotApiJson(List<?> tlEntities) {
        if (tlEntities == null || tlEntities.isEmpty()) return null;
        JSONArray arr = new JSONArray();
        for (Object ent : tlEntities) {
            if (ent == null) continue;
            try {
                Class<?> cls = ent.getClass();
                String simple = cls.getSimpleName();
                int offset = cls.getField("offset").getInt(ent);
                int length = cls.getField("length").getInt(ent);
                if (length <= 0 || offset < 0) continue;

                JSONObject o = new JSONObject();
                o.put("offset", offset);
                o.put("length", length);

                if (simple.contains("CustomEmoji")) {
                    long docId = cls.getField("document_id").getLong(ent);
                    if (docId == 0L) continue;
                    o.put("type", "custom_emoji");
                    o.put("custom_emoji_id", String.valueOf(docId));
                } else if (simple.contains("Bold")) {
                    o.put("type", "bold");
                } else if (simple.contains("Italic")) {
                    o.put("type", "italic");
                } else if (simple.contains("Underline")) {
                    o.put("type", "underline");
                } else if (simple.contains("Strike")) {
                    o.put("type", "strikethrough");
                } else if (simple.contains("Spoiler")) {
                    o.put("type", "spoiler");
                } else if (simple.contains("Code")) {
                    o.put("type", "code");
                } else if (simple.contains("Pre")) {
                    o.put("type", "pre");
                    Object lang = cls.getField("language").get(ent);
                    if (lang instanceof String && !((String) lang).isEmpty()) {
                        o.put("language", (String) lang);
                    }
                } else if (simple.contains("TextUrl")) {
                    Object url = cls.getField("url").get(ent);
                    if (!(url instanceof String) || ((String) url).isEmpty()) continue;
                    o.put("type", "text_link");
                    o.put("url", (String) url);
                } else if (simple.contains("Blockquote")) {
                    boolean collapsed = false;
                    try { collapsed = cls.getField("collapsed").getBoolean(ent); } catch (Throwable ignored) {}
                    o.put("type", collapsed ? "expandable_blockquote" : "blockquote");
                } else if (simple.contains("MentionName")) {
                    long uid = 0L;
                    try {
                        java.lang.reflect.Field uf = cls.getField("user_id");
                        Object rawUid = uf.get(ent);
                        if (rawUid instanceof Number) {
                            uid = ((Number) rawUid).longValue();
                        } else if (rawUid != null) {
                            uid = rawUid.getClass().getField("user_id").getLong(rawUid);
                        }
                    } catch (Throwable ignored) {}
                    if (uid == 0L) continue;
                    o.put("type", "text_mention");
                    o.put("user_id", uid);
                    JSONObject u = new JSONObject();
                    u.put("id", uid);
                    o.put("user", u);
                } else if (simple.endsWith("Url")) {
                    o.put("type", "url");
                } else if (simple.endsWith("Mention")) {
                    o.put("type", "mention");
                } else if (simple.contains("Hashtag")) {
                    o.put("type", "hashtag");
                } else if (simple.contains("Cashtag")) {
                    o.put("type", "cashtag");
                } else if (simple.contains("BotCommand")) {
                    o.put("type", "bot_command");
                } else if (simple.contains("Email")) {
                    o.put("type", "email");
                } else if (simple.contains("Phone")) {
                    o.put("type", "phone_number");
                } else {
                    continue;
                }
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        return arr.length() > 0 ? arr : null;
    }

    public static String serializeTlEntitiesToJsonString(List<?> tlEntities) {
        JSONArray arr = serializeTlEntitiesToBotApiJson(tlEntities);
        return (arr != null && arr.length() > 0) ? arr.toString() : null;
    }

    /** Returns a copy of the Bot API entities JSONArray without custom_emoji entries (fallback when bot lacks custom emoji send rights). */
    public static JSONArray stripCustomEmojiEntitiesJson(JSONArray src) {
        if (src == null || src.length() == 0) return null;
        JSONArray out = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject o = src.optJSONObject(i);
            if (o != null && !"custom_emoji".equals(o.optString("type", ""))) {
                out.put(o);
            }
        }
        return out.length() > 0 ? out : null;
    }

    private static Object buildSyntheticEmojiDocument(long docId, String mimeType, int size,
                                                      int width, int height, String altEmoji) {
        return buildSyntheticEmojiDocument(docId, mimeType, size, width, height, altEmoji, null);
    }

    private static Object buildSyntheticEmojiDocument(long docId, String mimeType, int size,
                                                      int width, int height, String altEmoji, String setName) {
        try {
            Class<?> docCls = Class.forName("org.telegram.tgnet.TLRPC$TL_document");
            Class<?> imgAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeImageSize");
            Class<?> emojiAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeCustomEmoji");
            Class<?> emptySetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_inputStickerSetEmpty");
            Class<?> shortSetCls = Class.forName("org.telegram.tgnet.TLRPC$TL_inputStickerSetShortName");
            Class<?> fnAttrCls = Class.forName("org.telegram.tgnet.TLRPC$TL_documentAttributeFilename");
            Object doc = docCls.getConstructor().newInstance();
            docCls.getField("id").setLong(doc, docId);
            docCls.getField("dc_id").setInt(doc, 1);
            docCls.getField("access_hash").setLong(doc, 0L);
            docCls.getField("flags").setInt(doc, 0);
            // CRITICAL: TL_document.serializeToStream ALWAYS writes file_reference as a byte array.
            // Leaving file_reference == null causes NativeByteBuffer.writeByteArray(null) to shift
            // the stream and trigger TLParseException: can't parse magic ffb62b95 in Vector!
            docCls.getField("file_reference").set(doc, new byte[0]);
            String safeMime = mimeType != null ? mimeType : "image/webp";
            docCls.getField("mime_type").set(doc, safeMime);
            docCls.getField("size").setLong(doc, size);
            docCls.getField("date").setInt(doc, (int) (System.currentTimeMillis() / 1000L));
            @SuppressWarnings("unchecked")
            ArrayList<Object> attrs = (ArrayList<Object>) docCls.getField("attributes").get(doc);
            if (attrs != null) {
                Object sz = imgAttrCls.getConstructor().newInstance();
                imgAttrCls.getField("w").setInt(sz, width > 0 ? width : 512);
                imgAttrCls.getField("h").setInt(sz, height > 0 ? height : 512);
                attrs.add(sz);

                Object ea = emojiAttrCls.getConstructor().newInstance();
                emojiAttrCls.getField("alt").set(ea, (altEmoji != null && !altEmoji.isEmpty()) ? altEmoji : "😀");
                if (setName != null && !setName.trim().isEmpty()) {
                    Object sn = shortSetCls.getConstructor().newInstance();
                    shortSetCls.getField("short_name").set(sn, setName.trim());
                    emojiAttrCls.getField("stickerset").set(ea, sn);
                } else {
                    emojiAttrCls.getField("stickerset").set(ea, emptySetCls.getConstructor().newInstance());
                }
                attrs.add(ea);

                Object fn = fnAttrCls.getConstructor().newInstance();
                String ext = "application/x-tgsticker".equals(safeMime) ? ".tgs"
                        : ("video/webm".equals(safeMime) ? ".webm" : ".webp");
                fnAttrCls.getField("file_name").set(fn, "emoji_" + docId + ext);
                attrs.add(fn);
            }
            return doc;
        } catch (Throwable t) {
            Log.w(TAG, "buildSyntheticEmojiDocument failed: " + t.getMessage());
            return null;
        }
    }

    public interface BotEditCallback {
        void onResult(boolean ok, String errorDesc, String acceptedEntitiesJson);
    }

    public interface BotForwardCallback {
        void onResult(boolean ok, int[] sentMessageIds, int date, String errorDesc);
    }

    /**
     * Resolves exact Bot API candidate chat_id(s) for a Telegram dialog_id without misclassifying
     * modern basic groups (>1e9) or small supergroups/channels (<=1e9).
     */
    public static long[] resolveBotApiChatIdsForHint(long dialogId, Boolean isChannelHint) {
        return ColgramBotChatIdPolicy.resolveChatIds(dialogId, isChannelHint);
    }

    public static long[] resolveBotApiChatIds(int account, long dialogId) {
        if (dialogId >= 0L) {
            return new long[]{dialogId};
        }
        Boolean isChannel = null;
        try {
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);
            Object chat = mcClass.getMethod("getChat", Long.class).invoke(mc, -dialogId);
            if (chat != null) {
                String name = chat.getClass().getSimpleName();
                if (name.contains("channel") || name.contains("Channel")) {
                    isChannel = Boolean.TRUE;
                } else {
                    isChannel = Boolean.FALSE;
                }
            }
        } catch (Throwable ignored) {}
        return resolveBotApiChatIdsForHint(dialogId, isChannel);
    }

    private static boolean isNotModifiedResponse(JSONObject resp) {
        if (resp == null) return false;
        String desc = resp.optString("description", "").toLowerCase(java.util.Locale.US);
        return desc.contains("message is not modified");
    }

    private static boolean isChatNotFoundResponse(JSONObject resp) {
        if (resp == null) return false;
        String desc = resp.optString("description", "").toLowerCase(java.util.Locale.US);
        return desc.contains("chat not found") || desc.contains("peer_id_invalid");
    }

    private static String extractResultEntitiesJson(JSONObject resp, boolean isCaption) {
        if (resp == null) return null;
        JSONObject res = resp.optJSONObject("result");
        if (res == null) return null;
        JSONArray arr = res.optJSONArray(isCaption ? "caption_entities" : "entities");
        if (arr == null) arr = res.optJSONArray("entities");
        if (arr == null) arr = res.optJSONArray("caption_entities");
        return (arr != null && arr.length() > 0) ? arr.toString() : null;
    }

    /**
     * Edits a bot-authored message via Bot API (editMessageText / editMessageCaption),
     * bypassing MTProto BOT_METHOD_INVALID / MESSAGE_ID_INVALID on bot accounts.
     */
    public static void editBotMessage(final int account, final long dialogId, final int messageId,
                                      final String newText, final boolean isCaption, final Runnable onComplete) {
        editBotMessage(account, dialogId, messageId, newText, null, isCaption, false,
                (ok, err, entJson) -> { if (onComplete != null) onComplete.run(); });
    }

    public static void editBotMessage(final int account, final long dialogId, final int messageId,
                                      final String newText, final List<?> tlEntities, final boolean isCaption,
                                      final Runnable onComplete) {
        editBotMessage(account, dialogId, messageId, newText, tlEntities, isCaption, false,
                (ok, err, entJson) -> { if (onComplete != null) onComplete.run(); });
    }

    public static void editBotMessage(final int account, final long dialogId, final int messageId,
                                      final String newText, final List<?> tlEntities, final boolean isCaption,
                                      final boolean noWebpage, final BotEditCallback callback) {
        final JSONArray entitiesJson = serializeTlEntitiesToBotApiJson(tlEntities);
        executor.execute(() -> {
            boolean ok = false;
            String errDesc = "BOT_EDIT_FAILED";
            String acceptedEntJson = entitiesJson != null ? entitiesJson.toString() : null;
            try {
                String token = getBotToken(null, account);
                if (token == null || token.isEmpty()) {
                    errDesc = "NO_BOT_TOKEN";
                } else {
                    long[] chatIds = resolveBotApiChatIds(account, dialogId);
                    for (long chatId : chatIds) {
                        JSONObject payload = new JSONObject();
                        payload.put("chat_id", chatId);
                        payload.put("message_id", messageId);
                        String method;
                        String entKey;
                        if (isCaption) {
                            method = "editMessageCaption";
                            entKey = "caption_entities";
                            payload.put("caption", newText != null ? newText : "");
                        } else {
                            method = "editMessageText";
                            entKey = "entities";
                            payload.put("text", newText != null ? newText : "");
                            if (noWebpage) {
                                JSONObject lpo = new JSONObject();
                                lpo.put("is_disabled", true);
                                payload.put("link_preview_options", lpo);
                            }
                        }
                        if (entitiesJson != null && entitiesJson.length() > 0) {
                            payload.put(entKey, entitiesJson);
                        }
                        JSONObject resp = botApiPost(token, method, payload);
                        ok = (resp != null && resp.optBoolean("ok", false)) || isNotModifiedResponse(resp);
                        if (ok) {
                            String srvEnt = extractResultEntitiesJson(resp, isCaption);
                            if (srvEnt != null) acceptedEntJson = srvEnt;
                            break;
                        }
                        if (resp != null) {
                            errDesc = resp.optString("description", errDesc);
                            if (isChatNotFoundResponse(resp) && chatIds.length > 1) {
                                continue;
                            }
                        }
                        if (entitiesJson != null) {
                            JSONArray stripped = stripCustomEmojiEntitiesJson(entitiesJson);
                            if (stripped != null && stripped.length() > 0) {
                                payload.put(entKey, stripped);
                            } else {
                                payload.remove(entKey);
                            }
                            JSONObject retryResp = botApiPost(token, method, payload);
                            ok = (retryResp != null && retryResp.optBoolean("ok", false)) || isNotModifiedResponse(retryResp);
                            if (ok) {
                                String srvEnt = extractResultEntitiesJson(retryResp, isCaption);
                                acceptedEntJson = srvEnt != null ? srvEnt : (stripped != null ? stripped.toString() : null);
                                break;
                            }
                            if (retryResp != null) errDesc = retryResp.optString("description", errDesc);
                        }
                        if (isCaption) {
                            JSONObject fallback = new JSONObject();
                            fallback.put("chat_id", chatId);
                            fallback.put("message_id", messageId);
                            fallback.put("text", newText != null ? newText : "");
                            if (noWebpage) {
                                JSONObject lpo = new JSONObject();
                                lpo.put("is_disabled", true);
                                fallback.put("link_preview_options", lpo);
                            }
                            JSONArray fallbackEnt = stripCustomEmojiEntitiesJson(entitiesJson);
                            if (fallbackEnt != null && fallbackEnt.length() > 0) {
                                fallback.put("entities", fallbackEnt);
                            }
                            JSONObject resp2 = botApiPost(token, "editMessageText", fallback);
                            ok = (resp2 != null && resp2.optBoolean("ok", false)) || isNotModifiedResponse(resp2);
                            if (ok) {
                                String srvEnt = extractResultEntitiesJson(resp2, false);
                                acceptedEntJson = srvEnt != null ? srvEnt : (fallbackEnt != null ? fallbackEnt.toString() : null);
                                break;
                            }
                            if (resp2 != null) errDesc = resp2.optString("description", errDesc);
                        }
                    }
                }
            } catch (Throwable t) {
                errDesc = t.getMessage() != null ? t.getMessage() : errDesc;
                Log.w(TAG, "editBotMessage failed: " + errDesc);
            }
            final boolean fOk = ok;
            final String fErr = fOk ? null : errDesc;
            final String fEnt = acceptedEntJson;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(fOk, fErr, fEnt));
            }
        });
    }

    /**
     * Replaces photo/video/animation/audio/document media on an existing bot message via Bot API editMessageMedia.
     */
    public static void editBotMessageMedia(final Context context, final int account, final long dialogId,
                                           final int messageId, final File mediaFile, final boolean isVideo,
                                           final String caption, final List<?> tlEntities, final Runnable onComplete) {
        editBotMessageMedia(context, account, dialogId, messageId, mediaFile, isVideo ? "video" : "photo",
                caption, tlEntities, (ok, err, entJson) -> { if (onComplete != null) onComplete.run(); });
    }

    public static void editBotMessageMedia(final Context context, final int account, final long dialogId,
                                           final int messageId, final File mediaFile, final String mediaType,
                                           final String caption, final List<?> tlEntities, final BotEditCallback callback) {
        final JSONArray entitiesJson = serializeTlEntitiesToBotApiJson(tlEntities);
        final Context app = resolveAppContext(context);
        final String safeType = (mediaType != null && !mediaType.isEmpty()) ? mediaType : "photo";
        executor.execute(() -> {
            boolean ok = false;
            String errDesc = "BOT_EDIT_MEDIA_FAILED";
            String acceptedEntJson = entitiesJson != null ? entitiesJson.toString() : null;
            try {
                String token = getBotToken(app, account);
                if (token == null || token.isEmpty()) {
                    errDesc = "NO_BOT_TOKEN";
                } else if (mediaFile == null || !mediaFile.isFile() || mediaFile.length() <= 0) {
                    errDesc = "MEDIA_FILE_MISSING";
                } else {
                    long[] chatIds = resolveBotApiChatIds(account, dialogId);
                    for (long chatId : chatIds) {
                        JSONObject mediaObj = new JSONObject();
                        mediaObj.put("type", safeType);
                        mediaObj.put("media", "attach://file0");
                        if (caption != null && !caption.isEmpty()) {
                            mediaObj.put("caption", caption);
                        }
                        if (entitiesJson != null && entitiesJson.length() > 0) {
                            mediaObj.put("caption_entities", entitiesJson);
                        }
                        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
                        fields.put("chat_id", String.valueOf(chatId));
                        fields.put("message_id", String.valueOf(messageId));
                        fields.put("media", mediaObj.toString());
                        JSONObject resp = postBotMultipart(token, "editMessageMedia", fields, "file0", mediaFile);
                        ok = (resp != null && resp.optBoolean("ok", false)) || isNotModifiedResponse(resp);
                        if (ok) {
                            String srvEnt = extractResultEntitiesJson(resp, true);
                            if (srvEnt != null) acceptedEntJson = srvEnt;
                            break;
                        }
                        if (resp != null) {
                            errDesc = resp.optString("description", errDesc);
                            if (isChatNotFoundResponse(resp) && chatIds.length > 1) {
                                continue;
                            }
                        }
                        if (entitiesJson != null) {
                            JSONArray stripped = stripCustomEmojiEntitiesJson(entitiesJson);
                            if (stripped != null && stripped.length() > 0) {
                                mediaObj.put("caption_entities", stripped);
                            } else {
                                mediaObj.remove("caption_entities");
                            }
                            fields.put("media", mediaObj.toString());
                            JSONObject retryResp = postBotMultipart(token, "editMessageMedia", fields, "file0", mediaFile);
                            ok = (retryResp != null && retryResp.optBoolean("ok", false)) || isNotModifiedResponse(retryResp);
                            if (ok) {
                                String srvEnt = extractResultEntitiesJson(retryResp, true);
                                acceptedEntJson = srvEnt != null ? srvEnt : (stripped != null ? stripped.toString() : null);
                                break;
                            }
                            if (retryResp != null) errDesc = retryResp.optString("description", errDesc);
                        }
                    }
                }
            } catch (Throwable t) {
                errDesc = t.getMessage() != null ? t.getMessage() : errDesc;
                Log.w(TAG, "editBotMessageMedia failed: " + errDesc);
            }
            final boolean fOk = ok;
            final String fErr = fOk ? null : errDesc;
            final String fEnt = acceptedEntJson;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(fOk, fErr, fEnt));
            }
        });
    }

    private static JSONObject postBotMultipart(String token, String method, java.util.Map<String, String> fields,
                                               String fileField, File file) {
        String boundary = "----ColgramBoundary" + System.currentTimeMillis();
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/" + method);
            List<java.net.Proxy> rungs = transportLadder(url.getHost(), 443);
            for (java.net.Proxy rung : withBestRungRetry(rungs)) {
                HttpURLConnection conn = null;
                try {
                    conn = openVia(url, rung, 30000, Math.min(COLGRAM_CONNECT_TIMEOUT_MS, 5000));
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    try (OutputStream os = conn.getOutputStream()) {
                        for (java.util.Map.Entry<String, String> e : fields.entrySet()) {
                            String part = "--" + boundary + "\r\n"
                                    + "Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                                    + e.getValue() + "\r\n";
                            os.write(part.getBytes("UTF-8"));
                        }
                        String fileHdr = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + file.getName() + "\"\r\n"
                                + "Content-Type: application/octet-stream\r\n\r\n";
                        os.write(fileHdr.getBytes("UTF-8"));
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                        }
                        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                        os.flush();
                    }
                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    if (is != null) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                        }
                        if (code >= 200 && code < 300) ColgramEndpoints.noteSuccess(url.getHost(), 443);
                        return new JSONObject(sb.toString());
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "postBotMultipart error: " + t.getMessage());
        }
        return null;
    }

    /**
     * Sets or clears a standard or custom-emoji reaction on a message via Bot API setMessageReaction.
     */
    public static void sendBotReaction(final int account, final long dialogId, final int messageId,
                                       final String emoji, final boolean isBig, final Runnable onComplete) {
        sendBotReaction(account, dialogId, messageId, emoji, 0L, isBig, onComplete);
    }

    public static void sendBotReaction(final int account, final long dialogId, final int messageId,
                                       final String emoji, final long customEmojiDocId, final boolean isBig,
                                       final Runnable onComplete) {
        executor.execute(() -> {
            try {
                String token = getBotToken(null, account);
                if (token != null && !token.isEmpty()) {
                    long[] chatIds = resolveBotApiChatIds(account, dialogId);
                    for (long chatId : chatIds) {
                        JSONObject payload = new JSONObject();
                        payload.put("chat_id", chatId);
                        payload.put("message_id", messageId);
                        JSONArray reactionArr = new JSONArray();
                        if (customEmojiDocId != 0L) {
                            JSONObject r = new JSONObject();
                            r.put("type", "custom_emoji");
                            r.put("custom_emoji_id", String.valueOf(customEmojiDocId));
                            reactionArr.put(r);
                        } else if (emoji != null && !emoji.trim().isEmpty()) {
                            JSONObject r = new JSONObject();
                            r.put("type", "emoji");
                            r.put("emoji", emoji.trim());
                            reactionArr.put(r);
                        }
                        payload.put("reaction", reactionArr);
                        if (isBig) {
                            payload.put("is_big", true);
                        }
                        JSONObject resp = botApiPost(token, "setMessageReaction", payload);
                        if (resp != null && resp.optBoolean("ok", false)) {
                            break;
                        }
                        if (!isChatNotFoundResponse(resp)) {
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "sendBotReaction failed: " + t.getMessage());
            }
            if (onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
    }

    /** Deletes one or more messages via Bot API deleteMessages / deleteMessage fallback. */
    public static void deleteBotMessages(final Context context, final int account, final long dialogId,
                                         final List<Integer> msgIds) {
        if (msgIds == null || msgIds.isEmpty() || dialogId == 0) return;
        final Context app = resolveAppContext(context);
        final ArrayList<Integer> copy = new ArrayList<>(msgIds);
        executor.execute(() -> {
            try {
                String token = getBotToken(app, account);
                if (token == null || token.isEmpty()) return;
                long[] chatIds = resolveBotApiChatIds(account, dialogId);
                for (long chatId : chatIds) {
                    if (copy.size() == 1) {
                        JSONObject p = new JSONObject();
                        p.put("chat_id", chatId);
                        p.put("message_id", copy.get(0));
                        JSONObject resp = botApiPost(token, "deleteMessage", p);
                        if (resp != null && resp.optBoolean("ok", false)) return;
                        if (!isChatNotFoundResponse(resp)) return;
                    } else {
                        JSONObject p = new JSONObject();
                        p.put("chat_id", chatId);
                        JSONArray ids = new JSONArray();
                        for (Integer id : copy) if (id != null && id > 0) ids.put(id);
                        p.put("message_ids", ids);
                        JSONObject resp = botApiPost(token, "deleteMessages", p);
                        if (resp != null && resp.optBoolean("ok", false)) return;
                        if (!isChatNotFoundResponse(resp)) {
                            for (Integer id : copy) {
                                if (id == null || id <= 0) continue;
                                JSONObject single = new JSONObject();
                                single.put("chat_id", chatId);
                                single.put("message_id", id);
                                botApiPost(token, "deleteMessage", single);
                            }
                            return;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "deleteBotMessages failed: " + t.getMessage());
            }
        });
    }

    /** Pins or unpins a single message via Bot API pinChatMessage / unpinChatMessage. */
    public static void pinBotMessage(final Context context, final int account, final long dialogId,
                                     final int messageId, final boolean unpin, final boolean notify,
                                     final Runnable onSuccess) {
        final Context app = resolveAppContext(context);
        executor.execute(() -> {
            boolean ok = false;
            try {
                String token = getBotToken(app, account);
                if (token != null && !token.isEmpty()) {
                    long[] chatIds = resolveBotApiChatIds(account, dialogId);
                    for (long chatId : chatIds) {
                        JSONObject p = new JSONObject();
                        p.put("chat_id", chatId);
                        p.put("message_id", messageId);
                        if (!unpin) {
                            p.put("disable_notification", !notify);
                        }
                        JSONObject resp = botApiPost(token, unpin ? "unpinChatMessage" : "pinChatMessage", p);
                        if (resp != null && resp.optBoolean("ok", false)) {
                            ok = true;
                            break;
                        }
                        if (!isChatNotFoundResponse(resp)) break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "pinBotMessage failed: " + t.getMessage());
            }
            if (ok && onSuccess != null) {
                mainHandler.post(onSuccess);
            }
        });
    }

    /** Unpins all messages in a chat via Bot API unpinAllChatMessages. */
    public static void unpinAllBotMessages(final Context context, final int account, final long dialogId,
                                           final Runnable onSuccess) {
        final Context app = resolveAppContext(context);
        executor.execute(() -> {
            boolean ok = false;
            try {
                String token = getBotToken(app, account);
                if (token != null && !token.isEmpty()) {
                    long[] chatIds = resolveBotApiChatIds(account, dialogId);
                    for (long chatId : chatIds) {
                        JSONObject p = new JSONObject();
                        p.put("chat_id", chatId);
                        JSONObject resp = botApiPost(token, "unpinAllChatMessages", p);
                        if (resp != null && resp.optBoolean("ok", false)) {
                            ok = true;
                            break;
                        }
                        if (!isChatNotFoundResponse(resp)) break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "unpinAllBotMessages failed: " + t.getMessage());
            }
            if (ok && onSuccess != null) {
                mainHandler.post(onSuccess);
            }
        });
    }

    /** Forwards or copies messages via Bot API forwardMessage / copyMessage when MTProto rejects bot peers. */
    public static void forwardBotMessages(final Context context, final int account, final long fromDialogId,
                                          final long toDialogId, final List<Integer> msgIds, final boolean dropAuthor,
                                          final BotForwardCallback callback) {
        final Context app = resolveAppContext(context);
        final ArrayList<Integer> idsCopy = msgIds != null ? new ArrayList<>(msgIds) : new ArrayList<>();
        executor.execute(() -> {
            int[] sent = new int[idsCopy.size()];
            int sentDate = (int) (System.currentTimeMillis() / 1000L);
            boolean anyOk = false;
            String lastErr = "BOT_FORWARD_FAILED";
            try {
                String token = getBotToken(app, account);
                if (token != null && !token.isEmpty() && !idsCopy.isEmpty()) {
                    long[] fromIds = resolveBotApiChatIds(account, fromDialogId);
                    long[] toIds = resolveBotApiChatIds(account, toDialogId);
                    String method = dropAuthor ? "copyMessage" : "forwardMessage";
                    for (int idx = 0; idx < idsCopy.size(); idx++) {
                        int mid = idsCopy.get(idx);
                        boolean itemOk = false;
                        outer:
                        for (long toCid : toIds) {
                            for (long fromCid : fromIds) {
                                JSONObject p = new JSONObject();
                                p.put("chat_id", toCid);
                                p.put("from_chat_id", fromCid);
                                p.put("message_id", mid);
                                JSONObject resp = botApiPost(token, method, p);
                                if (resp != null && resp.optBoolean("ok", false)) {
                                    JSONObject res = resp.optJSONObject("result");
                                    int newMid = res != null ? res.optInt("message_id", 0) : 0;
                                    if (res != null && res.has("date")) sentDate = res.optInt("date", sentDate);
                                    sent[idx] = newMid;
                                    if (newMid > 0) {
                                        dispatchedLiveMsgKeys.add(account + ":" + toDialogId + ":" + newMid);
                                    }
                                    itemOk = true;
                                    anyOk = true;
                                    break outer;
                                }
                                if (resp != null) {
                                    lastErr = resp.optString("description", lastErr);
                                    if (!isChatNotFoundResponse(resp)) break outer;
                                }
                            }
                        }
                        if (!itemOk) break;
                    }
                }
            } catch (Throwable t) {
                lastErr = t.getMessage() != null ? t.getMessage() : lastErr;
            }
            final boolean fOk = anyOk;
            final int fDate = sentDate;
            final String fErr = fOk ? null : lastErr;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(fOk, sent, fDate, fErr));
            }
        });
    }

    /** Sends photo/video/animation/audio/voice/document media via Bot API when MTProto rejects bot peers. */
    public static void sendMediaViaBotApi(
            final Context context,
            final int account,
            final long dialogId,
            final File mediaFile,
            final String mediaType,
            final String caption,
            final List<?> tlEntities,
            final int replyToMessageId,
            final BotSendCallback callback
    ) {
        final Context app = resolveAppContext(context);
        final String token = getBotToken(app, account);
        final JSONArray entitiesJson = serializeTlEntitiesToBotApiJson(tlEntities);
        if (token == null || token.isEmpty() || mediaFile == null || !mediaFile.isFile() || dialogId == 0) {
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, 0, 0, "BOT_MEDIA_INVALID"));
            }
            return;
        }
        final String mt = mediaType != null ? mediaType.toLowerCase(java.util.Locale.US) : "document";
        final String method;
        final String fileField;
        switch (mt) {
            case "photo": method = "sendPhoto"; fileField = "photo"; break;
            case "video": method = "sendVideo"; fileField = "video"; break;
            case "animation": method = "sendAnimation"; fileField = "animation"; break;
            case "audio": method = "sendAudio"; fileField = "audio"; break;
            case "voice": method = "sendVoice"; fileField = "voice"; break;
            default: method = "sendDocument"; fileField = "document"; break;
        }
        executor.execute(() -> {
            long[] chatIds = resolveBotApiChatIds(account, dialogId);
            String lastErr = "BOT_API_SEND_MEDIA_FAILED";
            for (long chatId : chatIds) {
                try {
                    java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
                    fields.put("chat_id", String.valueOf(chatId));
                    if (caption != null && !caption.isEmpty()) fields.put("caption", caption);
                    if (entitiesJson != null && entitiesJson.length() > 0) {
                        fields.put("caption_entities", entitiesJson.toString());
                    }
                    if (replyToMessageId > 0) {
                        fields.put("reply_to_message_id", String.valueOf(replyToMessageId));
                    }
                    JSONObject resp = postBotMultipart(token, method, fields, fileField, mediaFile);
                    if ((resp == null || !resp.optBoolean("ok", false)) && entitiesJson != null) {
                        JSONArray stripped = stripCustomEmojiEntitiesJson(entitiesJson);
                        if (stripped != null && stripped.length() > 0) {
                            fields.put("caption_entities", stripped.toString());
                        } else {
                            fields.remove("caption_entities");
                        }
                        resp = postBotMultipart(token, method, fields, fileField, mediaFile);
                    }
                    if (resp != null && resp.optBoolean("ok", false)) {
                        JSONObject res = resp.optJSONObject("result");
                        int msgId = res != null ? res.optInt("message_id", 0) : 0;
                        int date = res != null ? res.optInt("date", (int) (System.currentTimeMillis() / 1000L))
                                : (int) (System.currentTimeMillis() / 1000L);
                        if (msgId > 0) {
                            dispatchedLiveMsgKeys.add(account + ":" + dialogId + ":" + msgId);
                            final int fId = msgId;
                            final int fDate = date;
                            if (callback != null) {
                                mainHandler.post(() -> callback.onResult(true, fId, fDate, null));
                            }
                            return;
                        }
                    } else if (resp != null) {
                        lastErr = resp.optString("description", lastErr);
                        if (!isChatNotFoundResponse(resp)) break;
                    }
                } catch (Throwable t) {
                    lastErr = t.getMessage() != null ? t.getMessage() : lastErr;
                }
            }
            final String finalErr = lastErr;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, 0, 0, finalErr));
            }
        });
    }

    private static long botApiChatIdFromDialogId(long dialogId) {
        return resolveBotApiChatIdsForHint(dialogId, null)[0];
    }

    private static boolean downloadBotFileById(String token, String fileId, File destFile) {
        return downloadBotFileById(token, fileId, destFile, 15000);
    }

    private static boolean downloadBotFileById(String token, String fileId, File destFile, int readTimeoutMs) {
        if (token == null || token.isEmpty() || fileId == null || fileId.isEmpty() || destFile == null) {
            return false;
        }
        try {
            JSONObject req = new JSONObject();
            req.put("file_id", fileId);
            JSONObject resp = botApiPost(token, "getFile", req, readTimeoutMs);
            if (resp == null || !resp.optBoolean("ok", false)) {
                return false;
            }
            JSONObject res = resp.optJSONObject("result");
            String filePath = res != null ? res.optString("file_path", "") : "";
            if (filePath.isEmpty()) return false;

            String urlStr = "https://api.telegram.org/file/bot" + token + "/" + filePath;
            URL url = new URL(urlStr);
            List<java.net.Proxy> rungs = transportLadder(url.getHost(), 443);
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(destFile.getAbsolutePath() + ".tmp");
            for (java.net.Proxy rung : withBestRungRetry(rungs)) {
                HttpURLConnection conn = null;
                try {
                    conn = openVia(url, rung, readTimeoutMs, Math.min(COLGRAM_CONNECT_TIMEOUT_MS, 4000));
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        try (InputStream is = conn.getInputStream();
                             FileOutputStream fos = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[8192];
                            int read;
                            int total = 0;
                            while ((read = is.read(buf)) != -1) {
                                fos.write(buf, 0, read);
                                total += read;
                            }
                            fos.flush();
                            if (total > 32) {
                                if (destFile.exists()) destFile.delete();
                                if (tmp.renameTo(destFile)) {
                                    ColgramEndpoints.noteSuccess(url.getHost(), 443);
                                    return true;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    if (tmp.exists()) tmp.delete();
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "downloadBotFileById error: " + t.getMessage());
        }
        return false;
    }

    public interface BotSendCallback {
        void onResult(boolean ok, int messageId, int date, String error);
    }

    /**
     * Called when a ChatActivity opens on a bot account so background polling / MTProto catch-up
     * runs immediately and any newly sent incoming messages appear without manual refresh.
     */
    public static void onBotChatOpened(final Context context, final int account) {
        if (context == null) return;
        final Context app = resolveAppContext(context);
        if (app == null) return;
        if (!isMtprotoBotAccount(account) && getBotToken(app, account).isEmpty()) {
            return;
        }
        syncBotDialogs(app, account, false);
    }

    /**
     * Fallback delivery over Telegram Bot API sendMessage when MTProto lacks a peer's access_hash
     * (e.g. users/groups first discovered through Bot API getUpdates).
     */
    public static void sendTextMessageViaBotApi(
            final Context context,
            final int account,
            final long dialogId,
            final String text,
            final int replyToMessageId,
            final BotSendCallback callback
    ) {
        sendTextMessageViaBotApi(context, account, dialogId, text, null, replyToMessageId, callback);
    }

    public static void sendTextMessageViaBotApi(
            final Context context,
            final int account,
            final long dialogId,
            final String text,
            final List<?> tlEntities,
            final int replyToMessageId,
            final BotSendCallback callback
    ) {
        final Context app = resolveAppContext(context);
        final String token = getBotToken(app, account);
        final JSONArray entitiesJson = serializeTlEntitiesToBotApiJson(tlEntities);
        if (token.isEmpty() || text == null || text.isEmpty() || dialogId == 0) {
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, 0, 0, "NO_BOT_TOKEN"));
            }
            return;
        }
        executor.execute(() -> {
            long[] candidateChatIds = resolveBotApiChatIds(account, dialogId);
            String lastErr = "BOT_API_SEND_FAILED";
            for (long chatId : candidateChatIds) {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("chat_id", chatId);
                    payload.put("text", text);
                    if (entitiesJson != null && entitiesJson.length() > 0) {
                        payload.put("entities", entitiesJson);
                    }
                    if (replyToMessageId > 0) {
                        payload.put("reply_to_message_id", replyToMessageId);
                    }
                    JSONObject resp = botApiPost(token, "sendMessage", payload);
                    if ((resp == null || !resp.optBoolean("ok", false)) && entitiesJson != null) {
                        JSONArray stripped = stripCustomEmojiEntitiesJson(entitiesJson);
                        if (stripped != null && stripped.length() > 0) {
                            payload.put("entities", stripped);
                        } else {
                            payload.remove("entities");
                        }
                        resp = botApiPost(token, "sendMessage", payload);
                    }
                    if (resp != null && resp.optBoolean("ok", false)) {
                        JSONObject res = resp.optJSONObject("result");
                        int msgId = res != null ? res.optInt("message_id", 0) : 0;
                        int date = res != null ? res.optInt("date", (int) (System.currentTimeMillis() / 1000L))
                                : (int) (System.currentTimeMillis() / 1000L);
                        if (msgId > 0) {
                            dispatchedLiveMsgKeys.add(account + ":" + dialogId + ":" + msgId);
                            final int fId = msgId;
                            final int fDate = date;
                            if (callback != null) {
                                mainHandler.post(() -> callback.onResult(true, fId, fDate, null));
                            }
                            return;
                        }
                    } else if (resp != null) {
                        lastErr = resp.optString("description", lastErr);
                    }
                } catch (Throwable t) {
                    lastErr = t.getMessage() != null ? t.getMessage() : lastErr;
                }
            }
            final String finalErr = lastErr;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, 0, 0, finalErr));
            }
        });
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
                final String uname = query;
                final Object fTarget = targetFragment;
                executor.execute(() -> {
                    long resolvedChatId = 0L;
                    boolean isChannel = false;
                    try {
                        String token = getBotToken(activity, account);
                        if (token != null && !token.isEmpty()) {
                            JSONObject p = new JSONObject();
                            p.put("chat_id", "@" + uname);
                            JSONObject resp = botApiPost(token, "getChat", p);
                            if (resp != null && resp.optBoolean("ok", false)) {
                                JSONObject res = resp.optJSONObject("result");
                                if (res != null) {
                                    long cid = res.optLong("id", 0L);
                                    if (cid != 0L) {
                                        if (isSupergroupOrChannel(cid)) {
                                            resolvedChatId = channelIdFromBotApi(cid);
                                            isChannel = true;
                                        } else {
                                            resolvedChatId = cid;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    final long fId = resolvedChatId;
                    final boolean fChan = isChannel;
                    mainHandler.post(() -> {
                        try {
                            if (fId != 0L) {
                                Bundle args = new Bundle();
                                if (fChan || fId < 0) args.putLong("chat_id", Math.abs(fId));
                                else args.putLong("user_id", fId);
                                Constructor<?> ctor = chatActivityClass.getConstructor(Bundle.class);
                                Object chatFrag = ctor.newInstance(args);
                                if (fTarget != null) {
                                    baseFragmentClass.getMethod("presentFragment", baseFragmentClass).invoke(fTarget, chatFrag);
                                } else if (launchActivityClass.isInstance(activity)) {
                                    launchActivityClass.getMethod("presentFragment", baseFragmentClass).invoke(activity, chatFrag);
                                }
                                return;
                            }
                            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);
                            Method openByUserName = mcClass.getMethod("openByUserName", String.class, baseFragmentClass, int.class);
                            openByUserName.invoke(mc, uname, fTarget, 1);
                        } catch (Throwable err) {
                            Log.e(TAG, "openChatAsBot fallback error", err);
                        }
                    });
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "openChatAsBot error", t);
            Toast.makeText(activity, "Ошибка открытия чата: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
