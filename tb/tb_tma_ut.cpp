#include <cstdlib>
#include <cstdint>
#include <iostream>
#include <string>
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

#include "UtTmaHarness.hpp"
#include "tb_tma_ut_scenarios.hpp"

// Default assumptions for TMA interface UT:
// 1) This UT validates interface behavior only (handshake/order/status/tag),
//    not internal data-movement correctness.
// 2) DUT runs in CHI-enabled placeholder mode (enable_chi_if=1).
// 3) CHI opcode numeric values are local placeholders; checks are semantic/order based.
// 4) No top/bctrl integration is exercised in this UT stage.
// 5) Test flow depends on pycircuit emit + pycc generated model in generated/cpp/ut_tma_harness.

namespace {

using pyc::cpp::Testbench;
using pyc::cpp::Wire;
using linxcore::tma_ut::CheckPhase;
using linxcore::tma_ut::Drive;
using linxcore::tma_ut::Expect;
using linxcore::tma_ut::InSig;
using linxcore::tma_ut::OutSig;
using linxcore::tma_ut::Scenario;
using linxcore::tma_ut::Step;

static void set_defaults(pyc::gen::UtTmaHarness &dut) {
  dut.cmd_valid_tma = Wire<1>(0);
  dut.cmd_tag_tma = Wire<8>(0);
  dut.cmd_payload_tma = Wire<64>(0);

  dut.gm_req_ready = Wire<1>(1);
  dut.gm_rsp_valid = Wire<1>(0);
  dut.gm_rsp_opcode = Wire<6>(0);
  dut.gm_rsp_txnid = Wire<8>(0);
  dut.gm_rsp_dbid = Wire<8>(0);
  dut.gm_rsp_resp = Wire<2>(0);
  dut.gm_dat_rx_valid = Wire<1>(0);
  dut.gm_dat_rx_opcode = Wire<6>(0);
  dut.gm_dat_rx_txnid = Wire<8>(0);
  dut.gm_dat_rx_dbid = Wire<8>(0);
  dut.gm_dat_rx_data = Wire<256>(0);
  dut.gm_dat_rx_be = Wire<32>(0);
  dut.gm_dat_rx_resp = Wire<2>(0);
  dut.gm_dat_tx_ready = Wire<1>(1);

  dut.tr_req_ready = Wire<1>(1);
  dut.tr_rsp_valid = Wire<1>(0);
  dut.tr_rsp_opcode = Wire<6>(0);
  dut.tr_rsp_txnid = Wire<8>(0);
  dut.tr_rsp_dbid = Wire<8>(0);
  dut.tr_rsp_resp = Wire<2>(0);
  dut.tr_dat_rx_valid = Wire<1>(0);
  dut.tr_dat_rx_opcode = Wire<6>(0);
  dut.tr_dat_rx_txnid = Wire<8>(0);
  dut.tr_dat_rx_dbid = Wire<8>(0);
  dut.tr_dat_rx_data = Wire<256>(0);
  dut.tr_dat_rx_be = Wire<32>(0);
  dut.tr_dat_rx_resp = Wire<2>(0);
  dut.tr_dat_tx_ready = Wire<1>(1);
}

static void apply_drive(pyc::gen::UtTmaHarness &dut, const Drive &d) {
  switch (d.sig) {
  case InSig::cmd_valid_tma:
    dut.cmd_valid_tma = Wire<1>(d.value);
    break;
  case InSig::cmd_tag_tma:
    dut.cmd_tag_tma = Wire<8>(d.value);
    break;
  case InSig::cmd_payload_tma:
    dut.cmd_payload_tma = Wire<64>(d.value);
    break;
  case InSig::gm_req_ready:
    dut.gm_req_ready = Wire<1>(d.value);
    break;
  case InSig::gm_rsp_valid:
    dut.gm_rsp_valid = Wire<1>(d.value);
    break;
  case InSig::gm_rsp_opcode:
    dut.gm_rsp_opcode = Wire<6>(d.value);
    break;
  case InSig::gm_rsp_txnid:
    dut.gm_rsp_txnid = Wire<8>(d.value);
    break;
  case InSig::gm_rsp_dbid:
    dut.gm_rsp_dbid = Wire<8>(d.value);
    break;
  case InSig::gm_rsp_resp:
    dut.gm_rsp_resp = Wire<2>(d.value);
    break;
  case InSig::gm_dat_rx_valid:
    dut.gm_dat_rx_valid = Wire<1>(d.value);
    break;
  case InSig::gm_dat_rx_opcode:
    dut.gm_dat_rx_opcode = Wire<6>(d.value);
    break;
  case InSig::gm_dat_rx_txnid:
    dut.gm_dat_rx_txnid = Wire<8>(d.value);
    break;
  case InSig::gm_dat_rx_resp:
    dut.gm_dat_rx_resp = Wire<2>(d.value);
    break;
  case InSig::tr_rsp_valid:
    dut.tr_rsp_valid = Wire<1>(d.value);
    break;
  case InSig::tr_rsp_opcode:
    dut.tr_rsp_opcode = Wire<6>(d.value);
    break;
  case InSig::tr_rsp_txnid:
    dut.tr_rsp_txnid = Wire<8>(d.value);
    break;
  case InSig::tr_rsp_resp:
    dut.tr_rsp_resp = Wire<2>(d.value);
    break;
  case InSig::tr_dat_rx_valid:
    dut.tr_dat_rx_valid = Wire<1>(d.value);
    break;
  case InSig::tr_dat_rx_opcode:
    dut.tr_dat_rx_opcode = Wire<6>(d.value);
    break;
  case InSig::tr_dat_rx_txnid:
    dut.tr_dat_rx_txnid = Wire<8>(d.value);
    break;
  case InSig::tr_dat_rx_resp:
    dut.tr_dat_rx_resp = Wire<2>(d.value);
    break;
  }
}

static std::uint64_t sample(const pyc::gen::UtTmaHarness &dut, OutSig sig) {
  switch (sig) {
  case OutSig::cmd_ready_tma:
    return dut.cmd_ready_tma.value();
  case OutSig::rsp_valid_tma:
    return dut.rsp_valid_tma.value();
  case OutSig::rsp_tag_tma:
    return dut.rsp_tag_tma.value();
  case OutSig::rsp_status_tma:
    return dut.rsp_status_tma.value();
  case OutSig::gm_req_valid:
    return dut.gm_req_valid.value();
  case OutSig::gm_req_opcode:
    return dut.gm_req_opcode.value();
  case OutSig::gm_req_txnid:
    return dut.gm_req_txnid.value();
  case OutSig::gm_dat_rx_ready:
    return dut.gm_dat_rx_ready.value();
  case OutSig::gm_dat_tx_valid:
    return dut.gm_dat_tx_valid.value();
  case OutSig::gm_dat_tx_opcode:
    return dut.gm_dat_tx_opcode.value();
  case OutSig::gm_dat_tx_txnid:
    return dut.gm_dat_tx_txnid.value();
  case OutSig::gm_dat_tx_dbid:
    return dut.gm_dat_tx_dbid.value();
  case OutSig::tr_req_valid:
    return dut.tr_req_valid.value();
  case OutSig::tr_req_opcode:
    return dut.tr_req_opcode.value();
  case OutSig::tr_req_txnid:
    return dut.tr_req_txnid.value();
  case OutSig::tr_rsp_ready:
    return dut.tr_rsp_ready.value();
  case OutSig::tr_dat_tx_valid:
    return dut.tr_dat_tx_valid.value();
  case OutSig::tr_dat_tx_opcode:
    return dut.tr_dat_tx_opcode.value();
  case OutSig::tr_dat_tx_txnid:
    return dut.tr_dat_tx_txnid.value();
  }
  return 0;
}

static const char *sig_name(OutSig sig) {
  switch (sig) {
  case OutSig::cmd_ready_tma:
    return "cmd_ready_tma";
  case OutSig::rsp_valid_tma:
    return "rsp_valid_tma";
  case OutSig::rsp_tag_tma:
    return "rsp_tag_tma";
  case OutSig::rsp_status_tma:
    return "rsp_status_tma";
  case OutSig::gm_req_valid:
    return "gm_req_valid";
  case OutSig::gm_req_opcode:
    return "gm_req_opcode";
  case OutSig::gm_req_txnid:
    return "gm_req_txnid";
  case OutSig::gm_dat_rx_ready:
    return "gm_dat_rx_ready";
  case OutSig::gm_dat_tx_valid:
    return "gm_dat_tx_valid";
  case OutSig::gm_dat_tx_opcode:
    return "gm_dat_tx_opcode";
  case OutSig::gm_dat_tx_txnid:
    return "gm_dat_tx_txnid";
  case OutSig::gm_dat_tx_dbid:
    return "gm_dat_tx_dbid";
  case OutSig::tr_req_valid:
    return "tr_req_valid";
  case OutSig::tr_req_opcode:
    return "tr_req_opcode";
  case OutSig::tr_req_txnid:
    return "tr_req_txnid";
  case OutSig::tr_rsp_ready:
    return "tr_rsp_ready";
  case OutSig::tr_dat_tx_valid:
    return "tr_dat_tx_valid";
  case OutSig::tr_dat_tx_opcode:
    return "tr_dat_tx_opcode";
  case OutSig::tr_dat_tx_txnid:
    return "tr_dat_tx_txnid";
  }
  return "unknown";
}

static bool check_expect(
    const pyc::gen::UtTmaHarness &dut,
    const Scenario &scenario,
    std::size_t step_idx,
    std::uint64_t cycle,
    const Expect &e) {
  const std::uint64_t got = sample(dut, e.sig);
  if (got == e.value) {
    return true;
  }
  std::cerr << "FAIL: " << scenario.name << " step=" << step_idx << " cycle=" << cycle << " "
            << (e.phase == CheckPhase::pre ? "pre" : "post") << " sig=" << sig_name(e.sig)
            << " got=0x" << std::hex << got << " exp=0x" << e.value << std::dec;
  if (!e.label.empty()) {
    std::cerr << " (" << e.label << ")";
  }
  std::cerr << "\n";
  return false;
}

static bool run_scenario(const Scenario &scenario, bool dump_vcd) {
  pyc::gen::UtTmaHarness dut{};
  Testbench<pyc::gen::UtTmaHarness> tb(dut);
  tb.addClock(dut.clk, 1);

  set_defaults(dut);
  tb.reset(dut.rst, 2, 1);
  set_defaults(dut);
  dut.eval();

  if (dump_vcd) {
    const std::string vcd_path = "tb_tma_ut_" + scenario.name + ".vcd";
    tb.enableVcd(vcd_path, "tb_tma_ut");
    tb.vcdTrace(dut.clk, "clk");
    tb.vcdTrace(dut.rst, "rst");
    tb.vcdTrace(dut.cmd_valid_tma, "cmd_valid_tma");
    tb.vcdTrace(dut.cmd_ready_tma, "cmd_ready_tma");
    tb.vcdTrace(dut.rsp_valid_tma, "rsp_valid_tma");
    tb.vcdTrace(dut.rsp_status_tma, "rsp_status_tma");
  }

  bool ok = true;
  std::uint64_t cycle = 0;
  for (std::size_t i = 0; i < scenario.steps.size(); ++i) {
    const Step &s = scenario.steps[i];
    dut.eval();
    for (const Expect &e : s.expects) {
      if (e.phase == CheckPhase::pre && !check_expect(dut, scenario, i, cycle, e)) {
        ok = false;
      }
    }

    for (const Drive &d : s.drives) {
      apply_drive(dut, d);
    }

    tb.runPosedgeCycles(1);
    dut.eval();
    for (const Expect &e : s.expects) {
      if (e.phase == CheckPhase::post && !check_expect(dut, scenario, i, cycle, e)) {
        ok = false;
      }
    }

    cycle++;
  }

  return ok;
}

} // namespace

int main() {
  const bool dump_vcd = (std::getenv("PYC_VCD") != nullptr);
  const std::vector<Scenario> scenarios = linxcore::tma_ut::build_scenarios();

  std::size_t pass = 0;
  std::size_t fail = 0;
  for (const Scenario &s : scenarios) {
    const bool ok = run_scenario(s, dump_vcd);
    if (ok) {
      std::cout << "[PASS] " << s.name << "\n";
      pass++;
    } else {
      std::cout << "[FAIL] " << s.name << "\n";
      fail++;
    }
  }

  std::cout << "Summary: pass=" << pass << " fail=" << fail << " total=" << scenarios.size() << "\n";
  return (fail == 0) ? 0 : 1;
}
