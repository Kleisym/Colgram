import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import subprocess


class NativeBootstrapTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("native_bootstrap", Path(__file__).with_name("native_bootstrap.py"))
        self.assertTrue(Path(spec.origin).is_file(), "build still downloads unpinned current Telegram binaries")
        self.bootstrap = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.bootstrap)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Native Pin Test",
                        "-c", "user.email=native-pin@example.invalid", "commit", "-q",
                        "--allow-empty", "-m", "fixture"], check=True)
        self.commit = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()
        (self.root / "TMessagesProj/src/main/java/org/telegram/tgnet").mkdir(parents=True)
        (self.root / "gradle.properties").write_text("APP_VERSION_NAME=12.10.6-colgram.test\n")
        (self.root / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java").write_text(
            "public static native void native_sendRequest(int account, long object, int flags, int dc, int connection, boolean immediate, int token);")
        self.payload = b"\x7fELF\x02" + b"\x00"*30 + b"native_sendRequest\x00(IJIIIZI)V\x00"
        self.apk = self.root / "pinned.apk"
        with zipfile.ZipFile(self.apk, "w") as z: z.writestr("lib/x86_64/libtmessages.49.so", self.payload)
        self.config = {"telegram_version":"12.10.6","telegram_commit":self.commit,"apk_url":"https://example.invalid/pinned.apk",
            "apk_sha256":hashlib.sha256(self.apk.read_bytes()).hexdigest(),"required_jni_descriptor":"(IJIIIZI)V",
            "entries":{"lib/x86_64/libtmessages.49.so":{"sha256":hashlib.sha256(self.payload).hexdigest(),"bytes":len(self.payload)}}}
        self.manifest = self.root / "native.json"
        self.manifest.write_text(json.dumps(self.config))

    def ensure(self): return self.bootstrap.ensure_native(self.root, self.manifest, self.apk)

    def test_matching_pinned_binary_is_extracted_and_reused(self):
        self.ensure()
        path = self.root / "TMessagesProj/src/main/jniLibs/x86_64/libtmessages.49.so"
        self.assertEqual(self.payload,path.read_bytes())
        self.ensure()
        self.assertEqual(self.payload,path.read_bytes())

    def test_wrong_apk_digest_fails_before_writing(self):
        self.apk.write_bytes(b"wrong version")
        with self.assertRaises(ValueError):self.ensure()
        self.assertFalse((self.root / "TMessagesProj/src/main/jniLibs").exists())

    def test_java_jni_change_is_not_silently_accepted(self):
        path=self.root / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"
        path.write_text(path.read_text().replace("int token)","int token, int newFlag)"))
        with self.assertRaises(ValueError):self.ensure()

    def test_changed_telegram_version_requires_new_native_pin(self):
        (self.root / "gradle.properties").write_text("APP_VERSION_NAME=99.0\n")
        with self.assertRaises(ValueError):self.ensure()

    def test_different_commit_is_rejected_even_with_cached_matching_libraries(self):
        self.ensure()
        self.config["telegram_commit"] = "0" * 40
        self.manifest.write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, "commit"):
            self.ensure()

    def test_wrong_member_digest_does_not_touch_existing_library(self):
        target=self.root / "TMessagesProj/src/main/jniLibs/x86_64/libtmessages.49.so"
        target.parent.mkdir(parents=True);target.write_bytes(b"saved original")
        self.config["entries"]["lib/x86_64/libtmessages.49.so"]["sha256"]="0"*64
        self.manifest.write_text(json.dumps(self.config))
        with self.assertRaises(ValueError):self.ensure()
        self.assertEqual(b"saved original",target.read_bytes())


if __name__ == "__main__":unittest.main()
