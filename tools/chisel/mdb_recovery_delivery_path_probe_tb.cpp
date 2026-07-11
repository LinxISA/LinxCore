#include <cstdlib>
#include <iostream>

#include "VMDBRecoveryDeliveryPathProbe.h"
#include "verilated.h"

static void tick(VMDBRecoveryDeliveryPathProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "mdb-recovery-delivery-path-probe: " << message << '\n';
        std::exit(1);
    }
}

static void drive_report(VMDBRecoveryDeliveryPathProbe &dut, int stid, int bid, int rid) {
    dut.io_candidateValid = 1;
    dut.io_conflictValid = 1;
    dut.io_nukeFlush = 1;
    dut.io_stid = stid;
    dut.io_bid = bid;
    dut.io_rid = rid;
}

int main(int argc, char **argv) {
    // CROSS_RTL_SCENARIO: atomic-conflict-admission
    // CROSS_RTL_SCENARIO: conflict-sink-backpressure
    // CROSS_RTL_SCENARIO: unrequired-sink-bypass
    Verilated::commandArgs(argc, argv);
    VMDBRecoveryDeliveryPathProbe dut;

    dut.reset = 1;
    dut.io_flush = 0;
    dut.io_candidateValid = 0;
    dut.io_conflictValid = 0;
    dut.io_recordReady = 0;
    dut.io_nukeFlush = 0;
    dut.io_stid = 0;
    dut.io_bid = 0;
    dut.io_rid = 0;
    dut.io_oldestValidMask = 3;
    dut.io_oldestBid0 = 1;
    dut.io_oldestRid0 = 0;
    dut.io_oldestBid1 = 2;
    dut.io_oldestRid1 = 0;
    dut.io_lookupMatch = 1;
    dut.io_lookupBlockBid = 0x11;
    dut.io_sourceReady = 0;
    dut.io_replayLiveValid = 0;
    dut.io_replayEnable = 0;
    dut.io_replayConsume = 0;
    dut.io_replayPc = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    dut.io_candidateValid = 1;
    dut.io_conflictValid = 0;
    dut.eval();
    require(dut.io_candidateAccepted && !dut.io_recordValid && dut.io_recoveryCount == 0,
            "an unrequired record/recovery sink blocked a conflict-free candidate");

    drive_report(dut, 0, 1, 3);
    dut.eval();
    require(!dut.io_candidateAccepted && !dut.io_recordValid && dut.io_recoveryCount == 0,
            "record published while its recovery report could not be accepted");

    dut.io_recordReady = 1;
    dut.eval();
    require(dut.io_candidateAccepted && dut.io_recordValid,
            "ready conflict did not publish record and recovery atomically");
    tick(dut);
    dut.io_candidateValid = 0;
    dut.io_conflictValid = 0;
    dut.eval();
    require(dut.io_recoveryPending && dut.io_recoveryCount == 1,
            "accepted recovery report was not retained");
    require(dut.io_lookupValid && dut.io_lookupStid == 0 &&
                dut.io_lookupBid == 1 && dut.io_lookupRid == 3,
            "retained report lost exact lookup identity");
    require(dut.io_sourceValid && dut.io_sourceBlockBid == 0x11 &&
                !dut.io_sourceAccepted,
            "exactly promoted report was not held under source backpressure");
    tick(dut);
    require(dut.io_recoveryCount == 1 && dut.io_sourceValid,
            "blocked recovery report did not remain stable");

    dut.io_sourceReady = 1;
    dut.eval();
    require(dut.io_sourceAccepted,
            "eligible exact full-BID source was not accepted");
    tick(dut);
    dut.io_sourceReady = 0;
    dut.eval();
    require(!dut.io_recoveryPending && dut.io_recoveryCount == 0,
            "accepted source did not release the retained report");

    dut.io_lookupBlockBid = 0x12;
    drive_report(dut, 1, 2, 4);
    dut.eval();
    require(dut.io_candidateAccepted && dut.io_recordValid,
            "STID1 conflict was not admitted");
    tick(dut);
    dut.io_candidateValid = 0;
    dut.io_conflictValid = 0;
    dut.eval();
    require(dut.io_recoveryStidInRange && dut.io_lookupStid == 1 &&
                dut.io_sourceValid && dut.io_sourceStid == 1 &&
                dut.io_sourceBlockBid == 0x12,
            "report STID did not select its own oldest watermark and promotion lane");

    dut.io_sourceReady = 1;
    tick(dut);
    dut.io_sourceReady = 0;
    dut.io_lookupBlockBid = 0x13;
    drive_report(dut, 2, 3, 5);
    dut.eval();
    require(dut.io_candidateAccepted && dut.io_recordValid,
            "out-of-range report was not retained at the publication boundary");
    tick(dut);
    dut.io_candidateValid = 0;
    dut.io_conflictValid = 0;
    dut.eval();
    require(dut.io_recoveryPending && !dut.io_recoveryStidInRange &&
                !dut.io_sourceValid && !dut.io_sourceAccepted,
            "out-of-range STID authorized recovery or escaped retention");

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.eval();
    require(!dut.io_recoveryPending && dut.io_recoveryCount == 0,
            "flush did not clear retained MDB recovery state");

    dut.io_flush = 0;
    dut.io_replayLiveValid = 1;
    dut.io_replayEnable = 1;
    dut.io_replayConsume = 1;
    dut.io_replayPc = 0x4000;
    dut.eval();
    require(dut.io_replayLiveSelected,
            "live replay probe was not selected");
    tick(dut);
    dut.io_replayLiveValid = 0;
    dut.eval();
    require(dut.io_replayRetainedReplayed && !dut.io_replaySelected,
            "accepted live probe replayed again while ResolveQ remained visible");

    dut.io_flush = 1;
    tick(dut);
    dut.io_flush = 0;
    dut.io_replayLiveValid = 1;
    dut.io_replayEnable = 0;
    dut.io_replayConsume = 0;
    dut.io_replayPc = 0x5000;
    tick(dut);
    dut.io_replayLiveValid = 0;
    dut.io_replayConsume = 1;
    dut.eval();
    require(dut.io_replaySelected && dut.io_replayRetainedNeedsRetry,
            "unaccepted live probe did not request a mandatory retry");
    tick(dut);
    dut.eval();
    require(!dut.io_replayRetainedNeedsRetry && !dut.io_replayRetainedReplayed,
            "mandatory retry consumed the later ResolveQ replay chance");
    dut.io_replayEnable = 1;
    dut.eval();
    require(dut.io_replaySelected,
            "delayed ResolveQ replay was not preserved after mandatory retry");
    tick(dut);
    dut.eval();
    require(dut.io_replayRetainedReplayed && !dut.io_replaySelected,
            "accepted delayed replay was not consumed exactly once");

    std::cout << "mdb-recovery-delivery-path-probe: PASS\n";
    return 0;
}
