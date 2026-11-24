package org.colgram.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Byte-preserving local shaping in front of the exact selected proxy, not another relay. */
public final class ColgramLocalTcpFront implements AutoCloseable {
    private static final Semaphore SLOTS = new Semaphore(48);
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "colgram-base-pipe"); t.setDaemon(true); return t;
    });
    private final ServerSocket listener;
    private final String host;
    private final int targetPort;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ColgramLocalTcpFront(String host, int port) throws IOException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid base destination");
        this.host = host;
        this.targetPort = port;
        listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        IO.execute(this::accept);
    }

    public int port() { return closed ? 0 : listener.getLocalPort(); }

    private void accept() {
        try {
            while (!closed) {
                Socket client = listener.accept();
                if (closed || !SLOTS.tryAcquire()) { client.close(); continue; }
                sockets.add(client);
                IO.execute(() -> relay(client));
            }
        } catch (IOException ignored) {
        } finally { close(); }
    }

    private void relay(Socket client) {
        Socket target = new Socket(java.net.Proxy.NO_PROXY);
        sockets.add(target);
        try {
            if (closed) return;
            client.setTcpNoDelay(true);
            target.setTcpNoDelay(true);
            try {
                client.setReceiveBufferSize(262144);
                client.setSendBufferSize(262144);
                target.setReceiveBufferSize(262144);
                target.setSendBufferSize(262144);
                target.setKeepAlive(true);
            } catch (Throwable ignored) {}
            target.connect(new InetSocketAddress(host, targetPort), 4500);
            if (closed) return;
            java.util.concurrent.CountDownLatch downstream = new java.util.concurrent.CountDownLatch(1);
            IO.execute(() -> {
                try { copy(target.getInputStream(), client.getOutputStream(), false); }
                catch (IOException ignored) {}
                finally { try { client.shutdownOutput(); } catch (IOException ignored) {} downstream.countDown(); }
            });
            copy(client.getInputStream(), target.getOutputStream(), true);
            target.shutdownOutput();
            // Preserve downstream after the caller's FIN (TLS close_notify / final response).
            downstream.await();
        } catch (IOException ignored) {
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
        } finally {
            closeSocket(client); closeSocket(target);
            sockets.remove(client); sockets.remove(target); SLOTS.release();
        }
    }

    private static void copy(InputStream in, OutputStream out, boolean shape) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count, shaped = 0;
        while ((count = in.read(buffer)) != -1) {
            // Shape SOCKS5 CONNECT requests (10-byte IPv4 / 22-byte IPv6 / domain) into
            // 2-byte TSPU-evading TCP segments so carrier DPI never sees `05 01 00 01 149.154.*`
            // or `kws*.web.telegram.org` in a single TCP segment.
            boolean socksConnect = count >= 8 && count <= 262
                    && buffer[0] == 5 && buffer[1] == 1 && buffer[2] == 0
                    && (buffer[3] == 1 || buffer[3] == 3 || buffer[3] == 4);
            if (shape && socksConnect && shaped < 4) {
                for (int off = 0; off < count; ) {
                    int step = Math.min(2, count - off);
                    out.write(buffer, off, step);
                    out.flush();
                    off += step;
                    if (off < count) sleepQuiet(6);
                }
                shaped++;
                continue;
            }
            // Shape only the beginning and an actual TLS/FakeTLS ClientHello after SOCKS negotiation.
            // 100% byte-preserving: no injected bytes, no TLS rewriting, no HMAC breakage — slices
            // directly at byte 1 + SNI midpoint so TSPU cannot extract the full FakeTLS/TLS SNI.
            boolean hello = count > 11 && buffer[0] == 22 && buffer[1] == 3 && buffer[5] == 1;
            if (shape && count > 16 && (shaped == 0 || (hello && shaped < 4))) {
                int[] sni = hello ? ColgramTlsMimic.findTlsSniSpan(buffer, count) : null;
                if (sni != null) {
                    int sniMid = sni[0] + Math.max(1, (sni[1] - sni[0]) / 2);
                    if (sniMid > 1 && sniMid < count) {
                        out.write(buffer, 0, 1); out.flush();
                        sleepQuiet(1);
                        out.write(buffer, 1, sniMid - 1); out.flush();
                        sleepQuiet(2);
                        out.write(buffer, sniMid, count - sniMid); out.flush();
                        shaped++;
                        continue;
                    }
                }
                int cut = hello ? 1 : Math.min(32, count / 2);
                out.write(buffer, 0, cut); out.flush();
                sleepQuiet(2);
                out.write(buffer, cut, count - cut); shaped++;
            } else out.write(buffer, 0, count);
            if (in.available() <= 0) {
                out.flush();
            }
        }
        out.flush();
    }

    private static void sleepQuiet(long ms) throws IOException {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket socket : sockets) closeSocket(socket);
        sockets.clear();
    }
}
