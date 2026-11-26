package org.colgram.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Small endpoint race under a single deadline. The result is only a TCP socket, not MTProto. */
public final class ColgramSocketConnectRace {
    private static final int SOCKET_BUFFER_BYTES = 262144;
    private static final ExecutorService RACE_POOL = new ThreadPoolExecutor(
            0, 64, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "colgram-race-dial");
                t.setDaemon(true);
                return t;
            });

    private ColgramSocketConnectRace() {}

    private static void preTuneSocket(Socket socket) {
        if (socket == null) return;
        try { socket.setTcpNoDelay(true); } catch (Throwable ignored) {}
        try { socket.setKeepAlive(true); } catch (Throwable ignored) {}
        try { socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES); } catch (Throwable ignored) {}
        try { socket.setSendBufferSize(SOCKET_BUFFER_BYTES); } catch (Throwable ignored) {}
    }

    /** Keep a reachable primary; give it part, not all, of the shared deadline. */
    public static Socket connectPreferred(InetSocketAddress primary, InetSocketAddress[] alternatives, int timeoutMs) {
        if (primary == null || timeoutMs <= 0) return null;
        LinkedHashSet<InetSocketAddress> distinct = new LinkedHashSet<>();
        if (alternatives != null) for (InetSocketAddress address : alternatives) {
            if (address != null && !address.equals(primary) && address.getPort() > 0) distinct.add(address);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int primaryHeadStartMs = ColgramDpiBypass.isCellularNetwork() ? 240 : 160;
        if (distinct.isEmpty()) {
            Socket socket = new Socket(Proxy.NO_PROXY);
            try {
                preTuneSocket(socket);
                ColgramDpiBypass.connectTracked(socket, primary, timeoutMs);
                return socket;
            } catch (IOException | RuntimeException unavailable) {
                close(socket);
                return null;
            }
        }
        return raceNativeWithHeadStart(primary, new ArrayList<>(distinct), deadline, primaryHeadStartMs,
                0, 0, null, null, false);
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
        if (primary == null || timeoutMs <= 0) return null;
        boolean nativeOk = ColgramNativeDesync.isAvailable() && extractIpLiteral(primary) != null;
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
        int primaryHeadStartMs = ColgramDpiBypass.isCellularNetwork() ? 240 : 160;
        if (distinct.isEmpty()) {
            if (nativeOk) {
                try {
                    return ColgramNativeDesync.connect(
                            extractIpLiteral(primary), primary.getPort(), timeoutMs, safeMss, windowClamp, fdProtector);
                } catch (IOException | RuntimeException unavailable) {
                    return null;
                }
            }
            return connectSingleProtected(primary, timeoutMs, sockProtector);
        }
        return raceNativeWithHeadStart(primary, new ArrayList<>(distinct), deadline, primaryHeadStartMs,
                safeMss, windowClamp, sockProtector, fdProtector, nativeOk);
    }

    private static Socket connectSingleProtected(InetSocketAddress endpoint, int timeoutMs,
                                                 ColgramDohResolver.SocketProtector sockProtector) {
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            preTuneSocket(socket);
            if (sockProtector != null && !sockProtector.protect(socket)) {
                close(socket);
                return null;
            }
            ColgramDpiBypass.connectTracked(socket, endpoint, timeoutMs);
            return socket;
        } catch (IOException | RuntimeException unavailable) {
            close(socket);
            return null;
        }
    }

    private static String extractIpLiteral(InetSocketAddress addr) {
        if (addr == null) return null;
        if (!addr.isUnresolved() && addr.getAddress() != null) {
            return addr.getAddress().getHostAddress();
        }
        String host = addr.getHostString();
        return ColgramDohResolver.isIpLiteral(host) ? host : null;
    }

    private static Socket raceNativeWithHeadStart(InetSocketAddress primary,
                                                  List<InetSocketAddress> alternatives,
                                                  long deadline,
                                                  int headStartMs,
                                                  int mssClamp,
                                                  int windowClamp,
                                                  ColgramDohResolver.SocketProtector sockProtector,
                                                  ColgramNativeDesync.FdProtector fdProtector,
                                                  boolean allowNative) {
        final Object gate = new Object();
        final Socket[] winner = new Socket[1];
        final boolean[] finished = new boolean[1];
        final List<Socket> pending = new ArrayList<>();
        final List<Future<?>> futures = new ArrayList<>(1 + alternatives.size());
        final CountDownLatch outcome = new CountDownLatch(1);
        final CountDownLatch primaryDone = new CountDownLatch(1);
        final int totalAttempts = 1 + alternatives.size();
        final AtomicInteger remaining = new AtomicInteger(totalAttempts);

        Runnable primaryTask = () -> {
            Socket socket = null;
            boolean selected = false;
            try {
                long left = nanosLeft(deadline);
                if (left <= 0) return;
                synchronized (gate) {
                    if (finished[0] || winner[0] != null) return;
                }
                int waitMs = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left));
                String ip = allowNative ? extractIpLiteral(primary) : null;
                if (ip != null) {
                    socket = ColgramNativeDesync.connect(ip, primary.getPort(), waitMs, mssClamp, windowClamp, fdProtector);
                } else {
                    Socket j = new Socket(Proxy.NO_PROXY);
                    preTuneSocket(j);
                    synchronized (gate) {
                        if (finished[0] || winner[0] != null || nanosLeft(deadline) <= 0) {
                            close(j);
                            return;
                        }
                        pending.add(j);
                    }
                    socket = j;
                    if (sockProtector != null && !sockProtector.protect(j)) {
                        return;
                    }
                    ColgramDpiBypass.connectTracked(j, primary, waitMs);
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
                primaryDone.countDown();
                if (remaining.decrementAndGet() == 0) outcome.countDown();
            }
        };

        try {
            futures.add(RACE_POOL.submit(primaryTask));
        } catch (Throwable rejected) {
            primaryDone.countDown();
            if (remaining.decrementAndGet() == 0) outcome.countDown();
        }

        // True Happy-Eyeballs stagger: give primary a brief head-start, then launch alternates in parallel
        try {
            long waitHead = Math.min(Math.max(10L, TimeUnit.NANOSECONDS.toMillis(nanosLeft(deadline))), headStartMs);
            primaryDone.await(waitHead, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        synchronized (gate) {
            if (winner[0] != null) {
                finished[0] = true;
                return winner[0];
            }
        }

        for (final InetSocketAddress endpoint : alternatives) {
            synchronized (gate) {
                if (finished[0] || winner[0] != null || nanosLeft(deadline) <= 0) {
                    if (remaining.decrementAndGet() == 0) outcome.countDown();
                    continue;
                }
            }
            Runnable altTask = () -> {
                Socket socket = null;
                boolean selected = false;
                try {
                    long left = nanosLeft(deadline);
                    if (left <= 0) return;
                    synchronized (gate) {
                        if (finished[0] || winner[0] != null) return;
                    }
                    int waitMs = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left));
                    String ip = allowNative ? extractIpLiteral(endpoint) : null;
                    if (ip != null) {
                        socket = ColgramNativeDesync.connect(ip, endpoint.getPort(), waitMs, mssClamp, windowClamp, fdProtector);
                    } else {
                        Socket j = new Socket(Proxy.NO_PROXY);
                        preTuneSocket(j);
                        synchronized (gate) {
                            if (finished[0] || winner[0] != null || nanosLeft(deadline) <= 0) {
                                close(j);
                                return;
                            }
                            pending.add(j);
                        }
                        socket = j;
                        if (sockProtector != null && !sockProtector.protect(j)) {
                            return;
                        }
                        ColgramDpiBypass.connectTracked(j, endpoint, waitMs);
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
            };
            try {
                futures.add(RACE_POOL.submit(altTask));
            } catch (Throwable rejected) {
                if (remaining.decrementAndGet() == 0) outcome.countDown();
            }
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
            for (Future<?> f : futures) {
                f.cancel(true);
            }
            return winner[0];
        }
    }

    private static Socket raceNative(List<InetSocketAddress> endpoints, long deadline,
                                     int mssClamp, int windowClamp,
                                     ColgramDohResolver.SocketProtector sockProtector,
                                     ColgramNativeDesync.FdProtector fdProtector) {
        if (endpoints == null || endpoints.isEmpty()) return null;
        return raceNativeWithHeadStart(endpoints.get(0), endpoints.subList(1, endpoints.size()),
                deadline, 0, mssClamp, windowClamp, sockProtector, fdProtector, true);
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
                    ColgramDpiBypass.connectTracked(socket, endpoint, (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
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
                    ColgramDpiBypass.connectTracked(socket, endpoint, (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
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
