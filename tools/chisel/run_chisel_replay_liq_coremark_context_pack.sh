#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_DIR="${BUILD_DIR:-${ROOT_DIR}/generated/r525-replay-liq-coremark-context-pack}"
COREMARK_ELF="${COREMARK_ELF:-${ROOT_DIR}/tests/benchmarks/build/coremark_real.elf}"
COREMARK_ROWS="${COREMARK_ROWS:-1024}"
COREMARK_MAX_SECONDS="${COREMARK_MAX_SECONDS:-30}"
REPLAY_CAPTURE_ROWS="${REPLAY_CAPTURE_ROWS:-18}"
REPLAY_MAX_SECONDS="${REPLAY_MAX_SECONDS:-8}"
RUN_FOCUSED=1
ALLOW_NATURAL_COREMARK_REPLAY=0

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [options]

Options:
  --build-dir <dir>              Output root (default: ${BUILD_DIR})
  --coremark-elf <elf>           CoreMark ELF (default: ${COREMARK_ELF})
  --coremark-rows <int>          Raw CoreMark QEMU rows to capture (default: ${COREMARK_ROWS})
  --coremark-max-seconds <int>   CoreMark capture watchdog (default: ${COREMARK_MAX_SECONDS})
  --replay-capture-rows <int>    Constructed replay-loop rows to capture (default: ${REPLAY_CAPTURE_ROWS})
  --replay-max-seconds <int>     Constructed replay-loop watchdog (default: ${REPLAY_MAX_SECONDS})
  --skip-focused                 Skip focused Chisel specs before generated-RTL gates
  --allow-natural-coremark-replay
                                  Do not require the CoreMark prefix replay-LIQ counters to stay zero

Runs the focused replay-LIQ owner specs, a real CoreMark reduced-store
replay-LIQ prefix gate, and the R524 constructed replay-LIQ/MDB positive gate.
The combined manifest explicitly separates natural CoreMark no-regression
evidence from constructed replay-LIQ/MDB evidence; it is not full LSU or
natural CoreMark replay coverage.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build-dir) BUILD_DIR="$2"; shift 2 ;;
    --coremark-elf) COREMARK_ELF="$2"; shift 2 ;;
    --coremark-rows) COREMARK_ROWS="$2"; shift 2 ;;
    --coremark-max-seconds) COREMARK_MAX_SECONDS="$2"; shift 2 ;;
    --replay-capture-rows) REPLAY_CAPTURE_ROWS="$2"; shift 2 ;;
    --replay-max-seconds) REPLAY_MAX_SECONDS="$2"; shift 2 ;;
    --skip-focused) RUN_FOCUSED=0; shift ;;
    --allow-natural-coremark-replay) ALLOW_NATURAL_COREMARK_REPLAY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ "${BUILD_DIR}" != /* ]]; then
  BUILD_DIR="${ROOT_DIR}/${BUILD_DIR}"
fi
if [[ "${COREMARK_ELF}" != /* ]]; then
  COREMARK_ELF="${ROOT_DIR}/${COREMARK_ELF}"
fi
if [[ ! -f "${COREMARK_ELF}" ]]; then
  echo "error: CoreMark ELF not found: ${COREMARK_ELF}" >&2
  exit 2
fi
for numeric in COREMARK_ROWS COREMARK_MAX_SECONDS REPLAY_CAPTURE_ROWS REPLAY_MAX_SECONDS; do
  value="${!numeric}"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: ${numeric} must be a positive integer, got ${value}" >&2
    exit 2
  fi
done

LOG_DIR="${BUILD_DIR}/logs"
REPORT_DIR="${BUILD_DIR}/report"
COREMARK_BUILD_DIR="${BUILD_DIR}/coremark-prefix"
REPLAY_BUILD_DIR="${BUILD_DIR}/constructed-replay-loop"
mkdir -p "${LOG_DIR}" "${REPORT_DIR}"

run_logged() {
  local label="$1"
  shift
  local log="${LOG_DIR}/${label}.log"
  local cmd_log="${LOG_DIR}/${label}.cmd"
  printf '%q ' "$@" > "${cmd_log}"
  printf '\n' >> "${cmd_log}"
  echo "r525-pack-run=${label}"
  "$@" 2>&1 | tee "${log}"
}

if [[ "${RUN_FOCUSED}" == "1" ]]; then
  for spec in \
    ReducedStoreResidentForward \
    LoadForwardPipeline \
    LoadInflightQueue \
    LoadReplayWakeup \
    MDBConflictDetect \
    LinxCoreFrontendFetchRfAluTraceTop; do
    run_logged "focused-${spec}" \
      bash "${ROOT_DIR}/tools/chisel/run_chisel_tests.sh" --only "${spec}"
  done
fi

COREMARK_ZERO_COUNTERS="wait_replay_capture_accepted,replay_queue_out_fire,liq_alloc_accepted,mdb_conflict_valid,mdb_lookup_wait_plan_request_valid,liq_replay_wake_wait_store_clear"
COREMARK_ENV=(
  "FETCH_REPLAY_LIQ_REQUIRE_ZERO=${COREMARK_ZERO_COUNTERS}"
)
if [[ "${ALLOW_NATURAL_COREMARK_REPLAY}" == "1" ]]; then
  COREMARK_ENV=()
fi

run_logged "coremark-prefix-replay-liq" \
  env "${COREMARK_ENV[@]}" \
  bash "${ROOT_DIR}/tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh" \
    --build-dir "${COREMARK_BUILD_DIR}" \
    --elf "${COREMARK_ELF}" \
    --expected-rows 0 \
    --capture-rows "${COREMARK_ROWS}" \
    --allow-block-markers \
    --allow-block-loop-reentry \
    --reduced-store-replay-liq \
    --disable-store-memory-mutation \
    --max-seconds "${COREMARK_MAX_SECONDS}" \
    -- \
    -nographic \
    -monitor none \
    -machine virt \
    -m 1280M \
    -kernel "${COREMARK_ELF}"

REPLAY_NONZERO_COUNTERS="wait_replay_capture_accepted,wait_replay_clear_valid,wait_replay_relaunch_valid,replay_queue_enqueue_accepted,replay_queue_out_fire,liq_alloc_accepted,liq_base_lookup_granted,source_return_query_issued,source_return_response_apply_valid,source_row_mutation_request_valid,liq_row_mutation_write_enable,resolve_queue_push_accepted,resolve_queue_valid,mdb_conflict_store_valid,mdb_conflict_store_with_resolve_queue_valid,mdb_conflict_resolve_candidate,mdb_conflict_valid,mdb_fanout_record_valid,mdb_fanout_record_accepted,mdb_fanout_record_processed,mdb_fanout_bmdb_report,mdb_fanout_ssit_nonempty,mdb_fanout_lookup_valid,mdb_fanout_lookup_accepted,mdb_fanout_lookup_processed,mdb_fanout_lookup_table_hit,mdb_fanout_lu_out_valid,mdb_fanout_su_out_valid,mdb_fanout_lu_out_hit,mdb_fanout_su_out_hit,mdb_lookup_wait_plan_lookup_hit,mdb_lookup_wait_plan_wait_intent_valid,mdb_lookup_wait_plan_request_valid,mdb_lookup_wait_plan_bridge_valid,liq_row_mutation_selected_mdb_wait_plan,mdb_wait_plan_row_mutation_write_enable,liq_wait_store_mask_nonzero,liq_replay_wake_store_unit,liq_replay_wake_store_unit_full_match_active,liq_replay_wake_wait_store_clear"

run_logged "constructed-replay-loop" \
  env \
    "FETCH_REPLAY_LIQ_REQUIRE_NONZERO=${REPLAY_NONZERO_COUNTERS}" \
    "FETCH_REPLAY_LIQ_REQUIRE_ZERO=mdb_fanout_su_wakeup_valid" \
    "LINXCORE_REPLAY_LIQ_EARLY_STA_ADDRESS=1" \
  bash "${ROOT_DIR}/tools/chisel/run_chisel_frontend_fetch_rf_alu_qemu_elf_xcheck.sh" \
    --build-dir "${REPLAY_BUILD_DIR}" \
    --fixture replay-ldi-sdi-ldi-loop \
    --capture-rows "${REPLAY_CAPTURE_ROWS}" \
    --reduced-store-replay-liq \
    --disable-store-memory-mutation \
    --expect-load-pcs 0x10002,0x1000a,0x10002,0x1000a,0x10002,0x1000a \
    --expect-store-pcs 0x10006,0x10006,0x10006 \
    --max-seconds "${REPLAY_MAX_SECONDS}"

python3 - \
  "${ROOT_DIR}" \
  "${BUILD_DIR}" \
  "${COREMARK_BUILD_DIR}" \
  "${REPLAY_BUILD_DIR}" \
  "${COREMARK_ELF}" \
  "${COREMARK_ROWS}" \
  "${REPLAY_CAPTURE_ROWS}" \
  "${ALLOW_NATURAL_COREMARK_REPLAY}" \
  "${RUN_FOCUSED}" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

root_dir = Path(sys.argv[1])
build_dir = Path(sys.argv[2])
coremark_dir = Path(sys.argv[3])
replay_dir = Path(sys.argv[4])
coremark_elf = sys.argv[5]
coremark_rows = int(sys.argv[6])
replay_rows = int(sys.argv[7])
allow_natural = sys.argv[8] == "1"
run_focused = sys.argv[9] == "1"


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def git_sha(path: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except subprocess.CalledProcessError:
        return "unknown"


def sideband_summary(path: Path) -> dict:
    stats = load_json(path)
    replay = stats.get("replay_liq", {})
    keys = [
        "cycles_sampled",
        "wait_replay_capture_accepted",
        "replay_queue_out_fire",
        "liq_alloc_accepted",
        "liq_base_lookup_granted",
        "resolve_queue_push_accepted",
        "mdb_conflict_valid",
        "mdb_fanout_record_processed",
        "mdb_fanout_lookup_table_hit",
        "mdb_lookup_wait_plan_request_valid",
        "liq_replay_wake_wait_store_clear",
    ]
    return {key: replay.get(key) for key in keys}


coremark_manifest = load_json(coremark_dir / "report" / "crosscheck_manifest.json")
replay_manifest = load_json(replay_dir / "report" / "crosscheck_manifest.json")
manifest = {
    "schema": "linxcore.replay_liq_coremark_context_pack.v1",
    "status": "pass",
    "evidence_kind": "coremark_context_pack_plus_constructed_replay_liq",
    "claim_boundary": (
        "CoreMark prefix is a reduced-store replay-LIQ no-regression gate; "
        "the replay-LIQ/MDB positive path is forced by the constructed replay loop."
    ),
    "natural_coremark_replay_allowed": allow_natural,
    "focused_specs_run": run_focused,
    "coremark_prefix": {
        "elf": coremark_elf,
        "capture_rows": coremark_rows,
        "manifest": str(coremark_dir / "report" / "crosscheck_manifest.json"),
        "status": coremark_manifest.get("status"),
        "summary": coremark_manifest.get("summary", {}),
        "sideband": sideband_summary(coremark_dir / "report" / "frontend_fetch_rf_alu_sideband_stats.json"),
    },
    "constructed_replay_loop": {
        "fixture": "replay-ldi-sdi-ldi-loop",
        "capture_rows": replay_rows,
        "manifest": str(replay_dir / "report" / "crosscheck_manifest.json"),
        "status": replay_manifest.get("status"),
        "summary": replay_manifest.get("summary", {}),
        "sideband": sideband_summary(replay_dir / "report" / "frontend_fetch_rf_alu_sideband_stats.json"),
    },
    "git": {
        "linxcore": git_sha(root_dir),
    },
}
out = build_dir / "report" / "replay_liq_coremark_context_pack.json"
out.parent.mkdir(parents=True, exist_ok=True)
with out.open("w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, sort_keys=True)
    f.write("\n")
print(f"replay-liq-coremark-context-pack-report={out}")
PY
