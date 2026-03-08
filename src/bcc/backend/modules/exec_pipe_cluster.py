from __future__ import annotations

from pycircuit import Circuit, module


def _pick_slot(pack, slot: int, width: int):
    return pack.slice(lsb=slot * width, width=width)


def _pack_bus(m: Circuit, values):
    return m.concat(*reversed(values))


@module(name="LinxCoreBackendExecPipe")
def build_backend_exec_pipe(
    m: Circuit,
    *,
    issue_w: int = 4,
    rob_w: int = 6,
    ptag_w: int = 6,
    bru_slot: int = 1,
    cmd_slot: int = 3,
) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const
    issue_meta_w = 1 + 64 + 64 + rob_w + ptag_w + 1 + 1 + 1 + 12 + 4
    issue_data_w = 64 * 5
    issue_bundle_w = issue_meta_w + issue_data_w
    w2_meta_w = 1 + rob_w + ptag_w + 1 + 1 + 1 + 4
    w2_data_w = 64 * 5

    flush_i = m.input("flush_i", width=1)
    issue_pack = m.input("issue_pack", width=issue_w * issue_bundle_w)
    aux_in_pack = m.input("aux_in_pack", width=22 + 64)

    stage_names = ("p1", "i1", "i2", "e1", "w1", "w2")
    field_widths = {
        "uid": 64,
        "pc": 64,
        "rob": rob_w,
        "pdst": ptag_w,
        "has_dst": 1,
        "is_load": 1,
        "is_store": 1,
        "value": 64,
        "addr": 64,
        "wdata": 64,
        "size": 4,
        "op": 12,
        "src0": 64,
        "src1": 64,
        "bru_checkpoint": 6,
        "bru_epoch": 16,
        "cmd_block_bid": 64,
    }

    regs: dict[str, dict[str, list[object]]] = {}
    for stage in stage_names:
        regs[stage] = {"valid": []}
        for field in field_widths:
            regs[stage][field] = []
        for slot in range(issue_w):
            regs[stage]["valid"].append(
                m.out(f"{stage}_valid_{slot}", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
            )
            for field, width in field_widths.items():
                regs[stage][field].append(
                    m.out(
                        f"{stage}_{field}_{slot}",
                        clk=clk,
                        rst=rst,
                        width=width,
                        init=c(0, width=width),
                        en=c(1, width=1),
                    )
                )

    for slot in range(issue_w):
        slot_bundle = _pick_slot(issue_pack, slot, issue_bundle_w)
        slot_meta = slot_bundle.slice(lsb=0, width=issue_meta_w)
        slot_data = slot_bundle.slice(lsb=issue_meta_w, width=issue_data_w)
        issue_fields = {
            "valid": slot_meta.slice(lsb=0, width=1),
            "uid": slot_meta.slice(lsb=1, width=64),
            "pc": slot_meta.slice(lsb=65, width=64),
            "rob": slot_meta.slice(lsb=129, width=rob_w),
            "pdst": slot_meta.slice(lsb=129 + rob_w, width=ptag_w),
            "has_dst": slot_meta.slice(lsb=129 + rob_w + ptag_w, width=1),
            "is_load": slot_meta.slice(lsb=130 + rob_w + ptag_w, width=1),
            "is_store": slot_meta.slice(lsb=131 + rob_w + ptag_w, width=1),
            "op": slot_meta.slice(lsb=132 + rob_w + ptag_w, width=12),
            "size": slot_meta.slice(lsb=144 + rob_w + ptag_w, width=4),
            "value": slot_data.slice(lsb=0, width=64),
            "addr": slot_data.slice(lsb=64, width=64),
            "wdata": slot_data.slice(lsb=128, width=64),
            "src0": slot_data.slice(lsb=192, width=64),
            "src1": slot_data.slice(lsb=256, width=64),
            "bru_checkpoint": c(0, width=6),
            "bru_epoch": c(0, width=16),
            "cmd_block_bid": c(0, width=64),
        }
        if slot == bru_slot:
            issue_fields["bru_checkpoint"] = aux_in_pack.slice(lsb=0, width=6)
            issue_fields["bru_epoch"] = aux_in_pack.slice(lsb=6, width=16)
        if slot == cmd_slot:
            issue_fields["cmd_block_bid"] = aux_in_pack.slice(lsb=22, width=64)

        for stage_idx, stage in enumerate(stage_names):
            prev_fields = issue_fields
            if stage_idx > 0:
                prev_stage = stage_names[stage_idx - 1]
                prev_fields = {"valid": regs[prev_stage]["valid"][slot].out()}
                for field in field_widths:
                    prev_fields[field] = regs[prev_stage][field][slot].out()

            next_valid = flush_i._select_internal(c(0, width=1), prev_fields["valid"])
            regs[stage]["valid"][slot].set(next_valid)
            for field, width in field_widths.items():
                next_value = next_valid._select_internal(prev_fields[field], c(0, width=width))
                regs[stage][field][slot].set(next_value)

    for stage in stage_names:
        for slot in range(issue_w):
            m.output(f"probe_{stage}_valid_{slot}", regs[stage]["valid"][slot].out())
            m.output(f"probe_{stage}_uid_{slot}", regs[stage]["uid"][slot].out())
            m.output(f"probe_{stage}_pc_{slot}", regs[stage]["pc"][slot].out())
            m.output(f"probe_{stage}_rob_{slot}", regs[stage]["rob"][slot].out())

    w2_meta = []
    w2_data = []
    for slot in range(issue_w):
        visible_valid = regs["w2"]["valid"][slot].out() & (~flush_i)
        w2_meta.append(
            m.concat(
                regs["w2"]["size"][slot].out(),
                regs["w2"]["is_store"][slot].out(),
                regs["w2"]["is_load"][slot].out(),
                regs["w2"]["has_dst"][slot].out(),
                regs["w2"]["pdst"][slot].out(),
                regs["w2"]["rob"][slot].out(),
                visible_valid,
            )
        )
        w2_data.append(
            m.concat(
                regs["w2"]["src1"][slot].out(),
                regs["w2"]["src0"][slot].out(),
                regs["w2"]["wdata"][slot].out(),
                regs["w2"]["addr"][slot].out(),
                regs["w2"]["value"][slot].out(),
            )
        )

    w2_bundle = [m.concat(w2_data[slot], w2_meta[slot]) for slot in range(issue_w)]
    m.output("w2_pack_o", _pack_bus(m, w2_bundle))

    bru_e1_valid = c(0, width=1)
    bru_e1_rob = c(0, width=rob_w)
    bru_e1_value = c(0, width=64)
    bru_e1_op = c(0, width=12)
    bru_e1_checkpoint = c(0, width=6)
    bru_e1_epoch = c(0, width=16)
    if 0 <= bru_slot < issue_w:
        bru_e1_valid = regs["e1"]["valid"][bru_slot].out() & (~flush_i)
        bru_e1_rob = regs["e1"]["rob"][bru_slot].out()
        bru_e1_value = regs["e1"]["value"][bru_slot].out()
        bru_e1_op = regs["e1"]["op"][bru_slot].out()
        bru_e1_checkpoint = regs["e1"]["bru_checkpoint"][bru_slot].out()
        bru_e1_epoch = regs["e1"]["bru_epoch"][bru_slot].out()
    bru_e1_pack = m.concat(bru_e1_epoch, bru_e1_checkpoint, bru_e1_op, bru_e1_value, bru_e1_rob, bru_e1_valid)

    cmd_w2_valid = c(0, width=1)
    cmd_w2_rob = c(0, width=rob_w)
    cmd_w2_value = c(0, width=64)
    cmd_w2_op = c(0, width=12)
    cmd_w2_block_bid = c(0, width=64)
    if 0 <= cmd_slot < issue_w:
        cmd_w2_valid = regs["w2"]["valid"][cmd_slot].out() & (~flush_i)
        cmd_w2_rob = regs["w2"]["rob"][cmd_slot].out()
        cmd_w2_value = regs["w2"]["value"][cmd_slot].out()
        cmd_w2_op = regs["w2"]["op"][cmd_slot].out()
        cmd_w2_block_bid = regs["w2"]["cmd_block_bid"][cmd_slot].out()
    cmd_w2_pack = m.concat(cmd_w2_block_bid, cmd_w2_op, cmd_w2_value, cmd_w2_rob, cmd_w2_valid)
    m.output("aux_out_pack_o", m.concat(cmd_w2_pack, bru_e1_pack))
