package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * ColgramQRLoginBottomSheet — Native Telegram QR Code Login
 *
 * Implements MTProto auth.exportLoginToken pairing flow.
 * Allows instant, 100% free login by scanning QR with any active Telegram session
 * (Settings -> Devices -> Link Desktop Device), bypassing SMS fees.
 */
public class ColgramQRLoginBottomSheet {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pollRunnable = null;
    private static boolean isDismissed = false;

    public static void show(final LoginActivity activity, final int currentAccount) {
        if (activity == null || activity.getParentActivity() == null) return;
        final Context context = activity.getParentActivity();

        isDismissed = false;
        final boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false);
        builder.setApplyTopPadding(false);

        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (accentColor == 0) accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
        if (accentColor == 0) accentColor = 0xFF2AABEE;

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        // Drag handle
        View pill = new View(context);
        pill.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(2.5f), 0x33888888, 0x55888888));
        container.addView(pill, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(isRu ? "Вход по QR-коду" : "Log in by QR Code");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        // Subtitle instructions
        TextView descView = new TextView(context);
        descView.setText(isRu
                ? "Откройте Telegram на другом устройстве:\nНастройки → Устройства → Подключить устройство\nи наведите камеру на этот экран."
                : "Open Telegram on your other device:\nSettings → Devices → Link Desktop Device\nand point your camera at this screen.");
        descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        descView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        descView.setGravity(Gravity.CENTER_HORIZONTAL);
        descView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        container.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // QR Frame with clean card background
        final FrameLayout qrFrame = new FrameLayout(context);
        qrFrame.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), 0xFFFFFFFF, 0xFFEEEEEE));
        qrFrame.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));

        final ImageView qrImageView = new ImageView(context);
        qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrFrame.addView(qrImageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Center logo overlay on QR
        final ImageView centerLogo = new ImageView(context);
        try {
            centerLogo.setImageResource(R.drawable.colgram_plane_splash);
        } catch (Throwable t) {
            centerLogo.setImageResource(R.mipmap.ic_launcher);
        }
        centerLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrFrame.addView(centerLogo, LayoutHelper.createFrame(44, 44, Gravity.CENTER));

        final RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(36));
        progressView.setProgressColor(accentColor);
        qrFrame.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        container.addView(qrFrame, LayoutHelper.createLinear(240, 240, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Status / timer text
        final TextView statusText = new TextView(context);
        statusText.setText(isRu ? "Ожидание сканирования..." : "Waiting for scan...");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusText.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Cancel button (native Telegram secondary button)
        final TextView cancelBtn = new TextView(context);
        cancelBtn.setText(isRu ? "Отмена" : "Cancel");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        cancelBtn.setTypeface(AndroidUtilities.bold());
        int cancelColor = Theme.getColor(Theme.key_dialogTextBlack);
        if (cancelColor == 0) cancelColor = 0xFF222222;
        cancelBtn.setTextColor(cancelColor);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        cancelBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), 0x0C000000, 0x1A000000));
        container.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 0, 0, 4));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);
        builder.setCustomView(scrollView);

        final BottomSheet bottomSheet = builder.create();

        cancelBtn.setOnClickListener(v -> bottomSheet.dismiss());

        bottomSheet.setOnDismissListener(dialog -> {
            isDismissed = true;
            if (pollRunnable != null) {
                handler.removeCallbacks(pollRunnable);
                pollRunnable = null;
            }
        });

        bottomSheet.show();

        // Start MTProto QR Export Flow
        startQrExport(activity, currentAccount, bottomSheet, qrImageView, progressView, centerLogo, statusText, isRu);
    }

    private static void startQrExport(final LoginActivity activity, final int currentAccount,
                                      final BottomSheet bottomSheet,
                                      final ImageView qrImageView,
                                      final RadialProgressView progressView,
                                      final ImageView centerLogo,
                                      final TextView statusText,
                                      final boolean isRu) {
        if (isDismissed || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.except_ids = new ArrayList<>();

        int flags = ConnectionsManager.RequestFlagEnableUnauthorized
                | ConnectionsManager.RequestFlagFailOnServerErrors
                | ConnectionsManager.RequestFlagWithoutLogin;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isDismissed || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

            if (error == null && response != null) {
                if (response instanceof TLRPC.TL_auth_loginToken) {
                    TLRPC.TL_auth_loginToken tokenObj = (TLRPC.TL_auth_loginToken) response;
                    byte[] token = tokenObj.token;
                    if (token != null && token.length > 0) {
                        String b64 = Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        String qrUri = "tg://login?token=" + b64;
                        generateAndDisplayQR(qrUri, qrImageView, progressView, centerLogo);
                        statusText.setText(isRu ? "Наведите камеру на QR-код" : "Scan QR code with Telegram");

                        // Schedule next poll check
                        schedulePoll(activity, currentAccount, bottomSheet, qrImageView, progressView, centerLogo, statusText, isRu);
                    }
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
                    ConnectionsManager.getInstance(currentAccount).setDefaultDatacenterId(migrate.dc_id);
                    startQrExport(activity, currentAccount, bottomSheet, qrImageView, progressView, centerLogo, statusText, isRu);
                } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onSuccess(activity, (TLRPC.TL_auth_loginTokenSuccess) response, bottomSheet, isRu);
                }
            } else {
                String err = (error != null && error.text != null) ? error.text : "UNKNOWN";
                FileLog.e("QR export error: " + err);
                if (!isDismissed) {
                    // Retry after 3 seconds on failure
                    schedulePoll(activity, currentAccount, bottomSheet, qrImageView, progressView, centerLogo, statusText, isRu);
                }
            }
        }), flags);
    }

    private static void schedulePoll(final LoginActivity activity, final int currentAccount,
                                     final BottomSheet bottomSheet,
                                     final ImageView qrImageView,
                                     final RadialProgressView progressView,
                                     final ImageView centerLogo,
                                     final TextView statusText,
                                     final boolean isRu) {
        if (isDismissed || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
        pollRunnable = () -> {
            if (!isDismissed && activity.getParentActivity() != null && !activity.getParentActivity().isFinishing()) {
                startQrExport(activity, currentAccount, bottomSheet, qrImageView, progressView, centerLogo, statusText, isRu);
            }
        };
        handler.postDelayed(pollRunnable, 2500);
    }

    private static void generateAndDisplayQR(final String content,
                                             final ImageView qrImageView,
                                             final RadialProgressView progressView,
                                             final ImageView centerLogo) {
        new Thread(() -> {
            try {
                HashMap<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                hints.put(EncodeHintType.MARGIN, 0);

                TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
                final Bitmap qrBitmap = writer.encode(content, 640, 640, hints, null, 1.0f, 0xFFFFFFFF, 0xFF000000);

                AndroidUtilities.runOnUIThread(() -> {
                    if (qrBitmap != null) {
                        qrImageView.setImageBitmap(qrBitmap);
                        progressView.setVisibility(View.GONE);
                        centerLogo.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }).start();
    }

    private static void onSuccess(final LoginActivity activity,
                                  final TLRPC.TL_auth_loginTokenSuccess success,
                                  final BottomSheet bottomSheet,
                                  final boolean isRu) {
        isDismissed = true;
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }

        try {
            bottomSheet.dismiss();
        } catch (Throwable ignored) {}

        Toast.makeText(activity.getParentActivity(), isRu ? "Авторизация успешна!" : "Login successful!", Toast.LENGTH_SHORT).show();

        try {
            activity.onAuthSuccess((TLRPC.TL_auth_authorization) success.authorization);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
