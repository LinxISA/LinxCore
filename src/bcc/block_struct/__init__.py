"""Block-structured core models (BROB/ROB).

These modules provide a cycle-accurate, parameterized reference for:
- BROB (Block Reorder Buffer): BID lifetime tracking / in-order retire.
- ROB  (uop ROB): RID lifetime tracking / in-order retire for scalar uops.

They are intended to be used by pycircuit stage implementations and unit tests.
"""

from .types import BlockType, BrobEntryState, RobEntryState, CompletionSource
from .brob import BrobModel, BrobAllocReq, BrobCompleteEvent, BrobRetireEvent
from .rob import RobModel, RobAllocUop, RobCompleteEvent, RobRetireEvent
