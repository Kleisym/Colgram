"""Cold-launch resource regressions, including migration of a generated checkout."""
import importlib.util
import math
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
START = '''<resources>
    <style name="Theme.TMessages.Start">
        <item name="android:windowBackground">{background}</item>
        <item name="android:windowSplashScreenAnimatedIcon">{icon}</item>
        <item name="android:windowSplashScreenAnimationDuration">@integer/splash_screen_duration</item>
        <item name="android:windowSplashScreenBackground">{background}</item>
    </style>
    <style name="OtherTheme">
        <item name="android:windowBackground">#123456</item>
    </style>
</resources>
'''


class StartupResourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('colgram_patcher', ROOT / 'scripts/apply-patches.py')
        cls.patcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.patcher)

    def fixture(self, root):
        main = root / 'TMessagesProj/src/main/res'
        standalone = root / 'TMessagesProj_AppStandalone/src/main/res'
        for res, variant, background, icon in (
                (main, 'values-v31', '?android:windowBackground', '@drawable/tg_splash_320'),
                (main, 'values-night', '#1f2732', '@drawable/colgram_cloud_logo'),
                (standalone, 'values-night-v31', '@android:color/black', '@drawable/colgram_plane_splash')):
            (res / variant).mkdir(parents=True)
            (res / variant / 'styles.xml').write_text(START.format(background=background, icon=icon), encoding='utf-8')
        return main, standalone

    def test_startup_reuses_original_avatar_and_keeps_artwork_inside_android_mask(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            for res in self._apply(repo):
                self.assertEqual((ROOT / 'assets/app_icon.png').read_bytes(),
                                 (res / 'drawable-nodpi/colgram_app_avatar.png').read_bytes())
                wrapper = ET.parse(res / 'drawable/colgram_startup_avatar.xml').getroot()
                self.assertEqual('inset', wrapper.tag)
                self.assertEqual('@drawable/colgram_app_avatar', wrapper.attrib[ANDROID + 'drawable'])
                default = wrapper.attrib.get(ANDROID + 'inset', '0%')
                insets = [float(wrapper.attrib.get(ANDROID + key, default).removesuffix('%')) / 100 for key in
                          ('insetLeft', 'insetRight', 'insetTop', 'insetBottom')]
                self.assertTrue(all(value == insets[0] for value in insets), insets)
                self.assertLessEqual(math.sqrt(2) * 288 * (1 - 2 * insets[0]), 192)
                self.assertTrue((res / 'drawable/colgram_intro_cloud.xml').is_file())
                self.assertTrue((res / 'drawable/colgram_cloud_logo.xml').is_file())

    def _apply(self, repo):
        variants = self.fixture(repo)
        self.patcher.apply_custom_app_icon(str(repo), str(ROOT / 'assets/app_icon.png'))
        return variants

    def test_splash_style_migrates_old_icons_without_changing_application_background(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            variants = self._apply(repo)
            for res in variants:
                for style in res.glob('values*/styles.xml'):
                    parsed = ET.parse(style).getroot()
                    launch = parsed.find('./style[@name="Theme.TMessages.Start"]')
                    self.assertEqual('@drawable/colgram_startup_avatar',
                                     launch.find('./item[@name="android:windowSplashScreenAnimatedIcon"]').text)
                    self.assertEqual('@android:color/black',
                                     launch.find('./item[@name="android:windowSplashScreenBackground"]').text)
                    source_background = {'values-v31': '?android:windowBackground', 'values-night': '#1f2732',
                                         'values-night-v31': '@android:color/black'}[style.parent.name]
                    self.assertEqual(source_background, launch.find('./item[@name="android:windowBackground"]').text)
                    self.assertEqual('#123456', parsed.find('./style[@name="OtherTheme"]/item').text)
            before = [p.read_bytes() for res in variants for p in sorted(res.glob('values*/styles.xml'))]
            self.patcher.apply_custom_app_icon(str(repo), str(ROOT / 'assets/app_icon.png'))
            self.assertEqual(before, [p.read_bytes() for res in variants for p in sorted(res.glob('values*/styles.xml'))])


if __name__ == '__main__':
    unittest.main()
