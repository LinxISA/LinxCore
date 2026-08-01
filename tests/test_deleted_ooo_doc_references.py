from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools/chisel/check_deleted_ooo_doc_references.py"

# Mirror the exact deleted Scala basename inventory from 5f80ea0b..1375436f
# so omission of any owner or suite fails as an independent fixture assertion.
DELETED_TASK11_OWNERS = (
    "OooBrob",
    "OooCtuIngressBridge",
    "OooFrontendRecoveryBridge",
    "OooIfuD1Ingress",
    "OooIfuRawIngress",
    "OooO3IexStorePipeline",
    "OooO3RenameCoordinator",
    "OooPRename",
    "OooRobBrobPcCoordinator",
    "OooRobStoreCommitOwner",
    "OooS1GroupedRob",
    "OooTURename",
)
DELETED_TASK11_SUITES = (
    "OooBrobSpec",
    "OooCtuIngressBridgeSpec",
    "OooD3ReservationAllocatorSpec",
    "OooD3S1BrobIntegrationSpec",
    "OooD3S1GroupedRobIntegrationSpec",
    "OooDispatchSpec",
    "OooFrontendCtuRecoveryIntegrationSpec",
    "OooFrontendIfuRecoveryIntegrationSpec",
    "OooFrontendRecoveryBridgeSpec",
    "OooIfuD1IngressSpec",
    "OooIfuRawIngressSpec",
    "OooO3FastResolveIntegrationSpec",
    "OooO3IexIntegrationSpec",
    "OooO3IexStorePipelineSpec",
    "OooO3RenameCoordinatorSpec",
    "OooO3RenameRandomizedSpec",
    "OooPRenameSpec",
    "OooRobBrobPcCoordinatorSpec",
    "OooRobStoreCommitOwnerSpec",
    "OooRobStoreCommitStqIntegrationSpec",
    "OooS1GroupedRobFaultSpec",
    "OooS1GroupedRobSpec",
    "OooTURenameSpec",
)
DELETED_TASK11_RUNNERS = tuple(
    suite.removesuffix("Spec") for suite in DELETED_TASK11_SUITES
)


class DeletedOooDocReferencesTest(unittest.TestCase):
    def run_checker(self, doc_root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), "--doc-root", str(doc_root)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_rejects_every_deleted_task11_owner_and_suite_in_live_docs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "live.md").write_text(
                "\n".join(DELETED_TASK11_OWNERS)
                + "\n"
                + "\n".join(
                    f"chisel/src/test/scala/linxcore/ooo/{suite}.scala"
                    for suite in DELETED_TASK11_SUITES
                )
                + "\n"
                + "\n".join(
                    f"bash tools/chisel/run_chisel_tests.sh --only {runner}"
                    for runner in DELETED_TASK11_RUNNERS
                )
                + "\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        for reference in (
            *DELETED_TASK11_OWNERS,
            *DELETED_TASK11_SUITES,
            *DELETED_TASK11_RUNNERS,
        ):
            with self.subTest(reference=reference):
                self.assertIn(f"[{reference}]", result.stdout)

    def test_does_not_reject_surviving_owner_names_in_live_prose(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "live.md").write_text(
                "OooDispatch composes OooD3ReservationAllocator.\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_does_not_reject_surviving_types_with_deleted_owner_prefixes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "live.md").write_text(
                "OooTURenamePreparedTransaction and OooBrobPrepared remain live.\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_historical_word_does_not_exempt_an_arbitrary_live_location(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "live.md").write_text(
                "Historical note naming OooO3RenameCoordinator as active.\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("OooO3RenameCoordinator", result.stdout)

    def test_allows_only_the_exact_agent_loop_historical_section(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "agent-loop.md").write_text(
                "# Agent Loop\n\n"
                "## Suggested Next Packets\n\n"
                "Historical R704 used OooO3RenameCoordinator.\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_allows_only_the_exact_mainline_ledger_historical_section(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "mainline-loop-ledger.md").write_text(
                "# Mainline Ledger\n\n"
                "## Loop 9 — Canonical ROB, BROB, commit, and precise recovery authority\n\n"
                "The old OooO3RenameCoordinator was displaced.\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_allowlisted_headings_at_other_paths_and_sections(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_root = Path(tmp)
            (doc_root / "agent-loop.md").write_text(
                "# Agent Loop\n\n"
                "## Current Work\n\n"
                "OooS1GroupedRob is current.\n",
                encoding="utf-8",
            )
            (doc_root / "other.md").write_text(
                "## Suggested Next Packets\n\n"
                "OooRobStoreCommitOwner is current.\n",
                encoding="utf-8",
            )
            (doc_root / "mainline-loop-ledger.md").write_text(
                "# Mainline Ledger\n\n"
                "## Current Loop\n\n"
                "OooBrobSpec is current.\n",
                encoding="utf-8",
            )

            result = self.run_checker(doc_root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("agent-loop.md:5: [OooS1GroupedRob]", result.stdout)
        self.assertIn("other.md:3: [OooRobStoreCommitOwner]", result.stdout)
        self.assertIn("mainline-loop-ledger.md:5: [OooBrobSpec]", result.stdout)


if __name__ == "__main__":
    unittest.main()
