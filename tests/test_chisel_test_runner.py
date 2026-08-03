import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import textwrap
import unittest


class ChiselTestRunnerTest(unittest.TestCase):
    repo_root = Path(__file__).resolve().parents[1]
    runner = (
        repo_root
        / "tools"
        / "chisel"
        / "run_chisel_tests.sh"
    )

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.capture = self.root / "sbt.json"
        fake_sbt = self.bin_dir / "sbt"
        fake_sbt.write_text(
            textwrap.dedent(
                """
                #!/usr/bin/env python3
                import json
                import os
                from pathlib import Path
                import sys

                Path(os.environ["FAKE_SBT_CAPTURE"]).write_text(
                    json.dumps({
                        "argv": sys.argv[1:],
                        "jobs": os.environ.get("LINX_CHISEL_TEST_JOBS"),
                    }),
                    encoding="utf-8",
                )
                raise SystemExit(int(os.environ.get("FAKE_SBT_STATUS", "0")))
                """
            ).lstrip(),
            encoding="utf-8",
        )
        fake_sbt.chmod(fake_sbt.stat().st_mode | stat.S_IXUSR)

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_runner(self, *args: str, extra_env: dict[str, str] | None = None):
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{self.bin_dir}{os.pathsep}{env['PATH']}",
                "FAKE_SBT_CAPTURE": str(self.capture),
                "LINX_CHISEL_ARTIFACT_ROOT": str(self.root / "artifacts"),
                "LINX_CHISEL_HEARTBEAT_SECONDS": "1",
            }
        )
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            ["bash", str(self.runner), *args],
            cwd=self.runner.parents[2],
            env=env,
            capture_output=True,
            text=True,
        )

    def read_capture(self):
        return json.loads(self.capture.read_text(encoding="utf-8"))

    def test_repeated_only_selectors_form_one_test_only_command(self):
        result = self.run_runner("--only", "FooSpec", "--only", "BarSpec")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        captured = self.read_capture()
        self.assertEqual(captured["argv"][-1], "testOnly *FooSpec* *BarSpec*")
        self.assertEqual(captured["argv"].count("--server"), 1)

    def test_all_and_only_are_mutually_exclusive(self):
        result = self.run_runner("--all", "--only", "FooSpec")

        self.assertEqual(result.returncode, 2)
        self.assertIn("mutually exclusive", result.stderr)
        self.assertFalse(self.capture.exists())

    def test_default_jobs_is_one(self):
        result = self.run_runner("--only", "FooSpec")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read_capture()["jobs"], "1")
        summary = next(
            line.removeprefix("linx-chisel-summary ")
            for line in result.stdout.splitlines()
            if line.startswith("linx-chisel-summary ")
        )
        self.assertEqual(json.loads(summary)["jobs"], 1)

    def test_ooo_integration_uses_bounded_simulation_profiles(self):
        source = (
            self.repo_root
            / "chisel"
            / "src"
            / "test"
            / "scala"
            / "linxcore"
            / "ooo"
            / "OOOIntegrationSpec.scala"
        ).read_text(encoding="utf-8")

        self.assertIn("SimulationParamProfiles", source)
        self.assertNotIn("{ParamProfiles,", source)
        self.assertNotIn("val base = ParamProfiles.", source)

    def test_cli_jobs_overrides_environment(self):
        result = self.run_runner(
            "--only",
            "FooSpec",
            "--jobs",
            "3",
            extra_env={"LINX_CHISEL_TEST_JOBS": "5"},
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.read_capture()["jobs"], "3")

    def test_zero_stall_disables_idle_termination(self):
        result = self.run_runner("--only", "FooSpec", "--stall-seconds", "0")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.capture.exists())

    def test_invalid_numeric_options_fail_before_sbt(self):
        cases = (
            ("--heartbeat-seconds", "0"),
            ("--stall-seconds", "-1"),
            ("--wall-seconds", "-1"),
            ("--jobs", "0"),
            ("--artifact-budget-bytes", "-1"),
        )
        for option, value in cases:
            with self.subTest(option=option, value=value):
                self.capture.unlink(missing_ok=True)
                result = self.run_runner("--only", "FooSpec", option, value)
                self.assertEqual(result.returncode, 2)
                self.assertIn("error:", result.stderr)
                self.assertFalse(self.capture.exists())

    def test_fake_sbt_failure_status_is_preserved(self):
        result = self.run_runner(
            "--only", "FooSpec", extra_env={"FAKE_SBT_STATUS": "7"})

        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        self.assertEqual(self.read_capture()["argv"][-1], "testOnly *FooSpec*")


if __name__ == "__main__":
    unittest.main()
