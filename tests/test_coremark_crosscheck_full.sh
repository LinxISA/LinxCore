#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
TMP_DIR="$(mktemp -d -t linxcore_coremark_full.XXXXXX)"
CORE_MEMH="${TMP_DIR}/coremark_from_elf.memh"
QEMU_TRACE="${TMP_DIR}/coremark_qemu_commit.jsonl"
DUT_TRACE="${TMP_DIR}/coremark_dut_commit.jsonl"
RAW_TRACE="${TMP_DIR}/coremark_raw_events.jsonl"
COMMIT_TEXT="${TMP_DIR}/coremark_dut_commit.txt"
LINXTRACE_OUT="${TMP_DIR}/coremark_full.linxtrace"
QEMU_FIFO="${TMP_DIR}/qemu_trace.fifo"
LLVM_READELF="${LLVM_READELF:-${LINX_ROOT}/compiler/llvm/build-linxisa-clang/bin/llvm-readelf}"
KEEP_TMP="${COREMARK_KEEP_TMP:-0}"
if [[ -n "${QEMU_BIN:-}" ]]; then
  QEMU_BIN="${QEMU_BIN}"
elif [[ -x "${LINX_ROOT}/emulator/qemu/build-linx/qemu-system-linx64" ]]; then
  # Match the Chisel cross-check lane: this build emits LINX_COMMIT_TRACE.
  QEMU_BIN="${LINX_ROOT}/emulator/qemu/build-linx/qemu-system-linx64"
else
  QEMU_BIN="${LINX_ROOT}/emulator/qemu/build/qemu-system-linx64}"
fi
QEMU_MAX_SECONDS="${QEMU_MAX_SECONDS:-30}"
PYC_MAX_COMMITS="${PYC_MAX_COMMITS:-0}"
PYC_MAX_CYCLES="${PYC_MAX_CYCLES:-50000000}"

resolve_coremark_elf() {
  local cand
  for cand in \
    "${LINX_ROOT}/workloads/generated/elf/coremark.elf" \
    "/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" \
    "/Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl/elf/coremark/coremark.elf" \
    "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl_1000/elf/coremark/coremark.elf" \
    "${ROOT_DIR}/tests/benchmarks_latest_llvm_musl/elf/coremark/coremark.elf"
  do
    if [[ -f "${cand}" ]]; then
      echo "${cand}"
      return 0
    fi
  done
  return 1
}

CORE_ELF="$(resolve_coremark_elf)" || {
  echo "error: missing CoreMark ELF in canonical or fixture locations" >&2
  exit 2
}

qemu_pids_for_elf() {
  ps -Ao pid=,command= | awk -v q="${QEMU_BIN}" -v e="-kernel ${CORE_ELF}" '
    index($0, q) && index($0, e) { print $1 }
  '
}

stop_qemu_for_elf() {
  local pids=""
  pids="$(qemu_pids_for_elf || true)"
  if [[ -n "${pids}" ]]; then
    while IFS= read -r pid; do
      [[ -z "${pid}" ]] && continue
      kill "${pid}" >/dev/null 2>&1 || true
    done <<< "${pids}"
    sleep 0.2
    pids="$(qemu_pids_for_elf || true)"
    if [[ -n "${pids}" ]]; then
      while IFS= read -r pid; do
        [[ -z "${pid}" ]] && continue
        kill -9 "${pid}" >/dev/null 2>&1 || true
      done <<< "${pids}"
    fi
  fi
}

cleanup() {
  if [[ -n "${qemu_pid:-}" ]]; then kill "${qemu_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${qemu_child_pid:-}" ]]; then kill "${qemu_child_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${reader_pid:-}" ]]; then kill "${reader_pid}" >/dev/null 2>&1 || true; fi
  stop_qemu_for_elf
  rm -f "${QEMU_FIFO}"
  if [[ "${KEEP_TMP}" == "1" ]]; then
    echo "coremark full evidence retained at ${TMP_DIR}" >&2
  else
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT INT TERM

if [[ ! -f "${CORE_ELF}" ]]; then
  echo "error: missing CoreMark ELF: ${CORE_ELF}" >&2
  exit 2
fi
if [[ ! -x "${LLVM_READELF}" ]]; then
  echo "error: missing llvm-readelf: ${LLVM_READELF}" >&2
  exit 2
fi
if [[ ! -x "${QEMU_BIN}" ]]; then
  echo "error: missing qemu binary: ${QEMU_BIN}" >&2
  exit 2
fi

BOOT_PC="$("${LLVM_READELF}" -h "${CORE_ELF}" | awk '/Entry point address:/ {print $4; exit}')"
if [[ -z "${BOOT_PC}" ]]; then
  echo "error: failed to extract ELF entry from ${CORE_ELF}" >&2
  exit 2
fi

bash "${ROOT_DIR}/tools/image/elf_to_memh.sh" "${CORE_ELF}" "${CORE_MEMH}" >/dev/null

PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP=0x0000000007fefff0 \
PYC_MAX_COMMITS="${PYC_MAX_COMMITS}" \
PYC_MAX_CYCLES="${PYC_MAX_CYCLES}" \
PYC_IDLE_LOOP_PC_A="${PYC_IDLE_LOOP_PC_A:-0x00000000000124A6}" \
PYC_IDLE_LOOP_PC_B="${PYC_IDLE_LOOP_PC_B:-0x00000000000124A8}" \
PYC_IDLE_LOOP_STREAK="${PYC_IDLE_LOOP_STREAK:-2048}" \
PYC_LINXTRACE=0 \
PYC_RAW_TRACE="${RAW_TRACE}" \
PYC_COMMIT_TRACE="${DUT_TRACE}" \
PYC_COMMIT_TRACE_TEXT="${COMMIT_TEXT}" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -DNDEBUG}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${CORE_MEMH}" >/dev/null

if [[ ! -s "${DUT_TRACE}" ]]; then
  echo "error: missing/empty DUT trace: ${DUT_TRACE}" >&2
  exit 3
fi

DUT_COMMITS="$(awk 'END { print NR + 0 }' "${DUT_TRACE}")"
if [[ "${DUT_COMMITS}" -le 0 ]]; then
  echo "error: DUT trace produced zero commits" >&2
  exit 3
fi

mkfifo "${QEMU_FIFO}"
head -n "${DUT_COMMITS}" < "${QEMU_FIFO}" > "${QEMU_TRACE}" &
reader_pid=$!

bash "${ROOT_DIR}/tools/qemu/run_qemu_commit_trace.sh" \
  --qemu-bin "${QEMU_BIN}" \
  --max-seconds "${QEMU_MAX_SECONDS}" \
  --elf "${CORE_ELF}" \
  --out "${QEMU_FIFO}" \
  -- \
  -nographic -monitor none -machine virt -kernel "${CORE_ELF}" >/dev/null &
qemu_pid=$!
qemu_child_pid=""

set +e
wait "${reader_pid}"
reader_rc=$?
if kill -0 "${qemu_pid}" >/dev/null 2>&1; then
  qemu_child_pid="$(pgrep -P "${qemu_pid}" | head -n1 || true)"
  if [[ -n "${qemu_child_pid}" ]]; then
    kill "${qemu_child_pid}" >/dev/null 2>&1 || true
  fi
  kill "${qemu_pid}" >/dev/null 2>&1 || true
fi
stop_qemu_for_elf
wait "${qemu_pid}"
qemu_rc=$?
set -e

if [[ "${reader_rc}" -ne 0 ]]; then
  echo "error: failed to capture bounded QEMU trace (reader_rc=${reader_rc})" >&2
  exit 3
fi
if [[ ! -s "${QEMU_TRACE}" ]]; then
  echo "error: missing/empty QEMU trace: ${QEMU_TRACE}" >&2
  exit 3
fi
if [[ "${qemu_rc}" -ne 0 && "${qemu_rc}" -ne 143 ]]; then
  echo "warn: QEMU producer exited with status ${qemu_rc}; continuing with captured prefix" >&2
fi

QEMU_COMMITS="$(awk 'END { print NR + 0 }' "${QEMU_TRACE}")"
if [[ "${QEMU_COMMITS}" -lt "${DUT_COMMITS}" ]]; then
  echo "error: QEMU trace shorter than DUT trace: qemu=${QEMU_COMMITS} dut=${DUT_COMMITS}" >&2
  exit 3
fi

python3 "${ROOT_DIR}/tools/trace/build_linxtrace_view.py" \
  --raw "${RAW_TRACE}" \
  --out "${LINXTRACE_OUT}" \
  --commit-text "${COMMIT_TEXT}" \
  --elf "${CORE_ELF}" >/dev/null

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" \
  "${LINXTRACE_OUT}" \
  --require-stages "F0,F3,D1,D3,IQ,BROB,CMT" \
  --single-stage-per-cycle >/dev/null

python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode failfast \
  --max-commits "${DUT_COMMITS}" \
  --report-dir "${TMP_DIR}" >/dev/null

echo "coremark xcheck full: ok (commits=${DUT_COMMITS})"
