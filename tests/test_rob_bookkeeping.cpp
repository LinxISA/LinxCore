#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>

#include <pyc/cpp/pyc_tb.hpp>

#include "linxcore_top.hpp"

using pyc::cpp::Testbench;
using pyc::cpp::Wire;

namespace {

constexpr std::uint64_t kBootPc = 0x0000'0000'0001'0000ull;
constexpr std::uint64_t kBootSp = 0x0000'0000'0002'0000ull;
constexpr std::uint64_t kDefaultMaxCycles = 20000ull;
constexpr std::uint64_t kRobDepth = 64ull;

template <typename MemT>
bool loadMemh(MemT &mem, const std::string &path) {
  std::ifstream f(path);
  if (!f.is_open()) {
    std::cerr << "failed to open memh: " << path << "\n";
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
    const unsigned v = std::stoul(tok, nullptr, 16) & 0xFFu;
    mem.pokeByte(static_cast<std::size_t>(addr), static_cast<std::uint8_t>(v));
    addr++;
  }
  return true;
}

struct CommitSlot {
  bool fire = false;
  std::uint64_t rob = 0;
  bool mem_valid = false;
  bool mem_is_store = false;
  std::uint64_t mem_size = 0;
};

CommitSlot readSlot(const pyc::gen::linxcore_top &dut, int slot) {
  CommitSlot s{};
  switch (slot) {
  case 0:
    s.fire = dut.commit_fire0.toBool();
    s.rob = dut.commit_rob0.value();
    s.mem_valid = dut.commit_mem_valid0.toBool();
    s.mem_is_store = dut.commit_mem_is_store0.toBool();
    s.mem_size = dut.commit_mem_size0.value();
    break;
  case 1:
    s.fire = dut.commit_fire1.toBool();
    s.rob = dut.commit_rob1.value();
    s.mem_valid = dut.commit_mem_valid1.toBool();
    s.mem_is_store = dut.commit_mem_is_store1.toBool();
    s.mem_size = dut.commit_mem_size1.value();
    break;
  case 2:
    s.fire = dut.commit_fire2.toBool();
    s.rob = dut.commit_rob2.value();
    s.mem_valid = dut.commit_mem_valid2.toBool();
    s.mem_is_store = dut.commit_mem_is_store2.toBool();
    s.mem_size = dut.commit_mem_size2.value();
    break;
  default:
    s.fire = dut.commit_fire3.toBool();
    s.rob = dut.commit_rob3.value();
    s.mem_valid = dut.commit_mem_valid3.toBool();
    s.mem_is_store = dut.commit_mem_is_store3.toBool();
    s.mem_size = dut.commit_mem_size3.value();
    break;
  }
  return s;
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
  if (const char *env = std::getenv("PYC_BOOT_PC"))
    bootPc = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  if (const char *env = std::getenv("PYC_BOOT_SP"))
    bootSp = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));

  std::uint64_t maxCycles = kDefaultMaxCycles;
  if (const char *env = std::getenv("PYC_MAX_CYCLES"))
    maxCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));

  dut.boot_pc = Wire<64>(bootPc);
  dut.boot_sp = Wire<64>(bootSp);
  dut.host_wvalid = Wire<1>(0);
  dut.host_waddr = Wire<64>(0);
  dut.host_wdata = Wire<64>(0);
  dut.host_wstrb = Wire<8>(0);

  Testbench<pyc::gen::linxcore_top> tb(dut);
  tb.addClock(dut.clk, 1);
  tb.reset(dut.rst, 2, 1);

  std::uint64_t commits = 0;

  while (dut.cycles.value() < maxCycles) {
    tb.runCycles(1);

    bool havePrevSlotRob = false;
    std::uint64_t prevSlotRob = 0;
    for (int slot = 0; slot < 4; slot++) {
      const CommitSlot s = readSlot(dut, slot);
      if (!s.fire)
        continue;

      if (s.rob >= kRobDepth) {
        std::cerr << "ROB index out of range: " << s.rob << "\n";
        return 1;
      }

      // Within one cycle, multi-commit slots must stay in ROB order.
      if (havePrevSlotRob) {
        const std::uint64_t expected = (prevSlotRob + 1) % kRobDepth;
        const bool sameRob = (s.rob == prevSlotRob);
        const bool nextRob = (s.rob == expected);
        if (!sameRob && !nextRob) {
          std::cerr << "ROB slot-order mismatch: prev=" << prevSlotRob
                    << " expected_same_or_next={" << prevSlotRob << "," << expected << "} got=" << s.rob
                    << "\n";
          return 1;
        }
      }
      prevSlotRob = s.rob;
      havePrevSlotRob = true;
      commits++;

      if (s.mem_valid && s.mem_size == 0) {
        std::cerr << "invalid mem commit size=0 at rob=" << s.rob << "\n";
        return 1;
      }
    }

    if (dut.mmio_exit_valid.toBool() || dut.halted.toBool()) {
      break;
    }
  }

  if (commits < 32) {
    std::cerr << "too few commits observed: " << commits << "\n";
    return 1;
  }

  std::cout << "rob bookkeeping ok: commits=" << commits << "\n";
  return 0;
}
