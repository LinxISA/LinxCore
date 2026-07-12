#include <cstdlib>
#include <iostream>

#include "VScalarGPRIssueWakeupProbe.h"
#include "verilated.h"

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << "scalar-gpr-issue-wakeup-probe: " << message << '\n';
        std::exit(1);
    }
}

static void eval(VScalarGPRIssueWakeupProbe &dut) {
    dut.eval();
    require(!dut.io_protocolError, "GPR protocol error asserted");
}

static void tick(VScalarGPRIssueWakeupProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

static void idle(VScalarGPRIssueWakeupProbe &dut) {
    dut.io_enqueueValid = 0;
    dut.io_sourceTag = 40;
    dut.io_writeRequest = 0;
    dut.io_writeCommit = 0;
    dut.io_writeTag = 40;
    dut.io_writeData = 0;
    dut.io_issueReady = 1;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarGPRIssueWakeupProbe dut;

    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;

    idle(dut);
    dut.io_enqueueValid = 1;
    eval(dut);
    require(dut.io_enqueueReady, "issue queue did not accept dependent row");
    tick(dut);

    idle(dut);
    eval(dut);
    require(!dut.io_sourceReady && !dut.io_selectedValid && !dut.io_pickFire,
            "not-ready physical source became selectable before writeback");

    dut.io_writeRequest = 1;
    dut.io_writeCommit = 0;
    dut.io_writeData = 0x12345678ULL;
    eval(dut);
    require(dut.io_writeReady && !dut.io_writeFire && !dut.io_wakeupMatched &&
                !dut.io_selectedValid,
            "request-only write published readiness or issue wakeup");
    tick(dut);

    idle(dut);
    dut.io_writeRequest = 1;
    dut.io_writeCommit = 1;
    dut.io_writeData = 0x12345678ULL;
    eval(dut);
    require(dut.io_writeFire && dut.io_wakeupMatched &&
                dut.io_wakeupMatchCount == 1 && !dut.io_selectedValid,
            "committed P write did not match exactly one resident source");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_sourceReady && dut.io_selectedValid && dut.io_pickFire &&
                ((dut.io_readyMask >> 40) & 1ULL),
            "committed P wakeup was not pick-visible on the next cycle");
    tick(dut);

    idle(dut);
    eval(dut);
    require(!dut.io_issueValid, "issue became valid before the RF read stage advanced");
    tick(dut);

    idle(dut);
    eval(dut);
    require(dut.io_issueValid && dut.io_issueFire &&
                dut.io_issueData == 0x12345678ULL,
            "woken issue row did not read committed physical GPR data");

    std::cout << "scalar-gpr-issue-wakeup-probe: PASS\n";
    return 0;
}
