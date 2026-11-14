"""Pinned DialogsActivity hint fixes: host anchors and generated-source convergence."""
from pathlib import Path
import shutil
import subprocess
import unittest

from theme_repairs import patch_story_hints

ROOT = Path(__file__).resolve().parents[1]
REL = "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"


FIXTURE = """public class DialogsActivity {
    void createView() {
                storyHint = new HintView2(context, HintView2.DIRECTION_RIGHT)
                        .setCloseButton(true)
                        .setText(StoryRecorder.cameraBtnSpan(context))
                        .setBgColor(getThemedColor(Theme.key_undo_background))
                        .setOnHiddenListener(() -> {});
    }

    void onResume() {
            storyHint.show();
    }

    private void updateStoriesPosting() {
                storyHint.show();
    }

    void showPremiumHint() {
            Spannable text = AndroidUtilities.replaceSingleTag(LocaleController.getString("StoriesPremiumHint2").replace('\\n', ' '), Theme.key_undo_cancelColor, 0, () -> {
                goPremium();
            });
            storyPremiumHint = new HintView2(context, HintView2.DIRECTION_RIGHT)
                .setText(text)
                .setBgColor(getThemedColor(Theme.key_undo_background));
    }
}
"""


class ThemeRepairsTest(unittest.TestCase):
    def test_rejects_unrelated_source(self):
        with self.assertRaisesRegex(ValueError, "story hint"):
            patch_story_hints("public class Unexpected {}")

    def assert_paired_colors(self, source):
        patched = patch_story_hints(source)
        self.assertEqual(patched, patch_story_hints(patched))
        self.assertEqual(1, patched.count("COLGRAM_STORY_HINT_PALETTE = 1"))
        self.assertEqual(1, patched.count("ColgramThemePolicy.forStoryHint("))
        self.assertEqual(2, patched.count(".setBgColor(hintColors.background)"))
        self.assertEqual(2, patched.count(".setTextColor(hintColors.text)"))
        self.assertIn("AndroidUtilities.replaceSingleLinkBold(", patched)
        self.assertIn("hintColors.link, () -> {", patched)
        self.assertNotIn(".setBgColor(getThemedColor(Theme.key_undo_background))", patched)
        self.assertEqual(2, patched.count("showColgramStoryHint();"))
        self.assertIn(".setCloseButton(true)", patched)
        self.assertIn("StoryRecorder.cameraBtnSpan(context)", patched)

    def test_both_hints_and_premium_link_keep_their_paired_colors(self):
        self.assert_paired_colors(FIXTURE)

    def test_pinned_upstream_and_existing_sources_converge(self):
        repo = ROOT / "Telegram-Src"
        if not (repo / REL).is_file() or not shutil.which("git"):
            self.skipTest("pinned upstream checkout is not installed")
        pristine = subprocess.check_output(["git", "-C", str(repo), "show", f"HEAD:{REL}"]).decode("utf-8")
        existing = (repo / REL).read_text(encoding="utf-8")
        for source in (pristine, existing):
            with self.subTest(source_len=len(source)):
                self.assert_paired_colors(source)


if __name__ == "__main__":
    unittest.main()
