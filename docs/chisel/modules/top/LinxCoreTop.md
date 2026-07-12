# LinxCoreTop

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreTop.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/top/LinxCoreTopSpec.scala`
- Current child owners: `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ReducedCommitROB.scala`,
  `rtl/LinxCore/chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala`
- LinxCoreModel evidence: `model/LinxCoreModel/model/bctrl/spe/SPEROB.cpp`,
  `model/LinxCoreModel/model/interface/CommitInfo.h`
- Contract IDs: `LC-IF-CHISEL-TOP-001`, `LC-IF-CHISEL-XCHK-003`

## Purpose

`LinxCoreTop` is the current Chisel top-level bring-up shell. It is not yet the
full LinxCore frontend, decode, issue, execute, recovery, and commit system. It
instantiates the monitored `ReducedCommitROB` and canonical `ScalarLSU` store,
active/resolved load, and scalar MDB boundaries. Live failed-wait delete timing,
oldest eligibility, and exact recovery-source promotion are integrated beneath
the LSU owner. The reduced ROB supplies exact slot-plus-wrap validation for
canonical scalar LRET dequeue and consumes canonical W2 resolve through one
external-priority completion bridge. Full-BID recovery-source lookup and
central cleanup remain separate from this reduced RID lookup. Cache/miss
queues, physical RF/wakeup integration, and full recovery remain staged.

`LinxCoreFrontendTraceTop` is the separate next bring-up top for raw frontend
window to commit-row flow. Keep this reduced replay top stable for existing
xchecks while that newer wrapper grows toward live Verilator execution.

## Interface

| Direction | Signal | Type | Valid/ready | Description |
|---|---|---|---|---|
| input | `allocValid` | `Bool` | valid | Sends `allocRow` into the reduced ROB tail. |
| output | `allocReady` | `Bool` | ready | True when the reduced ROB can accept `allocRow`. |
| output | `allocDuplicateIdentity` | `Bool` | combinational | True when `allocRow.identity.(bid,gid,rid)` duplicates a live reduced ROB row. |
| input | `allocRow` | `CommitTraceRow` | `allocValid && allocReady` | Commit-trace-shaped row used as the temporary reduced top input payload. |
| input | `completeValid` | `Bool` | valid | Marks one reduced ROB slot complete. |
| input | `completeRobValue` | `UInt(log2Ceil(robEntries).W)` | `completeValid` | Reduced ROB slot index to complete. |
| output | `scalarLoadCompleteSelected` | `Bool` | combinational | Canonical scalar W2 won the shared reduced-ROB completion port. |
| output | `completeCollision` | `Bool` | combinational | External completion held a simultaneous scalar W2 candidate. |
| bidirectional boundary | `scalarLsu.store` | `STQSCBCommitPathIO` | typed per signal | Canonical scalar LSU store request, flush, cache/response, state, and diagnostic boundary. |
| bidirectional boundary | `scalarLsu.load` | `ScalarLSULoadPathIO` | typed per signal | Canonical LIQ/ResolveQ allocation, launch, forwarding, replay/refill, recovery, retire, and state boundary. |
| bidirectional boundary | `scalarLsu.recovery` | `ScalarLSURecoverySourcePortIO` | typed valid/ready | Promoted full-BID source and readiness, oldest BID/RID watermark, and exact ROB lookup request/result. Cleanup is not owned by this reduced shell. |
| output | `commit.rows` | `Vec(commitWidth, CommitTraceRow)` | row `valid` | Head-ordered retired rows from the reduced ROB. |
| output | `commitValidMask` | `UInt(commitWidth.W)` | combinational | Reduced ROB commit valid mask. |
| output | `commitCount` | `UInt` | combinational | Number of rows retiring this cycle. |
| output | `commitMonitorValidMask` | `UInt(commitWidth.W)` | combinational | Monitor-derived valid mask for the exported commit window. |
| output | `commitMonitorValidCount` | `UInt` | combinational | Monitor-derived valid row count. |
| output | `commitSkippedSlot` | `Bool` | combinational | Monitor error flag for non-prefix valid rows. |
| output | `commitDuplicateIdentity` | `Bool` | combinational | Monitor error flag for duplicate `CommitInfo` identity in one retire window. |
| output | `commitSlotMismatch` | `Bool` | combinational | Monitor error flag for a row whose slot label does not match its vector position. |
| output | `commitInvalidSideEffect` | `Bool` | combinational | Monitor error flag for side-effect envelopes on invalid fixed-width slots. |
| output | `commitContractError` | `Bool` | combinational | OR of all monitor error flags. |
| output | `empty` | `Bool` | combinational | Reduced ROB has no live entries. |
| output | `full` | `Bool` | combinational | Reduced ROB has no free entries. |
| output | `size` | `UInt(log2Ceil(robEntries + 1).W)` | combinational | Reduced ROB live-entry count. |
| output | `headValid` | `Bool` | combinational | Reduced ROB head slot contains a live row. |
| output | `headComplete` | `Bool` | combinational | Reduced ROB head slot is live and complete. |
| output | `headRobValue` | `UInt(log2Ceil(robEntries).W)` | combinational | Current reduced ROB head slot. |
| output | `idle` | `Bool` | combinational | True when the reduced ROB and speculative/response LSU state are quiescent. |

## State

State is owned by `ReducedCommitROB` and `ScalarLSU` children. `LinxCoreTop`
owns no registers of its own.

## Logic Design

The top computes `CommitTraceParams` from `CoreParams` so top-level commit width
and ROB slot width stay tied to the core configuration. It wires external
allocation into `ReducedCommitROB`. `ScalarLoadCompletionROBBridge` routes the
canonical LRET RID lookup to the reduced ROB and arbitrates external execute
completion against canonical scalar W2 resolve. External completion has fixed
priority; W2 remains resident and retries because resolve-ready is withheld.
When scalar W2 wins, the full RID reaches the ROB exact-completion port and is
revalidated against the resident slot generation before the completion bit can
change. The legacy external completion input remains a trusted slot-only
reduced-harness interface and is not the canonical scalar-load contract. A
same-slot external/scalar candidate is rejected as duplicate source ownership;
only different-slot contention may hold and retry.
The top forwards the
commit window and monitor flags, and reports `idle` when the reduced ROB is
empty and the scalar LSU reports its STQ, commit-drain queue, SCB row bank, SCB
response buffer, LIQ, ResolveQ, pending load transfer, MDB command/fanout
queues, retained wait plans, registered MDB store wakeup, and retained recovery
source empty.

This keeps the first top-level Chisel structure aligned with the
LinxCoreModel-derived `SPEROB::commit` walk: rows retire in contiguous completed
head order, and invalid fixed-width slots are zeroed before adapter filtering.

## Timing

Timing is inherited from `ReducedCommitROB`: completion is registered and is not
visible to commit selection until the next cycle. The completion bridge is
combinational and adds no retained state. Exact-completion readiness is part of
the W2 atomic rendezvous, so stale/free RID rejection holds the W2 slot.

## Flush/Recovery

The scalar LSU accepts typed Linx store and load flush boundaries, including
precise LIQ/ResolveQ pruning. It retains MDB recovery, checks the external
oldest BID/RID watermark, and publishes an exactly promoted full-BID source.
The reduced ROB cannot supply canonical BID/RID, answer the exact full-BID
lookup, or consume that source, so full checkpoint restore, rename cleanup,
all-source arbitration, and precise trap ownership are not implemented in this
shell. Future integrated top work must
replace the reduced ROB interface with real frontend/backend ownership while
keeping commit rows monitored before cross-check use.

## Trace/Observability

`commit.rows` is the top-level Chisel commit observation surface for the current
bring-up shell. `commitContractError` must remain false before emitted JSONL is
treated as QEMU/DUT evidence.

`tools/chisel/run_chisel_trace_replay_xcheck.sh` now exercises this surface
with adapter-normalized input rows instead of only the built-in three-row smoke.
That gate is still a replay harness: it proves the top can export and compare a
bounded external commit stream, not that frontend/decode/execute/LSU generated
those rows from an ELF.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only LinxCoreTop`
- `bash tools/chisel/run_chisel_verilator_lint.sh`
- `bash tools/chisel/run_chisel_top_xcheck.sh`
- `bash tools/chisel/run_chisel_trace_replay_xcheck.sh`
- `bash tools/chisel/run_chisel_scalar_load_completion_rob_probe.sh`
- `bash tools/chisel/run_chisel_frontend_trace_top_lint.sh`
- `bash tools/chisel/build_chisel.sh`

Current tests cover parameter derivation from `CoreParams`, top-level
elaboration with `ReducedCommitROB`, `CommitTraceMonitor`, `ScalarLSU`, and its
STQ-to-SCB and LIQ-to-ResolveQ children, and top Verilator
lint over all emitted SystemVerilog files. The top xcheck emits a dedicated
8-entry, two-wide `LinxCoreTop` configuration and reuses the reduced ROB
Verilator trace harness to prove the top-level commit observation surface
against QEMU-shaped reference rows. The trace replay xcheck uses the same top
configuration and comparator path but drives rows loaded from an external
normalized JSONL stream.
