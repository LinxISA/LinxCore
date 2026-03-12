from __future__ import annotations

from pycircuit import Circuit, module


def _pack_bits(m: Circuit, bits: list) -> object:
    packed = bits[-1]
    for i in range(len(bits) - 2, -1, -1):
        packed = m.concat(packed, bits[i])
    return packed


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

    valid_mask = m.out("valid_mask", clk=clk, rst=rst, width=depth, init=c(0, width=depth), en=c(1, width=1))
    done_mask = m.out("done_mask", clk=clk, rst=rst, width=depth, init=c(0, width=depth), en=c(1, width=1))
    eob_mask = m.out("eob_mask", clk=clk, rst=rst, width=depth, init=c(0, width=depth), en=c(1, width=1))
    trap_valid_mask = m.out("trap_valid_mask", clk=clk, rst=rst, width=depth, init=c(0, width=depth), en=c(1, width=1))

    bid = []
    trap_cause = []

    for i in range(int(depth)):
        bid.append(m.out(f"bid{i}", clk=clk, rst=rst, width=bid_w, init=c(0, width=bid_w), en=c(1, width=1)))
        trap_cause.append(m.out(f"trap_cause{i}", clk=clk, rst=rst, width=trap_cause_w, init=c(0, width=trap_cause_w), en=c(1, width=1)))

    alloc_idx = tail.out()

    head_valid = valid_mask.out().lshr(amount=head.out())[0]
    head_done = done_mask.out().lshr(amount=head.out())[0]
    head_eob = eob_mask.out().lshr(amount=head.out())[0]
    head_bid = c(0, width=bid_w)
    head_trap_valid = trap_valid_mask.out().lshr(amount=head.out())[0]
    head_trap_cause = c(0, width=trap_cause_w)

    for i in range(int(depth)):
        idx_hit = head.out() == c(i, width=rid_w)
        head_bid = idx_hit._select_internal(bid[i].out(), head_bid)
        head_trap_cause = idx_hit._select_internal(trap_cause[i].out(), head_trap_cause)

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

    valid_bits_next = []
    done_bits_next = []
    eob_bits_next = []
    trap_valid_bits_next = []

    for i in range(int(depth)):
        idx = c(i, width=rid_w)
        hit_alloc = alloc_idx == idx
        hit_wb = wb_rid == idx
        hit_retire = retire_fire & (head.out() == idx)
        valid_next = valid_mask.out()[i]
        done_next = done_mask.out()[i]
        eob_next = eob_mask.out()[i]
        trap_valid_next = trap_valid_mask.out()[i]

        bid_next = bid[i].out()
        tc_next = trap_cause[i].out()

        # Retire clears the slot (highest priority).
        valid_next = hit_retire._select_internal(c(0, width=1), valid_next)
        done_next = hit_retire._select_internal(c(0, width=1), done_next)
        eob_next = hit_retire._select_internal(c(0, width=1), eob_next)
        trap_valid_next = hit_retire._select_internal(c(0, width=1), trap_valid_next)
        bid_next = hit_retire._select_internal(c(0, width=bid_w), bid_next)
        tc_next = hit_retire._select_internal(c(0, width=trap_cause_w), tc_next)

        # Allocation sets a fresh entry (priority below retire).
        alloc_fire = alloc_valid & hit_alloc
        valid_next = alloc_fire._select_internal(c(1, width=1), valid_next)
        done_next = alloc_fire._select_internal(c(0, width=1), done_next)
        eob_next = alloc_fire._select_internal(alloc_eob, eob_next)
        bid_next = alloc_fire._select_internal(alloc_bid, bid_next)
        trap_valid_next = alloc_fire._select_internal(c(0, width=1), trap_valid_next)
        tc_next = alloc_fire._select_internal(c(0, width=trap_cause_w), tc_next)

        # Writeback marks done and captures trap info (only for existing entry).
        wb_fire = wb_valid & hit_wb & (~alloc_fire) & (~hit_retire)
        done_next = wb_fire._select_internal(c(1, width=1), done_next)
        trap_valid_next = wb_fire._select_internal(wb_trap_valid, trap_valid_next)
        tc_next = wb_fire._select_internal(wb_trap_cause, tc_next)

        bid[i].set(bid_next)
        trap_cause[i].set(tc_next)
        valid_bits_next.append(valid_next)
        done_bits_next.append(done_next)
        eob_bits_next.append(eob_next)
        trap_valid_bits_next.append(trap_valid_next)

    valid_mask.set(_pack_bits(m, valid_bits_next))
    done_mask.set(_pack_bits(m, done_bits_next))
    eob_mask.set(_pack_bits(m, eob_bits_next))
    trap_valid_mask.set(_pack_bits(m, trap_valid_bits_next))

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
