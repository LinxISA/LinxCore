# Phase 0B Evidence

## 2026-06-28 Packet A

Scope:

- Added the initial Chisel lane skeleton.
- Added `ROBID` and `CommitIdentity` sources.
- Added `CommitTraceRow`, memory/trap envelopes, commit-window valid mask, and
  commit schema tests.
- Added initial `FlushControl` request, bus, classification, need-flush, and
  older-signal arbitration sources.
- Added initial `BID` helpers and `BROB` metadata tracker sources.
- Documented model-derived ROBID and CommitInfo contracts.
- Documented model-derived FlushControl contracts.
- Documented model-derived BROB/BID contracts.
- Added `tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only`.
- Added `tools/chisel/run_chisel_verilator_lint.sh`.
- Added `tools/chisel/trace_schema_adapter.py` and
  `tools/chisel/run_chisel_qemu_crosscheck.sh`.

Evidence:

```bash
bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only
bash tools/chisel/run_chisel_tests.sh --only CommitTrace
bash tools/chisel/run_chisel_tests.sh --only BROB
bash tools/chisel/run_chisel_tests.sh --only FlushControl
bash tools/chisel/run_chisel_verilator_lint.sh
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
```

Expected result:

- `ROBID semantic check: ok`
- Scala ROBID tests pass.
- Scala FlushControl tests pass.
- Scala BROB tests pass.

Observed result:

- `ROBID semantic check: ok`
- `bash tools/chisel/build_chisel.sh` passed.
- `bash tools/chisel/run_chisel_tests.sh --only BROB` passed 5 tests in
  `BROBSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only FlushControl` passed 6 tests in
  `FlushControlSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only ROBID` passed 3 tests in
  `ROBIDSpec`.
- `bash tools/chisel/run_chisel_tests.sh --only CommitTrace` passed 5 tests in
  `CommitTraceSpec`.
- `bash tools/chisel/run_chisel_rob_bookkeeping.sh --robid-only` passed the
  Python semantic check and the Scala ROBID test.
- `bash tools/chisel/emit_verilog.sh` emitted
  `generated/chisel-verilog/LinxCoreTop.sv`.
- `verilator --lint-only generated/chisel-verilog/LinxCoreTop.sv` passed.
- `python3 tools/chisel/trace_schema_adapter.py --self-test` passed.
- `bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run` selected
  `/Users/zhoubot/linx-isa/emulator/qemu/build-linx/qemu-system-linx64` and
  passed the adapter self-test.
- A synthetic one-row QEMU/DUT wrapper smoke normalized both traces and compared
  one commit with zero mismatches.

Skill evolve:

- `skill-evolve: update linx-core` because the initial Chisel gate sequence,
  Homebrew `openjdk@17`/`sbt` setup, Scala `2.13.17`, Verilator lint wrapper,
  QEMU adapter dry-run, and no-parallel-SBT rule are reusable LinxCore bring-up
  policy.
- `skill-evolve: update linx-core` for Packet B because the FlushControl gate
  and LinxCoreModel `CheckOlder` branch-order invariant are reusable by future
  recovery/BROB agents.
- `skill-evolve: update linx-core` for Packet C because the BROB/BID gate and
  full-BID flush/tag invariants are reusable by future BCTRL/BISQ agents.
- `skill-evolve: update linx-core` for the commit-trace schema because later
  agents must preserve `CommitInfo` 32-bit identity separately from the 64-bit
  hardware `block_bid` sideband and must filter invalid fixed-width slots before
  QEMU comparison.
