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

    private static void testEventDrivenSchedulerZeroIdleAndFloodWait() {
        ColgramColemailerEngine engine = ColgramColemailerEngine.newStandaloneForTest();

        // Zero campaigns -> activeCampaignCount == 0 and getNextDueCampaignDelayMs returns -1 (zero wakeups!)
        check(engine.getActiveCampaignCount() == 0 && !engine.hasActiveCampaigns(), "idle engine must have 0 active campaigns");
        check(engine.getNextDueCampaignDelayMs(1_000_000L) == -1L,
                "idle engine must return -1L delay (zero wakeups)");

        // Paused campaign -> still -1L
        ColgramColemailerEngine.BroadcastCampaign intervalCamp = new ColgramColemailerEngine.BroadcastCampaign(
                "zero_drain", "Пост", ColgramColemailerEngine.MODE_INTERVAL, 120L, 0, "");
        intervalCamp.ownerAccount = 0;
        intervalCamp.enabled = false;
        intervalCamp.targetChats.add(-10010L);
        intervalCamp.targetChats.add(-10020L);
        engine.addOrUpdateCampaign(intervalCamp);
        check(engine.getActiveCampaignCount() == 0, "disabled campaign must not count as active");
        check(engine.getNextDueCampaignDelayMs(0, 1_000_000L) == -1L,
                "disabled campaign must return -1L delay");

        // Enable campaign & arm baselines at t=1000s
        engine.setCampaignEnabled("zero_drain", true);
        check(engine.getActiveCampaignCount() == 1, "enabled campaign must count as active");
        engine.armMissingBaselines(0, 1000L);
        check(engine.getNextDueCampaignDelayMs(0, 1_000_000L) == 120_000L,
                "exact remaining delay at t=1000s must be 120_000ms, got " + engine.getNextDueCampaignDelayMs(0, 1_000_000L));
        check(engine.getNextDueCampaignDelayMs(0, 1_050_000L) == 70_000L,
                "exact remaining delay at t=1050s must be 70_000ms");
        check(engine.getNextDueCampaignDelayMs(0, 1_125_000L) == 0L,
                "overdue campaign must return 0L");

        // Per-(account, dialogId) FLOOD_WAIT_X backoff isolates only the rate-limited chat
        check(ColgramColemailerEngine.parseFloodWaitSeconds("FLOOD_WAIT_45") == 45L, "parse FLOOD_WAIT_45");
        check(ColgramColemailerEngine.parseFloodWaitSeconds("{\"ok\":false,\"parameters\":{\"retry_after\":33}}") == 33L,
                "parse retry_after JSON");
        engine.recordFloodWait(0, -10010L, 45L, 1_125_000L);
        check(engine.isFloodBackedOff(0, -10010L, 1_125_000L), "-10010 must be in flood backoff");
        check(!engine.isFloodBackedOff(0, -10020L, 1_125_000L), "-10020 must NOT be blocked by -10010's flood wait");

        List<ColgramColemailerEngine.BroadcastDueEvent> due = engine.collectDueBroadcasts(0, 1125L);
        check(due.size() == 1 && due.get(0).chatId == -10020L,
                "collectDueBroadcasts must skip flood-backed-off -10010 and still emit -10020");
        engine.markBroadcastSent(intervalCamp, -10020L, 1125L);
        // Next wakeup is now exact flood backoff expiry of -10010 (45_000ms), NOT 15s polling!
        check(engine.getNextDueCampaignDelayMs(0, 1_125_000L) == 45_000L,
                "next wakeup must equal exact flood backoff expiry 45_000ms, got " + engine.getNextDueCampaignDelayMs(0, 1_125_000L));

        // Pure counter campaign: -1L while below threshold, 0L once incoming messages hit threshold
        ColgramColemailerEngine cntEngine = ColgramColemailerEngine.newStandaloneForTest();
        ColgramColemailerEngine.BroadcastCampaign cntCamp = new ColgramColemailerEngine.BroadcastCampaign(
                "cnt_only", "Счётчик", ColgramColemailerEngine.MODE_COUNTER, 0L, 2, "");
        cntCamp.ownerAccount = 0;
        cntCamp.targetChats.add(-10099L);
        cntEngine.addOrUpdateCampaign(cntCamp);
        cntEngine.armMissingBaselines(0, 2000L);
        check(cntEngine.getNextDueCampaignDelayMs(0, 2_000_000L) == -1L,
                "untriggered counter campaign needs zero timer wakeups (-1L)");
        check(!cntEngine.onIncomingChatMessageAndCheckDue(0, -10099L, 2001L), "1/2 messages should not trigger yet");
        check(cntEngine.getNextDueCampaignDelayMs(0, 2_001_000L) == -1L, "still below threshold -> -1L");
        check(cntEngine.onIncomingChatMessageAndCheckDue(0, -10099L, 2002L), "2/2 messages must trigger");
        check(cntEngine.getNextDueCampaignDelayMs(0, 2_002_000L) == 0L, "triggered counter campaign -> 0L");
    }

    private static void testUpgradedAdSpamHeuristicsAndZeroFalsePositives() {
        String[] ads = {
                "erid: 2Vtzqwp1234 Скидка 30% на доставку сегодня",
                "#реклама Новый сервис доставки по всему городу",
                "Криптосигналы VIP! Заработок в день от 500$ без опыта",
                "Казино 1win, промокод VIP2026 — забирай выигрыш сразу!",
                "Розыгрыш призов! Подписывайся и жми сюда https://t.me/+SecretPromo99",
                "Ставки на спорт с гарантией, промокод WIN100, переходи https://t.me/+BetChannel"
        };
        for (String ad : ads) {
            ColgramSpamGuard.SpamReport r = ColgramSpamGuard.diagnose(ad);
            check(r.isSpam, "ad must be detected as spam: [" + ad + "] score=" + r.score + " factors=" + r.factors);
        }

        // Explicit ad disclosures set isExplicitAd & shouldAutoRead even without pings
        ColgramSpamGuard.SpamReport eridRep = ColgramSpamGuard.diagnose("erid: 2Vtzqwp9999 Акция недели");
        check(eridRep.isExplicitAd && eridRep.shouldAutoRead, "erid disclosure must set isExplicitAd and shouldAutoRead");
        check(ColgramNotificationFilter.isAd("erid: 2Vtzqwp9999 Акция недели"), "notification filter must catch erid");
        check(ColgramNotificationFilter.isAd("#реклама Лучший VPN сервис"), "notification filter must catch #реклама");

        // Suspicious inline URL buttons + forwarded channel ad from non-contact
        ColgramSpamGuard.SpamReport btnFwd = ColgramSpamGuard.diagnose(
                "Скидка только сегодня! Жми по кнопке ниже", "", null, false, false, false, false,
                false, Collections.emptyList(), true, true);
        check(btnFwd.isSpam, "inline URL buttons + channel forward promo must be flagged as spam, score=" + btnFwd.score);

        // Zero false positives on normal friend conversations & auth codes
        String[] normalMessages = {
                "Привет, как дела? Пойдём вечером в кино?",
                "Скинь пожалуйста конспект по физике за вчера",
                "Я буду через 15 минут возле метро",
                "Ты уже посмотрел новую серию?",
                "Надоела эта реклама на ютубе уже",
                "У нас на работе сегодня дали премию!",
                "Код подтверждения: 482910. Не сообщайте его никому."
        };
        for (String okMsg : normalMessages) {
            ColgramSpamGuard.SpamReport r = ColgramSpamGuard.diagnose(okMsg);
            check(!r.isSpam && !r.shouldAutoRead,
                    "normal chat message must NEVER false-positive: [" + okMsg + "] score=" + r.score + " factors=" + r.factors);
        }
    }

    private static void testDmAutoDeleteMuteSafetyAndIsolation() {
        ColgramColemailerEngine engine = ColgramColemailerEngine.newStandaloneForTest();
        long selfAcc0 = 55555L;
        long selfAcc1 = 66666L;
        engine.setSelfUserId(0, selfAcc0);
        engine.setSelfUserId(1, selfAcc1);

        // Strict refusal of Saved Messages, Secret Chats, Folders, groups/channels, 0, and service IDs (777000, 42777, etc.)
        long[] forbidden = {
                0L,
                selfAcc0,                  // Saved Messages on account 0
                777000L,                   // Telegram Service Notifications
                42777L,                    // Telegram Support / Login codes
                333000L,                   // Telegram Support
                4240004L,                  // Telegram Anti-Spam
                136817688L,                // Channel_Bot
                1087968824L,               // GroupAnonymousBot
                2666000L,                  // Anonymous Saved Messages pseudo-peer
                0x4000000000001234L,       // Secret Chat
                0x2000000000000001L,       // Folder dialog
                -100123456789L             // Supergroup / Channel
        };
        for (long badId : forbidden) {
            check(!engine.setDmAutoDeleteMuted(0, badId, true),
                    "setDmAutoDeleteMuted MUST refuse disallowed dialogId=" + badId);
            check(!engine.toggleDmAutoDeleteMuted(0, badId),
                    "toggleDmAutoDeleteMuted MUST refuse disallowed dialogId=" + badId);
            check(!engine.isDmAutoDeleteMuted(0, badId),
                    "isDmAutoDeleteMuted MUST remain false for disallowed dialogId=" + badId);
        }

        // Real 1-on-1 DM toggle & per-account isolation
        long peerA = 123456L;
        long peerB = 789012L;
        check(!engine.isDmAutoDeleteMuted(0, peerA), "initially unmuted");
        check(engine.toggleDmAutoDeleteMuted(0, peerA), "first toggle enables DM mute");
        check(engine.isDmAutoDeleteMuted(0, peerA), "account 0 peerA must be muted");
        check(!engine.isDmAutoDeleteMuted(1, peerA), "account 1 peerA must stay isolated (unmuted)");

        check(engine.setDmAutoDeleteMuted(1, peerB, true), "account 1 peerB mute enabled");
        Set<Long> acc0Set = engine.getDmAutoDeleteMutedDialogs(0);
        Set<Long> acc1Set = engine.getDmAutoDeleteMutedDialogs(1);
        check(acc0Set.size() == 1 && acc0Set.contains(peerA), "account 0 set mismatch: " + acc0Set);
        check(acc1Set.size() == 1 && acc1Set.contains(peerB), "account 1 set mismatch: " + acc1Set);

        // Serialization round-trip preserves per-account DM auto-delete mutes
        String ser = engine.serializeState();
        ColgramColemailerEngine restored = ColgramColemailerEngine.newStandaloneForTest();
        restored.setSelfUserId(0, selfAcc0);
        restored.setSelfUserId(1, selfAcc1);
        restored.loadFromSerializedState(ser);
        check(restored.isDmAutoDeleteMuted(0, peerA), "restored acc 0 peerA must stay muted");
        check(!restored.isDmAutoDeleteMuted(1, peerA), "restored acc 1 peerA must stay unmuted");
        check(restored.isDmAutoDeleteMuted(1, peerB), "restored acc 1 peerB must stay muted");
        check(!restored.toggleDmAutoDeleteMuted(0, peerA), "second toggle disables DM mute");
        check(!restored.isDmAutoDeleteMuted(0, peerA), "peerA must now be unmuted");
    }

    private static void testTrack2AntiPingStrangersMassMentionsAndStateV2() {
        // 1. Stealth entity ping detection: weak substring "own.contains(label)" MUST NOT bypass!
        long selfId = 9001L;
        List<String> own = java.util.Arrays.asList("Alexander", "alex_dev");
        String msgStealthSubstr = "Перейдите сюда lex сегодня!";
        List<ColgramSpamGuard.MentionTarget> stealthSubTargets = java.util.Collections.singletonList(
                new ColgramSpamGuard.MentionTarget(selfId, "", msgStealthSubstr.indexOf("lex"), 3));
        check(ColgramSpamGuard.PING_STEALTH.equals(
                ColgramSpamGuard.classifyEntityPing(msgStealthSubstr, stealthSubTargets, selfId, own)),
                "Weak substring inside Alexander must be caught as PING_STEALTH");

        String msgPrefixOk = "Привет Алекс, зайдёшь на созвон?";
        List<ColgramSpamGuard.MentionTarget> prefixTargets = java.util.Collections.singletonList(
                new ColgramSpamGuard.MentionTarget(selfId, "", msgPrefixOk.indexOf("Алекс"), 5));
        // Exact/prefix token against Cyrillic Alexander/Алекс
        check(ColgramSpamGuard.PING_NAME.equals(
                ColgramSpamGuard.classifyEntityPing(
                        "Привет Алекс!",
                        java.util.Collections.singletonList(new ColgramSpamGuard.MentionTarget(selfId, "", 7, 5)),
                        selfId,
                        java.util.Arrays.asList("Александр", "alex_dev"))),
                "Token prefix Алекс -> Александр must remain legitimate PING_NAME");

        // 2. Mass mentions: >=3 totalMentions OR @all/@everyone/@все
        List<ColgramSpamGuard.MentionTarget> threeMentions = java.util.Arrays.asList(
                new ColgramSpamGuard.MentionTarget(0L, "", 0, 4),
                new ColgramSpamGuard.MentionTarget(0L, "", 5, 4),
                new ColgramSpamGuard.MentionTarget(0L, "", 10, 4));
        check(ColgramSpamGuard.hasHiddenOrMassMentionEntity("@aaa @bbb @ccc привет", threeMentions),
                "totalMentions == 3 must trigger hasHiddenOrMassMentionEntity");
        check(ColgramSpamGuard.hasHiddenOrMassMentionEntity("Внимание @all @everyone заходите", Collections.emptyList()),
                "@all / @everyone tags must trigger hasHiddenOrMassMentionEntity");

        // 3. Layer (c) commercial Steam/topup >220 char ad wall + NotificationFilter handleless ad wall
        String longSteamAd = "Пополнение баланса Steam с минимальной комиссией! Всегда на связи, открыт к заказам "
                + "24/7. Также в продаже гифты CS2, Valorant, PSN и Xbox подписки. Полная гарантия и быстрая выдача "
                + "баланса на любой регион по самым выгодным ценам на рынке, обращайтесь для оформления заказа сейчас!";
        ColgramSpamGuard.SpamReport wallRep = ColgramSpamGuard.diagnose(
                longSteamAd, "", ColgramSpamGuard.PING_USERNAME, true, false, false, false,
                false, own, false, false, 4, true);
        check(wallRep.isSpam && wallRep.shouldAutoRead,
                "Long >220 commercial Steam wall w/ custom emoji + URL entity must be blocked, score=" + wallRep.score);
        check(ColgramNotificationFilter.isAd(longSteamAd),
                "NotificationFilter.isAd must catch >220-char handleless commercial ad wall");

        // 4. Layer (d) big-group unsolicited stranger ping + 24h outgoing interaction LRU
        ColgramSpamGuard.resetInteractionsForTest();
        long stranger = 888777L;
        long now = System.currentTimeMillis();
        check(ColgramSpamGuard.isUnsolicitedStrangerBigGroupPing(
                stranger, 150, false, false, false, true, now),
                "Stranger ping in >=50 group must be blocked when no prior interaction or genuine reply");
        check(!ColgramSpamGuard.isUnsolicitedStrangerBigGroupPing(
                stranger, 150, false, false, true, true, now),
                "Genuine reply to self in big group must NOT be blocked as stranger ping");
        check(!ColgramSpamGuard.isUnsolicitedStrangerBigGroupPing(
                stranger, 20, false, false, false, true, now),
                "Small group <50 members must NOT trigger big-group stranger block");
        ColgramSpamGuard.recordOutgoingInteraction(stranger);
        check(!ColgramSpamGuard.isUnsolicitedStrangerBigGroupPing(
                stranger, 150, false, false, false, true, now + 1000L),
                "User interacted within 24h must be unblocked for pings");

        // 5. Engine activeCounterCampaignCount reset + TS/CNT persistence v2 + rotations
        ColgramColemailerEngine eng = ColgramColemailerEngine.newStandaloneForTest();
        ColgramColemailerEngine.BroadcastCampaign emptyCounter = new ColgramColemailerEngine.BroadcastCampaign(
                "empty_cnt", "", ColgramColemailerEngine.MODE_COUNTER, 0L, 5, "");
        eng.addOrUpdateCampaign(emptyCounter);
        check(eng.getActiveCounterCampaignCount() == 0, "Empty-text counter campaign must not inflate activeCounterCampaignCount");

        ColgramColemailerEngine.BroadcastCampaign c1 = new ColgramColemailerEngine.BroadcastCampaign(
                "promo_a", "Пост A", ColgramColemailerEngine.MODE_HYBRID, 600L, 3, "");
        c1.targetChats.add(-10099L);
        eng.addOrUpdateCampaign(c1);
        eng.markBroadcastSent(c1, -10099L, 1_700_000_000L);
        eng.onIncomingChatMessageAndCheckDue(-1, -10099L, 1_700_000_100L);
        String v2State = eng.serializeState();

        ColgramColemailerEngine restored = ColgramColemailerEngine.newStandaloneForTest();
        restored.loadFromSerializedState(v2State);
        ColgramColemailerEngine.BroadcastCampaign rc1 = restored.getCampaign("promo_a");
        check(rc1 != null && rc1.chatLastSentAt.get(-10099L) == 1_700_000_000L,
                "v2 serialization must persist chatLastSentAt TS");
        check(rc1.chatCounters.get(-10099L) == 1,
                "v2 serialization must persist chatCounters CNT");
        restored.loadFromSerializedState("");
        check(restored.getActiveCounterCampaignCount() == 0,
                "loadFromSerializedState empty must reset activeCounterCampaignCount to 0");
    }

    public static void main(String[] args) {
        testSpamGuardNormalizationAndPingDetection();
        testSpamGuardDiagnosisAndLeadVeto();
        testNotificationFilter();
        testColemailerEngineButtonsScheduleAndRotation();
        testWhitelistMuteQuietSafetyAndSerialization();
        testEventDrivenSchedulerZeroIdleAndFloodWait();
        testUpgradedAdSpamHeuristicsAndZeroFalsePositives();
        testDmAutoDeleteMuteSafetyAndIsolation();
        testTrack2AntiPingStrangersMassMentionsAndStateV2();
        System.out.println("ColgramColemailerTest: PASS");
    }
}
