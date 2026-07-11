#include <cstdlib>
#include <iostream>

#include "VRecoveryClassMergeProbe.h"
#include "verilated.h"

namespace {

constexpr int kMissPredFlush = 0;
constexpr int kPeReplay = 1;
constexpr int kNukeFlush = 2;
constexpr int kInnerFlush = 3;
constexpr int kFastReplay = 4;
constexpr int kScalar = 0;
constexpr int kGlobalFlush = 0;
constexpr int kGlobalReplay = 1;
constexpr int kPeScoped = 2;

void tick(VRecoveryClassMergeProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "recovery-class-merge-probe: " << message << '\n';
        std::exit(1);
    }
}

void clear_input(VRecoveryClassMergeProbe &dut) {
    dut.io_inValid = 0;
}

void drive_request(VRecoveryClassMergeProbe &dut,
                   int type,
                   int block_bid,
                   int stid,
                   int pe,
                   int rid,
                   bool fetch_tpc_valid = true) {
    dut.io_inValid = 1;
    dut.io_inType = type;
    dut.io_inBlockBid = block_bid;
    dut.io_inStid = stid;
    dut.io_inPe = pe;
    dut.io_inRid = rid;
    dut.io_inExecEngine = kScalar;
    dut.io_inFetchTpcValid = fetch_tpc_valid;
    dut.eval();
}

void accept_request(VRecoveryClassMergeProbe &dut,
                    int type,
                    int block_bid,
                    int stid,
                    int pe,
                    int rid,
                    bool fetch_tpc_valid = true) {
    drive_request(dut, type, block_bid, stid, pe, rid, fetch_tpc_valid);
    require(dut.io_inReady && dut.io_inAccepted, "valid scoped report was not accepted");
    tick(dut);
    clear_input(dut);
}

void stage_blocked_output(VRecoveryClassMergeProbe &dut, int block_bid) {
    dut.io_outReady = 0;
    accept_request(dut, kNukeFlush, block_bid, 1, 0, 0);
    dut.eval();
    require(!dut.io_outValid && (dut.io_globalFlushPendingMask & 0x2),
            "STID1 placeholder did not enter the global-flush lane");
    tick(dut);
    dut.eval();
    require(dut.io_outValid && dut.io_outBlockBid == block_bid && dut.io_outStid == 1,
            "STID1 placeholder was not staged into the irrevocable output slot");
}

}  // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VRecoveryClassMergeProbe dut;

    dut.reset = 1;
    dut.io_inValid = 0;
    dut.io_inType = kNukeFlush;
    dut.io_inBlockBid = 0;
    dut.io_inStid = 0;
    dut.io_inPe = 0;
    dut.io_inRid = 0;
    dut.io_inExecEngine = kScalar;
    dut.io_inFetchTpcValid = 1;
    dut.io_oldestBid0 = 0;
    dut.io_oldestBid1 = 0;
    dut.io_oldestBlockComplete = 0;
    dut.io_outReady = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    stage_blocked_output(dut, 0x21);

    accept_request(dut, kPeReplay, 0x12, 0, 0, 2, true);
    dut.eval();
    require(dut.io_outBlockBid == 0x21 && dut.io_outStid == 1,
            "blocked output changed while a PE report entered another STID");
    require(dut.io_pePendingMask == 0x1,
            "PE replay did not enter its parameterized PE lane");

    accept_request(dut, kNukeFlush, 0x12, 0, 0, 2);
    dut.eval();
    require(dut.io_globalFlushPendingMask == 0x1 && dut.io_pePendingMask == 0,
            "same-BID nuke did not cancel the queued PE replay");
    require(dut.io_outBlockBid == 0x21,
            "queued class cancellation modified the irrevocable output");

    drive_request(dut, kInnerFlush, 0x12, 0, 0, 2);
    require(dut.io_inAccepted && dut.io_inDroppedByOlder,
            "same-BID inner flush was not rejected behind an older nuke");
    tick(dut);
    clear_input(dut);

    accept_request(dut, kFastReplay, 0x12, 0, 0, 2);
    dut.eval();
    require(dut.io_globalFlushPendingMask == 0x1 && dut.io_globalReplayPendingMask == 0x1,
            "flush and replay class lanes were not retained independently");

    dut.io_outReady = 1;
    dut.eval();
    require(dut.io_outAccepted && dut.io_outBlockBid == 0x21,
            "STID1 placeholder was not accepted before the independent STID0 action");
    tick(dut);
    dut.io_outReady = 0;
    dut.eval();
    require(dut.io_outValid && dut.io_outType == kFastReplay &&
                dut.io_outClass == kGlobalReplay && dut.io_outBlockBid == 0x12 &&
                dut.io_outStid == 0,
            "older fast replay did not cancel the queued nuke at class dispatch");
    require(dut.io_globalFlushPendingMask == 0 && dut.io_globalReplayPendingMask == 0,
            "replay cancellation left duplicate global class state");

    dut.io_outReady = 1;
    tick(dut);
    dut.io_outReady = 0;
    dut.io_oldestBlockComplete = 1;
    drive_request(dut, kFastReplay, 0x13, 0, 0, 1);
    require(dut.io_inAccepted && dut.io_inDroppedByComplete,
            "completed oldest block did not reject a global replay");
    tick(dut);
    clear_input(dut);
    dut.io_oldestBlockComplete = 0;
    dut.eval();
    require(!dut.io_pending, "completed-block replay left retained class state");

    drive_request(dut, kNukeFlush, 0x40, 2, 0, 0);
    require(!dut.io_inReady && !dut.io_inAccepted && dut.io_inBlockedByStid,
            "out-of-range STID entered class state");
    drive_request(dut, kNukeFlush, 0x40, 0, 2, 0);
    require(!dut.io_inReady && !dut.io_inAccepted && dut.io_inBlockedByPe,
            "out-of-range PE entered class state");
    clear_input(dut);

    stage_blocked_output(dut, 0x31);
    accept_request(dut, kInnerFlush, 0x14, 0, 0, 3);
    drive_request(dut, kPeReplay, 0x14, 0, 0, 2, true);
    require(dut.io_inAccepted && dut.io_inMerged,
            "older same-PE replay did not trigger the model inner-flush merge");
    tick(dut);
    clear_input(dut);
    dut.eval();
    require(dut.io_globalFlushPendingMask == 0x1 && dut.io_pePendingMask == 0,
            "merged inner recovery was not reclassified into the global flush lane");

    dut.io_outReady = 1;
    tick(dut);
    dut.io_outReady = 0;
    dut.eval();
    require(dut.io_outValid && dut.io_outClass == kGlobalFlush &&
                dut.io_outType == kInnerFlush && dut.io_outBlockBid == 0x14 &&
                dut.io_outRid == 2,
            "merged recovery did not preserve the older exact request identity");

    accept_request(dut, kPeReplay, 0x15, 0, 0, 1, true);
    accept_request(dut, kPeReplay, 0x16, 0, 1, 1, true);
    dut.eval();
    require(dut.io_pePendingMask == 0x3,
            "independent PE reports were not retained in separate PE lanes");

    dut.io_outReady = 1;
    tick(dut);
    dut.io_outReady = 0;
    dut.eval();
    require(dut.io_outValid && dut.io_outClass == kPeScoped && dut.io_outPe == 0 &&
                dut.io_outBlockBid == 0x15,
            "PE lane zero was not serialized first");
    dut.io_outReady = 1;
    tick(dut);
    dut.io_outReady = 0;
    dut.eval();
    require(dut.io_outValid && dut.io_outClass == kPeScoped && dut.io_outPe == 1 &&
                dut.io_outBlockBid == 0x16,
            "PE lane one was lost behind PE lane zero");
    dut.io_outReady = 1;
    tick(dut);
    dut.io_outReady = 0;
    dut.eval();
    require(!dut.io_pending, "all accepted recovery classes did not drain");

    std::cout << "recovery-class-merge-probe-status=pass\n";
    return 0;
}
