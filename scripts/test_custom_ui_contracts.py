#!/usr/bin/env python3
"""Contract tests for the ColgramUiKit custom design system, thermal GPU fixes, and UI patch convergence."""

from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
TEMPLATES = SCRIPTS / "templates"
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
sys.path.insert(0, str(SCRIPTS))

TRACK3_UI_TEMPLATES = (
    "ColgramUiKit.java",
    "ColgramSettingsActivity.java",
    "ColgramDesignStudioActivity.java",
    "ColgramFilesActivity.java",
    "ColgramAccountSwitcherHelper.java",
    "ColgramAntiSpamActivity.java",
    "ColgramAiActivity.java",
    "ColgramAiSheet.java",
    "ColgramAiOrbView.java",
    "ColgramPluginsActivity.java",
    "ColgramTempMailActivity.java",
    "ColgramAutoReplyActivity.java",
    "ColgramVersionsActivity.java",
    "ColgramDefenderActivity.java",
    "ColgramMessageSeenSheet.java",
    "ColgramEditHistorySheet.java",
    "ColgramQuickBarView.java",
    "ColgramBottomBarConfig.java",
    "ColgramFloatWindowManager.java",
    "ColgramBotLoginBottomSheet.java",
    "ColgramQRLoginBottomSheet.java",
    "ColgramGlobalSearchView.java",
    "ColgramEasterEggs.java",
    "ColgramNativeColemailerBridge.java",
    "ColgramCallRoutes.java",
    "ColgramBrowserRoutes.java",
    "ColgramSubscriptionControls.java",
    "ProxyListWarpControls.java.inc",
    "ColgramAeroStyler.java",
    "ColgramMenuChrome.java",
)


def _eol_patch(full_path: str, target: str, replacement: str, _label: str) -> None:
    p = Path(full_path)
    raw = p.read_text(encoding="utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    norm = raw.replace("\r\n", "\n")
    t_norm = target.replace("\r\n", "\n")
    r_norm = replacement.replace("\r\n", "\n")
    if r_norm in norm:
        return
    if t_norm not in norm:
        raise AssertionError(f"Target not found in {p.name}: {t_norm[:80]!r}")
    p.write_text(norm.replace(t_norm, r_norm, 1).replace("\n", nl), encoding="utf-8", newline="")


class CustomUiContractsTest(unittest.TestCase):
    def test_zero_alert_dialog_builder_across_track3_surfaces(self):
        builder_re = re.compile(r"\bnew\s+(?:org\.telegram\.ui\.ActionBar\.)?AlertDialog\.Builder\b")
        import_re = re.compile(r"^import\s+org\.telegram\.ui\.ActionBar\.AlertDialog;", re.MULTILINE)
        for name in TRACK3_UI_TEMPLATES:
            src = (TEMPLATES / name).read_text(encoding="utf-8")
            self.assertIsNone(builder_re.search(src), f"{name} still constructs raw AlertDialog.Builder")
            self.assertIsNone(import_re.search(src), f"{name} still imports ActionBar.AlertDialog")

        edit_dlg = (CORE / "ColgramEditHistoryDialog.java").read_text(encoding="utf-8")
        self.assertNotIn("AlertDialog$Builder", edit_dlg)
        self.assertNotIn("0xFF141820", edit_dlg)

    def test_colgram_ui_kit_design_tokens_and_zero_koliti_leak(self):
        kit = (TEMPLATES / "ColgramUiKit.java").read_text(encoding="utf-8")
        for token in (
            "SHEET_TOP_RADIUS_DP = 24f;",
            "CARD_RADIUS_DP = 16f;",
            "INNER_CARD_RADIUS_DP = 14f;",
            "ICON_BADGE_RADIUS_DP = 10f;",
            "PILL_RADIUS_DP = 999f;",
            "CTA_HEIGHT_DP = 46;",
            "class LiveTelemetryCardView extends FrameLayout",
            "ColgramPolymorphicStealth.getStealthStatsSummary()",
            "ColgramTelegramWsBridge.getLiveTelemetrySummary()",
            "ColgramDpiBypass.getLiveModeLabel()",
            "ColgramDpiBypass.upstreamStatus()",
            "public static void showNotice(",
            "WindowManager.LayoutParams.FLAG_SECURE",
            "R.drawable.msg_viewchats",
        ):
            self.assertIn(token, kit, f"ColgramUiKit.java missing contract token: {token}")

        for filename in ("ColgramUiKit.java", "ColgramEasterEggs.java"):
            src = (TEMPLATES / filename).read_text(encoding="utf-8")
            for forbidden in (
                "import org.colgram.core.ColgramAiConfig;",
                "import org.colgram.core.ColgramAiEngine;",
                "import org.colgram.core.ColgramAiModelManager;",
                "import org.colgram.core.ColgramAiModels;",
                "import org.colgram.core.ColgramKolitiVoice;",
                "import org.colgram.core.ColgramNativeLlama;",
            ):
                self.assertNotIn(forbidden, src, f"{filename} leaked Koliti import {forbidden}")

    def test_live_telemetry_mounted_and_stale_iphone15_removed(self):
        settings = (TEMPLATES / "ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertIn("LiveTelemetryCardView", settings)
        self.assertIn("liveTelemetryRow", settings)
        self.assertNotIn("iPhone 15", settings)

        ai_act = (TEMPLATES / "ColgramAiActivity.java").read_text(encoding="utf-8")
        self.assertIn("R.drawable.input_mic", ai_act)
        self.assertNotIn("🎙", ai_act)

        sub_ctrl = (TEMPLATES / "ColgramSubscriptionControls.java").read_text(encoding="utf-8")
        self.assertIn("!org.colgram.core.ColgramHookHandler.shouldBypassFlagSecure()", sub_ctrl)
        self.assertIn("IMPORTANT_FOR_AUTOFILL_NO", sub_ctrl)

    def test_gpu_and_background_animator_thermal_contracts(self):
        aero = (TEMPLATES / "ColgramAeroStyler.java").read_text(encoding="utf-8")
        self.assertIn("cachedWaveW", aero)
        self.assertIn("cachedWaveH", aero)
        self.assertIn("shaderMatrix.setTranslate", aero)

        chrome = (TEMPLATES / "ColgramMenuChrome.java").read_text(encoding="utf-8")
        self.assertIn("setClipToOutline(true)", chrome)
        self.assertIn("cachedWavePaths", chrome)
        self.assertIn("cachedCrestPaths", chrome)
        self.assertNotIn("canvas.clipPath(", chrome)

        ai_sheet = (TEMPLATES / "ColgramAiSheet.java").read_text(encoding="utf-8")
        self.assertIn("onWindowVisibilityChanged", ai_sheet)
        self.assertIn("syncPulseAnimators", ai_sheet)
        self.assertIn("ApplicationLoader.mainInterfacePaused", ai_sheet)
        self.assertIn("ColgramPowerGuard.isPowerSaveMode", ai_sheet)

        eggs = (TEMPLATES / "ColgramEasterEggs.java").read_text(encoding="utf-8")
        self.assertIn("onWindowVisibilityChanged", eggs)
        self.assertIn("cachedGrad", eggs)
        self.assertIn("GLYPH_STRS", eggs)
        self.assertIn("applyFlagshipSheetChrome", eggs)
        self.assertNotIn("setShadowLayer(", eggs)

    def test_apply_patches_registers_uikit_and_ui_upgrades(self):
        ap = (SCRIPTS / "apply-patches.py").read_text(encoding="utf-8")
        self.assertGreaterEqual(
            ap.count('"ColgramUiKit.java"'),
            2,
            "ColgramUiKit.java must be registered in BOTH inject_hooks and sync_colgram_extra_templates_and_assets",
        )
        self.assertIn("ColgramUiKit.showConfirmSheet(", ap)
        self.assertIn('purgeCell.setText("Удалить полностью у себя", "Стереть локальную копию из сейфа анти-удаления", false, false);', ap)
        self.assertIn('"AlertsCreator.java permanent local delete checkbox UI"', ap)

    def test_koliti_split_patches_ui_upgrades_and_idempotency(self):
        import koliti_split_patches as ksp

        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "Telegram-Src"
            ui_dir = repo / "TMessagesProj/src/main/java/org/telegram/ui"
            comp_dir = ui_dir / "Components"
            comp_dir.mkdir(parents=True, exist_ok=True)

            launch = ui_dir / "LaunchActivity.java"
            launch.write_text(
                "public class LaunchActivity {\n"
                "    private void showColgramPendingUpdateDialog(org.colgram.core.ColgramUpdater.PendingUpdate pending) {\n"
                "        try {\n"
                "            String notes = pending.releaseNotes;\n"
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
                "            showAlertDialog(builder);\n"
                "        } catch (Throwable t) {}\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )

            alerts = comp_dir / "AlertsCreator.java"
            alerts.write_text(
                "public class AlertsCreator {\n"
                "    void f() {\n"
                '                purgeCell.setText("Удалить полностью у себя", "", false, false);\n'
                "    }\n"
                "}\n",
                encoding="utf-8",
            )

            data_settings = ui_dir / "DataSettingsActivity.java"
            data_settings.write_text(
                "public class DataSettingsActivity {\n"
                "    private void colgramShowMemoryDialog() {\n"
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
                "        showDialog(builder.create());\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )

            dialogs = ui_dir / "DialogsActivity.java"
            dialogs.write_text(
                "public class DialogsActivity {\n"
                "    void menu() {\n"
                "        if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED) {\n"
                '            io.add(R.drawable.msg_bot, "Koliti AI", () -> {});\n'
                "        }\n"
                '        io.add(R.drawable.msg_settings, "Настройки Colgram", () -> {\n'
                "            presentFragment(new ColgramSettingsActivity());\n"
                "        });\n"
                "        if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFFEF4444);\n"
                '        io.add(R.drawable.msg_policy, "Анти-спам", () -> {\n'
                "            presentFragment(new ColgramAntiSpamActivity());\n"
                "        });\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )

            proxy = ui_dir / "ProxyListActivity.java"
            proxy.write_text(
                "public class ProxyListActivity {\n"
                "    private int colgramHeroRow = -1;\n"
                "    void update() {\n"
                "                if (heroChanged) {\n"
                "                    colgramRefreshBoundRow(colgramHeroRow);\n"
                "                }\n"
                "    }\n"
                "    void updateRows() {\n"
                "        colgramHeroRow = rowCount++;\n"
                "        useProxyRow = rowCount++;\n"
                "    }\n"
                "    static final int\n"
                "            VIEW_TYPE_COLGRAM_HERO = 7;\n"
                "    void bind() {\n"
                '                    hero.bind(badgeIcon, accent, "Colgram • Сеть и обход", subtitle, pill, portChip, dohChip, secChip);\n'
                "                    break;\n"
                "                }\n"
                "    }\n"
                "    void create(int viewType) {\n"
                "                case VIEW_TYPE_COLGRAM_HERO:\n"
                "                    view = new ColgramMenuChrome.HeroBannerCell(mContext);\n"
                "                    break;\n"
                "    }\n"
                "    long getItemId(int position) {\n"
                "            if (position == colgramHeroRow) {\n"
                "                return -24;\n"
                "            }\n"
                "    }\n"
                "    int getItemViewType(int position) {\n"
                "            if (position == colgramHeroRow) {\n"
                "                return VIEW_TYPE_COLGRAM_HERO;\n"
                "            }\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )

            for _ in range(2):
                ksp._upgrade_launch_ota_sheet(str(repo), _eol_patch)
                ksp._upgrade_alerts_creator_subtitle(str(repo), _eol_patch)
                ksp._upgrade_data_settings_memory_sheet(str(repo), _eol_patch)
                ksp._harmonize_dialogs_overflow_menu(str(repo), _eol_patch)
                ksp._configure_proxy_list_telemetry(str(repo), _eol_patch)

            launch_txt = launch.read_text(encoding="utf-8")
            self.assertNotIn("AlertDialog.Builder", launch_txt)
            self.assertIn("SHA-256 ПРОВЕРЕН", launch_txt)
            self.assertIn("ColgramUiKit.showCustomViewSheet(", launch_txt)

            self.assertIn(
                'purgeCell.setText("Удалить полностью у себя", "Стереть локальную копию из сейфа анти-удаления", false, false);',
                alerts.read_text(encoding="utf-8"),
            )

            data_txt = data_settings.read_text(encoding="utf-8")
            self.assertNotIn("AlertDialog.Builder", data_txt)
            self.assertIn("ColgramUiKit.showConfirmSheet(", data_txt)

            dialogs_txt = dialogs.read_text(encoding="utf-8")
            self.assertEqual(1, dialogs_txt.count("ColgramDesignStudioActivity"))
            self.assertEqual(1, dialogs_txt.count("ColgramDefenderActivity"))
            self.assertIn('"Антиспам"', dialogs_txt)
            self.assertNotIn('"Анти-спам"', dialogs_txt)

            proxy_txt = proxy.read_text(encoding="utf-8")
            self.assertEqual(1, proxy_txt.count("VIEW_TYPE_COLGRAM_TELEMETRY = 8;"))
            self.assertIn("new ColgramUiKit.LiveTelemetryCardView(mContext)", proxy_txt)
            self.assertIn("colgramRefreshBoundRow(colgramTelemetryRow);", proxy_txt)


if __name__ == "__main__":
    unittest.main()
