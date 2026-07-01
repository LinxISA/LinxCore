#include "VLinxCoreFrontendFetchRfAluTraceTop.h"
#include "verilated.h"

#include "commit_trace_jsonl.h"

#include <cerrno>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace {

using linxcore::chisel::CommitTraceJsonRow;
using linxcore::chisel::write_dut_commit_jsonl;
using linxcore::chisel::write_qemu_commit_jsonl;

constexpr int kDenseRowDrainCycles = 64;

struct Args {
  std::string dut_trace;
  std::string qemu_trace;
  std::string memory_bin;
  std::string memory_hex;
  std::string expected_rows;
  std::uint64_t memory_base = 0x1000;
};

struct ExpectedRow {
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 4;
  std::uint64_t next_pc = 0;
  bool skip = false;
  std::string skip_kind;
  bool block_boundary = false;
  bool block_stop = false;
  bool loop_reentry = false;
  std::uint64_t loop_reentry_from_pc = 0;
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
};

struct ObservedRow {
  bool valid = false;
  std::uint64_t seq = 0;
  std::uint64_t cycle = 0;
  std::uint8_t slot = 0;
  std::uint32_t bid = 0;
  std::uint32_t gid = 0;
  std::uint32_t rid = 0;
  bool rob_valid = false;
  bool rob_wrap = false;
  std::uint8_t rob_value = 0;
  bool block_bid_valid = false;
  std::uint64_t block_bid = 0;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 0;
  bool wb_valid = false;
  std::uint8_t wb_reg = 0;
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
  bool rf_write_valid = false;
  std::uint8_t rf_write_tag = 0;
  std::uint64_t rf_write_data = 0;
  std::uint8_t src_phys_valid_mask = 0;
  std::uint8_t src_phys_tag0 = 0;
  std::uint8_t src_phys_tag1 = 0;
  std::uint8_t src_phys_tag2 = 0;
};

struct IssueDebug {
  std::uint8_t src_valid_mask = 0;
  std::uint8_t src_phys_tag0 = 0;
  std::uint8_t src_phys_tag1 = 0;
  std::uint8_t src_phys_tag2 = 0;
};

struct PhysWriter {
  bool valid = false;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t wb_reg = 0;
  std::uint64_t data = 0;
};

std::map<std::uint64_t, IssueDebug> g_issue_debug_by_pc;
std::map<std::uint8_t, PhysWriter> g_phys_writer_by_tag;

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0
            << " --dut-trace <dut.jsonl> --qemu-trace <qemu.jsonl>"
            << " [--expected-rows <rows.jsonl>]"
            << " [--memory-bin <program.bin> --memory-base <addr>]"
            << " [--memory-hex <sparse.mem>]\n";
  std::exit(2);
}

std::uint64_t parse_u64_arg(const std::string &value, const std::string &name) {
  errno = 0;
  char *end = nullptr;
  const unsigned long long parsed = std::strtoull(value.c_str(), &end, 0);
  if (errno != 0 || end == value.c_str() || *end != '\0') {
    std::cerr << "invalid " << name << ": " << value << "\n";
    std::exit(2);
  }
  return static_cast<std::uint64_t>(parsed);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--dut-trace" && i + 1 < argc) {
      args.dut_trace = argv[++i];
    } else if (arg == "--qemu-trace" && i + 1 < argc) {
      args.qemu_trace = argv[++i];
    } else if (arg == "--memory-bin" && i + 1 < argc) {
      args.memory_bin = argv[++i];
    } else if (arg == "--memory-hex" && i + 1 < argc) {
      args.memory_hex = argv[++i];
    } else if (arg == "--expected-rows" && i + 1 < argc) {
      args.expected_rows = argv[++i];
    } else if (arg == "--memory-base" && i + 1 < argc) {
      args.memory_base = parse_u64_arg(argv[++i], "--memory-base");
    } else {
      usage(argv[0]);
    }
  }
  if (args.dut_trace.empty() || args.qemu_trace.empty()) {
    usage(argv[0]);
  }
  if (!args.memory_bin.empty() && !args.memory_hex.empty()) {
    std::cerr << "--memory-bin and --memory-hex are mutually exclusive\n";
    usage(argv[0]);
  }
  return args;
}

std::string trim_copy(const std::string &value) {
  const auto first = value.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) {
    return "";
  }
  const auto last = value.find_last_not_of(" \t\r\n");
  return value.substr(first, last - first + 1);
}

bool json_value_token(const std::string &line, const std::string &key, std::string &token) {
  const std::string quoted_key = "\"" + key + "\"";
  const std::size_t key_pos = line.find(quoted_key);
  if (key_pos == std::string::npos) {
    return false;
  }
  std::size_t pos = key_pos + quoted_key.size();
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    ++pos;
  }
  if (pos >= line.size() || line[pos] != ':') {
    return false;
  }
  ++pos;
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    ++pos;
  }
  if (pos >= line.size()) {
    return false;
  }

  if (line[pos] == '"') {
    ++pos;
    std::string value;
    bool escaped = false;
    for (; pos < line.size(); ++pos) {
      const char ch = line[pos];
      if (escaped) {
        value.push_back(ch);
        escaped = false;
        continue;
      }
      if (ch == '\\') {
        escaped = true;
        continue;
      }
      if (ch == '"') {
        token = trim_copy(value);
        return true;
      }
      value.push_back(ch);
    }
    return false;
  }

  const std::size_t end = line.find_first_of(",}", pos);
  if (end == std::string::npos) {
    return false;
  }
  token = trim_copy(line.substr(pos, end - pos));
  return !token.empty();
}

bool json_has_key(const std::string &line, const std::string &key) {
  std::string ignored;
  return json_value_token(line, key, ignored);
}

std::uint64_t json_u64(
    const std::string &line,
    const std::string &key,
    std::uint64_t default_value,
    const std::string &context) {
  std::string token;
  if (!json_value_token(line, key, token)) {
    return default_value;
  }
  return parse_u64_arg(token, context + "." + key);
}

std::uint8_t json_u8(
    const std::string &line,
    const std::string &key,
    std::uint8_t default_value,
    const std::string &context) {
  const std::uint64_t value = json_u64(line, key, default_value, context);
  if (value > 0xffU) {
    std::cerr << "expected row field out of uint8 range: "
              << context << "." << key << "=" << value << "\n";
    std::exit(2);
  }
  return static_cast<std::uint8_t>(value);
}

bool json_bool(
    const std::string &line,
    const std::string &key,
    bool default_value,
    const std::string &context) {
  std::string token;
  if (!json_value_token(line, key, token)) {
    return default_value;
  }
  for (char &ch : token) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  if (token == "true" || token == "yes" || token == "1") {
    return true;
  }
  if (token == "false" || token == "no" || token == "0") {
    return false;
  }
  const std::uint64_t numeric = parse_u64_arg(token, context + "." + key);
  return numeric != 0;
}

ExpectedRow parse_expected_row_jsonl(
    const std::string &line,
    const std::string &path,
    std::uint64_t line_no) {
  const std::string context = path + ":" + std::to_string(line_no);
  if (!json_has_key(line, "pc") || !json_has_key(line, "insn")) {
    std::cerr << "expected row is missing pc or insn at " << context << "\n";
    std::exit(2);
  }

  ExpectedRow row;
  row.pc = json_u64(line, "pc", 0, context);
  row.insn = json_u64(line, "insn", 0, context);
  row.len = json_u8(line, "len", 4, context);
  if (row.len != 2 && row.len != 4 && row.len != 6 && row.len != 8) {
    std::cerr << "expected row has unsupported instruction length at "
              << context << " len=" << static_cast<unsigned>(row.len) << "\n";
    std::exit(2);
  }
  row.skip = json_bool(line, "skip", false, context);
  (void)json_value_token(line, "skip_kind", row.skip_kind);
  row.block_boundary = json_bool(line, "block_boundary", false, context);
  row.block_stop = json_bool(line, "block_stop", false, context);
  row.loop_reentry = json_bool(line, "loop_reentry", false, context);
  row.loop_reentry_from_pc = json_u64(line, "loop_reentry_from_pc", 0, context);

  row.src0_valid = json_bool(line, "src0_valid", false, context);
  row.src0_reg = json_u8(line, "src0_reg", 0, context);
  row.src0_data = json_u64(line, "src0_data", 0, context);
  row.src1_valid = json_bool(line, "src1_valid", false, context);
  row.src1_reg = json_u8(line, "src1_reg", 0, context);
  row.src1_data = json_u64(line, "src1_data", 0, context);

  const bool has_dst_valid = json_has_key(line, "dst_valid");
  const bool has_dst_reg = json_has_key(line, "dst_reg");
  const bool has_dst_data = json_has_key(line, "dst_data");
  row.dst_valid = json_bool(line, "dst_valid", json_bool(line, "wb_valid", false, context), context);
  row.dst_reg = json_u8(line, "dst_reg", json_u8(line, "wb_rd", 0, context), context);
  row.dst_data = json_u64(line, "dst_data", json_u64(line, "wb_data", 0, context), context);
  if (!has_dst_valid && json_has_key(line, "wb_valid")) {
    row.dst_valid = json_bool(line, "wb_valid", false, context);
  }
  if (!has_dst_reg && json_has_key(line, "wb_rd")) {
    row.dst_reg = json_u8(line, "wb_rd", 0, context);
  }
  if (!has_dst_data && json_has_key(line, "wb_data")) {
    row.dst_data = json_u64(line, "wb_data", 0, context);
  }
  row.mem_valid = json_bool(line, "mem_valid", false, context);
  row.mem_is_store = json_bool(line, "mem_is_store", false, context);
  row.mem_addr = json_u64(line, "mem_addr", 0, context);
  row.mem_wdata = json_u64(line, "mem_wdata", 0, context);
  row.mem_rdata = json_u64(line, "mem_rdata", 0, context);
  row.mem_size = json_u8(line, "mem_size", 0, context);
  row.next_pc = json_u64(line, "next_pc", row.pc + row.len, context);
  return row;
}

std::vector<ExpectedRow> load_expected_rows_jsonl(const std::string &path) {
  std::ifstream in(path);
  if (!in) {
    std::cerr << "failed to open expected rows: " << path
              << " error=" << std::strerror(errno) << "\n";
    std::exit(2);
  }

  std::vector<ExpectedRow> rows;
  std::string line;
  std::uint64_t line_no = 0;
  while (std::getline(in, line)) {
    ++line_no;
    line = trim_copy(line);
    if (line.empty() || line[0] == '#') {
      continue;
    }
    if (line.find("\"type\"") != std::string::npos &&
        line.find("\"META\"") != std::string::npos) {
      continue;
    }
    if (json_has_key(line, "valid") &&
        !json_bool(line, "valid", true, path + ":" + std::to_string(line_no))) {
      continue;
    }
    rows.push_back(parse_expected_row_jsonl(line, path, line_no));
  }
  if (rows.empty()) {
    std::cerr << "expected row stream is empty: " << path << "\n";
    std::exit(2);
  }
  return rows;
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
  dut.io_fetchReqReady = 0;
  dut.io_fetchRespValid = 0;
  dut.io_fetchRespWindow = 0;
  dut.io_rfInitValid = 0;
  dut.io_rfInitArchTag = 0;
  dut.io_rfInitData = 0;
  dut.io_deallocReady = 1;
  dut.io_loadLookupData = 0;
}

void tick(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

void reset(VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
}

void init_rf(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::uint8_t arch_tag, std::uint64_t data) {
  clear_inputs(dut);
  dut.io_rfInitValid = 1;
  dut.io_rfInitArchTag = arch_tag;
  dut.io_rfInitData = data;
  tick(dut);
  clear_inputs(dut);
  dut.eval();
}

std::map<std::uint8_t, std::uint64_t> initial_rf_preloads(const std::vector<ExpectedRow> &rows) {
  std::map<std::uint8_t, std::uint64_t> preloads;
  std::map<std::uint8_t, bool> produced;

  const auto observe_source = [&](bool valid, std::uint8_t reg, std::uint64_t data) {
    if (!valid || produced[reg]) {
      return;
    }
    const auto [it, inserted] = preloads.emplace(reg, data);
    if (!inserted && it->second != data) {
      std::cerr << "expected rows require conflicting initial RF data"
                << " reg=" << static_cast<unsigned>(reg)
                << " first=" << it->second
                << " later=" << data << "\n";
      std::exit(2);
    }
  };

  for (const ExpectedRow &row : rows) {
    if (row.skip) {
      continue;
    }
    observe_source(row.src0_valid, row.src0_reg, row.src0_data);
    observe_source(row.src1_valid, row.src1_reg, row.src1_data);
    if (row.dst_valid && row.dst_reg < 24) {
      produced[row.dst_reg] = true;
    }
  }
  return preloads;
}

std::uint64_t mask_insn(std::uint64_t insn, std::uint8_t len) {
  if (len == 2) {
    return insn & 0xffffULL;
  }
  if (len == 4) {
    return insn & 0xffff'ffffULL;
  }
  if (len == 6) {
    return insn & 0xffff'ffff'ffffULL;
  }
  return insn;
}

class FetchMemoryImage {
public:
  void store_byte(std::uint64_t addr, std::uint8_t value) {
    bytes_[addr] = value;
  }

  void load_binary(const std::string &path, std::uint64_t base) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
      std::cerr << "failed to open fetch memory image: " << path
                << " error=" << std::strerror(errno) << "\n";
      std::exit(2);
    }

    char ch = 0;
    std::uint64_t offset = 0;
    while (in.get(ch)) {
      store_byte(base + offset, static_cast<std::uint8_t>(static_cast<unsigned char>(ch)));
      ++offset;
    }
    if (offset == 0) {
      std::cerr << "fetch memory image is empty: " << path << "\n";
      std::exit(2);
    }
  }

  void load_sparse_hex(const std::string &path) {
    std::ifstream in(path);
    if (!in) {
      std::cerr << "failed to open sparse fetch memory image: " << path
                << " error=" << std::strerror(errno) << "\n";
      std::exit(2);
    }

    std::string line;
    std::uint64_t loaded = 0;
    std::uint64_t line_no = 0;
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
        std::cerr << "invalid sparse fetch memory line"
                  << " path=" << path
                  << " line=" << line_no << "\n";
        std::exit(2);
      }

      const std::uint64_t addr =
          parse_u64_arg(addr_token, "sparse memory address at " + path + ":" + std::to_string(line_no));
      const std::uint64_t byte =
          parse_u64_arg(byte_token, "sparse memory byte at " + path + ":" + std::to_string(line_no));
      if (byte > 0xffU) {
        std::cerr << "sparse fetch memory byte out of range"
                  << " path=" << path
                  << " line=" << line_no
                  << " value=0x" << std::hex << byte << std::dec << "\n";
        std::exit(2);
      }
      store_byte(addr, static_cast<std::uint8_t>(byte));
      ++loaded;
    }

    if (loaded == 0) {
      std::cerr << "sparse fetch memory image is empty: " << path << "\n";
      std::exit(2);
    }
  }

  std::uint64_t read_window(std::uint64_t pc) const {
    std::uint64_t window = 0;
    for (std::uint8_t byte_index = 0; byte_index < 8; ++byte_index) {
      std::uint8_t byte = 0xffU;
      if (!read_byte(pc + byte_index, byte) && byte_index == 0) {
        std::cerr << "fetch memory image missing first byte"
                  << " pc=0x" << std::hex << pc << std::dec << "\n";
        std::exit(1);
      }
      window |= static_cast<std::uint64_t>(byte) << (static_cast<unsigned>(byte_index) * 8U);
    }
    return window;
  }

  std::uint64_t read_u64_or_zero(std::uint64_t addr) const {
    std::uint64_t value = 0;
    for (std::uint8_t byte_index = 0; byte_index < 8; ++byte_index) {
      std::uint8_t byte = 0;
      (void)read_byte(addr + byte_index, byte);
      value |= static_cast<std::uint64_t>(byte) << (static_cast<unsigned>(byte_index) * 8U);
    }
    return value;
  }

  void store_u64(std::uint64_t addr, std::uint64_t value) {
    for (std::uint8_t byte_index = 0; byte_index < 8; ++byte_index) {
      store_byte(
          addr + byte_index,
          static_cast<std::uint8_t>((value >> (static_cast<unsigned>(byte_index) * 8U)) & 0xffU));
    }
  }

  static FetchMemoryImage from_rows(const std::vector<ExpectedRow> &rows) {
    FetchMemoryImage image;
    for (const ExpectedRow &row : rows) {
      const std::uint64_t insn = mask_insn(row.insn, row.len);
      for (std::uint8_t byte_index = 0; byte_index < row.len; ++byte_index) {
        image.store_byte(
            row.pc + byte_index,
            static_cast<std::uint8_t>((insn >> (static_cast<unsigned>(byte_index) * 8U)) & 0xffU));
      }
    }
    return image;
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

void eval_with_load_lookup(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const FetchMemoryImage &fetch_memory) {
  dut.eval();
  if (dut.io_loadLookupValid) {
    dut.io_loadLookupData = fetch_memory.read_u64_or_zero(dut.io_loadLookupAddr);
    dut.eval();
  }
  if (dut.io_executeAccepted && dut.io_issueQueueHeadValid) {
    IssueDebug debug;
    debug.src_valid_mask = dut.io_issueQueueHeadSrcValidMask;
    debug.src_phys_tag0 = dut.io_issueQueueHeadSrcPhysTag_0;
    debug.src_phys_tag1 = dut.io_issueQueueHeadSrcPhysTag_1;
    debug.src_phys_tag2 = dut.io_issueQueueHeadSrcPhysTag_2;
    g_issue_debug_by_pc[dut.io_issueQueueHeadPc] = debug;
  }
  if (dut.io_rfWriteValid) {
    PhysWriter writer;
    writer.valid = true;
    writer.pc = dut.io_executeCompletePc;
    writer.insn = dut.io_executeCompleteInsn;
    writer.wb_reg = dut.io_executeCompleteWbReg;
    writer.data = dut.io_rfWriteData;
    g_phys_writer_by_tag[dut.io_rfWriteTag] = writer;
  }
}

void attach_issue_debug(ObservedRow &row) {
  const auto it = g_issue_debug_by_pc.find(row.pc);
  if (it == g_issue_debug_by_pc.end()) {
    return;
  }
  row.src_phys_valid_mask = it->second.src_valid_mask;
  row.src_phys_tag0 = it->second.src_phys_tag0;
  row.src_phys_tag1 = it->second.src_phys_tag1;
  row.src_phys_tag2 = it->second.src_phys_tag2;
}

void dump_phys_writer_line(const char *label, std::uint8_t tag) {
  const auto it = g_phys_writer_by_tag.find(tag);
  if (it == g_phys_writer_by_tag.end() || !it->second.valid) {
    std::cerr << "frontend fetch RF ALU " << label
              << " tag=" << static_cast<unsigned>(tag)
              << " writer=<none>\n";
    return;
  }
  std::cerr << "frontend fetch RF ALU " << label
            << " tag=" << static_cast<unsigned>(tag)
            << " writer_pc=0x" << std::hex << it->second.pc
            << " writer_insn=0x" << it->second.insn
            << std::dec
            << " writer_wb=" << static_cast<unsigned>(it->second.wb_reg)
            << " writer_data=" << it->second.data << "\n";
}

void expect_monitor_clean(
    const VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const char *context,
    std::uint8_t expected_mask,
    std::uint8_t expected_count) {
  if (dut.io_commitContractError || dut.io_commitSkippedSlot ||
      dut.io_commitDuplicateIdentity || dut.io_commitSlotMismatch ||
      dut.io_commitInvalidSideEffect) {
    std::cerr << "commit monitor error during " << context
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

ObservedRow read_slot0(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  ObservedRow row;
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
  row.wb_reg = dut.io_commit_rows_0_wb_reg;
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
  row.rf_write_valid = dut.io_rfWriteValid;
  row.rf_write_tag = dut.io_rfWriteTag;
  row.rf_write_data = dut.io_rfWriteData;
  row.src_phys_valid_mask = dut.io_executeCompleteSrcPhysValidMask;
  row.src_phys_tag0 = dut.io_executeCompleteSrcPhysTag_0;
  row.src_phys_tag1 = dut.io_executeCompleteSrcPhysTag_1;
  row.src_phys_tag2 = dut.io_executeCompleteSrcPhysTag_2;
  attach_issue_debug(row);
  return row;
}

bool slot1_valid(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  return dut.io_commit_rows_1_valid;
}

ObservedRow read_slot1(const VLinxCoreFrontendFetchRfAluTraceTop &dut) {
  ObservedRow row;
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
  row.wb_reg = dut.io_commit_rows_1_wb_reg;
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
  row.rf_write_valid = dut.io_rfWriteValid;
  row.rf_write_tag = dut.io_rfWriteTag;
  row.rf_write_data = dut.io_rfWriteData;
  row.src_phys_valid_mask = dut.io_executeCompleteSrcPhysValidMask;
  row.src_phys_tag0 = dut.io_executeCompleteSrcPhysTag_0;
  row.src_phys_tag1 = dut.io_executeCompleteSrcPhysTag_1;
  row.src_phys_tag2 = dut.io_executeCompleteSrcPhysTag_2;
  attach_issue_debug(row);
  return row;
}

void collect_commit_if_present(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    std::vector<ObservedRow> &pending,
    const char *context) {
  if (dut.io_executeUnsupported) {
    std::cerr << context << " execute reported unsupported opcode="
              << static_cast<unsigned>(dut.io_executeUnsupportedOpcode) << "\n";
    std::exit(1);
  }
  if (dut.io_executeCompleteValid && dut.io_completeIgnored) {
    std::cerr << context << " execute completion was ignored"
              << " rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
              << "\n";
    std::exit(1);
  }
  if (!dut.io_commit_rows_0_valid) {
    return;
  }
  const ObservedRow slot0 = read_slot0(dut);
  const bool slot1 = slot1_valid(dut);
  expect_monitor_clean(dut, context, slot1 ? 0x3 : 0x1, slot1 ? 2 : 1);
  pending.push_back(slot0);
  if (slot1) {
    pending.push_back(read_slot1(dut));
  }
}

void expect_row(const ObservedRow &observed, const ExpectedRow &expected) {
  if (!observed.valid ||
      observed.slot > 1 ||
      observed.pc != expected.pc ||
      mask_insn(observed.insn, observed.len) != mask_insn(expected.insn, expected.len) ||
      observed.len != expected.len ||
      observed.mem_valid != expected.mem_valid ||
      observed.mem_is_store != expected.mem_is_store ||
      observed.trap_valid ||
      observed.src0_valid != expected.src0_valid ||
      observed.src1_valid != expected.src1_valid ||
      observed.dst_valid != expected.dst_valid ||
      observed.wb_valid != expected.dst_valid ||
      observed.next_pc != expected.next_pc) {
    std::cerr << "frontend fetch RF ALU trace top commit row mismatch"
              << " pc=0x" << std::hex << observed.pc
              << " insn=0x" << observed.insn
              << std::dec << " len=" << static_cast<unsigned>(observed.len)
              << " wb_valid=" << observed.wb_valid
              << " src0=(" << observed.src0_valid << ","
              << static_cast<unsigned>(observed.src0_reg) << ")"
              << " src1=(" << observed.src1_valid << ","
              << static_cast<unsigned>(observed.src1_reg) << ")"
              << " dst=(" << observed.dst_valid << ","
              << static_cast<unsigned>(observed.dst_reg) << ")"
              << " mem=(" << observed.mem_valid << ","
              << observed.mem_is_store << ")"
              << " next_pc=0x" << std::hex << observed.next_pc << std::dec
              << "\n";
    std::exit(1);
  }

  if (observed.src0_valid &&
      (observed.src0_reg != expected.src0_reg || observed.src0_data != expected.src0_data)) {
    std::cerr << "frontend fetch RF ALU trace top src0 mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected=(" << static_cast<unsigned>(expected.src0_reg)
              << "," << expected.src0_data << ") observed=("
              << static_cast<unsigned>(observed.src0_reg)
              << "," << observed.src0_data << ")"
              << " src_phys_mask=0x" << std::hex << static_cast<unsigned>(observed.src_phys_valid_mask)
              << " src_phys=(" << static_cast<unsigned>(observed.src_phys_tag0)
              << "," << static_cast<unsigned>(observed.src_phys_tag1)
              << "," << static_cast<unsigned>(observed.src_phys_tag2) << ")"
              << " rf_write=(" << std::dec << observed.rf_write_valid
              << "," << static_cast<unsigned>(observed.rf_write_tag)
              << "," << observed.rf_write_data << ")\n";
    dump_phys_writer_line("src0 last writer", observed.src_phys_tag0);
    std::exit(1);
  }
  if (observed.src1_valid &&
      (observed.src1_reg != expected.src1_reg || observed.src1_data != expected.src1_data)) {
    std::cerr << "frontend fetch RF ALU trace top src1 mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected=(" << static_cast<unsigned>(expected.src1_reg)
              << "," << expected.src1_data << ") observed=("
              << static_cast<unsigned>(observed.src1_reg)
              << "," << observed.src1_data << ")"
              << " src_phys_mask=0x" << std::hex << static_cast<unsigned>(observed.src_phys_valid_mask)
              << " src_phys=(" << static_cast<unsigned>(observed.src_phys_tag0)
              << "," << static_cast<unsigned>(observed.src_phys_tag1)
              << "," << static_cast<unsigned>(observed.src_phys_tag2) << ")"
              << " rf_write=(" << std::dec << observed.rf_write_valid
              << "," << static_cast<unsigned>(observed.rf_write_tag)
              << "," << observed.rf_write_data << ")\n";
    dump_phys_writer_line("src1 last writer", observed.src_phys_tag1);
    std::exit(1);
  }
  if (observed.dst_valid &&
      (observed.dst_reg != expected.dst_reg || observed.dst_data != expected.dst_data ||
       observed.wb_reg != expected.dst_reg || observed.wb_data != expected.dst_data)) {
    std::cerr << "frontend fetch RF ALU trace top dst/wb mismatch"
              << " expected_pc=0x" << std::hex << expected.pc
              << " observed_pc=0x" << observed.pc
              << " expected_insn=0x" << mask_insn(expected.insn, expected.len)
              << " observed_insn=0x" << mask_insn(observed.insn, observed.len)
              << std::dec
              << " expected=(" << static_cast<unsigned>(expected.dst_reg)
              << "," << expected.dst_data << ") observed_dst=("
              << static_cast<unsigned>(observed.dst_reg)
              << "," << observed.dst_data << ") observed_wb=("
              << static_cast<unsigned>(observed.wb_reg)
              << "," << observed.wb_data << ")\n";
    std::exit(1);
  }
  if (observed.mem_valid &&
      (observed.mem_addr != expected.mem_addr ||
       observed.mem_wdata != expected.mem_wdata ||
       observed.mem_rdata != expected.mem_rdata ||
       observed.mem_size != expected.mem_size)) {
    std::cerr << "frontend fetch RF ALU trace top mem mismatch"
              << " expected=(addr=0x" << std::hex << expected.mem_addr
              << ",wdata=0x" << expected.mem_wdata
              << ",rdata=0x" << expected.mem_rdata
              << std::dec << ",size=" << static_cast<unsigned>(expected.mem_size)
              << ") observed=(addr=0x" << std::hex << observed.mem_addr
              << ",wdata=0x" << observed.mem_wdata
              << ",rdata=0x" << observed.mem_rdata
              << std::dec << ",size=" << static_cast<unsigned>(observed.mem_size)
              << ")\n";
    std::exit(1);
  }
}

CommitTraceJsonRow to_json_row(const ObservedRow &row) {
  CommitTraceJsonRow json;
  json.valid = row.valid;
  json.seq = row.seq;
  json.cycle = row.cycle;
  json.slot = row.slot;
  json.bid = row.bid;
  json.gid = row.gid;
  json.rid = row.rid;
  json.rob_valid = row.rob_valid;
  json.rob_wrap = row.rob_wrap;
  json.rob_value = row.rob_value;
  json.block_bid_valid = row.block_bid_valid;
  json.block_bid = row.block_bid;
  json.pc = row.pc;
  json.insn = row.insn;
  json.len = row.len;
  json.wb_valid = row.wb_valid;
  json.wb_rd = row.wb_reg;
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

CommitTraceJsonRow to_json_row(const ExpectedRow &row) {
  CommitTraceJsonRow json;
  json.pc = row.pc;
  json.insn = mask_insn(row.insn, row.len);
  json.len = row.len;
  json.wb_valid = row.dst_valid;
  json.wb_rd = row.dst_reg;
  json.wb_data = row.dst_data;
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
  json.next_pc = row.next_pc;
  return json;
}

void write_dut_row(std::ofstream &out, const ObservedRow &row) {
  write_dut_commit_jsonl(out, to_json_row(row));
  out.flush();
}

void write_qemu_row(std::ofstream &out, const ExpectedRow &row) {
  write_qemu_commit_jsonl(out, to_json_row(row));
  out.flush();
}

void start_source(VLinxCoreFrontendFetchRfAluTraceTop &dut, std::uint64_t pc) {
  clear_inputs(dut);
  dut.io_startValid = 1;
  dut.io_startPc = pc;
  tick(dut);
  clear_inputs(dut);
  dut.eval();
  if (!dut.io_sourceActive) {
    std::cerr << "frontend fetch RF ALU source did not arm at start pc=0x"
              << std::hex << pc << std::dec << "\n";
    std::exit(1);
  }
}

std::size_t dense_window_end(const std::vector<ExpectedRow> &rows, std::size_t start) {
  const std::uint64_t start_pc = rows.at(start).pc;
  std::uint64_t next_pc = start_pc;
  std::size_t end = start;
  while (end < rows.size()) {
    const ExpectedRow &row = rows[end];
    if (row.pc != next_pc) {
      break;
    }
    if ((row.pc + row.len) - start_pc > 8U) {
      break;
    }
    next_pc = row.next_pc;
    ++end;
    if (row.next_pc != row.pc + row.len) {
      break;
    }
  }
  if (end == start) {
    std::cerr << "dense window grouping could not fit first row"
              << " pc=0x" << std::hex << start_pc << std::dec
              << " len=" << static_cast<unsigned>(rows[start].len) << "\n";
    std::exit(1);
  }
  return end;
}

std::uint8_t dense_window_mask(std::size_t count) {
  if (count == 0 || count > 4) {
    std::cerr << "unsupported dense window slot count=" << count << "\n";
    std::exit(1);
  }
  return static_cast<std::uint8_t>((1U << count) - 1U);
}

std::uint8_t dense_window_advance(const std::vector<ExpectedRow> &rows, std::size_t start, std::size_t end) {
  const std::uint64_t start_pc = rows.at(start).pc;
  const ExpectedRow &last = rows.at(end - 1);
  const std::uint64_t advance = (last.pc + last.len) - start_pc;
  if (advance == 0 || advance > 8) {
    std::cerr << "unsupported dense window advance=" << advance << "\n";
    std::exit(1);
  }
  return static_cast<std::uint8_t>(advance);
}

bool row_redirects(const ExpectedRow &row) {
  return row.next_pc != row.pc + row.len;
}

struct BfuBodyGeometryHint {
  bool valid = false;
  std::uint64_t header_pc = 0;
  std::uint64_t hsize_bytes = 0;
  std::uint64_t bsize_bytes = 0;
};

struct BfuGeometryDiagnostics {
  std::uint64_t comparable_count = 0;
  std::uint64_t match_count = 0;
  std::uint64_t resolved_accept_count = 0;
  std::uint64_t cut_arm_comparable_count = 0;
  std::uint64_t cut_arm_accept_count = 0;
  std::uint64_t cut_arm_mismatch_count = 0;
  std::uint64_t local_body_cut_prefix_count = 0;
  std::uint64_t resolved_source_runtime_selected_count = 0;
  std::uint64_t resolved_source_replay_selected_count = 0;
  std::uint64_t resolved_source_runtime_feedback_count = 0;
  std::uint64_t resolved_source_runtime_pending_count = 0;
  std::uint64_t resolved_source_runtime_pending_consume_count = 0;
  std::uint64_t resolved_source_runtime_pending_drop_mismatch_count = 0;
  std::uint64_t resolved_source_runtime_pending_candidate_comparable_count = 0;
  std::uint64_t resolved_source_runtime_pending_candidate_match_count = 0;
  std::uint64_t resolved_source_runtime_pending_candidate_mismatch_count = 0;
  std::uint64_t resolved_source_runtime_replay_comparable_count = 0;
  std::uint64_t resolved_source_runtime_replay_match_count = 0;
  std::uint64_t resolved_source_runtime_replay_mismatch_count = 0;
};

struct FetchDenseWindowResult {
  bool captured_tail_superset = false;
  bool local_body_cut_prefix = false;
  std::size_t captured_slots = 0;
};

void observe_bfu_geometry_diagnostics(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    BfuGeometryDiagnostics &stats,
    const char *context) {
  if (dut.io_reducedBfuStaticExternalMismatch ||
      dut.io_reducedBfuStaticExternalHeaderMismatch ||
      dut.io_reducedBfuStaticExternalHSizeMismatch ||
      dut.io_reducedBfuStaticExternalBSizeMismatch) {
    std::cerr << "frontend fetch RF ALU BFU static/external geometry mismatch during "
              << context
              << " comparable=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalComparable)
              << " match=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalMatch)
              << " headerMismatch=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalHeaderMismatch)
              << " hsizeMismatch=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalHSizeMismatch)
              << " bsizeMismatch=" << static_cast<unsigned>(dut.io_reducedBfuStaticExternalBSizeMismatch)
              << "\n";
    std::exit(1);
  }
  if (dut.io_reducedBfuResolvedBodyEndHeaderMismatch ||
      dut.io_reducedBfuResolvedBodyEndInactiveDrop ||
      dut.io_reducedBfuResolvedBodyEndFlushDrop ||
      dut.io_reducedBfuResolvedBodyEndUnderflow) {
    std::cerr << "frontend fetch RF ALU BFU resolved body-end owner rejected replay geometry during "
              << context
              << " accepted=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndAccepted)
              << " headerMismatch=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndHeaderMismatch)
              << " inactiveDrop=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndInactiveDrop)
              << " flushDrop=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndFlushDrop)
              << " underflow=" << static_cast<unsigned>(dut.io_reducedBfuResolvedBodyEndUnderflow)
              << "\n";
    std::exit(1);
  }
  if (dut.io_reducedBfuResolvedBodyEndAccepted) {
    ++stats.resolved_accept_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeSelected) {
    ++stats.resolved_source_runtime_selected_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceReplaySelected) {
    ++stats.resolved_source_replay_selected_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeFeedbackFire) {
    ++stats.resolved_source_runtime_feedback_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePending) {
    ++stats.resolved_source_runtime_pending_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingConsumeFire) {
    ++stats.resolved_source_runtime_pending_consume_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingDropMismatch) {
    ++stats.resolved_source_runtime_pending_drop_mismatch_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingCandidateComparable) {
    ++stats.resolved_source_runtime_pending_candidate_comparable_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMatch) {
    ++stats.resolved_source_runtime_pending_candidate_match_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimePendingCandidateMismatch) {
    ++stats.resolved_source_runtime_pending_candidate_mismatch_count;
  }
  if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeReplayComparable) {
    ++stats.resolved_source_runtime_replay_comparable_count;
    if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeReplayMismatch) {
      ++stats.resolved_source_runtime_replay_mismatch_count;
    }
    if (dut.io_reducedBfuResolvedBodyEndSourceRuntimeReplayMatch) {
      ++stats.resolved_source_runtime_replay_match_count;
    }
  }
  if (dut.io_reducedBfuBodyCutArmComparable) {
    ++stats.cut_arm_comparable_count;
    if (dut.io_reducedBfuBodyCutArmMismatch ||
        dut.io_reducedBfuBodyCutArmHeaderMismatch ||
        dut.io_reducedBfuBodyCutArmHSizeMismatch ||
        dut.io_reducedBfuBodyCutArmBSizeMismatch) {
      ++stats.cut_arm_mismatch_count;
    }
  }
  if (dut.io_reducedBfuBodyCutArmAccepted) {
    ++stats.cut_arm_accept_count;
  }
  if (dut.io_reducedBfuStaticExternalComparable) {
    ++stats.comparable_count;
    if (!dut.io_reducedBfuStaticExternalMatch) {
      std::cerr << "frontend fetch RF ALU BFU geometry was comparable without a match during "
                << context << "\n";
      std::exit(1);
    }
    ++stats.match_count;
  }
}

BfuBodyGeometryHint dense_window_bfu_body_geometry(const std::vector<ExpectedRow> &rows, std::size_t start, std::size_t end) {
  BfuBodyGeometryHint hint;
  if (end <= start || end >= rows.size()) {
    return hint;
  }
  const ExpectedRow &next = rows[end];
  if (!next.loop_reentry || next.loop_reentry_from_pc == 0) {
    return hint;
  }
  const std::uint64_t cut_pc = rows[end - 1].pc + rows[end - 1].len;
  if (next.loop_reentry_from_pc != cut_pc) {
    std::cerr << "loop re-entry metadata does not match dense window cut"
              << " start_pc=0x" << std::hex << rows[start].pc
              << " expected_cut=0x" << cut_pc
              << " row_cut=0x" << next.loop_reentry_from_pc
              << " restart=0x" << next.pc << std::dec << "\n";
    std::exit(1);
  }
  const std::uint64_t header_pc = next.pc;
  const std::uint64_t body_base_pc = header_pc + 2;
  if (cut_pc < body_base_pc) {
    std::cerr << "loop re-entry metadata cuts before reduced BFU body base"
              << " header_pc=0x" << std::hex << header_pc
              << " body_base=0x" << body_base_pc
              << " cut=0x" << cut_pc << std::dec << "\n";
    std::exit(1);
  }
  hint.valid = true;
  hint.header_pc = header_pc;
  hint.hsize_bytes = 0;
  hint.bsize_bytes = cut_pc - body_base_pc;
  return hint;
}

void drive_bfu_body_geometry_hint(VLinxCoreFrontendFetchRfAluTraceTop &dut, const BfuBodyGeometryHint &hint) {
  if (!hint.valid) {
    return;
  }
  dut.io_reducedBfuBodyValid = 1;
  dut.io_reducedBfuHeaderPc = hint.header_pc;
  dut.io_reducedBfuHSizeBytes = hint.hsize_bytes;
  dut.io_reducedBfuBSizeBytes = hint.bsize_bytes;
}

bool is_suppressed_local_body_cut_marker(const std::vector<ExpectedRow> &rows, std::size_t index) {
  if (index + 1 >= rows.size()) {
    return false;
  }
  const ExpectedRow &row = rows[index];
  return row.skip &&
         row.block_boundary &&
         !row.block_stop &&
         row_redirects(row) &&
         row.next_pc < row.pc &&
         rows[index + 1].pc == row.next_pc;
}

FetchDenseWindowResult fetch_dense_window(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const std::vector<ExpectedRow> &rows,
    std::size_t start,
    std::size_t end,
    const FetchMemoryImage &fetch_memory,
    std::vector<ObservedRow> &pending_commits,
    BfuGeometryDiagnostics &bfu_stats) {
  const ExpectedRow &first = rows.at(start);
  const std::size_t slot_count = end - start;
  const std::uint8_t expected_mask = dense_window_mask(slot_count);
  const std::uint8_t expected_advance = dense_window_advance(rows, start, end);
  const bool redirect_tail = row_redirects(rows.at(end - 1));
  const bool capture_tail = end == rows.size();
  const BfuBodyGeometryHint body_cut = dense_window_bfu_body_geometry(rows, start, end);
  const std::uint64_t bfu_match_count_before = bfu_stats.match_count;

  for (int cycle = 0; cycle < 8; ++cycle) {
    clear_inputs(dut);
    dut.io_fetchReqReady = 1;
    drive_bfu_body_geometry_hint(dut, body_cut);
    eval_with_load_lookup(dut, fetch_memory);
    observe_bfu_geometry_diagnostics(dut, bfu_stats, "fetch request");
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU fetch");
    if (dut.io_fetchReqValid) {
      if (dut.io_fetchReqPc != first.pc || !dut.io_sourceReqFire) {
        std::cerr << "frontend fetch RF ALU request mismatch"
                  << " expected_pc=0x" << std::hex << first.pc
                  << " observed_pc=0x" << dut.io_fetchReqPc << std::dec
                  << " sourceReqFire=" << static_cast<unsigned>(dut.io_sourceReqFire)
                  << "\n";
        std::exit(1);
      }
      tick(dut);
      goto request_done;
    }
    tick(dut);
  }
  std::cerr << "frontend fetch RF ALU source did not request pc=0x"
            << std::hex << first.pc << std::dec << "\n";
  std::exit(1);

request_done:
  clear_inputs(dut);
  dut.io_fetchRespValid = 1;
  dut.io_fetchRespWindow = fetch_memory.read_window(first.pc);
  drive_bfu_body_geometry_hint(dut, body_cut);
  eval_with_load_lookup(dut, fetch_memory);
  observe_bfu_geometry_diagnostics(dut, bfu_stats, "fetch response");
  if (!dut.io_fetchRespReady || !dut.io_sourceRespFire) {
    std::cerr << "frontend fetch RF ALU response was not accepted"
              << " pc=0x" << std::hex << first.pc << std::dec
              << " respReady=" << static_cast<unsigned>(dut.io_fetchRespReady)
              << " sourceRespFire=" << static_cast<unsigned>(dut.io_sourceRespFire)
              << "\n";
    std::exit(1);
  }
  tick(dut);

  for (int cycle = 0; cycle < 8; ++cycle) {
    clear_inputs(dut);
    drive_bfu_body_geometry_hint(dut, body_cut);
    eval_with_load_lookup(dut, fetch_memory);
    observe_bfu_geometry_diagnostics(dut, bfu_stats, "dense packet drain");
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU dense drain");
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error before enqueue\n";
      std::exit(1);
    }
    if (dut.io_sourceOutFire) {
      const bool strict_dense_match =
          dut.io_f4ValidMask == expected_mask &&
          dut.io_f4SlotCount == slot_count &&
          dut.io_denseSlotQueueInSlotCount == slot_count &&
          dut.io_sourceAdvanceBytes == expected_advance;
      const bool relaxed_prefix_match =
          (redirect_tail || capture_tail) &&
          (dut.io_f4ValidMask & expected_mask) == expected_mask &&
          dut.io_f4SlotCount >= slot_count &&
          dut.io_denseSlotQueueInSlotCount >= slot_count &&
          dut.io_sourceAdvanceBytes >= expected_advance;
      const std::size_t observed_slots = static_cast<std::size_t>(dut.io_denseSlotQueueInSlotCount);
      const bool observed_strict_prefix = observed_slots > 0 && observed_slots < slot_count;
      const bool local_body_cut_prefix =
          observed_strict_prefix &&
          dut.io_reducedBodyCutFire &&
          dut.io_f4SlotCount == observed_slots &&
          dut.io_f4ValidMask == dense_window_mask(observed_slots) &&
          dut.io_sourceAdvanceBytes == dense_window_advance(rows, start, start + observed_slots) &&
          dut.io_reducedBodyCutAdvanceBytes == dut.io_sourceAdvanceBytes;
      if (!dut.io_denseSlotQueueInFire || (!strict_dense_match && !relaxed_prefix_match && !local_body_cut_prefix)) {
        std::cerr << "frontend fetch RF ALU dense packet was not captured"
                  << " pc=0x" << std::hex << first.pc << std::dec
                  << " expected_mask=0x" << std::hex << static_cast<unsigned>(expected_mask)
                  << " observed_mask=0x" << static_cast<unsigned>(dut.io_f4ValidMask)
                  << std::dec
                  << " expected_slots=" << slot_count
                  << " observed_f4_slots=" << static_cast<unsigned>(dut.io_f4SlotCount)
                  << " observed_queue_slots=" << static_cast<unsigned>(dut.io_denseSlotQueueInSlotCount)
                  << " expected_advance=" << static_cast<unsigned>(expected_advance)
                  << " observed_advance=" << static_cast<unsigned>(dut.io_sourceAdvanceBytes)
                  << "\n";
        std::exit(1);
      }
      FetchDenseWindowResult result;
      result.captured_tail_superset = capture_tail && observed_slots > slot_count;
      result.local_body_cut_prefix = local_body_cut_prefix;
      result.captured_slots = local_body_cut_prefix ? observed_slots : slot_count;
      if (local_body_cut_prefix) {
        ++bfu_stats.local_body_cut_prefix_count;
      }
      if (body_cut.valid && bfu_stats.match_count == bfu_match_count_before) {
        std::cerr << "frontend fetch RF ALU BFU body-geometry hint did not produce a static/external match"
                  << " header_pc=0x" << std::hex << body_cut.header_pc
                  << " hsize=0x" << body_cut.hsize_bytes
                  << " bsize=0x" << body_cut.bsize_bytes
                  << std::dec << "\n";
        std::exit(1);
      }
      tick(dut);
      return result;
    }
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU packet source did not emit dense packet"
            << " pc=0x" << std::hex << first.pc << std::dec << "\n";
  std::exit(1);
}

struct DrainDenseRowResult {
  std::uint8_t rob_value = 0;
  bool marker_redirect = false;
};

DrainDenseRowResult drain_dense_row(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const ExpectedRow &row,
    bool &active_block_valid,
    std::uint64_t &active_block_bid,
    const FetchMemoryImage &fetch_memory,
    std::vector<ObservedRow> &pending_commits,
    bool local_body_cut_reentry_header = false) {
  for (int cycle = 0; cycle < kDenseRowDrainCycles; ++cycle) {
    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error before dense slot drain\n";
      std::exit(1);
    }
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU dense slot drain");
    if (dut.io_denseSlotQueueOutFire) {
      if (!dut.io_decodeReady) {
        std::cerr << "frontend fetch RF ALU dense slot drained while decode was not ready"
                  << " pc=0x" << std::hex << row.pc << std::dec << "\n";
        std::exit(1);
      }
      if (row.skip) {
        const bool expected_alloc = row.block_boundary;
        const bool redirect_boundary = row.block_boundary && row_redirects(row);
        const bool expected_done = active_block_valid && (row.block_boundary || row.block_stop);
        const bool expected_alloc_fire =
            expected_alloc && !redirect_boundary && !local_body_cut_reentry_header;
        if (row.block_stop && !active_block_valid) {
          std::cerr << "frontend fetch RF ALU marker stop had no active block"
                    << " pc=0x" << std::hex << row.pc
                    << " insn=0x" << row.insn << std::dec
                    << "\n";
          std::exit(1);
        }
        if (dut.io_selectedValid ||
            !dut.io_blockMarkerSkipFire ||
            dut.io_blockMarkerMixedPacket ||
            dut.io_blockMarkerPc != row.pc ||
            mask_insn(dut.io_blockMarkerInsn, dut.io_blockMarkerLen) != mask_insn(row.insn, row.len) ||
            dut.io_blockMarkerLen != row.len ||
            static_cast<bool>(dut.io_blockMarkerBoundary) != row.block_boundary ||
            static_cast<bool>(dut.io_blockMarkerStop) != row.block_stop ||
            static_cast<bool>(dut.io_blockMarkerAllocFire) != expected_alloc_fire ||
            static_cast<bool>(dut.io_blockScalarDoneFire) != expected_done ||
            dut.io_decRenPushFire ||
            dut.io_robAllocFire) {
          std::cerr << "frontend fetch RF ALU marker dense slot mismatch"
                    << " pc=0x" << std::hex << row.pc
                    << " insn=0x" << row.insn << std::dec
                    << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
                    << " skipFire=" << static_cast<unsigned>(dut.io_blockMarkerSkipFire)
                    << " mixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
                    << " boundary=" << static_cast<unsigned>(dut.io_blockMarkerBoundary)
                    << " stop=" << static_cast<unsigned>(dut.io_blockMarkerStop)
                    << " allocFire=" << static_cast<unsigned>(dut.io_blockMarkerAllocFire)
                    << " activeValid=" << static_cast<unsigned>(dut.io_blockMarkerActiveValid)
                    << " activeBid=0x" << std::hex << dut.io_blockMarkerActiveBid
                    << " activeTarget=0x" << dut.io_blockMarkerActiveTarget
                    << " markerTarget=0x" << dut.io_blockMarkerTarget
                    << std::dec
                    << " scalarDone=" << static_cast<unsigned>(dut.io_blockScalarDoneFire)
                    << " decRenPush=" << static_cast<unsigned>(dut.io_decRenPushFire)
                    << " robAlloc=" << static_cast<unsigned>(dut.io_robAllocFire)
                    << "\n";
          std::exit(1);
        }
        if (expected_done && dut.io_blockScalarDoneBid != active_block_bid) {
          std::cerr << "frontend fetch RF ALU marker scalar-done BID mismatch"
                    << " pc=0x" << std::hex << row.pc
                    << " expected_bid=0x" << active_block_bid
                    << " observed_bid=0x" << dut.io_blockScalarDoneBid
                    << std::dec << "\n";
          std::exit(1);
        }
        DrainDenseRowResult result;
        result.marker_redirect = dut.io_blockMarkerStopRedirectValid;
        const std::uint64_t allocated_bid = dut.io_blockMarkerAllocBid;
        tick(dut);
        if (redirect_boundary || local_body_cut_reentry_header) {
          active_block_valid = false;
          active_block_bid = 0;
        } else if (row.block_boundary) {
          active_block_valid = true;
          active_block_bid = allocated_bid;
        } else if (row.block_stop) {
          active_block_valid = false;
          active_block_bid = 0;
        }
        return result;
      }

      if (!dut.io_selectedValid ||
          dut.io_blockMarkerMixedPacket ||
          dut.io_blockMarkerSkipFire ||
          !dut.io_decRenPushFire ||
          !dut.io_robAllocFire) {
        std::cerr << "frontend fetch RF ALU scalar dense slot mismatch"
                  << " pc=0x" << std::hex << row.pc << std::dec
                  << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
                  << " mixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
                  << " skipFire=" << static_cast<unsigned>(dut.io_blockMarkerSkipFire)
                  << " decRenPush=" << static_cast<unsigned>(dut.io_decRenPushFire)
                  << " robAlloc=" << static_cast<unsigned>(dut.io_robAllocFire)
                  << "\n";
        std::exit(1);
      }
      if (active_block_valid && dut.io_selectedBlockBid != active_block_bid) {
        std::cerr << "frontend fetch RF ALU scalar row used wrong active block BID"
                  << " pc=0x" << std::hex << row.pc
                  << " expected_bid=0x" << active_block_bid
                  << " observed_bid=0x" << dut.io_selectedBlockBid
                  << std::dec << "\n";
        std::exit(1);
      }
      const auto rob_value = static_cast<std::uint8_t>(dut.io_selectedRobValue);
      const auto selected_block_bid = static_cast<std::uint64_t>(dut.io_selectedBlockBid);
      tick(dut);
      if (row_redirects(row)) {
        active_block_valid = false;
        active_block_bid = 0;
      } else if (!active_block_valid) {
        active_block_valid = true;
        active_block_bid = selected_block_bid;
      }
      DrainDenseRowResult result;
      result.rob_value = rob_value;
      return result;
    }
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU dense slot queue did not drain row"
            << " pc=0x" << std::hex << row.pc << std::dec
            << " queueEmpty=" << static_cast<unsigned>(dut.io_denseSlotQueueEmpty)
            << " queueCount=" << static_cast<unsigned>(dut.io_denseSlotQueueCount)
            << " headSlot=" << static_cast<unsigned>(dut.io_denseSlotQueueHeadSlot)
            << " decodeReady=" << static_cast<unsigned>(dut.io_decodeReady)
            << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
            << " markerSkipValid=" << static_cast<unsigned>(dut.io_blockMarkerSkipValid)
            << " markerMixed=" << static_cast<unsigned>(dut.io_blockMarkerMixedPacket)
            << " markerBoundary=" << static_cast<unsigned>(dut.io_blockMarkerBoundary)
            << " markerStop=" << static_cast<unsigned>(dut.io_blockMarkerStop)
            << " markerAllocReady=" << static_cast<unsigned>(dut.io_blockMarkerAllocReady)
            << " markerAllocBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockMarkerAllocBid)
            << std::dec
            << " markerLifecycleConflict=" << static_cast<unsigned>(dut.io_blockMarkerLifecycleConflict)
            << " markerActiveValid=" << static_cast<unsigned>(dut.io_blockMarkerActiveValid)
            << " markerActiveBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockMarkerActiveBid)
            << " markerActiveTarget=0x"
            << static_cast<unsigned long long>(dut.io_blockMarkerActiveTarget)
            << std::dec
            << " blockScalarDoneFire=" << static_cast<unsigned>(dut.io_blockScalarDoneFire)
            << " blockScalarDoneBid=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_blockScalarDoneBid)
            << std::dec
            << " blockRetireFire=" << static_cast<unsigned>(dut.io_blockRetireFire)
            << " issueCount=" << static_cast<unsigned>(dut.io_issueQueueCount)
            << " executeBusy=" << static_cast<unsigned>(dut.io_executeBusy)
            << " occupiedMask=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_occupiedMask)
            << " completedMask=0x"
            << static_cast<unsigned long long>(dut.io_completedMask)
            << " blockAllocatedMask=0x"
            << static_cast<unsigned long long>(dut.io_blockAllocatedMask)
            << " blockCompleteMask=0x"
            << static_cast<unsigned long long>(dut.io_blockCompleteMask)
            << " blockPendingMask=0x"
            << static_cast<unsigned long long>(dut.io_blockPendingMask)
            << std::dec << "\n";
  std::exit(1);
}

void drain_empty(VLinxCoreFrontendFetchRfAluTraceTop &dut, const FetchMemoryImage &fetch_memory) {
  for (int cycle = 0; cycle < 16; ++cycle) {
    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_idle && dut.io_empty && dut.io_size == 0 && !dut.io_executeBusy) {
      return;
    }
    tick(dut);
  }
  std::cerr << "frontend fetch RF ALU trace top did not drain after commit"
            << " idle=" << static_cast<unsigned>(dut.io_idle)
            << " size=" << static_cast<unsigned>(dut.io_size)
            << " outstanding=" << static_cast<unsigned>(dut.io_outstandingCount)
            << " executeBusy=" << static_cast<unsigned>(dut.io_executeBusy)
            << " sourceWaiting=" << static_cast<unsigned>(dut.io_sourceWaitingResponse)
            << " sourcePacket=" << static_cast<unsigned>(dut.io_sourcePacketValid)
            << "\n";
  std::exit(1);
}

void commit_expected_row(
    VLinxCoreFrontendFetchRfAluTraceTop &dut,
    const ExpectedRow &expected,
    std::vector<ObservedRow> &pending_commits,
    FetchMemoryImage &fetch_memory,
    std::ofstream &dut_out,
    std::ofstream &qemu_out) {
  for (int cycle = 0; cycle < 32; ++cycle) {
    if (!pending_commits.empty()) {
      const ObservedRow observed = pending_commits.front();
      pending_commits.erase(pending_commits.begin());
      expect_row(observed, expected);
      write_dut_row(dut_out, observed);
      write_qemu_row(qemu_out, expected);
      if (expected.mem_valid && expected.mem_is_store && expected.mem_size == 8) {
        fetch_memory.store_u64(expected.mem_addr, expected.mem_wdata);
      }
      return;
    }
    clear_inputs(dut);
    eval_with_load_lookup(dut, fetch_memory);
    if (dut.io_rfStateError) {
      std::cerr << "frontend fetch RF ALU reported RF state error while waiting for commit\n";
      std::exit(1);
    }
    if (dut.io_executeUnsupported) {
      std::cerr << "execute reported unsupported opcode="
                << static_cast<unsigned>(dut.io_executeUnsupportedOpcode) << "\n";
      std::exit(1);
    }
    if (dut.io_executeCompleteValid && dut.io_completeIgnored) {
      std::cerr << "execute completion was ignored while waiting for commit"
                << " rob=" << static_cast<unsigned>(dut.io_executeCompleteRobValue)
                << "\n";
      std::exit(1);
    }
    collect_commit_if_present(dut, pending_commits, "frontend fetch RF ALU trace top commit");
    if (!pending_commits.empty()) {
      const ObservedRow observed = pending_commits.front();
      pending_commits.erase(pending_commits.begin());
      expect_row(observed, expected);
      write_dut_row(dut_out, observed);
      write_qemu_row(qemu_out, expected);
      if (expected.mem_valid && expected.mem_is_store && expected.mem_size == 8) {
        fetch_memory.store_u64(expected.mem_addr, expected.mem_wdata);
      }
      tick(dut);
      return;
    }
    expect_monitor_clean(dut, "frontend fetch RF ALU trace top wait", 0x0, 0);
    tick(dut);
  }

  std::cerr << "frontend fetch RF ALU trace top did not emit a commit row"
            << " pc=0x" << std::hex << expected.pc << std::dec
            << " pendingCommits=" << pending_commits.size()
            << " decRenCount=" << static_cast<unsigned>(dut.io_decRenCount)
            << " decRenValid=" << static_cast<unsigned>(dut.io_decRenValid)
            << " decRenHeadPc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_decRenHeadPc)
            << " decRenHeadRidValid=" << std::dec
            << static_cast<unsigned>(dut.io_decRenHeadRidValid)
            << " decRenHeadRidValue=" << static_cast<unsigned>(dut.io_decRenHeadRidValue)
            << " renamedOutValid=" << static_cast<unsigned>(dut.io_renamedOutValid)
            << " renamedAccepted=" << static_cast<unsigned>(dut.io_renamedAccepted)
            << " decodeBlockedByRename=" << static_cast<unsigned>(dut.io_decodeBlockedByRename)
            << " decodeBlockedByRob=" << static_cast<unsigned>(dut.io_decodeBlockedByRob)
            << " decodeBlockedByOutput=" << static_cast<unsigned>(dut.io_decodeBlockedByOutput)
            << " decodeBlockedByTURename=" << static_cast<unsigned>(dut.io_decodeBlockedByTURename)
            << " gprFree=" << static_cast<unsigned>(dut.io_gprFreeCount)
            << " gprMapQFree=" << static_cast<unsigned>(dut.io_gprMapQFreeCount)
            << " robRenameUpdateAttemptValid="
            << static_cast<unsigned>(dut.io_robRenameUpdateAttemptValid)
            << " robRenameUpdateReady=" << static_cast<unsigned>(dut.io_robRenameUpdateReady)
            << " robRenameUpdateFire=" << static_cast<unsigned>(dut.io_robRenameUpdateFire)
            << " robRenameUpdateIgnored=" << static_cast<unsigned>(dut.io_robRenameUpdateIgnored)
            << " tuUnderflow=0x" << std::hex
            << static_cast<unsigned>(dut.io_tuRenameSourceUnderflowMask)
            << " tuActiveBank=" << std::dec
            << static_cast<unsigned>(dut.io_tuRenameActiveBankValid)
            << " tuTBlocked=" << static_cast<unsigned>(dut.io_tuRenameBlockedByTAlloc)
            << " tuUBlocked=" << static_cast<unsigned>(dut.io_tuRenameBlockedByUAlloc)
            << " tuTUsed=" << static_cast<unsigned>(dut.io_tuRenameTUsedEntries)
            << " tuUUsed=" << static_cast<unsigned>(dut.io_tuRenameUUsedEntries)
            << " tuRetCmdValid=" << static_cast<unsigned>(dut.io_tuRetireCommandValid)
            << " tuRetCmdFire=" << static_cast<unsigned>(dut.io_tuRetireCommandFire)
            << " tuLocalCommitPending=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitPending)
            << " tuLocalCommitValid=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitValid)
            << " tuLocalCommitReady=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitReady)
            << " tuLocalCommitFire=" << static_cast<unsigned>(dut.io_tuRetireLocalBlockCommitFire)
            << " tuRetAccepted=" << static_cast<unsigned>(dut.io_tuRetireAccepted)
            << " tuRetMiss=" << static_cast<unsigned>(dut.io_tuRetireMiss)
            << " tuRetMismatch=" << static_cast<unsigned>(dut.io_tuRetireReleaseMismatch)
            << " tuRetUnsupported=" << static_cast<unsigned>(dut.io_tuRetireUnsupported)
            << " localT=0x" << std::hex << static_cast<unsigned>(dut.io_localTReadyMask)
            << " localU=0x" << static_cast<unsigned>(dut.io_localUReadyMask)
            << std::dec
            << " localTPending=" << static_cast<unsigned>(dut.io_localTPendingCount)
            << " localUPending=" << static_cast<unsigned>(dut.io_localUPendingCount)
            << " localIncomingUsesLocal=" << static_cast<unsigned>(dut.io_localIncomingUsesLocal)
            << " localIncomingBlocked=" << static_cast<unsigned>(dut.io_localIncomingBlocked)
            << " issueCount=" << static_cast<unsigned>(dut.io_issueQueueCount)
            << " issueHeadValid=" << static_cast<unsigned>(dut.io_issueQueueHeadValid)
            << " issueHeadIssued=" << static_cast<unsigned>(dut.io_issueQueueHeadIssued)
            << " issueHeadPc=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_issueQueueHeadPc)
            << " issueHeadOpcode=0x"
            << static_cast<unsigned>(dut.io_issueQueueHeadOpcode)
            << " issueHeadSrcValid=0x"
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcValidMask)
            << " issueHeadSrc0=(cls=" << std::dec
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcClass_0)
            << ",phys=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcPhysTag_0)
            << ",rel=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcRelTag_0)
            << ") issueHeadSrc1=(cls="
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcClass_1)
            << ",phys=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcPhysTag_1)
            << ",rel=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcRelTag_1)
            << ") issueHeadSrc2=(cls="
            << static_cast<unsigned>(dut.io_issueQueueHeadSrcClass_2)
            << ",phys=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcPhysTag_2)
            << ",rel=" << static_cast<unsigned>(dut.io_issueQueueHeadSrcRelTag_2)
            << ")"
            << " issueSourceReady=0x" << std::hex
            << static_cast<unsigned>(dut.io_issueQueueSourceReadyMask)
            << std::dec
            << " issueSelectedValid=" << static_cast<unsigned>(dut.io_issueQueueSelectedValid)
            << " issueSelectedReadReady=" << static_cast<unsigned>(dut.io_issueQueueSelectedReadReady)
            << " issueI1Valid=" << static_cast<unsigned>(dut.io_issueQueueI1Valid)
            << " issueI2Valid=" << static_cast<unsigned>(dut.io_issueQueueI2Valid)
            << " issueBlockedBySource=" << static_cast<unsigned>(dut.io_issueQueueBlockedBySource)
            << " issueBlockedByRead=" << static_cast<unsigned>(dut.io_issueQueueBlockedByRead)
            << " issueBlockedByOutput=" << static_cast<unsigned>(dut.io_issueQueueBlockedByOutput)
            << " issueBlockedByIssued=" << static_cast<unsigned>(dut.io_issueQueueBlockedByIssued)
            << " executeBusy=" << static_cast<unsigned>(dut.io_executeBusy)
            << " outstanding=" << static_cast<unsigned>(dut.io_outstandingCount)
            << " robDeallocBlockLastValid=" << static_cast<unsigned>(dut.io_robDeallocBlockLastValid)
            << " blockScalarDoneFire=" << static_cast<unsigned>(dut.io_blockScalarDoneFire)
            << " blockRetireFire=" << static_cast<unsigned>(dut.io_blockRetireFire)
            << " commitHeadValid=" << static_cast<unsigned>(dut.io_commitHeadValid)
            << " commitHeadStatus=" << static_cast<unsigned>(dut.io_commitHeadStatus)
            << " commitHeadRobValue=" << static_cast<unsigned>(dut.io_commitHeadRobValue)
            << " occupiedMask=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_occupiedMask)
            << " completedMask=0x" << std::hex
            << static_cast<unsigned long long>(dut.io_completedMask)
            << " retiredMask=0x"
            << static_cast<unsigned long long>(dut.io_retiredMask)
            << std::dec
            << "\n";
  std::exit(1);
}

std::vector<ExpectedRow> fixture_rows() {
  const std::uint64_t add =
      0x00000005ULL | (3ULL << 7) | (4ULL << 15) | (5ULL << 20);
  const std::uint64_t addi =
      0x00000015ULL | (6ULL << 7) | (3ULL << 15) | (0x7ffULL << 20);
  const std::uint64_t c_movr =
      0x0006ULL | (6ULL << 6) | (5ULL << 11);

  ExpectedRow r0;
  r0.pc = 0x1000;
  r0.insn = add;
  r0.len = 4;
  r0.src0_valid = true;
  r0.src0_reg = 4;
  r0.src0_data = 10;
  r0.src1_valid = true;
  r0.src1_reg = 5;
  r0.src1_data = 32;
  r0.dst_valid = true;
  r0.dst_reg = 3;
  r0.dst_data = 42;

  ExpectedRow r1;
  r1.pc = 0x1004;
  r1.insn = addi;
  r1.len = 4;
  r1.src0_valid = true;
  r1.src0_reg = 3;
  r1.src0_data = 42;
  r1.dst_valid = true;
  r1.dst_reg = 6;
  r1.dst_data = 2089;

  ExpectedRow r2;
  r2.pc = 0x1008;
  r2.insn = c_movr;
  r2.len = 2;
  r2.src0_valid = true;
  r2.src0_reg = 6;
  r2.src0_data = 2089;
  r2.dst_valid = true;
  r2.dst_reg = 5;
  r2.dst_data = 2089;

  return {r0, r1, r2};
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const Args args = parse_args(argc, argv);

  const auto rows = args.expected_rows.empty()
                        ? fixture_rows()
                        : load_expected_rows_jsonl(args.expected_rows);
  FetchMemoryImage fetch_memory;
  if (!args.memory_hex.empty()) {
    fetch_memory.load_sparse_hex(args.memory_hex);
  } else if (!args.memory_bin.empty()) {
    fetch_memory.load_binary(args.memory_bin, args.memory_base);
  } else {
    fetch_memory = FetchMemoryImage::from_rows(rows);
  }

  VLinxCoreFrontendFetchRfAluTraceTop dut;
  reset(dut);
  for (const auto &[arch_tag, data] : initial_rf_preloads(rows)) {
    init_rf(dut, arch_tag, data);
  }

  std::ofstream dut_out(args.dut_trace);
  std::ofstream qemu_out(args.qemu_trace);
  if (!dut_out || !qemu_out) {
    std::cerr << "failed to open output traces\n";
    return 2;
  }

  start_source(dut, rows.front().pc);
  bool active_block_valid = false;
  std::uint64_t active_block_bid = 0;
  bool captured_tail_superset = false;
  bool next_local_body_cut_reentry_header = false;
  std::uint64_t next_local_body_cut_reentry_pc = 0;
  std::vector<ObservedRow> pending_commits;
  BfuGeometryDiagnostics bfu_stats;
  for (std::size_t row_index = 0; row_index < rows.size();) {
    const std::size_t window_end = dense_window_end(rows, row_index);
    const FetchDenseWindowResult capture =
        fetch_dense_window(dut, rows, row_index, window_end, fetch_memory, pending_commits, bfu_stats);
    captured_tail_superset |= capture.captured_tail_superset;
    const std::size_t drain_end = row_index + capture.captured_slots;

    std::vector<std::size_t> scalar_slots;
    bool replay_local_body_cut_reentry_header = false;
    for (std::size_t slot_index = row_index; slot_index < drain_end; ++slot_index) {
      const ExpectedRow &row = rows[slot_index];
      const bool local_body_cut_reentry_header =
          next_local_body_cut_reentry_header &&
          slot_index == row_index &&
          row.skip &&
          row.block_boundary &&
          row.pc == next_local_body_cut_reentry_pc;
      if (next_local_body_cut_reentry_header && slot_index == row_index && !local_body_cut_reentry_header) {
        std::cerr << "frontend fetch RF ALU expected a local-body-cut re-entry header"
                  << " expected_pc=0x" << std::hex << next_local_body_cut_reentry_pc
                  << " observed_pc=0x" << row.pc << std::dec
                  << " skip=" << static_cast<unsigned>(row.skip)
                  << " block_boundary=" << static_cast<unsigned>(row.block_boundary)
                  << "\n";
        return 1;
      }
      if (row.skip) {
        const DrainDenseRowResult drain_result = drain_dense_row(
            dut,
            row,
            active_block_valid,
            active_block_bid,
            fetch_memory,
            pending_commits,
            local_body_cut_reentry_header);
        if (local_body_cut_reentry_header) {
          next_local_body_cut_reentry_header = false;
          next_local_body_cut_reentry_pc = 0;
          if (drain_result.marker_redirect) {
            replay_local_body_cut_reentry_header = true;
            break;
          }
        }
        continue;
      }
      (void)drain_dense_row(dut, row, active_block_valid, active_block_bid, fetch_memory, pending_commits);
      scalar_slots.push_back(slot_index);
    }

    for (const auto slot_index : scalar_slots) {
      const ExpectedRow &row = rows[slot_index];
      commit_expected_row(dut, row, pending_commits, fetch_memory, dut_out, qemu_out);
    }
    if (replay_local_body_cut_reentry_header) {
      continue;
    }
    row_index = drain_end;
    if (capture.local_body_cut_prefix) {
      if (!is_suppressed_local_body_cut_marker(rows, row_index)) {
        std::cerr << "frontend fetch RF ALU local body cut did not stop before a redirecting BSTART marker"
                  << " row_index=" << row_index;
        if (row_index < rows.size()) {
          const ExpectedRow &row = rows[row_index];
          std::cerr << " pc=0x" << std::hex << row.pc
                    << " next_pc=0x" << row.next_pc << std::dec
                    << " skip=" << static_cast<unsigned>(row.skip)
                    << " block_boundary=" << static_cast<unsigned>(row.block_boundary);
        }
        std::cerr << "\n";
        return 1;
      }
      next_local_body_cut_reentry_header = true;
      next_local_body_cut_reentry_pc = rows[row_index].next_pc;
      ++row_index;
    } else {
      row_index = window_end;
    }
  }

  if (!pending_commits.empty()) {
    std::cerr << "frontend fetch RF ALU trace top has unconsumed commit rows="
              << pending_commits.size() << "\n";
    return 1;
  }
  const auto print_bfu_stats = [&]() {
    std::cout << "bfu_static_external_comparable=" << bfu_stats.comparable_count
              << " bfu_static_external_matches=" << bfu_stats.match_count
              << " bfu_resolved_body_end_accepts=" << bfu_stats.resolved_accept_count
              << " bfu_body_cut_arm_comparable=" << bfu_stats.cut_arm_comparable_count
              << " bfu_body_cut_arm_accepts=" << bfu_stats.cut_arm_accept_count
              << " bfu_body_cut_arm_mismatches=" << bfu_stats.cut_arm_mismatch_count
              << " bfu_local_body_cut_prefixes=" << bfu_stats.local_body_cut_prefix_count
              << " bfu_resolved_source_runtime_selected=" << bfu_stats.resolved_source_runtime_selected_count
              << " bfu_resolved_source_replay_selected=" << bfu_stats.resolved_source_replay_selected_count
              << " bfu_resolved_source_runtime_feedback=" << bfu_stats.resolved_source_runtime_feedback_count
              << " bfu_resolved_source_runtime_pending=" << bfu_stats.resolved_source_runtime_pending_count
              << " bfu_resolved_source_runtime_pending_consumes="
              << bfu_stats.resolved_source_runtime_pending_consume_count
              << " bfu_resolved_source_runtime_pending_drop_mismatches="
              << bfu_stats.resolved_source_runtime_pending_drop_mismatch_count
              << " bfu_resolved_source_runtime_pending_candidate_comparable="
              << bfu_stats.resolved_source_runtime_pending_candidate_comparable_count
              << " bfu_resolved_source_runtime_pending_candidate_matches="
              << bfu_stats.resolved_source_runtime_pending_candidate_match_count
              << " bfu_resolved_source_runtime_pending_candidate_mismatches="
              << bfu_stats.resolved_source_runtime_pending_candidate_mismatch_count
              << " bfu_resolved_source_runtime_replay_comparable="
              << bfu_stats.resolved_source_runtime_replay_comparable_count
              << " bfu_resolved_source_runtime_replay_matches="
              << bfu_stats.resolved_source_runtime_replay_match_count
              << " bfu_resolved_source_runtime_replay_mismatches="
              << bfu_stats.resolved_source_runtime_replay_mismatch_count
              << "\n";
  };
  if (captured_tail_superset) {
    print_bfu_stats();
    dut.final();
    return 0;
  }
  drain_empty(dut, fetch_memory);
  eval_with_load_lookup(dut, fetch_memory);
  if (!dut.io_idle || !dut.io_empty || dut.io_size != 0) {
    std::cerr << "frontend fetch RF ALU trace top did not finish idle"
              << " idle=" << static_cast<unsigned>(dut.io_idle)
              << " empty=" << static_cast<unsigned>(dut.io_empty)
              << " size=" << static_cast<unsigned>(dut.io_size)
              << "\n";
    return 1;
  }
  expect_monitor_clean(dut, "post-drain idle window", 0x0, 0);

  print_bfu_stats();
  dut.final();
  return 0;
}
