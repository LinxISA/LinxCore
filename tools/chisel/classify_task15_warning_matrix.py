#!/usr/bin/env python3
"""Classify the bounded Task-15 W2/W4/W6/W8 elaboration warning matrix."""

from __future__ import annotations

import re
import sys
from pathlib import Path


WIDTHS = ("W2", "W4", "W6", "W8")
# No warning is accepted by default. Add an entry only for a narrowly scoped,
# reviewed warning whose exact code is unavoidable and documented.
ALLOWLIST_CODES: set[str] = set()
MARKER = re.compile(r"TASK15_WARNING_MATRIX_(BEGIN|END) (W[2468])")
WARNING = re.compile(r"\[(W\d{3})\]")


def classify(text: str) -> tuple[dict[str, list[str]], list[str]]:
    active: str | None = None
    sections = {width: [] for width in WIDTHS}
    outside: list[str] = []
    seen_begin: set[str] = set()
    seen_end: set[str] = set()

    for line in text.splitlines():
        marker = MARKER.search(line)
        if marker:
            action, width = marker.groups()
            if action == "BEGIN":
                if active is not None:
                    raise ValueError(f"nested matrix marker: {line}")
                active = width
                seen_begin.add(width)
            else:
                if active != width:
                    raise ValueError(f"unmatched matrix marker: {line}")
                seen_end.add(width)
                active = None
            continue
        if WARNING.search(line):
            (sections[active] if active else outside).append(line)

    missing = [width for width in WIDTHS if width not in seen_begin or width not in seen_end]
    if missing:
        raise ValueError("missing completed matrix sections: " + ", ".join(missing))
    if active is not None:
        raise ValueError(f"unterminated matrix section: {active}")
    return sections, outside


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {Path(sys.argv[0]).name} <test-log>")
    try:
        sections, outside = classify(Path(sys.argv[1]).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    failed = False
    for width in WIDTHS:
        codes = [WARNING.search(line).group(1) for line in sections[width]]
        allowed = [code for code in codes if code in ALLOWLIST_CODES]
        rejected = [code for code in codes if code not in ALLOWLIST_CODES]
        status = "fail" if rejected else "pass"
        failed |= bool(rejected)
        print(
            f"task15-warning-matrix width={width} status={status} "
            f"rejected={','.join(rejected) or 'none'} "
            f"allowed={','.join(allowed) or 'none'}"
        )
    outside_codes = [WARNING.search(line).group(1) for line in outside]
    print(f"task15-warning-matrix outside={','.join(outside_codes) or 'none'}")
    failed |= any(code not in ALLOWLIST_CODES for code in outside_codes)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
