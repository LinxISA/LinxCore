#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "src" / "bcc" / "backend" / "backend.py"
TOP = ROOT / "src" / "top" / "top.py"
TOP_EXPORT_CORE = ROOT / "src" / "top" / "modules" / "export_core.py"
BACKEND_TRACE_EXPORT_CORE = ROOT / "src" / "bcc" / "backend" / "modules" / "trace_export_core.py"
LEGACY_ENGINE = ROOT / "src" / "bcc" / "backend" / "engine.py"
LEGACY_SHIM = ROOT / "src" / "bcc" / "backend" / "JanusBCCBackend.py"
BACKEND_DIR = ROOT / "src" / "bcc" / "backend"
TOP_DIR = ROOT / "src" / "top"


def _line_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8", errors="replace").splitlines())


def main() -> int:
    if LEGACY_ENGINE.exists():
        raise SystemExit(f"backend composition lint failed: legacy engine file must be removed: {LEGACY_ENGINE}")
    if LEGACY_SHIM.exists():
        raise SystemExit(f"backend composition lint failed: legacy shim file must be removed: {LEGACY_SHIM}")

    if not BACKEND.is_file():
        raise SystemExit(f"backend composition lint failed: missing backend file: {BACKEND}")
    if not TOP.is_file():
        raise SystemExit(f"backend composition lint failed: missing top file: {TOP}")

    forbidden_cluster = []
    for scan_dir in (BACKEND_DIR, TOP_DIR):
        if not scan_dir.is_dir():
            continue
        for path in scan_dir.rglob("*.py"):
            if "__pycache__" in path.parts:
                continue
            if "cluster" in path.name.lower():
                forbidden_cluster.append(path)
    if forbidden_cluster:
        joined = ", ".join(str(p) for p in sorted(forbidden_cluster))
        raise SystemExit(
            "backend composition lint failed: `cluster` naming is forbidden in top/backend module files: "
            + joined
        )

    backend_text = BACKEND.read_text(encoding="utf-8", errors="replace")
    if "BACKEND_COMPOSITION_ROOT" not in backend_text:
        raise SystemExit(
            "backend composition lint failed: backend.py must contain marker BACKEND_COMPOSITION_ROOT"
        )

    backend_lines = _line_count(BACKEND)
    top_lines = _line_count(TOP)
    top_export_core_lines = _line_count(TOP_EXPORT_CORE) if TOP_EXPORT_CORE.is_file() else 0
    backend_trace_export_core_lines = _line_count(BACKEND_TRACE_EXPORT_CORE) if BACKEND_TRACE_EXPORT_CORE.is_file() else 0

    if backend_lines > 300:
        raise SystemExit(
            f"backend composition lint failed: {BACKEND} has {backend_lines} lines (limit 300)"
        )
    if top_lines > 250:
        raise SystemExit(
            f"backend composition lint failed: {TOP} has {top_lines} lines (limit 250)"
        )
    if TOP_EXPORT_CORE.is_file() and top_export_core_lines > 3000:
        raise SystemExit(
            "backend composition lint failed: "
            f"{TOP_EXPORT_CORE} has {top_export_core_lines} lines (limit 3000)"
        )
    if BACKEND_TRACE_EXPORT_CORE.is_file() and backend_trace_export_core_lines > 3000:
        raise SystemExit(
            "backend composition lint failed: "
            f"{BACKEND_TRACE_EXPORT_CORE} has {backend_trace_export_core_lines} lines (limit 3000)"
        )

    print(
        "backend composition lint passed "
        f"(backend_lines={backend_lines}, top_lines={top_lines}, "
        f"top_export_core_lines={top_export_core_lines}, "
        f"backend_trace_export_core_lines={backend_trace_export_core_lines})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
