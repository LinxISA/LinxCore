# Pipeview Verification Playbook

## Goal

Validate that LinxCoreSight reflects exact cycle-stage residency for dynamic uops.

## Fast check (CoreMark, 1000 commits)

```bash
PYC_MAX_COMMITS=1000 \
bash /Users/zhoubot/LinxCore/tools/linxcoresight/run_linxtrace.sh \
  /Users/zhoubot/LinxCore/tests/benchmarks/build/coremark_real.memh 1000
```

## Required visual checks

1. A uop appears at `F0` then `F1` and progresses to `ROB/CMT`.
2. Long waits must show sustained `IQ` residency before `P1`.
3. Flushes must show `FLS` on the same uop line (no duplicate synthetic lines).
4. Template expansion (`FENTRY/FEXIT/FRET.*`) must produce multiple uop lines.

## Automated checks

```bash
python3 /Users/zhoubot/LinxCore/tools/linxcoresight/lint_linxtrace.py \
  /Users/zhoubot/LinxCore/generated/linxtrace/coremark/coremark_real_1000insn.linxtrace.jsonl \
  --meta /Users/zhoubot/LinxCore/generated/linxtrace/coremark/coremark_real_1000insn.linxtrace.meta.json \
  --require-stages F0,F1,D3,IQ,P1,ROB,CMT,FLS
```
