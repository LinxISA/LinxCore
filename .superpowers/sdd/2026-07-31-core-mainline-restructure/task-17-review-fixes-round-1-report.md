# Task 17 review fixes — round 1

## Finding addressed

The activation caller previously connected DTU to the permanently invalid OOO
trace output while the live IEX trace bypassed DTU and inherited external
backpressure. `IEX` also generated its public terminal PWrite observation from
the trace arbiter output fire, so that observation disappeared when the trace
consumer stalled.

## RED evidence

`DTUActivationTraceSpec` initially stalled the activation probe's external IEX
trace consumer and issued terminal operations. The test failed with
`rfWriteCount=0` where two terminal PWrite observations were required.

The first elaboration attempt also exposed stale activation-harness tie-offs
for the existing LSU maintenance and memory-fault ports. Those ports were tied
to inert values before recapturing the behavioral RED. They are unrelated to
the trace ownership change.

The precise behavioral RED used 595,635,289 artifact bytes and failed only on
the expected terminal PWrite suppression.

## Ownership correction

- The only live trace producer in the OOO/IEX/LSU activation graph is IEX;
  current OOO and LSU trace outputs are explicitly invalid. The caller now
  connects `iex.io.trace` directly to DTU's always-accepting trace ingress.
- The former `iexTraceReady` activation input now controls DTU's external trace
  export only. Stalling it can retain or drop observations but cannot
  backpressure IEX.
- DTU remains the sole loss-tolerant export owner. No trace adapter or second
  trace state owner was added.
- `IEX` records a typed terminal PWrite observation when the corresponding
  terminal source actually fires. Per-source pending observations are selected
  independently of trace-arbiter fire and trace output readiness. This retains
  the exact ROB, tag, generation, and value without deriving architectural
  progress from an observational handshake.

## Behavioral proof

The compact W4 activation test uses four ROB groups and four BROB entries while
preserving all public identity widths. It issues one valid closed block:

`BSTART_FALL -> ADDI -> ADDI -> BSTOP`

The external DTU trace export remains stalled throughout. The first packet is
retained and checked stable for three cycles. The second packet overflows the
one-slot exporter and is counted as dropped while both terminal writes,
completion publication, and commit continue.

Final exact counters:

- terminal PWrite observations: 2
- resolve completions: 2
- commit transactions: 1
- DTU accepted trace observations: 2
- DTU dropped trace observations: 1

## Verification

- `DTUActivationTraceSpec` — PASS, 1/1; 586,754,201 artifact bytes, 564
  files, 145.464 seconds, peak process-tree RSS 7,593,181,184 bytes.
- `DTUSpec` — PASS, 4/4; 167,214,581 artifact bytes under a
  300,000,000-byte cap.
- New IEX elaboration warnings — none. The final selector emitted only the
  repository's generic multiple-main-class warnings.
- `python3 tools/chisel/check_production_owner_manifest.py` — PASS: 27 closed
  owners, 24 classified emitters, 6 declared adapters, and L1/L2/L3 roles
  mapped.
- `python3 -m unittest tests.test_production_owner_manifest -v` — PASS, 46/46.
- `git diff --check` — PASS.

The owner manifest was updated only after the activation behavior passed. It
now names `DTUActivationTraceSpec` as activation-level evidence for the live
DTU caller.
