#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r935-moore-rf-arbiter"
VERILATOR_BUILD_JOBS="${VERILATOR_BUILD_JOBS:-1}"
EXPECT_CURRENT_RED="${EXPECT_CURRENT_RED:-0}"
SELF_TEST=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)
      BUILD_DIR="${2:-}"
      shift 2
      ;;
    --self-test)
      SELF_TEST=1
      shift
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

PROBE_SOURCE="${ROOT_DIR}/chisel/src/test/scala/linxcore/execute/ReducedScalarWritebackArbiterBehaviorProbe.scala"
PROBE_DIAGNOSTIC_SUFFIX="/src/test/scala/linxcore/execute/ReducedScalarWritebackArbiterBehaviorProbe.scala"
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
EMIT_LOG="${BUILD_DIR}/emit.log"
TOP_MODULE="ReducedScalarWritebackArbiterBehaviorProbe"
EMIT_MAIN="linxcore.execute.EmitReducedScalarWritebackArbiterBehaviorProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/backend_reduced_scalar_writeback_arbiter_behavior_probe_tb.cpp"

cleanup() {
  rm -rf -- "${BUILD_DIR}"
}
trap cleanup EXIT

classify_exact_missing_interface() {
  local log="$1"
  local emit_rc="$2"
  local field
  local expected_count
  local primary_diagnostics
  local primary_diagnostic_count
  local -a fields=(
    templateEnable
    templateValid
    templateTag
    templateData
    selectedTemplate
    templateBlockedByDisabled
    templateBlockedByExecute
    templateBlockedByReplay
  )

  [[ "${emit_rc}" -eq 1 ]] || return 1
  primary_diagnostics="$(
    grep -E '^\[error\] .+\.scala:[0-9]+:[0-9]+:' "${log}" ||
      true
  )"
  primary_diagnostic_count="$(
    grep -E -c '^\[error\] .+\.scala:[0-9]+:[0-9]+:' "${log}" ||
      true
  )"
  [[ "${primary_diagnostic_count}" -eq "${#fields[@]}" ]] || return 1
  for field in "${fields[@]}"; do
    expected_count="$(
      grep -F "${PROBE_DIAGNOSTIC_SUFFIX}:" <<<"${primary_diagnostics}" |
        grep -F -c ": value ${field} is not a member of linxcore.execute.ReducedScalarWritebackArbiterIO" ||
        true
    )"
    [[ "${expected_count}" -eq 1 ]] || return 1
  done
  ! grep -E -q 'OutOfMemoryError|StackOverflowError|timed out|timeout|Connection refused|server failed' "${log}"
}

outcome_status() {
  local outcome="$1"
  local expect_red="$2"
  case "${outcome}:${expect_red}" in
    missing:1|green:0)
      echo 0
      ;;
    missing:0|green:1)
      echo 1
      ;;
    *)
      echo 2
      ;;
  esac
}

run_self_test() {
  local dir exact cached wrong foreign same_file timeout setup
  dir="$(mktemp -d)"
  exact="${dir}/exact.log"
  cached="${dir}/cached.log"
  wrong="${dir}/wrong.log"
  foreign="${dir}/foreign.log"
  same_file="${dir}/same-file.log"
  timeout="${dir}/timeout.log"
  setup="${dir}/setup.log"
  {
    local field
    for field in \
      templateEnable templateValid templateTag templateData \
      selectedTemplate templateBlockedByDisabled \
      templateBlockedByExecute templateBlockedByReplay; do
      echo "[error] ${PROBE_SOURCE}:1:1: value ${field} is not a member of linxcore.execute.ReducedScalarWritebackArbiterIO"
    done
  } >"${exact}"
  sed "s#${PROBE_SOURCE}#\${BASE}${PROBE_DIAGNOSTIC_SUFFIX}#" "${exact}" >"${cached}"
  sed 's/value templateTag/value wrongTemplateTag/' "${exact}" >"${wrong}"
  {
    cat "${exact}"
    echo "[error] /tmp/Unrelated.scala:7:1: value boom is not a member of Other"
  } >"${foreign}"
  {
    cat "${exact}"
    echo "[error] ${PROBE_SOURCE}:99:1: type mismatch"
  } >"${same_file}"
  {
    cat "${exact}"
    echo "[error] timed out waiting for sbt server"
  } >"${timeout}"
  {
    cat "${exact}"
    echo "[error] Connection refused while initializing compiler bridge"
  } >"${setup}"

  classify_exact_missing_interface "${exact}" 1 ||
    { rm -rf -- "${dir}"; echo "error: exact current-red classifier rejected its positive case" >&2; return 1; }
  classify_exact_missing_interface "${cached}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cached-path current-red classifier rejected its positive case" >&2; return 1; }
  ! classify_exact_missing_interface "${exact}" 0 ||
    { rm -rf -- "${dir}"; echo "error: successful compilation passed missing-interface classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${exact}" 2 ||
    { rm -rf -- "${dir}"; echo "error: unexpected emit status passed missing-interface classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${wrong}" 1 ||
    { rm -rf -- "${dir}"; echo "error: wrong missing member passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${foreign}" 1 ||
    { rm -rf -- "${dir}"; echo "error: foreign compile failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${same_file}" 1 ||
    { rm -rf -- "${dir}"; echo "error: same-file compile failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${timeout}" 1 ||
    { rm -rf -- "${dir}"; echo "error: timeout contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${setup}" 1 ||
    { rm -rf -- "${dir}"; echo "error: setup contamination passed current-red classifier" >&2; return 1; }

  [[ "$(outcome_status missing 1)" -eq 0 ]]
  [[ "$(outcome_status missing 0)" -eq 1 ]]
  [[ "$(outcome_status green 1)" -eq 1 ]]
  [[ "$(outcome_status green 0)" -eq 0 ]]
  [[ "$(outcome_status unexpected 0)" -eq 2 ]]
  rm -rf -- "${dir}"
  echo "backend-reduced-scalar-writeback-arbiter-runner-self-test: PASS"
}

if [[ "${SELF_TEST}" -eq 1 ]]; then
  run_self_test
  exit 0
fi

if [[ "${EXPECT_CURRENT_RED}" != 0 && "${EXPECT_CURRENT_RED}" != 1 ]]; then
  echo "error: EXPECT_CURRENT_RED must be 0 or 1" >&2
  exit 2
fi
if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for the scalar writeback-arbiter behavior probe" >&2
  exit 2
fi

mkdir -p "${SV_DIR}" "${OBJ_DIR}"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

set +e
(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain ${EMIT_MAIN} --target-dir ${SV_DIR}"
) >"${EMIT_LOG}" 2>&1
emit_rc=$?
set -e

if [[ "${emit_rc}" -ne 0 ]]; then
  if classify_exact_missing_interface "${EMIT_LOG}" "${emit_rc}"; then
    if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
      echo "backend-reduced-scalar-writeback-arbiter-current-red: PASS (exact missing template IO)"
      exit 0
    fi
    echo "backend-reduced-scalar-writeback-arbiter: RED (exact missing template IO)" >&2
    exit 1
  fi
  echo "error: emit failed for a reason other than the exact missing template IO" >&2
  cat "${EMIT_LOG}" >&2
  exit 2
fi

if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
  echo "error: current-red mode requested, but the real DUT exposes the template interface" >&2
  exit 1
fi

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${SV_DIR}/${source}")
done <"${SV_DIR}/filelist.f"
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no emitted SystemVerilog under ${SV_DIR}" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module ReducedScalarWritebackArbiter' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real ReducedScalarWritebackArbiter" >&2
  exit 2
fi

verilator \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${TESTBENCH}" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o backend_reduced_scalar_writeback_arbiter_behavior_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${OBJ_DIR}/backend_reduced_scalar_writeback_arbiter_behavior_probe_tb"
