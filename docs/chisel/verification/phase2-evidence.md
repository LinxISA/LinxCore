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
- Added `FrontendInstructionBuffer`, an 8-entry packet FIFO for frontend-owned
  F3/F4 buffering.
- Stored checkpoint identity inside the buffered packet so future F4/D1/D2 work
  treats checkpoint as frontend packet state instead of reconstructing it from
  adjacent control wiring.
- Added `FrontendDecodeIngress`, a transport wrapper that composes
  `FrontendInstructionBuffer` and `F4DecodeWindow` without moving opcode decode
  or uop construction into frontend glue.
- Preserved the no same-cycle push-to-D1 bypass rule: pushed packets become
  visible only after FIFO residency, and pop requires `decodeReady &&
  f4.d1.valid`.

Evidence:

```bash
bash tools/chisel/build_chisel.sh
bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles
bash tools/chisel/run_chisel_tests.sh --only CommitTrace
bash tools/chisel/run_chisel_tests.sh --only F4DecodeWindow
bash tools/chisel/run_chisel_tests.sh --only FrontendInstructionBuffer
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeIngress
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
- `FrontendInstructionBufferSpec` ran 6 tests; all passed.
- The focused test covers FIFO ordering, packet identity retention,
  simultaneous push/pop count behavior, full backpressure, flush clearing and
  output masking, packet field widths, and Chisel elaboration.
- `FrontendDecodeIngressSpec` ran 7 tests; all passed.
- The focused test covers FIFO residency before visibility, decode-side hold
  and consume, F4 slot slicing with packet identity, flush clearing/masking,
  ingress backpressure, debug widths, and Chisel elaboration through the IB and
  F4 child modules.
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

- `F4DecodeWindow` is combinational only. F0/F1/F2/F3 fetch ownership,
  registered F4/D1 transport, and D1/D2 opcode decode are still future modules.
- `FrontendInstructionBuffer` is not yet wired into `LinxCoreTop` or a live
  frontend pipeline.
- `FrontendDecodeIngress` is not yet wired into `LinxCoreTop` or a live
  frontend pipeline.
- Full opcode decode and macro-boundary standalone behavior are deferred until
  the Chisel opcode table exists.

Skill evolve:

- `skill-evolve: update linx-core` because `F4DecodeWindow` is a reusable Phase
  2 frontend gate and it locks a model-derived 8-byte instruction length rule
  that downstream decode and trace agents must preserve.
- `skill-evolve: update linx-core` because `FrontendInstructionBuffer` is a
  reusable Phase 2 frontend FIFO gate and records that Chisel buffers carry
  checkpoint identity as packet-owned state.
- `skill-evolve: update linx-core` because `FrontendDecodeIngress` is a
  reusable Phase 2 frontend transport gate and records the IB-to-F4 pop,
  flush, and no-bypass composition rules.
