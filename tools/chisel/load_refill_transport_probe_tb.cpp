#include "VLoadRefillTransportProbe.h"
#include "verilated.h"

#include <cstdlib>
#include <iostream>

namespace {
vluint64_t sim_time = 0;

void eval(VLoadRefillTransportProbe &dut) { dut.eval(); }

void tick(VLoadRefillTransportProbe &dut) {
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

void clear_inputs(VLoadRefillTransportProbe &dut) {
  dut.io_hardFlush = 0;
  dut.io_hold = 0;
  dut.io_missValid = 0;
  dut.io_missLineAddr = 0;
  dut.io_externalValid = 0;
  dut.io_externalLineAddr = 0;
  dut.io_outReady = 0;
  for (int i = 0; i < 16; ++i) {
    dut.io_missData[i] = 0;
    dut.io_externalData[i] = 0;
  }
}

void enqueue_external(VLoadRefillTransportProbe &dut, uint64_t line) {
  dut.io_externalValid = 1;
  dut.io_externalLineAddr = line;
  eval(dut);
  expect(dut.io_externalReady, "external refill must be ready");
  tick(dut);
  dut.io_externalValid = 0;
}
}  // namespace

double sc_time_stamp() { return static_cast<double>(sim_time); }

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VLoadRefillTransportProbe dut;
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  eval(dut);
  expect(dut.io_count == 0 && !dut.io_protocolError, "transport must reset empty");

  dut.io_missValid = 1;
  dut.io_missLineAddr = 0x1000;
  dut.io_externalValid = 1;
  dut.io_externalLineAddr = 0x2000;
  eval(dut);
  expect(dut.io_missReady && dut.io_externalReady && dut.io_dualIngressAccepted,
         "two free slots must accept both sources");
  tick(dut);
  dut.io_missValid = 0;
  dut.io_externalValid = 0;
  expect(dut.io_count == 2, "dual ingress must retain two packets");
  expect(dut.io_outValid && dut.io_outFromMissQueue && dut.io_outLineAddr == 0x1000,
         "blocked output must expose the oldest miss packet");
  tick(dut);
  expect(dut.io_count == 2 && dut.io_outValid && dut.io_outFromMissQueue &&
             dut.io_outLineAddr == 0x1000,
         "blocked output must remain stable across cycles");

  dut.io_hold = 1;
  dut.io_outReady = 1;
  eval(dut);
  expect(!dut.io_outValid && !dut.io_missReady && !dut.io_externalReady,
         "precise-recovery hold must freeze ingress and egress");
  tick(dut);
  expect(dut.io_count == 2, "hold must preserve resident packets");

  dut.io_hold = 0;
  eval(dut);
  expect(dut.io_outValid && dut.io_outFromMissQueue && dut.io_outLineAddr == 0x1000,
         "same-cycle dual ingress must publish miss source first");
  tick(dut);
  expect(dut.io_outValid && !dut.io_outFromMissQueue && dut.io_outLineAddr == 0x2000,
         "external source must follow the miss source");
  tick(dut);
  dut.io_outReady = 0;
  expect(dut.io_count == 0, "ordered drain must empty the transport");

  enqueue_external(dut, 0x3000);
  enqueue_external(dut, 0x4000);
  enqueue_external(dut, 0x5000);
  expect(dut.io_count == 3, "three retained packets must occupy three slots");

  dut.io_outReady = 1;
  dut.io_missValid = 1;
  dut.io_missLineAddr = 0x6000;
  dut.io_externalValid = 1;
  dut.io_externalLineAddr = 0x7000;
  eval(dut);
  expect(dut.io_outAccepted && dut.io_outLineAddr == 0x3000,
         "full-side dequeue must consume the oldest packet");
  expect(dut.io_missReady && dut.io_externalReady && dut.io_dualIngressAccepted,
         "same-cycle dequeue must create two ingress slots");
  tick(dut);
  dut.io_outReady = 0;
  dut.io_missValid = 0;
  dut.io_externalValid = 0;
  expect(dut.io_count == 4 && !dut.io_protocolError,
         "dequeue plus dual ingress must end full without corruption");

  dut.io_outReady = 1;
  dut.io_missValid = 1;
  dut.io_missLineAddr = 0x8000;
  dut.io_externalValid = 1;
  dut.io_externalLineAddr = 0x9000;
  eval(dut);
  expect(dut.io_outAccepted && dut.io_outLineAddr == 0x4000,
         "full transport must dequeue the oldest resident packet");
  expect(dut.io_missReady && !dut.io_externalReady && !dut.io_dualIngressAccepted,
         "one full-side dequeue slot must go to miss-source priority");
  tick(dut);
  dut.io_outReady = 0;
  dut.io_missValid = 0;
  dut.io_externalValid = 0;
  expect(dut.io_count == 4 && !dut.io_protocolError,
         "single-slot replacement must preserve full occupancy");

  dut.io_hardFlush = 1;
  tick(dut);
  dut.io_hardFlush = 0;
  eval(dut);
  expect(dut.io_count == 0 && dut.io_validMask == 0 && !dut.io_protocolError,
         "hard flush must clear all buffered refill state");

  std::cout << "ok: load refill transport generated-RTL probe passed\n";
  return 0;
}
