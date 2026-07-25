#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedScalarAluHlSdiPrProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpHlSdiPo = 216;
constexpr std::uint32_t kOpHlSdiPr = 217;
constexpr std::uint32_t kOpHlSdiUpr = 220;
constexpr std::uint32_t kOpSdi = 390;
constexpr std::uint64_t kPcBase = 0x0000000000017000ULL;
constexpr int kWaitCycles = 10;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
    const char *name;
    std::uint32_t opcode;
    std::uint64_t raw;
    std::int32_t simm;
    std::uint64_t data;
    std::uint64_t base;
    std::uint8_t src_d_arch;
    std::uint8_t src_d_phys;
    std::uint8_t src_r_arch;
    std::uint8_t src_r_phys;
    bool dst_valid;
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
    std::cerr << "backend-reduced-scalar-alu-hl-sdi-pr-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VReducedScalarAluHlSdiPrProbe &dut) {
    dut.eval();
}

void tick(VReducedScalarAluHlSdiPrProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

std::uint64_t mask64(std::uint64_t value) {
    return value;
}

std::uint64_t scaled_simm17(std::int32_t simm) {
    return mask64(static_cast<std::uint64_t>(static_cast<std::int64_t>(simm)) << 3);
}

std::uint64_t expected_pr_address(const Case &c) {
    return mask64(c.base + scaled_simm17(c.simm));
}

std::uint64_t sdi_raw(std::uint8_t src_d, std::uint8_t src_r, std::int16_t simm) {
    const std::uint16_t imm = static_cast<std::uint16_t>(simm) & 0x0fffU;
    return (static_cast<std::uint64_t>((imm >> 5) & 0x7fU) << 25) |
           (static_cast<std::uint64_t>(src_d & 0x1fU) << 20) |
           (static_cast<std::uint64_t>(src_r & 0x1fU) << 15) |
           (static_cast<std::uint64_t>(imm & 0x1fU) << 7) |
           0x3059ULL;
}

std::uint64_t hl_sdi_raw(std::uint32_t opcode, std::uint8_t dst, std::uint8_t src_d,
                         std::uint8_t src_r, std::int32_t simm) {
    const std::uint32_t imm = static_cast<std::uint32_t>(simm) & 0x1ffffU;
    std::uint64_t fixed = 0;
    switch (opcode) {
    case kOpHlSdiPo:
        fixed = 0x3059003eULL;
        break;
    case kOpHlSdiPr:
        fixed = 0x3059002eULL;
        break;
    case kOpHlSdiUpr:
        fixed = 0x7059002eULL;
        break;
    default:
        fail("unsupported HL.SDI raw builder opcode");
    }
    return fixed |
           (static_cast<std::uint64_t>(imm & 0x1fU) << 6) |
           (static_cast<std::uint64_t>(dst & 0x1fU) << 11) |
           (static_cast<std::uint64_t>((imm >> 5) & 0x1fU) << 23) |
           (static_cast<std::uint64_t>(src_d & 0x1fU) << 35) |
           (static_cast<std::uint64_t>(src_r & 0x1fU) << 40) |
           (static_cast<std::uint64_t>((imm >> 10) & 0x7fU) << 41);
}

void idle(VReducedScalarAluHlSdiPrProbe &dut) {
    dut.io_inValid = 0;
    dut.io_flushValid = 0;
    dut.io_opcode = kOpHlSdiPr;
    dut.io_pc = 0;
    dut.io_insnLen = 6;
    dut.io_insnRaw = 0;
    dut.io_imm = 0;
    dut.io_srcDData = 0;
    dut.io_srcRData = 0;
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
    dut.io_srcDArchTag = 2;
    dut.io_srcDPhysTag = 20;
    dut.io_srcRArchTag = 3;
    dut.io_srcRPhysTag = 21;
    dut.io_dstValid = 1;
    dut.io_dstArchTag = 4;
    dut.io_dstPhysTag = 22;
}

void reset(VReducedScalarAluHlSdiPrProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

void require_legal_hl_encoding(const Case &c) {
    require(c.opcode == kOpHlSdiPr || c.opcode == kOpHlSdiPo || c.opcode == kOpHlSdiUpr,
            std::string(c.name) + ": not an HL.SDI adjacency opcode");
    require((c.src_d_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcD exceeded encoding width");
    require((c.src_r_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcR exceeded encoding width");
    require((c.dst_arch & ~0x1fU) == 0, std::string(c.name) + ": RegDst exceeded encoding width");
    require((c.raw & 0xffff0000707f003fULL) != 0, std::string(c.name) + ": empty HL.SDI raw instruction");
}

void drive_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, std::uint64_t pc) {
    if (c.opcode == kOpHlSdiPr || c.opcode == kOpHlSdiPo || c.opcode == kOpHlSdiUpr) {
        require_legal_hl_encoding(c);
    }
    idle(dut);
    dut.io_inValid = 1;
    dut.io_opcode = c.opcode;
    dut.io_pc = pc;
    dut.io_insnLen = c.opcode == kOpSdi ? 4 : 6;
    dut.io_insnRaw = c.raw;
    dut.io_imm = static_cast<std::uint64_t>(static_cast<std::int64_t>(c.simm));
    dut.io_srcDData = c.data;
    dut.io_srcRData = c.base;
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
    dut.io_srcDArchTag = c.src_d_arch;
    dut.io_srcDPhysTag = c.src_d_phys;
    dut.io_srcRArchTag = c.src_r_arch;
    dut.io_srcRPhysTag = c.src_r_phys;
    dut.io_dstValid = c.dst_valid ? 1 : 0;
    dut.io_dstArchTag = c.dst_arch;
    dut.io_dstPhysTag = c.dst_phys;
    eval(dut);
}

bool release_identity_matches(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
    return dut.io_releaseBidValid && dut.io_releaseBidWrap == c.bid_wrap &&
           dut.io_releaseBidValue == c.bid && dut.io_releaseGidValid &&
           dut.io_releaseGidWrap == c.gid_wrap && dut.io_releaseGidValue == c.gid &&
           dut.io_releaseRidValid && dut.io_releaseRidWrap == c.rid_wrap &&
           dut.io_releaseRidValue == c.rid && dut.io_releaseStid == 0;
}

bool completion_identity_matches(VReducedScalarAluHlSdiPrProbe &dut, const Case &c,
                                 std::uint64_t pc) {
    const std::uint64_t expected_addr = c.opcode == kOpSdi
                                            ? mask64(c.base + scaled_simm17(c.simm))
                                            : expected_pr_address(c);
    return dut.io_completeRobValue == c.rid && dut.io_completeLsId == c.lsid &&
           dut.io_completeDstPhysValid == c.dst_valid &&
           (!c.dst_valid || (dut.io_completeDstPhysTag == c.dst_phys &&
                             dut.io_completeDstData == expected_addr)) &&
           dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == c.src_d_phys &&
           dut.io_completeSrc1PhysValid && dut.io_completeSrc1PhysTag == c.src_r_phys &&
           dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
           dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
           dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
           dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
           dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
           dut.io_completeRowInsn == c.raw && dut.io_completeRowLen == (c.opcode == kOpSdi ? 4 : 6) &&
           dut.io_completeRowSrc0Valid && dut.io_completeRowSrc0Reg == c.src_d_arch &&
           dut.io_completeRowSrc0Data == c.data && dut.io_completeRowSrc1Valid &&
           dut.io_completeRowSrc1Reg == c.src_r_arch && dut.io_completeRowSrc1Data == c.base &&
           dut.io_completeRowDstValid == c.dst_valid && dut.io_completeRowWbValid == c.dst_valid &&
           (!c.dst_valid || (dut.io_completeRowDstReg == c.dst_arch &&
                             dut.io_completeRowDstData == expected_addr &&
                             dut.io_completeRowWbReg == c.dst_arch &&
                             dut.io_completeRowWbData == expected_addr)) &&
           dut.io_completeRowMemValid && dut.io_completeRowMemIsStore &&
           dut.io_completeRowMemAddr == expected_addr &&
           dut.io_completeRowMemWdata == c.data && dut.io_completeRowMemRdata == 0 &&
           dut.io_completeRowMemSize == 8;
}

void require_no_forbidden_side_effects(VReducedScalarAluHlSdiPrProbe &dut, const std::string &name) {
    require(!dut.io_loadLookupValid, name + ": unexpected load lookup");
    require(!dut.io_loadWaitHold, name + ": unexpected load wait hold");
    require(!dut.io_loadLiqEligible, name + ": unexpected LIQ eligibility");
    require(!dut.io_branchConditionValid, name + ": unexpected branch condition");
    require(!dut.io_redirectValid, name + ": unexpected redirect");
    require(!dut.io_liqReleaseValid, name + ": unexpected LIQ release");
}

void observe_future_green_pulses(VReducedScalarAluHlSdiPrProbe &dut, const Case &c,
                                 std::uint64_t pc, unsigned &completion_count,
                                 unsigned &release_count) {
    require_no_forbidden_side_effects(dut, c.name);
    require(!dut.io_unsupported, std::string(c.name) + ": future-green path reported unsupported");
    if (dut.io_completeValid) {
        require(completion_count == 0, std::string(c.name) + ": duplicate completion");
        require(completion_identity_matches(dut, c, pc), std::string(c.name) + ": completion identity or semantics mismatch");
        ++completion_count;
    }
    if (dut.io_releaseValid) {
        require(release_count == 0, std::string(c.name) + ": duplicate release");
        require(release_identity_matches(dut, c), std::string(c.name) + ": release identity mismatch");
        ++release_count;
    }
}

void require_no_late_pulse(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
    if (dut.io_completeValid || dut.io_releaseValid || dut.io_unsupported ||
        dut.io_branchConditionValid || dut.io_redirectValid || dut.io_liqReleaseValid ||
        dut.io_loadLookupValid || dut.io_completeRowValid) {
        fail(std::string(c.name) + ": duplicate or forbidden late pulse after success");
    }
}

void drain_for_duplicates(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        require_no_late_pulse(dut, c);
    }
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after duplicate drain");
}

void run_future_green_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 8ULL;
    drive_case(dut, c, pc);
    require(dut.io_inReady, std::string(c.name) + ": input was not ready");
    require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");
    require(!dut.io_completeValid, std::string(c.name) + ": completed in accept cycle");
    require_no_forbidden_side_effects(dut, c.name);
    unsigned completion_count = 0;
    unsigned release_count = 0;
    observe_future_green_pulses(dut, c, pc, completion_count, release_count);
    tick(dut);
    idle(dut);

    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        observe_future_green_pulses(dut, c, pc, completion_count, release_count);
        if (completion_count == 1 && release_count == 1) {
            drain_for_duplicates(dut, c);
            return;
        }
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": future-green completion/release did not complete exactly once");
}

bool prove_current_red_or_future_green(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
    reset(dut);
    require(c.opcode == kOpHlSdiPr, "first transaction must be OP_HL_SDI_PR");
    drive_case(dut, c, kPcBase);
    require(dut.io_inReady, "first-red OP_HL_SDI_PR input was not ready");
    require(dut.io_accepted, "first-red OP_HL_SDI_PR was not accepted");
    require(!dut.io_completeValid, "first-red OP_HL_SDI_PR completed in accept cycle");
    require_no_forbidden_side_effects(dut, "first-red OP_HL_SDI_PR accept cycle");
    tick(dut);
    idle(dut);

    bool observed_unsupported = false;
    unsigned completion_count = 0;
    unsigned release_count = 0;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        require_no_forbidden_side_effects(dut, "first-red OP_HL_SDI_PR");
        if (dut.io_releaseValid) {
            require(release_count == 0, "first-red OP_HL_SDI_PR duplicate release");
            require(release_identity_matches(dut, c), "first-red OP_HL_SDI_PR release identity mismatch");
            ++release_count;
        }
        if (dut.io_completeValid) {
            require(!dut.io_unsupported, "first OP_HL_SDI_PR completed while unsupported");
            require(completion_count == 0, "first OP_HL_SDI_PR duplicate completion");
            require(completion_identity_matches(dut, c, kPcBase), "first OP_HL_SDI_PR future-green completion mismatch");
            ++completion_count;
        }
        if (completion_count == 1 && release_count == 1) {
            drain_for_duplicates(dut, c);
            return true;
        }
        observed_unsupported =
            observed_unsupported || (dut.io_unsupported && dut.io_unsupportedOpcode == kOpHlSdiPr);
        tick(dut);
        idle(dut);
    }

    require(observed_unsupported, "accepted OP_HL_SDI_PR did not report delayed unsupported opcode 217");
    require(completion_count == 0, "unsupported OP_HL_SDI_PR produced a completion before product support");
    require(release_count == 1, "unsupported OP_HL_SDI_PR did not produce exactly one same-identity normal release");
    require(!dut.io_busy, "unsupported OP_HL_SDI_PR busy did not clear after delayed terminal");
    fail("accepted OP_HL_SDI_PR 217 reached delayed unsupported/no-completion/no-memory-row first-red");
    return false;
}

void run_flush_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
    reset(dut);
    drive_case(dut, c, kPcBase + 0x200ULL);
    require(dut.io_accepted, std::string(c.name) + ": flush case was not accepted");
    tick(dut);
    idle(dut);
    dut.io_flushValid = 1;
    eval(dut);
    require(!dut.io_completeValid && !dut.io_releaseValid && !dut.io_unsupported &&
            !dut.io_completeRowValid && !dut.io_loadLookupValid && !dut.io_liqReleaseValid,
            std::string(c.name) + ": killed operation produced completion/release/unsupported pulse");
    tick(dut);
    idle(dut);
    eval(dut);
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after flush");
}

void run_backpressure_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
    reset(dut);
    drive_case(dut, c, kPcBase + 0x208ULL);
    require(dut.io_accepted, std::string(c.name) + ": first backpressure operation was not accepted");
    tick(dut);
    Case other = c;
    other.name = "hl_sdi_pr_backpressure_second_input";
    other.rid = static_cast<std::uint8_t>((c.rid + 1U) & 0x3fU);
    other.bid = static_cast<std::uint8_t>((c.bid + 1U) & 0x3fU);
    drive_case(dut, other, kPcBase + 0x210ULL);
    require(!dut.io_accepted, std::string(c.name) + ": accepted a second input while first ownership was live");
    require(!dut.io_inReady, std::string(c.name) + ": reported ready while first ownership was live");
    tick(dut);
    idle(dut);

    unsigned completion_count = 0;
    unsigned release_count = 0;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        observe_future_green_pulses(dut, c, kPcBase + 0x208ULL, completion_count, release_count);
        if (completion_count == 1 && release_count == 1) {
            drain_for_duplicates(dut, c);
            return;
        }
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": backpressure case did not reach future-green completion");
}

void run_adjacent_unsupported_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + 0x300ULL + static_cast<std::uint64_t>(index) * 8ULL;
    drive_case(dut, c, pc);
    require(dut.io_accepted, std::string(c.name) + ": adjacent unsupported case was not accepted");
    tick(dut);
    idle(dut);
    bool observed_unsupported = false;
    unsigned completion_count = 0;
    unsigned release_count = 0;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        require_no_forbidden_side_effects(dut, c.name);
        if (dut.io_completeValid || dut.io_completeRowValid) {
            ++completion_count;
        }
        if (dut.io_releaseValid) {
            require(release_count == 0, std::string(c.name) + ": duplicate release");
            require(release_identity_matches(dut, c), std::string(c.name) + ": release identity mismatch");
            ++release_count;
        }
        observed_unsupported =
            observed_unsupported || (dut.io_unsupported && dut.io_unsupportedOpcode == c.opcode);
        tick(dut);
        idle(dut);
    }
    require(observed_unsupported, std::string(c.name) + ": adjacent opcode did not report unsupported");
    require(completion_count == 0, std::string(c.name) + ": adjacent unsupported opcode completed");
    require(release_count == 1, std::string(c.name) + ": adjacent unsupported opcode did not release exactly once");
}

std::vector<Case> hl_pr_cases() {
    return {
        {"hl_sdi_pr_real_dhrystone_raw_minus16_first_red", kOpHlSdiPr, 0xfc15bfd90feeULL, -2, 0x1122334455667788ULL, 0x0000004000001000ULL, 2, 20, 28, 21, true, 1, 22, 21, false, 31, true, 10, false, 0x21700001U, 0x8510000000000001ULL},
        {"hl_sdi_pr_zero_offset_regdst_diff", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 5, 6, 7, 0), 0, 0xa5a55a5ac3c33c3cULL, 0x0000000000008000ULL, 6, 23, 7, 24, true, 5, 25, 22, true, 32, false, 11, true, 0x21700002U, 0x8510000000000002ULL},
        {"hl_sdi_pr_positive_offset", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 8, 9, 10, 31), 31, 0x0102030405060708ULL, 0x0000000000009000ULL, 9, 26, 10, 27, true, 8, 28, 23, false, 33, false, 12, true, 0x21700003U, 0x8510000000000003ULL},
        {"hl_sdi_pr_negative_boundary", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 11, 12, 13, -65536), -65536, 0xfedcba9876543210ULL, 0x0000000000100000ULL, 12, 29, 13, 30, true, 11, 31, 24, true, 34, true, 13, false, 0x21700004U, 0x8510000000000004ULL},
        {"hl_sdi_pr_positive_boundary_wrap_regdst_equals_base", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 16, 15, 16, 65535), 65535, 0x8877665544332211ULL, 0xfffffffffffffff8ULL, 15, 32, 16, 33, true, 16, 34, 25, false, 35, true, 14, true, 0x21700005U, 0x8510000000000005ULL},
    };
}

std::vector<Case> adjacent_cases() {
    return {
        {"hl_sdi_po_adjacent_still_unsupported", kOpHlSdiPo, hl_sdi_raw(kOpHlSdiPo, 3, 4, 5, 7), 7, 0x1111111111111111ULL, 0x2000ULL, 4, 35, 5, 36, true, 3, 37, 26, false, 36, false, 15, false, 0x21700006U, 0x8510000000000006ULL},
        {"hl_sdi_upr_adjacent_still_unsupported", kOpHlSdiUpr, hl_sdi_raw(kOpHlSdiUpr, 17, 18, 19, -9), -9, 0x2222222222222222ULL, 0x3000ULL, 18, 38, 19, 39, true, 17, 40, 27, true, 37, true, 16, true, 0x21700007U, 0x8510000000000007ULL},
    };
}

Case sdi_case() {
    return {"sdi_existing_supported_scaled_store", kOpSdi, sdi_raw(3, 4, -4), -4, 0x7766554433221100ULL, 0x0000000000005000ULL, 3, 41, 4, 42, false, 0, 0, 28, false, 38, false, 17, false, 0x21700008U, 0x8510000000000008ULL};
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedScalarAluHlSdiPrProbe dut;

    const std::vector<Case> pr_cases = hl_pr_cases();
    const bool first_case_completed = prove_current_red_or_future_green(dut, pr_cases.front());
    if (!first_case_completed) {
        return 1;
    }
    for (unsigned idx = 1; idx < pr_cases.size(); ++idx) {
        run_future_green_case(dut, pr_cases[idx], idx);
    }
    run_flush_case(dut, pr_cases[1]);
    run_backpressure_case(dut, pr_cases[2]);

    unsigned adjacent_index = 0;
    for (const Case &c : adjacent_cases()) {
        run_adjacent_unsupported_case(dut, c, adjacent_index++);
    }
    run_future_green_case(dut, sdi_case(), 20);

    std::cout << "backend-reduced-scalar-alu-hl-sdi-pr-probe: PASS\n";
    return 0;
}
