#include <cstdlib>
#include <iostream>

#include "VScalarLoadCompletionROBProbe.h"
#include "verilated.h"

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-load-completion-rob-probe: " << message << '\n';
        std::exit(1);
    }
}

static void tick(VScalarLoadCompletionROBProbe &dut) {
    dut.clock = 0;
    dut.eval();
    require(!dut.io_protocolError, "protocol error asserted");
    dut.clock = 1;
    dut.eval();
}

static void idle(VScalarLoadCompletionROBProbe &dut) {
    dut.io_allocValid = 0;
    dut.io_externalCompleteValid = 0;
    dut.io_loadValid = 0;
    dut.io_loadRidValid = 0;
    dut.io_loadRidWrap = 0;
    dut.io_loadRidValue = 0;
    dut.io_loadResolveEnable = 1;
    dut.io_loadWritebackEnable = 1;
    dut.io_loadWakeupEnable = 1;
    dut.io_loadDstValid = 0;
    dut.io_loadDstKind = 0;
    dut.io_loadDstTag = 0;
    dut.io_loadData = 0;
    dut.io_loadSpecWakeup = 0;
    dut.io_loadStackValid = 0;
    dut.io_gprClearValid = 0;
    dut.io_gprClearTag = 0;
    dut.io_externalGprWriteValid = 0;
    dut.io_externalGprWriteTag = 0;
    dut.io_externalGprWriteData = 0;
    dut.io_gprReadTag = 0;
    dut.io_singlePortMode = 0;
}

static unsigned allocate(VScalarLoadCompletionROBProbe &dut, unsigned tag,
                         unsigned &wrap) {
    idle(dut);
    dut.io_allocValid = 1;
    dut.io_allocTag = tag;
    dut.eval();
    require(dut.io_allocReady, "ROB allocation was not ready");
    const unsigned value = dut.io_allocRobValue;
    wrap = dut.io_allocRobWrap;
    tick(dut);
    return value;
}

static void external_complete_and_retire(VScalarLoadCompletionROBProbe &dut,
                                         unsigned value) {
    idle(dut);
    dut.io_externalCompleteValid = 1;
    dut.io_externalCompleteRobValue = value;
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_commitValid && dut.io_commitRobValue == value,
            "externally completed row did not become committable");
    tick(dut);
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarLoadCompletionROBProbe dut;

    dut.reset = 1;
    idle(dut);
    dut.io_allocTag = 0;
    dut.io_externalCompleteRobValue = 0;
    dut.io_loadDstValid = 0;
    dut.io_loadDstKind = 0;
    dut.io_loadDstTag = 0;
    dut.io_loadData = 0;
    dut.io_loadSpecWakeup = 0;
    dut.io_loadStackValid = 0;
    dut.io_loadWritebackEnable = 1;
    dut.io_loadWakeupEnable = 1;
    dut.io_gprClearValid = 0;
    dut.io_gprClearTag = 0;
    dut.io_externalGprWriteValid = 0;
    dut.io_externalGprWriteTag = 0;
    dut.io_externalGprWriteData = 0;
    dut.io_gprReadTag = 0;
    dut.io_singlePortMode = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    unsigned wrap0 = 0;
    unsigned wrap1 = 0;
    const unsigned row0 = allocate(dut, 1, wrap0);
    const unsigned row1 = allocate(dut, 2, wrap1);
    require(row0 == 0 && row1 == 1 && wrap0 == 0 && wrap1 == 0 && dut.io_size == 2,
            "ROB allocation order is incorrect");

    idle(dut);
    dut.io_externalCompleteValid = 1;
    dut.io_externalCompleteRobValue = row0;
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidValue = row1;
    dut.eval();
    require(dut.io_lookupRowValid && dut.io_collision &&
                !dut.io_loadResolveReady && !dut.io_scalarLoadSelected,
            "external completion did not hold the colliding scalar load");
    tick(dut);

    idle(dut);
    dut.eval();
    require(dut.io_commitValid && dut.io_commitRobValue == row0,
            "externally completed head row did not become committable");
    tick(dut);

    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidValue = row1;
    dut.eval();
    require(dut.io_lookupRowValid && dut.io_loadResolveReady &&
                dut.io_scalarLoadSelected && !dut.io_collision,
            "held scalar load did not complete the exact live ROB row");
    tick(dut);

    idle(dut);
    dut.eval();
    require(dut.io_commitValid && dut.io_commitRobValue == row1,
            "scalar-load-completed row did not become committable");
    tick(dut);

    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidValue = row1;
    dut.eval();
    require(!dut.io_lookupRowValid && dut.io_lookupBlockedByFree &&
                !dut.io_scalarLoadSelected,
            "retired ROB identity was accepted as a live scalar load");
    require(dut.io_size == 0, "ROB did not return to empty");

    for (unsigned tag = 3; tag <= 8; ++tag) {
        unsigned wrap = 1;
        const unsigned value = allocate(dut, tag, wrap);
        require(value == tag - 1 && wrap == 0,
                "ROB did not advance through the pre-wrap slots");
        external_complete_and_retire(dut, value);
    }

    unsigned wrapped = 0;
    const unsigned wrappedRow = allocate(dut, 9, wrapped);
    require(wrappedRow == 0 && wrapped == 1,
            "ROB allocation did not expose the wrapped RID generation");

    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = 0;
    dut.io_loadRidValue = wrappedRow;
    dut.eval();
    require(!dut.io_lookupRowValid && dut.io_lookupBlockedByStaleRid &&
                !dut.io_loadResolveReady && !dut.io_scalarLoadSelected,
            "stale pre-wrap RID was allowed to complete a reused ROB slot");

    external_complete_and_retire(dut, wrappedRow);

    unsigned wrapped1 = 0;
    const unsigned exactRow = allocate(dut, 10, wrapped1);
    require(exactRow == 1 && wrapped1 == 1,
            "post-wrap exact-completion row identity is incorrect");
    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = 1;
    dut.io_loadRidValue = exactRow;
    dut.eval();
    require(dut.io_lookupRowValid && dut.io_loadResolveReady &&
                dut.io_scalarLoadSelected,
            "exact wrapped RID did not reach the scalar completion port");
    tick(dut);
    idle(dut);
    dut.eval();
    require(dut.io_commitValid && dut.io_commitRobValue == exactRow,
            "exact wrapped scalar completion did not become committable");
    tick(dut);
    require(dut.io_size == 0, "ROB did not drain after wrapped exact completion");

    unsigned gprWrap = 0;
    const unsigned gprRow = allocate(dut, 11, gprWrap);
    require(gprRow == 2 && gprWrap == 1,
            "GPR completion row identity is incorrect");
    idle(dut);
    dut.io_gprClearValid = 1;
    dut.io_gprClearTag = 40;
    tick(dut);

    for (unsigned blockedSink = 0; blockedSink < 3; ++blockedSink) {
        idle(dut);
        dut.io_loadValid = 1;
        dut.io_loadRidValid = 1;
        dut.io_loadRidWrap = gprWrap;
        dut.io_loadRidValue = gprRow;
        dut.io_loadDstValid = 1;
        dut.io_loadDstKind = 1;
        dut.io_loadDstTag = 40;
        dut.io_loadData = 0xbb;
        dut.io_loadResolveEnable = blockedSink != 0;
        dut.io_loadWritebackEnable = blockedSink != 1;
        dut.io_loadWakeupEnable = blockedSink != 2;
        dut.eval();
        require(dut.io_lookupRowValid && !dut.io_loadResolveReady &&
                    !dut.io_scalarLoadSelected && !dut.io_loadWritebackSelected &&
                    !dut.io_loadWakeupPublished,
                "public completion allow did not hold resident scalar W2");
        tick(dut);
        idle(dut);
        dut.io_gprReadTag = 40;
        dut.eval();
        require(!dut.io_gprReadReady && dut.io_size == 1,
                "blocked public completion allow mutated GPR or ROB state");
    }

    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = gprWrap;
    dut.io_loadRidValue = gprRow;
    dut.io_loadDstValid = 1;
    dut.io_loadDstKind = 1;
    dut.io_loadDstTag = 40;
    dut.io_loadData = 0xbb;
    dut.io_externalGprWriteValid = 1;
    dut.io_externalGprWriteTag = 40;
    dut.io_externalGprWriteData = 0xaa;
    dut.eval();
    require(dut.io_lookupRowValid && !dut.io_loadResolveReady &&
                !dut.io_scalarLoadSelected && dut.io_loadBlockedByExternalWrite &&
                dut.io_externalGprWriteFire,
            "same-tag external write did not hold scalar W2 before all side effects");
    tick(dut);

    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = gprWrap;
    dut.io_loadRidValue = gprRow;
    dut.io_loadDstValid = 1;
    dut.io_loadDstKind = 1;
    dut.io_loadDstTag = 40;
    dut.io_loadData = 0xbb;
    dut.eval();
    require(dut.io_loadResolveReady && dut.io_scalarLoadSelected &&
                dut.io_loadWritebackSelected && dut.io_loadWakeupPublished,
            "scalar W2 did not atomically resolve, write back, and wake up");
    tick(dut);
    idle(dut);
    dut.io_gprReadTag = 40;
    dut.eval();
    require(dut.io_gprReadReady && dut.io_gprReadData == 0xbb,
            "scalar W2 data and ready-table state did not commit together");
    require(dut.io_commitValid && dut.io_commitRobValue == gprRow,
            "GPR-completed scalar row did not become committable");
    tick(dut);

    unsigned dualWrap = 0;
    const unsigned dualRow = allocate(dut, 12, dualWrap);
    idle(dut);
    dut.io_gprClearValid = 1;
    dut.io_gprClearTag = 41;
    tick(dut);
    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = dualWrap;
    dut.io_loadRidValue = dualRow;
    dut.io_loadDstValid = 1;
    dut.io_loadDstKind = 1;
    dut.io_loadDstTag = 41;
    dut.io_loadData = 0xcc;
    dut.io_externalGprWriteValid = 1;
    dut.io_externalGprWriteTag = 42;
    dut.io_externalGprWriteData = 0xdd;
    dut.eval();
    require(dut.io_loadResolveReady && dut.io_scalarLoadSelected &&
                dut.io_loadWritebackSelected && dut.io_loadWakeupPublished &&
                dut.io_externalGprWriteFire && !dut.io_loadBlockedByExternalWrite,
            "independent external and scalar GPR write ports did not complete together");
    tick(dut);
    idle(dut);
    dut.io_gprReadTag = 41;
    dut.eval();
    require(dut.io_gprReadReady && dut.io_gprReadData == 0xcc &&
                dut.io_commitValid && dut.io_commitRobValue == dualRow,
            "dual-port scalar completion did not preserve GPR or ROB state");
    tick(dut);
    require(dut.io_size == 0, "ROB did not drain after dual-port physical sink scenario");

    unsigned singleWrap = 0;
    const unsigned singleRow = allocate(dut, 13, singleWrap);
    idle(dut);
    dut.io_gprClearValid = 1;
    dut.io_gprClearTag = 43;
    tick(dut);
    idle(dut);
    dut.io_singlePortMode = 1;
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = singleWrap;
    dut.io_loadRidValue = singleRow;
    dut.io_loadDstValid = 1;
    dut.io_loadDstKind = 1;
    dut.io_loadDstTag = 43;
    dut.io_loadData = 0xee;
    dut.io_externalGprWriteValid = 1;
    dut.io_externalGprWriteTag = 44;
    dut.io_externalGprWriteData = 0xff;
    dut.eval();
    require(!dut.io_loadResolveReady && !dut.io_scalarLoadSelected &&
                dut.io_loadBlockedByExternalWrite && dut.io_externalGprWriteFire,
            "one-port sink did not serialize independent external and scalar writes");
    tick(dut);
    idle(dut);
    dut.io_singlePortMode = 1;
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = singleWrap;
    dut.io_loadRidValue = singleRow;
    dut.io_loadDstValid = 1;
    dut.io_loadDstKind = 1;
    dut.io_loadDstTag = 43;
    dut.io_loadData = 0xee;
    dut.eval();
    require(dut.io_loadResolveReady && dut.io_scalarLoadSelected &&
                dut.io_loadWritebackSelected && dut.io_loadWakeupPublished,
            "one-port scalar W2 did not retry after external write released");
    tick(dut);
    idle(dut);
    dut.io_singlePortMode = 1;
    dut.io_gprReadTag = 43;
    dut.eval();
    require(dut.io_gprReadReady && dut.io_gprReadData == 0xee &&
                dut.io_commitValid && dut.io_commitRobValue == singleRow,
            "one-port retry did not commit GPR and ROB state atomically");
    tick(dut);

    unsigned localWrap = 0;
    const unsigned localRow = allocate(dut, 14, localWrap);
    idle(dut);
    dut.io_loadValid = 1;
    dut.io_loadRidValid = 1;
    dut.io_loadRidWrap = localWrap;
    dut.io_loadRidValue = localRow;
    dut.io_loadDstValid = 1;
    dut.io_loadDstKind = 2;
    dut.io_loadDstTag = 1;
    dut.io_loadData = 0x1234;
    dut.eval();
    require(dut.io_protocolError && !dut.io_loadResolveReady &&
                !dut.io_scalarLoadSelected && !dut.io_loadWritebackSelected &&
                !dut.io_loadWakeupPublished,
            "unsupported T destination did not surface a blocking contract error");

    std::cout << "scalar-load-completion-rob-probe: PASS\n";
    return 0;
}
