#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PYC_ROOT="/Users/zhoubot/pyCircuit"
CALLFRAME_SIZE_RAW="${LINXCORE_CALLFRAME_SIZE:-0}"
CALLFRAME_SIZE="$(
python3 - <<'PY' "${CALLFRAME_SIZE_RAW}"
import sys
s = sys.argv[1]
try:
    v = int(s, 0)
except ValueError:
    v = 0
if v < 0 or (v & 0x7):
    v = 0
print(v & ((1 << 64) - 1))
PY
)"

# Keep opcode ids/meta synchronized with QEMU decode trees.
python3 "${ROOT_DIR}/tools/generate/extract_qemu_opcode_matrix.py" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx \
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"
python3 "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx
python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"

if [[ -f "${PYC_ROOT}/scripts/lib.sh" ]]; then
  # Legacy pyCircuit tree layout.
  source "${PYC_ROOT}/scripts/lib.sh"
elif [[ -f "${PYC_ROOT}/flows/scripts/lib.sh" ]]; then
  # Current pyCircuit tree layout.
  source "${PYC_ROOT}/flows/scripts/lib.sh"
else
  PYC_COMPILE="${PYC_ROOT}/build-top/bin/pyc-compile"
fi

if [[ -z "${PYC_COMPILE:-}" && -x "${PYC_ROOT}/build-top/bin/pyc-compile" ]]; then
  PYC_COMPILE="${PYC_ROOT}/build-top/bin/pyc-compile"
fi

if [[ -z "${PYC_COMPILE:-}" ]]; then
  if command -v pyc_find_pyc_compile >/dev/null 2>&1; then
    if ! pyc_find_pyc_compile; then
      PYC_COMPILE="${PYC_ROOT}/build-top/bin/pyc-compile"
    fi
  else
    PYC_COMPILE="${PYC_ROOT}/build-top/bin/pyc-compile"
  fi
fi

if [[ ! -x "${PYC_COMPILE}" ]]; then
  echo "error: pyc-compile not found: ${PYC_COMPILE}" >&2
  exit 1
fi

OUT_CPP="${ROOT_DIR}/generated/cpp/linxcore_top"
OUT_V="${ROOT_DIR}/generated/verilog/linxcore_top"
mkdir -p "${OUT_CPP}" "${OUT_V}"

# IMPORTANT: incremental build performance
# --------------------------------------
# Do NOT wipe generated outputs on every regeneration.
# Deleting all .cpp/.hpp forces a full C++ rebuild (minutes) even when the design is unchanged.
# Instead, emit into temp dirs and only update files whose contents actually changed.
OUT_CPP_TMP="$(mktemp -d -t linxcore_cpp.XXXXXX)"
OUT_V_TMP="$(mktemp -d -t linxcore_v.XXXXXX)"
trap 'rm -rf "${OUT_CPP_TMP}" "${OUT_V_TMP}" "${TMP_PYC}"' EXIT

sync_dir() {
  local src="$1"
  local dst="$2"
  mkdir -p "${dst}"

  # Remove files that no longer exist in src (only for the file types we generate).
  find "${dst}" -maxdepth 1 -type f \( -name '*.hpp' -o -name '*.cpp' -o -name '*.json' -o -name '*.stats.json' -o -name '*.v' -o -name '*.ys' \) -print0 \
    | while IFS= read -r -d '' f; do
        base="$(basename "${f}")"
        if [[ ! -f "${src}/${base}" ]]; then
          rm -f "${f}"
        fi
      done

  # Copy in changed/new files; preserve timestamps when identical to avoid triggering recompilation.
  # Prefer rsync if available.
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --checksum --delete \
      --include='*.hpp' --include='*.cpp' --include='*.json' --include='*.stats.json' --include='*.v' --include='*.ys' \
      --exclude='*' \
      "${src}/" "${dst}/"
  else
    # Fallback: content-compare copy.
    for f in "${src}"/*; do
      [[ -f "${f}" ]] || continue
      b="$(basename "${f}")"
      case "${b}" in
        *.hpp|*.cpp|*.json|*.stats.json|*.v|*.ys)
          if [[ -f "${dst}/${b}" ]] && cmp -s "${f}" "${dst}/${b}"; then
            :
          else
            cp -f "${f}" "${dst}/${b}"
          fi
          ;;
      esac
    done
  fi
}

LOGIC_DEPTH="${PYC_LOGIC_DEPTH:-128}"

TMP_PYC="$(mktemp -t linxcore_top.XXXXXX.pyc)"

PYC_PYTHON_DIR="${PYC_ROOT}/python"
if [[ ! -d "${PYC_PYTHON_DIR}/pycircuit" && -d "${PYC_ROOT}/compiler/frontend/pycircuit" ]]; then
  PYC_PYTHON_DIR="${PYC_ROOT}/compiler/frontend"
fi

EMIT_PARAM_ARGS=()
while IFS='=' read -r key value; do
  [[ -z "${key}" ]] && continue
  [[ "${key}" != PYC_PARAM_* ]] && continue
  param_name="${key#PYC_PARAM_}"
  param_name="$(printf '%s' "${param_name}" | tr '[:upper:]' '[:lower:]')"
  [[ -z "${param_name}" ]] && continue
  EMIT_PARAM_ARGS+=(--param "${param_name}=${value}")
done < <(env)

if [[ "${#EMIT_PARAM_ARGS[@]}" -gt 0 ]]; then
  PYTHONDONTWRITEBYTECODE=1 \
  LINXCORE_CALLFRAME_SIZE="${CALLFRAME_SIZE}" \
  PYTHONPATH="${PYC_PYTHON_DIR}:${ROOT_DIR}/src" \
  python3 -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}" "${EMIT_PARAM_ARGS[@]}"
else
  PYTHONDONTWRITEBYTECODE=1 \
  LINXCORE_CALLFRAME_SIZE="${CALLFRAME_SIZE}" \
  PYTHONPATH="${PYC_PYTHON_DIR}:${ROOT_DIR}/src" \
  python3 -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}"
fi

TRY_V_OUTDIR="${LINXCORE_TRY_V_OUTDIR:-0}"
if [[ "${TRY_V_OUTDIR}" == "1" ]]; then
  if ! "${PYC_COMPILE}" "${TMP_PYC}" --emit=verilog --logic-depth="${LOGIC_DEPTH}" --out-dir="${OUT_V_TMP}" >/dev/null 2>&1; then
    MONO_V="${OUT_V_TMP}/linxcore_top.v"
    "${PYC_COMPILE}" "${TMP_PYC}" --emit=verilog --logic-depth="${LOGIC_DEPTH}" -o "${MONO_V}"
    python3 "${ROOT_DIR}/tools/generate/split_verilog_modules.py" \
      --src "${MONO_V}" \
      --out-dir "${OUT_V_TMP}" \
      --top "linxcore_top"
  fi
else
  MONO_V="${OUT_V_TMP}/linxcore_top.v"
  "${PYC_COMPILE}" "${TMP_PYC}" --emit=verilog --logic-depth="${LOGIC_DEPTH}" -o "${MONO_V}"
  python3 "${ROOT_DIR}/tools/generate/split_verilog_modules.py" \
    --src "${MONO_V}" \
    --out-dir "${OUT_V_TMP}" \
    --top "linxcore_top"
fi

# Default shard thresholds tuned for developer iteration on large designs.
# Smaller shards reduce single-TU compiler stress (esp. JanusBccBackendCompat tick) and
# improve incremental rebuild latency.
CPP_SHARD_LINES="${PYC_CPP_SHARD_THRESHOLD_LINES:-30000}"
CPP_SHARD_BYTES="${PYC_CPP_SHARD_THRESHOLD_BYTES:-1048576}"
"${PYC_COMPILE}" "${TMP_PYC}" \
  --emit=cpp \
  --logic-depth="${LOGIC_DEPTH}" \
  --out-dir="${OUT_CPP_TMP}" \
  --cpp-split=module \
  --cpp-shard-threshold-lines="${CPP_SHARD_LINES}" \
  --cpp-shard-threshold-bytes="${CPP_SHARD_BYTES}"

# Sync temp outputs into the stable generated directories.
sync_dir "${OUT_CPP_TMP}" "${OUT_CPP}"
sync_dir "${OUT_V_TMP}" "${OUT_V}"

printf '%s\n' "${CALLFRAME_SIZE}" > "${OUT_CPP}/.callframe_size"
printf '%s\n' "${CALLFRAME_SIZE}" > "${OUT_V}/.callframe_size"

python3 - <<'PY' "${OUT_CPP}/manifest.json" "${OUT_V}/manifest.json"
from __future__ import annotations

import json
import sys
from pathlib import Path

required = [
    "linxcore_top",
    "JanusBccIfuF0Top",
    "JanusBccIfuF1Top",
    "JanusBccIfuICacheTop",
    "JanusBccIfuF2Top",
    "JanusBccIfuCtrlTop",
    "JanusBccIfuF3Top",
    "JanusBccIfuF4Top",
    "JanusBccOooDec1Top",
    "JanusBccOooDec2Top",
    "JanusBccOooRenTop",
    "JanusBccOooS1Top",
    "JanusBccOooS2Top",
    "JanusBccIexTop",
    "JanusBccOooRobTop",
    "JanusBccOooFlushTop",
    "JanusBccOooRenuTop",
    "JanusBccLsuLiqTop",
    "JanusBccLsuLhqTop",
    "JanusBccLsuStqTop",
    "JanusBccLsuScbTop",
    "JanusBccLsuL1DTop",
    "JanusBccLsuMdbTop",
    "JanusBccBisqTop",
    "JanusBccBrenuTop",
    "JanusBccBctrlTop",
    "JanusBccBrobTop",
    "JanusTmuNocNodeTop",
    "JanusTmuTileRegTop",
    "JanusTmaTop",
    "JanusCubeTop",
    "JanusTauTop",
    "LinxCoreVecTop",
]

for manifest_path in [Path(sys.argv[1]), Path(sys.argv[2])]:
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    raw_mods = data.get("cpp_modules", []) + data.get("verilog_modules", [])
    mods = set()
    for item in raw_mods:
        stem = item.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        mods.add(stem)
    missing = [m for m in required if m not in mods]
    if missing:
        raise SystemExit(f"error: {manifest_path} missing required modules: {', '.join(missing)}")

print("manifest module split check passed")
PY

echo "${OUT_CPP}"
echo "${OUT_V}"
