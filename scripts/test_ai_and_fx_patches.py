import os
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class AiAndDynamicUiPatchTest(unittest.TestCase):
    def test_native_llama_libraries_present(self):
        for abi in ("arm64-v8a", "x86_64"):
            so_path = os.path.join(ROOT, "vendor", "colgram-llama", "src", "main", "jniLibs", abi, "libcolgram_llama.so")
            self.assertTrue(os.path.isfile(so_path), f"Missing compiled native engine for {abi}: {so_path}")
            self.assertGreater(os.path.getsize(so_path), 1024 * 1024, f"Unexpectedly small .so for {abi}")

    def test_apply_patches_wires_llama_ai_and_easter_eggs(self):
        with open(os.path.join(ROOT, "scripts", "apply-patches.py"), "r", encoding="utf-8") as fh:
            text = fh.read()
        for required in (
            "def sync_llama_module(",
            "ColgramConfettiView.java",
            "ColgramEasterEggs.java",
            "ColgramAiOrbView.java",
            "ColgramAiChatBridge.java",
            "ColgramAiSheet.java",
            "ColgramAiActivity.java",
            "ColgramNativeAiDeviceTest.java",
            "colgramAiOrb = new ColgramAiOrbView(context);",
            "ColgramEasterEggs.handleDialogsTitleTap",
            "case 108:",
        ):
            self.assertIn(required, text, f"Missing expected patch marker: {required}")


if __name__ == "__main__":
    unittest.main()
