#!/usr/bin/env python3
"""Reject live documentation references to deleted Task 11 OOO surfaces."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
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
    (
        "chisel/agent-loop.md",
        "## Suggested Next Packets",
    ),
    (
        "chisel/mainline-loop-ledger.md",
        "## Loop 9 — Canonical ROB, BROB, commit, and precise recovery authority",
    ),
    (
        "superpowers/plans/2026-07-31-core-mainline-restructure.md",
        "### Task 8: Build RENU with separate P and T/U rename machines",
    ),
    (
        "superpowers/plans/2026-07-31-core-mainline-restructure.md",
        "### Task 11: Atomically cut canonical OOO onto production D3/S1 mechanisms",
    ),
    (
        "superpowers/plans/2026-07-31-scalar-load-structural-block-policy.md",
        "### Task 3: Install policy behind the OOO/IEX production boundary",
    ),
    (
        "superpowers/plans/2026-07-31-scalar-load-structural-block-policy.md",
        "### Task 4: Documentation, full gates, push, and superproject pin",
    ),
    (
        "superpowers/specs/2026-08-01-production-owner-atomic-cutover-design.md",
        "### 2.1 Preserve Tasks 1-9",
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
        help="documentation tree to scan (defaults to <repo-root>/docs)",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=ROOT,
        help="repository root used by the default documentation scan",
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
    args = parse_args()
    doc_root = args.doc_root or args.repo_root / "docs"
    stale = find_stale_references(doc_root.resolve())
    if stale:
        for item in stale:
            print(f"deleted-ooo-doc-reference: ERROR: {item}")
        return 1
    print("deleted-ooo-doc-reference: no live deleted Task 11 references")
    return 0


if __name__ == "__main__":
    sys.exit(main())
