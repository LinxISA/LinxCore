# TOP architecture

## TOP is a typed router {#ARC-TOP-010}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 depends-on=ARC-TOP-001 -->

TOP MUST instantiate the configured boxes, connect their typed interfaces, and
adapt external memory, interrupt, reset, and debug ports. TOP MUST NOT own ROB
entries, rename maps, issue queues, cache policy, age selection, commit policy,
or recovery arbitration.

The callable `TOP` contains exactly one IFU, CTU, OOO, IEX, LSU, and DTU in one
clock/reset domain. It contains no `Reg`, `Mem`, or `Queue`, and IEX has no
direct control path to IFU. Recovery reaches IFU only from its OOO owner.

## Box interfaces have one source of truth {#ARC-TOP-011}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

All cross-box Bundle definitions and complete box IO definitions MUST live
under `linxcore/top/interface`. A generated interface manifest and executable
shape tests must describe the same elaborated types.

## Principal widths share one parameter hierarchy {#ARC-TOP-012}
<!-- ndf: kind=req level=must layer=L1 status=stable since=0.1 -->

IFU, CTU, OOO, and IEX widths MUST be derived from a centralized parameter
profile supporting 2, 4, 6, and 8 lanes. Resource counts that are not lane
widths, including execution-pipe and LSU-pipe counts, remain explicit
parameters with validated defaults.
