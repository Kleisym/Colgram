"""Custom onboarding contract: render source, lifecycle, theme and Android compilation."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "scripts/templates/ColgramIntroArtView.java"


class IntroArtViewTest(unittest.TestCase):
    def source(self):
        self.assertTrue(TEMPLATE.is_file(), "All four middle slides still use stock native artwork")
        return TEMPLATE.read_text(encoding="utf-8")

    def test_six_scenes_keep_the_original_endpoint_drawables(self):
        source = self.source()
        self.assertIn("R.drawable.colgram_app_avatar", source)
        self.assertIn("R.drawable.colgram_intro_cloud", source)
        for name in ("drawWelcome", "drawFast", "drawFree", "drawPowerful", "drawPrivate", "drawCloud"):
            self.assertIn("void " + name + "(", source)
        for stock in ("TextureView", "GLES20", "Intro.", "R.drawable.intro_", "drawLock", "drawClock", "drawInfinity"):
            self.assertNotIn(stock, source)
        self.assertIn("clipPath(avatarClip)", source)
        self.assertIn("56, 26, 184, 154", source)
        self.assertIn("30, 27, 210, 153", source)

    def test_swipes_crossfade_and_translate_both_adjacent_custom_scenes(self):
        source = self.source()
        self.assertIn("Math.max(0f, Math.min(5f, progress))", source)
        self.assertIn("Math.abs(pageProgress - page)", source)
        self.assertIn("saveLayerAlpha", source)
        self.assertIn("canvas.translate", source)
        self.assertIn("setScrolling(boolean", source)
        self.assertIn("setPageProgress(float", source)

    def test_entry_motion_is_finite_respects_system_scale_and_stops_offscreen(self):
        source = self.source()
        self.assertIn("ValueAnimator.areAnimatorsEnabled()", source)
        self.assertIn("setDuration(900)", source)
        self.assertIn("onDetachedFromWindow()", source)
        self.assertIn("onVisibilityChanged", source)
        self.assertIn("setMotionEnabled(boolean", source)
        self.assertIn("cancelEntryAnimation()", source)
        self.assertNotIn("INFINITE", source)
        self.assertNotIn("postInvalidateOnAnimation()", source)
        self.assertIn("Theme.key_windowBackgroundWhite", source)
        self.assertIn("Theme.key_windowBackgroundWhiteBlackText", source)

    def test_template_compiles_against_installed_android_platform(self):
        self.source()
        sdk = Path(os.environ.get("ANDROID_SDK_ROOT", r"C:/android-sdk"))
        platforms = sorted(sdk.glob("platforms/android-*/android.jar"))
        javac = shutil.which("javac")
        if not javac:
            candidates = list(Path(r"C:/colgram-tools").glob("jdk-*/bin/javac.exe"))
            javac = str(candidates[-1]) if candidates else None
        if not platforms or not javac:
            self.skipTest("Android SDK/JDK not installed")
        with tempfile.TemporaryDirectory(prefix="colgram-intro-") as temp:
            work = Path(temp)
            theme = work / "org/telegram/ui/ActionBar/Theme.java"
            theme.parent.mkdir(parents=True)
            theme.write_text("package org.telegram.ui.ActionBar; public class Theme { public static final int key_windowBackgroundWhite=0, key_windowBackgroundWhiteBlackText=1; public static int getColor(int key) { return 0; } }", encoding="utf-8")
            resources = work / "org/telegram/messenger/R.java"
            resources.parent.mkdir(parents=True)
            resources.write_text("package org.telegram.messenger; public class R { public static class drawable { public static int colgram_app_avatar=1, colgram_intro_cloud=2; } }", encoding="utf-8")
            command = [javac, "-proc:none", "-encoding", "UTF-8", "-classpath", str(platforms[-1]), "-d", str(work / "classes"), str(theme), str(resources), str(TEMPLATE)]
            result = subprocess.run(command, capture_output=True, text=True, errors="replace")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
