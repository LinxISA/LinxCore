#!/usr/bin/env node
"use strict";

/**
 * Konata internals diagnostic for LinxCore v0005 traces.
 *
 * Uses Konata's real parser/runtime objects to report:
 * - parsed op counts
 * - renderable rows (at least one stage with end > start)
 * - stage-level-map consistency (lane/stage pair must exist)
 * - first rows renderability summary (helps explain blank right pane)
 */

const path = require("path");

const KONATA_ROOT = process.env.KONATA_ROOT || "/Users/zhoubot/Konata";
const { OnikiriParser } = require(path.join(KONATA_ROOT, "onikiri_parser.js"));
const { FileReader } = require(path.join(KONATA_ROOT, "file_reader.js"));

function parseArgs(argv) {
  let trace = "";
  let top = 20;
  let json = false;
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--top" && i + 1 < argv.length) {
      top = Number(argv[++i] || "20");
      continue;
    }
    if (a === "--json") {
      json = true;
      continue;
    }
    if (!trace) {
      trace = a;
      continue;
    }
    throw new Error(`unknown argument: ${a}`);
  }
  if (!trace) {
    throw new Error("usage: konata_internal_diag.js <trace.konata> [--top N] [--json]");
  }
  if (!Number.isFinite(top) || top <= 0) {
    throw new Error(`invalid --top value: ${top}`);
  }
  return { trace, top: Math.floor(top), json };
}

function summarize(parser, top) {
  const lastID = parser.lastID;
  const stageMap = parser.stageLevelMap;
  const rows = [];
  const stageHist = new Map();
  const laneHist = new Map();

  let total = 0;
  let retired = 0;
  let flushed = 0;
  let blocks = 0;
  let renderable = 0;
  let nonRenderable = 0;
  let missingStageMapRows = 0;
  let badDurationRows = 0;

  for (let id = 0; id <= lastID; id++) {
    const op = parser.getOp(id);
    if (!op) {
      continue;
    }
    total++;
    if (op.retired) retired++;
    if (op.flush) flushed++;
    if (String(op.kind || "").toLowerCase() === "block") blocks++;

    let drawableStages = 0;
    let totalStages = 0;
    let missingMap = 0;
    let nonPositive = 0;
    const stagesSet = new Set();
    const laneSet = new Set();

    for (const laneName of Object.keys(op.lanes || {})) {
      laneSet.add(laneName);
      laneHist.set(laneName, (laneHist.get(laneName) || 0) + 1);
      const lane = op.lanes[laneName];
      const sarr = lane && lane.stages ? lane.stages : [];
      for (const st of sarr) {
        totalStages++;
        stagesSet.add(st.name);
        stageHist.set(st.name, (stageHist.get(st.name) || 0) + 1);
        if (!stageMap.has(laneName, st.name)) {
          missingMap++;
        }
        const end = st.endCycle === 0 ? op.retiredCycle : st.endCycle;
        if (end > st.startCycle) {
          drawableStages++;
        } else {
          nonPositive++;
        }
      }
    }

    const isRenderable = drawableStages > 0;
    if (isRenderable) renderable++;
    else nonRenderable++;
    if (missingMap > 0) missingStageMapRows++;
    if (nonPositive > 0) badDurationRows++;

    rows.push({
      id,
      kind: String(op.kind || "normal"),
      uid: String(op.uidHex || ""),
      rid: op.rid,
      retired: !!op.retired,
      flush: !!op.flush,
      label: String(op.labelName || ""),
      stages: Array.from(stagesSet).sort(),
      lanes: Array.from(laneSet).sort(),
      totalStages,
      drawableStages,
      missingMap,
      nonPositive,
      renderable: isRenderable,
    });
  }

  rows.sort((a, b) => a.id - b.id);
  const firstRows = rows.slice(0, top);
  const firstNonBlockRenderable = rows.find((r) => r.kind !== "block" && r.renderable) || null;

  const topStages = Array.from(stageHist.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, top)
    .map(([k, v]) => ({ stage: k, count: v }));
  const topLanes = Array.from(laneHist.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, top)
    .map(([k, v]) => ({ lane: k, count: v }));

  return {
    total,
    retired,
    flushed,
    blocks,
    renderable,
    nonRenderable,
    missingStageMapRows,
    badDurationRows,
    uniqueStages: stageHist.size,
    uniqueLanes: laneHist.size,
    firstRows,
    firstNonBlockRenderable,
    topStages,
    topLanes,
  };
}

function run(trace, top, jsonOut) {
  return new Promise((resolve, reject) => {
    const parser = new OnikiriParser();
    const fr = new FileReader();
    fr.open(trace);
    const savedLog = console.log;
    if (jsonOut) {
      console.log = () => {};
    }
    const restoreLog = () => {
      console.log = savedLog;
    };
    const restoreLogDeferred = () => {
      setImmediate(() => {
        restoreLog();
      });
    };
    parser.setFile(
      fr,
      () => {},
      () => {
        try {
          const s = summarize(parser, top);
          if (jsonOut) {
            process.stdout.write(JSON.stringify(s, null, 2) + "\n");
            restoreLogDeferred();
          } else {
            restoreLog();
            console.log(`trace=${trace}`);
            console.log(
              `ops=${s.total} retired=${s.retired} flushed=${s.flushed} blocks=${s.blocks} ` +
                `renderable=${s.renderable} non_renderable=${s.nonRenderable}`
            );
            console.log(
              `unique_stages=${s.uniqueStages} unique_lanes=${s.uniqueLanes} ` +
                `missing_stage_map_rows=${s.missingStageMapRows} bad_duration_rows=${s.badDurationRows}`
            );
            if (s.firstNonBlockRenderable) {
              console.log(
                `first_non_block_renderable=id:${s.firstNonBlockRenderable.id} ` +
                  `label=${s.firstNonBlockRenderable.label}`
              );
            } else {
              console.log("first_non_block_renderable=none");
            }
            console.log("top_stages:");
            for (const e of s.topStages) {
              console.log(`  ${e.stage}: ${e.count}`);
            }
            console.log("top_lanes:");
            for (const e of s.topLanes) {
              console.log(`  ${e.lane}: ${e.count}`);
            }
            console.log("first_rows:");
            for (const r of s.firstRows) {
              console.log(
                `  id=${r.id} kind=${r.kind} renderable=${Number(r.renderable)} ` +
                  `drawable=${r.drawableStages}/${r.totalStages} missing_map=${r.missingMap} ` +
                  `bad_dur=${r.nonPositive} label=${r.label}`
              );
            }
          }
          resolve(0);
        } catch (e) {
          restoreLog();
          reject(e);
        }
      },
      (_parseErr, e) => {
        restoreLog();
        reject(e || new Error("parse failed"));
      }
    );
  });
}

async function main() {
  let args;
  try {
    args = parseArgs(process.argv);
  } catch (e) {
    console.error(String(e.message || e));
    process.exit(2);
  }

  try {
    await run(args.trace, args.top, args.json);
  } catch (e) {
    console.error(`konata-internal-diag error: ${String(e && e.message ? e.message : e)}`);
    process.exit(1);
  }
}

main();
