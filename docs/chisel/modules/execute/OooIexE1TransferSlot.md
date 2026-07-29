# OooIexE1TransferSlot

## Purpose

`OooIexE1TransferSlot` is the domain-specific retained boundary where one I2
transaction stops being issue-lane recovery state and becomes execute-owned
E1 state. It does not decode opcodes or publish completion, register-file
writes, wakeups, redirects, or memory requests.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexE1TransferSlot.scala`
- `chisel/src/main/scala/linxcore/ooo/OooBundles.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexE1TransferSlotSpec.scala`

## Atomic ownership rule

```text
I2 owner -- accept fire + exact IQ/dispatch release fire --> retained E1 owner
```

`i2.fire` and `issueRelease.fire` are the same event. If the release sink is
blocked, I2 remains valid and the E1 slot remains empty. Once the event fires,
the slot retains the complete `OooIexI2Transaction` together with:

- exact `ownerClass`;
- physical issue-domain `ownerLane`;
- monotonically advancing `slotGeneration`.

The complete transaction remains stable while `e1.ready` is low. A resident
transaction may drain while a replacement transfers on the same edge; the
old E1 output and new exact release still refer to different retained values.

## Validation and recovery

Admission requires exact PE/STID group identity, valid BID and reservation,
membership in the configured class/bank projection, coverage of the selected
child's generated recipe capability by the static physical-domain capability,
a logical-source mask equal to all valid sources, and a
bypass mask contained by that source mask. The I2 row is a snapshot captured
on the earlier pick/claim edge and may still contain the pre-claim `inFlight`
bit; only the canonical IQ release sink revalidates live in-flight ownership.
Its `ready/fire` is coupled to slot acceptance. A malformed immutable shape
raises `rejected` as a fail-stop diagnostic and cannot fire either acceptance
or IQ release.

Before transfer, matching grouped-ROB recovery or exact load cancel blocks the
acceptance; the issue lane still owns cancellation. After transfer, the E1
slot checks those events independently, suppresses `e1.valid` in the matching
cycle, reports the complete killed transaction, and clears residency on the
edge. Load cancellation uses the complete
`{STID,epoch,producer RobMemberKey,loadGeneration}` token, so numerical tag or
stale generation reuse cannot kill a new owner.

## Current boundary and remaining gaps

The module closes one physical-domain protocol, not the complete execute
topology.
Remaining work is:

- route each retained E1 transaction to its typed FU or external FSU owner;
- implement ALU/BRU result paths and AGU/LSU request/resolve ownership;
- arbitrate W1/W2/W3 bypass, committed wakeup, RF write, ROB completion, and
  redirect sinks from exact execute transactions;
- prove default-width throughput, recovery, and backpressure in the canonical
  IFU-to-OOO-to-IEX composition.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooIexE1TransferSlotSpec
```

The focused test covers release backpressure, atomic transfer, retained E1
payload stability, same-cycle drain/refill, exact recovery suffix kill, stale
and exact load generations, generation advancement, class/bank mismatch
fail-stop behavior, recipe-capability mismatch rejection, and multi-class
ownership through the fabric test.
