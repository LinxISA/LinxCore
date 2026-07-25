#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "VFrontendHlSdiPrOperandDecodeProbe.h"
#include "verilated.h"

namespace {

constexpr uint64_t Mask64 = ~uint64_t{0};
constexpr int OpHlSdi = 213;
constexpr int OpHlSdiPo = 216;
constexpr int OpHlSdiPr = 217;
constexpr int OpHlSdiU = 218;
constexpr int OpHlSdiUpo = 219;
constexpr int OpHlSdiUpr = 220;
constexpr int OpHlSwiPo = 255;
constexpr int OpSb = 386;
constexpr int OpFentry = 429;
constexpr int CatStore = 11;
constexpr int DispatchLsu = 4;
constexpr int OperandNone = 0;
constexpr int OperandReg = 1;
constexpr int ImmNone = 10;
constexpr int ImmFentryUimmHi = 0;
constexpr int ImmSimm17 = 17;
constexpr int ImmSimm22 = 20;
constexpr int RegInvalid = 63;

struct Expected {
    std::string name;
    uint64_t insn;
    int len = 6;
    int meta_len = 6;
    bool active = true;
    bool meta_valid = true;
    int opcode = OpHlSdiPr;
    int category = CatStore;
    int dispatch = DispatchLsu;
    int rd_kind = OperandReg;
    int rs1_kind = OperandReg;
    int rs2_kind = OperandReg;
    int imm_kind = ImmSimm17;
    bool is_store = true;
    bool src0_valid = true;
    int src0_arch = 0;
    bool src1_valid = true;
    int src1_arch = 0;
    bool src2_valid = false;
    int src2_arch = RegInvalid;
    bool dst_valid = true;
    int dst_arch = 0;
    bool imm_valid = true;
    uint64_t imm = 0;
};

uint64_t bits(uint64_t value, int width) {
    return value & ((uint64_t{1} << width) - 1);
}

uint64_t encode_sdi_wb(uint64_t match, int dst, int srcd, int srcr, int32_t simm17) {
    const uint64_t raw = bits(static_cast<uint64_t>(simm17), 17);
    uint64_t insn = match;
    insn |= bits(dst, 5) << 11;
    insn |= bits(raw >> 12, 5) << 6;
    insn |= bits(raw >> 7, 5) << 23;
    insn |= bits(srcd, 5) << 31;
    insn |= bits(srcr, 5) << 36;
    insn |= bits(raw, 7) << 41;
    return insn;
}

uint64_t encode_sdi_nowb(uint64_t match, int srcd, int srcr, int32_t simm22) {
    const uint64_t raw = bits(static_cast<uint64_t>(simm22), 22);
    uint64_t insn = match;
    insn |= bits(raw >> 12, 10) << 6;
    insn |= bits(raw >> 7, 5) << 23;
    insn |= bits(srcd, 5) << 31;
    insn |= bits(srcr, 5) << 36;
    insn |= bits(raw, 7) << 41;
    return insn;
}

uint64_t sext_to_u64(uint64_t raw, int width) {
    const uint64_t sign = uint64_t{1} << (width - 1);
    if ((raw & sign) == 0) {
        return raw;
    }
    return raw | (Mask64 << width);
}

void eval_case(VFrontendHlSdiPrOperandDecodeProbe &dut, const Expected &exp,
               std::vector<std::string> &failures) {
    dut.io_active = exp.active;
    dut.io_insnRaw = exp.insn;
    dut.io_lenBytes = exp.len;
    dut.eval();

    auto check = [&](bool condition, const std::string &field, uint64_t got, uint64_t want) {
        if (!condition) {
            std::ostringstream out;
            out << exp.name << ": " << field << " got=0x" << std::hex << got
                << " want=0x" << want;
            failures.push_back(out.str());
        }
    };

    check(dut.io_metaValid == exp.meta_valid, "metaValid", dut.io_metaValid, exp.meta_valid);
    check(dut.io_opcode == static_cast<uint32_t>(exp.opcode), "opcode", dut.io_opcode, exp.opcode);
    check(dut.io_metaLenBytes == static_cast<uint32_t>(exp.meta_len), "metaLenBytes", dut.io_metaLenBytes, exp.meta_len);
    check(dut.io_majorCategory == static_cast<uint32_t>(exp.category), "majorCategory", dut.io_majorCategory, exp.category);
    check(dut.io_dispatchTarget == static_cast<uint32_t>(exp.dispatch), "dispatchTarget", dut.io_dispatchTarget, exp.dispatch);
    check(dut.io_rdKind == static_cast<uint32_t>(exp.rd_kind), "rdKind", dut.io_rdKind, exp.rd_kind);
    check(dut.io_rs1Kind == static_cast<uint32_t>(exp.rs1_kind), "rs1Kind", dut.io_rs1Kind, exp.rs1_kind);
    check(dut.io_rs2Kind == static_cast<uint32_t>(exp.rs2_kind), "rs2Kind", dut.io_rs2Kind, exp.rs2_kind);
    check(dut.io_immKind == static_cast<uint32_t>(exp.imm_kind), "immKind", dut.io_immKind, exp.imm_kind);
    check(dut.io_isStore == exp.is_store, "isStore", dut.io_isStore, exp.is_store);

    check(dut.io_src_0_valid == exp.src0_valid, "src0.valid", dut.io_src_0_valid, exp.src0_valid);
    check(dut.io_src_0_archTag == static_cast<uint32_t>(exp.src0_arch), "src0.archTag", dut.io_src_0_archTag, exp.src0_arch);
    check(dut.io_src_1_valid == exp.src1_valid, "src1.valid", dut.io_src_1_valid, exp.src1_valid);
    check(dut.io_src_1_archTag == static_cast<uint32_t>(exp.src1_arch), "src1.archTag", dut.io_src_1_archTag, exp.src1_arch);
    check(dut.io_src_2_valid == exp.src2_valid, "src2.valid", dut.io_src_2_valid, exp.src2_valid);
    check(dut.io_src_2_archTag == static_cast<uint32_t>(exp.src2_arch), "src2.archTag", dut.io_src_2_archTag, exp.src2_arch);
    check(dut.io_dst_valid == exp.dst_valid, "dst.valid", dut.io_dst_valid, exp.dst_valid);
    check(dut.io_dst_archTag == static_cast<uint32_t>(exp.dst_arch), "dst.archTag", dut.io_dst_archTag, exp.dst_arch);
    check(dut.io_immValid == exp.imm_valid, "immValid", dut.io_immValid, exp.imm_valid);
    check(dut.io_imm == exp.imm, "imm", dut.io_imm, exp.imm);
}

Expected sdi_pr_case(std::string name, uint64_t insn, int dst, int srcd, int srcr, int32_t simm17) {
    Expected exp;
    exp.name = std::move(name);
    exp.insn = insn;
    exp.src0_arch = srcd;
    exp.src1_arch = srcr;
    exp.dst_arch = dst;
    exp.imm = sext_to_u64(bits(static_cast<uint64_t>(simm17), 17), 17);
    return exp;
}

Expected sdi_wb_case(std::string name, uint64_t match, int opcode, int dst, int srcd, int srcr, int32_t simm17) {
    Expected exp = sdi_pr_case(std::move(name), encode_sdi_wb(match, dst, srcd, srcr, simm17), dst, srcd, srcr, simm17);
    exp.opcode = opcode;
    return exp;
}

Expected sdi_nowb_case(std::string name, uint64_t match, int opcode, int srcd, int srcr, int32_t simm22) {
    Expected exp;
    exp.name = std::move(name);
    exp.insn = encode_sdi_nowb(match, srcd, srcr, simm22);
    exp.opcode = opcode;
    exp.rd_kind = OperandNone;
    exp.src0_arch = srcd;
    exp.src1_arch = srcr;
    exp.dst_valid = false;
    exp.dst_arch = RegInvalid;
    exp.imm_kind = ImmSimm22;
    exp.imm = sext_to_u64(bits(static_cast<uint64_t>(simm22), 22), 22);
    return exp;
}

Expected inactive_case(std::string name, uint64_t insn, int len, bool active) {
    Expected exp;
    exp.name = std::move(name);
    exp.insn = insn;
    exp.len = len;
    exp.meta_len = len == 6 ? 6 : 0;
    exp.active = active;
    exp.meta_valid = len == 6;
    exp.opcode = len == 6 ? OpHlSdiPr : 0;
    exp.category = len == 6 ? CatStore : 0;
    exp.dispatch = len == 6 ? DispatchLsu : 0;
    exp.rd_kind = len == 6 ? OperandReg : 0;
    exp.rs1_kind = len == 6 ? OperandReg : 0;
    exp.rs2_kind = len == 6 ? OperandReg : 0;
    exp.imm_kind = len == 6 ? ImmSimm17 : 0;
    exp.is_store = len == 6;
    exp.src0_valid = false;
    exp.src0_arch = RegInvalid;
    exp.src1_valid = false;
    exp.src1_arch = RegInvalid;
    exp.dst_valid = false;
    exp.dst_arch = RegInvalid;
    exp.imm_valid = false;
    return exp;
}

Expected sb_case() {
    Expected exp;
    exp.name = "unrelated OP_SB keeps legacy three-source store shape";
    exp.insn = 0x00000049ULL | (3ULL << 27) | (4ULL << 15) | (5ULL << 20);
    exp.len = 4;
    exp.meta_len = 4;
    exp.opcode = OpSb;
    exp.imm_kind = ImmNone;
    exp.rd_kind = OperandNone;
    exp.src0_arch = 3;
    exp.src1_arch = 4;
    exp.src2_valid = true;
    exp.src2_arch = 5;
    exp.dst_valid = false;
    exp.dst_arch = RegInvalid;
    exp.imm_valid = false;
    return exp;
}

Expected fentry_case() {
    Expected exp;
    exp.name = "unrelated FENTRY keeps sp writeback and macro immediate";
    exp.insn = 0x90a50041ULL;
    exp.len = 4;
    exp.meta_len = 4;
    exp.opcode = OpFentry;
    exp.category = 9;
    exp.dispatch = 5;
    exp.rd_kind = OperandNone;
    exp.rs1_kind = OperandNone;
    exp.rs2_kind = OperandNone;
    exp.imm_kind = ImmFentryUimmHi;
    exp.is_store = false;
    exp.src0_arch = 10;
    exp.src1_valid = false;
    exp.src1_arch = RegInvalid;
    exp.dst_arch = 1;
    exp.imm = 576;
    return exp;
}

} // namespace

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    VFrontendHlSdiPrOperandDecodeProbe dut;
    std::vector<Expected> cases;

    cases.push_back(sdi_pr_case("real bytes ee 0f d9 bf 15 fc decode HL.SDI.PR",
                                0xfc15bfd90feeULL, 1, 11, 1, -2));
    cases.push_back(sdi_wb_case("HL.SDI.PR zero SIMM17 uses RegDst1/SrcD/SrcR", 0x3059002eULL, OpHlSdiPr, 2, 3, 4, 0));
    cases.push_back(sdi_wb_case("HL.SDI.PR positive SIMM17 remains raw", 0x3059002eULL, OpHlSdiPr, 5, 6, 7, 1234));
    cases.push_back(sdi_wb_case("HL.SDI.PR negative SIMM17 remains raw", 0x3059002eULL, OpHlSdiPr, 8, 9, 10, -2));
    cases.push_back(sdi_wb_case("HL.SDI.PR minimum SIMM17 remains raw", 0x3059002eULL, OpHlSdiPr, 11, 12, 13, -65536));
    cases.push_back(sdi_wb_case("HL.SDI.PR maximum SIMM17 remains raw", 0x3059002eULL, OpHlSdiPr, 14, 15, 16, 65535));
    cases.push_back(sdi_wb_case("HL.SDI.PO decodes post-index writeback", 0x3059003eULL, OpHlSdiPo, 17, 18, 19, -17));
    cases.push_back(sdi_wb_case("HL.SDI.UPO decodes unsigned post-index writeback", 0x7059003eULL, OpHlSdiUpo, 20, 21, 22, 33));
    cases.push_back(sdi_wb_case("HL.SDI.UPR decodes unsigned pre-index writeback", 0x7059002eULL, OpHlSdiUpr, 23, 24, 25, -34));
    auto dhrystone_swi_po = sdi_wb_case(
        "Dhrystone HL.SWI.PO uses prefix RegDst1", 0x2059003eULL,
        OpHlSwiPo, 22, 7, 22, 1);
    dhrystone_swi_po.category = 0;
    dhrystone_swi_po.dispatch = 2;
    dhrystone_swi_po.is_store = false;
    cases.push_back(dhrystone_swi_po);
    cases.push_back(sdi_nowb_case("HL.SDI has no writeback and signed SIMM22", 0x3059000eULL, OpHlSdi, 26, 27, -2));
    cases.push_back(sdi_nowb_case("HL.SDI.U has no writeback and signed SIMM22", 0x7059000eULL, OpHlSdiU, 28, 29, 123456));
    cases.push_back(inactive_case("inactive suppresses operands and immediate", encode_sdi_wb(0x3059002eULL, 2, 3, 4, -2), 6, false));
    cases.push_back(inactive_case("wrong length suppresses decode and operands", encode_sdi_wb(0x3059002eULL, 2, 3, 4, -2), 4, true));
    cases.push_back(sb_case());
    cases.push_back(fentry_case());

    std::vector<std::string> failures;
    for (const auto &test_case : cases) {
        eval_case(dut, test_case, failures);
    }

    if (!failures.empty()) {
        std::cerr << "frontend-hl-sdi-pr-operand-decode-probe: " << failures.size()
                  << " mismatch(es)\n";
        for (const auto &failure : failures) {
            std::cerr << "  " << failure << '\n';
        }
        return 1;
    }

    std::cout << "frontend-hl-sdi-pr-operand-decode-probe: PASS\n";
    return 0;
}
