#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def stage_map(profile: dict) -> dict[str, float]:
    out: dict[str, float] = {}
    for row in profile.get("stages", []):
        name = str(row.get("stage", ""))
        real = row.get("real_s")
        if name and isinstance(real, (int, float)):
            out[name] = float(real)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Warn on pyCircuit compile latency regressions")
    ap.add_argument("--profile-json", required=True, help="Current profile JSON")
    ap.add_argument("--baseline-json", required=False, help="Baseline profile JSON")
    ap.add_argument("--max-regress-pct", type=float, default=25.0, help="Regression threshold percent")
    ap.add_argument("--strict", action="store_true", help="Exit non-zero when threshold is exceeded")
    args = ap.parse_args()

    current = json.loads(Path(args.profile_json).read_text(encoding="utf-8"))
    curr = stage_map(current)
    if not args.baseline_json:
        print("warn: no baseline provided; skipping regression comparison")
        return 0

    baseline = json.loads(Path(args.baseline_json).read_text(encoding="utf-8"))
    base = stage_map(baseline)
    fails: list[str] = []

    for stage, curr_s in sorted(curr.items()):
        base_s = base.get(stage)
        if base_s is None or base_s <= 0:
            print(f"warn: baseline missing stage={stage}")
            continue
        delta_pct = ((curr_s - base_s) / base_s) * 100.0
        msg = f"stage={stage} current={curr_s:.3f}s baseline={base_s:.3f}s delta={delta_pct:+.2f}%"
        if delta_pct > args.max_regress_pct:
            print(f"warn: regression {msg}")
            fails.append(msg)
        else:
            print(f"ok: {msg}")

    if fails and args.strict:
        print(f"error: {len(fails)} stage(s) exceeded threshold")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
