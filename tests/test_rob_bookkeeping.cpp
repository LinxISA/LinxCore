#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <optional>
#include <string>

#include <pyc/cpp/pyc_tb.hpp>

#include "../tb/linxcore_host_mem_shadow.hpp"
#include "linxcore_top.hpp"

using pyc::cpp::Testbench;
using pyc::cpp::ProbeRegistry;
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

struct CommitSlotProbeSet {
  const ProbeRegistry::Entry *fire = nullptr;
  const ProbeRegistry::Entry *rob = nullptr;
  const ProbeRegistry::Entry *mem_valid = nullptr;
  const ProbeRegistry::Entry *mem_is_store = nullptr;
  const ProbeRegistry::Entry *mem_size = nullptr;
};

struct CommitRedirectProbeSet {
  const ProbeRegistry::Entry *valid = nullptr;
  const ProbeRegistry::Entry *pc = nullptr;
};

struct IfuStubPacket {
  std::uint64_t pc = 0;
  std::uint64_t window = 0;
  std::uint64_t checkpoint = 0;
  std::uint64_t pkt_uid = 0;
  std::uint8_t insn_count = 0;
  std::uint8_t len_bytes = 0;
};

std::uint64_t readProbeValue(const ProbeRegistry::Entry *entry) {
  if (entry == nullptr || entry->ptr == nullptr)
    return 0;
  switch (entry->width_bits) {
  case 1:
    return reinterpret_cast<const Wire<1> *>(entry->ptr)->value();
  case 4:
    return reinterpret_cast<const Wire<4> *>(entry->ptr)->value();
  case 6:
    return reinterpret_cast<const Wire<6> *>(entry->ptr)->value();
  case 64:
    return reinterpret_cast<const Wire<64> *>(entry->ptr)->value();
  default:
    return 0;
  }
}

CommitSlot readSlot(const CommitSlotProbeSet &probe) {
  CommitSlot s{};
  s.fire = readProbeValue(probe.fire) != 0;
  s.rob = readProbeValue(probe.rob);
  s.mem_valid = readProbeValue(probe.mem_valid) != 0;
  s.mem_is_store = readProbeValue(probe.mem_is_store) != 0;
  s.mem_size = readProbeValue(probe.mem_size);
  return s;
}

std::uint64_t maskInsn(std::uint64_t raw, std::uint8_t len) {
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

std::uint8_t normalizeLen(std::uint8_t len) {
  return (len == 2 || len == 4 || len == 6) ? len : 4;
}

std::uint64_t buildIfuStubWindow(std::uint64_t raw, std::uint8_t len) {
  const std::uint8_t useLen = normalizeLen(len);
  std::uint64_t payloadMask = 0xFFFF'FFFFull;
  if (useLen == 2) {
    payloadMask = 0xFFFFull;
  } else if (useLen == 6) {
    payloadMask = 0xFFFF'FFFF'FFFFull;
  }
  const std::uint64_t payload = maskInsn(raw, useLen) & payloadMask;
  return (0xFFFF'FFFF'FFFF'FFFFull & ~payloadMask) | payload;
}

std::uint8_t inferLenFromInsn16(std::uint16_t insn16) {
  if ((insn16 & 0xFu) == 0xEu)
    return 6;
  return (insn16 & 0x1u) ? 4 : 2;
}

std::pair<std::uint64_t, std::uint8_t> fetchInsnAtPc(const HostMemShadow &mem, std::uint64_t pc) {
  const std::uint16_t insn16 = static_cast<std::uint16_t>(mem.loadGuestByte(pc) |
                                                           (static_cast<std::uint16_t>(mem.loadGuestByte(pc + 1)) << 8));
  const std::uint8_t len = inferLenFromInsn16(insn16);
  std::uint64_t raw = 0;
  for (std::uint8_t i = 0; i < len; i++) {
    raw |= static_cast<std::uint64_t>(mem.loadGuestByte(pc + i)) << (8u * i);
  }
  return {maskInsn(raw, len), len};
}

std::optional<IfuStubPacket> makeMemIfuStubPacket(const HostMemShadow &mem,
                                                  std::uint64_t pc,
                                                  std::uint64_t checkpoint,
                                                  std::uint64_t pktUid) {
  const auto [raw, len] = fetchInsnAtPc(mem, pc);
  return IfuStubPacket{
      pc,
      buildIfuStubWindow(raw, len),
      checkpoint & 0x3Full,
      pktUid,
      1,
      normalizeLen(len),
  };
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
  std::uint64_t bootRa = bootSp + 0x10000ull;
  if (const char *env = std::getenv("PYC_BOOT_PC"))
    bootPc = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  if (const char *env = std::getenv("PYC_BOOT_SP"))
    bootSp = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  if (const char *env = std::getenv("PYC_BOOT_RA"))
    bootRa = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  else
    bootRa = bootSp + 0x10000ull;

  std::uint64_t maxCycles = kDefaultMaxCycles;
  if (const char *env = std::getenv("PYC_MAX_CYCLES"))
    maxCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));

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
  dut.tb_ifu_stub_enable = Wire<1>(1);
  dut.tb_ifu_stub_valid = Wire<1>(0);
  dut.tb_ifu_stub_pc = Wire<64>(0);
  dut.tb_ifu_stub_window = Wire<64>(0);
  dut.tb_ifu_stub_checkpoint = Wire<6>(0);
  dut.tb_ifu_stub_pkt_uid = Wire<64>(0);
  dut.callframe_size_i = Wire<64>(0);

  Testbench<pyc::gen::linxcore_top> tb(dut);
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
  tb.addClock(dut.clk, 1);
  tb.reset(dut.rst, 2, 1);
  dut.tb_ifu_stub_enable = Wire<1>(1);
  dut.ic_l2_req_ready = Wire<1>(1);
  const std::uint64_t startCycle = dut.cycles.value();
  std::uint64_t ifuStubMemPc = bootPc;
  std::uint64_t ifuStubMemPktUid = 1;
  std::uint64_t ifuStubMemCkptSeq = 1;
  std::optional<IfuStubPacket> ifuStubPending{};

  std::uint64_t commits = 0;
  std::uint64_t icMissCycles = 20;
  if (const char *env = std::getenv("PYC_IC_MISS_CYCLES"))
    icMissCycles = static_cast<std::uint64_t>(std::stoull(env, nullptr, 0));
  bool icReqPending = false;
  bool icRspDriveNow = false;
  std::uint64_t icReqAddrPending = 0;
  std::uint64_t icReqRemainCycles = 0;
  ProbeRegistry probeRegistry{};
  dut.pyc_register_probes(probeRegistry, "dut");
  auto requireProbe = [&](const std::string &path) -> const ProbeRegistry::Entry * {
    const auto *entry = probeRegistry.findByPath(path);
    if (entry == nullptr) {
      std::cerr << "missing probe path: " << path << "\n";
      std::exit(2);
    }
    return entry;
  };
  CommitSlotProbeSet commitProbes[4]{};
  for (int slot = 0; slot < 4; ++slot) {
    const std::string base = "dut:probe.commit.slot" + std::to_string(slot) + ".";
    commitProbes[slot].fire = requireProbe(base + "fire");
    commitProbes[slot].rob = requireProbe(base + "rob");
    commitProbes[slot].mem_valid = requireProbe(base + "mem_valid");
    commitProbes[slot].mem_is_store = requireProbe(base + "mem_is_store");
    commitProbes[slot].mem_size = requireProbe(base + "mem_size");
  }
  CommitRedirectProbeSet redirectProbes{
      requireProbe("dut:probe.commit.redirect.valid"),
      requireProbe("dut:probe.commit.redirect.pc"),
  };

  while ((dut.cycles.value() - startCycle) < maxCycles) {
    if (!ifuStubPending.has_value()) {
      ifuStubPending = makeMemIfuStubPacket(memShadow, ifuStubMemPc, ifuStubMemCkptSeq, ifuStubMemPktUid);
    }
    bool ifuStubFire = false;
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

    tb.runCyclesAuto(1);
    if (dut.dmem_wvalid.toBool()) {
      memShadow.storeGuestWord(dut.dmem_waddr.value(), dut.dmem_wdata.value(),
                               static_cast<std::uint8_t>(dut.dmem_wstrb.value()));
    }
    if (ifuStubFire && ifuStubPending.has_value()) {
      ifuStubMemPc += static_cast<std::uint64_t>(ifuStubPending->len_bytes);
      ifuStubMemPktUid++;
      ifuStubMemCkptSeq += static_cast<std::uint64_t>(ifuStubPending->insn_count);
      ifuStubPending.reset();
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
      const CommitSlot s = readSlot(commitProbes[slot]);
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

    if (readProbeValue(redirectProbes.valid) != 0) {
      const std::uint64_t redirectPc = readProbeValue(redirectProbes.pc);
      if (redirectPc != 0) {
        ifuStubMemPc = redirectPc;
        ifuStubPending.reset();
      }
    }

    if (dut.mmio_exit_valid.toBool() || dut.halted.toBool()) {
      break;
    }
  }

  if (commits < 32) {
    ProbeRegistry probeRegistry{};
    dut.pyc_register_probes(probeRegistry, "dut");
    auto readPath = [&](const std::string &path) -> std::uint64_t {
      const auto *entry = probeRegistry.findByPath(path);
      return readProbeValue(entry);
    };
    std::cerr << "too few commits observed: " << commits
              << " cycles=" << dut.cycles.value()
              << " halted=" << dut.halted.toBool()
              << " pc=0x" << std::hex << dut.pc.value()
              << " mmio_exit=" << std::dec << dut.mmio_exit_valid.toBool()
              << " exit_code=" << dut.mmio_exit_code.value()
              << " commit_fire0=" << readPath("dut:probe.commit.slot0.fire")
              << " commit_rob0=" << readPath("dut:probe.commit.slot0.rob")
              << " redirect_valid=" << readPath("dut:probe.commit.redirect.valid")
              << " ib_valid=" << readPath("dut:probe.pipeview.ib.lane0.valid")
              << "\n";
    return 1;
  }

  std::cout << "rob bookkeeping ok: commits=" << commits << "\n";
  return 0;
}
