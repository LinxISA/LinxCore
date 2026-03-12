from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreIngressDecode")
def build_ingress_decode(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    in_f4_valid = m.input("in_f4_valid", width=1)
    in_f4_pc = m.input("in_f4_pc", width=64)
    in_f4_window = m.input("in_f4_window", width=64)
    in_f4_checkpoint = m.input("in_f4_checkpoint", width=6)
    in_f4_pkt_uid = m.input("in_f4_pkt_uid", width=64)
    ready_i = m.input("ready_i", width=1)

    c = m.const
    valid_q = m.out("valid_q", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    pc_q = m.out("pc_q", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    window_q = m.out("window_q", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
    checkpoint_q = m.out("checkpoint_q", clk=clk, rst=rst, width=6, init=c(0, width=6), en=c(1, width=1))
    pkt_uid_q = m.out("pkt_uid_q", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))

    accept = ready_i & in_f4_valid
    valid_q.set(accept)
    pc_q.set(in_f4_pc, when=accept)
    window_q.set(in_f4_window, when=accept)
    checkpoint_q.set(in_f4_checkpoint, when=accept)
    pkt_uid_q.set(in_f4_pkt_uid, when=accept)

    m.output("out_f4_valid", valid_q.out())
    m.output("out_f4_pc", pc_q.out())
    m.output("out_f4_window", window_q.out())
    m.output("out_f4_checkpoint", checkpoint_q.out())
    m.output("out_f4_pkt_uid", pkt_uid_q.out())


build_ingress_decode.__pycircuit_name__ = "LinxCoreIngressDecode"
