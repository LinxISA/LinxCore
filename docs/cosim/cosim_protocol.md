# LinxCore Co-Sim Protocol (M1)

This document defines the M1 lockstep interface between QEMU (`/Users/zhoubot/qemu/target/linx`) and the LinxCore lockstep runner (`/Users/zhoubot/LinxCore/cosim/linxcore_lockstep_runner.cpp`).

## 1) QEMU Environment Controls

QEMU reads the following environment variables:

- `LINX_COSIM_ENABLE=1`
- `LINX_COSIM_TRIGGER_PC=<hex>`
- `LINX_COSIM_TERMINATE_PC=<hex>`
- `LINX_COSIM_SOCKET=<unix_socket_path>`
- `LINX_COSIM_SNAPSHOT_PATH=<snapshot_bin_path>`
- `LINX_COSIM_MEM_RANGES=<base:size,base:size,...>` (hex or decimal)
- `LINX_COSIM_MAX_COMMITS=<int>` (optional; 0 means disabled)

Runner-side lockstep controls:

- `LINXCORE_MAX_DUT_CYCLES=<int>` hard stop for DUT stepping
- `LINXCORE_DEADLOCK_CYCLES=<int>` consecutive no-retire cycles before deadlock abort
- `LINXCORE_ACCEPT_MAX_COMMITS_END=1` optional: treat `end(reason="max_commits")` as success (sampling mode)
- `LINXCORE_DISASM_TOOL=<path>` optional disassembler helper (default: `linxdisasm.py`)
- `LINXCORE_DISASM_SPEC=<path>` optional ISA JSON spec path for disassembly

Behavior:

- `linx_cosim_before_insn` is called at instruction start.
- When `pc == trigger_pc`, QEMU writes a sparse snapshot and sends `start`.
- QEMU sends one `commit` JSONL record per retired instruction and waits for one ack.
- On first mismatch ack, QEMU fail-fast exits.
- On `pc == terminate_pc`, QEMU sends `end(reason="terminate_pc")` and exits.

## 2) Socket Transport

- Transport: Unix domain stream socket.
- Framing: one JSON object per line (JSONL).

### QEMU -> Runner

#### `start`

```json
{"type":"start","boot_pc":65536,"boot_sp":134217712,"boot_ra":134217840,"trigger_pc":65536,"terminate_pc":65824,"snapshot_path":"/tmp/linx.snap","seq_base":0}
```

#### `commit`

```json
{"type":"commit","seq":7,"pc":65564,"insn":12345,"len":4,"wb_valid":1,"wb_rd":2,"wb_data":42,"mem_valid":0,"mem_is_store":0,"mem_addr":0,"mem_wdata":0,"mem_rdata":0,"mem_size":0,"trap_valid":0,"trap_cause":0,"traparg0":0,"next_pc":65568}
```

#### `end`

```json
{"type":"end","reason":"terminate_pc"}
```

`reason` can be:

- `terminate_pc`
- `max_commits`
- `guest_exit`

### Runner -> QEMU

#### ack ok

```json
{"seq":7,"status":"ok"}
```

#### ack mismatch

```json
{"seq":7,"status":"mismatch","field":"wb_data","qemu":41,"dut":42}
```

## 3) Snapshot Binary Format

QEMU writes `LINX_COSIM_SNAPSHOT_PATH` with:

- Header:
  - `magic[8] = "LXCOSIM1"`
  - `version = 1` (`uint32_le`)
  - `range_count` (`uint32_le`)
- Range table (`range_count` entries):
  - `base` (`uint64_le`)
  - `size` (`uint64_le`)
  - `file_offset` (`uint64_le`) to payload bytes
- Raw payload bytes for each range

Ranges come from `LINX_COSIM_MEM_RANGES`.

## 4) Compare Normalization Rules (M1)

Runner compares QEMU commit vs DUT commit in order:

- Always compare: `pc`, `insn` (masked by `len`), `len`, `wb_valid`, `mem_valid`, `trap_valid`, `next_pc`
- If `wb_valid=0`: ignore `wb_rd`, `wb_data`
- If `mem_valid=0`: ignore `mem_is_store`, `mem_addr`, `mem_wdata`, `mem_rdata`, `mem_size`
- If `trap_valid=0`: ignore `trap_cause`, `traparg0`

## 5) Fail-Fast Policy

- First mismatch causes:
  - runner sends `status="mismatch"`
  - QEMU exits immediately
- Strict one-to-one retirement:
  - one QEMU `commit` must match exactly one DUT retire record
  - if QEMU sends `end` while DUT still has buffered retire records, runner reports mismatch (`extra_dut_commits`)
  - exception at `end(reason="terminate_pc")`: runner tolerates buffered DUT commits from the same DUT cycle as the matched terminate instruction (superscalar same-cycle tail)
- Deadlock detection:
  - if DUT cannot retire for `LINXCORE_DEADLOCK_CYCLES` while waiting for next commit, runner aborts
  - runner prints stuck ROB-head context (`PC`, raw insn, op/len, and disassembly when available)
- Success condition:
  - no mismatch and `end(reason="terminate_pc")`
  - sampling mode may also accept `end(reason="max_commits")` when `LINXCORE_ACCEPT_MAX_COMMITS_END=1` (or runner flag `--accept-max-commits-end`)
