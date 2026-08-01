#!/usr/bin/env python3
"""Fail-fast preflight checks for the Zybo Z7-20 platform framework."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.dont_write_bytecode = True

if __package__:
    from .generate_platform import (
        DEFAULT_MANIFEST,
        generated_file_differences,
        load_manifest,
        validate_manifest,
    )
else:
    from generate_platform import (
        DEFAULT_MANIFEST,
        generated_file_differences,
        load_manifest,
        validate_manifest,
    )


def _tool_errors() -> list[str]:
    errors: list[str] = []
    if sys.version_info < (3, 10):
        errors.append("Python 3.10 or newer is required")
    for script_name in ("generate_platform.py", "check_framework.py"):
        try:
            script_path = Path(__file__).with_name(script_name)
            compile(script_path.read_text(encoding="utf-8"), str(script_path), "exec")
        except (OSError, SyntaxError) as error:
            errors.append(f"cannot compile {script_name}: {error}")
    return errors


def _generated_errors(data: dict) -> list[str]:
    return [f"generated platform file is stale: {path}" for path in generated_file_differences(data)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("source", "tools", "generated", "all"), required=True)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    arguments = parser.parse_args(argv)

    errors: list[str] = []
    data: dict | None = None
    if arguments.mode in ("source", "all", "generated"):
        try:
            data = load_manifest(arguments.manifest)
            errors.extend(validate_manifest(data))
        except (OSError, ValueError) as error:
            errors.append(f"platform manifest error: {error}")
    if arguments.mode in ("tools", "all"):
        errors.extend(_tool_errors())
    if arguments.mode in ("generated", "all") and data is not None and not errors:
        errors.extend(_generated_errors(data))

    if errors:
        print("framework preflight failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print(f"framework preflight passed ({arguments.mode})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
