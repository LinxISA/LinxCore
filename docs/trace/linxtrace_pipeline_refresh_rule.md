# LinxTrace Pipeline Refresh Rule (Strict)

This repository enforces a hard synchronization contract between LinxCore and LinxCoreSight.

## Rule

Any pipeline-stage change (add/remove/rename/reorder) must update all of:

- `/Users/zhoubot/LinxCore/src/common/stage_tokens.py`
- `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp`
- `/Users/zhoubot/LinxCore/tools/trace/build_linxtrace_view.py`
- `/Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py`
- `/Users/zhoubot/LinxCore/tools/linxcoresight/lint_trace_contract_sync.py`
- `/Users/zhoubot/LinxCoreSight/src/lib/linxtrace.ts`
- `/Users/zhoubot/LinxCoreSight/src/components/LinxTraceViewer.tsx`

## Mandatory metadata in every trace

Every run must emit:

- `*.linxtrace.jsonl`
- `*.linxtrace.meta.json`

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
python3 /Users/zhoubot/LinxCore/tools/linxcoresight/lint_trace_contract_sync.py
python3 /Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py <trace.linxtrace.jsonl>
```

Both must pass before merge.

