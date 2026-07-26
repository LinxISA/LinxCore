#include "VIfuLineMemoryBridgeProbe.h"
#include "verilated.h"

#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace {
vluint64_t sim_time = 0;

void eval(VIfuLineMemoryBridgeProbe &dut) { dut.eval(); }

void tick(VIfuLineMemoryBridgeProbe &dut) {
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

void enqueue(VIfuLineMemoryBridgeProbe &dut,
             uint64_t transaction,
             uint64_t packet,
             uint64_t sequence,
             uint64_t line_va,
             uint64_t line_pa) {
  dut.io_transactionId = transaction;
  dut.io_packetUid = packet;
  dut.io_fetchSeq = sequence;
  dut.io_lineVa = line_va;
  dut.io_linePa = line_pa;
  dut.io_requestValid = 1;
  eval(dut);
  expect(dut.io_requestReady, "IFU request must allocate a free bridge row");
  tick(dut);
  dut.io_requestValid = 0;
  eval(dut);
}
}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VIfuLineMemoryBridgeProbe dut;
  dut.io_requestValid = 0;
  dut.io_memoryRequestReady = 0;
  dut.io_memoryResponseValid = 0;
  dut.io_memoryResponseTag = 0;
  dut.io_memoryResponseData = 0;
  dut.io_refillReady = 0;
  dut.reset = 1;
  tick(dut);
  dut.reset = 0;

  enqueue(dut, 0x11, 0x21, 0x31, 0x1000, 0x8000);
  expect(dut.io_memoryRequestValid, "first tagged memory request must be visible");
  const uint64_t first_tag = dut.io_memoryRequestTag;
  expect(dut.io_memoryRequestLinePa == 0x8000, "first line address must match");

  enqueue(dut, 0x12, 0x22, 0x32, 0x1040, 0x9000);
  expect(dut.io_memoryRequestTag == first_tag,
         "blocked request tag must remain stable across a younger allocation");
  expect(dut.io_memoryRequestLinePa == 0x8000,
         "blocked request payload must remain stable");

  dut.io_memoryRequestReady = 1;
  tick(dut);
  dut.io_memoryRequestReady = 0;
  eval(dut);
  expect(dut.io_memoryRequestValid, "second tagged request must follow the first");
  const uint64_t second_tag = dut.io_memoryRequestTag;
  expect(second_tag != first_tag, "live requests must use distinct memory tags");
  expect(dut.io_memoryRequestLinePa == 0x9000, "second line address must match");
  dut.io_memoryRequestReady = 1;
  tick(dut);
  dut.io_memoryRequestReady = 0;

  dut.io_refillReady = 1;
  dut.io_memoryResponseValid = 1;
  dut.io_memoryResponseTag = second_tag;
  dut.io_memoryResponseData = 0xfeed;
  eval(dut);
  expect(dut.io_memoryResponseReady, "matching response must enter a retained row");
  tick(dut);
  dut.io_memoryResponseValid = 0;
  eval(dut);
  expect(dut.io_refillValid, "out-of-order second response must reconstruct a refill");
  expect(dut.io_refillTransactionId == 0x12, "transaction identity must be retained");
  expect(dut.io_refillPacketUid == 0x22, "packet identity must be retained");
  expect(dut.io_refillFetchSeq == 0x32, "fetch sequence must be retained");
  expect(dut.io_refillLineVa == 0x1040 && dut.io_refillLinePa == 0x9000,
         "virtual and physical lines must be reconstructed independently");
  expect(dut.io_refillData == 0xfeed, "refill data must pass through");
  tick(dut);

  dut.io_memoryResponseValid = 1;
  dut.io_memoryResponseTag = second_tag + 9;
  dut.io_memoryResponseData = 0xdead;
  eval(dut);
  expect(dut.io_staleResponse && dut.io_memoryResponseReady,
         "stale tags must drain without reaching IFU");
  expect(!dut.io_refillValid, "stale response must not fabricate a refill");
  tick(dut);

  dut.io_memoryResponseTag = first_tag;
  dut.io_memoryResponseData = 0xbeef;
  dut.io_refillReady = 0;
  eval(dut);
  expect(dut.io_memoryResponseReady, "valid response must enter retained storage");
  tick(dut);
  dut.io_memoryResponseValid = 0;
  eval(dut);
  expect(dut.io_refillValid,
         "retained response must remain visible while the IFU refill sink is blocked");
  expect(dut.io_refillTransactionId == 0x11 && dut.io_refillPacketUid == 0x21 &&
             dut.io_refillFetchSeq == 0x31,
         "held response must preserve every independent identity");
  tick(dut);
  expect(dut.io_outstandingCount == 1, "blocked response must retain its row");
  dut.io_refillReady = 1;
  eval(dut);
  tick(dut);
  eval(dut);
  expect(dut.io_outstandingCount == 0, "all tagged requests must retire exactly once");

  std::cout << "ok: tagged IFU line bridge retained exact identities out of order\n";
  return 0;
}
