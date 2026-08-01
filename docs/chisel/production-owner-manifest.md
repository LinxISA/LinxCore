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
TOP wiring. They do not own architectural state. `IFU`, `CTU`, `OOO`, `IEX`,
`LSU`, and `DTU` are the permanent public boxes; a public interface Bundle is
not a compatibility owner.

## Entry-point classification

There is no production `TOP` emitter yet. `Reduced*`, every `*Probe`, and every
old `LinxCore*Top` emitter are verification or legacy entry points and cannot
promote an owner. `EmitD1DecodeRenameROBIngress` is also a standalone fixture.
The unqualified `Elaborate` object in `LinxCoreTop.scala` is explicitly legacy.
Task 17 must introduce the sole production `TOP` emitter and delete the old top
chain atomically.

## Machine-readable manifest

The JSON block is normative for the checker. `deletion_targets.active_callers`
must remain empty; a target with a caller is not deletable and fails the gate.
Adapters must explicitly declare `stateful: false`.

```json production-owner-manifest
{
  "schema_version": 1,
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
    "non_production_patterns": [
      "*Reduced*",
      "*Probe*",
      "EmitLinxCore*Top"
    ],
    "non_production": [
      {
        "name": "EmitD1DecodeRenameROBIngress",
        "path": "chisel/src/main/scala/linxcore/backend/D1DecodeRenameROBIngress.scala"
      },
      {
        "name": "Elaborate",
        "path": "chisel/src/main/scala/linxcore/top/LinxCoreTop.scala"
      },
      {
        "name": "EmitLinxCoreTopXcheck",
        "path": "chisel/src/main/scala/linxcore/top/LinxCoreTop.scala"
      }
    ]
  },
  "owners": [
    {
      "subsystem": "IFU",
      "state_key": "instruction_cache",
      "canonical_owner": "ISideL1I",
      "mechanism_files": ["chisel/src/main/scala/linxcore/frontend/ISideL1I.scala"],
      "public_box": "IFU",
      "public_box_file": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ifu/IFUISideSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ifu/IFUISideSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/top/LinxCoreTop.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "IFU",
      "state_key": "predictor_history",
      "canonical_owner": "BSide",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ifu/BSide.scala", "chisel/src/main/scala/linxcore/frontend/BSideHistoryQueue.scala", "chisel/src/main/scala/linxcore/frontend/BSidePredictionPipeline.scala"],
      "public_box": "IFU",
      "public_box_file": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ifu/IFUPredictionSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ifu/IFUPredictionSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/top/LinxCoreFrontendTraceTop.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "IFU",
      "state_key": "ifu_recovery_redirect",
      "canonical_owner": "IFURecovery",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ifu/IFURecovery.scala", "chisel/src/main/scala/linxcore/frontend/IfuRedirectArbiter.scala"],
      "public_box": "IFU",
      "public_box_file": "chisel/src/main/scala/linxcore/ifu/IFU.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/frontend/LinxCoreIfu.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ifu/IFURecoverySpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ifu/IFURecoverySpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/frontend/IfuBackendFeedbackBridgeProbe.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "CTU",
      "state_key": "instruction_buffer",
      "canonical_owner": "linxcore.ctu.InstructionBuffer",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ctu/InstructionBuffer.scala", "chisel/src/main/scala/linxcore/ctu/TemplateDecode.scala", "chisel/src/main/scala/linxcore/ctu/TemplateExpand.scala"],
      "public_box": "CTU",
      "public_box_file": "chisel/src/main/scala/linxcore/ctu/CTU.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ctu/CTU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ctu/CTUSpec.scala", "chisel/src/test/scala/linxcore/ctu/InstructionBufferSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ctu/CTUSpec.scala", "level": "L3", "status": "public-box-verified"}],
      "cutover_task": 17,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/frontend/InstructionBuffer.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "rename_p",
      "canonical_owner": "PRename",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/PRename.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/RENU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/RENUSpec.scala", "chisel/src/test/scala/linxcore/ooo/RENUAtomicSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/RENUAtomicSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/ooo/OooPRename.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "rename_tu",
      "canonical_owner": "TURename",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/TURename.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/RENU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/RENUSpec.scala", "chisel/src/test/scala/linxcore/ooo/TURenameSequenceSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/TURenameSequenceSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/ooo/OooTURename.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "rob",
      "canonical_owner": "ROB",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/ROB.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/ooo/OooS1GroupedRob.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "brob",
      "canonical_owner": "BROB",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/BROB.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/ooo/OooBrob.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "commit",
      "canonical_owner": "CommitControl",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/CommitControl.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOORobCommitSpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/ooo/OooRobStoreCommitOwner.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "recovery",
      "canonical_owner": "RecoveryControl",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/RecoveryControl.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OOORecoverySpec.scala", "level": "L3", "status": "standalone-verified"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/recovery/RecoveryCleanupControl.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "OOO",
      "state_key": "dispatch_reservation",
      "canonical_owner": "OooD3ReservationAllocator",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooD3ReservationAllocator.scala", "chisel/src/main/scala/linxcore/ooo/OooHierarchicalFreeSlotSelect.scala", "chisel/src/main/scala/linxcore/ooo/OooDispatch.scala"],
      "public_box": "OOO",
      "public_box_file": "chisel/src/main/scala/linxcore/ooo/OOO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooO3RenameCoordinator.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooD3ReservationAllocatorSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooD3ReservationAllocatorSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 11,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/ooo/OooO3RenameCoordinator.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "issue_queue",
      "canonical_owner": "OooIexIssue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexIssue.scala", "chisel/src/main/scala/linxcore/ooo/OooIexIssueBlockMatrix.scala"],
      "public_box": "IEX",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexPipeline.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexIssueSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 13,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/execute/ReducedScalarIssueQueue.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "physical_register_data_readiness",
      "canonical_owner": "OooIexOperandFiles",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexOperandFiles.scala", "chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala"],
      "public_box": "IEX",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexPipeline.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooIexOperandFilesSpec.scala", "chisel/src/test/scala/linxcore/execute/ScalarGPRFileSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexOperandFilesSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 13,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/execute/ScalarGPRFile.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "IEX",
      "state_key": "execution_pipeline",
      "canonical_owner": "OooIexExecutionPipeline",
      "mechanism_files": ["chisel/src/main/scala/linxcore/ooo/OooIexExecutionPipeline.scala", "chisel/src/main/scala/linxcore/ooo/OooIexTerminalFabric.scala"],
      "public_box": "IEX",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/IEXIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexPipeline.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/ooo/OooIexExecutionPipelineSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/ooo/OooIexExecutionPipelineSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 13,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/execute/ReducedScalarAluExecute.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "lsu_pipeline",
      "canonical_owner": "ScalarLSU",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala", "chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/ooo/OooIexScalarLoadStorePath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "store_queue",
      "canonical_owner": "STQEntryBank",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/STQEntryBank.scala", "chisel/src/main/scala/linxcore/lsu/STQDataBank.scala", "chisel/src/main/scala/linxcore/lsu/STQCommitQueue.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/StoreDispatchSTQPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/STQEntryBankSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/STQEntryBankSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ReducedStoreCommitFreeOwner.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "store_commit_buffer",
      "canonical_owner": "SCBRowBank",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/SCBRowBank.scala", "chisel/src/main/scala/linxcore/lsu/STQSCBCommitBackend.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/STQSCBCommitPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/SCBRowBankSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/SCBRowBankSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ReducedStoreResidentForward.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "load_inflight_queue",
      "canonical_owner": "LoadInflightQueue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LoadInflightQueue.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LoadInflightQueueSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LoadInflightQueueSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayLiqAllocPath.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "load_resolve_queue",
      "canonical_owner": "LoadResolveQueue",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/LoadResolveQueue.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/LoadResolveQueueSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/LoadResolveQueueSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ReducedLoadReplayRelaunchQueue.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "memory_dependency",
      "canonical_owner": "ScalarLSUMDBPath",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/ScalarLSUMDBPath.scala", "chisel/src/main/scala/linxcore/lsu/MDBConflictDetect.scala", "chisel/src/main/scala/linxcore/lsu/MDBSSIT.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/ScalarLSUMDBPathSpec.scala", "chisel/src/test/scala/linxcore/lsu/MDBConflictDetectSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/ScalarLSUMDBPathSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ReducedStoreWaitReplayChiselPathProbe.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "cache",
      "canonical_owner": "ScalarL1D",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala", "chisel/src/main/scala/linxcore/lsu/LoadMissQueue.scala", "chisel/src/main/scala/linxcore/lsu/LoadRefillTransport.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/ScalarL1DSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/ScalarL1DSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/lsu/ScalarL1DProbe.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "LSU",
      "state_key": "lsu_recovery",
      "canonical_owner": "ScalarLSURecoveryBoundary",
      "mechanism_files": ["chisel/src/main/scala/linxcore/lsu/ScalarLSURecoveryBoundary.scala", "chisel/src/main/scala/linxcore/lsu/ScalarLSURecoverySource.scala"],
      "public_box": "LSU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/LSUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/lsu/ScalarLSU.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/lsu/ScalarLSUSpec.scala", "level": "L3", "status": "mechanism-verified-cutover-pending"}],
      "cutover_task": 15,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/recovery/ScalarRedirectRecoverySourceProbe.scala", "active_callers": []}],
      "adapters": []
    },
    {
      "subsystem": "DTU",
      "state_key": "trace_debug_performance_observation",
      "canonical_owner": "DTU",
      "mechanism_files": ["chisel/src/main/scala/linxcore/commit/CommitTraceMonitor.scala", "chisel/src/main/scala/linxcore/top/interface/DTU.scala"],
      "public_box": "DTU",
      "public_box_file": "chisel/src/main/scala/linxcore/top/interface/DTUIO.scala",
      "active_callers": ["chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala"],
      "verification_fixtures": ["chisel/src/test/scala/linxcore/commit/CommitTraceMonitorSpec.scala"],
      "production_evidence": [{"fixture": "chisel/src/test/scala/linxcore/commit/CommitTraceMonitorSpec.scala", "level": "L3", "status": "observation-mechanism-verified-cutover-pending"}],
      "cutover_task": 16,
      "deletion_targets": [{"path": "chisel/src/main/scala/linxcore/top/LinxCoreBenchmarkAutonomousTop.scala", "active_callers": []}],
      "adapters": []
    }
  ]
}
```

## Cutover rule

A later task may change an owner only by updating its production mechanism,
public-box reachability, all active callers, L3 evidence, and deletion targets
atomically. A `standalone-verified` or `mechanism-verified-cutover-pending` row
is not production replacement evidence. No adapter may retain a queue, map,
ROB, cache, predictor, readiness table, or recovery transaction.
