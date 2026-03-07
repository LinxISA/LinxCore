#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any


def _to_float(v: Any) -> float:
    if isinstance(v, (int, float)):
        return float(v)
    try:
        return float(v)
    except Exception:
        return 0.0


def load_metrics(manifest_path: Path) -> dict[str, Any]:
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    sources = data.get("sources", [])
    if not isinstance(sources, list):
        sources = []

    total_cost = 0.0
    top_tu_cost = 0.0
    top_tu_path = ""
    module_costs: dict[str, float] = defaultdict(float)
    source_count = 0

    for row in sources:
        if not isinstance(row, dict):
            continue
        source_count += 1
        cost = _to_float(row.get("predicted_compile_cost", 0.0))
        total_cost += cost
        path = str(row.get("path", ""))
        if cost > top_tu_cost:
            top_tu_cost = cost
            top_tu_path = path
        mod = str(row.get("module", "")) or "<unknown>"
        module_costs[mod] += cost

    top_module = "<none>"
    top_module_cost = 0.0
    for mod, cost in module_costs.items():
        if cost > top_module_cost:
            top_module = mod
            top_module_cost = cost

    return {
        "source_count": source_count,
        "module_count": len(module_costs),
        "total_cost": total_cost,
        "top_tu_cost": top_tu_cost,
        "top_tu_path": top_tu_path,
        "top_module": top_module,
        "top_module_cost": top_module_cost,
    }


def _check_cap(
    *,
    name: str,
    value: float,
    cap: float,
    failures: list[str],
) -> None:
    if cap <= 0.0:
        return
    if value > cap:
        msg = f"{name}={value:.3f} exceeds cap={cap:.3f}"
        print(f"warn: {msg}")
        failures.append(msg)
    else:
        print(f"ok: {name}={value:.3f} within cap={cap:.3f}")


def _check_regression(
    *,
    name: str,
    current: float,
    baseline: float,
    max_regress_pct: float,
    failures: list[str],
) -> None:
    if baseline <= 0.0:
        print(f"warn: baseline {name} is non-positive ({baseline:.3f}); skipping")
        return
    delta_pct = ((current - baseline) / baseline) * 100.0
    msg = (
        f"{name} current={current:.3f} baseline={baseline:.3f} "
        f"delta={delta_pct:+.2f}%"
    )
    if delta_pct > max_regress_pct:
        print(f"warn: regression {msg}")
        failures.append(msg)
    else:
        print(f"ok: {msg}")


def main() -> int:
    ap = argparse.ArgumentParser(
        description=(
            "Check cpp_compile_manifest predicted compile-cost thresholds "
            "for TU/module hotspots."
        )
    )
    ap.add_argument("--manifest-json", required=True, help="Current cpp_compile_manifest.json")
    ap.add_argument("--baseline-json", help="Baseline cpp_compile_manifest.json")
    ap.add_argument(
        "--max-regress-pct",
        type=float,
        default=25.0,
        help="Allowed percent regression versus baseline",
    )
    ap.add_argument(
        "--max-top-tu-cost",
        type=float,
        default=0.0,
        help="Hard cap for hottest TU predicted_compile_cost (0 disables cap)",
    )
    ap.add_argument(
        "--max-top-module-cost",
        type=float,
        default=0.0,
        help="Hard cap for hottest module aggregated predicted_compile_cost (0 disables cap)",
    )
    ap.add_argument(
        "--max-total-cost",
        type=float,
        default=0.0,
        help="Hard cap for total predicted compile cost across all sources (0 disables cap)",
    )
    ap.add_argument(
        "--strict",
        action="store_true",
        help="Return non-zero when any threshold/regression check fails",
    )
    args = ap.parse_args()

    manifest_path = Path(args.manifest_json)
    if not manifest_path.is_file():
        print(f"error: missing manifest: {manifest_path}")
        return 2

    curr = load_metrics(manifest_path)
    print(
        "info: current manifest "
        f"sources={curr['source_count']} modules={curr['module_count']} "
        f"total_cost={curr['total_cost']:.3f} "
        f"top_tu_cost={curr['top_tu_cost']:.3f} "
        f"top_module_cost={curr['top_module_cost']:.3f}"
    )
    if curr["top_tu_path"]:
        print(f"info: hottest_tu={curr['top_tu_path']}")
    if curr["top_module"]:
        print(f"info: hottest_module={curr['top_module']}")

    failures: list[str] = []

    _check_cap(
        name="top_tu_cost",
        value=float(curr["top_tu_cost"]),
        cap=float(args.max_top_tu_cost),
        failures=failures,
    )
    _check_cap(
        name="top_module_cost",
        value=float(curr["top_module_cost"]),
        cap=float(args.max_top_module_cost),
        failures=failures,
    )
    _check_cap(
        name="total_cost",
        value=float(curr["total_cost"]),
        cap=float(args.max_total_cost),
        failures=failures,
    )

    if args.baseline_json:
        baseline_path = Path(args.baseline_json)
        if not baseline_path.is_file():
            print(f"warn: baseline manifest missing: {baseline_path}; skipping regression comparison")
        else:
            base = load_metrics(baseline_path)
            _check_regression(
                name="top_tu_cost",
                current=float(curr["top_tu_cost"]),
                baseline=float(base["top_tu_cost"]),
                max_regress_pct=float(args.max_regress_pct),
                failures=failures,
            )
            _check_regression(
                name="top_module_cost",
                current=float(curr["top_module_cost"]),
                baseline=float(base["top_module_cost"]),
                max_regress_pct=float(args.max_regress_pct),
                failures=failures,
            )
            _check_regression(
                name="total_cost",
                current=float(curr["total_cost"]),
                baseline=float(base["total_cost"]),
                max_regress_pct=float(args.max_regress_pct),
                failures=failures,
            )
    else:
        print("warn: no baseline manifest provided; only absolute caps were checked")

    if failures and args.strict:
        print(f"error: {len(failures)} compile-cost check(s) failed")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
