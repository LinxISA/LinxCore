#!/usr/bin/env python3
"""Generate the production OOO opcode recipe table and its audit ledger."""

from __future__ import annotations

import argparse
import copy
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = ROOT / "src" / "common" / "opcode_catalog.yaml"
DEFAULT_SCALA = (
    ROOT / "chisel" / "src" / "main" / "scala" / "linxcore" / "ooo" /
    "OooOpcodeRecipeTable.scala"
)
DEFAULT_AUDIT = ROOT / "docs" / "chisel" / "ooo-opcode-recipe-audit.md"

DISPOSITION = {"DISPATCH": 0, "FAST_RESOLVE": 1, "CTU": 2, "ILLEGAL": 3}
RECIPE = {
    "SINGLE": 0,
    "SCALAR_LOAD": 1,
    "SCALAR_STORE": 2,
    "BOUNDARY": 3,
    "START_CALL": 4,
    "SETRET": 5,
    "CTU_TEMPLATE": 6,
    "PRECISE_TRAP": 7,
    "ATOMIC_UNRESOLVED": 8,
    "PAIR_LOAD": 9,
    "PAIR_STORE": 10,
    "ENGINE_CMD": 11,
    "ILLEGAL": 15,
}
LATE_SPLIT = {"NONE": 0, "STORE_ADDRESS_DATA": 1, "PAIR_STORE_ADDRESS_DATA": 2}
FUSION = {"NONE": 0, "START_MARKER": 1, "STOP_MARKER": 2, "CARRIER": 3}
FAST_RESOLVE = {
    "NONE": 0,
    "BOUNDARY_METADATA": 1,
    "IMMEDIATE_PRODUCER": 2,
    "CONTROL_VALUE_PRODUCER": 3,
    "PRECISE_TRAP_RECORD": 4,
    "NO_EFFECT": 5,
}
IMPLICIT_DESTINATION = {"NONE": 0, "RA": 1, "SP": 2}
SIDE_EFFECT_OWNER = {
    "NONE": 0,
    "IEX": 1,
    "LSU": 2,
    "BCTRL": 3,
    "CTU": 4,
    "COMMIT": 5,
    "ILLEGAL": 7,
}
DISPATCH_CLASS = {
    "NONE": 0,
    "ALU": 1,
    "BRU": 2,
    "AGU": 3,
    "STD": 4,
    "FSU": 5,
    "SYS": 6,
    "CMD": 7,
    "BOUNDARY": 8,
}
PC_READ_PARENT = {"NONE", "PRIMARY"}

REQUIRED_OOO_FIELDS = {
    "disposition", "recipe_kind", "uop_count_min", "uop_count_max",
    "complex_break", "late_split_kind", "fusion_head_class",
    "fusion_tail_class", "fast_resolve_class", "implicit_source_mask",
    "implicit_destination", "side_effect_owner", "requires_target_validation",
    "may_trap", "may_trap_late", "may_redirect", "nonspeculative",
    "pc_read_parent", "pc_read_class",
    "dispatch_class", "dispatch_writes", "dispatch_demand", "memory_request_count", "p_source_count",
    "p_destination_count", "t_allocation_count", "u_allocation_count", "reason",
}


def source_len_bytes(record: dict) -> int:
    return {"insn16.decode": 2, "insn32.decode": 4, "insn48.decode": 6,
            "insn64.decode": 8, "internal": 0}[record["source_file"]]


def validate_catalog(data: dict) -> list[dict]:
    if data.get("version") != 2:
        raise ValueError(f"OOO recipe generator requires catalog version 2, got {data.get('version')}")
    records = list(data.get("records", []))
    if not records:
        raise ValueError("opcode catalog contains no records")
    by_id: dict[int, dict] = {}
    for record in records:
        missing = REQUIRED_OOO_FIELDS - set(record.get("ooo", {}))
        if missing:
            raise ValueError(f"{record.get('symbol')} missing OOO fields: {sorted(missing)}")
        meta = record["ooo"]
        for value, table, field in (
            (meta["disposition"], DISPOSITION, "disposition"),
            (meta["recipe_kind"], RECIPE, "recipe_kind"),
            (meta["late_split_kind"], LATE_SPLIT, "late_split_kind"),
            (meta["fusion_head_class"], FUSION, "fusion_head_class"),
            (meta["fusion_tail_class"], FUSION, "fusion_tail_class"),
            (meta["fast_resolve_class"], FAST_RESOLVE, "fast_resolve_class"),
            (meta["implicit_destination"], IMPLICIT_DESTINATION, "implicit_destination"),
            (meta["side_effect_owner"], SIDE_EFFECT_OWNER, "side_effect_owner"),
            (meta["dispatch_class"], DISPATCH_CLASS, "dispatch_class"),
        ):
            if value not in table:
                raise ValueError(f"{record['symbol']} has unknown {field}={value}")
        if meta["pc_read_parent"] not in PC_READ_PARENT:
            raise ValueError(
                f"{record['symbol']} has unknown pc_read_parent="
                f"{meta['pc_read_parent']}")
        if (meta["pc_read_parent"] == "PRIMARY" and
                meta["disposition"] != "DISPATCH"):
            raise ValueError(
                f"{record['symbol']} reads PC outside the dispatch path")
        if meta["pc_read_class"] not in DISPATCH_CLASS:
            raise ValueError(
                f"{record['symbol']} has unknown pc_read_class="
                f"{meta['pc_read_class']}")
        pc_read_required = meta["pc_read_parent"] == "PRIMARY"
        if pc_read_required != (meta["pc_read_class"] != "NONE"):
            raise ValueError(
                f"{record['symbol']} has inconsistent PC parent/class policy")
        if (pc_read_required and
                int(meta["dispatch_demand"][meta["pc_read_class"]]) <= 0):
            raise ValueError(
                f"{record['symbol']} PC-read class has no dispatch child")
        if meta["disposition"] == "DISPATCH" and int(meta["dispatch_writes"]) <= 0:
            raise ValueError(f"{record['symbol']} dispatches without a write demand")
        if meta["disposition"] != "DISPATCH" and int(meta["dispatch_writes"]) != 0:
            raise ValueError(f"{record['symbol']} non-dispatch recipe consumes IQ writes")
        demand = meta["dispatch_demand"]
        if set(demand) != set(DISPATCH_CLASS) - {"NONE"}:
            raise ValueError(f"{record['symbol']} has malformed dispatch_demand keys")
        if sum(int(value) for value in demand.values()) != int(meta["dispatch_writes"]):
            raise ValueError(f"{record['symbol']} dispatch demand does not sum to dispatch_writes")
        op_id = int(record["op_id"])
        signature = {k: v for k, v in meta.items() if k != "reason"}
        if op_id in by_id and by_id[op_id] != signature:
            raise ValueError(f"opcode id {op_id} has inconsistent OOO metadata across forms")
        by_id[op_id] = signature
    return records


def bool_lit(value: object) -> str:
    return "true" if bool(value) else "false"


def rule_expr(record: dict) -> str:
    meta = record["ooo"]
    demand = ", ".join(str(meta["dispatch_demand"][name]) for name in DISPATCH_CLASS if name != "NONE")
    return (
        "OooOpcodeRecipeRule("
        f"symbol = \"{record['symbol']}\", opcode = {record['op_id']}, "
        f"lenBytes = {source_len_bytes(record)}, mask = BigInt(\"{int(record['mask'], 0):x}\", 16), "
        f"value = BigInt(\"{int(record['match'], 0):x}\", 16), "
        f"disposition = {DISPOSITION[meta['disposition']]}, recipeKind = {RECIPE[meta['recipe_kind']]}, "
        f"uopCountMin = {meta['uop_count_min']}, uopCountMax = {meta['uop_count_max']}, "
        f"complexBreak = {bool_lit(meta['complex_break'])}, lateSplitKind = {LATE_SPLIT[meta['late_split_kind']]}, "
        f"fusionHeadClass = {FUSION[meta['fusion_head_class']]}, fusionTailClass = {FUSION[meta['fusion_tail_class']]}, "
        f"fastResolveClass = {FAST_RESOLVE[meta['fast_resolve_class']]}, implicitSourceMask = {meta['implicit_source_mask']}, "
        f"implicitDestination = {IMPLICIT_DESTINATION[meta['implicit_destination']]}, "
        f"sideEffectOwner = {SIDE_EFFECT_OWNER[meta['side_effect_owner']]}, "
        f"requiresTargetValidation = {bool_lit(meta['requires_target_validation'])}, "
        f"mayTrap = {bool_lit(meta['may_trap'])}, mayTrapLate = {bool_lit(meta['may_trap_late'])}, "
        f"mayRedirect = {bool_lit(meta['may_redirect'])}, nonspeculative = {bool_lit(meta['nonspeculative'])}, "
        f"pcReadRequired = {bool_lit(meta['pc_read_parent'] == 'PRIMARY')}, "
        f"pcReadClass = {DISPATCH_CLASS[meta['pc_read_class']]}, "
        f"dispatchClass = {DISPATCH_CLASS[meta['dispatch_class']]}, dispatchWrites = {meta['dispatch_writes']}, dispatchDemand = Seq({demand}), "
        f"memoryRequestCount = {meta['memory_request_count']}, "
        f"pSourceCount = {meta['p_source_count']}, pDestinationCount = {meta['p_destination_count']}, "
        f"tAllocationCount = {meta['t_allocation_count']}, uAllocationCount = {meta['u_allocation_count']})"
    )


def emit_scala(path: Path, records: list[dict]) -> None:
    rules_with_order = [
        (record, source_ordinal)
        for source_ordinal, record in enumerate(records)
        if source_len_bytes(record) != 0
    ]
    c_setret = next(record for record in records if record["symbol"] == "OP_C_SETRET")
    c_setret_alias = copy.deepcopy(c_setret)
    c_setret_alias.update({
        "source_file": "insn16.decode",
        "enc_len": 16,
        "mask": "0xf83f",
        "match": "0x5016",
    })
    rules_with_order.append((c_setret_alias, len(records)))
    rules_with_order.sort(
        key=lambda item: (-int(item[0]["mask"], 0).bit_count(), item[1]))
    rules = [record for record, _ in rules_with_order]
    unique: dict[int, dict] = {}
    for record in records:
        unique.setdefault(int(record["op_id"]), record)
    chunk_size = 48
    chunks = [rules[start:start + chunk_size] for start in range(0, len(rules), chunk_size)]

    lines = [
        "package linxcore.ooo", "", "import chisel3._", "",
        "// AUTO-GENERATED by tools/chisel/gen_ooo_recipe_table.py.",
        "// Source: src/common/opcode_catalog.yaml (schema version 2).",
        "object OooOpcodeDisposition { val Dispatch = 0; val FastResolve = 1; val Ctu = 2; val Illegal = 3 }",
        "object OooOpcodeRecipeKind { val Single = 0; val ScalarLoad = 1; val ScalarStore = 2; val Boundary = 3; val StartCall = 4; val Setret = 5; val CtuTemplate = 6; val PreciseTrap = 7; val AtomicUnresolved = 8; val PairLoad = 9; val PairStore = 10; val EngineCmd = 11; val Illegal = 15 }",
        "object OooLateSplitKind { val None = 0; val StoreAddressData = 1; val PairStoreAddressData = 2 }",
        "object OooFusionClass { val None = 0; val StartMarker = 1; val StopMarker = 2; val Carrier = 3 }",
        "object OooFastResolveClass { val None = 0; val BoundaryMetadata = 1; val ImmediateProducer = 2; val ControlValueProducer = 3; val PreciseTrapRecord = 4; val NoEffect = 5 }",
        "object OooImplicitDestination { val None = 0; val Ra = 1; val Sp = 2 }",
        "object OooSideEffectOwner { val None = 0; val Iex = 1; val Lsu = 2; val Bctrl = 3; val Ctu = 4; val Commit = 5; val Illegal = 7 }",
        "object OooDispatchClass { val None = 0; val Alu = 1; val Bru = 2; val Agu = 3; val Std = 4; val Fsu = 5; val Sys = 6; val Cmd = 7; val Boundary = 8 }",
        "", "class OooOpcodeRecipeMeta(val p: OooParams = OooParams()) extends Bundle {",
        "  val valid = Bool()", "  val opcode = UInt(p.opcodeWidth.W)",
        "  val disposition = UInt(2.W)", "  val recipeKind = UInt(4.W)",
        "  val uopCountMin = UInt(p.recipeUopCountWidth.W)", "  val uopCountMax = UInt(p.recipeUopCountWidth.W)",
        "  val complexBreak = Bool()", "  val lateSplitKind = UInt(2.W)",
        "  val fusionHeadClass = UInt(2.W)", "  val fusionTailClass = UInt(2.W)",
        "  val fastResolveClass = UInt(3.W)", "  val implicitSourceMask = UInt(3.W)",
        "  val implicitDestination = UInt(2.W)", "  val sideEffectOwner = UInt(3.W)",
        "  val requiresTargetValidation = Bool()", "  val mayTrap = Bool()", "  val mayTrapLate = Bool()",
        "  val mayRedirect = Bool()", "  val nonspeculative = Bool()", "  val pcReadRequired = Bool()",
        "  val pcReadClass = UInt(4.W)",
        "  val dispatchClass = UInt(4.W)", "  val dispatchWrites = UInt(p.dispatchCountWidth.W)",
        "  val dispatchDemand = Vec(p.iqClassCount, UInt(p.dispatchCountWidth.W))", "  val memoryRequestCount = UInt(3.W)",
        "  val pSourceCount = UInt(p.sourceCountWidth.W)", "  val pDestinationCount = UInt(p.destinationCountWidth.W)",
        "  val tAllocationCount = UInt(2.W)", "  val uAllocationCount = UInt(2.W)", "}", "",
        "final case class OooOpcodeRecipeRule(symbol: String, opcode: Int, lenBytes: Int, mask: BigInt, value: BigInt, disposition: Int, recipeKind: Int, uopCountMin: Int, uopCountMax: Int, complexBreak: Boolean, lateSplitKind: Int, fusionHeadClass: Int, fusionTailClass: Int, fastResolveClass: Int, implicitSourceMask: Int, implicitDestination: Int, sideEffectOwner: Int, requiresTargetValidation: Boolean, mayTrap: Boolean, mayTrapLate: Boolean, mayRedirect: Boolean, nonspeculative: Boolean, pcReadRequired: Boolean, pcReadClass: Int, dispatchClass: Int, dispatchWrites: Int, dispatchDemand: Seq[Int], memoryRequestCount: Int, pSourceCount: Int, pDestinationCount: Int, tAllocationCount: Int, uAllocationCount: Int)", "",
    ]
    for chunk_idx, chunk in enumerate(chunks):
        lines.append(f"private object OooOpcodeRecipeRules{chunk_idx} {{")
        lines.append("  val Values: Seq[OooOpcodeRecipeRule] = Seq(")
        lines.extend(f"    {rule_expr(record)}{',' if idx + 1 < len(chunk) else ''}" for idx, record in enumerate(chunk))
        lines.extend(["  )", "}", ""])
    lines.extend([
        "object OooOpcodeRecipeTable {",
        "  type Rule = OooOpcodeRecipeRule",
        f"  val CatalogRecordCount: Int = {len(records)}", f"  val DecodeRuleCount: Int = {len(rules)}", f"  val OpcodeCount: Int = {len(unique)}", "",
        "  val Rules: Seq[Rule] = " + " ++ ".join(f"OooOpcodeRecipeRules{idx}.Values" for idx in range(len(chunks))),
        "", "  private def assign(meta: OooOpcodeRecipeMeta, rule: Rule): Unit = {",
        "    meta.valid := true.B", "    meta.opcode := rule.opcode.U",
        "    meta.disposition := rule.disposition.U", "    meta.recipeKind := rule.recipeKind.U",
        "    meta.uopCountMin := rule.uopCountMin.U", "    meta.uopCountMax := rule.uopCountMax.U",
        "    meta.complexBreak := rule.complexBreak.B", "    meta.lateSplitKind := rule.lateSplitKind.U",
        "    meta.fusionHeadClass := rule.fusionHeadClass.U", "    meta.fusionTailClass := rule.fusionTailClass.U",
        "    meta.fastResolveClass := rule.fastResolveClass.U", "    meta.implicitSourceMask := rule.implicitSourceMask.U",
        "    meta.implicitDestination := rule.implicitDestination.U", "    meta.sideEffectOwner := rule.sideEffectOwner.U",
        "    meta.requiresTargetValidation := rule.requiresTargetValidation.B", "    meta.mayTrap := rule.mayTrap.B",
        "    meta.mayTrapLate := rule.mayTrapLate.B", "    meta.mayRedirect := rule.mayRedirect.B",
        "    meta.nonspeculative := rule.nonspeculative.B", "    meta.pcReadRequired := rule.pcReadRequired.B",
        "    meta.pcReadClass := rule.pcReadClass.U",
        "    meta.dispatchClass := rule.dispatchClass.U",
        "    meta.dispatchWrites := rule.dispatchWrites.U", "    rule.dispatchDemand.zipWithIndex.foreach { case (value, idx) => meta.dispatchDemand(idx) := value.U }",
        "    meta.memoryRequestCount := rule.memoryRequestCount.U", "    meta.pSourceCount := rule.pSourceCount.U",
        "    meta.pDestinationCount := rule.pDestinationCount.U", "    meta.tAllocationCount := rule.tAllocationCount.U",
        "    meta.uAllocationCount := rule.uAllocationCount.U", "  }", "",
        "  private def invalid(p: OooParams): OooOpcodeRecipeMeta = {",
        "    val meta = Wire(new OooOpcodeRecipeMeta(p)); meta := 0.U.asTypeOf(meta)",
        "    meta.disposition := OooOpcodeDisposition.Illegal.U; meta.recipeKind := OooOpcodeRecipeKind.Illegal.U; meta.sideEffectOwner := OooSideEffectOwner.Illegal.U; meta",
        "  }", "",
    ])
    for chunk_idx, chunk in enumerate(chunks):
        start = chunk_idx * chunk_size
        lines.extend([
            f"  private def decodeChunk{chunk_idx}(p: OooParams, insn: UInt, lenBytes: UInt): OooOpcodeRecipeMeta = {{",
            "    val meta = invalid(p); var matched = false.B",
        ])
        for local_idx, _ in enumerate(chunk):
            rule_idx = start + local_idx
            lines.append(f"    val rule{rule_idx} = Rules({rule_idx})")
            lines.append(f"    val hit{rule_idx} = !matched && lenBytes === rule{rule_idx}.lenBytes.U && (insn & rule{rule_idx}.mask.U(p.instructionWidth.W)) === rule{rule_idx}.value.U(p.instructionWidth.W)")
            lines.append(f"    when(hit{rule_idx}) {{ assign(meta, rule{rule_idx}) }}")
            lines.append(f"    matched = matched || hit{rule_idx}")
        lines.extend(["    meta", "  }", ""])
    lines.extend([
        "  def decode(p: OooParams, insn: UInt, lenBytes: UInt): OooOpcodeRecipeMeta = {",
    ])
    for chunk_idx, _ in enumerate(chunks):
        lines.append(f"    val chunk{chunk_idx} = decodeChunk{chunk_idx}(p, insn, lenBytes)")
    lines.append("    val result = invalid(p)")
    # Reverse construction preserves the global most-specific/source-order priority.
    expression = "invalid(p)"
    for chunk_idx in reversed(range(len(chunks))):
        expression = f"Mux(chunk{chunk_idx}.valid, chunk{chunk_idx}, {expression})"
    lines.append(f"    result := {expression}")
    lines.extend(["    result", "  }", "}", ""])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def emit_audit(path: Path, records: list[dict]) -> None:
    unique: dict[int, dict] = {}
    for record in records:
        unique.setdefault(int(record["op_id"]), record)
    counts = Counter(record["ooo"]["disposition"] for record in unique.values())
    pc_read_count = sum(
        record["ooo"]["pc_read_parent"] == "PRIMARY"
        for record in unique.values())
    lines = [
        "# OOO opcode recipe audit", "", "> AUTO-GENERATED by `tools/chisel/gen_ooo_recipe_table.py`.", "",
        f"Catalog records: **{len(records)}**; unique opcode IDs: **{len(unique)}**; "
        f"primary-parent PC reads: **{pc_read_count}**.", "",
        "| Disposition | Count |", "|---|---:|",
    ]
    lines.extend(f"| `{name}` | {counts.get(name, 0)} |" for name in DISPOSITION)
    lines.extend(["", "| Opcode | ID | Recipe | Disposition | Uops | Dispatch | PC read | Owner | Reason |", "|---|---:|---|---|---:|---|---|---|---|"])
    for op_id, record in sorted(unique.items()):
        meta = record["ooo"]
        uops = f"{meta['uop_count_min']}..{meta['uop_count_max']}"
        reason = str(meta["reason"]).replace("|", "\\|")
        pc_read = f"{meta['pc_read_parent']}/{meta['pc_read_class']}"
        lines.append(f"| `{record['symbol']}` | {op_id} | `{meta['recipe_kind']}` | `{meta['disposition']}` | {uops} | `{meta['dispatch_class']}` | `{pc_read}` | `{meta['side_effect_owner']}` | {reason} |")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--scala-out", type=Path, default=DEFAULT_SCALA)
    parser.add_argument("--audit-out", type=Path, default=DEFAULT_AUDIT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    records = validate_catalog(json.loads(args.catalog.read_text(encoding="utf-8")))
    if args.check:
        print(f"ooo-opcode-recipes: pass records={len(records)}")
        return
    emit_scala(args.scala_out, records)
    emit_audit(args.audit_out, records)
    print(f"generated {args.scala_out} and {args.audit_out}")


if __name__ == "__main__":
    main()
