# LoadForwardResultRetainer

## Purpose

`LoadForwardResultRetainer` is the bounded owner between registered STQ E4
results and the canonical `LoadInflightQueue` apply seam. It prevents a valid
asynchronous forwarding result from disappearing when recovery or another
exact-row writer temporarily owns the LIQ cycle.

## Contract

- Input is ready/valid traffic carrying the complete
  `LoadInflightForwardResult` payload.
- The FIFO head remains stable while LIQ reports `sinkRetryRequired`.
- `sinkAccepted` and `sinkRejectedPermanent` are terminal decisions and each
  dequeues exactly one head.
- Terminal and retry decisions are mutually exclusive; a decision without a
  resident head is a sticky protocol error.
- Hard flush clears buffered results. Typed precise recovery is a retryable
  LIQ decision, so the exact result survives the recovery cycle and is then
  accepted or rejected stale against post-recovery state.

The module owns only result transport. It does not own LIQ rows, STQ rows,
load attempts, recovery selection, or E4 classification.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only LoadForwardResultRetainer
bash tools/chisel/run_chisel_tests.sh --only LoadAttemptBinding
```

The directed tests prove retry retention, exact acceptance drain, permanent
stale drain, recovery classification, and clean protocol diagnostics.
