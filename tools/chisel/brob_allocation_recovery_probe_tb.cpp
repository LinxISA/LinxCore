#include <cstdlib>
#include <iostream>

#include "VBrobAllocationRecoveryProbe.h"
#include "verilated.h"

static void tick(VBrobAllocationRecoveryProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "brob-allocation-recovery-probe: " << message << '\n';
        std::exit(1);
    }
}

int main(int argc, char **argv) {
    // CROSS_RTL_SCENARIO: per-stid-independent-advance
    // CROSS_RTL_SCENARIO: miss-predict-inclusive-restore
    // CROSS_RTL_SCENARIO: preserved-pivot-successor-restore
    // CROSS_RTL_SCENARIO: recovery-over-same-lane-advance
    // CROSS_RTL_SCENARIO: invalid-stid-rejection
    Verilated::commandArgs(argc, argv);
    VBrobAllocationRecoveryProbe dut;

    dut.reset = 1;
    dut.io_advanceValid = 0;
    dut.io_advanceStid = 0;
    dut.io_recoveryValid = 0;
    dut.io_recoveryStid = 0;
    dut.io_recoveryPivotBid = 0;
    dut.io_recoveryInclusive = 0;
    dut.io_queryStid = 0;
    dut.io_admissionAllocValid = 0;
    dut.io_admissionUsesExistingBlock = 0;
    dut.io_admissionStidInRange = 1;
    dut.io_admissionBrobReady = 0;
    dut.io_admissionRobReady = 0;
    dut.io_admissionRecoveryValid = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    dut.io_advanceValid = 1;
    dut.io_advanceStid = 0;
    tick(dut);
    dut.io_advanceStid = 1;
    tick(dut);
    dut.io_advanceValid = 0;
    dut.eval();
    require(dut.io_cursor0 == 1 && dut.io_cursor1 == 1,
            "per-STID advances were not independent");

    dut.io_recoveryValid = 1;
    dut.io_recoveryStid = 0;
    dut.io_recoveryPivotBid = 7;
    dut.io_recoveryInclusive = 1;
    dut.eval();
    require(dut.io_recoveryApplied && dut.io_recoveryFirstKilledBid == 7 &&
                dut.io_recoveryOldAllocBid == 1,
            "inclusive recovery target was not the first killed BID");
    tick(dut);
    require(dut.io_cursor0 == 7 && dut.io_cursor1 == 1,
            "inclusive recovery did not restore only its STID cursor");

    dut.io_recoveryPivotBid = 4;
    dut.io_recoveryInclusive = 0;
    dut.eval();
    require(dut.io_recoveryFirstKilledBid == 5 && dut.io_recoveryOldAllocBid == 7,
            "preserved pivot did not restore to its successor");
    tick(dut);
    require(dut.io_cursor0 == 5,
            "exclusive recovery did not install the successor cursor");

    dut.io_advanceValid = 1;
    dut.io_advanceStid = 0;
    dut.io_recoveryPivotBid = 9;
    dut.io_recoveryInclusive = 1;
    tick(dut);
    require(dut.io_cursor0 == 9,
            "same-lane allocation overrode recovery");

    dut.io_advanceStid = 1;
    dut.io_recoveryPivotBid = 3;
    tick(dut);
    require(dut.io_cursor0 == 3 && dut.io_cursor1 == 2,
            "recovery incorrectly blocked another STID advance");

    dut.io_advanceValid = 0;
    dut.io_recoveryStid = 3;
    dut.io_recoveryPivotBid = 12;
    dut.eval();
    require(!dut.io_recoveryInRange && !dut.io_recoveryApplied,
            "invalid STID recovery was accepted");
    tick(dut);
    require(dut.io_cursor0 == 3 && dut.io_cursor1 == 2,
            "invalid STID recovery mutated cursor state");

    dut.io_recoveryValid = 0;
    dut.io_queryStid = 1;
    dut.eval();
    require(dut.io_queryInRange && dut.io_nextBid == 2,
            "query did not select the requested STID cursor");

    dut.io_admissionAllocValid = 1;
    dut.io_admissionBrobReady = 1;
    dut.io_admissionRobReady = 1;
    dut.io_admissionRecoveryValid = 1;
    dut.eval();
    require(!dut.io_admissionAllocReady && !dut.io_admissionAllocFire &&
                !dut.io_admissionRobAllocValid && !dut.io_admissionBrobAllocValid,
            "recovery blocked public allocation but not a resident child owner");
    dut.io_admissionRecoveryValid = 0;
    dut.eval();
    require(dut.io_admissionAllocReady && dut.io_admissionAllocFire &&
                dut.io_admissionRobAllocValid && dut.io_admissionBrobAllocValid,
            "coherent ROB/BROB allocation was not admitted after recovery");
    dut.io_admissionUsesExistingBlock = 1;
    dut.io_admissionStidInRange = 0;
    dut.io_admissionBrobReady = 0;
    dut.eval();
    require(!dut.io_admissionAllocReady && !dut.io_admissionRobAllocValid,
            "existing-block allocation bypassed invalid STID rejection");

    std::cout << "brob-allocation-recovery-probe: PASS\n";
    return 0;
}
