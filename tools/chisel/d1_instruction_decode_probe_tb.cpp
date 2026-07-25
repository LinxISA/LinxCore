#include "VD1InstructionDecodeProbe.h"
#include "verilated.h"

#include <cstdlib>
#include <iostream>

namespace {
vluint64_t sim_time = 0;

void eval(VD1InstructionDecodeProbe &dut) { dut.eval(); }

void tick(VD1InstructionDecodeProbe &dut) {
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
}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VD1InstructionDecodeProbe dut;
  dut.io_inValid = 1;
  dut.io_outReady = 0;
  dut.io_flushValid = 0;
  dut.reset = 1;
  tick(dut);
  dut.reset = 0;

  expect(dut.io_outValid, "full D1 group must decode");
  expect(!dut.io_inReady, "D1 input must backpressure with blocked output");
  expect(dut.io_validMask == 0xf, "all four decoded lanes must be valid");
  expect(dut.io_instructionUid_0 == 0x900 && dut.io_instructionUid_3 == 0x903,
         "instruction UIDs must survive full decode");
  expect(dut.io_predictionTag_0 == 0x400 && dut.io_predictionTag_3 == 0x403,
         "prediction tags must survive full decode");
  expect(dut.io_predictionTarget_0 == 0x3000 && dut.io_predictionTarget_3 == 0x3018,
         "prediction targets must survive full decode");
  expect(dut.io_predictionFinal_0 && dut.io_predictionFinal_1 &&
             dut.io_predictionFinal_2 && dut.io_predictionFinal_3,
         "all lanes must retain final B-F4 prediction metadata");

  tick(dut);
  tick(dut);
  expect(dut.io_instructionUid_3 == 0x903 && dut.io_predictionTag_3 == 0x403,
         "decoded payload must remain stable under backpressure");

  dut.io_outReady = 1;
  eval(dut);
  expect(dut.io_inReady, "full group must accept atomically when output is ready");

  dut.io_flushValid = 1;
  eval(dut);
  expect(dut.io_outValid && dut.io_validMask == 0x3,
         "precise flush must retain only the older dense prefix");
  expect(dut.io_decodedValid_0 && dut.io_decodedValid_1 &&
             !dut.io_decodedValid_2 && !dut.io_decodedValid_3,
         "trigger and younger decoded lanes must be suppressed");

  std::cout << "ok: production D1 decoded four lanes and preserved final prediction sidecars\n";
  return 0;
}
