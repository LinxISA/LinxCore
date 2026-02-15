# LinxCore Milestone 1 Scope

## Goal

Establish ROB-first retirement correctness and lockstep co-simulation plumbing between:

- Janus OoO C++ model (`/Users/zhoubot/pyCircuit`)
- Linx QEMU (`/Users/zhoubot/qemu`)
- LinxCore orchestrator/runner (`/Users/zhoubot/LinxCore`)

## Implemented in M1

### Janus / pyCircuit

- ROB state extended with:
  - `insn_raw`
  - load metadata (`is_load`, `load_addr`, `load_data`, `load_size`)
- Commit-slot outputs extended with:
  - `commit_fireX`, `commit_pcX`, `commit_robX`, `commit_opX`, `commit_valueX`
  - `commit_lenX`, `commit_insn_rawX`
  - `commit_wb_validX`, `commit_wb_rdX`, `commit_wb_dataX`
  - `commit_mem_validX`, `commit_mem_is_storeX`, `commit_mem_addrX`, `commit_mem_wdataX`, `commit_mem_rdataX`, `commit_mem_sizeX`
  - `commit_trap_validX`, `commit_trap_causeX`, `commit_next_pcX`
- C++ TB JSONL commit export via `PYC_COMMIT_TRACE`.

### QEMU / target/linx

- Co-sim env controls added and parsed.
- Trigger helper at instruction start (`linx_cosim_before_insn`) for pre-exec trigger snapshot.
- Sparse snapshot dump (`LXCOSIM1` format).
- Per-commit JSONL send + per-commit ack wait.
- Fail-fast on mismatch ack.
- End-window handling:
  - `terminate_pc`
  - `max_commits`
  - `guest_exit`
- Commit trace fields updated with `len` and `mem_is_store`.

### LinxCore repo

- Git repo initialized.
- Added:
  - `docs/cosim/cosim_protocol.md`
  - `docs/flows/milestone1_scope.md`
  - `cosim/linxcore_lockstep_runner.cpp`
  - `tools/qemu/run_cosim_lockstep.sh`
  - `tools/qemu/build_sparse_ranges.py`
- Runner behavior:
  - Accepts QEMU socket connection.
  - Loads sparse snapshot into DUT memory (`imem` + `dmem`).
  - Steps generated LinxCore C++ DUT until commit slots fire.
  - Compares one retired record at a time and returns per-commit ack.
  - Supports forced mismatch injection (`--force-mismatch`) for negative testing.

## M1 Out of Scope

- Mid-program trigger with architectural register-state snapshot/restore.
- FPGA monitor streaming.
- Full privileged/MMU/interrupt differential coverage.

## Exit Criteria

- One commit message and one ack per retired record.
- First mismatch exits immediately.
- Success requires `end(reason="terminate_pc")` with no mismatch.
