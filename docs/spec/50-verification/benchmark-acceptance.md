# Benchmark acceptance

Task 18 accepts only a naturally executed ELF through emitted W4 `TOP` with
external memory, UART, and finisher behavior owned by the harness. Instruction
or commit replay is not accepted as a natural run.

The bounded scalar linx-avs smoke is currently `BLOCKED_EXTERNAL_ARTIFACT`:
the canonical source entry is
`/Users/zhoubot/linx-isa/avs/compiler/linx-llvm/tests/run.sh`, its pinned clang
is absent, and the `avs` tree contains no ELF. The missing compiler is not
built and no fixture ELF may substitute for this gate. Once the pinned ELF is
available, acceptance requires finisher pass, nonzero IFU/CTU/OOO/IEX/LSU
activation, followed by the separately defined bounded architectural
cross-check. The natural harness itself declares `comparison_kind=none` and
does not manufacture a zero-mismatch claim.
