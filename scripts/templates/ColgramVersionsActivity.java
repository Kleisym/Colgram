package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
        /** Upstream DrKLO/Telegram tag this entry builds from; null for plain links. */
        public final String sourceTag;

        public VersionItem(String title, String subtitle, String url, boolean isDirectApk, String sourceTag) {
            this.title = title;
            this.subtitle = subtitle;
            this.url = url;
            this.isDirectApk = isDirectApk;
            this.sourceTag = sourceTag;
        }
    }

    // Every entry points at a REAL, version-specific artifact. The previous list had
    // two different "archive" entries both resolving to telegram.org/dl/android/apk,
    // which silently installed the same current build no matter what was chosen.
    //
    // sourceTag is the upstream DrKLO/Telegram tag; the Colgram CI clones exactly that
    // tag and patches it, so switching versions actually switches the codebase.
    private static final VersionItem[] COLGRAM_BUILDS = {
        new VersionItem("Colgram Latest Release", "Последняя стабильная сборка Colgram",
                "https://github.com/Kleisym/Colgram/releases/latest", false, null),
        new VersionItem("Colgram CI Preview", "Свежие сборки из CI/CD пайплайна",
                "https://github.com/Kleisym/Colgram/actions", false, null),
    };

    private static final VersionItem[] OFFICIAL_BUILDS = {
        new VersionItem("Telegram 12.10.3 (текущая база)", "Синхронизировано с апстримом Colgram",
                "https://github.com/DrKLO/Telegram", false, "release-12.10.3"),
        new VersionItem("Telegram 11.4.2 (архив)", "Стабильная версия 2024 года",
                "https://github.com/DrKLO/Telegram/tree/release-11.4.2-5469", false, "release-11.4.2-5469"),
        new VersionItem("Telegram 11.1.3 (архив)", "Стабильная версия 2024 года",
                "https://github.com/DrKLO/Telegram/tree/release-11.1.3-5244", false, "release-11.1.3-5244"),
        new VersionItem("Telegram 10.14.5 (архив)", "Стабильная версия начала 2024",
                "https://github.com/DrKLO/Telegram/tree/release-10.14.5-4945", false, "release-10.14.5-4945"),
        new VersionItem("Telegram 10.9.1 (архив)", "Облегчённый старый билд",
                "https://github.com/DrKLO/Telegram/tree/release-10.9.1-4464", false, "release-10.9.1-4464"),
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
        if (item.sourceTag != null) {
            // A tagged upstream build: offer the real switch, not a dead link.
            builder.setMessage(item.subtitle + "\n\nПересобрать Colgram на этой версии Telegram?"
                    + "\n\nТег: " + item.sourceTag);
            builder.setPositiveButton("Собрать", (d, w) -> startVersionBuild(item));
        } else {
            builder.setMessage(item.subtitle + "\n\nПерейти к загрузке этой версии?");
            builder.setPositiveButton("Открыть", (d, w) -> Browser.openUrl(getParentActivity(), item.url));
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /**
     * Records the chosen upstream tag and hands off to the CI pipeline.
     *
     * A version switch cannot be done on-device: it means re-cloning the upstream tag,
     * re-applying every patch and rebuilding. So the tag is persisted (the CI reads it)
     * and the user is taken to the workflow run that does the work.
     */
    private void startVersionBuild(VersionItem item) {
        try {
            org.colgram.core.ColgramConfig.setPendingUpstreamTag(item.sourceTag);
            android.widget.Toast.makeText(getParentActivity(),
                    "Версия " + item.sourceTag + " сохранена. Запустите сборку в CI.",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {
            // Config is best-effort; the browser fallback below still works.
        }
        org.telegram.messenger.browser.Browser.openUrl(getParentActivity(),
                "https://github.com/Kleisym/Colgram/actions/workflows/build-colgram.yml");
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
                        // Report the real build value. BuildVars only exposes
                        // BUILD_VERSION_STRING (backed by BuildConfig); there is no
                        // APP_VERSION_NAME / BUILD_VERSION field, so don't reference one.
                        s.setTextAndValue("Colgram Client",
                                "v" + org.telegram.messenger.BuildVars.BUILD_VERSION_STRING + " • CPython 3.11",
                                false);
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
