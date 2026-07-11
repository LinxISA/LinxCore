# RecoveryBackendControl

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/recovery/RecoveryBackendControl.scala`
- Fabric child: `chisel/src/main/scala/linxcore/recovery/RecoveryFabric.scala`
- Resident identity owner: `chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala`
- Canonical backend composition: `chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala`
- First connected producer: `chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Integrated proof: `chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala`
- Contract ID: `LC-CHISEL-RECOVERY-BACKEND-001`

## Purpose

`RecoveryBackendControl` is the canonical wiring owner between retained Linx
recovery producers and backend state consumers. It keeps identity lookup,
source indexing, cleanup acceptance, and ROB mutation in one reviewable
boundary without moving producer policy into the ROB or LSU.

The owner preserves full block BID separately from ring `ROBID`, compares age
only within one STID, and imports no ARM exception, power, exclusive-monitor,
or condition-code state.

## Parameters

| Parameter | Meaning |
|---|---|
| `nonLsuSourceCount` | Number of independently retained full-BID sources before the scalar-LSU source. |
| `stidCount` | Number of instantiated STID recovery lanes. |
| `peCount` | Number of PE-scoped lanes per STID. |
| `entries` | Resident ROB ring capacity. |
| `bidWidth` | Exact full block-generation identity width. |
| `peIdWidth`, `stidWidth`, `tidWidth` | Linx scope widths. |

## Ownership Contract

1. Non-LSU sources retain their own events and enter the fabric at stable
   indices. The scalar-LSU source is appended as the final source.
2. The LSU full-BID lookup request is forwarded unchanged to the resident ROB;
   the echoed result is forwarded unchanged back to the LSU source owner.
3. `RecoveryFabric` alone owns source arbitration, class merge, and registered
   cleanup intent.
4. `robFlush.req.valid` asserts only when `intent.valid`, `robPruneValid`, and
   the external all-consumer `intentReady` handshake are all true.
5. The owner exposes the complete cleanup intent. It never assumes that absent
   BCTRL, rename, frontend, LSU, tile, vector, MTC, or PE consumers are ready.
6. A blocked intent remains stable, retains all source/class state behind it,
   and produces no ROB side effect.

## Integration Status

R646 extracts this ownership from the former real-ROB proof wiring and uses it
in both `RecoveryCleanupROBProbe` and `DecodeRenameROBPath`. The backend path
routes scalar-LSU lookup ports through `DispatchROBAllocator` to resident
`ROBEntryBank` rows and exports the shared cleanup handshake. The full
fetch/RF/ALU composition connects one retained scalar redirect source. Direct
BCC/IEX/PE trigger-owner wiring, the canonical scalar-LSU source connection,
full BROB pointer recovery, and multi-STID oldest-block watermarks remain open.
R647 carries parameterized source provenance through arbitration, class merge,
and registered cleanup. The backend exposes resolved-cause and consumed-payload
masks so source-private order/LSID sidecars are applied only for the request
payload that survives selection. This closes the provenance prerequisite for a
second live source; connecting those producers remains separate work.
Marker-only frontend redirects are not connected as backend sources until the
owner can provide the authoritative full BID for the incremented cleanup BID.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only RecoveryBackendControlSpec`
- `bash tools/chisel/run_chisel_recovery_cleanup_rob_probe.sh`
- `bash tools/chisel/run_chisel_scalar_redirect_recovery_source_probe.sh`
- `bash tools/chisel/run_chisel_frontend_fetch_rf_alu_trace_top_xcheck.sh`

The focused test proves composition and handshake-qualified ROB publication.
The generated real-ROB probe proves exact lookup, blocked-intent stability, and
resident-row pruning. The full fetch/RF/ALU cross-check protects commit
behavior through the integrated backend boundary.
