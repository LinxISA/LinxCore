#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
CHISEL_DIR="${ROOT_DIR}/chisel"
TOP_MODULE="TOP"
EMIT_MAIN="linxcore.top.EmitTOP"
ELF=""
BUILD_DIR="${ROOT_DIR}/generated/top-natural"
MAX_CYCLES="20000"
HEARTBEAT_CYCLES="2000"
DEADLOCK_CYCLES="3000"
MANIFEST=""
SELF_TEST=0
WIDTH="2"
RESET_SP="0x0000000007fefff0"
PORT_VALIDATION=0
LINT=0
COMMIT_TRACE=""
BUILD_JOBS="1"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --self-test) SELF_TEST=1; shift ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --elf) ELF="${2:-}"; shift 2 ;;
    --build-dir) BUILD_DIR="${2:-}"; shift 2 ;;
    --max-cycles) MAX_CYCLES="${2:-}"; shift 2 ;;
    --heartbeat-cycles) HEARTBEAT_CYCLES="${2:-}"; shift 2 ;;
    --deadlock-cycles) DEADLOCK_CYCLES="${2:-}"; shift 2 ;;
    --width) WIDTH="${2:-}"; shift 2 ;;
    --reset-sp) RESET_SP="${2:-}"; shift 2 ;;
    --port-validation) PORT_VALIDATION=1; shift ;;
    --lint) LINT=1; shift ;;
    --commit-trace) COMMIT_TRACE="${2:-}"; shift 2 ;;
    --build-jobs) BUILD_JOBS="${2:-}"; shift 2 ;;
    --qemu|--qemu-trace|--expected-rows|--replay-rows|--result-hint)
      echo "error: oracle/replay option is forbidden in natural mode: $1" >&2
      exit 2
      ;;
    *) echo "error: unknown argument: $1" >&2; exit 2 ;;
  esac
done

if ! [[ "${BUILD_JOBS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "error: --build-jobs must be a positive integer" >&2
  exit 2
fi

write_contract_manifest() {
  local output="$1"
  mkdir -p "$(dirname -- "${output}")"
  python3 - "${output}" "${BUILD_JOBS}" <<'PY'
import json
import sys
from pathlib import Path

payload = {
    "schema": 1,
    "top": "TOP",
    "task": 18,
    "build_jobs": int(sys.argv[2]),
    "harness_owns": ["elf", "memory", "uart", "finisher", "manifest"],
    "instruction_oracle": False,
    "commit_oracle": False,
    "memory_size_encoding": {
        "Bytes1": 0, "Bytes2": 1, "Bytes4": 2, "Bytes8": 3,
        "Bytes16": 4, "Bytes32": 5, "Bytes64": 6,
    },
}
path = Path(sys.argv[1])
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
temporary.replace(path)
PY
}

record_build_jobs() {
  local output="$1"
  python3 - "${output}" "${BUILD_JOBS}" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["build_jobs"] = int(sys.argv[2])
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
temporary.replace(path)
PY
}

prepare_harness_manifest() {
  local output="$1"
  rm -f -- "${output}" "${output}.tmp"
}

finalize_harness_status() {
  local tb_status="$1"
  local output="$2"
  local record_status=0

  if [[ -f "${output}" ]]; then
    record_build_jobs "${output}" || record_status=$?
  fi
  if [[ "${tb_status}" -ne 0 ]]; then
    return "${tb_status}"
  fi
  return "${record_status}"
}

if [[ "${LINX_TOP_NATURAL_SOURCE_FUNCTIONS_ONLY:-0}" -eq 1 ]]; then
  return 0 2>/dev/null || exit 0
fi

if [[ "${SELF_TEST}" -eq 1 ]]; then
  if [[ -z "${MANIFEST}" ]]; then
    echo "error: --manifest is required with --self-test" >&2
    exit 2
  fi
  write_contract_manifest "${MANIFEST}"
  python3 - "${MANIFEST}" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert payload["top"] == "TOP"
assert payload["memory_size_encoding"]["Bytes64"] == 6
assert payload["build_jobs"] > 0
assert not payload["instruction_oracle"]
assert not payload["commit_oracle"]
PY
  exit 0
fi

if [[ "${PORT_VALIDATION}" -eq 0 && ( -z "${ELF}" || ! -f "${ELF}" ) ]]; then
  echo "error: --elf must name an existing ELF" >&2
  exit 2
fi
if [[ "${BUILD_DIR}" != /* ]]; then BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"; fi
if [[ -z "${MANIFEST}" ]]; then MANIFEST="${BUILD_DIR}/report/natural_manifest.json"; fi

MEMORY_HEX="${BUILD_DIR}/elf.load.mem"
SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
PROFILE_METADATA="${BUILD_DIR}/top-profile.env"
TOP_VPI_CONFIG="${ROOT_DIR}/tools/chisel/top_natural.vlt"
SYSTEM_ISSUE_LANES=""
RETIRE_WIDTH=""
mkdir -p "${SV_DIR}" "${BUILD_DIR}/report"
prepare_harness_manifest "${MANIFEST}"
if [[ "${PORT_VALIDATION}" -eq 1 ]]; then
  printf '# linxcore.top.port-validation\n' > "${MEMORY_HEX}"
  ELF_ENTRY="0x0"
else
  python3 "${ROOT_DIR}/tools/chisel/frontend_fetch_elf_memory.py" \
    --elf "${ELF}" --output "${MEMORY_HEX}"
  ELF_ENTRY="$(python3 - "${ELF}" <<'PY'
import struct
import sys
from pathlib import Path
print(hex(struct.unpack_from("<16sHHIQQQIHHHHHH", Path(sys.argv[1]).read_bytes(), 0)[4]))
PY
)"
fi

source "${ROOT_DIR}/tools/chisel/chisel_env.sh"
(
  cd "${CHISEL_DIR}"
  LINX_TOP_WIDTH="${WIDTH}" LINX_TOP_RESET_PC="${ELF_ENTRY}" \
    LINX_TOP_PROFILE_METADATA="${PROFILE_METADATA}" \
    LINX_TOP_SIMULATION_PROFILE=1 \
    sbt --batch --no-colors "runMain ${EMIT_MAIN} --target-dir ${SV_DIR}"
)
if [[ ! -f "${SV_DIR}/${TOP_MODULE}.sv" ]]; then
  echo "error: missing emitted ${SV_DIR}/${TOP_MODULE}.sv" >&2
  exit 2
fi
VERILATOR_SV="${SV_DIR}/CoreTOP.verilator.sv"
sed 's/^module TOP(/module CoreTOP(/' "${SV_DIR}/${TOP_MODULE}.sv" > "${VERILATOR_SV}"
mkdir -p "${OBJ_DIR}"
find "${OBJ_DIR}" -mindepth 1 -delete
SV_FILES=()
while IFS= read -r source; do
  [[ "${source}" == "${SV_DIR}/${TOP_MODULE}.sv" ]] && continue
  SV_FILES+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' | sort)
verilator --cc "${ROOT_DIR}/tools/chisel/top_verilator_harness.sv" \
  "${TOP_VPI_CONFIG}" "${SV_FILES[@]}" \
  --top-module CoreTOPHarness -Wno-PINMISSING \
  --prefix VCoreTOP --vpi \
  --exe "${ROOT_DIR}/tools/chisel/top_natural_tb.cpp" --build \
  --build-jobs "${BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" -o top_natural_tb -CFLAGS "-std=c++17 -O2"
find "${OBJ_DIR}" -type f -name '*.gch' -delete

TB_ARGS=(--memory-hex "${MEMORY_HEX}" --max-cycles "${MAX_CYCLES}"
  --heartbeat-cycles "${HEARTBEAT_CYCLES}"
  --deadlock-cycles "${DEADLOCK_CYCLES}"
  --reset-sp "${RESET_SP}" --uart-output "${BUILD_DIR}/report/uart.txt"
  --manifest "${MANIFEST}")
while IFS='=' read -r key value; do
  case "${key}" in
    stidCount) TB_ARGS+=(--stid-count "${value}") ;;
    gprArchRegs) TB_ARGS+=(--gpr-arch-regs "${value}") ;;
    spAtag) TB_ARGS+=(--sp-atag "${value}") ;;
    traceWidth) TB_ARGS+=(--trace-width "${value}") ;;
    retireWidth) RETIRE_WIDTH="${value}" ;;
    systemIssueLanes) SYSTEM_ISSUE_LANES="${value}" ;;
    loadLanes) LOAD_LANES="${value}" ;;
    storeLanes) STORE_LANES="${value}" ;;
  esac
done < "${PROFILE_METADATA}"
if [[ -z "${SYSTEM_ISSUE_LANES}" ]]; then
  echo "error: missing systemIssueLanes in TOP profile metadata" >&2
  exit 2
fi
if [[ -z "${RETIRE_WIDTH}" ]]; then
  echo "error: missing retireWidth in TOP profile metadata" >&2
  exit 2
fi
TB_ARGS+=(--data-lanes "$((LOAD_LANES + STORE_LANES))")
TB_ARGS+=(--system-issue-lanes "${SYSTEM_ISSUE_LANES}")
TB_ARGS+=(--retire-width "${RETIRE_WIDTH}")
if [[ -n "${COMMIT_TRACE}" ]]; then
  TB_ARGS+=(--commit-trace "${COMMIT_TRACE}")
fi
if [[ "${PORT_VALIDATION}" -eq 1 ]]; then TB_ARGS+=(--validate-ports); fi
set +e
"${OBJ_DIR}/top_natural_tb" "${TB_ARGS[@]}"
TB_STATUS=$?
set -e
FINAL_STATUS=0
finalize_harness_status "${TB_STATUS}" "${MANIFEST}" || FINAL_STATUS=$?
if [[ "${FINAL_STATUS}" -ne 0 ]]; then exit "${FINAL_STATUS}"; fi
if [[ "${LINT}" -eq 1 ]]; then
  verilator --lint-only --top-module CoreTOPHarness -Wno-PINMISSING \
    "${ROOT_DIR}/tools/chisel/top_verilator_harness.sv" \
    "${TOP_VPI_CONFIG}" "${SV_FILES[@]}"
fi
