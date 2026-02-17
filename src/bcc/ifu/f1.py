from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuF1")
def build_janus_bcc_ifu_f1(m: Circuit, *, btb_entries: int = 8) -> None:
    clk_f1 = m.clock("clk")
    rst_f1 = m.reset("rst")

    f0_to_f1_stage_pc_f0 = m.input("f0_to_f1_stage_pc_f0", width=64)
    f0_to_f1_stage_valid_f0 = m.input("f0_to_f1_stage_valid_f0", width=1)
    f0_to_f1_stage_pkt_uid_f0 = m.input("f0_to_f1_stage_pkt_uid_f0", width=64)

    c = m.const
    idx_w = max(1, (btb_entries - 1).bit_length())

    btb_valid_f1 = []
    btb_tag_f1 = []
    btb_tgt_f1 = []
    btb_ctr_f1 = []
    for i in range(btb_entries):
        btb_valid_f1.append(m.out(f"btb_valid{i}_f1", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1)))
        btb_tag_f1.append(m.out(f"btb_tag{i}_f1", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1)))
        btb_tgt_f1.append(m.out(f"btb_tgt{i}_f1", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1)))
        btb_ctr_f1.append(m.out(f"btb_ctr{i}_f1", clk=clk_f1, rst=rst_f1, width=2, init=c(1, width=2), en=c(1, width=1)))

    req_idx_f1 = f0_to_f1_stage_pc_f0[2 : 2 + idx_w]
    req_hit_f1 = c(0, width=1)
    req_tgt_f1 = f0_to_f1_stage_pc_f0 + c(8, width=64)
    req_take_f1 = c(0, width=1)

    for i in range(btb_entries):
        idx_hit_f1 = req_idx_f1.eq(c(i, width=idx_w))
        tag_hit_f1 = btb_valid_f1[i].out() & btb_tag_f1[i].out().eq(f0_to_f1_stage_pc_f0)
        hit_f1 = idx_hit_f1 & tag_hit_f1
        req_hit_f1 = hit_f1._select_internal(c(1, width=1), req_hit_f1)
        req_tgt_f1 = hit_f1._select_internal(btb_tgt_f1[i].out(), req_tgt_f1)
        req_take_f1 = hit_f1._select_internal(btb_ctr_f1[i].out()[1], req_take_f1)

        wr_hit_f1 = f0_to_f1_stage_valid_f0 & idx_hit_f1
        seq_tgt_f1 = f0_to_f1_stage_pc_f0 + c(8, width=64)
        ctr_old_f1 = btb_ctr_f1[i].out()
        pseudo_taken_f1 = f0_to_f1_stage_pc_f0[1]
        ctr_up_f1 = ctr_old_f1
        ctr_up_f1 = (pseudo_taken_f1 & ctr_old_f1.ult(c(3, width=2)))._select_internal(ctr_old_f1 + c(1, width=2), ctr_up_f1)
        ctr_up_f1 = ((~pseudo_taken_f1) & ctr_old_f1.ugt(c(0, width=2)))._select_internal(ctr_old_f1 - c(1, width=2), ctr_up_f1)

        btb_valid_f1[i].set(c(1, width=1), when=wr_hit_f1)
        btb_tag_f1[i].set(f0_to_f1_stage_pc_f0, when=wr_hit_f1)
        btb_tgt_f1[i].set(seq_tgt_f1, when=wr_hit_f1)
        btb_ctr_f1[i].set(ctr_up_f1, when=wr_hit_f1)

    # Keep strict lockstep-safe frontend behavior by default: predictor metadata
    # is tracked here, but F1 does not override the fetch PC in this milestone.
    next_pc_f1 = f0_to_f1_stage_pc_f0

    m.output("f1_to_icache_stage_pc_f1", next_pc_f1)
    m.output("f1_to_icache_stage_valid_f1", f0_to_f1_stage_valid_f0)
    m.output("f1_to_icache_stage_pkt_uid_f1", f0_to_f1_stage_pkt_uid_f0)
    m.output("f1_pred_hit_f1", req_hit_f1)
    m.output("f1_pred_take_f1", req_take_f1)
    m.output("f1_pred_tgt_f1", req_tgt_f1)
