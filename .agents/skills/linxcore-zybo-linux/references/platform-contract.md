# Zybo platform contract

Read `tools/fpga/zybo_z7_20/platform.json` for the machine-readable source of
truth. Preserve these frozen first-platform values.

| Contract | Value |
| --- | --- |
| Board/part | Zybo Z7-20 / `xc7z020clg400-1` |
| Accepted clock | `FCLK_CLK0`, 50 MHz (`safe_50`) |
| Linx address/PC/data | 32 / 64 / 64 bits |
| AXI HP0 | 64-bit, 64-byte lines, one outstanding transaction |
| Control | GP0 AXI-Lite at `0x43c00000`, size `0x00010000` |
| Linx DDR arena | `0x00000000..0x0fffffff` (256 MiB) |
| Route order | MMIO, DDR, fault |
| Budget | 40,000 LUT; 80,000 FF; 100 BRAM36; 64 DSP48 |

MMIO is never DDR: UART data is `0x10000000`; a read at `0x10000004` is UART
status and a write there is Linux exit; the canonical smoke finisher is
`0x10009000`; optional virtio starts at `0x30001000`. Unsupported addresses
fault. Keep the first DDR path non-coherent: the PS monitor cleans the Linx
arena before release and invalidates inspected lines after Linx stops.

Linux NOMMU boots PC `0x00010000`, SP `0x0ffef000`, `a0 = 0`,
`a1 = 0x0f000000`, with initramfs at `0x08000000`. The monitor must reject
overlapping kernel, initramfs, DTB, monitor, or MMIO ranges; the manifest
reserves kernel `0x00010000/0x01000000`, initramfs `0x08000000/0x04000000`,
and DTB `0x0f000000/0x00010000` (base/size).

Use deterministic Tcl. Require the exact Zybo board definition and PS preset;
failure is a preflight stop, never a reason to build a substitute bitstream.
