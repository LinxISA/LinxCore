#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>

#include <pyc/cpp/pyc_tb.hpp>

#include "../tb/linxcore_host_mem_shadow.hpp"
#include "linxcore_top.hpp"

using pyc::cpp::Testbench;
using pyc::cpp::Wire;
using linxcore::sim::HostMemShadow;
using linxcore::sim::replayPreloadWords;
using linxcore::sim::resolveMemBytesFromEnv;

namespace {

constexpr std::uint64_t kBootPc = 0x0000'0000'0001'0000ull;
constexpr std::uint64_t kBootSp = 0x0000'0000'0002'0000ull;
constexpr std::uint64_t kDefaultMaxCycles = 20000ull;
constexpr std::uint64_t kRobDepth = 64ull;

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
  HostMemShadow memShadow(resolveMemBytesFromEnv());
  std::string memhError{};
  if (!memShadow.loadMemh(memhPath, &memhError)) {
    std::cerr << "failed to load memh: " << memhPath << " (" << memhError << ")\n";
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
  dut.ic_l2_req_ready = Wire<1>(1);
  dut.ic_l2_rsp_valid = Wire<1>(0);
  dut.ic_l2_rsp_addr = Wire<64>(0);
  dut.ic_l2_rsp_data = Wire<512>(0);
  dut.ic_l2_rsp_error = Wire<1>(0);
  dut.tb_ifu_stub_enable = Wire<1>(1);
  dut.tb_ifu_stub_valid = Wire<1>(0);
  dut.tb_ifu_stub_pc = Wire<64>(0);
  dut.tb_ifu_stub_window = Wire<64>(0);
  dut.tb_ifu_stub_checkpoint = Wire<6>(0);
  dut.tb_ifu_stub_pkt_uid = Wire<64>(0);
  dut.callframe_size_i = Wire<64>(0);

  Testbench<pyc::gen::linxcore_top> tb(dut);
  tb.addClock(dut.clk, 1);
  tb.reset(dut.rst, 2, 1);
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
    tb.runCycles(1);
  });
  dut.host_wvalid = Wire<1>(0);
  dut.host_waddr = Wire<64>(0);
  dut.host_wdata = Wire<64>(0);
  dut.host_wstrb = Wire<8>(0);
  dut.tb_ifu_stub_enable = Wire<1>(0);
  dut.ic_l2_req_ready = Wire<1>(1);
  const std::uint64_t startCycle = dut.cycles.value();

  std::uint64_t commits = 0;
  std::uint64_t icMissCycles = 20;
  if (const char *env = std::getenv("PYC_IC_MISS_CYCLES"))
    icMissCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  bool icReqPending = false;
  bool icRspDriveNow = false;
  std::uint64_t icReqAddrPending = 0;
  std::uint64_t icReqRemainCycles = 0;

  while ((dut.cycles.value() - startCycle) < maxCycles) {
    dut.ic_l2_req_ready = Wire<1>(icReqPending ? 0 : 1);
    if (icRspDriveNow) {
      dut.ic_l2_rsp_valid = Wire<1>(1);
      dut.ic_l2_rsp_addr = Wire<64>(icReqAddrPending);
      dut.ic_l2_rsp_data = memShadow.buildIcacheLine(icReqAddrPending);
      dut.ic_l2_rsp_error = Wire<1>(0);
    } else {
      dut.ic_l2_rsp_valid = Wire<1>(0);
      dut.ic_l2_rsp_addr = Wire<64>(0);
      dut.ic_l2_rsp_data = Wire<512>(0);
      dut.ic_l2_rsp_error = Wire<1>(0);
    }

    const bool icReqSeenPre = (!icReqPending) && dut.ic_l2_req_valid.toBool() && dut.ic_l2_req_ready.toBool();
    const std::uint64_t icReqAddrPre = dut.ic_l2_req_addr.value() & ~0x3Full;

    tb.runCycles(1);
    if (dut.dmem_wvalid.toBool()) {
      memShadow.storeGuestWord(dut.dmem_waddr.value(), dut.dmem_wdata.value(),
                               static_cast<std::uint8_t>(dut.dmem_wstrb.value()));
    }

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
      }
    }

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
