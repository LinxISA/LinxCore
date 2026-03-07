from __future__ import annotations

from pycircuit import Circuit, const, module

from ..util import mux_by_uindex


@module(name="StandaloneOexRenameMaps")
def build_rename_maps(m: Circuit, *, aregs: int = 64, ptag_w: int = 8) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")

    in_valid = m.input("in_valid", width=1)
    src0_atag = m.input("src0_atag", width=6)
    src1_atag = m.input("src1_atag", width=6)
    dst_atag = m.input("dst_atag", width=6)

    commit_valid = m.input("commit_valid", width=1)
    commit_atag = m.input("commit_atag", width=6)
    commit_ptag = m.input("commit_ptag", width=ptag_w)

    flush_i = m.input("flush_i", width=1)

    c = m.const

    smap = []
    cmap = []
    for i in range(int(aregs)):
        smap.append(
            m.out(
                f"smap{i}",
                clk=clk,
                rst=rst,
                width=ptag_w,
                init=c(i & ((1 << ptag_w) - 1), width=ptag_w),
                en=c(1, width=1),
            )
        )
        cmap.append(
            m.out(
                f"cmap{i}",
                clk=clk,
                rst=rst,
                width=ptag_w,
                init=c(i & ((1 << ptag_w) - 1), width=ptag_w),
                en=c(1, width=1),
            )
        )

    next_ptag = m.out("next_ptag", clk=clk, rst=rst, width=ptag_w, init=c(32, width=ptag_w), en=c(1, width=1))

    src0_ptag = mux_by_uindex(m, idx=src0_atag, items=smap, default=c(0, width=ptag_w))
    src1_ptag = mux_by_uindex(m, idx=src1_atag, items=smap, default=c(0, width=ptag_w))

    dst_need = in_valid & (~dst_atag.__eq__(c(0, width=6)))
    alloc_ptag = next_ptag.out()
    dst_ptag = dst_need._select_internal(alloc_ptag, c(0, width=ptag_w))

    for i in range(int(aregs)):
        idx = c(i, width=6)
        do_alloc = dst_need & dst_atag.__eq__(idx)
        do_commit = commit_valid & commit_atag.__eq__(idx)

        smap[i].set(dst_ptag, when=do_alloc)
        cmap[i].set(commit_ptag, when=do_commit)
        smap[i].set(cmap[i].out(), when=flush_i)

    next_ptag.set((dst_need)._select_internal(next_ptag.out() + c(1, width=ptag_w), next_ptag.out()))

    m.output("src0_ptag", src0_ptag)
    m.output("src1_ptag", src1_ptag)
    m.output("dst_ptag", dst_ptag)
    m.output("alloc_ok", in_valid)
