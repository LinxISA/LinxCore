#!/usr/bin/env python3
"""Validate reduced store wait/replay generated-RTL reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


SCHEMA = "linxcore.reduced_store_wait_replay_chisel_path.v1"

REQUIRED_KEYS = [
    "cycles",
    "ready_forward_observed",
    "sta_wait_capture",
    "not_ready_wake_blocked",
    "std_wake_clear",
    "relaunch_queue_fire",
    "liq_alloc",
    "liq_refill",
    "liq_launch_valid",
    "liq_launch_accepted",
    "liq_e4_update",
    "liq_lhq_record",
    "liq_resolved",
    "resolve_queue_push",
    "resolve_queue_retired",
    "mdb_resolve_conflict",
    "mdb_nuke_flush",
    "mdb_fanout_record_accepted",
    "mdb_fanout_record_processed",
    "mdb_bmdb_report",
    "mdb_lookup_hit",
    "mdb_su_wakeup",
    "mdb_lookup_wait_plan_no_target",
    "mdb_lookup_wait_plan_live_target",
    "mdb_lookup_wait_plan_request",
    "mdb_lookup_wait_plan_bridge",
    "mdb_lookup_wait_plan_scb_evidence",
    "mdb_lookup_wait_plan_write",
    "mdb_lookup_wait_plan_apply",
    "mdb_lookup_wait_plan_wait_status_after_write",
    "liq_replay_wake_completed_mask",
    "liq_scb_returned_mask_before_mdb_write",
    "liq_sources_returned_mask_before_mdb_write",
    "liq_wait_mask_after_mdb_write",
    "liq_wait_store_mask_after_mdb_write",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="reduced-store wait/replay JSON report")
    parser.add_argument(
        "--require-true",
        action="append",
        default=[],
        metavar="KEY",
        help="require a report boolean field to be true",
    )
    parser.add_argument(
        "--require-false",
        action="append",
        default=[],
        metavar="KEY",
        help="require a report boolean field to be false",
    )
    parser.add_argument(
        "--require-nonzero",
        action="append",
        default=[],
        metavar="KEY",
        help="require a report integer field to be greater than zero",
    )
    parser.add_argument(
        "--require-zero",
        action="append",
        default=[],
        metavar="KEY",
        help="require a report integer field to be exactly zero",
    )
    return parser.parse_args()


def load_report(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as f:
            report = json.load(f)
    except OSError as exc:
        print(f"error: failed to read reduced-store report {path}: {exc}", file=sys.stderr)
        sys.exit(2)
    except json.JSONDecodeError as exc:
        print(f"error: malformed reduced-store report {path}: {exc}", file=sys.stderr)
        sys.exit(2)

    if not isinstance(report, dict):
        print("error: reduced-store report must be a JSON object", file=sys.stderr)
        sys.exit(2)
    return report


def validate_schema(report: dict[str, Any]) -> None:
    schema = report.get("schema")
    if schema != SCHEMA:
        print(f"error: unexpected reduced-store report schema {schema!r}", file=sys.stderr)
        sys.exit(2)

    for key in REQUIRED_KEYS:
        if key not in report:
            print(f"error: reduced-store report missing required key {key!r}", file=sys.stderr)
            sys.exit(2)

    cycles = report["cycles"]
    if not isinstance(cycles, int) or cycles <= 0:
        print("error: reduced-store report cycles must be a positive integer", file=sys.stderr)
        sys.exit(2)


def get_key(report: dict[str, Any], key: str) -> Any:
    if key not in report:
        print(f"error: unknown reduced-store report key {key!r}", file=sys.stderr)
        sys.exit(2)
    return report[key]


def require_bool(report: dict[str, Any], selectors: list[str], expected: bool) -> None:
    for key in selectors:
        value = get_key(report, key)
        if not isinstance(value, bool):
            print(f"error: reduced-store report {key} must be a boolean", file=sys.stderr)
            sys.exit(2)
        if value is not expected:
            print(
                f"error: reduced-store report {key} must be {str(expected).lower()}, "
                f"observed {str(value).lower()}",
                file=sys.stderr,
            )
            sys.exit(1)


def require_int(report: dict[str, Any], selectors: list[str], zero: bool) -> None:
    for key in selectors:
        value = get_key(report, key)
        if not isinstance(value, int) or isinstance(value, bool):
            print(f"error: reduced-store report {key} must be an integer", file=sys.stderr)
            sys.exit(2)
        if zero and value != 0:
            print(
                f"error: reduced-store report {key} must be zero, observed {value}",
                file=sys.stderr,
            )
            sys.exit(1)
        if not zero and value <= 0:
            print(
                f"error: reduced-store report {key} must be nonzero, observed {value}",
                file=sys.stderr,
            )
            sys.exit(1)


def main() -> int:
    args = parse_args()
    report = load_report(args.report)
    validate_schema(report)
    require_bool(report, args.require_true, True)
    require_bool(report, args.require_false, False)
    require_int(report, args.require_nonzero, zero=False)
    require_int(report, args.require_zero, zero=True)
    print(f"reduced_store_wait_replay_chisel_path_json={args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
