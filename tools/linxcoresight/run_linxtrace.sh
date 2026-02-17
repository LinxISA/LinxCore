#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 "${ROOT_DIR}/tools/linxcoresight/lint_trace_contract_sync.py"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <program.memh> [max_commits]" >&2
  exit 2
fi

MEMH="$1"
MAX_COMMITS="${2:-${PYC_LINXTRACE_MAX_COMMITS:-0}}"
name="$(basename "${MEMH}")"
name="${name%.memh}"
LLVM_READELF="${LLVM_READELF:-/Users/zhoubot/llvm-project/build-linxisa-clang/bin/llvm-readelf}"
LINXISA_DIR="${LINXISA_DIR:-${HOME}/linx-isa}"

out_dir="${ROOT_DIR}/generated/linxtrace"
if [[ "${name}" == *"coremark"* ]]; then
  out_dir="${out_dir}/coremark"
elif [[ "${name}" == *"dhrystone"* ]]; then
  out_dir="${out_dir}/dhrystone"
fi
mkdir -p "${out_dir}"

if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  linxtrace_path="${out_dir}/${name}_${MAX_COMMITS}insn.linxtrace.jsonl"
else
  linxtrace_path="${out_dir}/${name}.linxtrace.jsonl"
fi

if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  commit_trace_path="${out_dir}/${name}_${MAX_COMMITS}insn.commit.jsonl"
else
  commit_trace_path="${out_dir}/${name}.commit.jsonl"
fi
commit_text_path="${commit_trace_path%.jsonl}.txt"
raw_trace_path="${out_dir}/${name}.raw_events.jsonl"
map_report_path="${out_dir}/${name}.linxtrace.map.json"
meta_path="${linxtrace_path%.linxtrace.jsonl}.linxtrace.meta.json"

objdump_elf="${PYC_OBJDUMP_ELF:-${PYC_LINXTRACE_ELF:-}}"
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
  "PYC_LINXTRACE=0"
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
  --out "${linxtrace_path}"
  --meta-out "${meta_path}"
  --map-report "${map_report_path}"
  --commit-text "${commit_text_path}"
)
if [[ -n "${objdump_elf}" ]]; then
  builder_args+=(--elf "${objdump_elf}")
fi
python3 "${ROOT_DIR}/tools/trace/build_linxtrace_view.py" "${builder_args[@]}"

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" \
  "${linxtrace_path}" \
  --meta "${meta_path}" \
  --require-stages F0,F3,D1,D3,IQ,BROB,CMT \
  --single-stage-per-cycle

if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  python3 - "$linxtrace_path" "$MAX_COMMITS" <<'PY'
import json
import sys

path = sys.argv[1]
want = int(sys.argv[2])
retired = 0
with open(path, "r", errors="ignore") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        if rec.get("type") == "RETIRE":
            retired += 1
if retired < want:
    print(f"linxtrace-retire-count {retired} (requested {want}, non-fatal in scope=all)")
else:
    print(f"linxtrace-retire-count {retired} (requested {want})")
PY
fi

if [[ "${PYC_LINXTRACE_CLI_DEBUG:-1}" == "1" ]]; then
  bash "${ROOT_DIR}/tools/linxcoresight/linxtrace_cli_debug.sh" "${linxtrace_path}" 12
fi

if [[ -n "${boot_pc}" ]]; then
  echo "linxtrace: ${linxtrace_path} (boot_pc=${boot_pc})"
else
  echo "linxtrace: ${linxtrace_path}"
fi
echo "meta: ${meta_path}"
echo "commit_jsonl: ${commit_trace_path}"
echo "trace_txt: ${commit_text_path}"
echo "raw_events: ${raw_trace_path}"
echo "map_report: ${map_report_path}"
