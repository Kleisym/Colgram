#!/usr/bin/env python3
"""Contract tests for the Dual-APK Koliti split, edition-aware updater, CI workflow, and release publisher."""

import importlib.util
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))


def _load_apply_patches():
    spec = importlib.util.spec_from_file_location("apply_patches", SCRIPTS / "apply-patches.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class KolitiSplitContractsTest(unittest.TestCase):
    def test_build_flags_default_true(self):
        flags_file = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramBuildFlags.java"
        self.assertTrue(flags_file.is_file(), "ColgramBuildFlags.java must exist")
        text = flags_file.read_text(encoding="utf-8")
        self.assertIn("public static final boolean KOLITI_ENABLED = true;", text)

    def test_core_and_updater_edition_split_contracts(self):
        cfg = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramConfig.java").read_text(encoding="utf-8")
        self.assertIn("ColgramBuildFlags.KOLITI_ENABLED", cfg)
        self.assertIn('"ghost,proxy,saved,theme"', cfg)

        policy = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramUpdatePolicy.java").read_text(encoding="utf-8")
        self.assertIn("boolean preferKoliti", policy)
        self.assertIn("isKolitiApkName", policy)

        updater = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramUpdater.java").read_text(encoding="utf-8")
        self.assertIn("ColgramBuildFlags.KOLITI_ENABLED", updater)
        self.assertNotIn("catch (Exception ignored) {\n                    success = true;", updater)
        self.assertIn("success = false;", updater)
        self.assertIn("if (release == null)", updater)

        for name in ("ColgramEasterEggs.java", "ColgramQuickBarView.java"):
            txt = (SCRIPTS / "templates" / name).read_text(encoding="utf-8")
            for forbidden_import in (
                "import org.colgram.core.ColgramAiEngine;",
                "import org.colgram.core.ColgramAiModels;",
                "import org.colgram.core.ColgramKolitiVoice;",
                "import org.colgram.core.ColgramNativeLlama;",
            ):
                self.assertNotIn(forbidden_import, txt, f"{name} must use reflection instead of {forbidden_import}")

    def test_shared_ui_templates_gate_koliti_and_fix_bugs(self):
        expected_templates = (
            "ColgramQuickBarView.java",
            "ColgramBottomBarConfig.java",
            "ColgramDesignStudioActivity.java",
            "ColgramSettingsActivity.java",
            "ColgramEasterEggs.java",
            "ColgramColaSlideView.java",
            "ColgramEditHistorySheet.java",
            "ColgramMessageSeenSheet.java",
            "ColgramAeroStyler.java",
            "ColgramMenuChrome.java",
        )
        for name in expected_templates:
            txt = (SCRIPTS / "templates" / name).read_text(encoding="utf-8")
            self.assertIn("ColgramBuildFlags.KOLITI_ENABLED", txt, f"{name} must gate Koliti via ColgramBuildFlags")

        edit_sheet = (SCRIPTS / "templates" / "ColgramEditHistorySheet.java").read_text(encoding="utf-8")
        self.assertIn("ThreadLocal<SimpleDateFormat>", edit_sheet)

        aero = (SCRIPTS / "templates" / "ColgramAeroStyler.java").read_text(encoding="utf-8")
        self.assertIn("BACKDROP_SCALE = 0.25f", aero)
        self.assertIn("onSizeChanged(", aero)
        self.assertIn("inJustDecodeBounds", aero)
        self.assertIn("inSampleSize", aero)

    def test_apply_patches_cli_and_modular_wiring(self):
        patcher = _load_apply_patches()
        self.assertTrue(patcher.resolve_koliti_enabled([], {"COLGRAM_KOLITI": "1"}))
        self.assertFalse(patcher.resolve_koliti_enabled([], {"COLGRAM_KOLITI": "0"}))
        self.assertFalse(patcher.resolve_koliti_enabled(["Telegram-Src", "--no-koliti"], {"COLGRAM_KOLITI": "1"}))
        self.assertTrue(patcher.resolve_koliti_enabled(["Telegram-Src", "--koliti"], {"COLGRAM_KOLITI": "0"}))

        ap_text = (SCRIPTS / "apply-patches.py").read_text(encoding="utf-8")
        self.assertIn("from bypass_bg_patches import apply", ap_text)
        self.assertIn("from attach_del_perf_patches import apply", ap_text)
        self.assertIn("from koliti_split_patches import apply", ap_text)

    def test_koliti_split_patches_temp_tree_both_modes(self):
        import koliti_split_patches as ksp

        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "Telegram-Src"
            flags = repo / "colgram-core/src/main/java/org/colgram/core/ColgramBuildFlags.java"
            flags.parent.mkdir(parents=True, exist_ok=True)
            flags.write_text("package org.colgram.core;\npublic final class ColgramBuildFlags { public static final boolean KOLITI_ENABLED = true; }\n", encoding="utf-8")

            settings = repo / "settings.gradle"
            settings.write_text("include ':colgram-core'\ninclude ':colgram-llama'\nproject(':colgram-llama').projectDir = file('colgram-llama')\n", encoding="utf-8")

            tm_gradle = repo / "TMessagesProj/build.gradle"
            tm_gradle.parent.mkdir(parents=True, exist_ok=True)
            tm_gradle.write_text("dependencies {\n    implementation project(':colgram-singbox')\n    implementation project(':colgram-llama')\n}\n", encoding="utf-8")

            sa_gradle = repo / "TMessagesProj_AppStandalone/build.gradle"
            sa_gradle.parent.mkdir(parents=True, exist_ok=True)
            sa_gradle.write_text('noCompress += ["pack", "gguf"]\nabiFilters "armeabi-v7a", "arm64-v8a", "x86", "x86_64"\n', encoding="utf-8")

            (repo / "colgram-llama").mkdir(parents=True, exist_ok=True)
            raw_dir = repo / "TMessagesProj/src/main/res/raw"
            raw_dir.mkdir(parents=True, exist_ok=True)
            (raw_dir / "koliti_song.ogg").write_bytes(b"ogg")
            ui_dir = repo / "TMessagesProj/src/main/java/org/telegram/ui"
            ui_dir.mkdir(parents=True, exist_ok=True)
            (ui_dir / "ColgramAiActivity.java").write_text("class ColgramAiActivity {}", encoding="utf-8")

            core_dir = repo / "colgram-core/src/main/java/org/colgram/core"
            (core_dir / "ColgramAiEngine.java").write_text("package org.colgram.core; public class ColgramAiEngine {}", encoding="utf-8")
            (core_dir / "ColgramAiConfig.java").write_text("package org.colgram.core; public class ColgramAiConfig {}", encoding="utf-8")

            # Disable Koliti
            ksp._write_build_flags(str(repo), False)
            ksp._configure_standalone_gradle(str(repo))
            ksp._configure_llama_gradle(str(repo), False)
            ksp._sync_koliti_assets_and_native(str(repo), False)

            self.assertIn("KOLITI_ENABLED = false;", flags.read_text(encoding="utf-8"))
            self.assertNotIn(":colgram-llama", settings.read_text(encoding="utf-8"))
            self.assertNotIn("colgram-llama", tm_gradle.read_text(encoding="utf-8"))
            self.assertIn('noCompress += ["pack", "gguf", "onnx"]', sa_gradle.read_text(encoding="utf-8"))
            self.assertIn("colgramAbis", sa_gradle.read_text(encoding="utf-8"))
            self.assertFalse((repo / "colgram-llama").exists())
            self.assertFalse((raw_dir / "koliti_song.ogg").exists())
            self.assertFalse((core_dir / "ColgramAiEngine.java").exists())
            self.assertTrue((core_dir / "ColgramAiConfig.java").exists())
            self.assertIn("REQ_PICK_MEDIA_FROM_SHEET", (ui_dir / "ColgramAiActivity.java").read_text(encoding="utf-8"))

            # Re-enable Koliti
            ksp._write_build_flags(str(repo), True)
            ksp._configure_llama_gradle(str(repo), True)
            self.assertIn("KOLITI_ENABLED = true;", flags.read_text(encoding="utf-8"))
            self.assertIn("include ':colgram-llama'", settings.read_text(encoding="utf-8"))
            self.assertIn("implementation project(':colgram-llama')", tm_gradle.read_text(encoding="utf-8"))

    def test_ci_workflow_dual_apk_and_signing_contracts(self):
        yml = (ROOT / ".github/workflows/build-colgram.yml").read_text(encoding="utf-8")
        self.assertIn("paths-ignore:", yml)
        self.assertIn("Free Runner Disk Space", yml)
        self.assertIn("rm -rf Telegram-Src/TMessagesProj_AppStandalone/build Telegram-Src/colgram-core/build", yml)
        self.assertIn("lib/armeabi-v7a/", yml)
        self.assertIn("COLGRAM_KOLITI: \"0\"", yml)
        self.assertIn("COLGRAM_KOLITI: \"1\"", yml)
        self.assertIn("-PcolgramAbis=arm64-v8a,x86_64", yml)
        self.assertIn('"$ZIPALIGN" -f -P 16 4', yml)
        self.assertIn('"$ZIPALIGN" -c -P 16 4 "$MAIN_OUT"', yml)
        self.assertIn('"$ZIPALIGN" -c -P 16 4 "$KOLITI_OUT"', yml)
        self.assertIn("3c5326960d586b6906a7ab09f7f3039f2385c0c5f8190527614569169e132d41", yml)
        self.assertIn("2147483648", yml)
        self.assertIn("Colgram-${{ env.UPSTREAM_TAG }}.apk", yml)
        self.assertIn("Colgram-${{ env.UPSTREAM_TAG }}-Koliti.apk", yml)
        self.assertIn(
            "if: startsWith(github.ref, 'refs/tags/v') || github.event_name == 'workflow_dispatch'",
            yml,
        )
        self.assertNotIn("github.event_name == 'push'", yml)

    def test_release_publish_body_and_safe_ordering(self):
        import release_publish as rp

        body = rp.build_release_body(
            "12.10.6",
            "v12.10.6-ci171",
            {
                "Colgram-12.10.6.apk": {"size": 538_000_000, "sha256": "a" * 64},
                "Colgram-12.10.6-Koliti.apk": {"size": 1_950_000_000, "sha256": "b" * 64},
                "NetBypass.exe": {"size": 2_159_616, "sha256": "c" * 64},
                "NetBypass-Portable.zip": {"size": 960_993, "sha256": "d" * 64},
            },
        )
        self.assertIn("colgram-release-poster.png", body)
        self.assertIn(
            "[![Смотреть видео-обзор Colgram 12.10.6 (1080p60)](https://raw.githubusercontent.com/Kleisym/Colgram/main/docs/media/colgram-release-preview.gif)](https://github.com/Kleisym/Colgram/releases/download/v12.10.6-ci171/colgram-release.mp4)",
            body,
        )
        self.assertIn("Colgram-12.10.6.apk", body)
        self.assertIn("Colgram-12.10.6-Koliti.apk", body)
        self.assertIn("Next-Gen", body)
        self.assertIn("NetBypass v6.0", body)

        legacy = rp.legacy_tags_to_delete(
            [
                {"id": 1, "tag_name": "v12.10.6-ci171"},
                {"id": 2, "tag_name": "koliti-models-v3"},
                {"id": 3, "tag_name": "colgram-f2908b1-98"},
                {"id": 4, "tag_name": "colgram-12.10.6-170"},
            ],
            {"v12.10.6-ci171", "koliti-models-v3"},
        )
        self.assertEqual([(3, "colgram-f2908b1-98"), (4, "colgram-12.10.6-170")], legacy)

        orphans = rp.legacy_orphan_tags_to_delete(
            [{"name": "v12.10.6-ci171"}, {"name": "koliti-models-v3"}, {"name": "old-orphan-tag"}],
            {"v12.10.6-ci171", "koliti-models-v3"},
        )
        self.assertEqual(["old-orphan-tag"], orphans)

        self.assertFalse(
            rp.is_remote_manifest_safe_to_prune(
                "https://github.com/Kleisym/Colgram/releases/download/colgram-f2908b1-98/colgram-signed-release.apk",
                "v12.10.6-ci171",
                {"colgram-f2908b1-98"},
            )
        )
        self.assertTrue(
            rp.is_remote_manifest_safe_to_prune(
                "https://github.com/Kleisym/Colgram/releases/download/v12.10.6-ci171/Colgram-12.10.6.apk",
                "v12.10.6-ci171",
                {"colgram-f2908b1-98"},
            )
        )

        with tempfile.TemporaryDirectory() as tmp:
            mp = Path(tmp) / "telegram-native.json"
            mp.write_text('{"apk_url":"old","apk_sha256":"0"}\n', encoding="utf-8")
            updated = rp.update_telegram_native_manifest(
                mp,
                "https://github.com/Kleisym/Colgram/releases/download/v12.10.6-ci171/Colgram-12.10.6.apk",
                "e" * 64,
            )
            self.assertEqual("e" * 64, updated["apk_sha256"])

        rp_src = (SCRIPTS / "release_publish.py").read_text(encoding="utf-8")
        self.assertIn("--prune-legacy", rp_src)
        self.assertIn('"make_latest": "true"', rp_src)
        self.assertIn('"prerelease": True', rp_src)
        self.assertLess(rp_src.index("update_telegram_native_manifest("), rp_src.index("_prune_legacy_releases_and_tags("))
        self.assertNotIn("print(token", rp_src)


if __name__ == "__main__":
    unittest.main()
