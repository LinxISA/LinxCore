#include "VReducedScalarAluHlSdiPrProbe.h"
#include "verilated.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr std::uint32_t kOpAddi = 62;
constexpr std::uint32_t kOpCAdd = 564;
constexpr std::uint32_t kOpCAddi = 565;
constexpr std::uint64_t kPcBase = 0x00000000000113b8ULL;
constexpr std::uint8_t kDstArchT = 31;
constexpr std::uint8_t kSrcRArch = 9;
constexpr std::uint8_t kSrcRPhys = 19;
constexpr int kWaitCycles = 10;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
  const char *name;
  std::uint32_t opcode;
  std::uint64_t src_l_data;
  std::uint64_t src_r_data;
  std::uint8_t src_l_arch;
  std::uint8_t src_l_phys;
  std::int8_t simm5;
  std::uint8_t dst_arch;
  std::uint8_t dst_phys;
  std::uint8_t rid;
  bool rid_wrap;
  std::uint8_t bid;
  bool bid_wrap;
  std::uint8_t gid;
  bool gid_wrap;
  std::uint32_t lsid;
  std::uint64_t block_bid;
  std::uint64_t expected;
};

struct RawCAddi {
  std::uint8_t src_l;
  std::uint8_t simm5_raw;
};

struct ExpectedEvent {
  const char *name;
  std::uint16_t raw;
  std::uint8_t src_l;
  std::int8_t simm5;
  std::uint64_t src_l_data;
  std::uint8_t dst_arch;
  std::uint64_t expected;
  bool accepted;
  bool completion;
  bool release;
  bool writeback;
  bool unsupported;
  bool memory_side_effect;
  bool branch_side_effect;
  bool redirect_side_effect;
};

std::string hex64(std::uint64_t value) {
  std::ostringstream os;
  os << "0x" << std::hex << value << std::dec;
  return os.str();
}

[[noreturn]] void fail(const std::string &message) {
  std::cerr << "backend-c-addi-execute-probe: " << message << '\n';
  std::exit(1);
}

void require(bool condition, const std::string &message) {
  if (!condition) {
    fail(message);
  }
}

bool current_red_mode() {
  const char *value = std::getenv("EXPECT_CURRENT_RED");
  return value != nullptr && std::string(value) == "1";
}

std::uint64_t mask_bits(std::uint64_t value, unsigned width) {
  if (width == 64) {
    return value;
  }
  return value & ((1ULL << width) - 1ULL);
}

std::uint64_t sign_extend(std::uint64_t raw, unsigned width) {
  const std::uint64_t sign = 1ULL << (width - 1);
  const std::uint64_t mask = (1ULL << width) - 1ULL;
  const std::uint64_t value = raw & mask;
  return (value & sign) == 0 ? value : value | ~mask;
}

std::uint16_t encode_c_addi(std::uint8_t src_l, std::int8_t simm5) {
  return static_cast<std::uint16_t>(
      0x000cU |
      (static_cast<std::uint16_t>(src_l & 0x1fU) << 6) |
      (static_cast<std::uint16_t>(mask_bits(static_cast<std::uint8_t>(simm5), 5)) << 11));
}

std::uint16_t encode_c_add(std::uint8_t src_l, std::uint8_t src_r) {
  return static_cast<std::uint16_t>(
      0x0008U |
      (static_cast<std::uint16_t>(src_l & 0x1fU) << 6) |
      (static_cast<std::uint16_t>(src_r & 0x1fU) << 11));
}

std::uint32_t encode_addi(std::uint8_t dst, std::uint8_t src, std::uint16_t uimm12) {
  return 0x0015U |
         (static_cast<std::uint32_t>(dst & 0x1fU) << 7) |
         (static_cast<std::uint32_t>(src & 0x1fU) << 15) |
         (static_cast<std::uint32_t>(uimm12 & 0x0fffU) << 20);
}

RawCAddi decode_c_addi(std::uint16_t raw) {
  require((raw & 0x003fU) == 0x000cU, "C.ADDI decode mask/value rejected legal raw");
  return RawCAddi{
      static_cast<std::uint8_t>((raw >> 6) & 0x1fU),
      static_cast<std::uint8_t>((raw >> 11) & 0x1fU)};
}

std::int8_t sext5_to_i8(std::uint8_t raw) {
  return static_cast<std::int8_t>(static_cast<std::uint8_t>(sign_extend(raw, 5)));
}

std::uint64_t c_addi_value(std::uint64_t src_l_data, std::int8_t simm5) {
  const std::uint64_t imm = sign_extend(mask_bits(static_cast<std::uint8_t>(simm5), 5), 5);
  return src_l_data + imm;
}

std::uint64_t c_add_value(std::uint64_t src_l_data, std::uint64_t src_r_data) {
  return src_l_data + src_r_data;
}

std::uint64_t addi_value(std::uint64_t src_l_data, std::uint16_t uimm12) {
  return src_l_data + (uimm12 & 0x0fffU);
}

void check_expected_event(const ExpectedEvent &row) {
  const auto decoded = decode_c_addi(row.raw);
  require(decoded.src_l == row.src_l, std::string(row.name) + ": SrcL raw decode mismatch");
  require(sext5_to_i8(decoded.simm5_raw) == row.simm5,
          std::string(row.name) + ": SIMM5 sign-extension mismatch");
  require(row.dst_arch == kDstArchT,
          std::string(row.name) + ": C.ADDI destination is not fixed T/x31");
  require(row.expected == c_addi_value(row.src_l_data, row.simm5),
          std::string(row.name) + ": expected arithmetic table mismatch");
  require(row.accepted && row.completion && row.release && row.writeback && !row.unsupported,
          std::string(row.name) + ": future-green terminal table is inconsistent");
  require(!row.memory_side_effect && !row.branch_side_effect && !row.redirect_side_effect,
          std::string(row.name) + ": C.ADDI table expects unrelated side effects");
}

void precheck_c_addi_oracles() {
  const std::array<std::pair<std::int8_t, std::uint64_t>, 5> simm_cases = {{
      {0, 0x0000000000000000ULL},
      {15, 0x000000000000000fULL},
      {-1, 0xffffffffffffffffULL},
      {-16, 0xfffffffffffffff0ULL},
      {7, 0x0000000000000007ULL},
  }};
  for (const auto &[simm, expected] : simm_cases) {
    const std::uint16_t raw = encode_c_addi(12, simm);
    require((raw & 0x003fU) == 0x000cU, "C.ADDI raw mask/value is not 0x003f/0x000c");
    const auto decoded = decode_c_addi(raw);
    require(decoded.src_l == 12, "C.ADDI SrcL field did not round-trip");
    require(sext5_to_i8(decoded.simm5_raw) == simm, "C.ADDI SIMM5 field did not round-trip");
    require(sign_extend(decoded.simm5_raw, 5) == expected,
            "C.ADDI sext5 mismatch for simm=" + std::to_string(static_cast<int>(simm)));
  }

  require(c_addi_value(0xffffffffffffffffULL, 1) == 0x0ULL,
          "C.ADDI modulo-64 wrap precheck failed");
  require(c_addi_value(0x10ULL, -16) == 0x0ULL,
          "C.ADDI negative boundary addition precheck failed");
  require(c_add_value(0x10ULL, 0x22ULL) == 0x32ULL,
          "adjacent C.ADD arithmetic precheck failed");
  require(addi_value(0x100ULL, 0x7ffU) == 0x8ffULL,
          "adjacent OP_ADDI arithmetic precheck failed");

  const std::array<ExpectedEvent, 6> table = {{
      {"c_addi_zero", encode_c_addi(1, 0), 1, 0, 0x20ULL, kDstArchT, 0x20ULL,
       true, true, true, true, false, false, false, false},
      {"c_addi_positive_15", encode_c_addi(2, 15), 2, 15, 0x10ULL, kDstArchT, 0x1fULL,
       true, true, true, true, false, false, false, false},
      {"c_addi_negative_one", encode_c_addi(3, -1), 3, -1, 0x0ULL, kDstArchT, 0xffffffffffffffffULL,
       true, true, true, true, false, false, false, false},
      {"c_addi_negative_16", encode_c_addi(4, -16), 4, -16, 0x10ULL, kDstArchT, 0x0ULL,
       true, true, true, true, false, false, false, false},
      {"c_addi_wrap", encode_c_addi(5, 1), 5, 1, 0xffffffffffffffffULL, kDstArchT, 0x0ULL,
       true, true, true, true, false, false, false, false},
      {"c_addi_x31_alias", encode_c_addi(31, -1), 31, -1, 0x40ULL, kDstArchT, 0x3fULL,
       true, true, true, true, false, false, false, false},
  }};
  for (const auto &row : table) {
    check_expected_event(row);
  }

  std::cerr << "backend-c-addi-execute-probe: precheck ok: C.ADDI builder, "
               "raw decode, sext5, modulo arithmetic, fixed T/x31 destination, "
               "adjacent arithmetic, and future-green event table\n";
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

void idle(VReducedScalarAluHlSdiPrProbe &dut) {
  dut.io_inValid = 0;
  dut.io_flushValid = 0;
  dut.io_opcode = 0;
  dut.io_pc = 0;
  dut.io_insnLen = 2;
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
  dut.io_srcDArchTag = 0;
  dut.io_srcDPhysTag = 17;
  dut.io_srcRArchTag = kSrcRArch;
  dut.io_srcRPhysTag = kSrcRPhys;
  dut.io_dstValid = 0;
  dut.io_dstArchTag = 0;
  dut.io_dstPhysTag = 0;
}

void reset(VReducedScalarAluHlSdiPrProbe &dut) {
  dut.reset = 1;
  idle(dut);
  tick(dut);
  tick(dut);
  dut.reset = 0;
  tick(dut);
}

std::uint64_t raw_for_case(const Case &c) {
  if (c.opcode == kOpCAddi) {
    return encode_c_addi(c.src_l_arch, c.simm5);
  }
  if (c.opcode == kOpCAdd) {
    return encode_c_add(c.src_l_arch, kSrcRArch);
  }
  if (c.opcode == kOpAddi) {
    return encode_addi(c.dst_arch, c.src_l_arch, static_cast<std::uint16_t>(mask_bits(c.simm5, 12)));
  }
  fail(std::string(c.name) + ": unknown opcode");
}

std::uint64_t imm_for_case(const Case &c) {
  if (c.opcode == kOpCAddi) {
    return sign_extend(mask_bits(static_cast<std::uint8_t>(c.simm5), 5), 5);
  }
  if (c.opcode == kOpAddi) {
    return mask_bits(static_cast<std::uint8_t>(c.simm5), 12);
  }
  return 0;
}

std::uint64_t expected_for_case(const Case &c) {
  if (c.opcode == kOpCAddi) {
    return c_addi_value(c.src_l_data, c.simm5);
  }
  if (c.opcode == kOpCAdd) {
    return c_add_value(c.src_l_data, c.src_r_data);
  }
  if (c.opcode == kOpAddi) {
    return addi_value(c.src_l_data, static_cast<std::uint16_t>(mask_bits(c.simm5, 12)));
  }
  fail(std::string(c.name) + ": unknown opcode for expected value");
}

void drive_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, std::uint64_t pc) {
  idle(dut);
  dut.io_inValid = 1;
  dut.io_opcode = c.opcode;
  dut.io_pc = pc;
  dut.io_insnLen = c.opcode == kOpCAddi || c.opcode == kOpCAdd ? 2 : 4;
  dut.io_insnRaw = raw_for_case(c);
  dut.io_imm = imm_for_case(c);
  dut.io_srcDData = c.src_l_data;
  dut.io_srcRData = c.src_r_data;
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
  dut.io_srcDArchTag = c.src_l_arch;
  dut.io_srcDPhysTag = c.src_l_phys;
  dut.io_srcRArchTag = kSrcRArch;
  dut.io_srcRPhysTag = kSrcRPhys;
  dut.io_dstValid = 1;
  dut.io_dstArchTag = c.dst_arch;
  dut.io_dstPhysTag = c.dst_phys;
  eval(dut);
}

bool completion_matches(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, std::uint64_t pc) {
  const std::uint64_t expected = expected_for_case(c);
  const bool has_src1 = c.opcode == kOpCAdd;
  const bool gpr_dst = c.dst_arch < 24;
  return dut.io_completeValid && dut.io_completeRobValue == c.rid &&
         dut.io_completeLsId == c.lsid && dut.io_completeDstPhysValid == gpr_dst &&
         (!gpr_dst || dut.io_completeDstPhysTag == c.dst_phys) &&
         dut.io_completeDstData == expected &&
         dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == c.src_l_phys &&
         dut.io_completeSrc1PhysValid == has_src1 &&
         (!has_src1 || dut.io_completeSrc1PhysTag == kSrcRPhys) &&
         dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
         dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
         dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
         dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
         dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
         dut.io_completeRowInsn == raw_for_case(c) &&
         dut.io_completeRowLen == (c.opcode == kOpCAddi || c.opcode == kOpCAdd ? 2 : 4) &&
         dut.io_completeRowSrc0Valid && dut.io_completeRowSrc0Reg == c.src_l_arch &&
         dut.io_completeRowSrc0Data == c.src_l_data &&
         dut.io_completeRowSrc1Valid == has_src1 &&
         (!has_src1 || (dut.io_completeRowSrc1Reg == kSrcRArch &&
                        dut.io_completeRowSrc1Data == c.src_r_data)) &&
         dut.io_completeRowDstValid && dut.io_completeRowDstReg == c.dst_arch &&
         dut.io_completeRowDstData == expected && dut.io_completeRowWbValid &&
         dut.io_completeRowWbReg == c.dst_arch && dut.io_completeRowWbData == expected &&
         !dut.io_completeRowMemValid && !dut.io_branchConditionValid &&
         !dut.io_redirectValid;
}

bool release_matches(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
  return dut.io_releaseValid && dut.io_releaseBidValid &&
         dut.io_releaseBidWrap == c.bid_wrap && dut.io_releaseBidValue == c.bid &&
         dut.io_releaseGidValid && dut.io_releaseGidWrap == c.gid_wrap &&
         dut.io_releaseGidValue == c.gid && dut.io_releaseRidValid &&
         dut.io_releaseRidWrap == c.rid_wrap && dut.io_releaseRidValue == c.rid &&
         dut.io_releaseStid == 0;
}

void require_no_unrelated_side_effects(VReducedScalarAluHlSdiPrProbe &dut, const std::string &name) {
  require(!dut.io_loadWaitHold && !dut.io_loadLookupValid && !dut.io_loadLiqEligible &&
              !dut.io_liqReleaseValid && !dut.io_branchConditionValid && !dut.io_redirectValid,
          name + ": unexpected load/branch/redirect side effect");
}

bool has_unrelated_side_effects(VReducedScalarAluHlSdiPrProbe &dut) {
  return dut.io_loadWaitHold || dut.io_loadLookupValid || dut.io_loadLiqEligible ||
         dut.io_liqReleaseValid || dut.io_branchConditionValid || dut.io_redirectValid;
}

void require_no_terminal_or_side_effect(VReducedScalarAluHlSdiPrProbe &dut,
                                        const Case &c,
                                        const char *phase) {
  if (dut.io_completeValid || dut.io_releaseValid || dut.io_unsupported ||
      has_unrelated_side_effects(dut)) {
    fail(std::string(c.name) + ": unexpected terminal or side-effect pulse " + phase);
  }
}

void drain_for_duplicates(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
  bool busy_cleared = !dut.io_busy;
  for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
    idle(dut);
    tick(dut);
    eval(dut);
    require_no_terminal_or_side_effect(dut, c, "during post-terminal drain");
    busy_cleared = busy_cleared || !dut.io_busy;
  }
  require(busy_cleared && !dut.io_busy, std::string(c.name) + ": busy did not clear after drain");
}

bool wait_for_completion(VReducedScalarAluHlSdiPrProbe &dut,
                         const Case &c,
                         std::uint64_t pc) {
  for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
    eval(dut);
    if (dut.io_unsupported) {
      fail(std::string(c.name) + ": unexpected unsupported pulse in future-green case");
    }
    if (dut.io_completeValid) {
      require(completion_matches(dut, c, pc),
              std::string(c.name) + ": completion/writeback identity mismatch");
      require(release_matches(dut, c), std::string(c.name) + ": release identity mismatch");
      require_no_unrelated_side_effects(dut, c.name);
      drain_for_duplicates(dut, c);
      return true;
    }
    if (dut.io_releaseValid) {
      fail(std::string(c.name) + ": release without completion in future-green case");
    }
    require_no_unrelated_side_effects(dut, c.name);
    tick(dut);
    idle(dut);
  }
  return false;
}

bool wait_for_current_red_unsupported(VReducedScalarAluHlSdiPrProbe &dut,
                                      const Case &c) {
  for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
    eval(dut);
    if (dut.io_completeValid) {
      fail(std::string(c.name) + ": current-red path saw completion before unsupported");
    }
    if (dut.io_unsupported) {
      require(dut.io_unsupportedOpcode == c.opcode,
              std::string(c.name) + ": unsupported reported wrong opcode");
      require(release_matches(dut, c), std::string(c.name) + ": unsupported release identity mismatch");
      require_no_unrelated_side_effects(dut, c.name);
      drain_for_duplicates(dut, c);
      return true;
    }
    require_no_terminal_or_side_effect(dut, c, "before unsupported");
    tick(dut);
    idle(dut);
  }
  return false;
}

bool run_case(VReducedScalarAluHlSdiPrProbe &dut,
              const Case &c,
              unsigned index,
              bool expect_current_red = false) {
  reset(dut);
  require(c.expected == expected_for_case(c),
          std::string(c.name) + ": fixture expected value disagrees with independent oracle");
  if (c.opcode == kOpCAddi) {
    require(c.dst_arch == kDstArchT, std::string(c.name) + ": C.ADDI fixture did not use fixed T/x31");
  }
  const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 8ULL;
  drive_case(dut, c, pc);
  require(dut.io_inReady, std::string(c.name) + ": input was not ready before accept");
  require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");
  require_no_terminal_or_side_effect(dut, c, "in accept cycle");

  tick(dut);
  idle(dut);
  eval(dut);
  require(!dut.io_inReady || !dut.io_busy,
          std::string(c.name) + ": busy/inReady acceptance backpressure invariant failed");

  if (expect_current_red) {
    if (wait_for_current_red_unsupported(dut, c)) {
      require(!dut.io_busy, std::string(c.name) + ": busy did not clear after unsupported");
      return false;
    }
    fail(std::string(c.name) + ": current-red path produced neither unsupported nor completion");
  }

  if (wait_for_completion(dut, c, pc)) {
    return true;
  }

  if (dut.io_busy) {
    drain_for_duplicates(dut, c);
  }
  fail(std::string(c.name) + ": accepted transaction produced no completion");
}

std::vector<Case> cases() {
  return {
      {"adjacent_c_add_preserved", kOpCAdd, 0x10ULL, 0x22ULL, 11, 31, 0, kDstArchT, 40, 2, false, 3, false, 4, true, 0x113b8001U, 0xcadd100000000001ULL, 0x32ULL},
      {"c_addi_zero_current_red", kOpCAddi, 0x20ULL, 0ULL, 6, 32, 0, kDstArchT, 41, 3, false, 4, true, 5, false, 0x113b8002U, 0xcadd100000000002ULL, 0x20ULL},
      {"c_addi_positive_15", kOpCAddi, 0x10ULL, 0ULL, 7, 33, 15, kDstArchT, 42, 4, true, 5, false, 6, true, 0x113b8003U, 0xcadd100000000003ULL, 0x1fULL},
      {"c_addi_negative_one", kOpCAddi, 0x0ULL, 0ULL, 8, 34, -1, kDstArchT, 43, 5, false, 6, true, 7, false, 0x113b8004U, 0xcadd100000000004ULL, 0xffffffffffffffffULL},
      {"c_addi_negative_16", kOpCAddi, 0x10ULL, 0ULL, 10, 35, -16, kDstArchT, 44, 6, true, 7, false, 8, true, 0x113b8005U, 0xcadd100000000005ULL, 0x0ULL},
      {"c_addi_overflow_wrap", kOpCAddi, 0xffffffffffffffffULL, 0ULL, 12, 36, 1, kDstArchT, 45, 7, false, 8, true, 9, false, 0x113b8006U, 0xcadd100000000006ULL, 0x0ULL},
      {"c_addi_src_l_x31_alias", kOpCAddi, 0x40ULL, 0ULL, 31, 37, -1, kDstArchT, 46, 8, true, 9, false, 10, true, 0x113b8007U, 0xcadd100000000007ULL, 0x3fULL},
      {"adjacent_addi_preserved", kOpAddi, 0x100ULL, 0ULL, 13, 38, 15, 14, 47, 9, false, 10, true, 11, false, 0x113b8008U, 0xcadd100000000008ULL, 0x10fULL},
  };
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  precheck_c_addi_oracles();

  VReducedScalarAluHlSdiPrProbe dut;
  const auto all_cases = cases();

  require(run_case(dut, all_cases.front(), 0),
          "adjacent C.ADD did not complete before current-red check");

  const bool red_inverted = current_red_mode();
  const bool first_c_addi_completed = run_case(dut, all_cases[1], 1, red_inverted);
  if (!first_c_addi_completed) {
    if (red_inverted) {
      std::cerr << "backend-c-addi-execute-probe: current-red ok: legal OP_C_ADDI "
                   "accepted and produced unsupported without completion\n";
      std::cout << "backend-c-addi-execute-probe: PASS current-red\n";
      return 0;
    }
    fail("current RTL accepted legal OP_C_ADDI but reported unsupported without completion");
  }
  if (red_inverted) {
    fail("EXPECT_CURRENT_RED=1 set, but OP_C_ADDI completed instead of reproducing current red");
  }

  for (unsigned idx = 2; idx < all_cases.size(); ++idx) {
    require(run_case(dut, all_cases[idx], idx),
            std::string(all_cases[idx].name) + ": future-green case did not complete");
  }

  std::cout << "backend-c-addi-execute-probe: PASS\n";
  return 0;
}
