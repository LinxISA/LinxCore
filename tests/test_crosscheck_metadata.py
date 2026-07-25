#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "chisel"))
MODULE_PATH = ROOT / "tools" / "trace" / "crosscheck_qemu_linxcore.py"
SPEC = importlib.util.spec_from_file_location("crosscheck_qemu_linxcore", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
CROSSCHECK = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CROSSCHECK
SPEC.loader.exec_module(CROSSCHECK)

REDUCER_PATH = ROOT / "tools" / "chisel" / "frontend_fetch_rf_alu_qemu_rows.py"
REDUCER_SPEC = importlib.util.spec_from_file_location("frontend_fetch_rf_alu_qemu_rows", REDUCER_PATH)
assert REDUCER_SPEC is not None and REDUCER_SPEC.loader is not None
REDUCER = importlib.util.module_from_spec(REDUCER_SPEC)
sys.modules[REDUCER_SPEC.name] = REDUCER
REDUCER_SPEC.loader.exec_module(REDUCER)


class CrosscheckMetadataTest(unittest.TestCase):
    def _commit_row(self, *, pc: int = 0x1000, next_pc: int = 0x1004) -> dict[str, int]:
        return {
            "pc": pc,
            "insn": 0x00000013,
            "len": 4,
            "next_pc": next_pc,
            "wb_valid": 0,
            "wb_rd": 0,
            "wb_data": 0,
            "src0_valid": 0,
            "src0_reg": 0,
            "src0_data": 0,
            "src1_valid": 0,
            "src1_reg": 0,
            "src1_data": 0,
            "dst_valid": 0,
            "dst_reg": 0,
            "dst_data": 0,
            "mem_valid": 0,
            "mem_is_store": 0,
            "mem_addr": 0,
            "mem_wdata": 0,
            "mem_rdata": 0,
            "mem_size": 0,
            "trap_valid": 0,
            "trap_cause": 0,
            "traparg0": 0,
        }

    def test_common_crosscheck_failure_persists_manifest_and_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            qemu_trace = tmp / "qemu.jsonl"
            dut_trace = tmp / "dut.jsonl"
            report_dir = tmp / "report"
            qemu_trace.write_text(json.dumps(self._commit_row(next_pc=0x1004)) + "\n", encoding="utf-8")
            dut_trace.write_text(json.dumps(self._commit_row(next_pc=0x1008)) + "\n", encoding="utf-8")

            proc = subprocess.run(
                [
                    "bash",
                    str(ROOT / "tools" / "chisel" / "run_chisel_qemu_crosscheck.sh"),
                    "--qemu-trace",
                    str(qemu_trace),
                    "--dut-trace",
                    str(dut_trace),
                    "--report-dir",
                    str(report_dir),
                    "--max-commits",
                    "1",
                    "--mode",
                    "failfast",
                ],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

            self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
            report = json.loads((report_dir / "crosscheck_report.json").read_text(encoding="utf-8"))
            manifest = json.loads((report_dir / "crosscheck_manifest.json").read_text(encoding="utf-8"))
            self.assertGreaterEqual(report["mismatch_count"], 1)
            self.assertEqual(report["first_mismatch"]["field"], "next_pc")
            self.assertEqual(manifest["status"], "fail")
            self.assertEqual(manifest["comparator_status"], 1)
            self.assertEqual(manifest["summary"]["mismatch_count"], report["mismatch_count"])

    def test_frontend_elf_wrapper_shell_syntax(self) -> None:
        proc = subprocess.run(
            [
                "bash",
                "-n",
                str(ROOT / "tools" / "chisel" / "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh"),
            ],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def _write_jsonl(self, path: Path, rows: list[dict]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")

    def _write_frontend_common_bundle(self, build_dir: Path, *, mismatch: bool) -> None:
        traces = build_dir / "traces"
        report_dir = build_dir / "report"
        report_dir.mkdir(parents=True, exist_ok=True)
        qemu_live = traces / "qemu.live.raw.jsonl"
        qemu_preview = traces / "qemu.live.expected.preview.jsonl"
        qemu_ref = traces / "qemu.reference.jsonl"
        dut = traces / "dut.chisel.jsonl"
        norm_qemu = report_dir / "qemu.normalized.jsonl"
        norm_dut = report_dir / "dut.normalized.jsonl"

        qemu_row = self._commit_row(next_pc=0x1004)
        dut_row = self._commit_row(next_pc=0x1008 if mismatch else 0x1004)
        self._write_jsonl(qemu_live, [qemu_row, qemu_row])
        self._write_jsonl(qemu_preview, [qemu_row])
        self._write_jsonl(qemu_ref, [qemu_row])
        self._write_jsonl(dut, [dut_row])
        self._write_jsonl(norm_qemu, [qemu_row])
        self._write_jsonl(norm_dut, [dut_row])

        first_mismatch = None
        mismatch_count = 0
        if mismatch:
            first_mismatch = {
                "seq": 0,
                "field": "next_pc",
                "qemu": 0x1004,
                "dut": 0x1008,
                "qemu_pc": 0x1000,
                "dut_pc": 0x1000,
            }
            mismatch_count = 1

        report = {
            "qemu_rows": 1,
            "dut_rows": 1,
            "compared_rows": 1,
            "mismatch_count": mismatch_count,
            "first_mismatch": first_mismatch,
        }
        (report_dir / "crosscheck_report.json").write_text(
            json.dumps(report, indent=2) + "\n",
            encoding="utf-8",
        )
        (report_dir / "crosscheck_mismatches.json").write_text(
            json.dumps([first_mismatch] if first_mismatch else [], indent=2) + "\n",
            encoding="utf-8",
        )
        (report_dir / "crosscheck_report.md").write_text("# report\n", encoding="utf-8")
        common_manifest = {
            "status": "fail" if mismatch else "pass",
            "inputs": {
                "qemu_trace": str(qemu_ref),
                "dut_trace": str(dut),
            },
            "normalized": {
                "qemu_trace": str(norm_qemu),
                "dut_trace": str(norm_dut),
            },
            "reports": {
                "json": str(report_dir / "crosscheck_report.json"),
                "mismatches": str(report_dir / "crosscheck_mismatches.json"),
                "markdown": str(report_dir / "crosscheck_report.md"),
                "manifest": str(report_dir / "crosscheck_manifest.json"),
            },
        }
        (report_dir / "crosscheck_manifest.json").write_text(
            json.dumps(common_manifest, indent=2) + "\n",
            encoding="utf-8",
        )

    def _run_frontend_finalize_fixture(
        self,
        *,
        mismatch: bool,
        qemu_args: tuple[str, ...] = ("-nographic", "-kernel", "fixture.elf"),
    ) -> tuple[subprocess.CompletedProcess[str], dict, dict]:
        with tempfile.TemporaryDirectory() as tmpdir:
            build_dir = Path(tmpdir) / "frontend-bundle"
            self._write_frontend_common_bundle(build_dir, mismatch=mismatch)
            status = "fail" if mismatch else "pass"
            exit_status = "1" if mismatch else "0"
            proc = subprocess.run(
                [
                    "bash",
                    str(ROOT / "tools" / "chisel" / "run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh"),
                    "--build-dir",
                    str(build_dir),
                    "--elf",
                    str(build_dir / "fixture.elf"),
                    "--qemu-bin",
                    "/bin/true",
                    "--expected-rows",
                    "7",
                    "--capture-rows",
                    "8",
                    "--pc-lo",
                    "0x1000",
                    "--pc-hi",
                    "0x1004",
                    "--allow-block-markers",
                    "--finalize-only",
                    "generated-rtl",
                    status,
                    exit_status,
                    "--",
                    *qemu_args,
                ],
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            report_dir = build_dir / "report"
            report = json.loads((report_dir / "frontend_fetch_rf_alu_qemu_elf_report.json").read_text(encoding="utf-8"))
            manifest = json.loads(
                (report_dir / "frontend_fetch_rf_alu_qemu_elf_manifest.json").read_text(encoding="utf-8")
            )
            leftovers = list(report_dir.glob(".frontend_fetch_rf_alu_qemu_elf_*.tmp"))
            self.assertEqual(leftovers, [])
            return proc, report, manifest

    def test_frontend_finalize_only_pass_bundle_is_self_describing(self) -> None:
        proc, report, manifest = self._run_frontend_finalize_fixture(mismatch=False)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(report["schema"], "linxcore.chisel.frontend_fetch_rf_alu_qemu_elf_report.v2")
        self.assertEqual(manifest["schema"], "linxcore.chisel.frontend_fetch_rf_alu_qemu_elf_manifest.v2")
        self.assertEqual(manifest["status"], "pass")
        self.assertEqual(manifest["counts"]["expected_preview_rows"], 1)
        self.assertEqual(manifest["counts"]["dut_captured_rows"], 1)
        self.assertEqual(manifest["counts"]["compared_rows"], 1)
        self.assertEqual(manifest["mismatch_count"], 0)
        self.assertIsNone(manifest["first_mismatch"])
        self.assertEqual(manifest["selected"]["expected_rows"], 7)
        self.assertEqual(manifest["selected"]["capture_rows"], 8)
        self.assertEqual(manifest["selected"]["qemu_args"], ["-nographic", "-kernel", "fixture.elf"])
        self.assertIn("linxcore", manifest["git"])
        self.assertIn("dirty", manifest["git"]["linxcore"])
        self.assertTrue(any(item["path"].endswith("qemu.live.expected.preview.jsonl") for item in manifest["artifacts"]))
        self.assertTrue(all(item["sha256"] for item in manifest["artifacts"]))
        self.assertEqual(report["proof_boundary"], manifest["proof_boundary"])
        self.assertNotIn("natural full-core execution", manifest["status"])

    def test_frontend_finalize_only_mismatch_preserves_status_and_first_mismatch(self) -> None:
        proc, report, manifest = self._run_frontend_finalize_fixture(mismatch=True)
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        self.assertEqual(report["status"], "fail")
        self.assertEqual(manifest["exit_status"], 1)
        self.assertEqual(manifest["counts"]["qemu_live_raw_rows"], 2)
        self.assertEqual(manifest["counts"]["expected_preview_rows"], 1)
        self.assertEqual(manifest["counts"]["dut_captured_rows"], 1)
        self.assertEqual(manifest["mismatch_count"], 1)
        self.assertEqual(manifest["first_mismatch"]["field"], "next_pc")
        self.assertEqual(report["first_mismatch"], manifest["first_mismatch"])
        self.assertTrue(any(item["path"].endswith("crosscheck_report.json") for item in manifest["artifacts"]))

    def test_frontend_finalize_only_accepts_empty_qemu_args(self) -> None:
        proc, _, manifest = self._run_frontend_finalize_fixture(mismatch=False, qemu_args=())
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(manifest["selected"]["qemu_args"], [])

    def test_catalog_block_boundaries_are_metadata(self) -> None:
        self.assertTrue(CROSSCHECK._is_bstart32(0x00000391))
        self.assertTrue(CROSSCHECK._is_bstart32(0x000003A1))
        self.assertTrue(CROSSCHECK._is_bstart32(0x00002001))
        self.assertFalse(CROSSCHECK._is_bstart32(0x00000005))

    def test_reducer_classifies_split_bstart_from_catalog(self) -> None:
        direct = {"insn": 0x00000391, "len": 4}
        cond = {"insn": 0x000003A1, "len": 4}

        self.assertEqual(
            REDUCER._classify_block_marker(direct),
            ("OP_BSTART_SPLIT_DIRECT", True, False),
        )
        self.assertEqual(
            REDUCER._classify_block_marker(cond),
            ("OP_BSTART_SPLIT_COND", True, False),
        )

    def test_reducer_accepts_setc_geu_no_writeback(self) -> None:
        rows = [
            {
                "pc": 0x125D2,
                "insn": 0xF856,
                "len": 2,
                "next_pc": 0x125D4,
                "wb_valid": 1,
                "wb_rd": 31,
                "wb_data": 1,
                "src0_valid": 0,
                "src0_reg": 0,
                "src0_data": 0,
                "src1_valid": 0,
                "src1_reg": 0,
                "src1_data": 0,
                "dst_valid": 1,
                "dst_reg": 31,
                "dst_data": 1,
                "mem_valid": 0,
                "mem_is_store": 0,
                "mem_addr": 0,
                "mem_wdata": 0,
                "mem_rdata": 0,
                "mem_size": 0,
                "trap_valid": 0,
                "trap_cause": 0,
                "traparg0": 0,
            },
            {
                "pc": 0x125D4,
                "insn": 0x064C7065,
                "len": 4,
                "next_pc": 0x125D8,
                "wb_valid": 0,
                "wb_rd": 0,
                "wb_data": 0,
                "src0_valid": 0,
                "src0_reg": 0,
                "src0_data": 0,
                "src1_valid": 1,
                "src1_reg": 4,
                "src1_data": 3,
                "dst_valid": 0,
                "dst_reg": 0,
                "dst_data": 0,
                "mem_valid": 0,
                "mem_is_store": 0,
                "mem_addr": 0,
                "mem_wdata": 0,
                "mem_rdata": 0,
                "mem_size": 0,
                "trap_valid": 0,
                "trap_cause": 0,
                "traparg0": 0,
            },
        ]

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "qemu.jsonl"
            output = Path(tmpdir) / "expected.jsonl"
            with source.open("w", encoding="utf-8") as handle:
                for row in rows:
                    handle.write(json.dumps(row) + "\n")

            self.assertEqual(REDUCER.extract_rows(source, output), 2)
            extracted = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(extracted[1]["insn"], 0x064C7065)
            self.assertEqual(extracted[1]["wb_valid"], 0)


if __name__ == "__main__":
    unittest.main()
