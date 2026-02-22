from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.helpers import mux_by_uindex


@module(name="JanusBccLsuLiq")
def build_janus_bcc_lsu_liq(m: Circuit, *, depth: int = 32, idx_w: int = 5) -> None:
    clk_liq = m.clock("clk")
    rst_liq = m.reset("rst")

    load_valid_liq = m.input("load_valid_liq", width=1)
    load_rob_liq = m.input("load_rob_liq", width=6)
    load_addr_liq = m.input("load_addr_liq", width=64)

    c = m.const

    with m.scope("liq_ctrl"):
        head_liq = m.out("head_liq", clk=clk_liq, rst=rst_liq, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        tail_liq = m.out("tail_liq", clk=clk_liq, rst=rst_liq, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        count_liq = m.out("count_liq", clk=clk_liq, rst=rst_liq, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    q_valid_liq = []
    q_rob_liq = []
    q_addr_liq = []
    for i in range(depth):
        q_valid_liq.append(m.out(f"valid{i}_liq", clk=clk_liq, rst=rst_liq, width=1, init=c(0, width=1), en=c(1, width=1)))
        q_rob_liq.append(m.out(f"rob{i}_liq", clk=clk_liq, rst=rst_liq, width=6, init=c(0, width=6), en=c(1, width=1)))
        q_addr_liq.append(m.out(f"addr{i}_liq", clk=clk_liq, rst=rst_liq, width=64, init=c(0, width=64), en=c(1, width=1)))

    enq_ready_liq = count_liq.out().ult(c(depth, width=idx_w + 1))
    head_valid_liq = mux_by_uindex(m, idx=head_liq.out(), items=q_valid_liq, default=c(0, width=1))
    head_rob_liq = mux_by_uindex(m, idx=head_liq.out(), items=q_rob_liq, default=c(0, width=6))
    head_addr_liq = mux_by_uindex(m, idx=head_liq.out(), items=q_addr_liq, default=c(0, width=64))

    enq_fire_liq = load_valid_liq & enq_ready_liq
    deq_fire_liq = head_valid_liq

    for i in range(depth):
        idx_liq = c(i, width=idx_w)
        do_enq_liq = enq_fire_liq & tail_liq.out().eq(idx_liq)
        do_deq_liq = deq_fire_liq & head_liq.out().eq(idx_liq)

        v_next_liq = q_valid_liq[i].out()
        v_next_liq = do_deq_liq._select_internal(c(0, width=1), v_next_liq)
        v_next_liq = do_enq_liq._select_internal(c(1, width=1), v_next_liq)
        q_valid_liq[i].set(v_next_liq)

        q_rob_liq[i].set(load_rob_liq, when=do_enq_liq)
        q_addr_liq[i].set(load_addr_liq, when=do_enq_liq)

    head_next_liq = deq_fire_liq._select_internal(head_liq.out() + c(1, width=idx_w), head_liq.out())
    tail_next_liq = enq_fire_liq._select_internal(tail_liq.out() + c(1, width=idx_w), tail_liq.out())

    count_next_liq = count_liq.out()
    count_next_liq = (enq_fire_liq & (~deq_fire_liq))._select_internal(count_next_liq + c(1, width=idx_w + 1), count_next_liq)
    count_next_liq = ((~enq_fire_liq) & deq_fire_liq)._select_internal(count_next_liq - c(1, width=idx_w + 1), count_next_liq)

    head_liq.set(head_next_liq)
    tail_liq.set(tail_next_liq)
    count_liq.set(count_next_liq)

    m.output("liq_fire_liq", deq_fire_liq)
    m.output("liq_rob_liq", head_rob_liq)
    m.output("liq_addr_liq", head_addr_liq)
    m.output("liq_count_liq", count_liq.out())
    m.output("liq_enq_fire_liq", enq_fire_liq)
