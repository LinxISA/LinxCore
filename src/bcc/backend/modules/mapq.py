from __future__ import annotations

from pycircuit import Circuit, module

from ..helpers import onehot_from_tag


def _or_tree(values):
    level = list(values)
    while len(level) > 1:
        next_level = []
        for index in range(0, len(level), 2):
            if index + 1 < len(level):
                next_level.append(level[index] | level[index + 1])
            else:
                next_level.append(level[index])
        level = next_level
    return level[0]


def _pack_lsb_first(m: Circuit, values):
    packed = values[0]
    for index in range(1, len(values)):
        packed = m.concat(values[index], packed)
    return packed


@module(name="LinxCoreScalarMapQ")
def build_scalar_mapq(
    m: Circuit,
    *,
    depth: int = 32,
    arch_tag_width: int = 5,
    phys_regs: int = 64,
    phys_tag_width: int = 6,
    rid_width: int = 6,
    order_width: int = 64,
) -> None:
    if depth <= 0 or depth & (depth - 1):
        raise ValueError("MapQ depth must be a positive power of two")
    if phys_regs <= 0 or phys_regs & (phys_regs - 1):
        raise ValueError("physical register count must be a positive power of two")

    index_width = (depth - 1).bit_length()
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    rename_valid = m.input("rename_valid", width=1)
    rename_arch = m.input("rename_arch", width=arch_tag_width)
    rename_old_phys = m.input("rename_old_phys", width=phys_tag_width)
    rename_new_phys = m.input("rename_new_phys", width=phys_tag_width)
    rename_rid = m.input("rename_rid", width=rid_width)
    rename_order = m.input("rename_order", width=order_width)

    commit_valid = m.input("commit_valid", width=1)
    commit_rid = m.input("commit_rid", width=rid_width)
    commit_order = m.input("commit_order", width=order_width)
    flush_valid = m.input("flush_valid", width=1)
    flush_order = m.input("flush_order", width=order_width)
    flush_inclusive = m.input("flush_inclusive", width=1)

    commit_match_q = m.out("commit_match_q", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    commit_match_mask_q = m.out("commit_match_mask_q", clk=clk, rst=rst, width=depth, init=c(0, width=depth), en=c(1, width=1))
    commit_arch_q = m.out("commit_arch_q", clk=clk, rst=rst, width=arch_tag_width, init=c(0, width=arch_tag_width), en=c(1, width=1))
    commit_new_phys_q = m.out("commit_new_phys_q", clk=clk, rst=rst, width=phys_tag_width, init=c(0, width=phys_tag_width), en=c(1, width=1))
    flush_prune_mask_q = m.out("flush_prune_mask_q", clk=clk, rst=rst, width=depth, init=c(0, width=depth), en=c(1, width=1))
    release_phys_mask_q = m.out("release_phys_mask_q", clk=clk, rst=rst, width=phys_regs, init=c(0, width=phys_regs), en=c(1, width=1))

    valid = []
    arch = []
    old_phys = []
    new_phys = []
    rid = []
    order = []
    for index in range(depth):
        valid.append(m.out(f"valid{index}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1)))
        arch.append(m.out(f"arch{index}", clk=clk, rst=rst, width=arch_tag_width, init=c(0, width=arch_tag_width), en=c(1, width=1)))
        old_phys.append(m.out(f"old_phys{index}", clk=clk, rst=rst, width=phys_tag_width, init=c(0, width=phys_tag_width), en=c(1, width=1)))
        new_phys.append(m.out(f"new_phys{index}", clk=clk, rst=rst, width=phys_tag_width, init=c(0, width=phys_tag_width), en=c(1, width=1)))
        rid.append(m.out(f"rid{index}", clk=clk, rst=rst, width=rid_width, init=c(0, width=rid_width), en=c(1, width=1)))
        order.append(m.out(f"order{index}", clk=clk, rst=rst, width=order_width, init=c(0, width=order_width), en=c(1, width=1)))

    free = [~entry.out() for entry in valid]
    alloc_take = []
    for index in range(depth):
        older_free = c(0, width=1) if index == 0 else _or_tree(free[:index])
        alloc_take.append(free[index] & (~older_free))
    alloc_found = _or_tree(free)
    alloc_index_terms = []
    for index in range(depth):
        alloc_index_terms.append(
            alloc_take[index]._select_internal(c(index, width=index_width), c(0, width=index_width))
        )
    alloc_index = _or_tree(alloc_index_terms)
    rename_fire = rename_valid & alloc_found & (~flush_valid)

    valid_bits = []
    commit_bits = []
    flush_bits = []
    commit_hits = []
    release_terms = []
    commit_arch = c(0, width=arch_tag_width)
    commit_new_phys = c(0, width=phys_tag_width)
    for index in range(depth):
        commit_hit = (
            commit_valid
            & valid[index].out()
            & rid[index].out().__eq__(commit_rid)
            & order[index].out().__eq__(commit_order)
        )
        younger = flush_order.ult(order[index].out())
        same = order[index].out().__eq__(flush_order)
        flush_hit = flush_valid & valid[index].out() & (younger | (flush_inclusive & same))
        valid_bits.append(valid[index].out())
        commit_bits.append(commit_hit)
        flush_bits.append(flush_hit)
        commit_hits.append(commit_hit)
        commit_arch = commit_hit._select_internal(arch[index].out(), commit_arch)
        commit_new_phys = commit_hit._select_internal(new_phys[index].out(), commit_new_phys)
        release_old = onehot_from_tag(m, tag=old_phys[index].out(), width=phys_regs, tag_width=phys_tag_width)
        release_new = onehot_from_tag(m, tag=new_phys[index].out(), width=phys_regs, tag_width=phys_tag_width)
        release_terms.append(commit_hit._select_internal(release_old, c(0, width=phys_regs)))
        release_terms.append(flush_hit._select_internal(release_new, c(0, width=phys_regs)))

        alloc_hit = rename_fire & alloc_index.__eq__(c(index, width=index_width))
        next_valid = valid[index].out()
        next_valid = (commit_hit | flush_hit)._select_internal(c(0, width=1), next_valid)
        next_valid = alloc_hit._select_internal(c(1, width=1), next_valid)
        valid[index].set(next_valid)
        arch[index].set(alloc_hit._select_internal(rename_arch, arch[index].out()))
        old_phys[index].set(alloc_hit._select_internal(rename_old_phys, old_phys[index].out()))
        new_phys[index].set(alloc_hit._select_internal(rename_new_phys, new_phys[index].out()))
        rid[index].set(alloc_hit._select_internal(rename_rid, rid[index].out()))
        order[index].set(alloc_hit._select_internal(rename_order, order[index].out()))

    valid_mask = _pack_lsb_first(m, valid_bits)
    commit_match_mask = _pack_lsb_first(m, commit_bits)
    flush_prune_mask = _pack_lsb_first(m, flush_bits)
    commit_found = _or_tree(commit_hits)
    release_phys_mask = _or_tree(release_terms)

    commit_match_q.set(commit_found)
    commit_match_mask_q.set(commit_match_mask)
    commit_arch_q.set(commit_arch)
    commit_new_phys_q.set(commit_new_phys)
    flush_prune_mask_q.set(flush_prune_mask)
    release_phys_mask_q.set(release_phys_mask)

    m.output("rename_ready", alloc_found & (~flush_valid))
    m.output("rename_fire", rename_fire)
    m.output("alloc_index", alloc_index)
    m.output("valid_mask", valid_mask)
    m.output("commit_match", commit_match_q.out())
    m.output("commit_match_mask", commit_match_mask_q.out())
    m.output("commit_arch", commit_arch_q.out())
    m.output("commit_new_phys", commit_new_phys_q.out())
    m.output("flush_prune_mask", flush_prune_mask_q.out())
    m.output("release_phys_mask", release_phys_mask_q.out())
