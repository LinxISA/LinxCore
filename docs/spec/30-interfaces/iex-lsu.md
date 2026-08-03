# IEX to LSU interface

## Independent address and store-data paths {#IFC-IEX-LSU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=D-IDENTITY-001,PRM-RESOURCE-002 -->

IEX shall provide independently backpressured load-address, store-address, and
store-data transactions. The default topology shall expose two paths of each
kind, and LSU responses shall match complete memory and ROB identities rather
than address or queue slot alone. OOO shall allocate program-order memory IDs,
IEX shall allocate each memory transaction and initial load attempt on the
canonical Dispatch-to-IQ acceptance edge, and LSU shall own LIQ reissue,
repick, and later attempt rebind.

## LSU request and result Bundles {#MEC-IEX-LSU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-IEX-LSU-001 -->

`IEXLSUIO` uses parameterized vectors of `LoadIssueTxn`, `StoreAddressTxn`, and
`StoreDataTxn`; `LoadResultTxn` carries the latest accepted `MemoryIdentity`.
The same load-pipe count sizes independent LSU-to-IEX `LoadReissueTxn`,
`LoadRepickTxn`, and `LoadCancelTxn` vectors. Reissue and repick carry exact
current/next attempt identities; load cancel is retained under backpressure and
cancels speculative dependents without freeing LIQ residency. Store address
and data remain separate transactions until the LSU-owned join accepts both
exact identities.

`LoadReissueTxn` and `LoadRebindApplyTxn` implement the Option-A ownership
transfer. The memory transaction, allocation, destination, and pipe remain
unchanged; only the attempt advances to its exact modulo successor. The common
fire cancels the old exact attempt in every LSU owner and returns the LIQ row
to replay eligibility. An issued lower-memory transaction is retained only as
a stale-response tombstone and cannot coalesce with the new attempt.
