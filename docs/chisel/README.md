# LinxCore Chisel RTL Lane

This directory is the authoring home for the Chisel replacement lane. The lane
is developed beside the current pyCircuit implementation until Chisel reaches
equivalent module, trace, QEMU, and LinxCoreModel evidence.

Current phase:

- Phase 0: build skeleton
- Phase 0A: model notes
- Phase 0B: ROB and cross-check infrastructure first
- Phase 1: interface schema and type-system monitors in progress
- Phase 2: frontend F4 decode-window and instruction-buffer slicing started
- Phase 1 top shell: `LinxCoreTop` wraps the monitored reduced ROB so top
  emit/lint uses real commit structure before the full frontend/backend exists

The first implementation packets are ROBID, commit identity, the initial
FlushControl arbitration primitive, BROB/BID metadata, and the shared Phase 1
common interface bundles. They are derived from
`model/LinxCoreModel/model/ModelCommon/ROBID.*`,
`model/LinxCoreModel/model/interface/CommitInfo.h`, and
`model/LinxCoreModel/model/core/FlushControl.*`,
`model/LinxCoreModel/model/bctrl/BROB.*`, and the C++ model bus headers under
`model/LinxCoreModel/model/ModelCommon/bus/`. The first Phase 2 frontend slice
also follows the LinxCoreModel `CheckMInstSize` instruction-length rule from
`model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h` and the F4/F5/instBuffer
queueing flow in `model/LinxCoreModel/model/pe/ifu/iside/pe_ifu.cpp`.

The current `LinxCoreTop` is a reduced bring-up shell, not the final core. It
forwards a monitored `ReducedCommitROB` so top-level generated RTL carries the
same commit-window contract used by the reduced QEMU cross-check harness.

Open setup issues are tracked in `docs/chisel/issues.md`. The multi-agent
development loop and skill-evolve closeout rules are captured in
`docs/chisel/agent-loop.md`.

Commands from `rtl/LinxCore`:

```bash
bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendInstructionBuffer
bash tools/chisel/run_chisel_tests.sh --only CommitTraceMonitor
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_tests.sh
bash tools/chisel/emit_verilog.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

`build_chisel.sh`, `run_chisel_tests.sh`, and `emit_verilog.sh` require a local
JDK plus `sbt`. The wrappers prefer Homebrew `openjdk@17` when `JAVA_HOME` is
not set. The ROBID-only gate always runs the model-derived semantic checks and
runs the Scala test when the toolchain exists.
