#!/usr/bin/env python3
"""Unit tests for scripts/update_upstream.py."""

import os
import tempfile
import unittest

import update_upstream


class UpdateUpstreamTest(unittest.TestCase):
    def test_parse_semver_tuple(self):
        self.assertEqual(update_upstream.parse_semver_tuple("release-12.10.6_5900"), (12, 10, 6))
        self.assertEqual(
            update_upstream.parse_semver_tuple("12.10.6-colgram.mumu-call-routes-20261114"),
            (12, 10, 6),
        )
        self.assertEqual(update_upstream.parse_semver_tuple("v12.11.0"), (12, 11, 0))
        self.assertEqual(update_upstream.parse_semver_tuple(""), ())

    def test_is_upstream_newer(self):
        self.assertTrue(
            update_upstream.is_upstream_newer(
                "12.10.6-colgram.mumu-call-routes-20261114", "release-12.11.0_6000"
            )
        )
        self.assertFalse(
            update_upstream.is_upstream_newer(
                "12.10.6-colgram.mumu-call-routes-20261114", "release-12.10.6_5900"
            )
        )
        self.assertFalse(update_upstream.is_upstream_newer("12.10.6", "release-12.9.9_5800"))
        self.assertFalse(update_upstream.is_upstream_newer("12.10.1", "12.10"))

    def test_read_local_version_and_offline_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            props = os.path.join(tmp, "gradle.properties")
            with open(props, "w", encoding="utf-8") as f:
                f.write("APP_VERSION_CODE=7112\nAPP_VERSION_NAME=12.10.6-colgram.test\n")
            self.assertEqual(update_upstream.read_local_version(tmp), "12.10.6-colgram.test")
            report = update_upstream.inspect_upstream_status(
                repo_root=tmp,
                telegram_dir=tmp,
                override_remote_tag="release-12.11.0_6001",
                check_syntax=False,
            )
            self.assertTrue(report["update_available"])
            self.assertEqual(report["remote_semver"], [12, 11, 0])
            self.assertIn("release-12.11.0_6001", report["recommended_ci_command"])
            self.assertIn("-f telegram_version=release-12.11.0_6001", report["recommended_ci_command"])

    def test_github_workflow_extracts_real_version_and_runs_gates(self):
        from pathlib import Path
        wf_path = Path(__file__).resolve().parent.parent / ".github/workflows/build-colgram.yml"
        wf = wf_path.read_text(encoding="utf-8")
        self.assertIn("telegram_version:", wf)
        self.assertIn("APP_VERSION_NAME=", wf)
        self.assertIn("verify-privacy.py", wf)
        self.assertIn("host_verify.py --jvm-tests", wf)
        self.assertIn("--notes-file /tmp/release_notes.md", wf)
        self.assertIn("SHA256SUMS.txt", wf)


if __name__ == "__main__":
    unittest.main()
