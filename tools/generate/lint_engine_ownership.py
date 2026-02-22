#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "src" / "bcc" / "backend" / "engine.py"


def main() -> int:
    if not ENGINE.is_file():
        raise SystemExit(f"missing engine file: {ENGINE}")

    text = ENGINE.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    # Migration guardrail: keep the compatibility engine from becoming even
    # larger while logic is moved into focused backend files.
    max_lines = 3200
    if len(lines) > max_lines:
        raise SystemExit(
            f"engine ownership lint failed: {ENGINE} has {len(lines)} lines, "
            f"limit is {max_lines}. Move stage/component logic into focused files."
        )

    # Require explicit orchestration marker so reviewers can quickly identify
    # ownership intent.
    marker = "ENGINE_ORCHESTRATION_ONLY"
    if marker not in text:
        raise SystemExit(
            f"engine ownership lint failed: missing marker '{marker}' in {ENGINE}. "
            "Add the marker near module entry and keep ownership in stage files."
        )

    print("engine ownership lint passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

