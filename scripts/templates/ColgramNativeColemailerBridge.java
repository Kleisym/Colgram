package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.colgram.core.ColgramAutoReplyPolicy;
import org.colgram.core.ColgramBotChatIdPolicy;
import org.colgram.core.ColgramColemailerEngine;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramHookHandler;
import org.colgram.core.ColgramHttp;
import org.colgram.core.ColgramSpamGuard;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native Java bridge connecting ColgramColemailerEngine & ColgramSpamGuard to
 * Telegram 12.10.6 MTProto / Bot API, chat commands, folder sync, notification suppression,
 * and 1-on-1 DM instant auto-delete mute.
 */
public final class ColgramNativeColemailerBridge {

    private static final String TAG = "ColgramColemailer";
    private static final long FOLDER_SYNC_MIN_INTERVAL_MS = 60_000L;
    private static volatile long lastFolderSyncMs = 0L;

    private static final Runnable scheduledTickRunnable = ColgramNativeColemailerBridge::runDueBroadcastsOnUi;

    private static final Map<String, Long> recentIncomingDedupe = new LinkedHashMap<String, Long>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };

    private static final Map<String, Boolean> recentSuppressVerdicts = new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 256;
        }
    };

    private static final Map<String, MessageObject> pendingReplyByDialog = new ConcurrentHashMap<>();
    private static final Set<String> activeGatekeeperDialogs = ConcurrentHashMap.newKeySet();
    private static volatile List<List<ColgramColemailerEngine.ButtonSpec>> draftButtons = Collections.emptyList();

    private ColgramNativeColemailerBridge() {}

    private static String accountKey(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    /** Never allow Saved Messages, Secret Chats, Folders, or official Telegram service dialogs as broadcast/auto targets. */
    private static boolean isBlockedTargetDialog(int account, long dialogId) {
        if (dialogId == 0L || DialogObject.isEncryptedDialog(dialogId)
                || DialogObject.isFolderDialogId(dialogId)
                || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(dialogId)) {
            return true;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        return selfId == 0L || dialogId == selfId;
    }

    /**
     * Event-driven scheduler entry point (backwards-compatible name):
     * Schedules ONE exact delayed UI runnable for the next due campaign.
     * Cancels all callbacks when disabled or activeCampaignCount == 0 -> 0.0% idle CPU & 0 wakeups.
     */
    public static void startScheduler() {
        rescheduleScheduler();
    }

    public static void stopScheduler() {
        AndroidUtilities.cancelRunOnUIThread(scheduledTickRunnable);
    }

    public static void rescheduleScheduler() {
        AndroidUtilities.cancelRunOnUIThread(scheduledTickRunnable);
        if (!ColgramConfig.isBroadcasterEnabled()) {
            return;
        }
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        if (!engine.hasActiveCampaigns()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long delayMs = engine.getNextDueCampaignDelayMs(-1, nowMs);
        if (delayMs < 0L) {
            return;
        }
        AndroidUtilities.runOnUIThread(scheduledTickRunnable, Math.max(0L, delayMs));
    }

    public static void onFloodWait(int account, long dialogId, String errorText) {
        long waitSec = ColgramColemailerEngine.parseFloodWaitSeconds(errorText);
        if (waitSec <= 0L) waitSec = 60L;
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        engine.recordFloodWait(account, dialogId, waitSec, System.currentTimeMillis());
        engine.recordPingRpc(dialogId, "flood_wait", false, "wait=" + waitSec + "s");
        rescheduleScheduler();
    }

    public static void rememberPendingReply(long dialogId, MessageObject replyToMsg) {
        rememberPendingReply(UserConfig.selectedAccount, dialogId, replyToMsg);
    }

    public static void rememberPendingReply(int account, long dialogId, MessageObject replyToMsg) {
        if (dialogId == 0L) return;
        String key = accountKey(account, dialogId);
        if (replyToMsg == null) {
            pendingReplyByDialog.remove(key);
        } else {
            pendingReplyByDialog.put(key, replyToMsg);
        }
    }

    public static void syncFoldersIntoEngine() {
        syncFoldersIntoEngine(UserConfig.selectedAccount);
    }

    public static void syncFoldersIntoEngine(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return;
        ArrayList<MessagesController.DialogFilter> filters = mc.getDialogFilters();
        if (filters == null || filters.isEmpty()) return;
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        if (dialogs == null) return;
        AccountInstance ai = AccountInstance.getInstance(account);
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        for (MessagesController.DialogFilter f : filters) {
            if (f == null || TextUtils.isEmpty(f.name)) continue;
            Set<Long> ids = new LinkedHashSet<>();
            for (TLRPC.Dialog d : dialogs) {
                if (d != null && !isBlockedTargetDialog(account, d.id) && f.includesDialog(ai, d.id, d)) {
                    ids.add(d.id);
                }
            }
            engine.syncFolderTargets(account, f.name, ids);
        }
        lastFolderSyncMs = System.currentTimeMillis();
        rescheduleScheduler();
    }

    private static boolean anyActiveCampaignNeedsFolderSync(ColgramColemailerEngine engine) {
        for (ColgramColemailerEngine.BroadcastCampaign c : engine.listCampaigns()) {
            if (c != null && c.enabled && !TextUtils.isEmpty(c.folderName)) {
                return true;
            }
        }
        return false;
    }

    private static void runDueBroadcastsOnUi() {
        try {
            if (!ColgramConfig.isBroadcasterEnabled()) {
                return;
            }
            ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
            if (!engine.hasActiveCampaigns()) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            long nowSec = nowMs / 1000L;
            int selectedAcc = UserConfig.selectedAccount;
            boolean needFolderSync = (nowMs - lastFolderSyncMs >= FOLDER_SYNC_MIN_INTERVAL_MS)
                    && anyActiveCampaignNeedsFolderSync(engine);
            int dispatchSlot = 0;
            for (int acc = 0; acc < UserConfig.MAX_ACCOUNT_COUNT; acc++) {
                if (!UserConfig.getInstance(acc).isClientActivated()) continue;
                if (acc != selectedAcc && !engine.hasActiveCampaigns(acc)) continue;
                if (needFolderSync) {
                    syncFoldersIntoEngine(acc);
                }
                List<ColgramColemailerEngine.BroadcastDueEvent> due = engine.collectDueBroadcasts(acc, nowSec);
                if (due.isEmpty()) continue;
                final int targetAccount = acc;
                for (int i = 0; i < due.size(); i++) {
                    final ColgramColemailerEngine.BroadcastDueEvent ev = due.get(i);
                    if (isBlockedTargetDialog(targetAccount, ev.chatId)) continue;
                    if (engine.isFloodBackedOff(targetAccount, ev.chatId, nowMs)) continue;
                    // Advance baseline immediately so overlapping ticks never double-send the same slot.
                    engine.markBroadcastSent(ev.campaign, ev.chatId, nowSec);
                    long delayMs = dispatchSlot++ * 900L;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!ColgramConfig.isBroadcasterEnabled()) return;
                        if (engine.isFloodBackedOff(targetAccount, ev.chatId, System.currentTimeMillis())) return;
                        dispatchCampaignToChat(targetAccount, ev.campaign, ev.chatId);
                    }, delayMs);
                }
            }
        } catch (Throwable tr) {
            FileLog.e(tr);
        } finally {
            rescheduleScheduler();
        }
    }

    public static void triggerCampaignNow(String name) {
        if (TextUtils.isEmpty(name)) return;
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        ColgramColemailerEngine.BroadcastCampaign c = engine.getCampaign(name);
        if (c == null) {
            showToast("Рассылка не найдена", name);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            int account = (c.ownerAccount >= 0 && c.ownerAccount < UserConfig.MAX_ACCOUNT_COUNT)
                    ? c.ownerAccount : UserConfig.selectedAccount;
            if (!TextUtils.isEmpty(c.folderName)) {
                syncFoldersIntoEngine(account);
            }
            if (c.targetChats.isEmpty()) {
                showToast("Нет целевых чатов", "Укажите папку или откройте нужный чат и используйте .рассыл");
                return;
            }
            int idx = 0;
            int sentCount = 0;
            long nowMs = System.currentTimeMillis();
            for (Long chatId : new ArrayList<>(c.targetChats)) {
                if (chatId == null || isBlockedTargetDialog(account, chatId)) continue;
                if (engine.isFloodBackedOff(account, chatId, nowMs)) continue;
                final long target = chatId;
                sentCount++;
                AndroidUtilities.runOnUIThread(() -> {
                    if (engine.isFloodBackedOff(account, target, System.currentTimeMillis())) return;
                    dispatchCampaignToChat(account, c, target);
                }, idx++ * 900L);
            }
            showToast("Рассылка запущена", c.name + ": чатов " + sentCount);
        });
    }

    private static void dispatchCampaignToChat(int account, ColgramColemailerEngine.BroadcastCampaign campaign, long dialogId) {
        if (campaign == null || TextUtils.isEmpty(campaign.text) || isBlockedTargetDialog(account, dialogId)) return;
        if (campaign.ownerAccount >= 0 && campaign.ownerAccount != account) return;
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        if (engine.isFloodBackedOff(account, dialogId, System.currentTimeMillis())) return;
        String botToken = ColgramConfig.getHelperBotToken();
        if (!campaign.buttons.isEmpty() && !TextUtils.isEmpty(botToken)) {
            final String markupJson = ColgramColemailerEngine.toBotApiReplyMarkupJson(campaign.buttons);
            final String botChatId = toBotApiChatId(account, dialogId);
            new Thread(() -> {
                try {
                    String apiUrl = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                    String json = "{\"chat_id\":" + botChatId
                            + ",\"text\":\"" + jsonEscape(campaign.text) + "\""
                            + ",\"disable_web_page_preview\":" + campaign.disablePreview
                            + ",\"reply_markup\":" + markupJson + "}";
                    ColgramHttp.Response resp = ColgramHttp.post(apiUrl, json, null);
                    if (resp != null && resp.code >= 200 && resp.code < 300) {
                        engine.markBroadcastSent(campaign, dialogId, System.currentTimeMillis() / 1000L);
                        engine.recordPingRpc(dialogId, "broadcast_bot_api", true, campaign.name);
                        return;
                    }
                    if (resp != null && resp.code == 429) {
                        long waitSec = ColgramColemailerEngine.parseFloodWaitSeconds(resp.body);
                        if (waitSec <= 0L) waitSec = 60L;
                        engine.recordFloodWait(account, dialogId, waitSec, System.currentTimeMillis());
                        engine.recordPingRpc(dialogId, "broadcast_bot_api_429", false, "HTTP 429 wait=" + waitSec + "s");
                        AndroidUtilities.runOnUIThread(ColgramNativeColemailerBridge::rescheduleScheduler);
                        return;
                    }
                    engine.recordPingRpc(
                            dialogId, "broadcast_bot_api_fallback", false, "HTTP " + (resp == null ? -1 : resp.code));
                } catch (Throwable t) {
                    engine.recordPingRpc(dialogId, "broadcast_bot_api_err", false, t.getMessage());
                }
                AndroidUtilities.runOnUIThread(() -> sendClientPost(account, campaign, dialogId));
            }, "colgram-bot-broadcast").start();
            return;
        }
        sendClientPost(account, campaign, dialogId);
    }

    private static void sendClientPost(int account, ColgramColemailerEngine.BroadcastCampaign campaign, long dialogId) {
        if (campaign == null || isBlockedTargetDialog(account, dialogId)) return;
        if (campaign.ownerAccount >= 0 && campaign.ownerAccount != account) return;
        try {
            StringBuilder payload = new StringBuilder(campaign.text);
            if (!campaign.buttons.isEmpty()) {
                StringBuilder links = new StringBuilder();
                for (List<ColgramColemailerEngine.ButtonSpec> row : campaign.buttons) {
                    boolean added = false;
                    for (ColgramColemailerEngine.ButtonSpec b : row) {
                        if ("url".equals(b.type) && !TextUtils.isEmpty(b.value)) {
                            if (added) links.append(" | ");
                            links.append("[").append(b.text).append("](").append(b.value).append(")");
                            added = true;
                        }
                    }
                    if (added) links.append("\n");
                }
                if (links.length() > 0) {
                    payload.append("\n\n").append(links.toString().trim());
                }
            }
            CharSequence[] msgHolder = new CharSequence[]{payload.toString()};
            ArrayList<TLRPC.MessageEntity> entities = MediaDataController.getInstance(account)
                    .getEntities(msgHolder, true, true);
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    msgHolder[0].toString(),
                    dialogId,
                    null, null, null,
                    !campaign.disablePreview,
                    entities,
                    null, null, true, 0, 0, null, false
            );
            SendMessagesHelper.getInstance(account).sendMessage(params);
            ColgramColemailerEngine.getInstance().markBroadcastSent(campaign, dialogId, System.currentTimeMillis() / 1000L);
            ColgramColemailerEngine.getInstance().recordPingRpc(dialogId, "broadcast_client", true, campaign.name);
        } catch (Throwable t) {
            FileLog.e(t);
            ColgramColemailerEngine.getInstance().recordPingRpc(dialogId, "broadcast_client_err", false, t.getMessage());
        }
    }

    public static boolean handleChatCommand(long dialogId, int replyToMsgId, String rawText, long replySenderId) {
        return handleChatCommand(UserConfig.selectedAccount, dialogId, replyToMsgId, rawText, replySenderId);
    }

    public static boolean handleChatCommand(int account, long dialogId, int replyToMsgId, String rawText, long replySenderId) {
        if (TextUtils.isEmpty(rawText)) return false;
        String trimmed = rawText.trim();
        if (!trimmed.startsWith(".")) return false;
        int safeAcc = (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) ? account : UserConfig.selectedAccount;
        int nl = trimmed.indexOf('\n');
        String firstLine = nl >= 0 ? trimmed.substring(0, nl).trim() : trimmed;
        String restBody = nl >= 0 ? trimmed.substring(nl + 1).trim() : "";
        String[] parts = firstLine.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        account = safeAcc;
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        MessageObject replyObj = pendingReplyByDialog.remove(accountKey(account, dialogId));

        switch (cmd) {
            case ".очиститьпинги":
            case ".clearpings":
                startClearUnreadSpamPings(account, dialogId);
                return true;

            case ".пингдиаг":
            case ".pingdiag": {
                String argText = firstLine.length() > cmd.length() ? firstLine.substring(cmd.length()).trim() : "";
                if (!restBody.isEmpty()) {
                    argText = argText.isEmpty() ? restBody : argText + "\n" + restBody;
                }
                showPingDiagnostic(account, dialogId, replyObj, argText);
                return true;
            }

            case ".тишина":
            case ".quiet": {
                boolean turnOff = parts.length > 1 && ("выкл".equalsIgnoreCase(parts[1])
                        || "off".equalsIgnoreCase(parts[1])
                        || "стоп".equalsIgnoreCase(parts[1]));
                if (turnOff) {
                    restoreQuietMode(account, dialogId);
                } else {
                    applyQuietMode(account, dialogId);
                }
                return true;
            }

            case ".dmmute":
            case ".мутлс": {
                long selfId = UserConfig.getInstance(account).getClientUserId();
                if (DialogObject.isEncryptedDialog(dialogId)
                        || !ColgramColemailerEngine.isEligibleDmAutoDeleteDialog(dialogId, selfId)) {
                    showToast("Мут ЛС недоступен здесь", "Доступно только в обычном личном чате 1-на-1");
                    return true;
                }
                boolean nextState;
                if (parts.length > 1 && ("выкл".equalsIgnoreCase(parts[1]) || "off".equalsIgnoreCase(parts[1]) || "0".equals(parts[1]))) {
                    engine.setDmAutoDeleteMuted(account, dialogId, selfId, false);
                    nextState = false;
                } else if (parts.length > 1 && ("вкл".equalsIgnoreCase(parts[1]) || "on".equalsIgnoreCase(parts[1]) || "1".equals(parts[1]))) {
                    engine.setDmAutoDeleteMuted(account, dialogId, selfId, true);
                    nextState = true;
                } else {
                    nextState = engine.toggleDmAutoDeleteMuted(account, dialogId, selfId);
                }
                if (nextState) {
                    NotificationsController nc = NotificationsController.getInstance(account);
                    if (nc != null) nc.removeNotificationsForDialog(dialogId);
                    showToast("Мут ЛС включён", "Все входящие сообщения этого собеседника удаляются мгновенно");
                } else {
                    showToast("Мут ЛС выключен", "Входящие сообщения снова приходят обычно");
                }
                return true;
            }

            case ".dmunmute":
            case ".размутлс": {
                long selfId = UserConfig.getInstance(account).getClientUserId();
                engine.setDmAutoDeleteMuted(account, dialogId, selfId, false);
                showToast("Мут ЛС выключен", "Автоудаление входящих отключено");
                return true;
            }

            case ".мутить":
            case ".мут":
            case ".mute": {
                boolean wholeChat = false;
                boolean explicitMode = false;
                String mode = ColgramConfig.getDefaultMuteMode();
                for (int i = 1; i < parts.length; i++) {
                    String tok = parts[i].toLowerCase(Locale.ROOT);
                    if (tok.equals("чат") || tok.equals("chat")) wholeChat = true;
                    else {
                        mode = ColgramColemailerEngine.normalizeMuteMode(tok);
                        explicitMode = true;
                    }
                }
                long selfId = UserConfig.getInstance(account).getClientUserId();
                // Inside a 1-on-1 DM, `.mute` (or `.mute delete`) toggles/enables instant DM Auto-Delete Mute cleanly.
                if (dialogId > 0L && replySenderId == 0L && !wholeChat
                        && !DialogObject.isEncryptedDialog(dialogId)
                        && ColgramColemailerEngine.isEligibleDmAutoDeleteDialog(dialogId, selfId)
                        && (!explicitMode || ColgramColemailerEngine.MUTE_DELETE.equals(mode))) {
                    boolean nextState = explicitMode
                            ? engine.setDmAutoDeleteMuted(account, dialogId, selfId, true)
                            : engine.toggleDmAutoDeleteMuted(account, dialogId, selfId);
                    if (nextState) {
                        NotificationsController nc = NotificationsController.getInstance(account);
                        if (nc != null) nc.removeNotificationsForDialog(dialogId);
                        showToast("Мут ЛС включён", "Все входящие сообщения удаляются мгновенно");
                    } else {
                        showToast("Мут ЛС выключен", "Автоудаление входящих отключено");
                    }
                    return true;
                }
                long target = replySenderId != 0 ? replySenderId : (dialogId > 0 ? dialogId : 0L);
                if (wholeChat && target == 0) {
                    engine.setMuteRule(0L, dialogId, true, mode);
                    showToast("Режим чата: " + mode, "Для всего чата включено правило автодействия");
                    return true;
                }
                if (target == 0) {
                    showToast("Ответьте на сообщение", "`.мутить [тихо|удалить|варн|бан] [чат]` работает в личке или ответом");
                    return true;
                }
                long scope = wholeChat ? dialogId : 0L;
                engine.setMuteRule(target, scope, false, mode);
                if (scope == 0L) {
                    ColgramHookHandler.setNotificationsBlocked(target, true);
                }
                showToast("Правило мута добавлено", "ID " + target + " -> " + mode + (scope == 0 ? " (глобально)" : " (в чате)"));
                return true;
            }

            case ".размутить":
            case ".размут":
            case ".unmute": {
                long selfId = UserConfig.getInstance(account).getClientUserId();
                boolean wasDmMuted = dialogId > 0L && engine.isDmAutoDeleteMuted(account, dialogId);
                if (wasDmMuted) {
                    engine.setDmAutoDeleteMuted(account, dialogId, selfId, false);
                }
                long target = replySenderId != 0 ? replySenderId : (dialogId > 0 ? dialogId : 0L);
                boolean wholeChat = parts.length > 1 && ("чат".equalsIgnoreCase(parts[1]) || "chat".equalsIgnoreCase(parts[1]));
                if (wholeChat) {
                    engine.removeMuteRule(0L, dialogId, true);
                    showToast("Чат размучен", "Групповое правило удалено");
                    return true;
                }
                if (target == 0) {
                    showToast("Укажите пользователя ответом", "Ответьте на сообщение или откройте личный чат");
                    return true;
                }
                engine.removeMuteRule(target, dialogId, false);
                engine.removeMuteRule(target, 0L, false);
                ColgramHookHandler.setNotificationsBlocked(target, false);
                showToast(wasDmMuted ? "Мут ЛС выключен" : "Пользователь размучен",
                        wasDmMuted ? "Входящие сообщения снова отображаются" : ("ID " + target + " снова активен"));
                return true;
            }

            case ".мутлист":
            case ".mutelist": {
                List<ColgramColemailerEngine.MuteRule> rules = engine.listMuteRules();
                Set<Long> dmMuted = engine.getDmAutoDeleteMutedDialogs(account);
                List<Long> wl = engine.listWhitelist();
                StringBuilder sb = new StringBuilder();
                sb.append("Мут ЛС (автоудаление): ").append(dmMuted.size()).append("\n");
                for (Long did : dmMuted) {
                    sb.append("• ЛС ID ").append(did).append("\n");
                }
                sb.append("\nПравила мута: ").append(rules.size()).append("\n");
                for (ColgramColemailerEngine.MuteRule r : rules) {
                    sb.append("• ");
                    if (r.wholeChat) sb.append("Чат ").append(r.scopeChatId);
                    else sb.append("Юзер ").append(r.senderId).append(r.scopeChatId == 0 ? " (везде)" : " (чат " + r.scopeChatId + ")");
                    sb.append(" -> ").append(r.mode).append("\n");
                }
                sb.append("\nБелый список (").append(wl.size()).append("): ").append(wl);
                showAlert("Список мута и исключений", sb.toString());
                return true;
            }

            case ".вайтлист":
            case ".белый":
            case ".wl": {
                long target = replySenderId != 0 ? replySenderId : (dialogId > 0 ? dialogId : 0L);
                if (target == 0) {
                    showToast("Ответьте на сообщение", "`.вайтлист` добавляет/удаляет автора в белый список");
                    return true;
                }
                if (engine.isWhitelisted(target)) {
                    engine.removeWhitelist(target);
                    showToast("Удалён из белого списка", "ID " + target);
                } else {
                    engine.addWhitelist(target);
                    engine.removeMuteRule(target, 0L, false);
                    engine.removeMuteRule(target, dialogId, false);
                    ColgramHookHandler.setNotificationsBlocked(target, false);
                    showToast("Добавлен в белый список", "ID " + target + " защищён от антиспама");
                }
                return true;
            }

            case ".кнопки":
            case ".buttons": {
                String tail = trimmed.length() > cmd.length() ? trimmed.substring(cmd.length()).trim() : "";
                if ("очистить".equalsIgnoreCase(tail) || "clear".equalsIgnoreCase(tail)) {
                    draftButtons = Collections.emptyList();
                    showToast("Черновик кнопок очищен", "Новые рассылки создаются без кнопок");
                    return true;
                }
                if ("тест".equalsIgnoreCase(tail) || "test".equalsIgnoreCase(tail)) {
                    if (draftButtons.isEmpty()) {
                        showToast("Нет кнопок в черновике", "Задайте через `.кнопки [Текст](https://...)`");
                    } else {
                        showAlert("Предпросмотр кнопок (Bot API JSON)",
                                ColgramColemailerEngine.formatButtonsPreview(draftButtons)
                                        + "\n\n" + ColgramColemailerEngine.toBotApiReplyMarkupJson(draftButtons));
                    }
                    return true;
                }
                try {
                    List<List<ColgramColemailerEngine.ButtonSpec>> rows = ColgramColemailerEngine.parseButtonLayout(tail);
                    if (rows.isEmpty()) {
                        showToast("Формат `.кнопки`", "[Сайт](https://example.com) || [Купить](cb:buy)");
                        return true;
                    }
                    draftButtons = rows;
                    showAlert("Кнопки сохранены в черновик", ColgramColemailerEngine.formatButtonsPreview(rows));
                } catch (IllegalArgumentException e) {
                    showToast("Ошибка разбора кнопок", e.getMessage());
                }
                return true;
            }

            case ".превью":
            case ".preview": {
                String body = trimmed.length() > cmd.length() ? trimmed.substring(cmd.length()).trim() : "";
                if (body.isEmpty() && replyObj != null && replyObj.messageOwner != null) {
                    body = replyObj.messageOwner.message;
                }
                ColgramColemailerEngine.ParsedPost parsed = ColgramColemailerEngine.extractButtonsFromPost(body);
                List<List<ColgramColemailerEngine.ButtonSpec>> btns = !parsed.buttons.isEmpty() ? parsed.buttons : draftButtons;
                showAlert("Предпросмотр поста",
                        parsed.body + "\n\n--- Кнопки ---\n" + ColgramColemailerEngine.formatButtonsPreview(btns));
                return true;
            }

            case ".рассыл":
            case ".broadcast": {
                String tail = trimmed.length() > cmd.length() ? trimmed.substring(cmd.length()).trim() : "";
                String[] pipeSplit = tail.split("\\|", 2);
                String header = pipeSplit[0].trim();
                String body = pipeSplit.length > 1 ? pipeSplit[1].trim() : restBody;
                if (body.isEmpty() && replyObj != null && replyObj.messageOwner != null) {
                    body = replyObj.messageOwner.message;
                }
                String[] hTokens = header.split("\\s+");
                if (hTokens.length == 0 || hTokens[0].isEmpty() || body.isEmpty()) {
                    showToast("Формат рассылки", ".рассыл Имя 15м | Текст поста (или в UI Colgram Антиспам)");
                    return true;
                }
                String campName = hTokens[0];
                String schedRaw = header.length() > campName.length() ? header.substring(campName.length()).trim() : "10м";
                ColgramColemailerEngine.ScheduleSpec spec = ColgramColemailerEngine.parseSchedule(schedRaw);
                ColgramColemailerEngine.ParsedPost parsed = ColgramColemailerEngine.extractButtonsFromPost(body);
                ColgramColemailerEngine.BroadcastCampaign existing = engine.getCampaign(campName);
                ColgramColemailerEngine.BroadcastCampaign c = new ColgramColemailerEngine.BroadcastCampaign(
                        campName, parsed.body, spec.mode, spec.intervalSec, spec.counterThreshold,
                        existing != null ? existing.folderName : "");
                c.ownerAccount = account;
                if (existing != null) {
                    c.targetChats.addAll(existing.targetChats);
                    c.disablePreview = existing.disablePreview;
                }
                if (c.targetChats.isEmpty() && !isBlockedTargetDialog(account, dialogId)) {
                    c.targetChats.add(dialogId);
                }
                c.buttons.addAll(!parsed.buttons.isEmpty() ? parsed.buttons : draftButtons);
                engine.addOrUpdateCampaign(c);
                engine.armMissingBaselines(account, System.currentTimeMillis() / 1000L);
                ColgramConfig.setBroadcasterEnabled(true);
                rescheduleScheduler();
                showToast("Кампания сохранена: " + campName,
                        "Режим " + modeTitle(spec.mode) + ", чатов: " + c.targetChats.size());
                return true;
            }

            case ".стоп":
            case ".stop": {
                if (parts.length > 1) {
                    boolean ok = engine.setCampaignEnabled(parts[1], false);
                    rescheduleScheduler();
                    showToast(ok ? "Кампания приостановлена" : "Кампания не найдена", parts[1]);
                } else {
                    ColgramConfig.setBroadcasterEnabled(false);
                    stopScheduler();
                    showToast("Авторассылка остановлена", "Включить: `.продолжить` или в настройках Colgram");
                }
                return true;
            }

            case ".продолжить":
            case ".resume": {
                if (parts.length > 1) {
                    boolean ok = engine.setCampaignEnabled(parts[1], true);
                    engine.armMissingBaselines(account, System.currentTimeMillis() / 1000L);
                    rescheduleScheduler();
                    showToast(ok ? "Кампания возобновлена" : "Кампания не найдена", parts[1]);
                } else {
                    ColgramConfig.setBroadcasterEnabled(true);
                    engine.armMissingBaselines(account, System.currentTimeMillis() / 1000L);
                    rescheduleScheduler();
                    showToast("Авторассылка активна", "Событийный планировщик (0% нагрузки в простое)");
                }
                return true;
            }

            case ".синк":
            case ".sync": {
                syncFoldersIntoEngine(account);
                showToast("Папки синхронизированы", "Списки чатов в кампаниях обновлены из Telegram");
                return true;
            }

            default:
                return false;
        }
    }

    /**
     * Zero-latency notification suppression gate called from NotificationsController BEFORE sound/vibration/banner.
     * Returns true to silently suppress notifications for:
     *   1. 1-on-1 DM Instant Auto-Delete Mute dialogs
     *   2. Explicitly muted users / chats (respecting whitelist)
     *   3. Ads, promos (`erid:`, `#реклама`, promo links, suspicious URL buttons / ad channel forwards) and spam pings
     *      from non-contacts — with 0% false positives on contacts, Saved Messages, Secret Chats, and normal conversations.
     */
    public static boolean shouldSuppressNotification(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return false;
        return shouldSuppressNotification(account, messageObject.messageOwner);
    }

    public static boolean shouldSuppressNotification(int account, TLRPC.Message msg) {
        if (msg == null || msg.out || msg instanceof TLRPC.TL_messageEmpty) return false;
        try {
            long selfId = UserConfig.getInstance(account).getClientUserId();
            ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
            if (selfId > 0L) {
                engine.setSelfUserId(account, selfId);
            }
            long dialogId = MessageObject.getDialogId(msg);
            if (dialogId == 0L || (selfId > 0L && dialogId == selfId)
                    || DialogObject.isEncryptedDialog(dialogId)
                    || DialogObject.isFolderDialogId(dialogId)
                    || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(dialogId)) {
                return false;
            }
            long senderId = msg.from_id != null ? MessageObject.getPeerId(msg.from_id)
                    : (dialogId > 0L ? dialogId : 0L);
            if (selfId > 0L && senderId == selfId) {
                return false;
            }

            // 1. 1-on-1 DM Instant Auto-Delete Mute (O(1) lock-free, supersedes contact check because explicitly toggled).
            if (dialogId > 0L && engine.isDmAutoDeleteMuted(account, dialogId)) {
                return true;
            }

            // 2. Explicit mute rules (O(1) lock-free, respects whitelist).
            if (senderId != 0L && engine.getMuteActionFor(senderId, dialogId) != null) {
                return true;
            }
            if (dialogId < 0L && engine.getMuteActionFor(0L, dialogId) != null) {
                return true;
            }

            // 3. Anti-spam / ad suppression (zero false positives on contacts & whitelisted users).
            if (!ColgramConfig.isSpamGuardEnabled()) return false;
            if (senderId != 0L && (isUserContact(account, senderId) || engine.isWhitelisted(senderId))) {
                return false;
            }

            String cacheKey = msg.id > 0 ? (account + ":" + dialogId + ":" + msg.id) : null;
            if (cacheKey != null) {
                synchronized (recentSuppressVerdicts) {
                    Boolean cached = recentSuppressVerdicts.get(cacheKey);
                    if (cached != null) return cached;
                }
            }

            boolean isGroup = dialogId < 0L;
            boolean isBroadcastChannel = false;
            if (isGroup) {
                MessagesController mc = MessagesController.getInstance(account);
                TLRPC.Chat chat = mc != null ? mc.getChat(-dialogId) : null;
                isBroadcastChannel = chat != null && ChatObject.isChannelAndNotMegaGroup(chat);
            }

            String text = msg.message == null ? "" : msg.message;
            boolean inlineUrlBtns = hasInlineUrlButtons(msg);
            boolean fwdFromChannel = isForwardedFromChannel(msg);
            if (text.isEmpty() && !inlineUrlBtns && !msg.mentioned) {
                return false;
            }

            List<ColgramSpamGuard.MentionTarget> targets = extractMentionTargets(msg);
            String ownUname = ownUsername(account);
            List<String> own = ownLabels(account);
            String pingKind = classifyMessagePing(msg, selfId, ownUname, own, targets);
            boolean isPing = msg.mentioned || pingKind != null;
            boolean hiddenEntity = ColgramSpamGuard.hasHiddenOrMassMentionEntity(text, targets);
            boolean visualMedia = isVisualMedia(msg);
            boolean viaBot = msg.via_bot_id != 0;

            ColgramSpamGuard.SpamReport report = ColgramSpamGuard.diagnose(
                    text, "", pingKind, isPing, viaBot, visualMedia, hiddenEntity,
                    isPing && !isBroadcastChannel, own, inlineUrlBtns, fwdFromChannel);

            boolean suppress;
            if (report.isLead) {
                suppress = false;
            } else if (isBroadcastChannel) {
                suppress = report.isExplicitAd
                        || (report.isSpam && report.score >= 85 && (inlineUrlBtns || report.isHardSpam || report.shouldAutoRead));
            } else if (isGroup) {
                suppress = report.isSpam || report.shouldAutoRead;
            } else {
                // Non-contact 1-on-1 DM: silence obvious cold spam/ad disclosures without touching ordinary first DMs.
                suppress = report.isExplicitAd || report.isHardSpam || report.score >= 70;
            }

            if (cacheKey != null) {
                synchronized (recentSuppressVerdicts) {
                    recentSuppressVerdicts.put(cacheKey, suppress);
                }
            }
            return suppress;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void executeDmInstantAutoDelete(int account, long dialogId, int msgId, int msgDate) {
        if (dialogId <= 0L || msgId <= 0) return;
        // Mark force-purged immediately so Anti-Delete has zero race window.
        ColgramHookHandler.markForcePurged(dialogId, msgId);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ColgramHookHandler.markForcePurged(dialogId, msgId);
                MessagesController mc = MessagesController.getInstance(account);
                if (mc != null) {
                    ArrayList<Integer> ids = new ArrayList<>(1);
                    ids.add(msgId);
                    // revoke=true deletes for BOTH sides on Telegram servers instantaneously.
                    mc.deleteMessages(ids, null, null, dialogId, 0, true, 0);
                    if (!ColgramHookHandler.shouldPreventReadReceipt(dialogId)) {
                        mc.markDialogAsRead(dialogId, msgId, msgId, msgDate, false, 0, 0, true, 0);
                    }
                }
                NotificationsController nc = NotificationsController.getInstance(account);
                if (nc != null) {
                    nc.removeNotificationsForDialog(dialogId);
                }
                ColgramColemailerEngine.getInstance().recordPingRpc(dialogId, "dm_auto_delete", true, "msgId=" + msgId);
            } catch (Throwable tr) {
                FileLog.e(tr);
            }
        });
    }

    public static void onIncomingMessage(int account, TLRPC.Message msg) {
        if (msg == null || msg.id <= 0 || msg instanceof TLRPC.TL_messageEmpty || msg.action != null) return;
        TLRPC.User selfUser = UserConfig.getInstance(account).getCurrentUser();
        if (selfUser != null && selfUser.bot) return;
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0L) return;

        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        engine.setSelfUserId(account, selfId);

        long dialogId = MessageObject.getDialogId(msg);
        if (dialogId == 0L || dialogId == selfId
                || DialogObject.isEncryptedDialog(dialogId)
                || DialogObject.isFolderDialogId(dialogId)
                || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(dialogId)) {
            return;
        }
        String dedupeKey = account + ":" + dialogId + ":" + msg.id;
        long nowMs = System.currentTimeMillis();
        synchronized (recentIncomingDedupe) {
            if (recentIncomingDedupe.containsKey(dedupeKey)) return;
            recentIncomingDedupe.put(dedupeKey, nowMs);
        }

        long senderId = msg.from_id != null ? MessageObject.getPeerId(msg.from_id)
                : (dialogId > 0L ? dialogId : 0L);
        if (msg.out || (senderId != 0 && senderId == selfId)) {
            return;
        }

        // 0. 1-on-1 DM Instant Auto-Delete Mute (handles ALL message types including media/stickers/voice, even if from_id is null).
        if (dialogId > 0L && engine.isDmAutoDeleteMuted(account, dialogId)) {
            executeDmInstantAutoDelete(account, dialogId, msg.id, msg.date);
            return;
        }

        long nowSec = nowMs / 1000L;
        if (msg.date >= nowSec - 180L && engine.hasActiveCampaigns()) {
            boolean thresholdReached = engine.onIncomingChatMessageAndCheckDue(account, dialogId, nowSec);
            if (thresholdReached && ColgramConfig.isBroadcasterEnabled()) {
                rescheduleScheduler();
            }
        }

        // Safe Gatekeeper Auto-Sub when active in a group/bot dialog
        if (ColgramConfig.isAutoSubEnabled() && !TextUtils.isEmpty(msg.message)
                && msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup
                && (msg.mentioned || dialogId > 0 || activeGatekeeperDialogs.contains(accountKey(account, dialogId)))
                && ColgramColemailerEngine.isGatekeeperText(msg.message)) {
            handleGatekeeperMessage(account, dialogId, msg);
        }

        if (senderId == 0) return;

        // 1. Explicit Mute Rule overrides everything (except whitelisted users, handled inside engine)
        String ruleAction = engine.getMuteActionFor(senderId, dialogId);
        if (ruleAction != null) {
            applyMuteAction(account, dialogId, senderId, msg.id, ruleAction);
            return;
        }

        if (!ColgramConfig.isSpamGuardEnabled()) return;
        if (isUserContact(account, senderId) || engine.isWhitelisted(senderId)) return;

        // 1-on-1 private DMs and pure broadcast channels are NEVER touched by automatic
        // group spam deletion, auto-read, or Saved Messages lead forwarding.
        boolean isGroup = dialogId < 0;
        if (!isGroup) return;
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = mc != null ? mc.getChat(-dialogId) : null;
        if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat)) {
            return;
        }

        String text = msg.message == null ? "" : msg.message;
        List<ColgramSpamGuard.MentionTarget> targets = extractMentionTargets(msg);
        String ownUname = ownUsername(account);
        List<String> own = ownLabels(account);
        String pingKind = classifyMessagePing(msg, selfId, ownUname, own, targets);
        boolean isPing = msg.mentioned || pingKind != null;
        boolean hiddenEntity = ColgramSpamGuard.hasHiddenOrMassMentionEntity(text, targets);
        boolean visualMedia = isVisualMedia(msg);
        boolean viaBot = msg.via_bot_id != 0;
        boolean inlineUrlBtns = hasInlineUrlButtons(msg);
        boolean fwdFromChannel = isForwardedFromChannel(msg);

        boolean allowLeadCheck = isPing && ColgramConfig.isSaveLeadsEnabled();
        ColgramSpamGuard.SpamReport report = ColgramSpamGuard.diagnose(
                text, "", pingKind, isPing, viaBot, visualMedia, hiddenEntity,
                allowLeadCheck, own, inlineUrlBtns, fwdFromChannel);

        if (report.isLead && allowLeadCheck) {
            saveLeadCopyToSavedMessages(account, dialogId, msg, report);
            return;
        }

        if (report.isSpam || report.shouldAutoRead) {
            synchronized (recentSuppressVerdicts) {
                recentSuppressVerdicts.put(dedupeKey, Boolean.TRUE);
            }
            engine.recordPingRpc(dialogId, "incoming_spam", true,
                    "score=" + report.score + " trigger=" + report.primaryTrigger);
            if (isPing) {
                markMessageIdsRead(account, dialogId, Collections.singletonList(msg.id));
            }
            applyMuteAction(account, dialogId, senderId, msg.id, ColgramConfig.getDefaultMuteMode());
        }
    }

    private static void startClearUnreadSpamPings(int account, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return;
        TLRPC.InputPeer inputPeer = mc.getInputPeer(dialogId);
        if (inputPeer == null) {
            showToast("Чат недоступен", "Откройте чат перед очисткой пингов");
            return;
        }
        showToast("Сканирую непрочитанные пинги…", "Ищу спам-упоминания без открытия переписки");
        fetchMentionsPage(account, dialogId, inputPeer, 0, 0, 0, 0, 0);
    }

    private static void fetchMentionsPage(int account, long dialogId, TLRPC.InputPeer peer,
                                          int offsetId, int page, int totalScanned, int totalCleared, int totalLeads) {
        if (page >= 10) {
            finishClearPings(account, dialogId, totalScanned, totalCleared, totalLeads);
            return;
        }
        TLRPC.TL_messages_getUnreadMentions req = new TLRPC.TL_messages_getUnreadMentions();
        req.peer = peer;
        req.offset_id = offsetId;
        req.limit = 100;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                if (error != null && error.text != null && error.text.toUpperCase(Locale.ROOT).contains("FLOOD_WAIT")) {
                    onFloodWait(account, dialogId, error.text);
                }
                ColgramColemailerEngine.getInstance().recordPingRpc(
                        dialogId, "unread_mentions", false, error != null ? error.text : "empty");
                AndroidUtilities.runOnUIThread(() ->
                        showToast("Ошибка RPC", error != null ? error.text : "Не удалось прочитать пинги"));
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            MessagesController mc = MessagesController.getInstance(account);
            mc.putUsers(res.users, true);
            mc.putChats(res.chats, true);

            if (res.messages.isEmpty()) {
                ColgramColemailerEngine.getInstance().recordPingRpc(
                        dialogId, "unread_mentions_done", true, "cleared=" + totalCleared);
                finishClearPings(account, dialogId, totalScanned, totalCleared, totalLeads);
                return;
            }

            long selfId = UserConfig.getInstance(account).getClientUserId();
            String ownUname = ownUsername(account);
            List<String> own = ownLabels(account);
            ArrayList<Integer> spamIds = new ArrayList<>();
            int leadsInPage = 0;
            int minId = Integer.MAX_VALUE;
            ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();

            for (TLRPC.Message m : res.messages) {
                if (m == null) continue;
                if (m.id < minId) minId = m.id;
                long sender = m.from_id != null ? MessageObject.getPeerId(m.from_id) : 0L;
                if (sender != 0 && (isUserContact(account, sender) || engine.isWhitelisted(sender))) {
                    continue;
                }
                List<ColgramSpamGuard.MentionTarget> targets = extractMentionTargets(m);
                String pingKind = classifyMessagePing(m, selfId, ownUname, own, targets);
                String txt = m.message == null ? "" : m.message;
                boolean allowLeads = dialogId < 0 && ColgramConfig.isSaveLeadsEnabled();
                ColgramSpamGuard.SpamReport report = ColgramSpamGuard.diagnose(
                        txt, "", pingKind, true, m.via_bot_id != 0, isVisualMedia(m),
                        ColgramSpamGuard.hasHiddenOrMassMentionEntity(txt, targets),
                        allowLeads, own, hasInlineUrlButtons(m), isForwardedFromChannel(m));
                if (report.isLead && allowLeads) {
                    saveLeadCopyToSavedMessages(account, dialogId, m, report);
                    leadsInPage++;
                    continue;
                }
                String forced = sender != 0 ? engine.getMuteActionFor(sender, dialogId) : null;
                if (forced != null || report.shouldAutoRead || report.isSpam) {
                    spamIds.add(m.id);
                }
            }

            if (!spamIds.isEmpty()) {
                markMessageIdsRead(account, dialogId, spamIds);
            }

            final int nextScanned = totalScanned + res.messages.size();
            final int nextCleared = totalCleared + spamIds.size();
            final int nextLeads = totalLeads + leadsInPage;
            if (res.messages.size() < 100 || minId == Integer.MAX_VALUE) {
                finishClearPings(account, dialogId, nextScanned, nextCleared, nextLeads);
            } else {
                final int nextOffset = minId;
                AndroidUtilities.runOnUIThread(() ->
                        fetchMentionsPage(account, dialogId, peer, nextOffset, page + 1, nextScanned, nextCleared, nextLeads), 250L);
            }
        });
    }

    private static void finishClearPings(int account, long dialogId, int scanned, int cleared, int leads) {
        AndroidUtilities.runOnUIThread(() -> {
            if (cleared >= scanned && scanned > 0 && leads == 0) {
                MessagesController.getInstance(account).markMentionsAsRead(dialogId, 0);
            }
            showToast("Пинги проверены: " + scanned,
                    "Спам-пингов снято: " + cleared + (leads > 0 ? ", лидов сохранено: " + leads : ""));
        });
    }

    private static void showPingDiagnostic(int account, long dialogId, MessageObject replyObj, String inlineArg) {
        String text = inlineArg;
        String pingKind = null;
        boolean viaBot = false;
        boolean visual = false;
        boolean hidden = false;
        boolean inlineUrlBtns = false;
        boolean fwdFromChannel = false;
        long senderId = 0L;
        if ((text == null || text.isEmpty()) && replyObj != null && replyObj.messageOwner != null) {
            TLRPC.Message m = replyObj.messageOwner;
            text = m.message == null ? "" : m.message;
            long selfId = UserConfig.getInstance(account).getClientUserId();
            List<ColgramSpamGuard.MentionTarget> targets = extractMentionTargets(m);
            pingKind = classifyMessagePing(m, selfId, ownUsername(account), ownLabels(account), targets);
            viaBot = m.via_bot_id != 0;
            visual = isVisualMedia(m);
            hidden = ColgramSpamGuard.hasHiddenOrMassMentionEntity(text, targets);
            inlineUrlBtns = hasInlineUrlButtons(m);
            fwdFromChannel = isForwardedFromChannel(m);
            senderId = replyObj.getSenderId();
        }
        if (TextUtils.isEmpty(text) && !inlineUrlBtns) {
            showAlert("Диагностика (.пингдиаг)",
                    "Ответьте на сообщение командой `.пингдиаг` или передайте текст после команды.\n\n"
                            + "Последние RPC события для чата:\n"
                            + ColgramColemailerEngine.getInstance().formatRecentRpcForDialog(dialogId));
            return;
        }
        ColgramSpamGuard.SpamReport rep = ColgramSpamGuard.diagnose(
                text, "", pingKind, pingKind != null || replyObj == null, viaBot, visual, hidden,
                true, ownLabels(account), inlineUrlBtns, fwdFromChannel);
        StringBuilder sb = new StringBuilder();
        sb.append("Скор: ").append(rep.score).append(" / ").append(ColgramSpamGuard.SPAM_THRESHOLD);
        sb.append(rep.isSpam ? " [СПАМ]" : (rep.isLead ? " [ЖИВОЙ ЛИД]" : " [ЧИСТО]")).append("\n");
        if (senderId != 0) sb.append("Отправитель ID: ").append(senderId).append("\n");
        if (pingKind != null) sb.append("Тип пинга: ").append(pingKind).append("\n");
        sb.append("Автопрочтение: ").append(rep.shouldAutoRead ? "да" : "нет").append("\n");
        if (!rep.obfuscationFlags.isEmpty()) {
            sb.append("Обфускация: ").append(rep.obfuscationFlags).append("\n");
        }
        sb.append("\nФакторы:\n");
        if (rep.factors.isEmpty()) sb.append("• (нет срабатываний)\n");
        else for (String f : rep.factors) sb.append("• ").append(f).append("\n");
        sb.append("\nRPC журнал:\n").append(ColgramColemailerEngine.getInstance().formatRecentRpcForDialog(dialogId));
        showAlert("Диагностика антиспама (.пингдиаг)", sb.toString());
    }

    private static void applyMuteAction(int account, long dialogId, long senderId, int msgId, String mode) {
        String normalized = ColgramColemailerEngine.normalizeMuteMode(mode);
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return;
        if (ColgramColemailerEngine.MUTE_SILENT.equals(normalized)) {
            if (senderId > 0L) {
                ColgramHookHandler.setNotificationsBlocked(senderId, true);
            }
            return;
        }
        if (ColgramColemailerEngine.MUTE_DELETE.equals(normalized)
                || ColgramColemailerEngine.MUTE_WARN.equals(normalized)
                || ColgramColemailerEngine.MUTE_BAN.equals(normalized)) {
            // Mark force-purged immediately before posting to UI thread so Anti-Delete never resurrects it.
            ColgramHookHandler.markForcePurged(dialogId, msgId);
            ArrayList<Integer> ids = new ArrayList<>(1);
            ids.add(msgId);
            AndroidUtilities.runOnUIThread(() -> {
                ColgramHookHandler.markForcePurged(dialogId, msgId);
                mc.deleteMessages(ids, null, null, dialogId, 0, true, 0);
                NotificationsController nc = NotificationsController.getInstance(account);
                if (nc != null) {
                    nc.removeNotificationsForDialog(dialogId);
                }
                if (ColgramColemailerEngine.MUTE_WARN.equals(normalized)) {
                    showToast("Спам-сообщение удалено", "Отправитель ID " + senderId);
                }
            });
        }
        if (ColgramColemailerEngine.MUTE_BAN.equals(normalized) && dialogId < 0) {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            if (chat != null && ChatObject.canBlockUsers(chat)) {
                if (ChatObject.isChannel(chat)) {
                    TLRPC.TL_channels_editBanned banReq = new TLRPC.TL_channels_editBanned();
                    banReq.channel = mc.getInputChannel(-dialogId);
                    banReq.participant = mc.getInputPeer(senderId);
                    banReq.banned_rights = new TLRPC.TL_chatBannedRights();
                    banReq.banned_rights.view_messages = true;
                    banReq.banned_rights.until_date = 0;
                    ConnectionsManager.getInstance(account).sendRequest(banReq, null);
                } else {
                    TLRPC.User u = mc.getUser(senderId);
                    if (u != null) mc.deleteParticipantFromChat(-dialogId, u);
                }
            }
        }
    }

    private static void applyQuietMode(int account, long dialogId) {
        SharedPreferences prefs = MessagesController.getNotificationsSettings(account);
        String key = NotificationsController.getSharedPrefKey(dialogId, 0);
        int prevNotify2 = prefs.getInt("notify2_" + key, -1);
        int prevUntil = prefs.getInt("notifyuntil_" + key, 0);
        boolean prevSilent = prefs.getBoolean("silent_" + key, false);
        boolean prevPreviews = prefs.getBoolean("content_preview_" + key, true);
        String prevSoundTitle = prefs.getString("sound_" + key, null);
        String prevSoundPath = prefs.getString("sound_path_" + key, null);
        long prevSoundDocId = prefs.getLong("sound_document_id_" + key, 0L);

        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        if (engine.getQuietSnapshot(dialogId) == null) {
            engine.saveQuietSnapshot(new ColgramColemailerEngine.QuietSnapshot(
                    dialogId, prevNotify2, prevUntil, prevSilent, prevPreviews,
                    prevSoundTitle, prevSoundPath, prevSoundDocId));
        }

        prefs.edit()
                .putInt("notify2_" + key, 2)
                .remove("notifyuntil_" + key)
                .putBoolean("silent_" + key, true)
                .putString("sound_path_" + key, "NoSound")
                .remove("sound_" + key)
                .remove("sound_document_id_" + key)
                .apply();

        MessagesStorage.getInstance(account).setDialogFlags(dialogId, 1L);
        TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
        if (dialog != null) {
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialog.notify_settings.mute_until = Integer.MAX_VALUE;
            dialog.notify_settings.silent = true;
        }
        NotificationsController nc = NotificationsController.getInstance(account);
        nc.removeNotificationsForDialog(dialogId);
        nc.updateServerNotificationsSettings(dialogId, 0, true);
        showToast("Тихий режим (.тишина)", "Снимок настроек сохранён. Вернуть: `.тишина выкл`");
    }

    private static void restoreQuietMode(int account, long dialogId) {
        ColgramColemailerEngine.QuietSnapshot snap = ColgramColemailerEngine.getInstance().removeQuietSnapshot(dialogId);
        if (snap == null) {
            NotificationsController.getInstance(account).setDialogNotificationsSettings(
                    dialogId, 0, NotificationsController.SETTING_MUTE_UNMUTE);
            showToast("Уведомления включены", "Для этого чата снимок не найден — включён стандартный звук");
            return;
        }
        SharedPreferences prefs = MessagesController.getNotificationsSettings(account);
        String key = NotificationsController.getSharedPrefKey(dialogId, 0);
        SharedPreferences.Editor ed = prefs.edit();
        if (snap.prevNotifyType == -1) ed.remove("notify2_" + key);
        else ed.putInt("notify2_" + key, snap.prevNotifyType);
        if (snap.prevMuteUntil <= 0) ed.remove("notifyuntil_" + key);
        else ed.putInt("notifyuntil_" + key, snap.prevMuteUntil);
        ed.putBoolean("silent_" + key, snap.prevSilent);
        ed.putBoolean("content_preview_" + key, snap.prevShowPreviews);
        if (snap.prevSoundDocId != 0L) ed.putLong("sound_document_id_" + key, snap.prevSoundDocId);
        else ed.remove("sound_document_id_" + key);
        if (snap.prevSoundPath != null && !snap.prevSoundPath.isEmpty()) ed.putString("sound_path_" + key, snap.prevSoundPath);
        else ed.remove("sound_path_" + key);
        if (snap.prevSoundTitle != null && !snap.prevSoundTitle.isEmpty()) ed.putString("sound_" + key, snap.prevSoundTitle);
        else ed.remove("sound_" + key);
        ed.apply();

        long restoredFlags = (snap.prevNotifyType == 2 || snap.prevNotifyType == 3)
                ? (((long) snap.prevMuteUntil << 32) | 1L) : 0L;
        MessagesStorage.getInstance(account).setDialogFlags(dialogId, restoredFlags);
        TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
        if (dialog != null) {
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialog.notify_settings.mute_until = snap.prevMuteUntil;
            dialog.notify_settings.silent = snap.prevSilent;
        }
        NotificationsController.getInstance(account).updateServerNotificationsSettings(dialogId, 0, true);
        showToast("Тихий режим снят", "Восстановлены точные звук и уведомления чата");
    }

    private static void markMessageIdsRead(int account, long dialogId, List<Integer> ids) {
        if (ids == null || ids.isEmpty() || dialogId == 0L) return;
        if (ColgramHookHandler.shouldPreventReadReceipt(dialogId)) return;
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return;
        if (dialogId < 0 && ChatObject.isChannel(mc.getChat(-dialogId))) {
            TLRPC.TL_channels_readMessageContents req = new TLRPC.TL_channels_readMessageContents();
            req.channel = mc.getInputChannel(-dialogId);
            req.id.addAll(ids);
            ConnectionsManager.getInstance(account).sendRequest(req, null);
        } else {
            TLRPC.TL_messages_readMessageContents req = new TLRPC.TL_messages_readMessageContents();
            req.id.addAll(ids);
            ConnectionsManager.getInstance(account).sendRequest(req, null);
        }
    }

    private static void saveLeadCopyToSavedMessages(int account, long fromDialogId,
                                                    TLRPC.Message msg, ColgramSpamGuard.SpamReport report) {
        if (!ColgramConfig.isSaveLeadsEnabled()) return;
        if (msg == null || msg.id <= 0 || msg.out || msg.noforwards) return;
        // Strictly allow forwarding verified group pings ONLY — never 1-on-1 DMs, Saved Messages,
        // Secret Chats, folders, or Telegram service chats.
        if (fromDialogId >= 0L || DialogObject.isEncryptedDialog(fromDialogId)
                || DialogObject.isFolderDialogId(fromDialogId)
                || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(fromDialogId)) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0L || fromDialogId == selfId) return;
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null || mc.isPeerNoForwards(fromDialogId)) return;
        TLRPC.InputPeer fromPeer = mc.getInputPeer(fromDialogId);
        TLRPC.InputPeer selfPeer = mc.getInputPeer(selfId);
        if (fromPeer == null || selfPeer == null
                || fromPeer instanceof TLRPC.TL_inputPeerEmpty
                || fromPeer instanceof TLRPC.TL_inputPeerSelf
                || selfPeer instanceof TLRPC.TL_inputPeerEmpty) {
            return;
        }

        TLRPC.TL_messages_forwardMessages fwd = new TLRPC.TL_messages_forwardMessages();
        fwd.from_peer = fromPeer;
        fwd.to_peer = selfPeer;
        fwd.id.add(msg.id);
        fwd.random_id.add(Utilities.random.nextLong());
        fwd.silent = true;
        ConnectionsManager.getInstance(account).sendRequest(fwd, null);
    }

    private static void handleGatekeeperMessage(int account, long dialogId, TLRPC.Message msg) {
        if (!(msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) return;
        TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.reply_markup;
        List<String> links = new ArrayList<>(ColgramColemailerEngine.extractChannelLinks(msg.message));
        TL_keyboard.KeyboardInlineButton verifyBtn = null;

        for (TL_keyboard.KeyboardInlineButtonRow row : markup.rows) {
            if (row == null) continue;
            for (TL_keyboard.KeyboardInlineButton btn : row.buttons) {
                if (btn == null) continue;
                String label = btn.text == null ? "" : btn.text;
                String url = btn.type instanceof TL_keyboard.TL_inlineButtonTypeUrl
                        ? ((TL_keyboard.TL_inlineButtonTypeUrl) btn.type).url : "";
                String cb = btn.type instanceof TL_keyboard.TL_inlineButtonTypeCallback
                        ? new String(((TL_keyboard.TL_inlineButtonTypeCallback) btn.type).data, StandardCharsets.UTF_8) : "";
                if (ColgramColemailerEngine.isUnsafeButton(label, url, cb)) continue;
                if (!url.isEmpty() && (ColgramColemailerEngine.isSubscribeButton(label) || url.contains("t.me/"))) {
                    links.addAll(ColgramColemailerEngine.extractChannelLinks(url));
                }
                if (verifyBtn == null && ColgramColemailerEngine.isVerifyButton(label)
                        && btn.type instanceof TL_keyboard.TL_inlineButtonTypeCallback
                        && !((TL_keyboard.TL_inlineButtonTypeCallback) btn.type).requires_password) {
                    verifyBtn = btn;
                }
            }
        }

        int joined = 0;
        for (String target : new LinkedHashSet<>(links)) {
            if (joined >= 2) break;
            joined++;
            joinSafeTarget(account, target);
        }
        if (verifyBtn != null && verifyBtn.type instanceof TL_keyboard.TL_inlineButtonTypeCallback) {
            final byte[] cbData = ((TL_keyboard.TL_inlineButtonTypeCallback) verifyBtn.type).data;
            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.TL_messages_getBotCallbackAnswer req = new TLRPC.TL_messages_getBotCallbackAnswer();
                req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
                req.msg_id = msg.id;
                req.game = false;
                if (cbData != null) {
                    req.flags |= 1;
                    req.data = cbData;
                }
                ConnectionsManager.getInstance(account).sendRequest(req, null);
                activeGatekeeperDialogs.add(accountKey(account, dialogId));
            }, joined > 0 ? 1500L : 400L);
        }
    }

    private static void joinSafeTarget(int account, String target) {
        if (TextUtils.isEmpty(target)) return;
        MessagesController mc = MessagesController.getInstance(account);
        if (target.startsWith("+")) {
            TLRPC.TL_messages_importChatInvite req = new TLRPC.TL_messages_importChatInvite();
            req.hash = target.substring(1);
            ConnectionsManager.getInstance(account).sendRequest(req, null);
        } else {
            TLRPC.TL_contacts_resolveUsername res = new TLRPC.TL_contacts_resolveUsername();
            res.username = target.replace("@", "");
            ConnectionsManager.getInstance(account).sendRequest(res, (response, error) -> {
                if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer rp = (TLRPC.TL_contacts_resolvedPeer) response;
                    mc.putUsers(rp.users, true);
                    mc.putChats(rp.chats, true);
                    for (TLRPC.Chat c : rp.chats) {
                        if (c != null && ChatObject.isChannel(c) && c.left) {
                            TLRPC.TL_channels_joinChannel join = new TLRPC.TL_channels_joinChannel();
                            join.channel = mc.getInputChannel(c);
                            ConnectionsManager.getInstance(account).sendRequest(join, null);
                            break;
                        }
                    }
                }
            });
        }
    }

    private static List<ColgramSpamGuard.MentionTarget> extractMentionTargets(TLRPC.Message msg) {
        if (msg == null || msg.entities == null || msg.entities.isEmpty()) return Collections.emptyList();
        List<ColgramSpamGuard.MentionTarget> out = new ArrayList<>();
        for (TLRPC.MessageEntity ent : msg.entities) {
            if (ent == null) continue;
            if (ent instanceof TLRPC.TL_messageEntityMentionName) {
                out.add(new ColgramSpamGuard.MentionTarget(
                        ((TLRPC.TL_messageEntityMentionName) ent).user_id, "", ent.offset, ent.length));
            } else if (ent instanceof TLRPC.TL_messageEntityTextUrl) {
                out.add(new ColgramSpamGuard.MentionTarget(
                        0L, ((TLRPC.TL_messageEntityTextUrl) ent).url, ent.offset, ent.length));
            } else if (ent instanceof TLRPC.TL_messageEntityMention) {
                out.add(new ColgramSpamGuard.MentionTarget(0L, "", ent.offset, ent.length));
            }
        }
        return out;
    }

    private static String classifyMessagePing(TLRPC.Message msg, long selfId, String ownUsername,
                                              List<String> own, List<ColgramSpamGuard.MentionTarget> targets) {
        if (msg == null) return null;
        // Telegram sets `msg.mentioned = true` ONLY when a reply actually targets OUR message
        // (not when stranger A replies to stranger B, and not on ordinary forum topic messages).
        if (msg.mentioned && msg.reply_to != null && msg.reply_to.reply_to_msg_id != 0) {
            return ColgramSpamGuard.PING_REPLY;
        }
        String txt = msg.message == null ? "" : msg.message;
        String entKind = ColgramSpamGuard.classifyEntityPing(txt, targets, selfId, own);
        if (entKind != null) return entKind;
        if (!TextUtils.isEmpty(ownUsername)) {
            String needle = "@" + ownUsername.toLowerCase(Locale.ROOT);
            String low = txt.toLowerCase(Locale.ROOT);
            int idx = low.indexOf(needle);
            while (idx >= 0) {
                boolean leftOk = idx == 0 || !isUsernameChar(low.charAt(idx - 1));
                int end = idx + needle.length();
                boolean rightOk = end >= low.length() || !isUsernameChar(low.charAt(end));
                if (leftOk && rightOk) {
                    return ColgramSpamGuard.PING_USERNAME;
                }
                idx = low.indexOf(needle, idx + 1);
            }
        }
        return msg.mentioned ? ColgramSpamGuard.PING_STEALTH : null;
    }

    private static boolean isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String ownUsername(int account) {
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) return "";
        String uname = UserObject.getPublicUsername(self);
        return TextUtils.isEmpty(uname) ? "" : uname;
    }

    private static List<String> ownLabels(int account) {
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String uname = UserObject.getPublicUsername(self);
        if (!TextUtils.isEmpty(uname)) out.add(uname);
        if (!TextUtils.isEmpty(self.first_name)) out.add(self.first_name);
        if (!TextUtils.isEmpty(self.last_name)) out.add(self.last_name);
        return out;
    }

    private static boolean isUserContact(int account, long userId) {
        TLRPC.User u = MessagesController.getInstance(account).getUser(userId);
        return u != null && (u.contact || u.mutual_contact);
    }

    private static boolean isVisualMedia(TLRPC.Message msg) {
        return msg.media instanceof TLRPC.TL_messageMediaPhoto || msg.media instanceof TLRPC.TL_messageMediaDocument;
    }

    private static boolean hasInlineUrlButtons(TLRPC.Message msg) {
        if (msg == null || !(msg.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) return false;
        TLRPC.TL_replyInlineMarkup markup = (TLRPC.TL_replyInlineMarkup) msg.reply_markup;
        if (markup.rows == null || markup.rows.isEmpty()) return false;
        for (TL_keyboard.KeyboardInlineButtonRow row : markup.rows) {
            if (row == null || row.buttons == null) continue;
            for (TL_keyboard.KeyboardInlineButton btn : row.buttons) {
                if (btn != null && btn.type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
                    String u = ((TL_keyboard.TL_inlineButtonTypeUrl) btn.type).url;
                    if (!TextUtils.isEmpty(u)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isForwardedFromChannel(TLRPC.Message msg) {
        if (msg == null || msg.fwd_from == null) return false;
        if (msg.fwd_from.channel_post != 0) return true;
        return msg.fwd_from.from_id != null && msg.fwd_from.from_id.channel_id != 0;
    }

    private static String toBotApiChatId(int account, long dialogId) {
        Boolean isChannelHint = null;
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (chat != null) {
                isChannelHint = ChatObject.isChannel(chat);
            }
        }
        long[] resolved = ColgramBotChatIdPolicy.resolveChatIds(dialogId, isChannelHint);
        long chosen = resolved != null && resolved.length > 0 ? resolved[0] : dialogId;
        return Long.toString(chosen);
    }

    private static String modeTitle(int mode) {
        if (mode == ColgramColemailerEngine.MODE_COUNTER) return "По сообщениям";
        if (mode == ColgramColemailerEngine.MODE_HYBRID) return "Гибрид";
        return "Интервал";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public static void showToast(String title, String subtitle) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BaseFragment frag = LaunchActivity.getLastFragment();
                BulletinFactory bf = (frag != null && frag.getParentActivity() != null && frag.getVisibleDialog() == null)
                        ? BulletinFactory.of(frag) : BulletinFactory.global();
                if (bf != null) {
                    bf.createSimpleBulletin(title, subtitle).show();
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void showAlert(String title, String body) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BaseFragment frag = LaunchActivity.getLastFragment();
                if (frag == null || frag.getParentActivity() == null) {
                    showToast(title, body);
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(frag.getParentActivity());
                builder.setTitle(title);

                ScrollView scroll = new ScrollView(frag.getParentActivity());
                LinearLayout box = new LinearLayout(frag.getParentActivity());
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = AndroidUtilities.dp(20);
                box.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(8));
                TextView tv = new TextView(frag.getParentActivity());
                tv.setText(body);
                tv.setTextSize(14);
                tv.setGravity(Gravity.START);
                tv.setTextIsSelectable(true);
                tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                box.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                scroll.addView(box);
                builder.setView(scroll);
                builder.setNeutralButton("Копировать", (d, w) -> {
                    AndroidUtilities.addToClipboard(body);
                    showToast("Скопировано", title);
                });
                builder.setPositiveButton("OK", null);
                frag.showDialog(builder.create());
            } catch (Throwable ignored) {}
        });
    }
}
