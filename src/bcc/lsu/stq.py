from __future__ import annotations

from pycircuit import Circuit, module

from bcc.backend.helpers import mux_by_uindex


@module(name="JanusBccLsuStq")
def build_janus_bcc_lsu_stq(m: Circuit, *, depth: int = 32, idx_w: int = 5) -> None:
    clk_stq = m.clock("clk")
    rst_stq = m.reset("rst")

    store_valid_stq = m.input("store_valid_stq", width=1)
    store_rob_stq = m.input("store_rob_stq", width=6)
    store_addr_stq = m.input("store_addr_stq", width=64)
    store_data_stq = m.input("store_data_stq", width=64)

    c = m.const

    with m.scope("stq_ctrl"):
        head_stq = m.out("head_stq", clk=clk_stq, rst=rst_stq, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        tail_stq = m.out("tail_stq", clk=clk_stq, rst=rst_stq, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
        count_stq = m.out("count_stq", clk=clk_stq, rst=rst_stq, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    q_valid_stq = []
    q_rob_stq = []
    q_addr_stq = []
    q_data_stq = []
    for i in range(depth):
        q_valid_stq.append(m.out(f"valid{i}_stq", clk=clk_stq, rst=rst_stq, width=1, init=c(0, width=1), en=c(1, width=1)))
        q_rob_stq.append(m.out(f"rob{i}_stq", clk=clk_stq, rst=rst_stq, width=6, init=c(0, width=6), en=c(1, width=1)))
        q_addr_stq.append(m.out(f"addr{i}_stq", clk=clk_stq, rst=rst_stq, width=64, init=c(0, width=64), en=c(1, width=1)))
        q_data_stq.append(m.out(f"data{i}_stq", clk=clk_stq, rst=rst_stq, width=64, init=c(0, width=64), en=c(1, width=1)))

    enq_ready_stq = count_stq.out().ult(c(depth, width=idx_w + 1))
    head_valid_stq = mux_by_uindex(m, idx=head_stq.out(), items=q_valid_stq, default=c(0, width=1))
    head_rob_stq = mux_by_uindex(m, idx=head_stq.out(), items=q_rob_stq, default=c(0, width=6))
    head_addr_stq = mux_by_uindex(m, idx=head_stq.out(), items=q_addr_stq, default=c(0, width=64))
    head_data_stq = mux_by_uindex(m, idx=head_stq.out(), items=q_data_stq, default=c(0, width=64))

    enq_fire_stq = store_valid_stq & enq_ready_stq
    deq_fire_stq = head_valid_stq  # drain 1/cycle in functional-first model

    for i in range(depth):
        idx_stq = c(i, width=idx_w)
        do_enq_stq = enq_fire_stq & tail_stq.out().eq(idx_stq)
        do_deq_stq = deq_fire_stq & head_stq.out().eq(idx_stq)

        v_next_stq = q_valid_stq[i].out()
        v_next_stq = do_deq_stq._select_internal(c(0, width=1), v_next_stq)
        v_next_stq = do_enq_stq._select_internal(c(1, width=1), v_next_stq)
        q_valid_stq[i].set(v_next_stq)

        q_rob_stq[i].set(store_rob_stq, when=do_enq_stq)
        q_addr_stq[i].set(store_addr_stq, when=do_enq_stq)
        q_data_stq[i].set(store_data_stq, when=do_enq_stq)

    head_next_stq = deq_fire_stq._select_internal(head_stq.out() + c(1, width=idx_w), head_stq.out())
    tail_next_stq = enq_fire_stq._select_internal(tail_stq.out() + c(1, width=idx_w), tail_stq.out())

    count_next_stq = count_stq.out()
    count_next_stq = (enq_fire_stq & (~deq_fire_stq))._select_internal(count_next_stq + c(1, width=idx_w + 1), count_next_stq)
    count_next_stq = ((~enq_fire_stq) & deq_fire_stq)._select_internal(count_next_stq - c(1, width=idx_w + 1), count_next_stq)

    head_stq.set(head_next_stq)
    tail_stq.set(tail_next_stq)
    count_stq.set(count_next_stq)

    m.output("stq_enq_fire_stq", enq_fire_stq)
    m.output("stq_deq_fire_stq", deq_fire_stq)
    m.output("stq_head_store_valid_stq", head_valid_stq)
    m.output("stq_head_store_addr_stq", head_addr_stq)
    m.output("stq_head_store_data_stq", head_data_stq)
    m.output("stq_head_store_rob_stq", head_rob_stq)
    m.output("stq_count_stq", count_stq.out())
