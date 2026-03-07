from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccBlockStructRob")
def build_janus_bcc_block_struct_rob(
    m: Circuit,
    *,
    depth: int = 64,
    rid_w: int = 6,
    bid_w: int = 64,
    trap_cause_w: int = 32,
) -> None:
    """Minimal block-aware ROB retire helper (signal-level).

    Exposes a pulse when retiring an EOB uop:
      - scalar_done_valid + scalar_done_bid + scalar_done_trap_*

    This is meant to validate the ROB→BROB completion contract with pyc flows.
    """

    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    alloc_valid = m.input("alloc_valid", width=1)
    alloc_bid = m.input("alloc_bid", width=bid_w)
    alloc_eob = m.input("alloc_eob", width=1)

    wb_valid = m.input("wb_valid", width=1)
    wb_rid = m.input("wb_rid", width=rid_w)
    wb_trap_valid = m.input("wb_trap_valid", width=1)
    wb_trap_cause = m.input("wb_trap_cause", width=trap_cause_w)

    retire_ready = m.input("retire_ready", width=1)

    head = m.out("head", clk=clk, rst=rst, width=rid_w, init=c(0, width=rid_w), en=c(1, width=1))
    tail = m.out("tail", clk=clk, rst=rst, width=rid_w, init=c(0, width=rid_w), en=c(1, width=1))
    retire_fire_reg = m.out("retire_fire_reg", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    retire_bid_reg = m.out("retire_bid_reg", clk=clk, rst=rst, width=bid_w, init=c(0, width=bid_w), en=c(1, width=1))
    retire_eob_reg = m.out("retire_eob_reg", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    scalar_done_valid_reg = m.out("scalar_done_valid_reg", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    scalar_done_bid_reg = m.out("scalar_done_bid_reg", clk=clk, rst=rst, width=bid_w, init=c(0, width=bid_w), en=c(1, width=1))
    scalar_done_trap_valid_reg = m.out("scalar_done_trap_valid_reg", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    scalar_done_trap_cause_reg = m.out(
        "scalar_done_trap_cause_reg",
        clk=clk,
        rst=rst,
        width=trap_cause_w,
        init=c(0, width=trap_cause_w),
        en=c(1, width=1),
    )

    valid = []
    done = []
    eob = []
    bid = []
    trap_valid = []
    trap_cause = []
    valid_next = []
    done_next = []
    eob_next = []
    bid_next = []
    trap_valid_next = []
    trap_cause_next = []

    for i in range(int(depth)):
        valid.append(m.out(f"valid{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        done.append(m.out(f"done{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        eob.append(m.out(f"eob{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        bid.append(m.out(f"bid{i}", clk=clk, rst=rst, width=bid_w, init=c(0, width=bid_w), en=c(1, width=1)))
        trap_valid.append(m.out(f"trap_valid{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        trap_cause.append(m.out(f"trap_cause{i}", clk=clk, rst=rst, width=trap_cause_w, init=c(0, width=trap_cause_w), en=c(1, width=1)))

    alloc_idx = tail.out()

    for i in range(int(depth)):
        idx = c(i, width=rid_w)
        hit_alloc = alloc_idx.__eq__(idx)
        hit_wb = wb_rid.__eq__(idx)

        v_next = valid[i].out()
        d_next = done[i].out()

        v_next = (alloc_valid & hit_alloc)._select_internal(c(1, width=1), v_next)
        d_next = (alloc_valid & hit_alloc)._select_internal(c(0, width=1), d_next)
        d_next = (wb_valid & hit_wb)._select_internal(c(1, width=1), d_next)

        valid_next.append(v_next)
        done_next.append(d_next)
        bid_next.append((alloc_valid & hit_alloc)._select_internal(alloc_bid, bid[i].out()))
        eob_next.append((alloc_valid & hit_alloc)._select_internal(alloc_eob, eob[i].out()))
        trap_valid_next.append((wb_valid & hit_wb)._select_internal(wb_trap_valid, trap_valid[i].out()))
        trap_cause_next.append((wb_valid & hit_wb)._select_internal(wb_trap_cause, trap_cause[i].out()))

    head_valid = c(0, width=1)
    head_done = c(0, width=1)
    head_eob = c(0, width=1)
    head_bid = c(0, width=bid_w)
    head_trap_valid = c(0, width=1)
    head_trap_cause = c(0, width=trap_cause_w)

    for i in range(int(depth)):
        idx_hit = head.out().__eq__(c(i, width=rid_w))
        head_valid = idx_hit._select_internal(valid[i].out(), head_valid)
        head_done = idx_hit._select_internal(done[i].out(), head_done)
        head_eob = idx_hit._select_internal(eob[i].out(), head_eob)
        head_bid = idx_hit._select_internal(bid[i].out(), head_bid)
        head_trap_valid = idx_hit._select_internal(trap_valid[i].out(), head_trap_valid)
        head_trap_cause = idx_hit._select_internal(trap_cause[i].out(), head_trap_cause)

    retire_fire = retire_ready & head_valid & head_done
    scalar_done_fire = retire_fire & head_eob

    # Clear retired slot
    for i in range(int(depth)):
        idx_hit = head.out().__eq__(c(i, width=rid_w))
        clear_hit = retire_fire & idx_hit
        valid_next[i] = clear_hit._select_internal(c(0, width=1), valid_next[i])
        done_next[i] = clear_hit._select_internal(c(0, width=1), done_next[i])
        eob_next[i] = clear_hit._select_internal(c(0, width=1), eob_next[i])
        bid_next[i] = clear_hit._select_internal(c(0, width=bid_w), bid_next[i])
        trap_valid_next[i] = clear_hit._select_internal(c(0, width=1), trap_valid_next[i])
        trap_cause_next[i] = clear_hit._select_internal(c(0, width=trap_cause_w), trap_cause_next[i])

    for i in range(int(depth)):
        valid[i].set(valid_next[i])
        done[i].set(done_next[i])
        eob[i].set(eob_next[i])
        bid[i].set(bid_next[i])
        trap_valid[i].set(trap_valid_next[i])
        trap_cause[i].set(trap_cause_next[i])

    head_next = retire_fire._select_internal(head.out() + c(1, width=rid_w), head.out())
    tail_next = alloc_valid._select_internal(tail.out() + c(1, width=rid_w), tail.out())

    head.set(head_next)
    tail.set(tail_next)
    retire_fire_reg.set(retire_fire)
    retire_bid_reg.set(retire_fire._select_internal(head_bid, c(0, width=bid_w)))
    retire_eob_reg.set(retire_fire._select_internal(head_eob, c(0, width=1)))
    scalar_done_valid_reg.set(scalar_done_fire)
    scalar_done_bid_reg.set(scalar_done_fire._select_internal(head_bid, c(0, width=bid_w)))
    scalar_done_trap_valid_reg.set(scalar_done_fire._select_internal(head_trap_valid, c(0, width=1)))
    scalar_done_trap_cause_reg.set(scalar_done_fire._select_internal(head_trap_cause, c(0, width=trap_cause_w)))

    m.output("retire_fire", retire_fire_reg.out())
    m.output("retire_bid", retire_bid_reg.out())
    m.output("retire_eob", retire_eob_reg.out())
    m.output("scalar_done_valid", scalar_done_valid_reg.out())
    m.output("scalar_done_bid", scalar_done_bid_reg.out())
    m.output("scalar_done_trap_valid", scalar_done_trap_valid_reg.out())
    m.output("scalar_done_trap_cause", scalar_done_trap_cause_reg.out())
