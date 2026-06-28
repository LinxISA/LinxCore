# Common Interface Bundles

## Source Mapping

- Chisel: `rtl/LinxCore/chisel/src/main/scala/linxcore/common/InterfaceBundles.scala`
- Tests: `rtl/LinxCore/chisel/src/test/scala/linxcore/common/InterfaceBundlesSpec.scala`
- Previous pyCircuit owners:
  - `src/common/types.py`
  - `src/common/interfaces.py`
  - `src/bcc/backend/params.py`
  - `src/bcc/backend/decode.py`
  - `src/bcc/backend/dispatch.py`
  - `src/bcc/backend/state.py`
- LinxCoreModel evidence:
  - `model/LinxCoreModel/model/ModelCommon/bus/RenameBus.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/MemRequest.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/FetchReqBus.h`
  - `model/LinxCoreModel/model/ModelCommon/bus/PEResolveBus.h`
  - `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`
- Contract IDs: `LC-IF-CHISEL-COMMON-001`

## Purpose

`InterfaceBundles.scala` is the Phase 1 shared type packet for the Chisel RTL
lane. It defines passive bundle shapes for frontend-to-decode packets, decoded
uops, renamed uops, issue queue entries, LSU request/response packets, ROB
rows, and lightweight trace probes.

The packet intentionally carries both LinxCoreModel-style `bid/gid/rid`
identity and the hardware 64-bit `blockBid` sideband. These are separate
domains: `bid/gid/rid` identify model row/group/block ownership, while
`blockBid` is the BROB/BCTRL block identity that must not be truncated into a
model `CommitInfo.bid` field.

## Interface

### `InterfaceParams`

| Field | Default | Source contract |
|---|---:|---|
| `fetchWidth` / `decodeWidth` | 4 | `OooParams.fetch_w`, `dispatch_w`; 64-bit F4 window |
| `issueWidth` / `commitWidth` | 4 | Current bring-up issue and retire widths |
| `pcWidth` / `windowWidth` | 64 | F4 and D1 packet interfaces |
| `opcodeWidth` | 12 | pyCircuit opcode catalog IDs |
| `insnWidth` / `lenWidth` | 64 / 4 | 16/32/48/64-bit Linx encodings plus byte length |
| `archRegWidth` / `physRegWidth` | 6 / 6 | `REG_INVALID=0x3f`, 64-entry ptag bring-up |
| `robEntries` / `iqEntries` | 64 / 32 | pyCircuit `OooParams` defaults |
| `blockBidWidth` / `blockUidWidth` | 64 / 64 | model and DFX identity fields |
| `uopUidWidth` | 64 | `UOP_UID_FIELDS` |
| `lsidWidth` | 32 | backend and model memory request ID fields |
| `checkpointWidth` | 6 | F4/D1 start-marker checkpoint contract |

### Bundle Summary

| Bundle | Purpose | Key fields |
|---|---|---|
| `FrontendDecodePacket` | F4-to-D1 decode ingress | `valid`, `pc`, `window`, `pktUid`, `checkpointId` |
| `UopUidPacket` | DFX/dynamic uop identity | `uid`, `parentUid`, `fetchPacketUid`, `fetchSlot`, `replayDepth` |
| `DecodedUop` | Canonical D2 architectural uop | `src[3]`, `dst[1]`, `imm`, `rid/bid/gid`, `lsid`, memory class/split metadata, boundary metadata, raw instruction |
| `RenamedUop` | D3/S1 backend-visible uop | renamed source/destination tags, dispatch target, memory class/split metadata, model identity, block identity |
| `IssueQueueEntry` | IQ residency record | `valid`, `inflight`, `issueSlot`, `target`, `uop` |
| `LsuRequest` | Scalar LSU request envelope | `uid`, load/store flags, `rid/bid/gid/subrid`, `modelLsId`, `lsid`, address/data/mask |
| `LsuResponse` | LSU-to-ROB result envelope | load/store flags, `rid/gid`, `lsid`, address/data/mask, trap |
| `RobRow` | Integrated ROB row skeleton | valid/done, decoded identity, renamed operands, memory flags, boundary and trap fields |
| `LinxTraceProbe` | Lightweight stage/row visibility | `pc`, `opcode`, `uid`, `rid/bid/gid`, `blockBid` |

## Logic Design

This packet contains no stateful pipeline logic. The only executable behavior is
compile-time parameter validation through `InterfaceParams`.

The bundle shape follows these rules:

- preserve F4/D1 width contracts from `src/common/interfaces.py`;
- preserve LinxCoreModel instruction lengths 2, 4, 6, and 8 bytes from
  `CheckMInstSize`;
- preserve `REG_INVALID=0x3f` and `TRAP_BRU_RECOVERY_NOT_BSTART=0x0000b001`;
- preserve block boundary kind ordering `fall, cond, call, ret, direct, ind,
  icall` from `src/common/isa.py`;
- keep source operands as `{P,T,U,CArg}` and destination operands as
  `{Gpr,T,U}`;
- carry model `ROBID` domains as `rid`, `bid`, and `gid`;
- carry reduced memory-class and store-split sidebands through
  `isLoad`, `isStore`, `storeSplitIntent`, `isLoadStorePair`, `isStorePcr`,
  and `cacheMaintainNoSplit`;
- carry the 64-bit BROB/BCTRL identity as `blockBid` with a separate valid bit;
- keep LSU scalar `lsid` as 32 bits while also preserving the model `lsID`
  ROBID-like field as `modelLsId`.

## Timing

All records are passive `Bundle` definitions. Timing belongs to the future
stage owners:

- F4/D1 own fetch packet movement and no-slot-compaction behavior.
- D1/D2 own decoded uop construction and boundary metadata.
- D2/D3 own rename, resource allocation, and source readiness.
- S1/S2/IQ own issue residency and `inflight` transitions.
- LSU owns memory progression and response ordering.
- ROB/CMT own retirement, trap precision, and commit trace emission.

## Flush/Recovery

The bundles carry checkpoint, model identity, and block identity fields needed
by later recovery logic but do not implement flush. Future recovery modules must
use the live backend-owned checkpoint and block epoch fields rather than
reconstructing them from stream scans.

## Trace/Observability

`LinxTraceProbe` is a narrow visibility packet for future stage probes. It does
not replace `CommitTraceRow`; architectural QEMU comparison still uses commit
rows and `tools/chisel/trace_schema_adapter.py`.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only InterfaceBundles`
- `bash tools/chisel/build_chisel.sh`

The focused test checks default widths, constant encodings, boundary enum
ordering, 4-bit instruction length fields, decoded/renamed/LSU/ROB/trace packet
field widths, and Chisel elaboration through a probe module.
