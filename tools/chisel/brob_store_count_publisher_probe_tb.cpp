#include <cstdlib>
#include <iostream>

#include "VBrobStoreCountPublisherProbe.h"
#include "verilated.h"

static void tick(VBrobStoreCountPublisherProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "brob-store-count-publisher-probe: " << message << '\n';
        std::exit(1);
    }
}

static void idle(VBrobStoreCountPublisherProbe &dut) {
    dut.io_allocValid = 0;
    dut.io_storeValid = 0;
    dut.io_scalarValid = 0;
    dut.io_explicitValid = 0;
    dut.io_recoveryValid = 0;
}

static void allocate(VBrobStoreCountPublisherProbe &dut, unsigned stid,
                     unsigned bid, unsigned &live) {
    idle(dut);
    dut.io_allocValid = 1;
    dut.io_allocStid = stid;
    dut.io_allocBid = bid;
    dut.eval();
    require(dut.io_allocAccepted, "exact range-row allocation was rejected");
    tick(dut);
    ++live;
    if (stid == 0) dut.io_orderLiveCount_0 = live;
    else dut.io_orderLiveCount_1 = live;
    idle(dut);
}

static void observe_store(VBrobStoreCountPublisherProbe &dut, unsigned stid,
                          unsigned bid) {
    idle(dut);
    dut.io_storeValid = 1;
    dut.io_storeStid = stid;
    dut.io_storeBid = bid;
    dut.eval();
    require(dut.io_storeAccepted, "scalar store missed its exact live block");
    tick(dut);
    idle(dut);
}

static void query(VBrobStoreCountPublisherProbe &dut, unsigned stid,
                  unsigned bid) {
    dut.io_queryStid = stid;
    dut.io_queryBid = bid;
    dut.eval();
}

static void capture_explicit(VBrobStoreCountPublisherProbe &dut,
                             unsigned stid, unsigned bid, unsigned value) {
    idle(dut);
    dut.io_explicitValid = 1;
    dut.io_explicitStid = stid;
    dut.io_explicitBid = bid;
    dut.io_explicitValue = value;
    dut.eval();
    require(dut.io_explicitReady && dut.io_explicitInputAccepted,
            "live explicit count was not captured");
    tick(dut);
    idle(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VBrobStoreCountPublisherProbe dut;
    unsigned live0 = 0;
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

    // A future/nonresident identity must remain backpressured.
    dut.io_explicitValid = 1;
    dut.io_explicitStid = 0;
    dut.io_explicitBid = 0;
    dut.io_explicitValue = 2;
    dut.eval();
    require(!dut.io_explicitReady && dut.io_explicitBlockedByLiveWindow,
            "nonresident explicit count crossed the live-window gate");

    allocate(dut, 0, 0, live0);
    observe_store(dut, 0, 0);
    observe_store(dut, 0, 0);
    idle(dut);
    dut.io_scalarValid = 1;
    dut.io_scalarStid = 0;
    dut.io_scalarBid = 0;
    dut.eval();
    require(dut.io_scalarInputAccepted, "scalar closure was not retained");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_publishValid && !dut.io_publishUseValue &&
                dut.io_scalarPublishFire,
            "retained scalar closure did not publish accumulated count");
    tick(dut);
    tick(dut);
    query(dut, 0, 0);
    require(dut.io_queryHit && dut.io_queryCountKnown &&
                dut.io_queryStoreCount == 2 && dut.io_headCountKnown_0,
            "scalar closure did not freeze the exact accumulated count");

    // Same-value repeat is terminal and idempotent.
    capture_explicit(dut, 0, 0, 2);
    dut.eval();
    require(dut.io_publishValid && dut.io_publishUseValue &&
                dut.io_explicitPublishFire && !dut.io_countConflict,
            "same-value duplicate did not terminate idempotently");
    tick(dut);
    query(dut, 0, 0);
    require(dut.io_queryStoreCount == 2,
            "idempotent duplicate changed the frozen count");

    // Conflicting repeat remains held until recovery cancels its exact suffix.
    capture_explicit(dut, 0, 0, 3);
    dut.eval();
    require(dut.io_countConflict && dut.io_explicitPending &&
                !dut.io_explicitPublishFire,
            "conflicting duplicate was not held as an integration error");
    idle(dut);
    dut.io_recoveryValid = 1;
    dut.io_recoveryStid = 0;
    dut.io_recoveryFirstKilledBid = 0;
    dut.eval();
    require(dut.io_explicitPendingCanceled,
            "accepted recovery did not cancel the retained conflicting event");
    tick(dut);
    live0 = 0;
    dut.io_orderLiveCount_0 = 0;
    idle(dut);
    dut.eval();
    require(!dut.io_explicitPending, "killed explicit event remained resident");

    // Rebuild three live rows and prove same-block versus different-block
    // collision policy.
    dut.reset = 1;
    tick(dut);
    dut.reset = 0;
    live0 = 0;
    dut.io_orderHeadBid_0 = 0;
    dut.io_orderLiveCount_0 = 0;
    allocate(dut, 0, 0, live0);
    allocate(dut, 0, 1, live0);
    allocate(dut, 0, 2, live0);

    idle(dut);
    dut.io_scalarValid = 1;
    dut.io_scalarStid = 0;
    dut.io_scalarBid = 0;
    dut.io_explicitValid = 1;
    dut.io_explicitStid = 0;
    dut.io_explicitBid = 0;
    dut.io_explicitValue = 5;
    dut.eval();
    require(dut.io_scalarInputAccepted && dut.io_explicitInputAccepted,
            "same-block sources were not retained together");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_sameBlockCollision && dut.io_publishUseValue &&
                dut.io_explicitPublishFire && dut.io_scalarRedundantWithExplicit,
            "same-block explicit count did not override scalar accumulation");
    tick(dut);
    query(dut, 0, 0);
    require(dut.io_queryStoreCount == 5,
            "same-block explicit authority did not reach the range row");

    idle(dut);
    dut.io_scalarValid = 1;
    dut.io_scalarStid = 0;
    dut.io_scalarBid = 1;
    dut.io_explicitValid = 1;
    dut.io_explicitStid = 0;
    dut.io_explicitBid = 2;
    dut.io_explicitValue = 7;
    dut.eval();
    require(dut.io_scalarInputAccepted && dut.io_explicitInputAccepted,
            "different-block sources were not retained together");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_differentBlockCollision && !dut.io_publishUseValue &&
                dut.io_publishBid == 1 && dut.io_scalarPublishFire,
            "different-block collision did not publish scalar closure first");
    tick(dut);
    dut.eval();
    require(dut.io_publishValid && dut.io_publishUseValue &&
                dut.io_publishBid == 2 && dut.io_explicitPublishFire,
            "retained explicit count did not follow scalar closure");
    tick(dut);
    query(dut, 0, 1);
    require(dut.io_queryCountKnown && dut.io_queryStoreCount == 0,
            "scalar zero-store block did not close with accumulated count");
    query(dut, 0, 2);
    require(dut.io_queryCountKnown && dut.io_queryStoreCount == 7,
            "serialized explicit block count is incorrect");

    // The publisher can retain against a temporarily missing range row. Once
    // coherent allocation arrives, alloc and count publication apply together.
    dut.io_orderLiveCount_0 = 4;
    capture_explicit(dut, 0, 3, 9);
    dut.eval();
    require(dut.io_publishValid && dut.io_explicitPending &&
                !dut.io_explicitPublishFire,
            "missing sink row did not backpressure the retained event");
    dut.io_allocValid = 1;
    dut.io_allocStid = 0;
    dut.io_allocBid = 3;
    dut.eval();
    require(dut.io_allocAccepted && dut.io_explicitPublishFire,
            "coherent alloc/count arrival did not drain the retained event");
    tick(dut);
    idle(dut);
    query(dut, 0, 3);
    require(dut.io_queryHit && dut.io_queryCountKnown &&
                dut.io_queryStoreCount == 9,
            "retained explicit count did not populate the newly resident row");

    std::cout << "brob-store-count-publisher-probe: PASS\n";
    return 0;
}
