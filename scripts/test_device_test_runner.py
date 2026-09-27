"""The device-test runner decides pass/fail from instrumentation output, so its parser is a
test gate and needs its own tests. A gate that cannot recognise a failure is worse than none.
"""
import importlib.util
import tempfile
import unittest
from pathlib import Path

RUNNER = Path(__file__).resolve().parent / "device-tests.py"

spec = importlib.util.spec_from_file_location("device_tests", RUNNER)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def parse_text(body: str):
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False,
                                     encoding="utf-8") as handle:
        handle.write(body)
        path = Path(handle.name)
    try:
        return runner.parse(path)
    finally:
        path.unlink()


class DeviceTestParserTest(unittest.TestCase):
    def test_a_passing_test_is_reported_as_passing(self):
        tests, clean = parse_text(
            "INSTRUMENTATION_STATUS: test=fineTest\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: test=fineTest\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual([("fineTest", 0)], tests)
        self.assertTrue(clean)

    def test_a_stack_line_makes_the_test_fail_even_when_the_code_is_zero(self):
        tests, _ = parse_text(
            "INSTRUMENTATION_STATUS: test=brokenTest\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: grey text on grey\n"
            "INSTRUMENTATION_STATUS: test=brokenTest\n"
            "INSTRUMENTATION_STATUS_CODE: -2\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual([("brokenTest", -1)], tests)

    def test_negative_status_codes_are_not_skipped_as_non_numeric(self):
        # str.isdigit() is False for "-2", and treating that as "nothing to see here" would
        # report a broken test as a pass - the one failure mode this runner exists to prevent.
        tests, _ = parse_text(
            "INSTRUMENTATION_STATUS: test=brokenTest\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: test=brokenTest\n"
            "INSTRUMENTATION_STATUS_CODE: -2\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual([("brokenTest", -1)], tests)

    def test_an_unrecognised_shape_fails_closed(self):
        tests, _ = parse_text(
            "INSTRUMENTATION_STATUS: test=weirdTest\n"
            "INSTRUMENTATION_STATUS_CODE: 99\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual([("weirdTest", -1)], tests)

    def test_every_test_in_a_multi_test_run_is_reported(self):
        tests, _ = parse_text(
            "INSTRUMENTATION_STATUS: test=aTest\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: test=aTest\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_STATUS: test=bTest\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: test=bTest\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n\nOK (2 tests)\n\nINSTRUMENTATION_CODE: -1\n")
        self.assertEqual([("aTest", 0), ("bTest", 0)], tests)

    def test_the_runner_never_trusts_gradles_exit_code(self):
        source = RUNNER.read_text(encoding="utf-8")
        self.assertNotIn("return proc.returncode", source)
        self.assertIn("Failed to receive the UTP test results", source)


if __name__ == "__main__":
    unittest.main()
