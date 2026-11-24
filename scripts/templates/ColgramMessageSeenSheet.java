package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MessagePrivateSeenView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * ColgramMessageSeenSheet — Mobile equivalent of Telegram Desktop's right-click "Seen by" viewer
 * for photos, videos, documents/media files, voice messages, and video circles (кружки).
 * Accessible from ChatActivity ActionMode (long-press any media/кружок -> 👁), PhotoViewer ⋮ menu,
 * and ChatActivity's message menu.
 */
public final class ColgramMessageSeenSheet {

    private ColgramMessageSeenSheet() {}

    public static boolean canCheckSeen(int currentAccount, MessageObject message, TLRPC.Chat chat, TLRPC.User user) {
        if (message == null || message.messageOwner == null) return false;
        if (!message.isOutOwner() || !message.isSent() || message.isEditing()
                || message.isSending() || message.isSendError() || message.getId() <= 0) {
            return false;
        }
        if (message.isEphemeral() || message.isSecretMedia()) return false;
        if (message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) return false;

        if (chat != null) {
            // Groups & supergroups (not broadcast channels / monoforums)
            if (ChatObject.isChannel(chat) && !ChatObject.isMegagroup(chat)) return false;
            if (ChatObject.isMonoForum(chat)) return false;
            return true;
        }
        if (user != null) {
            return !UserObject.isUserSelf(user)
                    && !UserObject.isReplyUser(user)
                    && !UserObject.isAnonymous(user)
                    && !user.bot
                    && !UserObject.isService(user.id);
        }
        return false;
    }

    public static boolean canCheckSeenForMessage(int currentAccount, MessageObject message) {
        if (message == null) return false;
        long dialogId = message.getDialogId();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.Chat chat = dialogId < 0 ? mc.getChat(-dialogId) : null;
        TLRPC.User user = dialogId > 0 ? mc.getUser(dialogId) : null;
        return canCheckSeen(currentAccount, message, chat, user);
    }

    public static void showFromPhotoViewer(
            Activity activity,
            int currentAccount,
            MessageObject message,
            BaseFragment parentFragment,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (activity == null || message == null) return;
        long dialogId = message.getDialogId();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.Chat chat = dialogId < 0 ? mc.getChat(-dialogId) : null;
        TLRPC.User user = dialogId > 0 ? mc.getUser(dialogId) : null;
        show(activity, parentFragment, currentAccount, message, chat, user, resourcesProvider);
    }

    public static void show(
            BaseFragment parentFragment,
            int currentAccount,
            MessageObject message,
            TLRPC.Chat chat,
            TLRPC.User user,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (parentFragment == null) return;
        Activity act = parentFragment.getParentActivity();
        if (act == null) return;
        show(act, parentFragment, currentAccount, message, chat, user, resourcesProvider);
    }

    public static void show(
            Activity act,
            BaseFragment parentFragment,
            int currentAccount,
            MessageObject message,
            TLRPC.Chat chat,
            TLRPC.User user,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (act == null || act.isFinishing() || act.isDestroyed() || message == null || !canCheckSeen(currentAccount, message, chat, user)) {
            return;
        }
        final boolean isDark = Theme.isCurrentThemeDark();
        final int cTextPrimary = isDark ? 0xFFF1F5F9 : 0xFF0F172A;
        final int cTextSecondary = isDark ? 0xFF94A3B8 : 0xFF64748B;
        final int cCard = isDark ? 0xFF141822 : 0xFFFFFFFF;
        final int cBorder = isDark ? 0x22FFFFFF : 0x1A0F172A;
        final int cAccent = 0xFFE11D48;

        final boolean isRound = message.isRoundVideo();
        final boolean isVoice = message.isVoice();
        final boolean isPlayedMedia = isRound || isVoice;

        final BottomSheet sheet = new BottomSheet(act, false, resourcesProvider);
        sheet.setApplyBottomPadding(false);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(22) + AndroidUtilities.navigationBarHeight);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(isDark ? 0xFF0D1018 : 0xFFF8FAFC);
        sheetBg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        root.setBackground(sheetBg);

        // Drag pill
        View handle = new View(act);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(isDark ? 0x44FFFFFF : 0x330F172A);
        handleBg.setCornerRadius(dp(3));
        handle.setBackground(handleBg);
        root.addView(handle, LayoutHelper.createLinear(38, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // Header row
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconWrap = new FrameLayout(act);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(isDark ? 0x2BE11D48 : 0x18E11D48);
        iconWrap.setBackground(iconBg);
        ImageView icon = new ImageView(act);
        icon.setImageResource(isPlayedMedia ? R.drawable.msg_played : R.drawable.msg_seen);
        icon.setColorFilter(cAccent, PorterDuff.Mode.SRC_IN);
        iconWrap.addView(icon, LayoutHelper.createFrame(20, 20, Gravity.CENTER));
        header.addView(iconWrap, LayoutHelper.createLinear(38, 38, 0, 0, 12, 0));

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        final TextView titleTv = new TextView(act);
        if (isRound) {
            titleTv.setText("Кто посмотрел кружок");
        } else if (isVoice) {
            titleTv.setText("Кто прослушал голосовое");
        } else if (message.isPhoto() || message.isVideo() || message.isDocument() || message.isGif()) {
            titleTv.setText("Кто посмотрел медиа");
        } else {
            titleTv.setText("Кто посмотрел сообщение");
        }
        titleTv.setTextColor(cTextPrimary);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
        titleTv.setTypeface(AndroidUtilities.bold());
        titleCol.addView(titleTv);

        final TextView subTv = new TextView(act);
        subTv.setText(chat != null ? chat.title : ContactsController.formatName(user));
        subTv.setTextColor(cTextSecondary);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        subTv.setMaxLines(1);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        header.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Private 1-on-1 chat path: show native MessagePrivateSeenView inside our glass card
        if (chat == null) {
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable cBg = new GradientDrawable();
            cBg.setColor(cCard);
            cBg.setCornerRadius(dp(16));
            cBg.setStroke(dp(1), cBorder);
            card.setBackground(cBg);

            MessagePrivateSeenView privateSeen = new MessagePrivateSeenView(
                    act,
                    MessagePrivateSeenView.TYPE_SEEN,
                    message,
                    sheet::dismiss,
                    resourcesProvider
            );
            card.addView(privateSeen, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
            root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            sheet.setCustomView(root);
            ColgramGlobalOrbController.pushModalSuppression();
            sheet.setOnDismissListener(d -> ColgramGlobalOrbController.popModalSuppression());
            try {
                sheet.show();
            } catch (Throwable ignored) {
                ColgramGlobalOrbController.popModalSuppression();
            }
            return;
        }

        // Group / Supergroup path: live TL_messages_getMessageReadParticipants
        final LinearLayout listContainer = new LinearLayout(act);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cCard);
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), cBorder);
        listContainer.setBackground(cardBg);

        LinearLayout loadingRow = new LinearLayout(act);
        loadingRow.setOrientation(LinearLayout.HORIZONTAL);
        loadingRow.setGravity(Gravity.CENTER);
        loadingRow.setPadding(dp(12), dp(18), dp(12), dp(18));
        ProgressBar pb = new ProgressBar(act);
        pb.setIndeterminate(true);
        loadingRow.addView(pb, LayoutHelper.createLinear(22, 22, 0, 0, 10, 0));
        TextView loadTv = new TextView(act);
        loadTv.setText("Загружаю список просмотров…");
        loadTv.setTextColor(cTextSecondary);
        loadTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        loadingRow.addView(loadTv);
        listContainer.addView(loadingRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final int maxH = Math.min(dp(380), (int) (AndroidUtilities.displaySize.y * 0.52f));
        ScrollView scroll = new ScrollView(act) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int hMode = View.MeasureSpec.getMode(heightMeasureSpec);
                int hSize = View.MeasureSpec.getSize(heightMeasureSpec);
                int cappedSpec = (hMode == View.MeasureSpec.UNSPECIFIED)
                        ? View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
                        : View.MeasureSpec.makeMeasureSpec(Math.min(hSize, maxH), hMode);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(listContainer, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT) {{
            bottomMargin = dp(4);
        }});

        final int[] reqIds = new int[]{0, 0};
        sheet.setCustomView(root);
        ColgramGlobalOrbController.pushModalSuppression();
        sheet.setOnDismissListener(d -> {
            ColgramGlobalOrbController.popModalSuppression();
            ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);
            if (reqIds[0] != 0) {
                cm.cancelRequest(reqIds[0], true);
                reqIds[0] = 0;
            }
            if (reqIds[1] != 0) {
                cm.cancelRequest(reqIds[1], true);
                reqIds[1] = 0;
            }
        });
        try {
            sheet.show();
        } catch (Throwable ignored) {
            ColgramGlobalOrbController.popModalSuppression();
            return;
        }

        final MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.TL_messages_getMessageReadParticipants req = new TLRPC.TL_messages_getMessageReadParticipants();
        req.msg_id = message.getId();
        req.peer = mc.getInputPeer(message.getDialogId());
        final long fromId = message.messageOwner.from_id != null ? message.messageOwner.from_id.user_id : 0L;

        reqIds[0] = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            reqIds[0] = 0;
            if (sheet.isDismissed() || act.isFinishing() || act.isDestroyed()) return;
            if (error != null || !(response instanceof Vector)) {
                String msgText;
                if (error != null && error.text != null && error.text.contains("MESSAGE_ID_INVALID")) {
                    msgText = "Список просмотров недоступен для этого сообщения (возможно, прошло более 7 дней или в чате более 100 участников).";
                } else {
                    msgText = "Пока никто не посмотрел или список просмотров недоступен в этом чате.";
                }
                renderEmpty(act, listContainer, msgText, cTextSecondary);
                return;
            }

            Vector vector = (Vector) response;
            final ArrayList<Pair<Long, Integer>> allPeers = new ArrayList<>();
            final HashMap<Long, TLObject> localMap = new HashMap<>();
            boolean needFetchUsers = false;

            for (int i = 0, n = vector.objects.size(); i < n; i++) {
                Object o = vector.objects.get(i);
                long peerId = 0L;
                int date = 0;
                if (o instanceof TLRPC.TL_readParticipantDate) {
                    peerId = ((TLRPC.TL_readParticipantDate) o).user_id;
                    date = ((TLRPC.TL_readParticipantDate) o).date;
                } else if (o instanceof Long) {
                    peerId = (Long) o;
                }
                if (peerId == 0L || peerId == fromId) continue;
                allPeers.add(new Pair<>(peerId, date));
                TLObject cached = peerId > 0 ? mc.getUser(peerId) : mc.getChat(-peerId);
                if (cached != null) {
                    localMap.put(peerId, cached);
                } else {
                    needFetchUsers = true;
                }
            }

            if (allPeers.isEmpty()) {
                renderEmpty(act, listContainer,
                        isPlayedMedia ? "Пока никто не посмотрел / не прослушал." : "Пока никто из участников не посмотрел.",
                        cTextSecondary);
                subTv.setText(chat.title + " • 0 просмотров");
                return;
            }

            Runnable renderList = () -> {
                if (sheet.isDismissed()) return;
                subTv.setText(chat.title + " • " + LocaleController.formatPluralString(
                        isPlayedMedia ? "MessagePlayed" : "MessageSeen", allPeers.size()));
                listContainer.removeAllViews();
                for (int i = 0; i < allPeers.size(); i++) {
                    Pair<Long, Integer> pair = allPeers.get(i);
                    TLObject obj = localMap.get(pair.first);
                    addViewerRow(act, parentFragment, sheet, currentAccount, listContainer, obj, pair.first, pair.second,
                            cTextPrimary, cTextSecondary, cBorder, i < allPeers.size() - 1);
                }
            };

            if (!needFetchUsers) {
                renderList.run();
                return;
            }

            if (ChatObject.isChannel(chat)) {
                TLRPC.TL_channels_getParticipants uReq = new TLRPC.TL_channels_getParticipants();
                uReq.limit = Math.max(100, mc.chatReadMarkSizeThreshold);
                uReq.offset = 0;
                uReq.filter = new TLRPC.TL_channelParticipantsRecent();
                uReq.channel = mc.getInputChannel(chat.id);
                reqIds[1] = ConnectionsManager.getInstance(currentAccount).sendRequest(uReq, (r1, e1) -> AndroidUtilities.runOnUIThread(() -> {
                    reqIds[1] = 0;
                    if (sheet.isDismissed() || act.isFinishing() || act.isDestroyed()) return;
                    if (r1 instanceof TLRPC.TL_channels_channelParticipants) {
                        TLRPC.TL_channels_channelParticipants cp = (TLRPC.TL_channels_channelParticipants) r1;
                        for (int i = 0; i < cp.users.size(); i++) {
                            TLRPC.User u = cp.users.get(i);
                            mc.putUser(u, false);
                            localMap.put(u.id, u);
                        }
                    }
                    renderList.run();
                }));
            } else {
                TLRPC.TL_messages_getFullChat uReq = new TLRPC.TL_messages_getFullChat();
                uReq.chat_id = chat.id;
                reqIds[1] = ConnectionsManager.getInstance(currentAccount).sendRequest(uReq, (r1, e1) -> AndroidUtilities.runOnUIThread(() -> {
                    reqIds[1] = 0;
                    if (sheet.isDismissed() || act.isFinishing() || act.isDestroyed()) return;
                    if (r1 instanceof TLRPC.TL_messages_chatFull) {
                        TLRPC.TL_messages_chatFull cf = (TLRPC.TL_messages_chatFull) r1;
                        for (int i = 0; i < cf.users.size(); i++) {
                            TLRPC.User u = cf.users.get(i);
                            mc.putUser(u, false);
                            localMap.put(u.id, u);
                        }
                    }
                    renderList.run();
                }));
            }
        }));
    }

    private static void renderEmpty(Context ctx, LinearLayout container, String msg, int color) {
        container.removeAllViews();
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(20), dp(14), dp(20));
        container.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static void addViewerRow(
            Activity act,
            BaseFragment parentFragment,
            BottomSheet sheet,
            int currentAccount,
            LinearLayout container,
            TLObject obj,
            long peerId,
            int readDate,
            int cTextPrimary,
            int cTextSecondary,
            int cBorder,
            boolean withDivider
    ) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), dp(12), dp(12)));

        BackupImageView avatar = new BackupImageView(act);
        avatar.setRoundRadius(dp(19));
        AvatarDrawable avDr = new AvatarDrawable();
        if (obj != null) {
            avDr.setInfo(currentAccount, obj);
            ImageLocation loc = ImageLocation.getForUserOrChat(currentAccount, obj, ImageLocation.TYPE_SMALL);
            avatar.setImage(loc, "50_50", avDr, obj);
        } else {
            avDr.setInfo(peerId, "ID " + peerId, "");
            avatar.setImageDrawable(avDr);
        }
        row.addView(avatar, LayoutHelper.createLinear(38, 38, 0, 0, 12, 0));

        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView nameTv = new TextView(act);
        nameTv.setText(obj != null ? ContactsController.formatName(obj) : ("Пользователь #" + peerId));
        nameTv.setTextColor(cTextPrimary);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        nameTv.setTypeface(AndroidUtilities.bold());
        nameTv.setMaxLines(1);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(nameTv);

        if (readDate > 0) {
            TextView dateTv = new TextView(act);
            dateTv.setText("✓✓ " + LocaleController.formatSeenDate(readDate));
            dateTv.setTextColor(cTextSecondary);
            dateTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            info.addView(dateTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            if (parentFragment == null || parentFragment.isFinishing() || parentFragment.getParentLayout() == null) return;
            sheet.dismiss();
            Bundle args = new Bundle();
            if (obj instanceof TLRPC.User) {
                args.putLong("user_id", ((TLRPC.User) obj).id);
            } else if (obj instanceof TLRPC.Chat) {
                args.putLong("chat_id", ((TLRPC.Chat) obj).id);
            } else if (peerId > 0) {
                args.putLong("user_id", peerId);
            } else {
                return;
            }
            parentFragment.presentFragment(new ProfileActivity(args));
        });

        container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (withDivider) {
            View div = new View(act);
            div.setBackgroundColor(cBorder);
            container.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 60, 0, 10, 0));
        }
    }
}
