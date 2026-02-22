#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

ELF=""
MEMH=""
OUT_DIR=""
MODE="diagnostic"
MAX_COMMITS="1000"
TB_MAX_CYCLES="50000000"
BOOT_PC=""
BOOT_SP="${BOOT_SP:-0x0000000007fefff0}"
QEMU_BIN="${QEMU_BIN:-/Users/zhoubot/qemu/build-linx/qemu-system-linx64}"
QEMU_MAX_SECONDS="${QEMU_MAX_SECONDS:-0}"
LLVM_READELF="${LLVM_READELF:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf}"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") --elf <program.elf> [--memh <program.memh>] [options]

Options:
  --out-dir <dir>           Output dir (default: /tmp/linxcore_fifo_xcheck.XXXXXX)
  --mode <diagnostic|failfast>
  --max-commits <int>       Compare window and DUT stop point (default: 1000)
  --tb-max-cycles <int>     DUT max cycles (default: 50000000)
  --boot-pc <hex>           Override DUT boot PC (default: ELF entry)
  --boot-sp <hex>           DUT boot SP (default: 0x0000000007fefff0)
  --qemu-bin <path>         QEMU binary path
  --qemu-max-seconds <int>  Timeout passed to QEMU trace runner (0 disables)

Runs QEMU and LinxCore simultaneously using FIFOs, captures both traces,
then runs tools/trace/crosscheck_qemu_linxcore.py.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf) ELF="$2"; shift 2 ;;
    --memh) MEMH="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --max-commits) MAX_COMMITS="$2"; shift 2 ;;
    --tb-max-cycles) TB_MAX_CYCLES="$2"; shift 2 ;;
    --boot-pc) BOOT_PC="$2"; shift 2 ;;
    --boot-sp) BOOT_SP="$2"; shift 2 ;;
    --qemu-bin) QEMU_BIN="$2"; shift 2 ;;
    --qemu-max-seconds) QEMU_MAX_SECONDS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "error: unknown arg: $1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -z "${ELF}" ]]; then
  echo "error: --elf is required" >&2
  usage
  exit 2
fi
if [[ ! -f "${ELF}" ]]; then
  echo "error: ELF not found: ${ELF}" >&2
  exit 2
fi
if [[ "${MODE}" != "diagnostic" && "${MODE}" != "failfast" ]]; then
  echo "error: --mode must be diagnostic|failfast" >&2
  exit 2
fi

if [[ -z "${BOOT_PC}" ]]; then
  if [[ ! -x "${LLVM_READELF}" ]]; then
    echo "error: missing llvm-readelf: ${LLVM_READELF}" >&2
    exit 2
  fi
  BOOT_PC="$(${LLVM_READELF} -h "${ELF}" | awk '/Entry point address:/ {print $4; exit}')"
  if [[ -z "${BOOT_PC}" ]]; then
    echo "error: failed to read ELF entry from ${ELF}" >&2
    exit 2
  fi
fi

if [[ -z "${MEMH}" ]]; then
  MEMH="${ELF%.elf}.memh"
  if [[ ! -f "${MEMH}" ]]; then
    bash "${ROOT_DIR}/tools/image/elf_to_memh.sh" "${ELF}" "${MEMH}" >/dev/null
  fi
fi
if [[ ! -f "${MEMH}" ]]; then
  echo "error: memh not found: ${MEMH}" >&2
  exit 2
fi

created_tmp=0
if [[ -z "${OUT_DIR}" ]]; then
  OUT_DIR="$(mktemp -d -t linxcore_fifo_xcheck.XXXXXX)"
  created_tmp=1
else
  mkdir -p "${OUT_DIR}"
fi

QEMU_FIFO="${OUT_DIR}/qemu_trace.fifo"
DUT_FIFO="${OUT_DIR}/dut_trace.fifo"
QEMU_TRACE="${OUT_DIR}/qemu_trace.jsonl"
DUT_TRACE="${OUT_DIR}/dut_trace.jsonl"
QEMU_TRACE_TXT="${OUT_DIR}/qemu_trace.txt"
DUT_TRACE_TXT="${OUT_DIR}/dut_trace.txt"
REPORT_DIR="${OUT_DIR}/report"

qemu_pids_for_elf() {
  ps -Ao pid=,command= | awk -v q="${QEMU_BIN}" -v e="-kernel ${ELF}" '
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
  local rc=$?
  if [[ -n "${qemu_pid:-}" ]]; then kill "${qemu_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${qemu_child_pid:-}" ]]; then kill "${qemu_child_pid}" >/dev/null 2>&1 || true; fi
  stop_qemu_for_elf
  if [[ -n "${dut_pid:-}" ]]; then kill "${dut_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${reader_q_pid:-}" ]]; then kill "${reader_q_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${reader_d_pid:-}" ]]; then kill "${reader_d_pid}" >/dev/null 2>&1 || true; fi
  rm -f "${QEMU_FIFO}" "${DUT_FIFO}"
  if [[ "${created_tmp}" -eq 1 && ${rc} -eq 0 ]]; then
    :
  fi
  return ${rc}
}
trap cleanup EXIT INT TERM

rm -f "${QEMU_FIFO}" "${DUT_FIFO}" "${QEMU_TRACE}" "${DUT_TRACE}"
mkfifo "${QEMU_FIFO}" "${DUT_FIFO}"

cat "${QEMU_FIFO}" | tee "${QEMU_TRACE}" >/dev/null &
reader_q_pid=$!
cat "${DUT_FIFO}" | tee "${DUT_TRACE}" >/dev/null &
reader_d_pid=$!

bash "${ROOT_DIR}/tools/qemu/run_qemu_commit_trace.sh" \
  --qemu-bin "${QEMU_BIN}" \
  --max-seconds "${QEMU_MAX_SECONDS}" \
  --elf "${ELF}" \
  --out "${QEMU_FIFO}" \
  -- \
  -nographic -monitor none -machine virt -kernel "${ELF}" >/dev/null &
qemu_pid=$!
qemu_child_pid=""

PYC_BOOT_PC="${BOOT_PC}" \
PYC_BOOT_SP="${BOOT_SP}" \
PYC_MAX_COMMITS="${MAX_COMMITS}" \
PYC_MAX_CYCLES="${TB_MAX_CYCLES}" \
PYC_COMMIT_TRACE="${DUT_FIFO}" \
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O2 -g0}" \
  bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >/dev/null &
dut_pid=$!

set +e
wait "${dut_pid}"
dut_rc=$?
if kill -0 "${qemu_pid}" >/dev/null 2>&1; then
  # DUT reached requested compare window; stop QEMU producer so flow does not
  # depend on external timeout binaries.
  qemu_child_pid="$(pgrep -P "${qemu_pid}" | head -n1 || true)"
  if [[ -n "${qemu_child_pid}" ]]; then
    kill "${qemu_child_pid}" >/dev/null 2>&1 || true
  fi
  kill "${qemu_pid}" >/dev/null 2>&1 || true
fi
stop_qemu_for_elf
wait "${qemu_pid}"
qemu_rc=$?
wait "${reader_q_pid}"
reader_q_rc=$?
wait "${reader_d_pid}"
reader_d_rc=$?
set -e

if [[ ! -s "${QEMU_TRACE}" ]]; then
  echo "error: missing/empty QEMU trace: ${QEMU_TRACE}" >&2
  exit 3
fi
if [[ ! -s "${DUT_TRACE}" ]]; then
  echo "error: missing/empty DUT trace: ${DUT_TRACE}" >&2
  exit 3
fi

if [[ "${qemu_rc}" -ne 0 ]]; then
  echo "warn: QEMU producer exited with status ${qemu_rc}; continuing with captured trace" >&2
fi
if [[ "${dut_rc}" -ne 0 ]]; then
  echo "warn: DUT producer exited with status ${dut_rc}; continuing with captured trace" >&2
fi
if [[ "${reader_q_rc}" -ne 0 || "${reader_d_rc}" -ne 0 ]]; then
  echo "warn: FIFO reader exit status qemu=${reader_q_rc} dut=${reader_d_rc}" >&2
fi

mkdir -p "${REPORT_DIR}"
python3 "${ROOT_DIR}/tools/trace/crosscheck_qemu_linxcore.py" \
  --qemu-trace "${QEMU_TRACE}" \
  --dut-trace "${DUT_TRACE}" \
  --mode "${MODE}" \
  --max-commits "${MAX_COMMITS}" \
  --report-dir "${REPORT_DIR}"

python3 "${ROOT_DIR}/tools/trace/commit_jsonl_to_text.py" \
  --input "${QEMU_TRACE}" \
  --output "${QEMU_TRACE_TXT}" \
  --objdump-elf "${ELF}" >/dev/null || true
python3 "${ROOT_DIR}/tools/trace/commit_jsonl_to_text.py" \
  --input "${DUT_TRACE}" \
  --output "${DUT_TRACE_TXT}" \
  --objdump-elf "${ELF}" >/dev/null || true

echo "qemu_trace=${QEMU_TRACE}"
echo "dut_trace=${DUT_TRACE}"
echo "qemu_trace_txt=${QEMU_TRACE_TXT}"
echo "dut_trace_txt=${DUT_TRACE_TXT}"
echo "report_dir=${REPORT_DIR}"
