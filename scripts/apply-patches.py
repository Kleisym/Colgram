#!/usr/bin/env python3
"""
Colgram Python Injection Engine
Replaces brittle git apply with robust semantic code injection.
Directly hooks Telegram source without line number dependencies.
"""

import os
import re
import sys
import shutil
import zipfile
import urllib.request
import subprocess

def patch_file(filepath, search_pattern, replacement, description):
    if not os.path.exists(filepath):
        print(f" [!] File not found: {filepath}")
        return False

    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    if replacement.strip() in content:
        print(f" [=] Already patched: {description}")
        return True

    if callable(search_pattern):
        new_content = search_pattern(content)
        if new_content == content:
            print(f" [!] Pattern not matched for: {description}")
            return False
    elif isinstance(search_pattern, str):
        if search_pattern not in content:
            print(f" [!] Anchor string not found for: {description}")
            return False
        new_content = content.replace(search_pattern, replacement, 1)
    else:
        # Regex
        new_content, count = search_pattern.subn(replacement, content, count=1)
        if count == 0:
            print(f" [!] Regex not matched for: {description}")
            return False

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f" [+] Successfully patched: {description}")
    return True

def inject_hooks(repo_path):
    print("[*] Performing semantic code injection into Telegram source...")

    # 1. ApplicationLoader.java -> Initialize Colgram core
    app_loader = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ApplicationLoader.java")
    patch_file(
        app_loader,
        "applicationContext = getApplicationContext();",
        "applicationContext = getApplicationContext();\n            org.colgram.core.ColgramHookHandler.init(applicationContext);",
        "ApplicationLoader.onCreate initialization"
    )

    # 2. ConnectionsManager.java -> Hardware & OS Cloaking
    conn_manager = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "ConnectionsManager.java")
    
    def cloak_replacer(content):
        target = "init(SharedConfig.buildVersion()"
        if target not in content:
            return content
        inject_code = """
        java.util.Map<String, String> cloaked = org.colgram.core.ColgramHookHandler.hookInitConnection(deviceModel, systemVersion, appVersion, langCode);
        if (cloaked != null) {
            if (cloaked.containsKey("device_model")) deviceModel = cloaked.get("device_model");
            if (cloaked.containsKey("system_version")) systemVersion = cloaked.get("system_version");
            if (cloaked.containsKey("app_version")) appVersion = cloaked.get("app_version");
            if (cloaked.containsKey("lang_code")) langCode = cloaked.get("lang_code");
        }
        """
        return content.replace(target, inject_code + "\n        " + target, 1)

    patch_file(conn_manager, cloak_replacer, "", "ConnectionsManager MTProto Cloaking")

    # 3. FileLoader.java -> Storage Sandbox & Media Lock
    file_loader = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoader.java")
    patch_file(
        file_loader,
        "public static File getDirectory(int type) {",
        "public static File getDirectory(int type) {\n        File sandboxed = org.colgram.core.ColgramHookHandler.hookGetDirectory(type);\n        if (sandboxed != null) return sandboxed;",
        "FileLoader.getDirectory Sandbox Redirect"
    )
    patch_file(
        file_loader,
        "if (!file.delete()) {",
        "if (org.colgram.core.ColgramHookHandler.shouldPreventMediaDeletion(file)) continue;\n                if (!file.delete()) {",
        "FileLoader.deleteFiles Media Lock"
    )

    # 4. FlagSecureReason.java -> FLAG_SECURE Bypass
    flag_secure = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FlagSecureReason.java")
    patch_file(
        flag_secure,
        "public static boolean isSecuredNow(Window window) {",
        "public static boolean isSecuredNow(Window window) {\n        if (org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure()) return false;",
        "FlagSecureReason Bypass"
    )

    # 5. ChatMessageCell.java -> Visual cue for deleted messages
    chat_cell = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "ChatMessageCell.java")
    patch_file(
        chat_cell,
        "if (attachedToWindow && !frozen) {",
        "if (messageObject != null && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(messageObject.getDialogId(), messageObject.getId())) setAlpha(0.65f); else setAlpha(1.0f);\n        if (attachedToWindow && !frozen) {",
        "ChatMessageCell Deleted Styling"
    )

    # 6. MessagesController.java -> Ghost Mode (Suppress Read & Typing)
    messages_controller = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java")
    patch_file(
        messages_controller,
        "public void markDialogAsRead(long dialogId, int maxPositiveId, int maxNegativeId, int maxDate, boolean popup, long threadId, int countDiff, boolean readNow, int scheduledCount) {",
        "public void markDialogAsRead(long dialogId, int maxPositiveId, int maxNegativeId, int maxDate, boolean popup, long threadId, int countDiff, boolean readNow, int scheduledCount) {\n        if (org.colgram.core.ColgramHookHandler.shouldPreventReadReceipt(dialogId)) return;",
        "MessagesController Ghost Read Receipt"
    )
    patch_file(
        messages_controller,
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, int classGuid) {",
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, int classGuid) {\n        if (org.colgram.core.ColgramHookHandler.shouldPreventTypingStatus(dialogId)) return false;",
        "MessagesController Ghost Typing Suppression (int)"
    )
    patch_file(
        messages_controller,
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, String emojicon, int classGuid) {",
        "public boolean sendTyping(long dialogId, long threadMsgId, int action, String emojicon, int classGuid) {\n        if (org.colgram.core.ColgramHookHandler.shouldPreventTypingStatus(dialogId)) return false;",
        "MessagesController Ghost Typing Suppression (String)"
    )

    # 7. LoginActivity.java -> Suppress phone call permission requests completely
    login_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LoginActivity.java")
    patch_file(
        login_activity,
        "private boolean checkPermissions = true;",
        "private boolean checkPermissions = false; // Colgram: suppress call permission nags",
        "LoginActivity Disable checkPermissions Field"
    )
    patch_file(
        login_activity,
        "private boolean checkShowPermissions = true;",
        "private boolean checkShowPermissions = false; // Colgram: suppress call permission nags",
        "LoginActivity Disable checkShowPermissions Field"
    )
    patch_file(
        login_activity,
        "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && AndroidUtilities.isSimAvailable()) {",
        "checkPermissions = false;\n                        if (false) {",
        "LoginActivity Suppress Call Permissions (onConfirm)"
    )
    patch_file(
        login_activity,
        "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && simcardAvailable) {",
        "simcardAvailable = false; checkPermissions = false;\n            if (false) {",
        "LoginActivity Suppress Call Permissions (Primary)"
    )
    patch_file(
        login_activity,
        "if (checkShowPermissions && (!allowCall || !allowReadPhoneNumbers)) {",
        "checkShowPermissions = false;\n                        if (false) {",
        "LoginActivity Suppress Call Permissions (Secondary)"
    )

    # 8. SendMessagesHelper.java -> Intercept outgoing messages for Python commands and plugins
    send_messages_helper = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "SendMessagesHelper.java")
    patch_file(
        send_messages_helper,
        "public void sendMessage(SendMessageParams sendMessageParams) {",
        """public void sendMessage(SendMessageParams sendMessageParams) {
        if (sendMessageParams != null && sendMessageParams.message != null) {
            int replyId = sendMessageParams.replyToMsg != null ? sendMessageParams.replyToMsg.getId() : 0;
            if (org.colgram.core.ColgramHookHandler.hookOnSendMessage(sendMessageParams.peer, replyId, sendMessageParams.message)) {
                return;
            }
        }""",
        "SendMessagesHelper Plugin & Python Command Interceptor"
    )

    # 9. TLRPC.java -> Inject TL_auth_importBotAuthorization for native bot login
    tlrpc_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "TLRPC.java")
    tl_import_auth_target = """    public static class TL_auth_importAuthorization extends TLObject {
        public static final int constructor = 0xa57a7dad;

        public long id;
        public byte[] bytes;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeByteArray(bytes);
        }
    }"""
    tl_import_bot_auth_code = """    public static class TL_auth_importAuthorization extends TLObject {
        public static final int constructor = 0xa57a7dad;

        public long id;
        public byte[] bytes;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(id);
            stream.writeByteArray(bytes);
        }
    }

    public static class TL_auth_importBotAuthorization extends TLObject {
        public static final int constructor = 0x67a3ff2c;

        public int flags;
        public int api_id;
        public String api_hash;
        public String bot_auth_token;

        public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
            return auth_Authorization.TLdeserialize(stream, constructor, exception);
        }

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt32(api_id);
            stream.writeString(api_hash);
            stream.writeString(bot_auth_token);
        }
    }"""
    patch_file(
        tlrpc_file,
        tl_import_auth_target,
        tl_import_bot_auth_code,
        "TLRPC Inject TL_auth_importBotAuthorization"
    )

    # 10. ColgramBotLoginBottomSheet.java -> Write Telegram-Native BottomSheet for Bot Login
    bot_sheet_path = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramBotLoginBottomSheet.java")
    bot_sheet_source = '''package org.telegram.ui;

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
                    java.lang.reflect.Method m = LoginActivity.class.getDeclaredMethod("onAuthSuccess", TLRPC.TL_auth_authorization.class);
                    m.setAccessible(true);
                    m.invoke(activity, (TLRPC.TL_auth_authorization) response);
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
'''
    with open(bot_sheet_path, "w", encoding="utf-8") as f:
        f.write(bot_sheet_source)
    print(" [+] Generated Telegram-Native ColgramBotLoginBottomSheet.java")

    # 11. LoginActivity.java -> Make onAuthSuccess public
    patch_file(
        login_activity,
        "private void onAuthSuccess(TLRPC.TL_auth_authorization res) {",
        "public void onAuthSuccess(TLRPC.TL_auth_authorization res) {",
        "LoginActivity Make onAuthSuccess Public"
    )

    # 12. LoginActivity.java -> Inject Bot Token Login button in PhoneView (with 76dp margin to avoid FAB collision)
    bot_login_btn = """addView(phoneOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));
            TextView botLoginBtn = new TextView(context);
            boolean isRuLang = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            botLoginBtn.setText(isRuLang ? "🤖  Войти через токен бота" : "🤖  Log in via Bot Token");
            botLoginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            botLoginBtn.setTypeface(AndroidUtilities.bold());
            botLoginBtn.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            botLoginBtn.setGravity(Gravity.CENTER);
            botLoginBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
            botLoginBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), Theme.getColor(Theme.key_featuredStickers_addButton) & 0x14ffffff, Theme.getColor(Theme.key_featuredStickers_addButton) & 0x33ffffff));
            botLoginBtn.setOnClickListener(v -> {
                org.telegram.ui.ColgramBotLoginBottomSheet.show(LoginActivity.this, currentAccount);
            });
            addView(botLoginBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.CENTER_HORIZONTAL, 16, 12, 76, 8));"""
    patch_file(
        login_activity,
        "addView(phoneOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));",
        bot_login_btn,
        "LoginActivity Bot Token Login Button"
    )

    # 13. LoginActivity.java -> Fix back button on VIEW_PHONE_INPUT (return to IntroActivity)
    login_back_target = """        if (currentViewNum == VIEW_PHONE_INPUT || activityMode == MODE_CHANGE_LOGIN_EMAIL && currentViewNum == VIEW_ADD_EMAIL) {
            if (invoked) {
                for (int a = 0; a < views.length; a++) {
                    if (views[a] != null) {
                        views[a].onDestroyActivity();
                    }
                }
                clearCurrentState();
            }
            return true;
        }"""
    login_back_replacement = """        if (currentViewNum == VIEW_PHONE_INPUT || activityMode == MODE_CHANGE_LOGIN_EMAIL && currentViewNum == VIEW_ADD_EMAIL) {
            if (invoked) {
                for (int a = 0; a < views.length; a++) {
                    if (views[a] != null) {
                        views[a].onDestroyActivity();
                    }
                }
                clearCurrentState();
            }
            if (activityMode == MODE_LOGIN && (parentLayout == null || parentLayout.getFragmentStack().size() <= 1)) {
                presentFragment(new IntroActivity(), true);
                return false;
            }
            return true;
        }"""
    patch_file(
        login_activity,
        login_back_target,
        login_back_replacement,
        "LoginActivity Return To IntroActivity On Back"
    )

    # 14. UserConfig.java -> Unlock all account slots
    user_config = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "UserConfig.java")
    patch_file(
        user_config,
        "public static int getMaxAccountCount() {\n        return hasPremiumOnAccounts() ? 5 : 3;\n    }",
        "public static int getMaxAccountCount() {\n        return UserConfig.MAX_ACCOUNT_COUNT;\n    }",
        "UserConfig Unlock Max Account Count"
    )
    patch_file(
        user_config,
        "public static boolean hasPremiumOnAccounts() {",
        "public static boolean hasPremiumOnAccounts() {\n        if (true) return true;",
        "UserConfig Has Premium on Accounts"
    )

    # 14. UserInfoActivity.java -> Bypass account limit check on Add Account
    user_info_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "UserInfoActivity.java")
    patch_file(
        user_info_activity,
        "if (!UserConfig.hasPremiumOnAccounts()) {\n                freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);\n            }",
        "// Colgram: all account slots unlocked without premium",
        "UserInfoActivity Unlock Add Account"
    )

    # 15. IntroActivity.java -> Fix back button (do not remove IntroActivity from backstack) and language switching
    intro_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "IntroActivity.java")
    if os.path.exists(intro_activity):
        # Keep IntroActivity in backstack when opening LoginActivity (removeLast = false)
        intro_t1 = """        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
            destroyed = true;
        });"""
        intro_r1 = """        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);
            destroyed = false;
        });"""
        patch_file(intro_activity, intro_t1, intro_r1, "IntroActivity Retain In Backstack On Start Messaging")

        intro_t2 = """                        AndroidUtilities.runOnUIThread(()->{
                            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
                            destroyed = true;
                        }, 100);"""
        intro_r2 = """                        AndroidUtilities.runOnUIThread(()->{
                            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);
                            destroyed = false;
                        }, 100);"""
        patch_file(intro_activity, intro_t2, intro_r2, "IntroActivity Retain In Backstack On Language Switch")

        # Reset startPressed and destroyed on resume so buttons work when user navigates back
        patch_file(
            intro_activity,
            "public void onResume() {\n        super.onResume();",
            "public void onResume() {\n        super.onResume();\n        startPressed = false;\n        destroyed = false;",
            "IntroActivity Reset startPressed On Resume"
        )
        # Update activity resources configuration when language is switched
        patch_file(
            intro_activity,
            "LocaleController.getInstance().applyLanguage(localeInfo, true, false, currentAccount);",
            """try {
                android.content.res.Configuration cfg = new android.content.res.Configuration();
                cfg.locale = new java.util.Locale(localeInfo.shortName);
                v.getContext().getResources().updateConfiguration(cfg, v.getContext().getResources().getDisplayMetrics());
            } catch (Throwable ignored) {}
            LocaleController.getInstance().applyLanguage(localeInfo, true, false, currentAccount);""",
            "IntroActivity Apply Language Configuration"
        )

    # 16. LoginActivity.java -> Visual Alert and Auto-Rotation on -1000 Connection Error in PhoneView
    phone_error_target = """fillNextCodeParams(params, (TLRPC.auth_SentCode) response);
                    }
                } else {
                    if (error.text != null) {"""
    phone_error_replacement = """fillNextCodeParams(params, (TLRPC.auth_SentCode) response);
                    }
                } else {
                    if (error != null && (error.code == -1000 || error.text == null)) {
                        try {
                            android.widget.Toast.makeText(getParentActivity(), "Ошибка соединения с Telegram (-1000). Переключаем прокси...", android.widget.Toast.LENGTH_SHORT).show();
                            org.colgram.core.ColgramProxyManager.switchToNextProxy();
                        } catch (Throwable ignored) {}
                    }
                    if (error.text != null) {"""
    patch_file(
        login_activity,
        phone_error_target,
        phone_error_replacement,
        "LoginActivity Handle -1000 Connection Error In PhoneView"
    )

    phone_error_target2 = """} else if (error.code != -1000) {
                            AlertsCreator.processError(currentAccount, error, LoginActivity.this, req, phoneInputData.phoneNumber);
                        }"""
    phone_error_replacement2 = """} else {
                            if (error.code != -1000) {
                                AlertsCreator.processError(currentAccount, error, LoginActivity.this, req, phoneInputData.phoneNumber);
                            } else {
                                try {
                                    android.widget.Toast.makeText(getParentActivity(), "Ошибка соединения с Telegram (-1000). Переключаем прокси...", android.widget.Toast.LENGTH_SHORT).show();
                                    org.colgram.core.ColgramProxyManager.switchToNextProxy();
                                } catch (Throwable ignored) {}
                            }
                        }"""
    # 17. ConnectionsManager.java -> Suppress proxy promo check (sponsor channels)
    patch_file(
        conn_manager,
        "accountInstance.getMessagesController().checkPromoInfo(true);",
        "// Colgram: Suppressed proxy promo check\n                    // accountInstance.getMessagesController().checkPromoInfo(true);",
        "ConnectionsManager Suppress checkPromoInfo"
    )

    # 18. MessagesController.java -> Suppress checkPromoInfo completely (kill proxy sponsor channels)
    def promo_suppressor(content):
        import re
        return re.sub(
            r'public void checkPromoInfo\s*\([^)]*\)\s*\{',
            r'public void checkPromoInfo(boolean force) {\n        if (true) return;',
            content,
            count=1
        )
    patch_file(messages_controller, promo_suppressor, "", "MessagesController Suppress checkPromoInfo")

    # 19. DialogsActivity.java -> Make Proxy Button Always Visible In Header & Popup Menu
    dialogs_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java")
    if os.path.exists(dialogs_activity):
        patch_file(
            dialogs_activity,
            "final boolean proxyVisible = proxyEnabled && !TextUtils.isEmpty(proxyAddress)",
            "final boolean proxyVisible = true; // Colgram: proxy menu item always visible\n            final boolean proxyVisibleOld = proxyEnabled && !TextUtils.isEmpty(proxyAddress)",
            "DialogsActivity Proxy Menu Item Always Visible"
        )
        header_proxy_target = "downloadsItem.setVisibility(View.GONE);\n\n            updateProxyButton(false, false);"
        header_proxy_replacement = """downloadsItem.setVisibility(View.GONE);

            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            if (colgramProxyItem != null) {
                colgramProxyItem.setContentDescription(getString(R.string.ProxySettings));
                colgramProxyItem.setOnClickListener(v -> presentFragment(new ProxyListActivity()));
            }

            updateProxyButton(false, false);"""
        patch_file(
            dialogs_activity,
            header_proxy_target,
            header_proxy_replacement,
            "DialogsActivity Header Proxy Button Always Visible"
        )

    # 20. MessagesStorage.java -> Anti-Delete (Preserve Deleted Messages In Local DB)
    messages_storage = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesStorage.java")
    if os.path.exists(messages_storage):
        def anti_delete_injector(content):
            target = "public ArrayList<Long> markMessagesAsDeleted("
            if target not in content:
                return content
            idx = content.find(target)
            brace_idx = content.find("{", idx)
            if brace_idx == -1:
                return content
            inject = """
        if (org.colgram.core.ColgramConfig.isAntiDeleteEnabled() && messages != null) {
            java.util.ArrayList<Integer> filtered = new java.util.ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                int mid = messages.get(i);
                if (org.colgram.core.ColgramHookHandler.hookShouldPreventDelete(dialogId, mid)) {
                    // Preserved locally in Colgram vault
                } else {
                    filtered.add(mid);
                }
            }
            messages = filtered;
            if (messages.isEmpty()) {
                return new java.util.ArrayList<>();
            }
        }
        """
            return content[:brace_idx + 1] + inject + content[brace_idx + 1:]
        patch_file(messages_storage, anti_delete_injector, "", "MessagesStorage Anti-Delete Preservation")

    # 21. MessagesController.java -> Save Message Edit History
    if os.path.exists(messages_controller):
        def edit_history_injector(content):
            target = "if (update instanceof TLRPC.TL_updateEditMessage) {"
            if target not in content:
                return content
            inject = """
        if (update instanceof TLRPC.TL_updateEditMessage) {
            TLRPC.TL_updateEditMessage uem = (TLRPC.TL_updateEditMessage) update;
            if (uem.message != null) {
                long did = uem.message.dialog_id != 0 ? uem.message.dialog_id : (uem.message.peer_id != null ? org.telegram.messenger.MessageObject.getPeerId(uem.message.peer_id) : 0);
                org.colgram.core.ColgramHookHandler.hookOnMessageEdited(did, uem.message.id, uem.message.message, uem.message.date);
            }
        } else if (update instanceof TLRPC.TL_updateEditChannelMessage) {
            TLRPC.TL_updateEditChannelMessage uem = (TLRPC.TL_updateEditChannelMessage) update;
            if (uem.message != null) {
                long did = uem.message.dialog_id != 0 ? uem.message.dialog_id : (uem.message.peer_id != null ? org.telegram.messenger.MessageObject.getPeerId(uem.message.peer_id) : 0);
                org.colgram.core.ColgramHookHandler.hookOnMessageEdited(did, uem.message.id, uem.message.message, uem.message.date);
            }
        }
        """
            return content.replace(target, inject + "\n        " + target, 1)
        patch_file(messages_controller, edit_history_injector, "", "MessagesController Save Edit History")

    # 22. ContactsController.java -> Disable Contact Sync & Suggest Contacts by Default
    contacts_controller = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ContactsController.java")
    if os.path.exists(contacts_controller):
        patch_file(
            contacts_controller,
            "public boolean contactsSync = true;",
            "public boolean contactsSync = false; // Colgram: anonymous by default",
            "ContactsController Disable contactsSync"
        )
        patch_file(
            contacts_controller,
            "public boolean suggestContacts = true;",
            "public boolean suggestContacts = false; // Colgram: anonymous by default",
            "ContactsController Disable suggestContacts"
        )

    # 23. ChatActivity.java -> Unlock Custom Wallpapers for All Chats Without Premium
    chat_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java")
    if os.path.exists(chat_activity):
        patch_file(
            chat_activity,
            "if (!getUserConfig().isPremium()) {\n            showCustomWallpaperPremiumAlert();",
            "if (false) {\n            showCustomWallpaperPremiumAlert();",
            "ChatActivity Unlock Custom Wallpaper Without Premium"
        )
    # 24. Theme.java -> Inject Colgram Cyber Red Colors
    theme_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "Theme.java")
    if os.path.exists(theme_file):
        def theme_cyber_injector(content):
            target = "public static int getColor(String key, ResourcesProvider resourcesProvider) {"
            if target not in content:
                target = "public static int getColor(String key) {"
            if target not in content:
                return content
            inject = """
        if (org.colgram.core.ColgramConfig.isCyberThemeEnabled()) {
            if ("featuredStickers_addButton".equals(key) || "chats_actionBackground".equals(key) || "switchTrackChecked".equals(key) || "dialogFloatingButton".equals(key)) {
                return 0xffff3344;
            }
            if ("windowBackgroundWhite".equals(key) || "windowBackgroundGray".equals(key)) {
                return 0xff0e0f12;
            }
            if ("actionBarDefault".equals(key)) {
                return 0xff16181e;
            }
        }
        """
            idx = content.find(target)
            brace_idx = content.find("{", idx)
            if brace_idx == -1:
                return content
            return content[:brace_idx + 1] + inject + content[brace_idx + 1:]
        patch_file(theme_file, theme_cyber_injector, "", "Theme Inject Colgram Cyber Red Colors")

    # 25. SettingsActivity.java -> Inject Colgram Settings Entry
    settings_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SettingsActivity.java")
    if os.path.exists(settings_activity):
        def settings_menu_injector(content):
            target = "listView.setOnItemClickListener((view, position) -> {"
            if target not in content:
                return content
            inject = """
            if (position == 1) {
                org.colgram.core.ColgramSettingsActivity.start(getParentActivity());
                return;
            }
            """
            return content.replace(target, target + inject, 1)
        patch_file(settings_activity, settings_menu_injector, "", "SettingsActivity Inject Colgram Settings Entry")



def download_official_binaries(repo_path):
    print("[*] Setting up precompiled official native libraries...")
    apk_url = "https://telegram.org/dl/android/apk"
    temp_apk = os.path.join(repo_path, "official_temp.apk")
    jni_libs_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "jniLibs")

    # 1. Disable externalNativeBuild and configure jniLibs unconditionally in TMessagesProj/build.gradle
    tmessages_gradle = os.path.join(repo_path, "TMessagesProj", "build.gradle")
    if os.path.exists(tmessages_gradle):
        with open(tmessages_gradle, "r", encoding="utf-8") as f:
            content = f.read()

        lines = content.split('\n')
        out_lines = []
        in_block = False
        brace_count = 0

        for line in lines:
            if 'externalNativeBuild {' in line:
                in_block = True
                brace_count = line.count('{') - line.count('}')
                continue
            if in_block:
                brace_count += line.count('{') - line.count('}')
                if brace_count <= 0:
                    in_block = False
                continue
            out_lines.append(line)

        gradle_content = '\n'.join(out_lines)
        gradle_content = gradle_content.replace(
            "sourceSets.main.jniLibs.srcDirs = ['./jni/']",
            "sourceSets.main.jniLibs.srcDirs = ['src/main/jniLibs']"
        )
        with open(tmessages_gradle, "w", encoding="utf-8") as f:
            f.write(gradle_content)
        print(" [+] Cleanly configured Gradle jniLibs and removed externalNativeBuild")

    # 2. Configure packagingOptions in all app and library modules to pickFirst on .so files
    for module in ["TMessagesProj", "TMessagesProj_AppStandalone"]:
        gradle_path = os.path.join(repo_path, module, "build.gradle")
        if os.path.exists(gradle_path):
            with open(gradle_path, "r", encoding="utf-8") as f:
                content = f.read()
            packaging_code = """
    packagingOptions {
        jniLibs {
            pickFirsts += ['**/*.so']
        }
    }
"""
            if "pickFirsts += ['**/*.so']" not in content:
                content = content.replace("android {", "android {" + packaging_code, 1)
                with open(gradle_path, "w", encoding="utf-8") as f:
                    f.write(content)
                print(f" [+] Added packagingOptions to {module}/build.gradle")

    # 3. Download official APK with retries
    success = False
    for attempt in range(4):
        try:
            print(f" -> Downloading official Telegram APK (attempt {attempt+1}/4)...")
            if os.path.exists(temp_apk):
                os.remove(temp_apk)

            # Try curl first
            curl_res = subprocess.run(
                ["curl", "-L", "--retry", "3", "--retry-delay", "2", "-s", "-o", temp_apk, apk_url],
                capture_output=True
            )
            if curl_res.returncode != 0 or not os.path.exists(temp_apk) or os.path.getsize(temp_apk) < 30 * 1024 * 1024:
                req = urllib.request.Request(apk_url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=120) as resp, open(temp_apk, 'wb') as out_file:
                    shutil.copyfileobj(resp, out_file)

            if os.path.exists(temp_apk) and os.path.getsize(temp_apk) > 30 * 1024 * 1024:
                with zipfile.ZipFile(temp_apk, 'r') as zip_ref:
                    if os.path.exists(jni_libs_dir):
                        shutil.rmtree(jni_libs_dir)
                    os.makedirs(jni_libs_dir, exist_ok=True)

                    for file_info in zip_ref.infolist():
                        if file_info.filename.startswith("lib/"):
                            if "liblanguage_id" in file_info.filename or not file_info.filename.endswith(".so"):
                                continue
                            rel_path = file_info.filename[len("lib/"):]
                            target_file = os.path.join(jni_libs_dir, rel_path)
                            os.makedirs(os.path.dirname(target_file), exist_ok=True)
                            with zip_ref.open(file_info) as src, open(target_file, 'wb') as dst:
                                shutil.copyfileobj(src, dst)
                    print(" [+] Successfully extracted prebuilt .so libraries to jniLibs!")
                    success = True
                    break
            else:
                print(f" [!] Attempt {attempt+1} produced small/invalid file.")
        except Exception as e:
            print(f" [!] Attempt {attempt+1} error: {e}")
        finally:
            if os.path.exists(temp_apk):
                os.remove(temp_apk)

    if not success:
        print(" [!] FATAL: Failed to download official Telegram APK for native .so extraction.")
        sys.exit(1)

def inject_core(repo_path, core_source_dir):
    print("[*] Injecting colgram-core module into project...")
    target_core_dir = os.path.join(repo_path, "colgram-core")
    if os.path.exists(target_core_dir):
        shutil.rmtree(target_core_dir)
    shutil.copytree(core_source_dir, target_core_dir)
    print(f" [+] colgram-core successfully copied to {target_core_dir}")

    # Add module to settings.gradle
    settings_gradle = os.path.join(repo_path, "settings.gradle")
    if os.path.exists(settings_gradle):
        with open(settings_gradle, "r", encoding="utf-8") as f:
            content = f.read()
        if "':colgram-core'" not in content:
            with open(settings_gradle, "a", encoding="utf-8") as f:
                f.write("\ninclude ':colgram-core'\n")
            print(" [+] Injected ':colgram-core' into settings.gradle")

    # Add dependency to TMessagesProj/build.gradle
    tmessages_gradle = os.path.join(repo_path, "TMessagesProj", "build.gradle")
    if os.path.exists(tmessages_gradle):
        with open(tmessages_gradle, "r", encoding="utf-8") as f:
            content = f.read()
        if "project(':colgram-core')" not in content:
            content = content.replace(
                "dependencies {",
                "dependencies {\n    implementation project(':colgram-core')"
            )
            with open(tmessages_gradle, "w", encoding="utf-8") as f:
                f.write(content)
            print(" [+] Added colgram-core dependency to TMessagesProj/build.gradle")

def configure_chaquopy_build(repo_path):
    print("[*] Configuring Chaquopy CPython plugin in root and application build.gradle...")

    # 1. Add Chaquopy classpath to root build.gradle
    root_gradle = os.path.join(repo_path, "build.gradle")
    if os.path.exists(root_gradle):
        with open(root_gradle, "r", encoding="utf-8") as f:
            content = f.read()

        if "com.chaquo.python:gradle" not in content:
            if "buildscript {" in content:
                content = content.replace(
                    "dependencies {",
                    "dependencies {\n        classpath 'com.chaquo.python:gradle:15.0.1'",
                    1
                )
                if "mavenCentral()" not in content:
                    content = content.replace(
                        "repositories {",
                        "repositories {\n        mavenCentral()",
                        1
                    )
            else:
                buildscript_block = """buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.chaquo.python:gradle:15.0.1'
    }
}
"""
                content = buildscript_block + content

            with open(root_gradle, "w", encoding="utf-8") as f:
                f.write(content)
            print(" [+] Configured Chaquopy classpath in root build.gradle")

    # 2. Apply Chaquopy plugin to TMessagesProj_AppStandalone (the application module)
    standalone_gradle = os.path.join(repo_path, "TMessagesProj_AppStandalone", "build.gradle")
    if os.path.exists(standalone_gradle):
        with open(standalone_gradle, "r", encoding="utf-8") as f:
            content = f.read()

        if "com.chaquo.python" not in content:
            # Apply plugin after android application plugin
            if "apply plugin: 'com.android.application'" in content:
                content = content.replace(
                    "apply plugin: 'com.android.application'",
                    "apply plugin: 'com.android.application'\napply plugin: 'com.chaquo.python'"
                )
            elif "id 'com.android.application'" in content or "id(\"com.android.application\")" in content:
                content = content.replace(
                    "id 'com.android.application'",
                    "id 'com.android.application'\n    id 'com.chaquo.python'"
                )

            # Add chaquopy config block before dependencies block
            chaquopy_block = """
chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

"""
            if "dependencies {" in content:
                content = content.replace("dependencies {", chaquopy_block + "dependencies {", 1)

            with open(standalone_gradle, "w", encoding="utf-8") as f:
                f.write(content)
            print(" [+] Applied Chaquopy plugin to TMessagesProj_AppStandalone/build.gradle")

    # 3. Ensure minSdkVersion 24 in TMessagesProj and TMessagesProj_AppStandalone
    for mod in ["TMessagesProj", "TMessagesProj_AppStandalone"]:
        mod_gradle = os.path.join(repo_path, mod, "build.gradle")
        if os.path.exists(mod_gradle):
            with open(mod_gradle, "r", encoding="utf-8") as f:
                m_content = f.read()
            m_content = re.sub(r'minSdkVersion\s+\d+', 'minSdkVersion 24', m_content)
            with open(mod_gradle, "w", encoding="utf-8") as f:
                f.write(m_content)
            print(f" [+] Ensured minSdkVersion 24 in {mod}/build.gradle")

    # 4. Add gradle.properties flags to disable configuration cache (Chaquopy compat)
    gradle_props = os.path.join(repo_path, "gradle.properties")
    if os.path.exists(gradle_props):
        with open(gradle_props, "r", encoding="utf-8") as f:
            props = f.read()
        if "org.gradle.configuration-cache" not in props:
            with open(gradle_props, "a", encoding="utf-8") as f:
                f.write("\norg.gradle.configuration-cache=false\n")
            print(" [+] Disabled Gradle configuration cache for Chaquopy compatibility")

def clone_required_submodules(repo_path):
    print("[*] Checking out required submodules for Gradle (media & jlatexmath)...")
    media_dir = os.path.join(repo_path, "TMessagesProj_Modules", "media")
    core_settings = os.path.join(media_dir, "core_settings.gradle")
    
    if not os.path.exists(core_settings):
        shutil.rmtree(media_dir, ignore_errors=True)
        print(" -> Cloning media submodule at pinned commit c822f1f33d30591fdbbf3919662be258f7cfbfc6...")
        subprocess.run(["git", "clone", "https://github.com/Arseny271/media.git", media_dir], check=True)
        subprocess.run(["git", "checkout", "c822f1f33d30591fdbbf3919662be258f7cfbfc6"], cwd=media_dir, check=True)
        print(" [+] Successfully checked out media submodule with core_settings.gradle")

    jlatex_dir = os.path.join(repo_path, "TMessagesProj", "lib", "jlatexmath")
    if not os.path.exists(os.path.join(jlatex_dir, "jlatexmath")):
        shutil.rmtree(jlatex_dir, ignore_errors=True)
        print(" -> Cloning jlatexmath submodule at pinned commit 919e50b2f6f64b04b712cdb13d558ff9ecf9c8ed...")
        subprocess.run(["git", "clone", "https://github.com/dkaraush/jlatexmath-android.git", jlatex_dir], check=True)
        subprocess.run(["git", "checkout", "919e50b2f6f64b04b712cdb13d558ff9ecf9c8ed"], cwd=jlatex_dir, check=True)
        print(" [+] Successfully checked out jlatexmath submodule")

def configure_package_and_branding(repo_path):
    print("[*] Configuring Colgram package identity and branding...")
    
    # 1. Update gradle.properties with unique package name
    gradle_props = os.path.join(repo_path, "gradle.properties")
    if os.path.exists(gradle_props):
        with open(gradle_props, "r", encoding="utf-8") as f:
            props = f.read()
        props = re.sub(r"APP_PACKAGE\s*=\s*.*", "APP_PACKAGE=org.colgram.messenger", props)
        with open(gradle_props, "w", encoding="utf-8") as f:
            f.write(props)
        print(" [+] Set APP_PACKAGE=org.colgram.messenger in gradle.properties")

    # 2. Update TMessagesProj_AppStandalone/build.gradle
    standalone_gradle = os.path.join(repo_path, "TMessagesProj_AppStandalone", "build.gradle")
    if os.path.exists(standalone_gradle):
        with open(standalone_gradle, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace("defaultConfig.applicationId = APP_PACKAGE", 'defaultConfig.applicationId = "org.colgram.messenger"')
        with open(standalone_gradle, "w", encoding="utf-8") as f:
            f.write(content)
        print(" [+] Set applicationId = org.colgram.messenger in TMessagesProj_AppStandalone/build.gradle")

    # 3. Fix TMessagesProj_AppStandalone/src/main/AndroidManifest.xml (Icon, RoundIcon, Label)
    standalone_manifest = os.path.join(repo_path, "TMessagesProj_AppStandalone", "src", "main", "AndroidManifest.xml")
    if os.path.exists(standalone_manifest):
        with open(standalone_manifest, "r", encoding="utf-8") as f:
            m_content = f.read()
        old_app_tag = '<application android:name="org.telegram.messenger.ApplicationLoaderImpl" tools:replace="name">'
        new_app_tag = '<application android:name="org.telegram.messenger.ApplicationLoaderImpl" android:icon="@mipmap/ic_launcher_sa" android:roundIcon="@mipmap/ic_launcher_sa" android:label="Colgram" tools:replace="name,icon,roundIcon,label">'
        if old_app_tag in m_content:
            m_content = m_content.replace(old_app_tag, new_app_tag)
            with open(standalone_manifest, "w", encoding="utf-8") as f:
                f.write(m_content)
            print(" [+] Injected icon and label into TMessagesProj_AppStandalone AndroidManifest.xml")

    # 4. Fix TMessagesProj/src/main/AndroidManifest.xml (DefaultIcon & Application)
    main_manifest = os.path.join(repo_path, "TMessagesProj", "src", "main", "AndroidManifest.xml")
    if os.path.exists(main_manifest):
        with open(main_manifest, "r", encoding="utf-8") as f:
            m_content = f.read()
        
        # Set icon on DefaultIcon activity-alias
        old_alias = """        <activity-alias
            android:enabled="true"
            android:name="org.telegram.messenger.DefaultIcon"
            android:targetActivity="org.telegram.ui.LaunchActivity"
            android:exported="true">"""
        new_alias = """        <activity-alias
            android:enabled="true"
            android:name="org.telegram.messenger.DefaultIcon"
            android:targetActivity="org.telegram.ui.LaunchActivity"
            android:icon="@mipmap/ic_launcher_sa"
            android:roundIcon="@mipmap/ic_launcher_sa"
            android:label="Colgram"
            android:exported="true">"""
        if old_alias in m_content:
            m_content = m_content.replace(old_alias, new_alias)

        # Set icon on <application
        m_content = m_content.replace(
            'android:name="org.telegram.messenger.ApplicationLoader"',
            'android:name="org.telegram.messenger.ApplicationLoader"\n        android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher_round"\n        android:label="Colgram"'
        )

        # Strip phone, contacts, location, and account permissions from AndroidManifest.xml for full user privacy
        for perm in [
            "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
            "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"
        ]:
            m_content = m_content.replace(
                f'<uses-permission android:name="android.permission.{perm}" />',
                f'<!-- stripped {perm} for Colgram privacy -->'
            )

        with open(main_manifest, "w", encoding="utf-8") as f:
            f.write(m_content)
        print(" [+] Injected icon and label, stripped aggressive permissions in TMessagesProj AndroidManifest.xml")

    # 5. Patch google-services.json so GoogleServices plugin finds org.colgram.messenger
    import json
    import copy
    for root, _, files in os.walk(repo_path):
        for f in files:
            if f == "google-services.json":
                path = os.path.join(root, f)
                try:
                    with open(path, "r", encoding="utf-8") as jf:
                        data = json.load(jf)
                    clients = data.get("client", [])
                    has_colgram = any(c.get("client_info", {}).get("android_client_info", {}).get("package_name") == "org.colgram.messenger" for c in clients)
                    if not has_colgram and clients:
                        new_client = copy.deepcopy(clients[0])
                        new_client["client_info"]["android_client_info"]["package_name"] = "org.colgram.messenger"
                        clients.append(new_client)
                        data["client"] = clients
                        with open(path, "w", encoding="utf-8") as jf:
                            json.dump(data, jf, indent=2)
                        print(f" [+] Added org.colgram.messenger to {path}")
                except Exception as e:
                    print(f" [!] Error patching {path}: {e}")

def apply_custom_app_icon(repo_path, source_icon_path):
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    prebuilt_icons_dir = os.path.join(root_dir, "assets", "icons")

    target_dirs = [
        os.path.join(repo_path, "TMessagesProj", "src", "main", "res"),
        os.path.join(repo_path, "TMessagesProj_AppStandalone", "src", "main", "res")
    ]

    # If prebuilt icons exist, copy them directly (no external dependencies needed)
    if os.path.exists(prebuilt_icons_dir):
        print("[*] Copying pre-generated custom Colgram avatar icons...")
        for density_name in os.listdir(prebuilt_icons_dir):
            src_density = os.path.join(prebuilt_icons_dir, density_name)
            if not os.path.isdir(src_density):
                continue
            for res_dir in target_dirs:
                if not os.path.exists(res_dir):
                    continue
                dest_density = os.path.join(res_dir, density_name)
                os.makedirs(dest_density, exist_ok=True)
                for file_name in os.listdir(src_density):
                    shutil.copy2(os.path.join(src_density, file_name), os.path.join(dest_density, file_name))
        print(" [+] Custom Colgram avatar successfully applied across all mipmap densities!")
        return

    if not os.path.exists(source_icon_path):
        print(f" [!] Source icon not found at: {source_icon_path}")
        return

    try:
        from PIL import Image
    except ImportError:
        print(" [!] Pillow not available and no prebuilt icons found, skipping icon resizing.")
        return

    print("[*] Generating custom Colgram avatar across all mipmap densities...")
    base_img = Image.open(source_icon_path).convert("RGBA")
    
    sizes = {
        "mdpi": (48, 108),
        "hdpi": (72, 162),
        "xhdpi": (96, 216),
        "xxhdpi": (144, 324),
        "xxxhdpi": (192, 432),
    }

    for res_dir in target_dirs:
        if not os.path.exists(res_dir):
            continue
        for density, (icon_size, fg_size) in sizes.items():
            mipmap_dir = os.path.join(res_dir, f"mipmap-{density}")
            os.makedirs(mipmap_dir, exist_ok=True)
            
            icon_img = base_img.resize((icon_size, icon_size), Image.LANCZOS)
            for name in ["ic_launcher.png", "ic_launcher_round.png", "ic_launcher_sa.png", "icon_2_launcher.png", "icon_2_launcher_round.png"]:
                icon_img.save(os.path.join(mipmap_dir, name), "PNG")

            fg_img = base_img.resize((fg_size, fg_size), Image.LANCZOS)
            for name in ["icon_foreground.png", "icon_foreground_sa.png", "icon_foreground_round.png"]:
                fg_img.save(os.path.join(mipmap_dir, name), "PNG")

    print(" [+] Custom Colgram avatar successfully generated across all mipmap densities!")

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    core_dir = os.path.join(root_dir, "colgram-core")
    custom_icon = os.path.join(root_dir, "assets", "app_icon.png")

    target_repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root_dir, "Telegram")

    if not os.path.exists(target_repo):
        print(f"[!] Target Telegram repo not found at: {target_repo}")
        sys.exit(1)

    clone_required_submodules(target_repo)
    configure_package_and_branding(target_repo)
    apply_custom_app_icon(target_repo, custom_icon)
    inject_core(target_repo, core_dir)
    configure_chaquopy_build(target_repo)
    download_official_binaries(target_repo)
    inject_hooks(target_repo)
    print("\n[+] Colgram setup complete! Ready to build APK.")

if __name__ == "__main__":
    main()
