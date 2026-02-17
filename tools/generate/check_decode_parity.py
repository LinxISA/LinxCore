#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from opcode_catalog_lib import load_catalog, load_qemu_entries


def _norm(records: list[dict]) -> dict[str, dict]:
    out = {}
    for r in records:
        out[r["mnemonic"]] = r
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Check QEMU vs LinxCore opcode catalog parity")
    ap.add_argument("--qemu-linx-dir", default="/Users/zhoubot/qemu/target/linx")
    ap.add_argument("--catalog", default="/Users/zhoubot/LinxCore/src/common/opcode_catalog.yaml")
    args = ap.parse_args()

    expected_entries = load_qemu_entries(Path(args.qemu_linx_dir))
    actual = load_catalog(Path(args.catalog))

    e = {}
    for row in expected_entries:
        e.setdefault(
            row.mnemonic,
            {
                "mnemonic": row.mnemonic,
                "enc_len": row.enc_len,
                "mask": f"0x{row.mask:x}",
                "match": f"0x{row.match:x}",
            },
        )
    a = _norm(actual["records"])

    # Ignore internal synthetic rows for parity against qemu decode files.
    a_qemu = {k: v for k, v in a.items() if not k.startswith("internal_")}

    missing = sorted(set(e) - set(a_qemu))
    extra = sorted(set(a_qemu) - set(e))
    mismatches: list[str] = []

    for k in sorted(set(e) & set(a_qemu)):
        ek = e[k]
        ak = a_qemu[k]
        for fld in ("enc_len", "mask", "match"):
            if str(ek[fld]) != str(ak[fld]):
                mismatches.append(f"{k}: field {fld} expected={ek[fld]} actual={ak[fld]}")
        if ak.get("major_cat", "") == "":
            mismatches.append(f"{k}: missing major_cat")

    if missing:
        print("missing mnemonics:")
        for m in missing:
            print(f"  {m}")
    if extra:
        print("extra mnemonics:")
        for m in extra:
            print(f"  {m}")
    if mismatches:
        print("mismatches:")
        for m in mismatches[:200]:
            print(f"  {m}")
        if len(mismatches) > 200:
            print(f"  ... {len(mismatches)-200} more")

    if missing or extra or mismatches:
        return 1

    print(f"decode parity check passed: {len(a_qemu)} mnemonics")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
