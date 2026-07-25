#include <cstdint>
#include <cstdlib>
#include <iostream>

#include "VCompressedWordLoadExecuteProbe.h"
#include "verilated.h"

namespace {

constexpr std::uint32_t kOpCLdi = 570;
constexpr std::uint32_t kOpCLwi = 571;
constexpr std::uint64_t kPc = 0x40001020ULL;
constexpr std::uint64_t kInsn = 0x0000000000001234ULL;
constexpr std::uint64_t kBase = 0x40010080ULL;
constexpr std::uint64_t kCLwiImm = 0xfffffffffffffffdULL;
constexpr std::uint64_t kCLwiAddr = 0x40010074ULL;
constexpr std::uint64_t kCLdiImm = 0xffffffffffffffe8ULL;
constexpr std::uint64_t kCLdiAddr = 0x40010068ULL;
constexpr std::uint64_t kStaleAcceptedData = 0x0000000077773333ULL;
constexpr std::uint64_t kZeroLoad = 0x0000000000000000ULL;
constexpr std::uint64_t kCLwiLoad = 0x0000000080010020ULL;
constexpr std::uint64_t kCLwiResult = 0xffffffff80010020ULL;
constexpr std::uint64_t kLsid = 0x10203040ULL;
constexpr std::uint64_t kBlockBid = 0xfeedface12345678ULL;
constexpr std::uint8_t kBid = 5;
constexpr std::uint8_t kGid = 6;
constexpr std::uint8_t kRid = 7;
constexpr std::uint8_t kDstArch = 31;
constexpr std::uint8_t kDstKindT = 2;
constexpr std::uint8_t kDstRel = 0;
constexpr std::uint8_t kDstPhys = 45;
constexpr std::uint8_t kSrcArch = 24;
constexpr std::uint8_t kSrcPhys = 12;
constexpr int kDuplicateCompletionDrainCycles = 8;

void fail(const char *message) {
    std::cerr << "backend-compressed-load-lookup-timing-probe: " << message << '\n';
    std::exit(1);
}

void require(bool condition, const char *message) {
    if (!condition) {
        fail(message);
    }
}

void eval(VCompressedWordLoadExecuteProbe &dut) {
    dut.eval();
}

void tick(VCompressedWordLoadExecuteProbe &dut) {
    dut.clock = 0;
    eval(dut);
    dut.clock = 1;
    eval(dut);
}

void idle(VCompressedWordLoadExecuteProbe &dut) {
    dut.io_inValid = 0;
    dut.io_opcode = 0;
    dut.io_pc = 0;
    dut.io_insnRaw = 0;
    dut.io_insnLen = 4;
    dut.io_imm = 0;
    dut.io_src0Data = 0;
    dut.io_src1Data = 0;
    dut.io_loadLookupData = 0;
    dut.io_loadLookupWaitBlocked = 0;
    dut.io_loadLiqEnable = 0;
    dut.io_loadLiqAccepted = 0;
    dut.io_bidValid = 1;
    dut.io_bidWrap = 0;
    dut.io_bidValue = kBid;
    dut.io_gidValid = 1;
    dut.io_gidWrap = 1;
    dut.io_gidValue = kGid;
    dut.io_ridValid = 1;
    dut.io_ridWrap = 0;
    dut.io_ridValue = kRid;
    dut.io_lsid = kLsid;
    dut.io_blockBid = kBlockBid;
    dut.io_dstArchTag = kDstArch;
    dut.io_dstPhysTag = kDstPhys;
    dut.io_srcArchTag = kSrcArch;
    dut.io_srcPhysTag = kSrcPhys;
}

void reset(VCompressedWordLoadExecuteProbe &dut) {
    dut.reset = 1;
    idle(dut);
    tick(dut);
    tick(dut);
    dut.reset = 0;
    tick(dut);
}

void require_identity_lookup(VCompressedWordLoadExecuteProbe &dut) {
    require(dut.io_loadLookupPc == kPc, "load lookup PC did not preserve input PC");
    require(dut.io_loadLookupBidValid && !dut.io_loadLookupBidWrap && dut.io_loadLookupBidValue == kBid,
            "load lookup BID identity mismatch");
    require(dut.io_loadLookupGidValid && dut.io_loadLookupGidWrap && dut.io_loadLookupGidValue == kGid,
            "load lookup GID identity mismatch");
    require(dut.io_loadLookupRidValid && !dut.io_loadLookupRidWrap && dut.io_loadLookupRidValue == kRid,
            "load lookup RID identity mismatch");
    require(dut.io_loadLookupLsId == kLsid, "load lookup LSID mismatch");
    require(dut.io_loadLookupDstValid, "load lookup destination was not valid");
    require(dut.io_loadLookupDstKind == kDstKindT, "load lookup destination kind was not local T");
    require(dut.io_loadLookupDstArchTag == kDstArch, "load lookup architectural destination mismatch");
    require(dut.io_loadLookupDstRelTag == kDstRel, "load lookup local T relative tag mismatch");
    require(dut.io_loadLookupDstPhysTag == kDstPhys, "load lookup physical destination mismatch");
}

void accept_load_with_real_lookup_window(VCompressedWordLoadExecuteProbe &dut, std::uint32_t opcode,
                                         std::uint64_t imm, std::uint64_t actual_load_data,
                                         bool wait_blocked = false) {
    idle(dut);
    dut.io_inValid = 1;
    dut.io_opcode = opcode;
    dut.io_pc = kPc;
    dut.io_insnRaw = kInsn;
    dut.io_insnLen = 2;
    dut.io_imm = imm;
    dut.io_src0Data = kBase;
    dut.io_loadLookupData = kStaleAcceptedData;
    dut.io_loadLookupWaitBlocked = 0;
    dut.io_loadLiqEnable = 0;
    eval(dut);
    require(dut.io_inReady, "input was not ready for ordinary renamed load");
    require(dut.io_accepted, "renamed load was not accepted by the execute input");
    tick(dut);

    idle(dut);
    dut.io_loadLookupData = actual_load_data;
    dut.io_loadLookupWaitBlocked = wait_blocked ? 1 : 0;
    eval(dut);
}

void advance_lookup_to_w1(VCompressedWordLoadExecuteProbe &dut, std::uint64_t actual_load_data) {
    dut.io_loadLookupData = actual_load_data;
    tick(dut);
}

void wait_for_completion(VCompressedWordLoadExecuteProbe &dut, const char *context) {
    for (int cycle = 0; cycle < 8; ++cycle) {
        idle(dut);
        eval(dut);
        if (dut.io_completeValid) {
            return;
        }
        tick(dut);
    }
    std::cerr << "backend-compressed-load-lookup-timing-probe: " << context << '\n';
    std::exit(1);
}

void require_common_completion(VCompressedWordLoadExecuteProbe &dut, std::uint64_t result,
                               std::uint64_t addr, std::uint8_t size) {
    require(dut.io_completeRobValue == kRid, "completion ROB value mismatch");
    require(dut.io_completeLsId == kLsid, "completion LSID mismatch");
    require(!dut.io_completeDstPhysValid, "local T completion unexpectedly used scalar physical writeback");
    require(dut.io_completeDstData == result, "completion result mismatch");
    require(dut.io_completeRowValid, "completion row was not valid");
    require(dut.io_completeRowBid == kBid, "completion row BID mismatch");
    require(dut.io_completeRowGid == kGid, "completion row GID mismatch");
    require(dut.io_completeRowRid == kRid, "completion row RID mismatch");
    require(dut.io_completeRowRobValid && !dut.io_completeRowRobWrap && dut.io_completeRowRobValue == kRid,
            "completion row ROB mismatch");
    require(dut.io_completeRowPc == kPc, "completion row PC mismatch");
    require(dut.io_completeRowDstValid && dut.io_completeRowDstReg == kDstArch,
            "completion row architectural destination mismatch");
    require(dut.io_completeRowDstData == result, "completion row destination data mismatch");
    require(dut.io_completeRowWbValid && dut.io_completeRowWbReg == kDstArch,
            "completion row writeback destination mismatch");
    require(dut.io_completeRowWbData == result, "completion row writeback data mismatch");
    require(dut.io_completeRowMemValid, "completion row memory evidence was not valid");
    require(!dut.io_completeRowMemIsStore, "completion row memory evidence was marked store");
    require(dut.io_completeRowMemAddr == addr, "completion row memory address mismatch");
    require(dut.io_completeRowMemRdata == result, "completion row memory data mismatch");
    require(dut.io_completeRowMemSize == size, "completion row memory size mismatch");
}

bool completion_matches_expected(VCompressedWordLoadExecuteProbe &dut, std::uint64_t result,
                                 std::uint64_t addr, std::uint8_t size) {
    return dut.io_completeRobValue == kRid && dut.io_completeLsId == kLsid &&
           !dut.io_completeDstPhysValid && dut.io_completeDstData == result &&
           dut.io_completeRowValid && dut.io_completeRowBid == kBid &&
           dut.io_completeRowGid == kGid && dut.io_completeRowRid == kRid &&
           dut.io_completeRowRobValid && !dut.io_completeRowRobWrap &&
           dut.io_completeRowRobValue == kRid && dut.io_completeRowPc == kPc &&
           dut.io_completeRowDstValid && dut.io_completeRowDstReg == kDstArch &&
           dut.io_completeRowDstData == result && dut.io_completeRowWbValid &&
           dut.io_completeRowWbReg == kDstArch && dut.io_completeRowWbData == result &&
           dut.io_completeRowMemValid && !dut.io_completeRowMemIsStore &&
           dut.io_completeRowMemAddr == addr && dut.io_completeRowMemRdata == result &&
           dut.io_completeRowMemSize == size;
}

void require_exactly_one_completion(VCompressedWordLoadExecuteProbe &dut, std::uint64_t result,
                                    std::uint64_t addr, std::uint8_t size) {
    require(dut.io_completeValid, "completion drain started without the first completion pulse");
    int completion_count = 1;
    for (int cycle = 0; cycle < kDuplicateCompletionDrainCycles; ++cycle) {
        tick(dut);
        idle(dut);
        eval(dut);
        if (dut.io_completeValid) {
            ++completion_count;
            if (!completion_matches_expected(dut, result, addr, size)) {
                fail("duplicate completion row changed before reset");
            }
            fail("duplicate completion pulse before reset");
        }
    }
    require(completion_count == 1, "completion count was not exactly one before reset");
}

void prove_zero_c_lwi_uses_lookup_window(VCompressedWordLoadExecuteProbe &dut) {
    reset(dut);
    accept_load_with_real_lookup_window(dut, kOpCLwi, kCLwiImm, kZeroLoad);
    require(dut.io_loadLookupValid, "accepted zero C.LWI did not assert loadLookupValid");
    require(dut.io_loadLookupAddr == kCLwiAddr, "zero C.LWI load address did not scale signed simm5 by four");
    require(dut.io_loadLookupSize == 4, "zero C.LWI load size was not four bytes");
    require(dut.io_loadLookupReturnSignExtend, "zero C.LWI did not request signed word load return");
    require_identity_lookup(dut);
    advance_lookup_to_w1(dut, kZeroLoad);
    wait_for_completion(dut, "zero C.LWI did not complete after lookup");
    if (dut.io_completeDstData != kZeroLoad || dut.io_completeRowWbData != kZeroLoad ||
        dut.io_completeRowMemRdata != kZeroLoad) {
        fail("first-red: legitimate zero C.LWI lookup completed with stale acceptance data");
    }
    require_common_completion(dut, kZeroLoad, kCLwiAddr, 4);
    require_exactly_one_completion(dut, kZeroLoad, kCLwiAddr, 4);
}

void prove_zero_c_ldi_uses_lookup_window(VCompressedWordLoadExecuteProbe &dut) {
    reset(dut);
    accept_load_with_real_lookup_window(dut, kOpCLdi, kCLdiImm, kZeroLoad);
    require(dut.io_loadLookupValid, "zero C.LDI did not assert loadLookupValid");
    require(dut.io_loadLookupAddr == kCLdiAddr, "zero C.LDI did not use pre-scaled immediate directly");
    require(dut.io_loadLookupSize == 8, "zero C.LDI load size was not eight bytes");
    require(!dut.io_loadLookupReturnSignExtend, "zero C.LDI unexpectedly requested sign extension");
    require_identity_lookup(dut);
    advance_lookup_to_w1(dut, kZeroLoad);
    wait_for_completion(dut, "zero C.LDI did not complete after lookup");
    require_common_completion(dut, kZeroLoad, kCLdiAddr, 8);
    require_exactly_one_completion(dut, kZeroLoad, kCLdiAddr, 8);
}

void prove_nonzero_c_lwi_uses_lookup_window(VCompressedWordLoadExecuteProbe &dut) {
    reset(dut);
    accept_load_with_real_lookup_window(dut, kOpCLwi, kCLwiImm, kCLwiLoad);
    require(dut.io_loadLookupValid, "nonzero C.LWI did not assert loadLookupValid");
    require(dut.io_loadLookupAddr == kCLwiAddr, "nonzero C.LWI load address did not scale signed simm5 by four");
    require(dut.io_loadLookupSize == 4, "nonzero C.LWI load size was not four bytes");
    require(dut.io_loadLookupReturnSignExtend, "nonzero C.LWI did not request signed word load return");
    require_identity_lookup(dut);
    advance_lookup_to_w1(dut, kCLwiLoad);
    wait_for_completion(dut, "nonzero C.LWI did not complete after lookup");
    require_common_completion(dut, kCLwiResult, kCLwiAddr, 4);
    require_exactly_one_completion(dut, kCLwiResult, kCLwiAddr, 4);
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VCompressedWordLoadExecuteProbe dut;

    prove_zero_c_lwi_uses_lookup_window(dut);
    prove_zero_c_ldi_uses_lookup_window(dut);
    prove_nonzero_c_lwi_uses_lookup_window(dut);

    std::cout << "backend-compressed-load-lookup-timing-probe: PASS\n";
    return 0;
}
