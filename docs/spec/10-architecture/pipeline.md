# Main pipeline

## IFU to CTU boundary {#ARC-TOP-030}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

IFU MUST deliver D1 packets containing instructions expanded to the common
64-bit representation plus complete fetch and prediction identity. CTU handles
template-block, FENTRY, and FEXIT expansion and writes ordinary or expanded
micro-operation candidates into the instruction buffer.

## OOO covers D1 through S1 {#ARC-TOP-031}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

OOO MUST cover D1 decode, D2 ROB reservation and backpressure, D2-to-D3 rename,
D3 early completion, and D3-to-S1 dispatch. Decode normalizes 16-, 32-, 48-,
and 64-bit encodings to one uop shape carrying `bid`, `rid`, opcode,
source/destination validity and tags, and prediction metadata.

## Dispatch targets typed queues {#ARC-TOP-032}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

Dispatch MUST classify uops into typed IEX or LSU destinations before one
atomic prefix fire. ALU, BRU, AGU, STD, system/multicycle, and CMD resources
remain distinguishable; CMD uses its own issue queue. LSU exposes two load and
two store paths in the default profile.

## Precise recovery crosses owners explicitly {#ARC-TOP-033}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

Recovery MUST use complete instruction identity and explicit prepare/apply
transactions across all affected state owners. Redirect, trap, interrupt, and
debug causes converge on OOO arbitration, while each box performs only its
local flush, rollback, or cancellation.
