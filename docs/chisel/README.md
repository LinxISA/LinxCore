# LinxCore Chisel RTL Lane

This directory is the authoring home for the Chisel replacement lane. The lane
is developed beside the current pyCircuit implementation until Chisel reaches
equivalent module, trace, QEMU, and LinxCoreModel evidence.

Current phase:

- Phase 0: build skeleton
- Phase 0A: model notes
- Phase 0B: ROB and cross-check infrastructure first

The first implementation packets are ROBID, commit identity, the initial
FlushControl arbitration primitive, and BROB/BID metadata. They are derived from
`model/LinxCoreModel/model/ModelCommon/ROBID.*`,
`model/LinxCoreModel/model/interface/CommitInfo.h`, and
`model/LinxCoreModel/model/core/FlushControl.*`, and
`model/LinxCoreModel/model/bctrl/BROB.*`.

Open setup issues are tracked in `docs/chisel/issues.md`.

Commands from `rtl/LinxCore`:

```bash
bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_tests.sh
bash tools/chisel/emit_verilog.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

`build_chisel.sh`, `run_chisel_tests.sh`, and `emit_verilog.sh` require a local
JDK plus `sbt`. The wrappers prefer Homebrew `openjdk@17` when `JAVA_HOME` is
not set. The ROBID-only gate always runs the model-derived semantic checks and
runs the Scala test when the toolchain exists.
