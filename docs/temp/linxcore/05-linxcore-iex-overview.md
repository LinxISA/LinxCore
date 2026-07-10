# IEX Overview

This document introduces the LinxCore integer/scalar execution fabric and the issue queues used by scalar, control, memory-address, store-data, system, and command uops.

## 1 Role of IEX

IEX executes renamed scalar uops and coordinates with the LSU, ROB, BCTRL, and block fabric. It is responsible for:

- ALU execution;
- branch/control condition calculation;
- address-generation uops for scalar loads/stores;
- store-data uops;
- system and CSR-like uops that are not handled directly at boundary decode;
- command queueing for block descriptors and engine-backed blocks;
- wakeup and bypass signals for `P`, `T`, and `U` consumers;
- resolve signals to ROB and BROB.

In the Linx migration, IEX is no longer organized around ARM integer, VFP, SVE, pointer-authentication, or AArch64 system instruction behavior. It is organized around LinxISA uop classes and block control.

## 2 Execution Classes

| Class | Responsibility |
| --- | --- |
| ALU | Integer/scalar arithmetic, logical operations, moves, and scalar immediate operations. |
| BRU | `SETC.*`, compare, branch-condition, target-preparation, and boundary correction metadata. |
| AGU/LDA | Load-address generation and scalar memory request preparation. |
| STA | Store-address generation. |
| STD | Store-data production; reads source operands but does not write a register result. |
| FSU/SYS | Floating/system-like work in the scalar shared path, as configured by implementation. |
| CMD | Block command and descriptor path for BCTRL/BISQ/BROB/block-fabric integration. |

Boundary delimiter instructions (`BSTART`, `BSTOP`, template boundaries) are resolved before ordinary IQ issue when they are pure boundary uops. Descriptor and command instructions may use `cmd_iq` when they need backend command scheduling.

## 3 Queue Topology

The promoted LinxCore contract names these physical IQs:

- `alu_iq0`
- `shared_iq1`
- `bru_iq`
- `agu_iq0`
- `agu_iq1`
- `std_iq0`
- `std_iq1`
- `cmd_iq`

The current LinxCoreModel has wider family-level evidence:

| Family | Depth | Count | Write ports | Notes |
| --- | ---: | ---: | ---: | --- |
| CMD | 24 | 1 | 4 | Descriptor and command issue. |
| ALU | 24 | 8 | 4 | Scalar ALU family. |
| LDA | 32 | 4 | 4 | Load address generation. |
| STA | 16 | 4 | 4 | Store address generation. |
| STD | 16 | 4 | 4 | Store data. |
| BRU | 32 | 4 | 4 | Branch/control resolve. |

The model also has SIMT and MTC issue-queue families for vector/tile execution. Those are engine or second-layer execution details, not the scalar IEX baseline.

## 4 Dispatch Width and Retire Evidence

LinxCoreModel evidence:

- `max_iq_insert_num=4`;
- `iex_retire_width=4`;
- `iex_load_wb_width=4`;
- `ld_rscv_width=4`;
- `issuedPerIqLane=2`;
- `iex_dispatch_mode=1`, described in config as reserving entries for the oldest block;
- `iex_mdb_enable=true`.

The promoted architecture contract uses a 4-wide baseline for dispatch, issue, and commit, with a single scalar LSU.

## 5 Integration With ROB and BROB

IEX must report:

- uop resolve to ROB;
- branch/control correction metadata to the boundary machine;
- memory address and ordering metadata to LSU;
- command enqueue and response routing metadata to BCTRL/BROB;
- trap and exception information precisely by `rid` and `bid`.

IEX cannot commit architectural redirect independently. Boundary redirect is selected by the block boundary logic.

## 6 Integration With Register Files

Scalar IEX consumes and produces:

- global `P` physical tags through normal RF/bypass;
- `T` and `U` local physical tags through block-private local RF paths;
- CARG-derived values materialized before IQ entry.

Read-port arbitration is global at `I1`. The default integer RF read-port count in the promoted contract is 3. STD uses read ports but no write port.

## 7 Differences From ARM-Oriented IEX

| ARM-oriented topic | LinxCore treatment |
| --- | --- |
| AArch64 ALU/BRU/AGU pipes | Retained as generic execution classes, but driven by LinxISA decoded uops. |
| VFP/SVE execution | Replaced by Linx VEC/SIMT and tile-engine block integration. |
| Pointer authentication | Not a LinxCore `v0.56` IEX feature unless future ISA catalog adds it. |
| ARM system register file flows | Replaced by ACR/SSR/trap-envelope behavior and Linx system instructions. |
| Branch mispredict redirect | Replaced by `SETC` correction plus boundary-authoritative redirect. |
| PC-relative branch target ownership | Legal target must be a block start marker. |

## 8 Open Questions

- Which model IEX queue families should be physically retained in the first pyCircuit RTL, and which should be collapsed behind the promoted physical IQ names?
- Should `iex_mdb_enable` be specified as required for LinxCore `v0.56`, or kept as an implementation option with required correctness fallback?
- How should FSU/SYS sharing in `shared_iq1` be documented once the LinxISA system instruction subset is fully staged in RTL?
