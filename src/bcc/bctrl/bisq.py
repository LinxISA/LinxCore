from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBctrlBisq")
def build_janus_bcc_bctrl_bisq(m: Circuit, *, depth: int = 16, idx_w: int = 4) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    enq_valid_bisq = m.input("enq_valid_bisq", width=1)
    enq_kind_bisq = m.input("enq_kind_bisq", width=3)
    enq_payload_bisq = m.input("enq_payload_bisq", width=64)
    enq_tile_bisq = m.input("enq_tile_bisq", width=6)
    enq_rob_bisq = m.input("enq_rob_bisq", width=6)

    deq_ready_bisq = m.input("deq_ready_bisq", width=1)

    c = m.const

    with m.scope("bisq_ctrl"):
        count_bisq = m.out("count_bisq", clk=clk_top, rst=rst_top, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    head_kind_bisq = m.out("head_kind_bisq", clk=clk_top, rst=rst_top, width=3, init=c(0, width=3), en=c(1, width=1))
    head_payload_bisq = m.out("head_payload_bisq", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1))
    head_tile_bisq = m.out("head_tile_bisq", clk=clk_top, rst=rst_top, width=6, init=c(0, width=6), en=c(1, width=1))
    head_rob_bisq = m.out("head_rob_bisq", clk=clk_top, rst=rst_top, width=6, init=c(0, width=6), en=c(1, width=1))

    fire_enq_bisq = enq_valid_bisq & count_bisq.out().ult(c(depth, width=idx_w + 1))
    fire_deq_bisq = deq_ready_bisq & (~(count_bisq.out() == c(0, width=idx_w + 1)))

    count_next_bisq = count_bisq.out()
    count_next_bisq = (fire_enq_bisq & (~fire_deq_bisq))._select_internal(
        count_next_bisq + c(1, width=idx_w + 1), count_next_bisq
    )
    count_next_bisq = ((~fire_enq_bisq) & fire_deq_bisq)._select_internal(
        count_next_bisq - c(1, width=idx_w + 1), count_next_bisq
    )
    count_bisq.set(count_next_bisq)

    head_kind_bisq.set(enq_kind_bisq, when=fire_enq_bisq)
    head_payload_bisq.set(enq_payload_bisq, when=fire_enq_bisq)
    head_tile_bisq.set(enq_tile_bisq, when=fire_enq_bisq)
    head_rob_bisq.set(enq_rob_bisq, when=fire_enq_bisq)

    m.output("bisq_head_valid_bisq", ~(count_bisq.out() == c(0, width=idx_w + 1)))
    m.output("bisq_head_kind_bisq", head_kind_bisq.out())
    m.output("bisq_head_payload_bisq", head_payload_bisq.out())
    m.output("bisq_head_tile_bisq", head_tile_bisq.out())
    m.output("bisq_head_rob_bisq", head_rob_bisq.out())
    m.output("bisq_count_bisq", count_bisq.out())
