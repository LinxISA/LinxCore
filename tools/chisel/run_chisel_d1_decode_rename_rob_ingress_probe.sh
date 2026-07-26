#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${ROOT_DIR}/tools/chisel/chisel_env.sh"

OUT_DIR="${ROOT_DIR}/generated/chisel-verilog/d1-decode-rename-rob-ingress"
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cd "${ROOT_DIR}/chisel"
sbt --server --batch --no-colors \
  "runMain linxcore.backend.EmitD1DecodeRenameROBIngress --target-dir ${OUT_DIR}"

sv_sources=()
while IFS= read -r source; do
  sv_sources+=("${source}")
done < <(find "${OUT_DIR}" -maxdepth 1 -name '*.sv' -type f -print | sort)
if [[ "${#sv_sources[@]}" -eq 0 ]]; then
  echo "D1 ingress probe emitted no SystemVerilog" >&2
  exit 1
fi

for required_module in \
  D1DecodeRenameROBIngress \
  D1DecodedLaneQueue \
  DecodeRenameROBPath \
  DecodeRenameQueue \
  ScalarTURenameBridge \
  DispatchROBAllocator; do
  if ! grep -Eq "module ${required_module}([ #(]|$)" "${sv_sources[@]}"; then
    echo "D1 ingress probe is missing ${required_module}" >&2
    exit 1
  fi
done

for forbidden_module in F4DecodeWindow F4DenseSlotQueue FrontendDecodeStage; do
  if grep -Eq "module ${forbidden_module}([ #(]|$)" "${sv_sources[@]}"; then
    echo "production D1 ingress unexpectedly contains ${forbidden_module}" >&2
    exit 1
  fi
done

verilator --lint-only --timing --top-module D1DecodeRenameROBIngress "${sv_sources[@]}"
echo "D1 fixed-width ingress generated-RTL probe passed (${#sv_sources[@]} sources)"
