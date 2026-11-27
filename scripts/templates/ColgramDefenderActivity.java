package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramSecurityDefender;
import org.colgram.core.ColgramSecurityPolicy;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramDefenderActivity — Interactive console & autonomous guard for Colgram's built-in
 * Defender Bot ("Встроенный бот-защитник"), finding and auto-closing Telegram privacy/security leaks.
 */
public class ColgramDefenderActivity extends BaseFragment {

    private static final int MENU_SCAN = 1;
    private static final int MENU_CLEAN = 2;

    private static final int ROW_HEADER = 0;
    private static final int ROW_CHECK = 1;
    private static final int ROW_ACTION = 2;
    private static final int ROW_SHADOW = 3;
    private static final int ROW_INFO = 4;
    private static final int ROW_HERO = 5;

    private static final int ACT_NONE = 0;
    private static final int ACT_SCAN_NOW = 1;
    private static final int ACT_CLEAN_TRACES = 2;
    private static final int ACT_BOT_COMMAND = 3;
    private static final int ACT_TOGGLE_ENABLED = 4;
    private static final int ACT_TOGGLE_AUTO_CLOSE = 5;
    private static final int ACT_TOGGLE_VOIP_P2P = 6;
    private static final int ACT_TOGGLE_BOT_LOCATION = 7;
    private static final int ACT_TOGGLE_STRIP_TRACKERS = 8;
    private static final int ACT_TOGGLE_WEBVIEW_HARDEN = 9;
    private static final int ACT_FINDING_DETAIL = 10;

    private static final class Row {
        final int viewType;
        final String title;
        final String value;
        final boolean checked;
        final int actionId;
        final ColgramSecurityPolicy.AuditFinding finding;
        final int iconRes;
        final int iconColor;

        Row(int viewType, String title, String value, boolean checked, int actionId,
            ColgramSecurityPolicy.AuditFinding finding) {
            this(viewType, title, value, checked, actionId, finding, 0, 0);
        }

        Row(int viewType, String title, String value, boolean checked, int actionId,
            ColgramSecurityPolicy.AuditFinding finding, int iconRes, int iconColor) {
            this.viewType = viewType;
            this.title = title;
            this.value = value;
            this.checked = checked;
            this.actionId = actionId;
            this.finding = finding;
            this.iconRes = iconRes;
            this.iconColor = iconColor;
        }
    }

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private final List<Row> rows = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private boolean scanning;

    @Override
    public View createView(Context context) {
        ColgramConfig.init(context);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Бот-защитник Colgram");
        ColgramMenuChrome.styleActionBar(actionBar);

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SCAN, R.drawable.msg_retry).setContentDescription("Сканировать дыры сейчас");
        menu.addItem(MENU_CLEAN, R.drawable.msg_delete).setContentDescription("Очистить следы слежки");

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SCAN) {
                    runScanAsync("manual-menu");
                } else if (id == MENU_CLEAN) {
                    runCleanTracesAsync();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            switch (row.actionId) {
                case ACT_SCAN_NOW:
                    runScanAsync("manual-button");
                    break;
                case ACT_CLEAN_TRACES:
                    runCleanTracesAsync();
                    break;
                case ACT_BOT_COMMAND:
                    showCommandPrompt();
                    break;
                case ACT_TOGGLE_ENABLED: {
                    boolean next = !ColgramConfig.isDefenderEnabled();
                    ColgramConfig.setDefenderEnabled(next);
                    if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
                    runScanAsync("toggle-defender", true);
                    break;
                }
                case ACT_TOGGLE_AUTO_CLOSE: {
                    boolean next = !ColgramConfig.isDefenderAutoCloseEnabled();
                    ColgramConfig.setDefenderAutoCloseEnabled(next);
                    if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
                    runScanAsync("toggle-auto-close", true);
                    break;
                }
                case ACT_TOGGLE_VOIP_P2P: {
                    boolean next = !ColgramConfig.isDefenderBlockVoipP2pEnabled();
                    ColgramConfig.setDefenderBlockVoipP2pEnabled(next);
                    if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
                    runScanAsync("toggle-voip-p2p", true);
                    break;
                }
                case ACT_TOGGLE_BOT_LOCATION: {
                    boolean next = !ColgramConfig.isDefenderBlockBotLocationEnabled();
                    ColgramConfig.setDefenderBlockBotLocationEnabled(next);
                    if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
                    runScanAsync("toggle-bot-location", true);
                    break;
                }
                case ACT_TOGGLE_STRIP_TRACKERS: {
                    boolean next = !ColgramConfig.isDefenderStripTrackersEnabled();
                    ColgramConfig.setDefenderStripTrackersEnabled(next);
                    if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
                    runScanAsync("toggle-trackers", true);
                    break;
                }
                case ACT_TOGGLE_WEBVIEW_HARDEN: {
                    boolean next = !ColgramConfig.isDefenderWebViewHardeningEnabled();
                    ColgramConfig.setDefenderWebViewHardeningEnabled(next);
                    if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
                    runScanAsync("toggle-webview", true);
                    break;
                }
                case ACT_FINDING_DETAIL:
                    if (row.finding != null) showFindingDialog(row.finding);
                    break;
                default:
                    break;
            }
        });

        rebuildRows();
        if (ColgramSecurityDefender.getLatestReport() == null) {
            runScanAsync("screen-open");
        }
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onFragmentDestroy();
    }

    private void runScanAsync(String trigger) {
        runScanAsync(trigger, false);
    }

    private void runScanAsync(String trigger, boolean silentStart) {
        if (scanning) return;
        scanning = true;
        if (!silentStart) {
            rebuildRows();
        } else if (listAdapter != null && !rows.isEmpty()) {
            listAdapter.notifyItemChanged(0);
        }
        final Context ctx = getParentActivity() != null ? getParentActivity().getApplicationContext() : getContext();
        executor.execute(() -> {
            ColgramSecurityDefender.AuditReport report = ColgramSecurityDefender.runFullAuditAndHeal(ctx, trigger);
            mainHandler.post(() -> {
                scanning = false;
                if (destroyed) return;
                rebuildRows();
                if (getParentActivity() != null && trigger != null && trigger.startsWith("manual")) {
                    ColgramUiKit.showNotice(this, R.drawable.outline_shield_check,
                            "Защита: " + report.scorePercent + "% · закрыто " + report.closedCount + "/" + report.findings.size()
                                    + (report.autoFixedNow > 0 ? " (исправлено: " + report.autoFixedNow + ")" : ""),
                            false);
                }
            });
        });
    }

    private void runCleanTracesAsync() {
        final Context ctx = getParentActivity() != null ? getParentActivity().getApplicationContext() : getContext();
        executor.execute(() -> {
            int count = ColgramSecurityDefender.cleanTrackingRemnants(ctx);
            ColgramSecurityDefender.runFullAuditAndHeal(ctx, "manual-clean");
            mainHandler.post(() -> {
                if (destroyed) return;
                rebuildRows();
                if (getParentActivity() != null) {
                    ColgramUiKit.showNotice(this, R.drawable.msg_clearcache,
                            "Следы слежки очищены: " + count, false);
                }
            });
        });
    }

    private void showCommandPrompt() {
        Activity ctx = getParentActivity();
        if (ctx == null) return;
        ColgramUiKit.showInputSheet(
                ctx,
                resourceProvider,
                R.drawable.msg_bots,
                "Команда Боту-защитнику",
                "Доступно также прямо в любом чате (сообщение не отправляется собеседнику): .defender, .защита, .clean",
                ".defender / .clean / /scan",
                ".defender",
                android.text.InputType.TYPE_CLASS_TEXT,
                false,
                false,
                true,
                "Выполнить",
                rawCmd -> {
                    String cmd = rawCmd != null ? rawCmd : ".defender";
                    executor.execute(() -> {
                        String reply = ColgramSecurityDefender.handleCommand(ctx.getApplicationContext(), cmd);
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            rebuildRows();
                            Activity act = getParentActivity();
                            if (act != null) {
                                ColgramUiKit.showInfoSheet(
                                        act,
                                        resourceProvider,
                                        R.drawable.msg_bots,
                                        "Ответ Бота-защитника",
                                        reply,
                                        "ОК",
                                        null,
                                        null,
                                        null,
                                        true
                                );
                            }
                        });
                    });
                },
                null,
                null
        );
    }

    private void showFindingDialog(ColgramSecurityPolicy.AuditFinding finding) {
        Activity ctx = getParentActivity();
        if (ctx == null) return;
        String msg = "Категория: " + finding.category
                + "\nСтатус: " + finding.statusBadge()
                + "\n\n" + finding.detail;
        if (finding.status == ColgramSecurityPolicy.STATUS_WARNING) {
            ColgramUiKit.showConfirmSheet(
                    ctx,
                    resourceProvider,
                    R.drawable.msg_secret,
                    finding.title,
                    msg,
                    "Закрыть автоматом",
                    false,
                    () -> {
                        ColgramConfig.setDefenderEnabled(true);
                        ColgramConfig.setDefenderAutoCloseEnabled(true);
                        runScanAsync("manual-heal-item");
                    },
                    LocaleController.getString(R.string.Cancel),
                    null
            );
        } else {
            ColgramUiKit.showInfoSheet(
                    ctx,
                    resourceProvider,
                    R.drawable.msg_secret,
                    finding.title,
                    msg,
                    "ОК",
                    null,
                    null,
                    null,
                    false
            );
        }
    }

    private void rebuildRows() {
        rows.clear();
        ColgramSecurityDefender.AuditReport report = ColgramSecurityDefender.getLatestReport();

        String statusHeadline;
        if (scanning) {
            statusHeadline = "Сканирование дыр и утечек Telegram…";
        } else if (report != null) {
            String when = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(report.timestampMs));
            statusHeadline = "Защита " + report.scorePercent + "% (" + report.closedCount + "/"
                    + report.findings.size() + ") • проверка в " + when;
        } else {
            statusHeadline = "Готов к проверке всех дыр Telegram";
        }

        rows.add(new Row(ROW_HERO, statusHeadline,
                report != null
                        ? "Закрыто " + report.closedCount + " из " + report.findings.size() + " векторов угроз"
                        : "Непрерывная защита Telegram и WebView",
                false, ACT_NONE, null));

        rows.add(new Row(ROW_HEADER, "Состояние Бота-защитника", null, false, ACT_NONE, null));
        rows.add(new Row(ROW_ACTION, "Индекс безопасности", statusHeadline, false, ACT_SCAN_NOW, null,
                R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_EMERALD));
        rows.add(new Row(ROW_ACTION, "Запустить сканирование и авто-лечение",
                scanning ? "идёт проверка…" : "проверить и закрыть все утечки", false, ACT_SCAN_NOW, null,
                R.drawable.msg_search, ColgramMenuChrome.COLOR_BLUE));
        rows.add(new Row(ROW_ACTION, "Очистить следы слежки WebView и логов",
                "удалить cookies/кеш WebView, скрыть токены в логах", false, ACT_CLEAN_TRACES, null,
                R.drawable.msg_clearcache, ColgramMenuChrome.COLOR_AMBER));
        rows.add(new Row(ROW_ACTION, "Выполнить команду бота (.defender / .clean)",
                "работает в том числе из любого чата", false, ACT_BOT_COMMAND, null,
                R.drawable.msg_bots, ColgramMenuChrome.COLOR_VIOLET));
        rows.add(new Row(ROW_INFO,
                "Авто-исправлений: " + ColgramConfig.getDefenderFixedTotal()
                        + " • Вырезано UTM/трекеров: " + ColgramConfig.getDefenderTrackersStripped()
                        + " • WebRTC P2P блокировок: " + ColgramConfig.getDefenderP2pBlocked(),
                null, false, ACT_NONE, null));

        rows.add(new Row(ROW_HEADER, "Автоматические щиты от утечек", null, false, ACT_NONE, null));
        rows.add(new Row(ROW_CHECK, "Встроенный Бот-защитник (24/7 фоновый страж)", null,
                ColgramConfig.isDefenderEnabled(), ACT_TOGGLE_ENABLED, null,
                R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_CRIMSON));
        rows.add(new Row(ROW_CHECK, "Закрывать найденные дыры автоматически", null,
                ColgramConfig.isDefenderAutoCloseEnabled(), ACT_TOGGLE_AUTO_CLOSE, null,
                R.drawable.msg_retry, ColgramMenuChrome.COLOR_EMERALD));
        rows.add(new Row(ROW_CHECK, "Блокировать WebRTC P2P утечку IP в звонках", null,
                ColgramConfig.isDefenderBlockVoipP2pEnabled(), ACT_TOGGLE_VOIP_P2P, null,
                R.drawable.settings_calls, ColgramMenuChrome.COLOR_BLUE));
        rows.add(new Row(ROW_CHECK, "Запретить Mini App доступ к геолокации", null,
                ColgramConfig.isDefenderBlockBotLocationEnabled(), ACT_TOGGLE_BOT_LOCATION, null,
                R.drawable.msg_secret, ColgramMenuChrome.COLOR_INDIGO));
        rows.add(new Row(ROW_CHECK, "Вырезать UTM / fbclid / yclid трекеры в ссылках", null,
                ColgramConfig.isDefenderStripTrackersEnabled(), ACT_TOGGLE_STRIP_TRACKERS, null,
                R.drawable.msg_link, ColgramMenuChrome.COLOR_CYAN));
        rows.add(new Row(ROW_CHECK, "Изоляция WebView и маскировка User-Agent", null,
                ColgramConfig.isDefenderWebViewHardeningEnabled(), ACT_TOGGLE_WEBVIEW_HARDEN, null,
                R.drawable.settings_privacy, ColgramMenuChrome.COLOR_TEAL));
        rows.add(new Row(ROW_SHADOW, null, null, false, ACT_NONE, null));

        rows.add(new Row(ROW_HEADER, "Закрытые дыры и аудит систем ("
                + (report != null ? report.findings.size() : 0) + ")", null, false, ACT_NONE, null));
        if (report != null) {
            for (ColgramSecurityPolicy.AuditFinding f : report.findings) {
                int fColor = f.status == ColgramSecurityPolicy.STATUS_SEALED
                        ? ColgramMenuChrome.COLOR_EMERALD
                        : f.status == ColgramSecurityPolicy.STATUS_AUTO_FIXED
                                ? ColgramMenuChrome.COLOR_BLUE
                                : ColgramMenuChrome.COLOR_AMBER;
                int fIcon = f.status == ColgramSecurityPolicy.STATUS_SEALED
                        ? R.drawable.outline_shield_check
                        : f.status == ColgramSecurityPolicy.STATUS_AUTO_FIXED
                                ? R.drawable.msg_secret
                                : R.drawable.msg_info;
                rows.add(new Row(ROW_ACTION, f.title, f.statusBadge() + " · " + f.category, false,
                        ACT_FINDING_DETAIL, f, fIcon, fColor));
            }
        } else {
            rows.add(new Row(ROW_ACTION, "Ожидание первого сканирования…", "нажмите для запуска", false,
                    ACT_SCAN_NOW, null, R.drawable.msg_search, ColgramMenuChrome.COLOR_SLATE));
        }

        List<ColgramSecurityDefender.BotEvent> feed = ColgramSecurityDefender.getBotFeed();
        if (!feed.isEmpty()) {
            rows.add(new Row(ROW_SHADOW, null, null, false, ACT_NONE, null));
            rows.add(new Row(ROW_HEADER, "Лента Бота-защитника", null, false, ACT_NONE, null));
            int start = Math.max(0, feed.size() - 8);
            for (int i = feed.size() - 1; i >= start; i--) {
                ColgramSecurityDefender.BotEvent ev = feed.get(i);
                rows.add(new Row(ROW_ACTION,
                        ev.fromUser ? "Команда: " + ev.message : ev.message,
                        new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(ev.timestampMs)),
                        false, ACT_NONE, null, R.drawable.msg_info, ColgramMenuChrome.COLOR_INDIGO));
            }
        }
        rows.add(new Row(ROW_SHADOW, null, null, false, ACT_NONE, null));

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos < 0 || pos >= rows.size()) return false;
            return rows.get(pos).actionId != ACT_NONE;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= rows.size()) return ROW_SHADOW;
            return rows.get(position).viewType;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case ROW_HERO:
                    view = new ColgramMenuChrome.HeroBannerCell(context);
                    break;
                case ROW_HEADER:
                    view = new HeaderCell(context, resourceProvider);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
                    break;
                case ROW_CHECK:
                    view = new TextCheckCell(context, resourceProvider);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
                    break;
                case ROW_ACTION:
                    view = new TextSettingsCell(context, resourceProvider);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
                    break;
                case ROW_INFO:
                    view = new TextInfoPrivacyCell(context, resourceProvider);
                    break;
                case ROW_SHADOW:
                default:
                    view = new ShadowSectionCell(context, resourceProvider);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            boolean divider = position + 1 < rows.size()
                    && (rows.get(position + 1).viewType == ROW_CHECK || rows.get(position + 1).viewType == ROW_ACTION);
            switch (holder.getItemViewType()) {
                case ROW_HERO: {
                    ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                    ColgramSecurityDefender.AuditReport r = ColgramSecurityDefender.getLatestReport();
                    int accent = scanning
                            ? ColgramMenuChrome.COLOR_CYAN
                            : (r != null && r.scorePercent >= 90 ? ColgramMenuChrome.COLOR_EMERALD : ColgramMenuChrome.COLOR_CRIMSON);
                    String pill = scanning ? "Проверка…" : (r != null ? r.scorePercent + "%" : "");
                    long stripped = ColgramConfig.getDefenderTrackersStripped();
                    hero.bind(R.drawable.outline_shield_check, accent,
                            "Защита Colgram", row.value, pill,
                            "Исправлено: " + ColgramConfig.getDefenderFixedTotal(),
                            stripped > 0 ? ("Трекеры: -" + stripped) : "Трекеры: 0",
                            "P2P блок: " + ColgramConfig.getDefenderP2pBlocked());
                    break;
                }
                case ROW_HEADER: {
                    HeaderCell hc = (HeaderCell) holder.itemView;
                    hc.setText(row.title);
                    ColgramMenuChrome.styleHeader(hc, 0xFF047857, 0xFF34D399);
                    break;
                }
                case ROW_CHECK: {
                    TextCheckCell tc = (TextCheckCell) holder.itemView;
                    ColgramMenuChrome.applyCheckIcon(tc, row.iconColor, row.iconRes);
                    tc.setTextAndCheck(row.title, row.checked, divider);
                    break;
                }
                case ROW_ACTION: {
                    TextSettingsCell ts = (TextSettingsCell) holder.itemView;
                    ColgramMenuChrome.applySettingIcon(ts, row.iconRes, row.iconColor);
                    ts.setTextAndValue(row.title, row.value == null ? "" : row.value, divider);
                    break;
                }
                case ROW_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                case ROW_SHADOW:
                default:
                    break;
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
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultSelector));
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
