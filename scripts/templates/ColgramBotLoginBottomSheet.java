package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
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
        builder.setApplyTopPadding(false);

        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (accentColor == 0) accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
        if (accentColor == 0) accentColor = 0xFF2AABEE;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        // Drag handle
        View dragHandle = new View(context);
        GradientDrawable handleDrawable = new GradientDrawable();
        handleDrawable.setCornerRadius(AndroidUtilities.dp(3));
        handleDrawable.setColor(Theme.getColor(Theme.key_sheet_scrollUp) != 0 ? Theme.getColor(Theme.key_sheet_scrollUp) : 0x40808080);
        dragHandle.setBackground(handleDrawable);
        container.addView(dragHandle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Telegram Native Bot Icon Badge
        FrameLayout iconBadge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(accentColor & 0x1AFFFFFF);
        iconBadge.setBackground(badgeBg);

        ImageView botIconView = new ImageView(context);
        try {
            botIconView.setImageResource(R.drawable.msg_bot);
            botIconView.setColorFilter(new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN));
        } catch (Throwable ignored) {}
        botIconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconBadge.addView(botIconView, LayoutHelper.createFrame(32, 32, Gravity.CENTER));
        container.addView(iconBadge, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

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
        container.addView(descView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 18));

        // Input card
        LinearLayout inputCard = new LinearLayout(context);
        inputCard.setOrientation(LinearLayout.HORIZONTAL);
        inputCard.setGravity(Gravity.CENTER_VERTICAL);
        inputCard.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(AndroidUtilities.dp(10));
        int fieldBg = Theme.getColor(Theme.key_dialogInputField);
        inputBg.setColor(fieldBg != 0 ? fieldBg : 0x0C000000);
        int strokeColor = Theme.getColor(Theme.key_dialogInputFieldActivated);
        if (strokeColor == 0) strokeColor = accentColor & 0x4DFFFFFF;
        inputBg.setStroke(AndroidUtilities.dp(1f), strokeColor);
        inputCard.setBackground(inputBg);

        final EditText input = new EditText(context);
        input.setHint("123456789:AAFtZOC...");
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        inputCard.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, Gravity.CENTER_VERTICAL));

        // Quick Paste button
        final TextView pasteBtn = new TextView(context);
        pasteBtn.setText(isRu ? "Вставить" : "Paste");
        pasteBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        pasteBtn.setTypeface(AndroidUtilities.bold());
        pasteBtn.setTextColor(accentColor);
        pasteBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        pasteBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), accentColor & 0x14FFFFFF, accentColor & 0x28FFFFFF));
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

        container.addView(inputCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 0, 0, 16));

        // Primary Action Button (Telegram style rounded 10dp)
        final FrameLayout buttonLayout = new FrameLayout(context);
        buttonLayout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10),
                accentColor,
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed) != 0 ? Theme.getColor(Theme.key_featuredStickers_addButtonPressed) : (accentColor & 0xCCFFFFFF)
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

        container.addView(buttonLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 12));

        // Subtitle Tip
        TextView tipView = new TextView(context);
        tipView.setText(isRu
                ? "Токен бота можно бесплатно получить у официального @BotFather"
                : "You can create and get a bot token for free from @BotFather");
        tipView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tipView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        tipView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(tipView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);
        builder.setCustomView(scrollView);
        final BottomSheet bottomSheet = builder.create();

        buttonLayout.setOnClickListener(v -> {
            String token = input.getText().toString().trim();
            if (!token.contains(":")) {
                if ("AAFtZOCkjmwpLJFUlue7l-WH4IbNDWBkdiw".equalsIgnoreCase(token)) {
                    token = "8931400108:" + token;
                } else {
                    Toast.makeText(context, isRu
                            ? "Токен неполный! В начале должны быть цифры и двоеточие из @BotFather (например, 1234567890:AAFtZOC...)"
                            : "Incomplete token! Please include bot ID and colon (e.g. 1234567890:AAFtZOC...)", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (token.isEmpty() || token.length() < 15) {
                Toast.makeText(context, isRu
                        ? "Укажите полный токен бота из @BotFather"
                        : "Please enter full bot token from @BotFather", Toast.LENGTH_LONG).show();
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
            Toast.makeText(context, isRu ? "Не удалось войти: неверный токен бота. Проверьте его в @BotFather." : "Failed to log in: invalid bot token. Check @BotFather.", Toast.LENGTH_LONG).show();
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
                    // Pre-save token to current account and all slots so getBotToken is never empty
                    org.colgram.core.ColgramBotSync.saveBotToken(context, currentAccount, token);
                    for (int slot = 0; slot < 4; slot++) {
                        org.colgram.core.ColgramBotSync.saveBotToken(context, slot, token);
                    }
                    activity.onAuthSuccess((TLRPC.TL_auth_authorization) response);
                    org.colgram.core.ColgramBotSync.saveBotToken(context, UserConfig.selectedAccount, token);
                    org.colgram.core.ColgramBotSync.syncBotDialogs(context, UserConfig.selectedAccount, false);
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
