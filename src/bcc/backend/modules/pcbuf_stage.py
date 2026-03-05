from __future__ import annotations

from pycircuit import Circuit, module, u

from common.isa import BK_FALL


@module(name="LinxCorePcBufStage")
def build_pcbuf_stage(
    m: Circuit,
    *,
    depth: int = 64,
    idx_w: int = 6,
    dispatch_w: int = 4,
) -> None:
    if depth <= 0:
        raise ValueError("depth must be > 0")
    if idx_w <= 0:
        raise ValueError("idx_w must be > 0")
    if dispatch_w <= 0:
        raise ValueError("dispatch_w must be > 0")

    clk = m.clock("clk")
    rst = m.reset("rst")

    lookup_pc_i = m.input("lookup_pc_i", width=64)

    wr_valid = []
    wr_pc = []
    wr_kind = []
    wr_target = []
    wr_pred_take = []
    wr_is_bstart = []
    for slot in range(int(dispatch_w)):
        wr_valid.append(m.input(f"wr_valid{slot}", width=1))
        wr_pc.append(m.input(f"wr_pc{slot}", width=64))
        wr_kind.append(m.input(f"wr_kind{slot}", width=3))
        wr_target.append(m.input(f"wr_target{slot}", width=64))
        wr_pred_take.append(m.input(f"wr_pred_take{slot}", width=1))
        wr_is_bstart.append(m.input(f"wr_is_bstart{slot}", width=1))

    tail = m.out("tail", clk=clk, rst=rst, width=idx_w, init=0, en=1)

    v = []
    pc = []
    kind = []
    target = []
    pred_take = []
    is_bstart = []
    for i in range(int(depth)):
        v.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=0, en=1))
        pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=0, en=1))
        kind.append(m.out(f"k{i}", clk=clk, rst=rst, width=3, init=int(BK_FALL), en=1))
        target.append(m.out(f"t{i}", clk=clk, rst=rst, width=64, init=0, en=1))
        pred_take.append(m.out(f"p{i}", clk=clk, rst=rst, width=1, init=0, en=1))
        is_bstart.append(m.out(f"b{i}", clk=clk, rst=rst, width=1, init=0, en=1))

    # Allocate write indices in slot order.
    wr_idx = []
    tail_tmp = tail.out()
    for slot in range(int(dispatch_w)):
        wr_idx.append(tail_tmp)
        tail_tmp = (tail_tmp + u(idx_w, 1)) if wr_valid[slot] else tail_tmp
    tail.set(tail_tmp)

    # Update entries (multi-write, no free). Writes are assumed unique per cycle.
    for i in range(int(depth)):
        idx = u(idx_w, i)
        v_next = v[i].out()
        pc_next = pc[i].out()
        kind_next = kind[i].out()
        tgt_next = target[i].out()
        pred_next = pred_take[i].out()
        isb_next = is_bstart[i].out()
        for slot in range(int(dispatch_w)):
            hit = wr_valid[slot] & (wr_idx[slot] == idx)
            v_next = u(1, 1) if hit else v_next
            pc_next = wr_pc[slot] if hit else pc_next
            kind_next = wr_kind[slot] if hit else kind_next
            tgt_next = wr_target[slot] if hit else tgt_next
            pred_next = wr_pred_take[slot] if hit else pred_next
            isb_next = wr_is_bstart[slot] if hit else isb_next
        v[i].set(v_next)
        pc[i].set(pc_next)
        kind[i].set(kind_next)
        target[i].set(tgt_next)
        pred_take[i].set(pred_next)
        is_bstart[i].set(isb_next)

    # Lookup: signal whether any stored BSTART metadata matches `lookup_pc_i`.
    lookup_hit = u(1, 0)
    lookup_is_bstart = u(1, 0)
    for i in range(int(depth)):
        pc_hit = v[i].out() & (pc[i].out() == lookup_pc_i)
        lookup_hit = u(1, 1) if pc_hit else lookup_hit
        lookup_is_bstart = u(1, 1) if (pc_hit & is_bstart[i].out()) else lookup_is_bstart

    m.output("lookup_hit", lookup_hit)
    m.output("lookup_is_bstart", lookup_is_bstart)
    m.output("tail_o", tail.out())

