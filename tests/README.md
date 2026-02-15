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
- Purpose: stage naming/interface lint and top-level stage-prefix connectivity checks.

Run all:

```bash
bash /Users/zhoubot/LinxCore/tests/test_runner_protocol.sh
bash /Users/zhoubot/LinxCore/tests/test_trace_schema_and_mem.sh
bash /Users/zhoubot/LinxCore/tests/test_rob_bookkeeping.sh
bash /Users/zhoubot/LinxCore/tests/test_cosim_smoke.sh
bash /Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh
```
