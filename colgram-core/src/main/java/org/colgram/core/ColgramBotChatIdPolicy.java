package org.colgram.core;

/**
 * Pure-JVM policy for resolving MTProto dialog IDs to Telegram Bot API chat_id candidates
 * without misclassifying modern basic groups (>1e9) or small supergroups/channels (<=1e9).
 */
public final class ColgramBotChatIdPolicy {

    private ColgramBotChatIdPolicy() {}

    public static long[] resolveChatIds(long dialogId, Boolean isChannelHint) {
        if (dialogId >= 0L) {
            return new long[]{dialogId};
        }
        long abs = -dialogId;
        long channelStyle = -(1_000_000_000_000L + abs);
        if (Boolean.TRUE.equals(isChannelHint)) {
            return new long[]{channelStyle};
        }
        if (Boolean.FALSE.equals(isChannelHint)) {
            return new long[]{dialogId};
        }
        return new long[]{channelStyle, dialogId};
    }
}
