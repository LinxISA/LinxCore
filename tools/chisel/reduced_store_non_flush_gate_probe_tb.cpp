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
    dut.io_commitBlockBid = 0;
    dut.io_rowValid = 1;
    dut.io_nonFlushValid = 0;
    dut.io_nonFlushHeadBid = 0;
    dut.io_nonFlushPrefixCount = 0;
    tick(dut);
    tick(dut);
    dut.reset = 0;

    // Block 9 is outside the safe [8, 8] prefix. Its commit is retained.
    dut.io_commitValid = 1;
    dut.io_commitBlockBid = 9;
    dut.io_nonFlushValid = 1;
    dut.io_nonFlushHeadBid = 8;
    dut.io_nonFlushPrefixCount = 1;
    dut.eval();
    require(dut.io_blockedByNonFlush, "unsafe committed store was not blocked");
    require(dut.io_pendingMarkMask == 0, "unsafe committed store marked STQ early");
    tick(dut);
    dut.io_commitValid = 0;
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

    std::cout << "reduced-store-non-flush-gate-probe: PASS\n";
    return 0;
}
