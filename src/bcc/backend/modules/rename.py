from __future__ import annotations

from pycircuit import Circuit, module

from common.module_specs import backend_frontend_in_spec


@module(name="LinxCoreRename")
def build_rename(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    in_spec = backend_frontend_in_spec(m)
    ins = m.inputs(in_spec, prefix="in_")

    rename_active = m.out("rename_active", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    next_active = ins["f4_valid"].read()._select_internal(c(1, width=1), rename_active.out())
    rename_active.set(next_active)

    out_valid = ins["f4_valid"].read() & rename_active.out()
    out_ready = (~ins["f4_valid"].read())._select_internal(c(1, width=1), rename_active.out())
    out_seen = out_valid | rename_active.out()
    m.output("rename_valid_o", out_valid)
    m.output("rename_ready_o", out_ready)
    m.output("rename_seen_o", out_seen)
    m.output("rename_pc_o", ins["f4_pc"].read())
    m.output("rename_uid_o", ins["f4_pkt_uid"].read())
