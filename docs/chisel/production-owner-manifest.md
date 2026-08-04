# LinxCore production-owner cutover manifest

This document freezes the one-owner decision before the atomic
`TOP -> IFU -> CTU -> OOO -> IEX -> LSU` cutovers. It is both a review page and
an executable manifest consumed by
`tools/chisel/check_production_owner_manifest.py`. Later cutover tasks must
update the owner, caller, evidence, and deletion records in the same commit as
the boundary change.

## Authority and NDF projection

- **L1 contract homes** state observable ownership and interface obligations in
  `docs/spec/10-architecture/ownership.md`, `docs/spec/10-architecture/top.md`,
  and the eight `docs/spec/30-interfaces/` homes.
- **L2 mechanism evidence** is the production mechanism/file ownership below
  each public box plus the generated typed Bundle projection in
  `docs/chisel/generated/top-interface-manifest.json`.
- **L3 verification evidence** is the executable owner/contract fixture named
  on every row. `standalone-verified` means the owner is proved in isolation
  but is not yet promoted through canonical `TOP`; promotion requires the
  later task's nonzero generated-RTL/workload evidence.

`TOPIO.scala` and the generated interface manifest are the contract homes for
future TOP wiring. They do not own architectural state. `IFU`, `CTU`, `OOO`, `IEX`,
`LSU`, and `DTU` are the permanent public boxes; a public interface Bundle is
not a compatibility owner.

## Entry-point classification

There is no production `TOP` emitter yet. Every retained `*Probe` is a
verification entry point and cannot promote an owner.
`EmitD1DecodeRenameROBIngress` and `EmitOOOIEXLSUActivationProbe` are retained
standalone fixtures. Task 18 must introduce the sole production `TOP` emitter.

## Machine-readable manifest

The JSON block is normative for the checker. Every deletion target names an
exact Scala symbol. A `planned-active` target must declare exactly the callers
discovered in main Scala; a `deletion-ready` target is accepted only when both
sets are empty. Compatibility adapters must be stateless. Pre-cutover legacy
state owners whose historical names end in `Adapter` are recorded separately
as `legacy-state-owner`, must report their discovered state honestly, and must
carry a deletion task; they are never compatibility adapters or promotion
evidence.

```json production-owner-manifest
{
  "schema_version": 2,
  "completed_cutover_tasks": [13, 15, 16],
  "ndf": {
    "L1": [
      "docs/spec/10-architecture/ownership.md",
      "docs/spec/10-architecture/top.md",
      "docs/spec/30-interfaces/ifu-ctu.md",
      "docs/spec/30-interfaces/ctu-ooo.md",
      "docs/spec/30-interfaces/ooo-iex.md",
      "docs/spec/30-interfaces/iex-lsu.md",
      "docs/spec/30-interfaces/recovery.md",
      "docs/spec/30-interfaces/commit.md",
      "docs/spec/30-interfaces/dtu.md",
      "docs/spec/30-interfaces/memory.md"
    ],
    "L2": [
      "chisel/src/main/scala/linxcore/top/interface/TOPIO.scala",
      "docs/chisel/generated/top-interface-manifest.json"
    ],
    "L3": [
      "chisel/src/test/scala/linxcore/top/interface/TopInterfaceSpec.scala",
      "chisel/src/test/scala/linxcore/top/interface/InterfaceManifestSpec.scala",
      "tests/test_production_owner_manifest.py"
    ],
    "interface_manifest": "docs/chisel/generated/top-interface-manifest.json"
  },
  "entry_points": {
    "production": [],
    "non_production": [
      {"name": "linxcore.bctrl.EmitBrobOrderStateProbe", "path": "chisel/src/main/scala/linxcore/bctrl/BrobOrderStateProbe.scala", "classification": "probe"},
      {"name": "linxcore.bctrl.EmitBrobStoreCountPublisherProbe", "path": "chisel/src/main/scala/linxcore/bctrl/BrobStoreCountPublisherProbe.scala", "classification": "probe"},
      {"name": "linxcore.bctrl.EmitBrobStoreRangeStateProbe", "path": "chisel/src/main/scala/linxcore/bctrl/BrobStoreRangeStateProbe.scala", "classification": "probe"},
      {"name": "linxcore.backend.EmitD1DecodeRenameROBIngress", "path": "chisel/src/main/scala/linxcore/backend/D1DecodeRenameROBIngress.scala", "classification": "fixture"},
      {"name": "linxcore.frontend.EmitD1InstructionDecodeProbe", "path": "chisel/src/main/scala/linxcore/frontend/D1InstructionDecodeProbe.scala", "classification": "probe"},
      {"name": "linxcore.backend.EmitDecodeLoadStoreIdAssignProbe", "path": "chisel/src/main/scala/linxcore/backend/DecodeLoadStoreIdAssignProbe.scala", "classification": "probe"},
      {"name": "linxcore.rename.EmitGPRRenameStidProbe", "path": "chisel/src/main/scala/linxcore/rename/GPRRenameStidProbe.scala", "classification": "probe"},
      {"name": "linxcore.frontend.EmitIfuBackendFeedbackBridgeProbe", "path": "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridgeProbe.scala", "classification": "probe"},
      {"name": "linxcore.top.EmitIfuLineMemoryBridgeProbe", "path": "chisel/src/main/scala/linxcore/top/IfuLineMemoryBridgeProbe.scala", "classification": "probe"},
      {"name": "linxcore.top.EmitLinxCoreCompositionProbe", "path": "chisel/src/main/scala/linxcore/top/LinxCoreCompositionProbe.scala", "classification": "probe"},
      {"name": "linxcore.top.EmitLinxCoreFrontendFetchTraceTop", "path": "chisel/src/main/scala/linxcore/top/LinxCoreFrontendFetchTraceTop.scala", "classification": "legacy-top"},
      {"name": "linxcore.top.EmitLinxCoreFrontendTraceTop", "path": "chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala", "classification": "legacy-top"},
      {"name": "linxcore.frontend.EmitLinxCoreIfuThroughputProbe", "path": "chisel/src/main/scala/linxcore/frontend/LinxCoreIfuThroughputProbe.scala", "classification": "probe"},
      {"name": "linxcore.lsu.EmitLoadMissQueueProbe", "path": "chisel/src/main/scala/linxcore/lsu/LoadMissQueueProbe.scala", "classification": "probe"},
      {"name": "linxcore.iex.EmitOOOIEXLSUActivationProbe", "path": "chisel/src/main/scala/linxcore/iex/EmitOOOIEXLSUActivationProbe.scala", "classification": "fixture"},
      {"name": "linxcore.lsu.EmitLoadRefillTransportProbe", "path": "chisel/src/main/scala/linxcore/lsu/LoadRefillTransportProbe.scala", "classification": "probe"},
      {"name": "linxcore.recovery.EmitRecoveryClassMergeProbe", "path": "chisel/src/main/scala/linxcore/recovery/RecoveryClassMergeProbe.scala", "classification": "probe"},
      {"name": "linxcore.recovery.EmitRecoveryCleanupROBProbe", "path": "chisel/src/main/scala/linxcore/recovery/RecoveryCleanupROBProbe.scala", "classification": "probe"},
      {"name": "linxcore.recovery.EmitRecoveryProducerProbe", "path": "chisel/src/main/scala/linxcore/recovery/RecoveryProducerProbe.scala", "classification": "probe"},
      {"name": "linxcore.rob.EmitReducedCommitROB", "path": "chisel/src/main/scala/linxcore/rob/EmitReducedCommitROB.scala", "classification": "reduced"},
      {"name": "linxcore.lsu.EmitScalarL1DScbProbe", "path": "chisel/src/main/scala/linxcore/lsu/ScalarL1DScbProbe.scala", "classification": "probe"},
      {"name": "linxcore.lsu.EmitScalarLSULoadPathReturnProbe", "path": "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPathReturnProbe.scala", "classification": "probe"},
      {"name": "linxcore.lsu.EmitScalarLSULoadReturnQueueProbe", "path": "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnQueueProbe.scala", "classification": "probe"},
      {"name": "linxcore.lsu.EmitScalarLSUMDBPathProbe", "path": "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPathProbe.scala", "classification": "probe"}
    ]
  },
  "adapters": [
    {
      "symbol": "linxcore.top.IfuWindowLineFillAdapter",
      "path": "chisel/src/main/scala/linxcore/top/IfuWindowLineFillAdapter.scala",
      "role": "legacy-state-owner",
      "stateful": true,
      "status": "planned-deletion",
      "owner_domain": "instruction_cache",
      "cutover_task": 17,
      "deletion_target": "linxcore.top.IfuWindowLineFillAdapter"
    },
    {
      "symbol": "linxcore.ifu.ISideMemoryAdapter",
      "path": "chisel/src/main/scala/linxcore/ifu/ISide.scala",
      "role": "legacy-state-owner",
      "stateful": true,
      "status": "planned-deletion",
      "owner_domain": "instruction_cache",
      "cutover_task": 17,
      "deletion_target": "linxcore.ifu.ISideMemoryAdapter"
    },
    {
      "symbol": "linxcore.frontend.IfuBackendFeedbackBridge",
      "path": "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridge.scala",
      "role": "legacy-state-owner", "stateful": true, "status": "planned-deletion",
      "owner_domain": "ifu_recovery_redirect", "cutover_task": 17,
      "deletion_target": "linxcore.frontend.IfuBackendFeedbackBridge"
    },
    {
      "symbol": "linxcore.rename.ScalarDecodeRenameBridge",
      "path": "chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala",
      "role": "legacy-state-owner", "stateful": true, "status": "planned-deletion",
      "owner_domain": "rename_p", "cutover_task": 11,
      "deletion_target": "linxcore.rename.ScalarDecodeRenameBridge"
    },
    {
      "symbol": "linxcore.rename.ScalarTURenameBridge",
      "path": "chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala",
      "role": "legacy-state-owner", "stateful": true, "status": "planned-deletion",
      "owner_domain": "rename_tu", "cutover_task": 11,
      "deletion_target": "linxcore.rename.ScalarTURenameBridge"
    },
    {
      "symbol": "linxcore.top.IfuLineMemoryBridge",
      "path": "chisel/src/main/scala/linxcore/top/IfuLineMemoryBridge.scala",
      "role": "legacy-state-owner", "stateful": true, "status": "planned-deletion",
      "owner_domain": "instruction_cache", "cutover_task": 17,
      "deletion_target": "linxcore.top.IfuLineMemoryBridge"
    }
  ],
  "owners": [
    {
      "subsystem": "IFU",
      "state_key": "instruction_cache",
      "canonical_owner": "ISideL1I",
      "mechanism_files": ["chisel/src/main/scala/linxcore/frontend/ISideL1I.scala"],
      "public_box": "IFU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IFUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ifu/IFUISideSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ifu/IFUISideSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [
        {"path": "chisel/src/main/scala/linxcore/top/IfuWindowLineFillAdapter.scala", "symbol": "linxcore.top.IfuWindowLineFillAdapter", "status": "deletion-ready", "active_callers": []},
        {"path": "chisel/src/main/scala/linxcore/ifu/ISide.scala", "symbol": "linxcore.ifu.ISideMemoryAdapter", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/ifu/ISide.scala"]},
        {"path": "chisel/src/main/scala/linxcore/top/IfuLineMemoryBridge.scala", "symbol": "linxcore.top.IfuLineMemoryBridge", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/top/IfuLineMemoryBridgeProbe.scala", "chisel/src/main/scala/linxcore/top/LinxCoreComposition.scala"]}
      ],
      "adapters": []
    },
    {
      "subsystem": "IFU",
      "state_key": "predictor_history",
      "canonical_owner": "BSide",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ifu/BSide.scala", "chisel/src/main/scala/linxcore/frontend/BSideHistoryQueue.scala", "chisel/src/main/scala/linxcore/frontend/BSidePredictionPipeline.scala"],
      "public_box": "IFU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IFUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ifu/IFUPredictionSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ifu/IFUPredictionSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala", "symbol": "linxcore.top.LinxCoreFrontendTraceTop", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala"]}],
      "adapters": []
    },
    {
      "subsystem": "IFU",
      "state_key": "ifu_recovery_redirect",
      "canonical_owner": "IFURecovery",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ifu/IFURecovery.scala", "chisel/src/main/scala/linxcore/frontend/IfuRedirectArbiter.scala"],
      "public_box": "IFU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IFUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ifu/IFURecoverySpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ifu/IFURecoverySpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [
        {"path": "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridgeProbe.scala", "symbol": "linxcore.frontend.IfuBackendFeedbackBridgeProbe", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridgeProbe.scala"]},
        {"path": "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridge.scala", "symbol": "linxcore.frontend.IfuBackendFeedbackBridge", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridgeProbe.scala", "chisel/src/main/scala/linxcore/top/LinxCoreComposition.scala"]}
      ],
      "adapters": []
    },
    {
      "subsystem": "CTU",
      "state_key": "instruction_buffer",
      "canonical_owner": "InstructionBuffer",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ctu/InstructionBuffer.scala", "chisel/src/main/scala/linxcore/ctu/TemplateDecode.scala", "chisel/src/main/scala/linxcore/ctu/TemplateExpand.scala"],
      "public_box": "CTU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ctu/CTU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/CTUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ctu/CTU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ctu/CTUSpec.scala", "chisel/src/test/scala/linxcore/ctu/InstructionBufferSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ctu/CTUSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/frontend/InstructionBuffer.scala", "symbol": "linxcore.frontend.InstructionBuffer", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"]}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "rename_p",
      "canonical_owner": "PRename",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/PRename.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/RENU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/RENUSpec.scala", "chisel/src/test/scala/linxcore/ooo/RENUAtomicSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/RENUAtomicSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [
        {"path": "chisel/src/main/scala/linxcore/rename/ScalarDecodeRenameBridge.scala", "symbol": "linxcore.rename.ScalarDecodeRenameBridge", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala"]}
      ],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "rename_tu",
      "canonical_owner": "TURename",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/TURename.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/RENU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/RENUSpec.scala", "chisel/src/test/scala/linxcore/ooo/TURenameSequenceSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/TURenameSequenceSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [
        {"path": "chisel/src/main/scala/linxcore/rename/ScalarTURenameBridge.scala", "symbol": "linxcore.rename.ScalarTURenameBridge", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/backend/DecodeRenameROBPath.scala"]}
      ],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "memory_order",
      "canonical_owner": "OooMemoryOrderAllocator",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooMemoryOrderAllocator.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OOOD3S1Graph.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooMemoryOrderAllocatorSpec.scala", "chisel/src/test/scala/linxcore/ooo/OOOMemoryOrderIntegrationSpec.scala", "chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOOMemoryOrderIntegrationSpec.scala", "case": "publishes DEC memory demand as stable full-order metadata", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 13,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "rob",
      "canonical_owner": "ROB",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/ROB.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OOOD3S1Graph.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 11,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "brob",
      "canonical_owner": "BROB",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/BROB.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OOOD3S1Graph.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 11,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "commit",
      "canonical_owner": "CommitControl",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/CommitControl.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OOOD3S1Graph.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala", "case": "commits one completed canonical OOO publication exactly once", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "case": "CommitControl requires exact NFRDY and never repeats a resident head", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 11,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "recovery",
      "canonical_owner": "RecoveryControl",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OOOD3S1Graph.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala", "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 11,
      "deletion_targets": [
        {"path": "chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala", "symbol": "linxcore.recovery.RecoveryCleanupControl", "status": "planned-active", "active_callers": ["chisel/src/main/scala/linxcore/recovery/RecoveryFabric.scala"]}
      ],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "dispatch_reservation",
      "canonical_owner": "OooD3ReservationAllocator",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooD3ReservationAllocator.scala", "chisel/src/main/scala/linxcore/ooo/OooHierarchicalFreeSlotSelect.scala", "chisel/src/main/scala/linxcore/ooo/OooDispatch.scala"],
      "public_box": "OOO",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/OOOIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/Dispatch.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOODispatchSpec.scala", "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOOIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 11,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "issue_queue",
      "canonical_owner": "OooIexIssue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala", "chisel/src/main/scala/linxcore/ooo/OooIexIssueBlockMatrix.scala"],
      "public_box": "IEX",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/iex/IEX.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexIssueP1Fabric.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala", "chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/iex/IEXMechanismSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 13,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "physical_register_data_readiness",
      "canonical_owner": "OooIexOperandFiles",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala", "chisel/src/main/scala/linxcore/ooo/OooIexPDataFile.scala"],
      "public_box": "IEX",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/iex/IEX.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexIssueReadFabric.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooIexOperandFilesSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexOperandFilesSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 13,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "execution_pipeline",
      "canonical_owner": "OooIexExecutionPipeline",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala", "chisel/src/main/scala/linxcore/ooo/OooIexTerminalFabric.scala"],
      "public_box": "IEX",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/iex/IEX.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/iex/IEX.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooIexExecutionPipelineSpec.scala", "chisel/src/test/scala/linxcore/ooo/OooIexTerminalFabricSpec.scala", "chisel/src/test/scala/linxcore/ooo/OooIexCanonicalLoadOwnershipSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexExecutionPipelineSpec.scala", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexTerminalFabricSpec.scala", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexCanonicalLoadOwnershipSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 13,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "memory_transaction_and_initial_attempt_serial",
      "canonical_owner": "OooIexIssue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala"],
      "public_box": "IEX",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/iex/IEX.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexIssueP1Fabric.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/iex/IEXPrivateIngressSpec.scala", "chisel/src/test/scala/linxcore/iex/LoadIexIssuePipelineSpec.scala", "chisel/src/test/scala/linxcore/ooo/OooIexCanonicalLoadOwnershipSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/iex/IEXPrivateIngressSpec.scala", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/iex/LoadIexIssuePipelineSpec.scala", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexCanonicalLoadOwnershipSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 13,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "lsu_pipeline",
      "canonical_owner": "LSU",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LSU.scala", "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala", "chisel/src/main/scala/linxcore/lsu/STQSCBCommitBackend.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/iex/EmitOOOIEXLSUActivationProbe.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala", "chisel/src/test/scala/linxcore/lsu/LoadMissQueueSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala", "level": "L3", "status": "public-box-verified"}, {"fixture": "chisel/src/test/scala/linxcore/lsu/LoadMissQueueSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 15,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "store_queue",
      "canonical_owner": "STQEntryBank",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala", "chisel/src/main/scala/linxcore/lsu/STQDataBank.scala", "chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala", "chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala", "chisel/src/main/scala/linxcore/ooo/OooIexStoreStqFabric.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/STQEntryBankSpec.scala", "chisel/src/test/scala/linxcore/lsu/IEXLSUIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/IEXLSUIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 15,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "store_commit_buffer",
      "canonical_owner": "SCBRowBank",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala", "chisel/src/main/scala/linxcore/lsu/STQSCBCommitBackend.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/STQSCBCommitBackend.scala", "chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala", "chisel/src/main/scala/linxcore/lsu/ScalarL1DScbProbe.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/SCBRowBankSpec.scala", "chisel/src/test/scala/linxcore/lsu/IEXLSUIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/IEXLSUIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 15,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "load_inflight_queue",
      "canonical_owner": "LoadInflightQueue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala", "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPathProbe.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LoadInflightQueueSpec.scala", "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 15,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "load_resolve_queue",
      "canonical_owner": "LoadResolveQueue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LoadResolveQueueSpec.scala", "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 15,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "memory_dependency",
      "canonical_owner": "ScalarLSUMDBPath",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala", "chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala", "chisel/src/main/scala/linxcore/lsu/MDBSSIT.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala", "chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPathProbe.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/ScalarLSUMDBPathSpec.scala", "chisel/src/test/scala/linxcore/lsu/MDBConflictDetectSpec.scala", "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/ScalarLSUMDBPathSpec.scala", "level": "L3", "status": "standalone-verified"}, {"fixture": "chisel/src/test/scala/linxcore/lsu/MDBConflictDetectSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 15,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "cache",
      "canonical_owner": "ScalarL1D",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala", "chisel/src/main/scala/linxcore/lsu/LoadMissQueue.scala", "chisel/src/main/scala/linxcore/lsu/LoadRefillTransport.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarL1DScbProbe.scala", "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/ScalarL1DSpec.scala", "chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala", "chisel/src/test/scala/linxcore/lsu/LSUMemorySpec.scala", "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 16,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "data_translation",
      "canonical_owner": "DSideTranslation",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/Translation.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/LSU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LSUMemorySpec.scala", "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 16,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "lower_memory_transactions",
      "canonical_owner": "LSULowerTransactionRecovery",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LSURecovery.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/LSU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LSUMemorySpec.scala", "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 16,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "lsu_recovery",
      "canonical_owner": "LSU",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LSU.scala"],
      "public_box": "LSU",
      "public_box_status": "module",
      "public_box_file": "chisel/src/main/scala/linxcore/lsu/LSU.scala",
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/iex/EmitOOOIEXLSUActivationProbe.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LSULoadSpec.scala", "chisel/src/test/scala/linxcore/lsu/LSUMemorySpec.scala", "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LSUIntegrationSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 16,
      "deletion_targets": [],
      "adapters": []
    },
    {
      "subsystem": "DTU",
      "state_key": "trace_debug_performance_observation",
      "canonical_owner": "CommitTraceMonitor",
      "mechanism_files": ["chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala", "chisel/src/main/scala/linxcore/top/interface/DTU.scala"],
      "public_box": "DTU",
      "public_box_status": "pending",
      "public_box_file": null,
      "public_interface_file": "chisel/src/main/scala/linxcore/top/interface/DTUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/rob/ROBEntryBank.scala", "chisel/src/main/scala/linxcore/rob/ReducedCommitROB.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/commit/CommitTraceMonitorSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/commit/CommitTraceMonitorSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 17,
      "deletion_targets": [],
      "adapters": []
    }
  ]
}
```

## Cutover rule

A later task may change an owner only by updating its production mechanism,
public-box reachability, all active callers, L3 evidence, and deletion targets
atomically. A `standalone-verified` or `mechanism-verified-cutover-pending` row
is not production replacement evidence. A compatibility adapter may retain no
queue, map, ROB, cache, predictor, readiness table, or recovery transaction.
Any pre-existing stateful legacy owner named `Adapter` stays planned for the
same atomic deletion as its closed state domain.
