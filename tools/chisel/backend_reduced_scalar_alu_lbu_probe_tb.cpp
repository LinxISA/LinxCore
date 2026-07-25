#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedScalarAluLbuProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpLbu = 346;
constexpr std::uint64_t kPcBase = 0x0000000000014800ULL;
constexpr int kWaitCycles = 10;
constexpr int kDuplicateDrainCycles = 8;

enum class Ownership {
    Direct,
    Liq,
};

struct Case {
    const char *name;
    std::uint64_t base;
    std::uint64_t offset;
    std::uint8_t src_r_type;
    std::uint8_t shamt;
    std::uint8_t load_byte;
    Ownership ownership;
    bool wait_block_first;
    bool liq_hold_first;
    std::uint8_t src_l_arch;
    std::uint8_t src_l_phys;
    std::uint8_t src_r_arch;
    std::uint8_t src_r_phys;
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
    std::cerr << "backend-reduced-scalar-alu-lbu-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VReducedScalarAluLbuProbe &dut) {
    dut.eval();
}

void tick(VReducedScalarAluLbuProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

std::uint64_t mask64(std::uint64_t value) {
    return value;
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
        return mask64(0ULL - value);
    default:
        return value;
    }
}

std::uint64_t expected_address(const Case &c) {
    return mask64(c.base + mask64(modifier(c.offset, c.src_r_type) << c.shamt));
}

std::uint64_t expected_result(const Case &c) {
    return static_cast<std::uint64_t>(c.load_byte);
}

std::uint64_t insn_for_case(const Case &c) {
    return (static_cast<std::uint64_t>(c.shamt & 0x1fU) << 27) |
           (static_cast<std::uint64_t>(c.src_r_type & 0x3U) << 25) |
           (static_cast<std::uint64_t>(c.src_r_arch & 0x1fU) << 20) |
           (static_cast<std::uint64_t>(c.src_l_arch & 0x1fU) << 15) |
           (4ULL << 12) |
           (static_cast<std::uint64_t>(c.dst_arch & 0x1fU) << 7) |
           0x4009ULL;
}

void idle(VReducedScalarAluLbuProbe &dut) {
    dut.io_inValid = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_srcLData = 0;
    dut.io_srcRData = 0;
    dut.io_loadLookupData = 0;
    dut.io_loadLookupWaitBlocked = 0;
    dut.io_loadLiqEnable = 0;
    dut.io_loadLiqAccepted = 0;
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
    dut.io_srcLArchTag = 0;
    dut.io_srcLPhysTag = 16;
    dut.io_srcRArchTag = 1;
    dut.io_srcRPhysTag = 17;
    dut.io_dstArchTag = 2;
    dut.io_dstPhysTag = 40;
}

void reset(VReducedScalarAluLbuProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

void require_legal_encoding(const Case &c) {
    require((c.shamt & ~0x1fU) == 0, std::string(c.name) + ": shamt exceeded encoding width");
    require((c.src_r_type & ~0x3U) == 0, std::string(c.name) + ": SrcRType exceeded encoding width");
    require((c.src_l_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcL exceeded encoding width");
    require((c.src_r_arch & ~0x1fU) == 0, std::string(c.name) + ": SrcR exceeded encoding width");
    require((c.dst_arch & ~0x1fU) == 0, std::string(c.name) + ": RegDst exceeded encoding width");
    const std::uint64_t insn = insn_for_case(c);
    require(((insn >> 27) & 0x1fU) == c.shamt, std::string(c.name) + ": shamt encoding mismatch");
    require(((insn >> 25) & 0x3U) == c.src_r_type, std::string(c.name) + ": SrcRType encoding mismatch");
    require(((insn >> 20) & 0x1fU) == c.src_r_arch, std::string(c.name) + ": SrcR encoding mismatch");
    require(((insn >> 15) & 0x1fU) == c.src_l_arch, std::string(c.name) + ": SrcL encoding mismatch");
    require(((insn >> 12) & 0x7U) == 4, std::string(c.name) + ": LBU funct3 encoding mismatch");
    require(((insn >> 7) & 0x1fU) == c.dst_arch, std::string(c.name) + ": RegDst encoding mismatch");
    require((insn & 0x7fU) == 0x09, std::string(c.name) + ": LBU low value 0x4009 encoding mismatch");
}

void drive_case(VReducedScalarAluLbuProbe &dut, const Case &c, std::uint64_t pc) {
    require_legal_encoding(c);
    idle(dut);
    dut.io_inValid = 1;
    dut.io_pc = pc;
    dut.io_insnRaw = insn_for_case(c);
    dut.io_srcLData = c.base;
    dut.io_srcRData = c.offset;
    dut.io_loadLookupData = c.load_byte;
    dut.io_loadLiqEnable = c.ownership == Ownership::Liq ? 1 : 0;
    dut.io_loadLiqAccepted = (c.ownership == Ownership::Liq && !c.liq_hold_first) ? 1 : 0;
    dut.io_loadLookupWaitBlocked = (c.ownership == Ownership::Direct && c.wait_block_first) ? 1 : 0;
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
    dut.io_srcLArchTag = c.src_l_arch;
    dut.io_srcLPhysTag = c.src_l_phys;
    dut.io_srcRArchTag = c.src_r_arch;
    dut.io_srcRPhysTag = c.src_r_phys;
    dut.io_dstArchTag = c.dst_arch;
    dut.io_dstPhysTag = c.dst_phys;
    eval(dut);
}

void continue_case_inputs(VReducedScalarAluLbuProbe &dut, const Case &c, bool direct_wait, bool liq_accepted) {
    dut.io_loadLookupData = c.load_byte;
    dut.io_loadLookupWaitBlocked = direct_wait ? 1 : 0;
    dut.io_loadLiqEnable = c.ownership == Ownership::Liq ? 1 : 0;
    dut.io_loadLiqAccepted = liq_accepted ? 1 : 0;
}

bool lookup_identity_matches(VReducedScalarAluLbuProbe &dut, const Case &c, std::uint64_t pc) {
    return dut.io_loadLookupValid && dut.io_loadLookupAddr == expected_address(c) &&
           dut.io_loadLookupSize == 1 && !dut.io_loadLookupReturnSignExtend &&
           dut.io_loadLiqEligible && dut.io_loadLookupPc == pc &&
           dut.io_loadLookupBidValid && dut.io_loadLookupBidWrap == c.bid_wrap &&
           dut.io_loadLookupBidValue == c.bid && dut.io_loadLookupGidValid &&
           dut.io_loadLookupGidWrap == c.gid_wrap && dut.io_loadLookupGidValue == c.gid &&
           dut.io_loadLookupRidValid && dut.io_loadLookupRidWrap == c.rid_wrap &&
           dut.io_loadLookupRidValue == c.rid && dut.io_loadLookupLsId == c.lsid &&
           dut.io_loadLookupDstValid && dut.io_loadLookupDstArchTag == c.dst_arch &&
           dut.io_loadLookupDstPhysTag == c.dst_phys && dut.io_loadLookupSourceTraceValid &&
           dut.io_loadLookupSource0Valid && dut.io_loadLookupSource0Reg == c.src_l_arch &&
           dut.io_loadLookupSource0Data == c.base && dut.io_loadLookupSource1Valid &&
           dut.io_loadLookupSource1Reg == c.src_r_arch && dut.io_loadLookupSource1Data == c.offset;
}

bool completion_identity_matches(VReducedScalarAluLbuProbe &dut, const Case &c, std::uint64_t pc) {
    const std::uint64_t expected = expected_result(c);
    return dut.io_completeRobValue == c.rid && dut.io_completeLsId == c.lsid &&
           dut.io_completeDstPhysValid && dut.io_completeDstPhysTag == c.dst_phys &&
           dut.io_completeDstData == expected &&
           dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == c.src_l_phys &&
           dut.io_completeSrc1PhysValid && dut.io_completeSrc1PhysTag == c.src_r_phys &&
           dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
           dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
           dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
           dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
           dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
           dut.io_completeRowInsn == insn_for_case(c) && dut.io_completeRowLen == 4 &&
           dut.io_completeRowSrc0Valid && dut.io_completeRowSrc0Reg == c.src_l_arch &&
           dut.io_completeRowSrc0Data == c.base && dut.io_completeRowSrc1Valid &&
           dut.io_completeRowSrc1Reg == c.src_r_arch && dut.io_completeRowSrc1Data == c.offset &&
           dut.io_completeRowDstValid && dut.io_completeRowDstReg == c.dst_arch &&
           dut.io_completeRowDstData == expected && dut.io_completeRowWbValid &&
           dut.io_completeRowWbReg == c.dst_arch && dut.io_completeRowWbData == expected &&
           dut.io_completeRowMemValid && !dut.io_completeRowMemIsStore &&
           dut.io_completeRowMemAddr == expected_address(c) &&
           dut.io_completeRowMemRdata == expected && dut.io_completeRowMemSize == 1;
}

bool release_identity_matches(VReducedScalarAluLbuProbe &dut, const Case &c) {
    return dut.io_releaseBidValid && dut.io_releaseBidWrap == c.bid_wrap &&
           dut.io_releaseBidValue == c.bid && dut.io_releaseGidValid &&
           dut.io_releaseGidWrap == c.gid_wrap && dut.io_releaseGidValue == c.gid &&
           dut.io_releaseRidValid && dut.io_releaseRidWrap == c.rid_wrap &&
           dut.io_releaseRidValue == c.rid && dut.io_releaseStid == 0;
}

bool liq_release_identity_matches(VReducedScalarAluLbuProbe &dut, const Case &c) {
    return dut.io_liqReleaseBidValid && dut.io_liqReleaseBidWrap == c.bid_wrap &&
           dut.io_liqReleaseBidValue == c.bid && dut.io_liqReleaseRidValid &&
           dut.io_liqReleaseRidWrap == c.rid_wrap && dut.io_liqReleaseRidValue == c.rid &&
           dut.io_liqReleaseStid == 0;
}

void require_no_sideband_duplicate(VReducedScalarAluLbuProbe &dut, const Case &c) {
    if (dut.io_completeValid || dut.io_branchConditionValid || dut.io_releaseValid || dut.io_liqReleaseValid) {
        fail(std::string(c.name) + ": duplicate completion/branch/direct release/LIQ release pulse");
    }
}

void observe_direct_pulses(VReducedScalarAluLbuProbe &dut, const Case &c, std::uint64_t pc,
                           unsigned &completion_count, unsigned &release_count) {
    if (dut.io_completeValid) {
        require(completion_count == 0, std::string(c.name) + ": duplicate direct completion before success");
        require(completion_identity_matches(dut, c, pc), std::string(c.name) + ": completion/writeback identity mismatch");
        require(!dut.io_branchConditionValid, std::string(c.name) + ": unexpected branchConditionValid");
        ++completion_count;
    }
    if (dut.io_releaseValid) {
        require(release_count == 0, std::string(c.name) + ": duplicate direct release before success");
        require(release_identity_matches(dut, c), std::string(c.name) + ": direct release identity mismatch");
        ++release_count;
    }
    require(!dut.io_liqReleaseValid, std::string(c.name) + ": direct case produced LIQ release");
}

void observe_liq_pulses(VReducedScalarAluLbuProbe &dut, const Case &c, unsigned &liq_release_count) {
    if (dut.io_liqReleaseValid) {
        require(liq_release_count == 0, std::string(c.name) + ": duplicate LIQ release before success");
        require(liq_release_identity_matches(dut, c), std::string(c.name) + ": LIQ release identity mismatch");
        ++liq_release_count;
    }
    require(!dut.io_completeValid, std::string(c.name) + ": LIQ case produced direct completion");
    require(!dut.io_releaseValid, std::string(c.name) + ": LIQ case produced direct release");
    require(!dut.io_branchConditionValid, std::string(c.name) + ": unexpected branchConditionValid");
}

void drain_for_duplicates(VReducedScalarAluLbuProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        require_no_sideband_duplicate(dut, c);
    }
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after duplicate drain");
}

void run_direct_future_green_case(VReducedScalarAluLbuProbe &dut, const Case &c, std::uint64_t pc) {
    drive_case(dut, c, pc);
    require(dut.io_inReady, std::string(c.name) + ": input was not ready");
    require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");
    require(!dut.io_completeValid, std::string(c.name) + ": completed in accept cycle");
    unsigned completion_count = 0;
    unsigned release_count = 0;
    observe_direct_pulses(dut, c, pc, completion_count, release_count);
    tick(dut);
    idle(dut);

    if (c.wait_block_first) {
        continue_case_inputs(dut, c, true, false);
        eval(dut);
        require(dut.io_loadWaitHold, std::string(c.name) + ": missing direct load wait hold");
        require(lookup_identity_matches(dut, c, pc), std::string(c.name) + ": lookup identity changed under wait hold");
        require(!dut.io_completeValid && !dut.io_releaseValid && !dut.io_liqReleaseValid,
                std::string(c.name) + ": early completion or release during direct wait hold");
        observe_direct_pulses(dut, c, pc, completion_count, release_count);
        tick(dut);
        idle(dut);
    }

    bool saw_lookup = false;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        continue_case_inputs(dut, c, false, false);
        eval(dut);
        if (dut.io_loadLookupValid) {
            saw_lookup = true;
            require(lookup_identity_matches(dut, c, pc), std::string(c.name) + ": lookup identity mismatch");
        }
        observe_direct_pulses(dut, c, pc, completion_count, release_count);
        if (saw_lookup && completion_count == 1 && release_count == 1) {
            drain_for_duplicates(dut, c);
            return;
        }
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": direct future-green lookup/completion/release did not complete");
}

void run_liq_future_green_case(VReducedScalarAluLbuProbe &dut, const Case &c, std::uint64_t pc) {
    drive_case(dut, c, pc);
    require(dut.io_inReady, std::string(c.name) + ": input was not ready");
    require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");
    unsigned liq_release_count = 0;
    observe_liq_pulses(dut, c, liq_release_count);
    tick(dut);
    idle(dut);

    if (c.liq_hold_first) {
        continue_case_inputs(dut, c, false, false);
        eval(dut);
        require(dut.io_loadWaitHold, std::string(c.name) + ": missing LIQ acceptance hold");
        require(lookup_identity_matches(dut, c, pc), std::string(c.name) + ": lookup identity changed during LIQ hold");
        require(!dut.io_completeValid && !dut.io_releaseValid && !dut.io_liqReleaseValid,
                std::string(c.name) + ": early completion or release before LIQ accepts");
        observe_liq_pulses(dut, c, liq_release_count);
        tick(dut);
        idle(dut);
    }

    bool saw_lookup = false;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        continue_case_inputs(dut, c, false, true);
        eval(dut);
        if (dut.io_loadLookupValid) {
            saw_lookup = true;
            require(lookup_identity_matches(dut, c, pc), std::string(c.name) + ": lookup identity mismatch");
        }
        observe_liq_pulses(dut, c, liq_release_count);
        if (saw_lookup && liq_release_count == 1) {
            drain_for_duplicates(dut, c);
            return;
        }
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": LIQ future-green lookup/release did not complete");
}

void run_future_green_case(VReducedScalarAluLbuProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 4ULL;
    if (c.ownership == Ownership::Direct) {
        run_direct_future_green_case(dut, c, pc);
    } else {
        run_liq_future_green_case(dut, c, pc);
    }
}

bool prove_first_lbu_current_red_or_future_green(VReducedScalarAluLbuProbe &dut, const Case &c) {
    reset(dut);
    require(c.base == 0 && c.offset == 0 && c.load_byte == 0 && c.src_r_type == 3 && c.shamt == 0,
            "first transaction must be architecture-generic OP_LBU zero-base zero-offset zero-byte");
    drive_case(dut, c, kPcBase);
    require(dut.io_inReady, "first-red OP_LBU input was not ready");
    require(dut.io_accepted, "first-red OP_LBU was not accepted");
    require(!dut.io_completeValid, "first-red OP_LBU completed in accept cycle");
    require(!dut.io_branchConditionValid, "first-red OP_LBU produced branch condition in accept cycle");
    unsigned observed_direct_release_count = 0;
    if (dut.io_releaseValid) {
        require(release_identity_matches(dut, c), "first-red OP_LBU direct release identity mismatch");
        ++observed_direct_release_count;
    }
    tick(dut);
    idle(dut);

    bool observed_unsupported = false;
    bool observed_lookup = false;
    unsigned observed_completion_count = 0;
    bool observed_liq_release = false;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        continue_case_inputs(dut, c, false, false);
        eval(dut);
        observed_lookup = observed_lookup || dut.io_loadLookupValid;
        observed_liq_release = observed_liq_release || dut.io_liqReleaseValid;
        if (dut.io_releaseValid) {
            require(release_identity_matches(dut, c), "first-red OP_LBU direct release identity mismatch");
            require(observed_direct_release_count == 0, "first-red OP_LBU produced duplicate direct release");
            ++observed_direct_release_count;
        }
        if (dut.io_completeValid) {
            require(lookup_identity_matches(dut, c, kPcBase), "first OP_LBU future-green missing lookup identity");
            require(completion_identity_matches(dut, c, kPcBase), "first OP_LBU future-green completion mismatch");
            require(observed_completion_count == 0, "first OP_LBU future-green duplicate completion");
            ++observed_completion_count;
        }
        if (dut.io_branchConditionValid) {
            fail("first OP_LBU produced branchConditionValid");
        }
        if (observed_lookup && observed_completion_count == 1 && observed_direct_release_count == 1) {
            drain_for_duplicates(dut, c);
            return true;
        }
        observed_unsupported =
            observed_unsupported || (dut.io_unsupported && dut.io_unsupportedOpcode == kOpLbu);
        tick(dut);
        idle(dut);
    }
    require(observed_unsupported, "first-red OP_LBU was accepted but did not report unsupported opcode 346");
    require(!observed_lookup, "first-red OP_LBU produced a load lookup before product support");
    require(observed_completion_count == 0, "first-red OP_LBU produced a completion before product support");
    require(!observed_liq_release, "first-red OP_LBU produced LIQ release before product support");
    require(observed_direct_release_count == 1, "first-red OP_LBU did not produce exactly one normal direct issue release");
    require(!dut.io_busy, "first-red OP_LBU busy did not clear after unsupported drain");
    std::cerr << "backend-reduced-scalar-alu-lbu-probe: first-red accepted OP_LBU 346 but observed unsupported/no loadLookup/no completeValid/exactly-one normal direct release/no LIQ release\n";
    std::exit(1);
    return false;
}

std::vector<Case> cases() {
    return {
        {"lbu_zero_addr_zero_byte_direct_first_red", 0x0000000000000000ULL, 0x0000000000000000ULL, 3, 0, 0x00, Ownership::Direct, false, false, 0, 16, 1, 17, 2, 40, 3, false, 4, true, 5, false, 0x14800001U, 0x1b00000000000001ULL},
        {"lbu_nonzero_base_zero_offset_high_byte_direct_wait", 0x0000000000004080ULL, 0x0000000000000000ULL, 0, 1, 0x80, Ownership::Direct, true, false, 3, 18, 4, 19, 5, 41, 6, false, 7, false, 8, true, 0x14800002U, 0x1b00000000000002ULL},
        {"lbu_nonzero_addr_zero_byte_direct_zext32", 0x0000000000005000ULL, 0x0000000000000024ULL, 1, 0, 0x00, Ownership::Direct, false, false, 6, 20, 7, 21, 8, 42, 9, true, 10, false, 11, true, 0x14800003U, 0x1b00000000000003ULL},
        {"lbu_nonzero_addr_high_byte_direct_neg", 0x0000000000006100ULL, 0xffffffffffffffffULL, 2, 0, 0xff, Ownership::Direct, false, false, 9, 22, 10, 23, 11, 43, 12, false, 13, true, 14, false, 0x14800004U, 0x1b00000000000004ULL},
        {"lbu_srcrtype_unchanged_shamt_nonzero_direct", 0x0000000000006000ULL, 0x000000000000007fULL, 3, 1, 0x81, Ownership::Direct, false, false, 12, 24, 13, 25, 14, 44, 15, true, 16, true, 17, false, 0x14800005U, 0x1b00000000000005ULL},
        {"lbu_zero_addr_zero_byte_liq_hold", 0x0000000000000000ULL, 0x0000000000000000ULL, 3, 0, 0x00, Ownership::Liq, false, true, 1, 26, 2, 27, 3, 45, 18, false, 19, false, 20, true, 0x14800006U, 0x1b00000000000006ULL},
        {"lbu_nonzero_base_zero_offset_high_byte_liq", 0x0000000000004080ULL, 0x0000000000000000ULL, 0, 1, 0x80, Ownership::Liq, false, false, 4, 28, 5, 29, 6, 46, 21, true, 22, false, 23, true, 0x14800007U, 0x1b00000000000007ULL},
        {"lbu_nonzero_addr_zero_byte_liq_zext32", 0x0000000000005000ULL, 0x0000000000000024ULL, 1, 0, 0x00, Ownership::Liq, false, false, 7, 30, 8, 31, 9, 47, 24, false, 25, true, 26, false, 0x14800008U, 0x1b00000000000008ULL},
        {"lbu_nonzero_addr_high_byte_liq_neg", 0x0000000000006100ULL, 0xffffffffffffffffULL, 2, 0, 0xff, Ownership::Liq, false, false, 10, 32, 11, 33, 12, 48, 27, true, 28, true, 29, false, 0x14800009U, 0x1b00000000000009ULL},
        {"lbu_srcrtype_unchanged_shamt_nonzero_liq", 0x0000000000006000ULL, 0x000000000000007fULL, 3, 1, 0x81, Ownership::Liq, false, false, 13, 34, 14, 35, 15, 49, 30, false, 31, false, 32, true, 0x1480000aU, 0x1b0000000000000aULL},
    };
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedScalarAluLbuProbe dut;

    const std::vector<Case> all_cases = cases();
    const bool first_case_completed = prove_first_lbu_current_red_or_future_green(dut, all_cases.front());
    require(first_case_completed, "first OP_LBU neither completed future-green nor exited at current first-red");
    for (unsigned idx = 1; idx < all_cases.size(); ++idx) {
        run_future_green_case(dut, all_cases[idx], idx);
    }

    std::cout << "backend-reduced-scalar-alu-lbu-probe: PASS\n";
    return 0;
}
