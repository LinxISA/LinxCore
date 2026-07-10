# LinxTrace Pipeline Refresh Rule (Strict)

Canonical contract summary:

- `rtl/LinxCore/docs/architecture/interfaces.md`

This repository enforces a hard synchronization contract between LinxCore and LinxCoreSight.

## Rule

Any pipeline-stage change (add/remove/rename/reorder) must update all of:

- `rtl/LinxCore/src/common/stage_tokens.py`
- `rtl/LinxCore/tb/tb_linxcore_top.cpp`
- `rtl/LinxCore/tools/trace/build_linxtrace_view.py`
- `rtl/LinxCore/tools/linxcoresight/lint_linxtrace.py`
- `rtl/LinxCore/tools/linxcoresight/lint_trace_contract_sync.py`
- `$LINXCORESIGHT_ROOT/src/lib/linxtrace.ts` in the named external
  LinxCoreSight checkout
- `$LINXCORESIGHT_ROOT/src/components/LinxTraceViewer.tsx`

## Mandatory metadata in every trace

Every run must emit:

- `*.linxtrace` (single file, with in-band `META` first record)

Metadata must include:

- `format=linxtrace.v1`
- `contract_id`
- `pipeline_schema_id`
- `stage_catalog`
- `lane_catalog`
- `row_catalog`

## Hard gate

Run:

```bash
python3 rtl/LinxCore/tools/linxcoresight/lint_trace_contract_sync.py
python3 rtl/LinxCore/tools/linxcoresight/lint_linxtrace.py <trace.linxtrace>
```

Both must pass before merge.
