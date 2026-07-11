# LinxCore Tests

- `test_runner_protocol.sh`
- Purpose: lockstep runner protocol check (start/commit/end), ack `ok`, and forced-mismatch fail-fast.
- `test_trace_schema_and_mem.sh`
- Purpose: short DUT run checks commit JSONL schema fields and observes both load/store commits.
- `test_rob_bookkeeping.sh`
- Purpose: checks commit-slot ROB ordering within multi-commit cycles and basic mem metadata invariants.
- `test_cosim_smoke.sh`
- Purpose: QEMU -> runner -> DUT co-sim smoke (`trigger_pc == boot_pc`) plus forced-mismatch negative path.
- `test_stage_connectivity.sh`
- Purpose: stage naming lint, no-stub lint, and top-level stage-prefix connectivity checks.
- `test_microarchitecture_contract.sh`
- Purpose: validates the golden contract index, mechanism intake coverage,
  dual-RTL ownership, top-shell roles, scenario coverage, and removal of live
  migration inputs; its fixtures also prove additive IFU/cache family extension
  without changing existing contract identities.
- `test_pycircuit_architecture_adapter.sh`
- Purpose: validates pyCircuit top roles, parameter defaults, state-owner
  evidence, promotion claims, declared gaps, and rejection of ARM-specific
  architectural behavior.
- `test_chisel_architecture_adapter.sh`
- Purpose: validates Chisel reduced-top role safety, parameter defaults,
  focused module evidence, promotion limitations, and Linx-specific BID gaps.
- `test_microarchitecture_conformance.sh`
- Purpose: validates shared scenario coverage, normalized owner events,
  architectural commit fields, and pyCircuit/Chisel differential mismatch
  detection without treating harness fixtures as RTL promotion evidence.
- `test_ooo_promotion.sh`
- Purpose: runs the pyCircuit scalar MapQ C++/Verilator flow and Chisel
  per-STID BID ring-order suite used for focused OOO promotion.
- `test_lsu_promotion.sh`
- Purpose: runs the pyCircuit SCB C++/Verilator flow and focused Chisel
  forwarding, replay, STQ/SCB, and MDB suites used for LSU promotion.
- `test_opcode_parity.sh`
- Purpose: regenerate opcode catalog/tables and enforce QEMU↔LinxCore decode metadata parity.
- `test_konata_sanity.sh` (legacy script name; LinxTrace flow)
- Purpose: generate LinxTrace from DUT, verify strict schema/stage checks, and basic ROB-vs-commit consistency.
- `test_konata_dfx_pipeview.sh` (legacy script name; LinxTrace flow)
- Purpose: DFX-only LinxTrace smoke on a short suite workload with stage-presence checks.
- `test_konata_template_pipeview.sh` (legacy script name; LinxTrace flow)
- Purpose: DFX-only LinxTrace run on a template-containing suite workload and enforce template-uop visibility.
- `test_cpp_codegen_safety_gate.sh`
- Purpose: strict C++ codegen safety gate for reg-primitive bindings (constructor/tick invariants + no-crash ctor-eval smoke).
- `test_coremark_crosscheck_1000.sh`
- Purpose: run real CoreMark for 1000 commits, compare QEMU vs LinxCore commit-by-commit, and require zero mismatch.
- `test_cbstop_inflation_guard.sh`
- Purpose: guard against early-window `C.BSTOP` inflation by comparing QEMU and LinxCore first-1000 commit histograms.
- `test_primitives_pyc_flow.sh`
- Purpose: pyCircuit leaf-module build/sim flow for S1 primitives (`mem2r1w`, `uid_allocator`).
- `test_frontend_pyc_flow.sh`
- Purpose: pyCircuit leaf+cluster flow for S2 frontend (`ifetch`, `bpu`, `ibuffer`, `ftq`, `frontend`).
- `test_pyc4_frontend_strict.sh`
- Purpose: enforce pyc4 frontend API hygiene and block new uses of legacy internal frontend tokens (`m.const`, `._select_internal`, `.__eq__`, `._trunc/_zext/_sext`) via baseline burn-down.
- `test_ooo_recovery_producer_pyc_flow.sh`
- Purpose: pyCircuit OOO retained recovery producer packet flow; verifies full 64-bit BID/restart TPC, STID/owner identity gating, backpressure stability, and rejection of ARM-specific semantics.
- `test_ooo_recovery_class_merge_pyc_flow.sh`
- Purpose: generated pyCircuit C++/Verilator proof of parameterized per-STID global recovery classes, per-PE lanes, model cancellation/merge rules, and irrevocable output behavior.
- `test_ooo_recovery_class_merge_cross_rtl.sh`
- Purpose: requires Chisel and pyCircuit generated RTL to declare and pass the same named two-STID/two-PE recovery-class scenario set.

Run all:

```bash
bash /Users/zhoubot/LinxCore/tests/test_runner_protocol.sh
bash /Users/zhoubot/LinxCore/tests/test_trace_schema_and_mem.sh
bash /Users/zhoubot/LinxCore/tests/test_rob_bookkeeping.sh
bash /Users/zhoubot/LinxCore/tests/test_cosim_smoke.sh
bash /Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh
bash /Users/zhoubot/LinxCore/tests/test_microarchitecture_contract.sh
bash /Users/zhoubot/LinxCore/tests/test_pycircuit_architecture_adapter.sh
bash /Users/zhoubot/LinxCore/tests/test_chisel_architecture_adapter.sh
bash /Users/zhoubot/LinxCore/tests/test_microarchitecture_conformance.sh
bash /Users/zhoubot/LinxCore/tests/test_ooo_promotion.sh
bash /Users/zhoubot/LinxCore/tests/test_lsu_promotion.sh
bash /Users/zhoubot/LinxCore/tests/test_opcode_parity.sh
bash /Users/zhoubot/LinxCore/tests/test_konata_sanity.sh
bash /Users/zhoubot/LinxCore/tests/test_konata_dfx_pipeview.sh
bash /Users/zhoubot/LinxCore/tests/test_konata_template_pipeview.sh
bash /Users/zhoubot/LinxCore/tests/test_cpp_codegen_safety_gate.sh
bash /Users/zhoubot/LinxCore/tests/test_coremark_crosscheck_1000.sh
bash /Users/zhoubot/LinxCore/tests/test_cbstop_inflation_guard.sh
bash /Users/zhoubot/LinxCore/tests/test_primitives_pyc_flow.sh
bash /Users/zhoubot/LinxCore/tests/test_frontend_pyc_flow.sh
bash /Users/zhoubot/LinxCore/tests/test_pyc4_frontend_strict.sh
bash /Users/zhoubot/LinxCore/tests/test_ooo_recovery_producer_pyc_flow.sh
bash /Users/zhoubot/LinxCore/tests/test_ooo_recovery_class_merge_pyc_flow.sh
bash /Users/zhoubot/LinxCore/tests/test_ooo_recovery_class_merge_cross_rtl.sh
```
