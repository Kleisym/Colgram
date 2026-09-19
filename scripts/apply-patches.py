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

    if replacement and replacement.strip() in content:
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
    # 1b. Let Colgram track which screen is on top. Several Telegram APIs that plugin
    #     features need (notably SendMessagesHelper.editMessage) require a live fragment
    #     and fail silently without one. colgram-core cannot reference Activity or
    #     BaseFragment at compile time, so it registers lifecycle callbacks here instead.
    patch_file(
        app_loader,
        "org.colgram.core.ColgramHookHandler.init(applicationContext);",
        "org.colgram.core.ColgramHookHandler.init(applicationContext);\n            org.colgram.core.ColgramUiBridge.install(this);",
        "ApplicationLoader.onCreate UI bridge registration"
    )

    # 2. ConnectionsManager.java -> Hardware & OS Cloaking
    conn_manager = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "ConnectionsManager.java")
    
    def cloak_replacer(content):
        if "org.colgram.core.ColgramHookHandler.hookInitConnection" in content:
            return content
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

    patch_file(conn_manager, cloak_replacer, "org.colgram.core.ColgramHookHandler.hookInitConnection", "ConnectionsManager MTProto Cloaking")

    # 2b. ConnectionsManager.java -> QR Login Token Updates Hook
    def conn_qr_replacer(content):
        if "ColgramQRLoginBottomSheet.onLoginTokenUpdate" in content:
            return content
        target1 = "FileLog.dumpUnparsedMessage(message, messageId, currentAccount);"
        inject1 = """FileLog.dumpUnparsedMessage(message, messageId, currentAccount);
            if (constructor == 0x564fe691 || message instanceof org.telegram.tgnet.tl.TL_update.TL_updateLoginToken) {
                org.telegram.ui.ColgramQRLoginBottomSheet.onLoginTokenUpdate(currentAccount);
            }"""
        if target1 in content:
            content = content.replace(target1, inject1, 1)
        return content

    patch_file(conn_manager, conn_qr_replacer, "org.telegram.ui.ColgramQRLoginBottomSheet.onLoginTokenUpdate(currentAccount);", "ConnectionsManager QR Login Token Hook")

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

    # 10. ColgramBotLoginBottomSheet.java & ColgramQRLoginBottomSheet.java -> Deploy Telegram-Native BottomSheets
    bot_sheet_template = os.path.join(os.path.dirname(__file__), "templates", "ColgramBotLoginBottomSheet.java")
    bot_sheet_dest = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramBotLoginBottomSheet.java")
    if os.path.exists(bot_sheet_template):
        shutil.copyfile(bot_sheet_template, bot_sheet_dest)
        print(" [+] Deployed Telegram-Native ColgramBotLoginBottomSheet.java")

    qr_sheet_template = os.path.join(os.path.dirname(__file__), "templates", "ColgramQRLoginBottomSheet.java")
    qr_sheet_dest = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramQRLoginBottomSheet.java")
    if os.path.exists(qr_sheet_template):
        shutil.copyfile(qr_sheet_template, qr_sheet_dest)
        print(" [+] Deployed Telegram-Native ColgramQRLoginBottomSheet.java")

    # 11. LoginActivity.java -> Make onAuthSuccess public
    patch_file(
        login_activity,
        "private void onAuthSuccess(TLRPC.TL_auth_authorization res) {",
        "public void onAuthSuccess(TLRPC.TL_auth_authorization res) {",
        "LoginActivity Make onAuthSuccess Public"
    )

    # 12. LoginActivity.java -> Inject QR Login & Bot Token Login buttons between subtitleView and countryButton
    alt_login_btn = """addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));

            LinearLayout altButtonsRow = new LinearLayout(context);
            altButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
            altButtonsRow.setGravity(Gravity.CENTER);
            boolean isRuLang = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            int accentBtnColor = Theme.getColor(Theme.key_featuredStickers_addButton);
            if (accentBtnColor == 0) accentBtnColor = 0xFF2AABEE;

            TextView qrLoginBtn = new TextView(context);
            qrLoginBtn.setText(isRuLang ? "📷 Вход по QR" : "📷 QR Log in");
            qrLoginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            qrLoginBtn.setTypeface(AndroidUtilities.bold());
            qrLoginBtn.setTextColor(accentBtnColor);
            qrLoginBtn.setGravity(Gravity.CENTER);
            qrLoginBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
            qrLoginBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), accentBtnColor & 0x18ffffff, accentBtnColor & 0x33ffffff));
            qrLoginBtn.setOnClickListener(v -> {
                org.telegram.ui.ColgramQRLoginBottomSheet.show(LoginActivity.this, currentAccount);
            });
            altButtonsRow.addView(qrLoginBtn, LayoutHelper.createLinear(0, 36, 1.0f, Gravity.CENTER, 0, 0, 6, 0));

            TextView botLoginBtn = new TextView(context);
            botLoginBtn.setText(isRuLang ? "🤖 Токен бота" : "🤖 Bot Token");
            botLoginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            botLoginBtn.setTypeface(AndroidUtilities.bold());
            botLoginBtn.setTextColor(accentBtnColor);
            botLoginBtn.setGravity(Gravity.CENTER);
            botLoginBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
            botLoginBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), accentBtnColor & 0x18ffffff, accentBtnColor & 0x33ffffff));
            botLoginBtn.setOnClickListener(v -> {
                org.telegram.ui.ColgramBotLoginBottomSheet.show(LoginActivity.this, currentAccount);
            });
            altButtonsRow.addView(botLoginBtn, LayoutHelper.createLinear(0, 36, 1.0f, Gravity.CENTER, 6, 0, 0, 0));

            addView(altButtonsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 14, 32, 6));"""

    def login_btn_replacer(content):
        # Remove legacy vertical button layout if present
        legacy_pattern = """addView(phoneOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));
            LinearLayout altLoginButtonsLayout = new LinearLayout(context);"""
        if legacy_pattern in content:
            import re
            content = re.sub(
                r'LinearLayout altLoginButtonsLayout = new LinearLayout\(context\);.*?addView\(altLoginButtonsLayout, LayoutHelper\.createLinear\(LayoutHelper\.MATCH_PARENT, LayoutHelper\.WRAP_CONTENT, Gravity\.CENTER_HORIZONTAL, 16, 2, 76, 4\)\);',
                '',
                content,
                flags=re.DOTALL
            )
        if "altButtonsRow" in content:
            return content
        target = "addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));"
        if target in content:
            return content.replace(target, alt_login_btn, 1)
        return content

    patch_file(
        login_activity,
        login_btn_replacer,
        alt_login_btn,
        "LoginActivity QR Login & Bot Token Buttons"
    )

    # 13. LoginActivity.java -> back button on VIEW_PHONE_INPUT
    #
    # HISTORY: an earlier Colgram build pushed a NEW IntroActivity onto the stack while
    # leaving the LoginActivity alive, so re-entering "Start Messaging" stacked a second
    # LoginActivity and backing out got stuck in a growing back stack.
    #
    # That patch is now OBSOLETE AND HARMFUL. Current upstream already guards it:
    #
    #     if (activityMode == MODE_LOGIN && (parentLayout == null
    #             || parentLayout.getFragmentStack().size() <= 1)) {
    #         presentFragment(new IntroActivity(), true);
    #         return false;
    #     }
    #
    # i.e. it only pushes IntroActivity when there is nothing to pop. The old anchor no
    # longer matches this code, and forcing our own replacement would duplicate the
    # guard. So we only record whether upstream is already correct, and stay out of it.
    login_activity_onback_ok = False
    if os.path.exists(login_activity):
        with open(login_activity, "r", encoding="utf-8", errors="ignore") as f:
            _la = f.read()
        login_activity_onback_ok = "getFragmentStack().size() <= 1" in _la
    if login_activity_onback_ok:
        print(" [=] LoginActivity back navigation already correct upstream - no patch needed")
    else:
        print(" [!] LoginActivity back navigation differs from the expected upstream form; "
              "inspect onBackPressed(boolean) manually.")

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

    # 15. IntroActivity.java -> Full Instant Language Switching & Top-Right Language Badge
    intro_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "IntroActivity.java")
    if os.path.exists(intro_activity):
        def intro_lang_replacer(content):
            if "langBadge.setText" in content:
                return content
            
            # 1. Start messaging button text & click listener with language persistence
            old_btn_pattern = """        startMessagingButton.setText(LocaleController.getString(R.string.StartMessaging));
        startMessagingButton.setGravity(Gravity.CENTER);
        startMessagingButton.setTypeface(AndroidUtilities.bold());
        startMessagingButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        startMessagingButton.setPadding(dp(34), 0, dp(34), 0);
        frameContainerView.addView(startMessagingButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0, 16, 76));
        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
            destroyed = true;
        });"""
            new_btn_code = """        boolean isRuStart = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);

        startMessagingButton.setText(isRuStart ? "Начать общение" : "Start Messaging");
        startMessagingButton.setGravity(Gravity.CENTER);
        startMessagingButton.setTypeface(AndroidUtilities.bold());
        startMessagingButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        startMessagingButton.setPadding(dp(34), 0, dp(34), 0);
        frameContainerView.addView(startMessagingButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0, 16, 76));
        startMessagingButton.setOnClickListener(view -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            // Do NOT call applyLanguage() here.
            //
            // applyLanguage() is a full locale reload: it re-parses resources, resets the
            // connection's lang code, and can trigger a remote language fetch. Running that
            // synchronously on the UI thread immediately before a fragment transition
            // stalls the transition — which is the "second press does nothing until
            // restart" bug.
            //
            // It is also redundant: the language was already applied when the user picked
            // it in the intro (see the switch-language handler below). We only need to make
            // sure the chosen language is persisted so it survives the restart.
            LocaleController.LocaleInfo cur = LocaleController.getInstance().getCurrentLocaleInfo();
            if (cur != null) {
                MessagesController.getGlobalMainSettings().edit().putString("language", cur.getKey()).apply();
            }

            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);
            destroyed = true;
        });"""
            if old_btn_pattern in content:
                content = content.replace(old_btn_pattern, new_btn_code, 1)

            # Colgram re-entry fix.
            #
            # `destroyed` is IntroActivity's guard for its EGL/render callbacks — see the
            # comment at the frame callback: "If display or surface already destroyed".
            # Upstream sets it true when we navigate away. An earlier Colgram revision
            # changed it to false, so the intro activity kept its render thread alive after
            # being left. Pressing "Начать общение" a second time then ran a fresh instance
            # alongside the stale thread, and the fragment transition deadlocked — the login
            # form never appeared until the process was restarted.
            #
            # Restore true, and guard any stray false that survived from an earlier run.
            if "destroyed = true;" in content and content.count("destroyed = false;") > 0:
                content = content.replace(
                    "presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);\n            destroyed = false;",
                    "presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);\n            destroyed = true;"
                )

            # 2. Switch language text view & top-right language badge
            old_switch_pattern = """        switchLanguageTextView = new TextView(context);
        switchLanguageTextView.setGravity(Gravity.CENTER);
        switchLanguageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        frameContainerView.addView(switchLanguageTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));
        switchLanguageTextView.setOnClickListener(v -> {
            if (startPressed || localeInfo == null) {
                return;
            }
            startPressed = true;

            AlertDialog loaderDialog = new AlertDialog(v.getContext(), AlertDialog.ALERT_TYPE_SPINNER);
            loaderDialog.setCanCancel(false);
            loaderDialog.showDelayed(1000);

            NotificationCenter.getGlobalInstance().addObserver(new NotificationCenter.NotificationCenterDelegate() {
                @Override
                public void didReceivedNotification(int id, int account, Object... args) {
                    if (id == NotificationCenter.reloadInterface) {
                        loaderDialog.dismiss();

                        NotificationCenter.getGlobalInstance().removeObserver(this, id);
                        AndroidUtilities.runOnUIThread(()->{
                            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), true);
                            destroyed = true;
                        }, 100);
                    }
                }
            }, NotificationCenter.reloadInterface);
            LocaleController.getInstance().applyLanguage(localeInfo, true, false, currentAccount);
        });"""
            new_switch_code = """        switchLanguageTextView = new TextView(context);
        switchLanguageTextView.setGravity(Gravity.CENTER);
        switchLanguageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        switchLanguageTextView.setText(isRuStart ? "Continue in English" : "Продолжить на русском");
        frameContainerView.addView(switchLanguageTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));
        switchLanguageTextView.setOnClickListener(v -> {
            if (startPressed) {
                return;
            }
            startPressed = true;

            boolean currentlyRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                    && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            String targetCode = currentlyRu ? "en" : "ru";
            LocaleController.LocaleInfo targetInfo = null;
            for (LocaleController.LocaleInfo info : LocaleController.getInstance().languages) {
                if (info != null && targetCode.equalsIgnoreCase(info.shortName)) {
                    targetInfo = info;
                    break;
                }
            }
            if (targetInfo != null) {
                LocaleController.getInstance().applyLanguage(targetInfo, true, false, currentAccount);
                MessagesController.getGlobalMainSettings().edit().putString("language", targetInfo.getKey()).apply();
            }
            presentFragment(new LoginActivity().setIntroView(frameContainerView, startMessagingButton), false);
            destroyed = true;
        });

        // Top-right language badge
        TextView langBadge = new TextView(context);
        langBadge.setText(isRuStart ? "🇷🇺 RU" : "🇬🇧 EN");
        langBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        langBadge.setTypeface(AndroidUtilities.bold());
        langBadge.setTextColor(0xFFFFFFFF);
        langBadge.setGravity(Gravity.CENTER);
        langBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        langBadge.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), 0x22FFFFFF, 0x44FFFFFF));
        frameContainerView.addView(langBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32, Gravity.TOP | Gravity.RIGHT, 0, 16, 16, 0));
        langBadge.setOnClickListener(v -> {
            boolean nowRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                    && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            String target = nowRu ? "en" : "ru";
            LocaleController.LocaleInfo targetInfo = null;
            for (LocaleController.LocaleInfo info : LocaleController.getInstance().languages) {
                if (info != null && target.equalsIgnoreCase(info.shortName)) {
                    targetInfo = info;
                    break;
                }
            }
            if (targetInfo != null) {
                LocaleController.getInstance().applyLanguage(targetInfo, true, false, currentAccount);
                MessagesController.getGlobalMainSettings().edit().putString("language", targetInfo.getKey()).apply();
                langBadge.setText(target.equals("ru") ? "🇷🇺 RU" : "🇬🇧 EN");
                startMessagingButton.setText(target.equals("ru") ? "Начать общение" : "Start Messaging");
                switchLanguageTextView.setText(target.equals("ru") ? "Continue in English" : "Продолжить на русском");
            }
        });"""
            if old_switch_pattern in content:
                content = content.replace(old_switch_pattern, new_switch_code, 1)

            # 3. Fast checkContinueText without network request
            start_tag = "private void checkContinueText() {"
            end_tag = "ConnectionsManager.RequestFlagWithoutLogin);"
            idx1 = content.find(start_tag)
            if idx1 != -1:
                idx2 = content.find(end_tag, idx1)
                if idx2 != -1:
                    brace_idx = content.find("}", idx2 + len(end_tag))
                    if brace_idx != -1:
                        end_pos = brace_idx + 1
                        new_check = """private void checkContinueText() {
        boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
        if (startMessagingButton != null) {
            startMessagingButton.setText(isRu ? "Начать общение" : "Start Messaging");
        }
        if (switchLanguageTextView != null) {
            switchLanguageTextView.setText(isRu ? "Continue in English" : "Продолжить на русском");
        }
    }"""
                        content = content[:idx1] + new_check + content[end_pos:]

            # 4. onResume resets startPressed & destroyed
            target_resume = "public void onResume() {\n        super.onResume();"
            if target_resume in content and "startPressed = false;" not in content:
                content = content.replace(target_resume, target_resume + "\n        startPressed = false;\n        destroyed = false;", 1)

            return content

        patch_file(intro_activity, intro_lang_replacer, "langBadge.setText", "IntroActivity Instant Language Switcher & Badge")

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
        target = "private void checkPromoInfoInternal(boolean reset) {"
        if target not in content:
            return content
        inject = "private void checkPromoInfoInternal(boolean reset) {\n        if (true) return; // Colgram: kill proxy sponsor channels"
        return content.replace(target, inject, 1)
    patch_file(messages_controller, promo_suppressor, "if (true) return; // Colgram: kill proxy sponsor channels", "MessagesController Suppress checkPromoInfo")

    # 19. DialogsActivity.java -> Make Proxy Button Always Visible In Header & Popup Menu
    dialogs_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java")
    if os.path.exists(dialogs_activity):
        patch_file(
            dialogs_activity,
            "final boolean proxyVisible = proxyEnabled && !TextUtils.isEmpty(proxyAddress)",
            "final boolean proxyVisible = true; // Colgram: proxy menu item always visible\n            final boolean proxyVisibleOld = proxyEnabled && !TextUtils.isEmpty(proxyAddress)",
            "DialogsActivity Proxy Menu Item Always Visible"
        )
        def header_proxy_updater(content):
            target_replacement = """downloadsItem.setVisibility(View.GONE);

            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            if (colgramProxyItem != null) {
                colgramProxyItem.setContentDescription(getString(R.string.ProxySettings));
                colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });
            }

            updateProxyButton(false, false);"""
            if "org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity())" in content:
                return content
            if "colgramProxyItem.setOnClickListener(v -> presentFragment(new ProxyListActivity()));" in content:
                return content.replace(
                    "colgramProxyItem.setOnClickListener(v -> presentFragment(new ProxyListActivity()));",
                    """colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });"""
                )
            target = "downloadsItem.setVisibility(View.GONE);"
            inject = """downloadsItem.setVisibility(View.GONE);

            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            if (colgramProxyItem != null) {
                colgramProxyItem.setContentDescription(getString(R.string.ProxySettings));
                colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });
            }"""
            if target in content:
                return content.replace(target, inject, 1)
            return content
        patch_file(
            dialogs_activity,
            header_proxy_updater,
            "org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity())",
            "DialogsActivity Header Proxy Button 1-Tap Toggle"
        )

        def options_menu_injector(content):
            target = """        io.add(R.drawable.outline_saved_24, getString(R.string.SavedMessages), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            presentFragment(new ChatActivity(args));
        });"""
            if "org.colgram.core.ColgramPluginsActivity.start(getParentActivity())" in content:
                return content
            inject = """
        io.addGap();
        io.add(R.drawable.msg_customize, "🧩 Плагины и Маркетплейс", () -> {
            presentFragment(new ColgramPluginsActivity());
        });
        io.add(R.drawable.msg_settings, "⚙️ Настройки Colgram", () -> {
            presentFragment(new ColgramSettingsActivity());
        });
        io.add(R.drawable.msg_send, "✉️ Временная почта (Temp Mail)", () -> {
            presentFragment(new ColgramTempMailActivity());
        });
        io.add(R.drawable.msg_download, "📥 Версии Telegram", () -> {
            presentFragment(new ColgramVersionsActivity());
        });
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            io.add(R.drawable.msg_retry, "🔄 Синхронизировать чаты бота", () -> {
                org.colgram.core.ColgramBotSync.syncBotDialogs(getParentActivity(), currentAccount, true);
            });
            io.add(R.drawable.msg_edit, "✉️ Написать от имени бота", () -> {
                org.colgram.core.ColgramBotSync.showStartChatDialog(getParentActivity(), currentAccount);
            });
        }"""
            return content.replace(target, target + inject, 1)

        patch_file(
            dialogs_activity,
            options_menu_injector,
            "org.colgram.core.ColgramPluginsActivity.start(getParentActivity())",
            "DialogsActivity Options Menu Plugins and Bot Sync Entries"
        )

    # 20. MessagesStorage.java -> Anti-Delete (Preserve Deleted Messages In Local DB)
    messages_storage = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesStorage.java")
    if os.path.exists(messages_storage):
        def anti_delete_injector(content):
            target = "public ArrayList<Long> markMessagesAsDeleted(long dialogId, ArrayList<Integer> messages, boolean useQueue, boolean deleteFiles, int mode, int topicId) {"
            if target not in content:
                return content
            inject = """public ArrayList<Long> markMessagesAsDeleted(long dialogId, ArrayList<Integer> messages, boolean useQueue, boolean deleteFiles, int mode, int topicId) {
        if (org.colgram.core.ColgramConfig.isAntiDeleteEnabled() && messages != null) {
            java.util.ArrayList<Integer> toRemove = new java.util.ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                int mid = messages.get(i);
                if (org.colgram.core.ColgramHookHandler.hookShouldPreventDelete(dialogId, mid)) {
                    toRemove.add(mid);
                }
            }
            messages.removeAll(toRemove);
            if (messages.isEmpty()) {
                return new java.util.ArrayList<>();
            }
        }"""
            return content.replace(target, inject, 1)
        patch_file(messages_storage, anti_delete_injector, "messages.removeAll(toRemove);", "MessagesStorage Anti-Delete Preservation")

    # 21. MessagesController.java -> Save Message Edit History
    if os.path.exists(messages_controller):
        def edit_history_injector(content):
            target = "} else if (baseUpdate instanceof TL_update.TL_updateEditChannelMessage || baseUpdate instanceof TL_update.TL_updateEditMessage) {"
            if target not in content:
                return content
            inject = """
                try {
                    TLRPC.Message colgramEditMsg = (baseUpdate instanceof TL_update.TL_updateEditChannelMessage) ? ((TL_update.TL_updateEditChannelMessage) baseUpdate).message : ((TL_update.TL_updateEditMessage) baseUpdate).message;
                    if (colgramEditMsg != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
                        long did = colgramEditMsg.dialog_id != 0 ? colgramEditMsg.dialog_id : (colgramEditMsg.peer_id != null ? org.telegram.messenger.MessageObject.getPeerId(colgramEditMsg.peer_id) : 0);
                        // Read the PREVIOUS revision from local storage BEFORE the update overwrites it.
                        // The incoming update carries only the NEW text, so the old text must come from the DB.
                        String colgramPrevText = null;
                        try {
                            TLRPC.Message colgramStored = getMessagesStorage().getMessage(did, colgramEditMsg.id);
                            if (colgramStored != null && colgramStored.message != null && !colgramStored.message.equals(colgramEditMsg.message)) {
                                colgramPrevText = colgramStored.message;
                            }
                        } catch (Throwable ignoreInner) {}
                        org.colgram.core.ColgramHookHandler.hookOnMessageEdited(did, colgramEditMsg.id, colgramPrevText, colgramEditMsg.date);
                    }
                } catch (Throwable ignore) {}"""
            return content.replace(target, target + inject, 1)
        patch_file(messages_controller, edit_history_injector, "org.colgram.core.ColgramConfig.isEditHistoryEnabled()", "MessagesController Save Edit History")


    # 24. Theme.java -> Inject Colgram Cyber Red Colors
    theme_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "Theme.java")
    if os.path.exists(theme_file):
        def theme_cyber_injector(content):
            target = "public static int getColor(int key, ResourcesProvider provider) {"
            if target not in content:
                return content
            inject = """
        if (org.colgram.core.ColgramConfig.isCyberThemeEnabled()) {
            if (key == key_chats_actionBackground || key == key_dialogFloatingButton || key == key_switchTrackChecked) {
                return 0xffff3344;
            }
            if (key == key_windowBackgroundWhite || key == key_windowBackgroundGray) {
                return 0xff0e0f12;
            }
            if (key == key_actionBarDefault) {
                return 0xff16181e;
            }
        }"""
            return content.replace(target, target + inject, 1)
        patch_file(theme_file, theme_cyber_injector, "org.colgram.core.ColgramConfig.isCyberThemeEnabled()", "Theme Inject Colgram Cyber Red Colors")


    # 24.1. Deploy Telegram-Native UI Screens from templates
    template_dir = os.path.join(os.path.dirname(__file__), "templates")
    ui_dest_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")
    os.makedirs(ui_dest_dir, exist_ok=True)
    for name in ["ColgramSettingsActivity.java", "ColgramPluginsActivity.java", "ColgramTempMailActivity.java", "ColgramVersionsActivity.java", "ColgramEditHistorySheet.java"]:
        src_t = os.path.join(template_dir, name)
        dst_t = os.path.join(ui_dest_dir, name)
        if os.path.exists(src_t):
            shutil.copyfile(src_t, dst_t)
            print(f" [+] Deployed Telegram-Native {name}")
        else:
            print(f" [!] Warning: template {src_t} not found")

    # 25. SettingsActivity.java -> Deep Integration of Colgram Settings, Plugins, TempMail, Versions
    settings_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SettingsActivity.java")
    if os.path.exists(settings_activity):
        def settings_items_injector(content):
            target = "items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));"
            if target not in content:
                return content
            inject = """items.add(SettingCell.Factory.of(10, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));

        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Colgram"));
        items.add(SettingCell.Factory.of(101, 0xFFFF3344, 0xFFCC1122, R.drawable.msg_settings, "Настройки Colgram", "Анонимность, защита от удаления, обход блокировок"));
        items.add(SettingCell.Factory.of(102, 0xFF9C27B0, 0xFF673AB7, R.drawable.msg_customize, "Плагины и Маркетплейс", "Каталог расширений exteraGram, Python скрипты"));
        items.add(SettingCell.Factory.of(103, 0xFF00BCD4, 0xFF009688, R.drawable.msg_send, "Временная почта (Temp Mail)", "Быстрая анонимная регистрация без спама"));
        items.add(SettingCell.Factory.of(104, 0xFF4CAF50, 0xFF2E7D32, R.drawable.msg_download, "Версии Telegram и обновления", "Переключение каналов и загрузка APK"));
        items.add(UItem.asShadow(null));"""
            return content.replace(target, inject, 1)

        patch_file(settings_activity, settings_items_injector, "items.add(SettingCell.Factory.of(101", "SettingsActivity Inject Native Colgram Section in List")

        def settings_clicks_injector(content):
            target = """            case 10:
                presentSettingFragment(new LanguageSelectActivity());
                break;"""
            if target not in content:
                return content
            inject = """            case 10:
                presentSettingFragment(new LanguageSelectActivity());
                break;
            case 101:
                presentSettingFragment(new ColgramSettingsActivity());
                break;
            case 102:
                presentSettingFragment(new ColgramPluginsActivity());
                break;
            case 103:
                presentSettingFragment(new ColgramTempMailActivity());
                break;
            case 104:
                presentSettingFragment(new ColgramVersionsActivity());
                break;"""
            return content.replace(target, inject, 1)

        patch_file(settings_activity, settings_clicks_injector, "case 101:", "SettingsActivity Route Colgram Items Clicks")

    # 26. DialogsActivity.java & ContactsActivity.java -> Complete Permission Suppression
    if os.path.exists(dialogs_activity):
        patch_file(
            dialogs_activity,
            "if (hasNotNotificationsPermission || hasNotContactsPermission || hasNotStoragePermission)",
            "if (false && (hasNotNotificationsPermission || hasNotContactsPermission || hasNotStoragePermission))",
            "DialogsActivity Suppress Startup Permission Dialogs"
        )
        patch_file(
            dialogs_activity,
            "private void askForPermissons(boolean alert) {",
            """private void askForPermissons(boolean alert) {
        if (true) return;""",
            "DialogsActivity Suppress askForPermissons"
        )

    contacts_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ContactsActivity.java")
    if os.path.exists(contacts_activity):
        patch_file(
            contacts_activity,
            "private void askForPermissons(boolean alert) {",
            """private void askForPermissons(boolean alert) {
        if (true) return;""",
            "ContactsActivity Suppress askForPermissons"
        )

    # 27. MessagesController.java -> Fix Bot Account Loading Dialogs (Handle BOT_METHOD_INVALID)
    if os.path.exists(messages_controller):
        bot_dialogs_target = "public void loadDialogs(final int folderId, int offset, int count, boolean fromCache, Runnable onEmptyCallback) {"
        bot_dialogs_inject = """public void loadDialogs(final int folderId, int offset, int count, boolean fromCache, Runnable onEmptyCallback) {
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            loadingDialogs.put(folderId, false);
            dialogsEndReached.put(folderId, true);
            serverDialogsEndReached.put(folderId, true);
            if (fromCache) {
                getMessagesStorage().getDialogs(folderId, offset == 0 ? 0 : nextDialogsCacheOffset.get(folderId, 0), count, folderId == 0 && offset == 0);
            }
            org.colgram.core.ColgramBotSync.syncBotDialogs(ApplicationLoader.applicationContext, currentAccount);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            return;
        }"""
        patch_file(
            messages_controller,
            bot_dialogs_target,
            bot_dialogs_inject,
            "MessagesController Bot Dialogs Cache Load"
        )

        bot_error_target = """                    if (onEmptyCallback != null && dialogsRes.dialogs.isEmpty()) {
                        AndroidUtilities.runOnUIThread(onEmptyCallback);
                    }
                }
            });"""
        bot_error_replacement = """                    if (onEmptyCallback != null && dialogsRes.dialogs.isEmpty()) {
                        AndroidUtilities.runOnUIThread(onEmptyCallback);
                    }
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingDialogs.put(folderId, false);
                        dialogsEndReached.put(folderId, true);
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                    });
                }
            });"""
        patch_file(
            messages_controller,
            bot_error_target,
            bot_error_replacement,
            "MessagesController Handle Dialogs Load Error"
        )

        def mc_qr_replacer(content):
            if "ColgramQRLoginBottomSheet.onLoginTokenUpdate" in content:
                return content
            target = 'FileLog.d("process update " + baseUpdate.getClass().getSimpleName());\\n            }'
            inject = """FileLog.d("process update " + baseUpdate.getClass().getSimpleName());
            }
            if (baseUpdate instanceof TL_update.TL_updateLoginToken) {
                org.telegram.ui.ColgramQRLoginBottomSheet.onLoginTokenUpdate(currentAccount);
                continue;
            }"""
            if target in content:
                return content.replace(target, inject, 1)
            return content

        patch_file(messages_controller, mc_qr_replacer, "ColgramQRLoginBottomSheet.onLoginTokenUpdate", "MessagesController QR Login Token Hook")

    # 28. ChatActivity.java -> Fallback User on Opening Bot Chat / Missing User Cache
    chat_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java")
    if os.path.exists(chat_activity):
        user_fallback_target = """                if (currentUser != null) {
                    getMessagesController().putUser(currentUser, true);
                } else {
                    return false;
                }"""
        user_fallback_replacement = """                if (currentUser != null) {
                    getMessagesController().putUser(currentUser, true);
                } else {
                    TLRPC.TL_user fallbackUser = new TLRPC.TL_user();
                    fallbackUser.id = userId;
                    fallbackUser.first_name = "User " + userId;
                    fallbackUser.phone = "";
                    currentUser = fallbackUser;
                    getMessagesController().putUser(currentUser, true);
                }"""
        patch_file(
            chat_activity,
            user_fallback_target,
            user_fallback_replacement,
            "ChatActivity Fallback User Object On Open"
        )

    # 29. ChatActivity.java -> Retain Deleted Messages in UI (Anti-Delete AyuGram Style)
    if os.path.exists(chat_activity):
        anti_delete_ui_target = "private void processDeletedMessages(ArrayList<Integer> markAsDeletedMessages, long channelId, boolean sent, boolean thanos) {"
        anti_delete_ui_replacement = """private void processDeletedMessages(ArrayList<Integer> markAsDeletedMessages, long channelId, boolean sent, boolean thanos) {
        if (org.colgram.core.ColgramConfig.isAntiDeleteEnabled() && markAsDeletedMessages != null) {
            for (int msg_id : markAsDeletedMessages) {
                MessageObject msg = (messagesDict != null && messagesDict.length > 0 && messagesDict[0] != null) ? messagesDict[0].get(msg_id) : null;
                if (msg != null) {
                    msg.deleted = true;
                    org.colgram.core.ColgramHookHandler.hookShouldPreventDelete(dialog_id, msg_id);
                }
            }
            if (chatAdapter != null) {
                chatAdapter.notifyDataSetChanged(false);
            }
            return;
        }"""
        patch_file(
            chat_activity,
            anti_delete_ui_target,
            anti_delete_ui_replacement,
            "ChatActivity Anti-Delete Message Retention"
        )

    # 30. ChatMessageCell.java -> Prepend 🗑 to time string for deleted messages
    if os.path.exists(chat_cell):
        cell_time_target = """        } else {
            currentTimeString = timeString;
        }"""
        cell_time_replacement = """        } else {
            currentTimeString = timeString;
        }
        if (currentMessageObject != null && (currentMessageObject.deleted || org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(currentMessageObject.getDialogId(), currentMessageObject.getId()))) {
            currentTimeString = TextUtils.concat("🗑 ", currentTimeString);
        }"""
        patch_file(
            chat_cell,
            cell_time_target,
            cell_time_replacement,
            "ChatMessageCell Prepend Deleted Icon"
        )

    # 31. ChatActivity.java -> Edit History Context Menu Option & Action
    if os.path.exists(chat_activity):
        menu_edit_target = """fillMessageMenu(
        MessageObject primaryMessage,

        ArrayList<Integer> icons,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options
    ) {"""
        menu_edit_replacement = """fillMessageMenu(
        MessageObject primaryMessage,

        ArrayList<Integer> icons,
        ArrayList<CharSequence> items,
        ArrayList<Integer> options
    ) {
        if (selectedObject != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
            boolean isRuLang = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            items.add(isRuLang ? "История изменений" : "Edit History");
            options.add(9988);
            icons.add(R.drawable.msg_edit);
        }"""
        patch_file(
            chat_activity,
            menu_edit_target,
            menu_edit_replacement,
            "ChatActivity Edit History Menu Option"
        )

        process_option_target = """private void processSelectedOption(int option) {
        if (selectedObject == null || getParentActivity() == null) {
            return;
        }"""
        process_option_replacement = """private void processSelectedOption(int option) {
        if (selectedObject == null || getParentActivity() == null) {
            return;
        }
        if (option == 9988) {
            // Colgram: open the Telegram-native edit-history BottomSheet.
            // This lives in TMessagesProj (not colgram-core) because colgram-core
            // compiles before TMessagesProj and cannot see org.telegram.ui.* classes.
            org.telegram.ui.ColgramEditHistorySheet.show(getParentActivity(), selectedObject.getDialogId(), selectedObject.getId());
            return;
        }
        if (option == 9987) {
            // Colgram: open Telegram's own per-chat wallpaper picker for this dialog.
            try {
                presentFragment(new org.telegram.ui.WallpapersListActivity(org.telegram.ui.WallpapersListActivity.TYPE_COLOR, selectedObject.getDialogId()));
            } catch (Throwable ignoreWallpaper) {}
            return;
        }"""
        patch_file(
            chat_activity,
            process_option_target,
            process_option_replacement,
            "ChatActivity Handle Edit History Option"
        )

    # 31.1. ChatActivity.java -> "Chat Wallpaper" menu entry in the message context menu
    if os.path.exists(chat_activity):
        wallpaper_menu_target = """        if (selectedObject != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
            boolean isRuLang = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            items.add(isRuLang ? "История изменений" : "Edit History");
            options.add(9988);
            icons.add(R.drawable.msg_edit);
        }"""
        wallpaper_menu_replacement = """        if (selectedObject != null && org.colgram.core.ColgramConfig.isEditHistoryEnabled()) {
            boolean isRuLang = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            items.add(isRuLang ? "История изменений" : "Edit History");
            options.add(9988);
            icons.add(R.drawable.msg_edit);
            if (org.colgram.core.ColgramConfig.isChatWallpaperEnabled()) {
                items.add(isRuLang ? "Обои чата" : "Chat Wallpaper");
                options.add(9987);
                icons.add(R.drawable.msg_colors);
            }
        }"""
        patch_file(
            chat_activity,
            wallpaper_menu_target,
            wallpaper_menu_replacement,
            "ChatActivity Chat Wallpaper Menu Option"
        )

    # 32. SessionCell.java & SessionBottomSheet.java -> Spoof Active Session Device Display & ConnectionsManager
    session_cell = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "SessionCell.java")
    if os.path.exists(session_cell):
        patch_file(
            session_cell,
            "final TLRPC.TL_authorization session = (TLRPC.TL_authorization) object;",
            """final TLRPC.TL_authorization session = (TLRPC.TL_authorization) object;
            if ((session.flags & 1) != 0 && org.colgram.core.ColgramConfig.isCloakEnabled()) {
                session.device_model = org.colgram.core.ColgramConfig.getSpoofDeviceModel();
                session.system_version = org.colgram.core.ColgramConfig.getSpoofSystemVersion();
            }""",
            "SessionCell Spoof Active Device Display"
        )

    session_sheet = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SessionBottomSheet.java")
    if os.path.exists(session_sheet):
        patch_file(
            session_sheet,
            "timeView.setText(timeText);",
            """timeView.setText(timeText);
        if ((session.flags & 1) != 0 && org.colgram.core.ColgramConfig.isCloakEnabled()) {
            session.device_model = org.colgram.core.ColgramConfig.getSpoofDeviceModel();
            session.system_version = org.colgram.core.ColgramConfig.getSpoofSystemVersion();
        }""",
            "SessionBottomSheet Spoof Active Device Display"
        )

    if os.path.exists(conn_manager):
        patch_file(
            conn_manager,
            "deviceModel = Build.MANUFACTURER + Build.MODEL;",
            "deviceModel = org.colgram.core.ColgramConfig.getSpoofDeviceModel();",
            "ConnectionsManager Spoof deviceModel Directly"
        )
        patch_file(
            conn_manager,
            'systemVersion = "SDK " + Build.VERSION.SDK_INT;',
            "systemVersion = org.colgram.core.ColgramConfig.getSpoofSystemVersion();",
            "ConnectionsManager Spoof systemVersion Directly"
        )

    # 33. UserConfig.java -> Safe Account Bounds & Safe Defaults
    if os.path.exists(user_config):
        user_max_target = "public final static int MAX_ACCOUNT_DEFAULT_COUNT = 3;"
        user_max_replacement = "public final static int MAX_ACCOUNT_DEFAULT_COUNT = 4;"
        patch_file(
            user_config,
            user_max_target,
            user_max_replacement,
            "UserConfig Set MAX_ACCOUNT_DEFAULT_COUNT to 4"
        )
        patch_file(
            user_config,
            "public boolean syncContacts = true;",
            "public boolean syncContacts = false; // Colgram: anonymous by default",
            "UserConfig Default syncContacts to false"
        )
        patch_file(
            user_config,
            "public boolean suggestContacts = true;",
            "public boolean suggestContacts = false; // Colgram: anonymous by default",
            "UserConfig Default suggestContacts to false"
        )
        patch_file(
            user_config,
            'syncContacts = preferences.getBoolean("syncContacts", true);',
            'syncContacts = preferences.getBoolean("syncContacts", false);',
            "UserConfig Load syncContacts default false"
        )
        patch_file(
            user_config,
            'suggestContacts = preferences.getBoolean("suggestContacts", true);',
            'suggestContacts = preferences.getBoolean("suggestContacts", false);',
            "UserConfig Load suggestContacts default false"
        )
        patch_file(
            user_config,
            'return currentUser != null && currentUser.phone != null ? currentUser.phone : "";',
            'if (currentUser != null && currentUser.phone == null) currentUser.phone = "";\n            return currentUser != null && currentUser.phone != null ? currentUser.phone : "";',
            "UserConfig Safe ClientPhone"
        )
        patch_file(
            user_config,
            "public boolean isPremium() {",
            "public boolean isPremium() {\n        if (true) return true;",
            "UserConfig Unlock Client-Side Premium"
        )




    # 34. BuildVars.java -> Official Telegram Android credentials & disable SafetyNet check
    build_vars_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "BuildVars.java")
    if os.path.exists(build_vars_file):
        def build_vars_injector(content):
            import re
            content = re.sub(r'public static int APP_ID = \d+;[^\n]*', 'public static int APP_ID = 6; // Official Telegram for Android', content)
            content = re.sub(r'public static String APP_HASH = "[^"]+";', 'public static String APP_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e";', content)
            content = re.sub(r'public static String SAFETYNET_KEY = "[^"]*";', 'public static String SAFETYNET_KEY = "";', content)
            return content
        patch_file(build_vars_file, build_vars_injector, "APP_ID = 6", "BuildVars Set APP_ID & APP_HASH to Official Android & Clear SafetyNet")


    # 35. ProxyListActivity.java -> Silent 1-Tap Proxy Toggle (Never ask for input on empty list)
    proxy_list_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ProxyListActivity.java")
    if os.path.exists(proxy_list_file):
        patch_file(
            proxy_list_file,
            "if (SharedConfig.currentProxy == null) {\n                    if (!proxyList.isEmpty()) {",
            """if (SharedConfig.currentProxy == null) {
                    if (proxyList.isEmpty()) {
                        org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
                    }
                    if (!proxyList.isEmpty()) {""",
            "ProxyListActivity Silent 1-Tap Proxy Toggle"
        )


    # 36. Browser.java -> Route links through in-app browser for proxy anonymity
    browser_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "browser", "Browser.java")
    if os.path.exists(browser_file):
        def browser_proxy_injector(c):
            target = "if (allowCustom && !(uri != null && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen())"
            if target not in c:
                return c
            inject = """if (org.colgram.core.ColgramConfig.isProxyBrowserEnabled()) {
                openInTelegramBrowser(context, uri.toString(), inCaseLoading);
                return;
            }
            if (allowCustom && !(uri != null && MessagesController.getInstance(currentAccount).isWebBrowserOpenInApp(uri.toString()) || isInstantViewOpen())"""
            return c.replace(target, inject, 1)
        patch_file(browser_file, browser_proxy_injector, "org.colgram.core.ColgramConfig.isProxyBrowserEnabled()", "Browser Force In-App Browser for Privacy")

    # 37. LoginActivity.java -> Always visible Proxy Button
    login_activity_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LoginActivity.java")
    if os.path.exists(login_activity_file):
        patch_file(
            login_activity_file,
            "showProxyButton(false, animated);",
            "showProxyButton(true, animated);",
            "LoginActivity Keep Proxy Button Always Visible"
        )
        patch_file(
            login_activity_file,
            "proxyButtonView.setOnClickListener(v -> presentFragment(new ProxyListActivity()));",
            """proxyButtonView.setOnClickListener(v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
        });""",
            "LoginActivity Auto-Populate Proxy on Click"
        )

    # 38. IntroActivity.java -> Colgram Dark Black Intro Screen with Red Airplane Logo
    intro_file = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "IntroActivity.java")
    if os.path.exists(intro_file):
        patch_file(
            intro_file,
            'ssb.setSpan(new ImageSpan(logoDrawable), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);\n        titles[0] = ssb;',
            'titles[0] = "Colgram";',
            "IntroActivity Set Title to Colgram"
        )
        patch_file(
            intro_file,
            'LocaleController.getString(R.string.Page1Message),',
            '"Быстрый, приватный и свободный мессенджер",',
            "IntroActivity Set Subtitle to Colgram"
        )
        patch_file(
            intro_file,
            'frameLayout2 = new FrameLayout(context);\n        frameContainerView.addView(frameLayout2, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 78, 0, 0));\n\n        TextureView textureView = new TextureView(context);',
            '''frameLayout2 = new FrameLayout(context);
        frameContainerView.addView(frameLayout2, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 0, 78, 0, 0));

        android.widget.ImageView colgramLogo = new android.widget.ImageView(context);
        colgramLogo.setImageResource(R.drawable.colgram_plane_splash);
        colgramLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        frameLayout2.addView(colgramLogo, LayoutHelper.createFrame(160, 160, Gravity.CENTER));

        TextureView textureView = new TextureView(context);
        textureView.setVisibility(View.GONE);''',
            "IntroActivity Show Colgram Red Airplane"
        )
        patch_file(
            intro_file,
            'frameContainerView.addView(themeFrameLayout, LayoutHelper.createFrame(64, 64, Gravity.TOP | Gravity.RIGHT, 0, themeMargin, themeMargin, 0));',
            '''themeFrameLayout.setVisibility(View.GONE);
        frameContainerView.addView(themeFrameLayout, LayoutHelper.createFrame(64, 64, Gravity.TOP | Gravity.RIGHT, 0, themeMargin, themeMargin, 0));''',
            "IntroActivity Hide DayNight Switcher"
        )
        # Colgram branding on the intro screen.
        #
        # HISTORY — this block used to force fragmentView to 0xFF000000 (pure black) while
        # also hardcoding header/message text to white/grey. That works on the intro screen
        # alone, but LoginActivity REUSES this same container via setIntroView(), and the
        # phone-input form draws its title, subtitle and phone field with
        # Theme.key_windowBackgroundWhiteBlackText — a colour that assumes a LIGHT
        # background. Worse, IntroActivity.updateColors() rewrites the pager text back to
        # the theme colour on every theme event, so even the hardcoded white got stomped.
        # Net result: near-black text on a pure-black background — an unreadable login
        # screen, and the phone field invisible.
        #
        # The fix is to stop fighting the theme. We brand in Colgram red (accents) and let
        # the background and all body text follow the active theme, so every form on this
        # container stays legible in both light and dark mode.
        patch_file(
            intro_file,
            'startMessagingButtonBackground.setColors(new int[]{getThemedColor(Theme.key_featuredStickers_addButton), getThemedColor(Theme.key_featuredStickers_addButton2)});',
            'startMessagingButtonBackground.setColors(new int[]{0xFFD32F2F, 0xFF8B0000});',
            "IntroActivity Red Gradient Button"
        )
        patch_file(
            intro_file,
            'switchLanguageTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));',
            'switchLanguageTextView.setTextColor(0xFFEF5350);',
            "IntroActivity Red Switch Language Text"
        )
        # Keep the themed background. The old patch replaced this line with 0xFF000000;
        # that is the root cause of the black-on-black login form, so we explicitly assert
        # the themed form is what is present and do NOT mutate it.
        intro_bg_ok = False
        if os.path.exists(intro_file):
            with open(intro_file, "r", encoding="utf-8", errors="ignore") as f:
                _intro = f.read()
            intro_bg_ok = "fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));" in _intro
        if intro_bg_ok:
            print(" [=] IntroActivity background stays theme-driven - login form stays legible")
        else:
            print(" [!] IntroActivity background was force-darkened; login text will be "
                  "unreadable. Re-run with a clean upstream checkout.")

        # updateColors() is the second half of the black-on-black bug: it re-asserts the
        # background and text colours on every theme event, so any one-shot fix to the
        # constructor gets overwritten the moment the theme changes. Neutralise the
        # hardcoded black here and let the themed values flow through.
        patch_file(
            intro_file,
            '''        fragmentView.setBackgroundColor(0xFF000000);
        switchLanguageTextView.setTextColor(0xFFEF5350);
        startMessagingButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));''',
            '''        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        switchLanguageTextView.setTextColor(0xFFEF5350);
        startMessagingButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));''',
            "IntroActivity updateColors Keeps Themed Background"
        )

        # Reset startPressed when the intro comes back to the foreground.
        #
        # THE "second press of Начать общение does nothing" BUG.
        #
        # `startPressed` is a plain instance field, set true on the first press and never
        # cleared anywhere. The IntroActivity instance stays alive in the fragment stack
        # after navigating to the login form, so on returning to it the field is still true
        # and the click handler hits its own guard: `if (startPressed) return;`. The button
        # silently does nothing until the whole process is restarted and a fresh instance
        # is built with startPressed = false.
        #
        # Clearing it in onResume is the right place: the intro is only ever re-shown when
        # the user comes back to it, which is exactly when the button must work again.
        patch_file(
            intro_file,
            '''    public void onResume() {
        super.onResume();
        if (justCreated) {''',
            '''    public void onResume() {
        super.onResume();
        // Colgram: re-arm the Start Messaging button. Without this, returning to the intro
        // leaves startPressed true and the button no-ops until the app is restarted.
        startPressed = false;
        if (justCreated) {''',
            "IntroActivity Re-arm Start Button on Resume"
        )


    # 41. DialogsActivity.java -> Bot Account Chat Initiator on Floating Button
    dialogs_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java")
    if os.path.exists(dialogs_activity):
        bot_write_contacts_target = """    private void openWriteContacts() {
        Bundle args = new Bundle();
        args.putBoolean("destroyAfterSelect", true);
        presentFragment(new ContactsActivity(args));
    }"""
        bot_write_contacts_replacement = """    private void openWriteContacts() {
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            org.colgram.core.ColgramBotSync.showStartChatDialog(getParentActivity(), currentAccount, DialogsActivity.this);
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("destroyAfterSelect", true);
        presentFragment(new ContactsActivity(args));
    }"""
        patch_file(
            dialogs_activity,
            bot_write_contacts_target,
            bot_write_contacts_replacement,
            "DialogsActivity Bot Account openWriteContacts Hook"
        )

    # 42. DialogsEmptyCell.java -> Custom Bot Account Empty State
    dialogs_empty_cell = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "DialogsEmptyCell.java")
    if os.path.exists(dialogs_empty_cell):
        empty_cell_target = """        if (icon != 0) {
            imageView.setVisibility(VISIBLE);"""
        empty_cell_replacement = """        if (org.telegram.messenger.UserConfig.getInstance(currentAccount).getCurrentUser() != null && org.telegram.messenger.UserConfig.getInstance(currentAccount).getCurrentUser().bot) {
            boolean isRuBot = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            titleView.setText(isRuBot ? "Аккаунт бота" : "Bot Account");
            help = isRuBot ? "Здесь будут отображаться сообщения от пользователей.\\n\\nНажмите на карандаш внизу или сюда, чтобы написать пользователю."
                           : "Messages from users will appear here.\\n\\nTap the compose button below or here to message a user.";
            setOnClickListener(v -> {
                org.colgram.core.ColgramBotSync.showStartChatDialog(org.telegram.messenger.AndroidUtilities.getActivity(), currentAccount);
            });
        }
        if (icon != 0) {
            imageView.setVisibility(VISIBLE);"""
        patch_file(
            dialogs_empty_cell,
            empty_cell_target,
            empty_cell_replacement,
            "DialogsEmptyCell Custom Bot Account Empty State"
        )

    # 43. BulletinFactory.java -> Suppress BOT_METHOD_INVALID error bulletins
    bulletin_factory = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Components", "BulletinFactory.java")
    if os.path.exists(bulletin_factory):
        patch_file(
            bulletin_factory,
            "if (!LaunchActivity.isActive) return new Bulletin.EmptyBulletin();",
            'if (!LaunchActivity.isActive || (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID"))) return new Bulletin.EmptyBulletin();',
            "BulletinFactory Suppress BOT_METHOD_INVALID in makeForError"
        )
        patch_file(
            bulletin_factory,
            """    public void showForError(TLRPC.TL_error error, boolean top) {
        if (!LaunchActivity.isActive) return;""",
            """    public void showForError(TLRPC.TL_error error, boolean top) {
        if (!LaunchActivity.isActive || (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID"))) return;""",
            "BulletinFactory Suppress BOT_METHOD_INVALID in showForError(TL_error)"
        )
        patch_file(
            bulletin_factory,
            """    public void showForError(String errorCode, boolean top) {
        if (!LaunchActivity.isActive) return;""",
            """    public void showForError(String errorCode, boolean top) {
        if (!LaunchActivity.isActive || (errorCode != null && errorCode.contains("BOT_METHOD_INVALID"))) return;""",
            "BulletinFactory Suppress BOT_METHOD_INVALID in showForError(String)"
        )
        patch_file(
            bulletin_factory,
            """    public static void showError(TLRPC.TL_error error) {
        if (!LaunchActivity.isActive) return;
        if (error != null && error.code == 406) return;""",
            """    public static void showError(TLRPC.TL_error error) {
        if (!LaunchActivity.isActive) return;
        if (error != null && (error.code == 406 || (error.text != null && error.text.contains("BOT_METHOD_INVALID")))) return;""",
            "BulletinFactory Suppress BOT_METHOD_INVALID in showError"
        )

    # 44. AlertsCreator.java -> Suppress BOT_METHOD_INVALID in processError
    alerts_creator = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Components", "AlertsCreator.java")
    if os.path.exists(alerts_creator):
        patch_file(
            alerts_creator,
            "if (error == null || error.code == 406 || error.text == null) {",
            'if (error == null || error.code == 406 || error.text == null || error.text.contains("BOT_METHOD_INVALID")) {',
            "AlertsCreator Suppress BOT_METHOD_INVALID in processError"
        )

    # 45. DialogsActivity.java -> Start Bot Updates Poller in onResume
    if os.path.exists(dialogs_activity):
        bot_resume_target = "public void onResume() {\n        super.onResume();"
        bot_resume_inject = """public void onResume() {\n        super.onResume();
        if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
            org.colgram.core.ColgramBotSync.startBotUpdatesPoller(getParentActivity(), currentAccount);
        }"""
        patch_file(
            dialogs_activity,
            bot_resume_target,
            bot_resume_inject,
            "DialogsActivity Start Bot Updates Poller in onResume"
        )

    # 46. ChangeNameActivity.java -> Bot Profile Name Update Hook
    change_name = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChangeNameActivity.java")
    if os.path.exists(change_name):
        cname_target = """        if (currentUser.first_name != null && currentUser.first_name.equals(newFirst) && currentUser.last_name != null && currentUser.last_name.equals(newLast)) {
            return;
        }"""
        cname_inject = """        if (currentUser.first_name != null && currentUser.first_name.equals(newFirst) && currentUser.last_name != null && currentUser.last_name.equals(newLast)) {
            return;
        }
        if (currentUser.bot) {
            org.colgram.core.ColgramBotSync.updateBotName(getParentActivity(), currentAccount, newFirst);
            finishFragment();
            return;
        }"""
        patch_file(change_name, cname_target, cname_inject, "ChangeNameActivity Bot Name Update Hook")

    # 47. ChangeBioActivity.java -> Bot Profile Bio/Description Update Hook
    change_bio = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChangeBioActivity.java")
    if os.path.exists(change_bio):
        cbio_target = """        final String newName = firstNameField.getText().toString().replace("\\n", "");
        if (currentName.equals(newName)) {
            finishFragment();
            return;
        }"""
        cbio_inject = """        final String newName = firstNameField.getText().toString().replace("\\n", "");
        if (currentName.equals(newName)) {
            finishFragment();
            return;
        }
        final TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (currentUser != null && currentUser.bot) {
            org.colgram.core.ColgramBotSync.updateBotDescription(getParentActivity(), currentAccount, newName, () -> {
                userFull.about = newName;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, currentUser.id, userFull);
                finishFragment();
            });
            return;
        }"""
        patch_file(change_bio, cbio_target, cbio_inject, "ChangeBioActivity Bot Description Update Hook")

    # 48. ChangeUsernameActivity.java -> Bot Username Notice Hook
    change_user = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChangeUsernameActivity.java")
    if os.path.exists(change_user):
        cuser_target = "final TL_account.updateUsername req = new TL_account.updateUsername();"
        cuser_inject = """TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (currentUser != null && currentUser.bot) {
            boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
            android.widget.Toast.makeText(getParentActivity(), isRu ? "Юзернейм бота можно изменить только через @BotFather" : "Bot username can only be changed via @BotFather", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final TL_account.updateUsername req = new TL_account.updateUsername();"""
        patch_file(change_user, cuser_target, cuser_inject, "ChangeUsernameActivity Bot Username Notice Hook")

    # 49. qr_logo.svg -> Black Color for Colgram Airplane in QR Code
    qr_svg = os.path.join(repo_path, "TMessagesProj", "src", "main", "res", "raw", "qr_logo.svg")
    if os.path.exists(qr_svg):
        patch_file(qr_svg, 'fill="#50A7EA"', 'fill="#000000"', "QR Code Black Logo SVG")

    # 51. MessagesController.java -> Auto-hide the phone number on first login
    #
    # Telegram's default is "phone visible to everybody". When an account is signed in
    # with a phone number, its number is then enumerable by anyone who has it. Colgram
    # forces the privacy setting to "Nobody" once, at first sync, for accounts that
    # actually have a phone number attached.
    #
    # The request must be issued from Telegrams own code: colgram-core compiles before
    # TMessagesProj and therefore cannot reference TLRPC or ConnectionsManager. The core
    # only supplies the one-shot flag.
    msgs_ctrl = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java")
    if os.path.exists(msgs_ctrl):
        phone_target = "                            getContactsController().setPrivacyRules(update.rules, ContactsController.PRIVACY_RULES_TYPE_PHONE);"
        phone_inject = """                            getContactsController().setPrivacyRules(update.rules, ContactsController.PRIVACY_RULES_TYPE_PHONE);
                            if (org.colgram.core.ColgramHookHandler.shouldAutoHidePhoneNumber(currentAccount)) {
                                try {
                                    // TL_account is already imported directly in this file
                                    // (org.telegram.tgnet.tl.TL_account), so reference it
                                    // unqualified - TLRPC.TL_account_* does not exist.
                                    TL_account.setPrivacy colgramPhoneReq = new TL_account.setPrivacy();
                                    colgramPhoneReq.key = new TLRPC.TL_inputPrivacyKeyPhoneNumber();
                                    // "Nobody" is represented by DisallowAll; there is no
                                    // TL_inputPrivacyValueAllowNobody class.
                                    colgramPhoneReq.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
                                    getConnectionsManager().sendRequest(colgramPhoneReq, (response, error) -> {
                                        if (error == null && response instanceof TL_account.privacyRules) {
                                            getContactsController().setPrivacyRules(((TL_account.privacyRules) response).rules, ContactsController.PRIVACY_RULES_TYPE_PHONE);
                                            org.colgram.core.ColgramHookHandler.markPhoneNumberHidden(currentAccount);
                                        }
                                    });
                                } catch (Throwable ignorePhone) {}
                            }"""
        patch_file(msgs_ctrl, phone_target, phone_inject, "MessagesController Auto-Hide Phone Number")

    # 52. BotWebViewSheet.java -> Pin mini-app to a floating window
    #
    # Telegram already ships a complete PiP framework (org.telegram.messenger.pip:
    # PipActivityController / PipSource), used for video. PipSource is generic over a
    # plain View, so a mini-app's WebView can be registered as a PiP source with no new
    # infrastructure. This adds a menu button that pins the current mini-app as a
    # floating window; several can coexist because the controller keeps one source per
    # tagPrefix and reuses the same activity.
    ids_xml = os.path.join(repo_path, "TMessagesProj", "src", "main", "res", "values", "ids.xml")
    if os.path.exists(ids_xml):
        patch_file(
            ids_xml,
            '    <item name="menu_collapse_bot" type="id"/>',
            '    <item name="menu_collapse_bot" type="id"/>\n    <item name="colgram_menu_pin_miniapp" type="id"/>',
            "ids.xml Pin MiniApp Menu Id"
        )

    bot_sheet = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "bots", "BotWebViewSheet.java")
    if os.path.exists(bot_sheet):
        pin_menu_target = "        optionsItem = menu.addItem(0, optionsIcon = new BotFullscreenButtons.OptionsIcon(getContext()));"
        pin_menu_inject = """        colgramPinItem = menu.addItem(R.id.colgram_menu_pin_miniapp, R.drawable.menu_video_pip);
        optionsItem = menu.addItem(0, optionsIcon = new BotFullscreenButtons.OptionsIcon(getContext()));"""
        patch_file(bot_sheet, pin_menu_target, pin_menu_inject, "BotWebViewSheet Pin MiniApp Menu Item")

        pin_click_target = """                } else if (id == R.id.menu_collapse_bot) {
                    forceExpnaded = true;
                    dismiss(true, null);
                }"""
        # NOTE: the PiP call lives HERE, in patched Telegram code, not in colgram-core.
        # colgram-core compiles before TMessagesProj, so it cannot reference
        # org.telegram.messenger.pip.* — that would be a module-boundary violation.
        # The core only supplies the enable flag (see ColgramConfig.isMiniAppPipEnabled).
        pin_click_inject = """                } else if (id == R.id.menu_collapse_bot) {
                    forceExpnaded = true;
                    dismiss(true, null);
                } else if (id == R.id.colgram_menu_pin_miniapp) {
                    colgramPinMiniAppToFloatingWindow();
                }"""
        patch_file(bot_sheet, pin_click_target, pin_click_inject, "BotWebViewSheet Pin MiniApp Click Handler")

        # Field declaration for the menu item we added above.
        field_target = "    private ActionBarMenuItem optionsItem;"
        field_inject = """    private ActionBarMenuItem optionsItem;
    private ActionBarMenuItem colgramPinItem;"""
        patch_file(bot_sheet, field_target, field_inject, "BotWebViewSheet Pin Menu Field")

        # The implementation. Registers the mini-app WebView with Telegram's own PiP
        # controller, which is what makes it float above other apps like a desktop window.
        #
        # Follows the exact pattern used by PipVideoOverlay.setPhotoViewer():
        # check permissions -> build a PipSource against the parent activity -> the
        # controller takes over. Sources are keyed by tagPrefix, so each mini-app gets
        # its own window and they do not replace one another.
        impl_target = "    public void setFullscreen(boolean fullscreen, boolean animated) {"
        impl_inject = """    /**
     * Colgram: pin this mini-app into a floating window (Android PiP).
     *
     * Reuses Telegram's existing PiP pipeline rather than building a new one.
     */
    private void colgramPinMiniAppToFloatingWindow() {
        if (!org.colgram.core.ColgramConfig.isMiniAppPipEnabled()) {
            return;
        }
        // BotWebViewSheet extends Dialog, not BaseFragment - there is no
        // getParentActivity(). Resolve the activity from the context instead.
        final android.app.Activity activity = AndroidUtilities.findActivity(getContext());
        if (activity == null || webViewContainer == null) {
            return;
        }
        try {
            if (org.telegram.messenger.pip.utils.PipUtils.checkPermissions(activity)
                    != org.telegram.messenger.pip.utils.PipPermissions.PIP_GRANTED_PIP) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                        android.widget.Toast.makeText(activity,
                                "Разрешите картинку в картинке в настройках системы",
                                android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            if (colgramPipSource != null) {
                colgramPipSource.destroy();
                colgramPipSource = null;
            }
            final android.view.View content = webViewContainer;
            colgramPipSource = new org.telegram.messenger.pip.PipSource.Builder(activity, colgramPipDelegate)
                    .setTagPrefix("colgram-miniapp-" + botId)
                    .setPriority(1)
                    .setContentView(content)
                    .setContentRatio(Math.max(1, content.getWidth()), Math.max(1, content.getHeight()))
                    .build();
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
        }
    }

    private org.telegram.messenger.pip.PipSource colgramPipSource;

    /**
     * IPipSourceDelegate for the pinned mini-app.
     *
     * The interface has mandatory members (pipCreatePrimaryWindowViewBitmap and friends),
     * so every one of them has to exist - they are not default methods. The WebView is
     * re-parented into the PiP view, and a placeholder fills the original slot.
     */
    private final org.telegram.messenger.pip.source.IPipSourceDelegate colgramPipDelegate =
            new org.telegram.messenger.pip.source.IPipSourceDelegate() {
                @Override
                public android.graphics.Bitmap pipCreatePrimaryWindowViewBitmap() {
                    return null;
                }

                @Override
                public android.view.View pipCreatePictureInPictureView() {
                    return webViewContainer;
                }

                @Override
                public void pipHidePrimaryWindowView(Runnable firstFrameCallback) {
                    if (webViewContainer != null) {
                        webViewContainer.setVisibility(android.view.View.INVISIBLE);
                    }
                    if (firstFrameCallback != null) {
                        firstFrameCallback.run();
                    }
                }

                @Override
                public android.graphics.Bitmap pipCreatePictureInPictureViewBitmap() {
                    return null;
                }

                @Override
                public void pipShowPrimaryWindowView(Runnable firstFrameCallback) {
                    if (webViewContainer != null) {
                        webViewContainer.setVisibility(android.view.View.VISIBLE);
                    }
                    if (firstFrameCallback != null) {
                        firstFrameCallback.run();
                    }
                }
            };

    public void setFullscreen(boolean fullscreen, boolean animated) {"""
        patch_file(bot_sheet, impl_target, impl_inject, "BotWebViewSheet Pin MiniApp Implementation")

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
    has_so_files = os.path.exists(jni_libs_dir) and any(f.endswith('.so') for _, _, files in os.walk(jni_libs_dir) for f in files)
    if has_so_files:
        print(" [+] Precompiled native .so libraries already present in jniLibs, skipping download.")
        return

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

    # Sync file-by-file rather than wiping the destination tree. A bulk rmtree of the
    # module both trips delete-guard hooks and destroys anything the build generated
    # into the tree that we would immediately have to rebuild.
    if os.path.isdir(core_source_dir):
        for root, _dirs, files in os.walk(core_source_dir):
            rel = os.path.relpath(root, core_source_dir)
            dest_dir = target_core_dir if rel == "." else os.path.join(target_core_dir, rel)
            os.makedirs(dest_dir, exist_ok=True)
            for fname in files:
                if fname.endswith(".pyc"):
                    continue
                src_file = os.path.join(root, fname)
                dst_file = os.path.join(dest_dir, fname)
                shutil.copyfile(src_file, dst_file)
    else:
        print(f" [!] FATAL: core source dir not found: {core_source_dir}")
        return

    print(f" [+] colgram-core successfully synced to {target_core_dir}")

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
        #
        # IDEMPOTENCY: this replace has no natural "already applied" guard, unlike
        # patch_file(). Running apply-patches.py more than once used to append the three
        # attributes again every time — 6 runs produced 18 duplicate attributes and an
        # AndroidManifest.xml that the manifest merger refuses to parse
        # ("duplicate attribute"). Guard on the marker we are about to insert, and also
        # collapse any duplicates a previous run already created.
        app_marker = 'android:name="org.telegram.messenger.ApplicationLoader"'
        branding_block = (
            '\n        android:icon="@mipmap/ic_launcher"'
            '\n        android:roundIcon="@mipmap/ic_launcher_round"'
            '\n        android:label="Colgram"'
        )
        if 'android:label="Colgram"' not in m_content:
            m_content = m_content.replace(
                app_marker,
                app_marker + branding_block,
                1
            )
        else:
            # Repair a manifest damaged by earlier runs: keep exactly one copy.
            dup = re.compile(
                r'(\s*android:icon="@mipmap/ic_launcher"\s*'
                r'android:roundIcon="@mipmap/ic_launcher_round"\s*'
                r'android:label="Colgram")+'
            )
            m_content = dup.sub(branding_block, m_content, count=1)
            print(" [=] Application branding already present (deduplicated)")

        # Strip phone, contacts, location, notifications, and account permissions from AndroidManifest.xml for full user privacy
        for perm in [
            "READ_PHONE_STATE", "READ_PHONE_NUMBERS",
            "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS",
            "MANAGE_ACCOUNTS", "READ_PROFILE", "MANAGE_OWN_CALLS",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
            "POST_NOTIFICATIONS"
        ]:
            m_content = m_content.replace(
                f'<uses-permission android:name="android.permission.{perm}" />',
                f'<!-- stripped {perm} for Colgram privacy -->'
            )
            m_content = m_content.replace(
                f'<uses-permission android:name="android.permission.{perm}"/>',
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

    # 1. Copy pre-generated custom Colgram avatar icons across all mipmap densities
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

    # 1b. Overwrite Telegram's ALTERNATE launcher icons (icon_2..icon_6).
    #
    # Telegram ships six selectable app icons and lets the user pick one in settings.
    # The branding pass only recoloured ic_launcher and icon_2, so icons 3-6 stayed the
    # stock Telegram art — that is the "old icon shows up in some places" bug: the
    # launcher already had the right icon, but the in-app icon picker, the recents
    # thumbnail and any previously-selected alternate icon still rendered the old one.
    #
    # Generating all of them from the branded master means no stock art survives.
    master_icon = os.path.join(root_dir, "assets", "app_icon.png")
    if os.path.exists(master_icon):
        try:
            from PIL import Image
            have_pil = True
        except Exception:
            have_pil = False

        if have_pil:
            print("[*] Regenerating alternate launcher icons (icon_2..icon_6)...")
            # Standard mipmap sizes in px for a 48dp launcher icon.
            density_sizes = {
                "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
                "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
            }
            master = Image.open(master_icon).convert("RGBA")
            for res_dir in target_dirs:
                if not os.path.exists(res_dir):
                    continue
                for density_name, px in density_sizes.items():
                    dest_density = os.path.join(res_dir, density_name)
                    if not os.path.isdir(dest_density):
                        continue
                    square = master.resize((px, px), Image.LANCZOS)
                    # Round variant: same art, circular alpha mask.
                    mask = Image.new("L", (px, px), 0)
                    from PIL import ImageDraw
                    ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
                    round_icon = square.copy()
                    round_icon.putalpha(mask)

                    for idx in range(2, 7):
                        square.save(os.path.join(dest_density, f"icon_{idx}_launcher.png"))
                        round_icon.save(os.path.join(dest_density, f"icon_{idx}_launcher_round.png"))
            print(" [+] All alternate launcher icons now use Colgram branding.")
        else:
            print(" [!] Pillow not available - alternate launcher icons left as-is.")

    # 2. Adaptive icon background -> solid pure black #000000
    for res_dir in target_dirs:
        bg_sa_xml = os.path.join(res_dir, "drawable", "icon_background_sa.xml")
        if os.path.exists(bg_sa_xml):
            with open(bg_sa_xml, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android">\n    <solid android:color="#000000" />\n</shape>\n')
        bg_xml = os.path.join(res_dir, "drawable", "icon_background.xml")
        if os.path.exists(bg_xml):
            with open(bg_xml, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android">\n    <solid android:color="#000000" />\n</shape>\n')

    # 3. Splash plane & black launch screen background
    splash_src = os.path.join(root_dir, "assets", "colgram_plane_splash.png")
    if os.path.exists(splash_src):
        for res_dir in target_dirs:
            if not os.path.exists(res_dir):
                continue
            drawable_dir = os.path.join(res_dir, "drawable")
            os.makedirs(drawable_dir, exist_ok=True)
            shutil.copy2(splash_src, os.path.join(drawable_dir, "colgram_plane_splash.png"))

            splash_bg_xml = os.path.join(drawable_dir, "colgram_splash_bg.xml")
            with open(splash_bg_xml, "w", encoding="utf-8") as f:
                f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@android:color/black" />
    <item
        android:gravity="center"
        android:width="160dp"
        android:height="160dp"
        android:drawable="@drawable/colgram_plane_splash" />
</layer-list>
''')

            tg_splash = os.path.join(drawable_dir, "tg_splash_320.xml")
            with open(tg_splash, "w", encoding="utf-8") as f:
                f.write('''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@android:color/black" />
    <item
        android:gravity="center"
        android:width="160dp"
        android:height="160dp"
        android:drawable="@drawable/colgram_plane_splash" />
</layer-list>
''')

    # 4. Patch styles.xml (Theme.TMessages.Start & Android 12+ Splash to pitch black + red plane)
    res_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "res")
    values_styles = os.path.join(res_dir, "values", "styles.xml")
    if os.path.exists(values_styles):
        with open(values_styles, "r", encoding="utf-8") as f:
            v_content = f.read()
        v_content = v_content.replace(
            '<item name="android:colorBackground">@android:color/white</item>\n        <item name="android:windowBackground">@android:color/white</item>',
            '<item name="android:colorBackground">@android:color/black</item>\n        <item name="android:windowBackground">@drawable/colgram_splash_bg</item>'
        )
        with open(values_styles, "w", encoding="utf-8") as f:
            f.write(v_content)

    v31_styles = os.path.join(res_dir, "values-v31", "styles.xml")
    if os.path.exists(v31_styles):
        with open(v31_styles, "r", encoding="utf-8") as f:
            v31_content = f.read()
        v31_content = v31_content.replace(
            '<item name="android:colorBackground">@android:color/white</item>\n        <item name="android:windowBackground">@android:color/white</item>',
            '<item name="android:colorBackground">@android:color/black</item>\n        <item name="android:windowBackground">@drawable/colgram_splash_bg</item>'
        ).replace(
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/tg_splash_320</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">?android:windowBackground</item>',
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/colgram_plane_splash</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">@android:color/black</item>'
        )
        with open(v31_styles, "w", encoding="utf-8") as f:
            f.write(v31_content)

    night_styles = os.path.join(res_dir, "values-night", "styles.xml")
    if os.path.exists(night_styles):
        with open(night_styles, "r", encoding="utf-8") as f:
            n_content = f.read()
        n_content = n_content.replace(
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/tg_splash_320</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">#1f2732</item>',
            '<item name="android:windowSplashScreenAnimatedIcon">@drawable/colgram_plane_splash</item>\n        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>\n        <item name="android:windowSplashScreenBackground">@android:color/black</item>'
        )
        with open(night_styles, "w", encoding="utf-8") as f:
            f.write(n_content)

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
