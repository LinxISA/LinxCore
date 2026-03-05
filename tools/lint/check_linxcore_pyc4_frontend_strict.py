#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class TokenSpec:
    name: str
    regex: re.Pattern[str]


TOKENS: list[TokenSpec] = [
    TokenSpec("m.const", re.compile(r"\bm\.const\b")),
    TokenSpec("._select_internal(", re.compile(r"\._select_internal\s*\(")),
    TokenSpec(".__eq__(", re.compile(r"\.__eq__\s*\(")),
    TokenSpec("._trunc(", re.compile(r"\._trunc\s*\(")),
    TokenSpec("._zext(", re.compile(r"\._zext\s*\(")),
    TokenSpec("._sext(", re.compile(r"\._sext\s*\(")),
]


def _iter_py_files(root: Path) -> list[Path]:
    out: list[Path] = []
    for path in sorted(root.rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        out.append(path)
    return out


def scan_counts(*, scan_root: Path) -> dict[str, dict[str, int]]:
    counts: dict[str, dict[str, int]] = {}
    for path in _iter_py_files(scan_root):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            # Skip non-text payloads (shouldn't happen under src/, but keep robust).
            continue
        try:
            inner = path.relative_to(scan_root).as_posix()
        except ValueError:
            inner = path.name
        prefix = scan_root.name
        rel = f"{prefix}/{inner}" if prefix else inner
        row: dict[str, int] = {}
        for tok in TOKENS:
            n = len(list(tok.regex.finditer(text)))
            if n:
                row[tok.name] = n
        if row:
            counts[rel] = row
    return counts


def load_baseline(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or int(data.get("version", 0)) != 1:
        raise ValueError("unsupported baseline format (expected version=1 dict)")
    counts = data.get("counts")
    if not isinstance(counts, dict):
        raise ValueError("baseline missing counts dict")
    return data


def write_baseline(*, path: Path, counts: dict[str, dict[str, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": 1,
        "tokens": [t.name for t in TOKENS],
        "counts": counts,
    }
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(
        description=(
            "Strict pyc4 frontend API lint for LinxCore (burn-down mode): "
            "block new uses of forbidden frontend tokens while allowing gradual removal."
        )
    )
    ap.add_argument("--scan-root", required=True, help="Root directory to scan (typically src/)")
    ap.add_argument("--baseline", required=True, help="Baseline JSON path")
    ap.add_argument(
        "--update-baseline",
        action="store_true",
        help="Write baseline from current tree and exit 0",
    )
    ap.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero when violations are found",
    )
    args = ap.parse_args()

    scan_root = Path(args.scan_root).resolve()
    baseline_path = Path(args.baseline).resolve()

    if not scan_root.is_dir():
        print(f"error: scan root is not a directory: {scan_root}", file=sys.stderr)
        return 2

    curr_counts = scan_counts(scan_root=scan_root)

    if args.update_baseline:
        write_baseline(path=baseline_path, counts=curr_counts)
        print(f"ok: wrote baseline: {baseline_path}")
        return 0

    if not baseline_path.is_file():
        print(f"error: baseline missing: {baseline_path}", file=sys.stderr)
        print("hint: run with --update-baseline to create it", file=sys.stderr)
        return 2

    baseline = load_baseline(baseline_path)
    base_counts = baseline.get("counts", {})
    if not isinstance(base_counts, dict):
        print("error: baseline counts is not a dict", file=sys.stderr)
        return 2

    violations: list[str] = []

    # New file with forbidden tokens is always a violation.
    for rel, row in sorted(curr_counts.items()):
        if rel not in base_counts:
            violations.append(f"new file has forbidden tokens: {rel} {row}")

    # Per-file burn-down: any count increase in an existing file is a violation.
    for rel, base_row_any in sorted(base_counts.items()):
        if not isinstance(base_row_any, dict):
            continue
        curr_row = curr_counts.get(rel, {})
        if not isinstance(curr_row, dict):
            curr_row = {}
        for tok in (t.name for t in TOKENS):
            b = int(base_row_any.get(tok, 0) or 0)
            c = int(curr_row.get(tok, 0) or 0)
            if c > b:
                violations.append(f"{rel}: token {tok!r} increased {b} -> {c}")

    # Summary report for local visibility.
    totals: dict[str, int] = {t.name: 0 for t in TOKENS}
    for row in curr_counts.values():
        for k, v in row.items():
            totals[k] = totals.get(k, 0) + int(v)
    print("info: current forbidden-token totals:")
    for tok in [t.name for t in TOKENS]:
        print(f"  {tok}: {totals.get(tok, 0)}")

    if violations:
        print(f"error: found {len(violations)} pyc4-frontend strict lint violation(s):", file=sys.stderr)
        for v in violations[:200]:
            print(f"  {v}", file=sys.stderr)
        if len(violations) > 200:
            print(f"  ... {len(violations) - 200} more", file=sys.stderr)
        return 1 if args.strict else 0

    print("ok: pyc4-frontend strict lint passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
