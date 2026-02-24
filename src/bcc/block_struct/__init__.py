"""Block-structured core models (BROB/ROB).

These modules provide a cycle-accurate, parameterized reference for:
- BROB (Block Reorder Buffer): BID lifetime tracking / in-order retire.
- ROB  (uop ROB): RID lifetime tracking / in-order retire for scalar uops.

They are intended to be used by pycircuit stage implementations and unit tests.
"""

from .types import (
    BlockType,
    BrobEntryState,
    CompletionSource,
    RobEntryState,
    TrapPayload,
    blocktype_needs_engine,
)
from .brob import BrobAllocReq, BrobCompleteEvent, BrobModel, BrobRetireEvent
from .rob import RobAllocUop, RobCompleteEvent, RobModel, RobRetireEvent
