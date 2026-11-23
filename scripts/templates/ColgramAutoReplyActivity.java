package org.telegram.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramAutoReplyPolicy;
import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * ColgramAutoReplyActivity — Telegram Business-style Auto-Responder configuration:
 * templates ({name}, {first_name}, {username}, {time}), work/away schedule,
 * audience filter, blacklist, bot-loop protection, and cooldown.
 */
public class ColgramAutoReplyActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private int rowCount;

    private int heroRow;
    private int mainHeaderRow;
    private int enabledRow;
    private int textRow;
    private int audienceRow;
    private int groupsRow;
    private int botsRow;
    private int cooldownRow;
    private int blacklistRow;
    private int mainSectionRow;

    private int scheduleHeaderRow;
    private int scheduleEnabledRow;
    private int onlyWhenAwayRow;
    private int startTimeRow;
    private int endTimeRow;
    private int awayTextRow;
    private int previewRow;
    private int scheduleSectionRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramConfig.init(getParentActivity());
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        heroRow = rowCount++;
        mainHeaderRow = rowCount++;
        enabledRow = rowCount++;
        textRow = rowCount++;
        audienceRow = rowCount++;
        groupsRow = rowCount++;
        botsRow = rowCount++;
        cooldownRow = rowCount++;
        blacklistRow = rowCount++;
        mainSectionRow = rowCount++;

        scheduleHeaderRow = rowCount++;
        scheduleEnabledRow = rowCount++;
        onlyWhenAwayRow = rowCount++;
        startTimeRow = rowCount++;
        endTimeRow = rowCount++;
        awayTextRow = rowCount++;
        previewRow = rowCount++;
        scheduleSectionRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Автоответчик (Business)");
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

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position == enabledRow) {
                boolean val = !ColgramConfig.isAutoReplyEnabled();
                ColgramConfig.setAutoReplyEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(heroRow);
                    listAdapter.notifyItemRangeChanged(textRow, scheduleSectionRow - textRow);
                }
            } else if (position == groupsRow) {
                boolean val = !ColgramConfig.isAutoReplyInGroups();
                ColgramConfig.setAutoReplyInGroups(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
            } else if (position == botsRow) {
                boolean val = !ColgramConfig.isAutoReplyToBots();
                ColgramConfig.setAutoReplyToBots(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
            } else if (position == textRow) {
                showTextEditor(false);
            } else if (position == audienceRow) {
                showAudiencePicker();
            } else if (position == cooldownRow) {
                showCooldownPicker();
            } else if (position == blacklistRow) {
                showBlacklistEditor();
            } else if (position == scheduleEnabledRow) {
                boolean val = !ColgramConfig.isAutoReplyScheduleEnabled();
                ColgramConfig.setAutoReplyScheduleEnabled(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(heroRow);
                    listAdapter.notifyItemRangeChanged(onlyWhenAwayRow, previewRow - onlyWhenAwayRow);
                }
            } else if (position == onlyWhenAwayRow) {
                boolean val = !ColgramConfig.isAutoReplyOnlyWhenAway();
                ColgramConfig.setAutoReplyOnlyWhenAway(val);
                if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(val);
            } else if (position == startTimeRow) {
                pickTime(true);
            } else if (position == endTimeRow) {
                pickTime(false);
            } else if (position == awayTextRow) {
                showTextEditor(true);
            } else if (position == previewRow) {
                showTemplatePreview();
            }
        });

        return fragmentView;
    }

    private void showTextEditor(boolean awayMode) {
        Context context = getParentActivity();
        if (context == null) return;
        final EditTextBoldCursor input = ColgramMenuChrome.makeDialogEditText(
                context, resourceProvider, "Переменные: {name}, {first_name}, {username}, {time}", true);
        input.setText(awayMode ? ColgramConfig.getAutoReplyAwayText() : ColgramConfig.getAutoReplyText());
        input.setSelection(input.getText().length());

        int pad = AndroidUtilities.dp(18);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(4));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        showDialog(new AlertDialog.Builder(context, resourceProvider)
                .setTitle(awayMode ? "Текст вне графика (Нет на месте)" : "Шаблон автоответа")
                .setMessage("Доступные теги: {name}, {first_name}, {username}, {time}")
                .setView(container)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        if (awayMode) ColgramConfig.setAutoReplyAwayText(text);
                        else ColgramConfig.setAutoReplyText(text);
                        if (listAdapter != null) {
                            listAdapter.notifyItemChanged(awayMode ? awayTextRow : textRow);
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void showAudiencePicker() {
        Context context = getParentActivity();
        if (context == null) return;
        String[] labels = {
                "Все чаты",
                "Только контакты",
                "Кроме контактов (незнакомцы)",
                "Только личные чаты"
        };
        showDialog(new AlertDialog.Builder(context, resourceProvider)
                .setTitle("Кому отвечать")
                .setItems(labels, (d, which) -> {
                    ColgramConfig.setAutoReplyAudience(which);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(heroRow);
                        listAdapter.notifyItemChanged(audienceRow);
                    }
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void showCooldownPicker() {
        Context context = getParentActivity();
        if (context == null) return;
        final int[] options = {0, 60, 300, 900, 3600, 86400};
        final String[] labels = {
                "Без паузы (на каждое сообщение)",
                "1 минута",
                "5 минут",
                "15 минут",
                "1 час",
                "24 часа"
        };
        showDialog(new AlertDialog.Builder(context, resourceProvider)
                .setTitle("Пауза на один чат")
                .setItems(labels, (d, which) -> {
                    ColgramConfig.setAutoReplyCooldownSec(options[which]);
                    if (listAdapter != null) listAdapter.notifyItemChanged(cooldownRow);
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void showBlacklistEditor() {
        Context context = getParentActivity();
        if (context == null) return;
        final EditTextBoldCursor input = ColgramMenuChrome.makeDialogEditText(
                context, resourceProvider, "ID чатов через запятую (напр. 123456, -100987654)", false);
        input.setText(ColgramConfig.getAutoReplyBlacklist());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = AndroidUtilities.dp(18);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(4));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        showDialog(new AlertDialog.Builder(context, resourceProvider)
                .setTitle("Исключения (чёрный список ID)")
                .setView(container)
                .setPositiveButton("Сохранить", (d, w) -> {
                    ColgramConfig.setAutoReplyBlacklist(input.getText().toString().trim());
                    if (listAdapter != null && blacklistRow >= 0) listAdapter.notifyItemChanged(blacklistRow);
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void pickTime(boolean isStart) {
        Context context = getParentActivity();
        if (context == null) return;
        int cur = isStart ? ColgramConfig.getAutoReplyStartMin() : ColgramConfig.getAutoReplyEndMin();
        int themeRes = Theme.isCurrentThemeDark()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new TimePickerDialog(context, themeRes, (view, hourOfDay, minute) -> {
            int val = hourOfDay * 60 + minute;
            if (isStart) ColgramConfig.setAutoReplyStartMin(val);
            else ColgramConfig.setAutoReplyEndMin(val);
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(heroRow);
                listAdapter.notifyItemChanged(isStart ? startTimeRow : endTimeRow);
            }
        }, cur / 60, cur % 60, true).show();
    }

    private void showTemplatePreview() {
        Context context = getParentActivity();
        if (context == null) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int m = cal.get(java.util.Calendar.MINUTE);
        String chosen = ColgramAutoReplyPolicy.selectReplyText(
                ColgramConfig.isAutoReplyScheduleEnabled(),
                ColgramConfig.isAutoReplyOnlyWhenAway(),
                ColgramConfig.getAutoReplyStartMin(),
                ColgramConfig.getAutoReplyEndMin(),
                h * 60 + m,
                ColgramConfig.getAutoReplyText(),
                ColgramConfig.getAutoReplyAwayText());
        String preview = chosen == null
                ? "Сейчас активен рабочий период графика — автоответ в этот час отправляться не будет."
                : ColgramAutoReplyPolicy.renderTemplate(chosen, "Иван Петров", "ivan_demo", h, m);
        showDialog(new AlertDialog.Builder(context, resourceProvider)
                .setTitle("Предпросмотр ответа")
                .setMessage(preview)
                .setPositiveButton("ОК", null)
                .create());
    }

    private String audienceLabel(int mode) {
        switch (mode) {
            case ColgramAutoReplyPolicy.AUDIENCE_CONTACTS:
                return "Только контакты";
            case ColgramAutoReplyPolicy.AUDIENCE_NON_CONTACTS:
                return "Незнакомцы (кроме контактов)";
            case ColgramAutoReplyPolicy.AUDIENCE_PRIVATE_ONLY:
                return "Только личные чаты";
            default:
                return "Все чаты";
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int p = holder.getAdapterPosition();
            return p != heroRow && p != mainHeaderRow && p != scheduleHeaderRow
                    && p != mainSectionRow && p != scheduleSectionRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroRow) return 10;
            if (position == mainHeaderRow || position == scheduleHeaderRow) return 0;
            if (position == enabledRow || position == groupsRow || position == botsRow
                    || position == scheduleEnabledRow || position == onlyWhenAwayRow) return 1;
            if (position == mainSectionRow || position == scheduleSectionRow) return 2;
            return 3;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view;
            if (viewType == 10) {
                view = new ColgramMenuChrome.HeroBannerCell(context);
                return new RecyclerListView.Holder(view);
            } else if (viewType == 0) {
                view = new HeaderCell(context, resourceProvider);
            } else if (viewType == 1) {
                view = new TextCheckCell(context, resourceProvider);
            } else if (viewType == 2) {
                view = new ShadowSectionCell(context, resourceProvider);
                return new RecyclerListView.Holder(view);
            } else {
                view = new TextSettingsCell(context, resourceProvider);
            }
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            boolean masterOn = ColgramConfig.isAutoReplyEnabled();
            boolean schedOn = ColgramConfig.isAutoReplyScheduleEnabled();
            holder.itemView.setAlpha(1f);

            if (type == 10) {
                ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                String schedRange = ColgramAutoReplyPolicy.formatMinutes(ColgramConfig.getAutoReplyStartMin())
                        + "–" + ColgramAutoReplyPolicy.formatMinutes(ColgramConfig.getAutoReplyEndMin());
                hero.bind(R.drawable.msg_reply_small,
                        masterOn ? ColgramMenuChrome.COLOR_BLUE : ColgramMenuChrome.COLOR_SLATE,
                        "Бизнес-автоответчик",
                        "Умные шаблоны с переменными, расписание отсутствия и защита от циклов с ботами",
                        masterOn ? "АКТИВЕН" : "ВЫКЛ",
                        audienceLabel(ColgramConfig.getAutoReplyAudience()),
                        schedOn ? ("График: " + schedRange) : "График: выкл",
                        ColgramConfig.isAutoReplyInGroups() ? "Личка + группы" : "Только ЛС");
                return;
            }
            if (type == 0) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                if (position == mainHeaderRow) {
                    cell.setText("Бизнес-автоответчик");
                    ColgramMenuChrome.styleHeader(cell, ColgramMenuChrome.COLOR_BLUE, 0xFF60A5FA);
                } else {
                    cell.setText("График отсутствия (Как в TG Business)");
                    ColgramMenuChrome.styleHeader(cell, ColgramMenuChrome.COLOR_INDIGO, 0xFF818CF8);
                }
                return;
            }

            // Subtle opacity cue for dependent rows when master or schedule switch is off
            float rowAlpha = 1f;
            if (!masterOn && position != enabledRow && position != previewRow) {
                rowAlpha = 0.56f;
            } else if (!schedOn && (position == onlyWhenAwayRow || position == startTimeRow
                    || position == endTimeRow || position == awayTextRow)) {
                rowAlpha = 0.58f;
            }
            holder.itemView.setAlpha(rowAlpha);

            if (type == 1) {
                TextCheckCell cell = (TextCheckCell) holder.itemView;
                if (position == enabledRow) {
                    ColgramMenuChrome.applyCheckIcon(cell, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_reply_small);
                    cell.setTextAndCheck("Автоответ на входящие", masterOn, true);
                } else if (position == groupsRow) {
                    ColgramMenuChrome.applyCheckIcon(cell, ColgramMenuChrome.COLOR_CYAN, R.drawable.msg_groups);
                    cell.setTextAndCheck("Отвечать в группах", ColgramConfig.isAutoReplyInGroups(), true);
                } else if (position == botsRow) {
                    ColgramMenuChrome.applyCheckIcon(cell, ColgramMenuChrome.COLOR_VIOLET, R.drawable.msg_bots);
                    cell.setTextAndCheck("Отвечать другим ботам", ColgramConfig.isAutoReplyToBots(), true);
                } else if (position == scheduleEnabledRow) {
                    ColgramMenuChrome.applyCheckIcon(cell, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_online);
                    cell.setTextAndCheck("График «Нет на месте»", schedOn, true);
                } else if (position == onlyWhenAwayRow) {
                    ColgramMenuChrome.applyCheckIcon(cell, ColgramMenuChrome.COLOR_TEAL, R.drawable.msg_secret);
                    cell.setTextAndCheck("Молчать вне окна графика", ColgramConfig.isAutoReplyOnlyWhenAway(), true);
                }
            } else if (type == 3) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == textRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_edit, ColgramMenuChrome.COLOR_EMERALD);
                    String preview = ColgramConfig.getAutoReplyText();
                    if (preview.length() > 38) preview = preview.substring(0, 38) + "…";
                    cell.setTextAndValue("Шаблон ответа", preview, true);
                } else if (position == audienceRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_openprofile, ColgramMenuChrome.COLOR_BLUE);
                    cell.setTextAndValue("Аудитория", audienceLabel(ColgramConfig.getAutoReplyAudience()), true);
                } else if (position == cooldownRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_speed, ColgramMenuChrome.COLOR_AMBER);
                    int sec = ColgramConfig.getAutoReplyCooldownSec();
                    String val = sec == 0 ? "без паузы" : (sec >= 3600 ? (sec / 3600) + " ч" : Math.max(1, sec / 60) + " мин");
                    cell.setTextAndValue("Пауза на диалог", val, true);
                } else if (position == blacklistRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_block, ColgramMenuChrome.COLOR_CRIMSON);
                    String bl = ColgramConfig.getAutoReplyBlacklist();
                    cell.setTextAndValue("Исключения (ID)", bl.isEmpty() ? "нет" : bl, false);
                } else if (position == startTimeRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_online, ColgramMenuChrome.COLOR_INDIGO);
                    cell.setTextAndValue("Начало отсутствия", ColgramAutoReplyPolicy.formatMinutes(ColgramConfig.getAutoReplyStartMin()), true);
                } else if (position == endTimeRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_online, ColgramMenuChrome.COLOR_CYAN);
                    cell.setTextAndValue("Конец отсутствия", ColgramAutoReplyPolicy.formatMinutes(ColgramConfig.getAutoReplyEndMin()), true);
                } else if (position == awayTextRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_edit, ColgramMenuChrome.COLOR_VIOLET);
                    String away = ColgramConfig.getAutoReplyAwayText();
                    if (away.length() > 38) away = away.substring(0, 38) + "…";
                    cell.setTextAndValue("Текст для «Нет на месте»", away, true);
                } else if (position == previewRow) {
                    ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_search, ColgramMenuChrome.COLOR_TEAL);
                    cell.setTextAndValue("Проверить шаблон сейчас", "показать превью", false);
                }
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
                new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
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
