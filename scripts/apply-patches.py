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

    # 7. LoginActivity.java -> Suppress phone call permission requests
    login_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LoginActivity.java")
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

    # 9. LoginActivity.java -> Inject Bot Token Login button in PhoneView
    bot_login_btn = """addView(phoneOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));
            TextView botLoginBtn = new TextView(context);
            botLoginBtn.setText("🤖 Войти через токен бота");
            botLoginBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            botLoginBtn.setTypeface(AndroidUtilities.bold());
            botLoginBtn.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            botLoginBtn.setGravity(Gravity.CENTER);
            botLoginBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
            botLoginBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), Theme.getColor(Theme.key_featuredStickers_addButton) & 0x1affffff, Theme.getColor(Theme.key_featuredStickers_addButton) & 0x33ffffff));
            botLoginBtn.setOnClickListener(v -> {
                org.colgram.core.ColgramBotLogin.showBotLoginDialog(context, currentAccount, () -> {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
                    presentFragment(new DialogsActivity(null), true);
                });
            });
            addView(botLoginBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 16, 12, 16, 8));"""
    patch_file(
        login_activity,
        "addView(phoneOutlineView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 8, 16, 8));",
        bot_login_btn,
        "LoginActivity Bot Token Login Button"
    )

    # 10. UserConfig.java -> Unlock all account slots
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

    # 11. UserInfoActivity.java -> Bypass account limit check on Add Account
    user_info_activity = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "UserInfoActivity.java")
    patch_file(
        user_info_activity,
        "if (!UserConfig.hasPremiumOnAccounts()) {\n                freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);\n            }",
        "// Colgram: all account slots unlocked without premium",
        "UserInfoActivity Unlock Add Account"
    )

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

        # Strip phone/call permissions from AndroidManifest.xml for user privacy
        m_content = m_content.replace(
            '<uses-permission android:name="android.permission.READ_PHONE_STATE" />',
            '<!-- stripped READ_PHONE_STATE -->'
        )
        m_content = m_content.replace(
            '<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />',
            '<!-- stripped READ_PHONE_NUMBERS -->'
        )

        with open(main_manifest, "w", encoding="utf-8") as f:
            f.write(m_content)
        print(" [+] Injected icon and label into TMessagesProj AndroidManifest.xml")

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
