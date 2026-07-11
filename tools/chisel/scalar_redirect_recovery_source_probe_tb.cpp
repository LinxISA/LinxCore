#include <cstdlib>
#include <iostream>

#include "VScalarRedirectRecoverySourceProbe.h"
#include "verilated.h"

static void tick(VScalarRedirectRecoverySourceProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-redirect-recovery-source-probe: " << message << '\n';
        std::exit(1);
    }
}

static void set_id(uint8_t &valid, uint8_t &wrap, uint8_t &value,
                   bool next_valid, bool next_wrap, int next_value) {
    valid = next_valid;
    wrap = next_wrap;
    value = next_value;
}

static void drive_event(VScalarRedirectRecoverySourceProbe &dut,
                        int block_bid, bool bid_wrap, int bid_value,
                        bool rid_valid, bool rid_wrap, int rid_value,
                        int lsid_value, int order) {
    dut.io_eventValid = 1;
    dut.io_blockBidValid = 1;
    dut.io_blockBid = block_bid;
    set_id(dut.io_bid_valid, dut.io_bid_wrap, dut.io_bid_value,
           true, bid_wrap, bid_value);
    set_id(dut.io_rid_valid, dut.io_rid_wrap, dut.io_rid_value,
           rid_valid, rid_wrap, rid_value);
    set_id(dut.io_lsId_valid, dut.io_lsId_wrap, dut.io_lsId_value,
           true, false, lsid_value);
    dut.io_resolveLsIdValid = 1;
    dut.io_orderValid = 1;
    dut.io_order = order;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarRedirectRecoverySourceProbe dut;

    dut.reset = 1;
    dut.io_eventValid = 0;
    dut.io_blockBidValid = 0;
    dut.io_blockBid = 0;
    set_id(dut.io_bid_valid, dut.io_bid_wrap, dut.io_bid_value, false, false, 0);
    set_id(dut.io_rid_valid, dut.io_rid_wrap, dut.io_rid_value, false, false, 0);
    set_id(dut.io_lsId_valid, dut.io_lsId_wrap, dut.io_lsId_value, false, false, 0);
    dut.io_resolveLsIdValid = 0;
    dut.io_orderValid = 0;
    dut.io_order = 0;
    dut.io_sourceReady = 0;
    dut.io_sourceResolved = 0;
    dut.io_payloadIntentConsumed = 0;
    dut.io_cancel = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    // 0x12 projects to wrap=0, slot=2 for an eight-entry ring.
    drive_event(dut, 0x12, false, 2, true, false, 4, 5, 0x155);
    dut.eval();
    require(dut.io_eventReady && dut.io_eventAccepted,
            "exact redirect was not accepted into an empty source");
    tick(dut);
    dut.io_eventValid = 0;
    dut.eval();
    require(dut.io_pending && dut.io_sourceValid && !dut.io_published,
            "captured redirect was not retained for publication");
    require(dut.io_sourceBlockBid == 0x12 && dut.io_sourceRid_valid &&
                dut.io_sourceRid_value == 5,
            "published redirect did not preserve block identity or restart RID");
    require(!dut.io_cleanupOrderValid && !dut.io_cleanupResolveLsIdValid,
            "private sidecars were exposed before matched payload consumption");

    dut.io_sourceReady = 1;
    dut.eval();
    require(dut.io_sourceAccepted,
            "retained redirect did not publish when the fabric became ready");
    tick(dut);
    dut.eval();
    require(dut.io_pending && dut.io_published && !dut.io_sourceValid &&
                !dut.io_cleanupOrderValid,
            "publish-once state exposed sidecars without payload ownership");

    // Matched payload consumption exposes old sidecars while source resolution
    // atomically admits the next event.
    // 0x1b projects to wrap=1, slot=3.
    dut.io_sourceResolved = 1;
    dut.io_payloadIntentConsumed = 1;
    drive_event(dut, 0x1b, true, 3, true, true, 6, 7, 0x2aa);
    dut.eval();
    require(dut.io_eventReady && dut.io_eventAccepted,
            "resolve-and-replace did not accept the next redirect");
    require(dut.io_cleanupOrderValid && dut.io_cleanupOrder == 0x155 &&
                dut.io_cleanupResolveLsIdValid && dut.io_cleanupLsId_value == 5,
            "matched payload consumption did not authorize retained sidecars");
    tick(dut);
    dut.io_eventValid = 0;
    dut.io_sourceResolved = 0;
    dut.io_payloadIntentConsumed = 0;
    dut.eval();
    require(dut.io_pending && !dut.io_published && dut.io_sourceValid &&
                dut.io_sourceBlockBid == 0x1b && dut.io_cleanupOrder == 0x2aa,
            "resolve-and-replace did not retain the replacement identity");

    // Cancellation must suppress publication in the cancellation cycle.
    dut.io_cancel = 1;
    dut.eval();
    require(!dut.io_sourceValid && !dut.io_sourceAccepted,
            "cancel leaked a retained redirect into the recovery fabric");
    tick(dut);
    dut.io_cancel = 0;
    dut.eval();
    require(!dut.io_pending && !dut.io_published,
            "cancel did not clear retained redirect state");

    // Mismatched projection remains blocked and cannot publish.
    dut.io_sourceReady = 1;
    drive_event(dut, 0x12, false, 3, true, false, 1, 2, 0x33);
    tick(dut);
    dut.io_eventValid = 0;
    dut.eval();
    require(dut.io_pending && dut.io_blockedByMissingIdentity &&
                !dut.io_sourceValid && !dut.io_sourceAccepted,
            "projection mismatch was not retained as blocked identity");
    dut.io_cancel = 1;
    tick(dut);
    dut.io_cancel = 0;

    // A valid full BID and ring BID still cannot publish without source RID.
    drive_event(dut, 0x12, false, 2, false, false, 1, 2, 0x44);
    tick(dut);
    dut.io_eventValid = 0;
    dut.eval();
    require(dut.io_pending && dut.io_blockedByMissingIdentity &&
                !dut.io_sourceValid,
            "invalid source RID was not rejected");

    std::cout << "scalar-redirect-recovery-source-probe-status=pass\n";
    return 0;
}
