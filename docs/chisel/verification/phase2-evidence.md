# Phase 2 Evidence

Date: 2026-06-28

Scope:

- Added `F4DecodeWindow` as the first frontend/decode Chisel module.
- Captured the LinxCoreModel `CheckMInstSize` length rule for 16/32/48/64-bit
  instruction sizing.
- Updated shared Chisel instruction-length fields from 3 bits to 4 bits so
  later decoded uops and commit trace rows can represent 8-byte instructions.
- Documented the F4 interface, purpose, logic, flush behavior, and verification
  obligations in `docs/chisel/modules/frontend/F4DecodeWindow.md`.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only CommitTrace
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
python3 tools/chisel/trace_schema_adapter.py --self-test
bash tools/chisel/run_chisel_qemu_crosscheck.sh --dry-run
bash tools/chisel/run_chisel_reduced_rob_xcheck.sh
bash tools/chisel/run_chisel_top_xcheck.sh
bash tools/chisel/run_chisel_verilator_lint.sh
```

Observed result:

- `build_chisel.sh` passed under Chisel 7.3.0.
- `InterfaceBundlesSpec` ran 6 tests; all passed with 4-bit instruction length
  fields.
- `CommitTraceSpec` and `CommitTraceMonitorSpec` ran 10 tests; all passed with
  4-bit commit trace length fields.
- `F4DecodeWindowSpec` ran 9 tests; all passed.
- The focused test covers the model low-bit length rule, four 16-bit slots,
  mixed 32/16-bit slots, 48/16-bit slots, single 64-bit instruction windows,
  truncated candidate rejection, flush masking, slot field widths, and Chisel
  elaboration.
- `trace_schema_adapter.py --self-test` passed.
- `run_chisel_qemu_crosscheck.sh --dry-run` selected
  `/Users/zhoubot/linx-isa/emulator/qemu/build-linx/qemu-system-linx64` and
  passed the adapter self-test.
- `run_chisel_reduced_rob_xcheck.sh` built the reduced ROB Verilator harness
  and compared three normalized rows with zero mismatches.
- `run_chisel_top_xcheck.sh` built the top-level Verilator harness and compared
  three normalized rows with zero mismatches.
- `run_chisel_verilator_lint.sh` passed over the emitted top-level
  SystemVerilog set.

Known limitations:

- `F4DecodeWindow` is combinational only. F0/F1/F2/F3 fetch ownership, IB queue
  residency, registered F4/D1 transport, and D1/D2 opcode decode are still
  future modules.
- Full opcode decode and macro-boundary standalone behavior are deferred until
  the Chisel opcode table exists.

Skill evolve:

- `skill-evolve: update linx-core` because `F4DecodeWindow` is a reusable Phase
  2 frontend gate and it locks a model-derived 8-byte instruction length rule
  that downstream decode and trace agents must preserve.
