package org.colgram.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Regression tests for the Saved Messages ("Избранное") & cross-chat / cross-account data-leak fixes:
 * - Opt-in default for Colemailer lead forwarding to Saved Messages
 * - Honoring `isLeadCheckAllowed` in ColgramSpamGuard.diagnose()
 * - Fail-closed Saved Messages, Secret Chat, Folder, and Telegram service dialog guards
 * - Correct negative-ID handling (sign bit must never trigger encrypted/folder mask)
 * - Account isolation in ColgramColemailerEngine campaigns & folder sync
 * - Blocking zero/invalid targets in ColgramPythonEngine.isValidSendTarget and anti-delete tombstones
 */
public final class ColgramSavedLeakRegressionTest {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("SavedLeakRegression failed: " + msg);
        }
    }

    private static void testSaveLeadsDefaultOffAndLeadGate() {
        check(!ColgramConfig.isSaveLeadsEnabled(),
                "isSaveLeadsEnabled() MUST default to false so chats never auto-forward into Saved Messages");

        String buyerMsg = "Сколько стоит реклама в канале? Куплю 2 поста @myname";
        ColgramSpamGuard.SpamReport disallowed = ColgramSpamGuard.diagnose(
                buyerMsg, "", ColgramSpamGuard.PING_USERNAME, true, false, false, false,
                false, Collections.singletonList("myname"));
        check(!disallowed.isLead,
                "diagnose(..., isLeadCheckAllowed=false) MUST NOT set isLead=true");

        ColgramSpamGuard.SpamReport allowed = ColgramSpamGuard.diagnose(
                buyerMsg, "", ColgramSpamGuard.PING_USERNAME, true, false, false, false,
                true, Collections.singletonList("myname"));
        check(allowed.isLead && !allowed.isSpam,
                "diagnose(..., isLeadCheckAllowed=true) still detects genuine buyer lead");

        String adText = "🔥 ПРОДАМ АККАУНТЫ / БАЗЫ / ПРАЙС 1500₽\n📩 Писать в ЛС @shop_manager\nhttps://t.me/+AbCdEfGhIjK";
        check(ColgramSpamGuard.judge(0L, 9991L, adText, false, true) == ColgramSpamGuard.VERDICT_OK,
                "judge must ignore dialogId=0");
        check(ColgramSpamGuard.judge(0x4000000000000123L, 9991L, adText, false, false) == ColgramSpamGuard.VERDICT_OK,
                "judge must ignore encrypted secret chat dialogs");
        check(ColgramSpamGuard.judge(-100123L, 777000L, adText, false, true) == ColgramSpamGuard.VERDICT_OK,
                "judge must ignore Telegram service notifications sender 777000");
    }

    private static void testAutoReplyAndDialogBitmaskIsolation() {
        long secretDid = 0x4000000000001234L;
        long folderDid = 0x2000000000000001L;

        check(ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(secretDid), "secret chat high bit detected");
        check(ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(folderDid), "folder dialog high bit detected");
        check(!ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(-100123456L),
                "negative supergroup ID must NOT match encrypted/folder mask");
        check(!ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(-4500000000L),
                "negative modern basic group ID must NOT match encrypted/folder mask");
        check(!ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(-1L),
                "negative -1 must NOT match encrypted/folder mask");
        check(!ColgramAutoReplyPolicy.isEncryptedOrFolderDialog(123456789L),
                "normal positive user ID must NOT match encrypted/folder mask");

        // Fail closed when selfUserId is unresolved (0 or negative)
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 0L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                "shouldReply must fail closed when selfUserId == 0");
        check(!ColgramAutoReplyPolicy.shouldReply(
                true, 200L, -1L, false, false, false, false, true, true,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                "shouldReply must fail closed when selfUserId < 0");

        // Saved Messages, Secret Chats, Folders, and official Telegram service peers blocked
        for (long blocked : new long[]{0L, 100L, secretDid, folderDid, 777000L, 42777L, 333000L, 136817688L, 1087968824L, 2666000L}) {
            check(!ColgramAutoReplyPolicy.shouldReply(
                    true, blocked, 100L, false, false, true, false, true, true,
                    ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                    "shouldReply must block protected/self dialog " + blocked);
        }

        // Valid normal user & valid group still work
        check(ColgramAutoReplyPolicy.shouldReply(
                true, 200L, 100L, false, false, false, false, false, true,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                "shouldReply allows normal user dialog");
        check(ColgramAutoReplyPolicy.shouldReply(
                true, -100123456L, 100L, false, false, false, false, true, false,
                ColgramAutoReplyPolicy.AUDIENCE_ALL, "", 10_000L, 0L, 60),
                "shouldReply allows normal negative group ID when allowGroups=true");
    }

    private static void testPythonEngineSendTargetGuard() {
        check(!ColgramAutoReplyPolicy.isValidSendTarget(0, 0L, "hello"),
                "dialogId=0 must be rejected by sendMessage guard");
        check(!ColgramAutoReplyPolicy.isValidSendTarget(-1, 12345L, "hello"),
                "negative account must be rejected");
        check(!ColgramAutoReplyPolicy.isValidSendTarget(5, 12345L, "hello"),
                "out-of-range account >= 5 must be rejected");
        check(!ColgramAutoReplyPolicy.isValidSendTarget(0, 12345L, "   "),
                "blank message must be rejected");
        check(!ColgramAutoReplyPolicy.isValidSendTarget(0, 0x4000000000000001L, "hello"),
                "encrypted dialog must be rejected");
        check(!ColgramAutoReplyPolicy.isValidSendTarget(0, 0x2000000000000001L, "hello"),
                "folder dialog must be rejected");
        check(!ColgramAutoReplyPolicy.isValidSendTarget(0, 777000L, "hello"),
                "Telegram service chat 777000 must be rejected");

        check(ColgramAutoReplyPolicy.isValidSendTarget(0, 12345L, "hello"),
                "valid user dialog accepted");
        check(ColgramAutoReplyPolicy.isValidSendTarget(2, -100987654321L, "hello"),
                "valid negative supergroup dialog accepted on account 2");
    }

    private static void testColemailerCampaignAccountIsolation() {
        ColgramColemailerEngine engine = ColgramColemailerEngine.newStandaloneForTest();
        ColgramColemailerEngine.BroadcastCampaign acc0 = new ColgramColemailerEngine.BroadcastCampaign(
                "camp_acc0", "Post 0", ColgramColemailerEngine.MODE_INTERVAL, 120L, 0, "Promo");
        acc0.ownerAccount = 0;
        acc0.targetChats.add(-100111L);

        ColgramColemailerEngine.BroadcastCampaign acc1 = new ColgramColemailerEngine.BroadcastCampaign(
                "camp_acc1", "Post 1", ColgramColemailerEngine.MODE_INTERVAL, 120L, 0, "Promo");
        acc1.ownerAccount = 1;
        acc1.targetChats.add(-100222L);

        engine.addOrUpdateCampaign(acc0);
        engine.addOrUpdateCampaign(acc1);

        // Folder sync for Account 0 must filter unsafe targets and NOT overwrite Account 1's campaign.
        Set<Long> dirtyTargets = new LinkedHashSet<>(Arrays.asList(
                0L, 777000L, 0x4000000000000001L, 0x2000000000000001L, -100333L));
        engine.syncFolderTargets(0, "Promo", dirtyTargets);
        check(acc0.targetChats.size() == 1 && acc0.targetChats.contains(-100333L),
                "Account 0 sync must strip zero/service/secret/folder IDs, got " + acc0.targetChats);
        check(acc1.targetChats.size() == 1 && acc1.targetChats.contains(-100222L),
                "Account 0 sync must NEVER overwrite Account 1's campaign targets");

        // Arm baselines, then verify Account 1 scheduler NEVER fires Account 0's campaign!
        check(engine.collectDueBroadcasts(0, 1000L).isEmpty(), "baseline arm acc0");
        check(engine.collectDueBroadcasts(1, 1000L).isEmpty(), "baseline arm acc1");

        List<ColgramColemailerEngine.BroadcastDueEvent> dueAcc0 = engine.collectDueBroadcasts(0, 1200L);
        check(dueAcc0.size() == 1 && "camp_acc0".equals(dueAcc0.get(0).campaign.name),
                "Account 0 must only collect camp_acc0, got " + dueAcc0.size());

        List<ColgramColemailerEngine.BroadcastDueEvent> dueAcc1 = engine.collectDueBroadcasts(1, 1200L);
        check(dueAcc1.size() == 1 && "camp_acc1".equals(dueAcc1.get(0).campaign.name),
                "Account 1 must only collect camp_acc1 (never leak camp_acc0), got " + dueAcc1.size());

        // Serialization round-trip preserves ownerAccount cleanly
        String serialized = engine.serializeState();
        ColgramColemailerEngine restored = ColgramColemailerEngine.newStandaloneForTest();
        restored.loadFromSerializedState(serialized);
        check(restored.getCampaign("camp_acc0").ownerAccount == 0, "ownerAccount=0 must round-trip");
        check(restored.getCampaign("camp_acc1").ownerAccount == 1, "ownerAccount=1 must round-trip");
    }

    private static void testAntiDeleteRejectsZeroDialogId() {
        check(!ColgramHookHandler.hookShouldPreventDelete(0L, 42),
                "hookShouldPreventDelete must reject dialogId=0 so tombstones never bleed into Saved Messages/other chats");
        check(!ColgramHookHandler.hookShouldPreventDelete(12345L, 0),
                "hookShouldPreventDelete must reject messageId=0");
    }

    public static void main(String[] args) {
        testSaveLeadsDefaultOffAndLeadGate();
        testAutoReplyAndDialogBitmaskIsolation();
        testPythonEngineSendTargetGuard();
        testColemailerCampaignAccountIsolation();
        testAntiDeleteRejectsZeroDialogId();
        System.out.println("ColgramSavedLeakRegressionTest: PASS");
    }
}
