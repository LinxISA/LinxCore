import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EVALUATOR = ROOT / "tools" / "chisel" / "evaluate_natural_benchmark_ipc.py"
GATE = ROOT / "tools" / "chisel" / "run_dual_benchmark_gate.sh"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class NaturalBenchmarkIpcEvaluatorTests(unittest.TestCase):
    def write_manifest(
        self,
        path: Path,
        *,
        terminal_status: str = "finisher_pass",
        finisher_pass: bool = True,
        cycles: int = 100,
        commits: int = 195,
        reset_sp: int = 0x7FEFFF0,
    ) -> None:
        path.write_text(
            json.dumps(
                {
                    "schema": "linxcore.benchmark_autonomous_natural.v1",
                    "terminal_status": terminal_status,
                    "cycles": cycles,
                    "commits": commits,
                    "reset_sp": reset_sp,
                    "finisher_pass": finisher_pass,
                }
            )
            + "\n",
            encoding="utf-8",
        )

    def run_evaluator(
        self,
        directory: Path,
        *,
        coremark_manifest: Path,
        dhrystone_manifest: Path,
        coremark_elf: Path,
        dhrystone_elf: Path,
        expected_coremark_sha256: str | None = None,
        expected_dhrystone_sha256: str | None = None,
        expected_runner_sha256: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(EVALUATOR),
                "--coremark-manifest",
                str(coremark_manifest),
                "--dhrystone-manifest",
                str(dhrystone_manifest),
                "--coremark-elf",
                str(coremark_elf),
                "--dhrystone-elf",
                str(dhrystone_elf),
                "--expected-coremark-sha256",
                expected_coremark_sha256 or sha256(coremark_elf),
                "--expected-dhrystone-sha256",
                expected_dhrystone_sha256 or sha256(dhrystone_elf),
                "--runner",
                str(GATE),
                "--expected-runner-sha256",
                expected_runner_sha256 or sha256(GATE),
                "--target-ipc",
                "1.90",
                "--output",
                str(directory / "summary.json"),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def test_pass_requires_both_correct_finishes_and_target_ipc(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            coremark_elf = directory / "coremark.elf"
            dhrystone_elf = directory / "dhrystone.elf"
            coremark_elf.write_bytes(b"coremark")
            dhrystone_elf.write_bytes(b"dhrystone")
            coremark_manifest = directory / "coremark.json"
            dhrystone_manifest = directory / "dhrystone.json"
            self.write_manifest(coremark_manifest, cycles=200, commits=390)
            self.write_manifest(dhrystone_manifest, cycles=100, commits=191)

            result = self.run_evaluator(
                directory,
                coremark_manifest=coremark_manifest,
                dhrystone_manifest=dhrystone_manifest,
                coremark_elf=coremark_elf,
                dhrystone_elf=dhrystone_elf,
            )

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "pass")
            self.assertEqual(summary["schema"], "linxcore.dual_natural_benchmark_ipc.v1")
            self.assertEqual([row["ipc"] for row in summary["workloads"]], [1.95, 1.91])

    def test_timeout_and_subtarget_ipc_fail_even_with_positive_commits(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            coremark_elf = directory / "coremark.elf"
            dhrystone_elf = directory / "dhrystone.elf"
            coremark_elf.write_bytes(b"coremark")
            dhrystone_elf.write_bytes(b"dhrystone")
            coremark_manifest = directory / "coremark.json"
            dhrystone_manifest = directory / "dhrystone.json"
            self.write_manifest(
                coremark_manifest,
                terminal_status="timeout",
                finisher_pass=False,
                cycles=1000,
                commits=2500,
            )
            self.write_manifest(dhrystone_manifest, cycles=1000, commits=1899)

            result = self.run_evaluator(
                directory,
                coremark_manifest=coremark_manifest,
                dhrystone_manifest=dhrystone_manifest,
                coremark_elf=coremark_elf,
                dhrystone_elf=dhrystone_elf,
            )

            self.assertEqual(result.returncode, 1)
            summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "fail")
            self.assertTrue(any("terminal_status" in error for error in summary["errors"]))
            self.assertTrue(any("IPC" in error for error in summary["errors"]))

    def test_elf_hash_and_reset_sp_are_hard_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            coremark_elf = directory / "coremark.elf"
            dhrystone_elf = directory / "dhrystone.elf"
            coremark_elf.write_bytes(b"coremark")
            dhrystone_elf.write_bytes(b"dhrystone")
            coremark_manifest = directory / "coremark.json"
            dhrystone_manifest = directory / "dhrystone.json"
            self.write_manifest(coremark_manifest, reset_sp=0x38970)
            self.write_manifest(dhrystone_manifest)

            result = self.run_evaluator(
                directory,
                coremark_manifest=coremark_manifest,
                dhrystone_manifest=dhrystone_manifest,
                coremark_elf=coremark_elf,
                dhrystone_elf=dhrystone_elf,
                expected_coremark_sha256="0" * 64,
            )

            self.assertEqual(result.returncode, 1)
            summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
            self.assertTrue(any("ELF SHA-256 drift" in error for error in summary["errors"]))
            self.assertTrue(any("reset_sp" in error for error in summary["errors"]))

    def test_runner_hash_drift_is_a_hard_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            coremark_elf = directory / "coremark.elf"
            dhrystone_elf = directory / "dhrystone.elf"
            coremark_elf.write_bytes(b"coremark")
            dhrystone_elf.write_bytes(b"dhrystone")
            coremark_manifest = directory / "coremark.json"
            dhrystone_manifest = directory / "dhrystone.json"
            self.write_manifest(coremark_manifest)
            self.write_manifest(dhrystone_manifest)

            result = self.run_evaluator(
                directory,
                coremark_manifest=coremark_manifest,
                dhrystone_manifest=dhrystone_manifest,
                coremark_elf=coremark_elf,
                dhrystone_elf=dhrystone_elf,
                expected_runner_sha256="0" * 64,
            )

            self.assertEqual(result.returncode, 1)
            summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["runner"]["sha256"], sha256(GATE))
            self.assertEqual(summary["runner"]["expected_sha256"], "0" * 64)
            self.assertTrue(any("runner SHA-256 drift" in error for error in summary["errors"]))

    def write_fake_runner(self, path: Path, *, status: int, emit_manifest: bool) -> None:
        body = [
            "#!/usr/bin/env bash",
            "set -euo pipefail",
            "build_dir=''",
            "while [[ $# -gt 0 ]]; do",
            "  case \"$1\" in",
            "    --build-dir)",
            "      build_dir=\"$2\"",
            "      shift 2",
            "      ;;",
            "    *)",
            "      shift",
            "      ;;",
            "  esac",
            "done",
            "if [[ -z \"${build_dir}\" ]]; then",
            "  echo 'missing --build-dir' >&2",
            "  exit 64",
            "fi",
        ]
        if emit_manifest:
            body.extend(
                [
                    "mkdir -p \"${build_dir}/report\"",
                    "cat > \"${build_dir}/report/natural_manifest.json\" <<'JSON'",
                    json.dumps(
                        {
                            "schema": "linxcore.benchmark_autonomous_natural.v1",
                            "terminal_status": "finisher_pass",
                            "cycles": 100,
                            "commits": 195,
                            "reset_sp": 0x7FEFFF0,
                            "finisher_pass": True,
                        }
                    ),
                    "JSON",
                ]
            )
        body.append(f"exit {status}")
        path.write_text("\n".join(body) + "\n", encoding="utf-8")
        path.chmod(0o755)

    def run_gate_with_fake_runner(
        self,
        directory: Path,
        *,
        runner_status: int,
        emit_manifest: bool,
    ) -> subprocess.CompletedProcess[str]:
        coremark_elf = directory / "coremark.elf"
        dhrystone_elf = directory / "dhrystone.elf"
        coremark_elf.write_bytes(b"coremark")
        dhrystone_elf.write_bytes(b"dhrystone")
        fake_runner = directory / "fake-natural-runner.sh"
        self.write_fake_runner(fake_runner, status=runner_status, emit_manifest=emit_manifest)
        env = os.environ.copy()
        env.update(
            {
                "LINXCORE_NATURAL_RUNNER": str(fake_runner),
                "LINXCORE_EXPECTED_NATURAL_RUNNER_SHA256": sha256(fake_runner),
                "LINXCORE_EXPECTED_COREMARK_SHA256": sha256(coremark_elf),
                "LINXCORE_EXPECTED_DHRYSTONE_SHA256": sha256(dhrystone_elf),
            }
        )
        return subprocess.run(
            [
                "bash",
                str(GATE),
                "--coremark-elf",
                str(coremark_elf),
                "--dhrystone-elf",
                str(dhrystone_elf),
                "--build-root",
                str(directory / "build"),
                "--max-cycles",
                "1",
            ],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
        )

    def test_gate_uses_one_build_directory_per_workload(self):
        text = GATE.read_text(encoding="utf-8")
        self.assertIn('"${BUILD_ROOT}/${benchmark}"', text)
        self.assertIn('for benchmark in coremark dhrystone', text)

    def test_gate_propagates_runner_failures(self):
        text = GATE.read_text(encoding="utf-8")
        self.assertIn("set -euo pipefail", text)
        self.assertIn('bash "${ROOT_DIR}/tools/chisel/run_top_natural.sh"', text)

    def test_gate_freezes_canonical_inputs_and_natural_only_runner(self):
        text = GATE.read_text(encoding="utf-8")
        self.assertIn("run_top_natural.sh", text)
        self.assertNotIn("--qemu", text)
        self.assertNotIn("--expected-rows", text)


if __name__ == "__main__":
    unittest.main()
