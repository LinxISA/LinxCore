# LinxCore Microarchitecture Remediation Plan

Date: 2026-03-11

Companion audit:

- [linxcore_microarch_conformance_audit_20260311.md](/Users/zhoubot/linx-isa/rtl/LinxCore/docs/cosim/linxcore_microarch_conformance_audit_20260311.md)

## Goal

Close the highest-risk contract failures first, in the order required to restore architectural correctness before spending time on decomposition cleanup or publication sync.

Priority order:

1. Restore `BSTART` / `BSTOP` / BID / flush correctness.
2. Restore IQ residency and hazard semantics.
3. Remove parent-level trace reconstruction from the canonical backend path.
4. Repair contract publication sync.
5. Reduce pyCircuit hierarchy pressure enough for required structural gates to run.

## Phase 0: Freeze the failing witnesses

Purpose:

- Keep one short, deterministic reproducer per failure class so fixes can be judged against a stable baseline.

Required witnesses:

1. Standalone no-stub CoreMark failfast:

```bash
PYC_BOOT_PC=0x12490 \
PYC_BOOT_SP=0x0000000007fefff0 \
PYC_MAX_COMMITS=20 \
PYC_QEMU_TRACE=/tmp/linxcore_fifo_diag_1773234155/qemu_trace.jsonl \
PYC_IFU_STUB_QEMU=0 \
PYC_XCHECK_MODE=failfast \
PYC_XCHECK_MAX_COMMITS=20 \
PYC_SKIP_TRACE_TEXT=1 \
PYC_COMMIT_TRACE=/tmp/linxcore_audit_nostub.jsonl \
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tools/generate/run_linxcore_top_cpp.sh \
  /Users/zhoubot/LinxCore/tests/benchmarks_latest_llvm_musl_1000/memh/coremark_latest_llvm_musl.memh
```

2. Block structure pyCircuit flow:

```bash
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_block_struct_pyc_flow.sh
```

3. Stage ownership lint:

```bash
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_stage_connectivity.sh
```

Exit condition:

- These commands are retained as the regression anchors for all following phases.

## Phase 1: Fix block/BID/flush correctness

Priority: `P0`

Why first:

- This is the only runtime-confirmed architectural divergence in the audit and it breaks `LC-MA-BLK-001` directly.

Target files:

- [trace_export_core.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/modules/trace_export_core.py)
- [commit_slot_step.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/modules/commit_slot_step.py)
- [engine.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/engine.py)
- if required by ownership of block state:
  - [top.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/top/top.py)
  - [export_core.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/top/modules/export_core.py)

Work items:

1. Make `BSTART` carry the newly allocated BID on the retiring ROB-visible row, not the old live BID.
2. Audit the full active-block update path:
   - dispatch-time BID assignment
   - ROB-visible BID for `BSTART`
   - active block advance on boundary retirement
   - flush/redirect BID selection
3. Confirm every BID-carrying structure uses `kill bid > flush_bid`.
4. Re-run the standalone no-stub lane after each BID-domain change.
5. Keep `cmd_tag == bid[7:0]` intact through the block fabric.

Acceptance criteria:

- The standalone no-stub failfast no longer diverges at `seq=8`.
- `0x124A6/0x124A8` do not survive the `_start -> _start_c` handoff.
- Debug shows `active_block_bid` and `flush_bid` advancing out of zero in the call/BSTOP window.
- `bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_cbstop_inflation_guard.sh` passes.

Stop condition:

- Do not move to Phase 2 while the standalone no-stub block-domain witness still fails.

## Phase 2: Fix IQ residency and hazard semantics

Priority: `P1`

Why second:

- The current IQ/issue path is statically non-conformant even if the block-domain bug is repaired.

Target files:

- [iq_bank.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/modules/iq_bank.py)
- [issue.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/issue.py)
- any supporting ready/wakeup owners under `src/bcc/backend/`
- LSU owners if the load-spec path is wired there

Work items:

1. Add explicit per-entry `inflight` state.
2. Change deallocation from `issue_fire` to `I2` confirmation.
3. Split non-spec ready-table state from IQ-local speculative-ready state.
4. Add the architectural `is_spec` merge rule.
5. Add `ld_gen_vec` propagation and `miss_pending` suppression for the LSU-dependent issue path.
6. Ensure wakeup at cycle `N` is only visible to pick at `N+1`.

Acceptance criteria:

- No IQ entry clears on pick alone.
- Pick excludes `inflight` entries.
- A cancelled issue attempt clears `inflight` without dropping `valid`.
- Static review shows `ld_gen_vec` and `miss_pending` exist on the live path.
- Existing stage/connectivity lint still passes.

Recommended validation additions:

- Add a focused IQ self-check or testbench that proves:
  - no same-cycle wake-to-pick
  - dealloc at `I2`
  - load miss suppression on `LD_E4`

## Phase 3: Converge trace ownership with the spec

Priority: `P2`

Why third:

- Trace ownership is important, but architectural correctness must be restored first.

Target files:

- [trace_export_core.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/modules/trace_export_core.py)
- [commit_trace_stage.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/modules/commit_trace_stage.py)
- [macro_trace_prep_stage.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/modules/macro_trace_prep_stage.py)
- [engine.py](/Users/zhoubot/linx-isa/rtl/LinxCore/src/bcc/backend/engine.py)

Work items:

1. Remove synthetic architectural row reconstruction from the monolithic wrapper where dedicated owner stages already exist.
2. Prefer the `engine.py -> macro_trace_prep_stage -> commit_trace_stage` path as the canonical commit/trace owner chain.
3. Keep trace-only shaping out of functional control logic.
4. Preserve the external commit payload contract while moving ownership to the correct modules.

Acceptance criteria:

- Trace rows come from dedicated owner-stage state, not parent-level reconstruction.
- `bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_trace_schema_and_mem.sh` passes.
- `python3 /Users/zhoubot/linx-isa/rtl/LinxCore/tools/linxcoresight/lint_trace_contract_sync.py` passes.

## Phase 4: Repair doc and publication sync

Priority: `P3`

Why fourth:

- Publication drift is real, but it does not block root-cause debugging of the functional failures.

Target files:

- canonical docs under `rtl/LinxCore/docs/architecture/*`
- published mirrors under `/Users/zhoubot/linx-isa/docs/architecture/linxcore/*`

Work items:

1. Regenerate or manually sync the published LinxCore mirror pages.
2. Re-run the strict architecture contract check.
3. Keep the canonical submodule docs as the source of truth; do not patch the mirror only.

Acceptance criteria:

- `python3 /Users/zhoubot/linx-isa/tools/bringup/check_linxcore_arch_contract.py --root /Users/zhoubot/linx-isa --strict` passes.

## Phase 5: Reduce pyCircuit compile-budget pressure on structural flows

Priority: `P4`

Why last:

- The current `PYC991` failure blocks one required structural gate, but the functional block-domain repair is still the primary closure item.

Target files:

- whichever owner currently causes the `TbBlockStructRob.cpp` hotspot
- likely backend structural owners, not just tests

Work items:

1. Identify the widest surviving parent/child boundary in the emitted ROB structural flow.
2. Pack exported query state instead of fanning out per-entry scalar ports.
3. Keep scans inside the owning module and export compact results.
4. Use recursive bank/lane slices if the parent eval shard is still too wide.

Acceptance criteria:

- `bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_block_struct_pyc_flow.sh` passes fully.
- No emitted TU exceeds the current hard budget.

## Closure gates

Do not declare closure until these pass:

```bash
cmake -S /Users/zhoubot/linx-isa/rtl/LinxCore -B /Users/zhoubot/linx-isa/rtl/LinxCore/build
cmake --build /Users/zhoubot/linx-isa/rtl/LinxCore/build -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_stage_connectivity.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_runner_protocol.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_cosim_smoke.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_opcode_parity.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_trace_schema_and_mem.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_rob_bookkeeping.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_block_struct_pyc_flow.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_coremark_crosscheck_1000.sh
bash /Users/zhoubot/linx-isa/rtl/LinxCore/tests/test_cbstop_inflation_guard.sh
```

Current known caveat:

- `test_rob_bookkeeping.sh` is not presently actionable in this checkout because it fails with `fallback benchmark memh not found`. That gap should be fixed before using the full closure list as a signoff decision.

## Recommended immediate next move

Start Phase 1 in the active backend path with one narrow objective:

- make the retiring `BSTART` row carry the new BID,
- then trace the single `_start -> _start_c` witness until `active_block_bid` and `flush_bid` advance correctly through the `0x1249C -> 0x124A4 -> 0x124AA` handoff.

That is the shortest path to converting the audit from diagnosis into a real architectural repair.

skill-evolve: no-update
