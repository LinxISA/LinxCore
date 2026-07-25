#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r992-johnson-setc-immediate"
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

PROBE_SOURCE="${ROOT_DIR}/chisel/src/test/scala/linxcore/execute/ReducedScalarAluSetcImmediateBehaviorProbe.scala"
R965_SOURCE="${ROOT_DIR}/chisel/src/test/scala/linxcore/bctrl/TemplateAcceptedSidecarHandoffProbe.scala"
R965_EXPECTED_HASH="3a457c2447017396c63f3195b54d097b966e4888a6069f3bb10b90cc63e8e7f2"
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
EMIT_LOG="${BUILD_DIR}/emit.log"
TB_LOG="${BUILD_DIR}/tb.log"
TOP_MODULE="ReducedScalarAluSetcImmediateBehaviorProbe"
EMIT_MAIN="linxcore.execute.EmitReducedScalarAluSetcImmediateBehaviorProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/backend_reduced_scalar_alu_setc_immediate_behavior_probe_tb.cpp"
EXCLUDED_R965_LOG="${BUILD_DIR}/excluded-r965-sha256.txt"

cleanup() {
  rm -rf -- "${BUILD_DIR}"
}
trap cleanup EXIT

hash_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

classify_exact_setc_immediate_current_red() {
  local log="$1"
  local tb_rc="$2"
  local line_count total_count line
  [[ "${tb_rc}" -eq 1 ]] || return 1
  total_count="$(wc -l <"${log}" | tr -d '[:space:]')"
  [[ "${total_count}" == "1" ]] || return 1
  line_count="$(grep -E -c '^backend-reduced-scalar-alu-setc-immediate-behavior-probe:' "${log}" || true)"
  [[ "${line_count}" == "1" ]] || return 1
  line="$(grep -E '^backend-reduced-scalar-alu-setc-immediate-behavior-probe:' "${log}" || true)"
  [[ "${line}" == "backend-reduced-scalar-alu-setc-immediate-behavior-probe: first-red accepted OP_SETC_EQI 329 and observed unsupportedOpcode=329 with release BID=3/0 GID=4/1 RID=5/0 STID=0; no completeValid/no branchConditionValid/no writeback/no duplicate pulses" ]]
}

run_self_test() {
  local dir exact subset superset wrong wrong_release foreign timeout zero_rc duplicate reordered
  dir="$(mktemp -d)"
  exact="${dir}/exact.log"
  subset="${dir}/subset.log"
  superset="${dir}/superset.log"
  wrong="${dir}/wrong.log"
  wrong_release="${dir}/wrong-release.log"
  foreign="${dir}/foreign.log"
  timeout="${dir}/timeout.log"
  zero_rc="${dir}/zero-rc.log"
  duplicate="${dir}/duplicate.log"
  reordered="${dir}/reordered.log"

  echo "backend-reduced-scalar-alu-setc-immediate-behavior-probe: first-red accepted OP_SETC_EQI 329 and observed unsupportedOpcode=329 with release BID=3/0 GID=4/1 RID=5/0 STID=0; no completeValid/no branchConditionValid/no writeback/no duplicate pulses" >"${exact}"
  : >"${subset}"
  { cat "${exact}"; echo "backend-reduced-scalar-alu-setc-immediate-behavior-probe: extra failure"; } >"${superset}"
  sed 's/OP_SETC_EQI 329/OP_SETC_NEI 339/' "${exact}" >"${wrong}"
  sed 's|BID=3/0|BID=3/1|' "${exact}" >"${wrong_release}"
  echo "other-probe: first-red accepted OP_SETC_EQI 329 and observed unsupportedOpcode=329 with release BID=3/0 GID=4/1 RID=5/0 STID=0; no completeValid/no branchConditionValid/no writeback/no duplicate pulses" >"${foreign}"
  { cat "${exact}"; echo "timed out waiting for verilator"; } >"${timeout}"
  cp "${exact}" "${zero_rc}"
  { cat "${exact}"; cat "${exact}"; } >"${duplicate}"
  echo "backend-reduced-scalar-alu-setc-immediate-behavior-probe: observed unsupportedOpcode=329 before release identity for first-red accepted OP_SETC_EQI 329" >"${reordered}"

  classify_exact_setc_immediate_current_red "${exact}" 1 ||
    { rm -rf -- "${dir}"; echo "error: exact current-red classifier rejected its positive case" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${subset}" 1 ||
    { rm -rf -- "${dir}"; echo "error: subset passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${superset}" 1 ||
    { rm -rf -- "${dir}"; echo "error: superset passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${wrong}" 1 ||
    { rm -rf -- "${dir}"; echo "error: wrong opcode passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${wrong_release}" 1 ||
    { rm -rf -- "${dir}"; echo "error: wrong release identity passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${foreign}" 1 ||
    { rm -rf -- "${dir}"; echo "error: foreign failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${timeout}" 1 ||
    { rm -rf -- "${dir}"; echo "error: timeout contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${duplicate}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate record passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${reordered}" 1 ||
    { rm -rf -- "${dir}"; echo "error: reordered text passed current-red classifier" >&2; return 1; }
  ! classify_exact_setc_immediate_current_red "${zero_rc}" 0 ||
    { rm -rf -- "${dir}"; echo "error: zero return code passed current-red classifier" >&2; return 1; }

  rm -rf -- "${dir}"
  echo "backend-reduced-scalar-alu-setc-immediate-behavior-runner-self-test: PASS"
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
  echo "error: Verilator is required for the SETC-immediate behavior probe" >&2
  exit 2
fi
if [[ ! -f "${R965_SOURCE}" ]]; then
  echo "error: required R965 exclusion source is missing: ${R965_SOURCE}" >&2
  exit 2
fi

mkdir -p "${SV_DIR}" "${OBJ_DIR}"
R965_HASH_BEFORE="$(hash_file "${R965_SOURCE}")"
if [[ "${R965_HASH_BEFORE}" != "${R965_EXPECTED_HASH}" ]]; then
  echo "error: R965 exclusion source hash changed before run: ${R965_HASH_BEFORE}" >&2
  exit 2
fi
printf 'TemplateAcceptedSidecarHandoffProbe.scala %s\n' "${R965_HASH_BEFORE}" >"${EXCLUDED_R965_LOG}"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

set +e
(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    'set Test / unmanagedSources := (Test / unmanagedSources).value.filterNot(_.getName == "TemplateAcceptedSidecarHandoffProbe.scala")' \
    "Test / runMain ${EMIT_MAIN} --target-dir ${SV_DIR}"
) >"${EMIT_LOG}" 2>&1
emit_rc=$?
set -e

R965_HASH_AFTER="$(hash_file "${R965_SOURCE}")"
if [[ "${R965_HASH_AFTER}" != "${R965_HASH_BEFORE}" ]]; then
  echo "error: R965 exclusion source hash changed during run: before=${R965_HASH_BEFORE} after=${R965_HASH_AFTER}" >&2
  exit 2
fi
printf 'TemplateAcceptedSidecarHandoffProbe.scala %s\n' "${R965_HASH_AFTER}" >>"${EXCLUDED_R965_LOG}"

if [[ "${emit_rc}" -ne 0 ]]; then
  echo "error: emit failed before SETC-immediate behavior simulation" >&2
  cat "${EMIT_LOG}" >&2
  exit 2
fi

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' -print | sort)
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no emitted SystemVerilog under ${SV_DIR}" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module ReducedScalarAluExecute' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real ReducedScalarAluExecute" >&2
  exit 2
fi

set +e
verilator \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
	  --exe "${TESTBENCH}" \
	  --output-split 2000 \
	  --output-split-cfuncs 2000 \
	  -Mdir "${OBJ_DIR}" \
	  -o backend_reduced_scalar_alu_setc_immediate_behavior_probe_tb \
	  -CFLAGS '-std=c++17 -O0 -g0' >"${BUILD_DIR}/verilator.log" 2>&1
verilator_rc=$?
set -e
if [[ "${verilator_rc}" -ne 0 ]]; then
  echo "error: Verilator generation failed before SETC-immediate behavior simulation" >&2
  cat "${BUILD_DIR}/verilator.log" >&2
  exit 2
fi

perl -0pi -e 's/\$\(VK_USER_OBJS\) \$\(VK_GLOBAL_OBJS\) \$\(VM_PREFIX\)__ALL\.a/\$\(VK_USER_OBJS\) \$\(VK_GLOBAL_OBJS\) \$\(VK_OBJS\)/' \
  "${OBJ_DIR}/VReducedScalarAluSetcImmediateBehaviorProbe.mk"

set +e
make -C "${OBJ_DIR}" \
  -f VReducedScalarAluSetcImmediateBehaviorProbe.mk \
  -j "${VERILATOR_BUILD_JOBS}" \
  VM_PARALLEL_BUILDS=1 \
  OPT_FAST='-O0' \
  OPT_SLOW='-O0' \
  OPT_GLOBAL='-O0' \
  backend_reduced_scalar_alu_setc_immediate_behavior_probe_tb >>"${BUILD_DIR}/verilator.log" 2>&1
make_rc=$?
set -e
if [[ "${make_rc}" -ne 0 ]]; then
  echo "error: Verilator build failed before SETC-immediate behavior simulation" >&2
  cat "${BUILD_DIR}/verilator.log" >&2
  exit 2
fi

set +e
"${OBJ_DIR}/backend_reduced_scalar_alu_setc_immediate_behavior_probe_tb" >"${TB_LOG}" 2>&1
tb_rc=$?
set -e

if [[ "${tb_rc}" -ne 0 ]]; then
  if classify_exact_setc_immediate_current_red "${TB_LOG}" "${tb_rc}"; then
    if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
	      cat "${TB_LOG}"
	      echo "backend-reduced-scalar-alu-setc-immediate-behavior-current-red: PASS (exact OP_SETC_EQI unsupported+release first-red; route=verilator-generate,archive-free-make,VM_PARALLEL_BUILDS=1,OPT=O0,jobs=${VERILATOR_BUILD_JOBS})"
	      exit 0
    fi
    cat "${TB_LOG}" >&2
    echo "backend-reduced-scalar-alu-setc-immediate-behavior: RED (exact OP_SETC_EQI unsupported+release first-red)" >&2
    exit 1
  fi
  echo "error: testbench failed outside the exact R992 current-red boundary" >&2
  cat "${TB_LOG}" >&2
  exit 2
fi

cat "${TB_LOG}"
if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
  echo "error: current-red mode requested, but the real DUT satisfied the SETC-immediate behavior matrix" >&2
  exit 1
fi
