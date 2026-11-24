package org.telegram.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.colgram.core.ColgramAiAgentTools;
import org.colgram.core.ColgramAiChatStore;
import org.colgram.core.ColgramAiConfig;
import org.colgram.core.ColgramAiContextEngine;
import org.colgram.core.ColgramAiEngine;
import org.colgram.core.ColgramAiModelManager;
import org.colgram.core.ColgramAiModels;
import org.colgram.core.ColgramAiWebSearch;
import org.colgram.core.ColgramKolitiVoice;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flagship Adaptive Chat Sheet for Koliti AI:
 * - Unified Light / Dark / Cyber palette via ColgramAeroStyler.Palette
 * - Scroll-proof viewport + keyboard-safe onMeasure + IME_ACTION_SEND
 * - Animated TypingWaveView during inference + async off-thread media thumbnails
 * - Borderless horizontally scrollable action pills (zero narrow-screen clipping)
 */
public final class ColgramAiSheet {

    private static final ExecutorService MEDIA_THUMB_EXEC = Executors.newFixedThreadPool(2);
    private static final int ACCENT_CRIMSON = 0xFFE11D48;
    private static final int ACCENT_CORAL = 0xFFF43F5E;
    private static final int ACCENT_AMBER = 0xFFF59E0B;
    private static final int ACCENT_EMERALD = 0xFF10B981;

    private final ColgramAeroStyler.Palette pal;
    private final boolean isDark;
    private final int cBgTop;
    private final int cBgBottom;
    private final int cCard;
    private final int cCardElevated;
    private final int cInputBg;
    private final int cChipBg;
    private final int cBorder;
    private final int cTextPrimary;
    private final int cTextSecondary;
    private final int cCodeBg;
    private final int cCodeFg;
    private final int cMathBg;
    private final int cMathFg;
    private final int cIconMuted;
    private final int cAccent;

    private final BaseFragment parentFragment;
    private final ChatActivity chatActivity;
    private final ColgramAiOrbView orbView;
    private final BottomSheet sheet;

    private final LinearLayout messagesContainer;
    private final ScrollView scrollView;
    private final EditText promptInput;
    private final TextView sessionTitleView;
    private final TextView statusLine;
    private final TextView contextPill;
    private final TextView voiceOverPill;
    private final TextView infoPill;
    private String lastFooterTelemetry = "";
    private final View liveDot;
    private final HorizontalScrollView attachmentStripScroll;
    private final LinearLayout attachmentStripContainer;
    private final ImageView micButton;
    private final ImageView sendButton;
    private final LinearLayout coreInstallBanner;
    private final TextView coreInstallText;
    private final TextView coreInstallAction;
    private final ProgressBar coreInstallProgress;

    private ValueAnimator dotPulseAnimator;
    private ValueAnimator thinkingPulseAnimator;
    private boolean busyGenerating = false;
    private long activeRequestSeq = 0L;
    private boolean allowHandleSwipeDismiss = false;
    private boolean attachActiveChatContext;
    private final List<String> attachedPaths = new ArrayList<>();
    private ColgramAiChatStore.ChatSession activeSession;
    private View emptyStateView;
    private SpeechRecognizer speechRecognizer;
    private boolean listeningSpeech = false;
    private boolean recordingVoiceNote = false;
    private long voiceRecordStartMs = 0L;
    private String detectedUserLang = "ru";
    private final java.util.concurrent.atomic.AtomicBoolean cleanedUp = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Runnable pendingWelcomeRunnable;
    private boolean embeddedUnpackRequested = false;
    private final ColgramAiModelManager.DownloadObserver downloadObserver = state -> AndroidUtilities.runOnUIThread(() -> {
        if (cleanedUp.get()) return;
        Activity act = resolveValidActivity(parentFragment);
        if (act != null) {
            syncCoreInstallBanner(act);
        }
    });

    private static Activity resolveValidActivity(BaseFragment parentFragment) {
        Activity act = (parentFragment != null && parentFragment.getParentActivity() != null)
                ? parentFragment.getParentActivity()
                : LaunchActivity.instance;
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return null;
        }
        return act;
    }

    public static ColgramAiSheet show(BaseFragment parentFragment, ChatActivity chatActivity, ColgramAiOrbView orbView) {
        if (resolveValidActivity(parentFragment) == null) return null;
        return new ColgramAiSheet(parentFragment, chatActivity, orbView, null);
    }

    public static ColgramAiSheet showWithQuickPrompt(
            BaseFragment parentFragment,
            ChatActivity chatActivity,
            ColgramAiOrbView orbView,
            String autoPrompt
    ) {
        if (resolveValidActivity(parentFragment) == null) return null;
        return new ColgramAiSheet(parentFragment, chatActivity, orbView, autoPrompt);
    }

    private ColgramAiSheet(
            BaseFragment parentFragment,
            ChatActivity chatActivity,
            ColgramAiOrbView orbView,
            String autoPrompt
    ) {
        this.parentFragment = parentFragment;
        this.chatActivity = chatActivity != null ? chatActivity : ColgramAiChatBridge.resolveActiveChat(parentFragment);
        this.orbView = orbView;

        Activity act = resolveValidActivity(parentFragment);
        if (act == null) {
            throw new IllegalStateException("Parent activity required");
        }

        this.pal = ColgramAeroStyler.Palette.resolve();
        this.isDark = pal.dark;
        this.cAccent = pal.accentPrimary;
        this.cBgTop = pal.sheetBgTop;
        this.cBgBottom = pal.sheetBgBottom;
        this.cCard = pal.cardSurface;
        this.cCardElevated = pal.cardElevated;
        this.cInputBg = pal.inputSurface;
        this.cChipBg = pal.chipSurface;
        this.cBorder = pal.borderSubtle;
        this.cTextPrimary = pal.textPrimary;
        this.cTextSecondary = pal.textSecondary;
        if (isDark) {
            cCodeBg = pal.cyber ? 0xFF0A0B0F : 0xFF0D111A;
            cCodeFg = 0xFFFDA4AF;
            cMathBg = 0x2438BDF8;
            cMathFg = 0xFFF1F5F9;
            cIconMuted = 0xFFCBD5E1;
        } else {
            cCodeBg = 0xFFEDF2F7;
            cCodeFg = 0xFFBE123C;
            cMathBg = 0x16E11D48;
            cMathFg = 0xFF0F172A;
            cIconMuted = 0xFF475569;
        }

        this.sheet = new BottomSheet(act, true) {
            @Override
            protected boolean canDismissWithSwipe() {
                return allowHandleSwipeDismiss;
            }
        };

        LinearLayout root = new LinearLayout(act) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int screenH = AndroidUtilities.displaySize.y > 0 ? AndroidUtilities.displaySize.y : dp(720);
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int availH = mode != MeasureSpec.UNSPECIFIED ? MeasureSpec.getSize(heightMeasureSpec) : 0;
                int preferredH = Math.max(dp(320), (int) (screenH * 0.84f));
                int targetH = availH > 0 ? Math.min(availH, preferredH) : preferredH;
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.max(dp(120), targetH), MeasureSpec.EXACTLY));
            }
        };
        final boolean compactPortrait = AndroidUtilities.displaySize.x > 0 && AndroidUtilities.displaySize.x <= dp(395);
        final int hPadDp = compactPortrait ? 12 : 16;
        final int hdrBtnDp = compactPortrait ? 31 : 34;
        final int hdrGapDp = compactPortrait ? 4 : 6;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(hPadDp), dp(6), dp(hPadDp), dp(12) + AndroidUtilities.navigationBarHeight);
        ColgramAeroStyler.applyFlagshipSheetChrome(sheet, root, pal);

        // Top drag handle (ONLY swipe-to-dismiss surface)
        FrameLayout handleZone = new FrameLayout(act);
        handleZone.setPadding(0, dp(4), 0, dp(10));
        handleZone.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) allowHandleSwipeDismiss = true;
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) allowHandleSwipeDismiss = false;
            return false;
        });
        View handle = new View(act);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(isDark ? 0x38FFFFFF : 0x260F172A);
        handleBg.setCornerRadius(dp(99));
        handle.setBackground(handleBg);
        handleZone.addView(handle, LayoutHelper.createFrame(40, 4.5f, Gravity.CENTER));
        root.addView(handleZone, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Sleek minimal Header
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        FrameLayout avatarWrap = new FrameLayout(act);
        GradientDrawable avatarRing = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT_CRIMSON, ACCENT_AMBER}
        );
        avatarRing.setShape(GradientDrawable.OVAL);
        avatarWrap.setBackground(avatarRing);
        avatarWrap.setPadding(dp(2), dp(2), dp(2), dp(2));

        ImageView kolitiAvatar = new ImageView(act);
        ColgramAeroStyler.applyKolitiAvatar(kolitiAvatar);
        kolitiAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarWrap.addView(kolitiAvatar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        liveDot = new View(act);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(ACCENT_EMERALD);
        dotBg.setStroke(dp(1.5f), cBgTop);
        liveDot.setBackground(dotBg);
        avatarWrap.addView(liveDot, LayoutHelper.createFrame(11, 11, Gravity.BOTTOM | Gravity.END));
        avatarWrap.setOnClickListener(ColgramEasterEggs::onHeroBadgeTap);
        header.addView(avatarWrap, LayoutHelper.createLinear(40, 40, 0, 0, 10, 0));

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setOnClickListener(v -> showChatHistorySheet(act));
        header.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        sessionTitleView = new TextView(act);
        sessionTitleView.setText(org.colgram.core.ColgramDesignConfig.getKolitiDisplayName());
        sessionTitleView.setTextColor(cTextPrimary);
        sessionTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f);
        sessionTitleView.setTypeface(AndroidUtilities.bold());
        sessionTitleView.setMaxLines(1);
        sessionTitleView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(sessionTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusLine = new TextView(act);
        statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
        statusLine.setTextColor(cTextSecondary);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        statusLine.setMaxLines(1);
        statusLine.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(statusLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        ImageView historyBtn = makeIconBtn(act, R.drawable.msg_list, cChipBg, cIconMuted);
        historyBtn.setContentDescription("История чатов Koliti");
        historyBtn.setOnClickListener(v -> showChatHistorySheet(act));
        header.addView(historyBtn, LayoutHelper.createLinear(hdrBtnDp, hdrBtnDp, hdrGapDp, 0, 0, 0));

        ImageView newChatBtn = makeIconBtn(act, R.drawable.msg_add, isDark ? 0xFF2D1422 : 0xFFFFE4E6, ACCENT_CRIMSON);
        newChatBtn.setContentDescription("Новый чат");
        newChatBtn.setOnClickListener(v -> startNewChatSession(act));
        header.addView(newChatBtn, LayoutHelper.createLinear(hdrBtnDp, hdrBtnDp, hdrGapDp, 0, 0, 0));

        ImageView studioBtn = makeIconBtn(act, R.drawable.msg_settings, cChipBg, cIconMuted);
        studioBtn.setContentDescription("Koliti Studio");
        studioBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (parentFragment != null) {
                parentFragment.presentFragment(new ColgramAiActivity());
            }
        });
        header.addView(studioBtn, LayoutHelper.createLinear(hdrBtnDp, hdrBtnDp, hdrGapDp, 0, 0, 0));

        ImageView closeBtn = makeIconBtn(act, R.drawable.msg_close, cChipBg, cIconMuted);
        closeBtn.setContentDescription("Закрыть");
        closeBtn.setOnClickListener(v -> sheet.dismiss());
        header.addView(closeBtn, LayoutHelper.createLinear(hdrBtnDp, hdrBtnDp, hdrGapDp, 0, 0, 0));

        // Interactive Context Scope & Voice-Over Bar (horizontally scrollable so pills never clip)
        HorizontalScrollView metaScroll = new HorizontalScrollView(act);
        metaScroll.setHorizontalScrollBarEnabled(false);
        metaScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout metaStrip = new LinearLayout(act);
        metaStrip.setOrientation(LinearLayout.HORIZONTAL);
        metaStrip.setGravity(Gravity.CENTER_VERTICAL);
        metaScroll.addView(metaStrip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(metaScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        contextPill = new TextView(act);
        contextPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        contextPill.setTypeface(AndroidUtilities.bold());
        contextPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        contextPill.setMaxLines(1);
        contextPill.setEllipsize(TextUtils.TruncateAt.END);
        ColgramMenuChrome.attachSpringPress(contextPill);
        contextPill.setOnClickListener(v -> {
            if (this.chatActivity == null) {
                Toast.makeText(act, "Откройте любой чат Telegram, чтобы подключить его контекст", Toast.LENGTH_SHORT).show();
                return;
            }
            attachActiveChatContext = !attachActiveChatContext;
            updateContextPillUi();
            Toast.makeText(act,
                    attachActiveChatContext ? "Контекст чата подключён" : "Свободный режим (без контекста чата)",
                    Toast.LENGTH_SHORT).show();
        });
        metaStrip.addView(contextPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 7, 0));

        voiceOverPill = new TextView(act);
        voiceOverPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        voiceOverPill.setTypeface(AndroidUtilities.bold());
        voiceOverPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        voiceOverPill.setMaxLines(1);
        ColgramMenuChrome.attachSpringPress(voiceOverPill);
        voiceOverPill.setOnClickListener(v -> {
            if (ColgramKolitiVoice.isSpeakingNow()) {
                ColgramKolitiVoice.stopSpeaking();
                updateVoiceOverPillUi(act);
                Toast.makeText(act, "⏹ Озвучка остановлена", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean next = !ColgramAiConfig.isVoiceOverEnabled(act);
            ColgramAiConfig.setVoiceOverEnabled(act, next);
            if (!next) {
                ColgramKolitiVoice.stopSpeaking();
            }
            updateVoiceOverPillUi(act);
            Toast.makeText(act,
                    next ? ("🔊 Озвучка включена (" + ColgramKolitiVoice.getVoiceEngineSummary(act) + ")")
                            : "🔇 Озвучка ответов отключена",
                    Toast.LENGTH_SHORT).show();
        });
        voiceOverPill.setOnLongClickListener(v -> {
            sheet.dismiss();
            if (parentFragment != null) {
                parentFragment.presentFragment(new ColgramAiActivity());
            }
            return true;
        });
        metaStrip.addView(voiceOverPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 7, 0));

        infoPill = new TextView(act);
        infoPill.setText("ⓘ Инфо");
        infoPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        infoPill.setTypeface(AndroidUtilities.bold());
        infoPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        infoPill.setMaxLines(1);
        infoPill.setTextColor(isDark ? 0xFF93C5FD : 0xFF1D4ED8);
        GradientDrawable infoPillBg = new GradientDrawable();
        infoPillBg.setCornerRadius(dp(999));
        infoPillBg.setColor(isDark ? 0x223B82F6 : 0x163B82F6);
        infoPillBg.setStroke(dp(1), isDark ? 0x553B82F6 : 0x443B82F6);
        infoPill.setBackground(infoPillBg);
        ColgramMenuChrome.attachSpringPress(infoPill);
        infoPill.setOnClickListener(v -> showKolitiInfoSheet(act, lastFooterTelemetry));
        metaStrip.addView(infoPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // Subtle divider below header
        View divider = new View(act);
        divider.setBackgroundColor(cBorder);
        root.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 6));

        // Optional core banner (hidden when embedded assets are bundled/ready)
        coreInstallBanner = new LinearLayout(act);
        coreInstallBanner.setOrientation(LinearLayout.VERTICAL);
        coreInstallBanner.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable installBg = new GradientDrawable();
        installBg.setColor(cCard);
        installBg.setCornerRadius(dp(16));
        installBg.setStroke(dp(1), 0x44E11D48);
        coreInstallBanner.setBackground(installBg);

        LinearLayout installTop = new LinearLayout(act);
        installTop.setOrientation(LinearLayout.HORIZONTAL);
        installTop.setGravity(Gravity.CENTER_VERTICAL);
        coreInstallBanner.addView(installTop, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        coreInstallText = new TextView(act);
        coreInstallText.setText("Подготовка ядра Koliti…");
        coreInstallText.setTextColor(cTextPrimary);
        coreInstallText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        coreInstallText.setMaxLines(1);
        coreInstallText.setEllipsize(TextUtils.TruncateAt.END);
        installTop.addView(coreInstallText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

        coreInstallAction = new TextView(act);
        coreInstallAction.setText("Установить");
        coreInstallAction.setTextColor(Color.WHITE);
        coreInstallAction.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        coreInstallAction.setTypeface(AndroidUtilities.bold());
        coreInstallAction.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable actBg = new GradientDrawable();
        actBg.setColor(ACCENT_CRIMSON);
        actBg.setCornerRadius(dp(999));
        coreInstallAction.setBackground(actBg);
        ColgramMenuChrome.attachSpringPress(coreInstallAction);
        coreInstallAction.setOnClickListener(v -> triggerCoreDownload(act));
        installTop.addView(coreInstallAction, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        coreInstallProgress = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        coreInstallProgress.setMax(100);
        coreInstallProgress.setVisibility(View.GONE);
        coreInstallProgress.getProgressDrawable().setColorFilter(ACCENT_CRIMSON, PorterDuff.Mode.SRC_IN);
        coreInstallBanner.addView(coreInstallProgress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4, 0, 8, 0, 0));

        syncCoreInstallBanner(act);
        ColgramAiModelManager.addDownloadObserver(downloadObserver);
        root.addView(coreInstallBanner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        // Responsive, Scroll-Locked Conversation Viewport (flexes cleanly with keyboard & attachment preview strip)
        scrollView = new ScrollView(act) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                allowHandleSwipeDismiss = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                allowHandleSwipeDismiss = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return super.onTouchEvent(ev);
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        messagesContainer = new LinearLayout(act);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(0, dp(4), 0, dp(10));
        scrollView.addView(messagesContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f);
        scrollLp.bottomMargin = dp(8);
        root.addView(scrollView, scrollLp);

        // Initialize STRICTLY ISOLATED session for this dialog (or independent session if opened from chat list)
        ColgramAiChatStore.init(act.getFilesDir());
        final int initAccount = this.chatActivity != null ? this.chatActivity.getCurrentAccount() : UserConfig.selectedAccount;
        detectedUserLang = ColgramAiChatBridge.detectUserLanguage(initAccount, this.chatActivity);
        long openDialogId = this.chatActivity != null ? this.chatActivity.getDialogId() : 0L;
        String openChatTitle = this.chatActivity != null ? ColgramAiChatBridge.resolveChatTitle(this.chatActivity) : "";
        activeSession = ColgramAiChatStore.getOrCreateSessionForDialog(openDialogId, openChatTitle);
        attachActiveChatContext = (openDialogId != 0L && activeSession.boundDialogId == openDialogId);
        boolean shouldWelcomeAudio = activeSession != null && activeSession.messages.isEmpty()
                && (autoPrompt == null || autoPrompt.trim().isEmpty());
        updateHeaderForSession();
        updateContextPillUi();
        updateVoiceOverPillUi(act);
        renderActiveSessionMessages();

        // Visual Attachment Preview Strip (rounded thumbnails + remove badge + add button, never clips composer)
        attachmentStripScroll = new HorizontalScrollView(act);
        attachmentStripScroll.setHorizontalScrollBarEnabled(false);
        attachmentStripScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        attachmentStripScroll.setVisibility(View.GONE);
        GradientDrawable attStripBg = new GradientDrawable();
        attStripBg.setColor(cCard);
        attStripBg.setCornerRadius(dp(16));
        attStripBg.setStroke(dp(1), cBorder);
        attachmentStripScroll.setBackground(attStripBg);
        attachmentStripScroll.setPadding(dp(8), dp(6), dp(8), dp(6));

        attachmentStripContainer = new LinearLayout(act);
        attachmentStripContainer.setOrientation(LinearLayout.HORIZONTAL);
        attachmentStripContainer.setGravity(Gravity.CENTER_VERTICAL);
        attachmentStripScroll.addView(attachmentStripContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(attachmentStripScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // Sleek floating pill composer bar
        LinearLayout inputRow = new LinearLayout(act);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(6), dp(5), dp(6), dp(5));
        GradientDrawable composerBg = new GradientDrawable();
        composerBg.setColor(cInputBg);
        composerBg.setCornerRadius(dp(26));
        composerBg.setStroke(dp(1), cBorder);
        inputRow.setBackground(composerBg);
        inputRow.setElevation(isDark ? 0f : dp(2));
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView mediaBtn = makeIconBtn(act, R.drawable.msg_media, Color.TRANSPARENT, cIconMuted);
        mediaBtn.setContentDescription("Прикрепить фото или видео");
        mediaBtn.setOnClickListener(v -> attachRecentMediaOrPick());
        inputRow.addView(mediaBtn, LayoutHelper.createLinear(38, 38, 0, 0, 4, 0));

        promptInput = new EditText(act);
        promptInput.setHint("Спросите Koliti о чём угодно…");
        promptInput.setHintTextColor(cTextSecondary);
        promptInput.setTextColor(cTextPrimary);
        promptInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        promptInput.setPadding(dp(6), dp(8), dp(6), dp(8));
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        promptInput.setMaxLines(5);
        promptInput.setBackground(null);
        promptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (!busyGenerating && !ColgramAiEngine.getInstance().isBusy()) {
                    String q = promptInput.getText().toString().trim();
                    if (!q.isEmpty() || !attachedPaths.isEmpty()) {
                        promptInput.setText("");
                        submitPrompt(q.isEmpty() ? "Опиши прикреплённое фото или видео." : q);
                    }
                }
                return true;
            }
            return false;
        });
        inputRow.addView(promptInput, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 4, 0));

        micButton = makeIconBtn(act, R.drawable.input_mic, cChipBg, cIconMuted);
        micButton.setContentDescription("Голосовой ввод для Koliti");
        micButton.setOnClickListener(v -> toggleVoiceInput(act));
        micButton.setOnLongClickListener(v -> {
            toggleDirectVoiceRecording(act);
            return true;
        });
        inputRow.addView(micButton, LayoutHelper.createLinear(38, 38, 0, 0, 5, 0));

        sendButton = makeIconBtn(act, R.drawable.msg_send, cAccent, Color.WHITE);
        sendButton.setContentDescription("Отправить");
        sendButton.setOnClickListener(v -> {
            if (busyGenerating || ColgramAiEngine.getInstance().isBusy()) {
                statusLine.setText("Останавливаю…");
                ColgramAiEngine.getInstance().cancelCurrent();
                setBusyUi(false);
                setOrbState(ColgramAiOrbView.State.IDLE);
                return;
            }
            if (recordingVoiceNote) {
                toggleDirectVoiceRecording(act);
                return;
            }
            String q = promptInput.getText().toString().trim();
            if (!q.isEmpty() || !attachedPaths.isEmpty()) {
                promptInput.setText("");
                submitPrompt(q.isEmpty() ? "Опиши прикреплённое фото или видео." : q);
            }
        });
        inputRow.addView(sendButton, LayoutHelper.createLinear(38, 38));

        ColgramKolitiVoice.setSpeakingStateListener((active, label) -> AndroidUtilities.runOnUIThread(() -> {
            updateVoiceOverPillUi(act);
            if (!busyGenerating) {
                if (active && label != null && !label.isEmpty()) {
                    statusLine.setText("🔊 " + label);
                } else {
                    statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
                }
            }
        }));
        startDotPulse();
        sheet.setCustomView(root);
        final Runnable cleanupAction = () -> {
            if (cleanedUp.compareAndSet(false, true)) {
                ColgramAiModelManager.removeDownloadObserver(downloadObserver);
                if (pendingWelcomeRunnable != null) {
                    root.removeCallbacks(pendingWelcomeRunnable);
                    pendingWelcomeRunnable = null;
                }
                if (busyGenerating) {
                    ColgramAiEngine.getInstance().cancelCurrent();
                    busyGenerating = false;
                }
                if (ColgramAiActivity.pendingSheetForMedia == ColgramAiSheet.this) {
                    ColgramAiActivity.pendingSheetForMedia = null;
                }
                if (ColgramAiActivity.pendingSheetForVoice == ColgramAiSheet.this) {
                    ColgramAiActivity.pendingSheetForVoice = null;
                }
                ColgramGlobalOrbController.popModalSuppression();
                ColgramKolitiVoice.setSpeakingStateListener(null);
                stopDotPulse();
                stopThinkingPulse();
                stopVoiceRecognition();
                ColgramKolitiVoice.stopAll();
                setOrbState(ColgramAiOrbView.State.IDLE);
            }
        };
        final android.content.DialogInterface.OnDismissListener dismissListener = d -> cleanupAction.run();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                cleanupAction.run();
            }
        });
        ColgramGlobalOrbController.pushModalSuppression();
        sheet.setOnDismissListener(dismissListener);
        try {
            if (parentFragment != null) {
                android.app.Dialog shown = parentFragment.showDialog(sheet, dismissListener);
                if (shown == null) {
                    if (!act.isFinishing() && !act.isDestroyed()) {
                        sheet.setOnDismissListener(dismissListener);
                        sheet.show();
                    } else {
                        cleanupAction.run();
                        return;
                    }
                }
            } else {
                sheet.show();
            }
        } catch (Throwable showErr) {
            cleanupAction.run();
            return;
        }

        if (autoPrompt != null && !autoPrompt.trim().isEmpty()) {
            root.post(() -> {
                if (!cleanedUp.get() && sheet.isShowing()) {
                    submitPrompt(autoPrompt.trim());
                }
            });
        } else {
            // Silently warm text core while the user reads/types so first reply is immediate
            ColgramAiEngine.getInstance().warmUpAsync(root.getContext());
            if (shouldWelcomeAudio) {
                pendingWelcomeRunnable = () -> {
                    pendingWelcomeRunnable = null;
                    if (!cleanedUp.get() && sheet.isShowing()) {
                        maybeSendWelcomeGreeting(act, true);
                    }
                };
                root.postDelayed(pendingWelcomeRunnable, 260L);
            }
        }
    }

    private void updateHeaderForSession() {
        if (activeSession == null || "Новый чат".equals(activeSession.title) || activeSession.messages.isEmpty()) {
            sessionTitleView.setText(org.colgram.core.ColgramDesignConfig.getKolitiDisplayName());
        } else {
            sessionTitleView.setText(shorten(activeSession.title, 26));
        }
    }

    private void updateContextPillUi() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        if (this.chatActivity != null && attachActiveChatContext) {
            String t = shorten(ColgramAiChatBridge.resolveChatTitle(this.chatActivity), 22);
            contextPill.setText("● Контекст: " + t + "  ×");
            contextPill.setTextColor(isDark ? 0xFFFDA4AF : 0xFFBE123C);
            bg.setColor(isDark ? 0x22E11D48 : 0x16E11D48);
            bg.setStroke(dp(1), 0x4CE11D48);
        } else if (this.chatActivity != null) {
            contextPill.setText("○ Свободный чат • подключить «" + shorten(ColgramAiChatBridge.resolveChatTitle(this.chatActivity), 16) + "»");
            contextPill.setTextColor(cTextSecondary);
            bg.setColor(cChipBg);
            bg.setStroke(dp(1), cBorder);
        } else {
            contextPill.setText("○ Свободный чат (изолированный контекст)");
            contextPill.setTextColor(cTextSecondary);
            bg.setColor(cChipBg);
            bg.setStroke(dp(1), cBorder);
        }
        contextPill.setBackground(bg);
    }

    private void startNewChatSession(Activity act) {
        if (busyGenerating) {
            ColgramAiEngine.getInstance().cancelCurrent();
            setBusyUi(false);
        }
        // New chat is 100% clean & isolated from previous Koliti chats and unrelated Telegram chats!
        activeSession = ColgramAiChatStore.createNewSession("Новый чат", 0L, "");
        attachActiveChatContext = false;
        ColgramAiEngine.getInstance().clearHistory();
        updateHeaderForSession();
        updateContextPillUi();
        updateVoiceOverPillUi(act);
        renderActiveSessionMessages();
        int acct = chatActivity != null ? chatActivity.getCurrentAccount() : UserConfig.selectedAccount;
        detectedUserLang = ColgramAiChatBridge.detectUserLanguage(acct, chatActivity);
        maybeSendWelcomeGreeting(act, true);
        statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
        Toast.makeText(act, "Новый изолированный чат создан", Toast.LENGTH_SHORT).show();
    }

    private void renderActiveSessionMessages() {
        messagesContainer.removeAllViews();
        emptyStateView = null;
        if (activeSession == null || activeSession.messages.isEmpty()) {
            ColgramAiEngine.getInstance().clearHistory();
            addHeroEmptyState();
            return;
        }
        ColgramAiEngine.getInstance().setHistory(ColgramAiChatStore.toConversationTurns(activeSession, 10));
        for (int i = 0; i < activeSession.messages.size(); i++) {
            ColgramAiChatStore.ChatMessage m = activeSession.messages.get(i);
            String audioTag = findKolitiAudioTag(m.mediaPaths);
            if ("user".equals(m.role)) {
                if (audioTag != null && audioTag.startsWith(ColgramKolitiVoice.MEDIA_PREFIX_AUDIO_FILE)) {
                    String path = audioTag.substring(ColgramKolitiVoice.MEDIA_PREFIX_AUDIO_FILE.length());
                    addKolitiAudioBubble(true, m.text, "Голосовое сообщение", audioTag, 0, path, false);
                } else {
                    addUserBubble(m.text, m.mediaPaths);
                }
            } else if (audioTag != null) {
                renderTaggedAssistantAudioBubble(m.text, m.footer, audioTag, false);
            } else {
                String prevUser = (i > 0 && "user".equals(activeSession.messages.get(i - 1).role))
                        ? activeSession.messages.get(i - 1).text : null;
                addAssistantBubble(m.text, m.footer, true, false, prevUser);
            }
        }
        scrollToBottomSmooth();
    }

    private void addHeroEmptyState() {
        Context ctx = messagesContainer.getContext();
        LinearLayout hero = new LinearLayout(ctx);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(8), dp(26), dp(8), dp(12));

        FrameLayout orbWrap = new FrameLayout(ctx);
        GradientDrawable ring = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT_CRIMSON, ACCENT_AMBER}
        );
        ring.setShape(GradientDrawable.OVAL);
        orbWrap.setBackground(ring);
        orbWrap.setPadding(dp(3), dp(3), dp(3), dp(3));
        ImageView ava = new ImageView(ctx);
        ColgramAeroStyler.applyKolitiAvatar(ava);
        ava.setScaleType(ImageView.ScaleType.CENTER_CROP);
        orbWrap.addView(ava, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        hero.addView(orbWrap, LayoutHelper.createLinear(64, 64, 0, 0, 0, 14));

        TextView heading = new TextView(ctx);
        heading.setText("Чем я могу помочь?");
        heading.setTextColor(cTextPrimary);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21f);
        heading.setTypeface(AndroidUtilities.bold());
        heading.setGravity(Gravity.CENTER);
        hero.addView(heading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        TextView sub = new TextView(ctx);
        sub.setText(chatActivity != null && attachActiveChatContext
                ? "Подключён контекст «" + shorten(ColgramAiChatBridge.resolveChatTitle(chatActivity), 24) + "»"
                : "Спроси о чём угодно — код, математика, анализ чата, фото и поиск");
        sub.setTextColor(cTextSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        sub.setGravity(Gravity.CENTER);
        hero.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        emptyStateView = hero;
        messagesContainer.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void showChatHistorySheet(Activity act) {
        final List<ColgramAiChatStore.ChatSession> all = ColgramAiChatStore.listSessions();
        BottomSheet histSheet = new BottomSheet(act, false);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(16) + AndroidUtilities.navigationBarHeight);
        ColgramAeroStyler.applyFlagshipSheetChrome(histSheet, box, pal);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        TextView title = new TextView(act);
        title.setText("Чаты Koliti (" + all.size() + ")");
        title.setTextColor(cTextPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        title.setTypeface(AndroidUtilities.bold());
        top.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView newBtn = new TextView(act);
        newBtn.setText("+ Новый чат");
        newBtn.setTextColor(Color.WHITE);
        newBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        newBtn.setTypeface(AndroidUtilities.bold());
        newBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable nbBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT_CRIMSON, ACCENT_CORAL});
        nbBg.setCornerRadius(dp(999));
        newBtn.setBackground(nbBg);
        ColgramMenuChrome.attachSpringPress(newBtn);
        newBtn.setOnClickListener(v -> {
            histSheet.dismiss();
            startNewChatSession(act);
        });
        top.addView(newBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        ScrollView listScroll = new ScrollView(act);
        LinearLayout listCol = new LinearLayout(act);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listCol, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());
        for (ColgramAiChatStore.ChatSession s : all) {
            boolean isCurrent = activeSession != null && s.id.equals(activeSession.id);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(13), dp(11), dp(10), dp(11));
            GradientDrawable rBg = new GradientDrawable();
            rBg.setColor(isCurrent ? (isDark ? 0x24E11D48 : 0x14E11D48) : cCard);
            rBg.setCornerRadius(dp(15));
            rBg.setStroke(dp(1), isCurrent ? 0x66E11D48 : cBorder);
            row.setBackground(rBg);

            LinearLayout info = new LinearLayout(act);
            info.setOrientation(LinearLayout.VERTICAL);
            row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            TextView t = new TextView(act);
            t.setText(shorten(s.title, 34));
            t.setTextColor(isCurrent ? ACCENT_CRIMSON : cTextPrimary);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            t.setTypeface(AndroidUtilities.bold());
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(t);

            String scopeTag = s.boundDialogId != 0L && !s.boundChatTitle.isEmpty()
                    ? "Контекст: " + shorten(s.boundChatTitle, 18) + " • "
                    : "Свободный • ";
            TextView meta = new TextView(act);
            meta.setText(scopeTag + s.messages.size() + " соо • " + fmt.format(new Date(s.updatedAtMs > 0 ? s.updatedAtMs : s.createdAtMs)));
            meta.setTextColor(cTextSecondary);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            info.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            ImageView del = makeIconBtn(act, R.drawable.msg_delete, Color.TRANSPARENT, cTextSecondary);
            del.setOnClickListener(v -> {
                ColgramAiChatStore.deleteSession(s.id);
                if (activeSession != null && activeSession.id.equals(s.id)) {
                    activeSession = ColgramAiChatStore.getOrCreateActiveSession();
                    attachActiveChatContext = (chatActivity != null && activeSession.boundDialogId == chatActivity.getDialogId());
                    updateHeaderForSession();
                    updateContextPillUi();
                    renderActiveSessionMessages();
                }
                histSheet.dismiss();
                showChatHistorySheet(act);
            });
            row.addView(del, LayoutHelper.createLinear(34, 34));

            ColgramMenuChrome.attachSpringPress(row);
            row.setOnClickListener(v -> {
                if (busyGenerating) {
                    ColgramAiEngine.getInstance().cancelCurrent();
                    setBusyUi(false);
                }
                activeSession = ColgramAiChatStore.switchSession(s.id);
                attachActiveChatContext = (chatActivity != null && activeSession.boundDialogId != 0L
                        && activeSession.boundDialogId == chatActivity.getDialogId());
                updateHeaderForSession();
                updateContextPillUi();
                renderActiveSessionMessages();
                histSheet.dismiss();
            });
            listCol.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }

        int maxH = Math.min(dp(420), (int) (AndroidUtilities.displaySize.y * 0.55f));
        box.addView(listScroll, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, Math.min(maxH, Math.max(dp(120), all.size() * dp(74)))));
        histSheet.setCustomView(box);
        histSheet.show();
    }

    private void submitPrompt(String query) {
        submitPromptInternal(query, true);
    }

    private void submitPromptInternal(String query, boolean renderUserBubble) {
        submitPromptWithCard(query, renderUserBubble, null, null, null, null, null);
    }

    private void submitPromptWithCard(
            String query,
            boolean renderUserBubble,
            View preCreatedRow,
            TextView preCreatedAns,
            TextView preCreatedFoot,
            LinearLayout preCreatedActions,
            View preCreatedWave
    ) {
        Activity act = parentFragment != null ? parentFragment.getParentActivity() : LaunchActivity.instance;
        if (act == null || query == null || query.trim().isEmpty()) return;

        // Interactive secret easter egg commands (.cola, .кола, .горка, .пузыри, .fizz, .matrix, .koliti)
        if (ColgramEasterEggs.tryHandleSecretCommand(query, parentFragment, sheet != null ? sheet.getContainer() : null)) {
            if (preCreatedRow != null) {
                messagesContainer.removeView(preCreatedRow);
                setBusyUi(false);
                setOrbState(ColgramAiOrbView.State.IDLE);
            }
            return;
        }

        if (emptyStateView != null) {
            messagesContainer.removeView(emptyStateView);
            emptyStateView = null;
        }

        List<String> mediaCopy = new ArrayList<>(attachedPaths);
        attachedPaths.clear();
        refreshAttachmentStrip();

        if (renderUserBubble) {
            addUserBubble(query, mediaCopy);
            if (activeSession != null) {
                ColgramAiChatStore.appendMessage(activeSession.id, "user", query, "", mediaCopy);
                updateHeaderForSession();
            }
        }

        final boolean[] ttsWarmedForTurn = {false};

        final View[] cardRowRef = new View[]{preCreatedRow};
        final TextView[] answerViewRef = new TextView[]{preCreatedAns};
        final TextView[] footerViewRef = new TextView[]{preCreatedFoot};
        final LinearLayout[] cardActionsRef = new LinearLayout[]{preCreatedActions};
        final View[] typingWaveRef = new View[]{preCreatedWave};
        if (answerViewRef[0] == null) {
            createStreamingAssistantCard(cardRowRef, answerViewRef, footerViewRef, cardActionsRef, typingWaveRef, true);
        }

        setBusyUi(true);
        setOrbState(ColgramAiOrbView.State.THINKING);
        final long myReqSeq = ++activeRequestSeq;

        final int account = chatActivity != null ? chatActivity.getCurrentAccount() : UserConfig.selectedAccount;
        long dialogId = chatActivity != null ? chatActivity.getDialogId() : 0L;
        boolean linkThisChat = chatActivity != null && attachActiveChatContext;

        // Lightweight pre-plan (<0.05ms in-memory) so normal chat prompts do ZERO UI-thread SQLite queries,
        // ZERO 45-dialog scans, and ZERO disk stat calls before launching inference!
        ColgramAiContextEngine.IntentPlan prePlan = ColgramAiContextEngine.planAutonomousIntent(
                query,
                ColgramAiContextEngine.AssistantMode.AUTO,
                !mediaCopy.isEmpty(),
                linkThisChat,
                false
        );
        boolean needActiveChat = linkThisChat && (prePlan.shouldUseActiveChatContext
                || prePlan.shouldDraftIntoInput
                || prePlan.resolvedMode == ColgramAiContextEngine.AssistantMode.VISION);
        boolean needCrossChat = prePlan.shouldIncludeCrossChat && ColgramAiConfig.isCrossChatRagEnabled(act);
        boolean needDialogDir = ColgramAiAgentTools.queryNeedsDialogDirectory(query, "")
                || queryContainsPronounRef(query);

        String title = needActiveChat ? ColgramAiChatBridge.resolveContextTitle(parentFragment, chatActivity) : "";
        String activeMeta = needActiveChat ? ColgramAiChatBridge.collectActiveChatMetadata(chatActivity) : "";
        List<ColgramAiContextEngine.ChatSnippet> activeSnippets = needActiveChat
                ? ColgramAiChatBridge.collectActiveChatSnippets(chatActivity, 60, false)
                : new ArrayList<>();
        List<ColgramAiContextEngine.ChatSnippet> crossSnippets = needCrossChat
                ? ColgramAiChatBridge.collectCrossChatSnippets(account, dialogId, 45)
                : new ArrayList<>();
        List<String> recentMedia = (linkThisChat && prePlan.shouldAutoGrabMedia)
                ? ColgramAiChatBridge.collectRecentChatMediaPaths(chatActivity, 2)
                : new ArrayList<>();
        String dialogDirectory = needDialogDir
                ? ColgramAiChatBridge.collectDialogDirectorySummary(account, dialogId, 22)
                : "";

        final ColgramAiContextEngine.IntentPlan[] resolvedPlanHolder = new ColgramAiContextEngine.IntentPlan[1];
        final ColgramAiAgentTools.InstalledPluginResult[] installedPluginHolder = new ColgramAiAgentTools.InstalledPluginResult[1];
        final boolean[] directActionFired = {false};
        final boolean[] songChosenByLlm = {false};
        final String[] deliveredChatTarget = {""};
        final String[] pendingStreamText = {null};
        final boolean[] streamUiFlushScheduled = {false};
        final boolean[] firstTokenRendered = {false};
        final Runnable streamUiFlushRunnable = () -> {
            streamUiFlushScheduled[0] = false;
            if (cleanedUp.get() || !sheet.isShowing() || activeRequestSeq != myReqSeq) return;
            String snap = pendingStreamText[0];
            if (snap == null) return;
            pendingStreamText[0] = null;
            stopThinkingPulse();
            if (typingWaveRef[0] != null && typingWaveRef[0].getVisibility() != View.GONE) {
                typingWaveRef[0].setVisibility(View.GONE);
            }
            if (answerViewRef[0].getVisibility() != View.VISIBLE) {
                answerViewRef[0].setVisibility(View.VISIBLE);
                answerViewRef[0].setAlpha(1f);
            }
            // During active streaming, avoid expensive full-document regex re-spans unless markdown/math exists
            if (hasMarkdownOrMathMarkers(snap)) {
                answerViewRef[0].setText(formatMarkdownSpans(snap));
            } else {
                answerViewRef[0].setText(snap);
            }
            scrollToBottomIfNearBottom();
        };

        ColgramAiEngine.ToolExecutor toolExecutor = normalizedAction -> {
            final ColgramAiEngine.ToolResult[] resHolder = new ColgramAiEngine.ToolResult[1];
            final CountDownLatch latch = new CountDownLatch(1);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    resHolder[0] = ColgramAiChatBridge.executeToolWithResult(parentFragment, act, account, normalizedAction);
                } catch (Throwable err) {
                    resHolder[0] = new ColgramAiEngine.ToolResult(false, false, false, normalizedAction, "",
                            "Ошибка выполнения: " + (err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName()));
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(4500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return resHolder[0] != null ? resHolder[0]
                    : new ColgramAiEngine.ToolResult(false, false, false, normalizedAction, "", "Таймаут выполнения инструмента.");
        };

        ColgramAiEngine.Request req = new ColgramAiEngine.Request(
                query,
                ColgramAiContextEngine.AssistantMode.AUTO,
                title,
                activeMeta,
                activeSnippets,
                crossSnippets,
                mediaCopy,
                false,
                linkThisChat,
                false,
                recentMedia,
                dialogDirectory,
                toolExecutor
        );

        ColgramAiEngine.getInstance().runRequest(act, req, new ColgramAiEngine.ResponseListener() {
            @Override
            public void onPlanResolved(ColgramAiContextEngine.IntentPlan plan) {
                if (cleanedUp.get() || activeRequestSeq != myReqSeq) return;
                resolvedPlanHolder[0] = plan;
                if (plan.resolvedMode == ColgramAiContextEngine.AssistantMode.VISION) {
                    setOrbState(ColgramAiOrbView.State.VISION);
                } else if (plan.shouldSearchWeb) {
                    setOrbState(ColgramAiOrbView.State.SEARCHING);
                } else {
                    setOrbState(ColgramAiOrbView.State.THINKING);
                }
            }

            @Override
            public void onToolResult(ColgramAiEngine.ToolResult result) {
                if (result == null || cleanedUp.get() || !sheet.isShowing() || activeRequestSeq != myReqSeq) return;
                if (result.ok) {
                    if ("SING_SONG".equals(result.toolName)) {
                        songChosenByLlm[0] = true;
                        return;
                    }
                    directActionFired[0] = true;
                    if ("SEND_TO_CHAT".equals(result.toolName) && !result.resolvedTarget.isEmpty()) {
                        deliveredChatTarget[0] = result.resolvedTarget;
                    }
                    if (result.dismissSheetOnFinish) {
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                sheet.dismiss();
                            } catch (Throwable ignored) {}
                        }, 320L);
                    }
                }
            }

            @Override
            public void onDirectUiAction(String uiAction) {
                if (cleanedUp.get() || !sheet.isShowing() || activeRequestSeq != myReqSeq) return;
                if ("SING_SONG".equals(uiAction)) {
                    songChosenByLlm[0] = true;
                    return;
                }
                if (uiAction != null && !uiAction.isEmpty()) {
                    boolean ok = ColgramAiChatBridge.executeInAppAction(parentFragment, act, account, uiAction);
                    if (ok) {
                        directActionFired[0] = true;
                    }
                    if (ok && (uiAction.startsWith("DRAFT_TO_CHAT:")
                            || uiAction.startsWith("OPEN_CHAT:")
                            || uiAction.startsWith("OPEN_"))) {
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                sheet.dismiss();
                            } catch (Throwable ignored) {}
                        }, 300L);
                    }
                }
            }

            @Override
            public void onPluginInstalled(ColgramAiAgentTools.InstalledPluginResult plugin) {
                if (activeRequestSeq != myReqSeq) return;
                installedPluginHolder[0] = plugin;
            }

            @Override
            public void onStage(String stageLabel) {
                if (cleanedUp.get() || !sheet.isShowing() || activeRequestSeq != myReqSeq) return;
                statusLine.setText(stageLabel);
            }

            @Override
            public void onWebHits(List<ColgramAiWebSearch.SearchHit> hits) {
                if (cleanedUp.get() || !sheet.isShowing() || activeRequestSeq != myReqSeq) return;
                if (hits != null && !hits.isEmpty()) {
                    statusLine.setText("Анализирую " + hits.size() + " источников…");
                }
            }

            @Override
            public void onTokenDelta(String accumulatedVisibleText, boolean isThinking) {
                if (cleanedUp.get() || !sheet.isShowing() || activeRequestSeq != myReqSeq) return;
                if (isThinking && !ColgramAiConfig.isThinkingEnabled(act)) {
                    statusLine.setText("Думаю…");
                    return;
                }
                if (!isThinking && !ttsWarmedForTurn[0] && ColgramAiConfig.isVoiceOverEnabled(act)) {
                    ttsWarmedForTurn[0] = true;
                    ColgramKolitiVoice.warmUpNeuralTtsAsync(act, detectedUserLang, R.raw.koliti_user_voice_ref);
                }
                pendingStreamText[0] = accumulatedVisibleText;
                if (!firstTokenRendered[0]) {
                    firstTokenRendered[0] = true;
                    messagesContainer.removeCallbacks(streamUiFlushRunnable);
                    streamUiFlushRunnable.run();
                } else if (!streamUiFlushScheduled[0]) {
                    streamUiFlushScheduled[0] = true;
                    messagesContainer.postDelayed(streamUiFlushRunnable, 55L);
                }
            }

            @Override
            public void onFinished(String finalText, String footerInfo, boolean usedLocalModel) {
                if (activeRequestSeq != myReqSeq) return;
                messagesContainer.removeCallbacks(streamUiFlushRunnable);
                streamUiFlushScheduled[0] = false;
                pendingStreamText[0] = null;
                stopThinkingPulse();
                if (typingWaveRef[0] != null) typingWaveRef[0].setVisibility(View.GONE);
                answerViewRef[0].setVisibility(View.VISIBLE);
                answerViewRef[0].setAlpha(1f);
                setBusyUi(false);
                setOrbState(ColgramAiOrbView.State.IDLE);
                if (cleanedUp.get() || !sheet.isShowing()) {
                    return;
                }
                ColgramAiContextEngine.IntentPlan plan = resolvedPlanHolder[0];

                if (songChosenByLlm[0]) {
                    if (cardRowRef[0] != null) {
                        messagesContainer.removeView(cardRowRef[0]);
                    }
                    String caption = (finalText != null && !finalText.trim().isEmpty())
                            ? finalText.trim()
                            : "🎵 Ладно, держи мою фирменную песню!";
                    String songFooter = "Koliti • Фирменная песня";
                    lastFooterTelemetry = footerInfo == null ? "" : footerInfo;
                    if (activeSession != null) {
                        ColgramAiChatStore.appendMessage(activeSession.id, "assistant", caption, songFooter,
                                java.util.Collections.singletonList(ColgramKolitiVoice.MEDIA_TAG_SONG));
                    }
                    addKolitiAudioBubble(false, caption, songFooter, ColgramKolitiVoice.MEDIA_TAG_SONG, R.raw.koliti_song, null, true);
                    statusLine.setText("🎵 Koliti поёт свою песню…");
                    scrollToBottomSmooth();
                    return;
                }

                if (!usedLocalModel && !directActionFired[0] && plan != null && !plan.inAppAction.isEmpty()) {
                    statusLine.setText(plan.intentSummary);
                    answerViewRef[0].setText(formatMarkdownSpans(finalText));
                    footerViewRef[0].setText(footerInfo);
                    if (activeSession != null) {
                        ColgramAiChatStore.appendMessage(activeSession.id, "assistant", finalText, footerInfo);
                    }
                    sheet.dismiss();
                    ColgramAiChatBridge.executeInAppAction(parentFragment, act, account, plan.inAppAction);
                    return;
                }

                String displayBody = finalText;
                ColgramAiAgentTools.InstalledPluginResult installedPlugin = installedPluginHolder[0];
                if (installedPlugin != null && installedPlugin.installed) {
                    String cmdHint = installedPlugin.command.isEmpty() ? "" : (" (команда ." + installedPlugin.command + ")");
                    displayBody = "✅ Плагин «" + installedPlugin.displayName + "» (" + installedPlugin.fileName + ") создан и включён" + cmdHint + "!\n\n" + finalText;
                }

                boolean autoInsertDraft = !directActionFired[0] && plan != null && plan.shouldDraftIntoInput
                        && canWriteToActiveChat()
                        && displayBody != null && !displayBody.isEmpty() && displayBody.length() < 900;
                if (autoInsertDraft) {
                    ColgramAiChatBridge.insertDraftIntoChat(chatActivity, displayBody, false);
                    statusLine.setText("✓ Вставлено в поле ввода");
                    Toast.makeText(act, "Текст вставлен в поле ввода", Toast.LENGTH_SHORT).show();
                } else {
                    statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
                }

                answerViewRef[0].setText(formatMarkdownSpans(displayBody));
                lastFooterTelemetry = footerInfo == null ? "" : footerInfo;
                applyTelemetryFooter(footerViewRef[0], footerInfo, act);
                if (activeSession != null) {
                    ColgramAiChatStore.appendMessage(activeSession.id, "assistant", displayBody, footerInfo);
                }
                populateAnswerActions(cardActionsRef[0], displayBody, autoInsertDraft,
                        installedPlugin != null && installedPlugin.installed, query);
                if (ColgramAiConfig.isVoiceOverEnabled(act) && displayBody != null && !displayBody.trim().isEmpty()) {
                    ColgramKolitiVoice.speakReplyAsync(act, displayBody, detectedUserLang, R.raw.koliti_user_voice_ref,
                            (usedClone, file, note) -> AndroidUtilities.runOnUIThread(() -> {
                                updateVoiceOverPillUi(act);
                                if (!busyGenerating && note != null && !note.isEmpty()) {
                                    statusLine.setText("🔊 " + note);
                                }
                            }));
                }
                if (!deliveredChatTarget[0].isEmpty()) {
                    final String targetToOpen = deliveredChatTarget[0];
                    View openSentBtn = makeIconTextPill(act, R.drawable.msg_send, "Открыть «" + shorten(targetToOpen, 16) + "»",
                            isDark ? 0xFF0F3B2E : 0xFFD1FAE5,
                            isDark ? 0xFF34D399 : 0xFF047857);
                    openSentBtn.setOnClickListener(v -> {
                        sheet.dismiss();
                        ColgramAiChatBridge.executeInAppAction(parentFragment, act, account, "OPEN_CHAT:" + targetToOpen);
                    });
                    cardActionsRef[0].addView(openSentBtn, 0, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 7, 0));
                }
                scrollToBottomSmooth();
            }

            @Override
            public void onError(String message) {
                if (activeRequestSeq != myReqSeq) return;
                messagesContainer.removeCallbacks(streamUiFlushRunnable);
                streamUiFlushScheduled[0] = false;
                pendingStreamText[0] = null;
                stopThinkingPulse();
                if (typingWaveRef[0] != null) typingWaveRef[0].setVisibility(View.GONE);
                answerViewRef[0].setVisibility(View.VISIBLE);
                answerViewRef[0].setAlpha(1f);
                setBusyUi(false);
                setOrbState(ColgramAiOrbView.State.IDLE);
                if (cleanedUp.get() || !sheet.isShowing()) return;
                statusLine.setText(message);
                answerViewRef[0].setText(message);
            }
        });
    }

    private static boolean hasMarkdownOrMathMarkers(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '`' || c == '$' || c == '#' || c == '~' || c == '\\') {
                return true;
            }
        }
        return false;
    }

    private static boolean queryContainsPronounRef(String q) {
        if (q == null || q.isEmpty()) return false;
        String l = q.toLowerCase(Locale.ROOT);
        return l.contains("ему") || l.contains("ей") || l.contains("него")
                || l.contains("неё") || l.contains("нее") || l.contains("туда")
                || l.contains("ещё раз") || l.contains("еще раз");
    }

    private boolean canWriteToActiveChat() {
        return chatActivity != null
                && chatActivity.chatActivityEnterView != null
                && chatActivity.chatActivityEnterView.getVisibility() == View.VISIBLE
                && chatActivity.chatActivityEnterView.getEditField() != null;
    }

    private CharSequence formatMarkdownSpans(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String normalized = raw.replace("\r\n", "\n");
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = normalized.split("\n", -1);
        boolean inCodeBlock = false;
        boolean inMathBlock = false;
        StringBuilder mathBlockBuf = new StringBuilder();
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.trim();

            // Fenced code blocks ```
            if (!inMathBlock && trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) {
                if (out.length() > 0) out.append('\n');
                int s = out.length();
                out.append("  ").append(line);
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(cCodeFg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(cCodeBg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // Multi-line display math blocks: $$ ... $$ or \[ ... \]
            if (inMathBlock) {
                if (trimmed.equals("$$") || trimmed.equals("\\]") || trimmed.endsWith("$$") || trimmed.endsWith("\\]")) {
                    String endPart = trimmed.replaceAll("(?:\\$\\$|\\\\\\])$", "").trim();
                    if (!endPart.isEmpty()) {
                        if (mathBlockBuf.length() > 0) mathBlockBuf.append(' ');
                        mathBlockBuf.append(endPart);
                    }
                    appendDisplayMathBlock(out, mathBlockBuf.toString());
                    mathBlockBuf.setLength(0);
                    inMathBlock = false;
                } else {
                    if (mathBlockBuf.length() > 0) mathBlockBuf.append(' ');
                    mathBlockBuf.append(trimmed);
                }
                continue;
            }

            // Single-line display math: $$...$$ or \[...\]
            if ((trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length() > 4)
                    || (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length() > 4)) {
                String inner = trimmed.substring(2, trimmed.length() - 2).trim();
                appendDisplayMathBlock(out, inner);
                continue;
            }
            if (trimmed.equals("$$") || trimmed.equals("\\[")
                    || (trimmed.startsWith("$$") && !trimmed.substring(2).contains("$$"))
                    || (trimmed.startsWith("\\[") && !trimmed.contains("\\]"))) {
                inMathBlock = true;
                mathBlockBuf.setLength(0);
                String firstPart = trimmed.substring(2).trim();
                if (!firstPart.isEmpty()) mathBlockBuf.append(firstPart);
                continue;
            }

            if (out.length() > 0) out.append('\n');
            if (trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ")) {
                String hdr = trimmed.replaceFirst("^#+\\s+", "");
                int s = out.length();
                appendInlineMarkdown(out, hdr);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.08f), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("> ")) {
                int barStart = out.length();
                out.append("▎ ");
                out.setSpan(new ForegroundColorSpan(ACCENT_CRIMSON), barStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int qStart = out.length();
                appendInlineMarkdown(out, trimmed.substring(2));
                out.setSpan(new StyleSpan(Typeface.ITALIC), qStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(cTextSecondary), qStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                out.append("• ");
                appendInlineMarkdown(out, trimmed.substring(2));
            } else {
                appendInlineMarkdown(out, line);
            }
        }
        if (inMathBlock && mathBlockBuf.length() > 0) {
            appendDisplayMathBlock(out, mathBlockBuf.toString());
        }
        return out;
    }

    private void appendDisplayMathBlock(SpannableStringBuilder out, String tex) {
        String rendered = ColgramAiContextEngine.renderLatexFormula(tex);
        if (rendered.isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        int s = out.length();
        out.append("  ").append(rendered).append("  ");
        out.setSpan(new TypefaceSpan("serif"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.12f), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(cMathFg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(cMathBg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final Pattern INLINE_MD = Pattern.compile(
            "(\\$\\$([^\\n]+?)\\$\\$|\\\\\\[([^\\n]+?)\\\\\\]|\\\\\\(([^\\n]+?)\\\\\\)|\\$([^$\\s\\n](?:[^$\\n]*?[^$\\s\\n])?)\\$|\\*\\*(.+?)\\*\\*|`([^`]+)`|~~([^~\n]+)~~|\\*([^*\n]+)\\*)"
    );

    private void appendInlineMarkdown(SpannableStringBuilder out, String text) {
        Matcher m = INLINE_MD.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                appendPlainOrBareLatex(out, text.substring(last, m.start()));
            }
            int s = out.length();
            if (m.group(2) != null || m.group(3) != null || m.group(4) != null || m.group(5) != null) {
                String tex = m.group(2) != null ? m.group(2)
                        : (m.group(3) != null ? m.group(3)
                        : (m.group(4) != null ? m.group(4) : m.group(5)));
                String rendered = ColgramAiContextEngine.renderLatexFormula(tex);
                out.append(" ").append(rendered).append(" ");
                out.setSpan(new TypefaceSpan("serif"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(cMathFg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(cMathBg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(6) != null) {
                appendInlineMarkdown(out, m.group(6));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(7) != null) {
                out.append(" ").append(m.group(7)).append(" ");
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(cCodeFg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(cCodeBg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(8) != null) {
                out.append(m.group(8));
                out.setSpan(new StrikethroughSpan(), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(9) != null) {
                out.append(m.group(9));
                out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        if (last < text.length()) {
            appendPlainOrBareLatex(out, text.substring(last));
        }
    }

    private void appendPlainOrBareLatex(SpannableStringBuilder out, String segment) {
        if (segment == null || segment.isEmpty()) return;
        if (segment.contains("\\frac{") || segment.contains("\\sqrt{") || segment.contains("\\pm")) {
            int s = out.length();
            out.append(ColgramAiContextEngine.renderLatexFormula(segment));
            out.setSpan(new TypefaceSpan("serif"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            out.append(segment);
        }
    }

    private void syncCoreInstallBanner(Activity act) {
        if (act == null || coreInstallBanner == null) return;
        boolean localCoreReady = ColgramAiModelManager.resolveActiveModel(act).isReady()
                && !ColgramAiModelManager.needsEmbeddedUpgrade(act);
        boolean hasEmbedded = ColgramAiModelManager.hasEmbeddedAssets(act);
        ColgramAiModelManager.DownloadState st = ColgramAiModelManager.getDownloadState();
        boolean downloading = ColgramAiModelManager.isDownloading() || st.active;
        ColgramAiModels.ModelSpec activeSpec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(act));

        if (localCoreReady && !downloading) {
            coreInstallBanner.setVisibility(View.GONE);
            if (!busyGenerating) {
                statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
            }
            return;
        }
        if (hasEmbedded && !downloading) {
            coreInstallBanner.setVisibility(View.GONE);
            if (!embeddedUnpackRequested) {
                embeddedUnpackRequested = true;
                ColgramAiModelManager.ensureEmbeddedReadyAsync(act, null);
            }
            return;
        }
        if (downloading) {
            if (st.extracting || ColgramAiModelManager.isExtractingEmbedded()) {
                coreInstallBanner.setVisibility(View.GONE);
                int pct = st.percent();
                if (!busyGenerating) {
                    statusLine.setText(pct >= 0 ? ("Обновление ядра • " + pct + "%") : "Обновление ядра…");
                }
                return;
            }
            coreInstallBanner.setVisibility(View.VISIBLE);
            coreInstallAction.setText("Пауза");
            coreInstallProgress.setVisibility(View.VISIBLE);
            int pct = st.percent();
            coreInstallProgress.setIndeterminate(pct < 0);
            if (pct >= 0) {
                coreInstallProgress.setProgress(pct);
            }
            String stage = st.phase.isEmpty() ? ("Загрузка " + activeSpec.title + "…") : st.phase;
            if (pct >= 0 && st.totalBytes > 0) {
                String sz = ColgramAiModelManager.formatSizeMb(st.downloadedBytes)
                        + "/" + ColgramAiModelManager.formatSizeMb(st.totalBytes);
                if (st.speedMbPerSec > 0.05) {
                    coreInstallText.setText(String.format(Locale.US, "%s • %d%% (%s • %.1f МБ/с)",
                            stage, pct, sz, st.speedMbPerSec));
                } else {
                    coreInstallText.setText(String.format(Locale.US, "%s • %d%% (%s)", stage, pct, sz));
                }
            } else {
                coreInstallText.setText(stage);
            }
            if (!busyGenerating) {
                statusLine.setText(pct >= 0 ? ("Загрузка Koliti • " + pct + "%") : "Загрузка Koliti…");
            }
            return;
        }

        // Lite APK, not currently downloading
        coreInstallBanner.setVisibility(View.VISIBLE);
        long partialBytes = ColgramAiModelManager.getPartialBytes(act, activeSpec);
        boolean hasErr = !TextUtils.isEmpty(st.lastError) && !st.pausedByUser
                && (st.modelId.isEmpty() || st.modelId.equals(activeSpec.id));
        if (partialBytes > 0L) {
            long total = activeSpec.approxModelBytes > 0L ? activeSpec.approxModelBytes : partialBytes;
            int pct = (int) Math.min(99L, (partialBytes * 100L) / Math.max(1L, total));
            coreInstallProgress.setVisibility(View.VISIBLE);
            coreInstallProgress.setIndeterminate(false);
            coreInstallProgress.setProgress(pct);
            coreInstallText.setText((hasErr ? (shorten(st.lastError, 26) + " • ") : "Пауза • ")
                    + ColgramAiModelManager.formatSizeMb(partialBytes) + "/" + ColgramAiModelManager.formatSizeMb(total));
            coreInstallAction.setText("Продолжить");
        } else if (hasErr) {
            coreInstallProgress.setVisibility(View.GONE);
            coreInstallText.setText(shorten(st.lastError, 52));
            coreInstallAction.setText("Повторить");
        } else {
            coreInstallProgress.setVisibility(View.GONE);
            coreInstallText.setText(activeSpec.title + " • " + ColgramAiModelManager.formatSizeMb(activeSpec.approxModelBytes) + " + Vision");
            coreInstallAction.setText("Установить");
        }
        if (!busyGenerating) {
            statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
        }
    }

    private void triggerCoreDownload(Activity act) {
        if (ColgramAiModelManager.isDownloading()) {
            ColgramAiModelManager.cancelDownload();
            syncCoreInstallBanner(act);
            return;
        }
        ColgramAiModels.ModelSpec activeSpec = ColgramAiModels.findById(ColgramAiConfig.getActiveModelId(act));
        final Context appCtx = act.getApplicationContext() != null ? act.getApplicationContext() : act;
        ColgramAiModelManager.startDownloadAsync(appCtx, activeSpec, true, new ColgramAiModelManager.DownloadListener() {
            @Override
            public void onProgress(String stage, long downloadedBytes, long totalBytes, double speedMbSec) {
                // Live UI progress pushed via global downloadObserver
            }

            @Override
            public void onComplete(boolean success, String message) {
                AndroidUtilities.runOnUIThread(() -> {
                    Activity curAct = resolveValidActivity(parentFragment);
                    if (curAct != null) {
                        Toast.makeText(curAct, success ? ("Готово: " + activeSpec.title) : message, Toast.LENGTH_LONG).show();
                    }
                    if (!cleanedUp.get() && curAct != null) {
                        syncCoreInstallBanner(curAct);
                    }
                });
            }
        });
    }

    private void startDotPulse() {
        stopDotPulse();
        Context ctx = liveDot != null ? liveDot.getContext() : null;
        if (org.colgram.core.ColgramPowerGuard.isPowerSaveMode(ctx)
                || org.colgram.core.ColgramPowerGuard.isThermalThrottled(ctx)) {
            if (liveDot != null) liveDot.setAlpha(1.0f);
            return;
        }
        final long[] lastDotFrameMs = new long[]{0L};
        dotPulseAnimator = ValueAnimator.ofFloat(0.6f, 1.0f);
        dotPulseAnimator.setDuration(1400L);
        dotPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        dotPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotPulseAnimator.setInterpolator(new LinearInterpolator());
        dotPulseAnimator.addUpdateListener(a -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastDotFrameMs[0] < 50L) return;
            lastDotFrameMs[0] = now;
            float v = (float) a.getAnimatedValue();
            liveDot.setAlpha(v);
        });
        dotPulseAnimator.start();
    }

    private void stopDotPulse() {
        if (dotPulseAnimator != null) {
            dotPulseAnimator.cancel();
            dotPulseAnimator = null;
        }
    }

    private void startThinkingPulse(View target) {
        stopThinkingPulse();
        thinkingPulseAnimator = ValueAnimator.ofFloat(0.45f, 1.0f);
        thinkingPulseAnimator.setDuration(680L);
        thinkingPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        thinkingPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        thinkingPulseAnimator.addUpdateListener(a -> target.setAlpha((float) a.getAnimatedValue()));
        thinkingPulseAnimator.start();
    }

    private void stopThinkingPulse() {
        if (thinkingPulseAnimator != null) {
            thinkingPulseAnimator.cancel();
            thinkingPulseAnimator = null;
        }
    }

    private void setBusyUi(boolean busy) {
        this.busyGenerating = busy;
        sendButton.setImageResource(busy ? R.drawable.msg_cancel : R.drawable.msg_send);
        if (liveDot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) liveDot.getBackground()).setColor(busy ? 0xFF38BDF8 : ACCENT_EMERALD);
        }
    }

    public void attachExternalMediaUri(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return;
        final Context appCtx = ctx.getApplicationContext();
        MEDIA_THUMB_EXEC.execute(() -> {
            File dst = null;
            try {
                File mediaDir = new File(appCtx.getFilesDir(), "koliti_media");
                if (!mediaDir.exists()) mediaDir.mkdirs();
                pruneOldKolitiMedia(mediaDir, 50);
                String mime = appCtx.getContentResolver().getType(uri);
                String ext = ".jpg";
                if (mime != null) {
                    String low = mime.toLowerCase(Locale.US);
                    if (low.contains("video")) ext = ".mp4";
                    else if (low.contains("png")) ext = ".png";
                    else if (low.contains("webp")) ext = ".webp";
                    else if (low.contains("gif")) ext = ".gif";
                }
                dst = new File(mediaDir, "media_" + System.currentTimeMillis() + "_" + System.nanoTime() + ext);
                try (InputStream is = appCtx.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(dst)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while (is != null && (n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                if (dst.length() > 0) {
                    final String savedPath = dst.getAbsolutePath();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (cleanedUp.get()) return;
                        if (attachedPaths.size() >= 4) {
                            attachedPaths.remove(0);
                        }
                        attachedPaths.add(savedPath);
                        refreshAttachmentStrip();
                    });
                } else {
                    try { dst.delete(); } catch (Throwable ignored) {}
                }
            } catch (Throwable err) {
                if (dst != null) {
                    try { dst.delete(); } catch (Throwable ignored) {}
                }
                AndroidUtilities.runOnUIThread(() ->
                        Toast.makeText(appCtx, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static void pruneOldKolitiMedia(File dir, int maxFiles) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= maxFiles) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int toDelete = files.length - maxFiles;
            for (int i = 0; i < toDelete; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {}
    }

    private void attachRecentMediaOrPick() {
        Activity act = parentFragment != null ? parentFragment.getParentActivity() : LaunchActivity.instance;
        if (act == null) return;
        List<String> recent = ColgramAiChatBridge.collectRecentChatMediaPaths(chatActivity, 1);
        if (!recent.isEmpty() && !attachedPaths.contains(recent.get(0))) {
            final String recentPath = recent.get(0);
            AlertDialog.Builder builder = new AlertDialog.Builder(act);
            builder.setTitle("Прикрепить к Koliti");
            builder.setItems(new CharSequence[]{
                    "Выбрать фото или видео из галереи",
                    "Взять последнее медиа из этого чата"
            }, (dialog, which) -> {
                if (which == 0) {
                    launchMediaPicker(act);
                } else {
                    if (attachedPaths.size() >= 4) attachedPaths.remove(0);
                    attachedPaths.add(recentPath);
                    refreshAttachmentStrip();
                }
            });
            builder.show();
            return;
        }
        launchMediaPicker(act);
    }

    private void launchMediaPicker(Activity act) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            ColgramAiActivity.pendingSheetForMedia = this;
            Intent chooser = Intent.createChooser(intent, "Фото или видео для Koliti");
            if (parentFragment != null) {
                parentFragment.startActivityForResult(chooser, ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET);
            } else {
                act.startActivityForResult(chooser, ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET);
            }
        } catch (Throwable t) {
            Toast.makeText(act, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshAttachmentStrip() {
        attachmentStripContainer.removeAllViews();
        if (attachedPaths.isEmpty()) {
            attachmentStripScroll.setVisibility(View.GONE);
            return;
        }
        Context ctx = attachmentStripContainer.getContext();
        attachmentStripScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < attachedPaths.size(); i++) {
            final String path = attachedPaths.get(i);
            final int idx = i;
            FrameLayout itemWrap = new FrameLayout(ctx);

            View thumb = buildRoundedMediaThumb(ctx, path, dp(58), dp(58), dp(12), true);
            itemWrap.addView(thumb, LayoutHelper.createFrame(58, 58, Gravity.BOTTOM | Gravity.START, 0, 5, 5, 0));

            TextView removeBtn = new TextView(ctx);
            removeBtn.setText("×");
            removeBtn.setTextColor(Color.WHITE);
            removeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            removeBtn.setTypeface(AndroidUtilities.bold());
            removeBtn.setGravity(Gravity.CENTER);
            GradientDrawable rmBg = new GradientDrawable();
            rmBg.setShape(GradientDrawable.OVAL);
            rmBg.setColor(0xD9111827);
            rmBg.setStroke(dp(1), 0x80FFFFFF);
            removeBtn.setBackground(rmBg);
            removeBtn.setOnClickListener(v -> {
                if (attachedPaths.remove(path)) {
                    refreshAttachmentStrip();
                }
            });
            itemWrap.addView(removeBtn, LayoutHelper.createFrame(20, 20, Gravity.TOP | Gravity.END));

            attachmentStripContainer.addView(itemWrap, LayoutHelper.createLinear(64, 64, 0, 0, 6, 0));
        }

        if (attachedPaths.size() < 4) {
            TextView addMore = new TextView(ctx);
            addMore.setText("+");
            addMore.setTextColor(cTextSecondary);
            addMore.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
            addMore.setGravity(Gravity.CENTER);
            GradientDrawable addBg = new GradientDrawable();
            addBg.setColor(cChipBg);
            addBg.setCornerRadius(dp(12));
            addBg.setStroke(dp(1), cBorder);
            addMore.setBackground(addBg);
            ColgramMenuChrome.attachSpringPress(addMore);
            addMore.setOnClickListener(v -> {
                Activity act = parentFragment != null ? parentFragment.getParentActivity() : LaunchActivity.instance;
                if (act != null) launchMediaPicker(act);
            });
            attachmentStripContainer.addView(addMore, LayoutHelper.createLinear(56, 56, 0, 4, 2, 0));
        }
        scrollToBottomSmooth();
    }

    private static boolean isVideoPath(String path) {
        if (path == null) return false;
        String low = path.toLowerCase(Locale.US);
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".mov") || low.endsWith(".webm") || low.endsWith(".avi");
    }

    private static Bitmap decodeMediaThumbnail(String path, int reqW, int reqH) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0L) return null;
        if (isVideoPath(path)) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(path);
                Bitmap frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, reqW), Math.max(1, reqH), true);
                    if (scaled != frame) {
                        frame.recycle();
                    }
                    return scaled;
                }
            } catch (Throwable ignored) {
            } finally {
                try { retriever.release(); } catch (Throwable ignored) {}
            }
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int inSample = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while (w > 0 && h > 0 && (w / (inSample * 2) >= reqW || h / (inSample * 2) >= reqH)) {
                inSample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, inSample);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private View buildRoundedMediaThumb(Context ctx, String path, int widthPx, int heightPx, int radiusPx, boolean openLightboxOnClick) {
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isDark ? 0xFF1F2638 : 0xFFE2E8F0);
        bg.setCornerRadius(radiusPx);
        bg.setStroke(dp(1), cBorder);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });

        final ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAlpha(0f);
        card.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final ImageView ph = new ImageView(ctx);
        ph.setImageResource(R.drawable.msg_media);
        ph.setColorFilter(cIconMuted, PorterDuff.Mode.SRC_IN);
        ph.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        card.addView(ph, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        final int decW = Math.max(128, widthPx);
        final int decH = Math.max(128, heightPx);
        MEDIA_THUMB_EXEC.execute(() -> {
            if (cleanedUp.get()) return;
            Bitmap bmp = decodeMediaThumbnail(path, decW, decH);
            if (bmp != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (cleanedUp.get()) {
                        bmp.recycle();
                        return;
                    }
                    iv.setImageBitmap(bmp);
                    ph.setVisibility(View.GONE);
                    iv.animate().alpha(1f).setDuration(160L).start();
                });
            }
        });

        if (isVideoPath(path)) {
            TextView playBadge = new TextView(ctx);
            playBadge.setText("▶");
            playBadge.setTextColor(Color.WHITE);
            playBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
            playBadge.setGravity(Gravity.CENTER);
            GradientDrawable pBg = new GradientDrawable();
            pBg.setShape(GradientDrawable.OVAL);
            pBg.setColor(0xA6000000);
            playBadge.setBackground(pBg);
            card.addView(playBadge, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        }

        if (openLightboxOnClick) {
            ColgramMenuChrome.attachSpringPress(card);
            card.setOnClickListener(v -> showImageLightbox(ctx, path));
        }
        return card;
    }

    private void showImageLightbox(Context ctx, String path) {
        if (ctx == null || path == null || !new File(path).isFile()) return;
        try {
            Dialog dlg = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0xF005070D));
                dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            }
            FrameLayout root = new FrameLayout(ctx);
            root.setAlpha(0f);
            root.setOnClickListener(v -> dlg.dismiss());

            final ImageView fullImg = new ImageView(ctx);
            fullImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fullImg.setScaleX(0.94f);
            fullImg.setScaleY(0.94f);
            int topInset = Math.max(dp(48), AndroidUtilities.statusBarHeight + dp(44));
            int botInset = Math.max(dp(48), AndroidUtilities.navigationBarHeight + dp(44));
            FrameLayout.LayoutParams imgLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            imgLp.setMargins(dp(16), topInset, dp(16), botInset);
            root.addView(fullImg, imgLp);

            final int maxDim = Math.max(720, Math.min(1600, Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y)));
            MEDIA_THUMB_EXEC.execute(() -> {
                Bitmap fullBmp = decodeMediaThumbnail(path, maxDim, maxDim);
                if (fullBmp != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        fullImg.setImageBitmap(fullBmp);
                        fullImg.animate().scaleX(1f).scaleY(1f).setDuration(190L)
                                .setInterpolator(new DecelerateInterpolator()).start();
                    });
                }
            });

            TextView close = new TextView(ctx);
            close.setText("✕");
            close.setTextColor(Color.WHITE);
            close.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
            close.setGravity(Gravity.CENTER);
            ColgramMenuChrome.attachTactileFeedback(close, 99, 0xA60F172A, 0x33FFFFFF, 0x33FFFFFF);
            close.setOnClickListener(v -> dlg.dismiss());
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
            closeLp.topMargin = AndroidUtilities.statusBarHeight + dp(12);
            closeLp.rightMargin = dp(16);
            root.addView(close, closeLp);

            TextView nameView = new TextView(ctx);
            nameView.setText(new File(path).getName());
            nameView.setTextColor(0xD9FFFFFF);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            nameLp.leftMargin = dp(16);
            nameLp.rightMargin = dp(16);
            nameLp.bottomMargin = AndroidUtilities.navigationBarHeight + dp(16);
            root.addView(nameView, nameLp);

            dlg.setContentView(root);
            dlg.show();
            root.animate().alpha(1f).setDuration(160L).start();
        } catch (Throwable ignored) {}
    }

    private void scrollToBottomSmooth() {
        scrollView.post(() -> {
            View last = messagesContainer.getChildAt(messagesContainer.getChildCount() - 1);
            if (last != null) {
                int targetY = Math.max(0, last.getBottom() - scrollView.getHeight() + dp(16));
                scrollView.smoothScrollTo(0, targetY);
            }
        });
    }

    private void scrollToBottomIfNearBottom() {
        View last = messagesContainer.getChildAt(messagesContainer.getChildCount() - 1);
        if (last == null) return;
        int viewH = scrollView.getHeight();
        if (viewH <= 0) return;
        int targetY = Math.max(0, last.getBottom() - viewH + dp(16));
        int currentY = scrollView.getScrollY();
        if (targetY - currentY <= dp(140) || currentY == 0) {
            scrollView.scrollTo(0, targetY);
        }
    }

    private void addUserBubble(String text) {
        addUserBubble(text, null);
    }

    private void addUserBubble(String text, List<String> mediaPaths) {
        Context ctx = messagesContainer.getContext();
        List<String> validMedia = new ArrayList<>();
        if (mediaPaths != null) {
            for (String p : mediaPaths) {
                if (p != null && !p.isEmpty() && new File(p).isFile()) {
                    validMedia.add(p);
                }
            }
        }

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT_CRIMSON, 0xFFBE123C}
        );
        bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), dp(6), dp(6), dp(20), dp(20)});

        if (validMedia.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            tv.setLineSpacing(dp(2f), 1f);
            tv.setPadding(dp(15), dp(10), dp(15), dp(10));
            tv.setBackground(bg);
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 48, 4, 0, 12);
            animateBubbleEntrance(tv, true);
            messagesContainer.addView(tv, lp);
            scrollToBottomSmooth();
            return;
        }

        LinearLayout bubble = new LinearLayout(ctx);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(7), dp(7), dp(7), dp(8));
        bubble.setBackground(bg);

        if (validMedia.size() == 1) {
            View thumb = buildRoundedMediaThumb(ctx, validMedia.get(0), dp(210), dp(160), dp(14), true);
            bubble.addView(thumb, LayoutHelper.createLinear(210, 160));
        } else {
            HorizontalScrollView rowScroll = new HorizontalScrollView(ctx);
            rowScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < validMedia.size(); i++) {
                View thumb = buildRoundedMediaThumb(ctx, validMedia.get(i), dp(116), dp(116), dp(12), true);
                row.addView(thumb, LayoutHelper.createLinear(116, 116, 0, 0, i < validMedia.size() - 1 ? 6 : 0, 0));
            }
            rowScroll.addView(row);
            bubble.addView(rowScroll, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        if (text != null && !text.trim().isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            tv.setLineSpacing(dp(2f), 1f);
            tv.setPadding(dp(8), dp(7), dp(8), dp(2));
            bubble.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 48, 4, 0, 12);
        animateBubbleEntrance(bubble, true);
        messagesContainer.addView(bubble, lp);
        scrollToBottomSmooth();
    }

    private void addAssistantBubble(String text, String footer, boolean withActions, boolean alreadyInserted, String retryPrompt) {
        final TextView[] ans = new TextView[1];
        final TextView[] ft = new TextView[1];
        final LinearLayout[] act = new LinearLayout[1];
        createStreamingAssistantCard(ans, ft, act, null, false);
        ans[0].setText(formatMarkdownSpans(text));
        if (footer != null && !footer.trim().isEmpty()) {
            lastFooterTelemetry = footer;
        }
        applyTelemetryFooter(ft[0], footer, AndroidUtilities.findActivity(messagesContainer.getContext()));
        if (withActions) {
            populateAnswerActions(act[0], text, alreadyInserted, false, retryPrompt);
        }
    }

    private void createStreamingAssistantCard(
            TextView[] ansOut,
            TextView[] footerOut,
            LinearLayout[] actionsOut,
            View[] waveOut,
            boolean showTypingWave
    ) {
        createStreamingAssistantCard(null, ansOut, footerOut, actionsOut, waveOut, showTypingWave);
    }

    private void createStreamingAssistantCard(
            View[] rowOut,
            TextView[] ansOut,
            TextView[] footerOut,
            LinearLayout[] actionsOut,
            View[] waveOut,
            boolean showTypingWave
    ) {
        Context ctx = messagesContainer.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        ImageView miniAva = new ImageView(ctx);
        ColgramAeroStyler.applyKolitiAvatar(miniAva);
        miniAva.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(miniAva, LayoutHelper.createLinear(28, 28, 0, 2, 10, 0));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), dp(18), dp(18), dp(6), dp(6)});
        bg.setStroke(dp(1), cBorder);
        card.setBackground(bg);
        if (!isDark) card.setElevation(dp(1));

        ColgramMenuChrome.TypingWaveView wave = new ColgramMenuChrome.TypingWaveView(ctx, cAccent);
        wave.setVisibility(showTypingWave ? View.VISIBLE : View.GONE);
        card.addView(wave, LayoutHelper.createLinear(44, 22, 0, 2, 0, 2));

        TextView body = new TextView(ctx);
        body.setTextColor(cTextPrimary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        body.setLineSpacing(dp(3f), 1f);
        body.setTextIsSelectable(true);
        body.setVisibility(showTypingWave ? View.GONE : View.VISIBLE);
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView foot = new TextView(ctx);
        foot.setTextColor(cTextSecondary);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        foot.setTypeface(AndroidUtilities.bold());
        foot.setPadding(dp(9), dp(4), dp(9), dp(4));
        foot.setMaxLines(1);
        foot.setEllipsize(TextUtils.TruncateAt.END);
        foot.setVisibility(View.GONE);
        card.addView(foot, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 2));

        HorizontalScrollView actionsScroll = new HorizontalScrollView(ctx);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actionsScroll.addView(actions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        row.addView(card, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        animateBubbleEntrance(row, false);
        messagesContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 6, 12));
        scrollToBottomSmooth();

        if (rowOut != null) rowOut[0] = row;
        ansOut[0] = body;
        footerOut[0] = foot;
        actionsOut[0] = actions;
        if (waveOut != null) waveOut[0] = wave;
    }

    private void populateAnswerActions(LinearLayout actionsRow, String answerText, boolean alreadyInserted, boolean pluginInstalled, String retryPrompt) {
        actionsRow.removeAllViews();
        Context ctx = actionsRow.getContext();
        if (pluginInstalled) {
            View openPlug = makeIconTextPill(ctx, R.drawable.msg_settings, "Открыть Плагины",
                    isDark ? 0xFF0F3B2E : 0xFFD1FAE5, isDark ? 0xFF34D399 : 0xFF047857);
            openPlug.setOnClickListener(v -> {
                sheet.dismiss();
                if (parentFragment != null) parentFragment.presentFragment(new ColgramPluginsActivity());
            });
            actionsRow.addView(openPlug, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 7, 0));
        }
        if (canWriteToActiveChat()) {
            String label = alreadyInserted ? "✓ В поле ввода" : "Вставить";
            View insertBtn = makeIconTextPill(ctx, R.drawable.msg_edit, label,
                    alreadyInserted ? (isDark ? 0xFF0F3B2E : 0xFFD1FAE5) : (isDark ? 0xFF2D1422 : 0xFFFFE4E6),
                    alreadyInserted ? (isDark ? 0xFF34D399 : 0xFF047857) : ACCENT_CRIMSON);
            insertBtn.setOnClickListener(v -> {
                ColgramAiChatBridge.insertDraftIntoChat(chatActivity, answerText, false);
                sheet.dismiss();
            });
            actionsRow.addView(insertBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 7, 0));
        }

        View copyBtn = makeIconTextPill(ctx, R.drawable.msg_copy, "Копировать", cChipBg, cIconMuted);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Koliti", answerText));
                Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show();
            }
        });
        actionsRow.addView(copyBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 7, 0));

        if (retryPrompt != null && !retryPrompt.trim().isEmpty()) {
            View retryBtn = makeIconTextPill(ctx, R.drawable.msg_retry, "Ещё раз", cChipBg, cIconMuted);
            retryBtn.setOnClickListener(v -> {
                if (!busyGenerating) submitPrompt(retryPrompt);
            });
            actionsRow.addView(retryBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30));
        }
    }

    private void animateBubbleEntrance(View v, boolean fromRight) {
        v.setAlpha(0f);
        v.setTranslationY(dp(10));
        v.setTranslationX(fromRight ? dp(8) : -dp(6));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .translationX(0f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void setOrbState(ColgramAiOrbView.State st) {
        if (orbView != null) orbView.setState(st);
    }

    private ImageView makeIconBtn(Context ctx, int resId, int bgColor, int tintColor) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(resId);
        iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (bgColor != Color.TRANSPARENT) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(bgColor);
            if (bgColor == cChipBg) gd.setStroke(1, cBorder);
            iv.setBackground(gd);
        }
        ColgramMenuChrome.attachSpringPress(iv);
        return iv;
    }

    private View makeIconTextPill(Context ctx, int iconRes, String label, int bgColor, int fgColor) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(10), dp(4), dp(11), dp(4));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(999));
        gd.setStroke(1, cBorder);
        box.setBackground(gd);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(fgColor, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        box.addView(icon, LayoutHelper.createLinear(13, 13, 0, 0, 5, 0));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(fgColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setSingleLine(true);
        box.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        ColgramMenuChrome.attachSpringPress(box);
        return box;
    }

    private void updateVoiceOverPillUi(Context ctx) {
        if (voiceOverPill == null) return;
        boolean enabled = ColgramAiConfig.isVoiceOverEnabled(ctx);
        boolean speaking = ColgramKolitiVoice.isSpeakingNow();
        boolean cloneConfigured = ColgramAiConfig.hasVoiceCloneServer(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        if (speaking) {
            voiceOverPill.setText("🔊 Говорит… ⏹");
            voiceOverPill.setTextColor(Color.WHITE);
            bg.setColor(ACCENT_EMERALD);
            bg.setStroke(dp(1), 0x8834D399);
        } else if (enabled) {
            voiceOverPill.setText(cloneConfigured ? "🔊 Клон голоса" : "🔊 Озвучка");
            voiceOverPill.setTextColor(isDark ? 0xFF34D399 : 0xFF047857);
            bg.setColor(isDark ? 0x2210B981 : 0x1610B981);
            bg.setStroke(dp(1), 0x5510B981);
        } else {
            voiceOverPill.setText("🔇 Озвучка");
            voiceOverPill.setTextColor(cTextSecondary);
            bg.setColor(cChipBg);
            bg.setStroke(dp(1), cBorder);
        }
        voiceOverPill.setBackground(bg);
    }

    private void applyTelemetryFooter(TextView foot, String rawFooter, Activity act) {
        if (foot == null) return;
        if (rawFooter == null || rawFooter.trim().isEmpty()) {
            foot.setVisibility(View.GONE);
            return;
        }
        String tps = extractRegexGroup(rawFooter, "\\(([0-9.]+)\\s*tok/s\\)");
        String genTok = extractRegexGroup(rawFooter, "gen\\s+([0-9]+)\\s*tok");
        String display;
        if (!tps.isEmpty() && !genTok.isEmpty()) {
            display = "⚡ " + tps + " tok/s  ·  " + genTok + " tok  ·  ⓘ Инфо";
        } else {
            display = "✦ " + shorten(rawFooter.trim(), 40) + "  ·  ⓘ";
        }
        foot.setVisibility(View.VISIBLE);
        foot.setText(display);
        foot.setTextColor(isDark ? 0xFF94A3B8 : 0xFF475569);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(isDark ? 0x16FFFFFF : 0x0C0F172A);
        bg.setStroke(dp(1), isDark ? 0x22FFFFFF : 0x180F172A);
        foot.setBackground(bg);
        ColgramMenuChrome.attachSpringPress(foot);
        final String capturedRaw = rawFooter;
        foot.setOnClickListener(v -> {
            Activity host = act != null ? act : AndroidUtilities.findActivity(foot.getContext());
            if (host != null) showKolitiInfoSheet(host, capturedRaw);
        });
    }

    private static String extractRegexGroup(String src, String regex) {
        if (src == null || src.isEmpty()) return "";
        try {
            Matcher m = Pattern.compile(regex).matcher(src);
            if (m.find() && m.groupCount() >= 1) {
                return m.group(1);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * Flagship frosted-glass Koliti Info & Telemetry BottomSheet showing live speed,
     * MAX reasoning state, voice engine, memory/sleep status, and hardware telemetry.
     */
    private void showKolitiInfoSheet(Activity act, String rawTelemetry) {
        if (act == null || act.isFinishing()) return;
        BottomSheet infoSheet = new BottomSheet(act, false);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(18) + AndroidUtilities.navigationBarHeight);
        ColgramAeroStyler.applyFlagshipSheetChrome(infoSheet, box, pal);

        // Top handle
        View handle = new View(act);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(isDark ? 0x38FFFFFF : 0x260F172A);
        hBg.setCornerRadius(dp(99));
        handle.setBackground(hBg);
        box.addView(handle, LayoutHelper.createLinear(40, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // Hero Header Card with Glowing Avatar + Live RAM Pill
        LinearLayout hero = new LinearLayout(act);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable heroBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                isDark ? new int[]{0x2CE11D48, 0x1E6366F1} : new int[]{0x18E11D48, 0x126366F1}
        );
        heroBg.setCornerRadius(dp(20));
        heroBg.setStroke(dp(1), isDark ? 0x44FB7185 : 0x33E11D48);
        hero.setBackground(heroBg);
        box.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        FrameLayout avaRing = new FrameLayout(act);
        GradientDrawable ringBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT_CRIMSON, ACCENT_AMBER}
        );
        ringBg.setShape(GradientDrawable.OVAL);
        avaRing.setBackground(ringBg);
        avaRing.setPadding(dp(2), dp(2), dp(2), dp(2));
        ImageView ava = new ImageView(act);
        ColgramAeroStyler.applyKolitiAvatar(ava);
        ava.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avaRing.addView(ava, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        hero.addView(avaRing, LayoutHelper.createLinear(46, 46, 0, 0, 12, 0));

        LinearLayout heroTxt = new LinearLayout(act);
        heroTxt.setOrientation(LinearLayout.VERTICAL);
        hero.addView(heroTxt, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView heroTitle = new TextView(act);
        heroTitle.setText(org.colgram.core.ColgramDesignConfig.getKolitiDisplayName() + " Core Ultra");
        heroTitle.setTextColor(cTextPrimary);
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f);
        heroTitle.setTypeface(AndroidUtilities.bold());
        heroTxt.addView(heroTitle);

        boolean loaded = ColgramAiEngine.getInstance().isModelLoaded();
        TextView stateBadge = new TextView(act);
        stateBadge.setText(loaded ? "● Активна в RAM · Готова" : "● Глубокий сон · 0 МБ / 0% CPU");
        stateBadge.setTextColor(loaded ? (isDark ? 0xFF34D399 : 0xFF047857) : (isDark ? 0xFF93C5FD : 0xFF1D4ED8));
        stateBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        stateBadge.setTypeface(AndroidUtilities.bold());
        heroTxt.addView(stateBadge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        // Parse live telemetry numbers
        String tps = extractRegexGroup(rawTelemetry, "\\(([0-9.]+)\\s*tok/s\\)");
        String genTok = extractRegexGroup(rawTelemetry, "gen\\s+([0-9]+)\\s*tok");
        String ttftMs = extractRegexGroup(rawTelemetry, "ttft\\s+([0-9.]+)ms");
        String prefillMs = extractRegexGroup(rawTelemetry, "prefill\\s+([0-9.]+)ms");
        String reuseTok = extractRegexGroup(rawTelemetry, "reuse\\s+([0-9]+)tok");
        String latencySrc = !ttftMs.isEmpty() ? ttftMs : prefillMs;
        String prefillFmt = "";
        if (!latencySrc.isEmpty()) {
            try {
                double ms = Double.parseDouble(latencySrc);
                prefillFmt = ms >= 1000.0 ? String.format(Locale.US, "%.1f с", ms / 1000.0) : (Math.round(ms) + " мс");
            } catch (Throwable ignored) {}
        }

        // 2x2 Flagship Metric Grid
        boolean thinkModeOn = ColgramAiConfig.isThinkingEnabled(act);
        LinearLayout row1 = new LinearLayout(act);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(makeInfoMetricTile(act, "⚡ Скорость", tps.isEmpty() ? "Локально" : (tps + " tok/s"),
                genTok.isEmpty() ? "Офлайн ядро" : (genTok + " токенов"), ACCENT_AMBER),
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));
        row1.addView(makeInfoMetricTile(act, "🧠 Режим", thinkModeOn ? "Мышление" : "Турбо",
                (thinkModeOn ? "CoT" : "Мгновенный") + " · Q" + ColgramAiConfig.getKvQuantBits(act), ACCENT_CRIMSON),
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        box.addView(row1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        boolean voiceOn = ColgramAiConfig.isVoiceOverEnabled(act);
        LinearLayout row2 = new LinearLayout(act);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(makeInfoMetricTile(act, "🎙 Озвучка", voiceOn ? "Включена" : "Выключена",
                ColgramKolitiVoice.getVoiceEngineSummary(act), ACCENT_EMERALD),
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));
        row2.addView(makeInfoMetricTile(act, "⏱ TTFT / KV", prefillFmt.isEmpty() ? "Готово" : prefillFmt,
                reuseTok.isEmpty() ? "FlashAttention" : ("Повтор кэша: " + reuseTok + " tok"), 0xFF3B82F6),
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        box.addView(row2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Structured Glass Spec Details Card
        LinearLayout specCard = new LinearLayout(act);
        specCard.setOrientation(LinearLayout.VERTICAL);
        specCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable specBg = new GradientDrawable();
        specBg.setColor(cCard);
        specBg.setCornerRadius(dp(18));
        specBg.setStroke(dp(1), cBorder);
        specCard.setBackground(specBg);

        int sleepSec = ColgramAiConfig.getIdleUnloadSeconds(act);
        String sleepLabel = sleepSec <= 0 ? "Отключён" : (sleepSec + " сек (0 МБ в простое)");
        String ctxLabel = (this.chatActivity != null && attachActiveChatContext)
                ? ("Чат: " + shorten(ColgramAiChatBridge.resolveChatTitle(this.chatActivity), 24))
                : "Свободный (изолированный)";
        String nativeCpu = org.colgram.core.ColgramNativeLlama.getSystemInfoSafe();
        if (nativeCpu.length() > 46) nativeCpu = nativeCpu.substring(0, 46) + "…";

        addInfoSpecRow(specCard, "Ядро ИИ", "Koliti Core Ultra · Текст + Vision", true);
        addInfoSpecRow(specCard, "Автосон RAM", sleepLabel, true);
        addInfoSpecRow(specCard, "Режим контекста", ctxLabel, true);
        addInfoSpecRow(specCard, "Язык диалога", "ru".equalsIgnoreCase(detectedUserLang) ? "Русский (RU)" : "English (EN)", true);
        addInfoSpecRow(specCard, "Движок CPU", nativeCpu, false);
        box.addView(specCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Action Buttons
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView studioOpenBtn = new TextView(act);
        studioOpenBtn.setText("Открыть Koliti Studio");
        studioOpenBtn.setGravity(Gravity.CENTER);
        studioOpenBtn.setTextColor(Color.WHITE);
        studioOpenBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        studioOpenBtn.setTypeface(AndroidUtilities.bold());
        GradientDrawable stBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT_CRIMSON, ACCENT_CORAL});
        stBg.setCornerRadius(dp(14));
        studioOpenBtn.setBackground(stBg);
        ColgramMenuChrome.attachSpringPress(studioOpenBtn);
        studioOpenBtn.setOnClickListener(v -> {
            infoSheet.dismiss();
            sheet.dismiss();
            if (parentFragment != null) {
                parentFragment.presentFragment(new ColgramAiActivity());
            }
        });
        btns.addView(studioOpenBtn, LayoutHelper.createLinear(0, 44, 1.4f, 0, 0, 8, 0));

        TextView closeInfoBtn = new TextView(act);
        closeInfoBtn.setText("Закрыть");
        closeInfoBtn.setGravity(Gravity.CENTER);
        closeInfoBtn.setTextColor(cTextPrimary);
        closeInfoBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        closeInfoBtn.setTypeface(AndroidUtilities.bold());
        GradientDrawable clBg = new GradientDrawable();
        clBg.setColor(cChipBg);
        clBg.setCornerRadius(dp(14));
        clBg.setStroke(dp(1), cBorder);
        closeInfoBtn.setBackground(clBg);
        ColgramMenuChrome.attachSpringPress(closeInfoBtn);
        closeInfoBtn.setOnClickListener(v -> infoSheet.dismiss());
        btns.addView(closeInfoBtn, LayoutHelper.createLinear(0, 44, 1f));
        box.addView(btns, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        infoSheet.setCustomView(box);
        infoSheet.show();
    }

    private LinearLayout makeInfoMetricTile(Activity act, String label, String value, String sub, int accentColor) {
        LinearLayout tile = new LinearLayout(act);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isDark ? ((accentColor & 0x00FFFFFF) | 0x1A000000) : ((accentColor & 0x00FFFFFF) | 0x10000000));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), (accentColor & 0x00FFFFFF) | (isDark ? 0x44000000 : 0x30000000));
        tile.setBackground(bg);

        TextView lTv = new TextView(act);
        lTv.setText(label);
        lTv.setTextColor(cTextSecondary);
        lTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        lTv.setTypeface(AndroidUtilities.bold());
        lTv.setMaxLines(1);
        tile.addView(lTv);

        TextView vTv = new TextView(act);
        vTv.setText(value);
        vTv.setTextColor(cTextPrimary);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        vTv.setTypeface(AndroidUtilities.bold());
        vTv.setMaxLines(1);
        vTv.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(vTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 1));

        TextView sTv = new TextView(act);
        sTv.setText(sub);
        sTv.setTextColor(cTextSecondary);
        sTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        sTv.setMaxLines(1);
        sTv.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(sTv);
        return tile;
    }

    private void addInfoSpecRow(LinearLayout parent, String key, String value, boolean withDivider) {
        Context ctx = parent.getContext();
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));

        TextView kTv = new TextView(ctx);
        kTv.setText(key);
        kTv.setTextColor(cTextSecondary);
        kTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        r.addView(kTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));

        TextView vTv = new TextView(ctx);
        vTv.setText(value);
        vTv.setTextColor(cTextPrimary);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        vTv.setTypeface(AndroidUtilities.bold());
        vTv.setGravity(Gravity.END);
        vTv.setMaxLines(1);
        vTv.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(vTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        parent.addView(r, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (withDivider) {
            View div = new View(ctx);
            div.setBackgroundColor(cBorder);
            parent.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        }
    }

    private void maybeSendWelcomeGreeting(Activity act, boolean autoPlay) {
        if (act == null || cleanedUp.get() || !sheet.isShowing() || activeSession == null || !activeSession.messages.isEmpty()) return;
        if (emptyStateView != null) {
            messagesContainer.removeView(emptyStateView);
            emptyStateView = null;
        }
        boolean isRu = "ru".equalsIgnoreCase(detectedUserLang);
        String tag = isRu ? ColgramKolitiVoice.MEDIA_TAG_GREET_RU : ColgramKolitiVoice.MEDIA_TAG_GREET_EN;
        int rawRes = isRu ? R.raw.koliti_greet_ru : R.raw.koliti_greet_en;
        String caption = isRu
                ? "Хей, привет! Я Колити — твой друг-помощник. Спрашивай что угодно!"
                : "Hello! I'm Koliti — your helpful friend. Ask me everything!";
        String footer = isRu ? "Koliti • Голосовое приветствие (RU)" : "Koliti • Voice Greeting (EN)";
        ColgramAiChatStore.appendMessage(activeSession.id, "assistant", caption, footer,
                java.util.Collections.singletonList(tag));
        addKolitiAudioBubble(false, caption, footer, tag, rawRes, null, autoPlay);
    }

    private static String findKolitiAudioTag(List<String> mediaPaths) {
        if (mediaPaths == null) return null;
        for (String p : mediaPaths) {
            if (p == null) continue;
            if (ColgramKolitiVoice.MEDIA_TAG_SONG.equals(p)
                    || ColgramKolitiVoice.MEDIA_TAG_GREET_RU.equals(p)
                    || ColgramKolitiVoice.MEDIA_TAG_GREET_EN.equals(p)
                    || p.startsWith(ColgramKolitiVoice.MEDIA_PREFIX_AUDIO_FILE)) {
                return p;
            }
        }
        return null;
    }

    private void renderTaggedAssistantAudioBubble(String text, String footer, String tag, boolean autoPlay) {
        if (ColgramKolitiVoice.MEDIA_TAG_SONG.equals(tag)) {
            addKolitiAudioBubble(false, text, footer, tag, R.raw.koliti_song, null, autoPlay);
        } else if (ColgramKolitiVoice.MEDIA_TAG_GREET_RU.equals(tag)) {
            addKolitiAudioBubble(false, text, footer, tag, R.raw.koliti_greet_ru, null, autoPlay);
        } else if (ColgramKolitiVoice.MEDIA_TAG_GREET_EN.equals(tag)) {
            addKolitiAudioBubble(false, text, footer, tag, R.raw.koliti_greet_en, null, autoPlay);
        } else if (tag.startsWith(ColgramKolitiVoice.MEDIA_PREFIX_AUDIO_FILE)) {
            String path = tag.substring(ColgramKolitiVoice.MEDIA_PREFIX_AUDIO_FILE.length());
            addKolitiAudioBubble(false, text, footer, tag, 0, path, autoPlay);
        }
    }

    private TextView addKolitiAudioBubble(
            boolean fromUser,
            String caption,
            String footer,
            String baseKey,
            int rawResId,
            String audioFilePath,
            boolean autoPlay
    ) {
        Context ctx = messagesContainer.getContext();
        final String playKey = baseKey + "_" + System.identityHashCode(new Object());

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(fromUser ? Gravity.END : Gravity.TOP);

        if (!fromUser) {
            ImageView miniAva = new ImageView(ctx);
            ColgramAeroStyler.applyKolitiAvatar(miniAva);
            miniAva.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(miniAva, LayoutHelper.createLinear(28, 28, 0, 2, 10, 0));
        }

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(13), dp(10));
        GradientDrawable bg = new GradientDrawable();
        if (fromUser) {
            bg.setColor(ACCENT_CRIMSON);
            bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), dp(6), dp(6), dp(20), dp(20)});
        } else {
            bg.setColor(cCard);
            bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), dp(18), dp(18), dp(6), dp(6)});
            bg.setStroke(dp(1), 0x44E11D48);
        }
        card.setBackground(bg);
        if (!isDark && !fromUser) card.setElevation(dp(1));

        LinearLayout playerRow = new LinearLayout(ctx);
        playerRow.setOrientation(LinearLayout.HORIZONTAL);
        playerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(playerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ImageView playBtn = makeIconBtn(
                ctx,
                R.drawable.ic_play,
                fromUser ? 0x33FFFFFF : ACCENT_CRIMSON,
                Color.WHITE
        );
        playerRow.addView(playBtn, LayoutHelper.createLinear(38, 38, 0, 0, 10, 0));

        LinearLayout waveCol = new LinearLayout(ctx);
        waveCol.setOrientation(LinearLayout.VERTICAL);
        playerRow.addView(waveCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final VoiceWaveformView waveform = new VoiceWaveformView(
                ctx,
                fromUser ? Color.WHITE : ACCENT_CRIMSON,
                fromUser ? 0x55FFFFFF : (isDark ? 0x40FFFFFF : 0x260F172A),
                Math.abs(baseKey.hashCode())
        );
        waveCol.addView(waveform, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24));

        final TextView timerView = new TextView(ctx);
        String defaultTimerLabel;
        if (rawResId == R.raw.koliti_song) {
            defaultTimerLabel = "🎵 0:15 • Песня Koliti";
        } else if (fromUser) {
            defaultTimerLabel = (footer != null && !footer.trim().isEmpty()) ? footer.trim() : "🎙 Ваше голосовое";
        } else {
            defaultTimerLabel = "🎙 Голосовое Koliti";
        }
        timerView.setText(defaultTimerLabel);
        timerView.setTextColor(fromUser ? 0xF2FFFFFF : cTextSecondary);
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        timerView.setMaxLines(1);
        timerView.setEllipsize(TextUtils.TruncateAt.END);
        waveCol.addView(timerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        TextView capTv = null;
        if (caption != null && !caption.trim().isEmpty()) {
            capTv = new TextView(ctx);
            capTv.setText(caption);
            capTv.setTextColor(fromUser ? Color.WHITE : cTextPrimary);
            capTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            capTv.setLineSpacing(dp(2.5f), 1f);
            card.addView(capTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        if (footer != null && !footer.trim().isEmpty() && !fromUser) {
            TextView footTv = new TextView(ctx);
            footTv.setText(footer);
            footTv.setTextColor(cTextSecondary);
            footTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
            card.addView(footTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));
        }

        final ColgramKolitiVoice.PlaybackListener listener = (k, playing, posMs, durMs) -> {
            if (!playKey.equals(k)) return;
            playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            float progress = durMs > 0 ? Math.min(1f, Math.max(0f, (float) posMs / (float) durMs)) : 0f;
            waveform.setProgress(playing ? progress : 0f, playing);
            if (durMs > 0) {
                timerView.setText(ColgramKolitiVoice.formatDurationMs(posMs) + " / " + ColgramKolitiVoice.formatDurationMs(durMs));
            }
        };

        View.OnClickListener toggleClick = v -> {
            if (rawResId != 0) {
                ColgramKolitiVoice.toggleRawPlayback(ctx, playKey, rawResId, listener);
            } else if (audioFilePath != null && !audioFilePath.isEmpty()) {
                ColgramKolitiVoice.toggleFilePlayback(playKey, audioFilePath, listener);
            }
        };
        playBtn.setOnClickListener(toggleClick);
        playerRow.setOnClickListener(toggleClick);

        if (fromUser) {
            // CRITICAL: LayoutHelper.createLinear ALREADY scales dp -> px! Passing dp(236) previously
            // caused double-dp multiplication (2124px wide on 3x phones, clipping the entire bubble)!
            float screenDp = AndroidUtilities.displaySize.x > 0 && AndroidUtilities.density > 0f
                    ? (AndroidUtilities.displaySize.x / AndroidUtilities.density) : 360f;
            int userCardDp = Math.min(256, Math.max(198, (int) screenDp - 80));
            row.addView(card, LayoutHelper.createLinear(userCardDp, LayoutHelper.WRAP_CONTENT));
            animateBubbleEntrance(row, true);
            messagesContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 40, 4, 0, 12));
        } else {
            row.addView(card, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            animateBubbleEntrance(row, false);
            messagesContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 6, 12));
        }
        scrollToBottomSmooth();

        if (autoPlay) {
            if (rawResId != 0) {
                ColgramKolitiVoice.playRawResource(ctx, playKey, rawResId, listener);
            } else if (audioFilePath != null && !audioFilePath.isEmpty()) {
                ColgramKolitiVoice.playAudioFile(playKey, audioFilePath, listener);
            }
        }
        return capTv;
    }

    private void toggleVoiceInput(Activity act) {
        if (act == null) return;
        if (recordingVoiceNote) {
            toggleDirectVoiceRecording(act);
            return;
        }
        if (listeningSpeech) {
            stopVoiceRecognition();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 6410);
            Toast.makeText(act, "Разрешите микрофон, чтобы говорить с Koliti", Toast.LENGTH_SHORT).show();
            return;
        }
        ColgramKolitiVoice.stopPlayback();
        ColgramKolitiVoice.stopSpeaking();

        // For Russian (default), go straight to our built-in 100% offline Vosk Zipformer voice-note pipeline:
        // records a playable ГС bubble, transcribes offline (~200 ms), and Koliti replies immediately with zero popups.
        if ("ru".equals(detectedUserLang)) {
            toggleDirectVoiceRecording(act);
            return;
        }

        if (SpeechRecognizer.isRecognitionAvailable(act)) {
            startLiveSpeechRecognition(act);
            return;
        }
        // Fallback 1: System voice recognition Activity if present
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru".equals(detectedUserLang) ? "ru-RU" : "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите с Koliti…");
            if (intent.resolveActivity(act.getPackageManager()) != null) {
                ColgramAiActivity.pendingSheetForVoice = this;
                if (parentFragment != null) {
                    parentFragment.startActivityForResult(intent, ColgramAiActivity.REQ_VOICE_RECOGNIZE);
                } else {
                    act.startActivityForResult(intent, ColgramAiActivity.REQ_VOICE_RECOGNIZE);
                }
                return;
            }
        } catch (Throwable ignored) {}
        // Fallback 2: Direct offline voice note recording
        toggleDirectVoiceRecording(act);
    }

    private void startLiveSpeechRecognition(Activity act) {
        try {
            stopVoiceRecognition();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(act);
            listeningSpeech = true;
            updateMicButtonUi();
            statusLine.setText("🎙 Слушаю вас…");

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru".equals(detectedUserLang) ? "ru-RU" : "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    AndroidUtilities.runOnUIThread(() -> statusLine.setText("🎙 Говорите…"));
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    AndroidUtilities.runOnUIThread(() -> statusLine.setText("Распознаю речь…"));
                }
                @Override public void onError(int error) {
                    AndroidUtilities.runOnUIThread(() -> {
                        stopVoiceRecognition();
                        statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            Toast.makeText(act, "Речь не распознана — попробуйте ещё раз", Toast.LENGTH_SHORT).show();
                        } else {
                            // Offer direct recording if device lacks online/offline speech engine
                            toggleDirectVoiceRecording(act);
                        }
                    });
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                    AndroidUtilities.runOnUIThread(() -> {
                        stopVoiceRecognition();
                        if (matches != null && !matches.isEmpty() && !TextUtils.isEmpty(matches.get(0))) {
                            onVoiceRecognizedResult(matches.get(0));
                        } else {
                            statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
                        }
                    });
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial = partialResults != null ? partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                    if (partial != null && !partial.isEmpty() && !TextUtils.isEmpty(partial.get(0))) {
                        String live = partial.get(0);
                        AndroidUtilities.runOnUIThread(() -> {
                            promptInput.setText(live);
                            promptInput.setSelection(live.length());
                        });
                    }
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            speechRecognizer.startListening(intent);
        } catch (Throwable err) {
            stopVoiceRecognition();
            toggleDirectVoiceRecording(act);
        }
    }

    public void onVoiceRecognizedResult(String spokenText) {
        if (spokenText == null || spokenText.trim().isEmpty()) return;
        String clean = spokenText.trim();
        promptInput.setText("");
        if (!busyGenerating && !ColgramAiEngine.getInstance().isBusy()) {
            submitPrompt(clean);
        } else {
            promptInput.setText(clean);
            promptInput.setSelection(clean.length());
        }
    }

    private void toggleDirectVoiceRecording(Activity act) {
        if (act == null) return;
        if (!recordingVoiceNote) {
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 6410);
                Toast.makeText(act, "Разрешите доступ к микрофону", Toast.LENGTH_SHORT).show();
                return;
            }
            stopVoiceRecognition();
            File dir = new File(act.getFilesDir(), "koliti_voice_notes");
            File out = new File(dir, "voice_" + System.currentTimeMillis() + ".m4a");
            if (ColgramKolitiVoice.startRecording(out)) {
                recordingVoiceNote = true;
                voiceRecordStartMs = System.currentTimeMillis();
                // Pre-warm BOTH offline ASR & local Koliti LLM while the user is speaking so
                // Koliti starts streaming his reply immediately after release!
                ColgramKolitiVoice.warmUpOfflineAsrAsync(act);
                ColgramAiEngine.getInstance().warmUpAsync(act);
                updateMicButtonUi();
                statusLine.setText("🔴 Запись голоса… Нажмите микрофон или Отправить");
            } else {
                Toast.makeText(act, "Микрофон недоступен на этом устройстве", Toast.LENGTH_SHORT).show();
            }
        } else {
            recordingVoiceNote = false;
            long recDurMs = voiceRecordStartMs > 0L ? Math.max(500L, System.currentTimeMillis() - voiceRecordStartMs) : 0L;
            voiceRecordStartMs = 0L;
            updateMicButtonUi();
            File recorded = ColgramKolitiVoice.stopRecording();
            if (recorded != null && recorded.isFile()) {
                if (emptyStateView != null) {
                    messagesContainer.removeView(emptyStateView);
                    emptyStateView = null;
                }
                final String tag = ColgramKolitiVoice.MEDIA_PREFIX_AUDIO_FILE + recorded.getAbsolutePath();
                String durTitle = recDurMs > 0L
                        ? ("🎙 " + ColgramKolitiVoice.formatDurationMs((int) recDurMs) + " • Ваше ГС")
                        : "🎙 Ваше голосовое";
                final TextView captionView = addKolitiAudioBubble(
                        true,
                        "Распознаю речь…",
                        durTitle,
                        tag,
                        0,
                        recorded.getAbsolutePath(),
                        false
                );
                // Show Koliti's live typing card IMMEDIATELY so the user sees instant responsiveness!
                final boolean canStreamNow = !busyGenerating && !ColgramAiEngine.getInstance().isBusy();
                final View[] preRow = new View[1];
                final TextView[] preAns = new TextView[1];
                final TextView[] preFoot = new TextView[1];
                final LinearLayout[] preAct = new LinearLayout[1];
                final View[] preWave = new View[1];
                if (canStreamNow) {
                    createStreamingAssistantCard(preRow, preAns, preFoot, preAct, preWave, true);
                    setBusyUi(true);
                }
                statusLine.setText("🎙 Слушаю ваше ГС…");
                setOrbState(ColgramAiOrbView.State.THINKING);
                // 100% offline Vosk Zipformer ASR — Koliti hears and replies immediately!
                ColgramKolitiVoice.transcribeAudioFileAsync(act, recorded, transcript -> AndroidUtilities.runOnUIThread(() -> {
                    if (cleanedUp.get() || !sheet.isShowing()) return;
                    String clean = transcript == null ? "" : transcript.trim();
                    if (!clean.isEmpty()) {
                        String bubbleCap = "«" + clean + "»";
                        if (captionView != null) {
                            captionView.setText(bubbleCap);
                        }
                        if (activeSession != null) {
                            ColgramAiChatStore.appendMessage(activeSession.id, "user", bubbleCap, durTitle,
                                    java.util.Collections.singletonList(tag));
                            updateHeaderForSession();
                        }
                        if (canStreamNow && preAns[0] != null) {
                            submitPromptWithCard(clean, false, preRow[0], preAns[0], preFoot[0], preAct[0], preWave[0]);
                        } else if (!busyGenerating && !ColgramAiEngine.getInstance().isBusy()) {
                            submitPromptInternal(clean, false);
                        } else {
                            promptInput.setText(clean);
                            promptInput.setSelection(clean.length());
                            statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
                        }
                    } else {
                        String fallbackCap = "Голосовое сообщение (речь не распознана)";
                        if (captionView != null) {
                            captionView.setText(fallbackCap);
                        }
                        if (activeSession != null) {
                            ColgramAiChatStore.appendMessage(activeSession.id, "user", fallbackCap, durTitle,
                                    java.util.Collections.singletonList(tag));
                            updateHeaderForSession();
                        }
                        if (canStreamNow && preAns[0] != null) {
                            if (preWave[0] != null) preWave[0].setVisibility(View.GONE);
                            preAns[0].setVisibility(View.VISIBLE);
                            preAns[0].setText("Не расслышал голосовое — попробуй сказать чуть громче или напиши текстом.");
                            setBusyUi(false);
                        }
                        setOrbState(ColgramAiOrbView.State.IDLE);
                        statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
                    }
                }));
            } else {
                statusLine.setText(ColgramAiEngine.getInstance().buildStatusSummary(act));
            }
        }
    }

    private void stopVoiceRecognition() {
        listeningSpeech = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Throwable ignored) {}
            try { speechRecognizer.cancel(); } catch (Throwable ignored) {}
            try { speechRecognizer.destroy(); } catch (Throwable ignored) {}
            speechRecognizer = null;
        }
        updateMicButtonUi();
    }

    private void updateMicButtonUi() {
        if (micButton == null) return;
        boolean active = listeningSpeech || recordingVoiceNote;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(active ? ACCENT_CRIMSON : cChipBg);
        if (!active) gd.setStroke(1, cBorder);
        micButton.setBackground(gd);
        micButton.setColorFilter(active ? Color.WHITE : cIconMuted, PorterDuff.Mode.SRC_IN);
    }

    private static final class VoiceWaveformView extends View {
        private static final int BAR_COUNT = 22;
        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF barRect = new RectF();
        private final float[] heights = new float[BAR_COUNT];
        private float progress = 0f;
        private boolean animating = false;

        VoiceWaveformView(Context ctx, int activeColor, int idleColor, int seed) {
            super(ctx);
            activePaint.setColor(activeColor);
            idlePaint.setColor(idleColor);
            int s = seed == 0 ? 17 : seed;
            for (int i = 0; i < BAR_COUNT; i++) {
                s = (s * 1103515245 + 12345) & 0x7fffffff;
                float env = (float) Math.sin(Math.PI * (i + 1.0) / (BAR_COUNT + 1.0));
                heights[i] = 0.24f + 0.72f * (0.4f * env + 0.6f * ((s % 100) / 100f));
            }
        }

        void setProgress(float p, boolean isPlaying) {
            this.progress = Math.max(0f, Math.min(1f, p));
            this.animating = isPlaying;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float gap = dp(2f);
            float barW = Math.max(1.5f, (w - gap * (BAR_COUNT - 1)) / (float) BAR_COUNT);
            float r = barW * 0.5f;
            float cy = h * 0.5f;
            long now = animating ? android.os.SystemClock.uptimeMillis() : 0L;
            for (int i = 0; i < BAR_COUNT; i++) {
                float frac = (float) i / (float) BAR_COUNT;
                float amp = heights[i];
                if (animating) {
                    float wave = (float) (0.14 * Math.sin(now * 0.012 + i * 0.65));
                    amp = Math.max(0.22f, Math.min(1f, amp + wave));
                }
                float bh = Math.max(dp(4f), h * amp);
                float left = i * (barW + gap);
                barRect.set(left, cy - bh * 0.5f, left + barW, cy + bh * 0.5f);
                canvas.drawRoundRect(barRect, r, r, frac <= progress ? activePaint : idlePaint);
            }
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
