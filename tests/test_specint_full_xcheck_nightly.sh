#!/usr/bin/env bash
set -euo pipefail

LINXCORE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd -- "${LINXCORE_ROOT}/../.." && pwd)"

SPEC_DIR="${SPEC_DIR:-${REPO_ROOT}/workloads/spec2017/cpu2017v118_x64_gcc12_avx2}"
SPEC_INPUT_SET="${SPEC_INPUT_SET:-test}"
SPEC_NIGHTLY_REPORT_ONLY="${SPEC_NIGHTLY_REPORT_ONLY:-1}"
OUT_ROOT="${SPEC_XCHECK_OUT_ROOT:-${REPO_ROOT}/workloads/generated/spec2017/full_xcheck_nightly}"
POLICY_MANIFEST="${SPEC_POLICY_MANIFEST:-${REPO_ROOT}/docs/bringup/agent_runs/manifest.yaml}"

if [[ "${SPEC_NIGHTLY_REPORT_ONLY}" != "0" && "${SPEC_NIGHTLY_REPORT_ONLY}" != "1" ]]; then
  echo "error: SPEC_NIGHTLY_REPORT_ONLY must be 0 or 1 (got ${SPEC_NIGHTLY_REPORT_ONLY})" >&2
  exit 2
fi

mapfile -t BENCHES < <(python3 - "${POLICY_MANIFEST}" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
obj = None
try:
    obj = json.loads(text)
except json.JSONDecodeError:
    try:
        import yaml  # type: ignore
    except ImportError as exc:
        raise SystemExit(f"error: failed to parse policy manifest as JSON and PyYAML is unavailable: {path}") from exc
    obj = yaml.safe_load(text)
if not isinstance(obj, dict):
    raise SystemExit(f"error: invalid policy manifest root: {path}")
policy = obj.get("spec_policy")
if not isinstance(policy, dict):
    raise SystemExit(f"error: missing spec_policy in: {path}")
stage_b = policy.get("stage_b_required")
if not isinstance(stage_b, list):
    raise SystemExit(f"error: spec_policy.stage_b_required must be list in: {path}")
excluded = policy.get("excluded_benchmarks", [])
excluded_set = set(str(x) for x in excluded) if isinstance(excluded, list) else set()
for bench in stage_b:
    b = str(bench)
    if b in excluded_set:
        continue
    print(b)
PY
)

if [[ "${#BENCHES[@]}" -eq 0 ]]; then
  echo "error: no stage-b benches resolved from ${POLICY_MANIFEST}" >&2
  exit 2
fi

mkdir -p "${OUT_ROOT}"

MATRIX_OUT="${OUT_ROOT}/qemu_matrix"
IMAGES_OUT="${OUT_ROOT}/phaseb_static_images"
SUITE_JSON="${OUT_ROOT}/linxcore_suite_stage_b.json"
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
  --stage b
  --input-set "${SPEC_INPUT_SET}"
  --transports "9p"
  --out-dir "${MATRIX_OUT}"
)
if [[ "${SPEC_NIGHTLY_REPORT_ONLY}" == "0" ]]; then
  matrix_cmd+=(--strict)
fi
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
  --stage b \
  --policy-manifest "${POLICY_MANIFEST}" \
  --out "${SUITE_JSON}"

xcheck_cmd=(
  python3 "${REPO_ROOT}/rtl/LinxCore/tools/trace/run_benchmark_suite_xcheck.py"
  --suite "${SUITE_JSON}"
  --out-dir "${XCHECK_OUT}"
  --mode diagnostic
  --max-commits 1000
  --continue-on-fail
)
if [[ "${SPEC_NIGHTLY_REPORT_ONLY}" == "1" ]]; then
  xcheck_cmd+=(--report-only)
fi
if [[ -n "${SPEC_TB_MAX_CYCLES:-}" ]]; then
  xcheck_cmd+=(--tb-max-cycles "${SPEC_TB_MAX_CYCLES}")
fi
if [[ -n "${SPEC_QEMU_MAX_SECONDS:-}" ]]; then
  xcheck_cmd+=(--qemu-max-seconds "${SPEC_QEMU_MAX_SECONDS}")
fi
"${xcheck_cmd[@]}"

echo "spec nightly xcheck: completed"
echo "report_only=${SPEC_NIGHTLY_REPORT_ONLY}"
echo "qemu_matrix=${MATRIX_OUT}/qemu_matrix_summary.json"
echo "phaseb_manifest=${IMAGES_OUT}/phaseb_image_manifest.json"
echo "suite_json=${SUITE_JSON}"
echo "xcheck_summary=${XCHECK_OUT}/summary.json"
