#!/usr/bin/env python3
"""Validate frontend fetch/RF/ALU sideband stats reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


SCHEMA = "linxcore.frontend_fetch_rf_alu.sideband_stats.v21"

REQUIRED_REPLAY_LIQ_KEYS = [
    "cycles_sampled",
    "load_lookup_valid",
    "load_lookup_execute_granted",
    "load_lookup_execute_with_eligible_store",
    "load_lookup_execute_with_wait_store",
    "execute_load_wait_hold",
    "resident_store_eligible",
    "resident_store_ready_forward",
    "resident_store_wait_blocked",
    "resident_store_wait_store_valid",
    "store_stq_resident",
    "store_stq_addr_ready",
    "store_stq_data_ready",
    "store_stq_addr_ready_not_data_ready",
    "store_stq_addr_and_data_ready",
    "load_lookup_execute_with_addr_ready_not_data_ready",
    "load_lookup_execute_granted_first_cycle",
    "load_lookup_execute_granted_last_cycle",
    "store_stq_addr_ready_not_data_ready_first_cycle",
    "store_stq_addr_ready_not_data_ready_last_cycle",
    "store_stq_addr_and_data_ready_first_cycle",
    "store_stq_addr_and_data_ready_last_cycle",
    "load_lookup_execute_with_addr_ready_not_data_ready_first_cycle",
    "load_lookup_execute_with_addr_ready_not_data_ready_last_cycle",
    "store_sta_dequeue_fire_first_cycle",
    "store_sta_dequeue_fire_last_cycle",
    "store_std_dequeue_fire_first_cycle",
    "store_std_dequeue_fire_last_cycle",
    "store_sta_exec_valid_first_cycle",
    "store_sta_exec_valid_last_cycle",
    "store_std_exec_valid_first_cycle",
    "store_std_exec_valid_last_cycle",
    "store_sta_queue_valid",
    "store_std_queue_valid",
    "store_sta_queue_only_valid",
    "store_sta_dequeue_fire",
    "store_std_dequeue_fire",
    "store_sta_exec_valid",
    "store_std_exec_valid",
    "store_sta_exec_only_valid",
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
    "liq_return_complete_repick_mask_nonzero",
    "liq_return_complete_source_returned_mask_nonzero",
    "liq_return_complete_data_complete_mask_nonzero",
    "liq_return_complete_request_complete_mask_nonzero",
    "liq_return_complete_candidate_mask_nonzero",
    "liq_return_complete_mask_nonzero",
    "liq_return_complete_valid",
    "liq_return_complete_candidate_count_nonzero",
    "liq_base_lookup_valid",
    "liq_base_lookup_granted",
    "liq_base_data_returned",
    "liq_base_line_valid_mask_nonzero",
    "launch_readiness_candidate_valid",
    "launch_readiness_base_data_ready",
    "launch_readiness_sources_returned",
    "launch_readiness_ready",
    "launch_readiness_enable",
    "launch_readiness_blocked_by_disabled",
    "launch_readiness_blocked_by_no_candidate",
    "launch_readiness_blocked_by_base_lookup",
    "launch_readiness_blocked_by_base_data",
    "launch_readiness_blocked_by_scb",
    "launch_readiness_blocked_by_return",
    "liq_wait_store_mask_nonzero",
    "liq_replay_wake_valid",
    "liq_replay_wake_active",
    "liq_replay_wake_wait_store_candidate",
    "liq_replay_wake_bid_match",
    "liq_replay_wake_lsid_match",
    "liq_replay_wake_pc_match",
    "liq_replay_wake_full_match",
    "liq_replay_wake_store_unit",
    "liq_replay_wake_store_unit_full_match",
    "liq_replay_wake_store_unit_full_match_active",
    "liq_replay_wake_store_unit_full_match_flush_blocked",
    "liq_replay_wake_wait_store_clear",
    "source_return_candidate_valid",
    "source_return_store_snapshot_ready",
    "source_return_store_source_returned",
    "source_return_scb_source_returned",
    "source_return_source_returned",
    "source_return_blocked_by_disabled",
    "source_return_blocked_by_no_candidate",
    "source_return_blocked_by_base_data",
    "source_return_blocked_by_store_snapshot",
    "source_return_blocked_by_scb",
    "source_return_store_snapshot_live_request_active",
    "source_return_store_snapshot_live_evidence_valid",
    "source_return_scb_live_active",
    "source_return_scb_live_request_active",
    "source_return_scb_live_evidence_valid",
    "source_return_scb_live_pending",
    "source_return_scb_live_returned",
    "source_return_scb_live_blocked_by_request_disabled",
    "source_return_scb_live_blocked_by_no_pending",
    "source_return_scb_live_blocked_by_scb_return",
    "source_return_query_issued",
    "source_return_response_apply_valid",
    "source_return_row_state_plan_valid",
    "source_row_mutation_candidate_valid",
    "source_row_mutation_live_permit",
    "source_row_mutation_request_valid",
    "source_row_mutation_blocked_by_head_proof",
    "source_row_mutation_blocked_by_live_disabled",
    "return_data_candidate_valid",
    "return_data_request_mask_nonzero",
    "return_data_bytes_complete",
    "return_data_cross_line",
    "return_data_size_supported",
    "return_data_valid",
    "return_data_blocked_by_disabled",
    "return_data_blocked_by_no_candidate",
    "return_data_blocked_by_zero_size",
    "return_data_blocked_by_unsupported_size",
    "return_data_blocked_by_cross_line",
    "return_data_blocked_by_incomplete_bytes",
    "return_publish_candidate_valid",
    "return_publish_data_ready",
    "return_publish_ready",
    "return_publish_blocked_by_no_candidate",
    "return_publish_blocked_by_data",
    "return_publish_blocked_by_consumer",
    "lret_payload_candidate_valid",
    "lret_payload_valid",
    "lret_payload_wakeup_required",
    "lret_payload_blocked_by_no_candidate",
    "lret_payload_blocked_by_data",
    "publish_control_candidate_valid",
    "publish_control_live_enable",
    "publish_control_armed",
    "publish_control_fire",
    "publish_control_blocked_by_no_payload",
    "publish_control_blocked_by_publish",
    "publish_control_blocked_by_side_effects",
    "publish_control_blocked_by_live_disabled",
    "publish_request_valid",
    "publish_request_lret",
    "publish_request_writeback",
    "publish_request_wakeup",
    "publish_request_mask_nonzero",
    "publish_request_blocked_by_no_fire",
    "publish_request_invalid_fire_without_payload",
    "lret_sink_enqueue_ready",
    "lret_sink_enqueue_accepted",
    "lret_sink_enqueue_dropped",
    "lret_sink_drain_valid",
    "lret_sink_drain_fire",
    "lret_sink_pending",
    "lret_sink_full",
    "lret_sink_blocked_by_no_payload",
    "lret_sink_blocked_by_full",
    "lret_sink_blocked_by_drain",
    "lret_commit_history_load_rows",
    "lret_shadow_enqueue",
    "lret_shadow_enqueue_after_prior_commit",
    "lret_shadow_drain",
    "lret_shadow_drain_missing",
    "lret_shadow_drain_after_prior_commit",
    "lret_shadow_free_after_prior_commit",
    "lret_drain_permit_any_pipe_free",
    "lret_drain_permit_ready",
    "lret_drain_permit_blocked_by_no_entry",
    "lret_drain_permit_blocked_by_pipe_full",
    "lret_drain_permit_pipe_occupied",
    "lret_iex_data_rob_row_valid",
    "lret_iex_data_rob_row_need_flush",
    "lret_iex_data_rob_row_blocked_by_invalid_rid",
    "lret_iex_data_rob_row_blocked_by_free",
    "lret_iex_data_rob_row_blocked_by_stale_rid",
    "lret_iex_data_candidate_valid",
    "lret_iex_data_would_drain",
    "lret_iex_data_set_mem_data_valid",
    "lret_iex_data_blocked_by_disabled",
    "lret_iex_data_blocked_by_flush",
    "lret_iex_data_blocked_by_no_entry",
    "lret_iex_data_blocked_by_invalid_entry",
    "lret_iex_data_blocked_by_drain",
    "lret_iex_data_blocked_by_rob_missing",
    "lret_iex_data_blocked_by_need_flush",
    "lret_rob_resolve_candidate_valid",
    "lret_rob_resolve_valid",
    "lret_rob_resolve_ready_for_pipe_insert",
    "lret_rob_resolve_mark_all_destinations_data_valid",
    "lret_rob_resolve_mark_destination_data_valid",
    "lret_rob_resolve_ret_lane_increment",
    "lret_rob_resolve_blocked_by_disabled",
    "lret_rob_resolve_blocked_by_flush",
    "lret_rob_resolve_blocked_by_no_set_mem_data",
    "lret_rob_resolve_blocked_by_unsupported_multi_lane",
    "lret_rob_resolve_blocked_by_invalid_rid",
    "lret_rob_resolve_blocked_by_no_destination",
    "lret_lane_completion_candidate_valid",
    "lret_lane_completion_complete_valid",
    "lret_lane_completion_ready_for_pipe_insert",
    "lret_lane_completion_requires_all_lanes",
    "lret_lane_completion_blocked_by_disabled",
    "lret_lane_completion_blocked_by_flush",
    "lret_lane_completion_blocked_by_no_resolve",
    "lret_lane_completion_blocked_by_zero_returned_lanes",
    "lret_lane_completion_blocked_by_invalid_real_req_cnt",
    "lret_lane_completion_blocked_by_scalar_load_pair_incomplete",
    "lret_lane_completion_blocked_by_vector_mem_incomplete",
    "lret_tload_completion_candidate_valid",
    "lret_tload_completion_tload_candidate_valid",
    "lret_tload_completion_tile_scb_send_valid",
    "lret_tload_completion_tile_scb_is_last",
    "lret_tload_completion_complete_valid",
    "lret_tload_completion_ready_for_pipe_insert",
    "lret_tload_completion_blocked_by_disabled",
    "lret_tload_completion_blocked_by_flush",
    "lret_tload_completion_blocked_by_no_lane_completion",
    "lret_tload_completion_blocked_by_invalid_sub_inst_cnt",
    "lret_tload_completion_blocked_by_tload_pending",
    "lret_final_metadata_candidate_valid",
    "lret_final_metadata_is_load_return_marked",
    "lret_final_metadata_load_branch_resolve_called",
    "lret_final_metadata_load_branch_resolve_side_effect_valid",
    "lret_final_metadata_pipe_cycle_sideband_valid",
    "lret_final_metadata_ready_for_pipe_insert",
    "lret_final_metadata_blocked_by_disabled",
    "lret_final_metadata_blocked_by_flush",
    "lret_final_metadata_blocked_by_no_tload_completion",
    "lret_timing_stats_candidate_valid",
    "lret_timing_stats_sideband_valid",
    "lret_timing_stats_iq_name_sideband_valid",
    "lret_timing_stats_ld_rnt_cycle_valid",
    "lret_timing_stats_update_valid",
    "lret_timing_stats_latency_underflow",
    "lret_timing_stats_ready_for_pipe_insert",
    "lret_timing_stats_blocked_by_disabled",
    "lret_timing_stats_blocked_by_flush",
    "lret_timing_stats_blocked_by_no_final_metadata",
    "lret_iex_insert_candidate_valid",
    "lret_iex_insert_valid",
    "lret_iex_insert_is_load_return",
    "lret_iex_insert_wakeup_required",
    "lret_iex_insert_blocked_by_no_set_mem_data",
    "lret_iex_insert_blocked_by_no_pipe",
    "lret_iex_insert_blocked_by_invalid_rid",
    "lret_residency_candidate_valid",
    "lret_residency_write_valid",
    "lret_residency_live_enable",
    "lret_residency_blocked_by_live_disabled",
    "lret_residency_slot_accepted",
    "lret_residency_slot_occupied",
    "lret_residency_advance_candidate_valid",
    "lret_residency_advance_valid",
    "lret_residency_advance_blocked_by_advance_disabled",
    "lret_w1_slot_accepted",
    "lret_w1_slot_occupied",
    "lret_w1_advance_candidate_valid",
    "lret_w1_advance_valid",
    "lret_w1_advance_blocked_by_advance_disabled",
    "lret_w2_slot_accepted",
    "lret_w2_slot_occupied",
    "lret_w2_slot_blocked_by_no_write",
    "lret_w2_slot_source_trace_valid",
    "w2_atomic_live_active",
    "w2_atomic_request_active",
    "w2_atomic_evidence_valid",
    "w2_atomic_side_effect_live_requested",
    "w2_atomic_promotion_requested",
    "w2_atomic_blocked",
    "w2_atomic_blocked_by_request_disabled",
    "w2_atomic_blocked_by_no_evidence",
    "w2_atomic_blocked_by_mode_disabled",
    "w2_atomic_blocked_by_policy",
    "w2_atomic_blocked_by_no_side_effect_sink",
    "w2_atomic_blocked_by_no_clear_commit",
    "w2_atomic_blocked_by_no_row_fill_candidate",
    "w2_atomic_blocked_by_no_lifecycle_row",
    "w2_atomic_blocked_by_no_required_side_effect",
    "w2_side_effect_candidate_valid",
    "w2_side_effect_ready",
    "w2_side_effect_live_all_required_enabled",
    "w2_side_effect_fire_valid",
    "w2_side_effect_fire_complete",
    "w2_clear_intent",
    "w2_clear_commit_ready",
    "w2_promotion_live",
    "w2_promotion_live_clear",
    "w2_promotion_advance_live",
    "w2_promotion_blocked",
    "w2_promotion_blocked_by_promotion_disabled",
    "w2_promotion_blocked_by_clear_intent",
    "w2_promotion_invalid_clear_without_slot",
    "w2_refill_ready_empty",
    "w2_refill_ready_same_cycle_eligible",
    "w2_refill_ready_same_cycle_ready",
    "w2_refill_ready_future_advance",
    "w2_refill_ready_matches_current",
    "w2_refill_ready_blocked",
    "w2_refill_ready_invalid_live_clear_without_intent",
    "w2_slot_replace_empty_write_eligible",
    "w2_slot_replace_same_cycle_eligible",
    "w2_slot_replace_same_cycle_ready",
    "w2_slot_replace_future_write_accept",
    "w2_slot_replace_matches_current",
    "w2_slot_replace_blocked",
    "w2_slot_replace_blocked_by_current_storage",
    "w2_slot_replace_invalid_future_ready_without_live_clear",
    "w2_slot_replace_overlap_candidate_occupied",
    "w2_slot_replace_overlap_candidate_clear_intent",
    "w2_slot_replace_overlap_candidate_live_clear",
    "w2_slot_replace_live_clear_without_w1_candidate",
    "w2_slot_replace_w1_candidate_without_live_clear",
    "w2_slot_replace_advance_valid_on_live_clear",
    "w2_slot_replace_w1_candidate_cycle_before_clear_intent",
    "w2_slot_replace_w1_candidate_cycle_before_live_clear",
    "w2_slot_replace_clear_intent_cycle_before_w1_candidate",
    "w2_slot_replace_live_clear_cycle_before_w1_candidate",
    "w2_slot_replace_live_clear_after_w1_candidate_gap2",
    "w2_slot_replace_live_clear_after_w1_candidate_gap3",
    "w2_slot_replace_live_clear_after_w1_candidate_gap4",
    "w2_slot_replace_live_clear_after_w1_candidate_gap5_plus",
    "w2_slot_replace_w1_candidate_after_live_clear_gap2",
    "w2_slot_replace_w1_candidate_after_live_clear_gap3",
    "w2_slot_replace_w1_candidate_after_live_clear_gap4",
    "w2_slot_replace_w1_candidate_after_live_clear_gap5_plus",
    "w2_advance_enable",
    "w2_advance_replace_on_clear",
    "w2_advance_uses_future_advance",
    "w2_advance_blocked",
    "w2_advance_blocked_by_live_promotion_disabled",
    "w2_advance_invalid_future_write_without_advance",
    "w2_commit_row_trace_source_ready",
    "w2_commit_row_trace_source_instruction_ready",
    "w2_commit_row_trace_source_source_ready",
    "w2_commit_row_trace_source_blocked",
    "w2_commit_row_trace_source_blocked_by_no_metadata",
    "w2_commit_row_trace_source_blocked_by_no_source_trace",
    "w2_commit_row_trace_source_rob_lookup_row_valid",
    "w2_commit_row_trace_source_rob_lookup_instruction_valid",
    "w2_commit_row_trace_source_rob_lookup_blocked_by_need_flush",
    "w2_commit_row_trace_source_rob_lookup_blocked_by_missing_instruction",
    "w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_disabled",
    "w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_before_completion",
    "w2_commit_row_candidate_valid",
    "w2_commit_row_fill_candidate",
    "w2_commit_row_complete_candidate",
    "w2_commit_row_candidate_blocked",
    "w2_commit_row_candidate_blocked_by_no_metadata",
    "w2_commit_row_candidate_blocked_by_no_source_trace",
    "w2_commit_row_candidate_blocked_by_invalid_size",
    "w2_commit_row_candidate_blocked_by_non_gpr_destination",
    "w2_commit_row_candidate_blocked_by_row_fill_disabled",
    "w2_row_fill_candidate_valid",
    "w2_row_fill_prerequisites_ready",
    "w2_row_fill_enable",
    "w2_row_fill_blocked_by_request_disabled",
    "w2_row_fill_blocked_by_no_candidate",
    "w2_row_fill_blocked_by_no_side_effect_commit",
    "w2_row_fill_blocked_by_no_clear_commit",
    "w2_row_fill_blocked_by_live_clear_disabled",
    "w2_row_fill_blocked_by_no_replay_row_lifecycle",
    "w2_lifecycle_candidate_valid",
    "w2_lifecycle_slot_identity_valid",
    "w2_lifecycle_resolved_row_match",
    "w2_lifecycle_row_clear_ready",
    "w2_lifecycle_ready",
    "w2_lifecycle_blocked_by_no_resolved_row",
    "w2_lifecycle_blocked_by_multiple_resolved_rows",
    "w2_lifecycle_blocked_by_clear_disabled",
    "w2_lifecycle_clear_request_enable",
    "w2_lifecycle_clear_commit_enable",
    "resolve_queue_push_accepted",
    "resolve_queue_valid",
    "resolve_queue_push_accepted_first_cycle",
    "resolve_queue_push_accepted_last_cycle",
    "resolve_queue_valid_first_cycle",
    "resolve_queue_valid_last_cycle",
    "mdb_conflict_store_valid",
    "mdb_conflict_store_valid_first_cycle",
    "mdb_conflict_store_valid_last_cycle",
    "mdb_conflict_store_with_resolve_queue_valid",
    "mdb_conflict_store_with_resolve_queue_valid_first_cycle",
    "mdb_conflict_store_with_resolve_queue_valid_last_cycle",
    "mdb_conflict_store_without_resolve_queue_valid",
    "mdb_conflict_active_candidate",
    "mdb_conflict_resolve_candidate",
    "mdb_conflict_resolve_candidate_first_cycle",
    "mdb_conflict_resolve_candidate_last_cycle",
    "mdb_conflict_valid",
    "mdb_fanout_record_valid",
    "mdb_fanout_record_accepted",
    "mdb_fanout_record_processed",
    "mdb_fanout_bmdb_report",
    "mdb_fanout_ssit_nonempty",
    "mdb_fanout_lookup_valid",
    "mdb_fanout_lookup_accepted",
    "mdb_fanout_lookup_processed",
    "mdb_fanout_lookup_table_hit",
    "mdb_fanout_lookup_first_after_nuke",
    "mdb_fanout_lookup_conf_blocked",
    "mdb_fanout_lookup_weight_blocked",
    "mdb_fanout_lu_out_valid",
    "mdb_fanout_lu_out_hit",
    "mdb_fanout_su_out_valid",
    "mdb_fanout_su_out_hit",
    "mdb_fanout_su_wakeup_valid",
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
    "liq_row_mutation_selected_source_return",
    "liq_row_mutation_selected_mdb_wait_plan",
    "liq_row_mutation_source_conflict",
    "mdb_wait_plan_row_mutation_write_enable",
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
    parser.add_argument(
        "--require-zero",
        action="append",
        default=[],
        metavar="replay_liq.KEY",
        help="require a validated counter to be exactly zero",
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


def require_zero(replay_liq: dict[str, int], selectors: list[str]) -> None:
    for selector in selectors:
        if not selector.startswith("replay_liq."):
            print(
                f"error: unsupported zero selector {selector!r}; expected replay_liq.KEY",
                file=sys.stderr,
            )
            sys.exit(2)
        key = selector.removeprefix("replay_liq.")
        if key not in replay_liq:
            print(f"error: unknown replay_liq counter {key!r}", file=sys.stderr)
            sys.exit(2)
        if replay_liq[key] != 0:
            print(
                f"error: replay_liq.{key} must be zero, observed {replay_liq[key]}",
                file=sys.stderr,
            )
            sys.exit(1)


def main() -> int:
    args = parse_args()
    stats = load_stats(args.stats)
    replay_liq = validate_replay_liq(stats, args.expect_reduced_store_replay_liq)
    require_nonzero(replay_liq, args.require_nonzero)
    require_zero(replay_liq, args.require_zero)
    print(f"sideband_stats_json={args.stats}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
