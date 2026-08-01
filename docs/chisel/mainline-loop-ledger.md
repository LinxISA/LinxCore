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

## Loop 9 — Canonical ROB, BROB, commit, and precise recovery authority

- Scope: Task 9 only, including twelve review/fix rounds needed to close the
  canonical owner semantics before any Task-10 dispatch or TOP cutover. The
  loop adds `linxcore.ooo.ROB`, `BROB`, `CommitControl`, and
  `RecoveryControl`; it does not integrate the old `OooO3RenameCoordinator`
  or change live TOP ownership.
- Baseline and references: the final round starts from the reviewed round-11
  commit `83c86230` plus the atomic-cutover plan commit `989fc583`. NDF
  revision `09cfe646931183caee82dd913f77f516b82134df` supplies stable clause
  identity and L1/L2/L3 verification edges; Linx semantics remain repository
  owned.
- Governing clauses: `OOO-010..013`, `MEC-OOO-006`, `VER-OOO-003`,
  `IFC-RECOVERY-001`, `MEC-RECOVERY-001`, `IFC-COMMIT-001`, and
  `MEC-COMMIT-001`, together with the exact identity and continuous-prefix
  contracts.
- RED evidence: initial Task-9 tests failed because the four canonical owners
  did not exist. Review rounds then exposed release-before-apply, preview
  mutation, bank geometry, age-token ambiguity, stale/mismatched recovery
  responses, compact-suffix endpoint errors, closed-block straddling, wrapped
  tail repair, unbound BROB publication, and non-causal target acknowledgements.
  Round 12 finally observed real ROB/BROB coordinated publication blocked at
  the first unbound D3 packet and a matching target beat held before Prepare
  incorrectly triggering Apply on retry.
- GREEN evidence: `OOORobCommitSpec` passes 21/21 and `OOORecoverySpec`
  passes 43/43. The latter uses a real ROB/BROB coordinator to prove
  allocator-authored BID 0/1/2/3, same-packet members, BID wrap and BROB
  generation, resident-generation reuse, release, and suffix recovery. A
  same-DUT held-beat/abort/retry test proves only a fresh causal target response
  can authorize Apply.
- Cross-gate evidence: ROBID passes 3/3; the generated BROB order-state probe
  passes; ROB bookkeeping commits 4623 rows; `TopInterfaceSpec` passes 9/9;
  `InterfaceManifestSpec` passes 2/2; the generated interface manifest is
  exact; the NDF profile reports 113 clauses, 52 L1 MUST clauses, 59 verified
  targets, zero open questions, and two verified references. The forbidden
  external-narrative scan is empty. Standard Chisel build and Verilator 5.044
  lint pass.
- Ownership result: ROB owns grouped resident identity, exact completion,
  ordered preview/retire/release, candidate status, and compact suffix
  recovery. BROB owns per-STID BID/generation allocation and block lifecycle.
  Their prepare graph exchanges allocator-authored BROB binding and
  ROB-authored resident identity, then mutates only on one common fire.
  CommitControl owns the retained all-owner commit authorization.
  RecoveryControl owns the single ROB-authored plan and causal all-target
  prepare barrier; stale/early beats are drainable but never acknowledgement
  authority.
- Independent review: the first round-12 pass found one HIGH test-strength
  issue because the held target beat regression adapted to ready instead of
  requiring pre-Prepare drain. The corrected test asserts ready before the
  state transition, proves the drain, and passes its 3/3 focused subset. Final
  re-review reports zero remaining findings and verdict `APPROVE`.
- Remaining gap: these owners remain standalone. Task 10 freezes the checked
  production-owner/call-site/deletion manifest; Task 11 then atomically cuts
  RENU D3/S1 dispatch onto these owners without importing the duplicate O3
  coordinator.
- skill-evolve: no-update — all twelve rounds apply existing exact-identity,
  single-owner, retained ready/valid, causal recovery, parameterization, and
  verification rules already captured by the LinxCore skill.
- Branch: `codex/chisel-gap-superpowers`
- Commits: Task-9 history is retained in the branch; the round-12 Lore commit
  has intent `Bind every ROB resident to causal BROB and recovery authority`.
- Push target: `origin/codex/chisel-gap-superpowers`; exact commit and remote
  equality are recorded after the immutable commit exists.

## Loop 10 — Checked production-owner cutover manifest

- Scope: Task 10 Steps 2–4 only. Task 9 Step 1 is already closed by pushed
  commit `2758fb00`; this loop does not modify `ROB.scala`, `BROB.scala`,
  `RecoveryControl.scala`, or `OOORecoverySpec.scala` and does not begin the
  Task-11 OOO dispatch cutover.
- RED evidence: the exact command
  `python3 -m unittest tests.test_production_owner_manifest -v` first failed
  with `ModuleNotFoundError: No module named
  'tests.test_production_owner_manifest'`. After the fixture suite was added,
  the same command failed because
  `tools/chisel/check_production_owner_manifest.py` did not exist.
- GREEN evidence: seven real CLI/temporary-repository tests pass. They prove
  acceptance of a complete manifest and rejection of duplicate ROB, rename,
  IQ, LSU-pipeline, cache, and recovery owners; an unknown scanned Scala
  emitter; missing production evidence; a stateful adapter; a deletion target
  with active callers; and missing NDF L1/L2/L3/interface-manifest homes.
- Checked baseline: the repository manifest records 23 IFU/CTU/OOO/IEX/LSU/
  DTU state categories and classifies all 40 scanned Chisel emitters. The
  checker reports one owner per state key and an executable NDF L1/L2/L3
  mapping. The generated TOP interface manifest is current. Strict NDF local
  reference verification reports 113 clauses, 52 L1 MUST clauses, 59 verified
  targets, zero open questions, and two references. `git diff --check` passes.
- Ownership result: Task-9 `PRename`, `TURename`, `ROB`, `BROB`,
  `CommitControl`, and `RecoveryControl` stay canonical but standalone until
  Task 11. Production IEX and LSU mechanisms are named in place rather than
  copied behind new stateful boxes. Evidence status distinguishes public-box,
  standalone, and mechanism-only proof from later canonical-TOP promotion.
- Entry-point result: `Reduced*`, `*Probe`, and old `LinxCore*Top` emitters are
  executable fixtures or legacy paths, never production entry points. No
  production `TOP` emitter is claimed before Task 17.
- Independent-review fix round 1: 17 real CLI tests now close six manifest
  bypass classes. Schema v2 uses a checker-owned closed inventory for all 23
  state domains and exact canonical symbols/primary mechanisms. It discovers
  deletion-target callers and accepts live pre-cutover targets only as
  `planned-active` with an exact caller set; only caller-free targets may be
  `deletion-ready`. It also discovers and classifies four existing adapter-
  named mechanisms, distinguishes public Modules from pending IEX/LSU/DTU
  boxes, checks evidence/reachability obligations, owns emitter classification
  across App/`def main`/`@main`/wrapper forms, and enforces repo-contained typed
  NDF layer roles without path reuse.
- Independent-review fix round 2: 29 real CLI tests close the six remaining
  bypasses. Scala caller discovery strips comments and literals and resolves
  braced import aliases. Every state domain now has an exact checker-owned
  `(symbol, path)` mechanism set. Twelve exact managed boundaries cover
  Adapter, Wrapper, and state-owning Bridge classes, including transitive
  stateful children; each legacy exception is tied to a domain, cutover task,
  and exact deletion target. `production-promoted` fails closed until Task 17
  owns its activation-artifact schema. All 40 emitters have exact FQCN/path/
  classification registrations, including cross-file wrapper reachability;
  names cannot self-grant an exemption. NDF roles reject symlinks and detect
  hard-linked identity reuse by device/inode. The live checker passes in 6.6
  seconds after emitter propagation was reduced to one definitions/calls index
  per Scala source. A final regression also proves a registered non-production
  emitter cannot be moved into the production list without Task-17 authority.
- Remaining boundary: Task 11 must update this manifest in the same atomic
  commit that changes OOO D3/S1 call sites and deletes the displaced legacy
  owner chain. The controller owns push and remote-equality confirmation before
  Task 11 begins.
- skill-evolve: no-update — the loop makes the existing single-owner,
  no-stateful-adapter, production-evidence, and NDF-layer rules executable; it
  introduces no new reusable LinxCore hardware invariant.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit has intent
  `Freeze one production owner before changing another boundary`.
- Push target: `origin/codex/chisel-gap-superpowers`; controller review and
  push are pending.

## Loop 7 — Canonical DEC and retained OOO D1/D2 admission

- Scope: Task 7 only, preceded by the NDF/interface planning correction in
  `89f081ed`. This loop adds the canonical combinational DEC and public OOO
  D1/D2 slice. It does not allocate physical ROB/BROB state, rename registers,
  dispatch to issue queues, or integrate the box in TOP.
- Baseline: implementation started from LinxCore `5780f40d`; the plan pins the
  SuperscalarNPU reference boundary and normative_language/NDF revision
  `09cfe646931183caee82dd913f77f516b82134df`, including `option`, `default`,
  `explore`, and `couples-with` semantics.
- Governing clauses: `IFC-CTU-OOO-001`, `MEC-CTU-OOO-001`, `OOO-001..005`,
  `MEC-OOO-001..002`, `D-PREFIX-001`, `D-IDENTITY-001`, `ARC-TOP-020..021`,
  `PRM-WIDTH-001`, `VER-IFC-001`, `VER-ARC-005..006`, `VER-OOO-001`,
  `REF-NDF-001`, and `REF-SNPU-001`.
- RED evidence: `OOODecodeSpec` first failed to compile because `DEC`, `OOO`,
  and `D2AdmissionGroup` did not exist. A template fetch-fault regression then
  observed `trap.valid=0`. Independent review exposed a legal CTU repacking
  case that combined an ordinary instruction with template children while D2
  was occupied; the real CTU-to-OOO test timed out after 64 cycles because DEC
  rejected the mixed tagged union and held ready low.
- GREEN evidence: `OOODecodeSpec` passes 8/8 checks for 16/32/48/64-bit
  normalization, template children, typed traps, mixed-lane fusion isolation,
  full-width virtual RID identity, recovery and W2/W4/W6/W8 elaboration.
  `CTUOOOIntegrationSpec` passes 3/3, including retained backpressure followed
  by exact in-order draining of a repacked ordinary-plus-template prefix.
  `OooD1DecodeSpec` passes 12/12 and proves the legacy dense-prefix assertion
  remains the default; `CTUSpec` passes 11/11; `TopInterfaceSpec` passes 9/9;
  and `InterfaceManifestSpec` passes 2/2.
- Generated/spec evidence: opcode generator tests pass 6/6 and decode parity
  covers 686 forms and 678 mnemonics. The generated interface manifest is
  current and unchanged because the existing CTU-to-OOO endpoint remains a
  `D1Packet`; the Task-7 slice adds no canonical TOP endpoint yet. The NDF
  profile reports 97 clauses, 43 L1 MUST clauses, 46 verified targets, zero
  open questions, and two verified local references.
- Build evidence: the standard Chisel build passes. Generated TOP elaboration
  and Verilator 5.044 lint pass. `git diff --check` passes and the opcode parity
  generator's pre-existing `xb` row side effect was restored rather than
  admitted into this loop.
- Ownership result: all public D1/D2 payloads and `OOOD1D2IO` live under
  `top/interface`. DEC owns no state. D2 retains immutable virtual RID group
  and member intent from full-width tail snapshots but never advances the
  tail. `residentBound` and `brobBound` remain false; BID, BROB generation and
  resident generation are explicitly unallocated. Recovery prepare fences one
  STID, matching apply cancels only that row, and abort releases the fence
  without mutation.
- Independent review: the first pass found two blocking design defects: mixed
  CTU packets could deadlock admission, and public OOO interface types were in
  the implementation package. The same implementer repaired both under TDD.
  A fresh final reviewer inspected `89f081ed..b46b0e83`, independently ran the
  NDF and diff checks, and reported 0 critical, 0 important, 0 minor findings
  with verdict `APPROVE`.
- Remaining gap: Task 8 owns D3 rename and resource reservation. Physical
  P/T/U mapping, SMAP/CMAP/MAPQ, freelists, unique ROB/BROB binding, early ROB
  completion and S1 dispatch remain absent. TOP integration remains Task 17;
  natural Dhrystone/CoreMark and scalar linx-avs evidence remain Tasks 18–19.
- skill-evolve: no-update — single-owner allocation, full identity retention,
  retained ready/valid, exact recovery and generated-decode rules already
  exist in the LinxCore domain workflow.
- Branch: `codex/chisel-gap-superpowers`
- Commits: plan correction `89f081ed`, initial implementation `54c05d72`, and
  reviewed mixed-packet/interface repair `b46b0e83`.
- Push target: `origin/codex/chisel-gap-superpowers`; controller push and
  remote equality are the final loop handoff.

## Loop 8 — D2/D3 rename with separate P and T/U owners

- Scope: Task 8 only. This loop adds the D2-to-D3 RENU slice, public
  `OOOD2D3` payloads, and central rename generation parameters. It does not
  instantiate ROB/BROB physical binding, S1 dispatch, issue queues, LSU
  allocation, or TOP end-to-end benchmark flow.
- Reference/contract boundary: NDF revision
  `09cfe646931183caee82dd913f77f516b82134df` supplies stable clause identity,
  L0-L3 refinement, typed graph edges, history and verification coverage.
  SuperscalarNPU supplies only typed-interface grouping and reviewable TOP
  wiring patterns. Neither source defines Linx payload semantics or RTL.
- Governing clauses: `ARC-TOP-021`, `OOO-006..009`, `PRM-RENAME-001`,
  `MEC-OOO-003..005`, `VER-OOO-002`, `VER-PRM-004`, plus the Task-7 D1/D2
  clauses and continuous-prefix identity rules.
- RED evidence: the expanded rename suite first ran 15 checks with 10 passing
  and 5 failing. It exposed relative-source underflow acceptance, incorrect
  same-uop WAW history, non-survivor recovery, missing cross-domain release
  atomicity, and conflated physical/MapQ generations. Later directed REDs
  exposed malformed release and D2 prefixes, and the final backpressure RED
  observed `recovery.prepare.ready=1` where a held target D3 row required 0.
- GREEN evidence: `RENUSpec` passes 15/15 checks, `RENUAtomicSpec` passes 7/7,
  and `TURenameSequenceSpec` passes 4/4. Together they cover D3-only
  publication, P RAW/WAW forwarding, independent T/U physical and sequence
  domains, exact all-or-none release, capacity exhaustion, stale generations,
  zero-destination sidecars, STID-scoped survivor recovery, held-target
  irrevocability, unrelated-STID progress, unequal capacities and behavioral
  W2/W4/W6/W8 publication. `OOODecodeSpec` passes 8/8 and
  `CTUOOOIntegrationSpec` passes 3/3 without integrating RENU into the OOO box.
- Interface/parameter evidence: `TopInterfaceSpec` passes 9/9,
  `CoreConfigurationSpec` passes 12/12, `OooParamsSpec` passes 4/4,
  `CoreParamsInterfaceClosureSpec` passes 2/2, and `InterfaceManifestSpec`
  passes 2/2. The generated TOP interface manifest is an exact current
  projection. The NDF profile reports 107 clauses, 48 L1 MUST clauses, 54
  verified targets, zero open questions, and two verified local references.
  The forbidden external-architecture-name scan under `docs/spec` is empty.
- Build evidence: `bash tools/chisel/build_chisel.sh` passes. Generated TOP
  elaboration and Verilator 5.044 lint pass. `git diff --check` is clean.
- Ownership result: `PRename` is the P SMAP/CMAP/free/generation/MapQ owner.
  `TURename` separately owns T and U physical cursors, sequence generations
  and MapQs. `RENU` owns only per-STID provisional D3 retention, exact
  cross-owner publication/release coordination and the recovery handshake.
  The public D2/D3 payload and per-uop release history live in `top/interface`.
- Independent review: the first final review blocked recovery because prepare
  could switch a target D3 payload held under backpressure and found a vacuous
  recovery test with mismatched plans. The repaired version blocks prepare
  until the irrevocable target fires, keeps unrelated STIDs live, and performs
  observable suffix pruning with an exact plan. A fresh read-only reviewer
  returned `APPROVE` with no remaining correctness or scope blocker.
- Remaining gap: this is a standalone RENU slice. ROB/BROB identity remains
  virtual, release has no real commit producer, and dispatch/S1 is absent.
  Task 9 must establish ROB/BROB, commit and precise-recovery authority; Task
  10 must join rename publication to real ROB/BROB and dispatch readiness.
- skill-evolve: no-update — this reinforces existing LinxCore invariants
  around single state owner, exact identity, retained ready/valid, and
  recovery scope; no shared skill change is needed.
- Branch: `codex/chisel-gap-superpowers`
- Commit: the enclosing Lore commit with intent
  `Preserve absolute and relative register semantics with separate owners`.
- Push target: `origin/codex/chisel-gap-superpowers`; controller push and
  remote equality are the final loop handoff.

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
