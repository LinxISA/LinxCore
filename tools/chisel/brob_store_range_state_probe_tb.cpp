#include <cstdlib>
#include <iostream>

#include "VBrobStoreRangeStateProbe.h"
#include "verilated.h"

static void tick(VBrobStoreRangeStateProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "brob-store-range-state-probe: " << message << '\n';
        std::exit(1);
    }
}

static void idle(VBrobStoreRangeStateProbe &dut) {
    dut.io_allocValid = 0;
    dut.io_storeValid = 0;
    dut.io_certainValid = 0;
    dut.io_retireValid = 0;
    dut.io_recoveryValid = 0;
    dut.io_certainUseValue = 0;
    dut.io_certainValue = 0;
}

static void allocate(VBrobStoreRangeStateProbe &dut, unsigned stid,
                     unsigned bid, unsigned &live) {
    idle(dut);
    dut.io_allocValid = 1;
    dut.io_allocStid = stid;
    dut.io_allocBid = bid;
    dut.eval();
    require(dut.io_allocReady && dut.io_allocAccepted,
            "expected exact store-range allocation was not accepted");
    tick(dut);
    ++live;
    if (stid == 0) dut.io_orderLiveCount_0 = live;
    else dut.io_orderLiveCount_1 = live;
    idle(dut);
    tick(dut);
}

static void observeStore(VBrobStoreRangeStateProbe &dut, unsigned stid,
                         unsigned bid) {
    idle(dut);
    dut.io_storeValid = 1;
    dut.io_storeStid = stid;
    dut.io_storeBid = bid;
    dut.eval();
    require(dut.io_storeAccepted, "store count missed its exact live block");
    tick(dut);
    idle(dut);
}

static void makeCertain(VBrobStoreRangeStateProbe &dut, unsigned stid,
                        unsigned bid, bool useValue = false,
                        unsigned value = 0) {
    idle(dut);
    dut.io_certainValid = 1;
    dut.io_certainStid = stid;
    dut.io_certainBid = bid;
    dut.io_certainUseValue = useValue;
    dut.io_certainValue = value;
    dut.eval();
    require(dut.io_certainAccepted, "count-certain event missed its exact live block");
    tick(dut);
    idle(dut);
    tick(dut);
}

static void retire(VBrobStoreRangeStateProbe &dut, unsigned stid,
                   unsigned bid, unsigned &head, unsigned &live) {
    idle(dut);
    dut.io_retireValid = 1;
    dut.io_retireStid = stid;
    dut.io_retireBid = bid;
    dut.eval();
    require(dut.io_retireAccepted, "retire missed its exact store-range row");
    tick(dut);
    head = (head + 1) & 0xf;
    --live;
    if (stid == 0) {
        dut.io_orderHeadBid_0 = head;
        dut.io_orderLiveCount_0 = live;
    } else {
        dut.io_orderHeadBid_1 = head;
        dut.io_orderLiveCount_1 = live;
    }
    idle(dut);
}

static void query(VBrobStoreRangeStateProbe &dut, unsigned stid, unsigned bid) {
    dut.io_queryStid = stid;
    dut.io_queryBid = bid;
    dut.eval();
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VBrobStoreRangeStateProbe dut;
    unsigned head0 = 0, head1 = 0, live0 = 0, live1 = 0;
    dut.reset = 1;
    dut.io_orderHeadBid_0 = 0;
    dut.io_orderHeadBid_1 = 0;
    dut.io_orderLiveCount_0 = 0;
    dut.io_orderLiveCount_1 = 0;
    dut.io_queryBid = 0;
    dut.io_queryStid = 0;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;

    allocate(dut, 0, 0, live0);
    allocate(dut, 1, 0, live1);
    dut.eval();
    require(dut.io_blockedValid_0 && dut.io_blockedBid_0 == 0 &&
                dut.io_blockedValid_1 && dut.io_blockedBid_1 == 0,
            "uncertain heads did not block independent range frontiers");
    query(dut, 0, 0);
    require(dut.io_queryHit && dut.io_queryStartValid &&
                dut.io_queryStartStoreId == 0 && !dut.io_queryCountKnown,
            "uncertain cursor block did not receive a stable start ID");

    observeStore(dut, 0, 0);
    observeStore(dut, 0, 0);
    makeCertain(dut, 0, 0);
    makeCertain(dut, 1, 0, true, 0);
    require(dut.io_rangeCursorBid_0 == 1 && dut.io_nextStoreId_0 == 2 &&
                dut.io_rangeCursorBid_1 == 1 && dut.io_nextStoreId_1 == 0,
            "per-STID range cursors or IDs did not advance independently");

    retire(dut, 0, 0, head0, live0);
    retire(dut, 1, 0, head1, live1);
    allocate(dut, 0, 1, live0);
    allocate(dut, 0, 2, live0);
    observeStore(dut, 0, 1);
    makeCertain(dut, 0, 2, true, 2);
    require(dut.io_rangeCursorBid_0 == 1 && dut.io_nextStoreId_0 == 2 &&
                dut.io_blockedValid_0 && dut.io_blockedBid_0 == 1,
            "known younger block bypassed an uncertain predecessor");
    query(dut, 0, 2);
    require(dut.io_queryHit && !dut.io_queryStartValid,
            "younger blocked block received a start ID early");

    makeCertain(dut, 0, 1);
    require(dut.io_rangeCursorBid_0 == 3 && dut.io_nextStoreId_0 == 5,
            "known consecutive blocks did not consume contiguous store ranges");
    query(dut, 0, 1);
    require(dut.io_queryStartStoreId == 2 && dut.io_queryStoreCount == 1,
            "first block range payload is incorrect");
    query(dut, 0, 2);
    require(dut.io_queryStartStoreId == 3 && dut.io_queryStoreCount == 2,
            "explicit-count successor range payload is incorrect");

    // Kill BID2 from live [1,2]. Its saved start ID rewinds cursor and ID.
    idle(dut);
    dut.io_recoveryValid = 1;
    dut.io_recoveryStid = 0;
    dut.io_recoveryFirstKilledBid = 2;
    dut.eval();
    require(dut.io_recoveryRewound && !dut.io_recoveryMissingStart,
            "assigned suffix recovery did not find its saved range start");
    tick(dut);
    live0 = 1;
    dut.io_orderLiveCount_0 = live0;
    idle(dut);
    require(dut.io_rangeCursorBid_0 == 2 && dut.io_nextStoreId_0 == 3,
            "assigned suffix recovery did not rewind range state");
    query(dut, 0, 2);
    require(!dut.io_queryHit, "recovery did not clear the killed range row");

    allocate(dut, 0, 2, live0);
    makeCertain(dut, 0, 2, true, 4);
    require(dut.io_rangeCursorBid_0 == 3 && dut.io_nextStoreId_0 == 7,
            "reallocated block did not reuse the recovered store-ID base");

    // Reset and advance empty ranges to a 4-bit rollover fixture.
    dut.reset = 1;
    idle(dut);
    tick(dut);
    dut.reset = 0;
    head0 = 0;
    live0 = 0;
    dut.io_orderHeadBid_0 = 0;
    dut.io_orderLiveCount_0 = 0;
    for (unsigned bid = 0; bid < 14; ++bid) {
        allocate(dut, 0, bid, live0);
        makeCertain(dut, 0, bid, true, 0);
        retire(dut, 0, bid, head0, live0);
    }
    allocate(dut, 0, 14, live0);
    allocate(dut, 0, 15, live0);
    allocate(dut, 0, 0, live0);
    makeCertain(dut, 0, 15, true, 2);
    makeCertain(dut, 0, 0, true, 3);
    makeCertain(dut, 0, 14, true, 1);
    require(dut.io_rangeCursorBid_0 == 1 && dut.io_nextStoreId_0 == 6,
            "store ranges did not advance through [14,15,0] rollover");
    query(dut, 0, 15);
    require(dut.io_queryStartStoreId == 1,
            "rollover successor did not receive the preceding range end");

    idle(dut);
    dut.io_recoveryValid = 1;
    dut.io_recoveryStid = 0;
    dut.io_recoveryFirstKilledBid = 15;
    dut.eval();
    require(dut.io_recoveryRewound, "rollover suffix recovery did not rewind");
    tick(dut);
    live0 = 1;
    dut.io_orderLiveCount_0 = live0;
    idle(dut);
    require(dut.io_rangeCursorBid_0 == 15 && dut.io_nextStoreId_0 == 1,
            "rollover recovery did not restore the saved BID15 start ID");
    query(dut, 0, 0);
    require(!dut.io_queryHit, "wrapped low killed row survived recovery");

    std::cout << "brob-store-range-state-probe: PASS\n";
    return 0;
}
