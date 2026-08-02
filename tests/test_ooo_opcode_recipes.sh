#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CATALOG="${ROOT_DIR}/src/common/opcode_catalog.yaml"
SCALA="${ROOT_DIR}/chisel/src/main/scala/linxcore/ooo/OooOpcodeRecipeTable.scala"
AUDIT="${ROOT_DIR}/docs/chisel/ooo-opcode-recipe-audit.md"
TMP_DIR="$(mktemp -d -t linxcore-ooo-recipes.XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

python3 "${ROOT_DIR}/tools/chisel/gen_ooo_recipe_table.py" \
  --catalog "${CATALOG}" \
  --scala-out "${TMP_DIR}/OooOpcodeRecipeTable.scala" \
  --audit-out "${TMP_DIR}/ooo-opcode-recipe-audit.md"

cmp "${SCALA}" "${TMP_DIR}/OooOpcodeRecipeTable.scala"
cmp "${AUDIT}" "${TMP_DIR}/ooo-opcode-recipe-audit.md"

python3 - "${CATALOG}" <<'PY'
import json
import sys

catalog = json.load(open(sys.argv[1], encoding="utf-8"))
assert catalog["version"] == 2
rows = catalog["records"]
assert len(rows) == 874
allowed = {"DISPATCH", "FAST_RESOLVE", "CTU", "ILLEGAL"}
assert all(row["ooo"]["disposition"] in allowed for row in rows)
assert all(
    (row["ooo"]["dispatch_writes"] > 0) ==
    (row["ooo"]["disposition"] == "DISPATCH")
    for row in rows
)

by_symbol = {}
for row in rows:
    by_symbol.setdefault(row["symbol"], row["ooo"])

assert by_symbol["OP_C_BSTOP"]["fast_resolve_class"] == "BOUNDARY_METADATA"
assert by_symbol["OP_START_CALL_32"]["fast_resolve_class"] == "CONTROL_VALUE_PRODUCER"
assert by_symbol["OP_SETRET"]["fast_resolve_class"] == "IMMEDIATE_PRODUCER"
assert by_symbol["OP_FENTRY"]["disposition"] == "CTU"
assert by_symbol["OP_MCOPY"]["disposition"] == "ILLEGAL"
assert by_symbol["OP_LR_W"]["recipe_kind"] == "ATOMIC_UNRESOLVED"
assert by_symbol["OP_HL_LDIP"]["recipe_kind"] == "PAIR_LOAD"
assert by_symbol["OP_HL_LDIP"]["memory_request_count"] == 2
assert by_symbol["OP_HL_SDIP"]["recipe_kind"] == "PAIR_STORE"
assert by_symbol["OP_HL_SDIP"]["p_source_count"] == 3
assert by_symbol["OP_HL_SDP"]["p_source_count"] == 4
assert by_symbol["OP_HL_SDIP"]["dispatch_demand"]["AGU"] == 1
assert by_symbol["OP_HL_SDIP"]["dispatch_demand"]["STD"] == 1
assert by_symbol["OP_SD"]["late_split_kind"] == "STORE_ADDRESS_DATA"
assert by_symbol["OP_SD"]["p_source_count"] == 3
assert by_symbol["OP_SDI"]["p_source_count"] == 2
assert by_symbol["OP_SD_PCR"]["pc_read_parent"] == "PRIMARY"
assert by_symbol["OP_SD_PCR"]["pc_read_class"] == "AGU"
assert by_symbol["OP_HL_SD_PCR"]["pc_read_parent"] == "PRIMARY"
assert by_symbol["OP_HL_SD_PCR"]["pc_read_class"] == "ALU"
assert by_symbol["OP_ACRC"]["disposition"] == "DISPATCH"
assert by_symbol["OP_BSTART_TMA"]["recipe_kind"] == "ENGINE_CMD"
for symbol in (
    "OP_BSTART_VPAR",
    "OP_BSTART_VSEQ",
    "OP_C_BSTART_VPAR",
    "OP_C_BSTART_VSEQ",
    "OP_V_QPOP",
    "OP_V_QPUSH",
):
    assert symbol in by_symbol

tepl_carrier = next(row for row in rows if row["mnemonic"] == "bstart_tepl")
assert tepl_carrier["flags"] == "DECODE_ONLY_CARRIER"
assert tepl_carrier["ooo"]["recipe_kind"] == "ENGINE_CMD"
PY

echo "ooo-opcode-recipes: pass records=874 deterministic=yes"
