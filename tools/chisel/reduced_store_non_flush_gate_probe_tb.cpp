#include <cstdlib>
#include <iostream>

#include "VReducedStoreNonFlushGateProbe.h"
#include "verilated.h"

static void tick(VReducedStoreNonFlushGateProbe &dut) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "reduced-store-non-flush-gate-probe: " << message << '\n';
        std::exit(1);
    }
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedStoreNonFlushGateProbe dut;
    dut.reset = 1;
    dut.io_flushValid = 0;
    dut.io_commitValid = 0;
    dut.io_commitStore = 0;
    dut.io_commitBlockBid = 0;
    dut.io_commitLsId = 0;
    dut.io_rowValid = 1;
    dut.io_rowScalarIex = 1;
    dut.io_nonFlushValid = 0;
    dut.io_nonFlushHeadBid = 0;
    dut.io_nonFlushPrefixCount = 0;
    dut.io_oldestBlockValid = 0;
    dut.io_oldestBlockBid = 0;
    dut.io_oldestRobValid = 0;
    dut.io_oldestRobBid = 0;
    dut.io_oldestRobLsId = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    // Block 9 is outside the safe [8, 8] prefix. Its commit is retained.
    dut.io_commitValid = 1;
    dut.io_commitStore = 1;
    dut.io_commitBlockBid = 9;
    dut.io_commitLsId = 1;
    dut.io_nonFlushValid = 1;
    dut.io_nonFlushHeadBid = 8;
    dut.io_nonFlushPrefixCount = 1;
    dut.eval();
    require(dut.io_blockedByNonFlush, "unsafe committed store was not blocked");
    require(dut.io_pendingMarkMask == 0, "unsafe committed store marked STQ early");
    tick(dut);
    dut.io_commitValid = 0;
    dut.io_commitStore = 0;
    dut.eval();
    require(dut.io_pendingMarkMask == 0 && !dut.io_markValid,
            "retained unsafe store escaped before frontier advance");

    // Advancing the same STID prefix to [8, 9] releases the retained event.
    dut.io_nonFlushPrefixCount = 2;
    tick(dut);
    dut.eval();
    require(dut.io_pendingMarkMask == 1 && dut.io_markValid && dut.io_markIndex == 0,
            "retained store did not become markable inside the strong prefix");

    // Accepted recovery clears retained and pending ownership.
    dut.io_flushValid = 1;
    tick(dut);
    dut.io_flushValid = 0;
    dut.eval();
    require(dut.io_pendingMarkMask == 0 && !dut.io_markValid,
            "flush did not clear non-flush-gated store ownership");

    // A later commit LSID in the exact oldest running Linx block makes the
    // retained scalar store locally non-flushable without completing the block.
    dut.io_nonFlushValid = 0;
    dut.io_nonFlushPrefixCount = 0;
    dut.io_oldestBlockValid = 1;
    dut.io_oldestBlockBid = 9;
    dut.io_commitValid = 1;
    dut.io_commitStore = 1;
    dut.io_commitBlockBid = 9;
    dut.io_commitLsId = 1;
    tick(dut);
    dut.io_commitStore = 0;
    dut.io_commitLsId = 2;
    dut.io_rowValid = 0;
    dut.eval();
    tick(dut);
    dut.io_commitValid = 0;
    dut.io_rowValid = 1;
    dut.eval();
    require(dut.io_earlySafeMatchMask == 1,
            "latched oldest-block LSID frontier did not authorize late scalar row");
    tick(dut);
    dut.eval();
    require(dut.io_pendingMarkMask == 1 && dut.io_markValid,
            "latched early-safe scalar store did not become markable");

    // The same frontier must not authorize tile-owned store behavior.
    dut.io_flushValid = 1;
    tick(dut);
    dut.io_flushValid = 0;
    dut.io_rowScalarIex = 0;
    dut.io_commitValid = 1;
    dut.io_commitStore = 1;
    dut.io_commitLsId = 1;
    tick(dut);
    dut.io_commitStore = 0;
    dut.io_commitLsId = 2;
    dut.io_rowValid = 0;
    tick(dut);
    dut.io_commitValid = 0;
    dut.io_rowValid = 1;
    dut.eval();
    require(dut.io_pendingMarkMask == 0 && !dut.io_markValid,
            "scalar early-safe frontier authorized a tile store");

    // The model PE-oldest-LSID fallback directly scans ready resident scalar
    // rows and does not depend on an earlier ROB-store identity capture.
    dut.io_flushValid = 1;
    tick(dut);
    dut.io_flushValid = 0;
    dut.io_rowScalarIex = 1;
    dut.io_oldestRobValid = 1;
    dut.io_oldestRobBid = 1;
    dut.io_oldestRobLsId = 2;
    dut.eval();
    require(dut.io_residentEarlySafeMask == 1,
            "oldest ROB LSID did not authorize older resident scalar store");
    tick(dut);
    dut.eval();
    require(dut.io_pendingMarkMask == 1 && dut.io_markValid,
            "resident early-safe scalar store did not become markable");

    std::cout << "reduced-store-non-flush-gate-probe: PASS\n";
    return 0;
}
