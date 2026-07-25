# Template D3 Reservation Fill Interface

## Source Mapping

- Target Chisel bundle owner: `rtl/LinxCore/chisel/src/main/scala/linxcore/common/TemplateD3ReservationFillBundles.scala`
- Target D3 admission owner: `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/TemplateD3ReservationAllocator.scala`
- Target row-fill owner: `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/TemplateD3RowFill.scala`
- Current migration sources:
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/backend/DispatchROBAllocator.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/TemplateRenameSidecar.scala`
  - `rtl/LinxCore/chisel/src/main/scala/linxcore/bctrl/BlockControlTemplateSequencer.scala`
- Architectural basis:
  - `rtl/LinxCore/docs/architecture/code_template_unit.md`
  - `rtl/LinxCore/docs/architecture/macro_instruction_generation.md`
- Model vocabulary:
  - `tools/LinxCoreModel/model/bctrl/template/TemplateIdentity.h`
  - `tools/LinxCoreModel/model/bctrl/template/TemplateIdentity.cpp`
- Contract IDs: `LC-IF-CHISEL-TEMPLATE-D3-RESERVE-FILL-001`
- Successor repair: R1050 supersedes the rejected R1042/R1044 draft and R1048
  repair boundary. R1050
  closes the identity sidecar, atomic credit, retained reserve response, and
  fatal-quiescence interface defects while preserving the accepted row plans.

## Purpose

`TemplateD3ReservationFill` is the canonical interface between D3 template
admission, CTU row production, normal ROB/rename/IQ/LIQ/STQ owners, recovery,
trace, and fatal teardown.

D3 reserves the whole template expansion atomically. A successful reserve
creates one contiguous wrapped RID interval for all rows in the D3 row plan,
pre-reserves every owner credit required by those rows, and exposes
`ReservedUnfilled` ROB entries that are invisible to issue, commit, and trace
until filled. A failed reserve mutates nothing. CTU later fills those exact
reserved rows in order with pre-reserved tokens; fill never reallocates ROB,
rename, IQ, LIQ, STQ, BROB, checkpoint, or memory-order resources.

This interface replaces the current private-parent/direct-effect topology.
Template children become normal rows with normal backend ownership. CTU must
not write RF/SP, D-memory, commit trace, redirect, or `setc` state directly.

## Terms

| Term | Meaning |
|---|---|
| Parent | The decoded template instruction reaching D3. The parent is not a hidden ROB row. |
| Group | The full reserved expansion for one accepted parent, keyed by `(lxcpu, context generation, peId, stid, engineLocalTid, bid, gid, groupBaseRid, templateGeneration)`. |
| Child row | One row in `BuildD3RowPlan`. The plan includes `VFORM` and `FINAL`; it is not only load/store children. |
| `ReservedUnfilled` | ROB row state created by atomic reservation before CTU supplies the row payload. It is resident and recovery-visible, but issue-, commit-, trace-, and side-effect-invisible. |
| Fill token | Per-row, pre-reserved proof that CTU may fill exactly one reserved RID without another allocation. |
| Owner credit | A pre-reserved credit for a downstream owner: ROB row, GPR destination/mapQ, IQ, LIQ, STQ, memory-order identity, checkpoint, BROB/range, target-publish, or final lease. |
| Fatal | A template contract violation that requires quiescent teardown before the group can be released or reused. |

## D3 Row Plan

`encoded_N` is the architectural register-ring count and must be in `1..22`.
The full profile requires `robEntries >= 32` because the maximum demand is
`N+6 = 28` rows for `FRET_STK` at `N=22`, plus normal headroom for progress.
Configurations with fewer ROB entries must deassert `fullProfileSupported` and
reject full-profile template reservation.

| `TemplateForm` | `formId` | Exact row count | Maximum rows | Row order |
|---|---:|---:|---:|---|
| `FENTRY` | `1` | `N+3` | `25` | `VFORM`, `SP_SUB`, `STORE[0..N-1]`, `FINAL` |
| `FEXIT` | `2` | `N+3` | `25` | `VFORM`, `SP_ADD`, `LOAD[0..N-1]`, `FINAL` |
| `FRET_RA` | `3` | `N+5` | `27` | `VFORM`, `VTGT`, `TARGET_PUBLISH`, `SP_ADD`, `LOAD[0..N-1]`, `FINAL` |
| `FRET_STK` | `4` | `N+6` | `28` | `VFORM`, `VLOAD`, `VTGT`, `SP_ADD`, `RESTORE_R10`, `TARGET_PUBLISH`, `LOAD[1..N-1]`, `FINAL` |

Malformed forms are not eligible for the normal reservation path. A malformed
template must either be rejected before reserve or converted into one normal
trap row by the existing decode/ROB path; it must not allocate a private
rowless validator.

## Bundle Reference

All bundles are passive Chisel `Bundle` types. Widths use `InterfaceParams`
unless specified otherwise.

### `TemplateOwnerID`

`TemplateOwnerID` is the lossless identity carried by reserve, fill, recovery,
trace, and fatal paths. Compact descriptors may transport shared group fields,
but every boundary must be able to reconstruct this exact row identity before
mutation.

| Field | Type | Lifetime | Description |
|---|---|---|---|
| `lxcpuId` | `UInt(32.W)` | group | Physical Linx CPU owner. |
| `lxcpuContextGeneration` | `UInt(64.W)` | group | Context generation; prevents reuse across reset/context switch. |
| `peId` | `UInt(p.peIdWidth.W)` | group | Scalar PE owner. |
| `stid` | `UInt(p.threadIdWidth.W)` | group | STID lane owner. Unrelated STIDs must not be globally fenced. |
| `engineLocalTid` | `UInt(p.threadIdWidth.W)` | group | CTU-local thread token for response routing. |
| `bid` | `UInt(p.robIndexWidth.W)` | group | Narrow BID projection used by compact diagnostic paths. |
| `bidRobid` | `ROBID(p.robEntries)` | group | Full wrap-qualified BID sidecar. |
| `gid` | `UInt(p.robIndexWidth.W)` | group | Narrow group-ID projection used by compact diagnostic paths. |
| `gidRobid` | `ROBID(p.robEntries)` | group | Full wrap-qualified GID sidecar. |
| `groupBaseRid` | `UInt(p.robIndexWidth.W)` | group | Narrow first RID value for compact descriptors. |
| `groupBaseRidRobid` | `ROBID(p.robEntries)` | group | Full wrap-qualified first RID in the contiguous reserved interval. |
| `groupBaseRobSlot` | `UInt(p.robIndexWidth.W)` | group | `groupBaseRid.value`; stored as a diagnostic projection. |
| `groupRowCount` | `UInt(5.W)` | group | Exact row count, maximum `28`. |
| `checkpointId` | `UInt(p.checkpointWidth.W)` or wider implementation field | group | Rename/recovery checkpoint consumed by the reservation. |
| `templateGeneration` | `UInt(64.W)` | group | Monotonic generation for parent/group reuse. |
| `sourcePc` | `UInt(p.pcWidth.W)` | group | Parent instruction PC. |
| `sourceRaw` | `UInt(p.insnWidth.W)` | group | Parent raw instruction bits. |
| `formId` | `UInt(8.W)` | group | `TemplateForm`: `1..4`. |
| `encodedN` | `UInt(5.W)` | group | Accepted `N`, range `1..22`. |
| `rowPresent` | `Bool` | row | Must be true for row fill, trace, recovery, and fatal records. |
| `rowKind` | `UInt(8.W)` | row | `TemplateRowKind` from the row plan. |
| `childOrdinal` | `UInt(5.W)` | row | Row ordinal in the group, `0 <= childOrdinal < groupRowCount`. |
| `rid` | `UInt(p.robIndexWidth.W)` | row | Narrow row RID value for compact descriptors. |
| `ridRobid` | `ROBID(p.robEntries)` | row | Wrap-qualified row RID: `groupBaseRidRobid + childOrdinal`. |
| `robSlot` | `UInt(p.robIndexWidth.W)` | row | `ridRobid.value`; stored as a diagnostic projection. |
| `rowGeneration` | `UInt(64.W)` | row | Per-row generation, normally `{templateGeneration, childOrdinal}` in implementation form. |
| `lsidValid` | `Bool` | row | True only for `VLOAD`, `LOAD`, and `STORE` rows. |
| `lsidValue` | `UInt(32.W)` | row | Full memory-order LSID. |
| `lsidWrapOrGeneration` | `UInt(64.W)` | row | Memory-order generation sidecar. |
| `loadIdValid` | `Bool` | row | True only for `VLOAD` and `LOAD`. |
| `loadIdValue` | `UInt(64.W)` | row | Load identity value. |
| `loadIdGeneration` | `UInt(64.W)` | row | Load identity generation. |
| `loadReplayGeneration` | `UInt(64.W)` | row | Replay generation for future load replay. |
| `storeIdValid` | `Bool` | row | True only for `STORE`. |
| `storeIdValue` | `UInt(64.W)` | row | Store identity value. |
| `storeIdGeneration` | `UInt(64.W)` | row | Store identity generation. |

Memory identity is canonical:

| Row kind | Required memory identity |
|---|---|
| `VLOAD`, `LOAD` | `lsidValid && loadIdValid && !storeIdValid`; all invalid store fields zero. |
| `STORE` | `lsidValid && !loadIdValid && storeIdValid`; all invalid load fields zero. |
| All other row kinds | `!lsidValid && !loadIdValid && !storeIdValid`; all invalid memory fields zero. |

Narrow and full identities are both normative. Before any mutation, every
consumer must project and compare:

- `bid === bidRobid.value`, `gid === gidRobid.value`,
  `groupBaseRid === groupBaseRidRobid.value`, and `rid === ridRobid.value`.
- `groupBaseRobSlot === groupBaseRidRobid.value` and
  `robSlot === ridRobid.value`.
- `ridRobid === AdvanceROBID(groupBaseRidRobid, childOrdinal, robEntries)`.
- Full owner equality includes every field in `TemplateOwnerID`, including the
  narrow values, `*Robid` sidecars, memory identities, generations, row kind,
  row ordinal, source PC/raw, form, `encodedN`, and context fields. No consumer
  may compare only `(stid,bid,rid)` or only the narrow projections.
- A projection mismatch is detected before ROB, rename, issue, LSU, control,
  trace, recovery, lease, or invalidation state is mutated. Diagnostic-only
  projections must not be used as allocation authority.

### `TemplateGroupDescriptor`

This compact group record may be stored once per accepted template and joined
with a row token to reconstruct `TemplateOwnerID`.

| Field | Type | Producer | Consumer | Description |
|---|---|---|---|---|
| `valid` | `Bool` | reserve | fill/recovery/fatal | Descriptor is live. |
| `group` fields | subset of `TemplateOwnerID` group fields | reserve | all | All `TemplateOwnerID` fields with group lifetime. |
| `firstRid` | `UInt(p.robIndexWidth.W)` | reserve | diagnostics | Alias of narrow `groupBaseRid`. |
| `firstRidRobid` | `ROBID(p.robEntries)` | reserve | ROB/fill/recovery | Alias of `groupBaseRidRobid`. |
| `firstRobSlot` | `UInt(p.robIndexWidth.W)` | reserve | diagnostics | Alias of `groupBaseRobSlot`. |
| `rowCount` | `UInt(5.W)` | reserve | all | Exact `N+3`, `N+5`, or `N+6` row count. |
| `lastRid` | `UInt(p.robIndexWidth.W)` | reserve | diagnostics | Narrow projection of `lastRidRobid`. |
| `lastRidRobid` | `ROBID(p.robEntries)` | reserve | ROB/recovery | `groupBaseRidRobid + rowCount - 1`. |
| `leaseValid` | `Bool` | reserve | final/fatal/recovery | Group resources are still held. |
| `fatalPoisoned` | `Bool` | fatal | all | Group is in quiescent fatal teardown. |

### `TemplateReserveDemand`

The classifier emits demand before mutation.

| Field | Type | Description |
|---|---|---|
| `formId` | `UInt(8.W)` | Must be one of `FENTRY/FEXIT/FRET_RA/FRET_STK`. |
| `encodedN` | `UInt(5.W)` | Must be `1..22`. |
| `rowCount` | `UInt(5.W)` | Exact `N+3`, `N+3`, `N+5`, or `N+6`; maximum `28`. |
| `brobRangeCredits` | `UInt(1.W)` | One BID/BROB range reservation for the full group. |
| `gprDestCredits` | `UInt(5.W)` | Number of GPR physical-destination reservations needed by filled rows. |
| `mapqCredits` | `UInt(5.W)` | Number of rename mapQ/update slots held for destination-producing rows. |
| `iqCredits` | `UInt(5.W)` | Number of IQ entries reserved for executable rows. |
| `liqCredits` | `UInt(5.W)` | Number of LIQ entries reserved. |
| `loadIdCredits` | `UInt(5.W)` | Number of load identities reserved. |
| `stqCredits` | `UInt(5.W)` | Number of STQ entries reserved. |
| `storeIdCredits` | `UInt(5.W)` | Number of store identities reserved. |
| `lsidCredits` | `UInt(5.W)` | Number of memory-order IDs reserved. |
| `targetPublishCredits` | `UInt(1.W)` | `1` only when a target-publish row exists. |
| `validationCredits` | `UInt(2.W)` | Validation slots for `VFORM`, `VLOAD`, and `VTGT` rows that require retained checking. |
| `checkpointCredits` | `UInt(1.W)` | `1` for the group checkpoint/lease. |
| `finalCredits` | `UInt(1.W)` | Always `1` for normal templates. |
| `leaseCredits` | `UInt(1.W)` | Group descriptor lease, released by `FINAL`, recovery, or fatal. |
| `invalidationTxnCredits` | `UInt(1.W)` | Capacity for one retained cancel/recovery/fatal invalidation transaction. |

### `TemplateReserveRequest`

| Field | Type | Stability | Description |
|---|---|---|---|
| `parent` | decoded D3 parent payload | stable while `valid && !ready` | Parent PC/raw/opcode, immediate, register range, PE/STID, checkpoint, and current block identity. |
| `demand` | `TemplateReserveDemand` | stable while `valid && !ready` | Exact resource demand. |
| `smapSnapshot` | `Vec(archRegs, UInt(physTagWidth.W))` | stable while `valid && !ready` | Rename map snapshot used to build child source/destination tags. |
| `cmapSnapshot` | `Vec(archRegs, UInt(physTagWidth.W))` | stable while `valid && !ready` | Commit map snapshot used by recovery/fatal validation. |
| `sourceOperands` | parent operand values/tags | stable while `valid && !ready` | SP/RA/source data required to compute children. |

### `TemplateReserveResponse`

| Field | Type | Description |
|---|---|---|
| `accepted` | `Bool` | True only on `request.fire` and full atomic admission. |
| `rejected` | `Bool` | True only on `request.fire` when no state mutated. |
| `rejectReason` | enum | `UnsupportedForm`, `MalformedN`, `RobTooSmall`, `RobUnavailable`, `DuplicateIdentity`, `CreditUnavailable`, `RecoveryBusy`, `FatalBusy`. |
| `descriptor` | `TemplateGroupDescriptor` | Valid only when `accepted`. |
| `tokens` | `Vec(28, TemplateFillToken)` | First `rowCount` tokens are valid only when `accepted`; each token carries compact credit-token handles, not wide owner payloads. |
| `creditTokenBank` | `TemplateCreditTokenBankDescriptor` | Valid only when `accepted`; descriptor-backed bank holding every concrete domain payload for the group. |
| `reservedMask` | `UInt(p.robEntries.W)` | ROB slots placed in `ReservedUnfilled`; diagnostic and assertion source. |
| `firstRid`/`lastRid` | `UInt(p.robIndexWidth.W)` | Narrow projections of the reserved interval. |
| `firstRidRobid`/`lastRidRobid` | `ROBID(p.robEntries)` | Reserved contiguous wrapped interval. |

### `TemplateFillToken`

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Token can be consumed once. |
| `descriptorGeneration` | `UInt(64.W)` | Must match the live descriptor. |
| `childOrdinal` | `UInt(5.W)` | Row ordinal. |
| `rid` | `ROBID(p.robEntries)` | Exact reserved row. |
| `rowKind` | `UInt(8.W)` | Expected row kind. |
| `resourceCreditMask` | implementation bit mask | Credits consumed by this row. |
| `creditTokenHandles` | `Vec(TemplateCreditDomainCount, TemplateCreditTokenHandle)` | Per-domain compact handles; invalid handles are zero. |
| `compositeHandle` | `TemplateCreditCompositeHandle` | Row-scoped serialized lookup handle for all domains consumed by this row. |

### `TemplateCreditToken` And Bank Payloads

`TemplateCreditDomain` is an enum with exactly these values:

| Value | Domain | Scope |
|---:|---|---|
| `0` | `ROB_ROW` | row |
| `1` | `BROB_RANGE` | group |
| `2` | `CHECKPOINT` | group |
| `3` | `GPR_PHYS_DEST` | row |
| `4` | `MAPQ` | row |
| `5` | `IQ_ENTRY` | row |
| `6` | `LIQ_ENTRY` | row |
| `7` | `LOAD_ID` | row |
| `8` | `STQ_ENTRY` | row |
| `9` | `STORE_ID` | row |
| `10` | `LSID` | row |
| `11` | `VALIDATION` | row |
| `12` | `TARGET_PUBLISH` | row |
| `13` | `LEASE_FINAL` | group/final row |
| `14` | `INVALIDATION_TXN` | group |

`TemplateCreditTokenState` is an enum: `Free=0`, `Reserved=1`,
`Consumed=2`, `Released=3`, and `Quarantined=4`.

`TemplateCreditTokenHeader` is the common header stored for every credit token:

| Field | Type | Meaning |
|---|---|---|
| `valid` | `Bool` | Header slot contains a live or retained token. |
| `domain` | `TemplateCreditDomain` | Domain value from the table above. |
| `state` | `TemplateCreditTokenState` | Current ledger state. |
| `groupOwner` | `TemplateGroupDescriptor` key | Full group identity used to match descriptor generation and owner fields. |
| `rowOwner` | `TemplateOwnerID` | Full row identity for row-scoped domains; invalid for group-scoped domains. |
| `rowScoped` | `Bool` | True only for row-scoped domains. |
| `childOrdinal` | `UInt(5.W)` | Row ordinal for row-scoped domains; zero for group-scoped domains. |
| `amount` | `UInt(6.W)` | Conserved count, normally one; zero is legal only when `valid=false`. |
| `tokenGeneration` | `UInt(64.W)` | Token allocation generation. |
| `ownerGeneration` | `UInt(64.W)` | Matches `templateGeneration` for group-scoped domains or row/allocation generation for row-scoped domains. |
| `descriptorGeneration` | `UInt(64.W)` | Must match the live descriptor before lookup, consume, release, or quarantine. |

Every invalid token header has all non-`valid` fields zero. Every valid
row-scoped token has `rowOwner.rowPresent=true`, `childOrdinal` equal to the
row owner ordinal, and a matching group projection. Every valid group-scoped
token has `rowScoped=false`, `childOrdinal=0`, `rowOwner` zeroed, and a live
group owner.

`TemplateCreditDomainPayload` is a tagged union selected by `domain`. Invalid
payload variants and fields not owned by the selected domain are zero:

| Domain | Concrete payload fields |
|---|---|
| `ROB_ROW` | `ridRobid`, `robSlot`, `reservedUnfilledGeneration`, `reservedMaskBit`. |
| `BROB_RANGE` | `bidRobid`, `gidRobid`, `rangeFirstRidRobid`, `rangeLastRidRobid`, `rowCount`, `brobRangeGeneration`. |
| `CHECKPOINT` | `checkpointId`, `checkpointGeneration`, `smapSnapshotHandle`, `cmapSnapshotHandle`. |
| `GPR_PHYS_DEST` | `physTag`, `architecturalReg`, `oldPhysTag`, `destGeneration`, `writesGpr`. |
| `MAPQ` | `mapqSlot`, `architecturalReg`, `newPhysTag`, `oldPhysTag`, `mapqGeneration`. |
| `IQ_ENTRY` | `iqIndex`, `issueClass`, `wakeupMask`, `iqGeneration`. |
| `LIQ_ENTRY` | `liqIndex`, `loadIdValue`, `loadIdGeneration`, `lsidValue`, `liqGeneration`. |
| `LOAD_ID` | `loadIdValue`, `loadIdGeneration`, `loadReplayGeneration`, `lsidValue`. |
| `STQ_ENTRY` | `stqIndex`, `storeIdValue`, `storeIdGeneration`, `lsidValue`, `stqGeneration`. |
| `STORE_ID` | `storeIdValue`, `storeIdGeneration`, `lsidValue`. |
| `LSID` | `lsidValue`, `lsidWrapOrGeneration`, `memoryKind` (`none`, `load`, `store`), `loadIdValue`, `storeIdValue`. |
| `VALIDATION` | `validationSlot`, `validationKind` (`VFORM`, `VLOAD`, `VTGT`), `expectedRowKind`, `validationGeneration`. |
| `TARGET_PUBLISH` | `targetSlot`, `targetGeneration`, `targetSourceOrdinal`, `redirectOwner`. |
| `LEASE_FINAL` | `leaseSlot`, `finalOrdinal`, `leaseGeneration`, `descriptorLeaseValid`. |
| `INVALIDATION_TXN` | `txnSlot`, `requiredOwnerMask`, `ackBitmap`, `txnGeneration`, `releaseOrQuarantinePolicy`. |

`TemplateCreditToken` combines `{ header, payload }`. Consumers must check
`header.domain` against the selected payload variant before mutation. A domain
or generation mismatch is stale/fatal according to the fatal table; it is never
converted into a different domain by masking.

`TemplateCreditTokenHandle` is compact and hardware-owned:

| Field | Type | Meaning |
|---|---|---|
| `valid` | `Bool` | Handle names a token-bank entry. |
| `bankId` | `UInt(templateCreditBankIdWidth.W)` | Bank instance local to the allocator/ledger. |
| `entryIndex` | `UInt(templateCreditBankIndexWidth.W)` | Physical token entry. |
| `domain` | `TemplateCreditDomain` | Expected token domain for lookup. |
| `tokenGeneration` | `UInt(64.W)` | Expected token generation. |
| `descriptorGeneration` | `UInt(64.W)` | Expected group descriptor generation. |

`creditTokenHandle` fields are invalid-zero: when `valid=false`, every other
field is zero. The handle is not allocation authority by itself; the token bank
entry must match domain, token generation, descriptor generation, owner, row
ordinal, and token state before consume/release/quarantine.

`TemplateCreditCompositeHandle` is a compact row handle:

| Field | Type | Meaning |
|---|---|---|
| `valid` | `Bool` | Composite row lookup is present. |
| `descriptorGeneration` | `UInt(64.W)` | Expected descriptor generation. |
| `childOrdinal` | `UInt(5.W)` | Row ordinal. |
| `firstHandleIndex` | `UInt(templateCreditHandleIndexWidth.W)` | First packed handle in the descriptor-backed handle table. |
| `handleCount` | `UInt(5.W)` | Number of handles for this row; bounded by `TemplateCreditDomainCount`. |
| `domainMask` | `UInt(TemplateCreditDomainCount.W)` | Domains included in the composite row lookup. |

`TemplateCreditTokenBankDescriptor` names the descriptor-backed token storage
created by reserve:

| Field | Type | Meaning |
|---|---|---|
| `valid` | `Bool` | Bank contains tokens for one accepted group. |
| `descriptorGeneration` | `UInt(64.W)` | Group generation all handles must echo. |
| `groupOwner` | `TemplateGroupDescriptor` key | Full group owner for bank membership. |
| `tokenBase` | `UInt(templateCreditBankIndexWidth.W)` | First physical token entry for the group. |
| `tokenCount` | `UInt(templateCreditBankCountWidth.W)` | Total concrete tokens reserved for the group. |
| `rowHandleBase` | `UInt(templateCreditHandleIndexWidth.W)` | First packed per-row handle. |
| `rowHandleCount` | `UInt(templateCreditHandleCountWidth.W)` | Total packed handles for all rows. |
| `domainPresentMask` | `UInt(TemplateCreditDomainCount.W)` | Domains represented in this bank. |

The bank is descriptor-backed storage, not a 28-wide payload insertion port and
not a flat hundreds-token combinational response. Reserve writes concrete
`TemplateCreditToken` entries into the bank and returns only compact handles in
`reserveResp.tokens` plus `creditTokenBank`. `rowFill` presents the row
`compositeHandle`; owners perform a bounded serialized
`creditTokenLookup(handle) -> TemplateCreditToken` and
`creditTokenConsume(handle, expectedOwner, expectedState)` sequence. The
serializer may consume one token per cycle or a small implementation-defined
number per cycle, but it must preserve row ordinal order and atomic per-row
visibility: a row is not made normal-visible until all required token consumes
for that row have succeeded and all invalid handles have been proven zero.

The following concrete ledger channels are implementation ports or internal
owner-facing subchannels:

| Direction | Port | Type | Producer | Consumer | Lifetime |
|---|---|---|---|---|---|
| request | `creditTokenLookup` | `Decoupled[TemplateCreditTokenHandle]` | row fill/fatal/recovery owner | credit token bank | Stable while stalled. |
| response | `creditTokenLookupResp` | `Decoupled[TemplateCreditToken]` | credit token bank | requesting owner | Retained until accepted; token bytes match the handle generation/domain or return invalid-zero stale diagnostic. |
| request | `creditTokenConsume` | `Decoupled[TemplateCreditTokenConsume]` | row fill owner | credit token bank | Stable until fire; consumes one `Reserved` token into `Consumed`. |
| request | `creditTokenRelease` | `Decoupled[TemplateCreditTokenRelease]` | retire/recovery/cancel/fatal owner | credit token bank | Stable until fire; releases or quarantines one token by exact handle and owner. |

`TemplateCreditTokenConsume` carries `{ creditTokenHandle, expectedOwner,
expectedDomain, expectedState=Reserved, consumeGeneration }`.
`TemplateCreditTokenRelease` carries `{ creditTokenHandle, expectedOwner,
expectedDomain, releaseState, releaseGeneration }`, where `releaseState` is
`Released` or `Quarantined`. Both requests fail closed on invalid-zero,
generation mismatch, owner mismatch, domain mismatch, state mismatch, or
payload-shape mismatch.

### `TemplateRowFill`

`TemplateRowFill` is a serialized `Decoupled` channel from CTU to the row
owners. It consumes one fill token and changes one `ReservedUnfilled` row into
the normal row state for that row kind.

| Field | Type | Stability | Description |
|---|---|---|---|
| `owner` | `TemplateOwnerID` | stable while `valid && !ready` | Fully reconstructed row identity. |
| `token` | `TemplateFillToken` | stable while `valid && !ready` | Matching unconsumed token. |
| `row` | `CommitTraceRow` | stable while `valid && !ready` | Commit-trace payload for the future normal row; trace is not visible until row commit. |
| `renamedUop` | `RenamedUop` | stable while `valid && !ready` | Normal issue/execute payload when the row issues. |
| `creditTokenHandle` | `TemplateCreditCompositeHandle` | stable while `valid && !ready` | Descriptor-backed composite handle for all pre-reserved row credits. |
| `dstReservation` | GPR destination token payload from `creditTokenLookup` | stable while `valid && !ready` | Pre-reserved destination/mapQ identity, if any. |
| `iqReservation` | IQ token payload from `creditTokenLookup` | stable while `valid && !ready` | Pre-reserved IQ entry, if any. |
| `liqReservation` | LIQ token payload from `creditTokenLookup` | stable while `valid && !ready` | Pre-reserved load entry, if any. |
| `stqReservation` | STQ token payload from `creditTokenLookup` | stable while `valid && !ready` | Pre-reserved store entry, if any. |
| `targetPublish` | target-publish payload | stable while `valid && !ready` | Normal control payload for `TARGET_PUBLISH`, if any. |
| `isFinal` | `Bool` | stable while `valid && !ready` | True only for `FINAL`. |

Fill is bounded: after an accepted reserve, CTU must either consume all tokens
in ordinal order or accept a cancel/recovery/fatal teardown. Implementations
must expose a watchdog parameter `templateFillMaxCycles >= 28` and assert that
no live descriptor remains partially filled longer than that bound unless
backpressure or accepted recovery is active.

### `TemplateResourceCredits`

Credits are reserved atomically by D3 and released only by normal retire,
accepted recovery, cancel before fill, or fatal teardown.

`TemplateCreditDomain` is an enum carried by every token and ledger entry:

| Domain | Reserved at | Consumed at | Released at | Notes |
|---|---|---|---|---|
| `ROB_ROW` | reserve | reserve | retire/recovery/fatal | Creates `ReservedUnfilled`; not reallocated at fill. |
| `BROB_RANGE` | reserve | reserve | normal block retirement/recovery/fatal | Reserves the BID/range identity for the group. |
| `CHECKPOINT` | reserve | reserve | `FINAL` lease release/recovery/fatal | Covers rename/recovery rollback for the whole group. |
| `GPR_PHYS_DEST` | reserve | fill | normal commit/recovery/fatal | Needed for SP, restore, and load destinations. |
| `MAPQ` | reserve | fill | map update retirement/recovery/fatal | Records rename-map update ownership separate from physical destination identity. |
| `IQ_ENTRY` | reserve | fill | issue/commit/recovery/fatal | Rows enter the normal issue path after fill. |
| `LIQ_ENTRY` | reserve | fill | load lifecycle/recovery/fatal | Queue capacity for `VLOAD`/`LOAD`. |
| `LOAD_ID` | reserve | fill | load lifecycle/recovery/fatal | Full load identity plus generation. |
| `STQ_ENTRY` | reserve | fill | store lifecycle/recovery/fatal | Queue capacity for `STORE`. |
| `STORE_ID` | reserve | fill | store lifecycle/recovery/fatal | Full store identity plus generation. |
| `LSID` | reserve | fill | memory-order retire/recovery/fatal | Numeric LSID is not recovery age authority. |
| `VALIDATION` | reserve | fill | validation completion/recovery/fatal | `VFORM`, `VLOAD`, and `VTGT` validation capacity. |
| `TARGET_PUBLISH` | reserve | fill | normal control retire/recovery/fatal | Required for return target publication. |
| `LEASE_FINAL` | reserve | fill final | `FINAL` retire/recovery/fatal | Releases group descriptor and any unconsumed lease state. |
| `INVALIDATION_TXN` | reserve | reserve | cancel/recovery/fatal transaction completion | Guarantees teardown can be represented without allocating while poisoned. |

Every reserved credit is represented by one concrete `TemplateCreditToken`
stored in `TemplateCreditTokenBank`. Response and fill channels carry
`creditTokenHandle` or `TemplateCreditCompositeHandle`, then retrieve concrete
domain payload through `creditTokenLookup`; they must not carry placeholder
masks as mutation authority.

The ledger is conserved: for each domain and owner generation,
`Free + Reserved + Consumed + Released + Quarantined` equals the configured
capacity plus any reset-created initial credits. A transition may move a token
only along `Free -> Reserved -> Consumed -> Released`, `Reserved -> Released`,
or `Reserved/Consumed -> Quarantined` during fatal. No row fill may borrow a
credit from a different owner, generation, row ordinal, STID, BROB range, or
memory identity.

Atomic reserve tests every domain in the demand table before mutation:
ROB rows, BROB/range, checkpoint, GPR physical destination, mapQ, IQ, LIQ,
load ID, STQ, store ID, LSID, validation, target publish, lease/final, and
invalidation transaction capacity. Acceptance moves every required token to
`Reserved` in one transaction. Rejection leaves every cursor, free count, token
state, descriptor bit, generation, and owner-visible identity unchanged,
including shortage counters and diagnostic projections.

### `TemplateCancelRecovery`

| Field | Type | Producer | Description |
|---|---|---|---|
| `cancel.valid` | `Bool` | frontend/backend cleanup | Cancels a not-yet-filled or partially filled group before side effects become visible. |
| `cancel.owner` | `TemplateGroupDescriptor` key | cancel source | Exact group key. |
| `recovery.valid` | `Bool` | central recovery | Accepted recovery request. |
| `recovery.stid` | `UInt(p.threadIdWidth.W)` | central recovery | Recovery STID. |
| `recovery.firstKilledBid` | full BID | central recovery | First killed block identity. |
| `recovery.inclusive` | `Bool` | central recovery | Whether pivot is killed. |
| `ack.valid` | `Bool` | template owner | Descriptor/tokens/credits have been removed or retained exactly. |
| `ack.killedMask` | `UInt(28.W)` | template owner | Rows removed from the group. |
| `ack.retainedMask` | `UInt(28.W)` | template owner | Filled rows retained by recovery. |

### `TemplateTrace`

| Field | Type | Description |
|---|---|---|
| `reserveAccepted` | `Bool` | One-cycle event for successful atomic reserve. |
| `reserveRejected` | `Bool` | One-cycle event for rejected no-mutation reserve. |
| `fillAccepted` | `Bool` | One-cycle event for accepted row fill. |
| `reservedUnfilledCount` | `UInt(5.W)` | Live unfilled row count. |
| `owner` | `TemplateOwnerID` | Exact row owner for fill/fatal/stale events. |
| `descriptor` | `TemplateGroupDescriptor` | Exact group owner for reserve/cancel/recovery events. |
| `fatalValid` | `Bool` | Fatal event was latched. |

Trace must not emit architectural commit rows for `ReservedUnfilled` entries.
Only normal ROB commit produces architectural trace.

### `TemplateFatal`

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Fatal request or retained fatal state. |
| `owner` | `TemplateOwnerID` or descriptor key | Exact failing owner. |
| `reason` | `UInt(3.W)` | Architectural `FatalReason` code `0..5`, retained in the fatal record. |
| `code` | enum | Diagnostic subtype: `BadOwner`, `BadRowKind`, `BadOrdinal`, `BadMemoryShape`, `TokenReuse`, `OutOfOrderFill`, `LostCredit`, `DuplicateFill`, `FillAfterRecovery`, `FillTimeout`, `FatalWhileFatal`. |
| `sourceContext` | `TemplateFatalSourceContext` | Source envelope described below. |
| `ackBitmap` | `UInt(TemplateFatalOwnerCount.W)` | Retained owner acknowledgements for the current fatal generation. |
| `quiescent` | `Bool` | No fill, issue, memory, RF, commit, redirect, or trace side effect is in flight for the poisoned group. |
| `teardownAck` | `Bool` | All descriptor, token, and credit state has been released or quarantined. |

Fatal handling is quiescent. Once fatal is latched, new reserve/fill for the
same group is blocked, visible side effects are suppressed, and teardown waits
for normal owner acknowledgements before releasing the group key.

`FatalReason` is architectural and closed for this interface:

| Value | Name | Meaning |
|---:|---|---|
| `0` | `BadOwnerIdentity` | Narrow/full identity projection, generation, STID, BID/GID/RID, or descriptor equality failed. |
| `1` | `BadRowPlan` | Row kind, row count, ordinal, form, or `encodedN` does not match `BuildD3RowPlan`. |
| `2` | `BadMemoryIdentity` | `VLOAD`/`LOAD`/`STORE` LSID/load/store shape is not canonical. |
| `3` | `CreditLedgerViolation` | Missing, reused, leaked, duplicated, or cross-owner credit token. |
| `4` | `ProtocolViolation` | Out-of-order fill, fill-after-recovery, duplicate fill, invalid response, or direct side-effect path. |
| `5` | `TimeoutOrNestedFatal` | Fill watchdog expiry or fatal while fatal. |

`TemplateFatalSourceContext` is a retained envelope:

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Source context is captured. |
| `sourceOwner` | `TemplateOwnerID` | Full failing row owner when row-scoped. |
| `sourceDescriptor` | `TemplateGroupDescriptor` | Full group key when group-scoped. |
| `sourcePort` | enum | `reserveReq`, `reserveResp`, `rowFill`, `rowFillAck`, `cancel`, `recovery`, `trace`, `ownerAck`, or implementation-local assertion. |
| `sourceGeneration` | `UInt(64.W)` | Fatal generation; every quiesce ack must echo this value. |
| `sourceCycle` | `UInt(64.W)` | Diagnostic cycle stamp. |
| `sourcePc` | `UInt(p.pcWidth.W)` | Parent or row PC if available. |
| `sourceRaw` | `UInt(p.insnWidth.W)` | Parent or row raw instruction if available. |
| `observed` | implementation bundle | Diagnostic observed value. |
| `expected` | implementation bundle | Diagnostic expected value. |

`TemplateFatalQuiesceReq` is the retained fatal-controller request:

| Field | Type | Stability | Description |
|---|---|---|---|
| `valid` | `Bool` | retained until all required acks | Quiesce request is active. |
| `generation` | `UInt(64.W)` | stable while `valid` | Fatal generation; copied from `sourceContext.sourceGeneration`. |
| `descriptorKey` | `TemplateGroupDescriptor` key | stable while `valid` | Exact poisoned group. |
| `reason` | `UInt(3.W)` | stable while `valid` | Closed `FatalReason` code. |
| `sourceContext` | `TemplateFatalSourceContext` | stable while `valid` | First retained fatal source. |
| `sourceOwner` | `TemplateOwnerID` | stable while `valid` | First failing row owner, invalid-zero when group-scoped. |
| `sourcePort` | enum | stable while `valid` | Boundary where fatal was detected. |
| `requiredOwnerMask` | `UInt(TemplateFatalOwnerCount.W)` | stable while `valid` | Owners that must ack for this generation. |
| `teardownPolicy` | enum | stable while `valid` | `ReleaseUnconsumedAndQuarantineConsumed`, `QuarantineAll`, or reset policy. |

`TemplateFatalQuiesceAck` is the retained owner response:

| Field | Type | Stability | Description |
|---|---|---|---|
| `valid` | `Bool` | stable until `quiesceAck.ready` | Owner has reached its local fatal barrier. |
| `generation` | `UInt(64.W)` | stable while `valid && !ready` | Must equal the request generation. |
| `descriptorKey` | `TemplateGroupDescriptor` key | stable while `valid && !ready` | Must equal the request descriptor key. |
| `ownerIndex` | `UInt(log2Ceil(TemplateFatalOwnerCount).W)` | stable while `valid && !ready` | Owner bit to set in `ackBitmap`. |
| `ownerMaskBit` | `UInt(TemplateFatalOwnerCount.W)` | stable while `valid && !ready` | One-hot bit for `ownerIndex`. |
| `stateReleased` | `Bool` | stable while `valid && !ready` | Owner released all matching releasable state. |
| `stateQuarantined` | `Bool` | stable while `valid && !ready` | Owner quarantined matching consumed/unsafe state. |
| `inFlightClear` | `Bool` | stable while `valid && !ready` | No matching operation can still mutate. |
| `creditAckMask` | `UInt(TemplateCreditDomainCount.W)` | stable while `valid && !ready` | Domains this owner has released or quarantined. |
| `lastSeenSourceGeneration` | `UInt(64.W)` | stable while `valid && !ready` | Diagnostic generation observed by the owner. |

Invalid acks are all-zero except `valid=false`. Acks with mismatched
generation, descriptor key, owner index, or owner mask bit are stale
diagnostics and must not set `ackBitmap`.

Fatal quiescence is a retained request/ack protocol. The fatal controller fans
out one retained `TemplateFatalQuiesceReq` to every owner that may hold,
create, observe, or release group state:

- ROB and BROB/range owners.
- Rename, checkpoint, mapQ, GPR physical-destination, issue, wakeup, writeback,
  and scalar/LSU result arbitration owners.
- LSU, LIQ, STQ, LSID, load ID, store ID, SCB, replay, and memory-response
  owners.
- Target-validation, `VTGT`, `TARGET_PUBLISH`, redirect, and transfer owners.
- Block-control/CTU row production and fill-token owners.
- Recovery, cancel, invalidation transaction, descriptor lease, and final-row
  owners.
- Trace, DFX, diagnostic sample, and fatal-record owners.

Each owner returns a retained `TemplateFatalQuiesceAck` and must hold it stable
until accepted by the fatal controller. Acks with a mismatched generation,
descriptor key, owner index, or owner mask bit are stale diagnostics and do not
set `ackBitmap`. The fatal controller computes
`ackBitmapNext = ackBitmap | (quiesceAck.ownerMaskBit & requiredOwnerMask)`
only when `quiesceAck.valid`, generation, descriptor, owner index, and owner
mask bit all match the retained request. The fatal controller retains
`ackBitmap` until every required owner bit for the current generation is set:
`(ackBitmap & requiredOwnerMask) === requiredOwnerMask`.

`TemplateFatalOwnerIndex` is an implementation enum with stable bit positions
for at least these owners: ROB, BROB/range, rename/checkpoint/mapQ,
GPR destination/writeback, issue/wakeup, LSU/LIQ/STQ/LSID/load/store IDs,
SCB/replay/memory response, target/redirect/transfer, CTU/fill token bank,
recovery/cancel/invalidation transaction, descriptor lease/final, trace/DFX,
and fatal record. Unimplemented owners in a reduced configuration clear their
bits from `requiredOwnerMask`; implemented owners must not share a bit.

Only after the all-acks barrier may the controller perform the single atomic
post-abort transaction:

- release or quarantine every descriptor, token, credit, invalidation
  transaction, and lease/final token for the group;
- mark the descriptor state `RELEASED_AFTER_ABORT`;
- publish one fatal record containing the first owner, reason, source context,
  ack bitmap, and release/quarantine summary;
- clear production-visible owner state for that group while retaining the
  fatal record for diagnostics.

The fatal controller owns `requiredOwnerMask`, `ackBitmap`, all-acks detection,
`quiescent`, and the final teardown barrier. Individual owners own local
in-flight drain, release/quarantine of their state, and `quiesceAck` generation
matching. The descriptor/token-bank owner performs the single post-barrier
descriptor, credit-token, lease/final, and invalidation-transaction release or
quarantine; no other owner may reuse the descriptor key.

`TemplateFatalTeardown` is the single post-all-acks barrier command:

| Field | Type | Description |
|---|---|---|
| `generation` | `UInt(64.W)` | Fatal generation that reached all owner acks. |
| `descriptorKey` | `TemplateGroupDescriptor` key | Poisoned group to release/quarantine. |
| `requiredOwnerMask` | `UInt(TemplateFatalOwnerCount.W)` | Required owners for the generation. |
| `ackBitmap` | `UInt(TemplateFatalOwnerCount.W)` | Final matching ack bitmap. |
| `creditReleaseMask` | `UInt(TemplateCreditDomainCount.W)` | Domains to release when still `Reserved`. |
| `creditQuarantineMask` | `UInt(TemplateCreditDomainCount.W)` | Domains to quarantine when consumed or unsafe. |
| `publishFatalRecord` | `Bool` | Must be true for a production fatal teardown. |

`TemplateFatalTeardownAck` is retained proof of the terminal transaction:

| Field | Type | Description |
|---|---|---|
| `valid` | `Bool` | Terminal teardown has completed. |
| `generation` | `UInt(64.W)` | Matches the teardown command. |
| `descriptorKey` | `TemplateGroupDescriptor` key | Matches the teardown command. |
| `releasedMask` | `UInt(TemplateCreditDomainCount.W)` | Domains released from `Reserved`. |
| `quarantinedMask` | `UInt(TemplateCreditDomainCount.W)` | Domains quarantined from `Reserved` or `Consumed`. |
| `descriptorState` | enum | Must be `RELEASED_AFTER_ABORT`. |
| `fatalRecordIndex` | implementation index | Retained diagnostic record location. |

The watchdog is diagnostic-only until it escalates to `FatalReason=5`; it must
not release credits, advance cursors, or reuse a descriptor key by itself.
Descriptor/context reuse after fatal is reset-only: a normal `FINAL`, recovery,
cancel, or fatal release cannot recycle `lxcpuContextGeneration`,
`templateGeneration`, owner generations, or poisoned descriptor keys without a
reset/context-generation change.

## Port-Level Contract

| Direction | Port | Type | Producer | Consumer | Lifetime |
|---|---|---|---|---|---|
| input | `reserveReq` | `Decoupled[TemplateReserveRequest]` | D3 decode/rename | reservation allocator | Valid until fire or upstream kill. |
| output | `reserveResp` | `Decoupled[TemplateReserveResponse]` | reservation allocator | CTU/diagnostics | Retained response for exactly one classified `reserveReq.fire`; payload stable until `reserveResp.fire`. |
| output | `fillToken` | `Vec(28, TemplateFillToken)` or descriptor-backed queue | reservation allocator | CTU fill | Live until consumed, cancel, recovery, or fatal. |
| input | `rowFill` | `Decoupled[TemplateRowFill]` | CTU | ROB/rename/IQ/LSU/control owners | Stable while stalled. |
| output | `rowFillAck` | `Valid` plus owner key | row owners | CTU/fill ledger | One cycle per accepted fill. |
| input | `cancel` | `Valid[TemplateCancelRecovery.cancel]` | cleanup owners | template ledger | Level or pulse until ack by integration policy. |
| input | `recovery` | `Valid[TemplateCancelRecovery.recovery]` | central recovery | template ledger | Accepted recovery event. |
| output | `recoveryAck` | `Valid[TemplateCancelRecovery.ack]` | template ledger | central recovery | One ack per affected group. |
| output | `trace` | `TemplateTrace` | template owners | DFX/verification | Non-architectural visibility only. |
| output | `fatal` | `TemplateFatal` | assertions/owners | fatal controller | Retained until quiescent teardown ack. |
| output | `quiesceReq` | `Vec(TemplateFatalOwnerCount, Valid[TemplateFatalQuiesceReq])` | fatal controller | all template state owners | Retained per owner until its matching ack is accepted; payload stable while valid. |
| input | `quiesceAck` | `Decoupled[TemplateFatalQuiesceAck]` or bounded owner-serialized equivalent | template state owners | fatal controller | Retained by each owner until accepted; only matching generation/descriptor/source owner index contributes to `ackBitmap`. |
| output | `fatalTeardown` | `Valid[TemplateFatalTeardown]` | fatal controller | descriptor/token bank/lease owners | One atomic post-all-acks barrier command. |
| input | `fatalTeardownAck` | `Valid[TemplateFatalTeardownAck]` | descriptor/token bank/lease owners | fatal controller | Retained proof that descriptor, tokens, invalidation transaction, and lease/final state reached released/quarantined terminal state. |

## Handshake Timing

| Case | Rule |
|---|---|
| Ready-valid stability | Every `Decoupled` payload must remain stable while `valid && !ready`. |
| Reserve request capture | `reserveReq.ready` means the allocator has one retained request/result slot available and can capture the command for exactly one classification. It does not by itself imply acceptance. |
| Reserve fire | `reserveReq.fire` captures the request and classifies once. That classification either accepts all required rows and credits atomically or rejects without mutating any owner. Partial reserve is illegal. |
| Reserve shortage disposition | A shortage, duplicate identity, recovery conflict, fatal conflict, unsupported form, malformed `N`, or too-small ROB is reported only through the retained `reserveResp.rejected` payload. Rejection mutates none of the shortage owners, including diagnostic shortage counters. |
| Reserve response retention | `reserveResp` is a retained `Decoupled` response. `accepted`, `rejected`, `rejectReason`, descriptor, tokens, reserved mask, RID interval, credit-token vectors, and diagnostic fields remain stable from response valid until `reserveResp.fire`. |
| Reserve concurrency | The allocator may deassert `reserveReq.ready` while a prior response is retained, unless it has independent request/result slots that preserve one response per request in order. |
| Fill order | `rowFill` must consume tokens in increasing `childOrdinal`. Out-of-order fill is fatal unless the implementation proves equivalent in-order ROB visibility before mutation. |
| Fill backpressure | Stalled fill holds the exact owner, token, row payload, and owner-credit payload stable. |
| Filled visibility | A row becomes issue/commit/trace-visible only after `rowFill.fire` and owner acks complete. |
| Reserved invisibility | `ReservedUnfilled` rows are resident and recovery-visible, but `canIssue=false`, `canCommit=false`, `trace.valid=false`, and `sideEffect.valid=false`. |
| Response staleness | Responses from pre-fill RF/load helper paths must carry full `TemplateOwnerID`; stale generation or owner mismatch is consumed as diagnostic or fatal, never used for mutation. |
| Bounded fill | Without backpressure or accepted recovery, the group must reach `FINAL` fill or fatal within `templateFillMaxCycles`. |

Accepted response semantics are all-or-none:

- `accepted=1` means ROB, BROB/range, checkpoint, GPR physical destination,
  mapQ, IQ, LIQ, load ID, STQ, store ID, LSID, validation, target publish,
  lease/final, and invalidation transaction tokens have all moved from `Free`
  to `Reserved`, all `ReservedUnfilled` rows are resident, and the descriptor is
  live before the response becomes visible.
- `rejected=1` means no owner-visible state changed. The rejected payload may
  include diagnostic availability snapshots captured before mutation, but those
  snapshots are not side effects and do not advance generations or cursors.
- `accepted` and `rejected` are mutually exclusive. A response with neither bit
  set is illegal once `reserveResp.valid` is high.

## Same-Cycle Priority

| Priority | Events in same cycle | Required behavior |
|---:|---|---|
| 1 | `globalClear`/reset | Clear local valid state, suppress reserve/fill side effects, and release or quarantine credits by reset policy. |
| 2 | accepted central recovery | Apply recovery before reserve/fill. A fill for a killed row is stale and must not mutate. |
| 3 | fatal already active | Block reserve/fill for matching group; continue quiescent teardown. |
| 4 | cancel for exact active group | Cancel unfilled tokens and suppress same-cycle fill for the canceled row. |
| 5 | reserve and fill | Existing fill may proceed only if it targets a different live group and all shared owner ports arbitrate without reducing atomic reserve guarantees. |
| 6 | multiple reserves | Deterministic arbitration by implementation owner; loser sees `ready=false` and keeps payload stable. |
| 7 | multiple fills | Only one serialized fill may mutate a group per cycle unless the implementation proves ordinal order and independent owner ports. |

## Reservation Semantics

Atomic reserve must check all of these predicates before mutation:

- `formId` is valid and `encodedN` is in `1..22`.
- `rowCount` equals the exact D3 formula: `N+3`, `N+3`, `N+5`, or `N+6`.
- `rowCount <= 28`.
- `fullProfileSupported` implies `robEntries >= 32`; otherwise full-profile
  template reserve rejects with `RobTooSmall`.
- ROB has a contiguous wrapped RID interval of `rowCount` free entries starting
  at the allocation cursor, and every resulting `rid` is unique by value and
  wrap.
- The `(bidRobid,gidRobid,ridRobid)` identities do not duplicate any live row,
  and their narrow projections match `bid`, `gid`, and `rid`.
- All owner credits are available for every row and group domain in the plan:
  ROB, BROB/range, checkpoint, GPR physical destination, mapQ, IQ, LIQ,
  loadId, STQ, storeId, LSID, validation, target-publish, lease/final, and
  invalidation transaction capacity.
- Recovery and fatal owners are not holding a conflicting group or STID.

On acceptance:

- allocate the whole contiguous RID interval;
- write every reserved row as `ReservedUnfilled`;
- allocate all owner credits and bind typed conserved tokens to the descriptor
  and rows;
- create one `TemplateGroupDescriptor`;
- return first/last RID and `rowCount` to CTU;
- expose non-architectural reserve trace only.

On failure:

- do not advance ROB, BROB/range, checkpoint, GPR physical destination, mapQ,
  IQ, LIQ, loadId, STQ, storeId, LSID, validation, target publish,
  lease/final, invalidation transaction, or template generation state;
- do not create `ReservedUnfilled` rows;
- return exactly one reject reason.

## Row Fill Semantics

Fill consumes pre-reserved tokens; it does not allocate resources. For each
filled row:

| Row kind | Normal owner after fill | Required side effects |
|---|---|---|
| `VFORM` | ROB/commit trace only | Records template parent event as a normal row; no direct RF/memory/control effect. |
| `SP_SUB` | rename/IQ/execute/RF through normal scalar path | SP destination uses pre-reserved GPR/mapQ credit. |
| `SP_ADD` | rename/IQ/execute/RF through normal scalar path | SP destination uses pre-reserved GPR/mapQ credit. |
| `STORE` | IQ/LSU/STQ/SCB through normal store path | Store uses pre-reserved LSID/STQ/store identity; no direct `storeRequest`. |
| `VLOAD` | IQ/LSU/LIQ through normal load path | Load data may feed later target calculation through normal dependency paths. |
| `LOAD` | IQ/LSU/LIQ/RF through normal load path | Restore destination uses normal load writeback ownership. |
| `VTGT` | IQ/execute/control-data path | Computes target data; no direct redirect. |
| `TARGET_PUBLISH` | normal control/redirect owner | Publishes target through the same owner as scalar control rows. |
| `RESTORE_R10` | rename/IQ/execute/RF through normal scalar path | Uses pre-reserved destination credit. |
| `FINAL` | ROB/final lease owner | Completes only after every earlier row is filled and completed; releases group lease at normal retire or recovery. |

`FINAL` must not retire before all prior group rows are filled and complete.
The final row is the only normal release point for descriptor lease state on a
successful template.

## Recovery, Cancel, Stale, And Fatal Behavior

| Scenario | Required behavior |
|---|---|
| Failed reservation | No mutation. `reserveResp.rejected=1`, exact reject reason, no descriptor, no token, no `ReservedUnfilled`. |
| Accepted reservation | All rows resident as `ReservedUnfilled`, all owner credits held, group descriptor live, tokens valid. |
| Serialized fill | Fill ordinal `k` consumes token `k`, reconstructs full `TemplateOwnerID`, validates row kind/memory shape, and mutates only RID `groupBaseRid+k`. |
| Flush before fill | Accepted recovery removes killed `ReservedUnfilled` rows and releases their unconsumed credits; retained rows remain invisible until filled. |
| Flush during fill | Recovery has priority. If the fill row is killed, the fill is stale and cannot mutate. If retained, fill may proceed only after recovery ack proves the row is still reserved and token-valid. |
| Stale fill | Owner mismatch, stale generation, wrong RID, reused token, or wrong ordinal is suppressed. If the stale producer could corrupt state, latch fatal. |
| Cancel before side effects | Exact cancel releases descriptor, tokens, and unfilled credits; filled rows are handled by normal recovery/retire ownership. |
| Recovery after side effects | Filled rows are pruned or retained by normal ROB/LSU/rename recovery using full BID/RID/STID identity. CTU does not manually undo RF/memory/control state. |
| Fatal before fill | Block the group, suppress fill/reserve side effects, release/quarantine unconsumed credits through fatal teardown. |
| Fatal after fill | Stop new fills for the group and wait for normal owners to reach quiescence before releasing the descriptor. |
| Fatal while fatal | Report `FatalWhileFatal`, keep the first fatal owner/code as the teardown authority, and do not reuse the group key. |

## Assertions

Implementations must include these assertions at the module boundary or in
directly bound property modules:

- `robEntries >= 32 || !fullProfileSupported`.
- `encodedN >= 1 && encodedN <= 22` on accepted reserve.
- Accepted `FENTRY`/`FEXIT` `rowCount === encodedN + 3`.
- Accepted `FRET_RA` `rowCount === encodedN + 5`.
- Accepted `FRET_STK` `rowCount === encodedN + 6`.
- `rowCount <= 28`.
- Accepted reserve changes every required owner credit in the same transaction
  or changes none.
- Accepted reserve covers every `TemplateCreditDomain`: `ROB_ROW`,
  `BROB_RANGE`, `CHECKPOINT`, `GPR_PHYS_DEST`, `MAPQ`, `IQ_ENTRY`,
  `LIQ_ENTRY`, `LOAD_ID`, `STQ_ENTRY`, `STORE_ID`, `LSID`, `VALIDATION`,
  `TARGET_PUBLISH`, `LEASE_FINAL`, and `INVALIDATION_TXN`.
- Rejected reserve does not change any allocation cursor, free count, live
  mask, generation, descriptor valid bit, or credit count.
- Accepted reserve marks exactly `rowCount` rows as `ReservedUnfilled`.
- `ReservedUnfilled` implies no issue, no commit, no architectural trace, no
  RF write, no memory request, no redirect, and no completion pulse.
- `bid === bidRobid.value`, `gid === gidRobid.value`,
  `groupBaseRid === groupBaseRidRobid.value`, and `rid === ridRobid.value`
  before any mutation.
- Every token RID equals `groupBaseRidRobid + childOrdinal` with wrap preserved.
- Every fill owner reconstructs the same group fields as the descriptor,
  including narrow identity fields and full ROBID sidecars.
- Full `TemplateOwnerID` equality covers every group, row, memory, generation,
  source, form, and projection field.
- Fill `rowKind` equals the D3 row plan entry for `childOrdinal`.
- Fill memory identity matches the row-kind table.
- A token can be consumed at most once.
- Fill ordinals are strictly increasing for a group unless a formally proven
  equivalent serializer is present.
- `FINAL` fill occurs only after all non-final tokens are consumed.
- `FINAL` commit/lease release occurs only after all prior group rows are
  filled and complete.
- Recovery/cancel/fatal dominates same-cycle fill for killed rows.
- Stale fill cannot mutate ROB, rename, IQ, LIQ, STQ, RF, memory, control, or
  trace state.
- No CTU path drives direct `storeRequest`, `rfWriteRequest`, SP publish,
  commit completion, or redirect mutation in production mode.
- Hidden parent rows, private rowless validators, global fences, and direct CTU
  RF/SP/memory/commit/redirect effects are absent from the production path.
- Bounded fill watchdog either reaches `FINAL`, observes backpressure, observes
  accepted recovery, or latches fatal before timeout.
- Fatal requests carry `FatalReason` in `0..5` and a retained source-context
  envelope.
- Fatal quiesce requests remain asserted until every owner returns a
  generation-qualified ack.
- `ackBitmap` is retained and only matching-generation acks set bits.
- Fatal teardown reaches all owner acks and `quiescent` before the single
  atomic `RELEASED_AFTER_ABORT` release/fatal-record transaction.
- Fatal descriptor/context reuse is reset-only.

## Migration From Current Ports

Current reduced ports are implementation evidence only. Production migration
must replace them as follows:

| Current port/path | Current behavior | Replacement |
|---|---|---|
| `DispatchROBAllocator.allocValid/allocRow` single-row allocation | Allocates one scalar ROB row per accepted request. | Add atomic multi-row reserve that allocates `rowCount` contiguous wrapped RIDs and all owner credits in one transaction. |
| `ROBEntryBank.allocValid` single-row insertion | Inserts one normal row and advances one slot. | Add `ReservedUnfilled` insertion for an exact RID interval and a later fill mutation path keyed by token. |
| `TemplateParentIdentity` | Carries STID, PE, PC/raw/opcode, BID/GID/RID, ROB slot, block BID, and 16-bit generation. | Replace or extend with lossless `TemplateOwnerID` group and row fields, including context generation, engine local TID, row kind/ordinal, memory identity, checkpoint, and 64-bit generations. |
| `TemplateRenameSidecarTable` fences | Holds per-STID decode/issue/memory fences around one private parent. | Replace with descriptor/tokens and normal owner credits. No global fence is permitted outside an explicitly single-STID configuration. |
| `BlockControlTemplateSequencer.parentRequest` | Starts private CTU sequencing from a retained parent sidecar. | CTU consumes accepted `TemplateGroupDescriptor` plus tokens from D3 reserve. |
| `BlockControlTemplateSequencer.loadRequest/loadResponse` | Private load gather path. | Emit normal `VLOAD`/`LOAD` rows through LIQ/LSU ownership. Responses return through normal wakeup/writeback dependencies. |
| `BlockControlTemplateSequencer.storeRequest` | Direct SCB/store request with `ownsStqRow=false`. | Remove from production. `STORE` rows use normal IQ/LSU/STQ/SCB insertion with pre-reserved STQ/store/LSID credits. |
| `BlockControlTemplateSequencer.rfWriteRequest` | Direct RF restore/write request. | Remove from production. `SP_*`, `LOAD`, and `RESTORE_R10` rows write RF through normal execute/load writeback and commit ownership. |
| `BlockControlTemplateSequencer.completion` and `spPublishValid` | Completes parent and publishes SP from CTU. | Replace with normal row completion and `FINAL` lease release. |
| `TemplateCompletion.redirectValid/nextPc` | Direct return redirect metadata. | Replace with normal `VTGT`/`TARGET_PUBLISH` control rows and existing redirect authority. |
| Top-level disabled template writeback inputs | Placeholder direct writeback integration. | Delete once normal rows reach writeback through issue/execute/LSU. |

## Verification

Required checks for this contract document:

```bash
cd /Users/zhoubot/linx-isa/rtl/LinxCore && rg -n "TemplateFatalQuiesceReq|TemplateFatalQuiesceAck|quiesceReq|quiesceAck|requiredOwnerMask|ackBitmap|TemplateCreditDomain|TemplateCreditToken|TemplateCreditTokenBank|creditTokenHandle|creditTokenLookup|creditTokenConsume|BROB_RANGE|INVALIDATION_TXN|descriptor-backed|serialized|R1050" docs/chisel/interfaces/TemplateD3ReservationFill.md
git -C /Users/zhoubot/linx-isa/rtl/LinxCore diff --check -- docs/chisel/interfaces/TemplateD3ReservationFill.md
PYTHONPATH=/Users/zhoubot/FishToucher/src python3.11 -m fishtoucher.cli mailbox --flow /Users/zhoubot/FishToucher/config/linxisa.example.json --verify-artifacts --artifact-root /Users/zhoubot/linx-isa /Users/zhoubot/FishToucher/runs/linxcore-r1050-d3-typed-credit-fatal-ports-20260722/mailbox.jsonl
```

Future implementation gates must add elaboration checks for the bundle packet,
directed reserve/fill tests for all four forms, fatal/recovery/cancel tests,
and a reduced generated-RTL cross-check that proves template rows appear only
through normal ROB commit trace.
