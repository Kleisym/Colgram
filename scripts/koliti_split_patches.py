#!/usr/bin/env python3
"""Dual-APK Koliti AI split patcher and asset/Gradle configurator."""

import os
import re
import shutil

_NOOP_COLGRAM_AI_ACTIVITY = """package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.ui.ActionBar.BaseFragment;

public class ColgramAiActivity extends BaseFragment {
    public static final int REQ_PICK_GGUF = 6401;
    public static final int REQ_PICK_MMPROJ = 6402;
    public static final int REQ_PICK_MEDIA_FROM_SHEET = 6403;
    public static final int REQ_VOICE_RECOGNIZE = 6404;
    public static final int REQ_PICK_VOICE_REF = 6405;
    public static ColgramAiSheet pendingSheetForMedia;
    public static ColgramAiSheet pendingSheetForVoice;

    @Override
    public View createView(Context context) {
        fragmentView = new FrameLayout(context);
        return fragmentView;
    }
}
"""

_NOOP_COLGRAM_AI_SHEET = """package org.telegram.ui;

import android.content.Context;
import android.net.Uri;
import android.view.View;

import org.telegram.ui.ActionBar.BaseFragment;

public final class ColgramAiSheet {
    private ColgramAiSheet() {}

    public static ColgramAiSheet show(BaseFragment parentFragment, ChatActivity chatActivity, ColgramAiOrbView orbView) {
        return null;
    }

    public static ColgramAiSheet showWithQuickPrompt(BaseFragment parentFragment, ChatActivity chatActivity, ColgramAiOrbView orbView, String autoPrompt) {
        return null;
    }

    public void attachExternalMediaUri(Context ctx, Uri uri) {}

    public void onVoiceRecognizedResult(String spokenText) {}
}
"""

_NOOP_COLGRAM_AI_ORB_VIEW = """package org.telegram.ui;

import android.content.Context;
import android.view.View;

public final class ColgramAiOrbView extends View {
    public enum State { IDLE, THINKING, VISION, SEARCHING, FLYING }

    public interface DragListener {
        void onDragDelta(float dxPx, float dyPx);
        void onDragEnded();
    }

    public ColgramAiOrbView(Context context) {
        super(context);
        setVisibility(GONE);
    }

    public void reloadCustomAvatar() {}
    public void applyDesignConfig() {}
    public void setOnOrbClickListener(Runnable listener) {}
    public void setOnOrbLongClickListener(Runnable listener) {}
    public void setDragListener(DragListener listener) {}
    public void setFlightVelocity(float vx, float vy) {}
    public void setState(State newState) {}
    public void setBaseTranslationY(float baseY) {}
    public void setHostPaused(boolean paused) {}
}
"""

_NOOP_COLGRAM_AI_CHAT_BRIDGE = """package org.telegram.ui;

public final class ColgramAiChatBridge {
    private ColgramAiChatBridge() {}
}
"""

_NOOP_COLGRAM_GLOBAL_ORB_CONTROLLER = """package org.telegram.ui;

import android.app.Activity;
import android.widget.FrameLayout;

public final class ColgramGlobalOrbController {
    private ColgramGlobalOrbController() {}

    public static void attach(LaunchActivity activity, FrameLayout host) {}
    public static ColgramGlobalOrbController getInstance() { return null; }
    public void detachIfHost(Activity target) {}
    public static void pushModalSuppression() {}
    public static void popModalSuppression() {}
    public ColgramAiOrbView getOrbView() { return null; }
    public void notifyChatInputMetrics(float inputBottomPx) {}
    public void setHostPaused(boolean paused) {}
    public void scheduleRelayout() {}
    public void applyDesignConfig() {}
}
"""

_NOOP_KOLITI_UI_STUBS = {
    "ColgramAiActivity.java": _NOOP_COLGRAM_AI_ACTIVITY,
    "ColgramAiSheet.java": _NOOP_COLGRAM_AI_SHEET,
    "ColgramAiOrbView.java": _NOOP_COLGRAM_AI_ORB_VIEW,
    "ColgramAiChatBridge.java": _NOOP_COLGRAM_AI_CHAT_BRIDGE,
    "ColgramGlobalOrbController.java": _NOOP_COLGRAM_GLOBAL_ORB_CONTROLLER,
}

_KOLITI_RAW_AUDIO_FILES = (
    "koliti_greet_en.ogg",
    "koliti_greet_ru.ogg",
    "koliti_song.ogg",
    "koliti_user_voice_ref.ogg",
)

_KOLITI_CORE_CLASS_FILES = (
    "ColgramAiAgentTools.java",
    "ColgramAiChatStore.java",
    "ColgramAiContextEngine.java",
    "ColgramAiEngine.java",
    "ColgramAiModelManager.java",
    "ColgramAiModels.java",
    "ColgramAiVisionProcessor.java",
    "ColgramAiWebSearch.java",
    "ColgramKolitiVoice.java",
    "ColgramNativeLlama.java",
    "ColgramVoiceTimbreDsp.java",
)

_GATE_PATCH_SPECS = [
    (
        "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java",
        "ApplicationLoader onTrimMemory Koliti gate",
        (
            "                ImageLoader.getInstance().clearMemory();\n"
            "                org.colgram.core.ColgramAiEngine.getInstance().unloadIfIdleInBackground();\n"
            "                org.colgram.core.ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(this);"
        ),
        (
            "                ImageLoader.getInstance().clearMemory();\n"
            "                if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
            "                    try {\n"
            '                        Class<?> engineCls = Class.forName("org.colgram.core.ColgramAiEngine");\n'
            '                        Object engine = engineCls.getMethod("getInstance").invoke(null);\n'
            "                        if (engine != null) {\n"
            '                            engineCls.getMethod("unloadIfIdleInBackground").invoke(engine);\n'
            "                        }\n"
            "                    } catch (Throwable ignored) {}\n"
            "                }\n"
            "                org.colgram.core.ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(this);"
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
        "LaunchActivity ColgramGlobalOrbController.attach Koliti gate",
        (
            "        actionBarLayout.setDelegate(this);\n"
            "        ColgramGlobalOrbController.attach(this, frameLayout);\n"
            "        Theme.loadWallpaper(true);"
        ),
        (
            "        actionBarLayout.setDelegate(this);\n"
            "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
            "            ColgramGlobalOrbController.attach(this, frameLayout);\n"
            "        }\n"
            "        Theme.loadWallpaper(true);"
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
        "LaunchActivity case ai Koliti gate",
        (
            '                case "ai":\n'
            '                    presentFragment(new ColgramAiActivity());\n'
            '                    return true;'
        ),
        (
            '                case "ai":\n'
            '                    if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n'
            '                        presentFragment(new ColgramAiActivity());\n'
            '                    } else {\n'
            '                        presentFragment(new ColgramSettingsActivity());\n'
            '                    }\n'
            '                    return true;'
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
        "LaunchActivity REQ_PICK_MEDIA_FROM_SHEET Koliti gate",
        "        if (requestCode == ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET) {",
        "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED && requestCode == ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET) {",
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
        "LaunchActivity REQ_VOICE_RECOGNIZE Koliti gate",
        "        if (requestCode == ColgramAiActivity.REQ_VOICE_RECOGNIZE) {",
        "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED && requestCode == ColgramAiActivity.REQ_VOICE_RECOGNIZE) {",
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        "ChatActivity orb creation Koliti gate",
        (
            "        colgramAiOrb = new ColgramAiOrbView(context);\n"
            "        boolean useLocalOrb = ColgramGlobalOrbController.getInstance() == null;\n"
            "        colgramAiOrb.setVisibility(useLocalOrb && org.colgram.core.ColgramAiConfig.isOrbEnabled(context) && !inPreviewMode && !isInsideContainer ? View.VISIBLE : View.GONE);\n"
            "        colgramAiOrb.setOnOrbClickListener(() -> ColgramAiSheet.show(ChatActivity.this, ChatActivity.this, colgramAiOrb));\n"
            "        contentView.addView(colgramAiOrb, LayoutHelper.createFrame(54, 54, Gravity.LEFT | Gravity.BOTTOM, 10, 0, 0, 0));"
        ),
        (
            "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
            "            colgramAiOrb = new ColgramAiOrbView(context);\n"
            "            boolean useLocalOrb = ColgramGlobalOrbController.getInstance() == null;\n"
            "            colgramAiOrb.setVisibility(useLocalOrb && org.colgram.core.ColgramAiConfig.isOrbEnabled(context) && !inPreviewMode && !isInsideContainer ? View.VISIBLE : View.GONE);\n"
            "            colgramAiOrb.setOnOrbClickListener(() -> ColgramAiSheet.show(ChatActivity.this, ChatActivity.this, colgramAiOrb));\n"
            "            contentView.addView(colgramAiOrb, LayoutHelper.createFrame(54, 54, Gravity.LEFT | Gravity.BOTTOM, 10, 0, 0, 0));\n"
            "        }"
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        "ChatActivity bottom padding 54dp Koliti gate",
        (
            "            float orbClearance = (!inPreviewMode && !isInsideContainer\n"
            "                    && org.colgram.core.ColgramAiConfig.isOrbEnabled(ApplicationLoader.applicationContext)) ? dp(54) : 0;"
        ),
        (
            "            float orbClearance = (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED\n"
            "                    && !inPreviewMode && !isInsideContainer\n"
            "                    && org.colgram.core.ColgramAiConfig.isOrbEnabled(ApplicationLoader.applicationContext)) ? dp(54) : 0;"
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        "ChatActivity REQ_PICK_MEDIA_FROM_SHEET Koliti gate",
        "            if (requestCode == ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET && data != null && data.getData() != null) {",
        "            if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED && requestCode == ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET && data != null && data.getData() != null) {",
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        "ChatActivity Saved Messages empty text Koliti gate",
        (
            "                } else if (currentUser != null && currentUser.self && currentUser.bot) {\n"
            '                    bigEmptyView.setStatusText("Локальные заметки Colgram\\nПишите заметки сюда или спросите Koliti");\n'
            "                }"
        ),
        (
            "                } else if (currentUser != null && currentUser.self && currentUser.bot) {\n"
            "                    bigEmptyView.setStatusText(org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED\n"
            '                            ? "Локальные заметки Colgram\\nПишите заметки сюда или спросите Koliti"\n'
            '                            : "Локальные заметки Colgram\\nПишите заметки сюда");\n'
            "                }"
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java",
        "DialogsActivity Koliti AI menu item gate",
        (
            "        org.telegram.ui.ActionBar.ActionBarMenuSubItem colgramSub;\n"
            '        io.add(R.drawable.msg_bot, "Koliti AI", () -> {\n'
            "            presentFragment(new ColgramAiActivity());\n"
            "        });\n"
            "        if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFFE11D48);"
        ),
        (
            "        org.telegram.ui.ActionBar.ActionBarMenuSubItem colgramSub;\n"
            "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
            '            io.add(R.drawable.msg_bot, "Koliti AI", () -> {\n'
            "                presentFragment(new ColgramAiActivity());\n"
            "            });\n"
            "            if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFFE11D48);\n"
            "        }"
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java",
        "SettingsActivity Row 108 and Row 109 subtitle Koliti gate",
        (
            '        items.add(SettingCell.Factory.of(108, 0xFFE11D48, 0xFF9F1239, R.drawable.msg_bot, "Koliti AI", "Встроено офлайн • Vision • Сон 0% нагрузки"));\n'
            '        items.add(SettingCell.Factory.of(109, 0xFF7C3AED, 0xFFE11D48, R.drawable.msg_theme, "Конструктор дизайна", "Цвета, расположение, размер и аватар Koliti"));'
        ),
        (
            "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
            '            items.add(SettingCell.Factory.of(108, 0xFFE11D48, 0xFF9F1239, R.drawable.msg_bot, "Koliti AI", "Встроено офлайн • Vision • Сон 0% нагрузки"));\n'
            "        }\n"
            '        items.add(SettingCell.Factory.of(109, 0xFF7C3AED, 0xFFE11D48, R.drawable.msg_theme, "Конструктор дизайна",\n'
            "                org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED\n"
            '                        ? "Цвета, расположение, размер и аватар Koliti"\n'
            '                        : "Цвета, расположение, стиль и интерфейс"));'
        ),
    ),
    (
        "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java",
        "SettingsActivity case 108 Koliti gate",
        (
            "            case 108:\n"
            "                presentSettingFragment(new ColgramAiActivity());\n"
            "                break;"
        ),
        (
            "            case 108:\n"
            "                if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
            "                    presentSettingFragment(new ColgramAiActivity());\n"
            "                }\n"
            "                break;"
        ),
    ),
]


def _write_build_flags(repo_path: str, koliti_enabled: bool) -> None:
    flags_path = os.path.join(
        repo_path,
        "colgram-core", "src", "main", "java", "org", "colgram", "core", "ColgramBuildFlags.java",
    )
    if not os.path.isdir(os.path.dirname(flags_path)):
        return
    flag_literal = "true" if koliti_enabled else "false"
    content = (
        "package org.colgram.core;\n\n"
        "public final class ColgramBuildFlags {\n"
        "    // Rewritten to false inside Telegram-Src copy when COLGRAM_KOLITI=0 (Main APK).\n"
        f"    public static final boolean KOLITI_ENABLED = {flag_literal};\n\n"
        "    private ColgramBuildFlags() {}\n"
        "}\n"
    )
    with open(flags_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(content)
    print(f" [+] Set Telegram-Src ColgramBuildFlags.KOLITI_ENABLED = {flag_literal}")


def _configure_standalone_gradle(repo_path: str) -> None:
    gradle_path = os.path.join(repo_path, "TMessagesProj_AppStandalone", "build.gradle")
    if not os.path.isfile(gradle_path):
        return
    with open(gradle_path, "r", encoding="utf-8", newline="") as fh:
        raw = fh.read()
    eol = "\r\n" if "\r\n" in raw else "\n"
    text = raw.replace("\r\n", "\n")

    # Ensure noCompress includes pack, gguf, and onnx
    text = re.sub(
        r'noCompress\s*\+=\s*(?:\[[^\]]*\]|"pack")',
        'noCompress += ["pack", "gguf", "onnx"]',
        text,
        count=1,
    )

    # Allow -PcolgramAbis=arm64-v8a,x86_64 to override afat ABI filters cleanly
    if "colgramAbis" not in text:
        def _replace_abi(match):
            ind = match.group(1)
            return (
                f"{ind}def customAbis = project.findProperty('colgramAbis')?.toString()?.split(',')?.collect {{ it.trim() }}?.findAll {{ it }}\n"
                f"{ind}if (customAbis && !customAbis.isEmpty()) {{\n"
                f"{ind}    abiFilters.clear()\n"
                f"{ind}    abiFilters.addAll(customAbis)\n"
                f"{ind}}} else {{\n"
                f'{ind}    abiFilters "armeabi-v7a", "arm64-v8a", "x86", "x86_64"\n'
                f"{ind}}}"
            )
        text = re.sub(
            r'^([ \t]*)abiFilters[ \t]+"armeabi-v7a",[ \t]*"arm64-v8a",[ \t]*"x86",[ \t]*"x86_64"[ \t]*$',
            _replace_abi,
            text,
            count=1,
            flags=re.MULTILINE,
        )

    updated = text.replace("\n", eol)
    if updated != raw:
        with open(gradle_path, "w", encoding="utf-8", newline="") as fh:
            fh.write(updated)
        print(" [+] Configured TMessagesProj_AppStandalone/build.gradle (noCompress + colgramAbis)")


def _configure_llama_gradle(repo_path: str, koliti_enabled: bool) -> None:
    settings_path = os.path.join(repo_path, "settings.gradle")
    if os.path.isfile(settings_path):
        with open(settings_path, "r", encoding="utf-8", newline="") as fh:
            raw = fh.read()
        eol = "\r\n" if "\r\n" in raw else "\n"
        text = raw.replace("\r\n", "\n")
        if not koliti_enabled:
            lines = [
                ln for ln in text.splitlines()
                if ":colgram-llama" not in ln and "file('colgram-llama')" not in ln and 'file("colgram-llama")' not in ln
            ]
            new_text = "\n".join(lines).rstrip("\n") + "\n"
        else:
            new_text = text
            if "':colgram-llama'" not in new_text and '":colgram-llama"' not in new_text:
                new_text = new_text.rstrip("\n") + "\ninclude ':colgram-llama'\n"
            if "project(':colgram-llama').projectDir" not in new_text:
                new_text = new_text.rstrip("\n") + "\nproject(':colgram-llama').projectDir = file('colgram-llama')\n"
        updated = new_text.replace("\n", eol)
        if updated != raw:
            with open(settings_path, "w", encoding="utf-8", newline="") as fh:
                fh.write(updated)

    proj_gradle = os.path.join(repo_path, "TMessagesProj", "build.gradle")
    if os.path.isfile(proj_gradle):
        with open(proj_gradle, "r", encoding="utf-8", newline="") as fh:
            raw = fh.read()
        eol = "\r\n" if "\r\n" in raw else "\n"
        text = raw.replace("\r\n", "\n")
        if not koliti_enabled:
            lines = [ln for ln in text.splitlines() if "project(':colgram-llama')" not in ln and 'project(":colgram-llama")' not in ln]
            new_text = "\n".join(lines) + ("\n" if text.endswith("\n") else "")
        else:
            new_text = text
            if "project(':colgram-llama')" not in new_text and 'project(":colgram-llama")' not in new_text:
                anchor = re.search(
                    r'^[ \t]*implementation[ \t]+project\(\s*["\']:colgram-singbox["\'][ \t]*\)[ \t]*$',
                    new_text,
                    re.MULTILINE,
                )
                if anchor is not None:
                    new_text = (
                        new_text[:anchor.end()]
                        + "\n    implementation project(':colgram-llama')"
                        + new_text[anchor.end():]
                    )
        updated = new_text.replace("\n", eol)
        if updated != raw:
            with open(proj_gradle, "w", encoding="utf-8", newline="") as fh:
                fh.write(updated)


def _sync_koliti_assets_and_native(repo_path: str, koliti_enabled: bool) -> None:
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    stash_root = os.path.join(root_dir, "build-tmp", "koliti-asset-cache")
    asset_targets = (
        ("koliti", os.path.join(repo_path, "TMessagesProj_AppStandalone", "src", "main", "assets", "koliti")),
        ("koliti_tts", os.path.join(repo_path, "TMessagesProj", "src", "main", "assets", "koliti_tts")),
        ("koliti_asr", os.path.join(repo_path, "TMessagesProj", "src", "main", "assets", "koliti_asr")),
    )
    raw_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "res", "raw")
    llama_dir = os.path.join(repo_path, "colgram-llama")
    core_dir = os.path.join(repo_path, "colgram-core", "src", "main", "java", "org", "colgram", "core")
    core_build_dir = os.path.join(repo_path, "colgram-core", "build")
    ui_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")
    test_file = os.path.join(
        repo_path, "TMessagesProj_AppTests", "src", "androidTest", "java", "org", "colgram", "core",
        "ColgramNativeAiDeviceTest.java",
    )

    if os.path.isdir(core_build_dir):
        shutil.rmtree(core_build_dir, ignore_errors=True)

    if not koliti_enabled:
        if os.path.isdir(llama_dir):
            shutil.rmtree(llama_dir, ignore_errors=True)
            print(" [-] Removed Telegram-Src/colgram-llama for Main APK (COLGRAM_KOLITI=0)")

        if os.path.isdir(core_dir):
            removed_core = 0
            for fn in _KOLITI_CORE_CLASS_FILES:
                p = os.path.join(core_dir, fn)
                if os.path.isfile(p):
                    os.remove(p)
                    removed_core += 1
            if removed_core:
                print(f" [-] Removed {removed_core} heavy Koliti core classes from Telegram-Src/colgram-core for Main APK")

        for key, path in asset_targets:
            if os.path.isdir(path):
                stash_dir = os.path.join(stash_root, key)
                has_files = any(os.scandir(path))
                if has_files and not os.path.isdir(stash_dir):
                    os.makedirs(stash_root, exist_ok=True)
                    shutil.move(path, stash_dir)
                else:
                    shutil.rmtree(path, ignore_errors=True)
                print(f" [-] Excluded assets/{key} from Main APK")

        if os.path.isdir(raw_dir):
            for fn in _KOLITI_RAW_AUDIO_FILES:
                p = os.path.join(raw_dir, fn)
                if os.path.isfile(p):
                    os.remove(p)

        if os.path.isfile(test_file):
            os.remove(test_file)

        if os.path.isdir(ui_dir):
            for filename, stub_code in _NOOP_KOLITI_UI_STUBS.items():
                dst = os.path.join(ui_dir, filename)
                with open(dst, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(stub_code)
            print(" [+] Installed no-op Koliti UI stubs in Telegram-Src for clean Main APK compilation")
    else:
        for key, path in asset_targets:
            stash_dir = os.path.join(stash_root, key)
            if not os.path.isdir(path) and os.path.isdir(stash_dir):
                os.makedirs(os.path.dirname(path), exist_ok=True)
                shutil.move(stash_dir, path)
                print(f" [+] Restored cached assets/{key} into Telegram-Src")


def _patch_text_replace_eol(full_path: str, target: str, replacement: str, label: str, eol_patch_fn) -> None:
    if not os.path.isfile(full_path):
        return
    with open(full_path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
        norm = fh.read().replace("\r\n", "\n")
    if replacement.replace("\r\n", "\n") in norm:
        return
    if target.replace("\r\n", "\n") in norm:
        eol_patch_fn(full_path, target, replacement, label)


def _upgrade_launch_ota_sheet(repo_path: str, eol_patch_fn) -> None:
    launch_path = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LaunchActivity.java"
    )
    if not os.path.isfile(launch_path):
        return
    with open(launch_path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
        norm = fh.read().replace("\r\n", "\n")
    if "ColgramUiKit.showCustomViewSheet(" in norm and "showColgramPendingUpdateDialog" in norm:
        return
    old_body = (
        "            AlertDialog.Builder builder = new AlertDialog.Builder(this);\n"
        '            builder.setTitle("Доступно обновление Colgram " + pending.version);\n'
        '            builder.setMessage((notes.isEmpty() ? "Новая сборка готова к установке." : notes)\n'
        '                    + (pending.expectedSha256 != null ? "\\n\\nSHA-256 проверен." : ""));\n'
        '            builder.setPositiveButton("Скачать и установить", (d, w) -> {\n'
        "                if (isFinishing() || isDestroyed()) return;\n"
        "                org.colgram.core.ColgramUpdater.downloadAndInstall(\n"
        "                        this, pending.downloadUrl, pending.version, pending.expectedSha256);\n"
        "            });\n"
        '            builder.setNeutralButton("Версии", (d, w) -> {\n'
        "                if (!isFinishing() && !isDestroyed() && actionBarLayout != null) {\n"
        "                    presentFragment(new ColgramVersionsActivity());\n"
        "                }\n"
        "            });\n"
        "            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);\n"
        "            showAlertDialog(builder);"
    )
    new_body = (
        "            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();\n"
        "            android.widget.LinearLayout bodyCard = ColgramUiKit.makeCard(this, pal, false);\n"
        "            android.widget.LinearLayout badgeRow = new android.widget.LinearLayout(this);\n"
        "            badgeRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);\n"
        '            badgeRow.addView(ColgramUiKit.makePillChip(this, "v" + pending.version,\n'
        "                    ColgramUiKit.withAlpha(pal.accentPrimary, 0x26), pal.accentPrimary,\n"
        "                    ColgramUiKit.withAlpha(pal.accentPrimary, 0x60)),\n"
        "                    org.telegram.ui.Components.LayoutHelper.createLinear(\n"
        "                            org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,\n"
        "                            org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));\n"
        "            if (pending.expectedSha256 != null) {\n"
        '                badgeRow.addView(ColgramUiKit.makePillChip(this, "SHA-256 ПРОВЕРЕН",\n'
        "                        ColgramUiKit.withAlpha(pal.semanticEmerald, 0x26), pal.semanticEmerald,\n"
        "                        ColgramUiKit.withAlpha(pal.semanticEmerald, 0x60)),\n"
        "                        org.telegram.ui.Components.LayoutHelper.createLinear(\n"
        "                                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,\n"
        "                                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT));\n"
        "            }\n"
        "            bodyCard.addView(badgeRow, org.telegram.ui.Components.LayoutHelper.createLinear(\n"
        "                    org.telegram.ui.Components.LayoutHelper.MATCH_PARENT,\n"
        "                    org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));\n"
        "            android.widget.TextView notesView = new android.widget.TextView(this);\n"
        '            notesView.setText(notes.isEmpty() ? "Новая сборка готова к установке." : notes);\n'
        "            notesView.setTextColor(pal.textPrimary);\n"
        "            notesView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13.5f);\n"
        "            notesView.setLineSpacing(AndroidUtilities.dp(3), 1.0f);\n"
        "            bodyCard.addView(notesView, org.telegram.ui.Components.LayoutHelper.createLinear(\n"
        "                    org.telegram.ui.Components.LayoutHelper.MATCH_PARENT,\n"
        "                    org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT));\n"
        "            org.telegram.ui.ActionBar.BaseFragment topFrag = actionBarLayout != null ? actionBarLayout.getLastFragment() : null;\n"
        "            org.telegram.ui.ActionBar.Theme.ResourcesProvider rp = topFrag != null ? topFrag.getResourceProvider() : null;\n"
        "            ColgramUiKit.showCustomViewSheet(\n"
        "                    this,\n"
        "                    rp,\n"
        "                    R.drawable.msg_download,\n"
        '                    "Доступно обновление Colgram " + pending.version,\n'
        '                    pending.expectedSha256 != null ? "Подписанный релиз · SHA-256 проверен" : "Официальный релиз Colgram",\n'
        "                    bodyCard,\n"
        '                    "Скачать и установить",\n'
        "                    sheet -> {\n"
        "                        if (isFinishing() || isDestroyed()) return true;\n"
        "                        org.colgram.core.ColgramUpdater.downloadAndInstall(\n"
        "                                this, pending.downloadUrl, pending.version, pending.expectedSha256);\n"
        "                        return true;\n"
        "                    },\n"
        '                    "Версии",\n'
        "                    sheet -> {\n"
        "                        if (!isFinishing() && !isDestroyed() && actionBarLayout != null) {\n"
        "                            presentFragment(new ColgramVersionsActivity());\n"
        "                        }\n"
        "                        return true;\n"
        "                    },\n"
        "                    LocaleController.getString(R.string.Cancel),\n"
        "                    false,\n"
        "                    false\n"
        "            );"
    )
    _patch_text_replace_eol(launch_path, old_body, new_body, "LaunchActivity ColgramUiKit OTA update sheet", eol_patch_fn)


def _upgrade_alerts_creator_subtitle(repo_path: str, eol_patch_fn) -> None:
    alerts_path = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Components", "AlertsCreator.java"
    )
    _patch_text_replace_eol(
        alerts_path,
        '                purgeCell.setText("Удалить полностью у себя", "", false, false);',
        '                purgeCell.setText("Удалить полностью у себя", "Стереть локальную копию из сейфа анти-удаления", false, false);',
        "AlertsCreator.java permanent delete anti-delete subtitle",
        eol_patch_fn,
    )


def _upgrade_data_settings_memory_sheet(repo_path: str, eol_patch_fn) -> None:
    data_path = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DataSettingsActivity.java"
    )
    if not os.path.isfile(data_path):
        return
    with open(data_path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
        norm = fh.read().replace("\r\n", "\n")
    if "colgramShowMemoryDialog" not in norm or "ColgramUiKit.showConfirmSheet(" in norm:
        return
    old_block = (
        "        final boolean enabled = org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled();\n"
        "        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());\n"
        '        builder.setTitle("Безлимитная память");\n'
        '        builder.setMessage("Включено: кэш медиа не ограничивается 300 МБ, просмотренные "\n'
        '                + "фото, видео и тексты остаются доступными всегда и хранятся внутри "\n'
        '                + "приложения, а не в галерее.\\n\\nВыключено: возвращается стандартное "\n'
        '                + "поведение Telegram с очисткой кэша.");\n'
        '        builder.setPositiveButton(enabled ? "Выключить" : "Включить", (dialog, which) -> {\n'
        "            boolean next = !org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled();\n"
        "            org.colgram.core.ColgramConfig.setUnlimitedMemoryEnabled(next);\n"
        "            org.colgram.core.ColgramUnlimitedMemory.apply(getContext(), next);\n"
        "            updateRows(true);\n"
        "        });\n"
        '        builder.setNegativeButton("Отмена", null);\n'
        "        showDialog(builder.create());"
    )
    new_block = (
        "        final boolean enabled = org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled();\n"
        "        ColgramUiKit.showConfirmSheet(\n"
        "                getParentActivity(),\n"
        "                getResourceProvider(),\n"
        "                R.drawable.msg_archive,\n"
        '                "Безлимитная память",\n'
        '                "Включено: кэш медиа не ограничивается 300 МБ, просмотренные "\n'
        '                        + "фото, видео и тексты остаются доступными всегда и хранятся внутри "\n'
        '                        + "приложения, а не в галерее.\\n\\nВыключено: возвращается стандартное "\n'
        '                        + "поведение Telegram с очисткой кэша.",\n'
        '                enabled ? "Выключить" : "Включить",\n'
        "                false,\n"
        "                () -> {\n"
        "                    boolean next = !org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled();\n"
        "                    org.colgram.core.ColgramConfig.setUnlimitedMemoryEnabled(next);\n"
        "                    org.colgram.core.ColgramUnlimitedMemory.apply(getContext(), next);\n"
        "                    updateRows(true);\n"
        "                },\n"
        '                "Отмена",\n'
        "                null\n"
        "        );"
    )
    _patch_text_replace_eol(data_path, old_block, new_block, "DataSettingsActivity ColgramUiKit memory sheet", eol_patch_fn)


def _harmonize_dialogs_overflow_menu(repo_path: str, eol_patch_fn) -> None:
    dialogs_path = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java"
    )
    if not os.path.isfile(dialogs_path):
        return
    with open(dialogs_path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
        norm = fh.read().replace("\r\n", "\n")
    if "ColgramDesignStudioActivity" not in norm:
        old_settings_anchor = (
            '        io.add(R.drawable.msg_settings, "Настройки Colgram", () -> {\n'
            "            presentFragment(new ColgramSettingsActivity());\n"
            "        });\n"
            "        if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFFEF4444);"
        )
        new_settings_block = (
            '        io.add(R.drawable.msg_theme, "Конструктор дизайна", () -> {\n'
            "            presentFragment(new ColgramDesignStudioActivity());\n"
            "        });\n"
            "        if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFF7C3AED);\n"
            + old_settings_anchor
            + "\n"
            '        io.add(R.drawable.msg_secret, "Бот-защитник", () -> {\n'
            "            presentFragment(new ColgramDefenderActivity());\n"
            "        });\n"
            "        if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFF00C853);"
        )
        _patch_text_replace_eol(
            dialogs_path,
            old_settings_anchor,
            new_settings_block,
            "DialogsActivity overflow menu DesignStudio & Defender entries",
            eol_patch_fn,
        )
    _patch_text_replace_eol(
        dialogs_path,
        '        io.add(R.drawable.msg_policy, "Анти-спам", () -> {',
        '        io.add(R.drawable.msg_policy, "Антиспам", () -> {',
        "DialogsActivity overflow menu Antispam label alignment",
        eol_patch_fn,
    )


def _configure_proxy_list_telemetry(repo_path: str, eol_patch_fn) -> None:
    proxy_path = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ProxyListActivity.java"
    )
    if not os.path.isfile(proxy_path):
        return
    with open(proxy_path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
        norm = fh.read().replace("\r\n", "\n")
    if "colgramHeroRow" not in norm or "VIEW_TYPE_COLGRAM_TELEMETRY" in norm:
        return
    specs = (
        (
            "ProxyListActivity colgramTelemetryRow field",
            "    private int colgramHeroRow = -1;",
            "    private int colgramHeroRow = -1;\n    private int colgramTelemetryRow = -1;",
        ),
        (
            "ProxyListActivity colgramTelemetryRow live refresh",
            "                if (heroChanged) {\n                    colgramRefreshBoundRow(colgramHeroRow);\n                }",
            "                if (heroChanged) {\n                    colgramRefreshBoundRow(colgramHeroRow);\n                    colgramRefreshBoundRow(colgramTelemetryRow);\n                }",
        ),
        (
            "ProxyListActivity colgramTelemetryRow row index",
            "        colgramHeroRow = rowCount++;\n        useProxyRow = rowCount++;",
            "        colgramHeroRow = rowCount++;\n        colgramTelemetryRow = rowCount++;\n        useProxyRow = rowCount++;",
        ),
        (
            "ProxyListActivity VIEW_TYPE_COLGRAM_TELEMETRY constant",
            "            VIEW_TYPE_COLGRAM_HERO = 7;",
            "            VIEW_TYPE_COLGRAM_HERO = 7,\n            VIEW_TYPE_COLGRAM_TELEMETRY = 8;",
        ),
        (
            "ProxyListActivity VIEW_TYPE_COLGRAM_TELEMETRY bind",
            "                    hero.bind(badgeIcon, accent, \"Colgram • Сеть и обход\", subtitle, pill, portChip, dohChip, secChip);\n                    break;\n                }",
            (
                "                    hero.bind(badgeIcon, accent, \"Colgram • Сеть и обход\", subtitle, pill, portChip, dohChip, secChip);\n"
                "                    break;\n"
                "                }\n"
                "                case VIEW_TYPE_COLGRAM_TELEMETRY: {\n"
                "                    ColgramUiKit.LiveTelemetryCardView telemetry = (ColgramUiKit.LiveTelemetryCardView) holder.itemView;\n"
                "                    telemetry.bindTheme();\n"
                "                    telemetry.refreshTelemetry();\n"
                "                    break;\n"
                "                }"
            ),
        ),
        (
            "ProxyListActivity VIEW_TYPE_COLGRAM_TELEMETRY create",
            "                case VIEW_TYPE_COLGRAM_HERO:\n                    view = new ColgramMenuChrome.HeroBannerCell(mContext);\n                    break;",
            "                case VIEW_TYPE_COLGRAM_HERO:\n                    view = new ColgramMenuChrome.HeroBannerCell(mContext);\n                    break;\n                case VIEW_TYPE_COLGRAM_TELEMETRY:\n                    view = new ColgramUiKit.LiveTelemetryCardView(mContext);\n                    break;",
        ),
        (
            "ProxyListActivity colgramTelemetryRow itemId",
            "            if (position == colgramHeroRow) {\n                return -24;",
            "            if (position == colgramHeroRow) {\n                return -24;\n            } else if (position == colgramTelemetryRow) {\n                return -25;",
        ),
        (
            "ProxyListActivity colgramTelemetryRow itemViewType",
            "            if (position == colgramHeroRow) {\n                return VIEW_TYPE_COLGRAM_HERO;",
            "            if (position == colgramHeroRow) {\n                return VIEW_TYPE_COLGRAM_HERO;\n            } else if (position == colgramTelemetryRow) {\n                return VIEW_TYPE_COLGRAM_TELEMETRY;",
        ),
    )
    for label, target, replacement in specs:
        _patch_text_replace_eol(proxy_path, target, replacement, label, eol_patch_fn)


def apply(repo_path: str, patch_file_fn, eol_patch_fn, koliti_enabled: bool = True) -> None:
    """Apply compile-time Koliti split gates, Gradle ABI/module wiring, and asset pruning."""
    _write_build_flags(repo_path, koliti_enabled)
    app_loader = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ApplicationLoader.java"
    )
    old_direct_gate = (
        "                if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
        "                    org.colgram.core.ColgramAiEngine.getInstance().unloadIfIdleInBackground();\n"
        "                }"
    )
    if os.path.isfile(app_loader):
        with open(app_loader, "r", encoding="utf-8", errors="ignore") as fh:
            if old_direct_gate in fh.read().replace("\r\n", "\n"):
                eol_patch_fn(
                    app_loader,
                    old_direct_gate,
                    (
                        "                if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
                        "                    try {\n"
                        '                        Class<?> engineCls = Class.forName("org.colgram.core.ColgramAiEngine");\n'
                        '                        Object engine = engineCls.getMethod("getInstance").invoke(null);\n'
                        "                        if (engine != null) {\n"
                        '                            engineCls.getMethod("unloadIfIdleInBackground").invoke(engine);\n'
                        "                        }\n"
                        "                    } catch (Throwable ignored) {}\n"
                        "                }"
                    ),
                    "ApplicationLoader onTrimMemory reflection upgrade",
                )
    for rel_path, label, target, replacement in _GATE_PATCH_SPECS:
        full_path = os.path.join(repo_path, *rel_path.split("/"))
        eol_patch_fn(full_path, target, replacement, label)
    _upgrade_launch_ota_sheet(repo_path, eol_patch_fn)
    _upgrade_alerts_creator_subtitle(repo_path, eol_patch_fn)
    _upgrade_data_settings_memory_sheet(repo_path, eol_patch_fn)
    _harmonize_dialogs_overflow_menu(repo_path, eol_patch_fn)
    _configure_proxy_list_telemetry(repo_path, eol_patch_fn)
    _configure_standalone_gradle(repo_path)
    _configure_llama_gradle(repo_path, koliti_enabled)
    _sync_koliti_assets_and_native(repo_path, koliti_enabled)
