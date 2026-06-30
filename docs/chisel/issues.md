# Chisel Issues

## CHISEL-ISSUE-001: Local JVM/SBT Toolchain Missing

Status: closed

Impact:

- The initial Chisel compile, Scala test, and Verilog emit were blocked until a
  local JDK and `sbt` were installed.

Evidence:

```bash
bash tools/chisel/build_chisel.sh                 # pass
bash tools/chisel/run_chisel_tests.sh --only ROBID # pass
bash tools/chisel/emit_verilog.sh                 # pass
```

Resolution:

- Installed Homebrew `openjdk@17` and `sbt`.
- Added `tools/chisel/chisel_env.sh` so wrappers set `JAVA_HOME` to Homebrew
  `openjdk@17` when `JAVA_HOME` is unset.
- Updated the Chisel project to Scala `2.13.17` to match the resolved
  dependency graph.

## CHISEL-ISSUE-002: SBT Server Socket Race Under Parallel Invocations

Status: open

Impact:

- Running two SBT-backed wrappers at the same time can race the SBT 2 server
  socket and produce `Connection refused`.

Evidence:

- A parallel invocation of `run_chisel_tests.sh --only ROBID` and
  `run_chisel_rob_bookkeeping.sh --robid-only` produced an SBT client
  `Connection refused` error, while the same ROBID gate passed when rerun
  sequentially.
- A 2026-06-28 parallel invocation of `build_chisel.sh` and
  `run_chisel_tests.sh --only CommitTrace` reproduced the same SBT client
  `Connection refused` failure; `run_chisel_tests.sh --only CommitTrace` passed
  when rerun sequentially.

Current mitigation:

- Run SBT-backed Chisel gates sequentially.
- Wrappers use `--batch --no-colors` for CI-like output, but this does not
  prove parallel server use is safe.

## CHISEL-ISSUE-003: CSEL Model/QEMU Source-Order Divergence

Status: open

Impact:

- The R136 reduced CoreMark live fetch/RF/ALU gate passes through 1620 raw QEMU
  rows, then the 1660-row probe reaches `OP_CSEL` at `pc=0x40005d32`.
- LinxCoreModel and Sail select `SrcL` when `SrcP != 0`; the current QEMU
  translator selects `SrcR` when `SrcP != 0`.
- LinxCore RTL must not silently implement the QEMU behavior as architectural
  `CSEL` while the model and Sail disagree, because this lane is model-aligned.

Evidence:

- LinxCore `21c630e0bfb024446b0beb378eecfabbbbadbb7f`.
- Local LinxCoreModel `1993e4e749403824a4908548baf77d5e15117068`.
- Fetched `origin/SuperScalarModel` `704a779` preserves the same model
  `CSEL` true-to-`SrcL` behavior.
- Sail source:
  `isa/sail/model/execute/execute.sail:1118` documents
  `exec_csel` as `SrcP != 0` selecting `SrcL`.
- LinxCoreModel source:
  `model/LinxCoreModel/isa/calculate/compound/Compound.cpp:7` decodes
  `srcP = srcs[0]`, `srcL = srcs[1]`, `srcR = srcs[2]`, and writes `srcL`
  when `srcP != 0`.
- QEMU source:
  `emulator/qemu/target/linx/translate.c:3404` initializes the output from
  `SrcL`, then overwrites it with `SrcR` when the predicate is nonzero.
- Live QEMU row 1625 in the 1660-row probe distinguishes the behavior:
  immediately before the row, `srcp=x24/T0=1`, `SrcL=x2=0`, and
  `SrcR=x28/U0=0x40000768`; QEMU writes `U0=0x40000768`, which is
  true-to-`SrcR`.

Current mitigation:

- Keep reduced RTL `OP_CSEL` unsupported until the architecture/model/QEMU
  owner resolves the source-order contract.
- Future QEMU-lane work should either align QEMU with Sail/LinxCoreModel or
  record an explicit architecture decision that changes the model and Sail.
