#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Set, Tuple


def _contract_id(stage_ids: List[str], lane_ids: List[str], row_schema: List[Tuple[int, str]], schema_id: str) -> str:
    seed = (
        f"{schema_id}|"
        f"{','.join(stage_ids)}|"
        f"{','.join(lane_ids)}|"
        f"{';'.join(f'{rid}:{kind}' for rid, kind in row_schema)}|"
        "linxtrace.v1"
    )
    h = 1469598103934665603
    for b in seed.encode("utf-8"):
        h ^= b
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return f"{schema_id}-{h:016X}"


def _read_meta_file(meta_path: Path) -> dict:
    if not meta_path.exists():
        raise SystemExit(f"missing meta file: {meta_path}")
    try:
        return json.loads(meta_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SystemExit(f"invalid meta JSON: {meta_path} ({exc})") from exc


def _read_trace_with_meta(trace_path: Path, meta_path: Path | None) -> tuple[dict, List[dict]]:
    if not trace_path.exists():
        raise SystemExit(f"missing LinxTrace file: {trace_path}")
    embedded_meta: dict | None = None
    events: List[dict] = []
    with trace_path.open("r", encoding="utf-8", errors="replace") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except Exception as exc:
                raise SystemExit(f"line {lineno}: invalid JSON ({exc})") from exc
            if not isinstance(rec, dict):
                raise SystemExit(f"line {lineno}: event must be a JSON object")
            if embedded_meta is None and rec.get("type") == "META":
                embedded_meta = dict(rec)
                embedded_meta.pop("type", None)
                continue
            rec["_lineno"] = lineno
            events.append(rec)
    if embedded_meta is not None:
        return embedded_meta, events
    if meta_path is not None:
        return _read_meta_file(meta_path), events
    raise SystemExit(
        f"missing in-band META record in trace and no --meta supplied: {trace_path}"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="Strict LinxTrace v1 linter.")
    ap.add_argument("trace", help="Path to *.linxtrace")
    ap.add_argument("--meta", default="", help="Optional explicit sidecar meta path (legacy/debug)")
    ap.add_argument("--require-stages", default="", help="Comma-separated stages that must appear in OCC.")
    ap.add_argument("--single-stage-per-cycle", action="store_true", help="Fail if a row appears in multiple stages in one cycle.")
    args = ap.parse_args()

    trace_path = Path(args.trace)
    meta_path = Path(args.meta) if args.meta else None
    meta, events = _read_trace_with_meta(trace_path, meta_path)

    if meta.get("format") != "linxtrace.v1":
        raise SystemExit(f"meta format mismatch: expected linxtrace.v1, got {meta.get('format')!r}")

    stage_catalog = meta.get("stage_catalog")
    lane_catalog = meta.get("lane_catalog")
    row_catalog = meta.get("row_catalog")
    schema_id = str(meta.get("pipeline_schema_id", ""))
    contract_id = str(meta.get("contract_id", ""))
    if not isinstance(stage_catalog, list) or not stage_catalog:
        raise SystemExit("meta missing non-empty stage_catalog")
    if not isinstance(lane_catalog, list):
        raise SystemExit("meta missing lane_catalog")
    if not isinstance(row_catalog, list) or not row_catalog:
        raise SystemExit("meta missing non-empty row_catalog")
    if not schema_id:
        raise SystemExit("meta missing pipeline_schema_id")
    if not contract_id:
        raise SystemExit("meta missing contract_id")

    stage_ids = [str(x.get("stage_id", "")) for x in stage_catalog if str(x.get("stage_id", ""))]
    lane_ids = [str(x.get("lane_id", "")) for x in lane_catalog if str(x.get("lane_id", ""))]
    row_schema: List[Tuple[int, str]] = []
    row_ids_meta: Set[int] = set()
    row_sid_seen: Set[str] = set()
    row_sid_meta: Dict[int, str] = {}
    row_kind_meta: Dict[int, str] = {}
    for row in row_catalog:
        try:
            row_id = int(row.get("row_id"))
        except Exception:
            raise SystemExit(f"invalid row_id in row_catalog: {row!r}")
        row_sid = str(row.get("row_sid", "")).strip()
        if not row_sid:
            raise SystemExit(f"row_catalog row_id={row_id} missing row_sid")
        row_kind = str(row.get("row_kind", ""))
        if not row_kind:
            raise SystemExit(f"row_catalog row_id={row_id} missing row_kind")
        entity_kind = str(row.get("entity_kind", "")).strip()
        if not entity_kind:
            raise SystemExit(f"row_catalog row_id={row_id} missing entity_kind")
        lifecycle_flags = row.get("lifecycle_flags")
        if not isinstance(lifecycle_flags, list) or not lifecycle_flags:
            raise SystemExit(f"row_catalog row_id={row_id} missing lifecycle_flags list")
        order_key = str(row.get("order_key", "")).strip()
        if not order_key:
            raise SystemExit(f"row_catalog row_id={row_id} missing order_key")
        id_refs = row.get("id_refs")
        if not isinstance(id_refs, dict):
            raise SystemExit(f"row_catalog row_id={row_id} missing id_refs object")
        for k in ("uop_uid", "block_uid", "block_bid"):
            if k not in id_refs:
                raise SystemExit(f"row_catalog row_id={row_id} id_refs missing {k}")
        row_schema.append((row_id, row_kind))
        row_ids_meta.add(row_id)
        row_kind_meta[row_id] = row_kind
        if row_sid in row_sid_seen:
            raise SystemExit(f"duplicate row_sid in row_catalog: {row_sid!r}")
        row_sid_seen.add(row_sid)
        row_sid_meta[row_id] = row_sid

    expected_contract = _contract_id(stage_ids, lane_ids, sorted(row_schema), schema_id)
    if expected_contract != contract_id:
        raise SystemExit(
            f"contract mismatch: meta={contract_id} expected={expected_contract}. "
            "Refresh trace emitter + renderer contract together."
        )

    allowed_types = {"OP_DEF", "LABEL", "OCC", "RETIRE", "BLOCK_EVT", "XCHECK", "DEP"}
    row_ids_defined: Set[int] = set()
    occ_count = 0
    retire_count = 0
    stage_hist = Counter()
    row_cycle_stage: Dict[Tuple[int, int], Set[str]] = defaultdict(set)
    line_by_row: Dict[int, int] = {}
    retire_by_row: Dict[int, int] = defaultdict(int)
    seen_block_evt = 0

    stage_set = set(stage_ids)
    lane_set = set(lane_ids)

    for rec in events:
        lineno = int(rec.get("_lineno", 0))
        rtype = str(rec.get("type", ""))
        if rtype not in allowed_types:
            raise SystemExit(f"line {lineno}: unknown event type {rtype!r}")

        if rtype in {"OP_DEF", "LABEL", "OCC", "RETIRE", "XCHECK"}:
            if "row_id" not in rec:
                raise SystemExit(f"line {lineno}: {rtype} missing row_id")
            try:
                row_id = int(rec.get("row_id"))
            except Exception:
                raise SystemExit(f"line {lineno}: {rtype} row_id must be integer")
            if row_id not in row_ids_meta:
                raise SystemExit(f"line {lineno}: {rtype} references unknown row_id={row_id}")
            row_sid = str(rec.get("row_sid", "")).strip()
            if row_sid and row_sid != row_sid_meta.get(row_id, ""):
                raise SystemExit(
                    f"line {lineno}: {rtype} row_sid mismatch for row_id={row_id}: "
                    f"got={row_sid!r} exp={row_sid_meta.get(row_id, '')!r}"
                )

        if rtype == "OP_DEF":
            row_id = int(rec["row_id"])
            row_ids_defined.add(row_id)
            line_by_row.setdefault(row_id, lineno)
            row_sid = str(rec.get("row_sid", "")).strip()
            if not row_sid:
                raise SystemExit(f"line {lineno}: OP_DEF missing row_sid")

        elif rtype == "LABEL":
            row_id = int(rec["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: LABEL before OP_DEF for row_id={row_id}")
            label_type = str(rec.get("label_type", ""))
            if label_type not in {"left", "detail"}:
                raise SystemExit(f"line {lineno}: LABEL has invalid label_type={label_type!r}")

        elif rtype == "OCC":
            occ_count += 1
            row_id = int(rec["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: OCC before OP_DEF for row_id={row_id}")
            row_sid = str(rec.get("row_sid", "")).strip()
            if not row_sid:
                raise SystemExit(f"line {lineno}: OCC missing row_sid")
            stage = str(rec.get("stage_id", ""))
            lane = str(rec.get("lane_id", ""))
            if stage not in stage_set:
                raise SystemExit(f"line {lineno}: OCC has unknown stage_id={stage!r}")
            if lane not in lane_set:
                raise SystemExit(f"line {lineno}: OCC has unknown lane_id={lane!r}")
            stage_hist[stage] += 1
            try:
                cycle = int(rec.get("cycle"))
            except Exception:
                raise SystemExit(f"line {lineno}: OCC cycle must be integer")
            if args.single_stage_per_cycle:
                key = (row_id, cycle)
                row_cycle_stage[key].add(stage)
                if row_kind_meta.get(row_id, "") == "packet":
                    continue
                if len(row_cycle_stage[key]) > 1:
                    raise SystemExit(
                        f"line {lineno}: row_id={row_id} appears in multiple stages at cycle={cycle}: "
                        f"{sorted(row_cycle_stage[key])}"
                    )

        elif rtype == "RETIRE":
            retire_count += 1
            row_id = int(rec["row_id"])
            if row_id not in row_ids_defined:
                raise SystemExit(f"line {lineno}: RETIRE before OP_DEF for row_id={row_id}")
            row_sid = str(rec.get("row_sid", "")).strip()
            if not row_sid:
                raise SystemExit(f"line {lineno}: RETIRE missing row_sid")
            retire_by_row[row_id] += 1

        elif rtype == "BLOCK_EVT":
            seen_block_evt += 1
            for key in ("kind", "block_uid", "core_id", "cycle"):
                if key not in rec:
                    raise SystemExit(f"line {lineno}: BLOCK_EVT missing {key}")
            if "block_bid" not in rec and "bid" not in rec:
                raise SystemExit(f"line {lineno}: BLOCK_EVT missing block_bid/bid")

    if occ_count == 0:
        raise SystemExit("trace has zero OCC events (pipeline would be blank)")

    if retire_count == 0:
        raise SystemExit("trace has zero RETIRE events")

    for row_id in row_ids_defined:
        n = retire_by_row.get(row_id, 0)
        row_kind = row_kind_meta.get(row_id, "")
        if row_kind == "packet":
            if n > 1:
                raise SystemExit(f"row_id={row_id} terminal lifecycle violation: packet row cannot RETIRE more than once (got {n})")
            continue
        if n != 1:
            raise SystemExit(f"row_id={row_id} terminal lifecycle violation: expected exactly 1 RETIRE, got {n}")

    required = [s.strip() for s in args.require_stages.split(",") if s.strip()]
    for stage in required:
        if stage_hist.get(stage, 0) == 0:
            raise SystemExit(f"missing required stage: {stage}")

    print(
        "linxtrace-ok "
        f"rows={len(row_ids_defined)} occ={occ_count} retire={retire_count} "
        f"stages={len(stage_hist)} block_evt={seen_block_evt} contract={contract_id}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
