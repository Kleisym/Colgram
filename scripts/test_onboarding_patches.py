"""Regression tests for persistent account-free network controls and welcome branding."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


class OnboardingPatchesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = ROOT / "scripts/onboarding_patches.py"
        if not path.is_file():
            raise AssertionError("No dedicated onboarding patch; the short always-visible marker still false-matches")
        spec = importlib.util.spec_from_file_location("onboarding_patches", path)
        cls.patch = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.patch)

    def login(self):
        return '''public class LoginActivity {
        proxyButtonView = new ImageView(context);
        proxyButtonView.setImageDrawable(proxyDrawable = new ProxyDrawable(context));
        proxyButtonView.setOnClickListener(v -> presentFragment(new ProxyListActivity()));
        proxyButtonView.setAlpha(0f);
        proxyButtonView.setVisibility(View.GONE);
        sizeNotifierFrameLayout.addView(proxyButtonView, LayoutHelper.createFrame(32, 32, Gravity.RIGHT | Gravity.TOP, 16, 16, 16, 16));
    private void updateProxyButton(boolean animated, boolean force) {
        if (proxyEnabled) {
            showProxyButton(true, animated);
        } else if (connecting) {
            showProxyButtonDelayed();
        } else {
            showProxyButton(false, animated);
        }
    }
    private boolean proxyButtonVisible;
}'''

    def intro(self):
        return '''public class IntroActivity {
    private Drawable logoDrawable;
        logoDrawable = context.getResources().getDrawable(R.drawable.telegram_logo).mutate();
        frameContainerView.addView(themeFrameLayout, LayoutHelper.createFrame(64, 64, Gravity.TOP | Gravity.RIGHT, 0, themeMargin, themeMargin, 0));
            loadTexture(R.drawable.intro_tg_plane, 21);
                paint.setColor(ThemeColors.TELEGRAM_COLOR); // It's logo color, it should not be colored by the theme
        TextureView textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(listener);
    private void updateColors(boolean fromTheme) {
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
    }
}'''

    def dialogs(self):
        return '''public class DialogsActivity {
        if (!onlySelect && searchString == null && folderId == 0 && communityId == 0) {
            downloadsItem.setVisibility(View.GONE);
            updateProxyButton(false, false);
        }
}'''

    def test_login_is_immediately_visible_even_when_tcp_state_is_unchanged(self):
        result = self.patch.patch_login_network_menu(self.login())
        method = result[result.index("private void updateProxyButton"):]
        self.assertNotIn("showProxyButtonDelayed();", method)
        self.assertNotIn("showProxyButton(false", method)
        self.assertNotIn("currentConnectionState == state && !force", method)
        self.assertIn("proxyButtonView.setVisibility(View.VISIBLE)", method)
        self.assertIn("proxyButtonView.setAlpha(1f)", method)
        self.assertNotIn("new ColgramSettingsActivity()", result)
        self.assertIn("new ProxyListActivity()", result)
        self.assertIn("createFrame(48, 48", result)
        self.assertNotIn("toggleProxy", result)

    def test_login_patch_is_idempotent_and_migrates_old_click_injection(self):
        old = self.login().replace("v -> presentFragment(new ProxyListActivity())", '''v -> {
            org.colgram.core.ColgramProxyManager.populateSharedConfigProxies();
            presentFragment(new ProxyListActivity());
        }''')
        once = self.patch.patch_login_network_menu(old)
        self.assertEqual(once, self.patch.patch_login_network_menu(once))
        self.assertEqual(1, once.count('setTag("colgram_network_menu")'))

    def test_intro_uses_real_colgram_texture_without_changing_surface_lifecycle(self):
        result = self.patch.patch_intro_branding_and_menu(self.intro())
        self.assertNotIn("R.drawable.intro_tg_plane", result)
        self.assertNotIn("ThemeColors.TELEGRAM_COLOR", result)
        self.assertNotIn("R.drawable.telegram_logo", result)
        self.assertIn("R.drawable.colgram_cloud_logo", result)
        self.assertIn("cloud.draw(new android.graphics.Canvas(bitmap))", result)
        self.assertIn("paint.setColor(android.graphics.Color.TRANSPARENT)", result)
        self.assertIn("TextureView textureView = new TextureView(context)", result)
        self.assertIn("textureView.setSurfaceTextureListener(listener)", result)
        self.assertIn('setTag("colgram_network_menu")', result)
        self.assertIn("AndroidUtilities.statusBarHeight / AndroidUtilities.density", result)

    def test_intro_patch_is_idempotent(self):
        once = self.patch.patch_intro_branding_and_menu(self.intro())
        self.assertEqual(once, self.patch.patch_intro_branding_and_menu(once))
        self.assertEqual(1, once.count("frameContainerView.addView(colgramNetworkButton"))

    def test_header_opens_the_original_proxy_and_warp_menu(self):
        result = self.patch.patch_dialogs_network_menu(self.dialogs())
        self.assertIn("!onlySelect && searchString == null && folderId == 0", result)
        self.assertNotIn("new ColgramSettingsActivity()", result)
        self.assertIn("new ProxyListActivity()", result)
        self.assertNotIn("toggleProxy", result)
        self.assertIn("new ProxyListActivity()", result)
        self.assertIn('setTag("colgram_network_menu")', result)

    def test_header_patch_is_idempotent_and_migrates_previous_toggle(self):
        once = self.patch.patch_dialogs_network_menu(self.dialogs())
        self.assertEqual(once, self.patch.patch_dialogs_network_menu(once))
        self.assertEqual(1, once.count("menu.addItem(2, proxyDrawable)"))
        previous = self.dialogs().replace("downloadsItem.setVisibility(View.GONE);", '''downloadsItem.setVisibility(View.GONE);
            org.telegram.ui.ActionBar.ActionBarMenuItem colgramProxyItem = menu.addItem(2, proxyDrawable);
            if (colgramProxyItem != null) {
                colgramProxyItem.setContentDescription(getString(R.string.ProxySettings));
                colgramProxyItem.setOnClickListener(v -> org.colgram.core.ColgramProxyManager.toggleProxy(getParentActivity()));
                colgramProxyItem.setOnLongClickListener(v -> {
                    presentFragment(new ProxyListActivity());
                    return true;
                });
            }''')
        migrated = self.patch.patch_dialogs_network_menu(previous)
        self.assertNotIn("toggleProxy", migrated)
        self.assertEqual(1, migrated.count("menu.addItem(2, proxyDrawable)"))

    def test_unknown_upstream_anchors_fail_instead_of_silently_claiming_success(self):
        for transform in (self.patch.patch_login_network_menu, self.patch.patch_intro_branding_and_menu,
                          self.patch.patch_dialogs_network_menu):
            with self.assertRaises(ValueError):
                transform("public class Unknown {}")

    def test_proxy_cloud_header_keeps_the_original_warp_menu(self):
        current = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java")
        if not current.exists():
            self.skipTest("upstream checkout is not present")
        text = current.read_text(encoding="utf-8")
        self.assertIn("warpRow", text)
        branded = self.patch.patch_proxy_cloud_branding(text)
        self.assertEqual(branded, self.patch.patch_proxy_cloud_branding(branded))
        self.assertIn("R.drawable.colgram_cloud_logo", branded)
        for name in ("warpRow", "warpDetailRow", "proxyAddRow", "useProxyRow"):
            self.assertEqual(text.count(name), branded.count(name))

    def test_custom_cloud_is_a_scalable_red_vector(self):
        root = ET.parse(ROOT / "assets/colgram_cloud_logo.xml").getroot()
        self.assertEqual("vector", root.tag)
        self.assertEqual("64", root.get("{http://schemas.android.com/apk/res/android}viewportWidth"))
        gradient = root.find(".//gradient")
        self.assertEqual("#FF4A56", gradient.get("{http://schemas.android.com/apk/res/android}endColor"))

    def test_pristine_pinned_upstream_and_already_patched_tree_converge(self):
        checkout = ROOT / "Telegram-Src"
        if not (checkout / ".git").exists():
            self.skipTest("upstream checkout is not present")
        for name, transform in (("LoginActivity", self.patch.patch_login_network_menu),
                                ("IntroActivity", self.patch.patch_intro_branding_and_menu),
                                ("DialogsActivity", self.patch.patch_dialogs_network_menu)):
            path = "TMessagesProj/src/main/java/org/telegram/ui/" + name + ".java"
            pristine = subprocess.check_output(["git", "-C", str(checkout), "show", "HEAD:" + path]).decode("utf-8")
            transformed = transform(pristine)
            self.assertEqual(transformed, transform(transformed), name + " pristine repeat")
            current = (checkout / path).read_text(encoding="utf-8")
            transformed = transform(current)
            self.assertEqual(transformed, transform(transformed), name + " patched repeat")


if __name__ == "__main__":
    unittest.main()
