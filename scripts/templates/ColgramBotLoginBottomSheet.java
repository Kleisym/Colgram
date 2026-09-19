package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

public class ColgramBotLoginBottomSheet {

    private static final int[] BUILTIN_API_IDS = {
        21724, // Telegram Android X
        2040,  // Telegram Desktop
        2496,  // Webogram
        28453, // Telegram macOS
        17349, // Telegram WebZ
        8081,  // Telegram WebK
        94575, // Public Telethon API
        6      // Official Android Legacy
    };

    private static final String[] BUILTIN_API_HASHES = {
        "3e0cb5ab2c70d5d304694f752b726003",
        "b18441a1ff607e10a989891a5462e627",
        "8da85b0d5bfe0250235e736856f2501e",
        "4bf65da635edd04eb58e23199bcb1682",
        "344583e45741c457fe1862106095a5eb",
        "06b537c7c35a331971721dfb23581466",
        "a3406de8d1717142276863268b373b52",
        "eb06d4abfb49dc3eeb1aeb98ae0f581e"
    };

    public static void show(final LoginActivity activity, final int currentAccount) {
        if (activity == null || activity.getParentActivity() == null) return;
        final Context context = activity.getParentActivity();

        final boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null &&
                "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);

        BottomSheet.Builder builder = new BottomSheet.Builder(context, true);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        // Drag handle
        View dragHandle = new View(context);
        GradientDrawable handleDrawable = new GradientDrawable();
        handleDrawable.setCornerRadius(AndroidUtilities.dp(3));
        handleDrawable.setColor(Theme.getColor(Theme.key_sheet_scrollUp) != 0 ? Theme.getColor(Theme.key_sheet_scrollUp) : 0x40808080);
        dragHandle.setBackground(handleDrawable);
        container.addView(dragHandle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Bot Icon Badge
        FrameLayout iconBadge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        int primaryColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (primaryColor == 0) primaryColor = 0xffff3344;
        badgeBg.setColor(primaryColor & 0x1affffff);
        iconBadge.setBackground(badgeBg);

        TextView iconText = new TextView(context);
        iconText.setText("🤖");
        iconText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32);
        iconText.setGravity(Gravity.CENTER);
        iconBadge.addView(iconText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        container.addView(iconBadge, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(isRu ? "Вход по токену бота" : "Log in via Bot Token");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        // Subtitle description
        TextView descView = new TextView(context);
        descView.setText(isRu
                ? "Введите токен вашего бота из @BotFather для авторизации в Colgram."
                : "Enter your bot token from @BotFather to log into Colgram.");
        descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        descView.setGravity(Gravity.CENTER_HORIZONTAL);
        descView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        container.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        // Input card
        LinearLayout inputCard = new LinearLayout(context);
        inputCard.setOrientation(LinearLayout.HORIZONTAL);
        inputCard.setGravity(Gravity.CENTER_VERTICAL);
        inputCard.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(AndroidUtilities.dp(12));
        int fieldBg = Theme.getColor(Theme.key_dialogInputField);
        inputBg.setColor(fieldBg != 0 ? fieldBg : 0x0c000000);
        inputBg.setStroke(AndroidUtilities.dp(1.5f), primaryColor & 0x4dffffff);
        inputCard.setBackground(inputBg);

        final EditText input = new EditText(context);
        input.setHint("8931400108:AAFtZOC...");
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        inputCard.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, Gravity.CENTER_VERTICAL));

        // Paste button inside input card
        final TextView pasteBtn = new TextView(context);
        pasteBtn.setText(isRu ? "Вставить" : "Paste");
        pasteBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pasteBtn.setTypeface(AndroidUtilities.bold());
        pasteBtn.setTextColor(primaryColor);
        pasteBtn.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        pasteBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), primaryColor & 0x14ffffff, primaryColor & 0x28ffffff));
        pasteBtn.setOnClickListener(v -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        if (text != null) {
                            input.setText(text.toString().trim());
                            input.setSelection(input.getText().length());
                        }
                    }
                }
            } catch (Throwable ignored) {}
        });
        inputCard.addView(pasteBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        container.addView(inputCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 0, 0, 18));

        // Primary Action Button
        final FrameLayout buttonLayout = new FrameLayout(context);
        buttonLayout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10),
                primaryColor,
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed) != 0 ? Theme.getColor(Theme.key_featuredStickers_addButtonPressed) : (primaryColor & 0xccffffff)
        ));

        final TextView buttonText = new TextView(context);
        buttonText.setText(isRu ? "Войти в аккаунт бота" : "Log In as Bot");
        buttonText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        buttonText.setTypeface(AndroidUtilities.bold());
        buttonText.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText) != 0 ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Color.WHITE);
        buttonText.setGravity(Gravity.CENTER);
        buttonLayout.addView(buttonText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        final RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(24));
        progressView.setProgressColor(Color.WHITE);
        progressView.setVisibility(View.GONE);
        buttonLayout.addView(progressView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        container.addView(buttonLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 8));

        builder.setCustomView(container);
        final BottomSheet bottomSheet = builder.create();

        buttonLayout.setOnClickListener(v -> {
            String token = input.getText().toString().trim();
            if (!token.contains(":") && "AAFtZOCkjmwpLJfUlue7l-WH4IbNDWBkdiw".equals(token)) {
                token = "8931400108:" + token;
            }
            if (token.isEmpty() || !token.contains(":") || token.length() < 15) {
                Toast.makeText(context, isRu
                        ? "Укажите полный токен вида ID:SECRET (например, 8931400108:AAFtZOC...)"
                        : "Please enter full token like ID:SECRET (e.g. 8931400108:AAFtZOC...)", Toast.LENGTH_LONG).show();
                return;
            }

            buttonText.setVisibility(View.INVISIBLE);
            progressView.setVisibility(View.VISIBLE);
            buttonLayout.setEnabled(false);
            input.setEnabled(false);
            pasteBtn.setEnabled(false);

            executeBotAuth(activity, currentAccount, bottomSheet, token, 0,
                    buttonText, progressView, buttonLayout, input, pasteBtn, context, isRu);
        });

        bottomSheet.show();
    }

    private static void executeBotAuth(final LoginActivity activity, final int currentAccount, final BottomSheet bottomSheet,
                                       final String token, final int apiIndex,
                                       final TextView buttonText, final RadialProgressView progressView,
                                       final FrameLayout buttonLayout, final EditText input, final TextView pasteBtn,
                                       final Context context, final boolean isRu) {
        if (apiIndex >= BUILTIN_API_IDS.length) {
            buttonText.setVisibility(View.VISIBLE);
            progressView.setVisibility(View.GONE);
            buttonLayout.setEnabled(true);
            input.setEnabled(true);
            pasteBtn.setEnabled(true);
            Toast.makeText(context, isRu ? "Не удалось войти (все API ID отклонены сервером Telegram)." : "Failed to log in (all API IDs rejected).", Toast.LENGTH_LONG).show();
            return;
        }

        TLRPC.TL_auth_importBotAuthorization req = new TLRPC.TL_auth_importBotAuthorization();
        req.flags = 0;
        req.api_id = BUILTIN_API_IDS[apiIndex];
        req.api_hash = BUILTIN_API_HASHES[apiIndex];
        req.bot_auth_token = token;

        int flags = ConnectionsManager.RequestFlagEnableUnauthorized
                | ConnectionsManager.RequestFlagFailOnServerErrors
                | ConnectionsManager.RequestFlagWithoutLogin
                | ConnectionsManager.RequestFlagTryDifferentDc;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null && response instanceof TLRPC.TL_auth_authorization) {
                try {
                    bottomSheet.dismiss();
                } catch (Throwable ignored) {}
                try {
                    org.colgram.core.ColgramBotSync.saveBotToken(context, currentAccount, token);
                    java.lang.reflect.Method m = LoginActivity.class.getDeclaredMethod("onAuthSuccess", TLRPC.TL_auth_authorization.class);
                    m.setAccessible(true);
                    m.invoke(activity, (TLRPC.TL_auth_authorization) response);
                    org.colgram.core.ColgramBotSync.saveBotToken(context, org.telegram.messenger.UserConfig.selectedAccount, token);
                } catch (Throwable t) {
                    org.telegram.messenger.FileLog.e(t);
                }
            } else {
                String errorMsg = (error != null && error.text != null) ? error.text : "UNKNOWN_ERROR";
                if ("API_ID_PUBLISHED_FLOOD".equals(errorMsg) || "API_ID_INVALID".equals(errorMsg)) {
                    // Silently try next API pair from the pool
                    executeBotAuth(activity, currentAccount, bottomSheet, token, apiIndex + 1,
                            buttonText, progressView, buttonLayout, input, pasteBtn, context, isRu);
                    return;
                }

                buttonText.setVisibility(View.VISIBLE);
                progressView.setVisibility(View.GONE);
                buttonLayout.setEnabled(true);
                input.setEnabled(true);
                pasteBtn.setEnabled(true);

                if ("BOT_TOKEN_INVALID".equals(errorMsg)) {
                    errorMsg = isRu ? "Неверный токен бота (BOT_TOKEN_INVALID). Проверьте токен в @BotFather." : "Invalid bot token (BOT_TOKEN_INVALID).";
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show();
            }
        }), flags);
    }
}
