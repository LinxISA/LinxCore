from __future__ import annotations

from pycircuit import Circuit, module, u, unsigned

from common.isa import (
    OP_ADDI,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
    OP_LDI,
    OP_SDI,
    OP_SETC_TGT,
    OP_SUBI,
)


@module(name="LinxCoreMacroTracePrepStage")
def build_macro_trace_prep_stage(
    m: Circuit,
    *,
    rob_w: int = 6,
) -> None:
    if rob_w <= 0:
        raise ValueError("rob_w must be > 0")

    pc_i = m.input("pc_i", width=64)
    rob_head_i = m.input("rob_head_i", width=rob_w)
    head_len_i = m.input("head_len_i", width=3)
    head_value_i = m.input("head_value_i", width=64)
    head_insn_raw_i = m.input("head_insn_raw_i", width=64)

    macro_op_i = m.input("macro_op_i", width=12)
    macro_uop_valid_i = m.input("macro_uop_valid_i", width=1)
    macro_stacksize_i = m.input("macro_stacksize_i", width=64)
    macro_frame_adj_i = m.input("macro_frame_adj_i", width=64)

    macro_reg_write_i = m.input("macro_reg_write_i", width=1)
    macro_uop_reg_i = m.input("macro_uop_reg_i", width=6)
    macro_uop_addr_i = m.input("macro_uop_addr_i", width=64)
    macro_uop_is_sp_sub_i = m.input("macro_uop_is_sp_sub_i", width=1)
    macro_uop_is_sp_add_i = m.input("macro_uop_is_sp_add_i", width=1)
    macro_uop_is_store_i = m.input("macro_uop_is_store_i", width=1)
    macro_uop_is_load_i = m.input("macro_uop_is_load_i", width=1)
    macro_uop_is_setc_tgt_i = m.input("macro_uop_is_setc_tgt_i", width=1)
    macro_reg_is_gpr_i = m.input("macro_reg_is_gpr_i", width=1)
    macro_reg_not_zero_i = m.input("macro_reg_not_zero_i", width=1)
    macro_store_fire_i = m.input("macro_store_fire_i", width=1)
    macro_store_data_i = m.input("macro_store_data_i", width=64)
    macro_load_data_eff_i = m.input("macro_load_data_eff_i", width=64)
    macro_sp_val_i = m.input("macro_sp_val_i", width=64)

    step_done_i = m.input("step_done_i", width=1)
    commit_tgt_live_i = m.input("commit_tgt_live_i", width=64)

    macro_trace_fire = macro_uop_valid_i
    macro_trace_pc = pc_i
    macro_trace_rob = rob_head_i
    macro_trace_val = head_value_i
    macro_trace_len = head_len_i
    macro_trace_insn_raw = head_insn_raw_i

    # Template child opcode mapping: default to macro opcode unless a child kind
    # is active (mutually exclusive under CTU contracts).
    macro_trace_op = macro_op_i
    macro_trace_op = u(12, OP_SUBI) if macro_uop_is_sp_sub_i else macro_trace_op
    macro_trace_op = u(12, OP_ADDI) if macro_uop_is_sp_add_i else macro_trace_op
    macro_trace_op = u(12, OP_SDI) if macro_uop_is_store_i else macro_trace_op
    macro_trace_op = u(12, OP_LDI) if macro_uop_is_load_i else macro_trace_op
    macro_trace_op = u(12, OP_SETC_TGT) if macro_uop_is_setc_tgt_i else macro_trace_op

    macro_adj_nonzero = macro_frame_adj_i != u(64, 0)

    wb_load = macro_reg_write_i
    wb_sp_sub = macro_uop_is_sp_sub_i & macro_adj_nonzero
    wb_sp_add = macro_uop_is_sp_add_i & macro_adj_nonzero
    macro_trace_wb_valid = macro_trace_fire & (wb_load | wb_sp_sub | wb_sp_add)

    macro_trace_wb_rd = u(6, 0)
    macro_trace_wb_rd = macro_uop_reg_i if wb_load else macro_trace_wb_rd
    macro_trace_wb_rd = u(6, 1) if (wb_sp_sub | wb_sp_add) else macro_trace_wb_rd

    macro_trace_wb_data = u(64, 0)
    macro_trace_wb_data = macro_load_data_eff_i if wb_load else macro_trace_wb_data
    macro_trace_wb_data = (macro_sp_val_i + macro_frame_adj_i) if wb_sp_add else macro_trace_wb_data
    macro_trace_wb_data = (macro_sp_val_i - macro_frame_adj_i) if wb_sp_sub else macro_trace_wb_data

    mem_store = macro_store_fire_i
    mem_load = macro_uop_is_load_i & macro_reg_is_gpr_i & macro_reg_not_zero_i
    macro_trace_mem_valid = macro_trace_fire & (mem_store | mem_load)
    macro_trace_mem_is_store = macro_trace_fire & mem_store
    macro_trace_mem_addr = macro_uop_addr_i
    macro_trace_mem_wdata = macro_store_data_i if mem_store else u(64, 0)
    macro_trace_mem_rdata = macro_load_data_eff_i if mem_load else u(64, 0)
    macro_trace_mem_size = u(4, 8) if macro_trace_mem_valid else u(4, 0)

    macro_trace_src0_valid = macro_trace_fire & (
        macro_uop_is_sp_sub_i | macro_uop_is_sp_add_i | macro_store_fire_i | macro_uop_is_load_i
    )
    macro_trace_src0_reg = u(6, 1)
    macro_trace_src0_reg = macro_uop_reg_i if macro_store_fire_i else macro_trace_src0_reg
    macro_trace_src0_data = macro_sp_val_i
    macro_trace_src0_data = macro_store_data_i if macro_store_fire_i else macro_trace_src0_data

    macro_trace_src1_valid = u(1, 0)
    macro_trace_src1_reg = u(6, 0)
    macro_trace_src1_data = u(64, 0)

    macro_trace_dst_valid = macro_trace_wb_valid
    macro_trace_dst_reg = macro_trace_wb_rd
    macro_trace_dst_data = macro_trace_wb_data

    is_fentry = macro_op_i == u(12, OP_FENTRY)
    is_fexit = macro_op_i == u(12, OP_FEXIT)
    is_fret = (macro_op_i == u(12, OP_FRET_RA)) | (macro_op_i == u(12, OP_FRET_STK))

    seq_pc = macro_trace_pc + (unsigned(head_len_i) + u(64, 0))
    done_fentry = (macro_uop_is_sp_sub_i & (macro_stacksize_i == u(64, 0))) | (macro_uop_is_store_i & step_done_i)
    done_fexit = macro_uop_is_load_i & step_done_i & is_fexit
    done_fret = macro_uop_is_load_i & step_done_i & is_fret
    macro_trace_next_pc = macro_trace_pc
    macro_trace_next_pc = seq_pc if done_fentry else macro_trace_next_pc
    macro_trace_next_pc = seq_pc if done_fexit else macro_trace_next_pc
    macro_trace_next_pc = commit_tgt_live_i if done_fret else macro_trace_next_pc

    # QEMU commit-trace emits a side-effect-free template marker before the
    # first architecturally visible FRET template micro-op.
    macro_shadow_fire = macro_trace_fire & (
        ((macro_op_i == u(12, OP_FRET_STK)) & macro_uop_is_sp_add_i)
        | ((macro_op_i == u(12, OP_FRET_RA)) & macro_uop_is_setc_tgt_i)
    )
    # For FRET.STK, the SP adjust is internal-only; emit only the shadow marker.
    macro_shadow_emit_uop = macro_shadow_fire & (~((macro_op_i == u(12, OP_FRET_STK)) & macro_uop_is_sp_add_i))

    m.output("macro_trace_fire", macro_trace_fire)
    m.output("macro_trace_pc", macro_trace_pc)
    m.output("macro_trace_rob", macro_trace_rob)
    m.output("macro_trace_op", macro_trace_op)
    m.output("macro_trace_val", macro_trace_val)
    m.output("macro_trace_len", macro_trace_len)
    m.output("macro_trace_insn_raw", macro_trace_insn_raw)
    m.output("macro_trace_wb_valid", macro_trace_wb_valid)
    m.output("macro_trace_wb_rd", macro_trace_wb_rd)
    m.output("macro_trace_wb_data", macro_trace_wb_data)
    m.output("macro_trace_src0_valid", macro_trace_src0_valid)
    m.output("macro_trace_src0_reg", macro_trace_src0_reg)
    m.output("macro_trace_src0_data", macro_trace_src0_data)
    m.output("macro_trace_src1_valid", macro_trace_src1_valid)
    m.output("macro_trace_src1_reg", macro_trace_src1_reg)
    m.output("macro_trace_src1_data", macro_trace_src1_data)
    m.output("macro_trace_dst_valid", macro_trace_dst_valid)
    m.output("macro_trace_dst_reg", macro_trace_dst_reg)
    m.output("macro_trace_dst_data", macro_trace_dst_data)
    m.output("macro_trace_mem_valid", macro_trace_mem_valid)
    m.output("macro_trace_mem_is_store", macro_trace_mem_is_store)
    m.output("macro_trace_mem_addr", macro_trace_mem_addr)
    m.output("macro_trace_mem_wdata", macro_trace_mem_wdata)
    m.output("macro_trace_mem_rdata", macro_trace_mem_rdata)
    m.output("macro_trace_mem_size", macro_trace_mem_size)
    m.output("macro_trace_next_pc", macro_trace_next_pc)
    m.output("macro_shadow_fire", macro_shadow_fire)
    m.output("macro_shadow_emit_uop", macro_shadow_emit_uop)

