#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <vector>
#include <filesystem>

#include "standalone_oex_top.hpp"

using pyc::gen::standalone_oex_top;

#ifndef DOEX_ROB_DEPTH
#define DOEX_ROB_DEPTH 256
#endif

#ifndef DOEX_ISSUEQ_DEPTH
#define DOEX_ISSUEQ_DEPTH 16
#endif

#ifndef DOEX_ALU_LANES
#define DOEX_ALU_LANES 2
#endif

#ifndef DOEX_BRU_LANES
#define DOEX_BRU_LANES 1
#endif

#ifndef DOEX_AGU_LANES
#define DOEX_AGU_LANES 2
#endif

#ifndef DOEX_STD_LANES
#define DOEX_STD_LANES 2
#endif

#ifndef DOEX_CMD_LANES
#define DOEX_CMD_LANES 1
#endif

#ifndef DOEX_FSU_LANES
#define DOEX_FSU_LANES 1
#endif

#ifndef DOEX_TPL_LANES
#define DOEX_TPL_LANES 1
#endif

#ifndef DOEX_ALU_LAT
#define DOEX_ALU_LAT 1
#endif

#ifndef DOEX_BRU_LAT
#define DOEX_BRU_LAT 1
#endif

#ifndef DOEX_AGU_LAT
#define DOEX_AGU_LAT 4
#endif

#ifndef DOEX_STD_LAT
#define DOEX_STD_LAT 1
#endif

#ifndef DOEX_CMD_LAT
#define DOEX_CMD_LAT 2
#endif

#ifndef DOEX_FSU_LAT
#define DOEX_FSU_LAT 4
#endif

#ifndef DOEX_TPL_LAT
#define DOEX_TPL_LAT 2
#endif

#ifndef DOEX_DISPATCH_W
#define DOEX_DISPATCH_W 4
#endif

#ifndef DOEX_COMMIT_W
#define DOEX_COMMIT_W 4
#endif

#ifndef DOEX_AREGS
#define DOEX_AREGS 64
#endif

#ifndef DOEX_AREGS_W
#define DOEX_AREGS_W 6
#endif

static_assert(DOEX_DISPATCH_W >= 1 && DOEX_DISPATCH_W <= 4, "OEX TB supports dispatch width 1..4");
static_assert(DOEX_COMMIT_W >= 1 && DOEX_COMMIT_W <= 4, "OEX TB supports commit width 1..4");
static_assert(DOEX_ROB_DEPTH > 0, "DOEX_ROB_DEPTH must be > 0");
static_assert((DOEX_ROB_DEPTH & (DOEX_ROB_DEPTH - 1)) == 0, "DOEX_ROB_DEPTH must be power-of-two");
static_assert(DOEX_ALU_LAT >= 1 && DOEX_ALU_LAT <= 4, "DOEX_ALU_LAT must be 1..4");
static_assert(DOEX_AGU_LAT >= 1 && DOEX_AGU_LAT <= 4, "DOEX_AGU_LAT must be 1..4");
static_assert(DOEX_BRU_LAT >= 1 && DOEX_BRU_LAT <= 4, "DOEX_BRU_LAT must be 1..4");
static_assert(DOEX_STD_LAT >= 1 && DOEX_STD_LAT <= 4, "DOEX_STD_LAT must be 1..4");
static_assert(DOEX_CMD_LAT >= 1 && DOEX_CMD_LAT <= 4, "DOEX_CMD_LAT must be 1..4");
static_assert(DOEX_FSU_LAT >= 1 && DOEX_FSU_LAT <= 4, "DOEX_FSU_LAT must be 1..4");
static_assert(DOEX_TPL_LAT >= 1 && DOEX_TPL_LAT <= 4, "DOEX_TPL_LAT must be 1..4");

namespace {

struct TraceRow {
  std::uint64_t seq = 0;
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 0;
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

static std::string toHex(std::uint64_t v) {
  std::ostringstream oss;
  oss << "0x" << std::uppercase << std::hex << v << std::dec;
  return oss.str();
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

static bool openRawTrace(std::ofstream &rawTrace, const std::string &path) {
  if (path.empty())
    return false;
  std::filesystem::path p(path);
  std::filesystem::path parent = p.parent_path();
  if (!parent.empty()) {
    std::error_code ec;
    std::filesystem::create_directories(parent, ec);
  }
  rawTrace.open(p, std::ios::out | std::ios::trunc);
  if (!rawTrace.is_open()) {
    std::cerr << "warning: failed to open raw trace path: " << path << "\n";
    return false;
  }
  return true;
}

static void emitRawOcc(std::ofstream &rawTrace,
                      std::uint64_t cycle,
                      std::uint64_t uop_uid,
                      std::uint64_t pc,
                      std::uint64_t rob_idx,
                      int core_id,
                      int lane,
                      const char *stage,
                      std::uint64_t stall = 0,
                      std::uint64_t stall_cause = 0) {
  if (!rawTrace.is_open())
    return;
  rawTrace << "{"
           << "\"type\":\"occ\","
           << "\"uop_uid\":" << uop_uid << ","
           << "\"cycle\":" << cycle << ","
           << "\"pc\":" << pc << ","
           << "\"rob\":" << rob_idx << ","
           << "\"core_id\":" << core_id << ","
           << "\"lane\":" << lane << ","
           << "\"stage\":\"" << stage << "\","
           << "\"stall\":" << stall << ","
           << "\"stall_cause\":" << stall_cause << "}\n";
}

static void emitRawCommit(std::ofstream &rawTrace,
                         std::uint64_t cycle,
                         std::uint64_t uop_uid,
                         const TraceRow &row,
                         std::uint64_t slot,
                         std::uint64_t rob_idx) {
  if (!rawTrace.is_open())
    return;
  rawTrace << "{"
           << "\"type\":\"commit\","
           << "\"uop_uid\":" << uop_uid << ","
           << "\"seq\":" << row.seq << ","
           << "\"cycle\":" << cycle << ","
           << "\"slot\":" << slot << ","
           << "\"pc\":" << row.pc << ","
           << "\"insn\":" << row.insn << ","
           << "\"len\":" << static_cast<int>(row.len) << ","
           << "\"src0_valid\":" << row.src0_valid << ","
           << "\"src0_reg\":" << row.src0_reg << ","
           << "\"src0_data\":" << row.src0_data << ","
           << "\"src1_valid\":" << row.src1_valid << ","
           << "\"src1_reg\":" << row.src1_reg << ","
           << "\"src1_data\":" << row.src1_data << ","
           << "\"dst_valid\":" << row.dst_valid << ","
           << "\"dst_reg\":" << row.dst_reg << ","
           << "\"dst_data\":" << row.dst_data << ","
           << "\"mem_valid\":" << row.mem_valid << ","
           << "\"mem_is_store\":" << row.mem_is_store << ","
           << "\"mem_addr\":" << row.mem_addr << ","
           << "\"mem_wdata\":" << row.mem_wdata << ","
           << "\"mem_rdata\":" << row.mem_rdata << ","
           << "\"mem_size\":" << row.mem_size << ","
           << "\"trap_valid\":" << row.trap_valid << ","
           << "\"trap_cause\":" << row.trap_cause << ","
           << "\"traparg0\":" << row.traparg0 << ","
           << "\"next_pc\":" << row.next_pc << ","
           << "\"rob\":" << rob_idx << ","
           << "\"core_id\":0"
           << "}\n";
}

static std::uint64_t rawUidFromSeq(std::uint64_t seq) { return seq + 1; }

static std::size_t wrapAdd(std::size_t a, std::size_t b, std::size_t depth) {
  return (a + b) % depth;
}

static std::uint64_t robUidLookup(const std::vector<std::int64_t> &rob_uid_map, std::uint64_t rob) {
  if (rob >= rob_uid_map.size() || rob_uid_map[rob] <= 0)
    return 0;
  return static_cast<std::uint64_t>(rob_uid_map[rob]);
}

static bool getJsonKeyPos(const std::string &line, const std::string &key, std::size_t &value_pos) {
  const std::string pattern = "\"" + key + "\"";
  std::size_t pos = line.find(pattern);
  if (pos == std::string::npos)
    return false;
  pos = line.find(':', pos + pattern.size());
  if (pos == std::string::npos)
    return false;
  pos++;
  while (pos < line.size() && std::isspace(static_cast<unsigned char>(line[pos])))
    pos++;
  if (pos >= line.size())
    return false;
  value_pos = pos;
  return true;
}

static bool getJsonU64(const std::string &line, const std::string &key, std::uint64_t &out) {
  std::size_t pos = 0;
  if (!getJsonKeyPos(line, key, pos))
    return false;
  errno = 0;
  char *endp = nullptr;
  out = std::strtoull(line.c_str() + pos, &endp, 0);
  if (errno != 0 || endp == line.c_str() + pos)
    return false;
  return true;
}

static bool parseJsonCommitLine(const std::string &line, std::uint64_t seq_default, TraceRow &out) {
  out.seq = seq_default;
  (void)getJsonU64(line, "seq", out.seq);
  std::uint64_t len64 = 0;
  if (!getJsonU64(line, "pc", out.pc) ||
      !getJsonU64(line, "insn", out.insn) ||
      !getJsonU64(line, "len", len64) ||
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

  // Destination fields: prefer dst_* keys, fall back to wb_* when absent.
  if (!getJsonU64(line, "dst_valid", out.dst_valid)) {
    std::uint64_t wb_valid = 0;
    std::uint64_t wb_rd = 0;
    std::uint64_t wb_data = 0;
    (void)getJsonU64(line, "wb_valid", wb_valid);
    (void)getJsonU64(line, "wb_rd", wb_rd);
    (void)getJsonU64(line, "wb_data", wb_data);
    out.dst_valid = wb_valid;
    out.dst_reg = wb_rd;
    out.dst_data = wb_data;
  } else {
    (void)getJsonU64(line, "dst_reg", out.dst_reg);
    (void)getJsonU64(line, "dst_data", out.dst_data);
  }

  out.len = static_cast<std::uint8_t>(len64 & 0xFFu);
  out.insn = maskInsn(out.insn, out.len);
  return true;
}

static std::string rowSummary(const TraceRow &r) {
  std::ostringstream oss;
  oss << "pc=" << toHex(r.pc)
      << " len=" << static_cast<unsigned>(r.len)
      << " insn=" << toHex(maskInsn(r.insn, r.len))
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

static const char *pipeStageName(int idx) {
  switch (idx) {
  case 0:
    return "E1";
  case 1:
    return "E2";
  case 2:
    return "E3";
  case 3:
    return "E4";
  default:
    return "E1";
  }
}

template <typename IsValidFn, typename RobFn>
static void emitPipeGroup(std::ofstream &rawTrace,
                         std::uint64_t cycle,
                         const standalone_oex_top &dut,
                         const std::vector<std::int64_t> &rob_uid_map,
                         int lane_base,
                         int lane_count,
                         int stage_count,
                         IsValidFn &&isValid,
                         RobFn &&readRob) {
  for (int lane = 0; lane < lane_count; ++lane) {
    for (int stage = 0; stage < stage_count; ++stage) {
      if (!isValid(dut, lane, stage))
        continue;
      const std::uint64_t rob = readRob(dut, lane, stage);
      const std::uint64_t uid = robUidLookup(rob_uid_map, rob);
      if (uid == 0)
        continue;
      emitRawOcc(rawTrace,
                 cycle,
                 uid,
                 0,
                 rob,
                0,
                lane_base + lane,
                pipeStageName(stage),
                0,
                0);
    }
  }
}

static bool pipeValidAlu(const standalone_oex_top &dut, int lane, int stage) {
  switch (lane) {
  case 0:
    return stage == 0 && dut.alu_pipe0__v0.toBool();
  case 1:
#if DOEX_ALU_LANES > 1
    return stage == 0 && dut.alu_pipe1__v0.toBool();
#else
    (void)dut;
    (void)stage;
    return false;
#endif
  default:
    return false;
  }
}

static std::uint64_t pipeRobAlu(const standalone_oex_top &dut, int lane, int stage) {
  (void)stage;
  switch (lane) {
  case 0:
    return dut.alu_pipe0__rob0.value();
  case 1:
#if DOEX_ALU_LANES > 1
    return dut.alu_pipe1__rob0.value();
#else
    return 0;
#endif
  default:
    return 0;
  }
}

static bool pipeValidBru(const standalone_oex_top &dut, int lane, int stage) {
  (void)lane;
  return lane == 0 && stage == 0 && dut.bru_pipe0__v0.toBool();
}

static std::uint64_t pipeRobBru(const standalone_oex_top &dut, int lane, int stage) {
  (void)lane;
  (void)stage;
  return dut.bru_pipe0__rob0.value();
}

static bool pipeValidAgu(const standalone_oex_top &dut, int lane, int stage) {
  switch (lane) {
  case 0:
    switch (stage) {
    case 0:
      return dut.agu_pipe0__v0.toBool();
    case 1:
      return dut.agu_pipe0__v1.toBool();
    case 2:
      return dut.agu_pipe0__v2.toBool();
    case 3:
      return dut.agu_pipe0__v3.toBool();
    default:
      return false;
    }
  case 1:
#if DOEX_AGU_LANES > 1
    switch (stage) {
    case 0:
      return dut.agu_pipe1__v0.toBool();
    case 1:
      return dut.agu_pipe1__v1.toBool();
    case 2:
      return dut.agu_pipe1__v2.toBool();
    case 3:
      return dut.agu_pipe1__v3.toBool();
    default:
      return false;
    }
#else
    (void)stage;
    return false;
#endif
  default:
    return false;
  }
}

static std::uint64_t pipeRobAgu(const standalone_oex_top &dut, int lane, int stage) {
  (void)stage;
  switch (lane) {
  case 0:
    switch (stage) {
    case 0:
      return dut.agu_pipe0__rob0.value();
    case 1:
      return dut.agu_pipe0__rob1.value();
    case 2:
      return dut.agu_pipe0__rob2.value();
    case 3:
      return dut.agu_pipe0__rob3.value();
    default:
      return 0;
    }
  case 1:
#if DOEX_AGU_LANES > 1
    switch (stage) {
    case 0:
      return dut.agu_pipe1__rob0.value();
    case 1:
      return dut.agu_pipe1__rob1.value();
    case 2:
      return dut.agu_pipe1__rob2.value();
    case 3:
      return dut.agu_pipe1__rob3.value();
    default:
      return 0;
    }
#else
    (void)stage;
    return 0;
#endif
  default:
    return 0;
  }
}

static bool pipeValidStd(const standalone_oex_top &dut, int lane, int stage) {
  switch (lane) {
  case 0:
    return stage == 0 && dut.std_pipe0__v0.toBool();
  case 1:
#if DOEX_STD_LANES > 1
    return stage == 0 && dut.std_pipe1__v0.toBool();
#else
    (void)stage;
    return false;
#endif
  default:
    return false;
  }
}

static std::uint64_t pipeRobStd(const standalone_oex_top &dut, int lane, int stage) {
  (void)stage;
  switch (lane) {
  case 0:
    return dut.std_pipe0__rob0.value();
  case 1:
#if DOEX_STD_LANES > 1
    return dut.std_pipe1__rob0.value();
#else
    return 0;
#endif
  default:
    return 0;
  }
}

static bool pipeValidCmd(const standalone_oex_top &dut, int lane, int stage) {
  if (lane != 0)
    return false;
  switch (stage) {
  case 0:
    return dut.cmd_pipe0__v0.toBool();
  case 1:
    return dut.cmd_pipe0__v1.toBool();
  default:
    return false;
  }
}

static std::uint64_t pipeRobCmd(const standalone_oex_top &dut, int lane, int stage) {
  if (lane != 0)
    return 0;
  switch (stage) {
  case 0:
    return dut.cmd_pipe0__rob0.value();
  case 1:
    return dut.cmd_pipe0__rob1.value();
  default:
    return 0;
  }
}

static bool pipeValidFsu(const standalone_oex_top &dut, int lane, int stage) {
  if (lane != 0)
    return false;
  switch (stage) {
  case 0:
    return dut.fsu_pipe0__v0.toBool();
  case 1:
    return dut.fsu_pipe0__v1.toBool();
  case 2:
    return dut.fsu_pipe0__v2.toBool();
  case 3:
    return dut.fsu_pipe0__v3.toBool();
  default:
    return false;
  }
}

static std::uint64_t pipeRobFsu(const standalone_oex_top &dut, int lane, int stage) {
  if (lane != 0)
    return 0;
  switch (stage) {
  case 0:
    return dut.fsu_pipe0__rob0.value();
  case 1:
    return dut.fsu_pipe0__rob1.value();
  case 2:
    return dut.fsu_pipe0__rob2.value();
  case 3:
    return dut.fsu_pipe0__rob3.value();
  default:
    return 0;
  }
}

static bool pipeValidTpl(const standalone_oex_top &dut, int lane, int stage) {
  if (lane != 0)
    return false;
  switch (stage) {
  case 0:
    return dut.tpl_pipe0__v0.toBool();
  case 1:
    return dut.tpl_pipe0__v1.toBool();
  default:
    return false;
  }
}

static std::uint64_t pipeRobTpl(const standalone_oex_top &dut, int lane, int stage) {
  if (lane != 0)
    return 0;
  switch (stage) {
  case 0:
    return dut.tpl_pipe0__rob0.value();
  case 1:
    return dut.tpl_pipe0__rob1.value();
  default:
    return 0;
  }
}

static void emitPipeOccupancy(std::ofstream &rawTrace,
                             std::uint64_t cycle,
                             const standalone_oex_top &dut,
                             const std::vector<std::int64_t> &rob_uid_map) {
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 0, DOEX_ALU_LANES, DOEX_ALU_LAT, pipeValidAlu, pipeRobAlu);
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 4, 1, DOEX_BRU_LAT, pipeValidBru, pipeRobBru);
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 5, DOEX_AGU_LANES, DOEX_AGU_LAT, pipeValidAgu, pipeRobAgu);
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 9, DOEX_STD_LANES, DOEX_STD_LAT, pipeValidStd, pipeRobStd);
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 11, 1, DOEX_CMD_LAT, pipeValidCmd, pipeRobCmd);
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 12, 1, DOEX_FSU_LAT, pipeValidFsu, pipeRobFsu);
  emitPipeGroup(rawTrace, cycle, dut, rob_uid_map, 13, 1, DOEX_TPL_LAT, pipeValidTpl, pipeRobTpl);
}

static bool compareField(const char *name, std::uint64_t exp, std::uint64_t got, std::uint64_t seq) {
  if (exp == got)
    return true;
  std::cerr << "MISMATCH seq=" << seq << " field=" << name << " exp=" << toHex(exp) << " got=" << toHex(got) << "\n";
  return false;
}

static bool compareRow(const TraceRow &exp, const TraceRow &got) {
  const std::uint64_t seq = exp.seq;
  bool ok = true;
  ok = ok && compareField("seq", exp.seq, got.seq, seq);
  ok = ok && compareField("pc", exp.pc, got.pc, seq);
  ok = ok && compareField("insn", maskInsn(exp.insn, exp.len), maskInsn(got.insn, got.len), seq);
  ok = ok && compareField("len", exp.len, got.len, seq);

  ok = ok && compareField("src0_valid", exp.src0_valid, got.src0_valid, seq);
  ok = ok && compareField("src0_reg", exp.src0_reg, got.src0_reg, seq);
  ok = ok && compareField("src0_data", exp.src0_data, got.src0_data, seq);
  ok = ok && compareField("src1_valid", exp.src1_valid, got.src1_valid, seq);
  ok = ok && compareField("src1_reg", exp.src1_reg, got.src1_reg, seq);
  ok = ok && compareField("src1_data", exp.src1_data, got.src1_data, seq);

  ok = ok && compareField("dst_valid", exp.dst_valid, got.dst_valid, seq);
  ok = ok && compareField("dst_reg", exp.dst_reg, got.dst_reg, seq);
  ok = ok && compareField("dst_data", exp.dst_data, got.dst_data, seq);

  ok = ok && compareField("mem_valid", exp.mem_valid, got.mem_valid, seq);
  ok = ok && compareField("mem_is_store", exp.mem_is_store, got.mem_is_store, seq);
  ok = ok && compareField("mem_addr", exp.mem_addr, got.mem_addr, seq);
  ok = ok && compareField("mem_wdata", exp.mem_wdata, got.mem_wdata, seq);
  ok = ok && compareField("mem_rdata", exp.mem_rdata, got.mem_rdata, seq);
  ok = ok && compareField("mem_size", exp.mem_size, got.mem_size, seq);

  ok = ok && compareField("trap_valid", exp.trap_valid, got.trap_valid, seq);
  ok = ok && compareField("trap_cause", exp.trap_cause, got.trap_cause, seq);
  ok = ok && compareField("traparg0", exp.traparg0, got.traparg0, seq);

  ok = ok && compareField("next_pc", exp.next_pc, got.next_pc, seq);
  return ok;
}

static void driveZeroRow(standalone_oex_top &dut, int slot) {
  auto set1 = [&](auto &w) { w = pyc::cpp::Wire<1>(0); };
  auto set6 = [&](auto &w) { w = pyc::cpp::Wire<6>(0); };
  auto set8 = [&](auto &w) { w = pyc::cpp::Wire<8>(0); };
  auto set32 = [&](auto &w) { w = pyc::cpp::Wire<32>(0); };
  auto set64 = [&](auto &w) { w = pyc::cpp::Wire<64>(0); };

  switch (slot) {
  case 0:
    set64(dut.in0_seq);
    set64(dut.in0_pc);
    set64(dut.in0_insn);
    set8(dut.in0_len);
    set1(dut.in0_src0_valid);
    set6(dut.in0_src0_reg);
    set64(dut.in0_src0_data);
    set1(dut.in0_src1_valid);
    set6(dut.in0_src1_reg);
    set64(dut.in0_src1_data);
    set1(dut.in0_dst_valid);
    set6(dut.in0_dst_reg);
    set64(dut.in0_dst_data);
    set1(dut.in0_mem_valid);
    set1(dut.in0_mem_is_store);
    set64(dut.in0_mem_addr);
    set64(dut.in0_mem_wdata);
    set64(dut.in0_mem_rdata);
    set8(dut.in0_mem_size);
    set1(dut.in0_trap_valid);
    set32(dut.in0_trap_cause);
    set64(dut.in0_traparg0);
    set64(dut.in0_next_pc);
    set1(dut.in0_valid);
    break;

#if DOEX_DISPATCH_W > 1
  case 1:
    set64(dut.in1_seq);
    set64(dut.in1_pc);
    set64(dut.in1_insn);
    set8(dut.in1_len);
    set1(dut.in1_src0_valid);
    set6(dut.in1_src0_reg);
    set64(dut.in1_src0_data);
    set1(dut.in1_src1_valid);
    set6(dut.in1_src1_reg);
    set64(dut.in1_src1_data);
    set1(dut.in1_dst_valid);
    set6(dut.in1_dst_reg);
    set64(dut.in1_dst_data);
    set1(dut.in1_mem_valid);
    set1(dut.in1_mem_is_store);
    set64(dut.in1_mem_addr);
    set64(dut.in1_mem_wdata);
    set64(dut.in1_mem_rdata);
    set8(dut.in1_mem_size);
    set1(dut.in1_trap_valid);
    set32(dut.in1_trap_cause);
    set64(dut.in1_traparg0);
    set64(dut.in1_next_pc);
    set1(dut.in1_valid);
    break;
#endif

#if DOEX_DISPATCH_W > 2
  case 2:
    set64(dut.in2_seq);
    set64(dut.in2_pc);
    set64(dut.in2_insn);
    set8(dut.in2_len);
    set1(dut.in2_src0_valid);
    set6(dut.in2_src0_reg);
    set64(dut.in2_src0_data);
    set1(dut.in2_src1_valid);
    set6(dut.in2_src1_reg);
    set64(dut.in2_src1_data);
    set1(dut.in2_dst_valid);
    set6(dut.in2_dst_reg);
    set64(dut.in2_dst_data);
    set1(dut.in2_mem_valid);
    set1(dut.in2_mem_is_store);
    set64(dut.in2_mem_addr);
    set64(dut.in2_mem_wdata);
    set64(dut.in2_mem_rdata);
    set8(dut.in2_mem_size);
    set1(dut.in2_trap_valid);
    set32(dut.in2_trap_cause);
    set64(dut.in2_traparg0);
    set64(dut.in2_next_pc);
    set1(dut.in2_valid);
    break;
#endif

#if DOEX_DISPATCH_W > 3
  case 3:
    set64(dut.in3_seq);
    set64(dut.in3_pc);
    set64(dut.in3_insn);
    set8(dut.in3_len);
    set1(dut.in3_src0_valid);
    set6(dut.in3_src0_reg);
    set64(dut.in3_src0_data);
    set1(dut.in3_src1_valid);
    set6(dut.in3_src1_reg);
    set64(dut.in3_src1_data);
    set1(dut.in3_dst_valid);
    set6(dut.in3_dst_reg);
    set64(dut.in3_dst_data);
    set1(dut.in3_mem_valid);
    set1(dut.in3_mem_is_store);
    set64(dut.in3_mem_addr);
    set64(dut.in3_mem_wdata);
    set64(dut.in3_mem_rdata);
    set8(dut.in3_mem_size);
    set1(dut.in3_trap_valid);
    set32(dut.in3_trap_cause);
    set64(dut.in3_traparg0);
    set64(dut.in3_next_pc);
    set1(dut.in3_valid);
    break;
#endif

  default:
    break;
  }
}

static void driveRow(standalone_oex_top &dut, int slot, const TraceRow &r) {
  auto set1 = [&](auto &w, std::uint64_t v) { w = pyc::cpp::Wire<1>(v & 1u); };
  auto set6 = [&](auto &w, std::uint64_t v) { w = pyc::cpp::Wire<6>(v & 0x3Fu); };
  auto set8 = [&](auto &w, std::uint64_t v) { w = pyc::cpp::Wire<8>(v & 0xFFu); };
  auto set32 = [&](auto &w, std::uint64_t v) { w = pyc::cpp::Wire<32>(v & 0xFFFF'FFFFu); };
  auto set64 = [&](auto &w, std::uint64_t v) { w = pyc::cpp::Wire<64>(v); };

  switch (slot) {
  case 0:
    set64(dut.in0_seq, r.seq);
    set64(dut.in0_pc, r.pc);
    set64(dut.in0_insn, r.insn);
    set8(dut.in0_len, r.len);
    set1(dut.in0_src0_valid, r.src0_valid);
    set6(dut.in0_src0_reg, r.src0_reg);
    set64(dut.in0_src0_data, r.src0_data);
    set1(dut.in0_src1_valid, r.src1_valid);
    set6(dut.in0_src1_reg, r.src1_reg);
    set64(dut.in0_src1_data, r.src1_data);
    set1(dut.in0_dst_valid, r.dst_valid);
    set6(dut.in0_dst_reg, r.dst_reg);
    set64(dut.in0_dst_data, r.dst_data);
    set1(dut.in0_mem_valid, r.mem_valid);
    set1(dut.in0_mem_is_store, r.mem_is_store);
    set64(dut.in0_mem_addr, r.mem_addr);
    set64(dut.in0_mem_wdata, r.mem_wdata);
    set64(dut.in0_mem_rdata, r.mem_rdata);
    set8(dut.in0_mem_size, r.mem_size);
    set1(dut.in0_trap_valid, r.trap_valid);
    set32(dut.in0_trap_cause, r.trap_cause);
    set64(dut.in0_traparg0, r.traparg0);
    set64(dut.in0_next_pc, r.next_pc);
    set1(dut.in0_valid, 1);
    break;

#if DOEX_DISPATCH_W > 1
  case 1:
    set64(dut.in1_seq, r.seq);
    set64(dut.in1_pc, r.pc);
    set64(dut.in1_insn, r.insn);
    set8(dut.in1_len, r.len);
    set1(dut.in1_src0_valid, r.src0_valid);
    set6(dut.in1_src0_reg, r.src0_reg);
    set64(dut.in1_src0_data, r.src0_data);
    set1(dut.in1_src1_valid, r.src1_valid);
    set6(dut.in1_src1_reg, r.src1_reg);
    set64(dut.in1_src1_data, r.src1_data);
    set1(dut.in1_dst_valid, r.dst_valid);
    set6(dut.in1_dst_reg, r.dst_reg);
    set64(dut.in1_dst_data, r.dst_data);
    set1(dut.in1_mem_valid, r.mem_valid);
    set1(dut.in1_mem_is_store, r.mem_is_store);
    set64(dut.in1_mem_addr, r.mem_addr);
    set64(dut.in1_mem_wdata, r.mem_wdata);
    set64(dut.in1_mem_rdata, r.mem_rdata);
    set8(dut.in1_mem_size, r.mem_size);
    set1(dut.in1_trap_valid, r.trap_valid);
    set32(dut.in1_trap_cause, r.trap_cause);
    set64(dut.in1_traparg0, r.traparg0);
    set64(dut.in1_next_pc, r.next_pc);
    set1(dut.in1_valid, 1);
    break;
#endif

#if DOEX_DISPATCH_W > 2
  case 2:
    set64(dut.in2_seq, r.seq);
    set64(dut.in2_pc, r.pc);
    set64(dut.in2_insn, r.insn);
    set8(dut.in2_len, r.len);
    set1(dut.in2_src0_valid, r.src0_valid);
    set6(dut.in2_src0_reg, r.src0_reg);
    set64(dut.in2_src0_data, r.src0_data);
    set1(dut.in2_src1_valid, r.src1_valid);
    set6(dut.in2_src1_reg, r.src1_reg);
    set64(dut.in2_src1_data, r.src1_data);
    set1(dut.in2_dst_valid, r.dst_valid);
    set6(dut.in2_dst_reg, r.dst_reg);
    set64(dut.in2_dst_data, r.dst_data);
    set1(dut.in2_mem_valid, r.mem_valid);
    set1(dut.in2_mem_is_store, r.mem_is_store);
    set64(dut.in2_mem_addr, r.mem_addr);
    set64(dut.in2_mem_wdata, r.mem_wdata);
    set64(dut.in2_mem_rdata, r.mem_rdata);
    set8(dut.in2_mem_size, r.mem_size);
    set1(dut.in2_trap_valid, r.trap_valid);
    set32(dut.in2_trap_cause, r.trap_cause);
    set64(dut.in2_traparg0, r.traparg0);
    set64(dut.in2_next_pc, r.next_pc);
    set1(dut.in2_valid, 1);
    break;
#endif

#if DOEX_DISPATCH_W > 3
  case 3:
    set64(dut.in3_seq, r.seq);
    set64(dut.in3_pc, r.pc);
    set64(dut.in3_insn, r.insn);
    set8(dut.in3_len, r.len);
    set1(dut.in3_src0_valid, r.src0_valid);
    set6(dut.in3_src0_reg, r.src0_reg);
    set64(dut.in3_src0_data, r.src0_data);
    set1(dut.in3_src1_valid, r.src1_valid);
    set6(dut.in3_src1_reg, r.src1_reg);
    set64(dut.in3_src1_data, r.src1_data);
    set1(dut.in3_dst_valid, r.dst_valid);
    set6(dut.in3_dst_reg, r.dst_reg);
    set64(dut.in3_dst_data, r.dst_data);
    set1(dut.in3_mem_valid, r.mem_valid);
    set1(dut.in3_mem_is_store, r.mem_is_store);
    set64(dut.in3_mem_addr, r.mem_addr);
    set64(dut.in3_mem_wdata, r.mem_wdata);
    set64(dut.in3_mem_rdata, r.mem_rdata);
    set8(dut.in3_mem_size, r.mem_size);
    set1(dut.in3_trap_valid, r.trap_valid);
    set32(dut.in3_trap_cause, r.trap_cause);
    set64(dut.in3_traparg0, r.traparg0);
    set64(dut.in3_next_pc, r.next_pc);
    set1(dut.in3_valid, 1);
    break;
#endif

  default:
    break;
  }
}

static TraceRow sampleOut(const standalone_oex_top &dut, int slot) {
  TraceRow r{};
  switch (slot) {
  case 0:
    r.seq = dut.out0_seq.value();
    r.pc = dut.out0_pc.value();
    r.insn = dut.out0_insn.value();
    r.len = static_cast<std::uint8_t>(dut.out0_len.value() & 0xFFu);
    r.src0_valid = dut.out0_src0_valid.value();
    r.src0_reg = dut.out0_src0_reg.value();
    r.src0_data = dut.out0_src0_data.value();
    r.src1_valid = dut.out0_src1_valid.value();
    r.src1_reg = dut.out0_src1_reg.value();
    r.src1_data = dut.out0_src1_data.value();
    r.dst_valid = dut.out0_dst_valid.value();
    r.dst_reg = dut.out0_dst_reg.value();
    r.dst_data = dut.out0_dst_data.value();
    r.mem_valid = dut.out0_mem_valid.value();
    r.mem_is_store = dut.out0_mem_is_store.value();
    r.mem_addr = dut.out0_mem_addr.value();
    r.mem_wdata = dut.out0_mem_wdata.value();
    r.mem_rdata = dut.out0_mem_rdata.value();
    r.mem_size = dut.out0_mem_size.value();
    r.trap_valid = dut.out0_trap_valid.value();
    r.trap_cause = dut.out0_trap_cause.value();
    r.traparg0 = dut.out0_traparg0.value();
    r.next_pc = dut.out0_next_pc.value();
    return r;

#if DOEX_COMMIT_W > 1
  case 1:
    r.seq = dut.out1_seq.value();
    r.pc = dut.out1_pc.value();
    r.insn = dut.out1_insn.value();
    r.len = static_cast<std::uint8_t>(dut.out1_len.value() & 0xFFu);
    r.src0_valid = dut.out1_src0_valid.value();
    r.src0_reg = dut.out1_src0_reg.value();
    r.src0_data = dut.out1_src0_data.value();
    r.src1_valid = dut.out1_src1_valid.value();
    r.src1_reg = dut.out1_src1_reg.value();
    r.src1_data = dut.out1_src1_data.value();
    r.dst_valid = dut.out1_dst_valid.value();
    r.dst_reg = dut.out1_dst_reg.value();
    r.dst_data = dut.out1_dst_data.value();
    r.mem_valid = dut.out1_mem_valid.value();
    r.mem_is_store = dut.out1_mem_is_store.value();
    r.mem_addr = dut.out1_mem_addr.value();
    r.mem_wdata = dut.out1_mem_wdata.value();
    r.mem_rdata = dut.out1_mem_rdata.value();
    r.mem_size = dut.out1_mem_size.value();
    r.trap_valid = dut.out1_trap_valid.value();
    r.trap_cause = dut.out1_trap_cause.value();
    r.traparg0 = dut.out1_traparg0.value();
    r.next_pc = dut.out1_next_pc.value();
    return r;
#endif

#if DOEX_COMMIT_W > 2
  case 2:
    r.seq = dut.out2_seq.value();
    r.pc = dut.out2_pc.value();
    r.insn = dut.out2_insn.value();
    r.len = static_cast<std::uint8_t>(dut.out2_len.value() & 0xFFu);
    r.src0_valid = dut.out2_src0_valid.value();
    r.src0_reg = dut.out2_src0_reg.value();
    r.src0_data = dut.out2_src0_data.value();
    r.src1_valid = dut.out2_src1_valid.value();
    r.src1_reg = dut.out2_src1_reg.value();
    r.src1_data = dut.out2_src1_data.value();
    r.dst_valid = dut.out2_dst_valid.value();
    r.dst_reg = dut.out2_dst_reg.value();
    r.dst_data = dut.out2_dst_data.value();
    r.mem_valid = dut.out2_mem_valid.value();
    r.mem_is_store = dut.out2_mem_is_store.value();
    r.mem_addr = dut.out2_mem_addr.value();
    r.mem_wdata = dut.out2_mem_wdata.value();
    r.mem_rdata = dut.out2_mem_rdata.value();
    r.mem_size = dut.out2_mem_size.value();
    r.trap_valid = dut.out2_trap_valid.value();
    r.trap_cause = dut.out2_trap_cause.value();
    r.traparg0 = dut.out2_traparg0.value();
    r.next_pc = dut.out2_next_pc.value();
    return r;
#endif

#if DOEX_COMMIT_W > 3
  case 3:
    r.seq = dut.out3_seq.value();
    r.pc = dut.out3_pc.value();
    r.insn = dut.out3_insn.value();
    r.len = static_cast<std::uint8_t>(dut.out3_len.value() & 0xFFu);
    r.src0_valid = dut.out3_src0_valid.value();
    r.src0_reg = dut.out3_src0_reg.value();
    r.src0_data = dut.out3_src0_data.value();
    r.src1_valid = dut.out3_src1_valid.value();
    r.src1_reg = dut.out3_src1_reg.value();
    r.src1_data = dut.out3_src1_data.value();
    r.dst_valid = dut.out3_dst_valid.value();
    r.dst_reg = dut.out3_dst_reg.value();
    r.dst_data = dut.out3_dst_data.value();
    r.mem_valid = dut.out3_mem_valid.value();
    r.mem_is_store = dut.out3_mem_is_store.value();
    r.mem_addr = dut.out3_mem_addr.value();
    r.mem_wdata = dut.out3_mem_wdata.value();
    r.mem_rdata = dut.out3_mem_rdata.value();
    r.mem_size = dut.out3_mem_size.value();
    r.trap_valid = dut.out3_trap_valid.value();
    r.trap_cause = dut.out3_trap_cause.value();
    r.traparg0 = dut.out3_traparg0.value();
    r.next_pc = dut.out3_next_pc.value();
    return r;
#endif

  default:
    break;
  }
  return r;
}

static bool outValid(const standalone_oex_top &dut, int slot) {
  switch (slot) {
  case 0:
    return dut.out0_valid.toBool();
#if DOEX_COMMIT_W > 1
  case 1:
    return dut.out1_valid.toBool();
#endif
#if DOEX_COMMIT_W > 2
  case 2:
    return dut.out2_valid.toBool();
#endif
#if DOEX_COMMIT_W > 3
  case 3:
    return dut.out3_valid.toBool();
#endif
  default:
    return false;
  }
}

static bool inReady(const standalone_oex_top &dut, int slot) {
  switch (slot) {
  case 0:
    return dut.in0_ready.toBool();
#if DOEX_DISPATCH_W > 1
  case 1:
    return dut.in1_ready.toBool();
#endif
#if DOEX_DISPATCH_W > 2
  case 2:
    return dut.in2_ready.toBool();
#endif
#if DOEX_DISPATCH_W > 3
  case 3:
    return dut.in3_ready.toBool();
#endif
  default:
    return false;
  }
}

} // namespace

int main(int argc, char **argv) {
  std::string trace_path;
  std::string raw_path;
  std::uint64_t max_commits = 0;
  std::uint64_t max_cycles = 0;

  for (int i = 1; i < argc; i++) {
    std::string a(argv[i]);
    if (a == "--trace" && i + 1 < argc) {
      trace_path = argv[++i];
      continue;
    }
    if (a == "--raw" && i + 1 < argc) {
      raw_path = argv[++i];
      continue;
    }
    if (a == "--max-commits" && i + 1 < argc) {
      max_commits = std::strtoull(argv[++i], nullptr, 0);
      continue;
    }
    if (a == "--max-cycles" && i + 1 < argc) {
      max_cycles = std::strtoull(argv[++i], nullptr, 0);
      continue;
    }
    if (a == "-h" || a == "--help") {
      std::cerr << "Usage: " << argv[0]
                << " --trace <qemu_trace.jsonl> [--raw <oex_raw_events.jsonl>] [--max-commits N] [--max-cycles N]\n";
      return 0;
    }
    std::cerr << "error: unknown arg: " << a << "\n";
    return 2;
  }

  if (trace_path.empty()) {
    std::cerr << "error: --trace is required\n";
    return 2;
  }

  std::ifstream in(trace_path);
  if (!in.is_open()) {
    std::cerr << "error: cannot open trace: " << trace_path << "\n";
    return 2;
  }

  auto dut = std::make_unique<standalone_oex_top>();
  dut->clk = pyc::cpp::Wire<1>(0);
  dut->rst = pyc::cpp::Wire<1>(1);
  for (int s = 0; s < DOEX_DISPATCH_W; s++)
    driveZeroRow(*dut, s);

  // Reset for a few cycles.
  for (int i = 0; i < 2; i++) {
    dut->clk = pyc::cpp::Wire<1>(0);
    dut->eval();
    dut->tick();
    dut->clk = pyc::cpp::Wire<1>(1);
    dut->eval();
    dut->tick();
  }
  dut->rst = pyc::cpp::Wire<1>(0);

  std::ofstream raw_trace_file;
  if (!raw_path.empty()) {
    openRawTrace(raw_trace_file, raw_path);
  }

  std::deque<TraceRow> drive_q{};
  std::deque<TraceRow> expect_q{};
  std::deque<std::pair<std::uint64_t, std::uint64_t>> rob_inflight{};
  std::vector<std::int64_t> rob_uid_map(DOEX_ROB_DEPTH, -1);
  bool eof = false;
  std::uint64_t next_seq = 0;
  std::uint64_t host_cycles = 0;
  std::uint64_t host_commits = 0;

  while (true) {
    // Fill the input drive queue up to dispatch width.
    while (!eof && drive_q.size() < static_cast<std::size_t>(DOEX_DISPATCH_W)) {
      std::string line;
      if (!std::getline(in, line)) {
        eof = true;
        break;
      }
      if (line.empty())
        continue;
      TraceRow r{};
      if (!parseJsonCommitLine(line, next_seq, r)) {
        std::cerr << "error: malformed trace row near seq=" << next_seq << "\n";
        std::cerr << line << "\n";
        return 3;
      }
      // Normalize sequence to file order.
      r.seq = next_seq;
      next_seq++;
      drive_q.push_back(r);
    }

    // Drive inputs for this cycle.
    for (int slot = 0; slot < DOEX_DISPATCH_W; slot++) {
      if (static_cast<std::size_t>(slot) < drive_q.size()) {
        driveRow(*dut, slot, drive_q[static_cast<std::size_t>(slot)]);
      } else {
        driveZeroRow(*dut, slot);
      }
    }

    // Pre-posedge phase: settle comb at clk=0, then sample outputs + ready.
    dut->clk = pyc::cpp::Wire<1>(0);
    dut->eval();
    dut->tick(); // negedge bookkeeping

    const std::uint64_t trace_cycle = host_cycles;

    // Consume commit outputs.
    for (int slot = 0; slot < DOEX_COMMIT_W; slot++) {
      if (!outValid(*dut, slot))
        continue;
      if (expect_q.empty()) {
        std::cerr << "error: DUT committed with empty expect queue at host_commit=" << host_commits << "\n";
        return 4;
      }
      TraceRow got = sampleOut(*dut, slot);
      TraceRow exp = expect_q.front();
      expect_q.pop_front();
      if (!compareRow(exp, got)) {
        std::cerr << "Expected: " << rowSummary(exp) << "\n";
        std::cerr << "Got:      " << rowSummary(got) << "\n";
        return 1;
      }
      TraceRow &exp_ref = exp;
      std::uint64_t exp_uid = rawUidFromSeq(exp_ref.seq);
      std::uint64_t exp_rob = 0;
      if (!rob_inflight.empty()) {
        const auto [rob_idx, uid] = rob_inflight.front();
        exp_rob = rob_idx;
        if (uid != exp_uid) {
          std::cerr << "warning: commit uid mapping mismatch: expected_uid=" << exp_uid
                    << " robmap_uid=" << uid << "\n";
        }
        if (rob_idx < rob_uid_map.size()) {
          rob_uid_map[static_cast<std::size_t>(rob_idx)] = -1;
        }
        rob_inflight.pop_front();
      }
      emitRawCommit(raw_trace_file, trace_cycle, exp_uid, exp_ref, slot, exp_rob);
      host_commits++;
      if (max_commits > 0 && host_commits >= max_commits) {
        eof = true;
        drive_q.clear();
        expect_q.clear();
        break;
      }
    }

    // Determine how many inputs are accepted (ready/valid prefix).
    std::size_t accept_n = 0;
    for (int slot = 0; slot < DOEX_DISPATCH_W; slot++) {
      if (static_cast<std::size_t>(slot) >= drive_q.size())
        break;
      if (inReady(*dut, slot)) {
        accept_n++;
      } else {
        break;
      }
    }
    const std::size_t tail_cur =
        static_cast<std::size_t>(dut->rob_tail_r.value());
    for (std::size_t k = 0; k < accept_n; k++) {
      const TraceRow &acc = drive_q.front();
      const std::uint64_t acc_uid = rawUidFromSeq(acc.seq);
      const std::size_t rob_idx = wrapAdd(tail_cur, k, DOEX_ROB_DEPTH);
      if (rob_idx < rob_uid_map.size()) {
        rob_uid_map[rob_idx] = static_cast<std::int64_t>(acc_uid);
      }
      if (DOEX_ROB_DEPTH > 0) {
        rob_inflight.push_back(std::make_pair(rob_idx, acc_uid));
      }
      emitRawOcc(raw_trace_file,
                 trace_cycle,
                 acc_uid,
                 acc.pc,
                 static_cast<std::uint64_t>(rob_idx),
                 0,
                 static_cast<int>(k),
                 "D3",
                 0,
                 0);
      expect_q.push_back(drive_q.front());
      drive_q.pop_front();
    }

    emitPipeOccupancy(raw_trace_file, trace_cycle, *dut, rob_uid_map);

    // Posedge: update state.
    dut->clk = pyc::cpp::Wire<1>(1);
    dut->eval();
    dut->tick();
    host_cycles++;

    if (max_cycles > 0 && host_cycles >= max_cycles) {
      std::cerr << "error: max cycles reached: " << host_cycles
                << " host_commits=" << host_commits
                << " drive_q=" << drive_q.size()
                << " expect_q=" << expect_q.size()
                << " dut_cycles=" << dut->cycles.value()
                << " dut_commits=" << dut->commits.value()
                << "\n";
      return 5;
    }

    if (eof && drive_q.empty() && expect_q.empty()) {
      break;
    }
  }

  dut->eval();
  const std::uint64_t dut_cycles = dut->cycles.value();
  const std::uint64_t dut_commits = dut->commits.value();
  const double ipc = (dut_cycles == 0) ? 0.0 : (static_cast<double>(dut_commits) / static_cast<double>(dut_cycles));

  std::cout << "cycles=" << dut_cycles << " commits=" << dut_commits << " ipc=" << std::setprecision(6) << ipc << "\n";
  return 0;
}
