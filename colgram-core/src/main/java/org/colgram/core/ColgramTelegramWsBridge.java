package org.colgram.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Direct MTProto-over-WebSocket TLS bridge (`wss://.../apiws`) inside Colgram's local gateway.
 *
 * When a carrier drops raw TCP SYN or MTProto traffic to Telegram's DC endpoints, Telegram's own
 * official WebSocket gateways (`/apiws`) accept standard obfuscated2 streams inside TLS WebSocket
 * binary frames once the 64-byte obfuscated2 header carries the target DC id in bytes `[60..61]`.
 * Connecting directly to Telegram's official edge verifies Telegram's TLS certificate and uses zero
 * third-party proxies.
 */
public final class ColgramTelegramWsBridge {

    private static final String TAG = "ColgramWsBridge";
    private static final String DEFAULT_EDGE_IP = "149.154.167.220";
    private static final String[] SHARED_EDGE_HOSTS_DC2 = {
            "kws2.web.telegram.org",
            "kws2-1.web.telegram.org",
            "zws2.web.telegram.org",
            "venus.web.telegram.org"
    };
    private static final String[] SHARED_EDGE_HOSTS_DC4 = {
            "kws4.web.telegram.org",
            "vesta.web.telegram.org",
            "kws4-1.web.telegram.org",
            "zws4.web.telegram.org"
    };
    private static final String[] ALL_EDGE_HOSTS = {
            "kws2.web.telegram.org",
            "kws2-1.web.telegram.org",
            "zws2.web.telegram.org",
            "venus.web.telegram.org",
            "vesta.web.telegram.org",
            "kws4.web.telegram.org",
            "kws4-1.web.telegram.org",
            "zws4.web.telegram.org",
            "kws1.web.telegram.org",
            "kws1-1.web.telegram.org",
            "zws1.web.telegram.org",
            "pluto.web.telegram.org",
            "kws3.web.telegram.org",
            "kws3-1.web.telegram.org",
            "zws3.web.telegram.org",
            "aurora.web.telegram.org",
            "kws5.web.telegram.org",
            "kws5-1.web.telegram.org",
            "zws5.web.telegram.org",
            "flora.web.telegram.org"
    };
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String[] WHITELIST_SPOOF_SNIS = ColgramPolymorphicStealth.WHITELIST_BENIGN_SNIS;
    private static final String[] VERIFIED_WSS_IPS_DC1_DC3 = {
            "149.154.174.100",
            "149.154.174.200",
            "149.154.175.209",
            "2001:b28:f23d:8005:7:0:109:337",
            "2001:b28:f23d:8005:7:0:109:338"
    };
    private static final String[] VERIFIED_WSS_IPS_DC2_DC4 = {
            "149.154.167.220",
            "2001:67c:4e8:f004::9"
    };
    private static final String[] VERIFIED_WSS_IPS_DC5 = {
            "149.154.170.100",
            "149.154.170.200",
            "149.154.170.96",
            "2001:b28:f23f:9::852:437",
            "2001:b28:f23f:9::852:438"
    };
    private static final String[] VERIFIED_WSS_IPS_ALL_PRIMARY = {
            "149.154.167.220",
            "149.154.174.100",
            "149.154.170.100",
            "2001:67c:4e8:f004::9",
            "2001:b28:f23d:8005:7:0:109:337",
            "2001:b28:f23f:9::852:437"
    };
    private static final int CONNECT_ATTEMPT_TIMEOUT_MS = 900;
    private static final int CONNECT_RACE_BUDGET_MS = 1150;
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 1700;
    private static final long MAX_DIAL_WALL_MS = 3500L;
    private static final int CELLULAR_CONNECT_ATTEMPT_TIMEOUT_MS = 1100;
    private static final int CELLULAR_CONNECT_RACE_BUDGET_MS = 1450;
    private static final int CELLULAR_HANDSHAKE_READ_TIMEOUT_MS = 2100;
    private static final long CELLULAR_MAX_DIAL_WALL_MS = 3200L;
    private static volatile boolean cellularNetwork = false;

    public static void setCellularNetwork(boolean cellular) {
        if (cellularNetwork != cellular) {
            cellularNetwork = cellular;
            preferredFragMode = cellular ? cellularPreferredFragMode : wifiPreferredFragMode;
            spoofSniCursor.set(cellular ? cellularSniCursor : wifiSniCursor);
            PREWARMED_POOL.clearAll();
        }
    }

    public static boolean isCellularNetwork() {
        return cellularNetwork;
    }

    private static int connectAttemptTimeoutMs() {
        return cellularNetwork ? CELLULAR_CONNECT_ATTEMPT_TIMEOUT_MS : CONNECT_ATTEMPT_TIMEOUT_MS;
    }

    private static int connectRaceBudgetMs() {
        return cellularNetwork ? CELLULAR_CONNECT_RACE_BUDGET_MS : CONNECT_RACE_BUDGET_MS;
    }

    private static int handshakeReadTimeoutMs() {
        return cellularNetwork ? CELLULAR_HANDSHAKE_READ_TIMEOUT_MS : HANDSHAKE_READ_TIMEOUT_MS;
    }

    public static long maxDialWallMs() {
        return cellularNetwork ? CELLULAR_MAX_DIAL_WALL_MS : MAX_DIAL_WALL_MS;
    }

    /** Returns live-verified official Telegram WSS edge IPs (IPv4 + IPv6) for DC 1..5. */
    public static String[] getVerifiedWssEdgeIpsForDc(int dc) {
        switch (dc) {
            case 1:
            case 3:
                return VERIFIED_WSS_IPS_DC1_DC3.clone();
            case 2:
            case 4:
                return VERIFIED_WSS_IPS_DC2_DC4.clone();
            case 5:
                return VERIFIED_WSS_IPS_DC5.clone();
            default:
                return VERIFIED_WSS_IPS_ALL_PRIMARY.clone();
        }
    }
    private static final long DNS_CACHE_TTL_MS = 300_000L;
    private static final long DNS_RETRY_BACKOFF_MS = 15_000L;
    private static final long FAILURE_COOLDOWN_MS = 1800L;
    private static final long BURST_FAILURE_WINDOW_MS = 800L;
    private static final long DEPRIORITIZE_TTL_MS = 45_000L;
    private static final long DEFAULT_EDGE_DEPRIORITIZE_TTL_MS = 8_000L;
    private static final int MAX_OUTBOUND_FRAME_BYTES = 65500;

    private static ExecutorService createWsDaemonPool(int maxThreads, final String prefix,
                                                      final String errLabel, final int androidPriority) {
        return new java.util.concurrent.ThreadPoolExecutor(
                0, maxThreads, 90L, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(() -> {
                        ColgramDpiBypass.setThreadPriorityQuiet(androidPriority);
                        r.run();
                    }, prefix);
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((th, ex) -> Log.w(TAG, errLabel + ex));
                    return t;
                });
    }

    private static final ExecutorService RACE_POOL = createWsDaemonPool(
            64, "colgram-ws-dial", "ws dial err: ", android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
    private static final ExecutorService TCP_RACE_POOL = createWsDaemonPool(
            96, "colgram-ws-tcp", "ws tcp err: ", android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);

    static final class EdgeEndpoint {
        final String ip;
        final String sniHost;

        EdgeEndpoint(String ip, String sniHost) {
            this.ip = ip;
            this.sniHost = sniHost;
        }

        String key() {
            return ip + "|" + sniHost;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EdgeEndpoint)) return false;
            EdgeEndpoint other = (EdgeEndpoint) o;
            return ip.equals(other.ip) && sniHost.equals(other.sniHost);
        }

        @Override
        public int hashCode() {
            return 31 * ip.hashCode() + sniHost.hashCode();
        }

        @Override
        public String toString() {
            return sniHost + "@" + ip;
        }
    }

    private static final EdgeEndpoint DEFAULT_ENDPOINT =
            new EdgeEndpoint(DEFAULT_EDGE_IP, "kws2.web.telegram.org");

    private static final int FRAG_NONE = 0;
    private static final int FRAG_TCP_SNI = 1;
    private static final int FRAG_TLS_RECORD_SNI = 2;
    private static final int FRAG_NATIVE_DISORDER_TLSREC = 3;
    private static final int FRAG_NATIVE_FAKE_TTL5_TLSREC = 4;
    private static final int FRAG_NATIVE_OOB_DISORDER_TLSREC = 5;
    private static final int FRAG_NATIVE_FAKE_TTL3_TLSREC = 6;
    private static final int FRAG_NATIVE_MSS_CLAMP_TLSREC = 7;
    private static final int FRAG_NATIVE_FAKE_TTL8_TLSREC = 8;
    private static final int FRAG_NATIVE_MSS64_OOB1_TLSREC = 9;
    private static final int FRAG_NATIVE_OOB1_ONLY_TLSREC = 10;
    private static final int FRAG_SPOOF_SNI_PLAIN = 11;
    private static final int FRAG_SPOOF_SNI_TLSREC = 12;
    private static final int FRAG_NO_SNI_TLSREC = 13;
    private static final int FRAG_NATIVE_MULTI_SPLIT_TLSREC = 14;
    private static final int FRAG_SPOOF_SNI_ALT = 15;
    private static final int FRAG_TCP_2BYTE_SNI = 16;
    private static final int FRAG_NATIVE_COMPACT_4_RECORD = 17;
    private static final int FRAG_NATIVE_HDR2_SNI2_BULK = 18;

    private static boolean isNativeFragMode(int mode) {
        return (mode >= FRAG_NATIVE_DISORDER_TLSREC && mode <= FRAG_NATIVE_OOB1_ONLY_TLSREC)
                || mode == FRAG_NATIVE_MULTI_SPLIT_TLSREC
                || mode == FRAG_NATIVE_COMPACT_4_RECORD
                || mode == FRAG_NATIVE_HDR2_SNI2_BULK;
    }

    private static boolean isSpoofSniFragMode(int mode) {
        return mode == FRAG_SPOOF_SNI_PLAIN
                || mode == FRAG_SPOOF_SNI_TLSREC
                || mode == FRAG_SPOOF_SNI_ALT;
    }

    private static int defaultFragModeForNetwork(boolean cellular) {
        // Prefer plain spoofed RU-whitelist SNI on Wi-Fi (0 fragment writes) and 2-record TLS SNI
        // split on stricter cellular TSPU DPI; cold-start Wave-1 races both automatically.
        return cellular ? FRAG_SPOOF_SNI_TLSREC : FRAG_SPOOF_SNI_PLAIN;
    }

    private static final int DC_BUCKET_COUNT = 12; // 0=other, 1..5=generic DC1..5, 6..10=media DC1..5
    private static final int DIAL_SLOTS_PER_BUCKET = 12;
    private static final long DIAL_SLOT_ACQUIRE_WAIT_MS = 2200L;
    private static final SecureRandom SHARED_MASK_SEEDER = new SecureRandom();
    static final int PREWARM_MAX_PER_BUCKET = 3;
    static final int PREWARM_MEDIA_MAX_PER_BUCKET = 4;
    static final long PREWARM_TTL_MS = 38_000L;
    static final long PREWARM_REFRESH_MS = 28_000L;
    private static final long DC135_DEAD_COOLDOWN_MS = 12_000L;
    private static final long DC135_MAX_BACKOFF_MS = 30_000L;
    private static final int DC135_WIFI_CONNECT_TIMEOUT_MS = 1200;
    private static final int DC135_CELLULAR_CONNECT_TIMEOUT_MS = 1600;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final int[] PREWARM_TARGET_DCS = {2, 4, -2, -4};
    private static final ExecutorService PREWARM_POOL = createWsDaemonPool(
            8, "colgram-ws-prewarm", "ws prewarm err: ", android.os.Process.THREAD_PRIORITY_BACKGROUND);

    private static final PrewarmedWsPool PREWARMED_POOL = new PrewarmedWsPool();
    private static volatile boolean prewarmEnabled = true;

    private static final java.util.concurrent.atomic.AtomicReferenceArray<EdgeEndpoint> lastWorkingByDc =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(DC_BUCKET_COUNT);
    private static volatile int wifiPreferredFragMode = defaultFragModeForNetwork(false);
    private static volatile int cellularPreferredFragMode = defaultFragModeForNetwork(true);
    private static volatile int preferredFragMode = defaultFragModeForNetwork(false);
    private static volatile int wifiSniCursor = 0;
    private static volatile int cellularSniCursor = 0;
    private static volatile boolean workedOnNetwork = false;
    private static volatile long resolvedAt = 0L;
    private static final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private static final AtomicInteger spoofSniCursor = new AtomicInteger(0);
    private static final Map<String, List<String>> perHostIps = new ConcurrentHashMap<>();
    private static final Map<String, Long> deprioritizedUntil = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicIntegerArray consecutiveFailuresByDc =
            new java.util.concurrent.atomic.AtomicIntegerArray(DC_BUCKET_COUNT);
    private static final java.util.concurrent.atomic.AtomicLongArray cooldownUntilByDc =
            new java.util.concurrent.atomic.AtomicLongArray(DC_BUCKET_COUNT);
    private static final AtomicLongArray dc135DeadUntil = new AtomicLongArray(DC_BUCKET_COUNT);
    private static final java.util.concurrent.atomic.AtomicIntegerArray dc135DeadStreak =
            new java.util.concurrent.atomic.AtomicIntegerArray(DC_BUCKET_COUNT);
    private static final AtomicInteger consecutiveTransportFailures = new AtomicInteger(0);
    private static final AtomicLongArray lastBurstFailAtByDc = new AtomicLongArray(DC_BUCKET_COUNT);

    static int prewarmMaxForBucket(int bucket) {
        return (bucket >= 6 && bucket <= 10) ? PREWARM_MEDIA_MAX_PER_BUCKET : PREWARM_MAX_PER_BUCKET;
    }

    static int prewarmMaxFor(int normDc, boolean media) {
        return media ? PREWARM_MEDIA_MAX_PER_BUCKET : PREWARM_MAX_PER_BUCKET;
    }
    private static final AtomicLongArray lastSuccessAtByDc = new AtomicLongArray(DC_BUCKET_COUNT);
    private static final Semaphore[] dialSlotsByDc = createDcSemaphores();
    private static final AtomicInteger lastHandshakeRttMs = new AtomicInteger(-1);
    private static volatile String lastWorkingEdgeLabel = "";

    public static int getLastHandshakeRttMs() {
        return lastHandshakeRttMs.get();
    }

    public static int getPrewarmedTotalCount() {
        return PREWARMED_POOL.totalSize();
    }

    public static String getLiveTelemetrySummary() {
        int rtt = lastHandshakeRttMs.get();
        int pooled = PREWARMED_POOL.totalSize();
        String edge = lastWorkingEdgeLabel;
        String net = cellularNetwork ? "Cell" : "WiFi";
        if (edge != null && !edge.isEmpty() && rtt >= 0) {
            return net + " WSS " + edge + " • " + rtt + "ms • pool=" + pooled;
        }
        if (workedOnNetwork) {
            return net + " WSS active • pool=" + pooled;
        }
        return net + " WSS idle • pool=" + pooled;
    }

    private static Semaphore[] createDcSemaphores() {
        Semaphore[] s = new Semaphore[DC_BUCKET_COUNT];
        for (int i = 0; i < s.length; i++) {
            s[i] = new Semaphore(DIAL_SLOTS_PER_BUCKET, true);
        }
        return s;
    }

    static int bucketFor(int dcId, boolean media) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm < 1 || norm > 5) return 0;
        return media ? (norm + 5) : norm;
    }

    private static int dcBucket(int dcId) {
        return bucketFor(dcId, Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    private ColgramTelegramWsBridge() {}

    public static final class WsSession implements Closeable {
        public final int dcId;
        public final int rawDcId;
        public final String edgeIp;
        public final String sniHost;
        public final Socket socket;
        public final Socket rawSocket;
        public final InputStream in;
        public final OutputStream out;
        private final Object writeLock = new Object();
        private long prngState;
        private byte[] writeScratch = new byte[4096];
        private final AtomicBoolean closed = new AtomicBoolean(false);
        volatile boolean fromPrewarm = false;

        WsSession(int dcId, String edgeIp, String sniHost, Socket socket,
                  InputStream in, OutputStream out) {
            this(dcId, dcId, edgeIp, sniHost, socket, null, in, out);
        }

        WsSession(int dcId, int rawDcId, String edgeIp, String sniHost, Socket socket,
                  InputStream in, OutputStream out) {
            this(dcId, rawDcId, edgeIp, sniHost, socket, null, in, out);
        }

        WsSession(int dcId, int rawDcId, String edgeIp, String sniHost, Socket socket,
                  Socket rawSocket, InputStream in, OutputStream out) {
            this.dcId = dcId;
            this.rawDcId = rawDcId;
            this.edgeIp = edgeIp;
            this.sniHost = sniHost;
            this.socket = socket;
            this.rawSocket = rawSocket;
            this.in = in;
            this.out = out;
            long seed;
            synchronized (SHARED_MASK_SEEDER) {
                seed = SHARED_MASK_SEEDER.nextLong();
            }
            this.prngState = seed != 0L ? seed : (System.nanoTime() ^ 0x9e3779b97f4a7c15L);
        }

        public void sendBinary(byte[] buf, int off, int len) throws IOException {
            sendBinaryInternal(buf, off, len, true);
        }

        void sendBinaryUnflushed(byte[] buf, int off, int len) throws IOException {
            sendBinaryInternal(buf, off, len, false);
        }

        void flushOut() throws IOException {
            synchronized (writeLock) {
                if (!closed.get()) out.flush();
            }
        }

        private void sendBinaryInternal(byte[] buf, int off, int len, boolean flush) throws IOException {
            if (len <= 0) return;
            synchronized (writeLock) {
                int pos = off;
                int remaining = len;
                boolean first = true;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, MAX_OUTBOUND_FRAME_BYTES);
                    boolean last = (chunk == remaining);
                    int headerByte = (last ? 0x80 : 0x00) | (first ? 0x02 : 0x00);
                    writeFrameLocked(headerByte, buf, pos, chunk, flush && last);
                    first = false;
                    pos += chunk;
                    remaining -= chunk;
                }
            }
        }

        public void sendPing() throws IOException {
            writeFrame(0x89, EMPTY_BYTES, 0, 0);
        }

        public void sendPong(byte[] payload, int len) throws IOException {
            byte[] safe = payload == null ? EMPTY_BYTES : payload;
            int clamped = Math.max(0, Math.min(len, Math.min(safe.length, 125)));
            writeFrame(0x8A, safe, 0, clamped);
        }

        public void sendClose() {
            try {
                writeFrame(0x88, EMPTY_BYTES, 0, 0);
            } catch (Throwable ignored) {
            }
        }

        private void writeFrame(int firstByte, byte[] data, int off, int len) throws IOException {
            synchronized (writeLock) {
                writeFrameLocked(firstByte, data, off, len, true);
            }
        }

        private int nextMaskInt() {
            long x = prngState;
            x ^= x >>> 12;
            x ^= x << 25;
            x ^= x >>> 27;
            prngState = x;
            return (int) ((x * 0x2545F4914F6CDD1DL) >>> 32);
        }

        private void writeFrameLocked(int firstByte, byte[] data, int off, int len, boolean flush) throws IOException {
            if (closed.get()) throw new IOException("WebSocket closed");
            int maskWord = nextMaskInt();
            byte m0 = (byte) (maskWord >>> 24);
            byte m1 = (byte) (maskWord >>> 16);
            byte m2 = (byte) (maskWord >>> 8);
            byte m3 = (byte) maskWord;
            int headerLen;
            if (len < 126) {
                headerLen = 2 + 4;
            } else if (len <= 65535) {
                headerLen = 4 + 4;
            } else {
                headerLen = 10 + 4;
            }
            int needed = headerLen + len;
            if (writeScratch.length < needed) {
                writeScratch = new byte[Math.max(needed, writeScratch.length * 2)];
            }
            byte[] frame = writeScratch;
            frame[0] = (byte) firstByte;
            int p = 1;
            if (len < 126) {
                frame[p++] = (byte) (0x80 | len);
            } else if (len <= 65535) {
                frame[p++] = (byte) (0x80 | 126);
                frame[p++] = (byte) ((len >>> 8) & 0xff);
                frame[p++] = (byte) (len & 0xff);
            } else {
                frame[p++] = (byte) (0x80 | 127);
                long l = len;
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame[p++] = (byte) ((l >>> shift) & 0xff);
                }
            }
            frame[p] = m0;
            frame[p + 1] = m1;
            frame[p + 2] = m2;
            frame[p + 3] = m3;
            p += 4;
            int i = 0;
            int limit4 = len - 3;
            while (i < limit4) {
                frame[p + i] = (byte) (data[off + i] ^ m0);
                frame[p + i + 1] = (byte) (data[off + i + 1] ^ m1);
                frame[p + i + 2] = (byte) (data[off + i + 2] ^ m2);
                frame[p + i + 3] = (byte) (data[off + i + 3] ^ m3);
                i += 4;
            }
            while (i < len) {
                int r = i & 3;
                byte m = r == 0 ? m0 : (r == 1 ? m1 : (r == 2 ? m2 : m3));
                frame[p + i] = (byte) (data[off + i] ^ m);
                i++;
            }
            out.write(frame, 0, needed);
            if (flush) out.flush();
            if (writeScratch.length > 8192 && needed <= 8192) {
                writeScratch = new byte[8192];
            }
        }

        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (rawSocket != null && rawSocket != socket) {
                try {
                    rawSocket.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                if (socket != null) socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class PrewarmedEntry {
        final WsSession session;
        final long createdAtMs;
        final long generation;
        final boolean protectedSocket;
        final int bucket;

        PrewarmedEntry(WsSession session, long createdAtMs, long generation,
                       boolean protectedSocket, int bucket) {
            this.session = session;
            this.createdAtMs = createdAtMs;
            this.generation = generation;
            this.protectedSocket = protectedSocket;
            this.bucket = bucket;
        }
    }

    /**
     * Bounded, thread-safe Zero-RTT pool of already-upgraded (101 Switching Protocols) Telegram
     * /apiws sessions awaiting the 64-byte obfuscated2 init frame.
     */
    private static final class PrewarmedWsPool {
        @SuppressWarnings("unchecked")
        private final java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry>[] buckets =
                new java.util.concurrent.ConcurrentLinkedDeque[DC_BUCKET_COUNT];
        private final java.util.concurrent.atomic.AtomicLong generation =
                new java.util.concurrent.atomic.AtomicLong(1L);
        private final java.util.concurrent.atomic.AtomicIntegerArray inFlight =
                new java.util.concurrent.atomic.AtomicIntegerArray(DC_BUCKET_COUNT);

        PrewarmedWsPool() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new java.util.concurrent.ConcurrentLinkedDeque<>();
            }
        }

        long currentGeneration() {
            return generation.get();
        }

        boolean isPrewarmUsable(PrewarmedEntry entry, long nowMs) {
            if (entry == null || entry.session == null) return false;
            if (entry.generation != generation.get()) return false;
            if (nowMs < entry.createdAtMs || (nowMs - entry.createdAtMs) > PREWARM_TTL_MS) {
                return false;
            }
            return ColgramTelegramWsBridge.isPrewarmUsable(entry.session);
        }

        boolean needsForegroundRefresh(int normDc, boolean media, boolean wantProtected, long nowMs) {
            int bucket = bucketFor(normDc, media);
            if (inFlight.get(bucket) > 0) return false;
            java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q = buckets[bucket];
            pruneExpired(q, nowMs);
            PrewarmedEntry newest = null;
            for (PrewarmedEntry entry : q) {
                if (entry.protectedSocket == wantProtected && isPrewarmUsable(entry, nowMs)) {
                    if (newest == null || entry.createdAtMs > newest.createdAtMs) {
                        newest = entry;
                    }
                }
            }
            if (newest == null) return true;
            if ((nowMs - newest.createdAtMs) >= PREWARM_REFRESH_MS) {
                if (q.size() >= prewarmMaxForBucket(bucket)) {
                    PrewarmedEntry evicted = q.pollLast();
                    if (evicted != null) evicted.session.close();
                }
                return true;
            }
            return false;
        }

        WsSession take(int normDc, boolean media, boolean wantProtected, long nowMs) {
            int bucket = bucketFor(normDc, media);
            java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q = buckets[bucket];
            PrewarmedEntry entry;
            while ((entry = q.pollFirst()) != null) {
                if (!isPrewarmUsable(entry, nowMs) || entry.protectedSocket != wantProtected) {
                    entry.session.close();
                    continue;
                }
                pruneTail(q, nowMs);
                return entry.session;
            }
            return null;
        }

        boolean hasUsable(int normDc, boolean media, boolean wantProtected, long nowMs) {
            int bucket = bucketFor(normDc, media);
            java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q = buckets[bucket];
            for (PrewarmedEntry entry : q) {
                if (isPrewarmUsable(entry, nowMs) && entry.protectedSocket == wantProtected) {
                    return true;
                }
                if (!isPrewarmUsable(entry, nowMs) && q.remove(entry)) {
                    entry.session.close();
                }
            }
            return false;
        }

        boolean offer(PrewarmedEntry entry, long nowMs) {
            if (entry == null || entry.session == null || !isPrewarmUsable(entry, nowMs)) {
                if (entry != null && entry.session != null) entry.session.close();
                return false;
            }
            java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q = buckets[entry.bucket];
            pruneExpired(q, nowMs);
            synchronized (q) {
                if (entry.generation != generation.get() || q.size() >= prewarmMaxForBucket(entry.bucket)) {
                    entry.session.close();
                    return false;
                }
                q.offerFirst(entry);
                return true;
            }
        }

        int evictExpired(long nowMs) {
            int evicted = 0;
            for (java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q : buckets) {
                evicted += pruneExpired(q, nowMs);
            }
            return evicted;
        }

        int clearBucket(int bucket) {
            if (bucket < 0 || bucket >= buckets.length) return 0;
            int closed = 0;
            PrewarmedEntry entry;
            while ((entry = buckets[bucket].pollFirst()) != null) {
                entry.session.close();
                closed++;
            }
            return closed;
        }

        int clearAll() {
            generation.incrementAndGet();
            int closed = 0;
            for (java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q : buckets) {
                PrewarmedEntry entry;
                while ((entry = q.pollFirst()) != null) {
                    entry.session.close();
                    closed++;
                }
            }
            return closed;
        }

        int size(int bucket) {
            return buckets[bucket].size();
        }

        int totalSize() {
            int sum = 0;
            for (java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q : buckets) {
                sum += q.size();
            }
            return sum;
        }

        private void pruneTail(java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q, long nowMs) {
            PrewarmedEntry tail;
            while ((tail = q.peekLast()) != null && !isPrewarmUsable(tail, nowMs)) {
                if (q.remove(tail)) {
                    tail.session.close();
                } else {
                    break;
                }
            }
        }

        private int pruneExpired(java.util.concurrent.ConcurrentLinkedDeque<PrewarmedEntry> q, long nowMs) {
            int removed = 0;
            for (PrewarmedEntry entry : q) {
                if (!isPrewarmUsable(entry, nowMs) && q.remove(entry)) {
                    entry.session.close();
                    removed++;
                }
            }
            return removed;
        }
    }

    static boolean isPrewarmUsable(WsSession session) {
        if (session == null || session.isClosed()) return false;
        Socket s = session.socket;
        if (s != null) {
            if (s.isClosed() || s.isInputShutdown() || s.isOutputShutdown()) return false;
            if (!s.isConnected()) return false;
            if (s instanceof ColgramNativeDesync.NativeSocket
                    && !((ColgramNativeDesync.NativeSocket) s).isIdleClean()) {
                return false;
            }
        }
        if (session.in != null) {
            try {
                if (session.in.available() > 0) return false;
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean supports(String host, int port) {
        if (port != 443 && port != 80 && port != 5222) return false;
        return ColgramTelegramDcAddresses.signedDcIdFor(host) != 0;
    }

    public static boolean hasWorkedOnCurrentNetwork() {
        return workedOnNetwork;
    }

    public static void resetNetworkState() {
        PREWARMED_POOL.clearAll();
        for (int i = 0; i < lastWorkingByDc.length(); i++) {
            lastWorkingByDc.set(i, null);
            lastSuccessAtByDc.set(i, 0L);
        }
        wifiPreferredFragMode = defaultFragModeForNetwork(false);
        cellularPreferredFragMode = defaultFragModeForNetwork(true);
        preferredFragMode = defaultFragModeForNetwork(cellularNetwork);
        workedOnNetwork = false;
        resolvedAt = 0L;
        wifiSniCursor = 0;
        cellularSniCursor = 0;
        spoofSniCursor.set(0);
        perHostIps.clear();
        clearCooldowns();
        warmUp();
    }

    /** Clears per-DC transient cooldowns and temporary endpoint deprioritizations without wiping DNS cache. */
    public static void clearCooldowns() {
        deprioritizedUntil.clear();
        for (int i = 0; i < consecutiveFailuresByDc.length(); i++) {
            consecutiveFailuresByDc.set(i, 0);
            cooldownUntilByDc.set(i, 0L);
            dc135DeadUntil.set(i, 0L);
            dc135DeadStreak.set(i, 0);
            lastBurstFailAtByDc.set(i, 0L);
        }
        consecutiveTransportFailures.set(0);
    }

    public static void deprioritizeEndpoint(WsSession session) {
        if (session == null || session.edgeIp == null || session.sniHost == null) return;
        EdgeEndpoint ep = new EdgeEndpoint(session.edgeIp, session.sniHost);
        long ttlMs = DEFAULT_EDGE_IP.equals(ep.ip) ? DEFAULT_EDGE_DEPRIORITIZE_TTL_MS : DEPRIORITIZE_TTL_MS;
        deprioritizedUntil.put(ep.key(), System.currentTimeMillis() + ttlMs);
        int bucket = dcBucket(session.rawDcId != 0 ? session.rawDcId : session.dcId);
        if (ep.equals(lastWorkingByDc.get(bucket))) {
            lastWorkingByDc.compareAndSet(bucket, ep, null);
        }
    }

    /**
     * Pre-resolve Telegram WebSocket edge hosts via DoH and pre-warm Zero-RTT /apiws sessions
     * for DC2/DC4 generic & media edges off-thread.
     */
    public static void warmUp() {
        if (!ColgramUiBridge.isAppInForeground()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - resolvedAt >= DNS_CACHE_TTL_MS || perHostIps.isEmpty()) {
            if (refreshInFlight.compareAndSet(false, true)) {
                try {
                    RACE_POOL.execute(ColgramTelegramWsBridge::refreshResolvedIps);
                } catch (Throwable rejected) {
                    refreshInFlight.set(false);
                }
            }
        }
        prewarmDefaultBucketsAsync();
    }

    /** Drops every idle pooled WSS socket immediately on app backgrounding so radio & timers stay dark. */
    public static void onBackgroundPause() {
        PREWARMED_POOL.clearAll();
    }

    /** Restores foreground prewarm pool & DoH cache on resume for zero-RTT first chat/avatar loads. */
    public static void onForegroundResume() {
        warmUp();
    }

    /**
     * Lightweight foreground-only pool maintenance called from the existing 8s watchdog tick.
     * Evicts expired entries and refreshes aging prewarmed buckets (>= PREWARM_REFRESH_MS)
     * without adding any background timers or wakeups.
     */
    public static void maintainForegroundPrewarmIfNeeded(android.content.Context ctx) {
        if (!prewarmEnabled || !ColgramUiBridge.isAppInForeground()) return;
        if (ColgramDpiBypass.wasExplicitlyStopped()
                || ColgramDpiBypass.isOverlayVpnActive()
                || ColgramDpiBypass.isStrictL3WhitelistDetected()) {
            return;
        }
        if (ctx != null && ColgramPowerGuard.shouldThrottle(ctx)) return;
        long now = System.currentTimeMillis();
        PREWARMED_POOL.evictExpired(now);
        if (!workedOnNetwork && !ColgramDpiBypass.isWsPreferredAnyBucket()) {
            return;
        }
        boolean wantProtected = ColgramDpiBypass.activeSystemDpiVpn() != null;
        for (int signedDc : PREWARM_TARGET_DCS) {
            int normDc = Math.abs(signedDc);
            boolean media = signedDc < 0;
            if (PREWARMED_POOL.needsForegroundRefresh(normDc, media, wantProtected, now)) {
                replenishPrewarmedAsync(normDc, media, 1);
            }
        }
    }

    public static boolean hasPrewarmed(int dcId, boolean media) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm < 1 || norm > 5) return false;
        boolean wantProtected = ColgramDpiBypass.activeSystemDpiVpn() != null;
        return PREWARMED_POOL.hasUsable(norm, media, wantProtected, System.currentTimeMillis());
    }

    static boolean hasPrewarmed(int dcId, boolean media, boolean wantProtected) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm < 1 || norm > 5) return false;
        return PREWARMED_POOL.hasUsable(norm, media, wantProtected, System.currentTimeMillis());
    }

    public static WsSession takePrewarmed(int normDc, boolean media) {
        boolean wantProtected = ColgramDpiBypass.activeSystemDpiVpn() != null;
        return takePrewarmed(normDc, media, wantProtected);
    }

    static WsSession takePrewarmed(int normDc, boolean media, boolean wantProtected) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(normDc);
        if (norm < 1 || norm > 5) return null;
        long now = System.currentTimeMillis();
        WsSession hit = PREWARMED_POOL.take(norm, media, wantProtected, now);
        if (hit != null) {
            hit.fromPrewarm = true;
            replenishPrewarmedAsync(norm, media);
        }
        return hit;
    }

    public static int drainPrewarmedPool() {
        return PREWARMED_POOL.clearAll();
    }

    public static void evictPrewarmedBucket(int dcId) {
        PREWARMED_POOL.clearBucket(dcBucket(dcId));
    }

    private static void prewarmDefaultBucketsAsync() {
        if (!prewarmEnabled || !ColgramUiBridge.isAppInForeground()) return;
        for (int signedDc : PREWARM_TARGET_DCS) {
            replenishPrewarmedAsync(Math.abs(signedDc), signedDc < 0);
        }
    }

    private static void replenishPrewarmedAsync(final int normDc, final boolean media, final int maxToSpawn) {
        if (!prewarmEnabled || !ColgramUiBridge.isAppInForeground() || normDc < 1 || normDc > 5) return;
        if (ColgramDpiBypass.wasExplicitlyStopped()
                || ColgramDpiBypass.isOverlayVpnActive()
                || ColgramDpiBypass.isStrictL3WhitelistDetected()) {
            return;
        }
        final int bucket = bucketFor(normDc, media);
        if (System.currentTimeMillis() < cooldownUntilByDc.get(bucket)) return;
        final android.net.VpnService sysVpn = ColgramDpiBypass.activeSystemDpiVpn();
        final boolean wantProtected = sysVpn != null;
        final long now = System.currentTimeMillis();
        PREWARMED_POOL.evictExpired(now);
        final int capForBucket = prewarmMaxFor(normDc, media);
        int current = PREWARMED_POOL.size(bucket);
        int pending = PREWARMED_POOL.inFlight.get(bucket);
        int freeSlots = capForBucket - (current + pending);
        int deficit = Math.min(Math.max(1, maxToSpawn), freeSlots);
        if (deficit <= 0) return;

        final long gen = PREWARMED_POOL.currentGeneration();
        final ColgramDohResolver.SocketProtector protector = sysVpn != null ? sysVpn::protect : null;
        final ColgramNativeDesync.FdProtector fdProtector = sysVpn != null ? sysVpn::protect : null;
        for (int i = 0; i < deficit; i++) {
            if (PREWARMED_POOL.inFlight.incrementAndGet(bucket) > capForBucket) {
                PREWARMED_POOL.inFlight.decrementAndGet(bucket);
                break;
            }
            try {
                PREWARM_POOL.execute(() -> {
                    WsSession session = null;
                    try {
                        if (gen != PREWARMED_POOL.currentGeneration()
                                || ColgramDpiBypass.wasExplicitlyStopped()
                                || ColgramDpiBypass.isOverlayVpnActive()
                                || ColgramDpiBypass.isStrictL3WhitelistDetected()) {
                            return;
                        }
                        if (!dialSlotsByDc[bucket].tryAcquire()) {
                            return;
                        }
                        try {
                            long deadlineMs = System.currentTimeMillis() + Math.min(2000L, maxDialWallMs());
                            session = dialFreshInternal(normDc, media, protector, fdProtector, deadlineMs, true);
                        } finally {
                            dialSlotsByDc[bucket].release();
                        }
                        if (session != null) {
                            if (gen == PREWARMED_POOL.currentGeneration()
                                    && !ColgramDpiBypass.wasExplicitlyStopped()
                                    && PREWARMED_POOL.offer(
                                            new PrewarmedEntry(session, System.currentTimeMillis(), gen, wantProtected, bucket),
                                            System.currentTimeMillis())) {
                                session = null;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (session != null) session.close();
                        PREWARMED_POOL.inFlight.decrementAndGet(bucket);
                    }
                });
            } catch (Throwable rejected) {
                PREWARMED_POOL.inFlight.decrementAndGet(bucket);
                break;
            }
        }
    }

    private static void replenishPrewarmedAsync(final int normDc, final boolean media) {
        replenishPrewarmedAsync(normDc, media, prewarmMaxFor(normDc, media));
    }

    static boolean offerPrewarmedForTest(WsSession session, long createdAtMs, boolean wantProtected) {
        if (session == null) return false;
        int norm = Obfuscated2FrameSplitter.normalizeDcId(session.dcId);
        boolean media = Obfuscated2FrameSplitter.isMediaDcId(session.rawDcId != 0 ? session.rawDcId : session.dcId);
        int bucket = bucketFor(norm, media);
        return PREWARMED_POOL.offer(
                new PrewarmedEntry(session, createdAtMs, PREWARMED_POOL.currentGeneration(), wantProtected, bucket),
                System.currentTimeMillis());
    }

    static int prewarmedPoolSizeForTest() {
        return PREWARMED_POOL.totalSize();
    }

    static int prewarmedPoolSizeForTest(int normDc, boolean media) {
        return PREWARMED_POOL.size(bucketFor(normDc, media));
    }

    static int evictExpiredPrewarmedForTest(long nowMs) {
        return PREWARMED_POOL.evictExpired(nowMs);
    }

    static void setPrewarmEnabledForTest(boolean enabled) {
        prewarmEnabled = enabled;
    }

    static int preferredFragModeForTest() {
        return preferredFragMode;
    }

    static void setLastWorkingForTest(int rawDcId, String ip, String sniHost) {
        int bucket = dcBucket(rawDcId);
        lastWorkingByDc.set(bucket, new EdgeEndpoint(ip, sniHost));
    }

    static String lastWorkingForTest(int rawDcId) {
        EdgeEndpoint ep = lastWorkingByDc.get(dcBucket(rawDcId));
        return ep != null ? ep.key() : null;
    }

    static boolean isDeprioritizedForTest(String ip, String sniHost) {
        Long until = deprioritizedUntil.get(new EdgeEndpoint(ip, sniHost).key());
        return until != null && until > System.currentTimeMillis();
    }

    static boolean needsForegroundRefreshForTest(int normDc, boolean media, boolean wantProtected, long nowMs) {
        return PREWARMED_POOL.needsForegroundRefresh(normDc, media, wantProtected, nowMs);
    }

    static void restoreBypassHint(boolean cellular, int fragMode, int sniCursor) {
        int safeSni = Math.floorMod(sniCursor, WHITELIST_SPOOF_SNIS.length);
        if (cellular) {
            cellularPreferredFragMode = fragMode;
            cellularSniCursor = safeSni;
        } else {
            wifiPreferredFragMode = fragMode;
            wifiSniCursor = safeSni;
        }
        if (cellularNetwork == cellular) {
            preferredFragMode = fragMode;
            spoofSniCursor.set(safeSni);
        }
    }

    public static WsSession dial(String destHost, int destPort) {
        int signedDc = ColgramTelegramDcAddresses.signedDcIdFor(destHost);
        if (signedDc == 0) return null;
        return dial(destHost, destPort, signedDc < 0);
    }

    public static WsSession dial(String destHost, int destPort, boolean preferMediaEdge) {
        return dialProtected(destHost, destPort, null, null, preferMediaEdge);
    }

    public static WsSession dialProtected(String destHost, int destPort,
                                          ColgramDohResolver.SocketProtector protector) {
        return dialProtected(destHost, destPort, protector, null);
    }

    /**
     * Dial Telegram's official `/apiws` WebSocket bridge, optionally protecting every outbound TCP
     * socket via {@code protector} / {@code fdProtector} and racing carrier-safe spoofed RU-whitelist
     * SNI (TLS 1.3), Java TLS-record-split, and native ByeDPI multi-split modes across verified
     * IPv4+IPv6 WSS edges.
     */
    public static WsSession dialProtected(String destHost, int destPort,
                                          ColgramDohResolver.SocketProtector protector,
                                          ColgramNativeDesync.FdProtector fdProtector) {
        int signedDc = ColgramTelegramDcAddresses.signedDcIdFor(destHost);
        if (signedDc == 0) return null;
        return dialProtected(destHost, destPort, protector, fdProtector, signedDc < 0);
    }

    public static WsSession dialProtected(String destHost, int destPort,
                                          ColgramDohResolver.SocketProtector protector,
                                          ColgramNativeDesync.FdProtector fdProtector,
                                          boolean preferMediaEdge) {
        if (destPort != 443 && destPort != 80 && destPort != 5222) return null;
        int signedDc = ColgramTelegramDcAddresses.signedDcIdFor(destHost);
        if (signedDc == 0) return null;
        int normDc = Math.abs(signedDc);
        boolean media = preferMediaEdge || signedDc < 0;
        return dialDcInternal(normDc, media, protector, fdProtector, true);
    }

    @FunctionalInterface
    interface FreshDcDialer {
        WsSession dial(int signedDc,
                       ColgramDohResolver.SocketProtector protector,
                       ColgramNativeDesync.FdProtector fdProtector);
    }

    static volatile FreshDcDialer dialFreshOverrideForTest = null;

    public static WsSession dialFreshForDc(int signedDc,
                                           ColgramDohResolver.SocketProtector protector,
                                           ColgramNativeDesync.FdProtector fdProtector) {
        FreshDcDialer override = dialFreshOverrideForTest;
        if (override != null) {
            return override.dial(signedDc, protector, fdProtector);
        }
        int normDc = Obfuscated2FrameSplitter.normalizeDcId(signedDc);
        if (normDc < 1 || normDc > 5) return null;
        boolean media = Obfuscated2FrameSplitter.isMediaDcId(signedDc);
        return dialDcInternal(normDc, media, protector, fdProtector, false);
    }

    private static WsSession dialDcInternal(int normDc, boolean media,
                                            ColgramDohResolver.SocketProtector protector,
                                            ColgramNativeDesync.FdProtector fdProtector,
                                            boolean allowPrewarm) {
        int bucket = bucketFor(normDc, media);
        long now = System.currentTimeMillis();
        if (now < cooldownUntilByDc.get(bucket)) return null;

        if (isSecondaryDcFastFail(normDc) && now >= dc135DeadUntil.get(bucket)
                && !ColgramDpiBypass.wasExplicitlyStopped()
                && PREWARMED_POOL.size(bucket) == 0 && PREWARMED_POOL.inFlight.get(bucket) == 0) {
            replenishPrewarmedAsync(normDc, media, 2);
        }

        if (allowPrewarm) {
            boolean wantProtected = (protector != null || fdProtector != null
                    || ColgramDpiBypass.activeSystemDpiVpn() != null);
            WsSession prewarmed = takePrewarmed(normDc, media, wantProtected);
            if (prewarmed != null) {
                workedOnNetwork = true;
                lastWorkingEdgeLabel = prewarmed.sniHost + "@" + prewarmed.edgeIp;
                lastSuccessAtByDc.set(bucket, now);
                consecutiveFailuresByDc.set(bucket, 0);
                cooldownUntilByDc.set(bucket, 0L);
                ColgramDpiBypass.onDirectLayer1Success();
                return prewarmed;
            }
        }

        // Coalesce concurrent tgnet dial storms per (DC, generic|media) bucket with 12 parallel slots
        // so parallel photo/avatar/emoji downloads never starve behind generic traffic.
        Semaphore dcSlot = dialSlotsByDc[bucket];
        boolean acquired = false;
        try {
            acquired = dcSlot.tryAcquire(DIAL_SLOT_ACQUIRE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!acquired) {
            return null;
        }
        try {
            now = System.currentTimeMillis();
            if (now < cooldownUntilByDc.get(bucket)) return null;
            long deadlineMs = now + maxDialWallMs();
            WsSession dialed = dialFreshInternal(normDc, media, protector, fdProtector, deadlineMs, false);
            if (dialed != null) {
                replenishPrewarmedAsync(normDc, media);
            }
            return dialed;
        } finally {
            dcSlot.release();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isSecondaryDcFastFail(int normDc) {
        return normDc == 1 || normDc == 3 || normDc == 5;
    }

    private static WsSession dialFreshInternal(int normDc, boolean media,
                                               ColgramDohResolver.SocketProtector protector,
                                               ColgramNativeDesync.FdProtector fdProtector,
                                               long deadlineMs, boolean forPrewarm) {
        boolean canNative = ColgramNativeDesync.isAvailable() && (protector == null || fdProtector != null);
        int bucket = bucketFor(normDc, media);

        // DC1/DC3/DC5 resilient connect (1200ms Wi-Fi / 1600ms cellular): race top-2 candidates,
        // retry with fallbackFragModes on timeout/SSL/EOF, and bound dead backoff 12s..30s.
        if (isSecondaryDcFastFail(normDc)) {
            long now = System.currentTimeMillis();
            if (now < dc135DeadUntil.get(bucket)) {
                return null;
            }
            List<EdgeEndpoint> fastCands = candidateEndpointsFor(normDc, media);
            if (fastCands.isEmpty()) {
                int streak = dc135DeadStreak.incrementAndGet(bucket);
                long backoff = Math.min(DC135_MAX_BACKOFF_MS, DC135_DEAD_COOLDOWN_MS << Math.min(2, Math.max(0, streak - 1)));
                dc135DeadUntil.set(bucket, now + backoff);
                return null;
            }
            int fastMode = preferredFragMode;
            if (!canNative && isNativeFragMode(fastMode)) {
                fastMode = FRAG_SPOOF_SNI_PLAIN;
            }
            int fastTimeout = cellularNetwork ? DC135_CELLULAR_CONNECT_TIMEOUT_MS : DC135_WIFI_CONNECT_TIMEOUT_MS;
            List<EdgeEndpoint> top2 = fastCands.size() > 2 ? fastCands.subList(0, 2) : fastCands;
            DialedTcp tcp = connectEdgeRace(top2, protector, fdProtector, fastMode, fastTimeout);
            if (tcp != null) {
                HandshakeResult res = tryHandshake(tcp, normDc, media, fastMode, forPrewarm);
                if (res.session != null) {
                    dc135DeadUntil.set(bucket, 0L);
                    dc135DeadStreak.set(bucket, 0);
                    setPreferredFragMode(fastMode);
                    return res.session;
                }
                if (!res.hostnameMismatch && (res.wrapperRejected || res.transportFailure || !workedOnNetwork
                        || consecutiveTransportFailures.get() >= 2)
                        && System.currentTimeMillis() + 600L < deadlineMs) {
                    int[] fallbacks = fallbackFragModes(fastMode, canNative);
                    int tried = 0;
                    for (int altMode : fallbacks) {
                        if (tried >= 2 || System.currentTimeMillis() + 600L >= deadlineMs) break;
                        tried++;
                        DialedTcp altTcp = connectEdgeRace(top2, protector, fdProtector, altMode, fastTimeout);
                        if (altTcp == null) break;
                        HandshakeResult altRes = tryHandshake(altTcp, normDc, media, altMode, forPrewarm);
                        if (altRes.session != null) {
                            dc135DeadUntil.set(bucket, 0L);
                            dc135DeadStreak.set(bucket, 0);
                            setPreferredFragMode(altMode);
                            return altRes.session;
                        }
                        if (altRes.hostnameMismatch) break;
                    }
                }
            }
            int streak = dc135DeadStreak.incrementAndGet(bucket);
            long backoff = Math.min(DC135_MAX_BACKOFF_MS, DC135_DEAD_COOLDOWN_MS << Math.min(2, Math.max(0, streak - 1)));
            dc135DeadUntil.set(bucket, System.currentTimeMillis() + backoff);
            return null;
        }

        // Cold-start Wave-1 parallel race on BOTH cellular and Wi-Fi:
        // Race carrier-safe spoofed RU-whitelist SNI (TLS 1.3, split + plain) alongside
        // real-SNI Java TLS-record-split and native MODE_MULTI_SPLIT_SNI (zero MSG_OOB,
        // zero TTL=1, zero tiny MSS).
        if (!workedOnNetwork) {
            List<EdgeEndpoint> coldCandidates = candidateEndpointsFor(normDc, media);
            if (!coldCandidates.isEmpty()) {
                int[] coldModes = canNative
                        ? new int[]{
                                FRAG_SPOOF_SNI_PLAIN,
                                FRAG_SPOOF_SNI_TLSREC,
                                FRAG_TLS_RECORD_SNI,
                                FRAG_NATIVE_MULTI_SPLIT_TLSREC
                        }
                        : new int[]{
                                FRAG_SPOOF_SNI_PLAIN,
                                FRAG_SPOOF_SNI_TLSREC,
                                FRAG_TLS_RECORD_SNI
                        };
                long coldBudget = Math.min(
                        cellularNetwork ? 2200L : 1700L,
                        Math.max(1000L, deadlineMs - System.currentTimeMillis()));
                WsSession coldWinner = raceHandshakeModes(
                        coldCandidates, normDc, media, coldModes, protector, fdProtector, coldBudget, forPrewarm);
                if (coldWinner != null) {
                    return coldWinner;
                }
            }
        }

        Set<String> triedIps = new LinkedHashSet<>();
        int maxAltPerEndpoint = workedOnNetwork ? 1 : 2;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (System.currentTimeMillis() >= deadlineMs) break;
            List<EdgeEndpoint> candidates = filterUnvisitedIps(candidateEndpointsFor(normDc, media), triedIps);
            if (candidates.isEmpty()) break;

            int primaryMode = preferredFragMode;
            if (!canNative && isNativeFragMode(primaryMode)) {
                primaryMode = FRAG_SPOOF_SNI_PLAIN;
            }
            DialedTcp tcp = connectEdgeRace(candidates, protector, fdProtector, primaryMode);
            if (tcp == null) break;
            triedIps.add(tcp.endpoint.ip);

            HandshakeResult res = tryHandshake(tcp, normDc, media, primaryMode, forPrewarm);
            if (res.session != null) {
                setPreferredFragMode(primaryMode);
                return res.session;
            }

            if (!res.hostnameMismatch
                    && (res.wrapperRejected || !workedOnNetwork
                            || (res.transportFailure && consecutiveTransportFailures.get() >= 2))
                    && System.currentTimeMillis() + 700L < deadlineMs) {
                int[] fallbacks = fallbackFragModes(primaryMode, canNative);
                int maxFallbacks = consecutiveTransportFailures.get() >= 2 ? 2 : maxAltPerEndpoint;
                int altTried = 0;
                for (int altMode : fallbacks) {
                    if (altTried >= maxFallbacks
                            || System.currentTimeMillis() + 800L >= deadlineMs) break;
                    altTried++;
                    DialedTcp altTcp = connectEdgeRace(
                            Collections.singletonList(tcp.endpoint), protector, fdProtector, altMode);
                    if (altTcp == null) break;
                    HandshakeResult altRes = tryHandshake(altTcp, normDc, media, altMode, forPrewarm);
                    if (altRes.session != null) {
                        setPreferredFragMode(altMode);
                        return altRes.session;
                    }
                    if (altRes.hostnameMismatch) break;
                }
            }
            long ttlMs = DEFAULT_EDGE_IP.equals(tcp.endpoint.ip)
                    ? DEFAULT_EDGE_DEPRIORITIZE_TTL_MS : DEPRIORITIZE_TTL_MS;
            deprioritizedUntil.put(tcp.endpoint.key(), System.currentTimeMillis() + ttlMs);
        }
        if (!forPrewarm) {
            recordBurstFailure(normDc, media);
        }
        return null;
    }

    private static void setPreferredFragMode(int mode) {
        preferredFragMode = mode;
        boolean cell = cellularNetwork;
        if (cell) cellularPreferredFragMode = mode;
        else wifiPreferredFragMode = mode;
        int curSni = Math.floorMod(spoofSniCursor.get(), WHITELIST_SPOOF_SNIS.length);
        if (cell) cellularSniCursor = curSni;
        else wifiSniCursor = curSni;
        ColgramDpiBypass.persistWssHint(cell, mode, curSni);
    }

    private static List<EdgeEndpoint> filterUnvisitedIps(List<EdgeEndpoint> all, Set<String> triedIps) {
        if (all.isEmpty() || triedIps.isEmpty()) return all;
        List<EdgeEndpoint> fresh = new ArrayList<>(all.size());
        for (EdgeEndpoint ep : all) {
            if (!triedIps.contains(ep.ip)) fresh.add(ep);
        }
        return fresh.isEmpty() ? all : fresh;
    }

    private static void recordBurstFailure(int normDc, boolean media) {
        int bucket = bucketFor(normDc, media);
        long now = System.currentTimeMillis();
        long prev = lastBurstFailAtByDc.get(bucket);
        if (now - prev >= BURST_FAILURE_WINDOW_MS
                && lastBurstFailAtByDc.compareAndSet(bucket, prev, now)) {
            recordFailure(normDc, media);
        }
    }

    private static WsSession raceHandshakeModes(final List<EdgeEndpoint> candidates, final int normDc,
                                                final boolean media,
                                                final int[] modes,
                                                final ColgramDohResolver.SocketProtector protector,
                                                final ColgramNativeDesync.FdProtector fdProtector,
                                                final long budgetMs,
                                                final boolean forPrewarm) {
        final Object winnerLock = new Object();
        final AtomicReference<WsSession> winner = new AtomicReference<>();
        final boolean[] timedOut = new boolean[]{false};
        final AtomicBoolean done = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger left = new AtomicInteger(modes.length);
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            final long delay = i <= 1 ? 0L : (i == 2 ? 15L : 30L);
            try {
                RACE_POOL.execute(() -> {
                    WsSession sess = null;
                    try {
                        if (delay > 0L) Thread.sleep(delay);
                        if (done.get()) return;
                        DialedTcp tcp = connectEdgeRace(candidates, protector, fdProtector, mode);
                        if (tcp == null || done.get()) {
                            if (tcp != null) { try { tcp.socket.close(); } catch (Throwable ignored) {} }
                            return;
                        }
                        HandshakeResult res = tryHandshake(tcp, normDc, media, mode, forPrewarm);
                        if (res.session != null) {
                            synchronized (winnerLock) {
                                if (!timedOut[0] && winner.get() == null) {
                                    winner.set(res.session);
                                    done.set(true);
                                    setPreferredFragMode(mode);
                                    latch.countDown();
                                } else {
                                    sess = res.session;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (sess != null) sess.close();
                        if (left.decrementAndGet() == 0) {
                            done.set(true);
                            latch.countDown();
                        }
                    }
                });
            } catch (Throwable rejected) {
                if (left.decrementAndGet() == 0) {
                    done.set(true);
                    latch.countDown();
                }
            }
        }
        try {
            latch.await(budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (winnerLock) {
            timedOut[0] = true;
            done.set(true);
            return winner.get();
        }
    }

    private static int[] fallbackFragModes(int primary, boolean canNative) {
        int[] ordered = canNative
                ? new int[]{
                        FRAG_SPOOF_SNI_TLSREC,
                        FRAG_SPOOF_SNI_ALT,
                        FRAG_SPOOF_SNI_PLAIN,
                        FRAG_NO_SNI_TLSREC,
                        FRAG_TLS_RECORD_SNI,
                        FRAG_TCP_SNI,
                        FRAG_NATIVE_MULTI_SPLIT_TLSREC,
                        FRAG_NATIVE_COMPACT_4_RECORD,
                        FRAG_NATIVE_HDR2_SNI2_BULK,
                        FRAG_NATIVE_DISORDER_TLSREC,
                        FRAG_NATIVE_FAKE_TTL3_TLSREC,
                        FRAG_NATIVE_OOB1_ONLY_TLSREC,
                        FRAG_NATIVE_MSS_CLAMP_TLSREC,
                        FRAG_NONE
                }
                : new int[]{
                        FRAG_SPOOF_SNI_TLSREC,
                        FRAG_SPOOF_SNI_ALT,
                        FRAG_SPOOF_SNI_PLAIN,
                        FRAG_NO_SNI_TLSREC,
                        FRAG_TLS_RECORD_SNI,
                        FRAG_TCP_SNI,
                        FRAG_NONE
                };
        List<Integer> out = new ArrayList<>(ordered.length);
        for (int m : ordered) {
            if (m != primary) out.add(m);
        }
        int[] res = new int[out.size()];
        for (int i = 0; i < out.size(); i++) res[i] = out.get(i);
        return res;
    }

    private static final class HandshakeResult {
        final WsSession session;
        final boolean wrapperRejected;
        final boolean hostnameMismatch;
        final boolean transportFailure;

        HandshakeResult(WsSession session, boolean wrapperRejected, boolean hostnameMismatch, boolean transportFailure) {
            this.session = session;
            this.wrapperRejected = wrapperRejected;
            this.hostnameMismatch = hostnameMismatch;
            this.transportFailure = transportFailure;
        }
    }

    private static Socket wrapSocketForMode(Socket base, String sniHost, int fragMode) {
        if (base instanceof ColgramNativeDesync.NativeSocket && isNativeFragMode(fragMode)) {
            ColgramNativeDesync.NativeSocket ns = (ColgramNativeDesync.NativeSocket) base;
            boolean cell = cellularNetwork;
            switch (fragMode) {
                case FRAG_NATIVE_MULTI_SPLIT_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_MULTI_SPLIT_SNI, 0, true, true, cell, 0);
                case FRAG_NATIVE_COMPACT_4_RECORD:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_COMPACT_4_RECORD, 0, true, true, cell, 0);
                case FRAG_NATIVE_HDR2_SNI2_BULK:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_HDR2_SNI2_BULK, 0, true, true, cell, 0);
                case FRAG_NATIVE_DISORDER_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_DISORDER_BYTE1, 1, true, true, cell, 0);
                case FRAG_NATIVE_FAKE_TTL5_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_FAKE_TTL, 5, true, false, cell, 0);
                case FRAG_NATIVE_OOB_DISORDER_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_OOB_BYTE1_DISORDER, 1, true, true, cell, 1);
                case FRAG_NATIVE_FAKE_TTL3_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_FAKE_TTL, 3, true, false, cell, 1);
                case FRAG_NATIVE_MSS_CLAMP_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_MSS_SPLIT, 0, true, false, cell, 0);
                case FRAG_NATIVE_FAKE_TTL8_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_FAKE_TTL, 8, true, false, cell, 3);
                case FRAG_NATIVE_MSS64_OOB1_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_MSS_OOB_BYTE1, 0, true, true, cell, 4);
                case FRAG_NATIVE_OOB1_ONLY_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_OOB_BYTE1_ONLY, 0, true, true, cell, 0);
                default:
                    return base;
            }
        }
        if (fragMode == FRAG_NONE || fragMode == FRAG_SPOOF_SNI_PLAIN) {
            return base;
        }
        return new FragmentedHelloSocket(base, sniHost, fragMode);
    }

    private static String pickSpoofSni(int fragMode) {
        int baseIdx = Math.floorMod(spoofSniCursor.get(), WHITELIST_SPOOF_SNIS.length);
        if (fragMode == FRAG_SPOOF_SNI_ALT) {
            return WHITELIST_SPOOF_SNIS[(baseIdx + 2) % WHITELIST_SPOOF_SNIS.length];
        }
        if (fragMode == FRAG_SPOOF_SNI_PLAIN) {
            return WHITELIST_SPOOF_SNIS[(baseIdx + 1) % WHITELIST_SPOOF_SNIS.length];
        }
        return WHITELIST_SPOOF_SNIS[baseIdx];
    }

    /**
     * Verifies that a CA-validated TLS {@link SSLSession} belongs to an official Telegram WebSocket
     * edge certificate ({@code *.web.telegram.org}, {@code *.telegram.org}, or {@code *.telesco.pe}).
     *
     * <p>When connecting with a spoofed RU-whitelist SNI (e.g. {@code max.ru} / {@code vk.com}) or
     * omitted SNI, Telegram's nginx edges upgrade cleanly to TLS 1.3 and serve their CA-signed
     * default-vhost certificate ({@code *.telegram.org} on DC2/DC4, {@code *.telesco.pe} on DC1/DC3,
     * {@code *.web.telegram.org} on DC5). The Android system {@code X509TrustManager} has already
     * validated the full signature chain during {@code startHandshake()}; this method enforces
     * Telegram SAN identity without weakening CA verification.
     */
    public static boolean verifyOfficialTelegramSession(SSLSession session, String realHost) {
        if (session == null) return false;
        try {
            if (realHost != null && isOfficialTelegramSan(realHost)
                    && HttpsURLConnection.getDefaultHostnameVerifier().verify(realHost, session)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Certificate[] certs = session.getPeerCertificates();
            if (certs == null || certs.length == 0 || !(certs[0] instanceof X509Certificate)) {
                return false;
            }
            X509Certificate leaf = (X509Certificate) certs[0];
            Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans == null) return false;
            for (List<?> entry : sans) {
                if (entry == null || entry.size() < 2) continue;
                Object type = entry.get(0);
                Object value = entry.get(1);
                if (type instanceof Integer && ((Integer) type) == 2 && value instanceof String) {
                    if (isOfficialTelegramSan((String) value)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isOfficialTelegramSan(String san) {
        if (san == null) return false;
        String s = san.trim().toLowerCase(Locale.US);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return false;
        return "*.web.telegram.org".equals(s)
                || "web.telegram.org".equals(s)
                || s.endsWith(".web.telegram.org")
                || "*.telegram.org".equals(s)
                || "telegram.org".equals(s)
                || "*.telesco.pe".equals(s)
                || "telesco.pe".equals(s);
    }

    /**
     * Completes TLS + `/apiws` upgrade directly on an already-connected TCP socket (e.g. a live L3
     * recovery probe), avoiding a redundant second SYN RTT. Closes {@code tcpSocket} on failure.
     */
    public static WsSession dialWithConnectedSocket(Socket tcpSocket, String destHost, boolean preferMediaEdge) {
        if (tcpSocket == null) return null;
        int signedDc = ColgramTelegramDcAddresses.signedDcIdFor(destHost);
        if (signedDc == 0) {
            try { tcpSocket.close(); } catch (Throwable ignored) {}
            return null;
        }
        int normDc = Math.abs(signedDc);
        boolean media = preferMediaEdge || signedDc < 0;
        String ip = tcpSocket.getInetAddress() != null ? tcpSocket.getInetAddress().getHostAddress() : DEFAULT_EDGE_IP;
        List<EdgeEndpoint> cands = candidateEndpointsFor(normDc, media);
        String realHost = primaryWssHostForSignedDc(media ? -normDc : normDc);
        for (EdgeEndpoint ep : cands) {
            if (ip.equals(ep.ip)) {
                realHost = ep.sniHost;
                break;
            }
        }
        int mode = preferredFragMode;
        if (!(tcpSocket instanceof ColgramNativeDesync.NativeSocket) && isNativeFragMode(mode)) {
            mode = FRAG_SPOOF_SNI_PLAIN;
        }
        HandshakeResult res = tryHandshake(new DialedTcp(new EdgeEndpoint(ip, realHost), tcpSocket), normDc, media, mode, false);
        if (res.session != null) {
            return res.session;
        }
        return null;
    }

    private static HandshakeResult tryHandshake(DialedTcp tcp, int normDc, boolean media, int fragMode,
                                                boolean forPrewarm) {
        final long startNs = System.nanoTime();
        boolean wrapperRejected = false;
        boolean hostnameMismatch = false;
        String ip = tcp.endpoint.ip;
        String realHost = tcp.endpoint.sniHost;
        int wireDcId = media ? -normDc : normDc;
        String tlsSni;
        if (fragMode == FRAG_NO_SNI_TLSREC) {
            tlsSni = ip;
        } else if (isSpoofSniFragMode(fragMode)) {
            tlsSni = pickSpoofSni(fragMode);
        } else {
            tlsSni = realHost;
        }
        try {
            SSLSocketFactory sf = ColgramNativeDesync.createEngineSslSocketFactory();
            Socket underlying = wrapSocketForMode(tcp.socket, tlsSni, fragMode);
            SSLSocket ssl;
            try {
                // Key TLS session tickets on realHost (per Telegram edge cluster) while overriding wire SNI via SSLParameters.
                ssl = (SSLSocket) sf.createSocket(underlying, realHost, 443, true);
            } catch (Throwable wrapErr) {
                if (fragMode == FRAG_NONE) throw wrapErr;
                wrapperRejected = true;
                throw wrapErr;
            }
            try {
                SSLParameters params = ssl.getSSLParameters();
                if (fragMode == FRAG_NO_SNI_TLSREC) {
                    params.setServerNames(Collections.<SNIServerName>emptyList());
                } else if (tlsSni != null && !ColgramDohResolver.isIpLiteral(tlsSni)) {
                    params.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(tlsSni)));
                }
                ssl.setSSLParameters(params);
            } catch (Throwable ignored) {
            }
            ssl.setTcpNoDelay(true);
            ssl.setSoTimeout(handshakeReadTimeoutMs());
            try {
                ssl.startHandshake();
            } catch (Throwable hsErr) {
                if (fragMode != FRAG_NONE && !(hsErr instanceof java.net.SocketTimeoutException)
                        && !(hsErr instanceof javax.net.ssl.SSLException)) {
                    wrapperRejected = true;
                }
                throw hsErr;
            }
            if (tcp.socket instanceof ColgramNativeDesync.NativeSocket) {
                ((ColgramNativeDesync.NativeSocket) tcp.socket).unclampWindow();
            }
            if (!verifyOfficialTelegramSession(ssl.getSession(), realHost)) {
                hostnameMismatch = true;
                throw new IOException("TLS cert not official Telegram edge for " + realHost);
            }
            OutputStream out = wrapBufferedOutput(ssl.getOutputStream());
            InputStream in = new BufferedInputStream(ssl.getInputStream(), 32768);
            if (!upgradeWebSocket(in, out, realHost)) {
                throw new IOException("WebSocket upgrade rejected by " + ip + " (" + realHost + ")");
            }
            tuneConnectedSocket(tcp.socket);
            tuneConnectedSocket(ssl);
            ssl.setSoTimeout(0);
            int bucket = bucketFor(normDc, media);
            int rttMs = (int) Math.max(1L, Math.min(60_000L, (System.nanoTime() - startNs) / 1_000_000L));
            lastHandshakeRttMs.set(rttMs);
            lastWorkingEdgeLabel = tcp.endpoint.toString();
            lastWorkingByDc.set(bucket, tcp.endpoint);
            lastSuccessAtByDc.set(bucket, System.currentTimeMillis());
            deprioritizedUntil.remove(tcp.endpoint.key());
            if (!forPrewarm) {
                workedOnNetwork = true;
                ColgramDpiBypass.onDirectLayer1Success();
            }
            consecutiveTransportFailures.set(0);
            consecutiveFailuresByDc.set(bucket, 0);
            cooldownUntilByDc.set(bucket, 0L);
            Log.i(TAG, "WSS upgraded ok: " + tcp.endpoint + " tlsSni=" + tlsSni
                    + " fragMode=" + fragMode + " dc=" + normDc + " wireDc=" + wireDcId);
            return new HandshakeResult(new WsSession(normDc, wireDcId, ip, realHost, ssl, tcp.socket, in, out), false, false, false);
        } catch (Throwable t) {
            boolean timeoutSslOrEof = (t instanceof java.net.SocketTimeoutException)
                    || (t instanceof javax.net.ssl.SSLException)
                    || (t instanceof java.io.EOFException)
                    || (t instanceof java.io.IOException);
            if (!hostnameMismatch && timeoutSslOrEof && !forPrewarm) {
                consecutiveTransportFailures.incrementAndGet();
            }
            if (isSpoofSniFragMode(fragMode)) {
                spoofSniCursor.incrementAndGet();
            }
            Log.d(TAG, "WSS handshake failed on " + ip + " (host=" + realHost + ", tlsSni=" + tlsSni
                    + ", fragMode=" + fragMode + "): " + t.getMessage());
            try {
                tcp.socket.close();
            } catch (Throwable ignored) {
            }
            return new HandshakeResult(null, wrapperRejected, hostnameMismatch, timeoutSslOrEof);
        }
    }

    private static OutputStream wrapBufferedOutput(OutputStream rawOut) {
        if (rawOut == null || rawOut instanceof BufferedOutputStream) {
            return rawOut;
        }
        return new BufferedOutputStream(rawOut, 16384);
    }

    /**
     * Primary official Telegram WebSocket hostname for a signed DC id:
     * {@code kws{abs}-1.web.telegram.org} for media ({@code < 0}) and
     * {@code kws{abs}.web.telegram.org} for generic ({@code > 0}).
     */
    public static String primaryWssHostForSignedDc(int signedDcId) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(signedDcId);
        if (norm < 1 || norm > 5) {
            return signedDcId < 0 ? "kws2-1.web.telegram.org" : "kws2.web.telegram.org";
        }
        return signedDcId < 0
                ? ("kws" + norm + "-1.web.telegram.org")
                : ("kws" + norm + ".web.telegram.org");
    }

    /** Primary official Telegram WebSocket hostname for DC 1..5 or media DC ids (-2, -4, 10002, -10004). */
    public static String primaryWssHostForDc(int dcId) {
        return primaryWssHostForDc(dcId, Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    /** Primary official Telegram WebSocket hostname for a normalized DC and explicit media flag. */
    public static String primaryWssHostForDc(int dcId, boolean media) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm == 4 && !media) return "vesta.web.telegram.org";
        String[] hosts = hostCandidatesForDc(norm, media);
        return (hosts != null && hosts.length > 0) ? hosts[0] : "kws2.web.telegram.org";
    }

    /** Fallback non-media WebSocket hostname for a DC when media edge `kws{dc}-1` fails or stalls. */
    public static String fallbackWssHostForDc(int dcId) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm < 1 || norm > 5) return "kws2.web.telegram.org";
        return "kws" + norm + ".web.telegram.org";
    }

    /**
     * Completes a TLS 1.3 + WebSocket {@code /apiws} upgrade over an already-connected L3 relay
     * tunnel socket (e.g. segmented SOCKS5 {@code ATYP=0x03 kws*.web.telegram.org:443} or HTTP
     * {@code CONNECT}). Uses spoofed RU-whitelist SNI ({@code max.ru}/{@code vk.com}) + fragmented
     * TLS ClientHello to evade carrier TSPU SNI DPI inside plaintext SOCKS5 tunnels, while still
     * enforcing full CA + official Telegram SAN validation via {@link #verifyOfficialTelegramSession}.
     *
     * <p>Does NOT mark direct Layer-1 {@code workedOnNetwork} or clear L3-whitelist detection.
     */
    public static WsSession dialViaRelaySocket(Socket relayTcpSocket, int dcId,
                                               String realHost, String relayLabel) {
        int normDc = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (relayTcpSocket == null || normDc < 1 || normDc > 5) return null;
        boolean media = Obfuscated2FrameSplitter.isMediaDcId(dcId);
        int wireDcId = media ? -normDc : normDc;
        String host = (realHost != null && !realHost.isEmpty()) ? realHost : primaryWssHostForSignedDc(wireDcId);
        String label = (relayLabel != null && !relayLabel.isEmpty()) ? relayLabel : host;
        String tlsSni = pickSpoofSni(FRAG_SPOOF_SNI_TLSREC);
        try {
            relayTcpSocket.setTcpNoDelay(true);
            SSLSocketFactory sf = ColgramNativeDesync.createEngineSslSocketFactory();
            Socket underlying = new FragmentedHelloSocket(relayTcpSocket, tlsSni, FRAG_TCP_2BYTE_SNI);
            SSLSocket ssl = (SSLSocket) sf.createSocket(underlying, host, 443, true);
            try {
                SSLParameters params = ssl.getSSLParameters();
                if (tlsSni != null && !ColgramDohResolver.isIpLiteral(tlsSni)) {
                    params.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(tlsSni)));
                }
                ssl.setSSLParameters(params);
            } catch (Throwable ignored) {}
            ssl.setTcpNoDelay(true);
            ssl.setSoTimeout(Math.max(3000, handshakeReadTimeoutMs()));
            ssl.startHandshake();
            if (!verifyOfficialTelegramSession(ssl.getSession(), host)) {
                throw new IOException("TLS cert not official Telegram edge over relay for " + host);
            }
            OutputStream out = wrapBufferedOutput(ssl.getOutputStream());
            InputStream in = new BufferedInputStream(ssl.getInputStream(), 32768);
            if (!upgradeWebSocket(in, out, host)) {
                throw new IOException("WebSocket upgrade rejected over relay " + label + " (" + host + ")");
            }
            tuneConnectedSocket(relayTcpSocket);
            tuneConnectedSocket(ssl);
            ssl.setSoTimeout(0);
            try {
                Log.i(TAG, "WSS-over-L3-relay upgraded ok: relay=" + label + " host=" + host
                        + " tlsSni=" + tlsSni + " dc=" + normDc + " wireDc=" + wireDcId);
            } catch (Throwable ignored) {}
            return new WsSession(normDc, wireDcId, label, host, ssl, relayTcpSocket, in, out);
        } catch (Throwable t) {
            spoofSniCursor.incrementAndGet();
            try {
                Log.d(TAG, "WSS-over-L3-relay failed relay=" + label + " host=" + host + ": " + t.getMessage());
            } catch (Throwable ignored) {}
            try {
                relayTcpSocket.close();
            } catch (Throwable ignored) {}
            return null;
        }
    }

    public interface FrameConsumer {
        void onFrame(byte[] data, int off, int len) throws IOException;
    }

    /**
     * Stateful non-mutating MTProto transport packet boundary splitter for client-to-server
     * obfuscated2 streams.
     *
     * <p>Telegram's {@code /apiws} relay expects at most one complete MTProto packet per WebSocket
     * binary message; coalesced TCP writes (for example {@code msgs_ack + req_DH_params} sent
     * back-to-back during the MTProto handshake) otherwise drop the trailing packets silently.
     * We track a read-only shadow copy of the AES-CTR keystream to locate exact Abridged /
     * Intermediate / PaddedIntermediate boundaries while forwarding the original ciphertext bytes
     * verbatim.
     */
    public static final class Obfuscated2FrameSplitter {
        private static final int MAX_PACKET_BYTES = 16 * 1024 * 1024;
        private static final int RESET_BUFFER_THRESHOLD = 65536;

        private final int proto;
        private final Cipher shadowCtr;
        private final int rawDcId;
        private final byte[] hdrPlain = new byte[4];
        private int hdrFilled = 0;
        private int payloadRemaining = -1;
        private boolean fallbackPassthrough = false;
        private byte[] plainScratch = new byte[4096];
        private byte[] txBuf = new byte[2048];
        private int txLen = 0;

        private Obfuscated2FrameSplitter(int proto, Cipher shadowCtr, int rawDcId) {
            this.proto = proto;
            this.shadowCtr = shadowCtr;
            this.rawDcId = rawDcId;
        }

        public static int normalizeDcId(int rawDc) {
            int absDc = Math.abs(rawDc);
            if (absDc >= 10000) absDc -= 10000;
            return (absDc >= 1 && absDc <= 5) ? absDc : 0;
        }

        public static boolean isMediaDcId(int rawDc) {
            return rawDc < 0 || Math.abs(rawDc) >= 10000;
        }

        public int rawDcId() {
            return rawDcId;
        }

        public boolean isMediaSession() {
            return isMediaDcId(rawDcId);
        }

        public static Obfuscated2FrameSplitter create(byte[] initBuf, int initLen, int dcId) {
            return initAndPatch(initBuf, initLen, dcId);
        }

        private static Obfuscated2FrameSplitter initAndPatch(byte[] data, int length, int dcId) {
            if (data == null || length < 64 || normalizeDcId(dcId) <= 0) return null;
            try {
                byte[] key = new byte[32];
                byte[] iv = new byte[16];
                System.arraycopy(data, 8, key, 0, 32);
                System.arraycopy(data, 40, iv, 0, 16);
                Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
                ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                byte[] ks = ctr.update(new byte[64]);
                if (ks == null || ks.length < 64) return null;
                int p0 = (data[56] ^ ks[56]) & 0xff;
                int p1 = (data[57] ^ ks[57]) & 0xff;
                int p2 = (data[58] ^ ks[58]) & 0xff;
                int p3 = (data[59] ^ ks[59]) & 0xff;
                if (p0 != p1 || p1 != p2 || p2 != p3) return null;
                if (p0 != 0xef && p0 != 0xdd && p0 != 0xee) return null;
                int existingRawDc = (short) (((data[60] ^ ks[60]) & 0xff)
                        | (((data[61] ^ ks[61]) & 0xff) << 8));
                int effectiveRawDc = existingRawDc;
                if (normalizeDcId(existingRawDc) <= 0) {
                    data[60] = (byte) ((dcId & 0xff) ^ (ks[60] & 0xff));
                    data[61] = (byte) (((dcId >> 8) & 0xff) ^ (ks[61] & 0xff));
                    effectiveRawDc = (short) dcId;
                }
                return new Obfuscated2FrameSplitter(p0, ctr, effectiveRawDc);
            } catch (Throwable t) {
                return null;
            }
        }

        public synchronized void feedInitAndTail(byte[] initBuf, int initLen, FrameConsumer sink)
                throws IOException {
            sink.onFrame(initBuf, 0, 64);
            if (initLen > 64) {
                feed(initBuf, 64, initLen - 64, sink);
            }
        }

        public synchronized void feedInitAndTailToSession(byte[] initBuf, int initLen, WsSession session)
                throws IOException {
            if (initLen <= 64) {
                session.sendBinary(initBuf, 0, 64);
                return;
            }
            session.sendBinaryUnflushed(initBuf, 0, 64);
            feed(initBuf, 64, initLen - 64, session::sendBinaryUnflushed);
            session.flushOut();
        }

        public synchronized void feedToSession(byte[] data, int off, int len, WsSession session)
                throws IOException {
            if (len <= 0) return;
            feed(data, off, len, session::sendBinaryUnflushed);
            session.flushOut();
        }

        private void appendTx(byte b) {
            if (txLen + 1 > txBuf.length) {
                growTx(txLen + 1);
            }
            txBuf[txLen++] = b;
        }

        private void appendTx(byte[] data, int off, int count) {
            if (count <= 0) return;
            if (txLen + count > txBuf.length) {
                growTx(txLen + count);
            }
            System.arraycopy(data, off, txBuf, txLen, count);
            txLen += count;
        }

        private void growTx(int needed) {
            int cap = txBuf.length;
            while (cap < needed) cap = cap * 2;
            byte[] next = new byte[cap];
            if (txLen > 0) System.arraycopy(txBuf, 0, next, 0, txLen);
            txBuf = next;
        }

        public synchronized void feed(byte[] data, int off, int len, FrameConsumer sink)
                throws IOException {
            if (len <= 0) return;
            if (fallbackPassthrough) {
                flushPartialAndRaw(data, off, len, sink);
                return;
            }
            if (plainScratch.length < len) {
                plainScratch = new byte[Math.max(len, plainScratch.length * 2)];
            }
            int produced;
            try {
                produced = shadowCtr.update(data, off, len, plainScratch, 0);
            } catch (Throwable err) {
                fallbackPassthrough = true;
                flushPartialAndRaw(data, off, len, sink);
                return;
            }
            if (produced != len) {
                fallbackPassthrough = true;
                flushPartialAndRaw(data, off, len, sink);
                return;
            }

            int pos = 0;
            while (pos < len) {
                if (payloadRemaining < 0) {
                    int hdrBytePos = pos;
                    byte cByte = data[off + pos];
                    byte pByte = plainScratch[pos];
                    pos++;
                    appendTx(cByte);
                    hdrPlain[hdrFilled++] = pByte;

                    if (hdrFilled == 1 && proto == 0xef) {
                        int low7 = (pByte & 0xff) & 0x7f;
                        if (low7 == 0) {
                            fallbackPassthrough = true;
                            flushPartialAndRaw(data, off + pos, len - pos, sink);
                            return;
                        }
                        if (low7 != 0x7f) {
                            payloadRemaining = low7 * 4;
                            hdrFilled = 0;
                        }
                    } else if (hdrFilled == 4) {
                        int hInt = (hdrPlain[0] & 0xff)
                                | ((hdrPlain[1] & 0xff) << 8)
                                | ((hdrPlain[2] & 0xff) << 16)
                                | ((hdrPlain[3] & 0xff) << 24);
                        int payloadBytes;
                        if (proto == 0xef) {
                            int words = (hInt >>> 8) & 0x7fffff;
                            payloadBytes = words * 4;
                        } else {
                            payloadBytes = hInt & 0x7fffffff;
                        }
                        if (payloadBytes <= 0 || payloadBytes > MAX_PACKET_BYTES) {
                            fallbackPassthrough = true;
                            flushPartialAndRaw(data, off + pos, len - pos, sink);
                            return;
                        }
                        payloadRemaining = payloadBytes;
                        hdrFilled = 0;
                    }
                    // Fast zero-copy path when complete payload sits contiguously after its header
                    if (payloadRemaining > 0 && txLen <= 4 && (pos + payloadRemaining <= len)) {
                        int hdrStart = hdrBytePos - (txLen - 1);
                        if (hdrStart >= 0) {
                            int fullLen = txLen + payloadRemaining;
                            pos += payloadRemaining;
                            payloadRemaining = -1;
                            txLen = 0;
                            sink.onFrame(data, off + hdrStart, fullLen);
                            continue;
                        }
                    }
                } else {
                    int take = Math.min(payloadRemaining, len - pos);
                    appendTx(data, off + pos, take);
                    pos += take;
                    payloadRemaining -= take;
                    if (payloadRemaining == 0) {
                        payloadRemaining = -1;
                        emitPacket(sink);
                    }
                }
            }
        }

        private void emitPacket(FrameConsumer sink) throws IOException {
            int outLen = txLen;
            txLen = 0;
            sink.onFrame(txBuf, 0, outLen);
            if (txBuf.length > RESET_BUFFER_THRESHOLD) {
                txBuf = new byte[4096];
            }
        }

        private void flushPartialAndRaw(byte[] data, int off, int len, FrameConsumer sink)
                throws IOException {
            if (txLen > 0) {
                int pendingLen = txLen;
                txLen = 0;
                sink.onFrame(txBuf, 0, pendingLen);
                if (txBuf.length > RESET_BUFFER_THRESHOLD) {
                    txBuf = new byte[4096];
                }
            }
            if (len > 0) {
                sink.onFrame(data, off, len);
            }
        }
    }

    /** True when raw DC id represents a media / CDN session (`-2`, `-4`, `10002`, `-10004`). */
    public static boolean isMediaDcId(int rawDc) {
        return Obfuscated2FrameSplitter.isMediaDcId(rawDc);
    }

    /** Extracts signed obfuscated2 raw DC id (`-2`, `-4`, `10002`, `-10004`, `1..5`) without mutating. */
    public static int extractRawDcFromObfuscated2(byte[] data, int length) {
        if (data == null || length < 64) return 0;
        try {
            byte[] key = new byte[32];
            byte[] iv = new byte[16];
            System.arraycopy(data, 8, key, 0, 32);
            System.arraycopy(data, 40, iv, 0, 16);
            Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
            ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ks = ctr.update(new byte[64]);
            if (ks == null || ks.length < 64) return 0;
            int p0 = (data[56] ^ ks[56]) & 0xff;
            int p1 = (data[57] ^ ks[57]) & 0xff;
            int p2 = (data[58] ^ ks[58]) & 0xff;
            int p3 = (data[59] ^ ks[59]) & 0xff;
            if (p0 != p1 || p1 != p2 || p2 != p3) return 0;
            if (p0 != 0xef && p0 != 0xdd && p0 != 0xee) return 0;
            return (short) (((data[60] ^ ks[60]) & 0xff) | (((data[61] ^ ks[61]) & 0xff) << 8));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Validate un-secreted obfuscated2 from `tgnet` (`useSecret == 0`) and inject `dc_id` into
     * bytes `[60..61]` without disturbing the AES-CTR keystream from byte `64` onward.
     * Existing valid positive or media DC ids (`-2`, `-4`, `10002`, `-10004`) are preserved verbatim.
     */
    public static boolean patchObfuscated2Header(byte[] data, int length, int dcId) {
        return Obfuscated2FrameSplitter.initAndPatch(data, length, dcId) != null;
    }

    private static void tuneConnectedSocket(Socket s) {
        if (s == null) return;
        try { s.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { s.setKeepAlive(true); } catch (Throwable ignored) {}
    }

    public static long pumpUpstreamFrames(WsSession session, OutputStream clientOut,
                                          OnBytes onReply) throws IOException {
        long total = 0L;
        byte[] buf = ColgramDpiBypass.borrowRelayBuffer();
        byte[] maskBuf = new byte[4];
        try {
            InputStream in = session.in;
            if (!(in instanceof BufferedInputStream) && !(in instanceof ByteArrayInputStream)) {
                in = new BufferedInputStream(in, 32768);
            }
            boolean inFragmentedData = false;
            if (session.socket != null) {
                try { session.socket.setSoTimeout(0); } catch (Throwable ignored) {}
            }
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7f;
                if (payloadLen == 126) {
                    int hi = readUnsignedByte(in);
                    int lo = readUnsignedByte(in);
                    payloadLen = (hi << 8) | lo;
                } else if (payloadLen == 127) {
                    payloadLen = 0L;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | readUnsignedByte(in);
                    }
                    if (payloadLen < 0L) throw new IOException("Invalid WS frame length");
                }
                byte[] mask = null;
                if (masked) {
                    readFully(in, maskBuf, 4);
                    mask = maskBuf;
                }

                if (opcode >= 0x8 && (!fin || payloadLen > 125L)) {
                    drainPayload(in, payloadLen);
                    session.sendClose();
                    break;
                }
                if (opcode == 0x8) {
                    Log.d(TAG, "WS close frame from " + session.edgeIp + " payloadLen=" + payloadLen);
                    drainPayload(in, payloadLen);
                    session.sendClose();
                    break;
                }
                if (opcode == 0x9) {
                    int ctrlLen = (int) Math.min(payloadLen, 125L);
                    byte[] ctrl = new byte[ctrlLen];
                    if (ctrlLen > 0) readFully(in, ctrl, ctrlLen);
                    if (mask != null && ctrlLen > 0) unmask(ctrl, 0, ctrlLen, mask, 0);
                    if (payloadLen > ctrlLen) drainPayload(in, payloadLen - ctrlLen);
                    session.sendPong(ctrl, ctrlLen);
                    continue;
                }
                if (opcode == 0xA) {
                    drainPayload(in, payloadLen);
                    continue;
                }
                if (opcode == 0x0) {
                    if (!inFragmentedData) {
                        drainPayload(in, payloadLen);
                        continue;
                    }
                    if (fin) inFragmentedData = false;
                } else if (opcode == 0x1 || opcode == 0x2) {
                    inFragmentedData = !fin;
                } else {
                    drainPayload(in, payloadLen);
                    continue;
                }

                long left = payloadLen;
                int maskOffset = 0;
                while (left > 0L) {
                    int toRead = (int) Math.min(left, buf.length);
                    int n = in.read(buf, 0, toRead);
                    if (n < 0) throw new EOFException("WS frame truncated");
                    if (mask != null) {
                        unmask(buf, 0, n, mask, maskOffset);
                        maskOffset = (maskOffset + n) & 3;
                    }
                    clientOut.write(buf, 0, n);
                    total += n;
                    left -= n;
                    if (onReply != null) onReply.onBytes(n);
                }
                if ((fin && !inFragmentedData) || in.available() <= 0) {
                    clientOut.flush();
                }
            }
            clientOut.flush();
            return total;
        } finally {
            ColgramDpiBypass.recycleRelayBuffer(buf);
        }
    }

    public interface OnBytes {
        void onBytes(int count);
    }

    private static String[] dcHosts(int dcId) {
        return hostCandidatesForDc(dcId, Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    private static String[] dcHosts(int dcId, boolean media) {
        return hostCandidatesForDc(dcId, media);
    }

    static String[] hostCandidatesForDc(int normDc, boolean media) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(normDc);
        if (norm == 0) norm = normDc;
        switch (norm) {
            case 1:
                return media
                        ? new String[]{"kws1-1.web.telegram.org", "kws1.web.telegram.org", "zws1.web.telegram.org", "pluto.web.telegram.org"}
                        : new String[]{"kws1.web.telegram.org", "kws1-1.web.telegram.org", "zws1.web.telegram.org", "pluto.web.telegram.org"};
            case 2:
                return media
                        ? new String[]{"kws2-1.web.telegram.org", "kws2.web.telegram.org", "zws2.web.telegram.org", "venus.web.telegram.org"}
                        : new String[]{"kws2.web.telegram.org", "kws2-1.web.telegram.org", "zws2.web.telegram.org", "venus.web.telegram.org"};
            case 3:
                return media
                        ? new String[]{"kws3-1.web.telegram.org", "kws3.web.telegram.org", "zws3.web.telegram.org", "aurora.web.telegram.org"}
                        : new String[]{"kws3.web.telegram.org", "kws3-1.web.telegram.org", "zws3.web.telegram.org", "aurora.web.telegram.org"};
            case 4:
                return media
                        ? new String[]{"kws4-1.web.telegram.org", "kws4.web.telegram.org", "vesta.web.telegram.org", "zws4.web.telegram.org"}
                        : new String[]{"kws4.web.telegram.org", "vesta.web.telegram.org", "kws4-1.web.telegram.org", "zws4.web.telegram.org"};
            case 5:
                return media
                        ? new String[]{"kws5-1.web.telegram.org", "kws5.web.telegram.org", "zws5.web.telegram.org", "flora.web.telegram.org"}
                        : new String[]{"kws5.web.telegram.org", "kws5-1.web.telegram.org", "zws5.web.telegram.org", "flora.web.telegram.org"};
            default:
                return media
                        ? new String[]{"kws2-1.web.telegram.org", "kws2.web.telegram.org", "vesta.web.telegram.org"}
                        : new String[]{"kws2.web.telegram.org", "kws2-1.web.telegram.org", "vesta.web.telegram.org"};
        }
    }

    private static String[] sharedEdgeHostsForDc(int dcId) {
        return sharedEdgeHostsForDc(dcId, Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    private static String[] sharedEdgeHostsForDc(int dcId, boolean media) {
        int norm = Obfuscated2FrameSplitter.normalizeDcId(dcId);
        if (norm == 2) {
            return media
                    ? new String[]{"kws2-1.web.telegram.org", "kws2.web.telegram.org", "zws2.web.telegram.org", "venus.web.telegram.org"}
                    : SHARED_EDGE_HOSTS_DC2;
        }
        if (norm == 4) {
            return media
                    ? new String[]{"kws4-1.web.telegram.org", "kws4.web.telegram.org", "vesta.web.telegram.org", "zws4.web.telegram.org"}
                    : SHARED_EDGE_HOSTS_DC4;
        }
        return new String[0];
    }

    private static boolean isSharedEdgeSniForDc(String sniHost, int dcId) {
        if (sniHost == null) return false;
        for (String host : sharedEdgeHostsForDc(dcId)) {
            if (host.equalsIgnoreCase(sniHost)) return true;
        }
        return false;
    }

    /**
     * Wraps the underlying plain TCP socket during TLS handshake so the very first TLS ClientHello
     * record is segmented at the SNI/record boundary via {@link ColgramTlsMimic#fragment(byte[])},
     * bypassing SNI inspection without altering any byte of the TLS transcript.
     */
    private static final class FragmentedHelloSocket extends Socket {
        private final Socket delegate;
        private final String sniHost;
        private final int fragMode;
        private volatile OutputStream wrappedOut;

        FragmentedHelloSocket(Socket delegate, String sniHost, int fragMode) {
            this.delegate = delegate;
            this.sniHost = sniHost;
            this.fragMode = fragMode;
        }

        @Override public java.io.InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
        @Override public void connect(java.net.SocketAddress endpoint, int timeout) throws IOException { delegate.connect(endpoint, timeout); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isBound() { return delegate.isBound(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
        @Override public void shutdownInput() throws IOException { delegate.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { delegate.shutdownOutput(); }
        @Override public void setSoTimeout(int timeout) throws java.net.SocketException { delegate.setSoTimeout(timeout); }
        @Override public int getSoTimeout() throws java.net.SocketException { return delegate.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean on) throws java.net.SocketException { delegate.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws java.net.SocketException { return delegate.getTcpNoDelay(); }
        @Override public void setSendBufferSize(int size) throws java.net.SocketException { delegate.setSendBufferSize(size); }
        @Override public int getSendBufferSize() throws java.net.SocketException { return delegate.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int size) throws java.net.SocketException { delegate.setReceiveBufferSize(size); }
        @Override public int getReceiveBufferSize() throws java.net.SocketException { return delegate.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean on) throws java.net.SocketException { delegate.setKeepAlive(on); }
        @Override public boolean getKeepAlive() throws java.net.SocketException { return delegate.getKeepAlive(); }
        @Override public void setSoLinger(boolean on, int linger) throws java.net.SocketException { delegate.setSoLinger(on, linger); }
        @Override public int getSoLinger() throws java.net.SocketException { return delegate.getSoLinger(); }
        @Override public void setReuseAddress(boolean on) throws java.net.SocketException { delegate.setReuseAddress(on); }
        @Override public boolean getReuseAddress() throws java.net.SocketException { return delegate.getReuseAddress(); }
        @Override public void setOOBInline(boolean on) throws java.net.SocketException { delegate.setOOBInline(on); }
        @Override public boolean getOOBInline() throws java.net.SocketException { return delegate.getOOBInline(); }
        @Override public java.net.InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public java.net.InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public int getLocalPort() { return delegate.getLocalPort(); }
        @Override public java.net.SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }
        @Override public java.net.SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
        @Override public void close() throws IOException { delegate.close(); }

        @Override
        public OutputStream getOutputStream() throws IOException {
            OutputStream existing = wrappedOut;
            if (existing != null) return existing;
            final OutputStream raw = delegate.getOutputStream();
            OutputStream fresh = new OutputStream() {
                private boolean firstHelloSent;

                @Override
                public synchronized void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public synchronized void write(byte[] b, int off, int len) throws IOException {
                    if (!firstHelloSent && len >= 11 && (b[off] & 0xFF) == 22 && b[off + 1] == 3 && (b[off + 5] & 0xFF) == 1) {
                        firstHelloSent = true;
                        byte[] copy = new byte[len];
                        System.arraycopy(b, off, copy, 0, len);
                        if (fragMode == FRAG_TCP_2BYTE_SNI) {
                            // Proven on MegaFon/Yota LTE inside plaintext SOCKS5: split TLS
                            // ClientHello into 2-record TLS SNI fragments + 2-byte TCP writes across SNI.
                            byte[][] recs = ColgramTlsMimic.splitTlsRecordAtSni(copy, 2);
                            if (recs != null && recs.length >= 2) {
                                byte[] r0 = recs[0];
                                int headCut = Math.min(r0.length, 6);
                                for (int pos = 0; pos < headCut; pos += 2) {
                                    raw.write(r0, pos, Math.min(2, headCut - pos));
                                    raw.flush();
                                }
                                if (headCut < r0.length) {
                                    raw.write(r0, headCut, r0.length - headCut);
                                    raw.flush();
                                }
                                for (int r = 1; r < recs.length; r++) {
                                    raw.write(recs[r]);
                                    raw.flush();
                                }
                                return;
                            }
                            int[] sniSpan = ColgramTlsMimic.findTlsSniSpan(copy, copy.length);
                            int segEnd = sniSpan != null ? Math.min(copy.length, sniSpan[1]) : Math.min(copy.length, 64);
                            int segStart = sniSpan != null ? Math.max(0, sniSpan[0] - 2) : 0;
                            if (segStart > 0) {
                                raw.write(copy, 0, segStart);
                                raw.flush();
                            }
                            for (int pos = segStart; pos < segEnd; pos += 2) {
                                raw.write(copy, pos, Math.min(2, segEnd - pos));
                                raw.flush();
                            }
                            if (segEnd < copy.length) {
                                raw.write(copy, segEnd, copy.length - segEnd);
                                raw.flush();
                            }
                            return;
                        }
                        byte[][] pieces = null;
                        if (fragMode == FRAG_TLS_RECORD_SNI
                                || fragMode == FRAG_SPOOF_SNI_TLSREC
                                || fragMode == FRAG_SPOOF_SNI_ALT
                                || fragMode == FRAG_NO_SNI_TLSREC) {
                            pieces = ColgramTlsMimic.splitTlsRecordAtSni(copy, 2);
                        }
                        if (pieces == null) {
                            pieces = ColgramTlsMimic.fragment(copy, new java.util.Random());
                        }
                        for (int i = 0; i < pieces.length; i++) {
                            raw.write(pieces[i]);
                            raw.flush();
                        }
                        return;
                    }
                    firstHelloSent = true;
                    raw.write(b, off, len);
                }

                @Override public void flush() throws IOException { raw.flush(); }
                @Override public void close() throws IOException { raw.close(); }
            };
            wrappedOut = fresh;
            return fresh;
        }
    }

    static void recordFailure(int dcId) {
        recordFailure(Obfuscated2FrameSplitter.normalizeDcId(dcId), Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    static void recordFailure(int normDc, boolean media) {
        int bucket = bucketFor(normDc, media);
        if (consecutiveFailuresByDc.incrementAndGet(bucket) >= 5) {
            cooldownUntilByDc.set(bucket, System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
            consecutiveFailuresByDc.set(bucket, 0);
        }
    }

    static boolean isInCooldown(int dcId) {
        return isInCooldown(Obfuscated2FrameSplitter.normalizeDcId(dcId), Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    static boolean isInCooldown(int normDc, boolean media) {
        return System.currentTimeMillis() < cooldownUntilByDc.get(bucketFor(normDc, media));
    }

    private static Set<String> activeDeprioritizedKeys(long now) {
        if (deprioritizedUntil.isEmpty()) return Collections.emptySet();
        Set<String> active = new LinkedHashSet<>();
        for (Map.Entry<String, Long> e : deprioritizedUntil.entrySet()) {
            Long until = e.getValue();
            if (until == null || until <= now) {
                deprioritizedUntil.remove(e.getKey(), until);
            } else {
                active.add(e.getKey());
            }
        }
        return active;
    }

    private static List<EdgeEndpoint> candidateEndpointsFor(int dcId) {
        return candidateEndpointsFor(dcId, Obfuscated2FrameSplitter.isMediaDcId(dcId));
    }

    private static boolean isIncludeIpv6Current() {
        boolean isProtected = ColgramDpiBypass.activeSystemDpiVpn() != null;
        return ColgramDohResolver.hasRoutableIpv6ForCurrentSocket(isProtected)
                || (isIpv6BypassEnabledSafe() && (isProtected || !ColgramDpiBypass.isOverlayVpnActive()));
    }

    private static List<EdgeEndpoint> candidateEndpointsFor(int dcId, boolean media) {
        long now = System.currentTimeMillis();
        if (now - resolvedAt > DNS_CACHE_TTL_MS && refreshInFlight.compareAndSet(false, true)) {
            try {
                RACE_POOL.execute(ColgramTelegramWsBridge::refreshResolvedIps);
            } catch (Throwable rejected) {
                refreshInFlight.set(false);
            }
        }
        int bucket = bucketFor(dcId, media);
        return buildCandidateEndpoints(dcId, lastWorkingByDc.get(bucket), perHostIps,
                activeDeprioritizedKeys(now), media, isIncludeIpv6Current());
    }

    /**
     * Pair each candidate IP strictly with an SNI/Host hostname belonging to {@code dcId}.
     * Telegram's shared European WebSocket edge ({@code 149.154.167.220}) enforces strict Host->DC
     * routing during {@code PQInnerDataDc} (returning MTProto error {@code -444} if e.g. DC2
     * traffic is sent over {@code vesta}/{@code kws4} or DC4 traffic over {@code kws2}).
     *
     * <p>For DC2/DC4, proven {@code 149.154.167.220} is ALWAYS ranked ahead of foreign DoH answers
     * (such as post-SYN blackholed {@code 149.154.167.99}).
     */
    static List<EdgeEndpoint> buildCandidateEndpoints(
            int dcId,
            EdgeEndpoint lastWorking,
            Map<String, List<String>> hostIps,
            Set<String> deprioritizedKeys) {
        return buildCandidateEndpoints(dcId, lastWorking, hostIps, deprioritizedKeys,
                Obfuscated2FrameSplitter.isMediaDcId(dcId), true);
    }

    static List<EdgeEndpoint> buildCandidateEndpoints(
            int dcId,
            EdgeEndpoint lastWorking,
            Map<String, List<String>> hostIps,
            Set<String> deprioritizedKeys,
            boolean media) {
        return buildCandidateEndpoints(dcId, lastWorking, hostIps, deprioritizedKeys, media, true);
    }

    static List<EdgeEndpoint> buildCandidateEndpoints(
            int dcId,
            EdgeEndpoint lastWorking,
            Map<String, List<String>> hostIps,
            Set<String> deprioritizedKeys,
            boolean media,
            boolean includeIpv6) {
        LinkedHashMap<String, EdgeEndpoint> ordered = new LinkedHashMap<>();
        Set<String> skipped = deprioritizedKeys == null ? Collections.emptySet() : deprioritizedKeys;
        int normDc = Obfuscated2FrameSplitter.normalizeDcId(dcId);

        if (lastWorking != null
                && (includeIpv6 || lastWorking.ip.indexOf(':') < 0)
                && !"149.154.167.99".equals(lastWorking.ip)
                && !skipped.contains(lastWorking.key())
                && isDcHostMatch(lastWorking.sniHost, normDc)
                && (!media || lastWorking.sniHost.toLowerCase(Locale.US).contains("-1."))
                && !(DEFAULT_EDGE_IP.equals(lastWorking.ip) && !isSharedEdgeSniForDc(lastWorking.sniHost, normDc))) {
            ordered.put(lastWorking.key(), lastWorking);
        }

        // For DC2/DC4, ALWAYS rank proven 149.154.167.220 ahead of DoH-resolved IPs (never dead .99).
        for (String host : sharedEdgeHostsForDc(normDc, media)) {
            EdgeEndpoint primaryShared = new EdgeEndpoint(DEFAULT_EDGE_IP, host);
            if (!skipped.contains(primaryShared.key())) ordered.putIfAbsent(primaryShared.key(), primaryShared);
        }

        // Next include fresh DoH-resolved DC-specific IPs (never pairing .220 with non-DC2/4 hosts, retiring .99).
        String[] dcSnis = hostCandidatesForDc(normDc, media);
        if (hostIps != null) {
            for (String host : dcSnis) {
                List<String> ips = hostIps.get(host);
                if (ips == null) continue;
                for (String ip : ips) {
                    if (ip == null || ip.isEmpty() || "149.154.167.99".equals(ip)) continue;
                    if (!includeIpv6 && ip.indexOf(':') >= 0) continue;
                    if (DEFAULT_EDGE_IP.equals(ip) && !isSharedEdgeSniForDc(host, normDc)) continue;
                    EdgeEndpoint ep = new EdgeEndpoint(ip, host);
                    if (!skipped.contains(ep.key())) ordered.putIfAbsent(ep.key(), ep);
                }
            }
        }

        // Live-verified official Telegram WebSocket edge IPv4 (+ IPv6 only when includeIpv6) per DC.
        for (String secIp : secondaryEdgeIpsForDc(normDc)) {
            if (secIp == null) continue;
            if (!includeIpv6 && secIp.indexOf(':') >= 0) continue;
            for (String host : dcSnis) {
                if (DEFAULT_EDGE_IP.equals(secIp) && !isSharedEdgeSniForDc(host, normDc)) continue;
                EdgeEndpoint sec = new EdgeEndpoint(secIp, host);
                if (!skipped.contains(sec.key())) ordered.putIfAbsent(sec.key(), sec);
            }
        }

        if (ordered.isEmpty()) {
            // Every candidate was temporarily deprioritized or filtered; re-seed strictly within dcId's
            // own verified WSS edges so DC1/DC3/DC5 never cross-wire onto DC2's kws2 endpoint.
            for (String secIp : secondaryEdgeIpsForDc(normDc)) {
                if (secIp == null) continue;
                if (!includeIpv6 && secIp.indexOf(':') >= 0) continue;
                for (String host : dcSnis) {
                    if (DEFAULT_EDGE_IP.equals(secIp) && !isSharedEdgeSniForDc(host, normDc)) continue;
                    EdgeEndpoint ep = new EdgeEndpoint(secIp, host);
                    ordered.putIfAbsent(ep.key(), ep);
                }
            }
            if (ordered.isEmpty()) {
                ordered.put(DEFAULT_ENDPOINT.key(), DEFAULT_ENDPOINT);
            }
        }

        // Escape hatch at tail: always append fresh-dial shared primary IPv4 (.220) + DoH IPv4
        // endpoints so temporary cooldowns never collapse all IPv4 routes.
        for (String host : sharedEdgeHostsForDc(normDc, media)) {
            EdgeEndpoint freshPrimary = new EdgeEndpoint(DEFAULT_EDGE_IP, host);
            ordered.putIfAbsent(freshPrimary.key(), freshPrimary);
        }
        if (hostIps != null) {
            for (String host : dcSnis) {
                List<String> ips = hostIps.get(host);
                if (ips == null) continue;
                for (String ip : ips) {
                    if (ip == null || ip.isEmpty() || "149.154.167.99".equals(ip)) continue;
                    if (ip.indexOf(':') >= 0) continue;
                    if (DEFAULT_EDGE_IP.equals(ip) && !isSharedEdgeSniForDc(host, normDc)) continue;
                    EdgeEndpoint ep = new EdgeEndpoint(ip, host);
                    ordered.putIfAbsent(ep.key(), ep);
                }
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private static boolean isDcHostMatch(String sniHost, int dcId) {
        if (sniHost == null) return false;
        for (String host : dcHosts(dcId)) {
            if (host.equalsIgnoreCase(sniHost)) return true;
        }
        return false;
    }

    private static String[] secondaryEdgeIpsForDc(int dcId) {
        switch (dcId) {
            case 1:
            case 3:
                return VERIFIED_WSS_IPS_DC1_DC3;
            case 2:
            case 4:
                return VERIFIED_WSS_IPS_DC2_DC4;
            case 5:
                return VERIFIED_WSS_IPS_DC5;
            default:
                return new String[0];
        }
    }

    private static void refreshResolvedIps() {
        final AtomicInteger resolvedCount = new AtomicInteger(0);
        final AtomicInteger remaining = new AtomicInteger(ALL_EDGE_HOSTS.length);
        for (final String host : ALL_EDGE_HOSTS) {
            RACE_POOL.execute(() -> {
                try {
                    InetAddress[] addrs = ColgramDohResolver.resolveWithIpv6(host);
                    if (addrs == null || addrs.length == 0) {
                        addrs = ColgramDohResolver.resolveOrSystem(host);
                    }
                    if (addrs != null) {
                        List<String> v4 = new ArrayList<>();
                        List<String> v6 = new ArrayList<>();
                        for (InetAddress a : addrs) {
                            if (a == null || a.getAddress() == null) continue;
                            String ip = a.getHostAddress();
                            if (a.getAddress().length == 4) {
                                if (!v4.contains(ip)) v4.add(ip);
                            } else if (a.getAddress().length == 16) {
                                if (!v6.contains(ip)) v6.add(ip);
                            }
                        }
                        List<String> combined = new ArrayList<>(v4.size() + v6.size());
                        combined.addAll(v4);
                        combined.addAll(v6);
                        if (!combined.isEmpty()) {
                            perHostIps.put(host, Collections.unmodifiableList(combined));
                            resolvedCount.incrementAndGet();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        long now = System.currentTimeMillis();
                        if (resolvedCount.get() > 0) {
                            resolvedAt = now;
                        } else {
                            // Do not cache an empty DoH failure for 5 minutes; retry after 15s.
                            resolvedAt = Math.max(0L, now - DNS_CACHE_TTL_MS + DNS_RETRY_BACKOFF_MS);
                        }
                        refreshInFlight.set(false);
                    }
                }
            });
        }
    }

    private static final class DialedTcp {
        final EdgeEndpoint endpoint;
        final Socket socket;

        DialedTcp(EdgeEndpoint endpoint, Socket socket) {
            this.endpoint = endpoint;
            this.socket = socket;
        }
    }

    private static boolean isIpv6BypassEnabledSafe() {
        try {
            return ColgramConfig.isIpv6BypassEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Selects up to 4 race endpoints from {@code candidates}. When {@code includeIpv6} is true,
     * scans the entire list so verified IPv6 WSS edges are NEVER hidden behind 3+ IPv4 entries,
     * interleaving IPv4 + IPv6 for fast Happy-Eyeballs dialing on dual-stack/IPv6-routed carriers.
     */
    static List<EdgeEndpoint> selectRaceEndpoints(List<EdgeEndpoint> candidates, boolean includeIpv6) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        List<EdgeEndpoint> v4 = new ArrayList<>(4);
        List<EdgeEndpoint> v6 = new ArrayList<>(2);
        Set<String> seenIps = new LinkedHashSet<>();
        for (EdgeEndpoint ep : candidates) {
            if (ep == null || ep.ip == null || ep.ip.isEmpty()) continue;
            boolean isV6 = ep.ip.indexOf(':') >= 0;
            if (isV6) {
                if (v6.size() < 2 && seenIps.add(ep.ip)) v6.add(ep);
            } else {
                if (v4.size() < 4 && seenIps.add(ep.ip)) v4.add(ep);
            }
            if (v4.size() >= 4 && (!includeIpv6 || v6.size() >= 2)) break;
        }
        List<EdgeEndpoint> picked = new ArrayList<>(4);
        if (includeIpv6 && !v6.isEmpty()) {
            // Interleave IPv4 and IPv6 so slot 1 races an IPv6 edge after just 40ms
            int i4 = 0, i6 = 0;
            while (picked.size() < 4 && (i4 < v4.size() || i6 < v6.size())) {
                if (i4 < v4.size() && picked.size() < 4) picked.add(v4.get(i4++));
                if (i6 < v6.size() && picked.size() < 4) picked.add(v6.get(i6++));
            }
        } else {
            for (EdgeEndpoint ep : v4) {
                if (picked.size() >= 4) break;
                picked.add(ep);
            }
            if (picked.isEmpty()) {
                for (EdgeEndpoint ep : v6) {
                    if (picked.size() >= 4) break;
                    picked.add(ep);
                }
            }
        }
        for (EdgeEndpoint ep : candidates) {
            if (picked.size() >= 4) break;
            if (ep != null && !picked.contains(ep) && (includeIpv6 || ep.ip.indexOf(':') < 0 || v4.isEmpty())) {
                picked.add(ep);
            }
        }
        return picked;
    }

    /**
     * Staggered parallel Happy-Eyeballs (IPv4 + IPv6) SYN race: covers carrier IPv4 SYN drops on
     * Russian 4G/LTE towers by always reserving a race slot for an IPv6 Telegram edge when present,
     * and opens a native ByeDPI socket directly whenever a native desync mode is active.
     */
    private static DialedTcp connectEdgeRace(List<EdgeEndpoint> candidates,
                                             final ColgramDohResolver.SocketProtector protector,
                                             final ColgramNativeDesync.FdProtector fdProtector,
                                             final int fragMode,
                                             final int attemptTimeoutMs) {
        if (candidates.isEmpty()) return null;
        boolean isProtected = (protector != null || fdProtector != null
                || ColgramDpiBypass.activeSystemDpiVpn() != null);
        boolean includeIpv6 = ColgramDohResolver.hasRoutableIpv6ForCurrentSocket(isProtected)
                || (isIpv6BypassEnabledSafe() && (isProtected || !ColgramDpiBypass.isOverlayVpnActive()));
        List<EdgeEndpoint> picked = selectRaceEndpoints(candidates, includeIpv6);
        if (picked.isEmpty()) return null;
        EdgeEndpoint ep0 = picked.get(0);
        EdgeEndpoint ep1 = picked.size() > 1 ? picked.get(1) : ep0;
        EdgeEndpoint ep2 = picked.size() > 2 ? picked.get(2) : ep0;
        EdgeEndpoint ep3 = picked.size() > 3 ? picked.get(3) : ep1;
        EdgeEndpoint[] plan = {ep0, ep1, ep2, ep3};
        long[] delaysMs = {
                0L,
                40L,
                140L,
                260L
        };

        // Prefer NativeSocket for ALL WSS connections when available so every WSS socket inherits
        // TCP_KEEPIDLE=180s / TCP_KEEPINTVL=30s / TCP_KEEPCNT=3 / TCP_USER_TIMEOUT=60000ms.
        final boolean useNativeSocket = ColgramNativeDesync.isAvailable()
                && (protector == null || fdProtector != null);
        final boolean useNativeDesync = useNativeSocket && isNativeFragMode(fragMode);
        // Never pass invalid TCP_MAXSEG < 88 (Linux TCP_MIN_MSS=88 -> EINVAL).
        final int mssClamp = (useNativeDesync
                && (fragMode == FRAG_NATIVE_MSS_CLAMP_TLSREC
                    || fragMode == FRAG_NATIVE_MSS64_OOB1_TLSREC)) ? 88 : 0;
        // Avoid pre-SYN TCP_WINDOW_CLAMP on primary/media/OOB modes (permanently caps TCP Window Scale).
        final int windowClamp = (useNativeDesync && cellularNetwork && fragMode == FRAG_NATIVE_MSS_CLAMP_TLSREC)
                ? 2048 : -1;

        final Object winnerLock = new Object();
        final AtomicReference<DialedTcp> winner = new AtomicReference<>();
        final boolean[] timedOut = new boolean[]{false};
        final AtomicBoolean done = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger pending = new AtomicInteger(plan.length);

        for (int i = 0; i < plan.length; i++) {
            final EdgeEndpoint ep = plan[i];
            final long delay = delaysMs[i];
            try {
                TCP_RACE_POOL.execute(() -> {
                    Socket sock = null;
                    try {
                        if (delay > 0L) Thread.sleep(delay);
                        if (done.get()) return;
                        if (useNativeSocket) {
                            sock = ColgramNativeDesync.connect(
                                    ep.ip, 443, attemptTimeoutMs, mssClamp, windowClamp, fdProtector);
                        } else {
                            sock = new Socket();
                            sock.setTcpNoDelay(true);
                            if (protector != null && !protector.protect(sock)) {
                                return;
                            }
                            ColgramDpiBypass.connectTracked(sock, new InetSocketAddress(ep.ip, 443), attemptTimeoutMs);
                        }
                        DialedTcp candidate = new DialedTcp(ep, sock);
                        synchronized (winnerLock) {
                            if (!timedOut[0] && winner.get() == null) {
                                winner.set(candidate);
                                done.set(true);
                                latch.countDown();
                                sock = null;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (sock != null) {
                            try {
                                sock.close();
                            } catch (Throwable ignored) {
                            }
                        }
                        if (pending.decrementAndGet() == 0) {
                            done.set(true);
                            latch.countDown();
                        }
                    }
                });
            } catch (Throwable rejected) {
                if (pending.decrementAndGet() == 0) {
                    done.set(true);
                    latch.countDown();
                }
            }
        }
        try {
            latch.await(connectRaceBudgetMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        DialedTcp result;
        synchronized (winnerLock) {
            timedOut[0] = true;
            done.set(true);
            result = winner.get();
        }
        if (result == null) {
            Log.d(TAG, "WS TCP connect race timed out (" + ep0 + ", " + ep1 + ")");
        }
        return result;
    }

    private static DialedTcp connectEdgeRace(List<EdgeEndpoint> candidates,
                                             final ColgramDohResolver.SocketProtector protector,
                                             final ColgramNativeDesync.FdProtector fdProtector,
                                             final int fragMode) {
        return connectEdgeRace(candidates, protector, fdProtector, fragMode, connectAttemptTimeoutMs());
    }

    private static boolean upgradeWebSocket(InputStream in, OutputStream out, String host)
            throws Exception {
        byte[] keyBytes = new byte[16];
        synchronized (SHARED_MASK_SEEDER) {
            SHARED_MASK_SEEDER.nextBytes(keyBytes);
        }
        String wsKey = encodeBase64(keyBytes);
        String expectedAccept = encodeBase64(MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_GUID).getBytes(StandardCharsets.US_ASCII)));

        String req = ColgramPolymorphicStealth.buildPolymorphicWsUpgradeRequest(host, wsKey);
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        ByteArrayOutputStream rawHdr = new ByteArrayOutputStream();
        int window = 0;
        while (rawHdr.size() < 8192) {
            int b = in.read();
            if (b < 0) return false;
            rawHdr.write(b);
            window = (window << 8) | (b & 0xff);
            if (window == 0x0d0a0d0a) break;
        }
        String headers = rawHdr.toString(StandardCharsets.ISO_8859_1.name());
        String[] lines = headers.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/1.1 101")) return false;
        boolean acceptMatches = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String name = lines[i].substring(0, colon).trim();
            if ("Sec-WebSocket-Accept".equalsIgnoreCase(name)) {
                String value = lines[i].substring(colon + 1).trim();
                acceptMatches = expectedAccept.equals(value);
            }
        }
        return acceptMatches;
    }

    private static void unmask(byte[] buf, int off, int len, byte[] mask, int maskOffset) {
        if (len <= 0) return;
        byte m0 = mask[maskOffset & 3];
        byte m1 = mask[(maskOffset + 1) & 3];
        byte m2 = mask[(maskOffset + 2) & 3];
        byte m3 = mask[(maskOffset + 3) & 3];
        int i = 0;
        int limit4 = len - 3;
        while (i < limit4) {
            buf[off + i] ^= m0;
            buf[off + i + 1] ^= m1;
            buf[off + i + 2] ^= m2;
            buf[off + i + 3] ^= m3;
            i += 4;
        }
        if (i < len) {
            buf[off + i] ^= m0;
            if (i + 1 < len) {
                buf[off + i + 1] ^= m1;
                if (i + 2 < len) {
                    buf[off + i + 2] ^= m2;
                }
            }
        }
    }

    private static int readUnsignedByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("Unexpected EOF");
        return b;
    }

    private static void readFully(InputStream in, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(dst, off, len - off);
            if (n < 0) throw new EOFException("Unexpected EOF");
            off += n;
        }
    }

    private static void drainPayload(InputStream in, long len) throws IOException {
        byte[] discard = new byte[4096];
        long left = len;
        while (left > 0L) {
            int n = in.read(discard, 0, (int) Math.min(left, discard.length));
            if (n < 0) throw new EOFException("Unexpected EOF");
            left -= n;
        }
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String encodeBase64(byte[] input) {
        StringBuilder sb = new StringBuilder(((input.length + 2) / 3) * 4);
        for (int i = 0; i < input.length; i += 3) {
            int b0 = input[i] & 0xff;
            int b1 = i + 1 < input.length ? (input[i + 1] & 0xff) : 0;
            int b2 = i + 2 < input.length ? (input[i + 2] & 0xff) : 0;
            sb.append(B64[b0 >>> 2]);
            sb.append(B64[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            sb.append(i + 1 < input.length ? B64[((b1 & 0x0f) << 2) | (b2 >>> 6)] : '=');
            sb.append(i + 2 < input.length ? B64[b2 & 0x3f] : '=');
        }
        return sb.toString();
    }
}
