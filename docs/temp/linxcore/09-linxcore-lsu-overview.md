# LSU Overview

This document introduces the LinxCore load/store unit, LinxISA memory model, memory channels, scalar LSU baseline, and LSID ordering.

## 1 Architectural Memory Model

LinxISA `v0.56` uses TSO as the architectural memory model.

Definitions:

- a memory operation is a load, store, atomic read-modify-write, or fence;
- program order is the committed architectural order on one LXCPU;
- device/MMIO accesses target platform-defined device memory regions.

Strict profile rules:

- store-to-store order is preserved for all addresses, including `TSTORE` beats;
- loads may observe store-buffer behavior consistent with TSO;
- fences and atomic qualifiers add ordering constraints;
- implementations may be stronger than TSO, but software must not depend on stronger behavior unless a platform profile states it.

## 2 BCC and MTC Channels

LinxISA models two memory issue channels inside one architectural TSO domain:

| Channel | Scope |
| --- | --- |
| BCC | Scalar load, store, atomic, and fence operations; ordering point for scalar blocks. |
| MTC | Tile memory blocks (`TLOAD`, `TSTORE`) and bridged shader-kernel memory issue (`l.*.brg`, `v.*.brg`) through `MPAR`/`MSEQ`. |

Rules:

- normal tile mode keeps BCC and MTC requests in one architectural TSO domain;
- entering `MPAR`/`MSEQ` requires an acquire-style boundary before kernel memory issue;
- exiting `MPAR`/`MSEQ` requires a release-style boundary before later scalar blocks execute;
- while a shader kernel is active, BCC scalar memory issue is architecturally closed for correctness.

## 3 Scalar LSU Baseline

The live LinxCore contract is a single-scalar-LSU-width machine.

LinxCoreModel evidence:

- `scalar_lsu_num=1`;
- `scalar_lsu_load_windows=64`;
- `scalar_lsu_store_windows=256`;
- `lsu_width=64`;
- `stq_depth=2048`;
- `scalar_stq_depth=256`;
- `store_commit_count=64`;
- `rslv_capcity=64`;
- `load_to_use_enable=true`;
- `load_ooo_enable=true` in the model, while the architecture contract still requires scalar LSU issue ordering by LSID.

## 4 LSID Ordering

Dispatch allocates a monotonic `load_store_id` (`LSID`) per memory uop. LSU issue remains ordered by `lsid_issue_ptr`.

Rules:

- each scalar memory uop receives exactly one LSID;
- LSU may issue a scalar memory uop only when `uop.LSID == lsid_issue_ptr`;
- successful issue advances the issue pointer;
- completion advances completion/retirement tracking according to the memory path;
- flush drops speculative younger memory state;
- LSID issue/complete pointers rebase to the allocation head after flush.

This strict profile avoids depending on memory-disambiguation speculation for correctness.

## 5 Store Drain

Committed stores are decoupled through the committed-store drain path. Younger speculative memory state must remain flushable before commit.

Architectural visibility occurs only when the store is no longer cancellable by precise exception, branch recovery, interrupt, or block flush.

Device/MMIO stores require explicit commit-visible ordering and must not leak through speculative paths.

## 6 Load Forwarding and Replay

Load forwarding, replay, and store-drain behavior must remain precise with respect to committed state.

The issue/execute contract supports:

- speculative load wakeup at `LD_E1`;
- actual load data at `LD_E4`;
- consumer bypass from `E4` to consumer `I2`;
- miss suppression of dependent picks when `miss_pending` is detected at `E4`.

The memory-ordering contract remains stronger than an unrestricted OOO load/store queue: scalar memory issue is LSID ordered.

## 7 MMIO and Device Memory

Linx platforms classify memory as Normal or Device/MMIO in the v0.56 manual.

Device/MMIO access is strongly ordered and side-effecting. The LSU must coordinate with commit so device-visible side effects cannot occur on a wrong path.

## 8 Open Questions

- Should `load_ooo_enable=true` in LinxCoreModel be treated as a future optimization beyond the strict LSID-ordered scalar profile, or is it already required behavior under additional constraints?
- What is the exact scalar LSU response format that should be exposed in pyCircuit trace beyond address, data, size, and trap fields?
- Which Device/MMIO ordering constraints need platform-specific documentation for the first LinxCore target?
