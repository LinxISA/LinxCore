#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


REG_CLASS_OPS = {
    "pyc.reg",
    "pyc.fifo",
    "pyc.byte_mem",
    "pyc.sync_mem",
    "pyc.sync_mem_dp",
    "pyc.async_fifo",
    "pyc.cdc_sync",
}

FUNC_RE = re.compile(r"^\s*func\.func\s+(?:private\s+)?@([A-Za-z_][A-Za-z0-9_$]*)")
OP_RE = re.compile(r"\b(pyc\.[a-zA-Z0-9_]+)\b")


def parse_module_stats(text: str) -> dict[str, dict[str, int]]:
    stats: dict[str, dict[str, int]] = {}
    cur: str | None = None

    for line in text.splitlines():
        m = FUNC_RE.match(line)
        if m:
            cur = m.group(1)
            stats.setdefault(cur, {"regs": 0, "wires": 0})
            continue

        if cur is None:
            continue

        for op in OP_RE.findall(line):
            if op in REG_CLASS_OPS:
                stats[cur]["regs"] += 1
            elif op == "pyc.wire":
                stats[cur]["wires"] += 1

    return stats


def load_exemptions(path: Path) -> set[str]:
    if not path.is_file():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    mods = data.get("exempt_modules", [])
    return {str(x) for x in mods}


def main() -> int:
    ap = argparse.ArgumentParser(description="Hard module resource budget lint for .pyc MLIR")
    ap.add_argument("--pyc", required=True, help="Path to emitted .pyc MLIR text")
    ap.add_argument("--max-regs", type=int, default=128)
    ap.add_argument("--max-wires", type=int, default=512)
    ap.add_argument("--exemptions", default="", help="Optional module budget exemption json")
    args = ap.parse_args()

    pyc_path = Path(args.pyc)
    if not pyc_path.is_file():
        raise SystemExit(f"module budget lint failed: missing .pyc file: {pyc_path}")

    text = pyc_path.read_text(encoding="utf-8", errors="replace")
    stats = parse_module_stats(text)

    exemptions = set()
    if args.exemptions:
        exemptions = load_exemptions(Path(args.exemptions))

    errs: list[str] = []
    for mod, ss in sorted(stats.items()):
        if mod in exemptions:
            continue
        regs = int(ss.get("regs", 0))
        wires = int(ss.get("wires", 0))
        if regs > args.max_regs or wires > args.max_wires:
            errs.append(
                f"{mod}: regs={regs} wires={wires} exceeds "
                f"limits regs<={args.max_regs} wires<={args.max_wires}"
            )

    if errs:
        raise SystemExit("module budget lint failed:\n" + "\n".join(errs))

    print(
        "module budget lint passed "
        f"(modules={len(stats)}, max_regs={args.max_regs}, max_wires={args.max_wires}, exemptions={len(exemptions)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
