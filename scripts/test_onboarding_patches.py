"""Regression tests for persistent account-free network controls and welcome branding."""
import importlib.util
import math
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
    private CharSequence[] titles;
    private volatile int currentViewPagerPage;
        LocaleController.getString(R.string.Page6Message)
        logoDrawable = context.getResources().getDrawable(R.drawable.telegram_logo).mutate();
        logoDrawable.setBounds(0, dp(8.666f), dp(115), dp(35));
        SpannableStringBuilder ssb = new SpannableStringBuilder(LocaleController.getString(R.string.Page1Title));
        ssb.setSpan(new ImageSpan(logoDrawable), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titles[0] = ssb;
        frameContainerView.addView(themeFrameLayout, LayoutHelper.createFrame(64, 64, Gravity.TOP | Gravity.RIGHT, 0, themeMargin, themeMargin, 0));
    public class EGLThread extends DispatchQueue {
        Intro.setPage(currentViewPagerPage);
        Intro.setPage(currentViewPagerPage);
            loadTexture(R.drawable.intro_private_screw, 20);
            loadTexture(R.drawable.intro_tg_plane, 21);
            loadTexture(v -> {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(ThemeColors.TELEGRAM_COLOR); // It's logo color, it should not be colored by the theme
                return bm;
            }, 22);
            loadTexture(telegramMaskProvider, 23);
    }
        TextureView textureView = new TextureView(context);
        frameLayout2.addView(textureView, LayoutHelper.createFrame(ICON_WIDTH_DP, ICON_HEIGHT_DP, Gravity.CENTER));
        textureView.setSurfaceTextureListener(listener);
        viewPager = new ViewPager(context);
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                bottomPages.setPageOffset(position, positionOffset);
                Intro.setScrollOffset(positionOffset);
            }
            public void onPageSelected(int i) {
                currentViewPagerPage = i;
            }
            public void onPageScrollStateChanged(int i) {
                if (i == ViewPager.SCROLL_STATE_DRAGGING) {
                    dragging = true;
                }
            }
    private void updateColors(boolean fromTheme) {
        logoDrawable.setColorFilter(Theme.multAlpha(getThemedColor(Theme.key_actionBarDefaultTitle), 0.9f), PorterDuff.Mode.MULTIPLY);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
    }
    private class IntroAdapter extends PagerAdapter {
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

    def test_intro_uses_all_custom_art_and_removes_the_entire_unused_gl_lifecycle(self):
        result = self.patch.patch_intro_branding_and_menu(self.intro())
        for obsolete in ("R.drawable.intro_tg_plane", "ThemeColors.TELEGRAM_COLOR", "logoDrawable",
                         "TextureView", "EGLThread", "eglThread", "Intro.set"):
            self.assertNotIn(obsolete, result)
        self.assertIn("colgramIntroArt = new ColgramIntroArtView(context)", result)
        self.assertIn('setTag("colgram_intro_art")', result)
        self.assertIn("createFrame(240, 180, Gravity.CENTER)", result)
        self.assertIn('setTag("colgram_network_menu")', result)
        self.assertIn("AndroidUtilities.statusBarHeight / AndroidUtilities.density", result)

    def test_pager_reports_absolute_swipe_progress_without_loading_native_intro(self):
        result = self.patch.patch_intro_branding_and_menu(self.intro())
        self.assertIn("colgramIntroArt.setPageProgress(position + positionOffset)", result)
        self.assertIn("colgramIntroArt.setSelectedPage(i)", result)
        self.assertIn("colgramIntroArt.setScrolling(i != ViewPager.SCROLL_STATE_IDLE)", result)
        self.assertIn("bottomPages.setPageOffset(position, positionOffset)", result)
        self.assertIn('LocaleController.getString(R.string.Page6Message).replace("Telegram", "Colgram")', result)
        self.assertIn("colgramIntroArt.invalidate()", result)

    def test_intro_replaces_legacy_gl_cloud_without_reinstalling_menu(self):
        old = self.intro().replace("private Drawable logoDrawable;", '''private Drawable logoDrawable;
    private android.widget.ImageView colgramNetworkButton;''').replace(
            "R.drawable.telegram_logo", "R.drawable.colgram_cloud_logo").replace(
            "        ssb.setSpan(new ImageSpan(logoDrawable), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);\n        titles[0] = ssb;",
            '        titles[0] = "Colgram";').replace(
            "loadTexture(R.drawable.intro_tg_plane, 21);", '''loadTexture(unused -> {
                android.graphics.drawable.Drawable cloud = getParentActivity().getResources().getDrawable(R.drawable.colgram_cloud_logo).mutate();
                Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                cloud.setBounds(0, 0, 512, 512);
                cloud.draw(new android.graphics.Canvas(bitmap));
                return bitmap;
            }, 21);''').replace(
            "ThemeColors.TELEGRAM_COLOR", "android.graphics.Color.TRANSPARENT").replace(
            "        TextureView textureView", '''        // COLGRAM_INTRO_BRANDING_MENU = 2
        colgramNetworkButton.setTag("colgram_network_menu");
        TextureView textureView''')
        result = self.patch.patch_intro_branding_and_menu(old)
        self.assertNotIn("R.drawable.colgram_cloud_logo", result)
        self.assertIn("new ColgramIntroArtView(context)", result)
        self.assertEqual(1, result.count('setTag("colgram_network_menu")'))
        self.assertEqual(result, self.patch.patch_intro_branding_and_menu(result))

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

    def test_startup_wrapper_fits_the_whole_original_avatar_inside_android_mask(self):
        path = ROOT / "assets/colgram_startup_avatar.xml"
        self.assertTrue(path.is_file(), "startup needs a dedicated mask-safe avatar drawable")
        root = ET.parse(path).getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual("inset", root.tag)
        self.assertEqual("@drawable/colgram_app_avatar", root.get(android + "drawable"))
        inset = float(root.get(android + "inset").removesuffix("%")) / 100
        # Android 12: a 288dp asset with no icon-background layer must fit inside
        # the inner 192dp circle. Check the corner, not merely the side length.
        avatar_side = 288 * (1 - 2 * inset)
        self.assertLess(math.hypot(avatar_side / 2, avatar_side / 2), 96)
        self.assertGreater(avatar_side, 110, "the avatar must remain recognizable")

    def test_cloud_slide_art_is_independent_of_the_menu_glyph(self):
        path = ROOT / "assets/colgram_intro_cloud.xml"
        self.assertTrue(path.is_file(), "the cloud slide needs its own red artwork")
        art = ET.parse(path).getroot()
        menu = ET.parse(ROOT / "assets/colgram_cloud_logo.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual("vector", art.tag)
        self.assertEqual("#FF4A56", art.find(".//gradient").get(android + "endColor"))
        menu_paths = {node.get(android + "pathData") for node in menu.findall("path")}
        art_paths = {node.get(android + "pathData") for node in art.findall("path")}
        self.assertTrue(menu_paths.isdisjoint(art_paths))

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
