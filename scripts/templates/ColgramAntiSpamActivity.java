package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramAntiSpam;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
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

/**
 * Anti-spam userbot control screen.
 *
 * The userbot is a Telethon program running in the bundled Python interpreter, not a plugin,
 * so it needs its own screen: it has credentials, a login handshake, and a live status.
 *
 * TWO THINGS THE UI MUST MAKE CLEAR
 * ---------------------------------
 * 1. This needs its OWN api_id / api_hash from my.telegram.org and its OWN phone number. It
 *    is a separate Telegram session, not a feature of the account you are logged into in the
 *    app. Users routinely assume otherwise, then wonder why nothing happens.
 * 2. The login code arrives in the Telegram app for THAT phone number — the userbot's own
 *    account — not in Colgram. So the code has to be typed back in here.
 *
 * Both are spelled out in the info rows rather than left to be discovered.
 *
 * Theming uses Telegram Theme tokens throughout. Do not introduce a hardcoded palette here:
 * a dark-only screen is invisible in light theme, which is exactly the bug class that made
 * the login form black-on-black earlier.
 */
public class ColgramAntiSpamActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int infoHeaderRow;
    private int infoRow;
    private int credsHeaderRow;
    private int apiIdRow;
    private int apiHashRow;
    private int phoneRow;
    private int passwordRow;
    private int credsShadowRow;
    private int controlHeaderRow;
    private int toggleRow;
    private int statusRow;
    private int submitCodeRow;
    private int submitPasswordRow;
    private int controlShadowRow;
    private int noteHeaderRow;
    private int noteRow;

    /** Latest status string, refreshed while the screen is open. */
    private String statusText = "Неизвестно";

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            if (listView != null) {
                AndroidUtilities.runOnUIThread(this, 3000);
            }
        }
    };

    private int account() {
        return UserConfig.selectedAccount;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Анти-спам юзербот");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        buildRows();

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onRowClick(position));

        return fragmentView;
    }

    private void buildRows() {
        rowCount = 0;
        infoHeaderRow = rowCount++;
        infoRow = rowCount++;
        credsHeaderRow = rowCount++;
        apiIdRow = rowCount++;
        apiHashRow = rowCount++;
        phoneRow = rowCount++;
        passwordRow = rowCount++;
        credsShadowRow = rowCount++;
        controlHeaderRow = rowCount++;
        toggleRow = rowCount++;
        statusRow = rowCount++;
        submitCodeRow = rowCount++;
        submitPasswordRow = rowCount++;
        controlShadowRow = rowCount++;
        noteHeaderRow = rowCount++;
        noteRow = rowCount++;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
        // The userbot logs in on its own thread, so the status changes without any user
        // action here. Poll while the screen is visible rather than requiring a manual
        // refresh — the login handshake can sit waiting for a code for minutes.
        AndroidUtilities.runOnUIThread(statusPoller, 1500);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(statusPoller);
    }

    private void refreshStatus() {
        String raw = ColgramAntiSpam.getStatus();
        if (raw == null) {
            statusText = "Python-движок недоступен";
        } else {
            // Adapter prints: phase|running|text|username
            String[] parts = raw.split("\\|", -1);
            if (parts.length >= 3) {
                String phase = parts[0];
                boolean running = "True".equalsIgnoreCase(parts[1]);
                String text = parts[2];
                String user = parts.length > 3 ? parts[3] : "";
                StringBuilder sb = new StringBuilder();
                sb.append(running ? "🟢 " : "⚪ ");
                if (!text.isEmpty()) sb.append(text);
                else sb.append(running ? "Работает" : "Остановлен");
                if (!user.isEmpty()) sb.append("  ").append(user);
                if ("error".equals(phase) && text.isEmpty()) sb.append("Ошибка");
                statusText = sb.toString();
            } else {
                statusText = raw.trim();
            }
        }
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(int position) {
        Context context = getParentActivity();
        if (context == null) return;
        int acc = account();

        if (position == apiIdRow) {
            editField("api_id", "Только цифры, из my.telegram.org",
                    ColgramAntiSpam.getApiId(context, acc), false, value ->
                            ColgramAntiSpam.saveCredentials(context, acc, value,
                                    ColgramAntiSpam.getApiHash(context, acc),
                                    ColgramAntiSpam.getPhone(context, acc),
                                    ColgramAntiSpam.getPassword(context, acc)));
        } else if (position == apiHashRow) {
            editField("api_hash", "32 символа, из my.telegram.org",
                    ColgramAntiSpam.getApiHash(context, acc), false, value ->
                            ColgramAntiSpam.saveCredentials(context, acc,
                                    ColgramAntiSpam.getApiId(context, acc), value,
                                    ColgramAntiSpam.getPhone(context, acc),
                                    ColgramAntiSpam.getPassword(context, acc)));
        } else if (position == phoneRow) {
            editField("Телефон аккаунта-юзербота", "В формате +79991234567",
                    ColgramAntiSpam.getPhone(context, acc), false, value ->
                            ColgramAntiSpam.saveCredentials(context, acc,
                                    ColgramAntiSpam.getApiId(context, acc),
                                    ColgramAntiSpam.getApiHash(context, acc), value,
                                    ColgramAntiSpam.getPassword(context, acc)));
        } else if (position == passwordRow) {
            editField("Пароль 2FA (если включён)", "Оставьте пустым, если 2FA нет",
                    ColgramAntiSpam.getPassword(context, acc), true, value ->
                            ColgramAntiSpam.saveCredentials(context, acc,
                                    ColgramAntiSpam.getApiId(context, acc),
                                    ColgramAntiSpam.getApiHash(context, acc),
                                    ColgramAntiSpam.getPhone(context, acc), value));
        } else if (position == toggleRow) {
            boolean enabled = ColgramAntiSpam.isEnabled(context, acc);
            if (enabled) {
                String err = ColgramAntiSpam.stop(context, acc);
                Toast.makeText(context, err == null ? "Юзербот остановлен" : err, Toast.LENGTH_SHORT).show();
            } else {
                String err = ColgramAntiSpam.start(context, acc);
                Toast.makeText(context, err == null
                        ? "Запуск... код придёт в Telegram для аккаунта-юзербота"
                        : err, Toast.LENGTH_LONG).show();
            }
            listAdapter.notifyDataSetChanged();
            refreshStatus();
        } else if (position == submitCodeRow) {
            editField("Код из Telegram", "Цифры из сообщения от Telegram",
                    "", false, value -> {
                        ColgramAntiSpam.submitCredentials(value, null);
                        Toast.makeText(context, "Код отправлен", Toast.LENGTH_SHORT).show();
                        refreshStatus();
                    });
        } else if (position == submitPasswordRow) {
            editField("Пароль 2FA", "Облачный пароль аккаунта-юзербота",
                    "", true, value -> {
                        ColgramAntiSpam.submitCredentials(null, value);
                        Toast.makeText(context, "Пароль отправлен", Toast.LENGTH_SHORT).show();
                        refreshStatus();
                    });
        }
    }

    /** Small callback so each editable field does not need its own listener class. */
    private interface OnValue {
        void accept(String value);
    }

    /**
     * Prompt for a single value.
     *
     * Uses a plain EditText inside an AlertDialog rather than EditTextBoldCursor. That is the
     * pattern ColgramPluginsActivity already uses and it is the right one: Telegram's dialog
     * theme colours a standard EditText correctly, whereas EditTextBoldCursor is built for
     * SettingsActivity rows and expects its own hint/line plumbing. Getting that wrong is how
     * you end up with an unreadable field — the same failure that made the login form
     * black-on-black.
     */
    private void editField(String title, String hint, String current, boolean isPassword, OnValue onValue) {
        Context context = getParentActivity();
        if (context == null) return;

        final EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setText(current == null ? "" : current);
        editText.setSingleLine(true);
        editText.setInputType(isPassword
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setView(editText);
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            String value = editText.getText().toString().trim();
            onValue.accept(value);
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == 1 || type == 3;
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
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new ShadowSectionCell(mContext);
                    break;
                case 4:
                    view = new TextInfoPrivacyCell(mContext);
                    view.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case 1:
                default:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == infoHeaderRow) {
                        headerCell.setText("Что это такое");
                    } else if (position == credsHeaderRow) {
                        headerCell.setText("Данные аккаунта-юзербота");
                    } else if (position == controlHeaderRow) {
                        headerCell.setText("Управление");
                    } else if (position == noteHeaderRow) {
                        headerCell.setText("Важно");
                    }
                    break;
                }
                case 1: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    int acc = account();
                    Context ctx = getParentActivity();
                    if (position == apiIdRow) {
                        String v = ctx == null ? "" : ColgramAntiSpam.getApiId(ctx, acc);
                        cell.setTextAndValue("api_id", v.isEmpty() ? "не задан" : v, true);
                    } else if (position == apiHashRow) {
                        String v = ctx == null ? "" : ColgramAntiSpam.getApiHash(ctx, acc);
                        // Never render the full hash: it is a credential and this screen may
                        // be visible over a shoulder or in a screenshot.
                        cell.setTextAndValue("api_hash",
                                v.isEmpty() ? "не задан" : (v.length() > 6 ? v.substring(0, 6) + "…" : "задан"), true);
                    } else if (position == phoneRow) {
                        String v = ctx == null ? "" : ColgramAntiSpam.getPhone(ctx, acc);
                        cell.setTextAndValue("Телефон", v.isEmpty() ? "не задан" : v, true);
                    } else if (position == passwordRow) {
                        String v = ctx == null ? "" : ColgramAntiSpam.getPassword(ctx, acc);
                        cell.setTextAndValue("Пароль 2FA", v.isEmpty() ? "не задан" : "задан", true);
                    } else if (position == toggleRow) {
                        boolean enabled = ctx != null && ColgramAntiSpam.isEnabled(ctx, acc);
                        cell.setTextAndValue(enabled ? "⏹ Остановить юзербот" : "▶️ Запустить юзербот", "", true);
                        cell.setTextColor(enabled
                                ? Theme.getColor(Theme.key_text_RedBold)
                                : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    } else if (position == statusRow) {
                        cell.setTextAndValue("Статус", statusText, true);
                    } else if (position == submitCodeRow) {
                        cell.setTextAndValue("Ввести код из Telegram", "", true);
                    } else if (position == submitPasswordRow) {
                        cell.setTextAndValue("Ввести пароль 2FA", "", true);
                    }
                    break;
                }
                case 4: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == infoRow) {
                        cell.setText("Юзербот следит за входящими сообщениями и оценивает их по "
                                + "спам-эвристикам. Это отдельная сессия Telegram со своим api_id и "
                                + "своим номером — он не использует аккаунт, в который вы вошли в Colgram.");
                    } else if (position == noteRow) {
                        cell.setText("• api_id и api_hash берутся на my.telegram.org (API development tools).\n"
                                + "• Код входа приходит в Telegram ТОГО аккаунта, чей номер вы указали, "
                                + "а не в Colgram.\n"
                                + "• Сессия хранится в приватной папке приложения.\n"
                                + "• Работа идёт в фоне, пока приложение не выгружено системой.");
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infoHeaderRow || position == credsHeaderRow
                    || position == controlHeaderRow || position == noteHeaderRow) {
                return 0;
            }
            if (position == credsShadowRow || position == controlShadowRow) {
                return 2;
            }
            if (position == infoRow || position == noteRow) {
                return 4;
            }
            return 1;
        }
    }
}
