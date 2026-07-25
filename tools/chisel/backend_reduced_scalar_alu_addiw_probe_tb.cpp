#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedScalarAluAddiwProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpAddiw = 63;
constexpr std::uint64_t kPcBase = 0x0000000000013000ULL;
constexpr std::uint8_t kFirstSrcArch = 0;
constexpr std::uint8_t kDefaultSrcPhys = 17;
constexpr std::uint8_t kDefaultDstPhys = 41;
constexpr int kWaitCycles = 8;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
    const char *name;
    std::uint64_t src;
    std::uint16_t uimm12;
    std::uint8_t src_arch;
    std::uint8_t src_phys;
    std::uint8_t dst_arch;
    std::uint8_t dst_phys;
    std::uint8_t bid;
    bool bid_wrap;
    std::uint8_t gid;
    bool gid_wrap;
    std::uint8_t rid;
    bool rid_wrap;
    std::uint32_t lsid;
    std::uint64_t block_bid;
};

void fail(const std::string &message) {
    std::cerr << "backend-reduced-scalar-alu-addiw-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VReducedScalarAluAddiwProbe &dut) {
    dut.eval();
}

void tick(VReducedScalarAluAddiwProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

std::uint64_t sail_addiw(std::uint64_t src, std::uint16_t uimm12) {
    const std::uint32_t low = static_cast<std::uint32_t>(src & 0xffffffffULL);
    const std::uint32_t imm = static_cast<std::uint32_t>(uimm12 & 0x0fffU);
    const std::uint32_t result = low + imm;
    return static_cast<std::uint64_t>(static_cast<std::int64_t>(static_cast<std::int32_t>(result)));
}

std::uint64_t insn_for_case(const Case &c) {
    return (static_cast<std::uint64_t>(c.uimm12 & 0x0fffU) << 20) |
           (static_cast<std::uint64_t>(c.src_arch & 0x1fU) << 15) |
           (static_cast<std::uint64_t>(c.dst_arch & 0x1fU) << 7) |
           0x35ULL;
}

void idle(VReducedScalarAluAddiwProbe &dut) {
    dut.io_inValid = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_uimm12 = 0;
    dut.io_src0Data = 0;
    dut.io_bidValid = 1;
    dut.io_bidWrap = 0;
    dut.io_bidValue = 0;
    dut.io_gidValid = 1;
    dut.io_gidWrap = 0;
    dut.io_gidValue = 0;
    dut.io_ridValid = 1;
    dut.io_ridWrap = 0;
    dut.io_ridValue = 0;
    dut.io_lsid = 0;
    dut.io_blockBid = 0;
    dut.io_src0ArchTag = kFirstSrcArch;
    dut.io_src0PhysTag = kDefaultSrcPhys;
    dut.io_dstArchTag = 1;
    dut.io_dstPhysTag = kDefaultDstPhys;
}

void reset(VReducedScalarAluAddiwProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

void require_legal_encoding(const Case &c) {
    require((c.uimm12 & ~0x0fffU) == 0, std::string(c.name) + ": uimm12 exceeded 12 bits");
    require((c.src_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcL exceeded encoding width");
    require((c.dst_arch & ~0x1fU) == 0, std::string(c.name) + ": RegDst exceeded encoding width");
    const std::uint64_t insn = insn_for_case(c);
    require(((insn >> 20) & 0x0fffU) == c.uimm12, std::string(c.name) + ": uimm12 encoding mismatch");
    require(((insn >> 15) & 0x1fU) == c.src_arch, std::string(c.name) + ": SrcL encoding mismatch");
    require(((insn >> 12) & 0x7U) == 0, std::string(c.name) + ": funct3 encoding mismatch");
    require(((insn >> 7) & 0x1fU) == c.dst_arch, std::string(c.name) + ": RegDst encoding mismatch");
    require(((insn >> 4) & 0x7U) == 0x3, std::string(c.name) + ": ADDIW low funct encoding mismatch");
    require(((insn >> 1) & 0x7U) == 0x2, std::string(c.name) + ": ADDIW opcode class encoding mismatch");
    require((insn & 0x1U) == 1, std::string(c.name) + ": instruction low valid bit mismatch");
}

void drive_case(VReducedScalarAluAddiwProbe &dut, const Case &c, std::uint64_t pc) {
    require_legal_encoding(c);
    idle(dut);
    dut.io_inValid = 1;
    dut.io_pc = pc;
    dut.io_insnRaw = insn_for_case(c);
    dut.io_uimm12 = c.uimm12;
    dut.io_src0Data = c.src;
    dut.io_bidValid = 1;
    dut.io_bidWrap = c.bid_wrap ? 1 : 0;
    dut.io_bidValue = c.bid;
    dut.io_gidValid = 1;
    dut.io_gidWrap = c.gid_wrap ? 1 : 0;
    dut.io_gidValue = c.gid;
    dut.io_ridValid = 1;
    dut.io_ridWrap = c.rid_wrap ? 1 : 0;
    dut.io_ridValue = c.rid;
    dut.io_lsid = c.lsid;
    dut.io_blockBid = c.block_bid;
    dut.io_src0ArchTag = c.src_arch;
    dut.io_src0PhysTag = c.src_phys;
    dut.io_dstArchTag = c.dst_arch;
    dut.io_dstPhysTag = c.dst_phys;
    eval(dut);
}

bool completion_identity_matches(VReducedScalarAluAddiwProbe &dut, const Case &c, std::uint64_t pc) {
    const std::uint64_t expected = sail_addiw(c.src, c.uimm12);
    return dut.io_completeRobValue == c.rid && dut.io_completeLsId == c.lsid &&
           dut.io_completeDstPhysValid && dut.io_completeDstPhysTag == c.dst_phys &&
           dut.io_completeDstData == expected &&
           dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == c.src_phys &&
           dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
           dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
           dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
           dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
           dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
           dut.io_completeRowInsn == insn_for_case(c) &&
           dut.io_completeRowLen == 4 && dut.io_completeRowSrc0Valid &&
           dut.io_completeRowSrc0Reg == c.src_arch && dut.io_completeRowSrc0Data == c.src &&
           !dut.io_completeRowSrc1Valid &&
           dut.io_completeRowDstValid && dut.io_completeRowDstReg == c.dst_arch &&
           dut.io_completeRowDstData == expected &&
           dut.io_completeRowWbValid && dut.io_completeRowWbReg == c.dst_arch &&
           dut.io_completeRowWbData == expected;
}

bool release_identity_matches(VReducedScalarAluAddiwProbe &dut, const Case &c) {
    return dut.io_releaseBidValid && dut.io_releaseBidWrap == c.bid_wrap &&
           dut.io_releaseBidValue == c.bid && dut.io_releaseGidValid &&
           dut.io_releaseGidWrap == c.gid_wrap && dut.io_releaseGidValue == c.gid &&
           dut.io_releaseRidValid && dut.io_releaseRidWrap == c.rid_wrap &&
           dut.io_releaseRidValue == c.rid && dut.io_releaseStid == 0;
}

void require_future_green_row(VReducedScalarAluAddiwProbe &dut, const Case &c, std::uint64_t pc) {
    require(completion_identity_matches(dut, c, pc), std::string(c.name) + ": completion/writeback identity mismatch");
    require(!dut.io_branchConditionValid, std::string(c.name) + ": unexpected branchConditionValid");
    require(dut.io_releaseValid, std::string(c.name) + ": missing releaseValid");
    require(release_identity_matches(dut, c), std::string(c.name) + ": release identity mismatch");
}

void drain_for_duplicates(VReducedScalarAluAddiwProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        if (dut.io_completeValid || dut.io_branchConditionValid || dut.io_releaseValid) {
            fail(std::string(c.name) + ": duplicate completion/branch/release pulse before reset");
        }
    }
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after drain");
}

void run_future_green_case(VReducedScalarAluAddiwProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 4ULL;
    drive_case(dut, c, pc);
    require(dut.io_inReady, std::string(c.name) + ": input was not ready");
    require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");
    tick(dut);
    idle(dut);
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        if (dut.io_completeValid) {
            require_future_green_row(dut, c, pc);
            drain_for_duplicates(dut, c);
            return;
        }
        if (dut.io_branchConditionValid) {
            fail(std::string(c.name) + ": branchConditionValid without completeValid");
        }
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": accepted transaction produced no completion");
}

bool prove_first_addiw_current_red_or_future_green(VReducedScalarAluAddiwProbe &dut, const Case &c) {
    reset(dut);
    require(c.src_arch == 0 && c.src == 0 && c.uimm12 == 40,
            "first transaction must be natural OP_ADDIW SrcL zero plus uimm12 40");
    drive_case(dut, c, kPcBase);
    require(dut.io_inReady, "first-red OP_ADDIW input was not ready");
    require(dut.io_accepted, "first-red OP_ADDIW was not accepted");
    require(!dut.io_completeValid, "first-red OP_ADDIW completed in accept cycle");
    require(!dut.io_branchConditionValid, "first-red OP_ADDIW produced branch condition in accept cycle");
    tick(dut);
    idle(dut);
    bool observed_unsupported = false;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        if (dut.io_completeValid) {
            require_future_green_row(dut, c, kPcBase);
            drain_for_duplicates(dut, c);
            return true;
        }
        if (dut.io_branchConditionValid) {
            fail("first OP_ADDIW produced branchConditionValid without completeValid");
        }
        observed_unsupported =
            observed_unsupported || (dut.io_unsupported && dut.io_unsupportedOpcode == kOpAddiw);
        tick(dut);
        idle(dut);
    }
    require(observed_unsupported, "first-red OP_ADDIW was accepted but did not report unsupported opcode 63");
    require(!dut.io_busy, "first-red OP_ADDIW busy did not clear after unsupported drain");
    std::cerr << "backend-reduced-scalar-alu-addiw-probe: first-red accepted OP_ADDIW 63 SrcL zero uimm12 40 but observed unsupported/no completeValid/no branchConditionValid\n";
    std::exit(1);
    return false;
}

std::vector<Case> cases() {
    return {
        {"addiw_zero_plus_40_first_red", 0x0000000000000000ULL, 0x028, 0, 17, 1, 41, 3, false, 4, true, 5, false, 0x13000001U, 0xadd1000000000001ULL},
        {"addiw_sign_transition", 0x000000007fffffffULL, 0x001, 2, 18, 3, 42, 6, false, 7, false, 8, true, 0x13000002U, 0xadd1000000000002ULL},
        {"addiw_wrap_to_zero", 0x00000000ffffffffULL, 0x001, 4, 19, 5, 43, 9, true, 10, false, 11, true, 0x13000003U, 0xadd1000000000003ULL},
        {"addiw_ignores_high_source_bits", 0x1234567800000027ULL, 0x001, 6, 20, 7, 44, 12, false, 13, true, 14, false, 0x13000004U, 0xadd1000000000004ULL},
        {"addiw_max_uimm12", 0x000000007ffff001ULL, 0xfff, 8, 21, 9, 45, 15, true, 16, true, 17, false, 0x13000005U, 0xadd1000000000005ULL},
    };
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedScalarAluAddiwProbe dut;

    const std::vector<Case> all_cases = cases();
    const bool first_case_completed = prove_first_addiw_current_red_or_future_green(dut, all_cases.front());
    require(first_case_completed, "first OP_ADDIW neither completed future-green nor exited at current first-red");
    for (unsigned idx = 1; idx < all_cases.size(); ++idx) {
        run_future_green_case(dut, all_cases[idx], idx);
    }

    std::cout << "backend-reduced-scalar-alu-addiw-probe: PASS\n";
    return 0;
}
