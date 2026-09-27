package org.telegram.ui;

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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramPluginManager;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
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
        actionBar.createMenu().addItem(100, R.drawable.msg_add).setContentDescription("Новый плагин");
        actionBar.createMenu().addItem(101, R.drawable.msg_link).setContentDescription("Установить по ссылке");
        actionBar.createMenu().addItem(102, R.drawable.msg_filehq).setContentDescription("Установить из файла");
        ActionBarMenuItem refresh = actionBar.createMenu().addItem(103, R.drawable.msg_retry);
        refresh.setContentDescription("Обновить каталог");

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
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
                case Row.KIND_FEATURE:
                    handleFeatureTap(row);
                    break;
                case Row.KIND_INSTALLED:
                case Row.KIND_BUILTIN:
                    if (row.plugin != null) {
                        ColgramPluginManager.togglePlugin(row.plugin.fileName, !row.plugin.isEnabled);
                        listAdapter.notifyDataSetChanged();
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

        rows.add(new Row(Row.KIND_HEADER, "Создать плагин", null, null));
        rows.add(new Row(Row.KIND_NEW_PLUGIN, null, null, null));

        List<ColgramPluginManager.PluginInfo> installed = ColgramPluginManager.getLoadedPlugins();
        int builtInCount = 0;
        for (ColgramPluginManager.PluginInfo p : installed) if (p.builtIn) builtInCount++;
        if (builtInCount > 0) {
            // These are Colgram's own features that happen to be implemented as scripts, not
            // marketplace plugins. Calling them plugins put them in the same list a user
            // uninstalls from, which is the wrong model: they cannot be removed and are not
            // something that was ever installed.
            rows.add(new Row(Row.KIND_HEADER,
                    "Встроенные возможности Colgram (" + builtInCount + ")", null, null));
            for (ColgramPluginManager.PluginInfo p : installed) {
                if (p.builtIn) rows.add(new Row(Row.KIND_BUILTIN, null, p, null));
            }
        }
        rows.add(new Row(Row.KIND_HEADER, "Пользовательские плагины (" + (installed.size() - builtInCount) + ")", null, null));
        for (ColgramPluginManager.PluginInfo p : installed) {
            if (!p.builtIn) rows.add(new Row(Row.KIND_INSTALLED, null, p, null));
        }

        String catHeader;
        if (catalogLoading) {
            catHeader = "Маркетплейс — загрузка...";
        } else if (catalog.isEmpty()) {
            catHeader = "Маркетплейс пуст";
        } else if (catalogIsRemote) {
            catHeader = "Маркетплейс (" + catalog.size() + ")";
        } else {
            catHeader = "Маркетплейс (" + catalog.size() + ", офлайн-набор)";
        }
        rows.add(new Row(Row.KIND_HEADER, catHeader, null, null));
        for (ColgramPluginManager.CatalogEntry e : catalog) {
            rows.add(new Row(Row.KIND_CATALOG, null, null, e));
        }

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
                Toast.makeText(getParentActivity(),
                        ar ? "Автоответчик включён" : "Автоответчик выключен", Toast.LENGTH_SHORT).show();
                listAdapter.notifyDataSetChanged();
                break;
            case "keywords":
                boolean kw = !org.colgram.core.ColgramConfig.isKeywordAlertsEnabled();
                org.colgram.core.ColgramConfig.setKeywordAlertsEnabled(kw);
                if (kw && org.colgram.core.ColgramConfig.getKeywordAlertsList().trim().isEmpty()) {
                    showKeywordsDialog();
                } else {
                    Toast.makeText(getParentActivity(),
                            kw ? "Оповещения по ключевым словам включены" : "Ключевые слова выключены",
                            Toast.LENGTH_SHORT).show();
                }
                listAdapter.notifyDataSetChanged();
                break;
            case "logger":
                boolean lg = !org.colgram.core.ColgramConfig.isMessageLoggerEnabled();
                org.colgram.core.ColgramConfig.setMessageLoggerEnabled(lg);
                Toast.makeText(getParentActivity(),
                        lg ? "Журнал сообщений включён" : "Журнал сообщений выключен",
                        Toast.LENGTH_SHORT).show();
                listAdapter.notifyDataSetChanged();
                break;
            case "export":
                Toast.makeText(getParentActivity(),
                        "Откройте чат и отправьте .export — файл появится в Colgram/exports",
                        Toast.LENGTH_LONG).show();
                break;
            default:
                break;
        }
    }

    /** One-line editor for the keyword list; enabling without words opens it automatically. */
    private void showKeywordsDialog() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ключевые слова");
        android.widget.EditText input = new android.widget.EditText(getParentActivity());
        input.setHint("через запятую: работа, счёт, дедлайн");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setText(org.colgram.core.ColgramConfig.getKeywordAlertsList());
        input.setSelection(input.getText().length());
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(getParentActivity());
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), 0);
        wrap.addView(input);
        builder.setView(wrap);
        builder.setPositiveButton("Сохранить", (d, w) -> {
            org.colgram.core.ColgramConfig.setKeywordAlertsList(input.getText().toString());
            listAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
            // Leaving the list empty leaves the toggle meaningless; switch it back off.
            if (org.colgram.core.ColgramConfig.getKeywordAlertsList().trim().isEmpty()) {
                org.colgram.core.ColgramConfig.setKeywordAlertsEnabled(false);
            }
            listAdapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
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
                    Toast.makeText(getParentActivity(),
                            isRemote ? "Каталог обновлён: " + result.size()
                                    : "Сеть недоступна — показан встроенный набор",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void installCatalogEntry(ColgramPluginManager.CatalogEntry e) {
        if (getParentActivity() == null) return;
        final String fileName = ColgramPluginManager.fileNameFor(e);
        boolean already = ColgramPluginManager.isPluginInstalled(fileName);

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(e.name == null ? "Плагин" : e.name);
        StringBuilder msg = new StringBuilder();
        if (e.description != null && !e.description.isEmpty()) msg.append(e.description).append("\n\n");
        if (e.author != null && !e.author.isEmpty()) msg.append("Автор: ").append(e.author).append("\n");
        if (e.version != null && !e.version.isEmpty()) msg.append("Версия: ").append(e.version).append("\n");
        msg.append("Файл: ").append(fileName).append("\n");
        if (already) msg.append("\nУже установлен — будет перезаписан.");
        builder.setMessage(msg.toString().trim());

        builder.setPositiveButton(already ? "Переустановить" : "Установить", (d, w) -> {
            executor.execute(() -> {
                boolean ok = false;
                String err = null;
                try {
                    if (e.downloadUrl != null && !e.downloadUrl.trim().isEmpty()) {
                        ok = ColgramPluginManager.installPluginFromUrl(e.downloadUrl);
                        if (!ok) err = "не удалось скачать " + e.downloadUrl;
                    } else {
                        // Bundled entries carry no URL: they are installed from the copy
                        // already inside the APK, which is why the fallback catalog always
                        // has a working install path.
                        ok = ColgramPluginManager.installBundledPlugin(fileName);
                        if (!ok) err = "встроенный плагин " + fileName + " не найден";
                    }
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final boolean success = ok;
                final String failure = err;
                mainHandler.post(() -> {
                    rebuildRows();
                    if (getParentActivity() == null) return;
                    Toast.makeText(getParentActivity(),
                            success ? "Установлено: " + e.name : "Ошибка: " + failure,
                            success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                });
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // ------------------------------------------------------------ install paths

    /** Install from a raw .py URL typed by the user. */
    private void showInstallFromUrlDialog() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Установить по ссылке");

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(4));

        final EditText urlInput = new EditText(getParentActivity());
        urlInput.setHint("https://example.com/my_plugin.py");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        urlInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        layout.addView(urlInput);
        builder.setView(layout);

        builder.setPositiveButton("Установить", (d, w) -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) return;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(getParentActivity(), "Ссылка должна начинаться с http:// или https://", Toast.LENGTH_LONG).show();
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
                    rebuildRows();
                    if (getParentActivity() == null) return;
                    Toast.makeText(getParentActivity(),
                            success ? "Плагин установлен" : "Ошибка: " + failure,
                            success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                });
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
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
            Toast.makeText(getParentActivity(),
                    "Не удалось открыть файловый менеджер: " + t.getMessage(), Toast.LENGTH_LONG).show();
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
                rebuildRows();
                if (getParentActivity() == null) return;
                Toast.makeText(getParentActivity(),
                        success ? "Плагин установлен из файла" : "Ошибка: " + failure,
                        success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
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
        if (getParentActivity() == null) return;
        String[] options = {"Редактировать код", "Удалить плагин", "Отмена"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(p.name);
        builder.setItems(options, (d, w) -> {
            if (w == 0) {
                String code = ColgramPluginManager.getPluginCode(p.fileName);
                showPluginEditorDialog(p.fileName, code);
            } else if (w == 1) {
                ColgramPluginManager.deletePlugin(p.fileName);
                rebuildRows();
                Toast.makeText(getParentActivity(), "Плагин удалён", Toast.LENGTH_SHORT).show();
            }
        });
        showDialog(builder.create());
    }

    private void showPluginEditorDialog(String initialName, String initialCode) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Python плагин");

        LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(12));

        final EditText nameInput = new EditText(getParentActivity());
        nameInput.setHint("my_plugin.py");
        nameInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        nameInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        if (initialName != null) nameInput.setText(initialName);
        layout.addView(nameInput);

        final EditText codeInput = new EditText(getParentActivity());
        codeInput.setHint("def on_command(cmd, args): ...");
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(13);
        codeInput.setMinLines(6);
        codeInput.setGravity(Gravity.TOP | Gravity.START);
        codeInput.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        codeInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        if (initialCode != null && !initialCode.isEmpty()) {
            codeInput.setText(initialCode);
        } else {
            codeInput.setText(b64("IyBuYW1lOiDQnNC+0Lkg0L/Qu9Cw0LPQuNC9CiMgY29tbWFuZDogbXljbWQKCmRlZiBvbl9jb21tYW5kKGNtZCwgYXJncyk6CiAgICByZXR1cm4gJ9Ce0YLQstC10YI6ICcgKyBhcmdzCg=="));
        }
        layout.addView(codeInput);

        builder.setView(layout);
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(getParentActivity(), "Укажите имя файла", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!name.endsWith(".py")) name += ".py";
            String code = codeInput.getText().toString();
            boolean ok = ColgramPluginManager.installPlugin(name, code);
            rebuildRows();
            Toast.makeText(getParentActivity(),
                    ok ? "Плагин сохранён" : "Не удалось сохранить плагин",
                    ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
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
            return row != null && row.kind != Row.KIND_HEADER;
        }

        @Override
        public int getItemViewType(int position) {
            Row row = rowAt(position);
            if (row == null || row.kind == Row.KIND_HEADER) return 0;
            if (row.kind == Row.KIND_INSTALLED || row.kind == Row.KIND_BUILTIN || row.kind == Row.KIND_FEATURE) return 1;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext);
                    break;
                case 1:
                    view = new TextCheckCell(mContext);
                    break;
                case 2:
                default:
                    view = new TextSettingsCell(mContext);
                    break;
            }
            // Theme-driven background so this screen is legible in light and dark alike.
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rowAt(position);
            if (row == null) return;
            switch (holder.getItemViewType()) {
                case 0:
                    ((HeaderCell) holder.itemView).setText(row.header);
                    break;
                case 1: {
                    TextCheckCell c = (TextCheckCell) holder.itemView;
                    if (row.kind == Row.KIND_FEATURE) {
                        boolean on;
                        switch (row.featureKey) {
                            case "auto_reply": on = org.colgram.core.ColgramConfig.isAutoReplyEnabled(); break;
                            case "keywords": on = org.colgram.core.ColgramConfig.isKeywordAlertsEnabled(); break;
                            case "logger": on = org.colgram.core.ColgramConfig.isMessageLoggerEnabled(); break;
                            default: on = true; break;
                        }
                        c.setTextAndCheck(row.featureTitle, on, true);
                        c.setTypeface(null);
                        break;
                    }
                    if (row.plugin != null) {
                        String cmd = row.plugin.command == null || row.plugin.command.isEmpty()
                                ? row.plugin.fileName
                                : "." + row.plugin.command;
                        c.setTextAndCheck(row.plugin.name + "  (" + cmd + ")", row.plugin.isEnabled, true);
                        c.setEnabled(true);
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (row.kind == Row.KIND_NEW_PLUGIN) {
                        s.setTextAndValue("Новый плагин", "написать код вручную", true);
                    } else if (row.entry != null) {
                        String fn = ColgramPluginManager.fileNameFor(row.entry);
                        boolean already = ColgramPluginManager.isPluginInstalled(fn);
                        String author = row.entry.author == null || row.entry.author.isEmpty()
                                ? "" : " • " + row.entry.author;
                        s.setTextAndValue(row.entry.name == null ? fn : row.entry.name,
                                (already ? "Установлен" : "Установить") + author, false);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }
}
