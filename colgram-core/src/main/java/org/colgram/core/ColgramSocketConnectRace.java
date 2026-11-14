package org.colgram.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Small endpoint race under a single deadline. The result is only a TCP socket, not MTProto. */
public final class ColgramSocketConnectRace {
    private ColgramSocketConnectRace() {}

    /** Keep a reachable primary; give it part, not all, of the shared deadline. */
    public static Socket connectPreferred(InetSocketAddress primary, InetSocketAddress[] alternatives, int timeoutMs) {
        if (primary == null || timeoutMs <= 0) return null;
        LinkedHashSet<InetSocketAddress> distinct = new LinkedHashSet<>();
        if (alternatives != null) for (InetSocketAddress address : alternatives) {
            if (address != null && !address.equals(primary) && address.getPort() > 0) distinct.add(address);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.setTcpNoDelay(true);
            socket.connect(primary, distinct.isEmpty() ? timeoutMs : Math.min(timeoutMs, 800));
            return socket;
        } catch (IOException | RuntimeException unavailable) {
            close(socket);
        }
        return distinct.isEmpty() ? null : race(new ArrayList<>(distinct), deadline);
    }

    private static Socket race(List<InetSocketAddress> endpoints, long deadline) {
        final Object gate = new Object();
        final Socket[] winner = new Socket[1];
        final boolean[] finished = new boolean[1];
        final List<Socket> pending = new ArrayList<>();
        final CountDownLatch outcome = new CountDownLatch(1);
        final AtomicInteger remaining = new AtomicInteger(endpoints.size());
        for (InetSocketAddress endpoint : endpoints) {
            Socket socket = new Socket(Proxy.NO_PROXY);
            synchronized (gate) {
                if (winner[0] != null || nanosLeft(deadline) <= 0) {
                    close(socket);
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                    continue;
                }
                pending.add(socket);
            }
            Thread attempt = new Thread(() -> {
                boolean selected = false;
                try {
                    long left = nanosLeft(deadline);
                    if (left <= 0) return;
                    socket.setTcpNoDelay(true);
                    socket.connect(endpoint, (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                    synchronized (gate) {
                        if (!finished[0] && winner[0] == null && nanosLeft(deadline) > 0) {
                            winner[0] = socket;
                            selected = true;
                            outcome.countDown();
                        }
                    }
                } catch (IOException | RuntimeException unavailable) {
                    // A sibling can still answer. The parent owns the common deadline.
                } finally {
                    if (!selected) close(socket);
                    synchronized (gate) { pending.remove(socket); }
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                }
            }, "colgram-direct-dial");
            attempt.setDaemon(true);
            attempt.start();
        }
        try {
            outcome.await(Math.max(0L, nanosLeft(deadline)), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (gate) {
            finished[0] = true;
            for (Socket socket : pending) if (socket != winner[0]) close(socket);
            return winner[0];
        }
    }

    private static long nanosLeft(long deadline) { return deadline - System.nanoTime(); }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
