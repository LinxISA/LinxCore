#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
QEMU_LINX_DIR="${QEMU_LINX_DIR:-${LINX_ROOT}/emulator/qemu/target/linx}"

find_python_bin() {
  if [[ -n "${PYC_PYTHON:-}" && -x "${PYC_PYTHON}" ]]; then
    echo "${PYC_PYTHON}"
    return 0
  fi
  local cand
  for cand in \
    "${PYC_PYTHON_BIN:-}" \
    "/opt/homebrew/bin/python3" \
    "python3.14" \
    "python3.13" \
    "python3.12" \
    "python3.11" \
    "python3.10" \
    "python3"
  do
    [[ -n "${cand}" ]] || continue
    local exe="${cand}"
    if [[ "${exe}" != /* ]]; then
      if ! command -v "${exe}" >/dev/null 2>&1; then
        continue
      fi
      exe="$(command -v "${exe}")"
    elif [[ ! -x "${exe}" ]]; then
      continue
    fi
    if "${exe}" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
      echo "${exe}"
      return 0
    fi
  done
  return 1
}

PYTHON_BIN="$(find_python_bin)" || {
  echo "error: need python>=3.10 to run pyc4 frontend (set PYC_PYTHON_BIN=...)" >&2
  exit 2
}
CALLFRAME_SIZE_RAW="${LINXCORE_CALLFRAME_SIZE:-0}"
CALLFRAME_SIZE="$(
"${PYTHON_BIN}" - <<'PY' "${CALLFRAME_SIZE_RAW}"
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
"${PYTHON_BIN}" "${ROOT_DIR}/tools/generate/extract_qemu_opcode_matrix.py" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"
"${PYTHON_BIN}" "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir "${QEMU_LINX_DIR}"
"${PYTHON_BIN}" "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"

find_pyc_root() {
  if [[ -n "${PYC_ROOT:-}" && -d "${PYC_ROOT}" ]]; then
    echo "${PYC_ROOT}"
    return 0
  fi
  if [[ -d "${LINX_ROOT}/tools/pyCircuit" ]]; then
    echo "${LINX_ROOT}/tools/pyCircuit"
    return 0
  fi
  return 1
}

PYC_ROOT_DIR="$(find_pyc_root)" || {
  echo "error: cannot locate pyCircuit; set PYC_ROOT=..." >&2
  exit 2
}

# shellcheck disable=SC1090
source "${PYC_ROOT_DIR}/flows/scripts/lib.sh"
pyc_find_pycc

OUT_CPP="${ROOT_DIR}/generated/cpp/linxcore_top"
OUT_V="${ROOT_DIR}/generated/verilog/linxcore_top"
mkdir -p "${OUT_CPP}" "${OUT_V}"

# C++ simulation does not require Verilog emission; keep it enabled by default
# for full-stack flows but allow callers (e.g. C++ sim gates) to skip it.
SKIP_VERILOG="${LINXCORE_SKIP_VERILOG:-0}"

# IMPORTANT: incremental build performance
# --------------------------------------
# Do NOT wipe generated outputs on every regeneration.
# Deleting all .cpp/.hpp forces a full C++ rebuild (minutes) even when the design is unchanged.
# Instead, emit into temp dirs and only update files whose contents actually changed.
OUT_CPP_TMP="$(mktemp -d -t linxcore_cpp.XXXXXX)"
OUT_V_TMP=""
if [[ "${SKIP_VERILOG}" == "0" ]]; then
  OUT_V_TMP="$(mktemp -d -t linxcore_v.XXXXXX)"
fi
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

# LinxCore currently has very deep combinational paths (functional model +
# large priority muxes). Keep a higher default so regeneration works out of the
# box, but allow callers to tighten this as we burn down depth.
LOGIC_DEPTH="${PYC_LOGIC_DEPTH:-2048}"
BUILD_PROFILE="${LINXCORE_BUILD_PROFILE:-release}"
if [[ "${BUILD_PROFILE}" != "release" && "${BUILD_PROFILE}" != "dev-fast" ]]; then
  echo "error: unknown LINXCORE_BUILD_PROFILE=${BUILD_PROFILE} (expected: release|dev-fast)" >&2
  exit 2
fi

PYCC_HELP="$("${PYCC}" --help 2>/dev/null || true)"
pycc_has_flag() {
  local flag="$1"
  printf '%s' "${PYCC_HELP}" | grep -Fq -- "${flag}"
}

PYCC_COMMON_ARGS=()
if pycc_has_flag "--build-profile="; then
  PYCC_COMMON_ARGS+=(--build-profile="${BUILD_PROFILE}")
fi

HIER_ARGS=()
if pycc_has_flag "--hierarchy-policy="; then
  # pyc4 default is strict, but pass explicitly to make the intent obvious and
  # prevent accidental legacy behavior in older environments.
  HIER_ARGS+=(--hierarchy-policy=strict)
else
  # Best-effort hierarchy preservation for older pycc builds.
  HIER_ARGS+=(--noinline)
fi

TMP_PYC="$(mktemp -t linxcore_top.XXXXXX.pyc)"
PYTHONPATH_VAL="$(pyc_pythonpath):${ROOT_DIR}/src"

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
  PYTHONPATH="${PYTHONPATH_VAL}" \
  "${PYTHON_BIN}" -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}" "${EMIT_PARAM_ARGS[@]}"
else
  PYTHONDONTWRITEBYTECODE=1 \
  LINXCORE_CALLFRAME_SIZE="${CALLFRAME_SIZE}" \
  PYTHONPATH="${PYTHONPATH_VAL}" \
  "${PYTHON_BIN}" -m pycircuit.cli emit "${ROOT_DIR}/src/linxcore_top.py" -o "${TMP_PYC}"
fi

if [[ "${SKIP_VERILOG}" == "0" ]]; then
  "${PYCC}" "${TMP_PYC}" \
    --emit=verilog \
    --logic-depth="${LOGIC_DEPTH}" \
    --out-dir="${OUT_V_TMP}" \
    "${PYCC_COMMON_ARGS[@]}" \
    "${HIER_ARGS[@]}"
fi

# Default shard thresholds tuned for developer iteration on large designs.
# Smaller shards reduce single-TU compiler stress (esp. JanusBccBackendCompat tick) and
# improve incremental rebuild latency.
CPP_SHARD_LINES="${PYC_CPP_SHARD_THRESHOLD_LINES:-}"
CPP_SHARD_BYTES="${PYC_CPP_SHARD_THRESHOLD_BYTES:-}"
CPP_SHARD_MAX_AST_NODES="${PYC_CPP_SHARD_MAX_AST_NODES:-}"

# Local defaults (only when caller didn't override). These are intentionally
# smaller than pycc's release defaults to avoid mega-TUs in LinxCore.
if [[ -z "${CPP_SHARD_LINES}" ]]; then
  if [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
    CPP_SHARD_LINES="16000"
  else
    CPP_SHARD_LINES="30000"
  fi
fi
if [[ -z "${CPP_SHARD_BYTES}" ]]; then
  if [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
    CPP_SHARD_BYTES="$((768 * 1024))"
  else
    CPP_SHARD_BYTES="1048576"
  fi
fi

CPP_SHARD_ARGS=(
  --cpp-shard-threshold-lines="${CPP_SHARD_LINES}"
  --cpp-shard-threshold-bytes="${CPP_SHARD_BYTES}"
)
if [[ -n "${CPP_SHARD_MAX_AST_NODES}" ]] && pycc_has_flag "--cpp-shard-max-ast-nodes="; then
  CPP_SHARD_ARGS+=(--cpp-shard-max-ast-nodes="${CPP_SHARD_MAX_AST_NODES}")
fi

"${PYCC}" "${TMP_PYC}" \
  --emit=cpp \
  --logic-depth="${LOGIC_DEPTH}" \
  --out-dir="${OUT_CPP_TMP}" \
  --cpp-split=module \
  "${PYCC_COMMON_ARGS[@]}" \
  "${HIER_ARGS[@]}" \
  "${CPP_SHARD_ARGS[@]}"

# Sync temp outputs into the stable generated directories.
sync_dir "${OUT_CPP_TMP}" "${OUT_CPP}"
if [[ "${SKIP_VERILOG}" == "0" ]]; then
  sync_dir "${OUT_V_TMP}" "${OUT_V}"
fi

printf '%s\n' "${CALLFRAME_SIZE}" > "${OUT_CPP}/.callframe_size"
if [[ "${SKIP_VERILOG}" == "0" ]]; then
  printf '%s\n' "${CALLFRAME_SIZE}" > "${OUT_V}/.callframe_size"
fi

# Make the compile manifest stable and relocatable: pycc writes absolute temp
# include paths into include_dirs; after syncing into OUT_CPP, rewrite to a
# relative include for consumers.
python3 - <<'PY' "${OUT_CPP}/cpp_compile_manifest.json"
from __future__ import annotations

import json
import sys
from pathlib import Path

p = Path(sys.argv[1])
if not p.exists():
    raise SystemExit(0)
data = json.loads(p.read_text(encoding="utf-8"))
data["include_dirs"] = ["."]
p.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
PY

if [[ "${SKIP_VERILOG}" == "0" ]]; then
  python3 - <<'PY' "${OUT_CPP}/manifest.json" "${OUT_V}/manifest.json"
from __future__ import annotations

import json
import sys
from pathlib import Path

required = [
    # Note: module names are canonicalized to CamelCase symbols in emitted artifacts.
    "LinxcoreTop",
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
    "JanusBccLsuScb",
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
else
  python3 - <<'PY' "${OUT_CPP}/manifest.json"
from __future__ import annotations

import json
import sys
from pathlib import Path

required = [
    # Note: module names are canonicalized to CamelCase symbols in emitted artifacts.
    "LinxcoreTop",
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
    "JanusBccLsuScb",
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

manifest_path = Path(sys.argv[1])
data = json.loads(manifest_path.read_text(encoding="utf-8"))
mods = set()
for item in data.get("cpp_modules", []):
    stem = item.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    mods.add(stem)
missing = [m for m in required if m not in mods]
if missing:
    raise SystemExit(f"error: {manifest_path} missing required modules: {', '.join(missing)}")
print("manifest module split check passed (cpp-only)")
PY
fi

echo "${OUT_CPP}"
echo "${OUT_V}"
