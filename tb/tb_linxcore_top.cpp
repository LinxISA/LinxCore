#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <array>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <optional>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#if __has_include(<pyc/cpp/pyc_tb.hpp>)
#include <pyc/cpp/pyc_tb.hpp>
#elif __has_include(<cpp/pyc_tb.hpp>)
#include <cpp/pyc_tb.hpp>
#elif __has_include(<pyc_tb.hpp>)
#include <pyc_tb.hpp>
#else
#error "pyc_tb.hpp not found; set include path for pyCircuit runtime headers"
#endif

#if __has_include(<pyc/cpp/pyc_linxtrace.hpp>)
#include <pyc/cpp/pyc_linxtrace.hpp>
#elif __has_include(<cpp/pyc_linxtrace.hpp>)
#include <cpp/pyc_linxtrace.hpp>
#elif __has_include(<pyc_linxtrace.hpp>)
#include <pyc_linxtrace.hpp>
#else
#error "pyc_linxtrace.hpp not found; set include path for pyCircuit runtime headers"
#endif

#if __has_include(<pyc/cpp/pyc_probe_registry.hpp>)
#include <pyc/cpp/pyc_probe_registry.hpp>
#elif __has_include(<cpp/pyc_probe_registry.hpp>)
#include <cpp/pyc_probe_registry.hpp>
#elif __has_include(<pyc_probe_registry.hpp>)
#include <pyc_probe_registry.hpp>
#else
#error "pyc_probe_registry.hpp not found; set include path for pyCircuit runtime headers"
#endif

#include "linxcore_top.hpp"
#include "linxcore_host_mem_shadow.hpp"
#include "tb_linxcore_trace_util.hpp"

using pyc::cpp::Testbench;
using pyc::cpp::Wire;
using pyc::cpp::ProbeRegistry;
using linxcore::sim::HostMemShadow;
using linxcore::sim::replayPreloadWords;
using linxcore::sim::resolveMemBytesFromEnv;

namespace {

constexpr std::uint64_t kBootPc = 0x0000'0000'0001'0000ull;
constexpr std::uint64_t kBootSp = 0x0000'0000'07fe'fff0ull;
constexpr std::uint64_t kDefaultMaxCycles = 50000000ull;
constexpr std::uint64_t kDefaultDeadlockCycles = 200000ull;
constexpr const char *kDefaultDisasmTool = "/Users/zhoubot/linx-isa/tools/isa/linxdisasm.py";
constexpr const char *kDefaultDisasmSpec = "/Users/zhoubot/linx-isa/isa/v0.3/linxisa-v0.3.json";
constexpr const char *kDefaultObjdumpTool = "/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-objdump";
constexpr const char *kDefaultOpcodeIdsPy = "/Users/zhoubot/LinxCore/src/common/opcode_ids_gen.py";

using linxcore::tb::disasmInsn;
using linxcore::tb::loadObjdumpPcMap;
using linxcore::tb::loadOpNameMap;

static bool envFlag(const char *name) {
  const char *v = std::getenv(name);
  if (!v)
    return false;
  return !(v[0] == '0' && v[1] == '\0');
}

static std::unordered_set<std::uint32_t> parseAcceptedExitCodes() {
  std::unordered_set<std::uint32_t> accepted{};
  const char *env = std::getenv("PYC_ACCEPT_EXIT_CODES");
  if (!env || env[0] == '\0') {
    accepted.insert(0u);
    return accepted;
  }

  std::string raw(env);
  std::size_t pos = 0;
  while (pos < raw.size()) {
    std::size_t end = raw.find(',', pos);
    if (end == std::string::npos)
      end = raw.size();
    std::string tok = raw.substr(pos, end - pos);
    tok.erase(std::remove_if(tok.begin(), tok.end(), [](unsigned char ch) { return std::isspace(ch) != 0; }), tok.end());
    if (!tok.empty()) {
      try {
        const unsigned long long v = std::stoull(tok, nullptr, 0);
        accepted.insert(static_cast<std::uint32_t>(v & 0xFFFF'FFFFull));
      } catch (...) {
        // Ignore malformed tokens and continue parsing.
      }
    }
    pos = (end == raw.size()) ? end : (end + 1);
  }
  if (accepted.empty())
    accepted.insert(0u);
  return accepted;
}

static std::uint64_t maskInsn(std::uint64_t raw, std::uint8_t len) {
  switch (len) {
  case 2:
    return raw & 0xFFFFu;
  case 4:
    return raw & 0xFFFF'FFFFu;
  case 6:
    return raw & 0xFFFF'FFFF'FFFFu;
  default:
    return raw;
  }
}

static std::uint8_t normalizeLen(std::uint8_t len) {
  return (len == 2 || len == 4 || len == 6) ? len : 4;
}

static std::uint64_t buildIfuStubWindow(std::uint64_t raw, std::uint8_t len) {
  const std::uint8_t useLen = normalizeLen(len);
  std::uint64_t payloadMask = 0xFFFF'FFFFull;
  if (useLen == 2) {
    payloadMask = 0xFFFFull;
  } else if (useLen == 6) {
    payloadMask = 0xFFFF'FFFF'FFFFull;
  }
  const std::uint64_t payload = maskInsn(raw, useLen) & payloadMask;
  // Pad remaining bytes with 0xFF so slot-decode sees invalid padding.
  return (0xFFFF'FFFF'FFFF'FFFFull & ~payloadMask) | payload;
}

static std::uint8_t inferLenFromInsn16(std::uint16_t insn16) {
  if ((insn16 & 0xFu) == 0xEu)
    return 6;
  return (insn16 & 0x1u) ? 4 : 2;
}

static std::pair<std::uint64_t, std::uint8_t> fetchInsnAtPc(const HostMemShadow &mem, std::uint64_t pc) {
  const std::uint16_t insn16 = static_cast<std::uint16_t>(mem.loadGuestByte(pc) |
                                                           (static_cast<std::uint16_t>(mem.loadGuestByte(pc + 1)) << 8));
  const std::uint8_t len = inferLenFromInsn16(insn16);
  std::uint64_t raw = 0;
  for (std::uint8_t i = 0; i < len; i++) {
    raw |= static_cast<std::uint64_t>(mem.loadGuestByte(pc + i)) << (8u * i);
  }
  return {maskInsn(raw, len), len};
}

static std::string toHex(std::uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::uppercase << std::hex << v << std::dec;
  return oss.str();
}

static std::string insnHexToken(std::uint64_t raw, std::uint8_t len) {
  unsigned digits = 16;
  if (len == 2)
    digits = 4;
  else if (len == 4)
    digits = 8;
  else if (len == 6)
    digits = 12;
  std::ostringstream oss;
  oss << std::hex << std::nouppercase << std::setfill('0') << std::setw(static_cast<int>(digits)) << maskInsn(raw, len);
  return oss.str();
}

struct XcheckCommit {
  std::uint64_t seq = 0;
  std::uint64_t pc = 0;
  std::uint64_t template_kind = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 0;
  std::uint64_t wb_valid = 0;
  std::uint64_t wb_rd = 0;
  std::uint64_t wb_data = 0;
  std::uint64_t src0_valid = 0;
  std::uint64_t src0_reg = 0;
  std::uint64_t src0_data = 0;
  std::uint64_t src1_valid = 0;
  std::uint64_t src1_reg = 0;
  std::uint64_t src1_data = 0;
  std::uint64_t dst_valid = 0;
  std::uint64_t dst_reg = 0;
  std::uint64_t dst_data = 0;
  std::uint64_t mem_valid = 0;
  std::uint64_t mem_is_store = 0;
  std::uint64_t mem_addr = 0;
  std::uint64_t mem_wdata = 0;
  std::uint64_t mem_rdata = 0;
  std::uint64_t mem_size = 0;
  std::uint64_t trap_valid = 0;
  std::uint64_t trap_cause = 0;
  std::uint64_t traparg0 = 0;
  std::uint64_t next_pc = 0;
};

static bool isBstart16(std::uint16_t hw) {
  if ((hw & 0xc7ffu) == 0x0000u || (hw & 0xc7ffu) == 0x0080u) {
    const std::uint8_t brtype = static_cast<std::uint8_t>((hw >> 11) & 0x7u);
    if (brtype != 0u) {
      return true;
    }
  }
  if ((hw & 0x000fu) == 0x0002u || (hw & 0x000fu) == 0x0004u) {
    return true;
  }
  switch (hw) {
  case 0x0840u:
  case 0x08c0u:
  case 0x48c0u:
  case 0x88c0u:
  case 0xc8c0u:
    return true;
  default:
    return false;
  }
}

static bool isBstart32(std::uint32_t insn) {
  switch (insn & 0x00007fffu) {
  case 0x00001001u:
  case 0x00002001u:
  case 0x00003001u:
  case 0x00004001u:
  case 0x00005001u:
  case 0x00006001u:
  case 0x00007001u:
    return true;
  default:
    return false;
  }
}

static bool isBstart48(std::uint64_t raw) {
  const std::uint16_t prefix = static_cast<std::uint16_t>(raw & 0xFFFFu);
  const std::uint32_t main32 = static_cast<std::uint32_t>((raw >> 16) & 0xFFFF'FFFFu);
  if ((prefix & 0xFu) != 0xEu) {
    return false;
  }
  return ((main32 & 0xFFu) == 0x01u) && (((main32 >> 12) & 0x7u) != 0u);
}

static bool isMacroMarker32(std::uint32_t insn) {
  switch (insn & 0x0000707fu) {
  case 0x00000041u:
  case 0x00001041u:
  case 0x00002041u:
  case 0x00003041u:
    return true;
  default:
    return false;
  }
}

static bool isMetadataCommit(const XcheckCommit &r) {
  const bool zero_meta = (r.len == 0) && (r.insn == 0) && (r.pc == 0);
  if (zero_meta) {
    return true;
  }

  const std::uint64_t eff_wb_valid = r.wb_valid | r.dst_valid;
  const std::uint64_t insn_m = maskInsn(r.insn, r.len);
  const bool is_bstart =
      ((r.len == 2) && isBstart16(static_cast<std::uint16_t>(insn_m))) ||
      ((r.len == 4) && isBstart32(static_cast<std::uint32_t>(insn_m))) ||
      ((r.len == 6) && isBstart48(insn_m));
  const bool is_cbstop = (r.len == 2) && (static_cast<std::uint16_t>(insn_m) == 0x0000u);
  const bool is_macro_marker = (r.len == 4) && isMacroMarker32(static_cast<std::uint32_t>(insn_m));
  const bool is_template_uop = (r.template_kind != 0);

  const bool bstart_metadata = is_bstart && (eff_wb_valid == 0) && (r.mem_valid == 0) && (r.trap_valid == 0) &&
                               (r.next_pc == (r.pc + r.len));
  const bool macro_metadata =
      is_macro_marker && (eff_wb_valid == 0) && (r.mem_valid == 0) && (r.trap_valid == 0);
  const bool template_metadata =
      is_template_uop && (eff_wb_valid == 0) && (r.mem_valid == 0) && (r.trap_valid == 0);
  const bool cbstop_metadata = is_cbstop && (eff_wb_valid == 0) && (r.mem_valid == 0) && (r.trap_valid == 0);

  return bstart_metadata || macro_metadata || template_metadata || cbstop_metadata;
}

struct XcheckMismatch {
  std::uint64_t seq = 0;
  std::string field{};
  std::uint64_t qemu = 0;
  std::uint64_t dut = 0;
  std::uint64_t qemu_pc = 0;
  std::uint64_t dut_pc = 0;
  std::uint64_t qemu_insn = 0;
  std::uint64_t dut_insn = 0;
  XcheckCommit qemu_row{};
  XcheckCommit dut_row{};
};

static std::string xcheckCommitSummary(const XcheckCommit &r) {
  std::ostringstream oss;
  oss << "pc=" << toHex(r.pc)
      << " len=" << static_cast<unsigned>(r.len)
      << " tmpl=" << r.template_kind
      << " insn=" << toHex(maskInsn(r.insn, r.len))
      << " wb(v=" << r.wb_valid << ",rd=" << r.wb_rd << ",data=" << toHex(r.wb_data) << ")"
      << " src0(v=" << r.src0_valid << ",r=" << r.src0_reg << ",d=" << toHex(r.src0_data) << ")"
      << " src1(v=" << r.src1_valid << ",r=" << r.src1_reg << ",d=" << toHex(r.src1_data) << ")"
      << " dst(v=" << r.dst_valid << ",r=" << r.dst_reg << ",d=" << toHex(r.dst_data) << ")"
      << " mem(v=" << r.mem_valid << ",st=" << r.mem_is_store
      << ",a=" << toHex(r.mem_addr) << ",wd=" << toHex(r.mem_wdata)
      << ",rd=" << toHex(r.mem_rdata) << ",sz=" << r.mem_size << ")"
      << " trap(v=" << r.trap_valid << ",cause=" << toHex(r.trap_cause)
      << ",arg0=" << toHex(r.traparg0) << ")"
      << " next_pc=" << toHex(r.next_pc);
  return oss.str();
}

static bool getJsonKeyPos(const std::string &line, const std::string &key, std::size_t &value_pos) {
  const std::string pattern = "\"" + key + "\"";
  std::size_t pos = line.find(pattern);
  if (pos == std::string::npos) {
    return false;
  }
  pos = line.find(':', pos + pattern.size());
  if (pos == std::string::npos) {
    return false;
  }
  pos++;
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos]))) {
    pos++;
  }
  if (pos >= line.size()) {
    return false;
  }
  value_pos = pos;
  return true;
}

static bool getJsonU64(const std::string &line, const std::string &key, std::uint64_t &out) {
  std::size_t pos = 0;
  if (!getJsonKeyPos(line, key, pos)) {
    return false;
  }
  errno = 0;
  char *endp = nullptr;
  out = std::strtoull(line.c_str() + pos, &endp, 0);
  if (errno != 0 || endp == line.c_str() + pos) {
    return false;
  }
  return true;
}

static bool parseJsonCommitLine(const std::string &line, std::uint64_t seq_default, XcheckCommit &out) {
  out.seq = seq_default;
  (void)getJsonU64(line, "seq", out.seq);
  (void)getJsonU64(line, "template_kind", out.template_kind);
  std::uint64_t len64 = 0;
  if (!getJsonU64(line, "pc", out.pc) ||
      !getJsonU64(line, "insn", out.insn) ||
      !getJsonU64(line, "len", len64) ||
      !getJsonU64(line, "wb_valid", out.wb_valid) ||
      !getJsonU64(line, "wb_rd", out.wb_rd) ||
      !getJsonU64(line, "wb_data", out.wb_data) ||
      !getJsonU64(line, "mem_valid", out.mem_valid) ||
      !getJsonU64(line, "mem_is_store", out.mem_is_store) ||
      !getJsonU64(line, "mem_addr", out.mem_addr) ||
      !getJsonU64(line, "mem_wdata", out.mem_wdata) ||
      !getJsonU64(line, "mem_rdata", out.mem_rdata) ||
      !getJsonU64(line, "mem_size", out.mem_size) ||
      !getJsonU64(line, "trap_valid", out.trap_valid) ||
      !getJsonU64(line, "trap_cause", out.trap_cause) ||
      !getJsonU64(line, "traparg0", out.traparg0) ||
      !getJsonU64(line, "next_pc", out.next_pc)) {
    return false;
  }
  // Optional runtime operand/value details.
  if (!getJsonU64(line, "src0_valid", out.src0_valid)) {
    out.src0_valid = 0;
    out.src0_reg = 0;
    out.src0_data = 0;
  } else {
    (void)getJsonU64(line, "src0_reg", out.src0_reg);
    (void)getJsonU64(line, "src0_data", out.src0_data);
  }
  if (!getJsonU64(line, "src1_valid", out.src1_valid)) {
    out.src1_valid = 0;
    out.src1_reg = 0;
    out.src1_data = 0;
  } else {
    (void)getJsonU64(line, "src1_reg", out.src1_reg);
    (void)getJsonU64(line, "src1_data", out.src1_data);
  }
  if (!getJsonU64(line, "dst_valid", out.dst_valid)) {
    // Backward-compatible fallback: derive destination from wb fields.
    out.dst_valid = out.wb_valid;
    out.dst_reg = out.wb_rd;
    out.dst_data = out.wb_data;
  } else {
    if (!getJsonU64(line, "dst_reg", out.dst_reg)) {
      out.dst_reg = out.wb_rd;
    }
    if (!getJsonU64(line, "dst_data", out.dst_data)) {
      out.dst_data = out.wb_data;
    }
  }
  out.len = static_cast<std::uint8_t>(len64 & 0xFFu);
  return true;
}

static bool loadXcheckTrace(const std::string &path, std::uint64_t max_commits, std::vector<XcheckCommit> &rows) {
  std::ifstream in(path);
  if (!in.is_open()) {
    std::cerr << "error: cannot open QEMU trace: " << path << "\n";
    return false;
  }
  rows.clear();
  const std::uint64_t raw_limit =
      (max_commits > 0) ? (max_commits * 32ull + 1024ull) : 0ull;
  rows.reserve(raw_limit > 0 ? static_cast<std::size_t>(raw_limit) : 4096);
  std::string line;
  std::uint64_t seq = 0;
  while (std::getline(in, line)) {
    if (line.empty()) {
      continue;
    }
    XcheckCommit row{};
    if (!parseJsonCommitLine(line, seq, row)) {
      std::cerr << "error: malformed trace row in " << path << ": " << line << "\n";
      return false;
    }
    row.insn = maskInsn(row.insn, row.len);
    rows.push_back(row);
    seq++;
    if (raw_limit > 0 && rows.size() >= raw_limit) {
      break;
    }
  }
  if (rows.empty()) {
    std::cerr << "error: empty QEMU trace: " << path << "\n";
    return false;
  }
  return true;
}

static std::optional<XcheckMismatch> compareXcheckCommit(std::uint64_t seq, const XcheckCommit &q, const XcheckCommit &d) {
  auto fail = [&](const char *field, std::uint64_t qv, std::uint64_t dv) -> std::optional<XcheckMismatch> {
    return XcheckMismatch{
        seq,
        field,
        qv,
        dv,
        q.pc,
        d.pc,
        maskInsn(q.insn, q.len),
        maskInsn(d.insn, d.len),
        q,
        d,
    };
  };
  auto cmp = [&](const char *field, std::uint64_t qv, std::uint64_t dv) -> std::optional<XcheckMismatch> {
    if (qv != dv) {
      return fail(field, qv, dv);
    }
    return std::nullopt;
  };

  if (auto mm = cmp("pc", q.pc, d.pc))
    return mm;
  if (auto mm = cmp("len", q.len, d.len))
    return mm;
  // Template micro-ops (expanded from FENTRY/FEXIT/FRET.*) do not have a stable
  // architectural encoding in the instruction stream. QEMU reports the macro
  // marker encoding, while the DUT emits a synthetic per-uop scalar encoding.
  // Compare by architectural effects, not insn bits.
  if (d.template_kind == 0) {
    if (auto mm = cmp("insn", maskInsn(q.insn, q.len), maskInsn(d.insn, d.len)))
      return mm;
  }
  if (auto mm = cmp("wb_valid", q.wb_valid, d.wb_valid))
    return mm;
  if (q.wb_valid) {
    if (auto mm = cmp("wb_rd", q.wb_rd, d.wb_rd))
      return mm;
    if (auto mm = cmp("wb_data", q.wb_data, d.wb_data))
      return mm;
  }
  if (q.src0_valid && !d.src0_valid) {
    return fail("src0_valid", q.src0_valid, d.src0_valid);
  }
  if (q.src0_valid && d.src0_valid) {
    if (auto mm = cmp("src0_reg", q.src0_reg, d.src0_reg))
      return mm;
    if (auto mm = cmp("src0_data", q.src0_data, d.src0_data))
      return mm;
  }
  if (q.src1_valid && !d.src1_valid) {
    return fail("src1_valid", q.src1_valid, d.src1_valid);
  }
  if (q.src1_valid && d.src1_valid) {
    if (auto mm = cmp("src1_reg", q.src1_reg, d.src1_reg))
      return mm;
    if (auto mm = cmp("src1_data", q.src1_data, d.src1_data))
      return mm;
  }
  if (auto mm = cmp("dst_valid", q.dst_valid, d.dst_valid))
    return mm;
  if (q.dst_valid) {
    if (auto mm = cmp("dst_reg", q.dst_reg, d.dst_reg))
      return mm;
    if (auto mm = cmp("dst_data", q.dst_data, d.dst_data))
      return mm;
  }

  if (auto mm = cmp("mem_valid", q.mem_valid, d.mem_valid))
    return mm;
  if (q.mem_valid) {
    if (auto mm = cmp("mem_is_store", q.mem_is_store, d.mem_is_store))
      return mm;
    if (auto mm = cmp("mem_addr", q.mem_addr, d.mem_addr))
      return mm;
    if (auto mm = cmp("mem_size", q.mem_size, d.mem_size))
      return mm;
    if (q.mem_is_store) {
      if (auto mm = cmp("mem_wdata", q.mem_wdata, d.mem_wdata))
        return mm;
    } else {
      if (auto mm = cmp("mem_rdata", q.mem_rdata, d.mem_rdata))
        return mm;
    }
  }

  if (auto mm = cmp("trap_valid", q.trap_valid, d.trap_valid))
    return mm;
  if (q.trap_valid) {
    if (auto mm = cmp("trap_cause", q.trap_cause, d.trap_cause))
      return mm;
    if (auto mm = cmp("traparg0", q.traparg0, d.traparg0))
      return mm;
  }

  if (auto mm = cmp("next_pc", q.next_pc, d.next_pc))
    return mm;

  return std::nullopt;
}

static std::string jsonEscape(const std::string &s) {
  std::ostringstream oss;
  for (char ch : s) {
    switch (ch) {
    case '\\':
      oss << "\\\\";
      break;
    case '"':
      oss << "\\\"";
      break;
    case '\n':
      oss << "\\n";
      break;
    case '\r':
      oss << "\\r";
      break;
    case '\t':
      oss << "\\t";
      break;
    default:
      oss << ch;
      break;
    }
  }
  return oss.str();
}

} // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
    std::cerr << "usage: " << argv[0] << " <program.memh>\n";
    return 2;
  }
  const std::string memhPath = argv[1];

  pyc::gen::linxcore_top dut{};
  ProbeRegistry probeRegistry{};
  dut.pyc_register_probes(probeRegistry, "dut");
  const bool simStatsEnabled = envFlag("PYC_SIM_STATS");
  auto dumpSimStats = [&]() {
    if (!simStatsEnabled)
      return;
    const char *path = std::getenv("PYC_SIM_STATS_PATH");
    if (path && path[0] != '\0') {
      std::filesystem::path out(path);
      if (out.has_parent_path())
        std::filesystem::create_directories(out.parent_path());
      dut.dump_sim_stats_to_path(path);
      return;
    }
    dut.dump_sim_stats(std::cerr);
  };

  HostMemShadow memShadow(resolveMemBytesFromEnv());
  std::string memhError{};
  if (!memShadow.loadMemh(memhPath, &memhError)) {
    std::cerr << "ERROR: failed to preload memh: " << memhPath << " (" << memhError << ")\n";
    return 2;
  }

  std::uint64_t bootPc = kBootPc;
  std::uint64_t bootSp = kBootSp;
  std::uint64_t bootRa = bootSp + 0x10000ull;
  bool bootRaExplicit = false;
  if (const char *env = std::getenv("PYC_BOOT_PC"))
    bootPc = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  if (const char *env = std::getenv("PYC_BOOT_SP"))
    bootSp = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  if (const char *env = std::getenv("PYC_BOOT_RA")) {
    bootRa = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
    bootRaExplicit = true;
  }
  if (!bootRaExplicit)
    bootRa = bootSp + 0x10000ull;

  dut.boot_pc = Wire<64>(bootPc);
  dut.boot_sp = Wire<64>(bootSp);
  dut.boot_ra = Wire<64>(bootRa);
  dut.host_wvalid = Wire<1>(0);
  dut.host_waddr = Wire<64>(0);
  dut.host_wdata = Wire<64>(0);
  dut.host_wstrb = Wire<8>(0);
  dut.ic_l2_req_ready = Wire<1>(1);
  dut.ic_l2_rsp_valid = Wire<1>(0);
  dut.ic_l2_rsp_addr = Wire<64>(0);
  dut.ic_l2_rsp_data = Wire<512>(0);
  dut.ic_l2_rsp_error = Wire<1>(0);
  dut.tb_ifu_stub_enable = Wire<1>(0);
  dut.tb_ifu_stub_valid = Wire<1>(0);
  dut.tb_ifu_stub_pc = Wire<64>(0);
  dut.tb_ifu_stub_window = Wire<64>(0);
  dut.tb_ifu_stub_checkpoint = Wire<6>(0);
  dut.tb_ifu_stub_pkt_uid = Wire<64>(0);
  dut.callframe_size_i = Wire<64>(0);

  Testbench<pyc::gen::linxcore_top> tb(dut);
  tb.addClock(dut.clk, 1);
  tb.reset(dut.rst, 2, 1);

  // Host memory replay drives a clocked SRAM write port, so it must happen
  // after the testbench clock/reset are established and with fetch held off.
  dut.tb_ifu_stub_enable = Wire<1>(0);
  dut.ic_l2_req_ready = Wire<1>(0);
  dut.ic_l2_rsp_valid = Wire<1>(0);
  dut.ic_l2_rsp_addr = Wire<64>(0);
  dut.ic_l2_rsp_data = Wire<512>(0);
  dut.ic_l2_rsp_error = Wire<1>(0);
  replayPreloadWords(memShadow, [&](std::uint64_t guestAddr, std::uint64_t data, std::uint8_t strb) {
    dut.host_wvalid = Wire<1>(1);
    dut.host_waddr = Wire<64>(guestAddr);
    dut.host_wdata = Wire<64>(data);
    dut.host_wstrb = Wire<8>(strb);
    tb.runCyclesAuto(1);
  });
  dut.host_wvalid = Wire<1>(0);
  dut.host_waddr = Wire<64>(0);
  dut.host_wdata = Wire<64>(0);
  dut.host_wstrb = Wire<8>(0);
  dut.tb_ifu_stub_enable = Wire<1>(1);
  dut.ic_l2_req_ready = Wire<1>(1);
  const std::uint64_t startCycle = dut.cycles.value();

  std::ofstream commitTrace{};
  if (const char *p = std::getenv("PYC_COMMIT_TRACE"); p && p[0] != '\0') {
    std::filesystem::path out(p);
    if (out.has_parent_path()) {
      std::filesystem::create_directories(out.parent_path());
    }
    commitTrace.open(out, std::ios::out | std::ios::trunc);
    if (!commitTrace.is_open()) {
      std::cerr << "WARN: cannot open commit trace output: " << out << "\n";
    }
  }

  std::ofstream rawTrace{};
  if (const char *p = std::getenv("PYC_RAW_TRACE"); p && p[0] != '\0') {
    std::filesystem::path out(p);
    if (out.has_parent_path()) {
      std::filesystem::create_directories(out.parent_path());
    }
    rawTrace.open(out, std::ios::out | std::ios::trunc);
    if (!rawTrace.is_open()) {
      std::cerr << "WARN: cannot open raw trace output: " << out << "\n";
    }
  }
  const bool rawTraceProbes = envFlag("PYC_RAW_TRACE_PROBES");
  const bool debugMacroPrf = envFlag("PYC_DEBUG_MACRO_PRF");

  const bool traceVcd = envFlag("PYC_VCD");
  const bool traceLinxTrace = envFlag("PYC_LINXTRACE");
  if (traceVcd) {
    std::filesystem::create_directories("/Users/zhoubot/LinxCore/generated/cpp/linxcore_top");
    tb.enableVcd("/Users/zhoubot/LinxCore/generated/cpp/linxcore_top/tb_linxcore_top.vcd",
                 "tb_linxcore_top");
    tb.vcdTrace(dut.clk, "clk");
    tb.vcdTrace(dut.rst, "rst");
    tb.vcdTrace(dut.cycles, "cycles");
    tb.vcdTrace(dut.halted, "halted");
    tb.vcdTrace(dut.pc, "pc");
  }

  pyc::cpp::LinxTraceWriter linxtrace_writer{};
  if (traceLinxTrace) {
    std::filesystem::path outPath{};
    if (const char *p = std::getenv("PYC_LINXTRACE_PATH"); p && p[0] != '\0') {
      outPath = std::filesystem::path(p);
    } else {
      const std::string stem = std::filesystem::path(memhPath).stem().string();
      outPath = std::filesystem::path("/Users/zhoubot/LinxCore/generated/cpp/linxcore_top") /
                ("tb_linxcore_top_cpp_" + (stem.empty() ? std::string("program") : stem) + ".linxtrace");
    }
    if (outPath.has_parent_path()) {
      std::filesystem::create_directories(outPath.parent_path());
    }
    if (!linxtrace_writer.open(outPath, dut.cycles.value())) {
      std::cerr << "WARN: cannot open LinxTrace output: " << outPath << "\n";
    }
  }

  std::uint64_t maxCycles = kDefaultMaxCycles;
  if (const char *env = std::getenv("PYC_MAX_CYCLES"))
    maxCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  std::uint64_t debugIfuStubCycles = 0;
  if (const char *env = std::getenv("PYC_DEBUG_IFU_STUB_CYCLES"))
    debugIfuStubCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  std::uint64_t maxCommits = 0;
  if (const char *env = std::getenv("PYC_MAX_COMMITS"))
    maxCommits = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  bool stopOnIdleLoop = false;
  std::uint64_t idleLoopPcA = 0;
  std::uint64_t idleLoopPcB = 0;
  std::uint64_t idleLoopStreakTarget = 0;
  if (const char *env = std::getenv("PYC_IDLE_LOOP_PC_A"); env && env[0] != '\0') {
    idleLoopPcA = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
    stopOnIdleLoop = true;
  }
  if (const char *env = std::getenv("PYC_IDLE_LOOP_PC_B"); env && env[0] != '\0') {
    idleLoopPcB = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
    stopOnIdleLoop = stopOnIdleLoop && true;
  } else {
    stopOnIdleLoop = false;
  }
  if (const char *env = std::getenv("PYC_IDLE_LOOP_STREAK"); env && env[0] != '\0') {
    idleLoopStreakTarget = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  }
  if (idleLoopStreakTarget == 0) {
    idleLoopStreakTarget = 256;
  }
  stopOnIdleLoop = stopOnIdleLoop && (idleLoopPcA != idleLoopPcB);
  std::uint64_t deadlockCycles = kDefaultDeadlockCycles;
  if (const char *env = std::getenv("PYC_DEADLOCK_CYCLES"))
    deadlockCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  std::uint64_t icMissCycles = 20;
  if (const char *env = std::getenv("PYC_IC_MISS_CYCLES"))
    icMissCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  const bool debugIcache = envFlag("PYC_DEBUG_ICACHE");
  std::uint64_t debugIcacheCycles = 200;
  if (const char *env = std::getenv("PYC_DEBUG_ICACHE_CYCLES"))
    debugIcacheCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  std::string disasmTool = kDefaultDisasmTool;
  if (const char *env = std::getenv("PYC_DISASM_TOOL"); env && env[0] != '\0')
    disasmTool = env;
  std::string disasmSpec = kDefaultDisasmSpec;
  if (const char *env = std::getenv("PYC_DISASM_SPEC"); env && env[0] != '\0')
    disasmSpec = env;
  std::string objdumpTool = kDefaultObjdumpTool;
  if (const char *env = std::getenv("PYC_OBJDUMP_TOOL"); env && env[0] != '\0')
    objdumpTool = env;
  std::string objdumpElf{};
  if (const char *env = std::getenv("PYC_OBJDUMP_ELF"); env && env[0] != '\0') {
    objdumpElf = env;
  } else {
    std::filesystem::path p(memhPath);
    if (p.extension() == ".memh") {
      p.replace_extension(".elf");
      if (std::filesystem::exists(p))
        objdumpElf = p.string();
    }
  }
  std::string opcodeIdsPath = kDefaultOpcodeIdsPy;
  if (const char *env = std::getenv("PYC_OPCODE_IDS_PY"); env && env[0] != '\0') {
    opcodeIdsPath = env;
  } else if (const char *env = std::getenv("PYC_ISA_PY"); env && env[0] != '\0') {
    // Backward-compatible override.
    opcodeIdsPath = env;
  }
  const auto acceptedExitCodes = parseAcceptedExitCodes();
  const auto opNameMap = loadOpNameMap(opcodeIdsPath);
  const auto objdumpPcMap = loadObjdumpPcMap(objdumpTool, objdumpElf);

  const char *xcheckTraceEnv = std::getenv("PYC_QEMU_TRACE");
  const bool xcheckEnabled = xcheckTraceEnv && xcheckTraceEnv[0] != '\0';
  std::string xcheckMode = "diagnostic";
  if (const char *env = std::getenv("PYC_XCHECK_MODE"); env && env[0] != '\0') {
    xcheckMode = env;
  }
  if (xcheckMode != "diagnostic" && xcheckMode != "failfast") {
    xcheckMode = "diagnostic";
  }
  std::uint64_t xcheckMaxCommits = 1000;
  if (const char *env = std::getenv("PYC_XCHECK_MAX_COMMITS"); env && env[0] != '\0') {
    xcheckMaxCommits = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  }
  const bool xcheckFailfast = (xcheckMode == "failfast");
  std::vector<XcheckCommit> qemuXcheckRows{};
  if (xcheckEnabled) {
    if (!loadXcheckTrace(xcheckTraceEnv, xcheckMaxCommits, qemuXcheckRows)) {
      return 2;
    }
  }

  // Optional IFU stub trace input independent of xcheck. This is the
  // fast-path for large benchmarks: feed the core from a QEMU commit trace
  // without doing per-commit cross-check comparisons.
  const char *ifuTraceEnv = std::getenv("PYC_IFU_STUB_TRACE");
  std::uint64_t ifuTraceMaxCommits = 0;
  if (const char *env = std::getenv("PYC_IFU_STUB_TRACE_MAX_COMMITS"); env && env[0] != '\0') {
    ifuTraceMaxCommits = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  }
  std::vector<XcheckCommit> ifuStubTraceRows{};
  const std::vector<XcheckCommit> *ifuStubRows = nullptr;
  if (ifuTraceEnv && ifuTraceEnv[0] != '\0') {
    if (!loadXcheckTrace(ifuTraceEnv, ifuTraceMaxCommits, ifuStubTraceRows)) {
      return 2;
    }
    ifuStubRows = &ifuStubTraceRows;
  }

  bool ifuStubFromQemu = false;
  if (ifuStubRows != nullptr) {
    ifuStubFromQemu = true;
  } else {
    if (const char *env = std::getenv("PYC_IFU_STUB_QEMU"); env && env[0] != '\0') {
      ifuStubFromQemu = !(env[0] == '0' && env[1] == '\0');
    } else {
      ifuStubFromQemu = xcheckEnabled;
    }
    if (ifuStubFromQemu && !xcheckEnabled) {
      std::cerr << "WARN: PYC_IFU_STUB_QEMU requested but PYC_QEMU_TRACE is missing; "
                   "set PYC_IFU_STUB_TRACE=/path/to/qemu_trace.jsonl or unset PYC_IFU_STUB_QEMU; "
                   "falling back to memh-backed IFU stub\n";
      ifuStubFromQemu = false;
    }
    if (ifuStubFromQemu) {
      ifuStubRows = &qemuXcheckRows;
    }
  }
  struct IfuStubPacket {
    std::uint64_t pc = 0;
    std::uint64_t window = 0;
    std::uint64_t checkpoint = 0;
    std::uint64_t pkt_uid = 0;
    std::uint8_t insn_count = 1;
    std::uint8_t len_bytes = 4;
    bool is_macro_marker = false;
  };
  std::size_t ifuStubTraceCursor = 0;
  std::size_t ifuStubPendingNextTraceCursor = 0;
  std::optional<IfuStubPacket> ifuStubPending{};
  bool ifuStubFiredThisCycle = false;
  std::uint64_t ifuStubFiredPcThisCycle = 0;
  bool ifuStubFiredLastCycle = false;
  std::uint64_t ifuStubFiredPcLastCycle = 0;
  std::size_t ifuStubTraceCursorBeforeLastFire = 0;
  std::size_t ifuStubTraceCursorBeforeFire = 0;
  std::uint64_t ifuStubTracePktUid = 1;
  std::uint64_t ifuStubTraceCkptSeq = 1;
  const bool ifuStubTracePack = envFlag("PYC_IFU_STUB_TRACE_PACK");
  auto makeTraceIfuStubPacket = [&](std::size_t cursor, IfuStubPacket &outPkt, std::size_t &outNextCursor) -> bool {
    if (!ifuStubFromQemu || ifuStubRows == nullptr) {
      return false;
    }
    const auto &rows = *ifuStubRows;
    std::size_t rowCursor = cursor;

    // Find next usable instruction row.
    while (rowCursor < rows.size()) {
      const auto &r = rows[rowCursor];
      const std::uint8_t len = normalizeLen(static_cast<std::uint8_t>(r.len & 0xFFu));
      if ((len == 2 || len == 4 || len == 6) && r.pc != 0) {
        break;
      }
      rowCursor++;
    }
    if (rowCursor >= rows.size()) {
      return false;
    }

    const auto &r0 = rows[rowCursor];
    const std::uint64_t basePc = r0.pc;
    const std::uint64_t checkpoint = (ifuStubTraceCkptSeq & 0x3Full);
    const std::uint64_t pktUid = ifuStubTracePktUid;

    std::uint64_t window = 0;
    std::uint8_t consumed = 0;
    std::uint8_t packed = 0;
    bool macro0 = false;
    bool bstart0 = false;
    std::uint64_t raw0 = 0;
    std::uint8_t len0 = 0;

    // Default to one-instruction packets for the strict QEMU lane. Enable
    // packing explicitly for throughput-focused trace-fed runs.
    while (rowCursor < rows.size() && packed < 4) {
      const auto &r = rows[rowCursor];
      const std::uint8_t len = normalizeLen(static_cast<std::uint8_t>(r.len & 0xFFu));
      if (!(len == 2 || len == 4 || len == 6) || r.pc == 0) {
        rowCursor++;
        continue;
      }
      if (packed != 0) {
        if (r.pc != (basePc + static_cast<std::uint64_t>(consumed)))
          break;
        if (macro0)
          break;
      }
      if (consumed + len > 8)
        break;

      const std::uint64_t raw = maskInsn(r.insn, len);
      const bool isMacro = (len == 4) && isMacroMarker32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
      bool isBstart = false;
      if (len == 2) {
        isBstart = isBstart16(static_cast<std::uint16_t>(raw & 0xFFFFu));
      } else if (len == 4) {
        isBstart = isBstart32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
      } else if (len == 6) {
        isBstart = isBstart48(raw);
      }
      // Keep template macro blocks as standalone fetch bundles (slot0 only).
      if (isMacro && packed != 0) {
        break;
      }
      // Keep BSTART markers as standalone bundles (slot0 only) for strict
      // block semantics (BID assignment + BROB allocation).
      if (isBstart && packed != 0) {
        break;
      }
      for (std::uint8_t i = 0; i < len; i++) {
        const std::uint8_t b = static_cast<std::uint8_t>((raw >> (8u * i)) & 0xFFu);
        window |= static_cast<std::uint64_t>(b) << (8u * (consumed + i));
      }
      if (packed == 0) {
        raw0 = raw;
        len0 = len;
        macro0 = isMacro;
        bstart0 = isBstart;
      }
      consumed = static_cast<std::uint8_t>(consumed + len);
      packed = static_cast<std::uint8_t>(packed + 1);
      rowCursor++;
      if (!ifuStubTracePack) {
        break;
      }
      if (macro0 || bstart0) {
        break;
      }
    }

    // QEMU traces emit duplicate commit rows for some single-fetch markers:
    // - template macro markers (FENTRY/FEXIT/FRET.*) to describe internal uops
    // - boundary markers such as BSTART.CALL while the architectural marker and
    //   its metadata side effects are both reported in the retire stream
    // Feed exactly one fetch packet for those rows and skip the trailing
    // duplicate retire-only rows.
    if (macro0 || bstart0) {
      while (rowCursor < rows.size()) {
        const auto &r = rows[rowCursor];
        const std::uint8_t len = normalizeLen(static_cast<std::uint8_t>(r.len & 0xFFu));
        if (!(len == 2 || len == 4 || len == 6) || r.pc == 0) {
          rowCursor++;
          continue;
        }
        const std::uint64_t raw = maskInsn(r.insn, len);
        const bool isMacro = (len == 4) && isMacroMarker32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
        bool isBstartDup = false;
        if (len == 2) {
          isBstartDup = isBstart16(static_cast<std::uint16_t>(raw & 0xFFFFu));
        } else if (len == 4) {
          isBstartDup = isBstart32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
        } else if (len == 6) {
          isBstartDup = isBstart48(raw);
        }
        const bool sameFetchMarker =
            (r.pc == basePc) && (len == len0) && (raw == raw0) && ((macro0 && isMacro) || (bstart0 && isBstartDup));
        if (!sameFetchMarker) {
          break;
        }
        rowCursor++;
      }
    }
    for (std::uint8_t i = consumed; i < 8; i++) {
      window |= 0xFFull << (8u * i);
    }

    if (consumed == 0) {
      consumed = 4;
      window = 0xFFFF'FFFF'FFFF'FFFFull;
    }

    outPkt = IfuStubPacket{
        basePc,
        window,
        checkpoint,
        pktUid,
        (packed == 0) ? static_cast<std::uint8_t>(1) : packed,
        consumed,
        macro0,
    };
    outNextCursor = rowCursor;
    return true;
  };
  // Packet UID and checkpoint sequence are decoupled:
  // - pkt_uid is a per-packet unique identifier (used for uop_uid synthesis).
  // - checkpoint is a per-instruction sequence (decode uses base+slot for
  //   start-marker checkpoint tags), so packed bundles must advance it by the
  //   number of instructions, not by packets.
  std::uint64_t ifuStubMemPktUid = 1;
  std::uint64_t ifuStubMemCkptSeq = 1;
  std::uint64_t ifuStubMemPc = bootPc;

  auto makeMemIfuStubPacket = [&](std::uint64_t pc, bool allow_pack) -> std::optional<IfuStubPacket> {
    if (pc == 0)
      return std::nullopt;

    if (!allow_pack) {
      // Conservative bring-up mode: fetch one instruction and pad remaining
      // bytes. This keeps template/macro redirection behavior deterministic.
      auto fetched = fetchInsnAtPc(memShadow, pc);
      const std::uint64_t raw = fetched.first;
      const std::uint8_t len = normalizeLen(fetched.second);
      const bool isMacro = (len == 4) && isMacroMarker32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
      return IfuStubPacket{
          pc,
          buildIfuStubWindow(raw, len),
          (ifuStubMemCkptSeq & 0x3Full),
          ifuStubMemPktUid,
          1,
          len,
          isMacro,
      };
    }

    // Pack up to 4 sequential instructions into an 8B window. Enforce the same
    // "macro/template must be slot0" rule as decode_bundle_8B.
    std::uint64_t window = 0;
    std::uint8_t consumed = 0;
    std::uint8_t packed = 0;
    bool macro0 = false;
    bool bstart0 = false;

    while (packed < 4) {
      const std::uint64_t slotPc = pc + static_cast<std::uint64_t>(consumed);
      auto fetched = fetchInsnAtPc(memShadow, slotPc);
      const std::uint64_t raw = fetched.first;
      const std::uint8_t len = normalizeLen(fetched.second);
      if (consumed + len > 8) {
        break;
      }

      const bool isMacro = (len == 4) && isMacroMarker32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
      bool isBstart = false;
      if (len == 2) {
        isBstart = isBstart16(static_cast<std::uint16_t>(raw & 0xFFFFu));
      } else if (len == 4) {
        isBstart = isBstart32(static_cast<std::uint32_t>(raw & 0xFFFF'FFFFu));
      } else if (len == 6) {
        isBstart = isBstart48(raw);
      }

      // Keep macro/template blocks and BSTART markers aligned to slot0.
      if (packed != 0 && (isMacro || isBstart)) {
        break;
      }

      for (std::uint8_t i = 0; i < len; i++) {
        const std::uint8_t b = static_cast<std::uint8_t>((raw >> (8u * i)) & 0xFFu);
        window |= static_cast<std::uint64_t>(b) << (8u * (consumed + i));
      }

      if (packed == 0) {
        macro0 = isMacro;
        bstart0 = isBstart;
      }

      consumed = static_cast<std::uint8_t>(consumed + len);
      packed = static_cast<std::uint8_t>(packed + 1);

      if (macro0 || bstart0) {
        break;
      }
    }

    for (std::uint8_t i = consumed; i < 8; i++) {
      window |= 0xFFull << (8u * i);
    }

    if (consumed == 0) {
      consumed = 4;
      window = 0xFFFF'FFFF'FFFF'FFFFull;
    }

    return IfuStubPacket{
        pc,
        window,
        (ifuStubMemCkptSeq & 0x3Full),
        ifuStubMemPktUid,
        (packed == 0) ? static_cast<std::uint8_t>(1) : packed,
        consumed,
        macro0,
    };
  };

  // Hard-cut: instruction supply is always host/QEMU-driven into the on-chip IB.
  dut.tb_ifu_stub_enable = Wire<1>(1);

  static constexpr std::array<const char *, 39> kTraceProbeStageNames = {
      "F0", "F1", "F2", "F3", "F4", "D1", "D2", "D3", "IQ", "S1", "S2", "P1", "I1",
      "I2", "E1", "E2", "E3", "E4", "W1", "W2", "LIQ", "LHQ", "STQ", "SCB", "MDB",
      "L1D", "BISQ", "BCTRL", "TMU", "TMA", "CUBE", "VEC", "TAU", "BROB", "ROB", "CMT", "FLS", "XCHK", "IB",
  };
  static constexpr std::array<const char *, 39> kTraceStageNames = {
      "F0", "F1", "F2", "F3", "F4", "D1", "D2", "D3", "IQ", "S1", "S2", "P1", "I1",
      "I2", "E1", "E2", "E3", "E4", "W1", "W2", "LIQ", "LHQ", "STQ", "SCB", "MDB",
      "L1D", "BISQ", "BCTRL", "TMU", "TMA", "CUBE", "VEC", "TAU", "BROB", "ROB", "CMT", "FLS", "XCHK", "IB",
  };
  static constexpr std::uint64_t kTraceSidF0 = 0;
  static constexpr std::uint64_t kTraceSidF1 = 1;
  static constexpr std::uint64_t kTraceSidF2 = 2;
  static constexpr std::uint64_t kTraceSidF3 = 3;
  static constexpr std::uint64_t kTraceSidF4 = 4;
  static constexpr std::uint64_t kTraceSidIb = 38;
  static constexpr std::uint64_t kTraceSidBrob = 33;
  static constexpr std::uint64_t kTraceSidD1 = 5;
  static constexpr std::uint64_t kTraceSidD2 = 6;
  static constexpr std::uint64_t kTraceSidD3 = 7;
  static constexpr std::uint64_t kTraceSidS1 = 9;
  static constexpr std::uint64_t kTraceSidS2 = 10;
  static constexpr std::uint64_t kTraceSidIq = 8;
  static constexpr std::uint64_t kTraceSidP1 = 11;
  static constexpr std::uint64_t kTraceSidI1 = 12;
  static constexpr std::uint64_t kTraceSidI2 = 13;
  static constexpr std::uint64_t kTraceSidE1 = 14;
  static constexpr std::uint64_t kTraceSidW1 = 18;
  static constexpr std::uint64_t kTraceSidW2 = 19;
  static constexpr std::uint64_t kTraceSidCmt = 35;
  static constexpr std::uint64_t kTraceSidFls = 36;
  static constexpr std::uint64_t kTraceSidXchk = 37;
  static constexpr std::uint64_t kTraceKindNormal = 0;
  static constexpr std::uint64_t kTraceKindFlush = 1;
  static constexpr std::uint64_t kTraceKindTrap = 2;
  static constexpr std::uint64_t kTraceKindReplay = 3;
  static constexpr std::uint64_t kTraceKindTemplate = 4;
  static constexpr std::uint64_t kTraceKindPacket = 5;

  struct PvEntry {
    std::uint64_t id = 0;
    int lane = 0;
    int stageId = -1;
    std::uint64_t pc = 0;
    std::uint64_t rob = 0;
    std::uint64_t raw = 0;
    std::uint8_t len = 4;
    std::string disasm{};
  };

  std::unordered_map<std::uint64_t, PvEntry> pvByUid{};
  std::unordered_map<std::uint64_t, std::uint64_t> pvUidToKid{};
  std::unordered_map<std::uint64_t, std::uint64_t> pvUidLastRetireCycle{};
  std::unordered_map<std::uint64_t, std::uint64_t> rawUidLastTerminalCycle{};
  std::array<std::uint64_t, 64> blockBidByRob{};
  std::unordered_map<std::uint64_t, std::uint64_t> blockBidByUid{};
  std::unordered_map<std::uint64_t, std::uint64_t> blockUidByBid{};
  std::uint64_t nextSyntheticBlockBidHi = 1;
  std::unordered_set<std::uint64_t> dfxRetiredKeysCycle{};
  std::unordered_set<std::uint64_t> dfxTouchedUidCycle{};
  std::uint64_t pvNextTraceRowId = 1;
  std::unordered_map<std::string, std::string> disasmCache{};
  std::uint64_t curCycleTrace = 0;

  auto lookupDisasm = [&](std::uint64_t raw, std::uint8_t len) -> std::string {
    const std::uint8_t useLen = normalizeLen(len);
    const std::string token = insnHexToken(raw, useLen);
    auto it = disasmCache.find(token);
    if (it != disasmCache.end())
      return it->second;
    const std::string d = disasmInsn(disasmTool, disasmSpec, raw, useLen);
    disasmCache.emplace(token, d);
    return d;
  };

  auto opNameFromDisasm = [](const std::string &disasm) -> std::string {
    if (disasm.empty() || disasm == "<disasm-unavailable>")
      return {};
    std::size_t i = 0;
    while (i < disasm.size() && std::isspace(static_cast<unsigned char>(disasm[i])) != 0)
      i++;
    std::size_t j = i;
    while (j < disasm.size()) {
      const char ch = disasm[j];
      if (std::isspace(static_cast<unsigned char>(ch)) != 0 || ch == ',' || ch == ';')
        break;
      j++;
    }
    if (j <= i)
      return {};
    return disasm.substr(i, j - i);
  };

  auto fallbackInsnToken = [](std::uint64_t insn, std::uint8_t len) -> std::string {
    std::ostringstream ss;
    ss << "insn_" << insnHexToken(insn, len);
    return ss.str();
  };

  auto resolveOpName = [&](std::uint64_t op, std::uint64_t insn, std::uint8_t len, const std::string &disasm) -> std::string {
    auto it = opNameMap.find(op);
    if (it != opNameMap.end() && !it->second.empty())
      return it->second;
    const std::string parsed = opNameFromDisasm(disasm);
    if (!parsed.empty())
      return parsed;
    return fallbackInsnToken(insn, len);
  };

  auto pvDisasmForLabel = [&](const std::string &disasm, std::uint64_t op, std::uint64_t insn, std::uint8_t len) {
    if (disasm != "<disasm-unavailable>")
      return disasm;
    auto it = opNameMap.find(op);
    if (it != opNameMap.end() && !it->second.empty())
      return it->second;
    return fallbackInsnToken(insn, len);
  };

  auto pvInsnText = [](std::uint64_t pc, const std::string &disasm) {
    std::ostringstream ss;
    ss << "0x" << std::uppercase << std::hex << pc << std::dec << ": " << disasm;
    return ss.str();
  };
  auto pvRetireKey = [](std::uint64_t pc, std::uint64_t rob, int lane) -> std::uint64_t {
    return (pc * 11400714819323198485ull) ^ ((rob & 0x3fu) << 3) ^ static_cast<std::uint64_t>(lane & 0x7);
  };
  auto pvKindText = [](std::uint64_t kind) -> const char * {
    switch (kind) {
    case 0:
      return "normal";
    case 1:
      return "flush";
    case 2:
      return "trap";
    case 3:
      return "replay";
    case 5:
      return "packet";
    default:
      return "template_child";
    }
  };
  auto canonicalStageFromProbeSid = [&](int probeSid, std::uint64_t kind) -> int {
    if (probeSid < 0 || probeSid >= static_cast<int>(kTraceProbeStageNames.size()))
      return -1;
    if (kind == kTraceKindPacket)
      return -1;
    const std::string_view probe = kTraceProbeStageNames[static_cast<std::size_t>(probeSid)];
    if (probe == "FLS")
      return static_cast<int>(kTraceSidFls);
    if (probe == "XCHK")
      return static_cast<int>(kTraceSidXchk);
    if (probe == "F0")
      return static_cast<int>(kTraceSidF0);
    if (probe == "F1")
      return static_cast<int>(kTraceSidF1);
    if (probe == "F2")
      return static_cast<int>(kTraceSidF2);
    if (probe == "F3")
      return static_cast<int>(kTraceSidF3);
    if (probe == "F4")
      return static_cast<int>(kTraceSidF4);
    if (probe == "IB")
      return static_cast<int>(kTraceSidIb);
    if (probe == "D1")
      return static_cast<int>(kTraceSidD1);
    if (probe == "D2")
      return static_cast<int>(kTraceSidD2);
    if (probe == "D3")
      return static_cast<int>(kTraceSidD3);
    if (probe == "S1")
      return static_cast<int>(kTraceSidS1);
    if (probe == "S2")
      return static_cast<int>(kTraceSidS2);
    if (probe == "IQ")
      return static_cast<int>(kTraceSidIq);
    if (probe == "P1")
      return static_cast<int>(kTraceSidP1);
    if (probe == "I1")
      return static_cast<int>(kTraceSidI1);
    if (probe == "I2")
      return static_cast<int>(kTraceSidI2);
    if (probe == "E1")
      return static_cast<int>(kTraceSidE1);
    if (probe == "W1")
      return static_cast<int>(kTraceSidW1);
    if (probe == "W2")
      return static_cast<int>(kTraceSidW2);
    if (probe == "BROB")
      return static_cast<int>(kTraceSidBrob);
    return -1;
  };
  auto frontendProbeUid = [&](int probeSid, std::uint64_t rawUid) -> std::uint64_t {
    if (rawUid == 0)
      return 0;
    std::uint64_t stageTag = 0;
    switch (probeSid) {
    case static_cast<int>(kTraceSidF0):
      stageTag = 1;
      break;
    case static_cast<int>(kTraceSidF1):
      stageTag = 2;
      break;
    case static_cast<int>(kTraceSidF2):
      stageTag = 3;
      break;
    case static_cast<int>(kTraceSidF3):
      stageTag = 4;
      break;
    case static_cast<int>(kTraceSidF4):
      stageTag = 6;
      break;
    case static_cast<int>(kTraceSidIb):
      stageTag = 5;
      break;
    default:
      return rawUid;
    }
    return (rawUid << 6) | stageTag | (std::uint64_t{1} << 63);
  };
  auto probeSidFromStageToken = [&](std::string_view stageToken) -> int {
    std::string upper{};
    upper.reserve(stageToken.size());
    for (char ch : stageToken) {
      upper.push_back(static_cast<char>(std::toupper(static_cast<unsigned char>(ch))));
    }
    for (std::size_t sid = 0; sid < kTraceProbeStageNames.size(); ++sid) {
      if (upper == kTraceProbeStageNames[sid]) {
        return static_cast<int>(sid);
      }
    }
    return -1;
  };
  auto readProbeValue = [&](const ProbeRegistry::Entry *entry) -> std::uint64_t {
    if (!entry || entry->ptr == nullptr) {
      return 0;
    }
    switch (entry->width_bits) {
    case 1:
      return reinterpret_cast<const Wire<1> *>(entry->ptr)->value();
    case 2:
      return reinterpret_cast<const Wire<2> *>(entry->ptr)->value();
    case 3:
      return reinterpret_cast<const Wire<3> *>(entry->ptr)->value();
    case 4:
      return reinterpret_cast<const Wire<4> *>(entry->ptr)->value();
    case 6:
      return reinterpret_cast<const Wire<6> *>(entry->ptr)->value();
    case 8:
      return reinterpret_cast<const Wire<8> *>(entry->ptr)->value();
    case 12:
      return reinterpret_cast<const Wire<12> *>(entry->ptr)->value();
    case 16:
      return reinterpret_cast<const Wire<16> *>(entry->ptr)->value();
    case 24:
      return reinterpret_cast<const Wire<24> *>(entry->ptr)->value();
    case 32:
      return reinterpret_cast<const Wire<32> *>(entry->ptr)->value();
    case 64:
      return reinterpret_cast<const Wire<64> *>(entry->ptr)->value();
    default:
      return 0;
    }
  };
  auto readProbeBool = [&](const ProbeRegistry::Entry *entry) -> bool { return readProbeValue(entry) != 0; };
  std::vector<std::string> missingProbePaths{};
  auto requireProbe = [&](const std::string &path) -> const ProbeRegistry::Entry * {
    if (const auto *entry = probeRegistry.findByPath(path)) {
      return entry;
    }
    missingProbePaths.push_back(path);
    return nullptr;
  };
  struct OccProbeSet {
    int probeSid = -1;
    int lane = 0;
    const ProbeRegistry::Entry *valid = nullptr;
    const ProbeRegistry::Entry *uid = nullptr;
    const ProbeRegistry::Entry *pc = nullptr;
    const ProbeRegistry::Entry *rob = nullptr;
    const ProbeRegistry::Entry *kind = nullptr;
    const ProbeRegistry::Entry *parent = nullptr;
    const ProbeRegistry::Entry *blockUid = nullptr;
    const ProbeRegistry::Entry *coreId = nullptr;
    const ProbeRegistry::Entry *stall = nullptr;
    const ProbeRegistry::Entry *stallCause = nullptr;
  };
  std::unordered_map<std::string, OccProbeSet> occProbeMap{};
  for (const auto *entry : probeRegistry.findByGlob("dut:probe.pipeview.**")) {
    const std::string &path = entry->path;
    const std::size_t colonPos = path.rfind(':');
    if (colonPos == std::string::npos) {
      continue;
    }
    const std::string leaf = path.substr(colonPos + 1);
    static constexpr std::string_view kOccPrefix = "probe.pipeview.";
    if (leaf.rfind(kOccPrefix, 0) != 0) {
      continue;
    }
    const std::string rel = leaf.substr(kOccPrefix.size());
    const std::size_t dot0 = rel.find('.');
    const std::size_t dot1 = (dot0 == std::string::npos) ? std::string::npos : rel.find('.', dot0 + 1);
    if (dot0 == std::string::npos || dot1 == std::string::npos || rel.find('.', dot1 + 1) != std::string::npos) {
      continue;
    }
    const std::string stage = rel.substr(0, dot0);
    const std::string laneToken = rel.substr(dot0 + 1, dot1 - (dot0 + 1));
    if (laneToken.rfind("lane", 0) != 0) {
      continue;
    }
    const std::string laneText = laneToken.substr(4);
    int lane = 0;
    try {
      lane = std::stoi(laneText);
    } catch (...) {
      continue;
    }
    const std::string field = rel.substr(dot1 + 1);
    std::string key = stage;
    key.push_back('#');
    key.append(std::to_string(lane));
    auto &probe = occProbeMap[key];
    probe.probeSid = probeSidFromStageToken(stage);
    probe.lane = lane;
    if (field == "valid") {
      probe.valid = entry;
    } else if (field == "uop_uid") {
      probe.uid = entry;
    } else if (field == "pc") {
      probe.pc = entry;
    } else if (field == "rob") {
      probe.rob = entry;
    } else if (field == "kind") {
      probe.kind = entry;
    } else if (field == "parent_uid") {
      probe.parent = entry;
    } else if (field == "block_uid") {
      probe.blockUid = entry;
    } else if (field == "core_id") {
      probe.coreId = entry;
    } else if (field == "stall") {
      probe.stall = entry;
    } else if (field == "stall_cause") {
      probe.stallCause = entry;
    }
  }
  std::vector<OccProbeSet> occProbes{};
  occProbes.reserve(occProbeMap.size());
  for (const auto &kv : occProbeMap) {
    const auto &probe = kv.second;
    if (probe.probeSid < 0 || !probe.valid || !probe.uid || !probe.pc) {
      continue;
    }
    occProbes.push_back(probe);
  }
  std::sort(occProbes.begin(), occProbes.end(), [](const OccProbeSet &a, const OccProbeSet &b) {
    if (a.probeSid != b.probeSid) {
      return a.probeSid < b.probeSid;
    }
    return a.lane < b.lane;
  });
  if (occProbes.empty()) {
    std::cerr << "ERROR: ProbeRegistry did not expose any probe.pipeview occupancy probes\n";
    return 2;
  }
  auto dumpOccSummary = [&](std::uint64_t focusPc) {
    bool any = false;
    std::cerr << "  occ_dbg";
    if (focusPc != 0 || dut.rob_head_valid.value() != 0) {
      std::cerr << " focus_pc=" << toHex(focusPc);
    }
    std::cerr << ":\n";
    for (const auto &probe : occProbes) {
      if (!readProbeBool(probe.valid)) {
        continue;
      }
      const std::uint64_t pc = readProbeValue(probe.pc);
      const std::uint64_t rob = probe.rob ? readProbeValue(probe.rob) : 0;
      const bool focusHit = (focusPc == pc);
      any = true;
      std::cerr << "    " << (focusHit ? '*' : ' ')
                << kTraceStageNames[static_cast<std::size_t>(probe.probeSid)]
                << ".lane" << probe.lane
                << " uid=" << readProbeValue(probe.uid)
                << " rob=" << rob
                << " pc=" << toHex(pc);
      if (probe.kind) {
        std::cerr << " kind=" << readProbeValue(probe.kind);
      }
      if (probe.parent) {
        std::cerr << " parent=" << readProbeValue(probe.parent);
      }
      if (probe.blockUid) {
        std::cerr << " block_uid=" << readProbeValue(probe.blockUid);
      }
      if (probe.stall && readProbeValue(probe.stall) != 0) {
        std::cerr << " stall=1";
        if (probe.stallCause) {
          std::cerr << " cause=" << readProbeValue(probe.stallCause);
        }
      }
      std::cerr << "\n";
    }
    if (!any) {
      std::cerr << "    <no active occupancy probes>\n";
    }
  };
  struct CommitRedirectProbeSet {
    const ProbeRegistry::Entry *valid = nullptr;
    const ProbeRegistry::Entry *pc = nullptr;
    const ProbeRegistry::Entry *bid = nullptr;
    const ProbeRegistry::Entry *replayCause = nullptr;
    const ProbeRegistry::Entry *bruFaultSet = nullptr;
  };
  struct CommitSlotProbeSet {
    const ProbeRegistry::Entry *fire = nullptr;
    const ProbeRegistry::Entry *pc = nullptr;
    const ProbeRegistry::Entry *rob = nullptr;
    const ProbeRegistry::Entry *op = nullptr;
    const ProbeRegistry::Entry *uopUid = nullptr;
    const ProbeRegistry::Entry *parentUopUid = nullptr;
    const ProbeRegistry::Entry *blockUid = nullptr;
    const ProbeRegistry::Entry *blockBid = nullptr;
    const ProbeRegistry::Entry *coreId = nullptr;
    const ProbeRegistry::Entry *isBstart = nullptr;
    const ProbeRegistry::Entry *isBstop = nullptr;
    const ProbeRegistry::Entry *loadStoreId = nullptr;
    const ProbeRegistry::Entry *templateKind = nullptr;
    const ProbeRegistry::Entry *len = nullptr;
    const ProbeRegistry::Entry *insnRaw = nullptr;
    const ProbeRegistry::Entry *wbValid = nullptr;
    const ProbeRegistry::Entry *wbRd = nullptr;
    const ProbeRegistry::Entry *wbData = nullptr;
    const ProbeRegistry::Entry *src0Valid = nullptr;
    const ProbeRegistry::Entry *src0Reg = nullptr;
    const ProbeRegistry::Entry *src0Data = nullptr;
    const ProbeRegistry::Entry *src1Valid = nullptr;
    const ProbeRegistry::Entry *src1Reg = nullptr;
    const ProbeRegistry::Entry *src1Data = nullptr;
    const ProbeRegistry::Entry *dstValid = nullptr;
    const ProbeRegistry::Entry *dstReg = nullptr;
    const ProbeRegistry::Entry *dstData = nullptr;
    const ProbeRegistry::Entry *memValid = nullptr;
    const ProbeRegistry::Entry *memIsStore = nullptr;
    const ProbeRegistry::Entry *memAddr = nullptr;
    const ProbeRegistry::Entry *memWdata = nullptr;
    const ProbeRegistry::Entry *memRdata = nullptr;
    const ProbeRegistry::Entry *memSize = nullptr;
    const ProbeRegistry::Entry *trapValid = nullptr;
    const ProbeRegistry::Entry *trapCause = nullptr;
    const ProbeRegistry::Entry *nextPc = nullptr;
  };
  struct BlockProbeSet {
    const ProbeRegistry::Entry *activeBid = nullptr;
    const ProbeRegistry::Entry *queryState = nullptr;
    const ProbeRegistry::Entry *queryAllocated = nullptr;
    const ProbeRegistry::Entry *queryReady = nullptr;
    const ProbeRegistry::Entry *queryException = nullptr;
    const ProbeRegistry::Entry *queryRetired = nullptr;
    const ProbeRegistry::Entry *count = nullptr;
    const ProbeRegistry::Entry *allocReady = nullptr;
    const ProbeRegistry::Entry *allocBid = nullptr;
    const ProbeRegistry::Entry *rspValid = nullptr;
    const ProbeRegistry::Entry *rspSrcRob = nullptr;
    const ProbeRegistry::Entry *rspBid = nullptr;
    const ProbeRegistry::Entry *retireFire = nullptr;
    const ProbeRegistry::Entry *retireBid = nullptr;
    const ProbeRegistry::Entry *issueFire = nullptr;
    const ProbeRegistry::Entry *issueBid = nullptr;
    const ProbeRegistry::Entry *issueSrcRob = nullptr;
  };
  struct CommitDbgProbeSet {
    const ProbeRegistry::Entry *redirectFromCorr = nullptr;
    const ProbeRegistry::Entry *brKind = nullptr;
    const ProbeRegistry::Entry *brEpoch = nullptr;
    const ProbeRegistry::Entry *brPredTake = nullptr;
    const ProbeRegistry::Entry *brBase = nullptr;
    const ProbeRegistry::Entry *brOff = nullptr;
    const ProbeRegistry::Entry *commitCond = nullptr;
    const ProbeRegistry::Entry *brCorrPending = nullptr;
    const ProbeRegistry::Entry *brCorrEpoch = nullptr;
    const ProbeRegistry::Entry *brCorrTake = nullptr;
    const ProbeRegistry::Entry *brCorrTarget = nullptr;
  };
  struct CtuDbgProbeSet {
    const ProbeRegistry::Entry *macroActive = nullptr;
    const ProbeRegistry::Entry *macroPc = nullptr;
    const ProbeRegistry::Entry *macroWaitCommit = nullptr;
    const ProbeRegistry::Entry *macroWaitCommitNext = nullptr;
    const ProbeRegistry::Entry *ctuUopValid = nullptr;
    const ProbeRegistry::Entry *startFire = nullptr;
    const ProbeRegistry::Entry *headReady = nullptr;
    const ProbeRegistry::Entry *headIsMacro = nullptr;
    const ProbeRegistry::Entry *headSkip = nullptr;
  };
  struct BackendDbgProbeSet {
    const ProbeRegistry::Entry *brobAllocFire = nullptr;
    const ProbeRegistry::Entry *assignBlockBid = nullptr;
    const ProbeRegistry::Entry *flushBid = nullptr;
  };
  auto commitSlotPath = [](int slot, std::string_view field) -> std::string {
    return std::string("dut:probe.commit.slot") + std::to_string(slot) + "." + std::string(field);
  };
  CommitRedirectProbeSet commitRedirectProbes{};
  commitRedirectProbes.valid = requireProbe("dut:probe.commit.redirect.valid");
  commitRedirectProbes.pc = requireProbe("dut:probe.commit.redirect.pc");
  commitRedirectProbes.bid = requireProbe("dut:probe.commit.redirect.bid");
  commitRedirectProbes.replayCause = requireProbe("dut:probe.commit.redirect.replay_cause");
  commitRedirectProbes.bruFaultSet = requireProbe("dut:probe.commit.redirect.bru_fault_set");
  std::array<CommitSlotProbeSet, 4> commitSlotProbes{};
  for (int slot = 0; slot < 4; ++slot) {
    auto &probe = commitSlotProbes[static_cast<std::size_t>(slot)];
    probe.fire = requireProbe(commitSlotPath(slot, "fire"));
    probe.pc = requireProbe(commitSlotPath(slot, "pc"));
    probe.rob = requireProbe(commitSlotPath(slot, "rob"));
    probe.op = requireProbe(commitSlotPath(slot, "op"));
    probe.uopUid = requireProbe(commitSlotPath(slot, "uop_uid"));
    probe.parentUopUid = requireProbe(commitSlotPath(slot, "parent_uop_uid"));
    probe.blockUid = requireProbe(commitSlotPath(slot, "block_uid"));
    probe.blockBid = requireProbe(commitSlotPath(slot, "block_bid"));
    probe.coreId = requireProbe(commitSlotPath(slot, "core_id"));
    probe.isBstart = requireProbe(commitSlotPath(slot, "is_bstart"));
    probe.isBstop = requireProbe(commitSlotPath(slot, "is_bstop"));
    probe.loadStoreId = requireProbe(commitSlotPath(slot, "load_store_id"));
    probe.templateKind = requireProbe(commitSlotPath(slot, "template_kind"));
    probe.len = requireProbe(commitSlotPath(slot, "len"));
    probe.insnRaw = requireProbe(commitSlotPath(slot, "insn_raw"));
    probe.wbValid = requireProbe(commitSlotPath(slot, "wb_valid"));
    probe.wbRd = requireProbe(commitSlotPath(slot, "wb_rd"));
    probe.wbData = requireProbe(commitSlotPath(slot, "wb_data"));
    probe.src0Valid = requireProbe(commitSlotPath(slot, "src0_valid"));
    probe.src0Reg = requireProbe(commitSlotPath(slot, "src0_reg"));
    probe.src0Data = requireProbe(commitSlotPath(slot, "src0_data"));
    probe.src1Valid = requireProbe(commitSlotPath(slot, "src1_valid"));
    probe.src1Reg = requireProbe(commitSlotPath(slot, "src1_reg"));
    probe.src1Data = requireProbe(commitSlotPath(slot, "src1_data"));
    probe.dstValid = requireProbe(commitSlotPath(slot, "dst_valid"));
    probe.dstReg = requireProbe(commitSlotPath(slot, "dst_reg"));
    probe.dstData = requireProbe(commitSlotPath(slot, "dst_data"));
    probe.memValid = requireProbe(commitSlotPath(slot, "mem_valid"));
    probe.memIsStore = requireProbe(commitSlotPath(slot, "mem_is_store"));
    probe.memAddr = requireProbe(commitSlotPath(slot, "mem_addr"));
    probe.memWdata = requireProbe(commitSlotPath(slot, "mem_wdata"));
    probe.memRdata = requireProbe(commitSlotPath(slot, "mem_rdata"));
    probe.memSize = requireProbe(commitSlotPath(slot, "mem_size"));
    probe.trapValid = requireProbe(commitSlotPath(slot, "trap_valid"));
    probe.trapCause = requireProbe(commitSlotPath(slot, "trap_cause"));
    probe.nextPc = requireProbe(commitSlotPath(slot, "next_pc"));
  }
  BlockProbeSet blockProbes{};
  blockProbes.activeBid = requireProbe("dut:probe.block.brob.active_bid");
  blockProbes.queryState = requireProbe("dut:probe.block.brob.query_state");
  blockProbes.queryAllocated = requireProbe("dut:probe.block.brob.query_allocated");
  blockProbes.queryReady = requireProbe("dut:probe.block.brob.query_ready");
  blockProbes.queryException = requireProbe("dut:probe.block.brob.query_exception");
  blockProbes.queryRetired = requireProbe("dut:probe.block.brob.query_retired");
  blockProbes.count = requireProbe("dut:probe.block.brob.count");
  blockProbes.allocReady = requireProbe("dut:probe.block.brob.alloc_ready");
  blockProbes.allocBid = requireProbe("dut:probe.block.brob.alloc_bid");
  blockProbes.rspValid = requireProbe("dut:probe.block.brob.rsp_valid");
  blockProbes.rspSrcRob = requireProbe("dut:probe.block.brob.rsp_src_rob");
  blockProbes.rspBid = requireProbe("dut:probe.block.brob.rsp_bid");
  blockProbes.retireFire = requireProbe("dut:probe.block.brob.retire_fire");
  blockProbes.retireBid = requireProbe("dut:probe.block.brob.retire_bid");
  blockProbes.issueFire = requireProbe("dut:probe.block.bctrl.issue_fire");
  blockProbes.issueBid = requireProbe("dut:probe.block.bctrl.issue_bid");
  blockProbes.issueSrcRob = requireProbe("dut:probe.block.bctrl.issue_src_rob");
  CommitDbgProbeSet commitDbgProbes{};
  commitDbgProbes.redirectFromCorr =
      requireProbe("dut.linxcore_top_root.janus_backend:redirect_from_corr_dbg");
  commitDbgProbes.brKind =
      requireProbe("dut.linxcore_top_root.janus_backend:br_kind_dbg");
  commitDbgProbes.brEpoch =
      requireProbe("dut.linxcore_top_root.janus_backend:br_epoch_dbg");
  commitDbgProbes.brPredTake =
      requireProbe("dut.linxcore_top_root.janus_backend:br_pred_take_dbg");
  commitDbgProbes.brBase =
      requireProbe("dut.linxcore_top_root.janus_backend:br_base_dbg");
  commitDbgProbes.brOff =
      requireProbe("dut.linxcore_top_root.janus_backend:br_off_dbg");
  commitDbgProbes.commitCond =
      requireProbe("dut.linxcore_top_root.janus_backend:commit_cond_dbg");
  commitDbgProbes.brCorrPending =
      requireProbe("dut.linxcore_top_root.janus_backend:br_corr_pending_dbg");
  commitDbgProbes.brCorrEpoch =
      requireProbe("dut.linxcore_top_root.janus_backend:br_corr_epoch_dbg");
  commitDbgProbes.brCorrTake =
      requireProbe("dut.linxcore_top_root.janus_backend:br_corr_take_dbg");
  commitDbgProbes.brCorrTarget =
      requireProbe("dut.linxcore_top_root.janus_backend:br_corr_target_dbg");
  CtuDbgProbeSet ctuDbgProbes{};
  ctuDbgProbes.macroActive =
      requireProbe("dut.linxcore_top_root.janus_backend:state__macro_active");
  ctuDbgProbes.macroPc =
      requireProbe("dut.linxcore_top_root.janus_backend:state__macro_pc");
  ctuDbgProbes.macroWaitCommit =
      requireProbe("dut.linxcore_top_root.janus_backend:state__macro_wait_commit");
  ctuDbgProbes.macroWaitCommitNext =
      requireProbe("dut.linxcore_top_root.janus_backend:state__macro_wait_commit__next");
  ctuDbgProbes.ctuUopValid =
      requireProbe("dut.linxcore_top_root.janus_backend:ctu_uop_valid");
  ctuDbgProbes.startFire =
      requireProbe("dut.linxcore_top_root.janus_backend.code_template_unit:start_fire");
  ctuDbgProbes.headReady =
      requireProbe("dut.linxcore_top_root.janus_backend.code_template_unit:head_ready__code_template_unit__L34");
  ctuDbgProbes.headIsMacro =
      requireProbe("dut.linxcore_top_root.janus_backend.code_template_unit:head_is_macro");
  ctuDbgProbes.headSkip =
      requireProbe("dut.linxcore_top_root.janus_backend.code_template_unit:head_skip");
  BackendDbgProbeSet backendDbgProbes{};
  backendDbgProbes.brobAllocFire =
      requireProbe("dut.linxcore_top_root.janus_backend:brob_alloc_fire");
  backendDbgProbes.assignBlockBid =
      requireProbe("dut.linxcore_top_root.janus_backend:state__assign_block_bid");
  backendDbgProbes.flushBid =
      requireProbe("dut.linxcore_top_root.janus_backend:state__flush_bid");
  if (!missingProbePaths.empty()) {
    std::cerr << "ERROR: ProbeRegistry is missing required probe paths:\n";
    for (const auto &path : missingProbePaths) {
      std::cerr << "  " << path << "\n";
    }
    return 2;
  }
  auto pvLaneToken = [](int lane) -> std::string {
    std::ostringstream ss;
    ss << "c0.l" << (lane < 0 ? 0 : lane);
    return ss.str();
  };
  auto pvFmtOperand = [](bool valid, std::uint32_t reg, std::uint64_t data) -> std::string {
    if (!valid) {
      return "-";
    }
    std::ostringstream ss;
    ss << reg << ":" << toHex(data);
    return ss.str();
  };
  auto blockBidLookup = [&](std::uint64_t blockUid, std::uint64_t rob) -> std::uint64_t {
    std::uint64_t bid = 0;
    const std::size_t robIdx = static_cast<std::size_t>(rob & 0x3Fu);
    if (robIdx < blockBidByRob.size()) {
      bid = blockBidByRob[robIdx];
    }
    if (blockUid != 0) {
      auto it = blockBidByUid.find(blockUid);
      if (it != blockBidByUid.end()) {
        if (bid == 0) {
          bid = it->second;
        } else if (it->second != bid) {
          it->second = bid;
        }
      } else if (bid != 0) {
        blockBidByUid.emplace(blockUid, bid);
      }
    }
    if (bid == 0 && blockUid != 0) {
      const std::uint64_t slot = (rob & 0xFu);
      const std::uint64_t hi = (nextSyntheticBlockBidHi++ & 0x0FFF'FFFF'FFFF'FFFFull);
      bid = (hi << 4) | slot;
      blockBidByUid[blockUid] = bid;
      if (robIdx < blockBidByRob.size()) {
        blockBidByRob[robIdx] = bid;
      }
    }
    return bid;
  };

  auto pvFlushAll = [&](const char *reason) {
    if (!linxtrace_writer.isOpen())
      return;
    (void)reason;
    for (const auto &kv : pvByUid) {
      const PvEntry &e = kv.second;
      linxtrace_writer.presenceV5(e.id, pvLaneToken(e.lane), "FLS", 1, "global_flush");
      linxtrace_writer.retire(e.id, e.id, 1);
      pvUidLastRetireCycle[kv.first] = curCycleTrace;
    }
    pvByUid.clear();
  };

  struct XcheckReportPaths {
    std::filesystem::path report_json{};
    std::filesystem::path report_md{};
    std::filesystem::path mismatches_json{};
  };
  auto resolveXcheckPaths = [&]() -> XcheckReportPaths {
    std::filesystem::path base{};
    if (const char *env = std::getenv("PYC_XCHECK_REPORT"); env && env[0] != '\0') {
      base = std::filesystem::path(env);
      if (base.has_extension()) {
        base = base.parent_path() / base.stem();
      }
    } else {
      const std::string stem = std::filesystem::path(memhPath).stem().string();
      base = std::filesystem::path("/Users/zhoubot/LinxCore/generated/linxtrace") /
             ((stem.empty() ? std::string("program") : stem) + "_crosscheck");
    }
    XcheckReportPaths out{};
    out.report_json = std::filesystem::path(base.string() + ".report.json");
    out.report_md = std::filesystem::path(base.string() + ".report.md");
    out.mismatches_json = std::filesystem::path(base.string() + ".mismatches.json");
    return out;
  };

  std::vector<XcheckMismatch> xcheckMismatches{};
  std::uint64_t xcheckCompared = 0;
  std::uint64_t xcheckQemuCursor = 0;
  std::uint64_t xcheckQemuMetaSkipped = 0;
  std::uint64_t xcheckDutMetaSkipped = 0;
  std::uint64_t xcheckQemuCbstop = 0;
  std::uint64_t xcheckDutCbstop = 0;
  bool xcheckTruncatedByMissingQemu = false;
  const auto xcheckPaths = resolveXcheckPaths();
  auto writeXcheckReport = [&]() {
    if (!xcheckEnabled) {
      return;
    }
    if (xcheckPaths.report_json.has_parent_path()) {
      std::filesystem::create_directories(xcheckPaths.report_json.parent_path());
    }
    if (xcheckPaths.report_md.has_parent_path()) {
      std::filesystem::create_directories(xcheckPaths.report_md.parent_path());
    }
    if (xcheckPaths.mismatches_json.has_parent_path()) {
      std::filesystem::create_directories(xcheckPaths.mismatches_json.parent_path());
    }

    const std::string inflRatio =
        (xcheckQemuCbstop == 0) ? ((xcheckDutCbstop > 0) ? "inf" : "1.0")
                                : std::to_string(static_cast<double>(xcheckDutCbstop) / static_cast<double>(xcheckQemuCbstop));

    std::ofstream js(xcheckPaths.report_json, std::ios::out | std::ios::trunc);
    if (js.is_open()) {
      js << "{\n";
      js << "  \"mode\":\"" << xcheckMode << "\",\n";
      js << "  \"qemu_trace\":\"" << jsonEscape(xcheckTraceEnv ? std::string(xcheckTraceEnv) : std::string("")) << "\",\n";
      js << "  \"qemu_rows\":" << qemuXcheckRows.size() << ",\n";
      js << "  \"compared\":" << xcheckCompared << ",\n";
      js << "  \"mismatch_count\":" << xcheckMismatches.size() << ",\n";
      js << "  \"qemu_cbstop\":" << xcheckQemuCbstop << ",\n";
      js << "  \"dut_cbstop\":" << xcheckDutCbstop << ",\n";
      js << "  \"qemu_meta_skipped\":" << xcheckQemuMetaSkipped << ",\n";
      js << "  \"dut_meta_skipped\":" << xcheckDutMetaSkipped << ",\n";
      js << "  \"cbstop_inflation\":\"" << inflRatio << "\",\n";
      js << "  \"truncated_by_missing_qemu\":" << (xcheckTruncatedByMissingQemu ? 1 : 0) << "\n";
      js << "}\n";
    }

    std::ofstream mm(xcheckPaths.mismatches_json, std::ios::out | std::ios::trunc);
    if (mm.is_open()) {
      mm << "[\n";
      for (std::size_t i = 0; i < xcheckMismatches.size(); i++) {
        const auto &m = xcheckMismatches[i];
        mm << "  {\"seq\":" << m.seq
           << ",\"field\":\"" << jsonEscape(m.field) << "\""
           << ",\"qemu\":" << m.qemu
           << ",\"dut\":" << m.dut
           << ",\"qemu_pc\":" << m.qemu_pc
           << ",\"dut_pc\":" << m.dut_pc
           << ",\"qemu_insn\":" << m.qemu_insn
           << ",\"dut_insn\":" << m.dut_insn
           << ",\"qemu_row\":{"
           << "\"pc\":" << m.qemu_row.pc
           << ",\"insn\":" << m.qemu_row.insn
           << ",\"len\":" << static_cast<unsigned>(m.qemu_row.len)
           << ",\"wb_valid\":" << m.qemu_row.wb_valid
           << ",\"wb_rd\":" << m.qemu_row.wb_rd
           << ",\"wb_data\":" << m.qemu_row.wb_data
           << ",\"src0_valid\":" << m.qemu_row.src0_valid
           << ",\"src0_reg\":" << m.qemu_row.src0_reg
           << ",\"src0_data\":" << m.qemu_row.src0_data
           << ",\"src1_valid\":" << m.qemu_row.src1_valid
           << ",\"src1_reg\":" << m.qemu_row.src1_reg
           << ",\"src1_data\":" << m.qemu_row.src1_data
           << ",\"dst_valid\":" << m.qemu_row.dst_valid
           << ",\"dst_reg\":" << m.qemu_row.dst_reg
           << ",\"dst_data\":" << m.qemu_row.dst_data
           << ",\"mem_valid\":" << m.qemu_row.mem_valid
           << ",\"mem_is_store\":" << m.qemu_row.mem_is_store
           << ",\"mem_addr\":" << m.qemu_row.mem_addr
           << ",\"mem_wdata\":" << m.qemu_row.mem_wdata
           << ",\"mem_rdata\":" << m.qemu_row.mem_rdata
           << ",\"mem_size\":" << m.qemu_row.mem_size
           << ",\"trap_valid\":" << m.qemu_row.trap_valid
           << ",\"trap_cause\":" << m.qemu_row.trap_cause
           << ",\"traparg0\":" << m.qemu_row.traparg0
           << ",\"next_pc\":" << m.qemu_row.next_pc
           << "}"
           << ",\"dut_row\":{"
           << "\"pc\":" << m.dut_row.pc
           << ",\"insn\":" << m.dut_row.insn
           << ",\"len\":" << static_cast<unsigned>(m.dut_row.len)
           << ",\"wb_valid\":" << m.dut_row.wb_valid
           << ",\"wb_rd\":" << m.dut_row.wb_rd
           << ",\"wb_data\":" << m.dut_row.wb_data
           << ",\"src0_valid\":" << m.dut_row.src0_valid
           << ",\"src0_reg\":" << m.dut_row.src0_reg
           << ",\"src0_data\":" << m.dut_row.src0_data
           << ",\"src1_valid\":" << m.dut_row.src1_valid
           << ",\"src1_reg\":" << m.dut_row.src1_reg
           << ",\"src1_data\":" << m.dut_row.src1_data
           << ",\"dst_valid\":" << m.dut_row.dst_valid
           << ",\"dst_reg\":" << m.dut_row.dst_reg
           << ",\"dst_data\":" << m.dut_row.dst_data
           << ",\"mem_valid\":" << m.dut_row.mem_valid
           << ",\"mem_is_store\":" << m.dut_row.mem_is_store
           << ",\"mem_addr\":" << m.dut_row.mem_addr
           << ",\"mem_wdata\":" << m.dut_row.mem_wdata
           << ",\"mem_rdata\":" << m.dut_row.mem_rdata
           << ",\"mem_size\":" << m.dut_row.mem_size
           << ",\"trap_valid\":" << m.dut_row.trap_valid
           << ",\"trap_cause\":" << m.dut_row.trap_cause
           << ",\"traparg0\":" << m.dut_row.traparg0
           << ",\"next_pc\":" << m.dut_row.next_pc
           << "}"
           << "}";
        if (i + 1 < xcheckMismatches.size()) {
          mm << ",";
        }
        mm << "\n";
      }
      mm << "]\n";
    }

    std::ofstream md(xcheckPaths.report_md, std::ios::out | std::ios::trunc);
    if (md.is_open()) {
      md << "# QEMU vs LinxCore Cross-Check\n\n";
      md << "- mode: `" << xcheckMode << "`\n";
      md << "- qemu trace: `" << (xcheckTraceEnv ? xcheckTraceEnv : "") << "`\n";
      md << "- qemu rows loaded: `" << qemuXcheckRows.size() << "`\n";
      md << "- compared commits: `" << xcheckCompared << "`\n";
      md << "- mismatches: `" << xcheckMismatches.size() << "`\n";
      md << "- C.BSTOP (QEMU): `" << xcheckQemuCbstop << "`\n";
      md << "- C.BSTOP (LinxCore): `" << xcheckDutCbstop << "`\n";
      md << "- metadata skipped (QEMU): `" << xcheckQemuMetaSkipped << "`\n";
      md << "- metadata skipped (LinxCore): `" << xcheckDutMetaSkipped << "`\n";
      md << "- C.BSTOP inflation: `" << inflRatio << "`\n\n";
      if (!xcheckMismatches.empty()) {
        const auto &m = xcheckMismatches.front();
        md << "## First mismatch\n\n";
        md << "- seq: `" << m.seq << "`\n";
        md << "- field: `" << m.field << "`\n";
        md << "- qemu: `" << toHex(m.qemu) << "`\n";
        md << "- dut: `" << toHex(m.dut) << "`\n";
        md << "- qemu_pc: `" << toHex(m.qemu_pc) << "`\n";
        md << "- dut_pc: `" << toHex(m.dut_pc) << "`\n";
        md << "- qemu_row: `" << xcheckCommitSummary(m.qemu_row) << "`\n";
        md << "- dut_row: `" << xcheckCommitSummary(m.dut_row) << "`\n";
      } else {
        md << "## First mismatch\n\n- none\n";
      }
    }
  };

  std::uint64_t commitTraceSeq = 0;
  std::uint64_t retireSeq = 0;
  std::uint64_t retiredCount = 0;
  std::uint64_t noRetireStreak = 0;
  std::uint64_t idleLoopMatchCount = 0;
  int idleLoopLastTag = -1;
  bool idleLoopReached = false;
  auto pvOnEvent = [&](std::uint64_t uid, int stageId, int lane, std::uint64_t pc, std::uint64_t rob,
                       std::uint64_t kind, std::uint64_t parentUid, std::uint64_t stall, std::uint64_t stallCause) {
    if (!linxtrace_writer.isOpen())
      return;
    if (uid == 0) {
      // Global redirect/fault probes do not identify a specific dynamic uop.
      // Keep per-uop retirement driven by explicit UID events only.
      return;
    }
    if (stageId < 0 || stageId >= static_cast<int>(kTraceStageNames.size()))
      return;
    auto rit = pvUidLastRetireCycle.find(uid);
    // Dynamic UID is single-lifetime: ignore any post-retire echoes.
    if (rit != pvUidLastRetireCycle.end())
      return;

    auto it = pvByUid.find(uid);
    if (it == pvByUid.end()) {
      auto fetched = fetchInsnAtPc(memShadow, pc);
      const std::uint64_t raw = fetched.first;
      const std::uint8_t len = normalizeLen(fetched.second);
      std::string disasm{};
      auto itObjdump = objdumpPcMap.find(pc);
      if (itObjdump != objdumpPcMap.end()) {
        disasm = itObjdump->second;
      } else {
        disasm = pvDisasmForLabel(lookupDisasm(raw, len), 0, raw, len);
      }
      PvEntry ent{};
      ent.id = pvNextTraceRowId++;
      ent.lane = lane;
      ent.stageId = -1;
      ent.pc = pc;
      ent.rob = rob;
      ent.raw = raw;
      ent.len = len;
      ent.disasm = disasm;
      linxtrace_writer.insnV5(ent.id, toHex(uid), 0, toHex(parentUid), pvKindText(kind));
      linxtrace_writer.label(ent.id, 0, pvInsnText(pc, disasm));
      std::ostringstream detail;
      detail << "uid=" << uid << " parent=" << parentUid << " kind=" << kind << " rob=" << rob;
      linxtrace_writer.label(ent.id, 1, detail.str());
      it = pvByUid.emplace(uid, std::move(ent)).first;
      pvUidToKid[uid] = it->second.id;
    }

    PvEntry &e = it->second;
    if (pc != 0)
      e.pc = pc;
    if (rob != 0)
      e.rob = rob;

    e.stageId = stageId;
    e.lane = lane;
    linxtrace_writer.presenceV5(
        e.id,
        pvLaneToken(lane),
        kTraceStageNames[static_cast<std::size_t>(stageId)],
        stall ? 1 : 0,
        std::to_string(stallCause));

    if (kind == kTraceKindFlush || kind == kTraceKindReplay || stageId == static_cast<int>(kTraceSidFls)) {
      if (stageId != static_cast<int>(kTraceSidFls)) {
        linxtrace_writer.presenceV5(e.id, pvLaneToken(e.lane), "FLS", 1, "flush");
      }
      linxtrace_writer.retire(e.id, e.id, 1);
      dfxRetiredKeysCycle.insert(pvRetireKey(e.pc, e.rob, e.lane));
      pvUidLastRetireCycle[uid] = curCycleTrace;
      pvByUid.erase(it);
      return;
    }

    if (kind == kTraceKindTrap || stageId == static_cast<int>(kTraceSidCmt)) {
      linxtrace_writer.retire(e.id, e.id, (kind == kTraceKindTrap) ? 1 : 0);
      dfxRetiredKeysCycle.insert(pvRetireKey(e.pc, e.rob, e.lane));
      pvUidLastRetireCycle[uid] = curCycleTrace;
      pvByUid.erase(it);
    }
  };

  bool icReqPending = false;
  bool icRspDriveNow = false;
  std::uint64_t icReqAddrPending = 0;
  std::uint64_t icReqRemainCycles = 0;
  auto buildIcacheLine = [&](std::uint64_t lineAddr) -> Wire<512> {
    return memShadow.buildIcacheLine(lineAddr);
  };

  struct CtuDebugSnapshot {
    bool pre = false;
    std::uint64_t cycle = 0;
    std::uint64_t core_pc = 0;
    std::uint64_t rob_head_pc = 0;
    std::uint64_t rob_head_op = 0;
    std::uint64_t br_kind = 0;
    std::uint64_t br_epoch = 0;
    std::uint64_t br_pred_take = 0;
    bool commit_cond = false;
    bool br_corr_pending = false;
    std::uint64_t br_corr_epoch = 0;
    std::uint64_t br_corr_take = 0;
    std::uint64_t br_corr_target = 0;
    bool redirect_from_corr = false;
    std::uint64_t rob_head_valid = 0;
    std::uint64_t rob_head_done = 0;
    bool macro_active = false;
    std::uint64_t macro_pc = 0;
    bool macro_wait_commit = false;
    bool macro_wait_commit_next = false;
    bool ctu_uop_valid = false;
    bool ctu_start_fire = false;
    bool ctu_head_ready = false;
    bool ctu_head_is_macro = false;
    bool ctu_head_skip = false;
    bool ctu_block_ifu = false;
    std::uint64_t force_pc = 0;
    bool ifu_valid = false;
    bool ifu_ready = false;
    std::uint64_t ifu_pc = 0;
    bool blk_evt_valid = false;
    std::uint64_t blk_evt_kind = 0;
    std::uint64_t blk_evt_pc = 0;
  };
  constexpr std::size_t kCtuDbgRingSize = 32;
  std::array<CtuDebugSnapshot, kCtuDbgRingSize> ctuDbgRing{};
  std::size_t ctuDbgHead = 0;
  std::size_t ctuDbgCount = 0;
  std::uint64_t ctuDbgForcePc = 0;
  std::uint32_t ctuDbgForceCycles = 0;
  auto pushCtuDbg = [&](const CtuDebugSnapshot &snap) {
    ctuDbgRing[ctuDbgHead] = snap;
    ctuDbgHead = (ctuDbgHead + 1) % kCtuDbgRingSize;
    if (ctuDbgCount < kCtuDbgRingSize) {
      ctuDbgCount++;
    }
  };
  auto dumpCtuDbg = [&]() {
    if (ctuDbgCount == 0) {
      return;
    }
    std::cerr << "  ctu_debug_recent (most recent last):\n";
    const std::size_t base = (ctuDbgHead + kCtuDbgRingSize - ctuDbgCount) % kCtuDbgRingSize;
    for (std::size_t i = 0; i < ctuDbgCount; i++) {
      const std::size_t idx = (base + i) % kCtuDbgRingSize;
      const auto &s = ctuDbgRing[idx];
      std::cerr << "    ph=" << (s.pre ? "pre" : "post")
                << " cyc=" << s.cycle
                << " core_pc=" << toHex(s.core_pc)
                << " ifu(v=" << (s.ifu_valid ? 1 : 0) << ",r=" << (s.ifu_ready ? 1 : 0)
                << ",pc=" << toHex(s.ifu_pc) << ")"
                << " rob_head_pc=" << toHex(s.rob_head_pc)
                << " rob_head_op=" << s.rob_head_op
                << " rob_head(v=" << s.rob_head_valid << ",d=" << s.rob_head_done << ")"
                << " br_kind=" << s.br_kind
                << " br_epoch=" << s.br_epoch
                << " pred_take=" << s.br_pred_take
                << " cond=" << (s.commit_cond ? 1 : 0)
                << " corr(p=" << (s.br_corr_pending ? 1 : 0)
                << ",e=" << s.br_corr_epoch
                << ",take=" << s.br_corr_take
                << ",tgt=" << toHex(s.br_corr_target) << ")"
                << " redir_corr=" << (s.redirect_from_corr ? 1 : 0)
                << " force_pc=" << toHex(s.force_pc)
                << " blk_evt(v=" << (s.blk_evt_valid ? 1 : 0) << ",k=" << s.blk_evt_kind
                << ",pc=" << toHex(s.blk_evt_pc) << ")"
                << " macro(active=" << (s.macro_active ? 1 : 0)
                << ",pc=" << toHex(s.macro_pc)
                << ",wait=" << (s.macro_wait_commit ? 1 : 0)
                << ",wait_n=" << (s.macro_wait_commit_next ? 1 : 0) << ")"
                << " ctu(uop=" << (s.ctu_uop_valid ? 1 : 0)
                << ",start=" << (s.ctu_start_fire ? 1 : 0)
                << ",head_ready=" << (s.ctu_head_ready ? 1 : 0)
                << ",head_macro=" << (s.ctu_head_is_macro ? 1 : 0)
                << ",head_skip=" << (s.ctu_head_skip ? 1 : 0)
                << ",block_ifu=" << (s.ctu_block_ifu ? 1 : 0) << ")"
                << "\n";
    }
  };

  struct BackendFocusSnapshot {
    std::uint64_t cycle = 0;
    std::uint64_t focusPc = 0;
    std::uint64_t headDone = 0;
    std::uint64_t s2Valid = 0;
    std::uint64_t s2Pc = 0;
    std::uint64_t decOp = 0;
    std::uint64_t dispatchFire = 0;
    std::uint64_t frontendReady = 0;
    std::uint64_t iqAllocOk = 0;
    std::uint64_t pregAllocOk = 0;
    std::uint64_t brobAllocFire = 0;
    std::uint64_t brobAllocReady = 0;
    std::uint64_t brobAllocBid = 0;
    std::uint64_t activeBlockBid = 0;
    std::uint64_t assignBlockBid = 0;
    std::uint64_t flushBid = 0;
    std::uint64_t commitRedirectValid = 0;
    std::uint64_t commitRedirectPc = 0;
    std::uint64_t commitRedirectBid = 0;
    std::uint64_t issueFire0 = 0;
    std::uint64_t issuePc0 = 0;
    std::uint64_t issueRob0 = 0;
    std::uint64_t issueFire2 = 0;
    std::uint64_t issuePc2 = 0;
    std::uint64_t issueRob2 = 0;
    std::uint64_t commitFire0 = 0;
    std::uint64_t commitPc0 = 0;
    std::uint64_t commitRob0 = 0;
    std::uint64_t commitFire1 = 0;
    std::uint64_t commitPc1 = 0;
    std::uint64_t commitRob1 = 0;
    std::uint64_t commitFire2 = 0;
    std::uint64_t commitPc2 = 0;
    std::uint64_t commitRob2 = 0;
    std::uint64_t commitFire3 = 0;
    std::uint64_t commitPc3 = 0;
    std::uint64_t commitRob3 = 0;
    std::uint64_t rawCommitFire0 = 0;
    std::uint64_t rawCommitPc0 = 0;
    std::uint64_t rawCommitRob0 = 0;
    std::uint64_t rawCommitFire1 = 0;
    std::uint64_t rawCommitPc1 = 0;
    std::uint64_t rawCommitRob1 = 0;
    std::uint64_t rawCommitFire2 = 0;
    std::uint64_t rawCommitPc2 = 0;
    std::uint64_t rawCommitRob2 = 0;
    std::uint64_t rawCommitFire3 = 0;
    std::uint64_t rawCommitPc3 = 0;
    std::uint64_t rawCommitRob3 = 0;
    std::uint64_t lsuResidentValid = 0;
    std::uint64_t lsuResidentPc = 0;
    std::uint64_t lsuPick0Valid = 0;
    std::uint64_t lsuPick0Pc = 0;
    std::uint64_t lsuInflightMask = 0;
    std::uint64_t lsuReadyMask = 0;
    std::uint64_t lsuWaitHit = 0;
    std::uint64_t lsuWaitSl = 0;
    std::uint64_t lsuWaitSr = 0;
    std::uint64_t lsuWaitSp = 0;
    std::uint64_t aluResidentValid = 0;
    std::uint64_t aluResidentPc = 0;
    std::uint64_t aluInflightMask = 0;
    std::uint64_t aluReadyMask = 0;
    std::uint64_t aluWaitHit = 0;
    std::uint64_t aluWaitSl = 0;
    std::uint64_t aluWaitSr = 0;
    std::uint64_t aluWaitSp = 0;
    std::uint64_t aluPick0Valid = 0;
    std::uint64_t aluPick0Pc = 0;
    std::uint64_t aluPick1Valid = 0;
    std::uint64_t aluPick1Pc = 0;
    std::uint64_t p1v0 = 0;
    std::uint64_t p1pc0 = 0;
    std::uint64_t p1v2 = 0;
    std::uint64_t p1pc2 = 0;
    std::uint64_t p1v3 = 0;
    std::uint64_t p1pc3 = 0;
    std::uint64_t e1v2 = 0;
    std::uint64_t e1pc2 = 0;
    std::uint64_t w1v2 = 0;
    std::uint64_t w1pc2 = 0;
    std::uint64_t w2v0 = 0;
    std::uint64_t w2pc0 = 0;
    std::uint64_t w2v2 = 0;
    std::uint64_t w2pc2 = 0;
    std::uint64_t w2v3 = 0;
    std::uint64_t w2pc3 = 0;
  };
  constexpr std::size_t kBackendFocusRingSize = 32;
  std::array<BackendFocusSnapshot, kBackendFocusRingSize> backendFocusRing{};
  std::size_t backendFocusHead = 0;
  std::size_t backendFocusCount = 0;
  auto pushBackendFocus = [&](const BackendFocusSnapshot &snap) {
    backendFocusRing[backendFocusHead] = snap;
    backendFocusHead = (backendFocusHead + 1) % kBackendFocusRingSize;
    if (backendFocusCount < kBackendFocusRingSize) {
      backendFocusCount++;
    }
  };
  auto dumpBackendFocus = [&]() {
    if (backendFocusCount == 0) {
      return;
    }
    std::cerr << "  backend_focus_recent (most recent last):\n";
    const std::size_t base = (backendFocusHead + kBackendFocusRingSize - backendFocusCount) % kBackendFocusRingSize;
    for (std::size_t i = 0; i < backendFocusCount; i++) {
      const auto &s = backendFocusRing[(base + i) % kBackendFocusRingSize];
      std::cerr << "    cyc=" << s.cycle
                << " focus_pc=" << toHex(s.focusPc)
                << " head_done=" << s.headDone
                << " s2(v=" << s.s2Valid << ",pc=" << toHex(s.s2Pc) << ",op=" << s.decOp << ")"
                << " disp(fire=" << s.dispatchFire
                << ",ready=" << s.frontendReady
                << ",iq_ok=" << s.iqAllocOk
                << ",preg_ok=" << s.pregAllocOk << ")"
                << " brob(f=" << s.brobAllocFire
                << ",r=" << s.brobAllocReady
                << ",bid=" << toHex(s.brobAllocBid)
                << ",act=" << toHex(s.activeBlockBid)
                << ",asn=" << toHex(s.assignBlockBid)
                << ",fl=" << toHex(s.flushBid) << ")"
                << " redir(v=" << s.commitRedirectValid
                << ",pc=" << toHex(s.commitRedirectPc)
                << ",bid=" << toHex(s.commitRedirectBid) << ")"
                << " issue0(f=" << s.issueFire0
                << ",pc=" << toHex(s.issuePc0)
                << ",rob=" << s.issueRob0 << ")"
                << " issue2(f=" << s.issueFire2
                << ",pc=" << toHex(s.issuePc2)
                << ",rob=" << s.issueRob2 << ")"
                << " cmt0(f=" << s.commitFire0
                << ",pc=" << toHex(s.commitPc0)
                << ",rob=" << s.commitRob0 << ")"
                << " cmt1(f=" << s.commitFire1
                << ",pc=" << toHex(s.commitPc1)
                << ",rob=" << s.commitRob1 << ")"
                << " cmt2(f=" << s.commitFire2
                << ",pc=" << toHex(s.commitPc2)
                << ",rob=" << s.commitRob2 << ")"
                << " cmt3(f=" << s.commitFire3
                << ",pc=" << toHex(s.commitPc3)
                << ",rob=" << s.commitRob3 << ")"
                << " raw0(f=" << s.rawCommitFire0
                << ",pc=" << toHex(s.rawCommitPc0)
                << ",rob=" << s.rawCommitRob0 << ")"
                << " raw1(f=" << s.rawCommitFire1
                << ",pc=" << toHex(s.rawCommitPc1)
                << ",rob=" << s.rawCommitRob1 << ")"
                << " raw2(f=" << s.rawCommitFire2
                << ",pc=" << toHex(s.rawCommitPc2)
                << ",rob=" << s.rawCommitRob2 << ")"
                << " raw3(f=" << s.rawCommitFire3
                << ",pc=" << toHex(s.rawCommitPc3)
                << ",rob=" << s.rawCommitRob3 << ")"
                << " lsu_iq(res=" << s.lsuResidentValid << "@" << toHex(s.lsuResidentPc)
                << ",pick0=" << s.lsuPick0Valid << "@" << toHex(s.lsuPick0Pc)
                << ",inflight=" << toHex(s.lsuInflightMask)
                << ",ready=" << toHex(s.lsuReadyMask)
                << ",wait=" << s.lsuWaitHit
                << "[" << s.lsuWaitSl << "," << s.lsuWaitSr << "," << s.lsuWaitSp << "])"
                << " alu_iq(res=" << s.aluResidentValid << "@" << toHex(s.aluResidentPc)
                << ",inflight=" << toHex(s.aluInflightMask)
                << ",ready=" << toHex(s.aluReadyMask)
                << ",wait=" << s.aluWaitHit
                << "[" << s.aluWaitSl << "," << s.aluWaitSr << "," << s.aluWaitSp << "]"
                << ",pick0=" << s.aluPick0Valid << "@" << toHex(s.aluPick0Pc)
                << ",pick1=" << s.aluPick1Valid << "@" << toHex(s.aluPick1Pc) << ")"
                << " p1(slot0=" << s.p1v0 << "@" << toHex(s.p1pc0)
                << ",slot2=" << s.p1v2 << "@" << toHex(s.p1pc2)
                << ",slot3=" << s.p1v3 << "@" << toHex(s.p1pc3) << ")"
                << " e1(slot2=" << s.e1v2 << "@" << toHex(s.e1pc2) << ")"
                << " w1(slot2=" << s.w1v2 << "@" << toHex(s.w1pc2) << ")"
                << " w2(slot0=" << s.w2v0 << "@" << toHex(s.w2pc0)
                << ",slot2=" << s.w2v2 << "@" << toHex(s.w2pc2)
                << ",slot3=" << s.w2v3 << "@" << toHex(s.w2pc3) << ")\n";
    }
  };

  while ((dut.cycles.value() - startCycle) < maxCycles) {
    const std::uint64_t cycleNow = dut.cycles.value();
    ifuStubFiredThisCycle = false;
    ifuStubFiredPcThisCycle = 0;
    ifuStubTraceCursorBeforeFire = ifuStubTraceCursor;

    if (xcheckEnabled && ctuDbgForceCycles == 0 && xcheckQemuCursor < qemuXcheckRows.size()) {
      const auto &qPeek = qemuXcheckRows[static_cast<std::size_t>(xcheckQemuCursor)];
      const std::uint8_t qLen = normalizeLen(qPeek.len);
      const std::uint64_t qInsn = maskInsn(qPeek.insn, qLen);
      const bool qIsMacro = (qLen == 4) && isMacroMarker32(static_cast<std::uint32_t>(qInsn & 0xFFFF'FFFFu));
      if (qIsMacro && qPeek.pc != 0) {
        ctuDbgForcePc = qPeek.pc;
        ctuDbgForceCycles = 32;
      }
    }

    bool ifuStubFire = false;
    if (!ifuStubPending.has_value()) {
      if (ifuStubFromQemu) {
        IfuStubPacket pkt{};
        std::size_t nextCursor = ifuStubTraceCursor;
        if (makeTraceIfuStubPacket(ifuStubTraceCursor, pkt, nextCursor)) {
          ifuStubPending = pkt;
          ifuStubPendingNextTraceCursor = nextCursor;
        }
      } else {
        // Default memh mode is conservative (1 insn per packet) for stability.
        // Enable packing explicitly when you want higher IPC from memh runs.
        const bool allowPack = envFlag("PYC_MEMH_PACK") && !dut.ctu_block_ifu.toBool();
        ifuStubPending = makeMemIfuStubPacket(ifuStubMemPc, allowPack);
      }
    }
    dut.tb_ifu_stub_enable = Wire<1>(1);
    if (ifuStubPending.has_value()) {
      const auto &pkt = *ifuStubPending;
      dut.tb_ifu_stub_valid = Wire<1>(1);
      dut.tb_ifu_stub_pc = Wire<64>(pkt.pc);
      dut.tb_ifu_stub_window = Wire<64>(pkt.window);
      dut.tb_ifu_stub_checkpoint = Wire<6>(pkt.checkpoint);
      dut.tb_ifu_stub_pkt_uid = Wire<64>(pkt.pkt_uid);
      ifuStubFire = dut.tb_ifu_stub_ready.toBool();
    } else {
      dut.tb_ifu_stub_valid = Wire<1>(0);
      dut.tb_ifu_stub_pc = Wire<64>(0);
      dut.tb_ifu_stub_window = Wire<64>(0);
      dut.tb_ifu_stub_checkpoint = Wire<6>(0);
      dut.tb_ifu_stub_pkt_uid = Wire<64>(0);
    }
    if (debugIfuStubCycles != 0 && (dut.cycles.value() - startCycle) < debugIfuStubCycles) {
      std::cerr << "[ifustub-pre] cyc=" << cycleNow
                << " tb_steps=" << tb.timeSteps()
                << " clk=" << dut.clk.toBool()
                << " rst=" << dut.rst.toBool()
                << " valid=" << dut.tb_ifu_stub_valid.toBool()
                << " ready=" << dut.tb_ifu_stub_ready.toBool()
                << " pending=" << (ifuStubPending.has_value() ? 1 : 0)
                << " pending_pc=" << toHex(ifuStubPending.has_value() ? ifuStubPending->pc : 0)
                << " drive_pc=" << toHex(dut.tb_ifu_stub_pc.value())
                << " pkt_uid=" << dut.tb_ifu_stub_pkt_uid.value()
                << " chk=" << dut.tb_ifu_stub_checkpoint.value()
                << " cycles_sig=" << dut.cycles.value()
                << "\n";
    }
    dut.ic_l2_req_ready = Wire<1>(icReqPending ? 0 : 1);
    if (icRspDriveNow) {
      dut.ic_l2_rsp_valid = Wire<1>(1);
      dut.ic_l2_rsp_addr = Wire<64>(icReqAddrPending);
      dut.ic_l2_rsp_data = buildIcacheLine(icReqAddrPending);
      dut.ic_l2_rsp_error = Wire<1>(0);
    } else {
      dut.ic_l2_rsp_valid = Wire<1>(0);
      dut.ic_l2_rsp_addr = Wire<64>(0);
      dut.ic_l2_rsp_data = Wire<512>(0);
      dut.ic_l2_rsp_error = Wire<1>(0);
    }

    const bool icReqSeenPre = (!icReqPending) && dut.ic_l2_req_valid.toBool() && dut.ic_l2_req_ready.toBool();
    const std::uint64_t icReqAddrPre = dut.ic_l2_req_addr.value() & ~0x3Full;
    if (debugIcache && cycleNow < debugIcacheCycles) {
      std::cerr << "[icdbg-pre] cyc=" << cycleNow
                << " req_v=" << dut.ic_l2_req_valid.toBool()
                << " req_r=" << dut.ic_l2_req_ready.toBool()
                << " req_a=0x" << std::hex << dut.ic_l2_req_addr.value()
                << " rsp_v=" << dut.ic_l2_rsp_valid.toBool()
                << " rsp_a=0x" << std::hex << dut.ic_l2_rsp_addr.value()
                << " miss_act=" << std::dec << dut.icache_miss_active_dbg.toBool()
                << " miss_w=" << dut.icache_miss_wait_dbg.toBool()
                << " miss_p=" << dut.icache_miss_phase_dbg.toBool()
                << " miss_n0=" << dut.icache_miss_need0_dbg.toBool()
                << " miss_n1=" << dut.icache_miss_need1_dbg.toBool()
                << " f1_v=" << dut.icache_f1_valid_dbg.toBool()
                << " f1_h=" << dut.icache_f1_hit_dbg.toBool()
                << " f1_m=" << dut.icache_f1_miss_dbg.toBool()
                << " f1_s=" << dut.icache_f1_stall_dbg.toBool()
                << " pend=" << icReqPending
                << " rem=" << std::dec << icReqRemainCycles
                << " pc=0x" << std::hex << dut.pc.value()
                << std::dec << "\n";
    }

    const std::uint64_t brKindPre = 0;
    const bool macroActivePre = readProbeBool(ctuDbgProbes.macroActive);
    const std::uint64_t macroPcPre = readProbeValue(ctuDbgProbes.macroPc);
    const bool macroWaitCommitPre = readProbeBool(ctuDbgProbes.macroWaitCommit);
    const bool macroWaitCommitNextPre = readProbeBool(ctuDbgProbes.macroWaitCommitNext);
    const bool ctuUopValidPre = readProbeBool(ctuDbgProbes.ctuUopValid);
    const bool ctuStartFirePre = readProbeBool(ctuDbgProbes.startFire);
    const bool ctuHeadReadyPre = readProbeBool(ctuDbgProbes.headReady);
    const bool ctuHeadIsMacroPre = readProbeBool(ctuDbgProbes.headIsMacro);
    const bool ctuHeadSkipPre = readProbeBool(ctuDbgProbes.headSkip);
    const bool ctuBlockIfuPre = dut.ctu_block_ifu.toBool();
    const bool blkEvtValidPre = false;
    const std::uint64_t blkEvtKindPre = 0;
    const std::uint64_t blkEvtPcPre = 0;
    const std::uint64_t robHeadValidPre = dut.rob_head_valid.value();
    const std::uint8_t headLenPre = normalizeLen(static_cast<std::uint8_t>(dut.rob_head_len.value() & 0x7u));
    const std::uint64_t headInsnPre = maskInsn(dut.rob_head_insn_raw.value(), headLenPre);
    const bool headInsnIsMacroPre = (headLenPre == 4) && isMacroMarker32(static_cast<std::uint32_t>(headInsnPre));
    if ((robHeadValidPre != 0 && headInsnIsMacroPre) || macroActivePre || macroWaitCommitPre || ctuStartFirePre ||
        ctuDbgForceCycles > 0) {
      pushCtuDbg(CtuDebugSnapshot{
          true,
          cycleNow,
          dut.pc.value(),
          dut.rob_head_pc.value(),
          dut.rob_head_op.value(),
          brKindPre,
          0,
          0,
          false,
          false,
          0,
          0,
          0,
          false,
          robHeadValidPre,
          dut.rob_head_done.value(),
          macroActivePre,
          macroPcPre,
          macroWaitCommitPre,
          macroWaitCommitNextPre,
          ctuUopValidPre,
          ctuStartFirePre,
          ctuHeadReadyPre,
          ctuHeadIsMacroPre,
          ctuHeadSkipPre,
          ctuBlockIfuPre,
          ctuDbgForcePc,
          dut.tb_ifu_stub_valid.toBool(),
          dut.tb_ifu_stub_ready.toBool(),
          dut.tb_ifu_stub_pc.value(),
          blkEvtValidPre,
          blkEvtKindPre,
          blkEvtPcPre,
      });
    }

    // Use Testbench fast-path when clock topology matches (single clock,
    // half-period 1). This cuts redundant comb eval work on negedge.
    tb.runCyclesAuto(1);
    if (debugIfuStubCycles != 0 && (cycleNow - startCycle) < debugIfuStubCycles) {
      std::cerr << "[ifustub-post] cyc=" << cycleNow
                << " tb_steps=" << tb.timeSteps()
                << " clk=" << dut.clk.toBool()
                << " rst=" << dut.rst.toBool()
                << " valid=" << dut.tb_ifu_stub_valid.toBool()
                << " ready=" << dut.tb_ifu_stub_ready.toBool()
                << " fire=" << (ifuStubFire ? 1 : 0)
                << " cycles_sig=" << dut.cycles.value()
                << " pc_sig=" << toHex(dut.pc.value())
                << "\n";
    }
    const bool ctuStartFirePost = readProbeBool(ctuDbgProbes.startFire);
    if (ifuStubFromQemu && ctuStartFirePost && ifuStubRows != nullptr) {
      // Macro start flushes younger frontend state. For the trace-fed IFU,
      // resume from the first QEMU row after the current macro's dynamic rows
      // so the post-template path stays aligned with commit order.
      const auto &rows = *ifuStubRows;
      const std::uint64_t macroPc = dut.rob_head_pc.value();
      std::size_t anchor = ifuStubTraceCursor;
      if (xcheckEnabled && ifuStubRows == &qemuXcheckRows) {
        anchor = std::min<std::size_t>(xcheckQemuCursor, rows.size());
      }

      std::size_t macroIdx = rows.size();
      for (std::size_t i = anchor; i < rows.size(); i++) {
        if (rows[i].pc == macroPc) {
          macroIdx = i;
          break;
        }
      }
      if (macroIdx >= rows.size()) {
        for (std::size_t i = 0; i < anchor; i++) {
          if (rows[i].pc == macroPc) {
            macroIdx = i;
            break;
          }
        }
      }
      if (macroIdx < rows.size()) {
        std::size_t resumeIdx = macroIdx;
        while (resumeIdx < rows.size() && rows[resumeIdx].pc == macroPc) {
          resumeIdx++;
        }
        ifuStubTraceCursor = resumeIdx;
        ifuStubPending.reset();
        ifuStubPendingNextTraceCursor = ifuStubTraceCursor;
        ifuStubFire = false;
      }
    }
    if (dut.dmem_wvalid.toBool()) {
      memShadow.storeGuestWord(dut.dmem_waddr.value(), dut.dmem_wdata.value(),
                               static_cast<std::uint8_t>(dut.dmem_wstrb.value()));
    }
    if (ifuStubFire && ifuStubPending.has_value()) {
      ifuStubFiredThisCycle = true;
      ifuStubFiredPcThisCycle = ifuStubPending->pc;
      ifuStubTraceCursorBeforeFire = ifuStubTraceCursor;
      ifuStubTraceCursorBeforeLastFire = ifuStubTraceCursorBeforeFire;
      if (ifuStubFromQemu) {
        ifuStubTraceCursor = ifuStubPendingNextTraceCursor;
        ifuStubTracePktUid++;
        ifuStubTraceCkptSeq += static_cast<std::uint64_t>(ifuStubPending->insn_count);
      } else {
        ifuStubMemPc += static_cast<std::uint64_t>(ifuStubPending->len_bytes);
        ifuStubMemPktUid++;
        ifuStubMemCkptSeq += static_cast<std::uint64_t>(ifuStubPending->insn_count);
      }
      ifuStubPending.reset();
    }
    ifuStubFiredLastCycle = ifuStubFiredThisCycle;
    ifuStubFiredPcLastCycle = ifuStubFiredPcThisCycle;

    if (icRspDriveNow) {
      icReqPending = false;
      icRspDriveNow = false;
      icReqAddrPending = 0;
      icReqRemainCycles = 0;
    } else if (icReqPending) {
      if (icReqRemainCycles > 0) {
        icReqRemainCycles--;
      }
      if (icReqRemainCycles == 0) {
        icRspDriveNow = true;
      }
    } else {
      const bool icReqSeenPost = dut.ic_l2_req_valid.toBool() && dut.ic_l2_req_ready.toBool();
      if (icReqSeenPre || icReqSeenPost) {
        icReqPending = true;
        icReqAddrPending = icReqSeenPre ? icReqAddrPre : (dut.ic_l2_req_addr.value() & ~0x3Full);
        icReqRemainCycles = icMissCycles;
        icRspDriveNow = false;
      }
    }
    if (debugIcache && cycleNow < debugIcacheCycles) {
      std::cerr << "[icdbg-post] cyc=" << cycleNow
                << " req_v=" << dut.ic_l2_req_valid.toBool()
                << " req_r=" << dut.ic_l2_req_ready.toBool()
                << " req_a=0x" << std::hex << dut.ic_l2_req_addr.value()
                << " rsp_v=" << dut.ic_l2_rsp_valid.toBool()
                << " rsp_a=0x" << std::hex << dut.ic_l2_rsp_addr.value()
                << " miss_act=" << std::dec << dut.icache_miss_active_dbg.toBool()
                << " miss_w=" << dut.icache_miss_wait_dbg.toBool()
                << " miss_p=" << dut.icache_miss_phase_dbg.toBool()
                << " miss_n0=" << dut.icache_miss_need0_dbg.toBool()
                << " miss_n1=" << dut.icache_miss_need1_dbg.toBool()
                << " f1_v=" << dut.icache_f1_valid_dbg.toBool()
                << " f1_h=" << dut.icache_f1_hit_dbg.toBool()
                << " f1_m=" << dut.icache_f1_miss_dbg.toBool()
                << " f1_s=" << dut.icache_f1_stall_dbg.toBool()
                << " pend=" << icReqPending
                << " rem=" << std::dec << icReqRemainCycles
                << " pc=0x" << std::hex << dut.pc.value()
                << std::dec << "\n";
    }

    curCycleTrace = cycleNow;
    dfxRetiredKeysCycle.clear();
    dfxTouchedUidCycle.clear();
    if (linxtrace_writer.isOpen()) {
      linxtrace_writer.atCycle(cycleNow);
    }
    std::unordered_map<std::uint64_t, int> commitUidLaneCycle{};
    {
      for (int slot = 0; slot < 4; ++slot) {
        const auto &probe = commitSlotProbes[static_cast<std::size_t>(slot)];
        if (!readProbeBool(probe.fire)) {
          continue;
        }
        const std::uint64_t uid = readProbeValue(probe.uopUid);
        if (uid != 0) {
          commitUidLaneCycle[uid] = slot;
        }
      }
    }

    if (readProbeBool(blockProbes.issueFire)) {
      const std::uint64_t bid = readProbeValue(blockProbes.issueBid);
      const std::size_t robIdx = static_cast<std::size_t>(readProbeValue(blockProbes.issueSrcRob) & 0x3Fu);
      if (bid != 0 && robIdx < blockBidByRob.size()) {
        blockBidByRob[robIdx] = bid;
      }
    }
    if (readProbeBool(blockProbes.rspValid)) {
      const std::uint64_t bid = readProbeValue(blockProbes.rspBid);
      const std::size_t robIdx = static_cast<std::size_t>(readProbeValue(blockProbes.rspSrcRob) & 0x3Fu);
      if (bid != 0 && robIdx < blockBidByRob.size()) {
        blockBidByRob[robIdx] = bid;
      }
    }

    if ((linxtrace_writer.isOpen() || rawTrace.is_open()) && !dut.halted.toBool()) {
      struct DfxEvent {
        std::uint64_t uid = 0;
        int probeSid = -1;
        int sid = -1;
        int lane = 0;
        std::uint64_t pc = 0;
        std::uint64_t rob = 0;
        std::uint64_t kind = 0;
        std::uint64_t parent = 0;
        std::uint64_t blockUid = 0;
        std::uint64_t blockBid = 0;
        std::uint64_t coreId = 0;
        std::uint64_t stall = 0;
        std::uint64_t stallCause = 0;
      };

      std::unordered_map<std::uint64_t, std::vector<DfxEvent>> dfxByUid{};
      std::vector<DfxEvent> probeEvents{};

      auto emit = [&](bool valid, std::uint64_t uid, int sid, int lane, std::uint64_t pc, std::uint64_t rob,
                      std::uint64_t kind, std::uint64_t parent, std::uint64_t blockUid = 0,
                      std::uint64_t coreId = 0, std::uint64_t stall = 0, std::uint64_t stallCause = 0) {
        if (!valid)
          return;
        DfxEvent ev{};
        ev.uid = uid;
        ev.probeSid = sid;
        ev.sid = canonicalStageFromProbeSid(sid, kind);
        ev.lane = lane;
        ev.pc = pc;
        ev.rob = rob;
        ev.kind = kind;
        ev.parent = parent;
        ev.blockUid = blockUid;
        ev.blockBid = blockBidLookup(blockUid, rob);
        ev.coreId = coreId;
        ev.stall = stall;
        ev.stallCause = stallCause;
        if (rawTraceProbes) {
          probeEvents.push_back(ev);
        }
        if (uid == 0 || ev.sid < 0) {
          return;
        }
        if (rawUidLastTerminalCycle.find(uid) != rawUidLastTerminalCycle.end()) {
          return;
        }
        dfxByUid[uid].push_back(ev);
      };

      for (const auto &probe : occProbes) {
        emit(readProbeBool(probe.valid),
             frontendProbeUid(probe.probeSid, readProbeValue(probe.uid)),
             probe.probeSid,
             probe.lane,
             readProbeValue(probe.pc),
             readProbeValue(probe.rob),
             readProbeValue(probe.kind),
             readProbeValue(probe.parent),
             readProbeValue(probe.blockUid),
             readProbeValue(probe.coreId),
             readProbeValue(probe.stall),
             readProbeValue(probe.stallCause));
      }

      auto eventKindPriority = [&](const DfxEvent &ev) -> int {
        if (ev.sid == static_cast<int>(kTraceSidFls) || ev.kind == kTraceKindFlush || ev.kind == kTraceKindReplay)
          return 3;
        if (ev.kind == kTraceKindTrap)
          return 2;
        return 1;
      };

      std::vector<DfxEvent> allEvents{};
      std::unordered_map<std::uint64_t, DfxEvent> bestEventByUid{};
      for (const auto &kv : dfxByUid) {
        const std::uint64_t uid = kv.first;
        const auto &vec = kv.second;
        if (vec.empty()) {
          continue;
        }

        int bestPrio = -1;
        for (const auto &ev : vec) {
          bestPrio = std::max(bestPrio, eventKindPriority(ev));
        }
        std::vector<DfxEvent> cand{};
        cand.reserve(vec.size());
        for (const auto &ev : vec) {
          if (eventKindPriority(ev) == bestPrio) {
            cand.push_back(ev);
          }
        }

        int prevSid = -1;
        auto itPrev = pvByUid.find(uid);
        if (itPrev != pvByUid.end()) {
          prevSid = itPrev->second.stageId;
        }

        auto pickByMinSid = [&](const std::vector<DfxEvent> &src) -> DfxEvent {
          DfxEvent best = src.front();
          for (const auto &ev : src) {
            if (ev.sid < best.sid || (ev.sid == best.sid && ev.lane < best.lane)) {
              best = ev;
            }
          }
          return best;
        };
        auto pickByMaxSid = [&](const std::vector<DfxEvent> &src) -> DfxEvent {
          DfxEvent best = src.front();
          for (const auto &ev : src) {
            if (ev.sid > best.sid || (ev.sid == best.sid && ev.lane < best.lane)) {
              best = ev;
            }
          }
          return best;
        };

        DfxEvent chosen = cand.front();
        auto itCommitLane = commitUidLaneCycle.find(uid);
        if (itCommitLane != commitUidLaneCycle.end()) {
          chosen = cand.front();
          chosen.sid = static_cast<int>(kTraceSidCmt);
          chosen.lane = itCommitLane->second;
          chosen.stall = 0;
          chosen.stallCause = 0;
        } else if (bestPrio >= 2) {
          // Terminal events (flush/trap) pick furthest stage.
          chosen = pickByMaxSid(cand);
        } else {
          bool foundAdvance = false;
          int advSid = 0x7fffffff;
          for (const auto &ev : cand) {
            if (prevSid >= 0 && ev.sid > prevSid && ev.sid < advSid) {
              advSid = ev.sid;
              chosen = ev;
              foundAdvance = true;
            }
          }
          if (!foundAdvance) {
            bool foundStay = false;
            for (const auto &ev : cand) {
              if (prevSid >= 0 && ev.sid == prevSid) {
                if (!foundStay || ev.lane < chosen.lane) {
                  chosen = ev;
                }
                foundStay = true;
              }
            }
            if (!foundStay) {
              chosen = pickByMinSid(cand);
            }
          }
        }

        bestEventByUid[uid] = chosen;
      }
      allEvents.reserve(bestEventByUid.size());
      for (const auto &kv : bestEventByUid) {
        allEvents.push_back(kv.second);
      }
      std::sort(allEvents.begin(), allEvents.end(), [&](const DfxEvent &a, const DfxEvent &b) {
        if (a.uid != b.uid)
          return a.uid < b.uid;
        if (a.sid != b.sid)
          return a.sid < b.sid;
        if (a.lane != b.lane)
          return a.lane < b.lane;
        return a.kind < b.kind;
      });
      for (const auto &ev : allEvents) {
        pvOnEvent(ev.uid, ev.sid, ev.lane, ev.pc, ev.rob, ev.kind, ev.parent, ev.stall, ev.stallCause);
        if (ev.uid != 0) {
          dfxTouchedUidCycle.insert(ev.uid);
          if (ev.sid == static_cast<int>(kTraceSidFls) || ev.sid == static_cast<int>(kTraceSidXchk) ||
              ev.kind == kTraceKindFlush || ev.kind == kTraceKindReplay) {
            rawUidLastTerminalCycle[ev.uid] = cycleNow;
          }
        }
      }
      if (rawTrace.is_open()) {
        if (rawTraceProbes) {
          auto *backend = dut.linxcore_top_root->janus_backend.get();
          auto *dispatch = backend->dispatch_frontend.get();
          auto *rename = backend->rename_bank.get();
          auto *rob = backend->rob_bank.get();
          auto *lsu = backend->lsu_stage.get();
          if (rename->lsid_checkpoint_capture_fire.value()) {
            rawTrace << "{"
                     << "\"type\":\"lsid_checkpoint_capture\","
                     << "\"cycle\":" << cycleNow << ","
                     << "\"bid\":" << rename->lsid_checkpoint_capture_bid.value() << ","
                     << "\"base\":" << rename->lsid_checkpoint_capture_base.value()
                     << "}\n";
          }
          if (lsu->issue_fire_lane0_raw.value() && !lsu->issue_fire_lane0_eff.value()) {
            rawTrace << "{"
                     << "\"type\":\"lsu_issue_block\","
                     << "\"cycle\":" << cycleNow << ","
                     << "\"pc\":" << backend->iq_lsu_bank->issue_pick_pc0_o.value() << ","
                     << "\"rob\":" << backend->iq_lsu_bank->issue_pick_rob0_o.value() << ","
                     << "\"is_load\":" << lsu->ex0_is_load.value() << ","
                     << "\"is_store\":" << lsu->ex0_is_store.value() << ","
                     << "\"lsid\":" << lsu->ex0_lsid.value() << ","
                     << "\"issue_ptr\":" << lsu->lsid_issue_ptr.value() << ","
                     << "\"lsid_block\":" << lsu->lsu_lsid_block_lane0.value() << ","
                     << "\"older_store\":" << lsu->rob_older_store_pending_lane0.value()
                     << "}\n";
          }
          if (rename->do_flush.value()) {
            rawTrace << "{"
                     << "\"type\":\"rename_flush\","
                     << "\"cycle\":" << cycleNow << ","
                     << "\"free_mask\":" << rename->free_mask_q.value() << ","
                     << "\"flush_bid\":" << rob->flush_bid.value() << ","
                     << "\"survivor_pdst_mask\":" << rob->flush_survivor_pdst_mask_o.value() << ","
                     << "\"lsid_checkpoint_valid\":" << rename->lsid_checkpoint_restore_valid_o.value() << ","
                     << "\"lsid_checkpoint_base\":" << rename->lsid_checkpoint_restore_base_o.value()
                     << "}\n";
          }
          auto writeRenameCommit = [&](int slot,
                                       bool fire,
                                       std::uint64_t dstKind,
                                       std::uint64_t dstAreg,
                                       std::uint64_t pdst) {
            if (!fire) {
              return;
            }
            rawTrace << "{"
                     << "\"type\":\"rename_commit\","
                     << "\"cycle\":" << cycleNow << ","
                     << "\"slot\":" << slot << ","
                     << "\"dst_kind\":" << dstKind << ","
                     << "\"dst_areg\":" << dstAreg << ","
                     << "\"pdst\":" << pdst << ","
                     << "\"free_mask\":" << rename->free_mask_q.value() << ","
                     << "\"cmap4\":" << rename->cmap4.value() << ","
                     << "\"cmap24\":" << rename->cmap24.value()
                     << "}\n";
          };
          writeRenameCommit(0, rename->commit_fire0.value(), rename->rob_dst_kind0.value(),
                            rename->rob_dst_areg0.value(), rename->rob_pdst0.value());
          writeRenameCommit(1, rename->commit_fire1.value(), rename->rob_dst_kind1.value(),
                            rename->rob_dst_areg1.value(), rename->rob_pdst1.value());
          writeRenameCommit(2, rename->commit_fire2.value(), rename->rob_dst_kind2.value(),
                            rename->rob_dst_areg2.value(), rename->rob_pdst2.value());
          writeRenameCommit(3, rename->commit_fire3.value(), rename->rob_dst_kind3.value(),
                            rename->rob_dst_areg3.value(), rename->rob_pdst3.value());
          auto writeIqAlloc = [&](const char *bank,
                                  int slot,
                                  bool fire,
                                  std::uint64_t rob,
                                  std::uint64_t pc,
                                  std::uint64_t op,
                                  std::uint64_t srcl,
                                  std::uint64_t srcr,
                                  std::uint64_t srcp,
                                  std::uint64_t pdst,
                                  std::uint64_t hasDst) {
            if (!fire) {
              return;
            }
            rawTrace << "{"
                     << "\"type\":\"iq_alloc\","
                     << "\"cycle\":" << cycleNow << ","
                     << "\"bank\":\"" << bank << "\","
                     << "\"slot\":" << slot << ","
                     << "\"rob\":" << rob << ","
                     << "\"pc\":" << pc << ","
                     << "\"op\":" << op << ","
                     << "\"srcl\":" << srcl << ","
                     << "\"srcr\":" << srcr << ","
                     << "\"srcp\":" << srcp << ","
                     << "\"pdst\":" << pdst << ","
                     << "\"has_dst\":" << hasDst << ","
                     << "\"rename_free_mask\":" << rename->free_mask_q.value() << ","
                     << "\"dispatch_alloc_mask\":" << dispatch->disp_alloc_mask.value() << ","
                     << "\"rename_flush\":" << rename->do_flush.value()
                     << "}\n";
          };
          auto emitIqAllocs = [&](const char *bank, auto *iq) {
            writeIqAlloc(bank, 0, iq->disp_fire0.value() && iq->disp_to0.value(), iq->disp_rob_idx0.value(),
                         iq->disp_pc0.value(), iq->disp_op0.value(), iq->disp_srcl_tag0.value(),
                         iq->disp_srcr_tag0.value(), iq->disp_srcp_tag0.value(), iq->disp_pdst0.value(),
                         iq->disp_need_pdst0.value());
            writeIqAlloc(bank, 1, iq->disp_fire1.value() && iq->disp_to1.value(), iq->disp_rob_idx1.value(),
                         iq->disp_pc1.value(), iq->disp_op1.value(), iq->disp_srcl_tag1.value(),
                         iq->disp_srcr_tag1.value(), iq->disp_srcp_tag1.value(), iq->disp_pdst1.value(),
                         iq->disp_need_pdst1.value());
            writeIqAlloc(bank, 2, iq->disp_fire2.value() && iq->disp_to2.value(), iq->disp_rob_idx2.value(),
                         iq->disp_pc2.value(), iq->disp_op2.value(), iq->disp_srcl_tag2.value(),
                         iq->disp_srcr_tag2.value(), iq->disp_srcp_tag2.value(), iq->disp_pdst2.value(),
                         iq->disp_need_pdst2.value());
            writeIqAlloc(bank, 3, iq->disp_fire3.value() && iq->disp_to3.value(), iq->disp_rob_idx3.value(),
                         iq->disp_pc3.value(), iq->disp_op3.value(), iq->disp_srcl_tag3.value(),
                         iq->disp_srcr_tag3.value(), iq->disp_srcp_tag3.value(), iq->disp_pdst3.value(),
                         iq->disp_need_pdst3.value());
          };
          emitIqAllocs("lsu", backend->iq_lsu_bank.get());
          emitIqAllocs("bru", backend->iq_bru_bank.get());
          emitIqAllocs("alu", backend->iq_alu_bank.get());
          emitIqAllocs("cmd", backend->iq_cmd_bank.get());
        }
        for (const auto &ev : allEvents) {
          const char *stageName = "UNK";
          if (ev.sid >= 0 && ev.sid < static_cast<int>(kTraceStageNames.size())) {
            stageName = kTraceStageNames[static_cast<std::size_t>(ev.sid)];
          }
          rawTrace << "{"
                   << "\"type\":\"occ\","
                   << "\"cycle\":" << cycleNow << ","
                   << "\"core_id\":" << ev.coreId << ","
                   << "\"stage\":\"" << stageName << "\","
                   << "\"lane\":" << ev.lane << ","
                   << "\"uop_uid\":" << ev.uid << ","
                   << "\"parent_uid\":" << ev.parent << ","
                   << "\"block_uid\":" << ev.blockUid << ","
                   << "\"block_bid\":" << ev.blockBid << ","
                   << "\"kind\":" << ev.kind << ","
                   << "\"stall\":" << ev.stall << ","
                   << "\"stall_cause\":" << ev.stallCause << ","
                   << "\"pc\":" << ev.pc << ","
                   << "\"rob\":" << ev.rob
                   << "}\n";
        }
        if (rawTraceProbes) {
          std::sort(probeEvents.begin(), probeEvents.end(), [&](const DfxEvent &a, const DfxEvent &b) {
            if (a.uid != b.uid)
              return a.uid < b.uid;
            if (a.probeSid != b.probeSid)
              return a.probeSid < b.probeSid;
            if (a.lane != b.lane)
              return a.lane < b.lane;
            if (a.kind != b.kind)
              return a.kind < b.kind;
            if (a.pc != b.pc)
              return a.pc < b.pc;
            if (a.rob != b.rob)
              return a.rob < b.rob;
            return a.parent < b.parent;
          });
          probeEvents.erase(std::unique(probeEvents.begin(), probeEvents.end(), [&](const DfxEvent &a, const DfxEvent &b) {
                             return a.uid == b.uid && a.probeSid == b.probeSid && a.lane == b.lane &&
                                    a.kind == b.kind && a.pc == b.pc && a.rob == b.rob &&
                                    a.parent == b.parent;
                           }),
                           probeEvents.end());
          for (const auto &ev : probeEvents) {
            const char *stageName = "UNK";
            if (ev.probeSid >= 0 && ev.probeSid < static_cast<int>(kTraceProbeStageNames.size())) {
              stageName = kTraceProbeStageNames[static_cast<std::size_t>(ev.probeSid)];
            }
            rawTrace << "{"
                     << "\"type\":\"probe_occ\","
                     << "\"cycle\":" << cycleNow << ","
                     << "\"core_id\":" << ev.coreId << ","
                     << "\"stage\":\"" << stageName << "\","
                     << "\"lane\":" << ev.lane << ","
                     << "\"uop_uid\":" << ev.uid << ","
                     << "\"parent_uid\":" << ev.parent << ","
                     << "\"block_uid\":" << ev.blockUid << ","
                     << "\"block_bid\":" << ev.blockBid << ","
                     << "\"kind\":" << ev.kind << ","
                     << "\"stall\":" << ev.stall << ","
                     << "\"stall_cause\":" << ev.stallCause << ","
                     << "\"pc\":" << ev.pc << ","
                     << "\"rob\":" << ev.rob
                     << "}\n";
          }
        }
      }
    }

    const bool redirectBlkEvt = readProbeBool(commitRedirectProbes.valid);
    const bool faultBlkEvt = readProbeBool(commitRedirectProbes.bruFaultSet) && !redirectBlkEvt;
    const bool blkEvtValid = redirectBlkEvt || faultBlkEvt;
    const std::uint64_t kindVal = redirectBlkEvt ? 3u : (faultBlkEvt ? 4u : 0u);
    const std::uint64_t blkEvtPc =
        redirectBlkEvt ? readProbeValue(commitRedirectProbes.pc) : (faultBlkEvt ? dut.pc.value() : 0u);
    const std::uint64_t blkEvtBid =
        redirectBlkEvt ? readProbeValue(commitRedirectProbes.bid) : (faultBlkEvt ? readProbeValue(blockProbes.activeBid) : 0u);
    if (blkEvtValid) {
      if (kindVal == 3) {
        // Redirect events are the canonical host-side PC steering for the
        // IB-fed frontend (no on-chip IFU/ICache path).
        const std::uint64_t redirPc = blkEvtPc;
        if (redirPc != 0) {
          if (!ifuStubFromQemu) {
            ifuStubMemPc = redirPc;
            ifuStubPending.reset();
          } else if (ifuStubRows != nullptr) {
            if (ifuStubFiredThisCycle && ifuStubFiredPcThisCycle == redirPc) {
              ifuStubTraceCursor = ifuStubTraceCursorBeforeFire;
              ifuStubPending.reset();
              ifuStubPendingNextTraceCursor = ifuStubTraceCursor;
            } else {
            // Trace-fed mode: resync around the committed stream position, not
            // the prefetch cursor. The IFU stub can run far ahead of retire,
            // so matching only "the next PC at/after the host cursor" can jump
            // to the wrong dynamic instance when loops revisit the same PC.
            const auto &rows = *ifuStubRows;
            auto isUsableRedirectRow = [&](const XcheckCommit &r) -> bool {
              const std::uint8_t len = normalizeLen(static_cast<std::uint8_t>(r.len & 0xFFu));
              return (len == 2 || len == 4 || len == 6) && r.pc == redirPc;
            };

            std::size_t anchor = ifuStubTraceCursor;
            if (xcheckEnabled && ifuStubRows == &qemuXcheckRows) {
              anchor = std::min<std::size_t>(xcheckQemuCursor, rows.size());
            }

            std::size_t i = rows.size();
            std::size_t bestDist = rows.size();
            for (std::size_t cand = 0; cand < rows.size(); cand++) {
              const auto &r = rows[cand];
              if (!isUsableRedirectRow(r)) {
                continue;
              }
              const std::size_t dist = (cand >= anchor) ? (cand - anchor) : (anchor - cand);
              if (dist < bestDist || (dist == bestDist && cand >= anchor && (i < anchor || cand < i))) {
                i = cand;
                bestDist = dist;
                if (dist == 0) {
                  break;
                }
              }
            }
            if (i >= rows.size()) {
              std::cerr << "error: IFU stub trace redirect PC not found: pc=" << toHex(redirPc)
                        << " cursor=" << ifuStubTraceCursor
                        << " anchor=" << anchor
                        << " rows=" << rows.size() << "\n";
              const std::size_t dumpBegin = (anchor > 4) ? (anchor - 4) : 0;
              const std::size_t dumpEnd = std::min<std::size_t>(rows.size(), anchor + 5);
              std::cerr << "  qemu_rows_near_anchor:\n";
              for (std::size_t dumpIdx = dumpBegin; dumpIdx < dumpEnd; dumpIdx++) {
                const auto &r = rows[dumpIdx];
                std::cerr << "    idx=" << dumpIdx
                          << " pc=" << toHex(r.pc)
                          << " len=" << static_cast<unsigned>(normalizeLen(static_cast<std::uint8_t>(r.len & 0xFFu)))
                          << " tmpl=" << r.template_kind
                          << " insn=" << toHex(maskInsn(r.insn, normalizeLen(static_cast<std::uint8_t>(r.len & 0xFFu))))
                          << "\n";
              }
              dumpBackendFocus();
              dumpCtuDbg();
              dumpSimStats();
              return 1;
            }
            ifuStubTraceCursor = i;
            ifuStubPending.reset();
            ifuStubPendingNextTraceCursor = ifuStubTraceCursor;
            }
          }
          ctuDbgForcePc = redirPc;
          ctuDbgForceCycles = 16;
        }
      }
      std::string kindText = "open";
      bool emitEvt = true;
      if (kindVal == 2) {
        // ROB-side block resolution is emitted from commit is_bstop events below
        // so block lifecycle is strictly open->resolved->retired.
        emitEvt = false;
      } else if (kindVal == 3) {
        kindText = "redirect";
      } else if (kindVal == 4) {
        kindText = "fault";
      }
      if (rawTrace.is_open() && emitEvt) {
        std::uint64_t evtBlockUid = 0;
        const std::uint64_t evtBlockBid = blkEvtBid;
        if (evtBlockBid != 0) {
          const auto uidIt = blockUidByBid.find(evtBlockBid);
          if (uidIt != blockUidByBid.end()) {
            evtBlockUid = uidIt->second;
          }
        }
        if (evtBlockUid != 0 && evtBlockBid != 0) {
          blockUidByBid[evtBlockBid] = evtBlockUid;
        }
        rawTrace << "{"
                 << "\"type\":\"blk_evt\","
                 << "\"cycle\":" << cycleNow << ","
                 << "\"kind\":\"" << kindText << "\","
                 << "\"kind_code\":" << kindVal << ","
                 << "\"block_uid\":" << evtBlockUid << ","
                 << "\"block_bid\":" << evtBlockBid << ","
                 << "\"core_id\":0,"
                 << "\"pc\":" << blkEvtPc << ","
                 << "\"seq\":0"
                 << "}\n";
      }
    }

    if (rawTrace.is_open() && readProbeBool(blockProbes.retireFire)) {
      const std::uint64_t retiredBid = readProbeValue(blockProbes.retireBid);
      std::uint64_t retiredUid = 0;
      const auto uidIt = blockUidByBid.find(retiredBid);
      if (uidIt != blockUidByBid.end()) {
        retiredUid = uidIt->second;
      }
      rawTrace << "{"
               << "\"type\":\"blk_evt\","
               << "\"cycle\":" << cycleNow << ","
               << "\"kind\":\"retired\","
               << "\"kind_code\":5,"
               << "\"block_uid\":" << retiredUid << ","
               << "\"block_bid\":" << retiredBid << ","
               << "\"core_id\":0,"
               << "\"pc\":0,"
               << "\"seq\":" << retireSeq
               << "}\n";
    }

    bool retiredThisCycle = false;
    bool stopMaxCommits = false;
    const std::uint64_t brKindDbg = readProbeValue(commitDbgProbes.brKind);
    const std::uint64_t brEpochDbg = readProbeValue(commitDbgProbes.brEpoch);
    const bool bruValidateDbg = false;
    const bool bruMismatchDbg = false;
    const std::uint64_t bruActualTakeDbg = readProbeValue(commitDbgProbes.brCorrTake);
    const std::uint64_t bruPredTakeDbg = readProbeValue(commitDbgProbes.brPredTake);
    const std::uint64_t bruBoundaryPcDbg =
        readProbeValue(commitDbgProbes.brBase) + readProbeValue(commitDbgProbes.brOff);
    const bool bruCorrPendingDbg = readProbeBool(commitDbgProbes.brCorrPending);
    const std::uint64_t bruCorrEpochDbg = readProbeValue(commitDbgProbes.brCorrEpoch);
    const std::uint64_t bruCorrTargetDbg = readProbeValue(commitDbgProbes.brCorrTarget);
    const bool redirectFromCorrDbg = readProbeBool(commitDbgProbes.redirectFromCorr);
    const bool redirectFromBoundaryDbg = false;
    const bool bruFaultSetDbg = readProbeBool(commitRedirectProbes.bruFaultSet);
    const bool macroActiveDbg = readProbeBool(ctuDbgProbes.macroActive);
    const std::uint64_t macroPcDbg = readProbeValue(ctuDbgProbes.macroPc);
    const bool macroWaitCommitDbg = readProbeBool(ctuDbgProbes.macroWaitCommit);
    const bool macroWaitCommitNextDbg = readProbeBool(ctuDbgProbes.macroWaitCommitNext);
    const bool ctuUopValidDbg = readProbeBool(ctuDbgProbes.ctuUopValid);
    const bool ctuStartFireDbg = readProbeBool(ctuDbgProbes.startFire);
    const bool ctuHeadReadyDbg = readProbeBool(ctuDbgProbes.headReady);
    const bool ctuHeadIsMacroDbg = readProbeBool(ctuDbgProbes.headIsMacro);
    const bool ctuHeadSkipDbg = readProbeBool(ctuDbgProbes.headSkip);
    const bool ctuBlockIfuDbg = dut.ctu_block_ifu.toBool();
    const bool blkEvtValidDbg = blkEvtValid;
    const std::uint64_t blkEvtKindDbg = kindVal;
    const std::uint64_t blkEvtPcDbg = blkEvtPc;

    if (debugMacroPrf && (ctuUopValidDbg || ctuStartFireDbg)) {
      auto *backend = dut.linxcore_top_root->janus_backend.get();
      auto *rename = backend->rename_bank.get();
      auto *prf = backend->prf.get();
      std::cerr << "macro_prf_dfx cycle=" << cycleNow
                << " head_pc=" << toHex(dut.rob_head_pc.value())
                << " start=" << (ctuStartFireDbg ? 1 : 0)
                << " uop=" << (ctuUopValidDbg ? 1 : 0)
                << " map(sp=s" << rename->smap1.value()
                << ",c" << rename->cmap1.value()
                << ") ready=" << toHex(rename->ready_mask_q.value())
                << " macro_prf(we=" << prf->wen8.value()
                << ",tag=" << prf->waddr8.value()
                << ",data=" << toHex(prf->wdata8.value()) << ")\n";
    }

    if (ctuHeadIsMacroDbg || macroActiveDbg || macroWaitCommitDbg || ctuStartFireDbg || ctuDbgForceCycles > 0) {
      pushCtuDbg(CtuDebugSnapshot{
          false,
          cycleNow,
          dut.pc.value(),
          dut.rob_head_pc.value(),
          dut.rob_head_op.value(),
          brKindDbg,
          brEpochDbg,
          bruPredTakeDbg,
          readProbeBool(commitDbgProbes.commitCond),
          bruCorrPendingDbg,
          bruCorrEpochDbg,
          bruActualTakeDbg,
          bruCorrTargetDbg,
          redirectFromCorrDbg,
          dut.rob_head_valid.value(),
          dut.rob_head_done.value(),
          macroActiveDbg,
          macroPcDbg,
          macroWaitCommitDbg,
          macroWaitCommitNextDbg,
          ctuUopValidDbg,
          ctuStartFireDbg,
          ctuHeadReadyDbg,
          ctuHeadIsMacroDbg,
          ctuHeadSkipDbg,
          ctuBlockIfuDbg,
          ctuDbgForcePc,
          dut.tb_ifu_stub_valid.toBool(),
          dut.tb_ifu_stub_ready.toBool(),
          dut.tb_ifu_stub_pc.value(),
          blkEvtValidDbg,
          blkEvtKindDbg,
          blkEvtPcDbg,
      });
    }

    if (ctuDbgForceCycles > 0) {
      ctuDbgForceCycles--;
      if (ctuDbgForceCycles == 0) {
        ctuDbgForcePc = 0;
      }
    }

    if (((dut.cycles.value() - startCycle) <= 24) || ctuDbgForceCycles > 0 || xcheckEnabled || noRetireStreak >= 16) {
      auto *backend = dut.linxcore_top_root->janus_backend.get();
      auto *dispatch = backend->dispatch_frontend.get();
      auto *commitTrace = backend->backend_commit_trace.get();
      auto *aluIq = backend->iq_alu_bank.get();
      auto *lsuIq = backend->iq_lsu_bank.get();
      auto *execPipe = backend->backend_exec_pipe.get();
      pushBackendFocus(BackendFocusSnapshot{
          dut.cycles.value(),
          dut.rob_head_valid.value() ? dut.rob_head_pc.value() : 0,
          dut.rob_head_done.value(),
          dispatch->probe_s2_valid_0.value(),
          dispatch->probe_s2_pc_0.value(),
          dispatch->dec_op.value(),
          dispatch->dispatch_fire.value(),
          dispatch->frontend_ready.value(),
          dispatch->iq_alloc_ok.value(),
          dispatch->preg_alloc_ok.value(),
          readProbeValue(backendDbgProbes.brobAllocFire),
          readProbeValue(blockProbes.allocReady),
          readProbeValue(blockProbes.allocBid),
          readProbeValue(blockProbes.activeBid),
          readProbeValue(backendDbgProbes.assignBlockBid),
          readProbeValue(backendDbgProbes.flushBid),
          readProbeValue(commitRedirectProbes.valid),
          readProbeValue(commitRedirectProbes.pc),
          readProbeValue(commitRedirectProbes.bid),
          lsuIq->issue_pick_valid0_o.value(),
          lsuIq->issue_pick_pc0_o.value(),
          lsuIq->issue_pick_rob0_o.value(),
          aluIq->issue_pick_valid0_o.value(),
          aluIq->issue_pick_pc0_o.value(),
          aluIq->issue_pick_rob0_o.value(),
          commitTrace->commit_fire0.value(),
          commitTrace->commit_pc0.value(),
          commitTrace->commit_rob0.value(),
          commitTrace->commit_fire1.value(),
          commitTrace->commit_pc1.value(),
          commitTrace->commit_rob1.value(),
          commitTrace->commit_fire2.value(),
          commitTrace->commit_pc2.value(),
          commitTrace->commit_rob2.value(),
          commitTrace->commit_fire3.value(),
          commitTrace->commit_pc3.value(),
          commitTrace->commit_rob3.value(),
          backend->raw_commit_fire0_dbg.value(),
          backend->raw_commit_pc0_dbg.value(),
          backend->raw_commit_rob0_dbg.value(),
          backend->raw_commit_fire1_dbg.value(),
          backend->raw_commit_pc1_dbg.value(),
          backend->raw_commit_rob1_dbg.value(),
          backend->raw_commit_fire2_dbg.value(),
          backend->raw_commit_pc2_dbg.value(),
          backend->raw_commit_rob2_dbg.value(),
          backend->raw_commit_fire3_dbg.value(),
          backend->raw_commit_pc3_dbg.value(),
          backend->raw_commit_rob3_dbg.value(),
          lsuIq->resident_valid_o.value(),
          lsuIq->resident_pc_o.value(),
          lsuIq->issue_pick_valid0_o.value(),
          lsuIq->issue_pick_pc0_o.value(),
          lsuIq->inflight_mask.value(),
          lsuIq->ready_mask.value(),
          lsuIq->head_wait_hit_o.value(),
          lsuIq->head_wait_sl_o.value(),
          lsuIq->head_wait_sr_o.value(),
          lsuIq->head_wait_sp_o.value(),
          aluIq->resident_valid_o.value(),
          aluIq->resident_pc_o.value(),
          aluIq->inflight_mask.value(),
          aluIq->ready_mask.value(),
          aluIq->head_wait_hit_o.value(),
          aluIq->head_wait_sl_o.value(),
          aluIq->head_wait_sr_o.value(),
          aluIq->head_wait_sp_o.value(),
          aluIq->issue_pick_valid0_o.value(),
          aluIq->issue_pick_pc0_o.value(),
          aluIq->issue_pick_valid1_o.value(),
          aluIq->issue_pick_pc1_o.value(),
          execPipe->probe_p1_valid_0.value(),
          execPipe->probe_p1_pc_0.value(),
          execPipe->probe_p1_valid_2.value(),
          execPipe->probe_p1_pc_2.value(),
          execPipe->probe_p1_valid_3.value(),
          execPipe->probe_p1_pc_3.value(),
          execPipe->probe_e1_valid_2.value(),
          execPipe->probe_e1_pc_2.value(),
          execPipe->probe_w1_valid_2.value(),
          execPipe->probe_w1_pc_2.value(),
          execPipe->probe_w2_valid_0.value(),
          execPipe->probe_w2_pc_0.value(),
          execPipe->probe_w2_valid_2.value(),
          execPipe->probe_w2_pc_2.value(),
          execPipe->probe_w2_valid_3.value(),
          execPipe->probe_w2_pc_3.value(),
      });
    }

    for (int slot = 0; slot < 4; slot++) {
      bool fire = false;
      std::uint64_t pc = 0;
      std::uint8_t templateKind = 0;
      std::uint64_t insnRaw = 0;
      std::uint8_t len = 0;
      bool wbValid = false;
      std::uint32_t wbRd = 0;
      std::uint64_t wbData = 0;
      bool src0Valid = false;
      std::uint32_t src0Reg = 0;
      std::uint64_t src0Data = 0;
      bool src1Valid = false;
      std::uint32_t src1Reg = 0;
      std::uint64_t src1Data = 0;
      bool dstValid = false;
      std::uint32_t dstReg = 0;
      std::uint64_t dstData = 0;
      bool memValid = false;
      bool memIsStore = false;
      std::uint64_t memAddr = 0;
      std::uint64_t memWdata = 0;
      std::uint64_t memRdata = 0;
      std::uint64_t memSize = 0;
      bool trapValid = false;
      std::uint32_t trapCause = 0;
      std::uint64_t nextPc = 0;
      std::uint64_t rob = 0;
      std::uint64_t op = 0;
      std::uint64_t uopUid = 0;
      std::uint64_t parentUopUid = 0;
      std::uint64_t blockUid = 0;
      std::uint64_t blockBid = 0;
      std::uint64_t loadStoreId = 0;
      std::uint64_t coreId = 0;
      bool isBstart = false;
      bool isBstop = false;
      const auto &commitProbe = commitSlotProbes[static_cast<std::size_t>(slot)];
      fire = readProbeBool(commitProbe.fire);
      pc = readProbeValue(commitProbe.pc);
      op = readProbeValue(commitProbe.op);
      rob = readProbeValue(commitProbe.rob);
      templateKind = static_cast<std::uint8_t>(readProbeValue(commitProbe.templateKind) & 0x7u);
      insnRaw = readProbeValue(commitProbe.insnRaw);
      len = static_cast<std::uint8_t>(readProbeValue(commitProbe.len) & 0x7u);
      wbValid = readProbeBool(commitProbe.wbValid);
      wbRd = static_cast<std::uint32_t>(readProbeValue(commitProbe.wbRd));
      wbData = readProbeValue(commitProbe.wbData);
      src0Valid = readProbeBool(commitProbe.src0Valid);
      src0Reg = static_cast<std::uint32_t>(readProbeValue(commitProbe.src0Reg));
      src0Data = readProbeValue(commitProbe.src0Data);
      src1Valid = readProbeBool(commitProbe.src1Valid);
      src1Reg = static_cast<std::uint32_t>(readProbeValue(commitProbe.src1Reg));
      src1Data = readProbeValue(commitProbe.src1Data);
      dstValid = readProbeBool(commitProbe.dstValid);
      dstReg = static_cast<std::uint32_t>(readProbeValue(commitProbe.dstReg));
      dstData = readProbeValue(commitProbe.dstData);
      memValid = readProbeBool(commitProbe.memValid);
      memIsStore = readProbeBool(commitProbe.memIsStore);
      memAddr = readProbeValue(commitProbe.memAddr);
      memWdata = readProbeValue(commitProbe.memWdata);
      memRdata = readProbeValue(commitProbe.memRdata);
      memSize = readProbeValue(commitProbe.memSize);
      trapValid = readProbeBool(commitProbe.trapValid);
      trapCause = static_cast<std::uint32_t>(readProbeValue(commitProbe.trapCause));
      nextPc = readProbeValue(commitProbe.nextPc);
      uopUid = readProbeValue(commitProbe.uopUid);
      parentUopUid = readProbeValue(commitProbe.parentUopUid);
      blockUid = readProbeValue(commitProbe.blockUid);
      blockBid = readProbeValue(commitProbe.blockBid);
      loadStoreId = readProbeValue(commitProbe.loadStoreId);
      coreId = readProbeValue(commitProbe.coreId);
      isBstart = readProbeBool(commitProbe.isBstart);
      isBstop = readProbeBool(commitProbe.isBstop);

      if (!fire)
        continue;
      retiredThisCycle = true;
      retiredCount++;
      const std::uint64_t seq = retireSeq++;
      const std::uint8_t useLen = normalizeLen(len);
      const std::uint64_t insn = maskInsn(insnRaw, useLen);
      if (blockBid == 0) {
        blockBid = blockBidLookup(blockUid, rob);
      }
      if (blockUid != 0 && blockBid != 0) {
        blockUidByBid[blockBid] = blockUid;
      }
      if (rawTrace.is_open() && isBstop) {
        rawTrace << "{"
                 << "\"type\":\"blk_evt\","
                 << "\"cycle\":" << cycleNow << ","
                 << "\"kind\":\"resolved\","
                 << "\"kind_code\":2,"
                 << "\"block_uid\":" << blockUid << ","
                 << "\"block_bid\":" << blockBid << ","
                 << "\"core_id\":" << coreId << ","
                 << "\"pc\":" << pc << ","
                 << "\"seq\":" << seq
                 << "}\n";
      }
      if (rawTrace.is_open() && isBstart) {
        rawTrace << "{"
                 << "\"type\":\"blk_evt\","
                 << "\"cycle\":" << cycleNow << ","
                 << "\"kind\":\"open\","
                 << "\"kind_code\":1,"
                 << "\"block_uid\":" << blockUid << ","
                 << "\"block_bid\":" << blockBid << ","
                 << "\"core_id\":" << coreId << ","
                 << "\"pc\":" << pc << ","
                 << "\"seq\":" << seq
                 << "}\n";
      }

      bool xcheckMismatch = false;
      XcheckMismatch xcheckMm{};
      std::optional<XcheckCommit> xcheckQRow{};
      if (xcheckEnabled && (xcheckMaxCommits == 0 || xcheckCompared < xcheckMaxCommits)) {
        XcheckCommit dutRow{};
        dutRow.seq = seq;
        dutRow.pc = pc;
        dutRow.template_kind = templateKind;
        dutRow.insn = insn;
        dutRow.len = useLen;
        dutRow.wb_valid = wbValid ? 1 : 0;
        dutRow.wb_rd = wbRd;
        dutRow.wb_data = wbData;
        dutRow.src0_valid = src0Valid ? 1 : 0;
        dutRow.src0_reg = src0Reg;
        dutRow.src0_data = src0Data;
        dutRow.src1_valid = src1Valid ? 1 : 0;
        dutRow.src1_reg = src1Reg;
        dutRow.src1_data = src1Data;
        dutRow.dst_valid = dstValid ? 1 : 0;
        dutRow.dst_reg = dstReg;
        dutRow.dst_data = dstData;
        dutRow.mem_valid = memValid ? 1 : 0;
        dutRow.mem_is_store = memIsStore ? 1 : 0;
        dutRow.mem_addr = memAddr;
        dutRow.mem_wdata = memWdata;
        dutRow.mem_rdata = memRdata;
        dutRow.mem_size = memSize;
        dutRow.trap_valid = trapValid ? 1 : 0;
        dutRow.trap_cause = trapCause;
        dutRow.traparg0 = 0;
        dutRow.next_pc = nextPc;
        if (isMetadataCommit(dutRow)) {
          xcheckDutMetaSkipped++;
        } else {
          while (xcheckQemuCursor < qemuXcheckRows.size() &&
                 isMetadataCommit(qemuXcheckRows[static_cast<std::size_t>(xcheckQemuCursor)])) {
            xcheckQemuMetaSkipped++;
            xcheckQemuCursor++;
          }
          if (xcheckQemuCursor < qemuXcheckRows.size()) {
            const auto &qRow = qemuXcheckRows[static_cast<std::size_t>(xcheckQemuCursor)];
            xcheckQRow = qRow;
            if (qRow.len == 2 && maskInsn(qRow.insn, qRow.len) == 0) {
              xcheckQemuCbstop++;
            }
            if (useLen == 2 && insn == 0) {
              xcheckDutCbstop++;
            }
            auto mm = compareXcheckCommit(seq, qRow, dutRow);
            if (mm.has_value()) {
              xcheckMismatch = true;
              xcheckMm = *mm;
              xcheckMismatches.push_back(*mm);
            }
            xcheckCompared++;
            xcheckQemuCursor++;
          } else {
            xcheckMismatch = true;
            xcheckTruncatedByMissingQemu = true;
            xcheckMm = XcheckMismatch{
                seq,
                "qemu_trace_exhausted",
                1,
                0,
                0,
                pc,
                0,
                insn,
                XcheckCommit{},
                dutRow,
            };
            xcheckMismatches.push_back(xcheckMm);
          }
        }
      }

      std::string disasmForOp{};
      std::string opName{};
      const bool needOpName = rawTrace.is_open() || commitTrace.is_open() || linxtrace_writer.isOpen();
      if (needOpName) {
        disasmForOp = lookupDisasm(insn, useLen);
        opName = resolveOpName(op, insn, useLen, disasmForOp);
      }
      if (rawTrace.is_open()) {
        rawTrace << "{"
                 << "\"type\":\"commit\","
                 << "\"cycle\":" << cycleNow << ","
                 << "\"slot\":" << slot << ","
                 << "\"seq\":" << seq << ","
                 << "\"core_id\":" << coreId << ","
                 << "\"uop_uid\":" << uopUid << ","
                 << "\"parent_uid\":" << parentUopUid << ","
                 << "\"block_uid\":" << blockUid << ","
                 << "\"block_bid\":" << blockBid << ","
                 << "\"load_store_id\":" << loadStoreId << ","
                 << "\"is_bstart\":" << (isBstart ? 1 : 0) << ","
                 << "\"is_bstop\":" << (isBstop ? 1 : 0) << ","
                 << "\"pc\":" << pc << ","
                 << "\"op\":" << op << ","
                 << "\"op_name\":\"" << jsonEscape(opName) << "\","
                 << "\"insn\":" << insn << ","
                 << "\"len\":" << static_cast<unsigned>(useLen) << ","
                 << "\"rob\":" << rob << ","
                 << "\"wb_valid\":" << (wbValid ? 1 : 0) << ","
                 << "\"wb_rd\":" << wbRd << ","
                 << "\"wb_data\":" << wbData << ","
                 << "\"src0_valid\":" << (src0Valid ? 1 : 0) << ","
                 << "\"src0_reg\":" << src0Reg << ","
                 << "\"src0_data\":" << src0Data << ","
                 << "\"src1_valid\":" << (src1Valid ? 1 : 0) << ","
                 << "\"src1_reg\":" << src1Reg << ","
                 << "\"src1_data\":" << src1Data << ","
                 << "\"dst_valid\":" << (dstValid ? 1 : 0) << ","
                 << "\"dst_reg\":" << dstReg << ","
                 << "\"dst_data\":" << dstData << ","
                 << "\"mem_valid\":" << (memValid ? 1 : 0) << ","
                 << "\"mem_is_store\":" << (memIsStore ? 1 : 0) << ","
                 << "\"mem_addr\":" << memAddr << ","
                 << "\"mem_wdata\":" << memWdata << ","
                 << "\"mem_rdata\":" << memRdata << ","
                 << "\"mem_size\":" << memSize << ","
                 << "\"trap_valid\":" << (trapValid ? 1 : 0) << ","
                 << "\"trap_cause\":" << trapCause << ","
                 << "\"traparg0\":0,"
                 << "\"next_pc\":" << nextPc
                 << "}\n";
        if (uopUid != 0 && dfxTouchedUidCycle.find(uopUid) == dfxTouchedUidCycle.end()) {
          std::uint64_t occKind = kTraceKindNormal;
          if (trapValid) {
            occKind = kTraceKindTrap;
          } else if (templateKind != 0 || parentUopUid != 0) {
            occKind = kTraceKindTemplate;
          }
          rawTrace << "{"
                   << "\"type\":\"occ\","
                   << "\"cycle\":" << cycleNow << ","
                   << "\"core_id\":" << coreId << ","
                   << "\"stage\":\"CMT\","
                   << "\"lane\":" << slot << ","
                   << "\"uop_uid\":" << uopUid << ","
                   << "\"parent_uid\":" << parentUopUid << ","
                   << "\"block_uid\":" << blockUid << ","
                   << "\"block_bid\":" << blockBid << ","
                   << "\"kind\":" << occKind << ","
                   << "\"stall\":0,"
                   << "\"stall_cause\":0,"
                   << "\"pc\":" << pc << ","
                   << "\"rob\":" << rob
                   << "}\n";
          dfxTouchedUidCycle.insert(uopUid);
        }
        if (uopUid != 0) {
          rawUidLastTerminalCycle[uopUid] = cycleNow;
        }
      }

      if (linxtrace_writer.isOpen()) {
        bool xcheckAnnotated = false;
        if (uopUid != 0) {
          auto kidIt = pvUidToKid.find(uopUid);
          auto retiredIt = pvUidLastRetireCycle.find(uopUid);
          if (kidIt != pvUidToKid.end() && retiredIt == pvUidLastRetireCycle.end()) {
            const std::string src0TextDut = pvFmtOperand(src0Valid, src0Reg, src0Data);
            const std::string src1TextDut = pvFmtOperand(src1Valid, src1Reg, src1Data);
            const std::string dstTextDut = pvFmtOperand(dstValid, dstReg, dstData);
            std::string src0Text = src0TextDut;
            std::string src1Text = src1TextDut;
            std::string dstText = dstTextDut;
            if (xcheckQRow.has_value()) {
              const XcheckCommit &q = *xcheckQRow;
              src0Text = pvFmtOperand(q.src0_valid != 0, static_cast<std::uint32_t>(q.src0_reg), q.src0_data);
              src1Text = pvFmtOperand(q.src1_valid != 0, static_cast<std::uint32_t>(q.src1_reg), q.src1_data);
              dstText = pvFmtOperand(q.dst_valid != 0, static_cast<std::uint32_t>(q.dst_reg), q.dst_data);
            }
            const std::string useDisasm = pvDisasmForLabel(lookupDisasm(insn, useLen), op, insn, useLen);
            linxtrace_writer.label(kidIt->second, 0, pvInsnText(pc, useDisasm));
            std::ostringstream detail;
            detail << "uid=" << toHex(uopUid)
                   << " parent=" << toHex(parentUopUid)
                   << " bid=" << toHex(blockBid)
                   << " op=" << opName
                   << " src0=" << src0Text
                   << " src1=" << src1Text
                   << " dst=" << dstText
                   << " wb=" << (wbValid ? (std::to_string(wbRd) + ":" + toHex(wbData)) : std::string("-"))
                   << " mem=" << (memValid ? (std::string(memIsStore ? "S@" : "L@") + toHex(memAddr)) : std::string("-"));
            if (xcheckQRow.has_value()) {
              if (src0Text != src0TextDut) {
                detail << " dut_src0=" << src0TextDut;
              }
              if (src1Text != src1TextDut) {
                detail << " dut_src1=" << src1TextDut;
              }
              if (dstText != dstTextDut) {
                detail << " dut_dst=" << dstTextDut;
              }
            }
            linxtrace_writer.label(kidIt->second, 1, detail.str());
          }
        }
        if (xcheckMismatch && uopUid != 0) {
          auto itLive = pvByUid.find(uopUid);
          if (itLive != pvByUid.end()) {
            std::ostringstream detail;
            detail << "\nXCHECK_FAIL seq=" << xcheckMm.seq
                   << " field=" << xcheckMm.field
                   << " q=" << toHex(xcheckMm.qemu)
                   << " d=" << toHex(xcheckMm.dut)
                   << " qrow={" << xcheckCommitSummary(xcheckMm.qemu_row) << "}"
                   << " drow={" << xcheckCommitSummary(xcheckMm.dut_row) << "}"
                   << " br_kind=" << brKindDbg
                   << " pred_take=" << bruPredTakeDbg
                   << " actual_take=" << bruActualTakeDbg
                   << " epoch=" << brEpochDbg
                   << " boundary_pc=" << toHex(bruBoundaryPcDbg)
                   << " bru_validate=" << (bruValidateDbg ? 1 : 0)
                   << " bru_mismatch=" << (bruMismatchDbg ? 1 : 0)
                   << " corr_pending=" << (bruCorrPendingDbg ? 1 : 0)
                   << " corr_epoch=" << bruCorrEpochDbg
                   << " corr_target=" << toHex(bruCorrTargetDbg)
                   << " redir_corr=" << (redirectFromCorrDbg ? 1 : 0)
                   << " redir_boundary=" << (redirectFromBoundaryDbg ? 1 : 0)
                   << " bru_fault=" << (bruFaultSetDbg ? 1 : 0);
            linxtrace_writer.label(itLive->second.id, 1, detail.str());
            linxtrace_writer.presenceV5(itLive->second.id, pvLaneToken(itLive->second.lane),
                              kTraceStageNames[static_cast<std::size_t>(kTraceSidXchk)], 1, "xcheck");
            xcheckAnnotated = true;
          }
        }
        if (uopUid != 0) {
          const std::uint64_t retireKey = pvRetireKey(pc, rob, slot);
          if (dfxRetiredKeysCycle.find(retireKey) == dfxRetiredKeysCycle.end()) {
            auto liveIt = pvByUid.find(uopUid);
            if (liveIt != pvByUid.end()) {
              if (dfxTouchedUidCycle.find(uopUid) == dfxTouchedUidCycle.end()) {
                linxtrace_writer.presenceV5(liveIt->second.id, pvLaneToken(slot), "CMT", 0, "0");
              }
              linxtrace_writer.retire(liveIt->second.id, liveIt->second.id, (xcheckMismatch || trapValid) ? 1 : 0);
              pvUidLastRetireCycle[uopUid] = curCycleTrace;
              pvByUid.erase(liveIt);
            } else {
              // Missing live line for a committed UID: synthesize one so retire stream remains exact.
              const std::string useDisasm = pvDisasmForLabel(lookupDisasm(insn, useLen), op, insn, useLen);
              const std::uint64_t id = pvNextTraceRowId++;
              linxtrace_writer.insnV5(id, toHex(uopUid), 0, toHex(parentUopUid), trapValid ? "trap" : "normal");
              linxtrace_writer.label(id, 0, pvInsnText(pc, useDisasm));
              std::ostringstream detail;
              detail << "uid=" << toHex(uopUid)
                     << " parent=" << toHex(parentUopUid)
                     << " bid=" << toHex(blockBid)
                     << " op=" << opName
                     << " src0=" << pvFmtOperand(src0Valid, src0Reg, src0Data)
                     << " src1=" << pvFmtOperand(src1Valid, src1Reg, src1Data)
                     << " dst=" << pvFmtOperand(dstValid, dstReg, dstData)
                     << " wb=" << (wbValid ? (std::to_string(wbRd) + ":" + toHex(wbData)) : std::string("-"))
                     << " mem=" << (memValid ? (std::string(memIsStore ? "S@" : "L@") + toHex(memAddr)) : std::string("-"));
              linxtrace_writer.label(id, 1, detail.str());
              linxtrace_writer.presenceV5(id, pvLaneToken(slot), "CMT", 0, "0");
              linxtrace_writer.retire(id, id, (xcheckMismatch || trapValid) ? 1 : 0);
              pvUidLastRetireCycle[uopUid] = curCycleTrace;
            }
            dfxRetiredKeysCycle.insert(retireKey);
          }
        } else {
          // Last-resort fallback for legacy paths that do not expose a uop UID.
          const std::uint64_t k = pvRetireKey(pc, rob, slot);
          if (dfxRetiredKeysCycle.find(k) == dfxRetiredKeysCycle.end()) {
            const std::uint64_t useRaw = insn;
            const std::string useDisasm = pvDisasmForLabel(lookupDisasm(useRaw, useLen), op, useRaw, useLen);
            const std::uint64_t id = pvNextTraceRowId++;
            linxtrace_writer.insnV5(id, toHex(id), 0, "0x0", trapValid ? "trap" : "normal");
            linxtrace_writer.label(id, 0, pvInsnText(pc, useDisasm));
            linxtrace_writer.presenceV5(id, pvLaneToken(slot), "CMT", 0, "0");
            if (xcheckMismatch) {
              std::ostringstream detail;
              detail << "XCHECK_FAIL seq=" << xcheckMm.seq
                     << " field=" << xcheckMm.field
                     << " q=" << toHex(xcheckMm.qemu)
                     << " d=" << toHex(xcheckMm.dut)
                     << " qrow={" << xcheckCommitSummary(xcheckMm.qemu_row) << "}"
                     << " drow={" << xcheckCommitSummary(xcheckMm.dut_row) << "}"
                     << " br_kind=" << brKindDbg
                     << " pred_take=" << bruPredTakeDbg
                     << " actual_take=" << bruActualTakeDbg
                     << " epoch=" << brEpochDbg
                     << " boundary_pc=" << toHex(bruBoundaryPcDbg)
                     << " bru_validate=" << (bruValidateDbg ? 1 : 0)
                     << " bru_mismatch=" << (bruMismatchDbg ? 1 : 0)
                     << " corr_pending=" << (bruCorrPendingDbg ? 1 : 0)
                     << " corr_epoch=" << bruCorrEpochDbg
                     << " corr_target=" << toHex(bruCorrTargetDbg)
                     << " redir_corr=" << (redirectFromCorrDbg ? 1 : 0)
                     << " redir_boundary=" << (redirectFromBoundaryDbg ? 1 : 0)
                     << " bru_fault=" << (bruFaultSetDbg ? 1 : 0);
              linxtrace_writer.label(id, 1, detail.str());
              linxtrace_writer.presenceV5(id, pvLaneToken(slot), kTraceStageNames[static_cast<std::size_t>(kTraceSidXchk)], 1, "xcheck");
              xcheckAnnotated = true;
            }
            linxtrace_writer.retire(id, id, (xcheckMismatch || trapValid) ? 1 : 0);
          }
        }
        if (xcheckMismatch && !xcheckAnnotated) {
          const std::string useDisasm = pvDisasmForLabel(lookupDisasm(insn, useLen), op, insn, useLen);
          const std::uint64_t id = pvNextTraceRowId++;
          linxtrace_writer.insnV5(id, toHex(id), 0, "0x0", "flush");
          linxtrace_writer.label(id, 0, pvInsnText(pc, useDisasm));
          std::ostringstream detail;
          detail << "XCHECK_FAIL seq=" << xcheckMm.seq
                 << " field=" << xcheckMm.field
                 << " q=" << toHex(xcheckMm.qemu)
                 << " d=" << toHex(xcheckMm.dut)
                 << " qrow={" << xcheckCommitSummary(xcheckMm.qemu_row) << "}"
                 << " drow={" << xcheckCommitSummary(xcheckMm.dut_row) << "}"
                 << " br_kind=" << brKindDbg
                 << " pred_take=" << bruPredTakeDbg
                 << " actual_take=" << bruActualTakeDbg
                 << " epoch=" << brEpochDbg
                 << " boundary_pc=" << toHex(bruBoundaryPcDbg)
                 << " bru_validate=" << (bruValidateDbg ? 1 : 0)
                 << " bru_mismatch=" << (bruMismatchDbg ? 1 : 0)
                 << " corr_pending=" << (bruCorrPendingDbg ? 1 : 0)
                 << " corr_epoch=" << bruCorrEpochDbg
                 << " corr_target=" << toHex(bruCorrTargetDbg)
                 << " redir_corr=" << (redirectFromCorrDbg ? 1 : 0)
                 << " redir_boundary=" << (redirectFromBoundaryDbg ? 1 : 0)
                 << " bru_fault=" << (bruFaultSetDbg ? 1 : 0);
          linxtrace_writer.label(id, 1, detail.str());
          linxtrace_writer.presenceV5(id, pvLaneToken(slot), kTraceStageNames[static_cast<std::size_t>(kTraceSidXchk)], 1, "xcheck");
          linxtrace_writer.retire(id, id, 1);
        }
      }

      if (xcheckMismatch && xcheckFailfast) {
        writeXcheckReport();
        pvFlushAll("xcheck_failfast");
        std::cerr << "error: xcheck mismatch at seq=" << xcheckMm.seq
                  << " field=" << xcheckMm.field
                  << " qemu=" << toHex(xcheckMm.qemu)
                  << " dut=" << toHex(xcheckMm.dut) << "\n"
                  << "  qemu_row: " << xcheckCommitSummary(xcheckMm.qemu_row) << "\n"
                  << "  dut_row:  " << xcheckCommitSummary(xcheckMm.dut_row) << "\n";
        dumpBackendFocus();
        dumpCtuDbg();
        dumpSimStats();
        return 1;
      }

      if (maxCommits > 0 && retiredCount >= maxCommits && !commitTrace.is_open()) {
        stopMaxCommits = true;
        break;
      }
      if (!commitTrace.is_open())
        continue;

      commitTrace << "{"
                  << "\"cycle\":" << commitTraceSeq++ << ","
                  << "\"seq\":" << seq << ","
                  << "\"slot\":" << slot << ","
                  << "\"core_id\":" << coreId << ","
                  << "\"uop_uid\":" << uopUid << ","
                  << "\"parent_uid\":" << parentUopUid << ","
                  << "\"block_uid\":" << blockUid << ","
                  << "\"block_bid\":" << blockBid << ","
                  << "\"load_store_id\":" << loadStoreId << ","
                  << "\"is_bstart\":" << (isBstart ? 1 : 0) << ","
                  << "\"is_bstop\":" << (isBstop ? 1 : 0) << ","
                  << "\"pc\":" << pc << ","
                  << "\"op\":" << op << ","
                  << "\"op_name\":\"" << jsonEscape(opName) << "\","
                  << "\"template_kind\":" << static_cast<unsigned>(templateKind) << ","
                  << "\"insn\":" << insn << ","
                  << "\"len\":" << static_cast<unsigned>(useLen) << ","
                  << "\"wb_valid\":" << (wbValid ? 1 : 0) << ","
                  << "\"wb_rd\":" << wbRd << ","
                  << "\"wb_data\":" << wbData << ","
                  << "\"src0_valid\":" << (src0Valid ? 1 : 0) << ","
                  << "\"src0_reg\":" << src0Reg << ","
                  << "\"src0_data\":" << src0Data << ","
                  << "\"src1_valid\":" << (src1Valid ? 1 : 0) << ","
                  << "\"src1_reg\":" << src1Reg << ","
                  << "\"src1_data\":" << src1Data << ","
                  << "\"dst_valid\":" << (dstValid ? 1 : 0) << ","
                  << "\"dst_reg\":" << dstReg << ","
                  << "\"dst_data\":" << dstData << ","
                  << "\"mem_valid\":" << (memValid ? 1 : 0) << ","
                  << "\"mem_is_store\":" << (memIsStore ? 1 : 0) << ","
                  << "\"mem_addr\":" << memAddr << ","
                  << "\"mem_wdata\":" << memWdata << ","
                  << "\"mem_rdata\":" << memRdata << ","
                  << "\"mem_size\":" << memSize << ","
                  << "\"trap_valid\":" << (trapValid ? 1 : 0) << ","
                  << "\"trap_cause\":" << trapCause << ","
                  << "\"traparg0\":0,"
                  << "\"next_pc\":" << nextPc
                  << "}\n";

      if (maxCommits > 0 && retiredCount >= maxCommits) {
        stopMaxCommits = true;
        break;
      }

      if (stopOnIdleLoop) {
        int idleTag = -1;
        if (pc == idleLoopPcA && nextPc == idleLoopPcB) {
          idleTag = 0;
        } else if (pc == idleLoopPcB && nextPc == idleLoopPcA) {
          idleTag = 1;
        }
        if (idleTag < 0) {
          idleLoopMatchCount = 0;
          idleLoopLastTag = -1;
        } else if (idleLoopMatchCount == 0 || idleTag != idleLoopLastTag) {
          idleLoopMatchCount++;
          idleLoopLastTag = idleTag;
        } else {
          idleLoopMatchCount = 1;
          idleLoopLastTag = idleTag;
        }
        if (idleLoopMatchCount >= idleLoopStreakTarget) {
          idleLoopReached = true;
        }
      }
    }

    if (retiredThisCycle) {
      noRetireStreak = 0;
    } else {
      noRetireStreak++;
      if (deadlockCycles > 0 && noRetireStreak >= deadlockCycles && !dut.halted.toBool() && !dut.mmio_exit_valid.toBool()) {
        std::uint8_t headLen = static_cast<std::uint8_t>(dut.rob_head_len.value() & 0x7u);
        if (headLen != 2 && headLen != 4 && headLen != 6)
          headLen = 4;
        const std::uint64_t headInsn = dut.rob_head_insn_raw.value();
        const std::string disasm = disasmInsn(disasmTool, disasmSpec, headInsn, headLen);
        std::cerr << "error: deadlock detected after " << noRetireStreak << " cycles without retire\n"
                  << "  cycle=" << dut.cycles.value() << " pc=" << toHex(dut.pc.value()) << "\n"
                  << "  commits=" << retiredCount
                  << " ifu_mode=" << (ifuStubFromQemu ? "trace" : "memh")
                  << " ifu_cursor=" << ifuStubTraceCursor << "/" << (ifuStubRows ? ifuStubRows->size() : 0ull)
                  << " ifu_pending=" << (ifuStubPending.has_value() ? 1 : 0)
                  << " ifu_ready=" << (dut.tb_ifu_stub_ready.toBool() ? 1 : 0)
                  << "\n"
                  << "  gate_dbg commit_fire0=" << readProbeValue(commitSlotProbes[0].fire)
                  << " ctu_block_ifu=" << dut.ctu_block_ifu.value()
                  << "\n"
                  << "  brob_dbg alloc=" << readProbeValue(blockProbes.queryAllocated)
                  << " ready=" << readProbeValue(blockProbes.queryReady)
                  << " exc=" << readProbeValue(blockProbes.queryException)
                  << " retired=" << readProbeValue(blockProbes.queryRetired)
                  << " state=" << readProbeValue(blockProbes.queryState)
                  << " count=" << readProbeValue(blockProbes.count)
                  << " alloc_ready=" << readProbeValue(blockProbes.allocReady)
                  << " alloc_bid=" << toHex(readProbeValue(blockProbes.allocBid))
                  << " active_bid=" << toHex(readProbeValue(blockProbes.activeBid))
                  << " retire_fire=" << readProbeValue(blockProbes.retireFire)
                  << " bctrl_issue_fire=" << readProbeValue(blockProbes.issueFire)
                  << " replay_cause=" << readProbeValue(commitRedirectProbes.replayCause) << "\n"
                  << "  rob_head_valid=" << dut.rob_head_valid.value() << " rob_head_done=" << dut.rob_head_done.value()
                  << " rob_head_pc=" << toHex(dut.rob_head_pc.value()) << "\n"
                  << "  rob_head_op=" << dut.rob_head_op.value() << " rob_head_len=" << static_cast<unsigned>(headLen)
                  << " rob_head_insn=" << toHex(maskInsn(headInsn, headLen)) << "\n"
                  << "  rob_head_disasm=" << disasm << "\n";
        dumpOccSummary(dut.rob_head_valid.value() ? dut.rob_head_pc.value() : 0);
        dumpBackendFocus();
        dumpCtuDbg();
        pvFlushAll("deadlock_abort");
        writeXcheckReport();
        dumpSimStats();
        return 1;
      }
    }

    if (stopMaxCommits || (maxCommits > 0 && retiredCount >= maxCommits)) {
      writeXcheckReport();
      if (xcheckEnabled && xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "error: xcheck mismatches detected: " << xcheckMismatches.size() << "\n";
        dumpSimStats();
        return 1;
      }
      if (xcheckEnabled && !xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "warn: xcheck mismatches detected (diagnostic mode): " << xcheckMismatches.size() << "\n";
      }
      std::cout << "\nok: max commits reached, cycles=" << dut.cycles.value()
                << " commits=" << retiredCount << "\n";
      dumpSimStats();
      return 0;
    }

    if (dut.mmio_uart_valid.toBool() && !dut.halted.toBool()) {
      const char ch = static_cast<char>(dut.mmio_uart_data.value() & 0xFFu);
      std::cout << ch << std::flush;
    }

    if (dut.mmio_exit_valid.toBool()) {
      const std::uint32_t code = static_cast<std::uint32_t>(dut.mmio_exit_code.value() & 0xFFFF'FFFFu);
      pvFlushAll("end_of_sim");
      writeXcheckReport();
      std::cout << "\nok: program exited, cycles=" << dut.cycles.value() << " commits=" << retiredCount
                << " exit_code=" << code << "\n";
      if (acceptedExitCodes.find(code) == acceptedExitCodes.end()) {
        std::cerr << "error: non-zero program exit code: " << code << "\n";
        dumpSimStats();
        return 1;
      }
      if (xcheckEnabled && xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "error: xcheck mismatches detected: " << xcheckMismatches.size() << "\n";
        dumpSimStats();
        return 1;
      }
      if (xcheckEnabled && !xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "warn: xcheck mismatches detected (diagnostic mode): " << xcheckMismatches.size() << "\n";
      }
      dumpSimStats();
      return 0;
    }

    if (dut.halted.toBool()) {
      pvFlushAll("end_of_sim");
      writeXcheckReport();
      if (xcheckEnabled && xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "error: xcheck mismatches detected: " << xcheckMismatches.size() << "\n";
        dumpSimStats();
        return 1;
      }
      if (xcheckEnabled && !xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "warn: xcheck mismatches detected (diagnostic mode): " << xcheckMismatches.size() << "\n";
      }
      std::cout << "\nok: core halted, cycles=" << dut.cycles.value() << " commits=" << retiredCount << "\n";
      dumpSimStats();
      return 0;
    }

    if (idleLoopReached) {
      pvFlushAll("end_of_sim");
      writeXcheckReport();
      if (xcheckEnabled && xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "error: xcheck mismatches detected: " << xcheckMismatches.size() << "\n";
        dumpSimStats();
        return 1;
      }
      if (xcheckEnabled && !xcheckFailfast && !xcheckMismatches.empty()) {
        std::cerr << "warn: xcheck mismatches detected (diagnostic mode): " << xcheckMismatches.size() << "\n";
      }
      std::cout << "\nok: terminal idle loop reached, cycles=" << dut.cycles.value()
                << " commits=" << retiredCount
                << " pc_a=" << toHex(idleLoopPcA)
                << " pc_b=" << toHex(idleLoopPcB) << "\n";
      dumpSimStats();
      return 0;
    }
  }

  std::cerr << "error: max cycles reached: " << maxCycles << "\n";
  pvFlushAll("max_cycles_abort");
  writeXcheckReport();
  dumpSimStats();
  return 1;
}
