# LinxCore IFU Architecture

This document defines the canonical LinxCore instruction-fetch architecture.
The IFU contains two independently backpressured engines:

- **I-SIDE** fetches instruction bytes, performs restricted predecode, expands
  each variable-length instruction into one 64-bit instruction record, and
  writes those records into the Instruction Buffer.
- **B-SIDE** predicts control flow. It consumes prediction requests and
  resolved/training events through decoupled interfaces and never owns
  instruction-byte assembly or Instruction Buffer storage.

I-SIDE and B-SIDE are **decoupled engines**. Their `valid/ready` state, queue
occupancy, replay, and progress are independent. A stall in one engine does
not implicitly stall the other.

## Stage numbering

I-SIDE stages are `I-F0`, `I-F1`, `I-F2`, `I-F3`, and `I-F4`. B-SIDE stages
are `B-F0`, `B-F1`, `B-F2`, `B-F3`, and `B-F4`. Both sequences mean stage 0
through stage 4 of their owning engine. Each stage is a real, separately
inspectable boundary. I-SIDE and B-SIDE may be at different stage numbers in
the same cycle; they are not a lockstep ten-stage chain.

`I-F4` is not an alias for Instruction Buffer and does not describe decode
width. LinxCoreModel predictor behavior is a reference for B-SIDE components
and timing, while the stage names above are the canonical LinxCore names.

```text
                    prediction request
             +------------------------------+
             |                              v
PC request -> I-F0 -> I-F1 -> I-F2 -> I-F3 -> I-F4 -> Instruction Buffer
             |          parallel              |
             |          ITLB + L1I             +-> boundary/training event
             v                                  v
          request ID                       B-SIDE queues
             |                                  |
             +----> B-F0 -> B-F1 -> B-F2 -> B-F3 -> B-F4
                                                 response/correction |
                                                                     v
                                                              fetch steering

Instruction Buffer -> D1 Decode: four 64-bit instructions per accepted group
```

Every cross-engine transaction carries at least `STID`, fetch request ID,
fetch epoch, PC, and checkpoint identity. Responses are matched by identity,
not by assumed cycle alignment.

## I-SIDE

### I-F0 — PC request capture

- Accepts a PC selected by fetch steering for one runnable STID.
- Aligns the PC to the L1I cache-line address while retaining the byte offset.
- Allocates the fetch request ID and snapshots the current fetch epoch.
- Sends an independent prediction request to B-SIDE.
- Holds the full request context until I-F1 accepts it.

### I-F1 — parallel ITLB and L1I lookup

- Launches ITLB lookup and L1I lookup in parallel from the same I-F0 request.
- The L1I lookup uses the virtual index; the translated physical address
  validates the physical tag and access permission before the line can be
  consumed.
- Neither result is accepted without the matching request ID, STID, and epoch.
- ITLB and L1I latency may be implementation parameters, but their architectural
  launch is parallel rather than TLB-first serial.

### I-F2 — translation/cache result resolution

- Joins the ITLB result and L1I lookup result by request identity.
- On an ITLB hit, validates permission and the physical L1I tag.
- On an L1I hit, retains the complete cache line and originating PC context.
- On an L1I miss, allocates or joins the instruction-miss/refill transaction.
- On an ITLB miss, emits an **inner flush** for the affected STID and epoch,
  invalidates younger I-SIDE work, and creates the translation-replay request.

An ITLB-miss inner flush is frontend-local. It does not retire state, flush the
ROB, or become an architectural recovery event. It cancels only matching and
younger speculative fetch transactions. B-SIDE receives the cancellation
identity so a late prediction cannot redirect the replayed stream.

### I-F3 — cache-line capture and byte-stream alignment

- Captures one complete cache line plus its PC/offset/fault context.
- Orders bytes from the requested PC and aligns the resulting byte stream.
- Preserves cross-line carry bytes and joins them with the next returned line.
- Presents an ordered byte stream to I-F4; it does not determine instruction
  length, produce an instruction payload, or perform predecode.

### I-F4 — instruction formation, restricted predecode, and buffer write

- Determines the 2/4/6/8-byte encoded instruction length and completes
  instruction assembly from the ordered I-F3 byte stream.
- Recognizes `BSTART` and `BSTOP` boundary forms and produces their boundary
  metadata.
- Zero-extends the complete encoded instruction into `inst[63:0]`.
- Does not perform general opcode decode, operand decode, immediate extraction,
  split/fuse analysis, branch prediction, or template expansion.
- Atomically writes the formed 64-bit instruction records and metadata into
  the per-STID Instruction Buffer when capacity is available.
- Holds its output stable under Instruction Buffer backpressure.

The Instruction Buffer is a queue after I-F4, not a pipeline-stage alias. Each
entry contains:

- `inst[63:0]`, with unused upper bits zero;
- PC and encoded byte length;
- STID, request ID, fetch epoch, and checkpoint identity;
- `is_bstart` and `is_bstop`;
- the complete effective B-SIDE `PredictionRecord`
  (`predictionTag`, `branchPc`, `taken`, `target`, `kind`, `provider`,
  `checkpointId`), if available;
- fetch fault metadata.

## B-SIDE

B-SIDE is a branch-prediction engine derived from the predictor organization
used by LinxCoreModel. Its canonical structures are:

- BTB family: BTB, UBTB, and PBTB;
- direction prediction: TAGE and BIM;
- history state: per-STID GHR/GHRQ and speculative RAS;
- target prediction: RAS for returns and IBTB for indirect targets;
- loop prediction: loop predictor and loop buffer;
- prediction-response, checkpoint, cancellation, and training queues.

The structures are architectural microarchitecture requirements; table sizes,
banking, and exact internal latency remain parameters.

### B-F0 — L0/NLP and history snapshot

- Accepts a decoupled `(STID, PC, request ID, epoch, checkpoint)` request.
- Looks up the L0/NLP predictor for a zero-distance next-line decision.
- Atomically allocates a prediction tag and exact history row containing the
  full request identity, immutable pre-request `ghrBefore`, and complete RAS
  image/pointer/count snapshot.
- Carries those snapshots to later consumers; later stages do not resample live
  GHR or RAS. Path-history checkpointing follows the same owner contract when
  implemented.

### B-F1 — uBTB and RAS

- Looks up uBTB for a fast target/type candidate.
- Reads the snapshotted RAS for a possible return target.
- Launches the larger PBTB/BTB, BIM, TAGE, and IBTB accesses.
- May publish an early prediction tagged by request identity.
- Keeps all candidate information tagged by request identity.

### B-F2 — PBTB/BTB and BIM

- Looks up PBTB/BTB for control-flow type and target candidates.
- Collects BIM direction and confidence.
- Carries the original history snapshot; it does not resample live history.

### B-F3 — short/medium TAGE and IBTB lookup

- Collects short- and medium-history TAGE providers.
- Starts/collects IBTB lookup for indirect-target candidates.
- Forms the current direction/target candidate and confidence.
- Retains the checkpoint needed to restore GHR/GHRQ/RAS.

### B-F4 — static prediction, long TAGE, IBTB/loop, and final arbitration

- Runs the static predictor from identity-matched I-F4 boundary metadata and
  collects long-history TAGE, final IBTB, loop predictor, and loop-buffer
  candidates. Static prediction belongs to B-F4, not I-F4.
- Performs the final exact-kind RAS check.
- Selects the final provider using type, history length, confidence, and target
  availability.
- Publishes a decoupled final prediction response to fetch steering.
- Publishes the history recovery key, corrected conditional delta, and typed
  Call/Return RAS delta with a tuple-changing redirect proposal; it does not
  mutate GHR or RAS before that proposal returns from the redirect arbiter as
  the canonical prune.
- Retains the response until accepted or cancelled by matching inner flush,
  backend recovery, or epoch change.
- Sends prediction metadata toward the matching Instruction Buffer entry.
- Does not write instruction bytes and does not perform predecode.

The prediction arbitration contract is:

- stage rank: `B-F4 > B-F3 > B-F2 > B-F1 > B-F0 > sequential`;
- backend typed restart is not a predictor provider and has higher priority
  than every B-SIDE prediction;
- within B-F4, an exact RAS return target and a high-confidence IBTB indirect
  target are same-rank target authorities selected by decoded control type;
- direction override order is
  `loop > long-TAGE > short-TAGE > BIM > static`;
- BTB/PBTB supplies direct targets; a direction provider does not invent one.

If any later B-SIDE stage differs from an already accepted lower-ranked
prediction in `{taken, branchPc, target, kind}`, that later stage emits an
identity-qualified prediction correction. B-F4 is the final correction point.
If the accepted earlier result has already driven fetch, the correction:

- produces a frontend inner flush for the matching STID/request/epoch;
- marks the STID history redirect pending without immediately changing GHR/RAS;
- changes the matching fetch epoch, preserves the correction producer, and
  cancels younger I-SIDE and B-SIDE work;
- on canonical prune, restores the exact request-owned GHR/RAS snapshots,
  appends the corrected conditional direction or applies the corrected
  Call/Return push/pop exactly once, and prunes younger history rows;
- restarts the corrected PC at I-F0;
- does not flush ROB/backend architectural state.

B-F4 is the last stage allowed to issue a prediction-driven inner flush. After
its final result is accepted, the immutable `PredictionRecord` follows the
instruction bundle through the Instruction Buffer and is associated with every
valid D1 lane.

Post-B-F4 validation is type-specific. Dispatch checks direct/call properties
that require no runtime operand. BRU E1 checks conditional `setc.*` direction
and indirect/return `setc.tgt` targets after their operands are available. Any
mismatch enters `BRU flush + recover`, restores predictor checkpoints through
the accepted recovery event, and publishes the architectural restart PC to
I-F0. It must not be reported as a frontend-only inner flush.

The Chisel `IfuBackendFeedbackBridge` implements this comparison and IFU
feedback boundary. It retains transaction ID, packet UID, fetch sequence,
request PC, prediction tag, epoch, and checkpoint independently. Correct
validation emits only actual-result training; mismatch training and the keyed
backend restart advance atomically. Dispatch/BRU event production and full-BID
ROB/BROB cleanup remain backend-composition responsibilities.

Resolved branch and block-control events train the relevant BTB, TAGE, BIM,
IBTB, and loop structures. Training is keyed by full STID/request/
checkpoint identity. TAGE training uses the request-owned pre-branch history,
not live GHR. A stale event may update neither learned tables nor speculative
state. Correct resolves release their history row; mispredict resolves retain
it until a keyed backend BRU recovery restores that exact checkpoint and
applies the actual conditional and Call/Return deltas. ITLB recovery whose
trigger never reached B-SIDE restores the oldest killed GHR/RAS snapshot;
start/reset clears the selected STID speculative GHR/RAS explicitly. The
oldest-killed fallback is legal only for an unkeyed ITLB miss. Prediction and
backend recovery must carry and match the exact request-owned history key.

## Decoupled-engine interface contract

The minimum logical channels are:

| Channel | Producer | Consumer | Payload |
| --- | --- | --- | --- |
| `fetch_req` | fetch steering | I-SIDE | STID, PC, request ID, epoch, checkpoint |
| `pred_req` | I-F0 | B-F0 | matching fetch identity and PC |
| `pred_rsp` | B-F0..B-F4 | fetch steering/final metadata join | direction, target, confidence, provider, checkpoint, final bit |
| `boundary_event` | I-F4 | B-F4 | BSTART/BSTOP location and accepted identity |
| `inner_flush` | I-F2 or accepted B-F0..B-F4 correction | I-SIDE/B-SIDE | STID, request/packet/prediction tag, epoch, replay PC, cause, history recovery action, conditional delta |
| `resolve_train` | Dispatch/BRU/recovery | B-SIDE | actual direction/target/kind and full prediction/recovery identity |

All channels use explicit `valid/ready` or queue semantics. No interface may
assume a fixed cycle relationship between I-F stage state and B-F stage state.

## D1 Decode contract

- D1 reads exactly up to four consecutive Instruction Buffer entries from one
  STID per accepted decode group.
- Each selected input is already one fixed 64-bit instruction value.
- D1 performs the first full opcode decode, operand and immediate extraction,
  illegal-instruction checks, and uop-shape formation.
- D1 consumes the `BSTART`/`BSTOP` hints but validates them against full decode.
- D1 associates the complete effective `PredictionRecord` with every valid
  lane; sharing immutable backing storage is permitted, but a global current
  prediction register is not.
- D1 does not reconstruct a variable-length byte window and does not
  concatenate neighboring Instruction Buffer entries.
- From D1 onward, all instruction payloads are fixed-width `inst[63:0]`.

If fewer than four consecutive entries are available, D1 may issue the
available prefix according to the group-admission contract. It never compacts
past an invalid, cancelled, faulting, or different-STID entry.

## Required invariants

1. I-F0 through I-F4 and B-F0 through B-F4 are two independent five-stage
   engines; Instruction Buffer is a queue after I-F4.
2. ITLB and L1I launch in parallel in I-F1.
3. ITLB miss at I-F2 and accepted B-F0..B-F4 prediction corrections produce
   identity-qualified frontend inner flushes; B-F4 is the final such point.
4. I-F3 determines 2/4/6/8-byte length and completes cross-line assembly;
   I-F4 predecodes only `BSTART`/`BSTOP` boundary metadata and normalizes the
   completed instruction to 64 bits.
5. Every Instruction Buffer instruction payload is 64 bits.
6. D1 reads at most four fixed 64-bit instructions and performs the first full
   decode.
7. Every valid D1 lane carries the complete effective prediction record.
8. Every prediction mismatch detected after B-F4 uses BRU flush/recover, never
   another prediction-driven inner flush.
9. B-SIDE owns prediction; I-SIDE owns instruction bytes and predecode.
10. I-SIDE and B-SIDE advance independently and communicate only through
   decoupled, identity-qualified channels.
11. Backend-resolved misprediction uses typed recovery and restarts I-F0.

The production D1 implementation is `D1InstructionDecodeStage`. It consumes a
four-entry `D1InstructionGroup` directly, performs full decode without an
intermediate byte-window/slot representation, and copies the complete final
prediction record into each decoded uop. I-F3 derives a dynamic instruction
UID from an independent 64-bit monotonic allocator. Allocation advances only
when an assembled group is accepted, remains stable under backpressure, and
does not alias when fetch-packet identity high bits change.

## Generated-RTL throughput gate

The canonical hot-cache supply gate is:

```bash
bash tools/chisel/run_chisel_ifu_throughput_gate.sh
```

It emits `LinxCoreIfu` with architectural 64-byte cachelines and requires
thirty-two consecutive full four-entry D1 groups, final B-F4 metadata on every
lane, and multiple prediction joins plus ordered line contexts in flight. This
gate proves eligible dense sequential IFU supply. It does not prove mixed
instruction lengths, prediction-recovery stress, production decode/dispatch
acceptance, or CoreMark/Dhrystone throughput.

## Non-normative superscalarNPU comparison

Reference evidence: `superscalarNPU` `origin/main@1fae7d0`. This comparison
helps identify reusable mechanisms; it is not a normative dependency and does
not override the LinxCore contract above.

Common mechanisms include B-side/I-side decoupling through an FTQ, per-thread
PC/GHR/RAS state, MBTB/TAGE/IBTB prediction, an Instruction Buffer, and
variable-length instruction-boundary scanning.

At `origin/main@1fae7d0`, the reference interface exposes prediction
taken/target independently for D1 slots 0..3, carries prediction metadata to
BRU, and reports target/address mispredict at E1. Linx generalizes that
per-lane contract to the complete `PredictionRecord`, retains data-dependent
validation in BRU E1, and additionally validates operand-independent
direct/call properties in Dispatch.

The reference design differs materially:

- it uses B0-B4 plus I-side F1-F3 rather than independent
  `I-F0..I-F4` and `B-F0..B-F4`;
- it has no TLB/PIPT translation path, removed UBTB and intra-flush behavior,
  groups main predictors in B2/B3, and places static prediction/context plus
  variable-width Instruction Buffer behavior in F3;
- LinxCore instead requires parallel ITLB/L1I, uBTB plus later-stage
  identity-qualified correction inner flush, staged predictor-accuracy
  refinement, I-F4 boundary-only predecode with 64-bit normalization, and D1
  full decode.
