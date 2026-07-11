#include <cstdlib>
#include <iostream>

#include "VRecoveryProducerProbe.h"
#include "verilated.h"

static void tick(VRecoveryProducerProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "recovery-producer-probe: " << message << '\n';
        std::exit(1);
    }
}

static void wait_for_intent(VRecoveryProducerProbe &dut,
                            int type,
                            int block_bid,
                            int stid,
                            const char *message,
                            int max_cycles = 24) {
    for (int cycle = 0; cycle < max_cycles; ++cycle) {
        dut.eval();
        if (dut.io_intentValid && dut.io_intentType == type &&
            dut.io_intentBlockBid == block_bid && dut.io_intentStid == stid) {
            return;
        }
        tick(dut);
    }
    require(false, message);
}

static void consume_intent(VRecoveryProducerProbe &dut) {
    dut.io_intentReady = 1;
    tick(dut);
    dut.io_intentReady = 0;
}

static void drain(VRecoveryProducerProbe &dut, const char *message) {
    dut.io_intentReady = 1;
    for (int cycle = 0; cycle < 48; ++cycle) {
        dut.eval();
        if (!dut.io_pending) {
            dut.io_intentReady = 0;
            return;
        }
        tick(dut);
    }
    require(false, message);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VRecoveryProducerProbe dut;

    dut.reset = 1;
    dut.io_bccValid = 0;
    dut.io_bccBlockBid = 0;
    dut.io_bccStid = 0;
    dut.io_bccExecEngine = 0;
    dut.io_slowValid = 0;
    dut.io_slowBlockBid = 0;
    dut.io_slowStid = 0;
    dut.io_slowFetchTpc = 0;
    dut.io_peValid = 0;
    dut.io_peBlockBid = 0;
    dut.io_peStid = 0;
    dut.io_peId = 0;
    dut.io_peFetchTpc = 0;
    dut.io_stall = 0;
    dut.io_stallProgress = 0;
    dut.io_stallIdentityValid = 0;
    dut.io_stallBlockBid = 0;
    dut.io_stallStid = 0;
    dut.io_intentReady = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    dut.io_bccValid = 1;
    dut.io_bccBlockBid = 0x101;
    dut.io_bccStid = 0;
    dut.io_bccExecEngine = 1;
    dut.io_slowValid = 1;
    dut.io_slowBlockBid = 0x202;
    dut.io_slowStid = 1;
    dut.io_slowFetchTpc = 0x22334455;
    dut.eval();
    require(dut.io_bccReady && dut.io_bccAccepted,
            "BCC miss-predict was not admitted to its retained queue");
    require(dut.io_slowReady && dut.io_slowAccepted,
            "IEX slow-insert was not independently admitted");
    tick(dut);
    dut.io_bccValid = 0;
    dut.io_slowValid = 0;

    wait_for_intent(dut, 0, 0x101, 0,
                    "BCC miss-predict did not reach cleanup with exact block BID");
    require(dut.io_intentBaseOnBid && !dut.io_intentImmediate,
            "BCC miss-predict received the wrong Linx recovery scope");
    require(dut.io_intentExecEngine == 1,
            "BCC miss-predict did not preserve the model IEX engine class");
    require(dut.io_pending,
            "independent IEX report was lost behind blocked BCC cleanup");
    consume_intent(dut);

    wait_for_intent(dut, 2, 0x202, 1,
                    "IEX slow-insert did not reach cleanup after BCC consumption");
    require(dut.io_intentBaseOnBid && dut.io_intentImmediate,
            "IEX slow-insert did not preserve immediate Linx nuke semantics");
    require(dut.io_intentFetchTpcValid && dut.io_intentFetchTpc == 0x22334455,
            "IEX slow-insert did not preserve the exact Linx restart target");
    consume_intent(dut);
    drain(dut, "BCC/IEX producer sequence did not drain");

    dut.io_peValid = 1;
    dut.io_peBlockBid = 0x303;
    dut.io_peStid = 1;
    dut.io_peId = 1;
    dut.io_peFetchTpc = 0x12345678;
    dut.eval();
    require(dut.io_peReady && dut.io_peAccepted,
            "PE mismatch was not admitted to its retained queue");
    tick(dut);
    dut.io_peValid = 0;
    wait_for_intent(dut, 3, 0x303, 1,
                    "PE mismatch did not reach cleanup with exact block identity");
    require(!dut.io_intentBaseOnBid && dut.io_intentPeId == 1,
            "PE mismatch did not retain PE-scoped inner-flush semantics");
    require(dut.io_intentFetchTpcValid && dut.io_intentFetchTpc == 0x12345678,
            "PE mismatch did not preserve the exact Linx restart target");
    consume_intent(dut);
    drain(dut, "PE recovery sequence did not drain");

    dut.io_stall = 1;
    dut.io_stallBlockBid = 0x404;
    dut.io_stallStid = 0;
    tick(dut);
    tick(dut);
    dut.eval();
    require(dut.io_stallBlockedByMissingIdentity,
            "IEX watchdog did not reject a threshold event without full identity");
    tick(dut);
    dut.eval();
    require(dut.io_stallBlockedByMissingIdentity && !dut.io_stallTriggerCaptured,
            "missing identity was not retained as a blocked watchdog condition");
    dut.io_stallIdentityValid = 1;
    dut.eval();
    require(dut.io_stallTriggerCaptured,
            "IEX watchdog did not capture the exact identity once supplied");
    tick(dut);
    dut.io_stall = 0;
    dut.io_stallIdentityValid = 0;
    wait_for_intent(dut, 4, 0x404, 0,
                    "IEX watchdog did not publish a full-BID fast replay");
    require(dut.io_intentBaseOnBid && !dut.io_intentImmediate,
            "IEX watchdog emitted the wrong recovery class");
    consume_intent(dut);
    drain(dut, "IEX watchdog recovery sequence did not drain");

    std::cout << "recovery-producer-probe-status=pass\n";
    return 0;
}
