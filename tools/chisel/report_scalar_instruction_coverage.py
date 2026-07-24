#!/usr/bin/env python3
"""Report auditable non-vector/tile LinxCore scalar instruction coverage."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]


def _first_existing_path(candidates: tuple[Path, ...]) -> Path:
    for path in candidates:
        if path.is_file():
            return path
    return candidates[0]


DEFAULT_SPEC = _first_existing_path(
    (
        ROOT.parent.parent / "isa" / "v0.57" / "linxisa-v0.57.json",
        Path("/Users/zhoubot/linx-isa/isa/v0.57/linxisa-v0.57.json"),
    )
)
DEFAULT_FRONTEND = (
    ROOT
    / "chisel"
    / "src"
    / "main"
    / "scala"
    / "linxcore"
    / "frontend"
    / "FrontendOpcodeDecodeTable.scala"
)
DEFAULT_ALU = (
    ROOT
    / "chisel"
    / "src"
    / "main"
    / "scala"
    / "linxcore"
    / "execute"
    / "ReducedScalarAluExecute.scala"
)
DEFAULT_SRC = ROOT / "src"

EXPECTED_CATALOG_FORMS = 769
EXPECTED_VECTOR_FORMS = 184
EXPECTED_TILE_PTO_DESCRIPTORS = 30
EXPECTED_VECTOR_MODE_BLOCK_DESCRIPTORS = 8
EXPECTED_SCALAR_DENOMINATOR = 547

TILE_PTO_PREFIXES = (
    "BSTART.ACCCVT",
    "BSTART.CUBE",
    "BSTART.FIXP",
    "BSTART.TEPL",
    "BSTART.TLOAD",
    "BSTART.TSTORE",
    "BSTART.TMOV",
    "BSTART.TPREFETCH",
    "BSTART.MGATHER",
    "BSTART.MSCATTER",
    "BSTART.TGEMV",
    "BSTART.TMATMUL",
)
VECTOR_MODE_BLOCK_MNEMONICS = {
    "BSTART.MPAR",
    "BSTART.MSEQ",
    "BSTART.VPAR",
    "BSTART.VSEQ",
    "C.BSTART.MPAR",
    "C.BSTART.MSEQ",
    "C.BSTART.VPAR",
    "C.BSTART.VSEQ",
}
KNOWN_ALIGNMENT_DIVERGENCES = {
    "CSEL": "QEMU/Sail operand-order divergence; exclude from cross-stack aligned support",
}


@dataclass(frozen=True)
class CoverageReport:
    report: dict[str, Any]


def _load_spec(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    instructions = data.get("instructions")
    if not isinstance(instructions, list):
        raise ValueError(f"{path}: expected top-level instructions list")
    for index, insn in enumerate(instructions):
        if not isinstance(insn, dict):
            raise ValueError(f"{path}: instructions[{index}] is not an object")
        for key in ("id", "mnemonic", "group", "uop_big_kind", "uop_class", "encoding"):
            if key not in insn:
                raise ValueError(f"{path}: instructions[{index}] missing {key}")
    return instructions


def _uop_class(insn: dict[str, Any]) -> dict[str, Any]:
    value = insn.get("uop_class")
    if not isinstance(value, dict):
        raise ValueError(f"{insn.get('id')}: uop_class must be an object")
    return value


def is_vector_form(insn: dict[str, Any]) -> bool:
    uop_class = _uop_class(insn)
    return (
        insn.get("uop_big_kind") == "VEC"
        or uop_class.get("cmd_kind") == "VEC_ENGINE_CMD"
    )


def is_tile_pto_descriptor(insn: dict[str, Any]) -> bool:
    mnemonic = str(insn["mnemonic"])
    return mnemonic == "B.IOT" or any(
        mnemonic.startswith(prefix) for prefix in TILE_PTO_PREFIXES
    )


def is_vector_mode_block_descriptor(insn: dict[str, Any]) -> bool:
    return str(insn["mnemonic"]) in VECTOR_MODE_BLOCK_MNEMONICS


def _excluded_reason(insn: dict[str, Any]) -> str | None:
    reasons = []
    if is_vector_form(insn):
        reasons.append("vector_form")
    if is_tile_pto_descriptor(insn):
        reasons.append("tile_pto_descriptor")
    if is_vector_mode_block_descriptor(insn):
        reasons.append("vector_mode_block_descriptor")
    if len(reasons) > 1:
        raise ValueError(f"{insn['id']}: non-disjoint exclusion predicates: {reasons}")
    return reasons[0] if reasons else None


def _norm_mnemonic(mnemonic: str) -> str:
    return mnemonic.lower().replace(".", "_").replace(" ", "_")


def _variant_from_asm(asm: str, variants: tuple[tuple[str, str], ...]) -> str | None:
    upper = asm.upper()
    for token, suffix in variants:
        if token in upper:
            return suffix
    return None


def frontend_meta_names_for_form(insn: dict[str, Any]) -> list[str]:
    """Return explicit generated opcode-meta names expected to decode this form."""

    mnemonic = str(insn["mnemonic"])
    asm = str(insn.get("asm", ""))
    if mnemonic == "B.DIM":
        upper = asm.upper()
        if "LB0" in upper:
            return ["b_dim_lb0"]
        if "LB1" in upper:
            return ["b_dim_lb1"]
        if "LB2" in upper:
            return ["b_dim_lb2"]
        return []
    if mnemonic == "BSTART":
        return ["bstart_split_cond" if "COND" in asm.upper() else "bstart_split_direct"]
    if mnemonic == "BSTART.STD":
        suffix = _variant_from_asm(
            asm,
            (
                ("RET", "ret"),
                ("ICALL", "icall"),
                ("COND", "cond"),
                ("IND", "ind"),
                ("DIRECT", "direct"),
                ("CALL", "call"),
                ("FALL", "fall"),
            ),
        )
        return [f"bstart_{suffix}"] if suffix else []
    if mnemonic == "BSTART.FP":
        suffix = _variant_from_asm(
            asm,
            (
                ("RET", "ret"),
                ("ICALL", "icall"),
                ("COND", "cond"),
                ("IND", "ind"),
                ("DIRECT", "direct"),
                ("CALL", "call"),
                ("FALL", "fall"),
            ),
        )
        return [f"bstart_fp_{suffix}"] if suffix else []
    if mnemonic == "C.BSTART":
        return ["c_bstart_cond" if "COND" in asm.upper() else "c_bstart_direct"]
    if mnemonic == "C.BSTART.STD":
        return [
            "internal_c_bstart_std",
            "c_bstart_std_call",
            "c_bstart_std_cond",
            "c_bstart_std_direct",
            "c_bstart_std_fall",
            "c_bstart_std_icall",
            "c_bstart_std_ind",
            "c_bstart_std_ret",
        ]
    if mnemonic == "C.SETRET":
        return ["internal_c_setret", "c_setret"]
    if mnemonic == "HL.BSTART CALL":
        return ["bstart_call"]
    if mnemonic == "HL.BSTART.STD":
        suffix = _variant_from_asm(
            asm,
            (("COND", "cond"), ("FALL", "fall"), ("CALL", "call"), ("DIRECT", "direct")),
        )
        return [f"hl_bstart_std_{suffix}"] if suffix else []
    if mnemonic == "HL.BSTART.FP":
        suffix = _variant_from_asm(
            asm,
            (("COND", "cond"), ("FALL", "fall"), ("CALL", "call"), ("DIRECT", "direct")),
        )
        return [f"hl_bstart_fp_{suffix}"] if suffix else []
    return [_norm_mnemonic(mnemonic)]


def _load_opcode_meta_symbols(src_root: Path) -> dict[str, set[str]]:
    sys.path.insert(0, str(src_root))
    try:
        from common.opcode_meta_gen import OPCODE_META_FORMS_BY_MNEMONIC  # type: ignore
    finally:
        try:
            sys.path.remove(str(src_root))
        except ValueError:
            pass

    result: dict[str, set[str]] = {}
    for mnemonic, forms in OPCODE_META_FORMS_BY_MNEMONIC.items():
        result[mnemonic] = {str(form.symbol) for form in forms}
    return result


def _symbols_for_form(
    insn: dict[str, Any], meta_symbols_by_name: dict[str, set[str]]
) -> set[str]:
    symbols: set[str] = set()
    for name in frontend_meta_names_for_form(insn):
        symbols.update(meta_symbols_by_name.get(name, set()))
    return symbols


def _parse_frontend_symbols(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    symbols = set(re.findall(r"\bval (OP_[A-Z0-9_]+): Int =", text))
    if not symbols:
        raise ValueError(f"{path}: no FrontendOpcodeDecodeTable OP_* symbols found")
    return symbols


IS_SUPPORTED_SIGNATURE = "private def isSupported(op: UInt): Bool ="
EXPANDED_END_SIGNATURE = "private def isDivideOrRemainder(op: UInt): Bool ="
LEGACY_END_SIGNATURE = "private def ldiScaledOffset(imm: UInt): UInt ="

SOURCE_SHAPE_CONTRACTS = {
    "expanded_current": {
        "start_signature": IS_SUPPORTED_SIGNATURE,
        "end_signature": EXPANDED_END_SIGNATURE,
        "forbidden_private_def_between_markers": True,
        "expected": {
            "frontend_strict_decode": {"covered": 546, "denominator": 547},
            "reduced_scalar_alu_support": {"covered": 189, "denominator": 547},
            "cross_stack_aligned_support": {"covered": 188, "denominator": 547},
        },
    },
    "legacy_clean_head": {
        "start_signature": IS_SUPPORTED_SIGNATURE,
        "end_signature": LEGACY_END_SIGNATURE,
        "forbidden_private_def_between_markers": True,
        "forbidden_signature_after_start": EXPANDED_END_SIGNATURE,
        "expected": {
            "frontend_strict_decode": {"covered": 546, "denominator": 547},
            "reduced_scalar_alu_support": {"covered": 58, "denominator": 547},
            "cross_stack_aligned_support": {"covered": 57, "denominator": 547},
        },
    },
}


def _private_def_between(body: str) -> re.Match[str] | None:
    return re.search(r"(?m)^\s*private def (?!isSupported\b)[A-Za-z0-9_]+\b", body)


def _extract_is_supported_body(path: Path) -> tuple[str, str, dict[str, Any]]:
    text = path.read_text(encoding="utf-8")
    start = text.find(IS_SUPPORTED_SIGNATURE)
    if start < 0:
        raise ValueError(
            f"{path}: could not isolate isSupported source-shape contract "
            f"({IS_SUPPORTED_SIGNATURE!r} not found)"
        )

    expanded_end = text.find(EXPANDED_END_SIGNATURE, start + len(IS_SUPPORTED_SIGNATURE))
    legacy_end = text.find(LEGACY_END_SIGNATURE, start + len(IS_SUPPORTED_SIGNATURE))

    if expanded_end >= 0:
        if legacy_end >= 0 and legacy_end < expanded_end:
            raise ValueError(
                f"{path}: ambiguous isSupported source-shape contract; "
                f"legacy end appears before expanded end"
            )
        contract_id = "expanded_current"
        end = expanded_end
    elif legacy_end >= 0:
        contract_id = "legacy_clean_head"
        end = legacy_end
    else:
        raise ValueError(
            f"{path}: unknown isSupported source-shape contract; expected "
            f"{EXPANDED_END_SIGNATURE!r} or {LEGACY_END_SIGNATURE!r} after isSupported"
        )

    if end <= start:
        raise ValueError(f"{path}: invalid isSupported source-shape marker order")

    body = text[start:end]
    nested_helper = _private_def_between(body)
    if nested_helper is not None:
        raise ValueError(
            f"{path}: isSupported source-shape contract drifted; "
            f"found helper before {SOURCE_SHAPE_CONTRACTS[contract_id]['end_signature']!r}: "
            f"{nested_helper.group(0).strip()}"
        )

    return contract_id, body, SOURCE_SHAPE_CONTRACTS[contract_id]


def _parse_supported_alu_symbols(path: Path) -> tuple[set[str], str, dict[str, Any]]:
    contract_id, body, contract = _extract_is_supported_body(path)
    symbols = set(re.findall(r"FrontendOpcodeDecodeTable\.(OP_[A-Z0-9_]+)", body))
    if not symbols:
        raise ValueError(f"{path}: isSupported contains no OP_* symbols")
    return symbols, contract_id, contract


def _bucket_ids(items: list[dict[str, Any]]) -> list[str]:
    return sorted(str(item["id"]) for item in items)


def build_report(
    spec_path: Path = DEFAULT_SPEC,
    frontend_path: Path = DEFAULT_FRONTEND,
    alu_path: Path = DEFAULT_ALU,
    src_root: Path = DEFAULT_SRC,
) -> CoverageReport:
    instructions = _load_spec(spec_path)
    if len(instructions) != EXPECTED_CATALOG_FORMS:
        raise ValueError(
            f"expected {EXPECTED_CATALOG_FORMS} v0.57 forms, got {len(instructions)}"
        )

    by_reason = {
        "vector_form": [],
        "tile_pto_descriptor": [],
        "vector_mode_block_descriptor": [],
    }
    scalar_forms: list[dict[str, Any]] = []
    for insn in instructions:
        reason = _excluded_reason(insn)
        if reason is None:
            scalar_forms.append(insn)
        else:
            by_reason[reason].append(insn)

    if len(by_reason["vector_form"]) != EXPECTED_VECTOR_FORMS:
        raise ValueError(f"vector exclusion count drifted: {len(by_reason['vector_form'])}")
    if len(by_reason["tile_pto_descriptor"]) != EXPECTED_TILE_PTO_DESCRIPTORS:
        raise ValueError(
            f"tile/PTO descriptor count drifted: {len(by_reason['tile_pto_descriptor'])}"
        )
    if (
        len(by_reason["vector_mode_block_descriptor"])
        != EXPECTED_VECTOR_MODE_BLOCK_DESCRIPTORS
    ):
        raise ValueError(
            "vector-mode block descriptor count drifted: "
            f"{len(by_reason['vector_mode_block_descriptor'])}"
        )
    if len(scalar_forms) != EXPECTED_SCALAR_DENOMINATOR:
        raise ValueError(f"scalar denominator drifted: {len(scalar_forms)}")

    frontend_symbols = _parse_frontend_symbols(frontend_path)
    alu_supported_symbols, contract_id, contract = _parse_supported_alu_symbols(alu_path)
    meta_symbols_by_name = _load_opcode_meta_symbols(src_root)

    frontend_missing = []
    frontend_covered = []
    alu_supported = []
    aligned_supported = []
    unsupported = []
    for insn in scalar_forms:
        symbols = _symbols_for_form(insn, meta_symbols_by_name)
        frontend_hit = bool(symbols & frontend_symbols)
        alu_hit = bool(symbols & alu_supported_symbols)
        aligned_hit = alu_hit and str(insn["mnemonic"]) not in KNOWN_ALIGNMENT_DIVERGENCES
        row = {
            "id": insn["id"],
            "mnemonic": insn["mnemonic"],
            "meta_names": frontend_meta_names_for_form(insn),
            "symbols": sorted(symbols),
        }
        if frontend_hit:
            frontend_covered.append(row)
        else:
            frontend_missing.append(row)
        if alu_hit:
            alu_supported.append(row)
        else:
            unsupported.append(row)
        if aligned_hit:
            aligned_supported.append(row)

    report = {
        "schema_version": "linxcore.scalar_instruction_coverage.v1",
        "spec": str(spec_path),
        "frontend_decode_table": str(frontend_path),
        "reduced_scalar_alu": str(alu_path),
        "source_shape_contracts": {
            "reduced_scalar_alu_is_supported": {
                "contract_id": contract_id,
                "start_signature": contract["start_signature"],
                "end_signature": contract["end_signature"],
                "private_def_between_markers": "forbidden",
                "expected": contract["expected"],
                "purpose": "fail closed if helper reordering would silently change support parsing",
            }
        },
        "catalog_forms": len(instructions),
        "excluded": {
            reason: {
                "count": len(items),
                "ids": _bucket_ids(items),
            }
            for reason, items in by_reason.items()
        },
        "scalar_denominator": len(scalar_forms),
        "frontend_strict_decode": {
            "covered": len(frontend_covered),
            "denominator": len(scalar_forms),
            "ratio_percent": round(100.0 * len(frontend_covered) / len(scalar_forms), 4),
            "missing": frontend_missing,
        },
        "reduced_scalar_alu_support": {
            "covered": len(alu_supported),
            "denominator": len(scalar_forms),
            "ratio_percent": round(100.0 * len(alu_supported) / len(scalar_forms), 4),
            "supported_symbol_count": len(alu_supported_symbols),
            "unsupported_first_50": unsupported[:50],
        },
        "cross_stack_aligned_support": {
            "covered": len(aligned_supported),
            "denominator": len(scalar_forms),
            "ratio_percent": round(100.0 * len(aligned_supported) / len(scalar_forms), 4),
            "known_divergences": KNOWN_ALIGNMENT_DIVERGENCES,
        },
    }
    return CoverageReport(report)


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=DEFAULT_SPEC)
    parser.add_argument("--frontend", type=Path, default=DEFAULT_FRONTEND)
    parser.add_argument("--alu", type=Path, default=DEFAULT_ALU)
    parser.add_argument("--src-root", type=Path, default=DEFAULT_SRC)
    parser.add_argument("--check", action="store_true", help="fail if frozen counts drift")
    parser.add_argument("--json-out", type=Path, default=None)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = _parse_args(argv)
    try:
        report = build_report(args.spec, args.frontend, args.alu, args.src_root).report
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.check:
        contract = report["source_shape_contracts"]["reduced_scalar_alu_is_supported"]
        expected = contract["expected"]
        for key, values in expected.items():
            covered = values["covered"]
            denominator = values["denominator"]
            actual = report[key]
            if actual["covered"] != covered or actual["denominator"] != denominator:
                print(
                    f"error: {key} drifted: "
                    f"{actual['covered']}/{actual['denominator']} != {covered}/{denominator}",
                    file=sys.stderr,
                )
                return 1

    text = json.dumps(report, indent=2, sort_keys=True)
    if args.json_out is not None:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
