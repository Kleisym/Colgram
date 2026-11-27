package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBuildFlags;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramPremiumConfig;
import org.colgram.core.ColgramSavedTagsStore;
import org.colgram.core.ColgramShieldConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Colgram Premium+ Hub — real client-side Premium+ capabilities without fake server claims.
 * Zero AlertDialog.Builder; always applies icon/chrome BEFORE setText* to prevent any text overlap.
 */
public class ColgramPremiumActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int heroRow;

    private int savedHeaderRow;
    private int savedTagsToggleRow;
    private int savedTagsExportRow;
    private int savedTagsImportRow;
    private int savedSectionRow;

    private int adsLimitsHeaderRow;
    private int blockAdsRow;
    private int bypassNoForwardsRow;
    private int adsLimitsSectionRow;

    private int voiceTranslateHeaderRow;
    private int localSttRow;
    private int localSttFirstRow;
    private int localTranslatorRow;
    private int voiceTranslateSectionRow;

    private int comfortHeaderRow;
    private int hideStoriesRow;
    private int autoHideArchiveRow;
    private int startFolderRow;
    private int doubleTapReactionRow;
    private int appIconsRow;
    private int comfortSectionRow;

    private int privacyStorageHeaderRow;
    private int infiniteStorageRow;
    private int ghostHubRow;
    private int antiSpamHubRow;
    private int defenderHubRow;
    private int footerInfoRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        Context ctx = getParentActivity() != null ? getParentActivity() : org.telegram.messenger.ApplicationLoader.applicationContext;
        ColgramPremiumConfig.init(ctx);
        ColgramSavedTagsStore.init(ctx);
        ColgramShieldConfig.init(ctx);
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        heroRow = rowCount++;

        savedHeaderRow = rowCount++;
        savedTagsToggleRow = rowCount++;
        savedTagsExportRow = rowCount++;
        savedTagsImportRow = rowCount++;
        savedSectionRow = rowCount++;

        adsLimitsHeaderRow = rowCount++;
        blockAdsRow = rowCount++;
        bypassNoForwardsRow = rowCount++;
        adsLimitsSectionRow = rowCount++;

        voiceTranslateHeaderRow = rowCount++;
        localSttRow = rowCount++;
        localSttFirstRow = rowCount++;
        localTranslatorRow = rowCount++;
        voiceTranslateSectionRow = rowCount++;

        comfortHeaderRow = rowCount++;
        hideStoriesRow = rowCount++;
        autoHideArchiveRow = rowCount++;
        startFolderRow = rowCount++;
        doubleTapReactionRow = rowCount++;
        appIconsRow = rowCount++;
        comfortSectionRow = rowCount++;

        privacyStorageHeaderRow = rowCount++;
        infiniteStorageRow = rowCount++;
        ghostHubRow = rowCount++;
        antiSpamHubRow = rowCount++;
        defenderHubRow = rowCount++;
        footerInfoRow = rowCount++;

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Colgram Premium+");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout root = (FrameLayout) fragmentView;
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (isFinishing()) return;
            handleRowClick(view, position);
        });

        return fragmentView;
    }

    private void handleRowClick(View view, int position) {
        Activity act = getParentActivity();
        if (position == savedTagsToggleRow) {
            boolean next = !ColgramPremiumConfig.isSavedTagsEnabled();
            ColgramPremiumConfig.setSavedTagsEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            ColgramUiKit.showNotice(this, R.drawable.msg_reactions,
                    next ? "Эмодзи-теги в Избранном включены" : "Эмодзи-теги скрыты", false);
        } else if (position == savedTagsExportRow) {
            if (act == null) return;
            String json = ColgramSavedTagsStore.exportJson(currentAccount);
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Colgram Saved Tags", json));
            }
            int count = ColgramSavedTagsStore.getTotalTaggedMessagesCount(currentAccount);
            ColgramUiKit.showHudBanner(act, "Теги скопированы",
                    "В буфере JSON для " + count + " сообщений из Избранного",
                    R.drawable.msg_copy, ColgramMenuChrome.COLOR_EMERALD);
        } else if (position == savedTagsImportRow) {
            if (act == null) return;
            ColgramUiKit.showInputSheet(act, resourceProvider, R.drawable.msg_download,
                    "Импорт тегов Избранного",
                    "Вставьте JSON-бэкап, экспортированный из Colgram Premium+",
                    "[{\"msgId\":123,\"emoji\":\"⭐\",\"label\":\"Важное\"}]",
                    "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                    false, false, false, "Импортировать",
                    val -> {
                        int imported = ColgramSavedTagsStore.importJson(act, currentAccount, val);
                        updateRows();
                        ColgramUiKit.showNotice(this, R.drawable.msg_check_s,
                                imported > 0 ? ("Импортировано тегов: " + imported) : "Новых тегов не найдено",
                                imported == 0);
                    }, null, null);
        } else if (position == blockAdsRow) {
            boolean next = !ColgramPremiumConfig.isBlockSponsoredAdsEnabled();
            ColgramPremiumConfig.setBlockSponsoredAdsEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
        } else if (position == bypassNoForwardsRow) {
            boolean next = !ColgramPremiumConfig.isBypassNoForwardsEnabled();
            ColgramPremiumConfig.setBypassNoForwardsEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
        } else if (position == localSttRow) {
            boolean next = !ColgramPremiumConfig.isLocalVoiceTranscribeEnabled();
            ColgramPremiumConfig.setLocalVoiceTranscribeEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
        } else if (position == localSttFirstRow) {
            boolean next = !ColgramPremiumConfig.isLocalVoiceTranscribeLocalFirst();
            ColgramPremiumConfig.setLocalVoiceTranscribeLocalFirst(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
        } else if (position == localTranslatorRow) {
            boolean next = !ColgramPremiumConfig.isLocalTranslatorEnabled();
            ColgramPremiumConfig.setLocalTranslatorEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
        } else if (position == hideStoriesRow) {
            boolean next = !ColgramPremiumConfig.isHideStoriesEnabled();
            ColgramPremiumConfig.setHideStoriesEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.storiesEnabledUpdate);
            ColgramUiKit.showNotice(this, R.drawable.msg_stories_archive,
                    next ? "Лента историй скрыта" : "Лента историй возвращена", false);
        } else if (position == autoHideArchiveRow) {
            boolean next = !ColgramPremiumConfig.isAutoHideArchiveEnabled();
            ColgramPremiumConfig.setAutoHideArchiveEnabled(next);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(next);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
        } else if (position == startFolderRow) {
            showStartFolderPicker();
        } else if (position == doubleTapReactionRow) {
            showDoubleTapReactionPicker();
        } else if (position == appIconsRow) {
            showLauncherIconPicker();
        } else if (position == infiniteStorageRow) {
            presentFragment(new ColgramFilesActivity());
        } else if (position == ghostHubRow) {
            presentFragment(new ColgramSettingsActivity());
        } else if (position == antiSpamHubRow) {
            presentFragment(new ColgramAntiSpamActivity());
        } else if (position == defenderHubRow) {
            presentFragment(new ColgramDefenderActivity());
        }
    }

    private void showStartFolderPicker() {
        Activity act = getParentActivity();
        if (act == null) return;
        ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        List<ColgramUiKit.ChoiceItem> items = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        int curId = ColgramPremiumConfig.getDefaultStartFolderId();

        items.add(new ColgramUiKit.ChoiceItem("Все чаты (по умолчанию)", "Открывать основной список",
                R.drawable.msg_folders, ColgramMenuChrome.COLOR_BLUE, curId == 0, false, null));
        ids.add(0);

        if (filters != null) {
            for (MessagesController.DialogFilter f : filters) {
                if (f == null || f.isDefault() || f.id <= 0) continue;
                items.add(new ColgramUiKit.ChoiceItem(f.name, "Папка #" + f.id,
                        R.drawable.msg_folders, ColgramMenuChrome.COLOR_VIOLET, curId == f.id, false, null));
                ids.add(f.id);
            }
        }
        ColgramUiKit.showChoiceSheet(act, resourceProvider, R.drawable.msg_folders,
                "Стартовая папка при входе", "Какую вкладку открывать первой", items, idx -> {
                    if (idx >= 0 && idx < ids.size()) {
                        ColgramPremiumConfig.setDefaultStartFolderId(ids.get(idx));
                        updateRows();
                        ColgramUiKit.showNotice(this, R.drawable.msg_folders,
                                "Стартовая вкладка: " + items.get(idx).title, false);
                    }
                });
    }

    private void showDoubleTapReactionPicker() {
        Activity act = getParentActivity();
        if (act == null) return;
        final String cur = ColgramPremiumConfig.getLocalDoubleTapReaction();
        final String[] values = {"", "❤", "👍", "🔥", "⚡", "🎉", "😢", "disabled"};
        final String[] titles = {
                "По умолчанию Telegram", "❤️ Сердце", "👍 Класс", "🔥 Огонь",
                "⚡ Молния", "🎉 Праздник", "😢 Грусть", "Отключить двойной тап"
        };
        List<ColgramUiKit.ChoiceItem> items = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            boolean sel = values[i].equals(cur);
            items.add(new ColgramUiKit.ChoiceItem(titles[i], null, R.drawable.msg_reactions,
                    ColgramMenuChrome.COLOR_ROSE, sel, "disabled".equals(values[i]), null));
        }
        ColgramUiKit.showChoiceSheet(act, resourceProvider, R.drawable.msg_reactions,
                "Быстрая реакция двойным тапом", "Работает локально без Telegram Premium", items, idx -> {
                    if (idx >= 0 && idx < values.length) {
                        ColgramPremiumConfig.setLocalDoubleTapReaction(values[idx]);
                        updateRows();
                    }
                });
    }

    private void showLauncherIconPicker() {
        Activity act = getParentActivity();
        if (act == null) return;
        LauncherIconController.LauncherIcon[] icons = LauncherIconController.LauncherIcon.values();
        List<ColgramUiKit.ChoiceItem> items = new ArrayList<>();
        for (LauncherIconController.LauncherIcon icon : icons) {
            boolean active = LauncherIconController.isEnabled(icon);
            items.add(new ColgramUiKit.ChoiceItem(
                    LocaleController.getString(icon.title),
                    icon.premium ? "Разблокировано в Colgram Premium+" : "Стандартная иконка",
                    icon.foreground, ColgramMenuChrome.COLOR_INDIGO, active, false,
                    icon.premium ? "PLUS" : null));
        }
        ColgramUiKit.showChoiceSheet(act, resourceProvider, R.drawable.msg_theme,
                "Иконка приложения", "Все встроенные иконки открыты без подписки", items, idx -> {
                    if (idx >= 0 && idx < icons.length) {
                        LauncherIconController.setIcon(icons[idx]);
                        updateRows();
                        ColgramUiKit.showNotice(this, R.drawable.msg_theme,
                                "Иконка изменена: " + LocaleController.getString(icons[idx].title), false);
                    }
                });
    }

    private String formatStartFolderSummary() {
        int id = ColgramPremiumConfig.getDefaultStartFolderId();
        if (id <= 0) return "Все чаты";
        ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters != null) {
            for (MessagesController.DialogFilter f : filters) {
                if (f != null && f.id == id) return f.name;
            }
        }
        return "Папка #" + id;
    }

    private String formatReactionSummary() {
        String rx = ColgramPremiumConfig.getLocalDoubleTapReaction();
        if ("disabled".equals(rx)) return "Отключено";
        return TextUtils.isEmpty(rx) ? "По умолчанию Telegram" : ("Локальная: " + rx);
    }

    private String formatActiveIconSummary() {
        for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
            if (LauncherIconController.isEnabled(icon)) {
                return LocaleController.getString(icon.title) + " · все иконки открыты";
            }
        }
        return "По умолчанию · все иконки открыты";
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context ctx) {
            this.mContext = ctx;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int vt = holder.getItemViewType();
            return vt == 1 || vt == 3;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroRow) return 5;
            if (position == savedHeaderRow || position == adsLimitsHeaderRow
                    || position == voiceTranslateHeaderRow || position == comfortHeaderRow
                    || position == privacyStorageHeaderRow) {
                return 0;
            }
            if (position == savedSectionRow || position == adsLimitsSectionRow
                    || position == voiceTranslateSectionRow || position == comfortSectionRow) {
                return 2;
            }
            if (position == footerInfoRow) return 4;
            if (position == savedTagsExportRow || position == savedTagsImportRow
                    || position == startFolderRow || position == doubleTapReactionRow
                    || position == appIconsRow || position == infiniteStorageRow
                    || position == ghostHubRow || position == antiSpamHubRow || position == defenderHubRow) {
                return 3;
            }
            return 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case 5:
                    v = new ColgramMenuChrome.HeroBannerCell(mContext);
                    break;
                case 0:
                    v = new HeaderCell(mContext, resourceProvider);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
                    break;
                case 2:
                    v = new ShadowSectionCell(mContext, resourceProvider);
                    break;
                case 3:
                    v = new TextDetailSettingsCell(mContext);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
                    break;
                case 4:
                    v = new TextInfoPrivacyCell(mContext, resourceProvider);
                    break;
                case 1:
                default:
                    v = new TextCheckCell(mContext, resourceProvider);
                    v.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
                    break;
            }
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            boolean divider = position + 1 < rowCount
                    && getItemViewType(position + 1) != 2
                    && getItemViewType(position + 1) != 4
                    && getItemViewType(position + 1) != 0;

            if (type == 5) {
                ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                int taggedCount = ColgramSavedTagsStore.getTotalTaggedMessagesCount(currentAccount);
                hero.bind(
                        R.drawable.msg_reactions,
                        ColgramMenuChrome.COLOR_VIOLET,
                        "Colgram Premium+",
                        "Реальные клиентские функции без подписки и без фейковых запросов",
                        "АКТИВНО",
                        ColgramPremiumConfig.isBlockSponsoredAdsEnabled() ? "Без рекламы" : "Реклама вкл",
                        "Тегов: " + taggedCount,
                        ColgramBuildFlags.KOLITI_ENABLED ? "Офлайн STT (Koliti)" : "Локальный STT + Перевод"
                );
            } else if (type == 0) {
                HeaderCell hc = (HeaderCell) holder.itemView;
                if (position == savedHeaderRow) {
                    hc.setText("Избранное и теги");
                    ColgramMenuChrome.styleHeader(hc, ColgramMenuChrome.COLOR_VIOLET, 0xFFA78BFA);
                } else if (position == adsLimitsHeaderRow) {
                    hc.setText("Без рекламы и лимитов");
                    ColgramMenuChrome.styleHeader(hc, ColgramMenuChrome.COLOR_CRIMSON, 0xFFFB7185);
                } else if (position == voiceTranslateHeaderRow) {
                    hc.setText("Голос и перевод");
                    ColgramMenuChrome.styleHeader(hc, ColgramMenuChrome.COLOR_CYAN, 0xFF22D3EE);
                } else if (position == comfortHeaderRow) {
                    hc.setText("Персонализация и комфорт");
                    ColgramMenuChrome.styleHeader(hc, ColgramMenuChrome.COLOR_EMERALD, 0xFF34D399);
                } else if (position == privacyStorageHeaderRow) {
                    hc.setText("Приватность, призрак и ∞-хранилище");
                    ColgramMenuChrome.styleHeader(hc, ColgramMenuChrome.COLOR_BLUE, 0xFF60A5FA);
                }
            } else if (type == 1) {
                TextCheckCell tc = (TextCheckCell) holder.itemView;
                if (position == savedTagsToggleRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_VIOLET, R.drawable.msg_reactions);
                    tc.setTextAndValueAndCheck("Эмодзи-теги в Избранном",
                            "Локальная панель фильтров, бейджи и быстрый переход по тегам",
                            ColgramPremiumConfig.isSavedTagsEnabled(), true, divider);
                } else if (position == blockAdsRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_CRIMSON, R.drawable.msg_block);
                    tc.setTextAndValueAndCheck("Блокировщик рекламы Telegram Ads",
                            "Скрывает спонсорские посты в каналах, ботах и поиске на 100%",
                            ColgramPremiumConfig.isBlockSponsoredAdsEnabled(), true, divider);
                } else if (position == bypassNoForwardsRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_AMBER, R.drawable.msg_copy);
                    tc.setTextAndValueAndCheck("Копирование и сохранение при запрете",
                            "Обход ограничения noforwards для текста, медиа и пересылки",
                            ColgramPremiumConfig.isBypassNoForwardsEnabled(), true, divider);
                } else if (position == localSttRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_CYAN, R.drawable.input_mic);
                    tc.setTextAndValueAndCheck("Расшифровка голосовых и кружков (STT)",
                            ColgramBuildFlags.KOLITI_ENABLED
                                    ? "Офлайн Vosk/Sherpa (Koliti) или системный распознаватель при лимите"
                                    : "Автоподхват локального распознавателя Android 13+ при лимите сервера",
                            ColgramPremiumConfig.isLocalVoiceTranscribeEnabled(), true, divider);
                } else if (position == localSttFirstRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_TEAL, R.drawable.msg_voicechat);
                    tc.setTextAndValueAndCheck("Сначала локально (без запроса на сервер)",
                            "Сразу расшифровывать на устройстве, не тратя пробные попытки",
                            ColgramPremiumConfig.isLocalVoiceTranscribeLocalFirst(), true, divider);
                } else if (position == localTranslatorRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_BLUE, R.drawable.msg_translate);
                    tc.setTextAndValueAndCheck("Перевод сообщений и всего чата",
                            "Бесшовный локальный перевод через прокси без лимитов Premium",
                            ColgramPremiumConfig.isLocalTranslatorEnabled(), true, divider);
                } else if (position == hideStoriesRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_INDIGO, R.drawable.msg_stories_archive);
                    tc.setTextAndValueAndCheck("Скрыть ленту историй (Stories)",
                            "Убирает верхнюю панель историй в списке чатов",
                            ColgramPremiumConfig.isHideStoriesEnabled(), true, divider);
                } else if (position == autoHideArchiveRow) {
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_SLATE, R.drawable.msg_folders);
                    tc.setTextAndValueAndCheck("Автоматически скрывать Архив",
                            "Скрывает строку Архива при открытии списка чатов",
                            ColgramPremiumConfig.isAutoHideArchiveEnabled(), true, divider);
                }
            } else if (type == 3) {
                TextDetailSettingsCell td = (TextDetailSettingsCell) holder.itemView;
                if (position == savedTagsExportRow) {
                    int tagged = ColgramSavedTagsStore.getTotalTaggedMessagesCount(currentAccount);
                    ColgramMenuChrome.applyDetailIcon(td, "Экспорт тегов Избранного (JSON)",
                            "Сохранено тегов для " + tagged + " сообщений · скопировать в буфер",
                            R.drawable.msg_copy, ColgramMenuChrome.COLOR_EMERALD, divider);
                } else if (position == savedTagsImportRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Импорт тегов Избранного",
                            "Восстановить локальные теги из JSON-резервной копии",
                            R.drawable.msg_download, ColgramMenuChrome.COLOR_BLUE, divider);
                } else if (position == startFolderRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Стартовая папка при открытии",
                            formatStartFolderSummary(),
                            R.drawable.msg_folders, ColgramMenuChrome.COLOR_VIOLET, divider);
                } else if (position == doubleTapReactionRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Быстрая реакция двойным тапом",
                            formatReactionSummary(),
                            R.drawable.msg_reactions, ColgramMenuChrome.COLOR_ROSE, divider);
                } else if (position == appIconsRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Иконки приложения",
                            formatActiveIconSummary(),
                            R.drawable.msg_theme, ColgramMenuChrome.COLOR_INDIGO, divider);
                } else if (position == infiniteStorageRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Бесконечное хранилище (≤ 1 ГБ на диске)",
                            "Лимит: " + (ColgramShieldConfig.getStorageHardCapMb() <= 0 ? "Безлимит" : (ColgramShieldConfig.getStorageHardCapMb() + " МБ"))
                                    + " · сжатие WebP/zlib и умная дегидратация",
                            R.drawable.msg_folders, ColgramMenuChrome.COLOR_AMBER, divider);
                } else if (position == ghostHubRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Режим призрака и Анти-Удаление",
                            "Скрытие прочтения, печати, онлайна, историй и сейф удалённых",
                            R.drawable.msg_secret, ColgramMenuChrome.COLOR_CRIMSON, divider);
                } else if (position == antiSpamHubRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Антиспам, Анти-Пинг и Colemailer",
                            "Очистка спам-упоминаний, рассылки и защита от «317 @»",
                            R.drawable.msg_block, ColgramMenuChrome.COLOR_CRIMSON, divider);
                } else if (position == defenderHubRow) {
                    ColgramMenuChrome.applyDetailIcon(td, "Бот-защитник Telegram",
                            ColgramConfig.isDefenderEnabled() ? "Авто-аудит и закрытие утечек активны" : "Выключен",
                            R.drawable.outline_shield_check, ColgramMenuChrome.COLOR_EMERALD, divider);
                }
            } else if (type == 4) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        "Все функции Colgram Premium+ реализованы честно на стороне клиента без обмана сервера "
                                + "и работают на обычном аккаунте Telegram.");
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextDetailSettingsCell.class},
                null, null, null, Theme.key_windowBackgroundWhite));
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
        return list;
    }
}
