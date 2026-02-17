# LinxCore Konata Pipeview (DFX v0005)

## Generate traces

```bash
bash /Users/zhoubot/LinxCore/tools/konata/run_konata_trace.sh <program.memh>
```

CoreMark/Dhrystone outputs are placed under:

- `/Users/zhoubot/LinxCore/generated/konata/coremark/`
- `/Users/zhoubot/LinxCore/generated/konata/dhrystone/`

## Source of truth

- Pipeline occupancy is emitted from pyCircuit debug probes (`dbg__occ_*`).
- TB emits raw JSONL events: `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp`
- Offline builder compiles raw events into Konata:
  - `/Users/zhoubot/LinxCore/tools/trace/build_konata_block_view.py`
- Top probe wiring: `/Users/zhoubot/LinxCore/src/top/top.py`
- UID allocator: `/Users/zhoubot/LinxCore/src/common/uid_allocator.py`

Konata trace format is `Kanata\t0005` (v0005-only flow, LinxCore-only parser path).
The final trace contains:
1. Block lifecycle rows (`kind=block`)
2. Dynamic uop rows (`kind=normal/flush/trap/replay/template_child`)

## Stage set

`F0,F1,F2,F3,F4,D1,D2,D3,IQ,S1,S2,P1,I1,I2,E1,E2,E3,E4,W1,W2,LIQ,LHQ,STQ,SCB,MDB,L1D,BISQ,BCTRL,TMU,TMA,CUBE,VEC,TAU,BROB,ROB,CMT,FLS`

Cross-check marker stage (only on mismatch):

`XCHK`

## Label format

Uop row left pane should show:

- `pc`
- objdump-style disassembly text (`pc: disassembly`)
- for generated template/replay uops:
  - pseudo assembly format: `u<seq-id>: <uop-asm>`
  - no `pc` prefix

Block row left pane should show:

- `BLOCK c<core> b<block_uid> BPC=<open_pc> TYPE=<block_type> BR=<branch_type>`
- template-form block rows use:
  - `BLOCK ... TYPE=TEMPLATE TDISASM=<template disassembly>`

Additional metadata is emitted via `L type=1` detail records (`uid/op/src0/src1/dst/wb/mem/trap` and xcheck deltas).
For v0005, repeated `L type=0` updates replace prior left-pane text (not append),
so runtime operand refinements (`op/src0/src1/dst`) remain tidy.

When `PYC_QEMU_TRACE` is enabled, mismatches append detail text and one-cycle `XCHK` stage marker on the retiring uop line.

## Quality checks

```bash
python3 /Users/zhoubot/LinxCore/tools/konata/check_konata_stages.py \
  /Users/zhoubot/LinxCore/generated/konata/coremark/<trace>.konata \
  --require-stages F0,F3,D1,D3,IQ,BROB,CMT \
  --single-stage-per-cycle
```

Strict lint behavior:

1. Fails on non-v0005 traces.
2. Fails on commands outside `Kanata/C/C=/I/L/P/R`.
3. Fails on post-retire commands, undefined IDs, invalid lane/stage tokens.
4. Fails when any row has no occupancy (`P`) records.

## CLI render debug

Use the CLI inspector when GUI rendering looks wrong:

```bash
bash /Users/zhoubot/LinxCore/tools/konata/konata_cli_debug.sh \
  /Users/zhoubot/LinxCore/generated/konata/coremark/<trace>.konata
```

It prints:
1. row counts with/without stage records
2. stage histogram
3. early row summaries (`kid/kind/label/stages`)
4. parser validation via `/Users/zhoubot/Konata/onikiri_parser.js`

For deeper Konata internals (drawable rows, stage-map consistency, lane distribution):

```bash
bash /Users/zhoubot/LinxCore/tools/konata/konata_internal_diag.sh \
  /Users/zhoubot/LinxCore/generated/konata/coremark/<trace>.konata --top 20
```

To ensure you open the rebuilt local Konata app (not system default), use:

```bash
bash /Users/zhoubot/LinxCore/tools/konata/open_konata_trace.sh \
  /Users/zhoubot/LinxCore/generated/konata/coremark/<trace>.konata
```

Install/update Konata app bundle:

```bash
bash /Users/zhoubot/LinxCore/tools/konata/install_konata_app.sh
```

`open_konata_trace.sh` prefers:
1. `KONATA_APP` (if set)
2. `/Applications/Konata.app`
3. `~/Applications/Konata.app`
4. local packaged app under `/Users/zhoubot/Konata/packaging-work/...`

Konata is built with software rendering enabled by default for stability.
To force GPU rendering (for A/B checks), launch with:

```bash
KONATA_FORCE_SOFTWARE_RENDER=0 \
open -a /Applications/Konata.app \
  /Users/zhoubot/LinxCore/generated/konata/coremark/<trace>.konata
```
