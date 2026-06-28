# FrontendRegAliasClassify

## Purpose

`FrontendRegAliasClassify` is the decode-time owner for the scalar reg6
namespace used by 16-bit and 32-bit scalar instructions. It keeps scalar GPR
tags separate from LinxCoreModel T/U link aliases before decoded uops enter the
scalar GPR rename path.

This helper does not allocate physical registers, consume T/U queues, perform
rename, allocate ROB rows, or decide whether an unsupported alias can execute.
It only classifies architectural tags so later owners see the same operand
families as the C++ model.

## Source Mapping

Model evidence:

- `model/LinxCoreModel/isa/ISACommon/GPR.h`: `GPR_COUNT = 24`.
- `model/LinxCoreModel/isa/ISACommon/DecodeUtiles.h`: scalar source
  `LINK_LEFT_BOUND = 24`, `LINK_RIGHT_BOUND = 27`,
  `UREG_LEFT_BOUND = 28`, `UREG_RIGHT_BOUND = 31`; scalar destination
  `DST_UREG_BOUND = 30`, `DST_LINK_BOUND = 31`.
- `model/LinxCoreModel/isa/MInst.cpp`: `SetSrcOperand()` maps source tags
  24..27 to `OPD_TLINK` and 28..31 to `OPD_ULINK`; `SetDstOperand()` maps
  destination tag 30 to `OPD_ULINK` and tag 31 to `OPD_TLINK`.
- `model/LinxCoreModel/isa/codec/decodefiles/block16.decode`: compressed
  scalar forms use fixed source tag 24 for T-link reads and fixed destination
  tag 31 for T-queue writes.

## Interface

The helper is a combinational Chisel object used by `FrontendOperandDecode`.

`source(p, valid, tag)` returns a `DecodedOperand`:

- `valid`: preserved from the caller.
- `operandClass`: `P`, `T`, `U`, or `Invalid`.
- `archTag`: the original reg6 tag.
- `relTag`: the class-relative index, or `REG_INVALID` for invalid aliases.

`destination(p, valid, tag)` returns a `DecodedDestination`:

- `valid`: preserved from the caller.
- `kind`: `Gpr`, `T`, `U`, or `None`.
- `archTag`: the original reg6 tag.
- `relTag`: the scalar GPR index for `Gpr`, zero for T/U queue destinations,
  or `REG_INVALID` for unsupported destination aliases.

## Logic Design

Source classification:

- `0..23`: `OperandClass.P`, `relTag = tag`.
- `24..27`: `OperandClass.T`, `relTag = tag - 24`.
- `28..31`: `OperandClass.U`, `relTag = tag - 28`.
- all other active tags: `OperandClass.Invalid`, `relTag = REG_INVALID`.

Destination classification:

- `0..23`: `DestinationKind.Gpr`, `relTag = tag`.
- `31`: `DestinationKind.T`, `relTag = 0`.
- `30`: `DestinationKind.U`, `relTag = 0`.
- all other active tags: `DestinationKind.None`, `relTag = REG_INVALID`.

The destination path intentionally does not use the source T/U ranges. The C++
model treats scalar destination aliases as queue selectors, not as indexed T/U
register numbers.

## Integration

`FrontendOperandDecode` calls this helper from its `setSrc` and `setDst`
paths. `ScalarDecodeRenameBridge` remains a scalar-GPR-only owner, so T/U
operands still stop at the reduced rename boundary until a T/U rename or queue
owner is added.

## Verification

Focused gate:

```bash
bash tools/chisel/run_chisel_tests.sh --only FrontendDecodeStage
```

`FrontendDecodeStageSpec` covers:

- scalar source boundaries `0`, `23`, `24`, `27`, `28`, and `31`;
- scalar destination boundaries `23`, `30`, `31`, and unsupported `24`;
- enum value stability for `OperandClass.{P,T,U}` and
  `DestinationKind.{Gpr,T,U}`;
- Chisel elaboration of a classifier probe.

## Open Work

- Add the T/U rename or queue owner that consumes `OperandClass.T/U` and
  `DestinationKind.T/U`.
- Extend classification for 64-bit SIMT/tile/vector operand namespaces in a
  separate owner.
- Add executable decode simulation once the frontend test lane has a
  cycle-level `DecodedUop` probe driver.
