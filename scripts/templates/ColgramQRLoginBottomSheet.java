package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
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
import org.telegram.messenger.Utilities;
import android.os.Bundle;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * ColgramQRLoginBottomSheet — Native Telegram QR Code Login
 *
 * Implements MTProto auth.exportLoginToken / updateLoginToken / importLoginToken flow.
 * Allows instant, 100% free login by scanning QR with any active Telegram session
 * (Settings -> Devices -> Link Desktop Device), bypassing SMS fees.
 */
public class ColgramQRLoginBottomSheet {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static ColgramQRLoginBottomSheet activeInstance = null;

    private final LoginActivity activity;
    private final int currentAccount;
    private final BottomSheet bottomSheet;
    private final ImageView qrImageView;
    private final RadialProgressView progressView;
    private final TextView statusText;
    private final boolean isRu;

    private Runnable refreshRunnable = null;
    private boolean isDismissed = false;
    private byte[] currentToken = null;

    public static void show(final LoginActivity activity, final int currentAccount) {
        if (activity == null || activity.getParentActivity() == null) return;
        final Context context = activity.getParentActivity();

        final boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false);
        builder.setApplyTopPadding(false);

        int accentColor = pal.accentPrimary;

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(14), AndroidUtilities.dp(24),
                AndroidUtilities.dp(22) + AndroidUtilities.navigationBarHeight);

        // Drag handle
        View pill = new View(context);
        pill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(2.5f), pal.dark ? 0x44FFFFFF : 0x300F172A));
        container.addView(pill, LayoutHelper.createLinear(40, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // Colgram QR badge
        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(AndroidUtilities.dp(24));
        badgeBg.setColor((accentColor & 0x00FFFFFF) | 0x1A000000);
        badgeBg.setStroke(AndroidUtilities.dp(1), (accentColor & 0x00FFFFFF) | 0x44000000);
        badge.setBackground(badgeBg);
        ImageView badgeIcon = new ImageView(context);
        badgeIcon.setImageResource(R.drawable.msg_qrcode);
        badgeIcon.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        badgeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        badge.addView(badgeIcon, LayoutHelper.createFrame(26, 26, Gravity.CENTER));
        container.addView(badge, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(isRu ? "Вход по QR-коду" : "Log in by QR Code");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(pal.textPrimary);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        // Subtitle instructions
        TextView descView = new TextView(context);
        descView.setText(isRu
                ? "Откройте Telegram на другом устройстве:\nНастройки → Устройства → Подключить устройство\nи наведите камеру на этот экран."
                : "Open Telegram on your other device:\nSettings → Devices → Link Desktop Device\nand point your camera at this screen.");
        descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        descView.setTextColor(pal.textSecondary);
        descView.setGravity(Gravity.CENTER_HORIZONTAL);
        descView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        container.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // QR Frame with crisp white border + elevation so it stands out cleanly in both Light & Dark themes
        final FrameLayout qrFrame = new FrameLayout(context);
        GradientDrawable qrCardBg = new GradientDrawable();
        qrCardBg.setColor(0xFFFFFFFF);
        qrCardBg.setCornerRadius(AndroidUtilities.dp(18));
        qrCardBg.setStroke(AndroidUtilities.dp(1), pal.dark ? 0x22FFFFFF : 0x220F172A);
        qrFrame.setBackground(qrCardBg);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            qrFrame.setElevation(AndroidUtilities.dp(4));
        }
        qrFrame.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));

        final ImageView qrImageView = new ImageView(context);
        qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrFrame.addView(qrImageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(36));
        progressView.setProgressColor(accentColor);
        qrFrame.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        int minScreen = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
        int qrSideDp = Math.max(176, Math.min(236, Math.round((minScreen * 0.56f) / Math.max(1f, AndroidUtilities.density))));
        container.addView(qrFrame, LayoutHelper.createLinear(qrSideDp, qrSideDp, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        // Status / hint text
        final TextView statusText = new TextView(context);
        statusText.setText(isRu ? "Генерация QR-кода..." : "Generating QR code...");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusText.setTextColor(pal.textSecondary);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // Check button (manual fallback — automatic confirmation runs silently in background)
        final LinearLayout checkBtn = new LinearLayout(context);
        checkBtn.setOrientation(LinearLayout.HORIZONTAL);
        checkBtn.setGravity(Gravity.CENTER);
        checkBtn.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(9), AndroidUtilities.dp(18), AndroidUtilities.dp(9));
        ColgramMenuChrome.attachTactileFeedback(checkBtn, 20,
                (accentColor & 0x00FFFFFF) | 0x1A000000,
                (accentColor & 0x00FFFFFF) | 0x55000000,
                (accentColor & 0x00FFFFFF) | 0x33000000);
        ImageView checkIcon = new ImageView(context);
        checkIcon.setImageResource(R.drawable.msg_retry);
        checkIcon.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
        checkIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        checkBtn.addView(checkIcon, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        final TextView checkLabel = new TextView(context);
        checkLabel.setText(isRu ? "Проверить сейчас" : "Check now");
        checkLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        checkLabel.setTypeface(AndroidUtilities.bold());
        checkLabel.setTextColor(accentColor);
        checkBtn.addView(checkLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        container.addView(checkBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // Cancel button
        final TextView cancelBtn = new TextView(context);
        cancelBtn.setText(isRu ? "Отмена" : "Cancel");
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        cancelBtn.setTypeface(AndroidUtilities.bold());
        cancelBtn.setTextColor(pal.textPrimary);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setMinHeight(AndroidUtilities.dp(44));
        cancelBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        ColgramMenuChrome.attachTactileFeedback(cancelBtn, 12,
                pal.chipSurface, pal.borderSubtle, pal.dark ? 0x26FFFFFF : 0x180F172A);
        container.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);
        builder.setCustomView(scrollView);

        final BottomSheet bottomSheet = builder.create();
        ColgramAeroStyler.applyFlagshipSheetChrome(bottomSheet, scrollView, pal);

        final ColgramQRLoginBottomSheet instance = new ColgramQRLoginBottomSheet(
                activity, currentAccount, bottomSheet, qrImageView, progressView, statusText, isRu);
        activeInstance = instance;

        // Refresh button is a manual fallback — automatic confirmation runs silently without blanking QR.
        checkBtn.setOnClickListener(v -> instance.onTokenScanned(false));
        cancelBtn.setOnClickListener(v -> bottomSheet.dismiss());

        bottomSheet.setOnDismissListener(dialog -> {
            instance.isDismissed = true;
            instance.cancelRefresh();
            instance.stopAutoConfirm();
            if (activeInstance == instance) {
                activeInstance = null;
            }
        });

        bottomSheet.show();

        // Start MTProto QR Export Flow (single call, no rapid polling)
        instance.startQrExport();

        // Automatic confirmation: poll the token status in the background so the user
        // never has to tap anything after scanning. The server push (updateLoginToken)
        // is the primary trigger; this poller is the safety net when push is delayed
        // or filtered by the proxy.
        instance.startAutoConfirm();
    }

    private Runnable autoConfirmRunnable = null;
    private int autoConfirmAttempts = 0;

    private void startAutoConfirm() {
        stopAutoConfirm();
        autoConfirmAttempts = 0;
        autoConfirmRunnable = () -> {
            if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) {
                return;
            }
            autoConfirmAttempts++;
            // Give up after ~2 minutes of polling to avoid battery drain.
            if (autoConfirmAttempts > 24) {
                return;
            }
            onTokenScanned(true);
            handler.postDelayed(autoConfirmRunnable, 5000L);
        };
        handler.postDelayed(autoConfirmRunnable, 5000L);
    }

    private void stopAutoConfirm() {
        if (autoConfirmRunnable != null) {
            handler.removeCallbacks(autoConfirmRunnable);
            autoConfirmRunnable = null;
        }
    }

    /**
     * Called whenever MTProto receives TL_updateLoginToken (push notification from server).
     */
    public static void onLoginTokenUpdate(final int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (activeInstance != null && !activeInstance.isDismissed && activeInstance.currentAccount == account) {
                activeInstance.onTokenScanned(false);
            }
        });
    }

    private ColgramQRLoginBottomSheet(LoginActivity activity, int currentAccount, BottomSheet bottomSheet,
                                      ImageView qrImageView, RadialProgressView progressView,
                                      TextView statusText, boolean isRu) {
        this.activity = activity;
        this.currentAccount = currentAccount;
        this.bottomSheet = bottomSheet;
        this.qrImageView = qrImageView;
        this.progressView = progressView;
        this.statusText = statusText;
        this.isRu = isRu;
    }

    private void showSpinner() {
        progressView.animate().cancel();
        progressView.setAlpha(1f);
        progressView.setVisibility(View.VISIBLE);
    }

    private void startQrExport() {
        if (isDismissed || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        if (qrImageView.getDrawable() == null) {
            showSpinner();
        }

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
                        currentToken = token;
                        String b64 = Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        String qrUri = "tg://login?token=" + b64;
                        generateAndDisplayQR(qrUri, qrImageView.getDrawable() == null);
                        statusText.setText(isRu ? "Наведите камеру Telegram на QR-код" : "Scan QR code with Telegram camera");

                        // Calculate validity time (default ~30s), refresh ONLY when expired
                        int waitSec = tokenObj.expires - ConnectionsManager.getInstance(currentAccount).getCurrentTime();
                        if (waitSec <= 5 || waitSec > 60) waitSec = 30;
                        scheduleRefresh((long) waitSec * 1000L);
                    }
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    handleMigration((TLRPC.TL_auth_loginTokenMigrateTo) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                }
            } else {
                String err = (error != null && error.text != null) ? error.text : "UNKNOWN";
                FileLog.e("QR export error: " + err);
                statusText.setText(isRu ? "Ошибка запроса: " + err : "Request error: " + err);
                progressView.setVisibility(View.GONE);
                if (!isDismissed) {
                    scheduleRefresh(5000L);
                }
            }
        }), flags);
    }

    private void onTokenScanned(final boolean silent) {
        if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        if (!silent) {
            statusText.setText(isRu ? "Подтверждение авторизации..." : "Confirming authorization...");
            showSpinner();
        }

        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.except_ids = new ArrayList<>();

        int flags = ConnectionsManager.RequestFlagEnableUnauthorized
                | ConnectionsManager.RequestFlagFailOnServerErrors
                | ConnectionsManager.RequestFlagWithoutLogin;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

            if (error == null && response != null) {
                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    handleMigration((TLRPC.TL_auth_loginTokenMigrateTo) response);
                } else if (response instanceof TLRPC.TL_auth_loginToken) {
                    TLRPC.TL_auth_loginToken tokenObj = (TLRPC.TL_auth_loginToken) response;
                    boolean sameToken = currentToken != null && tokenObj.token != null
                            && java.util.Arrays.equals(currentToken, tokenObj.token);
                    if (!sameToken && tokenObj.token != null && tokenObj.token.length > 0) {
                        currentToken = tokenObj.token;
                        String b64 = Base64.encodeToString(currentToken, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                        generateAndDisplayQR("tg://login?token=" + b64, !silent && qrImageView.getDrawable() == null);
                    } else if (!silent) {
                        progressView.setVisibility(View.GONE);
                    }
                    if (!silent) {
                        statusText.setText(isRu ? "Наведите камеру Telegram на QR-код" : "Scan QR code with Telegram camera");
                    }
                }
            } else {
                String err = (error != null && error.text != null) ? error.text : "UNKNOWN";
                if (err.contains("SESSION_PASSWORD_NEEDED")) {
                    on2FARequired();
                } else if (!silent) {
                    FileLog.e("QR token confirm error: " + err);
                    statusText.setText(isRu ? "Нажмите «Проверить сейчас» для повторной проверки" : "Tap 'Check now' to check again");
                    progressView.setVisibility(View.GONE);
                }
            }
        }), flags);
    }

    private void handleMigration(final TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        statusText.setText(isRu ? "Переключение датацентра..." : "Migrating datacenter...");
        showSpinner();

        ConnectionsManager.getInstance(currentAccount).setDefaultDatacenterId(migrate.dc_id);

        TLRPC.TL_auth_importLoginToken importReq = new TLRPC.TL_auth_importLoginToken();
        importReq.token = migrate.token;

        int flags = ConnectionsManager.RequestFlagEnableUnauthorized
                | ConnectionsManager.RequestFlagFailOnServerErrors
                | ConnectionsManager.RequestFlagWithoutLogin;

        ConnectionsManager.getInstance(currentAccount).sendRequest(importReq, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

            if (error == null && response != null) {
                if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                    onSuccess((TLRPC.TL_auth_loginTokenSuccess) response);
                } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                    handleMigration((TLRPC.TL_auth_loginTokenMigrateTo) response);
                }
            } else {
                String err = (error != null && error.text != null) ? error.text : "UNKNOWN";
                if (err.contains("SESSION_PASSWORD_NEEDED")) {
                    on2FARequired();
                } else {
                    FileLog.e("QR import error: " + err);
                    statusText.setText(isRu ? "Ошибка миграции: " + err : "Migration error: " + err);
                    progressView.setVisibility(View.GONE);
                }
            }
        }), flags);
    }

    private void scheduleRefresh(long delayMs) {
        cancelRefresh();
        refreshRunnable = () -> {
            if (!isDismissed && activity != null && activity.getParentActivity() != null && !activity.getParentActivity().isFinishing()) {
                startQrExport();
            }
        };
        handler.postDelayed(refreshRunnable, delayMs);
    }

    private void cancelRefresh() {
        if (refreshRunnable != null) {
            handler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }

    private void generateAndDisplayQR(final String content, final boolean fadeIn) {
        new Thread(() -> {
            try {
                HashMap<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                hints.put(EncodeHintType.MARGIN, 0);

                TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
                final Bitmap qrBitmap = writer.encode(content, 640, 640, hints, null, 1.0f, 0xFFFFFFFF, 0xFF000000);

                AndroidUtilities.runOnUIThread(() -> {
                    if (qrBitmap != null && !isDismissed) {
                        qrImageView.animate().cancel();
                        if (fadeIn) {
                            qrImageView.setAlpha(0f);
                            qrImageView.setImageBitmap(qrBitmap);
                            qrImageView.animate().alpha(1f).setDuration(220).start();
                        } else {
                            qrImageView.setAlpha(1f);
                            qrImageView.setImageBitmap(qrBitmap);
                        }
                        if (progressView.getVisibility() == View.VISIBLE) {
                            progressView.animate().alpha(0f).setDuration(140)
                                    .withEndAction(() -> progressView.setVisibility(View.GONE))
                                    .start();
                        }
                    }
                });
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }).start();
    }

    private void onSuccess(final TLRPC.TL_auth_loginTokenSuccess success) {
        isDismissed = true;
        activeInstance = null;
        cancelRefresh();
        stopAutoConfirm();

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

    private void on2FARequired() {
        isDismissed = true;
        activeInstance = null;
        cancelRefresh();
        stopAutoConfirm();

        try {
            bottomSheet.dismiss();
        } catch (Throwable ignored) {}

        Toast.makeText(activity.getParentActivity(), isRu ? "Требуется пароль двухэтапной аутентификации" : "2FA password required", Toast.LENGTH_LONG).show();

        try {
            TL_account.getPassword req = new TL_account.getPassword();
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (resp, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (err == null && resp instanceof TL_account.Password) {
                    TL_account.Password password = (TL_account.Password) resp;
                    Bundle bundle = new Bundle();
                    SerializedData data = new SerializedData(password.getObjectSize());
                    password.serializeToStream(data);
                    bundle.putString("password", Utilities.bytesToHex(data.toByteArray()));
                    activity.setPage(6, true, bundle, false);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
