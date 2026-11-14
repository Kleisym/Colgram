package org.colgram.core;

/** Fixed anonymous-probe capacity with generation-owned completion, independent of Android. */
public final class ColgramProbeLanes {
    public static final class Ticket {
        private final int lane;
        private final long id;
        private Ticket(int lane, long id) { this.lane = lane; this.id = id; }
        public int lane() { return lane; }
        public long id() { return id; }
    }
    private final long[] owners;
    private long next;

    public ColgramProbeLanes(int capacity) {
        if (capacity < 1 || capacity > 8) throw new IllegalArgumentException("probe capacity");
        owners = new long[capacity];
    }

    public synchronized Ticket acquire() {
        for (int lane = 0; lane < owners.length; lane++) {
            if (owners[lane] != 0L) continue;
            long id = ++next;
            if (id == 0L) id = ++next;
            owners[lane] = id;
            return new Ticket(lane, id);
        }
        return null;
    }

    public synchronized boolean hasCapacity() {
        for (long owner : owners) if (owner == 0L) return true;
        return false;
    }

    public synchronized boolean release(Ticket ticket) {
        if (ticket == null || ticket.lane() < 0 || ticket.lane() >= owners.length
                || owners[ticket.lane()] != ticket.id()) return false;
        owners[ticket.lane()] = 0L;
        return true;
    }
}
