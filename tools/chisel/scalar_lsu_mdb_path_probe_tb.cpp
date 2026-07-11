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
    dut.io_loadWaitStore = 0;
    dut.io_storeProbeValid = 0;
    dut.io_storeAddrOnly = 0;
    dut.io_storeBid = 5;
    dut.io_storeLsId = 3;
    dut.io_storePc = 0x2000;
    dut.io_storeAddr = 0x8040;
    dut.io_storeSize = 8;
    dut.io_lookupValid = 0;
    dut.io_mutationAccepted = 0;
    dut.io_integratedAllocValid = 0;
    dut.io_integratedTrainValid = 0;
    dut.io_integratedSeedWaitValid = 0;
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

    dut.io_loadWaitStore = 1;
    dut.eval();
    require(!dut.io_oneCycleTimeoutValid,
            "one-cycle timeout asserted before one complete resident cycle");
    tick(dut);
    dut.eval();
    require(dut.io_oneCycleTimeoutValid,
            "one-cycle timeout did not assert after one resident cycle");

    dut.io_flush = 1;
    dut.io_lookupValid = 1;
    dut.eval();
    require(!dut.io_lookupAccepted, "flush cycle accepted a lookup command");
    require(!dut.io_lookupProcessed, "flush cycle processed a lookup command");
    require(!dut.io_mutationValid, "flush cycle exposed a stale mutation");
    require(!dut.io_oneCycleTimeoutValid, "flush cycle exposed stale timeout state");
    tick(dut);
    dut.io_flush = 0;
    dut.io_lookupValid = 0;
    dut.io_loadWaitStore = 0;
    dut.eval();
    require(dut.io_ssitValidMask != 0, "ordinary recovery erased SSIT predictor state");

    dut.io_loadResolved = 0;
    dut.io_loadWaitStore = 1;
    dut.io_mutationAccepted = 0;
    for (int attempt = 0; attempt < 3; ++attempt) {
        require(wait_for(dut, dut.io_failedWaitReleaseValid, 12),
                "stable LIQ wait did not reach the failed-wait timeout");
        if (attempt == 0) {
            require(!dut.io_deleteAccepted,
                    "blocked timed-out wait changed MDB prediction state");
            tick(dut);
            require(dut.io_failedWaitReleaseValid,
                    "timed-out wait was not retained under LIQ mutation backpressure");
            require(!dut.io_deleteAccepted,
                    "held timed-out wait enqueued delete before LIQ mutation acceptance");
            dut.io_mutationAccepted = 1;
        }
        dut.eval();
        require(dut.io_deleteAccepted, "timed-out wait did not enqueue an MDB delete");
        require(dut.io_failedWaitReleaseAccepted,
                "timed-out wait did not atomically accept its LIQ release");
        tick(dut);
        require(wait_for(dut, dut.io_deleteProcessed, 8),
                "timed-out wait delete was not processed");
        require(dut.io_deleteMatched, "timed-out wait delete missed the trained SSIT row");
        if (attempt < 2) {
            require(dut.io_deleteDroppedBelowStall,
                    "pre-release delete did not decay below the stall threshold");
            require(!dut.io_deleteReleased, "SSIT row released before zero-weight delete");
        } else {
            require(dut.io_deleteReleased, "zero-weight delete did not release the SSIT row");
        }
        tick(dut);
    }
    dut.io_loadWaitStore = 0;
    dut.io_mutationAccepted = 0;
    dut.eval();
    require(dut.io_ssitValidMask == 0, "failed-wait delete sequence retained the SSIT row");

    dut.io_storeBid = 3;
    dut.io_loadResolved = 1;
    dut.io_storeProbeValid = 1;
    dut.eval();
    require(dut.io_conflictValid, "cross-BID resolved conflict was not detected");
    require(dut.io_nukeFlush && !dut.io_innerFlush, "cross-BID conflict did not publish NukeFlush");
    tick(dut);
    dut.io_storeProbeValid = 0;

    dut.io_integratedAllocValid = 1;
    dut.eval();
    require(dut.io_integratedAllocAccepted, "integrated LIQ row allocation was not accepted");
    tick(dut);
    dut.io_integratedAllocValid = 0;

    require(dut.io_integratedTrainReady, "integrated MDB record queue was not ready");
    dut.io_integratedTrainValid = 1;
    dut.eval();
    require(dut.io_integratedTrainAccepted, "integrated SSIT training record was not accepted");
    tick(dut);
    dut.io_integratedTrainValid = 0;
    require(wait_for(dut, dut.io_integratedRecordProcessed, 8),
            "integrated SSIT training record was not processed");
    tick(dut);

    dut.io_integratedSeedWaitValid = 1;
    dut.eval();
    require(dut.io_integratedSeedWaitAccepted,
            "native LIQ did not accept the predicted-store wait seed");
    tick(dut);
    dut.io_integratedSeedWaitValid = 0;
    dut.eval();
    require((dut.io_integratedWaitStoreMask & 1) != 0,
            "native LIQ did not retain seeded wait-store state");

    for (int resident_cycle = 0; resident_cycle < 4; ++resident_cycle) {
        dut.eval();
        require(!dut.io_integratedFailedWaitReleaseValid,
                "failed-wait release asserted before four resident cycles");
        tick(dut);
    }

    dut.io_integratedSeedWaitValid = 1;
    dut.eval();
    require(dut.io_integratedFailedWaitReleaseValid,
            "native LIQ wait did not expire after four resident cycles");
    require(!dut.io_integratedDeleteAccepted,
            "blocked native LIQ mutation enqueued delete feedback");
    tick(dut);
    require(dut.io_integratedFailedWaitReleaseValid,
            "native LIQ expiry was not retained across a writer conflict");
    require((dut.io_integratedWaitStoreMask & 1) != 0,
            "writer-conflict cycle cleared native LIQ wait state");

    dut.io_integratedSeedWaitValid = 0;
    dut.eval();
    require(dut.io_integratedFailedWaitReleaseAccepted,
            "native LIQ timeout mutation was not accepted");
    require(dut.io_integratedDeleteAccepted,
            "native LIQ timeout did not enqueue delete in the mutation cycle");
    require((dut.io_integratedWaitStoreMask & 1) != 0,
            "native LIQ wait changed before the accepted clock edge");
    tick(dut);
    dut.eval();
    require((dut.io_integratedWaitStoreMask & 1) == 0,
            "accepted timeout mutation did not clear native LIQ wait state");
    require(wait_for(dut, dut.io_integratedDeleteProcessed, 8),
            "native LIQ timeout delete was not processed");
    require(dut.io_integratedDeleteMatched,
            "native LIQ timeout delete missed the trained SSIT row");
    require(!dut.io_integratedProtocolError,
            "integrated LIQ/MDB lane reported a protocol error");

    std::cout << "scalar-lsu-mdb-probe-status=pass\n";
    return 0;
}
