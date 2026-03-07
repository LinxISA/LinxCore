#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from opcode_catalog_lib import build_catalog, save_catalog
from workspace_paths import resolve_linxcore_root, resolve_qemu_linx_dir

THIS_FILE = Path(__file__).resolve()
LINXCORE_ROOT = resolve_linxcore_root(THIS_FILE)
DEFAULT_QEMU_LINX_DIR = resolve_qemu_linx_dir(LINXCORE_ROOT)


def main() -> int:
    ap = argparse.ArgumentParser(description="Extract Linx opcode catalog from QEMU decodetree files")
    ap.add_argument(
        "--qemu-linx-dir",
        default=str(DEFAULT_QEMU_LINX_DIR) if DEFAULT_QEMU_LINX_DIR is not None else "",
        help="Path to qemu/target/linx directory",
    )
    ap.add_argument(
        "--out",
        default=str(LINXCORE_ROOT / "src/common/opcode_catalog.yaml"),
        help="Output catalog path (JSON-formatted YAML)",
    )
    args = ap.parse_args()

    qemu_dir = Path(args.qemu_linx_dir)
    if not args.qemu_linx_dir:
        raise SystemExit("error: could not resolve qemu/target/linx; set --qemu-linx-dir or QEMU_LINX_DIR")
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
