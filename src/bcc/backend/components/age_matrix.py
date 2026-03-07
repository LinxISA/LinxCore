from __future__ import annotations

from pycircuit import Circuit, module


@module(name="LinxCoreAgeMatrix")
def build_age_matrix(m: Circuit, *, depth: int = 16, idx_w: int = 4) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    depth_i = max(1, int(depth))
    idx_w_i = max(1, int(idx_w))

    alloc_fire = m.input("alloc_fire", width=1)
    alloc_idx = m.input("alloc_idx", width=idx_w_i)
    free_fire = m.input("free_fire", width=1)
    free_idx = m.input("free_idx", width=idx_w_i)
    valid_mask = m.input("valid_mask", width=depth_i)
    cand_mask = m.input("cand_mask", width=depth_i)

    older = []
    for i in range(depth_i):
        row = []
        for j in range(depth_i):
            init = c(0, width=1)
            row.append(m.out(f"older_{i}_{j}", clk=clk, rst=rst, width=1, init=init, en=c(1, width=1)))
        older.append(row)

    for i in range(depth_i):
        for j in range(depth_i):
            if i == j:
                older[i][j].set(c(0, width=1))
                continue

            i_hit_alloc = alloc_idx.__eq__(c(i, width=idx_w_i))
            j_hit_alloc = alloc_idx.__eq__(c(j, width=idx_w_i))
            i_hit_free = free_idx.__eq__(c(i, width=idx_w_i))
            j_hit_free = free_idx.__eq__(c(j, width=idx_w_i))

            nxt = older[i][j].out()
            kill = free_fire & (i_hit_free | j_hit_free)
            nxt = kill._select_internal(c(0, width=1), nxt)

            # New entry is always younger than currently valid entries.
            i_valid = valid_mask[i]
            j_valid = valid_mask[j]
            nxt = (alloc_fire & i_hit_alloc & j_valid)._select_internal(c(0, width=1), nxt)
            nxt = (alloc_fire & j_hit_alloc & i_valid)._select_internal(c(1, width=1), nxt)
            older[i][j].set(nxt)

    oldest_valid = c(0, width=1)
    oldest_idx = c(0, width=idx_w_i)
    for i in range(depth_i):
        cand_i = cand_mask[i]
        has_older = c(0, width=1)
        for j in range(depth_i):
            if i == j:
                continue
            has_older = has_older | (cand_mask[j] & older[j][i].out())
        is_oldest = cand_i & (~has_older) & (~oldest_valid)
        oldest_valid = is_oldest._select_internal(c(1, width=1), oldest_valid)
        oldest_idx = is_oldest._select_internal(c(i, width=idx_w_i), oldest_idx)

    m.output("oldest_valid", oldest_valid)
    m.output("oldest_idx", oldest_idx)
