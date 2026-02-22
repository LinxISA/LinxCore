from __future__ import annotations

from .opcode_meta_gen import OPCODE_META_BY_MNEMONIC, OpcodeMeta


_DECODE32 = tuple(m for m in OPCODE_META_BY_MNEMONIC.values() if m.source_file == "insn32.decode")


def decode32_meta(insn: int) -> OpcodeMeta | None:
    word = insn & 0xFFFFFFFF
    best: OpcodeMeta | None = None
    best_bits = -1
    for meta in _DECODE32:
        if (word & meta.mask) != meta.match:
            continue
        bits = int(meta.mask).bit_count()
        if bits > best_bits:
            best = meta
            best_bits = bits
    return best
