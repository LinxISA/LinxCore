# Control, Safety, Interfaces, and Verification

This document defines the control-safety surface, observability interfaces, and verification expectations for the LinxCore draft.

## 1 Control-Flow Safety

Every architectural control-flow target MUST point at a legal block start marker. Legal targets include:

- explicit `BSTART.*`;
- compressed `C.BSTART.*`;
- extended `HL.BSTART.*`;
- template block markers such as `FENTRY`, `FEXIT`, `FRET.RA`, and `FRET.STK`.

Branching into the interior of a coupled block, into a decoupled `BodyTPC`, or to an invalid fallthrough location must raise a precise control-flow-integrity exception.

In LinxCore microarchitecture, branch recovery targets that are not legal block starts raise `TRAP_BRU_RECOVERY_NOT_BSTART (0x0000B001)` precisely. The ISA manual describes architectural `E_BLOCK(EC_CFI)` reporting for CFI failures.

## 2 Boundary Authority

Architectural redirect selection belongs to the block boundary:

- BRU computes condition and target data;
- `SETC.*` updates block condition/target state;
- correction metadata is stored with epoch/checkpoint context;
- boundary commit chooses the next architectural block PC;
- ROB ordering prevents younger wrong-path state from committing.

No execution pipe may perform an invisible architectural PC rewrite.

## 3 Trace Interface

Commit trace must preserve enough information to compare model, pyCircuit, RTL, and emulator behavior.

Required commit fields from the current interface contract:

- cycle;
- pc;
- insn;
- wb_valid;
- wb_rd;
- wb_data;
- mem_valid;
- mem_addr;
- mem_wdata;
- mem_rdata;
- mem_size;
- trap_valid;
- trap_cause;
- next_pc.

For LinxCore block-aware verification, trace should also preserve:

- `rid`;
- `bid`;
- `lsid` for memory uops;
- boundary type;
- block type;
- `sob`/`eob`;
- command tag where present;
- engine completion/trap response metadata where present.

## 4 pyCircuit Interface

The pyCircuit interface is versioned by SemVer in the LinxCore interface documentation. Stage boundaries and module ownership are part of the contract.

Environment controls:

- `PYC_COMMIT_TRACE`;
- `PYC_BOOT_PC`;
- `PYC_MEM_BYTES`;
- `PYC_MAX_CYCLES`.

The frontend/decode/backend stage hierarchy must remain inspectable. Wrappers may compose stages, but must not hide stage-local architectural state in anonymous glue.

## 5 LinxCoreModel Interface

LinxCoreModel is the executable reference for:

- BFU behavior;
- CUBE behavior;
- ELF loading;
- direct boot;
- MMIO finisher behavior;
- selected tile/vector/reference behavior used by bring-up.

Invalid model states are not architectural defaults. Missing local-pipe ownership, invalid BFU pipe states, unsupported CUBE conversions, and unsupported tile fill/element forms should fail fast in debug/reference execution.

When reporting model evidence, do not claim a `gfsim` pass unless the ELF also passed the required QEMU lane for the same report.

## 6 Verification Matrix Coverage

The current architecture verification matrix includes contract IDs for:

- architecture document structure;
- pipeline staging;
- hazard/replay;
- block control;
- privilege;
- MMU;
- interrupts;
- memory ordering;
- engine integration;
- forward progress;
- pyCircuit interfaces;
- trace schema;
- sync files;
- LinxCoreModel alignment.

Verification must show coverage of both instruction precision and block precision.

## 7 Required Gate Families

Representative gates from the architecture contract include:

```bash
python3 tools/isa/build_golden.py --profile v0.56 --check
python3 tools/isa/validate_spec.py --profile v0.56
python3 tools/bringup/check_avs_contract.py --matrix avs/linx_avs_v1_test_matrix.yaml
python3 tools/bringup/check_avs_profile_closure.py --matrix avs/linx_avs_v1_test_matrix.yaml --status avs/linx_avs_v1_test_matrix_status.json --tier pr
python3 tools/bringup/check_sail_model.py
python3 tools/isa/check_canonical_v056.py --root .
python3 tools/bringup/check_linxcore_arch_contract.py --root . --strict --require-mkdocs
```

LinxCore-local verification should additionally exercise:

- branch target legality;
- `BSTART`/`BSTOP` boundary redirects;
- `BID` wrap and flush ordering;
- `T`/`U` lifetime and release;
- LSID-ordered scalar memory;
- precise trap and interrupt entry;
- engine command completion and flush.

## 8 Metadata Handling

These documents intentionally omit author names, personal metadata, private contact data, and operational labels that are not required for the technical contract. Technical identifiers such as signal names, register names, instruction names, and source paths are preserved because they are required for engineering precision.

## 9 Open Questions

- Should block-aware trace fields be mandatory in the base commit trace schema, or emitted through a separate block-event sideband?
- Which gates should be required for every documentation-only update versus every RTL behavior update?
- Should `TRAP_BRU_RECOVERY_NOT_BSTART` be visible in public trace, or normalized to ISA-level CFI reporting before trace emission?
