package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramStorageSandbox;
import org.colgram.core.ColgramUnlimitedMemory;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramFilesActivity — Unified "Бесконечное хранилище и файлы Colgram" browser.
 *
 * Combines:
 *  - Unlimited offline cache control (media/files/videos preserved forever inside the sandbox),
 *  - Instant jump to Telegram Cloud Saved Messages ("Избранное" infinite cloud vault),
 *  - Searchable, navigable local sandbox folder with safe FileProvider open/share/import/delete.
 */
public class ColgramFilesActivity extends BaseFragment {

    private static final int REQ_IMPORT = 10977;
    private static final int ID_SEARCH = 0;
    private static final int ID_ADD_FILE = 1;
    private static final int ID_NEW_FOLDER = 2;
    private static final int ID_REFRESH = 3;

    public interface PickListener {
        void onPicked(ArrayList<String> paths);
    }

    private static PickListener pendingPickListener;

    public static void beginPick(PickListener listener) {
        pendingPickListener = listener;
    }

    private boolean pickMode;
    private boolean destroyed;
    private long lastTapMs;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private final List<File> allEntries = new ArrayList<>();
    private final List<File> visibleEntries = new ArrayList<>();
    private File currentDir;
    private File sandboxRoot;
    private String searchQuery = "";
    private String dirStatsSummary = "подсчёт...";

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Integer> folderChildCounts = new ConcurrentHashMap<>();
    private int heroRow = -1;
    private int controlsHeaderRow = -1;
    private int cloudDriveRow = -1;
    private int unlimitedCacheRow = -1;
    private int controlsShadowRow = -1;
    private int headerRow = -1;
    private int parentDirRow = -1;
    private int entriesStartRow = 0;
    private int emptyRow = -1;
    private int bottomShadowRow = -1;
    private int rowCount = 0;

    private final Comparator<File> nameComparator =
            (a, b) -> a.getName().compareToIgnoreCase(b.getName());

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        destroyed = false;
        pickMode = pendingPickListener != null;
        sandboxRoot = ColgramStorageSandbox.getSandboxRootDir(getParentActivity());
        currentDir = sandboxRoot;
        reload();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (pickMode) {
            pendingPickListener = null;
        }
        bgExecutor.shutdownNow();
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (currentDir != null && sandboxRoot != null && !sameDir(currentDir, sandboxRoot)) {
            if (invoked) {
                File parent = currentDir.getParentFile();
                currentDir = (parent != null && isInsideSandbox(parent)) ? parent : sandboxRoot;
                reload();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private boolean sameDir(File a, File b) {
        if (a == null || b == null) return false;
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private boolean isInsideSandbox(File dir) {
        if (sandboxRoot == null || dir == null) return false;
        String r = sandboxRoot.getAbsolutePath();
        String p = dir.getAbsolutePath();
        return p.equals(r) || p.startsWith(r + File.separator);
    }

    private void reload() {
        final File dir = currentDir;
        if (dir == null || destroyed) {
            allEntries.clear();
            applyFilter();
            return;
        }
        bgExecutor.execute(() -> {
            final List<File> scannedAll = new ArrayList<>();
            final List<File> dirSnapshot = new ArrayList<>();
            File[] children = dir.listFiles();
            if (children != null) {
                List<File> folders = new ArrayList<>();
                List<File> files = new ArrayList<>();
                for (File child : children) {
                    if (child == null || child.getName().startsWith(".colgram-write-probe")
                            || ".nomedia".equals(child.getName())) continue;
                    if (child.isDirectory()) {
                        folders.add(child);
                        dirSnapshot.add(child);
                    } else {
                        files.add(child);
                    }
                }
                Collections.sort(folders, nameComparator);
                Collections.sort(files, nameComparator);
                scannedAll.addAll(folders);
                scannedAll.addAll(files);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || !sameDir(dir, currentDir)) return;
                allEntries.clear();
                allEntries.addAll(scannedAll);
                applyFilter();
            });
            long[] st = ColgramStorageSandbox.computeDirStats(dir);
            for (File sub : dirSnapshot) {
                String[] ch = sub.list();
                folderChildCounts.put(sub.getAbsolutePath(), ch == null ? 0 : ch.length);
            }
            final String formatted = objects((int) st[0]) + " · " + AndroidUtilities.formatFileSize(st[1]);
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || !sameDir(dir, currentDir)) return;
                dirStatsSummary = formatted;
                if (listAdapter != null) {
                    if (heroRow >= 0) listAdapter.notifyItemChanged(heroRow);
                    if (headerRow >= 0) listAdapter.notifyItemChanged(headerRow);
                    if (!dirSnapshot.isEmpty() && !visibleEntries.isEmpty()) {
                        listAdapter.notifyItemRangeChanged(entriesStartRow, visibleEntries.size());
                    }
                }
            });
        });
    }

    private void applyFilter() {
        visibleEntries.clear();
        String q = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        for (File f : allEntries) {
            if (q.isEmpty() || f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                visibleEntries.add(f);
            }
        }
        recalculateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void recalculateRows() {
        rowCount = 0;
        if (!pickMode) {
            heroRow = rowCount++;
            controlsHeaderRow = rowCount++;
            cloudDriveRow = rowCount++;
            unlimitedCacheRow = rowCount++;
            controlsShadowRow = rowCount++;
        } else {
            heroRow = -1;
            controlsHeaderRow = -1;
            cloudDriveRow = -1;
            unlimitedCacheRow = -1;
            controlsShadowRow = -1;
        }
        headerRow = rowCount++;
        boolean inSubDir = currentDir != null && sandboxRoot != null && !sameDir(currentDir, sandboxRoot);
        parentDirRow = inSubDir ? rowCount++ : -1;
        entriesStartRow = rowCount;
        if (visibleEntries.isEmpty()) {
            emptyRow = rowCount++;
        } else {
            emptyRow = -1;
            rowCount += visibleEntries.size();
        }
        bottomShadowRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(pickMode ? "Выбрать файл из хранилища" : "Бесконечное хранилище");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!onBackPressed(true)) {
                        return;
                    }
                    finishFragment();
                } else if (id == ID_ADD_FILE) {
                    pickFiles();
                } else if (id == ID_NEW_FOLDER) {
                    promptNewFolder();
                } else if (id == ID_REFRESH) {
                    reload();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(ID_SEARCH, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                    }

                    @Override
                    public void onSearchCollapse() {
                        searchQuery = "";
                        applyFilter();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        searchQuery = editText.getText() == null ? "" : editText.getText().toString();
                        applyFilter();
                    }
                });
        searchItem.setSearchFieldHint("Поиск файлов и папок...");
        ActionBarMenuItem addMenu = menu.addItem(10, R.drawable.msg_add);
        addMenu.setContentDescription("Действия с файлами");
        addMenu.addSubItem(ID_ADD_FILE, R.drawable.msg_add, "Импортировать файлы");
        addMenu.addSubItem(ID_NEW_FOLDER, R.drawable.msg_addfolder, "Создать папку");
        addMenu.addSubItem(ID_REFRESH, R.drawable.msg_retry, "Обновить список");

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (destroyed) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastTapMs < 280L) return;
            lastTapMs = now;
            if (position == heroRow || position == controlsHeaderRow
                    || position == controlsShadowRow || position == bottomShadowRow) {
                return;
            }
            if (position == headerRow) {
                showPath(currentDir == null ? "" : currentDir.getAbsolutePath());
                return;
            }
            if (position == emptyRow) {
                pickFiles();
                return;
            }
            if (position == cloudDriveRow) {
                openSavedMessagesCloud();
                return;
            }
            if (position == unlimitedCacheRow) {
                boolean next = !ColgramConfig.isUnlimitedMemoryEnabled();
                ColgramConfig.setUnlimitedMemoryEnabled(next);
                ColgramUnlimitedMemory.apply(getParentActivity(), next);
                if (listAdapter != null) {
                    if (heroRow >= 0) listAdapter.notifyItemChanged(heroRow);
                    listAdapter.notifyItemChanged(unlimitedCacheRow);
                }
                Toast.makeText(getParentActivity(),
                        next ? "Медиа и файлы хранятся вечно без автоочистки"
                                : "Восстановлен стандартный лимит кэша",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (position == parentDirRow) {
                onBackPressed(true);
                return;
            }
            int index = position - entriesStartRow;
            if (index < 0 || index >= visibleEntries.size()) return;
            File file = visibleEntries.get(index);
            if (file.isDirectory()) {
                currentDir = file;
                reload();
            } else {
                showFileActions(file, index, position);
            }
        });

        return fragmentView;
    }

    private void openSavedMessagesCloud() {
        Activity act = getParentActivity();
        if (act == null) return;
        UserConfig uc = getUserConfig();
        if (uc == null || !uc.isClientActivated() || uc.getClientUserId() == 0L) {
            Toast.makeText(act, "Войдите в аккаунт для облачного хранилища в Избранном",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Bundle args = new Bundle();
        args.putLong("user_id", uc.getClientUserId());
        presentFragment(new ChatActivity(args));
    }

    private void promptNewFolder() {
        Activity act = getParentActivity();
        if (act == null || currentDir == null) return;
        EditTextBoldCursor input = new EditTextBoldCursor(act);
        input.setHint("Название папки");
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        input.setSingleLine(true);

        FrameLayout container = new FrameLayout(act);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
        container.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(act, resourceProvider);
        builder.setTitle("Новая папка");
        builder.setView(container);
        builder.setPositiveButton("Создать", (d, w) -> {
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            final String name = raw.replaceAll("[\\\\/:*?\"<>|]", "_");
            final File dir = currentDir;
            if (name.isEmpty() || dir == null) return;
            final Context appCtx = act.getApplicationContext();
            bgExecutor.execute(() -> {
                File sub = new File(dir, name);
                final boolean ok = sub.exists() || sub.mkdirs();
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed) return;
                    if (ok) {
                        reload();
                    } else {
                        Toast.makeText(appCtx, "Не удалось создать папку", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showPath(String path) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle("Путь хранилища Colgram");
        builder.setMessage(path);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton("Скопировать", (dialog, which) -> {
            try {
                ClipboardManager cm = (ClipboardManager) getParentActivity()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("path", path));
            } catch (Throwable ignored) {}
        });
        showDialog(builder.create());
    }

    private void showFileActions(final File file, final int entryIndex, final int adapterPos) {
        if (getParentActivity() == null) return;
        final String[] items;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        builder.setTitle(file.getName());
        if (pickMode) {
            final String path = file.getAbsolutePath();
            items = new String[]{
                    "Отправить в чат",
                    "Скопировать путь",
            };
            builder.setItems(items, (dialog, which) -> {
                if (which == 1) {
                    showPath(path);
                    return;
                }
                PickListener listener = pendingPickListener;
                pendingPickListener = null;
                pickMode = false;
                ArrayList<String> paths = new ArrayList<>();
                paths.add(path);
                finishFragment();
                if (listener != null) {
                    listener.onPicked(paths);
                }
            });
        } else {
            items = new String[]{
                    "Открыть",
                    "Поделиться / В Избранное",
                    "Переименовать",
                    "Удалить",
                    "Скопировать путь",
            };
            builder.setItems(items, (dialog, which) -> {
                if (which == 0) {
                    openFile(file);
                } else if (which == 1) {
                    shareFile(file);
                } else if (which == 2) {
                    renameFile(file);
                } else if (which == 3) {
                    deleteFile(file, entryIndex, adapterPos);
                } else {
                    showPath(file.getAbsolutePath());
                }
            });
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openFile(File file) {
        if (getParentActivity() == null) return;
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    getParentActivity(), getParentActivity().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeFor(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(getParentActivity(), "Нет приложения, чем открыть этот файл",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile(File file) {
        if (getParentActivity() == null) return;
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    getParentActivity(), getParentActivity().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeFor(file.getName()));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, "Отправить / сохранить файл"));
        } catch (Throwable t) {
            Toast.makeText(getParentActivity(), "Не удалось поделиться файлом", Toast.LENGTH_SHORT).show();
        }
    }

    private void renameFile(File file) {
        Activity act = getParentActivity();
        if (act == null || file == null) return;
        EditTextBoldCursor input = new EditTextBoldCursor(act);
        input.setText(file.getName());
        input.setSelection(file.getName().length());
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        input.setSingleLine(true);

        FrameLayout container = new FrameLayout(act);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
        container.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(act, resourceProvider);
        builder.setTitle("Переименовать");
        builder.setView(container);
        builder.setPositiveButton("Сохранить", (d, w) -> {
            String raw = input.getText() == null ? "" : input.getText().toString().trim();
            final String next = raw.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (next.isEmpty()) return;
            final Context appCtx = act.getApplicationContext();
            bgExecutor.execute(() -> {
                File dest = new File(file.getParentFile(), next);
                final boolean ok = !dest.exists() && file.renameTo(dest);
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed) return;
                    if (ok) {
                        reload();
                    } else {
                        Toast.makeText(appCtx, "Не удалось переименовать", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void deleteFile(File file, int entryIndex, int adapterPos) {
        if (file == null || destroyed) return;
        Activity act = getParentActivity();
        final Context appCtx = act != null ? act.getApplicationContext() : ApplicationLoader.applicationContext;
        final File dirForStats = currentDir;
        bgExecutor.execute(() -> {
            boolean deleted = false;
            try {
                deleted = file.delete();
            } catch (Throwable ignored) {}
            final boolean ok = deleted;
            final String formattedStats;
            if (ok && dirForStats != null) {
                long[] st = ColgramStorageSandbox.computeDirStats(dirForStats);
                formattedStats = objects((int) st[0]) + " · " + AndroidUtilities.formatFileSize(st[1]);
            } else {
                formattedStats = null;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                if (ok) {
                    allEntries.remove(file);
                    visibleEntries.remove(file);
                    recalculateRows();
                    if (formattedStats != null) {
                        dirStatsSummary = formattedStats;
                    }
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                } else if (appCtx != null) {
                    Toast.makeText(appCtx, "Не удалось удалить файл", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif")) return "image/*";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")) return "video/*";
        if (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) return "audio/*";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        return "*/*";
    }

    private void pickFiles() {
        if (getParentActivity() == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            getParentActivity().startActivityForResult(
                    Intent.createChooser(intent, "Выбрать файлы"), REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(getParentActivity(), "Не удалось открыть выбор файлов",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null) return;
        Activity act = getParentActivity();
        if (act == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final File targetDir = currentDir != null ? currentDir : sandboxRoot;
        final Context appContext = act.getApplicationContext();
        if (targetDir == null) return;
        bgExecutor.execute(() -> {
            int copied = 0;
            for (Uri uri : uris) {
                if (copyInto(appContext, uri, targetDir)) copied++;
            }
            final int total = copied;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) return;
                reload();
                Toast.makeText(appContext, "Скопировано в хранилище Colgram: " + total,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static boolean copyInto(Context context, Uri uri, File targetDir) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            String name = displayName(context, uri);
            File out = new File(targetDir, name);
            int suffix = 1;
            while (out.exists()) {
                out = new File(targetDir, suffix + "_" + name);
                suffix++;
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    fos.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {}
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String value = cursor.getString(idx);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return last != null && !last.isEmpty() ? last : "file-" + System.currentTimeMillis();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int p = holder.getAdapterPosition();
            return p != heroRow && p != controlsHeaderRow && p != controlsShadowRow && p != bottomShadowRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroRow) return 10;
            if (position == controlsShadowRow || position == bottomShadowRow) return 2;
            if (position == headerRow || position == controlsHeaderRow) return 0;
            return 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 10) {
                view = new ColgramMenuChrome.HeroBannerCell(mContext);
            } else if (viewType == 2) {
                view = new ShadowSectionCell(mContext, resourceProvider);
            } else if (viewType == 0) {
                view = new HeaderCell(mContext, resourceProvider);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
            } else {
                view = new TextSettingsCell(mContext, resourceProvider);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == heroRow) {
                ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                boolean unlim = ColgramConfig.isUnlimitedMemoryEnabled();
                hero.bind(R.drawable.settings_folders,
                        unlim ? ColgramMenuChrome.COLOR_EMERALD : ColgramMenuChrome.COLOR_AMBER,
                        "Бесконечное хранилище",
                        "Песочница файлов Colgram, безлимитный кэш медиа и быстрый доступ к облаку Избранного",
                        unlim ? "БЕЗЛИМИТ" : "СТАНДАРТ",
                        dirStatsSummary,
                        "Sandbox изоляция",
                        "Облако TG");
                return;
            }
            if (position == controlsShadowRow || position == bottomShadowRow) return;
            if (position == controlsHeaderRow) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                cell.setText("Облако и безлимитный кэш");
                ColgramMenuChrome.styleHeader(cell, ColgramMenuChrome.COLOR_TEAL, 0xFF2DD4BF);
                return;
            }
            if (position == headerRow) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                String rel = (currentDir != null && sandboxRoot != null && !sameDir(currentDir, sandboxRoot))
                        ? currentDir.getName()
                        : "Файлы в песочнице";
                cell.setText(rel + " · " + dirStatsSummary);
                ColgramMenuChrome.styleHeader(cell, ColgramMenuChrome.COLOR_AMBER, 0xFFFBBF24);
                return;
            }
            TextSettingsCell cell = (TextSettingsCell) holder.itemView;
            if (position == cloudDriveRow) {
                ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_archive, ColgramMenuChrome.COLOR_CYAN);
                cell.setTextAndValue("Облачное хранилище (Избранное)", "Без лимита", true);
                return;
            }
            if (position == unlimitedCacheRow) {
                ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_clearcache, ColgramMenuChrome.COLOR_EMERALD);
                boolean on = ColgramConfig.isUnlimitedMemoryEnabled();
                cell.setTextAndValue("Безлимитный кэш медиа", on ? "Вкл (вечно)" : "Стандарт", false);
                return;
            }
            if (position == parentDirRow) {
                ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_folders, ColgramMenuChrome.COLOR_SLATE);
                cell.setTextAndValue(".. (Назад в папку выше)", "", !visibleEntries.isEmpty() || emptyRow >= 0);
                return;
            }
            if (position == emptyRow) {
                ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_add, ColgramMenuChrome.COLOR_BLUE);
                cell.setTextAndValue(
                        TextUtils.isEmpty(searchQuery) ? "Папка пока пуста" : "Ничего не найдено",
                        TextUtils.isEmpty(searchQuery) ? "Импортировать" : "Очистите поиск",
                        false);
                return;
            }
            int index = position - entriesStartRow;
            if (index < 0 || index >= visibleEntries.size()) return;
            File file = visibleEntries.get(index);
            boolean divider = index < visibleEntries.size() - 1;
            if (file.isDirectory()) {
                Integer cached = folderChildCounts.get(file.getAbsolutePath());
                String countLabel = cached == null ? "…" : objects(cached);
                ColgramMenuChrome.applySettingIcon(cell, R.drawable.msg_folders, ColgramMenuChrome.COLOR_AMBER);
                cell.setTextAndValue(file.getName(), countLabel, divider);
            } else {
                String lower = file.getName().toLowerCase(Locale.US);
                int iconRes = R.drawable.msg_view_file;
                int iconCol = ColgramMenuChrome.COLOR_BLUE;
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                    iconRes = R.drawable.msg_photos;
                    iconCol = ColgramMenuChrome.COLOR_ROSE;
                } else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")) {
                    iconRes = R.drawable.msg_media;
                    iconCol = ColgramMenuChrome.COLOR_VIOLET;
                } else if (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) {
                    iconRes = R.drawable.msg_media;
                    iconCol = ColgramMenuChrome.COLOR_CYAN;
                } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) {
                    iconRes = R.drawable.msg_archive;
                    iconCol = ColgramMenuChrome.COLOR_AMBER;
                }
                ColgramMenuChrome.applySettingIcon(cell, iconRes, iconCol);
                cell.setTextAndValue(file.getName(), AndroidUtilities.formatFileSize(file.length()), divider);
            }
        }
    }

    private static String objects(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        String word;
        if (mod10 == 1 && mod100 != 11) word = "объект";
        else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) word = "объекта";
        else word = "объектов";
        return n + " " + word;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
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
