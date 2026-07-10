# Block Fabric, Engines, and Open Questions

This document covers BCTRL/BISQ/BROB command fabric, engine command/response contracts, workload engines, and consolidated follow-up questions.

## 1 Block Fabric Role

The block fabric connects scalar block execution to second-layer engines. It must preserve the same architectural ordering model as scalar execution:

- block commands are created from typed `BSTART.*` headers and descriptors;
- command identity is derived from `BID`;
- engine completion is observed by BROB;
- exceptions and traps route precisely;
- flush kills younger block work;
- trace shows command and completion behavior.

Engines are not separate machines with independent retirement.

## 2 Command Envelope

The current block-fabric contract defines a common command envelope:

| Signal | Meaning |
| --- | --- |
| `cmd_valid` | Command is valid. |
| `cmd_ready` | Receiver can accept command. |
| `cmd_kind[2:0]` | Command kind or engine family. |
| `cmd_stid[STID_W-1:0]` | Separate STID/ring selector; it is never packed into BID. |
| `cmd_tag[BID_W-1:0]` | Response tag, equal to the complete per-STID BID slot index; default 256-entry configuration makes this 8 bits. |
| `cmd_tile[5:0]` | Tile-related command identifier where applicable. |
| `cmd_payload[63:0]` | Command payload. |
| `cmd_src_rob[5:0]` | Source ROB reference. |
| `cmd_epoch[7:0]` | Epoch for tag wrap/disambiguation. |

Backend marks a CMD uop done only on enqueue fire into the command path. Dequeue occurs when BCTRL issues the command.

## 3 Response Envelope

The common response envelope includes:

| Signal | Meaning |
| --- | --- |
| `rsp_valid` | Response is valid. |
| `rsp_stid[STID_W-1:0]` | Matches the command's separate STID/ring selector. |
| `rsp_tag[BID_W-1:0]` | Matches the command's complete per-STID BID slot index. |
| `rsp_status[3:0]` | Engine status. |
| `rsp_data0[63:0]` | First response payload. |
| `rsp_data1[63:0]` | Second response payload. |
| `rsp_trap_valid` | Response carries trap. |
| `rsp_trap_cause[31:0]` | Trap cause. |

BROB maps responses back to dynamic block identity and source ROB context.

## 4 BISQ, BRENU, and BROB

| Unit | Responsibility |
| --- | --- |
| BISQ | Block issue queue with true head/tail behavior. |
| BRENU | Allocates command tags/epochs and handles wrap disambiguation. |
| BROB | Tracks outstanding command tags, source ROB map, completion, and flush. |
| BCTRL | Dispatches block commands to engines according to block type, descriptor state, and resource availability. |

Rules:

- BISQ enqueues from backend CMD path.
- Backend marks CMD done only on enqueue fire.
- BCTRL issues from BISQ to engines.
- BRENU increments epoch on tag wrap.
- BROB is the architectural block-completion observer.

## 5 Engine Families

| Engine family | Architectural role |
| --- | --- |
| VEC | General programmable SIMT compute for `MPAR`/`MSEQ` and related vector execution. |
| TMA | Tile memory/access operations such as `TLOAD`, `TSTORE`, and `TMOV`; memory traffic uses MTC/CSU path. |
| CUBE | Matrix/tile compute selected by CUBE block headers and aliases. |
| TAU | Tile-to-tile rendering/profile operations. |
| TEPL | Generic extensible accelerator block type. |

All engine families use typed block starts and descriptors. Engine issue, completion, exception, and flush must remain visible to ROB/BROB/trace.

## 6 Workload Mapping

LinxISA `v0.56` covers multiple workload classes through the same block model:

- general-purpose scalar/control work through `STD`, `SYS`, `FP`, and scalar memory;
- AI workloads primarily through `CUBE + VEC + TMA`;
- rendering workloads primarily through `VEC + TMA + TAU`.

The machine remains one LinxCore-native architecture. It is not a separate GPU packet machine.

## 7 Consolidated Open Questions

1. What resource values should be promoted from LinxCoreModel into the first normative LinxCore RTL target?
2. Which `BROB_ENTRIES` values meet the first RTL PPA target, given that
   `BID_W = ceil(log2(BROB_ENTRIES))` and the model currently uses 256?
3. Which engine selectors are required for the first implementation milestone?
4. Should `MPAR`/`MSEQ` body fetch be owned by VEC/BCTRL entirely, or share frontend fetch structures?
5. What exact trace events are required for command enqueue, command issue, engine response, engine trap, and block retirement?
6. Should block-fabric response traps be normalized before ROB commit, or should engine-specific trap causes remain visible to handlers?
7. Which tile descriptor validation failures are fatal non-restartable traps versus precise restartable traps?
8. Should BCC/MTC memory ordering be verified through a shared global order checker or per-channel checkers plus boundary constraints?
9. What is the required subset of template execution for bring-up: `FENTRY`/`FEXIT` only, `FRET.*`, or full `MCOPY`/`MSET` support?
10. Which LinxCoreModel fail-fast assertions correspond to architecturally illegal states that RTL must trap, and which are model-only debug invariants?

## 8 Draft Completion Criteria

This LinxCore markdown set should be considered ready for implementation planning when:

- every ARM-specific architectural assumption has been replaced or explicitly rejected;
- every LinxISA `v0.56` block-control guarantee is represented;
- ROB, BROB, LSID, trap, and engine completion contracts are cross-consistent;
- open questions are reviewed and either answered or deliberately deferred;
- the repository conformance gates for ISA catalog, canonical v0.56 checks, and LinxCore architecture checks pass.
