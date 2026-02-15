#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <array>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <optional>
#include <regex>
#include <sstream>
#include <string>
#include <unordered_map>

#if __has_include(<pyc/cpp/pyc_tb.hpp>)
#include <pyc/cpp/pyc_tb.hpp>
#elif __has_include(<cpp/pyc_tb.hpp>)
#include <cpp/pyc_tb.hpp>
#elif __has_include(<pyc_tb.hpp>)
#include <pyc_tb.hpp>
#else
#error "pyc_tb.hpp not found; set include path for pyCircuit runtime headers"
#endif

#if __has_include(<pyc/cpp/pyc_konata.hpp>)
#include <pyc/cpp/pyc_konata.hpp>
#elif __has_include(<cpp/pyc_konata.hpp>)
#include <cpp/pyc_konata.hpp>
#elif __has_include(<pyc_konata.hpp>)
#include <pyc_konata.hpp>
#else
#error "pyc_konata.hpp not found; set include path for pyCircuit runtime headers"
#endif

#include "linxcore_top.hpp"

using pyc::cpp::Testbench;
using pyc::cpp::Wire;

namespace {

constexpr std::uint64_t kBootPc = 0x0000'0000'0001'0000ull;
constexpr std::uint64_t kBootSp = 0x0000'0000'07fe'fff0ull;
constexpr std::uint64_t kDefaultMaxCycles = 50000000ull;
constexpr std::uint64_t kDefaultDeadlockCycles = 200000ull;
constexpr const char *kDefaultDisasmTool = "/Users/zhoubot/linxisa/tools/isa/linxdisasm.py";
constexpr const char *kDefaultDisasmSpec = "/Users/zhoubot/linxisa/isa/spec/current/linxisa-v0.3.json";
constexpr const char *kDefaultIsaPy = "/Users/zhoubot/LinxCore/src/common/isa.py";
constexpr std::uint64_t kOpCBstop = 2ull;

static bool envFlag(const char *name) {
  const char *v = std::getenv(name);
  if (!v)
    return false;
  return !(v[0] == '0' && v[1] == '\0');
}

template <typename MemT>
static bool loadMemh(MemT &mem, const std::string &path) {
  std::ifstream f(path);
  if (!f.is_open()) {
    std::cerr << "ERROR: failed to open memh: " << path << "\n";
    return false;
  }

  std::uint64_t addr = 0;
  std::string tok;
  while (f >> tok) {
    if (tok.empty())
      continue;
    if (tok[0] == '@') {
      addr = std::stoull(tok.substr(1), nullptr, 16);
      continue;
    }
    unsigned v = std::stoul(tok, nullptr, 16) & 0xFFu;
    mem.pokeByte(static_cast<std::size_t>(addr), static_cast<std::uint8_t>(v));
    addr++;
  }
  return true;
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

static std::uint8_t inferLenFromInsn16(std::uint16_t insn16) {
  if ((insn16 & 0xFu) == 0xEu)
    return 6;
  return (insn16 & 0x1u) ? 4 : 2;
}

template <typename MemT>
static std::pair<std::uint64_t, std::uint8_t> fetchInsnAtPc(const MemT &mem, std::uint64_t pc) {
  const std::uint16_t insn16 = static_cast<std::uint16_t>(mem.peekByte(static_cast<std::size_t>(pc)) |
                                                           (static_cast<std::uint16_t>(mem.peekByte(static_cast<std::size_t>(pc + 1))) << 8));
  const std::uint8_t len = inferLenFromInsn16(insn16);
  std::uint64_t raw = 0;
  for (std::uint8_t i = 0; i < len; i++) {
    raw |= static_cast<std::uint64_t>(mem.peekByte(static_cast<std::size_t>(pc + i))) << (8u * i);
  }
  return {maskInsn(raw, len), len};
}

static std::string toHex(std::uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::hex << v << std::dec;
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

static std::string shellQuote(const std::string &s) {
  std::string out;
  out.reserve(s.size() + 2);
  out.push_back('\'');
  for (char ch : s) {
    if (ch == '\'') {
      out += "'\\''";
    } else {
      out.push_back(ch);
    }
  }
  out.push_back('\'');
  return out;
}

static std::optional<std::string> runCommandCapture(const std::string &cmd) {
  FILE *fp = ::popen(cmd.c_str(), "r");
  if (!fp)
    return std::nullopt;
  std::string out;
  char buf[512];
  while (std::fgets(buf, sizeof(buf), fp) != nullptr) {
    out += buf;
  }
  const int rc = ::pclose(fp);
  if (rc != 0)
    return std::nullopt;
  while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) {
    out.pop_back();
  }
  return out;
}

static std::string disasmInsn(const std::string &tool, const std::string &spec, std::uint64_t raw, std::uint8_t len) {
  const std::string token = insnHexToken(raw, len);
  const std::string cmd =
      "python3 " + shellQuote(tool) + " --spec " + shellQuote(spec) + " --hex " + shellQuote(token) + " 2>/dev/null";
  const auto out = runCommandCapture(cmd);
  if (!out.has_value() || out->empty())
    return "<disasm-unavailable>";
  const std::size_t tab = out->find('\t');
  if (tab == std::string::npos || tab + 1 >= out->size())
    return *out;
  return out->substr(tab + 1);
}

static std::unordered_map<std::uint64_t, std::string> loadOpNameMap(const std::string &isaPyPath) {
  std::unordered_map<std::uint64_t, std::string> out{};
  std::ifstream in(isaPyPath);
  if (!in.is_open())
    return out;

  static const std::regex kLineRe(R"(^\s*(OP_[A-Za-z0-9_]+)\s*=\s*([0-9]+)\s*$)");
  std::string line;
  while (std::getline(in, line)) {
    std::smatch m;
    if (!std::regex_match(line, m, kLineRe))
      continue;
    if (m.size() != 3)
      continue;
    const std::uint64_t opId = static_cast<std::uint64_t>(std::stoull(m[2].str(), nullptr, 10));
    out[opId] = m[1].str();
  }
  return out;
}

} // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
    std::cerr << "usage: " << argv[0] << " <program.memh>\n";
    return 2;
  }
  const std::string memhPath = argv[1];

  pyc::gen::linxcore_top dut{};

  if (!loadMemh(dut.mem2r1w.imem, memhPath) || !loadMemh(dut.mem2r1w.dmem, memhPath)) {
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

  Testbench<pyc::gen::linxcore_top> tb(dut);

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

  const bool traceVcd = envFlag("PYC_VCD");
  const bool traceKonata = envFlag("PYC_KONATA");
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

  pyc::cpp::KonataWriter konata{};
  if (traceKonata) {
    std::filesystem::path outPath{};
    if (const char *p = std::getenv("PYC_KONATA_PATH"); p && p[0] != '\0') {
      outPath = std::filesystem::path(p);
    } else {
      const std::string stem = std::filesystem::path(memhPath).stem().string();
      outPath = std::filesystem::path("/Users/zhoubot/LinxCore/generated/cpp/linxcore_top") /
                ("tb_linxcore_top_cpp_" + (stem.empty() ? std::string("program") : stem) + ".konata");
    }
    if (outPath.has_parent_path()) {
      std::filesystem::create_directories(outPath.parent_path());
    }
    if (!konata.open(outPath, dut.cycles.value())) {
      std::cerr << "WARN: cannot open konata output: " << outPath << "\n";
    }
  }

  std::uint64_t maxCycles = kDefaultMaxCycles;
  if (const char *env = std::getenv("PYC_MAX_CYCLES"))
    maxCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  std::uint64_t deadlockCycles = kDefaultDeadlockCycles;
  if (const char *env = std::getenv("PYC_DEADLOCK_CYCLES"))
    deadlockCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  std::string disasmTool = kDefaultDisasmTool;
  if (const char *env = std::getenv("PYC_DISASM_TOOL"); env && env[0] != '\0')
    disasmTool = env;
  std::string disasmSpec = kDefaultDisasmSpec;
  if (const char *env = std::getenv("PYC_DISASM_SPEC"); env && env[0] != '\0')
    disasmSpec = env;
  std::string isaPyPath = kDefaultIsaPy;
  if (const char *env = std::getenv("PYC_ISA_PY"); env && env[0] != '\0')
    isaPyPath = env;
  const auto opNameMap = loadOpNameMap(isaPyPath);
  bool konataSkipBstopPrefix = true;
  if (const char *env = std::getenv("PYC_KONATA_SKIP_BSTOP_PREFIX"); env && env[0] != '\0') {
    konataSkipBstopPrefix = !(env[0] == '0' && env[1] == '\0');
  }
  const bool konataAllowSynthetic = envFlag("PYC_KONATA_SYNTHETIC");

  tb.addClock(dut.clk, 1);
  tb.reset(dut.rst, 2, 1);

  enum class PvPhase : std::uint8_t { Front, Exec };
  static constexpr std::array<const char *, 9> kPvFrontStages = {"F1", "F2", "F3", "F4", "D1", "D2", "D3", "S1", "S2"};
  static constexpr std::array<const char *, 7> kPvExecStages = {"P1", "I1", "I2", "E1", "W1", "W2", "ROB"};

  struct PvEntry {
    std::uint64_t id = 0;
    std::uint64_t robIdx = 0;
    int lane = 0;
    PvPhase phase = PvPhase::Front;
    std::uint8_t stageIdx = 0;
    std::uint64_t lastStepCycle = 0;
    std::uint64_t pc = 0;
    std::uint64_t op = 0;
    std::uint64_t raw = 0;
    std::uint8_t len = 4;
    std::string disasm{};
  };

  std::unordered_map<std::uint64_t, PvEntry> pvByRob{};
  std::uint64_t pvNextId = 1;
  std::unordered_map<std::string, std::string> disasmCache{};

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

  auto pvDisasmForLabel = [&](const std::string &disasm, std::uint64_t op) {
    if (disasm != "<disasm-unavailable>")
      return disasm;
    auto it = opNameMap.find(op);
    if (it != opNameMap.end())
      return it->second;
    std::ostringstream ss;
    ss << "uop_op" << op;
    return ss.str();
  };

  auto pvInsnText = [](std::uint64_t pc, const std::string &disasm) {
    std::ostringstream ss;
    ss << std::hex << std::setfill('0') << std::setw(16) << pc << ":\t" << disasm;
    return ss.str();
  };

  auto pvStagePulse = [&](std::uint64_t id, int lane, const char *stage) {
    konata.stageStart(id, lane, stage);
    konata.stageEnd(id, lane, stage);
  };

  auto pvCurrentStageName = [&](const PvEntry &e) -> const char * {
    if (e.phase == PvPhase::Front)
      return kPvFrontStages[e.stageIdx];
    return kPvExecStages[e.stageIdx];
  };

  auto pvAdvanceOneCycle = [&](PvEntry &e, std::uint64_t cycleNow) {
    if (!konata.isOpen())
      return;
    if (e.lastStepCycle == cycleNow)
      return;
    if (e.phase == PvPhase::Front) {
      if (e.stageIdx + 1 >= kPvFrontStages.size())
        return;
      konata.stageEnd(e.id, e.lane, kPvFrontStages[e.stageIdx]);
      e.stageIdx++;
      konata.stageStart(e.id, e.lane, kPvFrontStages[e.stageIdx]);
      e.lastStepCycle = cycleNow;
      return;
    }
    if (e.stageIdx + 1 >= kPvExecStages.size())
      return;
    konata.stageEnd(e.id, e.lane, kPvExecStages[e.stageIdx]);
    e.stageIdx++;
    konata.stageStart(e.id, e.lane, kPvExecStages[e.stageIdx]);
    e.lastStepCycle = cycleNow;
  };

  auto pvSquash = [&](std::uint64_t robIdx, const char *reason) {
    auto it = pvByRob.find(robIdx);
    if (it == pvByRob.end() || !konata.isOpen())
      return;
    (void)reason;
    konata.stageEnd(it->second.id, it->second.lane, pvCurrentStageName(it->second));
    pvStagePulse(it->second.id, it->second.lane, "FLS");
    konata.label(it->second.id, 1, std::string("FLUSH ") + pvInsnText(it->second.pc, it->second.disasm));
    konata.retire(it->second.id, it->second.id, 1);
    pvByRob.erase(it);
  };

  auto pvFlushAll = [&](const char *reason) {
    if (!konata.isOpen())
      return;
    (void)reason;
    for (const auto &kv : pvByRob) {
      konata.stageEnd(kv.second.id, kv.second.lane, pvCurrentStageName(kv.second));
      pvStagePulse(kv.second.id, kv.second.lane, "FLS");
      konata.label(kv.second.id, 1, std::string("FLUSH ") + pvInsnText(kv.second.pc, kv.second.disasm));
      konata.retire(kv.second.id, kv.second.id, 1);
    }
    pvByRob.clear();
  };

  std::uint64_t commitTraceSeq = 0;
  std::uint64_t retiredCount = 0;
  std::uint64_t noRetireStreak = 0;
  bool konataCaptureStarted = !konataSkipBstopPrefix;

  while (dut.cycles.value() < maxCycles) {
    tb.runCycles(1);
    const std::uint64_t cycleNow = dut.cycles.value();
    if (konata.isOpen()) {
      konata.atCycle(cycleNow);
    }

    if (konata.isOpen() && !dut.halted.toBool()) {
      for (auto &kv : pvByRob) {
        pvAdvanceOneCycle(kv.second, cycleNow);
      }

      for (int slot = 0; slot < 4; slot++) {
        bool fire = false;
        std::uint64_t pc = 0;
        std::uint64_t op = 0;
        std::uint64_t robIdx = 0;
        if (slot == 0) {
          fire = dut.dispatch_fire0.toBool();
          pc = dut.dispatch_pc0.value();
          op = dut.dispatch_op0.value();
          robIdx = dut.dispatch_rob0.value();
        } else if (slot == 1) {
          fire = dut.dispatch_fire1.toBool();
          pc = dut.dispatch_pc1.value();
          op = dut.dispatch_op1.value();
          robIdx = dut.dispatch_rob1.value();
        } else if (slot == 2) {
          fire = dut.dispatch_fire2.toBool();
          pc = dut.dispatch_pc2.value();
          op = dut.dispatch_op2.value();
          robIdx = dut.dispatch_rob2.value();
        } else {
          fire = dut.dispatch_fire3.toBool();
          pc = dut.dispatch_pc3.value();
          op = dut.dispatch_op3.value();
          robIdx = dut.dispatch_rob3.value();
        }
        if (!fire)
          continue;
        if (!konataCaptureStarted) {
          if (op == kOpCBstop) {
            continue;
          }
          konataCaptureStarted = true;
        }
        pvSquash(robIdx, "rob_reused_before_retire");

        auto fetched = fetchInsnAtPc(dut.mem2r1w.imem, pc);
        std::uint64_t raw = fetched.first;
        std::uint8_t len = normalizeLen(fetched.second);
        std::string disasm = lookupDisasm(raw, len);

        const std::uint64_t id = pvNextId++;
        PvEntry ent{};
        ent.id = id;
        ent.robIdx = robIdx;
        ent.lane = slot;
        ent.phase = PvPhase::Front;
        ent.stageIdx = 0;
        ent.lastStepCycle = cycleNow;
        ent.pc = pc;
        ent.op = op;
        ent.raw = raw;
        ent.len = len;
        ent.disasm = pvDisasmForLabel(disasm, op);
        pvByRob[robIdx] = ent;
        konata.insn(id, pc, 0);
        konata.label(id, 0, pvInsnText(pc, ent.disasm));
        konata.stageStart(id, slot, kPvFrontStages[0]);
      }

      for (int slot = 0; slot < 4; slot++) {
        bool fire = false;
        std::uint64_t robIdx = 0;
        if (slot == 0) {
          fire = dut.issue_fire0.toBool();
          robIdx = dut.issue_rob0.value();
        } else if (slot == 1) {
          fire = dut.issue_fire1.toBool();
          robIdx = dut.issue_rob1.value();
        } else if (slot == 2) {
          fire = dut.issue_fire2.toBool();
          robIdx = dut.issue_rob2.value();
        } else {
          fire = dut.issue_fire3.toBool();
          robIdx = dut.issue_rob3.value();
        }
        if (!fire)
          continue;
        auto it = pvByRob.find(robIdx);
        if (it == pvByRob.end()) {
          continue;
        }
        if (it->second.phase == PvPhase::Front) {
          while (it->second.stageIdx + 1 < kPvFrontStages.size()) {
            konata.stageEnd(it->second.id, it->second.lane, kPvFrontStages[it->second.stageIdx]);
            it->second.stageIdx++;
            konata.stageStart(it->second.id, it->second.lane, kPvFrontStages[it->second.stageIdx]);
          }
          konata.stageEnd(it->second.id, it->second.lane, kPvFrontStages[it->second.stageIdx]);
          it->second.phase = PvPhase::Exec;
          it->second.stageIdx = 0;
          it->second.lane = slot;
          it->second.lastStepCycle = cycleNow;
          konata.stageStart(it->second.id, it->second.lane, kPvExecStages[0]);
        }
      }
    }

    bool retiredThisCycle = false;

    for (int slot = 0; slot < 4; slot++) {
      bool fire = false;
      std::uint64_t pc = 0;
      std::uint64_t insnRaw = 0;
      std::uint8_t len = 0;
      bool wbValid = false;
      std::uint32_t wbRd = 0;
      std::uint64_t wbData = 0;
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

      if (slot == 0) {
        fire = dut.commit_fire0.toBool();
        pc = dut.commit_pc0.value();
        op = dut.commit_op0.value();
        rob = dut.commit_rob0.value();
        insnRaw = dut.commit_insn_raw0.value();
        len = static_cast<std::uint8_t>(dut.commit_len0.value() & 0x7u);
        wbValid = dut.commit_wb_valid0.toBool();
        wbRd = static_cast<std::uint32_t>(dut.commit_wb_rd0.value());
        wbData = dut.commit_wb_data0.value();
        memValid = dut.commit_mem_valid0.toBool();
        memIsStore = dut.commit_mem_is_store0.toBool();
        memAddr = dut.commit_mem_addr0.value();
        memWdata = dut.commit_mem_wdata0.value();
        memRdata = dut.commit_mem_rdata0.value();
        memSize = dut.commit_mem_size0.value();
        trapValid = dut.commit_trap_valid0.toBool();
        trapCause = static_cast<std::uint32_t>(dut.commit_trap_cause0.value());
        nextPc = dut.commit_next_pc0.value();
      } else if (slot == 1) {
        fire = dut.commit_fire1.toBool();
        pc = dut.commit_pc1.value();
        op = dut.commit_op1.value();
        rob = dut.commit_rob1.value();
        insnRaw = dut.commit_insn_raw1.value();
        len = static_cast<std::uint8_t>(dut.commit_len1.value() & 0x7u);
        wbValid = dut.commit_wb_valid1.toBool();
        wbRd = static_cast<std::uint32_t>(dut.commit_wb_rd1.value());
        wbData = dut.commit_wb_data1.value();
        memValid = dut.commit_mem_valid1.toBool();
        memIsStore = dut.commit_mem_is_store1.toBool();
        memAddr = dut.commit_mem_addr1.value();
        memWdata = dut.commit_mem_wdata1.value();
        memRdata = dut.commit_mem_rdata1.value();
        memSize = dut.commit_mem_size1.value();
        trapValid = dut.commit_trap_valid1.toBool();
        trapCause = static_cast<std::uint32_t>(dut.commit_trap_cause1.value());
        nextPc = dut.commit_next_pc1.value();
      } else if (slot == 2) {
        fire = dut.commit_fire2.toBool();
        pc = dut.commit_pc2.value();
        op = dut.commit_op2.value();
        rob = dut.commit_rob2.value();
        insnRaw = dut.commit_insn_raw2.value();
        len = static_cast<std::uint8_t>(dut.commit_len2.value() & 0x7u);
        wbValid = dut.commit_wb_valid2.toBool();
        wbRd = static_cast<std::uint32_t>(dut.commit_wb_rd2.value());
        wbData = dut.commit_wb_data2.value();
        memValid = dut.commit_mem_valid2.toBool();
        memIsStore = dut.commit_mem_is_store2.toBool();
        memAddr = dut.commit_mem_addr2.value();
        memWdata = dut.commit_mem_wdata2.value();
        memRdata = dut.commit_mem_rdata2.value();
        memSize = dut.commit_mem_size2.value();
        trapValid = dut.commit_trap_valid2.toBool();
        trapCause = static_cast<std::uint32_t>(dut.commit_trap_cause2.value());
        nextPc = dut.commit_next_pc2.value();
      } else {
        fire = dut.commit_fire3.toBool();
        pc = dut.commit_pc3.value();
        op = dut.commit_op3.value();
        rob = dut.commit_rob3.value();
        insnRaw = dut.commit_insn_raw3.value();
        len = static_cast<std::uint8_t>(dut.commit_len3.value() & 0x7u);
        wbValid = dut.commit_wb_valid3.toBool();
        wbRd = static_cast<std::uint32_t>(dut.commit_wb_rd3.value());
        wbData = dut.commit_wb_data3.value();
        memValid = dut.commit_mem_valid3.toBool();
        memIsStore = dut.commit_mem_is_store3.toBool();
        memAddr = dut.commit_mem_addr3.value();
        memWdata = dut.commit_mem_wdata3.value();
        memRdata = dut.commit_mem_rdata3.value();
        memSize = dut.commit_mem_size3.value();
        trapValid = dut.commit_trap_valid3.toBool();
        trapCause = static_cast<std::uint32_t>(dut.commit_trap_cause3.value());
        nextPc = dut.commit_next_pc3.value();
      }

      if (!fire)
        continue;
      retiredThisCycle = true;
      retiredCount++;

      if (konata.isOpen()) {
        const std::uint8_t useLen = normalizeLen(len);
        const std::uint64_t useRaw = maskInsn(insnRaw, useLen);
        const std::string useDisasm = pvDisasmForLabel(lookupDisasm(useRaw, useLen), op);
        bool skipKonataCommit = false;
        if (!konataCaptureStarted) {
          if (op != kOpCBstop) {
            konataCaptureStarted = true;
          } else {
            skipKonataCommit = true;
          }
        }
        if (!skipKonataCommit) {
          auto it = pvByRob.find(rob);
          if (it == pvByRob.end()) {
            if (konataAllowSynthetic) {
              const std::uint64_t sid = pvNextId++;
              konata.insn(sid, pc, 0);
              konata.label(sid, 0, pvInsnText(pc, useDisasm));
              konata.stageStart(sid, slot, "ROB");
              konata.stageEnd(sid, slot, "ROB");
              pvStagePulse(sid, slot, "CMT");
              konata.retire(sid, sid, 0);
            }
          } else {
            it->second.pc = pc;
            it->second.robIdx = rob;
            it->second.op = op;
            it->second.raw = useRaw;
            it->second.len = useLen;
            it->second.disasm = useDisasm;

            if (it->second.phase == PvPhase::Front) {
              while (it->second.stageIdx + 1 < kPvFrontStages.size()) {
                konata.stageEnd(it->second.id, it->second.lane, kPvFrontStages[it->second.stageIdx]);
                it->second.stageIdx++;
                konata.stageStart(it->second.id, it->second.lane, kPvFrontStages[it->second.stageIdx]);
              }
              konata.stageEnd(it->second.id, it->second.lane, kPvFrontStages[it->second.stageIdx]);
              it->second.phase = PvPhase::Exec;
              it->second.stageIdx = 0;
              konata.stageStart(it->second.id, it->second.lane, kPvExecStages[it->second.stageIdx]);
            }
            while (it->second.stageIdx + 1 < kPvExecStages.size()) {
              konata.stageEnd(it->second.id, it->second.lane, kPvExecStages[it->second.stageIdx]);
              it->second.stageIdx++;
              konata.stageStart(it->second.id, it->second.lane, kPvExecStages[it->second.stageIdx]);
            }
            konata.stageEnd(it->second.id, it->second.lane, kPvExecStages.back());
            pvStagePulse(it->second.id, it->second.lane, "CMT");
            konata.retire(it->second.id, it->second.id, 0);
            pvByRob.erase(it);
          }
        }
      }
      if (!commitTrace.is_open())
        continue;

      const std::uint64_t insn = maskInsn(insnRaw, len);
      commitTrace << "{"
                  << "\"cycle\":" << commitTraceSeq++ << ","
                  << "\"pc\":" << pc << ","
                  << "\"insn\":" << insn << ","
                  << "\"len\":" << static_cast<unsigned>(len) << ","
                  << "\"wb_valid\":" << (wbValid ? 1 : 0) << ","
                  << "\"wb_rd\":" << wbRd << ","
                  << "\"wb_data\":" << wbData << ","
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
                  << "  cycle=" << dut.cycles.value() << " pc=" << toHex(dut.pc.value()) << " fpc=" << toHex(dut.fpc.value())
                  << " rob_count=" << dut.rob_count.value() << "\n"
                  << "  rob_head_valid=" << dut.rob_head_valid.value() << " rob_head_done=" << dut.rob_head_done.value()
                  << " rob_head_pc=" << toHex(dut.rob_head_pc.value()) << "\n"
                  << "  rob_head_op=" << dut.rob_head_op.value() << " rob_head_len=" << static_cast<unsigned>(headLen)
                  << " rob_head_insn=" << toHex(maskInsn(headInsn, headLen)) << "\n"
                  << "  head_wait_hit=" << dut.head_wait_hit.value()
                  << " head_wait_kind=" << dut.head_wait_kind.value()
                  << " sl=" << dut.head_wait_sl.value() << " sr=" << dut.head_wait_sr.value() << " sp=" << dut.head_wait_sp.value()
                  << " sl_rdy=" << dut.head_wait_sl_rdy.value()
                  << " sr_rdy=" << dut.head_wait_sr_rdy.value()
                  << " sp_rdy=" << dut.head_wait_sp_rdy.value() << "\n"
                  << "  rob_head_disasm=" << disasm << "\n";
        pvFlushAll("deadlock_abort");
        return 1;
      }
    }

    if (dut.mmio_uart_valid.toBool() && !dut.halted.toBool()) {
      const char ch = static_cast<char>(dut.mmio_uart_data.value() & 0xFFu);
      std::cout << ch << std::flush;
    }

    if (dut.mmio_exit_valid.toBool()) {
      const std::uint32_t code = static_cast<std::uint32_t>(dut.mmio_exit_code.value() & 0xFFFF'FFFFu);
      pvFlushAll("end_of_sim");
      std::cout << "\nok: program exited, cycles=" << dut.cycles.value() << " commits=" << retiredCount
                << " exit_code=" << code << "\n";
      if (code != 0) {
        std::cerr << "error: non-zero program exit code: " << code << "\n";
        return 1;
      }
      return 0;
    }

    if (dut.halted.toBool()) {
      pvFlushAll("end_of_sim");
      std::cout << "\nok: core halted, cycles=" << dut.cycles.value() << " commits=" << retiredCount << "\n";
      return 0;
    }
  }

  std::cerr << "error: max cycles reached: " << maxCycles << "\n";
  pvFlushAll("max_cycles_abort");
  return 1;
}
