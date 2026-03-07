# OEX Session Record (2026-02-20)

## Requirement Summary
- Rework `/standalone/OEX` into a scalable, parameterized pyCircuit v3.5 design for a superscalar OEX shadow model.
- Use a single source of configuration via profile-driven parameters (`oex_debug`, `oex_target`) with:
  - dispatch width, commit width, issue width
  - ROB and IQ depths
  - lane counts per execution class
  - architectural/physical tag sizes and fixed execution latencies
  - derived widths computed with template-like arithmetic.
- Keep shadow checking strict: every committed row must match QEMU commit-trace JSONL fields:
  - `pc`, `len`, `insn`, `src0_valid/src0_reg/src0_data`, `src1_*`,
    `dst_valid/dst_reg/dst_data`, `mem_*`, `trap_*`, `next_pc`.
- Report and compare through to completion for CoreMark and Dhrystone:
  - `cycles`
  - `commits`
  - `IPC = commits / cycles`.
- Produce LinxTrace for LinxCoreSight/pipeview via the same run.

## Plan Executed
1. Validate/confirm current pyc modules and TB already expose major parameterization and modular structure.
2. Build the v3.5 frontend/backend flow and generate a checked C++ TB.
3. Run full CoreMark and Dhrystone QEMU traces with mismatch-fail-fast verification.
4. Emit raw events (`--raw`), convert to LinxTrace, and lint/summarize.
5. Run `linxtrace_cli_debug` stage/row summaries for pipeview inspection.
6. Fix runner scripts so benchmark wrappers work through shell invocation.

## Results (stored artifacts)
- `/tmp/oex_coremark_full.txt`  
  `cycles=794987 commits=1216365 ipc=1.53004`
- `/tmp/oex_dh_full.txt`  
  `cycles=820380 commits=1255201 ipc=1.53002`
- `/tmp/coremark_oex.linxtrace`
- `/tmp/dh_oex.linxtrace`
- `/tmp/coremark_oex_raw.jsonl`
- `/tmp/dh_oex_raw.jsonl`

