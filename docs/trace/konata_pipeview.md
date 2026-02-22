# Deprecated: Konata Pipeview

Konata is no longer part of the active LinxCore trace workflow.

Use LinxTrace v1 + LinxCoreSight:

```bash
bash /Users/zhoubot/LinxCore/tools/linxcoresight/run_linxtrace.sh <program.memh> [max_commits]
python3 /Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py <trace.linxtrace.jsonl>
bash /Users/zhoubot/LinxCore/tools/linxcoresight/open_linxcoresight.sh <trace.linxtrace.jsonl>
```

Contracts:

- `/Users/zhoubot/LinxCore/docs/trace/linxtrace_v1.md`
- `/Users/zhoubot/LinxCore/docs/trace/linxtrace_pipeline_refresh_rule.md`
