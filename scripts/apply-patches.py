#!/usr/bin/env python3
"""
Colgram Patch & Injection Engine
Applies modular privacy hooks, extracts official prebuilt native binaries,
and injects colgram-core into Telegram source.
"""

import os
import re
import sys
import shutil
import zipfile
import urllib.request
import subprocess

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
        os.makedirs(jni_libs_dir, exist_ok=True)
        with zipfile.ZipFile(temp_apk, 'r') as zip_ref:
            for file_info in zip_ref.infolist():
                if file_info.filename.startswith("lib/"):
                    # Extract to jniLibs
                    rel_path = file_info.filename[len("lib/"):]
                    target_file = os.path.join(jni_libs_dir, rel_path)
                    os.makedirs(os.path.dirname(target_file), exist_ok=True)
                    with zip_ref.open(file_info) as src, open(target_file, 'wb') as dst:
                        shutil.copyfileobj(src, dst)

        print(" [+] Successfully extracted prebuilt .so libraries to jniLibs!")
        if os.path.exists(temp_apk):
            os.remove(temp_apk)

        # Disable externalNativeBuild in TMessagesProj/build.gradle so Gradle uses prebuilts
        tmessages_gradle = os.path.join(repo_path, "TMessagesProj", "build.gradle")
        if os.path.exists(tmessages_gradle):
            with open(tmessages_gradle, "r", encoding="utf-8") as f:
                gradle_content = f.read()
            # Comment out externalNativeBuild
            gradle_content = re.sub(r'(externalNativeBuild\s*\{)', r'/* \1', gradle_content)
            gradle_content = re.sub(r'(\}\s*//\s*externalNativeBuild)', r'\1 */', gradle_content)
            # Make sure jniLibs is configured
            if "sourceSets.main.jniLibs.srcDirs" not in gradle_content:
                gradle_content = gradle_content.replace(
                    "android {",
                    "android {\n    sourceSets.main.jniLibs.srcDirs = ['src/main/jniLibs']"
                )
            with open(tmessages_gradle, "w", encoding="utf-8") as f:
                f.write(gradle_content)
            print(" [+] Configured Gradle to use prebuilt native libraries (fast build)")

    except Exception as e:
        print(f" [!] Warning: Prebuilt binary extraction encountered error: {e}")
        print("     Will fallback to standard NDK build if available.")

def inject_core(repo_path, core_source_dir):
    print("[*] Injecting colgram-core module into project...")
    target_core_dir = os.path.join(repo_path, "colgram-core")
    if os.path.exists(target_core_dir):
        shutil.rmtree(target_core_dir)
    shutil.copytree(core_source_dir, target_core_dir)
    print(f" [+] colgram-core successfully copied to {target_core_dir}")

    # Add module to settings.gradle if not present
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

def apply_patches(repo_path, patches_dir):
    print(f"[*] Applying Colgram patches to: {repo_path}")
    if not os.path.exists(patches_dir):
        print(f"[!] Patches directory not found: {patches_dir}")
        return False

    patch_files = sorted([f for f in os.listdir(patches_dir) if f.endswith('.patch')])
    for patch in patch_files:
        patch_path = os.path.join(patches_dir, patch)
        print(f" -> Applying {patch}...")
        res = subprocess.run(
            ["git", "apply", "--ignore-whitespace", "--recount", patch_path],
            cwd=repo_path,
            capture_output=True,
            text=True
        )
        if res.returncode != 0:
            print(f" [!] Warning: Direct git apply failed for {patch}: {res.stderr.strip()}")
            print(f" [*] Attempting patch with 3-way fallback...")
            res3 = subprocess.run(
                ["git", "apply", "-3", patch_path],
                cwd=repo_path,
                capture_output=True,
                text=True
            )
            if res3.returncode != 0:
                print(f" [x] Error applying {patch}: {res3.stderr.strip()}")
                return False
        print(f" [+] Successfully applied {patch}")
    return True

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    patches_dir = os.path.join(root_dir, "patches")
    core_dir = os.path.join(root_dir, "colgram-core")

    target_repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root_dir, "Telegram")

    if not os.path.exists(target_repo):
        print(f"[!] Target Telegram repo not found at: {target_repo}")
        print("    Clone Telegram or pass path as argument: python apply-patches.py <path_to_repo>")
        sys.exit(1)

    inject_core(target_repo, core_dir)
    download_official_binaries(target_repo)
    if apply_patches(target_repo, patches_dir):
        print("\n[+] Colgram setup complete! Ready to build APK.")
    else:
        print("\n[x] Patching encountered errors.")
        sys.exit(1)

if __name__ == "__main__":
    main()
