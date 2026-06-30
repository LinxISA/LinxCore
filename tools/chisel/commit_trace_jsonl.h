#pragma once
#ifndef LINXCORE_CHISEL_COMMIT_TRACE_JSONL_H
#define LINXCORE_CHISEL_COMMIT_TRACE_JSONL_H

#include <cstdint>
#include <ostream>

namespace linxcore::chisel {

struct CommitTraceJsonRow {
  bool valid = true;
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
  std::uint8_t len = 4;
  bool wb_valid = false;
  std::uint8_t wb_rd = 0;
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

inline unsigned bit(bool value) { return value ? 1U : 0U; }

inline void write_arch_fields(std::ostream &out, const CommitTraceJsonRow &row) {
  out << "\"pc\":" << row.pc
      << ",\"insn\":" << row.insn
      << ",\"len\":" << static_cast<unsigned>(row.len)
      << ",\"wb_valid\":" << bit(row.wb_valid)
      << ",\"wb_rd\":" << static_cast<unsigned>(row.wb_rd)
      << ",\"wb_data\":" << row.wb_data
      << ",\"src0_valid\":" << bit(row.src0_valid)
      << ",\"src0_reg\":" << static_cast<unsigned>(row.src0_reg)
      << ",\"src0_data\":" << row.src0_data
      << ",\"src1_valid\":" << bit(row.src1_valid)
      << ",\"src1_reg\":" << static_cast<unsigned>(row.src1_reg)
      << ",\"src1_data\":" << row.src1_data
      << ",\"dst_valid\":" << bit(row.dst_valid)
      << ",\"dst_reg\":" << static_cast<unsigned>(row.dst_reg)
      << ",\"dst_data\":" << row.dst_data
      << ",\"mem_valid\":" << bit(row.mem_valid)
      << ",\"mem_is_store\":" << bit(row.mem_is_store)
      << ",\"mem_addr\":" << row.mem_addr
      << ",\"mem_wdata\":" << row.mem_wdata
      << ",\"mem_rdata\":" << row.mem_rdata
      << ",\"mem_size\":" << static_cast<unsigned>(row.mem_size)
      << ",\"trap_valid\":" << bit(row.trap_valid)
      << ",\"trap_cause\":" << row.trap_cause
      << ",\"traparg0\":" << row.trap_arg0
      << ",\"next_pc\":" << row.next_pc;
}

inline void write_dut_commit_jsonl(std::ostream &out, const CommitTraceJsonRow &row) {
  out << "{\"valid\":" << bit(row.valid)
      << ",\"seq\":" << row.seq
      << ",\"cycle\":" << row.cycle
      << ",\"slot\":" << static_cast<unsigned>(row.slot)
      << ",\"bid\":" << row.bid
      << ",\"gid\":" << row.gid
      << ",\"rid\":" << row.rid
      << ",\"rob_valid\":" << bit(row.rob_valid)
      << ",\"rob_wrap\":" << bit(row.rob_wrap)
      << ",\"rob_value\":" << static_cast<unsigned>(row.rob_value)
      << ",\"block_bid_valid\":" << bit(row.block_bid_valid)
      << ",\"block_bid\":" << row.block_bid
      << ",";
  write_arch_fields(out, row);
  out << "}\n";
}

inline void write_qemu_commit_jsonl(std::ostream &out, const CommitTraceJsonRow &row) {
  out << "{";
  write_arch_fields(out, row);
  out << "}\n";
}

} // namespace linxcore::chisel

#endif // LINXCORE_CHISEL_COMMIT_TRACE_JSONL_H
