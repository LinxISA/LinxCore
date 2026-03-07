# Stub Removal Matrix

This matrix tracks modules that previously behaved as pass-through placeholders
and the minimum bar for being considered non-stub in the current design.

## Completion rule

A module is considered done only if it satisfies both:

1. It owns state or arbitration logic such as a queue, scoreboard, pointer,
   pipeline stage register, or explicit ready/valid arbitration.
2. It produces outputs consumed by another stage or module in the canonical
   top-level graph.

## BCC IFU

- `src/bcc/ifu/f0.py`
  - Done: owns fetch PC state and redirect or advance arbitration.
- `src/bcc/ifu/f1.py`
  - Done: owns predictor or tag metadata state and emits sideband metadata.
- `src/bcc/ifu/f2.py`
  - Done: computes fetch-window advancement and backpressure handshake.
- `src/bcc/ifu/f3.py`
  - Done: owns enqueue or dequeue behavior and BSTART metadata emission.
- `src/bcc/ifu/f4.py`
  - Done: owns stage register or cutpoint behavior.
- `src/bcc/ifu/icache.py`
  - Done: owns cache response pipeline metadata.
- `src/bcc/ifu/ctrl.py`
  - Done: owns checkpoint and flush sequencing state.

Current-top note:

- these IFU modules are implemented and non-stub
- they are not the canonical instruction-supply path in the current bring-up
  top graph, which uses host/QEMU input into the IB

## BCC OOO, IEX, and LSU

- `src/bcc/ooo/{dec1,dec2,ren,s1,s2,rob,pc_buffer,flush_ctrl,renu}.py`
  - Done: decode, rename, dispatch, ROB, and flush logic owned per stage file.
- `src/bcc/iex/{iex,iex_alu,iex_bru,iex_fsu,iex_agu,iex_std}.py`
  - Done: lane pipelines with stage registers and issue or redirect behavior.
- `src/bcc/lsu/{liq,lhq,stq,scb,mdb,l1d}.py`
  - Done: queue-based LSU behavior with forwarding, conflict, drain, and
    violation signals.

## Block fabric and PEs

- `src/bcc/bctrl/bisq.py`
  - Done: true multi-entry queue with head, tail, count, and flush trimming.
- `src/bcc/bctrl/brob.py`
  - Done: BID allocation tracking, outstanding command lifecycle, and
    completion-to-ROB mapping.
- `src/bcc/bctrl/bctrl.py`
  - Done: lane arbitration, shared command envelope routing, and PE response
    muxing.
- `src/bcc/bctrl/brenu.py`
  - Legacy only: not part of the canonical top-level graph for current closure.
    Do not use it as the architecture authority for current tag or epoch
    semantics.
- `src/tmu/noc/{pipe,node}.py`
  - Done: explicit valid/ready transport with deterministic latency.
- `src/tmu/sram/tilereg.py`
  - Done: concurrent tile read/write behavior for PE command path.
- `src/{tma,cube,vec,tau}/*.py`
  - Done: command accept state plus deterministic response generation.

## Verification gates

- `tools/generate/lint_no_stubs.py`
- `tests/test_stage_connectivity.sh`
