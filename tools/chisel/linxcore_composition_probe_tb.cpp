#include "VLinxCoreCompositionProbe.h"
#include "verilated.h"

#include <cstdlib>
#include <cstdint>
#include <iostream>

namespace {
vluint64_t sim_time = 0;

void eval(VLinxCoreCompositionProbe &dut) { dut.eval(); }

void tick(VLinxCoreCompositionProbe &dut) {
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

void driveIdle(VLinxCoreCompositionProbe &dut) {
  dut.io_itlbRefillValid = 0;
  dut.io_startValid = 0;
  dut.io_startPc = 0x1200;
  dut.io_boundaryMode = 0;
  dut.io_memoryRequestReady = 0;
  dut.io_memoryResponseValid = 0;
  dut.io_memoryResponseTag = 0;
  dut.io_memoryResponseLinePa = 0;
  dut.io_decodedReady = 0;
  dut.io_validateMispredict = 0;
  dut.io_correctedTarget = 0;
}

void resetDut(VLinxCoreCompositionProbe &dut) {
  driveIdle(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  eval(dut);
}

void preloadAndStart(VLinxCoreCompositionProbe &dut) {
  dut.io_itlbRefillValid = 1;
  tick(dut);
  dut.io_itlbRefillValid = 0;
  dut.io_startValid = 1;
  tick(dut);
  dut.io_startValid = 0;
}

std::uint64_t issueFirstLine(VLinxCoreCompositionProbe &dut) {
  for (int cycle = 0; cycle < 80 && !dut.io_memoryRequestValid; ++cycle) tick(dut);
  expect(dut.io_memoryRequestValid, "composition must issue a tagged line request");
  expect(dut.io_memoryRequestLinePa == 0x2200,
         "translated line request must use the expected physical line");
  const std::uint64_t tag = dut.io_memoryRequestTag;
  tick(dut);
  tick(dut);
  expect(dut.io_memoryRequestValid && dut.io_memoryRequestTag == tag,
         "blocked memory request must retain its tag");
  expect(dut.io_memoryRequestLinePa == 0x2200,
         "blocked memory request must retain its line address");
  dut.io_memoryRequestReady = 1;
  tick(dut);
  dut.io_memoryRequestReady = 0;
  return tag;
}

void returnFirstLine(VLinxCoreCompositionProbe &dut, std::uint64_t tag) {
  dut.io_memoryResponseValid = 1;
  dut.io_memoryResponseTag = tag;
  dut.io_memoryResponseLinePa = 0x2200;
  eval(dut);
  expect(dut.io_memoryResponseReady, "tagged response must be accepted");
  expect(!dut.io_staleMemoryResponse, "matching response must not be stale");
  tick(dut);
  dut.io_memoryResponseValid = 0;
}

void waitForDecoded(VLinxCoreCompositionProbe &dut) {
  for (int cycle = 0; cycle < 200 && !dut.io_decodedValid; ++cycle) tick(dut);
  expect(dut.io_decodedValid, "D1 output must become valid");
  expect(dut.io_decodedPredictionFinal, "D1 output must carry final B-F4 prediction");
}
}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VLinxCoreCompositionProbe dut;

  resetDut(dut);
  preloadAndStart(dut);
  const auto denseTag = issueFirstLine(dut);
  returnFirstLine(dut, denseTag);
  waitForDecoded(dut);
  expect(dut.io_decodedValidMask == 0xf, "dense line must produce a four-wide D1 group");
  expect(dut.io_decodedPc0 == 0x1200 && dut.io_decodedPc3 == 0x1206,
         "four-wide D1 PCs must remain ordered");
  expect(dut.io_decodedInsn0 == 0x1048, "D1 must expose the fixed 64-bit instruction container");
  dut.io_decodedReady = 1;
  tick(dut);
  dut.io_decodedReady = 0;

  resetDut(dut);
  dut.io_boundaryMode = 1;
  preloadAndStart(dut);
  const auto boundaryTag = issueFirstLine(dut);
  returnFirstLine(dut, boundaryTag);
  waitForDecoded(dut);
  expect(dut.io_decodedValidMask == 0x1,
         "a direct BSTART must publish alone before its final-path body refetch");
  expect(dut.io_decodedPredictionKind == 4,
         "BSTART direct block must reach D1 as a direct final prediction");
  const std::uint64_t correctedTarget = dut.io_decodedPredictionTarget + 0x40;
  dut.io_decodedReady = 1;
  tick(dut);
  dut.io_decodedReady = 0;

  dut.io_correctedTarget = correctedTarget;
  dut.io_validateMispredict = 1;
  eval(dut);
  expect(dut.io_validationReady, "captured direct block must accept Dispatch validation");
  tick(dut);
  dut.io_validateMispredict = 0;

  for (int cycle = 0; cycle < 40 && !dut.io_canonicalFlushValid; ++cycle) tick(dut);
  expect(dut.io_canonicalFlushValid, "target mismatch must produce canonical BRU recovery");
  expect(dut.io_canonicalFlushReason == 4, "recovery reason must be BruRecovery");
  expect(dut.io_canonicalRestartPc == correctedTarget,
         "canonical recovery must restart at the validated target");
  expect(dut.io_canonicalNewEpoch == 2,
         "B-F4 correction and backend recovery must allocate consecutive epochs");

  std::cout << "ok: IFU composition closes tagged fetch, D1, and BRU recovery\n";
  return 0;
}
