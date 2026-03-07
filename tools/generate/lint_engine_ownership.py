#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "src" / "bcc" / "backend" / "engine.py"


def main() -> int:
    # Deprecated after hard backend decomposition cutover. Keep this script so
    # old CI hooks do not fail unexpectedly, but enforce that engine.py is gone.
    if ENGINE.exists():
        raise SystemExit(f"engine ownership lint failed: legacy engine file must be removed: {ENGINE}")
    print("engine ownership lint deprecated: legacy engine path removed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
