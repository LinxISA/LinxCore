from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/chisel/run_top_natural.sh"
TB = ROOT / "tools/chisel/top_natural_tb.cpp"


class TopNaturalHarnessTest(unittest.TestCase):
    def test_self_test_records_harness_ownership_without_an_oracle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            result = subprocess.run(
                [str(RUNNER), "--self-test", "--manifest", str(manifest)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            payload = json.loads(manifest.read_text(encoding="utf-8"))

        self.assertEqual(payload["schema"], 1)
        self.assertEqual(payload["top"], "TOP")
        self.assertEqual(payload["task"], 18)
        self.assertEqual(
            payload["harness_owns"],
            ["elf", "memory", "uart", "finisher", "manifest"],
        )
        self.assertFalse(payload["instruction_oracle"])
        self.assertFalse(payload["commit_oracle"])
        self.assertEqual(payload["memory_size_encoding"]["Bytes64"], 6)

    def test_cpp_harness_has_no_replay_or_expected_commit_input(self) -> None:
        source = TB.read_text(encoding="utf-8")
        forbidden = (
            "expected_commit",
            "expected_instruction",
            "replay_rows",
            "commit_replay",
            "instruction_replay",
        )
        for token in forbidden:
            self.assertNotIn(token, source.lower())

        self.assertIn("io_instructionMemoryRequest_ready", source)
        for lane in range(4):
            self.assertIn(
                f'"io_dataMemoryRequest_" + std::to_string(lane)', source
            )
        self.assertIn("kUartAddress", source)
        self.assertIn("kFinisherAddress", source)
        self.assertIn('\\"activation\\"', source)
        self.assertIn("top-vpi-port-validation=pass", source)


if __name__ == "__main__":
    unittest.main()
