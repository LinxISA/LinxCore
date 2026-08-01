# Codex Goal Prompt: LinxCore Zybo Z7-20 Linux Bring-up

Paste the block below into Codex when creating the persistent implementation
goal. Do not set a token budget unless you intentionally want a hard budget.

```text
Create and pursue this goal:

In /mnt/c/Users/zhoub/linx/LinxCore-zybo-linux, implement the approved LinxCore Zybo Z7-20 Linux bring-up design on branch codex/zybo-linux-framework.

Authoritative documents:
- docs/superpowers/specs/2026-08-01-linxcore-zybo-linux-design.md
- docs/superpowers/plans/2026-08-01-linxcore-zybo-linux.md
- /mnt/c/Users/zhoub/linx/linx-isa/docs/bringup/contracts/fpga_platform_contract.md
- /mnt/c/Users/zhoub/linx/linx-isa/docs/bringup/phases/05_fpga_zybo_z7.md
- /mnt/c/Users/zhoub/linx/linx-isa/docs/bringup/phases/06_linux_on_janus.md

Execute the implementation plan task by task using test-driven development. Preserve the separate modified checkout at /mnt/c/Users/zhoub/linx/linx-isa/rtl/LinxCore and do not overwrite unrelated user changes. You may use bounded subagents for independent implementation review, specification review, and skill pressure testing; keep source edits coordinated in the named worktree.

The selected architecture is PS-assisted: Linx runs in PL, the ARM PS initializes DDR and loads images, GP0 provides AXI-Lite control, HP0 provides the 64-bit DDR path, and FCLK0 starts at 50 MHz. Keep MMIO decode ahead of DDR. Preserve UART 0x10000000, direction-sensitive UART-status/Linux-exit 0x10000004, canonical test finisher 0x10009000, PC/data width 64, physical address width 32, 64-byte lines, and the Linx block/CARG/FENTRY/FEXIT/FRET/LSID/precise-trace contracts.

Do not claim Linux readiness while instruction fetch or load data is injected, committed stores lack real DDR completion, boot a0/a1 are missing, AXI faults are imprecise, or timer/interrupt/exception-return ownership is incomplete. Stop at the first red prerequisite, diagnose it, add a regression test, and continue after it passes.

For each completed task:
1. run the focused RED/GREEN tests and relevant regressions;
2. update the plan checkboxes and evidence documentation;
3. commit a narrowly scoped change;
4. push codex/zybo-linux-framework to origin;
5. report exact commands, results, resource/timing numbers, and remaining blockers.

Continue across goal turns until the NOMMU Linx kernel reaches a BusyBox shell on the physical Zybo Z7-20 for ten cold boots, or until genuinely blocked after exhausting safe in-scope alternatives. Do not weaken an acceptance gate to make progress appear green. Update the superproject LinxCore pin only after the leaf branch is pushed and its required gates are fresh.
```
