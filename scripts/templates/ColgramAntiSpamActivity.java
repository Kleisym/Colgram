package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** Controls the anti-spam detector built into the signed-in Telegram client. */
public class ColgramAntiSpamActivity extends BaseFragment {
    private static final int HEADER_ROW = 0;
    private static final int DETECTOR_ROW = 1;
    private static final int INFO_ROW = 2;
    private static final int COMMANDS_ROW = 3;
    private static final int SHADOW_ROW = 4;
    private static final int ROW_COUNT = 5;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Антиспам");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frame = (FrameLayout) fragmentView;
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position == DETECTOR_ROW) {
                ColgramConfig.setSpamGuardEnabled(!ColgramConfig.isSpamGuardEnabled());
                listAdapter.notifyItemChanged(DETECTOR_ROW);
            } else if (position == COMMANDS_ROW) {
                new AlertDialog.Builder(context)
                        .setTitle("Команды в чате")
                        .setMessage(".мут — заглушить уведомления собеседника; в группе ответьте на его сообщение.\n"
                                + ".unmute — вернуть уведомления.\n\n"
                                + "Детектор рекламы проверяет входящие сообщения в текущем аккаунте автоматически.")
                        .setPositiveButton("Понятно", null)
                        .show();
            }
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getAdapterPosition() == DETECTOR_ROW
                    || holder.getAdapterPosition() == COMMANDS_ROW;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == HEADER_ROW) return 0;
            if (position == INFO_ROW) return 4;
            if (position == SHADOW_ROW) return 2;
            return 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == 2) {
                view = new ShadowSectionCell(context);
            } else if (viewType == 4) {
                view = new TextInfoPrivacyCell(context);
                view.setBackgroundDrawable(Theme.getThemedDrawable(context, R.drawable.greydivider,
                        Theme.key_windowBackgroundGrayShadow));
            } else {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position == HEADER_ROW) {
                ((HeaderCell) holder.itemView).setText("Встроенная защита");
            } else if (position == DETECTOR_ROW) {
                ((TextSettingsCell) holder.itemView).setTextAndValue("Детектор спама",
                        ColgramConfig.isSpamGuardEnabled() ? "включён" : "выключен", true);
            } else if (position == INFO_ROW) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        "Работает в уже открытом аккаунте Colgram. Дополнительный вход, api_id, "
                                + "api_hash, номер телефона и отдельный юзербот не нужны. "
                                + "Подозрительные сообщения от незнакомцев оцениваются по нескольким признакам: "
                                + "рекламные фразы, ссылки, частота и оформление текста.");
            } else if (position == COMMANDS_ROW) {
                ((TextSettingsCell) holder.itemView).setTextAndValue("Команды в чате",
                        ".мут / .unmute", false);
            }
        }
    }
}
