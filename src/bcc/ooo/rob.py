from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooRob")
def build_janus_bcc_ooo_rob(m: Circuit, *, depth: int = 64, rob_w: int = 6) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    alloc_valid_rob = m.input("alloc_valid_rob", width=1)
    alloc_pc_rob = m.input("alloc_pc_rob", width=64)
    wb_valid_rob = m.input("wb_valid_rob", width=1)
    wb_idx_rob = m.input("wb_idx_rob", width=rob_w)
    wb_value_rob = m.input("wb_value_rob", width=64)
    commit_ready_rob = m.input("commit_ready_rob", width=1)

    c = m.const

    with m.scope("rob_ctrl"):
        head_rob = m.out("head_rob", clk=clk_top, rst=rst_top, width=rob_w, init=c(0, width=rob_w), en=c(1, width=1))
        tail_rob = m.out("tail_rob", clk=clk_top, rst=rst_top, width=rob_w, init=c(0, width=rob_w), en=c(1, width=1))
        count_rob = m.out("count_rob", clk=clk_top, rst=rst_top, width=rob_w + 1, init=c(0, width=rob_w + 1), en=c(1, width=1))

    valid_rob = []
    done_rob = []
    pc_rob = []
    value_rob = []
    for i in range(depth):
        valid_rob.append(m.out(f"valid{i}_rob", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1)))
        done_rob.append(m.out(f"done{i}_rob", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1)))
        pc_rob.append(m.out(f"pc{i}_rob", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1)))
        value_rob.append(m.out(f"value{i}_rob", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1)))

    alloc_idx_rob = tail_rob.out()
    wb_hit_any_rob = c(0, width=1)

    for i in range(depth):
        idx_hit_alloc_rob = alloc_idx_rob == c(i, width=rob_w)
        idx_hit_wb_rob = wb_idx_rob == c(i, width=rob_w)

        valid_next_rob = valid_rob[i].out()
        done_next_rob = done_rob[i].out()

        valid_next_rob = (alloc_valid_rob & idx_hit_alloc_rob)._select_internal(c(1, width=1), valid_next_rob)
        done_next_rob = (alloc_valid_rob & idx_hit_alloc_rob)._select_internal(c(0, width=1), done_next_rob)
        done_next_rob = (wb_valid_rob & idx_hit_wb_rob)._select_internal(c(1, width=1), done_next_rob)

        valid_rob[i].set(valid_next_rob)
        done_rob[i].set(done_next_rob)
        pc_rob[i].set(alloc_pc_rob, when=alloc_valid_rob & idx_hit_alloc_rob)
        value_rob[i].set(wb_value_rob, when=wb_valid_rob & idx_hit_wb_rob)
        wb_hit_any_rob = wb_hit_any_rob | (wb_valid_rob & idx_hit_wb_rob)

    head_valid_rob = c(0, width=1)
    head_done_rob = c(0, width=1)
    head_pc_rob = c(0, width=64)
    for i in range(depth):
        idx_hit_head_rob = head_rob.out() == c(i, width=rob_w)
        head_valid_rob = idx_hit_head_rob._select_internal(valid_rob[i].out(), head_valid_rob)
        head_done_rob = idx_hit_head_rob._select_internal(done_rob[i].out(), head_done_rob)
        head_pc_rob = idx_hit_head_rob._select_internal(pc_rob[i].out(), head_pc_rob)

    commit_fire_rob = commit_ready_rob & head_valid_rob & head_done_rob
    alloc_fire_rob = alloc_valid_rob

    head_next_rob = commit_fire_rob._select_internal(head_rob.out() + c(1, width=rob_w), head_rob.out())
    tail_next_rob = alloc_fire_rob._select_internal(tail_rob.out() + c(1, width=rob_w), tail_rob.out())
    count_next_rob = count_rob.out()
    count_next_rob = (alloc_fire_rob & (~commit_fire_rob))._select_internal(count_next_rob + c(1, width=rob_w + 1), count_next_rob)
    count_next_rob = ((~alloc_fire_rob) & commit_fire_rob)._select_internal(count_next_rob - c(1, width=rob_w + 1), count_next_rob)

    head_rob.set(head_next_rob)
    tail_rob.set(tail_next_rob)
    count_rob.set(count_next_rob)

    m.output("commit_fire_rob", commit_fire_rob)
    m.output("commit_pc_rob", head_pc_rob)
    m.output("commit_idx_rob", head_rob.out())
    m.output("rob_count_rob", count_rob.out())
    m.output("rob_head_valid_rob", head_valid_rob)
    m.output("rob_head_done_rob", head_done_rob)
    m.output("rob_to_flush_ctrl_stage_redirect_valid_rob", c(0, width=1))
    m.output("rob_to_flush_ctrl_stage_redirect_pc_rob", c(0, width=64))
    m.output("rob_to_flush_ctrl_stage_checkpoint_id_rob", c(0, width=6))
