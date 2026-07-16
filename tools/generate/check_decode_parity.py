#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from pathlib import Path

from opcode_catalog_lib import load_catalog, load_qemu_entries

THIS_FILE = Path(__file__).resolve()
LINXCORE_ROOT = THIS_FILE.parents[2]
LINXISA_ROOT = THIS_FILE.parents[4]


def _record_signature(record: dict) -> tuple[str, int, int, int]:
    return (
        str(record["mnemonic"]),
        int(record["enc_len"]),
        int(str(record["mask"]), 0),
        int(str(record["match"]), 0),
    )


def _format_signature(signature: tuple[str, int, int, int]) -> str:
    mnemonic, enc_len, mask, match = signature
    return f"{mnemonic}: len={enc_len} mask=0x{mask:x} match=0x{match:x}"


def main() -> int:
    ap = argparse.ArgumentParser(description="Check QEMU vs LinxCore opcode catalog parity")
    ap.add_argument("--qemu-linx-dir", default=str(LINXISA_ROOT / "emulator/qemu/target/linx"))
    ap.add_argument("--catalog", default=str(LINXCORE_ROOT / "src/common/opcode_catalog.yaml"))
    args = ap.parse_args()

    expected_entries = load_qemu_entries(Path(args.qemu_linx_dir))
    actual = load_catalog(Path(args.catalog))

    expected = Counter(
        (row.mnemonic, row.enc_len, row.mask, row.match)
        for row in expected_entries
    )
    actual_qemu_records = [
        record
        for record in actual["records"]
        if not str(record["mnemonic"]).startswith("internal_")
    ]
    observed = Counter(_record_signature(record) for record in actual_qemu_records)

    missing = expected - observed
    extra = observed - expected
    mismatches: list[str] = []

    identities: dict[str, set[tuple[str, int]]] = defaultdict(set)
    form_indices: dict[str, list[int]] = defaultdict(list)
    form_counts: dict[str, set[int]] = defaultdict(set)
    for record in actual_qemu_records:
        mnemonic = str(record["mnemonic"])
        identities[mnemonic].add((str(record["symbol"]), int(record["op_id"])))
        form_indices[mnemonic].append(int(record.get("form_index", 0)))
        form_counts[mnemonic].add(int(record.get("form_count", 1)))
        if record.get("major_cat", "") == "":
            mismatches.append(f"{mnemonic}: missing major_cat")

    for mnemonic in sorted(identities):
        count = sum(
            multiplicity
            for signature, multiplicity in observed.items()
            if signature[0] == mnemonic
        )
        if len(identities[mnemonic]) != 1:
            mismatches.append(
                f"{mnemonic}: forms do not share one (symbol, op_id): "
                f"{sorted(identities[mnemonic])}"
            )
        if sorted(form_indices[mnemonic]) != list(range(count)):
            mismatches.append(
                f"{mnemonic}: form_index expected={list(range(count))} "
                f"actual={sorted(form_indices[mnemonic])}"
            )
        if form_counts[mnemonic] != {count}:
            mismatches.append(
                f"{mnemonic}: form_count expected={count} "
                f"actual={sorted(form_counts[mnemonic])}"
            )

    if missing:
        print("missing decode forms:")
        for signature, count in sorted(missing.items()):
            print(f"  {_format_signature(signature)} count={count}")
    if extra:
        print("extra catalog forms:")
        for signature, count in sorted(extra.items()):
            print(f"  {_format_signature(signature)} count={count}")
    if mismatches:
        print("mismatches:")
        for m in mismatches[:200]:
            print(f"  {m}")
        if len(mismatches) > 200:
            print(f"  ... {len(mismatches)-200} more")

    if missing or extra or mismatches:
        return 1

    print(
        "decode parity check passed: "
        f"{sum(observed.values())} forms, {len(identities)} mnemonics"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
