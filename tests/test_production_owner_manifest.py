from __future__ import annotations

import copy
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools/chisel/check_production_owner_manifest.py"
LIVE_MANIFEST = ROOT / "docs/chisel/production-owner-manifest.md"
MANIFEST_BLOCK = re.compile(
    r"```json\s+production-owner-manifest\s*\n(?P<payload>.*?)\n```",
    re.DOTALL,
)


class ProductionOwnerManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "repo"
        self.root.mkdir()
        self.manifest_path = self.root / "docs/chisel/production-owner-manifest.md"
        match = MANIFEST_BLOCK.search(LIVE_MANIFEST.read_text(encoding="utf-8"))
        assert match is not None
        self.manifest = json.loads(match.group("payload"))
        shutil.copytree(
            ROOT / "chisel/src/main/scala",
            self.root / "chisel/src/main/scala",
        )
        self._copy_manifest_files()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _copy_manifest_files(self) -> None:
        paths: set[str] = set()
        for value in self.manifest["ndf"].values():
            if isinstance(value, str):
                paths.add(value)
            else:
                paths.update(value)
        for owner in self.manifest["owners"]:
            for key in (
                "mechanism_files",
                "active_callers",
                "verification_fixtures",
            ):
                paths.update(owner[key])
            public_box_file = owner.get("public_box_file")
            if public_box_file:
                paths.add(public_box_file)
            public_interface_file = owner.get("public_interface_file")
            if public_interface_file:
                paths.add(public_interface_file)
            paths.update(item["fixture"] for item in owner["production_evidence"])
            paths.update(item["path"] for item in owner["deletion_targets"])
        for path in paths:
            source = ROOT / path
            if not source.is_file():
                continue
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                shutil.copy2(source, target)

    def _write_file(self, relative: str, content: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

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

    def owner(self, state_key: str, manifest: dict | None = None) -> dict:
        source = manifest or self.manifest
        return next(item for item in source["owners"] if item["state_key"] == state_key)

    def test_accepts_complete_manifest_through_real_cli(self) -> None:
        result = self._run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("production-owner-manifest: 23 closed owners", result.stdout)

    def test_rejects_real_deletion_caller_omitted_from_manifest(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        target = self.owner("dispatch_reservation", manifest)["deletion_targets"][0]
        target.update(
            {
                "path": "chisel/src/main/scala/fixture/LegacyOwner.scala",
                "symbol": "fixture.LegacyOwner",
                "status": "deletion-ready",
                "active_callers": [],
            }
        )
        self._write_file(
            target["path"],
            "package fixture\nclass LegacyOwner\n",
        )
        self._write_file(
            "chisel/src/main/scala/fixture/RealCaller.scala",
            "package fixture\nclass RealCaller { val owner = new LegacyOwner }\n",
        )
        self.assert_rejected(
            manifest,
            "deletion target fixture.LegacyOwner caller declaration mismatch",
        )

    def test_rejects_missing_closed_state_domain(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["owners"] = [
            item for item in manifest["owners"] if item["state_key"] != "rob"
        ]
        self.assert_rejected(manifest, "missing state domain rob")

    def test_rejects_alias_for_closed_state_domain(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        duplicate = copy.deepcopy(self.owner("rob", manifest))
        duplicate["state_key"] = "rob_shadow"
        duplicate["canonical_owner"] = "ROBShadow"
        duplicate["mechanism_files"] = [
            "chisel/src/main/scala/linxcore/ooo/ROBShadow.scala"
        ]
        self._write_file(
            duplicate["mechanism_files"][0],
            "package linxcore.ooo\nclass ROBShadow\n",
        )
        manifest["owners"].append(duplicate)
        self.assert_rejected(manifest, "unknown state domain rob_shadow")

    def test_rejects_second_mechanism_defining_canonical_owner(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        row = self.owner("rob", manifest)
        second = "chisel/src/main/scala/linxcore/ooo/ROBSecondOwner.scala"
        row["mechanism_files"].append(second)
        self._write_file(second, "package linxcore.ooo\nclass ROB\n")
        self.assert_rejected(manifest, "canonical symbol ROB must be defined exactly once")

    def test_rejects_undeclared_real_stateful_adapter(self) -> None:
        path = "chisel/src/main/scala/linxcore/ooo/HiddenCompatibilityAdapter.scala"
        self._write_file(
            path,
            "package linxcore.ooo\n"
            "import chisel3._\n"
            "class HiddenCompatibilityAdapter extends Module {\n"
            "  val held = RegInit(false.B)\n"
            "}\n",
        )
        self.assert_rejected(self.manifest, "undeclared adapter HiddenCompatibilityAdapter")

    def test_rejects_io_bundle_pretending_to_be_public_module_box(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        row = self.owner("instruction_cache", manifest)
        row["public_box_file"] = "chisel/src/main/scala/linxcore/top/interface/IFUIO.scala"
        row["public_box_status"] = "module"
        self.assert_rejected(manifest, "public box IFU is not a Module definition")

    def test_rejects_evidence_promotion_without_reachability_or_activation(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        row = self.owner("rob", manifest)
        row["production_evidence"][0]["status"] = "production-promoted"
        row["production_evidence"][0]["proof_kinds"] = [
            "generated-rtl-activation",
            "bounded-workload",
        ]
        emitter_path = "chisel/src/main/scala/linxcore/top/EmitFakeProduction.scala"
        self._write_file(
            emitter_path,
            "package linxcore.top\n"
            "object EmitFakeProduction extends App {\n"
            "  circt.stage.ChiselStage.emitSystemVerilogFile(new Object)\n"
            "}\n",
        )
        manifest["entry_points"]["production"] = [
            {
                "name": "EmitFakeProduction",
                "path": emitter_path,
                "root_symbol": "TOP",
                "boxes": ["OOO"],
                "production_evidence": [row["production_evidence"][0]["fixture"]],
            }
        ]
        self.assert_rejected(manifest, "production-promoted evidence for rob lacks")

    def test_rejects_evidence_fixture_not_in_verification_fixtures(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        row = self.owner("rob", manifest)
        fixture = "evidence/unreferenced.json"
        self._write_file(fixture, "{}\n")
        row["production_evidence"][0]["fixture"] = fixture
        self.assert_rejected(manifest, "evidence fixture for rob is not a verification fixture")

    def _add_unknown_emitter(self, name: str, body: str) -> None:
        self._write_file(
            f"chisel/src/main/scala/linxcore/top/{name}.scala",
            f"package linxcore.top\n{body}\n",
        )

    def test_rejects_manifest_wildcard_emitter_whitelist(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["entry_points"]["non_production_patterns"] = ["*"]
        self._add_unknown_emitter(
            "EmitUnknownWildcard",
            "object EmitUnknownWildcard extends App {\n"
            "  circt.stage.ChiselStage.emitSystemVerilogFile(new Object)\n"
            "}",
        )
        self.assert_rejected(manifest, "manifest-controlled emitter patterns are forbidden")

    def test_rejects_unknown_def_main_emitter(self) -> None:
        self._add_unknown_emitter(
            "EmitUnknownDefMain",
            "object EmitUnknownDefMain {\n"
            "  def main(args: Array[String]): Unit =\n"
            "    circt.stage.ChiselStage.emitSystemVerilogFile(new Object)\n"
            "}",
        )
        self.assert_rejected(self.manifest, "unknown emitter EmitUnknownDefMain")

    def test_rejects_unknown_at_main_emitter(self) -> None:
        self._add_unknown_emitter(
            "EmitUnknownAtMain",
            "@main def EmitUnknownAtMain(): Unit =\n"
            "  circt.stage.ChiselStage.emitSystemVerilogFile(new Object)",
        )
        self.assert_rejected(self.manifest, "unknown emitter EmitUnknownAtMain")

    def test_rejects_unknown_wrapper_call_emitter(self) -> None:
        self._add_unknown_emitter(
            "EmitUnknownWrapper",
            "def emitFixture(): Unit =\n"
            "  circt.stage.ChiselStage.emitSystemVerilogFile(new Object)\n"
            "object EmitUnknownWrapper {\n"
            "  def main(args: Array[String]): Unit = emitFixture()\n"
            "}",
        )
        self.assert_rejected(self.manifest, "unknown emitter EmitUnknownWrapper")

    def test_rejects_external_and_traversal_paths(self) -> None:
        external = Path(self.temporary.name) / "outside.md"
        external.write_text("outside\n", encoding="utf-8")
        for value in (str(external), "../outside.md"):
            with self.subTest(value=value):
                manifest = copy.deepcopy(self.manifest)
                manifest["ndf"]["L1"] = [value]
                self.assert_rejected(manifest, "path escapes repository root")

    def test_rejects_ndf_layer_misclassification(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["ndf"]["L1"] = [manifest["ndf"]["interface_manifest"]]
        self.assert_rejected(manifest, "NDF L1 path must be Markdown under docs/spec")

    def test_rejects_same_file_reused_across_ndf_layers(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        shared = manifest["ndf"]["L1"][0]
        manifest["ndf"]["L1"] = [shared]
        manifest["ndf"]["L2"] = [shared]
        manifest["ndf"]["L3"] = [shared]
        manifest["ndf"]["interface_manifest"] = shared
        self.assert_rejected(manifest, "NDF path reused across layer roles")

    def test_rejects_interface_manifest_outside_l2(self) -> None:
        manifest = copy.deepcopy(self.manifest)
        manifest["ndf"]["L2"] = [
            "chisel/src/main/scala/linxcore/top/interface/TOPIO.scala"
        ]
        self.assert_rejected(manifest, "interface manifest must be an NDF L2 member")


if __name__ == "__main__":
    unittest.main()
