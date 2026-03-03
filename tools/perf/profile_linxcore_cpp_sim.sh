#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"

OUT_DIR="${PYC_PERF_OUT_DIR:-${ROOT_DIR}/generated/perf}"
PROFILE="${LINXCORE_BUILD_PROFILE:-release}"
WORKLOAD="${PYC_PERF_WORKLOAD:-dhrystone}" # coremark|dhrystone
SIM_FAST="${PYC_PERF_SIM_FAST:-1}"         # 0|1
ENABLE_COMMIT_TRACE="${PYC_PERF_COMMIT_TRACE:-0}" # 0|1
ENABLE_LINXTRACE="${PYC_PERF_LINXTRACE:-0}"        # 0|1
MAX_CYCLES="${PYC_MAX_CYCLES:-}"
MAX_COMMITS="${PYC_MAX_COMMITS:-}"
BOOT_SP="${PYC_BOOT_SP:-0x00000000000ff000}"
BOOT_PC_DEFAULT="0x0000000000010000"

mkdir -p "${OUT_DIR}"

BUILD_BENCH_SCRIPT="${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh"
LLVM_READELF="${LLVM_READELF:-${LINX_ROOT}/compiler/llvm/build-linxisa-clang/bin/llvm-readelf}"
LINXISA_DIR="${LINXISA_DIR:-${LINX_ROOT}}"

elf_entry() {
  local elf="$1"
  if [[ ! -x "${LLVM_READELF}" || ! -f "${elf}" ]]; then
    return 1
  fi
  "${LLVM_READELF}" -h "${elf}" | awk '/Entry point address:/ {print $4; exit}'
}

resolve_boot_pc() {
  local name="$1"
  if [[ -n "${PYC_BOOT_PC:-}" ]]; then
    echo "${PYC_BOOT_PC}"
    return 0
  fi
  if [[ "${name}" == "coremark" ]]; then
    local elf="${LINXISA_DIR}/workloads/generated/elf/coremark.elf"
    local e
    e="$(elf_entry "${elf}" || true)"
    if [[ -n "${e}" ]]; then
      echo "${e}"
      return 0
    fi
  fi
  if [[ "${name}" == "dhrystone" ]]; then
    local elf="${LINXISA_DIR}/workloads/generated/elf/dhrystone.elf"
    local e
    e="$(elf_entry "${elf}" || true)"
    if [[ -n "${e}" ]]; then
      echo "${e}"
      return 0
    fi
  fi
  echo "${BOOT_PC_DEFAULT}"
}

build_out="$({
  bash "${BUILD_BENCH_SCRIPT}"
})"
CORE_MEMH="$(printf "%s\n" "${build_out}" | sed -n '1p')"
DHRY_MEMH="$(printf "%s\n" "${build_out}" | sed -n '2p')"
if [[ "${WORKLOAD}" == "coremark" ]]; then
  MEMH="${CORE_MEMH}"
else
  MEMH="${DHRY_MEMH}"
fi
if [[ ! -f "${MEMH}" ]]; then
  echo "error: memh not found: ${MEMH}" >&2
  exit 2
fi

BOOT_PC="$(resolve_boot_pc "${WORKLOAD}")"
ACCEPT_EXIT_CODES="${PYC_ACCEPT_EXIT_CODES:-}"
if [[ -z "${ACCEPT_EXIT_CODES}" ]]; then
  if [[ "${WORKLOAD}" == "dhrystone" ]]; then
    ACCEPT_EXIT_CODES="1"
  else
    ACCEPT_EXIT_CODES="0"
  fi
fi

STAMP="$(
python3 - <<'PY' "${PROFILE}" "${WORKLOAD}" "${SIM_FAST}" "${ENABLE_COMMIT_TRACE}" "${ENABLE_LINXTRACE}"
import sys, time
profile, w, fast, tr, ltr = sys.argv[1:]
ts = time.strftime("%Y%m%d_%H%M%S")
print(f"{profile}_{w}_fast{fast}_trace{tr}_linx{ltr}_{ts}")
PY
)"

RUN_LOG="${OUT_DIR}/cpp_sim_${STAMP}.log"
TIME_LOG="${OUT_DIR}/cpp_sim_${STAMP}.time.log"
STATS_PATH="${OUT_DIR}/cpp_sim_${STAMP}.sim_stats.txt"
OUT_JSON="${OUT_DIR}/cpp_sim_${STAMP}.json"

commit_trace_path=""
linxtrace_path=""
if [[ "${ENABLE_COMMIT_TRACE}" == "1" ]]; then
  commit_trace_path="${OUT_DIR}/cpp_sim_${STAMP}.commit.jsonl"
  rm -f "${commit_trace_path}" >/dev/null 2>&1 || true
fi
if [[ "${ENABLE_LINXTRACE}" == "1" ]]; then
  linxtrace_path="${OUT_DIR}/cpp_sim_${STAMP}.linxtrace.jsonl"
  rm -f "${linxtrace_path}" >/dev/null 2>&1 || true
fi

run_env=(
  PYC_SIM_FAST="${SIM_FAST}"
  PYC_SIM_STATS=1
  PYC_SIM_STATS_PATH="${STATS_PATH}"
  # Profiling should measure sim runtime, not post-processing.
  PYC_SKIP_TRACE_TEXT=1
  PYC_BOOT_PC="${BOOT_PC}"
  PYC_BOOT_SP="${BOOT_SP}"
  PYC_ACCEPT_EXIT_CODES="${ACCEPT_EXIT_CODES}"
)
if [[ -n "${MAX_CYCLES}" ]]; then
  run_env+=(PYC_MAX_CYCLES="${MAX_CYCLES}")
fi
if [[ -n "${MAX_COMMITS}" ]]; then
  run_env+=(PYC_MAX_COMMITS="${MAX_COMMITS}")
fi
if [[ -n "${commit_trace_path}" ]]; then
  run_env+=(PYC_COMMIT_TRACE="${commit_trace_path}")
fi
if [[ -n "${linxtrace_path}" ]]; then
  run_env+=(PYC_LINXTRACE=1 PYC_LINXTRACE_PATH="${linxtrace_path}")
fi

rc=0
/usr/bin/time -l env "${run_env[@]}" bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}" >"${RUN_LOG}" 2>"${TIME_LOG}" || rc=$?

python3 - <<'PY' "${OUT_JSON}" "${RUN_LOG}" "${TIME_LOG}" "${STATS_PATH}" "${rc}" "${PROFILE}" "${WORKLOAD}" "${MEMH}" "${BOOT_PC}" "${BOOT_SP}" "${SIM_FAST}" "${ENABLE_COMMIT_TRACE}" "${commit_trace_path}" "${ENABLE_LINXTRACE}" "${linxtrace_path}"
from __future__ import annotations

import json
import pathlib
import re
import sys

out_json = pathlib.Path(sys.argv[1])
run_log = pathlib.Path(sys.argv[2])
time_log = pathlib.Path(sys.argv[3])
stats_path = pathlib.Path(sys.argv[4])
rc = int(sys.argv[5])
profile, workload = sys.argv[6], sys.argv[7]
memh = sys.argv[8]
boot_pc, boot_sp = sys.argv[9], sys.argv[10]
sim_fast = int(sys.argv[11])
enable_commit_trace = int(sys.argv[12])
commit_trace_path = sys.argv[13]
enable_linxtrace = int(sys.argv[14])
linxtrace_path = sys.argv[15]

time_text = time_log.read_text(encoding="utf-8", errors="replace") if time_log.exists() else ""
run_text = run_log.read_text(encoding="utf-8", errors="replace") if run_log.exists() else ""

def _find_float(pat: str) -> float | None:
    m = re.search(pat, time_text)
    if not m:
        return None
    try:
        return float(m.group(1))
    except ValueError:
        return None

def _find_int(pat: str) -> int | None:
    m = re.search(pat, time_text)
    if not m:
        return None
    try:
        return int(m.group(1))
    except ValueError:
        return None

real_s = _find_float(r"([0-9]+(?:\.[0-9]+)?)\s+real\b")
user_s = _find_float(r"([0-9]+(?:\.[0-9]+)?)\s+user\b")
sys_s = _find_float(r"([0-9]+(?:\.[0-9]+)?)\s+sys\b")
max_rss = _find_int(r"([0-9]+)\s+maximum resident set size\b")
if max_rss is None:
    max_rss = _find_int(r"([0-9]+)\s+peak memory footprint\b")

cycles = None
m = re.search(r"cycles=([0-9]+)", run_text)
if m:
    cycles = int(m.group(1))

cycles_per_sec = None
if cycles is not None and real_s and real_s > 0:
    cycles_per_sec = float(cycles) / float(real_s)

stats_text = stats_path.read_text(encoding="utf-8", errors="replace") if stats_path.exists() else ""

summary = {
    "version": 1,
    "profile": profile,
    "workload": workload,
    "memh": memh,
    "boot_pc": boot_pc,
    "boot_sp": boot_sp,
    "sim_fast": bool(sim_fast),
    "commit_trace_enabled": bool(enable_commit_trace),
    "commit_trace_path": commit_trace_path if enable_commit_trace else "",
    "linxtrace_enabled": bool(enable_linxtrace),
    "linxtrace_path": linxtrace_path if enable_linxtrace else "",
    "rc": rc,
    "real_s": real_s,
    "user_s": user_s,
    "sys_s": sys_s,
    "max_rss_bytes": max_rss,
    "cycles": cycles,
    "cycles_per_sec": cycles_per_sec,
    "run_log": str(run_log),
    "time_log": str(time_log),
    "sim_stats_path": str(stats_path),
    "sim_stats_text": stats_text,
}

out_json.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(out_json)
PY

exit "${rc}"
