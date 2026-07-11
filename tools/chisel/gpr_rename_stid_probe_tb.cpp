#include <cstdlib>
#include <iostream>

#include "VGPRRenameStidProbe.h"
#include "verilated.h"

static void tick(VGPRRenameStidProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "gpr-rename-stid-probe: " << message << '\n';
        std::exit(1);
    }
}

static int rename_row(VGPRRenameStidProbe &dut, int stid, int arch, int full_bid, int rid) {
    dut.io_renameValid = 1;
    dut.io_renameStid = stid;
    dut.io_renameArch = arch;
    dut.io_renameFullBid = full_bid;
    dut.io_renameRid = rid;
    dut.eval();
    require(dut.io_renameReady && dut.io_renameAccepted,
            "valid per-STID rename was not accepted");
    const int phys = dut.io_renamePhys;
    tick(dut);
    dut.io_renameValid = 0;
    return phys;
}

static void commit_block(VGPRRenameStidProbe &dut, int stid, int full_bid) {
    dut.io_commitValid = 1;
    dut.io_commitStid = stid;
    dut.io_commitFullBid = full_bid;
    dut.eval();
    require(dut.io_commitAccepted, "valid per-STID block commit was not accepted");
    tick(dut);
    dut.io_commitValid = 0;
}

static void query(VGPRRenameStidProbe &dut, int stid, int arch) {
    dut.io_queryStid = stid;
    dut.io_queryArch = arch;
    dut.eval();
    require(dut.io_queryStidInRange, "query STID unexpectedly out of range");
}

static void reservation_push(VGPRRenameStidProbe &dut, int stid) {
    dut.io_reservationPushValid = 1;
    dut.io_reservationPushStid = stid;
    tick(dut);
    dut.io_reservationPushValid = 0;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VGPRRenameStidProbe dut;

    dut.reset = 1;
    dut.io_renameValid = 0;
    dut.io_renameStid = 0;
    dut.io_renameArch = 0;
    dut.io_renameFullBid = 0;
    dut.io_renameRid = 0;
    dut.io_commitValid = 0;
    dut.io_commitStid = 0;
    dut.io_commitFullBid = 0;
    dut.io_flushValid = 0;
    dut.io_flushStid = 0;
    dut.io_flushFullBid = 0;
    dut.io_queryStid = 0;
    dut.io_queryArch = 0;
    dut.io_reservationFlush = 0;
    dut.io_reservationPushValid = 0;
    dut.io_reservationPushStid = 0;
    dut.io_reservationPopValid = 0;
    dut.io_reservationPopStid = 0;
    dut.io_reservationSelectedValid = 1;
    dut.io_reservationSelectedStid = 0;
    dut.io_reservationSelectedNeedsGpr = 0;
    dut.io_reservationFreePhys = 8;
    dut.io_reservationSelectedMapQFree = 8;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    query(dut, 0, 2);
    require(dut.io_querySmap == 2 && dut.io_queryCmap == 2,
            "STID0 identity map is incorrect");
    query(dut, 1, 2);
    require(dut.io_querySmap == 26 && dut.io_queryCmap == 26,
            "STID1 identity map is not offset by the architectural register count");
    require(dut.io_freeCount == 16, "shared free list did not reserve both identity maps");

    const int stid0_phys = rename_row(dut, 0, 2, 0, 0);
    const int stid1_phys = rename_row(dut, 1, 2, 0, 0);
    require(stid0_phys == 48 && stid1_phys == 49,
            "STIDs did not allocate from one shared physical-tag free list");

    query(dut, 0, 2);
    require(dut.io_querySmap == 48 && dut.io_queryMapQCount == 1,
            "STID0 speculative mapQ state is incorrect");
    query(dut, 1, 2);
    require(dut.io_querySmap == 49 && dut.io_queryMapQCount == 1,
            "STID1 speculative mapQ state is incorrect");

    commit_block(dut, 0, 0);
    query(dut, 0, 2);
    require(dut.io_queryCmap == 48 && dut.io_queryMapQCount == 0,
            "STID0 commit did not retire its own mapQ row");
    query(dut, 1, 2);
    require(dut.io_queryCmap == 26 && dut.io_queryMapQCount == 1,
            "STID0 commit mutated equal-BID STID1 state");

    commit_block(dut, 0, 0);
    query(dut, 1, 2);
    require(dut.io_queryCmap == 26 && dut.io_queryMapQCount == 1,
            "empty STID0 commit consumed STID1 equal-BID state");
    commit_block(dut, 1, 0);
    query(dut, 1, 2);
    require(dut.io_queryCmap == 49 && dut.io_queryMapQCount == 0,
            "STID1 commit did not retire its own mapQ row");

    require(rename_row(dut, 0, 3, 1, 1) == 50,
            "STID0 second allocation did not use the shared free list");
    require(rename_row(dut, 1, 3, 1, 1) == 51,
            "STID1 second allocation did not use the shared free list");
    dut.io_flushValid = 1;
    dut.io_flushStid = 0;
    dut.io_flushFullBid = 1;
    dut.eval();
    require(dut.io_flushApplied, "STID0 recovery flush was not accepted");
    tick(dut);
    dut.io_flushValid = 0;

    query(dut, 0, 3);
    require(dut.io_querySmap == 3 && dut.io_queryMapQCount == 0,
            "STID0 flush did not restore and prune its lane");
    query(dut, 1, 3);
    require(dut.io_querySmap == 51 && dut.io_queryMapQCount == 1,
            "STID0 flush mutated STID1 speculative state");
    dut.io_renameStid = 0;
    dut.io_renameArch = 3;
    dut.io_queryStid = 1;
    dut.eval();
    require(dut.io_renameOldPhys == 3 && dut.io_querySmap == 51,
            "selected capacity query STID corrupted queued-lane old destination mapping");

    dut.io_renameValid = 1;
    dut.io_renameStid = 2;
    dut.io_renameArch = 4;
    dut.io_renameFullBid = 2;
    dut.io_renameRid = 2;
    dut.eval();
    require(!dut.io_renameReady && !dut.io_renameAccepted && dut.io_stateError,
            "out-of-range STID rename was not rejected visibly");
    dut.io_renameValid = 0;
    query(dut, 1, 3);
    require(dut.io_querySmap == 51 && dut.io_queryMapQCount == 1,
            "rejected STID mutated a valid lane");

    reservation_push(dut, 0);
    require(dut.io_reservationPhysCount == 1,
            "shared physical reservation count did not track STID0 push");
    dut.io_reservationSelectedStid = 0;
    dut.io_reservationSelectedNeedsGpr = 1;
    dut.io_reservationFreePhys = 2;
    dut.io_reservationSelectedMapQFree = 1;
    dut.eval();
    require(dut.io_reservationMapQCount == 1 && !dut.io_reservationReady,
            "full STID0 MapQ reservation lane admitted another row");

    dut.io_reservationSelectedStid = 1;
    dut.eval();
    require(dut.io_reservationMapQCount == 0 && dut.io_reservationReady,
            "STID0 MapQ pressure incorrectly blocked independent STID1");
    reservation_push(dut, 1);
    require(dut.io_reservationPhysCount == 2,
            "shared physical reservation count did not include both lanes");
    dut.io_reservationFreePhys = 2;
    dut.eval();
    require(!dut.io_reservationReady,
            "shared physical-register pressure did not block another reservation");

    std::cout << "gpr-rename-stid-probe-status=pass\n";
    return 0;
}
