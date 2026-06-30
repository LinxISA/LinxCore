#ifndef LINXCORE_COMMIT_TRACE_DUT_HEADER
#define LINXCORE_COMMIT_TRACE_DUT_HEADER "VReducedCommitROB.h"
#endif

#ifndef LINXCORE_COMMIT_TRACE_DUT_CLASS
#define LINXCORE_COMMIT_TRACE_DUT_CLASS VReducedCommitROB
#endif

#include LINXCORE_COMMIT_TRACE_DUT_HEADER
#include "verilated.h"

#include "commit_trace_jsonl.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <optional>
#include <string>
#include <vector>

namespace {

using CommitTraceDut = LINXCORE_COMMIT_TRACE_DUT_CLASS;
using linxcore::chisel::CommitTraceJsonRow;
using linxcore::chisel::write_dut_commit_jsonl;
using linxcore::chisel::write_qemu_commit_jsonl;

struct Args {
  std::string input_trace;
  std::string dut_trace;
  std::string qemu_trace;
  std::size_t max_rows = 0;
};

struct Row {
  std::uint64_t seq = 0;
  std::uint64_t cycle = 0;
  std::uint32_t bid = 0;
  std::uint32_t gid = 0;
  std::uint32_t rid = 0;
  std::uint64_t block_bid = 0;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 4;
  bool wb_valid = false;
  std::uint8_t wb_rd = 0;
  std::uint64_t wb_data = 0;
  bool src0_valid = false;
  std::uint8_t src0_reg = 0;
  std::uint64_t src0_data = 0;
  bool src1_valid = false;
  std::uint8_t src1_reg = 0;
  std::uint64_t src1_data = 0;
  bool dst_valid = false;
  std::uint8_t dst_reg = 0;
  std::uint64_t dst_data = 0;
  bool mem_valid = false;
  bool mem_is_store = false;
  std::uint64_t mem_addr = 0;
  std::uint64_t mem_wdata = 0;
  std::uint64_t mem_rdata = 0;
  std::uint8_t mem_size = 0;
  bool trap_valid = false;
  std::uint32_t trap_cause = 0;
  std::uint64_t trap_arg0 = 0;
  std::uint64_t next_pc = 0;
};

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0
            << " [--input-trace <flat.jsonl>] --dut-trace <dut.jsonl>"
            << " --qemu-trace <qemu.jsonl> [--max-rows <n>]\n";
  std::exit(2);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--dut-trace" && i + 1 < argc) {
      args.dut_trace = argv[++i];
    } else if (arg == "--qemu-trace" && i + 1 < argc) {
      args.qemu_trace = argv[++i];
    } else if (arg == "--input-trace" && i + 1 < argc) {
      args.input_trace = argv[++i];
    } else if (arg == "--max-rows" && i + 1 < argc) {
      args.max_rows = static_cast<std::size_t>(std::stoull(argv[++i]));
    } else {
      usage(argv[0]);
    }
  }
  if (args.dut_trace.empty() || args.qemu_trace.empty()) {
    usage(argv[0]);
  }
  return args;
}

std::string lower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return value;
}

std::optional<std::string> json_token(const std::string &line, const std::string &key) {
  const std::string needle = "\"" + key + "\"";
  const std::size_t key_pos = line.find(needle);
  if (key_pos == std::string::npos) {
    return std::nullopt;
  }

  const std::size_t colon = line.find(':', key_pos + needle.size());
  if (colon == std::string::npos) {
    return std::nullopt;
  }

  std::size_t pos = colon + 1;
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    ++pos;
  }
  if (pos >= line.size()) {
    return std::nullopt;
  }

  if (line[pos] == '"') {
    const std::size_t end = line.find('"', pos + 1);
    if (end == std::string::npos) {
      return std::nullopt;
    }
    return line.substr(pos + 1, end - pos - 1);
  }

  std::size_t end = pos;
  while (end < line.size() && line[end] != ',' && line[end] != '}' &&
         line[end] != ']' && !std::isspace(static_cast<unsigned char>(line[end]))) {
    ++end;
  }
  return line.substr(pos, end - pos);
}

std::uint64_t token_to_u64(const std::string &token, std::uint64_t fallback = 0) {
  const std::string lowered = lower(token);
  if (lowered == "true") {
    return 1;
  }
  if (lowered == "false" || lowered == "null") {
    return 0;
  }

  try {
    return std::stoull(token, nullptr, 0);
  } catch (const std::exception &) {
    return fallback;
  }
}

std::uint64_t get_u64(
    const std::string &line,
    std::initializer_list<const char *> keys,
    std::uint64_t fallback = 0) {
  for (const char *key : keys) {
    if (const auto token = json_token(line, key)) {
      return token_to_u64(*token, fallback);
    }
  }
  return fallback;
}

bool has_meta_type(const std::string &line) {
  const auto token = json_token(line, "type");
  return token && lower(*token) == "meta";
}

std::optional<Row> parse_trace_row(const std::string &line, std::uint64_t default_seq) {
  if (line.empty() || has_meta_type(line)) {
    return std::nullopt;
  }
  if (json_token(line, "valid") && get_u64(line, {"valid"}, 1) == 0) {
    return std::nullopt;
  }

  Row r;
  r.seq = get_u64(line, {"seq"}, default_seq);
  r.cycle = get_u64(line, {"cycle"}, 0);
  r.bid = static_cast<std::uint32_t>(get_u64(line, {"bid"}, 0));
  r.gid = static_cast<std::uint32_t>(get_u64(line, {"gid"}, 0));
  r.rid = static_cast<std::uint32_t>(get_u64(line, {"rid"}, default_seq));
  r.block_bid = get_u64(line, {"block_bid", "blockBid"}, r.bid);
  r.pc = get_u64(line, {"pc"}, 0);
  r.insn = get_u64(line, {"insn"}, 0);
  r.len = static_cast<std::uint8_t>(get_u64(line, {"len", "length", "insn_len"}, 4));
  r.wb_valid = get_u64(line, {"wb_valid"}, 0) != 0;
  r.wb_rd = static_cast<std::uint8_t>(get_u64(line, {"wb_rd", "wb_reg", "rd"}, 0));
  r.wb_data = get_u64(line, {"wb_data", "wb_value"}, 0);
  r.src0_valid = get_u64(line, {"src0_valid"}, 0) != 0;
  r.src0_reg = static_cast<std::uint8_t>(get_u64(line, {"src0_reg"}, 0));
  r.src0_data = get_u64(line, {"src0_data"}, 0);
  r.src1_valid = get_u64(line, {"src1_valid"}, 0) != 0;
  r.src1_reg = static_cast<std::uint8_t>(get_u64(line, {"src1_reg"}, 0));
  r.src1_data = get_u64(line, {"src1_data"}, 0);
  r.dst_valid = get_u64(line, {"dst_valid"}, r.wb_valid ? 1 : 0) != 0;
  r.dst_reg = static_cast<std::uint8_t>(get_u64(line, {"dst_reg"}, r.wb_rd));
  r.dst_data = get_u64(line, {"dst_data"}, r.wb_data);
  r.mem_valid = get_u64(line, {"mem_valid"}, 0) != 0;
  r.mem_is_store = get_u64(line, {"mem_is_store", "mem_isStore"}, 0) != 0;
  r.mem_addr = get_u64(line, {"mem_addr"}, 0);
  r.mem_wdata = get_u64(line, {"mem_wdata"}, 0);
  r.mem_rdata = get_u64(line, {"mem_rdata"}, 0);
  r.mem_size = static_cast<std::uint8_t>(get_u64(line, {"mem_size"}, 0));
  r.trap_valid = get_u64(line, {"trap_valid"}, 0) != 0;
  r.trap_cause = static_cast<std::uint32_t>(get_u64(line, {"trap_cause", "cause"}, 0));
  r.trap_arg0 = get_u64(line, {"traparg0", "trap_arg0", "trap_arg"}, 0);
  r.next_pc = get_u64(line, {"next_pc", "nextPc", "npc"}, 0);
  return r;
}

std::vector<Row> load_trace_rows(const std::string &path, std::size_t max_rows) {
  std::ifstream in(path);
  if (!in) {
    std::cerr << "failed to open input trace: " << path << "\n";
    std::exit(2);
  }

  std::vector<Row> rows;
  std::string line;
  std::uint64_t default_seq = 0;
  while (std::getline(in, line)) {
    if (auto row = parse_trace_row(line, default_seq)) {
      rows.push_back(*row);
      ++default_seq;
      if (max_rows != 0 && rows.size() >= max_rows) {
        break;
      }
    }
  }
  if (rows.empty()) {
    std::cerr << "input trace had no replayable commit rows: " << path << "\n";
    std::exit(2);
  }
  return rows;
}

void clear_inputs(CommitTraceDut &dut) {
  dut.io_allocValid = 0;
  dut.io_allocRow_valid = 0;
  dut.io_allocRow_seq = 0;
  dut.io_allocRow_cycle = 0;
  dut.io_allocRow_slot = 0;
  dut.io_allocRow_identity_bid = 0;
  dut.io_allocRow_identity_gid = 0;
  dut.io_allocRow_identity_rid = 0;
  dut.io_allocRow_rob_valid = 0;
  dut.io_allocRow_rob_wrap = 0;
  dut.io_allocRow_rob_value = 0;
  dut.io_allocRow_blockBidValid = 0;
  dut.io_allocRow_blockBid = 0;
  dut.io_allocRow_pc = 0;
  dut.io_allocRow_insn = 0;
  dut.io_allocRow_len = 0;
  dut.io_allocRow_wb_valid = 0;
  dut.io_allocRow_wb_reg = 0;
  dut.io_allocRow_wb_data = 0;
  dut.io_allocRow_src0_valid = 0;
  dut.io_allocRow_src0_reg = 0;
  dut.io_allocRow_src0_data = 0;
  dut.io_allocRow_src1_valid = 0;
  dut.io_allocRow_src1_reg = 0;
  dut.io_allocRow_src1_data = 0;
  dut.io_allocRow_dst_valid = 0;
  dut.io_allocRow_dst_reg = 0;
  dut.io_allocRow_dst_data = 0;
  dut.io_allocRow_mem_valid = 0;
  dut.io_allocRow_mem_isStore = 0;
  dut.io_allocRow_mem_addr = 0;
  dut.io_allocRow_mem_wdata = 0;
  dut.io_allocRow_mem_rdata = 0;
  dut.io_allocRow_mem_size = 0;
  dut.io_allocRow_trap_valid = 0;
  dut.io_allocRow_trap_cause = 0;
  dut.io_allocRow_trap_arg0 = 0;
  dut.io_allocRow_nextPc = 0;
  dut.io_completeValid = 0;
  dut.io_completeRobValue = 0;
}

void eval(CommitTraceDut &dut) { dut.eval(); }

void tick(CommitTraceDut &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

void reset(CommitTraceDut &dut) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
}

void drive_alloc(CommitTraceDut &dut, const Row &row) {
  clear_inputs(dut);
  dut.io_allocValid = 1;
  dut.io_allocRow_valid = 1;
  dut.io_allocRow_seq = row.seq;
  dut.io_allocRow_cycle = row.cycle;
  dut.io_allocRow_identity_bid = row.bid;
  dut.io_allocRow_identity_gid = row.gid;
  dut.io_allocRow_identity_rid = row.rid;
  dut.io_allocRow_blockBidValid = 1;
  dut.io_allocRow_blockBid = row.block_bid;
  dut.io_allocRow_pc = row.pc;
  dut.io_allocRow_insn = row.insn;
  dut.io_allocRow_len = row.len;
  dut.io_allocRow_wb_valid = row.wb_valid;
  dut.io_allocRow_wb_reg = row.wb_rd;
  dut.io_allocRow_wb_data = row.wb_data;
  dut.io_allocRow_src0_valid = row.src0_valid;
  dut.io_allocRow_src0_reg = row.src0_reg;
  dut.io_allocRow_src0_data = row.src0_data;
  dut.io_allocRow_src1_valid = row.src1_valid;
  dut.io_allocRow_src1_reg = row.src1_reg;
  dut.io_allocRow_src1_data = row.src1_data;
  dut.io_allocRow_dst_valid = row.dst_valid;
  dut.io_allocRow_dst_reg = row.dst_reg;
  dut.io_allocRow_dst_data = row.dst_data;
  dut.io_allocRow_mem_valid = row.mem_valid;
  dut.io_allocRow_mem_isStore = row.mem_is_store;
  dut.io_allocRow_mem_addr = row.mem_addr;
  dut.io_allocRow_mem_wdata = row.mem_wdata;
  dut.io_allocRow_mem_rdata = row.mem_rdata;
  dut.io_allocRow_mem_size = row.mem_size;
  dut.io_allocRow_trap_valid = row.trap_valid;
  dut.io_allocRow_trap_cause = row.trap_cause;
  dut.io_allocRow_trap_arg0 = row.trap_arg0;
  dut.io_allocRow_nextPc = row.next_pc;
}

void alloc_row(CommitTraceDut &dut, const Row &row) {
  drive_alloc(dut, row);
  eval(dut);
  if (!dut.io_allocReady) {
    std::cerr << "allocation rejected unexpectedly for rid=" << row.rid << "\n";
    std::exit(1);
  }
  tick(dut);
  clear_inputs(dut);
  eval(dut);
}

void expect_duplicate_rejected(CommitTraceDut &dut, const Row &row) {
  drive_alloc(dut, row);
  eval(dut);
  if (!dut.io_allocDuplicateIdentity || dut.io_allocReady) {
    std::cerr << "duplicate CommitInfo identity was not rejected\n";
    std::exit(1);
  }
  clear_inputs(dut);
  eval(dut);
}

void complete_slot(CommitTraceDut &dut, std::uint8_t slot) {
  clear_inputs(dut);
  dut.io_completeValid = 1;
  dut.io_completeRobValue = slot;
  tick(dut);
  clear_inputs(dut);
  eval(dut);
}

void expect_monitor_clean(
    const CommitTraceDut &dut,
    const char *context,
    std::uint8_t expected_mask,
    std::uint8_t expected_count) {
  if (dut.io_commitContractError || dut.io_commitSkippedSlot ||
      dut.io_commitDuplicateIdentity || dut.io_commitSlotMismatch ||
      dut.io_commitInvalidSideEffect) {
    std::cerr << "commit monitor reported a contract error during " << context
              << " mask=" << static_cast<unsigned>(dut.io_commitMonitorValidMask)
              << " count=" << static_cast<unsigned>(dut.io_commitMonitorValidCount)
              << " skipped=" << static_cast<unsigned>(dut.io_commitSkippedSlot)
              << " duplicate=" << static_cast<unsigned>(dut.io_commitDuplicateIdentity)
              << " slot_mismatch=" << static_cast<unsigned>(dut.io_commitSlotMismatch)
              << " invalid_side_effect=" << static_cast<unsigned>(dut.io_commitInvalidSideEffect)
              << "\n";
    std::exit(1);
  }

  if (dut.io_commitMonitorValidMask != expected_mask ||
      dut.io_commitMonitorValidCount != expected_count) {
    std::cerr << "commit monitor shape mismatch during " << context
              << " expected_mask=" << static_cast<unsigned>(expected_mask)
              << " observed_mask=" << static_cast<unsigned>(dut.io_commitMonitorValidMask)
              << " expected_count=" << static_cast<unsigned>(expected_count)
              << " observed_count=" << static_cast<unsigned>(dut.io_commitMonitorValidCount)
              << "\n";
    std::exit(1);
  }
}

Row row0() {
  Row r;
  r.seq = 0;
  r.cycle = 10;
  r.bid = 7;
  r.rid = 0;
  r.block_bid = 0x200000070ULL;
  r.pc = 0x1000;
  r.insn = 0x13;
  r.len = 4;
  r.wb_valid = true;
  r.wb_rd = 5;
  r.wb_data = 0x44;
  r.dst_valid = true;
  r.dst_reg = 5;
  r.dst_data = 0x44;
  r.next_pc = 0x1004;
  return r;
}

Row row1() {
  Row r;
  r.seq = 1;
  r.cycle = 10;
  r.bid = 7;
  r.rid = 1;
  r.block_bid = 0x200000071ULL;
  r.pc = 0x1004;
  r.insn = 0x23;
  r.len = 4;
  r.src0_valid = true;
  r.src0_reg = 3;
  r.src0_data = 0x99;
  r.mem_valid = true;
  r.mem_is_store = true;
  r.mem_addr = 0x2000;
  r.mem_wdata = 0x99;
  r.mem_size = 8;
  r.next_pc = 0x1008;
  return r;
}

Row row2() {
  Row r;
  r.seq = 2;
  r.cycle = 11;
  r.bid = 7;
  r.rid = 2;
  r.block_bid = 0x200000072ULL;
  r.pc = 0x1008;
  r.insn = 0x6f;
  r.len = 4;
  r.trap_valid = true;
  r.trap_cause = 0x45;
  r.trap_arg0 = 0x1008;
  r.next_pc = 0x1010;
  return r;
}

void expect_slot0(const CommitTraceDut &dut, const Row &row, std::uint8_t slot) {
  if (!dut.io_commit_rows_0_valid || dut.io_commit_rows_0_slot != slot ||
      dut.io_commit_rows_0_identity_bid != row.bid ||
      dut.io_commit_rows_0_identity_gid != row.gid ||
      dut.io_commit_rows_0_identity_rid != row.rid ||
      dut.io_commit_rows_0_blockBid != row.block_bid ||
      dut.io_commit_rows_0_pc != row.pc ||
      dut.io_commit_rows_0_insn != row.insn ||
      dut.io_commit_rows_0_len != row.len ||
      dut.io_commit_rows_0_wb_valid != row.wb_valid ||
      dut.io_commit_rows_0_wb_reg != row.wb_rd ||
      dut.io_commit_rows_0_wb_data != row.wb_data ||
      dut.io_commit_rows_0_mem_valid != row.mem_valid ||
      dut.io_commit_rows_0_mem_isStore != row.mem_is_store ||
      dut.io_commit_rows_0_mem_addr != row.mem_addr ||
      dut.io_commit_rows_0_mem_wdata != row.mem_wdata ||
      dut.io_commit_rows_0_mem_size != row.mem_size ||
      dut.io_commit_rows_0_trap_valid != row.trap_valid ||
      dut.io_commit_rows_0_trap_cause != row.trap_cause ||
      dut.io_commit_rows_0_trap_arg0 != row.trap_arg0 ||
      dut.io_commit_rows_0_nextPc != row.next_pc) {
    std::cerr << "slot0 mismatch for rid=" << row.rid << "\n";
    std::exit(1);
  }
}

void expect_slot1(const CommitTraceDut &dut, const Row &row, std::uint8_t slot) {
  if (!dut.io_commit_rows_1_valid || dut.io_commit_rows_1_slot != slot ||
      dut.io_commit_rows_1_identity_bid != row.bid ||
      dut.io_commit_rows_1_identity_gid != row.gid ||
      dut.io_commit_rows_1_identity_rid != row.rid ||
      dut.io_commit_rows_1_blockBid != row.block_bid ||
      dut.io_commit_rows_1_pc != row.pc ||
      dut.io_commit_rows_1_insn != row.insn ||
      dut.io_commit_rows_1_len != row.len ||
      dut.io_commit_rows_1_wb_valid != row.wb_valid ||
      dut.io_commit_rows_1_wb_reg != row.wb_rd ||
      dut.io_commit_rows_1_wb_data != row.wb_data ||
      dut.io_commit_rows_1_mem_valid != row.mem_valid ||
      dut.io_commit_rows_1_mem_isStore != row.mem_is_store ||
      dut.io_commit_rows_1_mem_addr != row.mem_addr ||
      dut.io_commit_rows_1_mem_wdata != row.mem_wdata ||
      dut.io_commit_rows_1_mem_size != row.mem_size ||
      dut.io_commit_rows_1_trap_valid != row.trap_valid ||
      dut.io_commit_rows_1_trap_cause != row.trap_cause ||
      dut.io_commit_rows_1_trap_arg0 != row.trap_arg0 ||
      dut.io_commit_rows_1_nextPc != row.next_pc) {
    std::cerr << "slot1 mismatch for rid=" << row.rid << "\n";
    std::exit(1);
  }
}

CommitTraceJsonRow to_json_row(const Row &row) {
  CommitTraceJsonRow json;
  json.seq = row.seq;
  json.cycle = row.cycle;
  json.bid = row.bid;
  json.gid = row.gid;
  json.rid = row.rid;
  json.block_bid_valid = true;
  json.block_bid = row.block_bid;
  json.pc = row.pc;
  json.insn = row.insn;
  json.len = row.len;
  json.wb_valid = row.wb_valid;
  json.wb_rd = row.wb_rd;
  json.wb_data = row.wb_data;
  json.src0_valid = row.src0_valid;
  json.src0_reg = row.src0_reg;
  json.src0_data = row.src0_data;
  json.src1_valid = row.src1_valid;
  json.src1_reg = row.src1_reg;
  json.src1_data = row.src1_data;
  json.dst_valid = row.dst_valid;
  json.dst_reg = row.dst_reg;
  json.dst_data = row.dst_data;
  json.mem_valid = row.mem_valid;
  json.mem_is_store = row.mem_is_store;
  json.mem_addr = row.mem_addr;
  json.mem_wdata = row.mem_wdata;
  json.mem_rdata = row.mem_rdata;
  json.mem_size = row.mem_size;
  json.trap_valid = row.trap_valid;
  json.trap_cause = row.trap_cause;
  json.trap_arg0 = row.trap_arg0;
  json.next_pc = row.next_pc;
  return json;
}

CommitTraceJsonRow dut_slot0_to_json(const CommitTraceDut &dut) {
  CommitTraceJsonRow row;
  row.valid = dut.io_commit_rows_0_valid;
  row.seq = dut.io_commit_rows_0_seq;
  row.cycle = dut.io_commit_rows_0_cycle;
  row.slot = dut.io_commit_rows_0_slot;
  row.bid = dut.io_commit_rows_0_identity_bid;
  row.gid = dut.io_commit_rows_0_identity_gid;
  row.rid = dut.io_commit_rows_0_identity_rid;
  row.rob_valid = dut.io_commit_rows_0_rob_valid;
  row.rob_wrap = dut.io_commit_rows_0_rob_wrap;
  row.rob_value = dut.io_commit_rows_0_rob_value;
  row.block_bid_valid = dut.io_commit_rows_0_blockBidValid;
  row.block_bid = dut.io_commit_rows_0_blockBid;
  row.pc = dut.io_commit_rows_0_pc;
  row.insn = dut.io_commit_rows_0_insn;
  row.len = dut.io_commit_rows_0_len;
  row.wb_valid = dut.io_commit_rows_0_wb_valid;
  row.wb_rd = dut.io_commit_rows_0_wb_reg;
  row.wb_data = dut.io_commit_rows_0_wb_data;
  row.src0_valid = dut.io_commit_rows_0_src0_valid;
  row.src0_reg = dut.io_commit_rows_0_src0_reg;
  row.src0_data = dut.io_commit_rows_0_src0_data;
  row.src1_valid = dut.io_commit_rows_0_src1_valid;
  row.src1_reg = dut.io_commit_rows_0_src1_reg;
  row.src1_data = dut.io_commit_rows_0_src1_data;
  row.dst_valid = dut.io_commit_rows_0_dst_valid;
  row.dst_reg = dut.io_commit_rows_0_dst_reg;
  row.dst_data = dut.io_commit_rows_0_dst_data;
  row.mem_valid = dut.io_commit_rows_0_mem_valid;
  row.mem_is_store = dut.io_commit_rows_0_mem_isStore;
  row.mem_addr = dut.io_commit_rows_0_mem_addr;
  row.mem_wdata = dut.io_commit_rows_0_mem_wdata;
  row.mem_rdata = dut.io_commit_rows_0_mem_rdata;
  row.mem_size = dut.io_commit_rows_0_mem_size;
  row.trap_valid = dut.io_commit_rows_0_trap_valid;
  row.trap_cause = dut.io_commit_rows_0_trap_cause;
  row.trap_arg0 = dut.io_commit_rows_0_trap_arg0;
  row.next_pc = dut.io_commit_rows_0_nextPc;
  return row;
}

CommitTraceJsonRow dut_slot1_to_json(const CommitTraceDut &dut) {
  CommitTraceJsonRow row;
  row.valid = dut.io_commit_rows_1_valid;
  row.seq = dut.io_commit_rows_1_seq;
  row.cycle = dut.io_commit_rows_1_cycle;
  row.slot = dut.io_commit_rows_1_slot;
  row.bid = dut.io_commit_rows_1_identity_bid;
  row.gid = dut.io_commit_rows_1_identity_gid;
  row.rid = dut.io_commit_rows_1_identity_rid;
  row.rob_valid = dut.io_commit_rows_1_rob_valid;
  row.rob_wrap = dut.io_commit_rows_1_rob_wrap;
  row.rob_value = dut.io_commit_rows_1_rob_value;
  row.block_bid_valid = dut.io_commit_rows_1_blockBidValid;
  row.block_bid = dut.io_commit_rows_1_blockBid;
  row.pc = dut.io_commit_rows_1_pc;
  row.insn = dut.io_commit_rows_1_insn;
  row.len = dut.io_commit_rows_1_len;
  row.wb_valid = dut.io_commit_rows_1_wb_valid;
  row.wb_rd = dut.io_commit_rows_1_wb_reg;
  row.wb_data = dut.io_commit_rows_1_wb_data;
  row.src0_valid = dut.io_commit_rows_1_src0_valid;
  row.src0_reg = dut.io_commit_rows_1_src0_reg;
  row.src0_data = dut.io_commit_rows_1_src0_data;
  row.src1_valid = dut.io_commit_rows_1_src1_valid;
  row.src1_reg = dut.io_commit_rows_1_src1_reg;
  row.src1_data = dut.io_commit_rows_1_src1_data;
  row.dst_valid = dut.io_commit_rows_1_dst_valid;
  row.dst_reg = dut.io_commit_rows_1_dst_reg;
  row.dst_data = dut.io_commit_rows_1_dst_data;
  row.mem_valid = dut.io_commit_rows_1_mem_valid;
  row.mem_is_store = dut.io_commit_rows_1_mem_isStore;
  row.mem_addr = dut.io_commit_rows_1_mem_addr;
  row.mem_wdata = dut.io_commit_rows_1_mem_wdata;
  row.mem_rdata = dut.io_commit_rows_1_mem_rdata;
  row.mem_size = dut.io_commit_rows_1_mem_size;
  row.trap_valid = dut.io_commit_rows_1_trap_valid;
  row.trap_cause = dut.io_commit_rows_1_trap_cause;
  row.trap_arg0 = dut.io_commit_rows_1_trap_arg0;
  row.next_pc = dut.io_commit_rows_1_nextPc;
  return row;
}

void write_qemu_row(std::ofstream &out, const Row &row) {
  write_qemu_commit_jsonl(out, to_json_row(row));
}

void write_dut_slot0(std::ofstream &out, const CommitTraceDut &dut) {
  write_dut_commit_jsonl(out, dut_slot0_to_json(dut));
}

void write_dut_slot1(std::ofstream &out, const CommitTraceDut &dut) {
  write_dut_commit_jsonl(out, dut_slot1_to_json(dut));
}

void run_builtin_smoke(CommitTraceDut &dut, std::ofstream &dut_out, std::ofstream &qemu_out) {
  const std::vector<Row> rows{row0(), row1(), row2()};
  alloc_row(dut, rows[0]);
  alloc_row(dut, rows[1]);
  expect_duplicate_rejected(dut, rows[1]);
  alloc_row(dut, rows[2]);

  complete_slot(dut, 2);
  complete_slot(dut, 1);
  complete_slot(dut, 0);

  eval(dut);
  expect_slot0(dut, rows[0], 0);
  expect_slot1(dut, rows[1], 1);
  expect_monitor_clean(dut, "first retire window", 0x3, 2);
  write_dut_slot0(dut_out, dut);
  write_dut_slot1(dut_out, dut);
  write_qemu_row(qemu_out, rows[0]);
  write_qemu_row(qemu_out, rows[1]);

  tick(dut);

  eval(dut);
  expect_slot0(dut, rows[2], 0);
  if (dut.io_commit_rows_1_valid) {
    std::cerr << "slot1 should be invalid after final single-row retire\n";
    std::exit(1);
  }
  expect_monitor_clean(dut, "final single-row retire", 0x1, 1);
  write_dut_slot0(dut_out, dut);
  write_dut_slot1(dut_out, dut);
  write_qemu_row(qemu_out, rows[2]);

  tick(dut);
}

void run_trace_replay(
    CommitTraceDut &dut,
    const std::vector<Row> &rows,
    std::ofstream &dut_out,
    std::ofstream &qemu_out) {
  for (const Row &row : rows) {
    alloc_row(dut, row);
    const auto slot = static_cast<std::uint8_t>(dut.io_headRobValue);
    complete_slot(dut, slot);

    eval(dut);
    expect_slot0(dut, row, 0);
    if (dut.io_commit_rows_1_valid) {
      std::cerr << "trace replay expected one committed row per cycle\n";
      std::exit(1);
    }
    expect_monitor_clean(dut, "trace replay retire", 0x1, 1);
    write_dut_slot0(dut_out, dut);
    write_dut_slot1(dut_out, dut);
    write_qemu_row(qemu_out, row);

    tick(dut);
    eval(dut);
  }
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const Args args = parse_args(argc, argv);

  CommitTraceDut dut;
  reset(dut);

  std::ofstream dut_out(args.dut_trace);
  std::ofstream qemu_out(args.qemu_trace);
  if (!dut_out || !qemu_out) {
    std::cerr << "failed to open output traces\n";
    return 2;
  }

  if (args.input_trace.empty()) {
    run_builtin_smoke(dut, dut_out, qemu_out);
  } else {
    run_trace_replay(dut, load_trace_rows(args.input_trace, args.max_rows), dut_out, qemu_out);
  }

  eval(dut);
  if (!dut.io_empty || dut.io_size != 0) {
    std::cerr << "ROB did not drain after trace smoke\n";
    return 1;
  }
  expect_monitor_clean(dut, "post-drain idle window", 0x0, 0);

  dut.final();
  return 0;
}
