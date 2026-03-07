#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
TRACE="${ROOT_DIR}/tests/benchmarks_latest_llvm_musl/verify/coremark/qemu_trace.jsonl"

bash "${ROOT_DIR}/standalone/OEX/tools/run_oex_shadow.sh" "${TRACE}" "$@"
