from __future__ import annotations

from pycircuit import Circuit, module

from common.module_specs import backend_block_if_spec


@module(name="LinxCoreBlockFabric")
def build_block_fabric(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    blk_spec = backend_block_if_spec(m)
    blk = m.inputs(blk_spec, prefix="blk_")
    cmd_valid_i = m.input("cmd_valid_i", width=1)
    cmd_bid_i = m.input("cmd_bid_i", width=64)
    c = m.const

    active = m.out("active", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    next_active = blk["brob_active_allocated"].read()._select_internal(c(1, width=1), active.out())
    next_active = blk["brob_active_retired"].read()._select_internal(c(0, width=1), next_active)
    active.set(next_active)

    cmd_tag = cmd_bid_i._trunc(width=8)
    cmd_bid = cmd_bid_i
    cmd_ready = blk["bisq_enq_ready"].read()
    cmd_accept = cmd_valid_i & cmd_ready
    m.output("active_o", active.out())
    m.output("cmd_tag_o", cmd_tag)
    m.output("cmd_bid_o", cmd_bid)
    m.output("cmd_ready_o", cmd_ready)
    m.output("cmd_accept_o", cmd_accept)
