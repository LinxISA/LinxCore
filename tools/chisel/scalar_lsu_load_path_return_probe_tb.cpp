#include <cstdlib>
#include <iostream>

#include "VScalarLSULoadPathReturnProbe.h"
#include "verilated.h"

static unsigned publication_count = 0;

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-lsu-load-path-return-probe: " << message << '\n';
        std::exit(1);
    }
}

static void tick(VScalarLSULoadPathReturnProbe &dut) {
    dut.clock = 0;
    dut.eval();
    require(!dut.io_protocolError, "canonical load-return protocol error asserted");
    if (dut.io_publicationAccepted) {
        publication_count++;
    }
    dut.clock = 1;
    dut.eval();
}

static void idle(VScalarLSULoadPathReturnProbe &dut) {
    dut.io_hardFlush = 0;
    dut.io_preciseFlushValid = 0;
    dut.io_allocValid = 0;
    dut.io_launchValid = 0;
    dut.io_drainReady = 0;
    dut.io_sideEffectReady = 1;
}

static unsigned allocate(VScalarLSULoadPathReturnProbe &dut, unsigned stid,
                         unsigned pipe, unsigned bid, unsigned addr) {
    idle(dut);
    dut.io_allocValid = 1;
    dut.io_allocStid = stid;
    dut.io_allocPipe = pipe;
    dut.io_allocBid = bid;
    dut.io_allocAddr = addr;
    dut.eval();
    require(dut.io_allocReady && dut.io_allocAccepted,
            "expected LIQ allocation was not accepted");
    const unsigned index = dut.io_allocIndex;
    tick(dut);
    return index;
}

static void launch(VScalarLSULoadPathReturnProbe &dut, unsigned index) {
    idle(dut);
    dut.io_launchValid = 1;
    dut.io_launchIndex = index;
    dut.eval();
    require(dut.io_launchReady && dut.io_launchAccepted,
            "expected reserved load launch was not accepted");
    tick(dut);
}

static void require_blocked_launch(VScalarLSULoadPathReturnProbe &dut,
                                   unsigned index) {
    idle(dut);
    dut.io_launchValid = 1;
    dut.io_launchIndex = index;
    dut.eval();
    require(!dut.io_launchReady && !dut.io_launchAccepted &&
                dut.io_launchBlockedByReturnCredit,
            "full resident-plus-reserved lane did not block launch");
}

static void drain(VScalarLSULoadPathReturnProbe &dut, unsigned stid,
                  unsigned pipe, unsigned bid, unsigned long long data) {
    idle(dut);
    dut.io_drainReady = 1;
    dut.eval();
    require(dut.io_drainValid && dut.io_drainFire,
            "expected canonical LRET drain was not accepted");
    if (dut.io_drainStid != stid || dut.io_drainPipe != pipe ||
        dut.io_drainBid != bid || dut.io_drainData != data) {
        std::cerr << "expected stid=" << stid << " pipe=" << pipe
                  << " bid=" << bid << " data=" << data
                  << " observed stid=" << static_cast<unsigned>(dut.io_drainStid)
                  << " pipe=" << static_cast<unsigned>(dut.io_drainPipe)
                  << " bid=" << static_cast<unsigned>(dut.io_drainBid)
                  << " data=" << dut.io_drainData << '\n';
        require(false, "canonical LRET drain identity or data mismatch");
    }
    tick(dut);
}

static void advance_to_publication(VScalarLSULoadPathReturnProbe &dut) {
    idle(dut);
    for (unsigned cycle = 0; cycle < 6; ++cycle) {
        dut.eval();
        if (dut.io_publicationValid) {
            return;
        }
        tick(dut);
    }
    require(false, "timed out waiting for the E4 publication cycle");
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarLSULoadPathReturnProbe dut;

    dut.reset = 1;
    dut.io_hardFlush = 0;
    dut.io_preciseFlushValid = 0;
    dut.io_preciseFlushStid = 0;
    dut.io_preciseFlushBid = 0;
    dut.io_allocValid = 0;
    dut.io_allocStid = 0;
    dut.io_allocPipe = 0;
    dut.io_allocBid = 0;
    dut.io_allocAddr = 0;
    dut.io_launchValid = 0;
    dut.io_launchIndex = 0;
    dut.io_drainReady = 0;
    dut.io_sideEffectReady = 1;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    const unsigned row0 = allocate(dut, 0, 0, 1, 0);
    const unsigned row1 = allocate(dut, 0, 0, 2, 1);
    const unsigned row2 = allocate(dut, 0, 0, 3, 2);
    const unsigned row3 = allocate(dut, 0, 1, 4, 3);

    launch(dut, row0);
    launch(dut, row1);
    require_blocked_launch(dut, row2);
    launch(dut, row3);

    idle(dut);
    for (unsigned cycle = 0; cycle < 6; ++cycle) {
        tick(dut);
    }
    dut.eval();
    require(publication_count == 3,
            "E4 hits did not publish atomically to ResolveQ and LRET");
    require(dut.io_lane0Count == 2 && dut.io_lane1Count == 1 &&
                dut.io_lane2Count == 0 && dut.io_lane3Count == 0 &&
                dut.io_totalCount == 3 && dut.io_reservedCount == 0,
            "canonical per-lane resident and reservation counts are incorrect");

    drain(dut, 0, 1, 4, 3);

    idle(dut);
    dut.io_launchValid = 1;
    dut.io_launchIndex = row2;
    dut.io_drainReady = 1;
    dut.eval();
    require(dut.io_launchReady && dut.io_launchAccepted &&
                dut.io_drainFire && dut.io_drainBid == 1,
            "same-lane drain did not create same-cycle launch credit");
    tick(dut);

    advance_to_publication(dut);
    dut.io_drainReady = 1;
    dut.eval();
    require(dut.io_publicationAccepted && dut.io_drainFire &&
                dut.io_drainBid == 2,
            "E4 enqueue and resident drain did not complete in one lane cycle");
    tick(dut);
    require(publication_count == 4,
            "released lane credit did not permit the blocked launch to publish");
    drain(dut, 0, 0, 3, 2);

    const unsigned hardFlushRow = allocate(dut, 0, 0, 5, 4);
    launch(dut, hardFlushRow);
    advance_to_publication(dut);
    dut.io_hardFlush = 1;
    dut.eval();
    require(!dut.io_publicationValid && !dut.io_publicationAccepted &&
                !dut.io_protocolError,
            "hard flush did not squash a coincident E4 publication");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_totalCount == 0 && dut.io_reservedCount == 0,
            "hard flush did not clear canonical LRET and reservation state");

    const unsigned preciseFlushRow = allocate(dut, 0, 0, 6, 5);
    launch(dut, preciseFlushRow);
    advance_to_publication(dut);
    dut.io_preciseFlushValid = 1;
    dut.io_preciseFlushStid = 0;
    dut.io_preciseFlushBid = 6;
    dut.eval();
    require(!dut.io_publicationValid && !dut.io_publicationAccepted &&
                !dut.io_protocolError,
            "precise flush did not squash a coincident E4 publication");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_totalCount == 0 && dut.io_reservedCount == 0,
            "precise flush did not clear in-flight reservation state");

    const unsigned w2BackpressureRow = allocate(dut, 1, 1, 7, 6);
    launch(dut, w2BackpressureRow);
    advance_to_publication(dut);
    tick(dut);
    idle(dut);
    dut.io_drainReady = 1;
    dut.io_sideEffectReady = 0;
    dut.eval();
    require(dut.io_drainFire && dut.io_drainBid == 7,
            "canonical pipeline did not accept the retained LRET head");
    tick(dut);
    require(dut.io_w1ValidMask != 0,
            "canonical pipeline did not retain the drained return in W1");
    idle(dut);
    dut.io_sideEffectReady = 0;
    tick(dut);
    require(dut.io_w2ValidMask != 0 && dut.io_completionMask == 0 &&
                dut.io_returnPending && !dut.io_returnEmpty,
            "W2 did not retain the return in exported quiescence under backpressure");
    tick(dut);
    require(dut.io_w2ValidMask != 0 && dut.io_completionMask == 0,
            "W2 return was not stable across sustained backpressure");
    dut.io_sideEffectReady = 1;
    dut.eval();
    require(dut.io_completionMask != 0,
            "W2 did not publish after every required side effect became ready");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_pipelineEmpty && !dut.io_returnPending && dut.io_returnEmpty,
            "canonical W1/W2 pipeline did not clear after atomic completion");

    std::cout << "scalar-lsu-load-path-return-probe: PASS\n";
    return 0;
}
