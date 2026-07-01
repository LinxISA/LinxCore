#include "VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop.h"
#include "verilated.h"

#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace {

constexpr std::uint64_t kStartPc = 0x40005504ULL;
constexpr std::uint64_t kFirstWindow = 0x0000918710460800ULL;
constexpr std::uint64_t kInitialSp = 0x4fffddb0ULL;

void clear_inputs(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
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

void tick(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

void reset(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
}

[[noreturn]] void fail(const char *message, const VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  std::cerr << "marker-row smoke failed: " << message
            << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
            << " selectedBlockBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_selectedBlockBid)
            << " blockMarkerSkipFire=" << std::dec
            << static_cast<unsigned>(dut.io_blockMarkerSkipFire)
            << " decRenPushFire=" << static_cast<unsigned>(dut.io_decRenPushFire)
            << " robAllocFire=" << static_cast<unsigned>(dut.io_robAllocFire)
            << " decRenHeadPc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_decRenHeadPc)
            << std::dec
            << " decRenCount=" << static_cast<unsigned>(dut.io_decRenCount)
            << "\n";
  std::exit(1);
}

void init_rf(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  clear_inputs(dut);
  dut.io_rfInitValid = 1;
  dut.io_rfInitArchTag = 1;
  dut.io_rfInitData = kInitialSp;
  tick(dut);
}

void start_source(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  clear_inputs(dut);
  dut.io_startValid = 1;
  dut.io_startPc = kStartPc;
  tick(dut);
}

void accept_fetch_request(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  for (int cycle = 0; cycle < 16; ++cycle) {
    clear_inputs(dut);
    dut.io_fetchReqReady = 1;
    dut.eval();
    if (dut.io_fetchReqValid) {
      if (!dut.io_sourceReqFire || dut.io_fetchReqPc != kStartPc) {
        fail("fetch request did not match start PC", dut);
      }
      tick(dut);
      return;
    }
    tick(dut);
  }
  fail("fetch source did not request the start PC", dut);
}

void accept_fetch_response(VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop &dut) {
  clear_inputs(dut);
  dut.io_fetchRespValid = 1;
  dut.io_fetchRespWindow = kFirstWindow;
  dut.eval();
  if (!dut.io_fetchRespReady || !dut.io_sourceRespFire) {
    fail("fetch response was not accepted", dut);
  }
  tick(dut);
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);

  VLinxCoreFrontendFetchRfAluMarkerRowsTraceTop dut;
  reset(dut);
  init_rf(dut);
  start_source(dut);
  accept_fetch_request(dut);
  accept_fetch_response(dut);

  int selected_rows = 0;
  std::uint64_t marker_bid = 0;

  for (int cycle = 0; cycle < 128; ++cycle) {
    clear_inputs(dut);
    dut.eval();
    if (dut.io_rfStateError || dut.io_commitContractError) {
      fail("top reported a state or commit contract error", dut);
    }

    if (dut.io_denseSlotQueueOutFire && dut.io_selectedValid) {
      if (dut.io_blockMarkerSkipFire) {
        fail("marker-row wrapper still consumed the marker as a skip row", dut);
      }
      if (!dut.io_decRenPushFire || !dut.io_robAllocFire) {
        fail("selected row did not reserve ROB state", dut);
      }
      if (selected_rows == 0) {
        marker_bid = dut.io_selectedBlockBid;
      } else if (selected_rows == 1) {
        if (dut.io_selectedBlockBid != marker_bid) {
          fail("following scalar row did not reuse marker BID", dut);
        }
        std::cout << "marker-row-smoke-status=pass"
                  << " marker_bid=0x" << std::hex
                  << static_cast<unsigned long long>(marker_bid)
                  << std::dec << "\n";
        return 0;
      }
      ++selected_rows;
    }
    tick(dut);
  }

  fail("did not observe marker row followed by scalar row", dut);
}
