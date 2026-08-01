#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

for required_tool in sbt verilator python3; do
  if ! command -v "${required_tool}" >/dev/null 2>&1; then
    echo "error: ${required_tool} is required for the Zybo AXI probe" >&2
    exit 2
  fi
done

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

OUT_DIR="$(mktemp -d /tmp/linx-chisel-zybo-axi.XXXXXX)"
trap 'rm -rf -- "${OUT_DIR}"' EXIT
SV_DIR="${OUT_DIR}/sv"
mkdir -p "${SV_DIR}"

cd "${ROOT_DIR}/chisel"
sbt --server --batch --no-colors --mem "${LINX_CHISEL_SBT_MEM_MB}" \
  "runMain linxcore.fpga.zybo.EmitLinxAxi4Master --target-dir ${SV_DIR}"

TOP_SV="${SV_DIR}/LinxAxi4Master.sv"
if [[ ! -s "${TOP_SV}" ]]; then
  echo "error: repository emission did not create ${TOP_SV}" >&2
  exit 2
fi

mapfile -t SV_FILES < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' -print | sort)
if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: repository emission produced no SystemVerilog sources" >&2
  exit 2
fi

python3 - "${TOP_SV}" <<'PY'
import re
import sys
from pathlib import Path

top = Path(sys.argv[1])
text = re.sub(r"//.*", "", top.read_text(encoding="utf-8"))

expected = {
    "io_axi_ar_valid": ("output", 1),
    "io_axi_ar_ready": ("input", 1),
    "io_axi_ar_bits_id": ("output", 1),
    "io_axi_ar_bits_addr": ("output", 32),
    "io_axi_ar_bits_len": ("output", 8),
    "io_axi_ar_bits_size": ("output", 3),
    "io_axi_ar_bits_burst": ("output", 2),
    "io_axi_r_valid": ("input", 1),
    "io_axi_r_ready": ("output", 1),
    "io_axi_r_bits_id": ("input", 1),
    "io_axi_r_bits_data": ("input", 64),
    "io_axi_r_bits_resp": ("input", 2),
    "io_axi_r_bits_last": ("input", 1),
    "io_axi_aw_valid": ("output", 1),
    "io_axi_aw_ready": ("input", 1),
    "io_axi_aw_bits_id": ("output", 1),
    "io_axi_aw_bits_addr": ("output", 32),
    "io_axi_aw_bits_len": ("output", 8),
    "io_axi_aw_bits_size": ("output", 3),
    "io_axi_aw_bits_burst": ("output", 2),
    "io_axi_w_valid": ("output", 1),
    "io_axi_w_ready": ("input", 1),
    "io_axi_w_bits_data": ("output", 64),
    "io_axi_w_bits_strb": ("output", 8),
    "io_axi_w_bits_last": ("output", 1),
    "io_axi_b_valid": ("input", 1),
    "io_axi_b_ready": ("output", 1),
    "io_axi_b_bits_id": ("input", 1),
    "io_axi_b_bits_resp": ("input", 2),
}

header_match = re.search(r"module\s+LinxAxi4Master\s*\((.*?)\);", text, re.DOTALL)
if header_match is None:
    raise SystemExit("AXI port contract failure: missing LinxAxi4Master module header")

observed = {}
direction = None
width = 1
for item in header_match.group(1).split(","):
    declaration = " ".join(item.split())
    match = re.match(
        r"(?:(input|output)\s+)?(?:(?:wire|logic|reg)\s+)?"
        r"(?:\[(\d+)\s*:\s*(\d+)\]\s+)?([A-Za-z_][A-Za-z0-9_]*)$",
        declaration,
    )
    if match is None:
        raise SystemExit(f"AXI port contract failure: cannot parse declaration {declaration!r}")
    item_direction, high, low, name = match.groups()
    if item_direction is not None:
        direction = item_direction
        width = 1
    if high is not None:
        width = abs(int(high) - int(low)) + 1
    if direction is None:
        raise SystemExit(f"AXI port contract failure: {name} has no direction")
    observed[name] = (direction, width)

errors = []
for name, (want_direction, want_width) in expected.items():
    if name not in observed:
        errors.append(f"missing HP0 port declaration: {name}")
        continue
    got_direction, got_width = observed[name]
    if got_direction != want_direction or got_width != want_width:
        errors.append(
            f"{name}: expected {want_direction} width {want_width}, "
            f"observed {got_direction} width {got_width}"
        )

if errors:
    raise SystemExit("AXI port contract failure:\n- " + "\n- ".join(errors))
PY

verilator --lint-only --top-module LinxAxi4Master "${SV_FILES[@]}"

echo "zybo-axi-probe: PASS (repository emission, HP0 port contract, Verilator lint)"
