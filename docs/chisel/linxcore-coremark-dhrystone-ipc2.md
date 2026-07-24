# LinxCore CoreMark/Dhrystone IPC2 Plan

Date: 2026-07-24

## Scope

This spec defines the bring-up and performance plan for the Chisel
`LinxCoreBenchmarkAutonomousTop` path running the frozen FishToucher CoreMark
and Dhrystone ELFs in natural mode. The target is end-to-end finisher pass for
both workloads with IPC close to 2.0. The performance gate currently uses
`target_ipc = 1.90` as the acceptance threshold.

This document is not a claim that the target has been reached. The current
state still times out on both workloads. `OP_HL_SDI_PO` has now been verified
only on the autonomous direct-execute/commit-row natural benchmark path. The
reduced STA bridge and general LSU path are still open backlog, so this
document MUST NOT be read as a hardware-wide `OP_HL_SDI_PO` closure claim. The
current next red is `ACRC`, observed as opcode `0x222`, and remains under
semantic and owner audit.

## Frozen Artifacts

The performance loop MUST run against the exact workload, runner, and repo
state recorded here until this spec is explicitly revised.

| Component | Frozen value |
| --- | --- |
| Superproject | `/Users/zhoubot/linx-isa` at `3b50632a780f2a15e011442d73e66a557337801d` |
| LinxCore Chisel | `/Users/zhoubot/linx-isa/rtl/LinxCore` at `f9ca183e09e50003578531cf69d7073ac13535ad` |
| LinxCoreModel | `/Users/zhoubot/linx-isa/tools/LinxCoreModel` at `2a1cf81e47060141e5305be5e49079a8fadc8e42` |
| QEMU | `/Users/zhoubot/linx-isa/emulator/qemu` at `a7c5cf0dc1b31a0f538473fd0c2413baa81edf9f` |
| LLVM | `/Users/zhoubot/linx-isa/compiler/llvm` at `53388eed435037fbfc323e171969d8d74d1db827` |
| FishToucher | `/Users/zhoubot/FishToucher` at `2fc309e58f667f70b9611c052a2e83b663457508` |
| CoreMark ELF | `/Users/zhoubot/linx-isa/workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/coremark/coremark.elf` |
| CoreMark ELF SHA-256 | `9c734694793da5d3b3765bc45c7acff787a3ca1854ad1780897e1d5b8deb3cff` |
| Dhrystone ELF | `/Users/zhoubot/linx-isa/workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/dhrystone/dhrystone.elf` |
| Dhrystone ELF SHA-256 | `617bd0985595ccf208dd2130809c1befc1605de1ee9188dbf3cfaf46fd9e9911` |
| Natural runner | `tools/chisel/run_chisel_benchmark_autonomous_top_natural.sh` |
| Natural runner SHA-256 | `91d84b3b300209bf5f384f185eb1ace4b5bfb81b3a8107fc8a6678e3a86bc1e3` |
| Natural testbench SHA-256 | `57d98600b209301811ca9150d2a7fcb8290d82e67bca02e9acad1505f4433aa9` |
| Reset PC | `0x10000` |
| Reset SP | `0x0000000007fefff0` |
| Finisher pass code | `0x5555` |

The natural runner MUST NOT use QEMU rows, replay rows, expected rows, or
checker-fed values to advance the DUT. QEMU and compiler artifacts are semantic
oracles for functional comparison only.

## Natural IPC Gate

The current promotion gate is:

```sh
bash tools/chisel/run_chisel_dual_benchmark_ipc_gate.sh \
  --coremark-elf /Users/zhoubot/linx-isa/workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/coremark/coremark.elf \
  --dhrystone-elf /Users/zhoubot/linx-isa/workloads/generated/fishtoucher-c-workloads-ca3e11b-20260717-r1/benchmarks/dhrystone/dhrystone.elf \
  --build-root generated/perf-ipc2-evaluator \
  --max-cycles 3000000 \
  --target-ipc 1.90
```

Acceptance MUST require all of the following:

1. Both ELF hashes match the frozen values.
2. The natural runner hash matches the frozen value.
3. Reset SP equals `0x0000000007fefff0`.
4. Both runs report `terminal_status = finisher_pass`.
5. Both runs report `finisher_pass = true`.
6. Both runs report positive cycle and commit counts.
7. Both runs reach IPC `>= 1.90`.
8. The immutable summary uses schema
   `linxcore.dual_natural_benchmark_ipc.v1`.

Timeout, trap, unsupported instruction, finisher fail, hash drift, missing
manifest fields, zero cycles, zero commits, or sub-target IPC MUST fail the
gate.

## Baseline

The first frozen 100K-cycle baseline is recorded in
`generated/perf-ipc2-baseline-r1/report/dual_benchmark_ipc.json`.

| Workload | Status | Cycles | Commits | IPC | Gate errors |
| --- | --- | ---: | ---: | ---: | --- |
| CoreMark | `timeout` | 100,000 | 1,089 | 0.01089 | no finisher pass; IPC below 1.90 |
| Dhrystone | `timeout` | 100,000 | 813 | 0.00813 | no finisher pass; IPC below 1.90 |

The baseline establishes a correctness blocker, not a tuned performance
number. Both workloads stopped in the same generated `memset` loop pattern:
stores followed by loop-control arithmetic and `FRET.STK`.

## P0 Evidence

### First red before P0

The first common red was an older scalar arithmetic uop accepted into execute
near a `FRET.STK` boundary, then not completing into ROB/wakeup state. The
symptom appeared in both workloads:

- CoreMark: accepted loop-bottom `ADDI` around PC `0xc6ec`; the next iteration
  store at PC `0xc6d4` waited on the missing physical tag.
- Dhrystone: same pattern around PC `0x91ec`; the next iteration store at
  PC `0x91d4` waited on the missing physical tag.

The microarchitecture rule is:

> Once a scalar uop has been accepted into execute, younger marker redirect or
> recovery logic SHALL NOT silently kill its completion. Selective recovery may
> cancel pre-execute attempts, but accepted E/W work MUST either complete,
> publish an explicit unsupported/trap result, or be covered by a precise
> same-identity recovery contract.

This rule matches the existing LinxCore bring-up contract that accepted I2/E1
work is non-cancellable by later redirect cleanup.

### Issue-ordering improvement window

After the issue-ordering P0 experiment, the 10K-cycle natural windows
advanced:

| Workload | Baseline 10K commits | P0 10K commits | Delta |
| --- | ---: | ---: | ---: |
| CoreMark | 1,089 | 1,234 | +145 commits (+13.3%) |
| Dhrystone | 813 | 958 | +145 commits (+17.8%) |

Evidence:

- `generated/perf-ipc2-baseline-r1/coremark-full10k/report/natural_manifest.json`
- `generated/perf-ipc2-baseline-r1/dhrystone-full10k/report/natural_manifest.json`
- `generated/perf-ipc2-redirect-age-r1/coremark/report/natural_manifest.json`
- `generated/perf-ipc2-redirect-age-r1/dhrystone/report/natural_manifest.json`

This is useful progress only. It is not end-to-end closure, and it is not an
IPC2 result.

### HL_SDI_PO autonomous window

`OP_HL_SDI_PO` is no longer the current unsupported instruction. The current
loop records it as implemented for the autonomous direct-execute/commit-row
benchmark path. Focused ALU verification passes 6/6 and the Chisel build
passes.

This is a deliberately narrow claim. `ReducedScalarAluExecute` contains
`OP_HL_SDI_PO` handling for the natural path, but
`ReducedStoreStaAddressExecBridge` still only supports `OP_HL_SDI_PR`. The
reduced STA bridge, normal store-address execution bridge, and general LSU
promotion path for `OP_HL_SDI_PO` remain unclosed. They are backlog items, not
evidence included in this natural-window promotion.

The first 30K-cycle natural window after the `OP_HL_SDI_PO` implementation is
recorded in `generated/hl-sdi-po-natural-small-r1/report/dual_benchmark_ipc.json`.

| Workload | Status | Cycles | Commits | IPC | Gate errors |
| --- | --- | ---: | ---: | ---: | --- |
| CoreMark | `timeout` | 30,000 | 1,259 | 0.0419666667 | no finisher pass; IPC below 1.90 |
| Dhrystone | `timeout` | 30,000 | 983 | 0.0327666667 | no finisher pass; IPC below 1.90 |

This remains useful progress only. Both workloads still timeout and both IPC
values are far below the IPC2 target.

### Current next red

The current next red is `ACRC`, observed as Chisel opcode `0x222`. It is not
yet assigned to a final hardware owner. The next loop MUST audit its ISA
semantics, compiler/QEMU behavior, model owner, and Chisel ownership boundary
before implementing or waiving it. Until that audit completes, this document
MUST NOT claim that `ACRC` is a scalar ALU instruction, a pure frontend issue,
or a system/CSR instruction.

## Cross-Stack Alignment

ISA, compiler, and QEMU define architectural semantics. They do not constrain
the number of decode, issue, execute, or commit lanes used by the hardware, as
long as the committed architectural stream and side effects match the ISA.

The current Chisel natural path is functionally aligned with this boundary:

- LLVM emits the frozen CoreMark and Dhrystone ELFs.
- QEMU is the functional semantic oracle for architectural rows and terminal
  behavior.
- The natural Chisel runner fetches and executes from ELF-backed memory without
  QEMU row injection.
- Chisel commit rows, UART writes, finisher writes, loads, and stores are DUT
  observations.

The IPC2 target is therefore a microarchitecture target. It is allowed to widen
frontend, rename, issue, execute, and retire as long as precise Linx row order,
store side effects, block-marker semantics, recovery, and trace observability
remain valid.

## Width Alignment

LinxCoreModel already describes a 4-wide scalar performance direction:

| Model config | Value |
| --- | --- |
| `configs/spe.toml:decodeWidth` | 4 |
| `configs/spe.toml:renameWidth` | 4 |
| `configs/spe.toml:speROBDepth` | 128 |
| `configs/spe.toml:retiredWidth` | 4 |
| `configs/iex.toml:iex_retire_width` | 4 |
| `configs/bctrl.toml:bctrl_bandwidth` | 4 |

Chisel defaults also expose 4-wide intent in `CoreParams` and
`InterfaceParams`, but the natural autonomous top currently forces
`commitWidth = 1` and requires it:

```scala
CoreParams(robEntries = LinxCoreBenchmarkAutonomousTop.BenchmarkRobEntries, commitWidth = 1)
require(coreParams.commitWidth == 1,
  "Phase 1 autonomous benchmark top serializes committed stores through commitWidth == 1")
```

The live path also has one scalar issue output and one
`ReducedScalarAluExecute` instance. Therefore the current autonomous path has
a hard IPC ceiling at or below one committed instruction per cycle, before
ordinary stalls. IPC near 2.0 is infeasible until the width bottlenecks are
removed.

## Microarchitecture Plan

### P0: Correctness to next first-red

The implementation MUST close first-red correctness before any performance
claim. The accepted-execute uop invariant above is part of P0. `OP_HL_SDI_PO`
has been closed only for the current autonomous direct-execute/commit-row
workload path; the reduced STA bridge and general LSU path remain backlog. The
current P0 successor is the `ACRC` semantic and owner audit.

Acceptance:

- `ACRC` has an explicit ISA, QEMU, compiler, LinxCoreModel, and Chisel owner
  classification.
- If `ACRC` is executable in the reduced path, it produces semantics matching
  ISA/QEMU. If it belongs to a later system/control owner, the natural workload
  path has an explicit waiver or implementation plan.
- Unsupported status is not used for any compiler-generated scalar forms that
  are required by the frozen workloads and belong to the current reduced-path
  scope.
- CoreMark and Dhrystone both advance past the current 30K first-red window.
- Focused generated probes cover the chosen `ACRC` owner semantics, source and
  destination side effects, unsupported suppression, and no fabricated commit
  or memory side effects.

### P1: Stall and IPC Counter Taxonomy

The benchmark top SHALL publish stable counters for frontend, decode/rename,
issue, execute, ROB commit, store side effect, marker recovery, and unsupported
blocks. The dual benchmark summary SHOULD include enough taxonomy to identify
the next bottleneck without manually mining raw event JSON.

Acceptance:

- The 100K natural dual gate emits a stall-counter block per workload.
- Counters are monotonic and sum to explain idle/no-commit cycles at the top
  level.
- Counter schema changes are versioned.

### P2: 4-Wide Retire With Ordered Store Effects

The core SHALL retire up to four completed ROB rows per cycle when the rows are
oldest-contiguous and side-effect ordering permits it. Store side effects MUST
remain committed and ordered.

The preferred hardware shape is not to keep `commitWidth == 1` globally.
Instead, commit lanes SHALL retire rows wide, while store effects enqueue into
an ordered committed-store side-effect queue. The existing scalar LSU already
has independent store-commit capacities such as `commitIssueWidth = 2`; the
autonomous top needs a vectorized or serialized side-effect observation
adapter that does not serialize all non-store retire.

Acceptance:

- Existing commit/dealloc ordering tests pass at `commitWidth = 4`.
- The natural memory port observes committed stores in architectural order.
- Multi-lane commit rows preserve BID/GID/RID/ROB provenance.
- CoreMark and Dhrystone natural windows show commit cycles above one row per
  cycle in non-store bursts.

### P3: Multi-Uop Decode, Rename, and Dispatch

The frontend already carries 4-slot F4/decode structures. Decode/rename still
collapses to one selected scalar row in the live path. P3 SHALL preserve all
valid decoded slots through queueing, rename admission, ROB allocation, and
issue enqueue where resource credits permit.

Acceptance:

- A 4-slot packet with independent scalar rows can rename and allocate multiple
  rows without dropping identities or sidecars.
- Resource backpressure stalls atomically; no partial rename state leaks on
  insufficient GPR, MapQ, ROB, STQ, or T/U resources.
- Block marker boundaries and stop rows remain precise.

### P4: Dual Issue and Execute

The scalar backend has two issue banks, but the current exposed path issues one
scalar uop into one execute pipe. P4 SHALL allow at least two independent scalar
uops per cycle to read operands, issue, execute, and write back when there are
no ordering conflicts.

The current three-read-port scalar RF can feed one three-source operation. A
credible IPC2 design MUST either add read ports, bank reads with conflict
detection, or restrict pairing to source-port-compatible operations with
bypass. The pairing rule must be explicit and observable in counters.

Acceptance:

- Two independent ALU operations can issue and complete in one cycle window.
- Pairing refuses conflicting reads/writes without corrupting readiness.
- Wakeup and ROB completion accept at least two scalar completions per cycle.
- The gate records real two-lane issue/execute activity on natural workloads.

### P5: Selective Recovery and Branch/Marker Latency

Once P2-P4 expose width, branch and marker recovery latency may dominate. P5
SHALL keep the accepted-uop invariant while reducing recoverable frontend and
issue bubbles. It may include better block-marker prediction, faster restart,
and selective queue pruning.

Acceptance:

- Recovery tests prove older accepted execute work survives younger redirects.
- Recovery drops only rows covered by exact BID/RID/full-LSID authority.
- Natural workload counters show reduced frontend restart bubbles without
  increasing mismatch, trap, or unsupported rates.

## Instruction Coverage

Coverage MUST be reported with separate denominators. Vector and tile
instructions are excluded from this scalar bring-up metric unless a future
spec expands the scope.

| Metric | Count | Coverage | Meaning |
| --- | ---: | ---: | --- |
| Non-vector/tile operational ISA denominator | 547 | 100% denominator | Scalar/control/system/FP surface after excluding vector/tile |
| Frontend effective decode | 546 / 547 | 99.82% | All but `XB` have frontend decode coverage |
| Reduced scalar ALU/backend support | 189 / 547 | 34.55% | Instructions with implemented reduced scalar execution support |
| Cross-stack aligned reduced support | 188 / 547 | 34.37% | Same as above excluding current `CSEL` semantic divergence |

Open red items:

- `XB`: frontend decode hole.
- `CSEL`: QEMU/Sail/model operand-semantics divergence must be resolved before
  claiming aligned support.
- `REV`: QEMU whole-register reversal and Sail ring-segment reversal disagree;
  fix the cross-stack contract before RTL promotion.
- Atomics: `LR`, `SC`, `CAS`, and AMO forms require atomicity, ordering, and
  memory-system ownership.
- System, CSR, TLB, cache-maintenance, trap, and protection forms require
  privilege, exception, and maintenance owners.
- FP forms require rounding mode, NaN, denormal, exception flag, and pipeline
  ownership.
- `ACRC` opcode `0x222` is the current workload-blocking non-vector/tile audit
  item; its semantic owner is not yet settled.

## FishToucher Steward Contract

FishToucher is the loop steward. Promotion to the benchmark matrix SHALL use
four natural cells:

| Workload | Model/QEMU semantic oracle | Chisel natural DUT |
| --- | --- | --- |
| CoreMark | frozen ELF semantic pass | frozen ELF natural finisher pass and IPC |
| Dhrystone | frozen ELF semantic pass | frozen ELF natural finisher pass and IPC |

Diagnostics, bounded prefixes, generated probes, and trace mining may explain
or de-risk a change, but they MUST NOT occupy a matrix cell. A loop result is
promotable only after mailbox-style assignment, result, and verdict records
tie the exact artifacts to the four-cell outcome.

## Non-Goals

- This spec does not include vector or tile implementation.
- This spec does not claim CoreMark or Dhrystone currently pass end to end.
- This spec does not claim IPC2 until the dual natural gate passes at
  `target_ipc = 1.90`.
- This spec does not claim hardware-wide `OP_HL_SDI_PO` completion. The
  current evidence covers only the autonomous direct-execute/commit-row path;
  `ReducedStoreStaAddressExecBridge` and the general LSU path remain backlog.
- This spec does not replace QEMU/Sail/compiler semantic validation.
- This spec does not authorize QEMU-assisted natural execution.
- This spec does not require hand-building a new compiler workload unless the
  frozen ELF contract is explicitly updated.

## Skill Evolution Trigger

Update the LinxCore skill only when the loop learns a reusable rule that is not
already encoded there. The P0 accepted-execute invariant is reusable if the
existing skill does not already state it with the same strength:

> Accepted E/W scalar work MUST not be cancelled by a younger redirect unless
> a precise same-identity recovery contract publishes the terminal result.

Do not update the skill merely to record transient benchmark numbers.
