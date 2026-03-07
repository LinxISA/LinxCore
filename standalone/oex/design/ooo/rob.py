from __future__ import annotations

from pycircuit import Circuit, const, module

from ..stage_specs import d3_spec, retire_spec, wb_spec
from ..util import mux_by_uindex


@module(name="StandaloneOexRob")
def build_rob(m: Circuit, *, depth: int = 64, idx_w: int = 6) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    alloc_s = d3_spec(m)
    wb_s = wb_spec(m)
    out_s = retire_spec(m)

    alloc = m.inputs(alloc_s, prefix="alloc_")
    wb = m.inputs(wb_s, prefix="wb_")

    flush_i = m.input("flush_i", width=1)

    c = m.const

    head = m.out("head", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    tail = m.out("tail", clk=clk, rst=rst, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    count = m.out("count", clk=clk, rst=rst, width=idx_w + 1, init=c(0, width=idx_w + 1), en=c(1, width=1))

    v = []
    seq = []
    pc = []
    raw = []
    ln = []
    dst_valid = []
    dst_reg = []
    dst_data = []

    for i in range(int(depth)):
        v.append(m.out(f"v{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        seq.append(m.out(f"seq{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        pc.append(m.out(f"pc{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        raw.append(m.out(f"raw{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))
        ln.append(m.out(f"len{i}", clk=clk, rst=rst, width=8, init=c(0, width=8), en=c(1, width=1)))
        dst_valid.append(m.out(f"dstv{i}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        dst_reg.append(m.out(f"dstr{i}", clk=clk, rst=rst, width=8, init=c(0, width=8), en=c(1, width=1)))
        dst_data.append(m.out(f"dstd{i}", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1)))

    full = count.out().__eq__(c(depth, width=idx_w + 1))
    alloc_fire = alloc["valid"].read() & (~full)

    head_v = mux_by_uindex(m, idx=head.out(), items=v, default=c(0, width=1))
    head_seq = mux_by_uindex(m, idx=head.out(), items=seq, default=c(0, width=64))
    head_pc = mux_by_uindex(m, idx=head.out(), items=pc, default=c(0, width=64))
    head_raw = mux_by_uindex(m, idx=head.out(), items=raw, default=c(0, width=64))
    head_len = mux_by_uindex(m, idx=head.out(), items=ln, default=c(0, width=8))
    head_dstv = mux_by_uindex(m, idx=head.out(), items=dst_valid, default=c(0, width=1))
    head_dstr = mux_by_uindex(m, idx=head.out(), items=dst_reg, default=c(0, width=8))
    head_dstd = mux_by_uindex(m, idx=head.out(), items=dst_data, default=c(0, width=64))

    retire_fire = head_v

    for i in range(int(depth)):
        idx = c(i, width=idx_w)
        do_alloc = alloc_fire & tail.out().__eq__(idx)
        do_retire = retire_fire & head.out().__eq__(idx)

        v_next = v[i].out()
        v_next = do_retire._select_internal(c(0, width=1), v_next)
        v_next = do_alloc._select_internal(c(1, width=1), v_next)
        v[i].set(v_next)

        seq[i].set(alloc["seq"], when=do_alloc)
        pc[i].set(alloc["pc"], when=do_alloc)
        raw[i].set(alloc["raw"], when=do_alloc)
        ln[i].set(alloc["len"], when=do_alloc)

        # Debug model: capture wb to entry selected by wb.rob low bits.
        do_wb = wb["valid"].read() & wb["rob"].read()[0:idx_w].__eq__(idx)
        dst_valid[i].set(wb["dst_valid"], when=do_wb)
        dst_reg[i].set(wb["dst_reg"], when=do_wb)
        dst_data[i].set(wb["dst_data"], when=do_wb)

        v[i].set(c(0, width=1), when=flush_i)

    head.set(retire_fire._select_internal(head.out() + c(1, width=idx_w), head.out()))
    tail.set(alloc_fire._select_internal(tail.out() + c(1, width=idx_w), tail.out()))

    count_n = count.out()
    count_n = (alloc_fire & (~retire_fire))._select_internal(count_n + c(1, width=idx_w + 1), count_n)
    count_n = ((~alloc_fire) & retire_fire)._select_internal(count_n - c(1, width=idx_w + 1), count_n)
    count_n = flush_i._select_internal(c(0, width=idx_w + 1), count_n)
    count.set(count_n)

    next_pc = head_pc + head_len._zext(width=64)

    m.outputs(
        out_s,
        {
            "seq": head_seq,
            "pc": head_pc,
            "raw": head_raw,
            "len": head_len,
            "src0_valid": c(0, width=1),
            "src0_reg": c(0, width=8),
            "src0_data": c(0, width=64),
            "src1_valid": c(0, width=1),
            "src1_reg": c(0, width=8),
            "src1_data": c(0, width=64),
            "dst_valid": head_dstv,
            "dst_reg": head_dstr,
            "dst_data": head_dstd,
            "mem_valid": c(0, width=1),
            "mem_is_store": c(0, width=1),
            "mem_addr": c(0, width=64),
            "mem_wdata": c(0, width=64),
            "mem_rdata": c(0, width=64),
            "mem_size": c(0, width=8),
            "trap_valid": c(0, width=1),
            "trap_cause": c(0, width=32),
            "traparg0": c(0, width=64),
            "next_pc": next_pc,
            "valid": retire_fire,
        },
        prefix="out_",
    )

    m.output("rob_head", head.out())
    m.output("rob_tail", tail.out())
    m.output("rob_count", count.out())
