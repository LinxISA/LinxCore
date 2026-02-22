from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccOooPcBuffer")
def build_janus_bcc_ooo_pc_buffer(m: Circuit, *, depth: int = 64, idx_w: int = 6) -> None:
    clk_top = m.clock("clk")
    rst_top = m.reset("rst")

    wr_valid_pcb = m.input("wr_valid_pcb", width=1)
    wr_idx_pcb = m.input("wr_idx_pcb", width=idx_w)
    wr_pc_pcb = m.input("wr_pc_pcb", width=64)
    rd_idx_pcb = m.input("rd_idx_pcb", width=idx_w)
    f3_to_pcb_stage_bstart_valid_f3 = m.input("f3_to_pcb_stage_bstart_valid_f3", width=1)
    f3_to_pcb_stage_bstart_pc_f3 = m.input("f3_to_pcb_stage_bstart_pc_f3", width=64)
    f3_to_pcb_stage_bstart_kind_f3 = m.input("f3_to_pcb_stage_bstart_kind_f3", width=3)
    f3_to_pcb_stage_bstart_target_f3 = m.input("f3_to_pcb_stage_bstart_target_f3", width=64)
    f3_to_pcb_stage_pred_take_f3 = m.input("f3_to_pcb_stage_pred_take_f3", width=1)
    lookup_pc_pcb = m.input("lookup_pc_pcb", width=64)

    c = m.const

    tail_pcb = m.out("tail_pcb", clk=clk_top, rst=rst_top, width=idx_w, init=c(0, width=idx_w), en=c(1, width=1))
    tail_next_pcb = f3_to_pcb_stage_bstart_valid_f3._select_internal(tail_pcb.out() + c(1, width=idx_w), tail_pcb.out())
    tail_pcb.set(tail_next_pcb)

    pcs_pcb = []
    valid_pcb = []
    kind_pcb = []
    target_pcb = []
    pred_take_pcb = []
    is_bstart_pcb = []
    for i in range(depth):
        valid_pcb.append(m.out(f"valid{i}_pcb", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1)))
        pcs_pcb.append(m.out(f"pc{i}_pcb", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1)))
        kind_pcb.append(m.out(f"kind{i}_pcb", clk=clk_top, rst=rst_top, width=3, init=c(0, width=3), en=c(1, width=1)))
        target_pcb.append(m.out(f"target{i}_pcb", clk=clk_top, rst=rst_top, width=64, init=c(0, width=64), en=c(1, width=1)))
        pred_take_pcb.append(m.out(f"pred{i}_pcb", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1)))
        is_bstart_pcb.append(m.out(f"isb{i}_pcb", clk=clk_top, rst=rst_top, width=1, init=c(0, width=1), en=c(1, width=1)))

    rd_pc_pcb = c(0, width=64)
    lookup_hit_pcb = c(0, width=1)
    lookup_known_pcb = c(0, width=1)
    lookup_kind_pcb = c(0, width=3)
    lookup_target_pcb = c(0, width=64)
    lookup_pred_take_pcb = c(0, width=1)
    for i in range(depth):
        idx_hit_pcb = rd_idx_pcb == c(i, width=idx_w)
        wr_hit_pcb = wr_idx_pcb == c(i, width=idx_w)
        f3_wr_hit_pcb = tail_pcb.out() == c(i, width=idx_w)
        lookup_match_pcb = valid_pcb[i].out() & is_bstart_pcb[i].out() & pcs_pcb[i].out().eq(lookup_pc_pcb)
        lookup_known_match_pcb = valid_pcb[i].out() & pcs_pcb[i].out().eq(lookup_pc_pcb)
        rd_pc_pcb = idx_hit_pcb._select_internal(pcs_pcb[i].out(), rd_pc_pcb)
        lookup_hit_pcb = lookup_match_pcb._select_internal(c(1, width=1), lookup_hit_pcb)
        lookup_known_pcb = lookup_known_match_pcb._select_internal(c(1, width=1), lookup_known_pcb)
        lookup_kind_pcb = lookup_match_pcb._select_internal(kind_pcb[i].out(), lookup_kind_pcb)
        lookup_target_pcb = lookup_match_pcb._select_internal(target_pcb[i].out(), lookup_target_pcb)
        lookup_pred_take_pcb = lookup_match_pcb._select_internal(pred_take_pcb[i].out(), lookup_pred_take_pcb)

        valid_pcb[i].set(c(1, width=1), when=(wr_valid_pcb & wr_hit_pcb) | (f3_to_pcb_stage_bstart_valid_f3 & f3_wr_hit_pcb))
        pcs_pcb[i].set(wr_pc_pcb, when=wr_valid_pcb & wr_hit_pcb)
        pcs_pcb[i].set(f3_to_pcb_stage_bstart_pc_f3, when=f3_to_pcb_stage_bstart_valid_f3 & f3_wr_hit_pcb)
        kind_pcb[i].set(f3_to_pcb_stage_bstart_kind_f3, when=f3_to_pcb_stage_bstart_valid_f3 & f3_wr_hit_pcb)
        target_pcb[i].set(f3_to_pcb_stage_bstart_target_f3, when=f3_to_pcb_stage_bstart_valid_f3 & f3_wr_hit_pcb)
        pred_take_pcb[i].set(f3_to_pcb_stage_pred_take_f3, when=f3_to_pcb_stage_bstart_valid_f3 & f3_wr_hit_pcb)
        is_bstart_pcb[i].set(c(1, width=1), when=f3_to_pcb_stage_bstart_valid_f3 & f3_wr_hit_pcb)
        is_bstart_pcb[i].set(c(0, width=1), when=wr_valid_pcb & wr_hit_pcb)

    m.output("rd_pc_pcb", rd_pc_pcb)
    m.output("pcb_to_bru_stage_lookup_hit_pcb", lookup_hit_pcb)
    m.output("pcb_to_bru_stage_lookup_known_pcb", lookup_known_pcb)
    m.output("pcb_to_bru_stage_lookup_is_bstart_pcb", lookup_hit_pcb)
    m.output("pcb_to_bru_stage_lookup_kind_pcb", lookup_kind_pcb)
    m.output("pcb_to_bru_stage_lookup_target_pcb", lookup_target_pcb)
    m.output("pcb_to_bru_stage_lookup_pred_take_pcb", lookup_pred_take_pcb)
