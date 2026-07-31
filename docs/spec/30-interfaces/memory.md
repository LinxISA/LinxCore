# External memory interface

## Identity-qualified memory traffic {#IFC-MEMORY-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=D-IDENTITY-001 -->

Instruction and data memory requests and asynchronous responses shall carry a
transaction identity with generation. A response shall not be accepted by
address, queue slot, or low transaction bits alone.

## Memory request and response Bundles {#MEC-MEMORY-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-MEMORY-001 -->

`MemoryRequestTxn` and `MemoryResponseTxn` share `MemoryTransactionIdentity`.
`TOPIO` exposes separate instruction and data channels so cache, device, and
recovery ownership cannot be inferred from an untyped shared payload.
