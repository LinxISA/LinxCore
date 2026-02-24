# LinxCore architecture naming conventions

This document defines naming conventions for stage/module interfaces and parameters.
It exists to keep multi-agent development consistent.

## 1) Stage names (canonical)

### Frontend fetch pipeline

- `f0`, `f1`, `f2`, `f3`, `f4` — fetch stages.

### Decode / dispatch pipeline

- `d1` — uop generation stage (split/fuse), 4 uops/cycle.
- `d2` — rename stage (typed tags), 4 uops/cycle.
- `d3` — timing / register stage, 4 uops/cycle.

### Command/block structured pipeline

- `cmd_iq` — command instruction queue.
- `cmd_pipe` — command assembly pipeline (reads descriptor array, resolves command).
- `biq` — block issue queue (assembled commands wait here for issue).
- `brob` — block reorder buffer (BID-based lifetime tracking).

### Engines (default set; parameterized)

- `eng_scalar`
- `eng_vec`
- `eng_tma`
- `eng_cube`
- `eng_tau`

Note: the number of engines is DSE-parameterized (`N_ENGINE`).

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

- `BROB_ENTRIES` (power of 2). Default: 128
- `BROB_ALLOC_PER_CYCLE` default: 1
- `BROB_COMPLETE_PER_CYCLE` default: 1
- `BROB_RETIRE_PER_CYCLE` default: 1
- `N_ENGINE` default: 5

Derived widths:

- `BID_W = log2(BROB_ENTRIES)`

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

- `R` — general register tags (r0–r23 namespace)
- `T` — tile tags
- `U` — uqueue tags
- `BARG` — block-arg tags

Each namespace may have a different capacity; encoding uses a shared `idx` width.
