from __future__ import annotations

from pycircuit import Circuit, function, module


@function
def _map_addr(m: Circuit, *, addr, stack_base, stack_mask, stack_off, low_mask):
    is_stack = ~addr.ult(stack_base)
    stack_addr = ((addr - stack_base) & stack_mask) + stack_off
    low_addr = addr & low_mask
    return is_stack._select_internal(stack_addr, low_addr)


@module(name="LinxCoreMem2R1W")
def build_mem2r1w(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
    c = m.const
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
    waddr = host_wvalid._select_internal(host_waddr, d_waddr)
    wdata = host_wvalid._select_internal(host_wdata, d_wdata)
    wstrb = host_wvalid._select_internal(host_wstrb, d_wstrb)

    # Bounded memory mapping:
    # - low addresses map directly into the low region,
    # - high stack addresses map into a dedicated upper window to avoid
    #   destructive aliasing against code/data in small bring-up memories.
    low_mask = c(mem_bytes - 1, width=64)
    stack_window = max(1, mem_bytes // 2)
    stack_offset = mem_bytes - stack_window
    stack_base = c(0x0000000007FE0000, width=64)
    stack_mask = c(stack_window - 1, width=64)
    stack_off = c(stack_offset, width=64)

    if_raddr_eff = _map_addr(
        m=m,
        addr=if_raddr,
        stack_base=stack_base,
        stack_mask=stack_mask,
        stack_off=stack_off,
        low_mask=low_mask,
    )
    d_raddr_eff = _map_addr(
        m=m,
        addr=d_raddr,
        stack_base=stack_base,
        stack_mask=stack_mask,
        stack_off=stack_off,
        low_mask=low_mask,
    )
    waddr_eff = _map_addr(
        m=m,
        addr=waddr,
        stack_base=stack_base,
        stack_mask=stack_mask,
        stack_off=stack_off,
        low_mask=low_mask,
    )

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


build_mem2r1w.__pycircuit_name__ = "LinxCoreMem2R1W"
