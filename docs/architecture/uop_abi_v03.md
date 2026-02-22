# LinxCore Micro-Op ABI (v0.3 bring-up)

Status: **proposal / WIP**

This document defines the **internal micro-op ABI** for LinxCore used after decode and before rename/issue/execute/commit.
It is designed to:

- Implement LinxISA **strict v0.3** bring-up behavior.
- Support **split** (1 ISA instruction → multiple uops), e.g. store address/data split.
- Support **fusion/accumulation** (multiple ISA instructions → one uop), e.g. block-header descriptor accumulation and
  emission of a single block command at `BSTOP`.

Authoritative external semantics references:

- `docs/bringup/plan/isa_clarifications.md`
- ISA manual: `docs/architecture/isa-manual/src/linxisa-isa-manual.adoc`
- Architecture contract: `docs/architecture/v0.3-architecture-contract.md`
- Bring-up contract checks: `docs/bringup/check26_contract.yaml`

---

## 1. Terminology

- **DecEvent**: per-instruction decoded event (1 ISA instruction → 1 DecEvent). Not necessarily executable.
- **HdrAcc**: header accumulator that absorbs descriptor/config instructions (may emit 0 uops) and produces a fused uop.
- **UopPacket**: an executable micro-op (dec2 may emit 0..N UopPackets per DecEvent).
- **Unified opcode space**: a single `op_uop` identifies both ISA ops (`OP_*`) and internal uops (`UOP_*`).

---

## 2. Design invariants

1) **Rename boundary**
- `dec2` outputs **logical** register identifiers only (architectural indices).
- The rename stage maps logical registers to physical tags.

2) **Split support**
- An ISA instruction may expand into multiple uops (e.g. store splits to address/data uops).

3) **Fusion support**
- Multiple ISA instructions (e.g. block header descriptors) may be accumulated and fused into **one** uop at a boundary
  trigger (typically `BSTOP`).

4) **Precise trap binding**
- Traps are architecturally precise and bind to the retiring program order, even if an ISA instruction expanded into
  multiple uops.

---

## 3. Unified opcode space

### 3.1 Opcode naming

- ISA operations are named `OP_<MNEMONIC>` and correspond to LinxISA v0.3 mnemonics.
- Internal micro-ops are named `UOP_<NAME>`.

### 3.2 Opcode field

- `op_uop` is the single opcode field used by backend uops.
- `op_uop` covers both `OP_*` and `UOP_*`.
- Decoding produces `OP_*` DecEvents; dec2 may emit `UOP_*` via split/fuse/macro expansion.

Recommended width: **16 bits** (to accommodate ≥710 ISA ops plus internal uops and future growth).

---

## 4. DecEvent format (decode → dec2)

A DecEvent represents one architected instruction occurrence.

Required fields:

- `valid:1`
- `pc:64`
- `len_bytes:3`  (2/4/6/8)
- `insn_raw:64`  (raw bits, unused high bits cleared)
- `op_id:16`     (ISA opcode id; uses unified opcode numbering)
- `rd:6`, `rs1:6`, `rs2:6`  (unused = REG_INVALID)
- `imm:64`
- `imm_kind:4`  (`NONE/I/S/B/U/J/BLOCK/TILE`)
- `epoch/checkpoint_id` (optional, if required by the redirect/flush model)

Notes:
- DecEvent intentionally contains minimal categorization; dec2 looks up category/meta via `OP_META[op_id]`.

---

## 5. UopPacket format (dec2 → rename/issue)

A UopPacket is an executable operation. dec2 may emit 0..N UopPackets per DecEvent.

### 5.1 Common header

- `uop_valid:1`
- `uop_uid:64`     (unique per uop)
- `pc:64`          (anchor PC for debug/trace)
- `isa_anchor_uid:64`  (ties split/fused uops to their originating DecEvent group)
- `op_uop:16`      (unified opcode)
- `major_cat:4`    (`BOUNDARY/INT/BRU/LSU/ATOM/SYS/FP/VEC/TILE`)
- `uop_kind:6`     (execution resource class; examples below)
- `macro_mode:2`   (`NONE/SPLIT/FUSED/FSM`)
- `macro_uid:32`   (same across uops originating from one DecEvent split, or across a fused header group)
- `macro_step:8`, `macro_len:8`

### 5.2 Logical operand specification (pre-rename)

- `dst_kind:2` (`NONE/GPR/SYSR/TILE`)
- `dst:6`
- `src_kind[0..2]:2`
- `src[0..2]:6`
- `imm:64`, `imm_kind:4`

---

## 6. Category enums

### 6.1 major_cat

- `BOUNDARY`: block markers/descriptors and command-stream operations (B.* / BSTART.* / BSTOP)
- `INT`: integer ALU and shifts
- `BRU`: compares, setc, control-flow operations
- `LSU`: loads/stores
- `ATOM`: atomic ops and fences
- `SYS`: cache/TLB/system ops
- `FP`: floating point ops
- `VEC`: vector ops
- `TILE`: tile/TMU/TMA/CUBE/TAU execution-side ops (non-command execution)

### 6.2 uop_kind (examples)

Execution/issue classes are separated from "command IQ" operations.

Compute/execute examples:

- `ALU`
- `BRU`
- `AGU` (address generate unit; includes **LDA** = load address/memory op, and store address)
- `STD` (store data)
- `FSU` (floating-point execution unit)
- `VEC` (vector execution; internally sub-classed)
- `AMO`
- `SYS`
- `RSV` (reserved/unassigned encodings; must trap or follow v0.3 policy)

Command/Boundary examples (flow through the unified **CMD IQ**):

- `BBD` (block-boundary domain markers; BSTART/BSTOP and boundary events)
- `CMD` (generic command uop)
  - All `B.*` descriptor ops (e.g. `B.IOR`, `B.ARG`, `B.TEXT`, ...)
  - Engine launch commands formed at `BSTOP` ("block cmd" == "accel cmd")

Note: TMU/TMA/CUBE/TAU do **not** appear as compute/execute uop kinds; they are formed by **multiple CMD uops** and routed to engines.

For CMD uops, additional routing fields are required (see §8):

- `cmd_kind` (descriptor / launch / barrier / etc.)
- `engine_target` (which engine consumes this command)

---

## 7. LSU split/fuse rules

### 7.1 Store split rule (mandatory)

An ISA store may expand into two uops:

- `UOP_AGU` (store address): address-generation and ordering reservation (allocates/uses `stq_id`).
- `UOP_STD` (store data): data payload uop bound to the same `stq_id`.

Both must share:

- `macro_mode = SPLIT`
- `macro_uid` (same)
- `stq_id` (same)

### 7.2 Load rule

Loads are represented as **LDA** operations executed by the `AGU` class (load address + memory access semantics).
If the design later chooses to split address and memory access, that split must remain architecturally equivalent.

---

## 8. CMD IQ model (descriptor + launch + TEPL)

LinxCore uses a unified **CMD IQ** for block command traffic.

### 8.1 Descriptor ops are CMD uops

All `B.*` descriptor/config instructions (e.g. `B.ARG/B.TEXT/B.IOR/B.IOT/B.DIM/B.ATTR/...`) are represented as:

- `uop_kind = CMD`
- `cmd_kind = DESC_<FAMILY>` (e.g. `DESC_B_IOR`)

Implementation may also accumulate descriptor effects in local header state (**HdrAcc**) before launch.

### 8.2 Launch at BSTOP (block cmd == accel cmd)

For TEPL blocks (`BSTART.TEPL`), the launch CMD must additionally carry:

- `tpl_id` = TEPL template operation selector (v0.3 `TileOp10`)
- any required arguments sourced from accumulated descriptors (`B.ARG/B.IOR/...`) or defined argument namespaces

At `BSTOP`, the implementation forms a **single launch command** (conceptually "block cmd" == "accel cmd") and routes it
to the selected engine.

- If header/format validation fails, raise v0.3 traps (e.g. `E_BLOCK(EC_BLOCKFMT)`) and do not launch.
- If valid, emit either:
  - a single fused CMD uop (`uop_kind=CMD`, `cmd_kind=LAUNCH`, `macro_mode=FUSED`), **or**
  - an equivalent internal representation as long as the CMD IQ externally observes one logical launch.

Required routing fields for CMD uops:

- `engine_target` (which engine consumes this command)
- `cmd_kind` (descriptor / launch / barrier / template / etc.)
- `tpl_mode` (when `cmd_kind` relates to templates):
  - `CORE_MACRO`: decoded template expands into core-executed uop sequences (e.g. `FENTRY/FEXIT/FRET*/MCOPY`)
  - `TEPL_ENGINE`: TEPL template-op is routed via CMD IQ to an engine
- `tpl_id` (template operation selector; for TEPL this is the v0.3 `TileOp10` / template-op id)

---

## 9. Internal uop list (initial)

Minimum required internal uops:

- `UOP_NOP`
- `UOP_AGU` (covers LDA + store-address form; distinguished via meta/cmd_kind)
- `UOP_STD`
- `UOP_BBD` (block boundary marker uop)
- `UOP_CMD` (generic CMD IQ uop)

---

## 10. Golden classification + pycircuit dispatch (issq routing)

The uop classification golden is the source of truth for **instruction dispatch** in the PYC model.

Golden paths:

- Directory tree (preferred): `docs/architecture/golden/uop_classification_v0.3/`
  - `index.json` lists all big_kinds and sub_kinds.
  - `<BIG_KIND>/<SUB_KIND>.json` contains instruction records.
- Monolithic compatibility file (deprecated): `docs/architecture/golden/uop_classification_v0.3.json`

### 10.1 How dec2 uses golden

1. Decode produces `DecEvent(op_id, pc, regs, imm, ...)`.
2. dec2 looks up classification meta for `op_id` / mnemonic:
   - `uop_kind` (big kind): ALU / BRU / AGU / STD / FSU / VEC / AMO / SYS / BBD / CMD / RSV
   - sub-kind fields: e.g. `agu_kind`, `addr_mode`, `cmd_kind`, `engine_target`, `tpl_mode`, etc.
3. dec2 forms one or more `UopPacket` and enqueues them into the appropriate **issue queue (issq)**.

### 10.2 Recommended issq mapping

- `issq_alu`  ← `uop_kind=ALU`
- `issq_bru`  ← `uop_kind=BRU`
- `issq_agu`  ← `uop_kind=AGU` (includes LDA/STA)
- `issq_std`  ← `uop_kind=STD`
- `issq_fsu`  ← `uop_kind=FSU`
- `issq_vec`  ← `uop_kind=VEC`
- `issq_amo`  ← `uop_kind=AMO`
- `issq_sys`  ← `uop_kind=SYS`
- `issq_bbd`  ← `uop_kind=BBD`
- `issq_cmd`  ← `uop_kind=CMD` (CMD IQ)
- `issq_rsv`  ← `uop_kind=RSV` (reserved encodings; trap per v0.3 policy)

### 10.3 CMD IQ routing

For `uop_kind=CMD`, the golden additionally provides:

- `cmd_kind` (e.g. `DESC_*`, `BLOCK_SPLIT`, `VEC_ENGINE_CMD`, `Q_PUSH/Q_POP/Q_MANAGE`, `LAUNCH`, ...)
- `engine_target` (e.g. `VEC`, `TMA`, `TMU`, `CUBE`, ...)
- `tpl_mode` / `tpl_id` for TEPL template routing

These fields are consumed by the CMD IQ / engine dispatch logic to route commands to engines.

---

## 11. Open items

- Finalize bitwidth decisions and packing.
- Specify the trap payload ABI (`TRAPNO`, `TRAPARG0`, `BI`) in the commit trace.
