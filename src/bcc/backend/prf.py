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
    pregs_n = int(pregs)
    read_n = int(read_ports)
    write_n = int(write_ports)
    init_sp = int(init_sp_tag)
    init_ra = int(init_ra_tag)

    if pregs_n <= 0:
        raise ValueError("prf pregs must be > 0")
    if read_n <= 0:
        raise ValueError("prf read_ports must be > 0")
    if write_n <= 0:
        raise ValueError("prf write_ports must be > 0")
    if init_sp < 0 or init_sp >= pregs_n:
        raise ValueError("prf init_sp_tag out of range")
    if init_ra < 0 or init_ra >= pregs_n:
        raise ValueError("prf init_ra_tag out of range")

    ptag_w = max(1, (pregs_n - 1).bit_length())

    clk = m.clock("clk")
    rst = m.reset("rst")

    boot_sp = m.input("boot_sp", width=64)
    boot_ra = m.input("boot_ra", width=64)

    raddr = [m.input(f"raddr{i}", width=ptag_w) for i in range(read_n)]

    wen = [m.input(f"wen{i}", width=1) for i in range(write_n)]
    waddr = [m.input(f"waddr{i}", width=ptag_w) for i in range(write_n)]
    wdata = [m.input(f"wdata{i}", width=64) for i in range(write_n)]

    c = m.const
    one1 = c(1, width=1)
    zero1 = c(0, width=1)
    zero64 = c(0, width=64)

    regs = []
    for i in range(pregs_n):
        init = zero64
        if i == init_sp:
            init = boot_sp
        elif i == init_ra:
            init = boot_ra
        regs.append(m.out(f"p{i}", clk=clk, rst=rst, width=64, init=init, en=one1))

    for i in range(1, pregs_n):
        we_i = zero1
        wdata_i = zero64
        ptag_i = c(i, width=ptag_w)
        for port in range(write_n):
            hit = wen[port] & waddr[port].__eq__(ptag_i)
            we_i = we_i | hit
            wdata_i = hit._select_internal(wdata[port], wdata_i)
        regs[i].set(wdata_i, when=we_i)

    for i in range(read_n):
        data = mux_by_uindex(m, idx=raddr[i], items=regs, default=zero64)
        m.output(f"rdata{i}", data)

