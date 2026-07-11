#include <cstdlib>
#include <iostream>

#include "VBrobOrderStateProbe.h"
#include "verilated.h"

static void tick(VBrobOrderStateProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "brob-order-state-probe: " << message << '\n';
        std::exit(1);
    }
}

static void idle(VBrobOrderStateProbe &dut) {
    dut.io_allocValid = 0;
    dut.io_scalarDoneValid = 0;
    dut.io_scalarTrapValid = 0;
    dut.io_recoveryValid = 0;
}

static void set_recovery(VBrobOrderStateProbe &dut, unsigned stid,
                         unsigned pointer, bool inclusive) {
    dut.io_recoveryValid = 1;
    dut.io_recoveryStid = stid;
    dut.io_recoveryPivotBid = pointer & 0x7;
    dut.io_recoveryTransportPointerValid = 1;
    dut.io_recoveryTransportPointer = pointer;
    dut.io_recoveryInclusive = inclusive;
}

static void allocate(VBrobOrderStateProbe &dut, unsigned stid, unsigned bid) {
    idle(dut);
    dut.io_allocValid = 1;
    dut.io_allocStid = stid;
    dut.io_allocBid = bid;
    dut.eval();
    require(dut.io_allocApplied, "expected allocation was not admitted");
    tick(dut);
    dut.io_allocValid = 0;
}

static void complete(VBrobOrderStateProbe &dut, unsigned stid, unsigned bid,
                     bool trap = false) {
    idle(dut);
    dut.io_scalarDoneValid = 1;
    dut.io_scalarDoneStid = stid;
    dut.io_scalarDoneBid = bid;
    dut.io_scalarTrapValid = trap;
    tick(dut);
    dut.io_scalarDoneValid = 0;
    dut.io_scalarTrapValid = 0;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VBrobOrderStateProbe dut;

    dut.reset = 1;
    dut.io_allocValid = 0;
    dut.io_allocBid = 0;
    dut.io_allocStid = 0;
    dut.io_scalarDoneValid = 0;
    dut.io_scalarDoneBid = 0;
    dut.io_scalarDoneStid = 0;
    dut.io_scalarTrapValid = 0;
    dut.io_retireReady = 0;
    dut.io_recoveryValid = 0;
    dut.io_recoveryStid = 0;
    dut.io_recoveryPivotBid = 0;
    dut.io_recoveryTransportPointerValid = 0;
    dut.io_recoveryTransportPointer = 0;
    dut.io_recoveryInclusive = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    // Independent per-STID allocation windows.
    allocate(dut, 0, 0);
    allocate(dut, 0, 1);
    allocate(dut, 1, 0);
    dut.eval();
    require(dut.io_allocCursor_0 == 2 && dut.io_allocCursor_1 == 1,
            "allocation tails did not advance independently");
    require(dut.io_commitCursor_0 == 0 && dut.io_commitCursor_1 == 0 &&
                dut.io_liveCount_0 == 2 && dut.io_liveCount_1 == 1,
            "commit heads or live counts do not describe the resident windows");
    require(!dut.io_nonFlushValid_0 && dut.io_nonFlushPrefixCount_0 == 0 &&
                dut.io_nonFlushBlockedValid_0 && dut.io_nonFlushBlockedBid_0 == 0,
            "unsafe head incorrectly opened the non-flush prefix");

    // A younger completion cannot bypass an incomplete head.
    complete(dut, 0, 1);
    dut.eval();
    require(!dut.io_retireValid, "younger completed block bypassed the commit head");
    require(!dut.io_nonFlushValid_0 && dut.io_nonFlushBlockedBid_0 == 0,
            "younger completion bypassed the non-flush head");

    // Head completion remains visible while downstream is blocked.
    complete(dut, 0, 0);
    dut.io_retireReady = 0;
    dut.eval();
    require(dut.io_retireValid && dut.io_retireBid == 0 && dut.io_retireStid == 0,
            "completed head was not published for retirement");
    require(dut.io_nonFlushValid_0 && dut.io_nonFlushHeadBid_0 == 0 &&
                dut.io_nonFlushFrontierBid_0 == 1 &&
                dut.io_nonFlushPrefixCount_0 == 2 &&
                !dut.io_nonFlushBlockedValid_0,
            "consecutive completed rows did not form the exact non-flush prefix");
    tick(dut);
    require(dut.io_commitCursor_0 == 0 && dut.io_liveCount_0 == 2,
            "retirement backpressure mutated the commit window");

    // Another STID may complete while the first head remains blocked.
    complete(dut, 1, 0);
    dut.io_retireReady = 1;
    dut.eval();
    require(dut.io_retireValid && dut.io_retireStid == 0 &&
                dut.io_retireMetadataAccepted,
            "oldest held retirement did not retain priority or exact metadata identity");
    require(dut.io_nonFlushValid_1 && dut.io_nonFlushPrefixCount_1 == 1,
            "independent STID did not publish its own non-flush prefix");
    tick(dut);
    require(dut.io_commitCursor_0 == 1 && dut.io_liveCount_0 == 1,
            "first ordered retirement did not advance its head");

    // RR arbitration gives the other ready STID the next shared retire slot.
    dut.eval();
    require(dut.io_retireValid && dut.io_retireStid == 1 && dut.io_retireBid == 0,
            "shared retire port did not rotate to the other ready STID");
    tick(dut);
    require(dut.io_liveCount_1 == 0 && dut.io_commitCursor_1 == 1,
            "second STID retirement did not update only its window");

    // The already-complete younger block becomes the next head and retires.
    dut.eval();
    require(dut.io_retireValid && dut.io_retireStid == 0 && dut.io_retireBid == 1,
            "completed successor did not become the next ordered head");
    tick(dut);
    require(dut.io_liveCount_0 == 0 && dut.io_commitCursor_0 == 2,
            "successor retirement did not empty the first window");

    // Simultaneous allocation and head retirement move both cursors without changing count.
    allocate(dut, 0, 2);
    complete(dut, 0, 2);
    dut.io_allocValid = 1;
    dut.io_allocStid = 0;
    dut.io_allocBid = 3;
    dut.io_retireReady = 1;
    dut.eval();
    require(dut.io_allocApplied && dut.io_retireFire && dut.io_retireBid == 2,
            "simultaneous allocation and head retirement were not both accepted");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 4 && dut.io_commitCursor_0 == 3 &&
                dut.io_liveCount_0 == 1,
            "simultaneous allocation and retirement changed the live count");

    allocate(dut, 0, 4);
    allocate(dut, 0, 5);
    require(dut.io_liveCount_0 == 3, "recovery fixture did not contain three live blocks");

    // Inclusive recovery names the first killed block and retains [head, pivot).
    set_recovery(dut, 0, 5, true);
    dut.eval();
    require(dut.io_recoveryWindowValid && dut.io_recoveryApplied &&
                dut.io_recoveryFirstKilledBid == 5 && dut.io_recoveryRetainedCount == 2,
            "inclusive recovery did not validate and measure the resident suffix");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 5 && dut.io_commitCursor_0 == 3 &&
                dut.io_liveCount_0 == 2,
            "inclusive recovery changed the commit head or retained count incorrectly");

    // A preserved pivot restores to its successor without moving the head.
    allocate(dut, 0, 5);
    set_recovery(dut, 0, 4, false);
    dut.eval();
    require(dut.io_recoveryApplied && dut.io_recoveryFirstKilledBid == 5 &&
                dut.io_recoveryRetainedCount == 2,
            "preserved-pivot recovery did not restore to the successor");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 5 && dut.io_commitCursor_0 == 3 &&
                dut.io_liveCount_0 == 2,
            "preserved-pivot recovery corrupted the commit window");

    // Accepted recovery suppresses allocation even when the tail identity matches.
    dut.io_allocValid = 1;
    dut.io_allocStid = 0;
    dut.io_allocBid = 5;
    set_recovery(dut, 0, 4, false);
    dut.eval();
    require(dut.io_recoveryApplied && !dut.io_allocApplied,
            "accepted recovery did not dominate a simultaneous allocation");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 5 && dut.io_liveCount_0 == 2,
            "recovery/allocation overlap changed the retained window");

    // An out-of-window pivot is rejected atomically by order and metadata owners.
    set_recovery(dut, 0, 9, true);
    dut.eval();
    require(!dut.io_recoveryWindowValid && !dut.io_recoveryApplied,
            "out-of-window recovery pivot was accepted");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 5 && dut.io_commitCursor_0 == 3 &&
                dut.io_liveCount_0 == 2 && !dut.io_headMismatch_0,
            "rejected recovery mutated order or metadata state");

    // Allocation identity must equal the current full-BID tail.
    dut.io_allocValid = 1;
    dut.io_allocStid = 0;
    dut.io_allocBid = 13;
    dut.eval();
    require(!dut.io_allocIdentityMatch && !dut.io_allocApplied,
            "stale or skipped full-BID allocation identity was accepted");
    tick(dut);
    require(dut.io_allocCursor_0 == 5 && dut.io_liveCount_0 == 2,
            "rejected allocation mutated the live window");

    // Drive the 4-bit implementation identity through rollover, then recover
    // a suffix spanning high and low numeric BID values.
    dut.reset = 1;
    dut.io_retireReady = 0;
    idle(dut);
    tick(dut);
    dut.reset = 0;
    for (unsigned bid = 0; bid < 14; ++bid) {
        allocate(dut, 0, bid);
        complete(dut, 0, bid);
        dut.io_retireReady = 1;
        dut.eval();
        require(dut.io_retireValid && dut.io_retireBid == bid,
                "rollover setup did not publish the exact completed head");
        tick(dut);
        dut.io_retireReady = 0;
    }
    allocate(dut, 0, 14);
    allocate(dut, 0, 15);
    allocate(dut, 0, 0);
    require(dut.io_allocCursor_0 == 1 && dut.io_commitCursor_0 == 14 &&
                dut.io_liveCount_0 == 3,
            "rollover fixture did not span the full-BID boundary");
    complete(dut, 0, 14);
    complete(dut, 0, 15);
    complete(dut, 0, 0);
    dut.eval();
    require(dut.io_nonFlushValid_0 && dut.io_nonFlushHeadBid_0 == 14 &&
                dut.io_nonFlushFrontierBid_0 == 0 &&
                dut.io_nonFlushPrefixCount_0 == 3,
            "non-flush prefix did not cross full-BID rollover");

    set_recovery(dut, 0, 15, true);
    dut.eval();
    require(dut.io_recoveryApplied && dut.io_recoveryRetainedCount == 1,
            "rollover recovery did not retain only the pre-pivot head");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 15 && dut.io_commitCursor_0 == 14 &&
                dut.io_liveCount_0 == 1 && !dut.io_headMismatch_0,
            "rollover recovery diverged order and metadata state");

    // Successful reallocation proves both high-valued and wrapped-low suffix
    // rows were marked reusable by the same modular recovery window.
    allocate(dut, 0, 15);
    allocate(dut, 0, 0);
    require(dut.io_allocCursor_0 == 1 && dut.io_liveCount_0 == 3,
            "metadata suffix spanning rollover was not reusable");

    // The external BID is only the canonical slot. Legacy upper pointer bits
    // may disagree, but cannot override resolution within the live window.
    set_recovery(dut, 0, 8, true);
    dut.io_recoveryTransportPointer = 8;
    dut.eval();
    require(dut.io_recoveryCanonicalMatch && dut.io_recoveryApplied &&
                dut.io_recoveryResolvedPivotBid == 0 &&
                dut.io_recoveryLegacyPointerMismatch &&
                dut.io_recoveryRetainedCount == 2,
            "canonical slot did not override mismatched legacy pointer bits");
    tick(dut);
    idle(dut);
    require(dut.io_allocCursor_0 == 0 && dut.io_commitCursor_0 == 14 &&
                dut.io_liveCount_0 == 2 && !dut.io_headMismatch_0,
            "canonical rollover recovery diverged order and metadata state");

    // Completion carrying a precise trap remains outside the strong prefix.
    dut.reset = 1;
    idle(dut);
    tick(dut);
    dut.reset = 0;
    allocate(dut, 0, 0);
    complete(dut, 0, 0, true);
    dut.eval();
    require(dut.io_oldestComplete_0 && !dut.io_nonFlushValid_0 &&
                dut.io_nonFlushBlockedValid_0 && dut.io_nonFlushBlockedBid_0 == 0,
            "exception-bearing completion entered the strong non-flush prefix");

    std::cout << "brob-order-state-probe: PASS\n";
    return 0;
}
