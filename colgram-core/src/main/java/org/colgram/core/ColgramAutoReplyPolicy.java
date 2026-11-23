package org.colgram.core;

import java.util.Locale;

/**
 * Pure-Java Telegram Business style auto-responder decision & template engine (no android.* deps).
 */
public final class ColgramAutoReplyPolicy {
    private ColgramAutoReplyPolicy() {}

    public static final int AUDIENCE_ALL = 0;
    public static final int AUDIENCE_CONTACTS = 1;
    public static final int AUDIENCE_NON_CONTACTS = 2;
    public static final int AUDIENCE_PRIVATE_ONLY = 3;

    /**
     * Checks whether {@code nowMinutesOfDay} (0..1439) falls inside a daily window [start, end).
     * Supports overnight schedules where {@code startMinutes > endMinutes} (e.g. 22:00 -> 09:00).
     */
    public static boolean isWithinSchedule(boolean scheduleEnabled, int startMinutes, int endMinutes, int nowMinutesOfDay) {
        if (!scheduleEnabled) {
            return false;
        }
        int start = Math.floorMod(startMinutes, 1440);
        int end = Math.floorMod(endMinutes, 1440);
        int cur = Math.floorMod(nowMinutesOfDay, 1440);
        if (start == end) {
            return true; // 24h window
        }
        if (start < end) {
            return cur >= start && cur < end;
        }
        return cur >= start || cur < end;
    }

    /** FormatsHH:mm minute offset cleanly as "HH:mm". */
    public static String formatMinutes(int totalMinutes) {
        int m = Math.floorMod(totalMinutes, 1440);
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    /** True when {@code dialogId} appears in the comma/space-separated blacklist string. */
    public static boolean isBlacklisted(long dialogId, String blacklistCsv) {
        if (blacklistCsv == null || blacklistCsv.trim().isEmpty()) {
            return false;
        }
        String target = Long.toString(dialogId);
        for (String token : blacklistCsv.split("[,;\\s]+")) {
            if (target.equals(token.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Core loop-safe reply gate across human and bot accounts. */
    public static boolean shouldReply(
            boolean enabled,
            long dialogId,
            long selfUserId,
            boolean isOut,
            boolean isBotPeer,
            boolean replyToBots,
            boolean isChannel,
            boolean allowGroups,
            boolean isContact,
            int audienceMode,
            String blacklistCsv,
            long nowMs,
            long lastReplyMs,
            int cooldownSec) {
        if (!enabled || isOut || dialogId == 0L || (selfUserId > 0L && dialogId == selfUserId)) {
            return false;
        }
        if (isChannel) {
            return false;
        }
        if (dialogId < 0L && !allowGroups) {
            return false;
        }
        if (isBotPeer && !replyToBots) {
            return false;
        }
        if (isBlacklisted(dialogId, blacklistCsv)) {
            return false;
        }
        if (dialogId > 0L) {
            if (audienceMode == AUDIENCE_CONTACTS && !isContact) {
                return false;
            }
            if (audienceMode == AUDIENCE_NON_CONTACTS && isContact) {
                return false;
            }
        } else if (audienceMode == AUDIENCE_PRIVATE_ONLY || audienceMode == AUDIENCE_CONTACTS || audienceMode == AUDIENCE_NON_CONTACTS) {
            return false;
        }
        long cooldownMs = Math.max(0, cooldownSec) * 1000L;
        if (cooldownMs > 0L && lastReplyMs > 0L && nowMs - lastReplyMs < cooldownMs) {
            return false;
        }
        return true;
    }

    /** Picks primary vs away text based on active Business schedule. */
    public static String selectReplyText(
            boolean scheduleEnabled,
            boolean replyOnlyWhenAway,
            int awayStartMinutes,
            int awayEndMinutes,
            int nowMinutesOfDay,
            String primaryText,
            String awayText) {
        boolean inAwayWindow = isWithinSchedule(scheduleEnabled, awayStartMinutes, awayEndMinutes, nowMinutesOfDay);
        if (scheduleEnabled && replyOnlyWhenAway && !inAwayWindow) {
            return null; // User is currently "available" outside away schedule
        }
        if (inAwayWindow && awayText != null && !awayText.trim().isEmpty()) {
            return awayText;
        }
        return primaryText;
    }

    /** Replaces Business placeholders ({name}, {first_name}, {username}, {time}) safely. */
    public static String renderTemplate(String template, String fullName, String username, int hour, int minute) {
        if (template == null) return "";
        String cleanName = (fullName != null && !fullName.trim().isEmpty()) ? fullName.trim() : "друг";
        int space = cleanName.indexOf(' ');
        String firstName = space > 0 ? cleanName.substring(0, space) : cleanName;
        String cleanUser = (username != null && !username.trim().isEmpty())
                ? ("@" + username.trim().replaceFirst("^@", ""))
                : cleanName;
        String timeStr = String.format(Locale.US, "%02d:%02d",
                Math.floorMod(hour, 24), Math.floorMod(minute, 60));

        return template
                .replace("{name}", cleanName)
                .replace("{first_name}", firstName)
                .replace("{username}", cleanUser)
                .replace("{time}", timeStr);
    }
}
