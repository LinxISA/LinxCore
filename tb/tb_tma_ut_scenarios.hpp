#pragma once

#include <cstdint>
#include <initializer_list>
#include <string>
#include <vector>

namespace linxcore::tma_ut {

enum class InSig {
  cmd_valid_tma,
  cmd_tag_tma,
  cmd_payload_tma,
  gm_req_ready,
  gm_rsp_valid,
  gm_rsp_opcode,
  gm_rsp_txnid,
  gm_rsp_dbid,
  gm_rsp_resp,
  gm_dat_rx_valid,
  gm_dat_rx_opcode,
  gm_dat_rx_txnid,
  gm_dat_rx_resp,
  tr_rsp_valid,
  tr_rsp_opcode,
  tr_rsp_txnid,
  tr_rsp_resp,
  tr_dat_rx_valid,
  tr_dat_rx_opcode,
  tr_dat_rx_txnid,
  tr_dat_rx_resp,
};

enum class OutSig {
  cmd_ready_tma,
  rsp_valid_tma,
  rsp_tag_tma,
  rsp_status_tma,
  gm_req_valid,
  gm_req_opcode,
  gm_req_txnid,
  gm_dat_rx_ready,
  gm_dat_tx_valid,
  gm_dat_tx_opcode,
  gm_dat_tx_txnid,
  gm_dat_tx_dbid,
  tr_req_valid,
  tr_req_opcode,
  tr_req_txnid,
  tr_rsp_ready,
  tr_dat_tx_valid,
  tr_dat_tx_opcode,
  tr_dat_tx_txnid,
};

enum class CheckPhase {
  pre,
  post,
};

struct Drive {
  InSig sig;
  std::uint64_t value;
};

struct Expect {
  OutSig sig;
  std::uint64_t value;
  CheckPhase phase;
  std::string label;
};

struct Step {
  std::vector<Drive> drives;
  std::vector<Expect> expects;
  std::string note;
};

struct Scenario {
  std::string name;
  std::vector<Step> steps;
};

inline Drive D(InSig sig, std::uint64_t value) { return Drive{sig, value}; }

inline Expect PRE(OutSig sig, std::uint64_t value, const char *label = "") {
  return Expect{sig, value, CheckPhase::pre, label};
}

inline Expect POST(OutSig sig, std::uint64_t value, const char *label = "") {
  return Expect{sig, value, CheckPhase::post, label};
}

inline Step STEP(
    std::initializer_list<Drive> drives = {},
    std::initializer_list<Expect> expects = {},
    const char *note = "") {
  return Step{std::vector<Drive>(drives), std::vector<Expect>(expects), note};
}

inline std::vector<Scenario> build_scenarios() {
  static constexpr std::uint64_t ST_OK = 0x0;
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

  std::vector<Scenario> out;

  out.push_back(Scenario{
      "tload_happy_path_single_cmd",
      {
          STEP({D(InSig::cmd_tag_tma, 0x11), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1, "cmd ready before issue")}),
          STEP({D(InSig::cmd_valid_tma, 0)},
               {PRE(OutSig::gm_req_valid, 1), PRE(OutSig::gm_req_opcode, OPC_REQ_READ_ONCE), PRE(OutSig::gm_req_txnid, 0)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)},
               {PRE(OutSig::gm_dat_rx_ready, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)},
               {PRE(OutSig::tr_req_valid, 1), PRE(OutSig::tr_req_opcode, OPC_REQ_WRITE_NOSNP), PRE(OutSig::tr_req_txnid, 0)}),
          STEP({},
               {PRE(OutSig::tr_dat_tx_valid, 1), PRE(OutSig::tr_dat_tx_opcode, OPC_DAT_NCB_WRDATA), PRE(OutSig::tr_dat_tx_txnid, 0)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 0), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {PRE(OutSig::tr_rsp_ready, 1), POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0x11), POST(OutSig::rsp_status_tma, ST_OK)}),
          STEP({D(InSig::tr_rsp_valid, 0)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "tstore_happy_path_single_cmd",
      {
          STEP({D(InSig::cmd_tag_tma, 0x12), D(InSig::cmd_payload_tma, PAYLOAD_TSTORE), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)},
               {PRE(OutSig::tr_req_valid, 1), PRE(OutSig::tr_req_opcode, OPC_REQ_READ_NOSNP), PRE(OutSig::tr_req_txnid, 0)}),
          STEP({D(InSig::tr_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::tr_dat_rx_txnid, 0), D(InSig::tr_dat_rx_resp, 0), D(InSig::tr_dat_rx_valid, 1)}),
          STEP({D(InSig::tr_dat_rx_valid, 0)},
               {PRE(OutSig::gm_req_valid, 1), PRE(OutSig::gm_req_opcode, OPC_REQ_WRITE_UNIQUE), PRE(OutSig::gm_req_txnid, 0)}),
          STEP({D(InSig::gm_rsp_opcode, OPC_RSP_COMP_DBID), D(InSig::gm_rsp_txnid, 0), D(InSig::gm_rsp_dbid, 0x2A), D(InSig::gm_rsp_resp, 0), D(InSig::gm_rsp_valid, 1)}),
          STEP({D(InSig::gm_rsp_valid, 0)},
               {PRE(OutSig::gm_dat_tx_valid, 1), PRE(OutSig::gm_dat_tx_opcode, OPC_DAT_NCB_WRDATA), PRE(OutSig::gm_dat_tx_txnid, 0), PRE(OutSig::gm_dat_tx_dbid, 0x2A)}),
          STEP({D(InSig::gm_rsp_opcode, OPC_RSP_COMP), D(InSig::gm_rsp_txnid, 0), D(InSig::gm_rsp_resp, 0), D(InSig::gm_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0x12), POST(OutSig::rsp_status_tma, ST_OK)}),
          STEP({D(InSig::gm_rsp_valid, 0)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "cmd_backpressure_hold_and_fire_once",
      {
          STEP({D(InSig::cmd_tag_tma, 0x13), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0), D(InSig::gm_req_ready, 0)},
               {PRE(OutSig::gm_req_valid, 1), PRE(OutSig::cmd_ready_tma, 0)}),
          STEP({}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({D(InSig::gm_req_ready, 1)}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)},
               {PRE(OutSig::gm_dat_rx_ready, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_dat_tx_valid, 1)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 0), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_status_tma, ST_OK)}),
          STEP({D(InSig::tr_rsp_valid, 0)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "write_flow_requires_dbid_before_dat",
      {
          STEP({D(InSig::cmd_tag_tma, 0x14), D(InSig::cmd_payload_tma, PAYLOAD_TSTORE), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({D(InSig::tr_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::tr_dat_rx_txnid, 0), D(InSig::tr_dat_rx_resp, 0), D(InSig::tr_dat_rx_valid, 1)}),
          STEP({D(InSig::tr_dat_rx_valid, 0)}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({}, {PRE(OutSig::gm_dat_tx_valid, 0)}),
          STEP({D(InSig::gm_rsp_opcode, OPC_RSP_COMP_DBID), D(InSig::gm_rsp_txnid, 0), D(InSig::gm_rsp_dbid, 0x15), D(InSig::gm_rsp_resp, 0), D(InSig::gm_rsp_valid, 1)}),
          STEP({D(InSig::gm_rsp_valid, 0)}, {PRE(OutSig::gm_dat_tx_valid, 1), PRE(OutSig::gm_dat_tx_dbid, 0x15)}),
          STEP({D(InSig::gm_rsp_opcode, OPC_RSP_COMP), D(InSig::gm_rsp_txnid, 0), D(InSig::gm_rsp_resp, 0), D(InSig::gm_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_status_tma, ST_OK)}),
          STEP({D(InSig::gm_rsp_valid, 0)}),
      },
  });

  out.push_back(Scenario{
      "read_flow_requires_dat_before_complete",
      {
          STEP({D(InSig::cmd_tag_tma, 0x15), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_req_valid, 0), PRE(OutSig::rsp_valid_tma, 0)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_dat_tx_valid, 1)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 0), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_status_tma, ST_OK)}),
          STEP({D(InSig::tr_rsp_valid, 0)}),
      },
  });

  out.push_back(Scenario{
      "access_err_from_resp_slverr",
      {
          STEP({D(InSig::cmd_tag_tma, 0x16), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 2), D(InSig::gm_dat_rx_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_status_tma, ST_ACCESS_ERR)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "protocol_err_on_txnid_mismatch",
      {
          STEP({D(InSig::cmd_tag_tma, 0x17), D(InSig::cmd_payload_tma, PAYLOAD_TSTORE), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({D(InSig::tr_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::tr_dat_rx_txnid, 1), D(InSig::tr_dat_rx_resp, 0), D(InSig::tr_dat_rx_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_status_tma, ST_PROTOCOL_ERR)}),
          STEP({D(InSig::tr_dat_rx_valid, 0)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "timeout_err_when_no_rsp_within_window",
      {
          STEP({D(InSig::cmd_tag_tma, 0x18), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0), D(InSig::gm_req_ready, 0)}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({}, {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_status_tma, ST_TIMEOUT)}),
          STEP({D(InSig::gm_req_ready, 1)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "unsupported_op_returns_status",
      {
          STEP({D(InSig::cmd_tag_tma, 0x19), D(InSig::cmd_payload_tma, PAYLOAD_UNSUPPORTED), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)},
               {PRE(OutSig::rsp_valid_tma, 1), PRE(OutSig::rsp_tag_tma, 0x19), PRE(OutSig::rsp_status_tma, ST_UNSUPPORTED)}),
          STEP({}, {PRE(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "rsp_tag_echo_and_single_completion",
      {
          STEP({D(InSig::cmd_tag_tma, 0xAA), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::gm_req_txnid, 0)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_dat_tx_valid, 1)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 0), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0xAA)}),
          STEP({D(InSig::tr_rsp_valid, 0)}, {POST(OutSig::rsp_valid_tma, 0)}),
      },
  });

  out.push_back(Scenario{
      "multi_cmd_sequential_completion_order",
      {
          STEP({D(InSig::cmd_tag_tma, 0xB1), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 1), D(InSig::cmd_tag_tma, 0xB2), D(InSig::cmd_payload_tma, PAYLOAD_TSTORE)},
               {PRE(OutSig::cmd_ready_tma, 0), PRE(OutSig::gm_req_valid, 1), PRE(OutSig::gm_req_txnid, 0)}),
          STEP({D(InSig::cmd_valid_tma, 0), D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_dat_tx_valid, 1)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 0), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0xB1)}),
          STEP({D(InSig::tr_rsp_valid, 0)}),
          STEP({D(InSig::cmd_tag_tma, 0xB2), D(InSig::cmd_payload_tma, PAYLOAD_TSTORE), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::tr_req_valid, 1), PRE(OutSig::tr_req_txnid, 1)}),
          STEP({D(InSig::tr_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::tr_dat_rx_txnid, 1), D(InSig::tr_dat_rx_resp, 0), D(InSig::tr_dat_rx_valid, 1)}),
          STEP({D(InSig::tr_dat_rx_valid, 0)}, {PRE(OutSig::gm_req_valid, 1)}),
          STEP({D(InSig::gm_rsp_opcode, OPC_RSP_COMP_DBID), D(InSig::gm_rsp_txnid, 1), D(InSig::gm_rsp_dbid, 0x3C), D(InSig::gm_rsp_resp, 0), D(InSig::gm_rsp_valid, 1)}),
          STEP({D(InSig::gm_rsp_valid, 0)}, {PRE(OutSig::gm_dat_tx_valid, 1)}),
          STEP({D(InSig::gm_rsp_opcode, OPC_RSP_COMP), D(InSig::gm_rsp_txnid, 1), D(InSig::gm_rsp_resp, 0), D(InSig::gm_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0xB2)}),
          STEP({D(InSig::gm_rsp_valid, 0)}),
      },
  });

  out.push_back(Scenario{
      "txn_id_allocate_release_no_overlap",
      {
          STEP({D(InSig::cmd_tag_tma, 0xC1), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::gm_req_txnid, 0)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 0), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_dat_tx_valid, 1)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 0), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0xC1)}),
          STEP({D(InSig::tr_rsp_valid, 0)}),
          STEP({D(InSig::cmd_tag_tma, 0xC2), D(InSig::cmd_payload_tma, PAYLOAD_TLOAD), D(InSig::cmd_valid_tma, 1)},
               {PRE(OutSig::cmd_ready_tma, 1)}),
          STEP({D(InSig::cmd_valid_tma, 0)}, {PRE(OutSig::gm_req_txnid, 1)}),
          STEP({D(InSig::gm_dat_rx_opcode, OPC_DAT_COMPDATA), D(InSig::gm_dat_rx_txnid, 1), D(InSig::gm_dat_rx_resp, 0), D(InSig::gm_dat_rx_valid, 1)}),
          STEP({D(InSig::gm_dat_rx_valid, 0)}, {PRE(OutSig::tr_req_valid, 1)}),
          STEP({}, {PRE(OutSig::tr_dat_tx_valid, 1)}),
          STEP({D(InSig::tr_rsp_opcode, OPC_RSP_COMP), D(InSig::tr_rsp_txnid, 1), D(InSig::tr_rsp_resp, 0), D(InSig::tr_rsp_valid, 1)},
               {POST(OutSig::rsp_valid_tma, 1), POST(OutSig::rsp_tag_tma, 0xC2), POST(OutSig::rsp_status_tma, ST_OK)}),
          STEP({D(InSig::tr_rsp_valid, 0)}),
      },
  });

  return out;
}

} // namespace linxcore::tma_ut
