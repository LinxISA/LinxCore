# LinxTrace Flow Contract

This document defines the strict integration contract between LinxCore trace emission and LinxCoreSight rendering.

## Contract Scope

The following paths are contract-coupled and must be updated together:

- `src/common/stage_tokens.py`
- `tb/tb_linxcore_top.cpp`
- `tools/trace/build_linxtrace_view.py`
- `tools/linxcoresight/lint_linxtrace.py`
- `tools/linxcoresight/lint_trace_contract_sync.py`
- `/Users/zhoubot/LinxCoreSight/src/lib/linxtrace.ts`
- `/Users/zhoubot/LinxCoreSight/src/workers/traceIndex.worker.ts`
- `/Users/zhoubot/LinxCoreSight/src/components/trace/TraceCanvasView.tsx`
- `/Users/zhoubot/LinxCoreSight/src/styles/traceThemes.ts`

## LinxTrace v1 Row Identity

Each row has:

- `row_id`: dense integer runtime key
- `row_sid`: semantic ID (`c<core>.blk.<block_uid>` / `c<core>.uop.<uid>` / `c<core>.gen.<parent>.<uid>`)
- `entity_kind`: block/uop lineage class
- `lifecycle_flags`: retired/flushed/trapped/resolved/fallback_closed/inflight
- `order_key`: stable deterministic row ordering key
- `id_refs`: `{seq,uop_uid,block_uid,block_bid}`

Viewer logic must use metadata row order and not infer row identity from `seq` or `uid` heuristics.

## Event Requirements

- `OCC`: must include `row_id,row_sid,cycle,stage_id,lane_id,stall,cause`
- `RETIRE`: must include `row_id,row_sid,cycle,status`
- `BLOCK_EVT`: must include `kind,block_uid,block_bid,seq,cycle,pc`

## Failure Modes (and strict response)

- Missing/unknown stage token: fail load with explicit message.
- Missing row lifecycle terminal: fail lint/build.
- Empty drawable OCC in viewport: show hard overlay error (no silent blank panel).
- Contract drift (`contract_id` mismatch): fail fast in linter and viewer.

## Required Debug Workflow

1. Build trace:
   - `bash tools/linxcoresight/run_linxtrace.sh <program.memh> [max_commits]`
2. Lint trace:
   - `python3 tools/linxcoresight/lint_linxtrace.py <trace> --single-stage-per-cycle`
3. Viewer diagnostics:
   - `npm run trace:lint -- <trace>`
   - `npm run linxtrace:render-check -- <trace>`
   - `npm run linxtrace:first-failure -- <trace>`
4. UI snapshot mode when needed:
   - `LCS_UI_SNAPSHOT=1 /Applications/LinxCoreSight.app/Contents/MacOS/LinxCoreSight <trace>`
