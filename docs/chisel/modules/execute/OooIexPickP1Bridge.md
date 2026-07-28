# OooIexPickP1Bridge

## Purpose

`OooIexPickP1Bridge` converts one retained `OooIexPickToken` into one exact
`OooIexP1Request`. It addresses the canonical IQ query port and never stores a
copy of the scheduling row, payload sidecar, age state, or PC value.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexPickP1Bridge.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexPickP1BridgeSpec.scala`

## Exact join

The bridge proves all of the following before asserting P1 valid:

- query state is `ResidentS3`, the scheduling row is valid, and it is not
  already in flight;
- PE/STID/epoch/transaction, full `RobMemberKey`, and full dispatch
  reservation equal the retained picker token;
- class/bank/entry address equals the row reservation;
- the generated recipe is valid, matches the row opcode, and dispatches;
- the current physical child class equals generated `pcReadClass` before PC is
  requested; that child has a valid in-range primary-parent index and a valid
  PC token within `parentCount`.

The recipe's `pcReadRequired/pcReadClass` and the sidecar's derived
`pcParentIndex` become the P1 controls. A non-PC recipe, or a sibling child of
a split recipe, does not require any PC token. In particular, scalar PCR store
addresses read PC in AGU while their STD data children do not.

## Failure and ownership

A malformed join waits until the P1 lane reports capacity, then emits both a
typed `OooIexPickJoinReject` and an exact `OooIexReadRepick`. In the composed
module the IQ claim and retry occur on the same edge, leaving the canonical row
resident and not in flight. This is fail-closed evidence; repeated malformed
metadata is a design error, not an alternate execution path.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexPickP1BridgeSpec
```

The four UT cases cover exact PC metadata, a non-PC recipe, child-class
selection, downstream backpressure, and missing-PC-metadata reject/repick. The standalone generated
module is 913 SystemVerilog lines and contains no payload or scheduling-row
storage reference.
