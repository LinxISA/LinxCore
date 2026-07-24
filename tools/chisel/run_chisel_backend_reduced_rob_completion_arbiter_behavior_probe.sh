#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r925-moore-behavior"
VERILATOR_BUILD_JOBS="${VERILATOR_BUILD_JOBS:-1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)
      BUILD_DIR="${2:-}"
      shift 2
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for the completion-arbiter behavior probe" >&2
  exit 2
fi

SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
TOP_MODULE="ReducedRobCompletionArbiterBehaviorProbe"
EMIT_MAIN="linxcore.backend.EmitReducedRobCompletionArbiterBehaviorProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/backend_reduced_rob_completion_arbiter_behavior_probe_tb.cpp"
MISMATCH_LOG="${BUILD_DIR}/parent-slot-mismatch.log"

rm -rf "${SV_DIR}" "${OBJ_DIR}"
mkdir -p "${SV_DIR}" "${OBJ_DIR}"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain ${EMIT_MAIN} --target-dir ${SV_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${SV_DIR}/${source}")
done <"${SV_DIR}/filelist.f"
assert_sources=()
if [[ -d "${SV_DIR}/verification/assert" ]]; then
  while IFS= read -r source; do
    already_listed=false
    for listed in "${sv_sources[@]}"; do
      if [[ "${listed}" == "${source}" ]]; then
        already_listed=true
        break
      fi
    done
    if [[ "${already_listed}" == false ]]; then
      assert_sources+=("${source}")
    fi
  done < <(find "${SV_DIR}/verification/assert" -type f -name '*.sv' | sort)
fi
sv_sources+=("${assert_sources[@]}")
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no emitted SystemVerilog under ${SV_DIR}" >&2
  exit 2
fi
if [[ "${#assert_sources[@]}" -eq 0 ]]; then
  echo "error: emitted completion-arbiter probe did not produce an assert layer" >&2
  exit 1
fi
if grep -R -i -q --include='*.sv' 'exactComplete' "${SV_DIR}"; then
  echo "error: emitted completion-arbiter probe contains an exactComplete path" >&2
  exit 1
fi

verilator \
  --assert \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${TESTBENCH}" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  "-I${SV_DIR}/verification" \
  "-I${SV_DIR}/verification/assert" \
  -Mdir "${OBJ_DIR}" \
  -o backend_reduced_rob_completion_arbiter_behavior_probe_tb \
  -CFLAGS '-std=c++17 -O2'

PROBE="${OBJ_DIR}/backend_reduced_rob_completion_arbiter_behavior_probe_tb"
"${PROBE}"

set +e
"${PROBE}" --trigger-parent-slot-mismatch >"${MISMATCH_LOG}" 2>&1
mismatch_rc=$?
set -e
if [[ "${mismatch_rc}" -eq 0 ]]; then
  echo "error: parent-slot mismatch was silently accepted" >&2
  exit 1
fi
if ! grep -F -q 'template completion parent slot must match' "${MISMATCH_LOG}"; then
  echo "error: mismatch failed without the real parent-slot hardware assertion" >&2
  cat "${MISMATCH_LOG}" >&2
  exit 1
fi

echo "backend-reduced-rob-completion-arbiter-parent-slot-assertion: PASS (rc=${mismatch_rc})"
echo "backend-reduced-rob-completion-arbiter-exact-complete-absence: PASS"
