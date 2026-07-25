#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedScalarAluSetcCompareProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpSetcEq = 328;
constexpr std::uint32_t kOpSetcGe = 330;
constexpr std::uint32_t kOpSetcGeu = 332;
constexpr std::uint32_t kOpSetcLt = 334;
constexpr std::uint32_t kOpSetcLtu = 336;
constexpr std::uint32_t kOpSetcNe = 338;
constexpr std::uint64_t kPcBase = 0x0000000000012000ULL;
constexpr std::uint8_t kSrc0Arch = 5;
constexpr std::uint8_t kSrc1Arch = 6;
constexpr std::uint8_t kSrc0Phys = 17;
constexpr std::uint8_t kSrc1Phys = 18;
constexpr int kWaitCycles = 8;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
    const char *name;
    std::uint32_t opcode;
    std::uint64_t lhs;
    std::uint64_t rhs;
    std::uint8_t src_r_type;
    bool expected;
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
    std::cerr << "backend-reduced-scalar-alu-setc-compare-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VReducedScalarAluSetcCompareProbe &dut) {
    dut.eval();
}

void tick(VReducedScalarAluSetcCompareProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

void idle(VReducedScalarAluSetcCompareProbe &dut) {
    dut.io_inValid = 0;
    dut.io_opcode = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_src0Data = 0;
    dut.io_src1Data = 0;
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
    dut.io_src0ArchTag = kSrc0Arch;
    dut.io_src0PhysTag = kSrc0Phys;
    dut.io_src1ArchTag = kSrc1Arch;
    dut.io_src1PhysTag = kSrc1Phys;
}

void reset(VReducedScalarAluSetcCompareProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

std::uint64_t sanitize_rhs(std::uint8_t src_r_type, std::uint64_t rhs) {
    switch (src_r_type & 0x3) {
    case 0:
        return rhs;
    case 1:
        return static_cast<std::uint64_t>(static_cast<std::int64_t>(static_cast<std::int32_t>(rhs & 0xffffffffULL)));
    case 2:
        return rhs & 0xffffffffULL;
    default:
        return rhs;
    }
}

bool sail_condition(std::uint32_t opcode, std::uint64_t lhs, std::uint64_t rhs, std::uint8_t src_r_type) {
    const std::uint64_t converted_rhs = sanitize_rhs(src_r_type, rhs);
    switch (opcode) {
    case kOpSetcEq:
        return lhs == converted_rhs;
    case kOpSetcNe:
        return lhs != converted_rhs;
    case kOpSetcLt:
        return static_cast<std::int64_t>(lhs) < static_cast<std::int64_t>(converted_rhs);
    case kOpSetcGe:
        return static_cast<std::int64_t>(lhs) >= static_cast<std::int64_t>(converted_rhs);
    case kOpSetcLtu:
        return lhs < converted_rhs;
    case kOpSetcGeu:
        return lhs >= converted_rhs;
    default:
        fail("unknown SETC opcode in Sail oracle");
        return false;
    }
}

std::uint64_t opcode_match(std::uint32_t opcode) {
    switch (opcode) {
    case kOpSetcEq:
        return 0x00000065ULL;
    case kOpSetcNe:
        return 0x00001065ULL;
    case kOpSetcLt:
        return 0x00004065ULL;
    case kOpSetcGe:
        return 0x00005065ULL;
    case kOpSetcLtu:
        return 0x00006065ULL;
    case kOpSetcGeu:
        return 0x00007065ULL;
    default:
        fail("unknown SETC opcode while constructing legal instruction");
        return 0;
    }
}

std::uint64_t insn_for_case(const Case &c) {
    return opcode_match(c.opcode) |
           (static_cast<std::uint64_t>(c.src_r_type & 0x3) << 25) |
           (static_cast<std::uint64_t>(kSrc1Arch & 0x1f) << 20) |
           (static_cast<std::uint64_t>(kSrc0Arch & 0x1f) << 15);
}

void drive_case(VReducedScalarAluSetcCompareProbe &dut, const Case &c, std::uint64_t pc) {
    idle(dut);
    dut.io_inValid = 1;
    dut.io_opcode = c.opcode;
    dut.io_pc = pc;
    dut.io_insnRaw = insn_for_case(c);
    dut.io_src0Data = c.lhs;
    dut.io_src1Data = c.rhs;
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
    dut.io_src0ArchTag = kSrc0Arch;
    dut.io_src0PhysTag = kSrc0Phys;
    dut.io_src1ArchTag = kSrc1Arch;
    dut.io_src1PhysTag = kSrc1Phys;
    eval(dut);
}

void require_no_success_pulses(VReducedScalarAluSetcCompareProbe &dut, const std::string &context) {
    require(!dut.io_completeValid, context + ": unexpected completeValid");
    require(!dut.io_branchConditionValid, context + ": unexpected branchConditionValid");
}

bool completion_identity_matches(VReducedScalarAluSetcCompareProbe &dut, const Case &c, std::uint64_t pc) {
    return dut.io_completeRobValue == c.rid && dut.io_completeLsId == c.lsid &&
           !dut.io_completeDstPhysValid && dut.io_completeDstData == 0 &&
           dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == kSrc0Phys &&
           dut.io_completeSrc1PhysValid && dut.io_completeSrc1PhysTag == kSrc1Phys &&
           dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
           dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
           dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
           dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
           dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
           dut.io_completeRowInsn == insn_for_case(c) &&
           dut.io_completeRowLen == 4 && dut.io_completeRowSrc0Valid &&
           dut.io_completeRowSrc0Reg == kSrc0Arch && dut.io_completeRowSrc0Data == c.lhs &&
           dut.io_completeRowSrc1Valid && dut.io_completeRowSrc1Reg == kSrc1Arch &&
           dut.io_completeRowSrc1Data == c.rhs && !dut.io_completeRowDstValid &&
           !dut.io_completeRowWbValid;
}

bool release_identity_matches(VReducedScalarAluSetcCompareProbe &dut, const Case &c) {
    return dut.io_releaseBidValid && dut.io_releaseBidWrap == c.bid_wrap &&
           dut.io_releaseBidValue == c.bid && dut.io_releaseGidValid &&
           dut.io_releaseGidWrap == c.gid_wrap && dut.io_releaseGidValue == c.gid &&
           dut.io_releaseRidValid && dut.io_releaseRidWrap == c.rid_wrap &&
           dut.io_releaseRidValue == c.rid && dut.io_releaseStid == 0;
}

void require_future_green_row(VReducedScalarAluSetcCompareProbe &dut, const Case &c, std::uint64_t pc) {
    require(completion_identity_matches(dut, c, pc), std::string(c.name) + ": completion identity/no-dst mismatch");
    require(dut.io_branchConditionValid, std::string(c.name) + ": missing branchConditionValid");
    require(static_cast<bool>(dut.io_branchConditionTaken) == c.expected,
            std::string(c.name) + ": branch condition disagreed with Sail oracle");
    require(dut.io_releaseValid, std::string(c.name) + ": missing releaseValid");
    require(release_identity_matches(dut, c), std::string(c.name) + ": release identity mismatch");
}

void drain_for_duplicates(VReducedScalarAluSetcCompareProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        if (dut.io_completeValid || dut.io_branchConditionValid || dut.io_releaseValid) {
            fail(std::string(c.name) + ": duplicate completion/condition/release pulse before reset");
        }
    }
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after drain");
}

void run_future_green_case(VReducedScalarAluSetcCompareProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 4ULL;
    require(sail_condition(c.opcode, c.lhs, c.rhs, c.src_r_type) == c.expected,
            std::string(c.name) + ": embedded Sail oracle fixture is inconsistent");
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
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": accepted transaction produced no completion");
}

bool prove_first_geu_current_red_or_future_green(VReducedScalarAluSetcCompareProbe &dut, const Case &c) {
    reset(dut);
    require(c.opcode == kOpSetcGeu, "first transaction must be OP_SETC_GEU");
    require(c.expected, "first OP_SETC_GEU fixture must be Sail-true");
    drive_case(dut, c, kPcBase);
    require(dut.io_inReady, "first-red OP_SETC_GEU input was not ready");
    require(dut.io_accepted, "first-red OP_SETC_GEU was not accepted");
    require_no_success_pulses(dut, "first-red OP_SETC_GEU accept cycle");
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
            fail("first OP_SETC_GEU produced branchConditionValid without completeValid");
        }
        observed_unsupported =
            observed_unsupported || (dut.io_unsupported && dut.io_unsupportedOpcode == kOpSetcGeu);
        tick(dut);
        idle(dut);
    }
    require(observed_unsupported, "first-red OP_SETC_GEU was accepted but did not report unsupported opcode 332");
    require(!dut.io_busy, "first-red OP_SETC_GEU busy did not clear after unsupported drain");
    std::cerr << "backend-reduced-scalar-alu-setc-compare-probe: first-red accepted OP_SETC_GEU 332 but observed unsupported/no completeValid/no branchConditionValid\n";
    std::exit(1);
    return false;
}

std::vector<Case> cases() {
    return {
        {"setc_geu_first_red_true", kOpSetcGeu, 0xffffffffffffffffULL, 0x0000000000000001ULL, 0, true, 3, false, 4, true, 5, false, 0x12000001U, 0xabc0000000000001ULL},
        {"setc_eq_true", kOpSetcEq, 0x1111222233334444ULL, 0x1111222233334444ULL, 0, true, 6, false, 7, true, 8, false, 0x12000002U, 0xabc0000000000002ULL},
        {"setc_eq_false", kOpSetcEq, 0x1111222233334444ULL, 0x1111222233334445ULL, 0, false, 9, true, 10, false, 11, true, 0x12000003U, 0xabc0000000000003ULL},
        {"setc_ne_true", kOpSetcNe, 0x0000000000000007ULL, 0x0000000000000008ULL, 0, true, 12, false, 13, false, 14, true, 0x12000004U, 0xabc0000000000004ULL},
        {"setc_ne_false", kOpSetcNe, 0x0000000000000008ULL, 0x0000000000000008ULL, 0, false, 15, true, 16, true, 17, false, 0x12000005U, 0xabc0000000000005ULL},
        {"setc_lt_true_signed_boundary", kOpSetcLt, 0x8000000000000000ULL, 0x0000000000000000ULL, 0, true, 18, false, 19, true, 20, false, 0x12000006U, 0xabc0000000000006ULL},
        {"setc_lt_false_equality", kOpSetcLt, 0xffffffffffffffffULL, 0xffffffffffffffffULL, 0, false, 21, true, 22, false, 23, true, 0x12000007U, 0xabc0000000000007ULL},
        {"setc_ge_true_equality", kOpSetcGe, 0xffffffffffffffffULL, 0xffffffffffffffffULL, 0, true, 24, false, 25, true, 26, false, 0x12000008U, 0xabc0000000000008ULL},
        {"setc_ge_false_signed_boundary", kOpSetcGe, 0x8000000000000000ULL, 0x0000000000000000ULL, 0, false, 27, true, 28, false, 29, true, 0x12000009U, 0xabc0000000000009ULL},
        {"setc_ltu_true_unsigned_boundary", kOpSetcLtu, 0x0000000000000000ULL, 0xffffffffffffffffULL, 0, true, 30, false, 31, true, 32, false, 0x1200000aU, 0xabc000000000000aULL},
        {"setc_ltu_false_equality", kOpSetcLtu, 0x00000000ffffffffULL, 0x00000000ffffffffULL, 0, false, 33, true, 34, false, 35, true, 0x1200000bU, 0xabc000000000000bULL},
        {"setc_geu_true_equality", kOpSetcGeu, 0x00000000ffffffffULL, 0x00000000ffffffffULL, 0, true, 36, false, 37, true, 38, false, 0x1200000cU, 0xabc000000000000cULL},
        {"setc_geu_false_unsigned_boundary", kOpSetcGeu, 0x0000000000000000ULL, 0xffffffffffffffffULL, 0, false, 39, true, 40, false, 41, true, 0x1200000dU, 0xabc000000000000dULL},
        {"srcrtype_raw_is_not_sign_extend", kOpSetcEq, 0x0000000080000001ULL, 0x0000000080000001ULL, 0, true, 42, false, 43, true, 44, false, 0x1200000eU, 0xabc000000000000eULL},
        {"srcrtype_sign_extend_low_word", kOpSetcEq, 0xffffffff80000001ULL, 0x0000000080000001ULL, 1, true, 45, true, 46, false, 47, true, 0x1200000fU, 0xabc000000000000fULL},
        {"srcrtype_zero_extend_low_word", kOpSetcEq, 0x0000000080000001ULL, 0xffffffff80000001ULL, 2, true, 48, false, 49, true, 50, false, 0x12000010U, 0xabc0000000000010ULL},
        {"srcrtype_three_sanitizes_to_raw", kOpSetcEq, 0x0000000000000005ULL, 0x0000000000000005ULL, 3, true, 51, true, 52, false, 53, true, 0x12000011U, 0xabc0000000000011ULL},
    };
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedScalarAluSetcCompareProbe dut;

    const std::vector<Case> all_cases = cases();
    const bool first_case_completed = prove_first_geu_current_red_or_future_green(dut, all_cases.front());
    require(first_case_completed, "first OP_SETC_GEU neither completed future-green nor exited at current first-red");
    for (unsigned idx = 1; idx < all_cases.size(); ++idx) {
        run_future_green_case(dut, all_cases[idx], idx);
    }

    std::cout << "backend-reduced-scalar-alu-setc-compare-probe: PASS\n";
    return 0;
}
