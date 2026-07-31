#!/usr/bin/env python3
"""Validate the LinxCore NDF documentation profile."""

from __future__ import annotations

import argparse
import hashlib
import re
import shlex
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path


CLAUSE_RE = re.compile(r"^#{2,6}\s+.+?\s+\{#([A-Z][A-Z0-9-]*)\}\s*$")
METADATA_RE = re.compile(r"<!--\s*ndf:\s*(.*?)\s*-->")
CROSS_REFERENCE_RE = re.compile(r"\[\[([A-Z][A-Z0-9-]*)\]\]")
HEX40_RE = re.compile(r"^[0-9a-f]{40}$")
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_METADATA = ("kind", "level", "layer", "status")
EDGE_KEYS = (
    "refines",
    "depends-on",
    "conflicts-with",
    "verifies",
    "affects",
    "blocks",
)
ORIGIN_STATUSES = {"verbatim", "paraphrase", "interpretation"}


@dataclass
class Clause:
    identifier: str
    path: Path
    line: int
    metadata: dict[str, str] = field(default_factory=dict)
    text: list[str] = field(default_factory=list)


def parse_metadata(raw: str, clause: Clause, errors: list[str]) -> None:
    try:
        tokens = shlex.split(raw)
    except ValueError as error:
        errors.append(
            f"{clause.path}:{clause.line}: invalid metadata for "
            f"{clause.identifier}: {error}"
        )
        return

    for token in tokens:
        if "=" not in token:
            errors.append(
                f"{clause.path}:{clause.line}: invalid metadata token "
                f"{token!r} for {clause.identifier}"
            )
            continue
        key, value = token.split("=", 1)
        if key in clause.metadata and clause.metadata[key] != value:
            errors.append(
                f"{clause.path}:{clause.line}: conflicting metadata {key} "
                f"for {clause.identifier}"
            )
            continue
        clause.metadata[key] = value


def load_clauses(spec_root: Path) -> tuple[list[Clause], list[str]]:
    clauses: list[Clause] = []
    errors: list[str] = []

    for path in sorted(spec_root.rglob("*.md")):
        current: Clause | None = None
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            heading = CLAUSE_RE.match(line)
            if heading:
                current = Clause(heading.group(1), path, line_number)
                clauses.append(current)
                continue
            if current is None:
                continue
            current.text.append(line)
            for metadata in METADATA_RE.findall(line):
                parse_metadata(metadata, current, errors)

    return clauses, errors


def split_targets(value: str) -> list[str]:
    return [target for target in re.split(r"[,;]", value) if target]


def validate_local_reference(clause: Clause, errors: list[str]) -> None:
    origin_kind = clause.metadata.get("origin-kind")
    if origin_kind == "file":
        origin = Path(clause.metadata["origin"]).expanduser()
        if not origin.is_file():
            errors.append(
                f"{clause.path}:{clause.line}: local reference missing for "
                f"{clause.identifier}: {origin}"
            )
            return
        actual = hashlib.sha256(origin.read_bytes()).hexdigest()
        if actual != clause.metadata["sha256"]:
            errors.append(
                f"{clause.path}:{clause.line}: local reference hash mismatch for "
                f"{clause.identifier}"
            )
        return

    if origin_kind == "git" and "checkout" in clause.metadata:
        checkout = Path(clause.metadata["checkout"]).expanduser()
        if not checkout.is_dir():
            errors.append(
                f"{clause.path}:{clause.line}: local reference checkout missing for "
                f"{clause.identifier}: {checkout}"
            )
            return
        result = subprocess.run(
            [
                "git",
                "-C",
                str(checkout),
                "cat-file",
                "-t",
                clause.metadata["revision"],
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        object_type = result.stdout.strip()
        if result.returncode != 0 or object_type != "commit":
            errors.append(
                f"{clause.path}:{clause.line}: local reference revision mismatch for "
                f"{clause.identifier}"
            )


def validate(
    spec_root: Path, *, verify_local_references: bool
) -> tuple[list[Clause], list[str]]:
    clauses, errors = load_clauses(spec_root)
    by_identifier: dict[str, Clause] = {}

    for clause in clauses:
        if clause.identifier in by_identifier:
            errors.append(
                f"{clause.path}:{clause.line}: duplicate clause id "
                f"{clause.identifier}"
            )
        else:
            by_identifier[clause.identifier] = clause

        for key in REQUIRED_METADATA:
            if key not in clause.metadata:
                errors.append(
                    f"{clause.path}:{clause.line}: missing metadata {key} "
                    f"for {clause.identifier}"
                )

    for clause in clauses:
        targets: list[str] = []
        for key in EDGE_KEYS:
            if key in clause.metadata:
                targets.extend(split_targets(clause.metadata[key]))
        targets.extend(
            match
            for line in clause.text
            for match in CROSS_REFERENCE_RE.findall(line)
        )
        for target in sorted(set(targets)):
            if target not in by_identifier:
                errors.append(
                    f"{clause.path}:{clause.line}: dangling reference {target} "
                    f"from {clause.identifier}"
                )

        if not clause.identifier.startswith("REF-"):
            continue

        reference_error_count = len(errors)
        origin_kind = clause.metadata.get("origin-kind")
        origin_status = clause.metadata.get("origin-status")
        if origin_kind not in {"file", "git"}:
            errors.append(
                f"{clause.path}:{clause.line}: invalid origin-kind for "
                f"{clause.identifier}"
            )
        if origin_status not in ORIGIN_STATUSES:
            errors.append(
                f"{clause.path}:{clause.line}: invalid origin-status for "
                f"{clause.identifier}"
            )
        if origin_kind == "file":
            if "origin" not in clause.metadata:
                errors.append(
                    f"{clause.path}:{clause.line}: missing origin for "
                    f"{clause.identifier}"
                )
            digest = clause.metadata.get("sha256", "")
            if not HEX64_RE.fullmatch(digest):
                errors.append(
                    f"{clause.path}:{clause.line}: invalid sha256 for "
                    f"{clause.identifier}"
                )
        elif origin_kind == "git":
            if "repository" not in clause.metadata:
                errors.append(
                    f"{clause.path}:{clause.line}: missing repository for "
                    f"{clause.identifier}"
                )
            revision = clause.metadata.get("revision", "")
            if not HEX40_RE.fullmatch(revision):
                errors.append(
                    f"{clause.path}:{clause.line}: invalid revision for "
                    f"{clause.identifier}"
                )

        if verify_local_references and len(errors) == reference_error_count:
            validate_local_reference(clause, errors)

    verification_targets = {
        target
        for clause in clauses
        if clause.metadata.get("kind") == "verif"
        for target in split_targets(clause.metadata.get("verifies", ""))
    }
    l1_must = [
        clause
        for clause in clauses
        if clause.metadata.get("kind") == "req"
        and clause.metadata.get("level") == "must"
        and clause.metadata.get("layer") == "L1"
    ]
    for clause in l1_must:
        if clause.identifier not in verification_targets:
            errors.append(
                f"{clause.path}:{clause.line}: missing verifies edge for L1 MUST "
                f"{clause.identifier}"
            )

    return clauses, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("spec_root", type=Path)
    parser.add_argument(
        "--verify-local-references",
        action="store_true",
        help="also compare pinned identities with available local sources",
    )
    args = parser.parse_args()

    if not (args.spec_root / "ndf.yaml").is_file():
        print(f"{args.spec_root}: missing ndf.yaml", file=sys.stderr)
        return 1

    clauses, errors = validate(
        args.spec_root, verify_local_references=args.verify_local_references
    )
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1

    l1_must = sum(
        clause.metadata.get("kind") == "req"
        and clause.metadata.get("level") == "must"
        and clause.metadata.get("layer") == "L1"
        for clause in clauses
    )
    verification_targets = {
        target
        for clause in clauses
        if clause.metadata.get("kind") == "verif"
        for target in split_targets(clause.metadata.get("verifies", ""))
    }
    open_questions = sum(
        clause.metadata.get("status") in {"open", "tbd"} for clause in clauses
    )
    references = sum(clause.identifier.startswith("REF-") for clause in clauses)
    print(
        f"clauses={len(clauses)} l1_must={l1_must} "
        f"verified={len(verification_targets)} "
        f"open_questions={open_questions} references={references}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
