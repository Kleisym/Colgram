"""Regression coverage for the reported +7 country-code focus trap."""
import importlib.util
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LoginPhonePatchesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = ROOT / 'scripts/login_patches.py'
        if not path.is_file():
            raise AssertionError('No focus/keyboard repair transformer exists')
        spec = importlib.util.spec_from_file_location('login_patches', path)
        cls.patch = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.patch)

    def test_complete_known_country_code_advances_even_when_other_regions_share_it(self):
        source = '''public class PhoneView {
                        if (textToSet != null) {
                            phoneField.requestFocus();
                            phoneField.setText(textToSet);
                            phoneField.setSelection(phoneField.length());
                        }
}'''
        result = self.patch.patch_phone_focus(source)
        self.assertIn('codesMap.containsKey(text)', result)
        self.assertIn('codeField.hasFocus()', result)
        self.assertIn('textToSet == null', result)
        self.assertIn('country != null', result)
        self.assertIn('textToSet = phoneField.getText().toString()', result)
        self.assertEqual(1, result.count('phoneField.requestFocus()'))
        self.assertEqual(result, self.patch.patch_phone_focus(result))

    def test_pristine_and_existing_source_anchors_work(self):
        repo = ROOT / 'Telegram-Src'
        path = 'TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java'
        if not (repo / '.git').is_dir():
            self.skipTest('no generated upstream checkout')
        for source in (subprocess.check_output(['git', '-C', str(repo), 'show', 'HEAD:' + path]).decode(),
                       (repo / path).read_text(encoding='utf-8')):
            result = self.patch.patch_phone_focus(source)
            self.assertEqual(result, self.patch.patch_phone_focus(result))
            login = self.patch.patch_phone_form(result)
            self.assertEqual(login, self.patch.patch_phone_form(login))
            phone = login.split('public class PhoneView extends', 1)[1].split('private boolean numberFilled;', 1)[0]
            self.assertIn('return false; // COLGRAM_PHONE_SYSTEM_IME', phone)
            self.assertIn('EditorInfo.IME_ACTION_DONE', phone)
            self.assertIn('attempt != colgramPhoneAttempt', phone)
            self.assertIn('colgramPhoneRequestId = 0;', phone)
            self.assertIn('30_000', phone)
            self.assertIn('cancelColgramPhoneRequest();', phone)
            self.assertNotIn('switchToNextProxy', phone)

    def test_dialog_title_uses_colgram_not_telegram_image_span(self):
        repo = ROOT / 'Telegram-Src'
        path = 'TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java'
        if not (repo / '.git').is_dir():
            self.skipTest('no generated upstream checkout')
        source = subprocess.check_output(['git', '-C', str(repo), 'show', 'HEAD:' + path]).decode()
        result = self.patch.patch_dialogs_wordmark(source)
        self.assertIn('actionBar.setTitle("Colgram", statusDrawable)', result)
        self.assertNotIn('R.drawable.telegram_logo_2', result)
        self.assertEqual(result, self.patch.patch_dialogs_wordmark(result))

    def test_unknown_layout_fails_explicitly(self):
        with self.assertRaises(ValueError):
            self.patch.patch_phone_focus('public class Unknown {}')


if __name__ == '__main__':
    unittest.main()
