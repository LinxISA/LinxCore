# OooO3IexStorePipeline

## Purpose

`OooO3IexStorePipeline` is the production composition from grouped D2 input
through D3/S1 O3 ownership, typed issue/execute, exact grouped-ROB completion,
and canonical STQ/CommitQ/SCB store retirement.

It instantiates `OooO3RenameCoordinator` with the formal physical profile's
real capability topology and connects it directly to
`OooIexExecutionStorePipeline`. The following seams are private:

- O3 S1 publication into retained IEX residency;
- exact dispatch-slot release and PTag recycle;
- every execution terminal completion lane plus fast completion through the
  retained ROB completion buffer;
- semantic ROB store-commit tokens into the sole canonical STQ backend;
- PC-buffer reads requested by issue;
- one common O3/IEX/STQ recovery prepare and apply transaction;
- fast-result PRF writeback and issue wakeup.

The boundary still exports real architectural or SoC consumers: BCTRL and
trace, memory requests/responses, external execution families, translation/PMA
classification, cache/device store transport, ROB commit, non-flush evidence,
and global recovery requests.

## Ownership guarantees

No external Boolean ready can publish S1, discard an execution completion,
recycle a PTag, or acknowledge a committed store. All such handshakes end at
the owner that mutates the corresponding retained state.

Two ordinary completion lanes and fast resolve may arrive in one cycle. They
enter `OooRobCompletionBuffer` together and drain through the current
single-write ROB without producer loss. Store retirement carries semantic
ROB/BROB/member and full memory-order identity; it never exposes an STQ index.

## Verification

```bash
env LINX_CHISEL_SBT_MEM_MB=4096 \
  bash tools/chisel/run_chisel_tests.sh --only OooO3IexStorePipeline
env LINX_CHISEL_SBT_MEM_MB=4096 \
  bash tools/chisel/run_chisel_tests.sh --only OooRobStoreCommitStqIntegration
```

The top structural IT uses bounded test capacities and CHIRRTL elaboration so
the complete owner graph fits the 4 GiB local JVM budget. Child SystemVerilog
and dynamic tests independently cover O3 recovery, fourteen-lane execution,
fast resolve, canonical STA/STD convergence, exact ROB-token matching,
CommitQ, SCB, serialized completion, and terminal STQ release. A full combined
SystemVerilog emission was intentionally not claimed: it exceeded the 4 GiB
test heap during FIRRTL lowering.

## Remaining production gaps

- Replace typed test classification with physical DTLB/PMP/PMA ownership and
  connect the selected uncached/device fabric.
- Add the physical store-data bank, load forwarding and overlap checks,
  violation detection/replay, and serial-wrap quiescence.
- Implement retained multicycle/system/pointer-authentication/floating-vector/
  engine-command owners rather than leaving explicit typed boundaries.
- Close default-width synthesis timing and generated-graph cost, then promote
  this owner beneath the IFU/CTU/D1 core top.
- Run CoreMark and Dhrystone only after that canonical top promotion; current
  focused gates are integration evidence, not benchmark completion.
