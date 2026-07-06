#if defined(LINXCORE_MARKER_ROWS_TRACE_TOP)
#include "VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop.h"
#define VLinxCoreFrontendFetchRfAluTraceTop VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop
#elif defined(LINXCORE_REDUCED_STORE_TRACE_TOP)
#include "VLinxCoreFrontendFetchRfAluReducedStoreTraceTop.h"
#define VLinxCoreFrontendFetchRfAluTraceTop VLinxCoreFrontendFetchRfAluReducedStoreTraceTop
#elif defined(LINXCORE_REDUCED_STORE_REPLAY_LIQ_TRACE_TOP)
#include "VLinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop.h"
#define VLinxCoreFrontendFetchRfAluTraceTop VLinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop
#else
#include "VLinxCoreFrontendFetchRfAluTraceTop.h"
#endif
#include "verilated.h"

#include "commit_trace_jsonl.h"

#include <cerrno>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace {

using linxcore::chisel::CommitTraceJsonRow;
using linxcore::chisel::write_dut_commit_jsonl;
using linxcore::chisel::write_qemu_commit_jsonl;

constexpr int kDenseRowDrainCycles = 64;

template <typename T>
std::string hex_port(const T &value) {
  std::ostringstream out;
  out << std::hex << static_cast<unsigned long long>(value);
  return out.str();
}

template <std::size_t N>
std::string hex_port(const VlWide<N> &value) {
  std::ostringstream out;
  out << std::hex;
  bool seen = false;
  for (std::size_t remaining = N; remaining > 0; --remaining) {
    const std::uint32_t word = value.at(remaining - 1);
    if (!seen) {
      if (word == 0) {
        continue;
      }
      out << word;
      seen = true;
    } else {
      out << std::setw(8) << std::setfill('0') << word;
    }
  }
  if (!seen) {
    out << '0';
  }
  return out.str();
}

struct Args {
  std::string dut_trace;
  std::string qemu_trace;
  std::string memory_bin;
  std::string memory_hex;
  std::string expected_rows;
  std::string sideband_stats;
  std::uint64_t memory_base = 0x1000;
  bool admit_marker_rows = false;
  bool disable_store_memory_mutation = false;
  bool allow_residual_replay_liq_wait = false;
};

struct ExpectedRow {
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 4;
  std::uint64_t next_pc = 0;
  bool skip = false;
  std::string skip_kind;
  bool block_boundary = false;
  bool block_stop = false;
  bool loop_reentry = false;
  std::uint64_t loop_reentry_from_pc = 0;
  bool src0_valid = false;
  std::uint8_t src0_reg = 0;
  std::uint64_t src0_data = 0;
  bool src1_valid = false;
  std::uint8_t src1_reg = 0;
  std::uint64_t src1_data = 0;
  bool dst_valid = false;
  std::uint8_t dst_reg = 0;
  std::uint64_t dst_data = 0;
  bool mem_valid = false;
  bool mem_is_store = false;
  std::uint64_t mem_addr = 0;
  std::uint64_t mem_wdata = 0;
  std::uint64_t mem_rdata = 0;
  std::uint8_t mem_size = 0;
};

struct ObservedRow {
  bool valid = false;
  std::uint64_t seq = 0;
  std::uint64_t cycle = 0;
  std::uint8_t slot = 0;
  std::uint32_t bid = 0;
  std::uint32_t gid = 0;
  std::uint32_t rid = 0;
  bool rob_valid = false;
  bool rob_wrap = false;
  std::uint8_t rob_value = 0;
  bool block_bid_valid = false;
  std::uint64_t block_bid = 0;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 0;
  bool wb_valid = false;
  std::uint8_t wb_reg = 0;
  std::uint64_t wb_data = 0;
  bool src0_valid = false;
  std::uint8_t src0_reg = 0;
  std::uint64_t src0_data = 0;
  bool src1_valid = false;
  std::uint8_t src1_reg = 0;
  std::uint64_t src1_data = 0;
  bool dst_valid = false;
  std::uint8_t dst_reg = 0;
  std::uint64_t dst_data = 0;
  bool mem_valid = false;
  bool mem_is_store = false;
  std::uint64_t mem_addr = 0;
  std::uint64_t mem_wdata = 0;
  std::uint64_t mem_rdata = 0;
  std::uint8_t mem_size = 0;
  bool trap_valid = false;
  std::uint32_t trap_cause = 0;
  std::uint64_t trap_arg0 = 0;
  std::uint64_t next_pc = 0;
  bool rf_write_valid = false;
  std::uint8_t rf_write_tag = 0;
  std::uint64_t rf_write_data = 0;
  std::uint8_t src_phys_valid_mask = 0;
  std::uint8_t src_phys_tag0 = 0;
  std::uint8_t src_phys_tag1 = 0;
  std::uint8_t src_phys_tag2 = 0;
};

struct IssueDebug {
  std::uint8_t src_valid_mask = 0;
  std::uint8_t src_phys_tag0 = 0;
  std::uint8_t src_phys_tag1 = 0;
  std::uint8_t src_phys_tag2 = 0;
};

struct PhysWriter {
  bool valid = false;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t phys_tag = 0;
  std::uint8_t wb_reg = 0;
  std::uint64_t data = 0;
};

std::map<std::uint8_t, IssueDebug> g_issue_debug_by_rob;
std::map<std::uint8_t, PhysWriter> g_phys_writer_by_tag;
std::map<std::uint8_t, PhysWriter> g_arch_writer_by_reg;
std::uint64_t g_tb_cycle = 0;

struct GprCommitHistory {
  std::uint64_t dealloc_block_last_count = 0;
  std::uint64_t block_scalar_done_count = 0;
  std::uint64_t block_retire_count = 0;
  std::uint64_t gpr_commit_count = 0;
  std::uint64_t gpr_commit_hit_total = 0;
  std::uint64_t gpr_release_total = 0;
  std::uint64_t last_dealloc_block_last_bid = 0;
  std::uint64_t last_scalar_done_bid = 0;
  std::uint64_t last_block_retire_bid = 0;
  std::uint64_t last_gpr_commit_bid = 0;
  std::uint8_t last_gpr_commit_hits = 0;
  std::uint8_t last_gpr_releases = 0;
  std::uint64_t last_nonzero_gpr_commit_bid = 0;
  std::uint8_t last_nonzero_gpr_commit_hits = 0;
  std::uint8_t last_nonzero_gpr_releases = 0;
};

GprCommitHistory g_gpr_commit_history;

struct ReplayLiqSidebandStats {
  std::uint64_t cycles_sampled = 0;
  std::uint64_t load_lookup_valid = 0;
  std::uint64_t load_lookup_execute_granted = 0;
  std::uint64_t load_lookup_execute_with_eligible_store = 0;
  std::uint64_t load_lookup_execute_with_wait_store = 0;
  std::uint64_t execute_load_wait_hold = 0;
  std::uint64_t resident_store_eligible = 0;
  std::uint64_t resident_store_ready_forward = 0;
  std::uint64_t resident_store_wait_blocked = 0;
  std::uint64_t resident_store_wait_store_valid = 0;
  std::uint64_t store_stq_resident = 0;
  std::uint64_t store_stq_addr_ready = 0;
  std::uint64_t store_stq_data_ready = 0;
  std::uint64_t store_stq_addr_ready_not_data_ready = 0;
  std::uint64_t store_stq_addr_and_data_ready = 0;
  std::uint64_t load_lookup_execute_with_addr_ready_not_data_ready = 0;
  std::uint64_t load_lookup_execute_granted_first_cycle = 0;
  std::uint64_t load_lookup_execute_granted_last_cycle = 0;
  std::uint64_t store_stq_addr_ready_not_data_ready_first_cycle = 0;
  std::uint64_t store_stq_addr_ready_not_data_ready_last_cycle = 0;
  std::uint64_t store_stq_addr_and_data_ready_first_cycle = 0;
  std::uint64_t store_stq_addr_and_data_ready_last_cycle = 0;
  std::uint64_t load_lookup_execute_with_addr_ready_not_data_ready_first_cycle = 0;
  std::uint64_t load_lookup_execute_with_addr_ready_not_data_ready_last_cycle = 0;
  std::uint64_t store_sta_dequeue_fire_first_cycle = 0;
  std::uint64_t store_sta_dequeue_fire_last_cycle = 0;
  std::uint64_t store_std_dequeue_fire_first_cycle = 0;
  std::uint64_t store_std_dequeue_fire_last_cycle = 0;
  std::uint64_t store_sta_exec_valid_first_cycle = 0;
  std::uint64_t store_sta_exec_valid_last_cycle = 0;
  std::uint64_t store_std_exec_valid_first_cycle = 0;
  std::uint64_t store_std_exec_valid_last_cycle = 0;
  std::uint64_t store_sta_queue_valid = 0;
  std::uint64_t store_std_queue_valid = 0;
  std::uint64_t store_sta_queue_only_valid = 0;
  std::uint64_t store_sta_dequeue_fire = 0;
  std::uint64_t store_std_dequeue_fire = 0;
  std::uint64_t store_sta_exec_valid = 0;
  std::uint64_t store_std_exec_valid = 0;
  std::uint64_t store_sta_exec_only_valid = 0;
  std::uint64_t resident_store_wake_valid = 0;
  std::uint64_t resident_store_wake_ready = 0;
  std::uint64_t wait_replay_capture_accepted = 0;
  std::uint64_t wait_replay_clear_valid = 0;
  std::uint64_t wait_replay_relaunch_valid = 0;
  std::uint64_t replay_queue_enqueue_accepted = 0;
  std::uint64_t replay_queue_out_valid = 0;
  std::uint64_t replay_queue_out_fire = 0;
  std::uint64_t liq_alloc_valid = 0;
  std::uint64_t liq_alloc_accepted = 0;
  std::uint64_t liq_launch_valid = 0;
  std::uint64_t liq_launch_accepted = 0;
  std::uint64_t liq_return_complete_repick_mask_nonzero = 0;
  std::uint64_t liq_return_complete_source_returned_mask_nonzero = 0;
  std::uint64_t liq_return_complete_data_complete_mask_nonzero = 0;
  std::uint64_t liq_return_complete_request_complete_mask_nonzero = 0;
  std::uint64_t liq_return_complete_candidate_mask_nonzero = 0;
  std::uint64_t liq_return_complete_mask_nonzero = 0;
  std::uint64_t liq_return_complete_valid = 0;
  std::uint64_t liq_return_complete_candidate_count_nonzero = 0;
  std::uint64_t liq_return_complete_valid_w2_occupied = 0;
  std::uint64_t liq_base_lookup_valid = 0;
  std::uint64_t liq_base_lookup_granted = 0;
  std::uint64_t liq_base_data_returned = 0;
  std::uint64_t liq_base_line_valid_mask_nonzero = 0;
  std::uint64_t launch_readiness_candidate_valid = 0;
  std::uint64_t launch_readiness_base_data_ready = 0;
  std::uint64_t launch_readiness_sources_returned = 0;
  std::uint64_t launch_readiness_ready = 0;
  std::uint64_t launch_readiness_enable = 0;
  std::uint64_t launch_readiness_blocked_by_disabled = 0;
  std::uint64_t launch_readiness_blocked_by_no_candidate = 0;
  std::uint64_t launch_readiness_blocked_by_base_lookup = 0;
  std::uint64_t launch_readiness_blocked_by_base_data = 0;
  std::uint64_t launch_readiness_blocked_by_scb = 0;
  std::uint64_t launch_readiness_blocked_by_return = 0;
  std::uint64_t liq_wait_store_mask_nonzero = 0;
  std::uint64_t liq_replay_wake_valid = 0;
  std::uint64_t liq_replay_wake_active = 0;
  std::uint64_t liq_replay_wake_wait_store_candidate = 0;
  std::uint64_t liq_replay_wake_bid_match = 0;
  std::uint64_t liq_replay_wake_lsid_match = 0;
  std::uint64_t liq_replay_wake_pc_match = 0;
  std::uint64_t liq_replay_wake_full_match = 0;
  std::uint64_t liq_replay_wake_store_unit = 0;
  std::uint64_t liq_replay_wake_store_unit_full_match = 0;
  std::uint64_t liq_replay_wake_store_unit_full_match_active = 0;
  std::uint64_t liq_replay_wake_store_unit_full_match_flush_blocked = 0;
  std::uint64_t liq_replay_wake_wait_store_clear = 0;
  std::uint64_t source_return_candidate_valid = 0;
  std::uint64_t source_return_store_snapshot_ready = 0;
  std::uint64_t source_return_store_source_returned = 0;
  std::uint64_t source_return_scb_source_returned = 0;
  std::uint64_t source_return_source_returned = 0;
  std::uint64_t source_return_blocked_by_disabled = 0;
  std::uint64_t source_return_blocked_by_no_candidate = 0;
  std::uint64_t source_return_blocked_by_base_data = 0;
  std::uint64_t source_return_blocked_by_store_snapshot = 0;
  std::uint64_t source_return_blocked_by_scb = 0;
  std::uint64_t source_return_store_snapshot_live_request_active = 0;
  std::uint64_t source_return_store_snapshot_live_evidence_valid = 0;
  std::uint64_t source_return_scb_live_active = 0;
  std::uint64_t source_return_scb_live_request_active = 0;
  std::uint64_t source_return_scb_live_evidence_valid = 0;
  std::uint64_t source_return_scb_live_pending = 0;
  std::uint64_t source_return_scb_live_returned = 0;
  std::uint64_t source_return_scb_live_blocked_by_request_disabled = 0;
  std::uint64_t source_return_scb_live_blocked_by_no_pending = 0;
  std::uint64_t source_return_scb_live_blocked_by_scb_return = 0;
  std::uint64_t source_return_query_issued = 0;
  std::uint64_t source_return_response_apply_valid = 0;
  std::uint64_t source_return_row_state_plan_valid = 0;
  std::uint64_t source_return_candidate_w2_occupied = 0;
  std::uint64_t source_return_store_snapshot_ready_w2_occupied = 0;
  std::uint64_t source_return_query_issued_w2_occupied = 0;
  std::uint64_t source_return_response_apply_w2_occupied = 0;
  std::uint64_t source_row_mutation_candidate_valid = 0;
  std::uint64_t source_row_mutation_live_permit = 0;
  std::uint64_t source_row_mutation_request_valid = 0;
  std::uint64_t source_row_mutation_blocked_by_head_proof = 0;
  std::uint64_t source_row_mutation_blocked_by_live_disabled = 0;
  std::uint64_t source_row_mutation_request_w2_occupied = 0;
  std::uint64_t return_data_candidate_valid = 0;
  std::uint64_t return_data_request_mask_nonzero = 0;
  std::uint64_t return_data_bytes_complete = 0;
  std::uint64_t return_data_cross_line = 0;
  std::uint64_t return_data_size_supported = 0;
  std::uint64_t return_data_valid = 0;
  std::uint64_t return_data_blocked_by_disabled = 0;
  std::uint64_t return_data_blocked_by_no_candidate = 0;
  std::uint64_t return_data_blocked_by_zero_size = 0;
  std::uint64_t return_data_blocked_by_unsupported_size = 0;
  std::uint64_t return_data_blocked_by_cross_line = 0;
  std::uint64_t return_data_blocked_by_incomplete_bytes = 0;
  std::uint64_t return_publish_candidate_valid = 0;
  std::uint64_t return_publish_data_ready = 0;
  std::uint64_t return_publish_ready = 0;
  std::uint64_t return_publish_blocked_by_no_candidate = 0;
  std::uint64_t return_publish_blocked_by_data = 0;
  std::uint64_t return_publish_blocked_by_consumer = 0;
  std::uint64_t return_publish_candidate_w2_occupied = 0;
  std::uint64_t return_publish_ready_w2_occupied = 0;
  std::uint64_t lret_payload_candidate_valid = 0;
  std::uint64_t lret_payload_valid = 0;
  std::uint64_t lret_payload_wakeup_required = 0;
  std::uint64_t lret_payload_blocked_by_no_candidate = 0;
  std::uint64_t lret_payload_blocked_by_data = 0;
  std::uint64_t lret_payload_candidate_w2_occupied = 0;
  std::uint64_t lret_payload_valid_w2_occupied = 0;
  std::uint64_t publish_control_candidate_valid = 0;
  std::uint64_t publish_control_live_enable = 0;
  std::uint64_t publish_control_armed = 0;
  std::uint64_t publish_control_fire = 0;
  std::uint64_t publish_control_blocked_by_no_payload = 0;
  std::uint64_t publish_control_blocked_by_publish = 0;
  std::uint64_t publish_control_blocked_by_side_effects = 0;
  std::uint64_t publish_control_blocked_by_live_disabled = 0;
  std::uint64_t publish_control_candidate_w2_occupied = 0;
  std::uint64_t publish_control_live_enable_w2_occupied = 0;
  std::uint64_t publish_control_armed_w2_occupied = 0;
  std::uint64_t publish_control_fire_w2_occupied = 0;
  std::uint64_t publish_request_valid = 0;
  std::uint64_t publish_request_lret = 0;
  std::uint64_t publish_request_writeback = 0;
  std::uint64_t publish_request_wakeup = 0;
  std::uint64_t publish_request_mask_nonzero = 0;
  std::uint64_t publish_request_blocked_by_no_fire = 0;
  std::uint64_t publish_request_invalid_fire_without_payload = 0;
  std::uint64_t lret_sink_enqueue_ready = 0;
  std::uint64_t lret_sink_enqueue_accepted = 0;
  std::uint64_t lret_sink_enqueue_dropped = 0;
  std::uint64_t lret_sink_drain_valid = 0;
  std::uint64_t lret_sink_drain_fire = 0;
  std::uint64_t lret_sink_pending = 0;
  std::uint64_t lret_sink_full = 0;
  std::uint64_t lret_sink_blocked_by_no_payload = 0;
  std::uint64_t lret_sink_blocked_by_full = 0;
  std::uint64_t lret_sink_blocked_by_drain = 0;
  std::uint64_t lret_sink_enqueue_ready_w2_occupied = 0;
  std::uint64_t lret_sink_enqueue_accepted_w2_occupied = 0;
  std::uint64_t lret_sink_enqueue_dropped_w2_occupied = 0;
  std::uint64_t lret_sink_enqueue_accepted_same_cycle_drain_fire = 0;
  std::uint64_t lret_sink_enqueue_accepted_same_cycle_drain_fire_w2_occupied = 0;
  std::uint64_t lret_sink_enqueue_accepted_w2_without_drain_fire = 0;
  std::uint64_t lret_sink_enqueue_accepted_w2_completion_clear_slot = 0;
  std::uint64_t lret_sink_enqueue_accepted_w2_clear_intent = 0;
  std::uint64_t lret_sink_enqueue_accepted_w2_side_effect_fire_complete = 0;
  std::uint64_t lret_sink_enqueue_accepted_w2_live_clear = 0;
  std::uint64_t lret_sink_followup_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_sink_followup_w2_still_occupied = 0;
  std::uint64_t lret_sink_followup_w2_cleared = 0;
  std::uint64_t lret_sink_followup_after_enqueue_completion_clear_slot = 0;
  std::uint64_t lret_sink_followup_after_enqueue_completion_clear_slot_w2_cleared = 0;
  std::uint64_t lret_sink_followup_after_enqueue_clear_intent = 0;
  std::uint64_t lret_sink_followup_after_enqueue_clear_intent_w2_cleared = 0;
  std::uint64_t lret_sink_followup_after_enqueue_side_effect_fire_complete = 0;
  std::uint64_t lret_sink_followup_after_enqueue_side_effect_fire_complete_w2_cleared = 0;
  std::uint64_t lret_sink_followup_after_enqueue_live_clear = 0;
  std::uint64_t lret_sink_followup_after_enqueue_live_clear_w2_cleared = 0;
  std::uint64_t lret_sink_pending_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_sink_drain_valid_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_sink_drain_fire_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_drain_permit_ready_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_drain_permit_pipe_free_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_drain_permit_pipe_full_after_enqueue_accepted_w2 = 0;
  std::uint64_t lret_sink_pending_w2_occupied = 0;
  std::uint64_t lret_sink_drain_valid_w2_occupied = 0;
  std::uint64_t lret_sink_drain_fire_w2_occupied = 0;
  std::uint64_t w2_retire_record_capture_candidate = 0;
  std::uint64_t w2_retire_record_payload_valid = 0;
  std::uint64_t w2_retire_record_capture_valid = 0;
  std::uint64_t w2_retire_record_capture_ready = 0;
  std::uint64_t w2_retire_record_capture_accepted = 0;
  std::uint64_t w2_retire_record_capture_accepted_w2_occupied = 0;
  std::uint64_t w2_retire_record_capture_dropped = 0;
  std::uint64_t w2_retire_record_record_valid = 0;
  std::uint64_t w2_retire_record_record_ready = 0;
  std::uint64_t w2_retire_record_record_fire = 0;
  std::uint64_t w2_retire_record_pending = 0;
  std::uint64_t w2_retire_record_captured_with_lret_enqueue = 0;
  std::uint64_t w2_retire_record_record_from_lret_enqueue = 0;
  std::uint64_t w2_retire_record_blocked_by_invalid_payload = 0;
  std::uint64_t w2_retire_record_blocked_by_full = 0;
  std::uint64_t lret_commit_history_load_rows = 0;
  std::uint64_t lret_shadow_enqueue = 0;
  std::uint64_t lret_shadow_enqueue_after_prior_commit = 0;
  std::uint64_t lret_shadow_drain = 0;
  std::uint64_t lret_shadow_drain_missing = 0;
  std::uint64_t lret_shadow_drain_after_prior_commit = 0;
  std::uint64_t lret_shadow_free_after_prior_commit = 0;
  std::uint64_t lret_drain_permit_any_pipe_free = 0;
  std::uint64_t lret_drain_permit_ready = 0;
  std::uint64_t lret_drain_permit_blocked_by_no_entry = 0;
  std::uint64_t lret_drain_permit_blocked_by_pipe_full = 0;
  std::uint64_t lret_drain_permit_pipe_occupied = 0;
  std::uint64_t lret_iex_data_rob_row_valid = 0;
  std::uint64_t lret_iex_data_rob_row_need_flush = 0;
  std::uint64_t lret_iex_data_rob_row_blocked_by_invalid_rid = 0;
  std::uint64_t lret_iex_data_rob_row_blocked_by_free = 0;
  std::uint64_t lret_iex_data_rob_row_blocked_by_stale_rid = 0;
  std::uint64_t lret_iex_data_candidate_valid = 0;
  std::uint64_t lret_iex_data_would_drain = 0;
  std::uint64_t lret_iex_data_set_mem_data_valid = 0;
  std::uint64_t lret_iex_data_blocked_by_disabled = 0;
  std::uint64_t lret_iex_data_blocked_by_flush = 0;
  std::uint64_t lret_iex_data_blocked_by_no_entry = 0;
  std::uint64_t lret_iex_data_blocked_by_invalid_entry = 0;
  std::uint64_t lret_iex_data_blocked_by_drain = 0;
  std::uint64_t lret_iex_data_blocked_by_rob_missing = 0;
  std::uint64_t lret_iex_data_blocked_by_need_flush = 0;
  std::uint64_t lret_rob_resolve_candidate_valid = 0;
  std::uint64_t lret_rob_resolve_valid = 0;
  std::uint64_t lret_rob_resolve_ready_for_pipe_insert = 0;
  std::uint64_t lret_rob_resolve_mark_all_destinations_data_valid = 0;
  std::uint64_t lret_rob_resolve_mark_destination_data_valid = 0;
  std::uint64_t lret_rob_resolve_ret_lane_increment = 0;
  std::uint64_t lret_rob_resolve_blocked_by_disabled = 0;
  std::uint64_t lret_rob_resolve_blocked_by_flush = 0;
  std::uint64_t lret_rob_resolve_blocked_by_no_set_mem_data = 0;
  std::uint64_t lret_rob_resolve_blocked_by_unsupported_multi_lane = 0;
  std::uint64_t lret_rob_resolve_blocked_by_invalid_rid = 0;
  std::uint64_t lret_rob_resolve_blocked_by_no_destination = 0;
  std::uint64_t lret_lane_completion_candidate_valid = 0;
  std::uint64_t lret_lane_completion_complete_valid = 0;
  std::uint64_t lret_lane_completion_ready_for_pipe_insert = 0;
  std::uint64_t lret_lane_completion_requires_all_lanes = 0;
  std::uint64_t lret_lane_completion_blocked_by_disabled = 0;
  std::uint64_t lret_lane_completion_blocked_by_flush = 0;
  std::uint64_t lret_lane_completion_blocked_by_no_resolve = 0;
  std::uint64_t lret_lane_completion_blocked_by_zero_returned_lanes = 0;
  std::uint64_t lret_lane_completion_blocked_by_invalid_real_req_cnt = 0;
  std::uint64_t lret_lane_completion_blocked_by_scalar_load_pair_incomplete = 0;
  std::uint64_t lret_lane_completion_blocked_by_vector_mem_incomplete = 0;
  std::uint64_t lret_tload_completion_candidate_valid = 0;
  std::uint64_t lret_tload_completion_tload_candidate_valid = 0;
  std::uint64_t lret_tload_completion_tile_scb_send_valid = 0;
  std::uint64_t lret_tload_completion_tile_scb_is_last = 0;
  std::uint64_t lret_tload_completion_complete_valid = 0;
  std::uint64_t lret_tload_completion_ready_for_pipe_insert = 0;
  std::uint64_t lret_tload_completion_blocked_by_disabled = 0;
  std::uint64_t lret_tload_completion_blocked_by_flush = 0;
  std::uint64_t lret_tload_completion_blocked_by_no_lane_completion = 0;
  std::uint64_t lret_tload_completion_blocked_by_invalid_sub_inst_cnt = 0;
  std::uint64_t lret_tload_completion_blocked_by_tload_pending = 0;
  std::uint64_t lret_final_metadata_candidate_valid = 0;
  std::uint64_t lret_final_metadata_is_load_return_marked = 0;
  std::uint64_t lret_final_metadata_load_branch_resolve_called = 0;
  std::uint64_t lret_final_metadata_load_branch_resolve_side_effect_valid = 0;
  std::uint64_t lret_final_metadata_pipe_cycle_sideband_valid = 0;
  std::uint64_t lret_final_metadata_ready_for_pipe_insert = 0;
  std::uint64_t lret_final_metadata_blocked_by_disabled = 0;
  std::uint64_t lret_final_metadata_blocked_by_flush = 0;
  std::uint64_t lret_final_metadata_blocked_by_no_tload_completion = 0;
  std::uint64_t lret_timing_stats_candidate_valid = 0;
  std::uint64_t lret_timing_stats_sideband_valid = 0;
  std::uint64_t lret_timing_stats_iq_name_sideband_valid = 0;
  std::uint64_t lret_timing_stats_ld_rnt_cycle_valid = 0;
  std::uint64_t lret_timing_stats_update_valid = 0;
  std::uint64_t lret_timing_stats_latency_underflow = 0;
  std::uint64_t lret_timing_stats_ready_for_pipe_insert = 0;
  std::uint64_t lret_timing_stats_blocked_by_disabled = 0;
  std::uint64_t lret_timing_stats_blocked_by_flush = 0;
  std::uint64_t lret_timing_stats_blocked_by_no_final_metadata = 0;
  std::uint64_t lret_iex_insert_candidate_valid = 0;
  std::uint64_t lret_iex_insert_valid = 0;
  std::uint64_t lret_iex_insert_is_load_return = 0;
  std::uint64_t lret_iex_insert_wakeup_required = 0;
  std::uint64_t lret_iex_insert_blocked_by_no_set_mem_data = 0;
  std::uint64_t lret_iex_insert_blocked_by_no_pipe = 0;
  std::uint64_t lret_iex_insert_blocked_by_invalid_rid = 0;
  std::uint64_t lret_iex_insert_candidate_w2_occupied = 0;
  std::uint64_t lret_iex_insert_valid_w2_occupied = 0;
  std::uint64_t lret_residency_candidate_valid = 0;
  std::uint64_t lret_residency_write_valid = 0;
  std::uint64_t lret_residency_live_enable = 0;
  std::uint64_t lret_residency_blocked_by_live_disabled = 0;
  std::uint64_t lret_residency_slot_accepted = 0;
  std::uint64_t lret_residency_slot_occupied = 0;
  std::uint64_t lret_residency_advance_candidate_valid = 0;
  std::uint64_t lret_residency_advance_valid = 0;
  std::uint64_t lret_residency_advance_blocked_by_advance_disabled = 0;
  std::uint64_t lret_residency_candidate_w2_occupied = 0;
  std::uint64_t lret_residency_write_w2_occupied = 0;
  std::uint64_t lret_residency_slot_w2_occupied = 0;
  std::uint64_t lret_residency_advance_candidate_w2_occupied = 0;
  std::uint64_t lret_residency_advance_valid_w2_occupied = 0;
  std::uint64_t lret_w1_slot_accepted = 0;
  std::uint64_t lret_w1_slot_occupied = 0;
  std::uint64_t lret_w1_advance_candidate_valid = 0;
  std::uint64_t lret_w1_advance_valid = 0;
  std::uint64_t lret_w1_advance_blocked_by_advance_disabled = 0;
  std::uint64_t lret_w1_slot_w2_occupied = 0;
  std::uint64_t lret_w1_advance_candidate_w2_occupied = 0;
  std::uint64_t lret_w1_advance_valid_w2_occupied = 0;
  std::uint64_t lret_w2_slot_accepted = 0;
  std::uint64_t lret_w2_slot_occupied = 0;
  std::uint64_t lret_w2_slot_blocked_by_no_write = 0;
  std::uint64_t lret_w2_slot_source_trace_valid = 0;
  std::uint64_t w2_atomic_live_active = 0;
  std::uint64_t w2_atomic_request_active = 0;
  std::uint64_t w2_atomic_evidence_valid = 0;
  std::uint64_t w2_atomic_side_effect_live_requested = 0;
  std::uint64_t w2_atomic_promotion_requested = 0;
  std::uint64_t w2_atomic_blocked = 0;
  std::uint64_t w2_atomic_blocked_by_request_disabled = 0;
  std::uint64_t w2_atomic_blocked_by_no_evidence = 0;
  std::uint64_t w2_atomic_blocked_by_mode_disabled = 0;
  std::uint64_t w2_atomic_blocked_by_policy = 0;
  std::uint64_t w2_atomic_blocked_by_no_side_effect_sink = 0;
  std::uint64_t w2_atomic_blocked_by_no_clear_commit = 0;
  std::uint64_t w2_atomic_blocked_by_no_row_fill_candidate = 0;
  std::uint64_t w2_atomic_blocked_by_no_lifecycle_row = 0;
  std::uint64_t w2_atomic_blocked_by_no_required_side_effect = 0;
  std::uint64_t w2_side_effect_candidate_valid = 0;
  std::uint64_t w2_side_effect_ready = 0;
  std::uint64_t w2_side_effect_live_all_required_enabled = 0;
  std::uint64_t w2_side_effect_fire_valid = 0;
  std::uint64_t w2_side_effect_fire_complete = 0;
  std::uint64_t w2_clear_intent = 0;
  std::uint64_t w2_clear_commit_ready = 0;
  std::uint64_t w2_promotion_live = 0;
  std::uint64_t w2_promotion_live_clear = 0;
  std::uint64_t w2_promotion_advance_live = 0;
  std::uint64_t w2_promotion_blocked = 0;
  std::uint64_t w2_promotion_blocked_by_promotion_disabled = 0;
  std::uint64_t w2_promotion_blocked_by_clear_intent = 0;
  std::uint64_t w2_promotion_invalid_clear_without_slot = 0;
  std::uint64_t w2_refill_ready_empty = 0;
  std::uint64_t w2_refill_ready_same_cycle_eligible = 0;
  std::uint64_t w2_refill_ready_same_cycle_ready = 0;
  std::uint64_t w2_refill_ready_future_advance = 0;
  std::uint64_t w2_refill_ready_matches_current = 0;
  std::uint64_t w2_refill_ready_blocked = 0;
  std::uint64_t w2_refill_ready_invalid_live_clear_without_intent = 0;
  std::uint64_t w2_slot_replace_empty_write_eligible = 0;
  std::uint64_t w2_slot_replace_same_cycle_eligible = 0;
  std::uint64_t w2_slot_replace_same_cycle_ready = 0;
  std::uint64_t w2_slot_replace_future_write_accept = 0;
  std::uint64_t w2_slot_replace_matches_current = 0;
  std::uint64_t w2_slot_replace_blocked = 0;
  std::uint64_t w2_slot_replace_blocked_by_current_storage = 0;
  std::uint64_t w2_slot_replace_invalid_future_ready_without_live_clear = 0;
  std::uint64_t w2_slot_replace_overlap_candidate_occupied = 0;
  std::uint64_t w2_slot_replace_overlap_candidate_clear_intent = 0;
  std::uint64_t w2_slot_replace_overlap_candidate_live_clear = 0;
  std::uint64_t w2_slot_replace_overlap_live_clear_same_lsid = 0;
  std::uint64_t w2_slot_replace_overlap_live_clear_different_lsid = 0;
  std::uint64_t w2_slot_replace_overlap_live_clear_unknown_lsid = 0;
  std::uint64_t w2_slot_replace_live_clear_without_w1_candidate = 0;
  std::uint64_t w2_slot_replace_w1_candidate_without_live_clear = 0;
  std::uint64_t w2_slot_replace_advance_valid_on_live_clear = 0;
  std::uint64_t w2_slot_replace_w1_candidate_cycle_before_clear_intent = 0;
  std::uint64_t w2_slot_replace_w1_candidate_cycle_before_live_clear = 0;
  std::uint64_t w2_slot_replace_clear_intent_cycle_before_w1_candidate = 0;
  std::uint64_t w2_slot_replace_live_clear_cycle_before_w1_candidate = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_gap2 = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_gap3 = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_gap4 = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_gap5_plus = 0;
  std::uint64_t w2_slot_replace_w1_candidate_after_live_clear_gap2 = 0;
  std::uint64_t w2_slot_replace_w1_candidate_after_live_clear_gap3 = 0;
  std::uint64_t w2_slot_replace_w1_candidate_after_live_clear_gap4 = 0;
  std::uint64_t w2_slot_replace_w1_candidate_after_live_clear_gap5_plus = 0;
  std::uint64_t w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap2 = 0;
  std::uint64_t w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap3 = 0;
  std::uint64_t w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap4 = 0;
  std::uint64_t w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap5_plus = 0;
  std::uint64_t w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap2 = 0;
  std::uint64_t w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap3 = 0;
  std::uint64_t w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap4 = 0;
  std::uint64_t w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap5_plus = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_same_lsid = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_different_lsid = 0;
  std::uint64_t w2_slot_replace_live_clear_after_w1_candidate_unknown_lsid = 0;
  std::uint64_t w2_advance_enable = 0;
  std::uint64_t w2_advance_replace_on_clear = 0;
  std::uint64_t w2_advance_uses_future_advance = 0;
  std::uint64_t w2_advance_blocked = 0;
  std::uint64_t w2_advance_blocked_by_live_promotion_disabled = 0;
  std::uint64_t w2_advance_invalid_future_write_without_advance = 0;
  std::uint64_t w2_commit_row_trace_source_ready = 0;
  std::uint64_t w2_commit_row_trace_source_instruction_ready = 0;
  std::uint64_t w2_commit_row_trace_source_source_ready = 0;
  std::uint64_t w2_commit_row_trace_source_blocked = 0;
  std::uint64_t w2_commit_row_trace_source_blocked_by_no_metadata = 0;
  std::uint64_t w2_commit_row_trace_source_blocked_by_no_source_trace = 0;
  std::uint64_t w2_commit_row_trace_source_rob_lookup_row_valid = 0;
  std::uint64_t w2_commit_row_trace_source_rob_lookup_instruction_valid = 0;
  std::uint64_t w2_commit_row_trace_source_rob_lookup_blocked_by_need_flush = 0;
  std::uint64_t w2_commit_row_trace_source_rob_lookup_blocked_by_missing_instruction = 0;
  std::uint64_t w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_disabled = 0;
  std::uint64_t w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_before_completion = 0;
  std::uint64_t w2_commit_row_candidate_valid = 0;
  std::uint64_t w2_commit_row_fill_candidate = 0;
  std::uint64_t w2_commit_row_complete_candidate = 0;
  std::uint64_t w2_commit_row_candidate_blocked = 0;
  std::uint64_t w2_commit_row_candidate_blocked_by_no_metadata = 0;
  std::uint64_t w2_commit_row_candidate_blocked_by_no_source_trace = 0;
  std::uint64_t w2_commit_row_candidate_blocked_by_invalid_size = 0;
  std::uint64_t w2_commit_row_candidate_blocked_by_non_gpr_destination = 0;
  std::uint64_t w2_commit_row_candidate_blocked_by_row_fill_disabled = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_valid = 0;
  std::uint64_t w2_retire_record_commit_row_fill_candidate = 0;
  std::uint64_t w2_retire_record_commit_row_complete_candidate = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked_by_no_record = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked_by_no_metadata = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked_by_no_source_trace = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked_by_invalid_size = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked_by_non_gpr_destination = 0;
  std::uint64_t w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_intent = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_payload_rid_valid = 0;
  std::uint64_t w2_retire_record_instruction_metadata_w2_rid_valid = 0;
  std::uint64_t w2_retire_record_instruction_metadata_w2_rid_matches_capture = 0;
  std::uint64_t w2_retire_record_instruction_metadata_w2_metadata_ready = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_from_w2 = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_from_drain = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_blocked_by_no_payload_rid = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_rid = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch = 0;
  std::uint64_t w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_metadata = 0;
  std::uint64_t w2_retire_record_instruction_metadata_clear_accepted = 0;
  std::uint64_t w2_retire_record_instruction_metadata_provider_valid = 0;
  std::uint64_t w2_row_fill_candidate_valid = 0;
  std::uint64_t w2_row_fill_prerequisites_ready = 0;
  std::uint64_t w2_row_fill_enable = 0;
  std::uint64_t w2_row_fill_blocked_by_request_disabled = 0;
  std::uint64_t w2_row_fill_blocked_by_no_candidate = 0;
  std::uint64_t w2_row_fill_blocked_by_no_side_effect_commit = 0;
  std::uint64_t w2_row_fill_blocked_by_no_clear_commit = 0;
  std::uint64_t w2_row_fill_blocked_by_live_clear_disabled = 0;
  std::uint64_t w2_row_fill_blocked_by_no_replay_row_lifecycle = 0;
  std::uint64_t w2_lifecycle_candidate_valid = 0;
  std::uint64_t w2_lifecycle_slot_identity_valid = 0;
  std::uint64_t w2_lifecycle_resolved_row_match = 0;
  std::uint64_t w2_lifecycle_row_clear_ready = 0;
  std::uint64_t w2_lifecycle_ready = 0;
  std::uint64_t w2_lifecycle_blocked_by_no_resolved_row = 0;
  std::uint64_t w2_lifecycle_blocked_by_multiple_resolved_rows = 0;
  std::uint64_t w2_lifecycle_blocked_by_clear_disabled = 0;
  std::uint64_t w2_lifecycle_clear_request_enable = 0;
  std::uint64_t w2_lifecycle_clear_commit_enable = 0;
  std::uint64_t w2_retire_record_lifecycle_candidate_valid = 0;
  std::uint64_t w2_retire_record_lifecycle_slot_identity_valid = 0;
  std::uint64_t w2_retire_record_lifecycle_resolved_row_match = 0;
  std::uint64_t w2_retire_record_lifecycle_row_clear_ready = 0;
  std::uint64_t w2_retire_record_lifecycle_blocked_by_no_resolved_row = 0;
  std::uint64_t w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows = 0;
  std::uint64_t w2_retire_record_lifecycle_blocked_by_clear_disabled = 0;
  std::uint64_t w2_retire_record_lifecycle_request_candidate = 0;
  std::uint64_t w2_retire_record_lifecycle_live_promotion_candidate = 0;
  std::uint64_t w2_retire_record_lifecycle_request_blocked_by_no_lifecycle_row = 0;
  std::uint64_t w2_retire_record_lifecycle_request_blocked_by_no_atomic_request = 0;
  std::uint64_t w2_retire_record_lifecycle_request_blocked_by_no_row_fill_candidate = 0;
  std::uint64_t w2_retire_record_lifecycle_request_blocked_by_no_row_fill_enable = 0;
  std::uint64_t w2_retire_record_atomic_request_evidence_valid = 0;
  std::uint64_t w2_retire_record_atomic_request_row_fill_candidate_aligned = 0;
  std::uint64_t w2_retire_record_atomic_request_row_fill_enable_aligned = 0;
  std::uint64_t w2_retire_record_atomic_request_blocked_by_no_lifecycle_row = 0;
  std::uint64_t w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate = 0;
  std::uint64_t w2_retire_record_atomic_request_blocked_by_no_row_fill_enable = 0;
  std::uint64_t w2_retire_record_row_fill_enable_request_evidence_valid = 0;
  std::uint64_t w2_retire_record_row_fill_enable_candidate_aligned = 0;
  std::uint64_t w2_retire_record_row_fill_enable = 0;
  std::uint64_t w2_retire_record_row_fill_enable_blocked_by_request_disabled = 0;
  std::uint64_t w2_retire_record_row_fill_enable_blocked_by_no_lifecycle_row = 0;
  std::uint64_t w2_retire_record_row_fill_enable_blocked_by_no_row_fill_candidate = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_capture_intent = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_capture_from_lifecycle = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_clear_accepted = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_provider_valid = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_provider_valid_without_record = 0;
  std::uint64_t w2_retire_record_lifecycle_evidence_record_valid_without_provider = 0;
  std::uint64_t w2_retire_record_rob_fallback_capture_physical_complete = 0;
  std::uint64_t w2_retire_record_rob_fallback_candidate = 0;
  std::uint64_t w2_retire_record_rob_fallback_duplicate_physical_complete = 0;
  std::uint64_t w2_retire_record_rob_fallback_complete_valid = 0;
  std::uint64_t resolve_queue_push_accepted = 0;
  std::uint64_t resolve_queue_valid = 0;
  std::uint64_t resolve_queue_push_accepted_first_cycle = 0;
  std::uint64_t resolve_queue_push_accepted_last_cycle = 0;
  std::uint64_t resolve_queue_valid_first_cycle = 0;
  std::uint64_t resolve_queue_valid_last_cycle = 0;
  std::uint64_t mdb_conflict_store_valid = 0;
  std::uint64_t mdb_conflict_store_valid_first_cycle = 0;
  std::uint64_t mdb_conflict_store_valid_last_cycle = 0;
  std::uint64_t mdb_conflict_store_with_resolve_queue_valid = 0;
  std::uint64_t mdb_conflict_store_with_resolve_queue_valid_first_cycle = 0;
  std::uint64_t mdb_conflict_store_with_resolve_queue_valid_last_cycle = 0;
  std::uint64_t mdb_conflict_store_without_resolve_queue_valid = 0;
  std::uint64_t mdb_conflict_active_candidate = 0;
  std::uint64_t mdb_conflict_resolve_candidate = 0;
  std::uint64_t mdb_conflict_resolve_candidate_first_cycle = 0;
  std::uint64_t mdb_conflict_resolve_candidate_last_cycle = 0;
  std::uint64_t mdb_conflict_valid = 0;
  std::uint64_t mdb_fanout_record_valid = 0;
  std::uint64_t mdb_fanout_record_accepted = 0;
  std::uint64_t mdb_fanout_record_processed = 0;
  std::uint64_t mdb_fanout_bmdb_report = 0;
  std::uint64_t mdb_fanout_ssit_nonempty = 0;
  std::uint64_t mdb_fanout_lookup_valid = 0;
  std::uint64_t mdb_fanout_lookup_accepted = 0;
  std::uint64_t mdb_fanout_lookup_processed = 0;
  std::uint64_t mdb_fanout_lookup_table_hit = 0;
  std::uint64_t mdb_fanout_lookup_first_after_nuke = 0;
  std::uint64_t mdb_fanout_lookup_conf_blocked = 0;
  std::uint64_t mdb_fanout_lookup_weight_blocked = 0;
  std::uint64_t mdb_fanout_lu_out_valid = 0;
  std::uint64_t mdb_fanout_lu_out_hit = 0;
  std::uint64_t mdb_fanout_su_out_valid = 0;
  std::uint64_t mdb_fanout_su_out_hit = 0;
  std::uint64_t mdb_fanout_su_wakeup_valid = 0;
  std::uint64_t mdb_lookup_wait_plan_lookup_hit = 0;
  std::uint64_t mdb_lookup_wait_plan_wait_intent_valid = 0;
  std::uint64_t mdb_lookup_wait_plan_request_valid = 0;
  std::uint64_t mdb_lookup_wait_plan_blocked_by_no_target = 0;
  std::uint64_t mdb_lookup_wait_plan_blocked_by_missing_store_index = 0;
  std::uint64_t mdb_lookup_wait_plan_blocked_by_missing_store_lsid = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_active = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_valid = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_source_store_index_fits = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_blocked_by_disabled = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_blocked_by_flush = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_blocked_by_no_request = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_invalid_store_index_out_of_range = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_invalid_conflicting_status_write = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_invalid_wait_store_without_wait_status = 0;
  std::uint64_t mdb_lookup_wait_plan_bridge_invalid_return_without_split_sources = 0;
  std::uint64_t liq_row_mutation_bridge_valid = 0;
  std::uint64_t liq_row_mutation_selected_source_return = 0;
  std::uint64_t liq_row_mutation_selected_mdb_wait_plan = 0;
  std::uint64_t liq_row_mutation_source_conflict = 0;
  std::uint64_t mdb_wait_plan_row_mutation_write_enable = 0;
  std::uint64_t liq_row_mutation_write_enable = 0;
  std::uint64_t liq_row_mutation_apply_valid = 0;
  std::uint64_t liq_row_mutation_blocked_by_bridge = 0;
  std::uint64_t liq_row_mutation_blocked_by_control = 0;
};

ReplayLiqSidebandStats g_replay_liq_sideband_stats;
std::string g_replay_liq_sideband_stats_path;

struct ReplayLretRobId {
  bool valid = false;
  bool wrap = false;
  std::uint8_t value = 0;
};

std::vector<ReplayLretRobId> g_replay_lret_shadow_queue;
std::vector<ReplayLretRobId> g_replay_lret_committed_load_rob_ids;

bool same_replay_lret_rob_id(const ReplayLretRobId &lhs, const ReplayLretRobId &rhs) {
  return lhs.valid && rhs.valid && lhs.wrap == rhs.wrap && lhs.value == rhs.value;
}

bool replay_lret_rob_id_seen(const std::vector<ReplayLretRobId> &ids, const ReplayLretRobId &id) {
  for (const auto &entry : ids) {
    if (same_replay_lret_rob_id(entry, id)) {
      return true;
    }
  }
  return false;
}

void remember_replay_lret_committed_load(const ReplayLretRobId &id) {
  if (!id.valid || replay_lret_rob_id_seen(g_replay_lret_committed_load_rob_ids, id)) {
    return;
  }
  g_replay_lret_committed_load_rob_ids.push_back(id);
}

void observe_replay_lret_commit_history(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
#if defined(LINXCORE_REDUCED_STORE_REPLAY_LIQ_TRACE_TOP)
  if (dut.io_commit_rows_0_valid && dut.io_commit_rows_0_mem_valid &&
      !dut.io_commit_rows_0_mem_isStore && dut.io_commit_rows_0_rob_valid) {
    ++g_replay_liq_sideband_stats.lret_commit_history_load_rows;
    remember_replay_lret_committed_load({
      true,
      static_cast<bool>(dut.io_commit_rows_0_rob_wrap),
      static_cast<std::uint8_t>(dut.io_commit_rows_0_rob_value)
    });
  }
  if (dut.io_commit_rows_1_valid && dut.io_commit_rows_1_mem_valid &&
      !dut.io_commit_rows_1_mem_isStore && dut.io_commit_rows_1_rob_valid) {
    ++g_replay_liq_sideband_stats.lret_commit_history_load_rows;
    remember_replay_lret_committed_load({
      true,
      static_cast<bool>(dut.io_commit_rows_1_rob_wrap),
      static_cast<std::uint8_t>(dut.io_commit_rows_1_rob_value)
    });
  }
#else
  (void)dut;
#endif
}

void observe_replay_lret_shadow_fifo(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
#if defined(LINXCORE_REDUCED_STORE_REPLAY_LIQ_TRACE_TOP)
  if (dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
    ++g_replay_liq_sideband_stats.lret_shadow_drain;
    if (g_replay_lret_shadow_queue.empty()) {
      ++g_replay_liq_sideband_stats.lret_shadow_drain_missing;
    } else {
      const auto head = g_replay_lret_shadow_queue.front();
      g_replay_lret_shadow_queue.erase(g_replay_lret_shadow_queue.begin());
      const bool priorCommit = replay_lret_rob_id_seen(g_replay_lret_committed_load_rob_ids, head);
      if (priorCommit) {
        ++g_replay_liq_sideband_stats.lret_shadow_drain_after_prior_commit;
      }
      if (priorCommit && dut.io_reducedLoadReplayLiqLretIexDataRobRowBlockedByFree) {
        ++g_replay_liq_sideband_stats.lret_shadow_free_after_prior_commit;
      }
    }
  }

  if (dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted) {
    ReplayLretRobId enqueued = {
      static_cast<bool>(dut.io_reducedLoadReplayLiqLretPayloadRidValid),
      static_cast<bool>(dut.io_reducedLoadReplayLiqLretPayloadRidWrap),
      static_cast<std::uint8_t>(dut.io_reducedLoadReplayLiqLretPayloadRidValue)
    };
    ++g_replay_liq_sideband_stats.lret_shadow_enqueue;
    if (replay_lret_rob_id_seen(g_replay_lret_committed_load_rob_ids, enqueued)) {
      ++g_replay_liq_sideband_stats.lret_shadow_enqueue_after_prior_commit;
    }
    g_replay_lret_shadow_queue.push_back(enqueued);
  }
#else
  (void)dut;
#endif
}

void observe_cycle(bool event, std::uint64_t cycle, std::uint64_t &first, std::uint64_t &last) {
  if (event) {
    if (first == 0) {
      first = cycle;
    }
    last = cycle;
  }
}

void observe_replay_liq_sideband(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  ++g_replay_liq_sideband_stats.cycles_sampled;
#if defined(LINXCORE_REDUCED_STORE_REPLAY_LIQ_TRACE_TOP)
  const std::uint64_t cycle = g_replay_liq_sideband_stats.cycles_sampled;
  const std::uint64_t stq_occupied_mask =
      static_cast<std::uint64_t>(dut.io_storeStqOccupiedMask);
  const std::uint64_t stq_addr_ready_mask =
      static_cast<std::uint64_t>(dut.io_storeStqAddrReadyMask);
  const std::uint64_t stq_data_ready_mask =
      static_cast<std::uint64_t>(dut.io_storeStqDataReadyMask);
  const std::uint64_t stq_addr_ready_resident_mask =
      stq_occupied_mask & stq_addr_ready_mask;
  const std::uint64_t stq_data_ready_resident_mask =
      stq_occupied_mask & stq_data_ready_mask;
  const std::uint64_t stq_addr_ready_not_data_ready_mask =
      stq_addr_ready_resident_mask & ~stq_data_ready_mask;
  const std::uint64_t stq_addr_and_data_ready_mask =
      stq_addr_ready_resident_mask & stq_data_ready_mask;
  const bool load_lookup_execute_granted = dut.io_loadLookupExecuteGranted;
  const bool stq_addr_ready_not_data_ready = stq_addr_ready_not_data_ready_mask != 0;
  const bool stq_addr_and_data_ready = stq_addr_and_data_ready_mask != 0;
  const bool load_lookup_execute_with_addr_ready_not_data_ready =
      load_lookup_execute_granted && stq_addr_ready_not_data_ready;
  const bool w2_slot_occupied = dut.io_reducedLoadReplayLiqLretPipeW2SlotOccupied;
  const bool w2_completion_clear_slot =
      dut.io_reducedLoadReplayLiqLretPipeW2CompletionClearSlot;
  const bool w2_clear_intent =
      dut.io_reducedLoadReplayLiqLretPipeW2ClearIntentClearIntent;
  const bool w2_side_effect_fire_complete =
      dut.io_reducedLoadReplayLiqLretPipeW2SideEffectFireCompleteFireComplete;
  const bool w2_live_clear =
      dut.io_reducedLoadReplayLiqLretPipeW2ClearIntentLiveClear;
  static bool previous_lret_sink_enqueue_accepted_w2 = false;
  static bool previous_lret_sink_enqueue_w2_completion_clear_slot = false;
  static bool previous_lret_sink_enqueue_w2_clear_intent = false;
  static bool previous_lret_sink_enqueue_w2_side_effect_fire_complete = false;
  static bool previous_lret_sink_enqueue_w2_live_clear = false;
  if (previous_lret_sink_enqueue_accepted_w2) {
    ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_accepted_w2;
    if (w2_slot_occupied) {
      ++g_replay_liq_sideband_stats.lret_sink_followup_w2_still_occupied;
    } else {
      ++g_replay_liq_sideband_stats.lret_sink_followup_w2_cleared;
    }
    if (previous_lret_sink_enqueue_w2_completion_clear_slot) {
      ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_completion_clear_slot;
      if (!w2_slot_occupied) {
        ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_completion_clear_slot_w2_cleared;
      }
    }
    if (previous_lret_sink_enqueue_w2_clear_intent) {
      ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_clear_intent;
      if (!w2_slot_occupied) {
        ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_clear_intent_w2_cleared;
      }
    }
    if (previous_lret_sink_enqueue_w2_side_effect_fire_complete) {
      ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_side_effect_fire_complete;
      if (!w2_slot_occupied) {
        ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_side_effect_fire_complete_w2_cleared;
      }
    }
    if (previous_lret_sink_enqueue_w2_live_clear) {
      ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_live_clear;
      if (!w2_slot_occupied) {
        ++g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_live_clear_w2_cleared;
      }
    }
    if (dut.io_reducedLoadReplayLiqLretSinkPending) {
      ++g_replay_liq_sideband_stats.lret_sink_pending_after_enqueue_accepted_w2;
    }
    if (dut.io_reducedLoadReplayLiqLretSinkDrainValid) {
      ++g_replay_liq_sideband_stats.lret_sink_drain_valid_after_enqueue_accepted_w2;
    }
    if (dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
      ++g_replay_liq_sideband_stats.lret_sink_drain_fire_after_enqueue_accepted_w2;
    }
    if (dut.io_reducedLoadReplayLiqLretDrainPermitReady) {
      ++g_replay_liq_sideband_stats.lret_drain_permit_ready_after_enqueue_accepted_w2;
    }
    if (dut.io_reducedLoadReplayLiqLretDrainPermitAnyPipeFree) {
      ++g_replay_liq_sideband_stats.lret_drain_permit_pipe_free_after_enqueue_accepted_w2;
    }
    if (dut.io_reducedLoadReplayLiqLretDrainPermitBlockedByPipeFull) {
      ++g_replay_liq_sideband_stats.lret_drain_permit_pipe_full_after_enqueue_accepted_w2;
    }
  }
  observe_cycle(
      load_lookup_execute_granted,
      cycle,
      g_replay_liq_sideband_stats.load_lookup_execute_granted_first_cycle,
      g_replay_liq_sideband_stats.load_lookup_execute_granted_last_cycle);
  observe_cycle(
      stq_addr_ready_not_data_ready,
      cycle,
      g_replay_liq_sideband_stats.store_stq_addr_ready_not_data_ready_first_cycle,
      g_replay_liq_sideband_stats.store_stq_addr_ready_not_data_ready_last_cycle);
  observe_cycle(
      stq_addr_and_data_ready,
      cycle,
      g_replay_liq_sideband_stats.store_stq_addr_and_data_ready_first_cycle,
      g_replay_liq_sideband_stats.store_stq_addr_and_data_ready_last_cycle);
  observe_cycle(
      load_lookup_execute_with_addr_ready_not_data_ready,
      cycle,
      g_replay_liq_sideband_stats.load_lookup_execute_with_addr_ready_not_data_ready_first_cycle,
      g_replay_liq_sideband_stats.load_lookup_execute_with_addr_ready_not_data_ready_last_cycle);
  observe_cycle(
      dut.io_storeStaDequeueFire,
      cycle,
      g_replay_liq_sideband_stats.store_sta_dequeue_fire_first_cycle,
      g_replay_liq_sideband_stats.store_sta_dequeue_fire_last_cycle);
  observe_cycle(
      dut.io_storeStdDequeueFire,
      cycle,
      g_replay_liq_sideband_stats.store_std_dequeue_fire_first_cycle,
      g_replay_liq_sideband_stats.store_std_dequeue_fire_last_cycle);
  observe_cycle(
      dut.io_reducedStoreStaExecValid,
      cycle,
      g_replay_liq_sideband_stats.store_sta_exec_valid_first_cycle,
      g_replay_liq_sideband_stats.store_sta_exec_valid_last_cycle);
  observe_cycle(
      dut.io_reducedStoreStdExecValid,
      cycle,
      g_replay_liq_sideband_stats.store_std_exec_valid_first_cycle,
      g_replay_liq_sideband_stats.store_std_exec_valid_last_cycle);
  const bool resolve_queue_push_accepted = dut.io_reducedLoadReplayResolveQueuePushAccepted;
  const bool resolve_queue_valid = dut.io_reducedLoadReplayResolveQueueValidMask != 0;
  const bool mdb_conflict_store_valid = dut.io_reducedMdbConflictStoreValid;
  const bool mdb_conflict_store_with_resolve_queue_valid =
      mdb_conflict_store_valid && resolve_queue_valid;
  const bool mdb_conflict_resolve_candidate =
      dut.io_reducedMdbConflictResolveCandidateMask != 0;
  observe_cycle(
      resolve_queue_push_accepted,
      cycle,
      g_replay_liq_sideband_stats.resolve_queue_push_accepted_first_cycle,
      g_replay_liq_sideband_stats.resolve_queue_push_accepted_last_cycle);
  observe_cycle(
      resolve_queue_valid,
      cycle,
      g_replay_liq_sideband_stats.resolve_queue_valid_first_cycle,
      g_replay_liq_sideband_stats.resolve_queue_valid_last_cycle);
  observe_cycle(
      mdb_conflict_store_valid,
      cycle,
      g_replay_liq_sideband_stats.mdb_conflict_store_valid_first_cycle,
      g_replay_liq_sideband_stats.mdb_conflict_store_valid_last_cycle);
  observe_cycle(
      mdb_conflict_store_with_resolve_queue_valid,
      cycle,
      g_replay_liq_sideband_stats.mdb_conflict_store_with_resolve_queue_valid_first_cycle,
      g_replay_liq_sideband_stats.mdb_conflict_store_with_resolve_queue_valid_last_cycle);
  observe_cycle(
      mdb_conflict_resolve_candidate,
      cycle,
      g_replay_liq_sideband_stats.mdb_conflict_resolve_candidate_first_cycle,
      g_replay_liq_sideband_stats.mdb_conflict_resolve_candidate_last_cycle);
  if (dut.io_loadLookupValid) {
    ++g_replay_liq_sideband_stats.load_lookup_valid;
  }
  if (load_lookup_execute_granted) {
    ++g_replay_liq_sideband_stats.load_lookup_execute_granted;
  }
  if (load_lookup_execute_granted && dut.io_reducedStoreResidentEligibleMask != 0) {
    ++g_replay_liq_sideband_stats.load_lookup_execute_with_eligible_store;
  }
  if (load_lookup_execute_granted && dut.io_reducedStoreResidentWaitStoreValid) {
    ++g_replay_liq_sideband_stats.load_lookup_execute_with_wait_store;
  }
  if (dut.io_executeLoadWaitHold) {
    ++g_replay_liq_sideband_stats.execute_load_wait_hold;
  }
  if (dut.io_reducedStoreResidentEligibleMask != 0) {
    ++g_replay_liq_sideband_stats.resident_store_eligible;
  }
  if (dut.io_reducedStoreResidentReadyForward) {
    ++g_replay_liq_sideband_stats.resident_store_ready_forward;
  }
  if (dut.io_reducedStoreResidentWaitBlocked) {
    ++g_replay_liq_sideband_stats.resident_store_wait_blocked;
  }
  if (dut.io_reducedStoreResidentWaitStoreValid) {
    ++g_replay_liq_sideband_stats.resident_store_wait_store_valid;
  }
  if (stq_occupied_mask != 0) {
    ++g_replay_liq_sideband_stats.store_stq_resident;
  }
  if (stq_addr_ready_resident_mask != 0) {
    ++g_replay_liq_sideband_stats.store_stq_addr_ready;
  }
  if (stq_data_ready_resident_mask != 0) {
    ++g_replay_liq_sideband_stats.store_stq_data_ready;
  }
  if (stq_addr_ready_not_data_ready) {
    ++g_replay_liq_sideband_stats.store_stq_addr_ready_not_data_ready;
  }
  if (stq_addr_and_data_ready) {
    ++g_replay_liq_sideband_stats.store_stq_addr_and_data_ready;
  }
  if (load_lookup_execute_with_addr_ready_not_data_ready) {
    ++g_replay_liq_sideband_stats.load_lookup_execute_with_addr_ready_not_data_ready;
  }
  if (dut.io_storeStaQueueValid) {
    ++g_replay_liq_sideband_stats.store_sta_queue_valid;
  }
  if (dut.io_storeStdQueueValid) {
    ++g_replay_liq_sideband_stats.store_std_queue_valid;
  }
  if (dut.io_storeStaQueueValid && !dut.io_storeStdQueueValid) {
    ++g_replay_liq_sideband_stats.store_sta_queue_only_valid;
  }
  if (dut.io_storeStaDequeueFire) {
    ++g_replay_liq_sideband_stats.store_sta_dequeue_fire;
  }
  if (dut.io_storeStdDequeueFire) {
    ++g_replay_liq_sideband_stats.store_std_dequeue_fire;
  }
  if (dut.io_reducedStoreStaExecValid) {
    ++g_replay_liq_sideband_stats.store_sta_exec_valid;
  }
  if (dut.io_reducedStoreStdExecValid) {
    ++g_replay_liq_sideband_stats.store_std_exec_valid;
  }
  if (dut.io_reducedStoreStaExecValid && !dut.io_reducedStoreStdExecValid) {
    ++g_replay_liq_sideband_stats.store_sta_exec_only_valid;
  }
  if (dut.io_reducedStoreResidentReplayWakeValid) {
    ++g_replay_liq_sideband_stats.resident_store_wake_valid;
  }
  if (dut.io_reducedStoreResidentReplayWakeReady) {
    ++g_replay_liq_sideband_stats.resident_store_wake_ready;
  }
  if (dut.io_reducedLoadWaitReplayCaptureAccepted) {
    ++g_replay_liq_sideband_stats.wait_replay_capture_accepted;
  }
  if (dut.io_reducedLoadWaitReplayClearValid) {
    ++g_replay_liq_sideband_stats.wait_replay_clear_valid;
  }
  if (dut.io_reducedLoadWaitReplayRelaunchValid) {
    ++g_replay_liq_sideband_stats.wait_replay_relaunch_valid;
  }
  if (dut.io_reducedLoadReplayQueueEnqueueAccepted) {
    ++g_replay_liq_sideband_stats.replay_queue_enqueue_accepted;
  }
  if (dut.io_reducedLoadReplayQueueOutValid) {
    ++g_replay_liq_sideband_stats.replay_queue_out_valid;
  }
  if (dut.io_reducedLoadReplayQueueOutFire) {
    ++g_replay_liq_sideband_stats.replay_queue_out_fire;
  }
  if (dut.io_reducedLoadReplayLiqAllocValid) {
    ++g_replay_liq_sideband_stats.liq_alloc_valid;
  }
  if (dut.io_reducedLoadReplayLiqAllocAccepted) {
    ++g_replay_liq_sideband_stats.liq_alloc_accepted;
  }
  if (dut.io_reducedLoadReplayLiqLaunchValid) {
    ++g_replay_liq_sideband_stats.liq_launch_valid;
  }
  if (dut.io_reducedLoadReplayLiqLaunchAccepted) {
    ++g_replay_liq_sideband_stats.liq_launch_accepted;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteRepickMask != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_repick_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteSourceReturnedMask != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_source_returned_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteDataCompleteMask != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_data_complete_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteRequestCompleteMask != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_request_complete_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteCandidateMask != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_candidate_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteMask != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteValid) {
    ++g_replay_liq_sideband_stats.liq_return_complete_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqReturnCompleteValid) {
    ++g_replay_liq_sideband_stats.liq_return_complete_valid_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqReturnCompleteCandidateCount != 0) {
    ++g_replay_liq_sideband_stats.liq_return_complete_candidate_count_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqBaseLookupValid) {
    ++g_replay_liq_sideband_stats.liq_base_lookup_valid;
  }
  if (dut.io_reducedLoadReplayLiqBaseLookupGranted) {
    ++g_replay_liq_sideband_stats.liq_base_lookup_granted;
  }
  if (dut.io_reducedLoadReplayLiqBaseDataReturned) {
    ++g_replay_liq_sideband_stats.liq_base_data_returned;
  }
  if (dut.io_reducedLoadReplayLiqBaseLineValidMask != 0) {
    ++g_replay_liq_sideband_stats.liq_base_line_valid_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessCandidateValid) {
    ++g_replay_liq_sideband_stats.launch_readiness_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBaseDataReady) {
    ++g_replay_liq_sideband_stats.launch_readiness_base_data_ready;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessSourcesReturned) {
    ++g_replay_liq_sideband_stats.launch_readiness_sources_returned;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessReady) {
    ++g_replay_liq_sideband_stats.launch_readiness_ready;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessEnable) {
    ++g_replay_liq_sideband_stats.launch_readiness_enable;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.launch_readiness_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByNoCandidate) {
    ++g_replay_liq_sideband_stats.launch_readiness_blocked_by_no_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByBaseLookup) {
    ++g_replay_liq_sideband_stats.launch_readiness_blocked_by_base_lookup;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByBaseData) {
    ++g_replay_liq_sideband_stats.launch_readiness_blocked_by_base_data;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByScb) {
    ++g_replay_liq_sideband_stats.launch_readiness_blocked_by_scb;
  }
  if (dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByReturn) {
    ++g_replay_liq_sideband_stats.launch_readiness_blocked_by_return;
  }
  if (dut.io_reducedLoadReplayLiqWaitStoreMask != 0) {
    ++g_replay_liq_sideband_stats.liq_wait_store_mask_nonzero;
  }
  const bool liq_replay_wake_valid = dut.io_reducedLoadReplayLiqReplayWakeValid;
  const bool liq_replay_wake_flush = dut.io_reducedLoadReplayLiqReplayWakeFlush;
  const bool liq_replay_wake_store_unit_full_match =
      dut.io_reducedLoadReplayLiqReplayWakeStoreUnitFullMatchMask != 0;
  if (liq_replay_wake_valid) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_valid;
  }
  if (liq_replay_wake_valid && !liq_replay_wake_flush) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_active;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakeWaitStoreCandidateMask != 0) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_wait_store_candidate;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakeBidMatchMask != 0) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_bid_match;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakeLsIdMatchMask != 0) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_lsid_match;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakePcMatchMask != 0) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_pc_match;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakeFullMatchMask != 0) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_full_match;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakeStoreUnit) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_store_unit;
  }
  if (liq_replay_wake_store_unit_full_match) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_store_unit_full_match;
  }
  if (liq_replay_wake_store_unit_full_match && !liq_replay_wake_flush) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_store_unit_full_match_active;
  }
  if (liq_replay_wake_valid && liq_replay_wake_flush && liq_replay_wake_store_unit_full_match) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_store_unit_full_match_flush_blocked;
  }
  if (dut.io_reducedLoadReplayLiqReplayWakeWaitStoreClearMask != 0) {
    ++g_replay_liq_sideband_stats.liq_replay_wake_wait_store_clear;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnCandidateValid) {
    ++g_replay_liq_sideband_stats.source_return_candidate_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqSourceReturnCandidateValid) {
    ++g_replay_liq_sideband_stats.source_return_candidate_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotReady) {
    ++g_replay_liq_sideband_stats.source_return_store_snapshot_ready;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotReady) {
    ++g_replay_liq_sideband_stats.source_return_store_snapshot_ready_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSourceReturned) {
    ++g_replay_liq_sideband_stats.source_return_store_source_returned;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbSourceReturned) {
    ++g_replay_liq_sideband_stats.source_return_scb_source_returned;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnSourceReturned) {
    ++g_replay_liq_sideband_stats.source_return_source_returned;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.source_return_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnBlockedByNoCandidate) {
    ++g_replay_liq_sideband_stats.source_return_blocked_by_no_candidate;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnBlockedByBaseData) {
    ++g_replay_liq_sideband_stats.source_return_blocked_by_base_data;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnBlockedByStoreSnapshot) {
    ++g_replay_liq_sideband_stats.source_return_blocked_by_store_snapshot;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnBlockedByScb) {
    ++g_replay_liq_sideband_stats.source_return_blocked_by_scb;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveRequestActive) {
    ++g_replay_liq_sideband_stats.source_return_store_snapshot_live_request_active;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveEvidenceValid) {
    ++g_replay_liq_sideband_stats.source_return_store_snapshot_live_evidence_valid;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveActive) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_active;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveRequestActive) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_request_active;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveEvidenceValid) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_evidence_valid;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLivePending) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_pending;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveReturned) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_returned;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveBlockedByRequestDisabled) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_blocked_by_request_disabled;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveBlockedByNoPending) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_blocked_by_no_pending;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnScbLiveBlockedByScbReturn) {
    ++g_replay_liq_sideband_stats.source_return_scb_live_blocked_by_scb_return;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueIssued) {
    ++g_replay_liq_sideband_stats.source_return_query_issued;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueIssued) {
    ++g_replay_liq_sideband_stats.source_return_query_issued_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyValid) {
    ++g_replay_liq_sideband_stats.source_return_response_apply_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyValid) {
    ++g_replay_liq_sideband_stats.source_return_response_apply_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowStatePlanValid) {
    ++g_replay_liq_sideband_stats.source_return_row_state_plan_valid;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationCandidateValid) {
    ++g_replay_liq_sideband_stats.source_row_mutation_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationLivePermit) {
    ++g_replay_liq_sideband_stats.source_row_mutation_live_permit;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationRequestValid) {
    ++g_replay_liq_sideband_stats.source_row_mutation_request_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationRequestValid) {
    ++g_replay_liq_sideband_stats.source_row_mutation_request_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadProof) {
    ++g_replay_liq_sideband_stats.source_row_mutation_blocked_by_head_proof;
  }
  if (dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByLiveDisabled) {
    ++g_replay_liq_sideband_stats.source_row_mutation_blocked_by_live_disabled;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataCandidateValid) {
    ++g_replay_liq_sideband_stats.return_data_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataRequestByteMask != 0) {
    ++g_replay_liq_sideband_stats.return_data_request_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBytesComplete) {
    ++g_replay_liq_sideband_stats.return_data_bytes_complete;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataCrossLine) {
    ++g_replay_liq_sideband_stats.return_data_cross_line;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataSizeSupported) {
    ++g_replay_liq_sideband_stats.return_data_size_supported;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataValid) {
    ++g_replay_liq_sideband_stats.return_data_valid;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.return_data_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBlockedByNoCandidate) {
    ++g_replay_liq_sideband_stats.return_data_blocked_by_no_candidate;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBlockedByZeroSize) {
    ++g_replay_liq_sideband_stats.return_data_blocked_by_zero_size;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBlockedByUnsupportedSize) {
    ++g_replay_liq_sideband_stats.return_data_blocked_by_unsupported_size;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBlockedByCrossLine) {
    ++g_replay_liq_sideband_stats.return_data_blocked_by_cross_line;
  }
  if (dut.io_reducedLoadReplayLiqReturnDataBlockedByIncompleteBytes) {
    ++g_replay_liq_sideband_stats.return_data_blocked_by_incomplete_bytes;
  }
  if (dut.io_reducedLoadReplayLiqReturnPublishCandidateValid) {
    ++g_replay_liq_sideband_stats.return_publish_candidate_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqReturnPublishCandidateValid) {
    ++g_replay_liq_sideband_stats.return_publish_candidate_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqReturnPublishDataReady) {
    ++g_replay_liq_sideband_stats.return_publish_data_ready;
  }
  if (dut.io_reducedLoadReplayLiqReturnPublishReady) {
    ++g_replay_liq_sideband_stats.return_publish_ready;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqReturnPublishReady) {
    ++g_replay_liq_sideband_stats.return_publish_ready_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqReturnPublishBlockedByNoCandidate) {
    ++g_replay_liq_sideband_stats.return_publish_blocked_by_no_candidate;
  }
  if (dut.io_reducedLoadReplayLiqReturnPublishBlockedByData) {
    ++g_replay_liq_sideband_stats.return_publish_blocked_by_data;
  }
  if (dut.io_reducedLoadReplayLiqReturnPublishBlockedByConsumer) {
    ++g_replay_liq_sideband_stats.return_publish_blocked_by_consumer;
  }
  if (dut.io_reducedLoadReplayLiqLretPayloadCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_payload_candidate_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPayloadCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_payload_candidate_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPayloadValid) {
    ++g_replay_liq_sideband_stats.lret_payload_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPayloadValid) {
    ++g_replay_liq_sideband_stats.lret_payload_valid_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPayloadWakeupRequired) {
    ++g_replay_liq_sideband_stats.lret_payload_wakeup_required;
  }
  if (dut.io_reducedLoadReplayLiqLretPayloadBlockedByNoCandidate) {
    ++g_replay_liq_sideband_stats.lret_payload_blocked_by_no_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPayloadBlockedByData) {
    ++g_replay_liq_sideband_stats.lret_payload_blocked_by_data;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlCandidateValid) {
    ++g_replay_liq_sideband_stats.publish_control_candidate_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqPublishControlCandidateValid) {
    ++g_replay_liq_sideband_stats.publish_control_candidate_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlLiveEnable) {
    ++g_replay_liq_sideband_stats.publish_control_live_enable;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqPublishControlLiveEnable) {
    ++g_replay_liq_sideband_stats.publish_control_live_enable_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlArmed) {
    ++g_replay_liq_sideband_stats.publish_control_armed;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqPublishControlArmed) {
    ++g_replay_liq_sideband_stats.publish_control_armed_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlFire) {
    ++g_replay_liq_sideband_stats.publish_control_fire;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqPublishControlFire) {
    ++g_replay_liq_sideband_stats.publish_control_fire_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlBlockedByNoPayload) {
    ++g_replay_liq_sideband_stats.publish_control_blocked_by_no_payload;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlBlockedByPublish) {
    ++g_replay_liq_sideband_stats.publish_control_blocked_by_publish;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlBlockedBySideEffects) {
    ++g_replay_liq_sideband_stats.publish_control_blocked_by_side_effects;
  }
  if (dut.io_reducedLoadReplayLiqPublishControlBlockedByLiveDisabled) {
    ++g_replay_liq_sideband_stats.publish_control_blocked_by_live_disabled;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestValid) {
    ++g_replay_liq_sideband_stats.publish_request_valid;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestLret) {
    ++g_replay_liq_sideband_stats.publish_request_lret;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestWriteback) {
    ++g_replay_liq_sideband_stats.publish_request_writeback;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestWakeup) {
    ++g_replay_liq_sideband_stats.publish_request_wakeup;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestMask != 0) {
    ++g_replay_liq_sideband_stats.publish_request_mask_nonzero;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestBlockedByNoFire) {
    ++g_replay_liq_sideband_stats.publish_request_blocked_by_no_fire;
  }
  if (dut.io_reducedLoadReplayLiqPublishRequestInvalidFireWithoutPayload) {
    ++g_replay_liq_sideband_stats.publish_request_invalid_fire_without_payload;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkEnqueueReady) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_ready;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkEnqueueReady) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_ready_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkEnqueueDropped) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_dropped;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkEnqueueDropped) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_dropped_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkDrainValid) {
    ++g_replay_liq_sideband_stats.lret_sink_drain_valid;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkDrainValid) {
    ++g_replay_liq_sideband_stats.lret_sink_drain_valid_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
    ++g_replay_liq_sideband_stats.lret_sink_drain_fire;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
    ++g_replay_liq_sideband_stats.lret_sink_drain_fire_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_same_cycle_drain_fire;
  }
  if (w2_slot_occupied &&
      dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_same_cycle_drain_fire_w2_occupied;
  }
  if (w2_slot_occupied &&
      dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      !dut.io_reducedLoadReplayLiqLretSinkDrainFire) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_without_drain_fire;
  }
  if (w2_slot_occupied &&
      dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      w2_completion_clear_slot) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_completion_clear_slot;
  }
  if (w2_slot_occupied &&
      dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      w2_clear_intent) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_clear_intent;
  }
  if (w2_slot_occupied &&
      dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      w2_side_effect_fire_complete) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_side_effect_fire_complete;
  }
  if (w2_slot_occupied &&
      dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted &&
      w2_live_clear) {
    ++g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_live_clear;
  }
  previous_lret_sink_enqueue_accepted_w2 =
      w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkEnqueueAccepted;
  previous_lret_sink_enqueue_w2_completion_clear_slot =
      previous_lret_sink_enqueue_accepted_w2 && w2_completion_clear_slot;
  previous_lret_sink_enqueue_w2_clear_intent =
      previous_lret_sink_enqueue_accepted_w2 && w2_clear_intent;
  previous_lret_sink_enqueue_w2_side_effect_fire_complete =
      previous_lret_sink_enqueue_accepted_w2 && w2_side_effect_fire_complete;
  previous_lret_sink_enqueue_w2_live_clear =
      previous_lret_sink_enqueue_accepted_w2 && w2_live_clear;
  if (dut.io_reducedLoadReplayLiqLretSinkPending) {
    ++g_replay_liq_sideband_stats.lret_sink_pending;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretSinkPending) {
    ++g_replay_liq_sideband_stats.lret_sink_pending_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCaptureCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_capture_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordPayloadValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_payload_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCaptureValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_capture_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCaptureReady) {
    ++g_replay_liq_sideband_stats.w2_retire_record_capture_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCaptureAccepted) {
    ++g_replay_liq_sideband_stats.w2_retire_record_capture_accepted;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCaptureAccepted) {
    ++g_replay_liq_sideband_stats.w2_retire_record_capture_accepted_w2_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCaptureDropped) {
    ++g_replay_liq_sideband_stats.w2_retire_record_capture_dropped;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRecordValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_record_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRecordReady) {
    ++g_replay_liq_sideband_stats.w2_retire_record_record_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRecordFire) {
    ++g_replay_liq_sideband_stats.w2_retire_record_record_fire;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordPending) {
    ++g_replay_liq_sideband_stats.w2_retire_record_pending;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCapturedWithLretEnqueue) {
    ++g_replay_liq_sideband_stats.w2_retire_record_captured_with_lret_enqueue;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRecordFromLretEnqueue) {
    ++g_replay_liq_sideband_stats.w2_retire_record_record_from_lret_enqueue;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordBlockedByInvalidPayload) {
    ++g_replay_liq_sideband_stats.w2_retire_record_blocked_by_invalid_payload;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordBlockedByFull) {
    ++g_replay_liq_sideband_stats.w2_retire_record_blocked_by_full;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkFull) {
    ++g_replay_liq_sideband_stats.lret_sink_full;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkBlockedByNoPayload) {
    ++g_replay_liq_sideband_stats.lret_sink_blocked_by_no_payload;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkBlockedByFull) {
    ++g_replay_liq_sideband_stats.lret_sink_blocked_by_full;
  }
  if (dut.io_reducedLoadReplayLiqLretSinkBlockedByDrain) {
    ++g_replay_liq_sideband_stats.lret_sink_blocked_by_drain;
  }
  observe_replay_lret_shadow_fifo(dut);
  observe_replay_lret_commit_history(dut);
  if (dut.io_reducedLoadReplayLiqLretDrainPermitAnyPipeFree) {
    ++g_replay_liq_sideband_stats.lret_drain_permit_any_pipe_free;
  }
  if (dut.io_reducedLoadReplayLiqLretDrainPermitReady) {
    ++g_replay_liq_sideband_stats.lret_drain_permit_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretDrainPermitBlockedByNoEntry) {
    ++g_replay_liq_sideband_stats.lret_drain_permit_blocked_by_no_entry;
  }
  if (dut.io_reducedLoadReplayLiqLretDrainPermitBlockedByPipeFull) {
    ++g_replay_liq_sideband_stats.lret_drain_permit_blocked_by_pipe_full;
  }
  if (dut.io_reducedLoadReplayLiqLretDrainPermitPipeOccupiedMask != 0) {
    ++g_replay_liq_sideband_stats.lret_drain_permit_pipe_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataRobRowValid) {
    ++g_replay_liq_sideband_stats.lret_iex_data_rob_row_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataRobRowNeedFlush) {
    ++g_replay_liq_sideband_stats.lret_iex_data_rob_row_need_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataRobRowBlockedByInvalidRid) {
    ++g_replay_liq_sideband_stats.lret_iex_data_rob_row_blocked_by_invalid_rid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataRobRowBlockedByFree) {
    ++g_replay_liq_sideband_stats.lret_iex_data_rob_row_blocked_by_free;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataRobRowBlockedByStaleRid) {
    ++g_replay_liq_sideband_stats.lret_iex_data_rob_row_blocked_by_stale_rid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_iex_data_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataWouldDrain) {
    ++g_replay_liq_sideband_stats.lret_iex_data_would_drain;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataSetMemDataValid) {
    ++g_replay_liq_sideband_stats.lret_iex_data_set_mem_data_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByFlush) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByNoEntry) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_no_entry;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByInvalidEntry) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_invalid_entry;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByDrain) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_drain;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByRobMissing) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_rob_missing;
  }
  if (dut.io_reducedLoadReplayLiqLretIexDataBlockedByNeedFlush) {
    ++g_replay_liq_sideband_stats.lret_iex_data_blocked_by_need_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveValid) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveReadyForPipeInsert) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_ready_for_pipe_insert;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveMarkAllDestinationsDataValid) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_mark_all_destinations_data_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveMarkDestinationDataValid) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_mark_destination_data_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveRetLaneIncrement) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_ret_lane_increment;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveBlockedByFlush) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveBlockedByNoSetMemData) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_no_set_mem_data;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveBlockedByUnsupportedMultiLane) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_unsupported_multi_lane;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveBlockedByInvalidRid) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_invalid_rid;
  }
  if (dut.io_reducedLoadReplayLiqLretRobResolveBlockedByNoDestination) {
    ++g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_no_destination;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionCompleteValid) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_complete_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionReadyForPipeInsert) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_ready_for_pipe_insert;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionRequiresAllLanes) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_requires_all_lanes;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByFlush) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByNoResolve) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_no_resolve;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByZeroReturnedLanes) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_zero_returned_lanes;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByInvalidRealReqCnt) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_invalid_real_req_cnt;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByScalarLoadPairIncomplete) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_scalar_load_pair_incomplete;
  }
  if (dut.io_reducedLoadReplayLiqLretLaneCompletionBlockedByVectorMemIncomplete) {
    ++g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_vector_mem_incomplete;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionTloadCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_tload_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionTileScbSendValid) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_tile_scb_send_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionTileScbIsLast) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_tile_scb_is_last;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionCompleteValid) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_complete_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionReadyForPipeInsert) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_ready_for_pipe_insert;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionBlockedByFlush) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionBlockedByNoLaneCompletion) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_no_lane_completion;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionBlockedByInvalidSubInstCnt) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_invalid_sub_inst_cnt;
  }
  if (dut.io_reducedLoadReplayLiqLretTloadCompletionBlockedByTloadPending) {
    ++g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_tload_pending;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataIsLoadReturnMarked) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_is_load_return_marked;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataLoadBranchResolveCalled) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_load_branch_resolve_called;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataLoadBranchResolveSideEffectValid) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_load_branch_resolve_side_effect_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataPipeCycleSidebandValid) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_pipe_cycle_sideband_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataReadyForPipeInsert) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_ready_for_pipe_insert;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataBlockedByFlush) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_blocked_by_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretFinalMetadataBlockedByNoTloadCompletion) {
    ++g_replay_liq_sideband_stats.lret_final_metadata_blocked_by_no_tload_completion;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsSidebandValid) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_sideband_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsIqNameSidebandValid) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_iq_name_sideband_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsLdRntCycleValid) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_ld_rnt_cycle_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsUpdateValid) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_update_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsLatencyUnderflow) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_latency_underflow;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsReadyForPipeInsert) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_ready_for_pipe_insert;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsBlockedByDisabled) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_blocked_by_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsBlockedByFlush) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_blocked_by_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretTimingStatsBlockedByNoFinalMetadata) {
    ++g_replay_liq_sideband_stats.lret_timing_stats_blocked_by_no_final_metadata;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertValid) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertIsLoadReturn) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_is_load_return;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertWakeupRequired) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_wakeup_required;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertBlockedByNoSetMemData) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_blocked_by_no_set_mem_data;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertBlockedByNoPipe) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_blocked_by_no_pipe;
  }
  if (dut.io_reducedLoadReplayLiqLretIexPipeInsertBlockedByInvalidRid) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_blocked_by_invalid_rid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_residency_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyWriteValid) {
    ++g_replay_liq_sideband_stats.lret_residency_write_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyLiveEnable) {
    ++g_replay_liq_sideband_stats.lret_residency_live_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyBlockedByLiveDisabled) {
    ++g_replay_liq_sideband_stats.lret_residency_blocked_by_live_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencySlotAccepted) {
    ++g_replay_liq_sideband_stats.lret_residency_slot_accepted;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencySlotOccupied) {
    ++g_replay_liq_sideband_stats.lret_residency_slot_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyAdvanceCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_residency_advance_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyAdvanceValid) {
    ++g_replay_liq_sideband_stats.lret_residency_advance_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeResidencyAdvanceBlockedByAdvanceDisabled) {
    ++g_replay_liq_sideband_stats.lret_residency_advance_blocked_by_advance_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW1SlotAccepted) {
    ++g_replay_liq_sideband_stats.lret_w1_slot_accepted;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW1SlotOccupied) {
    ++g_replay_liq_sideband_stats.lret_w1_slot_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW1AdvanceCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_w1_advance_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW1AdvanceValid) {
    ++g_replay_liq_sideband_stats.lret_w1_advance_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW1AdvanceBlockedByAdvanceDisabled) {
    ++g_replay_liq_sideband_stats.lret_w1_advance_blocked_by_advance_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotAccepted) {
    ++g_replay_liq_sideband_stats.lret_w2_slot_accepted;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotOccupied) {
    ++g_replay_liq_sideband_stats.lret_w2_slot_occupied;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotBlockedByNoWrite) {
    ++g_replay_liq_sideband_stats.lret_w2_slot_blocked_by_no_write;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotSourceTraceValid) {
    ++g_replay_liq_sideband_stats.lret_w2_slot_source_trace_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestActive) {
    ++g_replay_liq_sideband_stats.w2_atomic_live_active;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestRequestActive) {
    ++g_replay_liq_sideband_stats.w2_atomic_request_active;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestEvidenceValid) {
    ++g_replay_liq_sideband_stats.w2_atomic_evidence_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestSideEffectLiveRequested) {
    ++g_replay_liq_sideband_stats.w2_atomic_side_effect_live_requested;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestPromotionRequested) {
    ++g_replay_liq_sideband_stats.w2_atomic_promotion_requested;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlocked) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByRequestDisabled) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_request_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByNoEvidence) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_evidence;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByModeDisabled) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_mode_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByPolicy) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_policy;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByNoSideEffectSink) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_side_effect_sink;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByNoClearCommit) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_clear_commit;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByNoRowFillCandidate) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_row_fill_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByNoLifecycleRow) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_lifecycle_row;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AtomicLiveRequestBlockedByNoRequiredSideEffect) {
    ++g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_required_side_effect;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SideEffectCandidateValid) {
    ++g_replay_liq_sideband_stats.w2_side_effect_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SideEffectReady) {
    ++g_replay_liq_sideband_stats.w2_side_effect_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SideEffectLiveControlAllRequiredLiveEnabled) {
    ++g_replay_liq_sideband_stats.w2_side_effect_live_all_required_enabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SideEffectFireVectorFireValid) {
    ++g_replay_liq_sideband_stats.w2_side_effect_fire_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SideEffectFireCompleteFireComplete) {
    ++g_replay_liq_sideband_stats.w2_side_effect_fire_complete;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ClearIntentClearIntent) {
    ++g_replay_liq_sideband_stats.w2_clear_intent;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ClearCommitGuardCommitClearReady) {
    ++g_replay_liq_sideband_stats.w2_clear_commit_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlLivePromotion) {
    ++g_replay_liq_sideband_stats.w2_promotion_live;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlLiveClearEnable) {
    ++g_replay_liq_sideband_stats.w2_promotion_live_clear;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlAdvanceLivePromotion) {
    ++g_replay_liq_sideband_stats.w2_promotion_advance_live;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlBlocked) {
    ++g_replay_liq_sideband_stats.w2_promotion_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlBlockedByPromotionDisabled) {
    ++g_replay_liq_sideband_stats.w2_promotion_blocked_by_promotion_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlBlockedByClearIntent) {
    ++g_replay_liq_sideband_stats.w2_promotion_blocked_by_clear_intent;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2PromotionControlInvalidClearIntentWithoutSlot) {
    ++g_replay_liq_sideband_stats.w2_promotion_invalid_clear_without_slot;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadyEmpty) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_empty;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadySameCycleEligible) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_same_cycle_eligible;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadySameCycleReady) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_same_cycle_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadyFutureAdvance) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_future_advance;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadyMatchesCurrent) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_matches_current;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadyBlocked) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RefillReadyInvalidLiveClearWithoutIntent) {
    ++g_replay_liq_sideband_stats.w2_refill_ready_invalid_live_clear_without_intent;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanEmptyWriteEligible) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_empty_write_eligible;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanSameCycleEligible) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_same_cycle_eligible;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanSameCycleReady) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_same_cycle_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanFutureWriteAccept) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_future_write_accept;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanMatchesCurrent) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_matches_current;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanBlocked) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanBlockedByCurrentStorage) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_blocked_by_current_storage;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2SlotReplacePlanInvalidFutureReadyWithoutLiveClear) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_invalid_future_ready_without_live_clear;
  }
  const bool w1_advance_candidate =
      dut.io_reducedLoadReplayLiqLretPipeW1AdvanceCandidateValid;
  const bool w1_advance_valid = dut.io_reducedLoadReplayLiqLretPipeW1AdvanceValid;
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretIexPipeInsertCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_candidate_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretIexPipeInsertValid) {
    ++g_replay_liq_sideband_stats.lret_iex_insert_valid_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeResidencyCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_residency_candidate_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeResidencyWriteValid) {
    ++g_replay_liq_sideband_stats.lret_residency_write_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeResidencySlotOccupied) {
    ++g_replay_liq_sideband_stats.lret_residency_slot_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeResidencyAdvanceCandidateValid) {
    ++g_replay_liq_sideband_stats.lret_residency_advance_candidate_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeResidencyAdvanceValid) {
    ++g_replay_liq_sideband_stats.lret_residency_advance_valid_w2_occupied;
  }
  if (w2_slot_occupied && dut.io_reducedLoadReplayLiqLretPipeW1SlotOccupied) {
    ++g_replay_liq_sideband_stats.lret_w1_slot_w2_occupied;
  }
  if (w2_slot_occupied && w1_advance_candidate) {
    ++g_replay_liq_sideband_stats.lret_w1_advance_candidate_w2_occupied;
  }
  if (w2_slot_occupied && w1_advance_valid) {
    ++g_replay_liq_sideband_stats.lret_w1_advance_valid_w2_occupied;
  }
  static bool prev_w1_advance_candidate = false;
  static bool prev_w2_clear_intent = false;
  static bool prev_w2_live_clear = false;
  static int cycles_since_w1_advance_candidate = -1;
  static int cycles_since_w2_live_clear = -1;
  static bool recent_w1_advance_lsid_valid = false;
  static std::uint64_t recent_w1_advance_lsid_value = 0;
  static bool recent_w2_live_clear_lsid_valid = false;
  static std::uint64_t recent_w2_live_clear_lsid_value = 0;
  if (cycles_since_w1_advance_candidate >= 0) {
    ++cycles_since_w1_advance_candidate;
  }
  if (cycles_since_w2_live_clear >= 0) {
    ++cycles_since_w2_live_clear;
  }
  if (w2_slot_occupied && w1_advance_candidate) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_overlap_candidate_occupied;
  }
  if (w2_slot_occupied && w1_advance_candidate && w2_clear_intent) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_overlap_candidate_clear_intent;
  }
  if (w2_slot_occupied && w1_advance_candidate && w2_live_clear) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_overlap_candidate_live_clear;
    const bool w1_lsid_valid = dut.io_reducedLoadReplayLiqLretPipeW1SlotLoadLsIdValid;
    const std::uint64_t w1_lsid_value =
        static_cast<std::uint64_t>(dut.io_reducedLoadReplayLiqLretPipeW1SlotLoadLsIdValue);
    const bool w2_lsid_valid = dut.io_reducedLoadReplayLiqLretPipeW2SlotLoadLsIdValid;
    const std::uint64_t w2_lsid_value =
        static_cast<std::uint64_t>(dut.io_reducedLoadReplayLiqLretPipeW2SlotLoadLsIdValue);
    if (w1_lsid_valid && w2_lsid_valid) {
      if (w1_lsid_value == w2_lsid_value) {
        ++g_replay_liq_sideband_stats.w2_slot_replace_overlap_live_clear_same_lsid;
      } else {
        ++g_replay_liq_sideband_stats.w2_slot_replace_overlap_live_clear_different_lsid;
      }
    } else {
      ++g_replay_liq_sideband_stats.w2_slot_replace_overlap_live_clear_unknown_lsid;
    }
  }
  if (w2_slot_occupied && w2_live_clear && !w1_advance_candidate) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_without_w1_candidate;
  }
  if (w2_slot_occupied && w1_advance_candidate && !w2_live_clear) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_without_live_clear;
  }
  if (w2_slot_occupied && w1_advance_valid && w2_live_clear) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_advance_valid_on_live_clear;
  }
  if (w2_slot_occupied && prev_w1_advance_candidate && w2_clear_intent) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_cycle_before_clear_intent;
  }
  if (w2_slot_occupied && prev_w1_advance_candidate && w2_live_clear) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_cycle_before_live_clear;
  }
  if (w2_slot_occupied && prev_w2_clear_intent && w1_advance_candidate) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_clear_intent_cycle_before_w1_candidate;
  }
  if (w2_slot_occupied && prev_w2_live_clear && w1_advance_candidate) {
    ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_cycle_before_w1_candidate;
  }
  if (w2_slot_occupied && w2_live_clear && !w1_advance_candidate &&
      cycles_since_w1_advance_candidate >= 2) {
    const bool w2_lsid_valid = dut.io_reducedLoadReplayLiqLretPipeW2SlotLoadLsIdValid;
    const std::uint64_t w2_lsid_value =
        static_cast<std::uint64_t>(dut.io_reducedLoadReplayLiqLretPipeW2SlotLoadLsIdValue);
    if (recent_w1_advance_lsid_valid && w2_lsid_valid) {
      if (recent_w1_advance_lsid_value == w2_lsid_value) {
        ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_same_lsid;
      } else {
        ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_different_lsid;
        if (cycles_since_w1_advance_candidate == 2) {
          ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap2;
        } else if (cycles_since_w1_advance_candidate == 3) {
          ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap3;
        } else if (cycles_since_w1_advance_candidate == 4) {
          ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap4;
        } else {
          ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap5_plus;
        }
      }
    } else {
      ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_unknown_lsid;
    }
    if (cycles_since_w1_advance_candidate == 2) {
      ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap2;
    } else if (cycles_since_w1_advance_candidate == 3) {
      ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap3;
    } else if (cycles_since_w1_advance_candidate == 4) {
      ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap4;
    } else {
      ++g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap5_plus;
    }
  }
  if (w2_slot_occupied && w1_advance_candidate && !w2_live_clear &&
      cycles_since_w2_live_clear >= 2) {
    const bool w1_lsid_valid = dut.io_reducedLoadReplayLiqLretPipeW1SlotLoadLsIdValid;
    const std::uint64_t w1_lsid_value =
        static_cast<std::uint64_t>(dut.io_reducedLoadReplayLiqLretPipeW1SlotLoadLsIdValue);
    const bool different_lsid =
        recent_w2_live_clear_lsid_valid && w1_lsid_valid &&
        recent_w2_live_clear_lsid_value != w1_lsid_value;
    if (cycles_since_w2_live_clear == 2) {
      ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap2;
      if (different_lsid) {
        ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap2;
      }
    } else if (cycles_since_w2_live_clear == 3) {
      ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap3;
      if (different_lsid) {
        ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap3;
      }
    } else if (cycles_since_w2_live_clear == 4) {
      ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap4;
      if (different_lsid) {
        ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap4;
      }
    } else {
      ++g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap5_plus;
      if (different_lsid) {
        ++g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap5_plus;
      }
    }
  }
  prev_w1_advance_candidate = w1_advance_candidate;
  prev_w2_clear_intent = w2_clear_intent;
  prev_w2_live_clear = w2_live_clear;
  if (w1_advance_candidate) {
    cycles_since_w1_advance_candidate = 0;
    recent_w1_advance_lsid_valid =
        dut.io_reducedLoadReplayLiqLretPipeW1SlotLoadLsIdValid;
    recent_w1_advance_lsid_value =
        static_cast<std::uint64_t>(dut.io_reducedLoadReplayLiqLretPipeW1SlotLoadLsIdValue);
  }
  if (w2_live_clear) {
    cycles_since_w2_live_clear = 0;
    recent_w2_live_clear_lsid_valid =
        dut.io_reducedLoadReplayLiqLretPipeW2SlotLoadLsIdValid;
    recent_w2_live_clear_lsid_value =
        static_cast<std::uint64_t>(dut.io_reducedLoadReplayLiqLretPipeW2SlotLoadLsIdValue);
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AdvanceControlAdvanceEnable) {
    ++g_replay_liq_sideband_stats.w2_advance_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AdvanceControlReplaceOnClear) {
    ++g_replay_liq_sideband_stats.w2_advance_replace_on_clear;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AdvanceControlUsesFutureAdvance) {
    ++g_replay_liq_sideband_stats.w2_advance_uses_future_advance;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AdvanceControlBlocked) {
    ++g_replay_liq_sideband_stats.w2_advance_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AdvanceControlBlockedByLivePromotionDisabled) {
    ++g_replay_liq_sideband_stats.w2_advance_blocked_by_live_promotion_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2AdvanceControlInvalidFutureWriteWithoutAdvance) {
    ++g_replay_liq_sideband_stats.w2_advance_invalid_future_write_without_advance;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceReady) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceInstructionReady) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_instruction_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceSourceReady) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_source_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceBlocked) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceBlockedByNoMetadata) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_blocked_by_no_metadata;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceBlockedByNoSourceTrace) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_blocked_by_no_source_trace;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceRobLookupRowValid) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_row_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceRobLookupInstructionValid) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_instruction_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceRobLookupBlockedByNeedFlush) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_need_flush;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceRobLookupBlockedByMissingInstruction) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_missing_instruction;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceRobLookupBlockedBySourceTraceDisabled) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowTraceSourceRobLookupBlockedBySourceTraceBeforeCompletion) {
    ++g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_before_completion;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateValid) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateRowFillValid) {
    ++g_replay_liq_sideband_stats.w2_commit_row_fill_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateCompleteRowValid) {
    ++g_replay_liq_sideband_stats.w2_commit_row_complete_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateBlocked) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateBlockedByNoMetadata) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_no_metadata;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateBlockedByNoSourceTrace) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_no_source_trace;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateBlockedByInvalidSize) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_invalid_size;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateBlockedByNonGprDestination) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_non_gpr_destination;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2CommitRowCandidateBlockedByRowFillDisabled) {
    ++g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_row_fill_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateRowFillValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_fill_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateCompleteRowValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_complete_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlocked) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlockedByNoRecord) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_no_record;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlockedByNoMetadata) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_no_metadata;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlockedByNoSourceTrace) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_no_source_trace;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlockedByInvalidSize) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_invalid_size;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlockedByNonGprDestination) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_non_gpr_destination;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordCommitRowCandidateBlockedByRowFillDisabled) {
    ++g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureIntent) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_intent;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCapturePayloadRidValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_payload_rid_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataW2RidValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_w2_rid_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataW2RidMatchesCapture) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_w2_rid_matches_capture;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataW2MetadataReady) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_w2_metadata_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureFromW2) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_from_w2;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureFromDrain) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_from_drain;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureBlockedByNoPayloadRid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_no_payload_rid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureBlockedByNoW2Rid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_rid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureBlockedByRidMismatch) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataCaptureBlockedByNoW2Metadata) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_metadata;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataClearAccepted) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_clear_accepted;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordInstructionMetadataProviderValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_provider_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlCandidateValid) {
    ++g_replay_liq_sideband_stats.w2_row_fill_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlPrerequisitesReady) {
    ++g_replay_liq_sideband_stats.w2_row_fill_prerequisites_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlRowFillEnable) {
    ++g_replay_liq_sideband_stats.w2_row_fill_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlBlockedByRequestDisabled) {
    ++g_replay_liq_sideband_stats.w2_row_fill_blocked_by_request_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlBlockedByNoCandidate) {
    ++g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlBlockedByNoSideEffectCommit) {
    ++g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_side_effect_commit;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlBlockedByNoClearCommit) {
    ++g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_clear_commit;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlBlockedByLiveClearDisabled) {
    ++g_replay_liq_sideband_stats.w2_row_fill_blocked_by_live_clear_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RowFillEnableControlBlockedByNoReplayRowLifecycle) {
    ++g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_replay_row_lifecycle;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleCandidateValid) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleSlotIdentityValid) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_slot_identity_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleResolvedRowMatch) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_resolved_row_match;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleRowClearReady) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_row_clear_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleReady) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleBlockedByNoResolvedRow) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_blocked_by_no_resolved_row;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleBlockedByMultipleResolvedRows) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_blocked_by_multiple_resolved_rows;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleBlockedByLifecycleClearDisabled) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_blocked_by_clear_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleRequestControlLifecycleClearRequestEnable) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_clear_request_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2ReplayRowLifecycleCommitPermitLifecycleClearCommitEnable) {
    ++g_replay_liq_sideband_stats.w2_lifecycle_clear_commit_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleCandidateValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_candidate_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleSlotIdentityValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_slot_identity_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleResolvedRowMatch) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_resolved_row_match;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRowClearReady) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_row_clear_ready;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleBlockedByNoResolvedRow) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_blocked_by_no_resolved_row;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleBlockedByMultipleResolvedRows) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleBlockedByLifecycleClearDisabled) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_blocked_by_clear_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRequestProbeCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRequestProbeLivePromotionCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_live_promotion_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRequestProbeBlockedByNoLifecycleRow) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_lifecycle_row;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRequestProbeBlockedByNoAtomicRequest) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_atomic_request;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRequestProbeBlockedByNoRowFillCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_row_fill_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleRequestProbeBlockedByNoRowFillEnable) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_row_fill_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordAtomicRequestProbeEvidenceValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_atomic_request_evidence_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordAtomicRequestProbeRowFillCandidateAligned) {
    ++g_replay_liq_sideband_stats.w2_retire_record_atomic_request_row_fill_candidate_aligned;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordAtomicRequestProbeRowFillEnableAligned) {
    ++g_replay_liq_sideband_stats.w2_retire_record_atomic_request_row_fill_enable_aligned;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordAtomicRequestProbeBlockedByNoLifecycleRow) {
    ++g_replay_liq_sideband_stats.w2_retire_record_atomic_request_blocked_by_no_lifecycle_row;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordAtomicRequestProbeBlockedByNoRowFillCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordAtomicRequestProbeBlockedByNoRowFillEnable) {
    ++g_replay_liq_sideband_stats.w2_retire_record_atomic_request_blocked_by_no_row_fill_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRowFillEnableRequestEvidenceValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_request_evidence_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRowFillEnableCandidateAligned) {
    ++g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_candidate_aligned;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRowFillEnable) {
    ++g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRowFillEnableBlockedByRequestDisabled) {
    ++g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_blocked_by_request_disabled;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRowFillEnableBlockedByNoLifecycleRow) {
    ++g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_blocked_by_no_lifecycle_row;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRowFillEnableBlockedByNoRowFillCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_blocked_by_no_row_fill_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceCaptureIntent) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_capture_intent;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceCaptureFromLifecycle) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_capture_from_lifecycle;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceCaptureBlockedByNoLifecycle) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceClearAccepted) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_clear_accepted;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceProviderValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_provider_valid;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceProviderValidWithoutRecord) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_provider_valid_without_record;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordLifecycleEvidenceRecordValidWithoutProvider) {
    ++g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_record_valid_without_provider;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRobFallbackCapturePhysicalComplete) {
    ++g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_capture_physical_complete;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRobFallbackCandidate) {
    ++g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_candidate;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRobFallbackDuplicatePhysicalComplete) {
    ++g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_duplicate_physical_complete;
  }
  if (dut.io_reducedLoadReplayLiqLretPipeW2RetireRecordRobFallbackCompleteValid) {
    ++g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_complete_valid;
  }
  if (resolve_queue_push_accepted) {
    ++g_replay_liq_sideband_stats.resolve_queue_push_accepted;
  }
  if (resolve_queue_valid) {
    ++g_replay_liq_sideband_stats.resolve_queue_valid;
  }
  if (mdb_conflict_store_valid) {
    ++g_replay_liq_sideband_stats.mdb_conflict_store_valid;
  }
  if (mdb_conflict_store_with_resolve_queue_valid) {
    ++g_replay_liq_sideband_stats.mdb_conflict_store_with_resolve_queue_valid;
  }
  if (mdb_conflict_store_valid && !resolve_queue_valid) {
    ++g_replay_liq_sideband_stats.mdb_conflict_store_without_resolve_queue_valid;
  }
  if (dut.io_reducedMdbConflictActiveCandidateMask != 0) {
    ++g_replay_liq_sideband_stats.mdb_conflict_active_candidate;
  }
  if (mdb_conflict_resolve_candidate) {
    ++g_replay_liq_sideband_stats.mdb_conflict_resolve_candidate;
  }
  if (dut.io_reducedMdbConflictValid) {
    ++g_replay_liq_sideband_stats.mdb_conflict_valid;
  }
  if (dut.io_reducedMdbFanoutRecordValid) {
    ++g_replay_liq_sideband_stats.mdb_fanout_record_valid;
  }
  if (dut.io_reducedMdbFanoutRecordAccepted) {
    ++g_replay_liq_sideband_stats.mdb_fanout_record_accepted;
  }
  if (dut.io_reducedMdbFanoutRecordProcessed) {
    ++g_replay_liq_sideband_stats.mdb_fanout_record_processed;
  }
  if (dut.io_reducedMdbFanoutBmdbReportValid) {
    ++g_replay_liq_sideband_stats.mdb_fanout_bmdb_report;
  }
  if (dut.io_reducedMdbFanoutSsitValidMask != 0) {
    ++g_replay_liq_sideband_stats.mdb_fanout_ssit_nonempty;
  }
  if (dut.io_reducedMdbFanoutLookupValid) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_valid;
  }
  if (dut.io_reducedMdbFanoutLookupAccepted) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_accepted;
  }
  if (dut.io_reducedMdbFanoutLookupProcessed) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_processed;
  }
  if (dut.io_reducedMdbFanoutLookupTableHit) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_table_hit;
  }
  if (dut.io_reducedMdbFanoutLookupFirstAfterNuke) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_first_after_nuke;
  }
  if (dut.io_reducedMdbFanoutLookupConfBlocked) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_conf_blocked;
  }
  if (dut.io_reducedMdbFanoutLookupWeightBlocked) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lookup_weight_blocked;
  }
  if (dut.io_reducedMdbFanoutLuOutValid) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lu_out_valid;
  }
  if (dut.io_reducedMdbFanoutLuOutHit) {
    ++g_replay_liq_sideband_stats.mdb_fanout_lu_out_hit;
  }
  if (dut.io_reducedMdbFanoutSuOutValid) {
    ++g_replay_liq_sideband_stats.mdb_fanout_su_out_valid;
  }
  if (dut.io_reducedMdbFanoutSuOutHit) {
    ++g_replay_liq_sideband_stats.mdb_fanout_su_out_hit;
  }
  if (dut.io_reducedMdbFanoutSuWakeupValid) {
    ++g_replay_liq_sideband_stats.mdb_fanout_su_wakeup_valid;
  }
  if (dut.io_reducedMdbLookupWaitPlanLookupHit) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_lookup_hit;
  }
  if (dut.io_reducedMdbLookupWaitPlanWaitIntentValid) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_wait_intent_valid;
  }
  if (dut.io_reducedMdbLookupWaitPlanRequestValid) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_request_valid;
  }
  if (dut.io_reducedMdbLookupWaitPlanBlockedByNoTarget) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_blocked_by_no_target;
  }
  if (dut.io_reducedMdbLookupWaitPlanBlockedByMissingStoreIndex) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_blocked_by_missing_store_index;
  }
  if (dut.io_reducedMdbLookupWaitPlanBlockedByMissingStoreLsId) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_blocked_by_missing_store_lsid;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_active) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_active;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_valid) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_valid;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_sourceStoreIndexFits) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_source_store_index_fits;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_blockedByDisabled) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_blocked_by_disabled;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_blockedByFlush) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_blocked_by_flush;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_blockedByNoRequest) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_blocked_by_no_request;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_invalidStoreIndexOutOfRange) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_store_index_out_of_range;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_invalidConflictingStatusWrite) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_conflicting_status_write;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_invalidWaitStoreWithoutWaitStatus) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_wait_store_without_wait_status;
  }
  if (dut.io_reducedMdbLookupWaitPlanBridge_invalidReturnWithoutSplitSources) {
    ++g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_return_without_split_sources;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationBridgeValid) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_bridge_valid;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationSelectedSourceReturn) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_selected_source_return;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationSelectedMdbWaitPlan) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_selected_mdb_wait_plan;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationSourceConflict) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_source_conflict;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationSelectedMdbWaitPlan &&
      dut.io_reducedLoadReplayLiqRowMutationWriteEnable) {
    ++g_replay_liq_sideband_stats.mdb_wait_plan_row_mutation_write_enable;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationWriteEnable) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_write_enable;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationApplyValid) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_apply_valid;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationBlockedByBridge) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_blocked_by_bridge;
  }
  if (dut.io_reducedLoadReplayLiqRowMutationBlockedByControl) {
    ++g_replay_liq_sideband_stats.liq_row_mutation_blocked_by_control;
  }
#else
  (void)dut;
#endif
}

bool write_replay_liq_sideband_stats(const std::string &path) {
  if (path.empty()) {
    return true;
  }
  std::ofstream out(path);
  if (!out) {
    std::cerr << "failed to open sideband stats path: " << path << "\n";
    return false;
  }
  out << "{\n"
      << "  \"schema\": \"linxcore.frontend_fetch_rf_alu.sideband_stats.v37\",\n"
#if defined(LINXCORE_REDUCED_STORE_REPLAY_LIQ_TRACE_TOP)
      << "  \"reduced_store_replay_liq_top\": true,\n"
#else
      << "  \"reduced_store_replay_liq_top\": false,\n"
#endif
      << "  \"replay_liq\": {\n"
      << "    \"cycles_sampled\": " << g_replay_liq_sideband_stats.cycles_sampled << ",\n"
      << "    \"load_lookup_valid\": "
      << g_replay_liq_sideband_stats.load_lookup_valid << ",\n"
      << "    \"load_lookup_execute_granted\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_granted << ",\n"
      << "    \"load_lookup_execute_with_eligible_store\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_with_eligible_store << ",\n"
      << "    \"load_lookup_execute_with_wait_store\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_with_wait_store << ",\n"
      << "    \"execute_load_wait_hold\": "
      << g_replay_liq_sideband_stats.execute_load_wait_hold << ",\n"
      << "    \"resident_store_eligible\": "
      << g_replay_liq_sideband_stats.resident_store_eligible << ",\n"
      << "    \"resident_store_ready_forward\": "
      << g_replay_liq_sideband_stats.resident_store_ready_forward << ",\n"
      << "    \"resident_store_wait_blocked\": "
      << g_replay_liq_sideband_stats.resident_store_wait_blocked << ",\n"
      << "    \"resident_store_wait_store_valid\": "
      << g_replay_liq_sideband_stats.resident_store_wait_store_valid << ",\n"
      << "    \"store_stq_resident\": "
      << g_replay_liq_sideband_stats.store_stq_resident << ",\n"
      << "    \"store_stq_addr_ready\": "
      << g_replay_liq_sideband_stats.store_stq_addr_ready << ",\n"
      << "    \"store_stq_data_ready\": "
      << g_replay_liq_sideband_stats.store_stq_data_ready << ",\n"
      << "    \"store_stq_addr_ready_not_data_ready\": "
      << g_replay_liq_sideband_stats.store_stq_addr_ready_not_data_ready << ",\n"
      << "    \"store_stq_addr_and_data_ready\": "
      << g_replay_liq_sideband_stats.store_stq_addr_and_data_ready << ",\n"
      << "    \"load_lookup_execute_with_addr_ready_not_data_ready\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_with_addr_ready_not_data_ready << ",\n"
      << "    \"load_lookup_execute_granted_first_cycle\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_granted_first_cycle << ",\n"
      << "    \"load_lookup_execute_granted_last_cycle\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_granted_last_cycle << ",\n"
      << "    \"store_stq_addr_ready_not_data_ready_first_cycle\": "
      << g_replay_liq_sideband_stats.store_stq_addr_ready_not_data_ready_first_cycle << ",\n"
      << "    \"store_stq_addr_ready_not_data_ready_last_cycle\": "
      << g_replay_liq_sideband_stats.store_stq_addr_ready_not_data_ready_last_cycle << ",\n"
      << "    \"store_stq_addr_and_data_ready_first_cycle\": "
      << g_replay_liq_sideband_stats.store_stq_addr_and_data_ready_first_cycle << ",\n"
      << "    \"store_stq_addr_and_data_ready_last_cycle\": "
      << g_replay_liq_sideband_stats.store_stq_addr_and_data_ready_last_cycle << ",\n"
      << "    \"load_lookup_execute_with_addr_ready_not_data_ready_first_cycle\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_with_addr_ready_not_data_ready_first_cycle << ",\n"
      << "    \"load_lookup_execute_with_addr_ready_not_data_ready_last_cycle\": "
      << g_replay_liq_sideband_stats.load_lookup_execute_with_addr_ready_not_data_ready_last_cycle << ",\n"
      << "    \"store_sta_dequeue_fire_first_cycle\": "
      << g_replay_liq_sideband_stats.store_sta_dequeue_fire_first_cycle << ",\n"
      << "    \"store_sta_dequeue_fire_last_cycle\": "
      << g_replay_liq_sideband_stats.store_sta_dequeue_fire_last_cycle << ",\n"
      << "    \"store_std_dequeue_fire_first_cycle\": "
      << g_replay_liq_sideband_stats.store_std_dequeue_fire_first_cycle << ",\n"
      << "    \"store_std_dequeue_fire_last_cycle\": "
      << g_replay_liq_sideband_stats.store_std_dequeue_fire_last_cycle << ",\n"
      << "    \"store_sta_exec_valid_first_cycle\": "
      << g_replay_liq_sideband_stats.store_sta_exec_valid_first_cycle << ",\n"
      << "    \"store_sta_exec_valid_last_cycle\": "
      << g_replay_liq_sideband_stats.store_sta_exec_valid_last_cycle << ",\n"
      << "    \"store_std_exec_valid_first_cycle\": "
      << g_replay_liq_sideband_stats.store_std_exec_valid_first_cycle << ",\n"
      << "    \"store_std_exec_valid_last_cycle\": "
      << g_replay_liq_sideband_stats.store_std_exec_valid_last_cycle << ",\n"
      << "    \"store_sta_queue_valid\": "
      << g_replay_liq_sideband_stats.store_sta_queue_valid << ",\n"
      << "    \"store_std_queue_valid\": "
      << g_replay_liq_sideband_stats.store_std_queue_valid << ",\n"
      << "    \"store_sta_queue_only_valid\": "
      << g_replay_liq_sideband_stats.store_sta_queue_only_valid << ",\n"
      << "    \"store_sta_dequeue_fire\": "
      << g_replay_liq_sideband_stats.store_sta_dequeue_fire << ",\n"
      << "    \"store_std_dequeue_fire\": "
      << g_replay_liq_sideband_stats.store_std_dequeue_fire << ",\n"
      << "    \"store_sta_exec_valid\": "
      << g_replay_liq_sideband_stats.store_sta_exec_valid << ",\n"
      << "    \"store_std_exec_valid\": "
      << g_replay_liq_sideband_stats.store_std_exec_valid << ",\n"
      << "    \"store_sta_exec_only_valid\": "
      << g_replay_liq_sideband_stats.store_sta_exec_only_valid << ",\n"
      << "    \"resident_store_wake_valid\": "
      << g_replay_liq_sideband_stats.resident_store_wake_valid << ",\n"
      << "    \"resident_store_wake_ready\": "
      << g_replay_liq_sideband_stats.resident_store_wake_ready << ",\n"
      << "    \"wait_replay_capture_accepted\": "
      << g_replay_liq_sideband_stats.wait_replay_capture_accepted << ",\n"
      << "    \"wait_replay_clear_valid\": "
      << g_replay_liq_sideband_stats.wait_replay_clear_valid << ",\n"
      << "    \"wait_replay_relaunch_valid\": "
      << g_replay_liq_sideband_stats.wait_replay_relaunch_valid << ",\n"
      << "    \"replay_queue_enqueue_accepted\": "
      << g_replay_liq_sideband_stats.replay_queue_enqueue_accepted << ",\n"
      << "    \"replay_queue_out_valid\": "
      << g_replay_liq_sideband_stats.replay_queue_out_valid << ",\n"
      << "    \"replay_queue_out_fire\": "
      << g_replay_liq_sideband_stats.replay_queue_out_fire << ",\n"
      << "    \"liq_alloc_valid\": "
      << g_replay_liq_sideband_stats.liq_alloc_valid << ",\n"
      << "    \"liq_alloc_accepted\": "
      << g_replay_liq_sideband_stats.liq_alloc_accepted << ",\n"
      << "    \"liq_launch_valid\": "
      << g_replay_liq_sideband_stats.liq_launch_valid << ",\n"
      << "    \"liq_launch_accepted\": "
      << g_replay_liq_sideband_stats.liq_launch_accepted << ",\n"
      << "    \"liq_return_complete_repick_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_repick_mask_nonzero << ",\n"
      << "    \"liq_return_complete_source_returned_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_source_returned_mask_nonzero << ",\n"
      << "    \"liq_return_complete_data_complete_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_data_complete_mask_nonzero << ",\n"
      << "    \"liq_return_complete_request_complete_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_request_complete_mask_nonzero << ",\n"
      << "    \"liq_return_complete_candidate_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_candidate_mask_nonzero << ",\n"
      << "    \"liq_return_complete_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_mask_nonzero << ",\n"
      << "    \"liq_return_complete_valid\": "
      << g_replay_liq_sideband_stats.liq_return_complete_valid << ",\n"
      << "    \"liq_return_complete_candidate_count_nonzero\": "
      << g_replay_liq_sideband_stats.liq_return_complete_candidate_count_nonzero << ",\n"
      << "    \"liq_return_complete_valid_w2_occupied\": "
      << g_replay_liq_sideband_stats.liq_return_complete_valid_w2_occupied << ",\n"
      << "    \"liq_base_lookup_valid\": "
      << g_replay_liq_sideband_stats.liq_base_lookup_valid << ",\n"
      << "    \"liq_base_lookup_granted\": "
      << g_replay_liq_sideband_stats.liq_base_lookup_granted << ",\n"
      << "    \"liq_base_data_returned\": "
      << g_replay_liq_sideband_stats.liq_base_data_returned << ",\n"
      << "    \"liq_base_line_valid_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_base_line_valid_mask_nonzero << ",\n"
      << "    \"launch_readiness_candidate_valid\": "
      << g_replay_liq_sideband_stats.launch_readiness_candidate_valid << ",\n"
      << "    \"launch_readiness_base_data_ready\": "
      << g_replay_liq_sideband_stats.launch_readiness_base_data_ready << ",\n"
      << "    \"launch_readiness_sources_returned\": "
      << g_replay_liq_sideband_stats.launch_readiness_sources_returned << ",\n"
      << "    \"launch_readiness_ready\": "
      << g_replay_liq_sideband_stats.launch_readiness_ready << ",\n"
      << "    \"launch_readiness_enable\": "
      << g_replay_liq_sideband_stats.launch_readiness_enable << ",\n"
      << "    \"launch_readiness_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.launch_readiness_blocked_by_disabled << ",\n"
      << "    \"launch_readiness_blocked_by_no_candidate\": "
      << g_replay_liq_sideband_stats.launch_readiness_blocked_by_no_candidate << ",\n"
      << "    \"launch_readiness_blocked_by_base_lookup\": "
      << g_replay_liq_sideband_stats.launch_readiness_blocked_by_base_lookup << ",\n"
      << "    \"launch_readiness_blocked_by_base_data\": "
      << g_replay_liq_sideband_stats.launch_readiness_blocked_by_base_data << ",\n"
      << "    \"launch_readiness_blocked_by_scb\": "
      << g_replay_liq_sideband_stats.launch_readiness_blocked_by_scb << ",\n"
      << "    \"launch_readiness_blocked_by_return\": "
      << g_replay_liq_sideband_stats.launch_readiness_blocked_by_return << ",\n"
      << "    \"liq_wait_store_mask_nonzero\": "
      << g_replay_liq_sideband_stats.liq_wait_store_mask_nonzero << ",\n"
      << "    \"liq_replay_wake_valid\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_valid << ",\n"
      << "    \"liq_replay_wake_active\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_active << ",\n"
      << "    \"liq_replay_wake_wait_store_candidate\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_wait_store_candidate << ",\n"
      << "    \"liq_replay_wake_bid_match\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_bid_match << ",\n"
      << "    \"liq_replay_wake_lsid_match\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_lsid_match << ",\n"
      << "    \"liq_replay_wake_pc_match\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_pc_match << ",\n"
      << "    \"liq_replay_wake_full_match\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_full_match << ",\n"
      << "    \"liq_replay_wake_store_unit\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_store_unit << ",\n"
      << "    \"liq_replay_wake_store_unit_full_match\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_store_unit_full_match << ",\n"
      << "    \"liq_replay_wake_store_unit_full_match_active\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_store_unit_full_match_active << ",\n"
      << "    \"liq_replay_wake_store_unit_full_match_flush_blocked\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_store_unit_full_match_flush_blocked << ",\n"
      << "    \"liq_replay_wake_wait_store_clear\": "
      << g_replay_liq_sideband_stats.liq_replay_wake_wait_store_clear << ",\n"
      << "    \"source_return_candidate_valid\": "
      << g_replay_liq_sideband_stats.source_return_candidate_valid << ",\n"
      << "    \"source_return_store_snapshot_ready\": "
      << g_replay_liq_sideband_stats.source_return_store_snapshot_ready << ",\n"
      << "    \"source_return_store_source_returned\": "
      << g_replay_liq_sideband_stats.source_return_store_source_returned << ",\n"
      << "    \"source_return_scb_source_returned\": "
      << g_replay_liq_sideband_stats.source_return_scb_source_returned << ",\n"
      << "    \"source_return_source_returned\": "
      << g_replay_liq_sideband_stats.source_return_source_returned << ",\n"
      << "    \"source_return_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.source_return_blocked_by_disabled << ",\n"
      << "    \"source_return_blocked_by_no_candidate\": "
      << g_replay_liq_sideband_stats.source_return_blocked_by_no_candidate << ",\n"
      << "    \"source_return_blocked_by_base_data\": "
      << g_replay_liq_sideband_stats.source_return_blocked_by_base_data << ",\n"
      << "    \"source_return_blocked_by_store_snapshot\": "
      << g_replay_liq_sideband_stats.source_return_blocked_by_store_snapshot << ",\n"
      << "    \"source_return_blocked_by_scb\": "
      << g_replay_liq_sideband_stats.source_return_blocked_by_scb << ",\n"
      << "    \"source_return_store_snapshot_live_request_active\": "
      << g_replay_liq_sideband_stats.source_return_store_snapshot_live_request_active << ",\n"
      << "    \"source_return_store_snapshot_live_evidence_valid\": "
      << g_replay_liq_sideband_stats.source_return_store_snapshot_live_evidence_valid << ",\n"
      << "    \"source_return_scb_live_active\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_active << ",\n"
      << "    \"source_return_scb_live_request_active\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_request_active << ",\n"
      << "    \"source_return_scb_live_evidence_valid\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_evidence_valid << ",\n"
      << "    \"source_return_scb_live_pending\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_pending << ",\n"
      << "    \"source_return_scb_live_returned\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_returned << ",\n"
      << "    \"source_return_scb_live_blocked_by_request_disabled\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_blocked_by_request_disabled << ",\n"
      << "    \"source_return_scb_live_blocked_by_no_pending\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_blocked_by_no_pending << ",\n"
      << "    \"source_return_scb_live_blocked_by_scb_return\": "
      << g_replay_liq_sideband_stats.source_return_scb_live_blocked_by_scb_return << ",\n"
      << "    \"source_return_query_issued\": "
      << g_replay_liq_sideband_stats.source_return_query_issued << ",\n"
      << "    \"source_return_response_apply_valid\": "
      << g_replay_liq_sideband_stats.source_return_response_apply_valid << ",\n"
      << "    \"source_return_row_state_plan_valid\": "
      << g_replay_liq_sideband_stats.source_return_row_state_plan_valid << ",\n"
      << "    \"source_return_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.source_return_candidate_w2_occupied << ",\n"
      << "    \"source_return_store_snapshot_ready_w2_occupied\": "
      << g_replay_liq_sideband_stats.source_return_store_snapshot_ready_w2_occupied << ",\n"
      << "    \"source_return_query_issued_w2_occupied\": "
      << g_replay_liq_sideband_stats.source_return_query_issued_w2_occupied << ",\n"
      << "    \"source_return_response_apply_w2_occupied\": "
      << g_replay_liq_sideband_stats.source_return_response_apply_w2_occupied << ",\n"
      << "    \"source_row_mutation_candidate_valid\": "
      << g_replay_liq_sideband_stats.source_row_mutation_candidate_valid << ",\n"
      << "    \"source_row_mutation_live_permit\": "
      << g_replay_liq_sideband_stats.source_row_mutation_live_permit << ",\n"
      << "    \"source_row_mutation_request_valid\": "
      << g_replay_liq_sideband_stats.source_row_mutation_request_valid << ",\n"
      << "    \"source_row_mutation_blocked_by_head_proof\": "
      << g_replay_liq_sideband_stats.source_row_mutation_blocked_by_head_proof << ",\n"
      << "    \"source_row_mutation_blocked_by_live_disabled\": "
      << g_replay_liq_sideband_stats.source_row_mutation_blocked_by_live_disabled << ",\n"
      << "    \"source_row_mutation_request_w2_occupied\": "
      << g_replay_liq_sideband_stats.source_row_mutation_request_w2_occupied << ",\n"
      << "    \"return_data_candidate_valid\": "
      << g_replay_liq_sideband_stats.return_data_candidate_valid << ",\n"
      << "    \"return_data_request_mask_nonzero\": "
      << g_replay_liq_sideband_stats.return_data_request_mask_nonzero << ",\n"
      << "    \"return_data_bytes_complete\": "
      << g_replay_liq_sideband_stats.return_data_bytes_complete << ",\n"
      << "    \"return_data_cross_line\": "
      << g_replay_liq_sideband_stats.return_data_cross_line << ",\n"
      << "    \"return_data_size_supported\": "
      << g_replay_liq_sideband_stats.return_data_size_supported << ",\n"
      << "    \"return_data_valid\": "
      << g_replay_liq_sideband_stats.return_data_valid << ",\n"
      << "    \"return_data_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.return_data_blocked_by_disabled << ",\n"
      << "    \"return_data_blocked_by_no_candidate\": "
      << g_replay_liq_sideband_stats.return_data_blocked_by_no_candidate << ",\n"
      << "    \"return_data_blocked_by_zero_size\": "
      << g_replay_liq_sideband_stats.return_data_blocked_by_zero_size << ",\n"
      << "    \"return_data_blocked_by_unsupported_size\": "
      << g_replay_liq_sideband_stats.return_data_blocked_by_unsupported_size << ",\n"
      << "    \"return_data_blocked_by_cross_line\": "
      << g_replay_liq_sideband_stats.return_data_blocked_by_cross_line << ",\n"
      << "    \"return_data_blocked_by_incomplete_bytes\": "
      << g_replay_liq_sideband_stats.return_data_blocked_by_incomplete_bytes << ",\n"
      << "    \"return_publish_candidate_valid\": "
      << g_replay_liq_sideband_stats.return_publish_candidate_valid << ",\n"
      << "    \"return_publish_data_ready\": "
      << g_replay_liq_sideband_stats.return_publish_data_ready << ",\n"
      << "    \"return_publish_ready\": "
      << g_replay_liq_sideband_stats.return_publish_ready << ",\n"
      << "    \"return_publish_blocked_by_no_candidate\": "
      << g_replay_liq_sideband_stats.return_publish_blocked_by_no_candidate << ",\n"
      << "    \"return_publish_blocked_by_data\": "
      << g_replay_liq_sideband_stats.return_publish_blocked_by_data << ",\n"
      << "    \"return_publish_blocked_by_consumer\": "
      << g_replay_liq_sideband_stats.return_publish_blocked_by_consumer << ",\n"
      << "    \"return_publish_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.return_publish_candidate_w2_occupied << ",\n"
      << "    \"return_publish_ready_w2_occupied\": "
      << g_replay_liq_sideband_stats.return_publish_ready_w2_occupied << ",\n"
      << "    \"lret_payload_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_payload_candidate_valid << ",\n"
      << "    \"lret_payload_valid\": "
      << g_replay_liq_sideband_stats.lret_payload_valid << ",\n"
      << "    \"lret_payload_wakeup_required\": "
      << g_replay_liq_sideband_stats.lret_payload_wakeup_required << ",\n"
      << "    \"lret_payload_blocked_by_no_candidate\": "
      << g_replay_liq_sideband_stats.lret_payload_blocked_by_no_candidate << ",\n"
      << "    \"lret_payload_blocked_by_data\": "
      << g_replay_liq_sideband_stats.lret_payload_blocked_by_data << ",\n"
      << "    \"lret_payload_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_payload_candidate_w2_occupied << ",\n"
      << "    \"lret_payload_valid_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_payload_valid_w2_occupied << ",\n"
      << "    \"publish_control_candidate_valid\": "
      << g_replay_liq_sideband_stats.publish_control_candidate_valid << ",\n"
      << "    \"publish_control_live_enable\": "
      << g_replay_liq_sideband_stats.publish_control_live_enable << ",\n"
      << "    \"publish_control_armed\": "
      << g_replay_liq_sideband_stats.publish_control_armed << ",\n"
      << "    \"publish_control_fire\": "
      << g_replay_liq_sideband_stats.publish_control_fire << ",\n"
      << "    \"publish_control_blocked_by_no_payload\": "
      << g_replay_liq_sideband_stats.publish_control_blocked_by_no_payload << ",\n"
      << "    \"publish_control_blocked_by_publish\": "
      << g_replay_liq_sideband_stats.publish_control_blocked_by_publish << ",\n"
      << "    \"publish_control_blocked_by_side_effects\": "
      << g_replay_liq_sideband_stats.publish_control_blocked_by_side_effects << ",\n"
      << "    \"publish_control_blocked_by_live_disabled\": "
      << g_replay_liq_sideband_stats.publish_control_blocked_by_live_disabled << ",\n"
      << "    \"publish_control_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.publish_control_candidate_w2_occupied << ",\n"
      << "    \"publish_control_live_enable_w2_occupied\": "
      << g_replay_liq_sideband_stats.publish_control_live_enable_w2_occupied << ",\n"
      << "    \"publish_control_armed_w2_occupied\": "
      << g_replay_liq_sideband_stats.publish_control_armed_w2_occupied << ",\n"
      << "    \"publish_control_fire_w2_occupied\": "
      << g_replay_liq_sideband_stats.publish_control_fire_w2_occupied << ",\n"
      << "    \"publish_request_valid\": "
      << g_replay_liq_sideband_stats.publish_request_valid << ",\n"
      << "    \"publish_request_lret\": "
      << g_replay_liq_sideband_stats.publish_request_lret << ",\n"
      << "    \"publish_request_writeback\": "
      << g_replay_liq_sideband_stats.publish_request_writeback << ",\n"
      << "    \"publish_request_wakeup\": "
      << g_replay_liq_sideband_stats.publish_request_wakeup << ",\n"
      << "    \"publish_request_mask_nonzero\": "
      << g_replay_liq_sideband_stats.publish_request_mask_nonzero << ",\n"
      << "    \"publish_request_blocked_by_no_fire\": "
      << g_replay_liq_sideband_stats.publish_request_blocked_by_no_fire << ",\n"
      << "    \"publish_request_invalid_fire_without_payload\": "
      << g_replay_liq_sideband_stats.publish_request_invalid_fire_without_payload << ",\n"
      << "    \"lret_sink_enqueue_ready\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_ready << ",\n"
      << "    \"lret_sink_enqueue_accepted\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted << ",\n"
      << "    \"lret_sink_enqueue_dropped\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_dropped << ",\n"
      << "    \"lret_sink_drain_valid\": "
      << g_replay_liq_sideband_stats.lret_sink_drain_valid << ",\n"
      << "    \"lret_sink_drain_fire\": "
      << g_replay_liq_sideband_stats.lret_sink_drain_fire << ",\n"
      << "    \"lret_sink_pending\": "
      << g_replay_liq_sideband_stats.lret_sink_pending << ",\n"
      << "    \"lret_sink_full\": "
      << g_replay_liq_sideband_stats.lret_sink_full << ",\n"
      << "    \"lret_sink_blocked_by_no_payload\": "
      << g_replay_liq_sideband_stats.lret_sink_blocked_by_no_payload << ",\n"
      << "    \"lret_sink_blocked_by_full\": "
      << g_replay_liq_sideband_stats.lret_sink_blocked_by_full << ",\n"
      << "    \"lret_sink_blocked_by_drain\": "
      << g_replay_liq_sideband_stats.lret_sink_blocked_by_drain << ",\n"
      << "    \"lret_sink_enqueue_ready_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_ready_w2_occupied << ",\n"
      << "    \"lret_sink_enqueue_accepted_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_occupied << ",\n"
      << "    \"lret_sink_enqueue_dropped_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_dropped_w2_occupied << ",\n"
      << "    \"lret_sink_enqueue_accepted_same_cycle_drain_fire\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_same_cycle_drain_fire << ",\n"
      << "    \"lret_sink_enqueue_accepted_same_cycle_drain_fire_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_same_cycle_drain_fire_w2_occupied << ",\n"
      << "    \"lret_sink_enqueue_accepted_w2_without_drain_fire\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_without_drain_fire << ",\n"
      << "    \"lret_sink_enqueue_accepted_w2_completion_clear_slot\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_completion_clear_slot << ",\n"
      << "    \"lret_sink_enqueue_accepted_w2_clear_intent\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_clear_intent << ",\n"
      << "    \"lret_sink_enqueue_accepted_w2_side_effect_fire_complete\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_side_effect_fire_complete << ",\n"
      << "    \"lret_sink_enqueue_accepted_w2_live_clear\": "
      << g_replay_liq_sideband_stats.lret_sink_enqueue_accepted_w2_live_clear << ",\n"
      << "    \"lret_sink_followup_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_sink_followup_w2_still_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_w2_still_occupied << ",\n"
      << "    \"lret_sink_followup_w2_cleared\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_w2_cleared << ",\n"
      << "    \"lret_sink_followup_after_enqueue_completion_clear_slot\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_completion_clear_slot << ",\n"
      << "    \"lret_sink_followup_after_enqueue_completion_clear_slot_w2_cleared\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_completion_clear_slot_w2_cleared << ",\n"
      << "    \"lret_sink_followup_after_enqueue_clear_intent\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_clear_intent << ",\n"
      << "    \"lret_sink_followup_after_enqueue_clear_intent_w2_cleared\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_clear_intent_w2_cleared << ",\n"
      << "    \"lret_sink_followup_after_enqueue_side_effect_fire_complete\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_side_effect_fire_complete << ",\n"
      << "    \"lret_sink_followup_after_enqueue_side_effect_fire_complete_w2_cleared\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_side_effect_fire_complete_w2_cleared << ",\n"
      << "    \"lret_sink_followup_after_enqueue_live_clear\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_live_clear << ",\n"
      << "    \"lret_sink_followup_after_enqueue_live_clear_w2_cleared\": "
      << g_replay_liq_sideband_stats.lret_sink_followup_after_enqueue_live_clear_w2_cleared << ",\n"
      << "    \"lret_sink_pending_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_sink_pending_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_sink_drain_valid_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_sink_drain_valid_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_sink_drain_fire_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_sink_drain_fire_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_drain_permit_ready_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_ready_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_drain_permit_pipe_free_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_pipe_free_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_drain_permit_pipe_full_after_enqueue_accepted_w2\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_pipe_full_after_enqueue_accepted_w2 << ",\n"
      << "    \"lret_sink_pending_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_pending_w2_occupied << ",\n"
      << "    \"lret_sink_drain_valid_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_drain_valid_w2_occupied << ",\n"
      << "    \"lret_sink_drain_fire_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_sink_drain_fire_w2_occupied << ",\n"
      << "    \"w2_retire_record_capture_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_capture_candidate << ",\n"
      << "    \"w2_retire_record_payload_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_payload_valid << ",\n"
      << "    \"w2_retire_record_capture_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_capture_valid << ",\n"
      << "    \"w2_retire_record_capture_ready\": "
      << g_replay_liq_sideband_stats.w2_retire_record_capture_ready << ",\n"
      << "    \"w2_retire_record_capture_accepted\": "
      << g_replay_liq_sideband_stats.w2_retire_record_capture_accepted << ",\n"
      << "    \"w2_retire_record_capture_accepted_w2_occupied\": "
      << g_replay_liq_sideband_stats.w2_retire_record_capture_accepted_w2_occupied << ",\n"
      << "    \"w2_retire_record_capture_dropped\": "
      << g_replay_liq_sideband_stats.w2_retire_record_capture_dropped << ",\n"
      << "    \"w2_retire_record_record_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_record_valid << ",\n"
      << "    \"w2_retire_record_record_ready\": "
      << g_replay_liq_sideband_stats.w2_retire_record_record_ready << ",\n"
      << "    \"w2_retire_record_record_fire\": "
      << g_replay_liq_sideband_stats.w2_retire_record_record_fire << ",\n"
      << "    \"w2_retire_record_pending\": "
      << g_replay_liq_sideband_stats.w2_retire_record_pending << ",\n"
      << "    \"w2_retire_record_captured_with_lret_enqueue\": "
      << g_replay_liq_sideband_stats.w2_retire_record_captured_with_lret_enqueue << ",\n"
      << "    \"w2_retire_record_record_from_lret_enqueue\": "
      << g_replay_liq_sideband_stats.w2_retire_record_record_from_lret_enqueue << ",\n"
      << "    \"w2_retire_record_blocked_by_invalid_payload\": "
      << g_replay_liq_sideband_stats.w2_retire_record_blocked_by_invalid_payload << ",\n"
      << "    \"w2_retire_record_blocked_by_full\": "
      << g_replay_liq_sideband_stats.w2_retire_record_blocked_by_full << ",\n"
      << "    \"lret_commit_history_load_rows\": "
      << g_replay_liq_sideband_stats.lret_commit_history_load_rows << ",\n"
      << "    \"lret_shadow_enqueue\": "
      << g_replay_liq_sideband_stats.lret_shadow_enqueue << ",\n"
      << "    \"lret_shadow_enqueue_after_prior_commit\": "
      << g_replay_liq_sideband_stats.lret_shadow_enqueue_after_prior_commit << ",\n"
      << "    \"lret_shadow_drain\": "
      << g_replay_liq_sideband_stats.lret_shadow_drain << ",\n"
      << "    \"lret_shadow_drain_missing\": "
      << g_replay_liq_sideband_stats.lret_shadow_drain_missing << ",\n"
      << "    \"lret_shadow_drain_after_prior_commit\": "
      << g_replay_liq_sideband_stats.lret_shadow_drain_after_prior_commit << ",\n"
      << "    \"lret_shadow_free_after_prior_commit\": "
      << g_replay_liq_sideband_stats.lret_shadow_free_after_prior_commit << ",\n"
      << "    \"lret_drain_permit_any_pipe_free\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_any_pipe_free << ",\n"
      << "    \"lret_drain_permit_ready\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_ready << ",\n"
      << "    \"lret_drain_permit_blocked_by_no_entry\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_blocked_by_no_entry << ",\n"
      << "    \"lret_drain_permit_blocked_by_pipe_full\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_blocked_by_pipe_full << ",\n"
      << "    \"lret_drain_permit_pipe_occupied\": "
      << g_replay_liq_sideband_stats.lret_drain_permit_pipe_occupied << ",\n"
      << "    \"lret_iex_data_rob_row_valid\": "
      << g_replay_liq_sideband_stats.lret_iex_data_rob_row_valid << ",\n"
      << "    \"lret_iex_data_rob_row_need_flush\": "
      << g_replay_liq_sideband_stats.lret_iex_data_rob_row_need_flush << ",\n"
      << "    \"lret_iex_data_rob_row_blocked_by_invalid_rid\": "
      << g_replay_liq_sideband_stats.lret_iex_data_rob_row_blocked_by_invalid_rid << ",\n"
      << "    \"lret_iex_data_rob_row_blocked_by_free\": "
      << g_replay_liq_sideband_stats.lret_iex_data_rob_row_blocked_by_free << ",\n"
      << "    \"lret_iex_data_rob_row_blocked_by_stale_rid\": "
      << g_replay_liq_sideband_stats.lret_iex_data_rob_row_blocked_by_stale_rid << ",\n"
      << "    \"lret_iex_data_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_iex_data_candidate_valid << ",\n"
      << "    \"lret_iex_data_would_drain\": "
      << g_replay_liq_sideband_stats.lret_iex_data_would_drain << ",\n"
      << "    \"lret_iex_data_set_mem_data_valid\": "
      << g_replay_liq_sideband_stats.lret_iex_data_set_mem_data_valid << ",\n"
      << "    \"lret_iex_data_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_disabled << ",\n"
      << "    \"lret_iex_data_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_flush << ",\n"
      << "    \"lret_iex_data_blocked_by_no_entry\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_no_entry << ",\n"
      << "    \"lret_iex_data_blocked_by_invalid_entry\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_invalid_entry << ",\n"
      << "    \"lret_iex_data_blocked_by_drain\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_drain << ",\n"
      << "    \"lret_iex_data_blocked_by_rob_missing\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_rob_missing << ",\n"
      << "    \"lret_iex_data_blocked_by_need_flush\": "
      << g_replay_liq_sideband_stats.lret_iex_data_blocked_by_need_flush << ",\n"
      << "    \"lret_rob_resolve_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_candidate_valid << ",\n"
      << "    \"lret_rob_resolve_valid\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_valid << ",\n"
      << "    \"lret_rob_resolve_ready_for_pipe_insert\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_ready_for_pipe_insert << ",\n"
      << "    \"lret_rob_resolve_mark_all_destinations_data_valid\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_mark_all_destinations_data_valid << ",\n"
      << "    \"lret_rob_resolve_mark_destination_data_valid\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_mark_destination_data_valid << ",\n"
      << "    \"lret_rob_resolve_ret_lane_increment\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_ret_lane_increment << ",\n"
      << "    \"lret_rob_resolve_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_disabled << ",\n"
      << "    \"lret_rob_resolve_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_flush << ",\n"
      << "    \"lret_rob_resolve_blocked_by_no_set_mem_data\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_no_set_mem_data << ",\n"
      << "    \"lret_rob_resolve_blocked_by_unsupported_multi_lane\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_unsupported_multi_lane << ",\n"
      << "    \"lret_rob_resolve_blocked_by_invalid_rid\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_invalid_rid << ",\n"
      << "    \"lret_rob_resolve_blocked_by_no_destination\": "
      << g_replay_liq_sideband_stats.lret_rob_resolve_blocked_by_no_destination << ",\n"
      << "    \"lret_lane_completion_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_candidate_valid << ",\n"
      << "    \"lret_lane_completion_complete_valid\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_complete_valid << ",\n"
      << "    \"lret_lane_completion_ready_for_pipe_insert\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_ready_for_pipe_insert << ",\n"
      << "    \"lret_lane_completion_requires_all_lanes\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_requires_all_lanes << ",\n"
      << "    \"lret_lane_completion_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_disabled << ",\n"
      << "    \"lret_lane_completion_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_flush << ",\n"
      << "    \"lret_lane_completion_blocked_by_no_resolve\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_no_resolve << ",\n"
      << "    \"lret_lane_completion_blocked_by_zero_returned_lanes\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_zero_returned_lanes << ",\n"
      << "    \"lret_lane_completion_blocked_by_invalid_real_req_cnt\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_invalid_real_req_cnt << ",\n"
      << "    \"lret_lane_completion_blocked_by_scalar_load_pair_incomplete\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_scalar_load_pair_incomplete << ",\n"
      << "    \"lret_lane_completion_blocked_by_vector_mem_incomplete\": "
      << g_replay_liq_sideband_stats.lret_lane_completion_blocked_by_vector_mem_incomplete << ",\n"
      << "    \"lret_tload_completion_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_candidate_valid << ",\n"
      << "    \"lret_tload_completion_tload_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_tload_candidate_valid << ",\n"
      << "    \"lret_tload_completion_tile_scb_send_valid\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_tile_scb_send_valid << ",\n"
      << "    \"lret_tload_completion_tile_scb_is_last\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_tile_scb_is_last << ",\n"
      << "    \"lret_tload_completion_complete_valid\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_complete_valid << ",\n"
      << "    \"lret_tload_completion_ready_for_pipe_insert\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_ready_for_pipe_insert << ",\n"
      << "    \"lret_tload_completion_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_disabled << ",\n"
      << "    \"lret_tload_completion_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_flush << ",\n"
      << "    \"lret_tload_completion_blocked_by_no_lane_completion\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_no_lane_completion << ",\n"
      << "    \"lret_tload_completion_blocked_by_invalid_sub_inst_cnt\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_invalid_sub_inst_cnt << ",\n"
      << "    \"lret_tload_completion_blocked_by_tload_pending\": "
      << g_replay_liq_sideband_stats.lret_tload_completion_blocked_by_tload_pending << ",\n"
      << "    \"lret_final_metadata_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_candidate_valid << ",\n"
      << "    \"lret_final_metadata_is_load_return_marked\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_is_load_return_marked << ",\n"
      << "    \"lret_final_metadata_load_branch_resolve_called\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_load_branch_resolve_called << ",\n"
      << "    \"lret_final_metadata_load_branch_resolve_side_effect_valid\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_load_branch_resolve_side_effect_valid << ",\n"
      << "    \"lret_final_metadata_pipe_cycle_sideband_valid\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_pipe_cycle_sideband_valid << ",\n"
      << "    \"lret_final_metadata_ready_for_pipe_insert\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_ready_for_pipe_insert << ",\n"
      << "    \"lret_final_metadata_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_blocked_by_disabled << ",\n"
      << "    \"lret_final_metadata_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_blocked_by_flush << ",\n"
      << "    \"lret_final_metadata_blocked_by_no_tload_completion\": "
      << g_replay_liq_sideband_stats.lret_final_metadata_blocked_by_no_tload_completion << ",\n"
      << "    \"lret_timing_stats_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_candidate_valid << ",\n"
      << "    \"lret_timing_stats_sideband_valid\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_sideband_valid << ",\n"
      << "    \"lret_timing_stats_iq_name_sideband_valid\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_iq_name_sideband_valid << ",\n"
      << "    \"lret_timing_stats_ld_rnt_cycle_valid\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_ld_rnt_cycle_valid << ",\n"
      << "    \"lret_timing_stats_update_valid\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_update_valid << ",\n"
      << "    \"lret_timing_stats_latency_underflow\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_latency_underflow << ",\n"
      << "    \"lret_timing_stats_ready_for_pipe_insert\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_ready_for_pipe_insert << ",\n"
      << "    \"lret_timing_stats_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_blocked_by_disabled << ",\n"
      << "    \"lret_timing_stats_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_blocked_by_flush << ",\n"
      << "    \"lret_timing_stats_blocked_by_no_final_metadata\": "
      << g_replay_liq_sideband_stats.lret_timing_stats_blocked_by_no_final_metadata << ",\n"
      << "    \"lret_iex_insert_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_candidate_valid << ",\n"
      << "    \"lret_iex_insert_valid\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_valid << ",\n"
      << "    \"lret_iex_insert_is_load_return\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_is_load_return << ",\n"
      << "    \"lret_iex_insert_wakeup_required\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_wakeup_required << ",\n"
      << "    \"lret_iex_insert_blocked_by_no_set_mem_data\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_blocked_by_no_set_mem_data << ",\n"
      << "    \"lret_iex_insert_blocked_by_no_pipe\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_blocked_by_no_pipe << ",\n"
      << "    \"lret_iex_insert_blocked_by_invalid_rid\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_blocked_by_invalid_rid << ",\n"
      << "    \"lret_iex_insert_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_candidate_w2_occupied << ",\n"
      << "    \"lret_iex_insert_valid_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_iex_insert_valid_w2_occupied << ",\n"
      << "    \"lret_residency_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_residency_candidate_valid << ",\n"
      << "    \"lret_residency_write_valid\": "
      << g_replay_liq_sideband_stats.lret_residency_write_valid << ",\n"
      << "    \"lret_residency_live_enable\": "
      << g_replay_liq_sideband_stats.lret_residency_live_enable << ",\n"
      << "    \"lret_residency_blocked_by_live_disabled\": "
      << g_replay_liq_sideband_stats.lret_residency_blocked_by_live_disabled << ",\n"
      << "    \"lret_residency_slot_accepted\": "
      << g_replay_liq_sideband_stats.lret_residency_slot_accepted << ",\n"
      << "    \"lret_residency_slot_occupied\": "
      << g_replay_liq_sideband_stats.lret_residency_slot_occupied << ",\n"
      << "    \"lret_residency_advance_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_residency_advance_candidate_valid << ",\n"
      << "    \"lret_residency_advance_valid\": "
      << g_replay_liq_sideband_stats.lret_residency_advance_valid << ",\n"
      << "    \"lret_residency_advance_blocked_by_advance_disabled\": "
      << g_replay_liq_sideband_stats.lret_residency_advance_blocked_by_advance_disabled << ",\n"
      << "    \"lret_residency_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_residency_candidate_w2_occupied << ",\n"
      << "    \"lret_residency_write_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_residency_write_w2_occupied << ",\n"
      << "    \"lret_residency_slot_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_residency_slot_w2_occupied << ",\n"
      << "    \"lret_residency_advance_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_residency_advance_candidate_w2_occupied << ",\n"
      << "    \"lret_residency_advance_valid_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_residency_advance_valid_w2_occupied << ",\n"
      << "    \"lret_w1_slot_accepted\": "
      << g_replay_liq_sideband_stats.lret_w1_slot_accepted << ",\n"
      << "    \"lret_w1_slot_occupied\": "
      << g_replay_liq_sideband_stats.lret_w1_slot_occupied << ",\n"
      << "    \"lret_w1_advance_candidate_valid\": "
      << g_replay_liq_sideband_stats.lret_w1_advance_candidate_valid << ",\n"
      << "    \"lret_w1_advance_valid\": "
      << g_replay_liq_sideband_stats.lret_w1_advance_valid << ",\n"
      << "    \"lret_w1_advance_blocked_by_advance_disabled\": "
      << g_replay_liq_sideband_stats.lret_w1_advance_blocked_by_advance_disabled << ",\n"
      << "    \"lret_w1_slot_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_w1_slot_w2_occupied << ",\n"
      << "    \"lret_w1_advance_candidate_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_w1_advance_candidate_w2_occupied << ",\n"
      << "    \"lret_w1_advance_valid_w2_occupied\": "
      << g_replay_liq_sideband_stats.lret_w1_advance_valid_w2_occupied << ",\n"
      << "    \"lret_w2_slot_accepted\": "
      << g_replay_liq_sideband_stats.lret_w2_slot_accepted << ",\n"
      << "    \"lret_w2_slot_occupied\": "
      << g_replay_liq_sideband_stats.lret_w2_slot_occupied << ",\n"
      << "    \"lret_w2_slot_blocked_by_no_write\": "
      << g_replay_liq_sideband_stats.lret_w2_slot_blocked_by_no_write << ",\n"
      << "    \"lret_w2_slot_source_trace_valid\": "
      << g_replay_liq_sideband_stats.lret_w2_slot_source_trace_valid << ",\n"
      << "    \"w2_atomic_live_active\": "
      << g_replay_liq_sideband_stats.w2_atomic_live_active << ",\n"
      << "    \"w2_atomic_request_active\": "
      << g_replay_liq_sideband_stats.w2_atomic_request_active << ",\n"
      << "    \"w2_atomic_evidence_valid\": "
      << g_replay_liq_sideband_stats.w2_atomic_evidence_valid << ",\n"
      << "    \"w2_atomic_side_effect_live_requested\": "
      << g_replay_liq_sideband_stats.w2_atomic_side_effect_live_requested << ",\n"
      << "    \"w2_atomic_promotion_requested\": "
      << g_replay_liq_sideband_stats.w2_atomic_promotion_requested << ",\n"
      << "    \"w2_atomic_blocked\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked << ",\n"
      << "    \"w2_atomic_blocked_by_request_disabled\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_request_disabled << ",\n"
      << "    \"w2_atomic_blocked_by_no_evidence\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_evidence << ",\n"
      << "    \"w2_atomic_blocked_by_mode_disabled\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_mode_disabled << ",\n"
      << "    \"w2_atomic_blocked_by_policy\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_policy << ",\n"
      << "    \"w2_atomic_blocked_by_no_side_effect_sink\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_side_effect_sink << ",\n"
      << "    \"w2_atomic_blocked_by_no_clear_commit\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_clear_commit << ",\n"
      << "    \"w2_atomic_blocked_by_no_row_fill_candidate\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_row_fill_candidate << ",\n"
      << "    \"w2_atomic_blocked_by_no_lifecycle_row\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_lifecycle_row << ",\n"
      << "    \"w2_atomic_blocked_by_no_required_side_effect\": "
      << g_replay_liq_sideband_stats.w2_atomic_blocked_by_no_required_side_effect << ",\n"
      << "    \"w2_side_effect_candidate_valid\": "
      << g_replay_liq_sideband_stats.w2_side_effect_candidate_valid << ",\n"
      << "    \"w2_side_effect_ready\": "
      << g_replay_liq_sideband_stats.w2_side_effect_ready << ",\n"
      << "    \"w2_side_effect_live_all_required_enabled\": "
      << g_replay_liq_sideband_stats.w2_side_effect_live_all_required_enabled << ",\n"
      << "    \"w2_side_effect_fire_valid\": "
      << g_replay_liq_sideband_stats.w2_side_effect_fire_valid << ",\n"
      << "    \"w2_side_effect_fire_complete\": "
      << g_replay_liq_sideband_stats.w2_side_effect_fire_complete << ",\n"
      << "    \"w2_clear_intent\": "
      << g_replay_liq_sideband_stats.w2_clear_intent << ",\n"
      << "    \"w2_clear_commit_ready\": "
      << g_replay_liq_sideband_stats.w2_clear_commit_ready << ",\n"
      << "    \"w2_promotion_live\": "
      << g_replay_liq_sideband_stats.w2_promotion_live << ",\n"
      << "    \"w2_promotion_live_clear\": "
      << g_replay_liq_sideband_stats.w2_promotion_live_clear << ",\n"
      << "    \"w2_promotion_advance_live\": "
      << g_replay_liq_sideband_stats.w2_promotion_advance_live << ",\n"
      << "    \"w2_promotion_blocked\": "
      << g_replay_liq_sideband_stats.w2_promotion_blocked << ",\n"
      << "    \"w2_promotion_blocked_by_promotion_disabled\": "
      << g_replay_liq_sideband_stats.w2_promotion_blocked_by_promotion_disabled << ",\n"
      << "    \"w2_promotion_blocked_by_clear_intent\": "
      << g_replay_liq_sideband_stats.w2_promotion_blocked_by_clear_intent << ",\n"
      << "    \"w2_promotion_invalid_clear_without_slot\": "
      << g_replay_liq_sideband_stats.w2_promotion_invalid_clear_without_slot << ",\n"
      << "    \"w2_refill_ready_empty\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_empty << ",\n"
      << "    \"w2_refill_ready_same_cycle_eligible\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_same_cycle_eligible << ",\n"
      << "    \"w2_refill_ready_same_cycle_ready\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_same_cycle_ready << ",\n"
      << "    \"w2_refill_ready_future_advance\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_future_advance << ",\n"
      << "    \"w2_refill_ready_matches_current\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_matches_current << ",\n"
      << "    \"w2_refill_ready_blocked\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_blocked << ",\n"
      << "    \"w2_refill_ready_invalid_live_clear_without_intent\": "
      << g_replay_liq_sideband_stats.w2_refill_ready_invalid_live_clear_without_intent << ",\n"
      << "    \"w2_slot_replace_empty_write_eligible\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_empty_write_eligible << ",\n"
      << "    \"w2_slot_replace_same_cycle_eligible\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_same_cycle_eligible << ",\n"
      << "    \"w2_slot_replace_same_cycle_ready\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_same_cycle_ready << ",\n"
      << "    \"w2_slot_replace_future_write_accept\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_future_write_accept << ",\n"
      << "    \"w2_slot_replace_matches_current\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_matches_current << ",\n"
      << "    \"w2_slot_replace_blocked\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_blocked << ",\n"
      << "    \"w2_slot_replace_blocked_by_current_storage\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_blocked_by_current_storage << ",\n"
      << "    \"w2_slot_replace_invalid_future_ready_without_live_clear\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_invalid_future_ready_without_live_clear << ",\n"
      << "    \"w2_slot_replace_overlap_candidate_occupied\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_overlap_candidate_occupied << ",\n"
      << "    \"w2_slot_replace_overlap_candidate_clear_intent\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_overlap_candidate_clear_intent << ",\n"
      << "    \"w2_slot_replace_overlap_candidate_live_clear\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_overlap_candidate_live_clear << ",\n"
      << "    \"w2_slot_replace_overlap_live_clear_same_lsid\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_overlap_live_clear_same_lsid << ",\n"
      << "    \"w2_slot_replace_overlap_live_clear_different_lsid\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_overlap_live_clear_different_lsid << ",\n"
      << "    \"w2_slot_replace_overlap_live_clear_unknown_lsid\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_overlap_live_clear_unknown_lsid << ",\n"
      << "    \"w2_slot_replace_live_clear_without_w1_candidate\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_without_w1_candidate << ",\n"
      << "    \"w2_slot_replace_w1_candidate_without_live_clear\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_without_live_clear << ",\n"
      << "    \"w2_slot_replace_advance_valid_on_live_clear\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_advance_valid_on_live_clear << ",\n"
      << "    \"w2_slot_replace_w1_candidate_cycle_before_clear_intent\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_cycle_before_clear_intent << ",\n"
      << "    \"w2_slot_replace_w1_candidate_cycle_before_live_clear\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_cycle_before_live_clear << ",\n"
      << "    \"w2_slot_replace_clear_intent_cycle_before_w1_candidate\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_clear_intent_cycle_before_w1_candidate << ",\n"
      << "    \"w2_slot_replace_live_clear_cycle_before_w1_candidate\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_cycle_before_w1_candidate << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_gap2\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap2 << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_gap3\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap3 << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_gap4\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap4 << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_gap5_plus\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_gap5_plus << ",\n"
      << "    \"w2_slot_replace_w1_candidate_after_live_clear_gap2\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap2 << ",\n"
      << "    \"w2_slot_replace_w1_candidate_after_live_clear_gap3\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap3 << ",\n"
      << "    \"w2_slot_replace_w1_candidate_after_live_clear_gap4\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap4 << ",\n"
      << "    \"w2_slot_replace_w1_candidate_after_live_clear_gap5_plus\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_w1_candidate_after_live_clear_gap5_plus << ",\n"
      << "    \"w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap2\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap2 << ",\n"
      << "    \"w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap3\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap3 << ",\n"
      << "    \"w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap4\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap4 << ",\n"
      << "    \"w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap5_plus\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_live_clear_after_w1_candidate_gap5_plus << ",\n"
      << "    \"w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap2\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap2 << ",\n"
      << "    \"w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap3\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap3 << ",\n"
      << "    \"w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap4\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap4 << ",\n"
      << "    \"w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap5_plus\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_different_lsid_w1_candidate_after_live_clear_gap5_plus << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_same_lsid\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_same_lsid << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_different_lsid\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_different_lsid << ",\n"
      << "    \"w2_slot_replace_live_clear_after_w1_candidate_unknown_lsid\": "
      << g_replay_liq_sideband_stats.w2_slot_replace_live_clear_after_w1_candidate_unknown_lsid << ",\n"
      << "    \"w2_advance_enable\": "
      << g_replay_liq_sideband_stats.w2_advance_enable << ",\n"
      << "    \"w2_advance_replace_on_clear\": "
      << g_replay_liq_sideband_stats.w2_advance_replace_on_clear << ",\n"
      << "    \"w2_advance_uses_future_advance\": "
      << g_replay_liq_sideband_stats.w2_advance_uses_future_advance << ",\n"
      << "    \"w2_advance_blocked\": "
      << g_replay_liq_sideband_stats.w2_advance_blocked << ",\n"
      << "    \"w2_advance_blocked_by_live_promotion_disabled\": "
      << g_replay_liq_sideband_stats.w2_advance_blocked_by_live_promotion_disabled << ",\n"
      << "    \"w2_advance_invalid_future_write_without_advance\": "
      << g_replay_liq_sideband_stats.w2_advance_invalid_future_write_without_advance << ",\n"
      << "    \"w2_commit_row_trace_source_ready\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_ready << ",\n"
      << "    \"w2_commit_row_trace_source_instruction_ready\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_instruction_ready << ",\n"
      << "    \"w2_commit_row_trace_source_source_ready\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_source_ready << ",\n"
      << "    \"w2_commit_row_trace_source_blocked\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_blocked << ",\n"
      << "    \"w2_commit_row_trace_source_blocked_by_no_metadata\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_blocked_by_no_metadata << ",\n"
      << "    \"w2_commit_row_trace_source_blocked_by_no_source_trace\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_blocked_by_no_source_trace << ",\n"
      << "    \"w2_commit_row_trace_source_rob_lookup_row_valid\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_row_valid << ",\n"
      << "    \"w2_commit_row_trace_source_rob_lookup_instruction_valid\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_instruction_valid << ",\n"
      << "    \"w2_commit_row_trace_source_rob_lookup_blocked_by_need_flush\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_need_flush << ",\n"
      << "    \"w2_commit_row_trace_source_rob_lookup_blocked_by_missing_instruction\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_missing_instruction << ",\n"
      << "    \"w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_disabled\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_disabled << ",\n"
      << "    \"w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_before_completion\": "
      << g_replay_liq_sideband_stats.w2_commit_row_trace_source_rob_lookup_blocked_by_source_trace_before_completion << ",\n"
      << "    \"w2_commit_row_candidate_valid\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_valid << ",\n"
      << "    \"w2_commit_row_fill_candidate\": "
      << g_replay_liq_sideband_stats.w2_commit_row_fill_candidate << ",\n"
      << "    \"w2_commit_row_complete_candidate\": "
      << g_replay_liq_sideband_stats.w2_commit_row_complete_candidate << ",\n"
      << "    \"w2_commit_row_candidate_blocked\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked << ",\n"
      << "    \"w2_commit_row_candidate_blocked_by_no_metadata\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_no_metadata << ",\n"
      << "    \"w2_commit_row_candidate_blocked_by_no_source_trace\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_no_source_trace << ",\n"
      << "    \"w2_commit_row_candidate_blocked_by_invalid_size\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_invalid_size << ",\n"
      << "    \"w2_commit_row_candidate_blocked_by_non_gpr_destination\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_non_gpr_destination << ",\n"
      << "    \"w2_commit_row_candidate_blocked_by_row_fill_disabled\": "
      << g_replay_liq_sideband_stats.w2_commit_row_candidate_blocked_by_row_fill_disabled << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_valid << ",\n"
      << "    \"w2_retire_record_commit_row_fill_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_fill_candidate << ",\n"
      << "    \"w2_retire_record_commit_row_complete_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_complete_candidate << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked_by_no_record\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_no_record << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked_by_no_metadata\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_no_metadata << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked_by_no_source_trace\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_no_source_trace << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked_by_invalid_size\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_invalid_size << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked_by_non_gpr_destination\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_non_gpr_destination << ",\n"
      << "    \"w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled\": "
      << g_replay_liq_sideband_stats.w2_retire_record_commit_row_candidate_blocked_by_row_fill_disabled << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_intent\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_intent << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_payload_rid_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_payload_rid_valid << ",\n"
      << "    \"w2_retire_record_instruction_metadata_w2_rid_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_w2_rid_valid << ",\n"
      << "    \"w2_retire_record_instruction_metadata_w2_rid_matches_capture\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_w2_rid_matches_capture << ",\n"
      << "    \"w2_retire_record_instruction_metadata_w2_metadata_ready\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_w2_metadata_ready << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_from_w2\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_from_w2 << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_from_drain\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_from_drain << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_blocked_by_no_payload_rid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_no_payload_rid << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_rid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_rid << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_rid_mismatch << ",\n"
      << "    \"w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_metadata\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_capture_blocked_by_no_w2_metadata << ",\n"
      << "    \"w2_retire_record_instruction_metadata_clear_accepted\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_clear_accepted << ",\n"
      << "    \"w2_retire_record_instruction_metadata_provider_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_instruction_metadata_provider_valid << ",\n"
      << "    \"w2_row_fill_candidate_valid\": "
      << g_replay_liq_sideband_stats.w2_row_fill_candidate_valid << ",\n"
      << "    \"w2_row_fill_prerequisites_ready\": "
      << g_replay_liq_sideband_stats.w2_row_fill_prerequisites_ready << ",\n"
      << "    \"w2_row_fill_enable\": "
      << g_replay_liq_sideband_stats.w2_row_fill_enable << ",\n"
      << "    \"w2_row_fill_blocked_by_request_disabled\": "
      << g_replay_liq_sideband_stats.w2_row_fill_blocked_by_request_disabled << ",\n"
      << "    \"w2_row_fill_blocked_by_no_candidate\": "
      << g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_candidate << ",\n"
      << "    \"w2_row_fill_blocked_by_no_side_effect_commit\": "
      << g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_side_effect_commit << ",\n"
      << "    \"w2_row_fill_blocked_by_no_clear_commit\": "
      << g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_clear_commit << ",\n"
      << "    \"w2_row_fill_blocked_by_live_clear_disabled\": "
      << g_replay_liq_sideband_stats.w2_row_fill_blocked_by_live_clear_disabled << ",\n"
      << "    \"w2_row_fill_blocked_by_no_replay_row_lifecycle\": "
      << g_replay_liq_sideband_stats.w2_row_fill_blocked_by_no_replay_row_lifecycle << ",\n"
      << "    \"w2_lifecycle_candidate_valid\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_candidate_valid << ",\n"
      << "    \"w2_lifecycle_slot_identity_valid\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_slot_identity_valid << ",\n"
      << "    \"w2_lifecycle_resolved_row_match\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_resolved_row_match << ",\n"
      << "    \"w2_lifecycle_row_clear_ready\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_row_clear_ready << ",\n"
      << "    \"w2_lifecycle_ready\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_ready << ",\n"
      << "    \"w2_lifecycle_blocked_by_no_resolved_row\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_blocked_by_no_resolved_row << ",\n"
      << "    \"w2_lifecycle_blocked_by_multiple_resolved_rows\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_blocked_by_multiple_resolved_rows << ",\n"
      << "    \"w2_lifecycle_blocked_by_clear_disabled\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_blocked_by_clear_disabled << ",\n"
      << "    \"w2_lifecycle_clear_request_enable\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_clear_request_enable << ",\n"
      << "    \"w2_lifecycle_clear_commit_enable\": "
      << g_replay_liq_sideband_stats.w2_lifecycle_clear_commit_enable << ",\n"
      << "    \"w2_retire_record_lifecycle_candidate_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_candidate_valid << ",\n"
      << "    \"w2_retire_record_lifecycle_slot_identity_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_slot_identity_valid << ",\n"
      << "    \"w2_retire_record_lifecycle_resolved_row_match\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_resolved_row_match << ",\n"
      << "    \"w2_retire_record_lifecycle_row_clear_ready\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_row_clear_ready << ",\n"
      << "    \"w2_retire_record_lifecycle_blocked_by_no_resolved_row\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_blocked_by_no_resolved_row << ",\n"
      << "    \"w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_blocked_by_multiple_resolved_rows << ",\n"
      << "    \"w2_retire_record_lifecycle_blocked_by_clear_disabled\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_blocked_by_clear_disabled << ",\n"
      << "    \"w2_retire_record_lifecycle_request_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_candidate << ",\n"
      << "    \"w2_retire_record_lifecycle_live_promotion_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_live_promotion_candidate << ",\n"
      << "    \"w2_retire_record_lifecycle_request_blocked_by_no_lifecycle_row\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_lifecycle_row << ",\n"
      << "    \"w2_retire_record_lifecycle_request_blocked_by_no_atomic_request\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_atomic_request << ",\n"
      << "    \"w2_retire_record_lifecycle_request_blocked_by_no_row_fill_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_row_fill_candidate << ",\n"
      << "    \"w2_retire_record_lifecycle_request_blocked_by_no_row_fill_enable\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_request_blocked_by_no_row_fill_enable << ",\n"
      << "    \"w2_retire_record_atomic_request_evidence_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_atomic_request_evidence_valid << ",\n"
      << "    \"w2_retire_record_atomic_request_row_fill_candidate_aligned\": "
      << g_replay_liq_sideband_stats.w2_retire_record_atomic_request_row_fill_candidate_aligned << ",\n"
      << "    \"w2_retire_record_atomic_request_row_fill_enable_aligned\": "
      << g_replay_liq_sideband_stats.w2_retire_record_atomic_request_row_fill_enable_aligned << ",\n"
      << "    \"w2_retire_record_atomic_request_blocked_by_no_lifecycle_row\": "
      << g_replay_liq_sideband_stats.w2_retire_record_atomic_request_blocked_by_no_lifecycle_row << ",\n"
      << "    \"w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_atomic_request_blocked_by_no_row_fill_candidate << ",\n"
      << "    \"w2_retire_record_atomic_request_blocked_by_no_row_fill_enable\": "
      << g_replay_liq_sideband_stats.w2_retire_record_atomic_request_blocked_by_no_row_fill_enable << ",\n"
      << "    \"w2_retire_record_row_fill_enable_request_evidence_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_request_evidence_valid << ",\n"
      << "    \"w2_retire_record_row_fill_enable_candidate_aligned\": "
      << g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_candidate_aligned << ",\n"
      << "    \"w2_retire_record_row_fill_enable\": "
      << g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable << ",\n"
      << "    \"w2_retire_record_row_fill_enable_blocked_by_request_disabled\": "
      << g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_blocked_by_request_disabled << ",\n"
      << "    \"w2_retire_record_row_fill_enable_blocked_by_no_lifecycle_row\": "
      << g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_blocked_by_no_lifecycle_row << ",\n"
      << "    \"w2_retire_record_row_fill_enable_blocked_by_no_row_fill_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_row_fill_enable_blocked_by_no_row_fill_candidate << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_capture_intent\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_capture_intent << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_capture_from_lifecycle\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_capture_from_lifecycle << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_capture_blocked_by_no_lifecycle << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_clear_accepted\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_clear_accepted << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_provider_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_provider_valid << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_provider_valid_without_record\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_provider_valid_without_record << ",\n"
      << "    \"w2_retire_record_lifecycle_evidence_record_valid_without_provider\": "
      << g_replay_liq_sideband_stats.w2_retire_record_lifecycle_evidence_record_valid_without_provider << ",\n"
      << "    \"w2_retire_record_rob_fallback_capture_physical_complete\": "
      << g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_capture_physical_complete << ",\n"
      << "    \"w2_retire_record_rob_fallback_candidate\": "
      << g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_candidate << ",\n"
      << "    \"w2_retire_record_rob_fallback_duplicate_physical_complete\": "
      << g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_duplicate_physical_complete << ",\n"
      << "    \"w2_retire_record_rob_fallback_complete_valid\": "
      << g_replay_liq_sideband_stats.w2_retire_record_rob_fallback_complete_valid << ",\n"
      << "    \"resolve_queue_push_accepted\": "
      << g_replay_liq_sideband_stats.resolve_queue_push_accepted << ",\n"
      << "    \"resolve_queue_valid\": "
      << g_replay_liq_sideband_stats.resolve_queue_valid << ",\n"
      << "    \"resolve_queue_push_accepted_first_cycle\": "
      << g_replay_liq_sideband_stats.resolve_queue_push_accepted_first_cycle << ",\n"
      << "    \"resolve_queue_push_accepted_last_cycle\": "
      << g_replay_liq_sideband_stats.resolve_queue_push_accepted_last_cycle << ",\n"
      << "    \"resolve_queue_valid_first_cycle\": "
      << g_replay_liq_sideband_stats.resolve_queue_valid_first_cycle << ",\n"
      << "    \"resolve_queue_valid_last_cycle\": "
      << g_replay_liq_sideband_stats.resolve_queue_valid_last_cycle << ",\n"
      << "    \"mdb_conflict_store_valid\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_valid << ",\n"
      << "    \"mdb_conflict_store_valid_first_cycle\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_valid_first_cycle << ",\n"
      << "    \"mdb_conflict_store_valid_last_cycle\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_valid_last_cycle << ",\n"
      << "    \"mdb_conflict_store_with_resolve_queue_valid\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_with_resolve_queue_valid << ",\n"
      << "    \"mdb_conflict_store_with_resolve_queue_valid_first_cycle\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_with_resolve_queue_valid_first_cycle << ",\n"
      << "    \"mdb_conflict_store_with_resolve_queue_valid_last_cycle\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_with_resolve_queue_valid_last_cycle << ",\n"
      << "    \"mdb_conflict_store_without_resolve_queue_valid\": "
      << g_replay_liq_sideband_stats.mdb_conflict_store_without_resolve_queue_valid << ",\n"
      << "    \"mdb_conflict_active_candidate\": "
      << g_replay_liq_sideband_stats.mdb_conflict_active_candidate << ",\n"
      << "    \"mdb_conflict_resolve_candidate\": "
      << g_replay_liq_sideband_stats.mdb_conflict_resolve_candidate << ",\n"
      << "    \"mdb_conflict_resolve_candidate_first_cycle\": "
      << g_replay_liq_sideband_stats.mdb_conflict_resolve_candidate_first_cycle << ",\n"
      << "    \"mdb_conflict_resolve_candidate_last_cycle\": "
      << g_replay_liq_sideband_stats.mdb_conflict_resolve_candidate_last_cycle << ",\n"
      << "    \"mdb_conflict_valid\": "
      << g_replay_liq_sideband_stats.mdb_conflict_valid << ",\n"
      << "    \"mdb_fanout_record_valid\": "
      << g_replay_liq_sideband_stats.mdb_fanout_record_valid << ",\n"
      << "    \"mdb_fanout_record_accepted\": "
      << g_replay_liq_sideband_stats.mdb_fanout_record_accepted << ",\n"
      << "    \"mdb_fanout_record_processed\": "
      << g_replay_liq_sideband_stats.mdb_fanout_record_processed << ",\n"
      << "    \"mdb_fanout_bmdb_report\": "
      << g_replay_liq_sideband_stats.mdb_fanout_bmdb_report << ",\n"
      << "    \"mdb_fanout_ssit_nonempty\": "
      << g_replay_liq_sideband_stats.mdb_fanout_ssit_nonempty << ",\n"
      << "    \"mdb_fanout_lookup_valid\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_valid << ",\n"
      << "    \"mdb_fanout_lookup_accepted\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_accepted << ",\n"
      << "    \"mdb_fanout_lookup_processed\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_processed << ",\n"
      << "    \"mdb_fanout_lookup_table_hit\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_table_hit << ",\n"
      << "    \"mdb_fanout_lookup_first_after_nuke\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_first_after_nuke << ",\n"
      << "    \"mdb_fanout_lookup_conf_blocked\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_conf_blocked << ",\n"
      << "    \"mdb_fanout_lookup_weight_blocked\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lookup_weight_blocked << ",\n"
      << "    \"mdb_fanout_lu_out_valid\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lu_out_valid << ",\n"
      << "    \"mdb_fanout_lu_out_hit\": "
      << g_replay_liq_sideband_stats.mdb_fanout_lu_out_hit << ",\n"
      << "    \"mdb_fanout_su_out_valid\": "
      << g_replay_liq_sideband_stats.mdb_fanout_su_out_valid << ",\n"
      << "    \"mdb_fanout_su_out_hit\": "
      << g_replay_liq_sideband_stats.mdb_fanout_su_out_hit << ",\n"
      << "    \"mdb_fanout_su_wakeup_valid\": "
      << g_replay_liq_sideband_stats.mdb_fanout_su_wakeup_valid << ",\n"
      << "    \"mdb_lookup_wait_plan_lookup_hit\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_lookup_hit << ",\n"
      << "    \"mdb_lookup_wait_plan_wait_intent_valid\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_wait_intent_valid << ",\n"
      << "    \"mdb_lookup_wait_plan_request_valid\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_request_valid << ",\n"
      << "    \"mdb_lookup_wait_plan_blocked_by_no_target\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_blocked_by_no_target << ",\n"
      << "    \"mdb_lookup_wait_plan_blocked_by_missing_store_index\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_blocked_by_missing_store_index << ",\n"
      << "    \"mdb_lookup_wait_plan_blocked_by_missing_store_lsid\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_blocked_by_missing_store_lsid << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_active\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_active << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_valid\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_valid << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_source_store_index_fits\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_source_store_index_fits << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_blocked_by_disabled\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_blocked_by_disabled << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_blocked_by_flush\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_blocked_by_flush << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_blocked_by_no_request\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_blocked_by_no_request << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_invalid_store_index_out_of_range\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_store_index_out_of_range << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_invalid_conflicting_status_write\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_conflicting_status_write << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_invalid_wait_store_without_wait_status\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_wait_store_without_wait_status << ",\n"
      << "    \"mdb_lookup_wait_plan_bridge_invalid_return_without_split_sources\": "
      << g_replay_liq_sideband_stats.mdb_lookup_wait_plan_bridge_invalid_return_without_split_sources << ",\n"
      << "    \"liq_row_mutation_bridge_valid\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_bridge_valid << ",\n"
      << "    \"liq_row_mutation_selected_source_return\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_selected_source_return << ",\n"
      << "    \"liq_row_mutation_selected_mdb_wait_plan\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_selected_mdb_wait_plan << ",\n"
      << "    \"liq_row_mutation_source_conflict\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_source_conflict << ",\n"
      << "    \"mdb_wait_plan_row_mutation_write_enable\": "
      << g_replay_liq_sideband_stats.mdb_wait_plan_row_mutation_write_enable << ",\n"
      << "    \"liq_row_mutation_write_enable\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_write_enable << ",\n"
      << "    \"liq_row_mutation_apply_valid\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_apply_valid << ",\n"
      << "    \"liq_row_mutation_blocked_by_bridge\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_blocked_by_bridge << ",\n"
      << "    \"liq_row_mutation_blocked_by_control\": "
      << g_replay_liq_sideband_stats.liq_row_mutation_blocked_by_control << "\n"
      << "  }\n"
      << "}\n";
  return true;
}

bool trace_top_debug_enabled() {
  static const bool enabled = std::getenv("LINXCORE_TRACE_TOP_DEBUG") != nullptr;
  return enabled;
}

void trace_top_debug_pipeline(
    const VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const char *context);

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0
            << " --dut-trace <dut.jsonl> --qemu-trace <qemu.jsonl>"
            << " [--expected-rows <rows.jsonl>]"
            << " [--memory-bin <program.bin> --memory-base <addr>]"
            << " [--memory-hex <sparse.mem>]"
            << " [--sideband-stats <stats.json>]"
            << " [--admit-marker-rows]"
            << " [--disable-store-memory-mutation]"
            << " [--allow-residual-replay-liq-wait]\n";
  std::exit(2);
}

std::uint64_t parse_u64_arg(const std::string &value, const std::string &name) {
  errno = 0;
  char *end = nullptr;
  const unsigned long long parsed = std::strtoull(value.c_str(), &end, 0);
  if (errno != 0 || end == value.c_str() || *end != '\0') {
    std::cerr << "invalid " << name << ": " << value << "\n";
    std::exit(2);
  }
  return static_cast<std::uint64_t>(parsed);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--dut-trace" && i + 1 < argc) {
      args.dut_trace = argv[++i];
    } else if (arg == "--qemu-trace" && i + 1 < argc) {
      args.qemu_trace = argv[++i];
    } else if (arg == "--memory-bin" && i + 1 < argc) {
      args.memory_bin = argv[++i];
    } else if (arg == "--memory-hex" && i + 1 < argc) {
      args.memory_hex = argv[++i];
    } else if (arg == "--expected-rows" && i + 1 < argc) {
      args.expected_rows = argv[++i];
    } else if (arg == "--sideband-stats" && i + 1 < argc) {
      args.sideband_stats = argv[++i];
    } else if (arg == "--memory-base" && i + 1 < argc) {
      args.memory_base = parse_u64_arg(argv[++i], "--memory-base");
    } else if (arg == "--admit-marker-rows") {
      args.admit_marker_rows = true;
    } else if (arg == "--disable-store-memory-mutation") {
      args.disable_store_memory_mutation = true;
    } else if (arg == "--allow-residual-replay-liq-wait") {
      args.allow_residual_replay_liq_wait = true;
    } else {
      usage(argv[0]);
    }
  }
  if (args.dut_trace.empty() || args.qemu_trace.empty()) {
    usage(argv[0]);
  }
  if (!args.memory_bin.empty() && !args.memory_hex.empty()) {
    std::cerr << "--memory-bin and --memory-hex are mutually exclusive\n";
    usage(argv[0]);
  }
  return args;
}

std::string trim_copy(const std::string &value) {
  const auto first = value.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) {
    return "";
  }
  const auto last = value.find_last_not_of(" \t\r\n");
  return value.substr(first, last - first + 1);
}

bool json_value_token(const std::string &line, const std::string &key, std::string &token) {
  const std::string quoted_key = "\"" + key + "\"";
  const std::size_t key_pos = line.find(quoted_key);
  if (key_pos == std::string::npos) {
    return false;
  }
  std::size_t pos = key_pos + quoted_key.size();
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    ++pos;
  }
  if (pos >= line.size() || line[pos] != ':') {
    return false;
  }
  ++pos;
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    ++pos;
  }
  if (pos >= line.size()) {
    return false;
  }

  if (line[pos] == '"') {
    ++pos;
    std::string value;
    bool escaped = false;
    for (; pos < line.size(); ++pos) {
      const char ch = line[pos];
      if (escaped) {
        value.push_back(ch);
        escaped = false;
        continue;
      }
      if (ch == '\\') {
        escaped = true;
        continue;
      }
      if (ch == '"') {
        token = trim_copy(value);
        return true;
      }
      value.push_back(ch);
    }
    return false;
  }

  const std::size_t end = line.find_first_of(",}", pos);
  if (end == std::string::npos) {
    return false;
  }
  token = trim_copy(line.substr(pos, end - pos));
  return !token.empty();
}

bool json_has_key(const std::string &line, const std::string &key) {
  std::string ignored;
  return json_value_token(line, key, ignored);
}

std::uint64_t json_u64(
    const std::string &line,
    const std::string &key,
    std::uint64_t default_value,
    const std::string &context) {
  std::string token;
  if (!json_value_token(line, key, token)) {
    return default_value;
  }
  return parse_u64_arg(token, context + "." + key);
}

std::uint8_t json_u8(
    const std::string &line,
    const std::string &key,
    std::uint8_t default_value,
    const std::string &context) {
  const std::uint64_t value = json_u64(line, key, default_value, context);
  if (value > 0xffU) {
    std::cerr << "expected row field out of uint8 range: "
              << context << "." << key << "=" << value << "\n";
    std::exit(2);
  }
  return static_cast<std::uint8_t>(value);
}

bool json_bool(
    const std::string &line,
    const std::string &key,
    bool default_value,
    const std::string &context) {
  std::string token;
  if (!json_value_token(line, key, token)) {
    return default_value;
  }
  for (char &ch : token) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  if (token == "true" || token == "yes" || token == "1") {
    return true;
  }
  if (token == "false" || token == "no" || token == "0") {
    return false;
  }
  const std::uint64_t numeric = parse_u64_arg(token, context + "." + key);
  return numeric != 0;
}

ExpectedRow parse_expected_row_jsonl(
    const std::string &line,
    const std::string &path,
    std::uint64_t line_no) {
  const std::string context = path + ":" + std::to_string(line_no);
  if (!json_has_key(line, "pc") || !json_has_key(line, "insn")) {
    std::cerr << "expected row is missing pc or insn at " << context << "\n";
    std::exit(2);
  }

  ExpectedRow row;
  row.pc = json_u64(line, "pc", 0, context);
  row.insn = json_u64(line, "insn", 0, context);
  row.len = json_u8(line, "len", 4, context);
  if (row.len != 2 && row.len != 4 && row.len != 6 && row.len != 8) {
    std::cerr << "expected row has unsupported instruction length at "
              << context << " len=" << static_cast<unsigned>(row.len) << "\n";
    std::exit(2);
  }
  row.skip = json_bool(line, "skip", false, context);
  (void)json_value_token(line, "skip_kind", row.skip_kind);
  row.block_boundary = json_bool(line, "block_boundary", false, context);
  row.block_stop = json_bool(line, "block_stop", false, context);
  row.loop_reentry = json_bool(line, "loop_reentry", false, context);
  row.loop_reentry_from_pc = json_u64(line, "loop_reentry_from_pc", 0, context);

  row.src0_valid = json_bool(line, "src0_valid", false, context);
  row.src0_reg = json_u8(line, "src0_reg", 0, context);
  row.src0_data = json_u64(line, "src0_data", 0, context);
  row.src1_valid = json_bool(line, "src1_valid", false, context);
  row.src1_reg = json_u8(line, "src1_reg", 0, context);
  row.src1_data = json_u64(line, "src1_data", 0, context);

  const bool has_dst_valid = json_has_key(line, "dst_valid");
  const bool has_dst_reg = json_has_key(line, "dst_reg");
  const bool has_dst_data = json_has_key(line, "dst_data");
  row.dst_valid = json_bool(line, "dst_valid", json_bool(line, "wb_valid", false, context), context);
  row.dst_reg = json_u8(line, "dst_reg", json_u8(line, "wb_rd", 0, context), context);
  row.dst_data = json_u64(line, "dst_data", json_u64(line, "wb_data", 0, context), context);
  if (!has_dst_valid && json_has_key(line, "wb_valid")) {
    row.dst_valid = json_bool(line, "wb_valid", false, context);
  }
  if (!has_dst_reg && json_has_key(line, "wb_rd")) {
    row.dst_reg = json_u8(line, "wb_rd", 0, context);
  }
  if (!has_dst_data && json_has_key(line, "wb_data")) {
    row.dst_data = json_u64(line, "wb_data", 0, context);
  }
  row.mem_valid = json_bool(line, "mem_valid", false, context);
  row.mem_is_store = json_bool(line, "mem_is_store", false, context);
  row.mem_addr = json_u64(line, "mem_addr", 0, context);
  row.mem_wdata = json_u64(line, "mem_wdata", 0, context);
  row.mem_rdata = json_u64(line, "mem_rdata", 0, context);
  row.mem_size = json_u8(line, "mem_size", 0, context);
  row.next_pc = json_u64(line, "next_pc", row.pc + row.len, context);
  return row;
}

std::vector<ExpectedRow> load_expected_rows_jsonl(const std::string &path) {
  std::ifstream in(path);
  if (!in) {
    std::cerr << "failed to open expected rows: " << path
              << " error=" << std::strerror(errno) << "\n";
    std::exit(2);
  }

  std::vector<ExpectedRow> rows;
  std::string line;
  std::uint64_t line_no = 0;
  while (std::getline(in, line)) {
    ++line_no;
    line = trim_copy(line);
    if (line.empty() || line[0] == '#') {
      continue;
    }
    if (line.find("\"type\"") != std::string::npos &&
        line.find("\"META\"") != std::string::npos) {
      continue;
    }
    if (json_has_key(line, "valid") &&
        !json_bool(line, "valid", true, path + ":" + std::to_string(line_no))) {
      continue;
    }
    rows.push_back(parse_expected_row_jsonl(line, path, line_no));
  }
  if (rows.empty()) {
    std::cerr << "expected row stream is empty: " << path << "\n";
    std::exit(2);
  }
  return rows;
}

void clear_inputs(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  dut.io_startValid = 0;
  dut.io_startPc = 0;
  dut.io_restartValid = 0;
  dut.io_restartPc = 0;
  dut.io_reducedBfuBodyValid = 0;
  dut.io_reducedBfuHeaderPc = 0;
  dut.io_reducedBfuHSizeBytes = 0;
  dut.io_reducedBfuBSizeBytes = 0;
  dut.io_frontendFlushValid = 0;
  dut.io_peId = 0;
  dut.io_threadId = 0;
  dut.io_fetchReqReady = 0;
  dut.io_fetchRespValid = 0;
  dut.io_fetchRespWindow = 0;
  dut.io_rfInitValid = 0;
  dut.io_rfInitArchTag = 0;
  dut.io_rfInitData = 0;
  dut.io_deallocReady = 1;
  dut.io_loadLookupData = 0;
}

void observe_gpr_commit_history(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  if (dut.io_robDeallocBlockLastValid) {
    ++g_gpr_commit_history.dealloc_block_last_count;
    g_gpr_commit_history.last_dealloc_block_last_bid = dut.io_robDeallocBlockLastBlockBid;
  }
  if (dut.io_blockScalarDoneFire) {
    ++g_gpr_commit_history.block_scalar_done_count;
    g_gpr_commit_history.last_scalar_done_bid = dut.io_blockScalarDoneBid;
  }
  if (dut.io_blockRetireFire) {
    ++g_gpr_commit_history.block_retire_count;
    g_gpr_commit_history.last_block_retire_bid = dut.io_blockRetireBid;
  }
  if (dut.io_gprCommitAccepted) {
    ++g_gpr_commit_history.gpr_commit_count;
    g_gpr_commit_history.last_gpr_commit_bid = dut.io_gprCommitBlockBid;
    g_gpr_commit_history.last_gpr_commit_hits = dut.io_gprCommittedMapQCount;
    g_gpr_commit_history.last_gpr_releases = dut.io_gprReleasedPhysCount;
    g_gpr_commit_history.gpr_commit_hit_total += dut.io_gprCommittedMapQCount;
    g_gpr_commit_history.gpr_release_total += dut.io_gprReleasedPhysCount;
    if (dut.io_gprCommittedMapQCount || dut.io_gprReleasedPhysCount) {
      g_gpr_commit_history.last_nonzero_gpr_commit_bid = dut.io_gprCommitBlockBid;
      g_gpr_commit_history.last_nonzero_gpr_commit_hits = dut.io_gprCommittedMapQCount;
      g_gpr_commit_history.last_nonzero_gpr_releases = dut.io_gprReleasedPhysCount;
    }
  }
}

void dump_gpr_commit_history_line(const char *label) {
  std::cerr << "frontend fetch RF ALU " << label
            << " histDeallocBlockLast=" << g_gpr_commit_history.dealloc_block_last_count
            << " histScalarDone=" << g_gpr_commit_history.block_scalar_done_count
            << " histBlockRetire=" << g_gpr_commit_history.block_retire_count
            << " histGprCommit=" << g_gpr_commit_history.gpr_commit_count
            << " histGprCommitHits=" << g_gpr_commit_history.gpr_commit_hit_total
            << " histGprReleases=" << g_gpr_commit_history.gpr_release_total
            << " lastDeallocBlockLastBid=0x" << std::hex
            << g_gpr_commit_history.last_dealloc_block_last_bid
            << " lastScalarDoneBid=0x" << g_gpr_commit_history.last_scalar_done_bid
            << " lastBlockRetireBid=0x" << g_gpr_commit_history.last_block_retire_bid
            << " lastGprCommitBid=0x" << g_gpr_commit_history.last_gpr_commit_bid
            << " lastNonzeroGprCommitBid=0x" << g_gpr_commit_history.last_nonzero_gpr_commit_bid
            << std::dec
            << " lastGprCommitHits=" << static_cast<unsigned>(g_gpr_commit_history.last_gpr_commit_hits)
            << " lastGprReleases=" << static_cast<unsigned>(g_gpr_commit_history.last_gpr_releases)
            << " lastNonzeroGprCommitHits="
            << static_cast<unsigned>(g_gpr_commit_history.last_nonzero_gpr_commit_hits)
            << " lastNonzeroGprReleases="
            << static_cast<unsigned>(g_gpr_commit_history.last_nonzero_gpr_releases)
            << "\n";
}

void tick(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  dut.clock = 0;
  dut.eval();
  observe_gpr_commit_history(dut);
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
  ++g_tb_cycle;
  observe_replay_liq_sideband(dut);
}

void reset(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
}

void init_rf(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::uint8_t arch_tag, std::uint64_t data) {
  clear_inputs(dut);
  dut.io_rfInitValid = 1;
  dut.io_rfInitArchTag = arch_tag;
  dut.io_rfInitData = data;
  tick(dut);
  clear_inputs(dut);
  dut.eval();
}

std::map<std::uint8_t, std::uint64_t> initial_rf_preloads(const std::vector<ExpectedRow> &rows) {
  std::map<std::uint8_t, std::uint64_t> preloads;
  std::map<std::uint8_t, bool> produced;

  const auto observe_source = [&](bool valid, std::uint8_t reg, std::uint64_t data) {
    if (!valid || produced[reg]) {
      return;
    }
    const auto [it, inserted] = preloads.emplace(reg, data);
    if (!inserted && it->second != data) {
      std::cerr << "expected rows require conflicting initial RF data"
                << " reg=" << static_cast<unsigned>(reg)
                << " first=" << it->second
                << " later=" << data << "\n";
      std::exit(2);
    }
  };

  for (const ExpectedRow &row : rows) {
    if (row.skip) {
      continue;
    }
    observe_source(row.src0_valid, row.src0_reg, row.src0_data);
    observe_source(row.src1_valid, row.src1_reg, row.src1_data);
    if (row.dst_valid && row.dst_reg < 24) {
      produced[row.dst_reg] = true;
    }
  }
  return preloads;
}

std::uint64_t mask_insn(std::uint64_t insn, std::uint8_t len) {
  if (len == 2) {
    return insn & 0xffffULL;
  }
  if (len == 4) {
    return insn & 0xffff'ffffULL;
  }
  if (len == 6) {
    return insn & 0xffff'ffff'ffffULL;
  }
  return insn;
}

class FetchMemoryImage {
public:
  void store_byte(std::uint64_t addr, std::uint8_t value) {
    bytes_[addr] = value;
  }

  void load_binary(const std::string &path, std::uint64_t base) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
      std::cerr << "failed to open fetch memory image: " << path
                << " error=" << std::strerror(errno) << "\n";
      std::exit(2);
    }

    char ch = 0;
    std::uint64_t offset = 0;
    while (in.get(ch)) {
      store_byte(base + offset, static_cast<std::uint8_t>(static_cast<unsigned char>(ch)));
      ++offset;
    }
    if (offset == 0) {
      std::cerr << "fetch memory image is empty: " << path << "\n";
      std::exit(2);
    }
  }

  void load_sparse_hex(const std::string &path) {
    std::ifstream in(path);
    if (!in) {
      std::cerr << "failed to open sparse fetch memory image: " << path
                << " error=" << std::strerror(errno) << "\n";
      std::exit(2);
    }

    std::string line;
    std::uint64_t loaded = 0;
    std::uint64_t line_no = 0;
    while (std::getline(in, line)) {
      ++line_no;
      const auto comment = line.find('#');
      if (comment != std::string::npos) {
        line.resize(comment);
      }

      std::istringstream iss(line);
      std::string addr_token;
      std::string byte_token;
      std::string extra;
      if (!(iss >> addr_token)) {
        continue;
      }
      if (!(iss >> byte_token) || (iss >> extra)) {
        std::cerr << "invalid sparse fetch memory line"
                  << " path=" << path
                  << " line=" << line_no << "\n";
        std::exit(2);
      }

      const std::uint64_t addr =
          parse_u64_arg(addr_token, "sparse memory address at " + path + ":" + std::to_string(line_no));
      const std::uint64_t byte =
          parse_u64_arg(byte_token, "sparse memory byte at " + path + ":" + std::to_string(line_no));
      if (byte > 0xffU) {
        std::cerr << "sparse fetch memory byte out of range"
                  << " path=" << path
                  << " line=" << line_no
                  << " value=0x" << std::hex << byte << std::dec << "\n";
        std::exit(2);
      }
      store_byte(addr, static_cast<std::uint8_t>(byte));
      ++loaded;
    }

    if (loaded == 0) {
      std::cerr << "sparse fetch memory image is empty: " << path << "\n";
      std::exit(2);
    }
  }

  std::uint64_t read_window(std::uint64_t pc) const {
    std::uint64_t window = 0;
    for (std::uint8_t byte_index = 0; byte_index < 8; ++byte_index) {
      std::uint8_t byte = 0xffU;
      if (!read_byte(pc + byte_index, byte) && byte_index == 0) {
        std::cerr << "fetch memory image missing first byte"
                  << " pc=0x" << std::hex << pc << std::dec << "\n";
        std::exit(1);
      }
      window |= static_cast<std::uint64_t>(byte) << (static_cast<unsigned>(byte_index) * 8U);
    }
    return window;
  }

  std::uint64_t read_u64_or_zero(std::uint64_t addr) const {
    std::uint64_t value = 0;
    for (std::uint8_t byte_index = 0; byte_index < 8; ++byte_index) {
      std::uint8_t byte = 0;
      (void)read_byte(addr + byte_index, byte);
      value |= static_cast<std::uint64_t>(byte) << (static_cast<unsigned>(byte_index) * 8U);
    }
    return value;
  }

  void store_u64(std::uint64_t addr, std::uint64_t value) {
    for (std::uint8_t byte_index = 0; byte_index < 8; ++byte_index) {
      store_byte(
          addr + byte_index,
          static_cast<std::uint8_t>((value >> (static_cast<unsigned>(byte_index) * 8U)) & 0xffU));
    }
  }

  static FetchMemoryImage from_rows(const std::vector<ExpectedRow> &rows) {
    FetchMemoryImage image;
    for (const ExpectedRow &row : rows) {
      const std::uint64_t insn = mask_insn(row.insn, row.len);
      for (std::uint8_t byte_index = 0; byte_index < row.len; ++byte_index) {
        image.store_byte(
            row.pc + byte_index,
            static_cast<std::uint8_t>((insn >> (static_cast<unsigned>(byte_index) * 8U)) & 0xffU));
      }
    }
    return image;
  }

private:
  bool read_byte(std::uint64_t addr, std::uint8_t &value) const {
    const auto it = bytes_.find(addr);
    if (it == bytes_.end()) {
      return false;
    }
    value = it->second;
    return true;
  }

  std::map<std::uint64_t, std::uint8_t> bytes_;
};

void eval_with_load_lookup(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const FetchMemoryImage &fetch_memory,
    const char *context = "eval") {
  dut.eval();
  if (dut.io_loadLookupValid) {
    dut.io_loadLookupData = fetch_memory.read_u64_or_zero(dut.io_loadLookupAddr);
    dut.eval();
  }
  if (dut.io_executeCompleteValid) {
    IssueDebug debug;
    debug.src_valid_mask = dut.io_executeCompleteSrcPhysValidMask;
    debug.src_phys_tag0 = dut.io_executeCompleteSrcPhysTag_0;
    debug.src_phys_tag1 = dut.io_executeCompleteSrcPhysTag_1;
    debug.src_phys_tag2 = dut.io_executeCompleteSrcPhysTag_2;
    g_issue_debug_by_rob[dut.io_executeCompleteRobValue] = debug;
  }
  if (dut.io_rfWriteValid) {
    PhysWriter writer;
    writer.valid = true;
    writer.pc = dut.io_executeCompletePc;
    writer.insn = dut.io_executeCompleteInsn;
    writer.phys_tag = dut.io_rfWriteTag;
    writer.wb_reg = dut.io_executeCompleteWbReg;
    writer.data = dut.io_rfWriteData;
    g_phys_writer_by_tag[dut.io_rfWriteTag] = writer;
    g_arch_writer_by_reg[writer.wb_reg] = writer;
  }
  trace_top_debug_pipeline(dut, context);
}

void attach_issue_debug(ObservedRow &row) {
  const auto it = g_issue_debug_by_rob.find(row.rob_value);
  if (it == g_issue_debug_by_rob.end()) {
    return;
  }
  row.src_phys_valid_mask = it->second.src_valid_mask;
  row.src_phys_tag0 = it->second.src_phys_tag0;
  row.src_phys_tag1 = it->second.src_phys_tag1;
  row.src_phys_tag2 = it->second.src_phys_tag2;
}

void dump_phys_writer_line(const char *label, std::uint8_t tag) {
  const auto it = g_phys_writer_by_tag.find(tag);
  if (it == g_phys_writer_by_tag.end() || !it->second.valid) {
    std::cerr << "frontend fetch RF ALU " << label
              << " tag=" << static_cast<unsigned>(tag)
              << " writer=<none>\n";
    return;
  }
  std::cerr << "frontend fetch RF ALU " << label
            << " tag=" << static_cast<unsigned>(tag)
            << " writer_pc=0x" << std::hex << it->second.pc
            << " writer_insn=0x" << it->second.insn
            << std::dec
            << " writer_phys=" << static_cast<unsigned>(it->second.phys_tag)
            << " writer_wb=" << static_cast<unsigned>(it->second.wb_reg)
            << " writer_data=" << it->second.data << "\n";
}

void dump_arch_writer_line(const char *label, std::uint8_t reg) {
  const auto it = g_arch_writer_by_reg.find(reg);
  if (it == g_arch_writer_by_reg.end() || !it->second.valid) {
    std::cerr << "frontend fetch RF ALU " << label
              << " reg=" << static_cast<unsigned>(reg)
              << " writer=<none>\n";
    return;
  }
  std::cerr << "frontend fetch RF ALU " << label
            << " reg=" << static_cast<unsigned>(reg)
            << " writer_pc=0x" << std::hex << it->second.pc
            << " writer_insn=0x" << it->second.insn
            << std::dec
            << " writer_phys=" << static_cast<unsigned>(it->second.phys_tag)
            << " writer_data=" << it->second.data << "\n";
}

bool is_fentry_insn(std::uint64_t insn) {
  return (insn & 0x707full) == 0x41ull;
}

std::uint8_t fentry_begin_reg(std::uint64_t insn) {
  return static_cast<std::uint8_t>((insn >> 15) & 0x1f);
}

void trace_top_debug_pipeline(
    const VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const char *context) {
  if (!trace_top_debug_enabled()) {
    return;
  }
  const bool interesting =
      dut.io_denseSlotQueueOutFire ||
      dut.io_decRenPushFire ||
      dut.io_decRenPopFire ||
      dut.io_renamedAccepted ||
      dut.io_issueQueueEnqueueFire ||
      dut.io_issueQueuePickFire ||
      dut.io_issueQueueIssueFire ||
      dut.io_issueQueueReleaseFire ||
      dut.io_executeAccepted ||
      dut.io_executeCompleteValid ||
      dut.io_completeAccepted ||
      dut.io_commit_rows_0_valid ||
      dut.io_blockMarkerStopRedirectValid ||
      dut.io_robMarkerRetireSourceLifecycleFire;
  if (!interesting) {
    return;
  }
  std::cerr << "[trace-top-debug]"
            << " cycle=" << g_tb_cycle
            << " context=" << context
            << " dense_out=" << static_cast<unsigned>(dut.io_denseSlotQueueOutFire)
            << " dense_head_slot=" << static_cast<unsigned>(dut.io_denseSlotQueueHeadSlot)
            << " selected_rob=" << static_cast<unsigned>(dut.io_selectedRobValue)
            << " dec_push=" << static_cast<unsigned>(dut.io_decRenPushFire)
            << " dec_pop=" << static_cast<unsigned>(dut.io_decRenPopFire)
            << " dec_count=" << static_cast<unsigned>(dut.io_decRenCount)
            << " dec_head_pc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_decRenHeadPc)
            << std::dec
            << " renamed=" << static_cast<unsigned>(dut.io_renamedAccepted)
            << " rename_upd=" << static_cast<unsigned>(dut.io_robRenameUpdateFire)
            << " issue_enq=" << static_cast<unsigned>(dut.io_issueQueueEnqueueFire)
            << " issue_pick=" << static_cast<unsigned>(dut.io_issueQueuePickFire)
            << " issue_fire=" << static_cast<unsigned>(dut.io_issueQueueIssueFire)
            << " issue_rel=" << static_cast<unsigned>(dut.io_issueQueueReleaseFire)
            << " issue_count=" << static_cast<unsigned>(dut.io_issueQueueCount)
            << " issue_head_pc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_issueQueueHeadPc)
            << " exec_acc=" << std::dec << static_cast<unsigned>(dut.io_executeAccepted)
            << " exec_done=" << static_cast<unsigned>(dut.io_executeCompleteValid)
            << " exec_pc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_executeCompletePc)
            << " exec_insn=0x"
            << static_cast<unsigned long long>(dut.io_executeCompleteInsn)
            << std::dec
            << " exec_rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
            << " complete_acc=" << static_cast<unsigned>(dut.io_completeAccepted)
            << " commit_mask=0x" << std::hex << static_cast<unsigned>(dut.io_commitValidMask)
            << " commit0_pc=0x"
            << static_cast<unsigned long long>(dut.io_commit_rows_0_pc)
            << std::dec
            << " commit0_rob=" << static_cast<unsigned>(dut.io_commit_rows_0_rob_value)
            << " marker_redirect=" << static_cast<unsigned>(dut.io_blockMarkerStopRedirectValid)
            << " marker_redirect_pc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockMarkerStopRedirectPc)
            << std::dec
            << " marker_lifecycle_fire=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceLifecycleFire)
            << " marker_source_valid=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceValid)
            << " marker_source_bid=("
            << static_cast<unsigned>(dut.io_robMarkerRetireSourceBidValid)
            << "," << static_cast<unsigned>(dut.io_robMarkerRetireSourceBidWrap)
            << "," << static_cast<unsigned>(dut.io_robMarkerRetireSourceBidValue)
            << ") marker_source_rid=("
            << static_cast<unsigned>(dut.io_robMarkerRetireSourceRidValid)
            << "," << static_cast<unsigned>(dut.io_robMarkerRetireSourceRidWrap)
            << "," << static_cast<unsigned>(dut.io_robMarkerRetireSourceRidValue)
            << ") marker_source_stid=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceStid)
            << " marker_source_block_bid_valid="
            << static_cast<unsigned>(dut.io_robMarkerRetireSourceBlockBidValid)
            << " marker_source_block_bid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_robMarkerRetireSourceBlockBid)
            << std::dec
            << "\n";
}

void expect_monitor_clean(
    const VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const char *context,
    std::uint8_t expected_mask,
    std::uint8_t expected_count) {
  if (dut.io_commitContractError || dut.io_commitSkippedSlot ||
      dut.io_commitDuplicateIdentity || dut.io_commitSlotMismatch ||
      dut.io_commitInvalidSideEffect) {
    std::cerr << "commit monitor error during " << context
              << " mask=" << static_cast<unsigned>(dut.io_commitMonitorValidMask)
              << " count=" << static_cast<unsigned>(dut.io_commitMonitorValidCount)
              << " skipped=" << static_cast<unsigned>(dut.io_commitSkippedSlot)
              << " duplicate=" << static_cast<unsigned>(dut.io_commitDuplicateIdentity)
              << " slot_mismatch=" << static_cast<unsigned>(dut.io_commitSlotMismatch)
              << " invalid_side_effect=" << static_cast<unsigned>(dut.io_commitInvalidSideEffect)
              << "\n";
    std::exit(1);
  }
  if (dut.io_commitMonitorValidMask != expected_mask ||
      dut.io_commitMonitorValidCount != expected_count) {
    std::cerr << "commit monitor shape mismatch during " << context
              << " expected_mask=" << static_cast<unsigned>(expected_mask)
              << " observed_mask=" << static_cast<unsigned>(dut.io_commitMonitorValidMask)
              << " expected_count=" << static_cast<unsigned>(expected_count)
              << " observed_count=" << static_cast<unsigned>(dut.io_commitMonitorValidCount)
              << "\n";
    std::exit(1);
  }
}

ObservedRow read_slot0(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  ObservedRow row;
  row.valid = dut.io_commit_rows_0_valid;
  row.seq = dut.io_commit_rows_0_seq;
  row.cycle = dut.io_commit_rows_0_cycle;
  row.slot = dut.io_commit_rows_0_slot;
  row.bid = dut.io_commit_rows_0_identity_bid;
  row.gid = dut.io_commit_rows_0_identity_gid;
  row.rid = dut.io_commit_rows_0_identity_rid;
  row.rob_valid = dut.io_commit_rows_0_rob_valid;
  row.rob_wrap = dut.io_commit_rows_0_rob_wrap;
  row.rob_value = dut.io_commit_rows_0_rob_value;
  row.block_bid_valid = dut.io_commit_rows_0_blockBidValid;
  row.block_bid = dut.io_commit_rows_0_blockBid;
  row.pc = dut.io_commit_rows_0_pc;
  row.insn = dut.io_commit_rows_0_insn;
  row.len = dut.io_commit_rows_0_len;
  row.wb_valid = dut.io_commit_rows_0_wb_valid;
  row.wb_reg = dut.io_commit_rows_0_wb_reg;
  row.wb_data = dut.io_commit_rows_0_wb_data;
  row.src0_valid = dut.io_commit_rows_0_src0_valid;
  row.src0_reg = dut.io_commit_rows_0_src0_reg;
  row.src0_data = dut.io_commit_rows_0_src0_data;
  row.src1_valid = dut.io_commit_rows_0_src1_valid;
  row.src1_reg = dut.io_commit_rows_0_src1_reg;
  row.src1_data = dut.io_commit_rows_0_src1_data;
  row.dst_valid = dut.io_commit_rows_0_dst_valid;
  row.dst_reg = dut.io_commit_rows_0_dst_reg;
  row.dst_data = dut.io_commit_rows_0_dst_data;
  row.mem_valid = dut.io_commit_rows_0_mem_valid;
  row.mem_is_store = dut.io_commit_rows_0_mem_isStore;
  row.mem_addr = dut.io_commit_rows_0_mem_addr;
  row.mem_wdata = dut.io_commit_rows_0_mem_wdata;
  row.mem_rdata = dut.io_commit_rows_0_mem_rdata;
  row.mem_size = dut.io_commit_rows_0_mem_size;
  row.trap_valid = dut.io_commit_rows_0_trap_valid;
  row.trap_cause = dut.io_commit_rows_0_trap_cause;
  row.trap_arg0 = dut.io_commit_rows_0_trap_arg0;
  row.next_pc = dut.io_commit_rows_0_nextPc;
  row.rf_write_valid = dut.io_rfWriteValid;
  row.rf_write_tag = dut.io_rfWriteTag;
  row.rf_write_data = dut.io_rfWriteData;
  row.src_phys_valid_mask = dut.io_executeCompleteSrcPhysValidMask;
  row.src_phys_tag0 = dut.io_executeCompleteSrcPhysTag_0;
  row.src_phys_tag1 = dut.io_executeCompleteSrcPhysTag_1;
  row.src_phys_tag2 = dut.io_executeCompleteSrcPhysTag_2;
  attach_issue_debug(row);
  return row;
}

bool slot1_valid(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  return dut.io_commit_rows_1_valid;
}

ObservedRow read_slot1(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  ObservedRow row;
  row.valid = dut.io_commit_rows_1_valid;
  row.seq = dut.io_commit_rows_1_seq;
  row.cycle = dut.io_commit_rows_1_cycle;
  row.slot = dut.io_commit_rows_1_slot;
  row.bid = dut.io_commit_rows_1_identity_bid;
  row.gid = dut.io_commit_rows_1_identity_gid;
  row.rid = dut.io_commit_rows_1_identity_rid;
  row.rob_valid = dut.io_commit_rows_1_rob_valid;
  row.rob_wrap = dut.io_commit_rows_1_rob_wrap;
  row.rob_value = dut.io_commit_rows_1_rob_value;
  row.block_bid_valid = dut.io_commit_rows_1_blockBidValid;
  row.block_bid = dut.io_commit_rows_1_blockBid;
  row.pc = dut.io_commit_rows_1_pc;
  row.insn = dut.io_commit_rows_1_insn;
  row.len = dut.io_commit_rows_1_len;
  row.wb_valid = dut.io_commit_rows_1_wb_valid;
  row.wb_reg = dut.io_commit_rows_1_wb_reg;
  row.wb_data = dut.io_commit_rows_1_wb_data;
  row.src0_valid = dut.io_commit_rows_1_src0_valid;
  row.src0_reg = dut.io_commit_rows_1_src0_reg;
  row.src0_data = dut.io_commit_rows_1_src0_data;
  row.src1_valid = dut.io_commit_rows_1_src1_valid;
  row.src1_reg = dut.io_commit_rows_1_src1_reg;
  row.src1_data = dut.io_commit_rows_1_src1_data;
  row.dst_valid = dut.io_commit_rows_1_dst_valid;
  row.dst_reg = dut.io_commit_rows_1_dst_reg;
  row.dst_data = dut.io_commit_rows_1_dst_data;
  row.mem_valid = dut.io_commit_rows_1_mem_valid;
  row.mem_is_store = dut.io_commit_rows_1_mem_isStore;
  row.mem_addr = dut.io_commit_rows_1_mem_addr;
  row.mem_wdata = dut.io_commit_rows_1_mem_wdata;
  row.mem_rdata = dut.io_commit_rows_1_mem_rdata;
  row.mem_size = dut.io_commit_rows_1_mem_size;
  row.trap_valid = dut.io_commit_rows_1_trap_valid;
  row.trap_cause = dut.io_commit_rows_1_trap_cause;
  row.trap_arg0 = dut.io_commit_rows_1_trap_arg0;
  row.next_pc = dut.io_commit_rows_1_nextPc;
  row.rf_write_valid = dut.io_rfWriteValid;
  row.rf_write_tag = dut.io_rfWriteTag;
  row.rf_write_data = dut.io_rfWriteData;
  row.src_phys_valid_mask = dut.io_executeCompleteSrcPhysValidMask;
  row.src_phys_tag0 = dut.io_executeCompleteSrcPhysTag_0;
  row.src_phys_tag1 = dut.io_executeCompleteSrcPhysTag_1;
  row.src_phys_tag2 = dut.io_executeCompleteSrcPhysTag_2;
  attach_issue_debug(row);
  return row;
}

void collect_commit_if_present(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    std::vector<ObservedRow> &pending,
    const char *context) {
  if (dut.io_executeUnsupported) {
    std::cerr << context << " execute reported unsupported opcode="
              << static_cast<unsigned>(dut.io_executeUnsupportedOpcode) << "\n";
    std::exit(1);
  }
  if (dut.io_executeCompleteValid && dut.io_completeIgnored) {
    std::cerr << context << " execute completion was ignored"
              << " rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
              << "\n";
    std::exit(1);
  }
  if (!dut.io_commit_rows_0_valid) {
    return;
  }
  const ObservedRow slot0 = read_slot0(dut);
  const bool slot1 = slot1_valid(dut);
  expect_monitor_clean(dut, context, slot1 ? 0x3 : 0x1, slot1 ? 2 : 1);
  pending.push_back(slot0);
  if (slot1) {
    pending.push_back(read_slot1(dut));
  }
}

void expect_row(
    const ObservedRow &observed,
    const ExpectedRow &expected,
    const VLinxCoreFrontendFetchRfAluTraceTop *dut = nullptr) {
  if (!observed.valid ||
      observed.slot > 1 ||
      observed.pc != expected.pc ||
      mask_insn(observed.insn, observed.len) != mask_insn(expected.insn, expected.len) ||
      observed.len != expected.len ||
      observed.mem_valid != expected.mem_valid ||
      observed.mem_is_store != expected.mem_is_store ||
      observed.trap_valid ||
      observed.src0_valid != expected.src0_valid ||
      observed.src1_valid != expected.src1_valid ||
      observed.dst_valid != expected.dst_valid ||
      observed.wb_valid != expected.dst_valid ||
      observed.next_pc != expected.next_pc) {
    std::cerr << "frontend fetch RF ALU trace top commit row mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << observed.insn
              << std::dec << " len=" << static_cast<unsigned>(observed.len)
              << " wb_valid=" << observed.wb_valid
              << " src0=(" << observed.src0_valid << ","
              << static_cast<unsigned>(observed.src0_reg) << ")"
              << " src1=(" << observed.src1_valid << ","
              << static_cast<unsigned>(observed.src1_reg) << ")"
              << " dst=(" << observed.dst_valid << ","
              << static_cast<unsigned>(observed.dst_reg) << ")"
              << " mem=(" << observed.mem_valid << ","
              << observed.mem_is_store << ")"
              << " next_pc=0x" << std::hex << observed.next_pc << std::dec
              << " seq=" << observed.seq
              << " cycle=" << observed.cycle
              << " slot=" << static_cast<unsigned>(observed.slot)
              << " rob=(" << observed.rob_valid
              << "," << observed.rob_wrap
              << "," << static_cast<unsigned>(observed.rob_value) << ")"
              << "\n";
    if (dut != nullptr) {
      std::cerr << "frontend fetch RF ALU commit debug"
                << " commit_mask=0x" << std::hex << static_cast<unsigned>(dut->io_commitValidMask)
                << " dealloc_mask=0x" << static_cast<unsigned>(dut->io_deallocValidMask)
                << " occupied=0x" << hex_port(dut->io_occupiedMask)
                << " completed=0x" << hex_port(dut->io_completedMask)
                << " retired=0x" << hex_port(dut->io_retiredMask)
                << std::dec
                << " commit_count=" << static_cast<unsigned>(dut->io_commitCount)
                << " dealloc_count=" << static_cast<unsigned>(dut->io_deallocCount)
                << " commit_head_valid=" << static_cast<unsigned>(dut->io_commitHeadValid)
                << " commit_head_status=" << static_cast<unsigned>(dut->io_commitHeadStatus)
                << " commit_head_rob=" << static_cast<unsigned>(dut->io_commitHeadRobValue)
                << " size=" << static_cast<unsigned>(dut->io_size)
                << " outstanding=" << static_cast<unsigned>(dut->io_outstandingCount)
                << " dec_ren_count=" << static_cast<unsigned>(dut->io_decRenCount)
                << " dec_ren_valid=" << static_cast<unsigned>(dut->io_decRenValid)
                << " dec_ren_head_pc=0x" << std::hex
                << static_cast<unsigned long long>(dut->io_decRenHeadPc)
                << std::dec
                << " renamed_accepted=" << static_cast<unsigned>(dut->io_renamedAccepted)
                << " rename_update_attempt=" << static_cast<unsigned>(dut->io_robRenameUpdateAttemptValid)
                << " rename_update_fire=" << static_cast<unsigned>(dut->io_robRenameUpdateFire)
                << " complete_accepted=" << static_cast<unsigned>(dut->io_completeAccepted)
                << " execute_accepted=" << static_cast<unsigned>(dut->io_executeAccepted)
                << " dense_count=" << static_cast<unsigned>(dut->io_denseSlotQueueCount)
                << " marker_barrier=" << static_cast<unsigned>(dut->io_admittedMarkerDrainBarrier)
                << "\n";
    }
    std::exit(1);
  }

  if (observed.src0_valid &&
      (observed.src0_reg != expected.src0_reg || observed.src0_data != expected.src0_data)) {
    std::cerr << "frontend fetch RF ALU trace top src0 mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected=(" << static_cast<unsigned>(expected.src0_reg)
              << "," << expected.src0_data << ") observed=("
              << static_cast<unsigned>(observed.src0_reg)
              << "," << observed.src0_data << ")"
              << " src_phys_mask=0x" << std::hex << static_cast<unsigned>(observed.src_phys_valid_mask)
              << std::dec
              << " src_phys=(" << static_cast<unsigned>(observed.src_phys_tag0)
              << "," << static_cast<unsigned>(observed.src_phys_tag1)
              << "," << static_cast<unsigned>(observed.src_phys_tag2) << ")"
              << " rf_write=(" << std::dec << observed.rf_write_valid
              << "," << static_cast<unsigned>(observed.rf_write_tag)
              << "," << observed.rf_write_data << ")\n";
    dump_phys_writer_line("src0 last writer", observed.src_phys_tag0);
    dump_arch_writer_line("src0 arch last writer", expected.src0_reg);
    dump_gpr_commit_history_line("src0 mismatch history");
    std::exit(1);
  }
  if (observed.src1_valid &&
      (observed.src1_reg != expected.src1_reg || observed.src1_data != expected.src1_data)) {
    std::cerr << "frontend fetch RF ALU trace top src1 mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected=(" << static_cast<unsigned>(expected.src1_reg)
              << "," << expected.src1_data << ") observed=("
              << static_cast<unsigned>(observed.src1_reg)
              << "," << observed.src1_data << ")"
              << " src_phys_mask=0x" << std::hex << static_cast<unsigned>(observed.src_phys_valid_mask)
              << std::dec
              << " src_phys=(" << static_cast<unsigned>(observed.src_phys_tag0)
              << "," << static_cast<unsigned>(observed.src_phys_tag1)
              << "," << static_cast<unsigned>(observed.src_phys_tag2) << ")"
              << " rf_write=(" << std::dec << observed.rf_write_valid
              << "," << static_cast<unsigned>(observed.rf_write_tag)
              << "," << observed.rf_write_data << ")\n";
    dump_phys_writer_line("src1 last writer", observed.src_phys_tag1);
    dump_arch_writer_line("src1 arch last writer", expected.src1_reg);
    dump_gpr_commit_history_line("src1 mismatch history");
    std::exit(1);
  }
  if (observed.dst_valid &&
      (observed.dst_reg != expected.dst_reg || observed.dst_data != expected.dst_data ||
       observed.wb_reg != expected.dst_reg || observed.wb_data != expected.dst_data)) {
    std::cerr << "frontend fetch RF ALU trace top dst/wb mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected=(" << static_cast<unsigned>(expected.dst_reg)
              << "," << expected.dst_data << ") observed_dst=("
              << static_cast<unsigned>(observed.dst_reg)
              << "," << observed.dst_data << ") observed_wb=("
              << static_cast<unsigned>(observed.wb_reg)
              << "," << observed.wb_data << ")\n";
    std::exit(1);
  }
  if (observed.mem_valid &&
      (observed.mem_addr != expected.mem_addr ||
       observed.mem_wdata != expected.mem_wdata ||
       observed.mem_rdata != expected.mem_rdata ||
       observed.mem_size != expected.mem_size)) {
    std::cerr << "frontend fetch RF ALU trace top mem mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << " expected=(addr=0x" << std::hex << expected.mem_addr
              << ",wdata=0x" << expected.mem_wdata
              << ",rdata=0x" << expected.mem_rdata
              << std::dec << ",size=" << static_cast<unsigned>(expected.mem_size)
              << ") observed=(addr=0x" << std::hex << observed.mem_addr
              << ",wdata=0x" << observed.mem_wdata
              << ",rdata=0x" << observed.mem_rdata
              << std::dec << ",size=" << static_cast<unsigned>(observed.mem_size)
              << ")"
              << " src_phys_mask=0x" << std::hex << static_cast<unsigned>(observed.src_phys_valid_mask)
              << std::dec
              << " src_phys=(" << static_cast<unsigned>(observed.src_phys_tag0)
              << "," << static_cast<unsigned>(observed.src_phys_tag1)
              << "," << static_cast<unsigned>(observed.src_phys_tag2) << ")"
              << " visible_src0=(" << static_cast<unsigned>(observed.src0_reg)
              << "," << observed.src0_data << ")"
              << " visible_src1=(" << static_cast<unsigned>(observed.src1_reg)
              << "," << observed.src1_data << ")\n";
    dump_phys_writer_line("mem src0 last writer", observed.src_phys_tag0);
    dump_phys_writer_line("mem src1 last writer", observed.src_phys_tag1);
    dump_phys_writer_line("mem src2 last writer", observed.src_phys_tag2);
    if (is_fentry_insn(observed.insn)) {
      dump_arch_writer_line("fentry save-source arch last writer", fentry_begin_reg(observed.insn));
    }
    std::exit(1);
  }
}

CommitTraceJsonRow to_json_row(const ObservedRow &row) {
  CommitTraceJsonRow json;
  json.valid = row.valid;
  json.seq = row.seq;
  json.cycle = row.cycle;
  json.slot = row.slot;
  json.bid = row.bid;
  json.gid = row.gid;
  json.rid = row.rid;
  json.rob_valid = row.rob_valid;
  json.rob_wrap = row.rob_wrap;
  json.rob_value = row.rob_value;
  json.block_bid_valid = row.block_bid_valid;
  json.block_bid = row.block_bid;
  json.pc = row.pc;
  json.insn = row.insn;
  json.len = row.len;
  json.wb_valid = row.wb_valid;
  json.wb_rd = row.wb_reg;
  json.wb_data = row.wb_data;
  json.src0_valid = row.src0_valid;
  json.src0_reg = row.src0_reg;
  json.src0_data = row.src0_data;
  json.src1_valid = row.src1_valid;
  json.src1_reg = row.src1_reg;
  json.src1_data = row.src1_data;
  json.dst_valid = row.dst_valid;
  json.dst_reg = row.dst_reg;
  json.dst_data = row.dst_data;
  json.mem_valid = row.mem_valid;
  json.mem_is_store = row.mem_is_store;
  json.mem_addr = row.mem_addr;
  json.mem_wdata = row.mem_wdata;
  json.mem_rdata = row.mem_rdata;
  json.mem_size = row.mem_size;
  json.trap_valid = row.trap_valid;
  json.trap_cause = row.trap_cause;
  json.trap_arg0 = row.trap_arg0;
  json.next_pc = row.next_pc;
  return json;
}

CommitTraceJsonRow to_json_row(const ExpectedRow &row) {
  CommitTraceJsonRow json;
  json.pc = row.pc;
  json.insn = mask_insn(row.insn, row.len);
  json.len = row.len;
  json.wb_valid = row.dst_valid;
  json.wb_rd = row.dst_reg;
  json.wb_data = row.dst_data;
  json.src0_valid = row.src0_valid;
  json.src0_reg = row.src0_reg;
  json.src0_data = row.src0_data;
  json.src1_valid = row.src1_valid;
  json.src1_reg = row.src1_reg;
  json.src1_data = row.src1_data;
  json.dst_valid = row.dst_valid;
  json.dst_reg = row.dst_reg;
  json.dst_data = row.dst_data;
  json.mem_valid = row.mem_valid;
  json.mem_is_store = row.mem_is_store;
  json.mem_addr = row.mem_addr;
  json.mem_wdata = row.mem_wdata;
  json.mem_rdata = row.mem_rdata;
  json.mem_size = row.mem_size;
  json.next_pc = row.next_pc;
  return json;
}

void write_dut_row(std::ofstream &out, const ObservedRow &row) {
  write_dut_commit_jsonl(out, to_json_row(row));
  out.flush();
}

void write_qemu_row(std::ofstream &out, const ExpectedRow &row) {
  write_qemu_commit_jsonl(out, to_json_row(row));
  out.flush();
}

void start_source(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::uint64_t pc) {
  clear_inputs(dut);
  dut.io_startValid = 1;
  dut.io_startPc = pc;
  tick(dut);
  clear_inputs(dut);
  dut.eval();
  if (!dut.io_sourceActive) {
    std::cerr << "frontend fetch RF ALU source did not arm at start pc=0x"
              << std::hex << pc << std::dec << "\n";
    std::exit(1);
  }
}

std::size_t dense_window_end(const std::vector<ExpectedRow> &rows, std::size_t start) {
  const std::uint64_t start_pc = rows.at(start).pc;
  std::uint64_t next_pc = start_pc;
  std::size_t end = start;
  while (end < rows.size()) {
    const ExpectedRow &row = rows[end];
    if (row.pc != next_pc) {
      break;
    }
    if ((row.pc + row.len) - start_pc > 8U) {
      break;
    }
    next_pc = row.next_pc;
    ++end;
    if (row.next_pc != row.pc + row.len) {
      break;
    }
  }
  if (end == start) {
    std::cerr << "dense window grouping could not fit first row"
              << " pc=0x" << std::hex << start_pc << std::dec
              << " len=" << static_cast<unsigned>(rows[start].len) << "\n";
    std::exit(1);
  }
  return end;
}

std::uint8_t dense_window_mask(std::size_t count) {
  if (count == 0 || count > 4) {
    std::cerr << "unsupported dense window slot count=" << count << "\n";
    std::exit(1);
  }
  return static_cast<std::uint8_t>((1U << count) - 1U);
}

std::uint8_t dense_window_advance(const std::vector<ExpectedRow> &rows, std::size_t start, std::size_t end) {
  const std::uint64_t start_pc = rows.at(start).pc;
  const ExpectedRow &last = rows.at(end - 1);
  const std::uint64_t advance = (last.pc + last.len) - start_pc;
  if (advance == 0 || advance > 8) {
    std::cerr << "unsupported dense window advance=" << advance << "\n";
    std::exit(1);
  }
  return static_cast<std::uint8_t>(advance);
}

bool row_redirects(const ExpectedRow &row) {
  return row.next_pc != row.pc + row.len;
}

bool is_marker_row_shape(const ObservedRow &observed, const ExpectedRow &expected) {
  return observed.valid &&
         observed.pc == expected.pc &&
         mask_insn(observed.insn, observed.len) == mask_insn(expected.insn, expected.len) &&
         observed.len == expected.len &&
         !observed.mem_valid &&
         !observed.trap_valid &&
         !observed.src0_valid &&
         !observed.src1_valid &&
         !observed.dst_valid &&
         !observed.wb_valid;
}

bool is_observed_marker_commit(const ObservedRow &observed) {
  return observed.valid &&
         !observed.mem_valid &&
         !observed.trap_valid &&
         !observed.src0_valid &&
         !observed.src1_valid &&
         !observed.dst_valid &&
         !observed.wb_valid;
}

void expect_marker_commit(const ObservedRow &observed, const ExpectedRow &expected) {
  if (!is_marker_row_shape(observed, expected)) {
    std::cerr << "frontend fetch RF ALU marker commit mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected_len=" << static_cast<unsigned>(expected.len)
              << " observed_len=" << static_cast<unsigned>(observed.len)
              << " mem=" << observed.mem_valid
              << " trap=" << observed.trap_valid
              << " src0=" << observed.src0_valid
              << " src1=" << observed.src1_valid
              << " dst=" << observed.dst_valid
              << " wb=" << observed.wb_valid
              << "\n";
    std::exit(1);
  }
}

void filter_pending_marker_commits(
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count);

struct BfuBodyGeometryHint {
  bool valid = false;
  std::uint64_t header_pc = 0;
  std::uint64_t hsize_bytes = 0;
  std::uint64_t bsize_bytes = 0;
};

struct BfuGeometryDiagnostics {
  std::uint64_t comparable_count = 0;
  std::uint64_t match_count = 0;
  std::uint64_t resolved_accept_count = 0;
  std::uint64_t cut_arm_comparable_count = 0;
  std::uint64_t cut_arm_accept_count = 0;
  std::uint64_t cut_arm_mismatch_count = 0;
  std::uint64_t local_body_cut_prefix_count = 0;
  std::uint64_t resolved_source_runtime_selected_count = 0;
  std::uint64_t resolved_source_replay_selected_count = 0;
  std::uint64_t resolved_source_runtime_feedback_count = 0;
  std::uint64_t resolved_source_runtime_pending_count = 0;
  std::uint64_t resolved_source_runtime_pending_consume_count = 0;
  std::uint64_t resolved_source_runtime_pending_drop_mismatch_count = 0;
  std::uint64_t resolved_source_runtime_pending_candidate_comparable_count = 0;
  std::uint64_t resolved_source_runtime_pending_candidate_match_count = 0;
  std::uint64_t resolved_source_runtime_pending_candidate_mismatch_count = 0;
  std::uint64_t pending_runtime_candidate_valid_count = 0;
  std::uint64_t pending_runtime_candidate_without_active_header_count = 0;
  std::uint64_t pending_runtime_candidate_active_header_mismatch_count = 0;
  std::uint64_t pending_runtime_candidate_replay_comparable_count = 0;
  std::uint64_t pending_runtime_candidate_replay_match_count = 0;
  std::uint64_t pending_runtime_candidate_replay_mismatch_count = 0;
  std::uint64_t promoted_runtime_body_end_oracle_pending_count = 0;
  std::uint64_t promoted_runtime_body_end_oracle_capture_count = 0;
  std::uint64_t promoted_runtime_body_end_oracle_replay_comparable_count = 0;
  std::uint64_t promoted_runtime_body_end_oracle_replay_match_count = 0;
  std::uint64_t promoted_runtime_body_end_oracle_replay_mismatch_count = 0;
  std::uint64_t promoted_runtime_body_end_oracle_overwrite_count = 0;
  std::uint64_t resolved_source_runtime_replay_comparable_count = 0;
  std::uint64_t resolved_source_runtime_replay_match_count = 0;
  std::uint64_t resolved_source_runtime_replay_mismatch_count = 0;
};

struct FetchDenseWindowResult {
  bool captured_tail_superset = false;
  bool local_body_cut_prefix = false;
  std::size_t captured_slots = 0;
};

void observe_bfu_geometry_diagnostics(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    BfuGeometryDiagnostics &stats,
    const char *context) {
  if (dut.io_reducedBfuStaticExternalMismatch ||
      dut.io_reducedBfuStaticExternalHeaderMismatch ||
      dut.io_reducedBfuStaticExternalHSizeMismatch ||
      dut.io_reducedBfuStaticExternalBSizeMismatch) {
    std::cerr << "frontend fetch RF ALU BFU static/external geometry mismatch during "
              << context
              << " comparable=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalComparable)
              << " match=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalMatch)
              << " headerMismatch=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalHeaderMismatch)
              << " hsizeMismatch=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalHSizeMismatch)
              << " bsizeMismatch=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalBSizeMismatch)
              << "\n";
    std::exit(1);
  }
  if (dut.io_reducedBfuResolvedBodyEndHeaderMismatch ||
      dut.io_reducedBfuResolvedBodyEndInactiveDrop ||
      dut.io_reducedBfuResolvedBodyEndFlushDrop ||
      dut.io_reducedBfuResolvedBodyEndUnderflow) {
    std::cerr << "frontend fetch RF ALU BFU resolved body-end owner rejected replay geometry during "
              << context
              << " accepted=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndAccepted)
              << " headerMismatch=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndHeaderMismatch)
              << " inactiveDrop=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndInactiveDrop)
              << " flushDrop=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndFlushDrop)
              << " underflow=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndUnderflow)
              << "\n";
    std::exit(1);
  }
  if (dut.io_reducedBfuResolvedBodyEndAccepted) {
    ++stats.resolved_accept_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeSelected) {
    ++stats.resolved_source_runtime_selected_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceReplaySelected) {
    ++stats.resolved_source_replay_selected_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeFeedbackFire) {
    ++stats.resolved_source_runtime_feedback_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePending) {
    ++stats.resolved_source_runtime_pending_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingConsumeFire) {
    ++stats.resolved_source_runtime_pending_consume_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingDropMismatch) {
    ++stats.resolved_source_runtime_pending_drop_mismatch_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingCandidateComparable) {
    ++stats.resolved_source_runtime_pending_candidate_comparable_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMatch) {
    ++stats.resolved_source_runtime_pending_candidate_match_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMismatch) {
    ++stats.resolved_source_runtime_pending_candidate_mismatch_count;
  }
  if (dut.io_reducedBfuPendingRuntimeCandidateValid) {
    ++stats.pending_runtime_candidate_valid_count;
  }
  if (dut.io_reducedBfuPendingRuntimeCandidatePendingWithoutActiveHeader) {
    ++stats.pending_runtime_candidate_without_active_header_count;
  }
  if (dut.io_reducedBfuPendingRuntimeCandidateActiveHeaderMismatch) {
    ++stats.pending_runtime_candidate_active_header_mismatch_count;
  }
  if (dut.io_reducedBfuPendingRuntimeCandidateReplayComparable) {
    ++stats.pending_runtime_candidate_replay_comparable_count;
    if (dut.io_reducedBfuPendingRuntimeCandidateReplayMismatch) {
      ++stats.pending_runtime_candidate_replay_mismatch_count;
      std::cerr << "frontend fetch RF ALU pending runtime candidate mismatched replay during "
                << context << "\n";
      std::exit(1);
    }
    if (dut.io_reducedBfuPendingRuntimeCandidateReplayMatch) {
      ++stats.pending_runtime_candidate_replay_match_count;
    }
  }
  if (dut.io_reducedBfuPromotedRuntimeBodyEndOraclePending) {
    ++stats.promoted_runtime_body_end_oracle_pending_count;
  }
  if (dut.io_reducedBfuPromotedRuntimeBodyEndOracleCaptureFire) {
    ++stats.promoted_runtime_body_end_oracle_capture_count;
  }
  if (dut.io_reducedBfuPromotedRuntimeBodyEndOracleOverwritePending) {
    ++stats.promoted_runtime_body_end_oracle_overwrite_count;
    std::cerr << "frontend fetch RF ALU promoted runtime body-end oracle was overwritten before replay during "
              << context << "\n";
    std::exit(1);
  }
  if (dut.io_reducedBfuPromotedRuntimeBodyEndOracleReplayComparable) {
    ++stats.promoted_runtime_body_end_oracle_replay_comparable_count;
    if (dut.io_reducedBfuPromotedRuntimeBodyEndOracleReplayMismatch) {
      ++stats.promoted_runtime_body_end_oracle_replay_mismatch_count;
      std::cerr << "frontend fetch RF ALU promoted runtime body-end oracle mismatched replay during "
                << context << "\n";
      std::exit(1);
    }
    if (dut.io_reducedBfuPromotedRuntimeBodyEndOracleReplayMatch) {
      ++stats.promoted_runtime_body_end_oracle_replay_match_count;
    }
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeReplayComparable) {
    ++stats.resolved_source_runtime_replay_comparable_count;
    if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeReplayMismatch) {
      ++stats.resolved_source_runtime_replay_mismatch_count;
    }
    if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeReplayMatch) {
      ++stats.resolved_source_runtime_replay_match_count;
    }
  }
  if (dut.io_reducedBfuBodyCutArmComparable) {
    ++stats.cut_arm_comparable_count;
    if (dut.io_reducedBfuBodyCutArmMismatch ||
        dut.io_reducedBfuBodyCutArmHeaderMismatch ||
        dut.io_reducedBfuBodyCutArmHSizeMismatch ||
        dut.io_reducedBfuBodyCutArmBSizeMismatch) {
      ++stats.cut_arm_mismatch_count;
    }
  }
  if (dut.io_reducedBfuBodyCutArmAccepted) {
    ++stats.cut_arm_accept_count;
  }
  if (dut.io_reducedBfuStaticExternalComparable) {
    ++stats.comparable_count;
    if (!dut.io_reducedBfuStaticExternalMatch) {
      std::cerr << "frontend fetch RF ALU BFU geometry was comparable without a match during "
                << context << "\n";
      std::exit(1);
    }
    ++stats.match_count;
  }
}

BfuBodyGeometryHint dense_window_bfu_body_geometry(const std::vector<ExpectedRow> &rows, std::size_t start, std::size_t end) {
  BfuBodyGeometryHint hint;
  if (end <= start || end >= rows.size()) {
    return hint;
  }
  const ExpectedRow &next = rows[end];
  if (!next.loop_reentry || next.loop_reentry_from_pc == 0) {
    return hint;
  }
  const std::uint64_t cut_pc = rows[end - 1].pc + rows[end - 1].len;
  if (next.loop_reentry_from_pc != cut_pc) {
    std::cerr << "loop re-entry metadata does not match dense window cut"
              << " start_pc=0x" << std::hex << rows[start].pc
              << " expected_cut=0x" << cut_pc
              << " row_cut=0x" << next.loop_reentry_from_pc
              << " restart=0x" << next.pc << std::dec << "\n";
    std::exit(1);
  }
  const std::uint64_t header_pc = next.pc;
  const std::uint64_t body_base_pc = header_pc + 2;
  if (cut_pc < body_base_pc) {
    std::cerr << "loop re-entry metadata cuts before reduced BFU body base"
              << " header_pc=0x" << std::hex << header_pc
              << " body_base=0x" << body_base_pc
              << " cut=0x" << cut_pc << std::dec << "\n";
    std::exit(1);
  }
  hint.valid = true;
  hint.header_pc = header_pc;
  hint.hsize_bytes = 0;
  hint.bsize_bytes = cut_pc - body_base_pc;
  return hint;
}

void drive_bfu_body_geometry_hint(VLinxCoreFrontendFetchRfAluTraceTop &dut, const BfuBodyGeometryHint &hint) {
  if (!hint.valid) {
    return;
  }
  dut.io_reducedBfuBodyValid = 1;
  dut.io_reducedBfuHeaderPc = hint.header_pc;
  dut.io_reducedBfuHSizeBytes = hint.hsize_bytes;
  dut.io_reducedBfuBSizeBytes = hint.bsize_bytes;
}

bool is_suppressed_local_body_cut_marker(const std::vector<ExpectedRow> &rows, std::size_t index) {
  if (index + 1 >= rows.size()) {
    return false;
  }
  const ExpectedRow &row = rows[index];
  return row.skip &&
         row.block_boundary &&
         !row.block_stop &&
         row_redirects(row) &&
         row.next_pc < row.pc &&
         rows[index + 1].pc == row.next_pc;
}

FetchDenseWindowResult fetch_dense_window(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const std::vector<ExpectedRow> &rows,
    std::size_t start,
    std::size_t end,
    const FetchMemoryImage &fetch_memory,
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count,
    BfuGeometryDiagnostics &bfu_stats) {
  const ExpectedRow &first = rows.at(start);
  const std::size_t slot_count = end - start;
  const std::uint8_t expected_mask = dense_window_mask(slot_count);
  const std::uint8_t expected_advance = dense_window_advance(rows, start, end);
  const bool redirect_tail = row_redirects(rows.at(end - 1));
  const bool capture_tail = end == rows.size();
  const BfuBodyGeometryHint body_cut = dense_window_bfu_body_geometry(rows, start, end);
  const std::uint64_t bfu_match_count_before = bfu_stats.match_count;

  for (int cycle = 0; cycle < 8; ++cycle) {
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    clear_inputs(dut);
    dut.io_fetchReqReady = 1;
    drive_bfu_body_geometry_hint(dut, body_cut);
    eval_with_load_lookup(dut, fetch_memory);
    observe_bfu_geometry_diagnostics(dut, bfu_stats, "fetch request");
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU fetch");
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (dut.io_fetchReqValid) {
      if (dut.io_fetchReqPc != first.pc || !dut.io_sourceReqFire) {
        std::cerr << "frontend fetch RF ALU request mismatch"
                  << " expected_pc=0x" << std::hex << first.pc
                  << " observed_pc=0x" << dut.io_fetchReqPc << std::dec
                  << " sourceReqFire=" << static_cast<unsigned>(dut.io_sourceReqFire)
                  << "\n";
        std::exit(1);
      }
      tick(dut);
      goto request_done;
    }
    tick(dut);
  }
  std::cerr << "frontend fetch RF ALU source did not request pc=0x"
            << std::hex << first.pc << std::dec << "\n";
  std::exit(1);

request_done:
  clear_inputs(dut);
  dut.io_fetchRespValid = 1;
  dut.io_fetchRespWindow = fetch_memory.read_window(first.pc);
  drive_bfu_body_geometry_hint(dut, body_cut);
  eval_with_load_lookup(dut, fetch_memory);
  observe_bfu_geometry_diagnostics(dut, bfu_stats, "fetch response");
  collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU fetch response");
  filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
  if (!dut.io_fetchRespReady || !dut.io_sourceRespFire) {
    std::cerr << "frontend fetch RF ALU response was not accepted"
              << " pc=0x" << std::hex << first.pc << std::dec
              << " respReady=" << static_cast<unsigned>(dut.io_fetchRespReady)
              << " sourceRespFire=" << static_cast<unsigned>(dut.io_sourceRespFire)
              << "\n";
    std::exit(1);
  }
  tick(dut);

  for (int cycle = 0; cycle < 8; ++cycle) {
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    clear_inputs(dut);
    drive_bfu_body_geometry_hint(dut, body_cut);
    eval_with_load_lookup(dut, fetch_memory);
    observe_bfu_geometry_diagnostics(dut, bfu_stats, "dense packet drain");
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU dense drain");
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error before enqueue\n";
      std::exit(1);
    }
    if (dut.io_sourceOutFire) {
      const bool strict_dense_match =
          dut.io_f4ValidMask == expected_mask &&
          dut.io_f4SlotCount == slot_count &&
          dut.io_denseSlotQueueInSlotCount == slot_count &&
          dut.io_sourceAdvanceBytes == expected_advance;
      const bool relaxed_prefix_match =
          (redirect_tail || capture_tail) &&
          (dut.io_f4ValidMask & expected_mask) == expected_mask &&
          dut.io_f4SlotCount >= slot_count &&
          dut.io_denseSlotQueueInSlotCount >= slot_count &&
          dut.io_sourceAdvanceBytes >= expected_advance;
      const std::size_t observed_slots = static_cast<std::size_t>(dut.io_denseSlotQueueInSlotCount);
      const bool observed_strict_prefix = observed_slots > 0 && observed_slots < slot_count;
      const bool local_body_cut_prefix =
          observed_strict_prefix &&
          dut.io_reducedBodyCutFire &&
          dut.io_f4SlotCount == observed_slots &&
          dut.io_f4ValidMask == dense_window_mask(observed_slots) &&
          dut.io_sourceAdvanceBytes == dense_window_advance(rows, start, start + observed_slots) &&
          dut.io_reducedBodyCutAdvanceBytes == dut.io_sourceAdvanceBytes;
      if (!dut.io_denseSlotQueueInFire || (!strict_dense_match && !relaxed_prefix_match && !local_body_cut_prefix)) {
        std::cerr << "frontend fetch RF ALU dense packet was not captured"
                  << " pc=0x" << std::hex << first.pc << std::dec
                  << " expected_mask=0x" << std::hex << static_cast<unsigned>(expected_mask)
                  << " observed_mask=0x" << static_cast<unsigned>(dut.io_f4ValidMask)
                  << std::dec
                  << " expected_slots=" << slot_count
                  << " observed_f4_slots=" << static_cast<unsigned>(dut.io_f4SlotCount)
                  << " observed_queue_slots=" << static_cast<unsigned>(dut.io_denseSlotQueueInSlotCount)
                  << " expected_advance=" << static_cast<unsigned>(expected_advance)
                  << " observed_advance=" << static_cast<unsigned>(dut.io_sourceAdvanceBytes)
                  << "\n";
        std::exit(1);
      }
      FetchDenseWindowResult result;
      result.captured_tail_superset = capture_tail && observed_slots > slot_count;
      result.local_body_cut_prefix = local_body_cut_prefix;
      result.captured_slots = local_body_cut_prefix ? observed_slots : slot_count;
      if (local_body_cut_prefix) {
        ++bfu_stats.local_body_cut_prefix_count;
      }
      if (body_cut.valid && bfu_stats.match_count == bfu_match_count_before) {
        std::cerr << "frontend fetch RF ALU BFU body-geometry hint did not produce a static/external match"
                  << " header_pc=0x" << std::hex << body_cut.header_pc
                  << " hsize=0x" << body_cut.hsize_bytes
                  << " bsize=0x" << body_cut.bsize_bytes
                  << std::dec << "\n";
        std::exit(1);
      }
      tick(dut);
      return result;
    }
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU packet source did not emit dense packet"
            << " pc=0x" << std::hex << first.pc << std::dec << "\n";
  std::exit(1);
}

struct DrainDenseRowResult {
  std::uint8_t rob_value = 0;
  bool marker_redirect = false;
};

DrainDenseRowResult drain_dense_row(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const ExpectedRow &row,
    bool &active_block_valid,
    std::uint64_t &active_block_bid,
    const FetchMemoryImage &fetch_memory,
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count,
    const BfuGeometryDiagnostics &bfu_stats,
    bool admit_marker_rows,
    bool local_body_cut_reentry_header = false) {
  for (int cycle = 0; cycle < kDenseRowDrainCycles; ++cycle) {
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error before dense slot drain\n";
      std::exit(1);
    }
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU dense slot drain");
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (dut.io_denseSlotQueueOutFire) {
      if (!dut.io_decodeReady) {
        std::cerr << "frontend fetch RF ALU dense slot drained while decode was not ready"
                  << " pc=0x" << std::hex << row.pc << std::dec << "\n";
        std::exit(1);
      }
      if (row.skip) {
        if (admit_marker_rows) {
          if (row.block_stop && !active_block_valid) {
            std::cerr << "frontend fetch RF ALU admitted marker stop had no active block"
                      << " pc=0x" << std::hex << row.pc
                      << " insn=0x" << row.insn << std::dec
                      << "\n";
            std::exit(1);
          }
          if (!dut.io_selectedValid ||
              dut.io_blockMarkerSkipFire ||
              dut.io_blockMarkerMixedPacket ||
              !dut.io_decRenPushFire ||
              !dut.io_robAllocFire) {
            std::cerr << "frontend fetch RF ALU admitted marker dense slot mismatch"
                      << " pc=0x" << std::hex << row.pc
                      << " insn=0x" << row.insn << std::dec
                      << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
                      << " skipFire=" << static_cast<unsigned>(dut.io_blockMarkerSkipFire)
                      << " mixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
                      << " decRenPush=" << static_cast<unsigned>(dut.io_decRenPushFire)
                      << " robAlloc=" << static_cast<unsigned>(dut.io_robAllocFire)
                      << "\n";
            std::exit(1);
          }
          if (row.block_stop && dut.io_selectedBlockBid != active_block_bid) {
            std::cerr << "frontend fetch RF ALU admitted marker stop used wrong active block BID"
                      << " pc=0x" << std::hex << row.pc
                      << " expected_bid=0x" << active_block_bid
                      << " observed_bid=0x" << dut.io_selectedBlockBid
                      << std::dec << "\n";
            std::exit(1);
          }
          const auto rob_value = static_cast<std::uint8_t>(dut.io_selectedRobValue);
          const auto selected_block_bid = static_cast<std::uint64_t>(dut.io_selectedBlockBid);
          if (trace_top_debug_enabled()) {
            std::cerr << "[trace-top-debug]"
                      << " cycle=" << g_tb_cycle
                      << " reserve_marker pc=0x" << std::hex << row.pc
                      << " insn=0x" << mask_insn(row.insn, row.len)
                      << std::dec
                      << " len=" << static_cast<unsigned>(row.len)
                      << " rob=" << static_cast<unsigned>(rob_value)
                      << " dense_head_slot=" << static_cast<unsigned>(dut.io_denseSlotQueueHeadSlot)
                      << " barrier=" << static_cast<unsigned>(dut.io_admittedMarkerDrainBarrier)
                      << "\n";
          }
          filtered_marker_commits.push_back(row);
          tick(dut);
          if (row.block_boundary) {
            active_block_valid = true;
            active_block_bid = selected_block_bid;
          } else if (row.block_stop) {
            active_block_valid = false;
            active_block_bid = 0;
          }
          DrainDenseRowResult result;
          result.rob_value = rob_value;
          return result;
        }

        const bool expected_alloc = row.block_boundary;
        const bool redirect_boundary = row.block_boundary && row_redirects(row);
        const bool expected_done = active_block_valid && (row.block_boundary || row.block_stop);
        const bool expected_alloc_fire =
            expected_alloc && !redirect_boundary && !local_body_cut_reentry_header;
        if (row.block_stop && !active_block_valid) {
          std::cerr << "frontend fetch RF ALU marker stop had no active block"
                    << " pc=0x" << std::hex << row.pc
                    << " insn=0x" << row.insn << std::dec
                    << "\n";
          std::exit(1);
        }
        if (dut.io_selectedValid ||
            !dut.io_blockMarkerSkipFire ||
            dut.io_blockMarkerMixedPacket ||
            dut.io_blockMarkerPc != row.pc ||
            mask_insn(dut.io_blockMarkerInsn, dut.io_blockMarkerLen) != mask_insn(row.insn, row.len) ||
            dut.io_blockMarkerLen != row.len ||
            static_cast<bool>(dut.io_blockMarkerBoundary) != row.block_boundary ||
            static_cast<bool>(dut.io_blockMarkerStop) != row.block_stop ||
            static_cast<bool>(dut.io_blockMarkerAllocFire) != expected_alloc_fire ||
            static_cast<bool>(dut.io_blockScalarDoneFire) != expected_done ||
            dut.io_decRenPushFire ||
            dut.io_robAllocFire) {
          std::cerr << "frontend fetch RF ALU marker dense slot mismatch"
                    << " pc=0x" << std::hex << row.pc
                    << " insn=0x" << row.insn << std::dec
                    << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
                    << " skipFire=" << static_cast<unsigned>(dut.io_blockMarkerSkipFire)
                    << " mixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
                    << " boundary=" << static_cast<unsigned>(dut.io_blockMarkerBoundary)
                    << " stop=" << static_cast<unsigned>(dut.io_blockMarkerStop)
                    << " allocFire=" << static_cast<unsigned>(dut.io_blockMarkerAllocFire)
                    << " activeValid=" << static_cast<unsigned>(dut.io_blockMarkerActiveValid)
                    << " activeBid=0x" << std::hex << dut.io_blockMarkerActiveBid
                    << " activeTarget=0x" << dut.io_blockMarkerActiveTarget
                    << " markerTarget=0x" << dut.io_blockMarkerTarget
                    << std::dec
                    << " scalarDone=" << static_cast<unsigned>(dut.io_blockScalarDoneFire)
                    << " decRenPush=" << static_cast<unsigned>(dut.io_decRenPushFire)
                    << " robAlloc=" << static_cast<unsigned>(dut.io_robAllocFire)
                    << "\n";
          std::exit(1);
        }
        if (expected_done && dut.io_blockScalarDoneBid != active_block_bid) {
          std::cerr << "frontend fetch RF ALU marker scalar-done BID mismatch"
                    << " pc=0x" << std::hex << row.pc
                    << " expected_bid=0x" << active_block_bid
                    << " observed_bid=0x" << dut.io_blockScalarDoneBid
                    << std::dec << "\n";
          std::exit(1);
        }
        DrainDenseRowResult result;
        result.marker_redirect = dut.io_blockMarkerStopRedirectValid;
        const std::uint64_t allocated_bid = dut.io_blockMarkerAllocBid;
        tick(dut);
        if (redirect_boundary || local_body_cut_reentry_header) {
          active_block_valid = false;
          active_block_bid = 0;
        } else if (row.block_boundary) {
          active_block_valid = true;
          active_block_bid = allocated_bid;
        } else if (row.block_stop) {
          active_block_valid = false;
          active_block_bid = 0;
        }
        return result;
      }

      if (!dut.io_selectedValid ||
          dut.io_blockMarkerMixedPacket ||
          dut.io_blockMarkerSkipFire ||
          !dut.io_decRenPushFire ||
          !dut.io_robAllocFire) {
        std::cerr << "frontend fetch RF ALU scalar dense slot mismatch"
                  << " pc=0x" << std::hex << row.pc << std::dec
                  << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
                  << " mixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
                  << " skipFire=" << static_cast<unsigned>(dut.io_blockMarkerSkipFire)
                  << " decRenPush=" << static_cast<unsigned>(dut.io_decRenPushFire)
                  << " robAlloc=" << static_cast<unsigned>(dut.io_robAllocFire)
                  << "\n";
        std::exit(1);
      }
      if (active_block_valid && dut.io_selectedBlockBid != active_block_bid) {
        active_block_bid = dut.io_selectedBlockBid;
      }
      const auto rob_value = static_cast<std::uint8_t>(dut.io_selectedRobValue);
      const auto selected_block_bid = static_cast<std::uint64_t>(dut.io_selectedBlockBid);
      if (trace_top_debug_enabled()) {
        std::cerr << "[trace-top-debug]"
                  << " cycle=" << g_tb_cycle
                  << " reserve_scalar pc=0x" << std::hex << row.pc
                  << " insn=0x" << mask_insn(row.insn, row.len)
                  << std::dec
                  << " len=" << static_cast<unsigned>(row.len)
                  << " rob=" << static_cast<unsigned>(rob_value)
                  << " dense_head_slot=" << static_cast<unsigned>(dut.io_denseSlotQueueHeadSlot)
                  << " barrier=" << static_cast<unsigned>(dut.io_admittedMarkerDrainBarrier)
                  << "\n";
      }
      tick(dut);
      if (row_redirects(row)) {
        active_block_valid = false;
        active_block_bid = 0;
      } else if (!active_block_valid) {
        active_block_valid = true;
        active_block_bid = selected_block_bid;
      }
      DrainDenseRowResult result;
      result.rob_value = rob_value;
      return result;
    }
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU dense slot queue did not drain row"
            << " pc=0x" << std::hex << row.pc << std::dec
            << " queueEmpty=" << static_cast<unsigned>(dut.io_denseSlotQueueEmpty)
            << " queueCount=" << static_cast<unsigned>(dut.io_denseSlotQueueCount)
            << " headSlot=" << static_cast<unsigned>(dut.io_denseSlotQueueHeadSlot)
            << " decodeReady=" << static_cast<unsigned>(dut.io_decodeReady)
            << " admittedBarrier=" << static_cast<unsigned>(dut.io_admittedMarkerDrainBarrier)
            << " decodeQueueReady=" << static_cast<unsigned>(dut.io_decodeQueuePushReady)
            << " decodeAllocReady=" << static_cast<unsigned>(dut.io_decodeAllocReady)
            << " decodeGprReady=" << static_cast<unsigned>(dut.io_decodeGprReservationReady)
            << " decodeClosesRedirect=" << static_cast<unsigned>(dut.io_decodeSelectedClosesActiveRedirect)
            << " decodeNeedsGpr=" << static_cast<unsigned>(dut.io_decodeSelectedNeedsGprReservation)
            << " gprReservationCount=" << static_cast<unsigned>(dut.io_gprReservationCount)
            << " gprReservationNeed=" << static_cast<unsigned>(dut.io_gprReservationNeed)
            << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
            << " decRenCount=" << static_cast<unsigned>(dut.io_decRenCount)
            << " decRenValid=" << static_cast<unsigned>(dut.io_decRenValid)
            << " decRenHeadPc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_decRenHeadPc)
            << std::dec
            << " decodeBlockedByRename=" << static_cast<unsigned>(dut.io_decodeBlockedByRename)
            << " decodeBlockedByRob=" << static_cast<unsigned>(dut.io_decodeBlockedByRob)
            << " decodeBlockedByOutput=" << static_cast<unsigned>(dut.io_decodeBlockedByOutput)
            << " decodeBlockedByTURename=" << static_cast<unsigned>(dut.io_decodeBlockedByTURename)
            << " gprFree=" << static_cast<unsigned>(dut.io_gprFreeCount)
            << " gprMapQValid=" << static_cast<unsigned>(dut.io_gprMapQValidCount)
            << " gprMapQFree=" << static_cast<unsigned>(dut.io_gprMapQFreeCount)
            << " gprSmapLive=" << static_cast<unsigned>(dut.io_gprSmapLiveCount)
            << " gprCmapLive=" << static_cast<unsigned>(dut.io_gprCmapLiveCount)
            << " gprMapQLive=" << static_cast<unsigned>(dut.io_gprMapQLiveCount)
            << " gprLivePhys=" << static_cast<unsigned>(dut.io_gprLivePhysCount)
            << " gprFreeFromLive=" << static_cast<unsigned>(dut.io_gprFreeFromLiveCount)
            << " gprFreeMismatch=" << static_cast<unsigned>(dut.io_gprFreeListMismatchCount)
            << " gprNextMapQValid=" << static_cast<unsigned>(dut.io_gprNextMapQValidCount)
            << " gprNextMapQLive=" << static_cast<unsigned>(dut.io_gprNextMapQLiveCount)
            << " gprNextLivePhys=" << static_cast<unsigned>(dut.io_gprNextLivePhysCount)
            << " gprNextFreeFromLive=" << static_cast<unsigned>(dut.io_gprNextFreeFromLiveCount)
            << " markerSkipValid=" << static_cast<unsigned>(dut.io_blockMarkerSkipValid)
            << " markerMixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
            << " markerBoundary=" << static_cast<unsigned>(dut.io_blockMarkerBoundary)
            << " markerStop=" << static_cast<unsigned>(dut.io_blockMarkerStop)
            << " markerAllocReady=" << static_cast<unsigned>(dut.io_blockMarkerAllocReady)
            << " markerAllocBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockMarkerAllocBid)
            << std::dec
            << " markerLifecycleConflict=" << static_cast<unsigned>(dut.io_blockMarkerLifecycleConflict)
            << " markerActiveValid=" << static_cast<unsigned>(dut.io_blockMarkerActiveValid)
            << " markerActiveBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockMarkerActiveBid)
            << " markerActiveTarget=0x"
            << static_cast<unsigned long long>(dut.io_blockMarkerActiveTarget)
            << std::dec
            << " blockScalarDoneFire=" << static_cast<unsigned>(dut.io_blockScalarDoneFire)
            << " blockScalarDoneBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockScalarDoneBid)
            << std::dec
            << " blockRetireFire=" << static_cast<unsigned>(dut.io_blockRetireFire)
            << " robDeallocBlockLastValid=" << static_cast<unsigned>(dut.io_robDeallocBlockLastValid)
            << " robDeallocBlockLastBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_robDeallocBlockLastBlockBid)
            << std::dec
            << " gprCommitAccepted=" << static_cast<unsigned>(dut.io_gprCommitAccepted)
            << " gprCommitBlockBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_gprCommitBlockBid)
            << std::dec
            << " gprCommittedMapQCount=" << static_cast<unsigned>(dut.io_gprCommittedMapQCount)
            << " gprReleasedPhysCount=" << static_cast<unsigned>(dut.io_gprReleasedPhysCount)
            << " histDeallocBlockLast=" << g_gpr_commit_history.dealloc_block_last_count
            << " histScalarDone=" << g_gpr_commit_history.block_scalar_done_count
            << " histBlockRetire=" << g_gpr_commit_history.block_retire_count
            << " histGprCommit=" << g_gpr_commit_history.gpr_commit_count
            << " histGprCommitHits=" << g_gpr_commit_history.gpr_commit_hit_total
            << " histGprReleases=" << g_gpr_commit_history.gpr_release_total
            << " lastBlockRetireBid=0x" << std::hex
            << g_gpr_commit_history.last_block_retire_bid
            << " lastGprCommitBid=0x" << g_gpr_commit_history.last_gpr_commit_bid
            << std::dec
            << " lastGprCommitHits=" << static_cast<unsigned>(g_gpr_commit_history.last_gpr_commit_hits)
            << " lastGprReleases=" << static_cast<unsigned>(g_gpr_commit_history.last_gpr_releases)
            << " lastNonzeroGprCommitBid=0x" << std::hex
            << g_gpr_commit_history.last_nonzero_gpr_commit_bid
            << std::dec
            << " lastNonzeroGprCommitHits="
            << static_cast<unsigned>(g_gpr_commit_history.last_nonzero_gpr_commit_hits)
            << " lastNonzeroGprReleases="
            << static_cast<unsigned>(g_gpr_commit_history.last_nonzero_gpr_releases)
            << " bfuStaticComparable=" << bfu_stats.comparable_count
            << " bfuStaticMatches=" << bfu_stats.match_count
            << " bfuResolvedAccepts=" << bfu_stats.resolved_accept_count
            << " bfuCutArmComparable=" << bfu_stats.cut_arm_comparable_count
            << " bfuCutArmAccepts=" << bfu_stats.cut_arm_accept_count
            << " bfuCutArmMismatches=" << bfu_stats.cut_arm_mismatch_count
            << " bfuLocalBodyCutPrefixes=" << bfu_stats.local_body_cut_prefix_count
            << " bfuRuntimeSelected=" << bfu_stats.resolved_source_runtime_selected_count
            << " bfuReplaySelected=" << bfu_stats.resolved_source_replay_selected_count
            << " bfuRuntimeFeedback=" << bfu_stats.resolved_source_runtime_feedback_count
            << " bfuRuntimePending=" << bfu_stats.resolved_source_runtime_pending_count
            << " bfuRuntimePendingConsumes=" << bfu_stats.resolved_source_runtime_pending_consume_count
            << " bfuRuntimePendingCandidateComparable="
            << bfu_stats.resolved_source_runtime_pending_candidate_comparable_count
            << " bfuRuntimePendingCandidateMatch="
            << bfu_stats.resolved_source_runtime_pending_candidate_match_count
            << " bfuRuntimePendingCandidateMismatch="
            << bfu_stats.resolved_source_runtime_pending_candidate_mismatch_count
            << " bfuPendingRuntimeCandidateValid=" << bfu_stats.pending_runtime_candidate_valid_count
            << " bfuPendingRuntimeCandidateHeaderMismatch="
            << bfu_stats.pending_runtime_candidate_active_header_mismatch_count
            << " bfuPromotedRuntimeCaptures="
            << bfu_stats.promoted_runtime_body_end_oracle_capture_count
            << " bfuPromotedRuntimeReplayComparable="
            << bfu_stats.promoted_runtime_body_end_oracle_replay_comparable_count
            << " bfuPromotedRuntimeReplayMatch="
            << bfu_stats.promoted_runtime_body_end_oracle_replay_match_count
            << " bfuRuntimeReplayComparable="
            << bfu_stats.resolved_source_runtime_replay_comparable_count
            << " bfuRuntimeReplayMatch="
            << bfu_stats.resolved_source_runtime_replay_match_count
            << " bfuRuntimeReplayMismatch="
            << bfu_stats.resolved_source_runtime_replay_mismatch_count
            << " issueCount=" << static_cast<unsigned>(dut.io_issueQueueCount)
            << " executeBusy=" << static_cast<unsigned>(dut.io_executeBusy)
            << " occupiedMask=0x" << std::hex
            << hex_port(dut.io_occupiedMask)
            << " completedMask=0x"
            << hex_port(dut.io_completedMask)
            << " blockAllocatedMask=0x"
            << hex_port(dut.io_blockAllocatedMask)
            << " blockCompleteMask=0x"
            << hex_port(dut.io_blockCompleteMask)
            << " blockPendingMask=0x"
            << hex_port(dut.io_blockPendingMask)
            << std::dec << "\n";
  std::exit(1);
}

void drain_empty(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemoryImage &fetch_memory) {
  for (int cycle = 0; cycle < 16; ++cycle) {
    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_idle && dut.io_empty && dut.io_size == 0 && !dut.io_executeBusy) {
      return;
    }
    tick(dut);
  }
  std::cerr << "frontend fetch RF ALU trace top did not drain after commit"
            << " idle=" << static_cast<unsigned>(dut.io_idle)
            << " size=" << static_cast<unsigned>(dut.io_size)
            << " outstanding=" << static_cast<unsigned>(dut.io_outstandingCount)
            << " executeBusy=" << static_cast<unsigned>(dut.io_executeBusy)
            << " sourceWaiting=" << static_cast<unsigned>(dut.io_sourceWaitingResponse)
            << " sourcePacket=" << static_cast<unsigned>(dut.io_sourcePacketValid)
            << " storeCommitPendingMarkMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedStoreCommitPendingMarkMask)
            << " storeCommitPendingFreeMask=0x"
            << static_cast<unsigned>(dut.io_reducedStoreCommitPendingFreeMask)
            << " storeCommitMarkValid=" << std::dec
            << static_cast<unsigned>(dut.io_reducedStoreCommitMarkValid)
            << " storeCommitMarkAccepted="
            << static_cast<unsigned>(dut.io_reducedStoreCommitMarkAccepted)
            << " storeCommitMarkBlocked="
            << static_cast<unsigned>(dut.io_reducedStoreCommitMarkBlocked)
            << " storeCommitPendingMarkCount="
            << static_cast<unsigned>(dut.io_reducedStoreCommitPendingMarkCount)
            << " storeDrainEmpty="
            << static_cast<unsigned>(dut.io_reducedStoreDrainEmpty)
            << " storeDrainQueueCount="
            << static_cast<unsigned>(dut.io_reducedStoreDrainQueueCount)
            << " storeDrainIssueMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedStoreDrainIssueValidMask)
            << " storeStqCommitMask=0x"
            << hex_port(dut.io_storeStqCommitMask)
            << " storeStqOccupiedMask=0x"
            << hex_port(dut.io_storeStqOccupiedMask)
            << " replayLiqEmpty=" << std::dec
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqEmpty)
            << " replayLiqResidentCount="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqResidentCount)
            << " replayResolveQueueEmpty="
            << static_cast<unsigned>(dut.io_reducedLoadReplayResolveQueueEmpty)
            << " replayResolveQueueCount="
            << static_cast<unsigned>(dut.io_reducedLoadReplayResolveQueueCount)
            << " replayResolveQueueValidMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedLoadReplayResolveQueueValidMask)
            << " replayLiqHeadValid=" << std::dec
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadValid)
            << " replayLiqHeadIndex="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadIndex)
            << " replayLiqHeadStatus="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadStatus)
            << " replayLiqHeadLoadId="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadLoadIdValid)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadLoadIdWrap)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadLoadIdValue)
            << " replayLiqHeadBid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadBidValid)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadBidWrap)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadBidValue)
            << " replayLiqHeadGid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadGidValid)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadGidWrap)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadGidValue)
            << " replayLiqHeadRid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadRidValid)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadRidWrap)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadRidValue)
            << " replayLiqHeadLoadLsId="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadLoadLsIdValid)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadLoadLsIdWrap)
            << "/" << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadLoadLsIdValue)
            << " replayLiqHeadPc=0x" << std::hex
            << hex_port(dut.io_reducedLoadReplayLiqHeadPc)
            << " replayLiqHeadAddr=0x"
            << hex_port(dut.io_reducedLoadReplayLiqHeadAddr)
            << " replayLiqHeadSize=" << std::dec
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadSize)
            << " replayLiqHeadWaitStore="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadWaitStore)
            << " replayLiqHeadStoreBypass="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadStoreBypass)
            << " replayLiqHeadDataComplete="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadDataComplete)
            << " replayLiqHeadSourcesReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadSourcesReturned)
            << " replayLiqHeadScbReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadScbReturned)
            << " replayLiqHeadStqReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqHeadStqReturned)
            << " replayLiqLaunchWaitMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchWaitMask)
            << " replayLiqLaunchUnblockedWaitMask=0x"
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchUnblockedWaitMask)
            << " replayLiqLaunchRequestCompleteMask=0x"
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchRequestCompleteMask)
            << " replayLiqLaunchDataHitMask=0x"
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchDataHitMask)
            << " replayLiqLaunchCandidateMask=0x"
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchCandidateMask)
            << " replayLiqLaunchMask=0x"
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchMask)
            << " replayLiqLaunchValid=" << std::dec
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchValid)
            << " replayLiqLaunchIndex="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchIndex)
            << " replayLiqLaunchDriveValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchDriveValid)
            << " replayLiqLaunchReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReady)
            << " replayLiqLaunchAccepted="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchAccepted)
            << " replayLiqLaunchCandidateCount="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchCandidateCount)
            << " replayLiqLaunchReadinessCandidateValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessCandidateValid)
            << " replayLiqLaunchReadinessBaseDataReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBaseDataReady)
            << " replayLiqLaunchReadinessSourcesReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessSourcesReturned)
            << " replayLiqLaunchReadinessReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessReady)
            << " replayLiqLaunchReadinessEnable="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessEnable)
            << " replayLiqLaunchReadinessBlockedByDisabled="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByDisabled)
            << " replayLiqLaunchReadinessBlockedByNoCandidate="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByNoCandidate)
            << " replayLiqLaunchReadinessBlockedByBaseLookup="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByBaseLookup)
            << " replayLiqLaunchReadinessBlockedByBaseData="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByBaseData)
            << " replayLiqLaunchReadinessBlockedByScb="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByScb)
            << " replayLiqLaunchReadinessBlockedByReturn="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqLaunchReadinessBlockedByReturn)
            << " replayLiqBaseLookupValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqBaseLookupValid)
            << " replayLiqBaseLookupGranted="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqBaseLookupGranted)
            << " replayLiqBaseDataReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqBaseDataReturned)
            << " replayLiqBaseLineValidMask=0x" << std::hex
            << hex_port(dut.io_reducedLoadReplayLiqBaseLineValidMask)
            << " replayLiqBaseLookupBlockedByExecute=" << std::dec
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqBaseLookupBlockedByExecute)
            << " replayLiqSourceReturnCandidateValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnCandidateValid)
            << " replayLiqSourceReturnStoreSnapshotReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotReady)
            << " replayLiqSourceReturnStoreSnapshotLegacyReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLegacyReady)
            << " replayLiqSourceReturnStoreSnapshotLiveReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveReady)
            << " replayLiqSourceReturnStoreSnapshotBlockedByLegacySnapshot="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotBlockedByLegacySnapshot)
            << " replayLiqSourceReturnStoreSnapshotBlockedBySnapshot="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotBlockedBySnapshot)
            << " replayLiqSourceReturnStoreSnapshotPolicyRequestCandidate="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyRequestCandidate)
            << " replayLiqSourceReturnStoreSnapshotPolicyRequestEnable="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyRequestEnable)
            << " replayLiqSourceReturnStoreSnapshotPolicySinkReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicySinkReady)
            << " replayLiqSourceReturnStoreSnapshotPolicyReqBlockedNoLaunch="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyRequestBlockedByNoLaunch)
            << " replayLiqSourceReturnStoreSnapshotPolicyReqBlockedRowMutation="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyRequestBlockedByRowMutationDisabled)
            << " replayLiqSourceReturnStoreSnapshotPolicyReqBlockedRequestQueue="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyRequestBlockedByRequestQueue)
            << " replayLiqSourceReturnStoreSnapshotPolicyReqBlockedAcceptedToken="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyRequestBlockedByAcceptedToken)
            << " replayLiqSourceReturnStoreSnapshotPolicySinkBlockedNoRequest="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicySinkBlockedByNoRequest)
            << " replayLiqSourceReturnStoreSnapshotPolicySinkBlockedRawSink="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicySinkBlockedByRawSink)
            << " replayLiqSourceReturnStoreSnapshotPolicyResponseBlockedQueueFull="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLiveArmPolicyResponseBlockedByQueueFull)
            << " replayLiqSourceReturnStoreSnapshotEffectiveRequestEnable="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEffectiveRequestEnable)
            << " replayLiqSourceReturnStoreSnapshotEffectiveRequestQueueCanAccept="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEffectiveRequestQueueCanAccept)
            << " replayLiqSourceReturnStoreSnapshotEffectiveSinkReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEffectiveSinkReady)
            << " replayLiqSourceReturnStoreSnapshotRequestControlBlockedByToken="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestControlBlockedByToken)
            << " replayLiqSourceReturnStoreSnapshotAcceptedTokenCanAccept="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotAcceptedTokenCanAccept)
            << " replayLiqSourceReturnStoreSnapshotAcceptedTokenValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotAcceptedTokenValid)
            << " replayLiqSourceReturnStoreSnapshotAcceptedTokenResidentValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotAcceptedTokenResidentValid)
            << " replayLiqSourceReturnStoreSnapshotAcceptedTokenCaptureCandidate="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotAcceptedTokenCaptureCandidate)
            << " replayLiqSourceReturnStoreSnapshotAcceptedTokenCaptureAccepted="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotAcceptedTokenCaptureAccepted)
            << " replayLiqSourceReturnStoreSnapshotAcceptedTokenBlockedByOutstanding="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotAcceptedTokenBlockedByOutstanding)
            << " replayLiqSourceReturnStoreSnapshotQueryIssueCandidate="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueCandidate)
            << " replayLiqSourceReturnStoreSnapshotQueryIssueValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueValid)
            << " replayLiqSourceReturnStoreSnapshotQueryIssueIssued="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueIssued)
            << " replayLiqSourceReturnStoreSnapshotQueryIssueBlockedByRequestDisabled="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueBlockedByRequestDisabled)
            << " replayLiqSourceReturnStoreSnapshotQueryIssueBlockedByNoLaunch="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueBlockedByNoLaunch)
            << " replayLiqSourceReturnStoreSnapshotQueryIssueBlockedBySink="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotQueryIssueBlockedBySink)
            << " replayLiqSourceReturnStoreSnapshotRequestPayloadValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestPayloadValid)
            << " replayLiqSourceReturnStoreSnapshotRequestPayloadBlockedByNoIssue="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestPayloadBlockedByNoIssue)
            << " replayLiqSourceReturnStoreSnapshotRequestPayloadBlockedByNoSelected="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestPayloadBlockedByNoSelected)
            << " replayLiqSourceReturnStoreSnapshotRequestPayloadBlockedByStaleRow="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestPayloadBlockedByStaleRow)
            << " replayLiqSourceReturnStoreSnapshotRequestQueueHeadValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueueHeadValid)
            << " replayLiqSourceReturnStoreSnapshotRequestQueueHeadConsumed="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueueHeadConsumed)
            << " replayLiqSourceReturnStoreSnapshotRequestQueuePending="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueuePending)
            << " replayLiqSourceReturnStoreSnapshotRequestQueueFull="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueueFull)
            << " replayLiqSourceReturnStoreSnapshotRequestQueueEmpty="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueueEmpty)
            << " replayLiqSourceReturnStoreSnapshotRequestQueueCount="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueueCount)
            << " replayLiqSourceReturnStoreSnapshotRequestQueueBlockedByFull="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestQueueBlockedByFull)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkCandidate="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkCandidate)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkReady)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkAccepted="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkAccepted)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkResponseValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkResponseValid)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkBlockedByNoRequest="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkBlockedByNoRequest)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkBlockedByRawSink="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkBlockedByRawSink)
            << " replayLiqSourceReturnStoreSnapshotRequestSinkBlockedByResponse="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRequestSinkBlockedByResponse)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueEnqueueAccepted="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueEnqueueAccepted)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueHeadValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueHeadValid)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueHeadConsumed="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueHeadConsumed)
            << " replayLiqSourceReturnStoreSnapshotResponseQueuePending="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueuePending)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueFull="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueFull)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueEmpty="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueEmpty)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueCount="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueCount)
            << " replayLiqSourceReturnStoreSnapshotResponseQueueBlockedByFull="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseQueueBlockedByFull)
            << " replayLiqSourceReturnStoreSnapshotResponseDrainDequeueReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseDrainDequeueReady)
            << " replayLiqSourceReturnStoreSnapshotResponseDrainOrderedConsumed="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseDrainOrderedConsumed)
            << " replayLiqSourceReturnStoreSnapshotResponseDrainStaleDropped="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseDrainStaleDropped)
            << " replayLiqSourceReturnStoreSnapshotResponseDrainBlockedByNoHead="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseDrainBlockedByNoHead)
            << " replayLiqSourceReturnStoreSnapshotResponseDrainBlockedByNoAction="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseDrainBlockedByNoAction)
            << " replayLiqSourceReturnStoreSnapshotLookupQueryValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLookupQueryValid)
            << " replayLiqSourceReturnStoreSnapshotLookupWaitStoreValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLookupWaitStoreValid)
            << " replayLiqSourceReturnStoreSnapshotLookupRawDataValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLookupRawDataValid)
            << " replayLiqSourceReturnStoreSnapshotLookupResponseDataValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLookupResponseDataValid)
            << " replayLiqSourceReturnStoreSnapshotLookupStoreBypassComplete="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotLookupStoreBypassComplete)
            << " replayLiqSourceReturnStoreSnapshotEvidenceRequestValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceRequestValid)
            << " replayLiqSourceReturnStoreSnapshotEvidenceQueryActive="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceQueryActive)
            << " replayLiqSourceReturnStoreSnapshotEvidenceResponseAccepted="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceResponseAccepted)
            << " replayLiqSourceReturnStoreSnapshotEvidenceSnapshotRequired="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceSnapshotRequired)
            << " replayLiqSourceReturnStoreSnapshotEvidenceSnapshotValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceSnapshotValid)
            << " replayLiqSourceReturnStoreSnapshotEvidenceWaitStoreReplay="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceWaitStoreReplay)
            << " replayLiqSourceReturnStoreSnapshotEvidenceBlockedByNoQuery="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceBlockedByNoQuery)
            << " replayLiqSourceReturnStoreSnapshotEvidenceBlockedByNoResponse="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceBlockedByNoResponse)
            << " replayLiqSourceReturnStoreSnapshotEvidenceBlockedByWaitStore="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotEvidenceBlockedByWaitStore)
            << " replayLiqSourceReturnStoreSnapshotResponseApplyCandidate="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyCandidate)
            << " replayLiqSourceReturnStoreSnapshotResponseApplyValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyValid)
            << " replayLiqSourceReturnStoreSnapshotResponseApplyBlockedByNoOrderedResponse="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyBlockedByNoOrderedResponse)
            << " replayLiqSourceReturnStoreSnapshotResponseApplyBlockedByNotRepick="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyBlockedByNotRepick)
            << " replayLiqSourceReturnStoreSnapshotResponseApplyBlockedByWaitStore="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyBlockedByWaitStore)
            << " replayLiqSourceReturnStoreSnapshotResponseApplyBlockedByNoData="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotResponseApplyBlockedByNoData)
            << " replayLiqSourceReturnStoreSnapshotRowStatePlanValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowStatePlanValid)
            << " replayLiqSourceReturnStoreSnapshotRowStatePlanBlockedByNoApply="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowStatePlanBlockedByNoApply)
            << " replayLiqSourceReturnStoreSnapshotRowMutationCandidateValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationCandidateValid)
            << " replayLiqSourceReturnStoreSnapshotRowMutationTargetReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationTargetReady)
            << " replayLiqSourceReturnStoreSnapshotRowMutationRequestValid="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationRequestValid)
            << " replayLiqSourceReturnStoreSnapshotRowMutationHeadProofReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationHeadProofReady)
            << " replayLiqSourceReturnStoreSnapshotRowMutationLivePermit="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationLivePermit)
            << " replayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadProof="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadProof)
            << " replayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadInvalidRow="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadInvalidRow)
            << " replayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadScbNotReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadScbNotReturned)
            << " replayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadNotRepick="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadNotRepick)
            << " replayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadTargetMismatch="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSnapshotRowMutationBlockedByHeadTargetMismatch)
            << " replayLiqSourceReturnStoreSourceReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnStoreSourceReturned)
            << " replayLiqSourceReturnScbSourceReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnScbSourceReturned)
            << " replayLiqSourceReturnSourceReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnSourceReturned)
            << " replayLiqSourceReturnBlockedByBaseData="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnBlockedByBaseData)
            << " replayLiqSourceReturnBlockedByStoreSnapshot="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnBlockedByStoreSnapshot)
            << " replayLiqSourceReturnBlockedByScb="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnBlockedByScb)
            << " replayLiqSourceReturnScbLivePending="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnScbLivePending)
            << " replayLiqSourceReturnScbLiveReturned="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqSourceReturnScbLiveReturned)
            << " replayLiqReturnConsumerReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnConsumerReady)
            << " replayLiqReturnConsumerBlockedBySources="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnConsumerBlockedBySources)
            << " replayLiqReturnConsumerBlockedByLretSink="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnConsumerBlockedByLretSink)
            << " replayLiqReturnPipeBudgetAvailable="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnPipeBudgetAvailable)
            << " replayLiqReturnPipeBudgetBlockedByConsumer="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnPipeBudgetBlockedByConsumer)
            << " replayLiqReturnPipeAvailable="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnPipeAvailable)
            << " replayLiqReturnPipeBlockedByNoPipe="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnPipeBlockedByNoPipe)
            << " replayLiqReturnReadinessReady="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnReadinessReady)
            << " replayLiqReturnReadinessBlockedBySources="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnReadinessBlockedBySources)
            << " replayLiqReturnReadinessBlockedByReturnPipe="
            << static_cast<unsigned>(dut.io_reducedLoadReplayLiqReturnReadinessBlockedByReturnPipe)
            << std::dec
            << "\n";
  if (!g_replay_liq_sideband_stats_path.empty()) {
    write_replay_liq_sideband_stats(g_replay_liq_sideband_stats_path);
  }
  std::exit(1);
}

void filter_pending_marker_commits(
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count) {
  while (!pending_commits.empty() && !filtered_marker_commits.empty()) {
    bool filtered = false;
    for (auto it = pending_commits.begin(); it != pending_commits.end(); ++it) {
      if (!is_observed_marker_commit(*it)) {
        continue;
      }
      expect_marker_commit(*it, filtered_marker_commits.front());
      pending_commits.erase(it);
      filtered_marker_commits.erase(filtered_marker_commits.begin());
      ++marker_commit_filter_count;
      filtered = true;
      break;
    }
    if (!filtered) {
      return;
    }
  }
}

void wait_for_admitted_marker_redirect(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const ExpectedRow &expected,
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count,
    FetchMemoryImage &fetch_memory) {
  bool saw_lifecycle_ready = false;
  bool saw_lifecycle_fire = false;
  bool saw_lifecycle_boundary_fire = false;
  bool saw_lifecycle_stop_fire = false;
  bool saw_retire_source_valid = false;
  bool saw_retire_source_boundary = false;
  bool saw_retire_source_stop = false;
  bool saw_dealloc_block_last = false;
  bool saw_redirect_valid = false;
  std::uint64_t last_retire_source_block_bid = 0;
  std::uint64_t last_retire_source_boundary_target = 0;
  std::uint64_t last_dealloc_block_last_bid = 0;
  std::uint64_t last_redirect_pc = 0;
  for (int cycle = 0; cycle < 64; ++cycle) {
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (!pending_commits.empty()) {
      std::cerr << "frontend fetch RF ALU saw scalar commit while waiting for admitted marker redirect"
                << " marker_pc=0x" << std::hex << expected.pc
                << " observed_pc=0x" << pending_commits.front().pc << std::dec
                << " admittedMarkerBarrier=" << static_cast<unsigned>(dut.io_admittedMarkerDrainBarrier)
                << "\n";
      std::exit(1);
    }

    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    saw_lifecycle_ready |= dut.io_robMarkerRetireSourceLifecycleReady;
    saw_lifecycle_fire |= dut.io_robMarkerRetireSourceLifecycleFire;
    saw_lifecycle_boundary_fire |= dut.io_robMarkerRetireSourceLifecycleBoundaryFire;
    saw_lifecycle_stop_fire |= dut.io_robMarkerRetireSourceLifecycleStopFire;
    saw_retire_source_valid |= dut.io_robMarkerRetireSourceValid;
    saw_retire_source_boundary |= dut.io_robMarkerRetireSourceBoundary;
    saw_retire_source_stop |= dut.io_robMarkerRetireSourceStop;
    saw_dealloc_block_last |= dut.io_robDeallocBlockLastValid;
    saw_redirect_valid |= dut.io_blockMarkerStopRedirectValid;
    if (dut.io_robMarkerRetireSourceValid) {
      last_retire_source_block_bid = dut.io_robMarkerRetireSourceBlockBid;
      last_retire_source_boundary_target = dut.io_robMarkerRetireSourceBoundaryTarget;
    }
    if (dut.io_robDeallocBlockLastValid) {
      last_dealloc_block_last_bid = dut.io_robDeallocBlockLastBlockBid;
    }
    if (dut.io_blockMarkerStopRedirectValid) {
      last_redirect_pc = dut.io_blockMarkerStopRedirectPc;
    }
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error while waiting for marker redirect\n";
      std::exit(1);
    }
    if (dut.io_executeUnsupported) {
      std::cerr << "execute reported unsupported opcode while waiting for marker redirect opcode="
                << static_cast<unsigned>(dut.io_executeUnsupportedOpcode) << "\n";
      std::exit(1);
    }
    if (dut.io_executeCompleteValid && dut.io_completeIgnored) {
      std::cerr << "execute completion was ignored while waiting for marker redirect"
                << " rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
                << "\n";
      std::exit(1);
    }

    const bool observed_commit_window = dut.io_commit_rows_0_valid;
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU marker redirect wait");
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (!pending_commits.empty()) {
      std::cerr << "frontend fetch RF ALU saw scalar commit after marker filtering while waiting for redirect"
                << " marker_pc=0x" << std::hex << expected.pc
                << " observed_pc=0x" << pending_commits.front().pc << std::dec
                << " admittedMarkerBarrier=" << static_cast<unsigned>(dut.io_admittedMarkerDrainBarrier)
                << "\n";
      std::exit(1);
    }

    if (dut.io_blockMarkerStopRedirectValid) {
      if (dut.io_blockMarkerStopRedirectPc != expected.next_pc) {
        std::cerr << "frontend fetch RF ALU admitted marker redirect mismatch"
                  << " marker_pc=0x" << std::hex << expected.pc
                  << " expected_pc=0x" << expected.next_pc
                  << " observed_pc=0x" << dut.io_blockMarkerStopRedirectPc
                  << std::dec << "\n";
        std::exit(1);
      }
      tick(dut);
      return;
    }
    if (observed_commit_window) {
      tick(dut);
      continue;
    }
    expect_monitor_clean(dut, "frontend fetch RF ALU marker redirect wait", 0x0, 0);
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU admitted marker did not redirect"
            << " marker_pc=0x" << std::hex << expected.pc
            << " expected_pc=0x" << expected.next_pc << std::dec
            << " pending_markers=" << filtered_marker_commits.size()
            << " pending_commits=" << pending_commits.size()
            << " lifecycle_ready=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceLifecycleReady)
            << " lifecycle_fire=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceLifecycleFire)
            << " lifecycle_boundary_fire=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceLifecycleBoundaryFire)
            << " lifecycle_stop_fire=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceLifecycleStopFire)
            << " retire_source_valid=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceValid)
            << " retire_source_boundary=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceBoundary)
            << " retire_source_stop=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceStop)
            << " retire_source_last=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceLast)
            << " retire_source_block_bid_valid=" << static_cast<unsigned>(dut.io_robMarkerRetireSourceBlockBidValid)
            << " retire_source_block_bid=0x" << std::hex << dut.io_robMarkerRetireSourceBlockBid
            << " retire_source_boundary_target=0x" << dut.io_robMarkerRetireSourceBoundaryTarget
            << " dealloc_block_last_valid=" << std::dec << static_cast<unsigned>(dut.io_robDeallocBlockLastValid)
            << " dealloc_block_last_block_bid=0x" << std::hex << dut.io_robDeallocBlockLastBlockBid
            << " active_valid=" << std::dec << static_cast<unsigned>(dut.io_blockMarkerActiveValid)
            << " active_bid=0x" << std::hex << dut.io_blockMarkerActiveBid
            << " active_target=0x" << dut.io_blockMarkerActiveTarget
            << " saw_lifecycle_ready=" << std::dec << static_cast<unsigned>(saw_lifecycle_ready)
            << " saw_lifecycle_fire=" << static_cast<unsigned>(saw_lifecycle_fire)
            << " saw_lifecycle_boundary_fire=" << static_cast<unsigned>(saw_lifecycle_boundary_fire)
            << " saw_lifecycle_stop_fire=" << static_cast<unsigned>(saw_lifecycle_stop_fire)
            << " saw_retire_source_valid=" << static_cast<unsigned>(saw_retire_source_valid)
            << " saw_retire_source_boundary=" << static_cast<unsigned>(saw_retire_source_boundary)
            << " saw_retire_source_stop=" << static_cast<unsigned>(saw_retire_source_stop)
            << " saw_dealloc_block_last=" << static_cast<unsigned>(saw_dealloc_block_last)
            << " saw_redirect_valid=" << static_cast<unsigned>(saw_redirect_valid)
            << " last_retire_source_block_bid=0x" << std::hex << last_retire_source_block_bid
            << " last_retire_source_boundary_target=0x" << last_retire_source_boundary_target
            << " last_dealloc_block_last_bid=0x" << last_dealloc_block_last_bid
            << " last_redirect_pc=0x" << last_redirect_pc << std::dec
            << "\n";
  std::exit(1);
}

void drain_tail_marker_commits(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count,
    FetchMemoryImage &fetch_memory) {
  for (int cycle = 0; cycle < 64; ++cycle) {
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (filtered_marker_commits.empty()) {
      return;
    }
    if (!pending_commits.empty()) {
      std::cerr << "frontend fetch RF ALU saw scalar commit while draining tail marker"
                << " marker_pc=0x" << std::hex << filtered_marker_commits.front().pc
                << " observed_pc=0x" << pending_commits.front().pc << std::dec
                << "\n";
      std::exit(1);
    }

    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error while draining tail marker\n";
      std::exit(1);
    }
    if (dut.io_executeUnsupported) {
      std::cerr << "execute reported unsupported opcode while draining tail marker opcode="
                << static_cast<unsigned>(dut.io_executeUnsupportedOpcode) << "\n";
      std::exit(1);
    }
    if (dut.io_executeCompleteValid && dut.io_completeIgnored) {
      std::cerr << "execute completion was ignored while draining tail marker"
                << " rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
                << "\n";
      std::exit(1);
    }
    const std::uint64_t tail_marker_pc = filtered_marker_commits.front().pc;
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU tail marker drain");
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (!pending_commits.empty()) {
      std::cerr << "frontend fetch RF ALU saw scalar commit after tail marker filtering"
                << " marker_pc=0x" << std::hex << tail_marker_pc
                << " observed_pc=0x" << pending_commits.front().pc << std::dec
                << "\n";
      std::exit(1);
    }
    tick(dut);
  }

  if (!filtered_marker_commits.empty()) {
    std::cerr << "frontend fetch RF ALU tail marker did not retire"
              << " marker_pc=0x" << std::hex << filtered_marker_commits.front().pc
              << " next_pc=0x" << filtered_marker_commits.front().next_pc
              << std::dec << "\n";
    std::exit(1);
  }
}

void commit_expected_row(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const ExpectedRow &expected,
    std::vector<ObservedRow> &pending_commits,
    std::vector<ExpectedRow> &filtered_marker_commits,
    std::uint64_t &marker_commit_filter_count,
    FetchMemoryImage &fetch_memory,
    std::ofstream &dut_out,
    std::ofstream &qemu_out,
    bool disable_store_memory_mutation) {
  for (int cycle = 0; cycle < 32; ++cycle) {
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (!pending_commits.empty()) {
      const ObservedRow observed = pending_commits.front();
      pending_commits.erase(pending_commits.begin());
      expect_row(observed, expected, &dut);
      write_dut_row(dut_out, observed);
      write_qemu_row(qemu_out, expected);
      if (!disable_store_memory_mutation && expected.mem_valid && expected.mem_is_store && expected.mem_size == 8) {
        fetch_memory.store_u64(expected.mem_addr, expected.mem_wdata);
      }
      if (pending_commits.empty()) {
        clear_inputs(dut);
        tick(dut);
      }
      return;
    }
    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error while waiting for commit\n";
      std::exit(1);
    }
    if (dut.io_executeUnsupported) {
      std::cerr << "execute reported unsupported opcode="
                << static_cast<unsigned>(dut.io_executeUnsupportedOpcode) << "\n";
      std::exit(1);
    }
    if (dut.io_executeCompleteValid && dut.io_completeIgnored) {
      std::cerr << "execute completion was ignored while waiting for commit"
                << " rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
                << "\n";
      std::exit(1);
    }
    const bool observed_commit_window = dut.io_commit_rows_0_valid;
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU trace top commit");
    filter_pending_marker_commits(pending_commits, filtered_marker_commits, marker_commit_filter_count);
    if (!pending_commits.empty()) {
      const ObservedRow observed = pending_commits.front();
      pending_commits.erase(pending_commits.begin());
      expect_row(observed, expected, &dut);
      write_dut_row(dut_out, observed);
      write_qemu_row(qemu_out, expected);
      if (!disable_store_memory_mutation && expected.mem_valid && expected.mem_is_store && expected.mem_size == 8) {
        fetch_memory.store_u64(expected.mem_addr, expected.mem_wdata);
      }
      tick(dut);
      return;
    }
    if (observed_commit_window) {
      tick(dut);
      continue;
    }
    expect_monitor_clean(dut, "frontend fetch RF ALU trace top wait", 0x0, 0);
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU trace top did not emit a commit row"
            << " pc=0x" << std::hex << expected.pc << std::dec
            << " pendingCommits=" << pending_commits.size()
            << " decRenCount=" << static_cast<unsigned>(dut.io_decRenCount)
            << " decRenValid=" << static_cast<unsigned>(dut.io_decRenValid)
            << " decRenHeadPc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_decRenHeadPc)
            << " decRenHeadRidValid=" << std::dec
            << static_cast<unsigned>(dut.io_decRenHeadRidValid)
            << " decRenHeadRidValue=" << static_cast<unsigned>(dut.io_decRenHeadRidValue)
            << " renamedOutValid=" << static_cast<unsigned>(dut.io_renamedOutValid)
            << " renamedAccepted=" << static_cast<unsigned>(dut.io_renamedAccepted)
            << " decodeBlockedByRename=" << static_cast<unsigned>(dut.io_decodeBlockedByRename)
            << " decodeBlockedByRob=" << static_cast<unsigned>(dut.io_decodeBlockedByRob)
            << " decodeBlockedByOutput=" << static_cast<unsigned>(dut.io_decodeBlockedByOutput)
            << " decodeBlockedByTURename=" << static_cast<unsigned>(dut.io_decodeBlockedByTURename)
            << " admittedBarrier=" << static_cast<unsigned>(dut.io_admittedMarkerDrainBarrier)
            << " decodeQueueReady=" << static_cast<unsigned>(dut.io_decodeQueuePushReady)
            << " decodeAllocReady=" << static_cast<unsigned>(dut.io_decodeAllocReady)
            << " decodeGprReady=" << static_cast<unsigned>(dut.io_decodeGprReservationReady)
            << " decodeClosesRedirect=" << static_cast<unsigned>(dut.io_decodeSelectedClosesActiveRedirect)
            << " decodeNeedsGpr=" << static_cast<unsigned>(dut.io_decodeSelectedNeedsGprReservation)
            << " gprReservationCount=" << static_cast<unsigned>(dut.io_gprReservationCount)
            << " gprReservationNeed=" << static_cast<unsigned>(dut.io_gprReservationNeed)
            << " gprFree=" << static_cast<unsigned>(dut.io_gprFreeCount)
            << " gprMapQValid=" << static_cast<unsigned>(dut.io_gprMapQValidCount)
            << " gprMapQFree=" << static_cast<unsigned>(dut.io_gprMapQFreeCount)
            << " gprSmapLive=" << static_cast<unsigned>(dut.io_gprSmapLiveCount)
            << " gprCmapLive=" << static_cast<unsigned>(dut.io_gprCmapLiveCount)
            << " gprMapQLive=" << static_cast<unsigned>(dut.io_gprMapQLiveCount)
            << " gprLivePhys=" << static_cast<unsigned>(dut.io_gprLivePhysCount)
            << " gprFreeFromLive=" << static_cast<unsigned>(dut.io_gprFreeFromLiveCount)
            << " gprFreeMismatch=" << static_cast<unsigned>(dut.io_gprFreeListMismatchCount)
            << " gprNextMapQValid=" << static_cast<unsigned>(dut.io_gprNextMapQValidCount)
            << " gprNextMapQLive=" << static_cast<unsigned>(dut.io_gprNextMapQLiveCount)
            << " gprNextLivePhys=" << static_cast<unsigned>(dut.io_gprNextLivePhysCount)
            << " gprNextFreeFromLive=" << static_cast<unsigned>(dut.io_gprNextFreeFromLiveCount)
            << " robRenameUpdateAttemptValid="
            << static_cast<unsigned>(dut.io_robRenameUpdateAttemptValid)
            << " robRenameUpdateReady=" << static_cast<unsigned>(dut.io_robRenameUpdateReady)
            << " robRenameUpdateFire=" << static_cast<unsigned>(dut.io_robRenameUpdateFire)
            << " robRenameUpdateIgnored=" << static_cast<unsigned>(dut.io_robRenameUpdateIgnored)
            << " tuUnderflow=0x" << std::hex
            << static_cast<unsigned>(dut.io_tuRenameSourceUnderflowMask)
            << " tuActiveBank=" << std::dec
            << static_cast<unsigned>(dut.io_tuRenameActiveBankValid)
            << " tuTBlocked=" << static_cast<unsigned>(dut.io_tuRenameBlockedByTAlloc)
            << " tuUBlocked=" << static_cast<unsigned>(dut.io_tuRenameBlockedByUAlloc)
            << " tuTUsed=" << static_cast<unsigned>(dut.io_tuRenameTUsedEntries)
            << " tuUUsed=" << static_cast<unsigned>(dut.io_tuRenameUUsedEntries)
            << " tuRetCmdValid=" << static_cast<unsigned>(dut.io_tuRetireCommandValid)
            << " tuRetCmdFire=" << static_cast<unsigned>(dut.io_tuRetireCommandFire)
            << " tuLocalCommitPending=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitPending)
            << " tuLocalCommitValid=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitValid)
            << " tuLocalCommitReady=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitReady)
            << " tuLocalCommitFire=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitFire)
            << " tuRetAccepted=" << static_cast<unsigned>(dut.io_tuRetireAccepted)
            << " tuRetMiss=" << static_cast<unsigned>(dut.io_tuRetireMiss)
            << " tuRetMismatch=" << static_cast<unsigned>(dut.io_tuRetireReleaseMismatch)
            << " tuRetUnsupported=" << static_cast<unsigned>(dut.io_tuRetireUnsupported)
            << " localT=0x" << std::hex << static_cast<unsigned>(dut.io_localTReadyMask)
            << " localU=0x" << static_cast<unsigned>(dut.io_localUReadyMask)
            << std::dec
            << " localTPending=" << static_cast<unsigned>(dut.io_localTPendingCount)
            << " localUPending=" << static_cast<unsigned>(dut.io_localUPendingCount)
            << " localIncomingUsesLocal=" << static_cast<unsigned>(dut.io_localIncomingUsesLocal)
            << " localIncomingBlocked=" << static_cast<unsigned>(dut.io_localIncomingBlocked)
            << " storeDispatchReady=" << static_cast<unsigned>(dut.io_storeDispatchReady)
            << " storeDispatchFire=" << static_cast<unsigned>(dut.io_storeDispatchFire)
            << " storeDispatchSplit=" << static_cast<unsigned>(dut.io_storeDispatchSplit)
            << " storeStaQ=" << static_cast<unsigned>(dut.io_storeStaQueueCount)
            << " storeStdQ=" << static_cast<unsigned>(dut.io_storeStdQueueCount)
            << " storeStaExecValid=" << static_cast<unsigned>(dut.io_reducedStoreStaExecValid)
            << " storeStdExecValid=" << static_cast<unsigned>(dut.io_reducedStoreStdExecValid)
            << " storeBlockedStaExec=" << static_cast<unsigned>(dut.io_storeBlockedByStaExec)
            << " storeBlockedStdExec=" << static_cast<unsigned>(dut.io_storeBlockedByStdExec)
            << " storeStaInsertReady=" << static_cast<unsigned>(dut.io_storeStaInsertReady)
            << " storeStdInsertReady=" << static_cast<unsigned>(dut.io_storeStdInsertReady)
            << " storeSelectedSta=" << static_cast<unsigned>(dut.io_storeSelectedSta)
            << " storeSelectedStd=" << static_cast<unsigned>(dut.io_storeSelectedStd)
            << " storeStqInsertValid=" << static_cast<unsigned>(dut.io_storeStqInsertValid)
            << " storeStqInsertAccepted=" << static_cast<unsigned>(dut.io_storeStqInsertAccepted)
            << " storeStqInsertAllocated=" << static_cast<unsigned>(dut.io_storeStqInsertAllocated)
            << " storeStqInsertMerged=" << static_cast<unsigned>(dut.io_storeStqInsertMerged)
            << " storeStqInsertConflict=" << static_cast<unsigned>(dut.io_storeStqInsertConflict)
            << " storeStqWait=0x" << std::hex << hex_port(dut.io_storeStqWaitMask)
            << " storeStqCommit=0x" << hex_port(dut.io_storeStqCommitMask)
            << std::dec
            << " storeStqResident=" << static_cast<unsigned>(dut.io_storeStqResidentCount)
            << " storeStqOutstandingWait=" << static_cast<unsigned>(dut.io_storeStqOutstandingWaitCount)
            << " storeStqFull=" << static_cast<unsigned>(dut.io_storeStqFull)
            << " reducedStoreExecBuf=" << static_cast<unsigned>(dut.io_reducedStoreExecBufferCount)
            << " reducedStoreExecMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedStoreExecValidMask)
            << std::dec
            << " reducedStoreCommitSeen=" << static_cast<unsigned>(dut.io_reducedStoreCommitStoreSeen)
            << " reducedStoreCommitMatched=" << static_cast<unsigned>(dut.io_reducedStoreCommitStoreMatched)
            << " reducedStoreCommitUnmatched=" << static_cast<unsigned>(dut.io_reducedStoreCommitStoreUnmatched)
            << " reducedStoreCommitMatchMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedStoreCommitMatchMask)
            << " reducedStoreCommitPendingMarkMask=0x"
            << static_cast<unsigned>(dut.io_reducedStoreCommitPendingMarkMask)
            << " reducedStoreCommitPendingFreeMask=0x"
            << static_cast<unsigned>(dut.io_reducedStoreCommitPendingFreeMask)
            << std::dec
            << " reducedStoreCommitMarkValid="
            << static_cast<unsigned>(dut.io_reducedStoreCommitMarkValid)
            << " reducedStoreCommitMarkAccepted="
            << static_cast<unsigned>(dut.io_reducedStoreCommitMarkAccepted)
            << " reducedStoreCommitMarkBlocked="
            << static_cast<unsigned>(dut.io_reducedStoreCommitMarkBlocked)
            << " reducedStoreCommitMarkPending="
            << static_cast<unsigned>(dut.io_reducedStoreCommitPendingMarkCount)
            << " reducedStoreDrainQ=" << static_cast<unsigned>(dut.io_reducedStoreDrainQueueCount)
            << " reducedStoreScbEntries=" << static_cast<unsigned>(dut.io_reducedStoreScbEntryCount)
            << " reducedStoreMemoryLines=" << static_cast<unsigned>(dut.io_reducedStoreMemoryLineCount)
            << " reducedStoreMemoryForwardMask=0x" << std::hex
            << static_cast<unsigned>(dut.io_reducedStoreMemoryLoadForwardMask)
            << " reducedStoreMemoryDropped=0x"
            << static_cast<unsigned>(dut.io_reducedStoreMemoryStoreDroppedMask)
            << std::dec
            << " issueCount=" << static_cast<unsigned>(dut.io_issueQueueCount)
            << " issueHeadValid=" << static_cast<unsigned>(dut.io_issueQueueHeadValid)
            << " issueHeadIssued=" << static_cast<unsigned>(dut.io_issueQueueHeadIssued)
            << " issueHeadPc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_issueQueueHeadPc)
            << " issueHeadOpcode=0x"
            << static_cast<unsigned>(dut.io_issueQueueHeadOpcode)
            << " issueHeadSrcValid=0x"
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcValidMask)
            << " issueHeadSrc0=(cls=" << std::dec
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcClass_0)
            << ",phys=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcPhysTag_0)
            << ",rel=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcRelTag_0)
            << ") issueHeadSrc1=(cls="
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcClass_1)
            << ",phys=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcPhysTag_1)
            << ",rel=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcRelTag_1)
            << ") issueHeadSrc2=(cls="
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcClass_2)
            << ",phys=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcPhysTag_2)
            << ",rel=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcRelTag_2)
            << ")"
            << " issueSourceReady=0x" << std::hex
            << static_cast<unsigned>(dut.io_issueQueueSourceReadyMask)
            << std::dec
            << " issueSelectedValid=" << static_cast<unsigned>(dut.io_issueQueueSelectedValid)
            << " issueSelectedReadReady=" << static_cast<unsigned>(dut.io_issueQueueSelectedReadReady)
            << " issueI1Valid=" << static_cast<unsigned>(dut.io_issueQueueI1Valid)
            << " issueI2Valid=" << static_cast<unsigned>(dut.io_issueQueueI2Valid)
            << " issueBlockedBySource=" << static_cast<unsigned>(dut.io_issueQueueBlockedBySource)
            << " issueBlockedByRead=" << static_cast<unsigned>(dut.io_issueQueueBlockedByRead)
            << " issueBlockedByOutput=" << static_cast<unsigned>(dut.io_issueQueueBlockedByOutput)
            << " issueBlockedByIssued=" << static_cast<unsigned>(dut.io_issueQueueBlockedByIssued)
            << " executeBusy=" << static_cast<unsigned>(dut.io_executeBusy)
            << " outstanding=" << static_cast<unsigned>(dut.io_outstandingCount)
            << " robDeallocBlockLastValid=" << static_cast<unsigned>(dut.io_robDeallocBlockLastValid)
            << " blockScalarDoneFire=" << static_cast<unsigned>(dut.io_blockScalarDoneFire)
            << " blockRetireFire=" << static_cast<unsigned>(dut.io_blockRetireFire)
            << " commitHeadValid=" << static_cast<unsigned>(dut.io_commitHeadValid)
            << " commitHeadStatus=" << static_cast<unsigned>(dut.io_commitHeadStatus)
            << " commitHeadRobValue=" << static_cast<unsigned>(dut.io_commitHeadRobValue)
            << " occupiedMask=0x" << std::hex
            << hex_port(dut.io_occupiedMask)
            << " completedMask=0x" << std::hex
            << hex_port(dut.io_completedMask)
            << " retiredMask=0x"
            << hex_port(dut.io_retiredMask)
            << std::dec
            << "\n";
  std::exit(1);
}

std::vector<ExpectedRow> fixture_rows() {
  const std::uint64_t add =
      0x00000005ULL | (3ULL << 7) | (4ULL << 15) | (5ULL << 20);
  const std::uint64_t addi =
      0x00000015ULL | (6ULL << 7) | (3ULL << 15) | (0x7ffULL << 20);
  const std::uint64_t c_movr =
      0x0006ULL | (6ULL << 6) | (5ULL << 11);

  ExpectedRow r0;
  r0.pc = 0x1000;
  r0.insn = add;
  r0.len = 4;
  r0.src0_valid = true;
  r0.src0_reg = 4;
  r0.src0_data = 10;
  r0.src1_valid = true;
  r0.src1_reg = 5;
  r0.src1_data = 32;
  r0.dst_valid = true;
  r0.dst_reg = 3;
  r0.dst_data = 42;

  ExpectedRow r1;
  r1.pc = 0x1004;
  r1.insn = addi;
  r1.len = 4;
  r1.src0_valid = true;
  r1.src0_reg = 3;
  r1.src0_data = 42;
  r1.dst_valid = true;
  r1.dst_reg = 6;
  r1.dst_data = 2089;

  ExpectedRow r2;
  r2.pc = 0x1008;
  r2.insn = c_movr;
  r2.len = 2;
  r2.src0_valid = true;
  r2.src0_reg = 6;
  r2.src0_data = 2089;
  r2.dst_valid = true;
  r2.dst_reg = 5;
  r2.dst_data = 2089;

  return {r0, r1, r2};
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const Args args = parse_args(argc, argv);
  g_replay_liq_sideband_stats_path = args.sideband_stats;

  const auto rows = args.expected_rows.empty()
                        ? fixture_rows()
                        : load_expected_rows_jsonl(args.expected_rows);
  FetchMemoryImage fetch_memory;
  if (!args.memory_hex.empty()) {
    fetch_memory.load_sparse_hex(args.memory_hex);
  } else if (!args.memory_bin.empty()) {
    fetch_memory.load_binary(args.memory_bin, args.memory_base);
  } else {
    fetch_memory = FetchMemoryImage::from_rows(rows);
  }

  VLinxCoreFrontendFetchRfAluTraceTop dut;
  reset(dut);
  for (const auto &[arch_tag, data] : initial_rf_preloads(rows)) {
    init_rf(dut, arch_tag, data);
  }

  std::ofstream dut_out(args.dut_trace);
  std::ofstream qemu_out(args.qemu_trace);
  if (!dut_out || !qemu_out) {
    std::cerr << "failed to open output traces\n";
    return 2;
  }

  start_source(dut, rows.front().pc);
  bool active_block_valid = false;
  std::uint64_t active_block_bid = 0;
  bool captured_tail_superset = false;
  bool next_local_body_cut_reentry_header = false;
  std::uint64_t next_local_body_cut_reentry_pc = 0;
  std::vector<ObservedRow> pending_commits;
  std::vector<ExpectedRow> filtered_marker_commits;
  std::uint64_t marker_rows_admitted = 0;
  std::uint64_t marker_commits_filtered = 0;
  BfuGeometryDiagnostics bfu_stats;
  for (std::size_t row_index = 0; row_index < rows.size();) {
    const std::size_t window_end = dense_window_end(rows, row_index);
    const FetchDenseWindowResult capture =
        fetch_dense_window(
            dut,
            rows,
            row_index,
            window_end,
            fetch_memory,
            pending_commits,
            filtered_marker_commits,
            marker_commits_filtered,
            bfu_stats);
    captured_tail_superset |= capture.captured_tail_superset;
    const std::size_t drain_end = row_index + capture.captured_slots;

    std::vector<std::size_t> scalar_slots;
    bool replay_local_body_cut_reentry_header = false;
    for (std::size_t slot_index = row_index; slot_index < drain_end; ++slot_index) {
      const ExpectedRow &row = rows[slot_index];
      const bool local_body_cut_reentry_header =
          next_local_body_cut_reentry_header &&
          slot_index == row_index &&
          row.skip &&
          row.block_boundary &&
          row.pc == next_local_body_cut_reentry_pc;
      if (next_local_body_cut_reentry_header && slot_index == row_index && !local_body_cut_reentry_header) {
        std::cerr << "frontend fetch RF ALU expected a local-body-cut re-entry header"
                  << " expected_pc=0x" << std::hex << next_local_body_cut_reentry_pc
                  << " observed_pc=0x" << row.pc << std::dec
                  << " skip=" << static_cast<unsigned>(row.skip)
                  << " block_boundary=" << static_cast<unsigned>(row.block_boundary)
                  << "\n";
        return 1;
      }
      if (row.skip) {
        const DrainDenseRowResult drain_result = drain_dense_row(
            dut,
            row,
            active_block_valid,
            active_block_bid,
            fetch_memory,
            pending_commits,
            filtered_marker_commits,
            marker_commits_filtered,
            bfu_stats,
            args.admit_marker_rows,
            local_body_cut_reentry_header);
        if (args.admit_marker_rows) {
          ++marker_rows_admitted;
          if ((row.block_boundary || row.block_stop) && row_redirects(row)) {
            for (const auto pending_scalar_slot : scalar_slots) {
              const ExpectedRow &pending_scalar = rows[pending_scalar_slot];
              commit_expected_row(
                  dut,
                  pending_scalar,
                  pending_commits,
                  filtered_marker_commits,
                  marker_commits_filtered,
                  fetch_memory,
                  dut_out,
                  qemu_out,
                  args.disable_store_memory_mutation);
            }
            scalar_slots.clear();
            wait_for_admitted_marker_redirect(
                dut,
                row,
                pending_commits,
                filtered_marker_commits,
                marker_commits_filtered,
                fetch_memory);
          }
        }
        if (local_body_cut_reentry_header) {
          next_local_body_cut_reentry_header = false;
          next_local_body_cut_reentry_pc = 0;
          if (drain_result.marker_redirect) {
            replay_local_body_cut_reentry_header = true;
            break;
          }
        }
        continue;
      }
      (void)drain_dense_row(
          dut,
          row,
          active_block_valid,
          active_block_bid,
          fetch_memory,
          pending_commits,
          filtered_marker_commits,
          marker_commits_filtered,
          bfu_stats,
          args.admit_marker_rows);
      scalar_slots.push_back(slot_index);
    }

    for (const auto slot_index : scalar_slots) {
      const ExpectedRow &row = rows[slot_index];
      commit_expected_row(
          dut,
          row,
          pending_commits,
          filtered_marker_commits,
          marker_commits_filtered,
          fetch_memory,
          dut_out,
          qemu_out,
          args.disable_store_memory_mutation);
    }
    if (replay_local_body_cut_reentry_header) {
      continue;
    }
    row_index = drain_end;
    if (capture.local_body_cut_prefix) {
      if (!is_suppressed_local_body_cut_marker(rows, row_index)) {
        std::cerr << "frontend fetch RF ALU local body cut did not stop before a redirecting BSTART marker"
                  << " row_index=" << row_index;
        if (row_index < rows.size()) {
          const ExpectedRow &row = rows[row_index];
          std::cerr << " pc=0x" << std::hex << row.pc
                    << " next_pc=0x" << row.next_pc << std::dec
                    << " skip=" << static_cast<unsigned>(row.skip)
                    << " block_boundary=" << static_cast<unsigned>(row.block_boundary);
        }
        std::cerr << "\n";
        return 1;
      }
      next_local_body_cut_reentry_header = true;
      next_local_body_cut_reentry_pc = rows[row_index].next_pc;
      ++row_index;
    } else {
      row_index = window_end;
    }
  }

  if (!filtered_marker_commits.empty()) {
    drain_tail_marker_commits(
        dut,
        pending_commits,
        filtered_marker_commits,
        marker_commits_filtered,
        fetch_memory);
  }
  if (!filtered_marker_commits.empty()) {
    std::cerr << "frontend fetch RF ALU trace top has unfiltered marker commits="
              << filtered_marker_commits.size()
              << " pc=0x" << std::hex << filtered_marker_commits.front().pc
              << std::dec << "\n";
    return 1;
  }
  if (!pending_commits.empty()) {
    std::cerr << "frontend fetch RF ALU trace top has unconsumed commit rows="
              << pending_commits.size() << "\n";
    return 1;
  }
  const auto print_bfu_stats = [&]() {
    std::cout << "bfu_static_external_comparable=" << bfu_stats.comparable_count
              << " bfu_static_external_matches=" << bfu_stats.match_count
              << " bfu_resolved_body_end_accepts=" << bfu_stats.resolved_accept_count
              << " bfu_body_cut_arm_comparable=" << bfu_stats.cut_arm_comparable_count
              << " bfu_body_cut_arm_accepts=" << bfu_stats.cut_arm_accept_count
              << " bfu_body_cut_arm_mismatches=" << bfu_stats.cut_arm_mismatch_count
              << " bfu_local_body_cut_prefixes=" << bfu_stats.local_body_cut_prefix_count
              << " bfu_resolved_source_runtime_selected=" << bfu_stats.resolved_source_runtime_selected_count
              << " bfu_resolved_source_replay_selected=" << bfu_stats.resolved_source_replay_selected_count
              << " bfu_resolved_source_runtime_feedback=" << bfu_stats.resolved_source_runtime_feedback_count
              << " bfu_resolved_source_runtime_pending=" << bfu_stats.resolved_source_runtime_pending_count
              << " bfu_resolved_source_runtime_pending_consumes="
              << bfu_stats.resolved_source_runtime_pending_consume_count
              << " bfu_resolved_source_runtime_pending_drop_mismatches="
              << bfu_stats.resolved_source_runtime_pending_drop_mismatch_count
              << " bfu_resolved_source_runtime_pending_candidate_comparable="
              << bfu_stats.resolved_source_runtime_pending_candidate_comparable_count
              << " bfu_resolved_source_runtime_pending_candidate_matches="
              << bfu_stats.resolved_source_runtime_pending_candidate_match_count
              << " bfu_resolved_source_runtime_pending_candidate_mismatches="
              << bfu_stats.resolved_source_runtime_pending_candidate_mismatch_count
              << " bfu_pending_runtime_candidate_valid="
              << bfu_stats.pending_runtime_candidate_valid_count
              << " bfu_pending_runtime_candidate_without_active_header="
              << bfu_stats.pending_runtime_candidate_without_active_header_count
              << " bfu_pending_runtime_candidate_active_header_mismatches="
              << bfu_stats.pending_runtime_candidate_active_header_mismatch_count
              << " bfu_pending_runtime_candidate_replay_comparable="
              << bfu_stats.pending_runtime_candidate_replay_comparable_count
              << " bfu_pending_runtime_candidate_replay_matches="
              << bfu_stats.pending_runtime_candidate_replay_match_count
              << " bfu_pending_runtime_candidate_replay_mismatches="
              << bfu_stats.pending_runtime_candidate_replay_mismatch_count
              << " bfu_promoted_runtime_body_end_oracle_pending="
              << bfu_stats.promoted_runtime_body_end_oracle_pending_count
              << " bfu_promoted_runtime_body_end_oracle_captures="
              << bfu_stats.promoted_runtime_body_end_oracle_capture_count
              << " bfu_promoted_runtime_body_end_oracle_replay_comparable="
              << bfu_stats.promoted_runtime_body_end_oracle_replay_comparable_count
              << " bfu_promoted_runtime_body_end_oracle_replay_matches="
              << bfu_stats.promoted_runtime_body_end_oracle_replay_match_count
              << " bfu_promoted_runtime_body_end_oracle_replay_mismatches="
              << bfu_stats.promoted_runtime_body_end_oracle_replay_mismatch_count
              << " bfu_promoted_runtime_body_end_oracle_overwrites="
              << bfu_stats.promoted_runtime_body_end_oracle_overwrite_count
              << " bfu_resolved_source_runtime_replay_comparable="
              << bfu_stats.resolved_source_runtime_replay_comparable_count
              << " bfu_resolved_source_runtime_replay_matches="
              << bfu_stats.resolved_source_runtime_replay_match_count
              << " bfu_resolved_source_runtime_replay_mismatches="
              << bfu_stats.resolved_source_runtime_replay_mismatch_count
              << " marker_rows_admitted=" << marker_rows_admitted
              << " marker_commits_filtered=" << marker_commits_filtered
              << "\n";
  };
  if (captured_tail_superset) {
    print_bfu_stats();
    if (!write_replay_liq_sideband_stats(args.sideband_stats)) {
      dut.final();
      return 2;
    }
    dut.final();
    return 0;
  }
  if (args.allow_residual_replay_liq_wait) {
    for (int cycle = 0; cycle < 8; ++cycle) {
      eval_with_load_lookup(dut, fetch_memory, "residual replay-liq wait observe");
      tick(dut);
    }
    print_bfu_stats();
    if (!write_replay_liq_sideband_stats(args.sideband_stats)) {
      dut.final();
      return 2;
    }
    dut.final();
    return 0;
  }
  drain_empty(dut, fetch_memory);
  eval_with_load_lookup(dut, fetch_memory);
  if (!dut.io_idle || !dut.io_empty || dut.io_size != 0) {
    std::cerr << "frontend fetch RF ALU trace top did not finish idle"
              << " idle=" << static_cast<unsigned>(dut.io_idle)
              << " empty=" << static_cast<unsigned>(dut.io_empty)
              << " size=" << static_cast<unsigned>(dut.io_size)
              << "\n";
    return 1;
  }
  expect_monitor_clean(dut, "post-drain idle window", 0x0, 0);

  print_bfu_stats();
  if (!write_replay_liq_sideband_stats(args.sideband_stats)) {
    dut.final();
    return 2;
  }
  dut.final();
  return 0;
}
