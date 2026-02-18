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
- `LinxCoreBackend` thin compatibility shell (`/Users/zhoubot/LinxCore/src/bcc/backend/backend.py`)
- Backend engine implementation (`/Users/zhoubot/LinxCore/src/bcc/backend/engine.py`)

Shared/common logic:

- `/Users/zhoubot/LinxCore/src/common/{isa.py,opcode_catalog.yaml,opcode_ids_gen.py,opcode_meta_gen.py,decode.py,decode16.py,decode32.py,decode48.py,decode64.py,decode_f4.py,exec_uop.py,types.py,params.py,util.py}`

BCC stage contracts are split into dedicated files:

- IFU: `/Users/zhoubot/LinxCore/src/bcc/ifu/{f0.py,f1.py,f2.py,f3.py,f4.py,icache.py,ctrl.py}`
- OOO: `/Users/zhoubot/LinxCore/src/bcc/ooo/{dec1.py,dec2.py,ren.py,s1.py,s2.py,rob.py,pc_buffer.py,flush_ctrl.py,renu.py}`
- IEX: `/Users/zhoubot/LinxCore/src/bcc/iex/{iex.py,iex_alu.py,iex_bru.py,iex_fsu.py,iex_agu.py,iex_std.py}`
- BCtrl: `/Users/zhoubot/LinxCore/src/bcc/bctrl/{bctrl.py,bisq.py,brenu.py,brob.py}`
- LSU: `/Users/zhoubot/LinxCore/src/bcc/lsu/{liq.py,lhq.py,stq.py,scb.py,mdb.py,l1d.py}`
- TMU/TMA/CUBE/VEC/TAU: `/Users/zhoubot/LinxCore/src/tmu/*`, `/Users/zhoubot/LinxCore/src/tma/tma.py`, `/Users/zhoubot/LinxCore/src/cube/cube.py`, `/Users/zhoubot/LinxCore/src/vec/vec.py`, `/Users/zhoubot/LinxCore/src/tau/tau.py`

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

## Block BID + BROB lifecycle

- Dynamic block identity now carries both:
  - `block_uid` (dynamic block instance identity), and
  - `block_bid` (BROB identity, low bits = BROB slot, high bits = debug uniqueness).
- `block_bid` is stored in ROB entries and propagated through dispatch/issue/commit trace taps.
- CMD pipe -> BISQ now carries `cmd_bid`, and BCtrl forwards this BID to PE paths and BROB issue paths.
- BROB now exposes explicit queried lifecycle state for the active block:
  - `allocated`
  - `ready`
  - `retired`
  - `exception`
- `BSTOP` retirement is gated by BROB state:
  - retire allowed when block is not allocated in BROB, or BROB marks it ready/exception.
  - successful `BSTOP` emits BROB retire event (`brob_retire_fire/bid`).

## LSID-ordered LSU (no memory speculation)

- Dispatch allocates per-memory-uop `load_store_id` (LSID).
- ROB retains LSID for each memory uop.
- LSU lane0 may issue only when:
  - LSU dependency checks pass, and
  - `uop.load_store_id == lsid_issue_ptr`.
- On effective LSU issue, `lsid_issue_ptr`/`lsid_complete_ptr` advance in-order.
- On redirect/flush, LSID issue/complete pointers are rebased to allocation head to avoid stale-ID deadlock after younger speculative drops.

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
- architectural redirect is boundary-authoritative:
  - BRU mismatch writes deferred correction state (`br_corr_*`), and
  - redirect is consumed at boundary commit with epoch match checks.
- `BSTART/BSTOP` always allocate ROB entries and resolve at D2 (`resolved_d2=1`), without FU execution.
- backend tracks block-head state (`state.block_head`) so `BSTART` can act as:
  - block head marker, or
  - in-body boundary terminator that restarts fetch at the resolved boundary PC.
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
  - `/Users/zhoubot/LinxCore/tools/image/build_linxisa_benchmarks_memh_compat.sh` resolves real benchmark ELFs from `/Users/zhoubot/linx-isa/workloads/generated/elf/` first, with optional strict mode `LINX_BENCH_REQUIRE_REAL=1`.
- QEMU commit-trace + cross-check:
  - `/Users/zhoubot/LinxCore/tools/qemu/run_qemu_commit_trace.sh`
  - `/Users/zhoubot/LinxCore/tools/trace/crosscheck_qemu_linxcore.py`

## Validation gates

- `/Users/zhoubot/LinxCore/tests/test_runner_protocol.sh`
- `/Users/zhoubot/LinxCore/tests/test_trace_schema_and_mem.sh`
- `/Users/zhoubot/LinxCore/tests/test_cosim_smoke.sh`
- `/Users/zhoubot/LinxCore/tests/test_stage_connectivity.sh`

## DFX pipeview

- Top-level DFX probe wiring is emitted from `/Users/zhoubot/LinxCore/src/top/top.py` using `m.debug_occ(...)`.
- Backend exports per-uop identity fields (`dispatch_uop_uid*`, `issue_uop_uid*`, `commit_uop_uid*`, `ctu_uop_uid`) from `/Users/zhoubot/LinxCore/src/bcc/backend/engine.py`.
- ROB tracks dynamic uop lineage via `uop_uid`/`parent_uid` arrays in `/Users/zhoubot/LinxCore/src/bcc/backend/state.py`.
- `CodeTemplateUnit` emits template-uop identity metadata in `/Users/zhoubot/LinxCore/src/bcc/backend/code_template_unit.py`.
- Global UID allocation is centralized in `/Users/zhoubot/LinxCore/src/common/uid_allocator.py`.
- LinxTrace format is `linxtrace.v1`, written via explicit occupancy records from `/Users/zhoubot/LinxCore/tb/tb_linxcore_top.cpp` and `/Users/zhoubot/LinxCore/tools/trace/build_linxtrace_view.py`.
- In-TB QEMU cross-check uses `PYC_QEMU_TRACE` with modes:
  - `PYC_XCHECK_MODE=diagnostic|failfast`
  - `PYC_XCHECK_MAX_COMMITS=<N>`
  - `PYC_XCHECK_REPORT=<path_prefix>`
- LinxTrace mismatch annotations emit one-cycle `XCHK` marker on mismatched retire events.

## Notes

Co-sim smoke is validated with `trigger_pc == boot_pc` and bounded terminate windows.
CoreMark/Dhrystone completion is supported by script; cycle tuning remains iterative.
