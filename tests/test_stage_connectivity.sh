#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

python3 "${ROOT_DIR}/tools/generate/lint_stage_naming.py"
python3 "${ROOT_DIR}/tools/generate/lint_no_stubs.py"
python3 "${ROOT_DIR}/tools/generate/check_decode_parity.py" \
  --qemu-linx-dir /Users/zhoubot/qemu/target/linx \
  --catalog "${ROOT_DIR}/src/common/opcode_catalog.yaml"

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
