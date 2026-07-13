# ScalarL1D

## Ownership

`chisel/src/main/scala/linxcore/lsu/ScalarL1D.scala` is the golden scalar L1D
array owner. The reduced pyCircuit L1D remains an adapter and may not redefine
cache state, replacement, permission, refill, or recovery behavior.

## State

`l1dSets`, `l1dWays`, and `lineBytes` are independent `ScalarLsuParams`.
Every way retains an aligned line address, full-line data, valid, writable,
dirty, and LRU-age state. Reset initializes all ways invalid. Linx typed
recovery and backend restart do not enter this module because physical cache
state is not speculative block state.

## Access Contract

- One scalar load lookup supplies the active LIQ phase at E2.
- One committed SCB lookup distinguishes tag hit from writable hit.
- A writable SCB hit applies its byte mask and marks the line dirty.
- An accepted SCB ownership-upgrade response grants writable capability to
  the matching resident line before retry. It does not allocate a missing
  line without refill data.
- Refill has array-port priority. New load and SCB accesses wait while a refill
  is presented.
- Duplicate refill preserves resident data and dirty state. The resident data
  is returned to LIQ instead of stale response bytes.
- New refill selects an invalid way first, then the least recently used way.
  Any valid victim is held on the eviction interface until accepted.

## Boundary

The owner stores neutral read/write/dirty coherence capability. It does not
define an external coherence protocol, memory class, translation/protection,
cache maintenance, platform reset, or missing-line write-refill transport. It
does not implement ARM exception levels, memory types, exclusives, barriers,
or acquire/release behavior.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only ScalarL1DSpec`
- `bash tools/chisel/run_chisel_scalar_l1d_probe.sh`
- `bash tools/chisel/run_chisel_scalar_l1d_scb_probe.sh`
- `bash tools/chisel/run_chisel_scalar_lsu_load_path_return_probe.sh`
