import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("colgram_patcher", Path(__file__).with_name("apply-patches.py"))
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


class BuildPackagesTest(unittest.TestCase):
    def data(self):
        return {"client": [{"client_info": {"android_client_info": {"package_name": "org.telegram.messenger"}, "mobilesdk_app_id": "local-build-fixture"}}]}

    def test_release_and_debug_packages_are_present(self):
        data = self.data()
        self.assertTrue(patcher.ensure_build_package_clients(data))
        packages = {x["client_info"]["android_client_info"]["package_name"] for x in data["client"]}
        self.assertTrue({"org.colgram.messenger", "org.colgram.messenger.web"}.issubset(packages))
        self.assertEqual("org.telegram.messenger", data["client"][0]["client_info"]["android_client_info"]["package_name"])

    def test_repeated_patching_is_idempotent(self):
        data = self.data()
        patcher.ensure_build_package_clients(data)
        self.assertFalse(patcher.ensure_build_package_clients(data))
        self.assertEqual(3, len(data["client"]))

    def test_existing_debug_client_is_not_replaced(self):
        data = self.data()
        data["client"].append({"client_info": {"android_client_info": {"package_name": "org.colgram.messenger.web"}, "mobilesdk_app_id": "keep-this-build-value"}})
        patcher.ensure_build_package_clients(data)
        self.assertEqual("keep-this-build-value", data["client"][1]["client_info"]["mobilesdk_app_id"])

    def test_missing_template_is_a_build_error(self):
        with self.assertRaises(ValueError): patcher.ensure_build_package_clients({"client": []})

    def test_masque_guard_precedes_native_and_full_startup(self):
        source = "public void onCreate() {\n applicationContext = this;\n super.onCreate();\n NativeLoader.initNativeLibs(this);\n ColgramHookHandler.init(this);\n}"
        result = patcher.isolate_masque_process(source)
        self.assertLess(result.index("ColgramProcess.isMasqueProcess(this)"), result.index("NativeLoader.initNativeLibs"))
        self.assertEqual(result, patcher.isolate_masque_process(result))

    def test_debug_overlay_label_matches_branded_base(self):
        source = '<manifest><application android:label="@string/AppName" android:theme="@style/App" /></manifest>'
        fixed = patcher.normalize_variant_application_label(source)
        self.assertIn('android:label="Colgram"', fixed)
        self.assertIn('android:theme="@style/App"', fixed)
        self.assertEqual(fixed, patcher.normalize_variant_application_label(fixed))

    def test_disabled_measurement_declarations_are_removed_from_overlay(self):
        source = '<manifest><application><receiver android:name="com.google.android.gms.measurement.AppMeasurementReceiver" android:enabled="false"><intent-filter /></receiver></application></manifest>'
        fixed = patcher.remove_measurement_components(source)
        self.assertIn('tools:node="remove"', fixed)
        self.assertNotIn('android:enabled="false"', fixed)
        self.assertEqual(fixed, patcher.remove_measurement_components(fixed))

    def test_device_tests_compile_against_the_real_colgram_modules(self):
        source = "dependencies {\n implementation project(':TMessagesProj')\n}\n"
        fixed = patcher.ensure_device_test_dependencies(source)
        self.assertIn("androidTestImplementation project(':colgram-core')", fixed)
        self.assertIn("androidTestImplementation project(':colgram-singbox')", fixed)
        self.assertEqual(fixed, patcher.ensure_device_test_dependencies(fixed))


if __name__ == "__main__": unittest.main()
