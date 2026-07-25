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

constexpr std::uint32_t kOpHlLui = 164;
constexpr std::uint32_t kOpLui = 268;
constexpr std::uint64_t kPcBase = 0x0000000000010fa4ULL;
constexpr std::uint8_t kSrcDArch = 8;
constexpr std::uint8_t kSrcRArch = 9;
constexpr std::uint8_t kSrcDPhys = 18;
constexpr std::uint8_t kSrcRPhys = 19;
constexpr int kWaitCycles = 10;
constexpr int kDuplicateDrainCycles = 8;

struct Case {
  const char *name;
  std::uint32_t opcode;
  std::uint32_t imm_raw;
  unsigned imm_width;
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

struct ExpectedEvent {
  const char *name;
  std::uint32_t opcode;
  std::uint32_t imm_raw;
  unsigned imm_width;
  std::uint8_t dst;
  std::uint64_t raw;
  std::uint64_t expected;
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
  std::cerr << "backend-lui-execute-probe: " << message << '\n';
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

std::uint64_t bits(std::uint64_t value, unsigned width) {
  if (width == 64) {
    return value;
  }
  return value & ((1ULL << width) - 1ULL);
}

std::uint64_t sign_extend(std::uint64_t raw, unsigned width) {
  const std::uint64_t sign = 1ULL << (width - 1);
  const std::uint64_t mask = (1ULL << width) - 1ULL;
  const std::uint64_t value = raw & mask;
  if ((value & sign) == 0) {
    return value;
  }
  return value | ~mask;
}

std::uint64_t lui_value(std::uint32_t imm20) {
  return (sign_extend(imm20, 20) << 12) & 0xffffffffffffffffULL;
}

std::uint64_t hl_lui_value(std::uint32_t imm32) {
  return sign_extend(imm32, 32);
}

std::uint32_t encode_lui(std::uint8_t dst, std::uint32_t imm20) {
  return 0x17U | (static_cast<std::uint32_t>(dst & 0x1fU) << 7) |
         ((imm20 & 0xfffffU) << 12);
}

std::uint64_t encode_hl_lui(std::uint8_t dst, std::uint32_t imm32) {
  return 0x17000eULL | (static_cast<std::uint64_t>(dst & 0x1fU) << 23) |
         (static_cast<std::uint64_t>(imm32 & 0xfffU) << 4) |
         (static_cast<std::uint64_t>(imm32 >> 12) << 28);
}

std::uint8_t decode_lui_dst(std::uint32_t raw) {
  return static_cast<std::uint8_t>((raw >> 7) & 0x1fU);
}

std::uint32_t decode_lui_imm20(std::uint32_t raw) {
  return (raw >> 12) & 0xfffffU;
}

std::uint8_t decode_hl_lui_dst(std::uint64_t raw) {
  return static_cast<std::uint8_t>((raw >> 23) & 0x1fU);
}

std::uint32_t decode_hl_lui_imm32(std::uint64_t raw) {
  return static_cast<std::uint32_t>(((raw >> 4) & 0xfffULL) |
                                    (((raw >> 28) & 0xfffffULL) << 12));
}

std::uint64_t expected_value(std::uint32_t opcode, std::uint32_t imm_raw, unsigned imm_width) {
  if (opcode == kOpLui) {
    require(imm_width == 20, "OP_LUI event must use imm20");
    return lui_value(imm_raw);
  }
  if (opcode == kOpHlLui) {
    require(imm_width == 32, "OP_HL_LUI event must use imm32");
    return hl_lui_value(imm_raw);
  }
  fail("unknown opcode in expected-value oracle");
}

std::uint64_t expected_raw(std::uint32_t opcode, std::uint8_t dst, std::uint32_t imm_raw) {
  if (opcode == kOpLui) {
    return encode_lui(dst, imm_raw);
  }
  if (opcode == kOpHlLui) {
    return encode_hl_lui(dst, imm_raw);
  }
  fail("unknown opcode in raw-encoding oracle");
}

void check_expected_event(const ExpectedEvent &row) {
  require(row.opcode == kOpLui || row.opcode == kOpHlLui,
          std::string(row.name) + ": unexpected opcode in event table");
  require(row.dst < 24, std::string(row.name) + ": destination must classify as scalar GPR");
  require(row.completion && row.release && row.writeback && !row.unsupported,
          std::string(row.name) + ": future-green terminal table is inconsistent");
  require(!row.memory_side_effect && !row.branch_side_effect && !row.redirect_side_effect,
          std::string(row.name) + ": LUI table expects unrelated side effects");
  require(row.raw == expected_raw(row.opcode, row.dst, row.imm_raw),
          std::string(row.name) + ": raw encoding disagrees with independent fixture");
  require(row.expected == expected_value(row.opcode, row.imm_raw, row.imm_width),
          std::string(row.name) + ": expected value disagrees with independent semantic fixture");
  if (row.opcode == kOpLui) {
    require(decode_lui_dst(static_cast<std::uint32_t>(row.raw)) == row.dst,
            std::string(row.name) + ": LUI destination decode mismatch");
    require(decode_lui_imm20(static_cast<std::uint32_t>(row.raw)) == bits(row.imm_raw, 20),
            std::string(row.name) + ": LUI imm20 decode mismatch");
  } else {
    require(decode_hl_lui_dst(row.raw) == row.dst,
            std::string(row.name) + ": HL.LUI destination decode mismatch");
    require(decode_hl_lui_imm32(row.raw) == bits(row.imm_raw, 32),
            std::string(row.name) + ": HL.LUI imm32 decode mismatch");
  }
}

void precheck_lui_oracles() {
  const std::array<std::pair<std::uint32_t, std::uint64_t>, 5> imm_cases = {{
      {0x00000U, 0x0000000000000000ULL},
      {0x00001U, 0x0000000000001000ULL},
      {0x7ffffU, 0x000000007ffff000ULL},
      {0x80000U, 0xffffffff80000000ULL},
      {0xfffffU, 0xfffffffffffff000ULL},
  }};

  for (const auto &[imm20, expected] : imm_cases) {
    const std::uint32_t raw = encode_lui(13, imm20);
    require((raw & 0x7fU) == 0x17U, "LUI raw opcode field is not 0x17");
    require(decode_lui_dst(raw) == 13, "LUI destination field did not round-trip");
    require(decode_lui_imm20(raw) == imm20, "LUI imm20 field did not round-trip");
    require(lui_value(imm20) == expected,
            "LUI sext20<<12 mismatch for imm20=" + hex64(imm20));
  }

  const std::uint64_t hl_raw = encode_hl_lui(14, 0x80000001U);
  require((hl_raw & 0x0000007f000fULL) == 0x00000017000eULL,
          "HL.LUI raw mask/value precheck failed");
  require(decode_hl_lui_dst(hl_raw) == 14, "HL.LUI destination field did not round-trip");
  require(decode_hl_lui_imm32(hl_raw) == 0x80000001U,
          "HL.LUI imm32 field did not round-trip");
  require(hl_lui_value(0x80000001U) == 0xffffffff80000001ULL,
          "HL.LUI sext32 semantic precheck failed");

  const std::array<ExpectedEvent, 5> table = {{
      {"lui_zero", kOpLui, 0x00000U, 20, 5, encode_lui(5, 0x00000U), 0x0ULL,
       true, true, true, false, false, false, false},
      {"lui_positive", kOpLui, 0x12345U, 20, 6, encode_lui(6, 0x12345U), 0x12345000ULL,
       true, true, true, false, false, false, false},
      {"lui_sign_bit", kOpLui, 0x80000U, 20, 7, encode_lui(7, 0x80000U), 0xffffffff80000000ULL,
       true, true, true, false, false, false, false},
      {"lui_negative_max", kOpLui, 0xfffffU, 20, 8, encode_lui(8, 0xfffffU), 0xfffffffffffff000ULL,
       true, true, true, false, false, false, false},
      {"hl_lui_adjacent", kOpHlLui, 0x80000001U, 32, 9, encode_hl_lui(9, 0x80000001U),
       0xffffffff80000001ULL, true, true, true, false, false, false, false},
  }};
  for (const auto &row : table) {
    check_expected_event(row);
  }

  std::cerr << "backend-lui-execute-probe: precheck ok: LUI/HL.LUI raw decode, "
               "destination extraction, sext materialization, boundaries, and "
               "future-green event table\n";
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
  dut.io_insnLen = 4;
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
  dut.io_srcDArchTag = kSrcDArch;
  dut.io_srcDPhysTag = kSrcDPhys;
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
  if (c.opcode == kOpLui) {
    return encode_lui(c.dst_arch, c.imm_raw);
  }
  if (c.opcode == kOpHlLui) {
    return encode_hl_lui(c.dst_arch, c.imm_raw);
  }
  fail(std::string(c.name) + ": unknown opcode");
}

std::uint64_t imm_for_case(const Case &c) {
  if (c.opcode == kOpLui) {
    require(c.imm_width == 20, std::string(c.name) + ": LUI fixture must use imm20");
    return lui_value(c.imm_raw);
  }
  if (c.opcode == kOpHlLui) {
    require(c.imm_width == 32, std::string(c.name) + ": HL.LUI fixture must use imm32");
    return hl_lui_value(c.imm_raw);
  }
  fail(std::string(c.name) + ": unknown opcode for immediate");
}

void drive_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, std::uint64_t pc) {
  idle(dut);
  dut.io_inValid = 1;
  dut.io_opcode = c.opcode;
  dut.io_pc = pc;
  dut.io_insnLen = c.opcode == kOpHlLui ? 6 : 4;
  dut.io_insnRaw = raw_for_case(c);
  dut.io_imm = imm_for_case(c);
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
  dut.io_dstValid = 1;
  dut.io_dstArchTag = c.dst_arch;
  dut.io_dstPhysTag = c.dst_phys;
  eval(dut);
}

bool completion_matches(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, std::uint64_t pc) {
  return dut.io_completeValid && dut.io_completeRobValue == c.rid &&
         dut.io_completeLsId == c.lsid && dut.io_completeDstPhysValid &&
         dut.io_completeDstPhysTag == c.dst_phys && dut.io_completeDstData == c.expected &&
         dut.io_completeSrc0PhysValid && dut.io_completeSrc0PhysTag == kSrcDPhys &&
         dut.io_completeSrc1PhysValid && dut.io_completeSrc1PhysTag == kSrcRPhys &&
         dut.io_completeRowValid && dut.io_completeRowBid == c.bid &&
         dut.io_completeRowGid == c.gid && dut.io_completeRowRid == c.rid &&
         dut.io_completeRowRobValid && dut.io_completeRowRobWrap == c.rid_wrap &&
         dut.io_completeRowRobValue == c.rid && dut.io_completeRowBlockBidValid &&
         dut.io_completeRowBlockBid == c.block_bid && dut.io_completeRowPc == pc &&
         dut.io_completeRowInsn == raw_for_case(c) &&
         dut.io_completeRowLen == (c.opcode == kOpHlLui ? 6 : 4) &&
         dut.io_completeRowSrc0Valid && dut.io_completeRowSrc0Reg == kSrcDArch &&
         dut.io_completeRowSrc0Data == 0 &&
         dut.io_completeRowSrc1Valid && dut.io_completeRowSrc1Reg == kSrcRArch &&
         dut.io_completeRowSrc1Data == 0 &&
         dut.io_completeRowDstValid && dut.io_completeRowDstReg == c.dst_arch &&
         dut.io_completeRowDstData == c.expected && dut.io_completeRowWbValid &&
         dut.io_completeRowWbReg == c.dst_arch && dut.io_completeRowWbData == c.expected &&
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

void drain_for_duplicates(VReducedScalarAluHlSdiPrProbe &dut, const Case &c) {
  for (int cycle = 0; cycle < kDuplicateDrainCycles; ++cycle) {
    idle(dut);
    tick(dut);
    eval(dut);
    if (dut.io_completeValid || dut.io_releaseValid || dut.io_unsupported) {
      fail(std::string(c.name) + ": duplicate terminal pulse before reset");
    }
  }
  require(!dut.io_busy, std::string(c.name) + ": busy did not clear after drain");
}

bool wait_for_completion_or_unsupported(VReducedScalarAluHlSdiPrProbe &dut,
                                        const Case &c,
                                        std::uint64_t pc,
                                        bool *unsupported_seen) {
  *unsupported_seen = false;
  for (int cycle = 0; cycle < kWaitCycles; ++cycle) {
    eval(dut);
    if (dut.io_completeValid) {
      require(completion_matches(dut, c, pc),
              std::string(c.name) + ": completion/writeback identity mismatch");
      require(release_matches(dut, c), std::string(c.name) + ": release identity mismatch");
      require(!dut.io_unsupported,
              std::string(c.name) + ": completion overlapped unsupported");
      require(!dut.io_loadWaitHold && !dut.io_loadLookupValid && !dut.io_loadLiqEligible &&
                  !dut.io_liqReleaseValid,
              std::string(c.name) + ": completion had unrelated load side effect");
      drain_for_duplicates(dut, c);
      return true;
    }
    if (dut.io_unsupported) {
      require(dut.io_unsupportedOpcode == c.opcode,
              std::string(c.name) + ": unsupported reported wrong opcode");
      require(!dut.io_completeValid,
              std::string(c.name) + ": unsupported overlapped a completion");
      require(release_matches(dut, c), std::string(c.name) + ": unsupported release identity mismatch");
      require(!dut.io_loadWaitHold && !dut.io_loadLookupValid &&
                  !dut.io_loadLiqEligible && !dut.io_liqReleaseValid &&
                  !dut.io_branchConditionValid && !dut.io_redirectValid,
              std::string(c.name) + ": unsupported had unrelated side effect");
      *unsupported_seen = true;
    }
    tick(dut);
    idle(dut);
  }
  return false;
}

bool run_case(VReducedScalarAluHlSdiPrProbe &dut, const Case &c, unsigned index) {
  reset(dut);
  require(c.expected == imm_for_case(c),
          std::string(c.name) + ": fixture expected value disagrees with independent semantic oracle");
  const std::uint64_t pc = kPcBase + static_cast<std::uint64_t>(index) * 8ULL;
  drive_case(dut, c, pc);
  require(dut.io_inReady, std::string(c.name) + ": input was not ready before accept");
  require(dut.io_accepted, std::string(c.name) + ": transaction was not accepted");

  tick(dut);
  idle(dut);
  eval(dut);
  require(!dut.io_inReady || !dut.io_busy,
          std::string(c.name) + ": busy/inReady backpressure invariant failed");

  bool unsupported_seen = false;
  const bool completed = wait_for_completion_or_unsupported(dut, c, pc, &unsupported_seen);
  if (completed) {
    return true;
  }
  if (unsupported_seen) {
    require(!dut.io_busy, std::string(c.name) + ": busy did not clear after unsupported");
    drain_for_duplicates(dut, c);
    return false;
  }
  fail(std::string(c.name) + ": accepted transaction produced neither completion nor unsupported");
}

std::vector<Case> cases() {
  return {
      {"adjacent_hl_lui_preserved", kOpHlLui, 0x80000001U, 32, 9, 41, 2, false, 3, false, 4, true, 0x10fa4001U, 0x8790000000000001ULL, 0xffffffff80000001ULL},
      {"lui_zero_current_red", kOpLui, 0x00000U, 20, 5, 42, 3, false, 4, true, 5, false, 0x10fa4002U, 0x8790000000000002ULL, 0x0000000000000000ULL},
      {"lui_positive", kOpLui, 0x12345U, 20, 6, 43, 4, true, 5, false, 6, true, 0x10fa4003U, 0x8790000000000003ULL, 0x0000000012345000ULL},
      {"lui_sign_bit_boundary", kOpLui, 0x80000U, 20, 7, 44, 5, false, 6, true, 7, false, 0x10fa4004U, 0x8790000000000004ULL, 0xffffffff80000000ULL},
      {"lui_negative_max", kOpLui, 0xfffffU, 20, 8, 45, 6, true, 7, false, 8, true, 0x10fa4005U, 0x8790000000000005ULL, 0xfffffffffffff000ULL},
      {"lui_max_positive", kOpLui, 0x7ffffU, 20, 10, 46, 7, false, 8, true, 9, false, 0x10fa4006U, 0x8790000000000006ULL, 0x000000007ffff000ULL},
      {"lui_high_gpr_destination_kind", kOpLui, 0x00001U, 20, 23, 47, 8, true, 9, false, 10, true, 0x10fa4007U, 0x8790000000000007ULL, 0x0000000000001000ULL},
  };
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  precheck_lui_oracles();

  VReducedScalarAluHlSdiPrProbe dut;
  const auto all_cases = cases();

  require(run_case(dut, all_cases.front(), 0),
          "adjacent HL.LUI did not complete before current-red check");

  const bool red_inverted = current_red_mode();
  const bool first_lui_completed = run_case(dut, all_cases[1], 1);
  if (!first_lui_completed) {
    if (red_inverted) {
      std::cerr << "backend-lui-execute-probe: current-red ok: legal OP_LUI "
                   "accepted and produced unsupported without completion\n";
      std::cout << "backend-lui-execute-probe: PASS current-red\n";
      return 0;
    }
    fail("current RTL accepted legal OP_LUI but reported unsupported without completion");
  }
  if (red_inverted) {
    fail("EXPECT_CURRENT_RED=1 set, but OP_LUI completed instead of reproducing current red");
  }

  for (unsigned idx = 2; idx < all_cases.size(); ++idx) {
    require(run_case(dut, all_cases[idx], idx),
            std::string(all_cases[idx].name) + ": future-green case did not complete");
  }

  std::cout << "backend-lui-execute-probe: PASS\n";
  return 0;
}
