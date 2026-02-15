#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC_ROOT = ROOT / "src"

sys.path.insert(0, str(SRC_ROOT))
from common.stage_tokens import INTERFACE_PREFIXES, STAGE_TOKENS  # noqa: E402

NAME_RE = re.compile(r"m\.(?:input|output|out)\(\"([^\"]+)\"")

EXEMPT = {"clk", "rst"}


def is_valid_name(name: str) -> bool:
    if name in EXEMPT:
        return True
    for prefix in INTERFACE_PREFIXES:
        if name.startswith(prefix + "_"):
            return True
    # Non-interface locals/debug ports are allowed; this lint focuses on
    # producer_to_consumer stage contracts.
    if "_stage" not in name and "_to_" not in name:
        return True
    suffix = name.split("_")[-1]
    return suffix in STAGE_TOKENS


def iter_source_files() -> list[Path]:
    files: list[Path] = []
    for path in SRC_ROOT.rglob("*.py"):
        if path.name == "__init__.py":
            continue
        if "common" in path.parts:
            continue
        files.append(path)
    return sorted(files)


def main() -> int:
    errors: list[str] = []
    for path in iter_source_files():
        text = path.read_text(encoding="utf-8")
        for ln, line in enumerate(text.splitlines(), start=1):
            for match in NAME_RE.finditer(line):
                name = match.group(1)
                if not is_valid_name(name):
                    errors.append(f"{path}:{ln}: invalid stage/interface signal name: {name}")

    if errors:
        print("stage naming lint failed:", file=sys.stderr)
        for e in errors:
            print(e, file=sys.stderr)
        return 1

    print("stage naming lint passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
