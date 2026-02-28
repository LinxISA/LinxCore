#!/usr/bin/env bash
set -euo pipefail

LINXCORE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd -- "${LINXCORE_ROOT}/../.." && pwd)"

SPEC_DIR="${SPEC_DIR:-${REPO_ROOT}/workloads/spec2017/cpu2017v118_x64_gcc12_avx2}"
SPEC_INPUT_SET="${SPEC_INPUT_SET:-test}"
OUT_ROOT="${SPEC_XCHECK_OUT_ROOT:-${REPO_ROOT}/workloads/generated/spec2017/stage_a_xcheck}"
POLICY_MANIFEST="${SPEC_POLICY_MANIFEST:-${REPO_ROOT}/docs/bringup/agent_runs/manifest.yaml}"

BENCHES=(
  "999.specrand_ir"
  "505.mcf_r"
  "531.deepsjeng_r"
)

mkdir -p "${OUT_ROOT}"

MATRIX_OUT="${OUT_ROOT}/qemu_matrix"
IMAGES_OUT="${OUT_ROOT}/phaseb_static_images"
SUITE_JSON="${OUT_ROOT}/linxcore_suite_stage_a.json"
XCHECK_OUT="${OUT_ROOT}/xcheck"

build_cmd=(
  bash "${REPO_ROOT}/tools/spec2017/build_int_rate_linx.sh"
  --spec-dir "${SPEC_DIR}"
  --mode phase-c
)
if [[ "${SPEC_BUILD_RUNTIMES:-0}" == "1" ]]; then
  build_cmd+=(--build-runtimes)
fi
for bench in "${BENCHES[@]}"; do
  build_cmd+=(--bench "${bench}")
done
"${build_cmd[@]}"

matrix_cmd=(
  python3 "${REPO_ROOT}/tools/spec2017/run_stage_qemu_matrix.py"
  --spec-dir "${SPEC_DIR}"
  --stage a
  --input-set "${SPEC_INPUT_SET}"
  --transports "9p,initramfs"
  --strict
  --out-dir "${MATRIX_OUT}"
)
for bench in "${BENCHES[@]}"; do
  matrix_cmd+=(--bench "${bench}")
done
"${matrix_cmd[@]}"

phaseb_cmd=(
  python3 "${REPO_ROOT}/tools/spec2017/prepare_phaseb_static_images.py"
  --spec-dir "${SPEC_DIR}"
  --out-dir "${IMAGES_OUT}"
  --mode phase-b
)
if [[ -n "${SPEC_PHASEB_OPTIMIZE:-}" ]]; then
  phaseb_cmd+=(--optimize "${SPEC_PHASEB_OPTIMIZE}")
fi
for bench in "${BENCHES[@]}"; do
  phaseb_cmd+=(--bench "${bench}")
done
"${phaseb_cmd[@]}"

python3 "${REPO_ROOT}/tools/spec2017/gen_linxcore_xcheck_suite.py" \
  --image-manifest "${IMAGES_OUT}/phaseb_image_manifest.json" \
  --stage a \
  --policy-manifest "${POLICY_MANIFEST}" \
  --out "${SUITE_JSON}"

xcheck_cmd=(
  python3 "${REPO_ROOT}/rtl/LinxCore/tools/trace/run_benchmark_suite_xcheck.py"
  --suite "${SUITE_JSON}"
  --out-dir "${XCHECK_OUT}"
  --mode failfast
  --max-commits 1000
)
if [[ -n "${SPEC_TB_MAX_CYCLES:-}" ]]; then
  xcheck_cmd+=(--tb-max-cycles "${SPEC_TB_MAX_CYCLES}")
fi
if [[ -n "${SPEC_QEMU_MAX_SECONDS:-}" ]]; then
  xcheck_cmd+=(--qemu-max-seconds "${SPEC_QEMU_MAX_SECONDS}")
fi
"${xcheck_cmd[@]}"

echo "spec stage-a xcheck: ok"
echo "qemu_matrix=${MATRIX_OUT}/qemu_matrix_summary.json"
echo "phaseb_manifest=${IMAGES_OUT}/phaseb_image_manifest.json"
echo "suite_json=${SUITE_JSON}"
echo "xcheck_summary=${XCHECK_OUT}/summary.json"
