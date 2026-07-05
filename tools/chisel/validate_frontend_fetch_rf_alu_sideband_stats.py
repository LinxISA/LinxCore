#!/usr/bin/env python3
"""Validate frontend fetch/RF/ALU sideband stats reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


SCHEMA = "linxcore.frontend_fetch_rf_alu.sideband_stats.v1"

REQUIRED_REPLAY_LIQ_KEYS = [
    "cycles_sampled",
    "resident_store_wake_valid",
    "resident_store_wake_ready",
    "wait_replay_capture_accepted",
    "wait_replay_clear_valid",
    "wait_replay_relaunch_valid",
    "replay_queue_enqueue_accepted",
    "replay_queue_out_valid",
    "replay_queue_out_fire",
    "liq_alloc_valid",
    "liq_alloc_accepted",
    "liq_launch_valid",
    "liq_launch_accepted",
    "liq_base_lookup_valid",
    "liq_base_lookup_granted",
    "liq_base_data_returned",
    "source_return_candidate_valid",
    "source_return_store_snapshot_ready",
    "source_return_store_snapshot_live_request_active",
    "source_return_store_snapshot_live_evidence_valid",
    "source_return_query_issued",
    "source_return_response_apply_valid",
    "source_return_row_state_plan_valid",
    "source_row_mutation_candidate_valid",
    "source_row_mutation_live_permit",
    "source_row_mutation_request_valid",
    "source_row_mutation_blocked_by_head_proof",
    "source_row_mutation_blocked_by_live_disabled",
    "mdb_lookup_wait_plan_lookup_hit",
    "mdb_lookup_wait_plan_wait_intent_valid",
    "mdb_lookup_wait_plan_request_valid",
    "mdb_lookup_wait_plan_blocked_by_no_target",
    "mdb_lookup_wait_plan_blocked_by_missing_store_index",
    "mdb_lookup_wait_plan_blocked_by_missing_store_lsid",
    "mdb_lookup_wait_plan_bridge_active",
    "mdb_lookup_wait_plan_bridge_valid",
    "mdb_lookup_wait_plan_bridge_source_store_index_fits",
    "mdb_lookup_wait_plan_bridge_blocked_by_disabled",
    "mdb_lookup_wait_plan_bridge_blocked_by_flush",
    "mdb_lookup_wait_plan_bridge_blocked_by_no_request",
    "mdb_lookup_wait_plan_bridge_invalid_store_index_out_of_range",
    "mdb_lookup_wait_plan_bridge_invalid_conflicting_status_write",
    "mdb_lookup_wait_plan_bridge_invalid_wait_store_without_wait_status",
    "mdb_lookup_wait_plan_bridge_invalid_return_without_split_sources",
    "liq_row_mutation_bridge_valid",
    "liq_row_mutation_write_enable",
    "liq_row_mutation_apply_valid",
    "liq_row_mutation_blocked_by_bridge",
    "liq_row_mutation_blocked_by_control",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stats", type=Path, help="sideband stats JSON report")
    parser.add_argument(
        "--expect-reduced-store-replay-liq",
        action="store_true",
        help="require reduced_store_replay_liq_top=true",
    )
    parser.add_argument(
        "--require-nonzero",
        action="append",
        default=[],
        metavar="replay_liq.KEY",
        help="require a validated counter to be greater than zero",
    )
    return parser.parse_args()


def load_stats(path: Path) -> dict:
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except OSError as exc:
        print(f"error: failed to read sideband stats {path}: {exc}", file=sys.stderr)
        sys.exit(2)
    except json.JSONDecodeError as exc:
        print(f"error: malformed sideband stats {path}: {exc}", file=sys.stderr)
        sys.exit(2)


def validate_replay_liq(stats: dict, expect_replay_liq: bool) -> dict[str, int]:
    schema = stats.get("schema")
    if schema != SCHEMA:
        print(f"error: unexpected sideband stats schema {schema!r}", file=sys.stderr)
        sys.exit(2)

    if stats.get("reduced_store_replay_liq_top") is not expect_replay_liq:
        print(
            "error: sideband stats reduced_store_replay_liq_top does not match "
            f"expected value {expect_replay_liq}",
            file=sys.stderr,
        )
        sys.exit(2)

    replay_liq = stats.get("replay_liq")
    if not isinstance(replay_liq, dict):
        print("error: sideband stats missing replay_liq object", file=sys.stderr)
        sys.exit(2)

    for key in REQUIRED_REPLAY_LIQ_KEYS:
        value = replay_liq.get(key)
        if not isinstance(value, int) or value < 0:
            print(f"error: replay_liq.{key} must be a nonnegative integer", file=sys.stderr)
            sys.exit(2)

    if replay_liq["cycles_sampled"] <= 0:
        print("error: replay_liq.cycles_sampled must be positive", file=sys.stderr)
        sys.exit(2)

    return replay_liq


def require_nonzero(replay_liq: dict[str, int], selectors: list[str]) -> None:
    for selector in selectors:
        if not selector.startswith("replay_liq."):
            print(
                f"error: unsupported nonzero selector {selector!r}; expected replay_liq.KEY",
                file=sys.stderr,
            )
            sys.exit(2)
        key = selector.removeprefix("replay_liq.")
        if key not in replay_liq:
            print(f"error: unknown replay_liq counter {key!r}", file=sys.stderr)
            sys.exit(2)
        if replay_liq[key] <= 0:
            print(
                f"error: replay_liq.{key} must be nonzero, observed {replay_liq[key]}",
                file=sys.stderr,
            )
            sys.exit(1)


def main() -> int:
    args = parse_args()
    stats = load_stats(args.stats)
    replay_liq = validate_replay_liq(stats, args.expect_reduced_store_replay_liq)
    require_nonzero(replay_liq, args.require_nonzero)
    print(f"sideband_stats_json={args.stats}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
