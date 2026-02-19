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
  linxtrace_path="${out_dir}/${name}_${MAX_COMMITS}insn.linxtrace"
else
  linxtrace_path="${out_dir}/${name}.linxtrace"
fi

if [[ "${MAX_COMMITS}" =~ ^[0-9]+$ ]] && [[ "${MAX_COMMITS}" -gt 0 ]]; then
  commit_trace_path="${out_dir}/${name}_${MAX_COMMITS}insn.commit.jsonl"
else
  commit_trace_path="${out_dir}/${name}.commit.jsonl"
fi
commit_text_path="${commit_trace_path%.jsonl}.txt"
raw_trace_path="${out_dir}/${name}.raw_events.jsonl"
map_report_path="${linxtrace_path}.map.json"

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
  --map-report "${map_report_path}"
  --commit-text "${commit_text_path}"
)
if [[ -n "${objdump_elf}" ]]; then
  builder_args+=(--elf "${objdump_elf}")
fi
python3 "${ROOT_DIR}/tools/trace/build_linxtrace_view.py" "${builder_args[@]}"

python3 "${ROOT_DIR}/tools/linxcoresight/lint_linxtrace.py" \
  "${linxtrace_path}" \
  --require-stages F1,F2,F3,F4,IB,D1,D2,D3,S1,IQ,BROB,CMT

python3 - "${commit_trace_path}" <<'PY'
import json
import os
import sys

path = sys.argv[1]
max_pct = float(os.environ.get("PYC_UNKNOWN_OP_MAX_PCT", "0.5"))
total = 0
unknown = 0
with open(path, "r", encoding="utf-8", errors="ignore") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        rtype = rec.get("type")
        if rtype is not None and rtype != "commit":
            continue
        # Flat commit schema (no "type") still has commit payload fields.
        if rtype is None and "seq" not in rec and "pc" not in rec:
            continue
        total += 1
        name = str(rec.get("op_name", ""))
        if name == "OP_UNKNOWN":
            unknown += 1
if total == 0:
    raise SystemExit("unknown-op gate: no commit rows found")
pct = (100.0 * unknown / float(total))
print(f"unknown-op gate: unknown={unknown} total={total} pct={pct:.4f}% max={max_pct:.4f}%")
if pct > max_pct:
    raise SystemExit(f"unknown-op ratio too high: {pct:.4f}% > {max_pct:.4f}%")
PY

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

if [[ "${PYC_LINXTRACE_STRICT_CLEAN:-1}" == "1" ]]; then
  rm -rf "${ROOT_DIR}/generated/konata"
  if find "${ROOT_DIR}/generated" -type f -name "*.konata" -print -quit | grep -q .; then
    echo "error: legacy konata artifact detected in generated output tree" >&2
    exit 1
  fi
fi

if [[ "${PYC_LINXTRACE_CLI_DEBUG:-1}" == "1" ]]; then
  bash "${ROOT_DIR}/tools/linxcoresight/linxtrace_cli_debug.sh" "${linxtrace_path}" 12
fi

if [[ -n "${boot_pc}" ]]; then
  echo "linxtrace: ${linxtrace_path} (boot_pc=${boot_pc})"
else
  echo "linxtrace: ${linxtrace_path}"
fi
echo "commit_jsonl: ${commit_trace_path}"
echo "trace_txt: ${commit_text_path}"
echo "raw_events: ${raw_trace_path}"
echo "map_report: ${map_report_path}"
