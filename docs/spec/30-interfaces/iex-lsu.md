# IEX to LSU interface

## Independent address and store-data paths {#IFC-IEX-LSU-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=D-IDENTITY-001,PRM-RESOURCE-002 -->

IEX shall provide independently backpressured load-address, store-address, and
store-data transactions. The default topology shall expose two paths of each
kind, and LSU responses shall match complete memory and ROB identities rather
than address or queue slot alone.

## LSU request and result Bundles {#MEC-IEX-LSU-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-IEX-LSU-001 -->

`IEXLSUIO` uses parameterized vectors of `LoadRequestTxn`,
`StoreAddressTxn`, and `StoreDataTxn`; `LoadResultTxn` carries the original
`MemoryIdentity`. Store address and data remain separate transactions until the
LSU-owned join accepts both exact identities.
