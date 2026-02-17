#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${PYC_PERF_OUT_DIR:-${ROOT_DIR}/tests/perf/walltime}"
LOG_DIR="${OUT_DIR}/logs"
mkdir -p "${LOG_DIR}"

BUILD_BENCH_SCRIPT="${ROOT_DIR}/tools/image/build_linxisa_benchmarks_memh_compat.sh"
RUN_SIM_SCRIPT="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"

RUNS="${PYC_PERF_RUNS:-5}"
WARMUP="${PYC_PERF_WARMUP:-1}"
MAX_COMMITS="${PYC_PERF_MAX_COMMITS:-100000}"
CORE_ITERATIONS="${CORE_ITERATIONS:-10}"
DHRY_RUNS="${DHRY_RUNS:-1000}"
BOOT_PC="${PYC_BOOT_PC:-0x0000000000010000}"
BOOT_SP="${PYC_BOOT_SP:-0x00000000000ff000}"

CSV_PATH="${OUT_DIR}/walltime_samples.csv"
REPORT_MD="${OUT_DIR}/walltime_report.md"

build_out="$({
  CORE_ITERATIONS="${CORE_ITERATIONS}" \
  DHRY_RUNS="${DHRY_RUNS}" \
  bash "${BUILD_BENCH_SCRIPT}"
})"
CORE_MEMH="$(printf "%s\n" "${build_out}" | sed -n '1p')"
DHRY_MEMH="$(printf "%s\n" "${build_out}" | sed -n '2p')"

if [[ ! -f "${CORE_MEMH}" || ! -f "${DHRY_MEMH}" ]]; then
  echo "error: benchmark memh generation failed" >&2
  exit 2
fi

echo "workload,phase,run_idx,real_s,user_s,sys_s,cycles,commits" > "${CSV_PATH}"

run_case() {
  local workload="$1"
  local memh="$2"
  local accept_exit_codes="$3"
  local total=$((WARMUP + RUNS))
  local idx
  for ((idx = 0; idx < total; ++idx)); do
    local phase="measure"
    if [[ "${idx}" -lt "${WARMUP}" ]]; then
      phase="warmup"
    fi
    local log_path="${LOG_DIR}/${workload}_${idx}.log"
    local time_path="${LOG_DIR}/${workload}_${idx}.time"
    local run_cmd=(
      env
      PYC_MAX_COMMITS="${MAX_COMMITS}"
      PYC_BOOT_PC="${BOOT_PC}"
      PYC_BOOT_SP="${BOOT_SP}"
      PYC_ACCEPT_EXIT_CODES="${accept_exit_codes}"
      PYC_SIM_STATS=0
      bash "${RUN_SIM_SCRIPT}" "${memh}"
    )
    /usr/bin/time -p -o "${time_path}" "${run_cmd[@]}" > "${log_path}" 2>&1
    local real_s user_s sys_s cycles commits
    real_s="$(awk '/^real / {print $2; exit}' "${time_path}")"
    user_s="$(awk '/^user / {print $2; exit}' "${time_path}")"
    sys_s="$(awk '/^sys / {print $2; exit}' "${time_path}")"
    cycles="$(awk 'match($0, /cycles=[0-9]+/) {s=substr($0,RSTART,RLENGTH); gsub("cycles=","",s); c=s} END {if (c=="") c="n/a"; print c}' "${log_path}")"
    commits="$(awk 'match($0, /commits=[0-9]+/) {s=substr($0,RSTART,RLENGTH); gsub("commits=","",s); c=s} END {if (c=="") c="n/a"; print c}' "${log_path}")"
    echo "${workload},${phase},${idx},${real_s},${user_s},${sys_s},${cycles},${commits}" >> "${CSV_PATH}"
  done
}

run_case "coremark" "${CORE_MEMH}" "${CORE_ACCEPT_EXIT_CODES:-0}"
run_case "dhrystone" "${DHRY_MEMH}" "${DHRY_ACCEPT_EXIT_CODES:-1}"

python3 - <<'PY' "${CSV_PATH}" "${REPORT_MD}" "${MAX_COMMITS}" "${RUNS}" "${WARMUP}" "${CORE_MEMH}" "${DHRY_MEMH}"
import csv
import statistics
import sys
from pathlib import Path

csv_path = Path(sys.argv[1])
report_md = Path(sys.argv[2])
max_commits = sys.argv[3]
runs = int(sys.argv[4])
warmup = int(sys.argv[5])
core_memh = sys.argv[6]
dhry_memh = sys.argv[7]

rows = list(csv.DictReader(csv_path.open()))
by_workload = {}
for r in rows:
    if r["phase"] != "measure":
        continue
    by_workload.setdefault(r["workload"], []).append(r)

def median_metric(workload: str, key: str) -> float:
    vals = [float(r[key]) for r in by_workload.get(workload, [])]
    if not vals:
        return float("nan")
    return statistics.median(vals)

def sample_metric(workload: str, key: str):
    vals = [r[key] for r in by_workload.get(workload, [])]
    return vals[0] if vals else "n/a"

core_real = median_metric("coremark", "real_s")
core_user = median_metric("coremark", "user_s")
core_sys = median_metric("coremark", "sys_s")
dhry_real = median_metric("dhrystone", "real_s")
dhry_user = median_metric("dhrystone", "user_s")
dhry_sys = median_metric("dhrystone", "sys_s")

core_cycles = sample_metric("coremark", "cycles")
core_commits = sample_metric("coremark", "commits")
dhry_cycles = sample_metric("dhrystone", "cycles")
dhry_commits = sample_metric("dhrystone", "commits")

report_md.parent.mkdir(parents=True, exist_ok=True)
with report_md.open("w") as f:
    f.write("# LinxCore Simulator Wall-Time Report\n\n")
    f.write("## Configuration\n\n")
    f.write(f"- max commits: `{max_commits}`\n")
    f.write(f"- warmup runs: `{warmup}`\n")
    f.write(f"- measured runs: `{runs}`\n")
    f.write(f"- coremark memh: `{core_memh}`\n")
    f.write(f"- dhrystone memh: `{dhry_memh}`\n")
    f.write(f"- raw samples csv: `{csv_path}`\n\n")
    f.write("## Median Wall-Time (measure phase)\n\n")
    f.write("| Workload | real (s) | user (s) | sys (s) | cycles (sample) | commits (sample) |\n")
    f.write("| --- | ---: | ---: | ---: | ---: | ---: |\n")
    f.write(f"| CoreMark | {core_real:.3f} | {core_user:.3f} | {core_sys:.3f} | {core_cycles} | {core_commits} |\n")
    f.write(f"| Dhrystone | {dhry_real:.3f} | {dhry_user:.3f} | {dhry_sys:.3f} | {dhry_cycles} | {dhry_commits} |\n")

print(report_md)
PY

echo "report: ${REPORT_MD}"
cat "${REPORT_MD}"
