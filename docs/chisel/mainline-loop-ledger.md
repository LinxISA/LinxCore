# Chisel mainline execution ledger

This ledger records the evidence and workflow boundary of each implementation
loop in
`docs/superpowers/plans/2026-07-31-core-mainline-restructure.md`. Commit and
push identities are filled only after the corresponding operation succeeds.

## Loop 1 — NDF contract spine and reference boundary

- Scope: Task 1 only; documentation contracts and their executable checker.
- Skills: repository-pinned `using-superpowers`, `executing-plans`,
  `test-driven-development`, `systematic-debugging`,
  `verification-before-completion`, and the `linx-core` domain workflow.
- Workflow: confirm a clean dedicated branch; run the existing Chisel baseline;
  add checker tests; observe the expected missing-checker RED; implement the
  smallest dependency-free checker; create the live clause spine and pinned
  reference projections; run focused, local-reference, and Chisel regression
  gates.
- RED evidence: `python3 -m unittest tests.test_ndf_profile -v` failed all
  seven cases because `tools/spec/check_ndf_profile.py` did not exist.
- GREEN evidence: nine checker tests pass; the live profile reports 27
  clauses, 13 L1 MUST clauses, 13 covered requirements, zero open questions,
  and two pinned structural-method references. Strict local-reference
  verification reports the same result. `bash tools/chisel/build_chisel.sh`
  completes successfully.
- Debug evidence: strict source verification initially treated a checkout HEAD
  as the pinned Git identity. Read-only inspection proved that the pinned
  commit object existed on another ref. A new regression test now requires
  object identity rather than working-branch identity. A second regression
  test ensures malformed local-reference metadata reports validation errors
  without a Python traceback.
- Result: the contract hierarchy, ownership rules, pipeline boundary, reference
  boundary, and executable profile check are established without changing RTL.
- Remaining gap: RTL still follows the pre-restructure hierarchy. Task 2 owns
  the centralized parameter profiles and is the next loop.
- skill-evolve: no-update — this loop adds a repository-specific documentation
  profile and does not discover a reusable LinxCore hardware invariant beyond
  the existing domain skill.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit with intent
  `Make the core contract independently reviewable before RTL moves`.
- Push target: `origin/codex/chisel-gap-superpowers`; exact commit and remote
  equality are recorded in the loop handoff after the immutable commit exists.

## Loop 2 — Central parameters and configurable profiles

- Scope: Task 2 plus the NDF interface-contract refinement requested while the
  loop was active; no box state or datapath was migrated.
- Skills: repository-pinned `using-superpowers`, `executing-plans`,
  `test-driven-development`, `verification-before-completion`, and the
  `linx-core` domain workflow.
- Workflow: add the W2/W4/W6/W8 profile test first; observe missing central
  parameter types; implement immutable module records and cross-parameter
  checks; add value-only legacy adapters behind their own RED/GREEN cycle;
  expose and close the old OOO W8 sizing gaps; project parameter options,
  coupling and verification into the local NDF profile; expand Task 3 with
  boundary homes and L1/L2/L3 interface acceptance.
- RED evidence: `CoreConfigurationSpec` first failed because
  `linxcore.params`, profiles and adapters did not exist. A later adapter test
  failed first on the old 2/4/6 decode-width restriction, then on W8
  store-commit and PC-bank capacity. The new NDF coupling test failed because
  `couples-with` was not yet a checked edge.
- GREEN evidence: `CoreConfigurationSpec` passes 9 tests, including all four
  profiles, invalid combinations, topology defaults and value-only adapters.
  `OooParamsSpec` passes 3 tests including W8 decode construction.
  `CoreParamsInterfaceClosureSpec` passes 2 compatibility tests.
  The 10 NDF-checker tests pass; the live profile reports 37 clauses, 17 L1
  MUST clauses, 18 verification targets, zero open questions and two pinned
  references under both ordinary and strict local-reference checks.
  `bash tools/chisel/build_chisel.sh` completes successfully.
- Result: the central parameter boundary now owns principal widths, module
  resources and validation. W4 preserves the requested IEX/LSU topology;
  W2/W4/W6/W8 are constructible profiles. Temporary adapters derive dependent
  legacy capacities but own no state.
- Interface-plan refinement: Task 3 now freezes eight contract homes
  (IFU–CTU, CTU–OOO, OOO–IEX, IEX–LSU, recovery, commit, DTU and memory),
  requires stable NDF IDs and typed edges, and separates L1 observable
  contracts, L2 Bundle mechanisms and L3 executable checks. Generated
  manifests remain projections of elaborated Bundles rather than a competing
  field source.
- Remaining gap: the typed Bundles, complete box IOs and generated manifest do
  not exist yet. Task 3 owns that implementation.
- skill-evolve: no-update — width/resource coupling is specific to this core;
  no new reusable invariant is needed in the shared LinxCore skill.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit with intent
  `Keep every width and resource choice explicit at one boundary`.
- Push target: `origin/codex/chisel-gap-superpowers`; exact commit and remote
  equality are recorded in the loop handoff after the immutable commit exists.

## Loop 3 — Typed TOP interfaces and generated manifest

- Scope: Task 3 only, plus the smallest wrapper repair needed to execute the
  required generated-RTL lint; no box state or datapath was migrated.
- Skills: repository-pinned `using-superpowers`, `executing-plans`,
  `using-git-worktrees`, `test-driven-development`,
  `systematic-debugging`, `requesting-code-review`,
  `verification-before-completion`, and the `linx-core` domain workflow.
- Workflow: establish eight interface contract homes; observe the missing-L3
  coverage failure; add Bundle tests and observe the missing-type compile
  failure; implement canonical transactions and box IOs; generate JSON and
  Markdown projections from the canonical payload Bundles; run focused,
  compatibility, build, NDF, generated-file, and Verilator gates; resolve an
  independent code review before closeout.
- RED evidence: the NDF checker reported exactly eight missing verification
  edges for the new L1 interface requirements. `TopInterfaceSpec` then failed
  because `FetchedPacket`, `D1Packet`, identities, uops, boundary IOs, and the
  manifest model did not exist.
- GREEN evidence: the interface suite passes eight checks across W2/W4/W6/W8,
  count-only prefixes, 64-bit instruction containers, generation-qualified
  identities, configurable native BID slots, P/T/U rename tags, independent
  CMD and LDA/STA/STD paths, prepare/prepared/apply/abort recovery, retained
  payload assertions, and aggregate IO elaboration. The manifest suite passes
  two checks and `render_top_interface_manifest.py --check` reports exact
  generated JSON/Markdown parity. The NDF profile reports 58 clauses, 25 L1
  MUST clauses, 26 verified targets, zero open questions, and two pinned
  references.
- Compatibility evidence: the existing `InterfaceBundles` suite passes nine
  tests and `CoreConfigurationSpec` passes nine tests. The standard Chisel
  build and full existing-top Verilator lint pass. These are no-regression and
  structural evidence only; this loop does not claim new-mainline composition
  or workload replacement.
- Debug evidence: the first full lint attempt exhausted a 1 GiB JVM because
  `emit_verilog.sh` did not consume the validated
  `LINX_CHISEL_SBT_MEM_MB` boundary used by the build and test wrappers. The
  wrapper now sources the shared environment and passes the configured heap to
  the server invocation. After one clean cache restoration, elaboration and
  Verilator lint complete successfully.
- Review evidence: independent review found that the first manifest omitted
  recovery `prepared` and `abort`, and that native BID was fixed at eight bits.
  The manifest now covers the complete recovery target protocol and guards its
  channel set against `RecoveryTargetIO`; native BID width derives from the
  configurable per-STID BROB slot count while BROB generation remains
  independent. Directed 64- and 512-slot tests prove six- and nine-bit BID
  shapes.
- Result: every cross-box payload has one canonical class under
  `top/interface`; each box and TOP has one directioned IO aggregate; every
  manifest endpoint names an NDF contract home; and checked-in generated
  projections are rejected when they drift from the Scala model.
- Remaining gap: the new IO classes are not yet connected to box
  implementations. Task 4 owns CTU and its retained Instruction Buffer before
  IFU/OOO composition begins.
- skill-evolve: no-update — exact identity separation, recovery
  prepare/apply, retained ready/valid payloads, and the 4 GiB wrapper contract
  are already present in the LinxCore workflow; this loop applies them to the
  new interface boundary.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit with intent
  `Give every box one typed contract and one direction of authority`.
- Push target: `origin/codex/chisel-gap-superpowers`; exact commit and remote
  equality are recorded in the loop handoff after the immutable commit exists.

## Loop 4 — CTU template descriptions and retained Instruction Buffer

- Scope: Task 4 only. This loop creates the new CTU box, canonical template
  child descriptions, retained D1 buffering, CTU behavior clauses, and focused
  verification. It does not connect IFU or OOO and does not migrate ROB,
  BROB, rename, issue, or LSU state.
- Baselines: LinxCore `34e337d393893e095361457eb639a7eb2c572694`,
  superproject `54635e8cb1119e5f199228cb7db330b168bf7dc0`,
  LinxCoreModel `31555f49dbb020c8eb9f26f7df98310a7415b69d`, and
  QEMU `c9f9570aa70da7e193ff8857bd9bde2cf052e546`.
- Skills: repository-pinned `using-superpowers`, `executing-plans`,
  `test-driven-development`, `systematic-debugging`,
  `requesting-code-review`, `verification-before-completion`, and the
  `linx-core` domain workflow.
- Workflow: inspect the generated opcode catalog and existing canonical
  template row definitions; add CTU and Instruction Buffer tests first;
  observe the missing-module RED; implement catalog-backed template
  recognition, child descriptors, retained packetization, scoped recovery and
  trace; replace an expensive square compaction network with ordered sparse
  slots; add L1/L2 behavior clauses, observe missing L3 coverage, then add
  executable conformance edges; run focused, adjacent, build, profile and
  generated-RTL gates; resolve independent review before closeout.
- RED evidence: `CTUSpec` and `InstructionBufferSpec` first failed compilation
  because `CTU`, `TemplateDecode`, `TemplateExpand`, and the retained
  `InstructionBuffer` did not exist. The NDF checker later reported five
  missing `verifies` edges for `CTU-001..005` and exposed one misspelled
  ownership reference.
- GREEN evidence: `CTUSpec` passes eleven checks covering width-wide ordinary
  transfer, complete identity/prediction retention, FENTRY/FEXIT/FRET recipes,
  malformed-range fallback, multi-packet expansion, cross-parent ordering,
  stable backpressure, exact prepare/apply and abort recovery, retained trace,
  W2/W4/W6/W8 packetization, and absence of backend state owners.
  `InstructionBufferSpec` passes four new checks; its wildcard gate also keeps
  thirteen existing frontend buffer checks green. `TopInterfaceSpec` passes
  eight checks. The standard Chisel build and existing-top Verilator lint pass.
  The NDF profile reports 69 clauses, 30 L1 MUST clauses, 31 verified targets,
  zero open questions and two pinned references.
- Independent review: a separate code-review pass inspected all four CTU RTL
  owners, both focused test suites, both NDF documents, and the adjacent opcode
  metadata state. It reported zero blocker, high, or medium findings and
  independently reran `CTUSpec` at eleven of eleven and `TopInterfaceSpec` at
  eight of eight.
- Debug evidence: the first implementation used a self-referential decode
  match wire, which FIRRTL correctly rejected as a combinational cycle. A
  catalog-order expression chain removed the cycle. The first exact recovery
  compactor selected every destination from every retained 64-bit operation
  and made one CTU test run take almost sixteen minutes. Ordered sparse slots
  with one 64-bit enqueue order per entry reduce selection to
  `ctuOutputWidth × instructionBufferEntries`; the same suite completes in
  under two minutes while preserving target-STID invalidation and unrelated
  order.
- Result: ordinary and template-derived operations share one retained D1
  stream. Template children retain their raw parent and describe only row
  identity, ordinal, count and child immediate; OOO remains responsible for
  D1/D2 validation and D3 resource reservation. IFU readiness is registered
  away from OOO readiness through packet retention and pre-cycle buffer
  credit.
- Remaining gap: CTU is not yet connected to the new IFU and OOO boxes.
  Tasks 5–7 own I-SIDE delivery, B-SIDE/recovery composition, and D1/D2
  validation plus ROB admission.
- skill-evolve: no-update — this loop applies existing generated-decode,
  retained ready/valid, exact recovery, and single-owner rules. It adds no
  reusable invariant beyond the installed LinxCore workflow.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit with intent
  `Make template expansion a retained boundary instead of frontend glue`.
- Push target: `origin/codex/chisel-gap-superpowers`; exact commit and remote
  equality are recorded in the loop handoff after the immutable commit exists.
