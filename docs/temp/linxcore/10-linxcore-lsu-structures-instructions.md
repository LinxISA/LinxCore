# LSU Structures and Instructions

This document covers LinxCore LSU structures, scalar memory instruction behavior, atomics/fences, and tile-memory descriptor requirements.

The LSU migration preserves ISA-neutral load/store machinery first: AGU issue, STQ, LIQ/LHQ, forwarding CAM, TLB/cache lookup, refill, store drain, SCB, MDB, and response queues. LinxISA then binds those structures to TSO, LSID ordering, BCC/MTC channels, block-aware flush, and Linx scalar/tile memory instructions.

## 1 Core Structures

The scalar LSU requires these logical structures:

| Structure | Responsibility |
| --- | --- |
| LSID allocator | Monotonic load/store ID allocation at dispatch. |
| Load queue / load window | Tracks speculative loads, dependency state, response status, and replay/miss state. |
| Store queue (STQ) | Holds speculative stores until commit eligibility. |
| Committed-store buffer/drain | Moves committed stores to the memory system in order. |
| Store data path | Receives STD uops and associates data with store-address entries. |
| Address-generation path | Receives AGU/LDA/STA uops and computes effective addresses. |
| Forwarding/CAM path | Allows older store data to satisfy younger loads when architecturally legal. |
| MMIO/device path | Preserves side-effect ordering and precise cancellation rules. |
| Memory response resolver | Reports load data, misses, faults, and completion to ROB/IEX. |
| TLB and memory-type classifier | Translates addresses and classifies Normal versus Device/MMIO behavior. |
| L1D tag/state/data arrays | Provide cache hit, refill, and local write-update behavior. |
| Store coalescing buffer (SCB) | Holds committed, non-flushable stores before DCache/CHI completion. |
| Response/refill queues | Preserve miss, refill, WriteResp, UpgradeResp, and retry ordering. |

The model has broader MTC/tile structures as well, including tile load/store queues, tile store buffers, miss queues, and tile SCB-like drain state. Those are second-layer memory paths and must still report through block/BID completion.

## 2 Scalar Loads

Scalar loads:

- receive LSID at dispatch;
- wait for LSID issue eligibility;
- compute effective address through the AGU/LDA path;
- check older stores for forwarding when required;
- access normal or device memory according to type;
- return data to the destination `P`, `T`, or `U` path as decoded;
- report faults precisely.

Load-to-use behavior may use speculative wakeup, but correctness cannot depend on a missed load value being consumed before actual data arrives.

## 3 Scalar Stores

Scalar stores split into address and data work:

- STA/AGU computes address and metadata;
- STD provides store data and byte enables;
- STQ holds speculative store state;
- commit makes the store non-speculative;
- committed-store drain sends the store to memory or device path.

Stores must not become globally visible before the core can still cancel them precisely.

## 4 Atomics and Fences

The v0.56 manual defines atomic qualifiers:

- `.aq` orders the atomic before later memory operations;
- `.rl` orders earlier memory operations before the atomic;
- `.aqrl` combines acquire and release behavior.

Fences include device and instruction synchronization forms such as `FENCE.D` and `FENCE.I` according to the ISA manual.

The LSU must preserve these orderings under TSO and must coordinate with block boundaries. Atomic and fence operations are memory operations for LSID ordering and ROB precision.

## 5 Tile Memory Descriptors

TLOAD/TSTORE are block-header-driven operations. Their effective memory footprint comes from:

- `BSTART.TLOAD` or `BSTART.TSTORE` data type;
- `B.DIM` / `C.B.DIM*` for `LB0..LB2`;
- `B.ARG` for layout selector and pad policy;
- one or more `B.IOR` descriptors for GPR/base/stride bindings;
- one or more `B.IOT` descriptors for tile IO and transfer size.

Strict profile requirements:

- descriptor-rich `TLOAD/TSTORE` headers require `B.ARG + B.IOR`;
- `B.IOT` dynamic-size descriptors are legal only when `RegSrc` resolves to strict `0B..512KB`;
- repeated `B.IOR` and repeated `B.IOT` are legal and consumed in header order;
- TMA execution state is not sourced from `B.CATR`/`B.DATR`.

## 6 TLOAD/TSTORE Layout

The architectural tile linearization forms are:

- `ND`: row-major;
- `DN`: column-major;
- `NZ`: inner-block row-major plus inter-block column-major;
- `ZN`: inner-block column-major plus inter-block row-major.

For TLOAD/TSTORE:

- `NZ`/`ZN` denote TR-side layout;
- GM-side layout is `ND` or `DN`;
- `LB0` is GM-side inner element count;
- `LB1` is GM-side outer element count;
- `LB2` is TR-side inner element count;
- `TR_outer = TileElements / LB2`.

`NZ`/`ZN` TR-side tiles require:

- `TR_inner * element_size` multiple of 32 bytes;
- `TR_outer` multiple of 16.

## 7 Bridged Shader Memory

Canonical shader-kernel global memory uses:

- `l.*.brg`;
- `v.*.brg`.

Both forms use the same explicit address grammar. `ri*` remains the required base namespace. `v.*.brg` does not add an implicit lane index; per-lane behavior comes only from lane-varying operands under the current EXEC mask.

## 8 Open Questions

- Should scalar atomics share the same single-width LSU pipe as ordinary loads/stores in the first RTL target, or receive a distinct serialized micro-pipeline?
- Which STQ fields should be mandatory in trace for replay and forwarding debug?
- Should TLOAD/TSTORE descriptor validation happen in BCTRL header ingestion, LSU/MTC issue, or both?
