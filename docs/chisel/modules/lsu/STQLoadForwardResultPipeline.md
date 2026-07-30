# STQLoadForwardResultPipeline

`STQLoadForwardResultPipeline` is the I0.15a production seam between the
generation-qualified STQ snapshot and the common load E3/E4 result stages. It
does not own STQ rows, LIQ rows, load generations, cache arrays, or terminal
publication.

## Ownership

- `STQEntryBank` owns store metadata/status/recovery.
- `STQDataBank` owns physical store masks and data.
- `STQLoadForwardingPipeline` owns replicated E1 tag snapshots and retained E3
  STQ responses.
- `LoadForwardResultPipeline` owns the registered E3/E4 source-return and miss
  classification stages after byte selection.
- The future OOO load composition owns the exact load-generation-to-LIQ-row
  association. This module does not create a second load lifecycle tracker.

`LoadSourceLineMerge` is the adjacent combinational source adapter. SCB bytes
override L1D bytes only where `scb.validMask` is set; invalid SCB bytes cannot
destroy valid cache data. L1D and SCB returned evidence stays separate from the
merged valid mask.

## Contract

`STQLoadForwardQuery` carries the merged baseline line together with:

- `baseValidMask`;
- `loadDataReturned`;
- `scbReturned`;
- the canonical LIQ `loadId` lease;
- the producer-qualified `attempt` generation;
- the physical `returnPipeIndex`.

The retained STQ response returns those fields unchanged inside `query`. A
normal response is translated directly into `LoadForwardSelection`, so the
canonical STQ owner is not expanded into another `LoadStoreForwardStore` CAM
image.

I0.15c-b3a registers only the minimal `{loadId, attempt, returnPipeIndex}`
identity sidecar in parallel with the common result pipeline; it does not
duplicate the 64-byte baseline query image. `e3Identity` and `e4Identity` are
aligned with their corresponding valid bits under bubbles and back-to-back
traffic, and recovery flush clears both valid stages. Downstream LIQ mutation
can compare `{loadId, attempt}` before accepting an asynchronous forwarding
result instead of trusting an opaque token or a bare row index.

I0.15c-b3c2 adds an explicit `normalReady` credit boundary. Normal STQ
responses do not enter E3 unless the downstream E3/E4 plus retained-result
owner has capacity. Structural hard blocks use their independent ready path,
so a full normal-result transport cannot hide or consume a hard-block record.
The E4 boundary also carries separate `scbReturned` and `stqReturned` evidence
into the canonical LIQ result instead of reconstructing source completion from
the merged byte mask.

Selected data-not-ready stores are normal results. Their `waitMask` and exact
`waitStore` identity enter E3/E4 and classify as `StoreDataNotReady` for LIQ
mutation.

The following are structural hard blocks and take the retained `hardBlock`
boundary instead of entering E3:

- an unknown older store address;
- E1/E3 metadata or physical-data generation drift;
- missing or half-range-ambiguous full LSID authority;
- an overlapping cross-line store;
- malformed load identity;
- a cross-line load before split-load ownership exists.

This separation prevents structural uncertainty from looking like uncovered
cache bytes or a legal store-data wait.

## Pipeline

```text
L1D partial line --+
                   +--> LoadSourceLineMerge --> STQ E1 query
SCB partial line --+                              |
                                                  v
                                  retained STQ E3 response
                                      |                 |
                               normal selection     hardBlock
                                      |
                                      v
                           LoadForwardResultPipeline
                                E3 --> E4
```

The common result pipeline computes:

```text
validMask      = baseValidMask | forwardMask
dataComplete   = (validMask & loadByteMask) == loadByteMask
sourcesReturned = loadDataReturned && scbReturned && stqReturned
```

Miss priority remains:

```text
StoreDataNotReady > DataNotComplete > AwaitingSources >
ReturnPortBlocked > NoMiss
```

## Verification

Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only STQLoadForwardResultPipeline
bash tools/chisel/run_chisel_tests.sh --only LoadForwardPipeline
bash tools/chisel/run_chisel_tests.sh --only STQLoadForwardingPipeline
bash tools/chisel/run_chisel_tests.sh --only OooIexStoreStqFabric
bash tools/chisel/run_chisel_tests.sh --only OooIexExecutionStoreIntegration
```

The directed suite proves:

- byte-exact SCB-over-L1D partial merging;
- partial baseline plus canonical STQ bytes completing one load;
- back-to-back exact row/attempt/return-pipe identity alignment in E3/E4;
- selected not-ready store identity reaching E4;
- hard-block backpressure without E3/E4 leakage;
- independent normal-result credit backpressure without blocking hard blocks;
- flush suppression;
- unequal `STQ=4`, `ROB=8`, `LSID=40`, and non-default token width.

## Remaining integration gap

I0.15c-b2b gives every production OOO load one exact canonical LIQ lease and
attempt identity and removes the former duplicate retry/return owner.
I0.15c-b3a carries that identity through the STQ asynchronous result pipe, and
I0.15c-b3c2 now drives three retained query lanes from canonical LIQ launches,
accepts the three retained STQ response lanes, reserves result capacity, and
applies exact E4 results back to that LIQ owner. The remaining boundary is the
closed OOO/store-fabric/scalar-LSU wrapper: common two-phase recovery,
structural-hard-block policy, final BID/BROB ordering projection, physical SCB
source ownership, and the translation/cache/coherence system still require
integration. No additional OOO request/retry/data residency may be introduced.
