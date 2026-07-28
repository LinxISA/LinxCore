# OooIexOldestReadyPicker

## Purpose

`OooIexOldestReadyPicker` is a reusable retained picker for one issue domain.
An issue domain is one uop class plus a mask of physical banks that feed one
execution-pipe arbitration point. The module receives only minimal projections
of canonical IQ scheduling rows; it does not copy row residency, readiness,
payload memory, or recovery ownership.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexOldestReadyPicker.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexOldestReadyPickerSpec.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala`

## Selection policy

Every enabled-bank candidate must contain exact PE/STID/member and
class/bank/entry reservation identity. Malformed candidates are excluded and
reported through `malformed` rather than being allowed to wedge selection.

The picker first selects one oldest eligible candidate independently for each
STID. Same-STID age is the modular concatenation
`{ridGeneration, ridSlot, memberIndex}`. The maximum live IQ population must
fit in less than half of this namespace, making subtraction unambiguous across
RID generation wrap. Physical bank/entry order is only a deterministic tie
break for duplicated ages.

The per-STID winners enter work-conserving round-robin arbitration. A terminal
pick advances the round-robin base to the following STID. The next candidate
may be retained on the same edge, so the domain sustains one pick per cycle
after its initial selection latency.

The ARM reference notes use different oldest rules for different queues: an
age matrix for AGU/STD and next-retire RID restrictions for many ALU cases.
The Linx baseline uses one exact member-age rule for every class. Later
class-specific latency, memory-order, nonspeculative, or safe-mode blockers
filter `eligible`; they do not replace member identity or create another age
owner.

## Retention and canonical in-flight ownership

The selected `OooIexPickToken` is retained under P1 backpressure. The token
contains only the physical query plus exact member/reservation identity. When
it fires, `OooIexIssue` sets `inFlight` in the canonical
`OooIexScheduleRow`. The picker does not retain an issued-row bitmap.

Same-edge refill locally excludes the fired token. From the following cycle,
the IQ owner's `inFlight` bit removes that row from the eligible projection.
An exact read denial or rejected read returns `{member,reservation}` through
`pickRetry`; only that exact resident in-flight row becomes pickable again.
Terminal release now requires `inFlight=true` in addition to the prior exact
member and dispatch reservation.

Recovery prepare drops an unclaimed retained target token through
`blockedCanceled`, blocks new target capture, and lets peer STIDs continue.
Common apply also cancels any retained killed token by exact grouped-ROB
membership. Killed physical rows remain freed by the canonical IEX recovery
apply, not by the picker.

## Remaining integration work

- freeze execution-pipe topology and instantiate disjoint domains for ALU,
  BRU, AGU, STD, FSU, SYS, and CMD throughput targets;
- reuse the selected query read to form a complete P1 row without adding a
  payload-memory read owner;
- add generated `pcReadRequired/pcParentIndex` execution metadata;
- connect P1/I1 read denial and partial-response rejection to `pickRetry`;
- add latency/resource/tracking-vector/load-generation/safe-mode blockers;
- close per-domain starvation counters, coverage, and default-width timing.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexOldestReadyPickerSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexIssueSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexRecoverySpec
```

The focused picker UT covers RID-generation wrap, same-edge refill, retained
backpressure, cross-STID fairness, bank masks, malformed fail-closed behavior,
and exact recovery cancellation. The IEX owner UT covers canonical claim,
retry-to-repick, terminal release, split atomicity, and 2/4/6 decode widths.
For the 2-bank x 4-entry structure case, the picker emits as a separate
3,718-line SystemVerilog module and the main IEX owner is 176,457 lines. The
picker RTL has no payload-memory reference.
