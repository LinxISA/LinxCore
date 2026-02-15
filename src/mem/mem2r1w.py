from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreMem2R1W")
def build_mem2r1w(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    if_raddr = m.input("if_raddr", width=64)
    d_raddr = m.input("d_raddr", width=64)

    d_wvalid = m.input("d_wvalid", width=1)
    d_waddr = m.input("d_waddr", width=64)
    d_wdata = m.input("d_wdata", width=64)
    d_wstrb = m.input("d_wstrb", width=8)

    host_wvalid = m.input("host_wvalid", width=1)
    host_waddr = m.input("host_waddr", width=64)
    host_wdata = m.input("host_wdata", width=64)
    host_wstrb = m.input("host_wstrb", width=8)

    wvalid = d_wvalid | host_wvalid
    waddr = host_wvalid.select(host_waddr, d_waddr)
    wdata = host_wvalid.select(host_wdata, d_wdata)
    wstrb = host_wvalid.select(host_wstrb, d_wstrb)

    # Bounded memory mapping:
    # - low addresses map directly into the low region,
    # - high stack addresses map into a dedicated upper window to avoid
    #   destructive aliasing against code/data in small bring-up memories.
    low_mask = m.const(mem_bytes - 1, width=64)
    stack_window = max(1, mem_bytes // 2)
    stack_offset = mem_bytes - stack_window
    stack_base = m.const(0x0000000007FE0000, width=64)
    stack_mask = m.const(stack_window - 1, width=64)
    stack_off = m.const(stack_offset, width=64)

    def map_addr(addr):
        is_stack = ~addr.ult(stack_base)
        stack_addr = ((addr - stack_base) & stack_mask) + stack_off
        low_addr = addr & low_mask
        return is_stack.select(stack_addr, low_addr)

    if_raddr_eff = map_addr(if_raddr)
    d_raddr_eff = map_addr(d_raddr)
    waddr_eff = map_addr(waddr)

    if_rdata = m.byte_mem(
        clk,
        rst,
        raddr=if_raddr_eff,
        wvalid=wvalid,
        waddr=waddr_eff,
        wdata=wdata,
        wstrb=wstrb,
        depth=mem_bytes,
        name="imem",
    )

    d_rdata = m.byte_mem(
        clk,
        rst,
        raddr=d_raddr_eff,
        wvalid=wvalid,
        waddr=waddr_eff,
        wdata=wdata,
        wstrb=wstrb,
        depth=mem_bytes,
        name="dmem",
    )

    m.output("if_rdata", if_rdata)
    m.output("d_rdata", d_rdata)
    m.output("waddr_eff", waddr_eff)
    m.output("wvalid_eff", wvalid)
    m.output("wdata_eff", wdata)
    m.output("wstrb_eff", wstrb)


# Compatibility alias used by legacy imports.
build_mem_2r1w = build_mem2r1w
build_mem2r1w.__pycircuit_name__ = "LinxCoreMem2R1W"
