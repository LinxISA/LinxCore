from __future__ import annotations

from pycircuit import Circuit, module

from common.decode_f4 import decode_f4_bundle


@module(name="LinxCoreIFetch")
def build_ifetch(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    boot_pc = m.input("boot_pc", width=64)

    imem_rdata = m.input("imem_rdata", width=64)

    stall_i = m.input("stall_i", width=1)
    redirect_valid = m.input("redirect_valid", width=1)
    redirect_pc = m.input("redirect_pc", width=64)

    pred_valid = m.input("pred_valid", width=1)
    pred_taken = m.input("pred_taken", width=1)
    pred_target = m.input("pred_target", width=64)

    c = m.const

    with m.scope("ifetch"):
        fpc = m.out("fpc", clk=clk, rst=rst, width=64, init=boot_pc, en=c(1, width=1))

    fetch_bundle = decode_f4_bundle(m, imem_rdata, name="fetch_bundle")
    fetch_advance4 = fetch_bundle.total_len_bytes
    fetch_advance4 = fetch_advance4.__eq__(c(0, width=4))._select_internal(c(2, width=4), fetch_advance4)
    fetch_advance64 = fetch_advance4._zext(width=64)

    fetch_valid = (~stall_i) & (~redirect_valid)
    seq_next_pc = fpc.out() + fetch_advance64
    pred_next_pc = (pred_valid & pred_taken)._select_internal(pred_target, seq_next_pc)

    fpc_next = fpc.out()
    fpc_next = redirect_valid._select_internal(redirect_pc, fpc_next)
    fpc_next = fetch_valid._select_internal(pred_next_pc, fpc_next)
    fpc.set(fpc_next)

    m.output("imem_raddr", redirect_valid._select_internal(redirect_pc, fpc.out()))
    m.output("fetch_valid", fetch_valid)
    m.output("fetch_pc", fpc.out())
    m.output("fetch_window", imem_rdata)
    m.output("fetch_next_pc", pred_next_pc)
    m.output("fpc_dbg", fpc.out())


build_ifetch.__pycircuit_name__ = "LinxCoreIFetch"
