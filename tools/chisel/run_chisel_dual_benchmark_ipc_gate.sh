#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SUPERPROJECT_DIR="$(cd -- "${ROOT_DIR}/../.." && pwd)"
NATURAL_RUNNER="${LINXCORE_NATURAL_RUNNER:-${ROOT_DIR}/tools/chisel/run_chisel_benchmark_autonomous_top_natural.sh}"
EVALUATOR="${LINXCORE_IPC_EVALUATOR:-${ROOT_DIR}/tools/chisel/evaluate_natural_benchmark_ipc.py}"
COREMARK_ELF="${SUPERPROJECT_DIR}/workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/coremark/coremark.elf"
DHRYSTONE_ELF="${SUPERPROJECT_DIR}/workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/dhrystone/dhrystone.elf"
EXPECTED_COREMARK_SHA256="${LINXCORE_EXPECTED_COREMARK_SHA256:-9c734694793da5d3b3765bc45c7acff787a3ca1854ad1780897e1d5b8deb3cff}"
EXPECTED_DHRYSTONE_SHA256="${LINXCORE_EXPECTED_DHRYSTONE_SHA256:-617bd0985595ccf208dd2130809c1befc1605de1ee9188dbf3cfaf46fd9e9911}"
EXPECTED_NATURAL_RUNNER_SHA256="${LINXCORE_EXPECTED_NATURAL_RUNNER_SHA256:-91d84b3b300209bf5f384f185eb1ace4b5bfb81b3a8107fc8a6678e3a86bc1e3}"
BUILD_ROOT="${ROOT_DIR}/generated/perf-ipc2-evaluator"
MAX_CYCLES="3000000"
TARGET_IPC="1.90"
RESET_SP="0x0000000007fefff0"
EVENT_SAMPLE_PERIOD="1000"
COMMIT_SAMPLE_PERIOD="1000"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --coremark-elf)
      COREMARK_ELF="${2:-}"
      shift 2
      ;;
    --dhrystone-elf)
      DHRYSTONE_ELF="${2:-}"
      shift 2
      ;;
    --build-root)
      BUILD_ROOT="${2:-}"
      shift 2
      ;;
    --max-cycles)
      MAX_CYCLES="${2:-}"
      shift 2
      ;;
    --target-ipc)
      TARGET_IPC="${2:-}"
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
    *)
      echo "error: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "${BUILD_ROOT}" != /* ]]; then
  BUILD_ROOT="${ROOT_DIR}/${BUILD_ROOT}"
fi
COREMARK_BUILD_DIR="${BUILD_ROOT}/coremark"
DHRYSTONE_BUILD_DIR="${BUILD_ROOT}/dhrystone"
COREMARK_MANIFEST="${COREMARK_BUILD_DIR}/report/natural_manifest.json"
DHRYSTONE_MANIFEST="${DHRYSTONE_BUILD_DIR}/report/natural_manifest.json"
SUMMARY="${BUILD_ROOT}/report/dual_benchmark_ipc.json"

mkdir -p "${COREMARK_BUILD_DIR}/report" "${DHRYSTONE_BUILD_DIR}/report"
rm -f "${COREMARK_MANIFEST}" "${DHRYSTONE_MANIFEST}"

set +e
bash "${NATURAL_RUNNER}" \
  --elf "${COREMARK_ELF}" \
  --build-dir "${COREMARK_BUILD_DIR}" \
  --max-cycles "${MAX_CYCLES}" \
  --reset-sp "${RESET_SP}" \
  --event-sample-period "${EVENT_SAMPLE_PERIOD}" \
  --commit-sample-period "${COMMIT_SAMPLE_PERIOD}"
coremark_status=$?

bash "${NATURAL_RUNNER}" \
  --elf "${DHRYSTONE_ELF}" \
  --build-dir "${DHRYSTONE_BUILD_DIR}" \
  --max-cycles "${MAX_CYCLES}" \
  --reset-sp "${RESET_SP}" \
  --event-sample-period "${EVENT_SAMPLE_PERIOD}" \
  --commit-sample-period "${COMMIT_SAMPLE_PERIOD}"
dhrystone_status=$?
set -e

set +e
python3 "${EVALUATOR}" \
  --coremark-manifest "${COREMARK_MANIFEST}" \
  --dhrystone-manifest "${DHRYSTONE_MANIFEST}" \
  --coremark-elf "${COREMARK_ELF}" \
  --dhrystone-elf "${DHRYSTONE_ELF}" \
  --expected-coremark-sha256 "${EXPECTED_COREMARK_SHA256}" \
  --expected-dhrystone-sha256 "${EXPECTED_DHRYSTONE_SHA256}" \
  --runner "${NATURAL_RUNNER}" \
  --expected-runner-sha256 "${EXPECTED_NATURAL_RUNNER_SHA256}" \
  --target-ipc "${TARGET_IPC}" \
  --expected-reset-sp "${RESET_SP}" \
  --output "${SUMMARY}"
evaluator_status=$?
set -e

echo "coremark-run-status=${coremark_status}"
echo "dhrystone-run-status=${dhrystone_status}"
echo "dual-benchmark-ipc-summary=${SUMMARY}"
if [[ "${evaluator_status}" -ne 0 ]]; then
  exit "${evaluator_status}"
fi
if [[ "${coremark_status}" -ne 0 || "${dhrystone_status}" -ne 0 ]]; then
  exit 1
fi
exit 0
