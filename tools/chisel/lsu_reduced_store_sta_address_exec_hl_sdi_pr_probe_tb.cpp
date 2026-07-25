#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedStoreStaAddressExecHlSdiPrProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpHlSdiPo = 216;
constexpr std::uint32_t kOpHlSdiPr = 217;
constexpr std::uint32_t kOpHlSdiUpr = 220;
constexpr std::uint32_t kOpSb = 386;
constexpr std::uint32_t kOpSdi = 390;
constexpr std::uint8_t kStoreTypeAll = 0;
constexpr std::uint8_t kStoreTypeAddr = 1;
constexpr std::uint8_t kStoreTypeData = 2;
constexpr int kStableCycles = 5;

struct Case {
    const char *name;
    std::uint32_t opcode;
    std::uint64_t raw;
    std::int32_t simm;
    std::uint64_t base;
    std::uint64_t src_d_data;
    std::uint8_t src_d_arch;
    std::uint8_t src_d_phys;
    std::uint8_t src_r_arch;
    std::uint8_t src_r_phys;
    std::uint64_t pc;
    std::uint8_t pe;
    std::uint8_t thread;
    bool bid_wrap;
    std::uint8_t bid;
    bool gid_wrap;
    std::uint8_t gid;
    bool rid_wrap;
    std::uint8_t rid;
    std::uint32_t lsid;
};

void fail(const std::string &message) {
    std::cerr << "lsu-reduced-store-sta-address-exec-hl-sdi-pr-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const std::string &message) {
    if (!condition) {
        fail(message);
    }
}

std::uint64_t mask64(std::uint64_t value) {
    return value;
}

std::int32_t sign_extend_17(std::uint32_t value) {
    const std::uint32_t masked = value & 0x1ffffU;
    return (masked & 0x10000U) != 0 ? static_cast<std::int32_t>(masked | 0xfffe0000U)
                                    : static_cast<std::int32_t>(masked);
}

std::uint32_t raw_simm17(std::uint64_t raw) {
    return static_cast<std::uint32_t>((((raw >> 6) & 0x1fULL) << 12) |
                                      (((raw >> 23) & 0x1fULL) << 7) |
                                      ((raw >> 41) & 0x7fULL));
}

std::uint64_t scaled_simm17(std::int32_t simm) {
    return mask64(static_cast<std::uint64_t>(static_cast<std::int64_t>(simm)) << 3);
}

std::uint64_t expected_pr_address(const Case &c) {
    require(sign_extend_17(raw_simm17(c.raw)) == c.simm, std::string(c.name) + ": raw simm17 decode mismatch");
    return mask64(c.base + scaled_simm17(c.simm));
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
           (static_cast<std::uint64_t>((imm >> 12) & 0x1fU) << 6) |
           (static_cast<std::uint64_t>(dst & 0x1fU) << 11) |
           (static_cast<std::uint64_t>((imm >> 7) & 0x1fU) << 23) |
           (static_cast<std::uint64_t>(src_d & 0x1fU) << 35) |
           (static_cast<std::uint64_t>(imm & 0x7fU) << 41);
}

std::uint64_t sdi_raw(std::uint8_t src_d, std::uint8_t src_r, std::int16_t simm) {
    const std::uint16_t imm = static_cast<std::uint16_t>(simm) & 0x0fffU;
    return (static_cast<std::uint64_t>((imm >> 5) & 0x7fU) << 25) |
           (static_cast<std::uint64_t>(src_d & 0x1fU) << 20) |
           (static_cast<std::uint64_t>(src_r & 0x1fU) << 15) |
           (static_cast<std::uint64_t>(imm & 0x1fU) << 7) |
           0x3059ULL;
}

std::uint64_t sb_raw(std::uint8_t src_d, std::uint8_t src_l, std::uint8_t src_r) {
    return (static_cast<std::uint64_t>(src_d & 0x1fU) << 20) |
           (static_cast<std::uint64_t>(src_l & 0x1fU) << 15) |
           (static_cast<std::uint64_t>(src_r & 0x1fU) << 7) |
           0x06000049ULL;
}

void eval(VReducedStoreStaAddressExecHlSdiPrProbe &dut) {
    dut.eval();
}

void idle(VReducedStoreStaAddressExecHlSdiPrProbe &dut) {
    dut.io_enable = 0;
    dut.io_queueValid = 0;
    dut.io_payloadValid = 0;
    dut.io_opcode = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_imm = 0;
    dut.io_storeType = kStoreTypeAddr;
    dut.io_peId = 0;
    dut.io_threadId = 0;
    dut.io_lsid = 0;
    dut.io_bidValid = 1;
    dut.io_bidWrap = 0;
    dut.io_bidValue = 0;
    dut.io_gidValid = 1;
    dut.io_gidWrap = 0;
    dut.io_gidValue = 0;
    dut.io_ridValid = 1;
    dut.io_ridWrap = 0;
    dut.io_ridValue = 0;
    dut.io_srcValid_0 = 0;
    dut.io_srcValid_1 = 0;
    dut.io_srcValid_2 = 0;
    dut.io_srcReady_0 = 1;
    dut.io_srcReady_1 = 1;
    dut.io_srcReady_2 = 1;
    dut.io_srcData_0 = 0;
    dut.io_srcData_1 = 0;
    dut.io_srcData_2 = 0;
    dut.io_srcArchTag_0 = 1;
    dut.io_srcArchTag_1 = 2;
    dut.io_srcArchTag_2 = 3;
    dut.io_srcPhysTag_0 = 21;
    dut.io_srcPhysTag_1 = 22;
    dut.io_srcPhysTag_2 = 23;
}

void drive_case(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c,
                std::uint32_t opcode = kOpHlSdiPr, std::uint8_t store_type = kStoreTypeAddr,
                bool enable = true, bool queue_valid = true, bool payload_valid = true,
                bool src0_valid = true, bool src0_ready = true, bool src1_valid = true,
                bool src1_ready = true, bool src2_valid = false, bool src2_ready = true) {
    idle(dut);
    dut.io_enable = enable ? 1 : 0;
    dut.io_queueValid = queue_valid ? 1 : 0;
    dut.io_payloadValid = payload_valid ? 1 : 0;
    dut.io_opcode = opcode;
    dut.io_pc = c.pc;
    dut.io_insnRaw = c.raw;
    dut.io_imm = static_cast<std::uint64_t>(static_cast<std::int64_t>(c.simm));
    dut.io_storeType = store_type;
    dut.io_peId = c.pe;
    dut.io_threadId = c.thread;
    dut.io_lsid = c.lsid;
    dut.io_bidValid = 1;
    dut.io_bidWrap = c.bid_wrap ? 1 : 0;
    dut.io_bidValue = c.bid;
    dut.io_gidValid = 1;
    dut.io_gidWrap = c.gid_wrap ? 1 : 0;
    dut.io_gidValue = c.gid;
    dut.io_ridValid = 1;
    dut.io_ridWrap = c.rid_wrap ? 1 : 0;
    dut.io_ridValue = c.rid;
    dut.io_srcValid_0 = src0_valid ? 1 : 0;
    dut.io_srcReady_0 = src0_ready ? 1 : 0;
    dut.io_srcData_0 = c.src_d_data;
    dut.io_srcValid_1 = src1_valid ? 1 : 0;
    dut.io_srcReady_1 = src1_ready ? 1 : 0;
    dut.io_srcData_1 = c.base;
    dut.io_srcValid_2 = src2_valid ? 1 : 0;
    dut.io_srcReady_2 = src2_ready ? 1 : 0;
    dut.io_srcData_2 = 0xfeedfacecafebeefULL;
    dut.io_srcArchTag_0 = c.src_d_arch;
    dut.io_srcArchTag_1 = c.src_r_arch;
    dut.io_srcArchTag_2 = 3;
    dut.io_srcPhysTag_0 = c.src_d_phys;
    dut.io_srcPhysTag_1 = c.src_r_phys;
    dut.io_srcPhysTag_2 = 33;
    eval(dut);
}

std::vector<Case> cases() {
    return {
        {"hl_sdi_pr_real_dhrystone_raw_minus16_first_red", kOpHlSdiPr, 0xfc15bfd90feeULL, -2, 0x0000004000001000ULL, 0x1122334455667788ULL, 2, 20, 28, 21, 0x100c8ULL, 1, 2, false, 21, true, 31, false, 10, 0x85700001U},
        {"hl_sdi_pr_zero_offset", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 5, 6, 7, 0), 0, 0x0000000000008000ULL, 0xa5a55a5ac3c33c3cULL, 6, 23, 7, 24, 0x17008ULL, 2, 3, true, 22, false, 32, true, 11, 0x85700002U},
        {"hl_sdi_pr_positive_offset", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 8, 9, 10, 31), 31, 0x0000000000009000ULL, 0x0102030405060708ULL, 9, 26, 10, 27, 0x17010ULL, 3, 4, false, 23, false, 33, true, 12, 0x85700003U},
        {"hl_sdi_pr_negative_boundary", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 11, 12, 13, -65536), -65536, 0x0000000000100000ULL, 0xfedcba9876543210ULL, 12, 29, 13, 30, 0x17018ULL, 4, 5, true, 24, true, 34, false, 13, 0x85700004U},
        {"hl_sdi_pr_positive_boundary_wrap", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 16, 15, 16, 65535), 65535, 0xfffffffffffffff8ULL, 0x8877665544332211ULL, 15, 32, 16, 33, 0x17020ULL, 5, 6, false, 25, true, 35, true, 14, 0x85700005U},
    };
}

void require_oracle_self_checks(const std::vector<Case> &all) {
    require(!all.empty(), "oracle setup produced no HL.SDI.PR cases");
    require(all.front().raw == 0xfc15bfd90feeULL, "oracle real raw case bytes changed");
    require(all.front().simm == -2, "oracle real raw case expected simm changed");
    require(sign_extend_17(raw_simm17(all.front().raw)) == -2,
            "oracle real raw bytes did not decode to simm -2");

    for (const Case &c : all) {
        require(c.opcode == kOpHlSdiPr, std::string(c.name) + ": non-HL.SDI.PR case in PR matrix");
        require(c.src_r_arch != c.src_d_arch, std::string(c.name) + ": source arch tags must distinguish SrcD/SrcR");
        require(c.src_r_phys != c.src_d_phys, std::string(c.name) + ": source phys tags must distinguish SrcD/SrcR");
        require(sign_extend_17(raw_simm17(c.raw)) == c.simm, std::string(c.name) + ": case raw/simm mismatch");
        require(expected_pr_address(c) == mask64(c.base + scaled_simm17(c.simm)),
                std::string(c.name) + ": expected address calculator mismatch");
    }

    const std::int32_t round_trip_simms[] = {0, 1, -1, 31, -32, 65535, -65536, -2};
    for (std::int32_t simm : round_trip_simms) {
        const std::uint64_t raw = hl_sdi_raw(kOpHlSdiPr, 3, 4, 5, simm);
        require(sign_extend_17(raw_simm17(raw)) == simm, "oracle synthetic HL.SDI.PR raw round trip failed");
    }

    const Case scaled_cases[] = {
        {"oracle_scaled_positive", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 1, 2, 3, 7), 7,
         0x1000ULL, 0, 2, 20, 3, 21, 0, 0, 0, false, 0, false, 0, false, 0, 0},
        {"oracle_scaled_negative", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 1, 2, 3, -2), -2,
         0x1000ULL, 0, 2, 20, 3, 21, 0, 0, 0, false, 0, false, 0, false, 0, 0},
        {"oracle_scaled_wrap", kOpHlSdiPr, hl_sdi_raw(kOpHlSdiPr, 1, 2, 3, 1), 1,
         0xfffffffffffffff8ULL, 0, 2, 20, 3, 21, 0, 0, 0, false, 0, false, 0, false, 0, 0},
    };
    require(expected_pr_address(scaled_cases[0]) == 0x1038ULL, "oracle positive scaled address mismatch");
    require(expected_pr_address(scaled_cases[1]) == 0xff0ULL, "oracle negative scaled address mismatch");
    require(expected_pr_address(scaled_cases[2]) == 0, "oracle wrapping scaled address mismatch");
}

void require_common_inputs(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c, const std::string &name) {
    require(dut.io_queueOpcode == c.opcode, name + ": queue opcode mismatch");
    require(dut.io_queueInsnRaw == c.raw, name + ": raw instruction was not preserved");
    require(dut.io_queueImm == static_cast<std::uint64_t>(static_cast<std::int64_t>(c.simm)),
            name + ": sign-extended immediate was not preserved");
    require(dut.io_queueStoreType == kStoreTypeAddr, name + ": store type was not Addr");
    require(dut.io_queueSrcValid_0 && dut.io_queueSrcReady_0, name + ": SrcD source was not preserved");
    require(dut.io_queueSrcValid_1 && dut.io_queueSrcReady_1, name + ": SrcR/base source was not valid-ready");
    require(!dut.io_queueSrcValid_2 && dut.io_queueSrcReady_2, name + ": unexpected source 2 dependency");
    require(dut.io_queueSrcData_0 == c.src_d_data, name + ": SrcD data mismatch");
    require(dut.io_queueSrcData_1 == c.base, name + ": SrcR/base data mismatch");
    require(dut.io_queueSrcArchTag_0 == c.src_d_arch && dut.io_queueSrcArchTag_1 == c.src_r_arch,
            name + ": source arch tag mismatch");
    require(dut.io_queueSrcPhysTag_0 == c.src_d_phys && dut.io_queueSrcPhysTag_1 == c.src_r_phys,
            name + ": source phys tag mismatch");
}

void require_no_fabricated_exec(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const std::string &name) {
    require(!dut.io_execValid, name + ": fabricated execValid before support");
    require(dut.io_execAddr == 0, name + ": fabricated exec address before support");
    require(dut.io_execData == 0, name + ": fabricated exec data before support");
    require(dut.io_execSize == 0, name + ": fabricated exec size before support");
}

void require_future_green_exec(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c) {
    require(dut.io_candidate, std::string(c.name) + ": candidate was false");
    require(dut.io_supportedOpcode, std::string(c.name) + ": supportedOpcode was false");
    require(dut.io_addrSourceMask == 0x2, std::string(c.name) + ": addrSourceMask was not 0b010");
    require(dut.io_addrSourceReady, std::string(c.name) + ": addrSourceReady was false");
    require(!dut.io_blockedBySource, std::string(c.name) + ": blockedBySource was true");
    require(!dut.io_blockedByUnsupported, std::string(c.name) + ": blockedByUnsupported was true");
    require(dut.io_execValid, std::string(c.name) + ": execValid was false");
    require(dut.io_execAddr == expected_pr_address(c), std::string(c.name) + ": OP_HL_SDI_PR address mismatch");
    require(dut.io_execData == 0, std::string(c.name) + ": STA exec data must remain zero");
    require(dut.io_execSize == 8, std::string(c.name) + ": OP_HL_SDI_PR STA size must be eight bytes");
    require(dut.io_execPeId == c.pe, std::string(c.name) + ": PE identity mismatch");
    require(dut.io_execStid == c.thread && dut.io_execTid == c.thread, std::string(c.name) + ": thread identity mismatch");
    require(!dut.io_execStackValid, std::string(c.name) + ": stackValid mutated");
    require(dut.io_execScalarIex, std::string(c.name) + ": scalarIex was not preserved");
    require(dut.io_execSimtLane == 0, std::string(c.name) + ": simt lane mutated");
}

bool first_red_or_future_green(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c) {
    drive_case(dut, c);
    require_common_inputs(dut, c, c.name);
    require(dut.io_candidate, "first-red OP_HL_SDI_PR was not an Addr candidate");
    if (dut.io_supportedOpcode) {
        require_future_green_exec(dut, c);
        return true;
    }
    require(dut.io_addrSourceMask == 0, "current-red OP_HL_SDI_PR source mask was not zero");
    require(dut.io_addrSourceReady, "current-red OP_HL_SDI_PR should be vacuously source-ready with mask zero");
    require(dut.io_blockedByUnsupported, "current-red OP_HL_SDI_PR did not assert blockedByUnsupported");
    require(!dut.io_blockedBySource, "current-red OP_HL_SDI_PR asserted blockedBySource");
    require_no_fabricated_exec(dut, "current-red OP_HL_SDI_PR");
    fail("current OP_HL_SDI_PR 217 is candidate=true but supported=false with no exec row; expected current-red wrapper should invert this nonzero status only for R857");
    return false;
}

void require_stable_future_green(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kStableCycles; ++cycle) {
        eval(dut);
        require_future_green_exec(dut, c);
    }
}

void run_gating_case(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c, const std::string &name,
                     bool enable, bool queue_valid, bool payload_valid, std::uint8_t store_type) {
    drive_case(dut, c, kOpHlSdiPr, store_type, enable, queue_valid, payload_valid);
    require(!dut.io_candidate, name + ": disabled queue still reported candidate");
    require(!dut.io_execValid, name + ": disabled queue still reported execValid");
    require(!dut.io_blockedByUnsupported, name + ": disabled queue reported unsupported block");
}

void run_source_block_case(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c,
                           bool src1_valid, bool src1_ready) {
    drive_case(dut, c, kOpHlSdiPr, kStoreTypeAddr, true, true, true, true, true, src1_valid, src1_ready);
    if (dut.io_supportedOpcode) {
        require(dut.io_candidate, "source block future path lost candidate");
        require(dut.io_addrSourceMask == 0x2, "source block future path lost OP_HL_SDI_PR source mask");
        require(!dut.io_addrSourceReady, "source block future path reported ready with missing selected source");
        require(dut.io_blockedBySource, "source block future path did not assert blockedBySource");
        require(!dut.io_blockedByUnsupported, "source block future path reported unsupported");
        require(!dut.io_execValid, "source block future path emitted exec with missing source");
    } else {
        require(dut.io_blockedByUnsupported, "source block current path lost unsupported first-red");
        require(!dut.io_blockedBySource, "current unsupported path must not report source block before OP_HL_SDI_PR support");
    }
}

void run_srcd_not_ready_case(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &c) {
    drive_case(dut, c, kOpHlSdiPr, kStoreTypeAddr, true, true, true, true, false, true, true);
    if (dut.io_supportedOpcode) {
        require_future_green_exec(dut, c);
    }
}

void run_adjacent_case(VReducedStoreStaAddressExecHlSdiPrProbe &dut, const Case &base, std::uint32_t opcode,
                       std::uint64_t raw, std::int32_t simm, bool expect_supported,
                       std::uint64_t expected_addr, std::uint32_t expected_size,
                       std::uint32_t expected_mask) {
    Case c = base;
    c.name = "adjacent";
    c.opcode = opcode;
    c.raw = raw;
    c.simm = simm;
    drive_case(dut, c, opcode, kStoreTypeAddr, true, true, true, true, true, true, true,
               (expected_mask & 0x4U) != 0, true);
    require(dut.io_candidate, "adjacent case lost candidate");
    require(dut.io_supportedOpcode == expect_supported, "adjacent supportedOpcode mismatch");
    if (expect_supported) {
        require(dut.io_addrSourceMask == expected_mask, "adjacent source mask mismatch");
        require(dut.io_addrSourceReady, "adjacent supported case was source-blocked");
        require(!dut.io_blockedBySource, "adjacent supported case reported source block");
        require(!dut.io_blockedByUnsupported, "adjacent supported case reported unsupported");
        require(dut.io_execValid, "adjacent supported case did not emit exec");
        require(dut.io_execAddr == expected_addr, "adjacent address mismatch");
        require(dut.io_execSize == expected_size, "adjacent size mismatch");
        require(dut.io_execData == 0, "adjacent STA data changed");
    } else {
        require(dut.io_addrSourceMask == 0, "adjacent unsupported source mask was not zero");
        require(dut.io_blockedByUnsupported, "adjacent unsupported case did not block as unsupported");
        require(!dut.io_execValid, "adjacent unsupported case emitted exec");
    }
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedStoreStaAddressExecHlSdiPrProbe dut;

    const std::vector<Case> all = cases();
    require_oracle_self_checks(all);
    const bool first_completed = first_red_or_future_green(dut, all.front());
    if (!first_completed) {
        return 1;
    }

    for (const Case &c : all) {
        drive_case(dut, c);
        require_common_inputs(dut, c, c.name);
        require_stable_future_green(dut, c);
    }

    run_gating_case(dut, all[1], "enable false", false, true, true, kStoreTypeAddr);
    run_gating_case(dut, all[1], "queueValid false", true, false, true, kStoreTypeAddr);
    run_gating_case(dut, all[1], "payload valid false", true, true, false, kStoreTypeAddr);
    run_gating_case(dut, all[1], "storeType All", true, true, true, kStoreTypeAll);
    run_gating_case(dut, all[1], "storeType Data", true, true, true, kStoreTypeData);
    run_source_block_case(dut, all[2], false, true);
    run_source_block_case(dut, all[2], true, false);
    run_srcd_not_ready_case(dut, all[3]);

    run_adjacent_case(dut, all[3], kOpSdi, sdi_raw(3, 4, -4), -4, true,
                      all[3].base + scaled_simm17(-4), 8, 0x2);
    run_adjacent_case(dut, all[3], kOpSb, sb_raw(3, 4, 5), 0, true,
                      all[3].base + 0xfeedfacecafebeefULL, 1, 0x6);
    run_adjacent_case(dut, all[3], kOpHlSdiPo, hl_sdi_raw(kOpHlSdiPo, 3, 4, 5, 7), 7,
                      false, 0, 0, 0);
    run_adjacent_case(dut, all[3], kOpHlSdiUpr, hl_sdi_raw(kOpHlSdiUpr, 17, 18, 19, -9), -9,
                      false, 0, 0, 0);

    std::cout << "lsu-reduced-store-sta-address-exec-hl-sdi-pr-probe: PASS\n";
    return 0;
}
