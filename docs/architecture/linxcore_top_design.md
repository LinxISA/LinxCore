# LinxCore OOO PYC Design (Memory-First)

## Scope

This document describes the standalone LinxCore stage-mapped OoO refactor implemented in:

- `/Users/zhoubot/LinxCore/src/linxcore_top.py`
- `/Users/zhoubot/LinxCore/src/*`

Primary goals:

- Split source by hardware component and pipeline stage.
- Keep the M1 retire-trace schema stable for QEMU lockstep tools.
- Improve memory-side throughput with I/D separation and store-retire decoupling.

## Module split

Top-level modules instantiated with real `m.instance` boundaries:

- `LinxCoreTop` integration (`/Users/zhoubot/LinxCore/src/top/top.py`)
- `LinxCoreMem2R1W` (`/Users/zhoubot/LinxCore/src/mem/mem2r1w.py`)
- `LinxCoreBackend` compatibility path (`/Users/zhoubot/LinxCore/src/bcc/backend/backend.py`)

Shared/common logic:

- `/Users/zhoubot/LinxCore/src/common/{isa.py,decode_f4.py,exec_uop.py,types.py,params.py,util.py}`

BCC stage contracts are split into dedicated files:

- IFU: `/Users/zhoubot/LinxCore/src/bcc/ifu/{f0.py,f1.py,f2.py,f3.py,f4.py,icache.py,ctrl.py}`
- OOO: `/Users/zhoubot/LinxCore/src/bcc/ooo/{dec1.py,dec2.py,ren.py,s1.py,s2.py,rob.py,pc_buffer.py,flush_ctrl.py,renu.py}`
- IEX: `/Users/zhoubot/LinxCore/src/bcc/iex/{iex.py,iex_alu.py,iex_bru.py,iex_fsu.py,iex_agu.py,iex_std.py}`
- BCtrl: `/Users/zhoubot/LinxCore/src/bcc/bctrl/{bctrl.py,bisq.py,brenu.py,brob.py}`
- LSU: `/Users/zhoubot/LinxCore/src/bcc/lsu/{liq.py,lhq.py,stq.py,scb.py,mdb.py,l1d.py}`
- TMU/TMA/CUBE/TAU: `/Users/zhoubot/LinxCore/src/tmu/*`, `/Users/zhoubot/LinxCore/src/tma/tma.py`, `/Users/zhoubot/LinxCore/src/cube/cube.py`, `/Users/zhoubot/LinxCore/src/tau/tau.py`

## Memory-first behavior

- I/D path separation via dual memories in `LinxCoreMem2R1W`:
  - `imem` read port for fetch.
  - `dmem` read/write path for LSU.
  - host writes mirrored to both memories.
- Store-retire decoupling:
  - retired stores enqueue into a committed store buffer.
  - independent drain path writes one store/cycle to `dmem`.
  - MMIO stores at commit still fire immediately:
    - `0x10000000` UART
    - `0x10000004` EXIT
- LSU ordering/forwarding:
  - stall load if an older unresolved store exists.
  - forward load data from older completed store or committed store buffer on address match.

## Retire trace contract

Per-commit-slot taps exported at top level (slot `0..3`):

- `commit_fireX`, `commit_pcX`, `commit_robX`, `commit_opX`
- `commit_lenX`, `commit_insn_rawX`
- `commit_wb_validX`, `commit_wb_rdX`, `commit_wb_dataX`
- `commit_mem_validX`, `commit_mem_is_storeX`, `commit_mem_addrX`, `commit_mem_wdataX`, `commit_mem_rdataX`, `commit_mem_sizeX`
- `commit_trap_validX`, `commit_trap_causeX`, `commit_next_pcX`

These are consumed by:

- `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp`
- `/Users/zhoubot/LinxCore/cosim/linxcore_lockstep_runner.cpp`

## Branch/block control semantics

Branch/block behavior is enforced in the canonical backend path:

- `BSTART` metadata is decoded in frontend/decode and stored in a PC-buffer table (`pc/kind/target/pred_take`).
- `pred_take` is carried as boundary metadata; baseline policy uses `pred_take=0` for conditional/return classes.
- `setc.cond` is validated in BRU against carried `pred_take`; mismatch raises redirect/fault handling.
- `BSTART/BSTOP` always allocate ROB entries and resolve at D2 (`resolved_d2=1`), without FU execution.
- Block-private release (`T/U/BARG`) is keyed only by `BSTOP` commit (`commit_is_bstop`), not by `BSTART`.
- BRU recovery target validity is checked against PC-buffer `is_bstart` entries; invalid targets raise precise trap with cause `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)`.

Important compatibility rule:

- `BSTART.CALL` does not perform implicit RA write. Return-address writeback must come from explicit `setret` instruction behavior.

## Tile/SIMT strict-v0.3 contracts

LinxCore/QEMU bring-up is aligned on these enforced contracts:

- Legacy `MCALL` naming maps to executable `BSTART.MPAR`/`BSTART.MSEQ` forms.
- Tile descriptor families are `B.IOT` (dynamic size via `RegSrc`) and `B.IOTI` (immediate size via `SizeCode`).
  `B.IOD` is deprecated for strict canonical streams.
- Dynamic `B.IOT` size is fail-fast validated to strict `512B..4KB` policy.
- Strict-v0.3 tile `DataType` mapping (u5):
  - floating: `FP64=0`, `FP32=1`, `FP16=2`, `FP8=3`, `BF16=6`, `FPL8=7`, `FP4=11`, `FPL4=12`
  - signed integer: `INT64=16`, `INT32=17`, `INT16=18`, `INT8=19`, `INT4=20`
  - unsigned integer: `UINT64=24`, `UINT32=25`, `UINT16=26`, `UINT8=27`, `UINT4=28`
- `TLOAD/TSTORE` use a 2D memory model: `LB0/LB1` define cols/rows and stride comes from `B.IOR` (`RegSrc0`) with `LB2`
  fallback; runtime enforces stride alignment and `stride >= row_span`.
- CUBE uses a singleton implicit ACC (no encoded ACC tile id). Canonical chain is:
  `TMATMUL` -> zero or more `TMATMUL.ACC` -> `ACCCVT`.
- Tile-byte legality is enforced with `ceil(dim0*dim1*dim2*element_bits/8) <= 4KB`
  (block-family `dim2` default `1` when absent; `element_bits` derives from `DataType`).
- For SIMT body blocks (`MPAR`, `MSEQ`, `VPAR`, `VSEQ`), header descriptor ingestion enforces at most 3 unique tile inputs
  and 1 tile output.
- `B.ATTR` full fields are captured in runtime state; SIMT bring-up currently restricts body-block usage to `aq/rl` bits.

## Scripts

- Generate split artifacts:
  - `/Users/zhoubot/LinxCore/tools/generate/update_generated_linxcore.sh`
- Build/run C++ TB:
  - `/Users/zhoubot/LinxCore/tools/generate/run_linxcore_top_cpp.sh`
- Run lockstep co-sim:
  - `/Users/zhoubot/LinxCore/tools/qemu/run_cosim_lockstep.sh`
- Benchmark harness:
  - `/Users/zhoubot/LinxCore/tools/image/run_linxcore_benchmarks.sh`
  - `/Users/zhoubot/LinxCore/tools/image/build_linxisa_benchmarks_memh_compat.sh` now defaults `LINX_INCLUDE_LIBM=0` to avoid unresolved soft-float runtime symbols on minimal Linx toolchains. Set `LINX_INCLUDE_LIBM=1` only when builtins are available.

## Validation gates

- `/Users/zhoubot/LinxCore/tests/test_runner_protocol.sh`
- `/Users/zhoubot/LinxCore/tests/test_trace_schema_and_mem.sh`
- `/Users/zhoubot/LinxCore/tests/test_cosim_smoke.sh`
- `/Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh`

## Notes

Co-sim smoke is validated with `trigger_pc == boot_pc` and bounded terminate windows.
CoreMark/Dhrystone completion is supported by script; cycle tuning remains iterative.
