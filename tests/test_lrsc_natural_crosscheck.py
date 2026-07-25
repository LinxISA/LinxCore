import json
import tempfile
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
