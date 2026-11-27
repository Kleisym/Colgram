package org.colgram.core;

import android.net.VpnService;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local loopback UDP DNS forwarder for whole-device DPI bypass TUN.
 *
 * Replaces sing-box's single-provider `final: "sys-dns-adguard"` (which suffers total DNS blackout
 * on any mobile carrier that blocks/throttles 94.140.14.14) with a resilient multi-provider
 * protected DoH racer (AdGuard, Google, Quad9, Cloudflare, Mullvad, ControlD, DNS.SB over native
 * ByeDPI desynced sockets + protected UDP/53 last-resort fallback) backed by a fast TTL cache,
 * stale-while-revalidate, singleflight query coalescing, and fast domestic UDP/53 bypass.
 */
final class ColgramLocalDnsProxy implements Closeable {

    private static final String TAG = "ColgramLocalDns";
    private static final int MAX_PACKET = 4096;
    private static final int MAX_CACHE = 4096;
    private static final long CACHE_TTL_MS = 300_000L;
    private static final long GOOGLEVIDEO_CACHE_TTL_MS = 120_000L;
    private static final long STALE_GRACE_MS = 300_000L;
    private static final long GOOGLEVIDEO_STALE_GRACE_MS = 30_000L;
    private static final long COALESCE_WAIT_MS = 5500L;
    private static final String[] PREWARM_DOMAINS = {
            "www.youtube.com",
            "youtubei.googleapis.com",
            "i.ytimg.com",
            "discord.com",
            "gateway.discord.gg",
            "instagram.com"
    };

    interface WireForwarder {
        byte[] forward(byte[] wireQuery);
    }

    private static final class CacheEntry {
        final byte[] reply;
        final long expiresAt;
        final long staleUntil;

        CacheEntry(byte[] reply, long expiresAt, long staleUntil) {
            this.reply = reply;
            this.expiresAt = expiresAt;
            this.staleUntil = staleUntil;
        }
    }

    private final DatagramSocket socket;
    private final boolean ipv4Only;
    private final WireForwarder customForwarder;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inflightByKey =
            new ConcurrentHashMap<>();
    private final Semaphore inflight = new Semaphore(64);
    private final ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(
            0, 68, 30L, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "colgram-local-dns");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, ex) -> Log.w(TAG, "local dns worker err: " + ex));
                return t;
            });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile VpnService vpn;

    ColgramLocalDnsProxy(boolean ipv4Only) throws IOException {
        this(ipv4Only, null);
    }

    ColgramLocalDnsProxy(boolean ipv4Only, WireForwarder customForwarder) throws IOException {
        this.ipv4Only = ipv4Only;
        this.customForwarder = customForwarder;
        this.socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        workers.execute(this::serveLoop);
    }

    int port() {
        return socket.getLocalPort();
    }

    void attach(VpnService service) {
        this.vpn = service;
        if (customForwarder == null && service != null && !closed.get()) {
            try {
                workers.execute(this::prewarmHotDomains);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {}
        }
    }

    private void prewarmHotDomains() {
        for (String host : PREWARM_DOMAINS) {
            if (closed.get() || vpn == null) return;
            String key = host + "|1";
            CacheEntry existing = cache.get(key);
            long now = System.currentTimeMillis();
            if (existing != null && now < existing.expiresAt) continue;
            byte[] q = buildSimpleAQuery(host);
            if (q != null) {
                resolveCoalesced(key, new Question(host, 1), q);
            }
        }
    }

    private static byte[] buildSimpleAQuery(String hostname) {
        if (hostname == null || hostname.isEmpty()) return null;
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream(64);
        int id = (int) (System.nanoTime() & 0xFFFF);
        body.write(id >> 8);
        body.write(id & 0xFF);
        body.write(0x01);
        body.write(0x00);
        body.write(0x00);
        body.write(0x01);
        body.write(0x00);
        body.write(0x00);
        body.write(0x00);
        body.write(0x00);
        body.write(0x00);
        body.write(0x00);
        for (String label : hostname.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 63) return null;
            body.write(bytes.length);
            body.write(bytes, 0, bytes.length);
        }
        body.write(0x00);
        body.write(0x00);
        body.write(0x01);
        body.write(0x00);
        body.write(0x01);
        return body.toByteArray();
    }

    private void serveLoop() {
        byte[] buf = new byte[MAX_PACKET];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (!closed.get() && !socket.isClosed()) {
            try {
                pkt.setLength(buf.length);
                socket.receive(pkt);
                final int len = pkt.getLength();
                if (len < 12) continue;
                final byte[] query = Arrays.copyOf(buf, len);
                final InetAddress clientAddr = pkt.getAddress();
                final int clientPort = pkt.getPort();
                if (!inflight.tryAcquire()) continue;
                try {
                    workers.execute(() -> {
                        try {
                            handleQuery(query, clientAddr, clientPort);
                        } catch (Throwable ignored) {
                        } finally {
                            inflight.release();
                        }
                    });
                } catch (Throwable rejected) {
                    inflight.release();
                }
            } catch (IOException ignored) {
                if (closed.get() || socket.isClosed()) break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleQuery(byte[] query, InetAddress clientAddr, int clientPort) {
        Question q = parseQuestion(query);
        if (q != null && (q.qtype == 65 || q.qtype == 64 || (ipv4Only && q.qtype == 28))) {
            sendReply(buildEmptyNoErrorReply(query), clientAddr, clientPort);
            return;
        }
        String key = q != null ? (q.name + "|" + q.qtype) : null;
        long now = System.currentTimeMillis();
        if (key != null) {
            CacheEntry hit = cache.get(key);
            if (hit != null && hit.reply.length >= 12) {
                if (now < hit.expiresAt) {
                    byte[] patched = hit.reply.clone();
                    patched[0] = query[0];
                    patched[1] = query[1];
                    sendReply(patched, clientAddr, clientPort);
                    return;
                }
                if (now < hit.staleUntil) {
                    byte[] patched = hit.reply.clone();
                    patched[0] = query[0];
                    patched[1] = query[1];
                    sendReply(patched, clientAddr, clientPort);
                    triggerBackgroundRefresh(key, q, query.clone());
                    return;
                }
            }
        }

        byte[] reply = resolveCoalesced(key, q, query);
        if (reply == null || reply.length < 12) {
            sendReply(buildServFailReply(query), clientAddr, clientPort);
            return;
        }
        byte[] out = reply.clone();
        out[0] = query[0];
        out[1] = query[1];
        sendReply(out, clientAddr, clientPort);
    }

    private void triggerBackgroundRefresh(final String key, final Question q, final byte[] queryCopy) {
        if (key == null || closed.get() || inflightByKey.containsKey(key)) return;
        try {
            workers.execute(() -> {
                try {
                    if (!closed.get()) {
                        resolveCoalesced(key, q, queryCopy);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private byte[] resolveCoalesced(String key, Question q, byte[] query) {
        if (key == null) {
            return lookupUpstream(q, query);
        }
        CompletableFuture<byte[]> created = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inflightByKey.putIfAbsent(key, created);
        if (existing != null) {
            try {
                return existing.get(COALESCE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                return null;
            }
        }
        try {
            byte[] result = lookupUpstream(q, query);
            if (result != null && result.length >= 12 && (result[3] & 0x0F) == 0) {
                cacheReply(key, q, result);
            }
            created.complete(result);
            return result;
        } catch (Throwable t) {
            created.complete(null);
            return null;
        } finally {
            inflightByKey.remove(key, created);
        }
    }

    private byte[] lookupUpstream(Question q, byte[] query) {
        if (customForwarder != null) {
            return customForwarder.forward(query);
        }
        VpnService owner = vpn;
        if (owner == null || closed.get()) return null;
        if (q != null && ColgramDpiBypass.isDomesticDirectHost(q.name)) {
            byte[] fastUdp = ColgramDohResolver.queryFastDomesticUdp(query, owner::protect);
            if (fastUdp != null && fastUdp.length >= 12) {
                return fastUdp;
            }
        }
        return ColgramDohResolver.queryRawWireProtected(
                query, owner::protect, owner::protect, owner::protect);
    }

    private void cacheReply(String key, Question q, byte[] reply) {
        long putNow = System.currentTimeMillis();
        if (cache.size() >= MAX_CACHE) {
            cache.entrySet().removeIf(entry -> putNow >= entry.getValue().staleUntil);
            if (cache.size() >= MAX_CACHE) {
                cache.clear();
            }
        }
        boolean gv = q != null && isGoogleVideoHost(q.name);
        long ttlMs = gv ? GOOGLEVIDEO_CACHE_TTL_MS : CACHE_TTL_MS;
        long staleMs = gv ? GOOGLEVIDEO_STALE_GRACE_MS : STALE_GRACE_MS;
        cache.put(key, new CacheEntry(reply.clone(), putNow + ttlMs, putNow + ttlMs + staleMs));
    }

    private static boolean isGoogleVideoHost(String name) {
        return name != null && (name.equals("googlevideo.com") || name.endsWith(".googlevideo.com"));
    }

    private void sendReply(byte[] reply, InetAddress addr, int port) {
        if (reply == null || closed.get() || socket.isClosed()) return;
        try {
            socket.send(new DatagramPacket(reply, reply.length, addr, port));
        } catch (IOException ignored) {
        }
    }

    static byte[] buildEmptyNoErrorReply(byte[] query) {
        byte[] resp = query.clone();
        resp[2] = (byte) ((query[2] & 0x01) | 0x81); // QR=1, RD preserved
        resp[3] = (byte) 0x80; // RA=1, RCODE=0 (NOERROR)
        resp[6] = 0; resp[7] = 0; // ANCOUNT=0
        resp[8] = 0; resp[9] = 0; // NSCOUNT=0
        resp[10] = 0; resp[11] = 0; // ARCOUNT=0
        return resp;
    }

    static byte[] buildServFailReply(byte[] query) {
        byte[] resp = query.clone();
        resp[2] = (byte) ((query[2] & 0x01) | 0x81);
        resp[3] = (byte) 0x82; // RA=1, RCODE=2 (SERVFAIL)
        resp[6] = 0; resp[7] = 0;
        resp[8] = 0; resp[9] = 0;
        resp[10] = 0; resp[11] = 0;
        return resp;
    }

    static final class Question {
        final String name;
        final int qtype;

        Question(String name, int qtype) {
            this.name = name;
            this.qtype = qtype;
        }
    }

    static Question parseQuestion(byte[] msg) {
        if (msg == null || msg.length < 17) return null;
        int qdCount = ((msg[4] & 0xFF) << 8) | (msg[5] & 0xFF);
        if (qdCount < 1) return null;
        int pos = 12;
        StringBuilder name = new StringBuilder(64);
        while (pos < msg.length) {
            int labelLen = msg[pos++] & 0xFF;
            if (labelLen == 0) break;
            if ((labelLen & 0xC0) != 0 || pos + labelLen > msg.length) return null;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < labelLen; i++) {
                name.append((char) (msg[pos++] & 0xFF));
            }
        }
        if (pos + 4 > msg.length) return null;
        int qtype = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
        return new Question(name.toString().toLowerCase(Locale.ROOT), qtype);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        vpn = null;
        cache.clear();
        for (CompletableFuture<byte[]> f : inflightByKey.values()) {
            f.complete(null);
        }
        inflightByKey.clear();
        ColgramDohResolver.clearConnectionPool();
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
        try {
            workers.shutdownNow();
        } catch (Throwable ignored) {
        }
    }
}
