#!/usr/bin/env python3
from __future__ import annotations

import argparse
import collections
import re
from pathlib import Path

REQ_STAGES = [
    "F0",
    "F1",
    "F2",
    "F3",
    "F4",
    "D1",
    "D2",
    "D3",
    "IQ",
    "S1",
    "S2",
    "P1",
    "I1",
    "I2",
    "E1",
    "E2",
    "E3",
    "E4",
    "W1",
    "W2",
    "LIQ",
    "LHQ",
    "STQ",
    "SCB",
    "MDB",
    "L1D",
    "BISQ",
    "BCTRL",
    "TMU",
    "TMA",
    "CUBE",
    "VEC",
    "TAU",
    "BROB",
    "ROB",
    "CMT",
    "FLS",
    "XCHK",
]
REQ_STAGE_SET = set(REQ_STAGES)
LANE_RE = re.compile(r"^c\d+\.(?:l\d+|blk)$")
LABEL_RE = re.compile(r"^0x[0-9A-F]+(?:\s+<[^>]+>)?:\s+\S.*$")
BLOCK_LABEL_RE = re.compile(r"^(?:0x[0-9A-F]+\s+)?BLOCK(?:\s+\S.*)?$")
GEN_RE = re.compile(r"^GEN\s+seq=\S+\s+op=\S+")
GEN_ASM_RE = re.compile(r"^u\d+:\s+\S.*$")


class TraceError(RuntimeError):
    pass


def fail(lineno: int, message: str, kid: int | None = None, label: str | None = None) -> TraceError:
    bits = [f"line={lineno}", message]
    if kid is not None:
        bits.append(f"kid={kid}")
    if label:
        bits.append(f"label={label}")
    return TraceError(" | ".join(bits))


def parse_int(text: str, what: str, lineno: int) -> int:
    try:
        return int(text, 0)
    except Exception as exc:
        raise fail(lineno, f"invalid {what}: {text!r}") from exc


def main() -> int:
    ap = argparse.ArgumentParser(description="Strict LinxCore v0005 Konata lint and stage coverage check.")
    ap.add_argument("trace", type=Path)
    ap.add_argument("--require-flush", action="store_true")
    ap.add_argument("--require-all-stages", action="store_true")
    ap.add_argument("--require-stages", default="")
    ap.add_argument("--require-template", action="store_true")
    ap.add_argument("--require-branch-flow", action="store_true")
    ap.add_argument("--single-stage-per-cycle", action="store_true")
    ap.add_argument("--top", type=int, default=12, help="top-N stage histogram count to print")
    args = ap.parse_args()

    if not args.trace.is_file():
        raise SystemExit(f"missing konata trace: {args.trace}")

    rows: dict[int, dict] = {}
    stage_hist: collections.Counter[str] = collections.Counter()
    retired = 0
    flush_seen = False
    template_seen = False
    branch_ctx_seen = False
    cycle = 0
    header_seen = False
    occ_cycle_uid: dict[str, tuple[str, int]] = {}

    with args.trace.open("r", encoding="utf-8", errors="replace") as f:
        for lineno, raw in enumerate(f, start=1):
            line = raw.rstrip("\n")
            if not line:
                continue
            cols = line.split("\t")
            rec = cols[0]
            if not header_seen:
                header_seen = True
                if rec != "Kanata" or len(cols) < 2 or cols[1].strip() != "0005":
                    raise SystemExit(f"line={lineno} | invalid header, expected 'Kanata\\t0005'")
                continue

            if rec == "C=":
                if len(cols) < 2:
                    raise SystemExit(f"line={lineno} | malformed C= record")
                cycle = parse_int(cols[1], "C= cycle", lineno)
                occ_cycle_uid.clear()
                continue
            if rec == "C":
                if len(cols) < 2:
                    raise SystemExit(f"line={lineno} | malformed C record")
                delta = parse_int(cols[1], "cycle delta", lineno)
                if delta < 0:
                    raise SystemExit(f"line={lineno} | negative cycle delta {delta}")
                cycle += delta
                occ_cycle_uid.clear()
                continue

            if rec not in {"I", "L", "P", "R"}:
                raise SystemExit(f"line={lineno} | unsupported command in strict mode: {rec}")
            if len(cols) < 2:
                raise SystemExit(f"line={lineno} | missing kid in command {rec}")
            kid = parse_int(cols[1], "kid", lineno)

            row = rows.get(kid)
            if rec == "I":
                if len(cols) < 6:
                    raise SystemExit(f"line={lineno} | malformed I record, expected 6 fields")
                if row is not None:
                    raise SystemExit(str(fail(lineno, "duplicate I record", kid=kid, label=row.get("label0"))))
                uid = cols[2].strip().lower()
                if not re.fullmatch(r"0x[0-9a-f]+", uid):
                    raise SystemExit(f"line={lineno} | invalid uid field: {uid!r}")
                parent_uid = cols[4].strip().lower()
                if not re.fullmatch(r"0x[0-9a-f]+", parent_uid):
                    raise SystemExit(f"line={lineno} | invalid parent uid field: {parent_uid!r}")
                rows[kid] = {
                    "uid": uid,
                    "kind": cols[5].strip().lower(),
                    "label0": "",
                    "has_p": False,
                    "retired": False,
                    "retire_line": 0,
                }
                continue

            if row is None:
                raise SystemExit(str(fail(lineno, f"{rec} references undefined kid", kid=kid)))

            if row["retired"]:
                raise SystemExit(
                    str(fail(lineno, f"command {rec} appears after retire", kid=kid, label=row.get("label0")))
                )

            if rec == "L":
                if len(cols) < 4:
                    raise SystemExit(str(fail(lineno, "malformed L record", kid=kid, label=row.get("label0"))))
                ltype = parse_int(cols[2], "label type", lineno)
                text = cols[3]
                if ltype == 0:
                    row["label0"] = text
                    if row["kind"] == "block":
                        if not BLOCK_LABEL_RE.match(text):
                            raise SystemExit(str(fail(lineno, "invalid block label format", kid=kid, label=text)))
                    else:
                        if not (LABEL_RE.match(text) or GEN_RE.match(text) or GEN_ASM_RE.match(text)):
                            raise SystemExit(str(fail(lineno, "invalid uop label format", kid=kid, label=text)))
                elif ltype == 1 and "bru_validate=" in text and "boundary_pc=" in text:
                    branch_ctx_seen = True
                continue

            if rec == "P":
                if len(cols) < 6:
                    raise SystemExit(str(fail(lineno, "malformed P record", kid=kid, label=row.get("label0"))))
                lane = cols[2].strip()
                stage = cols[3].strip().upper()
                if not LANE_RE.fullmatch(lane):
                    raise SystemExit(str(fail(lineno, f"invalid lane token {lane!r}", kid=kid, label=row.get("label0"))))
                if stage not in REQ_STAGE_SET:
                    raise SystemExit(str(fail(lineno, f"invalid stage token {stage!r}", kid=kid, label=row.get("label0"))))
                stall = parse_int(cols[4], "stall", lineno)
                if stall < 0:
                    raise SystemExit(str(fail(lineno, f"negative stall value {stall}", kid=kid, label=row.get("label0"))))
                row["has_p"] = True
                stage_hist[stage] += 1
                if stage == "FLS":
                    flush_seen = True
                if args.single_stage_per_cycle and row["kind"] != "block":
                    uid = row["uid"]
                    prev = occ_cycle_uid.get(uid)
                    if prev is not None and prev[0] != stage:
                        raise SystemExit(
                            str(
                                fail(
                                    lineno,
                                    f"uid appears in multiple stages in same cycle {cycle}: {prev[0]} vs {stage}",
                                    kid=kid,
                                    label=row.get("label0"),
                                )
                            )
                        )
                    occ_cycle_uid[uid] = (stage, lineno)
                continue

            if rec == "R":
                if len(cols) < 4:
                    raise SystemExit(str(fail(lineno, "malformed R record", kid=kid, label=row.get("label0"))))
                if not row["has_p"]:
                    raise SystemExit(str(fail(lineno, "R before any P", kid=kid, label=row.get("label0"))))
                _rid = parse_int(cols[2], "rid", lineno)
                rtype = parse_int(cols[3], "retire type", lineno)
                if rtype not in (0, 1):
                    raise SystemExit(str(fail(lineno, f"invalid retire type {rtype}", kid=kid, label=row.get("label0"))))
                row["retired"] = True
                row["retire_line"] = lineno
                retired += 1
                continue

    if not header_seen:
        raise SystemExit("missing Konata header")

    if not rows:
        raise SystemExit("trace has no instruction rows")

    for kid, row in rows.items():
        if not row["has_p"]:
            raise SystemExit(str(fail(row.get("retire_line", 0) or 0, "row has no P occupancy", kid=kid, label=row.get("label0"))))

    for row in rows.values():
        lo = row.get("label0", "").lower()
        if "fentry" in lo or "fexit" in lo or "fret" in lo:
            template_seen = True
            break

    required = ["F0", "F1", "D3", "IQ", "ROB", "CMT"]
    if args.require_all_stages:
        required = REQ_STAGES
    elif args.require_stages.strip():
        required = [s.strip().upper() for s in args.require_stages.split(",") if s.strip()]
    missing = [s for s in required if s not in stage_hist]
    if missing:
        raise SystemExit("missing required stages: " + ", ".join(missing))

    if args.require_flush and not flush_seen:
        raise SystemExit("required flush stage not present")
    if args.require_template and not template_seen:
        raise SystemExit("required template-uop labels not present")
    if args.require_branch_flow:
        branch_need = ["ROB", "CMT", "FLS"]
        missing_branch = [s for s in branch_need if s not in stage_hist]
        if missing_branch:
            raise SystemExit("missing branch-flow stages: " + ", ".join(missing_branch))
        if not branch_ctx_seen:
            raise SystemExit("required branch-flow mismatch context labels not present")

    print(
        f"konata-ok ids={len(rows)} retired={retired} unique_stages={len(stage_hist)} "
        f"flush={int(flush_seen)} template={int(template_seen)}"
    )
    print("stage-hist-top:")
    for stage, cnt in stage_hist.most_common(args.top):
        print(f"  {stage}: {cnt}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
