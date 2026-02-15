from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreMem2R1W")
def build_mem_2r1w(m: Circuit, *, mem_bytes: int = (1 << 20)) -> None:
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

    c = m.const

    wvalid = d_wvalid | host_wvalid
    waddr = host_wvalid.select(host_waddr, d_waddr)
    wdata = host_wvalid.select(host_wdata, d_wdata)
    wstrb = host_wvalid.select(host_wstrb, d_wstrb)

    if_rdata = m.byte_mem(
        clk,
        rst,
        raddr=if_raddr,
        wvalid=wvalid,
        waddr=waddr,
        wdata=wdata,
        wstrb=wstrb,
        depth=mem_bytes,
        name="imem",
    )

    d_rdata = m.byte_mem(
        clk,
        rst,
        raddr=d_raddr,
        wvalid=wvalid,
        waddr=waddr,
        wdata=wdata,
        wstrb=wstrb,
        depth=mem_bytes,
        name="dmem",
    )

    m.output("if_rdata", if_rdata)
    m.output("d_rdata", d_rdata)
    m.output("waddr_eff", waddr)
    m.output("wvalid_eff", wvalid)
    m.output("wdata_eff", wdata)
    m.output("wstrb_eff", wstrb)


build_mem_2r1w.__pycircuit_name__ = "LinxCoreMem2R1W"
