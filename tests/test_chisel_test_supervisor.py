import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

from tools.chisel.chisel_test_supervisor import (
    SupervisorConfig,
    parse_non_negative_int,
    parse_positive_int,
    snapshot_artifacts,
    supervise,
)


class ChiselTestSupervisorTest(unittest.TestCase):
    def run_supervised(self, config: SupervisorConfig, program: str, *args: str):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = supervise(config, [sys.executable, "-c", program, *args])
        return status, output.getvalue()

    def test_positive_and_non_negative_values_reject_invalid_text(self):
        self.assertEqual(parse_positive_int("heartbeat", "3"), 3)
        self.assertEqual(parse_non_negative_int("stall", "0"), 0)
        for text in ("", "0", "-1", "1.5", "one"):
            with self.subTest(kind="positive", text=text):
                with self.assertRaisesRegex(ValueError, "heartbeat"):
                    parse_positive_int("heartbeat", text)
        for text in ("", "-1", "1.5", "one"):
            with self.subTest(kind="non-negative", text=text):
                with self.assertRaisesRegex(ValueError, "stall"):
                    parse_non_negative_int("stall", text)

    def test_snapshot_artifacts_reports_total_and_largest_files(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "small.bin").write_bytes(b"a" * 3)
            nested = root / "nested"
            nested.mkdir()
            (nested / "large.bin").write_bytes(b"b" * 11)

            snapshot = snapshot_artifacts(root)

            self.assertEqual(snapshot.bytes, 14)
            self.assertEqual(snapshot.file_count, 2)
            self.assertEqual(snapshot.largest[0], ("nested/large.bin", 11))

    def test_output_progress_prevents_idle_stall(self):
        program = textwrap.dedent(
            """
            import time
            for index in range(6):
                print(f"tick={index}", flush=True)
                time.sleep(0.25)
            """
        )
        status, output = self.run_supervised(
            SupervisorConfig(heartbeat_seconds=1, stall_seconds=1), program)

        self.assertEqual(status, 0)
        self.assertIn("tick=5", output)
        self.assertNotIn('"reason": "idle-stall"', output)

    def test_artifact_progress_prevents_idle_stall(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact = Path(temp_dir) / "progress.bin"
            program = textwrap.dedent(
                """
                from pathlib import Path
                import sys
                import time
                target = Path(sys.argv[1])
                for _ in range(6):
                    with target.open("ab") as stream:
                        stream.write(b"x")
                        stream.flush()
                    time.sleep(0.25)
                """
            )
            status, output = self.run_supervised(
                SupervisorConfig(
                    heartbeat_seconds=1,
                    stall_seconds=1,
                    artifact_root=Path(temp_dir)),
                program,
                str(artifact),
            )

            self.assertEqual(status, 0)
            self.assertEqual(artifact.stat().st_size, 6)
            self.assertNotIn('"reason": "idle-stall"', output)

    def test_cpu_active_silent_child_is_not_called_stalled(self):
        program = textwrap.dedent(
            """
            import time
            deadline = time.monotonic() + 1.5
            value = 1
            while time.monotonic() < deadline:
                value = (value * 17 + 3) % 1000003
            """
        )
        status, output = self.run_supervised(
            SupervisorConfig(heartbeat_seconds=1, stall_seconds=1), program)

        self.assertEqual(status, 0)
        self.assertNotIn('"reason": "idle-stall"', output)

    def test_silent_idle_child_exits_124_with_diagnostics(self):
        status, output = self.run_supervised(
            SupervisorConfig(heartbeat_seconds=1, stall_seconds=1),
            "import time; time.sleep(10)",
        )

        self.assertEqual(status, 124)
        self.assertIn('"reason": "idle-stall"', output)
        self.assertIn("linx-chisel-summary ", output)
        self.assertIn('"phase":', output)

    def test_child_failure_status_is_preserved(self):
        status, output = self.run_supervised(
            SupervisorConfig(heartbeat_seconds=1, stall_seconds=0),
            "raise SystemExit(7)",
        )

        self.assertEqual(status, 7)
        self.assertIn('"child_status": 7', output)

    def test_artifact_budget_names_largest_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            artifact = Path(temp_dir) / "oversized.bin"
            program = "from pathlib import Path; import sys; Path(sys.argv[1]).write_bytes(b'x' * 64)"
            status, output = self.run_supervised(
                SupervisorConfig(
                    heartbeat_seconds=1,
                    stall_seconds=0,
                    artifact_root=Path(temp_dir),
                    artifact_budget_bytes=32),
                program,
                str(artifact),
            )

            self.assertEqual(status, 2)
            self.assertIn('"reason": "artifact-budget"', output)
            self.assertIn("oversized.bin", output)
            self.assertIn('"artifact_bytes": 64', output)

    def test_interrupt_reaches_the_child_process_group(self):
        supervisor = (
            Path(__file__).resolve().parents[1]
            / "tools"
            / "chisel"
            / "chisel_test_supervisor.py"
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            child = root / "child.py"
            started = root / "started"
            terminated = root / "terminated"
            child.write_text(
                textwrap.dedent(
                    """
                    from pathlib import Path
                    import signal
                    import sys
                    import time

                    started = Path(sys.argv[1])
                    terminated = Path(sys.argv[2])

                    def stop(signum, frame):
                        del frame
                        terminated.write_text(str(signum), encoding="utf-8")
                        raise SystemExit(0)

                    signal.signal(signal.SIGTERM, stop)
                    started.write_text("ready", encoding="utf-8")
                    while True:
                        time.sleep(0.1)
                    """
                ),
                encoding="utf-8",
            )
            process = subprocess.Popen(
                [
                    sys.executable,
                    str(supervisor),
                    "--heartbeat-seconds",
                    "1",
                    "--stall-seconds",
                    "0",
                    "--",
                    sys.executable,
                    str(child),
                    str(started),
                    str(terminated),
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            try:
                deadline = time.monotonic() + 5
                while not started.exists() and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.assertTrue(started.exists(), "child did not start")

                process.terminate()
                output, _ = process.communicate(timeout=8)

                self.assertEqual(process.returncode, 143, output)
                self.assertTrue(terminated.exists(), output)
                self.assertEqual(terminated.read_text(encoding="utf-8"), "15")
                summary = next(
                    line.removeprefix("linx-chisel-summary ")
                    for line in output.splitlines()
                    if line.startswith("linx-chisel-summary ")
                )
                self.assertEqual(json.loads(summary)["reason"], "signal")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    unittest.main()
