package org.colgram.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Unit tests for the embedded Colemailer v3.4.19 core engine, spam guard, and notification filter. */
public final class ColgramColemailerTest {
    private static void check(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError(msg);
        }
    }

    private static void testSpamGuardNormalizationAndPingDetection() {
        ColgramSpamGuard.NormalizedResult fancy = ColgramSpamGuard.normalizeText("𝐒𝐞𝐥𝐥 𝐚𝐜𝐜𝐨𝐮𝐧𝐭𝐬");
        check(fancy.flags.contains("fancy_font"), "fancy_font flag expected");

        ColgramSpamGuard.NormalizedResult spaced = ColgramSpamGuard.normalizeText("к у п л ю  т о в а р");
        check(spaced.flags.contains("spaced_letters"), "spaced_letters flag expected");

        ColgramSpamGuard.NormalizedResult homoglyph = ColgramSpamGuard.normalizeText("пpoдам дeшeвo aккayнты");
        check(homoglyph.flags.contains("mixed_script"), "mixed_script flag expected for Latin homoglyphs");

        check(ColgramSpamGuard.hasInvisibleGhostChars("Привет\u200Bвсем"), "zero-width ghost expected");
        check(!ColgramSpamGuard.hasInvisibleGhostChars("Обычное сообщение"), "clean text has no ghost chars");

        List<ColgramSpamGuard.MentionTarget> stealthTargets = Collections.singletonList(
                new ColgramSpamGuard.MentionTarget(777L, "", 6, 1)
        );
        String stealthMsg = "Привет\u200Bчат! Заходите в канал";
        check(ColgramSpamGuard.hasHiddenOrMassMentionEntity(stealthMsg, stealthTargets),
                "zero-width mention target must trigger hidden mention detection");
        String kind = ColgramSpamGuard.classifyEntityPing(
                stealthMsg, stealthTargets, 777L, Collections.singletonList("myuser"));
        check(ColgramSpamGuard.PING_STEALTH.equals(kind), "expected PING_STEALTH, got " + kind);

        List<ColgramSpamGuard.MentionTarget> normalTargets = Collections.singletonList(
                new ColgramSpamGuard.MentionTarget(777L, "", 7, 7)
        );
        String normalKind = ColgramSpamGuard.classifyEntityPing(
                "Привет @myuser!", normalTargets, 777L, Arrays.asList("myuser", "Иван"));
        check(ColgramSpamGuard.PING_USERNAME.equals(normalKind), "expected PING_USERNAME, got " + normalKind);

        List<ColgramSpamGuard.MentionTarget> nameTargets = Collections.singletonList(
                new ColgramSpamGuard.MentionTarget(777L, "", 7, 4)
        );
        String nameKind = ColgramSpamGuard.classifyEntityPing(
                "Привет Иван!", nameTargets, 777L, Arrays.asList("myuser", "Иван"));
        check(ColgramSpamGuard.PING_NAME.equals(nameKind), "expected PING_NAME, got " + nameKind);
    }

    private static void testSpamGuardDiagnosisAndLeadVeto() {
        String adText = "🔥 ПРОДАМ АККАУНТЫ / БАЗЫ / ПРАЙС 1500₽\n📩 Писать в ЛС @shop_manager\nhttps://t.me/+AbCdEfGhIjK";
        ColgramSpamGuard.SpamReport adReport = ColgramSpamGuard.diagnose(
                adText, "", ColgramSpamGuard.PING_STEALTH, true, false, false, true,
                true, Collections.singletonList("myname"));
        check(adReport.isSpam, "commercial ad with foreign handle and stealth ping should be spam, score=" + adReport.score);
        check(adReport.score >= ColgramSpamGuard.SPAM_THRESHOLD, "score must meet threshold");
        check(adReport.shouldAutoRead, "spam ping should mark shouldAutoRead");

        String buyerText = "Сколько стоит реклама в канале? Куплю у вас 2 поста @myname";
        check(ColgramSpamGuard.isGenuineBuyerQuestion(buyerText, Collections.singletonList("myname")),
                "buyer inquiry mentioning own handle must be recognized as buyer question");
        ColgramSpamGuard.SpamReport leadReport = ColgramSpamGuard.diagnose(
                buyerText, "", ColgramSpamGuard.PING_USERNAME, true, false, false, false,
                true, Collections.singletonList("myname"));
        check(!leadReport.isSpam, "genuine buyer lead must not be flagged as spam, score=" + leadReport.score);
        check(leadReport.isLead, "genuine buyer lead should have isLead=true");

        ColgramSpamGuard.reset(9991L);
        int v = ColgramSpamGuard.judge(-100123L, 9991L, adText, false, true);
        check(v != ColgramSpamGuard.VERDICT_OK, "judge should flag adText from non-contact");
        int contactSafe = ColgramSpamGuard.judge(-100123L, 9991L, adText, true, true);
        check(contactSafe == ColgramSpamGuard.VERDICT_OK, "contacts must bypass judge");
    }

    private static void testNotificationFilter() {
        check(ColgramNotificationFilter.supportsPackage("org.colgram.messenger.web"), "colgram.web supported");
        check(!ColgramNotificationFilter.supportsPackage("com.unknown.app"), "unknown pkg unsupported");

        String ad1 = "Продажа аккаунтов прайс 999р\nПишите в ЛС @seller_one или https://t.me/+XyZ123";
        String ad2 = "Продам базы, прайс 500 руб\nПисать в лс @promo_desk";
        String cleanMsg = "Привет, во сколько завтра созвон?";
        String authCode = "Код подтверждения: 849201. Не сообщайте никому.";

        check(ColgramNotificationFilter.isAd(ad1), "ad1 must be classified as ad");
        check(!ColgramNotificationFilter.isAd(cleanMsg), "cleanMsg must not be ad");
        check(!ColgramNotificationFilter.isAd(authCode), "authCode must not be ad");

        check(ColgramNotificationFilter.shouldDismiss(
                "org.colgram.messenger.web", true, false, false, true, new String[]{ad1, ad2}),
                "all-ad group bundle should dismiss");
        check(!ColgramNotificationFilter.shouldDismiss(
                "org.colgram.messenger.web", true, false, false, true, new String[]{ad1, cleanMsg}),
                "mixed bundle containing normal message must NOT dismiss");
        check(!ColgramNotificationFilter.shouldDismiss(
                "org.colgram.messenger.web", true, false, false, true, new String[]{authCode}),
                "auth code must never dismiss");
        check(!ColgramNotificationFilter.shouldDismiss(
                "org.colgram.messenger.web", true, false, false, false, new String[]{ad1}),
                "1v1/non-group notification must not dismiss");
    }

    private static void testColemailerEngineButtonsScheduleAndRotation() {
        String rawPost = "Заголовок поста\nПодробности акции\n\n[Сайт](https://example.com) || [Купить](cb:buy_1)\n[Канал](tg://resolve?domain=durov)";
        ColgramColemailerEngine.ParsedPost parsed = ColgramColemailerEngine.extractButtonsFromPost(rawPost);
        check("Заголовок поста\nПодробности акции".equals(parsed.body), "unexpected body: [" + parsed.body + "]");
        check(parsed.buttons.size() == 2, "expected 2 button rows");
        check(parsed.buttons.get(0).size() == 2, "expected 2 buttons in row 0");
        check("callback".equals(parsed.buttons.get(0).get(1).type), "expected callback button");
        check("buy_1".equals(parsed.buttons.get(0).get(1).value), "expected buy_1 callback data");

        String markup = ColgramColemailerEngine.toBotApiReplyMarkupJson(parsed.buttons);
        check(markup.contains("inline_keyboard"), "markup missing inline_keyboard");
        check(markup.contains("\"callback_data\":\"buy_1\""), "markup missing callback_data");
        check(markup.contains("\"url\":\"https://example.com\""), "markup missing url");

        ColgramColemailerEngine.ScheduleSpec sInt = ColgramColemailerEngine.parseSchedule("15 мин");
        check(sInt.mode == ColgramColemailerEngine.MODE_INTERVAL && sInt.intervalSec == 900L, "15 min schedule mismatch");

        ColgramColemailerEngine.ScheduleSpec sCnt = ColgramColemailerEngine.parseSchedule("20 сообщений");
        check(sCnt.mode == ColgramColemailerEngine.MODE_COUNTER && sCnt.counterThreshold == 20, "counter schedule mismatch");

        ColgramColemailerEngine.ScheduleSpec sHyb = ColgramColemailerEngine.parseSchedule("1 час 10 сообщений");
        check(sHyb.mode == ColgramColemailerEngine.MODE_HYBRID
                && sHyb.intervalSec == 3600L && sHyb.counterThreshold == 10, "hybrid schedule mismatch");

        ColgramColemailerEngine engine = ColgramColemailerEngine.newStandaloneForTest();
        ColgramColemailerEngine.BroadcastCampaign c1 = new ColgramColemailerEngine.BroadcastCampaign(
                "camp_a", "Пост А", ColgramColemailerEngine.MODE_HYBRID, 120L, 3, "Ads");
        c1.buttons.addAll(parsed.buttons);
        Set<Long> folderChats = new LinkedHashSet<>(Arrays.asList(-1001L, -1002L));
        c1.targetChats.addAll(folderChats);
        engine.addOrUpdateCampaign(c1);

        // First tick arms baseline chatLastSentAt without dumping an immediate catch-up burst.
        check(engine.collectDueBroadcasts(999L).isEmpty(), "initial tick must only arm baseline");

        // Stale counter scenario: 2 messages at t=1000, then stale window expires at t=3000.
        engine.onIncomingChatMessage(-1001L, 1000L);
        engine.onIncomingChatMessage(-1001L, 1005L);
        check(c1.chatCounters.get(-1001L) == 2, "expected counter=2 before stale reset");

        // After stale window (>=1800s), new activity resets old counter to 1 instead of firing on 3rd.
        engine.onIncomingChatMessage(-1001L, 3000L);
        check(c1.chatCounters.get(-1001L) == 1, "stale hybrid counter should reset to 1, got " + c1.chatCounters.get(-1001L));

        engine.onIncomingChatMessage(-1001L, 3010L);
        engine.onIncomingChatMessage(-1001L, 3020L);
        List<ColgramColemailerEngine.BroadcastDueEvent> due = engine.collectDueBroadcasts(3021L);
        check(due.size() == 1 && due.get(0).chatId == -1001L,
                "expected due broadcast only for -1001 once threshold and interval satisfied, got " + due.size());
        engine.markBroadcastSent(c1, -1001L, 3021L);
        check(c1.chatCounters.getOrDefault(-1001L, 0) == 0, "counter must reset to 0 after send");

        ColgramColemailerEngine.BroadcastCampaign c2 = new ColgramColemailerEngine.BroadcastCampaign(
                "camp_b", "Пост Б", ColgramColemailerEngine.MODE_INTERVAL, 600L, 0, "");
        engine.addOrUpdateCampaign(c2);
        engine.setRotation("rot1", Arrays.asList("camp_a", "camp_b"));
        check("camp_a".equals(engine.nextRotationCampaign("rot1", -1001L).name), "rotation step 0 -> camp_a");
        check("camp_b".equals(engine.nextRotationCampaign("rot1", -1001L).name), "rotation step 1 -> camp_b");
        check("camp_a".equals(engine.nextRotationCampaign("rot1", -1001L).name), "rotation step 2 wraps -> camp_a");
    }

    private static void testWhitelistMuteQuietSafetyAndSerialization() {
        ColgramColemailerEngine engine = ColgramColemailerEngine.newStandaloneForTest();
        ColgramColemailerEngine.BroadcastCampaign camp = new ColgramColemailerEngine.BroadcastCampaign(
                "vip", "Текст\tс\nпереносом", ColgramColemailerEngine.MODE_INTERVAL, 300L, 0, "VIP");
        camp.targetChats.add(-100999L);
        camp.buttons.addAll(ColgramColemailerEngine.parseButtonLayout("[Открыть](https://colgram.org) || [Инфо](cb:info)"));
        engine.addOrUpdateCampaign(camp);
        engine.setRotation("main", Collections.singletonList("vip"));

        engine.addWhitelist(111L);
        engine.setMuteRule(111L, 0L, false, ColgramColemailerEngine.MUTE_BAN);
        check(engine.getMuteActionFor(111L, -100999L) == null, "whitelist must override mute rules");

        engine.setMuteRule(222L, 0L, false, ColgramColemailerEngine.MUTE_SILENT);
        engine.setMuteRule(222L, -100999L, false, ColgramColemailerEngine.MUTE_DELETE);
        check(ColgramColemailerEngine.MUTE_DELETE.equals(engine.getMuteActionFor(222L, -100999L)),
                "chat-scoped mute must take priority over global mute");
        check(ColgramColemailerEngine.MUTE_SILENT.equals(engine.getMuteActionFor(222L, -100555L)),
                "global mute applies outside scoped chat");

        engine.setMuteRule(0L, -100888L, true, ColgramColemailerEngine.MUTE_WARN);
        check(ColgramColemailerEngine.MUTE_WARN.equals(engine.getMuteActionFor(333L, -100888L)),
                "whole-chat mute applies to arbitrary sender");

        engine.saveQuietSnapshot(new ColgramColemailerEngine.QuietSnapshot(
                -100999L, 1, 0, false, true, "Ping", "/sys/ping.ogg", 42L));

        String serialized = engine.serializeState();
        ColgramColemailerEngine restored = ColgramColemailerEngine.newStandaloneForTest();
        restored.loadFromSerializedState(serialized);

        ColgramColemailerEngine.BroadcastCampaign rCamp = restored.getCampaign("vip");
        check(rCamp != null && "Текст\tс\nпереносом".equals(rCamp.text), "escaped TSV text must round-trip losslessly");
        check(rCamp.buttons.size() == 1 && rCamp.buttons.get(0).size() == 2, "buttons must round-trip");
        check(restored.isWhitelisted(111L), "whitelist must round-trip");
        check(ColgramColemailerEngine.MUTE_DELETE.equals(restored.getMuteActionFor(222L, -100999L)), "mute rules must round-trip");
        ColgramColemailerEngine.QuietSnapshot snap = restored.getQuietSnapshot(-100999L);
        check(snap != null && snap.prevSoundDocId == 42L && "Ping".equals(snap.prevSoundTitle),
                "quiet snapshot must round-trip");

        check(ColgramColemailerEngine.isGatekeeperText("Для отправки сообщений подпишитесь на канал"),
                "gatekeeper text detection failed");
        check(ColgramColemailerEngine.isSubscribeButton("✅ Подписаться на канал"), "subscribe button detection");
        check(ColgramColemailerEngine.isVerifyButton("Проверить подписку"), "verify button detection");
        check(ColgramColemailerEngine.isUnsafeButton("Подтвердить удаление", "https://t.me/bot?start=ref123", ""),
                "unsafe start= button must be rejected");
        check(!ColgramColemailerEngine.isUnsafeButton("Проверить", "https://t.me/my_public_channel", "check_sub"),
                "safe channel/check callback must pass");

        List<String> links = ColgramColemailerEngine.extractChannelLinks(
                "Подпишись на https://t.me/colgram_news и https://t.me/+PrivInvite99.");
        check(links.size() == 2, "expected 2 extracted channel links, got " + links);
    }

    public static void main(String[] args) {
        testSpamGuardNormalizationAndPingDetection();
        testSpamGuardDiagnosisAndLeadVeto();
        testNotificationFilter();
        testColemailerEngineButtonsScheduleAndRotation();
        testWhitelistMuteQuietSafetyAndSerialization();
        System.out.println("ColgramColemailerTest: PASS");
    }
}
