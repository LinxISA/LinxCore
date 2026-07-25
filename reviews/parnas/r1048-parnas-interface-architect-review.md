# R1048 Parnas Interface Architect Review

Verdict: REQUEST CHANGES

Reviewer: Parnas, `linxcore-interface-architect`

Run: `linxcore-r1048-template-d3-interface-repair-20260722`

Reviewed immutable inputs:

- Snapshot manifest: `/Users/zhoubot/FishToucher/runs/linxcore-r1048-template-d3-interface-repair-20260722/artifact-snapshot/snapshot-manifest.json`
- Full-document artifact: `runs/linxcore-r1048-template-d3-interface-repair-20260722/artifacts/knuth/r1048-template-d3-reservation-fill.md`
- Patch artifact: `runs/linxcore-r1048-template-d3-interface-repair-20260722/artifacts/knuth/r1048-template-d3-reservation-fill.patch`
- Mailbox before verdict SHA-256: `2979352868b2fdff10c71c84e29012506f886d7ee78ec30ffb5e92101042d4ec`

## Defects

1. Fatal quiesce request/ack remains prose-only.

   Evidence: the reviewed artifact describes a retained `quiesceReq(valid, generation, descriptorKey, reason, sourceContext)` fanout at lines 375-378 and a retained `quiesceAck(valid, generation, descriptorKey, ownerIndex, stateReleased, stateQuarantined, inFlightClear)` at lines 391-394. The actual port-level contract at lines 416-429 exposes `reserveReq`, `reserveResp`, `fillToken`, `rowFill`, `rowFillAck`, `cancel`, `recovery`, `recoveryAck`, `trace`, and `fatal`, but no explicit quiesce request or quiesce ack ports. A fatal controller cannot be implemented from prose-only fanout semantics when the owner-facing ports, ready/valid direction, and acceptance boundary are absent.

   Required fix: define explicit fatal-controller-to-owner `quiesceReq` and owner-to-fatal-controller `quiesceAck` ports or typed vector channels in the port-level contract, with direction, type, producer, consumer, lifetime, ready/valid or valid/ack stability, generation matching, owner indexing, and all-acks acceptance semantics.

2. Typed credit-token vectors and payloads are not concretely defined.

   Evidence: `TemplateReserveResponse.tokens` is only `Vec(28, TemplateFillToken)` at line 210, while `TemplateFillToken.resourceCreditMask` is an implementation bit mask at line 224. `TemplateRowFill` then references `dstReservation`, `iqReservation`, `liqReservation`, and `stqReservation` as placeholder token names at lines 238-241 without concrete bundle types or owner payload fields. The document later says `reserveResp` retains "credit-token vectors" at line 439, but no `TemplateCreditToken`, per-domain vector layout, payload shape, or mapping from each `TemplateCreditDomain` to concrete owner identity fields is defined.

   Required fix: replace the mask/placeholders with concrete typed bundle definitions for credit tokens and per-domain payloads, including vector dimensions, valid bits, owner identity, generation, amount/state, and domain-specific payloads for ROB/BROB, checkpoint, GPR/mapQ, IQ, LIQ/load ID, STQ/store ID, LSID, validation/target publish, lease/final, and invalidation transaction capacity.

## Proof Boundary

This review rejects the R1048 contract as implementation-ready. It accepts that the artifact improved identity sidecars, response retention prose, and fatal reason enumeration, but those changes do not close the two interface blockers above because implementers still lack concrete ports and typed payloads for fatal quiescence and credit-token transfer.

No product, test, generated, result, snapshot, or reviewed artifact bytes were edited by this review.

skill-evolve: no-update
