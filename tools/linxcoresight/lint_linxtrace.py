#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

ROOT_DIR = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT_DIR / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from common.stage_tokens import (
    LINXTRACE_PIPELINE_SCHEMA_ID,
    LINXTRACE_STAGE_ID_ORDER,
    LINXTRACE_STAGE_ORDER_CSV,
)

STAGE_ORDER = list(LINXTRACE_STAGE_ID_ORDER)
STAGE_RANK = {stage: idx for idx, stage in enumerate(STAGE_ORDER)}
TERMINAL_STAGES = {"CMT", "FLS", "XCHK"}


def _contract_id(stage_ids: List[str], lane_ids: List[str], row_schema: List[Tuple[int, str]], schema_id: str) -> str:
    seed = (
        f"{schema_id}|"
        f"{','.join(stage_ids)}|"
        f"{','.join(lane_ids)}|"
        f"{';'.join(f'{rid}:{kind}' for rid, kind in row_schema)}|"
        "linxtrace.v1"
    )
    h = 1469598103934665603
    for byte in seed.encode("utf-8"):
        h ^= byte
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return f"{schema_id}-{h:016X}"


def _read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SystemExit(f"invalid JSON: {path} ({exc})") from exc


def _read_trace(trace_path: Path) -> Tuple[dict, List[dict]]:
    if not trace_path.exists():
        raise SystemExit(f"missing LinxTrace file: {trace_path}")

    meta = None
    events: List[dict] = []
    with trace_path.open("r", encoding="utf-8", errors="replace") as handle:
        for lineno, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except Exception as exc:
                raise SystemExit(f"line {lineno}: invalid JSON ({exc})") from exc
            if not isinstance(record, dict):
                raise SystemExit(f"line {lineno}: event must be a JSON object")
            record["_lineno"] = lineno
            if meta is None:
                if record.get("type") != "META":
                    raise SystemExit("first non-empty record must be META")
                record.pop("_lineno", None)
                meta = record
                continue
            if record.get("type") == "META":
                raise SystemExit(f"line {lineno}: duplicate META record")
            events.append(record)

    if meta is None:
        raise SystemExit("trace missing META record")
    return meta, events


def main() -> int:
    parser = argparse.ArgumentParser(description="Strict canonical LinxTrace v1 linter.")
    parser.add_argument("trace", help="Path to *.linxtrace")
    parser.add_argument("--meta", default="", help="Optional sidecar meta path to compare against in-band META.")
    parser.add_argument("--require-stages", default="", help="Comma-separated stages that must appear in OCC.")
    parser.add_argument(
        "--single-stage-per-cycle",
        action="store_true",
        help="Deprecated compatibility flag. Single-stage-per-cycle is always enforced.",
    )
    args = parser.parse_args()

    trace_path = Path(args.trace)
    meta, events = _read_trace(trace_path)
    if args.meta:
        sidecar = _read_json(Path(args.meta))
        if meta != sidecar:
            raise SystemExit("sidecar meta does not match in-band META")

    if meta.get("format") != "linxtrace.v1":
        raise SystemExit(f"meta format mismatch: expected linxtrace.v1, got {meta.get('format')!r}")
    if meta.get("pipeline_schema_id") != LINXTRACE_PIPELINE_SCHEMA_ID:
        raise SystemExit(
            "pipeline_schema_id mismatch: "
            f"got {meta.get('pipeline_schema_id')!r} expected {LINXTRACE_PIPELINE_SCHEMA_ID!r}"
        )
    if meta.get("stage_order_csv") != LINXTRACE_STAGE_ORDER_CSV:
        raise SystemExit(
            f"stage_order_csv mismatch: got {meta.get('stage_order_csv')!r} expected {LINXTRACE_STAGE_ORDER_CSV!r}"
        )

    stage_catalog = meta.get("stage_catalog")
    lane_catalog = meta.get("lane_catalog")
    row_catalog = meta.get("row_catalog")
    contract_id = str(meta.get("contract_id", ""))
    if not isinstance(stage_catalog, list) or not stage_catalog:
        raise SystemExit("meta missing non-empty stage_catalog")
    if not isinstance(lane_catalog, list):
        raise SystemExit("meta missing lane_catalog")
    if not isinstance(row_catalog, list) or not row_catalog:
        raise SystemExit("meta missing non-empty row_catalog")
    if not contract_id:
        raise SystemExit("meta missing contract_id")

    stage_ids = [str(item.get("stage_id", "")) for item in stage_catalog]
    if stage_ids != STAGE_ORDER:
        raise SystemExit(f"stage_catalog mismatch: got {stage_ids} expected {STAGE_ORDER}")
    lane_ids = [str(item.get("lane_id", "")) for item in lane_catalog if str(item.get("lane_id", ""))]
    lane_set = set(lane_ids)

    row_schema: List[Tuple[int, str]] = []
    row_ids_meta = set()
    for row in row_catalog:
        try:
            row_id = int(row.get("row_id"))
        except Exception:
            raise SystemExit(f"invalid row_id in row_catalog: {row!r}")
        row_kind = str(row.get("row_kind", ""))
        if row_kind not in {"uop", "block"}:
            raise SystemExit(f"row_catalog row_id={row_id} has forbidden row_kind={row_kind!r}")
        row_schema.append((row_id, row_kind))
        row_ids_meta.add(row_id)
        if str(row.get("entity_kind", "")) == "packet":
            raise SystemExit(f"row_catalog row_id={row_id} must not describe packets")

    expected_contract = _contract_id(STAGE_ORDER, lane_ids, sorted(row_schema), LINXTRACE_PIPELINE_SCHEMA_ID)
    if expected_contract != contract_id:
        raise SystemExit(
            f"contract mismatch: meta={contract_id} expected={expected_contract}. "
            "Refresh trace emitter + renderer contract together."
        )

    allowed_types = {"OP_DEF", "LABEL", "OCC", "RETIRE", "BLOCK_EVT", "XCHECK", "DEP"}
    row_ids_defined = set()
    row_kind_by_id: Dict[int, str] = {}
    row_entity_by_id: Dict[int, str] = {}
    row_cycle_stage: Dict[Tuple[int, int], str] = {}
    row_occ: Dict[int, List[Tuple[int, str, int]]] = defaultdict(list)
    retire_by_row: Dict[int, List[Tuple[int, str]]] = defaultdict(list)
    stage_hist = Counter()
    occ_count = 0
    retire_count = 0
    block_evt_count = 0

    for record in events:
        lineno = int(record.get("_lineno", 0))
        rtype = str(record.get("type", ""))
        if rtype not in allowed_types:
            raise SystemExit(f"line {lineno}: unknown event type {rtype!r}")

        if rtype in {"OP_DEF", "LABEL", "OCC", "RETIRE", "XCHECK"}:
            if "row_id" not in record:
                raise SystemExit(f"line {lineno}: {rtype} missing row_id")
            try:
                row_id = int(record.get("row_id"))
            except Exception:
                raise SystemExit(f"line {lineno}: {rtype} row_id must be integer")
            if row_id not in row_ids_meta:
                raise SystemExit(f"line {lineno}: {rtype} references unknown row_id={row_id}")

        if rtype == "OP_DEF":
            row_id = int(record["row_id"])
            row_kind = str(record.get("row_kind", ""))
            if row_kind not in {"uop", "block"}:
                raise SystemExit(f"line {lineno}: OP_DEF row_id={row_id} has forbidden row_kind={row_kind!r}")
            if str(record.get("kind", "")) == "packet":
                raise SystemExit(f"line {lineno}: OP_DEF row_id={row_id} must not be packet kind")
            row_ids_defined.add(row_id)
            row_kind_by_id[row_id] = row_kind
            row_entity_by_id[row_id] = str(record.get("kind", "normal"))

        elif rtype == "LABEL":
            row_id = int(record["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: LABEL before OP_DEF for row_id={row_id}")
            label_type = str(record.get("label_type", ""))
            if label_type not in {"left", "detail"}:
                raise SystemExit(f"line {lineno}: LABEL has invalid label_type={label_type!r}")

        elif rtype == "OCC":
            row_id = int(record["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: OCC before OP_DEF for row_id={row_id}")
            stage = str(record.get("stage_id", ""))
            lane = str(record.get("lane_id", ""))
            if stage not in STAGE_RANK:
                raise SystemExit(f"line {lineno}: OCC has unknown stage_id={stage!r}")
            if lane not in lane_set:
                raise SystemExit(f"line {lineno}: OCC has unknown lane_id={lane!r}")
            try:
                cycle = int(record.get("cycle"))
            except Exception:
                raise SystemExit(f"line {lineno}: OCC cycle must be integer")
            key = (row_id, cycle)
            if key in row_cycle_stage:
                raise SystemExit(
                    f"line {lineno}: duplicate OCC for row_id={row_id} cycle={cycle}: "
                    f"{row_cycle_stage[key]} and {stage}"
                )
            row_cycle_stage[key] = stage
            row_occ[row_id].append((cycle, stage, lineno))
            stage_hist[stage] += 1
            occ_count += 1

        elif rtype == "RETIRE":
            row_id = int(record["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: RETIRE before OP_DEF for row_id={row_id}")
            try:
                cycle = int(record.get("cycle"))
            except Exception:
                raise SystemExit(f"line {lineno}: RETIRE cycle must be integer")
            status = str(record.get("status", ""))
            retire_by_row[row_id].append((cycle, status))
            retire_count += 1

        elif rtype == "XCHECK":
            row_id = int(record["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: XCHECK before OP_DEF for row_id={row_id}")

        elif rtype == "BLOCK_EVT":
            block_evt_count += 1
            for key in ("kind", "block_uid", "core_id", "cycle"):
                if key not in record:
                    raise SystemExit(f"line {lineno}: BLOCK_EVT missing {key}")

    if occ_count == 0:
        raise SystemExit("trace has zero OCC events")
    if retire_count == 0:
        raise SystemExit("trace has zero RETIRE events")

    required = [stage.strip() for stage in args.require_stages.split(",") if stage.strip()]
    for stage in required:
        if stage_hist.get(stage, 0) == 0:
            raise SystemExit(f"missing required stage: {stage}")

    for row_id in sorted(row_ids_meta):
        occs = sorted(row_occ.get(row_id, []))
        if not occs:
            raise SystemExit(f"row_id={row_id} has no OCC records")

        last_cycle = -1
        last_rank = -1
        last_stage = ""
        for cycle, stage, lineno in occs:
            rank = STAGE_RANK[stage]
            if cycle <= last_cycle:
                raise SystemExit(f"line {lineno}: row_id={row_id} cycles are not strictly increasing")
            if last_rank > rank:
                raise SystemExit(
                    f"line {lineno}: row_id={row_id} regressed from stage {last_stage} to {stage} "
                    f"({last_cycle}->{cycle})"
                )
            last_cycle = cycle
            last_rank = rank
            last_stage = stage

        terminal_hits = [(cycle, stage) for cycle, stage, _ in occs if stage in {"FLS", "XCHK"}]
        if terminal_hits:
            terminal_cycle, terminal_stage = terminal_hits[-1]
            if terminal_cycle != occs[-1][0] or terminal_stage != occs[-1][1]:
                raise SystemExit(
                    f"row_id={row_id} has occupancy after terminal stage {terminal_stage} at cycle {terminal_cycle}"
                )

        row_kind = row_kind_by_id.get(row_id, "uop")
        row_entity = row_entity_by_id.get(row_id, "normal")
        if row_kind == "uop" and row_entity in {"flush", "replay"} and occs[-1][1] != "FLS":
            raise SystemExit(f"row_id={row_id} is {row_entity} but does not terminate at FLS")

        retires = retire_by_row.get(row_id, [])
        if len(retires) > 1:
            raise SystemExit(f"row_id={row_id} has multiple RETIRE events")
        if retires:
            retire_cycle, retire_status = retires[0]
            if retire_cycle < occs[-1][0]:
                raise SystemExit(
                    f"row_id={row_id} retires at cycle {retire_cycle} before final occupancy cycle {occs[-1][0]}"
                )
            if occs[-1][1] == "CMT":
                if retire_status not in {"ok", "terminal", "trap"}:
                    raise SystemExit(f"row_id={row_id} has invalid CMT retire status {retire_status!r}")
            elif occs[-1][1] in {"FLS", "XCHK"}:
                if retire_status in {"ok"}:
                    raise SystemExit(f"row_id={row_id} has terminal stage {occs[-1][1]} but retire status is ok")
            else:
                raise SystemExit(
                    f"row_id={row_id} retires without terminal visible stage (last stage={occs[-1][1]!r})"
                )
        elif occs[-1][1] in TERMINAL_STAGES:
            raise SystemExit(f"row_id={row_id} ends at terminal stage {occs[-1][1]} but has no RETIRE")

    print(
        "linxtrace-ok "
        f"rows={len(row_ids_defined)} occ={occ_count} retire={retire_count} "
        f"stages={len(stage_hist)} block_evt={block_evt_count} contract={contract_id}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
