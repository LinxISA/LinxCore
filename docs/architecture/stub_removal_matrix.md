# Stub Removal Matrix

This matrix tracks modules that previously behaved as pass-through placeholders and the minimum bar for being considered non-stub.

## Completion Rule

A module is considered **done** only if it satisfies both:

1. Owns state and/or arbitration logic (queue, scoreboard, pointer, pipeline stage register, or explicit ready/valid arbitration).
2. Produces outputs consumed by another stage/module in the canonical top-level graph.

## BCC IFU

- `src/bcc/ifu/f0.py`
  - Done: owns fetch PC state and redirect/advance arbitration.
- `src/bcc/ifu/f1.py`
  - Done: owns predictor/tag metadata state and emits sideband metadata.
- `src/bcc/ifu/f2.py`
  - Done: computes fetch-window advancement and backpressure handshake.
- `src/bcc/ifu/f3.py`
  - Done: owns ibuffer enqueue/dequeue and BSTART metadata emission.
- `src/bcc/ifu/f4.py`
  - Done: owns stage register/cutpoint (not pure wire-through).
- `src/bcc/ifu/icache.py`
  - Done: owns cache response pipeline metadata (valid/tag/data staging).
- `src/bcc/ifu/ctrl.py`
  - Done: owns checkpoint/flush sequencing state.

## BCC OOO / IEX / LSU

- `src/bcc/ooo/{dec1,dec2,ren,s1,s2,rob,pc_buffer,flush_ctrl,renu}.py`
  - Done: decode/rename/dispatch/ROB/flush logic owned per stage file.
- `src/bcc/iex/{iex,iex_alu,iex_bru,iex_fsu,iex_agu,iex_std}.py`
  - Done: lane pipelines with stage registers and issue/redirect behavior.
- `src/bcc/lsu/{liq,lhq,stq,scb,mdb,l1d}.py`
  - Done: queue-based LSU behavior with forwarding/conflict/drain/violation signals.

## Block Fabric + PEs

- `src/bcc/bctrl/bisq.py`
  - Done: true multi-entry queue, head/tail memory, backpressure.
- `src/bcc/bctrl/brenu.py`
  - Done: tag/epoch allocation with handshake and wrap.
- `src/bcc/bctrl/brob.py`
  - Done: outstanding command tracking and completion-to-ROB mapping.
- `src/bcc/bctrl/bctrl.py`
  - Done: cmd_ready arbitration and response muxing for TMA/CUBE/VEC/TAU.
- `src/tmu/noc/{pipe,node}.py`
  - Done: explicit valid/ready transport with deterministic latency.
- `src/tmu/sram/tilereg.py`
  - Done: concurrent tile read/write behavior for PE command path.
- `src/{tma,cube,vec,tau}/*.py`
  - Done: command accept state + fixed latency response generation (not combinational echo).

## Verification Gates

- `tools/generate/lint_no_stubs.py`
- `tests/test_stage_connectivity.sh`
