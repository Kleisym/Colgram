package org.colgram.core;

/**
 * ColgramGhostMode — Controls stealth features:
 * - Silent reading (no double checkmarks sent to sender)
 * - Stealth typing (no "typing..." or "recording audio..." status sent)
 * - Offline presence (does not send online ping)
 * - Screenshot protection bypass (removes FLAG_SECURE)
 */
public class ColgramGhostMode {

    /**
     * Intercepts messages.readHistory / channels.readHistory.
     * @return true if Telegram should be prevented from marking the chat as read on the server.
     */
    public static boolean shouldPreventReadReceipt(long dialogId) {
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
