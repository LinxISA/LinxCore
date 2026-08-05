# LinxCore Core Architecture Diagram Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an editable, source-grounded LinxCore core architecture diagram and a PNG first-review preview.

**Architecture:** Model the canonical/full pyCircuit `LinxCoreTop` as a grouped directed graph, lay it out with the Agents365 Graphviz generator, and add the reduced Chisel `TOP` as an explicitly non-canonical inset group. Structural validation runs before rendering; visual inspection is performed on a non-embedded PNG preview.

**Tech Stack:** draw.io XML, Agents365 `drawio-skill` 2.1.0, Python 3, Graphviz 14.1.2, draw.io Desktop CLI.

## Global Constraints

- The canonical/full pyCircuit topology is the main view.
- The Chisel `TOP` inset must be labelled `Reduced typed composition lane (not the full core)`.
- Primary data and command flow is solid; recovery, replay, flush, and redirect flow is red and dashed.
- Bring-up or staged-integration ownership is visually distinguished from promoted ownership.
- Diagram labels remain in English to match source and architecture documentation.
- Preserve all pre-existing uncommitted work in the LinxCore checkout.

---

### Task 1: Encode the source-grounded architecture graph

**Files:**
- Create: `docs/architecture/diagrams/linxcore-core-architecture.graph.json`

**Interfaces:**
- Consumes: `docs/architecture/linxcore_top_design.md`, `docs/architecture/overview.md`, `docs/architecture/module-catalog.md`, `src/top/top.py`, and `chisel/src/main/scala/linxcore/top/TOP.scala`.
- Produces: Agents365 autolayout JSON with `direction`, `nodes`, and `edges` fields.

- [ ] **Step 1: Create the diagram directory and graph source**

Use `apply_patch` to add one JSON object with `direction: "LR"`, grouped nodes for Frontend, Decode/OOO, Commit/Recovery, LSU/Memory, Block Fabric/Engines, Observability, and the Chisel inset. Include solid primary-flow edges and labels prefixed `RECOVERY:` for recovery/flush paths so they can be restyled after generation.

- [ ] **Step 2: Validate the graph-source syntax**

Run:

```bash
python3 -m json.tool docs/architecture/diagrams/linxcore-core-architecture.graph.json >/dev/null
```

Expected: exit status 0 with no output.

- [ ] **Step 3: Cross-check required ownership labels**

Run:

```bash
rg -n 'I-F0|B-F4|Instruction Buffer|D3 / Rename|ROB|BISQ|BCTRL|BROB|LIQ|STQ|MDB|SCB|VEC|CUBE|TMA|TAU|Reduced typed composition lane' docs/architecture/diagrams/linxcore-core-architecture.graph.json
```

Expected: every listed ownership label appears at least once.

### Task 2: Generate and structurally validate the editable draw.io file

**Files:**
- Create: `docs/architecture/diagrams/linxcore-core-architecture.drawio`
- Modify: `docs/architecture/diagrams/linxcore-core-architecture.drawio` only if recovery-edge styling or inset annotation requires a focused XML correction.

**Interfaces:**
- Consumes: `linxcore-core-architecture.graph.json`.
- Produces: uncompressed native draw.io XML.

- [ ] **Step 1: Generate the graph with Graphviz auto-layout**

Run:

```bash
python3 /Users/zhoubot/.codex/skills/drawio-skill/scripts/autolayout.py \
  docs/architecture/diagrams/linxcore-core-architecture.graph.json \
  --tune -o docs/architecture/diagrams/linxcore-core-architecture.drawio
```

Expected: stderr reports `wrote ...drawio` with non-zero node and edge counts.

- [ ] **Step 2: Apply architecture-specific edge styling**

Use `apply_patch` for a focused XML edit: edges whose generated labels begin with `RECOVERY:` receive `dashed=1;strokeColor=#b85450;` and the visible `RECOVERY:` prefix is removed. Do not alter node coordinates produced by Graphviz.

- [ ] **Step 3: Run strict structural validation and readability scoring**

Run:

```bash
python3 /Users/zhoubot/.codex/skills/drawio-skill/scripts/validate.py \
  --strict --score docs/architecture/diagrams/linxcore-core-architecture.drawio
```

Expected: zero errors, zero warnings, exit status 0, and a numeric readability score.

- [ ] **Step 4: Confirm XML ownership and edge endpoints**

Run:

```bash
rg -n 'Reduced typed composition lane|BCTRL|BROB|source=|target=' docs/architecture/diagrams/linxcore-core-architecture.drawio
```

Expected: the inset label, block-control nodes, and generated directed edges are present.

### Task 3: Render and inspect the first-review preview

**Files:**
- Create: `docs/architecture/diagrams/linxcore-core-architecture.png`

**Interfaces:**
- Consumes: validated `linxcore-core-architecture.drawio`.
- Produces: width-capped, non-embedded PNG suitable for visual inspection.

- [ ] **Step 1: Install draw.io Desktop only if the CLI is absent**

Run:

```bash
brew install --cask drawio
```

Expected: `/Applications/draw.io.app/Contents/MacOS/draw.io` exists and prints a version. Skip this step when `drawio --version` already succeeds.

- [ ] **Step 2: Export the clean preview**

Run with the resolved draw.io binary:

```bash
drawio -x -f png --width 2000 -b 10 \
  -o docs/architecture/diagrams/linxcore-core-architecture.png \
  docs/architecture/diagrams/linxcore-core-architecture.drawio
```

Expected: a readable PNG no larger than 2000 pixels wide. Do not pass `-e` for this review preview.

- [ ] **Step 3: Inspect the PNG with image vision**

Open the PNG with the local image viewer and check: no overlapping sibling nodes, no clipped labels, no off-canvas content, all main ownership groups visible, recovery arrows visually distinct, and the Chisel inset clearly marked non-canonical.

- [ ] **Step 4: Perform at most two focused correction rounds**

For each observed defect, edit only the graph JSON or generated XML responsible for that defect, regenerate/re-export, and repeat structural plus visual inspection. Stop after two correction rounds and present the current preview.

- [ ] **Step 5: Commit the graph source, draw.io file, and preview together**

Run:

```bash
git add docs/architecture/diagrams/linxcore-core-architecture.graph.json \
  docs/architecture/diagrams/linxcore-core-architecture.drawio \
  docs/architecture/diagrams/linxcore-core-architecture.png
git commit -m "docs: add LinxCore core architecture diagram"
```

Expected: one commit containing only the three diagram artifacts.

### Task 4: Produce the editable embedded PNG after user approval

**Files:**
- Create: `docs/architecture/diagrams/linxcore-core-architecture.drawio.png`

**Interfaces:**
- Consumes: user-approved `.drawio` source.
- Produces: final PNG with embedded editable draw.io XML.

- [ ] **Step 1: Export the approved embedded PNG**

Run:

```bash
drawio -x -f png -e -s 2 -b 10 \
  -o docs/architecture/diagrams/linxcore-core-architecture.drawio.png \
  docs/architecture/diagrams/linxcore-core-architecture.drawio
python3 /Users/zhoubot/.codex/skills/drawio-skill/scripts/repair_png.py \
  docs/architecture/diagrams/linxcore-core-architecture.drawio.png
```

Expected: repaired PNG ends with a valid IEND chunk and opens in draw.io with editable XML.

- [ ] **Step 2: Commit the approved final export**

Run:

```bash
git add docs/architecture/diagrams/linxcore-core-architecture.drawio.png
git commit -m "docs: export editable LinxCore architecture preview"
```

Expected: one commit containing only the final embedded PNG.

