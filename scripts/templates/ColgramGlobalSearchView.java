package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Full-scope "Глобальный поиск" tab inside SearchViewPager.
 * Queries global Telegram channels/groups/users/bots (contacts.search), global messages across
 * all chats (messages.searchGlobal), and public channel posts (channels.searchPosts when free
 * quota allows) in parallel, presenting everything in one unified, filterable, tappable list.
 */
public class ColgramGlobalSearchView extends FrameLayout {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_CHANNELS = 1;
    private static final int FILTER_USERS = 2;
    private static final int FILTER_MESSAGES = 3;

    private static final int VIEW_TYPE_FILTERS = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_PEER = 2;
    private static final int VIEW_TYPE_MESSAGE = 3;
    private static final int VIEW_TYPE_EMPTY = 4;

    private static final class RowItem {
        final int viewType;
        final String headerTitle;
        final TLRPC.Chat chat;
        final TLRPC.User user;
        final TLRPC.Message message;
        final boolean isPublicPost;

        RowItem(int viewType, String headerTitle, TLRPC.Chat chat, TLRPC.User user, TLRPC.Message message, boolean isPublicPost) {
            this.viewType = viewType;
            this.headerTitle = headerTitle;
            this.chat = chat;
            this.user = user;
            this.message = message;
            this.isPublicPost = isPublicPost;
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
    private int globalMsgReqId = 0;
    private int publicPostsReqId = 0;
    private Runnable pendingSearchRunnable;

    private final ArrayList<TLRPC.Chat> foundChats = new ArrayList<>();
    private final ArrayList<TLRPC.User> foundUsers = new ArrayList<>();
    private final ArrayList<TLRPC.Message> foundPublicPosts = new ArrayList<>();
    private final ArrayList<TLRPC.Message> foundMessages = new ArrayList<>();
    private final ArrayList<RowItem> rows = new ArrayList<>();

    private boolean ignoreRequestLayout;

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
            if (item.viewType == VIEW_TYPE_PEER) {
                openPeer(item.chat, item.user);
            } else if (item.viewType == VIEW_TYPE_MESSAGE && item.message != null) {
                openMessage(item.message);
            }
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
        foundChats.clear();
        foundUsers.clear();
        foundPublicPosts.clear();
        foundMessages.clear();

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
        AndroidUtilities.runOnUIThread(pendingSearchRunnable, 280L);
    }

    private void cancelActiveRequests() {
        ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);
        if (contactsReqId != 0) {
            cm.cancelRequest(contactsReqId, true);
            contactsReqId = 0;
        }
        if (globalMsgReqId != 0) {
            cm.cancelRequest(globalMsgReqId, true);
            globalMsgReqId = 0;
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
            // Bot accounts search local cached dialogs & chats safely without hitting BOT_METHOD_INVALID
            searchLocalForBot(mc, query);
            pendingRequests = 0;
            loadingSpinner.setVisibility(GONE);
            rebuildRows();
            return;
        }

        pendingRequests = 3;

        // 1. Global public channels, supergroups, users, bots (contacts.search)
        TLRPC.TL_contacts_search contactsReq = new TLRPC.TL_contacts_search();
        contactsReq.q = query;
        contactsReq.limit = 35;
        contactsReqId = cm.sendRequest(contactsReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            contactsReqId = 0;
            if (!TextUtils.equals(query, lastQuery)) return;
            if (response instanceof TLRPC.TL_contacts_found) {
                TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                LinkedHashMap<Long, TLRPC.Chat> chatMap = new LinkedHashMap<>();
                LinkedHashMap<Long, TLRPC.User> userMap = new LinkedHashMap<>();
                if (res.chats != null) {
                    for (TLRPC.Chat c : res.chats) {
                        if (c != null) chatMap.put(c.id, c);
                    }
                }
                if (res.users != null) {
                    for (TLRPC.User u : res.users) {
                        if (u != null) userMap.put(u.id, u);
                    }
                }
                foundChats.clear();
                foundChats.addAll(chatMap.values());
                foundUsers.clear();
                foundUsers.addAll(userMap.values());
            }
            onOneRequestDone();
        }));

        // 2. Global messages across all chats/channels/groups (messages.searchGlobal)
        TLRPC.TL_messages_searchGlobal msgReq = new TLRPC.TL_messages_searchGlobal();
        msgReq.q = query;
        msgReq.limit = 35;
        msgReq.filter = new TLRPC.TL_inputMessagesFilterEmpty();
        msgReq.offset_peer = new TLRPC.TL_inputPeerEmpty();
        globalMsgReqId = cm.sendRequest(msgReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            globalMsgReqId = 0;
            if (!TextUtils.equals(query, lastQuery)) return;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                foundMessages.clear();
                if (res.messages != null) {
                    foundMessages.addAll(res.messages);
                }
            }
            onOneRequestDone();
        }));

        // 3. Worldwide public channel posts (channels.searchPosts; #hashtag is free globally)
        TLRPC.TL_channels_searchPosts postsReq = new TLRPC.TL_channels_searchPosts();
        if (query.startsWith("#") && query.length() > 1) {
            postsReq.flags |= 1;
            postsReq.hashtag = query.substring(1);
        } else {
            postsReq.flags |= 2;
            postsReq.query = query;
        }
        postsReq.limit = 20;
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

    private void searchLocalForBot(MessagesController mc, String query) {
        String low = query.toLowerCase(Locale.ROOT);
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        if (dialogs == null) return;
        for (TLRPC.Dialog d : dialogs) {
            if (d == null) continue;
            if (d.id < 0) {
                TLRPC.Chat c = mc.getChat(-d.id);
                if (c != null && ((c.title != null && c.title.toLowerCase(Locale.ROOT).contains(low))
                        || (c.username != null && c.username.toLowerCase(Locale.ROOT).contains(low)))) {
                    foundChats.add(c);
                }
            } else {
                TLRPC.User u = mc.getUser(d.id);
                if (u != null && (UserObject.getUserName(u).toLowerCase(Locale.ROOT).contains(low)
                        || (u.username != null && u.username.toLowerCase(Locale.ROOT).contains(low)))) {
                    foundUsers.add(u);
                }
            }
        }
    }

    private void onOneRequestDone() {
        pendingRequests = Math.max(0, pendingRequests - 1);
        if (pendingRequests == 0) {
            loadingSpinner.setVisibility(GONE);
        }
        rebuildRows();
    }

    private void rebuildRows() {
        rows.clear();
        rows.add(new RowItem(VIEW_TYPE_FILTERS, null, null, null, null, false));

        if (lastQuery.length() < 2) {
            rows.add(new RowItem(VIEW_TYPE_EMPTY,
                    "Введите запрос (от 2 символов), чтобы искать по всему Telegram: каналы, группы, людей, ботов, публичные посты и сообщения.",
                    null, null, null, false));
            adapter.notifyDataSetChanged();
            return;
        }

        boolean showChannels = activeFilter == FILTER_ALL || activeFilter == FILTER_CHANNELS;
        boolean showUsers = activeFilter == FILTER_ALL || activeFilter == FILTER_USERS;
        boolean showMsgs = activeFilter == FILTER_ALL || activeFilter == FILTER_MESSAGES;

        if (showChannels && !foundChats.isEmpty()) {
            rows.add(new RowItem(VIEW_TYPE_HEADER, "Каналы и группы (" + foundChats.size() + ")", null, null, null, false));
            for (TLRPC.Chat c : foundChats) {
                rows.add(new RowItem(VIEW_TYPE_PEER, null, c, null, null, false));
            }
        }
        if (showUsers && !foundUsers.isEmpty()) {
            rows.add(new RowItem(VIEW_TYPE_HEADER, "Люди и боты (" + foundUsers.size() + ")", null, null, null, false));
            for (TLRPC.User u : foundUsers) {
                rows.add(new RowItem(VIEW_TYPE_PEER, null, null, u, null, false));
            }
        }
        if (showMsgs && !foundPublicPosts.isEmpty()) {
            rows.add(new RowItem(VIEW_TYPE_HEADER, "Публичные посты (" + foundPublicPosts.size() + ")", null, null, null, false));
            for (TLRPC.Message m : foundPublicPosts) {
                rows.add(new RowItem(VIEW_TYPE_MESSAGE, null, null, null, m, true));
            }
        }
        if (showMsgs && !foundMessages.isEmpty()) {
            rows.add(new RowItem(VIEW_TYPE_HEADER, "Сообщения (" + foundMessages.size() + ")", null, null, null, false));
            for (TLRPC.Message m : foundMessages) {
                rows.add(new RowItem(VIEW_TYPE_MESSAGE, null, null, null, m, false));
            }
        }

        if (rows.size() == 1 && pendingRequests == 0) {
            rows.add(new RowItem(VIEW_TYPE_EMPTY, "Ничего не найдено по запросу «" + lastQuery + "»", null, null, null, false));
        }
        adapter.notifyDataSetChanged();
    }

    private void openPeer(TLRPC.Chat chat, TLRPC.User user) {
        if (parentFragment == null) return;
        Bundle args = new Bundle();
        if (chat != null) {
            args.putLong("chat_id", chat.id);
        } else if (user != null) {
            args.putLong("user_id", user.id);
        } else {
            return;
        }
        parentFragment.presentFragment(new ChatActivity(args));
    }

    private void openMessage(TLRPC.Message msg) {
        if (parentFragment == null || msg == null) return;
        long dialogId = MessageObject.getDialogId(msg);
        if (dialogId == 0) return;
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
                    ((PeerResultCell) holder.itemView).bind(item.chat, item.user);
                    break;
                case VIEW_TYPE_MESSAGE:
                    ((MessageResultCell) holder.itemView).bind(item.message, item.isPublicPost);
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
        private final String[] labels = {"Всё", "Каналы и группы", "Люди и боты", "Сообщения и посты"};

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
        }

        void bind() {
            row.removeAllViews();
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                boolean selected = activeFilter == idx;
                TextView pill = new TextView(getContext());
                pill.setText(labels[i]);
                pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
                pill.setTypeface(AndroidUtilities.bold());
                pill.setPadding(dp(12), dp(6), dp(12), dp(6));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16));
                if (selected) {
                    bg.setColor(pal.accentPrimary);
                    pill.setTextColor(Color.WHITE);
                } else {
                    bg.setColor(pal.chipSurface);
                    bg.setStroke(Math.max(1, dp(0.8f)), pal.borderSubtle);
                    pill.setTextColor(pal.textPrimary);
                }
                pill.setBackground(bg);
                pill.setOnClickListener(v -> {
                    if (activeFilter != idx) {
                        activeFilter = idx;
                        rebuildRows();
                    }
                });
                row.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 7, 0));
            }
        }
    }

    private final class PeerResultCell extends LinearLayout {
        private final BackupImageView avatar;
        private final TextView titleTv;
        private final TextView subTv;

        PeerResultCell(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(8), dp(14), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(dp(22));
            addView(avatar, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            titleTv.setTypeface(AndroidUtilities.bold());
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subTv = new TextView(ctx);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            subTv.setMaxLines(1);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(TLRPC.Chat chat, TLRPC.User user) {
            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            AvatarDrawable ad = new AvatarDrawable();
            if (chat != null) {
                ad.setInfo(currentAccount, chat);
                avatar.setForUserOrChat(chat, ad);
                titleTv.setText(highlightQuery(chat.title != null ? chat.title : "Чат"));
                StringBuilder sub = new StringBuilder();
                String uname = ChatObject.getPublicUsername(chat);
                if (!TextUtils.isEmpty(uname)) sub.append("@").append(uname);
                if (chat.participants_count > 0) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(LocaleController.formatPluralStringComma(
                            ChatObject.isChannelAndNotMegaGroup(chat) ? "Subscribers" : "Members",
                            chat.participants_count));
                } else if (sub.length() == 0) {
                    sub.append(ChatObject.isChannelAndNotMegaGroup(chat) ? "Канал" : "Группа");
                }
                subTv.setText(sub.toString());
            } else if (user != null) {
                ad.setInfo(currentAccount, user);
                avatar.setForUserOrChat(user, ad);
                titleTv.setText(highlightQuery(UserObject.getUserName(user)));
                StringBuilder sub = new StringBuilder();
                String uname = UserObject.getPublicUsername(user);
                if (!TextUtils.isEmpty(uname)) sub.append("@").append(uname);
                if (user.bot) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append("бот");
                } else if (sub.length() == 0) {
                    sub.append("пользователь");
                }
                subTv.setText(sub.toString());
            }
        }
    }

    private final class MessageResultCell extends LinearLayout {
        private final BackupImageView avatar;
        private final TextView titleTv;
        private final TextView bodyTv;
        private final TextView dateTv;

        MessageResultCell(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(8), dp(14), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(dp(22));
            addView(avatar, LayoutHelper.createLinear(44, 44, Gravity.TOP, 0, 2, 12, 0));

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

        void bind(TLRPC.Message msg, boolean isPublicPost) {
            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bodyTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dateTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));

            MessagesController mc = MessagesController.getInstance(currentAccount);
            long did = MessageObject.getDialogId(msg);
            AvatarDrawable ad = new AvatarDrawable();
            String peerName = isPublicPost ? "Публичный пост" : "Чат";
            if (DialogObject.isUserDialog(did)) {
                TLRPC.User u = mc.getUser(did);
                if (u != null) {
                    ad.setInfo(currentAccount, u);
                    avatar.setForUserOrChat(u, ad);
                    peerName = UserObject.getUserName(u);
                }
            } else {
                TLRPC.Chat c = mc.getChat(-did);
                if (c != null) {
                    ad.setInfo(currentAccount, c);
                    avatar.setForUserOrChat(c, ad);
                    peerName = c.title != null ? c.title : peerName;
                }
            }
            titleTv.setText(isPublicPost ? ("🌐 " + peerName) : peerName);
            dateTv.setText(LocaleController.stringForMessageListDate(msg.date));
            String text = !TextUtils.isEmpty(msg.message) ? msg.message.replace('\n', ' ') : "[Медиа]";
            bodyTv.setText(highlightQuery(text));
        }
    }

    private CharSequence highlightQuery(String text) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(lastQuery)) return text == null ? "" : text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        String lowText = text.toLowerCase(Locale.ROOT);
        String lowQ = lastQuery.toLowerCase(Locale.ROOT);
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
}
