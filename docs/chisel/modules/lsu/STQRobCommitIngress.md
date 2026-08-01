# STQRobCommitIngress

`STQRobCommitIngress` converts one ROB-authorized semantic store beat into the
physical `WAIT -> Commit` transition owned by the LSU.

## Contract

The token carries exact ROB/BROB ownership plus full logical first LSID,
store ID, request count, and beat ordinal. It deliberately carries no STQ
index or lease generation. The ingress scans current STQ rows and requires:

- exactly one row with the same complete exact owner;
- exact `full ID == logical first ID + beat` relations;
- one live `Wait` row with both STA and STD converged;
- no active recovery for the LSU boundary; and
- CommitQ credit for that exact row.

Only when all conditions hold does `commit.ready` assert. The surrounding
`STQSCBCommitPath` makes token acceptance, STQ `Wait -> Commit`, and CommitQ
enqueue one atomic edge. Missing, duplicate, stale, half-filled, recovering,
or queue-blocked matches retain the upstream token and expose typed diagnostic
outputs. A physical slot number from ROB is never trusted.

## Verification

`STQRobCommitIngressSpec` covers unique acceptance, CommitQ backpressure,
missing/duplicate rows, half-filled rows, and recovery fencing.
`OooIexExecutionStoreIntegrationSpec` starts from a grouped ROB pair-store
batch, drains two semantic beats without a physical-index sideband, and proves
two committed STQ rows, atomic four-fragment SCB admission, and one logical
completion.
