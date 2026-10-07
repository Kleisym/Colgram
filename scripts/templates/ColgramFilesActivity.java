package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramStorageSandbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ColgramFilesActivity — the browser for Colgram's own file folder.
 *
 * The point is that attaching a file does not have to mean granting Telegram the whole media
 * library: files that belong to the app live in one folder, and this screen is how you see and
 * fill it. Everything here works on plain File APIs, so it needs no runtime permission at all —
 * the sandbox root is either Documents/Colgram (when the system lets us write there) or the app's
 * own external directory, and ColgramStorageSandbox has already picked whichever is real.
 */
public class ColgramFilesActivity extends BaseFragment {

    private static final int REQ_IMPORT = 10977;

    /**
     * One-shot handoff used by the attachment sheet: it registers a listener, presents this
     * screen, and the chosen file comes back as a list of local paths that
     * ChatActivity.didSelectFiles can send as an ordinary document. Static because the attach
     * sheet dismisses itself when it presents us, so there is no surviving caller to hold a
     * reference; it is cleared on pick and on destroy so it cannot outlive the flow.
     */
    public interface PickListener {
        void onPicked(ArrayList<String> paths);
    }

    private static PickListener pendingPickListener;

    public static void beginPick(PickListener listener) {
        pendingPickListener = listener;
    }

    private boolean pickMode;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private final List<File> entries = new ArrayList<>();
    private File currentDir;
    private File sandboxRoot;

    private int headerRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        pickMode = pendingPickListener != null;
        sandboxRoot = ColgramStorageSandbox.getSandboxRootDir(getParentActivity());
        currentDir = sandboxRoot;
        reload();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        // A pick that was abandoned must not leave a listener pointing at a dismissed sheet.
        if (pickMode) {
            pendingPickListener = null;
        }
        super.onFragmentDestroy();
    }

    private void reload() {
        entries.clear();
        File dir = currentDir;
        if (dir != null) {
            File[] children = dir.listFiles();
            if (children != null) {
                List<File> folders = new ArrayList<>();
                List<File> files = new ArrayList<>();
                for (File child : children) {
                    if (child == null || child.getName().startsWith(".colgram-write-probe")) continue;
                    if (child.isDirectory()) folders.add(child);
                    else files.add(child);
                }
                java.util.Collections.sort(folders, nameComparator);
                java.util.Collections.sort(files, nameComparator);
                entries.addAll(folders);
                entries.addAll(files);
            }
        }
        rowCount = entries.size() + 1;
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private final java.util.Comparator<File> nameComparator =
            (a, b) -> a.getName().compareToIgnoreCase(b.getName());

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(pickMode ? "Выбрать файл из папки" : "Папка Colgram");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    pickFiles();
                } else if (id == 2) {
                    reload();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        actionBar.createMenu().addItem(1, R.drawable.msg_add);
        actionBar.createMenu().addItem(2, R.drawable.msg_retry);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position == headerRow) {
                showPath(sandboxRoot == null ? "" : sandboxRoot.getAbsolutePath());
                return;
            }
            int index = position - 1;
            if (index < 0 || index >= entries.size()) return;
            File file = entries.get(index);
            if (file.isDirectory()) {
                currentDir = file;
                reload();
            } else {
                showFileActions(file);
            }
        });

        return fragmentView;
    }

    private void showPath(String path) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Папка Colgram");
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

    private void showFileActions(final File file) {
        if (getParentActivity() == null) return;
        final String[] items;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
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
                    "Удалить",
                    "Скопировать путь",
            };
            builder.setItems(items, (dialog, which) -> {
                if (which == 0) {
                    openFile(file);
                } else if (which == 1) {
                    deleteFile(file);
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
            // A file:// URI throws FileUriExposedException on anything modern, so hand the
            // receiver a grant through the provider the app already declares - its root-path
            // entry covers /storage/, which is where both sandbox candidates live.
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

    private void deleteFile(File file) {
        try {
            if (file.delete()) {
                reload();
            } else {
                Toast.makeText(getParentActivity(), "Не удалось удалить файл", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {}
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase();
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
        final Context appContext = getParentActivity().getApplicationContext();
        if (targetDir == null) return;
        new Thread(() -> {
            int copied = 0;
            for (Uri uri : uris) {
                if (copyInto(appContext, uri, targetDir)) copied++;
            }
            final int total = copied;
            AndroidUtilities.runOnUIThread(() -> {
                reload();
                Toast.makeText(appContext, "Скопировано в папку Colgram: " + total,
                        Toast.LENGTH_SHORT).show();
            });
        }, "colgram-folder-import").start();
    }

    /**
     * Copy one SAF document into the folder. The document URI is readable without any permission
     * for as long as we hold it, so this is the whole point of the screen: files come in through
     * the picker, nothing ever scans the device.
     */
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
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            return position == headerRow ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == headerRow) {
                HeaderCell cell = (HeaderCell) holder.itemView;
                String path = sandboxRoot == null ? "—" : sandboxRoot.getAbsolutePath();
                cell.setText(path);
                return;
            }
            int index = position - 1;
            if (index < 0 || index >= entries.size()) return;
            File file = entries.get(index);
            TextSettingsCell cell = (TextSettingsCell) holder.itemView;
            if (file.isDirectory()) {
                int count = file.list() == null ? 0 : file.list().length;
                cell.setTextAndValue(file.getName(), objects(count), false);
            } else {
                cell.setTextAndValue(file.getName(), AndroidUtilities.formatFileSize(file.length()), false);
            }
        }
    }

    /** Russian numeral agreement: 1 объект, 2 объекта, 5 объектов, 11 объектов. */
    private static String objects(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        String word;
        if (mod10 == 1 && mod100 != 11) word = "объект";
        else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) word = "объекта";
        else word = "объектов";
        return n + " " + word;
    }
}
