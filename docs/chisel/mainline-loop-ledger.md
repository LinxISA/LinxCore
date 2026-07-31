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

## Loop 6 — B-SIDE prediction and IFU recovery consolidation

- Scope: Task 6 only. This loop creates the public `linxcore.ifu.BSide`,
  `Prediction`, and `IFURecovery`/`IFUBackendFeedback` boundaries, wires
  `LinxCoreIfu` through those names, and keeps the public `IFU` W2/W4/W6/W8
  fixed-64-bit delivery surface unchanged. It does not add a second predictor,
  history queue, redirect arbiter, or backend feedback state owner.
- Baselines: implementation started from LinxCore
  `833c354cb38a3e3d2d47226a62c4b4e2b2f03dbb`; superproject
  `54635e8cb1119e5f199228cb7db330b168bf7dc0`; LinxCoreModel
  `31555f49dbb020c8eb9f26f7df98310a7415b69d`; QEMU
  `c9f9570aa70da7e193ff8857bd9bde2cf052e546`.
- Governing clauses: `IFU-001..008`, `MEC-IFU-001..006`,
  `IFC-IFU-CTU-001`, `MEC-IFU-CTU-001`, `IFC-RECOVERY-001`,
  `VER-IFU-001..005`, and the interface manifest clause. Interface Bundle
  shapes did not change, so the generated top-interface manifest is a check
  input rather than a regenerated artifact.
- Interface fanout: producer `IFU`/`ISide`/`BSide`; consumers CTU, external
  memory, and OOO-authored recovery/validation producers. Backend facts enter
  IFU only as OOO-authored validation or typed recovery data; there is no
  direct IEX-to-IFU control input.
- RED evidence: `IFUPredictionSpec` and `IFURecoverySpec` first failed
  compilation with missing `BSide`, `IFURecovery`, and `IFUBackendFeedback`
  symbols, proving that the public boundary did not exist.
- GREEN evidence: `IFUPredictionSpec` passes six checks covering the complete
  Sequential/B-F0/B-F1/B-F2/B-F3/B-F4 provider order, stale training rejection
  without predictor mutation, exact checkpoint-owned GHR recovery, canonical
  RAS recovery, exact public B-SIDE training, and public B-SIDE elaboration.
  The B-F3 case drives real public behavior: B-F2/BIM predicts taken, the test
  returns the canonical prune, and B-F3/ShortTage corrects to not-taken before
  final seal. `IFURecoverySpec` passes four checks covering
  backend-over-prediction priority, retained redirect hold under backpressure,
  structurally typed OOO-authored feedback with no IEX control port, and atomic
  mispredict training plus backend recovery. `IFUCTUIntegrationSpec` passes two
  checks whose behavioral traffic, retained backpressure, scoped recovery
  fence/apply, and fixed-64-bit payload assertions all execute in
  W2/W4/W6/W8; elaboration also exposes the explicit B-SIDE/recovery modules in
  every profile.
- Adjacent evidence: `IFUISideSpec`, `CTUSpec`, interface/manifest/NDF checks,
  Chisel build, Verilator lint, and diff hygiene are recorded in the Task 6
  report after the full wrapper ladder completes.
- Result: prediction remains speculative and owned below one B-SIDE boundary;
  typed recovery remains singular and overrides unpublished prediction
  corrections through the canonical redirect owner. The IFU-to-CTU payload
  remains the same complete fixed-64-bit fetched-packet interface from Loop 5.
- Independent review: round one required real lower-provider, checkpoint/state,
  and W2/W4/W6/W8 behavioral coverage. Round two accepted those repairs but
  rejected a constant-only B-F3 rank check. Systematic debugging then proved
  that B-F3 is intentionally silent when ShortTage agrees with B-F2 and that
  B-F4 waits for exact boundary metadata; the final public-path regression
  forces a legal BIM/ShortTage direction conflict. Round three reports zero
  critical, important, or minor findings.
- Remaining gap: public IFU exposes typed recovery today; live backend
  validation/training waits for the later TOP/OOO integration packet to supply
  the OOO-authored producer. Natural ELF/commit evidence remains Tasks 18–19.
- skill-evolve: no-update — the reusable invariants are already covered by the
  existing LinxCore workflow: single state owner, exact identity matching,
  retained ready/valid, and OOO-owned recovery authority.
- Branch: `codex/chisel-gap-superpowers`
- Commits: `7b1319e4..fdb1078e`, beginning with the Lore intent
  `Keep prediction speculative while recovery authority remains singular` and
  ending with the independently approved B-F3 behavioral proof.
- Push target: `origin/codex/chisel-gap-superpowers`; controller will push.

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

## Loop 5 — IFU I-SIDE and fixed-64-bit delivery

- Scope: Task 5 only, plus a planning-only interface-refinement amendment.
  This loop creates the public `IFU`, reuses the existing I-SIDE lookup,
  miss/refill, cross-line, prediction-join, and epoch owners, adds the retained
  canonical Fetch Buffer, and exposes typed instruction-memory traffic. It
  does not extract the final B-SIDE owner or connect IFU to CTU in TOP.
- Baselines: implementation started from LinxCore
  `3bcbb841455b2b74be672f189ef9bbd222ed45c2`; the interface work-order
  amendment is `31d1f98ede5e9370361781334bfae5b37184114f`;
  superproject `54635e8cb1119e5f199228cb7db330b168bf7dc0`,
  LinxCoreModel `31555f49dbb020c8eb9f26f7df98310a7415b69d`, and QEMU
  `c9f9570aa70da7e193ff8857bd9bde2cf052e546`.
- Governing clauses: `IFC-IFU-CTU-001`, `MEC-IFU-CTU-001`,
  `IFC-MEMORY-001`, `MEC-MEMORY-001`, `IFU-001..007`,
  `MEC-IFU-001..005`, and `D-IFU-001`. One-hop dependencies cover width
  profiles, prefix packets, exact identity, CTU/OOO ownership, recovery, and
  observational trace.
- Interface fanout: producer `IFU`/`ISide`; consumers CTU and external memory;
  canonical payloads `FetchedPacket`, `MemoryRequestTxn`,
  `MemoryResponseTxn`, and `RecoveryTargetIO`; central checks in
  `ParamChecks`; generated manifest and IFU/interface/CTU adjacent tests.
  TOP wiring remains intentionally not applicable until Task 17.
- Skills: repository-pinned `using-superpowers`, `executing-plans`,
  `test-driven-development`, `systematic-debugging`,
  `requesting-code-review`, `verification-before-completion`, and the
  `linx-core` domain workflow.
- RED evidence: `IFUISideSpec` first failed compilation with sixteen missing
  `IFU`, `ISide`, `FetchBuffer`, and parameter symbols. A later configuration
  test proved that a trace packet narrower than the IFU prefix had no guard.
  The first public two-STID extension also showed that holding all older STID0
  output prevents observing younger STID1 output; allowing the ordered prefix
  to drain proves the intended independent forward progress. A trace-stall
  extension then proved that the first trace implementation incorrectly
  consumed architectural delivery credit. Finally, a directed denied-line
  response timed out because the adapter substituted zero data instead of
  publishing a fetch fault.
- GREEN evidence: `IFUISideSpec` passes four checks: retained W2/W4/W6/W8
  repacketization with stable stall and 2/4/6/8-byte lengths; scoped pruning
  with a legal two-STID profile; W2/W4/W6/W8 public IFU elaboration; and a
  public two-STID flow covering ITLB replay, I-cache refill, stale-response
  rejection, cross-line assembly, exact prepare/apply, unrelated survivor
  retention, redirected nonzero-STID progress, recovery-fence release,
  non-blocking stable trace, and denied-line conversion to an exact
  `fetchFault` with its original cause.
  `CoreConfigurationSpec` passes ten checks including the new trace-width
  constraint.
- Adjacent evidence: the unchanged private IFU path passes all seven
  `LinxCoreIfuSpec` scenarios; `CTUSpec` passes eleven checks;
  `TopInterfaceSpec` passes eight; `InterfaceManifestSpec` passes two; the
  generated manifest is exact; the Chisel build and existing-top Verilator
  lint pass. The NDF checker tests pass ten of ten and the live profile reports
  86 clauses, 37 L1 MUST clauses, 38 verification targets, zero open questions
  and two pinned references.
- Independent review: the first pass found two high-priority issues:
  instruction-line errors were converted to zero data, and the then-current
  trace-stall/multi-STID test was red. The line-fault owner, non-blocking trace
  path, directed regressions, and `D-IFU-001` resolve both. The second pass
  reports zero critical, high, medium, or low findings and independently
  reruns `IFUISideSpec` at four of four, `CoreConfigurationSpec` at ten of ten,
  the manifest check, and the live NDF profile.
- Debug evidence: the first profile-elaboration test used four full simulator
  builds and was replaced with direct SystemVerilog elaboration. The first
  stable-payload assertion attempted `asUInt` outside a hardware module and
  was replaced by field snapshots. A one-STID dynamic index warning was
  removed with a static Scala branch. The final two-STID elaboration still
  reports three pre-existing aggregate-enum cast warnings inside the retained
  F0 prediction-context selector; Task 6 owns that B-SIDE/F0 consolidation.
- Result: IFU now presents one retained `count + Vec` stream of complete
  64-bit instruction containers independent of its four-lane private assembly
  geometry. Translation and line requests have explicit access kinds and
  exact generation-qualified response matching. A denied/corrupt line beat
  cannot install substituted or partial L1I data and instead retains one
  canonical fault with the original cause. Trace loss cannot consume IFU-to-CTU
  credit. The canonical payload carries architectural fetch, prediction, and
  fault identity only; CTU preserves it, and OOO remains the ROB/BROB
  allocation owner.
- Remaining gap: Task 6 must extract/consolidate B-SIDE prediction, remove the
  retained enum-cast warnings, and close final IFU recovery integration.
  IFU-to-CTU adjacent composition remains Task 6, and natural ELF/commit
  evidence remains Tasks 18–19.
- skill-evolve: no-update — exact identity, retained ready/valid,
  generation-qualified stale rejection, scoped recovery, and single-state
  ownership are already established LinxCore workflow rules.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit with intent
  `Make fixed-width instruction delivery independent of fetch geometry`.
- Push target: `origin/codex/chisel-gap-superpowers`; exact commit and remote
  equality are recorded in the loop handoff after the immutable commit exists.
