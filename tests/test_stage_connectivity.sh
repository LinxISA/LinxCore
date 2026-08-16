#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LINX_ROOT="$(cd -- "${ROOT_DIR}/../.." && pwd)"
ISA_PROFILE="${LINXCORE_ISA_PROFILE:-v0.58}"

python3 "${ROOT_DIR}/tools/generate/lint_stage_naming.py"
python3 "${ROOT_DIR}/tools/generate/lint_stage_spec_ownership.py"
python3 "${ROOT_DIR}/tools/generate/lint_no_stubs.py"
python3 "${ROOT_DIR}/tools/generate/lint_engine_ownership.py"
python3 "${ROOT_DIR}/tools/linxcoresight/lint_trace_contract_sync.py"
parity_args=(
  --linxisa-root "${LINX_ROOT}"
  --isa-profile "${ISA_PROFILE}"
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"
)
python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" "${parity_args[@]}"

python3 - <<'PY' "${ROOT_DIR}"
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
sys.path.insert(0, str(root / 'src'))
from common.interfaces import INTERFACE_SPEC

src_text = []
for p in (root / 'src').rglob('*.py'):
    if p.parts[-2:] == ('common', 'interfaces.py'):
        continue
    src_text.append(p.read_text(encoding='utf-8'))
joined = '\n'.join(src_text)

missing = [prefix for prefix in INTERFACE_SPEC if prefix not in joined]
if missing:
    raise SystemExit('missing interface prefix references in source tree: ' + ', '.join(missing))

print('stage connectivity prefix check passed')
PY
