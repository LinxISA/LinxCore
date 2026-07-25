#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "VReducedStoreStaAddressExecSbProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpSb = 386;
constexpr std::uint32_t kOpSbi = 387;
constexpr std::uint32_t kOpSd = 389;
constexpr std::uint32_t kOpHlSbPcr = 442;
constexpr std::uint32_t kOpCSwi = 583;
constexpr std::uint32_t kOpUnsupported = 0xfff;
constexpr int kStableCycles = 5;
constexpr std::uint8_t kStoreTypeAll = 0;
constexpr std::uint8_t kStoreTypeAddr = 1;
constexpr std::uint8_t kStoreTypeData = 2;

struct Case {
    const char *name;
    std::uint64_t base;
    std::uint64_t offset;
    std::uint8_t src_r_type;
    std::uint64_t pc;
    std::uint64_t insn;
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
    std::cerr << "lsu-reduced-store-sta-address-exec-sb-probe: " << message << '\n';
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

std::uint64_t sext32(std::uint64_t value) {
    const std::uint32_t low = static_cast<std::uint32_t>(value & 0xffffffffULL);
    return static_cast<std::uint64_t>(static_cast<std::int64_t>(static_cast<std::int32_t>(low)));
}

std::uint64_t zext32(std::uint64_t value) {
    return value & 0xffffffffULL;
}

std::uint64_t apply_src_r_type(std::uint64_t value, std::uint8_t src_r_type) {
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
    return c.base + apply_src_r_type(c.offset, c.src_r_type);
}

std::uint64_t insn_for_case(const Case &c) {
    return (static_cast<std::uint64_t>(c.src_r_type & 0x3U) << 25) |
           0x49ULL |
           (static_cast<std::uint64_t>(c.insn) & ~0x06000049ULL);
}

void eval(VReducedStoreStaAddressExecSbProbe &dut) {
    dut.eval();
}

void idle(VReducedStoreStaAddressExecSbProbe &dut) {
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

void drive_case(VReducedStoreStaAddressExecSbProbe &dut, const Case &c, std::uint32_t opcode = kOpSb,
                std::uint8_t store_type = kStoreTypeAddr, bool enable = true, bool queue_valid = true,
                bool payload_valid = true, bool src1_valid = true, bool src1_ready = true,
                bool src2_valid = true, bool src2_ready = true, bool src0_valid = false,
                bool src0_ready = true, std::uint64_t src0_data = 0) {
    idle(dut);
    dut.io_enable = enable ? 1 : 0;
    dut.io_queueValid = queue_valid ? 1 : 0;
    dut.io_payloadValid = payload_valid ? 1 : 0;
    dut.io_opcode = opcode;
    dut.io_pc = c.pc;
    dut.io_insnRaw = insn_for_case(c);
    dut.io_imm = 0x14;
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
    dut.io_srcData_0 = src0_data;
    dut.io_srcValid_1 = src1_valid ? 1 : 0;
    dut.io_srcReady_1 = src1_ready ? 1 : 0;
    dut.io_srcData_1 = c.base;
    dut.io_srcValid_2 = src2_valid ? 1 : 0;
    dut.io_srcReady_2 = src2_ready ? 1 : 0;
    dut.io_srcData_2 = c.offset;
    dut.io_srcArchTag_0 = 1;
    dut.io_srcArchTag_1 = 2;
    dut.io_srcArchTag_2 = 3;
    dut.io_srcPhysTag_0 = 31;
    dut.io_srcPhysTag_1 = 32;
    dut.io_srcPhysTag_2 = 33;
    eval(dut);
}

std::vector<Case> cases() {
    return {
        {"sb_zero_addr_zero_data_sext32_first_red", 0x0000000000000000ULL, 0x0000000000000000ULL, 0, 0x1298eULL, 0x49ULL, 1, 2, false, 3, true, 4, false, 5, 0x81300001U},
        {"sb_nonzero_base_zero_offset_raw", 0x0000000000004080ULL, 0x0000000000000000ULL, 3, 0x12992ULL, 0x6000049ULL, 2, 3, true, 6, false, 7, true, 8, 0x81300002U},
        {"sb_sign_extend_negative_offset", 0x0000000000005000ULL, 0x00000000fffffff8ULL, 0, 0x12996ULL, 0x49ULL, 3, 4, false, 9, false, 10, true, 11, 0x81300003U},
        {"sb_zero_extend_upper_ignored", 0x0000000000006000ULL, 0xffffffff00000024ULL, 1, 0x1299aULL, 0x2000049ULL, 4, 5, true, 12, true, 13, false, 14, 0x81300004U},
        {"sb_negated_offset_wrap", 0xfffffffffffffff0ULL, 0x0000000000000020ULL, 2, 0x1299eULL, 0x4000049ULL, 5, 6, false, 15, false, 16, true, 17, 0x81300005U},
    };
}

void require_common_inputs(VReducedStoreStaAddressExecSbProbe &dut, const Case &c, const std::string &name) {
    require(dut.io_queueOpcode == kOpSb, name + ": queue opcode was not OP_SB 386");
    require(dut.io_queueInsnRaw == insn_for_case(c), name + ": raw instruction was not preserved");
    require(dut.io_queueStoreType == kStoreTypeAddr, name + ": store type was not Addr");
    require(!dut.io_queueSrcValid_0, name + ": STA source 0 data owner should remain invalid");
    require(dut.io_queueSrcValid_1 && dut.io_queueSrcReady_1, name + ": SrcL was not valid-ready");
    require(dut.io_queueSrcValid_2 && dut.io_queueSrcReady_2, name + ": SrcR was not valid-ready");
    require(dut.io_queueSrcData_0 == 0, name + ": source 0 data was not zero");
    require(dut.io_queueSrcData_1 == c.base, name + ": SrcL data mismatch");
    require(dut.io_queueSrcData_2 == c.offset, name + ": SrcR data mismatch");
    require(dut.io_queueSrcArchTag_1 == 2 && dut.io_queueSrcArchTag_2 == 3, name + ": source arch tag mismatch");
    require(dut.io_queueSrcPhysTag_1 == 32 && dut.io_queueSrcPhysTag_2 == 33, name + ": source phys tag mismatch");
}

void require_no_fabricated_exec(VReducedStoreStaAddressExecSbProbe &dut, const std::string &name) {
    require(!dut.io_execValid, name + ": fabricated execValid before support");
    require(dut.io_execAddr == 0, name + ": fabricated exec address before support");
    require(dut.io_execData == 0, name + ": fabricated exec data before support");
    require(dut.io_execSize == 0, name + ": fabricated exec size before support");
}

void require_future_green_exec(VReducedStoreStaAddressExecSbProbe &dut, const Case &c) {
    require(dut.io_candidate, std::string(c.name) + ": candidate was false");
    require(dut.io_supportedOpcode, std::string(c.name) + ": supportedOpcode was false");
    require(dut.io_addrSourceMask == 0x6, std::string(c.name) + ": addrSourceMask was not 0b110");
    require(dut.io_addrSourceReady, std::string(c.name) + ": addrSourceReady was false");
    require(!dut.io_blockedBySource, std::string(c.name) + ": blockedBySource was true");
    require(!dut.io_blockedByUnsupported, std::string(c.name) + ": blockedByUnsupported was true");
    require(dut.io_execValid, std::string(c.name) + ": execValid was false");
    require(dut.io_execAddr == expected_address(c), std::string(c.name) + ": OP_SB address mismatch");
    require(dut.io_execData == 0, std::string(c.name) + ": STA exec data must remain zero");
    require(dut.io_execSize == 1, std::string(c.name) + ": OP_SB STA size must be one byte");
    require(dut.io_execPeId == c.pe, std::string(c.name) + ": PE identity mismatch");
    require(dut.io_execStid == c.thread && dut.io_execTid == c.thread, std::string(c.name) + ": thread identity mismatch");
    require(!dut.io_execStackValid, std::string(c.name) + ": stackValid mutated");
    require(dut.io_execScalarIex, std::string(c.name) + ": scalarIex was not preserved");
    require(dut.io_execSimtLane == 0, std::string(c.name) + ": simt lane mutated");
}

bool first_red_or_future_green(VReducedStoreStaAddressExecSbProbe &dut, const Case &c) {
    drive_case(dut, c);
    require_common_inputs(dut, c, c.name);
    require(dut.io_candidate, "first-red OP_SB was not an Addr candidate");
    if (dut.io_supportedOpcode) {
        require_future_green_exec(dut, c);
        return true;
    }
    require(dut.io_addrSourceMask == 0, "current-red OP_SB source mask was not zero");
    require(dut.io_addrSourceReady, "current-red OP_SB should be vacuously source-ready with mask zero");
    require(dut.io_blockedByUnsupported, "current-red OP_SB did not assert blockedByUnsupported");
    require(!dut.io_blockedBySource, "current-red OP_SB asserted blockedBySource");
    require_no_fabricated_exec(dut, "current-red OP_SB");
    if (!explicit_current_inversion()) {
        fail("current OP_SB 386 is candidate=true but supported=false with no exec row; rerun with EXPECT_CURRENT_UNSUPPORTED=1 only for current-red inversion");
    }
    std::cerr << "lsu-reduced-store-sta-address-exec-sb-probe: first-red OP_SB 386 candidate true but supported false, source mask zero, exec invalid, blockedByUnsupported true\n";
    return false;
}

void require_stable_future_green(VReducedStoreStaAddressExecSbProbe &dut, const Case &c) {
    for (int cycle = 0; cycle < kStableCycles; ++cycle) {
        eval(dut);
        require_future_green_exec(dut, c);
    }
}

void run_gating_case(VReducedStoreStaAddressExecSbProbe &dut, const Case &c, const std::string &name,
                     bool enable, bool queue_valid, bool payload_valid, std::uint8_t store_type) {
    drive_case(dut, c, kOpSb, store_type, enable, queue_valid, payload_valid);
    require(!dut.io_candidate, name + ": disabled queue still reported candidate");
    require(!dut.io_execValid, name + ": disabled queue still reported execValid");
    require(!dut.io_blockedByUnsupported, name + ": disabled queue reported unsupported block");
}

void run_source_block_case(VReducedStoreStaAddressExecSbProbe &dut, const Case &c, bool src1_valid,
                           bool src1_ready, bool src2_valid, bool src2_ready) {
    drive_case(dut, c, kOpSb, kStoreTypeAddr, true, true, true, src1_valid, src1_ready, src2_valid, src2_ready);
    if (dut.io_supportedOpcode) {
        require(dut.io_candidate, "source block future path lost candidate");
        require(dut.io_addrSourceMask == 0x6, "source block future path lost OP_SB source mask");
        require(!dut.io_addrSourceReady, "source block future path reported ready with a missing selected source");
        require(dut.io_blockedBySource, "source block future path did not assert blockedBySource");
        require(!dut.io_execValid, "source block future path emitted exec with missing source");
    } else {
        require(dut.io_blockedByUnsupported, "source block current path lost unsupported first-red");
        require(!dut.io_blockedBySource, "current unsupported path must not report source block before OP_SB support");
    }
}

void run_adjacent_case(VReducedStoreStaAddressExecSbProbe &dut, const Case &c, std::uint32_t opcode,
                       bool expect_supported, std::uint64_t expected_addr, std::uint32_t expected_size,
                       std::uint32_t expected_mask, bool src0_valid = false, bool src0_ready = true,
                       std::uint64_t src0_data = 0) {
    drive_case(dut, c, opcode, kStoreTypeAddr, true, true, true, true, true, true, true, src0_valid,
               src0_ready, src0_data);
    require(dut.io_candidate, "adjacent case lost candidate");
    require(dut.io_supportedOpcode == expect_supported, "adjacent supportedOpcode mismatch");
    if (expect_supported) {
        require(dut.io_addrSourceMask == expected_mask, "adjacent source mask mismatch");
        require(dut.io_addrSourceReady, "adjacent supported case was source-blocked");
        require(!dut.io_blockedBySource, "adjacent supported case reported source block");
        require(dut.io_execValid, "adjacent supported case did not emit exec");
        require(dut.io_execAddr == expected_addr, "adjacent address mismatch");
        require(dut.io_execSize == expected_size, "adjacent size mismatch");
        require(dut.io_execData == 0, "adjacent STA data changed");
    } else {
        require(dut.io_blockedByUnsupported, "adjacent unsupported case did not block as unsupported");
        require(!dut.io_execValid, "adjacent unsupported case emitted exec");
    }
}

void run_compressed_source_block_case(VReducedStoreStaAddressExecSbProbe &dut, const Case &c,
                                      const std::string &name, bool src0_valid, bool src0_ready) {
    drive_case(dut, c, kOpCSwi, kStoreTypeAddr, true, true, true, true, true, true, true, src0_valid,
               src0_ready, c.base);
    require(dut.io_candidate, name + ": compressed store lost candidate");
    require(dut.io_supportedOpcode, name + ": compressed store lost support");
    require(dut.io_addrSourceMask == 0x1, name + ": compressed store source mask was not 0b001");
    require(!dut.io_addrSourceReady, name + ": compressed store reported ready with missing source0");
    require(dut.io_blockedBySource, name + ": compressed store did not assert source block");
    require(!dut.io_blockedByUnsupported, name + ": compressed store incorrectly reported unsupported");
    require(!dut.io_execValid, name + ": compressed store emitted exec with missing source0");
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VReducedStoreStaAddressExecSbProbe dut;

    const std::vector<Case> all = cases();
    const bool first_completed = first_red_or_future_green(dut, all.front());
    if (!first_completed) {
        return 0;
    }

    for (const Case &c : all) {
        drive_case(dut, c);
        require_common_inputs(dut, c, c.name);
        require_stable_future_green(dut, c);
    }

    run_gating_case(dut, all[1], "enable false", false, true, true, kStoreTypeAddr);
    run_gating_case(dut, all[1], "queueValid false", true, false, true, kStoreTypeAddr);
    run_gating_case(dut, all[1], "payload valid false", true, true, false, kStoreTypeAddr);
    run_gating_case(dut, all[1], "storeType Data", true, true, true, kStoreTypeData);
    run_source_block_case(dut, all[2], false, true, true, true);
    run_source_block_case(dut, all[2], true, false, true, true);
    run_source_block_case(dut, all[2], true, true, false, true);
    run_source_block_case(dut, all[2], true, true, true, false);
    run_adjacent_case(dut, all[3], kOpSbi, true, all[3].base + 0x14ULL, 1, 0x2);
    run_adjacent_case(dut, all[3], kOpSd, true, all[3].base + (all[3].offset << 3), 8, 0x6);
    run_adjacent_case(dut, all[3], kOpHlSbPcr, true, all[3].pc + 0x14ULL, 1, 0x0);
    run_adjacent_case(dut, all[3], kOpCSwi, true, all[3].base + (0x14ULL << 2), 4, 0x1, true,
                      true, all[3].base);
    run_compressed_source_block_case(dut, all[3], "compressed source0 invalid", false, true);
    run_compressed_source_block_case(dut, all[3], "compressed source0 not ready", true, false);
    run_adjacent_case(dut, all[3], kOpUnsupported, false, 0, 0, 0);

    std::cout << "lsu-reduced-store-sta-address-exec-sb-probe: PASS\n";
    return 0;
}
