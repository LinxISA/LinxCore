# LinxCore Engineering Code Of Conduct

This repository follows strict architecture-ownership and bring-up rules.

## 1) Stage Ownership Is Mandatory

- Logic must live in the owning stage/component file.
- Cross-stage glue is allowed in top-level composition files only.
- Files named as compatibility wrappers must not become logic sinks.

## 2) `engine.py` Must Stay Orchestration-Only

- `src/bcc/backend/engine.py` may compose and route sub-engines.
- Stage-owned state machines, decode specifics, LSU ordering, and block-fabric policy must be implemented in focused files.
- New functional features must not be added directly as monolithic blocks in `engine.py`.

## 3) Interface Naming Contract

- Inter-stage links use `producer_to_consumer_stage_<field>_<stage>`.
- Stage tokens must follow `src/common/stage_tokens.py`.
- Debug/DFX probe naming must stay stable (`dbg__occ_*`, `dbg__blk_evt_*`).

## 4) Parameterization First

- New microarchitecture features must expose scalar parameters at build entry points.
- Default values must keep current test/benchmark behavior deterministic.
- Parameter docs are required for new knobs.

## 5) Validation Discipline

- No behavioral change is complete without:
  - stage/connectivity checks,
  - trace schema/co-sim checks,
  - CoreMark and Dhrystone smoke.
- Konata stage visibility changes require lint updates and passing Konata checks.

## 6) No Silent Compatibility Breaks

- External DUT functional ports and required commit JSON fields are stable contracts.
- Additive debug fields are allowed.
- Incompatible changes require explicit migration docs and script updates in the same change.

## 7) LinxTrace Pipeline Contract Is Strict

- Any pipeline stage change (add/remove/rename/reorder) must refresh all of:
  - stage tokens (`src/common/stage_tokens.py`),
  - trace compiler (`tools/trace/build_linxtrace_view.py`),
  - trace linter (`tools/linxcoresight/lint_linxtrace.py`),
  - contract sync gate (`tools/linxcoresight/lint_trace_contract_sync.py`),
  - TB stage map (`tb/tb_linxcore_top.cpp`),
  - LinxCoreSight parser/renderer (`/Users/zhoubot/LinxCoreSight/src/lib/linxtrace.ts` and viewer).
- LinxTrace sidecar metadata is mandatory:
  - `format=linxtrace.v1`,
  - `contract_id`,
  - `stage_catalog`, `lane_catalog`, `row_catalog`.
- `tools/linxcoresight/lint_trace_contract_sync.py` is mandatory and must pass before merge.
- If contract sync fails, treat as a hard error; do not land partial emitter/viewer updates.
- Renderer asserts must never degrade into a silent blank pipeline pane:
  - in strict mode, LinxCoreSight must show an explicit on-canvas error banner with actionable reason.
