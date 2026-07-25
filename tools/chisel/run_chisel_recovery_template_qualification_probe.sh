#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r947-dijkstra-template-recovery"
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

PROBE_SOURCE="${ROOT_DIR}/chisel/src/test/scala/linxcore/recovery/TemplateRecoveryQualificationBehaviorProbe.scala"
PROBE_DIAGNOSTIC_SUFFIX="/src/test/scala/linxcore/recovery/TemplateRecoveryQualificationBehaviorProbe.scala"
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
EMIT_LOG="${BUILD_DIR}/emit.log"
TOP_MODULE="TemplateRecoveryQualificationBehaviorProbe"
EMIT_MAIN="linxcore.recovery.EmitTemplateRecoveryQualificationBehaviorProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/recovery_template_qualification_probe_tb.cpp"

cleanup() {
  rm -rf -- "${BUILD_DIR}"
}
trap cleanup EXIT

classify_exact_missing_type() {
  local log="$1"
  local emit_rc="$2"
  local record
  local primary_diagnostic_count=0
  local source_echo_count=0
  local caret_count=0
  local one_error_count=0
  local direct_summary_count=0
  local cached_header_count=0
  local cached_summary_count=0
  local stack_frame_count=0
  local timing_count=0

  [[ "${emit_rc}" -eq 1 ]] || return 1
  while IFS= read -r record; do
    if [[ "${record}" == *"${PROBE_DIAGNOSTIC_SUFFIX}:"* ]] &&
       [[ "${record}" =~ ^\[error\]\ .+\.scala:[0-9]+:[0-9]+:\ not\ found:\ type\ TemplateRecoveryQualification$ ]]; then
      primary_diagnostic_count=$((primary_diagnostic_count + 1))
      continue
    fi
    case "${record}" in
      "[error]   private val qualification = Module(new TemplateRecoveryQualification(p))")
        source_echo_count=$((source_echo_count + 1))
        ;;
      "[error]                                          ^")
        caret_count=$((caret_count + 1))
        ;;
      "[error] one error found")
        one_error_count=$((one_error_count + 1))
        ;;
      "[error] (Test / compileIncremental) Compilation failed" | \
      "[error] (Test / Compile / compileIncremental) Compilation failed")
        direct_summary_count=$((direct_summary_count + 1))
        ;;
      "[error] sbt.util.CachedCompileFailure"*": Compilation failed")
        cached_header_count=$((cached_header_count + 1))
        ;;
      "[error] (Test / compileIncremental) sbt.util.CachedCompileFailure"*": Compilation failed")
        cached_summary_count=$((cached_summary_count + 1))
        ;;
      $'[error] \tat '*)
        stack_frame_count=$((stack_frame_count + 1))
        ;;
      "[error] Total time: "* | \
      "[error] elapsed time: "*)
        timing_count=$((timing_count + 1))
        ;;
      *)
        return 1
        ;;
    esac
  done < <(grep -E '^\[error\]' "${log}" || true)
  [[ "${primary_diagnostic_count}" -eq 1 ]] || return 1
  [[ "${source_echo_count}" -eq 1 ]] || return 1
  [[ "${caret_count}" -eq 1 ]] || return 1
  [[ "${timing_count}" -eq 1 ]] || return 1
  if [[ "${one_error_count}" -eq 1 && "${direct_summary_count}" -eq 1 &&
        "${cached_header_count}" -eq 0 && "${cached_summary_count}" -eq 0 &&
        "${stack_frame_count}" -eq 0 ]]; then
    return 0
  fi
  [[ "${one_error_count}" -eq 0 && "${direct_summary_count}" -eq 0 &&
     "${cached_header_count}" -eq 1 && "${cached_summary_count}" -eq 1 &&
     "${stack_frame_count}" -gt 0 ]]
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
  local dir exact_type cached wrong foreign same_file unlocated timeout setup status
  dir="$(mktemp -d)"
  exact_type="${dir}/exact-type.log"
  cached="${dir}/cached.log"
  wrong="${dir}/wrong.log"
  foreign="${dir}/foreign.log"
  same_file="${dir}/same-file.log"
  unlocated="${dir}/unlocated.log"
  timeout="${dir}/timeout.log"
  setup="${dir}/setup.log"
  status="${dir}/status.log"

  {
    echo "[error] ${PROBE_SOURCE}:70:50: not found: type TemplateRecoveryQualification"
    echo "[error]   private val qualification = Module(new TemplateRecoveryQualification(p))"
    echo "[error]                                          ^"
    echo "[error] one error found"
    echo "[error] (Test / compileIncremental) Compilation failed"
    echo "[error] elapsed time: 2 s, cache 95%, 22 disk cache hits, 1 onsite task"
  } >"${exact_type}"
  sed "s#${PROBE_SOURCE}#\${BASE}${PROBE_DIAGNOSTIC_SUFFIX}#" "${exact_type}" >"${cached}"
  sed 's/TemplateRecoveryQualification/TemplateRecoveryWrongType/' "${exact_type}" >"${wrong}"
  {
    cat "${exact_type}"
    echo "[error] /tmp/Unrelated.scala:7:1: value boom is not a member of Other"
  } >"${foreign}"
  {
    cat "${exact_type}"
    echo "[error] ${PROBE_SOURCE}:99:1: type mismatch"
  } >"${same_file}"
  {
    cat "${exact_type}"
    echo "[error] compiler bridge emitted an unlocated fatal diagnostic"
  } >"${unlocated}"
  {
    cat "${exact_type}"
    echo "[error] timed out waiting for sbt server"
  } >"${timeout}"
  {
    cat "${exact_type}"
    echo "[error] Connection refused while initializing compiler bridge"
  } >"${setup}"
  {
    cat "${exact_type}"
    echo "[error] compiler returned return code 1 after setup failed"
  } >"${status}"

  classify_exact_missing_type "${exact_type}" 1 ||
    { rm -rf -- "${dir}"; echo "error: exact missing-type diagnostic was rejected" >&2; return 1; }
  classify_exact_missing_type "${cached}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cached-path missing-type diagnostic was rejected" >&2; return 1; }
  ! classify_exact_missing_type "${exact_type}" 0 ||
    { rm -rf -- "${dir}"; echo "error: successful emit passed missing-type classifier" >&2; return 1; }
  ! classify_exact_missing_type "${exact_type}" 2 ||
    { rm -rf -- "${dir}"; echo "error: unexpected emit status passed missing-type classifier" >&2; return 1; }
  ! classify_exact_missing_type "${wrong}" 1 ||
    { rm -rf -- "${dir}"; echo "error: wrong missing symbol passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_type "${foreign}" 1 ||
    { rm -rf -- "${dir}"; echo "error: foreign compile failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_type "${same_file}" 1 ||
    { rm -rf -- "${dir}"; echo "error: same-file extra failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_type "${unlocated}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unlocated extra failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_type "${timeout}" 1 ||
    { rm -rf -- "${dir}"; echo "error: timeout contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_type "${setup}" 1 ||
    { rm -rf -- "${dir}"; echo "error: setup contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_type "${status}" 1 ||
    { rm -rf -- "${dir}"; echo "error: return-code contamination passed current-red classifier" >&2; return 1; }

  [[ "$(outcome_status missing 1)" -eq 0 ]]
  [[ "$(outcome_status missing 0)" -eq 1 ]]
  [[ "$(outcome_status green 1)" -eq 1 ]]
  [[ "$(outcome_status green 0)" -eq 0 ]]
  [[ "$(outcome_status unexpected 0)" -eq 2 ]]
  rm -rf -- "${dir}"
  echo "recovery-template-qualification-runner-self-test: PASS"
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
  echo "error: Verilator is required for the template recovery qualification probe" >&2
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
  if classify_exact_missing_type "${EMIT_LOG}" "${emit_rc}"; then
    if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
      echo "recovery-template-qualification-current-red: PASS (exact missing TemplateRecoveryQualification)"
      exit 0
    fi
    echo "recovery-template-qualification: RED (exact missing TemplateRecoveryQualification)" >&2
    exit 1
  fi
  echo "error: emit failed for a reason other than the exact missing TemplateRecoveryQualification type" >&2
  cat "${EMIT_LOG}" >&2
  exit 2
fi

if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
  echo "error: current-red mode requested, but the real DUT exposes TemplateRecoveryQualification" >&2
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
if ! grep -R -q --include='*.sv' 'module TemplateRecoveryQualification' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real TemplateRecoveryQualification" >&2
  exit 2
fi

verilator \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${TESTBENCH}" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o recovery_template_qualification_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${OBJ_DIR}/recovery_template_qualification_probe_tb"
