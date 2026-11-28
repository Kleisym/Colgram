package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.colgram.core.ColgramBuildFlags;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MessagePrivateSeenView;

import java.util.ArrayList;

/**
 * Thin Aero modal wrapper over ColgramReadReceiptsHelper + native MessageSeenView.UserCell rows.
 * Used where Telegram provides no native swipeback popup (ActionMode eye icon & PhotoViewer menu),
 * guaranteeing identical eligibility, counts, viewers, badges, and DM error handling.
 */
public final class ColgramMessageSeenSheet {

    private ColgramMessageSeenSheet() {}

    public static boolean canCheckSeen(int currentAccount, MessageObject message, TLRPC.Chat chat, TLRPC.User user) {
        return ColgramReadReceiptsHelper.canShowReadReceipts(currentAccount, message, chat, user);
    }

    public static boolean canCheckSeenForMessage(int currentAccount, MessageObject message) {
        return ColgramReadReceiptsHelper.canShowReadReceiptsForMessage(currentAccount, message);
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
        if (act == null || act.isFinishing() || act.isDestroyed() || message == null
                || !canCheckSeen(currentAccount, message, chat, user)) {
            return;
        }
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        final boolean isDark = pal.dark;
        final int cTextPrimary = pal.textPrimary;
        final int cTextSecondary = pal.textSecondary;
        final int cCard = pal.cardSurface;
        final int cBorder = pal.borderSubtle;
        final int cAccent = pal.accentPrimary;

        final boolean isRound = message.isRoundVideo();
        final boolean isVoice = message.isVoice();
        final boolean isPlayedMedia = isRound || isVoice;

        final BottomSheet sheet = new BottomSheet(act, false, resourcesProvider);
        sheet.setApplyBottomPadding(false);

        LinearLayout root = ColgramUiKit.makeSheetRoot(act, pal);
        ColgramAeroStyler.applyFlagshipSheetChrome(sheet, root, pal);

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
        String peerName = chat != null ? chat.title : ContactsController.formatName(user);
        int reactTotal = countMessageReactions(message);
        if (reactTotal > 0) {
            peerName = peerName + " · " + LocaleController.formatPluralString("ReactionsCount", reactTotal);
        }
        subTv.setText(peerName);
        subTv.setTextColor(cTextSecondary);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        subTv.setMaxLines(1);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        header.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        final int[] reqIds = new int[]{0};

        if (chat == null) {
            showPrivateSheet(act, sheet, root, reqIds, currentAccount, message, cTextPrimary, cTextSecondary,
                    cCard, cBorder, cAccent, resourcesProvider);
            return;
        }

        final LinearLayout listContainer = new LinearLayout(act);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cCard);
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), cBorder);
        listContainer.setBackground(cardBg);

        addLoadingRow(act, listContainer, cTextSecondary, "Загружаю список просмотров…");

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

        presentModalSheet(sheet, root, currentAccount, reqIds);

        final int totalReactions = reactTotal;
        reqIds[0] = ColgramReadReceiptsHelper.loadGroupReadParticipants(currentAccount, message, chat, false, res -> {
            reqIds[0] = 0;
            if (sheet.isDismissed() || act.isFinishing() || act.isDestroyed()) return;
            if (res == null || res.errorText != null) {
                String msgText;
                if (res != null && res.errorText != null && res.errorText.contains("MESSAGE_ID_INVALID")) {
                    msgText = "Список просмотров недоступен для этого сообщения.";
                } else {
                    msgText = "Пока никто не посмотрел или список просмотров недоступен в этом чате.";
                }
                renderEmpty(act, listContainer, msgText, cTextSecondary);
                return;
            }
            ArrayList<ColgramReadReceiptsHelper.ViewerEntry> entries = res.viewers;
            if (entries.isEmpty()) {
                renderEmpty(act, listContainer,
                        LocaleController.getString(R.string.NobodyViewed),
                        cTextSecondary);
                String zeroSub = chat.title + " • " + LocaleController.formatPluralString(
                        isPlayedMedia ? "MessagePlayed" : "MessageSeen", 0);
                if (totalReactions > 0) {
                    zeroSub += " · " + LocaleController.formatPluralString("ReactionsCount", totalReactions);
                }
                subTv.setText(zeroSub);
                return;
            }

            String seenSummary = chat.title + " • " + LocaleController.formatPluralString(
                    isPlayedMedia ? "MessagePlayed" : "MessageSeen", entries.size());
            if (totalReactions > 0) {
                seenSummary += " · " + LocaleController.formatPluralString("ReactionsCount", totalReactions);
            }
            subTv.setText(seenSummary);

            listContainer.removeAllViews();
            for (int i = 0; i < entries.size(); i++) {
                ColgramReadReceiptsHelper.ViewerEntry entry = entries.get(i);
                MessageSeenView.UserCell row = new MessageSeenView.UserCell(act);
                row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), dp(12), dp(12)));
                row.setUser(entry.obj, entry.date, false);
                if (row.nameView != null) {
                    row.nameView.setTextColor(cTextPrimary);
                }
                if (row.readView != null) {
                    row.readView.setTextColor(cTextSecondary);
                }
                final long peerId = entry.peerId;
                row.setOnClickListener(v -> {
                    if (parentFragment == null || parentFragment.isFinishing() || parentFragment.getParentLayout() == null) {
                        return;
                    }
                    sheet.dismiss();
                    Bundle args = new Bundle();
                    if (peerId > 0) {
                        args.putLong("user_id", peerId);
                    } else if (peerId < 0) {
                        args.putLong("chat_id", -peerId);
                    } else {
                        return;
                    }
                    parentFragment.presentFragment(new ProfileActivity(args));
                });
                listContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
                if (i < entries.size() - 1) {
                    View div = new View(act);
                    div.setBackgroundColor(cBorder);
                    listContainer.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 56, 0, 10, 0));
                }
            }
        });
    }

    private static void showPrivateSheet(
            Activity act,
            BottomSheet sheet,
            LinearLayout root,
            int[] reqIds,
            int currentAccount,
            MessageObject message,
            int cTextPrimary,
            int cTextSecondary,
            int cCard,
            int cBorder,
            int cAccent,
            Theme.ResourcesProvider resourcesProvider) {
        final LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable cBg = new GradientDrawable();
        cBg.setColor(cCard);
        cBg.setCornerRadius(dp(16));
        cBg.setStroke(dp(1), cBorder);
        card.setBackground(cBg);

        final ProgressBar pb = new ProgressBar(act);
        pb.setIndeterminate(true);
        card.addView(pb, LayoutHelper.createLinear(20, 20, 0, 0, 10, 0));

        final TextView statusTv = new TextView(act);
        statusTv.setText("Загружаю статус прочтения…");
        statusTv.setTextColor(cTextSecondary);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        card.addView(statusTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final TextView badgeTv = new TextView(act);
        badgeTv.setVisibility(View.GONE);
        badgeTv.setTextColor(cAccent);
        badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        badgeTv.setTypeface(AndroidUtilities.bold());
        badgeTv.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(12));
        badgeBg.setColor(Theme.multAlpha(cAccent, 0.14f));
        badgeTv.setBackground(badgeBg);
        card.addView(badgeTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));

        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        presentModalSheet(sheet, root, currentAccount, reqIds);

        Runnable[] fetch = new Runnable[1];
        fetch[0] = () -> reqIds[0] = ColgramReadReceiptsHelper.loadPrivateReadDate(
                currentAccount,
                message.getDialogId(),
                message.getId(),
                false,
                res -> {
                    reqIds[0] = 0;
                    if (sheet.isDismissed() || act.isFinishing() || act.isDestroyed()) return;
                    pb.setVisibility(View.GONE);
                    String text = ColgramReadReceiptsHelper.formatPrivateStatusText(res);
                    statusTv.setText(text);
                    statusTv.setTextColor(res != null && res.status == ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_OK
                            ? cTextPrimary : cTextSecondary);
                    if (res != null && res.status == ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_YOUR_PRIVACY_RESTRICTED) {
                        badgeTv.setText(LocaleController.getString(R.string.PmReadShowWhen));
                        badgeTv.setVisibility(View.VISIBLE);
                        card.setOnClickListener(v -> MessagePrivateSeenView.showSheet(
                                act, currentAccount, message.getDialogId(), false,
                                sheet::dismiss,
                                () -> {
                                    ColgramReadReceiptsHelper.invalidateMessage(currentAccount, message.getDialogId(), message.getId());
                                    pb.setVisibility(View.VISIBLE);
                                    badgeTv.setVisibility(View.GONE);
                                    fetch[0].run();
                                },
                                resourcesProvider));
                    } else {
                        badgeTv.setVisibility(View.GONE);
                        card.setOnClickListener(null);
                        if (res != null && res.status == ColgramReadReceiptsHelper.PrivateSeenResult.STATUS_ERROR && res.rawError != null) {
                            BulletinFactory.of(Bulletin.BulletinWindow.make(act), resourcesProvider).showForError(res.rawError);
                        }
                    }
                });
        fetch[0].run();
    }

    private static void presentModalSheet(BottomSheet sheet, LinearLayout root, int currentAccount, int[] reqIds) {
        sheet.setCustomView(root);
        if (ColgramBuildFlags.KOLITI_ENABLED) {
            ColgramGlobalOrbController.pushModalSuppression();
        }
        sheet.setOnDismissListener(d -> {
            if (ColgramBuildFlags.KOLITI_ENABLED) {
                ColgramGlobalOrbController.popModalSuppression();
            }
            if (reqIds != null && reqIds[0] != 0) {
                ConnectionsManager.getInstance(currentAccount).cancelRequest(reqIds[0], true);
                reqIds[0] = 0;
            }
        });
        try {
            sheet.show();
        } catch (Throwable ignored) {
            if (ColgramBuildFlags.KOLITI_ENABLED) {
                ColgramGlobalOrbController.popModalSuppression();
            }
        }
    }

    private static int countMessageReactions(MessageObject message) {
        if (message == null || message.messageOwner == null
                || message.messageOwner.reactions == null
                || message.messageOwner.reactions.results == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < message.messageOwner.reactions.results.size(); i++) {
            TLRPC.ReactionCount rc = message.messageOwner.reactions.results.get(i);
            if (rc != null && rc.count > 0) {
                total += rc.count;
            }
        }
        return total;
    }

    private static void addLoadingRow(Context ctx, LinearLayout container, int color, String text) {
        LinearLayout loadingRow = new LinearLayout(ctx);
        loadingRow.setOrientation(LinearLayout.HORIZONTAL);
        loadingRow.setGravity(Gravity.CENTER);
        loadingRow.setPadding(dp(12), dp(18), dp(12), dp(18));
        ProgressBar pb = new ProgressBar(ctx);
        pb.setIndeterminate(true);
        loadingRow.addView(pb, LayoutHelper.createLinear(22, 22, 0, 0, 10, 0));
        TextView loadTv = new TextView(ctx);
        loadTv.setText(text);
        loadTv.setTextColor(color);
        loadTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        loadingRow.addView(loadTv);
        container.addView(loadingRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
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
}
