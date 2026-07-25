#include <cstdlib>
#include <iostream>
#include <vector>

#include "VScalarContinuationBlockIdentityProbe.h"
#include "verilated.h"

namespace {

constexpr unsigned kBstart = 0x00002001;
constexpr unsigned kBstop = 0x00000001;

unsigned add(unsigned rd, unsigned rs1, unsigned rs2) {
    return 0x00000005u | (rd << 7) | (rs1 << 15) | (rs2 << 20);
}

void fail(const char *message) {
    std::cerr << "backend-scalar-continuation-block-identity-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const char *message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VScalarContinuationBlockIdentityProbe &dut) {
    dut.eval();
    require(!dut.io_commitContractError, "ROB commit contract error");
    require(!dut.io_unsupported, "decoded row became unsupported");
}

void tick(VScalarContinuationBlockIdentityProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

void idle(VScalarContinuationBlockIdentityProbe &dut) {
    dut.io_decodeValid = 0;
    dut.io_decodeInsn = 0;
    dut.io_decodePc = 0;
    dut.io_decodeLen = 4;
    dut.io_decodeLast = 0;
    dut.io_completeValid = 0;
    dut.io_completeRobValue = 0;
    dut.io_completeBlockBid = 0;
    dut.io_completePc = 0;
    dut.io_deallocReady = 1;
}

struct Row {
    unsigned rob = 0;
    unsigned long long bid = 0;
    unsigned long long pc = 0;
};

Row decode(VScalarContinuationBlockIdentityProbe &dut, unsigned long long pc, unsigned insn,
           bool architectural_last = false) {
    idle(dut);
    dut.io_decodeValid = 1;
    dut.io_decodeInsn = insn;
    dut.io_decodePc = pc;
    dut.io_decodeLen = 4;
    dut.io_decodeLast = architectural_last ? 1 : 0;
    eval(dut);
    require(dut.io_decodeReady, "decode row was not ready");
    require(dut.io_selectedValid, "decode row was not selected");
    Row row;
    row.rob = dut.io_selectedRobValue;
    row.bid = dut.io_selectedBlockBid;
    row.pc = pc;
    tick(dut);
    idle(dut);
    return row;
}

void complete(VScalarContinuationBlockIdentityProbe &dut, const Row &row) {
    idle(dut);
    dut.io_completeValid = 1;
    dut.io_completeRobValue = row.rob;
    dut.io_completeBlockBid = row.bid;
    dut.io_completePc = row.pc;
    tick(dut);
    idle(dut);
}

void drain(VScalarContinuationBlockIdentityProbe &dut, int cycles = 1) {
    idle(dut);
    for (int i = 0; i < cycles; ++i) {
        tick(dut);
    }
}

bool observes_release_for(VScalarContinuationBlockIdentityProbe &dut, unsigned long long bid) {
    return (dut.io_robDeallocBlockLastValid && dut.io_robDeallocBlockLastBlockBid == bid) ||
           (dut.io_blockRetireFire && dut.io_blockRetireBid == bid) ||
           (dut.io_gprCommitAccepted && dut.io_gprCommitBlockBid == bid);
}

void reset(VScalarContinuationBlockIdentityProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    drain(dut, 2);
}

void prove_architectural_last_still_releases(VScalarContinuationBlockIdentityProbe &dut) {
    reset(dut);
    Row start = decode(dut, 0x1000, kBstart);
    drain(dut, 4);
    Row body = decode(dut, 0x1004, add(3, 1, 2));
    drain(dut, 4);
    Row stop = decode(dut, 0x1008, kBstop);
    drain(dut, 4);

    complete(dut, start);
    complete(dut, body);
    complete(dut, stop);
    bool released = false;
    for (int cycle = 0; cycle < 24; ++cycle) {
        idle(dut);
        eval(dut);
        released = released || observes_release_for(dut, body.bid);
        tick(dut);
    }
    require(released, "true architectural block-last did not release the completed block");
}

void expose_resource_cut_identity_failure(VScalarContinuationBlockIdentityProbe &dut) {
    reset(dut);
    std::vector<Row> rows;
    rows.push_back(decode(dut, 0x2000, kBstart));
    drain(dut, 4);
    rows.push_back(decode(dut, 0x2004, add(31, 1, 2)));
    drain(dut, 4);
    rows.push_back(decode(dut, 0x2008, add(3, 1, 2)));
    drain(dut, 4);
    Row cut = decode(dut, 0x200c, add(4, 1, 2));
    rows.push_back(cut);
    drain(dut, 4);
    Row younger = decode(dut, 0x2010, add(5, 24, 1));
    rows.push_back(younger);
    if (younger.bid != cut.bid) {
        return;
    }

    for (const Row &row : rows) {
        if (row.pc != younger.pc) {
            complete(dut, row);
            for (int cycle = 0; cycle < 8; ++cycle) {
                idle(dut);
                eval(dut);
                if (observes_release_for(dut, cut.bid)) {
                    fail("same-full-BID continuation release observed before younger T/U row completed");
                }
                tick(dut);
            }
        }
    }
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VScalarContinuationBlockIdentityProbe dut;

    prove_architectural_last_still_releases(dut);
    expose_resource_cut_identity_failure(dut);

    std::cout << "backend-scalar-continuation-block-identity-probe: PASS\n";
    return 0;
}
