#include "VReducedStoreWaitReplayChiselPathProbe.h"
#include "verilated.h"

#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>

namespace {

constexpr std::uint8_t kStoreAll = 0;
constexpr std::uint8_t kStoreAddr = 1;
constexpr std::uint8_t kStoreData = 2;
constexpr std::uint64_t kStorePc = 0x4400;
constexpr std::uint64_t kLoadAddr = 0x3000;
constexpr std::uint64_t kLoadLineAddr = 0x3000;
constexpr std::uint64_t kStoreDataValue = 0x1122334455667788ULL;
constexpr std::uint64_t kBaseData = 0xffeeddccbbaa9988ULL;
constexpr std::uint64_t kRefillDataLow = 0x0123456789abcdefULL;
constexpr std::uint64_t kReplayWakeValidMask = 0xffULL;
constexpr std::uint8_t kReplayWakeScbSource = 1;
constexpr std::uint8_t kStoreBid = 1;
constexpr std::uint8_t kStoreLsId = 1;
constexpr std::uint8_t kLoadBid = 2;
constexpr std::uint8_t kLoadLsId = 3;
constexpr std::uint8_t kSecondStoreBid = 4;
constexpr std::uint8_t kSecondStoreLsId = 4;
constexpr std::uint8_t kSecondLoadBid = 5;
constexpr std::uint8_t kSecondLoadLsId = 6;

struct Args {
  std::string report_json;
};

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0 << " [--report-json <path>]\n";
  std::exit(2);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--report-json" && i + 1 < argc) {
      args.report_json = argv[++i];
    } else {
      usage(argv[0]);
    }
  }
  return args;
}

void fail(const std::string &message) {
  std::cerr << "reduced-store-wait-replay-chisel-path failure: " << message << "\n";
  std::exit(1);
}

void expect(bool condition, const std::string &message) {
  if (!condition) {
    fail(message);
  }
}

std::string mask_diag(VReducedStoreWaitReplayChiselPathProbe &dut) {
  return " candidate_mask=" + std::to_string(dut.io_mdbLookupWaitPlanCandidateMask) +
         " target_index=" + std::to_string(dut.io_mdbLookupWaitPlanTargetIndex) +
         " occupied_mask=" + std::to_string(dut.io_liqOccupiedMask) +
         " wait_mask=" + std::to_string(dut.io_liqWaitMask) +
         " repick_mask=" + std::to_string(dut.io_liqRepickMask);
}

void clear_inputs(VReducedStoreWaitReplayChiselPathProbe &dut) {
  dut.io_flush = 0;
  dut.io_storeInsertValid = 0;
  dut.io_storeInsert_storeType = 0;
  dut.io_storeInsert_peId = 0;
  dut.io_storeInsert_stid = 0;
  dut.io_storeInsert_tid = 0;
  dut.io_storeInsert_bid_valid = 0;
  dut.io_storeInsert_bid_wrap = 0;
  dut.io_storeInsert_bid_value = 0;
  dut.io_storeInsert_gid_valid = 0;
  dut.io_storeInsert_gid_wrap = 0;
  dut.io_storeInsert_gid_value = 0;
  dut.io_storeInsert_rid_valid = 0;
  dut.io_storeInsert_rid_wrap = 0;
  dut.io_storeInsert_rid_value = 0;
  dut.io_storeInsert_lsId_valid = 0;
  dut.io_storeInsert_lsId_wrap = 0;
  dut.io_storeInsert_lsId_value = 0;
  dut.io_storeInsert_tSeq_valid = 0;
  dut.io_storeInsert_tSeq_wrap = 0;
  dut.io_storeInsert_tSeq_value = 0;
  dut.io_storeInsert_uSeq_valid = 0;
  dut.io_storeInsert_uSeq_wrap = 0;
  dut.io_storeInsert_uSeq_value = 0;
  dut.io_storeInsert_tuDstValid = 0;
  dut.io_storeInsert_tuDstKind = 0;
  dut.io_storeInsert_pc = 0;
  dut.io_storeInsert_addr = 0;
  dut.io_storeInsert_data = 0;
  dut.io_storeInsert_size = 0;
  dut.io_storeInsert_stackValid = 0;
  dut.io_storeInsert_scalarIex = 0;
  dut.io_storeInsert_simtLane = 0;
  dut.io_loadValid = 0;
  dut.io_loadAddr = 0;
  dut.io_loadSize = 0;
  dut.io_loadBid_valid = 0;
  dut.io_loadBid_wrap = 0;
  dut.io_loadBid_value = 0;
  dut.io_loadLsId_valid = 0;
  dut.io_loadLsId_wrap = 0;
  dut.io_loadLsId_value = 0;
  dut.io_baseLoadData = 0;
  dut.io_captureEnable = 0;
  dut.io_liqRefillValid = 0;
  dut.io_liqRefillLineAddr = 0;
  for (int word = 0; word < 16; ++word) {
    dut.io_liqRefillData[word] = 0;
  }
  dut.io_liqLaunchEnable = 0;
  dut.io_liqE2LoadDataReturned = 0;
  dut.io_liqE2ScbReturned = 0;
  dut.io_liqE2StqReturned = 0;
  dut.io_liqE2ReturnReady = 0;
  dut.io_liqReplayWakeValid = 0;
  dut.io_liqReplayWake_source = 0;
  dut.io_liqReplayWake_storeId_valid = 0;
  dut.io_liqReplayWake_storeId_wrap = 0;
  dut.io_liqReplayWake_storeId_value = 0;
  dut.io_liqReplayWake_storeLsId_valid = 0;
  dut.io_liqReplayWake_storeLsId_wrap = 0;
  dut.io_liqReplayWake_storeLsId_value = 0;
  dut.io_liqReplayWake_pc = 0;
  dut.io_liqReplayWake_lineAddr = 0;
  dut.io_liqReplayWake_validMask = 0;
  for (int word = 0; word < 16; ++word) {
    dut.io_liqReplayWake_data[word] = 0;
  }
  dut.io_resolveQueueRetireValid = 0;
  dut.io_resolveQueueRetireBid_valid = 0;
  dut.io_resolveQueueRetireBid_wrap = 0;
  dut.io_resolveQueueRetireBid_value = 0;
  dut.io_resolveQueueRetireLsId_valid = 0;
  dut.io_resolveQueueRetireLsId_wrap = 0;
  dut.io_resolveQueueRetireLsId_value = 0;
  dut.io_mdbLookupValid = 0;
  dut.io_mdbDeleteValid = 0;
  dut.io_mdbDeleteWaitStorePc = 0;
  dut.io_mdbStore_valid = 0;
  dut.io_mdbStore_addrOnly = 0;
  dut.io_mdbStore_isTile = 0;
  dut.io_mdbStore_peId = 0;
  dut.io_mdbStore_stid = 0;
  dut.io_mdbStore_tid = 0;
  dut.io_mdbStore_bid_valid = 0;
  dut.io_mdbStore_bid_wrap = 0;
  dut.io_mdbStore_bid_value = 0;
  dut.io_mdbStore_gid_valid = 0;
  dut.io_mdbStore_gid_wrap = 0;
  dut.io_mdbStore_gid_value = 0;
  dut.io_mdbStore_rid_valid = 0;
  dut.io_mdbStore_rid_wrap = 0;
  dut.io_mdbStore_rid_value = 0;
  dut.io_mdbStore_lsId_valid = 0;
  dut.io_mdbStore_lsId_wrap = 0;
  dut.io_mdbStore_lsId_value = 0;
  dut.io_mdbStore_pc = 0;
  dut.io_mdbStore_addr = 0;
  dut.io_mdbStore_size = 0;
  dut.io_mdbLookupUseLiveLoad = 0;
}

void tick(VReducedStoreWaitReplayChiselPathProbe &dut, std::uint64_t &cycle) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
  ++cycle;
}

void reset(VReducedStoreWaitReplayChiselPathProbe &dut, std::uint64_t &cycle) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut, cycle);
  tick(dut, cycle);
  dut.reset = 0;
  dut.eval();
  expect(dut.io_stqOccupiedMask == 0, "reset did not clear STQ occupancy");
  expect(!dut.io_waitSlotActive, "reset did not clear wait replay slot");
  expect(dut.io_liqResidentCount == 0, "reset did not clear LIQ residency");
}

void drive_store(VReducedStoreWaitReplayChiselPathProbe &dut, std::uint8_t store_type,
                 std::uint8_t bid = kStoreBid, std::uint8_t ls_id = kStoreLsId) {
  dut.io_storeInsertValid = 1;
  dut.io_storeInsert_storeType = store_type;
  dut.io_storeInsert_bid_valid = 1;
  dut.io_storeInsert_bid_wrap = 0;
  dut.io_storeInsert_bid_value = bid;
  dut.io_storeInsert_gid_valid = 1;
  dut.io_storeInsert_gid_value = 0;
  dut.io_storeInsert_rid_valid = 1;
  dut.io_storeInsert_rid_value = 0;
  dut.io_storeInsert_lsId_valid = 1;
  dut.io_storeInsert_lsId_wrap = 0;
  dut.io_storeInsert_lsId_value = ls_id;
  dut.io_storeInsert_pc = kStorePc;
  dut.io_storeInsert_addr = kLoadAddr;
  dut.io_storeInsert_data = kStoreDataValue;
  dut.io_storeInsert_size = 8;
  dut.io_storeInsert_scalarIex = 1;
}

void drive_load(VReducedStoreWaitReplayChiselPathProbe &dut, bool capture_enable,
                std::uint8_t bid = kLoadBid, std::uint8_t ls_id = kLoadLsId) {
  dut.io_loadValid = 1;
  dut.io_loadAddr = kLoadAddr;
  dut.io_loadSize = 8;
  dut.io_loadBid_valid = 1;
  dut.io_loadBid_wrap = 0;
  dut.io_loadBid_value = bid;
  dut.io_loadLsId_valid = 1;
  dut.io_loadLsId_wrap = 0;
  dut.io_loadLsId_value = ls_id;
  dut.io_baseLoadData = kBaseData;
  dut.io_captureEnable = capture_enable ? 1 : 0;
}

void drive_live_lookup_load_identity(VReducedStoreWaitReplayChiselPathProbe &dut,
                                     std::uint8_t bid = kSecondLoadBid,
                                     std::uint8_t ls_id = kSecondLoadLsId) {
  dut.io_mdbLookupUseLiveLoad = 1;
  dut.io_mdbLookupValid = 1;
  dut.io_loadAddr = kLoadAddr;
  dut.io_loadSize = 8;
  dut.io_loadBid_valid = 1;
  dut.io_loadBid_wrap = 0;
  dut.io_loadBid_value = bid;
  dut.io_loadLsId_valid = 1;
  dut.io_loadLsId_wrap = 0;
  dut.io_loadLsId_value = ls_id;
}

void drive_scb_replay_wake(VReducedStoreWaitReplayChiselPathProbe &dut) {
  dut.io_liqReplayWakeValid = 1;
  dut.io_liqReplayWake_source = kReplayWakeScbSource;
  dut.io_liqReplayWake_lineAddr = kLoadLineAddr;
  dut.io_liqReplayWake_validMask = kReplayWakeValidMask;
  dut.io_liqReplayWake_data[0] = static_cast<std::uint32_t>(kRefillDataLow);
  dut.io_liqReplayWake_data[1] = static_cast<std::uint32_t>(kRefillDataLow >> 32);
}

void drive_mdb_store_probe(VReducedStoreWaitReplayChiselPathProbe &dut) {
  dut.io_mdbStore_valid = 1;
  dut.io_mdbStore_addrOnly = 0;
  dut.io_mdbStore_isTile = 0;
  dut.io_mdbStore_peId = 0;
  dut.io_mdbStore_stid = 0;
  dut.io_mdbStore_tid = 0;
  dut.io_mdbStore_bid_valid = 1;
  dut.io_mdbStore_bid_wrap = 0;
  dut.io_mdbStore_bid_value = kStoreBid;
  dut.io_mdbStore_gid_valid = 1;
  dut.io_mdbStore_gid_value = 0;
  dut.io_mdbStore_rid_valid = 1;
  dut.io_mdbStore_rid_value = 0;
  dut.io_mdbStore_lsId_valid = 1;
  dut.io_mdbStore_lsId_wrap = 0;
  dut.io_mdbStore_lsId_value = kStoreLsId;
  dut.io_mdbStore_pc = kStorePc;
  dut.io_mdbStore_addr = kLoadAddr;
  dut.io_mdbStore_size = 8;
}

struct Report {
  std::uint64_t cycles = 0;
  bool ready_forward_observed = false;
  bool sta_wait_capture = false;
  bool not_ready_wake_blocked = false;
  bool std_wake_clear = false;
  bool relaunch_queue_fire = false;
  bool liq_alloc = false;
  bool liq_refill = false;
  bool liq_launch_valid = false;
  bool liq_launch_accepted = false;
  bool liq_e4_update = false;
  bool liq_lhq_record = false;
  bool liq_resolved = false;
  bool resolve_queue_push = false;
  bool liq_clear_resolved = false;
  bool mdb_resolve_conflict = false;
  bool mdb_nuke_flush = false;
  bool mdb_fanout_record_accepted = false;
  bool mdb_fanout_record_processed = false;
  bool mdb_bmdb_report = false;
  bool mdb_fanout_record_reinforced = false;
  bool mdb_lookup_first_suppressed = false;
  bool mdb_lookup_hit = false;
  bool mdb_su_wakeup = false;
  bool mdb_lookup_wait_plan_no_target = false;
  bool mdb_lookup_wait_plan_live_target = false;
  bool mdb_lookup_wait_plan_request = false;
  bool mdb_lookup_wait_plan_bridge = false;
  bool mdb_lookup_wait_plan_control_blocked = false;
  bool mdb_lookup_wait_plan_scb_evidence = false;
  bool mdb_lookup_wait_plan_write = false;
  bool mdb_lookup_wait_plan_apply = false;
  bool mdb_lookup_wait_plan_wait_status_after_write = false;
  bool mdb_delete_accepted = false;
  bool mdb_delete_dropped_below_stall = false;
  bool mdb_delete_released = false;
  bool resolve_queue_retired = false;
  std::uint32_t youngest_store_lsid = 0;
  std::uint32_t launch_load_lsid = 0;
  std::uint32_t resolve_queue_count = 0;
  std::uint32_t resolve_queue_count_after_retire = 0;
  std::uint32_t mdb_resolve_candidate_mask = 0;
  std::uint32_t mdb_conflict_load_lsid = 0;
  std::uint32_t mdb_fanout_ssit_valid_mask = 0;
  std::uint32_t mdb_su_wakeup_store_index = 0;
  std::uint32_t mdb_lookup_wait_plan_candidate_mask = 0;
  std::uint32_t mdb_lookup_wait_plan_live_candidate_mask = 0;
  std::uint32_t mdb_lookup_wait_plan_live_target_index = 0;
  std::uint32_t liq_replay_wake_completed_mask = 0;
  std::uint32_t liq_sources_returned_mask_before_mdb_write = 0;
  std::uint32_t liq_scb_returned_mask_before_mdb_write = 0;
  std::uint32_t liq_wait_mask_after_mdb_write = 0;
  std::uint32_t liq_wait_store_mask_after_mdb_write = 0;
  std::uint32_t e4_cycles_after_launch = 0;
};

void run_ready_store_negative(VReducedStoreWaitReplayChiselPathProbe &dut, std::uint64_t &cycle, Report &report) {
  reset(dut, cycle);
  drive_store(dut, kStoreAll);
  dut.eval();
  expect(dut.io_storeInsertAccepted, "ready store insert was not accepted");
  tick(dut, cycle);

  clear_inputs(dut);
  drive_load(dut, true);
  dut.eval();
  expect(dut.io_forwardReady, "ready store did not produce ready forwarding");
  expect(!dut.io_forwardWaitBlocked, "ready store incorrectly blocked load on wait-store");
  expect(!dut.io_forwardWaitStoreValid, "ready store unexpectedly produced wait-store key");
  expect(!dut.io_waitSlotCaptureAccepted, "ready store unexpectedly captured wait replay slot");
  report.ready_forward_observed = true;
}

void run_sta_only_replay_path(VReducedStoreWaitReplayChiselPathProbe &dut, std::uint64_t &cycle, Report &report) {
  reset(dut, cycle);
  drive_store(dut, kStoreAddr);
  dut.eval();
  expect(dut.io_storeInsertAccepted, "STA insert was not accepted");
  tick(dut, cycle);
  expect((dut.io_stqOccupiedMask & 0x1) != 0, "STA row was not resident");
  expect((dut.io_stqAddrReadyMask & 0x1) != 0, "STA row did not set address-ready");
  expect((dut.io_stqDataReadyMask & 0x1) == 0, "STA row set data-ready too early");

  clear_inputs(dut);
  drive_load(dut, true);
  dut.eval();
  expect(dut.io_forwardWaitBlocked, "STA-only row did not block younger load");
  expect(dut.io_forwardWaitStoreValid, "STA-only row did not produce wait-store key");
  expect(dut.io_forwardWaitStoreIndex == 0, "STA-only wait-store key selected the wrong STQ index");
  expect(dut.io_waitSlotCaptureAccepted, "STA-only wait-store was not captured");
  tick(dut, cycle);
  report.sta_wait_capture = true;

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_waitSlotActive, "captured wait replay slot is not active");
  expect(dut.io_wakeIdentityMatch, "not-ready resident store lost wait identity");
  expect(!dut.io_wakeSelectedRowReady, "not-ready resident store reported wake-ready");
  expect(!dut.io_wakeValid, "not-ready resident store emitted wakeup");
  report.not_ready_wake_blocked = true;

  drive_store(dut, kStoreData);
  dut.eval();
  expect(dut.io_storeInsertAccepted, "STD merge was not accepted");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_wakeIdentityMatch, "ready resident store lost wait identity");
  expect(dut.io_wakeSelectedRowReady, "ready resident store did not report wake-ready");
  expect(dut.io_wakeValid, "ready resident store did not emit wakeup");
  expect(dut.io_waitStoreClear, "store wakeup did not clear wait replay slot");
  tick(dut, cycle);
  report.std_wake_clear = true;

  clear_inputs(dut);
  dut.eval();
  expect(!dut.io_waitSlotActive, "wait slot remained active after wake clear");
  expect(dut.io_relaunchQueuePending, "relaunch queue did not retain the replay candidate");
  expect(dut.io_relaunchQueueOutValid, "relaunch queue head was not valid");
  expect(dut.io_liqCandidateConsumeReady, "LIQ path was not ready to consume relaunch candidate");
  expect(dut.io_relaunchQueueOutFire, "relaunch queue did not fire into LIQ path");
  expect(dut.io_liqAllocAccepted, "LIQ path did not accept relaunch candidate");
  tick(dut, cycle);
  report.relaunch_queue_fire = true;
  report.liq_alloc = true;

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_liqResidentCount == 1, "LIQ did not retain allocated replay row");
  expect((dut.io_liqOccupiedMask & 0x1) != 0, "LIQ row zero is not occupied");
  expect(dut.io_liqFirstYoungestStoreLsId_valid, "LIQ row did not preserve youngest-store LSID valid bit");
  expect(dut.io_liqFirstYoungestStoreLsId_value == kStoreLsId, "LIQ row did not preserve youngest-store LSID value");
  report.youngest_store_lsid = dut.io_liqFirstYoungestStoreLsId_value;

  clear_inputs(dut);
  dut.io_liqRefillValid = 1;
  dut.io_liqRefillLineAddr = kLoadLineAddr;
  dut.io_liqRefillData[0] = static_cast<std::uint32_t>(kRefillDataLow);
  dut.io_liqRefillData[1] = static_cast<std::uint32_t>(kRefillDataLow >> 32);
  dut.eval();
  expect(dut.io_liqRefillAccepted, "LIQ refill wakeup was not accepted");
  expect((dut.io_liqRefillWakeMask & 0x1) != 0, "LIQ refill did not wake row zero");
  tick(dut, cycle);
  report.liq_refill = true;

  clear_inputs(dut);
  dut.eval();
  expect((dut.io_liqWaitMask & 0x1) != 0, "refilled LIQ row did not remain in Wait");
  expect(dut.io_liqLaunchValid, "refilled LIQ row did not become a launch candidate");
  expect(dut.io_liqLaunchReady, "refilled LIQ row was not launch-ready");
  expect(dut.io_liqLaunchIndex == 0, "LIQ launch selected the wrong row");
  expect(dut.io_liqLaunchSelectedLoadLsId_valid, "LIQ launch did not preserve selected load LSID valid bit");
  expect(dut.io_liqLaunchSelectedLoadLsId_value == kLoadLsId, "LIQ launch did not preserve selected load LSID value");
  report.liq_launch_valid = true;
  report.launch_load_lsid = dut.io_liqLaunchSelectedLoadLsId_value;

  dut.io_liqLaunchEnable = 1;
  dut.io_liqE2LoadDataReturned = 1;
  dut.io_liqE2ScbReturned = 1;
  dut.io_liqE2StqReturned = 1;
  dut.io_liqE2ReturnReady = 1;
  dut.eval();
  expect(dut.io_liqLaunchDriveValid, "LIQ launchEnable did not drive launch valid");
  expect(dut.io_liqLaunchAccepted, "LIQ launch was not accepted");
  tick(dut, cycle);
  report.liq_launch_accepted = true;

  clear_inputs(dut);
  bool saw_e4 = false;
  std::uint32_t e4_latency = 0;
  for (std::uint32_t wait = 0; wait < 4; ++wait) {
    dut.eval();
    if (dut.io_liqE4UpdateValid) {
      expect(dut.io_liqE4UpdateIndex == 0, "LIQ E4 update selected the wrong row");
      expect(dut.io_liqE4WakeupValid, "LIQ E4 update did not report wakeup-valid resolved data");
      expect(dut.io_liqLhqRecordValid, "LIQ E4 update did not publish an LHQ record");
      expect(dut.io_liqLhqRecordLoadLsId_valid, "LIQ LHQ record did not preserve load LSID valid bit");
      expect(dut.io_liqLhqRecordLoadLsId_value == kLoadLsId, "LIQ LHQ record did not preserve load LSID value");
      expect(dut.io_liqLhqRecordData[0] == static_cast<std::uint32_t>(kRefillDataLow),
             "LIQ LHQ record did not preserve refill data low word");
      expect(dut.io_resolveQueuePushAccepted, "ResolveQ did not accept the LIQ LHQ record");
      saw_e4 = true;
      e4_latency = wait;
      break;
    }
    tick(dut, cycle);
  }

  expect(saw_e4, "launched LIQ row did not produce an E4 update");
  report.liq_e4_update = true;
  report.liq_lhq_record = true;
  report.resolve_queue_push = true;
  report.e4_cycles_after_launch = e4_latency;

  tick(dut, cycle);
  dut.eval();
  expect((dut.io_liqResolvedMask & 0x1) != 0, "E4-resolved LIQ row did not enter Resolved");
  expect((dut.io_liqRepickMask & 0x1) == 0, "E4-resolved LIQ row remained in Repick");
  expect(dut.io_resolveQueueCount == 1, "ResolveQ did not retain the pushed LHQ record");
  expect((dut.io_resolveQueueValidMask & 0x1) != 0, "ResolveQ row zero is not valid after push");
  expect(dut.io_resolveQueueFirstLoadLsId_valid, "ResolveQ did not preserve load LSID valid bit");
  expect(dut.io_resolveQueueFirstLoadLsId_value == kLoadLsId, "ResolveQ did not preserve load LSID value");
  expect(dut.io_liqClearResolvedPending, "LIQ clear-resolved request was not pending after ResolveQ push");
  expect(dut.io_liqClearResolvedAccepted, "LIQ clear-resolved request was not accepted");
  report.liq_resolved = true;
  report.resolve_queue_count = dut.io_resolveQueueCount;

  tick(dut, cycle);
  dut.eval();
  expect(dut.io_liqResidentCount == 0, "LIQ resident count did not clear after ResolveQ push");
  expect(dut.io_liqOccupiedMask == 0, "LIQ row remained occupied after clear-resolved");
  expect(dut.io_liqResolvedMask == 0, "LIQ resolved mask remained set after clear-resolved");
  expect(!dut.io_liqClearResolvedPending, "LIQ clear-resolved request remained pending after acceptance");
  expect(dut.io_resolveQueueCount == 1, "ResolveQ lost the resolved record after LIQ clear");
  report.liq_clear_resolved = true;

  clear_inputs(dut);
  drive_mdb_store_probe(dut);
  dut.eval();
  expect(dut.io_mdbConflictValid, "MDB conflict detect did not see the ResolveQ row");
  expect(dut.io_mdbConflictFromResolveQueue, "MDB conflict did not come from ResolveQ");
  expect((dut.io_mdbResolveCandidateMask & 0x1) != 0, "MDB ResolveQ candidate mask did not select row zero");
  expect(dut.io_mdbConflictResolveIndex == 0, "MDB conflict selected the wrong ResolveQ index");
  expect(dut.io_mdbNukeFlush, "MDB conflict did not classify cross-BID conflict as nuke flush");
  expect(!dut.io_mdbInnerFlush, "MDB conflict incorrectly classified cross-BID conflict as inner flush");
  expect(dut.io_mdbConflictLoadLsId_valid, "MDB conflict record did not preserve load LSID valid bit");
  expect(dut.io_mdbConflictLoadLsId_value == kLoadLsId, "MDB conflict record did not preserve load LSID value");
  expect(dut.io_mdbConflictLoadPc == 0, "MDB conflict record load PC changed from fixture-owned zero PC");
  expect(dut.io_mdbFanoutRecordReady, "MDB fanout record queue was not ready");
  expect(dut.io_mdbFanoutRecordAccepted, "MDB fanout did not accept the conflict record");
  report.mdb_resolve_conflict = true;
  report.mdb_nuke_flush = true;
  report.mdb_fanout_record_accepted = true;
  report.mdb_resolve_candidate_mask = dut.io_mdbResolveCandidateMask;
  report.mdb_conflict_load_lsid = dut.io_mdbConflictLoadLsId_value;

  tick(dut, cycle);
  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutRecordProcessed, "MDB fanout did not process the accepted conflict record");
  expect(dut.io_mdbFanoutBmdbReportValid, "MDB fanout did not publish BMDB report intent");
  expect(dut.io_mdbFanoutBmdbLoadBid_valid, "MDB fanout BMDB load BID did not preserve valid bit");
  expect(dut.io_mdbFanoutBmdbLoadBid_value == kLoadBid, "MDB fanout BMDB load BID did not match resolved load");
  expect(dut.io_mdbFanoutBmdbStoreBid_valid, "MDB fanout BMDB store BID did not preserve valid bit");
  expect(dut.io_mdbFanoutBmdbStoreBid_value == kStoreBid, "MDB fanout BMDB store BID did not match conflict store");
  expect(dut.io_mdbFanoutBmdbStoreStid == 0, "MDB fanout BMDB STID did not match fixture thread");
  expect((dut.io_mdbFanoutSsitValidMask & 0x1) != 0, "MDB fanout SSIT did not allocate a record row");
  report.mdb_fanout_record_processed = true;
  report.mdb_bmdb_report = true;
  report.mdb_fanout_ssit_valid_mask = dut.io_mdbFanoutSsitValidMask;

  clear_inputs(dut);
  drive_mdb_store_probe(dut);
  dut.eval();
  expect(dut.io_mdbConflictValid, "MDB second conflict record lost ResolveQ conflict");
  expect(dut.io_mdbFanoutRecordAccepted, "MDB fanout did not accept the reinforcing conflict record");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutRecordProcessed, "MDB fanout did not process the reinforcing conflict record");
  expect(dut.io_mdbFanoutBmdbReportValid, "MDB fanout did not report BMDB intent for reinforcing record");
  expect((dut.io_mdbFanoutSsitValidMask & 0x1) != 0, "MDB fanout lost SSIT row after reinforcing record");
  report.mdb_fanout_record_reinforced = true;

  clear_inputs(dut);
  dut.io_mdbLookupValid = 1;
  dut.eval();
  expect(dut.io_mdbFanoutLookupReady, "MDB fanout lookup queue was not ready for first lookup");
  expect(dut.io_mdbFanoutLookupAccepted, "MDB fanout did not accept first lookup");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutLookupProcessed, "MDB fanout did not process first lookup");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutLuOutDequeued, "MDB fanout did not dequeue first LU lookup result");
  expect(dut.io_mdbFanoutSuOutDequeued, "MDB fanout did not dequeue first SU lookup result");
  expect(!dut.io_mdbFanoutLuOutHit, "first MDB lookup after nuke unexpectedly hit");
  expect(!dut.io_mdbFanoutSuOutHit, "first MDB SU lookup after nuke unexpectedly hit");
  expect(!dut.io_mdbFanoutSuMatchedStore, "first suppressed MDB lookup matched a store");
  expect(!dut.io_mdbFanoutSuWakeupValid, "first suppressed MDB lookup emitted wakeup");
  report.mdb_lookup_first_suppressed = true;

  clear_inputs(dut);
  dut.io_mdbLookupValid = 1;
  dut.eval();
  expect(dut.io_mdbFanoutLookupReady, "MDB fanout lookup queue was not ready for second lookup");
  expect(dut.io_mdbFanoutLookupAccepted, "MDB fanout did not accept second lookup");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutLookupProcessed, "MDB fanout did not process second lookup");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutLuOutDequeued, "MDB fanout did not dequeue second LU lookup result");
  expect(dut.io_mdbFanoutSuOutDequeued, "MDB fanout did not dequeue second SU lookup result");
  expect(dut.io_mdbFanoutLuOutHit, "reinforced MDB lookup did not hit for LU");
  expect(dut.io_mdbFanoutSuOutHit, "reinforced MDB lookup did not hit for SU");
  expect(dut.io_mdbFanoutSuMatchedStore, "MDB SU lookup did not match resident STQ row");
  expect(!dut.io_mdbFanoutSuStorePending, "MDB SU lookup reported ready resident store as pending");
  expect(dut.io_mdbFanoutSuWakeupValid, "MDB SU lookup did not emit wakeup");
  expect(dut.io_mdbFanoutSuWakeupStoreIndex == 0, "MDB SU wakeup selected the wrong STQ row");
  expect(dut.io_mdbFanoutSuWakeupBid_valid, "MDB SU wakeup did not preserve store BID valid bit");
  expect(dut.io_mdbFanoutSuWakeupBid_value == kStoreBid, "MDB SU wakeup did not preserve store BID");
  expect(dut.io_mdbLookupWaitPlanLookupHit, "MDB lookup wait planner did not see the LU hit");
  expect(dut.io_mdbLookupWaitPlanBlockedByNoTarget,
         "MDB lookup wait planner did not block after the source LIQ row had cleared");
  expect(!dut.io_mdbLookupWaitPlanWaitIntentValid,
         "MDB lookup wait planner unexpectedly found a current LIQ target after clear-resolved");
  expect(!dut.io_mdbLookupWaitPlanRequestValid,
         "MDB lookup wait planner emitted a mutation request without a current LIQ target");
  report.mdb_lookup_hit = true;
  report.mdb_su_wakeup = true;
  report.mdb_su_wakeup_store_index = dut.io_mdbFanoutSuWakeupStoreIndex;
  report.mdb_lookup_wait_plan_no_target = true;
  report.mdb_lookup_wait_plan_candidate_mask = dut.io_mdbLookupWaitPlanCandidateMask;

  clear_inputs(dut);
  drive_store(dut, kStoreAddr, kSecondStoreBid, kSecondStoreLsId);
  dut.eval();
  expect(dut.io_storeInsertAccepted, "second STA insert was not accepted");
  tick(dut, cycle);

  clear_inputs(dut);
  drive_load(dut, true, kSecondLoadBid, kSecondLoadLsId);
  dut.eval();
  expect(dut.io_forwardWaitBlocked, "second STA-only row did not block younger load");
  expect(dut.io_forwardWaitStoreValid, "second STA-only row did not produce wait-store key");
  expect(dut.io_forwardWaitStoreIndex == 1, "second STA-only wait-store key selected the wrong STQ index");
  expect(dut.io_waitSlotCaptureAccepted, "second STA-only wait-store was not captured");
  tick(dut, cycle);

  clear_inputs(dut);
  drive_store(dut, kStoreData, kSecondStoreBid, kSecondStoreLsId);
  dut.eval();
  expect(dut.io_storeInsertAccepted, "second STD merge was not accepted");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_waitStoreClear, "second store wakeup did not clear wait replay slot");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_relaunchQueueOutFire, "second relaunch queue did not fire into LIQ path");
  expect(dut.io_liqAllocAccepted, "second LIQ path did not accept relaunch candidate");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.io_liqRefillValid = 1;
  dut.io_liqRefillLineAddr = kLoadLineAddr;
  dut.io_liqRefillData[0] = static_cast<std::uint32_t>(kRefillDataLow);
  dut.io_liqRefillData[1] = static_cast<std::uint32_t>(kRefillDataLow >> 32);
  dut.eval();
  expect(dut.io_liqRefillAccepted, "second LIQ refill wakeup was not accepted");
  tick(dut, cycle);

  clear_inputs(dut);
  drive_scb_replay_wake(dut);
  dut.eval();
  expect((dut.io_liqReplayWakeMergeMask & 0x2) != 0,
         "second LIQ SCB replay wake did not merge row 1 before MDB mutation");
  expect((dut.io_liqReplayWakeCompletedMask & 0x2) != 0,
         "second LIQ SCB replay wake did not complete row 1 before MDB mutation");
  report.liq_replay_wake_completed_mask = dut.io_liqReplayWakeCompletedMask;
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect((dut.io_liqScbReturnedMask & 0x2) != 0,
         "second LIQ row did not retain SCB-return evidence after replay wake");
  expect((dut.io_liqSourcesReturnedMask & 0x2) != 0,
         "second LIQ row did not retain sources-returned evidence after replay wake");
  report.mdb_lookup_wait_plan_scb_evidence = true;
  report.liq_sources_returned_mask_before_mdb_write = dut.io_liqSourcesReturnedMask;
  report.liq_scb_returned_mask_before_mdb_write = dut.io_liqScbReturnedMask;

  clear_inputs(dut);
  drive_live_lookup_load_identity(dut);
  dut.eval();
  expect(dut.io_mdbFanoutLookupReady, "MDB fanout lookup queue was not ready for live second lookup");
  expect(dut.io_mdbFanoutLookupAccepted, "MDB fanout did not accept live second lookup");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.io_liqLaunchEnable = 1;
  dut.eval();
  expect(dut.io_mdbFanoutLookupProcessed, "MDB fanout did not process live second lookup");
  expect(dut.io_liqLaunchAccepted, "second LIQ launch was not accepted");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutLuOutDequeued, "MDB fanout did not dequeue live second LU lookup result");
  expect(dut.io_mdbFanoutSuOutDequeued, "MDB fanout did not dequeue live second SU lookup result");
  expect(dut.io_mdbFanoutLuOutHit, "live second MDB lookup did not hit for LU");
  expect(dut.io_mdbFanoutSuOutHit, "live second MDB lookup did not hit for SU");
  expect(dut.io_mdbLookupWaitPlanLookupHit, "MDB lookup wait planner did not see live second LU hit");
  expect(dut.io_mdbLookupWaitPlanCandidateMask == 0x2,
         "MDB lookup wait planner did not find the live Repick LIQ row" + mask_diag(dut));
  expect(dut.io_mdbLookupWaitPlanTargetIndex == 1,
         "MDB lookup wait planner selected the wrong live LIQ row" + mask_diag(dut));
  expect(dut.io_mdbLookupWaitPlanWaitIntentValid,
         "MDB lookup wait planner did not produce live second wait intent");
  expect(dut.io_mdbLookupWaitPlanRequestValid,
         "MDB lookup wait planner did not produce live second row mutation request");
  expect(dut.io_rowMutationBridgeValid,
         "MDB lookup wait planner request did not reach the LIQ row-mutation bridge");
  expect(dut.io_rowMutationTargetEvidenceValid,
         "live second row mutation did not see the SCB-returned target evidence");
  expect(!dut.io_rowMutationBlockedByControl,
         "live second row mutation was blocked even after SCB source-return evidence");
  expect(dut.io_rowMutationWriteEnable,
         "live second row mutation did not write after source-return evidence");
  expect(dut.io_rowMutationApplyValid,
         "live second row mutation did not apply after source-return evidence");
  report.mdb_lookup_wait_plan_live_target = true;
  report.mdb_lookup_wait_plan_request = true;
  report.mdb_lookup_wait_plan_bridge = true;
  report.mdb_lookup_wait_plan_control_blocked = false;
  report.mdb_lookup_wait_plan_write = true;
  report.mdb_lookup_wait_plan_apply = true;
  report.mdb_lookup_wait_plan_live_candidate_mask = dut.io_mdbLookupWaitPlanCandidateMask;
  report.mdb_lookup_wait_plan_live_target_index = dut.io_mdbLookupWaitPlanTargetIndex;

  tick(dut, cycle);
  clear_inputs(dut);
  dut.eval();
  expect((dut.io_liqWaitMask & 0x2) != 0,
         "MDB wait mutation did not return live row 1 to Wait");
  expect((dut.io_liqWaitStoreMask & 0x2) != 0,
         "MDB wait mutation did not arm wait-store state on live row 1");
  expect((dut.io_liqRepickMask & 0x2) == 0,
         "MDB wait mutation left live row 1 in Repick");
  expect((dut.io_liqScbReturnedMask & 0x2) == 0,
         "MDB wait mutation did not clear source-return state on live row 1");
  report.mdb_lookup_wait_plan_wait_status_after_write = true;
  report.liq_wait_mask_after_mdb_write = dut.io_liqWaitMask;
  report.liq_wait_store_mask_after_mdb_write = dut.io_liqWaitStoreMask;

  clear_inputs(dut);
  dut.io_mdbDeleteValid = 1;
  dut.io_mdbDeleteWaitStorePc = kStorePc;
  dut.eval();
  expect(dut.io_mdbFanoutDeleteReady, "MDB fanout delete queue was not ready for first delete");
  expect(dut.io_mdbFanoutDeleteAccepted, "MDB fanout did not accept first delete");
  tick(dut, cycle);

  clear_inputs(dut);
  dut.eval();
  expect(dut.io_mdbFanoutDeleteProcessed, "MDB fanout did not process first delete");
  expect(dut.io_mdbFanoutDeleteMatched, "MDB first delete did not match the learned SSIT row");
  expect(dut.io_mdbFanoutDeleteDroppedBelowStall, "MDB first delete did not report drop below stall threshold");
  expect(!dut.io_mdbFanoutDeleteReleased, "MDB first delete released the row too early");
  expect((dut.io_mdbFanoutSsitValidMask & 0x1) != 0, "MDB SSIT row was released after first delete");
  report.mdb_delete_accepted = true;
  report.mdb_delete_dropped_below_stall = true;

  for (int delete_index = 0; delete_index < 2; ++delete_index) {
    clear_inputs(dut);
    dut.io_mdbDeleteValid = 1;
    dut.io_mdbDeleteWaitStorePc = kStorePc;
    dut.eval();
    expect(dut.io_mdbFanoutDeleteReady, "MDB fanout delete queue was not ready for release delete");
    expect(dut.io_mdbFanoutDeleteAccepted, "MDB fanout did not accept release delete");
    tick(dut, cycle);

    clear_inputs(dut);
    dut.eval();
    expect(dut.io_mdbFanoutDeleteProcessed, "MDB fanout did not process release delete");
    expect(dut.io_mdbFanoutDeleteMatched, "MDB release delete did not match the learned SSIT row");
    if (delete_index == 0) {
      expect(dut.io_mdbFanoutDeleteDroppedBelowStall, "MDB second delete did not retain below-stall diagnostic");
      expect(!dut.io_mdbFanoutDeleteReleased, "MDB second delete released before zero-weight retry");
      expect((dut.io_mdbFanoutSsitValidMask & 0x1) != 0, "MDB SSIT row released before zero-weight retry");
    } else {
      expect(!dut.io_mdbFanoutDeleteDroppedBelowStall, "MDB release delete reported decay instead of release");
      expect(dut.io_mdbFanoutDeleteReleased, "MDB zero-weight delete did not release the SSIT row");
      expect((dut.io_mdbFanoutSsitValidMask & 0x1) == 0, "MDB SSIT row remained valid after release delete");
      report.mdb_delete_released = true;
    }
  }

  clear_inputs(dut);
  dut.io_resolveQueueRetireValid = 1;
  dut.io_resolveQueueRetireBid_valid = 1;
  dut.io_resolveQueueRetireBid_value = kLoadBid;
  dut.io_resolveQueueRetireLsId_valid = 1;
  dut.io_resolveQueueRetireLsId_value = kLoadLsId + 1;
  dut.eval();
  expect((dut.io_resolveQueueRetireMask & 0x1) != 0, "ResolveQ retire did not select the resolved row");
  expect(dut.io_resolveQueueRetireCount == 1, "ResolveQ retire count did not report one row");

  tick(dut, cycle);
  clear_inputs(dut);
  dut.eval();
  expect(dut.io_resolveQueueCount == 0, "ResolveQ row remained resident after retire watermark");
  expect(dut.io_resolveQueueValidMask == 0, "ResolveQ valid mask remained set after retire watermark");
  report.resolve_queue_retired = true;
  report.resolve_queue_count_after_retire = dut.io_resolveQueueCount;
}

void write_report(const std::string &path, const Report &report) {
  if (path.empty()) {
    return;
  }
  std::ofstream out(path);
  if (!out) {
    fail("failed to open report JSON: " + path);
  }
  out << "{\n"
      << "  \"schema\": \"linxcore.reduced_store_wait_replay_chisel_path.v1\",\n"
      << "  \"cycles\": " << report.cycles << ",\n"
      << "  \"ready_forward_observed\": " << (report.ready_forward_observed ? "true" : "false") << ",\n"
      << "  \"sta_wait_capture\": " << (report.sta_wait_capture ? "true" : "false") << ",\n"
      << "  \"not_ready_wake_blocked\": " << (report.not_ready_wake_blocked ? "true" : "false") << ",\n"
      << "  \"std_wake_clear\": " << (report.std_wake_clear ? "true" : "false") << ",\n"
      << "  \"relaunch_queue_fire\": " << (report.relaunch_queue_fire ? "true" : "false") << ",\n"
      << "  \"liq_alloc\": " << (report.liq_alloc ? "true" : "false") << ",\n"
      << "  \"liq_refill\": " << (report.liq_refill ? "true" : "false") << ",\n"
      << "  \"liq_launch_valid\": " << (report.liq_launch_valid ? "true" : "false") << ",\n"
      << "  \"liq_launch_accepted\": " << (report.liq_launch_accepted ? "true" : "false") << ",\n"
      << "  \"liq_e4_update\": " << (report.liq_e4_update ? "true" : "false") << ",\n"
      << "  \"liq_lhq_record\": " << (report.liq_lhq_record ? "true" : "false") << ",\n"
      << "  \"liq_resolved\": " << (report.liq_resolved ? "true" : "false") << ",\n"
      << "  \"resolve_queue_push\": " << (report.resolve_queue_push ? "true" : "false") << ",\n"
      << "  \"liq_clear_resolved\": " << (report.liq_clear_resolved ? "true" : "false") << ",\n"
      << "  \"mdb_resolve_conflict\": " << (report.mdb_resolve_conflict ? "true" : "false") << ",\n"
      << "  \"mdb_nuke_flush\": " << (report.mdb_nuke_flush ? "true" : "false") << ",\n"
      << "  \"mdb_fanout_record_accepted\": " << (report.mdb_fanout_record_accepted ? "true" : "false") << ",\n"
      << "  \"mdb_fanout_record_processed\": " << (report.mdb_fanout_record_processed ? "true" : "false") << ",\n"
      << "  \"mdb_bmdb_report\": " << (report.mdb_bmdb_report ? "true" : "false") << ",\n"
      << "  \"mdb_fanout_record_reinforced\": " << (report.mdb_fanout_record_reinforced ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_first_suppressed\": " << (report.mdb_lookup_first_suppressed ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_hit\": " << (report.mdb_lookup_hit ? "true" : "false") << ",\n"
      << "  \"mdb_su_wakeup\": " << (report.mdb_su_wakeup ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_no_target\": " << (report.mdb_lookup_wait_plan_no_target ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_live_target\": "
      << (report.mdb_lookup_wait_plan_live_target ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_request\": "
      << (report.mdb_lookup_wait_plan_request ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_bridge\": "
      << (report.mdb_lookup_wait_plan_bridge ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_control_blocked\": "
      << (report.mdb_lookup_wait_plan_control_blocked ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_scb_evidence\": "
      << (report.mdb_lookup_wait_plan_scb_evidence ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_write\": "
      << (report.mdb_lookup_wait_plan_write ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_apply\": "
      << (report.mdb_lookup_wait_plan_apply ? "true" : "false") << ",\n"
      << "  \"mdb_lookup_wait_plan_wait_status_after_write\": "
      << (report.mdb_lookup_wait_plan_wait_status_after_write ? "true" : "false") << ",\n"
      << "  \"mdb_delete_accepted\": " << (report.mdb_delete_accepted ? "true" : "false") << ",\n"
      << "  \"mdb_delete_dropped_below_stall\": " << (report.mdb_delete_dropped_below_stall ? "true" : "false") << ",\n"
      << "  \"mdb_delete_released\": " << (report.mdb_delete_released ? "true" : "false") << ",\n"
      << "  \"resolve_queue_retired\": " << (report.resolve_queue_retired ? "true" : "false") << ",\n"
      << "  \"youngest_store_lsid\": " << report.youngest_store_lsid << ",\n"
      << "  \"launch_load_lsid\": " << report.launch_load_lsid << ",\n"
      << "  \"resolve_queue_count\": " << report.resolve_queue_count << ",\n"
      << "  \"resolve_queue_count_after_retire\": " << report.resolve_queue_count_after_retire << ",\n"
      << "  \"mdb_resolve_candidate_mask\": " << report.mdb_resolve_candidate_mask << ",\n"
      << "  \"mdb_conflict_load_lsid\": " << report.mdb_conflict_load_lsid << ",\n"
      << "  \"mdb_fanout_ssit_valid_mask\": " << report.mdb_fanout_ssit_valid_mask << ",\n"
      << "  \"mdb_su_wakeup_store_index\": " << report.mdb_su_wakeup_store_index << ",\n"
      << "  \"mdb_lookup_wait_plan_candidate_mask\": " << report.mdb_lookup_wait_plan_candidate_mask << ",\n"
      << "  \"mdb_lookup_wait_plan_live_candidate_mask\": "
      << report.mdb_lookup_wait_plan_live_candidate_mask << ",\n"
      << "  \"mdb_lookup_wait_plan_live_target_index\": "
      << report.mdb_lookup_wait_plan_live_target_index << ",\n"
      << "  \"liq_replay_wake_completed_mask\": " << report.liq_replay_wake_completed_mask << ",\n"
      << "  \"liq_sources_returned_mask_before_mdb_write\": "
      << report.liq_sources_returned_mask_before_mdb_write << ",\n"
      << "  \"liq_scb_returned_mask_before_mdb_write\": "
      << report.liq_scb_returned_mask_before_mdb_write << ",\n"
      << "  \"liq_wait_mask_after_mdb_write\": " << report.liq_wait_mask_after_mdb_write << ",\n"
      << "  \"liq_wait_store_mask_after_mdb_write\": "
      << report.liq_wait_store_mask_after_mdb_write << ",\n"
      << "  \"e4_cycles_after_launch\": " << report.e4_cycles_after_launch << "\n"
      << "}\n";
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const Args args = parse_args(argc, argv);

  VReducedStoreWaitReplayChiselPathProbe dut;
  std::uint64_t cycle = 0;
  Report report;

  run_ready_store_negative(dut, cycle, report);
  run_sta_only_replay_path(dut, cycle, report);
  report.cycles = cycle;
  write_report(args.report_json, report);

  std::cout << "reduced-store-wait-replay-chisel-path: pass"
            << " cycles=" << report.cycles
            << " youngest_store_lsid=" << report.youngest_store_lsid
            << " launch_load_lsid=" << report.launch_load_lsid
            << " e4_cycles_after_launch=" << report.e4_cycles_after_launch << "\n";
  return 0;
}
