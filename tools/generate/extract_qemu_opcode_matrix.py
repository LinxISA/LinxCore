#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from opcode_catalog_lib import build_locked_catalog, save_catalog

THIS_FILE = Path(__file__).resolve()
LINXCORE_ROOT = THIS_FILE.parents[2]
LINXISA_ROOT = THIS_FILE.parents[4]


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Generate the LinxCore opcode catalog from the locked LinxISA/PTO snapshot"
    )
    ap.add_argument(
        "--linxisa-root",
        default=str(LINXISA_ROOT),
        help="Path to the LinxISA superproject containing the locked ISA profile",
    )
    ap.add_argument(
        "--out",
        default=str(LINXCORE_ROOT / "src/common/opcode_catalog.yaml"),
        help="Output catalog path (JSON-formatted YAML)",
    )
    ap.add_argument(
        "--isa-profile",
        default="v0.58",
        help="Locked LinxISA profile (default: v0.58)",
    )
    args = ap.parse_args()

    out = Path(args.out)
    catalog = build_locked_catalog(Path(args.linxisa_root), profile=args.isa_profile)
    save_catalog(out, catalog)

    records = catalog["records"]
    syms = {r["symbol"] for r in records}
    print(f"wrote {out}")
    family_counts = catalog["source"]["tile_family_counts"]
    print(
        f"forms={len(records)} unique_symbols={len(syms)} "
        f"TEPL={family_counts['TEPL']} TLSU={family_counts['TLSU']} CUBE={family_counts['CUBE']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
