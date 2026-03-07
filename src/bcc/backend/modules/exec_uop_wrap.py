from __future__ import annotations

from pycircuit import Circuit, module

from common.exec_uop import exec_uop_comb
from common.util import make_consts


@module(name="LinxCoreExecUop")
def build_exec_uop(m: Circuit) -> None:
    op = m.input("op_i", width=12)
    pc = m.input("pc_i", width=64)
    imm = m.input("imm_i", width=64)
    srcl_val = m.input("srcl_val_i", width=64)
    srcr_val = m.input("srcr_val_i", width=64)
    srcr_type = m.input("srcr_type_i", width=2)
    shamt = m.input("shamt_i", width=6)
    srcp_val = m.input("srcp_val_i", width=64)

    consts = make_consts(m)
    ex = exec_uop_comb(
        m,
        op=op,
        pc=pc,
        imm=imm,
        srcl_val=srcl_val,
        srcr_val=srcr_val,
        srcr_type=srcr_type,
        shamt=shamt,
        srcp_val=srcp_val,
        consts=consts,
    )

    m.output("alu_o", ex.alu)
    m.output("is_load_o", ex.is_load)
    m.output("is_store_o", ex.is_store)
    m.output("size_o", ex.size)
    m.output("addr_o", ex.addr)
    m.output("wdata_o", ex.wdata)
