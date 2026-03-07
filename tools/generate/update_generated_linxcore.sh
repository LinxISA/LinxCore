#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/lib/workspace_paths.sh"
LINXISA_ROOT="${LINXISA_ROOT:-${LINXISA_DIR:-$(linxcore_resolve_linxisa_root "${ROOT_DIR}" || true)}}"

QEMU_LINX_DIR="${QEMU_LINX_DIR:-$(linxcore_resolve_qemu_linx_dir "${ROOT_DIR}" || true)}"
if [[ ! -d "${QEMU_LINX_DIR}" ]]; then
  echo "error: QEMU Linx decode tree not found: ${QEMU_LINX_DIR}" >&2
  echo "hint: set QEMU_LINX_DIR=/abs/path/to/qemu/target/linx" >&2
  echo "hint: or set LINXCORE_QEMU_ROOT=/abs/path/to/qemu" >&2
  echo "hint: or set LINXISA_ROOT=/abs/path/to/linx-isa" >&2
  exit 1
fi

PYC_ROOT="${LINXCORE_PYC_ROOT:-${PYC_ROOT:-$(linxcore_resolve_pyc_root "${ROOT_DIR}" || true)}}"
if [[ ! -d "${PYC_ROOT}" ]]; then
  echo "error: pyCircuit root not found: ${PYC_ROOT}" >&2
  echo "hint: set LINXCORE_PYC_ROOT=/abs/path/to/pyCircuit" >&2
  exit 1
fi
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
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --out "${ROOT_DIR}/src/common/opcode_catalog.yaml"
python3 "${ROOT_DIR}/tools/generate/gen_opcode_tables.py" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml" \
  --linxcore-common "${ROOT_DIR}/src/common" \
  --qemu-linx-dir "${QEMU_LINX_DIR}"
python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
  --qemu-linx-dir "${QEMU_LINX_DIR}" \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"

if [[ -f "${PYC_ROOT}/scripts/lib.sh" ]]; then
  # Legacy pyCircuit tree layout.
  source "${PYC_ROOT}/scripts/lib.sh"
elif [[ -f "${PYC_ROOT}/flows/scripts/lib.sh" ]]; then
  # Current pyCircuit tree layout.
  source "${PYC_ROOT}/flows/scripts/lib.sh"
fi

# Backend tool: prefer pycc, fallback to pyc-compile for older trees.
if [[ -z "${PYC_COMPILE:-}" ]]; then
  if [[ -n "${PYCC:-}" && -x "${PYCC}" ]]; then
    PYC_COMPILE="${PYCC}"
  fi
fi

if [[ -z "${PYC_COMPILE:-}" ]]; then
  for cand in \
    "${PYC_ROOT}/build-top/bin/pycc" \
    "${PYC_ROOT}/build/bin/pycc" \
    "${PYC_ROOT}/compiler/mlir/build2/bin/pycc" \
    "${PYC_ROOT}/compiler/mlir/build/bin/pycc"
  do
    if [[ -x "${cand}" ]]; then
      PYC_COMPILE="${cand}"
      break
    fi
  done
fi

if [[ -z "${PYC_COMPILE:-}" ]]; then
  found="$(command -v pycc 2>/dev/null || true)"
  if [[ -n "${found}" ]]; then
    PYC_COMPILE="${found}"
  fi
fi

if [[ -z "${PYC_COMPILE:-}" ]]; then
  for cand in \
    "${PYC_ROOT}/build-top/bin/pyc-compile" \
    "${PYC_ROOT}/build/bin/pyc-compile" \
    "${PYC_ROOT}/compiler/mlir/build2/bin/pyc-compile" \
    "${PYC_ROOT}/compiler/mlir/build/bin/pyc-compile"
  do
    if [[ -x "${cand}" ]]; then
      PYC_COMPILE="${cand}"
      break
    fi
  done
fi

if [[ -z "${PYC_COMPILE:-}" || ! -x "${PYC_COMPILE}" ]]; then
  echo "error: pycc backend not found (PYC_COMPILE=${PYC_COMPILE:-<unset>})" >&2
  echo "hint: build it in pyCircuit: cd \"${PYC_ROOT}\" && bash flows/scripts/pyc build" >&2
  echo "hint: or set PYCC=/absolute/path/to/pycc" >&2
  exit 1
fi

BUILD_PROFILE="${LINXCORE_BUILD_PROFILE:-dev-fast}"
case "${BUILD_PROFILE}" in
  dev-fast|release) ;;
  *)
    echo "error: unsupported LINXCORE_BUILD_PROFILE=${BUILD_PROFILE} (expected dev-fast|release)" >&2
    exit 2
    ;;
esac

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

if [[ -n "${PYC_LOGIC_DEPTH:-}" ]]; then
  LOGIC_DEPTH="${PYC_LOGIC_DEPTH}"
else
  # The current WIP backend split builds substantially deeper comb trees than
  # the earlier closure baseline. Keep a higher default budget so bring-up
  # runs compile without requiring a local override.
  LOGIC_DEPTH=2048
fi

if [[ -n "${PYC_CPP_SHARD_THRESHOLD_LINES:-}" ]]; then
  CPP_SHARD_LINES="${PYC_CPP_SHARD_THRESHOLD_LINES}"
elif [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
  CPP_SHARD_LINES=8000
else
  CPP_SHARD_LINES=30000
fi

if [[ -n "${PYC_CPP_SHARD_THRESHOLD_BYTES:-}" ]]; then
  CPP_SHARD_BYTES="${PYC_CPP_SHARD_THRESHOLD_BYTES}"
elif [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
  CPP_SHARD_BYTES=393216
else
  CPP_SHARD_BYTES=1048576
fi

if [[ -n "${PYC_CPP_SHARD_MAX_AST_NODES:-}" ]]; then
  CPP_SHARD_MAX_AST_NODES="${PYC_CPP_SHARD_MAX_AST_NODES}"
elif [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
  CPP_SHARD_MAX_AST_NODES=96
else
  CPP_SHARD_MAX_AST_NODES=0
fi

NOINLINE_DEFAULT=0
if [[ "${BUILD_PROFILE}" == "dev-fast" ]]; then
  NOINLINE_DEFAULT=1
fi

INLINE_POLICY="${PYC_INLINE_POLICY:-}"
if [[ -z "${INLINE_POLICY}" && "${BUILD_PROFILE}" == "dev-fast" ]]; then
  INLINE_POLICY="off"
fi

CANONICALIZE_BUDGET="${PYC_CANONICALIZE_BUDGET:-0}"
if [[ "${CANONICALIZE_BUDGET}" == "0" && "${BUILD_PROFILE}" == "dev-fast" ]]; then
  CANONICALIZE_BUDGET=2
fi

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
PYC_HELP="$("${PYC_COMPILE}" --help 2>&1 || true)"
PYCC_COMMON_ARGS=(--logic-depth="${LOGIC_DEPTH}")
if grep -q -- '--build-profile' <<<"${PYC_HELP}"; then
  PYCC_COMMON_ARGS+=(--build-profile="${BUILD_PROFILE}")
fi
if grep -q -- '--hierarchy-policy' <<<"${PYC_HELP}"; then
  # pyc4: hierarchy must be strict to preserve module boundaries.
  PYCC_COMMON_ARGS+=(--hierarchy-policy="strict")
fi
if [[ -n "${INLINE_POLICY}" ]] && grep -q -- '--inline-policy' <<<"${PYC_HELP}"; then
  PYCC_COMMON_ARGS+=(--inline-policy="${INLINE_POLICY}")
fi
if [[ "${CANONICALIZE_BUDGET}" =~ ^[0-9]+$ ]] && [[ "${CANONICALIZE_BUDGET}" -gt 0 ]] && grep -q -- '--canonicalize-budget' <<<"${PYC_HELP}"; then
  PYCC_COMMON_ARGS+=(--canonicalize-budget="${CANONICALIZE_BUDGET}")
fi
if [[ -n "${PYC_PYCC_PROFILE_JSON:-}" ]] && grep -q -- '--profile-json' <<<"${PYC_HELP}"; then
  PYCC_COMMON_ARGS+=(--profile-json="${PYC_PYCC_PROFILE_JSON}")
  if [[ "${PYC_PYCC_PROFILE_PASS_TIMING:-1}" != "0" ]] && grep -q -- '--profile-pass-timing' <<<"${PYC_HELP}"; then
    PYCC_COMMON_ARGS+=(--profile-pass-timing)
  fi
fi

if [[ "${TRY_V_OUTDIR}" == "1" ]]; then
  if ! "${PYC_COMPILE}" "${TMP_PYC}" --emit=verilog "${PYCC_COMMON_ARGS[@]}" --out-dir="${OUT_V_TMP}" >/dev/null 2>&1; then
    MONO_V="${OUT_V_TMP}/linxcore_top.v"
    "${PYC_COMPILE}" "${TMP_PYC}" --emit=verilog "${PYCC_COMMON_ARGS[@]}" -o "${MONO_V}"
    python3 "${ROOT_DIR}/tools/generate/split_verilog_modules.py" \
      --src "${MONO_V}" \
      --out-dir "${OUT_V_TMP}" \
      --top "linxcore_top"
  fi
else
  MONO_V="${OUT_V_TMP}/linxcore_top.v"
  "${PYC_COMPILE}" "${TMP_PYC}" --emit=verilog "${PYCC_COMMON_ARGS[@]}" -o "${MONO_V}"
  python3 "${ROOT_DIR}/tools/generate/split_verilog_modules.py" \
    --src "${MONO_V}" \
    --out-dir "${OUT_V_TMP}" \
    --top "linxcore_top"
fi

NOINLINE_FLAG=()
if [[ "${PYC_NOINLINE:-${NOINLINE_DEFAULT}}" != "0" ]]; then
  NOINLINE_FLAG=(--noinline)
fi

CPP_AST_SHARD_FLAG=()
if [[ "${CPP_SHARD_MAX_AST_NODES}" =~ ^[0-9]+$ ]] && [[ "${CPP_SHARD_MAX_AST_NODES}" -gt 0 ]] && grep -q -- '--cpp-shard-max-ast-nodes' <<<"${PYC_HELP}"; then
  CPP_AST_SHARD_FLAG=(--cpp-shard-max-ast-nodes="${CPP_SHARD_MAX_AST_NODES}")
fi

"${PYC_COMPILE}" "${TMP_PYC}" \
  --emit=cpp \
  "${PYCC_COMMON_ARGS[@]}" \
  --out-dir="${OUT_CPP_TMP}" \
  --cpp-split=module \
  "${NOINLINE_FLAG[@]}" \
  --cpp-shard-threshold-lines="${CPP_SHARD_LINES}" \
  --cpp-shard-threshold-bytes="${CPP_SHARD_BYTES}" \
  "${CPP_AST_SHARD_FLAG[@]}"

# Sync temp outputs into the stable generated directories.
sync_dir "${OUT_CPP_TMP}" "${OUT_CPP}"
sync_dir "${OUT_V_TMP}" "${OUT_V}"

printf '%s\n' "${CALLFRAME_SIZE}" > "${OUT_CPP}/.callframe_size"
printf '%s\n' "${CALLFRAME_SIZE}" > "${OUT_V}/.callframe_size"
printf '%s\n' "${BUILD_PROFILE}" > "${OUT_CPP}/.build_profile"
printf '%s\n' "${BUILD_PROFILE}" > "${OUT_V}/.build_profile"

MANIFEST_JSON="${OUT_CPP}/cpp_compile_manifest.json"
MANIFEST_COST_CHECK="${ROOT_DIR}/tools/perf/check_manifest_compile_cost_thresholds.py"
if [[ -f "${MANIFEST_JSON}" && -f "${MANIFEST_COST_CHECK}" ]]; then
  BASELINE_DIR="${PYC_MANIFEST_COST_BASELINE_DIR:-${ROOT_DIR}/generated/perf/baselines}"
  BASELINE_JSON="${PYC_MANIFEST_COST_BASELINE_JSON:-${BASELINE_DIR}/cpp_compile_manifest.${BUILD_PROFILE}.json}"
  mkdir -p "${BASELINE_DIR}" "$(dirname -- "${BASELINE_JSON}")"

  if [[ "${PYC_MANIFEST_COST_UPDATE_BASELINE:-0}" == "1" ]]; then
    cp -f "${MANIFEST_JSON}" "${BASELINE_JSON}"
  elif [[ ! -f "${BASELINE_JSON}" && "${PYC_MANIFEST_COST_AUTO_BOOTSTRAP:-1}" == "1" ]]; then
    cp -f "${MANIFEST_JSON}" "${BASELINE_JSON}"
  fi

  cost_args=(
    --manifest-json "${MANIFEST_JSON}"
    --max-regress-pct "${PYC_MANIFEST_COST_MAX_REGRESS_PCT:-25}"
    --max-top-tu-cost "${PYC_MANIFEST_COST_MAX_TOP_TU:-250000}"
    --max-top-module-cost "${PYC_MANIFEST_COST_MAX_TOP_MODULE:-1500000}"
    --max-total-cost "${PYC_MANIFEST_COST_MAX_TOTAL:-2300000}"
  )
  if [[ -f "${BASELINE_JSON}" ]]; then
    cost_args+=(--baseline-json "${BASELINE_JSON}")
  fi
  if [[ "${PYC_MANIFEST_COST_STRICT:-0}" != "0" ]]; then
    cost_args+=(--strict)
  fi
  python3 "${MANIFEST_COST_CHECK}" "${cost_args[@]}"
fi

python3 - <<'PY' "${OUT_CPP}/manifest.json" "${OUT_V}/manifest.json"
from __future__ import annotations

import json
import sys
from pathlib import Path

required = [
    "linxcore_top",
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
