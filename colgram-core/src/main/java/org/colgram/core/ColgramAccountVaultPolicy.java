package org.colgram.core;

/**
 * Pure-Java policy for account badges and vault slot selection (no android.* deps).
 */
public final class ColgramAccountVaultPolicy {
    private ColgramAccountVaultPolicy() {}

    public static final String BADGE_HUMAN = "\uD83D\uDC64";
    public static final String BADGE_BOT = "\uD83E\uDD16 \u0411\u043E\u0442";

    public static String badge(boolean isBot) {
        return isBot ? BADGE_BOT : BADGE_HUMAN;
    }

    public static String pillText(boolean isBot) {
        return isBot ? BADGE_BOT : (BADGE_HUMAN + " \u0427\u0435\u043B");
    }

    public static String stableAccountKey(long userId, int fallbackSlot) {
        return userId > 0 ? ("uid_" + userId) : ("slot_" + fallbackSlot);
    }

    public static String label(String name, boolean isBot) {
        String n = name == null ? "" : name;
        return n + "  " + badge(isBot);
    }

    /**
     * Picks the native slot to archive when all slots are full:
     * least recently used slot that is neither pinned nor currently active.
     * @return slot index or -1 when every slot is pinned/active.
     */
    public static int pickEvictableSlot(boolean[] pinned, long[] lastUsed, int activeSlot) {
        int best = -1;
        for (int i = 0; i < pinned.length; i++) {
            if (i == activeSlot || pinned[i]) continue;
            if (best == -1 || lastUsed[i] < lastUsed[best]) best = i;
        }
        return best;
    }

    /**
     * Picks the least recently used inactive slot, preferring unpinned slots first,
     * falling back to the oldest inactive pinned slot when all slots are pinned so
     * adding accounts stays unlimited.
     */
    public static int pickEvictableSlotAllowPinnedFallback(boolean[] pinned, long[] lastUsed, int activeSlot) {
        int best = pickEvictableSlot(pinned, lastUsed, activeSlot);
        if (best >= 0) {
            return best;
        }
        for (int i = 0; i < pinned.length; i++) {
            if (i == activeSlot) continue;
            if (best == -1 || lastUsed[i] < lastUsed[best]) best = i;
        }
        return best >= 0 ? best : (pinned.length > 0 ? 0 : -1);
    }

    public static int nextPinOrder(int[] existingOrders) {
        int max = 0;
        if (existingOrders != null) {
            for (int v : existingOrders) {
                if (v > max) max = v;
            }
        }
        return max + 1;
    }

    /** Sort order: pinned first (by pin order ascending), then others by last used descending. */
    public static Integer[] order(boolean[] pinned, int[] pinOrder, long[] lastUsed) {
        Integer[] idx = new Integer[pinned.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            if (pinned[a] != pinned[b]) return pinned[a] ? -1 : 1;
            if (pinned[a]) return Integer.compare(pinOrder[a], pinOrder[b]);
            return Long.compare(lastUsed[b], lastUsed[a]);
        });
        return idx;
    }
}
