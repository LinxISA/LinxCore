from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK


@module(name="CodeTemplateUnit")
def build_code_template_unit(m: Circuit) -> None:
    c = m.const

    # Global run controls.
    base_can_run = m.input("base_can_run", width=1)

    # Head-of-ROB view for template start.
    head_is_macro = m.input("head_is_macro", width=1)
    head_skip = m.input("head_skip", width=1)
    head_valid = m.input("head_valid", width=1)
    head_done = m.input("head_done", width=1)

    # Current template state.
    macro_active_i = m.input("macro_active_i", width=1)
    macro_wait_commit_i = m.input("macro_wait_commit_i", width=1)
    macro_phase_i = m.input("macro_phase_i", width=2)
    macro_op_i = m.input("macro_op_i", width=12)
    macro_end_i = m.input("macro_end_i", width=6)
    macro_stacksize_i = m.input("macro_stacksize_i", width=64)
    macro_reg_i = m.input("macro_reg_i", width=6)
    macro_i_i = m.input("macro_i_i", width=6)
    macro_sp_base_i = m.input("macro_sp_base_i", width=64)
    macro_uop_uid_i = m.input("macro_uop_uid_i", width=64)
    macro_uop_parent_uid_i = m.input("macro_uop_parent_uid_i", width=64)

    head_ready = head_valid & head_done
    start_fire = (
        base_can_run
        & (~macro_active_i)
        & (~macro_wait_commit_i)
        & head_is_macro
        & (~head_skip)
        & head_ready
    )
    block_ifu = macro_active_i | start_fire

    ph_init = c(0, width=2)
    ph_mem = c(1, width=2)
    ph_sp = c(2, width=2)
    ph_setc = c(3, width=2)

    phase_init = macro_phase_i.eq(ph_init)
    phase_mem = macro_phase_i.eq(ph_mem)
    phase_sp = macro_phase_i.eq(ph_sp)
    phase_setc = macro_phase_i.eq(ph_setc)
    macro_is_fentry = macro_op_i.eq(c(OP_FENTRY, width=12))
    macro_is_fexit = macro_op_i.eq(c(OP_FEXIT, width=12))
    macro_is_fret_ra = macro_op_i.eq(c(OP_FRET_RA, width=12))
    macro_is_fret_stk = macro_op_i.eq(c(OP_FRET_STK, width=12))

    # Address progression for frame-template memory uops:
    # - FENTRY save:   addr = sp_base + (stack - (i + 1) * 8)
    # - FEXIT/FRET.*:  addr = sp_base - (i + 1) * 8
    i1 = (macro_i_i + c(1, width=6)).zext(width=64)
    bytes_i = i1.shl(amount=3)
    off_ok = bytes_i.ule(macro_stacksize_i)
    store_off = macro_stacksize_i - bytes_i
    store_addr = macro_sp_base_i + store_off
    load_addr = macro_sp_base_i - bytes_i
    uop_addr = macro_is_fentry.select(store_addr, load_addr)

    # Iteration control for mem phase.
    loop_fire = macro_active_i & phase_mem
    loop_done = loop_fire & ((~off_ok) | macro_reg_i.eq(macro_end_i))
    reg_plus = macro_reg_i + c(1, width=6)
    reg_wrap = reg_plus.ugt(c(23, width=6))
    loop_reg_next = reg_wrap.select(c(2, width=6), reg_plus)
    loop_i_next = macro_i_i + c(1, width=6)

    # Template-uop stream.
    #
    # - SP_SUB: FENTRY init phase (allocate frame)
    # - STORE:  FENTRY mem phase
    # - SP_ADD: FEXIT/FRET.STK init and FRET.RA pre-mem phase
    # - LOAD:   FEXIT/FRET.* mem loop
    # - SETC_TGT: FRET.STK post-RA-load or FRET.RA first step
    k_none = c(0, width=3)
    k_sp_sub = c(1, width=3)
    k_store = c(2, width=3)
    k_load = c(3, width=3)
    k_sp_add = c(4, width=3)
    k_setc_tgt = c(5, width=3)

    uop_valid = c(0, width=1)
    uop_kind = k_none
    init_fire = macro_active_i & phase_init
    uop_valid = (init_fire & macro_is_fentry).select(c(1, width=1), uop_valid)
    uop_kind = (init_fire & macro_is_fentry).select(k_sp_sub, uop_kind)
    uop_valid = (init_fire & (macro_is_fexit | macro_is_fret_stk)).select(c(1, width=1), uop_valid)
    uop_kind = (init_fire & (macro_is_fexit | macro_is_fret_stk)).select(k_sp_add, uop_kind)
    uop_valid = (init_fire & macro_is_fret_ra).select(c(1, width=1), uop_valid)
    uop_kind = (init_fire & macro_is_fret_ra).select(k_setc_tgt, uop_kind)

    mem_uop_fire = macro_active_i & phase_mem & off_ok
    mem_kind = macro_is_fentry.select(k_store, k_load)
    uop_valid = mem_uop_fire.select(c(1, width=1), uop_valid)
    uop_kind = mem_uop_fire.select(mem_kind, uop_kind)

    sp_uop_fire = macro_active_i & phase_sp & macro_is_fret_ra
    uop_valid = sp_uop_fire.select(c(1, width=1), uop_valid)
    uop_kind = sp_uop_fire.select(k_sp_add, uop_kind)

    setc_uop_fire = macro_active_i & phase_setc & macro_is_fret_stk
    uop_valid = setc_uop_fire.select(c(1, width=1), uop_valid)
    uop_kind = setc_uop_fire.select(k_setc_tgt, uop_kind)

    uop_is_sp_sub = uop_valid & uop_kind.eq(k_sp_sub)
    uop_is_store = uop_valid & uop_kind.eq(k_store)
    uop_is_load = uop_valid & uop_kind.eq(k_load)
    uop_is_sp_add = uop_valid & uop_kind.eq(k_sp_add)
    uop_is_setc_tgt = uop_valid & uop_kind.eq(k_setc_tgt)

    m.output("start_fire", start_fire)
    m.output("block_ifu", block_ifu)

    m.output("macro_is_fentry", macro_is_fentry)
    m.output("phase_init", phase_init)
    m.output("phase_mem", phase_mem)
    m.output("phase_sp", phase_sp)
    m.output("phase_setc", phase_setc)

    m.output("off_ok", off_ok)
    m.output("uop_valid", uop_valid)
    m.output("uop_kind", uop_kind)
    m.output("uop_reg", macro_reg_i)
    m.output("uop_addr", uop_addr)
    m.output("uop_size", c(8, width=4))
    m.output("uop_is_sp_sub", uop_is_sp_sub)
    m.output("uop_is_store", uop_is_store)
    m.output("uop_is_load", uop_is_load)
    m.output("uop_is_sp_add", uop_is_sp_add)
    m.output("uop_is_setc_tgt", uop_is_setc_tgt)
    m.output("uop_uid", macro_uop_uid_i)
    m.output("uop_parent_uid", macro_uop_parent_uid_i)
    # 1=fentry,2=fexit,3=fret_ra,4=fret_stk
    uop_template_kind = c(0, width=3)
    uop_template_kind = macro_is_fentry.select(c(1, width=3), uop_template_kind)
    uop_template_kind = macro_is_fexit.select(c(2, width=3), uop_template_kind)
    uop_template_kind = macro_is_fret_ra.select(c(3, width=3), uop_template_kind)
    uop_template_kind = macro_is_fret_stk.select(c(4, width=3), uop_template_kind)
    m.output("uop_template_kind", uop_template_kind)

    m.output("loop_fire", loop_fire)
    m.output("loop_done", loop_done)
    m.output("loop_reg_next", loop_reg_next)
    m.output("loop_i_next", loop_i_next)


build_code_template_unit.__pycircuit_name__ = "CodeTemplateUnit"
