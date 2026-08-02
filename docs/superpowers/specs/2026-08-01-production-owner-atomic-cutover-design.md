# LinxCore Production-Owner Atomic Cutover Design

## 1. Objective

Complete the canonical LinxCore Chisel graph by promoting the production-level
owners already present in the repository instead of building a parallel copy
of each subsystem. Keep one buildable implementation at every committed loop
boundary, converge each public interface once, and delete the displaced live
legacy entry points in the same subsystem cutover loop.

Git history is the archive. The live source tree must not retain compatibility
owners, archive directories, duplicate emitters, or reduced implementations
after their production replacement has passed the same-level evidence gate.

## 2. Decisions

### 2.1 Preserve Tasks 1-9

Retain the contract spine, central parameters, typed TOP interfaces, CTU, IFU,
DEC, RENU, PRename, TURename, canonical ROB/BROB, CommitControl, and
RecoveryControl. Finish the current Task-9 correctness loop and push its
commits before starting the new cutover sequence.

The production `OooO3RenameCoordinator` cannot become the new OOO box as a
whole because it owns another ROB/BROB/rename/recovery composition. Reuse its
proven private mechanisms only after assigning every state table to one
canonical owner.

### 2.2 Promote production mechanisms in place

Use the following repository mechanisms as the implementation starting point:

- IFU: the existing `LinxCoreIfu` and I-SIDE/B-SIDE owners already composed
  below the public `IFU` box;
- OOO dispatch: the existing reservation, hierarchical free-slot selection,
  dispatch classification, fast-resolve, and store-commit mechanisms;
- IEX: the existing `OooIexIssue*`, operand-file, execution-pipeline, terminal,
  and `ScalarGPRFile` mechanisms;
- LSU: the existing `ScalarLSU` internals, STQ/SCB/CommitQ, LIQ/ResolveQ/MDB,
  miss/refill/load-return, and `ScalarL1D` mechanisms.

Do not create a second stateful implementation merely to satisfy a target
class or filename. Public `OOO`, `IEX`, and `LSU` boxes are permanent typed
composition boundaries; they are not compatibility adapters.

### 2.3 Use one atomic cutover per closed owner graph

Normally one subsystem boundary is one cutover. When a typed identity is
allocated across two adjacent owners and neither side can become live without
the other's retained lease protocol, prepare both sides independently and cut
the closed adjacent graph in one loop. The OOO-IEX-LSU switch uses this joint
exception: Task 13 closes private canonical prerequisites, Task 14 prepares the
matching LSU lease owner, and Task 15 activates both public boxes together.

A cutover loop must finish all of the following before its commit:

1. freeze the producer/consumer payload and parameter mapping;
2. modify the selected production mechanisms to consume canonical types;
3. update every live producer, consumer, harness, emitter, test, and document;
4. connect the subsystem through the canonical public box;
5. delete the displaced legacy wrapper, reduced owner, and obsolete test path;
6. pass focused, adjacent, generated-RTL, and bounded architectural gates;
7. commit and push the green tree.

A temporary adapter may exist only inside the uncommitted working tree during
the cutover. It must own no state and must be absent from the cutover commit.

### 2.4 Separate preparation from interface cutover

Large production modules may need several behavior-preserving preparation
loops. Preparation may split files, centralize parameters, add assertions, or
expand tests, but it must not create a second owner or change the public
subsystem interface. The subsequent cutover loop changes the public interface
and all call sites once.

### 2.5 Delete legacy incrementally

Deletion no longer waits for the final repository task. Once a subsystem has
equivalent unit, adjacent-integration, generated-RTL, and bounded workload
evidence through the canonical graph, delete the displaced live chain in that
same loop. The final cleanup task removes only residual orphans and proves the
absence of old active identifiers.

## 3. Canonical Owner Rules

Maintain a checked owner manifest for architectural state, including ROB,
BROB, rename maps and freelists, IQ residency, physical register data and
readiness, STQ/SCB/LIQ/MDB/cache state, commit, and recovery arbitration. Every
state category has exactly one live owner reachable from the active emitter.

An implementation is not promoted because its unit test passes or because its
name contains `canonical`. Promotion requires:

- reachability from the canonical public box and active generated top;
- complete typed identity, backpressure, and recovery behavior;
- nonzero activation evidence at the relevant generated-RTL or workload gate;
- absence of a second live state owner for the same architectural state.

Probe modules and reduced workload paths remain verification fixtures only
until their mechanism is reachable through the canonical graph. Delete a
fixture when it exists only to support a displaced implementation.

## 4. Cutover Sequence

1. Close Task 9 and freeze the owner/call-site manifest.
2. Cut OOO D3/S1 dispatch onto the canonical RENU/ROB/BROB owners.
3. Prepare the production IEX mechanisms without changing their live boundary.
4. Close canonical OOO-IEX prerequisites: D1 memory controls, the unique OOO
   memory-order/recovery owner, private IEX canonical ingress/terminal and the
   approved IEX-LSU attempt lifecycle; keep public `IEX` absent.
5. Prepare the production LSU internals, implement the matching typed attempt
   lifecycle and resolve the two-load-pipe contract without a public cutover.
6. Atomically activate the canonical OOO-IEX-LSU graph, then delete the reduced
   issue/execute/completion chain, old `ScalarLSU` boundary and displaced LSU
   entry points in the same loop.
7. Integrate DTU, distributed recovery, trap, interrupt, and commit observation.
8. Promote one active `TOP` emitter and natural ELF harness; delete old tops.
9. Run natural scalar linx-avs, Dhrystone, CoreMark, bounded commit comparison,
   and recovery stress.
10. Remove residual orphan sources, docs, tools, and generated intermediates;
    run full closure and update the superproject gitlink.

## 5. Evidence Ladder

Run evidence in increasing cost order:

1. owner unit and contract tests;
2. upstream/downstream adjacent integration;
3. W2/W4/W6/W8 elaboration and interface-manifest parity;
4. generated-RTL lint and subsystem activation probes;
5. bounded scalar linx-avs and QEMU commit comparison;
6. natural Dhrystone/CoreMark and 1000-commit comparison;
7. multi-seed recovery/long-latency stress and the full closure suite.

Do not repeatedly run the full benchmark suite during local RED/GREEN fixes.
Run the complete promotion ladder at the subsystem cutover commit and final
closure.

## 6. Loop and Git Discipline

Each loop records its owner decision, interface mapping, files deleted,
verification evidence, generated-artifact cleanup, and remaining boundary.
Every green loop ends with one Lore commit and an immediate push. A loop may
not begin while earlier local commits remain unpushed unless the remote is
unavailable and the ledger records the exact blocker.

Generated Verilog trees, `obj_dir`, wave files, and compiler intermediates are
reproducible and must be pruned. Preserve only manifests, reports, bounded
trace excerpts, and evidence explicitly cited by current documentation.

## 7. Success Criteria

The migration succeeds when:

- one active `TOP` reaches exactly one IFU, CTU, OOO, IEX, LSU, and DTU box;
- production mechanisms are reused behind canonical typed interfaces without
  duplicate state owners or committed compatibility adapters;
- W2/W4/W6/W8 elaborate and W4 exposes the required physical topology;
- scalar linx-avs, natural Dhrystone, and natural CoreMark pass through the
  same emitted TOP and frozen ELF identities;
- bounded architectural comparison reports zero mismatches;
- live source, tests, emitters, harnesses, and current docs contain no
  displaced top or `Reduced*` chain;
- the LinxCore branch is pushed, clean, fully verified, and pinned by the
  LinxISA superproject.
