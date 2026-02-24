from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum


class BlockType(IntEnum):
    """LinxCore-defined block type enum.

    This is decoded from BSTART fields and is *not* tied to any QEMU numeric encoding.
    The default set matches current engine plan, but is DSE-extensible.
    """

    SCALAR = 0
    CMD = 1

    VEC = 2
    TMA = 3
    CUBE = 4
    TAU = 5


def blocktype_needs_engine(bt: BlockType) -> bool:
    return bt in (BlockType.VEC, BlockType.TMA, BlockType.CUBE, BlockType.TAU)


class BrobEntryState(IntEnum):
    FREE = 0
    ALLOC = 1
    ISSUED = 2
    COMPLETE = 3


class RobEntryState(IntEnum):
    FREE = 0
    ALLOC = 1
    COMPLETE = 2


class CompletionSource(IntEnum):
    """Completion event source.

    Scalar completion corresponds to ROB EOB retire (scalar_done).
    Engine completion corresponds to engine done (engine_done).
    """

    SCALAR = 0
    ENGINE = 1


@dataclass(frozen=True)
class TrapPayload:
    exception: bool
    trapno: int = 0
    traparg0: int = 0
    bi: int = 0

    @staticmethod
    def none() -> "TrapPayload":
        return TrapPayload(False, 0, 0, 0)
