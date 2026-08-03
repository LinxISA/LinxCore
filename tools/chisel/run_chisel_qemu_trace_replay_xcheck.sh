#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/chisel-qemu-trace-replay-xcheck}"
ELF=""
QEMU_TRACE=""
QEMU_BIN=""
MAX_COMMITS="${MAX_COMMITS:-32}"
REPLAY_ROWS=""
MAX_SECONDS="${MAX_SECONDS:-30}"
PC_LO=""
PC_HI=""
DRY_RUN=0

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") --elf <program.elf> [options] -- [qemu args]
  $(basename "$0") --qemu-trace <trace.jsonl> [options]
  $(basename "$0") --dry-run

Options:
  --build-dir <dir>       Output root (default: ${BUILD_DIR})
  --qemu-bin <path>       QEMU binary. Defaults to the Chisel cross-check QEMU.
  --max-commits <int>     Commit rows replayed/compared (default: ${MAX_COMMITS})
  --replay-rows <int>     Raw rows driven before metadata filtering
  --max-seconds <int>     Timeout for QEMU trace collection (default: ${MAX_SECONDS})
  --pc-lo <hex>           Optional QEMU commit-trace PC filter low bound
  --pc-hi <hex>           Optional QEMU commit-trace PC filter high bound

The --elf mode collects a QEMU commit trace first, then replays the resulting
architectural rows through the current Chisel commit-surface harness. The
--qemu-trace mode skips QEMU execution and replays an existing QEMU JSONL trace.
In --elf mode, --replay-rows also bounds the raw QEMU prefix collected before
the wrapper stops QEMU.
USAGE
}

QEMU_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf) ELF="$2"; shift 2 ;;
    --qemu-trace) QEMU_TRACE="$2"; shift 2 ;;
    --build-dir) BUILD_DIR="$2"; shift 2 ;;
    --qemu-bin) QEMU_BIN="$2"; shift 2 ;;
    --max-commits) MAX_COMMITS="$2"; shift 2 ;;
    --replay-rows) REPLAY_ROWS="$2"; shift 2 ;;
    --max-seconds) MAX_SECONDS="$2"; shift 2 ;;
    --pc-lo) PC_LO="$2"; shift 2 ;;
    --pc-hi) PC_HI="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --)
      shift
      QEMU_ARGS=("$@")
      break
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi

TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
MANIFEST="${REPORT_DIR}/crosscheck_manifest.json"
QEMU_FIFO=""
qemu_pid=""
reader_pid=""
watchdog_pid=""
producer_watch_pid=""
QEMU_TRACE_RUNNER="${ROOT_DIR}/tools/qemu/run_qemu_commit_trace.sh"
TRACE_REPLAY_RUNNER="${ROOT_DIR}/tools/chisel/run_chisel_trace_replay_xcheck.sh"
QEMU_CROSSCHECK_RUNNER="${ROOT_DIR}/tools/chisel/run_chisel_qemu_crosscheck.sh"

if [[ -z "${QEMU_BIN}" ]]; then
  QEMU_BIN="$(bash "${QEMU_CROSSCHECK_RUNNER}" --print-qemu-bin)"
fi
if [[ -z "${REPLAY_ROWS}" ]]; then
  REPLAY_ROWS="$((MAX_COMMITS * 32 + 1024))"
fi

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
  if [[ -n "${watchdog_pid}" ]]; then kill "${watchdog_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${producer_watch_pid}" ]]; then kill "${producer_watch_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${qemu_pid}" ]]; then kill "${qemu_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${reader_pid}" ]]; then kill "${reader_pid}" >/dev/null 2>&1 || true; fi
  if [[ -n "${ELF}" ]]; then stop_qemu_for_elf; fi
  if [[ -n "${QEMU_FIFO}" ]]; then rm -f "${QEMU_FIFO}"; fi
}
trap cleanup EXIT INT TERM

if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "qemu-bin=${QEMU_BIN}"
  echo "max-commits=${MAX_COMMITS}"
  echo "replay-rows=${REPLAY_ROWS}"
  echo "qemu-trace-runner=${QEMU_TRACE_RUNNER}"
  echo "trace-replay-runner=${TRACE_REPLAY_RUNNER}"
  bash "${QEMU_CROSSCHECK_RUNNER}" --dry-run
  exit 0
fi

if [[ -n "${ELF}" && -n "${QEMU_TRACE}" ]]; then
  echo "error: choose either --elf or --qemu-trace, not both" >&2
  exit 2
fi
if [[ -z "${ELF}" && -z "${QEMU_TRACE}" ]]; then
  echo "error: --elf or --qemu-trace is required" >&2
  usage
  exit 2
fi
if [[ -n "${QEMU_TRACE}" && "${#QEMU_ARGS[@]}" -ne 0 ]]; then
  echo "error: trailing QEMU args are only valid with --elf" >&2
  exit 2
fi

mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"

if [[ -n "${ELF}" ]]; then
  if [[ ! "${REPLAY_ROWS}" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: --replay-rows must be a positive integer for --elf capture" >&2
    exit 2
  fi
  QEMU_TRACE="${TRACE_DIR}/qemu.raw.jsonl"
  QEMU_FIFO="${TRACE_DIR}/qemu.raw.fifo"
  rm -f "${QEMU_TRACE}" "${QEMU_FIFO}"
  mkfifo "${QEMU_FIFO}"

  qemu_cmd=(
    bash "${QEMU_TRACE_RUNNER}"
    --elf "${ELF}"
    --out "${QEMU_FIFO}"
    --qemu-bin "${QEMU_BIN}"
    --max-seconds "${MAX_SECONDS}"
  )
  if [[ -n "${PC_LO}" ]]; then
    qemu_cmd+=(--pc-lo "${PC_LO}")
  fi
  if [[ -n "${PC_HI}" ]]; then
    qemu_cmd+=(--pc-hi "${PC_HI}")
  fi
  if [[ "${#QEMU_ARGS[@]}" -ne 0 ]]; then
    qemu_cmd+=(-- "${QEMU_ARGS[@]}")
  fi

  python3 - "${QEMU_FIFO}" "${QEMU_TRACE}" "${REPLAY_ROWS}" <<'PY' &
import sys

fifo_path, out_path, rows_s = sys.argv[1:4]
max_rows = int(rows_s)

try:
    with open(fifo_path, "r", encoding="utf-8", errors="replace") as src:
        with open(out_path, "w", encoding="utf-8") as dst:
            for index, line in enumerate(src):
                if index >= max_rows:
                    break
                dst.write(line)
except KeyboardInterrupt:
    raise SystemExit(130)
except BrokenPipeError:
    raise SystemExit(0)
except OSError:
    raise SystemExit(1)
PY
  reader_pid=$!
  "${qemu_cmd[@]}" >/dev/null &
  qemu_pid=$!

  (
    while kill -0 "${qemu_pid}" >/dev/null 2>&1; do
      sleep 0.1
    done
    if [[ -n "${reader_pid}" ]] && kill -0 "${reader_pid}" >/dev/null 2>&1; then
      kill "${reader_pid}" >/dev/null 2>&1 || true
    fi
  ) &
  producer_watch_pid=$!

  if [[ "${MAX_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
    (
      sleep "${MAX_SECONDS}"
      if kill -0 "${qemu_pid}" >/dev/null 2>&1; then
        stop_qemu_for_elf
        kill "${qemu_pid}" >/dev/null 2>&1 || true
      fi
    ) &
    watchdog_pid=$!
  fi

  set +e
  wait "${reader_pid}"
  reader_rc=$?
  reader_pid=""
  if [[ -n "${producer_watch_pid}" ]]; then
    kill "${producer_watch_pid}" >/dev/null 2>&1 || true
    producer_watch_pid=""
  fi
  if [[ -n "${watchdog_pid}" ]]; then
    kill "${watchdog_pid}" >/dev/null 2>&1 || true
    watchdog_pid=""
  fi
  if kill -0 "${qemu_pid}" >/dev/null 2>&1; then
    stop_qemu_for_elf
    kill "${qemu_pid}" >/dev/null 2>&1 || true
  fi
  wait "${qemu_pid}"
  qemu_rc=$?
  qemu_pid=""
  set -e

  rm -f "${QEMU_FIFO}"
  QEMU_FIFO=""
  raw_rows="$(awk 'END { print NR + 0 }' "${QEMU_TRACE}" 2>/dev/null || echo 0)"
  if [[ "${reader_rc}" -ne 0 && "${raw_rows}" -gt 0 ]]; then
    echo "warn: QEMU prefix reader exited with status ${reader_rc}" >&2
  fi
  if [[ "${qemu_rc}" -ne 0 && "${raw_rows}" -gt 0 ]]; then
    if [[ "${raw_rows}" -ge "${REPLAY_ROWS}" ]]; then
      echo "info: QEMU producer exited with status ${qemu_rc} after bounded prefix capture" >&2
    else
      echo "warn: QEMU producer exited with status ${qemu_rc}; continuing with captured prefix" >&2
    fi
  fi
  echo "qemu-capture-raw-rows=${raw_rows}"
fi

if [[ ! -s "${QEMU_TRACE}" ]]; then
  echo "error: QEMU trace not found or empty: ${QEMU_TRACE}" >&2
  exit 2
fi

WIDE_TRACE="${TRACE_DIR}/qemu.replay_input.wide.normalized.jsonl"
REPLAY_INPUT_TRACE="${TRACE_DIR}/qemu.replay_input.jsonl"
python3 "${ROOT_DIR}/tools/chisel/trace_schema_adapter.py" \
  --input "${QEMU_TRACE}" \
  --output "${WIDE_TRACE}" \
  --max-rows "${REPLAY_ROWS}"
python3 - "${ROOT_DIR}" "${WIDE_TRACE}" "${REPLAY_INPUT_TRACE}" "${MAX_COMMITS}" <<'PY'
import importlib.util
import sys
from pathlib import Path

root = Path(sys.argv[1])
wide = Path(sys.argv[2])
out = Path(sys.argv[3])
max_commits = int(sys.argv[4])

spec = importlib.util.spec_from_file_location(
    "linxcore_xcheck", root / "tools" / "trace" / "crosscheck_qemu_linxcore.py"
)
if spec is None or spec.loader is None:
    raise SystemExit("error: failed to load cross-check metadata classifier")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

lines = [line for line in wide.read_text(encoding="utf-8").splitlines() if line]
rows = module._load_trace(wide, 0)
selected: list[str] = []
arch_rows = 0
for line, row in zip(lines, rows):
    selected.append(line)
    if not module._is_metadata_commit(row):
        arch_rows += 1
        if max_commits > 0 and arch_rows >= max_commits:
            break

if max_commits > 0 and arch_rows < max_commits:
    raise SystemExit(
        f"error: QEMU trace has only {arch_rows} architectural rows within the replay window; need {max_commits}"
    )

out.write_text("\n".join(selected) + ("\n" if selected else ""), encoding="utf-8")
print(f"qemu-replay-input={out}")
print(f"qemu-replay-raw-rows={len(selected)}")
print(f"qemu-replay-arch-rows={arch_rows}")
PY
QEMU_TRACE="${REPLAY_INPUT_TRACE}"

bash "${TRACE_REPLAY_RUNNER}" \
  --input-trace "${QEMU_TRACE}" \
  --max-commits "${MAX_COMMITS}" \
  --replay-rows "${REPLAY_ROWS}" \
  --build-dir "${BUILD_DIR}"

if [[ ! -s "${MANIFEST}" ]]; then
  echo "error: expected cross-check manifest was not produced: ${MANIFEST}" >&2
  exit 3
fi

python3 - "${MANIFEST}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    manifest = json.load(f)
summary = manifest.get("summary", {})
print(
    "qemu-trace-replay-status={status} compared={compared} mismatches={mismatches}".format(
        status=manifest.get("status"),
        compared=summary.get("compared_rows"),
        mismatches=summary.get("mismatch_count"),
    )
)
PY

echo "qemu-trace=${QEMU_TRACE}"
echo "qemu-trace-replay-xcheck-report=${REPORT_DIR}"
echo "qemu-trace-replay-xcheck-manifest=${MANIFEST}"
