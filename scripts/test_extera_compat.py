#!/usr/bin/env python3
"""Offline compatibility tests for colgram-core/src/main/assets/plugins/extera_compat.py."""

import importlib.util
import json
import os
import sys
import tempfile
import types
import unittest


COMPAT_PATH = os.path.abspath(
    os.path.join(
        os.path.dirname(__file__),
        "..",
        "colgram-core",
        "src",
        "main",
        "assets",
        "plugins",
        "extera_compat.py",
    )
)


def _load_compat():
    spec = importlib.util.spec_from_file_location("exteraPlugins", COMPAT_PATH)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["exteraPlugins"] = mod
    spec.loader.exec_module(mod)
    return mod


class ExteraCompatTest(unittest.TestCase):
    def setUp(self):
        self.compat = _load_compat()

    def test_imports_and_markdown_and_settings_roundtrip(self):
        from base_plugin import BasePlugin, MenuItemData, MenuItemType, HookResult, HookStrategy
        from ui.settings import Header, Switch, Input, Selector, Text, Divider
        from ui.bulletin import BulletinHelper
        import client_utils
        import markdown_utils

        parsed = markdown_utils.parse_markdown("Hi **world** and `code` ||secret||")
        self.assertEqual(parsed.text, "Hi world and code secret")
        self.assertEqual([e["type"] for e in parsed.entities], ["bold", "code", "spoiler"])

        sent = []
        self.compat.set_bridge(lambda did, txt: sent.append((did, txt)), lambda *a: None, lambda *a: None)
        client_utils.send_message({"peer": 12345, "message": "hello from dict"})
        self.assertEqual(sent, [(12345, "hello from dict")])

        with tempfile.TemporaryDirectory() as tmp:
            import builtins
            builtins._colgram_plugins_dir = tmp
            plugin_file = os.path.join(tmp, "sample_extera_test_mod.py")
            with open(plugin_file, "w", encoding="utf-8") as fh:
                fh.write(
                    "from base_plugin import BasePlugin\n"
                    "from ui.settings import Header, Switch, Input, Selector\n"
                    "__id__ = 'sample_extera'\n"
                    "EVENTS = []\n"
                    "class SamplePlugin(BasePlugin):\n"
                    "    def on_plugin_load(self):\n"
                    "        EVENTS.append('load')\n"
                    "    def on_plugin_unload(self):\n"
                    "        EVENTS.append('unload')\n"
                    "    def create_settings(self):\n"
                    "        return [\n"
                    "            Header('Main'),\n"
                    "            Switch('enabled', 'Enable', default=True),\n"
                    "            Input('prefix', 'Prefix', default='.'),\n"
                    "            Selector('mode', 'Mode', items=['Fast', 'Safe'], default=0),\n"
                    "        ]\n"
                )
            sys.path.insert(0, tmp)
            try:
                summary = self.compat.sync_all_plugins(["sample_extera_test_mod"])
                self.assertIn("sample_extera_test_mod=loaded:SamplePlugin", summary)
                mod = sys.modules["sample_extera_test_mod"]
                self.assertEqual(mod.EVENTS, ["load"])

                schema = json.loads(self.compat.get_plugin_settings_json("sample_extera_test_mod"))
                self.assertEqual(len(schema), 4)
                self.assertEqual(schema[1]["value"], True)

                self.assertTrue(
                    self.compat.apply_plugin_setting_json("sample_extera_test_mod", "prefix", json.dumps("!"))
                )
                schema2 = json.loads(self.compat.get_plugin_settings_json("sample_extera_test_mod"))
                self.assertEqual(schema2[2]["value"], "!")

                # Empty enabled list must invoke on_plugin_unload()
                self.compat.sync_all_plugins([])
                self.assertEqual(mod.EVENTS, ["load", "unload"])
            finally:
                if tmp in sys.path:
                    sys.path.remove(tmp)
                sys.modules.pop("sample_extera_test_mod", None)
                if hasattr(builtins, "_colgram_plugins_dir"):
                    delattr(builtins, "_colgram_plugins_dir")

        # Verify unused symbols don't fail
        self.assertEqual(MenuItemType.MESSAGE_CONTEXT_MENU, "MESSAGE_CONTEXT_MENU")
        self.assertEqual(HookStrategy.CANCEL, "CANCEL")
        BulletinHelper.show_info("ok")

    def test_extended_plugin_and_telegram_version_compat(self):
        from base_plugin import AppEvent, XposedHook, MethodReplacement
        from ui.settings import Category, Slider, Action
        from plugins_controller import PluginsController
        import hook_utils
        import android_utils
        import client_utils

        self.assertEqual(AppEvent.RESUME, "RESUME")
        self.assertIsNotNone(XposedHook)
        self.assertIsNotNone(MethodReplacement)
        self.assertEqual(Category("Sec").to_dict()["type"], "header")
        self.assertEqual(Slider("speed", "Speed", default=50, min_value=0, max_value=100).to_dict()["value"], 50)
        self.assertEqual(Action("Run").to_dict()["type"], "action")
        self.assertEqual(
            hook_utils.resolve_class_alias("org.telegram.tgnet.TLRPC$TL_stories"),
            "org.telegram.tgnet.tl.TL_stories",
        )
        self.assertIs(hook_utils.find_class("com.exteragram.messenger.plugins.PluginsController"), PluginsController)

        class Dummy:
            def greet(self, who):
                return "hi " + who

        self.assertEqual(hook_utils.call_method(Dummy(), "greet", "koliti"), "hi koliti")
        self.assertGreater(android_utils.dp(10), 0)
        self.assertIsNotNone(client_utils.PLUGINS_MANAGER)
        self.assertEqual(client_utils.get_current_account(), 0)


if __name__ == "__main__":
    unittest.main()
