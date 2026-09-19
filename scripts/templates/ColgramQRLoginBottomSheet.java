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

        final RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(36));
        progressView.setProgressColor(accentColor);
        qrFrame.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        container.addView(qrFrame, LayoutHelper.createLinear(240, 240, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        // Status / hint text
        final TextView statusText = new TextView(context);
        statusText.setText(isRu ? "Генерация QR-кода..." : "Generating QR code...");
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusText.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // Check button (allows instant confirmation after scanning)
        final TextView checkBtn = new TextView(context);
        checkBtn.setText(isRu ? "🔄 Я отсканировал — войти" : "🔄 I scanned — Log in");
        checkBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        checkBtn.setTypeface(AndroidUtilities.bold());
        checkBtn.setTextColor(accentColor);
        checkBtn.setGravity(Gravity.CENTER);
        checkBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        checkBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(18), accentColor & 0x14ffffff, accentColor & 0x33ffffff));
        container.addView(checkBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // Cancel button
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

        final ColgramQRLoginBottomSheet instance = new ColgramQRLoginBottomSheet(
                activity, currentAccount, bottomSheet, qrImageView, progressView, statusText, isRu);
        activeInstance = instance;

        checkBtn.setOnClickListener(v -> instance.onTokenScanned());
        cancelBtn.setOnClickListener(v -> bottomSheet.dismiss());

        bottomSheet.setOnDismissListener(dialog -> {
            instance.isDismissed = true;
            instance.cancelRefresh();
            if (activeInstance == instance) {
                activeInstance = null;
            }
        });

        bottomSheet.show();

        // Start MTProto QR Export Flow (single call, no rapid polling)
        instance.startQrExport();
    }

    /**
     * Called whenever MTProto receives TL_updateLoginToken (push notification from server).
     */
    public static void onLoginTokenUpdate(final int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (activeInstance != null && !activeInstance.isDismissed && activeInstance.currentAccount == account) {
                activeInstance.onTokenScanned();
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

    private void startQrExport() {
        if (isDismissed || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        progressView.setVisibility(View.VISIBLE);

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
                        generateAndDisplayQR(qrUri);
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

    private void onTokenScanned() {
        if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        statusText.setText(isRu ? "Подтверждение авторизации..." : "Confirming authorization...");
        progressView.setVisibility(View.VISIBLE);

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
                    currentToken = tokenObj.token;
                    String b64 = Base64.encodeToString(currentToken, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    generateAndDisplayQR("tg://login?token=" + b64);
                    statusText.setText(isRu ? "Наведите камеру Telegram на QR-код" : "Scan QR code with Telegram camera");
                }
            } else {
                String err = (error != null && error.text != null) ? error.text : "UNKNOWN";
                if (err.contains("SESSION_PASSWORD_NEEDED")) {
                    on2FARequired();
                } else {
                    FileLog.e("QR token confirm error: " + err);
                    statusText.setText(isRu ? "Нажмите «Я отсканировал» для повторной проверки" : "Tap 'I scanned' to check again");
                    progressView.setVisibility(View.GONE);
                }
            }
        }), flags);
    }

    private void handleMigration(final TLRPC.TL_auth_loginTokenMigrateTo migrate) {
        if (isDismissed || activity == null || activity.getParentActivity() == null || activity.getParentActivity().isFinishing()) return;

        statusText.setText(isRu ? "Переключение датацентра..." : "Migrating datacenter...");
        progressView.setVisibility(View.VISIBLE);

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

    private void generateAndDisplayQR(final String content) {
        new Thread(() -> {
            try {
                HashMap<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                hints.put(EncodeHintType.MARGIN, 0);

                TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
                final Bitmap qrBitmap = writer.encode(content, 640, 640, hints, null, 1.0f, 0xFFFFFFFF, 0xFF000000);

                AndroidUtilities.runOnUIThread(() -> {
                    if (qrBitmap != null && !isDismissed) {
                        qrImageView.setImageBitmap(qrBitmap);
                        progressView.setVisibility(View.GONE);
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

        try {
            bottomSheet.dismiss();
        } catch (Throwable ignored) {}

        Toast.makeText(activity.getParentActivity(), isRu ? "Требуется пароль двухэтапной аутентификации" : "2FA password required", Toast.LENGTH_LONG).show();

        try {
            activity.needShowProgress(0);
            TLRPC.TL_account_getPassword req = new TLRPC.TL_account_getPassword();
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (resp, err) -> AndroidUtilities.runOnUIThread(() -> {
                activity.needHideProgress(false);
                if (resp instanceof TLRPC.TL_account_password) {
                    activity.openPasswordView((TLRPC.TL_account_password) resp);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
