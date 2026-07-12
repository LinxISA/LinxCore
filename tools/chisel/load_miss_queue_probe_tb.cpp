#include "VLoadMissQueueProbe.h"
#include "verilated.h"

#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace {

vluint64_t sim_time = 0;

void eval(VLoadMissQueueProbe &dut) {
  dut.eval();
}

void tick(VLoadMissQueueProbe &dut) {
  dut.clock = 0;
  eval(dut);
  ++sim_time;
  dut.clock = 1;
  eval(dut);
  ++sim_time;
  dut.clock = 0;
  eval(dut);
}

[[noreturn]] void fail(const char *message) {
  std::cerr << "FAIL: " << message << " at t=" << sim_time << '\n';
  std::exit(1);
}

void expect(bool condition, const char *message) {
  if (!condition) fail(message);
}

void clear_inputs(VLoadMissQueueProbe &dut) {
  dut.io_hardFlush = 0;
  dut.io_preciseFlushValid = 0;
  dut.io_preciseFlushStid = 0;
  dut.io_preciseFlushBid = 0;
  dut.io_missValid = 0;
  dut.io_missIndex = 0;
  dut.io_missLineAddr = 0;
  dut.io_missStid = 0;
  dut.io_missBid = 0;
  dut.io_missLsIdFull = 0;
  dut.io_requestReady = 0;
  dut.io_responseValid = 0;
  dut.io_responseMissValid = 0;
  dut.io_responseMissSlot = 0;
  dut.io_responseMissGeneration = 0;
  dut.io_responseLineAddr = 0;
  dut.io_responseIsRead = 1;
  dut.io_refillReady = 1;
  for (int i = 0; i < 16; ++i) dut.io_responseData[i] = 0;
}

void drive_miss(VLoadMissQueueProbe &dut, uint32_t index, uint64_t line,
                uint32_t stid, uint32_t bid, uint64_t lsid) {
  dut.io_missValid = 1;
  dut.io_missIndex = index;
  dut.io_missLineAddr = line;
  dut.io_missStid = stid;
  dut.io_missBid = bid;
  dut.io_missLsIdFull = lsid;
  eval(dut);
  expect(dut.io_missReady, "miss must be ready");
  expect(dut.io_missAccepted, "miss must be accepted");
  tick(dut);
  dut.io_missValid = 0;
  eval(dut);
}

}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VLoadMissQueueProbe dut;
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  eval(dut);
  expect(!dut.io_pending, "queue must reset empty");

  drive_miss(dut, 0, 0x1000, 0, 1, 0x101);
  expect(dut.io_requestValid, "first unique line must reach request head");
  const uint32_t first_slot = dut.io_requestMissSlot;
  const bool first_generation = dut.io_requestMissGeneration;
  expect(dut.io_requestLineAddr == 0x1000, "request line must be stable");
  tick(dut);
  expect(dut.io_requestValid && dut.io_requestMissSlot == first_slot &&
         dut.io_requestMissGeneration == first_generation &&
         dut.io_requestLineAddr == 0x1000,
         "backpressure must retain exact request");

  dut.io_requestReady = 1;
  eval(dut);
  expect(dut.io_requestAccepted, "request must fire when ready");
  tick(dut);
  dut.io_requestReady = 0;
  expect((dut.io_issuedMask & (1u << first_slot)) != 0,
         "accepted request must mark entry issued");

  drive_miss(dut, 1, 0x1000, 1, 2, 0x202);
  expect(dut.io_dependentCount == 2, "same line must coalesce two dependents");

  dut.io_preciseFlushValid = 1;
  dut.io_preciseFlushStid = 0;
  dut.io_preciseFlushBid = 0;
  eval(dut);
  expect(dut.io_precisePruneCount == 1, "precise flush must prune only STID 0");
  tick(dut);
  dut.io_preciseFlushValid = 0;
  expect(dut.io_dependentCount == 1, "cross-STID dependent must survive");

  dut.io_responseValid = 1;
  dut.io_responseMissValid = 1;
  dut.io_responseMissSlot = first_slot;
  dut.io_responseMissGeneration = first_generation;
  dut.io_responseLineAddr = 0x1000;
  dut.io_responseIsRead = 1;
  dut.io_responseData[0] = 0x44332211u;
  dut.io_refillReady = 0;
  eval(dut);
  expect(!dut.io_responseReady && dut.io_responseBlockedByRefill &&
             !dut.io_refillValid,
         "exact read response must wait for retained refill capacity");
  tick(dut);
  expect((dut.io_issuedMask & (1u << first_slot)) != 0,
         "refill backpressure must preserve the issued miss");
  dut.io_refillReady = 1;
  eval(dut);
  expect(dut.io_responseReady && dut.io_responseMatched,
         "exact response must match issued entry");
  expect(dut.io_refillValid && dut.io_refillLineAddr == 0x1000,
         "exact read response must publish refill");
  tick(dut);
  dut.io_responseValid = 0;
  dut.io_responseMissValid = 0;
  expect(!dut.io_pending, "matching response must free entry");

  dut.io_responseValid = 1;
  eval(dut);
  expect(dut.io_responseStale && dut.io_protocolError,
         "stale response must be consumed and diagnosed");
  tick(dut);
  dut.io_responseValid = 0;

  drive_miss(dut, 4, 0x1800, 0, 2, 0x282);
  const uint32_t malformed_slot = dut.io_requestMissSlot;
  const bool malformed_generation = dut.io_requestMissGeneration;
  dut.io_requestReady = 1;
  eval(dut);
  expect(dut.io_requestAccepted, "malformed-response setup request must issue");
  tick(dut);
  dut.io_requestReady = 0;

  dut.io_responseValid = 1;
  dut.io_responseMissValid = 1;
  dut.io_responseMissSlot = malformed_slot;
  dut.io_responseMissGeneration = malformed_generation;
  dut.io_responseLineAddr = 0x1800;
  dut.io_responseIsRead = 0;
  eval(dut);
  expect(dut.io_responseMatched && dut.io_protocolError && !dut.io_refillValid,
         "exact non-read response must diagnose without refill");
  tick(dut);
  expect((dut.io_issuedMask & (1u << malformed_slot)) != 0,
         "exact non-read response must preserve the live miss");

  dut.io_responseMissValid = 0;
  dut.io_responseIsRead = 1;
  eval(dut);
  expect(dut.io_responseStale && dut.io_protocolError && !dut.io_refillValid,
         "invalid miss identity must diagnose without refill");
  tick(dut);
  expect((dut.io_issuedMask & (1u << malformed_slot)) != 0,
         "invalid miss identity must preserve the live miss");

  dut.io_responseMissValid = 1;
  eval(dut);
  expect(dut.io_responseMatched && dut.io_refillValid,
         "valid read retry must still complete the preserved miss");
  tick(dut);
  dut.io_responseValid = 0;
  dut.io_responseMissValid = 0;
  expect(!dut.io_pending, "valid read retry must release the preserved miss");

  drive_miss(dut, 2, 0x2000, 0, 3, 0x303);
  dut.io_hardFlush = 1;
  tick(dut);
  dut.io_hardFlush = 0;
  eval(dut);
  expect(!dut.io_requestValid, "hard-flushed unissued miss must not issue");
  tick(dut);
  expect(!dut.io_pending, "unissued empty entry must drain and free");

  drive_miss(dut, 3, 0x3000, 0, 4, 0x404);
  const uint32_t orphan_slot = dut.io_requestMissSlot;
  const bool orphan_generation = dut.io_requestMissGeneration;
  dut.io_requestReady = 1;
  eval(dut);
  expect(dut.io_requestAccepted, "orphan setup request must issue");
  tick(dut);
  dut.io_requestReady = 0;
  dut.io_hardFlush = 1;
  tick(dut);
  dut.io_hardFlush = 0;
  eval(dut);
  expect((dut.io_orphanMask & (1u << orphan_slot)) != 0,
         "issued empty entry must remain as orphan");

  dut.io_responseValid = 1;
  dut.io_responseMissValid = 1;
  dut.io_responseMissSlot = orphan_slot;
  dut.io_responseMissGeneration = orphan_generation;
  dut.io_responseLineAddr = 0x3000;
  eval(dut);
  expect(dut.io_responseMatched, "orphan must drain only on exact response");
  tick(dut);
  dut.io_responseValid = 0;
  dut.io_responseMissValid = 0;
  expect(!dut.io_pending, "orphan response must release entry");

  std::cout << "ok: load miss queue generated-RTL probe passed\n";
  return 0;
}
