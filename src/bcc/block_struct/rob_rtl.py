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

    # Registered/pulsed outputs (stable after posedge for TB sampling).
    retire_fire_r = m.out("retire_fire_r", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    retire_bid_r = m.out("retire_bid_r", clk=clk, rst=rst, width=bid_w, init=c(0, width=bid_w), en=c(1, width=1))
    retire_eob_r = m.out("retire_eob_r", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))

    scalar_done_valid_r = m.out("scalar_done_valid_r", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    scalar_done_bid_r = m.out("scalar_done_bid_r", clk=clk, rst=rst, width=bid_w, init=c(0, width=bid_w), en=c(1, width=1))
    scalar_done_trap_valid_r = m.out("scalar_done_trap_valid_r", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    scalar_done_trap_cause_r = m.out(
        "scalar_done_trap_cause_r", clk=clk, rst=rst, width=trap_cause_w, init=c(0, width=trap_cause_w), en=c(1, width=1)
    )

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

    head_valid = c(0, width=1)
    head_done = c(0, width=1)
    head_eob = c(0, width=1)
    head_bid = c(0, width=bid_w)
    head_trap_valid = c(0, width=1)
    head_trap_cause = c(0, width=trap_cause_w)

    for i in range(int(depth)):
        idx_hit = head.out() == c(i, width=rid_w)
        head_valid = valid[i].out() if idx_hit else head_valid
        head_done = done[i].out() if idx_hit else head_done
        head_eob = eob[i].out() if idx_hit else head_eob
        head_bid = bid[i].out() if idx_hit else head_bid
        head_trap_valid = trap_valid[i].out() if idx_hit else head_trap_valid
        head_trap_cause = trap_cause[i].out() if idx_hit else head_trap_cause

    retire_fire = retire_ready & head_valid & head_done
    scalar_done_fire = retire_fire & head_eob

    # Capture event outputs before we advance head/clear slots.
    retire_fire_r.set(retire_fire)
    retire_bid_r.set(head_bid if retire_fire else c(0, width=bid_w))
    retire_eob_r.set(head_eob if retire_fire else c(0, width=1))

    scalar_done_valid_r.set(scalar_done_fire)
    scalar_done_bid_r.set(head_bid if scalar_done_fire else c(0, width=bid_w))
    scalar_done_trap_valid_r.set(head_trap_valid if scalar_done_fire else c(0, width=1))
    scalar_done_trap_cause_r.set(head_trap_cause if scalar_done_fire else c(0, width=trap_cause_w))

    for i in range(int(depth)):
        idx = c(i, width=rid_w)
        hit_alloc = alloc_idx == idx
        hit_wb = wb_rid == idx
        hit_retire = retire_fire & (head.out() == idx)

        v_next = valid[i].out()
        d_next = done[i].out()
        eob_next = eob[i].out()
        bid_next = bid[i].out()
        tv_next = trap_valid[i].out()
        tc_next = trap_cause[i].out()

        # Retire clears the slot (highest priority).
        v_next = c(0, width=1) if hit_retire else v_next
        d_next = c(0, width=1) if hit_retire else d_next
        eob_next = c(0, width=1) if hit_retire else eob_next
        bid_next = c(0, width=bid_w) if hit_retire else bid_next
        tv_next = c(0, width=1) if hit_retire else tv_next
        tc_next = c(0, width=trap_cause_w) if hit_retire else tc_next

        # Allocation sets a fresh entry (priority below retire).
        v_next = c(1, width=1) if (alloc_valid & hit_alloc) else v_next
        d_next = c(0, width=1) if (alloc_valid & hit_alloc) else d_next
        eob_next = alloc_eob if (alloc_valid & hit_alloc) else eob_next
        bid_next = alloc_bid if (alloc_valid & hit_alloc) else bid_next
        tv_next = c(0, width=1) if (alloc_valid & hit_alloc) else tv_next
        tc_next = c(0, width=trap_cause_w) if (alloc_valid & hit_alloc) else tc_next

        # Writeback marks done and captures trap info (only for existing entry).
        wb_fire = wb_valid & hit_wb & (~(alloc_valid & hit_alloc)) & (~hit_retire)
        d_next = c(1, width=1) if wb_fire else d_next
        tv_next = wb_trap_valid if wb_fire else tv_next
        tc_next = wb_trap_cause if wb_fire else tc_next

        valid[i].set(v_next)
        done[i].set(d_next)
        eob[i].set(eob_next)
        bid[i].set(bid_next)
        trap_valid[i].set(tv_next)
        trap_cause[i].set(tc_next)

    head_next = (head.out() + c(1, width=rid_w)) if retire_fire else head.out()
    tail_next = (tail.out() + c(1, width=rid_w)) if alloc_valid else tail.out()

    head.set(head_next)
    tail.set(tail_next)

    m.output("retire_fire", retire_fire_r.out())
    m.output("retire_bid", retire_bid_r.out())
    m.output("retire_eob", retire_eob_r.out())

    m.output("scalar_done_valid", scalar_done_valid_r.out())
    m.output("scalar_done_bid", scalar_done_bid_r.out())
    m.output("scalar_done_trap_valid", scalar_done_trap_valid_r.out())
    m.output("scalar_done_trap_cause", scalar_done_trap_cause_r.out())
