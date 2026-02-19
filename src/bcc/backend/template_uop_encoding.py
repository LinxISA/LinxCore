from __future__ import annotations

from pycircuit import Circuit

from common.isa import OP_ADDI, OP_LDI, OP_SDI, OP_SETC_TGT, OP_SUBI


def map_template_child_encoding(
    m: Circuit,
    *,
    macro_op,
    macro_insn_raw,
    uop_is_sp_sub,
    uop_is_sp_add,
    uop_is_store,
    uop_is_load,
    uop_is_setc_tgt,
):
    c = m.const

    op = macro_op
    op = uop_is_sp_sub.select(c(OP_SUBI, width=12), op)
    op = uop_is_sp_add.select(c(OP_ADDI, width=12), op)
    op = uop_is_store.select(c(OP_SDI, width=12), op)
    op = uop_is_load.select(c(OP_LDI, width=12), op)
    op = uop_is_setc_tgt.select(c(OP_SETC_TGT, width=12), op)

    # Generated template children use fixed scalar encodings so pipeview and
    # cross-check can classify them as real micro-ops instead of macro markers.
    insn = c(0x00000015, width=64)  # ADDI
    insn = uop_is_sp_sub.select(c(0x00001015, width=64), insn)  # SUBI
    insn = uop_is_sp_add.select(c(0x00000015, width=64), insn)  # ADDI
    insn = uop_is_store.select(c(0x00003059, width=64), insn)  # SDI
    insn = uop_is_load.select(c(0x00003019, width=64), insn)  # LDI
    insn = uop_is_setc_tgt.select(c(0x0000403B, width=64), insn)  # SETC.TGT

    return {
        "op": op,
        "len": c(4, width=3),
        "insn_raw": insn,
    }
