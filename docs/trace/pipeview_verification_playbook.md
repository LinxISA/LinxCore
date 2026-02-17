# Pipeview Verification Playbook

## Goal

Validate that Konata reflects exact cycle-stage residency for dynamic uops.

## Fast check (CoreMark, 1000 commits)

```bash
PYC_MAX_COMMITS=1000 \
PYC_KONATA=1 \
PYC_KONATA_SYNTHETIC=0 \
bash /Users/zhoubot/LinxCore/tools/image/run_linxcore_benchmarks.sh
```

## Required visual checks

1. A uop appears at `F0` then `F1` and progresses to `ROB/CMT`.
2. Long waits must show sustained `IQ` residency before `P1`.
3. Flushes must show `FLS` on the same uop line (no duplicate synthetic lines).
4. Template expansion (`FENTRY/FEXIT/FRET.*`) must produce multiple uop lines.

## Automated checks

```bash
python3 /Users/zhoubot/LinxCore/tools/konata/check_konata_stages.py \
  /Users/zhoubot/LinxCore/generated/konata/coremark/coremark.konata \
  --require-stages F0,F1,D3,IQ,P1,ROB,CMT,FLS
```
