# BrobLiveBidResolver

## Sources

- Chisel: `chisel/src/main/scala/linxcore/bctrl/BrobLiveBidResolver.scala`
- Tests: `chisel/src/test/scala/linxcore/bctrl/BrobLiveBidResolverSpec.scala`
- Integrated owner: `chisel/src/main/scala/linxcore/bctrl/BrobOrderState.scala`

## Role

`BrobLiveBidResolver` converts an external canonical BID slot into the unique
internal wrap-qualified pointer in one STID's live BROB window. It is the age
authority for cleanup. It does not trust migration-era upper BID transport bits
and does not compare canonical slot values as unsigned ages.

The selected window is defined by `headPointer[stid]` and
`liveCount[stid]`. The resolver enumerates offsets `[0, liveCount)`, forms each
internal pointer modulo `pointerWidth`, and compares only its low
`log2(entries)` slot bits with `candidateBid`.

## Parameters

| Parameter | Contract |
|---|---|
| `entries` | Power-of-two BROB capacity and canonical BID namespace. |
| `pointerWidth` | Internal pointer width; greater than `log2(entries)` so wrap history is explicit. |
| `stidWidth` | Width of the external STID selector. |
| `stidCount` | Number of independently owned live windows. |

## Result

- `matchValid` is true only for exactly one live match.
- `resolvedPointer` is that internal pointer.
- `distance` is its bounded offset from the selected head.
- `stidInRange`, `matchCount`, and `ambiguous` expose integration errors.

At most `entries` consecutive pointers may be live, so a valid window cannot
contain the same canonical slot twice. The module asserts this invariant and
rejects zero-match requests without mutating state.

## Verification

- `bash tools/chisel/run_chisel_tests.sh --only BrobLiveBidResolverSpec`
- `bash tools/chisel/run_chisel_tests.sh --only BrobOrderStateSpec`
- `bash tools/chisel/run_chisel_brob_order_state_probe.sh`

The generated order-state probe proves resolution through `[14,15,0]` and a
legacy transported value whose upper bits disagree while its canonical slot
still resolves to the correct live pointer.

## Scope

This is ISA-neutral bounded-ring machinery specialized by Linx STID, BID, and
block recovery semantics. It imports no ARM exception levels, condition flags,
barrier encodings, exclusives, or other foreign architectural behavior.
