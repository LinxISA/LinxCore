# Frontend and Decode

This document covers LinxCore instruction fetch, variable-length assembly, block-boundary recognition, template interaction, decode grouping, and ordering-ID allocation.

## 1 Frontend Responsibilities

The LinxCore frontend must deliver a program-order decode stream while preserving per-instruction metadata needed for block-structured execution. It is responsible for:

- selecting the next fetch PC under normal, predicted, redirect, trap, and template-injection flows;
- fetching variable-length LinxISA encodings from instruction memory;
- assembling 16-bit, 32-bit, 48-bit, and 64-bit instructions without illegal cross-slot concatenation;
- recognizing `BSTART`, `BSTOP`, and template boundary forms early enough to annotate decode entries;
- preserving per-instruction PC, length, raw bits, thread ID, and block metadata;
- enforcing legal ordering between ordinary IFU stream writes and CTU/template child stream injection.

## 2 F0: PC Select

`F0` is a dedicated PC-select stage. It receives candidate PCs from at least:

- normal sequential fetch;
- predicted branch or block-boundary prediction;
- block-boundary redirect;
- trap or interrupt vector entry;
- recovery from branch or target legality failure;
- template or CTU child-stream control.

The `F0 -> F1` boundary is registered. The architecture-facing interface is per-thread. Arbitration among threads is allowed, but the stage must not collapse per-thread architectural state into a single hidden PC machine.

## 3 F1 and F2: I-cache Access

`F1` owns instruction-cache lookup and miss backpressure. The current physical path is single-ported, so it arbitrates among threads and services at most one thread lookup per cycle.

`F2` receives the cache data plus PC and thread context. It performs ECC checking and stages the raw cache-read result. On ECC error, `F2` reports a fetch-side fault and blocks normal delivery to `F3`; it must not forward a normal bundle decorated with an error bit that later stages could mishandle.

`F2` does not own variable-length stitching. Cross-line assembly and carry state belong to `F3`.

## 4 F3: Stitch, Static Prediction, and Boundary Metadata

`F3` owns variable-length instruction assembly and per-thread carry buffering. It performs static prediction and recognizes block-boundary instructions.

The primary metadata produced by `F3` is semantic:

- `start_of_block`;
- `end_of_block`;
- boundary type and target if statically available;
- predicted-taken state;
- instruction length and raw instruction bits.

Downstream logic must not depend only on raw opcode residue when determining block state. A `BSTART` in the stream both terminates the previous block and begins the next block. A `BSTOP` terminates the active block. Template forms (`FENTRY`, `FEXIT`, `FRET.RA`, `FRET.STK`) are valid standalone block starts and valid architectural targets.

## 5 Template and CTU Interaction

Template instructions may be consumed by the code-template unit. If later instructions were fetched in the same bundle, those later instructions may remain in frontend queues, but they must not enter the instruction buffer ahead of the template-generated child stream.

While a CTU child stream is actively injecting instructions:

- IB write-port source selection gives priority to CTU;
- ordinary IFU writes wait until the active child stream completes;
- architectural ordering must reflect the template block and its generated body;
- committed block metadata must still show the template boundary.

Template blocks are standalone blocks, not payload instructions inside another block. They must not appear inside `BSTART..BSTOP` coupled blocks or inside decoupled block bodies.

## 6 Instruction Buffer

The instruction buffer is partitioned by thread. Each entry carries enough per-instruction metadata to allow decode to reconstruct a contiguous group:

- thread ID;
- `entry_base_pc`;
- per-instruction offset and length;
- raw instruction bits;
- semantic block metadata;
- predicted branch or boundary information;
- fault or kill state.

An instruction-buffer entry may straddle a block boundary. The metadata is per instruction, not one summary bit per entry.

Each per-thread bank provides up to `decodeWidth` lane reads per cycle. The current promoted contract uses a 4-lane decode window.

## 7 F4/IB and the D1 Ingress Group

`F4` is the fourth fetch stage and is the same state boundary as the
instruction buffer (`IB`). It owns completed variable-length instruction
records, lightweight predecode/prediction, block-boundary/template metadata,
and per-thread residency before D1. It does not perform full opcode/operand
decode and is not named after a decode width.

`D1` reads up to `decodeWidth` contiguous F4/IB entries in program order. The
baseline value is four, which is why legacy code calls its reader
`F4DecodeWindow`; that name denotes a D1-ingress helper, not an architectural
F4 decode stage.

Rules for the D1 ingress group:

- entries are consumed strictly in program order;
- an invalid, killed, or not-yet-complete entry stops the group and prevents
  later entries from compacting around it;
- D1 may form continuous byte views from adjacent F4/IB bytes only when those
  bytes belong to the same ordered instruction stream; it must never combine
  unrelated packet entries to manufacture a 48-bit or 64-bit instruction;
- the group preserves each selected instruction's PC, length, raw bits,
  prediction, boundary metadata, and checkpoint identity.

This split is required for LinxISA because compressed block forms, ordinary
32-bit forms, and 48/64-bit extended forms coexist while block recovery must
still identify one final fetch/IB ownership boundary.

## 8 D1 Decode and Group Formation

`D1` reads a program-order contiguous decode group from F4/IB. It performs
early decode and prepares an admission request; it does not allocate
architecturally visible identities itself.

`D1` prepares an all-or-nothing admission request for the decode group:

- if every instruction in the group can be admitted by D3, the group advances;
- if any instruction cannot be admitted, the full group stalls and retries;
- split or fused work must preserve older-before-younger ordering.

The output of `D1` carries decoded opcode/uop semantics, fixed operand slots, immediate values, block metadata, and preliminary execution class.

`D1` does not mutate globally visible ROB, BROB, rename, or LSID state. D2
creates the canonical uop and resource demand; D3 atomically receives the
ROB-owned RID, BROB-owned BID, and memory-order identity while performing
rename and dispatch.

`BSTART`/`BSTOP` and template forms must be recognized before the backend loses the original block relationship.

## 9 D2 Canonical Uop Shape

`D2` prepares rename requests, resolves boundary metadata, and creates ROB-visible boundary uops. The canonical uop shape is:

```text
{
  valid,
  thread_id,
  pc,
  opcode,
  uop_type,
  src[3],
  dst[1],
  imm,
  imm_type,
  imm_valid,
  rid,
  bid,
  lsid,
  sob,
  eob,
  boundary_type,
  boundary_target,
  pred_taken,
  insn_len,
  insn_raw
}
```

Source operand classes are `{P, T, U, CARG}`. Destination classes are `{P, T, U}`. `CARG` is source-only and resolves through the active `bid`.

## 10 Decode of Boundary Instructions

Boundary instruction handling differs from an ARM-style branch decoder:

- `BSTART.<type>` encodes a block type and a transition kind.
- `BSTOP` completes the active block.
- `C.BSTOP` is encoded as all zeroes in the compressed 16-bit space under current bring-up conventions.
- `HL.BSTART.*` provides 48-bit long-range target forms.
- `BSTART.CALL` may carry call metadata, but the architectural return address is still produced only by `SETRET` or `C.SETRET`.
- `SETC.*` writes block condition/target state consumed at block boundary; it is not an ordinary register-producing branch uop.

## 11 Open Questions

- Should F3 expose block metadata as compact semantic flags only, or should it also carry decoded block type and branch kind to reduce D2 work?
- Should the first RTL target implement full multi-thread IB banking from the start, or keep the model's scalar-thread path and reserve the interface shape?
- What should be the exact frontend policy when a fetch bundle contains a template boundary followed by ordinary instructions that are already assembled?
