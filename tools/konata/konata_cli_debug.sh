#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -lt 1 ]]; then
  echo "usage: $0 <trace.konata> [top_rows]" >&2
  exit 2
fi

TRACE="$1"
TOP="${2:-20}"

python3 "${ROOT_DIR}/tools/konata/konata_cli_debug.py" \
  "${TRACE}" \
  --top "${TOP}" \
  --show-empty \
  --require-stages F0,D3,IQ,BROB,CMT || true

if [[ -d "/Users/zhoubot/Konata" ]]; then
  node - "${TRACE}" <<'NODE'
const path = process.argv[2];
const {OnikiriParser} = require('/Users/zhoubot/Konata/onikiri_parser.js');
const {FileReader} = require('/Users/zhoubot/Konata/file_reader.js');
const p = new OnikiriParser();
const f = new FileReader();
f.open(path);
function finish() {
  let ids = 0;
  for (let i = 0; i <= p.lastID; i++) {
    if (p.getOp(i)) ids++;
  }
  console.log(`node_parser ids=${ids} rids=${p.lastRID}`);
  process.exit(0);
}
function update() {}
function error(parseErr, e) {
  console.error(`node_parser error parseErr=${parseErr} err=${e}`);
  process.exit(1);
}
p.setFile(f, update, finish, error);
NODE
fi
