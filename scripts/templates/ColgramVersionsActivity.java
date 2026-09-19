package org.telegram.ui;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class ColgramVersionsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private static class VersionItem {
        public final String title;
        public final String subtitle;
        public final String url;
        public final boolean isDirectApk;

        public VersionItem(String title, String subtitle, String url, boolean isDirectApk) {
            this.title = title;
            this.subtitle = subtitle;
            this.url = url;
            this.isDirectApk = isDirectApk;
        }
    }

    private static final VersionItem[] COLGRAM_BUILDS = {
        new VersionItem("🚀 Colgram Latest Release", "Стабильная сборка (GitHub Releases)", "https://github.com/Kleisym/Colgram/releases/latest", false),
        new VersionItem("⚡ Colgram Actions Preview", "Свежие сборки из CI/CD пайплайна", "https://github.com/Kleisym/Colgram/actions", false),
    };

    private static final VersionItem[] OFFICIAL_BUILDS = {
        new VersionItem("Telegram Android 11.1 (Официальный APK)", "Официальный клиент без цензуры Google Play", "https://telegram.org/dl/android/apk", true),
        new VersionItem("Telegram Android Beta Channel", "Бета-версии Telegram из App Center", "https://t.me/tgandroidbeta", false),
        new VersionItem("Telegram Android 10.14.5 (Архив)", "Стабильная предыдущая версия", "https://telegram.org/dl/android/apk", true),
        new VersionItem("Telegram Android 10.9 (Архив)", "Легковесный архивный билд", "https://telegram.org/dl/android/apk", true),
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Версии Telegram и обновления");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

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
            if (position >= 3 && position < 3 + COLGRAM_BUILDS.length) {
                VersionItem item = COLGRAM_BUILDS[position - 3];
                confirmDownload(item);
            } else {
                int offIndex = position - (3 + COLGRAM_BUILDS.length + 2);
                if (offIndex >= 0 && offIndex < OFFICIAL_BUILDS.length) {
                    VersionItem item = OFFICIAL_BUILDS[offIndex];
                    confirmDownload(item);
                }
            }
        });

        return fragmentView;
    }

    private void confirmDownload(VersionItem item) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.title);
        builder.setMessage(item.subtitle + "\n\nПерейти к загрузке и установке этой версии?");
        builder.setPositiveButton("Скачать", (d, w) -> {
            if (item.isDirectApk) {
                downloadDirectApk(item.title, item.url);
            } else {
                Browser.openUrl(getParentActivity(), item.url);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void downloadDirectApk(String title, String url) {
        try {
            if (getParentActivity() == null) return;
            DownloadManager dm = (DownloadManager) getParentActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(title);
            req.setDescription("Загрузка APK файла Telegram...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Telegram_Download.apk");
            dm.enqueue(req);
            Toast.makeText(getParentActivity(), "📥 Загрузка началась. Проверьте шторку уведомлений.", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Browser.openUrl(getParentActivity(), url);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return 1 + 1 + 1 + COLGRAM_BUILDS.length + 1 + 1 + OFFICIAL_BUILDS.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos == 0 || pos == 2) return false;
            int shadowPos = 3 + COLGRAM_BUILDS.length;
            if (pos == shadowPos || pos == shadowPos + 1) return false;
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 || position == 2 || position == 3 + COLGRAM_BUILDS.length + 1) {
                return 0;
            } else if (position == 3 + COLGRAM_BUILDS.length) {
                return 3;
            }
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    HeaderCell h = (HeaderCell) holder.itemView;
                    if (position == 0) h.setText("Текущая сборка");
                    else if (position == 2) h.setText("Сборки Colgram");
                    else h.setText("Официальные версии Telegram");
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (position == 1) {
                        s.setTextAndValue("Colgram Client", "v11.1.3 (Build 45) • CPython 3.11", false);
                    } else if (position >= 3 && position < 3 + COLGRAM_BUILDS.length) {
                        int idx = position - 3;
                        VersionItem item = COLGRAM_BUILDS[idx];
                        s.setTextAndValue(item.title, item.subtitle, idx < COLGRAM_BUILDS.length - 1);
                    } else {
                        int offIdx = position - (3 + COLGRAM_BUILDS.length + 2);
                        if (offIdx >= 0 && offIdx < OFFICIAL_BUILDS.length) {
                            VersionItem item = OFFICIAL_BUILDS[offIdx];
                            s.setTextAndValue(item.title, item.subtitle, offIdx < OFFICIAL_BUILDS.length - 1);
                        }
                    }
                    break;
                }
            }
        }
    }
}
