# LinxCore Chisel Cross-Stack Audit

Date: 2026-07-23

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
