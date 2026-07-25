#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r965-johnson-sidecar-handoff"
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

PROBE_SOURCE="${ROOT_DIR}/chisel/src/test/scala/linxcore/bctrl/TemplateAcceptedSidecarHandoffProbe.scala"
PROBE_DIAGNOSTIC_SUFFIX="/src/test/scala/linxcore/bctrl/TemplateAcceptedSidecarHandoffProbe.scala"
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
EMIT_LOG="${BUILD_DIR}/emit.log"
TB_LOG="${BUILD_DIR}/tb.log"
TOP_MODULE="TemplateAcceptedSidecarHandoffProbe"
EMIT_MAIN="linxcore.bctrl.EmitTemplateAcceptedSidecarHandoffProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/bctrl_template_accepted_sidecar_handoff_probe_tb.cpp"

cleanup() {
  rm -rf -- "${BUILD_DIR}"
}
trap cleanup EXIT

declare -a CACHED_STACK_LINES=(
  $'[error] \tat sbt.util.CachedCompileFailure.toException(CachedCompileFailure.scala:23)'
  $'[error] \tat sbt.util.ActionCache$.cache(ActionCache.scala:181)'
  $'[error] \tat sbt.Defaults$.$init$$$anonfun$1(Defaults.scala:2310)'
  $'[error] \tat scala.Function1.$anonfun$compose$1(Function1.scala:79)'
  $'[error] \tat scala.Function1.$anonfun$compose$1(Function1.scala:79)'
  $'[error] \tat sbt.std.Transform$$anon$3.work(Transform.scala:79)'
  $'[error] \tat sbt.std.Transform$$anon$3.work(Transform.scala:79)'
  $'[error] \tat sbt.Execute.submit$$anonfun$1$$anonfun$1(Execute.scala:283)'
  $'[error] \tat sbt.internal.util.ErrorHandling$.wideConvert(ErrorHandling.scala:24)'
  $'[error] \tat sbt.Execute.work(Execute.scala:294)'
  $'[error] \tat sbt.Execute.submit$$anonfun$1(Execute.scala:283)'
  $'[error] \tat sbt.ConcurrentRestrictions$$anon$4.$anonfun$2(ConcurrentRestrictions.scala:269)'
  $'[error] \tat sbt.CompletionService$$anon$2.call(CompletionService.scala:75)'
  $'[error] \tat sbt.CompletionService$$anon$2.call(CompletionService.scala:73)'
  $'[error] \tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)'
  $'[error] \tat java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:539)'
  $'[error] \tat java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)'
  $'[error] \tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)'
  $'[error] \tat java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)'
  $'[error] \tat java.base/java.lang.Thread.run(Thread.java:840)'
)

classify_exact_missing_interface() {
  local log="$1"
  local emit_rc="$2"
  local record state mode field_index triplet_phase expected_source primary_prefix primary_suffix location stack_index
  local -a fields=(
    "templateSidecar:linxcore.rename.ScalarDecodeRenameBridgeIO"
    "templateGenerationValid:linxcore.common.RenamedUop"
    "templateGeneration:linxcore.common.RenamedUop"
  )
  local -a echoes=(
    "[error]   val acceptedTemplateSidecar = bridge.io.templateSidecar"
    "[error]   val issueTemplateGenerationValid = issued.templateGenerationValid"
    "[error]   val issueTemplateGeneration = issued.templateGeneration"
  )
  local -a carets=(
    "[error]                                           ^"
    "[error]                                             ^"
    "[error]                                        ^"
  )

  [[ "${emit_rc}" -eq 1 ]] || return 1
  state=0
  mode=""

  while IFS= read -r record; do
    if [[ "${state}" -lt 9 ]]; then
      field_index=$((state / 3))
      triplet_phase=$((state % 3))
      case "${triplet_phase}" in
        0)
          if [[ "${state}" -eq 0 ]]; then
            if [[ "${record}" == "[error] ${PROBE_SOURCE}:"* ]]; then
              mode="cold"
            elif [[ "${record}" == "[error] \${BASE}${PROBE_DIAGNOSTIC_SUFFIX}:"* ]]; then
              mode="cached"
            else
              return 1
            fi
          fi
          if [[ "${mode}" == "cold" ]]; then
            expected_source="${PROBE_SOURCE}"
          else
            expected_source="\${BASE}${PROBE_DIAGNOSTIC_SUFFIX}"
          fi
          local member="${fields[${field_index}]%%:*}"
          local owner="${fields[${field_index}]#*:}"
          primary_prefix="[error] ${expected_source}:"
          primary_suffix=": value ${member} is not a member of ${owner}"
          [[ "${record}" == "${primary_prefix}"*"${primary_suffix}" ]] || return 1
          location="${record#"${primary_prefix}"}"
          location="${location%"${primary_suffix}"}"
          [[ "${location}" =~ ^[0-9]+:[0-9]+$ ]] || return 1
          ;;
        1)
          [[ "${record}" == "${echoes[${field_index}]}" ]] || return 1
          ;;
        2)
          [[ "${record}" == "${carets[${field_index}]}" ]] || return 1
          ;;
      esac
      state=$((state + 1))
    elif [[ "${mode}" == "cold" ]]; then
      case "${state}" in
        9) [[ "${record}" == "[error] three errors found" ]] || return 1 ;;
        10) [[ "${record}" == "[error] (Test / compileIncremental) Compilation failed" ]] || return 1 ;;
        11) [[ "${record}" =~ ^\[error\]\ elapsed\ time:\ [0-9]+\ s,\ cache\ [0-9]+%,\ [0-9]+\ disk\ cache\ hits,\ [0-9]+\ onsite\ tasks?$ ]] || return 1 ;;
        *) return 1 ;;
      esac
      state=$((state + 1))
    else
      case "${state}" in
        9) [[ "${record}" == '[error] sbt.util.CachedCompileFailure$$anon$1: Compilation failed' ]] || return 1 ;;
        10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29)
          stack_index=$((state - 10))
          [[ "${record}" == "${CACHED_STACK_LINES[${stack_index}]}" ]] || return 1
          ;;
        30) [[ "${record}" == '[error] (Test / compileIncremental) sbt.util.CachedCompileFailure$$anon$1: Compilation failed' ]] || return 1 ;;
        31) [[ "${record}" =~ ^\[error\]\ elapsed\ time:\ [0-9]+\ s,\ cache\ 100%,\ [0-9]+\ cached-failure\ cache\ hits?,\ [0-9]+\ disk\ cache\ hits$ ]] || return 1 ;;
        *) return 1 ;;
      esac
      state=$((state + 1))
    fi
  done < <(grep -E '^\[error\]' "${log}" || true)

  [[ "${mode}" == "cold" && "${state}" -eq 12 ]] ||
    [[ "${mode}" == "cached" && "${state}" -eq 32 ]]
}

run_self_test() {
  local dir exact cached subset superset wrong foreign same_file unlocated timeout setup duplicate reordered zero_rc
  dir="$(mktemp -d)"
  exact="${dir}/exact.log"
  cached="${dir}/cached.log"
  subset="${dir}/subset.log"
  superset="${dir}/superset.log"
  wrong="${dir}/wrong.log"
  foreign="${dir}/foreign.log"
  same_file="${dir}/same-file.log"
  unlocated="${dir}/unlocated.log"
  timeout="${dir}/timeout.log"
  setup="${dir}/setup.log"
  duplicate="${dir}/duplicate.log"
  reordered="${dir}/reordered.log"
  zero_rc="${dir}/zero-rc.log"

  emit_primary_records() {
    local source_path="$1"
    echo "[error] ${source_path}:198:43: value templateSidecar is not a member of linxcore.rename.ScalarDecodeRenameBridgeIO"
    echo "[error]   val acceptedTemplateSidecar = bridge.io.templateSidecar"
    echo "[error]                                           ^"
    echo "[error] ${source_path}:230:45: value templateGenerationValid is not a member of linxcore.common.RenamedUop"
    echo "[error]   val issueTemplateGenerationValid = issued.templateGenerationValid"
    echo "[error]                                             ^"
    echo "[error] ${source_path}:231:40: value templateGeneration is not a member of linxcore.common.RenamedUop"
    echo "[error]   val issueTemplateGeneration = issued.templateGeneration"
    echo "[error]                                        ^"
  }

  {
    emit_primary_records "${PROBE_SOURCE}"
    echo "[error] three errors found"
    echo "[error] (Test / compileIncremental) Compilation failed"
    echo "[error] elapsed time: 3 s, cache 95%, 22 disk cache hits, 1 onsite task"
  } >"${exact}"
  {
    emit_primary_records "\${BASE}${PROBE_DIAGNOSTIC_SUFFIX}"
    echo '[error] sbt.util.CachedCompileFailure$$anon$1: Compilation failed'
    printf '%s\n' "${CACHED_STACK_LINES[@]}"
    echo '[error] (Test / compileIncremental) sbt.util.CachedCompileFailure$$anon$1: Compilation failed'
    echo "[error] elapsed time: 1 s, cache 100%, 1 cached-failure cache hit, 22 disk cache hits"
  } >"${cached}"
  head -n 3 "${exact}" >"${subset}"
  { cat "${exact}"; echo "[error] ${PROBE_SOURCE}:1:1: value extra is not a member of linxcore.rename.ScalarDecodeRenameBridgeIO"; } >"${superset}"
  sed 's/templateGenerationValid/templateGenerationReady/' "${exact}" >"${wrong}"
  { cat "${exact}"; echo "[error] /tmp/Other.scala:1:1: value boom is not a member of Other"; } >"${foreign}"
  { cat "${exact}"; echo "[error] ${PROBE_SOURCE}:99:1: type mismatch"; } >"${same_file}"
  { cat "${exact}"; echo "[error] value boom is not a member of Other"; } >"${unlocated}"
  { cat "${exact}"; echo "[error] timed out waiting for sbt server"; } >"${timeout}"
  { cat "${exact}"; echo "[error] Connection refused while initializing compiler bridge"; } >"${setup}"
  { cat "${exact}"; sed -n '1p' "${exact}"; } >"${duplicate}"
  { sed -n '4,6p' "${exact}"; sed -n '1,3p' "${exact}"; sed -n '7,$p' "${exact}"; } >"${reordered}"
  cp "${exact}" "${zero_rc}"

  classify_exact_missing_interface "${exact}" 1 ||
    { rm -rf -- "${dir}"; echo "error: exact current-red classifier rejected its positive case" >&2; return 1; }
  classify_exact_missing_interface "${cached}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cached current-red classifier rejected its positive case" >&2; return 1; }
  ! classify_exact_missing_interface "${subset}" 1 ||
    { rm -rf -- "${dir}"; echo "error: subset passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${superset}" 1 ||
    { rm -rf -- "${dir}"; echo "error: superset passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${wrong}" 1 ||
    { rm -rf -- "${dir}"; echo "error: wrong member passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${foreign}" 1 ||
    { rm -rf -- "${dir}"; echo "error: foreign failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${same_file}" 1 ||
    { rm -rf -- "${dir}"; echo "error: same-file extra failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unlocated}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unlocated failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${timeout}" 1 ||
    { rm -rf -- "${dir}"; echo "error: timeout contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${setup}" 1 ||
    { rm -rf -- "${dir}"; echo "error: setup contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${reordered}" 1 ||
    { rm -rf -- "${dir}"; echo "error: reordered records passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${zero_rc}" 0 ||
    { rm -rf -- "${dir}"; echo "error: zero return code passed current-red classifier" >&2; return 1; }

  rm -rf -- "${dir}"
  echo "bctrl-template-accepted-sidecar-handoff-runner-self-test: PASS"
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
  echo "error: Verilator is required for the accepted sidecar handoff probe" >&2
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
      echo "bctrl-template-accepted-sidecar-handoff-current-red: PASS (exact missing bridge sidecar and issue generation interface)"
      exit 0
    fi
    echo "bctrl-template-accepted-sidecar-handoff: RED (exact missing bridge sidecar and issue generation interface)" >&2
    exit 1
  fi
  echo "error: emit failed for a reason outside the exact R965 missing interface" >&2
  cat "${EMIT_LOG}" >&2
  exit 2
fi

if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
  echo "error: current-red mode requested, but the real DUT exposes the accepted sidecar handoff interface" >&2
  exit 1
fi

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' -print | sort)
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no emitted SystemVerilog under ${SV_DIR}" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module ScalarDecodeRenameBridge' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real ScalarDecodeRenameBridge" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module TemplateRenameSidecarTable' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real TemplateRenameSidecarTable" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module ScalarIssueFabric' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real ScalarIssueFabric" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module BlockControlTemplateSequencer' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real BlockControlTemplateSequencer" >&2
  exit 2
fi

verilator \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${TESTBENCH}" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o bctrl_template_accepted_sidecar_handoff_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${OBJ_DIR}/bctrl_template_accepted_sidecar_handoff_probe_tb" >"${TB_LOG}" 2>&1
cat "${TB_LOG}"
