#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r951-mccarthy-template-snapshot"
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

PROBE_SOURCE="${ROOT_DIR}/chisel/src/test/scala/linxcore/rename/ScalarDecodeRenameTemplateSnapshotBehaviorProbe.scala"
PROBE_DIAGNOSTIC_SUFFIX="/src/test/scala/linxcore/rename/ScalarDecodeRenameTemplateSnapshotBehaviorProbe.scala"
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
EMIT_LOG="${BUILD_DIR}/emit.log"
TB_LOG="${BUILD_DIR}/tb.log"
TOP_MODULE="ScalarDecodeRenameTemplateSnapshotBehaviorProbe"
EMIT_MAIN="linxcore.rename.EmitScalarDecodeRenameTemplateSnapshotBehaviorProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/backend_rename_template_snapshot_probe_tb.cpp"
RUNTIME_IDENTITY_PARTITION_RED="backend-rename-template-snapshot-probe: FAIL: FENTRY-post-rename-thread-selection: destination allocated from reserved per-STID identity partition"

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
  local record
  local state=0
  local mode=""
  local field_index
  local triplet_phase
  local expected_source
  local primary_prefix
  local primary_suffix
  local location
  local stack_index
  local -a fields=(
    templateSnapshotValid
    templateSnapshotGeneration
    templateSmapSnapshot
    templateCmapSnapshot
  )
  local -a echoes=(
    "[error]   private val snapshotValid = bridge.io.templateSnapshotValid"
    "[error]   private val snapshotGeneration = bridge.io.templateSnapshotGeneration"
    "[error]   private val smapSnapshot = bridge.io.templateSmapSnapshot"
    "[error]   private val cmapSnapshot = bridge.io.templateCmapSnapshot"
  )
  local -a carets=(
    "[error]                                         ^"
    "[error]                                              ^"
    "[error]                                        ^"
    "[error]                                        ^"
  )

  [[ "${emit_rc}" -eq 1 ]] || return 1

  while IFS= read -r record; do
    if [[ "${state}" -lt 12 ]]; then
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
          primary_prefix="[error] ${expected_source}:"
          primary_suffix=": value ${fields[${field_index}]} is not a member of linxcore.rename.ScalarDecodeRenameBridgeIO"
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
        12)
          [[ "${record}" == "[error] four errors found" ]] || return 1
          ;;
        13)
          [[ "${record}" == "[error] (Test / compileIncremental) Compilation failed" ]] || return 1
          ;;
        14)
          [[ "${record}" =~ ^\[error\]\ elapsed\ time:\ [0-9]+\ s,\ cache\ [0-9]+%,\ [0-9]+\ disk\ cache\ hits,\ [0-9]+\ onsite\ tasks?$ ]] || return 1
          ;;
        *)
          return 1
          ;;
      esac
      state=$((state + 1))
    else
      case "${state}" in
        12)
          [[ "${record}" == '[error] sbt.util.CachedCompileFailure$$anon$1: Compilation failed' ]] || return 1
          ;;
        13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29|30|31|32)
          stack_index=$((state - 13))
          [[ "${record}" == "${CACHED_STACK_LINES[${stack_index}]}" ]] || return 1
          ;;
        33)
          [[ "${record}" == '[error] (Test / compileIncremental) sbt.util.CachedCompileFailure$$anon$1: Compilation failed' ]] || return 1
          ;;
        34)
          [[ "${record}" =~ ^\[error\]\ elapsed\ time:\ [0-9]+\ s,\ cache\ 100%,\ [0-9]+\ cached-failure\ cache\ hits?,\ [0-9]+\ disk\ cache\ hits$ ]] || return 1
          ;;
        *)
          return 1
          ;;
      esac
      state=$((state + 1))
    fi
  done < <(grep -E '^\[error\]' "${log}" || true)

  [[ "${mode}" == "cold" && "${state}" -eq 15 ]] ||
    [[ "${mode}" == "cached" && "${state}" -eq 35 ]]
}

classify_exact_runtime_identity_partition_red() {
  local log="$1"
  local tb_rc="$2"
  local line_count
  local line

  [[ "${tb_rc}" -eq 1 ]] || return 1
  [[ -s "${log}" ]] || return 1
  line_count="$(wc -l <"${log}" | tr -d ' ')"
  [[ "${line_count}" -eq 1 ]] || return 1
  IFS= read -r line <"${log}"
  [[ "${line}" == "${RUNTIME_IDENTITY_PARTITION_RED}" ]]
}

outcome_status() {
  local outcome="$1"
  local expect_red="$2"
  case "${outcome}:${expect_red}" in
    runtime-red:1|green:0)
      echo 0
      ;;
    runtime-red:0|green:1)
      echo 1
      ;;
    *)
      echo 2
      ;;
  esac
}

run_self_test() {
  local dir exact cached subset superset wrong foreign same_file unlocated catastrophe plugin timeout setup
  local duplicate_primary unknown_echo duplicate_echo unknown_caret duplicate_caret
  local unknown_summary duplicate_summary unknown_timing duplicate_timing unknown_stack duplicate_stack
  local reordered_triplets caret_before_echo cold_tail_permutation cached_echo_in_stack
  local cached_heading_permutation cached_task_timing_permutation reordered_stack
  local runtime_exact runtime_extra runtime_wrong_label runtime_wrong_reason runtime_duplicate
  local runtime_empty runtime_wrong_rc runtime_setup_rc runtime_old_compile
  dir="$(mktemp -d)"
  exact="${dir}/exact.log"
  cached="${dir}/cached.log"
  subset="${dir}/subset.log"
  superset="${dir}/superset.log"
  wrong="${dir}/wrong.log"
  foreign="${dir}/foreign.log"
  same_file="${dir}/same-file.log"
  unlocated="${dir}/unlocated.log"
  catastrophe="${dir}/catastrophe.log"
  plugin="${dir}/plugin.log"
  timeout="${dir}/timeout.log"
  setup="${dir}/setup.log"
  duplicate_primary="${dir}/duplicate-primary.log"
  unknown_echo="${dir}/unknown-echo.log"
  duplicate_echo="${dir}/duplicate-echo.log"
  unknown_caret="${dir}/unknown-caret.log"
  duplicate_caret="${dir}/duplicate-caret.log"
  unknown_summary="${dir}/unknown-summary.log"
  duplicate_summary="${dir}/duplicate-summary.log"
  unknown_timing="${dir}/unknown-timing.log"
  duplicate_timing="${dir}/duplicate-timing.log"
  unknown_stack="${dir}/unknown-stack.log"
  duplicate_stack="${dir}/duplicate-stack.log"
  reordered_triplets="${dir}/reordered-triplets.log"
  caret_before_echo="${dir}/caret-before-echo.log"
  cold_tail_permutation="${dir}/cold-tail-permutation.log"
  cached_echo_in_stack="${dir}/cached-echo-in-stack.log"
  cached_heading_permutation="${dir}/cached-heading-permutation.log"
  cached_task_timing_permutation="${dir}/cached-task-timing-permutation.log"
  reordered_stack="${dir}/reordered-stack.log"
  runtime_exact="${dir}/runtime-exact.log"
  runtime_extra="${dir}/runtime-extra.log"
  runtime_wrong_label="${dir}/runtime-wrong-label.log"
  runtime_wrong_reason="${dir}/runtime-wrong-reason.log"
  runtime_duplicate="${dir}/runtime-duplicate.log"
  runtime_empty="${dir}/runtime-empty.log"
  runtime_wrong_rc="${dir}/runtime-wrong-rc.log"
  runtime_setup_rc="${dir}/runtime-setup-rc.log"
  runtime_old_compile="${dir}/runtime-old-compile.log"

  emit_primary_records() {
    local source_path="$1"
    local field
    local echo_line
    local caret_line
    for field in \
      templateSnapshotValid templateSnapshotGeneration \
      templateSmapSnapshot templateCmapSnapshot; do
      case "${field}" in
        templateSnapshotValid)
          echo_line="  private val snapshotValid = bridge.io.templateSnapshotValid"
          caret_line="                                        ^"
          ;;
        templateSnapshotGeneration)
          echo_line="  private val snapshotGeneration = bridge.io.templateSnapshotGeneration"
          caret_line="                                             ^"
          ;;
        templateSmapSnapshot)
          echo_line="  private val smapSnapshot = bridge.io.templateSmapSnapshot"
          caret_line="                                       ^"
          ;;
        templateCmapSnapshot)
          echo_line="  private val cmapSnapshot = bridge.io.templateCmapSnapshot"
          caret_line="                                       ^"
          ;;
      esac
      echo "[error] ${source_path}:1:1: value ${field} is not a member of linxcore.rename.ScalarDecodeRenameBridgeIO"
      echo "[error] ${echo_line}"
      echo "[error] ${caret_line}"
    done
  }

  {
    emit_primary_records "${PROBE_SOURCE}"
    echo "[error] four errors found"
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
  {
    cat "${exact}"
    echo "[error] ${PROBE_SOURCE}:2:1: value unexpectedTemplateField is not a member of linxcore.rename.ScalarDecodeRenameBridgeIO"
  } >"${superset}"
  sed 's/value templateSmapSnapshot/value wrongTemplateSmapSnapshot/' "${exact}" >"${wrong}"
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
    echo "[error] value boom is not a member of Other"
  } >"${unlocated}"
  {
    cat "${exact}"
    echo "[error] arbitrary unlocated compiler catastrophe"
  } >"${catastrophe}"
  {
    cat "${exact}"
    echo "[error] java.lang.IllegalStateException: compiler plugin exploded in phase typer"
  } >"${plugin}"
  {
    cat "${exact}"
    echo "[error] timed out waiting for sbt server"
  } >"${timeout}"
  {
    cat "${exact}"
    echo "[error] Connection refused while initializing compiler bridge"
  } >"${setup}"
  {
    cat "${exact}"
    echo "[error] ${PROBE_SOURCE}:1:1: value templateSnapshotValid is not a member of linxcore.rename.ScalarDecodeRenameBridgeIO"
  } >"${duplicate_primary}"
  {
    cat "${exact}"
    echo "[error]   private val otherSnapshot = bridge.io.otherSnapshot"
  } >"${unknown_echo}"
  {
    cat "${exact}"
    echo "[error]   private val snapshotValid = bridge.io.templateSnapshotValid"
  } >"${duplicate_echo}"
  {
    cat "${exact}"
    echo "[error] ~~^~~"
  } >"${unknown_caret}"
  {
    cat "${exact}"
    echo "[error]                                         ^"
  } >"${duplicate_caret}"
  {
    cat "${exact}"
    echo "[error] five errors found"
  } >"${unknown_summary}"
  {
    cat "${exact}"
    echo "[error] four errors found"
  } >"${duplicate_summary}"
  {
    cat "${exact}"
    echo "[error] elapsed time: yesterday"
  } >"${unknown_timing}"
  {
    cat "${exact}"
    echo "[error] elapsed time: 4 s, cache 95%, 22 disk cache hits, 1 onsite task"
  } >"${duplicate_timing}"
  {
    cat "${cached}"
    echo $'[error] \tat compiler.plugin.UnknownFrame.run(UnknownFrame.scala:1)'
  } >"${unknown_stack}"
  {
    cat "${cached}"
    echo "${CACHED_STACK_LINES[0]}"
  } >"${duplicate_stack}"
  {
    sed -n '4,6p' "${exact}"
    sed -n '1,3p' "${exact}"
    sed -n '7,$p' "${exact}"
  } >"${reordered_triplets}"
  {
    sed -n '1p' "${exact}"
    sed -n '3p' "${exact}"
    sed -n '2p' "${exact}"
    sed -n '4,$p' "${exact}"
  } >"${caret_before_echo}"
  {
    sed -n '1,12p' "${exact}"
    sed -n '15p' "${exact}"
    sed -n '14p' "${exact}"
    sed -n '13p' "${exact}"
  } >"${cold_tail_permutation}"
  {
    sed -n '1p' "${cached}"
    sed -n '3,18p' "${cached}"
    sed -n '2p' "${cached}"
    sed -n '19,$p' "${cached}"
  } >"${cached_echo_in_stack}"
  {
    sed -n '1,12p' "${cached}"
    sed -n '14,33p' "${cached}"
    sed -n '13p' "${cached}"
    sed -n '34,35p' "${cached}"
  } >"${cached_heading_permutation}"
  {
    sed -n '1,33p' "${cached}"
    sed -n '35p' "${cached}"
    sed -n '34p' "${cached}"
  } >"${cached_task_timing_permutation}"
  {
    sed -n '1,13p' "${cached}"
    sed -n '15p' "${cached}"
    sed -n '14p' "${cached}"
    sed -n '16,$p' "${cached}"
  } >"${reordered_stack}"
  printf '%s\n' "${RUNTIME_IDENTITY_PARTITION_RED}" >"${runtime_exact}"
  {
    printf '%s\n' "${RUNTIME_IDENTITY_PARTITION_RED}"
    echo "backend-rename-template-snapshot-probe: extra diagnostic"
  } >"${runtime_extra}"
  echo "backend-rename-template-snapshot-probe: FAIL: FEXIT-active-stid-mismatch: destination allocated from reserved per-STID identity partition" >"${runtime_wrong_label}"
  echo "backend-rename-template-snapshot-probe: FAIL: FENTRY-post-rename-thread-selection: SMAP mismatch at arch 0" >"${runtime_wrong_reason}"
  {
    printf '%s\n' "${RUNTIME_IDENTITY_PARTITION_RED}"
    printf '%s\n' "${RUNTIME_IDENTITY_PARTITION_RED}"
  } >"${runtime_duplicate}"
  : >"${runtime_empty}"
  cp "${runtime_exact}" "${runtime_wrong_rc}"
  cp "${runtime_exact}" "${runtime_setup_rc}"
  cp "${exact}" "${runtime_old_compile}"

  classify_exact_missing_interface "${exact}" 1 ||
    { rm -rf -- "${dir}"; echo "error: exact current-red classifier rejected its positive case" >&2; return 1; }
  classify_exact_missing_interface "${cached}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cached-path current-red classifier rejected its positive case" >&2; return 1; }
  ! classify_exact_missing_interface "${subset}" 1 ||
    { rm -rf -- "${dir}"; echo "error: missing-member subset passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${superset}" 1 ||
    { rm -rf -- "${dir}"; echo "error: missing-member superset passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${wrong}" 1 ||
    { rm -rf -- "${dir}"; echo "error: wrong missing member passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${foreign}" 1 ||
    { rm -rf -- "${dir}"; echo "error: foreign compile failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${same_file}" 1 ||
    { rm -rf -- "${dir}"; echo "error: same-file compile failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unlocated}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unlocated compile failure passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${catastrophe}" 1 ||
    { rm -rf -- "${dir}"; echo "error: arbitrary unlocated catastrophe passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${plugin}" 1 ||
    { rm -rf -- "${dir}"; echo "error: compiler-plugin exception passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${timeout}" 1 ||
    { rm -rf -- "${dir}"; echo "error: timeout contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${setup}" 1 ||
    { rm -rf -- "${dir}"; echo "error: setup contamination passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate_primary}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate primary diagnostic passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unknown_echo}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unknown source echo passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate_echo}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate source echo passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unknown_caret}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unknown caret record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate_caret}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate caret record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unknown_summary}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unknown summary record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate_summary}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate summary record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unknown_timing}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unknown timing record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate_timing}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate timing record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${unknown_stack}" 1 ||
    { rm -rf -- "${dir}"; echo "error: unknown stack record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${duplicate_stack}" 1 ||
    { rm -rf -- "${dir}"; echo "error: duplicate stack record passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${reordered_triplets}" 1 ||
    { rm -rf -- "${dir}"; echo "error: reordered field triplets passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${caret_before_echo}" 1 ||
    { rm -rf -- "${dir}"; echo "error: caret-before-echo stream passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${cold_tail_permutation}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cold task-summary-timing permutation passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${cached_echo_in_stack}" 1 ||
    { rm -rf -- "${dir}"; echo "error: allowed source echo interleaved into cached stack passed classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${cached_heading_permutation}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cached heading permutation passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${cached_task_timing_permutation}" 1 ||
    { rm -rf -- "${dir}"; echo "error: cached task-timing permutation passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${reordered_stack}" 1 ||
    { rm -rf -- "${dir}"; echo "error: reordered cached stack passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${exact}" 0 ||
    { rm -rf -- "${dir}"; echo "error: zero return code passed current-red classifier" >&2; return 1; }
  ! classify_exact_missing_interface "${exact}" 2 ||
    { rm -rf -- "${dir}"; echo "error: unexpected return code passed current-red classifier" >&2; return 1; }

  classify_exact_runtime_identity_partition_red "${runtime_exact}" 1 ||
    { rm -rf -- "${dir}"; echo "error: exact runtime-red classifier rejected its positive case" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_wrong_rc}" 0 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted zero rc" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_setup_rc}" 2 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted setup rc" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_extra}" 1 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted extra output" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_wrong_label}" 1 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted wrong label" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_wrong_reason}" 1 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted wrong reason" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_duplicate}" 1 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted duplicate output" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_empty}" 1 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted empty output" >&2; return 1; }
  ! classify_exact_runtime_identity_partition_red "${runtime_old_compile}" 1 ||
    { rm -rf -- "${dir}"; echo "error: runtime classifier accepted old compile red" >&2; return 1; }

  [[ "$(outcome_status missing 1)" -eq 2 ]]
  [[ "$(outcome_status missing 0)" -eq 2 ]]
  [[ "$(outcome_status runtime-red 1)" -eq 0 ]]
  [[ "$(outcome_status runtime-red 0)" -eq 1 ]]
  [[ "$(outcome_status green 1)" -eq 1 ]]
  [[ "$(outcome_status green 0)" -eq 0 ]]
  [[ "$(outcome_status unexpected 0)" -eq 2 ]]
  rm -rf -- "${dir}"
  echo "backend-rename-template-snapshot-runner-self-test: PASS"
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
  echo "error: Verilator is required for the rename template snapshot probe" >&2
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
    echo "error: exact four missing bridge IO members is now an unexpected interface regression" >&2
    exit 2
  fi
  echo "error: emit failed for a reason other than the exact four missing bridge IO members" >&2
  cat "${EMIT_LOG}" >&2
  exit 2
fi

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${SV_DIR}/${source}")
done <"${SV_DIR}/filelist.f"
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no emitted SystemVerilog under ${SV_DIR}" >&2
  exit 2
fi
if ! grep -R -q --include='*.sv' 'module ScalarDecodeRenameBridge' "${SV_DIR}"; then
  echo "error: emitted probe does not bind the real ScalarDecodeRenameBridge" >&2
  exit 2
fi

verilator \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${TESTBENCH}" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o backend_rename_template_snapshot_probe_tb \
  -CFLAGS '-std=c++17 -O2'

set +e
"${OBJ_DIR}/backend_rename_template_snapshot_probe_tb" >"${TB_LOG}" 2>&1
tb_rc=$?
set -e

if [[ "${tb_rc}" -eq 0 ]]; then
  cat "${TB_LOG}"
  if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
    echo "error: current-red mode requested, but the real bridge passed the disjoint identity-map verifier" >&2
    exit 1
  fi
  exit 0
fi

if classify_exact_runtime_identity_partition_red "${TB_LOG}" "${tb_rc}"; then
  if [[ "${EXPECT_CURRENT_RED}" -eq 1 ]]; then
    echo "backend-rename-template-snapshot-current-red: PASS (exact two-STID identity-partition runtime red)"
    exit 0
  fi
  cat "${TB_LOG}" >&2
  echo "backend-rename-template-snapshot: RED (exact two-STID identity-partition runtime red)" >&2
  exit 1
fi

echo "error: testbench failed for a reason other than the exact two-STID identity-partition runtime red" >&2
cat "${TB_LOG}" >&2
exit 2
