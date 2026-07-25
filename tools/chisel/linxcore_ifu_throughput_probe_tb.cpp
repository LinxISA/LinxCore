#include "VLinxCoreIfuThroughputProbe.h"
#include "verilated.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <iostream>

namespace {
vluint64_t sim_time = 0;

void eval(VLinxCoreIfuThroughputProbe &dut) { dut.eval(); }

void tick(VLinxCoreIfuThroughputProbe &dut) {
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

void pulse_start(VLinxCoreIfuThroughputProbe &dut, std::uint64_t pc) {
  dut.io_startValid = 1;
  dut.io_startPc = pc;
  tick(dut);
  dut.io_startValid = 0;
}
}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VLinxCoreIfuThroughputProbe dut;
  dut.io_startValid = 0;
  dut.io_startPc = 0x1000;
  dut.io_d1Ready = 1;
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;

  pulse_start(dut, 0x1000);
  int warm_cycles = 0;
  while (dut.io_lineRefillCount < 4 && warm_cycles < 800) {
    tick(dut);
    ++warm_cycles;
  }
  expect(dut.io_lineRefillCount >= 4, "four cachelines must warm before measurement");

  pulse_start(dut, 0x1000);
  int fill_cycles = 0;
  int join_peak = 0;
  int context_peak = 0;
  while (!dut.io_d1Valid && fill_cycles < 160) {
    join_peak = std::max(join_peak, static_cast<int>(dut.io_joinCount));
    context_peak = std::max(context_peak, static_cast<int>(dut.io_lineContextCount));
    tick(dut);
    ++fill_cycles;
  }
  expect(dut.io_d1Valid, "warmed IFU must reach D1");

  for (int group = 0; group < 32; ++group) {
    join_peak = std::max(join_peak, static_cast<int>(dut.io_joinCount));
    context_peak = std::max(context_peak, static_cast<int>(dut.io_lineContextCount));
    expect(dut.io_d1Valid && dut.io_d1Fire, "eligible D1 cycle must not starve");
    expect(dut.io_d1ValidMask == 0xf, "eligible D1 group must contain four lanes");
    const std::uint64_t base = 0x1000 + static_cast<std::uint64_t>(group) * 8;
    expect(dut.io_d1Pc_0 == base, "lane 0 PC mismatch");
    expect(dut.io_d1Pc_1 == base + 2, "lane 1 PC mismatch");
    expect(dut.io_d1Pc_2 == base + 4, "lane 2 PC mismatch");
    expect(dut.io_d1Pc_3 == base + 6, "lane 3 PC mismatch");
    expect(dut.io_d1PredictionFinal_0 && dut.io_d1PredictionFinal_1 &&
               dut.io_d1PredictionFinal_2 && dut.io_d1PredictionFinal_3,
           "every lane must carry the final B-F4 prediction");
    expect(!dut.io_canonicalFlushValid, "dense sequential window must not redirect");
    tick(dut);
  }

  expect(join_peak >= 2, "throughput proof requires multiple prediction joins in flight");
  expect(context_peak >= 2, "throughput proof requires multiple line contexts in flight");
  std::cout << "ok: canonical IFU sustained 32 consecutive four-wide groups"
            << " join_peak=" << join_peak << " context_peak=" << context_peak << '\n';
  return 0;
}
