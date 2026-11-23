package org.colgram.core;

public final class ColgramAutoReplyPolicyTest {
    private static void check(boolean c, String m) {
        if (!c) throw new AssertionError(m);
    }

    public static void main(String[] args) {
        // Overnight schedule: 22:00 (1320) -> 09:00 (540)
        check(ColgramAutoReplyPolicy.isWithinSchedule(true, 22 * 60, 9 * 60, 23 * 60), "23:00 inside overnight window");
        check(ColgramAutoReplyPolicy.isWithinSchedule(true, 22 * 60, 9 * 60, 3 * 60), "03:00 inside overnight window");
        check(!ColgramAutoReplyPolicy.isWithinSchedule(true, 22 * 60, 9 * 60, 14 * 60), "14:00 outside overnight window");
        check(ColgramAutoReplyPolicy.isWithinSchedule(true, 9 * 60, 18 * 60, 12 * 60), "12:00 inside daytime window");
        check(!ColgramAutoReplyPolicy.isWithinSchedule(true, 9 * 60, 18 * 60, 19 * 60), "19:00 outside daytime window");
        check("22:05".equals(ColgramAutoReplyPolicy.formatMinutes(22 * 60 + 5)), "formatMinutes");

        // Template rendering
        String rendered = ColgramAutoReplyPolicy.renderTemplate(
                "Привет, {first_name} ({name} / {username}) в {time}",
                "Иван Петров", "ivanp", 8, 5);
        check("Привет, Иван (Иван Петров / @ivanp) в 08:05".equals(rendered), "rendered=" + rendered);

        // Select text with schedule
        check(ColgramAutoReplyPolicy.selectReplyText(
                true, true, 22 * 60, 9 * 60, 14 * 60, "Primary", "Away") == null,
                "silent outside away window when onlyWhenAway=true");
        check("Away".equals(ColgramAutoReplyPolicy.selectReplyText(
                true, true, 22 * 60, 9 * 60, 23 * 60, "Primary", "Away")),
                "away text active during away window");

        // Loop / self / blacklist / audience / cooldown guards
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 100L, 100L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                "never reply to self");
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 100L, false, true, false, false, true, false,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                "skip bot loop unless replyToBots=true");
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 100L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_NON_CONTACTS, "", 10_000L, 0L, 60),
                "non-contacts skips contacts");
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 100L, false, false, false, false, true, false,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "999, 200", 10_000L, 0L, 60),
                "blacklist respected");
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 100L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 50_000L, 20_000L, 60),
                "cooldown blocks early repeat");
        check(ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 100L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_CONTACTS, "", 90_000L, 20_000L, 60),
                "valid contact allowed after cooldown");
        // Group audience guard: CONTACTS / NON_CONTACTS must NEVER leak into group chats
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, -100123456L, 100L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_CONTACTS, "", 90_000L, 0L, 60),
                "AUDIENCE_CONTACTS blocks groups even when allowGroups=true");
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, -100123456L, 100L, false, false, false, false, true, false,
                ColgramAutoReplyPolicy.AUDIENCE_NON_CONTACTS, "", 90_000L, 0L, 60),
                "AUDIENCE_NON_CONTACTS blocks groups even when allowGroups=true");
        check(ColgramAutoReplyPolicy.shouldReply(
                true, -100123456L, 100L, false, false, false, false, true, false,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 90_000L, 0L, 60),
                "AUDIENCE_ALL allows groups when allowGroups=true");

        System.out.println("ColgramAutoReplyPolicyTest: PASS");
    }
}
