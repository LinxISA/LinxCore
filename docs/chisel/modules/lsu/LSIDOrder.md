# LSIDOrder

`linxcore.common.LSIDOrder` is the canonical combinational age helper for the
full Linx scalar memory-order identity. It operates on equal-width `UInt`
values and does not depend on ROB, STQ, LIQ, or cache capacity.

## Contract

- `equal(lhs, rhs)` is exact full-width equality.
- `less(lhs, rhs)` implements modulo serial-number order: `lhs` is older when
  the nonzero forward distance from `lhs` to `rhs` is less than half of the
  numeric domain.
- `lessEqual` combines exact equality with `less`.
- `ambiguous` identifies the forbidden half-range separation where neither
  operand can be ordered safely.

The finite live memory window must remain below half of the LSID domain. A
consumer must not replace this helper with plain unsigned comparison or with a
ROBID projection. Cross-block age still comes from the same-STID BROB ring;
`LSIDOrder` orders memory operations only within the selected block or typed
recovery domain.

## Model relationship

LinxCoreModel currently stores LSID in its `ROBID` type and uses `LessROBID`.
R670 preserves the model's wrap-qualified intent but upgrades Chisel to the
golden `lsidWidth` domain already owned by decode and ROB. This is a deliberate
Linx microarchitecture improvement, not an ARM architectural behavior.

## Verification

```bash
bash tools/chisel/run_chisel_tests.sh --only LSIDOrder
```

Tests cover ordinary order, wrap across `0xffffffff -> 0`, equality, explicit
half-range ambiguity, and 32-bit Chisel elaboration.
