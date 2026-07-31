#!/usr/bin/env python3
"""Regenerate or verify the canonical TOP interface manifest."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHISEL = ROOT / "chisel"
JSON_TARGET = ROOT / "docs/chisel/generated/top-interface-manifest.json"
MARKDOWN_TARGET = ROOT / "docs/chisel/generated/top-interface-manifest.md"


def emit(json_path: Path, markdown_path: Path) -> None:
    command = (
        f"source {ROOT / 'tools/chisel/chisel_env.sh'} && "
        f"cd {CHISEL} && "
        "sbt --server --batch --no-colors "
        '--mem "${LINX_CHISEL_SBT_MEM_MB}" '
        f"'runMain linxcore.top.interface.EmitInterfaceManifest "
        f"--json {json_path} --markdown {markdown_path}'"
    )
    subprocess.run(["bash", "-c", command], check=True, env=os.environ.copy())


def same(left: Path, right: Path) -> bool:
    return left.is_file() and right.is_file() and left.read_bytes() == right.read_bytes()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail when checked-in generated files differ from the Bundle projection",
    )
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="linxcore-interface-manifest-") as tmp:
        temporary = Path(tmp)
        emitted_json = temporary / JSON_TARGET.name
        emitted_markdown = temporary / MARKDOWN_TARGET.name
        emit(emitted_json, emitted_markdown)

        if args.check:
            stale = []
            if not same(JSON_TARGET, emitted_json):
                stale.append(JSON_TARGET)
            if not same(MARKDOWN_TARGET, emitted_markdown):
                stale.append(MARKDOWN_TARGET)
            if stale:
                for path in stale:
                    print(f"stale generated interface manifest: {path}")
                return 1
            print("top-interface-manifest: up to date")
            return 0

        JSON_TARGET.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(emitted_json, JSON_TARGET)
        shutil.copyfile(emitted_markdown, MARKDOWN_TARGET)
        print(f"wrote {JSON_TARGET}")
        print(f"wrote {MARKDOWN_TARGET}")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
