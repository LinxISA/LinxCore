# Zybo evidence gates

Advance only through the earliest red gate. Fresh Chisel tests, emitted-RTL
lint, Vivado reports, and hardware evidence are required in proportion to the
claim.

| Gate | Evidence |
| --- | --- |
| Z0 | Manifest validation and address consistency |
| Z1 | Focused Scala tests and constructor constraints |
| Z2 | MMIO, boot-register, AXI, UART, timer, reset tests |
| Z3 | Compact-top stalls, errors, trace tests |
| Z4 | CIRCT emission and Verilator lint |
| Z5 | Synthesis utilization within budget |
| Z6 | 50 MHz timing, DRC, bitstream |
| Z7 | Reset, LEDs, UART mailbox, AXI-control repeatability |
| Z8 | ARM/Linx DDR read-write, byte masks, bursts, coherency |
| Z9 | UART, real memory/control flow/load/store, finisher |
| Z10 | Precise traps, timer IRQ, EOI, return |
| Z11 | `_start`, DTB magic, memory discovery, early UART |
| Z12 | NOMMU initramfs and BusyBox shell |
| Z13 | Ten cold boots with archived logs and manifests |
| Z14 | PTW/TLB/page faults after NOMMU stability |

For fit changes, preserve BSTART/BSTOP safety, CARG lifetime and call-header
adjacency, and standalone FENTRY/FEXIT/FRET behavior; 32-bit LSID ordering,
precise committed side effects, recovery ownership, and the complete trace. A standalone execution-domain BSTOP remains an architectural marker and must not truncate already formed same-cacheline followers; only a BSTOP closing resident control context may terminate the stream. The actual benchmark ROB must
accommodate 16 `_start_c` body uops plus a closing marker. For Linux handoff,
verify PS cache clean to the point of coherency, PS invalidation before
inspection, boot PC/SP/`a0`/`a1`, real fetch/load/committed-store DDR traffic,
trap, and timer gates.

A valid Vivado result records Git revision/dirty state, manifest checksum,
Vivado and board-part versions, strategy, utilization, timing, DRC, and
bitstream SHA256. Never let later Linux output waive an earlier failure.
