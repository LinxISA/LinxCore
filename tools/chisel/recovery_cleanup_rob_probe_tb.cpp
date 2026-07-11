#include <cstdlib>
#include <iostream>

#include "VRecoveryCleanupROBProbe.h"
#include "verilated.h"

static void tick(VRecoveryCleanupROBProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "recovery-cleanup-rob-probe: " << message << '\n';
        std::exit(1);
    }
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VRecoveryCleanupROBProbe dut;

    dut.reset = 1;
    dut.io_allocValid = 0;
    dut.io_allocBid = 0;
    dut.io_allocBlockBid = 0;
    dut.io_allocStid = 0;
    dut.io_fullValid = 0;
    dut.io_fullBlockBid = 0;
    dut.io_fullStid = 0;
    dut.io_peerFullValid = 0;
    dut.io_peerFullBlockBid = 0;
    dut.io_peerFullStid = 0;
    dut.io_ringValid = 0;
    dut.io_ringBid = 0;
    dut.io_ringRid = 0;
    dut.io_ringNuke = 0;
    dut.io_oldestValid = 1;
    dut.io_oldestBid = 1;
    dut.io_oldestRid = 0;
    dut.io_intentReady = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    for (int bid = 1; bid <= 3; ++bid) {
        dut.io_allocBid = bid;
        dut.io_allocBlockBid = 0x10 + bid;
        dut.io_allocStid = bid == 3 ? 1 : 0;
        dut.io_allocValid = 1;
        dut.eval();
        require(dut.io_allocReady, "ROB allocation was not ready");
        tick(dut);
    }
    dut.io_allocValid = 0;
    dut.eval();
    require(dut.io_robSize == 3, "ROB did not retain three resident rows");

    dut.io_ringValid = 1;
    dut.io_ringNuke = 1;
    dut.io_ringBid = 2;
    dut.io_ringRid = 1;
    dut.eval();
    require(!dut.io_ringReady && !dut.io_ringAccepted && dut.io_ringBlockedByAge,
            "non-oldest non-immediate recovery was not retained before cleanup");
    tick(dut);
    require(dut.io_robSize == 3 && !dut.io_cleanupPending,
            "ineligible recovery reached cleanup or changed ROB state");
    dut.io_oldestBid = 2;
    dut.io_ringRid = 0;
    dut.eval();
    require(!dut.io_ringReady && !dut.io_ringAccepted &&
                !dut.io_ringLookupMatched && dut.io_ringLookupBlocked,
            "wrong RID did not block full-BID recovery lookup");
    tick(dut);
    require(dut.io_robSize == 3 && !dut.io_cleanupPending,
            "failed full-BID lookup consumed recovery or changed ROB state");
    dut.io_ringRid = 1;
    dut.eval();
    require(dut.io_ringReady && dut.io_ringAccepted,
            "eligible ring recovery did not recover and accept full BID");
    require(dut.io_ringLookupMatched && !dut.io_ringLookupBlocked,
            "eligible ring recovery did not exactly match the resident ROB row");
    tick(dut);
    dut.io_ringValid = 0;
    dut.eval();
    require(dut.io_arbiterSelectedValid && dut.io_arbiterSelectedSource == 2 &&
                dut.io_arbiterSelectedBlockBid == 0x12,
            "promoted ring report did not become the retained arbiter winner");
    require(!dut.io_cleanupPending,
            "newly admitted arbiter source bypassed its retained report slot");
    tick(dut);
    dut.eval();
    require(dut.io_cleanupPending && dut.io_cleanupIntentValid,
            "accepted ring recovery was not retained as cleanup intent");
    require(!dut.io_robFlushApplied && dut.io_robSize == 3,
            "blocked cleanup intent modified ROB state");
    require(dut.io_cleanupBlockFlushValid && dut.io_cleanupBlockFlushBid == 0x12,
            "exact ROB lookup did not authorize the allocator-owned full BID");
    tick(dut);
    require(dut.io_cleanupPending && dut.io_robSize == 3,
            "cleanup intent was not held under consumer backpressure");

    dut.io_intentReady = 1;
    dut.eval();
    require(dut.io_robFlushApplied,
            "accepted cleanup intent did not reach ROB prune logic");
    require(dut.io_robFlushPruneMask == 0x2,
            "BID-based nuke recovery crossed the request STID scope");
    tick(dut);
    dut.io_intentReady = 0;
    dut.eval();
    require(dut.io_robSize == 2,
            "ROB did not retain the older row and different-STID row after recovery");
    require(!dut.io_cleanupPending,
            "consumed cleanup intent remained pending");

    dut.io_fullValid = 1;
    dut.io_fullBlockBid = 0x12;
    dut.io_fullStid = 0;
    dut.io_peerFullValid = 1;
    dut.io_peerFullBlockBid = 0x11;
    dut.io_peerFullStid = 0;
    dut.io_oldestBid = 1;
    dut.eval();
    require(dut.io_fullReady && dut.io_fullAccepted,
            "first full-BID source was not admitted to its retained slot");
    require(dut.io_peerFullReady && dut.io_peerFullAccepted,
            "peer full-BID source was not independently admitted");
    tick(dut);
    dut.io_fullValid = 0;
    dut.io_peerFullValid = 0;
    dut.eval();
    require(dut.io_arbiterPendingMask == 0x3 && dut.io_arbiterSelectedValid &&
                dut.io_arbiterSelectedSource == 1 && dut.io_arbiterSelectedBlockBid == 0x11,
            "same-STID arbitration did not select the model-oldest report");
    tick(dut);
    dut.eval();
    require(dut.io_cleanupPending && dut.io_cleanupBlockFlushValid &&
                dut.io_cleanupBlockFlushBid == 0x11,
            "oldest full-BID request did not become retained cleanup intent");
    require(dut.io_arbiterPendingMask == 0x1 &&
                dut.io_arbiterSelectedBlockBid == 0x12,
            "younger source was not retained behind the selected request");

    dut.io_intentReady = 1;
    dut.eval();
    require(dut.io_robFlushApplied && dut.io_cleanupBlockFlushBid == 0x11,
            "oldest selected request was not applied before replacement");
    tick(dut);
    dut.io_intentReady = 0;
    dut.eval();
    require(dut.io_cleanupPending && dut.io_cleanupBlockFlushValid &&
                dut.io_cleanupBlockFlushBid == 0x12,
            "retained younger source did not replace consumed cleanup intent");
    require(dut.io_arbiterPendingMask == 0,
            "accepted replacement remained duplicated in the source arbiter");

    dut.io_intentReady = 1;
    tick(dut);
    dut.io_intentReady = 0;
    dut.io_fullValid = 1;
    dut.io_fullBlockBid = 0x11;
    dut.io_fullStid = 2;
    dut.eval();
    require(!dut.io_fullReady && !dut.io_fullAccepted,
            "out-of-range STID report was admitted into retained recovery state");

    dut.io_fullStid = 0;
    dut.io_peerFullValid = 1;
    dut.io_peerFullBlockBid = 0x13;
    dut.io_peerFullStid = 1;
    dut.eval();
    require(dut.io_fullAccepted && dut.io_peerFullAccepted,
            "independent STID reports were not admitted together");
    tick(dut);
    dut.io_fullValid = 0;
    dut.io_peerFullValid = 0;
    dut.eval();
    require(dut.io_arbiterSelectedValid && dut.io_arbiterSelectedSource == 1 &&
                dut.io_arbiterSelectedBlockBid == 0x13,
            "round-robin STID serialization compared incomparable BIDs or starved lane one");

    dut.io_peerFullValid = 1;
    dut.io_peerFullBlockBid = 0x15;
    dut.io_peerFullStid = 1;
    dut.eval();
    require(dut.io_peerFullAccepted,
            "selected STID1 source could not consume-and-replace while STID0 remained resident");
    tick(dut);
    dut.io_peerFullValid = 0;
    dut.io_intentReady = 1;
    dut.io_fullValid = 1;
    dut.io_fullBlockBid = 0x14;
    dut.io_fullStid = 0;
    dut.eval();
    require(dut.io_arbiterPendingMask == 0x3 && dut.io_arbiterSelectedSource == 0 &&
                dut.io_arbiterSelectedBlockBid == 0x11 && dut.io_fullAccepted,
            "round robin did not return to continuously resident STID0");
    tick(dut);
    dut.io_fullValid = 0;
    dut.io_intentReady = 0;
    dut.eval();
    require(dut.io_arbiterPendingMask == 0x3 && dut.io_arbiterSelectedSource == 1 &&
                dut.io_arbiterSelectedBlockBid == 0x15,
            "round robin did not return to STID1 while retaining the replaced STID0 source");
    require(dut.io_cleanupPending && dut.io_cleanupBlockFlushBid == 0x11,
            "STID0 selection did not replace the consumed STID1 cleanup intent");

    std::cout << "recovery-cleanup-rob-probe-status=pass\n";
    return 0;
}
