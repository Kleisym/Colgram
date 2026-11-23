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
        int primaryCap = ColgramDpiBypass.isCellularNetwork() ? 1400 : 800;
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.setTcpNoDelay(true);
            socket.connect(primary, distinct.isEmpty() ? timeoutMs : Math.min(timeoutMs, primaryCap));
            return socket;
        } catch (IOException | RuntimeException unavailable) {
            close(socket);
        }
        return distinct.isEmpty() ? null : race(new ArrayList<>(distinct), deadline);
    }

    /**
     * Native ByeDPI socket connect race: creates C-owned {@link ColgramNativeDesync.NativeSocket}
     * handles (supporting setsockopt IP_TTL/IPV6_UNICAST_HOPS/TCP_MAXSEG, MSG_OOB, sendfile fake)
     * with automatic fallback to {@link #connectPreferred} when native library is unavailable.
     */
    public static Socket connectPreferredNative(InetSocketAddress primary, InetSocketAddress[] alternatives,
                                                int timeoutMs, int mssClamp) {
        return connectPreferredNative(primary, alternatives, timeoutMs, mssClamp, 0);
    }

    public static Socket connectPreferredNative(InetSocketAddress primary, InetSocketAddress[] alternatives,
                                                int timeoutMs, int mssClamp, int windowClamp) {
        return connectPreferredNative(primary, alternatives, timeoutMs, mssClamp, windowClamp, null, null);
    }

    public static Socket connectPreferredNative(InetSocketAddress primary, InetSocketAddress[] alternatives,
                                                int timeoutMs, int mssClamp, int windowClamp,
                                                ColgramDohResolver.SocketProtector sockProtector,
                                                ColgramNativeDesync.FdProtector fdProtector) {
        if (!ColgramNativeDesync.isAvailable() || primary == null || timeoutMs <= 0) {
            return connectPreferredProtected(primary, alternatives, timeoutMs, sockProtector);
        }
        String primaryIp = extractIpLiteral(primary);
        if (primaryIp == null) {
            return connectPreferredProtected(primary, alternatives, timeoutMs, sockProtector);
        }
        LinkedHashSet<InetSocketAddress> distinct = new LinkedHashSet<>();
        if (alternatives != null) {
            for (InetSocketAddress address : alternatives) {
                if (address != null && !address.equals(primary) && address.getPort() > 0) {
                    distinct.add(address);
                }
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int safeMss = (mssClamp > 0 && mssClamp < 88) ? 88 : mssClamp;
        int primaryCap = ColgramDpiBypass.isCellularNetwork() ? 1400 : 750;
        try {
            int firstWait = distinct.isEmpty() ? timeoutMs : Math.min(timeoutMs, primaryCap);
            return ColgramNativeDesync.connect(primaryIp, primary.getPort(), firstWait, safeMss, windowClamp, fdProtector);
        } catch (IOException | RuntimeException unavailable) {
            // Fall through to parallel race across same-DC IPv4 + IPv6 + multi-port aliases
        }
        return distinct.isEmpty() ? null
                : raceNative(new ArrayList<>(distinct), deadline, safeMss, windowClamp, sockProtector, fdProtector);
    }

    private static Socket connectPreferredProtected(InetSocketAddress primary, InetSocketAddress[] alternatives,
                                                    int timeoutMs, ColgramDohResolver.SocketProtector sockProtector) {
        if (sockProtector == null) {
            return connectPreferred(primary, alternatives, timeoutMs);
        }
        if (primary == null || timeoutMs <= 0) return null;
        LinkedHashSet<InetSocketAddress> distinct = new LinkedHashSet<>();
        if (alternatives != null) for (InetSocketAddress address : alternatives) {
            if (address != null && !address.equals(primary) && address.getPort() > 0) distinct.add(address);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int primaryCap = ColgramDpiBypass.isCellularNetwork() ? 1400 : 800;
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.setTcpNoDelay(true);
            if (!sockProtector.protect(socket)) throw new IOException("Socket protection failed");
            socket.connect(primary, distinct.isEmpty() ? timeoutMs : Math.min(timeoutMs, primaryCap));
            return socket;
        } catch (IOException | RuntimeException unavailable) {
            close(socket);
        }
        return distinct.isEmpty() ? null : raceProtected(new ArrayList<>(distinct), deadline, sockProtector);
    }

    private static String extractIpLiteral(InetSocketAddress addr) {
        if (addr == null) return null;
        if (!addr.isUnresolved() && addr.getAddress() != null) {
            return addr.getAddress().getHostAddress();
        }
        String host = addr.getHostString();
        return ColgramDohResolver.isIpLiteral(host) ? host : null;
    }

    private static Socket raceNative(List<InetSocketAddress> endpoints, long deadline,
                                     int mssClamp, int windowClamp,
                                     ColgramDohResolver.SocketProtector sockProtector,
                                     ColgramNativeDesync.FdProtector fdProtector) {
        final Object gate = new Object();
        final Socket[] winner = new Socket[1];
        final boolean[] finished = new boolean[1];
        final List<Socket> pending = new ArrayList<>();
        final List<Thread> workers = new ArrayList<>(endpoints.size());
        final CountDownLatch outcome = new CountDownLatch(1);
        final AtomicInteger remaining = new AtomicInteger(endpoints.size());
        for (InetSocketAddress endpoint : endpoints) {
            Thread attempt = new Thread(() -> {
                Socket socket = null;
                boolean selected = false;
                try {
                    long left = nanosLeft(deadline);
                    if (left <= 0) return;
                    synchronized (gate) {
                        if (finished[0] || winner[0] != null) return;
                    }
                    int waitMs = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left));
                    String ip = extractIpLiteral(endpoint);
                    if (ip != null) {
                        socket = ColgramNativeDesync.connect(ip, endpoint.getPort(), waitMs, mssClamp, windowClamp, fdProtector);
                    } else {
                        Socket j = new Socket(Proxy.NO_PROXY);
                        synchronized (gate) {
                            if (finished[0] || winner[0] != null || nanosLeft(deadline) <= 0) {
                                close(j);
                                return;
                            }
                            pending.add(j);
                        }
                        socket = j;
                        j.setTcpNoDelay(true);
                        if (sockProtector != null && !sockProtector.protect(j)) {
                            return;
                        }
                        j.connect(endpoint, waitMs);
                    }
                    synchronized (gate) {
                        if (!finished[0] && winner[0] == null && nanosLeft(deadline) > 0) {
                            winner[0] = socket;
                            selected = true;
                            pending.remove(socket);
                            outcome.countDown();
                        }
                    }
                } catch (IOException | RuntimeException unavailable) {
                } finally {
                    if (!selected && socket != null) close(socket);
                    synchronized (gate) { if (socket != null) pending.remove(socket); }
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                }
            }, "colgram-native-dial");
            attempt.setDaemon(true);
            workers.add(attempt);
            attempt.start();
        }
        try {
            outcome.await(Math.max(0L, nanosLeft(deadline)), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (gate) {
            finished[0] = true;
            for (Socket socket : new ArrayList<>(pending)) {
                if (socket != winner[0]) close(socket);
            }
            pending.clear();
            for (Thread worker : workers) {
                if (worker.isAlive()) worker.interrupt();
            }
            return winner[0];
        }
    }

    private static Socket raceProtected(List<InetSocketAddress> endpoints, long deadline,
                                        ColgramDohResolver.SocketProtector sockProtector) {
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
                    if (sockProtector != null && !sockProtector.protect(socket)) return;
                    socket.connect(endpoint, (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                    synchronized (gate) {
                        if (!finished[0] && winner[0] == null && nanosLeft(deadline) > 0) {
                            winner[0] = socket;
                            selected = true;
                            outcome.countDown();
                        }
                    }
                } catch (IOException | RuntimeException unavailable) {
                } finally {
                    if (!selected) close(socket);
                    synchronized (gate) { pending.remove(socket); }
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                }
            }, "colgram-prot-dial");
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
