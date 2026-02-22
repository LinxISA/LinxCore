#!/usr/bin/env python3
from __future__ import annotations

import argparse
import itertools
import json
import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUN_CPP = ROOT / "tools" / "generate" / "run_linxcore_top_cpp.sh"


def run_once(memh: Path, params: dict[str, int], max_cycles: int) -> dict[str, object]:
    env = os.environ.copy()
    env["PYC_MAX_CYCLES"] = str(max_cycles)
    env["PYC_TB_FAST"] = env.get("PYC_TB_FAST", "1")
    for k, v in params.items():
        env[f"PYC_PARAM_{k.upper()}"] = str(v)
    cmd = [str(RUN_CPP), str(memh)]
    p = subprocess.run(cmd, cwd=str(ROOT), env=env, capture_output=True, text=True)
    return {
        "params": params,
        "rc": p.returncode,
        "stdout_tail": "\n".join(p.stdout.splitlines()[-20:]),
        "stderr_tail": "\n".join(p.stderr.splitlines()[-20:]),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Frontend I-cache DSE harness (JIT/param sweep).")
    ap.add_argument("memh", type=Path)
    ap.add_argument("--max-cycles", type=int, default=200000)
    ap.add_argument("--ic-sets", default="32")
    ap.add_argument("--ic-ways", default="4")
    ap.add_argument("--ib-depth", default="8")
    ap.add_argument("--out", type=Path, default=ROOT / "generated" / "perf" / "dse_frontend_icache.json")
    args = ap.parse_args()

    if not args.memh.is_file():
        raise SystemExit(f"missing memh: {args.memh}")

    def parse_list(raw: str) -> list[int]:
        vals = []
        for x in raw.split(","):
            x = x.strip()
            if not x:
                continue
            vals.append(int(x, 0))
        return vals

    ic_sets = parse_list(args.ic_sets)
    ic_ways = parse_list(args.ic_ways)
    ib_depth = parse_list(args.ib_depth)

    results = []
    for s, w, d in itertools.product(ic_sets, ic_ways, ib_depth):
        params = {
            "ic_sets": s,
            "ic_ways": w,
            "ib_depth": d,
            "ic_line_bytes": 64,
            "ifetch_bundle_bytes": 128,
        }
        results.append(run_once(args.memh, params, args.max_cycles))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"results": results}, indent=2), encoding="utf-8")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

