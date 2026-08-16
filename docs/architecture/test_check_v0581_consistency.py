#!/usr/bin/env python3
"""Negative tests for the LinxISA 0.58.1 documentation consistency gate."""

from __future__ import annotations

import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).with_name("check_v0581_consistency.py")
ENGINE_COUNTS = {"CUBE": 12, "SFU": 56, "TLSU": 10, "VEC": 31}
FAMILY_COUNTS = {"CUBE": 12, "TEPL": 87, "TLSU": 10}


class ConsistencyCheckerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.linxcore = self.root / "rtl" / "LinxCore"
        self.arch = self.linxcore / "docs" / "architecture"
        self.authority = self.root
        self.arch.mkdir(parents=True)
        (self.root / "isa" / "v0.58").mkdir(parents=True)
        (self.root / "docs" / "architecture").mkdir(parents=True)

        self.isa = {
            "version": "0.58.1",
            "state": {
                "engine_ops": {
                    "version": "0.58.1",
                    "profile": "v0.58",
                    "semantic_engine_counts": copy.deepcopy(ENGINE_COUNTS),
                    "note": "TEPL remains an encoding carrier and is not a semantic engine.",
                    "tepl": {
                        "accepted_selector_count": 87,
                        "kind": "mode_function",
                        "ops": (
                            [{"engine": "VEC"}] * 31
                            + [{"engine": "SFU"}] * 56
                        ),
                    },
                    "tlsu": {"legal_aliases": [{}] * 10},
                    "cube": {"legal_aliases": [{}] * 12},
                },
                "pto_ops": {
                    "version": "0.58.1",
                    "profile": "v0.58",
                    "operation_count": 109,
                    "family_counts": copy.deepcopy(FAMILY_COUNTS),
                    "engine_counts": copy.deepcopy(ENGINE_COUNTS),
                },
            },
            "instructions": [
                {
                    "mnemonic": "BSTART.TEPL",
                    "carrier_mnemonic": "BSTART.TEPL",
                    "accepted_assembly_mnemonics": [
                        "BSTART.TEPL",
                        "BSTART.VEC",
                        "BSTART.SFU",
                    ],
                    "canonical_assembly_by_engine": {
                        "SFU": "BSTART.SFU",
                        "VEC": "BSTART.VEC",
                    },
                }
            ],
        }
        self.manifest = {
            "architecture_version": "0.58.1",
            "documents": [
                {"path": "docs/architecture/README.md", "class": "canonical"},
                {"path": "docs/architecture/active.md", "class": "canonical"},
            ],
            "stage_families": [
                {
                    "id": "block-engine",
                    "stages": [
                        "BISQ",
                        "BCTRL",
                        "BROB",
                        "TEPL",
                        "TLSU",
                        "CUBE",
                        "VEC",
                        "SFU",
                    ],
                }
            ],
        }
        self.active_text = (
            "The Tile execution engines are exactly `VEC`, `SFU`, `TLSU`, and `CUBE`.\n"
            "`TEPL` is the unchanged Mode/Function encoding carrier for `VEC` and "
            "`SFU`; it is not an execution engine.\n"
            "The compiled block-family domain contains `TEPL`, `TLSU`, and `CUBE`; "
            "VEC/SFU are TEPL semantic engines and assembly aliases.\n"
        )
        (self.arch / "README.md").write_text(
            "# Docs\n\n## Contract pages\n\n- `active.md`\n"
            "\n## Deep dives retained here\n\n- `deep.md`\n"
            "\n## Archived Janus subsystem notes\n",
            encoding="utf-8",
        )
        (self.arch / "active.md").write_text(self.active_text, encoding="utf-8")
        (self.arch / "deep.md").write_text("Current deep dive.\n", encoding="utf-8")
        (self.root / "docs" / "architecture" / "v0.58-architecture-contract.md").write_text(
            "The architectural execution engines are exactly:\n"
            "TEPL is the unchanged Mode/Function encoding carrier; it is not an "
            "execution engine.\n",
            encoding="utf-8",
        )
        self._write_fixture()

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def _write_fixture(self) -> None:
        (self.root / "isa" / "v0.58" / "linxisa-v0.58.json").write_text(
            json.dumps(self.isa), encoding="utf-8"
        )
        (self.arch / "microarchitecture-contract.json").write_text(
            json.dumps(self.manifest), encoding="utf-8"
        )

    def _run(self, *, authority: Path | None = None) -> subprocess.CompletedProcess[str]:
        command = [
            "python3",
            str(CHECKER),
            "--linxcore-root",
            str(self.linxcore),
        ]
        if authority is not None:
            command.extend(("--authority-root", str(authority)))
        return subprocess.run(command, capture_output=True, text=True, check=False)

    def _assert_rejected(self, expected: str) -> None:
        self._write_fixture()
        result = self._run(authority=self.authority)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(expected, result.stderr + result.stdout)

    def test_accepts_exact_authority_with_derived_root(self) -> None:
        result = self._run()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)

    def test_rejects_missing_authority_root(self) -> None:
        with tempfile.TemporaryDirectory() as standalone:
            missing_linxcore = Path(standalone) / "LinxCore"
            missing_arch = missing_linxcore / "docs" / "architecture"
            missing_arch.mkdir(parents=True)
            (missing_arch / "README.md").write_text("# Docs\n", encoding="utf-8")
            (missing_arch / "microarchitecture-contract.json").write_text(
                json.dumps(self.manifest), encoding="utf-8"
            )
            result = subprocess.run(
                ["python3", str(CHECKER), "--linxcore-root", str(missing_linxcore)],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cannot derive LinxISA authority root", result.stderr + result.stdout)

    def test_rejects_wrong_version(self) -> None:
        self.isa["version"] = "0.58.0"
        self._assert_rejected("ISA version must be 0.58.1")

    def test_rejects_wrong_engine_counts(self) -> None:
        self.isa["state"]["engine_ops"]["semantic_engine_counts"]["SFU"] = 55
        self._assert_rejected("semantic engine counts")

    def test_rejects_wrong_family_counts(self) -> None:
        self.isa["state"]["pto_ops"]["family_counts"]["TEPL"] = 86
        self._assert_rejected("tile family counts")

    def test_rejects_wrong_carrier_identity(self) -> None:
        self.isa["instructions"][0]["carrier_mnemonic"] = "BSTART.SFU"
        self._assert_rejected("BSTART.TEPL carrier identity")

    def test_rejects_missing_tepl_block_family(self) -> None:
        self.manifest["stage_families"][0]["stages"].remove("TEPL")
        self._assert_rejected("compiled TEPL block family")

    def test_rejects_v057_link_in_readme_declared_deep_dive(self) -> None:
        (self.arch / "deep.md").write_text(
            "Normative: `docs/architecture/v0.57-architecture-contract.md`\n",
            encoding="utf-8",
        )
        result = self._run(authority=self.authority)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "active pages contain a v0.57 architecture link",
            result.stderr + result.stdout,
        )


if __name__ == "__main__":
    unittest.main()
