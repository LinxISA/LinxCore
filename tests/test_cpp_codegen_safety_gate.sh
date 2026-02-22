#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
GEN_CPP_DIR="${ROOT_DIR}/generated/cpp/linxcore_top"
GEN_HDR="${GEN_CPP_DIR}/JanusBccBackendCompat.hpp"
RUN_CPP="${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh"
MEMH="${ROOT_DIR}/tests/artifacts/suites/arithmetic.memh"

bash "${ROOT_DIR}/tools/generate/update_generated_linxcore.sh" >/dev/null

python3 - <<'PY' "${GEN_CPP_DIR}" "${GEN_HDR}"
import pathlib
import re
import sys

gen_dir = pathlib.Path(sys.argv[1])
hdr = pathlib.Path(sys.argv[2])
parts = [hdr.read_text(encoding='utf-8', errors='replace')]
for cpp in sorted(gen_dir.glob('*.cpp')):
    parts.append(cpp.read_text(encoding='utf-8', errors='replace'))
text = "\n".join(parts)

if "void _pyc_validate_primitive_bindings() const" not in text:
    raise SystemExit("missing _pyc_validate_primitive_bindings in generated header")

reg_decl = re.findall(r"pyc::cpp::pyc_reg<[^>]+> \*([A-Za-z_][A-Za-z0-9_]*_inst) = nullptr;", text)
if not reg_decl:
    raise SystemExit("no pointer-based reg primitive declarations found")

if "_inst->tick_compute()" not in text or "_inst->tick_commit()" not in text:
    raise SystemExit("tick paths are not pointer-based for reg primitives")

sample = reg_decl[: min(256, len(reg_decl))]
for name in sample:
    assign_pat = f"{name} = new pyc::cpp::pyc_reg<"
    if assign_pat not in text:
        raise SystemExit(f"missing constructor assignment for {name}")

legacy_hits = []
for name in sample:
    if f"{name}(" in text:
        legacy_hits.append(name)
if legacy_hits:
    raise SystemExit("legacy initializer-list style reg construction still present: " + ",".join(legacy_hits[:8]))

print(f"gate: validated pointer-bound reg primitives ({len(reg_decl)} total)")
PY

if [[ ! -f "${MEMH}" ]]; then
  echo "missing test memh: ${MEMH}" >&2
  exit 2
fi

# Baseline smoke: program completes and exits normally.
PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
PYC_MAX_CYCLES=50000 \
  bash "${RUN_CPP}" "${MEMH}" >/tmp/linxcore_cpp_gate_baseline.log 2>&1

# Constructor-eval stress: should not abort; max-cycle timeout is expected here.
set +e
ctor_out="$({
  PYC_TB_CXXFLAGS="${PYC_TB_CXXFLAGS:--O0 -g0}" \
  PYC_ENABLE_CTOR_EVAL=1 \
  PYC_MAX_CYCLES=1 \
    bash "${RUN_CPP}" "${MEMH}"
} 2>&1)"
ctor_rc=$?
set -e

if [[ "${ctor_rc}" -ne 1 ]]; then
  echo "unexpected ctor-eval rc=${ctor_rc}" >&2
  echo "${ctor_out}" >&2
  exit 3
fi
if ! grep -q "max cycles reached" <<<"${ctor_out}"; then
  echo "ctor-eval run did not hit expected timeout path" >&2
  echo "${ctor_out}" >&2
  exit 4
fi
if grep -Eiq "(Segmentation fault|AddressSanitizer|pointer being freed|null reg binding|abort)" <<<"${ctor_out}"; then
  echo "ctor-eval run indicates primitive binding failure" >&2
  echo "${ctor_out}" >&2
  exit 5
fi

echo "cpp codegen safety gate passed"
