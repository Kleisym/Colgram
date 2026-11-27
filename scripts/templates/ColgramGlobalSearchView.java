package org.telegram.ui;

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
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pure Global Telegram Search:
 * - Never pollutes Global Search with local private chats, personal contacts, or private messages
 *   (Tab 0 "Чаты" / DialogsSearchAdapter already handles local chats & contacts).
 * - Parallel remote global RPCs:
 *     1) TL_contacts_search (limit=50): worldwide public channels, groups, bots, and public users
 *        (plus automatic Cyrillic/Latin transliteration query in parallel when applicable)
 *     2) TL_contacts_resolveUsername: exact resolution for @username and t.me/... links
 *     3) TL_channels_searchPosts (limit=20): worldwide public channel posts & #hashtags
 *     4) Recommended public channels (getChannelRecommendations) when query is empty
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
    private static final int VIEW_TYPE_ACTION = 5;
    private static final String ACTION_RETRY_TOKEN = "__colgram_retry_global_search__";

    private static final class PeerEntry {
        final long dialogId;
        final TLRPC.Chat chat;
        final TLRPC.User user;
        final boolean isMine;
        final int matchScore;
        final int orderIndex;

        PeerEntry(long dialogId, TLRPC.Chat chat, TLRPC.User user, boolean isMine, int matchScore, int orderIndex) {
            this.dialogId = dialogId;
            this.chat = chat;
            this.user = user;
            this.isMine = isMine;
            this.matchScore = matchScore;
            this.orderIndex = orderIndex;
        }
    }

    private static final class RowItem {
        final int viewType;
        final String text;
        final String subText;
        final PeerEntry entry;
        final TLRPC.Message message;

        RowItem(int viewType, String text, String subText, PeerEntry entry, TLRPC.Message message) {
            this.viewType = viewType;
            this.text = text;
            this.subText = subText;
            this.entry = entry;
            this.message = message;
        }
    }

    private final BaseFragment parentFragment;
    private int currentAccount;
    public final RecyclerListView listView;
    private final GlobalAdapter adapter;
    private final ProgressBar loadingSpinner;

    private String lastQuery = "";
    private int activeFilter = FILTER_ALL;
    private int pendingRequests = 0;
    private boolean searchInFlight = false;
    private boolean searchCompletedForQuery = false;
    private String lastErrorText = null;

    private int contactsReqId = 0;
    private int translitContactsReqId = 0;
    private int resolveReqId = 0;
    private int publicPostsReqId = 0;
    private Runnable pendingSearchRunnable;
    private long lastOpenTapMs;

    private final ArrayList<PeerEntry> recommendedChannels = new ArrayList<>();
    private final ArrayList<PeerEntry> globalResults = new ArrayList<>();
    private final ArrayList<PeerEntry> globalTranslitResults = new ArrayList<>();
    private final ArrayList<PeerEntry> publicJoinedResults = new ArrayList<>();
    private PeerEntry resolvedEntry = null;
    private final ArrayList<TLRPC.Message> foundPublicPosts = new ArrayList<>();
    private final ArrayList<RowItem> rows = new ArrayList<>();

    private boolean ignoreRequestLayout;

    /** Standalone fragment hosting ColgramGlobalSearchView with an integrated search box. */
    public static BaseFragment createStandaloneFragment() {
        return new ColgramGlobalSearchActivity();
    }

    private int resolveAccount() {
        int acc = parentFragment != null ? parentFragment.getCurrentAccount() : UserConfig.selectedAccount;
        if (acc < 0 || acc >= UserConfig.MAX_ACCOUNT_COUNT) {
            acc = UserConfig.selectedAccount;
        }
        currentAccount = acc;
        return acc;
    }

    public ColgramGlobalSearchView(Context context, BaseFragment fragment) {
        super(context);
        this.parentFragment = fragment;
        this.currentAccount = resolveAccount();

        listView = new RecyclerListView(context);
        listView.setClipToPadding(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        adapter = new GlobalAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            RowItem item = rows.get(position);
            if (item.viewType == VIEW_TYPE_PEER && item.entry != null) {
                openPeer(item.entry.chat, item.entry.user);
            } else if (item.viewType == VIEW_TYPE_MESSAGE && item.message != null) {
                openMessage(item.message);
            } else if (item.viewType == VIEW_TYPE_ACTION) {
                if (ACTION_RETRY_TOKEN.equals(item.subText)) {
                    search(lastQuery, true);
                } else {
                    performQuickAction(item.subText);
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return false;
            RowItem item = rows.get(position);
            if (item.viewType == VIEW_TYPE_PEER && item.entry != null) {
                copyPeerLink(item.entry);
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
            return false;
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadingSpinner = new ProgressBar(context);
        loadingSpinner.setVisibility(GONE);
        addView(loadingSpinner, LayoutHelper.createFrame(32, 32, Gravity.CENTER));

        loadRecommendedChannels();
        rebuildRows();
    }

    public void onChannelRecommendationsLoaded() {
        if (lastQuery.isEmpty()) {
            loadRecommendedChannels();
            rebuildRows();
        }
    }

    private void loadRecommendedChannels() {
        recommendedChannels.clear();
        try {
            int acc = resolveAccount();
            TLRPC.User self = UserConfig.getInstance(acc).getCurrentUser();
            if (self != null && self.bot) return;
            MessagesController mc = MessagesController.getInstance(acc);
            if (mc == null) return;
            MessagesController.ChannelRecommendations rec = mc.getChannelRecommendations(0);
            if (rec == null || rec.chats == null) return;
            HashSet<Long> seen = new HashSet<>();
            int order = 0;
            for (int i = 0; i < rec.chats.size() && recommendedChannels.size() < 25; i++) {
                Object obj = rec.chats.get(i);
                if (obj instanceof TLRPC.Chat) {
                    TLRPC.Chat c = (TLRPC.Chat) obj;
                    long did = -c.id;
                    if (did != 0 && seen.add(did)) {
                        recommendedChannels.add(new PeerEntry(did, c, null, false, 0, order++));
                    }
                } else if (obj instanceof TLRPC.User) {
                    TLRPC.User u = (TLRPC.User) obj;
                    if (u.id != 0 && seen.add(u.id)) {
                        recommendedChannels.add(new PeerEntry(u.id, null, u, false, 0, order++));
                    }
                }
            }
        } catch (Throwable ignored) {}
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resolveAccount();
        if (!searchCompletedForQuery && !searchInFlight) {
            if (lastQuery.isEmpty()) {
                loadRecommendedChannels();
                rebuildRows();
            } else {
                search(lastQuery, true);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pendingSearchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        cancelActiveRequests();
        pendingRequests = 0;
        searchInFlight = false;
        searchCompletedForQuery = false;
        loadingSpinner.setVisibility(GONE);
    }

    public void search(String rawQuery) {
        search(rawQuery, false);
    }

    public void search(String rawQuery, boolean force) {
        resolveAccount();
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (pendingSearchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        if (!force && q.equals(lastQuery) && (searchCompletedForQuery || searchInFlight)) {
            return;
        }
        cancelActiveRequests();
        lastQuery = q;
        lastErrorText = null;
        searchCompletedForQuery = false;
        searchInFlight = false;
        pendingRequests = 0;

        globalResults.clear();
        globalTranslitResults.clear();
        publicJoinedResults.clear();
        resolvedEntry = null;
        foundPublicPosts.clear();

        if (q.isEmpty()) {
            loadRecommendedChannels();
            searchCompletedForQuery = true;
            loadingSpinner.setVisibility(GONE);
            rebuildRows();
            return;
        }

        final String usernameCand = extractUsernameCandidate(q);
        if (q.length() < 2 && usernameCand == null) {
            searchCompletedForQuery = true;
            loadingSpinner.setVisibility(GONE);
            rebuildRows();
            return;
        }

        searchInFlight = true;
        rebuildRows();

        final String targetQuery = q;
        pendingSearchRunnable = () -> executeGlobalSearch(targetQuery);
        AndroidUtilities.runOnUIThread(pendingSearchRunnable, 220L);
    }

    private void cancelActiveRequests() {
        ConnectionsManager cm = ConnectionsManager.getInstance(resolveAccount());
        if (contactsReqId != 0) {
            cm.cancelRequest(contactsReqId, true);
            contactsReqId = 0;
        }
        if (translitContactsReqId != 0) {
            cm.cancelRequest(translitContactsReqId, true);
            translitContactsReqId = 0;
        }
        if (resolveReqId != 0) {
            cm.cancelRequest(resolveReqId, true);
            resolveReqId = 0;
        }
        if (publicPostsReqId != 0) {
            cm.cancelRequest(publicPostsReqId, true);
            publicPostsReqId = 0;
        }
    }

    private static boolean isPublicDiscoverablePeer(TLRPC.Chat c, TLRPC.User u) {
        if (c != null) {
            if (!TextUtils.isEmpty(ChatObject.getPublicUsername(c))) return true;
            if (c.usernames != null) {
                for (TLRPC.TL_username un : c.usernames) {
                    if (un != null && un.active && !TextUtils.isEmpty(un.username)) return true;
                }
            }
            return false;
        }
        if (u != null) {
            if (UserObject.isUserSelf(u)) return false;
            if (!TextUtils.isEmpty(UserObject.getPublicUsername(u))) return true;
            if (u.usernames != null) {
                for (TLRPC.TL_username un : u.usernames) {
                    if (un != null && un.active && !TextUtils.isEmpty(un.username)) return true;
                }
            }
            return false;
        }
        return false;
    }

    private static String extractDirectUrl(String query) {
        if (TextUtils.isEmpty(query)) return null;
        String trimmed = query.trim();
        String low = trimmed.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) {
            return trimmed;
        }
        if (low.startsWith("t.me/") || low.startsWith("telegram.me/")) {
            return "https://" + trimmed;
        }
        return null;
    }

    private static String extractUsernameCandidate(String query) {
        if (TextUtils.isEmpty(query)) return null;
        String s = query.trim();
        if (s.indexOf(' ') >= 0) return null;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("https://")) s = s.substring(8);
        else if (low.startsWith("http://")) s = s.substring(7);
        low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("t.me/")) s = s.substring(5);
        else if (low.startsWith("telegram.me/")) s = s.substring(12);
        else if (s.startsWith("@")) s = s.substring(1);

        if (s.toLowerCase(Locale.ROOT).startsWith("s/")) {
            s = s.substring(2);
        }
        int qIdx = s.indexOf('?');
        if (qIdx >= 0) s = s.substring(0, qIdx);
        int slashIdx = s.indexOf('/');
        if (slashIdx >= 0) s = s.substring(0, slashIdx);

        if (s.length() < 4 || s.length() > 32) return null;
        char first = s.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) return null;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ok) return null;
        }
        return s;
    }

    private static String formatRpcError(TLRPC.TL_error error) {
        if (error == null || TextUtils.isEmpty(error.text)) return null;
        String t = error.text;
        if (t.startsWith("FLOOD_WAIT_")) {
            String sec = t.substring("FLOOD_WAIT_".length());
            return "Telegram временно ограничил поиск (подождите " + sec + " сек.)";
        }
        if (t.contains("SEARCH_QUERY_EMPTY")) {
            return null;
        }
        return "Ошибка глобального поиска: " + t;
    }

    private void executeGlobalSearch(final String query) {
        if (!TextUtils.equals(query, lastQuery)) return;
        final int acc = resolveAccount();
        final MessagesController mc = MessagesController.getInstance(acc);
        final MessagesStorage ms = MessagesStorage.getInstance(acc);
        final ConnectionsManager cm = ConnectionsManager.getInstance(acc);
        final TLRPC.User self = UserConfig.getInstance(acc).getCurrentUser();

        if (self != null && self.bot) {
            pendingRequests = 0;
            searchInFlight = false;
            searchCompletedForQuery = true;
            loadingSpinner.setVisibility(GONE);
            rebuildRows();
            return;
        }

        final String usernameCand = extractUsernameCandidate(query);
        final String rpcQuery = usernameCand != null ? usernameCand
                : (query.startsWith("@") && query.length() > 1 ? query.substring(1) : query);
        int reqCount = 0;

        // 1. Official Telegram Global Peer Search (TL_contacts_search limit=50)
        if (rpcQuery.length() >= 2) {
            reqCount++;
            TLRPC.TL_contacts_search contactsReq = new TLRPC.TL_contacts_search();
            contactsReq.q = rpcQuery;
            contactsReq.limit = 50;
            contactsReqId = cm.sendRequest(contactsReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                contactsReqId = 0;
                if (!TextUtils.equals(query, lastQuery)) return;
                if (error == null && response instanceof TLRPC.TL_contacts_found) {
                    TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                    mc.putUsers(res.users, false);
                    mc.putChats(res.chats, false);
                    ms.putUsersAndChats(res.users, res.chats, true, true);

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

                    globalResults.clear();
                    publicJoinedResults.clear();
                    HashSet<Long> globalSeen = new HashSet<>();
                    int gOrder = 0;
                    if (res.results != null) {
                        for (TLRPC.Peer peer : res.results) {
                            if (peer == null) continue;
                            long did = DialogObject.getPeerDialogId(peer);
                            if (did == 0 || !globalSeen.add(did)) continue;
                            TLRPC.Chat c = did < 0 ? chatById.get(-did) : null;
                            TLRPC.User u = did > 0 ? userById.get(did) : null;
                            if (c == null && u == null) continue;
                            if (u != null && UserObject.isUserSelf(u)) continue;
                            globalResults.add(new PeerEntry(did, c, u, false, 0, gOrder++));
                        }
                    }
                    // Include ONLY public discoverable peers (with @username) from my_results
                    // so private personal DMs / private contacts NEVER leak into Global Search!
                    int pubOrder = 0;
                    if (res.my_results != null) {
                        for (TLRPC.Peer peer : res.my_results) {
                            if (peer == null) continue;
                            long did = DialogObject.getPeerDialogId(peer);
                            if (did == 0 || !globalSeen.add(did)) continue;
                            TLRPC.Chat c = did < 0 ? chatById.get(-did) : null;
                            TLRPC.User u = did > 0 ? userById.get(did) : null;
                            if (!isPublicDiscoverablePeer(c, u)) continue;
                            publicJoinedResults.add(new PeerEntry(did, c, u, true, 0, pubOrder++));
                        }
                    }
                } else if (error != null) {
                    String errMsg = formatRpcError(error);
                    if (errMsg != null) lastErrorText = errMsg;
                }
                onOneRequestDone();
            }), ConnectionsManager.RequestFlagFailOnServerErrors);

            // 1b. Parallel transliteration query for Cyrillic/Latin discovery (e.g. "телеграм" -> "telegram")
            String translitRaw = LocaleController.getInstance().getTranslitString(rpcQuery);
            final String translitQ = translitRaw != null ? translitRaw.trim() : "";
            if (translitQ.length() >= 3 && !translitQ.equalsIgnoreCase(rpcQuery)) {
                reqCount++;
                TLRPC.TL_contacts_search trReq = new TLRPC.TL_contacts_search();
                trReq.q = translitQ;
                trReq.limit = 30;
                translitContactsReqId = cm.sendRequest(trReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    translitContactsReqId = 0;
                    if (!TextUtils.equals(query, lastQuery)) return;
                    if (error == null && response instanceof TLRPC.TL_contacts_found) {
                        TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                        mc.putUsers(res.users, false);
                        mc.putChats(res.chats, false);
                        ms.putUsersAndChats(res.users, res.chats, true, true);
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
                        globalTranslitResults.clear();
                        HashSet<Long> seenTr = new HashSet<>();
                        int trOrder = 0;
                        if (res.results != null) {
                            for (TLRPC.Peer peer : res.results) {
                                if (peer == null) continue;
                                long did = DialogObject.getPeerDialogId(peer);
                                if (did == 0 || !seenTr.add(did)) continue;
                                TLRPC.Chat c = did < 0 ? chatById.get(-did) : null;
                                TLRPC.User u = did > 0 ? userById.get(did) : null;
                                if (c == null && u == null) continue;
                                if (u != null && UserObject.isUserSelf(u)) continue;
                                globalTranslitResults.add(new PeerEntry(did, c, u, false, 0, trOrder++));
                            }
                        }
                    }
                    onOneRequestDone();
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
            }
        }

        // 2. Exact @username / t.me link resolver (TL_contacts_resolveUsername)
        if (usernameCand != null) {
            reqCount++;
            TLRPC.TL_contacts_resolveUsername resolveReq = new TLRPC.TL_contacts_resolveUsername();
            resolveReq.username = usernameCand;
            resolveReqId = cm.sendRequest(resolveReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                resolveReqId = 0;
                if (!TextUtils.equals(query, lastQuery)) return;
                if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                    mc.putUsers(res.users, false);
                    mc.putChats(res.chats, false);
                    ms.putUsersAndChats(res.users, res.chats, true, true);
                    if (res.peer != null) {
                        long did = DialogObject.getPeerDialogId(res.peer);
                        TLRPC.Chat c = did < 0 ? mc.getChat(-did) : null;
                        TLRPC.User u = did > 0 ? mc.getUser(did) : null;
                        if (c == null && did < 0 && res.chats != null) {
                            for (TLRPC.Chat cc : res.chats) {
                                if (cc != null && cc.id == -did) { c = cc; break; }
                            }
                        }
                        if (u == null && did > 0 && res.users != null) {
                            for (TLRPC.User uu : res.users) {
                                if (uu != null && uu.id == did) { u = uu; break; }
                            }
                        }
                        if (c != null || u != null) {
                            resolvedEntry = new PeerEntry(did, c, u, false, 4, -1);
                        }
                    }
                }
                onOneRequestDone();
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }

        // 3. Worldwide public channel posts & #hashtags (TL_channels_searchPosts)
        if ((query.startsWith("#") && query.length() > 1) || query.length() >= 3) {
            reqCount++;
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
                if (error == null && response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    mc.putUsers(res.users, false);
                    mc.putChats(res.chats, false);
                    ms.putUsersAndChats(res.users, res.chats, true, true);
                    foundPublicPosts.clear();
                    if (res.messages != null) {
                        foundPublicPosts.addAll(res.messages);
                    }
                }
                onOneRequestDone();
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }

        pendingRequests = reqCount;
        if (reqCount == 0) {
            searchInFlight = false;
            searchCompletedForQuery = true;
            loadingSpinner.setVisibility(GONE);
        }
        rebuildRows();
    }

    private void onOneRequestDone() {
        pendingRequests = Math.max(0, pendingRequests - 1);
        if (pendingRequests == 0) {
            searchInFlight = false;
            searchCompletedForQuery = true;
        }
        rebuildRows();
    }

    private static boolean matchesPeerFilter(PeerEntry entry, int filter) {
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

    private ArrayList<PeerEntry> collectAllGlobalPeers() {
        HashSet<Long> seen = new HashSet<>();
        ArrayList<PeerEntry> merged = new ArrayList<>();
        if (resolvedEntry != null && seen.add(resolvedEntry.dialogId)) {
            merged.add(resolvedEntry);
        }
        for (PeerEntry e : globalResults) {
            if (seen.add(e.dialogId)) merged.add(e);
        }
        for (PeerEntry e : globalTranslitResults) {
            if (seen.add(e.dialogId)) merged.add(e);
        }
        for (PeerEntry e : publicJoinedResults) {
            if (seen.add(e.dialogId)) merged.add(e);
        }
        return merged;
    }

    private void rebuildRows() {
        rows.clear();
        rows.add(new RowItem(VIEW_TYPE_FILTERS, null, null, null, null));

        if (lastQuery.isEmpty()) {
            ArrayList<PeerEntry> recs = new ArrayList<>();
            for (PeerEntry e : recommendedChannels) {
                if (matchesPeerFilter(e, activeFilter)) {
                    recs.add(e);
                }
            }
            if (!recs.isEmpty()) {
                rows.add(new RowItem(VIEW_TYPE_HEADER, "Популярные публичные каналы", null, null, null));
                for (PeerEntry e : recs) {
                    rows.add(new RowItem(VIEW_TYPE_PEER, null, null, e, null));
                }
            } else {
                rows.add(new RowItem(VIEW_TYPE_EMPTY,
                        "Глобальный поиск по всему Telegram.\nВведите название канала, бота, группы, @username, ссылку t.me/... или #хэштег.",
                        null, null, null));
            }
            loadingSpinner.setVisibility(GONE);
            adapter.notifyDataSetChanged();
            return;
        }

        final String directUrl = extractDirectUrl(lastQuery);
        final String handleCand = extractUsernameCandidate(lastQuery);
        if (directUrl != null) {
            rows.add(new RowItem(VIEW_TYPE_ACTION, "Открыть ссылку: " + directUrl, directUrl, null, null));
        } else if (lastQuery.startsWith("@") && handleCand != null) {
            rows.add(new RowItem(VIEW_TYPE_ACTION, "Открыть @" + handleCand, handleCand, null, null));
        }

        boolean hasRealResults = false;
        if (activeFilter != FILTER_POSTS) {
            ArrayList<PeerEntry> allGlobal = collectAllGlobalPeers();
            ArrayList<PeerEntry> remotePeers = new ArrayList<>();
            for (PeerEntry e : allGlobal) {
                if (matchesPeerFilter(e, activeFilter)) {
                    remotePeers.add(e);
                }
            }
            if (!remotePeers.isEmpty()) {
                hasRealResults = true;
                rows.add(new RowItem(VIEW_TYPE_HEADER, "Глобальный поиск", null, null, null));
                for (PeerEntry e : remotePeers) {
                    rows.add(new RowItem(VIEW_TYPE_PEER, null, null, e, null));
                }
            }
        }

        if (activeFilter == FILTER_ALL || activeFilter == FILTER_POSTS) {
            if (!foundPublicPosts.isEmpty()) {
                HashSet<String> seenMsgKeys = new HashSet<>();
                ArrayList<TLRPC.Message> postsToAdd = new ArrayList<>();
                for (TLRPC.Message msg : foundPublicPosts) {
                    if (msg == null) continue;
                    String key = MessageObject.getDialogId(msg) + ":" + msg.id;
                    if (seenMsgKeys.add(key)) {
                        postsToAdd.add(msg);
                    }
                }
                if (!postsToAdd.isEmpty()) {
                    hasRealResults = true;
                    rows.add(new RowItem(VIEW_TYPE_HEADER, "Публичные посты в каналах", null, null, null));
                    for (TLRPC.Message msg : postsToAdd) {
                        rows.add(new RowItem(VIEW_TYPE_MESSAGE, null, null, null, msg));
                    }
                }
            }
        }

        loadingSpinner.setVisibility((pendingRequests > 0 || searchInFlight) && !hasRealResults ? VISIBLE : GONE);

        if (!hasRealResults && !searchInFlight && pendingRequests == 0) {
            if (lastErrorText != null) {
                rows.add(new RowItem(VIEW_TYPE_EMPTY, lastErrorText, null, null, null));
                rows.add(new RowItem(VIEW_TYPE_ACTION, "Повторить глобальный поиск", ACTION_RETRY_TOKEN, null, null));
            } else {
                rows.add(new RowItem(VIEW_TYPE_EMPTY,
                        "В глобальном поиске Telegram ничего не найдено по запросу «" + lastQuery + "»",
                        null, null, null));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void performQuickAction(String target) {
        if (TextUtils.isEmpty(target)) return;
        Context ctx = getContext();
        if (target.startsWith("http://") || target.startsWith("https://")) {
            if (ctx != null) Browser.openUrl(ctx, target);
        } else if (parentFragment != null) {
            MessagesController.getInstance(currentAccount).openByUserName(target, parentFragment, 0);
        }
    }

    private void copyPeerLink(PeerEntry entry) {
        Context ctx = getContext();
        if (ctx == null) return;
        String username = entry.chat != null
                ? ChatObject.getPublicUsername(entry.chat)
                : (entry.user != null ? UserObject.getPublicUsername(entry.user) : null);
        String fallbackName = entry.chat != null
                ? (entry.chat.title != null ? entry.chat.title : "Чат")
                : (entry.user != null ? UserObject.getUserName(entry.user) : "Пользователь");
        String payload = !TextUtils.isEmpty(username) ? ("@" + username) : fallbackName;
        try {
            Context appCtx = ctx.getApplicationContext();
            ClipboardManager cm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Colgram Search", payload));
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
            return t == VIEW_TYPE_PEER || t == VIEW_TYPE_MESSAGE || t == VIEW_TYPE_ACTION;
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
            } else if (viewType == VIEW_TYPE_ACTION) {
                v = new ActionRowCell(context);
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
                    hc.setText(item.text);
                    hc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
                    break;
                case VIEW_TYPE_PEER:
                    ((PeerResultCell) holder.itemView).bind(item.entry);
                    break;
                case VIEW_TYPE_MESSAGE:
                    ((MessageResultCell) holder.itemView).bind(item.message);
                    break;
                case VIEW_TYPE_ACTION:
                    ((ActionRowCell) holder.itemView).bind(item.text);
                    break;
                case VIEW_TYPE_EMPTY:
                    TextView tv = (TextView) holder.itemView;
                    tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    tv.setText(item.text);
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
                bg.setCornerRadius(dp(999));
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
            ArrayList<PeerEntry> pool = lastQuery.isEmpty() ? recommendedChannels : collectAllGlobalPeers();
            int cAll = pool.size() + foundPublicPosts.size();
            int cCh = 0, cBots = 0, cGrp = 0, cPpl = 0;
            for (PeerEntry e : pool) {
                if (matchesPeerFilter(e, FILTER_CHANNELS)) cCh++;
                else if (matchesPeerFilter(e, FILTER_GROUPS)) cGrp++;
                else if (matchesPeerFilter(e, FILTER_BOTS)) cBots++;
                else if (matchesPeerFilter(e, FILTER_PEOPLE)) cPpl++;
            }
            int[] counts = new int[]{cAll, cCh, cBots, cGrp, cPpl, foundPublicPosts.size()};
            for (int i = 0; i < pills.length; i++) {
                boolean selected = activeFilter == i;
                TextView pill = pills[i];
                GradientDrawable bg = pillBgs[i];
                pill.setText(!lastQuery.isEmpty() && counts[i] > 0 ? (labels[i] + " · " + counts[i]) : labels[i]);
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

    private final class ActionRowCell extends LinearLayout {
        private final ImageView iconIv;
        private final TextView titleTv;

        ActionRowCell(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(10), dp(16), dp(10));
            setBackground(Theme.getSelectorDrawable(false));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            iconIv = new ImageView(ctx);
            iconIv.setImageResource(R.drawable.msg_openin);
            addView(iconIv, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            titleTv.setTypeface(AndroidUtilities.bold());
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }

        void bind(String text) {
            int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
            iconIv.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            titleTv.setTextColor(accent);
            titleTv.setText(text);
        }
    }

    private final class PeerResultCell extends LinearLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView titleTv;
        private final ImageView verifiedIv;
        private final TextView subTv;

        PeerResultCell(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(8), dp(14), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(dp(24));
            addView(avatar, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            LinearLayout topRow = new LinearLayout(ctx);
            topRow.setOrientation(HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
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
            topRow.addView(verifiedIv, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL));

            subTv = new TextView(ctx);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            subTv.setMaxLines(1);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(PeerEntry entry) {
            if (entry == null) return;
            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

            TLRPC.Chat chat = entry.chat;
            TLRPC.User user = entry.user;

            boolean verified = (chat != null && chat.verified) || (user != null && user.verified);
            verifiedIv.setVisibility(verified ? VISIBLE : GONE);

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
                subTv.setText(highlightQuery(sub.toString()));
            } else if (user != null) {
                if (UserObject.isUserSelf(user)) {
                    avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                    avatar.setImage(null, null, avatarDrawable, user);
                    titleTv.setText(highlightQuery("Избранное"));
                } else {
                    avatarDrawable.setInfo(currentAccount, user);
                    avatar.setForUserOrChat(user, avatarDrawable);
                    titleTv.setText(highlightQuery(UserObject.getUserName(user)));
                }

                StringBuilder sub = new StringBuilder();
                String uname = UserObject.getPublicUsername(user);
                if (!TextUtils.isEmpty(uname)) sub.append("@").append(uname);
                if (sub.length() > 0) sub.append(" · ");
                if (UserObject.isUserSelf(user)) {
                    sub.append("Сохранённые сообщения");
                } else if (user.bot) {
                    sub.append("Бот");
                } else if (user.contact) {
                    sub.append("Контакт");
                } else {
                    sub.append("Пользователь");
                }
                subTv.setText(highlightQuery(sub.toString()));
            }
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
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
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
            avatar.setRoundRadius(dp(24));
            addView(avatar, LayoutHelper.createLinear(48, 48, Gravity.TOP, 0, 2, 12, 0));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(VERTICAL);
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleTv = new TextView(ctx);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            titleTv.setTypeface(AndroidUtilities.bold());
            titleTv.setMaxLines(1);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            dateTv = new TextView(ctx);
            dateTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            dateTv.setMaxLines(1);
            top.addView(dateTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            bodyTv = new TextView(ctx);
            bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            bodyTv.setMaxLines(2);
            bodyTv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(bodyTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        void bind(TLRPC.Message msg) {
            if (msg == null) return;
            titleTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bodyTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            dateTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));

            MessagesController mc = MessagesController.getInstance(currentAccount);
            long did = MessageObject.getDialogId(msg);
            String peerName = "Сообщение";
            if (DialogObject.isUserDialog(did)) {
                TLRPC.User u = mc.getUser(did);
                if (u != null) {
                    if (UserObject.isUserSelf(u)) {
                        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                        avatar.setImage(null, null, avatarDrawable, u);
                        peerName = "Избранное";
                    } else {
                        avatarDrawable.setInfo(currentAccount, u);
                        avatar.setForUserOrChat(u, avatarDrawable);
                        peerName = UserObject.getUserName(u);
                    }
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
        String cand = extractUsernameCandidate(lastQuery);
        String qClean = cand != null ? cand : (lastQuery.startsWith("@") && lastQuery.length() > 1 ? lastQuery.substring(1) : lastQuery);
        String lowText = text.toLowerCase(Locale.ROOT);
        String lowQ = qClean.toLowerCase(Locale.ROOT);
        int idx = lowText.indexOf(lowQ);
        if (idx >= 0) {
            int end = Math.min(text.length(), idx + lowQ.length());
            int hlColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
            sb.setSpan(new ForegroundColorSpan(hlColor), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    /** Standalone screen launching directly into native-grade Telegram Global & Local Search. */
    private static final class ColgramGlobalSearchActivity extends BaseFragment {
        private ColgramGlobalSearchView searchView;
        private EditText searchInput;

        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle("Глобальный поиск");
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
            searchInput.setHint("Глобальный поиск: каналы, боты, @username, #теги…");
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
