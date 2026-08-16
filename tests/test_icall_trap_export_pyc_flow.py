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
SPEC = importlib.util.spec_from_file_location("icall_trap_export_support", SUPPORT_PATH)
assert SPEC is not None and SPEC.loader is not None
support = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = support
SPEC.loader.exec_module(support)

from pycircuit import Circuit, Tb, module, testbench  # noqa: E402
from common.isa import OP_BSTART_ICALL, TRAP_BRU_RECOVERY_NOT_BSTART  # noqa: E402
from bcc.backend.modules.recovery_checks import build_precise_trap_control_stage  # noqa: E402
from bcc.backend.modules.trace_export_core import (  # noqa: E402
    _COMMIT_TRACE_STBUF_ENTRY_SPECS,
    _commit_trace_macro_field_specs,
    _commit_trace_raw_slot_field_specs,
    _trace_field_width_sum,
    build_commit_trace_export,
)


ROB_W = 6
SOURCE_PC = 0x400
FAULT_ROB = 3
E_BLOCK_CFI_BAD_TARGET = 0x0101


def pack(fields: tuple[tuple[str, int], ...], values: dict[str, int]) -> int:
    result = 0
    shift = 0
    for name, bits in fields:
        result |= (values.get(name, 0) & ((1 << bits) - 1)) << shift
        shift += bits
    return result


RAW_SPECS = _commit_trace_raw_slot_field_specs(rob_w=ROB_W)
MACRO_SPECS = _commit_trace_macro_field_specs(rob_w=ROB_W)
RAW_FAULT = pack(
    RAW_SPECS,
    {
        "fire": 0,
        "pc": SOURCE_PC,
        "next_pc": SOURCE_PC,
        "rob": FAULT_ROB,
        "op": OP_BSTART_ICALL,
        "len": 4,
        "is_bstart": 1,
    },
)
RAW_TRAP_RETIRE = pack(
    RAW_SPECS,
    {
        "fire": 1,
        "pc": SOURCE_PC,
        "next_pc": SOURCE_PC,
        "rob": FAULT_ROB,
        "op": OP_BSTART_ICALL,
        "len": 4,
        "is_bstart": 1,
    },
)


@module(name="LinxCoreIcallTrapExportProbe")
def build(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    c = m.const

    fault_pending = m.input("fault_pending", width=1)
    fault_rob = m.input("fault_rob", width=ROB_W)
    fault_pc = m.input("fault_pc", width=64)
    raw_pack = m.input("raw_pack", width=_trace_field_width_sum(RAW_SPECS))

    trap_pending = m.out("trap_pending", clk=clk, rst=rst, width=1, init=c(0, width=1), en=c(1, width=1))
    trap_rob = m.out("trap_rob", clk=clk, rst=rst, width=ROB_W, init=c(0, width=ROB_W), en=c(1, width=1))
    trap_cause = m.out("trap_cause", clk=clk, rst=rst, width=32, init=c(0, width=32), en=c(1, width=1))
    trap_arg0 = m.out("trap_arg0", clk=clk, rst=rst, width=64, init=c(0, width=64), en=c(1, width=1))

    raw_fire = raw_pack.slice(lsb=0, width=1)
    raw_rob_lsb = 1 + 64 + 64
    raw_rob = raw_pack.slice(lsb=raw_rob_lsb, width=ROB_W)
    trap_ctrl = m.instance_auto(
        build_precise_trap_control_stage,
        name="precise_trap_control_stage",
        params={"commit_w": 1, "rob_w": ROB_W},
        do_flush=c(0, width=1),
        state_trap_pending=trap_pending.out(),
        state_trap_rob=trap_rob.out(),
        state_trap_cause=trap_cause.out(),
        state_trap_arg0=trap_arg0.out(),
        fault_pending=fault_pending,
        fault_rob=fault_rob,
        fault_arg0=fault_pc,
        commit_fire0=raw_fire,
        commit_idx0=raw_rob,
    )
    trap_pending.set(trap_ctrl["trap_pending_next"])
    trap_rob.set(trap_ctrl["trap_rob_next"])
    trap_cause.set(trap_ctrl["trap_cause_next"])
    trap_arg0.set(trap_ctrl["trap_arg0_next"])

    trace = m.instance_auto(
        build_commit_trace_export,
        name="architectural_commit_trace",
        params={"commit_w": 1, "max_commit_slots": 1, "sq_entries": 1, "rob_w": ROB_W},
        raw_pack_i=raw_pack,
        macro_pack_i=c(0, width=_trace_field_width_sum(MACRO_SPECS)),
        stbuf_pack_i=c(0, width=_trace_field_width_sum(_COMMIT_TRACE_STBUF_ENTRY_SPECS)),
        shadow_boundary_fire_i=c(0, width=1),
        shadow_boundary_fire1_i=c(0, width=1),
        post_macro_handoff_i=c(0, width=1),
        macro_wait_commit_i=c(0, width=1),
        macro_pc_i=c(0, width=64),
        trap_pending_i=trap_pending.out(),
        trap_rob_i=trap_rob.out(),
        trap_cause_i=trap_cause.out(),
    )
    m.output("state_pending", trap_pending.out())
    m.output("state_rob", trap_rob.out())
    m.output("state_internal_cause", trap_cause.out())
    m.output("state_arg0", trap_arg0.out())
    m.output("commit_fire", trace["commit_fire0"])
    m.output("commit_trap_valid", trace["commit_trap_valid0"])
    m.output("commit_trap_cause", trace["commit_trap_cause0"])
    m.output("commit_trap_arg0", trace["commit_trap_arg0"])
    m.output("commit_trap_bi", trace["commit_trap_bi"])
    m.output("commit_wb_valid", trace["commit_wb_valid0"])
    m.output("commit_mem_valid", trace["commit_mem_valid0"])


@testbench
def tb(t: Tb) -> None:
    t.clock("clk")
    t.reset("rst", cycles_asserted=0, cycles_deasserted=0)
    t.drive("fault_pending", 1, at=0)
    t.drive("fault_rob", FAULT_ROB, at=0)
    t.drive("fault_pc", SOURCE_PC, at=0)
    t.drive("raw_pack", RAW_FAULT, at=0)
    # The backend's deferred-fault owner holds this request until the precise
    # trap controller observes retirement and returns fault_pending_next=0.
    t.drive("fault_pending", 1, at=1)
    t.drive("fault_rob", FAULT_ROB, at=1)
    t.drive("fault_pc", SOURCE_PC, at=1)
    t.drive("raw_pack", RAW_TRAP_RETIRE, at=1)
    for cycle in range(2):
        t.print(
            f"ICALL trap cycle={cycle}",
            at=cycle,
            ports=(
                "state_pending",
                "state_rob",
                "state_internal_cause",
                "state_arg0",
                "commit_fire",
                "commit_trap_valid",
                "commit_trap_cause",
                "commit_trap_arg0",
                "commit_trap_bi",
                "commit_wb_valid",
                "commit_mem_valid",
            ),
        )
    t.finish(at=3)


ROW = re.compile(
    r".*ICALL trap cycle=(?P<cycle>\d+).*state_pending=(?P<pending>0x[0-9a-fA-F]+|\d+) "
    r"state_rob=(?P<rob>0x[0-9a-fA-F]+|\d+) "
    r"state_internal_cause=(?P<internal>0x[0-9a-fA-F]+|\d+) "
    r"state_arg0=(?P<state_arg0>0x[0-9a-fA-F]+|\d+) "
    r"commit_fire=(?P<fire>0x[0-9a-fA-F]+|\d+) "
    r"commit_trap_valid=(?P<trap>0x[0-9a-fA-F]+|\d+) "
    r"commit_trap_cause=(?P<cause>0x[0-9a-fA-F]+|\d+) "
    r"commit_trap_arg0=(?P<arg0>0x[0-9a-fA-F]+|\d+) "
    r"commit_trap_bi=(?P<bi>0x[0-9a-fA-F]+|\d+) "
    r"commit_wb_valid=(?P<wb>0x[0-9a-fA-F]+|\d+) "
    r"commit_mem_valid=(?P<mem>0x[0-9a-fA-F]+|\d+)$"
)


def main() -> int:
    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["PYTHONPATH"] = os.pathsep.join(
        [str(support.PYC_FRONTEND), str(ROOT / "src"), env.get("PYTHONPATH", "")]
    )
    env["PYCC"] = support._find_pycc(env)
    with tempfile.TemporaryDirectory(prefix="linxcore-icall-trap-export-") as td:
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
    assert len(rows) == 2, f"expected two ICALL trap rows, got {rows}"
    assert rows[0]["pending"] == 1
    assert rows[0]["rob"] == FAULT_ROB
    assert rows[0]["internal"] == TRAP_BRU_RECOVERY_NOT_BSTART
    assert rows[0]["state_arg0"] == SOURCE_PC
    assert rows[0]["fire"] == 0
    assert rows[0]["trap"] == 0
    assert rows[0]["wb"] == 0
    assert rows[0]["mem"] == 0
    assert rows[1]["pending"] == 1
    assert rows[1]["rob"] == FAULT_ROB
    assert rows[1]["internal"] == TRAP_BRU_RECOVERY_NOT_BSTART
    assert rows[1]["state_arg0"] == SOURCE_PC
    assert rows[1]["fire"] == 1
    assert rows[1]["trap"] == 1
    assert rows[1]["cause"] == E_BLOCK_CFI_BAD_TARGET
    assert rows[1]["arg0"] == SOURCE_PC
    assert rows[1]["bi"] == 0
    assert rows[1]["wb"] == 0
    assert rows[1]["mem"] == 0
    print("ok: fused ICALL bad target retires an architectural CFI envelope")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
