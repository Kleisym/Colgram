package org.colgram.core;

import android.net.VpnService;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local loopback UDP DNS forwarder for whole-device DPI bypass TUN.
 *
 * Replaces sing-box's single-provider `final: "sys-dns-adguard"` (which suffers total DNS blackout
 * on any mobile carrier that blocks/throttles 94.140.14.14) with a resilient multi-provider
 * protected DoH racer (AdGuard, Google, Quad9, Cloudflare, Mullvad, ControlD, DNS.SB over native
 * ByeDPI desynced sockets + protected UDP/53 last-resort fallback) backed by a fast TTL cache.
 */
final class ColgramLocalDnsProxy implements Closeable {

    private static final String TAG = "ColgramLocalDns";
    private static final int MAX_PACKET = 4096;
    private static final int MAX_CACHE = 1024;
    private static final long CACHE_TTL_MS = 45_000L;

    interface WireForwarder {
        byte[] forward(byte[] wireQuery);
    }

    private static final class CacheEntry {
        final byte[] reply;
        final long expiresAt;

        CacheEntry(byte[] reply, long expiresAt) {
            this.reply = reply;
            this.expiresAt = expiresAt;
        }
    }

    private final DatagramSocket socket;
    private final boolean ipv4Only;
    private final WireForwarder customForwarder;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Semaphore inflight = new Semaphore(48);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "colgram-local-dns");
        t.setDaemon(true);
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
                workers.execute(() -> {
                    try {
                        handleQuery(query, clientAddr, clientPort);
                    } finally {
                        inflight.release();
                    }
                });
            } catch (IOException ignored) {
                if (closed.get() || socket.isClosed()) break;
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleQuery(byte[] query, InetAddress clientAddr, int clientPort) {
        Question q = parseQuestion(query);
        if (ipv4Only && q != null && q.qtype == 28) {
            sendReply(buildEmptyNoErrorReply(query), clientAddr, clientPort);
            return;
        }
        String key = q != null ? (q.name + "|" + q.qtype) : null;
        long now = System.currentTimeMillis();
        if (key != null) {
            CacheEntry hit = cache.get(key);
            if (hit != null && now < hit.expiresAt && hit.reply.length >= 12) {
                byte[] patched = hit.reply.clone();
                patched[0] = query[0];
                patched[1] = query[1];
                sendReply(patched, clientAddr, clientPort);
                return;
            }
        }

        byte[] reply;
        if (customForwarder != null) {
            reply = customForwarder.forward(query);
        } else {
            VpnService owner = vpn;
            if (owner == null || closed.get()) return;
            reply = ColgramDohResolver.queryRawWireProtected(
                    query, owner::protect, owner::protect, owner::protect);
        }

        if (reply == null || reply.length < 12) {
            sendReply(buildServFailReply(query), clientAddr, clientPort);
            return;
        }
        byte[] out = reply.clone();
        out[0] = query[0];
        out[1] = query[1];
        if (key != null && (out[3] & 0x0F) == 0) {
            if (cache.size() >= MAX_CACHE) cache.clear();
            cache.put(key, new CacheEntry(out.clone(), System.currentTimeMillis() + CACHE_TTL_MS));
        }
        sendReply(out, clientAddr, clientPort);
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
