# LinxCore Zybo Z7-20 Linux Bring-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a resource-bounded Chisel Linx platform for the Zybo Z7-20 and advance it through real PS-DDR execution to a reproducible NOMMU Linux BusyBox shell.

**Architecture:** A compact Linx CPU runs in PL. A Zynq PS monitor owns board initialization, image loading, cache maintenance, console relay, and reset; AXI GP0 controls the platform and AXI HP0 carries Linx memory traffic. A generated platform manifest keeps Scala, Tcl, C, DTS, boot, and verification contracts synchronized.

**Tech Stack:** Scala 2.13.17, Chisel 7.3.0, CIRCT, ScalaTest, Python 3, SystemVerilog, Verilator, Tcl, Vivado 2025.2, Zynq standalone C, Linx Linux and BusyBox.

## Global Constraints

- Target revision begins at `294542ffb5c9a65915af34fa38abc32e2eec134f` on branch `codex/zybo-linux-framework`.
- Target part is exactly `xc7z020clg400-1`.
- First accepted core clock is 50 MHz from PS `FCLK_CLK0`.
- Linx physical PC and data remain 64-bit; the first FPGA physical address path is 32-bit.
- Cache lines remain 64 bytes.
- The first platform contains one Linx STID and one outstanding AXI transaction.
- MMIO decode precedes DDR forwarding.
- UART data remains `0x10000000`.
- Linux exit compatibility remains write-only `0x10000004` while reads at that address return UART status.
- The canonical FPGA test finisher remains `0x10009000`.
- Linux boots with `a0 = 0`, `a1 = 0x0f000000`, and a valid initial SP.
- Resource budgets are 40,000 LUT, 80,000 FF, 100 BRAM36, and 64 DSP48.
- Linx block marker safety, CARG lifetime, FENTRY/FEXIT/FRET behavior, 32-bit LSID ordering, precise committed side effects, and retained recovery ownership may not be weakened by FPGA sizing.
- A completion claim requires fresh Chisel tests, emitted RTL lint, Vivado reports, and hardware evidence appropriate to the claimed gate.
- The existing modified LinxCore checkout at `/mnt/c/Users/zhoub/linx/linx-isa/rtl/LinxCore` remains untouched.

---

## File and Ownership Map

| Area | Files | Responsibility |
| --- | --- | --- |
| Contract | `tools/fpga/zybo_z7_20/platform.json` | single source of platform truth |
| Generation | `tools/fpga/zybo_z7_20/generate_platform.py` | produce Scala, Tcl, C, DTS constants |
| Validation | `tools/fpga/zybo_z7_20/check_framework.py` | fail-fast cross-file and tool preflight |
| Skill | `.agents/skills/linxcore-zybo-linux/` | repeatable board workflow and evidence gates |
| Chisel sizing | `chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720PlatformParams.scala` | compact core and OOO profiles |
| Chisel protocols | `LinxPlatformMemory.scala`, `LinxAxi4.scala` | typed native and AXI boundaries |
| Chisel peripherals | `LinxMmioRouter.scala`, `LinxControlStatus.scala`, `LinxUartMailbox.scala`, `LinxPlatformTimer.scala` | platform state and MMIO |
| Core promotion | `LinxCoreBenchmarkAutonomousTop.scala` and focused owners | replace injected data with retained memory handshakes |
| FPGA top | `LinxCoreZyboTop.scala`, `EmitLinxCoreZyboTop.scala` | synthesizable integration and emission |
| Vivado | `tools/fpga/zybo_z7_20/vivado/` | deterministic PS/PL project, reports, bitstream |
| PS boot | `tools/fpga/zybo_z7_20/boot/ps_monitor/` | load, flush, release, relay, evidence |
| Linux | `tools/fpga/zybo_z7_20/boot/dts/` and superproject kernel changes | DTB and NOMMU boot contract |
| Evidence | `out/fpga/zybo_z7_20/<run-id>/` | generated reports; never checked in by default |

## Task 1: Platform Manifest, Generator, and Fail-Fast Validation

**Files:**
- Create: `tools/fpga/zybo_z7_20/platform.json`
- Create: `tools/fpga/zybo_z7_20/generate_platform.py`
- Create: `tools/fpga/zybo_z7_20/check_framework.py`
- Create: `tools/fpga/zybo_z7_20/tests/test_platform_contract.py`
- Create generated: `chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720Generated.scala`
- Create generated: `tools/fpga/zybo_z7_20/generated/platform_constants.tcl`
- Create generated: `tools/fpga/zybo_z7_20/generated/platform.h`
- Create generated: `tools/fpga/zybo_z7_20/generated/linx-zybo-memory.dtsi`

**Interfaces:**
- Consumes: the addresses, clocks, boot profiles, AXI geometry, resource limits, and board identity frozen in the design specification.
- Produces: `load_manifest(path: Path) -> dict`, `validate_manifest(data: dict) -> list[str]`, deterministic generated files, and `check_framework.py --mode source|tools|generated|all`.

- [x] **Step 1: Write the failing manifest-contract tests**

```python
class PlatformContractTest(unittest.TestCase):
    def test_linux_regions_do_not_overlap(self):
        data = load_manifest(MANIFEST)
        self.assertEqual(validate_manifest(data), [])

    def test_mmio_decode_precedes_ddr(self):
        data = load_manifest(MANIFEST)
        self.assertEqual(data["routing"]["priority"], ["mmio", "ddr", "fault"])

    def test_linux_boot_contract(self):
        linux = load_manifest(MANIFEST)["boot_profiles"]["linux_nommu"]
        self.assertEqual(linux["pc"], "0x00010000")
        self.assertEqual(linux["sp"], "0x0ffef000")
        self.assertEqual(linux["a0"], "0x00000000")
        self.assertEqual(linux["a1"], "0x0f000000")
```

- [x] **Step 2: Run the test and verify RED**

Run:

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_platform_contract -v
```

Expected: import or file-not-found failure because the manifest loader and manifest do not exist.

- [x] **Step 3: Implement the manifest and validator**

The manifest must contain these top-level keys and values:

```json
{
  "schema_version": 1,
  "board": {"name": "zybo_z7_20", "part": "xc7z020clg400-1"},
  "clock_profiles_hz": {"safe_50": 50000000, "balanced_75": 75000000, "stretch_100": 100000000},
  "axi": {"control_base": "0x43c00000", "control_size": "0x00010000", "data_width": 64, "line_bytes": 64, "max_outstanding": 1},
  "linx_memory": {"base": "0x00000000", "size": "0x10000000"},
  "mmio": {"uart_data": "0x10000000", "uart_status_linux_exit": "0x10000004", "test_finisher": "0x10009000", "virtio_base": "0x30001000"},
  "routing": {"priority": ["mmio", "ddr", "fault"]},
  "boot_profiles": {
    "smoke": {"pc": "0x00010000", "sp": "0x0003ff00", "a0": "0x00000000", "a1": "0x00000000"},
    "linux_nommu": {"pc": "0x00010000", "sp": "0x0ffef000", "a0": "0x00000000", "a1": "0x0f000000", "initramfs": "0x08000000"}
  },
  "resource_budget": {"lut": 40000, "ff": 80000, "bram36": 100, "dsp48": 64}
}
```

Validation must reject non-power-of-two line sizes, unsupported parts,
overlapping boot artifacts, MMIO inside the declared Linx RAM range, more
than one first-profile outstanding transaction, or a non-MMIO-first priority.

- [x] **Step 4: Verify GREEN and deterministic generation**

Run:

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_platform_contract -v
python3 tools/fpga/zybo_z7_20/generate_platform.py --check
python3 tools/fpga/zybo_z7_20/check_framework.py --mode source
```

Expected: all tests pass and generated files match the manifest.

- [x] **Step 5: Commit**

```bash
git add tools/fpga/zybo_z7_20 chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720Generated.scala
git commit -m "fpga: add generated Zybo platform contract"
```

## Task 2: Repository-Local Zybo Linux Skill

**Files:**
- Create through scaffolder: `.agents/skills/linxcore-zybo-linux/SKILL.md`
- Create: `.agents/skills/linxcore-zybo-linux/agents/openai.yaml`
- Create: `.agents/skills/linxcore-zybo-linux/references/platform-contract.md`
- Create: `.agents/skills/linxcore-zybo-linux/references/gates.md`
- Create: `.agents/skills/linxcore-zybo-linux/references/pressure-scenarios.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: `platform.json`, this plan, the design specification, and existing Linx architecture/RTL gates.
- Produces: a discoverable skill triggered by Zybo, Zynq, Vivado, FPGA Linux, PS/PL, HP0, or Linx board bring-up work.

- [ ] **Step 1: Record three failing baseline scenarios**

Run three fresh-context agents without the skill. Give them the scenarios
below and record whether they violate the named invariant:

```text
1. Fit pressure: "Make Linx tiny for Zybo; cut the ROB to 8 and remove block metadata."
   Required observation: baseline must be checked for block-safety and 16-uop _start_c capacity errors.
2. Boot pressure: "Connect the core to DDR and jump to Linux immediately."
   Required observation: baseline must be checked for missing a0/a1, cache clean, real store, trap, and timer gates.
3. Vivado pressure: "Board automation failed, but continue and make a bitstream anyway."
   Required observation: baseline must be checked for silent PS preset failure.
```

At least one baseline failure is required. If every baseline passes, do not
create a redundant skill; move the board-specific requirements into `AGENTS.md`
and retain the deterministic scripts.

- [ ] **Step 2: Scaffold the skill**

```bash
python3 /mnt/c/Users/zhoub/.codex/skills/.system/skill-creator/scripts/init_skill.py \
  linxcore-zybo-linux \
  --path .agents/skills \
  --resources references \
  --interface display_name="LinxCore Zybo Linux" \
  --interface short_description="Bring up compact LinxCore Chisel designs on Zybo Z7-20" \
  --interface default_prompt="Plan and verify a compact LinxCore Zybo Z7-20 FPGA/Linux milestone."
```

- [ ] **Step 3: Write the minimal skill against observed failures**

Use this frontmatter and workflow order:

```yaml
---
name: linxcore-zybo-linux
description: Use when changing or reviewing LinxCore Chisel for Zybo Z7-20, Zynq PS/PL, Vivado, FPGA boot, AXI DDR, or Linx Linux board bring-up.
---
```

The body requires: read manifest, name the current milestone, run the earliest
red gate, preserve Linx block/trace contracts, reject silent Vivado board
automation failure, record tool/resource/timing provenance, and stop Linux
claims before real memory, trap, timer, and boot-register gates pass.

- [ ] **Step 4: Validate GREEN with the skill loaded**

Run the same three scenarios with the skill loaded and require all three
expected decisions. Then run:

```bash
python3 /mnt/c/Users/zhoub/.codex/skills/.system/skill-creator/scripts/quick_validate.py \
  .agents/skills/linxcore-zybo-linux
```

Expected: `Skill is valid!` and pressure scenarios preserve all named gates.

- [ ] **Step 5: Commit**

```bash
git add .agents/skills/linxcore-zybo-linux AGENTS.md
git commit -m "docs: add LinxCore Zybo Linux workflow skill"
```

## Task 3: Compact Core and OOO Profiles

**Files:**
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/ZyboZ720PlatformParamsSpec.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720PlatformParams.scala`

**Interfaces:**
- Consumes: `CoreParams`, `ScalarBackendParams`, `ScalarLsuParams`, and `OooParams`.
- Produces: `ZyboZ720PlatformParams.LinuxMinCore`, `LinuxMinOoo`, `Smoke`, `LinuxNommu`, and constructor/ownership summaries.

- [ ] **Step 1: Write failing parameter tests**

```scala
test("Linux-min profile preserves architecture widths while reducing storage") {
  val p = ZyboZ720PlatformParams.LinuxMinCore
  assert(p.robEntries == 32)
  assert(p.commitWidth == 2)
  assert(p.scalarBackend.gprPhysRegs == 64)
  assert(p.scalarLsu.addrWidth == 32)
  assert(p.scalarLsu.pcWidth == 64)
  assert(p.scalarLsu.dataWidth == 64)
  assert(p.scalarLsu.lineBytes == 64)
  assert(p.lsidWidth == 32)
}

test("Linux-min OOO profile satisfies all constructor constraints") {
  val p = ZyboZ720PlatformParams.LinuxMinOoo
  assert(p.stidCount == 1)
  assert(p.instructionDecodeWidth == 2)
  assert(p.robGroupsPerStid == 16)
  assert(p.maxCommitStoreTokens == 16)
  assert(p.storeCommitBufferEntries == 16)
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
bash tools/chisel/run_chisel_tests.sh --only ZyboZ720PlatformParamsSpec
```

Expected: compile failure because `ZyboZ720PlatformParams` does not exist.

- [ ] **Step 3: Implement exact profiles**

Construct `LinuxMinCore` with the values in design section 6.1. Construct
`LinuxMinOoo` with one STID, 2/4/2/2/2 ingress widths, 16 ROB groups, four ROB
banks, 32 BROB entries, 64 P tags, 16 T/U tags, 16 PC entries, two IQ banks
with eight entries, a four-entry completion buffer, and 16 store tokens.

Add `require` checks that the benchmark ROB can contain 16 body uops plus a
closing marker and that Scala and generated manifest values match.

- [ ] **Step 4: Verify GREEN and elaboration ownership**

```bash
bash tools/chisel/run_chisel_tests.sh --only ZyboZ720PlatformParamsSpec
bash tools/chisel/build_chisel.sh
```

Expected: tests and compile pass. Record the generated hierarchy locations
that consume every reduced value; mark unconsumed OOO values as unpromoted in
the test report rather than claiming a resource reduction.

- [ ] **Step 5: Commit**

```bash
git add chisel/src/main/scala/linxcore/fpga/zybo/ZyboZ720PlatformParams.scala \
  chisel/src/test/scala/linxcore/fpga/zybo/ZyboZ720PlatformParamsSpec.scala
git commit -m "chisel: add compact Zybo core profiles"
```

## Task 4: Native Memory Protocol and MMIO-First Router

**Files:**
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxPlatformMemory.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxMmioRouter.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxMmioRouterSpec.scala`

**Interfaces:**
- Produces: `LinxMemRequest`, `LinxMemResponse`, `LinxMemFault`, `LinxMmioRequest`, `LinxMmioResponse`, and one-in/one-out retained Decoupled routing.
- Request fields: `id`, `source`, `addr`, `write`, `size`, `wdata`, `wstrb`, `line`, and `last`.
- Response fields: `id`, `rdata`, `fault`, and `last`.

- [ ] **Step 1: Write failing routing tests**

```scala
test("write at 0x10000004 is Linux exit while read is UART status") {
  assert(LinxMmioMap.classify(BigInt("10000004", 16), write = true) == LinuxExit)
  assert(LinxMmioMap.classify(BigInt("10000004", 16), write = false) == UartStatus)
}

test("test finisher and DDR never alias") {
  assert(LinxMmioMap.classify(BigInt("10009000", 16), write = true) == TestFinisher)
  assert(!LinxMmioMap.isDdr(BigInt("10009000", 16)))
}
```

- [ ] **Step 2: Verify RED**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxMmioRouterSpec
```

Expected: compile failure because the protocol and router do not exist.

- [ ] **Step 3: Implement the protocol and router**

The router accepts a request only when the selected destination can retain it.
It never issues the same request to MMIO and DDR. Unsupported addresses return
`LinxMemFault.Decode`; MMIO size or alignment violations return
`LinxMemFault.Access`. Linux exit and test-finisher writes emit separate
sideband events with the original 32-bit payload.

- [ ] **Step 4: Verify GREEN under backpressure**

Add tests for request stability, response stability, byte strobes, unmapped
faults, UART TX/RX status, and destination backpressure. Run:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxMmioRouterSpec
```

- [ ] **Step 5: Commit**

```bash
git add chisel/src/main/scala/linxcore/fpga/zybo/LinxPlatformMemory.scala \
  chisel/src/main/scala/linxcore/fpga/zybo/LinxMmioRouter.scala \
  chisel/src/test/scala/linxcore/fpga/zybo/LinxMmioRouterSpec.scala
git commit -m "chisel: add Linx FPGA memory and MMIO contract"
```

## Task 5: AXI4 HP0 Master

**Files:**
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxAxi4.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxAxi4Master.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxAxi4MasterSpec.scala`
- Create: `tools/chisel/run_chisel_zybo_axi_probe.sh`

**Interfaces:**
- Consumes: retained `LinxMemRequest` DDR transactions.
- Produces: AXI4 AW/W/B and AR/R channels with 32-bit addresses, 64-bit data, 8-bit strobes, and one ID.

- [ ] **Step 1: Write failing burst-reference tests**

```scala
test("64-byte line maps to eight 64-bit beats") {
  val burst = LinxAxi4Reference.readBurst(BigInt("00102000", 16), 64)
  assert(burst.len == 7)
  assert(burst.size == 3)
  assert(burst.addresses == (0 until 8).map(i => BigInt("00102000", 16) + i * 8))
}

test("burst crossing 4 KiB is rejected") {
  assertThrows[IllegalArgumentException] {
    LinxAxi4Reference.readBurst(BigInt("00100fe0", 16), 64)
  }
}
```

- [ ] **Step 2: Verify RED**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxAxi4MasterSpec
```

- [ ] **Step 3: Implement the one-outstanding AXI FSM**

States are `Idle`, `SendAr`, `ReceiveR`, `SendAw`, `SendW`, `ReceiveB`, and
`Respond`. The request is retained from acceptance through response. The read
path validates `RID`, `RLAST`, and beat count. The write path validates `BID`.
`SLVERR`, `DECERR`, ID mismatch, early/late `RLAST`, or beat overflow maps to a
retained `LinxMemFault.Protocol` or `LinxMemFault.Bus` response.

- [ ] **Step 4: Verify GREEN with randomized stalls**

Exercise every AXI channel with independent ready/valid stalls, then run:

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxAxi4MasterSpec
bash tools/chisel/run_chisel_zybo_axi_probe.sh
```

Expected: focused tests pass and emitted AXI probe passes Verilator lint.

- [ ] **Step 5: Commit**

```bash
git add chisel/src/main/scala/linxcore/fpga/zybo/LinxAxi4.scala \
  chisel/src/main/scala/linxcore/fpga/zybo/LinxAxi4Master.scala \
  chisel/src/test/scala/linxcore/fpga/zybo/LinxAxi4MasterSpec.scala \
  tools/chisel/run_chisel_zybo_axi_probe.sh
git commit -m "chisel: add retained Zybo AXI memory master"
```

## Task 6: Promote Real IFU, Load, and Store Memory Ownership

**Files:**
- Modify: `chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala:33`
- Modify focused production owners selected by the memory-ownership audit under `chisel/src/main/scala/linxcore/frontend/` and `chisel/src/main/scala/linxcore/lsu/`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxCoreMemoryBoundary.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxCoreMemoryBoundarySpec.scala`

**Interfaces:**
- Consumes: IFU line requests, LSU load attempts, committed store fragments, flush/recovery, and AXI responses.
- Produces: one retained native memory port with exact request identity and precise response ownership.

- [ ] **Step 1: Write a failing ownership test**

The test must prove these conditions in one scenario:

```scala
assert(fetchRequestHoldsUntilAccepted)
assert(loadAttemptHoldsUntilMatchingResponse)
assert(committedStoreFreesOnlyAfterWriteResponse)
assert(flushCannotCancelCommittedStore)
assert(staleLoadResponseCannotCompleteReallocatedRow)
```

Name the exact load generation, ROB identity, LSID, and store token fields that
make each assertion fail when omitted.

- [ ] **Step 2: Verify RED on the current injected-data boundary**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreMemoryBoundarySpec
```

Expected: the current top lacks a retained load response and real committed
store completion, so the ownership test fails for those reasons.

- [ ] **Step 3: Implement IFU line transport**

Map `IfuWindowLineFillAdapter` requests to 64-byte native reads. Retain PC,
line base, transaction identity, and redirect epoch through the response. A
flush may discard a stale refill only after the transport safely consumes its
AXI response.

- [ ] **Step 4: Implement LSU load transport**

Replace combinational `loadLookupData` injection with Decoupled request and
response ownership at the canonical load-miss/refill boundary. Carry load
generation, RID/BID/LSID, address, size, sign extension, destination tags, and
fault. Completion occurs only for the exact resident attempt.

- [ ] **Step 5: Implement committed-store transport**

Route the canonical committed-store serializer or SCB egress to native writes.
Retain byte masks and split fragments. Free a committed store token only after
the matching AXI write response; a bus error becomes a precise retained fault.

- [ ] **Step 6: Verify GREEN and preserve existing regressions**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreMemoryBoundarySpec
bash tools/chisel/run_chisel_tests.sh --only LinxCoreBenchmarkAutonomousTopSpec
bash tools/chisel/run_chisel_tests.sh --only FullBidBlockConditionOwnerSpec
bash tools/chisel/run_chisel_tests.sh --only LinxCoreFentryFretNaturalObservationSpec
```

- [ ] **Step 7: Commit**

```bash
git add chisel/src/main/scala/linxcore chisel/src/test/scala/linxcore
git commit -m "chisel: promote real FPGA memory ownership"
```

## Task 7: AXI-Lite Control, Boot Registers, UART, and Reset Sequencing

**Files:**
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxAxiLite.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxControlStatus.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxBootRegisterInit.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxUartMailbox.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxControlStatusSpec.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxBootRegisterInitSpec.scala`

**Interfaces:**
- Consumes: GP0 AXI-Lite, core status, UART events, finisher events, and fault events.
- Produces: start/reset/halt commands, PC/SP/`a0`/`a1`, three RF initialization writes, UART FIFOs, and sticky status.

- [ ] **Step 1: Write failing boot-sequence tests**

```scala
test("start initializes sp a0 and a1 before releasing fetch") {
  assert(initWrites.map(_.archTag) == Seq(1, 2, 3))
  assert(initWrites.map(_.data) == Seq(sp, hartId, dtb))
  assert(startPulseCycle > initWrites.last.cycle)
}

test("new start clears stale terminal state") {
  assert(afterStart.exitCode == 0)
  assert(!afterStart.trap)
  assert(!afterStart.pass)
  assert(!afterStart.fail)
}
```

Linx ABI architectural tags are SP=`R1`, `a0=R2`, and `a1=R3`.

- [ ] **Step 2: Verify RED**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxBootRegisterInitSpec
bash tools/chisel/run_chisel_tests.sh --only LinxControlStatusSpec
```

- [ ] **Step 3: Implement control and boot sequencing**

Capture AXI-Lite AW and W independently, apply byte strobes, reject writes to
read-only registers, and return `SLVERR` for unassigned offsets. Start is a
one-cycle command. The boot initializer writes R1, R2, and R3 on consecutive
accepted RF-init cycles, then emits the internal core start pulse.

- [ ] **Step 4: Implement UART mailboxes**

Use 16-byte TX and RX FIFOs. Reads of `0x10000004` return TX-ready and RX-ready.
Writes to `0x10000004` generate Linux exit and never enter the UART FIFO.
Overflow is sticky and visible in `STATUS`.

- [ ] **Step 5: Verify GREEN**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxBootRegisterInitSpec
bash tools/chisel/run_chisel_tests.sh --only LinxControlStatusSpec
```

- [ ] **Step 6: Commit**

```bash
git add chisel/src/main/scala/linxcore/fpga/zybo \
  chisel/src/test/scala/linxcore/fpga/zybo
git commit -m "chisel: add Zybo control and Linux boot registers"
```

## Task 8: Architectural Timer, Interrupt, and Precise Fault Gate

**Files:**
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxPlatformTimer.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxPlatformTimerSpec.scala`
- Modify the canonical system/CSR, recovery, and commit owners selected by the architecture audit.
- Create: `docs/fpga/zybo-z7-20-architectural-gap-ledger.md`

**Interfaces:**
- Consumes: 50 MHz clock, Linx SSR time/timecmp operations, EOI, exceptions, and external platform faults.
- Produces: monotonic time, IRQ0 pending, precise trap records, and architectural return.

- [ ] **Step 1: Write failing timer and trap tests**

Prove monotonic reads, compare-before/after behavior, pending retention until
EOI, interrupt masking, interrupted PC/BPC preservation, precise AXI access
faults, and resume after the architectural exception-return instruction.

- [ ] **Step 2: Verify RED against the current top**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxPlatformTimerSpec
```

Expected: missing live system/CSR and interrupt delivery ownership.

- [ ] **Step 3: Close the minimum QEMU/Linux parity surface**

Derive SSR indices, CSTATE interrupt enable/ring behavior, EVBASE, ETEMP,
IPENDING, EOIEI, time, and timecmp semantics from the Linx ISA, QEMU, and Linux
sources. Record each implemented or blocked item in the gap ledger with a test
name and source citation.

- [ ] **Step 4: Verify GREEN with differential vectors**

Run the same timer/trap vectors through QEMU and the Chisel reference harness;
compare trap cause, PC, BPC, pending bits, and post-return PC.

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxPlatformTimerSpec
```

- [ ] **Step 5: Commit**

```bash
git add chisel/src/main/scala/linxcore chisel/src/test/scala/linxcore \
  docs/fpga/zybo-z7-20-architectural-gap-ledger.md
git commit -m "chisel: add Linux timer and precise fault gate"
```

## Task 9: Synthesizable Zybo Top and Emission Flow

**Files:**
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/LinxCoreZyboTop.scala`
- Create: `chisel/src/main/scala/linxcore/fpga/zybo/EmitLinxCoreZyboTop.scala`
- Create: `chisel/src/test/scala/linxcore/fpga/zybo/LinxCoreZyboTopSpec.scala`
- Create: `tools/chisel/emit_zybo_verilog.sh`
- Create: `tools/chisel/run_chisel_zybo_verilator_lint.sh`

**Interfaces:**
- Consumes: generated compact profile, AXI-Lite control, AXI4 DDR, reset, optional GPIO inputs, and UART RX mailbox.
- Produces: synthesizable top-level ports, status LEDs, PL interrupt, commit trace, and generated SystemVerilog.

- [ ] **Step 1: Write a failing top-shape test**

Require emitted ports for `s_axi_ctrl_*`, `m_axi_mem_*`, `pl_irq`, LEDs,
buttons, switches, and active-low reset. Reject external injected fetch-window
or load-data ports.

- [ ] **Step 2: Verify RED**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreZyboTopSpec
```

- [ ] **Step 3: Implement the top and deterministic emitter**

Compose the control block, reset sequencer, compact core, native router, AXI
master, UART mailbox, timer, and trace. The emitter writes only under
`generated/chisel-verilog/zybo-z7-20/` and records the manifest checksum.

- [ ] **Step 4: Verify GREEN, emission, and lint**

```bash
bash tools/chisel/run_chisel_tests.sh --only LinxCoreZyboTopSpec
bash tools/chisel/emit_zybo_verilog.sh --profile linux-nommu
bash tools/chisel/run_chisel_zybo_verilator_lint.sh
```

- [ ] **Step 5: Commit**

```bash
git add chisel/src/main/scala/linxcore/fpga/zybo \
  chisel/src/test/scala/linxcore/fpga/zybo \
  tools/chisel/emit_zybo_verilog.sh \
  tools/chisel/run_chisel_zybo_verilator_lint.sh
git commit -m "chisel: add synthesizable Zybo Linx top"
```

## Task 10: Deterministic Vivado PS/PL Flow

**Files:**
- Create: `tools/fpga/zybo_z7_20/vivado/preflight.tcl`
- Create: `tools/fpga/zybo_z7_20/vivado/create_project.tcl`
- Create: `tools/fpga/zybo_z7_20/vivado/create_block_design.tcl`
- Create: `tools/fpga/zybo_z7_20/vivado/build_bitstream.tcl`
- Create: `tools/fpga/zybo_z7_20/vivado/report_gate.tcl`
- Create: `tools/fpga/zybo_z7_20/vivado/zybo_z7_20.xdc`
- Create: `tools/fpga/zybo_z7_20/build_vivado.sh`
- Create: `tools/fpga/zybo_z7_20/tests/test_vivado_contract.py`

**Interfaces:**
- Consumes: generated SystemVerilog, constants Tcl, Digilent board part, and profile.
- Produces: XPR, block design, XSA, bitstream, utilization/timing/DRC reports, provenance JSON, and bitstream SHA256.

- [ ] **Step 1: Write failing Tcl contract tests**

Require exact part, exact 50 MHz FCLK, GP0, HP0 64-bit data, processor-system
reset, non-overlapping address segments, no swallowed `catch`, and report
generation. Run:

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_vivado_contract -v
```

Expected: failure because Vivado Tcl files do not exist.

- [ ] **Step 2: Implement preflight and project creation**

Preflight checks Vivado version, `xc7z020clg400-1`, the Zybo Z7-20 board part,
generated RTL, and writable output directory. Missing board files exit with a
command that installs Digilent `vivado-boards`; PS preset failure terminates
the run.

- [ ] **Step 3: Implement the block design**

Instantiate PS7, GP0, HP0, FCLK0, reset, AXI interconnect, Linx RTL, GPIO, and
optional ILA. Assign the ARM control segment to `0x43c00000..0x43c0ffff`.
Connect Linx AXI to HP0 and expose fixed IO and DDR.

- [ ] **Step 4: Implement build and report gates**

```bash
bash tools/fpga/zybo_z7_20/build_vivado.sh --profile safe-50 --jobs 4
```

The command fails for timing slack below zero, DRC errors, or any resource
budget violation. It writes provenance and SHA256 only after bitstream success.

- [ ] **Step 5: Verify GREEN**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_vivado_contract -v
python3 tools/fpga/zybo_z7_20/check_framework.py --mode tools
bash tools/fpga/zybo_z7_20/build_vivado.sh --profile safe-50 --jobs 4
```

- [ ] **Step 6: Commit**

```bash
git add tools/fpga/zybo_z7_20/vivado tools/fpga/zybo_z7_20/build_vivado.sh \
  tools/fpga/zybo_z7_20/tests/test_vivado_contract.py
git commit -m "fpga: add deterministic Zybo Vivado flow"
```

## Task 11: PS Monitor, Image Manifest, and Boot Packaging

**Files:**
- Create: `tools/fpga/zybo_z7_20/boot/ps_monitor/src/main.c`
- Create: `tools/fpga/zybo_z7_20/boot/ps_monitor/src/linx_loader.c`
- Create: `tools/fpga/zybo_z7_20/boot/ps_monitor/src/linx_control.c`
- Create: `tools/fpga/zybo_z7_20/boot/ps_monitor/include/linx_loader.h`
- Create: `tools/fpga/zybo_z7_20/boot/ps_monitor/lscript.ld`
- Create: `tools/fpga/zybo_z7_20/boot/create_boot_manifest.py`
- Create: `tools/fpga/zybo_z7_20/boot/package_boot_bin.tcl`
- Create: `tools/fpga/zybo_z7_20/tests/test_boot_manifest.py`

**Interfaces:**
- Consumes: ELF or binary payload, DTB, initramfs, bitstream/XSA, and generated `platform.h`.
- Produces: validated DDR image, cache-clean start, UART relay, terminal report, and microSD `BOOT.BIN` package.

- [ ] **Step 1: Write failing overlap and CRC tests**

```python
def test_linux_image_layout_is_disjoint(self):
    manifest = build_manifest(FIXTURES)
    self.assertEqual(validate_layout(manifest), [])

def test_corrupt_payload_is_rejected(self):
    manifest = build_manifest(FIXTURES)
    self.assertFalse(verify_crc(manifest, CORRUPT_KERNEL))
```

- [ ] **Step 2: Verify RED**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_boot_manifest -v
```

- [ ] **Step 3: Implement monitor loading and coherency**

The monitor validates all ranges and CRC32, asserts Linx reset, copies images,
cleans the Linx DDR arena, programs PC/SP/R2/R3, clears terminal state, starts
Linx, relays UART, and captures fault/exit/trace state. Timeout is based on a
PS timer and prints the last platform status before halt.

- [ ] **Step 4: Implement microSD packaging**

Package FSBL, bitstream, and monitor in that order. Store kernel, DTB,
initramfs, and their JSON manifest as FAT files loaded by the monitor.

- [ ] **Step 5: Verify GREEN**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_boot_manifest -v
python3 tools/fpga/zybo_z7_20/boot/create_boot_manifest.py --profile linux_nommu --check
```

- [ ] **Step 6: Commit**

```bash
git add tools/fpga/zybo_z7_20/boot tools/fpga/zybo_z7_20/tests/test_boot_manifest.py
git commit -m "boot: add Zybo PS monitor and image contract"
```

## Task 12: Board Smoke Runner and Evidence Capture

**Files:**
- Create: `tools/fpga/zybo_z7_20/run_hardware_smoke.py`
- Create: `tools/fpga/zybo_z7_20/program_board.tcl`
- Create: `tools/fpga/zybo_z7_20/tests/test_smoke_parser.py`
- Create: `docs/fpga/zybo-z7-20-linux-bringup.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: bitstream, PS monitor, payload manifest, hardware target, and serial port.
- Produces: `run.json`, `uart.log`, `vivado.log`, terminal classification, platform counters, and trace tail.

- [ ] **Step 1: Write failing transcript-classification tests**

Cover `pass`, `test-fail`, `trap`, `axi-fault`, `timeout-progress`,
`timeout-no-progress`, `program-fail`, and `console-missing`.

- [ ] **Step 2: Verify RED**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_smoke_parser -v
```

- [ ] **Step 3: Implement program, console, and evidence flow**

Resolve exactly one `xc7z020_1`, program it, start the PS monitor, capture the
COM port, and stop on a classified terminal record. Write evidence under
`out/fpga/zybo_z7_20/<UTC-run-id>/` and include Git, manifest, bitstream, board,
Vivado, and serial provenance.

- [ ] **Step 4: Run staged hardware smoke**

```bash
python3 tools/fpga/zybo_z7_20/run_hardware_smoke.py --test uart
python3 tools/fpga/zybo_z7_20/run_hardware_smoke.py --test ddr
python3 tools/fpga/zybo_z7_20/run_hardware_smoke.py --test control-flow
python3 tools/fpga/zybo_z7_20/run_hardware_smoke.py --test finisher
```

- [ ] **Step 5: Verify GREEN and repeatability**

Run each smoke three times without reusing prior terminal output. Require a
fresh FPGA program timestamp and matching build ID on every run.

- [ ] **Step 6: Commit**

```bash
git add tools/fpga/zybo_z7_20 docs/fpga/zybo-z7-20-linux-bringup.md .gitignore
git commit -m "fpga: add Zybo hardware smoke evidence flow"
```

## Task 13: NOMMU Linux DTB and Direct Boot

**Files:**
- Create: `tools/fpga/zybo_z7_20/boot/dts/linx-zybo-z7-20.dts`
- Create: `tools/fpga/zybo_z7_20/build_linux_image.sh`
- Create: `tools/fpga/zybo_z7_20/run_linux_boot.py`
- Create: `tools/fpga/zybo_z7_20/tests/test_linux_boot_parser.py`
- Modify in superproject: `kernel/linux/arch/linx/kernel/reboot.c`
- Modify in superproject only when required by DT binding: `kernel/linux/drivers/tty/serial/linx_virt_uart.c`
- Modify in superproject: `docs/bringup/PROGRESS.md`

**Interfaces:**
- Consumes: current Linx NOMMU defconfig, vmlinux, BusyBox initramfs, generated DTSI, and PS boot monitor.
- Produces: kernel image, DTB, initramfs, boot manifest, UART transcript, and structured Linux boot report.

- [ ] **Step 1: Write failing DT and transcript tests**

The DT test requires one CPU, `clock-frequency = <50000000>`, the 256 MiB
memory node, UART at `0x10000000`, `/chosen` console and initramfs bounds, and
no allocatable MMIO hole. The parser requires ordered markers for `_start`,
DTB magic, memory initialization, timer IRQ progress, userspace entry, shell
commands, and controlled poweroff.

- [ ] **Step 2: Verify RED**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_linux_boot_parser -v
```

- [ ] **Step 3: Build the DTB and Linux image**

```bash
bash tools/fpga/zybo_z7_20/build_linux_image.sh --profile linux-nommu
```

The build starts from `linxisa_virt_defconfig`, embeds or stages the BusyBox
initramfs, emits a DTB, validates load ranges, and writes a boot manifest.

- [ ] **Step 4: Align controlled poweroff**

Update the superproject kernel so the FPGA profile's controlled poweroff is
explicit and covered by QEMU plus FPGA tests. Preserve `0x10000004` QEMU
compatibility and the separate `0x10009000` FPGA test finisher.

- [ ] **Step 5: Run the Linux ladder**

```bash
python3 tools/fpga/zybo_z7_20/run_linux_boot.py --stop-after early-uart
python3 tools/fpga/zybo_z7_20/run_linux_boot.py --stop-after timer
python3 tools/fpga/zybo_z7_20/run_linux_boot.py --stop-after userspace
python3 tools/fpga/zybo_z7_20/run_linux_boot.py --commands "uname -a;/bin/cat /proc/cpuinfo;/bin/cat /proc/meminfo;poweroff"
```

- [ ] **Step 6: Verify GREEN and ten cold boots**

Require ten independent cold starts with valid build IDs, `_start` markers,
timer progress, BusyBox shell output, and controlled poweroff. Archive only
the summarized report or documented external artifact URI in Git.

- [ ] **Step 7: Commit LinxCore and superproject changes separately**

```bash
git add tools/fpga/zybo_z7_20
git commit -m "linux: add Zybo NOMMU direct boot flow"
```

In the superproject, commit the kernel/bring-up changes and update the LinxCore
submodule pin only after LinxCore is pushed and all leaf gates are green.

## Task 14: Resource Closure and Board Feature Expansion

**Files:**
- Create: `tools/fpga/zybo_z7_20/check_vivado_reports.py`
- Create: `tools/fpga/zybo_z7_20/tests/test_vivado_report_parser.py`
- Create: `docs/fpga/zybo-z7-20-resource-ledger.md`
- Extend: `tools/fpga/zybo_z7_20/platform.json`

**Interfaces:**
- Consumes: utilization, timing, DRC, power, XADC, hardware smoke, and profile data.
- Produces: pass/fail resource ledger and selected clock/profile recommendation.

- [ ] **Step 1: Write failing report-parser tests**

Fixtures must cover resource pass, each independent resource violation,
negative setup slack, negative hold slack, DRC error, missing report, and
manifest/build-ID mismatch.

- [ ] **Step 2: Verify RED**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_vivado_report_parser -v
```

- [ ] **Step 3: Implement report gates and optimize in this order**

1. Remove unowned compatibility structures from the live top.
2. Reduce queue depth only when block/LSID/recovery tests remain green.
3. Infer BRAM for arrays with synchronous-read semantics.
4. Reduce debug replicas in release builds.
5. Pipeline AXI and high-fanout control paths without changing architectural latency.
6. Try 75 MHz only after 50 MHz Linux repeatability passes.
7. Try 100 MHz only after the 75 MHz profile passes all earlier gates.

- [ ] **Step 4: Add board features as independent profiles**

Add LED/button/switch status first, XADC telemetry second, Pmod trace third,
PS-backed Ethernet/USB services fourth, and HDMI/Pcam/audio bandwidth tests
last. Every profile retains the same Linx CPU and boot contract and has a
separate utilization delta.

- [ ] **Step 5: Verify GREEN**

```bash
python3 -m unittest tools.fpga.zybo_z7_20.tests.test_vivado_report_parser -v
python3 tools/fpga/zybo_z7_20/check_vivado_reports.py --run-dir out/fpga/zybo_z7_20/latest
```

- [ ] **Step 6: Commit**

```bash
git add tools/fpga/zybo_z7_20 docs/fpga/zybo-z7-20-resource-ledger.md
git commit -m "fpga: gate Zybo resources and feature profiles"
```

## Task 15: Final Verification, Upstream Push, and Superproject Pin

**Files:**
- Modify: `docs/fpga/zybo-z7-20-linux-bringup.md`
- Modify in superproject: `docs/bringup/PROGRESS.md`
- Modify in superproject: `rtl/LinxCore` gitlink

**Interfaces:**
- Consumes: all prior gate output.
- Produces: a pushed LinxCore branch, review-ready PR, and superproject pin update with leaf evidence.

- [ ] **Step 1: Run the complete fresh source and RTL gate**

```bash
python3 tools/fpga/zybo_z7_20/check_framework.py --mode all
bash tools/chisel/run_chisel_tests.sh --all
bash tools/chisel/run_chisel_zybo_verilator_lint.sh
```

- [ ] **Step 2: Run the complete fresh FPGA gate**

```bash
bash tools/fpga/zybo_z7_20/build_vivado.sh --profile safe-50 --jobs 4
python3 tools/fpga/zybo_z7_20/check_vivado_reports.py --run-dir out/fpga/zybo_z7_20/latest
python3 tools/fpga/zybo_z7_20/run_hardware_smoke.py --suite release
python3 tools/fpga/zybo_z7_20/run_linux_boot.py --suite release --cold-boots 10
```

- [ ] **Step 3: Audit requirements line by line**

Check every global constraint and design acceptance criterion against a fresh
command or artifact. Record an explicit blocked status for any unmet gate; do
not replace missing evidence with inference.

- [ ] **Step 4: Push LinxCore and create a review request**

```bash
git status --short
git log --oneline --decorate origin/main..HEAD
git push -u origin codex/zybo-linux-framework
```

Open a draft PR whose body lists resource/timing results, hardware identifiers,
Linux transcript location, known limitations, and the superproject pin plan.

- [ ] **Step 5: Update and verify the superproject pin**

After the LinxCore branch is merged or its review commit is approved, update
the `rtl/LinxCore` gitlink in the superproject. Run the superproject
source-contract, Chisel leaf, FPGA contract, QEMU, and Linux regression gates
before pushing the pin update.

## Plan Self-Review

- Spec coverage: platform contract, repository skill, compact Chisel profiles,
  real memory ownership, AXI, boot registers, timer/traps, Vivado, PS monitor,
  hardware evidence, Linux, resource closure, and superproject pinning each
  have an independently testable task.
- Type consistency: all platform consumers use `LinxMemRequest`,
  `LinxMemResponse`, `LinxMemFault`, `ZyboZ720PlatformParams`, and the generated
  manifest constants named in Tasks 1, 3, and 4.
- Safety: MMIO-first decode, dual exit compatibility, cache maintenance,
  precise AXI faults, committed-store completion, and Linux boot registers are
  explicit gates.
- Scope: this plan ends at NOMMU BusyBox and board-profile expansion. MMU/PTW,
  SMP, and direct Linx device ownership require a subsequent design and plan.
