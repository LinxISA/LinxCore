#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedScalarAluSetcImmediateBehaviorProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpSetcAndi = 327;
constexpr std::uint32_t kOpSetcEqi = 329;
constexpr std::uint32_t kOpSetcGei = 331;
constexpr std::uint32_t kOpSetcGeui = 333;
constexpr std::uint32_t kOpSetcLti = 335;
constexpr std::uint32_t kOpSetcLtui = 337;
constexpr std::uint32_t kOpSetcNei = 339;
constexpr std::uint32_t kOpSetcOri = 341;
constexpr std::uint32_t kOpHlSetcAndi = 227;
constexpr std::uint32_t kOpHlSetcEqi = 228;
constexpr std::uint32_t kOpHlSetcGei = 229;
constexpr std::uint32_t kOpHlSetcGeui = 230;
constexpr std::uint32_t kOpHlSetcLti = 231;
constexpr std::uint32_t kOpHlSetcLtui = 232;
constexpr std::uint32_t kOpHlSetcNei = 233;
constexpr std::uint32_t kOpHlSetcOri = 234;
constexpr std::uint64_t kPcBase = 0x0000000000018000ULL;
constexpr std::uint8_t kSrc0Arch = 7;
constexpr std::uint8_t kSrc0Phys = 23;
constexpr int kWaitCycles = 8;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
    const char *name;
    std::uint32_t opcode;
    std::uint64_t lhs;
    std::uint32_t imm;
    std::uint8_t shift;
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
    std::cerr << "backend-reduced-scalar-alu-setc-immediate-behavior-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VReducedScalarAluSetcImmediateBehaviorProbe &dut) {
    dut.eval();
}

void tick(VReducedScalarAluSetcImmediateBehaviorProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

void idle(VReducedScalarAluSetcImmediateBehaviorProbe &dut) {
    dut.io_inValid = 0;
    dut.io_flushValid = 0;
    dut.io_opcode = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_imm = 0;
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
    dut.io_src0ArchTag = kSrc0Arch;
    dut.io_src0PhysTag = kSrc0Phys;
}

void reset(VReducedScalarAluSetcImmediateBehaviorProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

bool is_unsigned_immediate_opcode(std::uint32_t opcode) {
    return opcode == kOpSetcLtui || opcode == kOpSetcGeui ||
           opcode == kOpHlSetcLtui || opcode == kOpHlSetcGeui;
}

bool is_hl_immediate_opcode(std::uint32_t opcode) {
    return opcode == kOpHlSetcAndi || opcode == kOpHlSetcEqi ||
           opcode == kOpHlSetcGei || opcode == kOpHlSetcGeui ||
           opcode == kOpHlSetcLti || opcode == kOpHlSetcLtui ||
           opcode == kOpHlSetcNei || opcode == kOpHlSetcOri;
}

std::uint64_t sign_extend(std::uint32_t imm, unsigned width) {
    const std::uint64_t mask = (1ULL << width) - 1ULL;
    const std::uint64_t sign_bit = 1ULL << (width - 1U);
    const std::uint64_t raw = imm & mask;
    return (raw & sign_bit) ? (raw | ~mask) : raw;
}

std::uint64_t decoded_imm(const Case &c) {
    const unsigned width = is_hl_immediate_opcode(c.opcode) ? 24U : 12U;
    const std::uint64_t mask = (1ULL << width) - 1ULL;
    return is_unsigned_immediate_opcode(c.opcode) ? (c.imm & mask) : sign_extend(c.imm, width);
}

std::uint64_t shifted_imm(const Case &c) {
    return decoded_imm(c) << (c.shift & 31U);
}

bool setc_immediate_condition(const Case &c) {
    const std::uint64_t rhs = shifted_imm(c);
    switch (c.opcode) {
    case kOpSetcEqi:
    case kOpHlSetcEqi:
        return c.lhs == rhs;
    case kOpSetcNei:
    case kOpHlSetcNei:
        return c.lhs != rhs;
    case kOpSetcAndi:
    case kOpHlSetcAndi:
        return (c.lhs & rhs) != 0;
    case kOpSetcOri:
    case kOpHlSetcOri:
        return (c.lhs | rhs) != 0;
    case kOpSetcLti:
    case kOpHlSetcLti:
        return static_cast<std::int64_t>(c.lhs) < static_cast<std::int64_t>(rhs);
    case kOpSetcGei:
    case kOpHlSetcGei:
        return static_cast<std::int64_t>(c.lhs) >= static_cast<std::int64_t>(rhs);
    case kOpSetcLtui:
    case kOpHlSetcLtui:
        return c.lhs < rhs;
    case kOpSetcGeui:
    case kOpHlSetcGeui:
        return c.lhs >= rhs;
    default:
        fail("unknown SETC-immediate opcode in oracle");
        return false;
    }
}

std::uint64_t opcode_match(std::uint32_t opcode) {
    switch (opcode) {
    case kOpHlSetcEqi:
        return 0x000000000075000eULL;
    case kOpHlSetcNei:
        return 0x000000001075000eULL;
    case kOpHlSetcAndi:
        return 0x000000002075000eULL;
    case kOpHlSetcOri:
        return 0x000000003075000eULL;
    case kOpHlSetcLti:
        return 0x000000004075000eULL;
    case kOpHlSetcGei:
        return 0x000000005075000eULL;
    case kOpHlSetcLtui:
        return 0x000000006075000eULL;
    case kOpHlSetcGeui:
        return 0x000000007075000eULL;
    case kOpSetcEqi:
        return 0x00000075ULL;
    case kOpSetcNei:
        return 0x00001075ULL;
    case kOpSetcAndi:
        return 0x00002075ULL;
    case kOpSetcOri:
        return 0x00003075ULL;
    case kOpSetcLti:
        return 0x00004075ULL;
    case kOpSetcGei:
        return 0x00005075ULL;
    case kOpSetcLtui:
        return 0x00006075ULL;
    case kOpSetcGeui:
        return 0x00007075ULL;
    default:
        fail("unknown SETC-immediate opcode while constructing instruction");
        return 0;
    }
}

std::uint64_t insn_for_case(const Case &c) {
    if (is_hl_immediate_opcode(c.opcode)) {
        const std::uint64_t imm = c.imm & 0x00ffffffU;
        return opcode_match(c.opcode) |
               ((imm >> 12) << 4) |
               ((imm & 0x0fffU) << 36) |
               (static_cast<std::uint64_t>(kSrc0Arch & 0x1fU) << 31) |
               (static_cast<std::uint64_t>(c.shift & 0x1fU) << 23);
    }
    return opcode_match(c.opcode) |
           (static_cast<std::uint64_t>(c.imm & 0x0fffU) << 20) |
           (static_cast<std::uint64_t>(kSrc0Arch & 0x1fU) << 15) |
           (static_cast<std::uint64_t>(c.shift & 0x1fU) << 7);
}

void drive_case(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c, std::uint64_t pc, bool flush) {
    idle(dut);
    dut.io_inValid = 1;
    dut.io_flushValid = flush ? 1 : 0;
    dut.io_opcode = c.opcode;
    dut.io_pc = pc;
    dut.io_insnRaw = insn_for_case(c);
    dut.io_imm = decoded_imm(c);
    dut.io_src0Data = c.lhs;
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
    eval(dut);
}

void require_no_success_pulses(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const std::string &context) {
    require(!dut.io_completeValid, context + ": unexpected completeValid");
    require(!dut.io_branchConditionValid, context + ": unexpected branchConditionValid");
    require(!dut.io_releaseValid, context + ": unexpected releaseValid");
}

void require_no_completion_branch_or_writeback(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const std::string &context) {
    require(!dut.io_completeValid, context + ": unexpected completeValid");
    require(!dut.io_branchConditionValid, context + ": unexpected branchConditionValid");
    require(!dut.io_completeDstPhysValid, context + ": unexpected RF destination valid");
    require(dut.io_completeDstPhysTag == 0, context + ": unexpected RF destination tag");
    require(dut.io_completeDstData == 0, context + ": unexpected RF destination data");
    require(!dut.io_completeRowValid, context + ": unexpected commit row valid");
    require(!dut.io_completeRowDstValid, context + ": unexpected architectural destination valid");
    require(dut.io_completeRowDstReg == 0, context + ": unexpected architectural destination register");
    require(dut.io_completeRowDstData == 0, context + ": unexpected architectural destination data");
    require(!dut.io_completeRowWbValid, context + ": unexpected architectural writeback valid");
    require(dut.io_completeRowWbReg == 0, context + ": unexpected architectural writeback register");
    require(dut.io_completeRowWbData == 0, context + ": unexpected architectural writeback data");
}

bool completion_identity_matches(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c, std::uint64_t pc) {
    return dut.io_completeRobValue == c.rid && dut.io_completeLsId == c.lsid &&
           !dut.io_completeDstPhysValid && dut.io_completeDstPhysTag == 0 &&
           dut.io_completeDstData == 0 && dut.io_completeSrc0PhysValid &&
           dut.io_completeSrc0PhysTag == kSrc0Phys && !dut.io_completeSrc1PhysValid &&
           dut.io_completeSrc1PhysTag == 0 && dut.io_completeRowValid &&
           dut.io_completeRowBid == c.bid && dut.io_completeRowGid == c.gid &&
           dut.io_completeRowRid == c.rid && dut.io_completeRowRobValid &&
           dut.io_completeRowRobWrap == c.rid_wrap && dut.io_completeRowRobValue == c.rid &&
           dut.io_completeRowBlockBidValid && dut.io_completeRowBlockBid == c.block_bid &&
           dut.io_completeRowPc == pc && dut.io_completeRowInsn == insn_for_case(c) &&
           dut.io_completeRowLen == (is_hl_immediate_opcode(c.opcode) ? 6 : 4) && dut.io_completeRowSrc0Valid &&
           dut.io_completeRowSrc0Reg == kSrc0Arch && dut.io_completeRowSrc0Data == c.lhs &&
           !dut.io_completeRowSrc1Valid && dut.io_completeRowSrc1Reg == 0 &&
           dut.io_completeRowSrc1Data == 0 && !dut.io_completeRowDstValid &&
           dut.io_completeRowDstReg == 0 && dut.io_completeRowDstData == 0 &&
           !dut.io_completeRowWbValid && dut.io_completeRowWbReg == 0 &&
           dut.io_completeRowWbData == 0;
}

bool release_identity_matches(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c) {
    return dut.io_releaseBidValid && dut.io_releaseBidWrap == c.bid_wrap &&
           dut.io_releaseBidValue == c.bid && dut.io_releaseGidValid &&
           dut.io_releaseGidWrap == c.gid_wrap && dut.io_releaseGidValue == c.gid &&
           dut.io_releaseRidValid && dut.io_releaseRidWrap == c.rid_wrap &&
           dut.io_releaseRidValue == c.rid && dut.io_releaseStid == 0;
}

void require_current_red_release_row(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c) {
    require(dut.io_unsupported, std::string(c.name) + ": missing unsupported pulse");
    require(dut.io_unsupportedOpcode == kOpSetcEqi, std::string(c.name) + ": unsupported opcode was not OP_SETC_EQI 329");
    require(dut.io_releaseValid, std::string(c.name) + ": missing mandatory unsupported releaseValid");
    require(release_identity_matches(dut, c), std::string(c.name) + ": unsupported release identity mismatch");
    require_no_completion_branch_or_writeback(dut, std::string(c.name) + " current-red row");
}

void require_future_green_row(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c, std::uint64_t pc) {
    require(completion_identity_matches(dut, c, pc), std::string(c.name) + ": completion/source/no-writeback identity mismatch");
    require(dut.io_branchConditionValid, std::string(c.name) + ": missing branchConditionValid");
    require(static_cast<bool>(dut.io_branchConditionTaken) == c.expected,
            std::string(c.name) + ": branch condition disagreed with QEMU/pyCircuit oracle");
    require(dut.io_releaseValid, std::string(c.name) + ": missing releaseValid");
    require(release_identity_matches(dut, c), std::string(c.name) + ": release identity mismatch");
    require(!dut.io_unsupported, std::string(c.name) + ": unsupported pulse on future-green row");
}

void drain_for_duplicates(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        if (dut.io_completeValid || dut.io_branchConditionValid || dut.io_releaseValid || dut.io_unsupported) {
            fail(std::string(c.name) + ": duplicate completion/condition/release/unsupported pulse before reset");
        }
    }
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after drain");
}

void wait_for_future_green_case(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c, std::uint64_t pc) {
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        if (dut.io_completeValid) {
            require_future_green_row(dut, c, pc);
            return;
        }
        if (dut.io_branchConditionValid || dut.io_releaseValid) {
            fail(std::string(c.name) + ": sideband pulse arrived without completeValid");
        }
        if (dut.io_unsupported && dut.io_unsupportedOpcode == c.opcode) {
            fail(std::string(c.name) + ": unsupported opcode " + std::to_string(c.opcode) + " before future-green completion");
        }
        tick(dut);
        idle(dut);
    }
    fail(std::string(c.name) + ": accepted transaction produced no completion");
}

void run_future_green_case(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c, unsigned index) {
    reset(dut);
    const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 4ULL;
    const unsigned shamt_lsb = is_hl_immediate_opcode(c.opcode) ? 23U : 7U;
    require((c.shift == ((insn_for_case(c) >> shamt_lsb) & 0x1fU)), std::string(c.name) + ": encoded shift fixture mismatch");
    require(setc_immediate_condition(c) == c.expected, std::string(c.name) + ": embedded oracle fixture is inconsistent");
    drive_case(dut, c, pc, false);
    require(dut.io_inReady, std::string(c.name) + ": input was not ready");
    require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");
    require_no_success_pulses(dut, std::string(c.name) + " accept cycle");
    tick(dut);
    idle(dut);
    wait_for_future_green_case(dut, c, pc);
    drain_for_duplicates(dut, c);
}

bool prove_first_current_red_or_future_green(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c) {
    reset(dut);
    require(c.opcode == kOpSetcEqi, "first transaction must be OP_SETC_EQI");
    require(c.expected, "first OP_SETC_EQI fixture must be oracle-true");
    drive_case(dut, c, kPcBase, false);
    require(dut.io_inReady, "first-red OP_SETC_EQI input was not ready");
    require(dut.io_accepted, "first-red OP_SETC_EQI was not accepted");
    require_no_success_pulses(dut, "first-red OP_SETC_EQI accept cycle");
    tick(dut);
    idle(dut);
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        eval(dut);
        if (dut.io_completeValid) {
            require_future_green_row(dut, c, kPcBase);
            drain_for_duplicates(dut, c);
            return true;
        }
        if (dut.io_unsupported || dut.io_releaseValid || dut.io_branchConditionValid) {
            require_current_red_release_row(dut, c);
            drain_for_duplicates(dut, c);
            std::cerr << "backend-reduced-scalar-alu-setc-immediate-behavior-probe: first-red accepted OP_SETC_EQI 329 and observed unsupportedOpcode=329 with release BID=3/0 GID=4/1 RID=5/0 STID=0; no completeValid/no branchConditionValid/no writeback/no duplicate pulses\n";
            std::exit(1);
        }
        tick(dut);
        idle(dut);
    }
    fail("first-red OP_SETC_EQI was accepted but produced no unsupported+release current-red row");
    return false;
}

void run_back_to_back(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &first, const Case &second) {
    reset(dut);
    require(setc_immediate_condition(first) == first.expected, "back-to-back first oracle fixture is inconsistent");
    require(setc_immediate_condition(second) == second.expected, "back-to-back second oracle fixture is inconsistent");
    const std::uint64_t first_pc = kPcBase + 0x400;
    const std::uint64_t second_pc = kPcBase + 0x404;
    drive_case(dut, first, first_pc, false);
    require(dut.io_inReady && dut.io_accepted, "back-to-back first transaction was not accepted");
    tick(dut);
    idle(dut);
    wait_for_future_green_case(dut, first, first_pc);
    tick(dut);
    drive_case(dut, second, second_pc, false);
    require(dut.io_inReady && dut.io_accepted, "back-to-back second transaction was not accepted after first completion");
    tick(dut);
    idle(dut);
    wait_for_future_green_case(dut, second, second_pc);
    drain_for_duplicates(dut, second);
}

void run_flush_cancellation(VReducedScalarAluSetcImmediateBehaviorProbe &dut, const Case &c) {
    reset(dut);
    const std::uint64_t pc = kPcBase + 0x800;
    drive_case(dut, c, pc, false);
    require(dut.io_inReady && dut.io_accepted, "flush-cancel transaction was not accepted");
    tick(dut);
    idle(dut);
    dut.io_flushValid = 1;
    for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        require_no_success_pulses(dut, "flush-cancel drain");
        require(!dut.io_unsupported, "flush-cancel produced unsupported after flush");
    }
    require(!dut.io_busy, "flush-cancel busy did not clear");
}

std::vector<Case> cases() {
    return {
        {"setc_eqi_shift0_true_first_red", kOpSetcEqi, 0x0000000000000007ULL, 0x007, 0, true, 3, false, 4, true, 5, false, 0x22000001U, 0xbcd0000000000001ULL},
        {"setc_eqi_shift1_false", kOpSetcEqi, 0x000000000000000eULL, 0x008, 1, false, 6, false, 7, true, 8, false, 0x22000002U, 0xbcd0000000000002ULL},
        {"setc_nei_sign_boundary_false", kOpSetcNei, 0xfffffffffffff800ULL, 0x800, 0, false, 9, true, 10, false, 11, true, 0x22000003U, 0xbcd0000000000003ULL},
        {"setc_nei_shift31_wrap_true", kOpSetcNei, 0x0000000000000000ULL, 0x800, 31, true, 12, false, 13, false, 14, true, 0x22000004U, 0xbcd0000000000004ULL},
        {"setc_andi_zero_false", kOpSetcAndi, 0x00f0000000000000ULL, 0x00f, 8, false, 15, true, 16, true, 17, false, 0x22000005U, 0xbcd0000000000005ULL},
        {"setc_andi_nonzero_true", kOpSetcAndi, 0x0000000000000f00ULL, 0x00f, 8, true, 18, false, 19, true, 20, false, 0x22000006U, 0xbcd0000000000006ULL},
        {"setc_ori_zero_false", kOpSetcOri, 0x0000000000000000ULL, 0x000, 31, false, 21, true, 22, false, 23, true, 0x22000007U, 0xbcd0000000000007ULL},
        {"setc_ori_nonzero_true", kOpSetcOri, 0x0000000000000000ULL, 0x001, 31, true, 24, false, 25, true, 26, false, 0x22000008U, 0xbcd0000000000008ULL},
        {"setc_lti_signed_min_true", kOpSetcLti, 0x8000000000000000ULL, 0x000, 0, true, 27, true, 28, false, 29, true, 0x22000009U, 0xbcd0000000000009ULL},
        {"setc_lti_negative_imm_false", kOpSetcLti, 0xfffffffffffff800ULL, 0x800, 0, false, 30, false, 31, true, 32, false, 0x2200000aU, 0xbcd000000000000aULL},
        {"setc_gei_negative_imm_true", kOpSetcGei, 0xfffffffffffff800ULL, 0x800, 0, true, 33, true, 34, false, 35, true, 0x2200000bU, 0xbcd000000000000bULL},
        {"setc_gei_signed_min_false", kOpSetcGei, 0x8000000000000000ULL, 0x000, 0, false, 36, false, 37, true, 38, false, 0x2200000cU, 0xbcd000000000000cULL},
        {"setc_ltui_high_unsigned_true", kOpSetcLtui, 0x0000000000000001ULL, 0xfff, 31, true, 39, true, 40, false, 41, true, 0x2200000dU, 0xbcd000000000000dULL},
        {"setc_ltui_equality_false", kOpSetcLtui, 0x0000000000000fffULL, 0xfff, 0, false, 42, false, 43, true, 44, false, 0x2200000eU, 0xbcd000000000000eULL},
        {"setc_geui_equality_true", kOpSetcGeui, 0x0000000000000fffULL, 0xfff, 0, true, 45, true, 46, false, 47, true, 0x2200000fU, 0xbcd000000000000fULL},
        {"setc_geui_high_unsigned_false", kOpSetcGeui, 0x0000000000000001ULL, 0xfff, 31, false, 48, false, 49, true, 50, false, 0x22000010U, 0xbcd0000000000010ULL},
        {"hl_setc_eqi_shift2_true", kOpHlSetcEqi, 0x0000000000048d14ULL, 0x012345, 2, true, 51, true, 52, false, 53, true, 0x22000011U, 0xbcd0000000000011ULL},
        {"hl_setc_eqi_shift3_false", kOpHlSetcEqi, 0x0000000000091a28ULL, 0x012346, 3, false, 54, false, 55, true, 56, false, 0x22000012U, 0xbcd0000000000012ULL},
        {"hl_setc_nei_sign_boundary_false", kOpHlSetcNei, 0xffffffffff800000ULL, 0x800000, 0, false, 57, true, 58, false, 59, true, 0x22000013U, 0xbcd0000000000013ULL},
        {"hl_setc_nei_shift5_true", kOpHlSetcNei, 0x0000000000000000ULL, 0x800001, 5, true, 60, false, 61, true, 62, false, 0x22000014U, 0xbcd0000000000014ULL},
        {"hl_setc_andi_zero_false", kOpHlSetcAndi, 0x00000000f0000000ULL, 0x00000f, 4, false, 1, true, 2, true, 3, false, 0x22000015U, 0xbcd0000000000015ULL},
        {"hl_setc_andi_nonzero_true", kOpHlSetcAndi, 0x00000000000000f0ULL, 0x00000f, 4, true, 4, false, 5, true, 6, false, 0x22000016U, 0xbcd0000000000016ULL},
        {"hl_setc_ori_zero_false", kOpHlSetcOri, 0x0000000000000000ULL, 0x000000, 9, false, 7, true, 8, false, 9, true, 0x22000017U, 0xbcd0000000000017ULL},
        {"hl_setc_ori_nonzero_true", kOpHlSetcOri, 0x0000000000000000ULL, 0x000001, 9, true, 10, false, 11, true, 12, false, 0x22000018U, 0xbcd0000000000018ULL},
        {"hl_setc_lti_signed_min_true", kOpHlSetcLti, 0x8000000000000000ULL, 0x000000, 0, true, 13, true, 14, false, 15, true, 0x22000019U, 0xbcd0000000000019ULL},
        {"hl_setc_lti_negative_imm_false", kOpHlSetcLti, 0xffffffffff800000ULL, 0x800000, 0, false, 16, false, 17, true, 18, false, 0x2200001aU, 0xbcd000000000001aULL},
        {"hl_setc_gei_negative_imm_true", kOpHlSetcGei, 0xffffffffff800000ULL, 0x800000, 0, true, 19, true, 20, false, 21, true, 0x2200001bU, 0xbcd000000000001bULL},
        {"hl_setc_gei_signed_min_false", kOpHlSetcGei, 0x8000000000000000ULL, 0x000000, 0, false, 22, false, 23, true, 24, false, 0x2200001cU, 0xbcd000000000001cULL},
        {"hl_setc_ltui_high_unsigned_true", kOpHlSetcLtui, 0x0000000000000001ULL, 0xffffff, 8, true, 25, true, 26, false, 27, true, 0x2200001dU, 0xbcd000000000001dULL},
        {"hl_setc_ltui_equality_false", kOpHlSetcLtui, 0x0000000000ffffffULL, 0xffffff, 0, false, 28, false, 29, true, 30, false, 0x2200001eU, 0xbcd000000000001eULL},
        {"hl_setc_geui_equality_true", kOpHlSetcGeui, 0x0000000000ffffffULL, 0xffffff, 0, true, 31, true, 32, false, 33, true, 0x2200001fU, 0xbcd000000000001fULL},
        {"hl_setc_geui_high_unsigned_false", kOpHlSetcGeui, 0x0000000000000001ULL, 0xffffff, 8, false, 34, false, 35, true, 36, false, 0x22000020U, 0xbcd0000000000020ULL},
    };
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedScalarAluSetcImmediateBehaviorProbe dut;

    const std::vector<Case> all_cases = cases();
    const bool first_case_completed = prove_first_current_red_or_future_green(dut, all_cases.front());
    require(first_case_completed, "first OP_SETC_EQI neither completed future-green nor exited at current first-red");
    for (unsigned idx = 1; idx < all_cases.size(); ++idx) {
        run_future_green_case(dut, all_cases[idx], idx);
    }
    run_back_to_back(dut, all_cases[1], all_cases[2]);
    run_flush_cancellation(dut, all_cases[3]);

    std::cout << "backend-reduced-scalar-alu-setc-immediate-behavior-probe: PASS\n";
    return 0;
}
