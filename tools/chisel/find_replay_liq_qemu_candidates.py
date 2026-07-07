#!/usr/bin/env python3
"""Find QEMU memory-row clusters that may be useful replay-LIQ probes.

This is a locator, not a proof.  It scans QEMU-shaped commit rows for nearby
store/load address overlap and same-cacheline memory reuse so a later generated
RTL run can target a concrete CoreMark window with replay-LIQ sidebands.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class MemoryEvent:
    row: int
    cycle: int
    pc: int
    insn: int
    is_store: bool
    addr: int
    size: int
    data: int

    @property
    def end(self) -> int:
        return self.addr + max(self.size, 0)

    @property
    def line(self) -> int:
        return self.addr // 64


def parse_int(value: Any, default: int = 0) -> int:
    if value is None:
        return default
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError(f"unsupported integer value {value!r}")


def load_events(path: Path) -> list[MemoryEvent]:
    events: list[MemoryEvent] = []
    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_no}: expected JSON object")
            if parse_int(row.get("mem_valid"), 0) == 0:
                continue
            is_store = parse_int(row.get("mem_is_store"), 0) != 0
            data_key = "mem_wdata" if is_store else "mem_rdata"
            events.append(
                MemoryEvent(
                    row=line_no - 1,
                    cycle=parse_int(row.get("cycle"), line_no - 1),
                    pc=parse_int(row.get("pc")),
                    insn=parse_int(row.get("insn")),
                    is_store=is_store,
                    addr=parse_int(row.get("mem_addr")),
                    size=parse_int(row.get("mem_size")),
                    data=parse_int(row.get(data_key), 0),
                )
            )
    return events


def ranges_overlap(a: MemoryEvent, b: MemoryEvent) -> bool:
    return a.addr < b.end and b.addr < a.end


def candidate_score(kind: str, first: MemoryEvent, second: MemoryEvent, distance: int) -> int:
    score = 0
    if ranges_overlap(first, second):
        score += 1000
    if first.line == second.line:
        score += 100
    if kind == "store_before_load":
        score += 50
    else:
        score += 35
    if first.pc == second.pc:
        score += 20
    score += max(0, 40 - min(distance, 40))
    return score


def find_candidates(
    events: list[MemoryEvent],
    *,
    lookback_rows: int,
    same_line: bool,
    min_second_row: int,
    max_second_row: int,
    dedupe_pairs: bool,
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for index, event in enumerate(events):
        if event.row < min_second_row:
            continue
        if max_second_row >= 0 and event.row > max_second_row:
            continue
        for prior in reversed(events[:index]):
            distance = event.row - prior.row
            if distance > lookback_rows:
                break
            if not same_line and not ranges_overlap(prior, event):
                continue
            if same_line and prior.line != event.line and not ranges_overlap(prior, event):
                continue
            if prior.is_store and not event.is_store:
                kind = "store_before_load"
            elif not prior.is_store and event.is_store:
                kind = "load_before_store"
            else:
                continue
            exact = ranges_overlap(prior, event)
            line_match = prior.line == event.line
            out.append(
                {
                    "kind": kind,
                    "score": candidate_score(kind, prior, event, distance),
                    "exact_overlap": exact,
                    "same_line": line_match,
                    "row_distance": distance,
                    "first": event_dict(prior),
                    "second": event_dict(event),
                    "pc_lo": min(prior.pc, event.pc),
                    "pc_hi": max(prior.pc, event.pc) + 1,
                }
            )
    out.sort(
        key=lambda item: (
            int(item["score"]),
            int(item["exact_overlap"]),
            -int(item["row_distance"]),
            -int(item["second"]["row"]),
        ),
        reverse=True,
    )
    if dedupe_pairs:
        unique: list[dict[str, Any]] = []
        seen: set[tuple[Any, ...]] = set()
        for item in out:
            first = item["first"]
            second = item["second"]
            first_loc = first["addr"] if item["exact_overlap"] else first["line"]
            second_loc = second["addr"] if item["exact_overlap"] else second["line"]
            key = (item["kind"], first["pc"], second["pc"], first_loc, second_loc)
            if key in seen:
                continue
            seen.add(key)
            unique.append(item)
        out = unique
    return out


def event_key(event: MemoryEvent) -> tuple[int, int, bool, int, int, int]:
    return (event.pc, event.insn, event.is_store, event.addr, event.size, event.data)


def raw_row_mapping(events: list[MemoryEvent], raw_events: list[MemoryEvent]) -> dict[int, int]:
    raw_by_key: dict[tuple[int, int, bool, int, int, int], list[int]] = {}
    for raw in raw_events:
        raw_by_key.setdefault(event_key(raw), []).append(raw.row)

    next_index: dict[tuple[int, int, bool, int, int, int], int] = {}
    out: dict[int, int] = {}
    for event in events:
        key = event_key(event)
        index = next_index.get(key, 0)
        raw_rows = raw_by_key.get(key, [])
        if index < len(raw_rows):
            out[event.row] = raw_rows[index]
        next_index[key] = index + 1
    return out


def event_dict(event: MemoryEvent) -> dict[str, Any]:
    return {
        "row": event.row,
        "cycle": event.cycle,
        "pc": f"0x{event.pc:x}",
        "insn": f"0x{event.insn:x}",
        "op": "store" if event.is_store else "load",
        "addr": f"0x{event.addr:x}",
        "size": event.size,
        "line": f"0x{event.line:x}",
        "data": f"0x{event.data:x}",
    }


def pc_histogram(events: Iterable[MemoryEvent]) -> list[dict[str, Any]]:
    buckets: dict[tuple[int, bool], dict[str, Any]] = {}
    for event in events:
        key = (event.pc, event.is_store)
        bucket = buckets.setdefault(
            key,
            {
                "pc": f"0x{event.pc:x}",
                "op": "store" if event.is_store else "load",
                "count": 0,
                "first_row": event.row,
                "last_row": event.row,
            },
        )
        bucket["count"] += 1
        bucket["last_row"] = event.row
    rows = list(buckets.values())
    rows.sort(key=lambda item: (int(item["count"]), int(item["last_row"])), reverse=True)
    return rows


def candidate_probe_hint(
    candidate: dict[str, Any],
    *,
    row_space: str,
    raw_rows_by_event_row: dict[int, int],
) -> dict[str, Any]:
    first = candidate["first"]
    second = candidate["second"]
    first_row = int(first["row"])
    second_row = int(second["row"])
    pc_lo = int(candidate["pc_lo"])
    pc_hi = int(candidate["pc_hi"])
    stores = [first["pc"] if first["op"] == "store" else second["pc"]]
    loads = [first["pc"] if first["op"] == "load" else second["pc"]]
    hint: dict[str, Any] = {
        "row_space": row_space,
        "input_window": {
            "start_row": first_row,
            "end_row": second_row,
            "capture_rows": second_row - first_row + 1,
        },
        "pc_filter": {
            "pc_lo": f"0x{pc_lo:x}",
            "pc_hi": f"0x{pc_hi:x}",
            "args": ["--pc-lo", f"0x{pc_lo:x}", "--pc-hi", f"0x{pc_hi:x}"],
            "caveat": (
                "PC filtering can select an earlier dynamic occurrence of the same PC range. "
                "Run a QEMU-only preflight with expected memory PCs before spending generated-RTL time."
            ),
        },
        "expected_memory_pcs": {
            "store_pcs": stores,
            "load_pcs": loads,
            "args": ["--expect-store-pcs", ",".join(stores), "--expect-load-pcs", ",".join(loads)],
        },
    }
    if first_row in raw_rows_by_event_row and second_row in raw_rows_by_event_row:
        raw_first = raw_rows_by_event_row[first_row]
        raw_second = raw_rows_by_event_row[second_row]
        hint["raw_dynamic_window"] = {
            "qemu_skip_rows": raw_first,
            "capture_rows": raw_second - raw_first + 1,
            "args": ["--qemu-skip-rows", str(raw_first), "--capture-rows", str(raw_second - raw_first + 1)],
            "claim_boundary": (
                "This reproduces the dynamic QEMU window only. Skipped raw rows are not "
                "generated-RTL replacement evidence because the reduced DUT cannot "
                "reconstruct skipped architectural state."
            ),
        }
    else:
        hint["raw_dynamic_window_unavailable"] = (
            "Candidate rows are not known raw QEMU row indices. Pass --raw-input with "
            "the matching qemu.live.raw.jsonl before using --qemu-skip-rows."
        )
    return hint


def annotate_candidates(
    candidates: list[dict[str, Any]],
    *,
    top: int,
    row_space: str,
    raw_rows_by_event_row: dict[int, int],
) -> list[dict[str, Any]]:
    annotated: list[dict[str, Any]] = []
    for item in candidates[:top]:
        row = dict(item)
        row["probe_hint"] = candidate_probe_hint(
            item,
            row_space=row_space,
            raw_rows_by_event_row=raw_rows_by_event_row,
        )
        annotated.append(row)
    return annotated


def write_summary(
    events: list[MemoryEvent],
    candidates: list[dict[str, Any]],
    top: int,
    *,
    row_space: str = "unknown",
    raw_rows_by_event_row: dict[int, int] | None = None,
) -> dict[str, Any]:
    store_count = sum(1 for event in events if event.is_store)
    load_count = len(events) - store_count
    raw_rows = raw_rows_by_event_row or {}
    return {
        "schema": "linxcore.replay_liq_qemu_candidate_locator.v1",
        "row_space": row_space,
        "event_count": len(events),
        "store_count": store_count,
        "load_count": load_count,
        "candidate_count": len(candidates),
        "claim_boundary": (
            "QEMU address clusters are candidate-selection hints only; replay-LIQ "
            "evidence still requires generated-RTL sideband counters and a passing "
            "QEMU/DUT comparator manifest."
        ),
        "top_candidates": annotate_candidates(
            candidates,
            top=top,
            row_space=row_space,
            raw_rows_by_event_row=raw_rows,
        ),
        "memory_pc_histogram": pc_histogram(events)[:top],
    }


def run_self_test() -> None:
    rows = [
        {"pc": 0x1000, "insn": 1, "mem_valid": 1, "mem_is_store": 1, "mem_addr": 0x2000, "mem_size": 8, "mem_wdata": 0xAA},
        {"pc": 0x1004, "insn": 2, "mem_valid": 1, "mem_is_store": 0, "mem_addr": 0x2000, "mem_size": 8, "mem_rdata": 0xAA},
        {"pc": 0x1008, "insn": 3, "mem_valid": 1, "mem_is_store": 0, "mem_addr": 0x2040, "mem_size": 8, "mem_rdata": 0},
        {"pc": 0x100C, "insn": 4, "mem_valid": 1, "mem_is_store": 1, "mem_addr": 0x2044, "mem_size": 4, "mem_wdata": 1},
    ]
    tmp = Path("/tmp/linxcore-replay-liq-qemu-candidate-selftest.jsonl")
    with tmp.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row) + "\n")
    events = load_events(tmp)
    candidates = find_candidates(
        events,
        lookback_rows=8,
        same_line=True,
        min_second_row=0,
        max_second_row=-1,
        dedupe_pairs=True,
    )
    if len(events) != 4:
        raise AssertionError("self-test did not load memory events")
    if not candidates:
        raise AssertionError("self-test did not find candidates")
    if candidates[0]["kind"] != "store_before_load" or not candidates[0]["exact_overlap"]:
        raise AssertionError("self-test top candidate is not the exact store/load pair")
    summary = write_summary(
        events,
        candidates,
        1,
        row_space="raw",
        raw_rows_by_event_row={event.row: event.row for event in events},
    )
    raw_window = summary["top_candidates"][0]["probe_hint"].get("raw_dynamic_window")
    if raw_window is None or raw_window["qemu_skip_rows"] != 0 or raw_window["capture_rows"] != 2:
        raise AssertionError("self-test did not annotate the raw dynamic window")
    tmp.unlink(missing_ok=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="QEMU raw or reduced expected JSONL")
    parser.add_argument(
        "--raw-input",
        type=Path,
        default=None,
        help="matching qemu.live.raw.jsonl used to annotate reduced-preview candidates with raw row windows",
    )
    parser.add_argument("--output", type=Path, help="write JSON summary to this path")
    parser.add_argument("--top", type=int, default=20, help="number of candidates to print/write")
    parser.add_argument("--lookback-rows", type=int, default=512, help="maximum row distance between paired memory events")
    parser.add_argument("--min-second-row", type=int, default=0, help="ignore candidates whose later memory row is before this raw/reduced row")
    parser.add_argument("--max-second-row", type=int, default=-1, help="ignore candidates whose later memory row is after this raw/reduced row")
    parser.add_argument("--exact-overlap-only", action="store_true", help="ignore same-line-only candidates")
    parser.add_argument("--no-dedupe-pairs", action="store_true", help="show repeated dynamic instances of the same PC/address-pair shape")
    parser.add_argument("--self-test", action="store_true", help="run built-in checks")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        run_self_test()
        print("replay-liq-qemu-candidate-locator self-test: ok")
        if args.input is None:
            return 0
    if args.input is None:
        raise SystemExit("error: --input is required unless --self-test is the only action")
    events = load_events(args.input)
    row_space = "raw" if args.input.name == "qemu.live.raw.jsonl" else "reduced"
    raw_rows_by_event_row: dict[int, int] = {}
    if args.raw_input is not None:
        raw_rows_by_event_row = raw_row_mapping(events, load_events(args.raw_input))
    elif row_space == "raw":
        raw_rows_by_event_row = {event.row: event.row for event in events}
    candidates = find_candidates(
        events,
        lookback_rows=args.lookback_rows,
        same_line=not args.exact_overlap_only,
        min_second_row=args.min_second_row,
        max_second_row=args.max_second_row,
        dedupe_pairs=not args.no_dedupe_pairs,
    )
    summary = write_summary(
        events,
        candidates,
        args.top,
        row_space=row_space,
        raw_rows_by_event_row=raw_rows_by_event_row,
    )
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2, sort_keys=True)
            f.write("\n")
    print(
        "replay-liq-qemu-candidates "
        f"events={summary['event_count']} stores={summary['store_count']} "
        f"loads={summary['load_count']} candidates={summary['candidate_count']}"
    )
    for item in summary["top_candidates"]:
        first = item["first"]
        second = item["second"]
        raw_window = item.get("probe_hint", {}).get("raw_dynamic_window")
        raw_filter = ""
        if isinstance(raw_window, dict):
            raw_filter = f" raw_skip={raw_window['qemu_skip_rows']} raw_capture={raw_window['capture_rows']}"
        print(
            f"score={item['score']} kind={item['kind']} exact={int(item['exact_overlap'])} "
            f"same_line={int(item['same_line'])} rows={first['row']}->{second['row']} "
            f"pcs={first['pc']}->{second['pc']} addrs={first['addr']}->{second['addr']} "
            f"pc_filter={hex(int(item['pc_lo']))}..{hex(int(item['pc_hi']))}{raw_filter}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
