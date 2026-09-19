#!/usr/bin/env python3
"""
Colgram Patch & Injection Engine
Applies modular privacy hooks and injects colgram-core into Telegram-FOSS.
"""

import os
import sys
import shutil
import subprocess

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

def inject_core(repo_path, core_source_dir):
    print(f"[*] Injecting colgram-core module into project...")
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

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    patches_dir = os.path.join(root_dir, "patches")
    core_dir = os.path.join(root_dir, "colgram-core")

    target_repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root_dir, "Telegram-FOSS")

    if not os.path.exists(target_repo):
        print(f"[!] Target Telegram repo not found at: {target_repo}")
        print("    Clone Telegram-FOSS or pass path as argument: python apply-patches.py <path_to_repo>")
        sys.exit(1)

    inject_core(target_repo, core_dir)
    if apply_patches(target_repo, patches_dir):
        print("\n[+] Colgram setup complete! Ready to build APK.")
    else:
        print("\n[x] Patching encountered errors.")
        sys.exit(1)

if __name__ == "__main__":
    main()
