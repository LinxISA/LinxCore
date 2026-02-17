from __future__ import annotations

from pycircuit import Circuit, module


@module(name="JanusBccIfuICache")
def build_janus_bcc_ifu_icache(
    m: Circuit,
    *,
    ic_sets: int = 32,
    ic_ways: int = 4,
    ic_line_bytes: int = 64,
    ifetch_bundle_bytes: int = 128,
    ic_miss_outstanding: int = 1,
    ic_enable: int = 1,
) -> None:
    clk_f1 = m.clock("clk")
    rst_f1 = m.reset("rst")

    f1_to_icache_stage_pc_f1 = m.input("f1_to_icache_stage_pc_f1", width=64)
    f1_to_icache_stage_valid_f1 = m.input("f1_to_icache_stage_valid_f1", width=1)
    f1_to_icache_stage_pkt_uid_f1 = m.input("f1_to_icache_stage_pkt_uid_f1", width=64)

    # Legacy direct-imem read port remains as a compatibility probe input, but
    # functional fetch data now comes from cache arrays + L2 refill.
    m.input("imem_rdata_top", width=64)

    ic_l2_req_ready_top = m.input("ic_l2_req_ready_top", width=1)
    ic_l2_rsp_valid_top = m.input("ic_l2_rsp_valid_top", width=1)
    ic_l2_rsp_addr_top = m.input("ic_l2_rsp_addr_top", width=64)
    ic_l2_rsp_data_top = m.input("ic_l2_rsp_data_top", width=512)
    ic_l2_rsp_error_top = m.input("ic_l2_rsp_error_top", width=1)

    if ic_sets <= 0 or (ic_sets & (ic_sets - 1)) != 0:
        raise ValueError("ic_sets must be power-of-two and > 0")
    if ic_ways <= 0:
        raise ValueError("ic_ways must be > 0")
    if ic_line_bytes != 64:
        raise ValueError("this milestone requires ic_line_bytes=64")
    if ifetch_bundle_bytes != 128:
        raise ValueError("this milestone requires ifetch_bundle_bytes=128")
    if ic_miss_outstanding != 1:
        raise ValueError("this milestone requires ic_miss_outstanding=1")

    c = m.const
    line_bits = ic_line_bytes * 8
    bundle_bits = ifetch_bundle_bytes * 8
    set_w = max(1, (ic_sets - 1).bit_length())
    way_w = max(1, (ic_ways - 1).bit_length())
    off_w = (ic_line_bytes - 1).bit_length()
    tag_w = 64 - set_w - off_w

    line_mask = c((~(ic_line_bytes - 1)) & ((1 << 64) - 1), width=64)
    bundle_mask = c((~(ifetch_bundle_bytes - 1)) & ((1 << 64) - 1), width=64)

    line_valid_ic = []
    line_tag_ic = []
    line_data_ic = []
    lru_rank_ic = []
    for s in range(ic_sets):
        for w in range(ic_ways):
            idx = s * ic_ways + w
            line_valid_ic.append(
                m.out(
                    f"line_valid{idx}_ic",
                    clk=clk_f1,
                    rst=rst_f1,
                    width=1,
                    init=c(0, width=1),
                    en=c(1, width=1),
                )
            )
            line_tag_ic.append(
                m.out(
                    f"line_tag{idx}_ic",
                    clk=clk_f1,
                    rst=rst_f1,
                    width=tag_w,
                    init=c(0, width=tag_w),
                    en=c(1, width=1),
                )
            )
            line_data_ic.append(
                m.out(
                    f"line_data{idx}_ic",
                    clk=clk_f1,
                    rst=rst_f1,
                    width=line_bits,
                    init=c(0, width=line_bits),
                    en=c(1, width=1),
                )
            )
            lru_rank_ic.append(
                m.out(
                    f"lru_rank{idx}_ic",
                    clk=clk_f1,
                    rst=rst_f1,
                    width=2,
                    init=c(w & 0x3, width=2),
                    en=c(1, width=1),
                )
            )

    miss_active_ic = m.out("miss_active_ic", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    miss_wait_rsp_ic = m.out("miss_wait_rsp_ic", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    miss_phase_ic = m.out("miss_phase_ic", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    miss_need_line0_ic = m.out("miss_need_line0_ic", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    miss_need_line1_ic = m.out("miss_need_line1_ic", clk=clk_f1, rst=rst_f1, width=1, init=c(0, width=1), en=c(1, width=1))
    miss_pc_ic = m.out("miss_pc_ic", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    miss_pkt_uid_ic = m.out("miss_pkt_uid_ic", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    miss_bundle_base_ic = m.out("miss_bundle_base_ic", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    miss_line0_addr_ic = m.out("miss_line0_addr_ic", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    miss_line1_addr_ic = m.out("miss_line1_addr_ic", clk=clk_f1, rst=rst_f1, width=64, init=c(0, width=64), en=c(1, width=1))
    miss_victim0_ic = m.out("miss_victim0_ic", clk=clk_f1, rst=rst_f1, width=way_w, init=c(0, width=way_w), en=c(1, width=1))
    miss_victim1_ic = m.out("miss_victim1_ic", clk=clk_f1, rst=rst_f1, width=way_w, init=c(0, width=way_w), en=c(1, width=1))

    req_pc_ic = f1_to_icache_stage_pc_f1
    req_pkt_uid_ic = f1_to_icache_stage_pkt_uid_f1
    req_bundle_base_ic = req_pc_ic & bundle_mask
    req_slot_base_off_ic = (req_pc_ic - req_bundle_base_ic).trunc(width=7)
    req_line0_addr_ic = req_bundle_base_ic
    req_line1_addr_ic = req_bundle_base_ic + c(ic_line_bytes, width=64)
    req_line0_set_ic = req_line0_addr_ic[off_w : off_w + set_w]
    req_line1_set_ic = req_line1_addr_ic[off_w : off_w + set_w]
    req_line0_tag_ic = req_line0_addr_ic.lshr(amount=off_w + set_w).trunc(width=tag_w)
    req_line1_tag_ic = req_line1_addr_ic.lshr(amount=off_w + set_w).trunc(width=tag_w)

    line0_hit_ic = c(0, width=1)
    line0_way_ic = c(0, width=way_w)
    line0_data_ic = c(0, width=line_bits)
    line1_hit_ic = c(0, width=1)
    line1_way_ic = c(0, width=way_w)
    line1_data_ic = c(0, width=line_bits)

    for s in range(ic_sets):
        set0_sel_ic = req_line0_set_ic.eq(c(s, width=set_w))
        set1_sel_ic = req_line1_set_ic.eq(c(s, width=set_w))
        for w in range(ic_ways):
            idx = s * ic_ways + w
            hit0_sw_ic = set0_sel_ic & line_valid_ic[idx].out() & line_tag_ic[idx].out().eq(req_line0_tag_ic)
            hit1_sw_ic = set1_sel_ic & line_valid_ic[idx].out() & line_tag_ic[idx].out().eq(req_line1_tag_ic)
            line0_hit_ic = hit0_sw_ic._select_internal(c(1, width=1), line0_hit_ic)
            line0_way_ic = hit0_sw_ic._select_internal(c(w, width=way_w), line0_way_ic)
            line0_data_ic = hit0_sw_ic._select_internal(line_data_ic[idx].out(), line0_data_ic)
            line1_hit_ic = hit1_sw_ic._select_internal(c(1, width=1), line1_hit_ic)
            line1_way_ic = hit1_sw_ic._select_internal(c(w, width=way_w), line1_way_ic)
            line1_data_ic = hit1_sw_ic._select_internal(line_data_ic[idx].out(), line1_data_ic)

    def pick_victim_way(set_idx_ic):
        victim_ic = c(0, width=way_w)
        for s in range(ic_sets):
            set_sel_ic = set_idx_ic.eq(c(s, width=set_w))
            inv_victim_ic = c(0, width=way_w)
            inv_found_ic = c(0, width=1)
            r0_victim_ic = c(0, width=way_w)
            r0_found_ic = c(0, width=1)
            for w in range(ic_ways):
                idx = s * ic_ways + w
                inv_sw_ic = ~line_valid_ic[idx].out()
                pick_inv_ic = inv_sw_ic & (~inv_found_ic)
                inv_victim_ic = pick_inv_ic._select_internal(c(w, width=way_w), inv_victim_ic)
                inv_found_ic = inv_sw_ic._select_internal(c(1, width=1), inv_found_ic)
                r0_sw_ic = lru_rank_ic[idx].out().eq(c(0, width=2))
                pick_r0_ic = r0_sw_ic & (~r0_found_ic)
                r0_victim_ic = pick_r0_ic._select_internal(c(w, width=way_w), r0_victim_ic)
                r0_found_ic = r0_sw_ic._select_internal(c(1, width=1), r0_found_ic)
            set_victim_ic = inv_found_ic._select_internal(inv_victim_ic, r0_victim_ic)
            victim_ic = set_sel_ic._select_internal(set_victim_ic, victim_ic)
        return victim_ic

    line0_victim_ic = pick_victim_way(req_line0_set_ic)
    line1_victim_ic = pick_victim_way(req_line1_set_ic)

    cache_en_ic = c(1 if ic_enable else 0, width=1)
    req_valid_ic = f1_to_icache_stage_valid_f1 & cache_en_ic
    req_hit_ic = req_valid_ic & line0_hit_ic & line1_hit_ic & (~miss_active_ic.out())
    req_miss_ic = req_valid_ic & (~req_hit_ic)
    miss_start_ic = req_miss_ic & (~miss_active_ic.out())

    miss_req_addr_ic = miss_phase_ic.out()._select_internal(miss_line1_addr_ic.out(), miss_line0_addr_ic.out())
    miss_req_need_ic = miss_phase_ic.out()._select_internal(miss_need_line1_ic.out(), miss_need_line0_ic.out())
    l2_req_valid_ic = miss_active_ic.out() & (~miss_wait_rsp_ic.out()) & miss_req_need_ic
    l2_req_fire_ic = l2_req_valid_ic & ic_l2_req_ready_top
    l2_fill_fire_ic = miss_wait_rsp_ic.out() & ic_l2_rsp_valid_top & ic_l2_rsp_addr_top.eq(miss_req_addr_ic)
    l2_fill_is_line1_ic = miss_phase_ic.out()
    l2_fill_victim_ic = l2_fill_is_line1_ic._select_internal(miss_victim1_ic.out(), miss_victim0_ic.out())
    l2_fill_set_ic = l2_fill_is_line1_ic._select_internal(
        miss_line1_addr_ic.out()[off_w : off_w + set_w], miss_line0_addr_ic.out()[off_w : off_w + set_w]
    )
    l2_fill_tag_ic = l2_fill_is_line1_ic._select_internal(
        miss_line1_addr_ic.out().lshr(amount=off_w + set_w).trunc(width=tag_w),
        miss_line0_addr_ic.out().lshr(amount=off_w + set_w).trunc(width=tag_w),
    )

    miss_active_next_ic = miss_active_ic.out()
    miss_wait_next_ic = miss_wait_rsp_ic.out()
    miss_phase_next_ic = miss_phase_ic.out()
    miss_need0_next_ic = miss_need_line0_ic.out()
    miss_need1_next_ic = miss_need_line1_ic.out()
    miss_pc_next_ic = miss_pc_ic.out()
    miss_pkt_uid_next_ic = miss_pkt_uid_ic.out()
    miss_bundle_base_next_ic = miss_bundle_base_ic.out()
    miss_line0_addr_next_ic = miss_line0_addr_ic.out()
    miss_line1_addr_next_ic = miss_line1_addr_ic.out()
    miss_victim0_next_ic = miss_victim0_ic.out()
    miss_victim1_next_ic = miss_victim1_ic.out()

    miss_active_next_ic = miss_start_ic._select_internal(c(1, width=1), miss_active_next_ic)
    miss_wait_next_ic = miss_start_ic._select_internal(c(0, width=1), miss_wait_next_ic)
    miss_phase_init_ic = (~line0_hit_ic)._select_internal(c(0, width=1), c(1, width=1))
    miss_phase_next_ic = miss_start_ic._select_internal(miss_phase_init_ic, miss_phase_next_ic)
    miss_need0_next_ic = miss_start_ic._select_internal(~line0_hit_ic, miss_need0_next_ic)
    miss_need1_next_ic = miss_start_ic._select_internal(~line1_hit_ic, miss_need1_next_ic)
    miss_pc_next_ic = miss_start_ic._select_internal(req_pc_ic, miss_pc_next_ic)
    miss_pkt_uid_next_ic = miss_start_ic._select_internal(req_pkt_uid_ic, miss_pkt_uid_next_ic)
    miss_bundle_base_next_ic = miss_start_ic._select_internal(req_bundle_base_ic, miss_bundle_base_next_ic)
    miss_line0_addr_next_ic = miss_start_ic._select_internal(req_line0_addr_ic, miss_line0_addr_next_ic)
    miss_line1_addr_next_ic = miss_start_ic._select_internal(req_line1_addr_ic, miss_line1_addr_next_ic)
    miss_victim0_init_ic = line0_hit_ic._select_internal(line0_way_ic, line0_victim_ic)
    miss_victim1_init_ic = line1_hit_ic._select_internal(line1_way_ic, line1_victim_ic)
    miss_victim0_next_ic = miss_start_ic._select_internal(miss_victim0_init_ic, miss_victim0_next_ic)
    miss_victim1_next_ic = miss_start_ic._select_internal(miss_victim1_init_ic, miss_victim1_next_ic)

    miss_wait_next_ic = l2_req_fire_ic._select_internal(c(1, width=1), miss_wait_next_ic)
    miss_wait_next_ic = l2_fill_fire_ic._select_internal(c(0, width=1), miss_wait_next_ic)

    need0_after_fill_ic = l2_fill_fire_ic & (~l2_fill_is_line1_ic)
    need1_after_fill_ic = l2_fill_fire_ic & l2_fill_is_line1_ic
    miss_need0_postfill_ic = need0_after_fill_ic._select_internal(c(0, width=1), miss_need0_next_ic)
    miss_need1_postfill_ic = need1_after_fill_ic._select_internal(c(0, width=1), miss_need1_next_ic)
    miss_need0_next_ic = miss_need0_postfill_ic
    miss_need1_next_ic = miss_need1_postfill_ic

    miss_done_ic = l2_fill_fire_ic & (~miss_need0_postfill_ic) & (~miss_need1_postfill_ic)
    miss_active_next_ic = miss_done_ic._select_internal(c(0, width=1), miss_active_next_ic)
    phase_after_fill_ic = miss_need0_postfill_ic._select_internal(c(0, width=1), c(1, width=1))
    miss_phase_next_ic = l2_fill_fire_ic._select_internal(phase_after_fill_ic, miss_phase_next_ic)

    miss_active_ic.set(miss_active_next_ic)
    miss_wait_rsp_ic.set(miss_wait_next_ic)
    miss_phase_ic.set(miss_phase_next_ic)
    miss_need_line0_ic.set(miss_need0_next_ic)
    miss_need_line1_ic.set(miss_need1_next_ic)
    miss_pc_ic.set(miss_pc_next_ic)
    miss_pkt_uid_ic.set(miss_pkt_uid_next_ic)
    miss_bundle_base_ic.set(miss_bundle_base_next_ic)
    miss_line0_addr_ic.set(miss_line0_addr_next_ic)
    miss_line1_addr_ic.set(miss_line1_addr_next_ic)
    miss_victim0_ic.set(miss_victim0_next_ic)
    miss_victim1_ic.set(miss_victim1_next_ic)

    for s in range(ic_sets):
        for w in range(ic_ways):
            idx = s * ic_ways + w
            fill_sw_ic = (
                l2_fill_fire_ic
                & l2_fill_set_ic.eq(c(s, width=set_w))
                & l2_fill_victim_ic.eq(c(w, width=way_w))
                & (~ic_l2_rsp_error_top)
            )
            line_valid_ic[idx].set(c(1, width=1), when=fill_sw_ic)
            line_tag_ic[idx].set(l2_fill_tag_ic, when=fill_sw_ic)
            line_data_ic[idx].set(ic_l2_rsp_data_top, when=fill_sw_ic)

    def touch_lru(set_idx_ic, way_ic, fire_ic):
        for s in range(ic_sets):
            fire_set_ic = fire_ic & set_idx_ic.eq(c(s, width=set_w))
            acc_rank_ic = c(0, width=2)
            for w in range(ic_ways):
                idx = s * ic_ways + w
                hit_sw_ic = fire_set_ic & way_ic.eq(c(w, width=way_w))
                acc_rank_ic = hit_sw_ic._select_internal(lru_rank_ic[idx].out(), acc_rank_ic)
            for w in range(ic_ways):
                idx = s * ic_ways + w
                is_hit_sw_ic = way_ic.eq(c(w, width=way_w))
                old_rank_ic = lru_rank_ic[idx].out()
                dec_sw_ic = fire_set_ic & (~is_hit_sw_ic) & old_rank_ic.ugt(acc_rank_ic)
                new_rank_ic = dec_sw_ic._select_internal(old_rank_ic - c(1, width=2), old_rank_ic)
                new_rank_ic = (fire_set_ic & is_hit_sw_ic)._select_internal(c(ic_ways - 1, width=2), new_rank_ic)
                lru_rank_ic[idx].set(new_rank_ic)

    touch_lru(req_line0_set_ic, line0_way_ic, req_hit_ic)
    touch_lru(req_line1_set_ic, line1_way_ic, req_hit_ic)
    touch_lru(l2_fill_set_ic, l2_fill_victim_ic, l2_fill_fire_ic & (~ic_l2_rsp_error_top))

    req_bundle128_ic = m.cat(line1_data_ic, line0_data_ic)
    req_valid_to_f2_ic = req_hit_ic
    req_stall_to_f2_ic = req_miss_ic | miss_active_ic.out()

    m.output("ic_l2_req_valid_top", l2_req_valid_ic)
    m.output("ic_l2_req_addr_top", miss_req_addr_ic & line_mask)

    m.output("imem_raddr_top", miss_req_addr_ic & line_mask)

    m.output("f1_to_f2_stage_pc_f1", req_pc_ic)
    m.output("f1_to_f2_stage_bundle128_f1", req_bundle128_ic)
    m.output("f1_to_f2_stage_bundle_base_pc_f1", req_bundle_base_ic)
    m.output("f1_to_f2_stage_slot_base_offset_f1", req_slot_base_off_ic)
    m.output("f1_to_f2_stage_hit_f1", req_hit_ic)
    m.output("f1_to_f2_stage_miss_f1", req_miss_ic)
    m.output("f1_to_f2_stage_stall_f1", req_stall_to_f2_ic)
    m.output("f1_to_f2_stage_pkt_uid_f1", req_pkt_uid_ic)
    m.output("f1_to_f2_stage_valid_f1", req_valid_to_f2_ic)

    # Backward-compatible probe fields kept for existing debug tooling.
    m.output("f1_to_f2_stage_window_f1", line0_data_ic[0:64])
    m.output("icache_to_f2_stage_pc_f1", req_pc_ic)
    m.output("icache_to_f2_stage_window_f1", line0_data_ic[0:64])
    m.output("icache_to_f2_stage_valid_f1", req_valid_to_f2_ic)
    m.output("icache_to_f2_stage_pkt_uid_f1", req_pkt_uid_ic)
    m.output("icache_miss_active_ic", miss_active_ic.out())

