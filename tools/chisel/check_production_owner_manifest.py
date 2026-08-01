#!/usr/bin/env python3
"""Validate the canonical LinxCore production-owner cutover manifest."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = ROOT / "docs/chisel/production-owner-manifest.md"
MANIFEST_BLOCK = re.compile(
    r"```json\s+production-owner-manifest\s*\n(?P<payload>.*?)\n```",
    re.DOTALL,
)
APP_ENTRY = re.compile(r"\bobject\s+(?P<name>[A-Za-z_]\w*)\s+extends\s+App\b")
OBJECT_DECLARATION = re.compile(r"\bobject\s+(?P<name>[A-Za-z_]\w*)\b")
DEF_MAIN = re.compile(r"\bdef\s+main\s*\(")
AT_MAIN_ENTRY = re.compile(r"@main\s+def\s+(?P<name>[A-Za-z_]\w*)\s*\(")
ANY_DEFINITION = re.compile(
    r"(?m)^[ \t]*(?:private(?:\[[^\]]+\])?[ \t]+)?(?:final[ \t]+)?"
    r"(?P<kind>class|object)\s+(?P<name>[A-Za-z_]\w*)\b"
)
INSTANTIATION = re.compile(
    r"\bnew\s+(?P<name>[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)\b"
)
MODULE_INSTANTIATION = re.compile(
    r"\bModule\s*\(\s*new\s+"
    r"(?P<name>[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)\b"
)
PACKAGE_DECLARATION = re.compile(r"(?m)^[ \t]*package[ \t]+(?P<name>[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")
STATE_TOKEN = re.compile(
    r"\b(?:Reg|RegInit|RegNext|RegEnable|Mem|SyncReadMem)\s*\(|\bQueue\s*\("
)
SYMBOL_DEFINITION = (
    r"(?m)^[ \t]*(?:private(?:\[[^\]]+\])?[ \t]+)?(?:final[ \t]+)?"
    r"(?:class|object)\s+{symbol}\b"
)
REQUIRED_SUBSYSTEMS = {"IFU", "CTU", "OOO", "IEX", "LSU", "DTU"}
EVIDENCE_STATUSES = {
    "public-box-verified",
    "standalone-verified",
    "mechanism-verified-cutover-pending",
    "production-promoted",
}
PUBLIC_INTERFACES = {
    box: f"chisel/src/main/scala/linxcore/top/interface/{box}IO.scala"
    for box in REQUIRED_SUBSYSTEMS
}
PUBLIC_MODULES = {
    "IFU": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
    "CTU": "chisel/src/main/scala/linxcore/ctu/CTU.scala",
    "OOO": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
}

# Closed inventory: the manifest may report these decisions but cannot invent,
# alias, omit, or redirect a state domain to a different primary owner.
STATE_DOMAINS: dict[str, tuple[str, str, str]] = {
    "instruction_cache": (
        "IFU",
        "ISideL1I",
        "chisel/src/main/scala/linxcore/frontend/ISideL1I.scala",
    ),
    "predictor_history": (
        "IFU",
        "BSide",
        "chisel/src/main/scala/linxcore/ifu/BSide.scala",
    ),
    "ifu_recovery_redirect": (
        "IFU",
        "IFURecovery",
        "chisel/src/main/scala/linxcore/ifu/IFURecovery.scala",
    ),
    "instruction_buffer": (
        "CTU",
        "InstructionBuffer",
        "chisel/src/main/scala/linxcore/ctu/InstructionBuffer.scala",
    ),
    "rename_p": (
        "OOO",
        "PRename",
        "chisel/src/main/scala/linxcore/ooo/PRename.scala",
    ),
    "rename_tu": (
        "OOO",
        "TURename",
        "chisel/src/main/scala/linxcore/ooo/TURename.scala",
    ),
    "rob": ("OOO", "ROB", "chisel/src/main/scala/linxcore/ooo/ROB.scala"),
    "brob": ("OOO", "BROB", "chisel/src/main/scala/linxcore/ooo/BROB.scala"),
    "commit": (
        "OOO",
        "CommitControl",
        "chisel/src/main/scala/linxcore/ooo/CommitControl.scala",
    ),
    "recovery": (
        "OOO",
        "RecoveryControl",
        "chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala",
    ),
    "dispatch_reservation": (
        "OOO",
        "OooD3ReservationAllocator",
        "chisel/src/main/scala/linxcore/ooo/OooD3ReservationAllocator.scala",
    ),
    "issue_queue": (
        "IEX",
        "OooIexIssue",
        "chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala",
    ),
    "physical_register_data_readiness": (
        "IEX",
        "OooIexOperandFiles",
        "chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala",
    ),
    "execution_pipeline": (
        "IEX",
        "OooIexExecutionPipeline",
        "chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala",
    ),
    "lsu_pipeline": (
        "LSU",
        "ScalarLSU",
        "chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala",
    ),
    "store_queue": (
        "LSU",
        "STQEntryBank",
        "chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala",
    ),
    "store_commit_buffer": (
        "LSU",
        "SCBRowBank",
        "chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala",
    ),
    "load_inflight_queue": (
        "LSU",
        "LoadInflightQueue",
        "chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala",
    ),
    "load_resolve_queue": (
        "LSU",
        "LoadResolveQueue",
        "chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala",
    ),
    "memory_dependency": (
        "LSU",
        "ScalarLSUMDBPath",
        "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala",
    ),
    "cache": (
        "LSU",
        "ScalarL1D",
        "chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala",
    ),
    "lsu_recovery": (
        "LSU",
        "ScalarLSURecoveryBoundary",
        "chisel/src/main/scala/linxcore/lsu/ScalarLSURecoveryBoundary.scala",
    ),
    "trace_debug_performance_observation": (
        "DTU",
        "CommitTraceMonitor",
        "chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala",
    ),
}

# Each state-domain mechanism set is checker-owned.  A manifest row may not
# widen a domain by appending a second implementation under a different name.
DOMAIN_MECHANISMS: dict[str, tuple[tuple[str, str], ...]] = {
    "instruction_cache": (("ISideL1I", "chisel/src/main/scala/linxcore/frontend/ISideL1I.scala"),),
    "predictor_history": (
        ("BSide", "chisel/src/main/scala/linxcore/ifu/BSide.scala"),
        ("BSideHistoryQueue", "chisel/src/main/scala/linxcore/frontend/BSideHistoryQueue.scala"),
        ("BSidePredictionPipeline", "chisel/src/main/scala/linxcore/frontend/BSidePredictionPipeline.scala"),
    ),
    "ifu_recovery_redirect": (
        ("IFURecovery", "chisel/src/main/scala/linxcore/ifu/IFURecovery.scala"),
        ("IfuRedirectArbiter", "chisel/src/main/scala/linxcore/frontend/IfuRedirectArbiter.scala"),
    ),
    "instruction_buffer": (
        ("InstructionBuffer", "chisel/src/main/scala/linxcore/ctu/InstructionBuffer.scala"),
        ("TemplateDecode", "chisel/src/main/scala/linxcore/ctu/TemplateDecode.scala"),
        ("TemplateExpand", "chisel/src/main/scala/linxcore/ctu/TemplateExpand.scala"),
    ),
    "rename_p": (("PRename", "chisel/src/main/scala/linxcore/ooo/PRename.scala"),),
    "rename_tu": (("TURename", "chisel/src/main/scala/linxcore/ooo/TURename.scala"),),
    "rob": (("ROB", "chisel/src/main/scala/linxcore/ooo/ROB.scala"),),
    "brob": (("BROB", "chisel/src/main/scala/linxcore/ooo/BROB.scala"),),
    "commit": (("CommitControl", "chisel/src/main/scala/linxcore/ooo/CommitControl.scala"),),
    "recovery": (("RecoveryControl", "chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala"),),
    "dispatch_reservation": (
        ("OooD3ReservationAllocator", "chisel/src/main/scala/linxcore/ooo/OooD3ReservationAllocator.scala"),
        ("OooHierarchicalFreeSlotSelect", "chisel/src/main/scala/linxcore/ooo/OooHierarchicalFreeSlotSelect.scala"),
        ("OooDispatch", "chisel/src/main/scala/linxcore/ooo/OooDispatch.scala"),
    ),
    "issue_queue": (
        ("OooIexIssue", "chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala"),
        ("OooIexIssueBlockMatrix", "chisel/src/main/scala/linxcore/ooo/OooIexIssueBlockMatrix.scala"),
    ),
    "physical_register_data_readiness": (
        ("OooIexOperandFiles", "chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala"),
        ("ScalarGPRFile", "chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala"),
    ),
    "execution_pipeline": (
        ("OooIexExecutionPipeline", "chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala"),
        ("OooIexTerminalFabric", "chisel/src/main/scala/linxcore/ooo/OooIexTerminalFabric.scala"),
    ),
    "lsu_pipeline": (
        ("ScalarLSU", "chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala"),
        ("ScalarLSULoadPath", "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"),
    ),
    "store_queue": (
        ("STQEntryBank", "chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala"),
        ("STQDataBank", "chisel/src/main/scala/linxcore/lsu/STQDataBank.scala"),
        ("STQCommitQueue", "chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala"),
    ),
    "store_commit_buffer": (
        ("SCBRowBank", "chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala"),
        ("STQSCBCommitBackend", "chisel/src/main/scala/linxcore/lsu/STQSCBCommitBackend.scala"),
    ),
    "load_inflight_queue": (("LoadInflightQueue", "chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala"),),
    "load_resolve_queue": (("LoadResolveQueue", "chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala"),),
    "memory_dependency": (
        ("ScalarLSUMDBPath", "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala"),
        ("MDBConflictDetect", "chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala"),
        ("MDBSSIT", "chisel/src/main/scala/linxcore/lsu/MDBSSIT.scala"),
    ),
    "cache": (
        ("ScalarL1D", "chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala"),
        ("LoadMissQueue", "chisel/src/main/scala/linxcore/lsu/LoadMissQueue.scala"),
        ("LoadRefillTransport", "chisel/src/main/scala/linxcore/lsu/LoadRefillTransport.scala"),
    ),
    "lsu_recovery": (
        ("ScalarLSURecoveryBoundary", "chisel/src/main/scala/linxcore/lsu/ScalarLSURecoveryBoundary.scala"),
        ("ScalarLSURecoverySource", "chisel/src/main/scala/linxcore/lsu/ScalarLSURecoverySource.scala"),
    ),
    "trace_debug_performance_observation": (
        ("CommitTraceMonitor", "chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala"),
        ("TraceEvent", "chisel/src/main/scala/linxcore/top/interface/DTU.scala"),
    ),
}

MANAGED_BOUNDARIES: tuple[tuple[str, str, str, bool, str, int, str], ...] = (
    ("linxcore.lsu.ReducedLoadReplayLiqAllocAdapter", "chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocAdapter.scala", "compatibility", False, "load_inflight_queue", 15, "linxcore.lsu.ReducedLoadReplayLiqAllocAdapter"),
    ("linxcore.ooo.OooIexLoadLiqAllocAdapter", "chisel/src/main/scala/linxcore/ooo/OooIexLoadLiqAllocAdapter.scala", "legacy-state-owner", True, "load_inflight_queue", 15, "linxcore.ooo.OooIexLoadLiqAllocAdapter"),
    ("linxcore.top.IfuWindowLineFillAdapter", "chisel/src/main/scala/linxcore/top/IfuWindowLineFillAdapter.scala", "legacy-state-owner", True, "instruction_cache", 17, "linxcore.top.IfuWindowLineFillAdapter"),
    ("linxcore.ifu.ISideMemoryAdapter", "chisel/src/main/scala/linxcore/ifu/ISide.scala", "legacy-state-owner", True, "instruction_cache", 17, "linxcore.ifu.ISideMemoryAdapter"),
    ("linxcore.frontend.IfuBackendFeedbackBridge", "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridge.scala", "legacy-state-owner", True, "ifu_recovery_redirect", 17, "linxcore.frontend.IfuBackendFeedbackBridge"),
    ("linxcore.lsu.ReducedStoreExecResultBridge", "chisel/src/main/scala/linxcore/lsu/ReducedStoreExecResultBridge.scala", "legacy-state-owner", True, "store_queue", 15, "linxcore.lsu.ReducedStoreExecResultBridge"),
    ("linxcore.lsu.SCBCommitBridge", "chisel/src/main/scala/linxcore/lsu/SCBCommitBridge.scala", "legacy-state-owner", True, "store_commit_buffer", 15, "linxcore.lsu.SCBCommitBridge"),
    ("linxcore.ooo.OooCtuIngressBridge", "chisel/src/main/scala/linxcore/ooo/OooCtuIngressBridge.scala", "legacy-state-owner", True, "dispatch_reservation", 11, "linxcore.ooo.OooCtuIngressBridge"),
    ("linxcore.ooo.OooFrontendRecoveryBridge", "chisel/src/main/scala/linxcore/ooo/OooFrontendRecoveryBridge.scala", "legacy-state-owner", True, "recovery", 11, "linxcore.ooo.OooFrontendRecoveryBridge"),
    ("linxcore.rename.ScalarDecodeRenameBridge", "chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala", "legacy-state-owner", True, "rename_p", 11, "linxcore.rename.ScalarDecodeRenameBridge"),
    ("linxcore.rename.ScalarTURenameBridge", "chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala", "legacy-state-owner", True, "rename_tu", 11, "linxcore.rename.ScalarTURenameBridge"),
    ("linxcore.top.IfuLineMemoryBridge", "chisel/src/main/scala/linxcore/top/IfuLineMemoryBridge.scala", "legacy-state-owner", True, "instruction_cache", 17, "linxcore.top.IfuLineMemoryBridge"),
)

# Exact elaboration entry-point inventory.  Classification is intentionally
# data, not a name heuristic: adding "Reduced" or "Probe" to a new executable
# does not grant it a non-production exemption.
EMITTER_INVENTORY: dict[tuple[str, str], str] = {
    ("linxcore.top.Elaborate", "chisel/src/main/scala/linxcore/top/LinxCoreTop.scala"): "legacy-top",
    ("linxcore.execute.ElaborateScalarIssueFabricProbe", "chisel/src/main/scala/linxcore/execute/ScalarIssueFabricProbe.scala"): "probe",
    ("linxcore.bctrl.EmitBrobOrderStateProbe", "chisel/src/main/scala/linxcore/bctrl/BrobOrderStateProbe.scala"): "probe",
    ("linxcore.bctrl.EmitBrobStoreCountPublisherProbe", "chisel/src/main/scala/linxcore/bctrl/BrobStoreCountPublisherProbe.scala"): "probe",
    ("linxcore.bctrl.EmitBrobStoreRangeStateProbe", "chisel/src/main/scala/linxcore/bctrl/BrobStoreRangeStateProbe.scala"): "probe",
    ("linxcore.backend.EmitD1DecodeRenameROBIngress", "chisel/src/main/scala/linxcore/backend/D1DecodeRenameROBIngress.scala"): "fixture",
    ("linxcore.frontend.EmitD1InstructionDecodeProbe", "chisel/src/main/scala/linxcore/frontend/D1InstructionDecodeProbe.scala"): "probe",
    ("linxcore.backend.EmitDecodeLoadStoreIdAssignProbe", "chisel/src/main/scala/linxcore/backend/DecodeLoadStoreIdAssignProbe.scala"): "probe",
    ("linxcore.rename.EmitGPRRenameStidProbe", "chisel/src/main/scala/linxcore/rename/GPRRenameStidProbe.scala"): "probe",
    ("linxcore.frontend.EmitIfuBackendFeedbackBridgeProbe", "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridgeProbe.scala"): "probe",
    ("linxcore.top.EmitIfuLineMemoryBridgeProbe", "chisel/src/main/scala/linxcore/top/IfuLineMemoryBridgeProbe.scala"): "probe",
    ("linxcore.top.EmitLinxCoreBenchmarkAutonomousTop", "chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreCompositionProbe", "chisel/src/main/scala/linxcore/top/LinxCoreCompositionProbe.scala"): "probe",
    ("linxcore.top.EmitLinxCoreFrontendAluTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendAluTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendFetchRfAluMarkerRowsTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluMarkerRowsTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendFetchRfAluReducedStoreLiveLoadLiqTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluReducedStoreLiveLoadLiqTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluReducedStoreReplayLiqTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendFetchRfAluReducedStoreTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluReducedStoreTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendFetchRfAluTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchRfAluTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendFetchTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendRfAluTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendRfAluTraceTop.scala"): "legacy-top",
    ("linxcore.top.EmitLinxCoreFrontendTraceTop", "chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala"): "legacy-top",
    ("linxcore.frontend.EmitLinxCoreIfuThroughputProbe", "chisel/src/main/scala/linxcore/frontend/LinxCoreIfuThroughputProbe.scala"): "probe",
    ("linxcore.top.EmitLinxCoreTopXcheck", "chisel/src/main/scala/linxcore/top/LinxCoreTop.scala"): "legacy-top",
    ("linxcore.lsu.EmitLoadMissQueueProbe", "chisel/src/main/scala/linxcore/lsu/LoadMissQueueProbe.scala"): "probe",
    ("linxcore.lsu.EmitLoadRefillTransportProbe", "chisel/src/main/scala/linxcore/lsu/LoadRefillTransportProbe.scala"): "probe",
    ("linxcore.recovery.EmitRecoveryClassMergeProbe", "chisel/src/main/scala/linxcore/recovery/RecoveryClassMergeProbe.scala"): "probe",
    ("linxcore.recovery.EmitRecoveryCleanupROBProbe", "chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala"): "probe",
    ("linxcore.recovery.EmitRecoveryProducerProbe", "chisel/src/main/scala/linxcore/recovery/RecoveryProducerProbe.scala"): "probe",
    ("linxcore.rob.EmitReducedCommitROB", "chisel/src/main/scala/linxcore/rob/EmitReducedCommitROB.scala"): "reduced",
    ("linxcore.lsu.EmitReducedStoreNonFlushGateProbe", "chisel/src/main/scala/linxcore/lsu/ReducedStoreNonFlushGateProbe.scala"): "probe",
    ("linxcore.lsu.EmitReducedStoreWaitReplayChiselPathProbe", "chisel/src/main/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathProbe.scala"): "probe",
    ("linxcore.execute.EmitScalarGPRIssueWakeupProbe", "chisel/src/main/scala/linxcore/execute/ScalarGPRIssueWakeupProbe.scala"): "probe",
    ("linxcore.lsu.EmitScalarL1DProbe", "chisel/src/main/scala/linxcore/lsu/ScalarL1DProbe.scala"): "probe",
    ("linxcore.lsu.EmitScalarL1DScbProbe", "chisel/src/main/scala/linxcore/lsu/ScalarL1DScbProbe.scala"): "probe",
    ("linxcore.lsu.EmitScalarLSULoadPathReturnProbe", "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPathReturnProbe.scala"): "probe",
    ("linxcore.lsu.EmitScalarLSULoadReturnQueueProbe", "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnQueueProbe.scala"): "probe",
    ("linxcore.lsu.EmitScalarLSUMDBPathProbe", "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPathProbe.scala"): "probe",
    ("linxcore.top.EmitScalarLoadCompletionROBProbe", "chisel/src/main/scala/linxcore/top/ScalarLoadCompletionROBProbe.scala"): "probe",
    ("linxcore.recovery.EmitScalarRedirectRecoverySourceProbe", "chisel/src/main/scala/linxcore/recovery/ScalarRedirectRecoverySourceProbe.scala"): "probe",
}


@dataclass(frozen=True)
class ScalaUnit:
    path: Path
    relative: str
    package: str
    text: str
    imports: dict[str, str]
    wildcard_imports: tuple[str, ...]
    import_errors: tuple[str, ...]


@dataclass(frozen=True)
class ScalaDefinition:
    fqcn: str
    simple: str
    kind: str
    path: Path
    relative: str
    body: str
    start: int
    body_start: int
    end: int
    imports: dict[str, str]
    wildcard_imports: tuple[str, ...]
    import_errors: tuple[str, ...]


@dataclass
class ScalaIndex:
    units: dict[Path, ScalaUnit]
    definitions: dict[str, ScalaDefinition]
    definitions_by_path: dict[Path, list[ScalaDefinition]]
    constructor_callers: dict[str, set[str]]
    unresolved_constructors: dict[Path, set[str]]
    module_edges: dict[str, set[str]]
    unresolved_module_edges: dict[str, set[str]]
    direct_stateful: set[str]


CallableKey = tuple[str, str]


@dataclass(frozen=True)
class ScalaCallable:
    key: CallableKey
    body: str
    path: Path
    relative: str
    imports: dict[str, str]
    wildcard_imports: tuple[str, ...]
    definition: ScalaDefinition | None


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ValueError(f"cannot read manifest {path}: {error}") from error
    match = MANIFEST_BLOCK.search(text)
    if not match:
        raise ValueError("missing fenced JSON production-owner-manifest block")
    try:
        value = json.loads(match.group("payload"))
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid production-owner manifest JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError("production-owner manifest root must be an object")
    return value


def strip_scala_noncode(text: str) -> str:
    """Replace comments and literals with spaces while preserving positions."""
    result = list(text)
    index = 0
    block_depth = 0
    state = "code"
    while index < len(text):
        pair = text[index : index + 2]
        triple = text[index : index + 3]
        if state == "code":
            if pair == "//":
                state = "line"
                result[index : index + 2] = "  "
                index += 2
                continue
            elif pair == "/*":
                state = "block"
                block_depth = 1
                result[index : index + 2] = "  "
                index += 2
                continue
            elif triple == '\"\"\"':
                state = "triple"
                result[index : index + 3] = "   "
                index += 3
                continue
            elif text[index] == '"':
                state = "string"
                result[index] = " "
                index += 1
                continue
            elif text[index] == "'":
                state = "char"
                result[index] = " "
                index += 1
                continue
            else:
                index += 1
                continue
        if state == "line":
            if text[index] == "\n":
                state = "code"
                index += 1
                continue
        elif state == "block":
            if pair == "/*":
                block_depth += 1
                result[index : index + 2] = "  "
                index += 2
                continue
            if pair == "*/":
                block_depth -= 1
                result[index : index + 2] = "  "
                index += 2
                if block_depth == 0:
                    state = "code"
                continue
        elif state == "triple" and triple == '\"\"\"':
            result[index : index + 3] = "   "
            index += 3
            state = "code"
            continue
        elif state in {"string", "char"}:
            quote = '"' if state == "string" else "'"
            if text[index] == "\\":
                result[index] = " "
                if index + 1 < len(text) and text[index + 1] != "\n":
                    result[index + 1] = " "
                    index += 2
                    continue
            elif text[index] == quote:
                result[index] = " "
                index += 1
                state = "code"
                continue
        if text[index] != "\n":
            result[index] = " "
        index += 1
    return "".join(result)


def contained_path(root: Path, value: Any, label: str, errors: list[str]) -> Path | None:
    if not isinstance(value, str) or not value:
        errors.append(f"{label} must be a non-empty path")
        return None
    candidate = Path(value)
    if candidate.is_absolute() or ".." in candidate.parts:
        errors.append(f"path escapes repository root: {value}")
        return None
    resolved = (root / candidate).resolve()
    if not resolved.is_relative_to(root):
        errors.append(f"path escapes repository root: {value}")
        return None
    if not resolved.is_file():
        errors.append(f"missing {label}: {value}")
        return None
    return resolved


def path_list(
    root: Path,
    value: Any,
    label: str,
    errors: list[str],
) -> list[str]:
    if not isinstance(value, list) or not value:
        errors.append(f"{label} must be a non-empty list")
        return []
    result: list[str] = []
    for index, item in enumerate(value):
        if contained_path(root, item, f"{label}[{index}]", errors):
            result.append(item)
    return result


def source_defines(path: Path, symbol: str) -> bool:
    pattern = re.compile(SYMBOL_DEFINITION.format(symbol=re.escape(symbol)))
    return bool(pattern.search(path.read_text(encoding="utf-8")))


def source_defines_fqcn(path: Path, symbol: str) -> bool:
    if "." not in symbol:
        return False
    package, simple = symbol.rsplit(".", 1)
    text = path.read_text(encoding="utf-8")
    package_match = PACKAGE_DECLARATION.search(text)
    return (
        package_match is not None
        and package_match.group("name") == package
        and bool(re.search(SYMBOL_DEFINITION.format(symbol=re.escape(simple)), text))
    )


def source_texts(root: Path) -> dict[Path, str]:
    source_root = root / "chisel/src/main/scala"
    if not source_root.is_dir():
        return {}
    return {
        path: strip_scala_noncode(path.read_text(encoding="utf-8"))
        for path in sorted(source_root.rglob("*.scala"))
    }


def _split_import_selectors(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def canonical_scala_name(value: str) -> str:
    normalized = re.sub(r"\s*\.\s*", ".", value.strip())
    return normalized.removeprefix("_root_.")


def parse_imports(text: str) -> tuple[dict[str, str], tuple[str, ...], tuple[str, ...]]:
    imports: dict[str, str] = {}
    wildcards: list[str] = []
    errors: list[str] = []
    for match in re.finditer(r"(?m)^[ \t]*import[ \t]+", text):
        index = match.end()
        depth = 0
        while index < len(text):
            char = text[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth < 0:
                    break
            elif char in "\n;" and depth == 0:
                break
            index += 1
        expression = text[match.end() : index].strip()
        braced = re.fullmatch(r"([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\.\{(.*)\}", expression, re.DOTALL)
        if braced:
            prefix, selectors = braced.groups()
            prefix = canonical_scala_name(prefix)
            for selector in _split_import_selectors(selectors):
                renamed = re.fullmatch(
                    r"([A-Za-z_]\w*)\s*(?:=>|\bas\b)\s*([A-Za-z_]\w*|_)",
                    selector,
                )
                if renamed:
                    original, alias = renamed.groups()
                    if alias != "_":
                        imports[alias] = f"{prefix}.{original}"
                    continue
                if re.fullmatch(r"[A-Za-z_]\w*", selector):
                    imports[selector] = f"{prefix}.{selector}"
                elif selector in {"_", "*"}:
                    wildcards.append(prefix)
                else:
                    errors.append(expression)
            continue
        wildcard = re.fullmatch(
            r"([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\.(?:_|\*)", expression
        )
        if wildcard:
            wildcards.append(canonical_scala_name(wildcard.group(1)))
            continue
        exact = re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", expression)
        if exact:
            canonical = canonical_scala_name(expression)
            imports[canonical.rsplit(".", 1)[-1]] = canonical
        elif expression:
            errors.append(expression)
    return imports, tuple(wildcards), tuple(errors)


def _mask_ranges(text: str, ranges: list[tuple[int, int]]) -> str:
    result = list(text)
    for start, end in ranges:
        for index in range(max(start, 0), min(end, len(result))):
            if result[index] != "\n":
                result[index] = " "
    return "".join(result)


def _definition_bodies(unit: ScalaUnit) -> list[ScalaDefinition]:
    matches = list(ANY_DEFINITION.finditer(unit.text))
    result: list[ScalaDefinition] = []
    for position, match in enumerate(matches):
        limit = matches[position + 1].start() if position + 1 < len(matches) else len(unit.text)
        brace = unit.text.find("{", match.end(), limit)
        body = ""
        body_start = match.end()
        end = match.end()
        if brace >= 0:
            depth = 0
            end = len(unit.text)
            for index in range(brace, len(unit.text)):
                if unit.text[index] == "{":
                    depth += 1
                elif unit.text[index] == "}":
                    depth -= 1
                    if depth == 0:
                        end = index + 1
                        break
            body = unit.text[brace:end]
            body_start = brace
        simple = match.group("name")
        fqcn = f"{unit.package}.{simple}" if unit.package else simple
        result.append(
            ScalaDefinition(
                fqcn=fqcn,
                simple=simple,
                kind=match.group("kind"),
                path=unit.path,
                relative=unit.relative,
                body=body,
                start=match.start(),
                body_start=body_start,
                end=end,
                imports={},
                wildcard_imports=(),
                import_errors=(),
            )
        )
    scoped: list[ScalaDefinition] = []
    for definition in result:
        prior_bodies = [
            (item.body_start, item.end)
            for item in result
            if item.start < definition.start
        ]
        prefix = _mask_ranges(unit.text[: definition.start], prior_bodies)
        outer_imports, outer_wildcards, outer_errors = parse_imports(prefix)
        nested_bodies = [
            (item.body_start - definition.body_start, item.end - definition.body_start)
            for item in result
            if definition.start < item.start and item.end <= definition.end
        ]
        local = _mask_ranges(definition.body, nested_bodies)
        local_imports, local_wildcards, local_errors = parse_imports(local)
        scoped.append(
            ScalaDefinition(
                fqcn=definition.fqcn,
                simple=definition.simple,
                kind=definition.kind,
                path=definition.path,
                relative=definition.relative,
                body=definition.body,
                start=definition.start,
                body_start=definition.body_start,
                end=definition.end,
                imports={**outer_imports, **local_imports},
                wildcard_imports=tuple(dict.fromkeys((*outer_wildcards, *local_wildcards))),
                import_errors=(*outer_errors, *local_errors),
            )
        )
    return scoped


def resolve_scala_name(
    raw: str,
    unit: ScalaUnit,
    definitions: dict[str, ScalaDefinition],
    definition: ScalaDefinition | None = None,
) -> str | None:
    normalized = canonical_scala_name(raw)
    imports = definition.imports if definition is not None else unit.imports
    wildcard_imports = (
        definition.wildcard_imports if definition is not None else unit.wildcard_imports
    )
    if "." in normalized:
        head, tail = normalized.split(".", 1)
        if head in imports:
            normalized = f"{imports[head]}.{tail}"
        return normalized if normalized in definitions else None
    imported = imports.get(normalized)
    if imported and imported in definitions:
        return imported
    same_package = f"{unit.package}.{normalized}" if unit.package else normalized
    if same_package in definitions:
        return same_package
    wildcard_matches = [
        f"{package}.{normalized}"
        for package in wildcard_imports
        if f"{package}.{normalized}" in definitions
    ]
    if len(wildcard_matches) == 1:
        return wildcard_matches[0]
    return None


def build_scala_index(root: Path, sources: dict[Path, str] | None = None) -> ScalaIndex:
    sources = sources if sources is not None else source_texts(root)
    units: dict[Path, ScalaUnit] = {}
    definitions_by_path: dict[Path, list[ScalaDefinition]] = {}
    definitions: dict[str, ScalaDefinition] = {}
    for path, text in sources.items():
        package_match = PACKAGE_DECLARATION.search(text)
        package = package_match.group("name") if package_match else ""
        preliminary = ScalaUnit(
            path=path,
            relative=path.relative_to(root).as_posix(),
            package=package,
            text=text,
            imports={},
            wildcard_imports=(),
            import_errors=(),
        )
        unit_definitions = _definition_bodies(preliminary)
        top_level = _mask_ranges(
            text,
            [(definition.body_start, definition.end) for definition in unit_definitions],
        )
        imports, wildcards, import_errors = parse_imports(top_level)
        unit = ScalaUnit(
            path=preliminary.path,
            relative=preliminary.relative,
            package=package,
            text=text,
            imports=imports,
            wildcard_imports=wildcards,
            import_errors=import_errors,
        )
        units[path] = unit
        definitions_by_path[path] = unit_definitions
        for definition in unit_definitions:
            previous = definitions.get(definition.fqcn)
            if previous is None:
                definitions[definition.fqcn] = definition
            else:
                definitions[definition.fqcn] = ScalaDefinition(
                    fqcn=definition.fqcn,
                    simple=definition.simple,
                    kind="companion",
                    path=definition.path,
                    relative=definition.relative,
                    body=f"{previous.body}\n{definition.body}",
                    start=min(previous.start, definition.start),
                    body_start=min(previous.body_start, definition.body_start),
                    end=max(previous.end, definition.end),
                    imports={**previous.imports, **definition.imports},
                    wildcard_imports=tuple(
                        dict.fromkeys(
                            (*previous.wildcard_imports, *definition.wildcard_imports)
                        )
                    ),
                    import_errors=(*previous.import_errors, *definition.import_errors),
                )

    constructor_callers: dict[str, set[str]] = {}
    unresolved_constructors: dict[Path, set[str]] = {}
    module_edges: dict[str, set[str]] = {}
    unresolved_module_edges: dict[str, set[str]] = {}
    direct_stateful: set[str] = set()
    for path, unit in units.items():
        for match in INSTANTIATION.finditer(unit.text):
            raw = match.group("name")
            scopes = [
                definition
                for definition in definitions_by_path[path]
                if definition.body_start <= match.start() < definition.end
            ]
            scope = min(scopes, key=lambda item: item.end - item.start) if scopes else None
            resolved = resolve_scala_name(raw, unit, definitions, scope)
            if resolved is None or resolved not in definitions:
                unresolved_constructors.setdefault(path, set()).add(
                    canonical_scala_name(raw)
                )
            else:
                constructor_callers.setdefault(resolved, set()).add(unit.relative)
        for definition in definitions_by_path[path]:
            if STATE_TOKEN.search(definition.body):
                direct_stateful.add(definition.fqcn)
            for match in MODULE_INSTANTIATION.finditer(definition.body):
                raw = match.group("name")
                resolved = resolve_scala_name(raw, unit, definitions, definition)
                if resolved is None or resolved not in definitions:
                    unresolved_module_edges.setdefault(definition.fqcn, set()).add(raw)
                else:
                    module_edges.setdefault(definition.fqcn, set()).add(resolved)
    return ScalaIndex(
        units=units,
        definitions=definitions,
        definitions_by_path=definitions_by_path,
        constructor_callers=constructor_callers,
        unresolved_constructors=unresolved_constructors,
        module_edges=module_edges,
        unresolved_module_edges=unresolved_module_edges,
        direct_stateful=direct_stateful,
    )


CALLABLE_DEFINITION = re.compile(
    r"(?m)^(?P<indent>[ \t]*)(?:@[A-Za-z_]\w*(?:\([^\n)]*\))?[ \t]+)*"
    r"(?:private(?:\[[^\]]+\])?[ \t]+)?"
    r"(?:final[ \t]+)?def[ \t]+(?P<name>[A-Za-z_]\w*)\b"
)


def _callable_regions(text: str) -> list[tuple[str, int, int]]:
    matches = list(CALLABLE_DEFINITION.finditer(text))
    result: list[tuple[str, int, int]] = []
    for match in matches:
        next_start = next(
            (item.start() for item in matches if item.start() > match.start()),
            len(text),
        )
        equals = text.find("=", match.end(), next_start)
        if equals < 0:
            continue
        base_indent = len(match.group("indent").expandtabs(2))
        depth = 0
        end = len(text)
        index = equals + 1
        while index < len(text):
            char = text[index]
            if char in "([{":
                depth += 1
            elif char in ")]}" and depth:
                depth -= 1
            if char == "\n" and depth == 0:
                line_start = index + 1
                line_end = text.find("\n", line_start)
                line_end = len(text) if line_end < 0 else line_end
                line = text[line_start:line_end]
                if line.strip():
                    indent = len(line) - len(line.lstrip(" \t"))
                    if indent <= base_indent:
                        end = line_start
                        break
            index += 1
        result.append((match.group("name"), match.start(), end))
    return result


def _without_imports(text: str) -> str:
    ranges: list[tuple[int, int]] = []
    for match in re.finditer(r"(?m)^[ \t]*import[ \t]+", text):
        index = match.end()
        depth = 0
        while index < len(text):
            char = text[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
            elif char in "\n;" and depth == 0:
                break
            index += 1
        ranges.append((match.start(), index))
    return _mask_ranges(text, ranges)


def _merge_callable(
    callables: dict[CallableKey, ScalaCallable],
    callable_: ScalaCallable,
) -> None:
    previous = callables.get(callable_.key)
    if previous is None:
        callables[callable_.key] = callable_
        return
    callables[callable_.key] = ScalaCallable(
        key=callable_.key,
        body=f"{previous.body}\n{callable_.body}",
        path=callable_.path,
        relative=callable_.relative,
        imports={**previous.imports, **callable_.imports},
        wildcard_imports=tuple(
            dict.fromkeys((*previous.wildcard_imports, *callable_.wildcard_imports))
        ),
        definition=callable_.definition or previous.definition,
    )


def build_callable_graph(
    index: ScalaIndex,
) -> tuple[
    dict[CallableKey, ScalaCallable],
    dict[CallableKey, set[CallableKey]],
    set[CallableKey],
]:
    callables: dict[CallableKey, ScalaCallable] = {}
    for path, unit in index.units.items():
        definitions = index.definitions_by_path[path]
        for definition in definitions:
            if definition.kind != "object":
                continue
            regions = _callable_regions(definition.body)
            object_body = _mask_ranges(
                definition.body,
                [(start, end) for _, start, end in regions],
            )
            object_imports, object_wildcards, _ = parse_imports(object_body)
            outer_imports = {
                **unit.imports,
                **object_imports,
            }
            outer_wildcards = tuple(
                dict.fromkeys((*unit.wildcard_imports, *object_wildcards))
            )
            _merge_callable(
                callables,
                ScalaCallable(
                    key=(definition.fqcn, "<body>"),
                    body=object_body,
                    path=path,
                    relative=unit.relative,
                    imports=outer_imports,
                    wildcard_imports=outer_wildcards,
                    definition=definition,
                ),
            )
            for name, start, end in regions:
                body = definition.body[start:end]
                local_imports, local_wildcards, _ = parse_imports(body)
                _merge_callable(
                    callables,
                    ScalaCallable(
                        key=(definition.fqcn, name),
                        body=body,
                        path=path,
                        relative=unit.relative,
                        imports={**outer_imports, **local_imports},
                        wildcard_imports=tuple(
                            dict.fromkeys((*outer_wildcards, *local_wildcards))
                        ),
                        definition=definition,
                    ),
                )

        top_level = _mask_ranges(
            unit.text,
            [(definition.start, definition.end) for definition in definitions],
        )
        for name, start, end in _callable_regions(top_level):
            body = unit.text[start:end]
            local_imports, local_wildcards, _ = parse_imports(body)
            _merge_callable(
                callables,
                ScalaCallable(
                    key=(unit.package, name),
                    body=body,
                    path=path,
                    relative=unit.relative,
                    imports={**unit.imports, **local_imports},
                    wildcard_imports=tuple(
                        dict.fromkeys((*unit.wildcard_imports, *local_wildcards))
                    ),
                    definition=None,
                ),
            )

    full_names = {
        f"{owner}.{name}" if owner else name: key
        for key in callables
        for owner, name in [key]
        if not name.startswith("<")
    }

    def resolve_reference(raw: str, caller: ScalaCallable) -> CallableKey | None:
        unit = index.units[caller.path]
        normalized = canonical_scala_name(raw)
        imported = caller.imports.get(normalized)
        if imported in full_names:
            return full_names[imported]
        if "." in normalized:
            head, tail = normalized.split(".", 1)
            if head in caller.imports:
                normalized = f"{caller.imports[head]}.{tail}"
                if normalized in full_names:
                    return full_names[normalized]
            owner_raw, name = normalized.rsplit(".", 1)
            owner = resolve_scala_name(
                owner_raw,
                unit,
                index.definitions,
                caller.definition,
            )
            return (owner, name) if owner is not None and (owner, name) in callables else None
        local = (caller.key[0], normalized)
        if local in callables:
            return local
        package_local = (unit.package, normalized)
        if package_local in callables:
            return package_local
        owner = resolve_scala_name(
            normalized,
            unit,
            index.definitions,
            caller.definition,
        )
        apply = (owner, "apply") if owner is not None else None
        return apply if apply in callables else None

    edges: dict[CallableKey, set[CallableKey]] = {}
    sinks: set[CallableKey] = set()
    for key, callable_ in callables.items():
        code = _without_imports(callable_.body)
        if "emitSystemVerilog" in code or "emitVerilog" in code:
            sinks.add(key)
        references = set(
            re.findall(r"\b[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)+", code)
        )
        references.update(re.findall(r"\b([A-Za-z_]\w*)\s*\(", code))
        nullary_names = {
            name
            for name, target in callable_.imports.items()
            if target in full_names
        }
        nullary_names.update(
            name for owner, name in callables if owner == callable_.key[0]
        )
        for name in nullary_names:
            if re.search(rf"\b{re.escape(name)}\b", code):
                references.add(name)
        for raw in references:
            resolved = resolve_reference(raw, callable_)
            if resolved is not None and resolved != key:
                edges.setdefault(key, set()).add(resolved)
    return callables, edges, sinks


def scan_emitters(
    root: Path,
    sources: dict[Path, str] | None = None,
    index: ScalaIndex | None = None,
) -> list[tuple[str, str]]:
    sources = sources if sources is not None else source_texts(root)
    index = index if index is not None else build_scala_index(root, sources)
    callables, edges, emitting_callables = build_callable_graph(index)
    changed = True
    while changed:
        changed = False
        for caller, callees in edges.items():
            if caller not in emitting_callables and callees & emitting_callables:
                emitting_callables.add(caller)
                changed = True

    emitters: set[tuple[str, str]] = set()
    for path, unit in index.units.items():
        relative = unit.relative
        qualified = lambda name: f"{unit.package}.{name}" if unit.package else name
        for match in APP_ENTRY.finditer(unit.text):
            fqcn = qualified(match.group("name"))
            if (fqcn, "<body>") in emitting_callables:
                emitters.add((fqcn, relative))
        for definition in index.definitions_by_path[path]:
            if definition.kind == "object" and (
                definition.fqcn,
                "main",
            ) in emitting_callables:
                emitters.add((definition.fqcn, relative))
        for match in AT_MAIN_ENTRY.finditer(unit.text):
            if (unit.package, match.group("name")) in emitting_callables:
                emitters.add((qualified(match.group("name")), relative))
    return sorted(emitters)


def scan_adapters(
    root: Path,
    sources: dict[Path, str] | None = None,
    index: ScalaIndex | None = None,
) -> dict[tuple[str, str], bool]:
    index = index if index is not None else build_scala_index(root, sources)
    result: dict[tuple[str, str], bool] = {}
    managed_root = root / "chisel/src/main/scala/linxcore"
    candidates = [
        definition
        for definitions in index.definitions_by_path.values()
        for definition in definitions
        if definition.path.is_relative_to(managed_root)
        and re.fullmatch(r"[A-Za-z_]\w*(?:Adapter|Wrapper|Bridge)", definition.simple)
    ]
    stateful_symbols = set(index.direct_stateful)
    changed = True
    while changed:
        changed = False
        for owner, children in index.module_edges.items():
            if owner not in stateful_symbols and children & stateful_symbols:
                stateful_symbols.add(owner)
                changed = True
    for definition in candidates:
        stateful = definition.fqcn in stateful_symbols
        if not definition.simple.endswith("Bridge") or stateful:
            result[(definition.fqcn, definition.relative)] = stateful
    return result


def discover_callers(
    root: Path,
    symbol: str,
    sources: dict[Path, str] | None = None,
    index: ScalaIndex | None = None,
) -> list[str]:
    index = index if index is not None else build_scala_index(root, sources)
    return sorted(index.constructor_callers.get(symbol, set()))


def instantiation_graph(sources: dict[Path, str]) -> dict[str, set[str]]:
    graph: dict[str, set[str]] = {}
    for text in sources.values():
        definitions = {match.group("name") for match in ANY_DEFINITION.finditer(text)}
        instantiated = {match.group("name") for match in INSTANTIATION.finditer(text)}
        for definition in definitions:
            graph.setdefault(definition, set()).update(instantiated)
    return graph


def reachable(graph: dict[str, set[str]], start: str, target: str) -> bool:
    pending = [start]
    visited: set[str] = set()
    while pending:
        current = pending.pop()
        if current == target:
            return True
        if current in visited:
            continue
        visited.add(current)
        pending.extend(graph.get(current, set()) - visited)
    return False


def validate_ndf(ndf: Any, root: Path, errors: list[str]) -> None:
    if not isinstance(ndf, dict):
        errors.append("missing NDF mapping")
        return
    layers: dict[str, list[str]] = {}
    for layer in ("L1", "L2", "L3"):
        if layer not in ndf:
            errors.append(f"missing NDF {layer}")
            layers[layer] = []
        else:
            layers[layer] = path_list(root, ndf[layer], f"NDF {layer}", errors)
    for value in layers["L1"]:
        path = Path(value)
        if not value.startswith("docs/spec/") or path.suffix != ".md":
            errors.append(f"NDF L1 path must be Markdown under docs/spec: {value}")
    for value in layers["L2"]:
        is_interface_source = (
            value.startswith("chisel/src/main/scala/linxcore/top/interface/")
            and Path(value).suffix == ".scala"
        )
        is_generated_manifest = value == "docs/chisel/generated/top-interface-manifest.json"
        if not (is_interface_source or is_generated_manifest):
            errors.append(f"NDF L2 path has invalid mechanism role: {value}")
    for value in layers["L3"]:
        is_scala_test = value.startswith("chisel/src/test/scala/") and value.endswith(".scala")
        is_python_test = value.startswith("tests/") and value.endswith(".py")
        if not (is_scala_test or is_python_test):
            errors.append(f"NDF L3 path must be an executable test: {value}")
    seen: dict[str, str] = {}
    identities: dict[tuple[int, int], tuple[str, str]] = {}
    for layer, values in layers.items():
        for value in values:
            if value in seen and seen[value] != layer:
                errors.append(f"NDF path reused across layer roles: {value}")
            seen[value] = layer
            lexical = root / value
            if lexical.is_symlink():
                errors.append(f"NDF normative path must not be a symlink: {value}")
                continue
            try:
                stat = lexical.stat()
            except OSError:
                continue
            identity = (stat.st_dev, stat.st_ino)
            previous = identities.get(identity)
            if previous is not None and previous[0] != layer:
                errors.append(
                    "NDF file identity reused across layer roles: "
                    f"{previous[1]} ({previous[0]}) and {value} ({layer})"
                )
            else:
                identities[identity] = (layer, value)
    interface_manifest = ndf.get("interface_manifest")
    if contained_path(root, interface_manifest, "NDF interface_manifest", errors):
        if interface_manifest not in layers["L2"]:
            errors.append("interface manifest must be an NDF L2 member")
        if interface_manifest != "docs/chisel/generated/top-interface-manifest.json":
            errors.append("interface manifest must use the canonical generated JSON path")


def validate_public_box(owner: dict[str, Any], root: Path, errors: list[str]) -> None:
    state_key = owner["state_key"]
    box = owner.get("public_box")
    status = owner.get("public_box_status")
    interface = owner.get("public_interface_file")
    expected_interface = PUBLIC_INTERFACES.get(box)
    if interface != expected_interface:
        errors.append(f"public interface mismatch for {state_key}")
    else:
        contained_path(root, interface, f"public interface for {state_key}", errors)
    if status == "module":
        box_file = owner.get("public_box_file")
        if box_file != PUBLIC_MODULES.get(box):
            errors.append(f"public box {box} is not a Module definition")
            return
        resolved = contained_path(root, box_file, f"public box for {state_key}", errors)
        if resolved:
            pattern = re.compile(
                rf"(?m)^\s*class\s+{re.escape(box)}\b[^\n]*extends\s+Module\b"
            )
            if not pattern.search(resolved.read_text(encoding="utf-8")):
                errors.append(f"public box {box} is not a Module definition")
    elif status == "pending":
        if box in PUBLIC_MODULES:
            errors.append(f"existing public Module {box} cannot be marked pending")
        if owner.get("public_box_file") not in (None, ""):
            errors.append(f"pending public box {box} must not name an IO Bundle as a box")
    else:
        errors.append(f"invalid public_box_status for {state_key}")


def validate_evidence(
    owner: dict[str, Any],
    entry_points: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    graph: dict[str, set[str]],
    errors: list[str],
) -> None:
    state_key = owner["state_key"]
    fixtures = set(owner.get("verification_fixtures", []))
    evidence = owner.get("production_evidence")
    if not isinstance(evidence, list) or not evidence:
        errors.append(f"missing production evidence for {state_key}")
        return
    for item in evidence:
        if not isinstance(item, dict):
            errors.append(f"production evidence for {state_key} must be an object")
            continue
        fixture = item.get("fixture")
        contained_path(root, fixture, f"production evidence fixture for {state_key}", errors)
        if fixture not in fixtures:
            errors.append(f"evidence fixture for {state_key} is not a verification fixture")
        if item.get("level") != "L3":
            errors.append(f"production evidence for {state_key} must be L3")
        status = item.get("status")
        if status not in EVIDENCE_STATUSES:
            errors.append(f"invalid evidence status for {state_key}: {status}")
        if status == "public-box-verified" and owner.get("public_box_status") != "module":
            errors.append(f"public-box evidence for {state_key} lacks a public Module")
        if status == "public-box-verified" and not reachable(
            graph,
            owner["public_box"],
            owner["canonical_owner"],
        ):
            errors.append(
                f"public-box evidence for {state_key} lacks box-to-owner reachability"
            )
        if status == "production-promoted":
            errors.append(
                "production-promoted is disabled until Task 17 artifact schema "
                "is checker-owned"
            )
            errors.append(
                f"production-promoted evidence for {state_key} lacks a "
                "checker-owned activation artifact"
            )


def validate_deletion_targets(
    owner: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    index: ScalaIndex,
    errors: list[str],
) -> None:
    state_key = owner["state_key"]
    targets = owner.get("deletion_targets")
    if not isinstance(targets, list):
        errors.append(f"deletion targets for {state_key} must be a list")
        return
    for target in targets:
        if not isinstance(target, dict):
            errors.append(f"deletion target for {state_key} must be an object")
            continue
        path = target.get("path")
        symbol = target.get("symbol")
        resolved = contained_path(root, path, f"deletion target for {state_key}", errors)
        if not isinstance(symbol, str) or "." not in symbol:
            errors.append(f"deletion target for {state_key} needs an exact FQCN symbol")
            continue
        if resolved and not source_defines_fqcn(resolved, symbol):
            errors.append(f"deletion target {path} does not define {symbol}")
        declared = target.get("active_callers")
        if not isinstance(declared, list) or not all(isinstance(item, str) for item in declared):
            errors.append(f"deletion target {symbol} active_callers must be a list")
            continue
        for caller in declared:
            contained_path(root, caller, f"declared caller for {symbol}", errors)
        discovered = discover_callers(root, symbol, sources, index)
        if sorted(declared) != discovered:
            errors.append(
                f"deletion target {symbol} caller declaration mismatch: "
                f"declared={sorted(declared)} discovered={discovered}"
            )
        status = target.get("status")
        if status == "planned-active":
            if not discovered:
                errors.append(f"planned-active deletion target {symbol} has no discovered callers")
        elif status == "deletion-ready":
            if discovered:
                errors.append(f"deletion-ready target {symbol} still has discovered callers")
            simple = symbol.rsplit(".", 1)[-1]
            uncertain: set[str] = set()
            for unit in index.units.values():
                if any(symbol in item or simple in item for item in unit.import_errors):
                    uncertain.add(unit.relative)
                unresolved = index.unresolved_constructors.get(unit.path, set())
                if any(
                    reference == symbol or reference.rsplit(".", 1)[-1] == simple
                    for reference in unresolved
                ):
                    uncertain.add(unit.relative)
            if uncertain:
                errors.append(
                    f"deletion-ready target {symbol} has unresolved Scala references: "
                    f"{sorted(uncertain)}"
                )
        else:
            errors.append(f"invalid deletion status for {symbol}")


def validate_adapters(
    manifest: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    index: ScalaIndex,
    errors: list[str],
) -> None:
    discovered = scan_adapters(root, sources, index)
    declared_items = manifest.get("adapters")
    if not isinstance(declared_items, list):
        errors.append("adapters must be a root-level list")
        declared_items = []
    declared: dict[tuple[str, str], dict[str, Any]] = {}
    for item in declared_items:
        if not isinstance(item, dict):
            errors.append("adapter declaration must be an object")
            continue
        key = (item.get("symbol"), item.get("path"))
        if not all(isinstance(value, str) and value for value in key):
            errors.append("adapter declaration needs exact symbol and path")
            continue
        declared[key] = item
        resolved = contained_path(root, key[1], f"adapter {key[0]}", errors)
        simple = key[0].rsplit(".", 1)[-1]
        if resolved and not source_defines(resolved, simple):
            errors.append(f"adapter path {key[1]} does not define {key[0]}")
    expected = {
        (symbol, path): {
            "symbol": symbol,
            "path": path,
            "role": role,
            "stateful": stateful,
            "status": "planned-deletion" if role == "legacy-state-owner" else "active",
            "owner_domain": domain,
            "cutover_task": cutover,
            "deletion_target": deletion_target,
        }
        for symbol, path, role, stateful, domain, cutover, deletion_target in MANAGED_BOUNDARIES
    }
    normalized_declared = {
        key: {
            field: item.get(field)
            for field in (
                "symbol", "path", "role", "stateful", "status",
                "owner_domain", "cutover_task", "deletion_target",
            )
        }
        for key, item in declared.items()
    }
    if normalized_declared != expected:
        errors.append("managed boundary inventory mismatch")
    deletion_symbols_by_domain = {
        owner.get("state_key"): {
            target.get("symbol")
            for target in owner.get("deletion_targets", [])
            if isinstance(target, dict)
        }
        for owner in manifest.get("owners", [])
        if isinstance(owner, dict)
    }
    for item in expected.values():
        if item["deletion_target"] not in deletion_symbols_by_domain.get(
            item["owner_domain"], set()
        ):
            errors.append(
                f"managed boundary {item['symbol']} lacks its exact deletion target"
            )
    for key, stateful in discovered.items():
        simple = key[0].rsplit(".", 1)[-1]
        item = declared.get(key) or declared.get((simple, key[1]))
        if item is None:
            label = "managed boundary" if key[0].endswith("Bridge") else "adapter"
            errors.append(f"undeclared {label} {simple} in {key[1]}")
            continue
        if item.get("stateful") is not stateful:
            errors.append(f"adapter {simple} stateful declaration mismatch")
        role = item.get("role")
        if role == "compatibility":
            if stateful:
                errors.append(f"stateful adapter {key[0]}")
            for child in sorted(index.unresolved_module_edges.get(key[0], set())):
                errors.append(f"adapter {simple} has unresolved child {child}")
        elif role == "legacy-state-owner":
            if item.get("status") != "planned-deletion":
                errors.append(f"legacy adapter {key[0]} must be planned for deletion")
            if item.get("owner_domain") not in STATE_DOMAINS:
                errors.append(f"legacy adapter {key[0]} needs a closed owner domain")
            if not isinstance(item.get("cutover_task"), int):
                errors.append(f"legacy adapter {key[0]} needs a cutover task")
        else:
            errors.append(f"invalid adapter role for {key[0]}")
    for key in sorted(set(declared) - set(discovered)):
        errors.append(f"declared adapter not discovered: {key[0]} in {key[1]}")


def validate_entry_points(
    manifest: dict[str, Any],
    root: Path,
    sources: dict[Path, str],
    index: ScalaIndex,
    errors: list[str],
) -> dict[str, Any]:
    entry_points = manifest.get("entry_points")
    if not isinstance(entry_points, dict):
        errors.append("entry_points must be an object")
        return {}
    if "non_production_patterns" in entry_points:
        errors.append("manifest-controlled emitter patterns are forbidden")
    production = entry_points.get("production", [])
    non_production = entry_points.get("non_production", [])
    if not isinstance(production, list) or not isinstance(non_production, list):
        errors.append("entry point registrations must be lists")
        return entry_points
    registrations: dict[tuple[str, str], str] = {}
    for classification, entries in (("production", production), ("non-production", non_production)):
        for entry in entries:
            if not isinstance(entry, dict):
                errors.append(f"{classification} entry point must be an object")
                continue
            name, path = entry.get("name"), entry.get("path")
            contained_path(root, path, f"{classification} emitter {name}", errors)
            if isinstance(name, str) and isinstance(path, str):
                registrations[(name, path)] = entry.get("classification", "")
                if classification == "production" and (name, path) in EMITTER_INVENTORY:
                    errors.append(
                        f"checker-owned non-production emitter cannot be promoted: {name}"
                    )
            if classification == "production" and not entry.get("production_evidence"):
                errors.append(f"missing production evidence for emitter {name}")
    discovered_emitters = set(scan_emitters(root, sources, index))
    for name, path in discovered_emitters:
        expected_class = EMITTER_INVENTORY.get((name, path))
        registration = registrations.get((name, path))
        if expected_class is None:
            errors.append(f"unknown emitter {name} in {path}")
            errors.append(f"unknown emitter {name.rsplit('.', 1)[-1]} in {path}")
        elif registration != expected_class:
            errors.append(
                f"emitter registration mismatch for {name}: "
                f"expected {expected_class}, got {registration or 'missing'}"
            )
    for name, path in sorted(set(registrations) - discovered_emitters):
        errors.append(f"registered emitter not discovered: {name} in {path}")
    if registrations != EMITTER_INVENTORY:
        errors.append("emitter inventory mismatch")
    return entry_points


def validate(manifest: dict[str, Any], root: Path) -> list[str]:
    errors: list[str] = []
    sources = source_texts(root)
    index = build_scala_index(root, sources)
    graph = instantiation_graph(sources)
    if manifest.get("schema_version") != 2:
        errors.append("schema_version must be 2")
    validate_ndf(manifest.get("ndf"), root, errors)
    entry_points = validate_entry_points(manifest, root, sources, index, errors)

    owners = manifest.get("owners")
    if not isinstance(owners, list) or not owners:
        errors.append("owners must be a non-empty list")
        owners = []
    state_keys = [owner.get("state_key") for owner in owners if isinstance(owner, dict)]
    for state_key, count in Counter(state_keys).items():
        if state_key and count > 1:
            errors.append(f"duplicate owner for state domain {state_key}")
    for state_key in sorted(set(STATE_DOMAINS) - set(state_keys)):
        errors.append(f"missing state domain {state_key}")
    for state_key in sorted(set(state_keys) - set(STATE_DOMAINS)):
        errors.append(f"unknown state domain {state_key}")

    for owner_index, owner in enumerate(owners):
        if not isinstance(owner, dict):
            errors.append(f"owner[{owner_index}] must be an object")
            continue
        state_key = owner.get("state_key")
        if state_key not in STATE_DOMAINS:
            continue
        subsystem, canonical_symbol, _primary_file = STATE_DOMAINS[state_key]
        if owner.get("subsystem") != subsystem:
            errors.append(f"subsystem mismatch for {state_key}")
        if owner.get("canonical_owner") != canonical_symbol:
            errors.append(f"canonical owner mismatch for {state_key}")
        mechanisms = path_list(
            root,
            owner.get("mechanism_files"),
            f"mechanism files for {state_key}",
            errors,
        )
        expected_mechanisms = DOMAIN_MECHANISMS[state_key]
        expected_paths = [path for _symbol, path in expected_mechanisms]
        if mechanisms != expected_paths:
            errors.append(f"mechanism set mismatch for {state_key}")
        for symbol, mechanism in expected_mechanisms:
            resolved = contained_path(root, mechanism, f"mechanism for {state_key}", errors)
            if resolved and not source_defines(resolved, symbol):
                errors.append(
                    f"mechanism {mechanism} does not define checker-owned symbol {symbol}"
                )
        if owner.get("public_box") != subsystem:
            errors.append(f"public box mismatch for {state_key}")
        validate_public_box(owner, root, errors)
        path_list(root, owner.get("active_callers"), f"active callers for {state_key}", errors)
        path_list(
            root,
            owner.get("verification_fixtures"),
            f"verification fixtures for {state_key}",
            errors,
        )
        validate_evidence(owner, entry_points, root, sources, graph, errors)
        if not isinstance(owner.get("cutover_task"), int):
            errors.append(f"missing cutover task for {state_key}")
        if owner.get("adapters") not in (None, []):
            errors.append(f"owner-local adapters must be empty for {state_key}")
        validate_deletion_targets(owner, root, sources, index, errors)

    subsystems = {owner.get("subsystem") for owner in owners if isinstance(owner, dict)}
    missing_subsystems = sorted(REQUIRED_SUBSYSTEMS - subsystems)
    if missing_subsystems:
        errors.append(f"missing subsystem state categories: {', '.join(missing_subsystems)}")
    validate_adapters(manifest, root, sources, index, errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--manifest", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    manifest_path = (
        args.manifest.resolve()
        if args.manifest
        else root / DEFAULT_MANIFEST.relative_to(ROOT)
    )
    try:
        manifest = load_manifest(manifest_path)
    except ValueError as error:
        print(f"production-owner-manifest: ERROR: {error}", file=sys.stderr)
        return 1
    errors = validate(manifest, root)
    if errors:
        for error in errors:
            print(f"production-owner-manifest: ERROR: {error}", file=sys.stderr)
        return 1
    owners = manifest["owners"]
    emitters = sum(len(items) for items in manifest["entry_points"].values())
    adapters = len(manifest["adapters"])
    print(
        f"production-owner-manifest: {len(owners)} closed owners, "
        f"{emitters} classified emitters, {adapters} declared adapters, "
        "NDF L1/L2/L3 roles mapped"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
