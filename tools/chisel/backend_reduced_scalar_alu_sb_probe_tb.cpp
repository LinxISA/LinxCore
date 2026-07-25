#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedScalarAluSbProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpSb = 386;
constexpr std::uint64_t kPcBase = 0x0000000000015800ULL;
constexpr int kWaitCycles = 10;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
    const char *name;
    std::uint64_t data;
    std::uint64_t base;
    std::uint64_t offset;
    std::uint8_t src_r_type;
    std::uint8_t src_d_arch;
    std::uint8_t src_d_phys;
    std::uint8_t src_l_arch;
    std::uint8_t src_l_phys;
    std::uint8_t src_r_arch;
    std::uint8_t src_r_phys;
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
    std::cerr << "backend-reduced-scalar-alu-sb-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

bool explicit_current_inversion() {
    const char *value = std::getenv("EXPECT_CURRENT_UNSUPPORTED");
    return value != nullptr && std::string(value) == "1";
}

void eval(VReducedScalarAluSbProbe &dut) {
    dut.eval();
}

void tick(VReducedScalarAluSbProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

std::uint64_t sext32(std::uint64_t value) {
    const std::uint32_t low = static_cast<std::uint32_t>(value & 0xffffffffULL);
    return static_cast<std::uint64_t>(static_cast<std::int64_t>(static_cast<std::int32_t>(low)));
}

std::uint64_t zext32(std::uint64_t value) {
    return value & 0xffffffffULL;
}

std::uint64_t modifier(std::uint64_t value, std::uint8_t src_r_type) {
    switch (src_r_type & 0x3U) {
    case 0:
        return sext32(value);
    case 1:
        return zext32(value);
    case 2:
        return 0ULL - value;
    default:
        return value;
    }
}

std::uint64_t expected_address(const Case &c) {
    return c.base + modifier(c.offset, c.src_r_type);
}

std::uint64_t expected_wdata(const Case &c) {
    return c.data & 0xffULL;
}

std::uint64_t insn_for_case(const Case &c) {
    return (static_cast<std::uint64_t>(c.src_d_arch & 0x1fU) << 27) |
           (static_cast<std::uint64_t>(c.src_r_type & 0x3U) << 25) |
           (static_cast<std::uint64_t>(c.src_r_arch & 0x1fU) << 20) |
           (static_cast<std::uint64_t>(c.src_l_arch & 0x1fU) << 15) |
           0x49ULL;
}

void idle(VReducedScalarAluSbProbe &dut) {
    dut.io_inValid = 0;
    dut.io_flushValid = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_srcDData = 0;
    dut.io_srcLData = 0;
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
    dut.io_srcDArchTag = 1;
    dut.io_srcDPhysTag = 20;
    dut.io_srcLArchTag = 2;
    dut.io_srcLPhysTag = 21;
    dut.io_srcRArchTag = 3;
    dut.io_srcRPhysTag = 22;
}

void reset(VReducedScalarAluSbProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

void require_legal_encoding(const Case &c) {
    require((c.src_r_type & ~0x3U) == 0, std::string(c.name) + ": SrcRType exceeded encoding width");
    require((c.src_d_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcD exceeded encoding width");
    require((c.src_l_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcL exceeded encoding width");
    require((c.src_r_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcR exceeded encoding width");
    const std::uint64_t insn = insn_for_case(c);
    require(((insn >> 25) & 0x3U) == c.src_r_type, std::string(c.name) + ": SrcRType encoding mismatch");
    require(((insn >> 20) & 0x1fU) == c.src_r_arch, std::string(c.name) + ": SrcR encoding mismatch");
    require(((insn >> 15) & 0x1fU) == c.src_l_arch, std::string(c.name) + ": SrcL encoding mismatch");
    require(((insn >> 27) & 0x1fU) == c.src_d_arch, std::string(c.name) + ": SrcD encoding mismatch");
    require((insn & 0x7fffU) == 0x49, std::string(c.name) + ": OP_SB low fixed pattern 0x49 encoding mismatch");
}

void drive_case(VReducedScalarAluSbProbe &dut, const Case &c, std::uint64_t pc) {
    require_legal_encoding(c);
    idle(dut);
    dut.io_inValid = 1;
    dut.io_pc = pc;
    dut.io_insnRaw = insn_for_case(c);
    dut.io_srcDData = c.data;
    dut.io_srcLData = c.base;
    dut.io_srcRData = c.offset;
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
    dut.io_srcLArchTag = c.src_l_arch;
    dut.io_srcLPhysTag = c.src_l_phys;
    dut.io_srcRArchTag = c.src_r_arch;
    dut.io_srcRPhysTag = c.src_r_phys;
    eval(dut);
}

bool release_identity_matches(VReducedScalarAluSbProbe &dut, const Case &c) {
    return dut.io_releaseBidValid && dut.io_releaseBidWrap == c.bid_wrap &&
           dut.io_releaseBidValue == c.bid && dut.io_releaseGidValid &&
           dut.io_releaseGidWrap == c.gid_wrap && dut.io_releaseGidValue == c.gid &&
           dut.io_releaseRidValid && dut.io_releaseRidWrap == c.rid_wrap &&
           dut.io_releaseRidValue == c.rid && dut.io_releaseStid == 0;
}

bool completion_identity_matches(VReducedScalarAluSbProbe &dut, const Case &c, std::uint64_t pc) {
    return dut.io_completeRobValue == c.rid && dut.io_completeLsId == c.lsid &&
           !dut.io_completeDstPhysValid && dut.io_completeDstData == 0 &&
           dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == c.src_d_phys &&
           dut.io_completeSrc1PhysValid && dut.io_completeSrc1PhysTag == c.src_l_phys &&
           dut.io_completeSrc2PhysValid && dut.io_completeSrc2PhysTag == c.src_r_phys &&
           dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
           dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
           dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
           dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
           dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
           dut.io_completeRowInsn == insn_for_case(c) && dut.io_completeRowLen == 4 &&
           dut.io_completeRowSrc0Valid && dut.io_completeRowSrc0Reg == c.src_d_arch &&
           dut.io_completeRowSrc0Data == c.data && dut.io_completeRowSrc1Valid &&
           dut.io_completeRowSrc1Reg == c.src_l_arch && dut.io_completeRowSrc1Data == c.base &&
           !dut.io_completeRowDstValid && !dut.io_completeRowWbValid &&
           dut.io_completeRowMemValid && dut.io_completeRowMemIsStore &&
           dut.io_completeRowMemAddr == expected_address(c) &&
           (dut.io_completeRowMemWdata & 0xffULL) == expected_wdata(c) &&
           (dut.io_completeRowMemWdata & ~0xffULL) == 0 &&
           dut.io_completeRowMemRdata == 0 && dut.io_completeRowMemSize == 1;
}

void require_no_forbidden_side_effects(VReducedScalarAluSbProbe &dut, const std::string &name) {
    require(!dut.io_loadLookupValid, name + ": unexpected load lookup");
    require(!dut.io_loadWaitHold, name + ": unexpected load wait hold");
    require(!dut.io_loadLiqEligible, name + ": unexpected LIQ eligibility");
    require(!dut.io_branchConditionValid, name + ": unexpected branch condition");
    require(!dut.io_redirectValid, name + ": unexpected redirect");
    require(!dut.io_liqReleaseValid, name + ": unexpected LIQ release");
}

void observe_future_green_pulses(VReducedScalarAluSbProbe &dut, const Case &c, std::uint64_t pc,
                                 unsigned &completion_count, unsigned &release_count) {
    require_no_forbidden_side_effects(dut, c.name);
    require(!dut.io_unsupported, std::string(c.name) + ": future-green path reported unsupported");
    if (dut.io_completeValid) {
        require(completion_count == 0, std::string(c.name) + ": duplicate completion before success");
        require(completion_identity_matches(dut, c, pc), std::string(c.name) + ": completion store-row identity mismatch");
        ++completion_count;
    }
    if (dut.io_releaseValid) {
        require(release_count == 0, std::string(c.name) + ": duplicate normal release before success");
        require(release_identity_matches(dut, c), std::string(c.name) + ": normal release identity mismatch");
        ++release_count;
    }
}

void require_no_late_pulse(VReducedScalarAluSbProbe &dut, const Case &c) {
    if (dut.io_completeValid || dut.io_releaseValid || dut.io_unsupported ||
        dut.io_branchConditionValid || dut.io_redirectValid || dut.io_liqReleaseValid ||
        dut.io_loadLookupValid || dut.io_completeRowValid) {
        fail(std::string(c.name) + ": duplicate or forbidden late pulse after success");
    }
}

void drain_for_duplicates(VReducedScalarAluSbProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        require_no_late_pulse(dut, c);
    }
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after duplicate drain");
}

void run_future_green_case(VReducedScalarAluSbProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 4ULL;
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
    fail(std::string(c.name) + ": OP_SB future-green completion/release did not complete exactly once");
}

bool prove_first_sb_current_red_or_future_green(VReducedScalarAluSbProbe &dut, const Case &c) {
    reset(dut);
    require(c.data == 0 && c.base == 0 && c.offset == 0 && c.src_r_type == 0,
            "first transaction must be architecture-generic zero OP_SB sext32 case");
    drive_case(dut, c, kPcBase);
    require(dut.io_inReady, "first-red OP_SB input was not ready");
    require(dut.io_accepted, "first-red OP_SB was not accepted");
    require(!dut.io_completeValid, "first-red OP_SB completed in accept cycle");
    require_no_forbidden_side_effects(dut, "first-red OP_SB accept cycle");
    unsigned release_count = 0;
    if (dut.io_releaseValid) {
        require(release_identity_matches(dut, c), "first-red OP_SB normal release identity mismatch");
        ++release_count;
    }
    tick(dut);
    idle(dut);

    bool observed_unsupported = false;
    unsigned completion_count = 0;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        require_no_forbidden_side_effects(dut, "first-red OP_SB");
        if (dut.io_releaseValid) {
            require(release_count == 0, "first-red OP_SB duplicate normal release");
            require(release_identity_matches(dut, c), "first-red OP_SB normal release identity mismatch");
            ++release_count;
        }
        if (dut.io_completeValid) {
            require(!dut.io_unsupported, "first OP_SB future-green completed while unsupported");
            require(completion_count == 0, "first OP_SB future-green duplicate completion");
            require(completion_identity_matches(dut, c, kPcBase), "first OP_SB future-green completion mismatch");
            ++completion_count;
        }
        if (completion_count == 1 && release_count == 1) {
            drain_for_duplicates(dut, c);
            return true;
        }
        observed_unsupported =
            observed_unsupported || (dut.io_unsupported && dut.io_unsupportedOpcode == kOpSb);
        tick(dut);
        idle(dut);
    }

    require(observed_unsupported, "first-red OP_SB was accepted but did not report unsupported opcode 386");
    require(completion_count == 0, "first-red OP_SB produced a completion before product support");
    require(release_count == 1, "first-red OP_SB did not produce exactly one same-identity normal release");
    require(!dut.io_busy, "first-red OP_SB busy did not clear after unsupported drain");
    if (!explicit_current_inversion()) {
        fail("first-red accepted OP_SB 386 reported unsupported/no completion/no memory row; rerun with EXPECT_CURRENT_UNSUPPORTED=1 only for current-red inversion");
    }
    std::cerr << "backend-reduced-scalar-alu-sb-probe: first-red accepted OP_SB 386 but observed unsupported/no completeValid/no memory row/exactly-one normal direct release/no load/branch/LIQ/writeback\n";
    return false;
}

void run_flush_case(VReducedScalarAluSbProbe &dut, const Case &c) {
    reset(dut);
    drive_case(dut, c, kPcBase + 0x100ULL);
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

void run_backpressure_case(VReducedScalarAluSbProbe &dut, const Case &c) {
    reset(dut);
    drive_case(dut, c, kPcBase + 0x104ULL);
    require(dut.io_accepted, std::string(c.name) + ": first backpressure operation was not accepted");
    tick(dut);
    Case other = c;
    other.name = "sb_backpressure_second_input";
    other.rid = static_cast<std::uint8_t>((c.rid + 1U) & 0x3fU);
    drive_case(dut, other, kPcBase + 0x108ULL);
    require(!dut.io_accepted, std::string(c.name) + ": accepted a second OP_SB while first ownership was live");
    require(!dut.io_inReady, std::string(c.name) + ": reported ready while first ownership was live");
    tick(dut);
    idle(dut);
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        if (dut.io_releaseValid) {
            require(release_identity_matches(dut, c), std::string(c.name) + ": release changed identity under backpressure");
        }
        if (dut.io_completeValid) {
            require(completion_identity_matches(dut, c, kPcBase + 0x104ULL),
                    std::string(c.name) + ": completion changed identity under backpressure");
            drain_for_duplicates(dut, c);
            return;
        }
        tick(dut);
        idle(dut);
    }
    if (!explicit_current_inversion()) {
        fail(std::string(c.name) + ": backpressure case did not reach future-green completion");
    }
}

std::vector<Case> cases() {
    return {
        {"sb_zero_addr_zero_data_sext32_first_red", 0x0000000000000000ULL, 0x0000000000000000ULL, 0x0000000000000000ULL, 0, 1, 20, 2, 21, 3, 22, 3, false, 4, true, 5, false, 0x15800001U, 0x2b00000000000001ULL},
        {"sb_nonzero_base_zero_offset_high_bit_raw", 0x0000000000000080ULL, 0x0000000000004100ULL, 0x0000000000000000ULL, 3, 4, 23, 5, 24, 6, 25, 6, true, 7, false, 8, true, 0x15800002U, 0x2b00000000000002ULL},
        {"sb_sign_extend_negative_offset_ff_data", 0xffff0000000000ffULL, 0x0000000000005000ULL, 0x00000000fffffff8ULL, 0, 7, 26, 8, 27, 9, 28, 9, false, 10, false, 11, true, 0x15800003U, 0x2b00000000000003ULL},
        {"sb_zero_extend_offset_upper_ignored", 0x1234567800000001ULL, 0x0000000000006000ULL, 0xffffffff00000024ULL, 1, 10, 29, 11, 30, 12, 31, 12, true, 13, true, 14, false, 0x15800004U, 0x2b00000000000004ULL},
        {"sb_negated_offset_wrap_data_80", 0x8000000000000080ULL, 0xfffffffffffffff0ULL, 0x0000000000000020ULL, 2, 13, 32, 14, 33, 15, 34, 15, false, 16, false, 17, true, 0x15800005U, 0x2b00000000000005ULL},
    };
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedScalarAluSbProbe dut;

    const std::vector<Case> all_cases = cases();
    const bool first_case_completed = prove_first_sb_current_red_or_future_green(dut, all_cases.front());
    if (!first_case_completed) {
        return 0;
    }
    for (unsigned idx = 1; idx < all_cases.size(); ++idx) {
        run_future_green_case(dut, all_cases[idx], idx);
    }
    run_flush_case(dut, all_cases[1]);
    run_backpressure_case(dut, all_cases[2]);

    std::cout << "backend-reduced-scalar-alu-sb-probe: PASS\n";
    return 0;
}
