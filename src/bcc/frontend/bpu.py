from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreBpuLite")
def build_bpu_lite(m: Circuit, *, tag_bits: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    req_valid = m.input("req_valid", width=1)
    req_pc = m.input("req_pc", width=64)

    update_valid = m.input("update_valid", width=1)
    update_pc = m.input("update_pc", width=64)
    update_taken = m.input("update_taken", width=1)
    update_target = m.input("update_target", width=64)

    c = m.const

    if tag_bits <= 1 or tag_bits > 16:
        raise ValueError("tag_bits must be in [2,16]")

    with m.scope("pred"):
        last_valid = m.out("last_valid", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
        last_tag = m.out("last_tag", clk=clk, rst=rst, width=tag_bits, init=c(0, width=tag_bits), en=c(1, width=1))
        last_tgt = m.out("last_tgt", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))
        ctr = m.out("ctr", clk=clk, rst=rst, width=2, init=c(1, width=2), en=c(1, width=1))

    req_tag = req_pc[2 : 2 + tag_bits]
    upd_tag = update_pc[2 : 2 + tag_bits]

    tag_hit = last_valid.out() & req_tag.eq(last_tag.out())
    taken_conf = ctr.out()[1]

    pred_taken = req_valid & tag_hit & taken_conf
    pred_target = pred_taken.select(last_tgt.out(), req_pc + c(8, width=64))

    ctr_up = ctr.out()
    ctr_up = (update_taken & ctr.out().ult(c(3, width=2))).select(ctr.out() + c(1, width=2), ctr_up)
    ctr_up = ((~update_taken) & ctr.out().ugt(c(0, width=2))).select(ctr.out() - c(1, width=2), ctr_up)

    last_valid.set(update_valid.select(c(1, width=1), last_valid.out()))
    last_tag.set(upd_tag, when=update_valid)
    last_tgt.set(update_target, when=update_valid)
    ctr.set(ctr_up, when=update_valid)

    m.output("pred_valid", req_valid)
    m.output("pred_taken", pred_taken)
    m.output("pred_target", pred_target)
    m.output("checkpoint_id", req_pc[2:8])


build_bpu_lite.__pycircuit_name__ = "LinxCoreBpuLite"
