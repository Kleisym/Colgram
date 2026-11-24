package org.colgram.core;

import android.util.Log;

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
            "zws2.web.telegram.org",
            "venus.web.telegram.org"
    };
    private static final String[] SHARED_EDGE_HOSTS_DC4 = {
            "vesta.web.telegram.org",
            "kws4.web.telegram.org",
            "zws4.web.telegram.org"
    };
    private static final String[] ALL_EDGE_HOSTS = {
            "kws2.web.telegram.org",
            "zws2.web.telegram.org",
            "venus.web.telegram.org",
            "vesta.web.telegram.org",
            "kws4.web.telegram.org",
            "zws4.web.telegram.org",
            "kws1.web.telegram.org",
            "zws1.web.telegram.org",
            "pluto.web.telegram.org",
            "kws3.web.telegram.org",
            "zws3.web.telegram.org",
            "aurora.web.telegram.org",
            "kws5.web.telegram.org",
            "zws5.web.telegram.org",
            "flora.web.telegram.org"
    };
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String[] WHITELIST_SPOOF_SNIS = {
            "max.ru",
            "vk.com",
            "gosuslugi.ru",
            "ya.ru",
            "ozon.ru",
            "sberbank.ru"
    };
    private static final String[] VERIFIED_WSS_IPS_DC1_DC3 = {
            "149.154.174.100",
            "149.154.174.200",
            "149.154.175.209",
            "2001:b28:f23d:8005:7:0:109:337",
            "2001:b28:f23d:8005:7:0:109:338"
    };
    private static final String[] VERIFIED_WSS_IPS_DC2_DC4 = {
            "149.154.167.220",
            "149.154.167.99",
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
    private static final int CONNECT_ATTEMPT_TIMEOUT_MS = 1100;
    private static final int CONNECT_RACE_BUDGET_MS = 1400;
    private static final int HANDSHAKE_READ_TIMEOUT_MS = 2200;
    private static final long MAX_DIAL_WALL_MS = 4600L;
    private static final int CELLULAR_CONNECT_ATTEMPT_TIMEOUT_MS = 2000;
    private static final int CELLULAR_CONNECT_RACE_BUDGET_MS = 2300;
    private static final int CELLULAR_HANDSHAKE_READ_TIMEOUT_MS = 3200;
    private static final long CELLULAR_MAX_DIAL_WALL_MS = 5400L;
    private static volatile boolean cellularNetwork = false;

    public static void setCellularNetwork(boolean cellular) {
        if (cellularNetwork != cellular) {
            cellularNetwork = cellular;
            preferredFragMode = defaultFragModeForNetwork(cellular);
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
    private static final int MAX_OUTBOUND_FRAME_BYTES = 65500;
    private static final int WS_IDLE_PING_TIMEOUT_MS = 25_000;
    private static final int WS_PONG_WAIT_TIMEOUT_MS = 10_000;

    private static final ExecutorService RACE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "colgram-ws-dial");
        t.setDaemon(true);
        return t;
    });

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

    private static boolean isNativeFragMode(int mode) {
        return (mode >= FRAG_NATIVE_DISORDER_TLSREC && mode <= FRAG_NATIVE_OOB1_ONLY_TLSREC)
                || mode == FRAG_NATIVE_MULTI_SPLIT_TLSREC;
    }

    private static boolean isSpoofSniFragMode(int mode) {
        return mode == FRAG_SPOOF_SNI_PLAIN
                || mode == FRAG_SPOOF_SNI_TLSREC
                || mode == FRAG_SPOOF_SNI_ALT;
    }

    private static int defaultFragModeForNetwork(boolean cellular) {
        // Spoofed RU-whitelist SNI + 2-record TLS split negotiates TLS 1.3 (cert encrypted!),
        // bypasses TSPU SNI DPI & L7 whitelists, and uses zero MSG_OOB / TTL=1 / tiny-MSS quirks.
        return FRAG_SPOOF_SNI_TLSREC;
    }

    private static final java.util.concurrent.atomic.AtomicReferenceArray<EdgeEndpoint> lastWorkingByDc =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(6);
    private static volatile int preferredFragMode = defaultFragModeForNetwork(false);
    private static volatile boolean workedOnNetwork = false;
    private static volatile long resolvedAt = 0L;
    private static final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private static final AtomicInteger spoofSniCursor = new AtomicInteger(0);
    private static final Map<String, List<String>> perHostIps = new ConcurrentHashMap<>();
    private static final Map<String, Long> deprioritizedUntil = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicIntegerArray consecutiveFailuresByDc =
            new java.util.concurrent.atomic.AtomicIntegerArray(6);
    private static final java.util.concurrent.atomic.AtomicLongArray cooldownUntilByDc =
            new java.util.concurrent.atomic.AtomicLongArray(6);
    private static final AtomicLongArray lastBurstFailAtByDc = new AtomicLongArray(6);
    private static final Semaphore[] dialSlotsByDc = createDcSemaphores();

    private static Semaphore[] createDcSemaphores() {
        Semaphore[] s = new Semaphore[6];
        for (int i = 0; i < s.length; i++) {
            s[i] = new Semaphore(2, true);
        }
        return s;
    }

    private static int dcBucket(int dcId) {
        return (dcId >= 1 && dcId <= 5) ? dcId : 0;
    }

    private ColgramTelegramWsBridge() {}

    public static final class WsSession implements Closeable {
        public final int dcId;
        public final String edgeIp;
        public final String sniHost;
        public final Socket socket;
        public final InputStream in;
        public final OutputStream out;
        private final Object writeLock = new Object();
        private final SecureRandom rng = new SecureRandom();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        WsSession(int dcId, String edgeIp, String sniHost, Socket socket,
                  InputStream in, OutputStream out) {
            this.dcId = dcId;
            this.edgeIp = edgeIp;
            this.sniHost = sniHost;
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        public void sendBinary(byte[] buf, int off, int len) throws IOException {
            if (len <= 0) return;
            synchronized (writeLock) {
                int pos = off;
                int remaining = len;
                boolean first = true;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, MAX_OUTBOUND_FRAME_BYTES);
                    boolean last = (chunk == remaining);
                    int headerByte = (last ? 0x80 : 0x00) | (first ? 0x02 : 0x00);
                    writeFrame(headerByte, buf, pos, chunk);
                    first = false;
                    pos += chunk;
                    remaining -= chunk;
                }
            }
        }

        public void sendPing() throws IOException {
            writeFrame(0x89, new byte[0], 0, 0);
        }

        public void sendPong(byte[] payload, int len) throws IOException {
            writeFrame(0x8A, payload, 0, Math.min(len, 125));
        }

        public void sendClose() {
            try {
                writeFrame(0x88, new byte[0], 0, 0);
            } catch (Throwable ignored) {
            }
        }

        private void writeFrame(int firstByte, byte[] data, int off, int len) throws IOException {
            if (closed.get()) throw new IOException("WebSocket closed");
            byte[] mask = new byte[4];
            rng.nextBytes(mask);
            int headerLen;
            if (len < 126) {
                headerLen = 2 + 4;
            } else if (len <= 65535) {
                headerLen = 4 + 4;
            } else {
                headerLen = 10 + 4;
            }
            byte[] frame = new byte[headerLen + len];
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
            System.arraycopy(mask, 0, frame, p, 4);
            p += 4;
            for (int i = 0; i < len; i++) {
                frame[p + i] = (byte) (data[off + i] ^ mask[i & 3]);
            }
            synchronized (writeLock) {
                out.write(frame);
                out.flush();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean supports(String host, int port) {
        if (port != 443 && port != 80 && port != 5222) return false;
        return ColgramTelegramDcAddresses.dcIdFor(host) > 0;
    }

    public static boolean hasWorkedOnCurrentNetwork() {
        return workedOnNetwork;
    }

    public static void resetNetworkState() {
        for (int i = 0; i < lastWorkingByDc.length(); i++) {
            lastWorkingByDc.set(i, null);
        }
        preferredFragMode = defaultFragModeForNetwork(cellularNetwork);
        workedOnNetwork = false;
        resolvedAt = 0L;
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
            lastBurstFailAtByDc.set(i, 0L);
        }
    }

    public static void deprioritizeEndpoint(WsSession session) {
        if (session == null || session.edgeIp == null || session.sniHost == null) return;
        EdgeEndpoint ep = new EdgeEndpoint(session.edgeIp, session.sniHost);
        deprioritizedUntil.put(ep.key(), System.currentTimeMillis() + DEPRIORITIZE_TTL_MS);
        int bucket = dcBucket(session.dcId);
        if (ep.equals(lastWorkingByDc.get(bucket))) {
            lastWorkingByDc.compareAndSet(bucket, ep, null);
        }
    }

    /** Pre-resolve Telegram WebSocket edge hosts via DoH off-thread so the first dial has backups ready. */
    public static void warmUp() {
        long now = System.currentTimeMillis();
        if (now - resolvedAt < DNS_CACHE_TTL_MS && !perHostIps.isEmpty()) return;
        if (!refreshInFlight.compareAndSet(false, true)) return;
        RACE_POOL.execute(ColgramTelegramWsBridge::refreshResolvedIps);
    }

    public static WsSession dial(String destHost, int destPort) {
        return dialProtected(destHost, destPort, null, null);
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
        if (destPort != 443 && destPort != 80 && destPort != 5222) return null;
        int dcId = ColgramTelegramDcAddresses.dcIdFor(destHost);
        if (dcId <= 0) return null;

        int bucket = dcBucket(dcId);
        long now = System.currentTimeMillis();
        if (now < cooldownUntilByDc.get(bucket)) return null;

        // Coalesce concurrent tgnet dial storms for the same DC so 5+ simultaneous cold-start
        // sockets don't burn parallel threads or trip DC cooldown on one transient wave.
        Semaphore dcSlot = dialSlotsByDc[bucket];
        boolean acquired = false;
        try {
            acquired = dcSlot.tryAcquire(Math.min(2200L, maxDialWallMs() / 2), TimeUnit.MILLISECONDS);
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

            boolean canNative = ColgramNativeDesync.isAvailable() && (protector == null || fdProtector != null);

            // Cold-start Wave-1 parallel race on BOTH cellular and Wi-Fi:
            // Race carrier-safe spoofed RU-whitelist SNI (TLS 1.3, split + plain) alongside
            // real-SNI Java TLS-record-split and native MODE_MULTI_SPLIT_SNI (zero MSG_OOB,
            // zero TTL=1, zero tiny MSS).
            if (!workedOnNetwork) {
                List<EdgeEndpoint> coldCandidates = candidateEndpointsFor(dcId);
                if (!coldCandidates.isEmpty()) {
                    int[] coldModes = canNative
                            ? new int[]{
                                    FRAG_SPOOF_SNI_TLSREC,
                                    FRAG_SPOOF_SNI_PLAIN,
                                    FRAG_TLS_RECORD_SNI,
                                    FRAG_NATIVE_MULTI_SPLIT_TLSREC
                            }
                            : new int[]{
                                    FRAG_SPOOF_SNI_TLSREC,
                                    FRAG_SPOOF_SNI_PLAIN,
                                    FRAG_TLS_RECORD_SNI
                            };
                    long coldBudget = Math.min(
                            cellularNetwork ? 3600L : 2800L,
                            Math.max(1400L, deadlineMs - System.currentTimeMillis()));
                    WsSession coldWinner = raceHandshakeModes(
                            coldCandidates, dcId, coldModes, protector, fdProtector, coldBudget);
                    if (coldWinner != null) {
                        return coldWinner;
                    }
                }
            }

            Set<String> triedIps = new LinkedHashSet<>();
            int maxAltPerEndpoint = 2;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (System.currentTimeMillis() >= deadlineMs) break;
                List<EdgeEndpoint> candidates = filterUnvisitedIps(candidateEndpointsFor(dcId), triedIps);
                if (candidates.isEmpty()) break;

                int primaryMode = preferredFragMode;
                if (!canNative && isNativeFragMode(primaryMode)) {
                    primaryMode = FRAG_SPOOF_SNI_TLSREC;
                }
                DialedTcp tcp = connectEdgeRace(candidates, protector, fdProtector, primaryMode);
                if (tcp == null) break;
                triedIps.add(tcp.endpoint.ip);

                HandshakeResult res = tryHandshake(tcp, dcId, primaryMode);
                if (res.session != null) {
                    preferredFragMode = primaryMode;
                    return res.session;
                }

                if (!res.hostnameMismatch && System.currentTimeMillis() + 800L < deadlineMs) {
                    int[] fallbacks = fallbackFragModes(primaryMode, canNative);
                    int altTried = 0;
                    for (int altMode : fallbacks) {
                        if (altTried >= maxAltPerEndpoint
                                || System.currentTimeMillis() + 800L >= deadlineMs) break;
                        altTried++;
                        DialedTcp altTcp = connectEdgeRace(
                                Collections.singletonList(tcp.endpoint), protector, fdProtector, altMode);
                        if (altTcp == null) break;
                        HandshakeResult altRes = tryHandshake(altTcp, dcId, altMode);
                        if (altRes.session != null) {
                            preferredFragMode = altMode;
                            return altRes.session;
                        }
                        if (altRes.hostnameMismatch) break;
                    }
                }
                deprioritizedUntil.put(tcp.endpoint.key(), System.currentTimeMillis() + DEPRIORITIZE_TTL_MS);
            }
            recordBurstFailure(dcId);
            return null;
        } finally {
            dcSlot.release();
        }
    }

    private static List<EdgeEndpoint> filterUnvisitedIps(List<EdgeEndpoint> all, Set<String> triedIps) {
        if (all.isEmpty() || triedIps.isEmpty()) return all;
        List<EdgeEndpoint> fresh = new ArrayList<>(all.size());
        for (EdgeEndpoint ep : all) {
            if (!triedIps.contains(ep.ip)) fresh.add(ep);
        }
        return fresh.isEmpty() ? all : fresh;
    }

    private static void recordBurstFailure(int dcId) {
        int bucket = dcBucket(dcId);
        long now = System.currentTimeMillis();
        long prev = lastBurstFailAtByDc.get(bucket);
        if (now - prev >= BURST_FAILURE_WINDOW_MS
                && lastBurstFailAtByDc.compareAndSet(bucket, prev, now)) {
            recordFailure(dcId);
        }
    }

    private static WsSession raceHandshakeModes(final List<EdgeEndpoint> candidates, final int dcId,
                                                final int[] modes,
                                                final ColgramDohResolver.SocketProtector protector,
                                                final ColgramNativeDesync.FdProtector fdProtector,
                                                final long budgetMs) {
        final Object winnerLock = new Object();
        final AtomicReference<WsSession> winner = new AtomicReference<>();
        final boolean[] timedOut = new boolean[]{false};
        final AtomicBoolean done = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger left = new AtomicInteger(modes.length);
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            final long delay = i * 60L;
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
                    HandshakeResult res = tryHandshake(tcp, dcId, mode);
                    if (res.session != null) {
                        synchronized (winnerLock) {
                            if (!timedOut[0] && winner.get() == null) {
                                winner.set(res.session);
                                done.set(true);
                                preferredFragMode = mode;
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

        HandshakeResult(WsSession session, boolean wrapperRejected, boolean hostnameMismatch) {
            this.session = session;
            this.wrapperRejected = wrapperRejected;
            this.hostnameMismatch = hostnameMismatch;
        }
    }

    private static Socket wrapSocketForMode(Socket base, String sniHost, int fragMode) {
        if (base instanceof ColgramNativeDesync.NativeSocket) {
            ColgramNativeDesync.NativeSocket ns = (ColgramNativeDesync.NativeSocket) base;
            boolean cell = cellularNetwork;
            switch (fragMode) {
                case FRAG_NATIVE_MULTI_SPLIT_TLSREC:
                    return ColgramNativeDesync.wrapForFirstTlsHello(
                            ns, ColgramNativeDesync.MODE_MULTI_SPLIT_SNI, 0, true, true, cell, 0);
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
                            ns, ColgramNativeDesync.MODE_FAKE_TTL, 5, true, false, cell, 3);
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
            return WHITELIST_SPOOF_SNIS[(baseIdx + 1) % WHITELIST_SPOOF_SNIS.length];
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

    private static HandshakeResult tryHandshake(DialedTcp tcp, int dcId, int fragMode) {
        boolean wrapperRejected = false;
        boolean hostnameMismatch = false;
        String ip = tcp.endpoint.ip;
        String realHost = tcp.endpoint.sniHost;
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
                ssl = (SSLSocket) sf.createSocket(underlying, tlsSni, 443, true);
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
            OutputStream out = ssl.getOutputStream();
            InputStream in = ssl.getInputStream();
            if (!upgradeWebSocket(in, out, realHost)) {
                throw new IOException("WebSocket upgrade rejected by " + ip + " (" + realHost + ")");
            }
            tuneConnectedSocket(tcp.socket);
            tuneConnectedSocket(ssl);
            ssl.setSoTimeout(0);
            int bucket = dcBucket(dcId);
            lastWorkingByDc.set(bucket, tcp.endpoint);
            deprioritizedUntil.remove(tcp.endpoint.key());
            workedOnNetwork = true;
            ColgramDpiBypass.onDirectLayer1Success();
            consecutiveFailuresByDc.set(bucket, 0);
            cooldownUntilByDc.set(bucket, 0L);
            Log.i(TAG, "WSS upgraded ok: " + tcp.endpoint + " tlsSni=" + tlsSni
                    + " fragMode=" + fragMode + " dc=" + dcId);
            return new HandshakeResult(new WsSession(dcId, ip, realHost, ssl, in, out), false, false);
        } catch (Throwable t) {
            if (isSpoofSniFragMode(fragMode)) {
                spoofSniCursor.incrementAndGet();
            }
            Log.d(TAG, "WSS handshake failed on " + ip + " (host=" + realHost + ", tlsSni=" + tlsSni
                    + ", fragMode=" + fragMode + "): " + t.getMessage());
            try {
                tcp.socket.close();
            } catch (Throwable ignored) {
            }
            return new HandshakeResult(null, wrapperRejected, hostnameMismatch);
        }
    }

    /** Primary official Telegram WebSocket hostname for DC 1..5 (used for ATYP=0x03 L3 relay tunnels). */
    public static String primaryWssHostForDc(int dcId) {
        String[] hosts = dcHosts(dcId);
        return (hosts != null && hosts.length > 0) ? hosts[0] : "kws2.web.telegram.org";
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
        if (relayTcpSocket == null || dcId < 1 || dcId > 5) return null;
        String host = (realHost != null && !realHost.isEmpty()) ? realHost : primaryWssHostForDc(dcId);
        String label = (relayLabel != null && !relayLabel.isEmpty()) ? relayLabel : host;
        String tlsSni = pickSpoofSni(FRAG_SPOOF_SNI_TLSREC);
        try {
            relayTcpSocket.setTcpNoDelay(true);
            SSLSocketFactory sf = ColgramNativeDesync.createEngineSslSocketFactory();
            Socket underlying = new FragmentedHelloSocket(relayTcpSocket, tlsSni, FRAG_TCP_2BYTE_SNI);
            SSLSocket ssl = (SSLSocket) sf.createSocket(underlying, tlsSni, 443, true);
            try {
                SSLParameters params = ssl.getSSLParameters();
                if (tlsSni != null && !ColgramDohResolver.isIpLiteral(tlsSni)) {
                    params.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(tlsSni)));
                }
                ssl.setSSLParameters(params);
            } catch (Throwable ignored) {}
            ssl.setTcpNoDelay(true);
            ssl.setSoTimeout(Math.max(3500, handshakeReadTimeoutMs()));
            ssl.startHandshake();
            if (!verifyOfficialTelegramSession(ssl.getSession(), host)) {
                throw new IOException("TLS cert not official Telegram edge over relay for " + host);
            }
            OutputStream out = ssl.getOutputStream();
            InputStream in = ssl.getInputStream();
            if (!upgradeWebSocket(in, out, host)) {
                throw new IOException("WebSocket upgrade rejected over relay " + label + " (" + host + ")");
            }
            tuneConnectedSocket(relayTcpSocket);
            tuneConnectedSocket(ssl);
            ssl.setSoTimeout(0);
            try {
                Log.i(TAG, "WSS-over-L3-relay upgraded ok: relay=" + label + " host=" + host
                        + " tlsSni=" + tlsSni + " dc=" + dcId);
            } catch (Throwable ignored) {}
            return new WsSession(dcId, label, host, ssl, in, out);
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
        private final byte[] hdrPlain = new byte[4];
        private int hdrFilled = 0;
        private int payloadRemaining = -1;
        private boolean fallbackPassthrough = false;
        private ByteArrayOutputStream packetBuf = new ByteArrayOutputStream(2048);

        private Obfuscated2FrameSplitter(int proto, Cipher shadowCtr) {
            this.proto = proto;
            this.shadowCtr = shadowCtr;
        }

        public static Obfuscated2FrameSplitter create(byte[] initBuf, int initLen, int dcId) {
            return initAndPatch(initBuf, initLen, dcId);
        }

        private static Obfuscated2FrameSplitter initAndPatch(byte[] data, int length, int dcId) {
            if (data == null || length < 64 || dcId <= 0) return null;
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
                data[60] = (byte) ((dcId & 0xff) ^ (ks[60] & 0xff));
                data[61] = (byte) (((dcId >> 8) & 0xff) ^ (ks[61] & 0xff));
                return new Obfuscated2FrameSplitter(p0, ctr);
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

        public synchronized void feed(byte[] data, int off, int len, FrameConsumer sink)
                throws IOException {
            if (len <= 0) return;
            if (fallbackPassthrough) {
                flushPartialAndRaw(data, off, len, sink);
                return;
            }
            byte[] plain;
            try {
                plain = shadowCtr.update(data, off, len);
            } catch (Throwable err) {
                fallbackPassthrough = true;
                flushPartialAndRaw(data, off, len, sink);
                return;
            }
            if (plain == null || plain.length != len) {
                fallbackPassthrough = true;
                flushPartialAndRaw(data, off, len, sink);
                return;
            }

            int pos = 0;
            while (pos < len) {
                if (payloadRemaining < 0) {
                    byte cByte = data[off + pos];
                    byte pByte = plain[pos];
                    pos++;
                    packetBuf.write(cByte);
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
                } else {
                    int take = Math.min(payloadRemaining, len - pos);
                    packetBuf.write(data, off + pos, take);
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
            byte[] pkt = packetBuf.toByteArray();
            if (pkt.length > RESET_BUFFER_THRESHOLD) {
                packetBuf = new ByteArrayOutputStream(2048);
            } else {
                packetBuf.reset();
            }
            sink.onFrame(pkt, 0, pkt.length);
        }

        private void flushPartialAndRaw(byte[] data, int off, int len, FrameConsumer sink)
                throws IOException {
            if (packetBuf.size() > 0) {
                byte[] pending = packetBuf.toByteArray();
                packetBuf = new ByteArrayOutputStream(2048);
                sink.onFrame(pending, 0, pending.length);
            }
            if (len > 0) {
                sink.onFrame(data, off, len);
            }
        }
    }

    /**
     * Validate un-secreted obfuscated2 from `tgnet` (`useSecret == 0`) and inject `dc_id` into
     * bytes `[60..61]` without disturbing the AES-CTR keystream from byte `64` onward.
     */
    public static boolean patchObfuscated2Header(byte[] data, int length, int dcId) {
        return Obfuscated2FrameSplitter.initAndPatch(data, length, dcId) != null;
    }

    private static void tuneConnectedSocket(Socket s) {
        if (s == null) return;
        try { s.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { s.setKeepAlive(true); } catch (Throwable ignored) {}
        try { s.setReceiveBufferSize(262144); } catch (Throwable ignored) {}
        try { s.setSendBufferSize(262144); } catch (Throwable ignored) {}
    }

    public static long pumpUpstreamFrames(WsSession session, OutputStream clientOut,
                                          OnBytes onReply) throws IOException {
        long total = 0L;
        byte[] buf = new byte[65536];
        InputStream in = session.in;
        boolean awaitingPong = false;
        if (session.socket != null) {
            try { session.socket.setSoTimeout(WS_IDLE_PING_TIMEOUT_MS); } catch (Throwable ignored) {}
        }
        while (true) {
            int b0;
            try {
                b0 = in.read();
            } catch (java.net.SocketTimeoutException idle) {
                if (awaitingPong) {
                    throw new IOException("WS keepalive pong timeout on " + session.edgeIp, idle);
                }
                awaitingPong = true;
                session.sendPing();
                if (session.socket != null) {
                    try { session.socket.setSoTimeout(WS_PONG_WAIT_TIMEOUT_MS); } catch (Throwable ignored) {}
                }
                continue;
            }
            if (b0 < 0) break;
            if (awaitingPong) {
                awaitingPong = false;
                if (session.socket != null) {
                    try { session.socket.setSoTimeout(WS_IDLE_PING_TIMEOUT_MS); } catch (Throwable ignored) {}
                }
            }
            int b1 = in.read();
            if (b1 < 0) break;
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
                mask = new byte[4];
                readFully(in, mask, 4);
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
                readFully(in, ctrl, ctrlLen);
                if (mask != null) unmask(ctrl, 0, ctrlLen, mask, 0);
                if (payloadLen > ctrlLen) drainPayload(in, payloadLen - ctrlLen);
                session.sendPong(ctrl, ctrlLen);
                continue;
            }
            if (opcode == 0xA) {
                drainPayload(in, payloadLen);
                continue;
            }
            if (opcode != 0x0 && opcode != 0x1 && opcode != 0x2) {
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
            if (in.available() <= 0) {
                clientOut.flush();
            }
        }
        clientOut.flush();
        return total;
    }

    public interface OnBytes {
        void onBytes(int count);
    }

    private static String[] dcHosts(int dcId) {
        switch (dcId) {
            case 1: return new String[]{"kws1.web.telegram.org", "zws1.web.telegram.org", "pluto.web.telegram.org"};
            case 2: return new String[]{"kws2.web.telegram.org", "zws2.web.telegram.org", "venus.web.telegram.org"};
            case 3: return new String[]{"kws3.web.telegram.org", "zws3.web.telegram.org", "aurora.web.telegram.org"};
            case 4: return new String[]{"vesta.web.telegram.org", "kws4.web.telegram.org", "zws4.web.telegram.org"};
            case 5: return new String[]{"kws5.web.telegram.org", "zws5.web.telegram.org", "flora.web.telegram.org"};
            default: return new String[]{"kws2.web.telegram.org", "vesta.web.telegram.org"};
        }
    }

    private static String[] sharedEdgeHostsForDc(int dcId) {
        if (dcId == 2) return SHARED_EDGE_HOSTS_DC2;
        if (dcId == 4) return SHARED_EDGE_HOSTS_DC4;
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
                                    try { Thread.sleep(3L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                if (headCut < r0.length) {
                                    raw.write(r0, headCut, r0.length - headCut);
                                    raw.flush();
                                }
                                try { Thread.sleep(4L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                for (int r = 1; r < recs.length; r++) {
                                    raw.write(recs[r]);
                                    raw.flush();
                                    if (r + 1 < recs.length) {
                                        try { Thread.sleep(3L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                    }
                                }
                                return;
                            }
                            int[] sniSpan = ColgramTlsMimic.findTlsSniSpan(copy, copy.length);
                            int segEnd = sniSpan != null ? Math.min(copy.length, sniSpan[1]) : Math.min(copy.length, 64);
                            int segStart = sniSpan != null ? Math.max(0, sniSpan[0] - 2) : 0;
                            if (segStart > 0) {
                                raw.write(copy, 0, segStart);
                                raw.flush();
                                try { Thread.sleep(3L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            }
                            for (int pos = segStart; pos < segEnd; pos += 2) {
                                raw.write(copy, pos, Math.min(2, segEnd - pos));
                                raw.flush();
                                try { Thread.sleep(3L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
                            if (i + 1 < pieces.length) {
                                try {
                                    Thread.sleep(2L);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
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
        int bucket = dcBucket(dcId);
        if (consecutiveFailuresByDc.incrementAndGet(bucket) >= 5) {
            cooldownUntilByDc.set(bucket, System.currentTimeMillis() + FAILURE_COOLDOWN_MS);
            consecutiveFailuresByDc.set(bucket, 0);
        }
    }

    static boolean isInCooldown(int dcId) {
        return System.currentTimeMillis() < cooldownUntilByDc.get(dcBucket(dcId));
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
        long now = System.currentTimeMillis();
        if (now - resolvedAt > DNS_CACHE_TTL_MS && refreshInFlight.compareAndSet(false, true)) {
            RACE_POOL.execute(ColgramTelegramWsBridge::refreshResolvedIps);
        }
        int bucket = dcBucket(dcId);
        return buildCandidateEndpoints(dcId, lastWorkingByDc.get(bucket), perHostIps, activeDeprioritizedKeys(now));
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
        LinkedHashMap<String, EdgeEndpoint> ordered = new LinkedHashMap<>();
        Set<String> skipped = deprioritizedKeys == null ? Collections.emptySet() : deprioritizedKeys;

        if (lastWorking != null
                && !skipped.contains(lastWorking.key())
                && isDcHostMatch(lastWorking.sniHost, dcId)
                && !(DEFAULT_EDGE_IP.equals(lastWorking.ip) && !isSharedEdgeSniForDc(lastWorking.sniHost, dcId))) {
            ordered.put(lastWorking.key(), lastWorking);
        }

        // For DC2/DC4, ALWAYS rank proven 149.154.167.220 ahead of DoH-resolved IPs (e.g. .99).
        for (String host : sharedEdgeHostsForDc(dcId)) {
            EdgeEndpoint primaryShared = new EdgeEndpoint(DEFAULT_EDGE_IP, host);
            if (!skipped.contains(primaryShared.key())) ordered.putIfAbsent(primaryShared.key(), primaryShared);
        }

        // Next include fresh DoH-resolved DC-specific IPs (never pairing .220 with non-DC2/4 hosts).
        if (hostIps != null) {
            for (String host : dcHosts(dcId)) {
                List<String> ips = hostIps.get(host);
                if (ips == null) continue;
                for (String ip : ips) {
                    if (ip == null || ip.isEmpty()) continue;
                    if (DEFAULT_EDGE_IP.equals(ip) && !isSharedEdgeSniForDc(host, dcId)) continue;
                    EdgeEndpoint ep = new EdgeEndpoint(ip, host);
                    if (!skipped.contains(ep.key())) ordered.putIfAbsent(ep.key(), ep);
                }
            }
        }

        // Live-verified official Telegram WebSocket edge IPv4 + IPv6 addresses per DC
        // (never raw non-TLS MTProto DC IPs).
        String[] dcSnis = dcHosts(dcId);
        for (String secIp : secondaryEdgeIpsForDc(dcId)) {
            if (secIp == null) continue;
            for (String host : dcSnis) {
                if (DEFAULT_EDGE_IP.equals(secIp) && !isSharedEdgeSniForDc(host, dcId)) continue;
                EdgeEndpoint sec = new EdgeEndpoint(secIp, host);
                if (!skipped.contains(sec.key())) ordered.putIfAbsent(sec.key(), sec);
            }
        }

        if (ordered.isEmpty()) {
            // Every candidate was temporarily deprioritized; re-seed strictly within dcId's own
            // verified WSS edges so DC1/DC3/DC5 never cross-wire onto DC2's kws2 endpoint.
            for (String secIp : secondaryEdgeIpsForDc(dcId)) {
                if (secIp == null) continue;
                for (String host : dcSnis) {
                    if (DEFAULT_EDGE_IP.equals(secIp) && !isSharedEdgeSniForDc(host, dcId)) continue;
                    EdgeEndpoint ep = new EdgeEndpoint(secIp, host);
                    ordered.putIfAbsent(ep.key(), ep);
                }
            }
            if (ordered.isEmpty()) {
                ordered.put(DEFAULT_ENDPOINT.key(), DEFAULT_ENDPOINT);
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
                                             final int fragMode) {
        if (candidates.isEmpty()) return null;
        boolean includeIpv6 = ColgramDohResolver.hasGlobalRoutableIpv6() || isIpv6BypassEnabledSafe();
        List<EdgeEndpoint> picked = selectRaceEndpoints(candidates, includeIpv6);
        if (picked.isEmpty()) return null;
        EdgeEndpoint ep0 = picked.get(0);
        EdgeEndpoint ep1 = picked.size() > 1 ? picked.get(1) : ep0;
        EdgeEndpoint ep2 = picked.size() > 2 ? picked.get(2) : ep0;
        EdgeEndpoint ep3 = picked.size() > 3 ? picked.get(3) : ep1;
        EdgeEndpoint[] plan = {ep0, ep1, ep2, ep3};
        long[] delaysMs = {
                0L,
                ep0.ip.equals(ep1.ip) ? 110L : 40L,
                140L,
                220L
        };

        final boolean useNative = isNativeFragMode(fragMode) && ColgramNativeDesync.isAvailable()
                && (protector == null || fdProtector != null);
        // Never pass invalid TCP_MAXSEG < 88 (Linux TCP_MIN_MSS=88 -> EINVAL).
        final int mssClamp = (fragMode == FRAG_NATIVE_MSS_CLAMP_TLSREC
                || fragMode == FRAG_NATIVE_MSS64_OOB1_TLSREC) ? 88 : 0;
        // Avoid pre-SYN TCP_WINDOW_CLAMP on primary modes (caps TCP Window Scale permanently).
        final int windowClamp = (useNative && cellularNetwork
                && (fragMode == FRAG_NATIVE_OOB_DISORDER_TLSREC || fragMode == FRAG_NATIVE_MSS_CLAMP_TLSREC))
                ? 2048 : 0;

        final Object winnerLock = new Object();
        final AtomicReference<DialedTcp> winner = new AtomicReference<>();
        final boolean[] timedOut = new boolean[]{false};
        final AtomicBoolean done = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger pending = new AtomicInteger(plan.length);

        for (int i = 0; i < plan.length; i++) {
            final EdgeEndpoint ep = plan[i];
            final long delay = delaysMs[i];
            RACE_POOL.execute(() -> {
                Socket sock = null;
                try {
                    if (delay > 0L) Thread.sleep(delay);
                    if (done.get()) return;
                    if (useNative) {
                        sock = ColgramNativeDesync.connect(
                                ep.ip, 443, connectAttemptTimeoutMs(), mssClamp, windowClamp, fdProtector);
                    } else {
                        sock = new Socket();
                        sock.setTcpNoDelay(true);
                        if (protector != null && !protector.protect(sock)) {
                            return;
                        }
                        sock.connect(new InetSocketAddress(ep.ip, 443), connectAttemptTimeoutMs());
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

    private static boolean upgradeWebSocket(InputStream in, OutputStream out, String host)
            throws Exception {
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String wsKey = encodeBase64(keyBytes);
        String expectedAccept = encodeBase64(MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_GUID).getBytes(StandardCharsets.US_ASCII)));

        String req = "GET /apiws HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: binary\r\n"
                + "Origin: https://web.telegram.org\r\n"
                + "User-Agent: Mozilla/5.0\r\n\r\n";
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
        for (int i = 0; i < len; i++) {
            buf[off + i] ^= mask[(maskOffset + i) & 3];
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
