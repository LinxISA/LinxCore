#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <iomanip>
#include <iostream>
#include <optional>
#include <random>
#include <sstream>
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
// 1) This UT validates interface behavior and byte-accurate data movement for NORM layout.
// 2) DUT runs in CHI-enabled mode with UT sideband config (enable_chi_if=1, enable_ut_cfg_if=1).
// 3) CHI opcode numeric values are local placeholders; checks focus on semantic/order correctness.
// 4) No top/bctrl integration is exercised in this UT stage.
// 5) Test flow depends on pycircuit emit + pycc generated model in generated/cpp/ut_tma_harness.

namespace {

using pyc::cpp::Testbench;
using pyc::cpp::Wire;
using linxcore::tma_ut::DataCase;
using linxcore::tma_ut::DataOp;

static constexpr std::uint64_t ST_OK = 0x0;
static constexpr std::uint64_t ST_DECODE_ERR = 0x1;
static constexpr std::uint64_t ST_PROTOCOL_ERR = 0x2;
static constexpr std::uint64_t ST_ACCESS_ERR = 0x3;
static constexpr std::uint64_t ST_TIMEOUT = 0x4;
static constexpr std::uint64_t ST_UNSUPPORTED = 0x5;

static constexpr std::uint64_t OPC_REQ_READ_ONCE = 0x01;
static constexpr std::uint64_t OPC_REQ_WRITE_UNIQUE = 0x02;
static constexpr std::uint64_t OPC_REQ_READ_NOSNP = 0x03;
static constexpr std::uint64_t OPC_REQ_WRITE_NOSNP = 0x04;

static constexpr std::uint64_t OPC_RSP_COMP = 0x01;
static constexpr std::uint64_t OPC_RSP_COMP_DBID = 0x02;
static constexpr std::uint64_t OPC_DAT_COMPDATA = 0x01;
static constexpr std::uint64_t OPC_DAT_NCB_WRDATA = 0x02;

static constexpr std::uint64_t PAYLOAD_TLOAD = 0x0;
static constexpr std::uint64_t PAYLOAD_TSTORE = 0x1;
static constexpr std::uint64_t PAYLOAD_UNSUPPORTED = 0x2;

struct ProtocolCase {
  std::string name;
  std::uint64_t payload;
  bool two_cmd = false;
  bool cmd_backpressure = false;
  bool inject_access_err = false;
  bool inject_proto_err = false;
  bool force_timeout = false;
  std::uint64_t expect_status = ST_OK;
};

struct RuntimeConfig {
  bool random_gm_latency = true;
  bool force_no_progress = false;
  bool inject_access_err_once = false;
  bool inject_proto_err_once = false;
  bool issue_two_cmd = false;
  bool cmd_backpressure = false;
  bool check_dbid_before_data = false;
  bool check_dat_before_rsp = false;
  bool check_tag_echo = false;
  bool check_txnid_increment = false;
  bool check_rsp_order = false;
  std::uint64_t expect_status = ST_OK;
};

struct RspEvent {
  std::uint64_t due = 0;
  std::uint64_t opcode = 0;
  std::uint64_t txnid = 0;
  std::uint64_t dbid = 0;
  std::uint64_t resp = 0;
};

struct DatEvent {
  std::uint64_t due = 0;
  std::uint64_t opcode = 0;
  std::uint64_t txnid = 0;
  std::uint64_t dbid = 0;
  std::array<std::uint8_t, 32> data{};
  std::uint32_t be = 0;
  std::uint64_t resp = 0;
};

struct PendingGmWrite {
  std::uint64_t addr = 0;
  std::uint64_t txnid = 0;
  std::uint64_t dbid = 0;
  std::uint32_t beats = 0;
  std::uint32_t received = 0;
  bool dbid_seen = false;
};

struct PendingTrWrite {
  std::uint64_t addr = 0;
  std::uint64_t txnid = 0;
  std::uint32_t beats = 0;
  std::uint32_t received = 0;
};

struct TraceSample {
  std::uint64_t cycle = 0;
  std::uint64_t state = 0;
  std::uint64_t bpq_occ = 0;
  std::uint64_t rfb_occ = 0;
  std::uint64_t wcb_occ = 0;
  std::uint64_t bdb_occ = 0;
  std::uint64_t gm_req_v = 0;
  std::uint64_t gm_req_r = 0;
  std::uint64_t gm_rsp_v = 0;
  std::uint64_t gm_rsp_r = 0;
  std::uint64_t gm_dat_rx_v = 0;
  std::uint64_t gm_dat_rx_r = 0;
  std::uint64_t gm_dat_tx_v = 0;
  std::uint64_t gm_dat_tx_r = 0;
  std::uint64_t tr_req_v = 0;
  std::uint64_t tr_req_r = 0;
  std::uint64_t tr_rsp_v = 0;
  std::uint64_t tr_rsp_r = 0;
  std::uint64_t tr_dat_rx_v = 0;
  std::uint64_t tr_dat_rx_r = 0;
  std::uint64_t tr_dat_tx_v = 0;
  std::uint64_t tr_dat_tx_r = 0;
  std::uint64_t rsp_v = 0;
  std::uint64_t rsp_tag = 0;
  std::uint64_t rsp_status = 0;
};

static std::uint64_t fnv1a64(const std::string &s) {
  std::uint64_t h = 1469598103934665603ull;
  for (unsigned char c : s) {
    h ^= static_cast<std::uint64_t>(c);
    h *= 1099511628211ull;
  }
  return h;
}

static bool env_true(const char *name) {
  const char *v = std::getenv(name);
  if (v == nullptr) {
    return false;
  }
  std::string s(v);
  for (char &ch : s) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  return !(s.empty() || s == "0" || s == "false" || s == "off" || s == "no");
}

static std::uint64_t env_u64(const char *name, std::uint64_t dflt) {
  const char *v = std::getenv(name);
  if (v == nullptr || *v == '\0') {
    return dflt;
  }
  char *end = nullptr;
  const std::uint64_t out = std::strtoull(v, &end, 0);
  if (end == v) {
    return dflt;
  }
  return out;
}

static std::string env_str(const char *name) {
  const char *v = std::getenv(name);
  return (v == nullptr) ? std::string() : std::string(v);
}

static Wire<256> wire_from_bytes(const std::array<std::uint8_t, 32> &bytes) {
  Wire<256> w(0);
  for (unsigned wi = 0; wi < 4; ++wi) {
    std::uint64_t word = 0;
    for (unsigned bj = 0; bj < 8; ++bj) {
      word |= static_cast<std::uint64_t>(bytes[wi * 8 + bj]) << (8u * bj);
    }
    w.setWord(wi, word);
  }
  return w;
}

static std::array<std::uint8_t, 32> bytes_from_wire(const Wire<256> &w) {
  std::array<std::uint8_t, 32> out{};
  for (unsigned wi = 0; wi < 4; ++wi) {
    const std::uint64_t word = w.word(wi);
    for (unsigned bj = 0; bj < 8; ++bj) {
      out[wi * 8 + bj] = static_cast<std::uint8_t>((word >> (8u * bj)) & 0xFFu);
    }
  }
  return out;
}

static void ensure_span(std::vector<std::uint8_t> &mem, std::uint64_t addr, std::size_t len) {
  const std::uint64_t end = addr + static_cast<std::uint64_t>(len);
  if (end > mem.size()) {
    mem.resize(static_cast<std::size_t>(end), 0);
  }
}

static std::array<std::uint8_t, 32> read_beat(const std::vector<std::uint8_t> &mem, std::uint64_t addr) {
  std::array<std::uint8_t, 32> out{};
  for (std::size_t i = 0; i < 32; ++i) {
    const std::uint64_t a = addr + static_cast<std::uint64_t>(i);
    out[i] = (a < mem.size()) ? mem[static_cast<std::size_t>(a)] : 0;
  }
  return out;
}

static void write_beat(
    std::vector<std::uint8_t> &mem,
    std::uint64_t addr,
    const std::array<std::uint8_t, 32> &data,
    std::uint32_t be_mask) {
  ensure_span(mem, addr, 32);
  for (std::size_t i = 0; i < 32; ++i) {
    if (((be_mask >> i) & 1u) == 0u) {
      continue;
    }
    mem[static_cast<std::size_t>(addr + static_cast<std::uint64_t>(i))] = data[i];
  }
}

static std::uint32_t gm_random_delay(std::mt19937_64 &rng) {
  std::uniform_int_distribution<int> p100(1, 100);
  if (p100(rng) <= 95) {
    std::uniform_int_distribution<int> d(10, 100);
    return static_cast<std::uint32_t>(d(rng));
  }
  std::uniform_int_distribution<int> d(100, 1000);
  return static_cast<std::uint32_t>(d(rng));
}

static std::uint32_t tr_small_delay(std::mt19937_64 &rng) {
  std::uniform_int_distribution<int> d(1, 3);
  return static_cast<std::uint32_t>(d(rng));
}

static std::uint64_t elem_bytes_from_type(std::uint64_t elem_type) {
  switch (elem_type & 0x7ull) {
  case 0:
    return 1;
  case 1:
  case 3:
    return 2;
  case 2:
  case 4:
    return 4;
  default:
    return 0;
  }
}

static std::uint64_t rows_per_chunk(std::uint64_t inner_bytes) {
  if (inner_bytes == 32) {
    return 8;
  }
  if (inner_bytes == 64) {
    return 4;
  }
  if (inner_bytes == 128) {
    return 2;
  }
  if (inner_bytes == 256) {
    return 1;
  }
  return 0;
}

static std::uint64_t row_req_count(std::uint64_t row_addr, std::uint64_t row_bytes) {
  const std::uint64_t off128 = row_addr & 0x7Full;
  if (off128 + row_bytes <= 128) {
    return 1;
  }
  const std::uint64_t off256 = row_addr & 0xFFull;
  if (off256 + row_bytes <= 256) {
    return 1;
  }
  return 2;
}

static void init_source_for_tload(
    const DataCase &tc,
    std::uint64_t gm_base,
    std::vector<std::uint8_t> &gm_mem,
    std::vector<std::uint8_t> &tr_mem,
    std::vector<std::uint8_t> &golden_tr) {
  ensure_span(gm_mem, gm_base + tc.gm_outer * tc.stride_B, 1024);
  ensure_span(tr_mem, gm_base + tc.gm_outer * tc.stride_B, 1024);
  const std::uint64_t rpc = rows_per_chunk(tc.inner_bytes);
  const std::uint64_t chunks = (tc.gm_outer + rpc - 1) / rpc;
  ensure_span(golden_tr, tc.gm_base_off + chunks * 256 + 2048, 1);

  for (std::uint64_t r = 0; r < tc.gm_outer; ++r) {
    const std::uint64_t row_addr = gm_base + r * tc.stride_B;
    for (std::uint64_t b = 0; b < tc.inner_bytes; ++b) {
      gm_mem[static_cast<std::size_t>(row_addr + b)] = static_cast<std::uint8_t>((r * 131ull + b * 17ull + 0x5Aull) & 0xFFull);
    }
    const std::uint64_t chunk = r / rpc;
    const std::uint64_t in_chunk = r % rpc;
    const std::uint64_t dst = tc.gm_base_off + chunk * 256 + in_chunk * tc.inner_bytes;
    ensure_span(golden_tr, dst, static_cast<std::size_t>(tc.inner_bytes));
    for (std::uint64_t b = 0; b < tc.inner_bytes; ++b) {
      golden_tr[static_cast<std::size_t>(dst + b)] = gm_mem[static_cast<std::size_t>(row_addr + b)];
    }
  }
}

static void init_source_for_tstore(
    const DataCase &tc,
    std::uint64_t tr_base,
    std::vector<std::uint8_t> &tr_mem,
    std::vector<std::uint8_t> &gm_mem,
    std::vector<std::uint8_t> &golden_gm,
    std::uint64_t gm_base) {
  const std::uint64_t rpc = rows_per_chunk(tc.inner_bytes);
  const std::uint64_t chunks = (tc.gm_outer + rpc - 1) / rpc;
  ensure_span(tr_mem, tr_base + chunks * 256 + 1024, 1);
  ensure_span(gm_mem, gm_base + tc.gm_outer * tc.stride_B + 1024, 1);
  ensure_span(golden_gm, gm_base + tc.gm_outer * tc.stride_B + 1024, 1);

  std::fill(gm_mem.begin(), gm_mem.end(), 0xA5u);
  std::fill(golden_gm.begin(), golden_gm.end(), 0xA5u);

  for (std::uint64_t r = 0; r < tc.gm_outer; ++r) {
    const std::uint64_t chunk = r / rpc;
    const std::uint64_t in_chunk = r % rpc;
    const std::uint64_t src = tr_base + chunk * 256 + in_chunk * tc.inner_bytes;
    for (std::uint64_t b = 0; b < tc.inner_bytes; ++b) {
      tr_mem[static_cast<std::size_t>(src + b)] = static_cast<std::uint8_t>((r * 73ull + b * 29ull + 0x3Cull) & 0xFFull);
    }
    const std::uint64_t row_addr = gm_base + r * tc.stride_B;
    for (std::uint64_t b = 0; b < tc.inner_bytes; ++b) {
      golden_gm[static_cast<std::size_t>(row_addr + b)] = tr_mem[static_cast<std::size_t>(src + b)];
    }
  }
}

static void set_defaults(pyc::gen::UtTmaHarness &dut) {
  dut.cmd_valid_tma = Wire<1>(0);
  dut.cmd_tag_tma = Wire<8>(0);
  dut.cmd_payload_tma = Wire<64>(0);

  dut.ut_cfg_layout = Wire<3>(0);
  dut.ut_cfg_elem_type = Wire<3>(0);
  dut.ut_cfg_pad_mode = Wire<2>(0);
  dut.ut_cfg_gm_base_addr = Wire<64>(0);
  dut.ut_cfg_tr_base_addr = Wire<64>(0);
  dut.ut_cfg_gm_inner_elems = Wire<16>(32);
  dut.ut_cfg_gm_outer_elems = Wire<16>(1);
  dut.ut_cfg_tr_inner_elems = Wire<16>(32);
  dut.ut_cfg_tr_outer_elems = Wire<16>(1);
  dut.ut_cfg_gm_inner_stride_B = Wire<16>(256);

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

static std::string trace_line(const TraceSample &t) {
  std::ostringstream oss;
  oss << "cyc=" << t.cycle << " st=" << t.state << " occ(b/r/w/d)=" << t.bpq_occ << "/" << t.rfb_occ << "/" << t.wcb_occ
      << "/" << t.bdb_occ << " gm(req " << t.gm_req_v << "/" << t.gm_req_r << " rsp " << t.gm_rsp_v << "/" << t.gm_rsp_r
      << " drx " << t.gm_dat_rx_v << "/" << t.gm_dat_rx_r << " dtx " << t.gm_dat_tx_v << "/" << t.gm_dat_tx_r
      << ") tr(req " << t.tr_req_v << "/" << t.tr_req_r << " rsp " << t.tr_rsp_v << "/" << t.tr_rsp_r << " drx "
      << t.tr_dat_rx_v << "/" << t.tr_dat_rx_r << " dtx " << t.tr_dat_tx_v << "/" << t.tr_dat_tx_r << ") rsp(v/tag/st)="
      << t.rsp_v << "/0x" << std::hex << t.rsp_tag << "/0x" << t.rsp_status << std::dec;
  return oss.str();
}

static bool run_data_case(
    const std::string &case_name,
    const DataCase &dc,
    const RuntimeConfig &cfg,
    bool dump_vcd,
    std::uint64_t base_seed,
    std::size_t trace_window,
    bool trace_all,
    const std::string &trace_case,
    bool *out_txnid_inc_ok,
    bool *out_rsp_order_ok,
    std::optional<std::uint64_t> payload_override) {
  pyc::gen::UtTmaHarness dut{};
  Testbench<pyc::gen::UtTmaHarness> tb(dut);
  tb.addClock(dut.clk, 1);

  set_defaults(dut);
  tb.reset(dut.rst, 2, 1);
  set_defaults(dut);

  if (dump_vcd) {
    const std::string vcd_path = "tb_tma_ut_" + case_name + ".vcd";
    tb.enableVcd(vcd_path, "tb_tma_ut");
    tb.vcdTrace(dut.clk, "clk");
    tb.vcdTrace(dut.rst, "rst");
    tb.vcdTrace(dut.cmd_valid_tma, "cmd_valid_tma");
    tb.vcdTrace(dut.cmd_ready_tma, "cmd_ready_tma");
    tb.vcdTrace(dut.rsp_valid_tma, "rsp_valid_tma");
    tb.vcdTrace(dut.rsp_status_tma, "rsp_status_tma");
    tb.vcdTrace(dut.dbg_state_tma, "dbg_state_tma");
  }

  const std::uint64_t case_seed = base_seed ^ fnv1a64(case_name);
  std::mt19937_64 rng(case_seed);

  const std::uint64_t elem_bytes = elem_bytes_from_type(dc.elem_type);
  const std::uint64_t gm_base = 0x0010'0000ull + dc.gm_base_off;
  const std::uint64_t tr_base = 0x0040'0000ull + dc.gm_base_off;
  const std::uint64_t gm_inner_elems = (elem_bytes == 0) ? 0 : (dc.inner_bytes / elem_bytes);
  const std::uint64_t tr_inner_elems = (elem_bytes == 0) ? 0 : (dc.tr_inner / elem_bytes);

  dut.ut_cfg_layout = Wire<3>(dc.layout);
  dut.ut_cfg_elem_type = Wire<3>(dc.elem_type);
  dut.ut_cfg_pad_mode = Wire<2>(dc.pad_mode);
  dut.ut_cfg_gm_base_addr = Wire<64>(gm_base);
  dut.ut_cfg_tr_base_addr = Wire<64>(tr_base);
  dut.ut_cfg_gm_inner_elems = Wire<16>(gm_inner_elems);
  dut.ut_cfg_gm_outer_elems = Wire<16>(dc.gm_outer);
  dut.ut_cfg_tr_inner_elems = Wire<16>(tr_inner_elems);
  dut.ut_cfg_tr_outer_elems = Wire<16>(dc.tr_outer);
  dut.ut_cfg_gm_inner_stride_B = Wire<16>(dc.stride_B);

  std::vector<std::uint8_t> gm_mem;
  std::vector<std::uint8_t> tr_mem;
  std::vector<std::uint8_t> golden_tr;
  std::vector<std::uint8_t> golden_gm;

  if (dc.expect_status == ST_OK) {
    if (dc.op == DataOp::tload) {
      init_source_for_tload(dc, gm_base, gm_mem, tr_mem, golden_tr);
    } else {
      init_source_for_tstore(dc, tr_base, tr_mem, gm_mem, golden_gm, gm_base);
    }
  }

  std::deque<RspEvent> gm_rsp_q;
  std::deque<RspEvent> tr_rsp_q;
  std::deque<DatEvent> gm_dat_q;
  std::deque<DatEvent> tr_dat_q;

  std::optional<RspEvent> gm_rsp_active;
  std::optional<RspEvent> tr_rsp_active;
  std::optional<DatEvent> gm_dat_active;
  std::optional<DatEvent> tr_dat_active;

  std::optional<PendingGmWrite> gm_wr;
  std::optional<PendingTrWrite> tr_wr;

  bool injected_access = false;
  bool injected_proto = false;

  std::vector<std::pair<std::uint8_t, std::uint64_t>> completions;
  std::vector<std::uint64_t> first_req_txnids;
  bool dbid_seen = false;
  bool dat_seen_before_rsp = false;
  bool order_ok = true;
  bool txnid_inc_ok = true;

  const bool trace_this_case = trace_all || (!trace_case.empty() && trace_case == case_name);
  std::deque<TraceSample> trace_ring;
  trace_ring.clear();

  std::uint64_t progress_watchdog = 0;
  constexpr std::uint64_t no_progress_window = 3000;

  std::uint64_t gm_req_expected = 0;
  std::uint64_t tr_req_expected = 0;
  if (elem_bytes != 0 && dc.inner_bytes != 0) {
    const std::uint64_t rpc = rows_per_chunk(dc.inner_bytes);
    tr_req_expected = (rpc == 0) ? 0 : ((dc.gm_outer + rpc - 1) / rpc);
    for (std::uint64_t r = 0; r < dc.gm_outer; ++r) {
      const std::uint64_t row_addr = gm_base + r * dc.stride_B;
      gm_req_expected += row_req_count(row_addr, dc.inner_bytes);
    }
  }
  const std::uint64_t cycle_budget = std::min<std::uint64_t>(300000, 30000 + gm_req_expected * 300 + tr_req_expected * 100);

  const std::uint8_t tag0 = 0xA1u;
  const std::uint8_t tag1 = 0xA2u;
  std::uint64_t payload = payload_override.has_value() ? *payload_override : ((dc.op == DataOp::tload) ? PAYLOAD_TLOAD : PAYLOAD_TSTORE);

  const bool two_cmd_case = cfg.issue_two_cmd;
  std::size_t cmds_total = two_cmd_case ? 2u : 1u;
  std::size_t cmd_issue_idx = 0;
  bool cmd_pending = true;
  std::uint8_t cmd_tag_cur = tag0;
  std::uint64_t cmd_payload_cur = payload;

  bool done = false;
  bool pass = true;

  std::uint64_t prev_state = 0;
  std::uint64_t prev_bpq_occ = 0;
  std::uint64_t prev_rfb_occ = 0;
  std::uint64_t prev_wcb_occ = 0;
  std::uint64_t prev_bdb_occ = 0;

  auto push_trace = [&](std::uint64_t cycle) {
    TraceSample t{};
    t.cycle = cycle;
    t.state = dut.dbg_state_tma.value();
    t.bpq_occ = dut.dbg_bpq_occ_tma.value();
    t.rfb_occ = dut.dbg_rfb_occ_tma.value();
    t.wcb_occ = dut.dbg_wcb_occ_tma.value();
    t.bdb_occ = dut.dbg_bdb_occ_tma.value();
    t.gm_req_v = dut.gm_req_valid.value();
    t.gm_req_r = dut.gm_req_ready.value();
    t.gm_rsp_v = dut.gm_rsp_valid.value();
    t.gm_rsp_r = dut.gm_rsp_ready.value();
    t.gm_dat_rx_v = dut.gm_dat_rx_valid.value();
    t.gm_dat_rx_r = dut.gm_dat_rx_ready.value();
    t.gm_dat_tx_v = dut.gm_dat_tx_valid.value();
    t.gm_dat_tx_r = dut.gm_dat_tx_ready.value();
    t.tr_req_v = dut.tr_req_valid.value();
    t.tr_req_r = dut.tr_req_ready.value();
    t.tr_rsp_v = dut.tr_rsp_valid.value();
    t.tr_rsp_r = dut.tr_rsp_ready.value();
    t.tr_dat_rx_v = dut.tr_dat_rx_valid.value();
    t.tr_dat_rx_r = dut.tr_dat_rx_ready.value();
    t.tr_dat_tx_v = dut.tr_dat_tx_valid.value();
    t.tr_dat_tx_r = dut.tr_dat_tx_ready.value();
    t.rsp_v = dut.rsp_valid_tma.value();
    t.rsp_tag = dut.rsp_tag_tma.value();
    t.rsp_status = dut.rsp_status_tma.value();
    trace_ring.push_back(t);
    while (trace_ring.size() > trace_window) {
      trace_ring.pop_front();
    }
    if (trace_this_case) {
      std::cout << "[trace] " << case_name << " " << trace_line(t) << "\n";
    }
  };

  auto fail_with_trace = [&](const std::string &msg) {
    std::cerr << "FAIL: " << case_name << " " << msg << "\n";
    for (const TraceSample &t : trace_ring) {
      std::cerr << "  " << trace_line(t) << "\n";
    }
    pass = false;
    done = true;
  };

  for (std::uint64_t cycle = 0; cycle < cycle_budget && !done; ++cycle) {
    // Default channel drives.
    dut.gm_req_ready = Wire<1>(cfg.force_no_progress ? 0 : 1);
    dut.gm_dat_tx_ready = Wire<1>(1);
    dut.tr_req_ready = Wire<1>(1);
    dut.tr_dat_tx_ready = Wire<1>(1);

    // Promote due events to active drivers.
    if (!gm_rsp_active.has_value() && !gm_rsp_q.empty() && gm_rsp_q.front().due <= cycle) {
      gm_rsp_active = gm_rsp_q.front();
      gm_rsp_q.pop_front();
    }
    if (!gm_dat_active.has_value() && !gm_dat_q.empty() && gm_dat_q.front().due <= cycle) {
      gm_dat_active = gm_dat_q.front();
      gm_dat_q.pop_front();
    }
    if (!tr_rsp_active.has_value() && !tr_rsp_q.empty() && tr_rsp_q.front().due <= cycle) {
      tr_rsp_active = tr_rsp_q.front();
      tr_rsp_q.pop_front();
    }
    if (!tr_dat_active.has_value() && !tr_dat_q.empty() && tr_dat_q.front().due <= cycle) {
      tr_dat_active = tr_dat_q.front();
      tr_dat_q.pop_front();
    }

    if (gm_rsp_active.has_value()) {
      dut.gm_rsp_valid = Wire<1>(1);
      dut.gm_rsp_opcode = Wire<6>(gm_rsp_active->opcode);
      dut.gm_rsp_txnid = Wire<8>(gm_rsp_active->txnid);
      dut.gm_rsp_dbid = Wire<8>(gm_rsp_active->dbid);
      dut.gm_rsp_resp = Wire<2>(gm_rsp_active->resp);
    } else {
      dut.gm_rsp_valid = Wire<1>(0);
      dut.gm_rsp_opcode = Wire<6>(0);
      dut.gm_rsp_txnid = Wire<8>(0);
      dut.gm_rsp_dbid = Wire<8>(0);
      dut.gm_rsp_resp = Wire<2>(0);
    }

    if (gm_dat_active.has_value()) {
      dut.gm_dat_rx_valid = Wire<1>(1);
      dut.gm_dat_rx_opcode = Wire<6>(gm_dat_active->opcode);
      dut.gm_dat_rx_txnid = Wire<8>(gm_dat_active->txnid);
      dut.gm_dat_rx_dbid = Wire<8>(gm_dat_active->dbid);
      dut.gm_dat_rx_data = wire_from_bytes(gm_dat_active->data);
      dut.gm_dat_rx_be = Wire<32>(gm_dat_active->be);
      dut.gm_dat_rx_resp = Wire<2>(gm_dat_active->resp);
    } else {
      dut.gm_dat_rx_valid = Wire<1>(0);
      dut.gm_dat_rx_opcode = Wire<6>(0);
      dut.gm_dat_rx_txnid = Wire<8>(0);
      dut.gm_dat_rx_dbid = Wire<8>(0);
      dut.gm_dat_rx_data = Wire<256>(0);
      dut.gm_dat_rx_be = Wire<32>(0);
      dut.gm_dat_rx_resp = Wire<2>(0);
    }

    if (tr_rsp_active.has_value()) {
      dut.tr_rsp_valid = Wire<1>(1);
      dut.tr_rsp_opcode = Wire<6>(tr_rsp_active->opcode);
      dut.tr_rsp_txnid = Wire<8>(tr_rsp_active->txnid);
      dut.tr_rsp_dbid = Wire<8>(tr_rsp_active->dbid);
      dut.tr_rsp_resp = Wire<2>(tr_rsp_active->resp);
    } else {
      dut.tr_rsp_valid = Wire<1>(0);
      dut.tr_rsp_opcode = Wire<6>(0);
      dut.tr_rsp_txnid = Wire<8>(0);
      dut.tr_rsp_dbid = Wire<8>(0);
      dut.tr_rsp_resp = Wire<2>(0);
    }

    if (tr_dat_active.has_value()) {
      dut.tr_dat_rx_valid = Wire<1>(1);
      dut.tr_dat_rx_opcode = Wire<6>(tr_dat_active->opcode);
      dut.tr_dat_rx_txnid = Wire<8>(tr_dat_active->txnid);
      dut.tr_dat_rx_dbid = Wire<8>(tr_dat_active->dbid);
      dut.tr_dat_rx_data = wire_from_bytes(tr_dat_active->data);
      dut.tr_dat_rx_be = Wire<32>(tr_dat_active->be);
      dut.tr_dat_rx_resp = Wire<2>(tr_dat_active->resp);
    } else {
      dut.tr_dat_rx_valid = Wire<1>(0);
      dut.tr_dat_rx_opcode = Wire<6>(0);
      dut.tr_dat_rx_txnid = Wire<8>(0);
      dut.tr_dat_rx_dbid = Wire<8>(0);
      dut.tr_dat_rx_data = Wire<256>(0);
      dut.tr_dat_rx_be = Wire<32>(0);
      dut.tr_dat_rx_resp = Wire<2>(0);
    }

    if (cmd_pending) {
      dut.cmd_valid_tma = Wire<1>(1);
      dut.cmd_tag_tma = Wire<8>(cmd_tag_cur);
      dut.cmd_payload_tma = Wire<64>(cmd_payload_cur);
    } else {
      dut.cmd_valid_tma = Wire<1>(0);
      dut.cmd_tag_tma = Wire<8>(0);
      dut.cmd_payload_tma = Wire<64>(0);
    }

    if (cfg.cmd_backpressure && cycle < 3) {
      dut.gm_req_ready = Wire<1>(0);
    }

    dut.eval();

    bool progress = false;

    const bool cmd_fire = (dut.cmd_valid_tma.value() != 0u) && (dut.cmd_ready_tma.value() != 0u);
    if (cmd_fire) {
      progress = true;
      cmd_issue_idx++;
      if (cmd_issue_idx >= cmds_total) {
        cmd_pending = false;
      } else {
        cmd_pending = true;
        cmd_tag_cur = tag1;
        cmd_payload_cur = payload;
      }
    }

    const bool gm_req_fire = (dut.gm_req_valid.value() != 0u) && (dut.gm_req_ready.value() != 0u);
    if (gm_req_fire) {
      progress = true;
      const std::uint64_t txnid = dut.gm_req_txnid.value();
      if (first_req_txnids.empty() || first_req_txnids.back() != txnid) {
        first_req_txnids.push_back(txnid);
      }
      const std::uint64_t beats = dut.gm_req_len.value() + 1u;
      const std::uint64_t addr = dut.gm_req_addr.value();
      if (dut.gm_req_opcode.value() == OPC_REQ_READ_ONCE) {
        const std::uint32_t dly = cfg.random_gm_latency ? gm_random_delay(rng) : 1u;
        for (std::uint64_t b = 0; b < beats; ++b) {
          DatEvent ev{};
          ev.due = cycle + dly + b;
          ev.opcode = OPC_DAT_COMPDATA;
          ev.txnid = txnid;
          ev.dbid = 0;
          ev.data = read_beat(gm_mem, addr + b * 32u);
          ev.be = 0xFFFF'FFFFu;
          ev.resp = 0;
          if (cfg.inject_access_err_once && !injected_access) {
            ev.resp = 2;
            injected_access = true;
          }
          if (cfg.inject_proto_err_once && !injected_proto) {
            ev.txnid ^= 0x1u;
            injected_proto = true;
          }
          gm_dat_q.push_back(ev);
        }
      } else if (dut.gm_req_opcode.value() == OPC_REQ_WRITE_UNIQUE) {
        gm_wr = PendingGmWrite{};
        gm_wr->addr = addr;
        gm_wr->txnid = txnid;
        gm_wr->beats = static_cast<std::uint32_t>(beats);
        gm_wr->received = 0;
        gm_wr->dbid = static_cast<std::uint64_t>((txnid + 0x21u) & 0xFFu);
        gm_wr->dbid_seen = false;

        RspEvent dbid{};
        dbid.due = cycle + (cfg.random_gm_latency ? gm_random_delay(rng) : 1u);
        dbid.opcode = OPC_RSP_COMP_DBID;
        dbid.txnid = txnid;
        dbid.dbid = gm_wr->dbid;
        dbid.resp = 0;
        if (cfg.inject_access_err_once && !injected_access) {
          dbid.resp = 2;
          injected_access = true;
        }
        if (cfg.inject_proto_err_once && !injected_proto) {
          dbid.txnid ^= 0x1u;
          injected_proto = true;
        }
        gm_rsp_q.push_back(dbid);
      }
    }

    const bool tr_req_fire = (dut.tr_req_valid.value() != 0u) && (dut.tr_req_ready.value() != 0u);
    if (tr_req_fire) {
      progress = true;
      const std::uint64_t txnid = dut.tr_req_txnid.value();
      const std::uint64_t beats = dut.tr_req_len.value() + 1u;
      const std::uint64_t addr = dut.tr_req_addr.value();
      if (dut.tr_req_opcode.value() == OPC_REQ_READ_NOSNP) {
        const std::uint32_t dly = tr_small_delay(rng);
        for (std::uint64_t b = 0; b < beats; ++b) {
          DatEvent ev{};
          ev.due = cycle + dly + b;
          ev.opcode = OPC_DAT_COMPDATA;
          ev.txnid = txnid;
          ev.dbid = 0;
          ev.data = read_beat(tr_mem, addr + b * 32u);
          ev.be = 0xFFFF'FFFFu;
          ev.resp = 0;
          if (cfg.inject_proto_err_once && !injected_proto) {
            ev.txnid ^= 0x1u;
            injected_proto = true;
          }
          tr_dat_q.push_back(ev);
        }
      } else if (dut.tr_req_opcode.value() == OPC_REQ_WRITE_NOSNP) {
        tr_wr = PendingTrWrite{};
        tr_wr->addr = addr;
        tr_wr->txnid = txnid;
        tr_wr->beats = static_cast<std::uint32_t>(beats);
        tr_wr->received = 0;
      }
    }

    const bool gm_rsp_fire = (dut.gm_rsp_valid.value() != 0u) && (dut.gm_rsp_ready.value() != 0u);
    if (gm_rsp_fire) {
      progress = true;
      if (gm_wr.has_value() && dut.gm_rsp_opcode.value() == OPC_RSP_COMP_DBID) {
        gm_wr->dbid_seen = true;
        dbid_seen = true;
      }
      if (gm_rsp_active.has_value()) {
        gm_rsp_active.reset();
      }
    }

    const bool tr_rsp_fire = (dut.tr_rsp_valid.value() != 0u) && (dut.tr_rsp_ready.value() != 0u);
    if (tr_rsp_fire) {
      progress = true;
      dat_seen_before_rsp = true;
      if (tr_rsp_active.has_value()) {
        tr_rsp_active.reset();
      }
    }

    const bool gm_dat_rx_fire = (dut.gm_dat_rx_valid.value() != 0u) && (dut.gm_dat_rx_ready.value() != 0u);
    if (gm_dat_rx_fire) {
      progress = true;
      dat_seen_before_rsp = true;
      if (gm_dat_active.has_value()) {
        gm_dat_active.reset();
      }
    }

    const bool tr_dat_rx_fire = (dut.tr_dat_rx_valid.value() != 0u) && (dut.tr_dat_rx_ready.value() != 0u);
    if (tr_dat_rx_fire) {
      progress = true;
      dat_seen_before_rsp = true;
      if (tr_dat_active.has_value()) {
        tr_dat_active.reset();
      }
    }

    const bool gm_dat_tx_fire = (dut.gm_dat_tx_valid.value() != 0u) && (dut.gm_dat_tx_ready.value() != 0u);
    if (gm_dat_tx_fire) {
      progress = true;
      if (gm_wr.has_value()) {
        if (cfg.check_dbid_before_data && !gm_wr->dbid_seen) {
          fail_with_trace("WriteData observed before DBID response");
          break;
        }
        const std::uint64_t beat_addr = gm_wr->addr + static_cast<std::uint64_t>(gm_wr->received) * 32u;
        const auto bytes = bytes_from_wire(dut.gm_dat_tx_data);
        const std::uint32_t be = static_cast<std::uint32_t>(dut.gm_dat_tx_be.value());
        write_beat(gm_mem, beat_addr, bytes, be);
        gm_wr->received++;
        if (gm_wr->received >= gm_wr->beats) {
          RspEvent comp{};
          comp.due = cycle + (cfg.random_gm_latency ? gm_random_delay(rng) : 1u);
          comp.opcode = OPC_RSP_COMP;
          comp.txnid = gm_wr->txnid;
          comp.dbid = gm_wr->dbid;
          comp.resp = 0;
          gm_rsp_q.push_back(comp);
          gm_wr.reset();
        }
      }
    }

    const bool tr_dat_tx_fire = (dut.tr_dat_tx_valid.value() != 0u) && (dut.tr_dat_tx_ready.value() != 0u);
    if (tr_dat_tx_fire) {
      progress = true;
      if (tr_wr.has_value()) {
        const std::uint64_t beat_addr = tr_wr->addr + static_cast<std::uint64_t>(tr_wr->received) * 32u;
        const auto bytes = bytes_from_wire(dut.tr_dat_tx_data);
        const std::uint32_t be = static_cast<std::uint32_t>(dut.tr_dat_tx_be.value());
        write_beat(tr_mem, beat_addr, bytes, be);
        tr_wr->received++;
        if (tr_wr->received >= tr_wr->beats) {
          RspEvent comp{};
          comp.due = cycle + tr_small_delay(rng);
          comp.opcode = OPC_RSP_COMP;
          comp.txnid = tr_wr->txnid;
          comp.dbid = 0;
          comp.resp = 0;
          tr_rsp_q.push_back(comp);
          tr_wr.reset();
        }
      }
    }

    tb.runPosedgeCycles(1);
    dut.eval();

    const bool rsp_fire = (dut.rsp_valid_tma.value() != 0u);
    if (rsp_fire) {
      progress = true;
      const std::uint8_t tag = static_cast<std::uint8_t>(dut.rsp_tag_tma.value() & 0xFFu);
      const std::uint64_t st = dut.rsp_status_tma.value();
      completions.push_back({tag, st});

      if (cfg.check_dat_before_rsp && !dat_seen_before_rsp && st == ST_OK) {
        fail_with_trace("Completion seen before any Dat progress");
        break;
      }

      if (cfg.check_rsp_order && completions.size() == 2) {
        order_ok = (completions[0].first == tag0) && (completions[1].first == tag1);
      }

      if (completions.size() >= cmds_total) {
        done = true;
      }
    }

    const std::uint64_t cur_state = dut.dbg_state_tma.value();
    const std::uint64_t cur_bpq = dut.dbg_bpq_occ_tma.value();
    const std::uint64_t cur_rfb = dut.dbg_rfb_occ_tma.value();
    const std::uint64_t cur_wcb = dut.dbg_wcb_occ_tma.value();
    const std::uint64_t cur_bdb = dut.dbg_bdb_occ_tma.value();
    if (cycle > 0) {
      const bool dbg_changed =
          (cur_state != prev_state) || (cur_bpq != prev_bpq_occ) || (cur_rfb != prev_rfb_occ) || (cur_wcb != prev_wcb_occ) ||
          (cur_bdb != prev_bdb_occ);
      progress = progress || dbg_changed;
    }
    prev_state = cur_state;
    prev_bpq_occ = cur_bpq;
    prev_rfb_occ = cur_rfb;
    prev_wcb_occ = cur_wcb;
    prev_bdb_occ = cur_bdb;

    push_trace(cycle);

    if (progress) {
      progress_watchdog = 0;
    } else {
      progress_watchdog++;
      if (progress_watchdog > no_progress_window) {
        fail_with_trace("no progress window exceeded (deadlock suspect)");
        break;
      }
    }
  }

  if (!done && pass) {
    fail_with_trace("cycle budget exceeded");
  }

  if (pass) {
    if (completions.size() != cmds_total) {
      fail_with_trace("completion count mismatch");
    }
  }

  if (pass) {
    for (const auto &cpl : completions) {
      if (cpl.second != cfg.expect_status) {
        std::ostringstream oss;
        oss << "status mismatch got=0x" << std::hex << cpl.second << " exp=0x" << cfg.expect_status << std::dec;
        fail_with_trace(oss.str());
        break;
      }
    }
  }

  if (pass && cfg.check_tag_echo) {
    if (completions.empty() || completions[0].first != tag0) {
      fail_with_trace("tag echo mismatch");
    }
  }

  if (pass && cfg.check_rsp_order && !order_ok) {
    fail_with_trace("completion order mismatch");
  }

  if (pass && cfg.check_txnid_increment) {
    if (first_req_txnids.size() >= 2) {
      txnid_inc_ok = (first_req_txnids[1] == ((first_req_txnids[0] + 1u) & 0xFFu));
    } else {
      txnid_inc_ok = false;
    }
    if (!txnid_inc_ok) {
      fail_with_trace("txn-id allocation did not increment between commands");
    }
  }

  if (pass && dc.expect_status == ST_OK) {
    if (dc.op == DataOp::tload) {
      const std::uint64_t rpc = rows_per_chunk(dc.inner_bytes);
      for (std::uint64_t r = 0; r < dc.gm_outer; ++r) {
        const std::uint64_t chunk = r / rpc;
        const std::uint64_t in_chunk = r % rpc;
        const std::uint64_t dst = tr_base + chunk * 256 + in_chunk * dc.inner_bytes;
        const std::uint64_t gdst = dc.gm_base_off + chunk * 256 + in_chunk * dc.inner_bytes;
        for (std::uint64_t b = 0; b < dc.inner_bytes; ++b) {
          const std::uint8_t got = tr_mem[static_cast<std::size_t>(dst + b)];
          const std::uint8_t exp = golden_tr[static_cast<std::size_t>(gdst + b)];
          if (got != exp) {
            std::ostringstream oss;
            oss << "TLOAD data mismatch row=" << r << " byte=" << b << " got=0x" << std::hex << int(got)
                << " exp=0x" << int(exp) << std::dec;
            fail_with_trace(oss.str());
            break;
          }
        }
        if (!pass) {
          break;
        }
      }
    } else {
      for (std::uint64_t r = 0; r < dc.gm_outer; ++r) {
        const std::uint64_t row_addr = gm_base + r * dc.stride_B;
        for (std::uint64_t b = 0; b < dc.inner_bytes; ++b) {
          const std::uint8_t got = gm_mem[static_cast<std::size_t>(row_addr + b)];
          const std::uint8_t exp = golden_gm[static_cast<std::size_t>(row_addr + b)];
          if (got != exp) {
            std::ostringstream oss;
            oss << "TSTORE data mismatch row=" << r << " byte=" << b << " got=0x" << std::hex << int(got)
                << " exp=0x" << int(exp) << std::dec;
            fail_with_trace(oss.str());
            break;
          }
        }
        if (!pass) {
          break;
        }
      }
    }
  }

  if (out_txnid_inc_ok != nullptr) {
    *out_txnid_inc_ok = txnid_inc_ok;
  }
  if (out_rsp_order_ok != nullptr) {
    *out_rsp_order_ok = order_ok;
  }
  return pass;
}

static DataCase protocol_to_data(const ProtocolCase &pc) {
  DataCase dc{};
  dc.name = pc.name;
  dc.op = (pc.payload == PAYLOAD_TSTORE) ? DataOp::tstore : DataOp::tload;
  dc.layout = 0;
  dc.elem_type = 0;
  dc.pad_mode = 0;
  dc.gm_base_off = 0;
  dc.inner_bytes = 32;
  dc.gm_outer = 8;
  dc.tr_inner = 32;
  dc.tr_outer = 8;
  dc.stride_B = 256;
  dc.expect_status = pc.expect_status;
  if (pc.name == "multi_cmd_sequential_completion_order" || pc.name == "txn_id_allocate_release_no_overlap") {
    dc.gm_outer = 16;
    dc.tr_outer = 16;
  }
  if (pc.name == "write_flow_requires_dbid_before_dat" || pc.name == "read_flow_requires_dat_before_complete") {
    dc.inner_bytes = 64;
    dc.gm_outer = 8;
    dc.tr_inner = 64;
    dc.tr_outer = 8;
  }
  return dc;
}

static bool run_protocol_case(
    const ProtocolCase &pc,
    bool dump_vcd,
    std::uint64_t base_seed,
    std::size_t trace_window,
    bool trace_all,
    const std::string &trace_case) {
  RuntimeConfig cfg{};
  cfg.random_gm_latency = true;
  cfg.force_no_progress = pc.force_timeout;
  cfg.inject_access_err_once = pc.inject_access_err;
  cfg.inject_proto_err_once = pc.inject_proto_err;
  cfg.issue_two_cmd = pc.two_cmd;
  cfg.cmd_backpressure = pc.cmd_backpressure;
  cfg.check_dbid_before_data = (pc.name == "write_flow_requires_dbid_before_dat");
  cfg.check_dat_before_rsp = (pc.name == "read_flow_requires_dat_before_complete");
  cfg.check_tag_echo = (pc.name == "rsp_tag_echo_and_single_completion");
  cfg.check_txnid_increment = (pc.name == "txn_id_allocate_release_no_overlap");
  cfg.check_rsp_order = (pc.name == "multi_cmd_sequential_completion_order");
  cfg.expect_status = pc.expect_status;

  DataCase dc = protocol_to_data(pc);
  bool txn_ok = true;
  bool order_ok = true;
  return run_data_case(pc.name, dc, cfg, dump_vcd, base_seed, trace_window, trace_all, trace_case, &txn_ok, &order_ok, pc.payload);
}

} // namespace

int main() {
  const bool dump_vcd = env_true("PYC_VCD");
  const bool trace_all = env_true("TMA_UT_TRACE");
  const std::string trace_case = env_str("TMA_UT_TRACE_CASE");
  const std::size_t trace_window = static_cast<std::size_t>(env_u64("TMA_UT_TRACE_WINDOW", 512));
  const std::uint64_t seed = env_u64("TMA_UT_SEED", 0x5EED1234ull);

  std::vector<ProtocolCase> protocol_cases = {
      {"tload_happy_path_single_cmd", PAYLOAD_TLOAD, false, false, false, false, false, ST_OK},
      {"tstore_happy_path_single_cmd", PAYLOAD_TSTORE, false, false, false, false, false, ST_OK},
      {"cmd_backpressure_hold_and_fire_once", PAYLOAD_TLOAD, false, true, false, false, false, ST_OK},
      {"write_flow_requires_dbid_before_dat", PAYLOAD_TSTORE, false, false, false, false, false, ST_OK},
      {"read_flow_requires_dat_before_complete", PAYLOAD_TLOAD, false, false, false, false, false, ST_OK},
      {"access_err_from_resp_slverr", PAYLOAD_TLOAD, false, false, true, false, false, ST_ACCESS_ERR},
      {"protocol_err_on_txnid_mismatch", PAYLOAD_TSTORE, false, false, false, true, false, ST_PROTOCOL_ERR},
      {"timeout_err_when_no_rsp_within_window", PAYLOAD_TLOAD, false, false, false, false, true, ST_TIMEOUT},
      {"unsupported_op_returns_status", PAYLOAD_UNSUPPORTED, false, false, false, false, false, ST_UNSUPPORTED},
      {"rsp_tag_echo_and_single_completion", PAYLOAD_TLOAD, false, false, false, false, false, ST_OK},
      {"multi_cmd_sequential_completion_order", PAYLOAD_TLOAD, true, false, false, false, false, ST_OK},
      {"txn_id_allocate_release_no_overlap", PAYLOAD_TLOAD, true, false, false, false, false, ST_OK},
  };

  const std::vector<DataCase> data_cases = linxcore::tma_ut::build_data_cases();

  std::size_t pass = 0;
  std::size_t fail = 0;

  for (const ProtocolCase &pc : protocol_cases) {
    const bool ok = run_protocol_case(pc, dump_vcd, seed, trace_window, trace_all, trace_case);
    if (ok) {
      std::cout << "[PASS] " << pc.name << "\n";
      pass++;
    } else {
      std::cout << "[FAIL] " << pc.name << "\n";
      fail++;
    }
  }

  for (const DataCase &dc : data_cases) {
    RuntimeConfig cfg{};
    cfg.random_gm_latency = true;
    cfg.force_no_progress = (dc.name == "deadlock_watchdog_no_progress_forced");
    cfg.issue_two_cmd = (dc.name == "multi_cmd_random_latency_no_deadlock");
    cfg.expect_status = dc.expect_status;

    bool txn_ok = true;
    bool order_ok = true;
    const bool ok = run_data_case(dc.name, dc, cfg, dump_vcd, seed, trace_window, trace_all, trace_case, &txn_ok, &order_ok, std::nullopt);
    if (ok) {
      std::cout << "[PASS] " << dc.name << "\n";
      pass++;
    } else {
      std::cout << "[FAIL] " << dc.name << "\n";
      fail++;
    }
  }

  const std::size_t total = protocol_cases.size() + data_cases.size();
  std::cout << "Summary: pass=" << pass << " fail=" << fail << " total=" << total << "\n";
  return (fail == 0) ? 0 : 1;
}
