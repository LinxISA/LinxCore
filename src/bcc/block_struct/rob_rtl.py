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

    valid = []
    done = []
    eob = []
    bid = []
    trap_valid = []
    trap_cause = []

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
        hit_alloc = alloc_idx.eq(idx)
        hit_wb = wb_rid.eq(idx)

        v_next = valid[i].out()
        d_next = done[i].out()

        v_next = (alloc_valid & hit_alloc).select(c(1, width=1), v_next)
        d_next = (alloc_valid & hit_alloc).select(c(0, width=1), d_next)
        d_next = (wb_valid & hit_wb).select(c(1, width=1), d_next)

        valid[i].set(v_next)
        done[i].set(d_next)

        bid[i].set(alloc_bid, when=alloc_valid & hit_alloc)
        eob[i].set(alloc_eob, when=alloc_valid & hit_alloc)

        trap_valid[i].set(wb_trap_valid, when=wb_valid & hit_wb)
        trap_cause[i].set(wb_trap_cause, when=wb_valid & hit_wb)

    head_valid = c(0, width=1)
    head_done = c(0, width=1)
    head_eob = c(0, width=1)
    head_bid = c(0, width=bid_w)
    head_trap_valid = c(0, width=1)
    head_trap_cause = c(0, width=trap_cause_w)

    for i in range(int(depth)):
        idx_hit = head.out().eq(c(i, width=rid_w))
        head_valid = idx_hit.select(valid[i].out(), head_valid)
        head_done = idx_hit.select(done[i].out(), head_done)
        head_eob = idx_hit.select(eob[i].out(), head_eob)
        head_bid = idx_hit.select(bid[i].out(), head_bid)
        head_trap_valid = idx_hit.select(trap_valid[i].out(), head_trap_valid)
        head_trap_cause = idx_hit.select(trap_cause[i].out(), head_trap_cause)

    retire_fire = retire_ready & head_valid & head_done
    scalar_done_fire = retire_fire & head_eob

    # Clear retired slot
    for i in range(int(depth)):
        idx_hit = head.out().eq(c(i, width=rid_w))
        valid[i].set(c(0, width=1), when=retire_fire & idx_hit)
        done[i].set(c(0, width=1), when=retire_fire & idx_hit)
        eob[i].set(c(0, width=1), when=retire_fire & idx_hit)
        trap_valid[i].set(c(0, width=1), when=retire_fire & idx_hit)
        trap_cause[i].set(c(0, width=trap_cause_w), when=retire_fire & idx_hit)

    head_next = retire_fire.select(head.out() + c(1, width=rid_w), head.out())
    tail_next = alloc_valid.select(tail.out() + c(1, width=rid_w), tail.out())

    head.set(head_next)
    tail.set(tail_next)

    m.output("retire_fire", retire_fire)
    m.output("retire_bid", head_bid)
    m.output("retire_eob", head_eob)

    m.output("scalar_done_valid", scalar_done_fire)
    m.output("scalar_done_bid", head_bid)
    m.output("scalar_done_trap_valid", head_trap_valid)
    m.output("scalar_done_trap_cause", head_trap_cause)
