#include "VLinxCoreFrontendFetchRfAluTraceTop.h"
#include "verilated.h"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <map>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr std::uint16_t kOpHlSdiPr = 217;
constexpr std::uint16_t kOpFentry = 429;
constexpr std::uint16_t kOpCMovi = 572;
constexpr std::uint64_t kBasePc = 0x1000;
constexpr std::uint64_t kSpSeed = 0x38850;

std::uint64_t g_cycle = 0;

std::uint64_t bits(std::int64_t value, unsigned width) {
  if (width == 64) {
    return static_cast<std::uint64_t>(value);
  }
  return static_cast<std::uint64_t>(value) & ((1ULL << width) - 1ULL);
}

std::uint64_t encode_sdi_wb(std::uint32_t match, unsigned dst, unsigned srcd, unsigned srcr,
                            std::int32_t simm17) {
  const std::uint64_t raw = bits(static_cast<std::uint64_t>(simm17), 17);
  std::uint64_t insn = match;
  insn |= bits(dst, 5) << 11;
  insn |= bits(raw >> 12, 5) << 6;
  insn |= bits(raw >> 7, 5) << 23;
  insn |= bits(srcd, 5) << 31;
  insn |= bits(srcr, 5) << 36;
  insn |= bits(raw, 7) << 41;
  return insn;
}

std::uint16_t encode_c_movi(unsigned dst, std::int32_t simm5) {
  return static_cast<std::uint16_t>(0x16U | (bits(simm5, 5) << 6) |
                                    (bits(dst, 5) << 11));
}

std::string hex64(std::uint64_t value) {
  std::ostringstream os;
  os << "0x" << std::hex << value << std::dec;
  return os.str();
}

struct DecodedSdiWb {
  unsigned dst = 0;
  unsigned srcd = 0;
  unsigned srcr = 0;
  std::int32_t simm17 = 0;
};

struct UopId {
  bool rid_valid = false;
  bool rid_wrap = false;
  unsigned rid_value = 0;
  bool bid_valid = false;
  bool bid_wrap = false;
  unsigned bid_value = 0;
  unsigned stid = 0;
};

std::int32_t sign_extend_17(std::uint32_t raw) {
  if ((raw & (1U << 16)) == 0) {
    return static_cast<std::int32_t>(raw);
  }
  return static_cast<std::int32_t>(raw | 0xfffe0000U);
}

DecodedSdiWb decode_sdi_wb_fields(std::uint64_t insn) {
  const std::uint32_t imm_hi5 = static_cast<std::uint32_t>((insn >> 6) & 0x1fU);
  const std::uint32_t imm_mid5 = static_cast<std::uint32_t>((insn >> 23) & 0x1fU);
  const std::uint32_t imm_lo7 = static_cast<std::uint32_t>((insn >> 41) & 0x7fU);
  const std::uint32_t imm17 = (imm_hi5 << 12) | (imm_mid5 << 7) | imm_lo7;
  return DecodedSdiWb{static_cast<unsigned>((insn >> 11) & 0x1fU),
                      static_cast<unsigned>((insn >> 31) & 0x1fU),
                      static_cast<unsigned>((insn >> 36) & 0x1fU),
                      sign_extend_17(imm17)};
}

bool precheck_sdi_encoder() {
  const std::uint64_t dhrystone_raw = encode_sdi_wb(0x3059002eU, 1, 11, 1, -2);
  if (dhrystone_raw != 0xfc15bfd90feeULL) {
    std::cerr << "backend-implicit-sp-order-probe: precheck failed: HL.SDI.PR builder "
              << "produced " << hex64(dhrystone_raw) << " want=0xfc15bfd90fee\n";
    return false;
  }
  const auto decoded = decode_sdi_wb_fields(dhrystone_raw);
  if (decoded.dst != 1 || decoded.srcd != 11 || decoded.srcr != 1 ||
      decoded.simm17 != -2) {
    std::cerr << "backend-implicit-sp-order-probe: precheck failed: decoded fields"
              << " dst=" << decoded.dst << " srcd=" << decoded.srcd
              << " srcr=" << decoded.srcr << " simm17=" << decoded.simm17 << "\n";
    return false;
  }
  const std::uint64_t t_src_raw = encode_sdi_wb(0x3059002eU, 1, 24, 1, -2);
  const auto t_decoded = decode_sdi_wb_fields(t_src_raw);
  if (t_decoded.dst != 1 || t_decoded.srcd != 24 || t_decoded.srcr != 1 ||
      t_decoded.simm17 != -2) {
    std::cerr << "backend-implicit-sp-order-probe: precheck failed: local-source fields"
              << " dst=" << t_decoded.dst << " srcd=" << t_decoded.srcd
              << " srcr=" << t_decoded.srcr << " simm17=" << t_decoded.simm17 << "\n";
    return false;
  }
  std::cerr << "backend-implicit-sp-order-probe: precheck ok: HL.SDI.PR builder "
            << "round-trips real raw 0xfc15bfd90fee and local-source variant\n";
  return true;
}

enum class SpAccess : unsigned {
  Preserve = 0,
  Read = 1,
  Write = 2,
  ReadWrite = 3,
};

SpAccess classify_sp_access(std::uint16_t opcode,
                            const std::array<std::optional<unsigned>, 3> &src,
                            std::optional<unsigned> dst) {
  const bool implicit_frame =
      opcode == 429 || opcode == 430 || opcode == 431 || opcode == 432;
  bool read = implicit_frame;
  for (const auto &tag : src) {
    read = read || (tag.has_value() && *tag == 1);
  }
  const bool write = implicit_frame || (dst.has_value() && *dst == 1);
  return static_cast<SpAccess>((read ? 1U : 0U) | (write ? 2U : 0U));
}

bool precheck_access_arithmetic_and_identity_oracles() {
  const std::array<std::uint16_t, 4> frame_opcodes = {429, 430, 431, 432};
  const std::array<std::uint32_t, 4> frame_matches = {
      0x41U, 0x1041U, 0x2041U, 0x3041U};
  const std::uint32_t frame_raw = 0x90a50041U;
  for (std::size_t i = 0; i < frame_opcodes.size(); ++i) {
    const std::uint32_t raw =
        (frame_raw & ~0x707fU) | frame_matches[i];
    if ((raw & 0x707fU) != frame_matches[i] ||
        classify_sp_access(frame_opcodes[i], {}, std::nullopt) !=
            SpAccess::ReadWrite) {
      std::cerr << "backend-implicit-sp-order-probe: precheck failed: frame "
                   "raw/classifier index="
                << i << "\n";
      return false;
    }
  }

  const std::array<std::pair<std::uint32_t, std::uint16_t>, 4> indexed_forms = {{
      {0x3059003eU, 216},
      {0x3059002eU, 217},
      {0x7059003eU, 219},
      {0x7059002eU, 220},
  }};
  for (const auto &[match, opcode] : indexed_forms) {
    for (unsigned srcd_is_sp = 0; srcd_is_sp < 2; ++srcd_is_sp) {
      for (unsigned srcr_is_sp = 0; srcr_is_sp < 2; ++srcr_is_sp) {
        for (unsigned dst_is_sp = 0; dst_is_sp < 2; ++dst_is_sp) {
          const unsigned srcd = srcd_is_sp ? 1 : 11;
          const unsigned srcr = srcr_is_sp ? 1 : 10;
          const unsigned dst = dst_is_sp ? 1 : 9;
          const auto decoded =
              decode_sdi_wb_fields(encode_sdi_wb(match, dst, srcd, srcr, -2));
          if (decoded.dst != dst || decoded.srcd != srcd ||
              decoded.srcr != srcr || decoded.simm17 != -2) {
            std::cerr << "backend-implicit-sp-order-probe: precheck failed: "
                         "indexed-store raw round trip opcode="
                      << opcode << "\n";
            return false;
          }
          const auto actual = classify_sp_access(
              opcode, {srcd, srcr, std::nullopt}, dst);
          const auto expected = static_cast<SpAccess>(
              ((srcd_is_sp || srcr_is_sp) ? 1U : 0U) |
              (dst_is_sp ? 2U : 0U));
          if (actual != expected) {
            std::cerr << "backend-implicit-sp-order-probe: precheck failed: "
                         "indexed-store classifier opcode="
                      << opcode << " srcd_sp=" << srcd_is_sp
                      << " srcr_sp=" << srcr_is_sp
                      << " dst_sp=" << dst_is_sp << "\n";
            return false;
          }
        }
      }
    }
  }

  if (classify_sp_access(kOpCMovi, {10, std::nullopt, std::nullopt}, 11) !=
          SpAccess::Preserve ||
      classify_sp_access(61, {10, 11, std::nullopt}, 12) !=
          SpAccess::Preserve) {
    std::cerr << "backend-implicit-sp-order-probe: precheck failed: non-SP "
                 "rows consumed reservation capacity\n";
    return false;
  }

  constexpr std::uint64_t initial_sp = 0x38850;
  constexpr std::uint64_t indexed_sp = initial_sp - 0x10;
  constexpr std::uint64_t frame_size = 0x50;
  constexpr std::uint64_t frame_sp = indexed_sp - frame_size;
  constexpr std::uint64_t save_addr = frame_sp + frame_size - 8;
  constexpr std::uint64_t load_addr = frame_sp + frame_size - 8;
  constexpr std::uint64_t restored_sp = frame_sp + frame_size;
  if (indexed_sp != 0x38840 || frame_sp != 0x387f0 ||
      save_addr != 0x38838 || load_addr != save_addr ||
      restored_sp != 0x38840) {
    std::cerr << "backend-implicit-sp-order-probe: precheck failed: "
                 "immediate-only arithmetic table\n";
    return false;
  }

  struct ExpectedOrder {
    unsigned epoch;
    UopId id;
    SpAccess access;
    unsigned accept_count;
    unsigned terminal_count;
    unsigned commit_count;
    unsigned publication_count;
  };
  const std::array<ExpectedOrder, 3> expected = {{
      {0, UopId{true, false, 0, true, false, 0, 0},
       SpAccess::Preserve, 1, 1, 1, 0},
      {0, UopId{true, false, 1, true, false, 0, 0},
       SpAccess::ReadWrite, 1, 1, 1, 1},
      {0, UopId{true, false, 2, true, false, 0, 0},
       SpAccess::ReadWrite, 1, 1, 1, 1},
  }};
  for (std::size_t i = 0; i < expected.size(); ++i) {
    const auto &row = expected[i];
    if (!row.id.rid_valid || !row.id.bid_valid || row.id.rid_value != i ||
        row.accept_count != 1 || row.terminal_count != 1 ||
        row.commit_count != 1 ||
        row.publication_count != (row.access == SpAccess::Preserve ? 0U : 1U)) {
      std::cerr << "backend-implicit-sp-order-probe: precheck failed: "
                   "identity/order table index="
                << i << "\n";
      return false;
    }
  }

  std::cerr << "backend-implicit-sp-order-probe: precheck ok: complete frame/"
               "explicit classifier, immediate-only arithmetic, identity/order, "
               "and non-SP capacity oracles\n";
  return true;
}

struct FetchMemoryImage {
  std::map<std::uint64_t, std::uint8_t> bytes;

  void store_insn(std::uint64_t pc, std::uint64_t raw, unsigned length) {
    for (unsigned i = 0; i < length; ++i) {
      bytes[pc + i] = static_cast<std::uint8_t>((raw >> (8 * i)) & 0xffU);
    }
  }

  std::uint64_t read_window(std::uint64_t pc) const {
    std::uint64_t window = 0;
    for (unsigned i = 0; i < 8; ++i) {
      const auto it = bytes.find(pc + i);
      if (it != bytes.end()) {
        window |= static_cast<std::uint64_t>(it->second) << (8 * i);
      }
    }
    return window;
  }
};

struct Event {
  enum class Kind { IssueInput, IssueOutput, ExecuteAccepted, Commit } kind;
  std::uint64_t cycle = 0;
  std::uint64_t pc = 0;
  std::uint16_t opcode = 0;
  UopId id;
};

const char *kind_name(Event::Kind kind) {
  switch (kind) {
  case Event::Kind::IssueInput:
    return "issue-input";
  case Event::Kind::IssueOutput:
    return "issue-output";
  case Event::Kind::ExecuteAccepted:
    return "execute-accepted";
  case Event::Kind::Commit:
    return "commit";
  }
  return "unknown";
}

std::string event_line(const Event &event) {
  std::ostringstream os;
  os << "cycle=" << event.cycle << " kind=" << kind_name(event.kind)
     << " pc=" << hex64(event.pc) << " opcode=" << event.opcode
     << " rid=" << event.id.rid_valid << ":" << event.id.rid_wrap << ":"
     << event.id.rid_value << " bid=" << event.id.bid_valid << ":"
     << event.id.bid_wrap << ":" << event.id.bid_value
     << " stid=" << event.id.stid;
  return os.str();
}

void clear_inputs(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  dut.io_startValid = 0;
  dut.io_startPc = 0;
  dut.io_restartValid = 0;
  dut.io_restartPc = 0;
  dut.io_reducedBfuBodyValid = 0;
  dut.io_reducedBfuHeaderPc = 0;
  dut.io_reducedBfuHSizeBytes = 0;
  dut.io_reducedBfuBSizeBytes = 0;
  dut.io_frontendFlushValid = 0;
  dut.io_peId = 0;
  dut.io_threadId = 0;
  dut.io_fetchReqReady = 1;
  dut.io_fetchRespValid = 0;
  dut.io_fetchRespWindow = 0;
  dut.io_rfInitValid = 0;
  dut.io_rfInitArchTag = 0;
  dut.io_rfInitData = 0;
  dut.io_deallocReady = 1;
  dut.io_loadLookupData = 0;
}

UopId input_id(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  return UopId{static_cast<bool>(dut.io_issueQueueInputRidValid),
               static_cast<bool>(dut.io_issueQueueInputRidWrap),
               static_cast<unsigned>(dut.io_issueQueueInputRidValue),
               static_cast<bool>(dut.io_issueQueueInputBidValid),
               static_cast<bool>(dut.io_issueQueueInputBidWrap),
               static_cast<unsigned>(dut.io_issueQueueInputBidValue),
               static_cast<unsigned>(dut.io_issueQueueInputStid)};
}

UopId output_id(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  return UopId{static_cast<bool>(dut.io_issueQueueOutputRidValid),
               static_cast<bool>(dut.io_issueQueueOutputRidWrap),
               static_cast<unsigned>(dut.io_issueQueueOutputRidValue),
               static_cast<bool>(dut.io_issueQueueOutputBidValid),
               static_cast<bool>(dut.io_issueQueueOutputBidWrap),
               static_cast<unsigned>(dut.io_issueQueueOutputBidValue),
               static_cast<unsigned>(dut.io_issueQueueOutputStid)};
}

UopId accepted_id(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  return UopId{static_cast<bool>(dut.io_executeAcceptedRidValid),
               static_cast<bool>(dut.io_executeAcceptedRidWrap),
               static_cast<unsigned>(dut.io_executeAcceptedRidValue),
               static_cast<bool>(dut.io_executeAcceptedBidValid),
               static_cast<bool>(dut.io_executeAcceptedBidWrap),
               static_cast<unsigned>(dut.io_executeAcceptedBidValue),
               static_cast<unsigned>(dut.io_executeAcceptedStid)};
}

void observe(const VLinxCoreFrontendFetchRfAluTraceTop &dut, std::vector<Event> &events) {
  if (dut.io_issueQueueInputValid) {
    events.push_back(Event{Event::Kind::IssueInput, g_cycle, dut.io_issueQueueInputPc,
                           static_cast<std::uint16_t>(dut.io_issueQueueInputOpcode),
                           input_id(dut)});
  }
  if (dut.io_issueQueueOutputValid) {
    events.push_back(Event{Event::Kind::IssueOutput, g_cycle, dut.io_issueQueueOutputPc,
                           static_cast<std::uint16_t>(dut.io_issueQueueOutputOpcode),
                           output_id(dut)});
  }
  if (dut.io_executeAcceptedIdentityValid) {
    events.push_back(Event{Event::Kind::ExecuteAccepted, g_cycle, dut.io_executeAcceptedPc,
                           static_cast<std::uint16_t>(dut.io_executeAcceptedOpcode),
                           accepted_id(dut)});
  }
  if (dut.io_commit_rows_0_valid) {
    events.push_back(Event{Event::Kind::Commit, g_cycle, dut.io_commit_rows_0_pc,
                           0, UopId{}});
  }
  if (dut.io_commit_rows_1_valid) {
    events.push_back(Event{Event::Kind::Commit, g_cycle, dut.io_commit_rows_1_pc,
                           0, UopId{}});
  }
}

void tick(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::vector<Event> &events) {
  dut.clock = 0;
  dut.eval();
  observe(dut, events);
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
  ++g_cycle;
}

void reset(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::vector<Event> &events) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut, events);
  tick(dut, events);
  dut.reset = 0;
  dut.eval();
}

void init_rf(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::uint8_t arch_tag, std::uint64_t data,
             std::vector<Event> &events) {
  clear_inputs(dut);
  dut.io_rfInitValid = 1;
  dut.io_rfInitArchTag = arch_tag;
  dut.io_rfInitData = data;
  tick(dut, events);
  clear_inputs(dut);
  dut.eval();
}

void start_fetch(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::uint64_t pc,
                 std::vector<Event> &events) {
  clear_inputs(dut);
  dut.io_startValid = 1;
  dut.io_startPc = pc;
  tick(dut, events);
  clear_inputs(dut);
  dut.eval();
}

void run_fetch_loop(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemoryImage &mem,
                    std::vector<Event> &events, unsigned max_cycles) {
  std::optional<std::uint64_t> pending_resp_pc;
  for (unsigned i = 0; i < max_cycles; ++i) {
    clear_inputs(dut);
    dut.io_fetchReqReady = 1;
    if (pending_resp_pc.has_value()) {
      dut.io_fetchRespValid = 1;
      dut.io_fetchRespWindow = mem.read_window(*pending_resp_pc);
      pending_resp_pc.reset();
    }
    dut.eval();
    if (dut.io_fetchReqValid && dut.io_fetchReqReady) {
      pending_resp_pc = dut.io_fetchReqPc;
    }
    tick(dut, events);
    if (dut.io_commitContractError || dut.io_rfStateError || dut.io_issueQueueProtocolError) {
      break;
    }
  }
  clear_inputs(dut);
  dut.eval();
}

std::optional<Event> first_event(const std::vector<Event> &events, Event::Kind kind,
                                 std::uint64_t pc, std::uint16_t opcode) {
  for (const auto &event : events) {
    if (event.kind == kind && event.pc == pc && event.opcode == opcode) {
      return event;
    }
  }
  return std::nullopt;
}

void dump_events(const std::vector<Event> &events) {
  for (const auto &event : events) {
    std::cerr << "  " << event_line(event) << "\n";
  }
}

int run_case(unsigned srcd, const char *label, bool expect_current_red,
             bool add_dependency_producer) {
  g_cycle = 0;
  std::vector<Event> events;
  FetchMemoryImage mem;

  const std::uint64_t producer_pc = kBasePc;
  const std::uint64_t older_pc = producer_pc + (add_dependency_producer ? 2 : 0);
  const std::uint64_t younger_pc = older_pc + 6;
  const std::uint16_t producer_raw = encode_c_movi(srcd, 5);
  const std::uint64_t older_raw = encode_sdi_wb(0x3059002eU, 1, srcd, 1, -2);
  const std::uint64_t younger_raw = 0x90a50041ULL;
  if (add_dependency_producer) {
    mem.store_insn(producer_pc, producer_raw, 2);
  }
  mem.store_insn(older_pc, older_raw, 6);
  mem.store_insn(younger_pc, younger_raw, 4);

  VLinxCoreFrontendFetchRfAluTraceTop dut;
  clear_inputs(dut);
  dut.reset = 0;
  reset(dut, events);
  init_rf(dut, 1, kSpSeed, events);
  init_rf(dut, 10, 0x4000, events);
  start_fetch(dut, producer_pc, events);
  run_fetch_loop(dut, mem, events, 180);

  std::cerr << "backend-implicit-sp-order-probe case=" << label
            << " producer=" << add_dependency_producer
            << " producer_pc=" << hex64(producer_pc)
            << " producer_raw=" << hex64(producer_raw)
            << " older_pc=" << hex64(older_pc) << " older_raw=" << hex64(older_raw)
            << " younger_pc=" << hex64(younger_pc) << " younger_raw=" << hex64(younger_raw)
            << "\n";
  dump_events(events);

  if (dut.io_commitContractError || dut.io_rfStateError || dut.io_issueQueueProtocolError) {
    std::cerr << "backend-implicit-sp-order-probe: DUT protocol error"
              << " commitContractError=" << static_cast<unsigned>(dut.io_commitContractError)
              << " rfStateError=" << static_cast<unsigned>(dut.io_rfStateError)
              << " issueQueueProtocolError="
              << static_cast<unsigned>(dut.io_issueQueueProtocolError) << "\n";
    return 2;
  }

  const auto older_input = first_event(events, Event::Kind::IssueInput, older_pc, kOpHlSdiPr);
  const auto younger_input = first_event(events, Event::Kind::IssueInput, younger_pc, kOpFentry);
  const auto older_accepted =
      first_event(events, Event::Kind::ExecuteAccepted, older_pc, kOpHlSdiPr);
  const auto younger_accepted =
      first_event(events, Event::Kind::ExecuteAccepted, younger_pc, kOpFentry);
  if (add_dependency_producer) {
    const auto producer_input =
        first_event(events, Event::Kind::IssueInput, producer_pc, kOpCMovi);
    const auto producer_accepted =
        first_event(events, Event::Kind::ExecuteAccepted, producer_pc, kOpCMovi);
    if (!producer_input.has_value() || !producer_accepted.has_value()) {
      std::cerr << "backend-implicit-sp-order-probe: BLOCKED: legal C.MOVI dependency producer did not "
                   "traverse frontend/decode/rename/issue/execute for case "
                << label << "\n";
      return 2;
    }
  }

  if (!older_input.has_value() || !younger_input.has_value()) {
    std::cerr << "backend-implicit-sp-order-probe: BLOCKED: real frontend/decode/rename did not "
                 "enqueue both legal SP-touching uops through issueQueueInput for case "
              << label << "\n";
    return 2;
  }
  if (older_input->cycle >= younger_input->cycle) {
    std::cerr << "backend-implicit-sp-order-probe: BLOCKED: stimulus did not establish older "
                 "HL.SDI.PR allocation before younger FENTRY for case "
              << label << "\n";
    return 2;
  }
  if (!younger_accepted.has_value()) {
    std::cerr << "backend-implicit-sp-order-probe: BLOCKED: younger FENTRY never reached "
                 "executeAcceptedIdentity for case "
              << label << "\n";
    return 2;
  }

  if (!older_accepted.has_value()) {
    std::cerr << "backend-implicit-sp-order-probe: BLOCKED: older HL.SDI.PR never reached "
                 "executeAcceptedIdentity, so the exact younger-before-older acceptance "
                 "predicate cannot be evaluated for case "
              << label << "\n";
    return 2;
  }

  const bool current_red = younger_accepted->cycle < older_accepted->cycle;
  if (current_red) {
    std::cerr << "backend-implicit-sp-order-probe: CURRENT_RED: scheduler accepted younger "
                 "FENTRY before older SP-producing HL.SDI.PR"
              << " case=" << label
              << " younger_cycle=" << younger_accepted->cycle
              << " older_cycle="
              << (older_accepted.has_value() ? std::to_string(older_accepted->cycle)
                                              : std::string("never"))
              << "\n";
    if (expect_current_red) {
      std::cerr << "backend-implicit-sp-order-probe: EXPECT_CURRENT_RED observed exact "
                   "DUT-issued predicate\n";
      return 0;
    }
    return 1;
  }

  std::cerr << "backend-implicit-sp-order-probe: future-green observation: older SP writer "
               "accepted before younger SP consumer for case "
            << label << " older_cycle=" << older_accepted->cycle
            << " younger_cycle=" << younger_accepted->cycle << "\n";
  return 3;
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const bool expect_current_red = std::getenv("EXPECT_CURRENT_RED") != nullptr;
  if (!precheck_sdi_encoder()) {
    return 2;
  }
  if (!precheck_access_arithmetic_and_identity_oracles()) {
    return 2;
  }

  struct Case {
    unsigned srcd;
    const char *label;
    bool add_dependency_producer;
  };
  const std::array<Case, 2> cases = {{
      {11, "gpr-srcd-x11-independent", false},
      {11, "gpr-srcd-x11-packed-producer", true},
  }};

  bool saw_future_green = false;
  int last_blocked = 2;
  for (const auto &c : cases) {
    const int status =
        run_case(c.srcd, c.label, expect_current_red, c.add_dependency_producer);
    if (status == 0 || status == 1) {
      return status;
    }
    if (status == 3) {
      saw_future_green = true;
      continue;
    }
    last_blocked = status;
  }
  if (saw_future_green) {
    if (expect_current_red) {
      std::cerr << "backend-implicit-sp-order-probe: EXPECT_CURRENT_RED requested, but legal "
                   "integrated stimuli produced only future-green accepted ordering\n";
      return 1;
    }
    return 0;
  }
  return last_blocked;
}
