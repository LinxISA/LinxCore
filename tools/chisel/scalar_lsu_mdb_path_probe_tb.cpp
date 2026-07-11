#include <cstdint>
#include <cstdlib>
#include <iostream>

#include "VScalarLSUMDBPathProbe.h"
#include "verilated.h"

static void tick(VScalarLSUMDBPathProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-lsu-mdb-probe: " << message << '\n';
        std::exit(1);
    }
}

static bool wait_for(VScalarLSUMDBPathProbe &dut, uint8_t &signal, int cycles) {
    for (int i = 0; i < cycles; ++i) {
        dut.eval();
        if (signal) {
            return true;
        }
        tick(dut);
    }
    return false;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarLSUMDBPathProbe dut;

    dut.reset = 1;
    dut.io_flush = 0;
    dut.io_loadValid = 1;
    dut.io_loadResolved = 1;
    dut.io_loadBid = 5;
    dut.io_loadLsId = 7;
    dut.io_loadPc = 0x1000;
    dut.io_loadAddr = 0x8040;
    dut.io_loadSize = 8;
    dut.io_storeProbeValid = 0;
    dut.io_storeAddrOnly = 0;
    dut.io_storeBid = 5;
    dut.io_storeLsId = 3;
    dut.io_storePc = 0x2000;
    dut.io_storeAddr = 0x8040;
    dut.io_storeSize = 8;
    dut.io_lookupValid = 0;
    dut.io_mutationAccepted = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    require(dut.io_storeProbeReady, "store probe must be ready after reset");
    dut.io_storeProbeValid = 1;
    dut.eval();
    require(dut.io_conflictValid, "same-BID resolved conflict was not detected");
    require(dut.io_innerFlush && !dut.io_nukeFlush, "same-BID conflict did not publish InnerFlush");
    require(dut.io_recordAccepted, "first conflict record was not accepted");
    tick(dut);
    dut.io_storeProbeValid = 0;

    require(wait_for(dut, dut.io_bmdbReportValid, 8), "first conflict record was not processed");
    require(dut.io_ssitValidMask != 0, "first conflict did not allocate SSIT state");

    require(dut.io_storeProbeReady, "store probe did not recover after first record");
    dut.io_storeProbeValid = 1;
    dut.eval();
    require(dut.io_recordAccepted, "reinforcement conflict record was not accepted");
    tick(dut);
    dut.io_storeProbeValid = 0;
    require(wait_for(dut, dut.io_bmdbReportValid, 8), "reinforcement record was not processed");

    dut.io_loadResolved = 0;
    require(dut.io_lookupReady, "lookup queue was not ready");
    dut.io_lookupValid = 1;
    dut.eval();
    require(dut.io_lookupAccepted, "first-after-nuke lookup was not accepted");
    tick(dut);
    dut.io_lookupValid = 0;
    require(wait_for(dut, dut.io_lookupProcessed, 8), "first-after-nuke lookup was not processed");
    for (int i = 0; i < 4; ++i) {
        tick(dut);
    }

    require(dut.io_lookupReady, "second lookup queue was not ready");
    dut.io_lookupValid = 1;
    dut.eval();
    require(dut.io_lookupAccepted, "second lookup was not accepted");
    tick(dut);
    dut.io_lookupValid = 0;

    require(wait_for(dut, dut.io_mutationValid, 12), "trained lookup did not request LIQ mutation");
    require(dut.io_lookupHit, "trained lookup did not report a hit");
    require(dut.io_mutationTargetIndex == 0, "trained lookup targeted the wrong LIQ row");
    tick(dut);
    require(dut.io_mutationValid, "lookup result was not held under mutation backpressure");
    dut.io_mutationAccepted = 1;
    dut.eval();
    require(dut.io_lookupWaitMutation, "accepted lookup mutation pulse was missing");
    tick(dut);
    dut.io_mutationAccepted = 0;
    require(!dut.io_protocolError, "canonical MDB protocol error asserted");

    dut.io_flush = 1;
    dut.io_lookupValid = 1;
    dut.eval();
    require(!dut.io_lookupAccepted, "flush cycle accepted a lookup command");
    require(!dut.io_lookupProcessed, "flush cycle processed a lookup command");
    require(!dut.io_mutationValid, "flush cycle exposed a stale mutation");
    tick(dut);
    dut.io_flush = 0;
    dut.io_lookupValid = 0;
    dut.eval();
    require(dut.io_ssitValidMask != 0, "ordinary recovery erased SSIT predictor state");

    dut.io_storeBid = 3;
    dut.io_loadResolved = 1;
    dut.io_storeProbeValid = 1;
    dut.eval();
    require(dut.io_conflictValid, "cross-BID resolved conflict was not detected");
    require(dut.io_nukeFlush && !dut.io_innerFlush, "cross-BID conflict did not publish NukeFlush");

    std::cout << "scalar-lsu-mdb-probe-status=pass\n";
    return 0;
}
