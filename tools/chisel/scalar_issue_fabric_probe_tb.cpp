#include <cstdlib>
#include <iostream>

#include "VScalarIssueFabricProbe.h"
#include "verilated.h"

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-issue-fabric-probe: " << message << '\n';
        std::exit(1);
    }
}

static void eval(VScalarIssueFabricProbe &dut) {
    dut.eval();
    require(!dut.io_protocolError, "fabric protocol error asserted");
}

static void tick(VScalarIssueFabricProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

static void idle(VScalarIssueFabricProbe &dut) {
    dut.io_enqueueValid = 0;
    dut.io_enqueueStid = 0;
    dut.io_enqueueRid = 0;
    dut.io_enqueuePc = 0;
    dut.io_enqueueBru = 0;
    dut.io_enqueueStore = 0;
    dut.io_sourceTag = 40;
    dut.io_wakeupValid = 0;
    dut.io_issueReady = 0;
    dut.io_flush = 0;
    dut.io_releaseValid = 0;
    dut.io_releaseStid = 0;
    dut.io_releaseRid = 0;
    dut.io_policyValidMask = 0;
    dut.io_policyStid_0 = 0;
    dut.io_policyStid_1 = 0;
    dut.io_policyRid_0 = 0;
    dut.io_policyRid_1 = 0;
    dut.io_policyAdvance = 0;
}

static void enqueue(VScalarIssueFabricProbe &dut, unsigned stid, unsigned rid, unsigned pc,
                    bool bru = false, bool store = false) {
    idle(dut);
    dut.io_enqueueValid = 1;
    dut.io_enqueueStid = stid;
    dut.io_enqueueRid = rid;
    dut.io_enqueuePc = pc;
    dut.io_enqueueBru = bru;
    dut.io_enqueueStore = store;
    eval(dut);
    require(dut.io_enqueueReady, "fabric did not accept an enqueue");
    tick(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarIssueFabricProbe dut;

    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;

    idle(dut);
    dut.io_policyValidMask = 3;
    dut.io_policyStid_0 = 0;
    dut.io_policyStid_1 = 0;
    dut.io_policyRid_0 = 5;
    dut.io_policyRid_1 = 2;
    eval(dut);
    require(dut.io_policyGrantMask == 2 && dut.io_policySelectedIndex == 1,
            "same-STID arbitration did not choose the older RID");

    dut.io_policyAdvance = 1;
    tick(dut);
    idle(dut);
    dut.io_policyValidMask = 3;
    dut.io_policyStid_0 = 0;
    dut.io_policyStid_1 = 1;
    dut.io_policyRid_0 = 7;
    dut.io_policyRid_1 = 0;
    eval(dut);
    require(dut.io_policyGrantMask == 1,
            "cross-STID arbitration compared unrelated RID ages");
    dut.io_policyAdvance = 1;
    tick(dut);
    idle(dut);
    dut.io_policyValidMask = 3;
    dut.io_policyStid_0 = 0;
    dut.io_policyStid_1 = 1;
    dut.io_policyRid_0 = 7;
    dut.io_policyRid_1 = 0;
    eval(dut);
    require(dut.io_policyGrantMask == 2,
            "cross-STID arbitration did not rotate fairly");

    enqueue(dut, 0, 1, 0x100);
    idle(dut);
    eval(dut);
    require(dut.io_enqueueBank == 1,
            "least-occupied routing did not spill the second row to bank 1");
    enqueue(dut, 0, 2, 0x104);

    idle(dut);
    eval(dut);
    require(dut.io_count == 2 && dut.io_bankOccupancy_0 == 1 &&
                dut.io_bankOccupancy_1 == 1 && dut.io_bankPickMask == 0,
            "blocked rows were not retained one per bank");

    dut.io_wakeupValid = 1;
    eval(dut);
    require(dut.io_bankPickMask == 0,
            "same-cycle wakeup incorrectly affected bank pick");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_bankPickMask == 3 && dut.io_simultaneousPick,
            "wakeup did not expose simultaneous bank-local picks at N+1");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_bankReadAttemptMask == 3 && dut.io_readContention &&
                dut.io_readArbitrationLoss && dut.io_bankReadGrantMask == 1 &&
                dut.io_cancelFire,
            "global I1 arbitration did not grant older bank and cancel loser");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_bankIssueValidMask == 1 && dut.io_bankPickMask == 2,
            "denied row was not retained for retry while winner reached I2");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_bankReadAttemptMask == 2 && dut.io_bankReadGrantMask == 2,
            "cancelled row did not retry and win a later I1 grant");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_bankIssueValidMask == 3 && dut.io_issueContention &&
                dut.io_bankIssueGrantMask == 1 && !dut.io_issueFire,
            "two resident I2 rows did not arbitrate oldest-first under backpressure");

    dut.io_issueReady = 1;
    eval(dut);
    require(dut.io_issueFire && dut.io_issueRid == 1 && dut.io_issueData == 0x128,
            "older bank did not issue first with captured RF data");
    tick(dut);

    idle(dut);
    dut.io_issueReady = 1;
    eval(dut);
    require(dut.io_issueFire && dut.io_issueRid == 2 && dut.io_issueData == 0x128,
            "retried bank did not issue after the older row");
    tick(dut);

    idle(dut);
    dut.io_flush = 1;
    tick(dut);
    enqueue(dut, 0, 3, 0x200, true);
    enqueue(dut, 0, 4, 0x204);
    idle(dut);
    dut.io_wakeupValid = 1;
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_controlFenceActive && dut.io_bankPickMask == 3,
            "resident BRU did not establish a control frontier before bank picks");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_controlFenceBlocked && dut.io_bankControlBlockedMask == 2 &&
                dut.io_bankReadGrantMask == 1 && dut.io_cancelFire,
            "younger same-STID row crossed the unresolved control frontier");
    tick(dut);

    idle(dut);
    dut.io_issueReady = 1;
    eval(dut);
    require(dut.io_issueFire && dut.io_issueRid == 3,
            "control row did not issue ahead of its younger same-STID row");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_controlFenceActive && dut.io_controlFenceBlocked &&
                dut.io_bankReadGrantMask == 0,
            "issued control row stopped fencing before exact release");

    dut.io_releaseValid = 1;
    dut.io_releaseStid = 0;
    dut.io_releaseRid = 3;
    tick(dut);

    idle(dut);
    eval(dut);
    require(!dut.io_controlFenceActive && !dut.io_controlFenceBlocked,
            "exact control release did not remove the frontier");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_bankReadGrantMask == 2,
            "younger same-STID row did not retry after control release");

    idle(dut);
    dut.io_flush = 1;
    tick(dut);
    enqueue(dut, 0, 5, 0x300, true);
    enqueue(dut, 1, 0, 0x304);
    idle(dut);
    dut.io_wakeupValid = 1;
    tick(dut);
    idle(dut);
    tick(dut);
    idle(dut);
    eval(dut);
    require(dut.io_controlFenceActive && dut.io_bankControlBlockedMask == 0 &&
                dut.io_bankReadGrantMask != 0,
            "one STID's control frontier blocked unrelated STID progress");

    idle(dut);
    dut.io_flush = 1;
    tick(dut);
    enqueue(dut, 0, 1, 0x400, false, true);
    enqueue(dut, 0, 2, 0x404, false, true);
    idle(dut);
    dut.io_wakeupValid = 1;
    tick(dut);
    idle(dut);
    tick(dut);
    idle(dut);
    eval(dut);
    require(dut.io_storeOrderBlocked && dut.io_bankStoreOrderBlockedMask == 2 &&
                dut.io_bankReadGrantMask == 1,
            "younger same-STID store crossed the oldest resident store frontier");
    tick(dut);
    idle(dut);
    dut.io_issueReady = 1;
    tick(dut);
    idle(dut);
    dut.io_releaseValid = 1;
    dut.io_releaseRid = 1;
    tick(dut);
    idle(dut);
    tick(dut);
    idle(dut);
    eval(dut);
    require(!dut.io_storeOrderBlocked && dut.io_bankReadGrantMask == 2,
            "younger store did not retry after exact older-store release");

    std::cout << "scalar-issue-fabric-probe: PASS\n";
    return 0;
}
