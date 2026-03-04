from __future__ import annotations

from pycircuit import Circuit, function, module, u

from common.isa import (
    OP_BSTART_STD_CALL,
    OP_BSTART_STD_COND,
    OP_BSTART_STD_DIRECT,
    OP_BSTART_STD_FALL,
    OP_C_BSTART_COND,
    OP_C_BSTART_DIRECT,
    OP_C_BSTART_STD,
    OP_C_BSTOP,
    OP_FENTRY,
    OP_FEXIT,
    OP_FRET_RA,
    OP_FRET_STK,
)


@function
def op_is_any(m: Circuit, op, *codes: int):
    v = u(1, 0)
    for code in codes:
        v = v | (op == u(12, code))
    return v


@module(name="LinxCoreCommitTraceStage")
def build_commit_trace_stage(
    m: Circuit,
    *,
    commit_w: int = 4,
    max_commit_slots: int = 4,
    rob_w: int = 6,
    sq_entries: int = 32,
) -> None:
    if commit_w <= 0:
        raise ValueError("commit_w must be > 0")
    if max_commit_slots <= 0:
        raise ValueError("max_commit_slots must be > 0")
    if commit_w > max_commit_slots:
        raise ValueError("commit_w must be <= max_commit_slots")
    if rob_w <= 0:
        raise ValueError("rob_w must be > 0")
    if sq_entries <= 0:
        raise ValueError("sq_entries must be > 0")

    z1 = u(1, 0)
    o1 = u(1, 1)
    z3 = u(3, 0)
    z4 = u(4, 0)
    z6 = u(6, 0)
    z32 = u(32, 0)
    z64 = u(64, 0)
    zrob = u(rob_w, 0)
    z12 = u(12, 0)
    z2 = u(2, 0)

    shadow_boundary_fire = m.input("shadow_boundary_fire", width=1)
    shadow_boundary_fire1 = m.input("shadow_boundary_fire1", width=1)

    trap_pending = m.input("trap_pending", width=1)
    trap_rob = m.input("trap_rob", width=rob_w)
    trap_cause_i = m.input("trap_cause", width=32)

    macro_trace_fire = m.input("macro_trace_fire", width=1)
    macro_trace_pc = m.input("macro_trace_pc", width=64)
    macro_trace_rob = m.input("macro_trace_rob", width=rob_w)
    macro_trace_op = m.input("macro_trace_op", width=12)
    macro_trace_val = m.input("macro_trace_val", width=64)
    macro_trace_len = m.input("macro_trace_len", width=3)
    macro_trace_insn_raw = m.input("macro_trace_insn_raw", width=64)
    macro_uop_uid = m.input("macro_uop_uid", width=64)
    macro_uop_parent_uid = m.input("macro_uop_parent_uid", width=64)
    macro_uop_template_kind = m.input("macro_uop_template_kind", width=3)
    macro_trace_wb_valid = m.input("macro_trace_wb_valid", width=1)
    macro_trace_wb_rd = m.input("macro_trace_wb_rd", width=6)
    macro_trace_wb_data = m.input("macro_trace_wb_data", width=64)
    macro_trace_src0_valid = m.input("macro_trace_src0_valid", width=1)
    macro_trace_src0_reg = m.input("macro_trace_src0_reg", width=6)
    macro_trace_src0_data = m.input("macro_trace_src0_data", width=64)
    macro_trace_src1_valid = m.input("macro_trace_src1_valid", width=1)
    macro_trace_src1_reg = m.input("macro_trace_src1_reg", width=6)
    macro_trace_src1_data = m.input("macro_trace_src1_data", width=64)
    macro_trace_dst_valid = m.input("macro_trace_dst_valid", width=1)
    macro_trace_dst_reg = m.input("macro_trace_dst_reg", width=6)
    macro_trace_dst_data = m.input("macro_trace_dst_data", width=64)
    macro_trace_mem_valid = m.input("macro_trace_mem_valid", width=1)
    macro_trace_mem_is_store = m.input("macro_trace_mem_is_store", width=1)
    macro_trace_mem_addr = m.input("macro_trace_mem_addr", width=64)
    macro_trace_mem_wdata = m.input("macro_trace_mem_wdata", width=64)
    macro_trace_mem_rdata = m.input("macro_trace_mem_rdata", width=64)
    macro_trace_mem_size = m.input("macro_trace_mem_size", width=4)
    macro_trace_next_pc = m.input("macro_trace_next_pc", width=64)

    macro_shadow_fire = m.input("macro_shadow_fire", width=1)
    macro_shadow_emit_uop = m.input("macro_shadow_emit_uop", width=1)

    # Buffer forwarding for load commit traces (reuse committed store buffer state).
    stbuf_valid = []
    stbuf_addr = []
    stbuf_data = []
    for i in range(int(sq_entries)):
        stbuf_valid.append(m.input(f"stbuf_valid{i}", width=1))
        stbuf_addr.append(m.input(f"stbuf_addr{i}", width=64))
        stbuf_data.append(m.input(f"stbuf_data{i}", width=64))

    commit_fires = []
    commit_pcs = []
    commit_next_pcs = []
    commit_idxs = []

    rob_pcs = []
    rob_ops = []
    rob_values = []
    rob_lens = []
    rob_insn_raws = []
    rob_uop_uids = []
    rob_parent_uids = []
    rob_dst_kinds = []
    rob_dst_aregs = []
    rob_src0_valids = []
    rob_src0_regs = []
    rob_src0_datas = []
    rob_src1_valids = []
    rob_src1_regs = []
    rob_src1_datas = []
    rob_is_stores = []
    rob_st_addrs = []
    rob_st_datas = []
    rob_st_sizes = []
    rob_is_loads = []
    rob_ld_addrs = []
    rob_ld_datas = []
    rob_ld_sizes = []
    rob_checkpoint_ids = []
    rob_load_store_ids = []

    commit_block_uids = []
    commit_block_bids = []
    commit_core_ids = []
    commit_is_bstarts = []
    commit_is_bstops = []

    for slot in range(int(max_commit_slots)):
        commit_fires.append(m.input(f"commit_fire{slot}_i", width=1))
        commit_pcs.append(m.input(f"commit_pc{slot}_i", width=64))
        commit_next_pcs.append(m.input(f"commit_next_pc{slot}_i", width=64))
        commit_idxs.append(m.input(f"commit_idx{slot}_i", width=rob_w))

        rob_pcs.append(m.input(f"rob_pc{slot}", width=64))
        rob_ops.append(m.input(f"rob_op{slot}", width=12))
        rob_values.append(m.input(f"rob_value{slot}", width=64))
        rob_lens.append(m.input(f"rob_len{slot}", width=3))
        rob_insn_raws.append(m.input(f"rob_insn_raw{slot}", width=64))
        rob_uop_uids.append(m.input(f"rob_uop_uid{slot}", width=64))
        rob_parent_uids.append(m.input(f"rob_parent_uid{slot}", width=64))
        rob_dst_kinds.append(m.input(f"rob_dst_kind{slot}", width=2))
        rob_dst_aregs.append(m.input(f"rob_dst_areg{slot}", width=6))
        rob_src0_valids.append(m.input(f"rob_src0_valid{slot}", width=1))
        rob_src0_regs.append(m.input(f"rob_src0_reg{slot}", width=6))
        rob_src0_datas.append(m.input(f"rob_src0_data{slot}", width=64))
        rob_src1_valids.append(m.input(f"rob_src1_valid{slot}", width=1))
        rob_src1_regs.append(m.input(f"rob_src1_reg{slot}", width=6))
        rob_src1_datas.append(m.input(f"rob_src1_data{slot}", width=64))
        rob_is_stores.append(m.input(f"rob_is_store{slot}", width=1))
        rob_st_addrs.append(m.input(f"rob_store_addr{slot}", width=64))
        rob_st_datas.append(m.input(f"rob_store_data{slot}", width=64))
        rob_st_sizes.append(m.input(f"rob_store_size{slot}", width=4))
        rob_is_loads.append(m.input(f"rob_is_load{slot}", width=1))
        rob_ld_addrs.append(m.input(f"rob_load_addr{slot}", width=64))
        rob_ld_datas.append(m.input(f"rob_load_data{slot}", width=64))
        rob_ld_sizes.append(m.input(f"rob_load_size{slot}", width=4))
        rob_checkpoint_ids.append(m.input(f"rob_checkpoint_id{slot}", width=6))
        rob_load_store_ids.append(m.input(f"rob_load_store_id{slot}", width=32))

        commit_block_uids.append(m.input(f"commit_block_uid{slot}_i", width=64))
        commit_block_bids.append(m.input(f"commit_block_bid{slot}_i", width=64))
        commit_core_ids.append(m.input(f"commit_core_id{slot}_i", width=2))
        commit_is_bstarts.append(m.input(f"commit_is_bstart{slot}_i", width=1))
        commit_is_bstops.append(m.input(f"commit_is_bstop{slot}_i", width=1))

    macro_shadow_uid = macro_uop_uid | u(64, 1 << 62)
    macro_shadow_uid_alt = macro_shadow_uid | u(64, 1)

    for slot in range(int(max_commit_slots)):
        fire = z1
        pc = z64
        rob_idx = zrob
        uop_uid = z64
        parent_uid = z64
        template_kind = u(3, 0)
        op = z12
        val = z64
        ln = z3
        insn_raw = z64
        wb_valid = z1
        wb_rd = z6
        wb_data = z64
        src0_valid = z1
        src0_reg = z6
        src0_data = z64
        src1_valid = z1
        src1_reg = z6
        src1_data = z64
        dst_valid = z1
        dst_reg = z6
        dst_data = z64
        mem_valid = z1
        mem_is_store = z1
        mem_addr = z64
        mem_wdata = z64
        mem_rdata = z64
        mem_size = z4
        trap_valid = z1
        trap_cause = z32
        next_pc = z64
        checkpoint_id = u(6, 0)

        if slot < commit_w:
            fire_raw = commit_fires[slot]
            pc = rob_pcs[slot]
            rob_idx = commit_idxs[slot]
            op = rob_ops[slot]
            val = rob_values[slot]
            ln = rob_lens[slot]
            insn_raw = rob_insn_raws[slot]
            uop_uid = rob_uop_uids[slot]
            parent_uid = rob_parent_uids[slot]
            is_macro_commit = op_is_any(m, op, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
            fire = fire_raw & (~is_macro_commit)
            is_gpr_dst = rob_dst_kinds[slot] == u(2, 1)
            wb_trace_suppress = op_is_any(
                m,
                op,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
                OP_C_BSTOP,
            )
            rd = rob_dst_aregs[slot]
            wb_valid = fire & is_gpr_dst & (rd != z6) & (~wb_trace_suppress)
            wb_rd = rd
            wb_data = rob_values[slot]
            src0_valid = fire & rob_src0_valids[slot]
            src0_reg = rob_src0_regs[slot]
            src0_data = rob_src0_datas[slot]
            src1_valid = fire & rob_src1_valids[slot]
            src1_reg = rob_src1_regs[slot]
            src1_data = rob_src1_datas[slot]
            dst_valid = wb_valid
            dst_reg = wb_rd
            dst_data = wb_data
            next_pc_slot = commit_next_pcs[slot]
            is_store = rob_is_stores[slot]
            is_load = rob_is_loads[slot]
            ld_trace_data = rob_ld_datas[slot]
            for i in range(int(sq_entries)):
                st_hit = stbuf_valid[i] & (stbuf_addr[i] == rob_ld_addrs[slot])
                ld_trace_data = stbuf_data[i] if st_hit else ld_trace_data
            mem_valid = fire & (is_store | is_load)
            mem_is_store = fire & is_store
            mem_addr = rob_st_addrs[slot] if is_store else rob_ld_addrs[slot]
            mem_wdata = rob_st_datas[slot] if is_store else z64
            mem_rdata = ld_trace_data if is_load else z64
            mem_size = rob_st_sizes[slot] if is_store else rob_ld_sizes[slot]
            next_pc = next_pc_slot
            checkpoint_id = rob_checkpoint_ids[slot]
            trap_hit = trap_pending & (commit_idxs[slot] == trap_rob)
            trap_valid = fire & trap_hit
            trap_cause = trap_cause_i if trap_hit else trap_cause

        # When `shadow_boundary_fire` is active, shift real retire records up
        # by one slot so slot0 can carry the synthetic boundary marker event.
        if slot > 0 and (slot - 1) < commit_w:
            prev = slot - 1
            fire_prev_raw = commit_fires[prev]
            pc_prev = commit_pcs[prev]
            rob_prev = commit_idxs[prev]
            op_prev = rob_ops[prev]
            val_prev = rob_values[prev]
            ln_prev = rob_lens[prev]
            insn_prev = rob_insn_raws[prev]
            uid_prev = rob_uop_uids[prev]
            parent_prev = rob_parent_uids[prev]
            is_macro_prev = op_is_any(m, op_prev, OP_FENTRY, OP_FEXIT, OP_FRET_RA, OP_FRET_STK)
            fire_prev = fire_prev_raw & (~is_macro_prev)
            is_gpr_prev = rob_dst_kinds[prev] == u(2, 1)
            wb_suppress_prev = op_is_any(
                m,
                op_prev,
                OP_C_BSTART_STD,
                OP_C_BSTART_COND,
                OP_C_BSTART_DIRECT,
                OP_BSTART_STD_FALL,
                OP_BSTART_STD_DIRECT,
                OP_BSTART_STD_COND,
                OP_BSTART_STD_CALL,
                OP_C_BSTOP,
            )
            rd_prev = rob_dst_aregs[prev]
            wb_valid_prev = fire_prev & is_gpr_prev & (rd_prev != z6) & (~wb_suppress_prev)
            wb_rd_prev = rd_prev
            wb_data_prev = rob_values[prev]
            src0_valid_prev = fire_prev & rob_src0_valids[prev]
            src0_reg_prev = rob_src0_regs[prev]
            src0_data_prev = rob_src0_datas[prev]
            src1_valid_prev = fire_prev & rob_src1_valids[prev]
            src1_reg_prev = rob_src1_regs[prev]
            src1_data_prev = rob_src1_datas[prev]
            dst_valid_prev = wb_valid_prev
            dst_reg_prev = wb_rd_prev
            dst_data_prev = wb_data_prev
            next_pc_prev = commit_next_pcs[prev]
            is_store_prev = rob_is_stores[prev]
            is_load_prev = rob_is_loads[prev]
            ld_trace_prev = rob_ld_datas[prev]
            for i in range(int(sq_entries)):
                st_hit_prev = stbuf_valid[i] & (stbuf_addr[i] == rob_ld_addrs[prev])
                ld_trace_prev = stbuf_data[i] if st_hit_prev else ld_trace_prev
            mem_valid_prev = fire_prev & (is_store_prev | is_load_prev)
            mem_is_store_prev = fire_prev & is_store_prev
            mem_addr_prev = rob_st_addrs[prev] if is_store_prev else rob_ld_addrs[prev]
            mem_wdata_prev = rob_st_datas[prev] if is_store_prev else z64
            mem_rdata_prev = ld_trace_prev if is_load_prev else z64
            mem_size_prev = rob_st_sizes[prev] if is_store_prev else rob_ld_sizes[prev]
            checkpoint_prev = rob_checkpoint_ids[prev]

            shift_active = shadow_boundary_fire
            if slot > 1:
                shift_active = shift_active | shadow_boundary_fire1

            fire = fire_prev if shift_active else fire
            pc = pc_prev if shift_active else pc
            rob_idx = rob_prev if shift_active else rob_idx
            op = op_prev if shift_active else op
            val = val_prev if shift_active else val
            ln = ln_prev if shift_active else ln
            insn_raw = insn_prev if shift_active else insn_raw
            uop_uid = uid_prev if shift_active else uop_uid
            parent_uid = parent_prev if shift_active else parent_uid
            wb_valid = wb_valid_prev if shift_active else wb_valid
            wb_rd = wb_rd_prev if shift_active else wb_rd
            wb_data = wb_data_prev if shift_active else wb_data
            src0_valid = src0_valid_prev if shift_active else src0_valid
            src0_reg = src0_reg_prev if shift_active else src0_reg
            src0_data = src0_data_prev if shift_active else src0_data
            src1_valid = src1_valid_prev if shift_active else src1_valid
            src1_reg = src1_reg_prev if shift_active else src1_reg
            src1_data = src1_data_prev if shift_active else src1_data
            dst_valid = dst_valid_prev if shift_active else dst_valid
            dst_reg = dst_reg_prev if shift_active else dst_reg
            dst_data = dst_data_prev if shift_active else dst_data
            mem_valid = mem_valid_prev if shift_active else mem_valid
            mem_is_store = mem_is_store_prev if shift_active else mem_is_store
            mem_addr = mem_addr_prev if shift_active else mem_addr
            mem_wdata = mem_wdata_prev if shift_active else mem_wdata
            mem_rdata = mem_rdata_prev if shift_active else mem_rdata
            mem_size = mem_size_prev if shift_active else mem_size
            next_pc = next_pc_prev if shift_active else next_pc
            checkpoint_id = checkpoint_prev if shift_active else checkpoint_id

        if slot == 0:
            fire = o1 if shadow_boundary_fire else fire
            pc = commit_pcs[0] if shadow_boundary_fire else pc
            rob_idx = commit_idxs[0] if shadow_boundary_fire else rob_idx
            op = rob_ops[0] if shadow_boundary_fire else op
            val = rob_values[0] if shadow_boundary_fire else val
            ln = rob_lens[0] if shadow_boundary_fire else ln
            insn_raw = rob_insn_raws[0] if shadow_boundary_fire else insn_raw
            uop_uid = rob_uop_uids[0] if shadow_boundary_fire else uop_uid
            parent_uid = rob_parent_uids[0] if shadow_boundary_fire else parent_uid
            wb_valid = z1 if shadow_boundary_fire else wb_valid
            wb_rd = z6 if shadow_boundary_fire else wb_rd
            wb_data = z64 if shadow_boundary_fire else wb_data
            src0_valid = z1 if shadow_boundary_fire else src0_valid
            src0_reg = z6 if shadow_boundary_fire else src0_reg
            src0_data = z64 if shadow_boundary_fire else src0_data
            src1_valid = z1 if shadow_boundary_fire else src1_valid
            src1_reg = z6 if shadow_boundary_fire else src1_reg
            src1_data = z64 if shadow_boundary_fire else src1_data
            dst_valid = z1 if shadow_boundary_fire else dst_valid
            dst_reg = z6 if shadow_boundary_fire else dst_reg
            dst_data = z64 if shadow_boundary_fire else dst_data
            mem_valid = z1 if shadow_boundary_fire else mem_valid
            mem_is_store = z1 if shadow_boundary_fire else mem_is_store
            mem_addr = z64 if shadow_boundary_fire else mem_addr
            mem_wdata = z64 if shadow_boundary_fire else mem_wdata
            mem_rdata = z64 if shadow_boundary_fire else mem_rdata
            mem_size = z4 if shadow_boundary_fire else mem_size
            next_pc = commit_pcs[0] if shadow_boundary_fire else next_pc
            checkpoint_id = rob_checkpoint_ids[0] if shadow_boundary_fire else checkpoint_id

            fire = o1 if macro_trace_fire else fire
            pc = macro_trace_pc if macro_trace_fire else pc
            rob_idx = macro_trace_rob if macro_trace_fire else rob_idx
            op = macro_trace_op if macro_trace_fire else op
            val = macro_trace_val if macro_trace_fire else val
            ln = macro_trace_len if macro_trace_fire else ln
            insn_raw = macro_trace_insn_raw if macro_trace_fire else insn_raw
            uop_uid = macro_uop_uid if macro_trace_fire else uop_uid
            parent_uid = macro_uop_parent_uid if macro_trace_fire else parent_uid
            template_kind = macro_uop_template_kind if macro_trace_fire else template_kind
            wb_valid = macro_trace_wb_valid if macro_trace_fire else wb_valid
            wb_rd = macro_trace_wb_rd if macro_trace_fire else wb_rd
            wb_data = macro_trace_wb_data if macro_trace_fire else wb_data
            src0_valid = macro_trace_src0_valid if macro_trace_fire else src0_valid
            src0_reg = macro_trace_src0_reg if macro_trace_fire else src0_reg
            src0_data = macro_trace_src0_data if macro_trace_fire else src0_data
            src1_valid = macro_trace_src1_valid if macro_trace_fire else src1_valid
            src1_reg = macro_trace_src1_reg if macro_trace_fire else src1_reg
            src1_data = macro_trace_src1_data if macro_trace_fire else src1_data
            dst_valid = macro_trace_dst_valid if macro_trace_fire else dst_valid
            dst_reg = macro_trace_dst_reg if macro_trace_fire else dst_reg
            dst_data = macro_trace_dst_data if macro_trace_fire else dst_data
            mem_valid = macro_trace_mem_valid if macro_trace_fire else mem_valid
            mem_is_store = macro_trace_mem_is_store if macro_trace_fire else mem_is_store
            mem_addr = macro_trace_mem_addr if macro_trace_fire else mem_addr
            mem_wdata = macro_trace_mem_wdata if macro_trace_fire else mem_wdata
            mem_rdata = macro_trace_mem_rdata if macro_trace_fire else mem_rdata
            mem_size = macro_trace_mem_size if macro_trace_fire else mem_size
            next_pc = macro_trace_next_pc if macro_trace_fire else next_pc

            uop_uid = macro_shadow_uid if macro_shadow_fire else uop_uid
            wb_valid = z1 if macro_shadow_fire else wb_valid
            wb_rd = z6 if macro_shadow_fire else wb_rd
            wb_data = z64 if macro_shadow_fire else wb_data
            src0_valid = z1 if macro_shadow_fire else src0_valid
            src0_reg = z6 if macro_shadow_fire else src0_reg
            src0_data = z64 if macro_shadow_fire else src0_data
            src1_valid = z1 if macro_shadow_fire else src1_valid
            src1_reg = z6 if macro_shadow_fire else src1_reg
            src1_data = z64 if macro_shadow_fire else src1_data
            dst_valid = z1 if macro_shadow_fire else dst_valid
            dst_reg = z6 if macro_shadow_fire else dst_reg
            dst_data = z64 if macro_shadow_fire else dst_data
            mem_valid = z1 if macro_shadow_fire else mem_valid
            mem_is_store = z1 if macro_shadow_fire else mem_is_store
            mem_addr = z64 if macro_shadow_fire else mem_addr
            mem_wdata = z64 if macro_shadow_fire else mem_wdata
            mem_rdata = z64 if macro_shadow_fire else mem_rdata
            mem_size = z4 if macro_shadow_fire else mem_size
            next_pc = macro_trace_pc if macro_shadow_fire else next_pc
        else:
            if slot == 1 and commit_w > 1:
                fire = o1 if shadow_boundary_fire1 else fire
                pc = commit_pcs[1] if shadow_boundary_fire1 else pc
                rob_idx = commit_idxs[1] if shadow_boundary_fire1 else rob_idx
                op = rob_ops[1] if shadow_boundary_fire1 else op
                val = rob_values[1] if shadow_boundary_fire1 else val
                ln = rob_lens[1] if shadow_boundary_fire1 else ln
                insn_raw = rob_insn_raws[1] if shadow_boundary_fire1 else insn_raw
                uop_uid = rob_uop_uids[1] if shadow_boundary_fire1 else uop_uid
                parent_uid = rob_parent_uids[1] if shadow_boundary_fire1 else parent_uid
                wb_valid = z1 if shadow_boundary_fire1 else wb_valid
                wb_rd = z6 if shadow_boundary_fire1 else wb_rd
                wb_data = z64 if shadow_boundary_fire1 else wb_data
                src0_valid = z1 if shadow_boundary_fire1 else src0_valid
                src0_reg = z6 if shadow_boundary_fire1 else src0_reg
                src0_data = z64 if shadow_boundary_fire1 else src0_data
                src1_valid = z1 if shadow_boundary_fire1 else src1_valid
                src1_reg = z6 if shadow_boundary_fire1 else src1_reg
                src1_data = z64 if shadow_boundary_fire1 else src1_data
                dst_valid = z1 if shadow_boundary_fire1 else dst_valid
                dst_reg = z6 if shadow_boundary_fire1 else dst_reg
                dst_data = z64 if shadow_boundary_fire1 else dst_data
                mem_valid = z1 if shadow_boundary_fire1 else mem_valid
                mem_is_store = z1 if shadow_boundary_fire1 else mem_is_store
                mem_addr = z64 if shadow_boundary_fire1 else mem_addr
                mem_wdata = z64 if shadow_boundary_fire1 else mem_wdata
                mem_rdata = z64 if shadow_boundary_fire1 else mem_rdata
                mem_size = z4 if shadow_boundary_fire1 else mem_size
                next_pc = commit_pcs[1] if shadow_boundary_fire1 else next_pc
                checkpoint_id = rob_checkpoint_ids[1] if shadow_boundary_fire1 else checkpoint_id
            if slot == 1:
                fire = o1 if macro_shadow_emit_uop else fire
                pc = macro_trace_pc if macro_shadow_emit_uop else pc
                rob_idx = macro_trace_rob if macro_shadow_emit_uop else rob_idx
                op = macro_trace_op if macro_shadow_emit_uop else op
                val = macro_trace_val if macro_shadow_emit_uop else val
                ln = macro_trace_len if macro_shadow_emit_uop else ln
                insn_raw = macro_trace_insn_raw if macro_shadow_emit_uop else insn_raw
                uop_uid = macro_shadow_uid_alt if macro_shadow_emit_uop else uop_uid
                parent_uid = macro_uop_parent_uid if macro_shadow_emit_uop else parent_uid
                template_kind = macro_uop_template_kind if macro_shadow_emit_uop else template_kind
                wb_valid = macro_trace_wb_valid if macro_shadow_emit_uop else wb_valid
                wb_rd = macro_trace_wb_rd if macro_shadow_emit_uop else wb_rd
                wb_data = macro_trace_wb_data if macro_shadow_emit_uop else wb_data
                src0_valid = macro_trace_src0_valid if macro_shadow_emit_uop else src0_valid
                src0_reg = macro_trace_src0_reg if macro_shadow_emit_uop else src0_reg
                src0_data = macro_trace_src0_data if macro_shadow_emit_uop else src0_data
                src1_valid = macro_trace_src1_valid if macro_shadow_emit_uop else src1_valid
                src1_reg = macro_trace_src1_reg if macro_shadow_emit_uop else src1_reg
                src1_data = macro_trace_src1_data if macro_shadow_emit_uop else src1_data
                dst_valid = macro_trace_dst_valid if macro_shadow_emit_uop else dst_valid
                dst_reg = macro_trace_dst_reg if macro_shadow_emit_uop else dst_reg
                dst_data = macro_trace_dst_data if macro_shadow_emit_uop else dst_data
                mem_valid = macro_trace_mem_valid if macro_shadow_emit_uop else mem_valid
                mem_is_store = macro_trace_mem_is_store if macro_shadow_emit_uop else mem_is_store
                mem_addr = macro_trace_mem_addr if macro_shadow_emit_uop else mem_addr
                mem_wdata = macro_trace_mem_wdata if macro_shadow_emit_uop else mem_wdata
                mem_rdata = macro_trace_mem_rdata if macro_shadow_emit_uop else mem_rdata
                mem_size = macro_trace_mem_size if macro_shadow_emit_uop else mem_size
                next_pc = macro_trace_next_pc if macro_shadow_emit_uop else next_pc

            if slot == 1:
                macro_kill = macro_trace_fire & (~macro_shadow_emit_uop)
            else:
                macro_kill = macro_trace_fire
            fire = z1 if macro_kill else fire
            pc = z64 if macro_kill else pc
            rob_idx = zrob if macro_kill else rob_idx
            op = z12 if macro_kill else op
            val = z64 if macro_kill else val
            ln = z3 if macro_kill else ln
            insn_raw = z64 if macro_kill else insn_raw
            uop_uid = z64 if macro_kill else uop_uid
            parent_uid = z64 if macro_kill else parent_uid
            template_kind = u(3, 0) if macro_kill else template_kind
            wb_valid = z1 if macro_kill else wb_valid
            wb_rd = z6 if macro_kill else wb_rd
            wb_data = z64 if macro_kill else wb_data
            src0_valid = z1 if macro_kill else src0_valid
            src0_reg = z6 if macro_kill else src0_reg
            src0_data = z64 if macro_kill else src0_data
            src1_valid = z1 if macro_kill else src1_valid
            src1_reg = z6 if macro_kill else src1_reg
            src1_data = z64 if macro_kill else src1_data
            dst_valid = z1 if macro_kill else dst_valid
            dst_reg = z6 if macro_kill else dst_reg
            dst_data = z64 if macro_kill else dst_data
            mem_valid = z1 if macro_kill else mem_valid
            mem_is_store = z1 if macro_kill else mem_is_store
            mem_addr = z64 if macro_kill else mem_addr
            mem_wdata = z64 if macro_kill else mem_wdata
            mem_rdata = z64 if macro_kill else mem_rdata
            mem_size = z4 if macro_kill else mem_size
            next_pc = z64 if macro_kill else next_pc
            checkpoint_id = u(6, 0) if macro_kill else checkpoint_id

        m.output(f"commit_fire{slot}", fire)
        m.output(f"commit_pc{slot}", pc)
        m.output(f"commit_rob{slot}", rob_idx)
        m.output(f"commit_op{slot}", op)
        m.output(f"commit_uop_uid{slot}", uop_uid)
        m.output(f"commit_parent_uop_uid{slot}", parent_uid)
        m.output(f"commit_block_uid{slot}", commit_block_uids[slot])
        m.output(f"commit_block_bid{slot}", commit_block_bids[slot])
        m.output(f"commit_core_id{slot}", commit_core_ids[slot])
        m.output(f"commit_is_bstart{slot}", commit_is_bstarts[slot])
        m.output(f"commit_is_bstop{slot}", commit_is_bstops[slot])
        m.output(f"commit_load_store_id{slot}", rob_load_store_ids[slot])
        m.output(f"commit_template_kind{slot}", template_kind)
        m.output(f"commit_value{slot}", val)
        m.output(f"commit_len{slot}", ln)
        m.output(f"commit_insn_raw{slot}", insn_raw)
        m.output(f"commit_wb_valid{slot}", wb_valid)
        m.output(f"commit_wb_rd{slot}", wb_rd)
        m.output(f"commit_wb_data{slot}", wb_data)
        m.output(f"commit_src0_valid{slot}", src0_valid)
        m.output(f"commit_src0_reg{slot}", src0_reg)
        m.output(f"commit_src0_data{slot}", src0_data)
        m.output(f"commit_src1_valid{slot}", src1_valid)
        m.output(f"commit_src1_reg{slot}", src1_reg)
        m.output(f"commit_src1_data{slot}", src1_data)
        m.output(f"commit_dst_valid{slot}", dst_valid)
        m.output(f"commit_dst_reg{slot}", dst_reg)
        m.output(f"commit_dst_data{slot}", dst_data)
        m.output(f"commit_mem_valid{slot}", mem_valid)
        m.output(f"commit_mem_is_store{slot}", mem_is_store)
        m.output(f"commit_mem_addr{slot}", mem_addr)
        m.output(f"commit_mem_wdata{slot}", mem_wdata)
        m.output(f"commit_mem_rdata{slot}", mem_rdata)
        m.output(f"commit_mem_size{slot}", mem_size)
        m.output(f"commit_trap_valid{slot}", trap_valid)
        m.output(f"commit_trap_cause{slot}", trap_cause)
        m.output(f"commit_next_pc{slot}", next_pc)
        m.output(f"commit_checkpoint_id{slot}", checkpoint_id)
