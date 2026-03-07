from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import d2_spec, d3_spec
from .rename_maps import build_rename_maps


@module(name="StandaloneOexOooRen")
def build_ren(m: Circuit, *, rob_w: int = 10, ptag_w: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    in_s = d2_spec(m)
    out_s = d3_spec(m)
    ins = m.inputs(in_s, prefix="in_")

    flush_i = m.input("flush_i", width=1)

    c = m.const

    rob_alloc_ptr = m.out("rob_alloc_ptr", clk=clk, rst=rst, width=rob_w, init=c(0, width=rob_w), en=c(1, width=1))

    renmap = m.instance_auto(
        build_rename_maps,
        name="rename_maps",
        module_name="StandaloneOexRenameMapsInst",
        params={"aregs": 64, "ptag_w": ptag_w},
        clk=clk,
        rst=rst,
        in_valid=ins["valid"],
        src0_atag=ins["src0_atag"],
        src1_atag=ins["src1_atag"],
        dst_atag=ins["dst_atag"],
        commit_valid=c(0, width=1),
        commit_atag=c(0, width=6),
        commit_ptag=c(0, width=ptag_w),
        flush_i=flush_i,
    )

    alloc_fire = ins["valid"].read()
    rob_now = rob_alloc_ptr.out()
    rob_alloc_ptr.set(alloc_fire._select_internal(rob_alloc_ptr.out() + c(1, width=rob_w), rob_alloc_ptr.out()))

    m.outputs(
        out_s,
        {
            "seq": ins["seq"],
            "pc": ins["pc"],
            "raw": ins["raw"],
            "len": ins["len"],
            "op_class": ins["op_class"],
            "src0_ptag": renmap["src0_ptag"],
            "src1_ptag": renmap["src1_ptag"],
            "dst_ptag": renmap["dst_ptag"],
            "rob": rob_now._zext(width=10),
            "iq_class": ins["iq_class"],
            "valid": ins["valid"],
        },
        prefix="out_",
    )
