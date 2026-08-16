#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile


THIS = Path(__file__).resolve()
ROOT = THIS.parents[1]
SUPPORT_PATH = THIS.with_name("test_exec_uop_srcrtype.py")
SPEC = importlib.util.spec_from_file_location("icall_pyc_test_support", SUPPORT_PATH)
assert SPEC is not None and SPEC.loader is not None
support = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = support
SPEC.loader.exec_module(support)

from pycircuit import Circuit, Tb, module, testbench  # noqa: E402
from common.decode import decode_window  # noqa: E402
from common.exec_uop import exec_uop_comb  # noqa: E402
from common.isa import BK_FALL, BK_ICALL, OP_BSTART_ICALL  # noqa: E402
from common.util import make_consts  # noqa: E402
from bcc.backend.modules.commit_slot_step import (  # noqa: E402
    COMMIT_SLOT_INPUT_FIELD_SPECS,
    COMMIT_SLOT_LIVE_FIELD_SPECS,
    COMMIT_SLOT_TRACE_FIELD_SPECS,
    _unpack_fields,
    build_commit_slot_step,
)


def width(fields: tuple[tuple[str, int], ...]) -> int:
    return sum(bits for _name, bits in fields)


def pack(fields: tuple[tuple[str, int], ...], values: dict[str, int]) -> int:
    result = 0
    shift = 0
    for name, bits in fields:
        result |= (values.get(name, 0) & ((1 << bits) - 1)) << shift
        shift += bits
    return result


def unpack(fields: tuple[tuple[str, int], ...], value: int) -> dict[str, int]:
    result: dict[str, int] = {}
    shift = 0
    for name, bits in fields:
        result[name] = (value >> shift) & ((1 << bits) - 1)
        shift += bits
    return result


def case(
    *,
    target: int,
    valid_bpcn: bool,
    proven_bstart: bool,
    condition: bool,
    trap_pending: bool = False,
) -> int:
    return pack(
        COMMIT_SLOT_INPUT_FIELD_SPECS,
        {
            "can_run": 1,
            "allow_macro": 1,
            "commit_allow": 1,
            "commit_cond": int(condition),
            "commit_tgt": target,
            "commit_tgt_valid": int(valid_bpcn),
            "commit_tgt_proven": int(proven_bstart),
            "trap_pending": int(trap_pending),
            "trap_rob": 3,
            "br_kind": BK_FALL,
            "block_head": 1,
            "rob_valid": 1,
            "rob_done": 1,
            "rob_pc": 0x400,
            "rob_op": OP_BSTART_ICALL,
            "rob_len": 4,
            "rob_is_bstart": 1,
            "rob_boundary_kind": BK_ICALL,
            "rob_block_uid": 0x11,
            "rob_block_bid": 0x11,
            "commit_idx": 3,
        },
    )


CASES = (
    case(target=0x900, valid_bpcn=True, proven_bstart=True, condition=False),
    case(target=0x900, valid_bpcn=False, proven_bstart=True, condition=True),
    case(target=0x902, valid_bpcn=True, proven_bstart=False, condition=True),
    case(target=0x901, valid_bpcn=True, proven_bstart=True, condition=True),
    case(
        target=0x902,
        valid_bpcn=True,
        proven_bstart=False,
        condition=True,
        trap_pending=True,
    ),
)
ICALL_UIMM5 = 13
ICALL_RAW = 0x50166001 | (ICALL_UIMM5 << 22)


@module(name="LinxCoreIcallBpcnProbe")
def build(m: Circuit) -> None:
    m.input("clk", width=1)
    m.input("rst", width=1)
    pack_i = m.input("pack_i", width=width(COMMIT_SLOT_INPUT_FIELD_SPECS))
    insn = m.input("insn", width=64)
    exec_pc = m.input("exec_pc", width=64)
    exec_zero64 = m.input("exec_zero64", width=64)
    exec_srcr_type = m.input("exec_srcr_type", width=2)
    exec_shamt = m.input("exec_shamt", width=6)
    decoded = decode_window(m, insn)
    consts = make_consts(m)
    executed = exec_uop_comb(
        m,
        op=decoded.op,
        pc=exec_pc,
        imm=decoded.imm,
        srcl_val=exec_zero64,
        srcr_val=exec_zero64,
        srcr_type=exec_srcr_type,
        shamt=exec_shamt,
        srcp_val=exec_zero64,
        consts=consts,
    )
    slot = m.instance_auto(build_commit_slot_step, name="commit_slot", pack_i=pack_i)
    trace = _unpack_fields(slot["trace_pack_o"], COMMIT_SLOT_TRACE_FIELD_SPECS)
    live = _unpack_fields(slot["live_pack_o"], COMMIT_SLOT_LIVE_FIELD_SPECS)
    m.output("commit_fire", trace["commit_fire"])
    m.output("commit_effect_fire", trace["commit_effect_fire"])
    m.output("icall_fault_set", trace["icall_fault_set"])
    m.output("icall_fault_rob", trace["icall_fault_rob"])
    m.output("commit_tgt", live["commit_tgt"])
    m.output("commit_cond", live["commit_cond"])
    m.output("commit_tgt_valid", live["commit_tgt_valid"])
    m.output("br_kind", live["br_kind"])
    m.output("decoded_op", decoded.op)
    m.output("decoded_regdst", decoded.regdst)
    m.output("decoded_imm", decoded.imm)
    m.output("return_label", executed.alu)


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=0, cycles_deasserted=0)
    for cycle, packed in enumerate(CASES):
        t.drive("pack_i", packed, at=cycle)
        t.drive("insn", ICALL_RAW, at=cycle)
        t.drive("exec_pc", 0x4000, at=cycle)
        t.drive("exec_zero64", 0, at=cycle)
        t.drive("exec_srcr_type", 0, at=cycle)
        t.drive("exec_shamt", 0, at=cycle)
        t.print(
            f"ICALL case={cycle}",
            at=cycle,
            ports=(
                "commit_fire",
                "commit_effect_fire",
                "icall_fault_set",
                "icall_fault_rob",
                "commit_tgt",
                "commit_cond",
                "commit_tgt_valid",
                "br_kind",
                "decoded_op",
                "decoded_regdst",
                "decoded_imm",
                "return_label",
            ),
        )
    t.finish(at=len(CASES) + 1)


ROW = re.compile(
    r".*ICALL case=(?P<case>\d+).*commit_fire=(?P<fire>0x[0-9a-fA-F]+|\d+) "
    r"commit_effect_fire=(?P<effect>0x[0-9a-fA-F]+|\d+) "
    r"icall_fault_set=(?P<fault>0x[0-9a-fA-F]+|\d+) "
    r"icall_fault_rob=(?P<fault_rob>0x[0-9a-fA-F]+|\d+) "
    r"commit_tgt=(?P<tgt>0x[0-9a-fA-F]+|\d+) "
    r"commit_cond=(?P<cond>0x[0-9a-fA-F]+|\d+) "
    r"commit_tgt_valid=(?P<tgt_valid>0x[0-9a-fA-F]+|\d+) "
    r"br_kind=(?P<kind>0x[0-9a-fA-F]+|\d+) "
    r"decoded_op=(?P<op>0x[0-9a-fA-F]+|\d+) "
    r"decoded_regdst=(?P<regdst>0x[0-9a-fA-F]+|\d+) "
    r"decoded_imm=(?P<imm>0x[0-9a-fA-F]+|\d+) "
    r"return_label=(?P<label>0x[0-9a-fA-F]+|\d+)$"
)


def main() -> int:
    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["PYTHONPATH"] = os.pathsep.join(
        [str(support.PYC_FRONTEND), str(ROOT / "src"), env.get("PYTHONPATH", "")]
    )
    env["PYCC"] = support._find_pycc(env)
    with tempfile.TemporaryDirectory(prefix="linxcore-icall-bpcn-") as td:
        out_dir = Path(td) / "pyc"
        support._run_checked(
            [
                support._find_python(),
                "-m",
                "pycircuit.cli",
                "build",
                str(THIS),
                "--out-dir",
                str(out_dir),
                "--target",
                "cpp",
                "--jobs",
                os.environ.get("PYC_SIM_JOBS", "4"),
                "--logic-depth",
                os.environ.get("PYC_SIM_LOGIC_DEPTH", "512"),
                "--profile",
                "dev",
            ],
            cwd=ROOT,
            env=env,
        )
        manifest = json.loads((out_dir / "project_manifest.json").read_text(encoding="utf-8"))
        proc = support._run_capture([manifest["cpp_executable"]], cwd=out_dir, env=env)

    rows = []
    for line in proc.stdout.splitlines():
        match = ROW.match(line.strip())
        if match:
            rows.append({key: int(value, 0) for key, value in match.groupdict().items()})
    assert len(rows) == 5, f"expected five ICALL rows, got {rows}"
    assert rows[0]["fire"] == 1
    assert rows[0]["effect"] == 1
    assert rows[0]["fault"] == 0
    assert rows[0]["tgt"] == 0x900
    assert rows[0]["cond"] == 0
    assert rows[0]["tgt_valid"] == 1
    assert rows[0]["kind"] == BK_ICALL
    assert rows[0]["op"] == OP_BSTART_ICALL
    assert rows[0]["regdst"] == 1
    assert rows[0]["imm"] == ICALL_UIMM5 << 1
    assert rows[0]["label"] == 0x4000 + 2 + (ICALL_UIMM5 << 1)
    assert rows[1]["fire"] == 0
    assert rows[1]["effect"] == 0
    assert rows[1]["fault"] == 1
    assert rows[1]["fault_rob"] == 3
    assert rows[2]["fire"] == 0
    assert rows[2]["fault"] == 1
    assert rows[3]["fire"] == 0
    assert rows[3]["fault"] == 1
    # Once the precise trap record names this ROB row, it may retire without
    # publishing CMAP/BARG/redirect effects.
    assert rows[4]["fire"] == 1
    assert rows[4]["effect"] == 0
    assert rows[4]["fault"] == 0
    print("ok: fused ICALL atomically validates and snapshots retiring BARG.BPCN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
