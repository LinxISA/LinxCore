# LinxCore Simulator Heartbeat and Parallel Build Design

## 1. Objective

Make Chisel verification observable, bounded, and faster without weakening the
microarchitecture being proved. The test runner reports continuous build
progress, simulation harnesses detect architectural deadlock from explicit
progress events, and behavior tests use smaller legal capacities while keeping
the full W2/W4/W6/W8 lane widths and physical execution topology.

This work changes verification infrastructure only. It adds no hardware state,
TOP port, queue owner, recovery policy, or compatibility path.

## 2. Baseline and Root Cause

`tools/chisel/run_chisel_tests.sh` currently invokes one foreground SBT command.
It emits no status between SBT log records and cannot distinguish elaboration,
FIRRTL lowering, Verilation, C++ compilation, or simulation.

The Task-13 W8 continuous D3 lane test exposed the cost of using full-capacity
parameters for a narrow behavior proof:

- generated SystemVerilog: 11,365,751 lines;
- generated `PRename.sv`: 8,183,231 lines and about 781 MiB;
- total generated simulator input: about 1 GiB;
- Verilator resident footprint: about 11.9 GiB, with a 22.6 GiB peak;
- active phase after several hours: `V3Split::splitReorderAll` at approximately
  one fully occupied CPU core.

The invocation already contains `-j 0`. Verilator therefore uses all available
parallelism for Verilation and C++ build phases that support it. More shell
parallelism cannot accelerate a single oversized sequential pass. The primary
repair is to reduce irrelevant capacities for behavior simulation, then use
bounded suite-level concurrency so independent models build in parallel
without exhausting memory.

## 3. Selected Design

### 3.1 Separate build liveness from architectural progress

The runner owns process supervision. The simulation test or natural workload
harness owns architectural progress detection. Neither layer infers the other.

The runner classifies the active phase from its descendant process tree:

1. SBT startup and Scala compilation;
2. Chisel elaboration;
3. FIRRTL lowering;
4. Verilation;
5. C++ compilation and link;
6. simulator execution;
7. result collection.

Every heartbeat reports:

- suite and test selector;
- phase and total elapsed time;
- time since the last output byte;
- time since the last generated-file size or modification change;
- descendant CPU usage, resident memory, and active process count;
- generated artifact bytes;
- the most active descendant command.

The default heartbeat interval is 30 seconds. `LINX_CHISEL_HEARTBEAT_SECONDS`
and a matching command-line option may override it with a positive integer.

### 3.2 Build stall policy

A silent build is not a stalled build when its process tree is consuming CPU or
its generated files are changing. The runner declares a build stall only when
all of the following remain true for the configured stall window:

- no new output bytes;
- no generated-file size or modification progress;
- aggregate descendant CPU remains below the low-activity threshold;
- the supervised command is still alive.

The default stall window is 600 seconds. A stall exits with status 124 after
printing the latest heartbeat, process tree, artifact summary, and log tail.
`LINX_CHISEL_STALL_SECONDS=0` disables automatic build-stall termination but
does not disable heartbeats. An optional wall-time limit remains separate and
is disabled by default because a high-CPU Verilation pass is not deadlock.

The runner forwards `INT` and `TERM` to the complete process group, waits for a
bounded graceful exit, and then reports every process that survived. It never
deletes build artifacts while a descendant remains alive.

### 3.3 Simulation deadlock policy

Architectural deadlock is measured in simulated cycles, not wall-clock time or
log silence. A shared test helper tracks the last cycle with one or more typed
progress events:

- accepted input or dispatch lane;
- architectural commit;
- execution completion or ROB resolve;
- memory request, response, replay, repick, or store visibility transition;
- recovery prepare, prepared, apply, or abort handshake;
- trap, interrupt, or command transaction;
- natural workload termination.

Each long-running simulation declares the progress events relevant to that
harness and a maximum number of cycles without them. When the limit expires,
the helper fails with the cycle, last progress event, retained identities,
queue or stage occupancy exposed by the harness, and the most recent commit or
memory transaction. Short directed tests may retain their existing bounded
loops; they do not acquire an unrelated global timeout.

The checker is test-only code. Hardware must not gain a watchdog register,
debug-only ready signal, or progress port to satisfy the test infrastructure.
DTU remains the debug and trace unit; commit and recovery ownership remain in
OOO and TOP distribution remains policy-free.

### 3.4 Bounded parallelism

One runner invocation uses one SBT server and may accept multiple suite
selectors. SBT schedules independent suites concurrently. The runner and SBT
share one `LINX_CHISEL_TEST_JOBS` limit so the number of concurrent simulator
builds is explicit and reproducible.

The default is two concurrent simulator suites. The user may raise it after
considering available memory. The runner records the selected job count in
every heartbeat and final summary. It does not launch independent SBT clients
against the same workspace.

Within one Verilator model, Verilation and C++ build parallelism remain
enabled. The implementation uses separate build and Verilation job settings
when supported, compilation-speed optimization for behavior tests, output
splitting, and cache-compatible generated outputs. Parallel C++ compilation
does not substitute for lowering the RTL size.

### 3.5 Simulation parameter profiles

Behavior simulation uses test-only `SimulationParamProfiles` under
`chisel/src/test/scala/linxcore/params`. Main-source `ParamProfiles.W2`, `W4`,
`W6`, and `W8` remain unchanged and remain mandatory for elaboration, interface
manifest, generated RTL, lint, activation, natural workload, and final closure
evidence.

Simulation profiles preserve the lane topology and every explicitly configured
fixed-width transaction domain:

- fetch, CTU, decode, rename, D3, dispatch, issue, and retire widths;
- two ALU, one BRU, two AGU, two STD, one system/multicycle queue, and one CMD
  queue;
- two load pipes and two store pipes;
- three PC Buffer write ports and six read ports;
- source and destination counts;
- PC, instruction, PE, instruction, transaction, generation, full LSID,
  memory-attempt, epoch, and recovery widths that are independent of a local
  storage capacity.

The current RTL derives RID slot, BID, PC Buffer index, P/T/U tag and MapQ
index widths from their corresponding capacities. Those local widths therefore
narrow in a capacity-reduced simulation profile. Such a profile proves
behavior and identity continuity within that legal configuration; it cannot
prove full-profile interface widths or full identity-space wrap. Interface
manifests, identity-width conformance, generated RTL, activation and closure
always use the unchanged main profiles. A future physical-capacity/identity-
capacity split must be implemented as an architectural parameter change, not
hidden inside this test-only profile.

They reduce only capacities that are irrelevant to a directed behavior proof.
Each width uses its own next-power-of-two prefix and rename-demand capacity;
W2 and W4 are not padded to W8 storage geometry. The scalar IQ retains a
four-entry minimum so its two banks each have at least two addressable rows;
this avoids a zero-bit reservation slot while remaining far below the main
64-entry capacity.
The W8 continuous D3 lane test uses this initial profile:

| Capacity | Main profile | Simulation profile | Preserved behavior |
| --- | ---: | ---: | --- |
| ROB groups per STID | 64 | 16 | one complete W8 D3 lane set and its nonwrapped tail |
| instructions per ROB group | 4 | 1 | eight independent RID groups |
| maximum recipe uops | 32 | 12 | current ordinary-group/CTU recipe invariant |
| ROB banks | 8 | 8 | eight-lane allocation geometry |
| BROB entries per STID | 256 | 8 | one W8 branch-order window |
| PC Buffer entries | 64 | 8 | eight bases and 3W/6R behavior |
| PC Buffer banks | 8 for W8 | 8 | W8 retire and PC bank geometry |
| integer physical registers | 128 | 64 | 24 committed mappings plus 16 W8 destinations |
| integer MapQ entries per STID | 256 | 16 | one maximum rename destination set |
| T physical registers | 32 | 16 | one maximum rename destination set |
| U physical registers | 32 | 16 | one maximum rename destination set |
| T/U MapQ entries per STID | 32 | 16 | one maximum rename destination set |
| scalar Issue Queue entries | 64 | 8 | one complete W8 admission prefix |
| IFU and CTU retained entries | 32 | at least the selected width | one full transfer |
| maximum CTU template uops | 32 | 2 | scalar/store-split directed behavior |
| load/store queues | 16/16 | 2/2 | two load and two store pipes |
| load-return/store-commit queues | 16/16 | 2/2 | one event per physical pipe |
| SCB entries | 16 | 4 | one two-pipe split-store batch |

No test may use the simulation profile to claim full-capacity wrap, occupancy,
fairness, recovery-depth, or stress behavior. Those tests select the smallest
capacity that still covers the claimed boundary, or use the unchanged main
profile. The test name and evidence record state which profile was used.

### 3.6 Generated artifact accounting

The runner reports generated bytes and the largest generated files at exit.
Representative behavior suites have a checked artifact-size budget so a
capacity or elaboration regression cannot silently recreate a gigabyte-scale
model. Exceeding the budget is a test-infrastructure failure with an actionable
message naming the suite, profile, total bytes, largest file, and configured
budget.

Full TOP and closure builds use separately declared budgets. A behavior-test
budget must never be reused to reject a required full-configuration build.

## 4. Interfaces and Files

The implementation plan will cover these surfaces:

- modify `tools/chisel/run_chisel_tests.sh` for the public command-line
  contract and one-SBT invocation;
- add one process-supervision helper under `tools/chisel` with unit tests that
  use synthetic commands rather than Chisel builds;
- add SBT job-limit configuration derived from `LINX_CHISEL_TEST_JOBS`;
- add test-only `SimulationParamProfiles` and parameter-invariant tests;
- migrate `OOOD3ContinuousPrefixSpec` to the W8 simulation profile;
- add a shared simulation progress checker and focused unit tests;
- integrate progress events into long-running graph and natural workload
  harnesses as those harnesses become active in Tasks 13 and 18-19;
- document runner options and artifact budgets in the Chisel development flow.

The process supervisor and progress checker own no hardware protocol and are
not architectural state owners or compatibility adapters.

## 5. Error Handling

Every failure answers what stopped, why it stopped, and how to reproduce it:

- invalid heartbeat, stall, job, or budget values fail before SBT starts;
- build stall prints low-activity evidence and exits 124;
- wall-time expiration states that it is a wall limit, not a deadlock claim;
- architectural deadlock prints cycle-based progress evidence and fails the
  exact simulation test;
- artifact-budget failure identifies the profile and largest generated file;
- child process failure preserves its exit status and log tail;
- interruption terminates the process group before cleanup is permitted.

## 6. Verification

Implementation follows test-first order:

1. supervisor unit tests with silent-active, silent-idle, output-progress,
   artifact-progress, child-failure, and signal-forwarding commands;
2. runner argument and environment validation tests;
3. progress-checker unit tests for forward progress, exact threshold failure,
   and diagnostic payload;
4. `SimulationParamProfiles` tests proving W2/W4/W6/W8 widths, W4 topology,
   fixed transaction widths, PC Buffer 3W/6R, minimum legal capacities, and
   explicit rejection of simulation profiles as interface-manifest evidence;
5. the W8 D3 lane test proving the same three-three-two accepted sequence with
   bounded artifact size;
6. focused Task-13 tests, interface manifest, W2/W4/W6/W8 full-profile
   elaboration, generated RTL, and Verilator lint;
7. natural workload progress and deadlock tests when the active TOP harness is
   introduced.

The before/after report records wall time, peak resident memory, generated
bytes, generated line count, and result parity for the W8 case.

## 7. Success Criteria

The design is complete when:

- a silent active build emits periodic heartbeats and is never classified as
  deadlocked;
- a silent idle build fails after the configured stall window with complete
  diagnostics;
- a simulation with no declared architectural progress fails at its cycle
  threshold;
- independent suites build concurrently within the configured memory-safe job
  limit;
- the W8 D3 behavior test preserves eight lanes, 3W PC Buffer acceptance, the
  three-three-two sequence, and complete identity continuity;
- W8 behavior-test generated bytes and peak memory fall substantially from the
  recorded baseline;
- unchanged main W2/W4/W6/W8 profiles still pass elaboration, lint, activation,
  and closure gates;
- no hardware owner, interface, or architectural behavior changes solely for
  verification infrastructure.
