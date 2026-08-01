# STQDataBank

## Purpose

`STQDataBank` is the production physical payload owner for canonical scalar
STQ rows. It separates store data from `STQEntryBank` metadata/status while
preserving one allocation and recovery authority.

Source and tests:

- `chisel/src/main/scala/linxcore/lsu/STQDataBank.scala`
- `chisel/src/test/scala/linxcore/lsu/STQDataBankSpec.scala`
- `chisel/src/main/scala/linxcore/ooo/OooIexStoreStqFabric.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexStoreStqFabricSpec.scala`

Reference evidence:

- `Documents/a.txt`, sections 3.11, 3.13 and 4: two independent STD writes,
  control/data partitioning, and STQ PA/BM/Data/Attribute/Status arrays.
- `tools/LinxCoreModel/model/lsu/store_unit/stq.cpp`:
  `STQueueEntryInfo::init`, `STQ::mergeStore`, and `STQ::lookupForLoad`.

## Ownership

`STQEntryBank` remains the sole owner of allocation, row status, address,
identity, recovery and terminal free. `STQDataBank` owns only byte mask and
payload bits. It does not allocate a row, compare program age, commit a store,
or select a recovery suffix.

A write is legal only when all of the following still match the resident row:

- physical index and lease generation;
- exact PE/STID, BID/BROB generation, RID generation, logical member and
  resident generation;
- full LSID, full store ID, logical first IDs, request count and beat;
- valid `Wait` state with no prior data completion.

The check is repeated during both physical phases. Recovery, free or slot reuse
cancels a pending transaction instead of publishing stale data.

## Physical organization and timing

Each row is 64 bytes and has two equal banks:

| Bank | Bytes | Default role |
|---|---:|---|
| 0 | 0-31 | scalar/integer STD and low vector half |
| 1 | 32-63 | high vector/line half |

The payload is unaligned within the row. STA owns address and final placement,
so STD is allowed to arrive before STA without depending on address operands.

```text
accept exact STD -> write byte mask -> write payload data + exact completion
        cycle N          cycle N+1                 cycle N+2
```

There are two retained write pipelines. They may accept and complete two
different physical rows together. Same-row collisions, malformed sizes and
stale leases fail closed. `dataReady` is not asserted at request acceptance or
after the mask phase; the STQ status owner changes it only from the final exact
completion.

## Consumers

`OooIexStoreStqFabric` joins physical payload data back onto the canonical STQ
row projection. `STQSCBCommitBackend`/`STQCommitDrain` and future replicated
load-forward snapshots therefore consume one data image. The data bank exposes
the complete line and byte mask for the future E1-CAM/E3-read forwarding path,
while the current scalar commit interface uses the low parameterized payload.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only STQDataBank
bash tools/chisel/run_chisel_tests.sh --only STQEntryBank
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegration
bash tools/chisel/build_chisel.sh
```

Directed tests use unequal `STQ=4`, `ROB=8`, and 40-bit LSID identities. They
cover two writes in one cycle, mask-before-data completion, stale generation,
same-row collision, pending recovery cancellation, bytes 32-63 in the second
bank, joined-row visibility, and closed O3/IEX/store elaboration.

## Remaining gaps

- Feed `lineData/byteMask` into the canonical load E1/E3 forwarding pipeline.
- Add vector/FSU payload widths and their arbitration policy.
- Define parity/ECC, scrub and low-power behavior.
- Prove physical timing and macro mapping at the production STQ depth.
