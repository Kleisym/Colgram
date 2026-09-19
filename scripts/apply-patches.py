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

    pass

def download_official_binaries(repo_path):
    print("[*] Setting up precompiled official native libraries...")
    apk_url = "https://telegram.org/dl/android/apk"
    temp_apk = os.path.join(repo_path, "official_temp.apk")
    jni_libs_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "jniLibs")

    try:
        print(" -> Downloading official Telegram APK for native .so extraction...")
        req = urllib.request.Request(apk_url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as resp, open(temp_apk, 'wb') as out_file:
            shutil.copyfileobj(resp, out_file)

        print(" -> Extracting native libraries from APK...")
        # Clean destination to prevent duplicate resources
        if os.path.exists(jni_libs_dir):
            shutil.rmtree(jni_libs_dir)
        os.makedirs(jni_libs_dir, exist_ok=True)

        with zipfile.ZipFile(temp_apk, 'r') as zip_ref:
            for file_info in zip_ref.infolist():
                if file_info.filename.startswith("lib/"):
                    # Exclude third-party libs like liblanguage_id_l2c_jni.so that come from AAR dependencies
                    if "liblanguage_id" in file_info.filename or not file_info.filename.endswith(".so"):
                        continue
                    rel_path = file_info.filename[len("lib/"):]
                    target_file = os.path.join(jni_libs_dir, rel_path)
                    os.makedirs(os.path.dirname(target_file), exist_ok=True)
                    with zip_ref.open(file_info) as src, open(target_file, 'wb') as dst:
                        shutil.copyfileobj(src, dst)

        print(" [+] Successfully extracted prebuilt .so libraries to jniLibs!")
        if os.path.exists(temp_apk):
            os.remove(temp_apk)

        # Disable externalNativeBuild in TMessagesProj/build.gradle using clean brace counter
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

        # Configure packagingOptions in all app and library modules to pickFirst on .so files
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

    except Exception as e:
        print(f" [!] Warning: Prebuilt binary extraction encountered error: {e}")

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

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    core_dir = os.path.join(root_dir, "colgram-core")

    target_repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root_dir, "Telegram")

    if not os.path.exists(target_repo):
        print(f"[!] Target Telegram repo not found at: {target_repo}")
        sys.exit(1)

    clone_required_submodules(target_repo)
    inject_core(target_repo, core_dir)
    download_official_binaries(target_repo)
    inject_hooks(target_repo)
    print("\n[+] Colgram setup complete! Ready to build APK.")

if __name__ == "__main__":
    main()
