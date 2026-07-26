#include "VIfuBackendFeedbackBridgeProbe.h"
#include "verilated.h"

#include <cstdlib>
#include <iostream>

namespace {
vluint64_t sim_time = 0;

void eval(VIfuBackendFeedbackBridgeProbe &dut) { dut.eval(); }

void tick(VIfuBackendFeedbackBridgeProbe &dut) {
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

void enqueue(VIfuBackendFeedbackBridgeProbe &dut, int mode) {
  dut.io_mode = mode;
  dut.io_inValid = 1;
  eval(dut);
  expect(dut.io_inReady, "validation input must accept");
  tick(dut);
  dut.io_inValid = 0;
  eval(dut);
  expect(dut.io_pending, "accepted validation must become pending");
}
}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VIfuBackendFeedbackBridgeProbe dut;
  dut.io_inValid = 0;
  dut.io_mode = 0;
  dut.io_resolveReady = 1;
  dut.io_recoveryReady = 1;
  dut.reset = 1;
  tick(dut);
  dut.reset = 0;

  enqueue(dut, 0);
  expect(dut.io_resolveValid, "correct conditional result must train");
  expect(!dut.io_recoveryValid && !dut.io_mispredict,
         "conditional target difference alone must not recover");
  expect(dut.io_resolveTarget == 0x4000, "training must carry actual target");
  tick(dut);

  dut.io_recoveryReady = 0;
  enqueue(dut, 1);
  expect(dut.io_mispredict, "conditional direction difference must mispredict");
  expect(!dut.io_resolveValid && dut.io_recoveryValid,
         "blocked recovery must also block training advancement");
  tick(dut);
  expect(dut.io_pending, "mispredict must remain retained under backpressure");
  dut.io_recoveryReady = 1;
  eval(dut);
  expect(dut.io_resolveValid && dut.io_recoveryValid,
         "training and recovery must become valid together");
  expect(dut.io_restartPc == 0x5000, "taken correction must restart at actual target");
  expect(dut.io_recoveryReason == 4, "recovery must be classified as BRU recovery");
  expect(dut.io_ghrAppendValid && dut.io_ghrAppendTaken,
         "conditional recovery must append actual direction");
  tick(dut);

  enqueue(dut, 2);
  expect(dut.io_resolveValid && dut.io_recoveryValid,
         "call target mismatch must recover at Dispatch");
  expect(dut.io_restartPc == 0x3100, "call must restart at actual static target");
  expect(dut.io_rasUpdate == 1, "call recovery must push the return address");
  tick(dut);

  enqueue(dut, 3);
  expect(dut.io_resolveValid && dut.io_recoveryValid,
         "return target mismatch must recover at BRU E1");
  expect(dut.io_restartPc == 0x7100, "return must restart at actual SETC target");
  expect(dut.io_rasUpdate == 2, "return recovery must pop the RAS");

  std::cout << "ok: type-specific IFU backend feedback is atomic and exact-keyed\n";
  return 0;
}
