package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.colgram.core.ColgramBotSync;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramThemePolicy;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.regex.Pattern;

public final class ColgramBotLoginBottomSheet {
    private static final Pattern TOKEN = Pattern.compile("[0-9]{1,20}:[A-Za-z0-9_-]{20,100}");
    private static final int requestTimeoutMs = 30_000;

    private ColgramBotLoginBottomSheet() {}

    public static void show(final LoginActivity activity, final int currentAccount) {
        if (activity == null || activity.getParentActivity() == null) return;
        final Context context = activity.getParentActivity();
        final boolean ru = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
        final ColgramThemePolicy.BotPalette palette = ColgramThemePolicy.forBotSheet(
                Theme.getColor(Theme.key_windowBackgroundWhite),
                Theme.getColor(Theme.key_dialogBackground),
                Theme.getColor(Theme.key_dialogTextBlack),
                Theme.getColor(Theme.key_dialogTextGray2),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_buttonText),
                ColgramConfig.isCyberThemeEnabled());

        BottomSheet.Builder builder = new BottomSheet.Builder(context, true, palette.sheet);
        builder.setApplyTopPadding(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(24));

        View handle = new View(context);
        handle.setBackground(fill(3, palette.handle));
        content.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        FrameLayout badge = new FrameLayout(context);
        badge.setBackground(fill(28, palette.badge));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_bot);
        icon.setColorFilter(palette.badgeIcon, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        badge.addView(icon, LayoutHelper.createFrame(32, 32, Gravity.CENTER));
        content.addView(badge, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        TextView title = label(context, ru ? "Вход по токену бота" : "Log in via bot token", 20, palette.title);
        title.setTypeface(AndroidUtilities.bold());
        content.addView(title, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 6));
        TextView description = label(context,
                ru ? "Введите полный токен вашего бота из @BotFather."
                   : "Enter your bot's full token from @BotFather.", 14, palette.secondary);
        content.addView(description, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 18));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(4), dp(8), dp(4));
        GradientDrawable field = fill(10, palette.field);
        field.setStroke(dp(1), palette.fieldStroke);
        card.setBackground(field);

        EditText input = new EditText(context);
        input.setTag("colgram_bot_token_input");
        input.setContentDescription(ru ? "Токен бота" : "Bot token");
        input.setHint(ru ? "ID:токен" : "ID:token");
        input.setHintTextColor(palette.fieldHint);
        input.setTextColor(palette.fieldText);
        input.setTextSize(14);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTypeface(Typeface.MONOSPACE);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        card.addView(input, LayoutHelper.createLinear(0, -2, 1f, Gravity.CENTER_VERTICAL));

        TextView paste = label(context, ru ? "Вставить" : "Paste", 13, palette.pasteText);
        paste.setTypeface(AndroidUtilities.bold());
        paste.setPadding(dp(12), dp(8), dp(12), dp(8));
        paste.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(6), palette.paste, palette.pastePressed));
        paste.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null) {
                input.setText(text.toString().trim());
                input.setSelection(input.length());
            }
        });
        card.addView(paste, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));
        content.addView(card, LayoutHelper.createLinear(-1, 50, 0, 0, 0, 16));

        FrameLayout submit = new FrameLayout(context);
        submit.setTag("colgram_bot_submit");
        String submitLabel = ru ? "Войти в аккаунт бота" : "Log in as bot";
        submit.setContentDescription(submitLabel);
        submit.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), palette.button, palette.buttonPressed));
        TextView buttonText = label(context, submitLabel, 15, palette.buttonText);
        buttonText.setTypeface(AndroidUtilities.bold());
        submit.addView(buttonText, LayoutHelper.createFrame(-1, -1, Gravity.CENTER));

        RadialProgressView spinner = new RadialProgressView(context);
        spinner.setSize(dp(24));
        spinner.setProgressColor(palette.buttonText);
        spinner.setVisibility(View.GONE);
        submit.addView(spinner, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        content.addView(submit, LayoutHelper.createLinear(-1, 48, 0, 0, 0, 12));

        TextView status = label(context, "", 13, palette.error);
        status.setTag("colgram_bot_status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status, LayoutHelper.createLinear(-1, -2));
        status.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        builder.setCustomView(scroll);
        final BottomSheet sheet = builder.create();
        final ConnectionsManager manager = ConnectionsManager.getInstance(currentAccount);

        final class Attempt {
            int requestId;
            int generation;
            Runnable timer;
            boolean dismissed;

            void clearTimer() {
                if (timer == null) return;
                AndroidUtilities.cancelRunOnUIThread(timer);
                timer = null;
            }

            void cancel() {
                generation++;
                clearTimer();
                if (requestId != 0) manager.cancelRequest(requestId, true);
                requestId = 0;
            }

            void busy(boolean value) {
                submit.setEnabled(!value);
                input.setEnabled(!value);
                paste.setEnabled(!value);
                buttonText.setVisibility(value ? View.INVISIBLE : View.VISIBLE);
                spinner.setVisibility(value ? View.VISIBLE : View.GONE);
            }

            void error(String message) {
                busy(false);
                status.setText(message);
                status.setVisibility(View.VISIBLE);
                scroll.post(() -> scroll.smoothScrollTo(0, content.getBottom()));
            }

            void submitToken() {
                if (dismissed || requestId != 0) return;
                final String token = input.getText().toString().trim();
                if (!TOKEN.matcher(token).matches()) {
                    error(ru ? "Нужен полный токен: цифры, двоеточие и секрет из @BotFather."
                             : "Enter the complete token: numeric ID, colon and secret from @BotFather.");
                    return;
                }
                AndroidUtilities.hideKeyboard(input);
                status.setVisibility(View.GONE);
                busy(true);
                final int ticket = ++generation;
                TLRPC.TL_auth_importBotAuthorization request = new TLRPC.TL_auth_importBotAuthorization();
                request.api_id = BuildVars.APP_ID;
                request.api_hash = BuildVars.APP_HASH;
                request.bot_auth_token = token;
                int flags = ConnectionsManager.RequestFlagEnableUnauthorized
                        | ConnectionsManager.RequestFlagWithoutLogin
                        | ConnectionsManager.RequestFlagFailOnServerErrors
                        | ConnectionsManager.RequestFlagTryDifferentDc;
                requestId = manager.sendRequest(request, (response, failure) -> AndroidUtilities.runOnUIThread(() -> {
                    if (dismissed || generation != ticket || !sheet.isShowing()) return;
                    clearTimer();
                    requestId = 0;
                    if (failure == null && response instanceof TLRPC.TL_auth_authorization) {
                        TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) response;
                        if (auth.user != null && auth.user.bot) {
                            input.setText("");
                            sheet.dismiss();
                            activity.onAuthSuccess(auth);
                            if (UserConfig.getInstance(currentAccount).getClientUserId() == auth.user.id) {
                                ColgramBotSync.saveBotToken(context, currentAccount, token);
                                ColgramBotSync.syncBotDialogs(context, currentAccount, false);
                            }
                            return;
                        }
                    }
                    error(failureMessage(failure, ru));
                }), flags);
                manager.bindRequestToGuid(requestId, activity.getClassGuid());
                timer = () -> {
                    if (dismissed || generation != ticket) return;
                    cancel();
                    error(networkMessage(ru));
                };
                AndroidUtilities.runOnUIThread(timer, requestTimeoutMs);
            }
        }
        Attempt attempt = new Attempt();
        submit.setOnClickListener(view -> attempt.submitToken());
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            attempt.submitToken();
            return true;
        });
        activity.showDialog(sheet, dialog -> {
            attempt.dismissed = true;
            attempt.cancel();
            input.setText("");
        });
    }

    private static String failureMessage(TLRPC.TL_error failure, boolean ru) {
        if (failure != null && (failure.code == -1000 || failure.text == null || failure.text.isEmpty())) {
            return networkMessage(ru);
        }
        String kind = failure == null ? "" : failure.text;
        if ("ACCESS_TOKEN_EXPIRED".equals(kind) || "ACCESS_TOKEN_INVALID".equals(kind)
                || "BOT_TOKEN_INVALID".equals(kind)) {
            return ru ? "Telegram отклонил токен. Проверьте полный токен в @BotFather."
                      : "Telegram rejected the token. Check the full token in @BotFather.";
        }
        if ("API_ID_INVALID".equals(kind) || "API_ID_PUBLISHED_FLOOD".equals(kind)) {
            return ru ? "Telegram отклонил идентификатор этой сборки, а не токен."
                      : "Telegram rejected this build's application ID, not your token.";
        }
        if (kind.startsWith("FLOOD_WAIT")) {
            return ru ? "Слишком много попыток. Попробуйте позже."
                      : "Too many attempts. Try again later.";
        }
        return ru ? "Telegram не завершил вход бота. Попробуйте снова."
                  : "Telegram did not complete bot login. Try again.";
    }

    private static String networkMessage(boolean ru) {
        return ru ? "Нет ответа от Telegram по текущему маршруту. Откройте настройки сети через щит и проверьте подключение."
                  : "No reply from Telegram on this route. Use the shield menu to check the connection.";
    }

    private static TextView label(Context context, String text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static GradientDrawable fill(int radius, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(color);
        return drawable;
    }

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }
}
