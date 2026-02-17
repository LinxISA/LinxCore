#!/usr/bin/env python3
from __future__ import annotations

import argparse
import collections
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser(description="Inspect Konata v0005 trace rows/stages for rendering debug.")
    ap.add_argument("trace", type=Path)
    ap.add_argument("--top", type=int, default=20, help="Number of rows to print in summaries.")
    ap.add_argument("--show-empty", action="store_true", help="Print rows with no P-stage events.")
    ap.add_argument(
        "--require-stages",
        default="",
        help="Comma-separated stage list that must exist in the trace (e.g. F0,D3,IQ,ROB,CMT).",
    )
    args = ap.parse_args()

    if not args.trace.is_file():
        raise SystemExit(f"missing trace: {args.trace}")

    rows = {}
    stage_hist = collections.Counter()
    total_p = 0
    total_r = 0

    with args.trace.open("r", encoding="utf-8", errors="replace") as f:
        for lineno, line in enumerate(f, start=1):
            cols = line.rstrip("\n").split("\t")
            if not cols:
                continue
            rec = cols[0]
            if rec == "I" and len(cols) >= 6:
                kid = int(cols[1], 0)
                rows.setdefault(
                    kid,
                    {
                        "kind": cols[5].strip(),
                        "uid": cols[2].strip(),
                        "label0": "",
                        "label1": "",
                        "p_count": 0,
                        "stages": set(),
                        "retired": False,
                    },
                )
            elif rec == "L" and len(cols) >= 4:
                kid = int(cols[1], 0)
                row = rows.setdefault(
                    kid,
                    {"kind": "?", "uid": "?", "label0": "", "label1": "", "p_count": 0, "stages": set(), "retired": False},
                )
                if cols[2] == "0":
                    row["label0"] = cols[3]
                elif cols[2] == "1":
                    row["label1"] = cols[3]
            elif rec == "P" and len(cols) >= 4:
                kid = int(cols[1], 0)
                stage = cols[3].strip()
                row = rows.setdefault(
                    kid,
                    {"kind": "?", "uid": "?", "label0": "", "label1": "", "p_count": 0, "stages": set(), "retired": False},
                )
                row["p_count"] += 1
                row["stages"].add(stage)
                stage_hist[stage] += 1
                total_p += 1
            elif rec == "R" and len(cols) >= 2:
                kid = int(cols[1], 0)
                row = rows.setdefault(
                    kid,
                    {"kind": "?", "uid": "?", "label0": "", "label1": "", "p_count": 0, "stages": set(), "retired": False},
                )
                row["retired"] = True
                total_r += 1

    required = [s.strip() for s in args.require_stages.split(",") if s.strip()]
    missing = [s for s in required if s not in stage_hist]
    if missing:
        raise SystemExit("missing required stages: " + ", ".join(missing))

    rows_sorted = sorted(rows.items(), key=lambda kv: kv[0])
    empty_rows = [(kid, row) for kid, row in rows_sorted if row["p_count"] == 0]
    rows_with_p = len(rows_sorted) - len(empty_rows)
    print(
        f"trace={args.trace} rows={len(rows_sorted)} rows_with_stage={rows_with_p} "
        f"rows_without_stage={len(empty_rows)} P={total_p} R={total_r} unique_stages={len(stage_hist)}"
    )

    print("top_stage_hist:")
    for stage, cnt in stage_hist.most_common(args.top):
        print(f"  {stage}: {cnt}")

    print("first_rows:")
    shown = 0
    for kid, row in rows_sorted:
        st = ",".join(sorted(row["stages"])) if row["stages"] else "-"
        print(
            f"  kid={kid} kind={row['kind']} p={row['p_count']} retired={int(row['retired'])} "
            f"stages=[{st}] label={row['label0']}"
        )
        shown += 1
        if shown >= args.top:
            break

    if args.show_empty and empty_rows:
        print("rows_without_stage:")
        for kid, row in empty_rows[: args.top]:
            print(f"  kid={kid} kind={row['kind']} label={row['label0']}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
