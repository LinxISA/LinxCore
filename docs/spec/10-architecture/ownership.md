# State ownership

## OOO owns architectural retirement {#ARC-TOP-020}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

OOO MUST be the sole owner of ROB allocation, in-order commit, precise trap and
interrupt selection, and the global recovery plan. IFU, CTU, IEX, and LSU own
only their local speculative state and report completion, fault, redirect, or
recovery acknowledgement through typed transactions.

## Rename domains remain distinct {#ARC-TOP-021}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

RENU MUST keep absolute GPR rename state separate from relative T/U rename
state. GPR architectural tags allocate physical tags from a free list; T/U
relative indices allocate ordered tags. SMAP, CMAP, and MAPQ transitions must
be atomic for the accepted instruction prefix.

## DTU is an observer {#ARC-TOP-022}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

DTU MUST own debug and trace collection only. Trace backpressure cannot block
architectural commit, and DTU cannot become an alternate owner of ROB,
recovery, interrupt, or trap state.
