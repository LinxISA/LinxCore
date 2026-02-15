#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path


def parse_modules(src: Path) -> tuple[list[str], list[tuple[str, list[str]]]]:
    lines = src.read_text(encoding="utf-8").splitlines(keepends=True)
    mod_re = re.compile(r"^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)\b")

    preamble: list[str] = []
    modules: list[tuple[str, list[str]]] = []

    i = 0
    while i < len(lines):
        m = mod_re.match(lines[i])
        if m:
            break
        preamble.append(lines[i])
        i += 1

    while i < len(lines):
        m = mod_re.match(lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        body: list[str] = [lines[i]]
        i += 1
        depth = 1
        while i < len(lines) and depth > 0:
            line = lines[i]
            if mod_re.match(line):
                depth += 1
            if re.match(r"^\s*endmodule\b", line):
                depth -= 1
            body.append(line)
            i += 1
        modules.append((name, body))

    return preamble, modules


def main() -> int:
    ap = argparse.ArgumentParser(description="Split monolithic Verilog into one module per file")
    ap.add_argument("--src", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--top", required=True)
    ap.add_argument("--primitives-dir", default="/Users/zhoubot/pyCircuit/include/pyc/verilog")
    args = ap.parse_args()

    src = Path(args.src)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    preamble, modules = parse_modules(src)

    include_re = re.compile(r'^\s*`include\s+"([^"]+)"')
    copied_prims: list[str] = []
    prim_dir = Path(args.primitives_dir)
    for line in preamble:
        m = include_re.match(line)
        if not m:
            continue
        name = m.group(1)
        prim_src = prim_dir / name
        prim_dst = out_dir / name
        if prim_src.exists():
            shutil.copy2(prim_src, prim_dst)
            copied_prims.append(name)

    verilog_modules: list[str] = []
    for name, body in modules:
        fn = f"{name}.v"
        verilog_modules.append(fn)
        (out_dir / fn).write_text("".join(preamble + body), encoding="utf-8")

    manifest = {
        "cpp_modules": [],
        "top": args.top,
        "verilog_modules": [*copied_prims, *verilog_modules],
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
