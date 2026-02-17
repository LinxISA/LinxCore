#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ $# -lt 1 ]]; then
  echo "usage: $0 <program.memh> [max_commits]" >&2
  exit 2
fi
MEMH="$1"
MAX_COMMITS="${2:-${PYC_KONATA_MAX_COMMITS:-0}}"
name="$(basename "${MEMH}")"
name="${name%.memh}"
LLVM_READELF="${LLVM_READELF:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf}"
LINXISA_DIR="${LINXISA_DIR:-${HOME}/linx-isa}"
out_dir="${ROOT_DIR}/generated/konata"
if [[ "${name}" == *"coremark"* ]]; then
  out_dir="${out_dir}/coremark"
elif [[ "${name}" == *"dhrystone"* ]]; then
  out_dir="${out_dir}/dhrystone"
fi
mkdir -p "${out_dir}"
if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  konata_path="${out_dir}/${name}_${MAX_COMMITS}insn.konata"
else
  konata_path="${out_dir}/${name}.konata"
fi
if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  commit_trace_path="${out_dir}/${name}_${MAX_COMMITS}insn.commit.jsonl"
else
  commit_trace_path="${out_dir}/${name}.commit.jsonl"
fi
commit_text_path="${commit_trace_path%.jsonl}.txt"
raw_trace_path="${out_dir}/${name}.raw_events.jsonl"
map_report_path="${out_dir}/${name}.konata.map.json"

objdump_elf="${PYC_OBJDUMP_ELF:-${PYC_KONATA_ELF:-}}"
if [[ -z "${objdump_elf}" ]]; then
  sidecar="${MEMH%.memh}.elf"
  if [[ -f "${sidecar}" ]]; then
    objdump_elf="${sidecar}"
  elif [[ "${name}" == *"coremark"* ]]; then
    cand="${LINXISA_DIR}/workloads/generated/elf/coremark.elf"
    if [[ -f "${cand}" ]]; then
      objdump_elf="${cand}"
    fi
  elif [[ "${name}" == *"dhrystone"* ]]; then
    cand="${LINXISA_DIR}/workloads/generated/elf/dhrystone.elf"
    if [[ -f "${cand}" ]]; then
      objdump_elf="${cand}"
    fi
  fi
fi

boot_pc="${PYC_BOOT_PC:-}"
if [[ -z "${boot_pc}" && -n "${objdump_elf}" && -x "${LLVM_READELF}" ]]; then
  boot_pc="$("${LLVM_READELF}" -h "${objdump_elf}" | awk '/Entry point address:/ {print $4; exit}')"
fi

run_env=(
  "PYC_KONATA=0"
  "PYC_KONATA_PATH=${konata_path}"
  "PYC_RAW_TRACE=${raw_trace_path}"
  "PYC_COMMIT_TRACE=${commit_trace_path}"
  "PYC_COMMIT_TRACE_TEXT=${commit_text_path}"
)
if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  run_env+=("PYC_MAX_COMMITS=${MAX_COMMITS}")
fi
if [[ -n "${boot_pc}" ]]; then
  run_env+=("PYC_BOOT_PC=${boot_pc}")
fi
if [[ -n "${objdump_elf}" ]]; then
  run_env+=("PYC_OBJDUMP_ELF=${objdump_elf}")
fi

env "${run_env[@]}" bash "${ROOT_DIR}/tools/generate/run_linxcore_top_cpp.sh" "${MEMH}"

builder_args=(
  --raw "${raw_trace_path}"
  --out "${konata_path}"
  --map-report "${map_report_path}"
  --commit-text "${commit_text_path}"
)
if [[ -n "${objdump_elf}" ]]; then
  builder_args+=(--elf "${objdump_elf}")
fi
python3 "${ROOT_DIR}/tools/trace/build_konata_block_view.py" "${builder_args[@]}"

python3 "${ROOT_DIR}/tools/konata/check_konata_stages.py" \
  "${konata_path}" \
  --require-stages F0,F3,D1,D3,IQ,BROB,CMT \
  --single-stage-per-cycle
if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  python3 - "$konata_path" "$MAX_COMMITS" <<'PY'
import sys
path = sys.argv[1]
want = int(sys.argv[2])
retired = 0
with open(path, "r", errors="ignore") as f:
    for line in f:
        if line.startswith("R\t"):
            retired += 1
if retired < want:
    print(f"konata-retire-count {retired} (requested {want}, non-fatal in scope=all)")
else:
    print(f"konata-retire-count {retired} (requested {want})")
PY
fi

if [[ -d "/Users/zhoubot/Konata" ]]; then
  node - "$konata_path" "$MAX_COMMITS" <<'NODE'
const path = process.argv[2];
const want = Number(process.argv[3] || "0");
const {OnikiriParser} = require('/Users/zhoubot/Konata/onikiri_parser.js');
const {FileReader} = require('/Users/zhoubot/Konata/file_reader.js');
const p = new OnikiriParser();
const f = new FileReader();
f.open(path);
  function finish() {
  let parsedIds = 0;
  let parsedRids = 0;
  for (let i = 0; i <= p.lastID; i++) {
    if (p.getOp(i)) parsedIds++;
  }
  for (let i = 0; i <= p.lastRID; i++) {
    if (p.getOpFromRID(i)) parsedRids++;
  }
  if (want > 0 && parsedIds < want) {
    console.log(`konata-parser-visible ids=${parsedIds} requested=${want} (non-fatal in scope=all)`);
  }
  console.log(`konata-parser-ok ids=${parsedIds} rids=${parsedRids}`);
  process.exit(0);
}
function update() {}
function error(parseErr, e) {
  console.error(`error: konata parser failed parseErr=${parseErr} err=${e}`);
  process.exit(1);
}
p.setFile(f, update, finish, error);
NODE
fi

if [[ "${PYC_KONATA_CLI_DEBUG:-1}" == "1" ]]; then
  bash "${ROOT_DIR}/tools/konata/konata_cli_debug.sh" "${konata_path}" 12
fi

if [[ -n "${boot_pc}" ]]; then
  echo "konata: ${konata_path} (boot_pc=${boot_pc})"
else
  echo "konata: ${konata_path}"
fi
echo "commit_jsonl: ${commit_trace_path}"
echo "trace_txt: ${commit_text_path}"
echo "raw_events: ${raw_trace_path}"
echo "map_report: ${map_report_path}"
