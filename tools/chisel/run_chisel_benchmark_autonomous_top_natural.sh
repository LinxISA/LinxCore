#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
SV_DIR="${CHISEL_DIR}/generated/chisel"
TOP_MODULE="LinxCoreBenchmarkAutonomousTop"
TOP_SV="${SV_DIR}/${TOP_MODULE}.sv"
EMIT_MAIN="linxcore.top.EmitLinxCoreBenchmarkAutonomousTop"
BUILD_DIR="${ROOT_DIR}/generated/chisel-benchmark-autonomous-top-natural"
ELF=""
MAX_CYCLES="1000000"
RESET_PC=""
RESET_SP=""
DIRECT_BOOT_SP="0x0000000007fefff0"
VERILATOR_BUILD_JOBS="${VERILATOR_BUILD_JOBS:-0}"
SBT_JVM_HEAP_MB="${SBT_JVM_HEAP_MB:-8192}"
EVENT_SAMPLE_PERIOD="1"
COMMIT_SAMPLE_PERIOD="1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --elf)
      ELF="${2:-}"
      shift 2
      ;;
    --build-dir)
      BUILD_DIR="${2:-}"
      shift 2
      ;;
    --max-cycles)
      MAX_CYCLES="${2:-}"
      shift 2
      ;;
    --reset-pc)
      RESET_PC="${2:-}"
      shift 2
      ;;
    --reset-sp)
      RESET_SP="${2:-}"
      shift 2
      ;;
    --event-sample-period)
      EVENT_SAMPLE_PERIOD="${2:-}"
      shift 2
      ;;
    --commit-sample-period)
      COMMIT_SAMPLE_PERIOD="${2:-}"
      shift 2
      ;;
    --qemu|--qemu-trace|--expected-rows|--replay-rows|--result-hint)
      echo "error: oracle/replay option is forbidden in natural mode: $1" >&2
      exit 2
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "${ELF}" ]]; then
  echo "error: --elf is required" >&2
  exit 2
fi
if [[ "${ELF}" != /* ]]; then
  ELF="${ROOT_DIR}/${ELF}"
fi
if [[ ! -f "${ELF}" ]]; then
  echo "error: ELF does not exist: ${ELF}" >&2
  exit 2
fi
if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi

if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for benchmark autonomous natural run" >&2
  exit 2
fi

OBJ_DIR="${BUILD_DIR}/obj_dir"
TRACE_DIR="${BUILD_DIR}/traces"
REPORT_DIR="${BUILD_DIR}/report"
MEMORY_HEX="${BUILD_DIR}/elf.load.mem"
COMMIT_TRACE="${TRACE_DIR}/dut.commit.jsonl"
EVENT_TRACE="${TRACE_DIR}/dut.events.jsonl"
UART_OUTPUT="${TRACE_DIR}/uart.txt"
MANIFEST="${REPORT_DIR}/natural_manifest.json"
REVISION_MANIFEST="${REPORT_DIR}/revision_manifest.json"

mkdir -p "${TRACE_DIR}" "${REPORT_DIR}"

python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_elf_memory.py" \
  --elf "${ELF}" \
  --output "${MEMORY_HEX}"

ELF_ENTRY="$(python3 - "${ELF}" <<'PY'
import struct
import sys
from pathlib import Path

data = Path(sys.argv[1]).read_bytes()
hdr = struct.unpack_from("<16sHHIQQQIHHHHHH", data, 0)
entry = hdr[4]
print(hex(entry))
PY
)"

RESET_PC="${RESET_PC:-${ELF_ENTRY}}"
RESET_SP="${RESET_SP:-${DIRECT_BOOT_SP}}"

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

cd "${CHISEL_DIR}"
sbt --batch --no-colors -J-Xmx"${SBT_JVM_HEAP_MB}"M -J-Xss8M "runMain ${EMIT_MAIN}"

if [[ ! -f "${TOP_SV}" ]]; then
  echo "error: missing emitted top: ${TOP_SV}" >&2
  exit 2
fi

SV_FILES=()
while IFS= read -r sv_path; do
  SV_FILES+=("${sv_path}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)
if [[ "${#SV_FILES[@]}" -eq 0 ]]; then
  echo "error: no benchmark autonomous SystemVerilog files were emitted under ${SV_DIR}" >&2
  exit 2
fi

rm -rf "${OBJ_DIR}"
verilator \
  --cc "${SV_FILES[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${ROOT_DIR}/tools/chisel/benchmark_autonomous_top_natural_tb.cpp" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o linxcore_benchmark_autonomous_top_natural_tb \
  -CFLAGS "-std=c++17 -O2 -I${ROOT_DIR}/tools/chisel"

set +e
"${OBJ_DIR}/linxcore_benchmark_autonomous_top_natural_tb" \
  --memory-hex "${MEMORY_HEX}" \
  --reset-pc "${RESET_PC}" \
  --reset-sp "${RESET_SP}" \
  --max-cycles "${MAX_CYCLES}" \
  --event-sample-period "${EVENT_SAMPLE_PERIOD}" \
  --commit-sample-period "${COMMIT_SAMPLE_PERIOD}" \
  --commit-trace "${COMMIT_TRACE}" \
  --event-trace "${EVENT_TRACE}" \
  --uart-output "${UART_OUTPUT}" \
  --manifest "${MANIFEST}"
run_status=$?
set -e

python3 - "${ROOT_DIR}" "${ELF}" "${MEMORY_HEX}" "${COMMIT_TRACE}" "${EVENT_TRACE}" "${UART_OUTPUT}" "${MANIFEST}" "${REVISION_MANIFEST}" <<'PY'
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root, elf, memory, commit, event, uart, manifest, out_path = map(Path, sys.argv[1:])

def sha256(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def git_info(path):
    rev = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.run(["git", "-C", str(path), "diff", "--quiet"]).returncode != 0
    untracked = subprocess.check_output(["git", "-C", str(path), "ls-files", "--others", "--exclude-standard"], text=True).splitlines()
    return {"revision": rev, "dirty": bool(dirty or untracked)}

payload = {
    "schema": "linxcore.benchmark_autonomous_natural.revision_manifest.v1",
    "git": {
        "superproject": git_info(root.parent.parent),
        "linxcore": git_info(root),
    },
    "artifacts": {
        "elf": {"path": str(elf), "sha256": sha256(elf)},
        "memory_hex": {"path": str(memory), "sha256": sha256(memory)},
        "commit_trace": {"path": str(commit), "sha256": sha256(commit)},
        "event_trace": {"path": str(event), "sha256": sha256(event)},
        "uart_output": {"path": str(uart), "sha256": sha256(uart)},
        "natural_manifest": {"path": str(manifest), "sha256": sha256(manifest)},
    },
}
tmp = out_path.with_suffix(out_path.suffix + ".tmp")
tmp.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
tmp.replace(out_path)
PY

echo "benchmark-autonomous-natural-manifest=${MANIFEST}"
echo "benchmark-autonomous-natural-revision-manifest=${REVISION_MANIFEST}"
exit "${run_status}"
