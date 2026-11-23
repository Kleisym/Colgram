package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramAntiSpam;
import org.colgram.core.ColgramColemailerEngine;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramHookHandler;
import org.colgram.core.ColgramSpamGuard;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ColgramNativeColemailerBridge;
import org.telegram.messenger.ColgramNotificationListenerService;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unified UI screen for Colgram Anti-Spam Guard (ping cleaner, notification filter, lead capture)
 * and embedded Colemailer v3.4.19 broadcaster studio, rotations, mute rules, and chat commands.
 */
public class ColgramAntiSpamActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_SETTING = 2;
    private static final int TYPE_INFO = 3;
    private static final int TYPE_SHADOW = 4;
    private static final int TYPE_HERO = 10;

    private static final int ACT_NOTIF_PERMISSION = 1;
    private static final int ACT_DEFAULT_MUTE_MODE = 2;
    private static final int ACT_TEST_DETECTOR = 3;
    private static final int ACT_MUTE_RULES = 4;
    private static final int ACT_WHITELIST = 5;
    private static final int ACT_QUIET_CHATS = 6;
    private static final int ACT_NEW_CAMPAIGN = 7;
    private static final int ACT_ROTATIONS = 8;
    private static final int ACT_BOT_TOKEN = 9;
    private static final int ACT_SYNC_FOLDERS = 10;
    private static final int ACT_CHEATSHEET = 11;
    private static final int ACT_LEGACY_USERBOT = 12;

    private static final int CHK_SPAM_GUARD = 101;
    private static final int CHK_NOTIF_FILTER = 102;
    private static final int CHK_SAVE_LEADS = 103;
    private static final int CHK_BROADCASTER = 104;
    private static final int CHK_AUTO_SUB = 105;

    private static final class RowItem {
        final int type;
        final int id;
        final String title;
        final String value;
        final boolean checked;
        final String campaignName;

        RowItem(int type, int id, String title, String value, boolean checked, String campaignName) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.value = value;
            this.checked = checked;
            this.campaignName = campaignName;
        }
    }

    private final List<RowItem> rows = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter listAdapter;

    @Override
    public View createView(Context context) {
        ColgramConfig.init(context);
        ColgramNativeColemailerBridge.startScheduler();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Colgram Антиспам & Colemailer");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        rebuildRows(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            handleRowClick(context, rows.get(position), view, position);
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getParentActivity() != null) {
            rebuildRows(getParentActivity());
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        }
    }

    private void refreshList(Context ctx) {
        rebuildRows(ctx);
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void rebuildRows(Context context) {
        rows.clear();
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        boolean notifGranted = ColgramNotificationListenerService.isPermissionGranted(context);

        rows.add(new RowItem(TYPE_HERO, 0, "Антиспам & Colemailer", null, false, null));
        rows.add(new RowItem(TYPE_HEADER, 0, "Антиспам-щит и фильтр пингов", null, false, null));
        rows.add(new RowItem(TYPE_CHECK, CHK_SPAM_GUARD, "3-уровневый детектор спам-пингов", null, ColgramConfig.isSpamGuardEnabled(), null));
        rows.add(new RowItem(TYPE_CHECK, CHK_NOTIF_FILTER, "Автоочистка спам-уведомлений (Android)", null, ColgramConfig.isNotificationAdFilterEnabled(), null));
        rows.add(new RowItem(TYPE_SETTING, ACT_NOTIF_PERMISSION, "Доступ к уведомлениям Android",
                notifGranted ? "Выдан" : "Выдать (нажать)", false, null));
        rows.add(new RowItem(TYPE_CHECK, CHK_SAVE_LEADS, "Сохранять живые лиды в Избранное", null, ColgramConfig.isSaveLeadsEnabled(), null));
        rows.add(new RowItem(TYPE_SETTING, ACT_DEFAULT_MUTE_MODE, "Режим реакции на спам",
                formatMuteMode(ColgramConfig.getDefaultMuteMode()), false, null));
        rows.add(new RowItem(TYPE_SETTING, ACT_TEST_DETECTOR, "Проверить текст в детекторе (песочница)", "Скоринг", false, null));
        rows.add(new RowItem(TYPE_INFO, 0,
                "Перехватывает скрытые @-упоминания, омоглифы, спам-прайсы и пробелы по буквам. Личные 1-на-1 чаты, коды входа и реальные вопросы покупателей не затрагиваются.",
                null, false, null));

        rows.add(new RowItem(TYPE_HEADER, 0, "Муты, белый список и .тишина", null, false, null));
        rows.add(new RowItem(TYPE_SETTING, ACT_MUTE_RULES, "Правила .мутить (пользователи / чаты)",
                String.valueOf(engine.listMuteRules().size()), false, null));
        rows.add(new RowItem(TYPE_SETTING, ACT_WHITELIST, "Белый список (неприкосновенные ID)",
                String.valueOf(engine.listWhitelist().size()), false, null));
        rows.add(new RowItem(TYPE_SETTING, ACT_QUIET_CHATS, "Сохранённые снимки (.тишина)",
                String.valueOf(engine.listQuietSnapshots().size()), false, null));
        rows.add(new RowItem(TYPE_SHADOW, 0, null, null, false, null));

        rows.add(new RowItem(TYPE_HEADER, 0, "Студия рассылок (Colemailer v3.4.19)", null, false, null));
        rows.add(new RowItem(TYPE_CHECK, CHK_BROADCASTER, "Включить фоновый планировщик рассылок", null, ColgramConfig.isBroadcasterEnabled(), null));
        rows.add(new RowItem(TYPE_SETTING, ACT_NEW_CAMPAIGN, "+ Добавить кампанию рассылки", "Мастер", false, null));

        List<ColgramColemailerEngine.BroadcastCampaign> campaigns = engine.listCampaigns();
        for (ColgramColemailerEngine.BroadcastCampaign c : campaigns) {
            String status = c.enabled ? "ВКЛ" : "ПАУЗА";
            String modeDesc;
            if (c.mode == ColgramColemailerEngine.MODE_COUNTER) {
                modeDesc = c.counterThreshold + " смс";
            } else if (c.mode == ColgramColemailerEngine.MODE_HYBRID) {
                modeDesc = formatInterval(c.intervalSec) + " + " + c.counterThreshold + " смс";
            } else {
                modeDesc = formatInterval(c.intervalSec);
            }
            String val = status + " · " + modeDesc + " · " + c.targetChats.size() + " ч.";
            if (!c.buttons.isEmpty()) val += " · кнопки";
            rows.add(new RowItem(TYPE_SETTING, 0, "Кампания: " + c.name, val, false, c.name));
        }

        rows.add(new RowItem(TYPE_SETTING, ACT_ROTATIONS, "Ротации постов по чатам",
                String.valueOf(engine.listRotations().size()), false, null));
        String tok = ColgramConfig.getHelperBotToken();
        rows.add(new RowItem(TYPE_SETTING, ACT_BOT_TOKEN, "Helper Bot Token (inline-кнопки Bot API)",
                TextUtils.isEmpty(tok) ? "Не задан (текст+ссылки)" : maskToken(tok), false, null));
        rows.add(new RowItem(TYPE_SETTING, ACT_SYNC_FOLDERS, "Синхронизировать папки Telegram сейчас", null, false, null));
        rows.add(new RowItem(TYPE_INFO, 0,
                "Режимы: интервал (15м), счётчик сообщений (20 сообщений) и гибрид (оба условия со сбросом застоя). Inline-кнопки в формате [Сайт](url) || [Клик](cb:data).",
                null, false, null));

        rows.add(new RowItem(TYPE_HEADER, 0, "Auto-Sub Gatekeeper и команды", null, false, null));
        rows.add(new RowItem(TYPE_CHECK, CHK_AUTO_SUB, "Auto-Sub (проверка подписок ботов)", null, ColgramConfig.isAutoSubEnabled(), null));
        rows.add(new RowItem(TYPE_SETTING, ACT_CHEATSHEET, "Шпаргалка команд в чатах (.мутить, .пингдиаг…)", "Открыть", false, null));
        int acct = UserConfig.selectedAccount;
        boolean userbotConfigured = !ColgramAntiSpam.getApiId(context, acct).isEmpty()
                && !ColgramAntiSpam.getApiHash(context, acct).isEmpty()
                && !ColgramAntiSpam.getPhone(context, acct).isEmpty();
        String userbotState = ColgramAntiSpam.isEnabled(context, acct)
                ? "Включён"
                : (userbotConfigured ? "Настроен" : "Выкл");
        rows.add(new RowItem(TYPE_SETTING, ACT_LEGACY_USERBOT, "Внешний Python Telethon Userbot (опц.)",
                userbotState, false, null));
        rows.add(new RowItem(TYPE_INFO, 0,
                "Auto-Sub никогда не нажимает кнопки бана/выхода/удаления/startapp и ограничивает подписку на 2 безопасные ссылки на проверку.",
                null, false, null));
    }

    private void handleRowClick(Context ctx, RowItem item, View view, int position) {
        if (item.campaignName != null) {
            showCampaignControlDialog(ctx, item.campaignName);
            return;
        }
        switch (item.id) {
            case CHK_SPAM_GUARD: {
                boolean next = !ColgramConfig.isSpamGuardEnabled();
                ColgramConfig.setSpamGuardEnabled(next);
                updateCheckRow(ctx, view, position, next, true);
                break;
            }
            case CHK_NOTIF_FILTER: {
                boolean next = !ColgramConfig.isNotificationAdFilterEnabled();
                ColgramConfig.setNotificationAdFilterEnabled(next);
                updateCheckRow(ctx, view, position, next, false);
                if (next && !ColgramNotificationListenerService.isPermissionGranted(ctx)) {
                    promptNotificationListenerPermission(ctx);
                }
                break;
            }
            case ACT_NOTIF_PERMISSION:
                ColgramNotificationListenerService.openNotificationListenerSettings(ctx);
                break;
            case CHK_SAVE_LEADS: {
                boolean next = !ColgramConfig.isSaveLeadsEnabled();
                ColgramConfig.setSaveLeadsEnabled(next);
                updateCheckRow(ctx, view, position, next, false);
                break;
            }
            case ACT_DEFAULT_MUTE_MODE:
                showMuteModePicker(ctx);
                break;
            case ACT_TEST_DETECTOR:
                showDetectorSandboxDialog(ctx);
                break;
            case ACT_MUTE_RULES:
                showMuteRulesDialog(ctx);
                break;
            case ACT_WHITELIST:
                showWhitelistDialog(ctx);
                break;
            case ACT_QUIET_CHATS:
                showQuietChatsDialog(ctx);
                break;
            case CHK_BROADCASTER: {
                boolean next = !ColgramConfig.isBroadcasterEnabled();
                ColgramConfig.setBroadcasterEnabled(next);
                if (next) {
                    ColgramNativeColemailerBridge.startScheduler();
                    ColgramNativeColemailerBridge.syncFoldersIntoEngine();
                }
                updateCheckRow(ctx, view, position, next, false);
                break;
            }
            case ACT_NEW_CAMPAIGN:
                showCampaignEditorDialog(ctx, null);
                break;
            case ACT_ROTATIONS:
                showRotationsDialog(ctx);
                break;
            case ACT_BOT_TOKEN:
                showBotTokenDialog(ctx);
                break;
            case ACT_SYNC_FOLDERS:
                ColgramNativeColemailerBridge.syncFoldersIntoEngine();
                Toast.makeText(ctx, "Папки Telegram синхронизированы с кампаниями", Toast.LENGTH_SHORT).show();
                refreshList(ctx);
                break;
            case CHK_AUTO_SUB: {
                boolean next = !ColgramConfig.isAutoSubEnabled();
                ColgramConfig.setAutoSubEnabled(next);
                updateCheckRow(ctx, view, position, next, false);
                break;
            }
            case ACT_CHEATSHEET:
                showCheatsheetDialog();
                break;
            case ACT_LEGACY_USERBOT:
                showLegacyUserbotDialog(ctx);
                break;
        }
    }

    private void updateCheckRow(Context ctx, View view, int position, boolean next, boolean updateHero) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(next);
        }
        rebuildRows(ctx);
        if (updateHero && listAdapter != null && !rows.isEmpty()) {
            listAdapter.notifyItemChanged(0);
        }
    }

    private void promptNotificationListenerPermission(Context ctx) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Доступ к уведомлениям");
        b.setMessage("Чтобы автоматически смахивать рекламные уведомления из групп на Android 9+, разрешите сервис «Colgram Антиспам» в системных настройках.");
        b.setPositiveButton("Открыть настройки", (d, w) -> ColgramNotificationListenerService.openNotificationListenerSettings(ctx));
        b.setNegativeButton("Позже", null);
        showDialog(b.create());
    }

    private void showMuteModePicker(Context ctx) {
        final String[] labels = {
                "Тихо (отключить уведомления)",
                "Удалить спам-сообщение",
                "Удалить и предупредить локально",
                "Удалить + забанить (для админов)"
        };
        final String[] modes = {
                ColgramColemailerEngine.MUTE_SILENT,
                ColgramColemailerEngine.MUTE_DELETE,
                ColgramColemailerEngine.MUTE_WARN,
                ColgramColemailerEngine.MUTE_BAN
        };
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Действие при срабатывании .мутить / спам-гарда");
        b.setItems(labels, (d, which) -> {
            ColgramConfig.setDefaultMuteMode(modes[which]);
            refreshList(ctx);
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void showDetectorSandboxDialog(Context ctx) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Проверка детектора антиспама");

        LinearLayout box = makeDialogContainer(ctx);
        EditText input = makeStyledEdit(ctx, "Вставьте подозрительное сообщение…", true);
        box.addView(input);

        b.setView(box);
        b.setPositiveButton("Проверить", (d, w) -> {
            String sample = input.getText().toString();
            ColgramSpamGuard.SpamReport r = ColgramSpamGuard.diagnose(
                    sample, "", ColgramSpamGuard.PING_USERNAME, true, false, false, false, true, Collections.emptyList());
            String msg = "Скор: " + r.score + " / " + ColgramSpamGuard.SPAM_THRESHOLD
                    + (r.isSpam ? " [СПАМ]" : (r.isLead ? " [ЛИД — сохранён]" : " [ЧИСТО]"))
                    + "\nАвтопрочтение: " + (r.shouldAutoRead ? "да" : "нет")
                    + "\nОбфускация: " + r.obfuscationFlags
                    + "\n\nФакторы:\n" + (r.factors.isEmpty() ? "• нет" : "• " + TextUtils.join("\n• ", r.factors));
            ColgramNativeColemailerBridge.showAlert("Результат скоринга", msg);
        });
        b.setNegativeButton("Закрыть", null);
        showDialog(b.create());
    }

    private void showMuteRulesDialog(Context ctx) {
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        List<ColgramColemailerEngine.MuteRule> rules = engine.listMuteRules();
        if (rules.isEmpty()) {
            AlertDialog.Builder b = new AlertDialog.Builder(ctx);
            b.setTitle("Правила .мутить");
            b.setMessage("Список пуст. Ответьте на сообщение в чате командой:\n.мутить [тихо|удалить|варн|бан] [чат]");
            b.setPositiveButton("Добавить по ID", (d, w) -> showAddMuteRuleDialog(ctx));
            b.setNegativeButton("OK", null);
            showDialog(b.create());
            return;
        }
        String[] items = new String[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            ColgramColemailerEngine.MuteRule r = rules.get(i);
            items[i] = (r.wholeChat ? ("Весь чат " + r.scopeChatId) : ("ID " + r.senderId + (r.scopeChatId == 0 ? " (везде)" : " в " + r.scopeChatId)))
                    + " -> " + formatMuteMode(r.mode) + " (удалить)";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Активные правила мута (" + rules.size() + ")");
        b.setItems(items, (d, which) -> {
            ColgramColemailerEngine.MuteRule r = rules.get(which);
            engine.removeMuteRule(r.senderId, r.scopeChatId, r.wholeChat);
            if (!r.wholeChat && r.senderId != 0) ColgramHookHandler.setNotificationsBlocked(r.senderId, false);
            refreshList(ctx);
        });
        b.setNeutralButton("+ По ID", (d, w) -> showAddMuteRuleDialog(ctx));
        b.setNegativeButton("Закрыть", null);
        showDialog(b.create());
    }

    private void showAddMuteRuleDialog(Context ctx) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Замутить по Telegram ID");
        LinearLayout box = makeDialogContainer(ctx);
        EditText idEdit = makeStyledEdit(ctx, "User ID (или -100... для чата)", false);
        idEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        EditText modeEdit = makeStyledEdit(ctx, "Режим: silent / delete / warn / ban", false);
        modeEdit.setText(ColgramConfig.getDefaultMuteMode());
        box.addView(idEdit);
        box.addView(modeEdit);
        b.setView(box);
        b.setPositiveButton("Добавить", (d, w) -> {
            try {
                long id = Long.parseLong(idEdit.getText().toString().trim());
                String mode = ColgramColemailerEngine.normalizeMuteMode(modeEdit.getText().toString());
                boolean whole = id < 0;
                ColgramColemailerEngine.getInstance().setMuteRule(whole ? 0L : id, whole ? id : 0L, whole, mode);
                if (!whole) ColgramHookHandler.setNotificationsBlocked(id, true);
                refreshList(ctx);
            } catch (NumberFormatException ignored) {
                Toast.makeText(ctx, "Введите корректный числовой ID", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void showWhitelistDialog(Context ctx) {
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        List<Long> wl = engine.listWhitelist();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Белый список (" + wl.size() + ")");
        if (!wl.isEmpty()) {
            String[] items = new String[wl.size()];
            for (int i = 0; i < wl.size(); i++) items[i] = "ID " + wl.get(i) + " (нажмите чтобы убрать)";
            b.setItems(items, (d, which) -> {
                engine.removeWhitelist(wl.get(which));
                refreshList(ctx);
            });
        } else {
            b.setMessage("Пока никого нет. Можно добавить в чате командой `.вайтлист` ответом или по ID ниже.");
        }
        b.setPositiveButton("+ Добавить ID", (d, w) -> {
            LinearLayout box = makeDialogContainer(ctx);
            EditText ed = makeStyledEdit(ctx, "Telegram User ID", false);
            ed.setInputType(InputType.TYPE_CLASS_NUMBER);
            box.addView(ed);
            showDialog(new AlertDialog.Builder(ctx).setTitle("Добавить в белый список").setView(box)
                    .setPositiveButton("OK", (d2, w2) -> {
                        try {
                            long uid = Long.parseLong(ed.getText().toString().trim());
                            engine.addWhitelist(uid);
                            refreshList(ctx);
                        } catch (NumberFormatException ignored) {}
                    }).setNegativeButton("Отмена", null).create());
        });
        b.setNegativeButton("Закрыть", null);
        showDialog(b.create());
    }

    private void showQuietChatsDialog(Context ctx) {
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        List<ColgramColemailerEngine.QuietSnapshot> snaps = engine.listQuietSnapshots();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Снимки .тишина (" + snaps.size() + ")");
        if (snaps.isEmpty()) {
            b.setMessage("Нет активных тихих чатов.\nОтправьте `.тишина` в любом чате — прежние настройки звука будут сохранены.");
            b.setPositiveButton("OK", null);
            showDialog(b.create());
            return;
        }
        String[] items = new String[snaps.size()];
        for (int i = 0; i < snaps.size(); i++) {
            items[i] = "Чат " + snaps.get(i).dialogId + " — восстановить звук (.тишина выкл)";
        }
        b.setItems(items, (d, which) -> {
            long did = snaps.get(which).dialogId;
            ColgramNativeColemailerBridge.handleChatCommand(did, 0, ".тишина выкл", 0L);
            refreshList(ctx);
        });
        b.setNegativeButton("Закрыть", null);
        showDialog(b.create());
    }

    private void showCampaignEditorDialog(Context ctx, ColgramColemailerEngine.BroadcastCampaign existing) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(existing == null ? "Новая кампания рассылки" : "Кампания: " + existing.name);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout box = makeDialogContainer(ctx);

        EditText nameEt = makeStyledEdit(ctx, "Имя (например: promo_1)", false);
        if (existing != null) {
            nameEt.setText(existing.name);
            nameEt.setEnabled(false);
        }

        // Collect live Telegram folders to help autocomplete/select
        List<String> folderNames = getTelegramFolderNames();
        EditText folderEt = makeStyledEdit(ctx, "Папка Telegram (пусто = из .рассыл)"
                + (folderNames.isEmpty() ? "" : ", напр.: " + folderNames.get(0)), false);
        if (existing != null) folderEt.setText(existing.folderName);

        TextView folderHint = new TextView(ctx);
        folderHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        folderHint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        folderHint.setText(folderNames.isEmpty()
                ? "Папки Telegram не найдены (используйте команду `.рассыл` прямо в нужном чате)"
                : "Доступные папки (нажмите для подстановки): " + TextUtils.join(", ", folderNames));
        folderHint.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        if (!folderNames.isEmpty()) {
            folderHint.setOnClickListener(v -> showDialog(new AlertDialog.Builder(ctx)
                    .setTitle("Выберите папку Telegram")
                    .setItems(folderNames.toArray(new String[0]), (d, which) -> folderEt.setText(folderNames.get(which)))
                    .create()));
        }

        EditText schedEt = makeStyledEdit(ctx, "Расписание: 15м | 20 сообщений | 1ч 15 сообщений", false);
        if (existing != null) {
            if (existing.mode == ColgramColemailerEngine.MODE_COUNTER) schedEt.setText(existing.counterThreshold + " сообщений");
            else if (existing.mode == ColgramColemailerEngine.MODE_HYBRID) schedEt.setText(formatInterval(existing.intervalSec) + " " + existing.counterThreshold + " сообщений");
            else schedEt.setText(formatInterval(existing.intervalSec));
        } else {
            schedEt.setText("15м");
        }

        EditText bodyEt = makeStyledEdit(ctx, "Текст поста (опционально внизу кнопки:\n[Сайт](https://example.com) || [Клик](cb:buy))", true);
        if (existing != null) {
            String full = existing.text;
            if (!existing.buttons.isEmpty()) {
                StringBuilder btns = new StringBuilder();
                for (List<ColgramColemailerEngine.ButtonSpec> row : existing.buttons) {
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) btns.append(" || ");
                        ColgramColemailerEngine.ButtonSpec bs = row.get(i);
                        btns.append("[").append(bs.text).append("](")
                                .append("callback".equals(bs.type) ? "cb:" + bs.value : bs.value).append(")");
                    }
                    btns.append("\n");
                }
                full = full + "\n\n" + btns.toString().trim();
            }
            bodyEt.setText(full);
        }

        CheckBox noPreviewCb = new CheckBox(ctx);
        noPreviewCb.setText("Отключить предпросмотр ссылок");
        noPreviewCb.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        noPreviewCb.setChecked(existing != null && existing.disablePreview);

        box.addView(nameEt);
        box.addView(folderEt);
        box.addView(folderHint);
        box.addView(schedEt);
        box.addView(bodyEt);
        box.addView(noPreviewCb);
        scroll.addView(box);
        b.setView(scroll);

        b.setPositiveButton("Сохранить", (d, w) -> {
            String name = nameEt.getText().toString().trim();
            String rawPayload = bodyEt.getText().toString().trim();
            if (name.isEmpty() || rawPayload.isEmpty()) {
                Toast.makeText(ctx, "Укажите имя и текст поста", Toast.LENGTH_SHORT).show();
                return;
            }
            ColgramColemailerEngine.ScheduleSpec spec = ColgramColemailerEngine.parseSchedule(schedEt.getText().toString());
            ColgramColemailerEngine.ParsedPost parsed = ColgramColemailerEngine.extractButtonsFromPost(rawPayload);
            ColgramColemailerEngine.BroadcastCampaign camp = new ColgramColemailerEngine.BroadcastCampaign(
                    name, parsed.body, spec.mode, spec.intervalSec, spec.counterThreshold, folderEt.getText().toString().trim());
            camp.disablePreview = noPreviewCb.isChecked();
            camp.buttons.addAll(parsed.buttons);
            if (existing != null) {
                camp.enabled = existing.enabled;
                camp.targetChats.addAll(existing.targetChats);
            }
            ColgramColemailerEngine.getInstance().addOrUpdateCampaign(camp);
            ColgramNativeColemailerBridge.syncFoldersIntoEngine();
            ColgramNativeColemailerBridge.startScheduler();
            refreshList(ctx);
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void showCampaignControlDialog(Context ctx, String campaignName) {
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        ColgramColemailerEngine.BroadcastCampaign c = engine.getCampaign(campaignName);
        if (c == null) return;
        String[] actions = {
                c.enabled ? "Поставить на паузу" : "Включить кампанию",
                "Отправить сейчас (тест во все чаты)",
                "Редактировать текст / кнопки / папку",
                "Удалить кампанию"
        };
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Кампания «" + c.name + "» (" + c.targetChats.size() + " чатов)");
        b.setItems(actions, (d, which) -> {
            if (which == 0) {
                engine.setCampaignEnabled(c.name, !c.enabled);
                refreshList(ctx);
            } else if (which == 1) {
                ColgramNativeColemailerBridge.triggerCampaignNow(c.name);
            } else if (which == 2) {
                showCampaignEditorDialog(ctx, c);
            } else if (which == 3) {
                engine.removeCampaign(c.name);
                refreshList(ctx);
            }
        });
        b.setNegativeButton("Закрыть", null);
        showDialog(b.create());
    }

    private void showRotationsDialog(Context ctx) {
        ColgramColemailerEngine engine = ColgramColemailerEngine.getInstance();
        List<ColgramColemailerEngine.RotationRule> list = engine.listRotations();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Ротации кампаний (" + list.size() + ")");
        if (!list.isEmpty()) {
            String[] items = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                ColgramColemailerEngine.RotationRule r = list.get(i);
                items[i] = r.name + ": " + TextUtils.join(" -> ", r.campaignNames) + " (удалить)";
            }
            b.setItems(items, (d, which) -> {
                engine.removeRotation(list.get(which).name);
                refreshList(ctx);
            });
        } else {
            b.setMessage("Ротация позволяет чередовать разные посты по кругу внутри чатов.");
        }
        b.setPositiveButton("+ Создать", (d, w) -> {
            LinearLayout box = makeDialogContainer(ctx);
            EditText nameEt = makeStyledEdit(ctx, "Имя ротации (например: main)", false);
            EditText campsEt = makeStyledEdit(ctx, "Кампании через запятую (camp_1, camp_2)", false);
            box.addView(nameEt);
            box.addView(campsEt);
            showDialog(new AlertDialog.Builder(ctx).setTitle("Новая ротация").setView(box)
                    .setPositiveButton("Сохранить", (d2, w2) -> {
                        String rn = nameEt.getText().toString().trim();
                        String[] parts = campsEt.getText().toString().split(",");
                        List<String> clean = new ArrayList<>();
                        for (String p : parts) {
                            String t = p.trim();
                            if (!t.isEmpty()) clean.add(t);
                        }
                        if (!rn.isEmpty() && !clean.isEmpty()) {
                            engine.setRotation(rn, clean);
                            refreshList(ctx);
                        }
                    }).setNegativeButton("Отмена", null).create());
        });
        b.setNegativeButton("Закрыть", null);
        showDialog(b.create());
    }

    private void showBotTokenDialog(Context ctx) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("Helper Bot Token (опционально)");
        LinearLayout box = makeDialogContainer(ctx);
        EditText ed = makeStyledEdit(ctx, "123456:ABC-DEF... (пусто = без бота)", false);
        ed.setText(ColgramConfig.getHelperBotToken());
        box.addView(ed);
        b.setView(box);
        b.setPositiveButton("Сохранить", (d, w) -> {
            ColgramConfig.setHelperBotToken(ed.getText().toString());
            refreshList(ctx);
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void showCheatsheetDialog() {
        String text =
                "• .очиститьпинги (.clearpings)\nСнимает спам-упоминания в открытом чате, не трогая личные вопросы и лиды.\n\n"
                        + "• .пингдиаг [текст]\nЛокальная диагностика скоринга и факторов на отвеченном сообщении (без чтения истории и утечки в чат).\n\n"
                        + "• .тишина / .тишина выкл\nЗапоминает точные настройки звука чата и восстанавливает их при выключении.\n\n"
                        + "• .мутить [тихо|удалить|варн|бан] [чат]\nВключает правило автореакции ответом на сообщение (или в личке).\n\n"
                        + "• .размутить / .мутлист / .вайтлист\nУправление мутом и неприкосновенным белым списком.\n\n"
                        + "• .кнопки [Текст](https://...) || [Клик](cb:data)\nСохраняет черновик inline-кнопок (`.кнопки тест` / `.кнопки очистить`).\n\n"
                        + "• .рассыл Имя 15м | Текст\nСоздаёт/обновляет рассылку в текущий чат (поддержка `20 сообщений`, `1ч 15 сообщений`).\n\n"
                        + "• .стоп [Имя] / .продолжить [Имя] / .синк\nПауза, запуск и синхронизация папок Telegram.";
        ColgramNativeColemailerBridge.showAlert("Шпаргалка команд Colemailer", text);
    }

    private void showLegacyUserbotDialog(Context ctx) {
        final int acct = UserConfig.selectedAccount;
        AlertDialog.Builder b = new AlertDialog.Builder(ctx, resourceProvider);
        b.setTitle("Внешний Telethon Userbot (опционально)");
        LinearLayout box = makeDialogContainer(ctx);
        EditText apiId = makeStyledEdit(ctx, "API ID", false);
        apiId.setText(ColgramAntiSpam.getApiId(ctx, acct));
        EditText apiHash = makeStyledEdit(ctx, "API Hash", false);
        apiHash.setText(ColgramAntiSpam.getApiHash(ctx, acct));
        EditText phone = makeStyledEdit(ctx, "Телефон (+7...)", false);
        phone.setText(ColgramAntiSpam.getPhone(ctx, acct));
        EditText password = makeStyledEdit(ctx, "2FA пароль (если есть)", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(ColgramAntiSpam.getPassword(ctx, acct));
        EditText codeEt = makeStyledEdit(ctx, "Код входа из Telegram/SMS (после запуска)", false);
        box.addView(apiId);
        box.addView(apiHash);
        box.addView(phone);
        box.addView(password);
        box.addView(codeEt);
        b.setView(ColgramMenuChrome.wrapDialogScroll(ctx, box));
        b.setPositiveButton("Сохранить / Код", (d, w) -> {
            String enteredCode = codeEt.getText().toString().trim();
            String pass = password.getText().toString();
            ColgramAntiSpam.saveCredentials(ctx, acct,
                    apiId.getText().toString().trim(),
                    apiHash.getText().toString().trim(),
                    phone.getText().toString().trim(),
                    pass);
            if (!enteredCode.isEmpty()) {
                new Thread(() -> {
                    boolean ok = ColgramAntiSpam.submitCredentials(enteredCode, pass);
                    AndroidUtilities.runOnUIThread(() -> Toast.makeText(ctx,
                            ok ? "Код отправлен userbot" : "Не удалось отправить код userbot", Toast.LENGTH_SHORT).show());
                }, "colgram-userbot-code").start();
            }
            refreshList(ctx);
        });
        boolean running = ColgramAntiSpam.isEnabled(ctx, acct);
        b.setNeutralButton(running ? "Остановить" : "Запустить", (d, w) -> {
            ColgramAntiSpam.saveCredentials(ctx, acct,
                    apiId.getText().toString().trim(),
                    apiHash.getText().toString().trim(),
                    phone.getText().toString().trim(),
                    password.getText().toString());
            Toast.makeText(ctx, running ? "Останавливаю userbot..." : "Запускаю userbot...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String err = running ? ColgramAntiSpam.stop(ctx, acct) : ColgramAntiSpam.start(ctx, acct);
                AndroidUtilities.runOnUIThread(() -> {
                    Toast.makeText(ctx, err != null ? err : (running ? "Userbot остановлен" : "Запуск запрошен"),
                            Toast.LENGTH_SHORT).show();
                    refreshList(ctx);
                });
            }, "colgram-userbot-toggle").start();
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private List<String> getTelegramFolderNames() {
        List<String> out = new ArrayList<>();
        try {
            ArrayList<MessagesController.DialogFilter> filters =
                    MessagesController.getInstance(UserConfig.selectedAccount).getDialogFilters();
            if (filters != null) {
                for (MessagesController.DialogFilter f : filters) {
                    if (f != null && !TextUtils.isEmpty(f.name) && f.id != 0) {
                        out.add(f.name);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static LinearLayout makeDialogContainer(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(18);
        box.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(4));
        return box;
    }

    private EditText makeStyledEdit(Context ctx, String hint, boolean multiline) {
        EditText et = ColgramMenuChrome.makeDialogEditText(ctx, resourceProvider, hint, multiline);
        if (multiline) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = AndroidUtilities.dp(8);
        et.setLayoutParams(lp);
        return et;
    }

    private static String formatMuteMode(String mode) {
        String n = ColgramColemailerEngine.normalizeMuteMode(mode);
        if (ColgramColemailerEngine.MUTE_DELETE.equals(n)) return "Удалять";
        if (ColgramColemailerEngine.MUTE_WARN.equals(n)) return "Удалять + варн";
        if (ColgramColemailerEngine.MUTE_BAN.equals(n)) return "Удалять + бан";
        return "Тихо (без пушей)";
    }

    private static String formatInterval(long sec) {
        if (sec >= 3600L && sec % 3600L == 0) return (sec / 3600L) + "ч";
        if (sec >= 60L && sec % 60L == 0) return (sec / 60L) + "м";
        return sec + "с";
    }

    private static String maskToken(String token) {
        if (token.length() <= 10) return "***";
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int t = holder.getItemViewType();
            return t == TYPE_CHECK || t == TYPE_SETTING;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HERO:
                    view = new ColgramMenuChrome.HeroBannerCell(mContext);
                    break;
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_SETTING:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            RowItem item = rows.get(position);
            boolean divider = position + 1 < rows.size()
                    && (rows.get(position + 1).type == TYPE_CHECK || rows.get(position + 1).type == TYPE_SETTING);
            switch (item.type) {
                case TYPE_HERO: {
                    ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                    ColgramColemailerEngine eng = ColgramColemailerEngine.getInstance();
                    boolean guardOn = ColgramConfig.isSpamGuardEnabled();
                    hero.bind(R.drawable.msg_policy,
                            guardOn ? ColgramMenuChrome.COLOR_CRIMSON : ColgramMenuChrome.COLOR_SLATE,
                            "Антиспам и Colemailer",
                            "Фильтрация спам-пингов и рассылки по папкам",
                            guardOn ? "Активен" : "Выкл",
                            "Муты: " + eng.listMuteRules().size(),
                            "Белый список: " + eng.listWhitelist().size(),
                            "Кампании: " + eng.listCampaigns().size());
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell hc = (HeaderCell) holder.itemView;
                    hc.setText(item.title);
                    ColgramMenuChrome.styleHeader(hc, 0xFFB91C1C, 0xFFF87171);
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell tc = (TextCheckCell) holder.itemView;
                    ColgramMenuChrome.applyCheckIcon(tc, colorFor(item), iconFor(item));
                    tc.setTextAndCheck(item.title, item.checked, divider);
                    break;
                }
                case TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    ColgramMenuChrome.applySettingIcon(cell, iconFor(item), colorFor(item));
                    if (!TextUtils.isEmpty(item.value)) {
                        cell.setTextAndValue(item.title, item.value, divider);
                    } else {
                        cell.setText(item.title, divider);
                    }
                    break;
                }
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(item.title);
                    break;
                default:
                    break;
            }
        }

        private int iconFor(RowItem item) {
            if (item.campaignName != null) return R.drawable.msg_send;
            switch (item.id) {
                case CHK_SPAM_GUARD: return R.drawable.outline_shield_check;
                case CHK_NOTIF_FILTER: return R.drawable.msg_block;
                case CHK_SAVE_LEADS: return R.drawable.msg_archive;
                case CHK_BROADCASTER: return R.drawable.msg_send;
                case CHK_AUTO_SUB: return R.drawable.msg_bots;
                case ACT_NOTIF_PERMISSION: return R.drawable.msg_info;
                case ACT_DEFAULT_MUTE_MODE: return R.drawable.msg_policy;
                case ACT_TEST_DETECTOR: return R.drawable.msg_search;
                case ACT_MUTE_RULES: return R.drawable.msg_block;
                case ACT_WHITELIST: return R.drawable.outline_shield_check;
                case ACT_QUIET_CHATS: return R.drawable.msg_folders;
                case ACT_NEW_CAMPAIGN: return R.drawable.msg_add;
                case ACT_ROTATIONS: return R.drawable.msg_retry;
                case ACT_BOT_TOKEN: return R.drawable.msg_bot;
                case ACT_SYNC_FOLDERS: return R.drawable.msg_folders;
                case ACT_CHEATSHEET: return R.drawable.msg_info;
                case ACT_LEGACY_USERBOT: return R.drawable.settings_devices;
                default: return R.drawable.msg_policy;
            }
        }

        private int colorFor(RowItem item) {
            if (item.campaignName != null) return ColgramMenuChrome.COLOR_AMBER;
            switch (item.id) {
                case CHK_SPAM_GUARD: return ColgramMenuChrome.COLOR_CRIMSON;
                case CHK_NOTIF_FILTER: return ColgramMenuChrome.COLOR_ROSE;
                case CHK_SAVE_LEADS: return ColgramMenuChrome.COLOR_AMBER;
                case CHK_BROADCASTER: return ColgramMenuChrome.COLOR_EMERALD;
                case CHK_AUTO_SUB: return ColgramMenuChrome.COLOR_CYAN;
                case ACT_NOTIF_PERMISSION: return ColgramMenuChrome.COLOR_BLUE;
                case ACT_DEFAULT_MUTE_MODE: return ColgramMenuChrome.COLOR_INDIGO;
                case ACT_TEST_DETECTOR: return ColgramMenuChrome.COLOR_VIOLET;
                case ACT_MUTE_RULES: return ColgramMenuChrome.COLOR_CRIMSON;
                case ACT_WHITELIST: return ColgramMenuChrome.COLOR_EMERALD;
                case ACT_QUIET_CHATS: return ColgramMenuChrome.COLOR_SLATE;
                case ACT_NEW_CAMPAIGN: return ColgramMenuChrome.COLOR_TEAL;
                case ACT_ROTATIONS: return ColgramMenuChrome.COLOR_CYAN;
                case ACT_BOT_TOKEN: return ColgramMenuChrome.COLOR_BLUE;
                case ACT_SYNC_FOLDERS: return ColgramMenuChrome.COLOR_INDIGO;
                case ACT_CHEATSHEET: return ColgramMenuChrome.COLOR_AMBER;
                case ACT_LEGACY_USERBOT: return ColgramMenuChrome.COLOR_VIOLET;
                default: return ColgramMenuChrome.COLOR_SLATE;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR,
                null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR,
                null, null, null, null, Theme.key_listSelector));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER,
                new Class[]{TextInfoPrivacyCell.class, ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR,
                new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{ColgramMenuChrome.HeroBannerCell.class}, null, null, () -> {
            if (listView != null) {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View ch = listView.getChildAt(i);
                    if (ch instanceof ColgramMenuChrome.HeroBannerCell) {
                        ((ColgramMenuChrome.HeroBannerCell) ch).updateTheme();
                    }
                }
            }
        }, Theme.key_windowBackgroundWhite));
        return list;
    }
}
