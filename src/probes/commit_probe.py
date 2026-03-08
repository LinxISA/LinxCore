from __future__ import annotations

from pycircuit import ProbeBuilder, ProbeView, probe

_TOP = "linxcore_top_root.linxcore_top_export"
_BACKEND = f"{_TOP}.janus_backend"
_COMMIT = f"{_BACKEND}.backend_trace_export.backend_commit_trace"


def define_commit_probe(target):
    @probe(target=target, name="commit")
    def commit_probe(p: ProbeBuilder, dut: ProbeView) -> None:
        p.emit(
            "redirect",
            {
                "valid": dut.read(f"{_BACKEND}.redirect_valid"),
                "pc": dut.read(f"{_BACKEND}.redirect_pc"),
                "bid": dut.read(f"{_BACKEND}.redirect_bid"),
                "replay_cause": dut.read(f"{_BACKEND}.replay_cause"),
                "bru_fault_set": dut.read(f"{_BACKEND}.bru_fault_set_dbg"),
            },
            at="tick",
            tags={"family": "commit", "stage": "redirect", "lane": 0},
        )
        for slot in range(4):
            p.emit(
                f"slot{slot}",
                {
                    "fire": dut.read(f"{_COMMIT}.commit_fire{slot}"),
                    "pc": dut.read(f"{_COMMIT}.commit_pc{slot}"),
                    "rob": dut.read(f"{_COMMIT}.commit_rob{slot}"),
                    "op": dut.read(f"{_COMMIT}.commit_op{slot}"),
                    "uop_uid": dut.read(f"{_COMMIT}.commit_uop_uid{slot}"),
                    "parent_uop_uid": dut.read(f"{_COMMIT}.commit_parent_uop_uid{slot}"),
                    "block_uid": dut.read(f"{_COMMIT}.commit_block_uid{slot}"),
                    "block_bid": dut.read(f"{_COMMIT}.commit_block_bid{slot}"),
                    "core_id": dut.read(f"{_COMMIT}.commit_core_id{slot}"),
                    "is_bstart": dut.read(f"{_COMMIT}.commit_is_bstart{slot}"),
                    "is_bstop": dut.read(f"{_COMMIT}.commit_is_bstop{slot}"),
                    "load_store_id": dut.read(f"{_COMMIT}.commit_load_store_id{slot}"),
                    "template_kind": dut.read(f"{_COMMIT}.commit_template_kind{slot}"),
                    "value": dut.read(f"{_COMMIT}.commit_value{slot}"),
                    "len": dut.read(f"{_COMMIT}.commit_len{slot}"),
                    "insn_raw": dut.read(f"{_COMMIT}.commit_insn_raw{slot}"),
                    "wb_valid": dut.read(f"{_COMMIT}.commit_wb_valid{slot}"),
                    "wb_rd": dut.read(f"{_COMMIT}.commit_wb_rd{slot}"),
                    "wb_data": dut.read(f"{_COMMIT}.commit_wb_data{slot}"),
                    "src0_valid": dut.read(f"{_COMMIT}.commit_src0_valid{slot}"),
                    "src0_reg": dut.read(f"{_COMMIT}.commit_src0_reg{slot}"),
                    "src0_data": dut.read(f"{_COMMIT}.commit_src0_data{slot}"),
                    "src1_valid": dut.read(f"{_COMMIT}.commit_src1_valid{slot}"),
                    "src1_reg": dut.read(f"{_COMMIT}.commit_src1_reg{slot}"),
                    "src1_data": dut.read(f"{_COMMIT}.commit_src1_data{slot}"),
                    "dst_valid": dut.read(f"{_COMMIT}.commit_dst_valid{slot}"),
                    "dst_reg": dut.read(f"{_COMMIT}.commit_dst_reg{slot}"),
                    "dst_data": dut.read(f"{_COMMIT}.commit_dst_data{slot}"),
                    "mem_valid": dut.read(f"{_COMMIT}.commit_mem_valid{slot}"),
                    "mem_is_store": dut.read(f"{_COMMIT}.commit_mem_is_store{slot}"),
                    "mem_addr": dut.read(f"{_COMMIT}.commit_mem_addr{slot}"),
                    "mem_wdata": dut.read(f"{_COMMIT}.commit_mem_wdata{slot}"),
                    "mem_rdata": dut.read(f"{_COMMIT}.commit_mem_rdata{slot}"),
                    "mem_size": dut.read(f"{_COMMIT}.commit_mem_size{slot}"),
                    "trap_valid": dut.read(f"{_COMMIT}.commit_trap_valid{slot}"),
                    "trap_cause": dut.read(f"{_COMMIT}.commit_trap_cause{slot}"),
                    "next_pc": dut.read(f"{_COMMIT}.commit_next_pc{slot}"),
                    "checkpoint_id": dut.read(f"{_COMMIT}.commit_checkpoint_id{slot}"),
                },
                at="tick",
                tags={"family": "commit", "stage": "cmt", "lane": slot},
            )

    return commit_probe
