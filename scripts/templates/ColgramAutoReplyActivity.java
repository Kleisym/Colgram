package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Auto-reply settings, opened from Settings -> Notifications.
 *
 * The reply itself is sent from ColgramHookHandler on the same inbound dispatch that feeds the
 * plugin engine, so what this screen configures is exactly what that hook reads: on/off, the
 * text, whether groups answer too, and a per-dialog cooldown that keeps a busy chat from
 * turning the account into a spam cannon.
 */
public class ColgramAutoReplyActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int headerRow;
    private int enabledRow;
    private int groupsRow;
    private int textRow;
    private int cooldownRow;
    private int sectionRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        ColgramConfig.init(getParentActivity());
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        headerRow = rowCount++;
        enabledRow = rowCount++;
        groupsRow = rowCount++;
        textRow = rowCount++;
        cooldownRow = rowCount++;
        sectionRow = rowCount++;
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Автоответчик");
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
            if (position == enabledRow) {
                boolean val = !ColgramConfig.isAutoReplyEnabled();
                ColgramConfig.setAutoReplyEnabled(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == groupsRow) {
                boolean val = !ColgramConfig.isAutoReplyInGroups();
                ColgramConfig.setAutoReplyInGroups(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
            } else if (position == textRow) {
                showTextEditor();
            } else if (position == cooldownRow) {
                showCooldownPicker();
            }
        });

        return fragmentView;
    }

    private void showTextEditor() {
        Context context = getContext();
        if (context == null) return;
        final EditText input = new EditText(context);
        input.setText(ColgramConfig.getAutoReplyText());
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        new AlertDialog.Builder(context)
                .setTitle("Текст автоответа")
                .setView(wrap)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        ColgramConfig.setAutoReplyText(text);
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showCooldownPicker() {
        Context context = getContext();
        if (context == null) return;
        final int[] options = {0, 60, 300, 3600};
        String[] labels = {
                "Отвечать на каждое сообщение",
                "Раз в минуту на один чат",
                "Раз в 5 минут на один чат",
                "Раз в час на один чат",
        };
        new AlertDialog.Builder(context)
                .setTitle("Как часто отвечать")
                .setItems(labels, (dialog, which) -> {
                    ColgramConfig.setAutoReplyCooldownSec(options[which]);
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == enabledRow || position == groupsRow
                    || position == textRow || position == cooldownRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(mContext);
                    break;
                case 2:
                    view = new TextSettingsCell(mContext);
                    break;
                case 3:
                    view = new ShadowSectionCell(mContext);
                    break;
                default:
                    view = new TextCheckCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    headerCell.setText("Автоответчик");
                    break;
                }
                case 2: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    if (position == textRow) {
                        textCell.setTextAndValue("Текст ответа", ColgramConfig.getAutoReplyText(), true);
                    } else if (position == cooldownRow) {
                        int sec = ColgramConfig.getAutoReplyCooldownSec();
                        String value;
                        if (sec <= 0) {
                            value = "на каждое сообщение";
                        } else if (sec < 60) {
                            value = "раз в " + sec + " сек";
                        } else if (sec < 3600) {
                            value = "раз в " + (sec / 60) + " мин";
                        } else {
                            value = "раз в час";
                        }
                        textCell.setTextAndValue("Частота ответа", value, false);
                    }
                    break;
                }
                case 3:
                    break;
                default: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == enabledRow) {
                        checkCell.setTextAndCheck("Включить автоответчик", ColgramConfig.isAutoReplyEnabled(), true);
                    } else if (position == groupsRow) {
                        checkCell.setTextAndCheck("Отвечать также в группах", ColgramConfig.isAutoReplyInGroups(), false);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return 0;
            } else if (position == textRow || position == cooldownRow) {
                return 2;
            } else if (position == sectionRow) {
                return 3;
            }
            return 1;
        }
    }
}
