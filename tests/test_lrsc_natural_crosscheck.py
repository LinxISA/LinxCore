import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.chisel import lrsc_natural_crosscheck as lrsc


class LrScNaturalCrosscheckTest(unittest.TestCase):
    def _write_trace(self, rows):
        td = tempfile.TemporaryDirectory()
        self.addCleanup(td.cleanup)
        trace = Path(td.name) / "trace.jsonl"
        trace.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
        return trace

    def _row(self, pc, insn, **overrides):
        row = {
            "pc": pc,
            "insn": insn,
            "wb_valid": 0,
            "wb_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_wdata": 0,
        }
        row.update(overrides)
        return row

    def _commit_row(self, pc, insn, transaction=(0, 0), **overrides):
        row = {
            "event": "commit",
            "pc": pc,
            "insn": insn,
            "wb_valid": 0,
            "wb_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "transaction": {
                "value": transaction[0],
                "generation": transaction[1],
            },
        }
        row.update(overrides)
        return row

    def _memory_request(self, transaction, data, **overrides):
        row = {
            "event": "memory_request",
            "command": "Write",
            "transaction": {
                "value": transaction[0],
                "generation": transaction[1],
            },
            "address": lrsc.DATA_BASE,
            "data": data,
            "mask": 0xF,
        }
        row.update(overrides)
        return row

    def test_parser_joins_store_commit_without_data_to_exact_write_request(self):
        transaction = (17, 3)
        rows = [
            self._commit_row(lrsc.PC_LO + 6, lrsc.LR_W_MATCH,
                             wb_valid=1,
                             wb_data=lrsc.EXPECTED_LR_SIGN_EXTENDED),
            self._commit_row(lrsc.PC_LO + 14, lrsc.SC_W_MATCH,
                             wb_valid=1, wb_data=0),
            self._commit_row(lrsc.PC_LO + 18, lrsc.LR_W_MATCH,
                             wb_valid=1, wb_data=lrsc.EXPECTED_STORE_VALUE),
            self._commit_row(lrsc.PC_LO + 22, lrsc.SWI_MATCH,
                             transaction=transaction, mem_valid=1,
                             mem_is_store=1),
            self._commit_row(lrsc.PC_LO + 26, lrsc.SC_W_MATCH,
                             wb_valid=1, wb_data=1),
            self._memory_request(transaction, lrsc.EXPECTED_STORE_VALUE),
        ]

        summary = lrsc.summarize_trace(self._write_trace(rows), "synthetic")

        self.assertEqual(summary.status, "pass")
        self.assertEqual(summary.final_memory, lrsc.EXPECTED_STORE_VALUE)
        self.assertNotIn("mem_wdata", rows[3])

    def _join_window(self, transaction, requests):
        return [
            self._commit_row(lrsc.PC_LO + 6, lrsc.LR_W_MATCH,
                             wb_valid=1,
                             wb_data=lrsc.EXPECTED_LR_SIGN_EXTENDED),
            self._commit_row(lrsc.PC_LO + 14, lrsc.SC_W_MATCH,
                             wb_valid=1, wb_data=0),
            self._commit_row(lrsc.PC_LO + 18, lrsc.LR_W_MATCH,
                             wb_valid=1, wb_data=lrsc.EXPECTED_STORE_VALUE),
            self._commit_row(lrsc.PC_LO + 22, lrsc.SWI_MATCH,
                             transaction=transaction, mem_valid=1,
                             mem_is_store=1),
            self._commit_row(lrsc.PC_LO + 26, lrsc.SC_W_MATCH,
                             wb_valid=1, wb_data=1),
            *requests,
        ]

    def test_parser_rejects_missing_exact_store_request(self):
        summary = lrsc.summarize_trace(
            self._write_trace(self._join_window((17, 3), [])), "synthetic")

        self.assertEqual(summary.status, "fail")
        self.assertTrue(any("missing memory request" in error
                            for error in summary.errors))

    def test_parser_rejects_store_request_with_wrong_generation(self):
        rows = self._join_window(
            (17, 3), [self._memory_request((17, 4), lrsc.EXPECTED_STORE_VALUE)])
        summary = lrsc.summarize_trace(self._write_trace(rows), "synthetic")

        self.assertEqual(summary.status, "fail")
        self.assertTrue(any("generation" in error for error in summary.errors))

    def test_parser_rejects_duplicate_exact_store_requests(self):
        request = self._memory_request((17, 3), lrsc.EXPECTED_STORE_VALUE)
        summary = lrsc.summarize_trace(
            self._write_trace(self._join_window((17, 3), [request, request])),
            "synthetic",
        )

        self.assertEqual(summary.status, "fail")
        self.assertTrue(any("duplicate memory request" in error
                            for error in summary.errors))

    def test_parser_accepts_complete_sail_matching_window(self):
        lr0 = lrsc.LR_W_MATCH | (2 << 7) | (7 << 15)
        sc_success = lrsc.SC_W_MATCH | (4 << 7) | (3 << 15) | (7 << 20)
        lr1 = lrsc.LR_W_MATCH | (5 << 7) | (7 << 15)
        conflict_store = lrsc.SWI_MATCH | (3 << 15) | (7 << 20)
        sc_failure = lrsc.SC_W_MATCH | (6 << 7) | (3 << 15) | (7 << 20)
        trace = self._write_trace(
            [
                self._row(lrsc.PC_LO + 6, lr0, wb_valid=1, wb_data=lrsc.EXPECTED_LR_SIGN_EXTENDED),
                self._row(lrsc.PC_LO + 14, sc_success, wb_valid=1, wb_data=0),
                self._row(lrsc.PC_LO + 18, lr1, wb_valid=1, wb_data=lrsc.EXPECTED_STORE_VALUE),
                self._row(
                    lrsc.PC_LO + 22,
                    conflict_store,
                    mem_valid=1,
                    mem_is_store=1,
                    mem_wdata=lrsc.EXPECTED_STORE_VALUE,
                ),
                self._row(lrsc.PC_LO + 26, sc_failure, wb_valid=1, wb_data=1),
            ]
        )

        summary = lrsc.summarize_trace(trace, "synthetic")

        self.assertEqual(summary.status, "pass")
        self.assertEqual(summary.lr_w, 2)
        self.assertEqual(summary.sc_success, 1)
        self.assertEqual(summary.sc_failure, 1)
        self.assertEqual(summary.final_memory, lrsc.EXPECTED_STORE_VALUE)

    def test_parser_fails_closed_on_qemu_lr_w_zero_extension(self):
        lr0 = lrsc.LR_W_MATCH | (2 << 7) | (7 << 15)
        sc_success = lrsc.SC_W_MATCH | (4 << 7) | (3 << 15) | (7 << 20)
        lr1 = lrsc.LR_W_MATCH | (5 << 7) | (7 << 15)
        conflict_store = lrsc.SWI_MATCH | (3 << 15) | (7 << 20)
        sc_failure = lrsc.SC_W_MATCH | (6 << 7) | (3 << 15) | (7 << 20)
        trace = self._write_trace(
            [
                self._row(lrsc.PC_LO + 6, lr0, wb_valid=1, wb_data=lrsc.EXPECTED_QEMU_ZERO_EXTENDED),
                self._row(lrsc.PC_LO + 14, sc_success, wb_valid=1, wb_data=0),
                self._row(lrsc.PC_LO + 18, lr1, wb_valid=1, wb_data=lrsc.EXPECTED_STORE_VALUE),
                self._row(
                    lrsc.PC_LO + 22,
                    conflict_store,
                    mem_valid=1,
                    mem_is_store=1,
                    mem_wdata=lrsc.EXPECTED_STORE_VALUE,
                ),
                self._row(lrsc.PC_LO + 26, sc_failure, wb_valid=1, wb_data=1),
            ]
        )

        summary = lrsc.summarize_trace(trace, "synthetic")

        self.assertEqual(summary.status, "fail")
        self.assertTrue(any("sign-extension mismatch" in error for error in summary.errors))

    def test_self_test_exercises_success_and_failure_paths(self):
        lrsc.self_test()

    def _fake_natural_runner(self, directory: Path, returncode: int) -> Path:
        runner = directory / "fake-natural-runner.py"
        rows = [
            self._commit_row(lrsc.PC_LO + 6, lrsc.LR_W_MATCH,
                      wb_valid=1, wb_data=lrsc.EXPECTED_LR_SIGN_EXTENDED),
            self._commit_row(lrsc.PC_LO + 14, lrsc.SC_W_MATCH,
                      wb_valid=1, wb_data=0),
            self._commit_row(lrsc.PC_LO + 18, lrsc.LR_W_MATCH,
                      wb_valid=1, wb_data=lrsc.EXPECTED_STORE_VALUE),
            self._commit_row(lrsc.PC_LO + 22, lrsc.SWI_MATCH,
                      transaction=(23, 5),
                      mem_valid=1, mem_is_store=1),
            self._memory_request((23, 5), lrsc.EXPECTED_STORE_VALUE),
            self._commit_row(lrsc.PC_LO + 26, lrsc.SC_W_MATCH,
                      wb_valid=1, wb_data=1),
        ]
        runner.write_text(
            "#!/usr/bin/env python3\n"
            "import json, pathlib, sys\n"
            "args = sys.argv[1:]\n"
            "trace = pathlib.Path(args[args.index('--commit-trace') + 1])\n"
            "manifest = pathlib.Path(args[args.index('--manifest') + 1])\n"
            "trace.parent.mkdir(parents=True, exist_ok=True)\n"
            f"rows = {rows!r}\n"
            "trace.write_text(''.join(json.dumps(row) + '\\n' for row in rows))\n"
            "manifest.parent.mkdir(parents=True, exist_ok=True)\n"
            "manifest.write_text(json.dumps({'terminal_status': 'finisher_pass'}))\n"
            f"raise SystemExit({returncode})\n",
            encoding="utf-8",
        )
        runner.chmod(0o755)
        return runner

    def test_run_chisel_summarizes_observation_only_commit_trace(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            runner = self._fake_natural_runner(directory, 0)
            elf = directory / "workload.elf"
            elf.write_bytes(b"ELF")
            with patch.object(lrsc, "NATURAL_RUNNER", runner):
                meta, summary = lrsc.run_chisel(elf, directory, 100)

        self.assertEqual(meta["returncode"], 0)
        self.assertIn("--commit-trace", meta["command"])
        self.assertEqual(summary.status, "pass")
        self.assertEqual(summary.sc_success, 1)
        self.assertEqual(summary.sc_failure, 1)

    def test_run_chisel_fails_when_natural_runner_fails_even_with_valid_trace(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            runner = self._fake_natural_runner(directory, 7)
            elf = directory / "workload.elf"
            elf.write_bytes(b"ELF")
            with patch.object(lrsc, "NATURAL_RUNNER", runner):
                _, summary = lrsc.run_chisel(elf, directory, 100)

        self.assertEqual(summary.status, "fail")
        self.assertTrue(any("returncode 7" in error for error in summary.errors))


if __name__ == "__main__":
    unittest.main()
