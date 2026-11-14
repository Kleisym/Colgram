package org.colgram.core;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class ColgramProbeLanesTest {
    public static void main(String[] args) throws Exception {
        ColgramProbeLanes pool = new ColgramProbeLanes(3);
        ColgramProbeLanes.Ticket a = pool.acquire(), b = pool.acquire(), c = pool.acquire();
        Set<Integer> slots = new HashSet<>();
        slots.add(a.lane()); slots.add(b.lane()); slots.add(c.lane());
        if (slots.size() != 3 || pool.acquire() != null) throw new AssertionError("capacity or lane collision");
        if (!pool.release(b)) throw new AssertionError("owner could not release");
        ColgramProbeLanes.Ticket replacement = pool.acquire();
        if (replacement.lane() != b.lane() || replacement.id() == b.id()) throw new AssertionError("reused owner");
        if (pool.release(b) || pool.acquire() != null) throw new AssertionError("stale completion freed a newer probe");
        pool.release(a); pool.release(c); pool.release(replacement);
        AtomicInteger live = new AtomicInteger(), peak = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[24];
        for (int index = 0; index < workers.length; index++) {
            workers[index] = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        ColgramProbeLanes.Ticket ticket = pool.acquire();
                        if (ticket == null) { Thread.yield(); continue; }
                        int current = live.incrementAndGet();
                        peak.accumulateAndGet(current, Math::max);
                        Thread.yield();
                        live.decrementAndGet();
                        if (!pool.release(ticket)) throw new AssertionError("lost lane owner");
                    }
                } catch (InterruptedException error) { throw new AssertionError(error); }
            });
            workers[index].setUncaughtExceptionHandler((thread, error) -> { error.printStackTrace(); System.exit(1); });
            workers[index].start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
        if (peak.get() > 3 || live.get() != 0) throw new AssertionError("unbounded probes");
        System.out.println("ColgramProbeLanesTest: distinct slots, capacity=3, stale callback, 24-thread contention PASS");
    }
}
