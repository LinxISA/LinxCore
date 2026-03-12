from __future__ import annotations

from pycircuit import Circuit, module

from .helpers import mux_by_uindex


@module(name="LinxCorePrf")
def build_prf(
    m: Circuit,
    *,
    pregs: int = 64,
    read_ports: int = 1,
    write_ports: int = 1,
    init_sp_tag: int = 1,
    init_ra_tag: int = 10,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    boot_sp = m.input("boot_sp", width=64)
    boot_ra = m.input("boot_ra", width=64)

    c = m.const

    if pregs <= 0:
        raise ValueError("pregs must be > 0")
    if read_ports <= 0:
        raise ValueError("read_ports must be > 0")
    if write_ports <= 0:
        raise ValueError("write_ports must be > 0")

    ptag_w = (pregs - 1).bit_length()

    raddrs = [m.input(f"raddr{i}", width=ptag_w) for i in range(read_ports)]
    wens = [m.input(f"wen{i}", width=1) for i in range(write_ports)]
    waddrs = [m.input(f"waddr{i}", width=ptag_w) for i in range(write_ports)]
    wdatas = [m.input(f"wdata{i}", width=64) for i in range(write_ports)]

    preg_regs = []
    with m.scope("prf"):
        for i in range(pregs):
            init = c(0, width=64)
            if i == init_sp_tag:
                init = boot_sp
            elif i == init_ra_tag:
                init = boot_ra
            preg_regs.append(m.out(f"p{i}", clk=clk, rst=rst, width=64, init=init, en=c(1, width=1)))

    for i in range(pregs):
        we = c(0, width=1)
        wdata = preg_regs[i].out()
        for port in range(write_ports):
            hit = wens[port] & waddrs[port].__eq__(c(i, width=ptag_w))
            we = we | hit
            wdata = hit._select_internal(wdatas[port], wdata)
        preg_regs[i].set(wdata, when=we)

    for port in range(read_ports):
        m.output(f"rdata{port}", mux_by_uindex(m, idx=raddrs[port], items=preg_regs, default=c(0, width=64)))


build_prf.__pycircuit_name__ = "LinxCorePrf"
