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

`STQLoadForwardQuery` now carries the merged baseline line together with:

- `baseValidMask`;
- `loadDataReturned`;
- `scbReturned`.

The retained STQ response returns those fields unchanged inside `query`. A
normal response is translated directly into `LoadForwardSelection`, so the
canonical STQ owner is not expanded into another `LoadStoreForwardStore` CAM
image.

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
- selected not-ready store identity reaching E4;
- hard-block backpressure without E3/E4 leakage;
- flush suppression;
- unequal `STQ=4`, `ROB=8`, `LSID=40`, and non-default token width.

## Remaining integration gap

I0.15a freezes the selector/result seam, but it does not yet claim the complete
OOO load lifecycle. The next packet must bind an exact
`OooIexLoadGeneration` to one canonical LIQ row, drive the three STQ query
ports from accepted load launches/relaunches, mutate wait/miss state only in
that LIQ owner, and return a hit/fault through the existing atomic OOO terminal
fabric. `OooIexLoadUnit` and `ScalarLSULoadPath` must not both own retry or
W1/W2 publication for the same production load.
