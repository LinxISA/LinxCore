#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export ROOT_DIR

python3 "${ROOT_DIR}/scripts/lint_stage_naming.py"

python3 - <<'PY'
import sys
import os
from pathlib import Path

root = Path(os.environ["ROOT_DIR"]).resolve()
sys.path.insert(0, str(root / "pyc"))
from linxcore.janus.common.interfaces import INTERFACE_SPEC

janus_text = []
for p in (root / 'pyc/linxcore/janus').rglob('*.py'):
    janus_text.append(p.read_text(encoding='utf-8'))
joined = '\n'.join(janus_text)

missing = []
for prefix in INTERFACE_SPEC:
    if prefix not in joined:
        missing.append(prefix)

if missing:
    raise SystemExit('missing interface prefix references in Janus source tree: ' + ', '.join(missing))

print('stage connectivity prefix check passed')
PY
