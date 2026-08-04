# External memory interface

## Identity-qualified memory traffic {#IFC-MEMORY-001}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=D-IDENTITY-001 -->

Instruction and data memory requests and asynchronous responses shall carry a
transaction identity with generation. A response shall not be accepted by
address, queue slot, or low transaction bits alone.

## Memory request and response Bundles {#MEC-MEMORY-001}
<!-- ndf: kind=arch level=must layer=L2 status=stable since=0.1 refines=IFC-MEMORY-001 -->

`MemoryRequestTxn` and `MemoryResponseTxn` share `MemoryTransactionIdentity`.
Each request also carries a `MemoryAccessKind` that distinguishes instruction
line, instruction translation, ordinary data, and device traffic without
overloading the coherence command. `TOPIO` exposes separate instruction and
data channels so cache, device, and recovery ownership cannot be inferred from
an untyped shared payload.

Responses carry both scalar `data` and a complete cacheline `lineData`.
Acquire responses use `lineData` as the refill image before the requesting
cache owner merges its exact store bytes. A denied or corrupt ownership
response must not grant permission, refill a line, or complete the requesting
store transaction successfully.

The live LSU translates load and store virtual addresses before allocating or
mutating their private rows. Translation requests use an identity namespace
distinct from cache-miss traffic, and only an exact value-plus-generation
response may refill either owner. The LSU publishes quiescence only after all
translation and data transactions accepted on its public memory lanes drain.
