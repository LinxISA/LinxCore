# LinxCore architecture naming conventions

This document defines naming conventions for stage/module interfaces and parameters.
It exists to keep multi-agent development consistent.

## 1) Stage names (canonical)

### Frontend fetch pipeline

- `f0` — thread arbitration, redirect selection, and next-PC control ahead of
  the four fetch stages.
- `f1`, `f2`, `f3`, `f4` — the four canonical fetch stages.
- `f4` and `ib` name the same final fetch/instruction-buffer boundary. Do not
  model `ib -> f4` as two serial architectural stages.
- `f0` is canonical frontend control, but is not counted as a fifth fetch-data
  stage.

### Decode / dispatch pipeline

- `d1` — early decode, exception detection, split/fuse recognition, and group
  formation.
- `d2` — operand extraction and resource/admission request preparation.
- `d3` — atomic admission, physical rename, ordering-ID acceptance, and
  dispatch-packet formation.
- `s1` — admitted-uop capture into the speculative issue-buffer boundary.
- `s2` — physical IQ row allocation and write.
- `s3` — newly written IQ row becomes resident and pick-visible.

The baseline width is four uops/cycle, but width never determines a stage
name.

### Issue, execute, result, and retire pipelines

- `p0`, `p1` — candidate preselect and final pick coordinates.
- `i1`, `i2` — RF read/arbitration and bypass/issue-confirm coordinates.
- `e1`, `e2`, `e3`, ... — absolute cycles after `i2` within an execution pipe.
- `w1`, `w2`, `w3`, ... — producer-relative actual data-bypass/result/writeback
  age. `w1` is the first age with real result data. Each pipe also declares any
  earlier speculative wakeup by its E stage separately.
- `r0`, `r1`, `r2`, `r3`, `r4` — completion intake through precise
  commit/flush/restart coordinates. `CMT` and the flush broadcast occur at
  `r2`; restart state becomes visible at `r4`.

`W` is not a serial pipeline appended after all `E` stages. Always qualify a
result stage by its pipe when the `E` alignment is not obvious, for example
`alu2_e2_w1` or `ld_e4_w1`.

### Command/block structured pipeline

- `cmd_iq` — command instruction queue.
- `cmd_pipe` — command assembly pipeline (reads descriptor array, resolves command).
- `biq` — block issue queue (assembled commands wait here for issue).
- `brob` — block reorder buffer (BID-based lifetime tracking).

### Non-scalar engines (default promoted set; parameterized)

- `eng_vec`
- `eng_tma`
- `eng_cube`
- `eng_tau`

Note: the number of promoted non-scalar completion sources is
DSE-parameterized (`N_NONSCALAR_ENGINE`, default 4). Scalar boundary completion
is a separate source.

## 2) Interface signal naming

### 2.1 General rule

All cross-module/stage ports MUST be named:

`<producer>_<consumer>_<signal>`

Examples:

- `d3_brob_alloc_valid`
- `d3_brob_alloc_ready`
- `cmd_pipe_biq_cmd_valid`
- `biq_brob_issue_valid`
- `eng_vec_brob_complete_valid`

### 2.2 Valid/ready direction

- Producer drives `*_valid`.
- Consumer drives `*_ready`.

### 2.3 Stage-local internal signals

Stage-local wires/regs (not ports) should use:

- `s_<name>` for stage state
- `w_<name>` for combinational wires

Example: `s_head`, `s_tail`, `w_flush_mask`.

## 3) Parameter naming (DSE)

- Use ALL_CAPS for DSE sweep parameters.
- Prefer semantic names over widths.

Examples:

- `N_STID` (configured hardware-thread contexts, at least 1). Default: 1;
  multi-STID is a supported target configuration.
- `ROB_ENTRIES` (power of 2, at least 2, per instruction-ROB partition)
- `IQ_ENTRIES` (power of 2, at least 2, per resident issue-queue bank)
- `MAPQ_ENTRIES` (power of 2, at least 2, per scalar-P rename owner). The
  default bring-up value is 32; configurations must reserve enough rows for
  the maximum in-flight scalar-P destinations admitted by their ROB policy.
- `BROB_ENTRIES` (power of 2, at least 2, per STID). Default: 256
- `BROB_ALLOC_PER_CYCLE` default: 1
- `BROB_COMPLETE_PER_CYCLE` default: 1
- `BROB_RETIRE_PER_CYCLE` default: 1
- `N_NONSCALAR_ENGINE` default: 4 (`vec`, `tma`, `cube`, `tau`)

Derived widths:

- `STID_W = max(1, ceil(log2(N_STID)))`
- `RID_W = ceil(log2(ROB_ENTRIES))`
- `BID_W = ceil(log2(BROB_ENTRIES))` — the complete BID carried between
  LinxCore modules alongside a separate STID.
- `BROB_PTR_W = BID_W + 1` per STID when a conventional wrap bit is used internally.
  The wrap bit, allocation generation, and age metadata are not BID bits.

For each default 256-entry STID partition, `BID_W = 8`. The cross-thread block
identity is `(stid, bid)`; STID is not packed into BID. BID reuse within one
STID is legal only after the old block and every command, response, queue row,
and cleanup side effect that names its slot have drained.

## 4) Record / type naming

- Packed structs: `*_t` suffix.
- Enums: `*_e` suffix.

Examples:

- `bid_t`
- `brob_state_e`
- `uop_t`
- `typed_tag_t`

## 5) Typed tag namespaces (rename)

Typed tags are encoded as `{type, idx}` (fixed width; idx uses max namespace width).
Namespaces currently used:

- `P` — global scalar register tags (r0–r23 namespace)
- `T` — scalar block-local T-link/ClockHands tags
- `U` — scalar block-local U-link/ClockHands tags
- `BARG`/`CARG` — block argument/condition state keyed by `(STID,BID)`

Tile-register tags use an explicit tile namespace such as `TILE`; they must not
reuse scalar `T` naming.

Each namespace may have a different capacity; encoding uses a shared `idx` width.
