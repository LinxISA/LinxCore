# Issue Queues and Execution

This document defines LinxCore IQ residency, pick, issue, wakeup, bypass, replay, and register-file arbitration.

The issue-queue structure is intentionally ISA-neutral. LinxCore preserves valid/resident entries, source readiness, oldest-ready pick, inflight cancellation, RF read arbitration, bypass, and deallocation timing. LinxISA changes the uop classes, operand classes, and boundary-control rules carried by those entries.

## 1 IQ Entry State

Each IQ entry carries:

- valid bit;
- `rid`, `bid`, and optional `lsid`;
- execution class;
- source valid bits;
- source readiness bits;
- speculative readiness markers;
- physical source tags;
- physical destination tag;
- PC or PC-derived metadata when needed;
- immediate and control metadata;
- inflight state;
- replay/cancel state.

Invalid source operands are treated as ready.

The ISA-neutral entry layout should remain recognizable even as Linx-specific sidecars are added:

| Field group | Neutral purpose | Linx-specific payload |
| --- | --- | --- |
| Identity | Age, replay, and resolve correlation. | `rid`, `(stid, bid)`, separate BROB ring-age/epoch sidecars where needed, STID/PE, optional `lsid`. |
| Opcode/class | Queue routing and execute selection. | Linx uop kind: ALU, BRU, AGU, STA, STD, SYS/FSU, CMD. |
| Sources | Readiness and data source selection. | `P` ptag, `T/U` qtag/local tag, materialized CARG. |
| Destination | Wakeup and writeback. | `P` ptag or local `T/U` destination sidecar. |
| Control | Cancel/replay/flush behavior. | block-boundary correction, command tag, trap cause, memory sidecars. |

## 2 Pick and Inflight Rules

An entry is pick-eligible when:

- the entry is valid;
- every valid source is ready;
- the entry is not inflight;
- the required downstream class is available;
- the entry is not suppressed by load-miss dependency state or block/flush state.

Pick policy is oldest-ready-first. Pick does not remove the entry from the IQ. Pick marks the entry inflight.

If a downstream issue attempt fails, the entry is not reinserted. Instead, inflight is cleared and the valid entry becomes eligible for a future pick.

The real IQ deallocation point is `I2` after issue is confirmed non-cancellable.

## 3 Pipeline Timing

The canonical execution coordinates are:

```text
P1 -> I1 -> I2 -> E1 -> E2 -> E3 -> ...
                    \---- W1/W2/W3 result-age overlay
```

| Stage | Responsibility |
| --- | --- |
| P1 | Select ready entries and mark them inflight. |
| I1 | Determine RF read needs and arbitrate read ports. |
| I2 | Confirm issue and deallocate IQ entry. |
| E1 | First absolute execute cycle after I2. |
| E2/E3/... | Later absolute execute cycles; the label does not move when one pipe produces a result early. |
| W1 | First producer-relative age with actual result data available to bypass/result logic. |
| W2 | Later result age; baseline scalar RF-write coordinate. |
| W3 | Later result age; baseline RF-visible/retained-bypass coordinate. |

Wakeup at cycle `N` becomes visible to pick at cycle `N+1`. Wakeup must not affect pick in the same cycle.

Baseline result alignment:

| Producer | Actual result | Next result age | RF-visible age |
| --- | --- | --- | --- |
| 1-cycle scalar ALU | `E1/W1` | `E2/W2` | `E3/W3` |
| 2-cycle scalar ALU | `E2/W1` | `E3/W2` | `E4/W3` |
| 3-cycle scalar/MAC | `E3/W1` | `E4/W2` | `E5/W3` |
| Baseline load hit | `E4/W1` | `E5/W2` | `E6/W3` |

Speculative wakeup, where used, is named by its cancellable E-stage event and
is distinct from actual W-stage data availability.

## 4 Ready Tables and Speculative Readiness

Global ready tables represent non-speculative readiness only:

- `ready_table_p[ptag]`;
- `ready_table_t[ttag]`;
- `ready_table_u[utag]`.

Speculative readiness lives in the IQ entry. The effective merge rule is:

```text
src_ready = src_ready_nonspec || src_ready_spec
```

This matters for load-to-use speculation. A dependent consumer may become pick-ready through speculative load wakeup but still must receive the actual data through bypass.

## 5 Wakeup Domains

| Destination class | Wakeup mechanism |
| --- | --- |
| `P` | Global broadcast by `ptag`. |
| `T` | Point-to-point wakeup using `qtag = (phys_issq_id, entry_id)`. |
| `U` | Point-to-point wakeup using `qtag = (phys_issq_id, entry_id)`. |

`phys_issq_id` is a physical IQ enum derived through generated spec templates. `entry_id` width is per-IQ and packed into a uniform maximum-width qtag wire.

## 6 Register-File Arbitration

Read ports may contend at `I1`.

Rules:

- default integer RF read ports: 3;
- arbitration is global across picked uops;
- arbitration policy is oldest-first by ROB age relative to ROB head;
- a uop that loses required read-port arbitration is cancelled for the cycle;
- cancellation clears inflight but does not deallocate the IQ entry;
- write ports must not contend;
- each picker/issue port corresponds to a pipeline with a dedicated RF write port;
- STD reads operands but does not write a destination.

## 7 Load Speculative Wakeup

Loads produce speculative wakeup at `LD_E1`. Load data returns at `LD_E4`.

Consumers that become ready only through load speculative wakeup:

- must not request RF read ports in `I1` for that source;
- must obtain the value through an `E4 -> consumer-I2` bypass;
- must be suppressed while the producer load has `miss_pending` at `E4`.

`ld_gen_vec` is a bitset representing load pipeline stages `E1` through `E4`. It propagates through dependency chains and advances by bit shift as the load moves through the pipeline.

The default miss visibility point is `E4`. A future top-level hook may extend this to `E5`, but that is not the default contract.

## 8 Replay and Cancellation

Ordinary cancellation does not create a new IQ entry. It clears inflight on the existing entry.

Replay sources include:

- RF read-port loss;
- LSU miss or ordering replay;
- branch/block recovery;
- interrupt or trap flush;
- engine response trap;
- command queue backpressure;
- load-store dependency correction if a future speculation mode enables it.

The current strict scalar memory profile avoids memory speculation by LSID-ordered issue, but model switches such as `load_store_replay` and `load_ooo_enable` identify areas where implementation options exist.

## 9 Execute Resolution

Execution results must resolve to ROB/BROB with enough metadata to preserve precision:

- `rid` for instruction ordering;
- `bid` for block ownership;
- destination class and physical tag;
- exception/trap flag and cause;
- branch/control correction metadata;
- memory address/size/order metadata where relevant;
- command tag and engine response metadata for CMD path.

Control execute units must not directly redirect architectural PC. They produce correction state consumed by boundary logic.

## 10 Open Questions

- Should the first RTL target implement load speculative wakeup, or should it begin with non-spec load wakeup and add the `LD_E1` path after baseline correctness?
- Should T/U point-to-point wakeup be implemented exactly as `qtag=(phys_issq_id, entry_id)` in RTL, or abstracted behind a generated wakeup network interface?
- Which issue-cancellation cases should be separately visible in trace for debug and conformance?
