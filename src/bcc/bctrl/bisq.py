from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.helpers import mux_by_uindex


@module(name="JanusBccBctrlBisq")
def build_janus_bcc_bctrl_bisq(m: Circuit, *, depth: int = 16, idx_w: int = 4) -> None:
    clk_bisq = m.clock("clk")
    rst_bisq = m.reset("rst")

    enq_valid_bisq = m.input("enq_valid_bisq", width=1)
    enq_kind_bisq = m.input("enq_kind_bisq", width=3)
    enq_bid_bisq = m.input("enq_bid_bisq", width=64)
    enq_payload_bisq = m.input("enq_payload_bisq", width=64)
    enq_tile_bisq = m.input("enq_tile_bisq", width=6)
    enq_rob_bisq = m.input("enq_rob_bisq", width=6)

    deq_ready_bisq = m.input("deq_ready_bisq", width=1)

    c = m.const

    with m.scope("bisq_ctrl"):
        head_bisq = m.out("head_bisq", clk=clk_bisq, rst=rst_bisq, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        tail_bisq = m.out("tail_bisq", clk=clk_bisq, rst=rst_bisq, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        count_bisq = m.out("count_bisq", clk=clk_bisq, rst=rst_bisq, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    q_valid_bisq = []
    q_kind_bisq = []
    q_bid_bisq = []
    q_payload_bisq = []
    q_tile_bisq = []
    q_rob_bisq = []
    for i in range(depth):
        q_valid_bisq.append(m.out(f"valid{i}_bisq", clk=clk_bisq, rst=rst_bisq, width=1, init=c(0, width=1), en=c(1, width=1)))
        q_kind_bisq.append(m.out(f"kind{i}_bisq", clk=clk_bisq, rst=rst_bisq, width=3, init=c(0, width=3), en=c(1, width=1)))
        q_bid_bisq.append(m.out(f"bid{i}_bisq", clk=clk_bisq, rst=rst_bisq, width=64, init=c(0, width=64), en=c(1, width=1)))
        q_payload_bisq.append(m.out(f"payload{i}_bisq", clk=clk_bisq, rst=rst_bisq, width=64, init=c(0, width=64), en=c(1, width=1)))
        q_tile_bisq.append(m.out(f"tile{i}_bisq", clk=clk_bisq, rst=rst_bisq, width=6, init=c(0, width=6), en=c(1, width=1)))
        q_rob_bisq.append(m.out(f"rob{i}_bisq", clk=clk_bisq, rst=rst_bisq, width=6, init=c(0, width=6), en=c(1, width=1)))

    enq_ready_bisq = count_bisq.out().ult(c(depth, width=idx_w + 1))
    deq_valid_bisq = mux_by_uindex(m, idx=head_bisq.out(), items=q_valid_bisq, default=c(0, width=1))

    head_kind_bisq = mux_by_uindex(m, idx=head_bisq.out(), items=q_kind_bisq, default=c(0, width=3))
    head_bid_bisq = mux_by_uindex(m, idx=head_bisq.out(), items=q_bid_bisq, default=c(0, width=64))
    head_payload_bisq = mux_by_uindex(m, idx=head_bisq.out(), items=q_payload_bisq, default=c(0, width=64))
    head_tile_bisq = mux_by_uindex(m, idx=head_bisq.out(), items=q_tile_bisq, default=c(0, width=6))
    head_rob_bisq = mux_by_uindex(m, idx=head_bisq.out(), items=q_rob_bisq, default=c(0, width=6))

    fire_enq_bisq = enq_valid_bisq & enq_ready_bisq
    fire_deq_bisq = deq_valid_bisq & deq_ready_bisq

    for i in range(depth):
        idx_bisq = c(i, width=idx_w)
        do_enq_bisq = fire_enq_bisq & tail_bisq.out().eq(idx_bisq)
        do_deq_bisq = fire_deq_bisq & head_bisq.out().eq(idx_bisq)

        v_next_bisq = q_valid_bisq[i].out()
        v_next_bisq = do_deq_bisq._select_internal(c(0, width=1), v_next_bisq)
        v_next_bisq = do_enq_bisq._select_internal(c(1, width=1), v_next_bisq)
        q_valid_bisq[i].set(v_next_bisq)

        q_kind_bisq[i].set(enq_kind_bisq, when=do_enq_bisq)
        q_bid_bisq[i].set(enq_bid_bisq, when=do_enq_bisq)
        q_payload_bisq[i].set(enq_payload_bisq, when=do_enq_bisq)
        q_tile_bisq[i].set(enq_tile_bisq, when=do_enq_bisq)
        q_rob_bisq[i].set(enq_rob_bisq, when=do_enq_bisq)

    head_next_bisq = fire_deq_bisq._select_internal(head_bisq.out() + c(1, width=idx_w), head_bisq.out())
    tail_next_bisq = fire_enq_bisq._select_internal(tail_bisq.out() + c(1, width=idx_w), tail_bisq.out())

    count_next_bisq = count_bisq.out()
    count_next_bisq = (fire_enq_bisq & (~fire_deq_bisq))._select_internal(count_next_bisq + c(1, width=idx_w + 1), count_next_bisq)
    count_next_bisq = ((~fire_enq_bisq) & fire_deq_bisq)._select_internal(count_next_bisq - c(1, width=idx_w + 1), count_next_bisq)

    head_bisq.set(head_next_bisq)
    tail_bisq.set(tail_next_bisq)
    count_bisq.set(count_next_bisq)

    m.output("bisq_head_valid_bisq", deq_valid_bisq)
    m.output("bisq_head_kind_bisq", head_kind_bisq)
    m.output("bisq_head_bid_bisq", head_bid_bisq)
    m.output("bisq_head_payload_bisq", head_payload_bisq)
    m.output("bisq_head_tile_bisq", head_tile_bisq)
    m.output("bisq_head_rob_bisq", head_rob_bisq)
    m.output("bisq_count_bisq", count_bisq.out())
    m.output("bisq_enq_ready_bisq", enq_ready_bisq)
    m.output("bisq_deq_fire_bisq", fire_deq_bisq)
