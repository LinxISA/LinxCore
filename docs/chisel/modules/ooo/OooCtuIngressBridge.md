# OooCtuIngressBridge

## Purpose

`OooCtuIngressBridge` is the production OOO-side boundary for the external
Code Template Unit. It sits after D1 decode/fusion and before D2 grouping.
It owns ingress ordering and lease lifetime, but not template recipe
computation, physical-resource allocation, execution, or architectural effects.

## Ordering and lease protocol

The bridge retains one decoded packet and at most one CTU lease per STID. It
compares canonical instruction IDs with half-range modular ordering and emits
the dense ordinary-uop prefix before the next diverted parent. An unresolved
complex parent blocks at its exact boundary. Other STIDs remain eligible.

```text
Idle --parentClaim.fire--> WaitPlan --exact plan.fire--> EmitChildren
 ^                                                        |
 `---------------- exact final child.fire ----------------'
```

The claim contains the raw parent and an immutable
`{PE, STID, parent, templateGroupId, generation}` lease. The external CTU must
return that lease in a nonzero bounded expansion plan and every child. A child
is accepted only if its lease, ordinal, count, final flag, valid recipe, and
nonrecursive disposition are exact. Stale or malformed plan/child offers are
consumed through typed reject ports without changing the lease.

Each accepted child is normalized into one `OooD1DecodedPacket` and follows
the ordinary D2/D3/S1 path. The bridge overwrites parent/template identity from
the retained lease, so the child producer cannot forge it. Nonfinal children
carry `traceOwner=false` and zero architectural-parent demand; only the final
child owns the parent. Expansions can therefore span several RID groups while
retiring the template parent once.

## Recovery

Recovery prepare snapshots target packet and lease occupancy, retains the
exact request, and fences the STID without mutation. Exact common apply clears
the target retained packet, ordinary/CTU/complex masks, and lease. Abort only
releases the prepare fence. While that exact prepare is retained, a generic
stage-cancel input cannot clear the target ahead of common apply. A post-apply
plan or child is stale by construction.

Clearing all target pre-D2 state is valid because same-STID ingress is ordered:
the retained D1 packet and active CTU continuation are younger than the ROB
trigger. This owner must be prepared before O3 accepts the request, and its
apply must be the same event as O3 common apply.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooCtuIngressBridge
bash tools/chisel/run_chisel_tests.sh --only OooIfuD1Ingress
bash tools/chisel/run_chisel_tests.sh --only OooFrontendCtuRecoveryIntegration
```

The tests cover mixed normal/template/normal order, six-child multi-RID
reinsertion, final-only parent ownership, unrelated-STID progress, stale plans,
wrong child order, recovery freeze/abort/apply, and a real bridge-to-frontend
common-apply composition.

## Remaining integration

O9 must instantiate the external recipe producer, connect the
claim/plan/child ports in the production top, and remove the legacy CTU paths
that block globally or write PRF, memory, `setc`, or commit state directly.
