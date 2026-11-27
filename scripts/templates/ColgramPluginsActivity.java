package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramPluginManager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramPluginsActivity — plugin manager and marketplace.
 *
 * All three install paths a user actually asks for:
 *   - write code   (editor dialog, via the "+" menu item)
 *   - from a URL   (raw .py fetched over HTTPS, via the link menu item)
 *   - from a file  (system document picker, via the file menu item)
 *
 * The marketplace list is loaded live from ColgramPluginManager.fetchCatalog(), which
 * reads the remote catalog and falls back to a bundled set. The previous version rendered
 * a hardcoded array and never displayed the remote catalog at all, so the marketplace
 * could never show anything beyond the built-in stubs.
 *
 * Colours come from Telegram Theme tokens throughout. This screen used to hardcode a dark
 * palette (0xFF0E0F12 background with white text), which made it unreadable in the light
 * theme — the same bug class as the black-on-black login form.
 */
public class ColgramPluginsActivity extends BaseFragment {

    private static final String TAG = "ColgramPlugins";
    /** Request code for the SAF .py picker. */
    private static final int REQ_PICK_PY = 4801;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    private final List<ColgramPluginManager.CatalogEntry> catalog = new ArrayList<>();
    private boolean catalogLoading = true;
    private boolean catalogIsRemote = false;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramPluginManager.init(getParentActivity());
        return true;
    }

    private static String b64(String s) {
        try {
            return new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Плагины");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 100) {
                    showPluginEditorDialog(null, null);
                } else if (id == 101) {
                    showInstallFromUrlDialog();
                } else if (id == 102) {
                    pickPluginFile();
                } else if (id == 103) {
                    loadCatalog(true);
                }
            }
        });
        ActionBarMenuItem addMenu = actionBar.createMenu().addItem(104, R.drawable.msg_add);
        addMenu.setContentDescription("Добавить плагин");
        addMenu.addSubItem(100, R.drawable.msg_edit, "Написать код вручную");
        addMenu.addSubItem(101, R.drawable.msg_link, "Установить по ссылке");
        addMenu.addSubItem(102, R.drawable.msg_filehq, "Установить из файла");
        ActionBarMenuItem refresh = actionBar.createMenu().addItem(103, R.drawable.msg_retry);
        refresh.setContentDescription("Обновить каталог");

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
            Row row = rowAt(position);
            if (row == null) return;
            switch (row.kind) {
                case Row.KIND_NEW_PLUGIN:
                    showPluginEditorDialog(null, null);
                    break;
                case Row.KIND_INSTALL_URL:
                    showInstallFromUrlDialog();
                    break;
                case Row.KIND_INSTALL_FILE:
                    pickPluginFile();
                    break;
                case Row.KIND_CATALOG_STATUS:
                case Row.KIND_REFRESH_CATALOG:
                    loadCatalog(true);
                    break;
                case Row.KIND_FEATURE:
                    handleFeatureTap(row);
                    break;
                case Row.KIND_INSTALLED:
                case Row.KIND_BUILTIN:
                    if (row.plugin != null) {
                        boolean nextEnabled = !row.plugin.isEnabled;
                        ColgramPluginManager.togglePlugin(row.plugin.fileName, nextEnabled);
                        if (view instanceof TextCheckCell) {
                            ((TextCheckCell) view).setChecked(nextEnabled);
                        } else {
                            listAdapter.notifyItemChanged(position);
                        }
                    }
                    break;
                case Row.KIND_CATALOG:
                    if (row.entry != null) installCatalogEntry(row.entry);
                    break;
                default:
                    break;
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            Row row = rowAt(position);
            if (row != null && row.kind == Row.KIND_INSTALLED && row.plugin != null) {
                showInstalledPluginActions(row.plugin);
                return true;
            }
            return false;
        });

        loadCatalog(false);

        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onFragmentDestroy();
    }

    // ------------------------------------------------------------ row model

    private static class Row {
        static final int KIND_HEADER = 0;
        static final int KIND_NEW_PLUGIN = 1;
        static final int KIND_INSTALLED = 2;
        static final int KIND_CATALOG = 3;
        static final int KIND_BUILTIN = 4;
        static final int KIND_FEATURE = 5;
        static final int KIND_HERO = 6;
        static final int KIND_INSTALL_URL = 7;
        static final int KIND_INSTALL_FILE = 8;
        static final int KIND_CATALOG_STATUS = 9;
        static final int KIND_REFRESH_CATALOG = 10;
        static final int KIND_SHADOW = 11;

        final int kind;
        final String header;
        final ColgramPluginManager.PluginInfo plugin;
        final ColgramPluginManager.CatalogEntry entry;
        /** For KIND_FEATURE: auto_reply | keywords | logger | export. */
        final String featureKey;
        final String featureTitle;
        final String featureSubtitle;

        Row(int kind, String header, ColgramPluginManager.PluginInfo plugin,
            ColgramPluginManager.CatalogEntry entry) {
            this(kind, header, plugin, entry, null, null, null);
        }

        Row(int kind, String header, ColgramPluginManager.PluginInfo plugin,
            ColgramPluginManager.CatalogEntry entry, String featureKey,
            String featureTitle, String featureSubtitle) {
            this.kind = kind;
            this.header = header;
            this.plugin = plugin;
            this.entry = entry;
            this.featureKey = featureKey;
            this.featureTitle = featureTitle;
            this.featureSubtitle = featureSubtitle;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    private void rebuildRows() {
        rows.clear();
        rows.add(new Row(Row.KIND_HERO, null, null, null));

        // Built-in features. These used to ship as Python plugins in this very list, which
        // made "по умолчанию в приложении" depend on a script engine booting. They are part
        // of Colgram now: toggles here, logic in colgram-core, no interpreter in the path.
        rows.add(new Row(Row.KIND_HEADER, "Встроенные функции", null, null));
        rows.add(new Row(Row.KIND_FEATURE, null, null, null, "auto_reply", "Автоответчик",
                "отвечает за вас в личке (и в группах — по настройке)"));
        rows.add(new Row(Row.KIND_FEATURE, null, null, null, "keywords", "Ключевые слова",
                "уведомляет, когда в сообщениях всплывает ваше слово"));
        rows.add(new Row(Row.KIND_FEATURE, null, null, null, "logger", "Журнал сообщений",
                "все входящие и исходящие в локальный JSONL-файл"));
        rows.add(new Row(Row.KIND_FEATURE, null, null, null, "export", "Экспорт чата",
                "команда .export сохраняет диалог в HTML"));
        rows.add(new Row(Row.KIND_SHADOW, null, null, null));

        rows.add(new Row(Row.KIND_HEADER, "Создать или установить", null, null));
        rows.add(new Row(Row.KIND_NEW_PLUGIN, null, null, null));
        rows.add(new Row(Row.KIND_INSTALL_URL, null, null, null));
        rows.add(new Row(Row.KIND_INSTALL_FILE, null, null, null));
        rows.add(new Row(Row.KIND_SHADOW, null, null, null));

        List<ColgramPluginManager.PluginInfo> installed = ColgramPluginManager.getLoadedPlugins();
        int builtInCount = 0;
        for (ColgramPluginManager.PluginInfo p : installed) if (p.builtIn) builtInCount++;
        if (builtInCount > 0) {
            rows.add(new Row(Row.KIND_HEADER,
                    "Встроенные возможности Colgram (" + builtInCount + ")", null, null));
            for (ColgramPluginManager.PluginInfo p : installed) {
                if (p.builtIn) rows.add(new Row(Row.KIND_BUILTIN, null, p, null));
            }
            rows.add(new Row(Row.KIND_SHADOW, null, null, null));
        }
        rows.add(new Row(Row.KIND_HEADER, "Пользовательские плагины (" + (installed.size() - builtInCount) + ")", null, null));
        for (ColgramPluginManager.PluginInfo p : installed) {
            if (!p.builtIn) rows.add(new Row(Row.KIND_INSTALLED, null, p, null));
        }
        rows.add(new Row(Row.KIND_SHADOW, null, null, null));

        String catHeader;
        if (catalogLoading) {
            catHeader = "Каталог расширений — загрузка...";
        } else if (catalog.isEmpty()) {
            catHeader = "Каталог расширений";
        } else if (catalogIsRemote) {
            catHeader = "Каталог расширений (" + catalog.size() + ")";
        } else {
            catHeader = "Каталог расширений (" + catalog.size() + ", офлайн-набор)";
        }
        rows.add(new Row(Row.KIND_HEADER, catHeader, null, null));
        if (!catalogLoading && catalog.isEmpty()) {
            rows.add(new Row(Row.KIND_CATALOG_STATUS, null, null, null));
            rows.add(new Row(Row.KIND_REFRESH_CATALOG, null, null, null));
        } else {
            for (ColgramPluginManager.CatalogEntry e : catalog) {
                rows.add(new Row(Row.KIND_CATALOG, null, null, e));
            }
        }
        rows.add(new Row(Row.KIND_SHADOW, null, null, null));

        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private Row rowAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        return rows.get(position);
    }

    // ------------------------------------------------------------ built-in features

    /** Toggle a built-in feature, or open its editor when it needs configuration. */
    private void handleFeatureTap(Row row) {
        if (getParentActivity() == null) return;
        switch (row.featureKey) {
            case "auto_reply":
                boolean ar = !org.colgram.core.ColgramConfig.isAutoReplyEnabled();
                org.colgram.core.ColgramConfig.setAutoReplyEnabled(ar);
                ColgramUiKit.showNotice(this, R.drawable.msg_reply_small,
                        ar ? "Автоответчик включён" : "Автоответчик выключен", false);
                listAdapter.notifyDataSetChanged();
                break;
            case "keywords":
                boolean kw = !org.colgram.core.ColgramConfig.isKeywordAlertsEnabled();
                org.colgram.core.ColgramConfig.setKeywordAlertsEnabled(kw);
                if (kw && org.colgram.core.ColgramConfig.getKeywordAlertsList().trim().isEmpty()) {
                    showKeywordsDialog();
                } else {
                    ColgramUiKit.showNotice(this, R.drawable.msg_search,
                            kw ? "Оповещения по ключевым словам включены" : "Ключевые слова выключены", false);
                }
                listAdapter.notifyDataSetChanged();
                break;
            case "logger":
                boolean lg = !org.colgram.core.ColgramConfig.isMessageLoggerEnabled();
                org.colgram.core.ColgramConfig.setMessageLoggerEnabled(lg);
                ColgramUiKit.showNotice(this, R.drawable.msg_archive,
                        lg ? "Журнал сообщений включён" : "Журнал сообщений выключен", false);
                listAdapter.notifyDataSetChanged();
                break;
            case "export":
                ColgramUiKit.showNotice(this, R.drawable.msg_download,
                        "Откройте чат и отправьте .export — файл появится в Colgram/exports", false);
                break;
            default:
                break;
        }
    }

    /** One-line editor for the keyword list; enabling without words opens it automatically. */
    private void showKeywordsDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        final boolean[] saved = new boolean[]{false};
        org.telegram.ui.ActionBar.BottomSheet sheet = ColgramUiKit.showInputSheet(
                act,
                resourceProvider,
                R.drawable.msg_edit,
                "Ключевые слова",
                "Список слов для локальных уведомлений о совпадениях",
                "через запятую: работа, счёт, дедлайн",
                org.colgram.core.ColgramConfig.getKeywordAlertsList(),
                InputType.TYPE_CLASS_TEXT,
                false,
                false,
                true,
                "Сохранить",
                raw -> {
                    saved[0] = true;
                    org.colgram.core.ColgramConfig.setKeywordAlertsList(raw == null ? "" : raw);
                    listAdapter.notifyDataSetChanged();
                },
                null,
                null
        );
        if (sheet != null) {
            sheet.setOnDismissListener(d -> {
                if (!saved[0] && org.colgram.core.ColgramConfig.getKeywordAlertsList().trim().isEmpty()) {
                    org.colgram.core.ColgramConfig.setKeywordAlertsEnabled(false);
                    listAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    // ------------------------------------------------------------ catalog

    private void loadCatalog(boolean showToast) {
        catalogLoading = true;
        rebuildRows();
        final boolean toast = showToast;
        executor.execute(() -> {
            List<ColgramPluginManager.CatalogEntry> fetched = null;
            boolean remote = false;
            try {
                fetched = ColgramPluginManager.fetchCatalog();
                remote = ColgramPluginManager.lastCatalogWasRemote();
            } catch (Throwable t) {
                Log.w(TAG, "catalog fetch failed: " + t.getMessage());
            }
            final List<ColgramPluginManager.CatalogEntry> result =
                    fetched == null ? new ArrayList<>() : fetched;
            final boolean isRemote = remote;
            mainHandler.post(() -> {
                if (destroyed) return;
                catalog.clear();
                for (ColgramPluginManager.CatalogEntry entry : result) {
                    if (!ColgramPluginManager.isBundledFeature(ColgramPluginManager.fileNameFor(entry))) {
                        catalog.add(entry);
                    }
                }
                catalogIsRemote = isRemote;
                catalogLoading = false;
                rebuildRows();
                if (toast && getParentActivity() != null) {
                    ColgramUiKit.showNotice(this, R.drawable.msg_customize,
                            isRemote ? "Каталог обновлён: " + result.size()
                                    : "Сеть недоступна — показан встроенный набор",
                            false);
                }
            });
        });
    }

    private void installCatalogEntry(ColgramPluginManager.CatalogEntry e) {
        Activity act = getParentActivity();
        if (act == null) return;
        final String fileName = ColgramPluginManager.fileNameFor(e);
        boolean already = ColgramPluginManager.isPluginInstalled(fileName);

        StringBuilder msg = new StringBuilder();
        if (e.description != null && !e.description.isEmpty()) msg.append(e.description).append("\n\n");
        if (e.author != null && !e.author.isEmpty()) msg.append("Автор: ").append(e.author).append("\n");
        if (e.version != null && !e.version.isEmpty()) msg.append("Версия: ").append(e.version).append("\n");
        msg.append("Файл: ").append(fileName).append("\n");
        if (already) msg.append("\nУже установлен — будет перезаписан.");

        ColgramUiKit.showConfirmSheet(
                act,
                resourceProvider,
                R.drawable.msg_customize,
                e.name == null ? "Плагин" : e.name,
                msg.toString().trim(),
                already ? "Переустановить" : "Установить",
                false,
                () -> executor.execute(() -> {
                    boolean ok = false;
                    String err = null;
                    try {
                        if (e.downloadUrl != null && !e.downloadUrl.trim().isEmpty()) {
                            ok = ColgramPluginManager.installPluginFromUrl(e.downloadUrl);
                            if (!ok) err = "не удалось скачать " + e.downloadUrl;
                        } else {
                            ok = ColgramPluginManager.installBundledPlugin(fileName);
                            if (!ok) err = "встроенный плагин " + fileName + " не найден";
                        }
                    } catch (Throwable t) {
                        err = t.getMessage() == null ? t.toString() : t.getMessage();
                    }
                    final boolean success = ok;
                    final String failure = err;
                    mainHandler.post(() -> {
                        if (destroyed) return;
                        rebuildRows();
                        if (getParentActivity() == null) return;
                        ColgramUiKit.showNotice(
                                this,
                                success ? R.drawable.msg_customize : R.drawable.msg_info,
                                success ? "Установлено: " + e.name : "Ошибка: " + failure,
                                !success
                        );
                    });
                }),
                LocaleController.getString(R.string.Cancel),
                null
        );
    }

    // ------------------------------------------------------------ install paths

    /** Install from a raw .py URL typed by the user. */
    private void showInstallFromUrlDialog() {
        Activity act = getParentActivity();
        if (act == null) return;
        ColgramUiKit.showInputSheet(
                act,
                resourceProvider,
                R.drawable.msg_link2,
                "Установить по ссылке",
                "Прямая ссылка на .py или .plugin файл",
                "https://example.com/my_plugin.py",
                null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false,
                false,
                true,
                "Установить",
                rawUrl -> {
                    String url = rawUrl == null ? "" : rawUrl.trim();
                    if (url.isEmpty()) return;
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info,
                                "Ссылка должна начинаться с http:// или https://", true);
                        return;
                    }
                    executor.execute(() -> {
                        boolean ok = false;
                        String err = null;
                        try {
                            ok = ColgramPluginManager.installPluginFromUrl(url);
                            if (!ok) err = "не удалось загрузить или установить";
                        } catch (Throwable t) {
                            err = t.getMessage() == null ? t.toString() : t.getMessage();
                        }
                        final boolean success = ok;
                        final String failure = err;
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            rebuildRows();
                            if (getParentActivity() == null) return;
                            ColgramUiKit.showNotice(
                                    this,
                                    success ? R.drawable.msg_customize : R.drawable.msg_info,
                                    success ? "Плагин установлен" : "Ошибка: " + failure,
                                    !success
                            );
                        });
                    });
                },
                null,
                null
        );
    }

    /**
     * Open the system document picker for a .py file.
     *
     * Uses ACTION_OPEN_DOCUMENT rather than a Colgram file browser: the plugin file lives
     * outside the app sandbox, so only SAF can reach it, and the user gets the familiar
     * system picker instead of a custom directory walk.
     */
    private void pickPluginFile() {
        if (getParentActivity() == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            // Providers assign .plugin many different MIME types; filtering here made
            // the attached file invisible in some document pickers.
            startActivityForResult(intent, REQ_PICK_PY);
        } catch (Throwable t) {
            ColgramUiKit.showNotice(this, R.drawable.msg_info,
                    "Не удалось открыть файловый менеджер: " + t.getMessage(), true);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_PY) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            return;
        }
        if (data == null || data.getData() == null || getParentActivity() == null) return;
        final Uri uri = data.getData();
        final Context ctx = getParentActivity();
        executor.execute(() -> {
            boolean ok = false;
            String err = null;
            try {
                String code = readTextFromUri(ctx, uri);
                if (code == null || code.trim().isEmpty()) {
                    err = "файл пустой или недоступен";
                } else {
                    String name = displayNameFromUri(ctx, uri);
                    String lowerName = name.toLowerCase();
                    if (!lowerName.endsWith(".py") && !lowerName.endsWith(".plugin")) {
                        err = "выберите файл .py или .plugin";
                    } else {
                        ok = ColgramPluginManager.installPlugin(name, code);
                        if (!ok) err = "некорректное имя или файл встроенной функции";
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final boolean success = ok;
            final String failure = err;
            mainHandler.post(() -> {
                if (destroyed) return;
                rebuildRows();
                Activity act = getParentActivity();
                if (act == null || act.isFinishing() || act.isDestroyed()) return;
                ColgramUiKit.showNotice(this, success ? R.drawable.msg_customize : R.drawable.msg_info,
                        success ? "Плагин установлен из файла" : "Ошибка: " + failure, !success);
            });
        });
    }

    private static String readTextFromUri(Context ctx, Uri uri) {
        try {
            java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (bos.size() + n > 2 * 1024 * 1024) {
                    in.close();
                    return null;
                }
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "readTextFromUri failed: " + t.getMessage());
            return null;
        }
    }

    /** SAF document IDs are not filenames; query the provider's display name. */
    private static String displayNameFromUri(Context ctx, Uri uri) {
        try {
            try (Cursor cursor = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
            String last = uri.getLastPathSegment();
            if (last == null) return "plugin.py";
            int slash = last.lastIndexOf('/');
            if (slash >= 0) last = last.substring(slash + 1);
            return last.isEmpty() ? "plugin.py" : last;
        } catch (Throwable t) {
            return "plugin.py";
        }
    }

    private void showInstalledPluginActions(ColgramPluginManager.PluginInfo p) {
        Activity act = getParentActivity();
        if (destroyed || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) return;
        java.util.List<ColgramUiKit.ChoiceItem> items = new java.util.ArrayList<>(3);
        items.add(ColgramUiKit.ChoiceItem.of("Настройки плагина", null, R.drawable.msg_settings));
        items.add(ColgramUiKit.ChoiceItem.of("Редактировать код", p.fileName, R.drawable.msg_edit));
        items.add(ColgramUiKit.ChoiceItem.destructive("Удалить плагин", "Удалить файл с устройства", R.drawable.msg_delete));
        ColgramUiKit.showChoiceSheet(
                act,
                resourceProvider,
                R.drawable.msg_customize,
                p.name,
                p.fileName,
                items,
                w -> {
                    if (w == 0) {
                        openExteraPluginSettings(p);
                    } else if (w == 1) {
                        executor.execute(() -> {
                            final String code = ColgramPluginManager.getPluginCode(p.fileName);
                            mainHandler.post(() -> {
                                if (!destroyed && getParentActivity() != null && !getParentActivity().isFinishing()) {
                                    showPluginEditorDialog(p.fileName, code);
                                }
                            });
                        });
                    } else if (w == 2) {
                        executor.execute(() -> {
                            ColgramPluginManager.deletePlugin(p.fileName);
                            mainHandler.post(() -> {
                                if (destroyed) return;
                                rebuildRows();
                                ColgramUiKit.showNotice(this, R.drawable.msg_delete, "Плагин удалён", false);
                            });
                        });
                    }
                }
        );
    }

    private void openExteraPluginSettings(ColgramPluginManager.PluginInfo p) {
        Activity ctx = getParentActivity();
        if (ctx == null) return;
        ColgramUiKit.showNotice(this, R.drawable.msg_settings, "Загрузка настроек...", false);
        executor.execute(() -> {
            String rawJson = ColgramPluginManager.getPluginSettingsSchemaJson(p.fileName);
            mainHandler.post(() -> {
                Activity curActivity = getParentActivity();
                if (destroyed || curActivity == null) return;
                try {
                    JSONArray arr = new JSONArray(rawJson != null ? rawJson : "[]");
                    if (arr.length() == 0) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "У плагина нет встроенных настроек UI", false);
                        return;
                    }
                    LinearLayout layout = new LinearLayout(curActivity);
                    layout.setOrientation(LinearLayout.VERTICAL);

                    final java.util.Map<String, Integer> selectorValues = new java.util.HashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        String type = item.optString("type", "");
                        final String key = item.optString("key", "");
                        String text = item.optString("text", "");
                        if ("header".equals(type)) {
                            TextView tv = new TextView(curActivity);
                            tv.setText(text);
                            tv.setTypeface(AndroidUtilities.bold());
                            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourceProvider));
                            tv.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(4));
                            layout.addView(tv);
                        } else if ("switch".equals(type) && !key.isEmpty()) {
                            final TextCheckCell tc = new TextCheckCell(curActivity, resourceProvider);
                            tc.setTextAndCheck(text, item.optBoolean("value", false), false);
                            tc.setTag(key);
                            tc.setOnClickListener(v -> tc.setChecked(!tc.isChecked()));
                            layout.addView(tc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                        } else if ("input".equals(type) && !key.isEmpty()) {
                            TextView lbl = new TextView(curActivity);
                            lbl.setText(text);
                            lbl.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
                            lbl.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(2));
                            layout.addView(lbl);
                            EditTextBoldCursor ed = ColgramMenuChrome.makeDialogEditText(curActivity, resourceProvider, "", false);
                            ed.setTag(key);
                            ed.setText(item.optString("value", ""));
                            layout.addView(ed, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                        } else if ("selector".equals(type) && !key.isEmpty()) {
                            JSONArray itemsArr = item.optJSONArray("items");
                            final List<String> labels = new ArrayList<>();
                            if (itemsArr != null) {
                                for (int k = 0; k < itemsArr.length(); k++) {
                                    labels.add(itemsArr.optString(k, ""));
                                }
                            }
                            if (!labels.isEmpty()) {
                                int initIdx = Math.max(0, Math.min(item.optInt("value", 0), labels.size() - 1));
                                selectorValues.put(key, initIdx);
                                final TextDetailSettingsCell selCell = new TextDetailSettingsCell(curActivity);
                                selCell.setTextAndValue(text, labels.get(initIdx), false);
                                selCell.setTag("selector:" + key);
                                selCell.setOnClickListener(v -> {
                                    Activity curAct = getParentActivity();
                                    if (destroyed || curAct == null || curAct.isFinishing() || curAct.isDestroyed()) return;
                                    Integer curVal = selectorValues.get(key);
                                    ColgramUiKit.showChoiceSheet(
                                            curAct,
                                            resourceProvider,
                                            R.drawable.msg_settings,
                                            text,
                                            null,
                                            labels.toArray(new String[0]),
                                            curVal != null ? curVal : 0,
                                            whichIdx -> {
                                                selectorValues.put(key, whichIdx);
                                                selCell.setTextAndValue(text, labels.get(whichIdx), false);
                                            }
                                    );
                                });
                                layout.addView(selCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                            }
                        } else if ("text".equals(type) || "divider".equals(type)) {
                            if (!text.isEmpty()) {
                                TextView tv = new TextView(curActivity);
                                tv.setText(text);
                                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
                                tv.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
                                layout.addView(tv);
                            }
                        }
                    }

                    ColgramUiKit.showCustomViewSheet(
                            curActivity,
                            resourceProvider,
                            R.drawable.msg_settings,
                            "Настройки: " + p.name,
                            null,
                            layout,
                            "Сохранить",
                            sheet -> {
                                final List<String[]> pending = new ArrayList<>();
                                for (int idxItem = 0; idxItem < layout.getChildCount(); idxItem++) {
                                    View v = layout.getChildAt(idxItem);
                                    Object tag = v.getTag();
                                    if (!(tag instanceof String)) continue;
                                    String k = (String) tag;
                                    if (v instanceof TextCheckCell) {
                                        pending.add(new String[]{k, ((TextCheckCell) v).isChecked() ? "true" : "false"});
                                    } else if (v instanceof EditText) {
                                        pending.add(new String[]{k, JSONObject.quote(((EditText) v).getText().toString())});
                                    } else if (k.startsWith("selector:")) {
                                        String realKey = k.substring("selector:".length());
                                        Integer idxVal = selectorValues.get(realKey);
                                        if (idxVal != null) {
                                            pending.add(new String[]{realKey, Integer.toString(idxVal)});
                                        }
                                    }
                                }
                                if (pending.isEmpty()) return true;
                                executor.execute(() -> {
                                    for (String[] kv : pending) {
                                        ColgramPluginManager.updatePluginSetting(p.fileName, kv[0], kv[1]);
                                    }
                                    mainHandler.post(() -> {
                                        if (!destroyed && getParentActivity() != null) {
                                            ColgramUiKit.showNotice(this, R.drawable.msg_settings, "Настройки плагина сохранены", false);
                                        }
                                    });
                                });
                                return true;
                            },
                            null,
                            null,
                            LocaleController.getString(R.string.Cancel),
                            true,
                            false
                    );
                } catch (Throwable t) {
                    ColgramUiKit.showNotice(this, R.drawable.msg_info, "Ошибка настроек: " + t.getMessage(), true);
                }
            });
        });
    }

    private void showPluginEditorDialog(String initialName, String initialCode) {
        Activity act = getParentActivity();
        if (act == null) return;
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditTextBoldCursor nameInput = ColgramUiKit.makeStyledEditText(
                act, resourceProvider, pal, "my_plugin.py", initialName, InputType.TYPE_CLASS_TEXT, true);
        LinearLayout nameBox = ColgramUiKit.makeInputContainer(act, pal, nameInput, false);
        layout.addView(nameBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        String seedCode = (initialCode != null && !initialCode.isEmpty())
                ? initialCode
                : b64("IyBuYW1lOiDQnNC+0Lkg0L/Qu9Cw0LPQuNC9CiMgY29tbWFuZDogbXljbWQKCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ9Ce0YLQstC10YI6ICcgKyBhcmdzCg==");
        final EditTextBoldCursor codeInput = ColgramUiKit.makeStyledEditText(
                act, resourceProvider, pal, "def on_command(cmd, args): ...", seedCode,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        codeInput.setMinLines(6);
        codeInput.setMaxLines(14);
        LinearLayout codeBox = ColgramUiKit.makeInputContainer(act, pal, codeInput, false);
        layout.addView(codeBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ColgramUiKit.showCustomViewSheet(
                act,
                resourceProvider,
                R.drawable.msg_customize,
                "Python плагин",
                "Локальный скрипт расширения для Colgram",
                layout,
                "Сохранить",
                s -> {
                    String rawName = nameInput.getText().toString().trim();
                    if (rawName.isEmpty()) {
                        ColgramUiKit.showNotice(this, R.drawable.msg_info, "Укажите имя файла", true);
                        return false;
                    }
                    final String fileName = (!rawName.endsWith(".py") && !rawName.endsWith(".plugin")) ? (rawName + ".py") : rawName;
                    final String code = codeInput.getText().toString();
                    executor.execute(() -> {
                        final boolean ok = ColgramPluginManager.installPlugin(fileName, code);
                        mainHandler.post(() -> {
                            if (destroyed || getParentActivity() == null) return;
                            rebuildRows();
                            ColgramUiKit.showNotice(
                                    this,
                                    ok ? R.drawable.msg_customize : R.drawable.msg_info,
                                    ok ? "Плагин сохранён" : "Не удалось сохранить плагин",
                                    !ok
                            );
                        });
                    });
                    return true;
                },
                null,
                null,
                LocaleController.getString(R.string.Cancel),
                true,
                false
        );
    }

    // ------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            Row row = rowAt(holder.getAdapterPosition());
            return row != null && row.kind != Row.KIND_HEADER && row.kind != Row.KIND_HERO && row.kind != Row.KIND_SHADOW;
        }

        @Override
        public int getItemViewType(int position) {
            Row row = rowAt(position);
            if (row == null || row.kind == Row.KIND_HEADER) return 0;
            if (row.kind == Row.KIND_HERO) return 10;
            if (row.kind == Row.KIND_SHADOW) return 4;
            if (row.kind == Row.KIND_INSTALLED || row.kind == Row.KIND_BUILTIN || row.kind == Row.KIND_FEATURE) return 1;
            if (row.kind == Row.KIND_CATALOG || row.kind == Row.KIND_CATALOG_STATUS) return 3;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 10:
                    view = new ColgramMenuChrome.HeroBannerCell(mContext);
                    break;
                case 0:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                    view = new TextDetailSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 4:
                    view = new ShadowSectionCell(mContext);
                    break;
                case 2:
                default:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rowAt(position);
            if (row == null) return;
            Row nextRow = rowAt(position + 1);
            boolean divider = nextRow != null && nextRow.kind != Row.KIND_HEADER && nextRow.kind != Row.KIND_SHADOW;
            switch (holder.getItemViewType()) {
                case 10: {
                    ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                    List<ColgramPluginManager.PluginInfo> installedList = ColgramPluginManager.getLoadedPlugins();
                    int customCount = 0;
                    for (ColgramPluginManager.PluginInfo pi : installedList) {
                        if (!pi.builtIn) customCount++;
                    }
                    hero.bind(R.drawable.msg_customize, ColgramMenuChrome.COLOR_VIOLET,
                            "Плагины",
                            "Встроенные инструменты и Python-модули",
                            "",
                            "Встроенных: 4",
                            "Своих: " + customCount,
                            catalogLoading ? "Обновление…" : (catalogIsRemote ? "Каталог онлайн" : "Офлайн"));
                    break;
                }
                case 0: {
                    HeaderCell hc = (HeaderCell) holder.itemView;
                    hc.setText(row.header);
                    ColgramMenuChrome.styleHeader(hc, 0xFF6D28D9, 0xFFA78BFA);
                    break;
                }
                case 1: {
                    TextCheckCell c = (TextCheckCell) holder.itemView;
                    if (row.kind == Row.KIND_FEATURE) {
                        boolean on;
                        int iconRes;
                        int iconColor;
                        switch (row.featureKey) {
                            case "auto_reply":
                                on = org.colgram.core.ColgramConfig.isAutoReplyEnabled();
                                iconRes = R.drawable.msg_reply_small;
                                iconColor = ColgramMenuChrome.COLOR_BLUE;
                                break;
                            case "keywords":
                                on = org.colgram.core.ColgramConfig.isKeywordAlertsEnabled();
                                iconRes = R.drawable.msg_search;
                                iconColor = ColgramMenuChrome.COLOR_AMBER;
                                break;
                            case "logger":
                                on = org.colgram.core.ColgramConfig.isMessageLoggerEnabled();
                                iconRes = R.drawable.msg_archive;
                                iconColor = ColgramMenuChrome.COLOR_TEAL;
                                break;
                            default:
                                on = true;
                                iconRes = R.drawable.msg_download;
                                iconColor = ColgramMenuChrome.COLOR_EMERALD;
                                break;
                        }
                        ColgramMenuChrome.applyCheckIcon(c, iconColor, iconRes);
                        if (row.featureSubtitle != null && !row.featureSubtitle.isEmpty()) {
                            c.setTextAndValueAndCheck(row.featureTitle, row.featureSubtitle, on, true, divider);
                        } else {
                            c.setTextAndCheck(row.featureTitle, on, divider);
                        }
                        c.setTypeface(null);
                        break;
                    }
                    if (row.plugin != null) {
                        ColgramMenuChrome.applyCheckIcon(c,
                                row.plugin.builtIn ? ColgramMenuChrome.COLOR_INDIGO : ColgramMenuChrome.COLOR_VIOLET,
                                R.drawable.msg_customize);
                        String cmd = row.plugin.command == null || row.plugin.command.isEmpty()
                                ? row.plugin.fileName
                                : "." + row.plugin.command;
                        c.setTextAndCheck(row.plugin.name + "  (" + cmd + ")", row.plugin.isEnabled, divider);
                        c.setEnabled(true);
                    }
                    break;
                }
                case 3: {
                    TextDetailSettingsCell d = (TextDetailSettingsCell) holder.itemView;
                    if (row.kind == Row.KIND_CATALOG_STATUS) {
                        ColgramMenuChrome.applyDetailIcon(d,
                                "Все базовые модули уже активны",
                                "Штатные плагины встроены и включены выше. Добавьте сторонний .py/.plugin по ссылке или нажмите, чтобы обновить каталог.",
                                R.drawable.msg_info, ColgramMenuChrome.COLOR_CYAN, divider);
                    } else if (row.entry != null) {
                        String fn = ColgramPluginManager.fileNameFor(row.entry);
                        boolean already = ColgramPluginManager.isPluginInstalled(fn);
                        String author = row.entry.author == null || row.entry.author.isEmpty()
                                ? "" : " • " + row.entry.author;
                        String desc = row.entry.description == null ? "" : row.entry.description;
                        String sub = (already ? "Установлен" : "Установить") + author + (desc.isEmpty() ? "" : "\n" + desc);
                        ColgramMenuChrome.applyDetailIcon(d,
                                row.entry.name == null ? fn : row.entry.name, sub,
                                R.drawable.msg_download, already ? ColgramMenuChrome.COLOR_EMERALD : ColgramMenuChrome.COLOR_BLUE, divider);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (row.kind == Row.KIND_NEW_PLUGIN) {
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_add, ColgramMenuChrome.COLOR_EMERALD);
                        s.setTextAndValue("Новый плагин", "код вручную", divider);
                    } else if (row.kind == Row.KIND_INSTALL_URL) {
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_link, ColgramMenuChrome.COLOR_BLUE);
                        s.setTextAndValue("Установить по ссылке", "URL .py / .plugin", divider);
                    } else if (row.kind == Row.KIND_INSTALL_FILE) {
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_folders, ColgramMenuChrome.COLOR_AMBER);
                        s.setTextAndValue("Установить из файла", "выбрать .py", divider);
                    } else if (row.kind == Row.KIND_REFRESH_CATALOG) {
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_retry, ColgramMenuChrome.COLOR_VIOLET);
                        s.setTextAndValue("Обновить каталог", catalogLoading ? "загрузка…" : "проверить GitHub", false);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextSettingsCell.class, TextDetailSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
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
                new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextDetailSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextDetailSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
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
