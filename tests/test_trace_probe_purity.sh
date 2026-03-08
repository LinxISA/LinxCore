#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

for deprecated in \
  "${ROOT_DIR}/src/top/modules/trace_event.py" \
  "${ROOT_DIR}/src/bcc/backend/modules/trace_occ.py" \
  "${ROOT_DIR}/src/top/modules/dfx_pipeview.py" \
  "${ROOT_DIR}/src/top/modules/stage_probe.py"; do
  if [[ -e "${deprecated}" ]]; then
    echo "error: deprecated DFX file still exists: ${deprecated#${ROOT_DIR}/}" >&2
    exit 1
  fi
done

if rg -n "m\\.debug\\(|m\\.debug_bundle\\(|m\\.debug_probe\\(|m\\.debug_occ\\(|m\\.probe\\(" \
  "${ROOT_DIR}/src" \
  "${ROOT_DIR}/tb" \
  "${ROOT_DIR}/tools" \
  >/dev/null; then
  echo "error: legacy debug/probe authoring is still present in LinxCore" >&2
  exit 1
fi

if rg -n "dbg__occ_|dut\\.dbg__occ_" \
  "${ROOT_DIR}/tb/tb_linxcore_top.cpp" \
  "${ROOT_DIR}/tools" \
  >/dev/null; then
  echo "error: LinxCore TB/tooling still references legacy dbg__occ paths" >&2
  exit 1
fi

if ! rg -n "probe\\.pipeview|probe\\.block|probe\\.commit" \
  "${ROOT_DIR}/tb/tb_linxcore_top.cpp" \
  >/dev/null; then
  echo "error: LinxCore TB is not consuming the new probe namespaces" >&2
  exit 1
fi

if rg -n "dut\\.commit_|dut\\.brob_|dut\\.bctrl_|dut\\.replay_cause|dut\\.redirect_" \
  "${ROOT_DIR}/tb/tb_linxcore_top.cpp" \
  >/dev/null; then
  echo "error: LinxCore TB still uses direct commit/block trace signals instead of probe.*" >&2
  exit 1
fi

for flow in \
  "${ROOT_DIR}/tests/test_coremark_crosscheck_1000.sh" \
  "${ROOT_DIR}/tools/image/run_linxcore_benchmarks.sh"; do
  if [[ -e "${flow}" ]] && rg -n "PYC_LINXTRACE=1|PYC_LINXTRACE_PATH" "${flow}" >/dev/null; then
    echo "error: canonical flows must not depend on direct runtime linxtrace writing: ${flow#${ROOT_DIR}/}" >&2
    exit 1
  fi
done

TMP_DIR="$(mktemp -d -t trace_probe_purity.XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

PROBE_SMOKE_PY="${TMP_DIR}/probe_contract_smoke.py"
BUILD_DIR="${TMP_DIR}/build"
cat > "${PROBE_SMOKE_PY}" <<'PY'
from __future__ import annotations

from pycircuit import Circuit, ProbeBuilder, ProbeView, Tb, TbProbes, module, probe, testbench


@module
def build(m: Circuit) -> None:
    clk = m.clock("clk")
    rst = m.reset("rst")
    in_x = m.input("in_x", width=8)
    q = m.out("q", clk=clk, rst=rst, width=8, init=0)
    q.set(in_x)
    m.output("out_y", q.out())


build.__pycircuit_name__ = "probe_contract_smoke"


@probe(target=build, name="pv")
def pv(p: ProbeBuilder, dut: ProbeView) -> None:
    p.emit(
        "lane0",
        {
            "in_x": dut.read("in_x"),
            "q": dut.read("q"),
        },
        at="tick",
        tags={"family": "pv", "stage": "smoke", "lane": 0},
    )


@testbench
def tb(t: Tb, probes: TbProbes) -> None:
    _ = probes["dut:probe.pv.lane0.in_x"]
    _ = probes["dut:probe.pv.lane0.q"]
    t.clock("clk")
    t.reset("rst", cycles_asserted=2, cycles_deasserted=0)
    t.timeout(4)
    t.drive("in_x", 0x12, at=0)
    t.finish(at=1)
PY

PYTHONPATH="/Users/zhoubot/linx-isa/tools/pyCircuit/compiler/frontend" \
  python3 -m pycircuit.cli build "${PROBE_SMOKE_PY}" --out-dir "${BUILD_DIR}" --target cpp --jobs 4 >/dev/null

python3 - "${BUILD_DIR}" <<'PY'
import json
import sys
from pathlib import Path

out_dir = Path(sys.argv[1]).resolve()
project_manifest = json.loads((out_dir / "project_manifest.json").read_text(encoding="utf-8"))
probe_manifest = json.loads((out_dir / "probe_manifest.json").read_text(encoding="utf-8"))
probe_plan = json.loads((out_dir / "probe_plan.json").read_text(encoding="utf-8"))
probe_json = json.loads((out_dir / "device" / "probes" / "pv.json").read_text(encoding="utf-8"))

project_probes = project_manifest.get("probes", [])
if not isinstance(project_probes, list) or not project_probes:
    raise SystemExit("project_manifest.json is missing the top-level probes section")

probe_entries = [p for p in probe_manifest.get("probes", []) if isinstance(p, dict)]
paths = {str(p.get("canonical_path", "")) for p in probe_entries}
required = {
    "dut:probe.pv.lane0.in_x",
    "dut:probe.pv.lane0.q",
}
missing = sorted(required - paths)
if missing:
    raise SystemExit(f"probe_manifest.json missing resolved probe paths: {missing!r}")
if any("dbg__" in path for path in paths):
    raise SystemExit("probe_manifest.json still contains legacy dbg__ paths")

aliases = probe_plan.get("aliases", [])
if not isinstance(aliases, list) or len(aliases) != 2:
    raise SystemExit(f"probe_plan.json alias count mismatch: {len(aliases) if isinstance(aliases, list) else aliases!r}")
alias_paths = {str(a.get("canonical_path", "")) for a in aliases if isinstance(a, dict)}
if alias_paths != required:
    raise SystemExit(f"probe_plan.json aliases mismatch: {sorted(alias_paths)!r}")

if probe_json.get("name") != "pv":
    raise SystemExit(f"device/probes/pv.json name mismatch: {probe_json.get('name')!r}")
leaf_paths = {
    str(leaf.get("canonical_path", ""))
    for leaf in probe_json.get("leaves", [])
    if isinstance(leaf, dict)
}
if leaf_paths != required:
    raise SystemExit(f"device/probes/pv.json leaves mismatch: {sorted(leaf_paths)!r}")

print("trace probe purity: ok")
PY
