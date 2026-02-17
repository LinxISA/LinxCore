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
- `test_opcode_parity.sh`
- Purpose: regenerate opcode catalog/tables and enforce QEMU↔LinxCore decode metadata parity.
- `test_konata_sanity.sh`
- Purpose: generate Konata trace from DUT, verify stage visibility/label format, and basic ROB-vs-commit consistency.
- `test_konata_dfx_pipeview.sh`
- Purpose: DFX-only Konata smoke on a short suite workload with stage-presence checks.
- `test_konata_template_pipeview.sh`
- Purpose: DFX-only Konata run on a template-containing suite workload and enforce template-uop visibility (`--require-template`).
- `test_cpp_codegen_safety_gate.sh`
- Purpose: strict C++ codegen safety gate for reg-primitive bindings (constructor/tick invariants + no-crash ctor-eval smoke).
- `test_coremark_crosscheck_1000.sh`
- Purpose: run real CoreMark for 1000 commits, compare QEMU vs LinxCore commit-by-commit, and require zero mismatch.
- `test_cbstop_inflation_guard.sh`
- Purpose: guard against early-window `C.BSTOP` inflation by comparing QEMU and LinxCore first-1000 commit histograms.

Run all:

```bash
bash /Users/zhoubot/LinxCore/tests/test_runner_protocol.sh
bash /Users/zhoubot/LinxCore/tests/test_trace_schema_and_mem.sh
bash /Users/zhoubot/LinxCore/tests/test_rob_bookkeeping.sh
bash /Users/zhoubot/LinxCore/tests/test_cosim_smoke.sh
bash /Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh
bash /Users/zhoubot/LinxCore/tests/test_opcode_parity.sh
bash /Users/zhoubot/LinxCore/tests/test_konata_sanity.sh
bash /Users/zhoubot/LinxCore/tests/test_konata_dfx_pipeview.sh
bash /Users/zhoubot/LinxCore/tests/test_konata_template_pipeview.sh
bash /Users/zhoubot/LinxCore/tests/test_cpp_codegen_safety_gate.sh
bash /Users/zhoubot/LinxCore/tests/test_coremark_crosscheck_1000.sh
bash /Users/zhoubot/LinxCore/tests/test_cbstop_inflation_guard.sh
```
