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
constexpr std::uint8_t kStoreBid = 1;
constexpr std::uint8_t kStoreLsId = 1;
constexpr std::uint8_t kLoadBid = 2;
constexpr std::uint8_t kLoadLsId = 3;

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

void drive_store(VReducedStoreWaitReplayChiselPathProbe &dut, std::uint8_t store_type) {
  dut.io_storeInsertValid = 1;
  dut.io_storeInsert_storeType = store_type;
  dut.io_storeInsert_bid_valid = 1;
  dut.io_storeInsert_bid_wrap = 0;
  dut.io_storeInsert_bid_value = kStoreBid;
  dut.io_storeInsert_gid_valid = 1;
  dut.io_storeInsert_gid_value = 0;
  dut.io_storeInsert_rid_valid = 1;
  dut.io_storeInsert_rid_value = 0;
  dut.io_storeInsert_lsId_valid = 1;
  dut.io_storeInsert_lsId_wrap = 0;
  dut.io_storeInsert_lsId_value = kStoreLsId;
  dut.io_storeInsert_pc = kStorePc;
  dut.io_storeInsert_addr = kLoadAddr;
  dut.io_storeInsert_data = kStoreDataValue;
  dut.io_storeInsert_size = 8;
  dut.io_storeInsert_scalarIex = 1;
}

void drive_load(VReducedStoreWaitReplayChiselPathProbe &dut, bool capture_enable) {
  dut.io_loadValid = 1;
  dut.io_loadAddr = kLoadAddr;
  dut.io_loadSize = 8;
  dut.io_loadBid_valid = 1;
  dut.io_loadBid_wrap = 0;
  dut.io_loadBid_value = kLoadBid;
  dut.io_loadLsId_valid = 1;
  dut.io_loadLsId_wrap = 0;
  dut.io_loadLsId_value = kLoadLsId;
  dut.io_baseLoadData = kBaseData;
  dut.io_captureEnable = capture_enable ? 1 : 0;
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
  std::uint32_t youngest_store_lsid = 0;
  std::uint32_t launch_load_lsid = 0;
  std::uint32_t resolve_queue_count = 0;
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
      << "  \"youngest_store_lsid\": " << report.youngest_store_lsid << ",\n"
      << "  \"launch_load_lsid\": " << report.launch_load_lsid << ",\n"
      << "  \"resolve_queue_count\": " << report.resolve_queue_count << ",\n"
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
