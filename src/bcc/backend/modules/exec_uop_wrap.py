from __future__ import annotations

from pycircuit import Circuit, module

from common.exec_uop import exec_uop_comb
from common.util import make_consts


@module(name="LinxCoreExecUopFlat")
def build_exec_uop(m: Circuit) -> None:
    out = exec_uop_comb(
        m,
        op=m.input("op_i", width=12),
        pc=m.input("pc_i", width=64),
        imm=m.input("imm_i", width=64),
        srcl_val=m.input("srcl_val_i", width=64),
        srcr_val=m.input("srcr_val_i", width=64),
        srcr_type=m.input("srcr_type_i", width=2),
        shamt=m.input("shamt_i", width=6),
        srcp_val=m.input("srcp_val_i", width=64),
        consts=make_consts(m),
    )

    outputs = (
        ("alu_o", out.alu),
        ("is_load_o", out.is_load),
        ("is_store_o", out.is_store),
        ("size_o", out.size),
        ("addr_o", out.addr),
        ("wdata_o", out.wdata),
    )
    for idx in range(len(outputs)):
        name, wire = outputs[idx]
        m.output(name, wire)
