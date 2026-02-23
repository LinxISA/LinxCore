#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from opcode_catalog_lib import build_catalog, save_catalog

THIS_FILE = Path(__file__).resolve()
LINXCORE_ROOT = THIS_FILE.parents[2]
LINXISA_ROOT = THIS_FILE.parents[4]


def main() -> int:
    ap = argparse.ArgumentParser(description="Extract Linx opcode catalog from QEMU decodetree files")
    ap.add_argument(
        "--qemu-linx-dir",
        default=str(LINXISA_ROOT / "emulator/qemu/target/linx"),
        help="Path to qemu/target/linx directory",
    )
    ap.add_argument(
        "--out",
        default=str(LINXCORE_ROOT / "src/common/opcode_catalog.yaml"),
        help="Output catalog path (JSON-formatted YAML)",
    )
    args = ap.parse_args()

    qemu_dir = Path(args.qemu_linx_dir)
    out = Path(args.out)
    catalog = build_catalog(qemu_dir)
    save_catalog(out, catalog)

    records = catalog["records"]
    syms = {r["symbol"] for r in records}
    print(f"wrote {out}")
    print(f"mnemonics={len(records)} unique_symbols={len(syms)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
