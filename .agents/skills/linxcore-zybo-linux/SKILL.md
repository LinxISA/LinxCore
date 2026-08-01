---
name: linxcore-zybo-linux
description: Use when changing or reviewing LinxCore Chisel for Zybo Z7-20, Zynq PS/PL, Vivado, FPGA boot, AXI DDR, or Linx Linux board bring-up.
---

# LinxCore Zybo Linux

Treat board bring-up as an ordered evidence ladder. Never trade Linx
architectural correctness for fit or claim a later milestone from a proxy.

## Required workflow

1. Read `tools/fpga/zybo_z7_20/platform.json`, then
   [platform-contract.md](references/platform-contract.md) and
   [gates.md](references/gates.md). Use the manifest as the source of truth.
2. Name the requested milestone (`Z0`–`Z14`), the evidence it requires, and
   the earliest unmet predecessor. Run that predecessor's red gate first;
   stop at its first failure.
3. Keep the live Linx contracts while sizing: BSTART/BSTOP and marker-only
   blocking, CARG lifetime and call-header adjacency, and standalone FENTRY,
   FEXIT, and FRET behavior; 32-bit LSID ordering; precise traps and committed
   stores; accepted-owner recovery retention; and full commit-trace fields. A standalone execution-domain BSTOP remains an architectural marker and must not truncate already formed same-cacheline followers; only a BSTOP closing resident control context may terminate the stream. A compact profile must
   prove that the benchmark ROB holds 16 `_start_c` body uops **plus its
   closing marker**; do not cut block metadata or call an unowned OOO parameter
   a physical reduction.
4. Keep the platform contract exact: `xc7z020clg400-1`, 50 MHz `FCLK_CLK0`,
   one outstanding 64-bit AXI transaction, 64-byte lines, MMIO before DDR,
   GP0 control, and HP0 data. Do not fabricate successful unsupported core
   responses; return a classified fault.
5. Treat missing Zybo board definitions or a failed PS preset/board-automation
   command as a preflight failure. Do not continue to synthesis or emit a
   success bitstream; report the install/fix needed and rerun deterministic
   Tcl only after it succeeds.
6. For PS-to-DDR handoff, validate artifact bounds/CRC, clean the Linx DDR
   arena to the point of coherency before release, and invalidate PS lines
   before inspecting Linx output. Program PC/SP/`a0`/`a1` while Linx is reset
   or halted, then clear stale state and start.
7. Record the gate result and provenance: Git revision plus dirty state,
   manifest checksum, tool/Vivado and board-part versions, strategy,
   utilization, timing, DRC, bitstream SHA256, commands, and hardware logs.

## Linux claim boundary

Do not call the platform Linux-capable before Z8 proves ARM/Linx DDR
read-write, byte masks, bursts, and coherency; Z9 proves real fetch, loads,
committed stores, control flow, UART, and finisher; and Z10 proves precise
traps, timer interrupt, EOI, and return. Linux entry additionally requires
the Linux boot registers (`a0 = 0`, `a1 = 0x0f000000`, valid PC/SP), valid DTB
and early UART at Z11; a shell requires Z12. Earlier evidence cannot waive a
predecessor.

Use [pressure-scenarios.md](references/pressure-scenarios.md) when changing
this workflow or checking whether a proposed shortcut is safe.
