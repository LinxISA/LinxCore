# ScalarLSULoadReturnPipeline

## Source Mapping

- Chisel: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadReturnPipeline.scala`
- Parent: `chisel/src/main/scala/linxcore/lsu/ScalarLSULoadPath.scala`
- Tests: `chisel/src/test/scala/linxcore/lsu/ScalarLSULoadReturnPipelineSpec.scala`
- Generated proof: `tools/chisel/run_chisel_scalar_lsu_load_path_return_probe.sh`
- Model: `model/LinxCoreModel/model/iex/iex.cpp`,
  `IEX::receiveFromLSU` and `IEX::setMemData`;
  `model/LinxCoreModel/model/iex/pipe/lda_pipe.cpp`,
  `LDAPipe::move`, `LDAPipe::runW1`, `LDAPipe::runW2`, and `LDAPipe::flush`
- Contract IDs: `LC-MA-MEM-001`, `LC-MA-HAZ-001`, `LC-MA-RES-001`

## Purpose

This is the canonical registered IEX owner after the scoped scalar LRET bank.
It replaces a raw external drain-ready boundary with exact ROB validation,
parameterized W1/W2 residency, and one atomic W2 side-effect rendezvous.

## Contract

- One W1 and W2 lane exists per `loadReturnPipeCount` entry.
- A queue head is admitted only when its exact ROB row is present and one W1
  lane can retain it. A present row marked `NeedFlush` is consumed as stale;
  an absent row holds the queue head.
- Fair ingress selection prevents one available W1 lane from being selected
  forever while other lanes remain unused.
- W1 advances only when its paired W2 lane is empty or completing.
- W2 resolve is always required. GPR writeback is required for a valid GPR
  destination. Normal memory wakeup is required only for a non-speculative,
  non-stack return. The slot clears only when all required sinks are ready.
- Every stage retains PE/STID/TID, BID/GID/RID/load-LSID, PC/address/size,
  destination, source traces, data, selected source pipe, and wakeup state.
- Typed precise recovery freezes movement and prunes matching W1/W2 entries.
  Hard reset/start/restart clears all lanes.

## Scope

The queue, stage, arbitration, recovery, and atomic-rendezvous mechanisms are
ISA-neutral. Linx identity and typed block recovery replace any foreign ISA
architectural state. ARM paired-load completion, exception levels, condition
flags, exclusives, barriers, acquire/release policy, and return state are not
part of this owner.

The reduced timing top still owns the physical single-pipe ROB/RF/wakeup
arbiters. Connecting those live sinks to this canonical output is the next
integration boundary; cache/miss and cross-line return sources remain staged.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadReturnPipelineSpec`
- `bash tools/chisel/run_chisel_tests.sh --only ScalarLSULoadPathSpec`
- `bash tools/chisel/run_chisel_scalar_lsu_load_path_return_probe.sh`
