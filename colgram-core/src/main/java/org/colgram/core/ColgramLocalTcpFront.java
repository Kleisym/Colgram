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
            target.connect(new InetSocketAddress(host, targetPort), 3500);
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
        byte[] buffer = new byte[16 * 1024];
        int count, shaped = 0;
        while ((count = in.read(buffer)) != -1) {
            // Shape only the beginning and an actual TLS ClientHello after SOCKS negotiation.
            // No injected bytes, TLS rewriting, truncation, address/port changes or extra server.
            boolean hello = count > 5 && buffer[0] == 22 && buffer[1] == 3 && buffer[5] == 1;
            if (shape && count > 16 && (shaped == 0 || (hello && shaped < 4))) {
                int cut = Math.min(32, count / 2);
                out.write(buffer, 0, cut); out.flush();
                try { Thread.sleep(2); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                out.write(buffer, cut, count - cut); shaped++;
            } else out.write(buffer, 0, count);
            out.flush();
        }
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
