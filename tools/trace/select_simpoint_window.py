#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def _to_int(v: Any, default: int = 0) -> int:
    if isinstance(v, int):
        return v
    if isinstance(v, str):
        try:
            return int(v, 0)
        except ValueError:
            return default
    return default


@dataclass
class CommitRow:
    seq: int
    pc: int
    next_pc: int
    insn: int
    length: int


def _load_rows(path: Path, max_commits: int) -> list[CommitRow]:
    rows: list[CommitRow] = []
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line_idx, raw in enumerate(f):
            line = raw.strip()
            if not line:
                continue
            obj = json.loads(line)
            seq = _to_int(obj.get("seq", len(rows)))
            rows.append(
                CommitRow(
                    seq=seq,
                    pc=_to_int(obj.get("pc", 0)),
                    next_pc=_to_int(obj.get("next_pc", 0)),
                    insn=_to_int(obj.get("insn", 0)),
                    length=_to_int(obj.get("len", 0)),
                )
            )
            if max_commits > 0 and len(rows) >= max_commits:
                break
    return rows


def _bb_key(r: CommitRow) -> str:
    # SimPoint-style BBV approximation from commit trace: use edge tuple.
    return f"{r.pc:016x}->{r.next_pc:016x}"


def _l1_distance_norm(local: Counter[str], local_n: int, global_hist: Counter[str], global_n: int) -> float:
    if local_n <= 0 or global_n <= 0:
        return 1.0
    keys = set(global_hist.keys()) | set(local.keys())
    s = 0.0
    for k in keys:
        p = float(local.get(k, 0)) / float(local_n)
        g = float(global_hist.get(k, 0)) / float(global_n)
        s += abs(p - g)
    return s


def _pick_windows(rows: list[CommitRow], interval: int, pick_count: int) -> list[dict[str, Any]]:
    if interval <= 0:
        raise ValueError("interval must be > 0")
    if not rows:
        return []

    global_hist: Counter[str] = Counter(_bb_key(r) for r in rows)
    global_n = len(rows)
    out: list[dict[str, Any]] = []

    n = len(rows)
    for start in range(0, n, interval):
        end = min(start + interval, n)
        window = rows[start:end]
        if not window:
            continue
        local_hist: Counter[str] = Counter(_bb_key(r) for r in window)
        local_n = len(window)
        score = _l1_distance_norm(local_hist, local_n, global_hist, global_n)
        top_edges = local_hist.most_common(8)
        out.append(
            {
                "interval_index": start // interval,
                "start_index": start,
                "end_index_exclusive": end,
                "commits": local_n,
                "start_seq": window[0].seq,
                "end_seq": window[-1].seq,
                "start_pc": window[0].pc,
                "end_pc": window[-1].pc,
                "score_l1": score,
                "weight": float(local_n) / float(global_n),
                "top_edges": [{"edge": e, "count": c} for e, c in top_edges],
            }
        )

    out.sort(key=lambda x: (x["score_l1"], x["start_seq"]))
    return out[: max(1, pick_count)]


def main() -> int:
    ap = argparse.ArgumentParser(
        description=(
            "Pick a SimPoint-style representative execution window from a commit trace. "
            "This is a lightweight BBV approximation over committed PC->next_pc edges."
        )
    )
    ap.add_argument("--trace", required=True, help="Input commit JSONL trace")
    ap.add_argument("--interval", type=int, default=1000, help="Window size in committed instructions")
    ap.add_argument("--pick-count", type=int, default=1, help="Number of best windows to emit")
    ap.add_argument("--max-commits", type=int, default=0, help="Optional read cap (0 = all)")
    ap.add_argument("--out", required=True, help="Output JSON file")
    ap.add_argument("--report", default="", help="Optional markdown report path")
    args = ap.parse_args()

    trace_path = Path(args.trace)
    out_path = Path(args.out)
    if not trace_path.is_file():
        raise SystemExit(f"error: trace not found: {trace_path}")
    rows = _load_rows(trace_path, args.max_commits)
    picks = _pick_windows(rows, args.interval, args.pick_count)

    payload = {
        "trace": str(trace_path),
        "interval": args.interval,
        "pick_count": args.pick_count,
        "rows": len(rows),
        "picked_windows": picks,
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    if args.report:
        md_path = Path(args.report)
        md: list[str] = []
        md.append("# SimPoint-Style Window Selection")
        md.append("")
        md.append(f"- Trace: `{trace_path}`")
        md.append(f"- Rows scanned: `{len(rows)}`")
        md.append(f"- Interval size: `{args.interval}`")
        md.append("")
        if not picks:
            md.append("No window selected.")
        else:
            md.append("## Top Windows")
            md.append("")
            md.append("| Rank | Interval | Seq Range | PC Range | Commits | L1 Score | Weight |")
            md.append("| --- | ---: | --- | --- | ---: | ---: | ---: |")
            for rank, p in enumerate(picks, start=1):
                md.append(
                    "| "
                    f"{rank} | {p['interval_index']} | "
                    f"{p['start_seq']}..{p['end_seq']} | "
                    f"0x{p['start_pc']:x}..0x{p['end_pc']:x} | "
                    f"{p['commits']} | {p['score_l1']:.6f} | {p['weight']:.4f} |"
                )
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text("\n".join(md) + "\n", encoding="utf-8")

    print(f"rows={len(rows)}")
    print(f"selected={len(picks)}")
    if picks:
        first = picks[0]
        print(
            "best_window="
            f"interval={first['interval_index']} "
            f"seq={first['start_seq']}..{first['end_seq']} "
            f"pc=0x{first['start_pc']:x}..0x{first['end_pc']:x} "
            f"score={first['score_l1']:.6f}"
        )
    print(f"out={out_path}")
    if args.report:
        print(f"report={args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

