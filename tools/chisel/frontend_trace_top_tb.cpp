#include "VLinxCoreFrontendTraceTop.h"
#include "verilated.h"

#include "commit_trace_jsonl.h"

#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

using linxcore::chisel::CommitTraceJsonRow;
using linxcore::chisel::write_dut_commit_jsonl;
using linxcore::chisel::write_qemu_commit_jsonl;

struct Args {
  std::string dut_trace;
  std::string qemu_trace;
};

struct ExpectedRow {
  std::uint64_t pc = 0;
  std::uint64_t insn = 0;
  std::uint8_t len = 4;
  bool src0_valid = false;
  std::uint8_t src0_reg = 0;
  bool src1_valid = false;
  std::uint8_t src1_reg = 0;
  bool dst_valid = false;
  std::uint8_t dst_reg = 0;
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
};

[[noreturn]] void usage(const char *argv0) {
  std::cerr << "usage: " << argv0
            << " --dut-trace <dut.jsonl> --qemu-trace <qemu.jsonl>\n";
  std::exit(2);
}

Args parse_args(int argc, char **argv) {
  Args args;
  for (int i = 1; i < argc; ++i) {
    const std::string arg(argv[i]);
    if (arg == "--dut-trace" && i + 1 < argc) {
      args.dut_trace = argv[++i];
    } else if (arg == "--qemu-trace" && i + 1 < argc) {
      args.qemu_trace = argv[++i];
    } else {
      usage(argv[0]);
    }
  }
  if (args.dut_trace.empty() || args.qemu_trace.empty()) {
    usage(argv[0]);
  }
  return args;
}

void clear_inputs(VLinxCoreFrontendTraceTop &dut) {
  dut.io_in_valid = 0;
  dut.io_in_peId = 0;
  dut.io_in_threadId = 0;
  dut.io_in_pc = 0;
  dut.io_in_window = 0;
  dut.io_in_pktUid = 0;
  dut.io_in_checkpointId = 0;
  dut.io_frontendFlushValid = 0;
  dut.io_completeValid = 0;
  dut.io_completeRobValue = 0;
  dut.io_deallocReady = 1;
}

void tick(VLinxCoreFrontendTraceTop &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
  dut.clock = 0;
  dut.eval();
}

void reset(VLinxCoreFrontendTraceTop &dut) {
  clear_inputs(dut);
  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  dut.eval();
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

void expect_monitor_clean(
    const VLinxCoreFrontendTraceTop &dut,
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

ObservedRow read_slot0(const VLinxCoreFrontendTraceTop &dut) {
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
  return row;
}

ObservedRow read_slot1(const VLinxCoreFrontendTraceTop &dut) {
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
  return row;
}

void expect_row(const ObservedRow &observed, const ExpectedRow &expected) {
  if (!observed.valid ||
      observed.slot != 0 ||
      observed.pc != expected.pc ||
      mask_insn(observed.insn, observed.len) != mask_insn(expected.insn, expected.len) ||
      observed.len != expected.len ||
      observed.wb_valid ||
      observed.mem_valid ||
      observed.trap_valid ||
      observed.src0_valid != expected.src0_valid ||
      observed.src1_valid != expected.src1_valid ||
      observed.dst_valid != expected.dst_valid ||
      observed.next_pc != expected.pc + expected.len) {
    std::cerr << "frontend trace top commit row mismatch"
              << " pc=0x" << std::hex << observed.pc
              << " insn=0x" << observed.insn
              << std::dec << " len=" << static_cast<unsigned>(observed.len)
              << " src0=(" << observed.src0_valid << ","
              << static_cast<unsigned>(observed.src0_reg) << ")"
              << " src1=(" << observed.src1_valid << ","
              << static_cast<unsigned>(observed.src1_reg) << ")"
              << " dst=(" << observed.dst_valid << ","
              << static_cast<unsigned>(observed.dst_reg) << ")"
              << " next_pc=0x" << std::hex << observed.next_pc << std::dec
              << "\n";
    std::exit(1);
  }

  if (observed.src0_valid &&
      (observed.src0_reg != expected.src0_reg || observed.src0_data != 0)) {
    std::cerr << "frontend trace top src0 mismatch"
              << " expected_reg=" << static_cast<unsigned>(expected.src0_reg)
              << " observed_reg=" << static_cast<unsigned>(observed.src0_reg)
              << " observed_data=" << observed.src0_data << "\n";
    std::exit(1);
  }
  if (observed.src1_valid &&
      (observed.src1_reg != expected.src1_reg || observed.src1_data != 0)) {
    std::cerr << "frontend trace top src1 mismatch"
              << " expected_reg=" << static_cast<unsigned>(expected.src1_reg)
              << " observed_reg=" << static_cast<unsigned>(observed.src1_reg)
              << " observed_data=" << observed.src1_data << "\n";
    std::exit(1);
  }
  if (observed.dst_valid &&
      (observed.dst_reg != expected.dst_reg || observed.dst_data != 0)) {
    std::cerr << "frontend trace top dst mismatch"
              << " expected_reg=" << static_cast<unsigned>(expected.dst_reg)
              << " observed_reg=" << static_cast<unsigned>(observed.dst_reg)
              << " observed_data=" << observed.dst_data << "\n";
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
  json.src0_valid = row.src0_valid;
  json.src0_reg = row.src0_reg;
  json.src1_valid = row.src1_valid;
  json.src1_reg = row.src1_reg;
  json.dst_valid = row.dst_valid;
  json.dst_reg = row.dst_reg;
  json.next_pc = row.pc + row.len;
  return json;
}

void write_dut_row(std::ofstream &out, const ObservedRow &row) {
  write_dut_commit_jsonl(out, to_json_row(row));
}

void write_qemu_row(std::ofstream &out, const ExpectedRow &row) {
  write_qemu_commit_jsonl(out, to_json_row(row));
}

std::uint8_t enqueue_frontend_row(
    VLinxCoreFrontendTraceTop &dut,
    const ExpectedRow &row,
    std::uint64_t pkt_uid) {
  clear_inputs(dut);
  dut.io_in_valid = 1;
  dut.io_in_pc = row.pc;
  dut.io_in_window = mask_insn(row.insn, row.len);
  dut.io_in_pktUid = pkt_uid;
  dut.io_in_checkpointId = pkt_uid & 0x3fU;
  dut.eval();

  if (!dut.io_decodeReady || !dut.io_selectedValid || ((dut.io_f4ValidMask & 0x1U) == 0)) {
    std::cerr << "frontend row was not accepted by F4/decode path"
              << " pc=0x" << std::hex << row.pc
              << " insn=0x" << row.insn << std::dec
              << " decodeReady=" << static_cast<unsigned>(dut.io_decodeReady)
              << " selectedValid=" << static_cast<unsigned>(dut.io_selectedValid)
              << " f4Mask=0x" << std::hex << static_cast<unsigned>(dut.io_f4ValidMask)
              << std::dec << "\n";
    std::exit(1);
  }
  const auto rob_value = static_cast<std::uint8_t>(dut.io_selectedRobValue);
  tick(dut);

  for (int cycle = 0; cycle < 8; ++cycle) {
    clear_inputs(dut);
    dut.eval();
    if (dut.io_renamedAccepted && dut.io_robRenameUpdateFire) {
      tick(dut);
      clear_inputs(dut);
      dut.eval();
      return rob_value;
    }
    tick(dut);
  }

  std::cerr << "frontend row did not reach rename/ROB update"
            << " pc=0x" << std::hex << row.pc << std::dec << "\n";
  std::exit(1);
}

void complete_rob_row(VLinxCoreFrontendTraceTop &dut, std::uint8_t rob_value) {
  clear_inputs(dut);
  dut.io_completeValid = 1;
  dut.io_completeRobValue = rob_value;
  dut.eval();
  if (dut.io_completeIgnored) {
    std::cerr << "completion surrogate was ignored for rob_value="
              << static_cast<unsigned>(rob_value) << "\n";
    std::exit(1);
  }
  tick(dut);
  clear_inputs(dut);
  dut.eval();
}

void drain_empty(VLinxCoreFrontendTraceTop &dut) {
  for (int cycle = 0; cycle < 8; ++cycle) {
    clear_inputs(dut);
    dut.eval();
    if (dut.io_empty && dut.io_size == 0) {
      return;
    }
    tick(dut);
  }
  std::cerr << "frontend trace top did not drain after commit"
            << " size=" << static_cast<unsigned>(dut.io_size)
            << " outstanding=" << static_cast<unsigned>(dut.io_outstandingCount)
            << "\n";
  std::exit(1);
}

void commit_expected_row(
    VLinxCoreFrontendTraceTop &dut,
    const ExpectedRow &expected,
    std::ofstream &dut_out,
    std::ofstream &qemu_out) {
  for (int cycle = 0; cycle < 8; ++cycle) {
    clear_inputs(dut);
    dut.eval();
    if (dut.io_commit_rows_0_valid) {
      const ObservedRow slot0 = read_slot0(dut);
      const ObservedRow slot1 = read_slot1(dut);
      expect_monitor_clean(dut, "frontend trace top commit", 0x1, 1);
      expect_row(slot0, expected);
      if (slot1.valid) {
        std::cerr << "frontend trace top expected a single-row commit window\n";
        std::exit(1);
      }
      write_dut_row(dut_out, slot0);
      write_dut_row(dut_out, slot1);
      write_qemu_row(qemu_out, expected);
      tick(dut);
      drain_empty(dut);
      return;
    }
    expect_monitor_clean(dut, "frontend trace top wait", 0x0, 0);
    tick(dut);
  }

  std::cerr << "frontend trace top did not emit a commit row"
            << " pc=0x" << std::hex << expected.pc << std::dec << "\n";
  std::exit(1);
}

std::vector<ExpectedRow> fixture_rows() {
  const std::uint64_t add =
      0x00000005ULL | (3ULL << 7) | (4ULL << 15) | (5ULL << 20);
  const std::uint64_t addi =
      0x00000015ULL | (6ULL << 7) | (7ULL << 15) | (0x7ffULL << 20);
  const std::uint64_t c_movr =
      0x0006ULL | (4ULL << 6) | (5ULL << 11);

  ExpectedRow r0;
  r0.pc = 0x1000;
  r0.insn = add;
  r0.len = 4;
  r0.src0_valid = true;
  r0.src0_reg = 4;
  r0.src1_valid = true;
  r0.src1_reg = 5;
  r0.dst_valid = true;
  r0.dst_reg = 3;

  ExpectedRow r1;
  r1.pc = 0x1004;
  r1.insn = addi;
  r1.len = 4;
  r1.src0_valid = true;
  r1.src0_reg = 7;
  r1.dst_valid = true;
  r1.dst_reg = 6;

  ExpectedRow r2;
  r2.pc = 0x1008;
  r2.insn = c_movr;
  r2.len = 2;
  r2.src0_valid = true;
  r2.src0_reg = 4;
  r2.dst_valid = true;
  r2.dst_reg = 5;

  return {r0, r1, r2};
}

} // namespace

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  const Args args = parse_args(argc, argv);

  VLinxCoreFrontendTraceTop dut;
  reset(dut);

  std::ofstream dut_out(args.dut_trace);
  std::ofstream qemu_out(args.qemu_trace);
  if (!dut_out || !qemu_out) {
    std::cerr << "failed to open output traces\n";
    return 2;
  }

  const auto rows = fixture_rows();
  for (std::size_t i = 0; i < rows.size(); ++i) {
    const std::uint8_t rob_value = enqueue_frontend_row(dut, rows[i], i);
    complete_rob_row(dut, rob_value);
    commit_expected_row(dut, rows[i], dut_out, qemu_out);
  }

  dut.eval();
  if (!dut.io_idle || !dut.io_empty || dut.io_size != 0) {
    std::cerr << "frontend trace top did not finish idle"
              << " idle=" << static_cast<unsigned>(dut.io_idle)
              << " empty=" << static_cast<unsigned>(dut.io_empty)
              << " size=" << static_cast<unsigned>(dut.io_size)
              << "\n";
    return 1;
  }
  expect_monitor_clean(dut, "post-drain idle window", 0x0, 0);

  dut.final();
  return 0;
}
