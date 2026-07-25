#include "VLinxCoreBenchmarkAutonomousTop.h"
#include "verilated.h"

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr std::uint64_t kOuterCallPc = 0x11eb4ULL;
constexpr std::uint32_t kOuterCallRaw32 = 0xfd2f4001U;
constexpr std::uint16_t kOuterCallSetretRaw16 = 0x5516U;
constexpr std::uint64_t kOuterReturnPc = 0x11ee0ULL;
constexpr std::uint64_t kFentryPc = 0x11370ULL;
constexpr std::uint32_t kFentryRaw = 0x29350041U;
constexpr std::uint64_t kFretPc = 0x11dbaULL;
constexpr std::uint32_t kFretRaw = 0x29353041U;
constexpr std::uint64_t kExpectedNewSp = 231248ULL;
constexpr std::uint64_t kExpectedUimm = 160ULL;
constexpr std::uint64_t kExpectedRaSlot = 231400ULL;
constexpr std::uint64_t kExpectedLastSlot = 231328ULL;
constexpr std::uint64_t kNestedRaSlot = 231240ULL;
constexpr std::uint64_t kNestedReturnPc = 0x113aeULL;
constexpr std::uint64_t kMaxTailCycles = 32ULL;
constexpr unsigned kSpReg = 1U;
constexpr unsigned kArchitecturalA0 = 2U;
constexpr unsigned kArchitecturalX3 = 23U;
constexpr unsigned kRegisterCount = 32U;

std::uint64_t parse_u64_arg(const std::string &value, const std::string &name) {
  errno = 0;
  char *end = nullptr;
  const unsigned long long parsed = std::strtoull(value.c_str(), &end, 0);
  if (errno != 0 || end == value.c_str() || *end != '\0') {
    throw std::runtime_error("invalid " + name + ": " + value);
  }
  return static_cast<std::uint64_t>(parsed);
}

std::string hex64(std::uint64_t value) {
  std::ostringstream out;
  out << "0x" << std::hex << value;
  return out.str();
}

class SparseMemory {
public:
  void store_byte(std::uint64_t addr, std::uint8_t value) {
    bytes_[addr] = value;
  }

  void load_sparse_hex(const std::string &path) {
    std::ifstream in(path);
    if (!in) {
      throw std::runtime_error("failed to open sparse memory image: " + path);
    }
    std::string line;
    std::uint64_t line_no = 0;
    std::uint64_t count = 0;
    while (std::getline(in, line)) {
      ++line_no;
      const auto comment = line.find('#');
      if (comment != std::string::npos) {
        line.resize(comment);
      }
      std::istringstream iss(line);
      std::string addr_token;
      std::string byte_token;
      std::string extra;
      if (!(iss >> addr_token)) {
        continue;
      }
      if (!(iss >> byte_token) || (iss >> extra)) {
        throw std::runtime_error("invalid sparse memory line " + std::to_string(line_no));
      }
      const auto addr = parse_u64_arg(addr_token, "sparse memory address");
      const auto byte = parse_u64_arg(byte_token, "sparse memory byte");
      if (byte > 0xffU) {
        throw std::runtime_error("sparse memory byte out of range");
      }
      store_byte(addr, static_cast<std::uint8_t>(byte));
      ++count;
    }
    if (count == 0) {
      throw std::runtime_error("sparse memory image is empty: " + path);
    }
  }

  std::uint64_t read_window(std::uint64_t pc) const {
    std::uint64_t value = 0;
    for (std::uint8_t i = 0; i < 8; ++i) {
      std::uint8_t byte = 0xffU;
      const bool found = read_byte(pc + i, byte);
      if (!found && i == 0) {
        throw std::runtime_error("fetch memory missing first byte at pc=" + hex64(pc));
      }
      value |= static_cast<std::uint64_t>(byte) << (i * 8U);
    }
    return value;
  }

  std::uint32_t read_u32(std::uint64_t addr) const {
    std::uint32_t value = 0;
    for (std::uint8_t i = 0; i < 4; ++i) {
      std::uint8_t byte = 0;
      if (!read_byte(addr + i, byte)) {
        throw std::runtime_error("memory missing byte at " + hex64(addr + i));
      }
      value |= static_cast<std::uint32_t>(byte) << (i * 8U);
    }
    return value;
  }

  std::uint64_t read_u48(std::uint64_t addr) const {
    std::uint64_t value = 0;
    for (std::uint8_t i = 0; i < 6; ++i) {
      std::uint8_t byte = 0;
      if (!read_byte(addr + i, byte)) {
        throw std::runtime_error("memory missing byte at " + hex64(addr + i));
      }
      value |= static_cast<std::uint64_t>(byte) << (i * 8U);
    }
    return value;
  }

  std::uint64_t read_u64_or_zero(std::uint64_t addr) const {
    std::uint64_t value = 0;
    for (std::uint8_t i = 0; i < 8; ++i) {
      std::uint8_t byte = 0;
      (void)read_byte(addr + i, byte);
      value |= static_cast<std::uint64_t>(byte) << (i * 8U);
    }
    return value;
  }

  void apply_store(std::uint64_t addr, std::uint64_t data, std::uint8_t size,
                   std::uint8_t mask) {
    if (size == 0 || size > 8) {
      throw std::runtime_error("unsupported committed store size");
    }
    std::uint8_t effective_mask = mask;
    if (effective_mask == 0) {
      const auto low = static_cast<std::uint8_t>(addr & 0x7U);
      if (low + size > 8) {
        throw std::runtime_error("committed store crosses observation lane");
      }
      effective_mask = static_cast<std::uint8_t>(((1U << size) - 1U) << low);
    }
    const std::uint64_t lane_base = addr & ~0x7ULL;
    for (std::uint8_t i = 0; i < 8; ++i) {
      if ((effective_mask & (1U << i)) != 0) {
        store_byte(lane_base + i,
                   static_cast<std::uint8_t>((data >> (i * 8U)) & 0xffU));
      }
    }
  }

private:
  bool read_byte(std::uint64_t addr, std::uint8_t &value) const {
    const auto it = bytes_.find(addr);
    if (it == bytes_.end()) {
      return false;
    }
    value = it->second;
    return true;
  }

  std::map<std::uint64_t, std::uint8_t> bytes_;
};

struct Args {
  std::string memory_hex;
  std::string report;
  std::uint64_t reset_pc = 0;
  std::uint64_t reset_sp = 0;
  std::uint64_t max_cycles = 5000;
  bool self_test_only = false;
};

struct Row {
  bool valid = false;
  std::uint64_t cycle = 0;
  std::uint64_t trace_cycle = 0;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint64_t bid = 0;
  std::uint64_t gid = 0;
  std::uint64_t rid = 0;
  bool wb_valid = false;
  std::uint64_t wb_rd = 0;
  std::uint64_t wb_data = 0;
  bool src0_valid = false;
  std::uint64_t src0_data = 0;
  bool mem_valid = false;
  std::uint64_t mem_addr = 0;
  std::uint64_t mem_wdata = 0;
  std::uint64_t mem_rdata = 0;
  std::uint64_t mem_size = 0;
  std::uint64_t next_pc = 0;
  bool rob_valid = false;
  std::uint64_t rob_value = 0;
  bool rob_wrap = false;
  bool block_bid_valid = false;
  std::uint64_t block_bid = 0;
};

struct AcceptEvent {
  std::uint64_t cycle = 0;
  bool identity_valid = false;
  bool bid_valid = false;
  std::uint64_t bid = 0;
  bool rid_valid = false;
  std::uint64_t rid = 0;
  std::uint64_t stid = 0;
  bool bid_wrap = false;
  bool rid_wrap = false;
};

struct CompleteEvent {
  std::uint64_t cycle = 0;
  std::uint64_t rob = 0;
};

struct StoreObs {
  std::uint64_t cycle = 0;
  std::uint64_t pc = 0;
  std::uint64_t addr = 0;
  std::uint64_t data = 0;
  std::uint64_t size = 0;
  std::uint64_t bid = 0;
  std::uint64_t gid = 0;
  std::uint64_t rid = 0;
  bool rob_valid = false;
  std::uint64_t rob = 0;
  bool block_bid_valid = false;
  std::uint64_t block_bid = 0;
  bool rob_wrap = false;
};

struct LookupObs {
  std::uint64_t cycle = 0;
  std::uint64_t pc = 0;
  std::uint64_t addr = 0;
  std::uint64_t data = 0;
};

struct TerminalEvent {
  std::uint64_t cycle = 0;
  std::uint64_t pc = 0;
};

struct FrameSpec {
  unsigned begin_reg = 10;
  unsigned end_reg = 19;
  std::uint64_t uimm = kExpectedUimm;
  std::uint64_t return_pc = kOuterReturnPc;
};

struct Observations {
  bool prechecks_ok = false;
  std::uint32_t outer_call_raw32 = 0;
  std::uint16_t outer_call_setret_raw16 = 0;
  std::uint64_t decoded_call_target_pc = 0;
  std::uint64_t decoded_call_return_pc = 0;
  std::uint64_t old_sp = 0;
  std::uint64_t new_sp = 0;
  bool snapshot_valid = false;
  std::vector<unsigned> snapshot_regs;
  std::vector<std::uint64_t> snapshot_values;
  std::vector<AcceptEvent> fentry_accepts;
  std::vector<CompleteEvent> fentry_completes;
  std::vector<Row> fentry_commits;
  std::vector<StoreObs> fentry_stores;
  std::vector<LookupObs> fret_lookups;
  std::vector<AcceptEvent> fret_accepts;
  std::vector<CompleteEvent> fret_completes;
  std::vector<Row> fret_commits;
  std::vector<TerminalEvent> markers;
  std::vector<TerminalEvent> restarts;
  std::vector<StoreObs> nested_nonmatches;
  std::uint64_t commits = 0;
  std::uint64_t cycles = 0;
};

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0
            << " --memory-hex <sparse.mem> --reset-pc <addr> --reset-sp <addr>"
            << " --report <json> [--max-cycles N] [--self-test-only]\n";
  std::exit(2);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--memory-hex" && i + 1 < argc) {
      args.memory_hex = argv[++i];
    } else if (arg == "--reset-pc" && i + 1 < argc) {
      args.reset_pc = parse_u64_arg(argv[++i], "--reset-pc");
    } else if (arg == "--reset-sp" && i + 1 < argc) {
      args.reset_sp = parse_u64_arg(argv[++i], "--reset-sp");
    } else if (arg == "--max-cycles" && i + 1 < argc) {
      args.max_cycles = parse_u64_arg(argv[++i], "--max-cycles");
    } else if (arg == "--report" && i + 1 < argc) {
      args.report = argv[++i];
    } else if (arg == "--self-test-only") {
      args.self_test_only = true;
    } else if (arg == "--qemu" || arg == "--qemu-trace" ||
               arg == "--expected-rows" || arg == "--replay-rows" ||
               arg == "--result-hint") {
      throw std::runtime_error("oracle/replay option is forbidden: " + arg);
    } else {
      usage(argv[0]);
    }
  }
  if (args.memory_hex.empty() || args.report.empty()) {
    usage(argv[0]);
  }
  return args;
}

int decode_dst_begin(std::uint32_t raw) {
  return static_cast<int>((raw >> 15) & 0x1fU);
}

int decode_dst_end(std::uint32_t raw) {
  return static_cast<int>((raw >> 20) & 0x1fU);
}

int decode_uimm(std::uint32_t raw) {
  return static_cast<int>((((raw >> 7) & 0x1fU) << 10) |
                          (((raw >> 25) & 0x7fU) << 3));
}

std::int64_t sign_extend(std::uint64_t value, unsigned bits) {
  const std::uint64_t sign = 1ULL << (bits - 1U);
  const std::uint64_t mask = (1ULL << bits) - 1ULL;
  value &= mask;
  return static_cast<std::int64_t>((value ^ sign) - sign);
}

std::uint64_t decode_call_target(std::uint64_t pc, std::uint32_t raw) {
  if ((raw & 0x7fffU) != 0x4001U) {
    throw std::runtime_error("outer CALL BSTART parcel precheck failed");
  }
  return static_cast<std::uint64_t>(
      static_cast<std::int64_t>(pc) + sign_extend(raw >> 15U, 17U) * 2);
}

std::uint64_t decode_call_return(std::uint64_t pc, std::uint16_t raw) {
  if ((raw & 0xf83fU) != 0x5016U) {
    throw std::runtime_error("outer CALL C.SETRET parcel precheck failed");
  }
  return pc + 4ULL + ((raw >> 6U) & 0x1fU) * 2ULL;
}

std::vector<unsigned> enumerate_registers(unsigned begin, unsigned end) {
  if (begin < kArchitecturalA0 || begin > kArchitecturalX3 ||
      end < kArchitecturalA0 || end > kArchitecturalX3) {
    throw std::runtime_error("template register is outside A0..X3");
  }
  std::vector<unsigned> regs;
  unsigned current = begin;
  for (unsigned steps = 0; steps <= kArchitecturalX3 - kArchitecturalA0;
       ++steps) {
    regs.push_back(current);
    if (current == end) {
      return regs;
    }
    current = current == kArchitecturalX3 ? kArchitecturalA0 : current + 1U;
  }
  throw std::runtime_error("register range did not terminate");
}

std::vector<std::uint64_t> expected_slots(std::uint64_t new_sp,
                                          const FrameSpec &spec) {
  std::vector<std::uint64_t> slots;
  const auto regs = enumerate_registers(spec.begin_reg, spec.end_reg);
  for (std::size_t i = 0; i < regs.size(); ++i) {
    slots.push_back(new_sp + spec.uimm - 8ULL - i * 8ULL);
  }
  return slots;
}

Row read_row(const VLinxCoreBenchmarkAutonomousTop &dut,
             std::uint64_t observation_cycle) {
  Row row;
  row.valid = dut.io_commit_rows_0_valid;
  row.cycle = observation_cycle;
  row.trace_cycle = dut.io_commit_rows_0_cycle;
  row.pc = dut.io_commit_rows_0_pc;
  row.insn = dut.io_commit_rows_0_insn;
  row.bid = dut.io_commit_rows_0_identity_bid;
  row.gid = dut.io_commit_rows_0_identity_gid;
  row.rid = dut.io_commit_rows_0_identity_rid;
  row.wb_valid = dut.io_commit_rows_0_wb_valid;
  row.wb_rd = dut.io_commit_rows_0_wb_reg;
  row.wb_data = dut.io_commit_rows_0_wb_data;
  row.src0_valid = dut.io_commit_rows_0_src0_valid;
  row.src0_data = dut.io_commit_rows_0_src0_data;
  row.mem_valid = dut.io_commit_rows_0_mem_valid;
  row.mem_addr = dut.io_commit_rows_0_mem_addr;
  row.mem_wdata = dut.io_commit_rows_0_mem_wdata;
  row.mem_rdata = dut.io_commit_rows_0_mem_rdata;
  row.mem_size = dut.io_commit_rows_0_mem_size;
  row.next_pc = dut.io_commit_rows_0_nextPc;
  row.rob_valid = dut.io_commit_rows_0_rob_valid;
  row.rob_value = dut.io_commit_rows_0_rob_value;
  row.rob_wrap = dut.io_commit_rows_0_rob_wrap;
  row.block_bid_valid = dut.io_commit_rows_0_blockBidValid;
  row.block_bid = dut.io_commit_rows_0_blockBid;
  return row;
}

void clear_inputs(VLinxCoreBenchmarkAutonomousTop &dut) {
  dut.io_startValid = 0;
  dut.io_resetPc = 0;
  dut.io_resetSp = 0;
  dut.io_restartValid = 0;
  dut.io_restartPc = 0;
  dut.io_restartSp = 0;
  dut.io_flushValid = 0;
  dut.io_peId = 0;
  dut.io_threadId = 0;
  dut.io_fetchReqReady = 0;
  dut.io_fetchRespValid = 0;
  dut.io_fetchRespWindow = 0;
  dut.io_loadLookupData = 0;
}

void eval_with_memory(VLinxCoreBenchmarkAutonomousTop &dut,
                      const SparseMemory &memory) {
  dut.eval();
  if (dut.io_loadLookupValid) {
    dut.io_loadLookupData = memory.read_u64_or_zero(dut.io_loadLookupAddr);
    dut.eval();
  }
}

void tick(VLinxCoreBenchmarkAutonomousTop &dut, const SparseMemory &memory) {
  dut.clock = 0;
  eval_with_memory(dut, memory);
  dut.clock = 1;
  eval_with_memory(dut, memory);
  dut.clock = 0;
  eval_with_memory(dut, memory);
}

void run_prechecks(const SparseMemory &memory) {
  const auto call_raw = memory.read_u32(kOuterCallPc);
  const auto setret =
      static_cast<std::uint16_t>(memory.read_u48(kOuterCallPc) >> 32U);
  if (call_raw != kOuterCallRaw32 || setret != kOuterCallSetretRaw16 ||
      decode_call_target(kOuterCallPc, call_raw) != kFentryPc ||
      decode_call_return(kOuterCallPc, setret) != kOuterReturnPc ||
      memory.read_u32(kFentryPc) != kFentryRaw ||
      memory.read_u32(kFretPc) != kFretRaw ||
      decode_dst_begin(kFentryRaw) != 10 ||
      decode_dst_end(kFentryRaw) != 19 ||
      decode_uimm(kFentryRaw) != static_cast<int>(kExpectedUimm) ||
      decode_dst_begin(kFretRaw) != 10 ||
      decode_dst_end(kFretRaw) != 19 ||
      decode_uimm(kFretRaw) != static_cast<int>(kExpectedUimm)) {
    throw std::runtime_error("natural ELF decode precheck failed");
  }
  const auto slots = expected_slots(kExpectedNewSp, FrameSpec{});
  if (slots.front() != kExpectedRaSlot || slots.back() != kExpectedLastSlot ||
      kNestedRaSlot == kExpectedRaSlot || kNestedReturnPc == kOuterReturnPc) {
    throw std::runtime_error("same-frame precheck failed");
  }
}

AcceptEvent read_accept(const VLinxCoreBenchmarkAutonomousTop &dut,
                        std::uint64_t cycle) {
  return AcceptEvent{
      cycle,
      static_cast<bool>(dut.io_debugExecuteAcceptedIdentityValid),
      static_cast<bool>(dut.io_debugExecuteAcceptedBidValid),
      dut.io_debugExecuteAcceptedBidValue,
      static_cast<bool>(dut.io_debugExecuteAcceptedRidValid),
      dut.io_debugExecuteAcceptedRidValue,
      dut.io_debugExecuteAcceptedStid,
      static_cast<bool>(dut.io_debugExecuteAcceptedBidWrap),
      static_cast<bool>(dut.io_debugExecuteAcceptedRidWrap)};
}

bool exact_accept_complete_commit(const std::vector<AcceptEvent> &accepts,
                                  const std::vector<CompleteEvent> &completes,
                                  const std::vector<Row> &commits) {
  if (accepts.size() != 1 || completes.size() != 1 || commits.size() != 1) {
    return false;
  }
  const auto &accept = accepts.front();
  const auto &complete = completes.front();
  const auto &commit = commits.front();
  return accept.identity_valid && accept.bid_valid && accept.rid_valid &&
         accept.stid == 0 && commit.rob_valid &&
         accept.bid == commit.bid && accept.rid == commit.rid &&
         accept.rid == complete.rob && accept.rid == commit.rob_value &&
         accept.rid_wrap == commit.rob_wrap &&
         accept.cycle <= complete.cycle && complete.cycle <= commit.cycle;
}

bool store_identity_matches(const StoreObs &store, const Row &fentry) {
  return store.pc == kFentryPc && store.rob_valid && fentry.rob_valid &&
         store.rob == fentry.rob_value && store.bid == fentry.bid &&
         store.gid == fentry.gid && store.rid == fentry.rid &&
         store.rob_wrap == fentry.rob_wrap &&
         store.block_bid_valid && fentry.block_bid_valid &&
         store.block_bid == fentry.block_bid;
}

struct Check {
  bool ok = false;
  std::string reason;
};

Check validate_common(const Observations &obs, const FrameSpec &spec) {
  if (!obs.prechecks_ok || !obs.snapshot_valid) {
    return {false, "setup_or_snapshot"};
  }
  if (obs.decoded_call_target_pc != kFentryPc ||
      obs.decoded_call_return_pc != spec.return_pc ||
      obs.old_sp < obs.new_sp || obs.old_sp - obs.new_sp != spec.uimm) {
    return {false, "call_or_stack"};
  }
  const auto regs = enumerate_registers(spec.begin_reg, spec.end_reg);
  if (obs.snapshot_regs != regs || obs.snapshot_values.size() != regs.size() ||
      obs.snapshot_values.empty() || obs.snapshot_values.front() != spec.return_pc) {
    return {false, "register_snapshot"};
  }
  if (!exact_accept_complete_commit(obs.fentry_accepts, obs.fentry_completes,
                                    obs.fentry_commits)) {
    return {false, "fentry_identity"};
  }
  if (!exact_accept_complete_commit(obs.fret_accepts, obs.fret_completes,
                                    obs.fret_commits)) {
    return {false, "fret_identity"};
  }
  const auto &fentry = obs.fentry_commits.front();
  const auto &fret = obs.fret_commits.front();
  if (fentry.pc != kFentryPc || fentry.insn != kFentryRaw ||
      fentry.src0_data != spec.return_pc ||
      !fentry.wb_valid || fentry.wb_data != obs.new_sp ||
      fret.pc != kFretPc || fret.insn != kFretRaw ||
      !fret.block_bid_valid) {
    return {false, "macro_commit"};
  }
  return {true, "ok"};
}

Check validate_terminal(const Observations &obs, std::uint64_t target) {
  if (obs.fret_completes.size() != 1) {
    return {false, "terminal_without_complete"};
  }
  const auto complete_cycle = obs.fret_completes.front().cycle;
  std::size_t matching_markers = 0;
  std::size_t immediate_restarts = 0;
  for (const auto &event : obs.markers) {
    if (event.pc == kFretPc) {
      ++matching_markers;
      if (event.cycle != complete_cycle) {
        return {false, "wrong_terminal_cycle"};
      }
    }
  }
  for (const auto &event : obs.restarts) {
    if (event.cycle == complete_cycle + 1ULL) {
      ++immediate_restarts;
      if (event.pc != target) {
        return {false, "wrong_terminal_target"};
      }
    }
  }
  if (matching_markers != 1) {
    return {false, "matching_terminal_count"};
  }
  if (immediate_restarts != 1) {
    return {false, "immediate_restart_count"};
  }
  return {true, "ok"};
}

Check validate_future(const Observations &obs, const FrameSpec &spec) {
  const Check common = validate_common(obs, spec);
  if (!common.ok) {
    return common;
  }
  const auto slots = expected_slots(obs.new_sp, spec);
  const auto &fentry = obs.fentry_commits.front();
  if (obs.fentry_stores.size() != slots.size()) {
    return {false, "store_count"};
  }
  for (std::size_t i = 0; i < slots.size(); ++i) {
    const auto &store = obs.fentry_stores[i];
    if (store.addr != slots[i]) {
      return {false, "store_order_or_unknown"};
    }
    if (store.data != obs.snapshot_values[i]) {
      return {false, "store_data"};
    }
    if (store.size != 8 || !store_identity_matches(store, fentry)) {
      return {false, "store_identity"};
    }
    if (obs.fret_lookups.empty() ||
        store.cycle >= obs.fret_lookups.front().cycle) {
      return {false, "late_same_frame_store"};
    }
  }
  if (obs.fret_lookups.size() != slots.size()) {
    return {false, "lookup_count"};
  }
  for (std::size_t i = 0; i < slots.size(); ++i) {
    const auto &lookup = obs.fret_lookups[i];
    if (lookup.pc != kFretPc || lookup.addr != slots[i] ||
        lookup.data != obs.snapshot_values[i]) {
      return {false, "lookup_order_or_data"};
    }
  }
  const auto &fret = obs.fret_commits.front();
  if (fret.mem_addr != slots.front() ||
      fret.mem_rdata != spec.return_pc ||
      fret.wb_data != spec.return_pc ||
      fret.next_pc != spec.return_pc) {
    return {false, "parent_return"};
  }
  for (const auto &store : obs.fentry_stores) {
    if (store.cycle >= obs.fret_lookups.front().cycle) {
      return {false, "late_same_frame_store"};
    }
  }
  return validate_terminal(obs, spec.return_pc);
}

Check validate_current_red(const Observations &obs, const FrameSpec &spec) {
  const Check common = validate_common(obs, spec);
  if (!common.ok) {
    return common;
  }
  const auto slots = expected_slots(obs.new_sp, spec);
  const auto &fentry = obs.fentry_commits.front();
  if (obs.fentry_stores.size() != 1 ||
      obs.fentry_stores.front().addr != slots.back() ||
      obs.fentry_stores.front().data != 0 ||
      obs.fentry_stores.front().size != 8 ||
      !store_identity_matches(obs.fentry_stores.front(), fentry)) {
    return {false, "not_exact_current_store"};
  }
  if (obs.fret_lookups.size() != 1 ||
      obs.fret_lookups.front().addr != slots.front() ||
      obs.fret_lookups.front().data != 0) {
    return {false, "not_exact_current_lookup"};
  }
  if (obs.fentry_stores.front().cycle >= obs.fret_lookups.front().cycle) {
    return {false, "late_same_frame_store"};
  }
  const auto &fret = obs.fret_commits.front();
  if (fret.mem_addr != slots.front() || fret.mem_rdata != 0 ||
      fret.wb_data != 0 || fret.next_pc != 0) {
    return {false, "not_exact_current_return"};
  }
  return validate_terminal(obs, 0);
}

std::string classify(const Observations &obs) {
  const Check current = validate_current_red(obs, FrameSpec{});
  if (current.ok) {
    return "current_red_fentry_missing_ra_save";
  }
  const Check future = validate_future(obs, FrameSpec{});
  if (future.ok) {
    return "future_green_full_range_return";
  }
  return "unexpected_observation:" + current.reason + ":" + future.reason;
}

Observations make_future_case(const FrameSpec &spec) {
  Observations obs;
  obs.prechecks_ok = true;
  obs.decoded_call_target_pc = kFentryPc;
  obs.decoded_call_return_pc = spec.return_pc;
  obs.old_sp = 4096;
  obs.new_sp = obs.old_sp - spec.uimm;
  obs.snapshot_valid = true;
  obs.snapshot_regs = enumerate_registers(spec.begin_reg, spec.end_reg);
  for (std::size_t i = 0; i < obs.snapshot_regs.size(); ++i) {
    obs.snapshot_values.push_back(i == 0 ? spec.return_pc : 0x100ULL + i);
  }
  AcceptEvent fa{10, true, true, 4, true, 7, 0};
  Row fc;
  fc.cycle = 12;
  fc.pc = kFentryPc;
  fc.insn = kFentryRaw;
  fc.bid = 4;
  fc.gid = 42;
  fc.rid = 7;
  fc.src0_valid = true;
  fc.src0_data = spec.return_pc;
  fc.wb_valid = true;
  fc.wb_data = obs.new_sp;
  fc.rob_valid = true;
  fc.rob_value = 7;
  fc.block_bid_valid = true;
  fc.block_bid = 25;
  obs.fentry_accepts.push_back(fa);
  obs.fentry_completes.push_back(CompleteEvent{11, 7});
  obs.fentry_commits.push_back(fc);
  const auto slots = expected_slots(obs.new_sp, spec);
  for (std::size_t i = 0; i < slots.size(); ++i) {
    obs.fentry_stores.push_back(
        StoreObs{20 + i, kFentryPc, slots[i], obs.snapshot_values[i], 8,
                 fc.bid, fc.gid, fc.rid, true, fc.rob_value, true,
                 fc.block_bid});
    obs.fret_lookups.push_back(
        LookupObs{50 + i, kFretPc, slots[i], obs.snapshot_values[i]});
  }
  AcceptEvent ra{49, true, true, 6, true, 9, 0};
  Row rc;
  rc.cycle = 70;
  rc.pc = kFretPc;
  rc.insn = kFretRaw;
  rc.bid = 6;
  rc.rid = 9;
  rc.rob_valid = true;
  rc.rob_value = 9;
  rc.block_bid_valid = true;
  rc.block_bid = 38;
  rc.mem_addr = slots.front();
  rc.mem_rdata = spec.return_pc;
  rc.wb_data = spec.return_pc;
  rc.next_pc = spec.return_pc;
  obs.fret_accepts.push_back(ra);
  obs.fret_completes.push_back(CompleteEvent{69, 9});
  obs.fret_commits.push_back(rc);
  obs.markers.push_back(TerminalEvent{69, kFretPc});
  obs.restarts.push_back(TerminalEvent{70, spec.return_pc});
  return obs;
}

void require_self_test(const std::string &name, bool condition) {
  if (!condition) {
    throw std::runtime_error("classifier self-test failed: " + name);
  }
  std::cerr << "self-test PASS " << name << "\n";
}

void run_classifier_self_tests() {
  const FrameSpec normal{};
  const Observations base = make_future_case(normal);
  require_self_test("positive-normal", validate_future(base, normal).ok);

  const FrameSpec wrapped{22, 3, 64, 0x1234};
  const Observations wrapped_case = make_future_case(wrapped);
  require_self_test("positive-wrapped",
                    enumerate_registers(22, 3) ==
                        std::vector<unsigned>({22, 23, 2, 3}) &&
                    validate_future(wrapped_case, wrapped).ok);

  auto reject = [&](const std::string &name, Observations candidate) {
    require_self_test(name, !validate_future(candidate, normal).ok);
  };
  auto candidate = base;
  candidate.fentry_stores[1].data ^= 1;
  reject("negative-wrong-data", candidate);
  candidate = base;
  candidate.fentry_stores[1].addr = 0xdeadbeef;
  reject("negative-unknown-store", candidate);
  candidate = base;
  candidate.fentry_stores.push_back(candidate.fentry_stores.front());
  reject("negative-duplicate-store", candidate);
  candidate = base;
  std::swap(candidate.fentry_stores[0], candidate.fentry_stores[1]);
  reject("negative-out-of-order-store", candidate);
  candidate = base;
  candidate.fentry_stores[0].rob ^= 1;
  reject("negative-wrong-rob", candidate);
  candidate = base;
  candidate.fentry_stores[0].bid ^= 1;
  reject("negative-wrong-bid", candidate);
  candidate = base;
  candidate.fentry_stores[0].block_bid ^= 1;
  reject("negative-wrong-block", candidate);
  candidate = base;
  std::swap(candidate.fret_lookups[0], candidate.fret_lookups[1]);
  reject("negative-lookup-order", candidate);
  candidate = base;
  candidate.fret_lookups[1].data ^= 1;
  reject("negative-lookup-data", candidate);
  candidate = base;
  candidate.markers[0].pc = 0;
  reject("negative-terminal-source", candidate);
  candidate = base;
  candidate.markers[0].cycle += 1;
  reject("negative-terminal-cycle", candidate);
  candidate = base;
  candidate.restarts[0].pc = 0;
  reject("negative-terminal-target", candidate);
  candidate = base;
  candidate.markers.push_back(candidate.markers.front());
  reject("negative-duplicate-terminal", candidate);
  candidate = base;
  candidate.fentry_stores[0].cycle = candidate.fret_lookups.front().cycle;
  reject("negative-late-store", candidate);
}

Observations run_probe(const Args &args, SparseMemory &memory) {
  Observations obs;
  run_prechecks(memory);
  obs.prechecks_ok = true;
  obs.outer_call_raw32 = memory.read_u32(kOuterCallPc);
  obs.outer_call_setret_raw16 =
      static_cast<std::uint16_t>(memory.read_u48(kOuterCallPc) >> 32U);
  obs.decoded_call_target_pc =
      decode_call_target(kOuterCallPc, obs.outer_call_raw32);
  obs.decoded_call_return_pc =
      decode_call_return(kOuterCallPc, obs.outer_call_setret_raw16);

  std::array<std::uint64_t, kRegisterCount> gpr{};
  gpr[kSpReg] = args.reset_sp;
  VLinxCoreBenchmarkAutonomousTop dut;
  bool pending_fetch = false;
  std::uint64_t pending_fetch_pc = 0;
  bool fret_committed = false;
  std::uint64_t tail_cycles = 0;

  clear_inputs(dut);
  dut.reset = 1;
  tick(dut, memory);
  tick(dut, memory);
  dut.reset = 0;

  for (std::uint64_t cycle = 0;
       cycle < args.max_cycles && !Verilated::gotFinish(); ++cycle) {
    clear_inputs(dut);
    dut.io_startValid = cycle == 0;
    dut.io_resetPc = args.reset_pc;
    dut.io_resetSp = args.reset_sp;
    dut.io_fetchReqReady = pending_fetch ? 0 : 1;
    if (pending_fetch) {
      dut.io_fetchRespValid = 1;
      dut.io_fetchRespWindow = memory.read_window(pending_fetch_pc);
    }
    eval_with_memory(dut, memory);

    if (pending_fetch && dut.io_fetchRespValid && dut.io_fetchRespReady) {
      pending_fetch = false;
    }
    if (!pending_fetch && dut.io_fetchReqValid && dut.io_fetchReqReady) {
      pending_fetch = true;
      pending_fetch_pc = dut.io_fetchReqPc;
    }

    if (dut.io_debugExecuteAccepted) {
      if (dut.io_debugExecuteAcceptedPc == kFentryPc) {
        obs.fentry_accepts.push_back(read_accept(dut, cycle));
      } else if (dut.io_debugExecuteAcceptedPc == kFretPc) {
        obs.fret_accepts.push_back(read_accept(dut, cycle));
      }
    }
    if (dut.io_debugLocalExecuteCompleteValid) {
      if (dut.io_debugLocalCompletePc == kFentryPc) {
        obs.fentry_completes.push_back(
            CompleteEvent{cycle, dut.io_debugExecuteCompleteRobValue});
      } else if (dut.io_debugLocalCompletePc == kFretPc) {
        obs.fret_completes.push_back(
            CompleteEvent{cycle, dut.io_debugExecuteCompleteRobValue});
      }
    }
    if (dut.io_loadLookupValid && dut.io_loadLookupPc == kFretPc) {
      obs.fret_lookups.push_back(
          LookupObs{cycle, dut.io_loadLookupPc, dut.io_loadLookupAddr,
                    memory.read_u64_or_zero(dut.io_loadLookupAddr)});
    }
    if (dut.io_debugMarkerRedirectFire && !obs.fret_accepts.empty()) {
      obs.markers.push_back(
          TerminalEvent{cycle, dut.io_debugMarkerRedirectPc});
    }
    if (dut.io_sourceRestartValid && !obs.fret_accepts.empty()) {
      obs.restarts.push_back(TerminalEvent{cycle, dut.io_sourceRestartPc});
    }

    if (dut.io_commit_rows_0_valid) {
      const Row row = read_row(dut, cycle);
      ++obs.commits;
      if (row.pc == kFentryPc && row.insn == kFentryRaw) {
        if (!obs.snapshot_valid) {
          obs.old_sp = gpr[kSpReg];
          obs.new_sp = row.wb_data;
          obs.snapshot_regs = enumerate_registers(
              decode_dst_begin(kFentryRaw), decode_dst_end(kFentryRaw));
          for (const unsigned reg : obs.snapshot_regs) {
            obs.snapshot_values.push_back(gpr[reg]);
          }
          obs.snapshot_valid = true;
        }
        obs.fentry_commits.push_back(row);
      } else if (row.pc == kFretPc && row.insn == kFretRaw) {
        obs.fret_commits.push_back(row);
        fret_committed = true;
      }
      if (row.wb_valid && row.wb_rd < kRegisterCount) {
        gpr[row.wb_rd] = row.wb_data;
      }
    }

    if (dut.io_storeObserveValid) {
      StoreObs store{
          cycle,
          dut.io_storeObservePc,
          dut.io_storeObserveAddr,
          dut.io_storeObserveData,
          dut.io_storeObserveSize,
          dut.io_storeObserveBid,
          dut.io_storeObserveGid,
          dut.io_storeObserveRid,
          static_cast<bool>(dut.io_storeObserveRobValid),
          dut.io_storeObserveRobValue,
          static_cast<bool>(dut.io_storeObserveBlockBidValid),
          dut.io_storeObserveBlockBid,
          static_cast<bool>(dut.io_storeObserveRobWrap)};
      if (store.pc == kFentryPc && !obs.fentry_accepts.empty()) {
        obs.fentry_stores.push_back(store);
      }
      if (store.addr == kNestedRaSlot || store.data == kNestedReturnPc) {
        obs.nested_nonmatches.push_back(store);
      }
      memory.apply_store(store.addr, store.data,
                         static_cast<std::uint8_t>(store.size),
                         dut.io_storeObserveMask);
    }

    tick(dut, memory);
    obs.cycles = cycle + 1;
    if (fret_committed && ++tail_cycles >= kMaxTailCycles) {
      break;
    }
  }
  return obs;
}

template <typename T, typename Emit>
void emit_array(std::ostream &out, const std::vector<T> &values, Emit emit) {
  out << "[";
  for (std::size_t i = 0; i < values.size(); ++i) {
    if (i != 0) {
      out << ",";
    }
    emit(out, values[i]);
  }
  out << "]";
}

void write_report(const Args &args, const Observations &obs,
                  const std::string &status, int exit_code) {
  const std::string tmp = args.report + ".tmp";
  std::ofstream out(tmp);
  if (!out) {
    throw std::runtime_error("failed to open report tmp");
  }
  out << "{\n"
      << "  \"schema\":\"linxcore.bctrl_fentry_fret_natural_observation.v2\",\n"
      << "  \"status\":\"" << status << "\",\n"
      << "  \"exit_code\":" << exit_code << ",\n"
      << "  \"self_tests\":\"pass\",\n"
      << "  \"prechecks_ok\":" << (obs.prechecks_ok ? "true" : "false") << ",\n"
      << "  \"decoded_call_target_pc\":" << obs.decoded_call_target_pc << ",\n"
      << "  \"decoded_call_return_pc\":" << obs.decoded_call_return_pc << ",\n"
      << "  \"old_sp\":" << obs.old_sp << ",\n"
      << "  \"new_sp\":" << obs.new_sp << ",\n"
      << "  \"frame_delta\":" << (obs.old_sp >= obs.new_sp ? obs.old_sp - obs.new_sp : 0) << ",\n"
      << "  \"snapshot\":{\"valid\":" << (obs.snapshot_valid ? "true" : "false")
      << ",\"registers\":";
  emit_array(out, obs.snapshot_regs,
             [](std::ostream &s, unsigned value) { s << value; });
  out << ",\"values\":";
  emit_array(out, obs.snapshot_values,
             [](std::ostream &s, std::uint64_t value) { s << value; });
  out << "},\n"
      << "  \"public_io_unavailable\":["
      << "\"accept_raw\",\"complete_raw\",\"complete_bid\",\"complete_stid\","
      << "\"lookup_rob\",\"lookup_bid\",\"marker_rob\",\"restart_source_pc\","
      << "\"commit_trace_cycle_populated\"],\n"
      << "  \"fentry_accepts\":";
  emit_array(out, obs.fentry_accepts, [](std::ostream &s, const AcceptEvent &v) {
    s << "{\"cycle\":" << v.cycle
      << ",\"identity_valid\":" << (v.identity_valid ? "true" : "false")
      << ",\"bid_valid\":" << (v.bid_valid ? "true" : "false")
      << ",\"bid\":" << v.bid
      << ",\"rid_valid\":" << (v.rid_valid ? "true" : "false")
      << ",\"rid\":" << v.rid << ",\"stid\":" << v.stid
      << ",\"bid_wrap\":" << (v.bid_wrap ? "true" : "false")
      << ",\"rid_wrap\":" << (v.rid_wrap ? "true" : "false") << "}";
  });
  out << ",\n  \"fentry_completes\":";
  emit_array(out, obs.fentry_completes,
             [](std::ostream &s, const CompleteEvent &v) {
               s << "{\"cycle\":" << v.cycle << ",\"rob\":" << v.rob << "}";
             });
  out << ",\n  \"fentry_commits\":";
  emit_array(out, obs.fentry_commits, [](std::ostream &s, const Row &v) {
    s << "{\"cycle\":" << v.cycle << ",\"trace_cycle\":" << v.trace_cycle
      << ",\"pc\":" << v.pc
      << ",\"raw\":" << v.insn << ",\"bid\":" << v.bid
      << ",\"gid\":" << v.gid << ",\"rid\":" << v.rid
      << ",\"rob\":" << v.rob_value << ",\"block_bid\":" << v.block_bid
      << ",\"rob_wrap\":" << (v.rob_wrap ? "true" : "false")
      << ",\"src0_valid\":" << (v.src0_valid ? "true" : "false")
      << ",\"src0_data\":" << v.src0_data
      << ",\"wb_valid\":" << (v.wb_valid ? "true" : "false")
      << ",\"wb_data\":" << v.wb_data
      << "}";
  });
  out << ",\n  \"fret_accepts\":";
  emit_array(out, obs.fret_accepts, [](std::ostream &s, const AcceptEvent &v) {
    s << "{\"cycle\":" << v.cycle
      << ",\"identity_valid\":" << (v.identity_valid ? "true" : "false")
      << ",\"bid_valid\":" << (v.bid_valid ? "true" : "false")
      << ",\"bid\":" << v.bid
      << ",\"rid_valid\":" << (v.rid_valid ? "true" : "false")
      << ",\"rid\":" << v.rid << ",\"stid\":" << v.stid
      << ",\"bid_wrap\":" << (v.bid_wrap ? "true" : "false")
      << ",\"rid_wrap\":" << (v.rid_wrap ? "true" : "false") << "}";
  });
  out << ",\n  \"fret_completes\":";
  emit_array(out, obs.fret_completes,
             [](std::ostream &s, const CompleteEvent &v) {
               s << "{\"cycle\":" << v.cycle << ",\"rob\":" << v.rob << "}";
             });
  out << ",\n  \"fret_commits\":";
  emit_array(out, obs.fret_commits, [](std::ostream &s, const Row &v) {
    s << "{\"cycle\":" << v.cycle << ",\"trace_cycle\":" << v.trace_cycle
      << ",\"pc\":" << v.pc
      << ",\"raw\":" << v.insn << ",\"bid\":" << v.bid
      << ",\"gid\":" << v.gid << ",\"rid\":" << v.rid
      << ",\"rob\":" << v.rob_value << ",\"block_bid\":" << v.block_bid
      << ",\"rob_wrap\":" << (v.rob_wrap ? "true" : "false")
      << ",\"mem_addr\":" << v.mem_addr << ",\"mem_rdata\":" << v.mem_rdata
      << ",\"wb_data\":" << v.wb_data << ",\"next_pc\":" << v.next_pc
      << "}";
  });
  out << ",\n  \"fentry_stores\":";
  emit_array(out, obs.fentry_stores, [](std::ostream &s, const StoreObs &v) {
    s << "{\"cycle\":" << v.cycle << ",\"pc\":" << v.pc
      << ",\"addr\":" << v.addr << ",\"data\":" << v.data
      << ",\"size\":" << v.size << ",\"bid\":" << v.bid
      << ",\"gid\":" << v.gid << ",\"rid\":" << v.rid
      << ",\"rob_valid\":" << (v.rob_valid ? "true" : "false")
      << ",\"rob\":" << v.rob
      << ",\"rob_wrap\":" << (v.rob_wrap ? "true" : "false")
      << ",\"block_bid_valid\":" << (v.block_bid_valid ? "true" : "false")
      << ",\"block_bid\":" << v.block_bid << "}";
  });
  out << ",\n  \"fret_lookups\":";
  emit_array(out, obs.fret_lookups, [](std::ostream &s, const LookupObs &v) {
    s << "{\"cycle\":" << v.cycle << ",\"pc\":" << v.pc
      << ",\"addr\":" << v.addr << ",\"data\":" << v.data << "}";
  });
  out << ",\n  \"markers\":";
  emit_array(out, obs.markers, [](std::ostream &s, const TerminalEvent &v) {
    s << "{\"cycle\":" << v.cycle << ",\"source_pc\":" << v.pc << "}";
  });
  out << ",\n  \"restarts\":";
  emit_array(out, obs.restarts, [](std::ostream &s, const TerminalEvent &v) {
    s << "{\"cycle\":" << v.cycle << ",\"target_pc\":" << v.pc << "}";
  });
  out << ",\n  \"nested_nonmatches\":";
  emit_array(out, obs.nested_nonmatches,
             [](std::ostream &s, const StoreObs &v) {
               s << "{\"cycle\":" << v.cycle << ",\"pc\":" << v.pc
                 << ",\"addr\":" << v.addr << ",\"data\":" << v.data << "}";
             });
  out << ",\n"
      << "  \"commits\":" << obs.commits << ",\n"
      << "  \"cycles\":" << obs.cycles << ",\n"
      << "  \"tail_cycles\":" << kMaxTailCycles << ",\n"
      << "  \"repair_packet\":\"first owner: block-control FENTRY template sequencing; downstream: backend, LSU, FRET recovery\",\n"
      << "  \"frame_policy\":\"Sail frame-size arithmetic remains separate\"\n"
      << "}\n";
  out.close();
  if (!out || std::rename(tmp.c_str(), args.report.c_str()) != 0) {
    throw std::runtime_error("failed to publish report: " +
                             std::string(std::strerror(errno)));
  }
}

} // namespace

int main(int argc, char **argv) {
  try {
    Verilated::commandArgs(argc, argv);
    const Args args = parse_args(argc, argv);
    run_classifier_self_tests();
    if (args.self_test_only) {
      Observations empty;
      write_report(args, empty, "self_tests_pass", 0);
      return 0;
    }

    SparseMemory memory;
    memory.load_sparse_hex(args.memory_hex);
    Observations obs = run_probe(args, memory);
    const std::string status = classify(obs);
    const bool expect_current_red =
        std::getenv("EXPECT_CURRENT_RED") != nullptr;
    int exit_code = 2;
    if (status == "future_green_full_range_return") {
      exit_code = 0;
    } else if (status == "current_red_fentry_missing_ra_save") {
      exit_code = expect_current_red ? 0 : 1;
    }
    write_report(args, obs, status, exit_code);
    std::cerr << "bctrl-fentry-fret-observation status=" << status
              << " exit_code=" << exit_code
              << " stores=" << obs.fentry_stores.size()
              << " lookups=" << obs.fret_lookups.size() << "\n";
    return exit_code;
  } catch (const std::exception &exc) {
    std::cerr << "error: " << exc.what() << "\n";
    return 2;
  }
}
