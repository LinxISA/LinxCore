# LinxCore Chisel Cross-Stack Audit

Date: 2026-07-30

## Production owner-graph re-audit

This pass distinguishes three things that older status summaries sometimes
mixed together: a module existing in source, focused UT/IT evidence for that
module, and the module being the unique owner in the production elaboration
graph. The repository contains 435 main Chisel source files and 410 Chisel
test files across the backend, block-control, execute, frontend, LSU, OOO,
rename, ROB, recovery, system, and top packages. That breadth is substantial,
but file count is not production closure.

The current top-level graph is:

| Entry point | Hardware actually instantiated | Status |
| --- | --- | --- |
| `LinxCoreComposition` | `LinxCoreIfu`, `IfuLineMemoryBridge`, `D1InstructionDecodeStage`, and `IfuBackendFeedbackBridge` | Canonical production IFU boundary. Its IO explicitly leaves full rename/dispatch/issue outside the wrapper. |
| `LinxCoreTop` | `ReducedCommitROB`, canonical `ScalarLSU`, load-completion bridge, and scalar GPR sink | Bring-up shell, not a complete core. It has no production IFU-to-OOO-to-IEX graph. |
| `LinxCoreBenchmarkAutonomousTop` | canonical `LinxCoreComposition` plus `LinxCoreFrontendFetchRfAluTraceTop` and benchmark-only line-fill adapter | Workload-capable promotion harness. The IFU is production, while the backend is still a trace top containing reduced owners and benchmark memory/service seams. |

Direct source evidence is in
[`LinxCoreComposition.scala`](../../chisel/src/main/scala/linxcore/top/LinxCoreComposition.scala),
[`LinxCoreTop.scala`](../../chisel/src/main/scala/linxcore/top/LinxCoreTop.scala),
and
[`LinxCoreBenchmarkAutonomousTop.scala`](../../chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala).
The benchmark top selects `useProductionD1Ingress=true`, but also selects the
reduced store dispatch and STA bridge. Its live backend instantiates
`ReducedRobCompletionArbiter`, `ReducedScalarAluExecute`,
`ReducedScalarWritebackArbiter`, `ReducedServiceRequestPath`, reduced template
state, and reduced store/load replay owners. Those names describe real
production-graph residency, not merely stale documentation.

### Ranked document-to-hardware gaps

| Rank | Gap | Current evidence | Closure criterion |
| ---: | --- | --- | --- |
| 1 | No single production core top owns IFU -> OOO -> IEX -> LSU -> commit. | The canonical IFU and canonical ScalarLSU live in different top graphs; the workload graph joins production IFU to a reduced trace backend. | One named production top elaborates the complete owner graph and becomes the default workload/trace gate. |
| 2 | The parameterized OOO implementation is ahead of its production placement. | O0-O7 focused owners exist, including 2/4/6 ingress, grouped ROB/BROB/PC, P/T/U rename, dispatch/IEX residency, recovery, and OOO-side CTU lease/reinsertion. The benchmark still feeds `D1DecodedLaneQueue` into the legacy single-row `DecodeRenameROBPath`. | Complete O8 physical banking/timing, then replace the legacy benchmark backend with the OOO shell without a parallel shadow owner. |
| 3 | IEX production ownership is not unique. | The workload graph still uses `ReducedScalarAluExecute`, reduced completion/writeback arbitration, and a trace-top RF/issue fabric. System/CSR/trap/interrupt execution does not have a production owner in this graph. | One D3 -> IQ -> IEX -> W2 graph owns execution, RF readiness, ROB completion, and system side effects; reduced arbiters leave production elaboration. |
| 4 | LSU canonical islands are not yet the sole memory owner. | `ScalarLSU` contains canonical STQ/SCB/LIQ/L1D/recovery components, but the workload graph enables reduced STQ/STA, memory overlay, resident-forward, and replay state. Translation/protection, attributes, complete lower-memory/coherence, and several cross-line cases remain open. | Bind OOO/IEX load/store generation to canonical LSU identities, remove shadow state, then prove natural workload and recovery behavior through cache/MMU/lower-memory owners. |
| 5 | CTU has an OOO-side protocol but no production recipe engine/top connection. | `CTU` owns parent claim, lease, plan, ordered child reinsertion, and recovery prepare/apply. Current handoff explicitly leaves the external recipe engine and production-top wiring to O9. | A CTU outside IFU and OOO expands `FENTRY`/`FEXIT`/template parents, writes canonical children through the retained lease, and participates in the same recovery transaction. |
| 6 | Status documents have different freshness levels. | `integrated-development-flow.md`, `development-loop.md`, and the packet ledger describe O8/O9 accurately; the Chisel README previously still described OOO as O0/O1 with decode, fusion, RENU, and recovery wholly future work. | Keep the short README synchronized to the authoritative handoff and mark long historical ledgers as evidence history rather than current placement. |
| 7 | The workload top is too monolithic for easy owner auditing. | `LinxCoreFrontendFetchRfAluTraceTop.scala` is roughly 12.9k lines and combines execution, template, store, replay, recovery, diagnostics, and trace adapters. | Promote bounded production modules behind stable interfaces; keep trace tops as observers/wrappers rather than state owners. |

### Fresh gate status

The 2026-07-30 re-audit ran the three focused top-entry suites after a clean
`Test/compile`:

| Gate | Result | Meaning |
| --- | --- | --- |
| `LinxCoreCompositionSpec` | 3/3 pass | The canonical IFU composition elaborates and preserves tagged line transport, B-F4 join, four-wide D1, backend validation, and canonical recovery. |
| `LinxCoreTopSpec` | 3/3 pass | The reduced commit-ROB plus canonical ScalarLSU shell elaborates. |
| `LinxCoreBenchmarkAutonomousTopSpec` | 15/15 pass | The workload top elaborates after the replay-LIQ bridge carries the canonical LIQ row lease and exact attempt into LRET, then preserves the data-or-fault result in the retained entry. |
| `LinxCoreFrontendFetchRfAluTraceTopSpec` | 44/44 pass | The full legacy live-top composition consumes the expanded LRET bundle without uninitialized fields. |
| <!-- task15-historical-specialized-evidence:start -->historical evidence only (no current runnable equivalent): `run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh` Provenance: source commit 8d2985c2d7971383315212995e5f2ce6f0fb4863. <!-- task15-historical-specialized-evidence:end --> | historical pass; 3 rows, 0 mismatches | At the cited source commit, emitted RTL compiled under Verilator and matched the bounded reference stream. |

The immediate LRET elaboration regression is closed. The repair is deliberately
limited to migration safety: it preserves exact canonical identity through the
hybrid workload graph but does not make the reduced trace backend a production
owner. Natural frozen-ELF benchmark reruns remain a separate promotion gate.

### Maturity assessment

- **IFU: high module maturity, medium system maturity.** The canonical
  I-SIDE/B-SIDE/Instruction-Buffer/D1 graph and real-workload evidence are
  strong. Atomic four-lane handoff into the production OOO graph, SoC PTW/L1I
  binding, complete predictor policy, and lower-memory error handling remain.
- **OOO: medium-high module maturity, medium integration maturity.** O0-O7 are
  unusually complete at focused-owner level. O8 physical implementation and
  O9 CTU/top/workload promotion are the decisive remaining steps.
- **IEX: medium module maturity, low-medium production-placement maturity.**
  Useful issue/RF/pipeline components exist, but the workload owner is still
  reduced and system execution is incomplete.
- **LSU: medium-high component maturity, medium-low unique-owner maturity.**
  The canonical component set is broad and heavily tested; dual canonical and
  reduced state paths are still the main architectural risk.
- **Whole core: medium-low current-head maturity.** Natural CoreMark/Dhrystone are meaningful
  vertical evidence, but they currently validate a hybrid production/reduced
  graph. The autonomous top now elaborates and its bounded generated-RTL gate
  passes; neither result proves that the final production core top is closed.

## Scope and acceptance boundary

This audit covers the benchmark-autonomous Chisel path used to execute the
frozen direct-boot CoreMark and Dhrystone images.  Acceptance means:

1. the same Chisel/Verilator binary reaches the pass finisher for both images;
2. UART output is byte-identical to the frozen QEMU semantic oracle;
3. instructions exercised by the images have no unexplained architectural
   divergence in the aligned commit prefix; and
4. the implemented pair-load rename and memory behavior has a credible
   production-hardware mapping.

This is a bring-up acceptance point, not a claim that the production cache,
MMU, coherent memory system, full D3 template path, or every Linx ISA opcode is
complete.

## End-to-end evidence

Both runs used reset PC `0x10000`, canonical direct-boot SP `0x7fefff0`, and
finisher pass code `0x5555`.

| Workload | Terminal result | Cycles | Commits | UART/QEMU SHA-256 |
| --- | --- | ---: | ---: | --- |
| CoreMark | `finisher_pass`; “Correct operation validated.” | 2,954,467 | 433,924 | `f572ae001f35f9bfbe7646c9c58d7cc1de1de769abf92642fb92a8cf943a9e6a` |
| Dhrystone 2.1 | `finisher_pass`; all reported final values match their expected values | 313,113 | 44,529 | `33bb6403150a20e16ab292791f8cfd814f007589f6c8a96de444c9991d074478` |

Primary evidence:

- CoreMark:
  [`generated/r1135-hl-imm24-coremark-terminal/report/natural_manifest.json`](../../generated/r1135-hl-imm24-coremark-terminal/report/natural_manifest.json)
- Dhrystone:
  [`generated/r1132-dual-credit-dhrystone-canonical-sp/report/natural_manifest.json`](../../generated/r1132-dual-credit-dhrystone-canonical-sp/report/natural_manifest.json)
- Frozen workload/QEMU contracts:
  [`coremark/manifest.json`](../../../../workloads/generated/linxcore-r678-direct/coremark/manifest.json)
  and
  [`dhrystone/manifest.json`](../../../../workloads/generated/linxcore-r678-direct/dhrystone/manifest.json)

The CoreMark standard CRC tuple is:

- seed `0xe9f5`
- list `0xe714`
- matrix `0x1fd7`
- state `0x8e3a`
- final `0xe714`

The natural harness now defaults to the canonical direct-boot SP instead of
deriving SP from the end of the last ELF load segment.  An explicit
`--reset-sp` still overrides the default.

## Architectural alignment

### Pair load/store

The architectural and implementation contracts agree that `HL.LDIP` has two
GPR destinations and `HL.SDIP` has two GPR data sources:

| Layer | Evidence | Contract |
| --- | --- | --- |
| ISA manual | `docs/isa/instructions/hl_ldip.md`, `hl_sdip.md` | `Dst0,Dst1` pair load; `SrcD,SrcD1` pair store |
| LLVM | `LinxISAInstrInfo.td:1038-1039,1091-1092` | two output operands for LDIP and two data inputs for SDIP |
| QEMU decode | `target/linx/insn48.decode:236-237,257-258` | exposes both destinations/sources |
| QEMU execute | `target/linx/translate.c:4384-4402,4471-4483` | accesses `addr0` and `addr0 + element_bytes` |
| LinxCoreModel | `lda_pipe.cpp:217-229`, `iex.cpp:1238-1248` | produces two memory requests and routes each returned lane to `pdsts_[lane]` |
| Chisel | `FrontendOperandDecode`, `ScalarDecodeRenameBridge`, `GPRRenameCheckpoint`, `ScalarGPRFile` | preserves and atomically renames/writes both GPR destinations |

`pairFirstDst` carries architectural `Dst0`; the normal destination remains
`Dst1`, preserving the existing commit-row projection while retaining both
physical destinations internally.  Rename admission reserves zero, one, or two
GPR/MapQ credits exactly.  A two-destination instruction either allocates both
physical registers and both MapQ entries or stalls without changing rename
state.

The autonomous memory top performs two lookups for pair loads.  Pair stores
expose a second accepted store observation lane, and the sparse-memory harness
applies both stores.  The ordinary single-row commit trace intentionally
projects only one pair-store lane; QEMU may project the other.  This is a trace
representation difference, not a memory-effect difference.

### HL 24-bit immediates

Sail and QEMU concatenate instruction fields `[15:4] @ [47:36]`.  QEMU declares
that encoding as `%uimm24 4:12 36:12` and `%simm24 4:s12 36:12`.
`FrontendOperandDecode` now implements the same concatenation and applies the
specified zero/sign extension for `ImmUIMM24` and `ImmSIMM24`.

The benchmark-discovered witness was raw instruction `0xfffc211500fe`
(`hl.andi t#1,65535 -> a0`) at PC `0x12578`.  Before the fix, the Chisel
immediate defaulted to zero; after the fix, it produces `0xe051`, matching QEMU,
and the aligned 94,751-commit CoreMark prefix has no non-projection functional
divergence.

### Template control and stack convention

The reduced FENTRY/FRET implementation and current template context behavior
are exercised by the natural workload path.  Direct-boot stack initialization
is fixed at `0x7fefff0`, matching the workload/QEMU contract.  This audit does
not promote the standalone D3 reservation and row-fill components to
production-mainline status.

## Hardware feasibility

The implemented dual-destination path is hardware-feasible:

- two-credit admission is a small integer comparison against free-list and
  per-STID MapQ capacity;
- allocation is atomic, avoiding partial-map rollback;
- the scalar RF has explicit second clear/write ports for pair destinations;
- both destinations retain old/new physical tags for normal commit/recovery.

The design direction also matches two comparison references. Both are
non-normative evidence and cannot override the Linx ISA or canonical LinxCore
contracts:

- LinxCoreModel represents a scalar pair memory operation as two request lanes
  under one ROB instruction and returns each lane to its corresponding
  physical destination.
- SuperNPU RTL atomically gates multi-row reservation on all required
  resources (`sn_ooo_rob_reserve_selector.v:41,51-68`) and carries separate
  `dst0`/`dst1` commit tags through banked MapQ handling
  (`sn_rename_scalar.v:35-39,93-124,524-529`).

For the production core, pair memory operations should therefore remain one
ROB identity with two LSU lanes/subrequests and an explicit lane-completion
mask.  The benchmark-autonomous top's direct sparse-memory lookup is adequate
for ISA bring-up, but should not be copied as the cache-facing LSU protocol.

## Verification gates

- `FrontendDecodeStageSpec`
- `GPRReservationTrackerSpec`
- `GPRRenameCheckpointSpec`
- `ReducedScalarAluExecuteSpec`
- `DecodeRenameROBPathSpec`
- `TULinkRenameSpec`
- `ScalarTURenameBridgeSpec`
- `TULinkRetireCommandPathSpec`
- `tests/test_benchmark_autonomous_natural.py`
- shell syntax check for the natural benchmark runner
- `git diff --check`

The retire-command test samples command and observable queue state before the
active edge.  Its expected snapshots follow that timing contract; the RTL
remains registered and has no test-only combinational bypass.

## Remaining risks and next production milestones

1. Integrate D3 reservation, row fill, and recovery qualification into the
   production decode/rename/ROB path and verify wrap/replay/flush behavior.
2. Replace autonomous sparse-memory pair handling with production LSU
   subrequests, ordering, exception aggregation, and lane completion.
3. Validate caches, MMU, coherence, atomics, and architectural exceptions with
   the same QEMU/Sail differential method.
4. Extend frozen-ELF coverage beyond these integer workloads, especially the
   scalar FP and broader template/vector opcode surfaces.
5. Preserve explicit trace schema for pair effects so differential tooling
   compares both architectural lanes instead of relying on a single projected
   commit row.
