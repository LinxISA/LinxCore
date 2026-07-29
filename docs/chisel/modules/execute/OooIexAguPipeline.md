# OooIexAguPipeline

## Purpose

`OooIexAguPipeline` is the typed scalar-load address owner after exact I2-to-E1
transfer. D1 converts encoding-specific address fields into
`OooMemoryControl`; E1 consumes only that normalized control and retains one
complete load request until a later LSU accepts it.

Source and test owners:

- `chisel/src/main/scala/linxcore/ooo/OooIexAguPipeline.scala`
- `chisel/src/test/scala/linxcore/ooo/OooIexAguPipelineSpec.scala`

The ownership boundary follows the scalar address portion of
`tools/LinxCoreModel/model/iex/pipe/agu_pipe.cpp::runE1Load` and
`tools/LinxCoreModel/isa/calculate/load/Load.cpp::{CalcLoadAddr,CalcLoadPCRAddr}`.
The P1/I1/I2/E1 placement also follows `Documents/a.txt`, without importing
ARM address, exception, or memory-ordering semantics.

## D1 memory control

The canonical OOO uop and IEX payload now carry:

- `BaseIndex`, `BaseOffset`, or `PcOffset` address mode;
- byte access size `1/2/4/8`;
- signed-load extension intent;
- normalized byte offset;
- register-index transform (`Identity`, `SignExtend32`, `ZeroExtend32`, or
  `Negate`) and shift amount;
- exact address/data source masks.

Scaled immediates are converted to byte offsets in D1. `_U` unscaled and PCR
offsets remain byte quantities. Register-index modifier bits are decoded once
into typed controls. AGU therefore never reads raw instruction bits or repeats
ISA field extraction.

The controls are preserved from `OooDecodedUop` through the memory-backed IEX
payload sidecar into the selected execute transaction.

## Ownership and timing

E1 accepts only an exact AGU-class row whose generated recipe is a one-request
`ScalarLoad` owned by LSU. Memory controls, source mask, destination count,
immediate/PC requirements, and live member identity must all agree.

The byte address is computed in E1 and retained with access size, sign-extension
intent, destination identity, and the complete execute transaction. No memory
request, load-generation allocation, speculative wakeup, RF write, ROB
completion, or trace event occurs until a later LSU accepts this retained
request.

Exact grouped-ROB recovery and exact input-load-generation cancellation remove
the retained request. An unrelated STID or stale generation does not.

## Supported subset

The static supported set is every generated recipe satisfying all of:

- dispatch disposition;
- `ScalarLoad` recipe kind;
- AGU dispatch class;
- LSU side-effect owner;
- exactly one memory request.

This currently covers 32-bit scalar byte/half/word/doubleword register-index,
scaled immediate, `_U` unscaled immediate, and PCR loads. Catalog entries still
misclassified as scalar ALU/IEX, including compact and high-long PCR loads, are
not silently pulled into AGU; their recipe generation must be corrected first.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only OooD1DecodeSpec
bash tools/chisel/run_chisel_tests.sh --only OooIexAguPipelineSpec
```

D1 UT proves scaled doubleword offset, unscaled word offset, PCR byte offset,
and register index transform/shift normalization. AGU UT covers generated
catalog alignment, all three address modes, signed-32 index transformation,
retained backpressure, drain/refill, exact recovery, stale/exact load cancel,
and malformed memory/class rejection.

## Remaining gaps

- static connection to the implemented `OooIexLoadUnit` generation/tracking
  boundary;
- load hit/miss return, size extraction, sign/zero extension, bypass, and
  speculative/committed wakeup;
- miss cancellation, replay, poison, and exact repick;
- store AGU/STD child-specific source projection and joined LSU request;
- correction of compact/high-long memory recipes;
- static-top integration, synthesis timing, and workload evidence.
