#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser(description="Inspect LinxTrace rows/stages quickly.")
    ap.add_argument("trace", help="Path to .linxtrace.jsonl")
    ap.add_argument("--top", type=int, default=12, help="Number of rows to print")
    args = ap.parse_args()

    p = Path(args.trace)
    if not p.exists():
        raise SystemExit(f"missing trace: {p}")

    stage_hist = Counter()
    row_left = {}
    row_occ = Counter()
    row_retire = Counter()
    total = 0
    with p.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            total += 1
            t = rec.get("type")
            if t == "LABEL" and rec.get("label_type") == "left":
                row_left[int(rec["row_id"])] = str(rec.get("text", ""))
            elif t == "OCC":
                rid = int(rec["row_id"])
                row_occ[rid] += 1
                stage_hist[str(rec.get("stage_id", ""))] += 1
            elif t == "RETIRE":
                row_retire[int(rec["row_id"])] += 1

    print(f"linxtrace-debug total_events={total} rows={len(row_left)} unique_stages={len(stage_hist)}")
    print("top stages:")
    for st, n in stage_hist.most_common(16):
        print(f"  {st}: {n}")
    print("top rows:")
    top = sorted(row_occ.items(), key=lambda x: (-x[1], x[0]))[: max(1, args.top)]
    for rid, n in top:
        lbl = row_left.get(rid, "")
        retired = row_retire.get(rid, 0)
        print(f"  row={rid} occ={n} retire={retired} label={lbl[:120]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

