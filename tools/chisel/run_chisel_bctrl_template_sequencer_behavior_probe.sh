#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${ROOT_DIR}/generated/r923-johnson-bctrl-template-sequencer-behavior"
VERILATOR_BUILD_JOBS="${VERILATOR_BUILD_JOBS:-1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir)
      BUILD_DIR="${2:-}"
      shift 2
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
if ! command -v verilator >/dev/null 2>&1; then
  echo "error: Verilator is required for the bctrl template sequencer behavior probe" >&2
  exit 2
fi

SV_DIR="${BUILD_DIR}/sv"
OBJ_DIR="${BUILD_DIR}/obj_dir"
TOP_MODULE="BlockControlTemplateSequencerBehaviorProbe"
EMIT_MAIN="linxcore.bctrl.EmitBlockControlTemplateSequencerBehaviorProbe"
TESTBENCH="${ROOT_DIR}/tools/chisel/bctrl_template_sequencer_behavior_probe_tb.cpp"

rm -rf "${SV_DIR}" "${OBJ_DIR}"
mkdir -p "${SV_DIR}" "${OBJ_DIR}"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

(
  cd "${ROOT_DIR}/chisel"
  sbt --server --batch --no-colors --mem 4096 \
    "Test / runMain ${EMIT_MAIN} --target-dir ${SV_DIR}"
)

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${SV_DIR}" -maxdepth 1 -type f -name '*.sv' -print | sort)
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "error: no emitted SystemVerilog under ${SV_DIR}" >&2
  exit 2
fi

verilator \
  --cc "${sv_sources[@]}" \
  --top-module "${TOP_MODULE}" \
  --exe "${TESTBENCH}" \
  --build \
  --build-jobs "${VERILATOR_BUILD_JOBS}" \
  -Mdir "${OBJ_DIR}" \
  -o bctrl_template_sequencer_behavior_probe_tb \
  -CFLAGS '-std=c++17 -O2'

"${OBJ_DIR}/bctrl_template_sequencer_behavior_probe_tb"
