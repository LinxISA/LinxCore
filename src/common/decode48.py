from __future__ import annotations

from .opcode_meta_gen import OPCODE_META_BY_MNEMONIC, OpcodeMeta


_DECODE48 = tuple(m for m in OPCODE_META_BY_MNEMONIC.values() if m.source_file == "insn48.decode")


def decode48_meta(insn: int) -> OpcodeMeta | None:
    word = insn & ((1 << 64) - 1)
    best: OpcodeMeta | None = None
    best_bits = -1
    for meta in _DECODE48:
        if (word & meta.mask) != meta.match:
            continue
        bits = int(meta.mask).bit_count()
        if bits > best_bits:
            best = meta
            best_bits = bits
    return best
