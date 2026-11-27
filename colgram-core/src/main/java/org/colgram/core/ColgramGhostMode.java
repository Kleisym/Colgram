package org.colgram.core;

/**
 * ColgramGhostMode — Controls stealth features:
 * - Silent reading (no double checkmarks sent to sender)
 * - Stealth typing (no "typing..." or "recording audio..." status sent)
 * - Offline presence (does not send online ping)
 * - Screenshot protection bypass (removes FLAG_SECURE)
 */
public class ColgramGhostMode {

    private static final ThreadLocal<Integer> SPAM_MENTION_CLEAR_DEPTH = new ThreadLocal<>();

    /**
     * Runs {@code action} with read-receipt / mention / reaction clearing temporarily allowed
     * on the current thread (e.g. explicit "Mark all read" or spam cleanup actions).
     */
    public static void runAllowingSpamMentionClear(Runnable action) {
        if (action == null) {
            return;
        }
        Integer prev = SPAM_MENTION_CLEAR_DEPTH.get();
        int nextDepth = (prev != null ? prev : 0) + 1;
        SPAM_MENTION_CLEAR_DEPTH.set(nextDepth);
        try {
            action.run();
        } finally {
            if (prev == null || prev <= 0) {
                SPAM_MENTION_CLEAR_DEPTH.remove();
            } else {
                SPAM_MENTION_CLEAR_DEPTH.set(prev);
            }
        }
    }

    /**
     * @return true while an explicit spam/mention/read-all clear action is executing on this thread.
     */
    public static boolean isAllowingSpamMentionClear() {
        Integer depth = SPAM_MENTION_CLEAR_DEPTH.get();
        return depth != null && depth > 0;
    }

    /**
     * Intercepts messages.readHistory / channels.readHistory.
     * @return true if Telegram should be prevented from marking the chat as read on the server.
     */
    public static boolean shouldPreventReadReceipt(long dialogId) {
        if (isAllowingSpamMentionClear()) {
            return false;
        }
        return ColgramConfig.isGhostReadEnabled();
    }

    /**
     * Intercepts messages.setTyping.
     * @return true if Telegram should be prevented from sending typing action to the server.
     */
    public static boolean shouldPreventTypingStatus(long dialogId) {
        return ColgramConfig.isGhostTypingEnabled();
    }

    /**
     * Intercepts account.updateStatus.
     * @return true if Telegram should avoid reporting user as online.
     */
    public static boolean shouldStayOffline() {
        return ColgramConfig.isGhostOnlineEnabled();
    }

    /**
     * Determines whether WindowManager.LayoutParams.FLAG_SECURE should be stripped.
     */
    public static boolean shouldBypassFlagSecure() {
        return ColgramConfig.isBypassFlagSecureEnabled();
    }
}
