# LSU Pipelines

This document describes the load, store, drain, forwarding, replay, MMIO, TMA, and bridged memory pipelines.

## 1 Scalar Load Pipeline

A scalar load follows this logical path:

1. D3 atomic admission/dispatch receives the ROB-owned `RID`, BROB-owned
   `BID`, and memory-order `LSID` for the load row.
2. Rename resolves destination class and source operands.
3. AGU/LDA enters an issue queue.
4. LSID issue pointer grants memory issue when the load is oldest eligible.
5. Address generation computes effective address and memory type.
6. Older stores are checked for legal forwarding.
7. Cache/memory request is issued if forwarding does not satisfy the load.
8. Speculative wakeup may occur at `LD_E1`.
9. Actual data returns at `LD_E4`.
10. ROB and wakeup/bypass state resolve.

If the load misses at `E4`, dependent picks that still carry `LD_E4` must be suppressed while `miss_pending` is true.

## 2 Scalar Store Pipeline

A scalar store follows two converging paths:

- STA/AGU computes address, byte enables, memory type, and ordering metadata.
- STD reads and supplies store data.

The STQ combines address and data. Before commit, store state is speculative and flushable. After commit, the store enters the committed-store drain path.

The store becomes globally visible only after it is non-speculative and released by the drain according to memory type and ordering rules.

## 3 Store Drain

Committed-store drain:

- accepts stores after ROB commit eligibility;
- preserves store-to-store order;
- handles Device/MMIO serialization;
- reports completion or faults where applicable;
- coordinates with fences and atomics;
- keeps trace-visible memory metadata aligned with commit behavior.

LinxCoreModel evidence has `store_commit_count=64`, but the architecture contract remains about order and visibility rather than mandating a 64-store retire event.

## 4 Forwarding

Forwarding must be older-to-younger and architecturally legal. A load may read from older store data when address and byte coverage prove it is correct under TSO and the core's precise exception rules.

Forwarding must not:

- use younger store data;
- use store data from a flushed block;
- bypass Device/MMIO ordering requirements;
- hide an older fault that should be precise.

## 5 Replay and Nuke Conditions

Replay may be required for:

- load miss after speculative wakeup;
- store-data/address mismatch timing;
- forwarding uncertainty;
- memory-type ordering restriction;
- fence/atomic serialization;
- flush or interrupt;
- block recovery;
- MMU fault.

The model includes switches such as `load_store_replay=false` and `pe_replay_enable=false` in current config. The architectural requirement is not the switch value; it is that any enabled replay path preserves precision and forward progress.

## 6 Device and MMIO Pipeline

Device/MMIO accesses are side-effecting and strongly ordered. The LSU must serialize them at the commit-visible point.

Rules:

- speculative Device/MMIO side effects are forbidden;
- a Device/MMIO load must not be repeated invisibly if the platform observes the access;
- a Device/MMIO store must not drain before it is non-speculative;
- traps or bus errors report precise source context.

## 7 TMA and MTC Pipeline

TMA tile memory blocks are engine/block-fabric work, not ordinary scalar LSU uops. They still participate in the architectural memory model.

TMA/TSTORE behavior:

- header descriptors define dimensions, layout, base/stride bindings, tile IO, transfer size, and data type;
- one architectural TLOAD/TSTORE may decompose into multiple internal beats;
- internal beats are not guaranteed to issue in source-program row-major or element-major order;
- completion is not atomic to observers unless a future profile explicitly defines stronger behavior;
- BCC/MTC ordering boundaries preserve TSO requirements.

## 8 Bridged Kernel Memory Pipeline

`l.*.brg` and `v.*.brg` execute inside canonical `MPAR`/`MSEQ` shader kernels.

Rules:

- `.brg` address formation is explicit-only;
- per-lane behavior is controlled by lane-varying operands and EXEC mask `p`;
- scalar/group operands broadcast;
- kernel entry requires an acquire-style boundary before memory issue;
- kernel exit requires a release-style boundary before scalar blocks continue;
- BCC scalar memory issue is closed while the shader kernel is active.

## 9 Open Questions

- Should load pipeline stage names `LD_E1..LD_E4` be surfaced in public trace, or remain internal debug names?
- What is the first RTL target for Device/MMIO replay avoidance on loads that may have external side effects?
- Which TMA beat-level events should be trace-visible for performance/debug versus hidden behind block completion?
