#!/usr/bin/env python3
"""Reject live documentation references to deleted Task 11 OOO surfaces."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DOC_ROOT = ROOT / "docs/chisel"
# Exact deleted Scala basenames from 5f80ea0b..1375436f. Keep owners and
# suites separate because only test-runner command lines may omit `Spec`.
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
HISTORICAL_SECTION_ALLOWLIST = {
    (
        "agent-loop.md",
        "## Suggested Next Packets",
    ),
    (
        "mainline-loop-ledger.md",
        "## Loop 9 — Canonical ROB, BROB, commit, and precise recovery authority",
    ),
}


def contains_identifier(line: str, identifier: str) -> bool:
    return re.search(
        rf"(?<![A-Za-z0-9_]){re.escape(identifier)}(?![A-Za-z0-9_])",
        line,
    ) is not None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--doc-root",
        type=Path,
        default=DEFAULT_DOC_ROOT,
        help="documentation tree to scan (defaults to docs/chisel)",
    )
    return parser.parse_args()


def find_stale_references(doc_root: Path) -> list[str]:
    stale: list[str] = []
    for path in sorted(doc_root.rglob("*.md")):
        relative = path.relative_to(doc_root)
        section = ""
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(), 1
        ):
            if line.startswith("#"):
                section = line.strip()
            if (relative.as_posix(), section) in HISTORICAL_SECTION_ALLOWLIST:
                continue
            matches = {
                reference
                for reference in (*DELETED_TASK11_OWNERS, *DELETED_TASK11_SUITES)
                if contains_identifier(line, reference)
            }
            if "--only" in line:
                matches.update(
                    reference
                    for reference in DELETED_TASK11_RUNNERS
                    if contains_identifier(line, reference)
                )
            stale.extend(
                f"{relative}:{line_number}: [{reference}] {line.strip()}"
                for reference in sorted(matches)
            )
    return stale


def main() -> int:
    stale = find_stale_references(parse_args().doc_root.resolve())
    if stale:
        for item in stale:
            print(f"deleted-ooo-doc-reference: ERROR: {item}")
        return 1
    print("deleted-ooo-doc-reference: no live deleted Task 11 references")
    return 0


if __name__ == "__main__":
    sys.exit(main())
