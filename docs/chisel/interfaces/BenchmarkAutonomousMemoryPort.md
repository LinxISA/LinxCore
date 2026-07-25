# Benchmark Autonomous Memory Port

## Source Mapping

- Contract ID: `LC-IF-CHISEL-BENCH-AUTON-MEM-001`
- Intended DUT top: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala`
- Reusable live pipeline surface: `rtl/LinxCore/chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala`
- Commit trace source: `rtl/LinxCore/chisel/src/main/scala/linxcore/commit/CommitTrace.scala`
- Current live-top documentation: `rtl/LinxCore/docs/chisel/modules/top/LinxCoreFrontendFetchRfAluTraceTop.md`

## Purpose

`BenchmarkAutonomousMemoryPort` is the normative external-memory and
termination contract for a generated-RTL autonomous benchmark top. The DUT
executes the reusable live scalar pipeline against an ELF-backed memory model.
The external harness may provide program bytes, data bytes, backpressure, and
terminal observation, but it must not provide QEMU rows, expected commit rows,
expected register values, benchmark-specific PCs, or expected-result inputs to
the DUT.

The interface boundary is first-class: fetch, load, committed stores, UART,
finisher, reset, flush, and trace provenance are named DUT ports. Test code may
inspect those ports; it may not reach into internal live-top wires or inject
architectural outcomes.

## Roles

| Owner | Responsibility |
| --- | --- |
| DUT top | Owns architectural execution, fetch PC progression, load request identity, committed-store observation, UART/finisher decode, halt/trap status, and commit trace rows. |
| External ELF memory model | Owns byte storage initialized from ELF segments, instruction-window assembly, load data return, store application after commit, and optional backpressure. |
| Harness/checker | Owns time limits, terminal status collection, UART transcript collection, and post-run comparison of emitted commit provenance. |

QEMU, model traces, expected rows, and benchmark-specific answer constants are
checker inputs only after a run. They are not legal DUT inputs and are not legal
memory-model responses.

## Interface

All widths come from `InterfaceParams` and `CommitTraceParams` unless stated
otherwise. Signal names below are normative for the generated-RTL top.

### Control

| Signal | Direction | Payload | Rule |
| --- | --- | --- | --- |
| `startValid` | input | `Bool` | Starts a benchmark from `resetPc` and `resetSp`. |
| `resetPc` | input | `UInt(p.pcWidth.W)` | Initial fetch PC. |
| `resetSp` | input | `UInt(p.immWidth.W)` | Initial architectural stack pointer seed. |
| `restartValid` | input | `Bool` | Restarts from `restartPc` and `restartSp` after a harness-visible recovery request. |
| `restartPc` | input | `UInt(p.pcWidth.W)` | Restart fetch PC. |
| `restartSp` | input | `UInt(p.immWidth.W)` | Restart stack pointer seed. |
| `flushValid` | input | `Bool` | External hard flush request. |
| `peId` | input | `UInt(p.peIdWidth.W)` | Scalar PE owner tag for generated frontend packets. |
| `threadId` | input | `UInt(p.threadIdWidth.W)` | STID/thread owner tag for generated frontend packets. |
| `active` | output | `Bool` | DUT is accepting or processing benchmark work. |
| `halted` | output | `Bool` | DUT has stopped because of finisher, trap, unsupported instruction, or explicit hard flush. |
| `trapValid` | output | `Bool` | Final status is architecturally failing or unsupported. |
| `status` | output | `UInt(4.W)` | Encoded idle, fetch, unsupported, finisher-pass, or finisher-fail status. |

### Fetch request and response

| Signal | Direction | Payload | Rule |
| --- | --- | --- | --- |
| `fetchReqValid` | output | `Bool` | DUT requests one aligned instruction window from external memory. |
| `fetchReqReady` | input | `Bool` | External memory accepts the request. |
| `fetchReqPc` | output | `UInt(p.pcWidth.W)` | Byte PC of the requested window. |
| `fetchReqFire` | output | `Bool` | Optional monitor pulse equal to `fetchReqValid && fetchReqReady`. |
| `fetchRespValid` | input | `Bool` | External memory returns the requested instruction window. |
| `fetchRespReady` | output | `Bool` | DUT accepts a fetch response. |
| `fetchRespWindow` | input | `UInt(p.windowWidth.W)` | Little-endian instruction bytes beginning at the accepted request PC. |
| `fetchRespFire` | output | `Bool` | Optional monitor pulse equal to `fetchRespValid && fetchRespReady`. |
| `fetchCurrentPc` | output | `UInt(p.pcWidth.W)` | Current fetch source PC, for observation only. |

The fetch channel is an in-order request/response channel. Unless a later
implementation explicitly adds request tags, the memory model must return
responses in accepted request order. The DUT must not infer the instruction
word from a trace row, QEMU row, PC whitelist, or benchmark oracle.

### Load request and response

The load-memory contract has two mutually exclusive phases.

Phase 1 is the compatibility protocol for the current live top. It uses the
existing `LoadLookupArbiter` and live-top ports only: `loadLookupValid`,
`loadLookupAddr`, `loadLookupPc`, execute-only destination metadata,
`loadLookupExecuteGranted`, `loadLookupReplayGranted`, and the single-cycle
`loadLookupData` input. Phase 1 has no `loadLookupReady`, `loadRespValid`,
`loadRespReady`, `loadRespData`, or `loadRespFaultValid` ports, and therefore
cannot claim load backpressure, held load-response stability, or multi-cycle
memory latency proof.

Phase 2 is the decoupled promotion protocol. It applies only after
`LoadLookupArbiter` and the live top add request ready, response-valid,
response-ready, response payload storage, and backpressure propagation through
the execute and replay consumers. Phase 2 must not be marked promoted by a
wrapper that merely ties a future ready signal true around the Phase 1
single-cycle data input.

| Signal | Direction | Payload | Rule |
| --- | --- | --- | --- |
| `loadLookupValid` | output | `Bool` | Phase 1 and Phase 2: DUT presents one selected load lookup. |
| `loadLookupAddr` | output | `UInt(p.immWidth.W)` | Phase 1 and Phase 2: selected effective byte address. Current `LoadLookupArbiter` selects execute address over replay address. |
| `loadLookupPc` | output | `UInt(p.pcWidth.W)` | Phase 1 and Phase 2: selected load PC. Current `LoadLookupArbiter` selects execute PC over replay PC. |
| `loadLookupSize` | output | `UInt(p.memSizeWidth.W)` | Phase 2 wrapper metadata: selected load size. |
| `loadLookupReturnSignExtend` | output | `Bool` | Phase 2 wrapper metadata: selected return extraction sign-extension policy. |
| `loadLookupExecuteGranted` | output | `Bool` | Phase 1 and Phase 2: execute source owns this lookup. |
| `loadLookupReplayGranted` | output | `Bool` | Phase 1 and Phase 2: replay source owns this lookup. |
| `loadLookupDstValid` | output | `Bool` | Phase 1 current live output is execute-only destination metadata. Phase 2 must expose selected execute-or-replay destination metadata. |
| `loadLookupDstKind` | output | `UInt(2.W)` | Destination class as live `DestinationKind`. |
| `loadLookupDstArchTag` | output | `UInt(p.archRegWidth.W)` | Architectural destination tag. |
| `loadLookupDstRelTag` | output | `UInt(p.archRegWidth.W)` | Relative destination tag. |
| `loadLookupDstPhysTag` | output | `UInt(p.physRegWidth.W)` | Physical destination tag. |
| `loadLookupDstOldPhysTag` | output | `UInt(p.physRegWidth.W)` | Previous physical destination tag. |
| `loadLookupBidValid` | output | `Bool` | Phase 2 wrapper metadata: selected load BID valid bit. |
| `loadLookupBidWrap` | output | `Bool` | Phase 2 wrapper metadata: selected load BID wrap bit. |
| `loadLookupBidValue` | output | `UInt(log2Ceil(p.robEntries).W)` | Phase 2 wrapper metadata: selected load BID value. |
| `loadLookupGidValid` | output | `Bool` | Phase 2 wrapper metadata: selected load GID valid bit. |
| `loadLookupGidWrap` | output | `Bool` | Phase 2 wrapper metadata: selected load GID wrap bit. |
| `loadLookupGidValue` | output | `UInt(log2Ceil(p.robEntries).W)` | Phase 2 wrapper metadata: selected load GID value. |
| `loadLookupRidValid` | output | `Bool` | Phase 2 wrapper metadata: selected load RID valid bit. |
| `loadLookupRidWrap` | output | `Bool` | Phase 2 wrapper metadata: selected load RID wrap bit. |
| `loadLookupRidValue` | output | `UInt(log2Ceil(p.robEntries).W)` | Phase 2 wrapper metadata: selected load RID value. |
| `loadLookupLsIdValid` | output | `Bool` | Phase 2 wrapper metadata: selected full load LSID validity. |
| `loadLookupLsIdValue` | output | `UInt(p.lsidWidth.W)` | Phase 2 wrapper metadata: selected full load-store identity. |
| `loadLookupData` | input | `UInt(p.immWidth.W)` | Phase 1 only: load value sampled in the request cycle. |
| `loadLookupReady` | input | `Bool` | Phase 2 only: external memory accepts the request. This is not a current live-top port. |
| `loadRespValid` | input | `Bool` | Phase 2 only: external memory returns a load value. This is not a current live-top port. |
| `loadRespReady` | output | `Bool` | Phase 2 only: DUT can accept the load response. This is not a current live-top port. |
| `loadRespData` | input | `UInt(p.immWidth.W)` | Phase 2 only: load value after memory-model byte assembly and store overlay application. |
| `loadRespFaultValid` | input | `Bool` | Phase 2 only: optional memory fault response after trap plumbing is specified. |

The load channel is architecturally real memory. The external model must answer
from ELF-backed bytes plus previously committed DUT stores, not from QEMU
expected `mem_rdata`, benchmark answer tables, or row-specific constants.
Store-to-load forwarding inside the DUT remains owned by the live LSU path; the
external model only applies stores after they become committed observations.

Phase 2 wrapper metadata must follow the same execute-vs-replay source as the
accepted lookup. Current arbitration is execute-priority:

- `lookupIsReplay = loadLookupReplayGranted`.
- `loadLookupAddr = Mux(executeGranted, execute.io.loadLookupAddr,
  Mux(replayGranted, liq.io.launchSelectedAddr, 0))`.
- `loadLookupPc = Mux(executeGranted, execute.io.loadLookupPc,
  Mux(replayGranted, liq.io.launchSelectedPc, 0))`.
- `loadLookupSize = Mux(lookupIsReplay, liq.io.launchSelectedSize,
  execute.io.loadLookupSize)` after normalizing the selected value to
  `UInt(p.memSizeWidth.W)`; Phase 2 promotion must not expose the current
  replay helper's wider intermediate width as the autonomous memory-port width.
- `loadLookupReturnSignExtend = Mux(lookupIsReplay,
  liq.io.launchSelectedReturnSignExtend,
  execute.io.loadLookupReturnSignExtend)`.
- `loadLookupDst* = Mux(lookupIsReplay, liq.io.launchSelectedDst*,
  execute.io.loadLookupDst.*)` for valid, kind, arch tag, relative tag,
  physical tag, and old physical tag.
- `loadLookupBid* = Mux(lookupIsReplay, liq.io.launchSelectedBid.*,
  execute.io.loadLookupBid.*)`.
- `loadLookupGid* = Mux(lookupIsReplay, liq.io.launchSelectedGid.*,
  execute.io.loadLookupGid.*)`.
- `loadLookupRid* = Mux(lookupIsReplay, liq.io.launchSelectedRid.*,
  execute.io.loadLookupRid.*)`.
- `loadLookupLsIdValue = Mux(lookupIsReplay,
  liq.io.rows(liq.io.launchIndex).youngestStoreLsIdFull,
  execute.io.loadLookupLsId)`.
- `loadLookupLsIdValid = Mux(lookupIsReplay,
  liq.io.rows(liq.io.launchIndex).youngestStoreLsIdFullValid,
  execute.io.loadLookupValid)`.

No wrapper may publish replay metadata while `loadLookupExecuteGranted` is true,
or execute metadata while `loadLookupReplayGranted` is true.

### Committed store observation

| Signal | Direction | Payload | Rule |
| --- | --- | --- | --- |
| `storeObserveValid` | output | `Bool` | One committed architectural store is visible this cycle. |
| `storeObserveAddr` | output | `UInt(p.immWidth.W)` | Effective byte address of the committed store. |
| `storeObserveData` | output | `UInt(p.immWidth.W)` | Store data before byte-mask merge into memory. |
| `storeObserveSize` | output | `UInt(4.W)` | Store byte size. |
| `storeObserveMask` | output | `UInt(8.W)` | Store byte mask. If absent, the memory model derives a contiguous mask from size and address. |
| `storeObservePc` | output | `UInt(p.pcWidth.W)` | PC of the committed store. |
| `storeObserveSeq` | output | `UInt(traceParams.seqWidth.W)` | Commit sequence for provenance. |
| `storeObserveCycle` | output | `UInt(traceParams.cycleWidth.W)` | Commit cycle for provenance. |
| `storeObserveSlot` | output | `UInt(traceParams.slotWidth.W)` | Commit slot for provenance. |
| `storeObserveBid/Gid/Rid` | output | `UInt(32.W)` each | Model identity from commit trace. |
| `storeObserveRobValid/Wrap/Value` | output | commit ROB ID | Native ROB identity for hardware provenance. |
| `storeObserveBlockBidValid/BlockBid` | output | block BID | Hardware block identity for BCTRL/BROB provenance. |

Committed store observation is post-ROB-commit and non-speculative. The
external memory model may update memory only for a `storeObserveValid` store,
never for store execution, STQ insertion, SCB residency, or internal diagnostic
state. If `CommitTraceRow.mem.valid && mem.isStore` is the source, the
contract maps `mem.addr`, `mem.wdata`, and `mem.size` to the store observation
payload and carries the same row's provenance fields.

Multiple commit lanes may retire multiple stores in one cycle. An implementation
must either expose a vector of `storeObserve*` fields with commit-lane ordering
or serialize them without reordering by `(seq, slot)`. The first generated-RTL
autonomous top may restrict `commitWidth == 1`; if so, that restriction must be
an elaboration requirement, not hidden harness behavior.

### UART and finisher

| Signal | Direction | Payload | Rule |
| --- | --- | --- | --- |
| `uartWriteValid` | output | `Bool` | Pulse for a committed store to `0x10000000`. |
| `uartWriteByte` | output | `UInt(8.W)` | Low byte of the committed UART store. |
| `finisherWriteValid` | output | `Bool` | Pulse for a committed store to `0x10009000`. |
| `finisherCode` | output | `UInt(16.W)` | Low 16 bits of the committed finisher store. |
| `finisherPayload` | output | `UInt(32.W)` | Low 32 bits of the committed finisher store. |
| `finisherPass` | output | `Bool` | Latched true only after committed finisher code `0x5555`. |

UART and finisher are decoded from committed store observation, not from
expected benchmark output. A non-pass finisher code halts with failing status.
A pass finisher code halts with passing status. UART stores are side effects and
also remain normal committed memory observations unless the platform memory map
marks the address as device-only.

## Ready/Valid Timing

Ready/valid stability is required on fetch and committed side-effect channels in
Phase 1, and on fetch, load, and committed side-effect channels in Phase 2:

- When `fetchReqValid` is high and `fetchReqReady` is low, `fetchReqPc` must
  remain stable until `fetchReqFire`.
- When `fetchRespValid` is high and `fetchRespReady` is low,
  `fetchRespWindow` must remain stable until `fetchRespFire`.
- In Phase 1, `loadLookupData` is single-cycle and tied-ready by construction;
  there is no legal claim of load request backpressure, load response
  backpressure, or multi-cycle load-response payload stability.
- In Phase 2, when `loadLookupValid` is high and `loadLookupReady` is low,
  every selected `loadLookup*` payload field must remain stable until request
  acceptance.
- In Phase 2, when `loadRespValid` is high and `loadRespReady` is low, every
  `loadResp*` payload field must remain stable until response acceptance.
- When `storeObserveValid`, `uartWriteValid`, or `finisherWriteValid` is high,
  the payload describes an already committed side effect and must be stable for
  that cycle. Backpressure is not allowed on committed side-effect observation;
  a harness that cannot drain it must fail the run instead of stalling commit.

Request acceptance and response acceptance are edge-observed at the rising
clock. Combinational load memory responses are allowed only in Phase 1 through
the current single-cycle `loadLookupData` compatibility path. Fetch responses
and Phase 2 load responses must be registered or otherwise hold payload stable
under backpressure.

## Reset, Restart, Flush, and Halt Priority

Priority for state-changing controls is:

1. hardware reset;
2. `startValid` or `restartValid`;
3. accepted backend recovery and explicit `flushValid`;
4. committed finisher store;
5. unsupported instruction or architectural trap;
6. ordinary fetch, execute, memory, and commit progression.

Hardware reset clears all valid state and status to idle. `startValid` and
`restartValid` clear prior halt, trap, UART/finisher latch, fetch, load,
store-observation, and commit-retirement transient state before launching the
selected PC/SP. A flush kills younger speculative state and must prevent new
fetch/load requests from being accepted for the flushed epoch. A committed
finisher is older than any later trap and halts the benchmark once observed.

## Memory Semantics

The external memory model is byte-addressed and little-endian. Fetch reads
assemble `fetchRespWindow` from consecutive bytes beginning at `fetchReqPc`.
Load reads assemble `loadRespData` from consecutive bytes beginning at
`loadLookupAddr`, applying `loadLookupSize` and
`loadLookupReturnSignExtend` according to the live LSU extraction contract.
Committed stores merge `storeObserveData` into memory using
`storeObserveMask` or the derived mask from `storeObserveAddr` and
`storeObserveSize`.

The memory model must be deterministic for a fixed ELF, reset PC/SP, and
backpressure schedule. It may model memory-mapped UART and finisher addresses,
but those devices are driven only by committed stores. It must not inspect the
future commit stream to answer an earlier fetch or load.

## Commit and Provenance

The autonomous top must expose the existing `commit` port from the live top:
`commit.rows[*].valid`, `seq`, `cycle`, `slot`, `identity.bid/gid/rid`, `rob`,
`blockBidValid`, `blockBid`, `pc`, `insn`, `len`, `wb`, `src0`, `src1`, `dst`,
`mem`, `trap`, and `nextPc`.

Commit rows are observational evidence. They may drive committed-store
observation and post-run checking. They must not drive fetch responses, load
responses, register writeback data, branch outcomes, finisher pass/fail, or any
other DUT-internal architectural decision.

Each row's provenance domains remain distinct:

- `identity.bid/gid/rid` are model trace identity fields.
- `rob.valid/wrap/value` is native ROB identity.
- `blockBidValid/blockBid` is hardware block identity.
- `seq/cycle/slot` are trace-order and timing fields.

No checker may collapse these fields into one identity or use one field as an
oracle for another.

## Anti-Oracle Rules

The generated-RTL autonomous benchmark DUT must not contain or receive:

- QEMU commit rows, QEMU memory results, QEMU next-PC values, or QEMU register
  values;
- expected commit rows, expected load values, expected store values, expected
  UART text, or expected finisher status;
- benchmark-specific PC, instruction, address, or data tables;
- row-number conditions, skip counts, replay fixtures, or first-red-specific
  constants;
- harness writes to architectural registers after start except through the
  declared `resetSp`/`restartSp` boot contract;
- memory responses derived from future DUT commits or checker expectations.

Allowed inputs are ELF-backed bytes, reset/restart controls, PE/STID tags,
ready/valid backpressure, and external interrupt/trap inputs only after those
interrupt/trap ports are explicitly specified.

## Promotion Gates

Focused gates prove interface rules in module-level or reference tests:

- fetch request payload stability under `fetchReqReady == false`;
- fetch response payload stability under `fetchRespReady == false`;
- Phase 1 load lookup uses only current live-top ports and makes no load
  backpressure claim;
- Phase 2 load request payload stability under `loadLookupReady == false`,
  after live-top ready plumbing exists;
- Phase 2 load response payload stability under `loadRespReady == false`,
  after live-top response storage exists;
- execute-vs-replay wrapper metadata follows the selected grant for every
  `loadLookup*` field;
- committed store observation is produced only from commit rows;
- UART and finisher pulses are produced only from committed stores;
- reset, restart, flush, finisher, and trap priority match this contract;
- generated RTL text contains no `qemu`, `expected`, `replay`, row-skip, or
  benchmark-specific oracle inputs.

Generated-RTL gates prove the emitted autonomous top and harness:

- elaborate the autonomous top with the full `BenchmarkAutonomousMemoryPort`;
- run against an ELF-backed memory model, not trace replay data;
- preserve fetch ready/valid assertions in Phase 1 generated RTL or Verilator
  monitors;
- preserve fetch and load ready/valid assertions in Phase 2 generated RTL or
  Verilator monitors after `LoadLookupArbiter` and the live top implement load
  ready/response storage/backpressure;
- compare only emitted commit rows and terminal side effects after execution;
- expose enough provenance to diagnose first-red differences without changing
  DUT behavior.

Natural gates promote the benchmark flow beyond synthetic fixtures:

- load an unmodified benchmark ELF and initialize only memory, `resetPc`, and
  `resetSp`;
- run to finisher pass/fail, trap, unsupported instruction, or timeout;
- collect UART transcript, finisher code, final status, and commit trace;
- compare the complete emitted trace to the external reference after the run;
- record the first mismatch as evidence without feeding it back into the DUT.

## Current Architecture Points

- The existing fetch-only `LinxCoreBenchmarkAutonomousTop` already names
  `fetchReq*`, `fetchResp*`, `storeObserve*`, `uartWrite*`, `finisher*`,
  `flushValid`, and status ports, but it currently treats `storeObserve*` as
  harness inputs. The full autonomous contract requires committed store
  observation to become DUT output sourced from live commit rows.
- The live top exposes `loadLookupValid`, `loadLookupAddr`, `loadLookupPc`,
  execute-only destination metadata, grant bits, and a single-cycle
  `loadLookupData` input. The current `LoadLookupArbiter` has no ready input,
  no response-valid input, no response-ready output, and no response storage.
  Phase 2 promotion requires adding those mechanisms to `LoadLookupArbiter` and
  the live top before decoupled load backpressure evidence can count.
- If `commitWidth > 1`, committed store observation needs either vectorized
  lanes or an explicit serializer. The first autonomous implementation may
  require `commitWidth == 1` until lane serialization is implemented.
