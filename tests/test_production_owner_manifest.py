from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools/chisel/check_production_owner_manifest.py"


class ProductionOwnerManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest_path = self.root / "docs/chisel/production-owner-manifest.md"
        self.manifest = self._base_manifest()
        for path in self._all_fixture_paths():
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("fixture\n", encoding="utf-8")
        emitter = self.root / "chisel/src/main/scala/linxcore/top/LegacyTop.scala"
        emitter.parent.mkdir(parents=True, exist_ok=True)
        emitter.write_text(
            "object EmitLinxCoreLegacyTop extends App {\n"
            "  circt.stage.ChiselStage.emitSystemVerilogFile(new Object)\n"
            "}\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _base_manifest(self) -> dict:
        state_keys = (
            ("IFU", "fetch_state"),
            ("CTU", "instruction_buffer"),
            ("OOO", "rob"),
            ("IEX", "issue_queue"),
            ("LSU", "store_queue"),
            ("DTU", "trace_observation"),
        )
        owners = []
        for subsystem, state_key in state_keys:
            slug = state_key.replace("_", "-")
            owners.append(
                {
                    "subsystem": subsystem,
                    "state_key": state_key,
                    "canonical_owner": f"{subsystem}{state_key.title().replace('_', '')}Owner",
                    "mechanism_files": [f"chisel/src/main/scala/{slug}.scala"],
                    "public_box": subsystem,
                    "public_box_file": f"chisel/src/main/scala/{subsystem.lower()}-box.scala",
                    "active_callers": [f"chisel/src/main/scala/{slug}-caller.scala"],
                    "verification_fixtures": [f"chisel/src/test/scala/{slug}-spec.scala"],
                    "production_evidence": [
                        {
                            "fixture": f"evidence/{slug}.json",
                            "level": "L3",
                            "status": "standalone-verified",
                        }
                    ],
                    "cutover_task": 10,
                    "deletion_targets": [
                        {
                            "path": f"legacy/{slug}.scala",
                            "active_callers": [],
                        }
                    ],
                    "adapters": [],
                }
            )
        return {
            "schema_version": 1,
            "ndf": {
                "L1": ["docs/spec/30-interfaces/contract.md"],
                "L2": ["docs/chisel/generated/top-interface-manifest.json"],
                "L3": ["chisel/src/test/scala/interface-spec.scala"],
                "interface_manifest": "docs/chisel/generated/top-interface-manifest.json",
            },
            "owners": owners,
            "entry_points": {
                "production": [],
                "non_production_patterns": ["*Reduced*", "*Probe*", "EmitLinxCore*Top"],
                "non_production": [],
            },
        }

    def _all_fixture_paths(self) -> set[str]:
        paths = set(self.manifest["ndf"]["L1"])
        paths.update(self.manifest["ndf"]["L2"])
        paths.update(self.manifest["ndf"]["L3"])
        for owner in self.manifest["owners"]:
            paths.update(owner["mechanism_files"])
            paths.add(owner["public_box_file"])
            paths.update(owner["active_callers"])
            paths.update(owner["verification_fixtures"])
            paths.update(item["fixture"] for item in owner["production_evidence"])
            paths.update(item["path"] for item in owner["deletion_targets"])
        return paths

    def _write_manifest(self, manifest: dict | None = None) -> None:
        self.manifest_path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(manifest or self.manifest, indent=2, sort_keys=True)
        self.manifest_path.write_text(
            "# Fixture production-owner manifest\n\n"
            "```json production-owner-manifest\n"
            f"{payload}\n"
            "```\n",
            encoding="utf-8",
        )

    def _run(self, manifest: dict | None = None) -> subprocess.CompletedProcess[str]:
        self._write_manifest(manifest)
        return subprocess.run(
            [
                sys.executable,
                str(CHECKER),
                "--root",
                str(self.root),
                "--manifest",
                str(self.manifest_path),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def assert_rejected(self, manifest: dict, message: str) -> None:
        result = self._run(manifest)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(message, result.stdout + result.stderr)

    def test_accepts_complete_manifest_through_real_cli(self) -> None:
        result = self._run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("production-owner-manifest: 6 owners", result.stdout)

    def test_rejects_duplicate_state_owners_for_protected_categories(self) -> None:
        categories = ("rob", "rename", "issue_queue", "lsu_pipeline", "cache", "recovery")
        for category in categories:
            with self.subTest(category=category):
                manifest = copy.deepcopy(self.manifest)
                first = manifest["owners"][0]
                first["state_key"] = category
                duplicate = copy.deepcopy(first)
                duplicate["canonical_owner"] = "DuplicateOwner"
                manifest["owners"].append(duplicate)
                self.assert_rejected(manifest, f"duplicate owner for state_key {category}")

    def test_rejects_unknown_scala_emitter(self) -> None:
        unknown = self.root / "chisel/src/main/scala/linxcore/top/Unknown.scala"
        unknown.parent.mkdir(parents=True, exist_ok=True)
        unknown.write_text(
            "object EmitUnknownProduction extends App {\n"
            "  circt.stage.ChiselStage.emitSystemVerilogFile(new Object)\n"
            "}\n",
            encoding="utf-8",
        )
        self.assert_rejected(self.manifest, "unknown emitter EmitUnknownProduction")

    def test_rejects_missing_production_evidence(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["owners"][0]["production_evidence"] = []
        self.assert_rejected(manifest, "missing production evidence for fetch_state")

    def test_rejects_stateful_adapter(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["owners"][0]["adapters"] = [
            {"name": "QueueCompatibilityAdapter", "stateful": True}
        ]
        self.assert_rejected(manifest, "stateful adapter QueueCompatibilityAdapter")

    def test_rejects_deletion_target_with_active_callers(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["owners"][0]["deletion_targets"][0]["active_callers"] = [
            "chisel/src/main/scala/fetch-state-caller.scala"
        ]
        self.assert_rejected(manifest, "deletion target legacy/fetch-state.scala has active callers")

    def test_rejects_missing_ndf_layer_or_contract_home(self) -> None:
        for key in ("L1", "L2", "L3", "interface_manifest"):
            with self.subTest(key=key):
                manifest = copy.deepcopy(self.manifest)
                manifest["ndf"].pop(key)
                self.assert_rejected(manifest, f"missing NDF {key}")


if __name__ == "__main__":
    unittest.main()
