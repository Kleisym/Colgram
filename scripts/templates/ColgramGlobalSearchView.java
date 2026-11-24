package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * True Telegram Global Search & Top-Rankings Explorer.
 * Uses TLRPC.TL_contacts_search (limit=50) to preserve Telegram's exact global ranking
 * order (#1, #2, #3...) from res.results, paired with verified badges, subscriber counts,
 * category filters (Все · Каналы · Боты · Группы · Люди · Посты), and public post search.
 */
public class ColgramGlobalSearchView extends FrameLayout {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_CHANNELS = 1;
    private static final int FILTER_BOTS = 2;
    private static final int FILTER_GROUPS = 3;
    private static final int FILTER_PEOPLE = 4;
    private static final int FILTER_POSTS = 5;

    private static final int VIEW_TYPE_FILTERS = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_PEER = 2;
    private static final int VIEW_TYPE_MESSAGE = 3;
    private static final int VIEW_TYPE_EMPTY = 4;

    private static final class GlobalEntry {
        final int rank;
        final long dialogId;
        final TLRPC.Chat chat;
        final TLRPC.User user;
        final boolean isMine;

        GlobalEntry(int rank, long dialogId, TLRPC.Chat chat, TLRPC.User user, boolean isMine) {
            this.rank = rank;
            this.dialogId = dialogId;
            this.chat = chat;
            this.user = user;
            this.isMine = isMine;
        }
    }

    private static final class RowItem {
        final int viewType;
        final String headerTitle;
        final GlobalEntry entry;
        final TLRPC.Message message;
        final int postRank;

        RowItem(int viewType, String headerTitle, GlobalEntry entry, TLRPC.Message message, int postRank) {
            this.viewType = viewType;
            this.headerTitle = headerTitle;
            this.entry = entry;
            this.message = message;
            this.postRank = postRank;
        }
    }

    private final BaseFragment parentFragment;
    private final int currentAccount;
    public final RecyclerListView listView;
    private final GlobalAdapter adapter;
    private final ProgressBar loadingSpinner;

    private String lastQuery = "";
    private int activeFilter = FILTER_ALL;
    private int pendingRequests = 0;
    private int contactsReqId = 0;
    private int publicPostsReqId = 0;
    private Runnable pendingSearchRunnable;
    private long lastOpenTapMs;

    private final ArrayList<GlobalEntry> globalResults = new ArrayList<>();
    private final ArrayList<TLRPC.Message> foundPublicPosts = new ArrayList<>();
    private final ArrayList<RowItem> rows = new ArrayList<>();

    private boolean ignoreRequestLayout;

    /** Standalone fragment hosting ColgramGlobalSearchView with an integrated search box (for Bottom Bar launch). */
    public static BaseFragment createStandaloneFragment() {
        return new ColgramGlobalSearchActivity();
    }

    public ColgramGlobalSearchView(Context context, BaseFragment fragment) {
        super(context);
        this.parentFragment = fragment;
        this.currentAccount = fragment != null ? fragment.getCurrentAccount() : UserConfig.selectedAccount;

        listView = new RecyclerListView(context);
        listView.setClipToPadding(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new GlobalAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            RowItem item = rows.get(position);
            if (item.viewType == VIEW_TYPE_PEER && item.entry != null) {
                openPeer(item.entry.chat, item.entry.user);
            } else if (item.viewType == VIEW_TYPE_MESSAGE && item.message != null) {
                openMessage(item.message);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return false;
            RowItem item = rows.get(position);
            if (item.viewType == VIEW_TYPE_PEER && item.entry != null) {
                copyRankedPeer(item.entry);
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
            return false;
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadingSpinner = new ProgressBar(context);
        loadingSpinner.setVisibility(GONE);
        addView(loadingSpinner, LayoutHelper.createFrame(32, 32, Gravity.CENTER));

        rebuildRows();
    }

    public void setPagesPaddings(int top, int bottom, boolean doNotRequestLayout) {
        setClipToPadding(false);
        ignoreRequestLayout = doNotRequestLayout;
        setPadding(0, top, 0, bottom);
        listView.setPadding(0, top, 0, bottom, doNotRequestLayout);
        MarginLayoutParams lp = (MarginLayoutParams) listView.getLayoutParams();
        lp.topMargin = -top;
        lp.bottomMargin = -bottom;
        ignoreRequestLayout = false;
    }

    @Override
    public void requestLayout() {
        if (ignoreRequestLayout) return;
        super.requestLayout();
    }

    public void search(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (pendingSearchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        if (q.equals(lastQuery) && (!rows.isEmpty() || q.isEmpty())) {
            return;
        }
        cancelActiveRequests();
        lastQuery = q;
        globalResults.clear();
        foundPublicPosts.clear();

        if (q.length() < 2) {
            pendingRequests = 0;
            loadingSpinner.setVisibility(GONE);
            rebuildRows();
            return;
        }

        loadingSpinner.setVisibility(VISIBLE);
        rebuildRows();

        final String targetQuery = q;
        pendingSearchRunnable = () -> executeGlobalSearch(targetQuery);
        AndroidUtilities.runOnUIThread(pendingSearchRunnable, 300L);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pendingSearchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        cancelActiveRequests();
    }

    private void cancelActiveRequests() {
        ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);
        if (contactsReqId != 0) {
            cm.cancelRequest(contactsReqId, true);
            contactsReqId = 0;
        }
        if (publicPostsReqId != 0) {
            cm.cancelRequest(publicPostsReqId, true);
            publicPostsReqId = 0;
        }
    }

    private void executeGlobalSearch(final String query) {
        if (!TextUtils.equals(query, lastQuery)) return;
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        final ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);
        final TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();

        if (self != null && self.bot) {
            pendingRequests = 0;
            loadingSpinner.setVisibility(GONE);
            rebuildRows();
            return;
        }

        pendingRequests = 2;

        // 1. Official Telegram Global Search Rankings (TL_contacts_search limit=50)
        // Strictly preserve res.results ordering (#1, #2, #3...) as Telegram's live global ranking!
        TLRPC.TL_contacts_search contactsReq = new TLRPC.TL_contacts_search();
        String rpcQuery = query.startsWith("@") && query.length() > 1 ? query.substring(1) : query;
        contactsReq.q = rpcQuery;
        contactsReq.limit = 50;
        contactsReqId = cm.sendRequest(contactsReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            contactsReqId = 0;
            if (!TextUtils.equals(query, lastQuery)) return;
            if (response instanceof TLRPC.TL_contacts_found) {
                TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);

                HashMap<Long, TLRPC.Chat> chatById = new HashMap<>();
                if (res.chats != null) {
                    for (TLRPC.Chat c : res.chats) {
                        if (c != null) chatById.put(c.id, c);
                    }
                }
                HashMap<Long, TLRPC.User> userById = new HashMap<>();
                if (res.users != null) {
                    for (TLRPC.User u : res.users) {
                        if (u != null) userById.put(u.id, u);
                    }
                }

                HashSet<Long> myPeerIds = new HashSet<>();
                if (res.my_results != null) {
                    for (TLRPC.Peer p : res.my_results) {
                        if (p != null) {
                            myPeerIds.add(DialogObject.getPeerDialogId(p));
                        }
                    }
                }

                globalResults.clear();
                HashSet<Long> seenDialogIds = new HashSet<>();
                int rank = 0;
                if (res.results != null) {
                    for (TLRPC.Peer peer : res.results) {
                        if (peer == null) continue;
                        long did = DialogObject.getPeerDialogId(peer);
                        if (did == 0 || !seenDialogIds.add(did)) continue;
                        TLRPC.Chat c = did < 0 ? chatById.get(-did) : null;
                        TLRPC.User u = did > 0 ? userById.get(did) : null;
                        if (c == null && u == null) continue;
                        rank++;
                        globalResults.add(new GlobalEntry(rank, did, c, u, myPeerIds.contains(did)));
                    }
                }
            }
            onOneRequestDone();
        }));

        // 2. Worldwide public channel posts (TL_channels_searchPosts)
        TLRPC.TL_channels_searchPosts postsReq = new TLRPC.TL_channels_searchPosts();
        if (query.startsWith("#") && query.length() > 1) {
            postsReq.flags |= 1;
            postsReq.hashtag = query.substring(1);
        } else {
            postsReq.flags |= 2;
            postsReq.query = query;
        }
        postsReq.limit = 25;
        postsReq.offset_peer = new TLRPC.TL_inputPeerEmpty();
        publicPostsReqId = cm.sendRequest(postsReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            publicPostsReqId = 0;
            if (!TextUtils.equals(query, lastQuery)) return;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                foundPublicPosts.clear();
                if (res.messages != null) {
                    foundPublicPosts.addAll(res.messages);
                }
            }
            onOneRequestDone();
        }));
    }

    private void onOneRequestDone() {
        pendingRequests = Math.max(0, pendingRequests - 1);
        if (pendingRequests == 0) {
            loadingSpinner.setVisibility(GONE);
        }
        rebuildRows();
    }

    private static boolean matchesPeerFilter(GlobalEntry entry, int filter) {
        if (entry == null) return false;
        if (filter == FILTER_ALL) return true;
        if (filter == FILTER_CHANNELS) {
            return entry.chat != null && ChatObject.isChannelAndNotMegaGroup(entry.chat);
        }
        if (filter == FILTER_GROUPS) {
            return entry.chat != null && !ChatObject.isChannelAndNotMegaGroup(entry.chat);
        }
        if (filter == FILTER_BOTS) {
            return entry.user != null && entry.user.bot;
        }
        if (filter == FILTER_PEOPLE) {
            return entry.user != null && !entry.user.bot;
        }
        return false;
    }

    private void rebuildRows() {
        rows.clear();
        rows.add(new RowItem(VIEW_TYPE_FILTERS, null, null, null, 0));

        if (lastQuery.length() < 2) {
            rows.add(new RowItem(VIEW_TYPE_EMPTY,
                    "Настоящий глобальный поиск по всему Telegram.\nВведите от 2 символов, чтобы увидеть глобальный топ выдачи (#1, #2, #3…): каналы, боты, группы, люди и публичные посты.",
                    null, null, 0));
            adapter.notifyDataSetChanged();
            return;
        }

        final TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (self != null && self.bot) {
            rows.add(new RowItem(VIEW_TYPE_EMPTY,
                    "Аккаунты ботов не поддерживают глобальный рейтинг Telegram (TL_contacts_search).",
                    null, null, 0));
            adapter.notifyDataSetChanged();
            return;
        }

        if (activeFilter != FILTER_POSTS) {
            ArrayList<GlobalEntry> filtered = new ArrayList<>();
            for (GlobalEntry e : globalResults) {
                if (matchesPeerFilter(e, activeFilter)) {
                    filtered.add(e);
                }
            }
            if (!filtered.isEmpty()) {
                rows.add(new RowItem(VIEW_TYPE_HEADER,
                        "Найдено в глобальном поиске: " + filtered.size(),
                        null, null, 0));
                for (GlobalEntry e : filtered) {
                    rows.add(new RowItem(VIEW_TYPE_PEER, null, e, null, 0));
                }
            }
        }

        if ((activeFilter == FILTER_POSTS || (activeFilter == FILTER_ALL && globalResults.isEmpty()))
                && !foundPublicPosts.isEmpty()) {
            rows.add(new RowItem(VIEW_TYPE_HEADER,
                    "Публичные посты каналов (" + foundPublicPosts.size() + ")",
                    null, null, 0));
            for (int i = 0; i < foundPublicPosts.size(); i++) {
                rows.add(new RowItem(VIEW_TYPE_MESSAGE, null, null, foundPublicPosts.get(i), i + 1));
            }
        }

        if (rows.size() == 1 && pendingRequests == 0) {
            rows.add(new RowItem(VIEW_TYPE_EMPTY,
                    "В глобальном поиске Telegram ничего не найдено по запросу «" + lastQuery + "»",
                    null, null, 0));
        }
        adapter.notifyDataSetChanged();
    }

    private void copyRankedPeer(GlobalEntry entry) {
        Context ctx = getContext();
        if (ctx == null) return;
        String username = entry.chat != null
                ? ChatObject.getPublicUsername(entry.chat)
                : (entry.user != null ? UserObject.getPublicUsername(entry.user) : null);
        String fallbackName = entry.chat != null
                ? (entry.chat.title != null ? entry.chat.title : "Чат")
                : (entry.user != null ? UserObject.getUserName(entry.user) : "Пользователь");
        String handle = !TextUtils.isEmpty(username) ? ("@" + username) : fallbackName;
        String payload = "#" + entry.rank + " в глобальном поиске Telegram по «" + lastQuery + "»: " + handle;
        try {
            Context appCtx = ctx.getApplicationContext();
            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Colgram Global Search", payload));
            }
            Toast.makeText(appCtx, "Скопировано: " + payload, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private void openPeer(TLRPC.Chat chat, TLRPC.User user) {
        if (parentFragment == null || parentFragment.isFinishing() || parentFragment.getParentLayout() == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastOpenTapMs < 380L) return;
        Bundle args = new Bundle();
        if (chat != null) {
            args.putLong("chat_id", chat.id);
        } else if (user != null) {
            args.putLong("user_id", user.id);
        } else {
            return;
        }
        lastOpenTapMs = now;
        parentFragment.presentFragment(new ChatActivity(args));
    }

    private void openMessage(TLRPC.Message msg) {
        if (parentFragment == null || parentFragment.isFinishing() || parentFragment.getParentLayout() == null || msg == null) return;
        long dialogId = MessageObject.getDialogId(msg);
        if (dialogId == 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastOpenTapMs < 380L) return;
        lastOpenTapMs = now;
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", msg.id);
        parentFragment.presentFragment(new ChatActivity(args));
    }

    private final class GlobalAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        GlobalAdapter(Context ctx) {
            this.context = ctx;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int t = holder.getItemViewType();
            return t == VIEW_TYPE_PEER || t == VIEW_TYPE_MESSAGE;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).viewType;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v;
            if (viewType == VIEW_TYPE_FILTERS) {
                v = new FilterStripCell(context);
            } else if (viewType == VIEW_TYPE_HEADER) {
                HeaderCell hc = new HeaderCell(context);
                hc.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                v = hc;
            } else if (viewType == VIEW_TYPE_PEER) {
                v = new PeerResultCell(context);
            } else if (viewType == VIEW_TYPE_MESSAGE) {
                v = new MessageResultCell(context);
            } else {
                TextView tv = new TextView(context);
                tv.setPadding(dp(24), dp(42), dp(24), dp(24));
                tv.setGravity(Gravity.CENTER);
                tv.setLineSpacing(dp(3), 1f);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
                tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                v = tv;
            }
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            RowItem item = rows.get(position);
            switch (item.viewType) {
                case VIEW_TYPE_FILTERS:
                    ((FilterStripCell) holder.itemView).bind();
                    break;
                case VIEW_TYPE_HEADER:
                    HeaderCell hc = (HeaderCell) holder.itemView;
                    hc.setText(item.headerTitle);
                    ColgramMenuChrome.styleHeader(hc, ColgramMenuChrome.COLOR_CRIMSON, 0xFFFF5566);
                    break;
                case VIEW_TYPE_PEER:
                    ((PeerResultCell) holder.itemView).bind(item.entry);
                    break;
                case VIEW_TYPE_MESSAGE:
                    ((MessageResultCell) holder.itemView).bind(item.message, item.postRank);
                    break;
                case VIEW_TYPE_EMPTY:
                    TextView tv = (TextView) holder.itemView;
                    tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    tv.setText(item.headerTitle);
                    break;
            }
        }
    }

    private final class FilterStripCell extends HorizontalScrollView {
        private final LinearLayout row;
        private final String[] labels = {"Все", "Каналы", "Боты", "Группы", "Люди", "Посты"};
        private final TextView[] pills = new TextView[labels.length];
        private final GradientDrawable[] pillBgs = new GradientDrawable[labels.length];

        FilterStripCell(Context ctx) {
            super(ctx);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            setPadding(dp(12), dp(8), dp(12), dp(8));
            setClipToPadding(false);
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView pill = new TextView(ctx);
                pill.setText(labels[i]);
                pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
                pill.setTypeface(AndroidUtilities.bold());
                pill.setPadding(dp(12), dp(6), dp(12), dp(6));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16));
                pill.setBackground(bg);
                pill.setOnClickListener(v -> {
                    if (activeFilter != idx) {
                        activeFilter = idx;
                        rebuildRows();
                    }
                });
                pills[i] = pill;
                pillBgs[i] = bg;
                row.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 7, 0));
            }
        }

        void bind() {
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            for (int i = 0; i < pills.length; i++) {
                boolean selected = activeFilter == i;
                TextView pill = pills[i];
                GradientDrawable bg = pillBgs[i];
                if (selected) {
                    bg.setColor(pal.accentPrimary);
                    bg.setStroke(0, 0);
                    pill.setTextColor(Color.WHITE);
                } else {
                    bg.setColor(pal.chipSurface);
                    bg.setStroke(Math.max(1, dp(0.8f)), pal.borderSubtle);
                    pill.setTextColor(pal.textPrimary);
                }
            }
        }
    }

    private final class PeerResultCell extends LinearLayout {
        private final TextView rankTv;
        private final GradientDrawable rankBg;
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView titleTv;
        private final ImageView verifiedIv;
        private final TextView mineBadgeTv;
        private final GradientDrawable mineBg;
        private final TextView subTv;

        PeerResultCell(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(8), dp(12), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            rankTv = new TextView(ctx);
            rankTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            rankTv.setTypeface(AndroidUtilities.bold());
            rankTv.setGravity(Gravity.CENTER);
            rankTv.setSingleLine(true);
            rankTv.setPadding(dp(6), dp(3), dp(6), dp(3));
            rankTv.setMinWidth(dp(32));
            rankBg = new GradientDrawable();
            rankBg.setCornerRadius(dp(9));
            rankTv.setBackground(rankBg);
            addView(rankTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 9, 0));

            avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(dp(22));
            addView(avatar, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 11, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            LinearLayout topRow = new LinearLayout(ctx);
            topRow.setOrientation(HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            titleTv.setTypeface(AndroidUtilities.bold());
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 4, 0));

            verifiedIv = new ImageView(ctx);
            try {
                android.graphics.drawable.Drawable bg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.verified_area);
                android.graphics.drawable.Drawable ck = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.verified_check);
                if (bg != null && ck != null) {
                    bg = bg.mutate();
                    ck = ck.mutate();
                    bg.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF3390EC, PorterDuff.Mode.MULTIPLY));
                    ck.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY));
                    org.telegram.ui.Components.CombinedDrawable cd = new org.telegram.ui.Components.CombinedDrawable(bg, ck);
                    verifiedIv.setImageDrawable(cd);
                } else {
                    verifiedIv.setImageResource(R.drawable.verified_profile);
                }
            } catch (Throwable ignored) {
                verifiedIv.setImageResource(R.drawable.verified_profile);
            }
            verifiedIv.setVisibility(GONE);
            topRow.addView(verifiedIv, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 5, 0));

            mineBadgeTv = new TextView(ctx);
            mineBadgeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f);
            mineBadgeTv.setTypeface(AndroidUtilities.bold());
            mineBadgeTv.setSingleLine(true);
            mineBadgeTv.setPadding(dp(6), dp(1.5f), dp(6), dp(1.5f));
            mineBg = new GradientDrawable();
            mineBg.setCornerRadius(dp(999));
            mineBadgeTv.setBackground(mineBg);
            mineBadgeTv.setVisibility(GONE);
            topRow.addView(mineBadgeTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            subTv = new TextView(ctx);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subTv.setMaxLines(1);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(GlobalEntry entry) {
            if (entry == null) return;
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

            applyRankStyle(rankTv, rankBg, entry.rank, pal);

            TLRPC.Chat chat = entry.chat;
            TLRPC.User user = entry.user;

            boolean verified = (chat != null && chat.verified) || (user != null && user.verified);
            verifiedIv.setVisibility(verified ? VISIBLE : GONE);

            if (entry.isMine) {
                mineBadgeTv.setVisibility(VISIBLE);
                mineBadgeTv.setText(chat != null ? "Вы подписаны" : "Ваш");
                mineBadgeTv.setTextColor(pal.dark ? 0xFF34D399 : 0xFF047857);
                mineBg.setColor(pal.dark ? 0x2610B981 : 0x1A10B981);
            } else {
                mineBadgeTv.setVisibility(GONE);
            }

            if (chat != null) {
                avatarDrawable.setInfo(currentAccount, chat);
                avatar.setForUserOrChat(chat, avatarDrawable);
                titleTv.setText(highlightQuery(chat.title != null ? chat.title : "Чат"));

                boolean isChannel = ChatObject.isChannelAndNotMegaGroup(chat);
                StringBuilder sub = new StringBuilder();
                String uname = ChatObject.getPublicUsername(chat);
                if (!TextUtils.isEmpty(uname)) sub.append("@").append(uname);
                if (chat.participants_count > 0) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(formatCompactCount(chat.participants_count))
                            .append(isChannel ? " подписчиков" : " участников");
                }
                if (sub.length() > 0) sub.append(" · ");
                sub.append(isChannel ? "Канал" : "Группа");
                subTv.setText(sub.toString());
            } else if (user != null) {
                avatarDrawable.setInfo(currentAccount, user);
                avatar.setForUserOrChat(user, avatarDrawable);
                titleTv.setText(highlightQuery(UserObject.getUserName(user)));

                StringBuilder sub = new StringBuilder();
                String uname = UserObject.getPublicUsername(user);
                if (!TextUtils.isEmpty(uname)) sub.append("@").append(uname);
                if (sub.length() > 0) sub.append(" · ");
                sub.append(user.bot ? "Бот" : "Пользователь");
                subTv.setText(sub.toString());
            }
        }
    }

    private static void applyRankStyle(TextView tv, GradientDrawable bg, int rank, ColgramAeroStyler.Palette pal) {
        tv.setText("#" + rank);
        if (rank == 1) {
            bg.setColor(0xFFF59E0B);
            bg.setStroke(Math.max(1, dp(0.8f)), 0xFFFDE68A);
            tv.setTextColor(0xFF1E1300);
        } else if (rank == 2) {
            bg.setColor(0xFFCBD5E1);
            bg.setStroke(Math.max(1, dp(0.8f)), 0xFFF8FAFC);
            tv.setTextColor(0xFF0F172A);
        } else if (rank == 3) {
            bg.setColor(0xFFCD7F32);
            bg.setStroke(Math.max(1, dp(0.8f)), 0xFFFDBA74);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(pal.chipSurface);
            bg.setStroke(Math.max(1, dp(0.8f)), pal.borderSubtle);
            tv.setTextColor(pal.textSecondary);
        }
    }

    private static String formatCompactCount(int count) {
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    private final class MessageResultCell extends LinearLayout {
        private final TextView rankTv;
        private final GradientDrawable rankBg;
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView titleTv;
        private final TextView bodyTv;
        private final TextView dateTv;

        MessageResultCell(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(8), dp(12), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            rankTv = new TextView(ctx);
            rankTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
            rankTv.setTypeface(AndroidUtilities.bold());
            rankTv.setGravity(Gravity.CENTER);
            rankTv.setSingleLine(true);
            rankTv.setPadding(dp(6), dp(3), dp(6), dp(3));
            rankTv.setMinWidth(dp(32));
            rankBg = new GradientDrawable();
            rankBg.setCornerRadius(dp(9));
            rankTv.setBackground(rankBg);
            addView(rankTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 9, 0));

            avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(dp(22));
            addView(avatar, LayoutHelper.createLinear(44, 44, Gravity.TOP, 0, 2, 11, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            titleTv.setTypeface(AndroidUtilities.bold());
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            dateTv = new TextView(ctx);
            dateTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            dateTv.setMaxLines(1);
            top.addView(dateTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            bodyTv = new TextView(ctx);
            bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            bodyTv.setMaxLines(2);
            bodyTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(bodyTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(TLRPC.Message msg, int postRank) {
            if (msg == null) return;
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            applyRankStyle(rankTv, rankBg, Math.max(1, postRank), pal);

            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bodyTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dateTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));

            MessagesController mc = MessagesController.getInstance(currentAccount);
            long did = MessageObject.getDialogId(msg);
            String peerName = "Публичный пост";
            if (DialogObject.isUserDialog(did)) {
                TLRPC.User u = mc.getUser(did);
                if (u != null) {
                    avatarDrawable.setInfo(currentAccount, u);
                    avatar.setForUserOrChat(u, avatarDrawable);
                    peerName = UserObject.getUserName(u);
                } else {
                    avatar.setImageDrawable(null);
                }
            } else {
                TLRPC.Chat c = mc.getChat(-did);
                if (c != null) {
                    avatarDrawable.setInfo(currentAccount, c);
                    avatar.setForUserOrChat(c, avatarDrawable);
                    peerName = c.title != null ? c.title : peerName;
                } else {
                    avatar.setImageDrawable(null);
                }
            }
            titleTv.setText(peerName);
            dateTv.setText(LocaleController.stringForMessageListDate(msg.date));
            String text = !TextUtils.isEmpty(msg.message) ? msg.message.replace('\n', ' ') : "[Медиа]";
            bodyTv.setText(highlightQuery(text));
        }
    }

    private CharSequence highlightQuery(String text) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(lastQuery)) return text == null ? "" : text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        String qClean = lastQuery.startsWith("@") && lastQuery.length() > 1 ? lastQuery.substring(1) : lastQuery;
        String lowText = text.toLowerCase(Locale.ROOT);
        String lowQ = qClean.toLowerCase(Locale.ROOT);
        int idx = lowText.indexOf(lowQ);
        if (idx >= 0) {
            int end = Math.min(text.length(), idx + lowQ.length());
            sb.setSpan(new ForegroundColorSpan(ColgramMenuChrome.COLOR_CRIMSON), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    /** Standalone screen launching directly into Telegram Global Top-Rankings Search. */
    private static final class ColgramGlobalSearchActivity extends BaseFragment {
        private ColgramGlobalSearchView searchView;
        private EditText searchInput;

        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle("Глобальный поиск Telegram");
            ColgramMenuChrome.styleActionBar(actionBar);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        AndroidUtilities.hideKeyboard(searchInput);
                        finishFragment();
                    }
                }
            });

            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
            fragmentView = root;

            LinearLayout bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(12), dp(4), dp(10), dp(4));
            GradientDrawable barBg = new GradientDrawable();
            barBg.setCornerRadius(dp(14));
            barBg.setColor(pal.chipSurface);
            barBg.setStroke(Math.max(1, dp(0.9f)), pal.borderSubtle);
            bar.setBackground(barBg);

            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.msg_search);
            icon.setColorFilter(pal.textSecondary, PorterDuff.Mode.SRC_IN);
            bar.addView(icon, LayoutHelper.createLinear(18, 18, 0, 0, 8, 0));

            searchInput = new EditText(context);
            searchInput.setBackground(null);
            searchInput.setHint("Канал, бот, @username или тема…");
            searchInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
            searchInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
            searchInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            searchInput.setSingleLine(true);
            searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            bar.addView(searchInput, LayoutHelper.createLinear(0, 38, 1f));

            final TextView clearBtn = new TextView(context);
            clearBtn.setText("✕");
            clearBtn.setGravity(Gravity.CENTER);
            clearBtn.setTextColor(pal.textSecondary);
            clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
            clearBtn.setVisibility(GONE);
            clearBtn.setOnClickListener(v -> {
                searchInput.setText("");
                searchInput.requestFocus();
                AndroidUtilities.showKeyboard(searchInput);
            });
            bar.addView(clearBtn, LayoutHelper.createLinear(30, 30));

            FrameLayout barHolder = new FrameLayout(context);
            barHolder.setPadding(dp(12), dp(10), dp(12), dp(4));
            barHolder.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 46));
            root.addView(barHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            searchView = new ColgramGlobalSearchView(context, this);
            searchView.listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        AndroidUtilities.hideKeyboard(searchInput);
                    }
                }
            });
            root.addView(searchView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    String txt = s == null ? "" : s.toString();
                    clearBtn.setVisibility(txt.isEmpty() ? GONE : VISIBLE);
                    if (searchView != null) {
                        searchView.search(txt);
                    }
                }
            });
            searchInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    AndroidUtilities.hideKeyboard(searchInput);
                    return true;
                }
                return false;
            });

            return fragmentView;
        }

        @Override
        public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
            super.onTransitionAnimationEnd(isOpen, backward);
            if (isOpen && !backward && searchInput != null && !isFinishing()) {
                searchInput.requestFocus();
                AndroidUtilities.showKeyboard(searchInput);
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            if (searchInput != null) {
                AndroidUtilities.hideKeyboard(searchInput);
            }
        }
    }
}
