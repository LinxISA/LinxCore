from __future__ import annotations

from pycircuit import Circuit, module

from common.isa import OP_BIOR, OP_BLOAD, OP_BSTORE
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


@module(name="LinxCoreBackendCmdBridge")
def build_backend_cmd_bridge(m: Circuit, *, rob_w: int = 6) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    blk_spec = backend_block_if_spec(m)
    blk = m.inputs(blk_spec, prefix="blk_")
    cmd_valid = m.input("cmd_valid", width=1)
    cmd_rob = m.input("cmd_rob", width=rob_w)
    cmd_value = m.input("cmd_value", width=64)
    cmd_op = m.input("cmd_op", width=12)
    cmd_block_bid = m.input("cmd_block_bid", width=64)

    cmd_kind = c(0, width=3)
    cmd_kind = cmd_op.__eq__(c(OP_BIOR, width=12))._select_internal(c(1, width=3), cmd_kind)
    cmd_kind = cmd_op.__eq__(c(OP_BLOAD, width=12))._select_internal(c(2, width=3), cmd_kind)
    cmd_kind = cmd_op.__eq__(c(OP_BSTORE, width=12))._select_internal(c(3, width=3), cmd_kind)
    cmd_payload = cmd_value
    cmd_tile = cmd_payload._trunc(width=6)

    block_fabric = m.instance_auto(
        build_block_fabric,
        name="block_fabric",
        module_name="LinxCoreBlockFabricBackend",
        clk=clk,
        rst=rst,
        blk_bisq_enq_ready=blk["bisq_enq_ready"].read(),
        blk_brob_active_allocated=blk["brob_active_allocated"].read(),
        blk_brob_active_ready=blk["brob_active_ready"].read(),
        blk_brob_active_exception=blk["brob_active_exception"].read(),
        blk_brob_active_retired=blk["brob_active_retired"].read(),
        blk_template_uid=blk["template_uid"].read(),
        cmd_valid_i=cmd_valid,
        cmd_bid_i=cmd_block_bid,
    )

    m.output("block_cmd_valid", cmd_valid)
    m.output("block_cmd_kind", cmd_kind)
    m.output("block_cmd_bid", block_fabric["cmd_bid_o"])
    m.output("block_cmd_payload", cmd_payload)
    m.output("block_cmd_tile", cmd_tile)
    m.output("block_cmd_src_rob", cmd_rob)
