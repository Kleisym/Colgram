package org.colgram.core;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Races a small set of ports without paying the timeout serially for each one. */
public final class ColgramSocketConnectRace {

    private ColgramSocketConnectRace() {}

    public static Socket connect(String host, int[] ports, int timeoutMs) {
        if (host == null || host.isEmpty() || ports == null || ports.length == 0) return null;
        final List<Integer> validPorts = new ArrayList<>();
        for (int port : ports) {
            if (port > 0 && port <= 65535 && !validPorts.contains(port)) validPorts.add(port);
        }
        if (validPorts.isEmpty()) return null;

        final Object gate = new Object();
        final Socket[] winner = new Socket[1];
        final boolean[] finished = new boolean[1];
        final List<Socket> pending = new ArrayList<>();
        final CountDownLatch outcome = new CountDownLatch(1);
        final AtomicInteger remaining = new AtomicInteger(validPorts.size());

        for (int port : validPorts) {
            final Socket candidate = new Socket();
            synchronized (gate) {
                if (finished[0]) {
                    close(candidate);
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                    continue;
                }
                pending.add(candidate);
            }
            Thread attempt = new Thread(() -> {
                try {
                    synchronized (gate) {
                        if (finished[0]) return;
                    }
                    candidate.setTcpNoDelay(true);
                    candidate.connect(new InetSocketAddress(host, port), Math.max(1, timeoutMs));
                    synchronized (gate) {
                        if (!finished[0] && winner[0] == null) {
                            winner[0] = candidate;
                            outcome.countDown();
                        } else {
                            close(candidate);
                        }
                    }
                } catch (Throwable ignored) {
                    close(candidate);
                } finally {
                    synchronized (gate) {
                        pending.remove(candidate);
                    }
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                }
            }, "colgram-port-race-" + port);
            attempt.setDaemon(true);
            attempt.start();
        }

        try {
            outcome.await(Math.max(1, timeoutMs) + 100L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        final Socket result;
        synchronized (gate) {
            finished[0] = true;
            result = winner[0];
            for (Socket socket : pending) {
                if (socket != result) close(socket);
            }
        }
        return result;
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (Throwable ignored) {}
    }
}
